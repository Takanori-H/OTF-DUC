package ltsa.updatingControllers.cli;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;

/**
 * Coordinates the child stdout boundary markers with parent-side RSS samples.
 *
 * <p>The child cannot enter the measured synthesis body until the start ACK is
 * flushed, and cannot enter post-processing until the end ACK is flushed.
 * Provider initialization failure therefore disables the samples but never
 * deadlocks the child.</p>
 */
final class MemoryWindowHandshakeCoordinator
        implements MemoryWindowMarkerDetector.Listener, AutoCloseable {

    private final Object lock = new Object();
    private final OutputStream childInput;

    private ProcessRssSampler sampler;
    private boolean samplerReady;
    private int startMarkerCount;
    private int endMarkerCount;
    private boolean startAckSent;
    private boolean endAckSent;
    private boolean startBoundarySampleSucceeded;
    private boolean endBoundarySampleSucceeded;
    private long startMarkerAtNanos = -1L;
    private long endMarkerAtNanos = -1L;
    private long startAckWaitNanos = -1L;
    private long endAckWaitNanos = -1L;
    private String protocolError = "";
    private boolean closed;

    MemoryWindowHandshakeCoordinator(OutputStream childInput) {
        if (childInput == null) {
            throw new NullPointerException("childInput");
        }
        this.childInput = childInput;
    }

    /**
     * Makes the RSS provider (or its failure, represented by {@code null})
     * visible to marker callbacks. This method always unblocks a pending start
     * marker by sending an ACK.
     */
    void samplerReady(ProcessRssSampler value) {
        synchronized (lock) {
            if (samplerReady) {
                return;
            }
            sampler = value;
            samplerReady = true;
            completeStartIfPossibleLocked();
            completeEndIfPossibleLocked();
        }
    }

    @Override
    public void synthesisWindowStarted() {
        synchronized (lock) {
            startMarkerCount++;
            if (startMarkerAtNanos < 0L) {
                startMarkerAtNanos = System.nanoTime();
            } else {
                appendProtocolErrorLocked("duplicate_start_marker");
            }
            if (startAckSent) {
                writeAckLocked(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START_ACK);
                return;
            }
            completeStartIfPossibleLocked();
        }
    }

    @Override
    public void synthesisWindowEnded() {
        synchronized (lock) {
            endMarkerCount++;
            if (endMarkerAtNanos < 0L) {
                endMarkerAtNanos = System.nanoTime();
            } else {
                appendProtocolErrorLocked("duplicate_end_marker");
            }
            if (startMarkerCount == 0) {
                appendProtocolErrorLocked("end_before_start_marker");
            }
            if (endAckSent) {
                writeAckLocked(MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END_ACK);
                return;
            }
            completeEndIfPossibleLocked();
        }
    }

    @Override
    public void protocolError(String error) {
        synchronized (lock) {
            appendProtocolErrorLocked(error == null ? "child_protocol_error" : error);
        }
    }

    Result snapshot() {
        synchronized (lock) {
            return new Result(
                    startMarkerCount,
                    endMarkerCount,
                    startAckSent,
                    endAckSent,
                    startBoundarySampleSucceeded,
                    endBoundarySampleSucceeded,
                    millis(startAckWaitNanos),
                    millis(endAckWaitNanos),
                    protocolError);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                childInput.close();
            } catch (IOException e) {
                appendProtocolErrorLocked("child_stdin_close_failed:" + e);
            }
        }
    }

    private void completeStartIfPossibleLocked() {
        if (!samplerReady || startMarkerCount == 0 || startAckSent) {
            return;
        }
        if (sampler != null) {
            try {
                sampler.beginSynthesisWindow();
                startBoundarySampleSucceeded = sampler.snapshot()
                        .isSynthesisWindowStartBoundarySampleSucceeded();
            } catch (RuntimeException e) {
                appendProtocolErrorLocked("start_boundary_sample_failed:" + e);
            } catch (LinkageError e) {
                appendProtocolErrorLocked("start_boundary_sample_failed:" + e);
            }
        }
        startAckSent = writeAckLocked(
                MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START_ACK);
        if (startMarkerAtNanos >= 0L) {
            startAckWaitNanos = System.nanoTime() - startMarkerAtNanos;
        }
    }

    private void completeEndIfPossibleLocked() {
        if (!samplerReady || endMarkerCount == 0 || endAckSent) {
            return;
        }
        if (sampler != null) {
            try {
                sampler.endSynthesisWindow();
                endBoundarySampleSucceeded = sampler.snapshot()
                        .isSynthesisWindowEndBoundarySampleSucceeded();
            } catch (RuntimeException e) {
                appendProtocolErrorLocked("end_boundary_sample_failed:" + e);
            } catch (LinkageError e) {
                appendProtocolErrorLocked("end_boundary_sample_failed:" + e);
            }
        }
        endAckSent = writeAckLocked(
                MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END_ACK);
        if (endMarkerAtNanos >= 0L) {
            endAckWaitNanos = System.nanoTime() - endMarkerAtNanos;
        }
    }

    private boolean writeAckLocked(String acknowledgement) {
        if (closed) {
            appendProtocolErrorLocked("ack_after_child_stdin_closed");
            return false;
        }
        try {
            childInput.write((acknowledgement + "\n").getBytes(StandardCharsets.US_ASCII));
            childInput.flush();
            return true;
        } catch (IOException e) {
            appendProtocolErrorLocked("ack_write_failed:" + e);
            try {
                childInput.close();
            } catch (IOException closeError) {
                appendProtocolErrorLocked("ack_pipe_close_failed:" + closeError);
            }
            closed = true;
            return false;
        }
    }

    private void appendProtocolErrorLocked(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (protocolError.isEmpty()) {
            protocolError = value;
        } else {
            protocolError += "; " + value;
        }
    }

    private static long millis(long nanos) {
        return nanos < 0L ? -1L : TimeUnit.NANOSECONDS.toMillis(nanos);
    }

    static final class Result {
        private final int startMarkerCount;
        private final int endMarkerCount;
        private final boolean startAckSent;
        private final boolean endAckSent;
        private final boolean startBoundarySampleSucceeded;
        private final boolean endBoundarySampleSucceeded;
        private final long startAckWaitMillis;
        private final long endAckWaitMillis;
        private final String protocolError;

        private Result(
                int startMarkerCount,
                int endMarkerCount,
                boolean startAckSent,
                boolean endAckSent,
                boolean startBoundarySampleSucceeded,
                boolean endBoundarySampleSucceeded,
                long startAckWaitMillis,
                long endAckWaitMillis,
                String protocolError) {
            this.startMarkerCount = startMarkerCount;
            this.endMarkerCount = endMarkerCount;
            this.startAckSent = startAckSent;
            this.endAckSent = endAckSent;
            this.startBoundarySampleSucceeded = startBoundarySampleSucceeded;
            this.endBoundarySampleSucceeded = endBoundarySampleSucceeded;
            this.startAckWaitMillis = startAckWaitMillis;
            this.endAckWaitMillis = endAckWaitMillis;
            this.protocolError = protocolError;
        }

        int getStartMarkerCount() {
            return startMarkerCount;
        }

        int getEndMarkerCount() {
            return endMarkerCount;
        }

        boolean isStartAckSent() {
            return startAckSent;
        }

        boolean isEndAckSent() {
            return endAckSent;
        }

        boolean isStartBoundarySampleSucceeded() {
            return startBoundarySampleSucceeded;
        }

        boolean isEndBoundarySampleSucceeded() {
            return endBoundarySampleSucceeded;
        }

        long getStartAckWaitMillis() {
            return startAckWaitMillis;
        }

        long getEndAckWaitMillis() {
            return endAckWaitMillis;
        }

        String getProtocolError() {
            return protocolError;
        }

        boolean isComplete() {
            return startMarkerCount == 1
                    && endMarkerCount == 1
                    && startAckSent
                    && endAckSent
                    && protocolError.isEmpty();
        }
    }
}
