package MTSSynthesis.controller.differential.nativeintegration;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request-local observations only: never changes the collector or calls GC.
 * Collector deltas are JVM-wide, coarse milliseconds/counts, not exact pause
 * times. A collection crossing an interval boundary can be attributed when
 * its counter advances. Do not subtract these from wall time as algorithm time.
 */
public final class NativeGenerationTiming {
    private final List<GarbageCollectorMXBean> collectors;
    private final Map<String, Long> phaseNanos = new LinkedHashMap<>();
    private final Map<String, Long> gcMillis = new LinkedHashMap<>();
    private final Map<String, Long> gcCounts = new LinkedHashMap<>();
    private final Set<String> unavailableMillis = new HashSet<>();
    private final Set<String> unavailableCounts = new HashSet<>();

    public NativeGenerationTiming() { this(ManagementFactory.getGarbageCollectorMXBeans()); }

    NativeGenerationTiming(List<GarbageCollectorMXBean> collectors) {
        this.collectors = new ArrayList<>(collectors);
    }

    public static final class Stamp {
        private final NativeGenerationTiming owner;
        private final long nanos;
        private final long[] millis, counts;
        private boolean finished;

        private Stamp(NativeGenerationTiming owner) {
            this.owner = owner;
            millis = owner.readCounters(true);
            counts = owner.readCounters(false);
            nanos = System.nanoTime();
        }
    }

    public Stamp start() { return new Stamp(this); }

    public void finish(String phase, Stamp start) { finish(phase, start, false); }

    /** Ordinary synthesis's total comprises three disjoint generation segments. */
    public void finishGenerationSegment(String phase, Stamp start) { finish(phase, start, true); }

    private void finish(String phase, Stamp start, boolean generationSegment) {
        long elapsed = System.nanoTime() - start.nanos;
        if (start.owner != this || start.finished || phase == null || !phase.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid or already recorded generation interval");
        }
        start.finished = true;
        phaseNanos.merge(phase, elapsed, Math::addExact);
        long millis = difference(start.millis, readCounters(true));
        long counts = difference(start.counts, readCounters(false));
        addCounter(gcMillis, unavailableMillis, phase, millis);
        addCounter(gcCounts, unavailableCounts, phase, counts);
        if (generationSegment) {
            addCounter(gcMillis, unavailableMillis, "controller_generation", millis);
            addCounter(gcCounts, unavailableCounts, "controller_generation", counts);
        }
    }

    private long[] readCounters(boolean millis) {
        long[] values = new long[collectors.size()];
        for (int i = 0; i < values.length; i++) {
            GarbageCollectorMXBean collector = collectors.get(i);
            values[i] = collector.isValid()
                    ? (millis ? collector.getCollectionTime() : collector.getCollectionCount()) : -1;
        }
        return values;
    }

    private static long difference(long[] before, long[] after) {
        if (before.length == 0 || before.length != after.length) return -1;
        long result = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] < 0 || after[i] < before[i]) return -1;
            result = Math.addExact(result, after[i] - before[i]);
        }
        return result;
    }

    private static void addCounter(Map<String, Long> values, Set<String> unavailable, String phase, long value) {
        if (value < 0) { unavailable.add(phase); values.remove(phase); }
        else if (!unavailable.contains(phase)) values.merge(phase, value, Math::addExact);
    }

    public Map<String, Long> getPhaseNanos() { return Collections.unmodifiableMap(phaseNanos); }
    public Map<String, Long> getGcCollectionMillis() { return Collections.unmodifiableMap(gcMillis); }
    public Map<String, Long> getGcCollectionCounts() { return Collections.unmodifiableMap(gcCounts); }
}
