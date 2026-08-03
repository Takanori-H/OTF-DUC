package ltsa.updatingControllers.cli;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

/**
 * Periodically samples the resident memory (RSS) of one operating-system
 * process.
 *
 * <p>The default provider uses OSHI and deliberately keeps a single
 * {@link OSProcess} instance. Each sample refreshes that instance with
 * {@link OSProcess#updateAttributes()} before reading
 * {@link OSProcess#getResidentMemory()}; it does not enumerate all processes
 * on every sampling interval.</p>
 *
 * <p>This class is a one-shot sampler. Repeated calls to {@link #start()} and
 * {@link #stop()} are safe, but a stopped sampler cannot be restarted. Only
 * aggregate data is retained; individual samples are not stored. Its peak is
 * therefore a sampled maximum of current RSS values, not an operating-system
 * lifetime high-water mark such as Windows {@code PeakWorkingSetSize}.</p>
 */
public final class ProcessRssSampler implements AutoCloseable {

    public static final long DEFAULT_INTERVAL_MILLIS = 50L;
    public static final long DEFAULT_PROVIDER_TIMEOUT_MILLIS = 5_000L;

    private static final long RSS_UNAVAILABLE = -1L;
    private static final long STOP_JOIN_TIMEOUT_MILLIS = 2_000L;
    private static final AtomicLong PROVIDER_THREAD_SEQUENCE = new AtomicLong();
    private static final AtomicBoolean DEFAULT_PROVIDER_CIRCUIT_OPEN =
            new AtomicBoolean();

    private final Object providerReadLock = new Object();
    private final Object stateLock = new Object();
    private final long pid;
    private final long intervalMillis;
    private final String provider;
    private final LongSupplier rssBytesSupplier;
    private final long providerTimeoutMillis;
    private final ExecutorService providerExecutor;
    private final boolean opensDefaultProviderCircuitOnTimeout;
    private final boolean providerDisabledByCircuitBreaker;

    private volatile boolean running;
    private boolean started;
    private boolean stopped;
    private Thread samplingThread;

    private long startedAtNanos = -1L;
    private long lastAttemptAtNanos = -1L;
    private long maxSampleGapNanos;
    private long sampleCount;
    private long sampleFailureCount;
    private long providerFailureCount;
    private long providerTimeoutCount;
    private long totalSamplingWallTimeNanos;
    private long samplerThreadCpuTimeNanos = RSS_UNAVAILABLE;
    private long providerThreadCpuTimeNanos;
    private boolean providerThreadCpuTimeAvailable;
    private int activeProviderTaskCount;
    private long lifetimePeakRssBytes;
    private long lifetimePeakAtEpochMillis = -1L;
    private long lifetimePeakAtElapsedMillis = -1L;

    private boolean synthesisWindowStarted;
    private boolean synthesisWindowCompleted;
    private boolean synthesisWindowOpen;
    private long synthesisWindowStartedAtNanos = -1L;
    private long synthesisWindowStartedAtEpochMillis = -1L;
    private long synthesisWindowCompletedAtEpochMillis = -1L;
    private long synthesisWindowSampleCount;
    private long synthesisWindowSampleFailureCount;
    private long synthesisWindowProviderFailureCount;
    private long synthesisWindowSamplingWallTimeNanos;
    private long synthesisWindowProviderThreadCpuTimeNanos;
    private boolean synthesisWindowProviderThreadCpuTimeAvailable;
    private long synthesisWindowPeakRssBytes;
    private long synthesisWindowPeakAtEpochMillis = -1L;
    private long synthesisWindowPeakAtElapsedMillis = -1L;
    private boolean synthesisWindowStartBoundarySampleSucceeded;
    private boolean synthesisWindowEndBoundarySampleSucceeded;

    /** Creates an OSHI sampler using the default 50 ms interval. */
    public ProcessRssSampler(long pid) {
        this(pid, DEFAULT_INTERVAL_MILLIS);
    }

    /**
     * Creates an OSHI sampler for one process.
     *
     * @param pid process identifier; must fit in OSHI's integer PID API
     * @param intervalMillis delay between periodic attempts, in milliseconds
     */
    public ProcessRssSampler(long pid, long intervalMillis) {
        this(
                pid,
                intervalMillis,
                defaultProviderName(),
                createOshiSupplier(pid),
                DEFAULT_PROVIDER_TIMEOUT_MILLIS,
                true);
    }

