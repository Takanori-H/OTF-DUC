package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.SafeGameSolver;
import MTSSynthesis.controller.util.GameStrategyToLTSBuilder;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.*;

public class FullSafetySynthesisTest {
    @Test
    public void retainsEverySafeReachableEdgeAndOriginalIds() {
        LTS<Integer, String> game = game(10, 20, 30, 40);
        edge(game, 10, "idle", 10);
        edge(game, 10, "start", 20);
        edge(game, 10, "alternative", 20);
        edge(game, 10, "unsafe", 30);
        edge(game, 20, "finish", 10);
        edge(game, 20, "wait", 20);
        edge(game, 30, "unsafeLoop", 30);
        edge(game, 40, "unreachableLoop", 40);
        game.addAction("reserved");

        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(
                game, Set.of("idle", "start", "alternative", "unsafe"), Set.of(30));
        assertEquals(Set.of(10, 20, 40), analysis.getWinningStates());
        assertEquals(Set.of(30), analysis.getLosingStates());
        LTS<Integer, String> controller = FullSafetySynthesis.generateController(analysis).get();
        assertEquals(Integer.valueOf(10), controller.getInitialState());
        assertEquals(Set.of(10, 20), controller.getStates());
        assertEquals(Set.of("10:idle:10", "10:start:20", "10:alternative:20",
                "20:finish:10", "20:wait:20"), edges(controller));
        assertEquals(game.getActions(), controller.getActions());
        // Explicit unsafe-state terminalization did not mutate the caller's game.
        assertTrue(game.getTransitions(30).getImage("unsafeLoop").contains(30));
    }

    @Test
    public void losingInitialStateReturnsNoController() {
        LTS<Integer, String> game = game(0, 1);
        edge(game, 0, "uncontrollable", 1);
        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(
                game, Collections.emptySet(), Collections.emptySet());
        assertFalse(analysis.isRealizable());
        assertEquals(Set.of(0, 1), analysis.getLosingStates());
        assertFalse(FullSafetySynthesis.generateController(analysis).isPresent());
    }

    @Test
    public void explicitlyUnsafeInitialLoopIsLosing() {
        LTS<Integer, String> game = game(0);
        edge(game, 0, "loop", 0);
        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(
                game, Set.of("loop"), Set.of(0));
        assertFalse(FullSafetySynthesis.generateController(analysis).isPresent());
    }

    @Test
    public void analysisSurvivesLaterMutationOfTheInputGame() {
        LTS<Integer, String> game = game(0, 1, 2);
        edge(game, 0, "start", 1);
        edge(game, 1, "finish", 0);
        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(
                game, Set.of("start"), Collections.emptySet());
        edge(game, 0, "fail", 2);
        game.removeTransition(1, "finish", 0);
        assertEquals(Set.of("0:start:1", "1:finish:0"),
                edges(FullSafetySynthesis.generateController(analysis).get()));
        assertEquals(Set.of(0, 1), analysis.getWinningStates());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsChoosingOnlyTheSafeOutcomeOfAControllableAction() {
        LTS<Integer, String> game = game(0, 1, 2);
        edge(game, 0, "choice", 1);
        edge(game, 0, "choice", 2);
        edge(game, 1, "safeLoop", 1);
        FullSafetySynthesis.analyze(game, Set.of("choice"), Set.of(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownUnsafeState() {
        FullSafetySynthesis.analyze(game(0), Collections.emptySet(), Set.of(42));
    }

    @Test
    public void matchesExistingStrategyExtractionOnADeterministicGame() {
        LTS<Integer, String> game = game(0, 1, 2, 3);
        edge(game, 0, "stay", 0);
        edge(game, 0, "start", 1);
        edge(game, 0, "shortcut", 1);
        edge(game, 0, "bad", 2);
        edge(game, 1, "finish", 0);
        edge(game, 1, "retry", 1);
        edge(game, 3, "unreachableLoop", 3);
        Set<String> controls = Set.of("stay", "start", "shortcut", "bad");
        SafeGameSolver<Integer, String> solver = new SafeGameSolver<>(game, controls);
        LTS<StrategyState<Integer, Integer>, String> legacy =
                GameStrategyToLTSBuilder.getInstance().buildLTSFrom(game, solver.buildStrategy());
        Set<String> legacyEdges = new HashSet<>();
        for (StrategyState<Integer, Integer> state : legacy.getStates()) {
            for (Pair<String, StrategyState<Integer, Integer>> transition : legacy.getTransitions(state)) {
                legacyEdges.add(state.getState() + ":" + transition.getFirst()
                        + ":" + transition.getSecond().getState());
            }
        }
        Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(game, controls, Set.of(2));
        assertEquals(solver.getWinningStates(), analysis.getWinningStates());
        assertEquals(legacyEdges, edges(FullSafetySynthesis.generateController(analysis).get()));
    }

    @Test
    public void exhaustivelyMatchesClosedSafeSubsetsOn256TinyGames() {
        // Two ordinary states, one terminal error, and one action of each kind.
        // Each (state, action) has no edge or one of three destinations: 4^4.
        // The oracle enumerates closed safe subsets, rather than propagating
        // losing states with the implementation's queue algorithm.
        for (int encoding = 0; encoding < 256; encoding++) {
            LTS<Integer, String> game = game(0, 1, 2);
            int choices = encoding;
            for (int state = 0; state < 2; state++) {
                for (String action : new String[] {"c", "u"}) {
                    int target = choices % 4;
                    choices /= 4;
                    if (target != 3) {
                        edge(game, state, action, target);
                    }
                }
            }
            Set<Integer> expectedWinning = new HashSet<>();
            for (int mask = 0; mask < 4; mask++) {
                Set<Integer> candidate = new HashSet<>();
                for (int state = 0; state < 2; state++) {
                    if ((mask & (1 << state)) != 0) {
                        candidate.add(state);
                    }
                }
                boolean closed = true;
                for (Integer state : candidate) {
                    boolean safeMove = false;
                    for (Pair<String, Integer> transition : game.getTransitions(state)) {
                        boolean inside = candidate.contains(transition.getSecond());
                        safeMove |= inside;
                        if (transition.getFirst().equals("u") && !inside) {
                            closed = false;
                        }
                    }
                    closed &= safeMove;
                }
                if (closed) {
                    expectedWinning.addAll(candidate);
                }
            }
            Analysis<Integer, String> analysis = FullSafetySynthesis.analyze(game, Set.of("c"), Set.of(2));
            String message = "Game encoding " + encoding;
            assertEquals(message, expectedWinning, analysis.getWinningStates());
            if (!expectedWinning.contains(0)) {
                assertFalse(message, FullSafetySynthesis.generateController(analysis).isPresent());
                continue;
            }
            Set<Integer> reached = new HashSet<>();
            Queue<Integer> pending = new ArrayDeque<>();
            Set<String> expectedEdges = new HashSet<>();
            pending.add(0);
            while (!pending.isEmpty()) {
                Integer state = pending.remove();
                if (!reached.add(state)) {
                    continue;
                }
                for (Pair<String, Integer> transition : game.getTransitions(state)) {
                    if (expectedWinning.contains(transition.getSecond())) {
                        expectedEdges.add(state + ":" + transition.getFirst() + ":" + transition.getSecond());
                        pending.add(transition.getSecond());
                    }
                }
            }
            LTS<Integer, String> controller = FullSafetySynthesis.generateController(analysis).get();
            assertEquals(message, reached, controller.getStates());
            assertEquals(message, expectedEdges, edges(controller));
        }
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
