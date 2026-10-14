package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

/**
 * Addition-only game updates and local safety analysis, with fixed requirements.
 * This does not update a controller or establish simulation/hotswap compatibility.
 */
public final class IncrementalSafetyAnalysis {
    private IncrementalSafetyAnalysis() {
    }

    /**
     * Apply a complete batch atomically to the exact analysis it was prepared for.
     * Old state identities, unsafe classifications and controllability are fixed.
     * Both controllable and uncontrollable additions are supported; new states
     * must be declared explicitly. No deletion or requirement relaxation occurs.
     *
     * <p>The reanalysis region is the predecessor closure of changed sources and
     * new states in the updated game. Its complement cannot reach the region and
     * retains its old classification. The region is solved from a tentative all-
     * winning set, using that complement as a fixed boundary. This is necessary
     * because adding edges can remove deadlocks and create winning cycles, so
     * retaining all old losing states would be unsound.</p>
     *
     * <p>Only this region's transitions are analyzed. Snapshot maps and result
     * sets still require O(number of states) copying; the region can be the whole
     * game. This API makes no bound on speedup or on the size of the region.</p>
     */
    public static <S, A> Result<S, A> update(Analysis<S, A> base, SafetyGameDelta<S, A> delta) {
        return analyze(SafetyGameUpdate.apply(base, delta));
    }

    /**
     * Analyze a previously applied game update, reusing classifications outside
     * the predecessor closure. Game mutation/validation is a separate stage so
     * that this path and the full-analysis baseline can share the same input.
     */
    public static <S, A> Result<S, A> analyze(SafetyGameUpdate<S, A> update) {
        Objects.requireNonNull(update, "update");
        Analysis<S, A> base = update.base;
        IndexedSafetyGame<S, A> game = update.game;
        Set<S> affected = predecessorClosure(game, update.seeds());
        Set<S> localLosing = new HashSet<>();
        Map<S, Integer> remainingSafeTargets = new HashMap<>();
        Queue<S> pending = new ArrayDeque<>();

        for (S state : affected) {
            Set<S> safeTargets = new HashSet<>();
            boolean forcedLoss = false;
            for (Map.Entry<A, S> edge : game.outgoing(state).entrySet()) {
                S target = edge.getValue();
                boolean tentativeWinning = affected.contains(target)
                        || base.getWinningStates().contains(target);
                if (tentativeWinning) {
                    safeTargets.add(target);
                } else if (!game.controls().contains(edge.getKey())) {
                    forcedLoss = true;
                }
            }
            remainingSafeTargets.put(state, safeTargets.size());
            // This also handles controllable-only edges to the losing boundary.
            if (game.unsafeStates().contains(state) || forcedLoss || safeTargets.isEmpty()) {
                localLosing.add(state);
                pending.add(state);
            }
        }

        while (!pending.isEmpty()) {
            S lost = pending.remove();
            for (S predecessor : game.predecessors(lost)) {
                if (!affected.contains(predecessor) || localLosing.contains(predecessor)) {
                    continue;
                }
                // The predecessor index and counter both count distinct targets,
                // not actions: several labels to the same target decrement once.
                int remaining = remainingSafeTargets.get(predecessor) - 1;
                remainingSafeTargets.put(predecessor, remaining);
                if (remaining == 0 || game.uncontrollablePredecessors(lost).contains(predecessor)) {
                    localLosing.add(predecessor);
                    pending.add(predecessor);
                }
            }
        }

        Set<S> winning = new HashSet<>(base.getWinningStates());
        winning.removeAll(affected);
        for (S state : affected) {
            if (!localLosing.contains(state)) {
                winning.add(state);
            }
        }
        Set<S> losing = new HashSet<>(base.getLosingStates());
        losing.removeAll(affected);
        losing.addAll(localLosing);

        int reused = base.game.states().size() - (affected.size() - update.getAddedStates().size());
        Analysis<S, A> updated = new Analysis<>(game, winning, losing, update.getRevision());
        return resultFor(update, updated, affected, reused);
    }

