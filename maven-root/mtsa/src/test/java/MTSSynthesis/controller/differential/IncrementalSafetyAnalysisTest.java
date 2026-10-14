package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis.Result;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class IncrementalSafetyAnalysisTest {
    @Test
    public void closingADeadlockCycleRecoversPredecessorsAndReusesUnrelatedComponent() {
        LTS<Integer, String> original = game(0, 1, 7);
        edge(original, 0, "forward", 1);
        edge(original, 7, "unrelated", 7);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of());
        assertEquals(Set.of(7), base.getWinningStates());

        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "back", 0).build());

        LTS<Integer, String> expected = game(0, 1, 7);
        edge(expected, 0, "forward", 1);
        edge(expected, 7, "unrelated", 7);
        edge(expected, 1, "back", 0);
        assertEquivalent(expected, Set.of(), Set.of(), result.getAnalysis());
        assertEquals(Set.of(0, 1, 7), result.getAnalysis().getWinningStates());
        assertEquals(Set.of(0, 1), result.getNewlyWinningStates());
        assertEquals(Set.of(), result.getNewlyLosingStates());
        assertEquals(Set.of(0, 1), result.getAffectedStates());
        assertEquals(1, result.getReusedStateCount());
        assertEquals(Set.of(), result.getAddedStates());
        assertEquals(1, result.getAddedTransitions().size());
    }

    @Test
    public void newlyClosedCycleWithUncontrollableUnsafeExitRemainsLosing() {
        LTS<Integer, String> original = game(0, 1, 2);
        edge(original, 0, "forward", 1);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of(2));
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(1, "back", 0)
                        .addTransition(1, "fail", 2).build());

        LTS<Integer, String> expected = game(0, 1, 2);
        edge(expected, 0, "forward", 1);
        edge(expected, 1, "back", 0);
        edge(expected, 1, "fail", 2);
        assertEquivalent(expected, Set.of(), Set.of(2), result.getAnalysis());
        assertEquals(Set.of(), result.getAnalysis().getWinningStates());
        assertEquals(Set.of(), result.getNewlyWinningStates());
        assertEquals(Set.of(), result.getNewlyLosingStates());
    }

    @Test
    public void oneBatchCanGainAndLoseStatesWhileChangingASafePredecessorsChoices() {
        Set<String> controls = Set.of("chooseGain", "chooseOld");
        Analysis<Integer, String> base = analyze(mixedGame(), controls, Set.of(3));
        assertEquals(Set.of(0, 2), base.getWinningStates());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(1, "gainLoop", 1)
                        .addTransition(2, "fail", 3).build());

        LTS<Integer, String> expected = mixedGame();
        edge(expected, 1, "gainLoop", 1);
        edge(expected, 2, "fail", 3);
        assertEquivalent(expected, controls, Set.of(3), result.getAnalysis());
        assertEquals(Set.of(0, 1), result.getAnalysis().getWinningStates());
        assertEquals(Set.of(1), result.getNewlyWinningStates());
        assertEquals(Set.of(2), result.getNewlyLosingStates());
        assertEquals(Set.of(0, 1, 2), result.getAffectedStates());
        assertEquals(1, result.getReusedStateCount());
        assertEquals(Set.of("0:chooseGain:1", "1:gainLoop:1"),
                edges(FullSafetySynthesis.generateController(result.getAnalysis()).get()));
    }

    @Test
    public void controllableOnlyEdgesToLosingBoundaryAreInitialLosingSeeds() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "first", 1);
        Set<String> controls = Set.of("first", "second");
        Analysis<Integer, String> base = analyze(original, controls, Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "second", 1).build());

        LTS<Integer, String> expected = game(0, 1);
        edge(expected, 0, "first", 1);
        edge(expected, 0, "second", 1);
        assertEquivalent(expected, controls, Set.of(), result.getAnalysis());
        assertEquals(Set.of(0), result.getAffectedStates());
        assertEquals(Set.of(), result.getAnalysis().getWinningStates());
        assertEquals(1, result.getReusedStateCount());
    }

    @Test
    public void aControllableEscapeToWinningBoundaryRecoversADeadlock() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 1, "safeLoop", 1);
        Analysis<Integer, String> base = analyze(original, Set.of("escape"), Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "escape", 1).build());

        LTS<Integer, String> expected = game(0, 1);
        edge(expected, 1, "safeLoop", 1);
        edge(expected, 0, "escape", 1);
        assertEquivalent(expected, Set.of("escape"), Set.of(), result.getAnalysis());
        assertEquals(Set.of(0), result.getNewlyWinningStates());
        assertEquals(Set.of(0), result.getAffectedStates());
        assertEquals(1, result.getReusedStateCount());
    }

    @Test
    public void multipleAddedStatesCanFormANewReachableRecoveryCycle() {
        Set<String> controls = Set.of("idle", "start");
        Analysis<Integer, String> base = analyze(twoStateGame(), controls, Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
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
        assertEquivalent(expected, controls, Set.of(), result.getAnalysis());
        assertEquals(Set.of(0, 1, 2, 3), result.getAnalysis().getWinningStates());
        assertEquals(Set.of(2, 3), result.getAddedStates());
        assertEquals(4, result.getAddedTransitions().size());
        assertEquals(Set.of(), result.getNewlyWinningStates());
        assertEquals(Set.of(), result.getNewlyLosingStates());
        assertEquals(Set.of(0, 1, 2, 3), result.getAffectedStates());
    }

    @Test
    public void anAddedOrdinaryDeadlockLosesItsUncontrollablePredecessor() {
        Set<String> controls = Set.of("idle", "start");
        Analysis<Integer, String> base = analyze(twoStateGame(), controls, Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(2)
                        .addTransition(1, "divert", 2).build());

        LTS<Integer, String> expected = twoStateGame();
        expected.addState(2);
        edge(expected, 1, "divert", 2);
        assertEquivalent(expected, controls, Set.of(), result.getAnalysis());
        assertEquals(Set.of(0), result.getAnalysis().getWinningStates());
        assertEquals(Set.of(1), result.getNewlyLosingStates());
        assertEquals(Set.of(2), result.getAddedStates());
        assertEquals(Set.of(1, 2), result.getAnalysis().getLosingStates());
    }

    @Test
    public void aNewUnsafeStateIsAddedAndCanMakeAnOldStateLosing() {
        LTS<Integer, String> original = game(0);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addUnsafeState(1)
                        .addTransition(0, "fail", 1).build());

        LTS<Integer, String> expected = game(0, 1);
        edge(expected, 0, "idle", 0);
        edge(expected, 0, "fail", 1);
        assertEquivalent(expected, Set.of(), Set.of(1), result.getAnalysis());
        assertEquals(Set.of(1), result.getAnalysis().getUnsafeStates());
        assertEquals(Set.of(1), result.getAddedStates());
        assertEquals(Set.of(0), result.getNewlyLosingStates());
        assertFalse(result.getAnalysis().isRealizable());
    }

    @Test
    public void unreachableNewStatesAreAnalyzedWithoutReanalyzingTheOldComponent() {
        LTS<Integer, String> original = game(0);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of());
        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(5).addState(6)
                        .addTransition(5, "out", 6)
                        .addTransition(6, "back", 5).build());

        LTS<Integer, String> expected = game(0, 5, 6);
        edge(expected, 0, "idle", 0);
        edge(expected, 5, "out", 6);
        edge(expected, 6, "back", 5);
        assertEquivalent(expected, Set.of(), Set.of(), result.getAnalysis());
        assertEquals(Set.of(5, 6), result.getAffectedStates());
        assertEquals(1, result.getReusedStateCount());
        assertEquals(Set.of(), result.getNewlyWinningStates());
        assertEquals(Set.of(0),
                FullSafetySynthesis.generateController(result.getAnalysis()).get().getStates());
    }

    @Test
    public void emptyAndDuplicateOnlyUpdatesHaveNoEffectiveDeltaButAdvanceRevision() {
        LTS<Integer, String> original = game(0);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> base = analyze(original, Set.of("idle"), Set.of());
        assertEquals(0L, base.getRevision());
        Result<Integer, String> empty = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).build());
        assertNoEffectiveChange(base, empty);
        assertEquals(1L, empty.getAnalysis().getRevision());

        Analysis<Integer, String> afterEmpty = empty.getAnalysis();
        Result<Integer, String> duplicate = IncrementalSafetyAnalysis.update(afterEmpty,
                SafetyGameDelta.forAnalysis(afterEmpty)
                        .addTransition(0, "idle", 0)
                        .addTransition(0, "idle", 0).build());
        assertNoEffectiveChange(afterEmpty, duplicate);
        assertEquals(2L, duplicate.getAnalysis().getRevision());
        assertEquals(0L, base.getRevision());
    }

    @Test
    public void updatingDoesNotMutateTheBaseAndGameCopiesAreIndependent() {
        LTS<Integer, String> original = twoStateGame();
        original.addState(2);
        Set<String> controls = new HashSet<>(Set.of("idle", "start"));
        Set<Integer> unsafe = new HashSet<>(Set.of(2));
        Analysis<Integer, String> base = analyze(original, controls, unsafe);
        Set<String> oldEdges = Set.of("0:idle:0", "0:start:1", "1:finish:0");
        controls.clear();
        unsafe.clear();
        edge(original, 1, "callerFail", 2);
        LTS<Integer, String> copy = base.copyGame();
        copy.removeTransition(0, "idle", 0);
        edge(copy, 0, "copyFail", 2);

        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "retry", 1).build());
        assertEquals(oldEdges, edges(base.copyGame()));
        assertEquals(Set.of(0, 1), base.getWinningStates());
        assertEquals(Set.of(2), base.getLosingStates());
        assertEquals(Set.of("idle", "start"), base.getControllableActions());
        assertEquals(Set.of(2), base.getUnsafeStates());
        assertEquals(0L, base.getRevision());
        assertEquals(1L, result.getAnalysis().getRevision());

        LTS<Integer, String> expected = twoStateGame();
        expected.addState(2);
        edge(expected, 1, "retry", 1);
        assertEquivalent(expected, Set.of("idle", "start"), Set.of(2), result.getAnalysis());
        LTS<Integer, String> updatedCopy = result.getAnalysis().copyGame();
        updatedCopy.removeTransition(1, "retry", 1);
        assertEquals(edges(expected), edges(result.getAnalysis().copyGame()));
    }

    @Test
    public void invalidBatchDoesNotPartiallyModifyTheBase() {
        LTS<Integer, String> original = twoStateGame();
        Analysis<Integer, String> base = analyze(original, Set.of("idle", "start"), Set.of());
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(1, "retry", 1)
                        .addTransition(0, "idle", 1).build()));
        assertEquivalent(twoStateGame(), Set.of("idle", "start"), Set.of(), base);
        assertEquals(0L, base.getRevision());

        Result<Integer, String> valid = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "retry", 1).build());
        LTS<Integer, String> expected = twoStateGame();
        edge(expected, 1, "retry", 1);
        assertEquivalent(expected, Set.of("idle", "start"), Set.of(), valid.getAnalysis());
    }

    @Test
    public void rejectsUnknownEndpointsAndNondeterministicOutcomes() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(99, "new", 0).build()));
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "new", 99).build()));
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "start", 0).build()));
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(0, "new", 0)
                        .addTransition(0, "new", 1).build()));
        assertEquivalent(twoStateGame(), Set.of("idle", "start"), Set.of(), base);
    }

    @Test
    public void rejectsOutgoingEdgesFromOldUnsafeStatesAndChangingAnOldSafeStateToUnsafe() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of(1));
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "escape", 0).build()));
        rejects(() -> IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addUnsafeState(0).build()));
        assertEquivalent(original, Set.of(), Set.of(1), base);
    }

    @Test
    public void rejectsADeltaBuiltForAnotherAnalysisOrForAnEarlierRevision() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyGameDelta<Integer, String> delta = SafetyGameDelta.forAnalysis(base)
                .addTransition(1, "retry", 1).build();
        Analysis<Integer, String> equivalentButDifferent =
                analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        rejects(() -> IncrementalSafetyAnalysis.update(equivalentButDifferent, delta));
        Analysis<Integer, String> later = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).build()).getAnalysis();
        rejects(() -> IncrementalSafetyAnalysis.update(later, delta));

        LTS<Integer, String> expected = twoStateGame();
        edge(expected, 1, "retry", 1);
        assertEquivalent(expected, Set.of("idle", "start"), Set.of(),
                IncrementalSafetyAnalysis.update(base, delta).getAnalysis());
        assertEquals(0L, base.getRevision());
        assertEquals(1L, later.getRevision());
    }

    @Test
    public void batchAndBothSequentialOrdersReachTheSameWinningRegionAndController() {
        Set<String> controls = Set.of("chooseGain", "chooseOld");
        Analysis<Integer, String> base = analyze(mixedGame(), controls, Set.of(3));
        Analysis<Integer, String> batch = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(1, "gainLoop", 1)
                        .addTransition(2, "fail", 3).build()).getAnalysis();
        Analysis<Integer, String> gainFirst = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "gainLoop", 1).build()).getAnalysis();
        Analysis<Integer, String> gainThenLoss = IncrementalSafetyAnalysis.update(gainFirst,
                SafetyGameDelta.forAnalysis(gainFirst).addTransition(2, "fail", 3).build()).getAnalysis();
        Analysis<Integer, String> lossFirst = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(2, "fail", 3).build()).getAnalysis();
        assertFalse(lossFirst.isRealizable());
        Analysis<Integer, String> lossThenGain = IncrementalSafetyAnalysis.update(lossFirst,
                SafetyGameDelta.forAnalysis(lossFirst).addTransition(1, "gainLoop", 1).build()).getAnalysis();

        LTS<Integer, String> expected = mixedGame();
        edge(expected, 1, "gainLoop", 1);
        edge(expected, 2, "fail", 3);
        assertEquivalent(expected, controls, Set.of(3), batch);
        assertEquivalent(expected, controls, Set.of(3), gainThenLoss);
        assertEquivalent(expected, controls, Set.of(3), lossThenGain);
        assertEquals(1L, batch.getRevision());
        assertEquals(2L, gainThenLoss.getRevision());
        assertEquals(2L, lossThenGain.getRevision());
    }

    @Test
    public void allMissingSlotsIn256TinyGamesMatchFullAnalysisAndClosedSubsetOracle() {
        int updates = 0;
        for (int encoding = 0; encoding < 256; encoding++) {
            LTS<Integer, String> original = tinyGame(encoding);
            Analysis<Integer, String> base = analyze(original, Set.of("c"), Set.of(2));
            assertEquals("Base encoding " + encoding, tinyOracle(original), base.getWinningStates());
            for (int source = 0; source < 2; source++) {
                for (String action : new String[] {"c", "u"}) {
                    if (!original.getTransitions(source).getImage(action).isEmpty()) {
                        continue;
                    }
                    for (int target = 0; target < 3; target++) {
                        Result<Integer, String> result = IncrementalSafetyAnalysis.update(base,
                                SafetyGameDelta.forAnalysis(base)
                                        .addTransition(source, action, target).build());
                        // Reconstruct independently from the encoding, not from
                        // the implementation's snapshot or updated graph.
                        LTS<Integer, String> expected = tinyGame(encoding);
                        edge(expected, source, action, target);
                        String context = "encoding=" + encoding + ", add="
                                + source + ":" + action + ":" + target;
                        Set<Integer> oracle = tinyOracle(expected);
                        assertEquals(context, oracle, result.getAnalysis().getWinningStates());
                        assertEquivalent(expected, Set.of("c"), Set.of(2), result.getAnalysis());
                        Set<Integer> gains = new HashSet<>(oracle);
                        gains.retainAll(base.getLosingStates());
                        Set<Integer> losses = new HashSet<>(base.getWinningStates());
                        losses.removeAll(oracle);
                        assertEquals(context, gains, result.getNewlyWinningStates());
                        assertEquals(context, losses, result.getNewlyLosingStates());
                        assertEquals(context, 1, result.getAddedTransitions().size());
                        assertEquals(context, Set.of(), result.getAddedStates());
                        assertEquals(context, edges(original), edges(base.copyGame()));
                        updates++;
                    }
                }
            }
        }
        assertEquals(768, updates);
    }

    private static void assertNoEffectiveChange(Analysis<Integer, String> base,
                                                Result<Integer, String> result) {
        assertEquals(base.getWinningStates(), result.getAnalysis().getWinningStates());
        assertEquals(base.getLosingStates(), result.getAnalysis().getLosingStates());
        assertEquals(edges(base.copyGame()), edges(result.getAnalysis().copyGame()));
        assertEquals(Set.of(), result.getAffectedStates());
        assertEquals(Set.of(), result.getAddedStates());
        assertEquals(Set.of(), result.getAddedTransitions());
        assertEquals(Set.of(), result.getNewlyWinningStates());
        assertEquals(Set.of(), result.getNewlyLosingStates());
        assertEquals(base.copyGame().getStates().size(), result.getReusedStateCount());
    }

    private static void assertEquivalent(LTS<Integer, String> expectedGame,
                                         Set<String> controls, Set<Integer> unsafe,
                                         Analysis<Integer, String> actual) {
        Analysis<Integer, String> full = analyze(expectedGame, controls, unsafe);
        assertEquals(full.getWinningStates(), actual.getWinningStates());
        assertEquals(full.getLosingStates(), actual.getLosingStates());
        assertEquals(full.isRealizable(), actual.isRealizable());
        assertEquals(expectedGame.getInitialState(), actual.getInitialState());
        assertEquals(expectedGame.getStates(), actual.copyGame().getStates());
        assertEquals(expectedGame.getActions(), actual.copyGame().getActions());
        assertEquals(edges(expectedGame), edges(actual.copyGame()));
        assertEquals(controls, actual.getControllableActions());
        assertEquals(unsafe, actual.getUnsafeStates());
        if (full.isRealizable()) {
            LTS<Integer, String> fullController = FullSafetySynthesis.generateController(full).get();
            LTS<Integer, String> actualController = FullSafetySynthesis.generateController(actual).get();
            assertEquals(fullController.getStates(), actualController.getStates());
            assertEquals(edges(fullController), edges(actualController));
        } else {
            assertFalse(FullSafetySynthesis.generateController(actual).isPresent());
        }
    }

    private static Analysis<Integer, String> analyze(LTS<Integer, String> game,
                                                     Set<String> controls, Set<Integer> unsafe) {
        return FullSafetySynthesis.analyze(game, controls, unsafe);
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Either delta construction or update validation may reject it.
        }
    }

    private static LTS<Integer, String> twoStateGame() {
        LTS<Integer, String> result = game(0, 1);
        edge(result, 0, "idle", 0);
        edge(result, 0, "start", 1);
        edge(result, 1, "finish", 0);
        return result;
    }

    private static LTS<Integer, String> mixedGame() {
        LTS<Integer, String> result = game(0, 1, 2, 3);
        edge(result, 0, "chooseGain", 1);
        edge(result, 0, "chooseOld", 2);
        edge(result, 2, "oldLoop", 2);
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

    private static Set<Integer> tinyOracle(LTS<Integer, String> game) {
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

    private static Set<String> edges(LTS<Integer, String> game) {
        Set<String> result = new HashSet<>();
        for (Integer source : game.getStates()) {
            for (Pair<String, Integer> transition : game.getTransitions(source)) {
                result.add(source + ":" + transition.getFirst() + ":" + transition.getSecond());
            }
        }
        return result;
    }
}
