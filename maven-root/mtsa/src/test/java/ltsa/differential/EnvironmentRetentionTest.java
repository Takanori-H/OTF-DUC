package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyController;
import ltsa.differential.EnvironmentSafetyProduct.Component;
import ltsa.differential.EnvironmentSafetyProduct.EnvEdge;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** Ownership changes must preserve semantic correspondence, rejection and diagnostics. */
public class EnvironmentRetentionTest {
    private static final String MODEL = "ENV_COMPONENT=Q0,\n"
            + "Q0=(pick->Q1|done->Q0),\nQ1=(done->Q0).\n||LOAD_ENV=(ENV_COMPONENT).\n"
            + "MON_000=(pick->M1|done->MON_000),\nM1=(done->MON_000|pick->ERROR).\n";

    @Test
    public void nonSelfAdditionWithNewStatesPreservesEveryGuaranteeAndOutput() throws Exception {
        Map<String, Object> compact = compare(MODEL, "pick\n", "Q1\tfault\tQ0\n");
        assertEquals("SUCCESS", compact.get("status"));
        assertTrue(((Number) compact.get("added_game_states")).intValue() > 0);
        Map<String, Object> plain = run(MODEL, "pick\n", "Q1\tfault\tQ0\n", "FULL");
        assertEquals("legacy", plain.get("full_retention"));
        assertEquals(true, plain.get("timing_valid_for_performance"));
        assertEquals(false, plain.get("memory_diagnostics_requested"));
        assertTrue(((List<?>) plain.get("memory_diagnostics")).isEmpty());
    }

    @Test
    public void lossOutsideOldControllerStillRejectsWithoutSwitchWitness() throws Exception {
        String model = "ENV_COMPONENT=Q0,\nQ0=(safe->Q2|enter->Q1),\n"
                + "Q1=(bad->Q2|escape->Q3),\nQ2=(back->Q0),\n"
                + "Q3=(advance->Q4),\nQ4=(return->Q3).\n||LOAD_ENV=(ENV_COMPONENT).\n"
                + "MON_000=(bad->ERROR|fault->ERROR|safe->MON_000).\n";
        Map<String, Object> compact = compare(model, "enter\nescape\n", "Q3\tfault\tQ0\n");
        assertEquals("PAPER_WINNING_REGION_INCOMPATIBLE", compact.get("status"));
        assertEquals(2, ((Number) compact.get("lost_old_winning_states")).intValue());
        assertEquals(0, ((Number) compact.get("lost_old_controller_states")).intValue());
        assertEquals(0, ((Number) compact.get("switch_state_count")).intValue());
    }

    @Test
    public void restoredLosingStatesAreNotConfusedWithNewStates() throws Exception {
        String model = "ENV_COMPONENT=Q0,\nQ0=(go->Q2|try->Q1),\nQ1=STOP,\nQ2=(back->Q0).\n"
                + "||LOAD_ENV=(ENV_COMPONENT).\nMON_000=(back->MON_000).\n";
        Map<String, Object> compact = compare(model, "go\ntry\n", "Q1\tescape\tQ2\n");
        assertEquals("SUCCESS", compact.get("status"));
        assertEquals(1, ((Number) compact.get("newly_winning_old_states")).intValue());
        assertEquals(0, ((Number) compact.get("added_game_states")).intValue());
        assertEquals(0, ((Number) compare(MODEL, "pick\n", "Q1\tfault\tQ0\n")
                .get("newly_winning_old_states")).intValue());
    }

    @Test
    public void witnessMapsMeaningWhenFreshProductRenumbersOldStates() {
        Component environment = new Component("E", Map.of(0, Map.of("z", 1),
                1, Map.of("z", 2), 2, Map.of("back", 0)), Set.of("z", "back"));
        Component monitor = new Component("M", Map.of(0, Map.of("a", 1),
                1, Map.of("back", 0)), Set.of("a", "back"));
        List<Component> monitors = Collections.singletonList(monitor);
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(environment, monitors);
        Analysis<Long, String> base = FullSafetySynthesis.analyze(FullSafetySynthesis.prepare(
                0L, old.rows, old.alphabet, Set.of(), Set.of(-1L)));
        SafetyController<Long, String> controller = FullSafetySynthesis.generateControllerSnapshot(base).get();
        FullUpdateWitness witness = new FullUpdateWitness(old, base, controller);
        EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(environment.apply(
                Collections.singletonList(new EnvEdge(0, "a", 2))).updated, monitors);
        boolean renumbered = false;
        for (Long state : controller.getStates()) {
            Long mapped = witness.mapWinningState(fresh, state);
            assertEquals(old.tuple(state), fresh.tuple(mapped));
            assertEquals(fresh.stateIdFor(old, state), mapped);
            renumbered |= !state.equals(mapped);
        }
        assertTrue("Must exercise changed numeric IDs", renumbered);
        assertTrue(fresh.rows.size() > old.rows.size());
        for (Long state : fresh.rows.keySet()) {
            assertEquals(state == -1L, witness.wasLosing(fresh, state));
        }
        assertEquals(old.rows.size(), witness.gameStateCount());
    }

