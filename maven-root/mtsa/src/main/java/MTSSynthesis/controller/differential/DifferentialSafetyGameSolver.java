package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.safe.SafeGame;
import MTSTools.ac.ic.doc.mtstools.model.LTS;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Full safety analysis used only by the differential-synthesis baseline.
 * The caller supplies an owned, fixed game with unsafe states terminalized.
 * No input transitions or shared MTSA solver behavior are changed here.
 */
final class DifferentialSafetyGameSolver<S, A> {
    private final SafeGame<S, A> game;
    private Set<S> winningStates;

    DifferentialSafetyGameSolver(LTS<S, A> game, Set<A> controllableActions) {
        this.game = new SafeGame<>(game, new HashSet<>(controllableActions));
    }

    void solveGame() {
        if (winningStates != null) {
            return;
        }
        Set<S> losing = new HashSet<>();
        Queue<S> pending = new ArrayDeque<>();
        for (S state : game.getStates()) {
            if (game.getSuccessors(state).isEmpty()) {
                losing.add(state);
                pending.add(state);
            }
        }

        while (!pending.isEmpty()) {
            S lost = pending.remove();
            for (S predecessor : game.getPredecessors(lost)) {
                if (losing.contains(predecessor)) {
                    continue;
                }
                boolean forcedLoss = false;
                // Scan local successors, not the growing global losing set.
                for (S successor : game.getUncontrollableSuccessors(predecessor)) {
                    if (losing.contains(successor)) {
                        forcedLoss = true;
                        break;
                    }
                }
                boolean noSafeSuccessor = losing.containsAll(game.getSuccessors(predecessor));
                if (forcedLoss || noSafeSuccessor) {
                    // Mark on enqueue: reconverging paths cannot enqueue a state
                    // twice or interrupt propagation to its own predecessors.
                    losing.add(predecessor);
                    pending.add(predecessor);
                }
            }
        }

        Set<S> winning = new HashSet<>(game.getStates());
        winning.removeAll(losing);
        winningStates = Collections.unmodifiableSet(winning);
    }

    Set<S> getWinningStates() {
        solveGame();
        return winningStates;
    }
}
