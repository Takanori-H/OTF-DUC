package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.gr.StrategyState;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;

/** Versioned streaming interchange for native MTSA artifacts, never Java object deserialization. */
public final class NativeSnapshotIO {
    private static final int MAGIC = 0x4d445331;
    public static final int VERSION = 1;
    private NativeSnapshotIO() { }

    public static void write(Path directory, NativeSynthesisSnapshot snapshot) throws IOException {
        snapshot.validate();
        if (snapshot.plant == null) throw new IllegalArgumentException("Cannot save a partial FULL input");
        Path destination = directory.toAbsolutePath().normalize();
        if (Files.exists(destination)) throw new FileAlreadyExistsException(destination.toString());
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempDirectory(destination.getParent(), ".native-snapshot-");
        boolean complete = false;
        try {
            Path data = temporary.resolve("models.bin");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(data)))) {
                out.writeInt(MAGIC); out.writeInt(VERSION);
                out.writeInt(snapshot.components.size());
                for (CompactState c : snapshot.components) writeCompact(out, c);
                writeStrings(out, snapshot.controllable);
                writeMts(out, snapshot.plant, Long::longValue);
                out.writeInt(snapshot.winning.size());
                for (Long state : new TreeSet<>(snapshot.winning)) out.writeLong(state);
                out.writeInt(snapshot.tuples.size());
                for (Long state : new TreeSet<>(snapshot.tuples.keySet())) {
                    out.writeLong(state);
                    for (int s : snapshot.tuples.get(state)) out.writeInt(s);
                }
                writeMts(out, snapshot.controller, s -> s.getState());
                writeCompact(out, snapshot.output);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", "mtsa-native-differential");
            manifest.put("version", VERSION);
            manifest.put("semantics", "deterministic-fixed-safety-gr-true-v1");
            manifest.put("data_sha256", digest(data));
            manifest.put("game_states", snapshot.plant.getStates().size());
            manifest.put("controller_states", snapshot.controller.getStates().size());
            Files.write(temporary.resolve("manifest.json"), JSONObject.toJSONString(manifest).getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(temporary.resolve("models.bin"));
                Files.deleteIfExists(temporary.resolve("manifest.json"));
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static String verifiedDigest(Path directory) throws IOException {
        try (Reader reader = Files.newBufferedReader(directory.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            JSONObject manifest = (JSONObject) new JSONParser().parse(reader);
            if (!"mtsa-native-differential".equals(manifest.get("format"))
                    || !Long.valueOf(VERSION).equals(manifest.get("version"))
                    || !"deterministic-fixed-safety-gr-true-v1".equals(manifest.get("semantics"))) {
                throw new IOException("Unsupported native snapshot format/semantics");
            }
            String hash = digest(directory.resolve("models.bin"));
            if (!hash.equals(manifest.get("data_sha256"))) throw new IOException("Snapshot checksum mismatch");
            return hash;
        } catch (org.json.simple.parser.ParseException | ClassCastException ex) {
            throw new IOException("Invalid native snapshot manifest", ex);
        }
    }

    /** FULL does not materialize the old plant or rebuild its game. */
    public static NativeSynthesisSnapshot read(Path directory, boolean retainAnalysis) throws IOException {
        verifiedDigest(directory);
        return readVerified(directory, retainAnalysis);
    }

    /** Package-private to avoid hashing a multi-GB input twice within a single restore request. */
    static NativeSynthesisSnapshot readVerified(Path directory, boolean retainAnalysis) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("models.bin"))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Invalid snapshot header");
            int componentCount = count(in);
            if (componentCount == 0) throw new IOException("Snapshot has no environment");
            List<CompactState> components = new ArrayList<>();
            for (int i = 0; i < componentCount; i++) components.add(readCompact(in));
            Set<String> controls = new HashSet<>(readStrings(in));
            MTS<Long, String> plant = readMts(in, Long::valueOf, retainAnalysis);
            Set<Long> winning = new HashSet<>();
            int n = count(in);
            for (int i = 0; i < n; i++) if (!winning.add(in.readLong())) throw new IOException("Duplicate winning state");
            Map<Long, int[]> tuples = new HashMap<>();
            n = count(in);
            for (int i = 0; i < n; i++) {
                long state = in.readLong(); int[] tuple = new int[componentCount];
                for (int j = 0; j < componentCount; j++) tuple[j] = in.readInt();
                if (tuples.put(state, tuple) != null) throw new IOException("Duplicate mapped state");
            }
            MTS<StrategyState<Long, Integer>, String> controller = readMts(in, s -> new StrategyState<>(s, 1), true);
            CompactState output = readCompact(in);
            if (in.read() != -1) throw new IOException("Unexpected trailing snapshot data");
            return new NativeSynthesisSnapshot(components, controls, plant,
                    retainAnalysis ? NativeSynthesisSnapshot.rebuildGame(plant, controls) : null,
                    winning, controller, output, tuples);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Inconsistent native snapshot", ex);
        }
    }

    public static String digest(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            try (InputStream in = Files.newInputStream(file)) {
                int n; while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : md.digest()) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }

    private static void writeStrings(DataOutputStream out, Collection<String> values) throws IOException {
        out.writeInt(values.size()); for (String value : values) out.writeUTF(value);
    }
    private static List<String> readStrings(DataInputStream in) throws IOException {
        int n = count(in); List<String> values = new ArrayList<>();
        for (int i = 0; i < n; i++) values.add(in.readUTF()); return values;
    }
    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0) throw new IOException("Negative record count"); return n;
    }
    private static void writeCompact(DataOutputStream out, CompactState c) throws IOException {
        out.writeUTF(c.name); out.writeInt(c.maxStates); out.writeInt(c.endseq);
        writeStrings(out, Arrays.asList(c.alphabet));
        for (int s = 0; s < c.maxStates; s++) {
            List<EventState> edges = new ArrayList<>();
            if (c.states[s] != null) {
                Enumeration<?> e = c.states[s].elements();
                while (e.hasMoreElements()) edges.add((EventState) e.nextElement());
            }
            out.writeInt(edges.size());
            for (EventState edge : edges) { out.writeInt(edge.getEvent()); out.writeInt(edge.getNext()); }
        }
    }
    private static CompactState readCompact(DataInputStream in) throws IOException {
        CompactState c = new CompactState(in.readUTF()); c.maxStates = count(in); c.endseq = in.readInt();
        c.alphabet = readStrings(in).toArray(new String[0]); c.states = new EventState[c.maxStates];
        if (c.maxStates == 0 || c.alphabet.length == 0) throw new IOException("Empty compact model");
        for (int s = 0; s < c.maxStates; s++) {
            int n = count(in);
            for (int j = 0; j < n; j++) {
                int action = in.readInt(), target = in.readInt();
                if (action < 0 || action >= c.alphabet.length || target < -1 || target >= c.maxStates) {
                    throw new IOException("Invalid compact transition");
                }
                c.states[s] = EventStateUtils.add(c.states[s], new EventState(action, target));
            }
        }
        return c;
    }
    private static <S> void writeMts(DataOutputStream out, MTS<S, String> model, ToLongFunction<S> id) throws IOException {
        out.writeLong(id.applyAsLong(model.getInitialState()));
        List<String> actions = new ArrayList<>(new TreeSet<>(model.getActions())); writeStrings(out, actions);
        Map<String, Integer> actionIds = new HashMap<>();
        for (int i = 0; i < actions.size(); i++) actionIds.put(actions.get(i), i);
        List<S> states = new ArrayList<>(model.getStates()); states.sort(Comparator.comparingLong(id));
        out.writeInt(states.size()); for (S state : states) out.writeLong(id.applyAsLong(state));
        for (S state : states) {
            out.writeInt(model.getTransitions(state, MTS.TransitionType.REQUIRED).size());
            for (Pair<String, S> edge : model.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                out.writeInt(actionIds.get(edge.getFirst())); out.writeLong(id.applyAsLong(edge.getSecond()));
            }
        }
    }
    private static <S> MTS<S, String> readMts(DataInputStream in, LongFunction<S> state, boolean retain) throws IOException {
        long initial = in.readLong(); List<String> actions = readStrings(in); int n = count(in);
        MTSImpl<S, String> model = retain ? new MTSImpl<>(state.apply(initial)) : null;
        long[] states = retain ? new long[n] : null;
        if (new HashSet<>(actions).size() != actions.size()) throw new IOException("Duplicate native action");
        if (retain) model.addActions(actions);
        boolean foundInitial = false;
        long previous = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long s = in.readLong(); foundInitial |= s == initial;
            if (i > 0 && s <= previous) throw new IOException("Duplicate or unordered native state");
            previous = s;
            if (retain) { states[i] = s; model.addState(state.apply(s)); }
        }
        if (!foundInitial) throw new IOException("Missing initial state");
        for (int i = 0; i < n; i++) {
            int edges = count(in);
            for (int j = 0; j < edges; j++) {
                int action = in.readInt(); long target = in.readLong();
                if (action < 0 || action >= actions.size()) throw new IOException("Invalid native action");
                if (retain) model.addRequired(state.apply(states[i]), actions.get(action), state.apply(target));
            }
        }
        return model;
    }
}
