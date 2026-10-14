package MTSSynthesis.controller.differential;

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

/**
 * Executable correspondence with IEICA Algorithms 3, 4, 6 and 7. The paper
 * constructs the all-winning subgraph; the production API exposes its rooted
 * reachable part. These small reference loops are not performance baselines.
 */
public class IeicaControllerConstructionTest {
    @Test
    public void paperDeltaUnionMatchesReachableOutputWhenOldClassificationsArePreserved() {
        LTS<Integer, String> original = game(0, 1, 2, 3, 4, -1);
        edge(original, 0, "go", 1);
        edge(original, 1, "back", 0);
        edge(original, 0, "try", 2);
        edge(original, 2, "bad", -1);
        edge(original, 2, "escape", 3);
        edge(original, 3, "step", 4);
        edge(original, 4, "again", 3);
        Set<String> controls = Set.of("go", "try", "escape");
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(original, controls, Set.of(-1));
        SafetyController<Integer, String> previous = FullSafetySynthesis.generateControllerSnapshot(base).get();
        assertEquals(Set.of(0, 1, 3, 4), base.getWinningStates());
        assertEquals(Set.of(0, 1), previous.getStates());

        SafetyGameDelta<Integer, String> delta = SafetyGameDelta.<Integer, String>forAnalysis(base)
                .addState(5).addState(6)
                .addTransition(1, "fault", 5)
                .addTransition(5, "next", 6)
                .addTransition(6, "enter", 3).build();
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base, delta);
        LTS<Integer, String> fresh = copyWithAdditions(original, delta);
        Analysis<Integer, String> oracle = FullSafetySynthesis.analyze(fresh, controls, Set.of(-1));
        assertEquals(oracle.getWinningStates(), change.getAnalysis().getWinningStates());
        assertTrue(change.getNewlyWinningStates().isEmpty());
        assertTrue(change.getNewlyLosingStates().isEmpty());

