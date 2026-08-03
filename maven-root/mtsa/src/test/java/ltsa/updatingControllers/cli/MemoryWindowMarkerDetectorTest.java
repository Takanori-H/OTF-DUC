package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;

public class MemoryWindowMarkerDetectorTest {

    @Test
    public void detectsMarkersSplitAcrossArbitraryChunks() {
        CountingListener listener = new CountingListener();
        MemoryWindowMarkerDetector detector = new MemoryWindowMarkerDetector(listener);
        byte[] text = ("ordinary output\n"
                + MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START + "\r\n"
                + "more output\n"
                + MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END + "\n")
                        .getBytes(StandardCharsets.US_ASCII);

        for (int i = 0; i < text.length; i += 3) {
            detector.accept(text, i, Math.min(3, text.length - i));
        }
        detector.endOfInput();

        assertEquals(1, listener.starts);
        assertEquals(1, listener.ends);
    }

    @Test
    public void ignoresMarkerTextEmbeddedInOrdinaryOrOversizedLines() {
        CountingListener listener = new CountingListener();
        MemoryWindowMarkerDetector detector = new MemoryWindowMarkerDetector(listener);
        StringBuilder oversized = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            oversized.append('x');
        }
        String text = "prefix " + MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START + "\n"
                + oversized + MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END + "\n";
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);

        detector.accept(bytes, 0, bytes.length);
        detector.endOfInput();

        assertEquals(0, listener.starts);
        assertEquals(0, listener.ends);
    }

    @Test
    public void detectsFinalMarkerWithoutTrailingNewline() {
        CountingListener listener = new CountingListener();
        MemoryWindowMarkerDetector detector = new MemoryWindowMarkerDetector(listener);
        byte[] bytes = MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END
                .getBytes(StandardCharsets.US_ASCII);

        detector.accept(bytes, 0, bytes.length);
        detector.endOfInput();

        assertEquals(0, listener.starts);
        assertEquals(1, listener.ends);
    }

    @Test
    public void reportsProtocolErrorMarker() {
        CountingListener listener = new CountingListener();
        MemoryWindowMarkerDetector detector = new MemoryWindowMarkerDetector(listener);
        byte[] bytes = (MemoryMeasurementProtocol.PROTOCOL_ERROR_PREFIX
                + " start_ack_timeout\n").getBytes(StandardCharsets.US_ASCII);

        detector.accept(bytes, 0, bytes.length);

        assertEquals("start_ack_timeout", listener.protocolError);
    }

    private static final class CountingListener
            implements MemoryWindowMarkerDetector.Listener {
        private int starts;
        private int ends;
        private String protocolError = "";

        @Override
        public void synthesisWindowStarted() {
            starts++;
        }

        @Override
        public void synthesisWindowEnded() {
            ends++;
        }

        @Override
        public void protocolError(String error) {
            protocolError = error;
        }
    }
}
