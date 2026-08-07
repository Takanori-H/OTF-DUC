package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;

public class MemoryWindowHandshakeCoordinatorTest {

    @Test
    public void acknowledgesOnlyAfterBoundarySamples() {
        ByteArrayOutputStream childInput = new ByteArrayOutputStream();
        MemoryWindowHandshakeCoordinator coordinator =
                new MemoryWindowHandshakeCoordinator(childInput);
        AtomicLong rss = new AtomicLong(100L);
        ProcessRssSampler sampler = new ProcessRssSampler(
                1234L,
                60_000L,
                "fake",
                rss::get);
        sampler.start();

        coordinator.synthesisWindowStarted();
        assertEquals("", ascii(childInput));

        coordinator.samplerReady(sampler);
        assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START_ACK + "\n",
                ascii(childInput));
        rss.set(200L);
        coordinator.synthesisWindowEnded();

        ProcessRssSampler.Result rssResult = sampler.stop();
        MemoryWindowHandshakeCoordinator.Result result = coordinator.snapshot();
        assertEquals(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START_ACK + "\n"
                        + MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END_ACK + "\n",
                ascii(childInput));
        assertTrue(result.isComplete());
        assertTrue(result.isStartBoundarySampleSucceeded());
        assertTrue(result.isEndBoundarySampleSucceeded());
        assertTrue(rssResult.isSynthesisWindowCompleted());
        assertEquals(2L, rssResult.getSynthesisWindowSampleCount());
        assertEquals(100L, rssResult.getSynthesisWindowStartRssBytes());
        assertEquals(200L, rssResult.getSynthesisWindowPeakRssBytes());
        assertEquals(100L, rssResult.getSynthesisWindowPeakIncreaseRssBytes());
        coordinator.close();
    }

    @Test
    public void unavailableProviderStillAcknowledgesBothBoundaries() {
        ByteArrayOutputStream childInput = new ByteArrayOutputStream();
        MemoryWindowHandshakeCoordinator coordinator =
                new MemoryWindowHandshakeCoordinator(childInput);

        coordinator.synthesisWindowStarted();
        coordinator.samplerReady(null);
        coordinator.synthesisWindowEnded();

        MemoryWindowHandshakeCoordinator.Result result = coordinator.snapshot();
        assertTrue(result.isComplete());
        assertFalse(result.isStartBoundarySampleSucceeded());
        assertFalse(result.isEndBoundarySampleSucceeded());
        assertEquals(1, result.getStartMarkerCount());
        assertEquals(1, result.getEndMarkerCount());
        coordinator.close();
    }

    @Test
    public void duplicateAndOutOfOrderMarkersAreAcknowledgedButInvalid() {
        ByteArrayOutputStream childInput = new ByteArrayOutputStream();
        MemoryWindowHandshakeCoordinator coordinator =
                new MemoryWindowHandshakeCoordinator(childInput);
        coordinator.samplerReady(null);

        coordinator.synthesisWindowEnded();
        coordinator.synthesisWindowStarted();
        coordinator.synthesisWindowStarted();
        coordinator.synthesisWindowEnded();

        MemoryWindowHandshakeCoordinator.Result result = coordinator.snapshot();
        assertFalse(result.isComplete());
        assertTrue(result.getProtocolError().contains("end_before_start_marker"));
        assertTrue(result.getProtocolError().contains("duplicate_start_marker"));
        assertTrue(result.getProtocolError().contains("duplicate_end_marker"));
        coordinator.close();
    }

    @Test
    public void acknowledgementPipeFailureIsAQualityError() {
        MemoryWindowHandshakeCoordinator coordinator =
                new MemoryWindowHandshakeCoordinator(new FailingOutputStream());
        coordinator.synthesisWindowStarted();
        coordinator.samplerReady(null);

        MemoryWindowHandshakeCoordinator.Result result = coordinator.snapshot();
        assertFalse(result.isStartAckSent());
        assertFalse(result.isComplete());
        assertTrue(result.getProtocolError().contains("ack_write_failed"));
        coordinator.close();
    }

    private static String ascii(ByteArrayOutputStream output) {
        return new String(output.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static final class FailingOutputStream extends OutputStream {
        @Override
        public void write(int value) throws IOException {
            throw new IOException("expected failure");
        }
    }
}
