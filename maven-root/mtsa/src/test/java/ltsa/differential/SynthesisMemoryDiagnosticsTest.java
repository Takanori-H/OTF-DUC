package ltsa.differential;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class SynthesisMemoryDiagnosticsTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static final class RetainedProbe {
        final byte[] payload = new byte[17];
    }

    @Test
    public void liveCheckpointBindsHistogramAndRejectsOverwrite() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("diagnostics");
        SynthesisMemoryDiagnostics diagnostics = new SynthesisMemoryDiagnostics(directory);
        RetainedProbe[] retained = new RetainedProbe[7];
        for (int i = 0; i < retained.length; i++) retained[i] = new RetainedProbe();
        Map<String, Object> evidence = diagnostics.checkpoint("after_initial_controller");
        Reference.reachabilityFence(retained);
        Path histogramPath = directory.resolve("after_initial_controller.txt");
        byte[] bytes = Files.readAllBytes(histogramPath);
        String histogram = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(histogramPath.toAbsolutePath().toString(), evidence.get("histogram_file"));
        assertEquals(1, evidence.get("schema_version"));
        assertEquals(true, evidence.get("live_only"));
        assertEquals(true, evidence.get("full_gc_requested"));
        assertEquals("[]", evidence.get("arguments").toString());
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format("%02x", value & 255));
        }
        assertEquals(hash.toString(), evidence.get("histogram_sha256"));
        long[] totals = SynthesisMemoryDiagnostics.parseTotals(histogram);
        assertEquals(totals[0], ((Number) evidence.get("total_instances")).longValue());
        assertEquals(totals[1], ((Number) evidence.get("total_bytes")).longValue());
        Matcher probe = Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+\\d+\\s+"
                + Pattern.quote(RetainedProbe.class.getName()) + "(?:\\s+[^\\r\\n]*)?$").matcher(histogram);
        assertTrue("Fenced live probes must appear in the real VM histogram", probe.find());
        assertTrue(Long.parseLong(probe.group(1)) >= retained.length);
        assertTrue(((Number) evidence.get("duration_ns")).longValue() > 0);
        assertTrue(((Number) evidence.get("checkpoint_duration_ns")).longValue()
                >= ((Number) evidence.get("duration_ns")).longValue());
        for (String phase : new String[]{"heap_before", "heap_after"}) {
            Map<?, ?> heap = (Map<?, ?>) evidence.get(phase);
            assertTrue(((Number) heap.get("used_bytes")).longValue() > 0);
            assertTrue(((Number) heap.get("committed_bytes")).longValue()
                    >= ((Number) heap.get("used_bytes")).longValue());
            assertTrue(((Number) heap.get("max_bytes")).longValue() > 0);
        }
        try {
            diagnostics.checkpoint("after_initial_controller");
            fail("Duplicate checkpoint must not overwrite evidence or request another GC");
        } catch (FileAlreadyExistsException expected) {
            assertArrayEquals(bytes, Files.readAllBytes(histogramPath));
        }
    }

    @Test
    public void unsafeNamesAndExistingDirectoryAreRejected() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("diagnostics");
        SynthesisMemoryDiagnostics diagnostics = new SynthesisMemoryDiagnostics(directory);
        for (String name : new String[]{null, "", "../outside", "a/b", ".", "x.txt", "space name"}) {
            try {
                diagnostics.checkpoint(name);
                fail("Unsafe checkpoint name accepted: " + name);
            } catch (IllegalArgumentException expected) {
                // Rejected before file reservation or the live histogram call.
            }
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(0L, files.count());
        }
        try {
            new SynthesisMemoryDiagnostics(directory);
            fail("Existing output directory accepted");
        } catch (FileAlreadyExistsException expected) {
            assertTrue(Files.isDirectory(directory));
        }
    }

    @Test
    public void invalidHistogramCannotBecomeSuccessfulEvidence() throws Exception {
        for (String histogram : new String[]{"diagnostic command unavailable", "Total 2 32\n",
                "1: 2 32 example.Probe\nTotal 3 32\n",
                "1: 2 32 example.Probe\nTotal 2 32\nTotal 2 32\n"}) {
            try {
                SynthesisMemoryDiagnostics.parseTotals(histogram);
                fail("Malformed histogram accepted");
            } catch (IOException expected) {
                // VM error text, incomplete data and inconsistent totals are not measurements.
            }
        }
    }
}
