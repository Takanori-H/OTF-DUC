package ltsa.updatingControllers.cli;

import java.util.Locale;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Best-effort reader for Windows' process-lifetime peak working-set value.
 *
 * <p>The value comes from {@code GetProcessMemoryInfo} and the
 * {@code PROCESS_MEMORY_COUNTERS.PeakWorkingSetSize} field. Unlike a sampled
 * RSS maximum, this is a high-water mark maintained by Windows. The process
 * handle is opened when {@link #open(long)} is called and deliberately kept
 * open until {@link #closeAndGet()} so that the final accounting information
 * can normally be queried after the child process has terminated.</p>
 *
 * <p>On a non-Windows host this class returns an unavailable result before any
 * Windows native provider is initialized. Native-load, access-denied, exited-
 * too-early, and query failures are data-quality outcomes rather than fatal
 * experiment-runner errors.</p>
 *
 * <p>The native path compiles against JNA/JNA-platform but has not been
 * exercised on the macOS development host. It must be included in the Windows
 * evaluation-machine preflight before its value is used in paper results.</p>
 */
public final class WindowsPeakWorkingSetProbe implements AutoCloseable {

    public static final String PROVIDER_NAME =
            "windows_psapi_get_process_memory_info_peak_working_set";

    private static final long UNAVAILABLE_BYTES = -1L;
    private static final int NO_NATIVE_ERROR = -1;

    private final long pid;
    private final Provider provider;
    private Result lastResult;
    private boolean closed;

    private WindowsPeakWorkingSetProbe(
            long pid,
            Provider provider,
            Result initialResult) {
        this.pid = pid;
        this.provider = provider;
        this.lastResult = initialResult;
    }

    /**
     * Opens a best-effort probe for {@code pid}.
     *
     * <p>Call this immediately after starting the child process, not only after
     * it exits. Keeping the handle open is what makes an after-wait final read
     * possible on Windows.</p>
     */
    public static WindowsPeakWorkingSetProbe open(long pid) {
        return openForOs(pid, System.getProperty("os.name", ""),
                new ProviderFactory() {
                    @Override
                    public Provider open(long requestedPid) throws Exception {
                        return NativeWindowsProvider.open(requestedPid);
                    }
                });
    }

    /**
     * Reads the high-water value accumulated by Windows up to this instant.
     *
     * <p>A final lifetime value should be obtained with
     * {@link #closeAndGet()} after the child has terminated.</p>
     */
    public synchronized Result read() {
        if (closed || provider == null) {
            return lastResult;
        }
        lastResult = queryProvider(false);
        return lastResult;
    }

    /**
     * Performs one final read, closes the retained process handle, and freezes
     * the result. Repeated calls are idempotent.
     */
    public synchronized Result closeAndGet() {
        if (closed) {
            return lastResult;
        }

        Result measured = provider == null ? lastResult : queryProvider(true);
        String closeFailureReason = "";
        int closeNativeErrorCode = NO_NATIVE_ERROR;
        if (provider != null) {
            try {
                provider.close();
            } catch (NativeProbeException e) {
                closeFailureReason = describeFailure("close_failed", e);
                closeNativeErrorCode = e.getNativeErrorCode();
            } catch (Exception e) {
                closeFailureReason = describeFailure("close_failed", e);
            } catch (LinkageError e) {
                closeFailureReason = describeFailure("close_linkage_failed", e);
            }
        }

        closed = true;
        lastResult = measured.withCloseOutcome(
                true,
                closeFailureReason,
                closeNativeErrorCode);
        return lastResult;
    }

    /** Returns the most recent result without calling the native provider. */
    public synchronized Result snapshot() {
        return lastResult;
    }

    @Override
    public void close() {
        closeAndGet();
    }

    private Result queryProvider(boolean finalRead) {
        long measuredAtEpochMillis = System.currentTimeMillis();
        try {
            long peakBytes = provider.readPeakWorkingSetSizeBytes();
            if (peakBytes < 0L) {
                return Result.unavailable(
                        pid,
                        provider.getProviderName(),
                        "provider_returned_negative_peak=" + peakBytes,
                        NO_NATIVE_ERROR,
                        measuredAtEpochMillis,
                        finalRead,
                        false);
            }
            return Result.available(
                    pid,
                    peakBytes,
                    provider.getProviderName(),
                    measuredAtEpochMillis,
                    finalRead,
                    false);
        } catch (NativeProbeException e) {
            return Result.unavailable(
                    pid,
                    provider.getProviderName(),
                    describeFailure("query_failed", e),
                    e.getNativeErrorCode(),
                    measuredAtEpochMillis,
                    finalRead,
                    false);
        } catch (Exception e) {
            return Result.unavailable(
                    pid,
                    provider.getProviderName(),
                    describeFailure("query_failed", e),
                    NO_NATIVE_ERROR,
                    measuredAtEpochMillis,
                    finalRead,
                    false);
        } catch (LinkageError e) {
            return Result.unavailable(
                    pid,
                    provider.getProviderName(),
                    describeFailure("query_linkage_failed", e),
                    NO_NATIVE_ERROR,
                    measuredAtEpochMillis,
                    finalRead,
                    false);
        }
    }

    static WindowsPeakWorkingSetProbe openForOs(
            long pid,
            String osName,
            ProviderFactory providerFactory) {
        validatePid(pid);
        String normalizedOsName = osName == null ? "" : osName.trim();
        if (!isWindows(normalizedOsName)) {
            Result unsupported = Result.unavailable(
                    pid,
                    PROVIDER_NAME,
                    "unsupported_os=" + safeText(normalizedOsName),
                    NO_NATIVE_ERROR,
                    System.currentTimeMillis(),
                    false,
                    false);
            return new WindowsPeakWorkingSetProbe(pid, null, unsupported);
        }
        if (providerFactory == null) {
            throw new NullPointerException("providerFactory");
        }

        try {
            Provider openedProvider = providerFactory.open(pid);
            if (openedProvider == null) {
                Result unavailable = Result.unavailable(
                        pid,
                        PROVIDER_NAME,
                        "provider_factory_returned_null",
                        NO_NATIVE_ERROR,
                        System.currentTimeMillis(),
                        false,
                        false);
                return new WindowsPeakWorkingSetProbe(pid, null, unavailable);
            }
            Result notReadYet = Result.unavailable(
                    pid,
                    openedProvider.getProviderName(),
                    "not_read_yet",
                    NO_NATIVE_ERROR,
                    -1L,
                    false,
                    false);
            return new WindowsPeakWorkingSetProbe(
                    pid,
                    openedProvider,
                    notReadYet);
        } catch (NativeProbeException e) {
            Result unavailable = Result.unavailable(
                    pid,
                    PROVIDER_NAME,
                    describeFailure("open_process_failed", e),
                    e.getNativeErrorCode(),
                    System.currentTimeMillis(),
                    false,
                    false);
            return new WindowsPeakWorkingSetProbe(pid, null, unavailable);
        } catch (Exception e) {
            Result unavailable = Result.unavailable(
                    pid,
                    PROVIDER_NAME,
                    describeFailure("provider_open_failed", e),
                    NO_NATIVE_ERROR,
                    System.currentTimeMillis(),
                    false,
                    false);
            return new WindowsPeakWorkingSetProbe(pid, null, unavailable);
        } catch (LinkageError e) {
            Result unavailable = Result.unavailable(
                    pid,
                    PROVIDER_NAME,
                    describeFailure("native_linkage_failed", e),
                    NO_NATIVE_ERROR,
                    System.currentTimeMillis(),
                    false,
                    false);
            return new WindowsPeakWorkingSetProbe(pid, null, unavailable);
        }
    }

    private static boolean isWindows(String osName) {
        return osName.toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static void validatePid(long pid) {
        if (pid <= 0L || pid > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "pid must be in range 1..Integer.MAX_VALUE: " + pid);
        }
    }

    private static String describeFailure(String prefix, Throwable failure) {
        StringBuilder text = new StringBuilder(prefix);
        text.append(':').append(failure.getClass().getSimpleName());
        String message = failure.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            text.append(':').append(safeText(message));
        }
        return text.toString();
    }

    private static String safeText(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    interface ProviderFactory {
        Provider open(long pid) throws Exception;
    }

    interface Provider extends AutoCloseable {
        long readPeakWorkingSetSizeBytes() throws Exception;

        String getProviderName();

        @Override
        void close() throws Exception;
    }

    private static final class NativeWindowsProvider implements Provider {

        private final HANDLE processHandle;
        private boolean closed;

        private NativeWindowsProvider(HANDLE processHandle) {
            this.processHandle = processHandle;
        }

        private static NativeWindowsProvider open(long pid)
                throws NativeProbeException {
            int processId = (int) pid;
            // Current Windows versions require one of the process-query rights.
            // The legacy fallback also requests VM_READ for XP/Server 2003.
            int limitedAccess = WinNT.PROCESS_QUERY_LIMITED_INFORMATION;
            HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                    limitedAccess,
                    false,
                    processId);
            if (isNullHandle(handle)) {
                int limitedError = Kernel32.INSTANCE.GetLastError();
                int legacyAccess = WinNT.PROCESS_QUERY_INFORMATION
                        | WinNT.PROCESS_VM_READ;
                handle = Kernel32.INSTANCE.OpenProcess(
                        legacyAccess,
                        false,
                        processId);
                if (isNullHandle(handle)) {
                    int legacyError = Kernel32.INSTANCE.GetLastError();
                    throw new NativeProbeException(
                            "OpenProcess failed; limitedError=" + limitedError
                                    + ", legacyError=" + legacyError,
                            legacyError);
                }
            }
            return new NativeWindowsProvider(handle);
        }

        @Override
        public long readPeakWorkingSetSizeBytes() throws NativeProbeException {
            if (closed) {
                throw new NativeProbeException("process handle is closed",
                        NO_NATIVE_ERROR);
            }
            ProcessMemoryCounters counters = new ProcessMemoryCounters();
            boolean success = PsapiProcessMemory.INSTANCE.GetProcessMemoryInfo(
                    processHandle,
                    counters,
                    counters.size());
            if (!success) {
                int error = Kernel32.INSTANCE.GetLastError();
                throw new NativeProbeException(
                        "GetProcessMemoryInfo failed", error);
            }
            counters.read();
            return counters.PeakWorkingSetSize.longValue();
        }

        @Override
        public String getProviderName() {
            return PROVIDER_NAME;
        }

        @Override
        public void close() throws NativeProbeException {
            if (closed) {
                return;
            }
            closed = true;
            if (!Kernel32.INSTANCE.CloseHandle(processHandle)) {
                int error = Kernel32.INSTANCE.GetLastError();
                throw new NativeProbeException("CloseHandle failed", error);
            }
        }

        private static boolean isNullHandle(HANDLE handle) {
            return handle == null
                    || handle.getPointer() == null
                    || Pointer.NULL.equals(handle.getPointer());
        }
    }

    private interface PsapiProcessMemory extends StdCallLibrary {
        PsapiProcessMemory INSTANCE = Native.load(
                "Psapi",
                PsapiProcessMemory.class,
                W32APIOptions.DEFAULT_OPTIONS);

        boolean GetProcessMemoryInfo(
                HANDLE process,
                ProcessMemoryCounters counters,
                int size);
    }

    @Structure.FieldOrder({
            "cb",
            "PageFaultCount",
            "PeakWorkingSetSize",
            "WorkingSetSize",
            "QuotaPeakPagedPoolUsage",
            "QuotaPagedPoolUsage",
            "QuotaPeakNonPagedPoolUsage",
            "QuotaNonPagedPoolUsage",
            "PagefileUsage",
            "PeakPagefileUsage"
    })
    public static final class ProcessMemoryCounters extends Structure {
        public DWORD cb;
        public DWORD PageFaultCount;
        public SIZE_T PeakWorkingSetSize;
        public SIZE_T WorkingSetSize;
        public SIZE_T QuotaPeakPagedPoolUsage;
        public SIZE_T QuotaPagedPoolUsage;
        public SIZE_T QuotaPeakNonPagedPoolUsage;
        public SIZE_T QuotaNonPagedPoolUsage;
        public SIZE_T PagefileUsage;
        public SIZE_T PeakPagefileUsage;

        public ProcessMemoryCounters() {
            cb = new DWORD(size());
        }
    }

    private static final class NativeProbeException extends Exception {
        private static final long serialVersionUID = 1L;

        private final int nativeErrorCode;

        private NativeProbeException(String message, int nativeErrorCode) {
            super(message);
            this.nativeErrorCode = nativeErrorCode;
        }

        private int getNativeErrorCode() {
            return nativeErrorCode;
        }
    }

    /** Immutable outcome of one high-water query or probe-open attempt. */
    public static final class Result {
        private final long pid;
        private final boolean available;
        private final long peakWorkingSetSizeBytes;
        private final String provider;
        private final String failureReason;
        private final int nativeErrorCode;
        private final long measuredAtEpochMillis;
        private final boolean finalRead;
        private final boolean probeClosed;
        private final String closeFailureReason;
        private final int closeNativeErrorCode;

        private Result(
                long pid,
                boolean available,
                long peakWorkingSetSizeBytes,
                String provider,
                String failureReason,
                int nativeErrorCode,
                long measuredAtEpochMillis,
                boolean finalRead,
                boolean probeClosed,
                String closeFailureReason,
                int closeNativeErrorCode) {
            this.pid = pid;
            this.available = available;
            this.peakWorkingSetSizeBytes = peakWorkingSetSizeBytes;
            this.provider = provider;
            this.failureReason = failureReason;
            this.nativeErrorCode = nativeErrorCode;
            this.measuredAtEpochMillis = measuredAtEpochMillis;
            this.finalRead = finalRead;
            this.probeClosed = probeClosed;
            this.closeFailureReason = closeFailureReason;
            this.closeNativeErrorCode = closeNativeErrorCode;
        }

        private static Result available(
                long pid,
                long peakWorkingSetSizeBytes,
                String provider,
                long measuredAtEpochMillis,
                boolean finalRead,
                boolean probeClosed) {
            return new Result(
                    pid,
                    true,
                    peakWorkingSetSizeBytes,
                    provider,
                    "",
                    NO_NATIVE_ERROR,
                    measuredAtEpochMillis,
                    finalRead,
                    probeClosed,
                    "",
                    NO_NATIVE_ERROR);
        }

        private static Result unavailable(
                long pid,
                String provider,
                String failureReason,
                int nativeErrorCode,
                long measuredAtEpochMillis,
                boolean finalRead,
                boolean probeClosed) {
            return new Result(
                    pid,
                    false,
                    UNAVAILABLE_BYTES,
                    provider,
                    failureReason,
                    nativeErrorCode,
                    measuredAtEpochMillis,
                    finalRead,
                    probeClosed,
                    "",
                    NO_NATIVE_ERROR);
        }

        private Result withCloseOutcome(
                boolean newProbeClosed,
                String newCloseFailureReason,
                int newCloseNativeErrorCode) {
            return new Result(
                    pid,
                    available,
                    peakWorkingSetSizeBytes,
                    provider,
                    failureReason,
                    nativeErrorCode,
                    measuredAtEpochMillis,
                    finalRead,
                    newProbeClosed,
                    newCloseFailureReason,
                    newCloseNativeErrorCode);
        }

        public long getPid() {
            return pid;
        }

        public boolean isAvailable() {
            return available;
        }

        public long getPeakWorkingSetSizeBytes() {
            return peakWorkingSetSizeBytes;
        }

        public String getProvider() {
            return provider;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public int getNativeErrorCode() {
            return nativeErrorCode;
        }

        public long getMeasuredAtEpochMillis() {
            return measuredAtEpochMillis;
        }

        public boolean isFinalRead() {
            return finalRead;
        }

        public boolean isProbeClosed() {
            return probeClosed;
        }

        public String getCloseFailureReason() {
            return closeFailureReason;
        }

        public int getCloseNativeErrorCode() {
            return closeNativeErrorCode;
        }
    }
}
