package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.*;

public class DifferentialSafetyGameSolverTest {
    @Test
    public void reconvergingLosingPathsDoNotStopPredecessorPropagation() {
        LTS<Long, String> game = new LTSImpl<>(0L);
        edge(game, 0L, "enter", 1L);
        edge(game, 1L, "left", 2L);
        edge(game, 1L, "right", 3L);
        edge(game, 2L, "failLeft", 4L);
        edge(game, 3L, "failRight", 4L);

        DifferentialSafetyGameSolver<Long, String> solver =
                new DifferentialSafetyGameSolver<>(game, Collections.emptySet());
        solver.solveGame();
        // Both branches discover state 1; propagation must still reach state 0.
        assertTrue(solver.getWinningStates().isEmpty());

        Analysis<Long, String> analysis = FullSafetySynthesis.analyze(
                game, Collections.emptySet(), Collections.emptySet());
        assertEquals(game.getStates(), analysis.getLosingStates());
        assertFalse(analysis.isRealizable());
        assertFalse(FullSafetySynthesis.generateController(analysis).isPresent());
    }

    @Test
    public void repeatedSolveAndGenerationPreserveTheSafeController() {
        LTS<Long, String> game = new LTSImpl<>(0L);
        edge(game, 0L, "idle", 0L);
        edge(game, 0L, "start", 1L);
        edge(game, 1L, "finish", 0L);
        edge(game, 1L, "fail", 2L);
        Set<String> controls = Set.of("idle", "start");
        DifferentialSafetyGameSolver<Long, String> solver =
                new DifferentialSafetyGameSolver<>(game, controls);

        solver.solveGame();
        assertEquals(Set.of(0L), solver.getWinningStates());
        solver.solveGame();
        assertEquals(Set.of(0L), solver.getWinningStates());

        Analysis<Long, String> analysis = FullSafetySynthesis.analyze(
                game, controls, Collections.emptySet());
        assertEquals(solver.getWinningStates(), analysis.getWinningStates());
        assertEquals(Set.of(1L, 2L), analysis.getLosingStates());
        for (int repetition = 0; repetition < 2; repetition++) {
            LTS<Long, String> controller = FullSafetySynthesis.generateController(analysis).get();
            assertEquals(Set.of(0L), controller.getStates());
            assertEquals(1, controller.getTransitions(0L).size());
            assertEquals(Set.of(0L), controller.getTransitions(0L).getImage("idle"));
        }
        assertEquals(Set.of(1L), game.getTransitions(0L).getImage("start"));
    }

    private static void edge(LTS<Long, String> game, Long source, String action, Long target) {
        game.addState(source);
        game.addState(target);
        game.addAction(action);
        game.addTransition(source, action, target);
    }
}
