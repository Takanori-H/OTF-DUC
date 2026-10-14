package ltsa.differential;

import MTSSynthesis.controller.differential.ControllerPatch;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyController;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import MTSSynthesis.controller.differential.SafetyGameUpdate;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ExternalDataMetric;
import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;
import ltsa.updatingControllers.memory.RunHeapMemorySampler;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** One fixed-safety update per JVM, with shared model and simulation semantics. */
public final class DifferentialSynthesisRunner {
    private static final String HEAP_ENABLED = "mtsa.evaluation.memorySampling.enabled";
    private static final String HEAP_INTERVAL = "mtsa.evaluation.memorySampling.intervalMillis";
    private static final Set<String> OPTIONS = new HashSet<>(Arrays.asList(
            "lts", "target", "scenario", "method", "output", "transitions",
            "execution-metadata", "metrics", "measurement-scope"));

    private DifferentialSynthesisRunner() { }

    public static void main(String[] args) {
        System.exit(runMain(args));
    }

    public static int runMain(String[] args) {
        try {
            if (args.length == 1 && "--help".equals(args[0])) {
                System.out.println("DifferentialSynthesisRunner --lts FILE --target TARGET "
                        + "--scenario YAML --method FULL|DIFFERENTIAL|FROM_SCRATCH --output LOG "
                        + "--transitions TSV --execution-metadata JSON --metrics CSV "
                        + "[--measurement-scope generation|update]");
                return 0;
            }
            run(parse(args));
            return 0;
        } catch (OutOfMemoryError failure) {
            failure.printStackTrace(System.err);
            return 5;
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            return 64;
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            return 4;
        }
    }

