package MTSSynthesis.controller.differential.nativeintegration;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NativeMemoryDiagnosticsTest {
    private static List<JSONObject> records(Path file) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            result.add((JSONObject) new JSONParser().parse(line));
        }
        return result;
    }

    @Test public void nestedScopesFlushCountsAndRestoreParentForFailure() throws Exception {
        Path dir = Files.createTempDirectory("native-memory-diagnostics");
        Path file = dir.resolve("memory-diagnostics.jsonl");
        NativeMemoryDiagnostics diagnostics = new NativeMemoryDiagnostics(file, "prepare", false);
        NativeMemoryDiagnostics.Token outer = diagnostics.begin("outer", NativeMemoryDiagnostics.counts("states", 7));
        NativeMemoryDiagnostics.Token inner = diagnostics.begin("inner", Collections.emptyMap());
        diagnostics.end(inner, NativeMemoryDiagnostics.counts("states", 11));
        assertEquals(4, records(file).size()); // Usable while the request is still running.
        OutOfMemoryError failure = new OutOfMemoryError("synthetic diagnostic test");
        diagnostics.failure(failure);
        diagnostics.close();
        List<JSONObject> rows = records(file);
        JSONObject failed = rows.get(rows.size() - 2);
        assertEquals("failure", failed.get("event"));
        assertEquals("outer", failed.get("phase"));
        assertEquals("java.lang.OutOfMemoryError", failed.get("error_class"));
        assertEquals("FAILED", rows.get(rows.size() - 1).get("status"));
        assertTrue(Files.readString(dir.resolve("memory-oom.txt")).contains("synthetic diagnostic test"));
        for (int i = 0; i < rows.size(); i++) {
            assertEquals((long) i + 1, rows.get(i).get("sequence"));
            assertTrue((Long) rows.get(i).get("heap_used_bytes") >= 0);
        }
        assertFalse(Files.exists(dir.resolve("memory-histograms")));
    }

    @Test public void successfulCloseAndScopeOwnershipAreChecked() throws Exception {
        Path file = Files.createTempDirectory("native-memory-scope").resolve("events.jsonl");
        try (NativeMemoryDiagnostics diagnostics = new NativeMemoryDiagnostics(file, "full", false)) {
            NativeMemoryDiagnostics.Token parent = diagnostics.begin("parent", Collections.emptyMap());
            NativeMemoryDiagnostics.Token child = diagnostics.begin("child", Collections.emptyMap());
            try {
                diagnostics.end(parent, Collections.emptyMap()); fail("Unbalanced scope accepted");
            } catch (IllegalStateException expected) { }
            diagnostics.end(child, Collections.emptyMap());
            diagnostics.end(parent, Collections.emptyMap());
        }
        List<JSONObject> rows = records(file);
        assertEquals("SUCCESS", rows.get(rows.size() - 1).get("status"));
        assertEquals("native_request", rows.get(rows.size() - 1).get("phase"));
    }

    @Test public void selectedHistogramWritesShallowClassEvidence() throws Exception {
        Path dir = Files.createTempDirectory("native-memory-histogram");
        Path file = dir.resolve("events.jsonl");
        try (NativeMemoryDiagnostics diagnostics = new NativeMemoryDiagnostics(file, "prepare", true)) {
            NativeMemoryDiagnostics.Token phase = diagnostics.begin("fluent_to_compact", Collections.emptyMap());
            diagnostics.end(phase, Collections.emptyMap());
        }
        JSONObject histogram = records(file).stream().filter(r -> "histogram".equals(r.get("event"))).findFirst().get();
        assertEquals("SUCCESS", histogram.get("status"));
        assertEquals("GC.class_histogram -all", histogram.get("command"));
        String content = Files.readString(dir.resolve((String) histogram.get("file")));
        assertTrue(content.contains("#instances"));
        assertTrue(content.contains("#bytes"));
        assertTrue(content.contains("java.lang.String"));
        assertTrue((Long) histogram.get("histogram_elapsed_nanos") >= 0);
    }
}
