package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis.Result;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Change metadata must identify controller entry rows without rescanning new edges. */
public class ChangedOldSourcesTest {
    @Test
    public void mixedBatchKeepsDistinctOldSourcesIncludingThoseOutsideTheController() {
        Analysis<Integer, String> base = base();
        SafetyController<Integer, String> previous = FullSafetySynthesis
                .generateControllerSnapshot(base).orElseThrow(AssertionError::new);
        assertEquals(Set.of(0, 1), previous.getStates());

        SafetyGameUpdate<Integer, String> update = mixedUpdate(base);
        // Both new edges at 0 produce one source; 4 belongs to the old game,
        // although it does not belong to the old reachable controller.
        assertEquals(Set.of(0, 4), update.getChangedOldSources());
        assertEquals(Set.of(2, 3), update.getAddedStates());
        assertEquals(5, update.getAddedTransitions().size());
        assertEquals(Set.of(0, 2, 3, 4), update.seeds());
        assertEquals(CachedLosingSafetyAnalysis.Backend.CACHED_LOSING_PROPAGATION,
                CachedLosingSafetyAnalysis.analyze(update).getBackend());

        for (Result<Integer, String> result : analyses(update)) {
            assertEquals(Set.of(0, 4), result.getChangedOldSources());
            assertEquals(update.getAddedTransitions(), result.getAddedTransitions());
            assertEquals(Set.of(2, 3), result.getAddedStates());
            assertEquals(Set.of(0, 1, 2, 3, 4, 5), result.getAnalysis().getWinningStates());
            assertEquals(Set.of(9), result.getAnalysis().getLosingStates());
            assertTrue(result.hasBase(base));
            assertFalse(result.hasBase(base()));
        }
        // Computing classifications and metadata did not modify the old game.
        assertEquals(Set.of(0, 1, 4, 5, 9), base.getStates());
        assertEquals(Set.of("go"), base.getTransitions(0).keySet());
        assertEquals(Set.of("remote"), base.getTransitions(4).keySet());
    }