    /** Creates an OSHI sampler with an explicit per-read timeout. */
    public ProcessRssSampler(
            long pid,
            long intervalMillis,
            long providerTimeoutMillis) {
        this(
                pid,
                intervalMillis,
                defaultProviderName(),
                createOshiSupplier(pid),
                providerTimeoutMillis,
                true);
    }

    /**
     * Creates a sampler with an injected RSS supplier.
     *
     * <p>This overload supports tests and alternative process-metric
     * providers. The supplier returns RSS bytes, zero for a failed reading, or
     * a negative value when the process is no longer available. A runtime or
     * linkage exception is counted as a provider failure and terminates
     * sampling without propagating the exception to the batch runner.</p>
     */
    public ProcessRssSampler(
            long pid,
            long intervalMillis,
            String provider,
            LongSupplier rssBytesSupplier) {
        this(
                pid,
                intervalMillis,
                provider,
                rssBytesSupplier,
                DEFAULT_PROVIDER_TIMEOUT_MILLIS,
                false);
    }

    ProcessRssSampler(
            long pid,
            long intervalMillis,
            String provider,
            LongSupplier rssBytesSupplier,
            long providerTimeoutMillis) {
        this(
                pid,
                intervalMillis,
                provider,
                rssBytesSupplier,
                providerTimeoutMillis,
                false);
    }

