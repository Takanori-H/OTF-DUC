package ltsa.differential;

import MTSSynthesis.controller.differential.CachedLosingSafetyAnalysis;
import MTSSynthesis.controller.differential.ControllerGenerationProfile;
import MTSSynthesis.controller.differential.ControllerUpdateSynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyController;
import MTSSynthesis.controller.differential.SafetyGameSnapshot;
import MTSSynthesis.controller.differential.SafetyGameUpdate;
import ltsa.differential.EnvironmentSafetyProduct.Component;
import ltsa.differential.EnvironmentSafetyProduct.EnvEdge;
import ltsa.differential.EnvironmentSafetyProduct.EnvUpdate;
import ltsa.differential.EnvironmentSafetyProduct.Expansion;
import ltsa.lts.CompactState;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.memory.RunHeapMemorySampler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated, fixed-safety environment-update experiment. FULL recomposes the
 * environment/monitor product, without reusing old graph rows or classifications.
 * DIFFERENTIAL reuses both and extends the old controller. ANALYSIS_REUSE uses
 * the same differential analysis followed by full controller extraction. The
 * fixed-safety analyzer retains old losing states and propagates new loss when
 * its applicability condition holds, otherwise explicitly re-solves the
 * predecessor closure. Neither path performs divided requirement-candidate
 * selection.
 *
 * The environment-driven monitor semantics reproduce the legacy paper inputs;
 * they do not change ordinary MTSA composition or other synthesis modes.
 */
public final class EnvironmentDifferentialSynthesisRunner {
    private static final long ERROR = -1L;
    private static final LTSOutput QUIET = new LTSOutput() {
        public void out(String s) { }
        public void outln(String s) { }
        public void clearOutput() { }
    };

