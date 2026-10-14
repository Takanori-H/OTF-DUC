package ltsa.differential;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** CLI profiling must preserve guarantees, outputs and explicit option isolation. */
public class EnvironmentGenerationProfileTest {
    private static final String MODEL = "ENV_COMPONENT=Q0,\n"
            + "Q0=(pick->Q1|done->Q0),\nQ1=(done->Q0).\n||LOAD_ENV=(ENV_COMPONENT).\n"
            + "MON_000=(pick->M1|done->MON_000),\nM1=(done->MON_000|pick->ERROR).\n";

    @Test
    public void bothSeedSelectionsAndFullExtractionKeepTheSameVerifiedController() throws Exception {
        Map<String, Object> indexed = run(MODEL, "DIFFERENTIAL", "--profile-generation", "--verify");
        Map<String, Object> scanned = run(MODEL, "DIFFERENTIAL", "--verify", "--profile-generation",
                "--generation-seeds=all-added-transitions");
        Map<String, Object> full = run(MODEL, "ANALYSIS_REUSE", "--profile-generation", "--verify");
        Map<String, Object> fresh = run(MODEL, "FULL", "--profile-generation", "--verify");
        for (Map<String, Object> report : Arrays.asList(indexed, scanned, full, fresh)) {
            assertEquals("SUCCESS", report.get("status"));
            assertTrue(report.get("verification").toString().startsWith("PASS"));
            assertEquals(((Map<?, ?>) indexed.get("controller")).get("rooted_sha256"),
                    ((Map<?, ?>) report.get("controller")).get("rooted_sha256"));
            assertTrue(((Number) report.get("added_game_states")).longValue() > 0);
            assertTimings(report);
        }
        assertEquals("CHANGED_OLD_SOURCES", indexed.get("generation_seed_selection"));
        assertEquals("ALL_ADDED_TRANSITIONS", scanned.get("generation_seed_selection"));
        assertNull(full.get("generation_seed_selection"));
        assertEquals(0L, counter(indexed, "added_edges_examined"));
        assertEquals(((Number) indexed.get("changed_old_sources")).longValue(),
                counter(indexed, "changed_old_sources_examined"));
        assertTrue(counter(scanned, "added_edges_examined") > 0);
        assertEquals(0L, counter(scanned, "changed_old_sources_examined"));
        assertEquals(counter(indexed, "expanded_states"), counter(scanned, "expanded_states"));
        assertEquals(counter(indexed, "game_edges_examined"), counter(scanned, "game_edges_examined"));
    }

    @Test
    public void profilingAloneDoesNotEnableIndependentVerificationAndDoesNotLeak() throws Exception {
        Map<String, Object> profiled = run(MODEL, "DIFFERENTIAL", "--profile-generation",
                "--generation-seeds=all-added-transitions");
        assertEquals(false, profiled.get("verification_requested"));
        assertFalse(profiled.containsKey("verification"));
        assertNotNull(profiled.get("generation_profile"));
        Map<String, Object> plain = run(MODEL, "DIFFERENTIAL");
        assertEquals(false, plain.get("verification_requested"));
        assertEquals(false, plain.get("generation_profile_requested"));
        assertNull(plain.get("generation_profile"));
        assertEquals("CHANGED_OLD_SOURCES", plain.get("generation_seed_selection"));
        assertEquals(((Map<?, ?>) profiled.get("controller")).get("rooted_sha256"),
                ((Map<?, ?>) plain.get("controller")).get("rooted_sha256"));
    }

    @Test
    public void rejectedUpdateHasNoGenerationProfile() throws Exception {
        String unsafe = MODEL.replace("pick->ERROR)", "pick->ERROR|fault->ERROR)");
        for (String method : Arrays.asList("FULL", "ANALYSIS_REUSE", "DIFFERENTIAL")) {
            Map<String, Object> report = run(unsafe, method, "--profile-generation", "--verify");
            assertEquals("PAPER_WINNING_REGION_INCOMPATIBLE", report.get("status"));
            assertEquals(false, report.get("generation_executed"));
            assertNull(report.get("generation_profile"));
            assertNull(((Map<?, ?>) report.get("timing_ns")).get("generation"));
        }
    }

    @Test
    public void malformedOrInapplicableOptionsFailBeforeReadingInput() throws Exception {
        for (String[] options : new String[][] {
                {"FULL", "--generation-seeds=changed-old-sources"},
                {"ANALYSIS_REUSE", "--generation-seeds=all-added-transitions"},
                {"DIFFERENTIAL", "--generation-seeds=unknown"},
                {"DIFFERENTIAL", "--profile-generation", "--profile-generation"},
                {"DIFFERENTIAL", "--verify", "--verify"},
                {"DIFFERENTIAL", "--generation-seeds=changed-old-sources", "--generation-seeds=all-added-transitions"},
                {"DIFFERENTIAL", "--unknown"}}) {
            Path output = Files.createTempDirectory("invalid-generation-profile-").resolve("result.json");
            List<String> args = new ArrayList<>(Arrays.asList("missing.lts", "1", "missing.controls",
                    "missing.delta", options[0], output.toString()));
            args.addAll(Arrays.asList(options).subList(1, options.length));
            try {
                EnvironmentDifferentialSynthesisRunner.main(args.toArray(new String[0]));
                fail("Expected option rejection: " + Arrays.toString(options));
            } catch (IllegalArgumentException expected) {
                assertFalse(Files.exists(output));
            }
        }
    }

    private static long counter(Map<String, Object> report, String name) {
        Map<?, ?> profile = (Map<?, ?>) report.get("generation_profile");
        return ((Number) ((Map<?, ?>) profile.get("counters")).get(name)).longValue();
    }

    private static void assertTimings(Map<String, Object> report) {
        Map<?, ?> profile = (Map<?, ?>) report.get("generation_profile");
        Map<?, ?> phases = (Map<?, ?>) profile.get("timing_ns");
        long sum = 0;
        for (String phase : Arrays.asList("seed_selection", "row_setup", "row_expansion",
                "controller_snapshot", "patch_construction")) {
            long elapsed = ((Number) phases.get(phase)).longValue();
            assertTrue(phase, elapsed >= 0);
            sum += elapsed;
        }
        long total = ((Number) profile.get("total_ns")).longValue();
        long other = ((Number) profile.get("unattributed_ns")).longValue();
        assertEquals(total, sum + other);
        assertTrue(other >= 0);
        assertTrue(((Number) ((Map<?, ?>) report.get("timing_ns")).get("generation")).longValue() >= total);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(String model, String method, String... options) throws Exception {
        Path root = Files.createTempDirectory("environment-generation-profile-");
        Path source = root.resolve("model.lts"), controls = root.resolve("controls.txt"), delta = root.resolve("change.tsv");
        Files.writeString(source, model);
        Files.writeString(controls, "pick\n");
        Files.writeString(delta, "Q1\tfault\tQ0\n");
        Path output = root.resolve("result.json");
        List<String> args = new ArrayList<>(Arrays.asList(source.toString(), "1", controls.toString(),
                delta.toString(), method, output.toString()));
        args.addAll(Arrays.asList(options));
        EnvironmentDifferentialSynthesisRunner.main(args.toArray(new String[0]));
        return (Map<String, Object>) new Yaml().load(Files.readString(output));
    }
}