    /**
     * Attach shared provenance and change metadata after classification finishes.
     * Full analysis reads old W/L only here, to report changes, never to solve.
     */
    static <S, A> Result<S, A> resultFor(SafetyGameUpdate<S, A> update,
            Analysis<S, A> analyzed, Set<S> reanalyzed, int reused) {
        Set<S> newlyWinning = new HashSet<>(update.base.getLosingStates());
        newlyWinning.retainAll(analyzed.getWinningStates());
        Set<S> newlyLosing = new HashSet<>(update.base.getWinningStates());
        newlyLosing.retainAll(analyzed.getLosingStates());
        return new Result<>(update.base.versionIdentity, analyzed, reanalyzed,
                newlyWinning, newlyLosing, update.getAddedStates(),
                update.getAddedTransitions(), update.getChangedOldSources(), reused);
    }

    private static <S, A> Set<S> predecessorClosure(IndexedSafetyGame<S, A> game, Set<S> seeds) {
        Set<S> affected = new HashSet<>(seeds);
        Queue<S> pending = new ArrayDeque<>(seeds);
        while (!pending.isEmpty()) {
            for (S predecessor : game.predecessors(pending.remove())) {
                if (affected.add(predecessor)) {
                    pending.add(predecessor);
                }
            }
        }
        return affected;
    }

    /** Analysis and explicit change information for later controller generation. */
    public static final class Result<S, A> {
        private final Object baseVersionIdentity;
        private final Analysis<S, A> analysis;
        private final Set<S> affectedStates;
        private final Set<S> newlyWinningStates;
        private final Set<S> newlyLosingStates;
        private final Set<S> addedStates;
        private final Set<Transition<S, A>> addedTransitions;
        private final Set<S> changedOldSources;
        private final int reusedStateCount;

        private Result(Object baseVersionIdentity, Analysis<S, A> analysis, Set<S> affectedStates,
                Set<S> newlyWinningStates, Set<S> newlyLosingStates, Set<S> addedStates,
                Set<Transition<S, A>> addedTransitions, Set<S> changedOldSources,
                int reusedStateCount) {
            this.baseVersionIdentity = baseVersionIdentity;
            this.analysis = analysis;
            this.affectedStates = immutable(affectedStates);
            this.newlyWinningStates = immutable(newlyWinningStates);
            this.newlyLosingStates = immutable(newlyLosingStates);
            this.addedStates = immutable(addedStates);
            this.addedTransitions = immutable(addedTransitions);
            this.changedOldSources = immutable(changedOldSources);
            this.reusedStateCount = reusedStateCount;
        }

        boolean hasBase(Analysis<S, A> base) {
            return baseVersionIdentity == base.versionIdentity;
        }

        public Analysis<S, A> getAnalysis() {
            return analysis;
        }

        /**
         * States reanalyzed: checked rows for retained-loss propagation, the
         * conservative region for predecessor-closure analysis, or all updated-
         * game states for full analysis. Not only W/L changes.
         */
        public Set<S> getAffectedStates() {
            return affectedStates;
        }

        /** Old losing states that became winning; newly introduced states are separate. */
        public Set<S> getNewlyWinningStates() {
            return newlyWinningStates;
        }

        /** Old winning states that became losing; newly introduced states are separate. */
        public Set<S> getNewlyLosingStates() {
            return newlyLosingStates;
        }

        public Set<S> getAddedStates() {
            return addedStates;
        }

        /** Effective additions, excluding edges already present in the base game. */
        public Set<Transition<S, A>> getAddedTransitions() {
            return addedTransitions;
        }

        /**
         * Old game sources of effective added edges, including states outside
         * the old controller. This metadata is independent of W/L changes and
         * excludes new-state sources and duplicate-only edge additions.
         */
        public Set<S> getChangedOldSources() {
            return changedOldSources;
        }

        /** Old states whose classification was reused; always zero for full analysis. */
        public int getReusedStateCount() {
            return reusedStateCount;
        }

        private static <T> Set<T> immutable(Set<T> values) {
            return Collections.unmodifiableSet(new HashSet<>(values));
        }
    }
}
