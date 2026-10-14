package MTSSynthesis.controller.differential.nativeintegration;

import org.junit.Test;

import javax.management.ObjectName;
import java.lang.management.GarbageCollectorMXBean;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

/** Counter behavior must be testable without forcing GC or depending on JVM timing. */
public class NativeGenerationTimingTest {
    @Test public void sumsCollectorsAndDisjointGenerationSegmentsWithoutLosingMeasuredZero() {
        Collector young = new Collector(10, 2), old = new Collector(20, 3);
        NativeGenerationTiming timing = new NativeGenerationTiming(Arrays.asList(young, old));
        NativeGenerationTiming.Stamp build = timing.start();
        young.advance(8, 1);
        old.advance(3, 2);
        timing.finishGenerationSegment("controller_build", build);
        timing.finishGenerationSegment("controller_conversion", timing.start());
        NativeGenerationTiming.Stamp post = timing.start();
        old.advance(12, 1);
        timing.finishGenerationSegment("controller_postcompose", post);

        assertEquals(Long.valueOf(11), timing.getGcCollectionMillis().get("controller_build"));
        assertEquals(Long.valueOf(3), timing.getGcCollectionCounts().get("controller_build"));
        assertEquals(Long.valueOf(0), timing.getGcCollectionMillis().get("controller_conversion"));
        assertEquals(Long.valueOf(0), timing.getGcCollectionCounts().get("controller_conversion"));
        assertEquals(Long.valueOf(23), timing.getGcCollectionMillis().get("controller_generation"));
        assertEquals(Long.valueOf(4), timing.getGcCollectionCounts().get("controller_generation"));
        assertEquals(3, timing.getPhaseNanos().size());
        for (Long elapsed : timing.getPhaseNanos().values()) assertTrue(elapsed >= 0);
        assertImmutable(timing.getPhaseNanos());
        assertImmutable(timing.getGcCollectionMillis());
        assertImmutable(timing.getGcCollectionCounts());
    }

    @Test public void nestedCopyAndPatchAreObservedWithoutAddingThemAgainToParentCounters() {
        Collector collector = new Collector(100, 10);
        NativeGenerationTiming timing = new NativeGenerationTiming(Collections.singletonList(collector));
        NativeGenerationTiming.Stamp generation = timing.start(), build = timing.start(), copy = timing.start();
        collector.advance(3, 1);
        timing.finish("controller_copy", copy);
        NativeGenerationTiming.Stamp patch = timing.start();
        collector.advance(7, 2);
        timing.finish("controller_patch", patch);
        timing.finish("controller_build", build);
        timing.finish("controller_generation", generation);

        assertEquals(Long.valueOf(3), timing.getGcCollectionMillis().get("controller_copy"));
        assertEquals(Long.valueOf(7), timing.getGcCollectionMillis().get("controller_patch"));
        assertEquals(Long.valueOf(10), timing.getGcCollectionMillis().get("controller_build"));
        assertEquals(Long.valueOf(10), timing.getGcCollectionMillis().get("controller_generation"));
        assertEquals(Long.valueOf(3), timing.getGcCollectionCounts().get("controller_generation"));
        Map<String, Long> nanos = timing.getPhaseNanos();
        assertTrue(nanos.get("controller_copy") + nanos.get("controller_patch") <= nanos.get("controller_build"));
        assertTrue(nanos.get("controller_build") <= nanos.get("controller_generation"));
    }

    @Test public void unsupportedTimeStaysAbsentAcrossLaterSegmentsWhileCountsRemainUsable() {
        Collector collector = new Collector(10, 1);
        NativeGenerationTiming timing = new NativeGenerationTiming(Collections.singletonList(collector));
        timing.finishGenerationSegment("controller_build", timing.start());
        NativeGenerationTiming.Stamp conversion = timing.start();
        collector.millis = -1;
        collector.count++;
        timing.finishGenerationSegment("controller_conversion", conversion);
        assertFalse(timing.getGcCollectionMillis().containsKey("controller_conversion"));
        assertFalse(timing.getGcCollectionMillis().containsKey("controller_generation"));
        assertEquals(Long.valueOf(1), timing.getGcCollectionCounts().get("controller_generation"));

        collector.millis = 20;
        NativeGenerationTiming.Stamp post = timing.start();
        collector.advance(4, 1);
        timing.finishGenerationSegment("controller_postcompose", post);
        // A later supported interval must not turn a partially unknown total into a partial sum.
        assertFalse(timing.getGcCollectionMillis().containsKey("controller_generation"));
        assertEquals(Long.valueOf(4), timing.getGcCollectionMillis().get("controller_postcompose"));
        assertEquals(Long.valueOf(2), timing.getGcCollectionCounts().get("controller_generation"));
        timing.finishGenerationSegment("controller_conversion", timing.start());
        assertFalse(timing.getGcCollectionMillis().containsKey("controller_conversion"));
    }

