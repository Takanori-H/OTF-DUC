package ltsa.updatingControllers.memory;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RunHeapMemorySamplerTest {

    @Test
    public void boundarySamplesProduceDeterministicAggregate() {
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(
                60_000L,
                sequence(100L, 250L),
                sequence(100L, 105L, 160L, 167L),
                sequence(1_000L, 2_000L),
                constant(20L));

        sampler.start();
        assertTrue(sampler.isRunning());

        RunHeapMemorySampler.Result result = sampler.stop();

        assertFalse(sampler.isRunning());
        assertEquals(60_000L, result.getIntervalMillis());
        assertEquals(100L, result.getFirstHeapUsedBytes());
        assertEquals(1_000L, result.getFirstTimestampEpochMillis());
        assertEquals(250L, result.getPeakHeapUsedBytes());
        assertEquals(2_000L, result.getPeakTimestampEpochMillis());
        assertEquals(2L, result.getSampleCount());
        assertEquals(0L, result.getFailedSampleCount());
        assertEquals(2L, result.getAttemptCount());
        assertTrue(result.isAvailable());
        assertEquals(60L, result.getMaxSampleGapNanos());
        assertEquals(12L, result.getTotalSamplingWallTimeNanos());
        assertEquals(0L, result.getSamplerThreadCpuTimeNanos());
        assertTrue(result.isSamplerThreadCpuTimeAvailable());
    }

    @Test
    public void periodicSamplingUsesDaemonThreadAndKeepsPeakOnly() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean sampledByDaemon = new AtomicBoolean();
        final CountDownLatch periodicSampleTaken = new CountDownLatch(1);
        LongSupplier heapSupplier = new LongSupplier() {
            @Override
            public long getAsLong() {
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return 100L;
                }
                if (Thread.currentThread().isDaemon()) {
                    sampledByDaemon.set(true);
                }
                periodicSampleTaken.countDown();
                return call == 2 ? 900L : 200L;
            }
        };

        RunHeapMemorySampler sampler = new RunHeapMemorySampler(1L, heapSupplier);
        sampler.start();
        assertTrue(periodicSampleTaken.await(5L, TimeUnit.SECONDS));

        RunHeapMemorySampler.Result result = sampler.stop();

        assertTrue(sampledByDaemon.get());
        assertEquals(900L, result.getPeakHeapUsedBytes());
        assertTrue(result.getSampleCount() >= 3L);
        assertTrue(result.getMaxSampleGapNanos() > 0L);
        assertTrue(result.getTotalSamplingWallTimeNanos() >= 0L);
        assertTrue(result.getSamplerThreadCpuTimeNanos()
                >= RunHeapMemorySampler.VALUE_UNAVAILABLE);
    }

    @Test
    public void supplierFailureIsCountedWithoutStoppingRun() {
        final AtomicInteger calls = new AtomicInteger();
        LongSupplier occasionallyFailingSupplier = new LongSupplier() {
            @Override
            public long getAsLong() {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("temporary failure");
                }
                return 321L;
            }
        };
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(
                60_000L,
                occasionallyFailingSupplier);

        sampler.start();
        RunHeapMemorySampler.Result result = sampler.stop();

        assertEquals(321L, result.getPeakHeapUsedBytes());
        assertEquals(321L, result.getFirstHeapUsedBytes());
        assertEquals(1L, result.getSampleCount());
        assertEquals(1L, result.getFailedSampleCount());
        assertEquals(2L, result.getAttemptCount());
        assertTrue(result.isAvailable());
    }

    @Test
    public void allFailedSamplesRemainExplicitlyUnavailable() {
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(
                60_000L,
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        throw new IllegalStateException("provider unavailable");
                    }
                });

        sampler.start();
        RunHeapMemorySampler.Result result = sampler.stop();

        assertFalse(result.isAvailable());
        assertEquals(RunHeapMemorySampler.VALUE_UNAVAILABLE,
                result.getFirstHeapUsedBytes());
        assertEquals(RunHeapMemorySampler.VALUE_UNAVAILABLE,
                result.getFirstTimestampEpochMillis());
        assertEquals(RunHeapMemorySampler.VALUE_UNAVAILABLE,
                result.getPeakHeapUsedBytes());
        assertEquals(RunHeapMemorySampler.VALUE_UNAVAILABLE,
                result.getPeakTimestampEpochMillis());
        assertEquals(0L, result.getSampleCount());
        assertEquals(2L, result.getFailedSampleCount());
    }

    @Test
    public void stopIsIdempotentAndDoesNotTakeAnotherSample() {
        final AtomicInteger calls = new AtomicInteger();
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(
                60_000L,
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        return calls.incrementAndGet();
                    }
                });

        sampler.start();
        RunHeapMemorySampler.Result first = sampler.stop();
        RunHeapMemorySampler.Result second = sampler.stop();

        assertSame(first, second);
        assertEquals(2, calls.get());
        assertEquals(2L, second.getSampleCount());
    }

    @Test
    public void defaultProviderReadsHeapFromManagementBean() {
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(
                RunHeapMemorySampler.DEFAULT_INTERVAL_MILLIS);

        sampler.start();
        RunHeapMemorySampler.Result result = sampler.stop();

        assertTrue(result.getPeakHeapUsedBytes() >= 0L);
        assertTrue(result.getSampleCount() >= 2L);
        assertEquals(0L, result.getFailedSampleCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveInterval() {
        new RunHeapMemorySampler(0L, constant(1L));
    }

    private static LongSupplier constant(final long value) {
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return value;
            }
        };
    }

    private static LongSupplier sequence(final long... values) {
        return new LongSupplier() {
            private int index;

            @Override
            public synchronized long getAsLong() {
                if (index >= values.length) {
                    throw new AssertionError("Unexpected supplier invocation");
                }
                return values[index++];
            }
        };
    }
}
