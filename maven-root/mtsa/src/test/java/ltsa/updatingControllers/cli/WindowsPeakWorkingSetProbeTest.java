package ltsa.updatingControllers.cli;

import java.util.concurrent.atomic.AtomicInteger;

import com.sun.jna.Native;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WindowsPeakWorkingSetProbeTest {

    private static final long TEST_PID = 4321L;

    @Test
    public void readsAndFreezesInjectedProviderResult() {
        FakeProvider provider = new FakeProvider(1024L, 4096L);
        WindowsPeakWorkingSetProbe probe = openWithProvider(provider);

        WindowsPeakWorkingSetProbe.Result first = probe.read();
        WindowsPeakWorkingSetProbe.Result finalResult = probe.closeAndGet();
        WindowsPeakWorkingSetProbe.Result repeated = probe.closeAndGet();

        assertTrue(first.isAvailable());
        assertEquals(1024L, first.getPeakWorkingSetSizeBytes());
        assertFalse(first.isFinalRead());
        assertFalse(first.isProbeClosed());

        assertTrue(finalResult.isAvailable());
        assertEquals(4096L, finalResult.getPeakWorkingSetSizeBytes());
        assertEquals("fake_windows_high_water", finalResult.getProvider());
        assertTrue(finalResult.getFailureReason().isEmpty());
        assertTrue(finalResult.isFinalRead());
        assertTrue(finalResult.isProbeClosed());
        assertTrue(finalResult.getCloseFailureReason().isEmpty());
        assertEquals(1, provider.getCloseCount());
        assertEquals(2, provider.getReadCount());

        assertEquals(finalResult.getPeakWorkingSetSizeBytes(),
                repeated.getPeakWorkingSetSizeBytes());
        assertEquals(1, provider.getCloseCount());
        assertEquals(2, provider.getReadCount());
    }

    @Test
    public void nonWindowsHostDoesNotOpenProvider() {
        final AtomicInteger factoryCalls = new AtomicInteger();
        WindowsPeakWorkingSetProbe probe =
                WindowsPeakWorkingSetProbe.openForOs(
                        TEST_PID,
                        "Mac OS X",
                        new WindowsPeakWorkingSetProbe.ProviderFactory() {
                            @Override
                            public WindowsPeakWorkingSetProbe.Provider open(
                                    long pid) {
                                factoryCalls.incrementAndGet();
                                return new FakeProvider(1L);
                            }
                        });

        WindowsPeakWorkingSetProbe.Result result = probe.closeAndGet();

        assertEquals(0, factoryCalls.get());
        assertFalse(result.isAvailable());
        assertEquals(-1L, result.getPeakWorkingSetSizeBytes());
        assertEquals(WindowsPeakWorkingSetProbe.PROVIDER_NAME,
                result.getProvider());
        assertTrue(result.getFailureReason().contains("unsupported_os=Mac OS X"));
        assertTrue(result.isProbeClosed());
    }

    @Test
    public void queryFailureIsReportedWithoutEscaping() {
        WindowsPeakWorkingSetProbe.Provider provider =
                new WindowsPeakWorkingSetProbe.Provider() {
                    @Override
                    public long readPeakWorkingSetSizeBytes() {
                        throw new IllegalStateException("synthetic query failure");
                    }

                    @Override
                    public String getProviderName() {
                        return "failing_provider";
                    }

                    @Override
                    public void close() {
                    }
                };
        WindowsPeakWorkingSetProbe probe = openWithProvider(provider);

        WindowsPeakWorkingSetProbe.Result result = probe.closeAndGet();

        assertFalse(result.isAvailable());
        assertEquals(-1L, result.getPeakWorkingSetSizeBytes());
        assertEquals("failing_provider", result.getProvider());
        assertTrue(result.getFailureReason().contains("query_failed"));
        assertTrue(result.getFailureReason().contains("synthetic query failure"));
        assertTrue(result.isFinalRead());
        assertTrue(result.isProbeClosed());
    }

    @Test
    public void invalidOrNegativeProviderValueIsUnavailable() {
        WindowsPeakWorkingSetProbe probe =
                openWithProvider(new FakeProvider(-2L));

        WindowsPeakWorkingSetProbe.Result result = probe.closeAndGet();

        assertFalse(result.isAvailable());
        assertEquals(-1L, result.getPeakWorkingSetSizeBytes());
        assertTrue(result.getFailureReason().contains(
                "provider_returned_negative_peak=-2"));
    }

    @Test
    public void validatesPidBeforeOsOrNativeChecks() {
        assertInvalidPid(0L);
        assertInvalidPid((long) Integer.MAX_VALUE + 1L);
    }

    @Test
    public void processMemoryCountersMatchesPointerSizedWindowsLayout() {
        WindowsPeakWorkingSetProbe.ProcessMemoryCounters counters =
                new WindowsPeakWorkingSetProbe.ProcessMemoryCounters();

        int expectedSize = 8 + (8 * Native.POINTER_SIZE);
        assertEquals(expectedSize, counters.size());
        assertEquals(expectedSize, counters.cb.intValue());
    }

    private static WindowsPeakWorkingSetProbe openWithProvider(
            final WindowsPeakWorkingSetProbe.Provider provider) {
        return WindowsPeakWorkingSetProbe.openForOs(
                TEST_PID,
                "Windows 11",
                new WindowsPeakWorkingSetProbe.ProviderFactory() {
                    @Override
                    public WindowsPeakWorkingSetProbe.Provider open(long pid) {
                        assertEquals(TEST_PID, pid);
                        return provider;
                    }
                });
    }

    private static void assertInvalidPid(long pid) {
        try {
            WindowsPeakWorkingSetProbe.openForOs(
                    pid,
                    "Mac OS X",
                    new WindowsPeakWorkingSetProbe.ProviderFactory() {
                        @Override
                        public WindowsPeakWorkingSetProbe.Provider open(
                                long ignored) {
                            fail("provider must not be invoked");
                            return null;
                        }
                    });
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("pid"));
        }
    }

    private static final class FakeProvider
            implements WindowsPeakWorkingSetProbe.Provider {
        private final long[] values;
        private int nextValue;
        private int readCount;
        private int closeCount;

        private FakeProvider(long... values) {
            this.values = values;
        }

        @Override
        public long readPeakWorkingSetSizeBytes() {
            int index = Math.min(nextValue, values.length - 1);
            nextValue++;
            readCount++;
            return values[index];
        }

        @Override
        public String getProviderName() {
            return "fake_windows_high_water";
        }

        @Override
        public void close() {
            closeCount++;
        }

        private int getReadCount() {
            return readCount;
        }

        private int getCloseCount() {
            return closeCount;
        }
    }
}
