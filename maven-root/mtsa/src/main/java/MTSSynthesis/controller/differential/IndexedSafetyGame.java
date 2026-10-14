package MTSSynthesis.controller.differential;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, label-deterministic game with reverse-edge indexes.
 * Updates copy outer maps and changed rows, sharing all unchanged rows.
 * State and action values must retain stable equality and hash codes.
 */
final class IndexedSafetyGame<S, A> {
    private final S initialState;
    private final Set<S> states;
    private final Set<A> actions;
    private final Set<A> controls;
    private final Set<S> unsafeStates;
    private final Map<S, Map<A, S>> outgoing;
    private final Map<S, Set<S>> predecessors;
    private final Map<S, Set<S>> uncontrollablePredecessors;

    /** All collections passed here are owned immutable collections. */
    private IndexedSafetyGame(S initialState, Set<S> states, Set<A> actions,
                              Set<A> controls, Set<S> unsafeStates,
                              Map<S, Map<A, S>> outgoing,
                              Map<S, Set<S>> predecessors,
                              Map<S, Set<S>> uncontrollablePredecessors) {
        this.initialState = initialState;
        this.states = states;
        this.actions = actions;
        this.controls = controls;
        this.unsafeStates = unsafeStates;
        this.outgoing = outgoing;
        this.predecessors = predecessors;
        this.uncontrollablePredecessors = uncontrollablePredecessors;
    }

    /** Build fresh owned rows/indexes directly, without intermediate LTS copies. */
    static <S, A> IndexedSafetyGame<S, A> fromRows(
            S initialState, Map<S, Map<A, S>> rows, Set<A> alphabet,
            Set<A> controls, Set<S> unsafe) {
        Objects.requireNonNull(rows, "rows");
        S initial = Objects.requireNonNull(initialState, "initial state");
        Set<S> states = DenseStateStorage.immutableStateCopy(rows.keySet());
        Set<A> actions = immutableSetCopy(alphabet, "action");
        Set<A> controlSet = immutableSetCopy(controls, "controllable action");
        Set<S> unsafeSet = immutableSetCopy(unsafe, "unsafe state");
        if (!states.contains(initial)) {
            throw new IllegalArgumentException("The initial state must belong to the game.");
        }
        if (!states.containsAll(unsafeSet)) {
            throw new IllegalArgumentException("Unsafe states must belong to the game.");
        }
        Map<S, Map<A, S>> outgoing = DenseStateStorage.mutableMapFor(states);
        Map<S, Set<S>> predecessors = DenseStateStorage.mutableMapFor(states);
        Map<S, Set<S>> uncontrollablePredecessors = DenseStateStorage.mutableMapFor(states);
        for (S state : states) {
            predecessors.put(state, new HashSet<>());
            uncontrollablePredecessors.put(state, new HashSet<>());
        }
        for (S source : states) {
            Map<A, S> input = Objects.requireNonNull(rows.get(source), "outgoing row");
            boolean terminal = unsafeSet.contains(source);
            for (Map.Entry<A, S> edge : input.entrySet()) {
                A action = Objects.requireNonNull(edge.getKey(), "action");
                S target = Objects.requireNonNull(edge.getValue(), "target");
                if (!states.contains(target) || !actions.contains(action)) {
                    throw new IllegalArgumentException("A transition has an unknown target or action.");
                }
                // Validate unsafe rows too, but never retain their transitions.
                if (!terminal) {
                    predecessors.get(target).add(source);
                    if (!controlSet.contains(action)) {
                        uncontrollablePredecessors.get(target).add(source);
                    }
                }
            }
            // Defensive for mutable inputs; the JDK can reuse an already
            // immutable product row without another forward-edge table.
            outgoing.put(source, terminal ? Map.of() : Map.copyOf(input));
        }
        freezePredecessorRows(predecessors, uncontrollablePredecessors);
        return new IndexedSafetyGame<>(initial, states, actions, controlSet, unsafeSet,
                Collections.unmodifiableMap(outgoing), Collections.unmodifiableMap(predecessors),
                Collections.unmodifiableMap(uncontrollablePredecessors));
    }