    private static void run(Map<String, String> options) throws Exception {
        Path lts = path(options, "lts");
        Path scenarioFile = path(options, "scenario");
        String target = required(options, "target");
        String method = required(options, "method");
        if (!"FULL".equals(method) && !"DIFFERENTIAL".equals(method) && !"FROM_SCRATCH".equals(method)) {
            throw new IllegalArgumentException("--method must be FULL, DIFFERENTIAL or FROM_SCRATCH");
        }
        String measurementScope = options.getOrDefault("measurement-scope", "generation");
        if (!"generation".equals(measurementScope) && !"update".equals(measurementScope)) {
            throw new IllegalArgumentException("--measurement-scope must be generation or update");
        }
        Path outputFile = path(options, "output");
        Path transitionsFile = path(options, "transitions");
        Path metadataFile = path(options, "execution-metadata");
        String metricsOption = options.getOrDefault("metrics", System.getProperty("mtsa.evaluation.csvFile"));
        if (metricsOption == null || metricsOption.trim().isEmpty()) {
            throw new IllegalArgumentException("--metrics (or mtsa.evaluation.csvFile) is required");
        }
        Path metricsFile = Paths.get(metricsOption).toAbsolutePath().normalize();
        List<Path> destinations = Arrays.asList(outputFile, transitionsFile, metadataFile, metricsFile,
                patchPath(transitionsFile), switchPath(transitionsFile));
        validateDistinctPaths(destinations, Arrays.asList(lts, scenarioFile));
        for (Path destination : destinations) {
            if (Files.exists(destination) && Files.size(destination) != 0) {
                throw new IllegalArgumentException("Use fresh output files: " + destination);
            }
            Files.createDirectories(destination.getParent());
        }
        Metrics metrics = new Metrics();
        metrics.text("mode", "Differential synthesis");
        metrics.text("result", "SUCCESS");
        metrics.text("failure_reason", "");
        metrics.text("ds_method", method);
        metrics.text("ds_schema_version", "2");
        metrics.text("ds_measurement_scope", measurementScope);
        metrics.text("ds_analysis_strategy", "FROM_SCRATCH".equals(method) ? "FULL_REANALYSIS" : "INCREMENTAL");
        metrics.text("ds_target", target);
        metrics.text("ds_lts_file", lts.toString());
        metrics.text("ds_scenario_file", scenarioFile.toString());
        metrics.text("ds_lts_sha256", sha256(Files.readAllBytes(lts)));
        metrics.text("ds_scenario_sha256", sha256(Files.readAllBytes(scenarioFile)));
        metrics.text("ds_memory_scope", "not_run");
        metrics.text("ds_timing_protocol", "one_update_per_fresh_jvm_no_warmup");
        for (String phase : Arrays.asList("scenario_load", "initial_preparation", "base_controller",
                "delta_resolve", "game_update", "analysis", "compatibility", "input_fingerprint",
                "generation", "update_pipeline", "export")) {
            metrics.put("ds_" + phase + "_ns", "", "ns");
        }
        metrics.put("heap_memory_sampling_available", "false", "boolean");
        long runStarted = System.nanoTime();
        try (BufferedWriter log = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            LTSOutput compilerOutput = new LTSOutput() {
                public void out(String text) { writeUnchecked(log, text); }
                public void outln(String text) { writeUnchecked(log, text + System.lineSeparator()); }
                public void clearOutput() { }
            };
            long start = System.nanoTime();
            DifferentialScenario scenario = DifferentialScenario.load(scenarioFile);
            metrics.elapsed("scenario_load", start);
            start = System.nanoTime();
            Analysis<Long, String> base = FullSafetySynthesisRunner.analyze(
                    lts, target, scenario.getControllableActions(), compilerOutput);
            metrics.elapsed("initial_preparation", start);
            start = System.nanoTime();
            Optional<SafetyController<Long, String>> old = FullSafetySynthesis.generateControllerSnapshot(base);
            metrics.elapsed("base_controller", start);
            // Resolve and validate the update even if the base has no controller.
            start = System.nanoTime();
            SafetyGameDelta<Long, String> delta = scenario.resolve(base);
            metrics.elapsed("delta_resolve", start);
            Stages stages = new Stages(method, base, old, delta);
            if ("update".equals(measurementScope)) {
                metrics.text("ds_memory_scope", "update_with_old_inputs");
                measure(() -> {
                    long pipelineStart = System.nanoTime();
                    stages.prepare();
                    stages.generate();
                    stages.pipelineNanos = System.nanoTime() - pipelineStart;
                }, stages, metrics);
                recordFingerprint(stages, metrics);
            } else {
                stages.prepare();
                recordFingerprint(stages, metrics);
                if (stages.plan != null && stages.plan.isCompatible()) {
                    metrics.text("ds_memory_scope", "generation_with_prepared_inputs");
                    measure(stages::generate, stages, metrics);
                }
            }
            stages.recordTimes(metrics);
            IncrementalSafetyAnalysis.Result<Long, String> change = stages.change;
            ControllerUpdateSynthesis.Result<Long, String> generated = stages.generated;
            String status = stages.status;
            metrics.count("ds_base_game_states", base.getStates().size());
            metrics.count("ds_base_game_transitions", countTransitions(base));
            metrics.count("ds_base_controller_states", old.isPresent() ? old.get().getStates().size() : 0);
            metrics.count("ds_updated_game_states", change.getAnalysis().getStates().size());
            metrics.count("ds_updated_game_transitions", countTransitions(change.getAnalysis()));
            metrics.count("ds_affected_states", change.getAffectedStates().size());
            metrics.count("ds_reanalyzed_states", change.getAffectedStates().size());
            metrics.count("ds_reused_analysis_states", change.getReusedStateCount());
            metrics.count("ds_added_game_states", change.getAddedStates().size());
            metrics.count("ds_added_game_transitions", change.getAddedTransitions().size());
            if (stages.plan != null) {
                metrics.count("ds_incompatible_old_states", stages.plan.getIncompatibleOldStates().size());
            }
            if (generated != null) {
                metrics.count("ds_expanded_states", generated.getExpandedStateCount());
                metrics.count("ds_reused_controller_rows", generated.getReusedRowCount());
            }
            metrics.text("ds_status", status);
            start = System.nanoTime();
            writeController(transitionsFile, status, generated);
            if (generated != null) {
                SafetyController<Long, String> controller = generated.getController().get();
                metrics.count("ds_controller_states", controller.getStates().size());
                long edges = 0;
                for (Long state : controller.getStates()) {
                    edges += controller.getTransitions(state).size();
                }
                metrics.count("ds_controller_transitions", edges);
                writeSwitch(switchPath(transitionsFile), generated.getSwitchStateMapping());
                if (generated.getPatch().isPresent()) {
                    ControllerPatch<Long, String> patch = generated.getPatch().get();
                    writePatch(patchPath(transitionsFile), patch);
                    metrics.count("ds_patch_states", patch.getAddedStates().size());
                    metrics.count("ds_patch_transitions", patch.getAddedTransitions().size());
                }
            }
            metrics.elapsed("export", start);
            // File hashing is outside export and generation timing.
            metrics.text("ds_controller_output_sha256", sha256(Files.readAllBytes(transitionsFile)));
            String metadata = "{\n  \"mode\": \"differential-synthesis\",\n"
                    + "  \"actualMethod\": " + json(method) + ",\n"
                    + "  \"measurementScope\": " + json(measurementScope) + ",\n"
                    + "  \"target\": " + json(target) + ",\n"
                    + "  \"status\": " + json(status) + ",\n"
                    + "  \"matches\": true\n}\n";
            Files.write(metadataFile, metadata.getBytes(StandardCharsets.UTF_8));
            log.write("\nDifferential synthesis method=" + method + " status=" + status + "\n");
            log.write("Memory window: " + metrics.value("ds_memory_scope") + "\n");
            if (!metrics.value("ds_lts_sha256").equals(sha256(Files.readAllBytes(lts)))
                    || !metrics.value("ds_scenario_sha256").equals(sha256(Files.readAllBytes(scenarioFile)))) {
                throw new IllegalArgumentException("Input files changed during this experiment; discard the run.");
            }
            metrics.elapsed("run_before_metrics_write", runStarted);
            UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(metricsFile.toFile(),
                    "Differential synthesis", "SUCCESS", "", metrics.rows());
        }
    }

