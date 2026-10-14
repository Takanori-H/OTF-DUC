package MTSSynthesis.controller.differential.nativeintegration;

import org.json.simple.JSONObject;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Request-owned observations only: never stores model objects, walks graphs or requests GC. */
public final class NativeMemoryDiagnostics implements AutoCloseable {
    public static final String PATH_PROPERTY = "mtsa.nativeDifferential.memoryDiagnostics";
    public static final String HISTOGRAM_PROPERTY = "mtsa.nativeDifferential.memoryHistograms";
    private final BufferedWriter events;
    private final PrintWriter failureStack;
    private final Path directory;
    private final boolean histograms;
    private final String mode;
    private final long processId = ProcessHandle.current().pid();
    private final long origin = System.nanoTime();
    private long sequence;
    private long diagnosticNanos;
    private int histogramCount;
    private Token active;
    private String diagnosticOperation;
    private boolean failed;
    private boolean closed;
    private byte[] emergencyReserve = new byte[1024 * 1024];

    public static final class Token {
        private final NativeMemoryDiagnostics owner;
        private final Token parent;
        private final String phase;
        private final long start;
        private final long diagnosticStart;
        private boolean ended;
        private Token(NativeMemoryDiagnostics owner, String phase) {
            this.owner = owner; this.parent = owner.active; this.phase = phase;
            this.start = System.nanoTime(); this.diagnosticStart = owner.diagnosticNanos;
        }
    }

    /** Called only by the explicit native differential request entry. Ordinary modes never call it. */
    public static NativeMemoryDiagnostics openConfigured(String mode) throws IOException {
        String value = System.getProperty(PATH_PROPERTY);
        if (value == null || value.trim().isEmpty()) return null;
        return new NativeMemoryDiagnostics(Paths.get(value), mode,
                Boolean.parseBoolean(System.getProperty(HISTOGRAM_PROPERTY, "false")));
    }

