package MTSSynthesis.controller.differential;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable addition-only batch for one particular analysis snapshot.
 * State and action values must have stable equality and hash codes.
 * Graph-dependent validation occurs when the complete batch is applied, so
 * its declarations and transitions may be supplied in any order.
 */
public final class SafetyGameDelta<S, A> {
    private final FullSafetySynthesis.Analysis<S, A> base;
    private final Set<S> addedStates;
    private final Set<S> addedUnsafeStates;
    private final Set<Transition<S, A>> addedTransitions;

    private SafetyGameDelta(Builder<S, A> builder) {
        base = builder.base;
        addedStates = Collections.unmodifiableSet(new LinkedHashSet<>(builder.addedStates));
        addedUnsafeStates = Collections.unmodifiableSet(new LinkedHashSet<>(builder.addedUnsafeStates));
        addedTransitions = Collections.unmodifiableSet(new LinkedHashSet<>(builder.addedTransitions));
    }

    public static <S, A> Builder<S, A> forAnalysis(FullSafetySynthesis.Analysis<S, A> base) {
        return new Builder<>(Objects.requireNonNull(base, "base"));
    }

    FullSafetySynthesis.Analysis<S, A> base() {
        return base;
    }

    public Set<S> getAddedStates() {
        return addedStates;
    }

    public Set<S> getAddedUnsafeStates() {
        return addedUnsafeStates;
    }

    /** Requested edges, with exact duplicates within this batch removed. */
    public Set<Transition<S, A>> getAddedTransitions() {
        return addedTransitions;
    }

    public static final class Builder<S, A> {
        private final FullSafetySynthesis.Analysis<S, A> base;
        private final Set<S> addedStates = new LinkedHashSet<>();
        private final Set<S> addedUnsafeStates = new LinkedHashSet<>();
        private final Set<Transition<S, A>> addedTransitions = new LinkedHashSet<>();

        private Builder(FullSafetySynthesis.Analysis<S, A> base) {
            this.base = base;
        }

        public Builder<S, A> addState(S state) {
            addedStates.add(Objects.requireNonNull(state, "state"));
            return this;
        }

        /** Declare a new unsafe state; existing states cannot be reclassified. */
        public Builder<S, A> addUnsafeState(S state) {
            Objects.requireNonNull(state, "state");
            addedStates.add(state);
            addedUnsafeStates.add(state);
            return this;
        }

        public Builder<S, A> addTransition(S source, A action, S target) {
            addedTransitions.add(new Transition<>(source, action, target));
            return this;
        }

        /** Later modifications of this builder do not change a built batch. */
        public SafetyGameDelta<S, A> build() {
            return new SafetyGameDelta<>(this);
        }
    }

    public static final class Transition<S, A> {
        private final S source;
        private final A action;
        private final S target;

        public Transition(S source, A action, S target) {
            this.source = Objects.requireNonNull(source, "source");
            this.action = Objects.requireNonNull(action, "action");
            this.target = Objects.requireNonNull(target, "target");
        }

        public S getSource() {
            return source;
        }

        public A getAction() {
            return action;
        }

        public S getTarget() {
            return target;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Transition<?, ?>)) {
                return false;
            }
            Transition<?, ?> edge = (Transition<?, ?>) other;
            return source.equals(edge.source) && action.equals(edge.action) && target.equals(edge.target);
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, action, target);
        }

        @Override
        public String toString() {
            return source + " --" + action + "--> " + target;
        }
    }
}