    private static void recordFingerprint(Stages stages, Metrics metrics) {
        long start = System.nanoTime();
        metrics.text("ds_prepared_input_sha256",
                inputFingerprint(stages.base, stages.change.getAnalysis(), stages.old));
        metrics.elapsed("input_fingerprint", start);
    }

    private static long countTransitions(Analysis<Long, String> game) {
        long count = 0;
        for (Long state : game.getStates()) { count += game.getTransitions(state).size(); }
        return count;
    }

    /** No CSV metric construction or graph hashing occurs inside the selected window. */
    private static void measure(Runnable work, Object retainedInputsAndResults, Metrics metrics) {
        boolean heapEnabled = Boolean.parseBoolean(System.getProperty(HEAP_ENABLED, "true"));
        long interval = Long.parseLong(System.getProperty(HEAP_INTERVAL, "50"));
        if (interval <= 0) { throw new IllegalArgumentException(HEAP_INTERVAL + " must be positive"); }
        RunHeapMemorySampler sampler = heapEnabled ? new RunHeapMemorySampler(interval) : null;
        boolean protocolEnabled = MemoryMeasurementProtocol.isEnabled();
        metrics.put("ds_memory_protocol_enabled", Boolean.toString(protocolEnabled), "boolean");
        boolean startAck = MemoryMeasurementProtocol.emitSynthesisWindowStart();
        try {
            if (sampler != null) { sampler.start(); }
            work.run();
        } finally {
            RunHeapMemorySampler.Result heap = sampler == null ? null : sampler.stop();
            boolean endAck = MemoryMeasurementProtocol.emitSynthesisWindowEnd();
            Reference.reachabilityFence(retainedInputsAndResults);
            // Convert measurements into CSV rows only after both memory windows close.
            if (heap != null) { recordHeap(metrics, heap); }
            else { metrics.put("heap_memory_sampling_enabled", "false", "boolean"); }
            metrics.put("ds_memory_protocol_acknowledged",
                    protocolEnabled ? Boolean.toString(startAck && endAck) : "", "boolean");
        }
    }

    /** Strongly retained through the final sample in either measurement scope. */
    private static final class Stages {
        final String method;
        final Analysis<Long, String> base;
        final Optional<SafetyController<Long, String>> old;
        final SafetyGameDelta<Long, String> delta;
        SafetyGameUpdate<Long, String> gameUpdate;
        IncrementalSafetyAnalysis.Result<Long, String> change;
        ControllerUpdateSynthesis.Plan<Long, String> plan;
        ControllerUpdateSynthesis.Result<Long, String> generated;
        String status = "BASE_UNREALIZABLE";
        long gameUpdateNanos = -1, analysisNanos = -1, compatibilityNanos = -1;
        long generationNanos = -1, pipelineNanos = -1;

        Stages(String method, Analysis<Long, String> base, Optional<SafetyController<Long, String>> old,
                SafetyGameDelta<Long, String> delta) {
            this.method = method;
            this.base = base;
            this.old = old;
            this.delta = delta;
        }

