package ltsa.updatingControllers.memory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Child-to-parent markers used by the batch runner to align process RSS
 * sampling with the controller-synthesis measurement window.
 *
 * <p>The markers are deliberately plain ASCII and emitted as complete lines.
 * They are measurement metadata, not LTS events.</p>
 */
public final class MemoryMeasurementProtocol {

    public static final String VERSION = "1";

    public static final String ENABLED_PROPERTY =
            "mtsa.evaluation.memoryProtocol.enabled";
    public static final String ACK_TIMEOUT_MILLIS_PROPERTY =
            "mtsa.evaluation.memoryProtocol.ackTimeoutMillis";
    public static final long DEFAULT_ACK_TIMEOUT_MILLIS = 30_000L;

    public static final String SYNTHESIS_WINDOW_START =
            "@@MTSA_MEMORY_SYNTHESIS_WINDOW_START@@";
    public static final String SYNTHESIS_WINDOW_END =
            "@@MTSA_MEMORY_SYNTHESIS_WINDOW_END@@";
    public static final String SYNTHESIS_WINDOW_START_ACK =
            "@@MTSA_MEMORY_SYNTHESIS_WINDOW_START_ACK@@";
    public static final String SYNTHESIS_WINDOW_END_ACK =
            "@@MTSA_MEMORY_SYNTHESIS_WINDOW_END_ACK@@";
    public static final String PROTOCOL_ERROR_PREFIX =
            "@@MTSA_MEMORY_PROTOCOL_ERROR@@";

    private static final String INPUT_EOF = "@@MTSA_MEMORY_PROTOCOL_INPUT_EOF@@";
    private static final LinkedBlockingQueue<String> ACK_LINES =
            new LinkedBlockingQueue<String>();
    private static boolean ackReaderStarted;

    private MemoryMeasurementProtocol() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    /**
     * Announces the start boundary and, in batch mode, waits until the parent
     * has opened the RSS sampling window. The wait is bounded so a broken
     * measurement channel cannot block controller synthesis indefinitely.
     */
    public static boolean emitSynthesisWindowStart() {
        return emitAndAwaitAck(
                SYNTHESIS_WINDOW_START,
                SYNTHESIS_WINDOW_START_ACK,
                "start");
    }

    /**
     * Announces the end boundary and waits until the parent has taken the
     * synchronous final RSS sample and closed the window.
     */
    public static boolean emitSynthesisWindowEnd() {
        return emitAndAwaitAck(
                SYNTHESIS_WINDOW_END,
                SYNTHESIS_WINDOW_END_ACK,
                "end");
    }

    private static boolean emitAndAwaitAck(
            String marker,
            String expectedAck,
            String phase) {
        if (!isEnabled()) {
            return true;
        }
        System.out.println(marker);
        System.out.flush();
        ensureAckReaderStarted();

        long timeoutMillis = configuredAckTimeoutMillis();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    emitProtocolError(phase + "_ack_timeout");
                    return false;
                }
                String line = ACK_LINES.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (line == null) {
                    emitProtocolError(phase + "_ack_timeout");
                    return false;
                }
                if (expectedAck.equals(line)) {
                    return true;
                }
                if (INPUT_EOF.equals(line)) {
                    ACK_LINES.offer(INPUT_EOF);
                    emitProtocolError(phase + "_ack_input_eof");
                    return false;
                }
                // A late acknowledgement for an earlier timed-out boundary
                // must not satisfy the current boundary.
                emitProtocolError(phase + "_unexpected_ack");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitProtocolError(phase + "_ack_interrupted");
            return false;
        }
    }

    private static synchronized void ensureAckReaderStarted() {
        if (ackReaderStarted) {
            return;
        }
        ackReaderStarted = true;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        System.in,
                        StandardCharsets.US_ASCII));
                try {
                    String line;
                    while ((line = input.readLine()) != null) {
                        ACK_LINES.offer(line);
                    }
                } catch (IOException e) {
                    emitProtocolError("ack_reader_io_error");
                } finally {
                    ACK_LINES.offer(INPUT_EOF);
                }
            }
        }, "MTSA-memory-protocol-ack-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static long configuredAckTimeoutMillis() {
        String raw = System.getProperty(ACK_TIMEOUT_MILLIS_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_ACK_TIMEOUT_MILLIS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0L ? value : DEFAULT_ACK_TIMEOUT_MILLIS;
        } catch (NumberFormatException e) {
            return DEFAULT_ACK_TIMEOUT_MILLIS;
        }
    }

    private static void emitProtocolError(String error) {
        System.out.println(PROTOCOL_ERROR_PREFIX + " " + error);
        System.out.flush();
    }
}
