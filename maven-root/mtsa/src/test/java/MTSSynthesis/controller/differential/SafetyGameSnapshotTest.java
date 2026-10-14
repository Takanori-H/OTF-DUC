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

public class SafetyGameSnapshotTest {
    @Test
    public void matchesExistingFullSolverOn512SeededDeterministicGames() {
        Random random = new Random(20261006L);
        Set<String> actions = Set.of("c0", "c1", "u0", "u1", "reserved");
        Set<String> controls = Set.of("c0", "c1", "unusedControl");
        for (int trial = 0; trial < 512; trial++) {
            int size = 1 + random.nextInt(18);
            Map<Integer, Map<String, Integer>> rows = new HashMap<>();
            Set<Integer> unsafe = new HashSet<>();
            for (int state = 0; state < size; state++) {
                Map<String, Integer> row = new HashMap<>();
                rows.put(state, row);
                if (random.nextInt(6) == 0) {
                    unsafe.add(state);
                }
                for (String action : new String[] {"c0", "c1", "u0", "u1"}) {
                    if (random.nextBoolean()) {
                        row.put(action, random.nextInt(size));
                    }
                }
            }
            LTS<Integer, String> reference = materialize(0, rows, actions);
            Analysis<Integer, String> expected = FullSafetySynthesis.analyze(reference, controls, unsafe);
            Analysis<Integer, String> actual = FullSafetySynthesis.analyze(
                    FullSafetySynthesis.prepare(0, rows, actions, controls, unsafe));
            assertEquals("winning trial " + trial, expected.getWinningStates(), actual.getWinningStates());
            assertEquals("losing trial " + trial, expected.getLosingStates(), actual.getLosingStates());
            assertEquals(expected.isRealizable(), actual.isRealizable());
            if (actual.isRealizable()) {
                assertControllerEquals(FullSafetySynthesis.generateControllerSnapshot(expected).get(),
                        FullSafetySynthesis.generateControllerSnapshot(actual).get());
            }
        }
    }

    @Test
    public void handlesErrorDeadlocksCyclesAndSeveralLabelsToOneTarget() {
        Map<Integer, Map<String, Integer>> rows = new HashMap<>();
        rows.put(-1, Map.of("c0", 0)); // Explicit ERROR is terminalized, including this edge.
        rows.put(0, Map.of("c0", 1, "c1", 1)); // Both labels lead to one deadlock.
        rows.put(1, Map.of());
        rows.put(2, Map.of("c0", 0, "c1", 3)); // Can disable the bad action.
        rows.put(3, Map.of("u", 2)); // A surviving two-state cycle.
        rows.put(4, Map.of("c0", 3, "u", -1)); // U-to-ERROR forces loss despite a safe option.
        rows.put(5, Map.of("c0", 4, "c1", 4));
        SafetyGameSnapshot<Integer, String> snapshot = FullSafetySynthesis.prepare(
                2, rows, Set.of("c0", "c1", "u"), Set.of("c0", "c1"), Set.of(-1));
        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(snapshot);
        assertEquals(Set.of(2, 3), analysis.getWinningStates());
        assertEquals(Set.of(-1, 0, 1, 4, 5), analysis.getLosingStates());
        assertTrue(snapshot.getTransitions(-1).isEmpty());
        assertEquals(Map.of("c0", 0), rows.get(-1));
        assertEquals(Set.of(2, 3), FullSafetySynthesis.generateControllerSnapshot(analysis).get().getStates());
    }

    @Test
    public void preparationOwnsAllCollectionsAndFullAnalysesAreIndependent() {
        Map<Integer, Map<String, Integer>> rows = new HashMap<>();
        rows.put(0, new HashMap<>(Map.of("go", 1)));
        rows.put(1, new HashMap<>(Map.of("back", 0)));
        Set<String> actions = new HashSet<>(Set.of("go", "back"));
        Set<String> controls = new HashSet<>(Set.of("go"));
        Set<Integer> unsafe = new HashSet<>();
        SafetyGameSnapshot<Integer, String> first = FullSafetySynthesis.prepare(
                0, rows, actions, controls, unsafe);
        SafetyGameSnapshot<Integer, String> second = FullSafetySynthesis.prepare(
                0, rows, actions, controls, unsafe);
        assertNotSame(first.game, second.game);
        assertNotSame(first.getTransitions(0), second.getTransitions(0));
        assertNotSame(first.game.predecessors(1), second.game.predecessors(1));
        rows.get(0).clear();
        rows.clear();
        actions.clear();
        controls.clear();
        unsafe.add(0);
        first.copyGame().removeTransition(0, "go", 1);
        assertEquals(Map.of("go", 1), first.getTransitions(0));
        assertEquals(Set.of("go", "back"), first.getActions());
        assertEquals(Set.of("go"), first.getControllableActions());
        assertTrue(first.getUnsafeStates().isEmpty());
        Analysis<Integer, String> analyzed = FullSafetySynthesis.analyze(first);
        Analysis<Integer, String> again = FullSafetySynthesis.analyze(first);
        assertNotSame(analyzed.versionIdentity, again.versionIdentity);
        assertEquals(Set.of(0, 1), analyzed.getWinningStates());
        assertEquals(analyzed.getWinningStates(), again.getWinningStates());
        expectUnsupported(() -> first.getStates().clear());
        expectUnsupported(() -> first.getActions().clear());
        expectUnsupported(() -> first.getControllableActions().clear());
        expectUnsupported(() -> first.getUnsafeStates().add(0));
        expectUnsupported(() -> first.getTransitions(0).clear());
    }

