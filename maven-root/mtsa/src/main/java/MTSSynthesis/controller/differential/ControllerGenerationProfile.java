package MTSSynthesis.controller.differential;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in diagnostics for the dedicated safety-controller generators.
 *
 * <p>Phases are disjoint wall-clock intervals inside generation. They exclude
 * compatibility checking and external serialization. The total starts after
 * the realizability/compatibility gate and ends before constructing this
 * diagnostic snapshot and the public result wrapper; an external generation
 * timer therefore also includes overhead outside this total. Instrumentation
 * itself is included in these diagnostic timings.</p>
 *
 * <p>{@code row_setup} includes the differential generator's outer-map copy;
 * {@code controller_snapshot} includes the final immutable outer-map copy.
 * {@code row_expansion} includes scanning outgoing game edges, including edges
 * filtered because their target loses. It is not just the number of emitted
 * controller edges. Full generation has no seed-selection or patch phase.</p>
 */
public final class ControllerGenerationProfile {
    private final Map<String, Long> phaseNanos;
    private final Map<String, Long> counters;
    private final long totalNanos;

    private ControllerGenerationProfile(Collector source, long totalNanos) {
        Map<String, Long> phases = new LinkedHashMap<>();
        phases.put("seed_selection", source.seedSelectionNanos);
        phases.put("row_setup", source.rowSetupNanos);
        phases.put("row_expansion", source.rowExpansionNanos);
        phases.put("controller_snapshot", source.controllerSnapshotNanos);
        phases.put("patch_construction", source.patchConstructionNanos);
        phaseNanos = Collections.unmodifiableMap(phases);
        Map<String, Long> operations = new LinkedHashMap<>();
        operations.put("added_edges_examined", source.addedEdgesExamined);
        operations.put("changed_old_sources_examined", source.changedOldSourcesExamined);
        operations.put("newly_winning_predecessors_examined", source.newlyWinningPredecessorsExamined);
        operations.put("seed_states", source.seedStates);
        operations.put("expanded_states", source.expandedStates);
        operations.put("game_edges_examined", source.gameEdgesExamined);
        operations.put("reused_controller_rows", source.reusedControllerRows);
        counters = Collections.unmodifiableMap(operations);
        this.totalNanos = totalNanos;
        long phaseSum = 0;
        for (long duration : phaseNanos.values()) {
            if (duration < 0) {
                throw new IllegalStateException("Negative generation phase duration.");
            }
            phaseSum += duration;
        }
        if (phaseSum > totalNanos) {
            throw new IllegalStateException("Generation phase intervals exceed the enclosing total.");
        }
    }

    public Map<String, Long> getPhaseNanos() {
        return phaseNanos;
    }

    /** Work counters describe actual iterations, including repeated predecessor visits. */
    public Map<String, Long> getCounters() {
        return counters;
    }

    public long getTotalNanos() {
        return totalNanos;
    }

    /** Internal overhead between measured phases, not a synthesized phase-sum total. */
    public long getUnattributedNanos() {
        long measured = 0;
        for (long duration : phaseNanos.values()) {
            measured += duration;
        }
        return totalNanos - measured;
    }

    /** Constructed only by explicitly profiled entry points. */
    static final class Collector {
        private final long startedAtNanos = System.nanoTime();
        long seedSelectionNanos;
        long rowSetupNanos;
        long rowExpansionNanos;
        long controllerSnapshotNanos;
        long patchConstructionNanos;
        long addedEdgesExamined;
        long changedOldSourcesExamined;
        long newlyWinningPredecessorsExamined;
        long seedStates;
        long expandedStates;
        long gameEdgesExamined;
        long reusedControllerRows;

        ControllerGenerationProfile finish() {
            long elapsed = System.nanoTime() - startedAtNanos;
            return new ControllerGenerationProfile(this, elapsed);
        }
    }
}
