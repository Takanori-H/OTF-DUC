package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.DenseStateStorage;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import ltsa.lts.CompactState;
import ltsa.lts.EventState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Environment-driven safety-monitor product for the differential-synthesis mode.
 * A missing monitor transition stutters; an explicit monitor ERROR goes to the
 * shared violation state. This is the legacy safety-tester contract, not a
 * replacement for arbitrary FSP parallel composition. Internal, modal,
 * probabilistic and label-nondeterministic components are rejected.
 *
 * <p>Environment additions preserve old tuple IDs and expand newly reachable
 * tuples. A fresh composition assigns independent IDs; comparisons therefore
 * use semantic tuples. Outer-map and old-tuple scans are included in expansion,
 * so this API does not promise work proportional only to the added edges.</p>
 */
public final class EnvironmentSafetyProduct {
    public static final long ERROR = -1L;

    final Map<Tuple, Long> ids;
    final List<Tuple> tuples;
    final Map<Long, Map<String, Long>> rows;
    final Set<String> alphabet;
    private final Component environment;
    private final List<Component> monitors;

    private EnvironmentSafetyProduct(Builder builder) {
        ids = Collections.unmodifiableMap(builder.ids);
        tuples = Collections.unmodifiableList(builder.tuples);
        // The private builder freezes each completed row and is consumed here.
        // Retain its outer map instead of copying all state entries once more.
        rows = Collections.unmodifiableMap(builder.rows);
        alphabet = builder.environment.alphabet;
        environment = builder.environment;
        monitors = builder.monitors;
    }

    public static EnvironmentSafetyProduct compose(Component environment, List<Component> monitors) {
        validateMonitors(monitors);
        Builder builder = new Builder(environment, monitors);
        Queue<Long> pending = new ArrayDeque<>();
        builder.register(new Tuple(0, new int[monitors.size()]), pending);
        while (!pending.isEmpty()) {
            builder.expandRow(pending.remove(), pending, null);
        }
        return new EnvironmentSafetyProduct(builder);
    }

    /**
     * Expand an addition to this exact environment with the same monitor
     * snapshots. The analysis must have been built from this product's LTS;
     * numeric IDs must not be taken from an independently composed product.
     */
    public Expansion expand(EnvUpdate update, List<Component> monitors, Analysis<Long, String> base) {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(base, "base");
        if (update.base != environment || !this.monitors.equals(monitors)) {
            throw new IllegalArgumentException("The update must use this environment and unchanged monitor snapshots.");
        }
        if (base.getInitialState() != 0L || !base.getStates().equals(rows.keySet())
                || !base.getUnsafeStates().equals(Collections.singleton(ERROR))) {
            throw new IllegalArgumentException("The analysis must use this product's tuple IDs and shared ERROR.");
        }
        Builder builder = new Builder(this, update.updated);
        Map<Integer, List<EnvEdge>> bySource = new HashMap<>();
        for (EnvEdge edge : update.added) {
            bySource.computeIfAbsent(edge.from, ignored -> new ArrayList<>()).add(edge);
        }
        SafetyGameDelta.Builder<Long, String> delta = SafetyGameDelta.forAnalysis(base);
        Queue<Long> pending = new ArrayDeque<>();
        int changedSources = 0;
        for (int i = 0; i < tuples.size(); i++) {
            Tuple source = tuples.get(i);
            List<EnvEdge> additions = bySource.get(source.env);
            if (additions == null) {
                continue;
            }
            changedSources++;
            Map<String, Long> row = new TreeMap<>(rows.get((long) i));
            for (EnvEdge edge : additions) {
                long target = builder.target(source, edge.action, edge.to, pending);
                row.put(edge.action, target);
                delta.addTransition((long) i, edge.action, target);
            }
            builder.rows.put((long) i, Map.copyOf(row));
        }
        int expanded = 0;
        while (!pending.isEmpty()) {
            long id = pending.remove();
            delta.addState(id);
            builder.expandRow(id, pending, delta);
            expanded++;
        }
        EnvironmentSafetyProduct product = new EnvironmentSafetyProduct(builder);
        // The core delta represents alphabet additions through effective edges.
        // Reject an alphabet-only change instead of silently producing two
        // different graph alphabets in the product and the indexed safety game.
        Set<String> introduced = new HashSet<>(product.alphabet);
        introduced.removeAll(alphabet);
        SafetyGameDelta<Long, String> gameDelta = delta.build();
        for (SafetyGameDelta.Transition<Long, String> edge : gameDelta.getAddedTransitions()) {
            introduced.remove(edge.getAction());
        }
        if (!introduced.isEmpty()) {
            throw new IllegalArgumentException("New environment actions have no reachable product edge: " + introduced);
        }
        return new Expansion(product, gameDelta, tuples.size(), changedSources, expanded);
    }

