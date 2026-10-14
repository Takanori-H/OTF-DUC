package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.SafetyController;
import ltsa.lts.EmptyLTSOuput;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class DifferentialSynthesisRunnerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path RESOURCES = Paths.get("src/test/resources/DifferentialSynthesis");

    @Test public void bothMethodsProduceSameControllerAndPreparedInput() throws Exception {
        for (String scenario : new String[]{"batch-good", "recovery-good"}) {
            String target = scenario.startsWith("batch") ? "B_GAME_OLD" : "NS_GAME_OLD";
            Run full = execute(scenario, target, "FULL");
            Run differential = execute(scenario, target, "DIFFERENTIAL");
            assertEquals(full.text(), differential.text());
            assertEquals("SUCCESS", full.metrics().get("ds_status"));
            assertEquals(full.metrics().get("ds_prepared_input_sha256"),
                    differential.metrics().get("ds_prepared_input_sha256"));
            assertEquals(full.metrics().get("ds_controller_output_sha256"),
                    differential.metrics().get("ds_controller_output_sha256"));
            assertEquals("FULL", full.metrics().get("ds_method"));
            assertEquals("DIFFERENTIAL", differential.metrics().get("ds_method"));
            for (Run run : new Run[]{full, differential}) {
                assertTrue(Long.parseLong(run.metrics().get("ds_generation_ns")) >= 0);
                assertEquals("generation_with_prepared_inputs", run.metrics().get("ds_memory_scope"));
                assertEquals("true", run.metrics().get("heap_memory_sampling_available"));
                assertTrue(Long.parseLong(run.metrics().get("heap_memory_sampling_sample_count")) >= 2);
                assertTrue(Files.size(DifferentialSynthesisRunner.switchPath(run.transitions)) > 0);
            }
            assertFalse(Files.exists(DifferentialSynthesisRunner.patchPath(full.transitions)));
            String patch = read(DifferentialSynthesisRunner.patchPath(differential.transitions));
            assertTrue(patch.startsWith("PATCH\tADDITIONS\n"));
            for (String row : patch.split("\n")) {
                if (row.startsWith("EDGE\t") || row.startsWith("STATE\t") || row.startsWith("ACTION\t")) {
                    assertTrue("patch row missing in new controller: " + row, differential.text().contains(row + "\n"));
                }
            }
            assertTrue(patch.contains(scenario.startsWith("batch") ? "\"alarm\"" : "\"divert\""));
            // Independently replay the exported additions onto the actual old
            // controller, not onto either generator's new snapshot.
            SafetyController<Long, String> old = FullSafetySynthesis.generateControllerSnapshot(
                    FullSafetySynthesisRunner.analyze(RESOURCES.resolve("BatchSafety.lts"), target,
                            new HashSet<>(Arrays.asList("idle", "start")), new EmptyLTSOuput())).get();
            Set<String> reconstructed = new HashSet<>();
            reconstructed.add("STATUS\tSUCCESS");
            reconstructed.add("INITIAL\t" + old.getInitialState());
            for (String action : old.getActions()) { reconstructed.add("ACTION\t\"" + action + "\""); }
            for (Long state : old.getStates()) {
                reconstructed.add("STATE\t" + state);
                for (Map.Entry<String, Long> edge : old.getTransitions(state).entrySet()) {
                    reconstructed.add("EDGE\t" + state + "\t\"" + edge.getKey() + "\"\t" + edge.getValue());
                }
            }
            for (String row : patch.split("\n")) {
                if (!row.startsWith("PATCH\t")) { reconstructed.add(row); }
            }
            assertEquals(new HashSet<>(Arrays.asList(full.text().split("\n"))), reconstructed);
        }
    }

    @Test public void rejectedUpdatesAreResultsWithoutGenerationOrDummyControllers() throws Exception {
        for (String method : new String[]{"FULL", "DIFFERENTIAL"}) {
            for (String scenario : new String[]{"batch-incompatible", "batch-unrealizable"}) {
                String expected = scenario.endsWith("incompatible") ? "SIMULATION_INCOMPATIBLE" : "UNREALIZABLE";
                Run run = execute(scenario, "B_GAME_OLD", method);
                assertEquals("STATUS\t" + expected + "\n", run.text());
                assertEquals(expected, run.metrics().get("ds_status"));
                assertEquals("", run.metrics().get("ds_generation_ns"));
                assertEquals("not_run", run.metrics().get("ds_memory_scope"));
                assertFalse(Files.exists(DifferentialSynthesisRunner.patchPath(run.transitions)));
                assertFalse(Files.exists(DifferentialSynthesisRunner.switchPath(run.transitions)));
            }
        }
    }

    @Test public void baseWithoutControllerIsReportedSeparately() throws Exception {
        Path model = temporary.newFile("dead.lts").toPath();
        write(model, "DEAD=STOP.\n||GAME=(DEAD).\n");
        Path scenario = temporary.newFile("empty.yaml").toPath();
        write(scenario, "controllableActions: []\nstates: {}\ntransitions: []\n");
        Run run = new Run();
        assertEquals(0, run.invoke(model, "GAME", scenario, "DIFFERENTIAL"));
        assertEquals("STATUS\tBASE_UNREALIZABLE\n", run.text());
        assertEquals("", run.metrics().get("ds_compatibility_ns"));
        assertEquals("", run.metrics().get("ds_generation_ns"));
        Run measured = new Run();
        assertEquals(0, measured.invoke(model, "GAME", scenario, "FROM_SCRATCH", "update"));
        assertEquals("STATUS\tBASE_UNREALIZABLE\n", measured.text());
        assertEquals("", measured.metrics().get("ds_compatibility_ns"));
        assertEquals("", measured.metrics().get("ds_generation_ns"));
        assertEquals("true", measured.metrics().get("heap_memory_sampling_available"));
        assertTrue(Long.parseLong(measured.metrics().get("ds_update_pipeline_ns")) >= 0);
    }

    @Test public void allThreeMethodsAgreeInBothMeasurementScopes() throws Exception {
        for (String scope : new String[]{"generation", "update"}) {
            for (String scenario : new String[]{"batch-good", "recovery-good", "batch-incompatible", "batch-unrealizable"}) {
                String target = scenario.startsWith("recovery") ? "NS_GAME_OLD" : "B_GAME_OLD";
                Run reference = null;
                for (String method : new String[]{"FULL", "DIFFERENTIAL", "FROM_SCRATCH"}) {
                    Run run = new Run();
                    assertEquals(0, run.invoke(RESOURCES.resolve("BatchSafety.lts"), target,
                            RESOURCES.resolve("cli/" + scenario + ".yaml"), method, scope));
                    Map<String, String> metrics = run.metrics();
                    assertEquals("2", metrics.get("ds_schema_version"));
                    assertEquals(scope, metrics.get("ds_measurement_scope"));
                    if (reference == null) { reference = run; }
                    assertEquals(reference.text(), run.text());
                    assertEquals(reference.metrics().get("ds_prepared_input_sha256"),
                            metrics.get("ds_prepared_input_sha256"));
                    if ("FROM_SCRATCH".equals(method)) {
                        assertEquals("FULL_REANALYSIS", metrics.get("ds_analysis_strategy"));
                        assertEquals("0", metrics.get("ds_reused_analysis_states"));
                        assertEquals(metrics.get("ds_updated_game_states"), metrics.get("ds_affected_states"));
                        assertFalse(Files.exists(DifferentialSynthesisRunner.patchPath(run.transitions)));
                    }
                    if ("update".equals(scope)) {
                        assertEquals("update_with_old_inputs", metrics.get("ds_memory_scope"));
                        assertEquals("true", metrics.get("heap_memory_sampling_available"));
                        long measuredStages = 0;
                        for (String phase : new String[]{"game_update", "analysis", "compatibility", "generation"}) {
                            String value = metrics.get("ds_" + phase + "_ns");
                            if (!value.isEmpty()) { measuredStages += Long.parseLong(value); }
                        }
                        assertTrue(Long.parseLong(metrics.get("ds_update_pipeline_ns")) >= measuredStages);
                    } else {
                        assertEquals("", metrics.get("ds_update_pipeline_ns"));
                    }
                }
            }
        }
    }

    @Test public void malformedTraceFailsBeforeProducingAcceptedOutput() throws Exception {
        Path scenario = temporary.newFile("bad.yaml").toPath();
        write(scenario, "controllableActions: [idle, start]\nstates:\n  s: {trace: [missing]}\ntransitions: []\n");
        Run run = new Run();
        assertEquals(64, run.invoke(RESOURCES.resolve("BatchSafety.lts"), "B_GAME_OLD", scenario, "FULL"));
        assertFalse(Files.exists(run.transitions));
        assertFalse(Files.exists(run.csv));
    }

    @Test public void refusesToMixWithPreviousResults() throws Exception {
        Run run = execute("batch-good", "B_GAME_OLD", "FULL");
        String old = run.text();
        assertEquals(64, run.invoke(RESOURCES.resolve("BatchSafety.lts"), "B_GAME_OLD",
                RESOURCES.resolve("cli/batch-good.yaml"), "DIFFERENTIAL"));
        assertEquals(old, run.text());
    }

    @Test public void rejectsUnknownAndDuplicateOptions() {
        assertEquals(64, DifferentialSynthesisRunner.runMain(new String[]{"--mistyped", "x"}));
        assertEquals(64, DifferentialSynthesisRunner.runMain(new String[]{"--lts", "x", "--lts", "y"}));
    }

    @Test public void rejectsOutputCollisionsBeforeCreatingFiles() throws Exception {
        Path shared = temporary.getRoot().toPath().resolve("shared");
        assertEquals(64, DifferentialSynthesisRunner.runMain(new String[]{
                "--lts", RESOURCES.resolve("BatchSafety.lts").toString(), "--target", "B_GAME_OLD",
                "--scenario", RESOURCES.resolve("cli/batch-good.yaml").toString(), "--method", "FULL",
                "--output", temporary.getRoot().toPath().resolve("log").toString(),
                "--transitions", shared.toString(), "--execution-metadata", shared.toString(),
                "--metrics", temporary.getRoot().toPath().resolve("metrics").toString()}));
        assertFalse(Files.exists(shared));
    }

    private Run execute(String scenario, String target, String method) throws Exception {
        Run run = new Run();
        assertEquals(0, run.invoke(RESOURCES.resolve("BatchSafety.lts"), target,
                RESOURCES.resolve("cli/" + scenario + ".yaml"), method));
        return run;
    }

    private static String read(Path file) throws Exception {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
    private static void write(Path file, String value) throws Exception {
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
    }

    private final class Run {
        final Path folder;
        final Path transitions;
        final Path csv;
        Run() throws Exception {
            folder = temporary.newFolder().toPath();
            transitions = folder.resolve("controller.tsv");
            csv = folder.resolve("metrics.csv");
        }
        int invoke(Path model, String target, Path scenario, String method) {
            return invoke(model, target, scenario, method, "generation");
        }
        int invoke(Path model, String target, Path scenario, String method, String scope) {
            return DifferentialSynthesisRunner.runMain(new String[]{
                    "--lts", model.toString(), "--target", target,
                    "--scenario", scenario.toString(), "--method", method,
                    "--measurement-scope", scope,
                    "--output", folder.resolve("run.log").toString(),
                    "--transitions", transitions.toString(), "--metrics", csv.toString(),
                    "--execution-metadata", folder.resolve("execution.json").toString()});
        }
        String text() throws Exception { return read(transitions); }
        Map<String, String> metrics() throws Exception {
            Map<String, String> result = new HashMap<>();
            // These controlled test metrics contain no embedded newlines or commas.
            for (String row : Files.readAllLines(csv, StandardCharsets.UTF_8)) {
                String[] fields = row.split(",", -1);
                if (fields.length == 19) {
                    result.put(unquote(fields[4]), unquote(fields[6]));
                }
            }
            return result;
        }
        String unquote(String value) {
            return value.startsWith("\"") && value.endsWith("\"")
                    ? value.substring(1, value.length() - 1).replace("\"\"", "\"") : value;
        }
    }
}