    static <S, A> IndexedSafetyGame<S, A> fromSnapshot(
            LTS<S, A> terminalized, Set<A> controls, Set<S> unsafe) {
        Objects.requireNonNull(terminalized, "terminalized");
        S initial = Objects.requireNonNull(terminalized.getInitialState(), "initial state");
        Set<S> states = DenseStateStorage.immutableStateCopy(terminalized.getStates());
        Set<A> actions = immutableSetCopy(terminalized.getActions(), "action");
        Set<A> controlSet = immutableSetCopy(controls, "controllable action");
        Set<S> unsafeSet = immutableSetCopy(unsafe, "unsafe state");
        if (!states.contains(initial)) {
            throw new IllegalArgumentException("The initial state must belong to the game.");
        }
        if (!states.containsAll(unsafeSet)) {
            throw new IllegalArgumentException("Unsafe states must belong to the game.");
        }

        Map<S, Map<A, S>> outgoing = DenseStateStorage.mutableMapFor(states);
        Map<S, Set<S>> predecessors = DenseStateStorage.mutableMapFor(states);
        Map<S, Set<S>> uncontrollablePredecessors = DenseStateStorage.mutableMapFor(states);
        for (S state : states) {
            outgoing.put(state, new HashMap<>());
            predecessors.put(state, new HashSet<>());
            uncontrollablePredecessors.put(state, new HashSet<>());
        }
        for (S source : states) {
            Map<A, S> row = outgoing.get(source);
            for (Pair<A, S> edge : terminalized.getTransitions(source)) {
                A action = Objects.requireNonNull(edge.getFirst(), "action");
                S target = Objects.requireNonNull(edge.getSecond(), "target");
                if (!states.contains(target) || !actions.contains(action)) {
                    throw new IllegalArgumentException("A transition has an unknown target or action.");
                }
                if (unsafeSet.contains(source)) {
                    throw new IllegalArgumentException("Unsafe states must already be terminalized.");
                }
                S existing = row.putIfAbsent(action, target);
                if (existing != null && !existing.equals(target)) {
                    throw new IllegalArgumentException("The game must be label-deterministic.");
                }
                predecessors.get(target).add(source);
                if (!controlSet.contains(action)) {
                    uncontrollablePredecessors.get(target).add(source);
                }
            }
        }
        for (Map.Entry<S, Map<A, S>> entry : outgoing.entrySet()) {
            entry.setValue(Map.copyOf(entry.getValue()));
        }
        freezePredecessorRows(predecessors, uncontrollablePredecessors);
        return new IndexedSafetyGame<>(initial, states, actions, controlSet, unsafeSet,
                Collections.unmodifiableMap(outgoing), Collections.unmodifiableMap(predecessors),
                Collections.unmodifiableMap(uncontrollablePredecessors));
    }

    S initialState() {
        return initialState;
    }

    Set<S> states() {
        return states;
    }

    Set<A> actions() {
        return actions;
    }

    Set<A> controls() {
        return controls;
    }

    Set<S> unsafeStates() {
        return unsafeStates;
    }

    Map<A, S> outgoing(S state) {
        requireKnownState(state);
        return outgoing.get(state);
    }

    Set<S> predecessors(S state) {
        requireKnownState(state);
        return predecessors.get(state);
    }

    Set<S> uncontrollablePredecessors(S state) {
        requireKnownState(state);
        return uncontrollablePredecessors.get(state);
    }