    public NativeMemoryDiagnostics(Path path, String mode, boolean histograms) throws IOException {
        Path absolute = path.toAbsolutePath();
        directory = absolute.getParent();
        Files.createDirectories(directory);
        this.mode = mode; this.histograms = histograms;
        events = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        PrintWriter stack;
        try {
            stack = new PrintWriter(Files.newBufferedWriter(directory.resolve("memory-oom.txt"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW));
        } catch (IOException ex) {
            try { events.close(); } catch (Throwable ignored) { }
            throw ex;
        }
        failureStack = stack;
        try {
            point("request_start", Collections.emptyMap());
        } catch (RuntimeException | Error ex) {
            try { failureStack.close(); } catch (Throwable ignored) { }
            try { events.close(); } catch (Throwable ignored) { }
            throw ex;
        }
    }

    public static Map<String, Long> counts(Object... entries) {
        if ((entries.length & 1) != 0) throw new IllegalArgumentException("Counts require key/value pairs");
        Map<String, Long> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], ((Number) entries[i + 1]).longValue());
        }
        return values;
    }

    public Token begin(String phase, Map<String, Long> counts) {
        Token token = new Token(this, phase);
        active = token;
        emit("begin", phase, counts, Collections.emptyMap());
        if (histograms && (phase.equals("fluent_to_compact") || phase.equals("fluent_recompose"))) {
            histogram(phase);
        }
        return token;
    }

    public void end(Token token, Map<String, Long> counts) {
        if (token.owner != this || token != active || token.ended) {
            throw new IllegalStateException("Unbalanced diagnostic scope");
        }
        if (histograms && token.phase.equals("native_game_build")) histogram(token.phase);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("phase_elapsed_nanos", System.nanoTime() - token.start);
        extra.put("phase_diagnostic_nanos", diagnosticNanos - token.diagnosticStart);
        emit("end", token.phase, counts, extra);
        token.ended = true;
        active = token.parent;
    }

    public void point(String phase, Map<String, Long> counts) {
        emit("point", phase, counts, Collections.emptyMap());
    }

    private void emit(String event, String phase, Map<String, Long> counts, Map<String, Object> extra) {
        long started = System.nanoTime();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schema_version", 1); row.put("event", event); row.put("phase", phase);
        row.put("mode", mode); row.put("sequence", ++sequence);
        row.put("process_id", processId);
        row.put("elapsed_nanos", started - origin);
        row.put("diagnostic_elapsed_nanos", diagnosticNanos);
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        row.put("heap_used_bytes", heap.getUsed()); row.put("heap_committed_bytes", heap.getCommitted());
        row.put("heap_max_bytes", heap.getMax());
        row.put("non_heap_used_bytes", ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        long gcCount = 0, gcMillis = 0;
        boolean countSupported = true, timeSupported = true;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = collector.getCollectionCount(), millis = collector.getCollectionTime();
            countSupported &= count >= 0; timeSupported &= millis >= 0;
            if (count >= 0) gcCount += count;
            if (millis >= 0) gcMillis += millis;
        }
        row.put("gc_collection_count", countSupported ? gcCount : null);
        row.put("gc_collection_millis", timeSupported ? gcMillis : null);
        row.put("counts", counts == null ? Collections.emptyMap() : counts);
        row.putAll(extra);
        try {
            events.write(JSONObject.toJSONString(row)); events.newLine(); events.flush();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot write native memory diagnostics", ex);
        } finally {
            diagnosticNanos += System.nanoTime() - started;
        }
    }

    private void histogram(String phase) {
        emit("histogram_start", phase, Collections.emptyMap(),
                Collections.singletonMap("command", "GC.class_histogram -all"));
        long started = System.nanoTime();
        String filename = String.format("%03d-%s.txt", ++histogramCount, phase);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("command", "GC.class_histogram -all");
        extra.put("scope", "Shallow class bytes including unreachable objects; not live/retained ownership. May stop application threads; no requested full GC.");
        diagnosticOperation = "class_histogram:" + phase;
        try {
            Path output = directory.resolve("memory-histograms");
            Files.createDirectories(output);
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            String result = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram", new Object[]{new String[]{"-all"}}, new String[]{"[Ljava.lang.String;"});
            Files.write(output.resolve(filename), result.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            extra.put("status", "SUCCESS"); extra.put("file", "memory-histograms/" + filename);
        } catch (Exception ex) {
            extra.put("status", "UNAVAILABLE"); extra.put("error", ex.toString());
        } finally {
            long elapsed = System.nanoTime() - started;
            extra.put("histogram_elapsed_nanos", elapsed);
            diagnosticNanos += elapsed;
            // If an Error propagates, retain the operation to identify diagnostic failures.
        }
        emit("histogram", phase, Collections.emptyMap(), extra);
        diagnosticOperation = null;
    }

    /** Best effort with reserved space; preserves the original Error and its last entered phase. */
    public void failure(Throwable error) {
        failed = true;
        emergencyReserve = null;
        String phase = active == null ? "native_request" : active.phase;
        try {
            failureStack.println("phase=" + phase);
            failureStack.println("diagnostic_operation=" + diagnosticOperation);
            error.printStackTrace(failureStack); failureStack.flush();
        } catch (Throwable ignored) { /* Never replace the actual synthesis failure. */ }
        try {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("error_class", error.getClass().getName()); extra.put("message", error.getMessage());
            extra.put("diagnostic_operation", diagnosticOperation);
            emit("failure", phase, Collections.emptyMap(), extra);
        } catch (Throwable ignored) { /* Earlier flushed checkpoints remain valid. */ }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        emergencyReserve = null;
        try {
            emit("request_end", active == null ? "native_request" : active.phase, Collections.emptyMap(),
                    Collections.singletonMap("status", failed ? "FAILED" : "SUCCESS"));
        } catch (Throwable ignored) { /* Close is also used while unwinding an OOM. */ }
        try { failureStack.close(); } catch (Throwable ignored) { }
        try { events.close(); } catch (Throwable ignored) { }
    }
}