        void prepare() {
            long start = System.nanoTime();
            gameUpdate = SafetyGameUpdate.apply(base, delta);
            gameUpdateNanos = System.nanoTime() - start;
            start = System.nanoTime();
            change = "FROM_SCRATCH".equals(method) ? FullSafetySynthesis.analyzeUpdate(gameUpdate)
                    : IncrementalSafetyAnalysis.analyze(gameUpdate);
            analysisNanos = System.nanoTime() - start;
            if (old.isPresent()) {
                start = System.nanoTime();
                plan = ControllerUpdateSynthesis.prepare(old.get(), change);
                compatibilityNanos = System.nanoTime() - start;
                status = plan.getStatus().name();
            }
        }

        void generate() {
            if (plan == null || !plan.isCompatible()) { return; }
            long start = System.nanoTime();
            generated = "DIFFERENTIAL".equals(method) ? ControllerUpdateSynthesis.generateDifferential(plan)
                    : ControllerUpdateSynthesis.generateFull(plan);
            generationNanos = System.nanoTime() - start;
        }

        void recordTimes(Metrics metrics) {
            metrics.put("ds_game_update_ns", gameUpdateNanos, "ns");
            metrics.put("ds_analysis_ns", analysisNanos, "ns");
            if (compatibilityNanos >= 0) { metrics.put("ds_compatibility_ns", compatibilityNanos, "ns"); }
            if (generationNanos >= 0) { metrics.put("ds_generation_ns", generationNanos, "ns"); }
            if (pipelineNanos >= 0) { metrics.put("ds_update_pipeline_ns", pipelineNanos, "ns"); }
        }
    }

    private static void recordHeap(Metrics m, RunHeapMemorySampler.Result heap) {
        m.put("heap_memory_sampling_enabled", "true", "boolean");
        m.put("heap_memory_sampling_available", Boolean.toString(heap.isAvailable()), "boolean");
        m.put("controller_synthesis_sampled_base_heap_used", heap.getFirstHeapUsedBytes(), "B");
        m.put("controller_synthesis_sampled_peak_heap_used", heap.getPeakHeapUsedBytes(), "B");
        m.put("controller_synthesis_sampled_heap_increase", heap.isAvailable()
                ? Math.max(0, heap.getPeakHeapUsedBytes() - heap.getFirstHeapUsedBytes()) : -1, "B");
        m.put("heap_memory_sampling_interval_ms", heap.getIntervalMillis(), "ms");
        m.put("heap_memory_sampling_sample_count", heap.getSampleCount(), "samples");
        m.put("heap_memory_sampling_failure_count", heap.getFailedSampleCount(), "samples");
        m.put("heap_memory_sampling_max_gap_ms", heap.getMaxSampleGapMillis(), "ms");
        m.put("heap_memory_sampling_total_wall_time_ns", heap.getTotalSamplingWallTimeNanos(), "ns");
        m.put("heap_memory_sampling_thread_cpu_time_ns", heap.getSamplerThreadCpuTimeNanos(), "ns");
        m.put("heap_memory_sampling_first_sample_epoch_ms", heap.getFirstTimestampEpochMillis(), "epoch_ms");
        m.put("controller_synthesis_sampled_peak_heap_epoch_ms", heap.getPeakTimestampEpochMillis(), "epoch_ms");
    }