    /** Independent mutable LTS materialization for the safety-analysis API. */
    public LTS<Long, String> copyLts() {
        LTS<Long, String> result = new LTSImpl<>(0L);
        result.addStates(rows.keySet());
        result.addActions(alphabet);
        for (Map.Entry<Long, Map<String, Long>> row : rows.entrySet()) {
            for (Map.Entry<String, Long> edge : row.getValue().entrySet()) {
                result.addTransition(row.getKey(), edge.getKey(), edge.getValue());
            }
        }
        return result;
    }

    public Long stateId(Tuple tuple) {
        return ids.get(Objects.requireNonNull(tuple, "tuple"));
    }

    /** Map an ID from another product by meaning, never by integer equality. */
    public Long stateIdFor(EnvironmentSafetyProduct other, long otherId) {
        Objects.requireNonNull(other, "other");
        if (otherId == ERROR) {
            return ERROR;
        }
        return stateId(other.tuple(otherId));
    }

    public Tuple tuple(long id) {
        if (id < 0 || id >= tuples.size()) {
            throw new IllegalArgumentException("Not an ordinary product tuple ID: " + id);
        }
        return tuples.get(Math.toIntExact(id));
    }

    public String key(long id) {
        return id == ERROR ? "ERROR" : tuple(id).key();
    }