    @Test
    public void emptyAndDuplicateOnlyUpdatesHaveNoChangedOldSources() {
        Analysis<Integer, String> base = base();
        SafetyGameUpdate<Integer, String> empty = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).build());
        SafetyGameUpdate<Integer, String> duplicate = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(0, "go", 1)
                        .addTransition(4, "remote", 5).build());
        for (SafetyGameUpdate<Integer, String> update : List.of(empty, duplicate)) {
            assertTrue(update.getChangedOldSources().isEmpty());
            assertTrue(update.getAddedTransitions().isEmpty());
            assertTrue(update.seeds().isEmpty());
            assertEquals(1L, update.getRevision());
            for (Result<Integer, String> result : analyses(update)) {
                assertTrue(result.getChangedOldSources().isEmpty());
                assertEquals(base.getWinningStates(), result.getAnalysis().getWinningStates());
                assertEquals(base.getLosingStates(), result.getAnalysis().getLosingStates());
            }
        }
    }

    @Test
    public void newStateSourcesAreNotOldSourcesEvenWhenTheyHaveEffectiveEdges() {
        Analysis<Integer, String> base = base();
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addState(3)
                        .addTransition(2, "forward", 3)
                        .addTransition(3, "return", 2).build());
        assertEquals(2, update.getAddedTransitions().size());
        assertEquals(Set.of(2, 3), update.seeds());
        assertTrue(update.getChangedOldSources().isEmpty());
        for (Result<Integer, String> result : analyses(update)) {
            assertTrue(result.getChangedOldSources().isEmpty());
            assertEquals(Set.of(0, 1, 2, 3, 4, 5), result.getAnalysis().getWinningStates());
            assertEquals(Set.of(0, 1), FullSafetySynthesis.generateControllerSnapshot(
                    result.getAnalysis()).orElseThrow(AssertionError::new).getStates());
        }
    }

    @Test
    public void newStateInOneVersionCanBeAnOldSourceInTheNextWithoutCarryingEarlierSources() {
        Analysis<Integer, String> base = base();
        SafetyGameUpdate<Integer, String> firstUpdate = mixedUpdate(base);
        Result<Integer, String> first = IncrementalSafetyAnalysis.analyze(firstUpdate);
        SafetyGameUpdate<Integer, String> secondUpdate = SafetyGameUpdate.apply(first.getAnalysis(),
                SafetyGameDelta.forAnalysis(first.getAnalysis()).addState(6)
                        .addTransition(2, "next", 6)
                        .addTransition(6, "rejoin", 1)
                        .addTransition(0, "divert", 2).build());

        assertEquals(Set.of(2), secondUpdate.getChangedOldSources());
        assertEquals(Set.of(2, 6), secondUpdate.seeds());
        assertEquals(2, secondUpdate.getAddedTransitions().size());
        for (Result<Integer, String> result : analyses(secondUpdate)) {
            assertEquals(Set.of(2), result.getChangedOldSources());
            assertEquals(Set.of(6), result.getAddedStates());
            assertTrue(result.hasBase(first.getAnalysis()));
            assertFalse(result.hasBase(base));
            assertEquals(2L, result.getAnalysis().getRevision());
        }
        assertEquals(Set.of(0, 4), firstUpdate.getChangedOldSources());
        assertEquals(Set.of(0, 4), first.getChangedOldSources());
        assertFalse(first.getAnalysis().getStates().contains(6));
    }

    @Test
    public void fallbackAndFullAnalysisPreserveChangedSourceMetadataWhenOldLossIsRepaired() {
        LTS<Integer, String> original = game(0, 1, 4);
        edge(original, 0, "go", 1);
        edge(original, 1, "back", 0);
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(original, Set.of(), Set.of());
        assertEquals(Set.of(4), base.getLosingStates());
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(4, "rescue", 0).build());
        assertEquals(CachedLosingSafetyAnalysis.Backend.PREDECESSOR_CLOSURE,
                CachedLosingSafetyAnalysis.analyze(update).getBackend());
        for (Result<Integer, String> result : analyses(update)) {
            assertEquals(Set.of(4), result.getChangedOldSources());
            assertEquals(Set.of(4), result.getNewlyWinningStates());
            assertEquals(Set.of(0, 1, 4), result.getAnalysis().getWinningStates());
        }
    }

    @Test
    public void updateAndAnalysisMetadataRemainImmutableAcrossBranchAnalyses() {
        SafetyGameUpdate<Integer, String> update = mixedUpdate(base());
        immutable(() -> update.getChangedOldSources().add(99));
        immutable(() -> update.getChangedOldSources().remove(0));
        for (Result<Integer, String> result : analyses(update)) {
            immutable(() -> result.getChangedOldSources().add(99));
            immutable(() -> result.getChangedOldSources().clear());
            assertEquals(Set.of(0, 4), result.getChangedOldSources());
        }
        assertEquals(Set.of(0, 4), update.getChangedOldSources());
    }

    private static SafetyGameUpdate<Integer, String> mixedUpdate(Analysis<Integer, String> base) {
        return SafetyGameUpdate.apply(base, SafetyGameDelta.forAnalysis(base)
                .addState(2).addState(3)
                .addTransition(0, "go", 1) // Existing edge: not an effective addition.
                .addTransition(0, "divert", 2)
                .addTransition(0, "detour", 4)
                .addTransition(4, "rare", 2)
                .addTransition(2, "forward", 3)
                .addTransition(3, "rejoin", 1).build());
    }

    private static List<Result<Integer, String>> analyses(SafetyGameUpdate<Integer, String> update) {
        return List.of(FullSafetySynthesis.analyzeUpdate(update), IncrementalSafetyAnalysis.analyze(update),
                CachedLosingSafetyAnalysis.analyze(update).getResult());
    }

    private static Analysis<Integer, String> base() {
        LTS<Integer, String> original = game(0, 1, 4, 5, 9);
        edge(original, 0, "go", 1);
        edge(original, 1, "back", 0);
        edge(original, 4, "remote", 5);
        edge(original, 5, "return", 4);
        return FullSafetySynthesis.analyze(original, Set.of(), Set.of(9));
    }

    private static LTS<Integer, String> game(Integer... states) {
        LTS<Integer, String> result = new LTSImpl<>(states[0]);
        for (Integer state : states) result.addState(state);
        return result;
    }

    private static void edge(LTS<Integer, String> game, int from, String action, int to) {
        game.addAction(action);
        game.addTransition(from, action, to);
    }

    private static void immutable(Runnable mutation) {
        try {
            mutation.run();
            fail("Mutation of changed-old-source metadata must be rejected");
        } catch (UnsupportedOperationException expected) {
            // Expected: analysis consumers cannot modify the shared update metadata.
        }
    }
}
