package ltsa.differential.nativeintegration;

import ltsa.lts.CompactState;
import ltsa.lts.EventState;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** One explicit ENV addition, bound to the saved input rather than compiler state numbering. */
public final class NativeEnvironmentDeltaIO {
    private NativeEnvironmentDeltaIO() { }

    public static NativeDifferentialSafetyBackend.EnvironmentEdge read(
            Path file, String snapshotDigest, CompactState environment) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object parsed = new JSONParser().parse(reader);
            if (!(parsed instanceof JSONObject)) throw new IOException("Delta must be a JSON object");
            JSONObject json = (JSONObject) parsed;
            Set<String> allowed = new HashSet<>(Arrays.asList("snapshot_sha256", "source", "source_trace",
                    "action", "target", "target_trace"));
            for (Object key : json.keySet()) if (!allowed.contains(key)) throw new IOException("Unknown delta key: " + key);
            if (!snapshotDigest.equals(json.get("snapshot_sha256"))) throw new IOException("Delta belongs to a different snapshot");
            Object action = json.get("action");
            if (!(action instanceof String)) throw new IOException("Delta action must be a string");
            return new NativeDifferentialSafetyBackend.EnvironmentEdge(endpoint(json, "source", environment),
                    (String) action, endpoint(json, "target", environment));
        } catch (org.json.simple.parser.ParseException ex) {
            throw new IOException("Invalid delta JSON", ex);
        }
    }

    private static int endpoint(JSONObject json, String key, CompactState environment) throws IOException {
        if (json.containsKey(key) == json.containsKey(key + "_trace")) {
            throw new IOException("Specify exactly one of " + key + " and " + key + "_trace");
        }
        if (json.containsKey(key)) {
            Object number = json.get(key);
            if (!(number instanceof Long) || (Long) number < 0 || (Long) number >= environment.maxStates) {
                throw new IOException("Expected an existing non-ERROR ENV state for " + key);
            }
            return ((Long) number).intValue();
        }
        Object value = json.get(key + "_trace");
        if (!(value instanceof List)) throw new IOException("Expected action array for " + key + "_trace");
        int state = 0;
        for (Object label : (List<?>) value) {
            if (!(label instanceof String)) throw new IOException("Trace labels must be strings");
            Integer target = null;
            if (state >= 0 && environment.states[state] != null) {
                Enumeration<?> edges = environment.states[state].elements();
                while (edges.hasMoreElements()) {
                    EventState edge = (EventState) edges.nextElement();
                    if (label.equals(environment.alphabet[edge.getEvent()])) {
                        if (target != null && target != edge.getNext()) throw new IOException("Ambiguous ENV trace");
                        target = edge.getNext();
                    }
                }
            }
            if (target == null || target < 0) throw new IOException("Trace is not an ordinary path in saved ENV: " + key);
            state = target;
        }
        return state;
    }
}
