package ltsa.differential;

import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.*;

public class EnvironmentDifferentialSynthesisRunnerTest {
    private static final String MODEL = "ENV_COMPONENT=Q0,\n"
            + "Q0=(pick->Q1|done->Q0),\nQ1=(done->Q0).\n||LOAD_ENV=(ENV_COMPONENT).\n"
            + "MON_000=(pick->M1|done->MON_000),\nM1=(done->MON_000|pick->ERROR).\n";

    @Test
    public void realEnvironmentAdditionMatchesFullAndIndependentOracle() throws Exception {
        String hash = null;
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(MODEL, "pick\n", "Q1\tfault\tQ0\n", method);
            assertEquals("SUCCESS", report.get("status"));
            assertAnalysisBackend(report, method, false);
            assertTrue(((Number) report.get("added_game_states")).intValue() > 0);
            assertEquals(0, ((Number) report.get("lost_old_winning_states")).intValue());
            assertTrue(report.get("verification").toString().startsWith("PASS"));
            Map<?, ?> controller = (Map<?, ?>) report.get("controller");
            if (hash == null) hash = controller.get("rooted_sha256").toString();
            assertEquals(hash, controller.get("rooted_sha256"));
            Map<?, ?> timings = (Map<?, ?>) report.get("timing_ns");
            long sum = 0;
            for (String phase : Arrays.asList("environment_update", "game_expansion", "game_index",
                    "analysis", "compatibility", "generation")) {
                long elapsed = ((Number) timings.get(phase)).longValue();
                assertTrue(phase, elapsed >= 0); sum += elapsed;
            }
            assertTrue(((Number) timings.get("update_total")).longValue() >= sum);
        }
    }

    @Test
    public void multipleEnvironmentAdditionsInOneTsvMatchAllMethods() throws Exception {
        String hash = null;
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(MODEL, "pick\n",
                    "Q1\tfault\tQ0\nQ0\tbypass\tQ1\n", method);
            assertEquals("SUCCESS", report.get("status"));
            assertAnalysisBackend(report, method, false);
            assertEquals(2, ((Number) report.get("requested_environment_edges")).intValue());
            assertTrue(((Number) report.get("added_game_states")).intValue() > 0);
            assertTrue(report.get("verification").toString().startsWith("PASS"));
            Map<?, ?> controller = (Map<?, ?>) report.get("controller");
            if (hash == null) hash = controller.get("rooted_sha256").toString();
            assertEquals(hash, controller.get("rooted_sha256"));
        }
    }

    @Test
    public void paperWinningRegionGateIsIdenticalAcrossMethods() throws Exception {
        String unsafe = MODEL.replace("M1=(done->MON_000|pick->ERROR)",
                "M1=(done->MON_000|pick->ERROR|fault->ERROR)");
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(unsafe, "pick\n", "Q1\tfault\tQ0\n", method);
            assertEquals("PAPER_WINNING_REGION_INCOMPATIBLE", report.get("status"));
            assertAnalysisBackend(report, method, false);
            assertEquals(false, ((Map<?, ?>) report.get("controller")).get("present"));
            assertEquals(false, report.get("generation_executed"));
            assertNull(((Map<?, ?>) report.get("timing_ns")).get("generation"));
        }
    }

    @Test
    public void losingWinningStatesOutsideOldControllerRejectsWithoutSwitchWitness() throws Exception {
        String model = "ENV_COMPONENT=Q0,\n"
                + "Q0=(safe->Q2|enter->Q1),\n"
                + "Q1=(bad->Q2|escape->Q3),\n"
                + "Q2=(back->Q0),\n"
                + "Q3=(advance->Q4),\n"
                + "Q4=(return->Q3).\n||LOAD_ENV=(ENV_COMPONENT).\n"
                + "MON_000=(bad->ERROR|fault->ERROR|safe->MON_000).\n";
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            // Q3/Q4 are winning in the game but reached only through losing Q1.
            // The added edge is not a self-loop and does not affect the old C.
            Map<String, Object> report = run(model, "enter\nescape\n", "Q3\tfault\tQ0\n", method);
            assertEquals("PAPER_WINNING_REGION_INCOMPATIBLE", report.get("status"));
            assertAnalysisBackend(report, method, false);
            assertEquals(2, ((Number) report.get("old_controller_states")).intValue());
            assertEquals(2, ((Number) report.get("lost_old_winning_states")).intValue());
            assertEquals(0, ((Number) report.get("lost_old_controller_states")).intValue());
            assertEquals(0, ((Number) report.get("newly_winning_old_states")).intValue());
            assertEquals(0, ((Number) report.get("switch_state_count")).intValue());
            assertEquals(false, ((Map<?, ?>) report.get("controller")).get("present"));
            assertFalse(report.containsKey("controller_file"));
            assertEquals(false, report.get("generation_executed"));
            assertNull(((Map<?, ?>) report.get("timing_ns")).get("generation"));
            assertTrue(report.get("verification").toString().startsWith("PASS"));
        }
    }

    @Test
    public void diagnosticsExposeOldLosingStateRestorationWithoutChangingOutput() throws Exception {
        String source = "ENV_COMPONENT=Q0,\n"
                + "Q0=(go->Q2|try->Q1),\nQ1=STOP,\nQ2=(back->Q0).\n"
                + "||LOAD_ENV=(ENV_COMPONENT).\nMON_000=(back->MON_000).\n";
        String hash = null;
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(source, "go\ntry\n", "Q1\tescape\tQ2\n", method);
            assertEquals("SUCCESS", report.get("status"));
            assertAnalysisBackend(report, method, true);
            assertEquals(1, ((Number) report.get("newly_winning_old_states")).intValue());
            assertEquals(2, ((Number) report.get("old_controller_states")).intValue());
            Map<?, ?> controller = (Map<?, ?>) report.get("controller");
            assertEquals(3, ((Number) controller.get("states")).intValue());
            if (hash == null) hash = controller.get("rooted_sha256").toString();
            assertEquals(hash, controller.get("rooted_sha256"));
            assertTrue(report.get("verification").toString().startsWith("PASS"));
        }
    }

    @Test
    public void firstUncontrollableEscapeUsesFallbackAndRestoresOldLosingState() throws Exception {
        String source = "ENV_COMPONENT=Q0,\n"
                + "Q0=(go->Q2|try->Q1),\nQ1=(bad->Q2),\nQ2=(back->Q0).\n"
                + "||LOAD_ENV=(ENV_COMPONENT).\nMON_000=(bad->ERROR|back->MON_000).\n";
        String hash = null;
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(source, "go\ntry\nbad\n", "Q1\tescape\tQ2\n", method);
            assertEquals("SUCCESS", report.get("status"));
            assertAnalysisBackend(report, method, true);
            assertEquals(1, ((Number) report.get("newly_winning_old_states")).intValue());
            Map<?, ?> controller = (Map<?, ?>) report.get("controller");
            assertEquals(3, ((Number) controller.get("states")).intValue());
            if (hash == null) hash = controller.get("rooted_sha256").toString();
            assertEquals(hash, controller.get("rooted_sha256"));
            assertTrue(report.get("verification").toString().startsWith("PASS"));
        }
    }

    @Test
    public void duplicateExistingEdgeIsNoOpWithExplicitCachedBackend() throws Exception {
        String hash = null;
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE")) {
            Map<String, Object> report = run(MODEL, "pick\n", "Q1\tdone\tQ0\n", method);
            assertEquals("SUCCESS", report.get("status"));
            assertAnalysisBackend(report, method, false);
            assertEquals(0, ((Number) report.get("added_game_states")).intValue());
            assertEquals(0, ((Number) report.get("newly_winning_old_states")).intValue());
            if (!method.equals("FULL")) {
                assertEquals(0, ((Number) report.get("reanalyzed_states")).intValue());
            }
            Map<?, ?> controller = (Map<?, ?>) report.get("controller");
            if (hash == null) hash = controller.get("rooted_sha256").toString();
            assertEquals(hash, controller.get("rooted_sha256"));
            assertTrue(report.get("verification").toString().startsWith("PASS"));
        }
    }

    @Test
    public void controlledAdditionIsRejectedByPaperMode() throws Exception {
        try { run(MODEL, "pick\nfault\n", "Q1\tfault\tQ0\n", "FULL"); fail("must reject"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("uncontrollable")); }
    }

    @Test
    public void unreachableNewActionHasSameInputRejection() throws Exception {
        String model = MODEL.substring(0, MODEL.indexOf("MON_000="))
                + "MON_000=(pick->ERROR|done->MON_000).\n";
        for (String method : Arrays.asList("FULL", "DIFFERENTIAL")) {
            try { run(model, "pick\n", "Q1\tfault\tQ0\n", method); fail("must reject"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("no reachable product edge")); }
        }
    }

    private static void assertAnalysisBackend(Map<String, Object> report, String method,
            boolean fallback) {
        if (method.equals("FULL")) {
            assertEquals("FULL", report.get("analysis_backend"));
            assertNull(report.get("analysis_fallback_reason"));
            assertTrue(report.get("analysis_algorithm").toString().startsWith("full fixed-safety worklist"));
        } else if (fallback) {
            assertEquals("PREDECESSOR_CLOSURE", report.get("analysis_backend"));
            assertNotNull(report.get("analysis_fallback_reason"));
            assertTrue(report.get("analysis_algorithm").toString().startsWith("predecessor-closure"));
        } else {
            assertEquals("CACHED_LOSING_PROPAGATION", report.get("analysis_backend"));
            assertNull(report.get("analysis_fallback_reason"));
            assertTrue(report.get("analysis_algorithm").toString()
                    .startsWith("fixed-safety cached losing propagation"));
        }
        assertTrue(report.get("analysis_algorithm").toString().contains("not divided candidate analysis"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(String source, String controls, String changes, String method) throws Exception {
        Path root = Files.createTempDirectory("environment-differential-test-");
        Path model = root.resolve("model.lts"), control = root.resolve("controls.txt"), change = root.resolve("change.tsv");
        Path result = root.resolve("result.json");
        Files.writeString(model, source, StandardCharsets.UTF_8);
        Files.writeString(control, controls, StandardCharsets.UTF_8);
        Files.writeString(change, changes, StandardCharsets.UTF_8);
        EnvironmentDifferentialSynthesisRunner.main(new String[]{model.toString(), "1", control.toString(),
                change.toString(), method, result.toString(), "--verify"});
        Map<String, Object> report = (Map<String, Object>) new Yaml().load(Files.readString(result, StandardCharsets.UTF_8));
        assertEquals(report.get("status").equals("SUCCESS"),
                Files.exists(root.resolve("result.json.controller.tsv")));
        return report;
    }
}