    @Test
    public void preparedAnalysisSupportsExistingDifferentialUpdatesWithNewStates() {
        Map<Integer, Map<String, Integer>> rows = new HashMap<>();
        rows.put(0, Map.of("go", 1));
        rows.put(1, Map.of("back", 0));
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(FullSafetySynthesis.prepare(
                0, rows, Set.of("go", "back"), Set.of(), Set.of()));
        SafetyGameDelta<Integer, String> delta = SafetyGameDelta.<Integer, String>forAnalysis(base)
                .addState(2).addState(3)
                .addTransition(0, "fault", 2)
                .addTransition(2, "continue", 3)
                .addTransition(3, "repeat", 2).build();
        Analysis<Integer, String> updated = IncrementalSafetyAnalysis.update(base, delta).getAnalysis();
        Analysis<Integer, String> full = FullSafetySynthesis.analyze(updated.copyGame(),
                updated.getControllableActions(), updated.getUnsafeStates());
        assertEquals(Set.of(0, 1, 2, 3), updated.getWinningStates());
        assertEquals(full.getWinningStates(), updated.getWinningStates());
        assertControllerEquals(FullSafetySynthesis.generateControllerSnapshot(full).get(),
                FullSafetySynthesis.generateControllerSnapshot(updated).get());
        assertEquals(Set.of(0, 1), base.getStates());
    }

    @Test
    public void compactReverseIndexesRemainIndependentAcrossSequentialUpdates() {
        SafetyGameSnapshot<Integer, String> snapshot = FullSafetySynthesis.prepare(0,
                Map.of(0, Map.of("c", 1), 1, Map.of("back", 0),
                        2, Map.of("c", 1), -1, Map.of()),
                Set.of("c", "back", "alt"), Set.of("c", "alt"), Set.of(-1));
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(snapshot);
        Analysis<Integer, String> first = IncrementalSafetyAnalysis.update(base,
                SafetyGameDelta.<Integer, String>forAnalysis(base)
                        // All-edge pair already exists; only the U index changes.
                        .addTransition(0, "fault", 1)
                        // All/U rows previously agree; this separates them.
                        .addTransition(2, "alt", 0).build()).getAnalysis();
        assertEquals(Set.of(0, 2), first.game.predecessors(1));
        assertEquals(Set.of(0), first.game.uncontrollablePredecessors(1));
        assertTrue(base.game.uncontrollablePredecessors(1).isEmpty());
        assertEquals(Set.of(1, 2), first.game.predecessors(0));
        assertEquals(Set.of(1), first.game.uncontrollablePredecessors(0));
        assertEquals(Set.of(1), base.game.predecessors(0));
        expectUnsupported(() -> first.game.uncontrollablePredecessors(1).add(2));

        Analysis<Integer, String> second = IncrementalSafetyAnalysis.update(first,
                SafetyGameDelta.<Integer, String>forAnalysis(first)
                        .addTransition(1, "failure", -1).build()).getAnalysis();
        Analysis<Integer, String> reference = FullSafetySynthesis.analyze(second.copyGame(),
                second.getControllableActions(), second.getUnsafeStates());
        assertEquals(reference.getLosingStates(), second.getLosingStates());
        assertEquals(Set.of(-1, 0, 1, 2), second.getLosingStates());
        assertEquals(Set.of(0, 1, 2), first.getWinningStates());
        assertEquals(Set.of(0, 1, 2), base.getWinningStates());
        assertTrue(first.game.predecessors(-1).isEmpty());
        assertEquals(Set.of(1), second.game.predecessors(-1));
        expectUnsupported(() -> second.game.predecessors(-1).clear());
    }

    @Test
    public void rejectsUndeclaredInputsBeforeConstructingASnapshot() {
        expectInvalid(() -> FullSafetySynthesis.prepare(9, Map.of(0, Map.of()),
                Set.of(), Set.of(), Set.of()));
        expectInvalid(() -> FullSafetySynthesis.prepare(0, Map.of(0, Map.of()),
                Set.of(), Set.of(), Set.of(9)));
        expectInvalid(() -> FullSafetySynthesis.prepare(0, Map.of(0, Map.of("go", 9)),
                Set.of("go"), Set.of(), Set.of()));
        expectInvalid(() -> FullSafetySynthesis.prepare(0, Map.of(0, Map.of("go", 0)),
                Set.of(), Set.of(), Set.of()));
        // Terminalization must not hide malformed input on an unsafe row.
        expectInvalid(() -> FullSafetySynthesis.prepare(0, Map.of(0, Map.of("go", 9)),
                Set.of("go"), Set.of(), Set.of(0)));
    }

    private static LTS<Integer, String> materialize(Integer initial,
            Map<Integer, Map<String, Integer>> rows, Set<String> actions) {
        LTS<Integer, String> game = new LTSImpl<>(initial);
        game.addStates(rows.keySet());
        game.addActions(actions);
        rows.forEach((source, row) -> row.forEach((action, target) -> game.addTransition(source, action, target)));
        return game;
    }

    private static void assertControllerEquals(SafetyController<Integer, String> expected,
            SafetyController<Integer, String> actual) {
        assertEquals(expected.getInitialState(), actual.getInitialState());
        assertEquals(expected.getActions(), actual.getActions());
        assertEquals(expected.getStates(), actual.getStates());
        for (Integer state : expected.getStates()) {
            assertEquals(expected.getTransitions(state), actual.getTransitions(state));
        }
    }

    private static void expectInvalid(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Input must be rejected.
        }
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Snapshot collections must not allow mutation.
        }
    }
}
