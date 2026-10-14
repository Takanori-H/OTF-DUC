package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;

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
 * Full and differential controller generation from the same updated analysis.
 * The new controller must match every old-controller transition with the same
 * action. All entry points are exclusive to the differential-synthesis path.
 */
public final class ControllerUpdateSynthesis {
    private ControllerUpdateSynthesis() {
    }

    public enum Status {
        SUCCESS,
        /** No safe, deadlock-free controller exists from the updated initial state. */
        UNREALIZABLE,
        /** A safe controller exists, but it cannot preserve all old reachable behavior. */
        SIMULATION_INCOMPATIBLE
    }

    /**
     * Equivalent seed-selection implementations in this dedicated API.
     * ALL_ADDED_TRANSITIONS retains the former scan for explicit experimental
     * comparisons; the default uses the update's distinct old-source index.
     */
    public enum SeedSelection {
        CHANGED_OLD_SOURCES,
        ALL_ADDED_TRANSITIONS
    }

    /**
     * Check provenance and simulation once, independently of either generator.
     *
     * <p>With fixed state IDs, initial state, controllability and old edges in a
     * label-deterministic game, matching any old trace forces the same state ID
     * at every step. Thus the new maximal reachable winning controller simulates
     * the old one iff every old reachable controller state remains winning.
     * This criterion is not a general simulation algorithm for arbitrary LTSs.</p>
     *
     * <p>The identity mapping is a state-transfer witness for the same composed
     * environment/monitor state. No runtime swap, observation reconciliation or
     * safety during a delayed switch is performed by this API.</p>
     */
    public static <S, A> Plan<S, A> prepare(SafetyController<S, A> previous,
                                           IncrementalSafetyAnalysis.Result<S, A> change) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(change, "change");
        if (!change.hasBase(previous.getAnalysis())) {
            throw new IllegalArgumentException("Controller and game update have different base analyses.");
        }
        Analysis<S, A> updated = change.getAnalysis();
        Set<S> incompatible = new HashSet<>(previous.getStates());
        incompatible.removeAll(updated.getWinningStates());
        Status status = !updated.isRealizable() ? Status.UNREALIZABLE
                : incompatible.isEmpty() ? Status.SUCCESS : Status.SIMULATION_INCOMPATIBLE;
        Map<S, S> switchStates = new HashMap<>();
        if (status == Status.SUCCESS) {
            for (S state : previous.getStates()) {
                switchStates.put(state, state);
            }
        }
        return new Plan<>(previous, change, status, incompatible, switchStates);
    }

    /** Full extraction baseline, with the same already-checked analysis and simulation condition. */
    public static <S, A> Result<S, A> generateFull(Plan<S, A> plan) {
        return generateFull(plan, false);
    }

    /** Opt-in timings use the same full extraction as the unprofiled baseline. */
    public static <S, A> Result<S, A> generateFull(Plan<S, A> plan, boolean profileGeneration) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.isCompatible()) {
            return new Result<>(plan, null, null, 0, 0);
        }
        Optional<SafetyController<S, A>> generated;
        ControllerGenerationProfile profile = null;
        if (profileGeneration) {
            FullSafetySynthesis.ProfiledController<S, A> profiled = FullSafetySynthesis
                    .generateControllerSnapshotProfiled(plan.getUpdatedAnalysis());
            generated = profiled.getController();
            profile = profiled.getGenerationProfile().orElseThrow(
                    () -> new IllegalStateException("A compatible profiled generation must have diagnostics."));
        } else {
            generated = FullSafetySynthesis.generateControllerSnapshot(plan.getUpdatedAnalysis());
        }
        SafetyController<S, A> controller = generated.orElseThrow(
                () -> new IllegalStateException("A compatible analysis must be realizable."));
        return new Result<>(plan, controller, null, controller.getStates().size(), 0, profile);
    }

    /**
     * Extend the previous controller with an additive patch. Old reachable rows
     * remain valid after the compatibility gate, so only rows that can gain an
     * edge and newly reachable states are explored. Edges into newly winning
     * states may already exist in the game and must not be missed.
     *
     * <p>Outer maps, patch sets and the shared compatibility witness are still
     * materialized; this does not promise sublinear total time or lower memory.</p>
     */
    public static <S, A> Result<S, A> generateDifferential(Plan<S, A> plan) {
        return generateDifferential(plan, SeedSelection.CHANGED_OLD_SOURCES, false);
    }

    public static <S, A> Result<S, A> generateDifferential(Plan<S, A> plan,
                                                         SeedSelection seedSelection) {
        return generateDifferential(plan, seedSelection, false);
    }

    public static <S, A> Result<S, A> generateDifferential(Plan<S, A> plan,
                                                         boolean profileGeneration) {
        return generateDifferential(plan, SeedSelection.CHANGED_OLD_SOURCES, profileGeneration);
    }

    /** Explicit reference variant and profiling are exclusive to this opt-in API. */
    public static <S, A> Result<S, A> generateDifferential(Plan<S, A> plan,
                                                         SeedSelection seedSelection,
                                                         boolean profileGeneration) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(seedSelection, "seedSelection");
        if (!plan.isCompatible()) {
            return new Result<>(plan, null, null, 0, 0);
        }
        ControllerGenerationProfile.Collector profile = profileGeneration
                ? new ControllerGenerationProfile.Collector() : null;
        long phaseStarted = profile == null ? 0 : System.nanoTime();
        SafetyController<S, A> previous = plan.previous;
        Analysis<S, A> updated = plan.getUpdatedAnalysis();
        IndexedSafetyGame<S, A> game = updated.game;
        Set<S> dirty = new HashSet<>();
        if (seedSelection == SeedSelection.ALL_ADDED_TRANSITIONS) {
            for (Transition<S, A> edge : plan.change.getAddedTransitions()) {
                if (profile != null) {
                    profile.addedEdgesExamined++;
                }
                if (previous.getStates().contains(edge.getSource())) {
                    dirty.add(edge.getSource());
                }
            }
        } else {
            for (S source : plan.change.getChangedOldSources()) {
                if (profile != null) {
                    profile.changedOldSourcesExamined++;
                }
                if (previous.getStates().contains(source)) {
                    dirty.add(source);
                }
            }
        }
        for (S gained : plan.change.getNewlyWinningStates()) {
            for (S predecessor : game.predecessors(gained)) {
                if (profile != null) {
                    profile.newlyWinningPredecessorsExamined++;
                }
                if (previous.getStates().contains(predecessor)) {
                    dirty.add(predecessor);
                }
            }
        }
        if (profile != null) {
            profile.seedSelectionNanos = System.nanoTime() - phaseStarted;
            profile.seedStates = dirty.size();
            phaseStarted = System.nanoTime();
        }

        Map<S, Map<A, S>> rows = new HashMap<>(previous.rows());
        Set<S> addedStates = new HashSet<>();
        Set<Transition<S, A>> addedTransitions = new HashSet<>();
        Set<S> scheduled = new HashSet<>(dirty);
        Queue<S> pending = new ArrayDeque<>(dirty);
        int changedOldRows = 0;
        if (profile != null) {
            profile.rowSetupNanos = System.nanoTime() - phaseStarted;
            phaseStarted = System.nanoTime();
        }
        while (!pending.isEmpty()) {
            S source = pending.remove();
            Map<A, S> oldRow = previous.rows().get(source);
            Map<A, S> row = oldRow == null ? Collections.emptyMap() : oldRow;
            boolean changedRow = false;
            for (Map.Entry<A, S> edge : game.outgoing(source).entrySet()) {
                if (profile != null) {
                    profile.gameEdgesExamined++;
                }
                S target = edge.getValue();
                if (!updated.getWinningStates().contains(target)) {
                    continue;
                }
                if (!row.containsKey(edge.getKey())) {
                    if (!changedRow) {
                        row = new HashMap<>(row);
                        changedRow = true;
                    }
                    row.put(edge.getKey(), target);
                    addedTransitions.add(new Transition<>(source, edge.getKey(), target));
                }
                if (!rows.containsKey(target)) {
                    // Mark on discovery so cycles enqueue each new state only once.
                    rows.put(target, Collections.emptyMap());
                    addedStates.add(target);
                    if (scheduled.add(target)) {
                        pending.add(target);
                    }
                }
            }
            if (changedRow) {
                row = Collections.unmodifiableMap(row);
                if (oldRow != null) {
                    changedOldRows++;
                }
            }
            rows.put(source, row);
        }
        if (profile != null) {
            profile.rowExpansionNanos = System.nanoTime() - phaseStarted;
            profile.expandedStates = scheduled.size();
            profile.reusedControllerRows = previous.getStates().size() - changedOldRows;
            phaseStarted = System.nanoTime();
        }

        SafetyController<S, A> controller = SafetyController.fromRows(updated, rows);
        if (profile != null) {
            profile.controllerSnapshotNanos = System.nanoTime() - phaseStarted;
            phaseStarted = System.nanoTime();
        }
        ControllerPatch<S, A> patch = new ControllerPatch<>(previous, updated, addedStates, addedTransitions);
        if (profile != null) {
            profile.patchConstructionNanos = System.nanoTime() - phaseStarted;
        }
        return new Result<>(plan, controller, patch, scheduled.size(),
                previous.getStates().size() - changedOldRows, profile == null ? null : profile.finish());
    }

    /** Shared preparation for fair full/differential generation comparisons. */
    public static final class Plan<S, A> {
        private final SafetyController<S, A> previous;
        private final IncrementalSafetyAnalysis.Result<S, A> change;
        private final Status status;
        private final Set<S> incompatibleOldStates;
        private final Map<S, S> switchStateMapping;

        private Plan(SafetyController<S, A> previous, IncrementalSafetyAnalysis.Result<S, A> change,
                     Status status, Set<S> incompatibleOldStates, Map<S, S> switchStateMapping) {
            this.previous = previous;
            this.change = change;
            this.status = status;
            this.incompatibleOldStates = Collections.unmodifiableSet(new HashSet<>(incompatibleOldStates));
            this.switchStateMapping = Collections.unmodifiableMap(new HashMap<>(switchStateMapping));
        }

        /** SUCCESS means compatible and ready; generation is a separate operation. */
        public Status getStatus() {
            return status;
        }

        public boolean isCompatible() {
            return status == Status.SUCCESS;
        }

        public Analysis<S, A> getUpdatedAnalysis() {
            return change.getAnalysis();
        }

        public Set<S> getIncompatibleOldStates() {
            return incompatibleOldStates;
        }

        /** Old controller state -> matching new game/controller state; empty on failure. */
        public Map<S, S> getSwitchStateMapping() {
            return switchStateMapping;
        }
    }

    /** A failed update has no accepted controller, patch or state-transfer witness. */
    public static final class Result<S, A> {
        private final Status status;
        private final SafetyController<S, A> controller;
        private final ControllerPatch<S, A> patch;
        private final Set<S> incompatibleOldStates;
        private final Map<S, S> switchStateMapping;
        private final int expandedStateCount;
        private final int reusedRowCount;
        private final ControllerGenerationProfile generationProfile;

        private Result(Plan<S, A> plan, SafetyController<S, A> controller,
                       ControllerPatch<S, A> patch, int expandedStateCount, int reusedRowCount) {
            this(plan, controller, patch, expandedStateCount, reusedRowCount, null);
        }

        private Result(Plan<S, A> plan, SafetyController<S, A> controller,
                       ControllerPatch<S, A> patch, int expandedStateCount, int reusedRowCount,
                       ControllerGenerationProfile generationProfile) {
            status = plan.status;
            this.controller = controller;
            this.patch = patch;
            incompatibleOldStates = plan.incompatibleOldStates;
            switchStateMapping = plan.switchStateMapping;
            this.expandedStateCount = expandedStateCount;
            this.reusedRowCount = reusedRowCount;
            this.generationProfile = generationProfile;
        }

        public Status getStatus() {
            return status;
        }

        public Optional<SafetyController<S, A>> getController() {
            return Optional.ofNullable(controller);
        }

        /** Only differential generation returns a patch, including an empty successful patch. */
        public Optional<ControllerPatch<S, A>> getPatch() {
            return Optional.ofNullable(patch);
        }

        public Set<S> getIncompatibleOldStates() {
            return incompatibleOldStates;
        }

        public Map<S, S> getSwitchStateMapping() {
            return switchStateMapping;
        }

        /** Number of states whose updated outgoing game row was scanned during generation. */
        public int getExpandedStateCount() {
            return expandedStateCount;
        }

        /** Rows explicitly reused from the old controller cache; the full path reports zero. */
        public int getReusedRowCount() {
            return reusedRowCount;
        }

        /** Empty when profiling is disabled or compatibility rejected generation. */
        public Optional<ControllerGenerationProfile> getGenerationProfile() {
            return Optional.ofNullable(generationProfile);
        }
    }
}
