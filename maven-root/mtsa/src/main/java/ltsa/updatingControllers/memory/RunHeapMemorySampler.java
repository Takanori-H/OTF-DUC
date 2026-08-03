package ltsa.updatingControllers.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Samples heap usage for one experiment run and retains aggregate values only.
 *
 * <p>The first sample is taken synchronously by {@link #start()}, before the
 * daemon sampling thread is started.  {@link #stop()} first terminates that
 * thread and then takes a final synchronous sample.  Consequently, a normally
 * started and stopped sampler has at least two sampling attempts even when the
 * measured operation is shorter than the configured interval.</p>
 *
 * <p>This class deliberately does not retain individual samples.  A failed
 * heap read is counted and ignored so that a transient management-interface
 * failure does not terminate the measured run.</p>
 */
public final class RunHeapMemorySampler implements AutoCloseable {

    public static final long DEFAULT_INTERVAL_MILLIS = 50L;
    public static final long VALUE_UNAVAILABLE = -1L;

    private static final AtomicLong THREAD_SEQUENCE = new AtomicLong();

    private final long intervalMillis;
    private final LongSupplier heapUsedBytesSupplier;
    private final LongSupplier nanoTimeSupplier;
    private final LongSupplier epochMillisSupplier;
    private final LongSupplier currentThreadCpuTimeSupplier;
    private final Object lifecycleLock = new Object();
    private final Object aggregateLock = new Object();

    private Lifecycle lifecycle = Lifecycle.NEW;
    private volatile boolean stopRequested;
    private Thread samplingThread;
    private Result stoppedResult;

    // Guarded by aggregateLock.
    private long firstHeapUsedBytes = VALUE_UNAVAILABLE;
    private long firstTimestampEpochMillis = VALUE_UNAVAILABLE;
    private long peakHeapUsedBytes = VALUE_UNAVAILABLE;
    private long peakTimestampEpochMillis = VALUE_UNAVAILABLE;
    private long successfulSampleCount;
    private long failedSampleCount;
    private boolean hasPreviousAttempt;
    private long previousAttemptStartedAtNanos;
    private long maxSampleGapNanos;
    private long totalSamplingWallTimeNanos;
    private long samplerThreadCpuTimeNanos = VALUE_UNAVAILABLE;

    /**
     * Creates a sampler backed by
     * {@code MemoryMXBean.getHeapMemoryUsage().getUsed()}.
     *
     * @param intervalMillis delay between background sampling attempts
     */
    public RunHeapMemorySampler(long intervalMillis) {
        this(intervalMillis, defaultHeapUsedBytesSupplier());
    }

    /**
     * Creates a sampler with an injectable heap-usage source.
     *
     * <p>The supplier must return heap usage in bytes.  A negative value or a
     * {@link RuntimeException} is treated as one failed sample.</p>
     *
     * @param intervalMillis delay between background sampling attempts
     * @param heapUsedBytesSupplier heap usage source, in bytes
     */
    public RunHeapMemorySampler(
            long intervalMillis,
            LongSupplier heapUsedBytesSupplier) {
        this(
                intervalMillis,
                heapUsedBytesSupplier,
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        return System.nanoTime();
                    }
                },
                new LongSupplier() {
                    @Override
                    public long getAsLong() {
                        return System.currentTimeMillis();
                    }
                },
                defaultCurrentThreadCpuTimeSupplier());
    }

    RunHeapMemorySampler(
            long intervalMillis,
            LongSupplier heapUsedBytesSupplier,
            LongSupplier nanoTimeSupplier,
            LongSupplier epochMillisSupplier,
            LongSupplier currentThreadCpuTimeSupplier) {
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        if (heapUsedBytesSupplier == null) {
            throw new NullPointerException("heapUsedBytesSupplier");
        }
        if (nanoTimeSupplier == null) {
            throw new NullPointerException("nanoTimeSupplier");
        }
        if (epochMillisSupplier == null) {
            throw new NullPointerException("epochMillisSupplier");
        }
        if (currentThreadCpuTimeSupplier == null) {
            throw new NullPointerException("currentThreadCpuTimeSupplier");
        }
        this.intervalMillis = intervalMillis;
        this.heapUsedBytesSupplier = heapUsedBytesSupplier;
        this.nanoTimeSupplier = nanoTimeSupplier;
        this.epochMillisSupplier = epochMillisSupplier;
        this.currentThreadCpuTimeSupplier = currentThreadCpuTimeSupplier;
    }

    /**
     * Takes the initial synchronous sample and starts background sampling.
     * Calling this method again while the sampler is running has no effect.
     * A stopped sampler cannot be restarted.
     *
     * @return this sampler
     */
    public RunHeapMemorySampler start() {
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.RUNNING) {
                return this;
            }
            if (lifecycle != Lifecycle.NEW) {
                throw new IllegalStateException("A stopped heap sampler cannot be restarted");
            }

            takeSample();
            stopRequested = false;
            samplingThread = new Thread(
                    new Runnable() {
                        @Override
                        public void run() {
                            runSamplingLoop();
                        }
                    },
                    "MTSA-Run-Heap-Sampler-" + THREAD_SEQUENCE.incrementAndGet());
            samplingThread.setDaemon(true);
            lifecycle = Lifecycle.RUNNING;
            samplingThread.start();
            return this;
        }
    }

    /**
     * Stops background sampling, takes the final synchronous sample, and
     * returns the immutable aggregate.  Repeated calls return the same result
     * without taking additional samples.
     *
     * @return final aggregate result
     */
    public Result stop() {
        Thread threadToJoin;
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.STOPPED) {
                return stoppedResult;
            }
            if (lifecycle == Lifecycle.STOPPING) {
                return awaitStoppedResultLocked();
            }
            if (lifecycle == Lifecycle.NEW) {
                lifecycle = Lifecycle.STOPPED;
                stoppedResult = snapshotAggregate();
                lifecycleLock.notifyAll();
                return stoppedResult;
            }

            lifecycle = Lifecycle.STOPPING;
            stopRequested = true;
            threadToJoin = samplingThread;
            if (threadToJoin != null) {
                threadToJoin.interrupt();
            }
        }

        boolean interrupted = joinUninterruptibly(threadToJoin);
        try {
            takeSample();
        } finally {
            synchronized (lifecycleLock) {
                stoppedResult = snapshotAggregate();
                samplingThread = null;
                lifecycle = Lifecycle.STOPPED;
                lifecycleLock.notifyAll();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return stoppedResult;
    }

    /**
     * Returns whether background sampling is currently active.
     */
    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return lifecycle == Lifecycle.RUNNING;
        }
    }

    @Override
    public void close() {
        stop();
    }

    private void runSamplingLoop() {
        long cpuStartedAtNanos = readCurrentThreadCpuTime();
        try {
            while (!stopRequested) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    // stop() interrupts this daemon so it can finish promptly.
                }
                if (!stopRequested) {
                    takeSample();
                }
            }
        } finally {
            long cpuFinishedAtNanos = readCurrentThreadCpuTime();
            synchronized (aggregateLock) {
                if (cpuStartedAtNanos >= 0L && cpuFinishedAtNanos >= cpuStartedAtNanos) {
                    samplerThreadCpuTimeNanos = cpuFinishedAtNanos - cpuStartedAtNanos;
                } else {
                    samplerThreadCpuTimeNanos = VALUE_UNAVAILABLE;
                }
            }
        }
    }

    private void takeSample() {
        long attemptStartedAtNanos = nanoTimeSupplier.getAsLong();
        long timestampEpochMillis = epochMillisSupplier.getAsLong();
        long heapUsedBytes = VALUE_UNAVAILABLE;
        boolean succeeded = false;
        try {
            heapUsedBytes = heapUsedBytesSupplier.getAsLong();
            succeeded = heapUsedBytes >= 0L;
        } catch (RuntimeException ignored) {
            // A transient provider failure must not stop the measured operation.
        }
        long attemptFinishedAtNanos = nanoTimeSupplier.getAsLong();
        long samplingWallTimeNanos = nonNegativeDifference(
                attemptFinishedAtNanos,
                attemptStartedAtNanos);

        synchronized (aggregateLock) {
            if (hasPreviousAttempt) {
                long gap = nonNegativeDifference(
                        attemptStartedAtNanos,
                        previousAttemptStartedAtNanos);
                if (gap > maxSampleGapNanos) {
                    maxSampleGapNanos = gap;
                }
            }
            hasPreviousAttempt = true;
            previousAttemptStartedAtNanos = attemptStartedAtNanos;
            totalSamplingWallTimeNanos = saturatedAdd(
                    totalSamplingWallTimeNanos,
                    samplingWallTimeNanos);

            if (!succeeded) {
                failedSampleCount = saturatedIncrement(failedSampleCount);
                return;
            }

            successfulSampleCount = saturatedIncrement(successfulSampleCount);
            if (firstHeapUsedBytes == VALUE_UNAVAILABLE) {
                firstHeapUsedBytes = heapUsedBytes;
                firstTimestampEpochMillis = timestampEpochMillis;
            }
            if (heapUsedBytes > peakHeapUsedBytes) {
                peakHeapUsedBytes = heapUsedBytes;
                peakTimestampEpochMillis = timestampEpochMillis;
            }
        }
    }

    private Result awaitStoppedResultLocked() {
        boolean interrupted = false;
        while (lifecycle != Lifecycle.STOPPED) {
            try {
                lifecycleLock.wait();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return stoppedResult;
    }

    private Result snapshotAggregate() {
        synchronized (aggregateLock) {
            return new Result(
                    intervalMillis,
                    firstHeapUsedBytes,
                    firstTimestampEpochMillis,
                    peakHeapUsedBytes,
                    peakTimestampEpochMillis,
                    successfulSampleCount,
                    failedSampleCount,
                    maxSampleGapNanos,
                    totalSamplingWallTimeNanos,
                    samplerThreadCpuTimeNanos);
        }
    }

    private long readCurrentThreadCpuTime() {
        try {
            return currentThreadCpuTimeSupplier.getAsLong();
        } catch (RuntimeException ignored) {
            return VALUE_UNAVAILABLE;
        }
    }

    private static boolean joinUninterruptibly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return false;
        }
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static long nonNegativeDifference(long later, long earlier) {
        long difference = later - earlier;
        return difference >= 0L ? difference : 0L;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static LongSupplier defaultHeapUsedBytesSupplier() {
        final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return memoryBean.getHeapMemoryUsage().getUsed();
            }
        };
    }

    private static LongSupplier defaultCurrentThreadCpuTimeSupplier() {
        final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        final boolean supported = threadBean.isCurrentThreadCpuTimeSupported()
                && threadBean.isThreadCpuTimeEnabled();
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return supported
                        ? threadBean.getCurrentThreadCpuTime()
                        : VALUE_UNAVAILABLE;
            }
        };
    }

    private enum Lifecycle {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED
    }

    /**
     * Immutable aggregate for one sampler lifetime.
     */
    public static final class Result {
        private final long intervalMillis;
        private final long firstHeapUsedBytes;
        private final long firstTimestampEpochMillis;
        private final long peakHeapUsedBytes;
        private final long peakTimestampEpochMillis;
        private final long sampleCount;
        private final long failedSampleCount;
        private final long maxSampleGapNanos;
        private final long totalSamplingWallTimeNanos;
        private final long samplerThreadCpuTimeNanos;

        private Result(
                long intervalMillis,
                long firstHeapUsedBytes,
                long firstTimestampEpochMillis,
                long peakHeapUsedBytes,
                long peakTimestampEpochMillis,
                long sampleCount,
                long failedSampleCount,
                long maxSampleGapNanos,
                long totalSamplingWallTimeNanos,
                long samplerThreadCpuTimeNanos) {
            this.intervalMillis = intervalMillis;
            this.firstHeapUsedBytes = firstHeapUsedBytes;
            this.firstTimestampEpochMillis = firstTimestampEpochMillis;
            this.peakHeapUsedBytes = peakHeapUsedBytes;
            this.peakTimestampEpochMillis = peakTimestampEpochMillis;
            this.sampleCount = sampleCount;
            this.failedSampleCount = failedSampleCount;
            this.maxSampleGapNanos = maxSampleGapNanos;
            this.totalSamplingWallTimeNanos = totalSamplingWallTimeNanos;
            this.samplerThreadCpuTimeNanos = samplerThreadCpuTimeNanos;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        /**
         * Returns the first successfully sampled heap usage, or
         * {@link RunHeapMemorySampler#VALUE_UNAVAILABLE} if every read failed.
         */
        public long getFirstHeapUsedBytes() {
            return firstHeapUsedBytes;
        }

        public long getFirstTimestampEpochMillis() {
            return firstTimestampEpochMillis;
        }

        public long getPeakHeapUsedBytes() {
            return peakHeapUsedBytes;
        }

        public long getPeakTimestampEpochMillis() {
            return peakTimestampEpochMillis;
        }

        /**
         * Returns the number of successful heap-usage reads.
         */
        public long getSampleCount() {
            return sampleCount;
        }

        public long getFailedSampleCount() {
            return failedSampleCount;
        }

        public long getAttemptCount() {
            return saturatedAdd(sampleCount, failedSampleCount);
        }

        /**
         * Returns true once at least one valid heap-usage value was sampled.
         */
        public boolean isAvailable() {
            return sampleCount > 0L;
        }

        public long getMaxSampleGapNanos() {
            return maxSampleGapNanos;
        }

        public long getMaxSampleGapMillis() {
            return TimeUnit.NANOSECONDS.toMillis(maxSampleGapNanos);
        }

        /**
         * Returns the accumulated wall-clock duration of all supplier calls,
         * including the synchronous start and stop samples.
         */
        public long getTotalSamplingWallTimeNanos() {
            return totalSamplingWallTimeNanos;
        }

        /**
         * Returns CPU time used by the daemon sampling thread, or
         * {@link RunHeapMemorySampler#VALUE_UNAVAILABLE} when JVM thread CPU
         * time is unsupported or disabled.  Synchronous start/stop sampling
         * runs on the caller thread and is therefore represented only in
         * {@link #getTotalSamplingWallTimeNanos()}.
         */
        public long getSamplerThreadCpuTimeNanos() {
            return samplerThreadCpuTimeNanos;
        }

        public boolean isSamplerThreadCpuTimeAvailable() {
            return samplerThreadCpuTimeNanos >= 0L;
        }
    }
}
