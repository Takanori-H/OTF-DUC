package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** The reference path constructs an independent LTS and runs the full solver. */
public class CachedLosingSafetyAnalysisTest {
    @Test
    public void safeNewCycleDoesNotReanalyzeTheOldPredecessorClosure() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("start", 1), 1, Map.of("continue", 0),
                8, Map.of("other", 9), 9, Map.of("other", 8)), Set.of("c"), Set.of());
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addState(3)
                        .addTransition(1, "fault", 2)
                        .addTransition(2, "c", 3)
                        .addTransition(3, "proceed", 2));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
        assertEquals(Set.of(0, 1, 2, 3, 8, 9), outcome.getResult().getAnalysis().getWinningStates());
        assertTrue(outcome.getResult().getNewlyLosingStates().isEmpty());
        assertTrue(outcome.getResult().getNewlyWinningStates().isEmpty());
        // 0 can reach the changed source, but no new loss needs propagation to 0.
        assertFalse(outcome.getResult().getAffectedStates().contains(0));
        assertFalse(outcome.getResult().getAffectedStates().contains(8));
        assertFalse(outcome.getResult().getAffectedStates().contains(9));
        assertTrue(IncrementalSafetyAnalysis.analyze(update).getAffectedStates().contains(0));
        assertMatchesFull(base, update, outcome);
        ControllerUpdateSynthesis.Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(
                FullSafetySynthesis.generateControllerSnapshot(base).get(), outcome.getResult());
        assertEquals(Set.of(2, 3), ControllerUpdateSynthesis.generateDifferential(plan)
                .getPatch().get().getAddedStates());
    }

    @Test
    public void newLossPropagatesThroughDuplicateTargetLabelsWithoutLosingASafeAlternative() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("c0", 1, "c1", 2),
                1, Map.of("u", 0),
                2, Map.of("u", 3), 3, Map.of("u", 2),
                4, Map.of("c0", 1, "c1", 1),
                5, Map.of("u", 1, "c0", 1),
                -1, Map.of()), Set.of("c0", "c1"), Set.of(-1));
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "fault", -1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
        assertEquals(Set.of(0, 2, 3), outcome.getResult().getAnalysis().getWinningStates());
        assertEquals(Set.of(-1, 1, 4, 5), outcome.getResult().getAnalysis().getLosingStates());
        assertEquals(Set.of(1, 4, 5), outcome.getResult().getNewlyLosingStates());
        assertEquals(Set.of(-1), base.getLosingStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void uncontrollableLabelToLostTargetWinsOverOtherSafeSuccessors() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("c0", 1, "u0", 1, "c1", 2, "u1", 2),
                1, Map.of("u0", 0),
                2, Map.of("u0", 3), 3, Map.of("u0", 2),
                4, Map.of("c0", 1, "c1", 2), -1, Map.of()),
                Set.of("c0", "c1"), Set.of(-1));
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "fault", -1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
        assertEquals(Set.of(2, 3, 4), outcome.getResult().getAnalysis().getWinningStates());
        assertEquals(Set.of(0, 1), outcome.getResult().getNewlyLosingStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void newlyDeclaredDeadlockAndUnsafeStatesSeedLossIncludingANewCycle() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("u", 1), 1, Map.of("u", 0)), Set.of("c0", "c1"), Set.of());
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base)
                        .addState(2).addState(3).addState(4).addUnsafeState(-1)
                        .addTransition(1, "fault", 2)
                        .addTransition(2, "c0", 3)
                        .addTransition(2, "c1", 3)
                        .addTransition(2, "u", 4)
                        .addTransition(4, "u", 2)
                        .addTransition(4, "error", -1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
        assertEquals(Set.of(-1, 0, 1, 2, 3, 4), outcome.getResult().getAnalysis().getLosingStates());
        assertTrue(outcome.getResult().getAnalysis().getWinningStates().isEmpty());
        assertEquals(Set.of(0, 1), outcome.getResult().getNewlyLosingStates());
        assertEquals(Set.of(-1), outcome.getResult().getAnalysis().getUnsafeStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void oldLosingSourceWithAnExistingUncontrollableLossCannotBeRestored() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("u", 1), 1, Map.of("u", 0),
                4, Map.of("u", -1), -1, Map.of()), Set.of(), Set.of(-1));
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(4, "escape", 0));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
        assertEquals(Set.of(-1, 4), outcome.getResult().getAnalysis().getLosingStates());
        assertTrue(outcome.getResult().getNewlyWinningStates().isEmpty());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void repeatedUpdatesKeepEveryEarlierGameAndClassificationUnchanged() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("u", 1), 1, Map.of("u", 0), -1, Map.of()), Set.of(), Set.of(-1));
        SafetyGameUpdate<Integer, String> first = apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addState(3)
                        .addTransition(1, "fault", 2)
                        .addTransition(2, "u", 3).addTransition(3, "u", 2));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> firstOutcome = cached(first);
        Analysis<Integer, String> firstAnalysis = firstOutcome.getResult().getAnalysis();
        Map<Integer, Map<String, Integer>> firstRows = copyRows(firstAnalysis);
        Set<Integer> firstWinning = new HashSet<>(firstAnalysis.getWinningStates());
        SafetyGameUpdate<Integer, String> second = apply(firstAnalysis,
                SafetyGameDelta.forAnalysis(firstAnalysis).addTransition(3, "fault2", -1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> secondOutcome = cached(second);
        assertMatchesFull(base, first, firstOutcome);
        assertMatchesFull(firstAnalysis, second, secondOutcome);
        assertEquals(Set.of(-1, 0, 1, 2, 3), secondOutcome.getResult().getAnalysis().getLosingStates());
        assertEquals(firstRows, copyRows(firstAnalysis));
        assertEquals(firstWinning, firstAnalysis.getWinningStates());
        assertEquals(Map.of("u", 0), base.getTransitions(1));
        assertEquals(Set.of(0, 1), base.getWinningStates());
        assertEquals(0L, base.getRevision());
        assertEquals(1L, firstAnalysis.getRevision());
        assertEquals(2L, secondOutcome.getResult().getAnalysis().getRevision());
        assertFalse(firstAnalysis.getActions().contains("fault2"));
        assertFalse(base.getStates().contains(2));
    }

    @Test
    public void emptyAndDuplicateControllableOnlyUpdatesReuseTheEntireBase() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("c0", 1), 1, Map.of("c1", 0), -1, Map.of()),
                Set.of("c0", "c1"), Set.of(-1));
        for (SafetyGameDelta<Integer, String> delta : java.util.Arrays.asList(
                SafetyGameDelta.forAnalysis(base).build(),
                SafetyGameDelta.forAnalysis(base).addTransition(0, "c0", 1).build())) {
            SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base, delta);
            CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = cached(update);
            assertTrue(outcome.getResult().getAffectedStates().isEmpty());
            assertTrue(outcome.getResult().getAddedTransitions().isEmpty());
            assertEquals(base.getStates().size(), outcome.getResult().getReusedStateCount());
            assertEquals(1L, outcome.getResult().getAnalysis().getRevision());
            assertMatchesFull(base, update, outcome);
        }
    }

    @Test
    public void firstUncontrollableEscapeFromAControllableOnlyLossUsesFallback() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("c", -1), 1, Map.of("u", 2),
                2, Map.of("u", 1), -1, Map.of()), Set.of("c"), Set.of(-1));
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "escape", 1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = fallback(update);
        assertEquals(Set.of(0), outcome.getResult().getNewlyWinningStates());
        assertEquals(Set.of(0, 1, 2), outcome.getResult().getAnalysis().getWinningStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void anUncontrollableEdgeRepairingAnOldDeadlockUsesFallback() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of(), 1, Map.of("u", 0)), Set.of(), Set.of());
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "continue", 1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = fallback(update);
        assertEquals(Set.of(0, 1), outcome.getResult().getNewlyWinningStates());
        assertTrue(outcome.getResult().getAnalysis().getLosingStates().isEmpty());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void anAddedControllableAlternativeUsesFallbackAndCanRestoreOldLoss() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of("c0", -1), 1, Map.of("u", 2),
                2, Map.of("u", 1), -1, Map.of()), Set.of("c0", "c1"), Set.of(-1));
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "c1", 1));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = fallback(update);
        assertEquals(Set.of(0), outcome.getResult().getNewlyWinningStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void fallbackGuardExaminesTheBaseRatherThanUncontrollableEdgesInTheSameBatch() {
        Analysis<Integer, String> base = analyze(Map.of(
                0, Map.of(), 1, Map.of("u", 2), 2, Map.of("u", 1)), Set.of(), Set.of());
        SafetyGameUpdate<Integer, String> update = apply(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(0, "first", 1).addTransition(0, "second", 2));
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = fallback(update);
        assertEquals(Set.of(0), outcome.getResult().getNewlyWinningStates());
        assertMatchesFull(base, update, outcome);
    }

    @Test
    public void seededBatchesWithNewStatesMatchIndependentFullAnalysisAndControllerGeneration() {
        Random random = new Random(2026100607L);
        Set<String> controls = Set.of("c0", "c1", "patchC");
        int cachedCount = 0;
        int fallbackCount = 0;
        int compatibleCount = 0;
        for (int trial = 0; trial < 384; trial++) {
            int oldSize = 3 + random.nextInt(9);
            Map<Integer, Map<String, Integer>> rows = new HashMap<>();
            Set<Integer> unsafe = new HashSet<>();
            for (int state = 0; state < oldSize; state++) {
                Map<String, Integer> row = new HashMap<>();
                rows.put(state, row);
                if (state >= 2 && random.nextInt(6) == 0) {
                    unsafe.add(state);
                } else {
                    for (String label : new String[] {"c0", "c1", "u0", "u1"}) {
                        if (random.nextBoolean()) {
                            row.put(label, differentTarget(random, oldSize, state));
                        }
                    }
                }
            }
            // Keep at least one old safe cycle; controllable branches still vary.
            rows.get(0).keySet().removeIf(label -> label.startsWith("u"));
            rows.get(1).keySet().removeIf(label -> label.startsWith("u"));
            rows.get(0).put("u0", 1);
            rows.get(1).put("u0", 0);
            Analysis<Integer, String> base = analyze(rows, controls, unsafe);
            Map<Integer, Map<String, Integer>> savedRows = copyRows(base);
            SafetyGameDelta.Builder<Integer, String> delta = SafetyGameDelta.forAnalysis(base);
            int newSize = 2 + random.nextInt(3);
            int total = oldSize + newSize;
            for (int state = oldSize; state < total; state++) {
                if (trial % 2 != 0 && random.nextInt(5) == 0) {
                    delta.addUnsafeState(state);
                    continue;
                }
                delta.addState(state);
                if (trial % 2 == 0) {
                    // A genuinely new safe cycle, separate from the old state IDs.
                    delta.addTransition(state, "c0", oldSize + (state - oldSize + 1) % newSize);
                    if (random.nextBoolean()) {
                        delta.addTransition(state, "c1", differentTarget(random, total, state));
                    }
                } else {
                    for (String label : new String[] {"c0", "c1", "u0", "u1"}) {
                        if (random.nextBoolean()) {
                            delta.addTransition(state, label, differentTarget(random, total, state));
                        }
                    }
                }
            }
            if (trial % 3 == 2) {
                delta.addTransition(0, "patchC", oldSize);
            } else {
                delta.addTransition(0, "fault0", oldSize);
                delta.addTransition(1, "fault1", oldSize + 1);
            }
            SafetyGameUpdate<Integer, String> update = apply(base, delta);
            CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome;
            if (trial % 3 == 2) {
                outcome = fallback(update);
                fallbackCount++;
            } else {
                outcome = cached(update);
                cachedCount++;
                assertTrue("cached old loss must persist: " + trial,
                        outcome.getResult().getAnalysis().getLosingStates().containsAll(base.getLosingStates()));
            }
            compatibleCount += assertMatchesFull(base, update, outcome) ? 1 : 0;
            assertEquals("base rows changed: " + trial, savedRows, copyRows(base));
            assertEquals(0L, base.getRevision());
            for (SafetyGameDelta.Transition<Integer, String> edge : update.getAddedTransitions()) {
                assertNotEquals("No added self-loop in trial " + trial, edge.getSource(), edge.getTarget());
            }
        }
        assertEquals(256, cachedCount);
        assertEquals(128, fallbackCount);
        assertTrue("The comparison must exercise successful full/differential C generation", compatibleCount >= 128);
    }

    private static CachedLosingSafetyAnalysis.Outcome<Integer, String> cached(
            SafetyGameUpdate<Integer, String> update) {
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = CachedLosingSafetyAnalysis.analyze(update);
        assertEquals("CACHED_LOSING_PROPAGATION", outcome.getBackend().name());
        assertNull(outcome.getFallbackReason());
        return outcome;
    }

    private static CachedLosingSafetyAnalysis.Outcome<Integer, String> fallback(
            SafetyGameUpdate<Integer, String> update) {
        CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome = CachedLosingSafetyAnalysis.analyze(update);
        assertEquals("PREDECESSOR_CLOSURE", outcome.getBackend().name());
        assertNotNull(outcome.getFallbackReason());
        IncrementalSafetyAnalysis.Result<Integer, String> reference = IncrementalSafetyAnalysis.analyze(update);
        assertEquals(reference.getAffectedStates(), outcome.getResult().getAffectedStates());
        assertEquals(reference.getReusedStateCount(), outcome.getResult().getReusedStateCount());
        return outcome;
    }

    private static boolean assertMatchesFull(Analysis<Integer, String> base,
            SafetyGameUpdate<Integer, String> update,
            CachedLosingSafetyAnalysis.Outcome<Integer, String> outcome) {
        IncrementalSafetyAnalysis.Result<Integer, String> actual = outcome.getResult();
        // This entry point uses the separate LTS/SafeGame full solver, not the
        // snapshot worklist or either incremental implementation.
        IncrementalSafetyAnalysis.Result<Integer, String> expected = FullSafetySynthesis.analyzeUpdate(update);
        assertEquals(expected.getAnalysis().getWinningStates(), actual.getAnalysis().getWinningStates());
        assertEquals(expected.getAnalysis().getLosingStates(), actual.getAnalysis().getLosingStates());
        assertEquals(expected.getNewlyWinningStates(), actual.getNewlyWinningStates());
        assertEquals(expected.getNewlyLosingStates(), actual.getNewlyLosingStates());
        assertEquals(update.getAddedStates(), actual.getAddedStates());
        assertEquals(update.getAddedTransitions(), actual.getAddedTransitions());
        assertEquals(update.getRevision(), actual.getAnalysis().getRevision());
        if (!base.isRealizable()) {
            return false;
        }
        SafetyController<Integer, String> previous = FullSafetySynthesis.generateControllerSnapshot(base).get();
        ControllerUpdateSynthesis.Plan<Integer, String> fullPlan = ControllerUpdateSynthesis.prepare(previous, expected);
        ControllerUpdateSynthesis.Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(previous, actual);
        assertEquals(fullPlan.getStatus(), plan.getStatus());
        assertEquals(fullPlan.getIncompatibleOldStates(), plan.getIncompatibleOldStates());
        if (!plan.isCompatible()) {
            assertFalse(ControllerUpdateSynthesis.generateFull(plan).getController().isPresent());
            assertFalse(ControllerUpdateSynthesis.generateDifferential(plan).getController().isPresent());
            return false;
        }
        SafetyController<Integer, String> reference = ControllerUpdateSynthesis.generateFull(fullPlan).getController().get();
        assertControllerEquals(reference, ControllerUpdateSynthesis.generateFull(plan).getController().get());
        assertControllerEquals(reference, ControllerUpdateSynthesis.generateDifferential(plan).getController().get());
        return true;
    }

    private static int differentTarget(Random random, int size, int source) {
        int target = random.nextInt(size - 1);
        return target >= source ? target + 1 : target;
    }

    private static Analysis<Integer, String> analyze(Map<Integer, Map<String, Integer>> rows,
            Set<String> controls, Set<Integer> unsafe) {
        LTS<Integer, String> game = new LTSImpl<>(0);
        game.addStates(rows.keySet());
        rows.values().forEach(row -> game.addActions(row.keySet()));
        rows.forEach((source, row) -> row.forEach((action, target) -> game.addTransition(source, action, target)));
        return FullSafetySynthesis.analyze(game, controls, unsafe);
    }

    private static SafetyGameUpdate<Integer, String> apply(Analysis<Integer, String> base,
            SafetyGameDelta.Builder<Integer, String> delta) {
        return SafetyGameUpdate.apply(base, delta.build());
    }

    private static Map<Integer, Map<String, Integer>> copyRows(Analysis<Integer, String> analysis) {
        Map<Integer, Map<String, Integer>> copy = new HashMap<>();
        for (Integer state : analysis.getStates()) {
            copy.put(state, new HashMap<>(analysis.getTransitions(state)));
        }
        return copy;
    }

    private static void assertControllerEquals(SafetyController<Integer, String> expected,
            SafetyController<Integer, String> actual) {
        assertEquals(expected.getInitialState(), actual.getInitialState());
        assertEquals(expected.getStates(), actual.getStates());
        assertEquals(expected.getActions(), actual.getActions());
        for (Integer state : expected.getStates()) {
            assertEquals(expected.getTransitions(state), actual.getTransitions(state));
        }
    }
}