    private static void writeController(Path file, String status,
            ControllerUpdateSynthesis.Result<Long, String> result) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("STATUS\t" + status + "\n");
            if (result == null) { return; }
            SafetyController<Long, String> controller = result.getController().get();
            writer.write("INITIAL\t" + controller.getInitialState() + "\n");
            for (String action : new TreeSet<>(controller.getActions())) {
                writer.write("ACTION\t" + json(action) + "\n");
            }
            for (Long state : new TreeSet<>(controller.getStates())) {
                writer.write("STATE\t" + state + "\n");
                for (Map.Entry<String, Long> edge : new TreeMap<>(controller.getTransitions(state)).entrySet()) {
                    writer.write("EDGE\t" + state + "\t" + json(edge.getKey()) + "\t" + edge.getValue() + "\n");
                }
            }
        }
    }

    private static void writePatch(Path file, ControllerPatch<Long, String> patch) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("PATCH\tADDITIONS\n");
            for (String action : new TreeSet<>(patch.getAddedActions())) {
                writer.write("ACTION\t" + json(action) + "\n");
            }
            for (Long state : new TreeSet<>(patch.getAddedStates())) { writer.write("STATE\t" + state + "\n"); }
            List<String> rows = new ArrayList<>();
            for (SafetyGameDelta.Transition<Long, String> edge : patch.getAddedTransitions()) {
                rows.add("EDGE\t" + edge.getSource() + "\t" + json(edge.getAction()) + "\t" + edge.getTarget());
            }
            java.util.Collections.sort(rows);
            for (String row : rows) { writer.write(row + "\n"); }
        }
    }

    private static void writeSwitch(Path file, Map<Long, Long> mapping) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("OLD_STATE\tNEW_STATE\n");
            for (Map.Entry<Long, Long> pair : new TreeMap<>(mapping).entrySet()) {
                writer.write(pair.getKey() + "\t" + pair.getValue() + "\n");
            }
        }
    }

    private static String inputFingerprint(Analysis<Long, String> base, Analysis<Long, String> updated,
            Optional<SafetyController<Long, String>> old) {
        MessageDigest digest = digest();
        for (Analysis<Long, String> game : Arrays.asList(base, updated)) {
            token(digest, "GAME"); token(digest, game.getInitialState().toString());
            for (String a : new TreeSet<>(game.getActions())) { token(digest, "A"); token(digest, a); }
            for (String a : new TreeSet<>(game.getControllableActions())) { token(digest, "C"); token(digest, a); }
            for (Long state : new TreeSet<>(game.getStates())) {
                token(digest, "S"); token(digest, state.toString());
                token(digest, Boolean.toString(game.getUnsafeStates().contains(state)));
                token(digest, Boolean.toString(game.getWinningStates().contains(state)));
                hashEdges(digest, game.getTransitions(state));
            }
        }
        if (old.isPresent()) {
            token(digest, "OLD_CONTROLLER");
            for (Long state : new TreeSet<>(old.get().getStates())) {
                token(digest, "S"); token(digest, state.toString());
                hashEdges(digest, old.get().getTransitions(state));
            }
        }
        return hex(digest.digest());
    }

    private static void hashEdges(MessageDigest digest, Map<String, Long> row) {
        for (Map.Entry<String, Long> edge : new TreeMap<>(row).entrySet()) {
            token(digest, "E"); token(digest, edge.getKey()); token(digest, edge.getValue().toString());
        }
    }

    private static void token(MessageDigest digest, String token) {
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':'); digest.update(bytes);
    }

    private static String sha256(byte[] bytes) { return hex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) { result.append(String.format("%02x", b & 255)); }
        return result.toString();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 == args.length || !OPTIONS.contains(args[i].substring(2))) {
                throw new IllegalArgumentException("Unknown or incomplete option: " + args[i]);
            }
            if (options.put(args[i].substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + args[i]);
            }
        }
        return options;
    }
    private static void validateDistinctPaths(List<Path> destinations, List<Path> inputs) throws IOException {
        List<Path> seen = new ArrayList<>(inputs);
        for (Path destination : destinations) {
            Path canonical = destination.toFile().getCanonicalFile().toPath();
            for (Path previous : seen) {
                if (canonical.equals(previous.toFile().getCanonicalFile().toPath())
                        || (Files.exists(destination) && Files.exists(previous)
                            && Files.isSameFile(destination, previous))) {
                    throw new IllegalArgumentException("Input/output paths must be distinct: " + destination);
                }
            }
            seen.add(destination);
        }
    }
    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) { throw new IllegalArgumentException("--" + key + " is required"); }
        return value;
    }
    private static Path path(Map<String, String> options, String key) { return Paths.get(required(options, key)).toAbsolutePath().normalize(); }
    static Path patchPath(Path transitions) { return Paths.get(transitions + ".patch.tsv"); }
    static Path switchPath(Path transitions) { return Paths.get(transitions + ".switch.tsv"); }

    private static void writeUnchecked(BufferedWriter writer, String value) {
        try { writer.write(value); } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    private static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') { result.append('\\').append(c); }
            else if (c < 32) { result.append(String.format("\\u%04x", (int) c)); }
            else { result.append(c); }
        }
        return result.append('"').toString();
    }

    private static final class Metrics {
        private final Map<String, ExternalDataMetric> entries = new LinkedHashMap<>();
        private final Map<String, String> values = new HashMap<>();
        void put(String key, Object value, String unit) {
            String text = String.valueOf(value);
            entries.put(key, new ExternalDataMetric(key, "Differential synthesis", key, text, unit));
            values.put(key, text);
        }
        void text(String key, String value) { put(key, value, "text"); }
        void count(String key, long value) { put(key, value, "count"); }
        void elapsed(String phase, long start) { put("ds_" + phase + "_ns", System.nanoTime() - start, "ns"); }
        String value(String key) { return values.get(key); }
        List<ExternalDataMetric> rows() { return new ArrayList<>(entries.values()); }
    }
}
