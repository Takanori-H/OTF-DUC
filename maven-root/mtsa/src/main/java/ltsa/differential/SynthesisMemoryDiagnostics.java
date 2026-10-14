package ltsa.differential;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in live-heap diagnostics for separate, non-performance synthesis runs.
 *
 * <p>The HotSpot DiagnosticCommand {@code gcClassHistogram} operation is invoked
 * with an empty argument array: {@code -all} is deliberately absent, so the VM
 * requests a full GC before enumerating live instances. This changes execution
 * and GC behaviour. Neither synthesis times nor the diagnostic duration from
 * such a run are ordinary performance samples.</p>
 *
 * <p>The histogram reports shallow bytes grouped by runtime class across the
 * entire JVM, not retained ownership or memory attributable to one synthesis
 * structure. The caller must keep the objects intended for a checkpoint live,
 * for example with a reachability fence after this method returns.</p>
 */
public final class SynthesisMemoryDiagnostics {
    private static final String BEAN_NAME = "com.sun.management:type=DiagnosticCommand";
    private static final String OPERATION = "gcClassHistogram";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,79}");
    private static final Pattern TOTAL = Pattern.compile("(?m)^\\s*Total\\s+(\\d+)\\s+(\\d+)\\s*$");
    private static final Pattern ROW = Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+[^\\r\\n]+$");

    private final Path outputDirectory;
    private final MBeanServer server;
    private final ObjectName diagnosticCommand;
    private final MemoryMXBean memory;

    /**
     * Creates a fresh directory; its parent must exist. Existing directories,
     * files and symlinks are rejected rather than reusing prior evidence.
     */
    public SynthesisMemoryDiagnostics(Path freshOutputDir) throws IOException {
        outputDirectory = Objects.requireNonNull(freshOutputDir, "freshOutputDir").toAbsolutePath().normalize();
        Files.createDirectory(outputDirectory);
        server = ManagementFactory.getPlatformMBeanServer();
        memory = ManagementFactory.getMemoryMXBean();
        try {
            diagnosticCommand = new ObjectName(BEAN_NAME);
        } catch (JMException failure) {
            throw new IOException("Cannot address the local DiagnosticCommand MBean", failure);
        }
        if (!server.isRegistered(diagnosticCommand)) {
            throw new IOException("This JVM does not expose the local DiagnosticCommand MBean");
        }
    }

    /**
     * Saves one live histogram and returns its evidence metadata.
     *
     * <p>{@code duration_ns} measures the MBean call only.
     * {@code checkpoint_duration_ns} includes reservation, heap readings,
     * histogram parsing, hashing and file output. Heap readings are immediately
     * before and after the MBean call; the latter can include diagnostic
     * allocations and is not expected to equal the histogram's byte total.</p>
     *
     * <p>A duplicate filename is rejected before requesting GC. If collection or
     * export fails, its reserved file is left in place as failed diagnostic
     * evidence; no successful metadata is returned and it cannot be overwritten.</p>
     */
    public synchronized Map<String, Object> checkpoint(String name) throws IOException {
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Checkpoint name must be 1-80 ASCII letters, digits, underscores or hyphens, starting with a letter or digit");
        }
        long checkpointStarted = System.nanoTime();
        Path histogramPath = outputDirectory.resolve(name + ".txt");
        MemoryUsage before;
        MemoryUsage after;
        long elapsed;
        byte[] bytes;
        long[] totals;
        String hash;
        try (OutputStream output = Files.newOutputStream(histogramPath,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            before = memory.getHeapMemoryUsage();
            long started = System.nanoTime();
            Object value;
            try {
                value = server.invoke(diagnosticCommand, OPERATION,
                        new Object[]{new String[0]}, new String[]{String[].class.getName()});
            } catch (JMException | SecurityException failure) {
                throw new IOException("Cannot obtain live heap histogram for checkpoint " + name, failure);
            }
            elapsed = System.nanoTime() - started;
            after = memory.getHeapMemoryUsage();
            if (!(value instanceof String)) {
                throw new IOException("DiagnosticCommand returned a non-text histogram");
            }
            String histogram = (String) value;
            totals = parseTotals(histogram);
            bytes = histogram.getBytes(StandardCharsets.UTF_8);
            hash = sha256(bytes);
            output.write(bytes);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1);
        result.put("checkpoint", name);
        result.put("collector", BEAN_NAME + "/" + OPERATION);
        result.put("arguments", Collections.emptyList());
        result.put("live_only", true);
        result.put("full_gc_requested", true);
        result.put("heap_before", heap(before));
        result.put("heap_after", heap(after));
        result.put("duration_ns", elapsed);
        result.put("histogram_file", histogramPath.toString());
        result.put("histogram_sha256", hash);
        result.put("total_instances", totals[0]);
        result.put("total_bytes", totals[1]);
        result.put("measurement_scope", "Whole-JVM live class shallow sizes; not retained ownership. Explicit GC diagnostics invalidate performance timings.");
        result.put("checkpoint_duration_ns", System.nanoTime() - checkpointStarted);
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> heap(MemoryUsage usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("used_bytes", usage.getUsed());
        result.put("committed_bytes", usage.getCommitted());
        result.put("max_bytes", usage.getMax());
        return Collections.unmodifiableMap(result);
    }

    static long[] parseTotals(String histogram) throws IOException {
        Matcher total = TOTAL.matcher(histogram);
        if (!total.find()) {
            throw new IOException("Histogram has no Total row");
        }
        try {
            long instances = Long.parseLong(total.group(1));
            long bytes = Long.parseLong(total.group(2));
            if (total.find()) {
                throw new IOException("Histogram has multiple Total rows");
            }
            long rowInstances = 0;
            long rowBytes = 0;
            Matcher row = ROW.matcher(histogram);
            while (row.find()) {
                rowInstances = Math.addExact(rowInstances, Long.parseLong(row.group(1)));
                rowBytes = Math.addExact(rowBytes, Long.parseLong(row.group(2)));
            }
            if (instances <= 0 || bytes <= 0 || instances != rowInstances || bytes != rowBytes) {
                throw new IOException("Histogram class rows do not match its positive Total row");
            }
            return new long[]{instances, bytes};
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IOException("Histogram counters are outside the supported range", failure);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
    }
}
