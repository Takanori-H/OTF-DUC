package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis.Result;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class SafetyGameUpdateAnalysisTest {
    @Test
    public void sharedGraphUpdateSupportsFullAndLocalAnalysisWithTheSameMetadata() {
        Set<String> controls = Set.of("gain", "old");
        Analysis<Integer, String> base = analyze(mixedGame(), controls, Set.of(3));
        SafetyController<Integer, String> old = snapshot(base);
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base)
                        .addTransition(1, "newLoop", 1)
                        .addTransition(2, "fail", 3).build());
        Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
        LTS<Integer, String> expected = mixedGame();
        edge(expected, 1, "newLoop", 1);
        edge(expected, 2, "fail", 3);

        assertBoth(expected, Set.of(0, 1), update, full, incremental);
        assertEquals(Set.of(1), full.getNewlyWinningStates());
        assertEquals(Set.of(2), full.getNewlyLosingStates());
        assertEquals(Set.of(0, 1, 2), incremental.getAffectedStates());
        assertEquals(1, incremental.getReusedStateCount());
        assertEquals(ControllerUpdateSynthesis.Status.SIMULATION_INCOMPATIBLE,
                ControllerUpdateSynthesis.prepare(old, full).getStatus());
        assertEquals(ControllerUpdateSynthesis.prepare(old, full).getStatus(),
                ControllerUpdateSynthesis.prepare(old, incremental).getStatus());
    }

    @Test
    public void bothAnalysesRecoverAFormerlyLosingCycle() {
        LTS<Integer, String> original = game(0, 1, 7);
        edge(original, 0, "forward", 1);
        edge(original, 7, "unrelated", 7);
        Analysis<Integer, String> base = analyze(original, Set.of(), Set.of());
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "back", 0).build());
        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
        Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
        LTS<Integer, String> expected = game(0, 1, 7);
        edge(expected, 0, "forward", 1);
        edge(expected, 1, "back", 0);
        edge(expected, 7, "unrelated", 7);

        assertBoth(expected, Set.of(0, 1, 7), update, full, incremental);
        assertEquals(Set.of(0, 1), full.getNewlyWinningStates());
        assertEquals(Set.of(0, 1), incremental.getAffectedStates());
        assertEquals(1, incremental.getReusedStateCount());
        assertEquals(Set.of(7), base.getWinningStates());
    }

    @Test
    public void bothAnalysesHandleNewReachableStatesAndNewUnsafeStates() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2).addUnsafeState(3)
                        .addTransition(1, "divert", 2)
                        .addTransition(2, "resume", 0)
                        .addTransition(2, "fail", 3).build());
        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
        Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
        LTS<Integer, String> expected = twoStateGame();
        expected.addState(2);
        expected.addState(3);
        edge(expected, 1, "divert", 2);
        edge(expected, 2, "resume", 0);
        edge(expected, 2, "fail", 3);

        assertBoth(expected, Set.of(0), update, full, incremental);
        assertEquals(Set.of(2, 3), update.getAddedStates());
        assertEquals(Set.of(3), update.getAddedUnsafeStates());
        assertEquals(Set.of(3), full.getAnalysis().getUnsafeStates());
        assertEquals(Set.of(1), full.getNewlyLosingStates());
    }

    @Test
    public void fullAnalysisReanalyzesAnEmptyOrDuplicateOnlyUpdate() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyGameUpdate<Integer, String> empty = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).build());
        SafetyGameUpdate<Integer, String> duplicate = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0, "idle", 0).build());
        for (SafetyGameUpdate<Integer, String> update : java.util.Arrays.asList(empty, duplicate)) {
            Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
            Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
            assertBoth(twoStateGame(), Set.of(0, 1), update, full, incremental);
            assertEquals(Set.of(), update.getAddedTransitions());
            assertEquals(Set.of(), full.getNewlyWinningStates());
            assertEquals(Set.of(), full.getNewlyLosingStates());
            assertEquals(Set.of(), incremental.getAffectedStates());
            assertEquals(2, incremental.getReusedStateCount());
            assertEquals(1L, full.getAnalysis().getRevision());
        }
    }

    @Test
    public void fullClassificationDoesNotReadTheCachedBasePartition() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> correct = analyze(original, Set.of(), Set.of());
        // Package-local adversarial cache: not constructible through the public
        // API. Reversing only W/L pins the full baseline's independence from
        // old classification, even for an empty update. It is not a new promise
        // that incremental analysis accepts invalid cached partitions.
        Analysis<Integer, String> adversarial = new Analysis<>(correct.game,
                Set.of(1), Set.of(0), 17L);
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(adversarial,
                SafetyGameDelta.forAnalysis(adversarial).build());
        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);

        assertEquals(Set.of(0), full.getAnalysis().getWinningStates());
        assertEquals(Set.of(1), full.getAnalysis().getLosingStates());
        assertEquals(Set.of(0, 1), full.getAffectedStates());
        assertEquals(0, full.getReusedStateCount());
        assertEquals(18L, full.getAnalysis().getRevision());
        // Change metadata is intentionally relative to the supplied cache,
        // and is the only full-path operation allowed to consult old W/L.
        assertEquals(Set.of(0), full.getNewlyWinningStates());
        assertEquals(Set.of(1), full.getNewlyLosingStates());
    }

    @Test
    public void fullResultsPreserveBaseProvenanceWithoutEquatingNewBranchTokens() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        Analysis<Integer, String> unrelated = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyController<Integer, String> old = snapshot(base);
        SafetyGameDelta<Integer, String> delta = SafetyGameDelta.forAnalysis(base)
                .addTransition(1, "retry", 1).build();
        rejects(() -> SafetyGameUpdate.apply(unrelated, delta));
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base, delta);
        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
        Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
        assertEquals(ControllerUpdateSynthesis.Status.SUCCESS,
                ControllerUpdateSynthesis.prepare(old, full).getStatus());
        assertEquals(ControllerUpdateSynthesis.Status.SUCCESS,
                ControllerUpdateSynthesis.prepare(old, incremental).getStatus());
        rejects(() -> ControllerUpdateSynthesis.prepare(snapshot(unrelated), full));

        SafetyGameDelta<Integer, String> next = SafetyGameDelta.forAnalysis(full.getAnalysis())
                .addTransition(0, "wait", 0).build();
        assertEquals(full.getAnalysis().getRevision(), incremental.getAnalysis().getRevision());
        rejects(() -> SafetyGameUpdate.apply(incremental.getAnalysis(), next));
        SafetyGameUpdate<Integer, String> nextUpdate = SafetyGameUpdate.apply(full.getAnalysis(), next);
        assertEquals(2L, nextUpdate.getRevision());
    }

    @Test
    public void analysesCanBeChainedAcrossFullAndIncrementalResults() {
        LTS<Integer, String> original = game(0, 1);
        edge(original, 0, "idle", 0);
        Analysis<Integer, String> base = analyze(original, Set.of("go"), Set.of());
        SafetyGameUpdate<Integer, String> firstUpdate = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "loop", 1).build());
        Result<Integer, String> first = FullSafetySynthesis.analyzeUpdate(firstUpdate);
        SafetyController<Integer, String> afterFirst = snapshot(first.getAnalysis());
        SafetyGameUpdate<Integer, String> secondUpdate = SafetyGameUpdate.apply(first.getAnalysis(),
                SafetyGameDelta.forAnalysis(first.getAnalysis()).addTransition(0, "go", 1).build());
        Result<Integer, String> secondIncremental = IncrementalSafetyAnalysis.analyze(secondUpdate);
        Result<Integer, String> secondFull = FullSafetySynthesis.analyzeUpdate(secondUpdate);
        LTS<Integer, String> expected = game(0, 1);
        edge(expected, 0, "idle", 0);
        edge(expected, 1, "loop", 1);
        edge(expected, 0, "go", 1);

        assertBoth(expected, Set.of(0, 1), secondUpdate, secondFull, secondIncremental);
        assertEquals(2L, secondFull.getAnalysis().getRevision());
        for (Result<Integer, String> result : java.util.Arrays.asList(secondFull, secondIncremental)) {
            ControllerUpdateSynthesis.Plan<Integer, String> plan =
                    ControllerUpdateSynthesis.prepare(afterFirst, result);
            assertEquals(ControllerUpdateSynthesis.Status.SUCCESS, plan.getStatus());
            assertGraphEquals(expected,
                    ControllerUpdateSynthesis.generateFull(plan).getController().get().copyLts());
            assertGraphEquals(expected,
                    ControllerUpdateSynthesis.generateDifferential(plan).getController().get().copyLts());
        }
        assertEquals(Set.of(0), base.getWinningStates());
        assertEquals(0L, base.getRevision());
    }

    @Test
    public void preparingAndReanalyzingDoesNotMutateTheBaseOrSharedUpdate() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2)
                        .addTransition(1, "divert", 2)
                        .addTransition(2, "resume", 0).build());
        LTS<Integer, String> copy = update.copyGame();
        copy.removeTransition(0, "idle", 0);
        copy.removeTransition(2, "resume", 0);
        immutable(() -> update.getStates().add(99));
        immutable(() -> update.getActions().add("injected"));
        immutable(() -> update.getTransitions(1).put("injected", 99));
        immutable(() -> update.getAddedStates().add(99));
        immutable(() -> update.getAddedTransitions().add(new Transition<>(0, "injected", 99)));

        LTS<Integer, String> expected = twoStateGame();
        expected.addState(2);
        edge(expected, 1, "divert", 2);
        edge(expected, 2, "resume", 0);
        for (int repetition = 0; repetition < 2; repetition++) {
            assertBoth(expected, Set.of(0, 1, 2), update,
                    FullSafetySynthesis.analyzeUpdate(update), IncrementalSafetyAnalysis.analyze(update));
            assertGraphEquals(expected, update.copyGame());
        }
        assertGraphEquals(twoStateGame(), base.copyGame());
        assertEquals(0L, base.getRevision());
        assertEquals(1L, update.getRevision());
    }

    @Test
    public void graphValidationRejectsInvalidBatchesBeforeEitherAnalysis() {
        Analysis<Integer, String> base = analyze(twoStateGame(), Set.of("idle", "start"), Set.of());
        rejects(() -> SafetyGameUpdate.apply(base, SafetyGameDelta.forAnalysis(base)
                .addTransition(1, "retry", 1).addTransition(0, "idle", 1).build()));
        rejects(() -> SafetyGameUpdate.apply(base, SafetyGameDelta.forAnalysis(base)
                .addTransition(0, "unknownTarget", 99).build()));
        assertGraphEquals(twoStateGame(), base.copyGame());
        assertEquals(0L, base.getRevision());
        SafetyGameUpdate<Integer, String> valid = SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(1, "retry", 1).build());
        assertEquals(Set.of(new Transition<>(1, "retry", 1)), valid.getAddedTransitions());
        assertEquals(Set.of(0, 1), FullSafetySynthesis.analyzeUpdate(valid).getAnalysis().getWinningStates());
    }

    @Test
    public void all768TinyUpdatesAgreeWithAnIndependentClosedSubsetOracle() {
        int count = 0;
        for (int encoding = 0; encoding < 256; encoding++) {
            LTS<Integer, String> original = tinyGame(encoding);
            Analysis<Integer, String> base = analyze(original, Set.of("c"), Set.of(2));
            for (int source = 0; source < 2; source++) {
                for (String action : new String[] {"c", "u"}) {
                    if (!original.getTransitions(source).getImage(action).isEmpty()) {
                        continue;
                    }
                    for (int target = 0; target < 3; target++) {
                        SafetyGameUpdate<Integer, String> update = SafetyGameUpdate.apply(base,
                                SafetyGameDelta.forAnalysis(base).addTransition(source, action, target).build());
                        LTS<Integer, String> expected = tinyGame(encoding);
                        edge(expected, source, action, target);
                        Result<Integer, String> full = FullSafetySynthesis.analyzeUpdate(update);
                        Result<Integer, String> incremental = IncrementalSafetyAnalysis.analyze(update);
                        String context = "encoding=" + encoding + ", add="
                                + source + ":" + action + ":" + target;
                        Set<Integer> winning = tinyOracle(expected);
                        assertEquals(context, winning, full.getAnalysis().getWinningStates());
                        assertEquals(context, winning, incremental.getAnalysis().getWinningStates());
                        assertBoth(expected, winning, update, full, incremental);
                        assertGraphEquals(original, base.copyGame());
                        if (base.isRealizable()) {
                            SafetyController<Integer, String> old = snapshot(base);
                            ControllerUpdateSynthesis.Plan<Integer, String> fullPlan =
                                    ControllerUpdateSynthesis.prepare(old, full);
                            ControllerUpdateSynthesis.Plan<Integer, String> incrementalPlan =
                                    ControllerUpdateSynthesis.prepare(old, incremental);
                            assertEquals(context, fullPlan.getStatus(), incrementalPlan.getStatus());
                            assertEquals(context, fullPlan.getIncompatibleOldStates(),
                                    incrementalPlan.getIncompatibleOldStates());
                            assertEquals(context, fullPlan.getSwitchStateMapping(),
                                    incrementalPlan.getSwitchStateMapping());
                        }
                        count++;
                    }
                }
            }
        }
        assertEquals(768, count);
    }

    private static void assertBoth(LTS<Integer, String> expected, Set<Integer> winning,
                                   SafetyGameUpdate<Integer, String> update,
                                   Result<Integer, String> full, Result<Integer, String> incremental) {
        Set<Integer> losing = new HashSet<>(expected.getStates());
        losing.removeAll(winning);
        for (Result<Integer, String> result : java.util.Arrays.asList(full, incremental)) {
            assertEquals(winning, result.getAnalysis().getWinningStates());
            assertEquals(losing, result.getAnalysis().getLosingStates());
            assertEquals(update.getRevision(), result.getAnalysis().getRevision());
            assertEquals(update.getControllableActions(), result.getAnalysis().getControllableActions());
            assertEquals(update.getUnsafeStates(), result.getAnalysis().getUnsafeStates());
            assertEquals(update.getAddedStates(), result.getAddedStates());
            assertEquals(update.getAddedTransitions(), result.getAddedTransitions());
            assertGraphEquals(expected, result.getAnalysis().copyGame());
        }
        assertEquals(full.getNewlyWinningStates(), incremental.getNewlyWinningStates());
        assertEquals(full.getNewlyLosingStates(), incremental.getNewlyLosingStates());
        assertEquals(expected.getStates(), full.getAffectedStates());
        assertEquals(0, full.getReusedStateCount());
        if (winning.contains(expected.getInitialState())) {
            assertGraphEquals(snapshot(full.getAnalysis()).copyLts(), snapshot(incremental.getAnalysis()).copyLts());
        } else {
            assertFalse(FullSafetySynthesis.generateControllerSnapshot(full.getAnalysis()).isPresent());
            assertFalse(FullSafetySynthesis.generateControllerSnapshot(incremental.getAnalysis()).isPresent());
        }
    }

    private static Set<Integer> tinyOracle(LTS<Integer, String> game) {
        Set<Integer> winning = new HashSet<>();
        for (int mask = 0; mask < 4; mask++) {
            Set<Integer> candidate = new HashSet<>();
            for (int state = 0; state < 2; state++) {
                if ((mask & (1 << state)) != 0) { candidate.add(state); }
            }
            boolean closed = true;
            for (Integer state : candidate) {
                boolean retainedMove = false;
                for (Pair<String, Integer> edge : game.getTransitions(state)) {
                    boolean inside = candidate.contains(edge.getSecond());
                    retainedMove |= inside;
                    if ("u".equals(edge.getFirst()) && !inside) { closed = false; }
                }
                closed &= retainedMove;
            }
            if (closed) { winning.addAll(candidate); }
        }
        return winning;
    }

    private static void assertGraphEquals(LTS<Integer, String> expected, LTS<Integer, String> actual) {
        assertEquals(expected.getInitialState(), actual.getInitialState());
        assertEquals(expected.getStates(), actual.getStates());
        assertEquals(expected.getActions(), actual.getActions());
        assertEquals(transitions(expected), transitions(actual));
    }

    private static Set<Transition<Integer, String>> transitions(LTS<Integer, String> game) {
        Set<Transition<Integer, String>> result = new HashSet<>();
        for (Integer state : game.getStates()) {
            for (Pair<String, Integer> edge : game.getTransitions(state)) {
                result.add(new Transition<>(state, edge.getFirst(), edge.getSecond()));
            }
        }
        return result;
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // No graph or analysis is partially published on failure.
        }
    }

    private static void immutable(Runnable operation) {
        try {
            operation.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // The common preparation must be safe to share between analyzers.
        }
    }

    private static Analysis<Integer, String> analyze(LTS<Integer, String> game,
                                                     Set<String> controls, Set<Integer> unsafe) {
        return FullSafetySynthesis.analyze(game, controls, unsafe);
    }

    private static SafetyController<Integer, String> snapshot(Analysis<Integer, String> analysis) {
        return FullSafetySynthesis.generateControllerSnapshot(analysis).get();
    }

    private static LTS<Integer, String> mixedGame() {
        LTS<Integer, String> result = game(0, 1, 2, 3);
        edge(result, 0, "gain", 1);
        edge(result, 0, "old", 2);
        edge(result, 2, "oldLoop", 2);
        return result;
    }

    private static LTS<Integer, String> twoStateGame() {
        LTS<Integer, String> result = game(0, 1);
        edge(result, 0, "idle", 0);
        edge(result, 0, "start", 1);
        edge(result, 1, "finish", 0);
        return result;
    }

    private static LTS<Integer, String> tinyGame(int encoding) {
        LTS<Integer, String> result = game(0, 1, 2);
        for (int state = 0; state < 2; state++) {
            for (String action : new String[] {"c", "u"}) {
                int target = encoding % 4;
                encoding /= 4;
                if (target != 3) { edge(result, state, action, target); }
            }
        }
        return result;
    }

    private static LTS<Integer, String> game(Integer initial, Integer... others) {
        LTS<Integer, String> result = new LTSImpl<>(initial);
        for (Integer state : others) { result.addState(state); }
        return result;
    }

    private static void edge(LTS<Integer, String> game, int source, String action, int target) {
        game.addAction(action);
        game.addTransition(source, action, target);
    }
}
