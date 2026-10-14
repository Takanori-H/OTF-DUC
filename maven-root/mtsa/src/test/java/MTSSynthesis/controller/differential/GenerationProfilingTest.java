package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Plan;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Result;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.SeedSelection;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis.Status;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class GenerationProfilingTest {
    @Test
    public void indexedSeedsAvoidNewRegionEdgesAndKeepControllerAndPatchIdentical() {
        Analysis<Integer, String> base = cycleWithFilteredEdge();
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addState(3).addState(5).addState(6)
                        .addTransition(1, "fault", 2).addTransition(2, "advance", 3)
                        .addTransition(3, "alternate", 2)
                        .addTransition(5, "outside", 6).addTransition(6, "outsideBack", 5).build());
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, change);
        long start = System.nanoTime();
        Result<Integer, String> indexed = ControllerUpdateSynthesis.generateDifferential(
                plan, SeedSelection.CHANGED_OLD_SOURCES, true);
        long elapsed = System.nanoTime() - start;
        Result<Integer, String> reference = ControllerUpdateSynthesis.generateDifferential(
                plan, SeedSelection.ALL_ADDED_TRANSITIONS, true);
        Result<Integer, String> full = ControllerUpdateSynthesis.generateFull(plan, true);
        assertControllerEquals(full.getController().get(), indexed.getController().get());
        assertControllerEquals(full.getController().get(), reference.getController().get());
        assertControllerEquals(indexed.getController().get(), indexed.getPatch().get().apply(old));
        assertEquals(reference.getPatch().get().getAddedStates(), indexed.getPatch().get().getAddedStates());
        assertEquals(reference.getPatch().get().getAddedTransitions(), indexed.getPatch().get().getAddedTransitions());
        assertEquals(Set.of(2, 3), indexed.getPatch().get().getAddedStates());
        assertEquals(Set.of(0, 1), old.getStates());
        assertEquals(Map.of("return", 0), old.getTransitions(1));
        ControllerGenerationProfile profile = indexed.getGenerationProfile().get();
        assertBounds(profile, elapsed);
        assertCount(profile, "added_edges_examined", 0);
        assertCount(profile, "changed_old_sources_examined", 1);
        assertCount(profile, "newly_winning_predecessors_examined", 0);
        assertCount(profile, "seed_states", 1);
        assertCount(profile, "expanded_states", 3);
        assertCount(profile, "game_edges_examined", 4);
        assertCount(profile, "reused_controller_rows", 1);
        assertCount(reference.getGenerationProfile().get(), "added_edges_examined", 5);
        assertCount(reference.getGenerationProfile().get(), "changed_old_sources_examined", 0);
        assertEquals(indexed.getExpandedStateCount(), reference.getExpandedStateCount());
        assertEquals(indexed.getReusedRowCount(), reference.getReusedRowCount());
        // Full extraction also scans the disabled controllable edge to unsafe 9.
        assertCount(full.getGenerationProfile().get(), "game_edges_examined", 6);
        assertCount(full.getGenerationProfile().get(), "expanded_states", 4);
        assertCount(full.getGenerationProfile().get(), "reused_controller_rows", 0);
    }

    @Test
    public void oldLosingRevivalStillSeedsExistingPredecessorEdges() {
        LTS<Integer, String> game = game(0, 1, 2);
        edge(game, 0, "step", 1);
        edge(game, 1, "return", 0);
        edge(game, 0, "choose", 2);
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(game, Set.of("choose"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        assertEquals(Set.of(2), base.getLosingStates());
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addState(3)
                        .addTransition(2, "newStep", 3).addTransition(3, "cycleBack", 2).build());
        assertEquals(Set.of(2), change.getNewlyWinningStates());
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, change);
        Result<Integer, String> indexed = ControllerUpdateSynthesis.generateDifferential(plan, true);
        Result<Integer, String> reference = ControllerUpdateSynthesis.generateDifferential(
                plan, SeedSelection.ALL_ADDED_TRANSITIONS, true);
        assertControllerEquals(ControllerUpdateSynthesis.generateFull(plan).getController().get(),
                indexed.getController().get());
        assertControllerEquals(reference.getController().get(), indexed.getController().get());
        assertEquals(Integer.valueOf(2), indexed.getController().get().getTransitions(0).get("choose"));
        assertCount(indexed.getGenerationProfile().get(), "changed_old_sources_examined", 1);
        assertCount(indexed.getGenerationProfile().get(), "newly_winning_predecessors_examined", 2);
        assertCount(indexed.getGenerationProfile().get(), "seed_states", 1);
        assertCount(indexed.getGenerationProfile().get(), "expanded_states", 3);
    }

    @Test
    public void unprofiledApisRemainUnprofiledForBothSeedImplementations() {
        Analysis<Integer, String> base = cycleWithFilteredEdge();
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "alternate", 0).build());
        Plan<Integer, String> plan = ControllerUpdateSynthesis.prepare(old, change);
        Result<Integer, String> defaultResult = ControllerUpdateSynthesis.generateDifferential(plan);
        Result<Integer, String> reference = ControllerUpdateSynthesis.generateDifferential(
                plan, SeedSelection.ALL_ADDED_TRANSITIONS);
        assertFalse(defaultResult.getGenerationProfile().isPresent());
        assertFalse(reference.getGenerationProfile().isPresent());
        assertFalse(ControllerUpdateSynthesis.generateFull(plan).getGenerationProfile().isPresent());
        assertControllerEquals(defaultResult.getController().get(), reference.getController().get());
        assertControllerEquals(defaultResult.getController().get(),
                ControllerUpdateSynthesis.generateDifferential(plan, true).getController().get());
    }

    @Test
    public void fullSnapshotProfileCountsOnlyReachedRowsButIncludesFilteredEdges() {
        Analysis<Integer, String> base = cycleWithFilteredEdge();
        long start = System.nanoTime();
        FullSafetySynthesis.ProfiledController<Integer, String> result =
                FullSafetySynthesis.generateControllerSnapshotProfiled(base);
        long elapsed = System.nanoTime() - start;
        assertControllerEquals(snapshot(base), result.getController().get());
        ControllerGenerationProfile profile = result.getGenerationProfile().get();
        assertBounds(profile, elapsed);
        assertCount(profile, "seed_states", 1);
        assertCount(profile, "expanded_states", 2);
        assertCount(profile, "game_edges_examined", 3);
        assertCount(profile, "added_edges_examined", 0);
        assertEquals(Long.valueOf(0), profile.getPhaseNanos().get("seed_selection"));
        assertEquals(Long.valueOf(0), profile.getPhaseNanos().get("patch_construction"));
        try {
            profile.getPhaseNanos().put("seed_selection", 1L);
            fail("Phase diagnostics must be immutable.");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        try {
            profile.getCounters().clear();
            fail("Counter diagnostics must be immutable.");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    public void emptyDeltaHasNoSeedOrExpansionWorkButStillBuildsNewSnapshotAndPatch() {
        Analysis<Integer, String> base = cycleWithFilteredEdge();
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).build());
        Result<Integer, String> result = ControllerUpdateSynthesis.generateDifferential(
                ControllerUpdateSynthesis.prepare(old, change), true);
        ControllerGenerationProfile profile = result.getGenerationProfile().get();
        assertCount(profile, "seed_states", 0);
        assertCount(profile, "expanded_states", 0);
        assertCount(profile, "game_edges_examined", 0);
        assertCount(profile, "reused_controller_rows", 2);
        assertEquals(Set.of(), result.getPatch().get().getAddedTransitions());
        assertControllerEquals(old, result.getController().get());
        assertNotSame(old, result.getController().get());
    }

    @Test
    public void rejectedGenerationReportsNoProfileForEitherFailureStatus() {
        Analysis<Integer, String> base = cycleWithFilteredEdge();
        SafetyController<Integer, String> old = snapshot(base);
        IncrementalSafetyAnalysis.Result<Integer, String> losing = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "failure", 9).build());
        Plan<Integer, String> unrealizable = ControllerUpdateSynthesis.prepare(old, losing);
        assertEquals(Status.UNREALIZABLE, unrealizable.getStatus());
        assertRejected(unrealizable);
        FullSafetySynthesis.ProfiledController<Integer, String> empty =
                FullSafetySynthesis.generateControllerSnapshotProfiled(losing.getAnalysis());
        assertFalse(empty.getController().isPresent());
        assertFalse(empty.getGenerationProfile().isPresent());

        LTS<Integer, String> optional = game(0, 1, 2, 9);
        edge(optional, 0, "chooseOne", 1);
        edge(optional, 0, "chooseTwo", 2);
        edge(optional, 1, "returnOne", 0);
        edge(optional, 2, "returnTwo", 0);
        Analysis<Integer, String> optionalBase = FullSafetySynthesis.analyze(
                optional, Set.of("chooseOne", "chooseTwo"), Set.of(9));
        IncrementalSafetyAnalysis.Result<Integer, String> incompatible = IncrementalSafetyAnalysis.update(optionalBase,
                SafetyGameDelta.forAnalysis(optionalBase).addTransition(1, "failure", 9).build());
        Plan<Integer, String> rejected = ControllerUpdateSynthesis.prepare(snapshot(optionalBase), incompatible);
        assertEquals(Status.SIMULATION_INCOMPATIBLE, rejected.getStatus());
        assertRejected(rejected);
    }

    private static void assertRejected(Plan<Integer, String> plan) {
        for (SeedSelection selection : SeedSelection.values()) {
            Result<Integer, String> result = ControllerUpdateSynthesis.generateDifferential(plan, selection, true);
            assertFalse(result.getController().isPresent());
            assertFalse(result.getPatch().isPresent());
            assertFalse(result.getGenerationProfile().isPresent());
        }
        assertFalse(ControllerUpdateSynthesis.generateFull(plan, true).getGenerationProfile().isPresent());
    }

    private static void assertBounds(ControllerGenerationProfile profile, long enclosingNanos) {
        assertEquals(Set.of("seed_selection", "row_setup", "row_expansion", "controller_snapshot", "patch_construction"),
                profile.getPhaseNanos().keySet());
        long sum = 0;
        for (long phase : profile.getPhaseNanos().values()) {
            assertTrue(phase >= 0);
            sum += phase;
        }
        assertTrue(sum <= profile.getTotalNanos());
        assertEquals(profile.getTotalNanos() - sum, profile.getUnattributedNanos());
        assertTrue(profile.getTotalNanos() <= enclosingNanos);
    }

    private static void assertCount(ControllerGenerationProfile profile, String key, long expected) {
        assertEquals(key, Long.valueOf(expected), profile.getCounters().get(key));
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

    private static Analysis<Integer, String> cycleWithFilteredEdge() {
        LTS<Integer, String> game = game(0, 1, 9);
        edge(game, 0, "step", 1);
        edge(game, 1, "return", 0);
        edge(game, 0, "badChoice", 9);
        return FullSafetySynthesis.analyze(game, Set.of("badChoice"), Set.of(9));
    }

    private static SafetyController<Integer, String> snapshot(Analysis<Integer, String> analysis) {
        return FullSafetySynthesis.generateControllerSnapshot(analysis).get();
    }

    private static LTS<Integer, String> game(Integer... states) {
        LTS<Integer, String> result = new LTSImpl<>(states[0]);
        result.addStates(Set.of(states));
        return result;
    }

    private static void edge(LTS<Integer, String> game, int source, String action, int target) {
        game.addAction(action);
        game.addTransition(source, action, target);
    }
}
