package MTSSynthesis.controller.differential;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Full-generation baseline for fixed-requirement differential synthesis.
 *
 * <p>The input is an already composed, label-deterministic safety game. Unsafe
 * states and deadlocks are losing; no fairness or liveness condition is added.
 * Analysis and controller generation are separate operations so that a future
 * differential generator can be compared against the same analysis result.
 * This class performs neither incremental analysis nor controller updates.</p>
 */
public final class FullSafetySynthesis {
    private FullSafetySynthesis() {
    }

    /**
     * Construct a new owned game and its reverse indexes, without analyzing it.
     * Each input row identifies one state, including terminal states with an
     * empty row. All transition endpoints and labels must be declared. Unsafe
     * states are terminalized in the owned copy. All input collections may be
     * mutated after this method returns; state/action values themselves must
     * retain stable equality and hash codes.
     *
     * <p>This entry point separates game construction from full analysis for
     * phase measurements. It constructs fresh reverse indexes, without old
     * winning classifications or intermediate LTS/SafeGame copies. Already
     * immutable input rows may be shared; mutable rows are defensively copied.
     * The environment-level FULL baseline supplies a freshly composed product,
     * independently of the old product.</p>
     */
    public static <S, A> SafetyGameSnapshot<S, A> prepare(
            S initialState, Map<S, Map<A, S>> rows, Set<A> actions,
            Set<A> controllableActions, Set<S> unsafeStates) {
        return new SafetyGameSnapshot<>(IndexedSafetyGame.fromRows(
                initialState, rows, actions, controllableActions, unsafeStates));
    }

    /**
     * Analyze every state of a prepared game from scratch. Preparation/indexing
     * is intentionally outside this operation; initialization, losing-region
     * propagation and construction of the returned W/L sets are inside it.
     * Unsafe states and deadlocks lose. Any uncontrollable edge to a losing
     * state forces loss; otherwise a state loses when no safe successor remains.
     * Several labels to one successor count once, matching the reverse index.
     */
    public static <S, A> Analysis<S, A> analyze(SafetyGameSnapshot<S, A> snapshot) {
        IndexedSafetyGame<S, A> game = Objects.requireNonNull(snapshot, "snapshot").game;
        Map<S, Integer> remainingSafeTargets = new HashMap<>();
        Set<S> losing = new HashSet<>();
        Queue<S> pending = new ArrayDeque<>();
        for (S state : game.states()) {
            int targetCount = new HashSet<>(game.outgoing(state).values()).size();
            remainingSafeTargets.put(state, targetCount);
            if (game.unsafeStates().contains(state) || targetCount == 0) {
                losing.add(state);
                pending.add(state);
            }
        }
        while (!pending.isEmpty()) {
            S lost = pending.remove();
            for (S predecessor : game.predecessors(lost)) {
                if (losing.contains(predecessor)) {
                    continue;
                }
                int remaining = remainingSafeTargets.get(predecessor) - 1;
                remainingSafeTargets.put(predecessor, remaining);
                if (remaining == 0 || game.uncontrollablePredecessors(lost).contains(predecessor)) {
                    losing.add(predecessor);
                    pending.add(predecessor);
                }
            }
        }
        Set<S> winning = new HashSet<>(game.states());
        winning.removeAll(losing);
        return new Analysis<>(game, winning, losing, 0L);
    }

    /**
     * Analyze a private snapshot, preserving the caller's state identities.
     * Unsafe states are terminalized in the snapshot, never in the input.
     * State/action values must retain stable equality and hash codes.
     *
     * <p>Nondeterministic action outcomes are deliberately rejected: the
     * dedicated safety solver works with successor states, not action groups,
     * and cannot safely choose between outcomes of one controllable action.</p>
     */
    public static <S, A> Analysis<S, A> analyze(
            LTS<S, A> game, Set<A> controllableActions, Set<S> unsafeStates) {
        return analyze(game, controllableActions, unsafeStates, 0L);
    }

    private static <S, A> Analysis<S, A> analyze(
            LTS<S, A> game, Set<A> controllableActions, Set<S> unsafeStates, long revision) {
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(controllableActions, "controllableActions");
        Objects.requireNonNull(unsafeStates, "unsafeStates");
        if (!game.getStates().containsAll(unsafeStates)) {
            throw new IllegalArgumentException("Unsafe states must belong to the game.");
        }

        LTS<S, A> snapshot = new LTSImpl<>(game.getInitialState());
        snapshot.addStates(game.getStates());
        snapshot.addActions(game.getActions());
        for (S state : game.getStates()) {
            Set<A> enabled = new HashSet<>();
            for (Pair<A, S> edge : game.getTransitions(state)) {
                if (!enabled.add(edge.getFirst())) {
                    throw new IllegalArgumentException(
                            "The full safety baseline requires a label-deterministic LTS; "
                                    + "multiple outcomes at state " + state
                                    + " for action " + edge.getFirst());
                }
                if (!unsafeStates.contains(state)) {
                    snapshot.addTransition(state, edge.getFirst(), edge.getSecond());
                }
            }
        }

        Set<A> controls = new HashSet<>(controllableActions);
        DifferentialSafetyGameSolver<S, A> solver =
                new DifferentialSafetyGameSolver<>(snapshot, controls);
        Set<S> winning = solver.getWinningStates();
        Set<S> losing = new HashSet<>(snapshot.getStates());
        losing.removeAll(winning);
        return new Analysis<>(IndexedSafetyGame.fromSnapshot(snapshot, controls, unsafeStates),
                winning, losing, revision);
    }