    /**
     * Apply the complete batch without mutating this snapshot, even on failure.
     * The analysis layer checks the batch's base-analysis identity before use.
     * Controllability is fixed: previously declared but unused controls stay
     * controllable; all other newly introduced actions are uncontrollable.
     */
    IndexedSafetyGame<S, A> apply(SafetyGameDelta<S, A> delta) {
        Objects.requireNonNull(delta, "delta");
        for (S state : delta.getAddedStates()) {
            if (states.contains(state)) {
                throw new IllegalArgumentException("An added state already belongs to the game: " + state);
            }
        }
        if (!delta.getAddedStates().containsAll(delta.getAddedUnsafeStates())) {
            throw new IllegalArgumentException("Only newly added states may be declared unsafe.");
        }

        Set<S> nextStates = DenseStateStorage.unionStates(states, delta.getAddedStates());
        Set<S> nextUnsafe = union(unsafeStates, delta.getAddedUnsafeStates());
        Map<S, Map<A, S>> nextOutgoing = DenseStateStorage.copyMapFor(nextStates, outgoing);
        Map<S, Set<S>> nextPredecessors = DenseStateStorage.copyMapFor(nextStates, predecessors);
        Map<S, Set<S>> nextUncontrollable = DenseStateStorage.copyMapFor(nextStates, uncontrollablePredecessors);
        for (S state : delta.getAddedStates()) {
            nextOutgoing.put(state, Collections.emptyMap());
            nextPredecessors.put(state, Collections.emptySet());
            nextUncontrollable.put(state, Collections.emptySet());
        }

        Map<S, Map<A, S>> changedOutgoing = new HashMap<>();
        Map<S, Set<S>> changedPredecessors = new HashMap<>();
        Map<S, Set<S>> changedUncontrollable = new HashMap<>();
        Set<A> addedActions = new HashSet<>();
        for (SafetyGameDelta.Transition<S, A> edge : delta.getAddedTransitions()) {
            S source = edge.getSource();
            A action = edge.getAction();
            S target = edge.getTarget();
            if (!nextStates.contains(source) || !nextStates.contains(target)) {
                throw new IllegalArgumentException("A transition has an undeclared endpoint: " + edge);
            }
            if (nextUnsafe.contains(source)) {
                throw new IllegalArgumentException("Cannot add a transition from an unsafe state: " + source);
            }
            S existing = nextOutgoing.get(source).get(action);
            if (existing != null) {
                if (!existing.equals(target)) {
                    throw new IllegalArgumentException(
                            "The update must be label-deterministic; conflicting transition: " + edge);
                }
                continue; // An exact duplicate, including an existing edge, is a no-op.
            }
            Map<A, S> row = changedOutgoing.get(source);
            if (row == null) {
                row = new HashMap<>(nextOutgoing.get(source));
                changedOutgoing.put(source, row);
                nextOutgoing.put(source, row);
            }
            row.put(action, target);
            addPredecessor(nextPredecessors, changedPredecessors, target, source);
            if (!controls.contains(action)) {
                addPredecessor(nextUncontrollable, changedUncontrollable, target, source);
            }
            if (!actions.contains(action)) {
                addedActions.add(action);
            }
        }

        if (delta.getAddedStates().isEmpty() && changedOutgoing.isEmpty()) {
            return this;
        }
        for (Map.Entry<S, Map<A, S>> entry : changedOutgoing.entrySet()) {
            nextOutgoing.put(entry.getKey(), Map.copyOf(entry.getValue()));
        }
        for (Map.Entry<S, Set<S>> entry : changedPredecessors.entrySet()) {
            S target = entry.getKey();
            Set<S> all = Set.copyOf(entry.getValue());
            Set<S> uncontrollable = nextUncontrollable.get(target);
            nextPredecessors.put(target, all);
            nextUncontrollable.put(target, all.equals(uncontrollable)
                    ? all : Set.copyOf(uncontrollable));
        }
        // An added U label may target an existing all-edge predecessor pair.
        // Then only the uncontrollable index changes and still needs freezing.
        for (S target : changedUncontrollable.keySet()) {
            if (!changedPredecessors.containsKey(target)) {
                Set<S> all = nextPredecessors.get(target);
                Set<S> uncontrollable = nextUncontrollable.get(target);
                nextUncontrollable.put(target, all.equals(uncontrollable)
                        ? all : Set.copyOf(uncontrollable));
            }
        }
        return new IndexedSafetyGame<>(initialState, nextStates, union(actions, addedActions),
                controls, nextUnsafe, Collections.unmodifiableMap(nextOutgoing),
                Collections.unmodifiableMap(nextPredecessors),
                Collections.unmodifiableMap(nextUncontrollable));
    }

    /** Materialize a mutable, independent LTS only when a caller needs one. */
    LTS<S, A> copyLts() {
        LTS<S, A> copy = new LTSImpl<>(initialState);
        copy.addStates(states);
        copy.addActions(actions);
        for (Map.Entry<S, Map<A, S>> row : outgoing.entrySet()) {
            for (Map.Entry<A, S> edge : row.getValue().entrySet()) {
                copy.addTransition(row.getKey(), edge.getKey(), edge.getValue());
            }
        }
        return copy;
    }

    private void requireKnownState(S state) {
        Objects.requireNonNull(state, "state");
        if (!states.contains(state)) {
            throw new IllegalArgumentException("Unknown game state: " + state);
        }
    }

    private static <S> void addPredecessor(Map<S, Set<S>> rows, Map<S, Set<S>> changed,
                                          S target, S source) {
        if (rows.get(target).contains(source)) {
            return;
        }
        Set<S> row = changed.get(target);
        if (row == null) {
            row = new HashSet<>(rows.get(target));
            changed.put(target, row);
            rows.put(target, row);
        }
        row.add(source);
    }

    private static <S> void freezePredecessorRows(Map<S, Set<S>> predecessors,
                                                 Map<S, Set<S>> uncontrollable) {
        for (Map.Entry<S, Set<S>> entry : predecessors.entrySet()) {
            Set<S> all = Set.copyOf(entry.getValue());
            Set<S> uc = uncontrollable.get(entry.getKey());
            entry.setValue(all);
            // Both relations remain present. Equal immutable rows can share
            // storage; apply() copies a row before modifying either relation.
            uncontrollable.put(entry.getKey(), all.equals(uc) ? all : Set.copyOf(uc));
        }
    }

    private static <T> Set<T> immutableSetCopy(Set<T> values, String elementName) {
        Objects.requireNonNull(values, elementName + " set");
        Set<T> copy = new HashSet<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, elementName));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static <T> Set<T> union(Set<T> original, Set<T> additions) {
        if (additions.isEmpty()) {
            return original;
        }
        Set<T> combined = new HashSet<>(original);
        combined.addAll(additions);
        return Collections.unmodifiableSet(combined);
    }
}