    public long deadlocks() {
        long count = 0;
        for (Map.Entry<Long, Map<String, Long>> row : rows.entrySet()) {
            if (row.getKey() != ERROR && row.getValue().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public Map<String, Object> counts() {
        long edges = 0;
        for (Map<String, Long> row : rows.values()) {
            edges += row.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("states_including_error", rows.size());
        result.put("tuple_states", tuples.size());
        result.put("edges", edges);
        result.put("actions", alphabet.size());
        result.put("reachable_non_error_deadlocks", deadlocks());
        return Collections.unmodifiableMap(result);
    }

    private static void validateMonitors(List<Component> monitors) {
        Objects.requireNonNull(monitors, "monitors");
        for (Component monitor : monitors) {
            Objects.requireNonNull(monitor, "monitor");
            if (monitor.emptyRows != 0) {
                throw new IllegalArgumentException("Unsupported non-ERROR terminal monitor state in " + monitor.name);
            }
        }
    }

    /** Immutable ordinary component, initially in state 0. */
    public static final class Component {
        final String name;
        final Map<Integer, Map<String, Integer>> rows;
        final Set<String> alphabet;
        final int emptyRows;
        final long errorEdges;

        public Component(CompactState compact) {
            this(compact.name, compactRows(compact), compactAlphabet(compact));
        }

        Component(String name, Map<Integer, Map<String, Integer>> rows, Set<String> alphabet) {
            this.name = Objects.requireNonNull(name, "name");
            if (!rows.containsKey(0)) {
                throw new IllegalArgumentException("Component must contain initial state 0: " + name);
            }
            Map<Integer, Map<String, Integer>> copied = new LinkedHashMap<>();
            Set<String> actions = new TreeSet<>(alphabet);
            int empty = 0;
            long errors = 0;
            for (Map.Entry<Integer, Map<String, Integer>> source : rows.entrySet()) {
                if (source.getKey() == null || source.getKey() < 0) {
                    throw new IllegalArgumentException("Invalid ordinary component state in " + name);
                }
                Map<String, Integer> row = new TreeMap<>();
                for (Map.Entry<String, Integer> edge : source.getValue().entrySet()) {
                    validateAction(edge.getKey());
                    int target = Objects.requireNonNull(edge.getValue(), "target");
                    if (target != -1 && !rows.containsKey(target)) {
                        throw new IllegalArgumentException("Unknown component edge target in " + name);
                    }
                    row.put(edge.getKey(), target);
                    actions.add(edge.getKey());
                    if (target == -1) {
                        errors++;
                    }
                }
                if (row.isEmpty()) {
                    empty++;
                }
                copied.put(source.getKey(), Collections.unmodifiableMap(row));
            }
            for (String action : actions) {
                validateAction(action);
            }
            this.rows = Collections.unmodifiableMap(copied);
            this.alphabet = Collections.unmodifiableSet(actions);
            emptyRows = empty;
            errorEdges = errors;
        }

        private Component(Component base, Map<Integer, Map<String, Integer>> ownedRows,
                          Set<String> ownedAlphabet) {
            name = base.name;
            rows = Collections.unmodifiableMap(ownedRows);
            alphabet = Collections.unmodifiableSet(ownedAlphabet);
            int empty = 0;
            long errors = 0;
            for (Map<String, Integer> row : rows.values()) {
                if (row.isEmpty()) {
                    empty++;
                }
                for (int target : row.values()) {
                    if (target == -1) {
                        errors++;
                    }
                }
            }
            emptyRows = empty;
            errorEdges = errors;
        }

        public Map<String, Integer> row(int state) {
            if (state == -1) {
                return Collections.emptyMap();
            }
            Map<String, Integer> row = rows.get(state);
            if (row == null) {
                throw new IllegalArgumentException("Unknown " + name + " state " + state);
            }
            return row;
        }

        public EnvUpdate apply(List<EnvEdge> requests) {
            Objects.requireNonNull(requests, "requests");
            Map<Integer, Map<String, Integer>> nextRows = new LinkedHashMap<>(rows);
            Set<String> nextAlphabet = new TreeSet<>(alphabet);
            List<EnvEdge> added = new ArrayList<>();
            Set<Integer> changed = new HashSet<>();
            for (EnvEdge edge : requests) {
                Objects.requireNonNull(edge, "edge");
                if (edge.from < 0 || !rows.containsKey(edge.from)
                        || (edge.to != -1 && !rows.containsKey(edge.to))) {
                    throw new IllegalArgumentException("Environment update needs existing non-ERROR source and known target.");
                }
                Integer prior = nextRows.get(edge.from).get(edge.action);
                if (prior != null) {
                    if (prior != edge.to) {
                        throw new IllegalArgumentException("Update changes an existing action outcome: " + edge.action);
                    }
                    continue;
                }
                if (changed.add(edge.from)) {
                    nextRows.put(edge.from, new TreeMap<>(nextRows.get(edge.from)));
                }
                nextRows.get(edge.from).put(edge.action, edge.to);
                nextAlphabet.add(edge.action);
                added.add(edge);
            }
            for (int state : changed) {
                nextRows.put(state, Collections.unmodifiableMap(nextRows.get(state)));
            }
            return new EnvUpdate(this, new Component(this, nextRows, nextAlphabet), added);
        }

        private static Set<String> compactAlphabet(CompactState compact) {
            Set<String> alphabet = new TreeSet<>(Arrays.asList(compact.alphabet));
            // Unused optional slots can be added by MTSA for ordinary FSP.
            // compactRows rejects actual internal/modal transitions.
            alphabet.removeIf(action -> action.equals("tau") || action.endsWith("?"));
            return alphabet;
        }

        private static Map<Integer, Map<String, Integer>> compactRows(CompactState compact) {
            Objects.requireNonNull(compact, "compact");
            Map<Integer, Map<String, Integer>> result = new LinkedHashMap<>();
            for (int source = 0; source < compact.maxStates; source++) {
                Map<String, Integer> row = new TreeMap<>();
                if (compact.states[source] != null) {
                    for (Enumeration<?> entries = compact.states[source].elements(); entries.hasMoreElements();) {
                        EventState edge = (EventState) entries.nextElement();
                        String action = compact.alphabet[edge.getEvent()];
                        validateAction(action);
                        if (edge.getClass() != EventState.class) {
                            throw new IllegalArgumentException("Unsupported probabilistic edge in " + compact.name);
                        }
                        int target = edge.getNext();
                        Integer prior = row.putIfAbsent(action, target);
                        if (prior != null && prior != target) {
                            throw new IllegalArgumentException("Nondeterministic component " + compact.name
                                    + " at " + source + "/" + action);
                        }
                    }
                }
                result.put(source, row);
            }
            return result;
        }
    }

    public static final class EnvEdge {
        final int from;
        final String action;
        final int to;

        public EnvEdge(int from, String action, int to) {
            validateAction(action);
            this.from = from;
            this.action = action;
            this.to = to;
        }
    }

    public static final class EnvUpdate {
        final Component base;
        final Component updated;
        final List<EnvEdge> added;

        private EnvUpdate(Component base, Component updated, List<EnvEdge> added) {
            this.base = base;
            this.updated = updated;
            this.added = Collections.unmodifiableList(new ArrayList<>(added));
        }
    }

    public static final class Tuple {
        final int env;
        // Ordinary tuples own one immutable primitive array. Small monitor
        // values need not occupy four bytes each; signed values remain exact.
        private final Object monitors;
        private final int hash;

        public Tuple(int env, int[] monitors) {
            this.env = env;
            this.monitors = compact(monitors);
            hash = 31 * env + monitorHash(this.monitors);
        }

        private Tuple(int env, Object monitors, int hash) {
            this.env = env;
            this.monitors = monitors;
            this.hash = hash;
        }

        private static int monitorHash(Object states) {
            // The primitive overloads use the same signed-element recurrence.
            if (states instanceof byte[]) {
                return Arrays.hashCode((byte[]) states);
            }
            if (states instanceof short[]) {
                return Arrays.hashCode((short[]) states);
            }
            return Arrays.hashCode((int[]) states);
        }

        private static Object compact(int[] states) {
            Objects.requireNonNull(states, "monitors");
            int min = 0;
            int max = 0;
            for (int state : states) {
                min = Math.min(min, state);
                max = Math.max(max, state);
            }
            if (min >= Byte.MIN_VALUE && max <= Byte.MAX_VALUE) {
                byte[] packed = new byte[states.length];
                for (int i = 0; i < states.length; i++) {
                    packed[i] = (byte) states[i];
                }
                return packed;
            }
            if (min >= Short.MIN_VALUE && max <= Short.MAX_VALUE) {
                short[] packed = new short[states.length];
                for (int i = 0; i < states.length; i++) {
                    packed[i] = (short) states[i];
                }
                return packed;
            }
            return states.clone();
        }

        private int monitorCount() {
            if (monitors instanceof byte[]) {
                return ((byte[]) monitors).length;
            }
            if (monitors instanceof short[]) {
                return ((short[]) monitors).length;
            }
            return ((int[]) monitors).length;
        }

        private int monitorState(int index) {
            if (monitors instanceof byte[]) {
                return ((byte[]) monitors)[index];
            }
            if (monitors instanceof short[]) {
                return ((short[]) monitors)[index];
            }
            return ((int[]) monitors)[index];
        }

        public int environmentState() { return env; }
        public int[] monitorStates() {
            if (monitors instanceof int[]) {
                return ((int[]) monitors).clone();
            }
            int[] states = new int[monitorCount()];
            for (int i = 0; i < states.length; i++) {
                states[i] = monitorState(i);
            }
            return states;
        }
        public int hashCode() { return hash; }
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Tuple)) {
                return false;
            }
            Tuple tuple = (Tuple) other;
            if (env != tuple.env || hash != tuple.hash || monitorCount() != tuple.monitorCount()) {
                return false;
            }
            if (monitors instanceof byte[] && tuple.monitors instanceof byte[]) {
                return Arrays.equals((byte[]) monitors, (byte[]) tuple.monitors);
            }
            if (monitors instanceof short[] && tuple.monitors instanceof short[]) {
                return Arrays.equals((short[]) monitors, (short[]) tuple.monitors);
            }
            if (monitors instanceof int[] && tuple.monitors instanceof int[]) {
                return Arrays.equals((int[]) monitors, (int[]) tuple.monitors);
            }
            for (int i = 0; i < monitorCount(); i++) {
                if (monitorState(i) != tuple.monitorState(i)) {
                    return false;
                }
            }
            return true;
        }
        public String key() { return env + ":" + Arrays.toString(monitorStates()); }
    }

    public static final class Expansion {
        final EnvironmentSafetyProduct product;
        final SafetyGameDelta<Long, String> delta;
        final int oldTuplesExamined;
        final int changedOldSources;
        final int newTuplesExpanded;

        private Expansion(EnvironmentSafetyProduct product, SafetyGameDelta<Long, String> delta,
                          int oldTuplesExamined, int changedOldSources, int newTuplesExpanded) {
            this.product = product;
            this.delta = delta;
            this.oldTuplesExamined = oldTuplesExamined;
            this.changedOldSources = changedOldSources;
            this.newTuplesExpanded = newTuplesExpanded;
        }
    }

    private static final class Builder {
        final Component environment;
        final List<Component> monitors;
        final Map<Tuple, Long> ids;
        final List<Tuple> tuples;
        final Map<Long, Map<String, Long>> rows;
        private final int[] targetStates;

        Builder(Component environment, List<Component> monitors) {
            this.environment = Objects.requireNonNull(environment, "environment");
            this.monitors = Collections.unmodifiableList(new ArrayList<>(monitors));
            targetStates = new int[monitors.size()];
            ids = new HashMap<>();
            tuples = new ArrayList<>();
            rows = DenseStateStorage.newAppendOnlyLongMap();
            rows.put(ERROR, Collections.emptyMap());
        }

        Builder(EnvironmentSafetyProduct base, Component environment) {
            this.environment = environment;
            monitors = base.monitors;
            targetStates = new int[monitors.size()];
            ids = new HashMap<>(base.ids);
            tuples = new ArrayList<>(base.tuples);
            rows = DenseStateStorage.newAppendOnlyLongMap();
            // Copy only the outer array; immutable unchanged rows are shared.
            rows.putAll(base.rows);
        }

        long register(Tuple tuple, Queue<Long> pending) {
            Long prior = ids.get(tuple);
            if (prior != null) {
                return prior;
            }
            return registerNew(tuple, pending);
        }

        private long registerNew(Tuple tuple, Queue<Long> pending) {
            long id = tuples.size();
            ids.put(tuple, id);
            tuples.add(tuple);
            rows.put(id, Collections.emptyMap());
            pending.add(id);
            return id;
        }

        long target(Tuple source, String action, int envTarget, Queue<Long> pending) {
            for (int i = 0; i < targetStates.length; i++) {
                int state = source.monitorState(i);
                Integer next = monitors.get(i).row(state).get(action);
                targetStates[i] = next == null ? state : next;
                if (targetStates[i] == -1) {
                    return ERROR;
                }
            }
            // This borrowed vector is only a synchronous lookup probe. Never
            // insert it into ids/tuples: the next edge overwrites targetStates.
            Tuple lookup = new Tuple(envTarget, targetStates,
                    31 * envTarget + Arrays.hashCode(targetStates));
            Long prior = ids.get(lookup);
            if (prior != null) {
                return prior;
            }
            // Only genuinely new tuples allocate retained monitor storage.
            Tuple stored = new Tuple(envTarget, Tuple.compact(targetStates), lookup.hash);
            return registerNew(stored, pending);
        }

        void expandRow(long id, Queue<Long> pending, SafetyGameDelta.Builder<Long, String> delta) {
            Tuple source = tuples.get(Math.toIntExact(id));
            Map<String, Long> row = new TreeMap<>();
            for (Map.Entry<String, Integer> edge : environment.row(source.env).entrySet()) {
                long target = target(source, edge.getKey(), edge.getValue(), pending);
                row.put(edge.getKey(), target);
                if (delta != null) {
                    delta.addTransition(id, edge.getKey(), target);
                }
            }
            // Freeze immediately, so an entire product of mutable TreeMaps is
            // never retained. prepare() can safely share these immutable rows.
            rows.put(id, Map.copyOf(row));
        }
    }

    private static void validateAction(String action) {
        if (action == null || action.trim().isEmpty() || action.equals("tau") || action.endsWith("?")) {
            throw new IllegalArgumentException("Unsupported internal/modal/empty action: " + action);
        }
    }
}
