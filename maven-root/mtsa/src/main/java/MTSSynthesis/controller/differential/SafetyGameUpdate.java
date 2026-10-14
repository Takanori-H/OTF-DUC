package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;
import MTSTools.ac.ic.doc.mtstools.model.LTS;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, validated game update before either full or incremental analysis.
 * Applying a batch changes the graph and reverse indexes, but does not classify
 * any state or inspect the base winning/losing regions. The same update can be
 * passed to both analysis algorithms without applying the batch twice.
 *
 * <p>This preparation object retains its base analysis for incremental reuse and
 * change reporting. Completed analysis results retain only the independent base
 * identity token, not this object or a chain of old analyses.</p>
 */
public final class SafetyGameUpdate<S, A> {
    final Analysis<S, A> base;
    final IndexedSafetyGame<S, A> game;
    private final long revision;
    private final Set<S> addedStates;
    private final Set<S> addedUnsafeStates;
    private final Set<Transition<S, A>> addedTransitions;
    private final Set<S> changedOldSources;
    private final Set<S> seeds;

    private SafetyGameUpdate(Analysis<S, A> base, IndexedSafetyGame<S, A> game,
                             long revision, SafetyGameDelta<S, A> delta) {
        this.base = base;
        this.game = game;
        this.revision = revision;
        this.addedStates = delta.getAddedStates();
        this.addedUnsafeStates = delta.getAddedUnsafeStates();
        Set<Transition<S, A>> effective = new HashSet<>();
        Set<S> changedOld = new HashSet<>();
        Set<S> changed = new HashSet<>(addedStates);
        for (Transition<S, A> edge : delta.getAddedTransitions()) {
            boolean oldSource = base.game.states().contains(edge.getSource());
            if (!oldSource
                    || !Objects.equals(base.game.outgoing(edge.getSource()).get(edge.getAction()),
                            edge.getTarget())) {
                effective.add(edge);
                changed.add(edge.getSource());
                if (oldSource) {
                    changedOld.add(edge.getSource());
                }
            }
        }
        this.addedTransitions = Collections.unmodifiableSet(effective);
        this.changedOldSources = Collections.unmodifiableSet(changedOld);
        this.seeds = Collections.unmodifiableSet(changed);
    }

    /**
     * Apply a complete addition-only batch atomically to its exact base analysis.
     * Empty and duplicate-edge-only updates still advance the revision. State
     * identities, the initial state, old unsafe classifications and action
     * controllability retain the existing addition-only API contract.
     */
    public static <S, A> SafetyGameUpdate<S, A> apply(
            Analysis<S, A> base, SafetyGameDelta<S, A> delta) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(delta, "delta");
        if (delta.base() != base) {
            throw new IllegalArgumentException("The delta belongs to a different analysis version.");
        }
        long revision = Math.addExact(base.getRevision(), 1L);
        IndexedSafetyGame<S, A> updated = base.game.apply(delta);
        return new SafetyGameUpdate<>(base, updated, revision, delta);
    }

    public long getRevision() {
        return revision;
    }

    public S getInitialState() {
        return game.initialState();
    }

    public Set<S> getStates() {
        return game.states();
    }

    public Set<A> getActions() {
        return game.actions();
    }

    public Set<A> getControllableActions() {
        return game.controls();
    }

    public Set<S> getUnsafeStates() {
        return game.unsafeStates();
    }

    /** Immutable outgoing row; an unknown state is rejected. */
    public Map<A, S> getTransitions(S state) {
        return game.outgoing(state);
    }

    public Set<S> getAddedStates() {
        return addedStates;
    }

    public Set<S> getAddedUnsafeStates() {
        return addedUnsafeStates;
    }

    /** Effective additions, excluding transitions already present in the base. */
    public Set<Transition<S, A>> getAddedTransitions() {
        return addedTransitions;
    }

    /**
     * Old game states with an effective outgoing-edge addition. New-state
     * sources and duplicate-only additions are excluded. Sources outside the
     * old reachable controller are included; generation selects its own subset.
     * Collected during game-update validation, without a later delta-edge scan.
     */
    public Set<S> getChangedOldSources() {
        return changedOldSources;
    }

    /** A mutable, independent copy of the updated game, with no analysis applied. */
    public LTS<S, A> copyGame() {
        return game.copyLts();
    }

    Set<S> seeds() {
        return seeds;
    }
}
