package ltsa.differential;

import MTSSynthesis.controller.differential.ControllerUpdateSynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyController;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import org.junit.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.*;

/** Explicit graph updates checked against independently compiled FSP targets. */
public class ControllerUpdateSynthesisIntegrationTest {
    private static final Set<String> CONTROLS =
            new HashSet<>(Arrays.asList("idle", "start"));

    @Test
    public void safeBatchHasEquivalentFullAndDifferentialControllers() throws Exception {
        Analysis<Long, String> old = analyze("B_GAME_OLD");
        Long working = follow(old.copyGame(), "start");
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(old)
                .addTransition(old.getInitialState(), "alarm", working)
                .addTransition(working, "retry", working)
                .build();

        assertSuccessfulUpdate(old, delta, "B_GAME_GOOD");
    }

    @Test
    public void newRecoveryStateHasEquivalentFullAndDifferentialControllers() throws Exception {
        Analysis<Long, String> old = analyze("NS_GAME_OLD");
        LTS<Long, String> game = old.copyGame();
        Long working = follow(game, "start");
        Long recovery = freshState(game);
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(old)
                .addState(recovery)
                .addTransition(working, "divert", recovery)
                .addTransition(recovery, "resume", working)
                .addTransition(recovery, "wait", recovery)
                .build();

        assertSuccessfulUpdate(old, delta, "NS_GAME_GOOD");
    }

    @Test
    public void losingOldWorkingStateRejectsSimulationDespiteWinningInitialState() throws Exception {
        Analysis<Long, String> old = analyze("B_GAME_OLD");
        LTS<Long, String> game = old.copyGame();
        Long working = follow(game, "start");
        Long error = freshState(game);
        SafetyController<Long, String> oldController =
                FullSafetySynthesis.generateControllerSnapshot(old).get();
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(old)
                .addUnsafeState(error)
                .addTransition(working, "fail", error)
                .build();
        IncrementalSafetyAnalysis.Result<Long, String> change =
                IncrementalSafetyAnalysis.update(old, delta);

        assertTrue("Initial idle loop still permits a safe controller", change.getAnalysis().isRealizable());
        assertTrue("The old controller could have reached working",
                oldController.copyLts().getStates().contains(working));
        assertTrue(change.getAnalysis().getLosingStates().contains(working));
        assertSameBehavior(FullSafetySynthesis.generateController(analyze("B_GAME_FAIL")).get(),
                FullSafetySynthesis.generateController(change.getAnalysis()).get());

        var plan = ControllerUpdateSynthesis.prepare(oldController, change);
        assertRejected(ControllerUpdateSynthesis.generateFull(plan),
                ControllerUpdateSynthesis.Status.SIMULATION_INCOMPATIBLE);
        assertRejected(ControllerUpdateSynthesis.generateDifferential(plan),
                ControllerUpdateSynthesis.Status.SIMULATION_INCOMPATIBLE);
        assertSameBehavior(FullSafetySynthesis.generateController(old).get(), oldController.copyLts());
    }

    @Test
    public void losingInitialStateReportsUnrealizableForBothGenerationModes() throws Exception {
        Analysis<Long, String> old = analyze("B_GAME_OLD");
        LTS<Long, String> game = old.copyGame();
        Long working = follow(game, "start");
        Long error = freshState(game);
        SafetyController<Long, String> oldController =
                FullSafetySynthesis.generateControllerSnapshot(old).get();
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(old)
                .addUnsafeState(error)
                .addTransition(old.getInitialState(), "alarm", working)
                .addTransition(working, "retry", working)
                .addTransition(working, "fail", error)
                .build();
        IncrementalSafetyAnalysis.Result<Long, String> change =
                IncrementalSafetyAnalysis.update(old, delta);

        assertFalse(change.getAnalysis().isRealizable());
        assertFalse(analyze("B_GAME_BAD").isRealizable());
        var plan = ControllerUpdateSynthesis.prepare(oldController, change);
        assertRejected(ControllerUpdateSynthesis.generateFull(plan),
                ControllerUpdateSynthesis.Status.UNREALIZABLE);
        assertRejected(ControllerUpdateSynthesis.generateDifferential(plan),
                ControllerUpdateSynthesis.Status.UNREALIZABLE);
        assertSameBehavior(FullSafetySynthesis.generateController(old).get(), oldController.copyLts());
    }

