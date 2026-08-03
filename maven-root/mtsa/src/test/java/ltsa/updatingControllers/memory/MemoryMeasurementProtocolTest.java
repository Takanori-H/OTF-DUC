package ltsa.updatingControllers.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public class MemoryMeasurementProtocolTest {

    @Test
    public void childCannotCrossEitherBoundaryBeforeMatchingAck() throws Exception {
        Process child = startChild(5_000L);
        BufferedReader output = reader(child);
        BufferedWriter input = writer(child);
        try {
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START,
                    readLineWithin(output, 2_000L));
            Thread.sleep(100L);
            assertFalse("child entered synthesis before START ACK", output.ready());

            writeLine(input, MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START_ACK);
            assertEquals("BODY", readLineWithin(output, 2_000L));
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END,
                    readLineWithin(output, 2_000L));
            Thread.sleep(100L);
            assertFalse("child entered post-processing before END ACK", output.ready());

            writeLine(input, MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END_ACK);
            assertEquals("POST", readLineWithin(output, 2_000L));
            assertTrue(child.waitFor(2L, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    public void missingAckTimesOutInsteadOfBlockingSynthesisForever() throws Exception {
        Process child = startChild(250L);
        BufferedReader output = reader(child);
        try {
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START,
                    readLineWithin(output, 2_000L));
            assertTrue(readLineWithin(output, 2_000L).contains("start_ack_timeout"));
            assertEquals("BODY", readLineWithin(output, 2_000L));
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END,
                    readLineWithin(output, 2_000L));
            assertTrue(readLineWithin(output, 2_000L).contains("end_ack_timeout"));
            assertEquals("POST", readLineWithin(output, 2_000L));
            assertTrue(child.waitFor(2L, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    public void closedInputMakesBothBoundariesFailFast() throws Exception {
        Process child = startChild(5_000L);
        BufferedReader output = reader(child);
        try {
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START,
                    readLineWithin(output, 2_000L));
            child.getOutputStream().close();
            assertTrue(readLineWithin(output, 2_000L).contains("start_ack_input_eof"));
            assertEquals("BODY", readLineWithin(output, 2_000L));
            assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END,
                    readLineWithin(output, 2_000L));
            assertTrue(readLineWithin(output, 2_000L).contains("end_ack_input_eof"));
            assertEquals("POST", readLineWithin(output, 2_000L));
            assertTrue(child.waitFor(2L, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
        } finally {
            child.destroyForcibly();
        }
    }

    private static Process startChild(long timeoutMillis) throws Exception {
        String javaHome = System.getProperty("java.home");
        String java = new File(new File(javaHome, "bin"), "java").getPath();
        String classPath = codeSourcePath(ProtocolChild.class)
                + File.pathSeparator
                + codeSourcePath(MemoryMeasurementProtocol.class);
        return new ProcessBuilder(
                java,
                "-cp",
                classPath,
                ProtocolChild.class.getName(),
                Long.toString(timeoutMillis))
                .redirectErrorStream(true)
                .start();
    }

    private static String codeSourcePath(Class<?> type) throws Exception {
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        try {
            return new File(location.toURI()).getPath();
        } catch (URISyntaxException e) {
            // Some legacy Maven setups expose a file URL containing raw
            // spaces. Keep that case working while preserving Windows drive
            // letters when the URL is a valid URI.
            return new File(URLDecoder.decode(
                    location.getPath(), StandardCharsets.UTF_8.name())).getPath();
        }
    }

    private static BufferedReader reader(Process child) {
        return new BufferedReader(new InputStreamReader(
                child.getInputStream(), StandardCharsets.US_ASCII));
    }

    private static BufferedWriter writer(Process child) {
        return new BufferedWriter(new OutputStreamWriter(
                child.getOutputStream(), StandardCharsets.US_ASCII));
    }

    private static void writeLine(BufferedWriter writer, String value) throws Exception {
        writer.write(value);
        writer.newLine();
        writer.flush();
    }

    private static String readLineWithin(BufferedReader reader, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!reader.ready() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("timed out waiting for child output", reader.ready());
        return reader.readLine();
    }

    public static final class ProtocolChild {
        public static void main(String[] args) {
            System.setProperty(MemoryMeasurementProtocol.ENABLED_PROPERTY, "true");
            System.setProperty(
                    MemoryMeasurementProtocol.ACK_TIMEOUT_MILLIS_PROPERTY,
                    args[0]);
            MemoryMeasurementProtocol.emitSynthesisWindowStart();
            System.out.println("BODY");
            System.out.flush();
            MemoryMeasurementProtocol.emitSynthesisWindowEnd();
            System.out.println("POST");
            System.out.flush();
        }
    }
}
