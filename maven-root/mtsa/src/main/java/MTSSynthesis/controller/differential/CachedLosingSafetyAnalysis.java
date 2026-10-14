package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Fixed-safety analysis retaining the old losing region and propagating only new
 * losses backwards, as in the published differential requirements analysis.
 * This class neither constructs controllers nor establishes simulation.
 */
public final class CachedLosingSafetyAnalysis {
    private CachedLosingSafetyAnalysis() {
    }

    public enum Backend {
        CACHED_LOSING_PROPAGATION,
        PREDECESSOR_CLOSURE
    }

    public enum FallbackReason {
        ADDED_CONTROLLABLE_EDGE_FROM_OLD_STATE,
        OLD_SOURCE_HAS_NO_UNCONTROLLABLE_EDGE
    }

    /**
     * Reuse old losses when every effective addition from an old source is
     * uncontrollable and that source already had an uncontrollable outgoing
     * edge. New states may have either kind of edge. This is a sufficient
     * implementation guard, not a claimed explicit precondition in the paper.
     *
     * <p>The guard preserves each old finite-rank loss witness: unsafe states
     * remain unsafe; changed old states retain an old uncontrollable edge to a
     * lower-rank losing state; controllable-only states and deadlocks cannot
     * change. Consequently old losses cannot revive. If the guard fails, the
     * predecessor-closure solver handles possible revival instead.</p>
     *
     * <p>Changed old winning sources and all new states seed the worklist. A
     * newly losing state schedules its still-unclassified-as-losing predecessors.
     * Rows may be checked repeatedly when different successors lose. Old losing
     * rows are not traversed by propagation. Complete result sets still require
     * O(number of states) copying; this is not a delta-size total-time bound.</p>
     */
    public static <S, A> Outcome<S, A> analyze(SafetyGameUpdate<S, A> update) {
        Objects.requireNonNull(update, "update");
        FallbackReason reason = fallbackReason(update);
        if (reason != null) {
            return new Outcome<>(IncrementalSafetyAnalysis.analyze(update),
                    Backend.PREDECESSOR_CLOSURE, reason);
        }

        Analysis<S, A> base = update.base;
        IndexedSafetyGame<S, A> game = update.game;
        Set<S> losing = new HashSet<>(base.getLosingStates());
        Set<S> evaluated = new HashSet<>();
        Set<S> queued = new HashSet<>();
        Queue<S> pending = new ArrayDeque<>();
        for (S state : update.seeds()) {
            if (!losing.contains(state) && queued.add(state)) {
                pending.add(state);
            }
        }

        while (!pending.isEmpty()) {
            S state = pending.remove();
            queued.remove(state);
            if (losing.contains(state)) {
                continue;
            }
            evaluated.add(state);
            if (!isLosing(game, state, losing)) {
                continue;
            }
            losing.add(state);
            for (S predecessor : game.predecessors(state)) {
                if (!losing.contains(predecessor) && queued.add(predecessor)) {
                    pending.add(predecessor);
                }
            }
        }

        Set<S> winning = new HashSet<>(base.getWinningStates());
        winning.addAll(update.getAddedStates());
        winning.removeAll(losing);
        int evaluatedOldStates = evaluated.size() - update.getAddedStates().size();
        int reused = base.getStates().size() - evaluatedOldStates;
        Analysis<S, A> analyzed = new Analysis<>(game, winning, losing, update.getRevision());
        return new Outcome<>(IncrementalSafetyAnalysis.resultFor(update, analyzed, evaluated, reused),
                Backend.CACHED_LOSING_PROPAGATION, null);
    }

    private static <S, A> FallbackReason fallbackReason(SafetyGameUpdate<S, A> update) {
        Set<S> checkedSources = new HashSet<>();
        // Inspect controllability first so a batch with several violations has
        // a deterministic reason independent of the transition-set iteration.
        for (Transition<S, A> edge : update.getAddedTransitions()) {
            if (update.base.getStates().contains(edge.getSource())
                    && update.getControllableActions().contains(edge.getAction())) {
                return FallbackReason.ADDED_CONTROLLABLE_EDGE_FROM_OLD_STATE;
            }
        }
        for (Transition<S, A> edge : update.getAddedTransitions()) {
            S source = edge.getSource();
            if (update.base.getStates().contains(source) && checkedSources.add(source)) {
                boolean hasUncontrollable = false;
                for (A action : update.base.getTransitions(source).keySet()) {
                    if (!update.getControllableActions().contains(action)) {
                        hasUncontrollable = true;
                        break;
                    }
                }
                if (!hasUncontrollable) {
                    return FallbackReason.OLD_SOURCE_HAS_NO_UNCONTROLLABLE_EDGE;
                }
            }
        }
        return null;
    }

    private static <S, A> boolean isLosing(IndexedSafetyGame<S, A> game, S state, Set<S> losing) {
        if (game.unsafeStates().contains(state)) {
            return true;
        }
        boolean hasSafeSuccessor = false;
        for (Map.Entry<A, S> edge : game.outgoing(state).entrySet()) {
            if (!losing.contains(edge.getValue())) {
                hasSafeSuccessor = true;
            } else if (!game.controls().contains(edge.getKey())) {
                return true;
            }
        }
        // A deadlock or a state with only losing successors is losing.
        return !hasSafeSuccessor;
    }

    public static final class Outcome<S, A> {
        private final IncrementalSafetyAnalysis.Result<S, A> result;
        private final Backend backend;
        private final FallbackReason fallbackReason;

        private Outcome(IncrementalSafetyAnalysis.Result<S, A> result,
                Backend backend, FallbackReason fallbackReason) {
            this.result = result;
            this.backend = backend;
            this.fallbackReason = fallbackReason;
        }

        public IncrementalSafetyAnalysis.Result<S, A> getResult() {
            return result;
        }

        public Backend getBackend() {
            return backend;
        }

        /** Null when the retained-losing-region algorithm was used. */
        public FallbackReason getFallbackReason() {
            return fallbackReason;
        }
    }
}
