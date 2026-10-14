package MTSSynthesis.controller.differential;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable additive controller update for one exact controller snapshot.
 * The generator establishes safety, reachability, and compatibility before
 * creating a patch. A patch retains only its base's independent identity token,
 * not the previous controller or analysis.
 */
public final class ControllerPatch<S, A> {
    private final Object baseIdentity;
    private final FullSafetySynthesis.Analysis<S, A> updated;
    private final Set<S> addedStates;
    private final Set<SafetyGameDelta.Transition<S, A>> addedTransitions;
    private final Set<A> addedActions;

    ControllerPatch(SafetyController<S, A> base,
                    FullSafetySynthesis.Analysis<S, A> updated,
                    Set<S> addedStates,
                    Set<SafetyGameDelta.Transition<S, A>> addedTransitions) {
        Objects.requireNonNull(base, "base");
        this.baseIdentity = base.identity();
        this.updated = Objects.requireNonNull(updated, "updated");
        this.addedStates = immutableSetCopy(addedStates, "added state");
        this.addedTransitions = immutableSetCopy(addedTransitions, "added transition");
        Set<A> newActions = new HashSet<>(updated.game.actions());
        newActions.removeAll(base.getActions());
        this.addedActions = Collections.unmodifiableSet(newActions);
    }

    public Set<S> getAddedStates() {
        return addedStates;
    }

    public Set<SafetyGameDelta.Transition<S, A>> getAddedTransitions() {
        return addedTransitions;
    }

    public Set<A> getAddedActions() {
        return addedActions;
    }

    /**
     * Apply atomically, sharing unchanged immutable rows and preserving every
     * old controller edge. The supplied base must be the snapshot for which
     * this patch was generated, not just an equivalent controller.
     */
    public SafetyController<S, A> apply(SafetyController<S, A> base) {
        Objects.requireNonNull(base, "base");
        if (base.identity() != baseIdentity) {
            throw new IllegalArgumentException("The patch belongs to a different controller snapshot.");
        }

        Map<S, Map<A, S>> rows = new HashMap<>(base.rows());
        for (S state : addedStates) {
            if (rows.containsKey(state)) {
                throw new IllegalArgumentException("An added state already belongs to the controller: " + state);
            }
            if (!updated.getWinningStates().contains(state)) {
                throw new IllegalArgumentException("An added controller state must be winning: " + state);
            }
            rows.put(state, Collections.emptyMap());
        }

        Map<S, Map<A, S>> changedRows = new HashMap<>();
        for (SafetyGameDelta.Transition<S, A> edge : addedTransitions) {
            S source = edge.getSource();
            A action = edge.getAction();
            S target = edge.getTarget();
            if (!rows.containsKey(source) || !rows.containsKey(target)) {
                throw new IllegalArgumentException("A controller transition has an unknown endpoint: " + edge);
            }
            S existing = rows.get(source).get(action);
            if (existing != null) {
                if (!existing.equals(target)) {
                    throw new IllegalArgumentException("Conflicting controller transition: " + edge);
                }
                continue;
            }
            Map<A, S> row = changedRows.get(source);
            if (row == null) {
                row = new HashMap<>(rows.get(source));
                changedRows.put(source, row);
                rows.put(source, row);
            }
            row.put(action, target);
        }
        for (Map.Entry<S, Map<A, S>> row : changedRows.entrySet()) {
            rows.put(row.getKey(), Collections.unmodifiableMap(row.getValue()));
        }
        return SafetyController.fromRows(updated, rows);
    }

    private static <T> Set<T> immutableSetCopy(Set<T> values, String elementName) {
        Objects.requireNonNull(values, elementName + " set");
        Set<T> copy = new HashSet<>();
        for (T value : values) {
            copy.add(Objects.requireNonNull(value, elementName));
        }
        return Collections.unmodifiableSet(copy);
    }
}
