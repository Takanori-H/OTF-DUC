package MTSSynthesis.controller.differential;

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
 * Immutable reachable safety controller for one analysis snapshot.
 * State IDs and the complete game alphabet are retained. State and action
 * values must retain stable equality and hash codes.
 */
public final class SafetyController<S, A> {
    private final FullSafetySynthesis.Analysis<S, A> analysis;
    private final Map<S, Map<A, S>> rows;
    private final Object identity = new Object();

    /** The outer map is owned and frozen; every shared row must be immutable. */
    private SafetyController(FullSafetySynthesis.Analysis<S, A> analysis,
                             Map<S, Map<A, S>> rows) {
        this.analysis = analysis;
        this.rows = rows;
    }

    /** Extract all reachable winning-to-winning edges without renumbering. */
    static <S, A> Optional<SafetyController<S, A>> full(
            FullSafetySynthesis.Analysis<S, A> analysis) {
        return full(analysis, null);
    }

    /** The nullable collector keeps all clock reads out of the ordinary entry point. */
    static <S, A> Optional<SafetyController<S, A>> full(
            FullSafetySynthesis.Analysis<S, A> analysis,
            ControllerGenerationProfile.Collector profile) {
        Objects.requireNonNull(analysis, "analysis");
        if (!analysis.isRealizable()) {
            return Optional.empty();
        }

        long phaseStarted = profile == null ? 0 : System.nanoTime();
        Set<S> winning = analysis.getWinningStates();
        Set<S> reached = new HashSet<>();
        Queue<S> pending = new ArrayDeque<>();
        Map<S, Map<A, S>> rows = new HashMap<>();
        S initial = analysis.getInitialState();
        reached.add(initial);
        pending.add(initial);
        if (profile != null) {
            profile.rowSetupNanos = System.nanoTime() - phaseStarted;
            profile.seedStates = 1;
            phaseStarted = System.nanoTime();
        }
        while (!pending.isEmpty()) {
            S source = pending.remove();
            Map<A, S> gameRow = analysis.game.outgoing(source);
            Map<A, S> filtered = null;
            for (Map.Entry<A, S> edge : gameRow.entrySet()) {
                if (profile != null) {
                    profile.gameEdgesExamined++;
                }
                S target = edge.getValue();
                if (winning.contains(target)) {
                    if (reached.add(target)) {
                        pending.add(target);
                    }
                } else {
                    if (filtered == null) {
                        filtered = new HashMap<>(gameRow);
                    }
                    filtered.remove(edge.getKey());
                }
            }
            rows.put(source, filtered == null ? gameRow : Collections.unmodifiableMap(filtered));
        }
        if (profile != null) {
            profile.rowExpansionNanos = System.nanoTime() - phaseStarted;
            profile.expandedStates = reached.size();
            phaseStarted = System.nanoTime();
        }
        SafetyController<S, A> controller = fromRows(analysis, rows);
        if (profile != null) {
            profile.controllerSnapshotNanos = System.nanoTime() - phaseStarted;
        }
        return Optional.of(controller);
    }

    /**
     * Own the outer map while sharing its immutable rows. Internal callers
     * supply exactly the reachable winning graph; this method deliberately
     * does not rescan all transitions or copy unchanged rows.
     */
    static <S, A> SafetyController<S, A> fromRows(
            FullSafetySynthesis.Analysis<S, A> analysis,
            Map<S, Map<A, S>> immutableRows) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(immutableRows, "immutableRows");
        Map<S, Map<A, S>> ownedRows = new HashMap<>();
        for (Map.Entry<S, Map<A, S>> row : immutableRows.entrySet()) {
            ownedRows.put(Objects.requireNonNull(row.getKey(), "state"),
                    Objects.requireNonNull(row.getValue(), "immutable row"));
        }
        if (!ownedRows.containsKey(analysis.getInitialState())) {
            throw new IllegalArgumentException("The controller must contain the initial state.");
        }
        return new SafetyController<>(analysis, Collections.unmodifiableMap(ownedRows));
    }

    public FullSafetySynthesis.Analysis<S, A> getAnalysis() {
        return analysis;
    }

    public S getInitialState() {
        return analysis.getInitialState();
    }

    public Set<S> getStates() {
        return rows.keySet();
    }

    /** Includes game actions that have no enabled controller transition. */
    public Set<A> getActions() {
        return analysis.game.actions();
    }

    public Map<A, S> getTransitions(S state) {
        Objects.requireNonNull(state, "state");
        Map<A, S> row = rows.get(state);
        if (row == null) {
            throw new IllegalArgumentException("Unknown controller state: " + state);
        }
        return row;
    }

    /** Materialize a mutable, independent LTS only when needed by a caller. */
    public LTS<S, A> copyLts() {
        LTS<S, A> copy = new LTSImpl<>(getInitialState());
        copy.addStates(getStates());
        copy.addActions(getActions());
        for (Map.Entry<S, Map<A, S>> row : rows.entrySet()) {
            for (Map.Entry<A, S> edge : row.getValue().entrySet()) {
                copy.addTransition(row.getKey(), edge.getKey(), edge.getValue());
            }
        }
        return copy;
    }

    Map<S, Map<A, S>> rows() {
        return rows;
    }

    Object identity() {
        return identity;
    }
}
