package ltsa.differential.nativeintegration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Per-execution measurements; absent phases mean not executed, rather than zero cost. */
public final class NativeDifferentialResult {
    private final NativeDifferentialOptions.Mode mode;
    private final Map<String, Long> phaseNanos;
    private final Map<String, Long> memoryBytes;
    private final Map<String, Long> gcCollectionMillis;
    private final Map<String, Long> gcCollectionCounts;

    public NativeDifferentialResult(NativeDifferentialOptions.Mode mode, Map<String, Long> phaseNanos) {
        this(mode, phaseNanos, Collections.<String, Long>emptyMap());
    }

    public NativeDifferentialResult(NativeDifferentialOptions.Mode mode,
                                    Map<String, Long> phaseNanos, Map<String, Long> memoryBytes) {
        this(mode, phaseNanos, memoryBytes, Collections.emptyMap(), Collections.emptyMap());
    }

    public NativeDifferentialResult(NativeDifferentialOptions.Mode mode, Map<String, Long> phaseNanos,
            Map<String, Long> memoryBytes, Map<String, Long> gcCollectionMillis, Map<String, Long> gcCollectionCounts) {
        if (mode == null) throw new IllegalArgumentException("Native differential mode is required");
        this.mode = mode;
        this.phaseNanos = immutableMeasurements(phaseNanos);
        this.memoryBytes = immutableMeasurements(memoryBytes);
        this.gcCollectionMillis = immutableMeasurements(gcCollectionMillis);
        this.gcCollectionCounts = immutableMeasurements(gcCollectionCounts);
    }

    private static Map<String, Long> immutableMeasurements(Map<String, Long> values) {
        if (values == null) throw new IllegalArgumentException("Measurements are required");
        Map<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().matches("[a-z][a-z0-9_]*")
                    || entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException("Invalid native differential measurement: " + entry);
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    public NativeDifferentialOptions.Mode getMode() { return mode; }
    public Map<String, Long> getPhaseNanos() { return phaseNanos; }
    public Map<String, Long> getMemoryBytes() { return memoryBytes; }
    public Map<String, Long> getGcCollectionMillis() { return gcCollectionMillis; }
    public Map<String, Long> getGcCollectionCounts() { return gcCollectionCounts; }
    public static String methodName(NativeDifferentialOptions.Mode mode) {
        return "native_differential_" + mode.name().toLowerCase(Locale.ROOT);
    }
    public static boolean isMethodName(String method) {
        if (method == null) return false;
        for (NativeDifferentialOptions.Mode mode : NativeDifferentialOptions.Mode.values()) {
            if (methodName(mode).equalsIgnoreCase(method.trim())) return true;
        }
        return false;
    }
}