    private EnvironmentDifferentialSynthesisRunner() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("EnvironmentDifferentialSynthesisRunner model.lts monitorCount "
                    + "controls.txt change.tsv FULL|DIFFERENTIAL|ANALYSIS_REUSE output.json [--verify] "
                    + "[--profile-generation] [--generation-seeds=changed-old-sources|all-added-transitions]\n"
                    + "[--full-retention=legacy|compact] [--memory-diagnostics=NEW_DIRECTORY]\n"
                    + "Generation seed selection applies only to DIFFERENTIAL; all-added-transitions "
                    + "is an experimental reference. FULL retention defaults to legacy. "
                    + "Memory diagnostics force GC; their timings are NOT performance measurements.");
            return;
        }
        if (args.length < 6) {
            throw new IllegalArgumentException("Use --help for the dedicated environment-update entry point");
        }
        Options options = Options.parse(args);
        Path output = Paths.get(args[5]).toAbsolutePath();
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite " + output);
        if (Files.exists(Paths.get(args[5] + ".controller.tsv"))) {
            throw new IllegalArgumentException("Refusing to overwrite controller output");
        }
        Files.createDirectories(output.getParent());
        Map<String, Object> report = map("schema_version", 1, "method", args[4],
                "status", "RUNNING", "verification_requested", options.verify,
                "generation_profile_requested", options.profileGeneration,
                "generation_seed_selection", args[4].equals("DIFFERENTIAL") ? options.seeds.name() : null,
                "generation_profile", null,
                "full_retention", args[4].equals("FULL") ? options.fullRetention : null,
                "memory_diagnostics_requested", options.memoryDirectory != null,
                "timing_valid_for_performance", options.memoryDirectory == null,
                "semantics", "fixed safety; environment-driven monitor product; missing row action stutters",
                "compatibility_contract", "paper old-winning-region inclusion plus old-controller simulation",
                "analysis_algorithm", null,
                "analysis_backend", null, "analysis_fallback_reason", null,
                "timing_protocol", options.memoryDirectory == null
                        ? "one update per fresh JVM; no warmup; direct update wall time"
                        : "memory diagnostic only; forced GC/class histograms perturb all timings; do not compare performance",
                "timing_exclusions", "compilation, initial preparation, result diagnostics, validation and serialization",
                "runtime", System.getProperty("java.runtime.version"),
                "max_heap_bytes", Runtime.getRuntime().maxMemory());
        try {
            execute(args, options, report);
        } catch (Exception failure) {
            report.put("status", "FAILED");
            report.put("failure", failure.toString());
            write(output, report);
            throw failure;
        } catch (OutOfMemoryError failure) {
            System.err.println("Out of memory in phase " + report.get("phase"));
            // The launcher records incomplete/failed measurements separately.
            throw failure;
        }
        write(output, report);
    }

    private static final class Options {
        boolean verify;
        boolean profileGeneration;
        String fullRetention = "legacy";
        Path memoryDirectory;
        ControllerUpdateSynthesis.SeedSelection seeds = ControllerUpdateSynthesis.SeedSelection.CHANGED_OLD_SOURCES;

        static Options parse(String[] args) {
            Options result = new Options();
            boolean seedsSpecified = false;
            Set<String> seen = new TreeSet<>();
            for (int i = 6; i < args.length; i++) {
                String option = args[i];
                String key = option.contains("=") ? option.substring(0, option.indexOf('=')) : option;
                if (!seen.add(key)) throw new IllegalArgumentException("Duplicate option: " + key);
                if (option.equals("--verify")) result.verify = true;
                else if (option.equals("--profile-generation")) result.profileGeneration = true;
                else if (option.startsWith("--generation-seeds=")) {
                    seedsSpecified = true;
                    String selection = option.substring("--generation-seeds=".length());
                    if (selection.equals("changed-old-sources")) {
                        result.seeds = ControllerUpdateSynthesis.SeedSelection.CHANGED_OLD_SOURCES;
                    } else if (selection.equals("all-added-transitions")) {
                        result.seeds = ControllerUpdateSynthesis.SeedSelection.ALL_ADDED_TRANSITIONS;
                    } else throw new IllegalArgumentException("Unknown generation seed selection: " + selection);
                } else if (option.startsWith("--full-retention=")) {
                    result.fullRetention = option.substring("--full-retention=".length());
                    if (!Arrays.asList("legacy", "compact").contains(result.fullRetention)) {
                        throw new IllegalArgumentException("Unknown FULL retention: " + result.fullRetention);
                    }
                } else if (option.startsWith("--memory-diagnostics=")) {
                    String directory = option.substring("--memory-diagnostics=".length());
                    if (directory.isEmpty()) throw new IllegalArgumentException("Missing memory diagnostic directory");
                    result.memoryDirectory = Paths.get(directory).toAbsolutePath();
                } else throw new IllegalArgumentException("Unknown option: " + option);
            }
            if (seedsSpecified && !args[4].equals("DIFFERENTIAL")) {
                throw new IllegalArgumentException("--generation-seeds applies only to DIFFERENTIAL");
            }
            if (seen.contains("--full-retention") && !args[4].equals("FULL")) {
                throw new IllegalArgumentException("--full-retention applies only to FULL");
            }
            return result;
        }
    }

    private static void execute(String[] args, Options options, Map<String, Object> report) throws Exception {
        String method = args[4];
        if (!Arrays.asList("FULL", "DIFFERENTIAL", "ANALYSIS_REUSE").contains(method)) {
            throw new IllegalArgumentException("Unknown method: " + method);
        }
        Map<String, Long> timing = new LinkedHashMap<>();
        report.put("timing_ns", timing);
        SynthesisMemoryDiagnostics memory = options.memoryDirectory == null ? null
                : new SynthesisMemoryDiagnostics(options.memoryDirectory);
        report.put("memory_diagnostics", memory == null ? Collections.emptyList() : new ArrayList<Map<String, Object>>());
        Path model = Paths.get(args[0]).toAbsolutePath();
        Path controlsFile = Paths.get(args[2]).toAbsolutePath();
        Path deltaFile = Paths.get(args[3]).toAbsolutePath();
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path path : Arrays.asList(model, controlsFile, deltaFile)) {
            hashes.put(path.toString(), hash(Files.readAllBytes(path)));
        }
        report.put("input_sha256", hashes);
        Set<String> controls = new TreeSet<>();
        for (String line : Files.readAllLines(controlsFile, StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty()) controls.add(line.trim());
        }
        if (controls.contains("tau")) throw new IllegalArgumentException("tau cannot be controllable");
        int monitorCount = Integer.parseInt(args[1]);
        if (monitorCount < 0) throw new IllegalArgumentException("Negative monitor count");
        phase(report, "compile");
        long start = System.nanoTime();
        String source = Files.readString(model, StandardCharsets.UTF_8);
        Component environment;
        List<Component> monitors = new ArrayList<>();
        Map<String, Integer> aliases = new TreeMap<>();
        synchronized (LTSCompiler.class) {
            LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), QUIET, model.getParent().toString());
            compiler.compile();
            CompactState compact = required(compiler, "ENV_COMPONENT");
            environment = new Component(compact);
            int marker = source.indexOf("||LOAD_ENV");
            if (marker < 0) throw new IllegalArgumentException("Missing LOAD_ENV boundary after environment");
            Matcher names = Pattern.compile("(?m)^\\s*(Q[0-9]+)\\s*=").matcher(source.substring(0, marker));
            while (names.find()) {
                Integer id = compact.getStateId(names.group(1));
                if (id == null) throw new IllegalArgumentException("Unknown environment alias " + names.group(1));
                aliases.put(names.group(1), id);
            }
            if (!Integer.valueOf(0).equals(aliases.get("Q0"))) {
                throw new IllegalArgumentException("Q0 must be the initial environment state");
            }
            for (int i = 0; i < monitorCount; i++) {
                monitors.add(new Component(required(compiler, String.format("MON_%03d", i))));
            }
        }
        List<EnvEdge> changes = readChanges(deltaFile, aliases, controls);
        timing.put("compile_and_input", System.nanoTime() - start);
        report.put("monitor_count", monitors.size());
        report.put("environment_states", environment.rows.size());
        report.put("requested_environment_edges", changes.size());
        if (memory != null) checkpoint(memory, report, "compiled", environment, monitors);

        long initialStart = System.nanoTime();
        phase(report, "initial_product"); start = System.nanoTime();
        EnvironmentSafetyProduct oldProduct = EnvironmentSafetyProduct.compose(environment, monitors);
        timing.put("initial_product", System.nanoTime() - start);
        if (memory != null) checkpoint(memory, report, "old_product", oldProduct);
        phase(report, "initial_index"); start = System.nanoTime();
        SafetyGameSnapshot<Long, String> initialSnapshot = prepare(oldProduct, controls);
        timing.put("initial_index", System.nanoTime() - start);
        if (memory != null) checkpoint(memory, report, "old_index", oldProduct, initialSnapshot);
        phase(report, "initial_analysis"); start = System.nanoTime();
        Analysis<Long, String> oldAnalysis = FullSafetySynthesis.analyze(initialSnapshot);
        timing.put("initial_analysis", System.nanoTime() - start);
        phase(report, "initial_controller"); start = System.nanoTime();
        Optional<SafetyController<Long, String>> oldController = FullSafetySynthesis.generateControllerSnapshot(oldAnalysis);
        timing.put("initial_controller", System.nanoTime() - start);
        timing.put("initial_total", System.nanoTime() - initialStart);
        report.put("old_game", oldProduct.counts());
        report.put("old_winning_states", oldAnalysis.getWinningStates().size());
        report.put("old_controller_states", oldController.map(c -> c.getStates().size()).orElse(0));
        if (memory != null) checkpoint(memory, report, "old_ready", oldProduct, oldAnalysis, oldController);
        if (!oldController.isPresent()) {
            report.put("status", "BASE_UNREALIZABLE"); return;
        }

        RunHeapMemorySampler sampler = new RunHeapMemorySampler(100);
        sampler.start();
        EnvironmentSafetyProduct updated;
        Analysis<Long, String> analysis;
        Optional<SafetyController<Long, String>> controller = Optional.empty();
        ControllerGenerationProfile generationProfile = null;
        Map<Long, Long> switchStates = new HashMap<>();
        FullUpdateWitness witness = null;
        long updateStart = System.nanoTime();
        try {
            if (method.equals("FULL") && options.fullRetention.equals("compact")) {
                phase(report, "old_guarantee_capture"); start = System.nanoTime();
                witness = new FullUpdateWitness(oldProduct, oldAnalysis, oldController.get());
                // No hidden ownership of old transitions/indexes survives in the witness.
                initialSnapshot = null;
                oldProduct = null;
                oldAnalysis = null;
                oldController = Optional.empty();
                timing.put("old_guarantee_capture", System.nanoTime() - start);
            } else timing.put("old_guarantee_capture", 0L);
            if (memory != null) checkpoint(memory, report, "update_ready", oldProduct, oldAnalysis, oldController, witness);
            phase(report, "environment_update"); start = System.nanoTime();
            EnvUpdate envUpdate = environment.apply(changes);
            timing.put("environment_update", System.nanoTime() - start);
            IncrementalSafetyAnalysis.Result<Long, String> deltaAnalysis = null;
            if (method.equals("FULL")) {
                phase(report, "game_expansion"); start = System.nanoTime();
                updated = EnvironmentSafetyProduct.compose(envUpdate.updated, monitors);
                validateIntroducedActions(witness == null ? oldProduct.alphabet : witness.alphabet(), updated);
                timing.put("game_expansion", System.nanoTime() - start);
                if (memory != null) checkpoint(memory, report, "updated_product", oldProduct, oldAnalysis, oldController, witness, updated);
                phase(report, "game_index"); start = System.nanoTime();
                SafetyGameSnapshot<Long, String> snapshot = prepare(updated, controls);
                timing.put("game_index", System.nanoTime() - start);
                if (memory != null) checkpoint(memory, report, "updated_index", oldProduct, oldAnalysis, oldController, witness, updated, snapshot);
                phase(report, "analysis"); start = System.nanoTime();
                analysis = FullSafetySynthesis.analyze(snapshot);
                timing.put("analysis", System.nanoTime() - start);
                report.put("analysis_backend", "FULL");
                report.put("analysis_algorithm", "full fixed-safety worklist; not divided candidate analysis");
                report.put("reanalyzed_states", analysis.getStates().size());
                report.put("reused_analysis_states", 0);
            } else {
                phase(report, "game_expansion"); start = System.nanoTime();
                Expansion expansion = oldProduct.expand(envUpdate, monitors, oldAnalysis);
                updated = expansion.product;
                timing.put("game_expansion", System.nanoTime() - start);
                if (memory != null) checkpoint(memory, report, "updated_product", oldProduct, oldAnalysis, oldController, updated, expansion);
                phase(report, "game_index"); start = System.nanoTime();
                SafetyGameUpdate<Long, String> update = SafetyGameUpdate.apply(oldAnalysis, expansion.delta);
                timing.put("game_index", System.nanoTime() - start);
                if (memory != null) checkpoint(memory, report, "updated_index", oldProduct, oldAnalysis, oldController, updated, expansion, update);
                phase(report, "analysis"); start = System.nanoTime();
                // Applicability checking and any fallback are part of analysis,
                // never unreported preparation outside this phase's timer.
                CachedLosingSafetyAnalysis.Outcome<Long, String> outcome = CachedLosingSafetyAnalysis.analyze(update);
                deltaAnalysis = outcome.getResult();
                analysis = deltaAnalysis.getAnalysis();
                timing.put("analysis", System.nanoTime() - start);
                report.put("analysis_backend", outcome.getBackend().name());
                report.put("analysis_fallback_reason", outcome.getFallbackReason() == null
                        ? null : outcome.getFallbackReason().name());
                report.put("analysis_algorithm", outcome.getBackend()
                        == CachedLosingSafetyAnalysis.Backend.CACHED_LOSING_PROPAGATION
                        ? "fixed-safety cached losing propagation; not divided candidate analysis"
                        : "predecessor-closure incremental fixed-safety analysis; not divided candidate analysis");
                report.put("reanalyzed_states", deltaAnalysis.getAffectedStates().size());
                report.put("reused_analysis_states", deltaAnalysis.getReusedStateCount());
                report.put("old_tuples_examined", expansion.oldTuplesExamined);
                report.put("changed_old_sources", expansion.changedOldSources);
                report.put("new_tuples_expanded", expansion.newTuplesExpanded);
            }
            if (memory != null) checkpoint(memory, report, "updated_analysis", oldProduct, oldAnalysis, oldController, witness, updated, analysis, deltaAnalysis);
            phase(report, "compatibility"); start = System.nanoTime();
            int lostOldWinning = 0, lostOldController = 0;
            for (Long state : witness == null ? oldAnalysis.getWinningStates() : witness.winningStates()) {
                Long mapped = witness == null ? updated.stateIdFor(oldProduct, state) : witness.mapWinningState(updated, state);
                if (mapped == null || !analysis.getWinningStates().contains(mapped)) lostOldWinning++;
            }
            ControllerUpdateSynthesis.Plan<Long, String> plan = deltaAnalysis == null ? null
                    : ControllerUpdateSynthesis.prepare(oldController.get(), deltaAnalysis);
            if (plan != null) {
                lostOldController = plan.getIncompatibleOldStates().size();
                switchStates = plan.getSwitchStateMapping();
            } else {
                for (Long state : witness == null ? oldController.get().getStates() : witness.controllerStates()) {
                    Long mapped = witness == null ? updated.stateIdFor(oldProduct, state) : witness.mapWinningState(updated, state);
                    if (mapped == null || !analysis.getWinningStates().contains(mapped)) lostOldController++;
                    else switchStates.put(state, mapped);
                }
                if (lostOldController != 0 || !analysis.isRealizable()) switchStates.clear();
            }
            String status = !analysis.isRealizable() ? "UNREALIZABLE"
                    : lostOldWinning != 0 ? "PAPER_WINNING_REGION_INCOMPATIBLE"
                    : lostOldController != 0 ? "SIMULATION_INCOMPATIBLE" : "SUCCESS";
            if (plan != null && status.equals("SUCCESS") && !plan.isCompatible()) {
                throw new IllegalStateException("Semantic simulation gate and incremental gate disagree");
            }
            // The paper gate can reject an otherwise compatible reachable C.
            // A rejected update has no accepted state-transfer witness.
            if (!status.equals("SUCCESS")) switchStates = Collections.emptyMap();
            timing.put("compatibility", System.nanoTime() - start);
            report.put("lost_old_winning_states", lostOldWinning);
            report.put("lost_old_controller_states", lostOldController);
            report.put("status", status);
            phase(report, "generation"); start = System.nanoTime();
            if (status.equals("SUCCESS")) {
                if (method.equals("DIFFERENTIAL")) {
                    ControllerUpdateSynthesis.Result<Long, String> result = ControllerUpdateSynthesis
                            .generateDifferential(plan, options.seeds, options.profileGeneration);
                    controller = result.getController();
                    generationProfile = result.getGenerationProfile().orElse(null);
                    report.put("generation_expanded_states", result.getExpandedStateCount());
                    report.put("reused_controller_rows", result.getReusedRowCount());
                } else if (options.profileGeneration) {
                    FullSafetySynthesis.ProfiledController<Long, String> result = FullSafetySynthesis
                            .generateControllerSnapshotProfiled(analysis);
                    controller = result.getController();
                    generationProfile = result.getGenerationProfile().orElse(null);
                } else {
                    controller = FullSafetySynthesis.generateControllerSnapshot(analysis);
                }
            }
            timing.put("generation", status.equals("SUCCESS") ? System.nanoTime() - start : null);
            report.put("generation_executed", status.equals("SUCCESS"));
            timing.put("update_total", System.nanoTime() - updateStart);
            if (memory != null) checkpoint(memory, report, "updated_controller", oldProduct, oldAnalysis, oldController, witness, updated, analysis, controller);
        } finally {
            RunHeapMemorySampler.Result heap = sampler.stop();
            report.put("heap_available", heap.isAvailable());
            report.put("update_base_heap_bytes", heap.getFirstHeapUsedBytes());
            report.put("update_peak_heap_bytes", heap.getPeakHeapUsedBytes());
            report.put("heap_sample_count", heap.getSampleCount());
        }
        phase(report, "diagnostics"); start = System.nanoTime();
        // JSON construction is diagnostic work; all actual profiling work is
        // included in the enclosing generation/update timers above.
        if (generationProfile != null) {
            report.put("generation_profile", map("schema_version", 1,
                    "timing_ns", generationProfile.getPhaseNanos(),
                    "counters", generationProfile.getCounters(),
                    "total_ns", generationProfile.getTotalNanos(),
                    "unattributed_ns", generationProfile.getUnattributedNanos()));
        }
        report.put("updated_game", updated.counts());
        report.put("updated_winning_states", analysis.getWinningStates().size());
        int newlyWinningOldStates = 0;
        for (Long state : analysis.getWinningStates()) {
            if (witness != null) {
                if (witness.wasLosing(updated, state)) newlyWinningOldStates++;
            } else {
                Long oldState = oldProduct.stateIdFor(updated, state);
                if (oldState != null && oldAnalysis.getLosingStates().contains(oldState)) newlyWinningOldStates++;
            }
        }
        // This boundary diagnostic is excluded from update/generation timing.
        report.put("newly_winning_old_states", newlyWinningOldStates);
        report.put("added_game_states", updated.rows.size()
                - (witness == null ? oldProduct.rows.size() : witness.gameStateCount()));
        report.put("controller", controllerInfo(controller));
        report.put("switch_state_count", switchStates.size());
        if (controller.isPresent()) {
            Path controllerFile = Paths.get(args[5] + ".controller.tsv").toAbsolutePath();
            try (java.io.BufferedWriter writer = Files.newBufferedWriter(controllerFile,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                SafetyController<Long, String> c = controller.get();
                writer.write("INITIAL\t" + c.getInitialState() + "\n");
                for (Long state : new TreeSet<>(c.getStates())) {
                    writer.write("STATE\t" + state + "\t" + updated.key(state) + "\n");
                    for (Map.Entry<String, Long> edge : new TreeMap<>(c.getTransitions(state)).entrySet()) {
                        writer.write("EDGE\t" + state + "\t" + json(edge.getKey()) + "\t" + edge.getValue() + "\n");
                    }
                }
            }
            report.put("controller_file", controllerFile.toString());
        }
        // Fresh oracle and the same semantic old-state guarantees in either retention mode.
        if (options.verify) {
            phase(report, "verification");
            EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(environment.apply(changes).updated, monitors);
            // Independent legacy LTS solver path, including separate indexing.
            Analysis<Long, String> oracle = FullSafetySynthesis.analyze(fresh.copyLts(), controls, Collections.singleton(ERROR));
            verifyGame(updated, analysis, fresh, oracle);
            int oracleLostW = 0, oracleLostC = 0;
            for (Long state : witness == null ? oldAnalysis.getWinningStates() : witness.winningStates()) {
                Long mapped = witness == null ? fresh.stateIdFor(oldProduct, state) : witness.mapWinningState(fresh, state);
                if (!oracle.getWinningStates().contains(mapped)) oracleLostW++;
            }
            for (Long state : witness == null ? oldController.get().getStates() : witness.controllerStates()) {
                Long mapped = witness == null ? fresh.stateIdFor(oldProduct, state) : witness.mapWinningState(fresh, state);
                if (!oracle.getWinningStates().contains(mapped)) oracleLostC++;
            }
            String expectedStatus = !oracle.isRealizable() ? "UNREALIZABLE"
                    : oracleLostW != 0 ? "PAPER_WINNING_REGION_INCOMPATIBLE"
                    : oracleLostC != 0 ? "SIMULATION_INCOMPATIBLE" : "SUCCESS";
            if (!expectedStatus.equals(report.get("status")) || controller.isPresent() != expectedStatus.equals("SUCCESS")) {
                throw new IllegalStateException("Independent oracle compatibility/status differs");
            }
            if (controller.isPresent()) {
                String expected = (String) controllerInfo(FullSafetySynthesis.generateControllerSnapshot(oracle)).get("rooted_sha256");
                if (!expected.equals(controllerInfo(controller).get("rooted_sha256"))) {
                    throw new IllegalStateException("Full oracle and generated controller differ");
                }
            }
            report.put("verification", "PASS: semantic product, edges, W/L and rooted controller against full LTS solver");
        }
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            if (!entry.getValue().equals(hash(Files.readAllBytes(Paths.get(entry.getKey()))))) {
                throw new IllegalStateException("Input changed during run: " + entry.getKey());
            }
        }
        timing.put("diagnostics_and_validation", System.nanoTime() - start);
        java.lang.ref.Reference.reachabilityFence(oldProduct);
        java.lang.ref.Reference.reachabilityFence(oldAnalysis);
        java.lang.ref.Reference.reachabilityFence(oldController);
        java.lang.ref.Reference.reachabilityFence(witness);
        java.lang.ref.Reference.reachabilityFence(switchStates);
        phase(report, "complete");
    }

    private static void validateIntroducedActions(Set<String> oldAlphabet,
            EnvironmentSafetyProduct updated) {
        Set<String> pending = new TreeSet<>(updated.alphabet);
        pending.removeAll(oldAlphabet);
        if (pending.isEmpty()) return;
        for (Map<String, Long> row : updated.rows.values()) {
            pending.removeAll(row.keySet());
            if (pending.isEmpty()) return;
        }
        throw new IllegalArgumentException("New environment actions have no reachable product edge: " + pending);
    }

    @SuppressWarnings("unchecked")
    private static void checkpoint(SynthesisMemoryDiagnostics memory, Map<String, Object> report,
            String name, Object... retained) throws Exception {
        if (memory == null) return;
        System.out.println("MEMORY_CHECKPOINT\t" + name); System.out.flush();
        ((List<Map<String, Object>>) report.get("memory_diagnostics")).add(memory.checkpoint(name));
        java.lang.ref.Reference.reachabilityFence(retained);
    }

    private static SafetyGameSnapshot<Long, String> prepare(EnvironmentSafetyProduct product, Set<String> controls) {
        return FullSafetySynthesis.prepare(0L, product.rows, product.alphabet, controls, Collections.singleton(ERROR));
    }

    static void verifyGame(EnvironmentSafetyProduct actual, Analysis<Long, String> analysis,
            EnvironmentSafetyProduct expected, Analysis<Long, String> oracle) {
        if (actual.rows.size() != expected.rows.size()) throw new IllegalStateException("Product sizes differ");
        for (Long state : actual.rows.keySet()) {
            Long other = expected.stateIdFor(actual, state);
            if (other == null || analysis.getWinningStates().contains(state) != oracle.getWinningStates().contains(other)) {
                throw new IllegalStateException("Product state/classification differs at " + state);
            }
            Map<String, Long> row = expected.rows.get(other);
            if (row.size() != actual.rows.get(state).size()) throw new IllegalStateException("Outdegree differs");
            for (Map.Entry<String, Long> edge : actual.rows.get(state).entrySet()) {
                if (!java.util.Objects.equals(row.get(edge.getKey()), expected.stateIdFor(actual, edge.getValue()))) {
                    throw new IllegalStateException("Labelled product edge differs");
                }
            }
        }
    }

    private static CompactState required(LTSCompiler compiler, String name) {
        CompactState state = compiler.getProcessCompactStateByName(name);
        if (state == null) throw new IllegalArgumentException("Missing process " + name);
        return state;
    }

    private static List<EnvEdge> readChanges(Path file, Map<String, Integer> aliases, Set<String> controls) throws Exception {
        List<EnvEdge> edges = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !aliases.containsKey(fields[0]) || !aliases.containsKey(fields[2])
                    || fields[1].isEmpty() || fields[1].equals("tau") || fields[1].endsWith("?")) {
                throw new IllegalArgumentException("Expected source alias, action, target alias: " + line);
            }
            if (controls.contains(fields[1])) throw new IllegalArgumentException("Paper mode accepts uncontrollable additions only");
            edges.add(new EnvEdge(aliases.get(fields[0]), fields[1], aliases.get(fields[2])));
        }
        if (edges.isEmpty()) throw new IllegalArgumentException("At least one environment addition is required");
        return edges;
    }

    /** Canonical rooted deterministic graph hash, independent of integer IDs. */
    static Map<String, Object> controllerInfo(Optional<SafetyController<Long, String>> value) throws Exception {
        if (!value.isPresent()) return map("present", false, "states", 0, "edges", 0);
        SafetyController<Long, String> controller = value.get();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<Long, Integer> ids = new HashMap<>(); Queue<Long> pending = new ArrayDeque<>();
        ids.put(controller.getInitialState(), 0); pending.add(controller.getInitialState());
        long edges = 0;
        token(digest, new TreeSet<>(controller.getActions()).toString());
        while (!pending.isEmpty()) {
            Long state = pending.remove(); token(digest, ids.get(state).toString());
            for (Map.Entry<String, Long> edge : new TreeMap<>(controller.getTransitions(state)).entrySet()) {
                if (!ids.containsKey(edge.getValue())) { ids.put(edge.getValue(), ids.size()); pending.add(edge.getValue()); }
                token(digest, edge.getKey()); token(digest, ids.get(edge.getValue()).toString()); edges++;
            }
            token(digest, "END");
        }
        return map("present", true, "states", ids.size(), "edges", edges, "rooted_sha256", hex(digest.digest()));
    }

    private static void token(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
    }
    private static void phase(Map<String, Object> report, String phase) {
        report.put("phase", phase); System.out.println("PHASE\t" + phase); System.out.flush();
    }
    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString();
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]); return out;
    }
    private static void write(Path path, Map<String, Object> value) throws Exception {
        Files.writeString(path, json(value) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (out.length() > 1) out.append(','); out.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder out = new StringBuilder("[");
            for (Object entry : (Iterable<?>) value) { if (out.length() > 1) out.append(','); out.append(json(entry)); }
            return out.append(']').toString();
        }
        StringBuilder out = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            switch (ch) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (ch < 32) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch);
            }
        }
        return out.append('"').toString();
    }
}
