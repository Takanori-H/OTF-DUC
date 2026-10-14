package MTSSynthesis.controller.model;

import MTSSynthesis.controller.gr.StrategyState;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SafeGameSolverRegressionTest {

    @Test
    public void duplicatePendingStateDoesNotStopLosingPropagation() {
        LTS<Long, String> game = new LTSImpl<>(0L);
        edge(game, 0L, "enter", 1L);
        edge(game, 1L, "left", 2L);
        edge(game, 1L, "right", 3L);
        edge(game, 2L, "failLeft", 4L);
        edge(game, 3L, "failRight", 4L);

        SafeGameSolver<Long, String> solver =
                new SafeGameSolver<>(game, Collections.emptySet());
        solver.solveGame();

        // Both branches enqueue state 1. Its duplicate must not prevent the
        // still-pending predecessor 0 from being marked losing.
        assertEquals(Collections.emptySet(), solver.getWinningStates());
        assertFalse(solver.isWinning(0L));
        assertEquals(Collections.emptySet(), solver.buildStrategy().getStates());
    }

    @Test
    public void repeatedSolvePreservesWinningRegionAndSafeStrategy() {
        LTS<Long, String> game = new LTSImpl<>(0L);
        edge(game, 0L, "idle", 0L);
        edge(game, 0L, "start", 1L);
        edge(game, 1L, "finish", 0L);
        edge(game, 1L, "fail", 2L);
        Set<String> controllable = new HashSet<>(Arrays.asList("idle", "start"));
        SafeGameSolver<Long, String> solver = new SafeGameSolver<>(game, controllable);
        StrategyState<Long, Integer> safeState = new StrategyState<>(0L, 1);

        for (int repetition = 0; repetition < 3; repetition++) {
            solver.solveGame();
            assertEquals(Collections.singleton(0L), solver.getWinningStates());
            assertFalse(solver.isWinning(1L));
            assertFalse(solver.isWinning(2L));
            Strategy<Long, Integer> strategy = solver.buildStrategy();
            assertEquals(Collections.singleton(safeState), strategy.getStates());
            assertEquals(Collections.singleton(safeState), strategy.getSuccessors(safeState));
        }

        assertEquals(Collections.singleton(1L), game.getTransitions(0L).getImage("start"));
    }

    private static void edge(LTS<Long, String> game, Long source, String action, Long target) {
        game.addState(source);
        game.addState(target);
        game.addAction(action);
        game.addTransition(source, action, target);
    }
}
