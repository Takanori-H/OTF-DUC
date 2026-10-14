package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.differential.nativeintegration.NativeSynthesisCapture;
import MTSSynthesis.controller.differential.nativeintegration.NativeGenerationTiming;
import MTSSynthesis.controller.differential.nativeintegration.NativeMemoryDiagnostics;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.utils.GenericMTSToLongStringMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.lts.util.MTSUtils;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.*;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit per-target branch of MTSA's ordinary composition/synthesis entry. */
public final class NativeDifferentialSynthesis {
    private NativeDifferentialSynthesis() { }

    public static void execute(CompositeState target, LTSOutput output) {
        NativeDifferentialOptions options = Objects.requireNonNull(target.differentialOptions);
        target.differentialResult = null;
        Map<String, Long> phases = new LinkedHashMap<>();
        Map<String, Long> memory = new LinkedHashMap<>();
        Map<String, Object> inputIdentity = new LinkedHashMap<>();
        long requestStart = System.nanoTime();
        NativeMemoryDiagnostics diagnostics = null;
        try (HeapSampler sampler = new HeapSampler()) {
            diagnostics = NativeMemoryDiagnostics.openConfigured(options.getMode().name().toLowerCase(Locale.ROOT));
            requireSupported(target);
            NativeSynthesisSnapshot result;
            NativeGenerationTiming generationTiming;
            if (options.isPrepare()) {
                NativeMemoryDiagnostics.Token componentsPhase = diagnostics == null ? null : diagnostics.begin(
                        "component_copy", NativeMemoryDiagnostics.counts("components", target.machines.size()));
                List<CompactState> components = copyComponents(target.machines);
                NativeSynthesisCapture<Long, String> capture = new NativeSynthesisCapture<>();
                capture.setMemoryDiagnostics(diagnostics);
                if (diagnostics != null) diagnostics.end(componentsPhase,
                        NativeMemoryDiagnostics.counts("components", components.size()));
                generationTiming = capture.getGenerationTiming();
                CompositeState session = session(target.name, components, target.goal, capture);
                long started = System.nanoTime();
                TransitionSystemDispatcher.applyComposition(session, output);
                phases.put("initial_total", System.nanoTime() - started);
                phases.putAll(capture.getPhaseNanos());
                started = System.nanoTime();
                NativeMemoryDiagnostics.Token capturePhase = diagnostics == null ? null : diagnostics.begin(
                        "snapshot_capture", Collections.emptyMap());
                result = NativeSynthesisSnapshot.capture(components, target.goal.getControllableActions(),
                        capture, session.composition);
                if (diagnostics != null) diagnostics.end(capturePhase, snapshotCounts(result));
                NativeMemoryDiagnostics.Token savePhase = diagnostics == null ? null : diagnostics.begin(
                        "snapshot_save", snapshotCounts(result));
                NativeSnapshotIO.write(Paths.get(options.getSnapshotPath()), result);
                if (diagnostics != null) diagnostics.end(savePhase, snapshotCounts(result));
                phases.put("snapshot_save", System.nanoTime() - started);
                target.composition = session.composition;
            } else {
                long started = System.nanoTime();
                NativeMemoryDiagnostics.Token restorePhase = diagnostics == null ? null : diagnostics.begin(
                        "snapshot_restore", Collections.emptyMap());
                Path snapshotPath = Paths.get(options.getSnapshotPath());
                String digest = NativeSnapshotIO.verifiedDigest(snapshotPath);
                inputIdentity.put("input_snapshot_sha256", digest);
                inputIdentity.put("delta_sha256", NativeSnapshotIO.digest(Paths.get(options.getDeltaPath())));
                boolean full = options.getMode() == NativeDifferentialOptions.Mode.FULL;
                NativeSynthesisSnapshot previous = NativeSnapshotIO.readVerified(snapshotPath, !full);
                requireSameInput(target, previous);
                NativeDifferentialSafetyBackend.EnvironmentEdge edge = NativeEnvironmentDeltaIO.read(
                        Paths.get(options.getDeltaPath()), digest, previous.components.get(0));
                NativeDifferentialSafetyBackend.ProductIndex index = full ? null :
                        new NativeDifferentialSafetyBackend.ProductIndex(previous.plant, previous.tuples, previous.components.size());
                if (diagnostics != null) diagnostics.end(restorePhase, snapshotCounts(previous));
                phases.put("snapshot_restore", System.nanoTime() - started);
                memory.put("restore_peak_heap", sampler.peak());
                memory.put("restored_start_heap", sampler.reset());
                long updateStart = System.nanoTime();
                if (full) {
                    started = System.nanoTime();
                    List<CompactState> components = NativeDifferentialSafetyBackend.updatedComponents(
                            previous.components, edge, previous.controllable);
                    NativeSynthesisCapture<Long, String> capture = new NativeSynthesisCapture<>();
                    capture.setMemoryDiagnostics(diagnostics);
                    generationTiming = capture.getGenerationTiming();
                    CompositeState session = session(target.name, components, previous.goal(), capture);
                    long applyingDelta = System.nanoTime() - started;
                    // This is the actual ordinary compiler/dispatcher/GR-solver route.
                    TransitionSystemDispatcher.applyComposition(session, output);
                    phases.putAll(capture.getPhaseNanos());
                    phases.put("game_update", phases.get("game_update") + applyingDelta);
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token capturePhase = diagnostics == null ? null : diagnostics.begin(
                            "snapshot_capture_and_compatibility", Collections.emptyMap());
                    result = NativeSynthesisSnapshot.capture(components, previous.controllable, capture, session.composition);
                    requireCompatible(previous, result);
                    if (diagnostics != null) diagnostics.end(capturePhase, snapshotCounts(result));
                    phases.put("compatibility", System.nanoTime() - started);
                } else {
                    generationTiming = new NativeGenerationTiming();
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token updatePhase = diagnostics == null ? null : diagnostics.begin(
                            "incremental_product_update", snapshotCounts(previous));
                    NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                            previous.plant, index, previous.components, edge, previous.controllable, output);
                    if (diagnostics != null) diagnostics.end(updatePhase, snapshotCounts(previous));
                    NativeMemoryDiagnostics.Token gamePhase = diagnostics == null ? null : diagnostics.begin(
                            "incremental_game_update", snapshotCounts(previous));
                    NativeDifferentialSafetyBackend.updateGame(previous.game, delta);
                    if (diagnostics != null) diagnostics.end(gamePhase, snapshotCounts(previous));
                    phases.put("game_update", System.nanoTime() - started);
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token analysisPhase = diagnostics == null ? null : diagnostics.begin(
                            "incremental_requirements_analysis", snapshotCounts(previous));
                    NativeDifferentialSafetyBackend.Analysis analysis = NativeDifferentialSafetyBackend.analyze(
                            previous.game, previous.winning, delta);
                    if (diagnostics != null) diagnostics.end(analysisPhase,
                            NativeMemoryDiagnostics.counts("winning_states", analysis.getWinning().size()));
                    phases.put("requirements_analysis", System.nanoTime() - started);
                    started = System.nanoTime();
                    // The same old-W preservation condition is checked in FULL via semantic tuples.
                    if (!analysis.getWinning().containsAll(previous.winning)) {
                        throw new IllegalStateException("Updated winning region does not preserve the old controller guarantee");
                    }
                    phases.put("compatibility", System.nanoTime() - started);
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token generationPhase = diagnostics == null ? null : diagnostics.begin(
                            "incremental_controller_generation", snapshotCounts(previous));
                    NativeGenerationTiming.Stamp generationStart = generationTiming.start();
                    NativeGenerationTiming.Stamp buildStart = generationTiming.start();
                    MTS<StrategyState<Long, Integer>, String> controller = options.getMode() == NativeDifferentialOptions.Mode.DIFFERENTIAL
                            ? NativeDifferentialSafetyBackend.differentialController(previous.plant, previous.winning,
                                analysis.getWinning(), previous.controller, delta, generationTiming)
                            : NativeDifferentialSafetyBackend.fullController(previous.plant, analysis.getWinning());
                    generationTiming.finish("controller_build", buildStart);
                    CompactState compact = finishController(target.name, delta.getUpdatedComponents(), controller, output, generationTiming);
                    generationTiming.finish("controller_generation", generationStart);
                    phases.put("controller_generation", System.nanoTime() - started);
                    if (diagnostics != null) diagnostics.end(generationPhase, NativeMemoryDiagnostics.counts(
                            "controller_states", controller.getStates().size(), "output_states", compact.maxStates));
                    // Correspondence/capture preparation belongs to the update total, never hidden in restore.
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token capturePhase = diagnostics == null ? null : diagnostics.begin(
                            "snapshot_capture", Collections.emptyMap());
                    result = new NativeSynthesisSnapshot(delta.getUpdatedComponents(), previous.controllable,
                            previous.plant, previous.game, analysis.getWinning(), controller, compact, index.copyTuples());
                    if (diagnostics != null) diagnostics.end(capturePhase, snapshotCounts(result));
                    phases.put("compatibility", phases.get("compatibility") + System.nanoTime() - started);
                }
                phases.put("update_total", System.nanoTime() - updateStart);
                memory.put("update_peak_heap", sampler.peak());
                target.composition = result.output;
                if (options.getOutputPath() != null) {
                    started = System.nanoTime();
                    NativeMemoryDiagnostics.Token savePhase = diagnostics == null ? null : diagnostics.begin(
                            "snapshot_save", snapshotCounts(result));
                    NativeSnapshotIO.write(Paths.get(options.getOutputPath()), result);
                    if (diagnostics != null) diagnostics.end(savePhase, snapshotCounts(result));
                    phases.put("snapshot_save", System.nanoTime() - started);
                }
            }
            phases.put("request_total", System.nanoTime() - requestStart);
            // The existing enclosing total retains polling/bookkeeping overhead. Never overwrite it
            // with child sums or the narrower observed interval used for GC counters.
            for (Map.Entry<String, Long> phase : generationTiming.getPhaseNanos().entrySet()) {
                if (!phase.getKey().equals("controller_generation")) phases.put(phase.getKey(), phase.getValue());
            }
            target.differentialResult = new NativeDifferentialResult(options.getMode(), phases, memory,
                    generationTiming.getGcCollectionMillis(), generationTiming.getGcCollectionCounts());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("backend", "mtsa-native");
            record.put("mode", options.getMode().name().toLowerCase(Locale.ROOT));
            record.put("status", "SUCCESS");
            record.put("snapshot_format_version", NativeSnapshotIO.VERSION);
            record.putAll(inputIdentity);
            record.put("phase_nanos", phases); record.put("sampled_heap_bytes", memory);
            record.put("generation_timing_version", 1);
            record.put("gc_measurement", "jvm_gc_mxbean_interval_deltas");
            record.put("phase_gc_collection_millis", generationTiming.getGcCollectionMillis());
            record.put("phase_gc_collection_counts", generationTiming.getGcCollectionCounts());
            record.put("game_states", result.plant.getStates().size());
            record.put("winning_states", result.winning.size());
            record.put("controller_states", result.controller.getStates().size());
            record.put("output_states", result.output.maxStates);
            output.outln("NATIVE_DIFFERENTIAL_METRICS " + JSONObject.toJSONString(record));
        } catch (IOException ex) {
            if (diagnostics != null) diagnostics.failure(ex);
            throw new IllegalStateException("Native differential input/output failed: " + ex.getMessage(), ex);
        } catch (RuntimeException | Error ex) {
            if (diagnostics != null) diagnostics.failure(ex);
            throw ex;
        } finally {
            if (diagnostics != null) diagnostics.close();
        }
    }

    /** Only O(1) collection sizes; diagnostics never traverse model transitions. */
    private static Map<String, Long> snapshotCounts(NativeSynthesisSnapshot snapshot) {
        Map<String, Long> counts = NativeMemoryDiagnostics.counts("components", snapshot.components.size(),
                "tuple_entries", snapshot.tuples.size(), "output_states", snapshot.output.maxStates);
        // FULL deliberately skips saved analysis structures. Missing data is not a zero-sized model.
        if (snapshot.plant != null) counts.put("plant_states", (long) snapshot.plant.getStates().size());
        if (snapshot.winning != null) counts.put("winning_states", (long) snapshot.winning.size());
        if (snapshot.controller != null) counts.put("controller_states", (long) snapshot.controller.getStates().size());
        return counts;
    }

    private static CompositeState session(String name, List<CompactState> components,
            ControllerGoal<String> goal, NativeSynthesisCapture<Long, String> capture) {
        CompositeState session = new CompositeState(name, new Vector<>(components));
        session.makeController = true;
        session.goal = goal;
        session.env = components.get(0);
        session.differentialCapture = capture;
        return session;
    }

    private static List<CompactState> copyComponents(List<CompactState> components) {
        List<CompactState> result = new ArrayList<>();
        for (CompactState c : components) result.add(c.myclone());
        return result;
    }

    /** Same usable CompactState endpoint as ordinary synthesise: conversion then C || safety monitors. */
    private static CompactState finishController(String name, List<CompactState> components,
            MTS<StrategyState<Long, Integer>, String> controller, LTSOutput output, NativeGenerationTiming timing) {
        NativeGenerationTiming.Stamp conversionStart = timing.start();
        MTS<Long, String> plain = new GenericMTSToLongStringMTSConverter<StrategyState<Long, Integer>, String>().transform(controller);
        CompactState compact = MTSToAutomataConverter.getInstance().convert(plain, name, false, true);
        compact.setMtsControlProblemAnswer("ALL");
        timing.finish("controller_conversion", conversionStart);
        NativeGenerationTiming.Stamp composeStart = timing.start();
        Vector<CompactState> machines = new Vector<>(components);
        machines.set(0, compact);
        CompositeState post = new CompositeState(name, machines);
        post.compose(output);
        timing.finish("controller_postcompose", composeStart);
        return post.composition;
    }

    private static void requireCompatible(NativeSynthesisSnapshot previous, NativeSynthesisSnapshot updated) {
        Set<List<Integer>> newWinning = new HashSet<>();
        for (Long state : updated.winning) newWinning.add(tupleKey(updated.tuples.get(state)));
        for (Long state : previous.winning) {
            if (!newWinning.contains(tupleKey(previous.tuples.get(state)))) {
                throw new IllegalStateException("Updated winning region does not preserve the old controller guarantee");
            }
        }
    }

    private static List<Integer> tupleKey(int[] tuple) {
        if (tuple == null) throw new IllegalStateException("Missing winning-state correspondence");
        List<Integer> result = new ArrayList<>(tuple.length);
        for (int state : tuple) result.add(state);
        return result;
    }

    private static void requireSameInput(CompositeState target, NativeSynthesisSnapshot snapshot) {
        if (!target.goal.getControllableActions().equals(snapshot.controllable)
                || target.machines.size() != snapshot.components.size()) {
            throw new IllegalArgumentException("FSP target does not match saved requirements/environment");
        }
        if (!sameComponent(target.machines.get(0), snapshot.components.get(0))) {
            throw new IllegalArgumentException("FSP environment does not match snapshot");
        }
        // Safety preprocessing returns a HashSet; its iteration order is not a semantic identity.
        // Validate the multiset, then execute using the saved order that defines the tuple coordinates.
        boolean[] used = new boolean[target.machines.size()];
        used[0] = true;
        for (int i = 1; i < snapshot.components.size(); i++) {
            CompactState saved = snapshot.components.get(i);
            boolean found = false;
            for (int j = 1; j < target.machines.size(); j++) {
                CompactState actual = target.machines.get(j);
                if (!used[j] && Objects.equals(actual.name, saved.name) && sameComponent(actual, saved)) {
                    used[j] = true; found = true; break;
                }
            }
            if (!found) throw new IllegalArgumentException("FSP safety monitor does not match snapshot: " + saved.name);
        }
    }

    private static boolean sameComponent(CompactState actual, CompactState saved) {
        if (actual.maxStates != saved.maxStates || actual.endseq != saved.endseq
                || !new HashSet<>(Arrays.asList(actual.alphabet)).equals(new HashSet<>(Arrays.asList(saved.alphabet)))) return false;
        for (int state = 0; state < actual.maxStates; state++) {
            if (!row(actual, state).equals(row(saved, state))) return false;
        }
        return true;
    }

    private static Map<String, Integer> row(CompactState model, int state) {
        Map<String, Integer> row = new HashMap<>();
        if (model.states[state] != null) {
            Enumeration<?> edges = model.states[state].elements();
            while (edges.hasMoreElements()) {
                EventState edge = (EventState) edges.nextElement();
                row.put(model.alphabet[edge.getEvent()], edge.getNext());
            }
        }
        return row;
    }

    private static void requireSupported(CompositeState target) {
        ControllerGoal<String> goal = target.goal;
        if (!target.makeController || goal == null || target.machines == null || target.machines.isEmpty()
                || target.hidden != null || target.priorityLabels != null || target.makeMinimal || target.makeDeterministic
                || target.makeOptimistic || target.makePessimistic || target.makeAbstract || target.makeClousure
                || target.isHeuristic || target.isCompositional || target.makeRTCController || target.makeRTCAnalysisController
                || target.isProbabilistic || target.makeMDP || target.makeEnactment || target.makeControlStack
                || target.isMonolithicDirector || target.isPartialOrderReduction || Analyser.partialOrderReduction
                || Options.getMaxStatesGeneration() != LTSConstants.NO_MAX_STATE_GENERATION
                || target.isMakeComponent() || target.checkCompatible || target.isProperty || target.isStarEnv
                || target.isPlant || target.isControlledDet || target.makeCompose || target.actionsToErrorSet != null
                || (target.getCompositionType() != -1 && target.getCompositionType() != Symbol.OR)) {
            throw new IllegalArgumentException("Native differential mode requires an ordinary unmodified controller target");
        }
        if (!goal.getAssumptions().isEmpty() || !goal.getGuarantees().isEmpty() || !goal.getBuchi().isEmpty()
                || !goal.getFaults().isEmpty() || !goal.getFluents().isEmpty() || goal.isNonBlocking()
                || goal.isReachability() || goal.isPermissive() || goal.isNonTransient() || goal.isExceptionHandling()
                || goal.isTestLatency() || goal.getLazyness() != 0 || !goal.getMarking().isEmpty()
                || !goal.getDisturbances().isEmpty() || !goal.getSelfLoopsUncontrollable().isEmpty()
                || !goal.getConcurrencyFluents().isEmpty() || !goal.getActivityFluents().isEmpty()) {
            throw new IllegalArgumentException("Native differential v1 supports fixed safety monitors and controllable actions only");
        }
        for (CompactState component : target.machines) {
            if (component.isNonDeterministic() || component.hasTau() || MTSUtils.isMTSRepresentation(component)) {
                throw new IllegalArgumentException("Native differential v1 requires deterministic tau-free ordinary LTS components");
            }
            for (EventState state : component.states) {
                if (state instanceof ProbabilisticEventState) {
                    throw new IllegalArgumentException("Native differential v1 does not serialize probabilistic transitions");
                }
            }
        }
    }

    /** A request-scoped sampled heap measurement. It never forces GC or claims to measure live objects/RSS. */
    private static final class HeapSampler implements AutoCloseable {
        private final AtomicLong maximum = new AtomicLong();
        private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "native-differential-heap"); t.setDaemon(true); return t;
        });
        HeapSampler() { sample(); worker.scheduleAtFixedRate(this::sample, 100, 100, TimeUnit.MILLISECONDS); }
        private long sample() {
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            maximum.accumulateAndGet(used, Math::max); return used;
        }
        long peak() { sample(); return maximum.get(); }
        long reset() { long used = sample(); maximum.set(used); return used; }
        @Override public void close() { worker.shutdownNow(); }
    }
}
