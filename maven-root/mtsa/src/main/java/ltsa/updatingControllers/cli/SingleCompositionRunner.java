package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ltsa.lts.CompactState;
import ltsa.differential.nativeintegration.NativeDifferentialResult;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSInputString;
import ltsa.lts.PrintTransitions;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.updatingControllers.CompositionEvaluationRunner;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.checks.UpdateRequirementChecker;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;

public final class SingleCompositionRunner {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_NO_COMPOSITION = 2;
    static final int EXIT_NO_TRANSITION_OUTPUT = 3;
    static final int EXIT_EXCEPTION = 4;
    static final int EXIT_OUT_OF_MEMORY = 5;
    static final int EXIT_METHOD_MISMATCH = 6;
    static final int EXIT_USAGE = 64;

    private SingleCompositionRunner() {
    }

    public static void main(String[] args) {
        int exitCode = runMain(args);
        System.exit(exitCode);
    }

    static int runMain(String[] args) {
        CliFileLTSOutput output = null;
        try {
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("help")) {
                printUsage(System.out);
                return EXIT_SUCCESS;
            }

            File ltsFile = requiredFile(options, "lts");
            String target = required(options, "target");
            File outputFile = requiredFile(options, "output");
            File transitionsFile = requiredFile(options, "transitions");
            File minimizedTransitionsFile = optionalFile(options, "minimized-transitions");
            File minimizedCountsFile = optionalFile(options, "minimized-counts");
            File requirementsCheckFile = optionalFile(options, "requirements-check");
            File executionMetadataFile = optionalFile(options, "execution-metadata");
            String expectedMethod = options.get("expected-method");
            boolean minimize = booleanOption(options, "minimize");
            boolean writeSeparateMinimizedOutput = minimizedTransitionsFile != null || minimizedCountsFile != null;

            output = new CliFileLTSOutput(outputFile);
            String source = new String(Files.readAllBytes(ltsFile.toPath()), StandardCharsets.UTF_8);
            File parent = ltsFile.getAbsoluteFile().getParentFile();
            String currentDirectory = parent == null
                    ? new File(".").getAbsolutePath()
                    : parent.getAbsolutePath();

            long compositionStartNanos = System.nanoTime();
            final CompositionEvaluationRunner.CompilationStep compilerStep =
                    CompositionEvaluationRunner.ltsCompilerStep(
                            new LTSInputString(source),
                            target,
                            currentDirectory);
            CompositionEvaluationRunner.Request request =
                    new CompositionEvaluationRunner.Request(
                            output,
                            new CompositionEvaluationRunner.CompilationStep() {
                                @Override
                                public CompositeState compile(ltsa.lts.LTSOutput compilerOutput)
                                        throws Exception {
                                    CompositeState compiled = compilerStep.compile(compilerOutput);
                                    MethodValidation validation = MethodValidation.inspect(
                                            compiled,
                                            target,
                                            expectedMethod);
                                    if (executionMetadataFile != null) {
                                        validation.write(executionMetadataFile);
                                    }
                                    if (!validation.matches) {
                                        throw new MethodMismatchException(validation.message);
                                    }
                                    return compiled;
                                }
                            })
                            .withCompositionStartNanos(compositionStartNanos)
                            .withOpenFileName(ltsFile.getAbsolutePath())
                            .withSuccessfulCompositionCallback(
                                    new CompositionEvaluationRunner.SuccessfulCompositionCallback() {
                                        @Override public void afterSuccessfulComposition(CompositeState current) {
                                            recordNativeDifferentialMetrics(current);
                                        }
                                    });

            CompositionEvaluationRunner.Result result = CompositionEvaluationRunner.run(request);
            Throwable failure = result.getFailure();
            if (failure instanceof MethodMismatchException) {
                System.err.println(failure.getMessage());
                return EXIT_METHOD_MISMATCH;
            }
            if (failure instanceof OutOfMemoryError) {
                return EXIT_OUT_OF_MEMORY;
            }
            if (failure != null) {
                failure.printStackTrace(System.err);
                return EXIT_EXCEPTION;
            }

            CompositeState current = result.getCompositeState();
            if (current == null || current.composition == null || !result.isSuccessful()) {
                return EXIT_NO_COMPOSITION;
            }

            CompactState selected = selectMachine(current, target);
            if (selected == null) {
                System.err.println("Transition target was not found: " + target);
                return EXIT_NO_TRANSITION_OUTPUT;
            }

            long rawStates = selected.maxStates;
            long rawTransitions = selected.ntransitionsLong();
            if (!minimize || writeSeparateMinimizedOutput || requirementsCheckFile != null) {
                writeTransitions(selected, transitionsFile);
            }
            if (requirementsCheckFile != null) {
                writeRequirementsCheck(ltsFile, target, transitionsFile, requirementsCheckFile);
            }

            if (minimize || writeSeparateMinimizedOutput) {
                long minimizeStart = System.nanoTime();
                CompactState minimized = TransitionSystemDispatcher.minimise(selected, output);
                long minimizeTimeMillis = elapsedMillis(minimizeStart);

                long countStart = System.nanoTime();
                long minimizedStates = minimized.maxStates;
                long minimizedTransitions = minimized.ntransitionsLong();
                long countTimeMillis = elapsedMillis(countStart);

                UpdatingControllerEvaluationRecorder.recordMinimizedOutputController(
                        minimizedStates,
                        minimizedTransitions,
                        countTimeMillis,
                        minimizeTimeMillis);
                output.outln("");
                output.outln("[minimized 出力]");
                output.outln("minimized 出力状態数 : " + minimizedStates + " states");
                output.outln("minimized 出力遷移数 : " + minimizedTransitions + " transitions");
                output.outln("minimize 時間 : " + Math.max(0, minimizeTimeMillis) + " ms");
                UpdatingControllerEvaluationRecorder.writeDataCsvFileIfConfigured(output);

                if (writeSeparateMinimizedOutput) {
                    if (minimizedTransitionsFile != null) {
                        writeTransitions(minimized, minimizedTransitionsFile);
                    }
                    if (minimizedCountsFile != null) {
                        writeMinimizedCounts(
                                minimizedCountsFile,
                                target,
                                rawStates,
                                rawTransitions,
                                minimizedStates,
                                minimizedTransitions,
                                countTimeMillis,
                                minimizeTimeMillis);
                    }
                } else {
                    writeTransitions(minimized, transitionsFile);
                }
            }

            return EXIT_SUCCESS;
        } catch (OutOfMemoryError e) {
            e.printStackTrace(System.err);
            return EXIT_OUT_OF_MEMORY;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            return EXIT_USAGE;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            return EXIT_EXCEPTION;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    static void recordNativeDifferentialMetrics(CompositeState current) {
        if (current.differentialOptions == null) return;
        NativeDifferentialResult result = current.differentialResult;
        if (result == null || result.getMode() != current.differentialOptions.getMode()) {
            throw new IllegalStateException("Native differential synthesis completed without matching measurements");
        }
        String section = "Native differential synthesis";
        UpdatingControllerEvaluationRecorder.recordStableMetric(
                "native_ds_schema_version", section, "Measurement schema", "1", "text", "");
        UpdatingControllerEvaluationRecorder.recordStableMetric(
                "native_ds_backend", section, "Synthesis backend", "mtsa_native", "text", "");
        UpdatingControllerEvaluationRecorder.recordStableMetric(
                "native_ds_method", section, "Executed method",
                NativeDifferentialResult.methodName(result.getMode()), "text", "");
        if (result.getPhaseNanos().containsKey("controller_build")) {
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "native_ds_generation_timing_version", section, "Generation timing schema", "1", "text", "");
        }
        for (Map.Entry<String, Long> phase : result.getPhaseNanos().entrySet()) {
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "native_ds_" + phase.getKey() + "_ns", section, phase.getKey(),
                    Long.toString(phase.getValue()), "ns",
                    "Elapsed time includes GC; generation contains build/conversion/postcompose, build contains copy/patch; do not sum nested phases; absent means not executed");
        }
        for (Map.Entry<String, Long> memory : result.getMemoryBytes().entrySet()) {
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "native_ds_" + memory.getKey() + "_bytes", section, memory.getKey(),
                    Long.toString(memory.getValue()), "bytes",
                    "Request-local sampled heap (100 ms), including retained data; no forced GC; not process RSS");
        }
        for (Map.Entry<String, Long> gc : result.getGcCollectionMillis().entrySet()) {
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "native_ds_" + gc.getKey() + "_gc_collection_ms", section, gc.getKey() + " GC collection time",
                    Long.toString(gc.getValue()), "ms",
                    "JVM-wide GC MXBean interval delta; coarse collection time, not exact pause; absent if unsupported");
        }
        for (Map.Entry<String, Long> gc : result.getGcCollectionCounts().entrySet()) {
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "native_ds_" + gc.getKey() + "_gc_collection_count", section, gc.getKey() + " GC collection count",
                    Long.toString(gc.getValue()), "count",
                    "JVM-wide GC MXBean interval delta; absent if unsupported; nested intervals must not be summed");
        }
    }

    private static CompactState selectMachine(CompositeState current, String target) {
        if (current.composition != null && namesMatch(current.composition.name, target)) {
            return current.composition;
        }
        if (current.machines != null) {
            for (Object machineObject : current.machines) {
                if (machineObject instanceof CompactState) {
                    CompactState machine = (CompactState) machineObject;
                    if (namesMatch(machine.name, target)) {
                        return machine;
                    }
                }
            }
        }

        return null;
    }

    private static boolean namesMatch(String machineName, String target) {
        if (machineName == null || target == null) {
            return false;
        }
        return machineName.equals(target)
                || machineName.equals("||" + target)
                || machineName.endsWith(":" + target);
    }

    private static void writeTransitions(CompactState selected, File transitionsFile) throws Exception {
        CliFileLTSOutput transitionsOutput = new CliFileLTSOutput(transitionsFile);
        try {
            transitionsOutput.clearOutput();
            new PrintTransitions(selected).print(transitionsOutput);
        } finally {
            transitionsOutput.close();
        }
    }

    private static void writeMinimizedCounts(
            File countsFile,
            String target,
            long rawStates,
            long rawTransitions,
            long minimizedStates,
            long minimizedTransitions,
            long countTimeMillis,
            long minimizeTimeMillis) throws Exception {
        CliFileLTSOutput.ensureParentDirectory(countsFile);
        StringBuilder builder = new StringBuilder();
        builder.append("target,metric_key,metric_label,value,unit\n");
        appendMinimizedCountRow(builder, target, "raw_output_update_controller_states",
                "Raw output update controller states", rawStates, "states");
        appendMinimizedCountRow(builder, target, "raw_output_update_controller_transitions",
                "Raw output update controller transitions", rawTransitions, "transitions");
        appendMinimizedCountRow(builder, target, "minimized_output_update_controller_states",
                "Minimized output update controller states", minimizedStates, "states");
        appendMinimizedCountRow(builder, target, "minimized_output_update_controller_transitions",
                "Minimized output update controller transitions", minimizedTransitions, "transitions");
        appendMinimizedCountRow(builder, target, "minimized_output_update_controller_count_time",
                "Minimized output update controller count time", Math.max(0, countTimeMillis), "ms");
        appendMinimizedCountRow(builder, target, "minimized_output_update_controller_minimize_time",
                "Minimized output update controller minimize time", Math.max(0, minimizeTimeMillis), "ms");
        ExperimentCampaignProvenance.writeUtf8Atomically(countsFile, builder.toString());
    }

    private static void appendMinimizedCountRow(
            StringBuilder builder,
            String target,
            String metricKey,
            String metricLabel,
            long value,
            String unit) {
        builder.append(csv(target))
                .append(',')
                .append(csv(metricKey))
                .append(',')
                .append(csv(metricLabel))
                .append(',')
                .append(value)
                .append(',')
                .append(csv(unit))
                .append('\n');
    }

    private static long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                builder.append("\"\"");
            } else {
                builder.append(ch);
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
            } else if (arg.startsWith("--")) {
                String key = arg.substring(2);
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                options.put(key, args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("--" + key + " is required.");
        }
        return value;
    }

    private static File requiredFile(Map<String, String> options, String key) {
        return new File(required(options, key));
    }

    private static File optionalFile(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return new File(value);
    }

    private static boolean booleanOption(Map<String, String> options, String key) {
        String value = options.get(key);
        return value != null
                && ("true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "1".equals(value));
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp mtsa.jar "
                + "ltsa.updatingControllers.cli.SingleCompositionRunner "
                + "--lts file.lts --target Target "
                + "--output output.txt --transitions transitions.txt "
                + "[--minimize true] "
                + "[--minimized-transitions minimized_transitions.txt] "
                + "[--minimized-counts minimized_counts.csv] "
                + "[--requirements-check requirements_check.csv] "
                + "[--expected-method Traditional|stepwise_delayed+SBP|OTF|native_differential_<mode>] "
                + "[--execution-metadata execution.json]");
    }

    private static void writeRequirementsCheck(
            File ltsFile,
            String target,
            File transitionsFile,
            File requirementsCheckFile) throws Exception {
        UpdateRequirementChecker.Report report =
                UpdateRequirementChecker.check(transitionsFile, ltsFile, target);
        CliFileLTSOutput.ensureParentDirectory(requirementsCheckFile);
        Files.write(requirementsCheckFile.toPath(), report.toCsv().getBytes(StandardCharsets.UTF_8));
    }

    private static final class MethodMismatchException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MethodMismatchException(String message) {
            super(message);
        }
    }

    static final class MethodValidation {
        final String target;
        final String expectedMethod;
        final String actualMethod;
        final boolean expectedSbp;
        final boolean actualSbp;
        final String compositeStateClass;
        final String compositeStateName;
        final boolean targetMatches;
        final boolean matches;
        final String message;

        private MethodValidation(
                String target,
                String expectedMethod,
                String actualMethod,
                boolean expectedSbp,
                boolean actualSbp,
                String compositeStateClass,
                String compositeStateName,
                boolean targetMatches,
                boolean matches,
                String message) {
            this.target = target;
            this.expectedMethod = expectedMethod;
            this.actualMethod = actualMethod;
            this.expectedSbp = expectedSbp;
            this.actualSbp = actualSbp;
            this.compositeStateClass = compositeStateClass;
            this.compositeStateName = compositeStateName;
            this.targetMatches = targetMatches;
            this.matches = matches;
            this.message = message;
        }

        static MethodValidation inspect(
                CompositeState current,
                String target,
                String expectedMethod) {
            String expected = normalizeMethod(expectedMethod);
            String actual = "non_updating_controller";
            boolean actualSbp = false;
            if (current != null && current.differentialOptions != null) {
                actual = NativeDifferentialResult.methodName(current.differentialOptions.getMode());
            } else if (current instanceof UpdatingControllerCompositeState) {
                UpdatingControllerCompositeState update =
                        (UpdatingControllerCompositeState) current;
                actualSbp = update.isSafetyBackwardPruning();
                if (update.isOTF()) {
                    actual = "otf";
                } else if (update.isStepwiseDelayed()) {
                    actual = "stepwise_delayed";
                } else if (update.isStepwise()) {
                    actual = "stepwise";
                } else {
                    actual = "traditional";
                }
            }
            boolean expectedSbp = expectsSbp(expectedMethod);
            boolean methodKnown = !"unknown".equals(expected);
            boolean targetMatches = current != null && namesMatch(current.name, target);
            boolean methodMatches = expectedMethod == null || expectedMethod.trim().isEmpty()
                    || (methodKnown && expected.equals(actual) && expectedSbp == actualSbp);
            boolean matches = targetMatches && methodMatches;
            String message = matches
                    ? "method/target validation passed"
                    : "Configured method/target does not match compiled semantics: target=" + target
                            + ", compiledTarget="
                            + (current == null ? "null" : current.name)
                            + ", targetMatches=" + targetMatches
                            + ", expectedMethod=" + expectedMethod
                            + ", expectedMode=" + expected
                            + ", expectedSBP=" + expectedSbp
                            + ", actualMode=" + actual
                            + ", actualSBP=" + actualSbp
                            + ", compositeState="
                            + (current == null ? "null" : current.name);
            return new MethodValidation(
                    target,
                    expectedMethod,
                    actual,
                    expectedSbp,
                    actualSbp,
                    current == null ? null : current.getClass().getName(),
                    current == null ? null : current.name,
                    targetMatches,
                    matches,
                    message);
        }

        private static String normalizeMethod(String method) {
            String normalized = method == null
                    ? ""
                    : method.toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("native_differential_")) {
                return NativeDifferentialResult.isMethodName(normalized) ? normalized : "unknown";
            }
            if (normalized.contains("traditional")) {
                return "traditional";
            }
            if (normalized.contains("stepwise") && normalized.contains("delayed")) {
                return "stepwise_delayed";
            }
            if (normalized.contains("stepwise")) {
                // Paper YAML uses stepwise_delayed+SBP, while some older YAML
                // shortened the label to Stepwise.
                return "stepwise_delayed";
            }
            if (normalized.contains("otf")) {
                return "otf";
            }
            return "unknown";
        }

        private static boolean expectsSbp(String method) {
            String normalized = method == null
                    ? ""
                    : method.toLowerCase(java.util.Locale.ROOT);
            return normalized.contains("sbp")
                    || normalized.contains("safetybackward")
                    || normalized.contains("safety_backward")
                    || normalized.contains("safety-backward");
        }

        void write(File file) throws Exception {
            CliFileLTSOutput.ensureParentDirectory(file);
            StringBuilder json = new StringBuilder();
            json.append("{\n")
                    .append("  \"target\": ").append(json(target)).append(",\n")
                    .append("  \"expectedMethod\": ").append(json(expectedMethod)).append(",\n")
                    .append("  \"actualMethod\": ").append(json(actualMethod)).append(",\n")
                    .append("  \"expectedSbp\": ").append(expectedSbp).append(",\n")
                    .append("  \"actualSbp\": ").append(actualSbp).append(",\n")
                    .append("  \"compositeStateClass\": ")
                    .append(json(compositeStateClass)).append(",\n")
                    .append("  \"compositeStateName\": ")
                    .append(json(compositeStateName)).append(",\n")
                    .append("  \"targetMatches\": ").append(targetMatches).append(",\n")
                    .append("  \"matches\": ").append(matches).append(",\n")
                    .append("  \"message\": ").append(json(message)).append('\n')
                    .append("}\n");
            ExperimentCampaignProvenance.writeUtf8Atomically(file, json.toString());
        }

        private static String json(String value) {
            if (value == null) {
                return "null";
            }
            return "\"" + value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r") + "\"";
        }
    }
}