    @Test public void unavailableAtStartAndCounterRollbackDoNotBecomeZeroOrNegativeMeasurements() {
        Collector first = new Collector(10, -1), second = new Collector(20, 2);
        NativeGenerationTiming timing = new NativeGenerationTiming(Arrays.asList(first, second));
        NativeGenerationTiming.Stamp started = timing.start();
        first.advance(4, 2);
        second.advance(3, 1);
        timing.finish("controller_build", started);
        assertEquals(Long.valueOf(7), timing.getGcCollectionMillis().get("controller_build"));
        assertFalse(timing.getGcCollectionCounts().containsKey("controller_build"));

        NativeGenerationTiming.Stamp rollback = timing.start();
        second.millis--;
        first.count++;
        timing.finish("controller_conversion", rollback);
        assertFalse(timing.getGcCollectionMillis().containsKey("controller_conversion"));
        assertEquals(Long.valueOf(1), timing.getGcCollectionCounts().get("controller_conversion"));
    }

    @Test public void invalidOrMissingCollectorsAreUnavailableEvenWhenAnotherCollectorWorks() {
        Collector valid = new Collector(10, 1), invalid = new Collector(20, 2);
        NativeGenerationTiming timing = new NativeGenerationTiming(Arrays.asList(valid, invalid));
        NativeGenerationTiming.Stamp started = timing.start();
        valid.advance(5, 1);
        invalid.valid = false;
        timing.finishGenerationSegment("controller_build", started);
        assertTrue(timing.getGcCollectionMillis().isEmpty());
        assertTrue(timing.getGcCollectionCounts().isEmpty());
        assertTrue(timing.getPhaseNanos().containsKey("controller_build"));

        NativeGenerationTiming absent = new NativeGenerationTiming(Collections.emptyList());
        absent.finishGenerationSegment("controller_build", absent.start());
        assertTrue(absent.getGcCollectionMillis().isEmpty());
        assertTrue(absent.getGcCollectionCounts().isEmpty());
        assertTrue(absent.getPhaseNanos().containsKey("controller_build"));
    }

    @Test public void foreignOrReusedIntervalsCannotCorruptMeasurements() {
        NativeGenerationTiming timing = new NativeGenerationTiming(Collections.emptyList());
        NativeGenerationTiming other = new NativeGenerationTiming(Collections.emptyList());
        NativeGenerationTiming.Stamp interval = timing.start();
        try {
            other.finish("controller_build", interval);
            fail("A different request's interval must be rejected");
        } catch (IllegalArgumentException expected) { }
        assertTrue(other.getPhaseNanos().isEmpty());
        try {
            timing.finish("invalid phase", interval);
            fail("Invalid measurement names must be rejected");
        } catch (IllegalArgumentException expected) { }
        assertTrue(timing.getPhaseNanos().isEmpty());
        timing.finish("controller_build", interval);
        long original = timing.getPhaseNanos().get("controller_build");
        try {
            timing.finish("controller_build", interval);
            fail("An interval must not be recorded twice");
        } catch (IllegalArgumentException expected) { }
        assertEquals(Long.valueOf(original), timing.getPhaseNanos().get("controller_build"));
    }

    private static void assertImmutable(Map<String, Long> values) {
        try {
            values.put("mutation", 0L);
            fail("Counter maps must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    private static final class Collector implements GarbageCollectorMXBean {
        private long millis, count;
        private boolean valid = true;
        private Collector(long millis, long count) { this.millis = millis; this.count = count; }
        private void advance(long millis, long count) { this.millis += millis; this.count += count; }
        @Override public long getCollectionCount() { return count; }
        @Override public long getCollectionTime() { return millis; }
        @Override public String getName() { return "test"; }
        @Override public boolean isValid() { return valid; }
        @Override public String[] getMemoryPoolNames() { return new String[0]; }
        @Override public ObjectName getObjectName() { return null; }
    }
}