    @Test
    public void unreachableNewActionIsStillRejectedInCompactMode() throws Exception {
        String model = MODEL.substring(0, MODEL.indexOf("MON_000="))
                + "MON_000=(pick->ERROR|done->MON_000).\n";
        for (String mode : Arrays.asList("legacy", "compact")) {
            try {
                run(model, "pick\n", "Q1\tfault\tQ0\n", "FULL", "--full-retention=" + mode);
                fail("Must reject unreachable new action");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("no reachable product edge"));
            }
        }
    }

    @Test
    public void retentionOptionsAreExplicitAndDoNotApplyToOtherMethods() throws Exception {
        for (String[] options : new String[][]{
                {"DIFFERENTIAL", "--full-retention=compact"},
                {"ANALYSIS_REUSE", "--full-retention=legacy"},
                {"FULL", "--full-retention=unknown"},
                {"FULL", "--full-retention=legacy", "--full-retention=compact"},
                {"FULL", "--memory-diagnostics="},
                {"FULL", "--memory-diagnostics=a", "--memory-diagnostics=b"}}) {
            Path result = Files.createTempDirectory("retention-options-").resolve("result.json");
            List<String> args = new ArrayList<>(Arrays.asList("missing", "1", "missing", "missing",
                    options[0], result.toString()));
            args.addAll(Arrays.asList(options).subList(1, options.length));
            try {
                EnvironmentDifferentialSynthesisRunner.main(args.toArray(new String[0]));
                fail("Must reject: " + args);
            } catch (IllegalArgumentException expected) {
                assertFalse(Files.exists(result));
            }
        }
    }

    @Test
    public void forcedGcDiagnosticsAreMarkedAndDoNotChangeTheController() throws Exception {
        Path directory = Files.createTempDirectory("retention-memory-").resolve("snapshots");
        Map<String, Object> diagnostic = run(MODEL, "pick\n", "Q1\tfault\tQ0\n", "FULL",
                "--verify", "--full-retention=compact", "--memory-diagnostics=" + directory);
        Map<String, Object> plain = run(MODEL, "pick\n", "Q1\tfault\tQ0\n", "FULL",
                "--verify", "--full-retention=compact");
        assertEquals(plain.get("controller"), diagnostic.get("controller"));
        assertEquals(false, diagnostic.get("timing_valid_for_performance"));
        assertEquals(true, diagnostic.get("memory_diagnostics_requested"));
        List<String> checkpoints = new ArrayList<>();
        for (Object value : (List<?>) diagnostic.get("memory_diagnostics")) {
            Map<?, ?> snapshot = (Map<?, ?>) value;
            checkpoints.add(snapshot.get("checkpoint").toString());
            assertEquals(true, snapshot.get("live_only"));
            assertTrue(((Number) snapshot.get("total_bytes")).longValue() > 0);
        }
        assertEquals(Arrays.asList("compiled", "old_product", "old_index", "old_ready", "update_ready",
                "updated_product", "updated_index", "updated_analysis", "updated_controller"), checkpoints);
    }

    private static Map<String, Object> compare(String model, String controls, String changes) throws Exception {
        Map<String, Object> legacy = run(model, controls, changes, "FULL", "--verify", "--full-retention=legacy");
        Map<String, Object> compact = run(model, controls, changes, "FULL", "--verify", "--full-retention=compact");
        for (String key : Arrays.asList("status", "old_game", "updated_game", "old_winning_states",
                "updated_winning_states", "old_controller_states", "lost_old_winning_states",
                "lost_old_controller_states", "newly_winning_old_states", "added_game_states",
                "switch_state_count", "controller", "generation_executed", "verification")) {
            assertEquals(key, legacy.get(key), compact.get(key));
        }
        assertTrue(compact.get("verification").toString().startsWith("PASS"));
        assertTrue(((Number) ((Map<?, ?>) compact.get("timing_ns")).get("old_guarantee_capture")).longValue() >= 0);
        return compact;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(String model, String controls, String changes,
            String method, String... options) throws Exception {
        Path directory = Files.createTempDirectory("retention-test-");
        Path source = directory.resolve("model.lts"), control = directory.resolve("controls.txt"),
                change = directory.resolve("change.tsv"), result = directory.resolve("result.json");
        Files.writeString(source, model); Files.writeString(control, controls); Files.writeString(change, changes);
        List<String> args = new ArrayList<>(Arrays.asList(source.toString(), "1", control.toString(),
                change.toString(), method, result.toString()));
        args.addAll(Arrays.asList(options));
        EnvironmentDifferentialSynthesisRunner.main(args.toArray(new String[0]));
        return (Map<String, Object>) new Yaml().load(Files.readString(result));
    }
}
