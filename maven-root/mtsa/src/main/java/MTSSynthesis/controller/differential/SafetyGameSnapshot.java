package MTSSynthesis.controller.differential;

import MTSTools.ac.ic.doc.mtstools.model.LTS;

import java.util.Map;
import java.util.Set;

/**
 * Owned immutable safety game prepared independently of winning-region analysis.
 * Construct with {@link FullSafetySynthesis#prepare}; all exposed collections
 * are immutable. State/action values must retain stable equality and hash codes.
 */
public final class SafetyGameSnapshot<S, A> {
    final IndexedSafetyGame<S, A> game;

    SafetyGameSnapshot(IndexedSafetyGame<S, A> game) {
        this.game = game;
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

    /** Immutable outgoing row; unknown states are rejected. */
    public Map<A, S> getTransitions(S state) {
        return game.outgoing(state);
    }

    /** Independent mutable LTS for inspection; not needed for analysis. */
    public LTS<S, A> copyGame() {
        return game.copyLts();
    }
}