    private static void assertSuccessfulUpdate(Analysis<Long, String> old,
            SafetyGameDelta<Long, String> delta, String newTarget) throws Exception {
        SafetyController<Long, String> oldController =
                FullSafetySynthesis.generateControllerSnapshot(old).get();
        LTS<Long, String> oldBefore = oldController.copyLts();
        IncrementalSafetyAnalysis.Result<Long, String> change =
                IncrementalSafetyAnalysis.update(old, delta);
        var plan = ControllerUpdateSynthesis.prepare(oldController, change);
        var full = ControllerUpdateSynthesis.generateFull(plan);
        var differential = ControllerUpdateSynthesis.generateDifferential(plan);

        assertEquals(ControllerUpdateSynthesis.Status.SUCCESS, full.getStatus());
        assertEquals(ControllerUpdateSynthesis.Status.SUCCESS, differential.getStatus());
        assertTrue(full.getController().isPresent());
        assertTrue(differential.getController().isPresent());
        assertTrue("Differential success must supply a replayable patch", differential.getPatch().isPresent());
        LTS<Long, String> reference = FullSafetySynthesis.generateController(analyze(newTarget)).get();
        assertSameBehavior(reference, full.getController().get().copyLts());
        assertSameBehavior(reference, differential.getController().get().copyLts());
        assertSameBehavior(reference, differential.getPatch().get().apply(oldController).copyLts());

        Map<Long, Long> identity = new HashMap<>();
        for (Long state : oldBefore.getStates()) {
            identity.put(state, state);
        }
        assertEquals("Every old reachable controller state can switch", identity, full.getSwitchStateMapping());
        assertEquals(identity, differential.getSwitchStateMapping());
        assertSameBehavior(oldBefore, oldController.copyLts());
        assertSameBehavior(oldBefore, FullSafetySynthesis.generateController(old).get());
    }

    private static void assertRejected(ControllerUpdateSynthesis.Result<Long, String> result,
            ControllerUpdateSynthesis.Status expected) {
        assertEquals(expected, result.getStatus());
        assertFalse("Rejected update cannot expose a controller", result.getController().isPresent());
        assertFalse("Rejected update cannot expose a patch", result.getPatch().isPresent());
        assertTrue("Rejected update cannot expose a switch mapping", result.getSwitchStateMapping().isEmpty());
    }

    private static Analysis<Long, String> analyze(String target) throws Exception {
        URL input = ControllerUpdateSynthesisIntegrationTest.class
                .getResource("/DifferentialSynthesis/BatchSafety.lts");
        assertNotNull("Missing BatchSafety.lts fixture", input);
        synchronized (LTSCompiler.class) {
            return FullSafetySynthesisRunner.analyze(
                    Paths.get(input.toURI()), target, CONTROLS, new EmptyLTSOuput());
        }
    }

    /**
     * Pair states by action paths from the initial state. Compiled FSP numbers
     * and explicit delta IDs are never compared across independently built games.
     */
    private static void assertSameBehavior(LTS<Long, String> expected, LTS<Long, String> actual) {
        assertEquals("Controller alphabet", expected.getActions(), actual.getActions());
        Map<Long, Long> forward = new HashMap<>();
        Map<Long, Long> reverse = new HashMap<>();
        Queue<Long> pending = new ArrayDeque<>();
        forward.put(expected.getInitialState(), actual.getInitialState());
        reverse.put(actual.getInitialState(), expected.getInitialState());
        pending.add(expected.getInitialState());
        while (!pending.isEmpty()) {
            Long expectedSource = pending.remove();
            Long actualSource = forward.get(expectedSource);
            Map<String, Long> expectedEdges = outgoing(expected, expectedSource);
            Map<String, Long> actualEdges = outgoing(actual, actualSource);
            assertEquals("Enabled actions at paired states", expectedEdges.keySet(), actualEdges.keySet());
            for (Map.Entry<String, Long> edge : expectedEdges.entrySet()) {
                Long expectedTarget = edge.getValue();
                Long actualTarget = actualEdges.get(edge.getKey());
                if (forward.containsKey(expectedTarget)) {
                    assertEquals("Action paths must agree on the target role",
                            forward.get(expectedTarget), actualTarget);
                } else {
                    assertFalse("Distinct reference roles must stay distinct", reverse.containsKey(actualTarget));
                    forward.put(expectedTarget, actualTarget);
                    reverse.put(actualTarget, expectedTarget);
                    pending.add(expectedTarget);
                }
            }
        }
        assertEquals("Reference controller must be reachable", expected.getStates(), forward.keySet());
        assertEquals("No extra or unreachable controller states", actual.getStates(), reverse.keySet());
    }

    private static Map<String, Long> outgoing(LTS<Long, String> game, Long state) {
        Map<String, Long> result = new HashMap<>();
        for (Pair<String, Long> edge : game.getTransitions(state)) {
            assertNull("Fixtures must be label deterministic", result.put(edge.getFirst(), edge.getSecond()));
        }
        return result;
    }

    private static Long follow(LTS<Long, String> game, String action) {
        Set<Long> successors = game.getTransitions(game.getInitialState()).getImage(action);
        assertEquals("Expected one successor for " + action, 1, successors.size());
        return successors.iterator().next();
    }

    private static Long freshState(LTS<Long, String> game) {
        long candidate = 1_000_000L;
        while (game.getStates().contains(candidate)) {
            candidate++;
        }
        return candidate;
    }
}
