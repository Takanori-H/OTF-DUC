package ltsa.updatingControllers.cli;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProcessRssSamplerTest {

    private static final long TEST_PID = 1234L;

    @After
    public void resetCircuitBreaker() {
        ProcessRssSampler.resetDefaultProviderCircuitBreakerForTests();
    }

    @Test
    public void keepsSeparateLifetimeAndSynthesisWindowPeaks() {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        long beforeStart = System.currentTimeMillis();
        sampler.start();
        assertEquals(1, supplier.getCallCount());

        supplier.setValue(300L);
        assertTrue(sampler.beginSynthesisWindow());
        assertEquals(300L, sampler.snapshot().getSynthesisWindowStartRssBytes());
        supplier.setValue(500L);
        assertTrue(sampler.endSynthesisWindow());
        supplier.setValue(700L);
        ProcessRssSampler.Result result = sampler.stop();

        assertEquals(TEST_PID, result.getPid());
        assertEquals(60_000L, result.getIntervalMillis());
        assertEquals(700L, result.getLifetimePeakRssBytes());
        assertEquals(500L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(300L, result.getSynthesisWindowStartRssBytes());
        assertEquals(200L, result.getSynthesisWindowPeakIncreaseRssBytes());
        assertTrue(result.getLifetimePeakAtEpochMillis() >= beforeStart);
        assertTrue(result.getLifetimePeakAtElapsedMillis() >= 0L);
        assertTrue(result.getSynthesisWindowPeakAtEpochMillis() >= beforeStart);
        assertTrue(result.getSynthesisWindowPeakAtElapsedMillis() >= 0L);
        assertEquals(4L, result.getSampleCount());
        assertEquals(2L, result.getSynthesisWindowSampleCount());
        assertTrue(result.getSynthesisWindowSamplingWallTimeNanos() >= 0L);
        assertTrue(result.isSynthesisWindowProviderThreadCpuMeasurementComplete());
        if (result.isSynthesisWindowProviderThreadCpuTimeAvailable()) {
            assertTrue(result.getSynthesisWindowProviderThreadCpuTimeNanos() >= 0L);
        }
        assertTrue(result.getTotalSamplingWallTimeNanos() >= 0L);
        assertTrue(result.isProviderThreadCpuMeasurementComplete());
        if (result.isProviderThreadCpuTimeAvailable()) {
            assertTrue(result.getProviderThreadCpuTimeNanos() >= 0L);
        }
        if (result.isSamplerThreadCpuTimeAvailable()) {
            assertTrue(result.getSamplerThreadCpuTimeNanos() >= 0L);
        }
        if (result.isTotalMeasurementThreadCpuTimeAvailable()) {
            assertEquals(
                    result.getSamplerThreadCpuTimeNanos()
                            + result.getProviderThreadCpuTimeNanos(),
                    result.getTotalMeasurementThreadCpuTimeNanos());
        }
        assertEquals("fake_provider", result.getProvider());
        assertTrue(result.isAvailable());
        assertTrue(result.isSynthesisWindowAvailable());
        assertTrue(result.isSynthesisWindowStarted());
        assertTrue(result.isSynthesisWindowCompleted());
        assertTrue(result.isSynthesisWindowStartBoundarySampleSucceeded());
        assertTrue(result.isSynthesisWindowEndBoundarySampleSucceeded());
        assertTrue(result.getSynthesisWindowStartedAtEpochMillis() >= beforeStart);
        assertTrue(result.getSynthesisWindowCompletedAtEpochMillis()
                >= result.getSynthesisWindowStartedAtEpochMillis());
        assertFalse(sampler.isRunning());
    }

    @Test
    public void periodicSamplerRunsOnDaemonThreadAndRecordsActualGap()
            throws Exception {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(10L, supplier);

        sampler.start();
        assertTrue(supplier.awaitCallCount(2, 2L, TimeUnit.SECONDS));
        ProcessRssSampler.Result result = sampler.stop();

        assertTrue(result.getSampleCount() >= 3L);
        assertTrue(result.getMaxSampleGapMillis() >= 1L);
    }

    @Test
    public void synthesisWindowGapExcludesPreWindowAndPostWindowAttempts()
            throws Exception {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        sampler.start();
        Thread.sleep(80L);
        sampler.beginSynthesisWindow();
        Thread.sleep(5L);
        sampler.endSynthesisWindow();
        ProcessRssSampler.Result result = sampler.stop();

        assertTrue(result.getMaxSampleGapMillis() >= 50L);
        assertTrue(result.getSynthesisWindowMaxSampleGapMillis() >= 1L);
        assertTrue(result.getSynthesisWindowMaxSampleGapMillis()
                < result.getMaxSampleGapMillis());
    }

    @Test
    public void countsProviderTimeoutOnlyWhenItOccursInsideSynthesisWindow() {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch releaseProvider = new CountDownLatch(1);
        ProcessRssSampler sampler = new ProcessRssSampler(
                TEST_PID,
                60_000L,
                "window_timeout_fake",
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        if (calls.incrementAndGet() == 1) {
                            return 100L;
                        }
                        while (true) {
                            try {
                                releaseProvider.await();
                                return 200L;
                            } catch (InterruptedException ignored) {
                                // Model a provider that ignores cancellation.
                            }
                        }
                    }
                },
                50L,
                true);
        try {
            sampler.start();
            assertTrue(sampler.beginSynthesisWindow());
            ProcessRssSampler.Result result = sampler.stop();

            assertEquals(1L, result.getSynthesisWindowProviderFailureCount());
            assertEquals(1L, result.getSynthesisWindowProviderTimeoutCount());
            assertTrue(result.isSynthesisWindowProviderCircuitOpened());
        } finally {
            releaseProvider.countDown();
            sampler.stop();
        }
    }

    @Test
    public void boundaryMethodsTakeSynchronousSamples() {
        ControllableSupplier supplier = new ControllableSupplier(10L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        sampler.start();
        assertEquals(1, supplier.getCallCount());
        supplier.setValue(20L);
        sampler.beginSynthesisWindow();
        assertEquals(2, supplier.getCallCount());
        supplier.setValue(30L);
        sampler.endSynthesisWindow();
        assertEquals(3, supplier.getCallCount());
        supplier.setValue(40L);
        ProcessRssSampler.Result result = sampler.stop();

        assertEquals(4, supplier.getCallCount());
        assertEquals(40L, result.getLifetimePeakRssBytes());
        assertEquals(30L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(20L, result.getSynthesisWindowStartRssBytes());
        assertEquals(10L, result.getSynthesisWindowPeakIncreaseRssBytes());
        assertEquals(2L, result.getSynthesisWindowSampleCount());
    }

    @Test
    public void usesStartBoundaryReadingAsBaselineAndClampsIncreaseAtZero() {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        sampler.start();
        supplier.setValue(300L);
        assertTrue(sampler.beginSynthesisWindow());
        supplier.setValue(200L);
        assertTrue(sampler.endSynthesisWindow());
        ProcessRssSampler.Result result = sampler.stop();

        assertEquals(300L, result.getSynthesisWindowStartRssBytes());
        assertEquals(300L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(0L, result.getSynthesisWindowPeakIncreaseRssBytes());
    }

    @Test
    public void increaseIsUnavailableWhenStartBoundaryReadingFails() {
        SequenceSupplier supplier = new SequenceSupplier(100L, 0L, 300L, 400L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        sampler.start();
        assertTrue(sampler.beginSynthesisWindow());
        assertTrue(sampler.endSynthesisWindow());
        ProcessRssSampler.Result result = sampler.stop();

        assertFalse(result.isSynthesisWindowStartBoundarySampleSucceeded());
        assertTrue(result.isSynthesisWindowAvailable());
        assertEquals(-1L, result.getSynthesisWindowStartRssBytes());
        assertEquals(300L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(-1L, result.getSynthesisWindowPeakIncreaseRssBytes());
    }

    @Test
    public void reportsStartedButIncompleteWindowWhenEndMarkerIsMissing() {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);
        sampler.start();
        sampler.beginSynthesisWindow();

        ProcessRssSampler.Result result = sampler.stop();

        assertTrue(result.isSynthesisWindowStarted());
        assertFalse(result.isSynthesisWindowCompleted());
        assertTrue(result.isSynthesisWindowAvailable());
        assertEquals(100L, result.getSynthesisWindowStartRssBytes());
        assertEquals(100L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(0L, result.getSynthesisWindowPeakIncreaseRssBytes());
        assertEquals(-1L, result.getSynthesisWindowCompletedAtEpochMillis());
    }

    @Test
    public void reportsWindowNotStartedWhenNoBeginMarkerArrives() {
        ControllableSupplier supplier = new ControllableSupplier(100L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);
        sampler.start();

        ProcessRssSampler.Result result = sampler.stop();

        assertFalse(result.isSynthesisWindowStarted());
        assertFalse(result.isSynthesisWindowCompleted());
        assertFalse(result.isSynthesisWindowAvailable());
        assertFalse(result.isSynthesisWindowStartBoundarySampleSucceeded());
        assertFalse(result.isSynthesisWindowEndBoundarySampleSucceeded());
        assertEquals(-1L, result.getSynthesisWindowStartRssBytes());
        assertEquals(-1L, result.getSynthesisWindowPeakRssBytes());
        assertEquals(-1L, result.getSynthesisWindowPeakIncreaseRssBytes());
        assertEquals(0L, result.getSynthesisWindowSampleCount());
    }

    @Test
    public void stopIsIdempotentAndFreezesResult() {
        ControllableSupplier supplier = new ControllableSupplier(64L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);
        sampler.start();

        ProcessRssSampler.Result first = sampler.stop();
        int callsAfterFirstStop = supplier.getCallCount();
        ProcessRssSampler.Result second = sampler.stop();

        assertEquals(2, callsAfterFirstStop);
        assertEquals(callsAfterFirstStop, supplier.getCallCount());
        assertEquals(first.getLifetimePeakRssBytes(), second.getLifetimePeakRssBytes());
        assertEquals(first.getLifetimePeakAtEpochMillis(),
                second.getLifetimePeakAtEpochMillis());
        assertEquals(first.getSampleCount(), second.getSampleCount());
        assertEquals(first.getSampleAttemptCount(), second.getSampleAttemptCount());
        assertEquals(first.getMaxSampleGapMillis(), second.getMaxSampleGapMillis());
        assertEquals(first.isAvailable(), second.isAvailable());
    }

    @Test
    public void countsSampleAndProviderFailuresWithoutClaimingAvailability()
            throws Exception {
        SequenceSupplier supplier = new SequenceSupplier(0L, 0L, -1L);
        ProcessRssSampler sampler = new ProcessRssSampler(
                TEST_PID,
                1L,
                "fake_unavailable",
                supplier);
        sampler.start();
        assertTrue(supplier.awaitAll(2L, TimeUnit.SECONDS));
        waitUntilStopped(sampler);

        ProcessRssSampler.Result result = sampler.stop();

        assertFalse(result.isAvailable());
        assertEquals(-1L, result.getLifetimePeakRssBytes());
        assertEquals(-1L, result.getLifetimePeakAtEpochMillis());
        assertEquals(0L, result.getSampleCount());
        assertEquals(3L, result.getSampleFailureCount());
        assertEquals(0L, result.getProviderFailureCount());
        assertEquals(3L, result.getSampleAttemptCount());
    }

    @Test
    public void countsProviderExceptionAndStopsSampling() throws Exception {
        final CountDownLatch called = new CountDownLatch(1);
        ProcessRssSampler sampler = new ProcessRssSampler(
                TEST_PID,
                1L,
                "failing_fake",
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        called.countDown();
                        throw new IllegalStateException("fake provider failure");
                    }
                });

        sampler.start();
        assertTrue(called.await(2L, TimeUnit.SECONDS));
        ProcessRssSampler.Result result = sampler.stop();

        assertFalse(result.isAvailable());
        assertEquals(-1L, result.getLifetimePeakRssBytes());
        assertEquals(0L, result.getSampleFailureCount());
        assertEquals(1L, result.getProviderFailureCount());
        assertEquals(1L, result.getSampleAttemptCount());
        assertFalse(sampler.isRunning());
    }

    @Test
    public void providerTimeoutKeepsLifecycleBounded() {
        final CountDownLatch releaseProvider = new CountDownLatch(1);
        ProcessRssSampler sampler = new ProcessRssSampler(
                TEST_PID,
                60_000L,
                "blocking_fake",
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        while (true) {
                            try {
                                releaseProvider.await();
                                return 100L;
                            } catch (InterruptedException ignored) {
                                // Simulate a native provider that ignores interruption.
                            }
                        }
                    }
                },
                50L);

        long started = System.nanoTime();
        sampler.start();
        ProcessRssSampler.Result result = sampler.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        releaseProvider.countDown();

        assertTrue("provider timeout was not bounded: " + elapsedMillis,
                elapsedMillis < 2_000L);
        assertFalse(result.isAvailable());
        assertEquals(1L, result.getProviderFailureCount());
        assertEquals(1L, result.getProviderTimeoutCount());
        assertEquals(50L, result.getProviderTimeoutMillis());
        assertFalse(result.isProviderThreadCpuMeasurementComplete());
    }

    @Test
    public void defaultProviderCircuitBreakerCapsHardTimeoutLeaksPerBatch() {
        final CountDownLatch releaseProvider = new CountDownLatch(1);
        ProcessRssSampler first = new ProcessRssSampler(
                TEST_PID,
                60_000L,
                "blocking_default_fake",
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        while (true) {
                            try {
                                releaseProvider.await();
                                return 100L;
                            } catch (InterruptedException ignored) {
                                // Model a native read that ignores cancellation.
                            }
                        }
                    }
                },
                50L,
                true);
        try {
            first.start();
            ProcessRssSampler.Result firstResult = first.stop();
            assertTrue(ProcessRssSampler.isDefaultProviderCircuitOpen());
            assertTrue(firstResult.isProviderCircuitOpen());

            final AtomicInteger secondProviderCalls = new AtomicInteger();
            ProcessRssSampler second = new ProcessRssSampler(
                    TEST_PID + 1L,
                    60_000L,
                    "second_default_fake",
                    new LongSupplier() {
                        @Override
                        public long getAsLong() {
                            secondProviderCalls.incrementAndGet();
                            return 200L;
                        }
                    },
                    50L,
                    true);
            second.start();
            ProcessRssSampler.Result secondResult = second.stop();

            assertEquals(0, secondProviderCalls.get());
            assertTrue(secondResult.isProviderDisabledByCircuitBreaker());
            assertTrue(secondResult.getProvider().endsWith("_circuit_open"));
            assertFalse(secondResult.isAvailable());
        } finally {
            releaseProvider.countDown();
            first.stop();
        }
    }

    @Test
    public void startAndWindowMarkersAreIdempotent() {
        ControllableSupplier supplier = new ControllableSupplier(1L);
        ProcessRssSampler sampler = sampler(60_000L, supplier);

        sampler.start();
        sampler.start();
        assertEquals(1, supplier.getCallCount());
        assertTrue(sampler.beginSynthesisWindow());
        assertFalse(sampler.beginSynthesisWindow());
        assertTrue(sampler.endSynthesisWindow());
        assertFalse(sampler.endSynthesisWindow());
        sampler.stop();

        assertEquals(4, supplier.getCallCount());
    }

    @Test
    public void validatesConstructorArguments() {
        LongSupplier supplier = new LongSupplier() {
            @Override
            public long getAsLong() {
                return 1L;
            }
        };

        assertIllegalArgument(0L, 50L, supplier);
        assertIllegalArgument((long) Integer.MAX_VALUE + 1L, 50L, supplier);
        assertIllegalArgument(TEST_PID, 0L, supplier);
        try {
            new ProcessRssSampler(TEST_PID, 50L, "fake", supplier, 0L);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("providerTimeoutMillis"));
        }

        try {
            new ProcessRssSampler(TEST_PID, 50L, "fake", null);
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {
            assertEquals("rssBytesSupplier", expected.getMessage());
        }
    }

    private static ProcessRssSampler sampler(
            long intervalMillis,
            LongSupplier supplier) {
        return new ProcessRssSampler(
                TEST_PID,
                intervalMillis,
                "fake_provider",
                supplier);
    }

    private static void assertIllegalArgument(
            long pid,
            long intervalMillis,
            LongSupplier supplier) {
        try {
            new ProcessRssSampler(pid, intervalMillis, "fake", supplier);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(
                    pid <= 0L || pid > Integer.MAX_VALUE ? "pid" : "intervalMillis"));
        }
    }

    private static void waitUntilStopped(ProcessRssSampler sampler)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (sampler.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertFalse("sampler thread did not stop", sampler.isRunning());
    }

    private static final class ControllableSupplier implements LongSupplier {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile long value;

        private ControllableSupplier(long initialValue) {
            value = initialValue;
        }

        @Override
        public long getAsLong() {
            long sampledValue = value;
            calls.incrementAndGet();
            return sampledValue;
        }

        private void setValue(long newValue) {
            value = newValue;
        }

        private int getCallCount() {
            return calls.get();
        }

        private boolean awaitCallCount(int expected, long timeout, TimeUnit unit)
                throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (calls.get() < expected && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            return calls.get() >= expected;
        }
    }

    private static final class SequenceSupplier implements LongSupplier {
        private final long[] values;
        private final CountDownLatch calls;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceSupplier(long... values) {
            this.values = values;
            this.calls = new CountDownLatch(values.length);
        }

        @Override
        public long getAsLong() {
            int current = index.getAndIncrement();
            calls.countDown();
            if (current >= values.length) {
                return -1L;
            }
            return values[current];
        }

        private boolean awaitAll(long timeout, TimeUnit unit)
                throws InterruptedException {
            return calls.await(timeout, unit);
        }
    }
}
