package ltsa.updatingControllers.cli;

import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;

/** Detects memory-window protocol lines without altering copied child output. */
final class MemoryWindowMarkerDetector {

    interface Listener {
        void synthesisWindowStarted();

        void synthesisWindowEnded();

        void protocolError(String error);
    }

    private static final int MAX_MARKER_LINE_BYTES = 256;

    private final Listener listener;
    private final StringBuilder line = new StringBuilder();
    private boolean lineOverflow;

    MemoryWindowMarkerDetector(Listener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.listener = listener;
    }

    void accept(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException();
        }
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\n') {
                completeLine();
            } else if (!lineOverflow) {
                if (line.length() >= MAX_MARKER_LINE_BYTES) {
                    lineOverflow = true;
                    line.setLength(0);
                } else {
                    line.append((char) value);
                }
            }
        }
    }

    void endOfInput() {
        if (line.length() > 0 || lineOverflow) {
            completeLine();
        }
    }

    private void completeLine() {
        if (!lineOverflow) {
            int length = line.length();
            if (length > 0 && line.charAt(length - 1) == '\r') {
                line.setLength(length - 1);
            }
            String text = line.toString();
            if (MemoryMeasurementProtocol.SYNTHESIS_WINDOW_START.equals(text)) {
                notifyStart();
            } else if (MemoryMeasurementProtocol.SYNTHESIS_WINDOW_END.equals(text)) {
                notifyEnd();
            } else if (text.startsWith(MemoryMeasurementProtocol.PROTOCOL_ERROR_PREFIX)) {
                String error = text.substring(
                        MemoryMeasurementProtocol.PROTOCOL_ERROR_PREFIX.length()).trim();
                notifyProtocolError(error);
            }
        }
        line.setLength(0);
        lineOverflow = false;
    }

    private void notifyStart() {
        try {
            listener.synthesisWindowStarted();
        } catch (RuntimeException ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        } catch (LinkageError ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        }
    }

    private void notifyEnd() {
        try {
            listener.synthesisWindowEnded();
        } catch (RuntimeException ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        } catch (LinkageError ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        }
    }

    private void notifyProtocolError(String error) {
        try {
            listener.protocolError(error);
        } catch (RuntimeException ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        } catch (LinkageError ignored) {
            // Measurement callbacks must never interrupt child stdout draining.
        }
    }
}