        Set<Transition<Integer, String>> paper = paperWinningEdges(base.getWinningStates(), edges(original));
        paper.addAll(paperWinningEdges(oracle.getWinningStates(), delta.getAddedTransitions()));
        ControllerUpdateSynthesis.Result<Integer, String> actual = ControllerUpdateSynthesis.generateDifferential(
                ControllerUpdateSynthesis.prepare(previous, change));
        assertEquals(rootedRows(0, paper), rows(actual.getController().get()));
        assertEquals(rootedRows(0, paperWinningEdges(oracle.getWinningStates(), edges(fresh))),
                rows(actual.getController().get()));
        assertEquals(Set.of(3, 4, 5, 6), actual.getPatch().get().getAddedStates());
        // Previously unreachable old winning behavior is needed in the rooted C.
        // It was already in the paper's all-W old SP, but not in the API's old C.
        assertTrue(actual.getPatch().get().getAddedTransitions().contains(new Transition<>(3, "step", 4)));
        assertFalse(delta.getAddedTransitions().contains(new Transition<>(3, "step", 4)));
    }

    @Test
    public void deadlockRepairShowsWhyLiteralDeltaUnionHasALimitedCorrespondence() {
        checkRestorationBoundary(false);
    }

    @Test
    public void firstUncontrollableEdgeCanRestoreLosingStateEvenWithoutAnOrdinaryDeadlock() {
        checkRestorationBoundary(true);
    }

    private static void checkRestorationBoundary(boolean oldLosingHasControlledErrorEdge) {
        LTS<Integer, String> original = game(0, 1, 2, -1);
        edge(original, 0, "try", 1);
        edge(original, 0, "go", 2);
        edge(original, 2, "back", 0);
        if (oldLosingHasControlledErrorEdge) edge(original, 1, "bad", -1);
        Set<String> controls = Set.of("try", "go", "bad");
        Analysis<Integer, String> base = FullSafetySynthesis.analyze(original, controls, Set.of(-1));
        assertEquals(Set.of(0, 2), base.getWinningStates());
        SafetyController<Integer, String> previous = FullSafetySynthesis.generateControllerSnapshot(base).get();
        SafetyGameDelta<Integer, String> delta = SafetyGameDelta.<Integer, String>forAnalysis(base)
                .addTransition(1, "newU", 2).build();
        IncrementalSafetyAnalysis.Result<Integer, String> change = IncrementalSafetyAnalysis.update(base, delta);
        Analysis<Integer, String> full = FullSafetySynthesis.analyze(copyWithAdditions(original, delta),
                controls, Set.of(-1));
        assertEquals(full.getWinningStates(), change.getAnalysis().getWinningStates());
        assertEquals(Set.of(1), change.getNewlyWinningStates());
        assertTrue(full.getWinningStates().containsAll(base.getWinningStates()));

        Set<Transition<Integer, String>> literal = paperWinningEdges(base.getWinningStates(), edges(original));
        literal.addAll(paperWinningEdges(full.getWinningStates(), delta.getAddedTransitions()));
        SafetyController<Integer, String> actual = ControllerUpdateSynthesis.generateDifferential(
                ControllerUpdateSynthesis.prepare(previous, change)).getController().get();
        Map<Integer, Map<String, Integer>> expected = rootedRows(0,
                paperWinningEdges(full.getWinningStates(), edges(copyWithAdditions(original, delta))));
        assertEquals(expected, rows(actual));
        assertNotEquals(rootedRows(0, literal), rows(actual));
        assertEquals(Integer.valueOf(1), actual.getTransitions(0).get("try"));
        assertEquals(Integer.valueOf(2), actual.getTransitions(1).get("newU"));
        // No added edge in this test is a self-loop. The literal delta-only
        // union misses the preexisting 0 --try--> 1 edge newly usable in C.
    }

    private static Set<Transition<Integer, String>> paperWinningEdges(Set<Integer> winning,
            Set<Transition<Integer, String>> candidates) {
        Set<Transition<Integer, String>> result = new HashSet<>();
        for (Integer source : winning) {
            for (Transition<Integer, String> edge : candidates) {
                if (source.equals(edge.getSource()) && winning.contains(edge.getTarget())) result.add(edge);
            }
        }
        return result;
    }

    private static Map<Integer, Map<String, Integer>> rootedRows(int initial,
            Set<Transition<Integer, String>> edges) {
        Map<Integer, Map<String, Integer>> result = new HashMap<>();
        Queue<Integer> pending = new ArrayDeque<>();
        result.put(initial, new HashMap<>());
        pending.add(initial);
        while (!pending.isEmpty()) {
            Integer source = pending.remove();
            for (Transition<Integer, String> edge : edges) {
                if (!source.equals(edge.getSource())) continue;
                result.get(source).put(edge.getAction(), edge.getTarget());
                if (!result.containsKey(edge.getTarget())) {
                    result.put(edge.getTarget(), new HashMap<>());
                    pending.add(edge.getTarget());
                }
            }
        }
        return result;
    }

    private static Map<Integer, Map<String, Integer>> rows(SafetyController<Integer, String> controller) {
        Map<Integer, Map<String, Integer>> result = new HashMap<>();
        for (Integer state : controller.getStates()) result.put(state, controller.getTransitions(state));
        return result;
    }

    private static Set<Transition<Integer, String>> edges(LTS<Integer, String> graph) {
        Set<Transition<Integer, String>> result = new HashSet<>();
        for (Integer state : graph.getStates()) {
            for (Pair<String, Integer> edge : graph.getTransitions(state)) {
                result.add(new Transition<>(state, edge.getFirst(), edge.getSecond()));
            }
        }
        return result;
    }

    private static LTS<Integer, String> copyWithAdditions(LTS<Integer, String> original,
            SafetyGameDelta<Integer, String> delta) {
        LTS<Integer, String> result = new LTSImpl<>(original.getInitialState());
        result.addStates(original.getStates());
        result.addStates(delta.getAddedStates());
        result.addActions(original.getActions());
        for (Transition<Integer, String> edge : edges(original)) edge(result, edge.getSource(), edge.getAction(), edge.getTarget());
        for (Transition<Integer, String> edge : delta.getAddedTransitions()) edge(result, edge.getSource(), edge.getAction(), edge.getTarget());
        return result;
    }

    private static LTS<Integer, String> game(Integer... states) {
        LTS<Integer, String> result = new LTSImpl<>(0);
        result.addStates(Set.of(states));
        return result;
    }

    private static void edge(LTS<Integer, String> graph, int from, String action, int to) {
        graph.addAction(action);
        graph.addTransition(from, action, to);
    }
}
