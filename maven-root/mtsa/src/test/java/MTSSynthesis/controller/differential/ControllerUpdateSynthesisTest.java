package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Plan;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Result;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Status;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.*;

public class ControllerUpdateSynthesisTest {
    @Test
    public void safeAddedTransitionPreservesEveryOldStateAndEdge() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "retry", 1).build());
        LTS<Integer, String> expected = twoStateGame();
        edge(expected, 1, "retry", 1);

        Result<Integer, String> result = assertSuccessfulUpdate(old, update, expected);
        assertEquals(Set.of(), result.getPatch().get().getAddedStates());
        assertEquals(Set.of("retry"), result.getPatch().get().getAddedActions());
        assertEquals(Set.of(new Transition<>(1, "retry", 1)),
                result.getPatch().get().getAddedTransitions());
        assertEquals(Map.of(0, 0, 1, 1), result.getSwitchStateMapping());
        assertEquals(1, result.getExpandedStateCount());
        assertEquals(1, result.getReusedRowCount());
    }

    @Test
    public void newReachableRecoveryStatesAreExpandedThroughTheWholeCycle() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addState(3)
                        .addTransition(1, "divert", 2)
                        .addTransition(2, "wait", 2)
                        .addTransition(2, "recover", 3)
                        .addTransition(3, "resume", 1).build());
        LTS<Integer, String> expected = twoStateGame();
        expected.addState(2);
        expected.addState(3);
        edge(expected, 1, "divert", 2);
        edge(expected, 2, "wait", 2);
        edge(expected, 2, "recover", 3);
        edge(expected, 3, "resume", 1);

        Result<Integer, String> result = assertSuccessfulUpdate(old, update, expected);
        assertEquals(Set.of(2, 3), result.getPatch().get().getAddedStates());
        assertEquals(4, result.getPatch().get().getAddedTransitions().size());
        // Handoff describes old runtime states, not every new controller state.
        assertEquals(Map.of(0, 0, 1, 1), result.getSwitchStateMapping());
    }

    @Test
    public void recoveryOfOldLosingStatesAlsoAddsPreviouslyExcludedGameEdges() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "idle", 0);
        edge(original, 0, "go", 1);
        Analysis<Integer, String> base = analyze(original, Set.of("idle", "go"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        assertEquals(Set.of(0), old.getStates());
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "loop", 1).build());

        LTS<Integer, String> expected = game(0, 1);
        edge(expected, 0, "idle", 0);
        edge(expected, 0, "go", 1);
        edge(expected, 1, "loop", 1);
        Result<Integer, String> result = assertSuccessfulUpdate(old, update, expected);
        assertEquals(Set.of(new Transition<>(1, "loop", 1)), update.getAddedTransitions());
        assertEquals(Set.of(new Transition<>(0, "go", 1), new Transition<>(1, "loop", 1)),
                result.getPatch().get().getAddedTransitions());
        assertEquals(Set.of(1), result.getPatch().get().getAddedStates());
    }

    @Test
    public void losingAnUnreachableOldWinningStateDoesNotRejectTheRuntimeController() {
        LTS<Integer, String> original = game(0, 1, 2);
        edge(original, 0, "idle", 0);
        edge(original, 1, "hiddenLoop", 1);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of(2));
        SafetyController<Integer, String> old = snapshot(base);
        assertEquals(Set.of(0, 1), base.getWinningStates());
        assertEquals(Set.of(0), old.getStates());
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "fail", 2).build());

        LTS<Integer, String> expectedGame = game(0, 1, 2);
        edge(expectedGame, 0, "idle", 0);
        edge(expectedGame, 1, "hiddenLoop", 1);
        edge(expectedGame, 1, "fail", 2);
        Result<Integer, String> result = assertSuccessfulUpdate(old, update,
                independentlyExtract(expectedGame, Set.of(0)));
        assertEquals(Set.of(1), update.getNewlyLosingStates());
        assertEquals(Set.of(), result.getIncompatibleOldStates());
        assertEquals(Set.of(), result.getPatch().get().getAddedTransitions());
        assertEquals(Set.of("fail"), result.getPatch().get().getAddedActions());
    }

    @Test
    public void losingAnOldReachableStateIsIncompatibleEvenWhenInitialStateStillWins() {
        LTS<Integer, String> original = twoStateGame();
        original.addState(2);
        Analysis<Integer, String> base = analyze(original, Set.of("idle", "start"), Set.of(2));
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "fail", 2).build());
        assertTrue(update.getAnalysis().isRealizable());
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, update);

        assertRejectedPlan(plan, Status.SIMULATION_INCOMPATIBLE);
        assertEquals(Set.of(1), plan.getIncompatibleOldStates());
        LTS<Integer, String> newSafeController = game(0);
        edge(newSafeController, 0, "idle", 0);
        assertFalse(simulates(newSafeController, old.copyLts()));
        // Rejection must preserve the still-owned old controller snapshot.
        assertGraphEquals(twoStateGame(), old.copyLts());
    }

    @Test
    public void losingTheInitialStateReportsUnrealizableRatherThanOnlyIncompatible() {
        LTS<Integer, String> original = game(0, 1, 2);
        edge(original, 0, "enter", 1);
        edge(original, 1, "return", 0);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of(2));
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "fail", 2).build());

        assertFalse(update.getAnalysis().isRealizable());
        assertRejectedPlan(ControllerUpdateSynthesis.prepare(old, update), Status.UNREALIZABLE);
        assertFalse(FullSafetySynthesis.generateControllerSnapshot(update.getAnalysis()).isPresent());
    }

    @Test
    public void unrelatedNewComponentChangesOnlyTheControllerAlphabet() {
        LTS<Integer, String> original = game(0);
        edge(original, 0, "idle", 0);
        original.addAction("reserved");
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(5)
                        .addTransition(5, "unrelated", 5).build());
        LTS<Integer, String> expected = game(0);
        edge(expected, 0, "idle", 0);
        expected.addAction("reserved");
        expected.addAction("unrelated");

        Result<Integer, String> result = assertSuccessfulUpdate(old, update, expected);
        assertEquals(Set.of(), result.getPatch().get().getAddedStates());
        assertEquals(Set.of(), result.getPatch().get().getAddedTransitions());
        assertEquals(Set.of("unrelated"), result.getPatch().get().getAddedActions());
    }

    @Test
    public void noOpUpdatesProduceEmptyPatchesForTheNewAnalysisVersion() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> empty = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).build());
        Result<Integer, String> first = assertSuccessfulUpdate(old, empty, twoStateGame());
        assertEmptyPatch(first.getPatch().get());
        assertEquals(0, first.getExpandedStateCount());
        assertEquals(old.getStates().size(), first.getReusedRowCount());
        assertEquals(1L, first.getController().get().getAnalysis().getRevision());

        SafetyController<Integer, String> current = first.getController().get();
        Analysis<Integer, String> currentAnalysis = current.getAnalysis();
        IncrementalSafetyAnalysis.Result<Integer, String> duplicate = IncrementalSafetyAnalysis.update(
                currentAnalysis, SafetyGameDelta.forAnalysis(currentAnalysis)
                        .addTransition(0, "idle", 0).build());
        Result<Integer, String> second = assertSuccessfulUpdate(current, duplicate, twoStateGame());
        assertEmptyPatch(second.getPatch().get());
        assertEquals(2L, second.getController().get().getAnalysis().getRevision());
        assertEquals(0L, old.getAnalysis().getRevision());
    }

    @Test
    public void prepareRejectsAnotherBaseEvenAtTheSameRevision() {
        Analysis<Integer, String> firstBase = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        Analysis<Integer, String> otherBase = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(firstBase);
        IncrementalSafetyAnalysis.Result<Integer, String> otherUpdate = IncrementalSafetyAnalysis.update(
                otherBase, SafetyGameDelta.forAnalysis(otherBase).addTransition(1, "retry", 1).build());
        assertEquals(firstBase.getRevision(), otherBase.getRevision());
        rejects(() -> ControllerUpdateSynthesis.prepare(old, otherUpdate));
        assertGraphEquals(twoStateGame(), old.copyLts());
    }

    @Test
    public void patchRejectsAnySnapshotOtherThanItsExactOldController() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "retry", 1).build());
        Result<Integer, String> result = ControllerUpdateSynthesis.generateDifferential(
                ControllerUpdateSynthesis.prepare(old, update));
        ControllerPatch<Integer, String> patch = result.getPatch().get();
        SafetyController<Integer, String> sameAnalysisDifferentSnapshot = snapshot(base);
        assertNotSame(old, sameAnalysisDifferentSnapshot);
        rejects(() -> patch.apply(sameAnalysisDifferentSnapshot));
        rejects(() -> patch.apply(result.getController().get()));
        assertGraphEquals(result.getController().get().copyLts(), patch.apply(old).copyLts());
        assertGraphEquals(twoStateGame(), old.copyLts());
    }

    @Test
    public void snapshotsPatchesAndSwitchMappingsCannotBeMutatedThroughGetters() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> update = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(2)
                        .addTransition(1, "divert", 2)
                        .addTransition(2, "resume", 0).build());
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, update);
        Result<Integer, String> result = ControllerUpdateSynthesis.generateDifferential(plan);
        ControllerPatch<Integer, String> patch = result.getPatch().get();
        SafetyController<Integer, String> updated = result.getController().get();

        immutable(() -> old.getStates().add(99));
        immutable(() -> old.getActions().add("injected"));
        immutable(() -> old.getTransitions(0).put("injected", 99));
        immutable(() -> patch.getAddedStates().add(99));
        immutable(() -> patch.getAddedActions().add("injected"));
        immutable(() -> patch.getAddedTransitions().add(new Transition<>(0, "injected", 99)));
        immutable(() -> plan.getSwitchStateMapping().put(0, 99));
        immutable(() -> result.getSwitchStateMapping().put(0, 99));
        LTS<Integer, String> copy = updated.copyLts();
        copy.removeTransition(0, "idle", 0);
        assertEquals(Integer.valueOf(0), updated.getTransitions(0).get("idle"));
        assertGraphEquals(twoStateGame(), old.copyLts());
        assertGraphEquals(updated.copyLts(), patch.apply(old).copyLts());
    }

    @Test
    public void patchesCanBeChainedUsingThePreviousUpdatedSnapshot() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "idle", 0);
        edge(original, 1, "return", 0);
        Analysis<Integer, String> base = analyze(original, Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> firstUpdate = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "start", 1).build());
        LTS<Integer, String> firstExpected = twoStateGameWithReturn();
        Result<Integer, String> first = assertSuccessfulUpdate(old, firstUpdate, firstExpected);
        SafetyController<Integer, String> current = first.getPatch().get().apply(old);
        Analysis<Integer, String> currentAnalysis = current.getAnalysis();
        IncrementalSafetyAnalysis.Result<Integer, String> secondUpdate = IncrementalSafetyAnalysis.update(
                currentAnalysis, SafetyGameDelta.forAnalysis(currentAnalysis).addState(2)
                        .addTransition(1, "divert", 2)
                        .addTransition(2, "resume", 0).build());
        LTS<Integer, String> expected = twoStateGameWithReturn();
        expected.addState(2);
        edge(expected, 1, "divert", 2);
        edge(expected, 2, "resume", 0);
        Result<Integer, String> second = assertSuccessfulUpdate(current, secondUpdate, expected);

        assertEquals(2L, second.getController().get().getAnalysis().getRevision());
        assertEquals(Set.of(2), second.getPatch().get().getAddedStates());
        rejects(() -> second.getPatch().get().apply(old));
        assertEquals(Set.of(0), old.getStates());
        assertGraphEquals(firstExpected, current.copyLts());
    }

    @Test
    public void tinyGameUpdatesMatchIndependentMaximalControllersAndSimulationOracle() {
        int checkedUpdates = 0;
        int skippedUpdates = 0;
        int realizableBases = 0;
        for (int encoding = 0; encoding < 256; encoding++) {
            LTS<Integer, String> original = tinyGame(encoding);
            Analysis<Integer, String> base = analyze(original, Set.of("c"), Set.of(2));
            Set<Integer> oldWinning = tinyWinningOracle(original);
            boolean oldRealizable = oldWinning.contains(0);
            assertEquals(oldRealizable, FullSafetySynthesis.generateControllerSnapshot(base).isPresent());
            SafetyController<Integer, String> old = oldRealizable ? snapshot(base) : null;
            LTS<Integer, String> expectedOld = oldRealizable
                    ? independentlyExtract(original, oldWinning) : null;
            if (oldRealizable) {
                realizableBases++;
                assertGraphEquals(expectedOld, old.copyLts());
            }
            for (int source = 0; source < 2; source++) {
                for (String action : new String[] {"c", "u"}) {
                    if (!original.getTransitions(source).getImage(action).isEmpty()) {
                        continue;
                    }
                    for (int target = 0; target < 3; target++) {
                        if (!oldRealizable) {
                            skippedUpdates++;
                            continue;
                        }
                        IncrementalSafetyAnalysis.Result<Integer, String> update =
                                IncrementalSafetyAnalysis.update(base, SafetyGameDelta.forAnalysis(base)
                                        .addTransition(source, action, target).build());
                        // Independent game reconstruction and closed-subset oracle:
                        // neither the expected graph nor its simulation relation
                        // comes from the update implementation or its state mapping.
                        LTS<Integer, String> expectedGame = tinyGame(encoding);
                        edge(expectedGame, source, action, target);
                        Set<Integer> newWinning = tinyWinningOracle(expectedGame);
                        LTS<Integer, String> expectedNew = newWinning.contains(0)
                                ? independentlyExtract(expectedGame, newWinning) : null;
                        Status expectedStatus = expectedNew == null ? Status.UNREALIZABLE
                                : simulates(expectedNew, expectedOld) ? Status.SUCCESS
                                : Status.SIMULATION_INCOMPATIBLE;
                        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, update);
                        String context = "encoding=" + encoding + ", add="
                                + source + ":" + action + ":" + target;
                        assertEquals(context, expectedStatus, plan.getStatus());
                        assertEquals(context, expectedStatus == Status.SUCCESS, plan.isCompatible());
                        if (expectedStatus == Status.SUCCESS) {
                            assertSuccessfulUpdate(old, update, expectedNew);
                        } else {
                            assertRejectedPlan(plan, expectedStatus);
                        }
                        assertGraphEquals(expectedOld, old.copyLts());
                        checkedUpdates++;
                    }
                }
            }
        }
        // All 768 additions are accounted for. An unrealizable old game has
        // no controller snapshot and therefore no controller-update input.
        assertEquals(130, realizableBases);
        assertEquals(336, checkedUpdates);
        assertEquals(432, skippedUpdates);
        assertEquals(768, checkedUpdates + skippedUpdates);
    }

    private static Result<Integer, String> assertSuccessfulUpdate(
            SafetyController<Integer, String> old,
            IncrementalSafetyAnalysis.Result<Integer, String> update,
            LTS<Integer, String> expectedController) {
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, update);
        assertEquals(Status.SUCCESS, plan.getStatus());
        assertTrue(plan.isCompatible());
        Map<Integer, Integer> identity = new HashMap<>();
        for (Integer state : old.getStates()) {
            identity.put(state, state);
        }
        assertEquals(identity, plan.getSwitchStateMapping());
        assertEquals(Set.of(), plan.getIncompatibleOldStates());
        Result<Integer, String> full = ControllerUpdateSynthesis.generateFull(plan);
        Result<Integer, String> differential = ControllerUpdateSynthesis.generateDifferential(plan);
        for (Result<Integer, String> result : java.util.Arrays.asList(full, differential)) {
            assertEquals(Status.SUCCESS, result.getStatus());
            assertEquals(Set.of(), result.getIncompatibleOldStates());
            assertEquals(identity, result.getSwitchStateMapping());
            assertTrue(result.getController().isPresent());
            assertSame(update.getAnalysis(), result.getController().get().getAnalysis());
            assertGraphEquals(expectedController, result.getController().get().copyLts());
            assertTrue(simulates(result.getController().get().copyLts(), old.copyLts()));
        }
        assertFalse(full.getPatch().isPresent());
        assertTrue(differential.getPatch().isPresent());
        ControllerPatch<Integer, String> patch = differential.getPatch().get();
        assertEquals(difference(expectedController.getStates(), old.getStates()), patch.getAddedStates());
        assertEquals(difference(expectedController.getActions(), old.getActions()), patch.getAddedActions());
        assertEquals(difference(transitions(expectedController), transitions(old.copyLts())),
                patch.getAddedTransitions());
        SafetyController<Integer, String> applied = patch.apply(old);
        assertSame(update.getAnalysis(), applied.getAnalysis());
        assertGraphEquals(expectedController, applied.copyLts());
        return differential;
    }

    private static void assertRejectedPlan(Plan<Integer, String> plan, Status expected) {
        assertEquals(expected, plan.getStatus());
        assertFalse(plan.isCompatible());
        assertEquals(Map.of(), plan.getSwitchStateMapping());
        Result<Integer, String> full = ControllerUpdateSynthesis.generateFull(plan);
        Result<Integer, String> differential = ControllerUpdateSynthesis.generateDifferential(plan);
        for (Result<Integer, String> result : java.util.Arrays.asList(full, differential)) {
            assertEquals(expected, result.getStatus());
            assertEquals(plan.getIncompatibleOldStates(), result.getIncompatibleOldStates());
            assertEquals(Map.of(), result.getSwitchStateMapping());
            assertFalse(result.getController().isPresent());
            assertFalse(result.getPatch().isPresent());
        }
    }

    private static void assertEmptyPatch(ControllerPatch<Integer, String> patch) {
        assertEquals(Set.of(), patch.getAddedStates());
        assertEquals(Set.of(), patch.getAddedActions());
        assertEquals(Set.of(), patch.getAddedTransitions());
    }

    /** Greatest simulation relation, with pairs ordered (new state, old state). */
    private static boolean simulates(LTS<Integer, String> newer, LTS<Integer, String> older) {
        Set<Pair<Integer, Integer>> relation = new HashSet<>();
        for (Integer newState : newer.getStates()) {
            for (Integer oldState : older.getStates()) {
                relation.add(Pair.create(newState, oldState));
            }
        }
        boolean changed;
        do {
            Set<Pair<Integer, Integer>> rejected = new HashSet<>();
            for (Pair<Integer, Integer> pair : relation) {
                for (Pair<String, Integer> oldEdge : older.getTransitions(pair.getSecond())) {
                    boolean matched = false;
                    for (Pair<String, Integer> newEdge : newer.getTransitions(pair.getFirst())) {
                        if (oldEdge.getFirst().equals(newEdge.getFirst())
                                && relation.contains(Pair.create(newEdge.getSecond(), oldEdge.getSecond()))) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        rejected.add(pair);
                        break;
                    }
                }
            }
            changed = relation.removeAll(rejected);
        } while (changed);
        return relation.contains(Pair.create(newer.getInitialState(), older.getInitialState()));
    }

    private static LTS<Integer, String> independentlyExtract(
            LTS<Integer, String> game, Set<Integer> winning) {
        assertTrue(winning.contains(game.getInitialState()));
        LTS<Integer, String> result = new LTSImpl<>(game.getInitialState());
        result.addActions(game.getActions());
        Queue<Integer> pending = new ArrayDeque<>();
        pending.add(game.getInitialState());
        Set<Integer> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Integer state = pending.remove();
            if (!visited.add(state)) {
                continue;
            }
            for (Pair<String, Integer> transition : game.getTransitions(state)) {
                if (winning.contains(transition.getSecond())) {
                    result.addState(transition.getSecond());
                    result.addTransition(state, transition.getFirst(), transition.getSecond());
                    pending.add(transition.getSecond());
                }
            }
        }
        return result;
    }

    private static Set<Integer> tinyWinningOracle(LTS<Integer, String> game) {
        Set<Integer> winning = new HashSet<>();
        for (int mask = 0; mask < 4; mask++) {
            Set<Integer> candidate = new HashSet<>();
            for (int state = 0; state < 2; state++) {
                if ((mask & (1 << state)) != 0) {
                    candidate.add(state);
                }
            }
            boolean closedAndNonblocking = true;
            for (Integer state : candidate) {
                boolean retainedMove = false;
                for (Pair<String, Integer> transition : game.getTransitions(state)) {
                    boolean inside = candidate.contains(transition.getSecond());
                    retainedMove |= inside;
                    if ("u".equals(transition.getFirst()) && !inside) {
                        closedAndNonblocking = false;
                    }
                }
                closedAndNonblocking &= retainedMove;
            }
            if (closedAndNonblocking) {
                winning.addAll(candidate);
            }
        }
        return winning;
    }

    private static void assertGraphEquals(LTS<Integer, String> expected, LTS<Integer, String> actual) {
        assertEquals(expected.getInitialState(), actual.getInitialState());
        assertEquals(expected.getStates(), actual.getStates());
        assertEquals(expected.getActions(), actual.getActions());
        assertEquals(transitions(expected), transitions(actual));
    }

    private static <T> Set<T> difference(Set<T> first, Set<T> second) {
        Set<T> result = new HashSet<>(first);
        result.removeAll(second);
        return result;
    }

    private static Set<Transition<Integer, String>> transitions(LTS<Integer, String> game) {
        Set<Transition<Integer, String>> result = new HashSet<>();
        for (Integer source : game.getStates()) {
            for (Pair<String, Integer> edge : game.getTransitions(source)) {
                result.add(new Transition<>(source, edge.getFirst(), edge.getSecond()));
            }
        }
        return result;
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Applicability is tied to the exact base or controller token.
        }
    }

    private static void immutable(Runnable operation) {
        try {
            operation.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Public snapshot and patch collections are read-only.
        }
    }

    private static Analysis<Integer, String> analyze(LTS<Integer, String> game,
                                                     Set<String> controls, Set<Integer> unsafe) {
        return FullSafetySynthesis.analyze(game, controls, unsafe);
    }

    private static SafetyController<Integer, String> snapshot(Analysis<Integer, String> analysis) {
        return FullSafetySynthesis.generateControllerSnapshot(analysis).get();
    }

    private static LTS<Integer, String> twoStateGame() {
        LTS<Integer, String> result = game(0, 1);
        edge(result, 0, "idle", 0);
        edge(result, 0, "start", 1);
        edge(result, 1, "finish", 0);
        return result;
    }

    private static LTS<Integer, String> twoStateGameWithReturn() {
        LTS<Integer, String> result = game(0, 1);
        edge(result, 0, "idle", 0);
        edge(result, 0, "start", 1);
        edge(result, 1, "return", 0);
        return result;
    }

    private static LTS<Integer, String> tinyGame(int encoding) {
        LTS<Integer, String> result = game(0, 1, 2);
        for (int state = 0; state < 2; state++) {
            for (String action : new String[] {"c", "u"}) {
                int target = encoding % 4;
                encoding /= 4;
                if (target != 3) {
                    edge(result, state, action, target);
                }
            }
        }
        return result;
    }

    private static LTS<Integer, String> game(Integer initial, Integer... others) {
        LTS<Integer, String> result = new LTSImpl<>(initial);
        for (Integer state : others) {
            result.addState(state);
        }
        return result;
    }

    private static void edge(LTS<Integer, String> game, int source, String action, int target) {
        game.addAction(action);
        game.addTransition(source, action, target);
    }
}