    ProcessRssSampler(
            long pid,
            long intervalMillis,
            String provider,
            LongSupplier rssBytesSupplier,
            long providerTimeoutMillis,
            boolean opensDefaultProviderCircuitOnTimeout) {
        validatePid(pid);
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive: "
                    + intervalMillis);
        }
        if (providerTimeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "providerTimeoutMillis must be positive: "
                            + providerTimeoutMillis);
        }
        this.pid = pid;
        this.intervalMillis = intervalMillis;
        String normalizedProvider = normalizeProvider(provider);
        LongSupplier requestedSupplier = Objects.requireNonNull(
                rssBytesSupplier,
                "rssBytesSupplier");
        this.opensDefaultProviderCircuitOnTimeout =
                opensDefaultProviderCircuitOnTimeout;
        this.providerDisabledByCircuitBreaker =
                opensDefaultProviderCircuitOnTimeout
                        && DEFAULT_PROVIDER_CIRCUIT_OPEN.get();
        if (providerDisabledByCircuitBreaker) {
            this.provider = normalizedProvider + "_circuit_open";
            this.rssBytesSupplier = new LongSupplier() {
                @Override
                public long getAsLong() {
                    return RSS_UNAVAILABLE;
                }
            };
        } else {
            this.provider = normalizedProvider;
            this.rssBytesSupplier = requestedSupplier;
        }
        this.providerTimeoutMillis = providerTimeoutMillis;
        this.providerExecutor = Executors.newSingleThreadExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(
                                runnable,
                                "Process-RSS-Provider-"
                                        + PROVIDER_THREAD_SEQUENCE.incrementAndGet());
                        thread.setDaemon(true);
                        return thread;
                    }
                });
    }

    /**
     * Takes a synchronous boundary sample and then starts the daemon sampler.
     */
    public void start() {
        synchronized (providerReadLock) {
            synchronized (stateLock) {
                if (started || stopped) {
                    return;
                }
                started = true;
                running = true;
                startedAtNanos = System.nanoTime();
                samplingThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runSamplingLoop();
                    }
                }, "Process-RSS-Sampler-" + pid);
                samplingThread.setDaemon(true);
            }

            sampleOnceWithProviderLockHeld();

            synchronized (stateLock) {
                if (running && !stopped) {
                    samplingThread.start();
                }
            }
        }
    }

    /**
     * Opens the synthesis measurement window and takes a synchronous sample.
     *
     * @return true only when this call opens the window
     */
    public boolean beginSynthesisWindow() {
        synchronized (providerReadLock) {
            synchronized (stateLock) {
                if (!started || stopped || synthesisWindowStarted) {
                    return false;
                }
                synthesisWindowStarted = true;
                synthesisWindowOpen = true;
                synthesisWindowStartedAtNanos = System.nanoTime();
                synthesisWindowStartedAtEpochMillis = System.currentTimeMillis();
            }
            boolean boundarySampleSucceeded = sampleOnceWithProviderLockHeld();
            synchronized (stateLock) {
                synthesisWindowStartBoundarySampleSucceeded =
                        boundarySampleSucceeded;
            }
            return true;
        }
    }

    /**
     * Takes a synchronous final window sample and closes the synthesis window.
     *
     * @return true only when this call completes an open window
     */
    public boolean endSynthesisWindow() {
        synchronized (providerReadLock) {
            synchronized (stateLock) {
                if (stopped || !synthesisWindowOpen) {
                    return false;
                }
            }

            boolean boundarySampleSucceeded = sampleOnceWithProviderLockHeld();

            synchronized (stateLock) {
                synthesisWindowEndBoundarySampleSucceeded =
                        boundarySampleSucceeded;
                synthesisWindowOpen = false;
                synthesisWindowCompleted = true;
                synthesisWindowCompletedAtEpochMillis = System.currentTimeMillis();
            }
            return true;
        }
    }

    /**
     * Takes one synchronous final sample, stops the daemon thread, and returns
     * the frozen result. Repeated calls return the same aggregate values.
     */
    public Result stop() {
        Thread threadToJoin;
        synchronized (providerReadLock) {
            synchronized (stateLock) {
                if (stopped) {
                    return snapshotLocked();
                }
            }

            // Include the final process boundary when the provider is still live.
            sampleOnceWithProviderLockHeld();

            synchronized (stateLock) {
                stopped = true;
                running = false;
                threadToJoin = samplingThread;
                if (threadToJoin != null) {
                    threadToJoin.interrupt();
                }
            }
        }

        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(STOP_JOIN_TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        providerExecutor.shutdownNow();
        return snapshot();
    }

    /** Returns a consistent aggregate snapshot without stopping sampling. */
    public Result snapshot() {
        synchronized (stateLock) {
            return snapshotLocked();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public long getPid() {
        return pid;
    }

    public long getIntervalMillis() {
        return intervalMillis;
    }

    @Override
    public void close() {
        stop();
    }

    private void runSamplingLoop() {
        long cpuStartedAtNanos = currentThreadCpuTime();
        try {
            while (running) {
                try {
                    Thread.sleep(intervalMillis);
                } catch (InterruptedException e) {
                    if (!running) {
                        return;
                    }
                }

                synchronized (providerReadLock) {
                    if (!running) {
                        return;
                    }
                    sampleOnceWithProviderLockHeld();
                }
            }
        } finally {
            long cpuFinishedAtNanos = currentThreadCpuTime();
            synchronized (stateLock) {
                if (cpuStartedAtNanos >= 0L
                        && cpuFinishedAtNanos >= cpuStartedAtNanos) {
                    samplerThreadCpuTimeNanos =
                            cpuFinishedAtNanos - cpuStartedAtNanos;
                }
            }
        }
    }

    /** Must be called with {@link #providerReadLock} held. */
    private boolean sampleOnceWithProviderLockHeld() {
        synchronized (stateLock) {
            if (!started || stopped || !running) {
                return false;
            }
        }

        long attemptAtNanos = System.nanoTime();
        long rssBytes;
        try {
            rssBytes = readProviderWithTimeout();
        } catch (ProviderTimeoutException e) {
            recordProviderFailure(
                    attemptAtNanos,
                    nonNegativeDifference(System.nanoTime(), attemptAtNanos),
                    true);
            return false;
        } catch (RuntimeException e) {
            recordProviderFailure(
                    attemptAtNanos,
                    nonNegativeDifference(System.nanoTime(), attemptAtNanos),
                    false);
            return false;
        } catch (LinkageError e) {
            recordProviderFailure(
                    attemptAtNanos,
                    nonNegativeDifference(System.nanoTime(), attemptAtNanos),
                    false);
            return false;
        }

        long observedAtNanos = System.nanoTime();
        long samplingWallTimeNanos = nonNegativeDifference(
                observedAtNanos,
                attemptAtNanos);
        long observedAtEpochMillis = System.currentTimeMillis();
        synchronized (stateLock) {
            if (stopped) {
                return false;
            }
            recordAttempt(attemptAtNanos, samplingWallTimeNanos);
            if (rssBytes <= 0L) {
                sampleFailureCount++;
                if (synthesisWindowOpen) {
                    synthesisWindowSampleFailureCount++;
                }
                if (rssBytes < 0L) {
                    running = false;
                }
                return false;
            }
            recordReading(rssBytes, observedAtNanos, observedAtEpochMillis);
            return true;
        }
    }

    private void recordProviderFailure(
            long attemptAtNanos,
            long samplingWallTimeNanos,
            boolean timedOut) {
        synchronized (stateLock) {
            if (stopped) {
                return;
            }
            recordAttempt(attemptAtNanos, samplingWallTimeNanos);
            providerFailureCount++;
            if (timedOut) {
                providerTimeoutCount++;
            }
            if (synthesisWindowOpen) {
                synthesisWindowProviderFailureCount++;
            }
            running = false;
        }
    }

    private long readProviderWithTimeout() {
        Future<Long> future = providerExecutor.submit(() -> {
            long cpuStartedAtNanos = currentThreadCpuTime();
            boolean taskStartedInSynthesisWindow;
            synchronized (stateLock) {
                activeProviderTaskCount++;
                taskStartedInSynthesisWindow = synthesisWindowOpen;
            }
            try {
                return Long.valueOf(rssBytesSupplier.getAsLong());
            } finally {
                long cpuFinishedAtNanos = currentThreadCpuTime();
                synchronized (stateLock) {
                    activeProviderTaskCount--;
                    if (cpuStartedAtNanos >= 0L
                            && cpuFinishedAtNanos >= cpuStartedAtNanos) {
                        providerThreadCpuTimeNanos = saturatedAdd(
                                providerThreadCpuTimeNanos,
                                cpuFinishedAtNanos - cpuStartedAtNanos);
                        providerThreadCpuTimeAvailable = true;
                        if (taskStartedInSynthesisWindow) {
                            synthesisWindowProviderThreadCpuTimeNanos =
                                    saturatedAdd(
                                            synthesisWindowProviderThreadCpuTimeNanos,
                                            cpuFinishedAtNanos - cpuStartedAtNanos);
                            synthesisWindowProviderThreadCpuTimeAvailable = true;
                        }
                    }
                }
            }
        });
        try {
            return future.get(providerTimeoutMillis, TimeUnit.MILLISECONDS).longValue();
        } catch (TimeoutException e) {
            future.cancel(true);
            if (opensDefaultProviderCircuitOnTimeout) {
                DEFAULT_PROVIDER_CIRCUIT_OPEN.set(true);
            }
            throw new ProviderTimeoutException(e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RSS provider read interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LinkageError) {
                throw (LinkageError) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException("RSS provider read failed", cause);
        }
    }

    private void recordAttempt(long attemptAtNanos, long samplingWallTimeNanos) {
        if (lastAttemptAtNanos >= 0L) {
            long gap = attemptAtNanos - lastAttemptAtNanos;
            if (gap > maxSampleGapNanos) {
                maxSampleGapNanos = gap;
            }
        }
        lastAttemptAtNanos = attemptAtNanos;
        totalSamplingWallTimeNanos = saturatedAdd(
                totalSamplingWallTimeNanos,
                samplingWallTimeNanos);
        if (synthesisWindowOpen) {
            synthesisWindowSamplingWallTimeNanos = saturatedAdd(
                    synthesisWindowSamplingWallTimeNanos,
                    samplingWallTimeNanos);
        }
    }

    private void recordReading(
            long rssBytes,
            long observedAtNanos,
            long observedAtEpochMillis) {
        sampleCount++;
        if (rssBytes > lifetimePeakRssBytes) {
            lifetimePeakRssBytes = rssBytes;
            lifetimePeakAtEpochMillis = observedAtEpochMillis;
            lifetimePeakAtElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    observedAtNanos - startedAtNanos);
        }

        if (synthesisWindowOpen) {
            synthesisWindowSampleCount++;
            if (rssBytes > synthesisWindowPeakRssBytes) {
                synthesisWindowPeakRssBytes = rssBytes;
                synthesisWindowPeakAtEpochMillis = observedAtEpochMillis;
                synthesisWindowPeakAtElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                        observedAtNanos - synthesisWindowStartedAtNanos);
            }
        }
    }

    private Result snapshotLocked() {
        boolean lifetimeAvailable = sampleCount > 0L;
        boolean synthesisAvailable = synthesisWindowSampleCount > 0L;
        return new Result(
                pid,
                intervalMillis,
                providerTimeoutMillis,
                lifetimeAvailable ? lifetimePeakRssBytes : RSS_UNAVAILABLE,
                lifetimePeakAtEpochMillis,
                lifetimePeakAtElapsedMillis,
                sampleCount,
                sampleFailureCount,
                providerFailureCount,
                providerTimeoutCount,
                TimeUnit.NANOSECONDS.toMillis(maxSampleGapNanos),
                totalSamplingWallTimeNanos,
                samplerThreadCpuTimeNanos,
                providerThreadCpuTimeNanos,
                providerThreadCpuTimeAvailable,
                activeProviderTaskCount == 0 && providerTimeoutCount == 0L,
                provider,
                DEFAULT_PROVIDER_CIRCUIT_OPEN.get(),
                providerDisabledByCircuitBreaker,
                lifetimeAvailable,
                synthesisWindowStarted,
                synthesisWindowCompleted,
                synthesisWindowStartedAtEpochMillis,
                synthesisWindowCompletedAtEpochMillis,
                synthesisAvailable ? synthesisWindowPeakRssBytes : RSS_UNAVAILABLE,
                synthesisWindowPeakAtEpochMillis,
                synthesisWindowPeakAtElapsedMillis,
                synthesisWindowSampleCount,
                synthesisWindowSampleFailureCount,
                synthesisWindowProviderFailureCount,
                synthesisWindowSamplingWallTimeNanos,
                synthesisWindowProviderThreadCpuTimeNanos,
                synthesisWindowProviderThreadCpuTimeAvailable,
                synthesisWindowStartBoundarySampleSucceeded,
                synthesisWindowEndBoundarySampleSucceeded,
                synthesisAvailable);
    }

    private static LongSupplier createOshiSupplier(long pid) {
        validatePid(pid);
        return new OshiRssBytesSupplier(pid);
    }

    private static void validatePid(long pid) {
        if (pid <= 0L || pid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pid must be between 1 and "
                    + Integer.MAX_VALUE + ": " + pid);
        }
    }

    private static String defaultProviderName() {
        String osName = System.getProperty("os.name", "unknown")
                .toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return "oshi_windows";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "oshi_macos";
        }
        if (osName.contains("linux")) {
            return "oshi_linux";
        }
        String normalized = osName.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return "oshi_" + (normalized.isEmpty() ? "unknown" : normalized);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return "unknown";
        }
        return provider.trim();
    }

    static boolean isDefaultProviderCircuitOpen() {
        return DEFAULT_PROVIDER_CIRCUIT_OPEN.get();
    }

    static void resetDefaultProviderCircuitBreakerForTests() {
        DEFAULT_PROVIDER_CIRCUIT_OPEN.set(false);
    }

    private static long currentThreadCpuTime() {
        try {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            return threadBean.isCurrentThreadCpuTimeSupported()
                    && threadBean.isThreadCpuTimeEnabled()
                            ? threadBean.getCurrentThreadCpuTime()
                            : RSS_UNAVAILABLE;
        } catch (RuntimeException e) {
            return RSS_UNAVAILABLE;
        }
    }

    private static long nonNegativeDifference(long later, long earlier) {
        long difference = later - earlier;
        return difference >= 0L ? difference : 0L;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static final class ProviderTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ProviderTimeoutException(Throwable cause) {
            super(cause);
        }
    }

    private static final class OshiRssBytesSupplier implements LongSupplier {
        private final long pid;
        private OSProcess process;
        private boolean initialized;

        private OshiRssBytesSupplier(long pid) {
            this.pid = pid;
        }

        @Override
        public synchronized long getAsLong() {
            if (!initialized) {
                OperatingSystem operatingSystem =
                        new SystemInfo().getOperatingSystem();
                process = operatingSystem.getProcess((int) pid);
                initialized = true;
            }
            if (process == null) {
                return RSS_UNAVAILABLE;
            }
            if (!process.updateAttributes()) {
                return RSS_UNAVAILABLE;
            }
            return process.getResidentMemory();
        }
    }

    /** Immutable aggregate returned by {@link ProcessRssSampler}. */
    public static final class Result {
        private final long pid;
        private final long intervalMillis;
        private final long providerTimeoutMillis;
        private final long lifetimePeakRssBytes;
        private final long lifetimePeakAtEpochMillis;
        private final long lifetimePeakAtElapsedMillis;
        private final long sampleCount;
        private final long sampleFailureCount;
        private final long providerFailureCount;
        private final long providerTimeoutCount;
        private final long maxSampleGapMillis;
        private final long totalSamplingWallTimeNanos;
        private final long samplerThreadCpuTimeNanos;
        private final long providerThreadCpuTimeNanos;
        private final boolean providerThreadCpuTimeAvailable;
        private final boolean providerThreadCpuMeasurementComplete;
        private final String provider;
        private final boolean providerCircuitOpen;
        private final boolean providerDisabledByCircuitBreaker;
        private final boolean available;
        private final boolean synthesisWindowStarted;
        private final boolean synthesisWindowCompleted;
        private final long synthesisWindowStartedAtEpochMillis;
        private final long synthesisWindowCompletedAtEpochMillis;
        private final long synthesisWindowPeakRssBytes;
        private final long synthesisWindowPeakAtEpochMillis;
        private final long synthesisWindowPeakAtElapsedMillis;
        private final long synthesisWindowSampleCount;
        private final long synthesisWindowSampleFailureCount;
        private final long synthesisWindowProviderFailureCount;
        private final long synthesisWindowSamplingWallTimeNanos;
        private final long synthesisWindowProviderThreadCpuTimeNanos;
        private final boolean synthesisWindowProviderThreadCpuTimeAvailable;
        private final boolean synthesisWindowStartBoundarySampleSucceeded;
        private final boolean synthesisWindowEndBoundarySampleSucceeded;
        private final boolean synthesisWindowAvailable;

        private Result(
                long pid,
                long intervalMillis,
                long providerTimeoutMillis,
                long lifetimePeakRssBytes,
                long lifetimePeakAtEpochMillis,
                long lifetimePeakAtElapsedMillis,
                long sampleCount,
                long sampleFailureCount,
                long providerFailureCount,
                long providerTimeoutCount,
                long maxSampleGapMillis,
                long totalSamplingWallTimeNanos,
                long samplerThreadCpuTimeNanos,
                long providerThreadCpuTimeNanos,
                boolean providerThreadCpuTimeAvailable,
                boolean providerThreadCpuMeasurementComplete,
                String provider,
                boolean providerCircuitOpen,
                boolean providerDisabledByCircuitBreaker,
                boolean available,
                boolean synthesisWindowStarted,
                boolean synthesisWindowCompleted,
                long synthesisWindowStartedAtEpochMillis,
                long synthesisWindowCompletedAtEpochMillis,
                long synthesisWindowPeakRssBytes,
                long synthesisWindowPeakAtEpochMillis,
                long synthesisWindowPeakAtElapsedMillis,
                long synthesisWindowSampleCount,
                long synthesisWindowSampleFailureCount,
                long synthesisWindowProviderFailureCount,
                long synthesisWindowSamplingWallTimeNanos,
                long synthesisWindowProviderThreadCpuTimeNanos,
                boolean synthesisWindowProviderThreadCpuTimeAvailable,
                boolean synthesisWindowStartBoundarySampleSucceeded,
                boolean synthesisWindowEndBoundarySampleSucceeded,
                boolean synthesisWindowAvailable) {
            this.pid = pid;
            this.intervalMillis = intervalMillis;
            this.providerTimeoutMillis = providerTimeoutMillis;
            this.lifetimePeakRssBytes = lifetimePeakRssBytes;
            this.lifetimePeakAtEpochMillis = lifetimePeakAtEpochMillis;
            this.lifetimePeakAtElapsedMillis = lifetimePeakAtElapsedMillis;
            this.sampleCount = sampleCount;
            this.sampleFailureCount = sampleFailureCount;
            this.providerFailureCount = providerFailureCount;
            this.providerTimeoutCount = providerTimeoutCount;
            this.maxSampleGapMillis = maxSampleGapMillis;
            this.totalSamplingWallTimeNanos = totalSamplingWallTimeNanos;
            this.samplerThreadCpuTimeNanos = samplerThreadCpuTimeNanos;
            this.providerThreadCpuTimeNanos = providerThreadCpuTimeNanos;
            this.providerThreadCpuTimeAvailable = providerThreadCpuTimeAvailable;
            this.providerThreadCpuMeasurementComplete =
                    providerThreadCpuMeasurementComplete;
            this.provider = provider;
            this.providerCircuitOpen = providerCircuitOpen;
            this.providerDisabledByCircuitBreaker =
                    providerDisabledByCircuitBreaker;
            this.available = available;
            this.synthesisWindowStarted = synthesisWindowStarted;
            this.synthesisWindowCompleted = synthesisWindowCompleted;
            this.synthesisWindowStartedAtEpochMillis = synthesisWindowStartedAtEpochMillis;
            this.synthesisWindowCompletedAtEpochMillis = synthesisWindowCompletedAtEpochMillis;
            this.synthesisWindowPeakRssBytes = synthesisWindowPeakRssBytes;
            this.synthesisWindowPeakAtEpochMillis = synthesisWindowPeakAtEpochMillis;
            this.synthesisWindowPeakAtElapsedMillis = synthesisWindowPeakAtElapsedMillis;
            this.synthesisWindowSampleCount = synthesisWindowSampleCount;
            this.synthesisWindowSampleFailureCount = synthesisWindowSampleFailureCount;
            this.synthesisWindowProviderFailureCount = synthesisWindowProviderFailureCount;
            this.synthesisWindowSamplingWallTimeNanos =
                    synthesisWindowSamplingWallTimeNanos;
            this.synthesisWindowProviderThreadCpuTimeNanos =
                    synthesisWindowProviderThreadCpuTimeNanos;
            this.synthesisWindowProviderThreadCpuTimeAvailable =
                    synthesisWindowProviderThreadCpuTimeAvailable;
            this.synthesisWindowStartBoundarySampleSucceeded =
                    synthesisWindowStartBoundarySampleSucceeded;
            this.synthesisWindowEndBoundarySampleSucceeded =
                    synthesisWindowEndBoundarySampleSucceeded;
            this.synthesisWindowAvailable = synthesisWindowAvailable;
        }

        public long getPid() {
            return pid;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public long getProviderTimeoutMillis() {
            return providerTimeoutMillis;
        }

        /** Returns -1 when {@link #isAvailable()} is false. */
        public long getLifetimePeakRssBytes() {
            return lifetimePeakRssBytes;
        }

        public long getLifetimePeakAtEpochMillis() {
            return lifetimePeakAtEpochMillis;
        }

        public long getLifetimePeakAtElapsedMillis() {
            return lifetimePeakAtElapsedMillis;
        }

        /** Number of strictly positive lifetime RSS readings. */
        public long getSampleCount() {
            return sampleCount;
        }

        /** Number of non-positive readings, including process disappearance. */
        public long getSampleFailureCount() {
            return sampleFailureCount;
        }

        /** Number of supplier exceptions. */
        public long getProviderFailureCount() {
            return providerFailureCount;
        }

        public long getProviderTimeoutCount() {
            return providerTimeoutCount;
        }

        public long getSampleAttemptCount() {
            return sampleCount + sampleFailureCount + providerFailureCount;
        }

        /** Largest gap between the starts of consecutive sample attempts. */
        public long getMaxSampleGapMillis() {
            return maxSampleGapMillis;
        }

        /** Total caller wait time for RSS provider calls, capped per timeout. */
        public long getTotalSamplingWallTimeNanos() {
            return totalSamplingWallTimeNanos;
        }

        /** CPU time of the scheduling daemon; excludes provider execution. */
        public long getSamplerThreadCpuTimeNanos() {
            return samplerThreadCpuTimeNanos;
        }

        public boolean isSamplerThreadCpuTimeAvailable() {
            return samplerThreadCpuTimeNanos >= 0L;
        }

        /** CPU time accumulated inside completed provider tasks. */
        public long getProviderThreadCpuTimeNanos() {
            return providerThreadCpuTimeNanos;
        }

        public boolean isProviderThreadCpuTimeAvailable() {
            return providerThreadCpuTimeAvailable;
        }

        /** False after a provider timeout or while a provider task is active. */
        public boolean isProviderThreadCpuMeasurementComplete() {
            return providerThreadCpuMeasurementComplete;
        }

        public boolean isTotalMeasurementThreadCpuTimeAvailable() {
            return isSamplerThreadCpuTimeAvailable()
                    && providerThreadCpuTimeAvailable
                    && providerThreadCpuMeasurementComplete;
        }

        /** Scheduling plus provider CPU, or -1 when either part is incomplete. */
        public long getTotalMeasurementThreadCpuTimeNanos() {
            return isTotalMeasurementThreadCpuTimeAvailable()
                    ? saturatedAdd(
                            samplerThreadCpuTimeNanos,
                            providerThreadCpuTimeNanos)
                    : RSS_UNAVAILABLE;
        }

        public boolean isProviderCircuitOpen() {
            return providerCircuitOpen;
        }

        public boolean isProviderDisabledByCircuitBreaker() {
            return providerDisabledByCircuitBreaker;
        }

        public String getProvider() {
            return provider;
        }

        public boolean isAvailable() {
            return available;
        }

        public boolean isSynthesisWindowStarted() {
            return synthesisWindowStarted;
        }

        public boolean isSynthesisWindowCompleted() {
            return synthesisWindowCompleted;
        }

        public long getSynthesisWindowStartedAtEpochMillis() {
            return synthesisWindowStartedAtEpochMillis;
        }

        public long getSynthesisWindowCompletedAtEpochMillis() {
            return synthesisWindowCompletedAtEpochMillis;
        }

        /** Returns -1 when {@link #isSynthesisWindowAvailable()} is false. */
        public long getSynthesisWindowPeakRssBytes() {
            return synthesisWindowPeakRssBytes;
        }

        public long getSynthesisWindowPeakAtEpochMillis() {
            return synthesisWindowPeakAtEpochMillis;
        }

        /** Milliseconds after beginSynthesisWindow() at the peak. */
        public long getSynthesisWindowPeakAtElapsedMillis() {
            return synthesisWindowPeakAtElapsedMillis;
        }

        public long getSynthesisWindowSampleCount() {
            return synthesisWindowSampleCount;
        }

        public long getSynthesisWindowSampleFailureCount() {
            return synthesisWindowSampleFailureCount;
        }

        public long getSynthesisWindowProviderFailureCount() {
            return synthesisWindowProviderFailureCount;
        }

        /** Provider-result wait wall time accumulated while the window is open. */
        public long getSynthesisWindowSamplingWallTimeNanos() {
            return synthesisWindowSamplingWallTimeNanos;
        }

        public long getSynthesisWindowProviderThreadCpuTimeNanos() {
            return synthesisWindowProviderThreadCpuTimeNanos;
        }

        public boolean isSynthesisWindowProviderThreadCpuTimeAvailable() {
            return synthesisWindowProviderThreadCpuTimeAvailable;
        }

        public boolean isSynthesisWindowProviderThreadCpuMeasurementComplete() {
            return synthesisWindowCompleted
                    && providerThreadCpuMeasurementComplete;
        }

        /** Whether the synchronous sample taken before the start ACK succeeded. */
        public boolean isSynthesisWindowStartBoundarySampleSucceeded() {
            return synthesisWindowStartBoundarySampleSucceeded;
        }

        /** Whether the synchronous sample taken before the end ACK succeeded. */
        public boolean isSynthesisWindowEndBoundarySampleSucceeded() {
            return synthesisWindowEndBoundarySampleSucceeded;
        }

        public boolean isSynthesisWindowAvailable() {
            return synthesisWindowAvailable;
        }
    }
}
