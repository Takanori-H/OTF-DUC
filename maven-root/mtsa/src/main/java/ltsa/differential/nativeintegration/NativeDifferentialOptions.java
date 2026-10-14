package ltsa.differential.nativeintegration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Immutable, target-local settings. Constructing these settings performs no file I/O. */
public final class NativeDifferentialOptions {
    public enum Mode { PREPARE, FULL, ANALYSIS_REUSE, DIFFERENTIAL }

    private final Mode mode;
    private final String snapshotPath;
    private final String deltaPath;
    private final String outputPath;
    private final String sourceDirectory;

    public NativeDifferentialOptions(Mode mode, String snapshot, String delta,
                                     String output, String sourceDirectory) {
        if (mode == null) {
            throw new IllegalArgumentException("differential mode is required");
        }
        this.mode = mode;
        Path base = Paths.get(sourceDirectory == null || sourceDirectory.isEmpty()
                ? "." : sourceDirectory).toAbsolutePath().normalize();
        this.sourceDirectory = base.toString();
        this.snapshotPath = resolveRequired(base, snapshot, "snapshot");
        if (mode == Mode.PREPARE) {
            if (delta != null || output != null) {
                throw new IllegalArgumentException(
                        "differential prepare accepts snapshot only; delta/output are update options");
            }
            this.deltaPath = null;
            this.outputPath = null;
        } else {
            this.deltaPath = resolveRequired(base, delta, "delta");
            this.outputPath = output == null ? null : resolveRequired(base, output, "output");
        }
    }

    private static String resolveRequired(Path base, String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("differential " + key + " is required");
        }
        return base.resolve(Paths.get(value)).normalize().toString();
    }

    public static Mode parseMode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("differential mode is required");
        }
        try {
            return Mode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown differential mode: " + value
                    + "; expected prepare, full, analysis_reuse, or differential");
        }
    }

    public Mode getMode() { return mode; }
    public String getSnapshotPath() { return snapshotPath; }
    public String getDeltaPath() { return deltaPath; }
    /** Optional destination of the next snapshot; not the controller output file. */
    public String getOutputPath() { return outputPath; }
    public String getSourceDirectory() { return sourceDirectory; }
    public boolean isPrepare() { return mode == Mode.PREPARE; }
    public boolean isUpdate() { return mode != Mode.PREPARE; }
}