    /**
     * Analyze the whole updated game from scratch, without reusing old W/L.
     * The graph update is common preparation; this analysis stage materializes
     * an independent LTS and runs the ordinary full safety-analysis path,
     * including its fresh snapshot, solver and reverse-index construction.
     *
     * <p>The completed result is bound to the update's base identity and new
     * revision so it can pass through the same controller-compatibility gate.
     * Old W/L are consulted only afterwards to report newly winning/losing
     * old states. All updated states are reported as reanalyzed, with no reused
     * classifications, even when the game update is empty.</p>
     */
    public static <S, A> IncrementalSafetyAnalysis.Result<S, A> analyzeUpdate(
            SafetyGameUpdate<S, A> update) {
        Objects.requireNonNull(update, "update");
        Analysis<S, A> fresh = analyze(update.copyGame(),
                update.getControllableActions(), update.getUnsafeStates(), update.getRevision());
        return IncrementalSafetyAnalysis.resultFor(update, fresh, update.getStates(), 0);
    }

    /**
     * Extract all winning-to-winning transitions reachable from the initial
     * state. The alphabet and state IDs are retained; states are not minimized
     * or renumbered. A losing initial state yields no controller.
     */
    public static <S, A> Optional<LTS<S, A>> generateController(Analysis<S, A> analysis) {
        return generateControllerSnapshot(analysis).map(SafetyController::copyLts);
    }

    /** Full extraction with an immutable cache suitable for later controller updates. */
    public static <S, A> Optional<SafetyController<S, A>> generateControllerSnapshot(
            Analysis<S, A> analysis) {
        return SafetyController.full(Objects.requireNonNull(analysis, "analysis"));
    }

    /**
     * Opt-in internal generation timings and work counters. The underlying full
     * extraction and output representation are the same as the unprofiled API.
     * An unrealizable analysis produces neither a controller nor a profile.
     */
    public static <S, A> ProfiledController<S, A> generateControllerSnapshotProfiled(
            Analysis<S, A> analysis) {
        Objects.requireNonNull(analysis, "analysis");
        if (!analysis.isRealizable()) {
            return new ProfiledController<>(Optional.empty(), null);
        }
        ControllerGenerationProfile.Collector profile = new ControllerGenerationProfile.Collector();
        Optional<SafetyController<S, A>> controller = SafetyController.full(analysis, profile);
        return new ProfiledController<>(controller, profile.finish());
    }

    public static final class ProfiledController<S, A> {
        private final Optional<SafetyController<S, A>> controller;
        private final ControllerGenerationProfile profile;

        private ProfiledController(Optional<SafetyController<S, A>> controller,
                                   ControllerGenerationProfile profile) {
            this.controller = controller;
            this.profile = profile;
        }

        public Optional<SafetyController<S, A>> getController() {
            return controller;
        }

        public Optional<ControllerGenerationProfile> getGenerationProfile() {
            return Optional.ofNullable(profile);
        }
    }

    /**
     * Immutable analysis of one game version. Updates retain the same safety
     * interpretation and controllability, and produce another owned snapshot.
     * Revisions identify a branch depth; delta applicability uses exact analysis
     * identity, not the revision number alone.
     */
    public static final class Analysis<S, A> {
        final IndexedSafetyGame<S, A> game;
        // A small independent token lets update results prove their provenance
        // without retaining the entire previous analysis and game.
        final Object versionIdentity = new Object();
        private final Set<S> winningStates;
        private final Set<S> losingStates;
        private final long revision;

        Analysis(IndexedSafetyGame<S, A> game, Set<S> winningStates,
                Set<S> losingStates, long revision) {
            this.game = game;
            this.revision = revision;
            this.winningStates = Collections.unmodifiableSet(new HashSet<>(winningStates));
            this.losingStates = Collections.unmodifiableSet(new HashSet<>(losingStates));
        }

        public S getInitialState() {
            return game.initialState();
        }

        public long getRevision() {
            return revision;
        }

        public Set<A> getControllableActions() {
            return game.controls();
        }

        public Set<S> getUnsafeStates() {
            return game.unsafeStates();
        }

        /** Immutable game view for trace resolution without copying the entire graph. */
        public Set<S> getStates() {
            return game.states();
        }

        public Set<A> getActions() {
            return game.actions();
        }

        /** Immutable outgoing row; an unknown state is rejected. */
        public Map<A, S> getTransitions(S state) {
            return game.outgoing(state);
        }

        /** A defensive materialization for inspection or independent full analysis. */
        public LTS<S, A> copyGame() {
            return game.copyLts();
        }

        public Set<S> getWinningStates() {
            return winningStates;
        }

        public Set<S> getLosingStates() {
            return losingStates;
        }

        public boolean isRealizable() {
            return winningStates.contains(game.initialState());
        }
    }
}
