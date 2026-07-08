package ltsa.updatingControllers.cli;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ltsa.updatingControllers.checks.TraceLanguageChecker;
import ltsa.updatingControllers.checks.TransitionGraph;
import ltsa.updatingControllers.checks.UpdateRequirementChecker;
import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;

public final class BatchExperimentRunner {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final String STATUS_OUT_OF_MEMORY = "OUT_OF_MEMORY";
    private static final String STATUS_JVM_START_FAILED = "JVM_START_FAILED";
    private static final String STATUS_EXCEPTION = "EXCEPTION";
    private static final String STATUS_NO_COMPOSITION = "NO_COMPOSITION";
    private static final String STATUS_NO_TRANSITION_OUTPUT = "NO_TRANSITION_OUTPUT";

    private BatchExperimentRunner() {
    }

    public static void main(String[] args) {
        int exitCode = runMain(args);
        System.exit(exitCode);
    }

    static int runMain(String[] args) {
        ExperimentConfig config = null;
        boolean dryRun = false;
        int failures = 0;
        String batchStartedAt = now();
        long batchStartedMillis = System.currentTimeMillis();
        try {
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("help")) {
                printUsage(System.out);
                return 0;
            }

            File configFile = new File(required(options, "config"));
            dryRun = Boolean.parseBoolean(options.get("dry-run"));
            config = ExperimentConfig.load(configFile);
            applyBooleanOverride(options, "requirements-check", config, "requirementsCheck");
            applyBooleanOverride(options, "trace-check", config, "traceCheck");

            boolean useRunDirectories = config.runsSpecified;
            for (int runIndex = 1; runIndex <= config.runs; runIndex++) {
                String runLabel = useRunDirectories ? runLabel(runIndex, config.runs) : null;
                File runOutputDir = outputDirForRun(config.outputDir, runLabel);
                List<CompletedCase> completedCases = new ArrayList<CompletedCase>();
                if (runLabel != null) {
                    System.out.println("=== " + runLabel + " / " + config.runs + " ===");
                }

                for (ExperimentCase experimentCase : config.cases) {
                    for (String warning : experimentCase.validationWarnings()) {
                        System.err.println("[WARN] " + experimentCase.id + ": " + warning);
                    }

                    CasePaths paths = CasePaths.create(
                            runOutputDir,
                            experimentCase,
                            config.requirementsCheck,
                            runIndex,
                            config.runs,
                            runLabel);
                    if (dryRun) {
                        System.out.println("[DRY-RUN] "
                                + (runLabel == null ? "" : runLabel + " ")
                                + experimentCase.id
                                + " -> " + paths.outputFile.getPath());
                        continue;
                    }

                    CaseResult result = runCase(config, experimentCase, paths);
                    writeMetaJson(config, experimentCase, paths, result);
                    completedCases.add(new CompletedCase(experimentCase, paths, result));
                    System.out.println("[" + result.status + "] "
                            + (runLabel == null ? "" : runLabel + " ")
                            + experimentCase.id);
                    if (!STATUS_SUCCESS.equals(result.status)) {
                        failures++;
                    }
                }
                if (!dryRun && config.traceCheck) {
                    writeTraceComparisons(runOutputDir, runLabel, completedCases);
                }
            }

            int exitCode = failures == 0 ? 0 : 1;
            if (!dryRun) {
                notifyBatchCompletion(
                        config,
                        failures == 0 ? STATUS_SUCCESS : "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        null);
            }
            return exitCode;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            if (config != null && !dryRun) {
                notifyBatchCompletion(
                        config,
                        "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        e.toString());
            }
            return 64;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            if (config != null && !dryRun) {
                notifyBatchCompletion(
                        config,
                        "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        e.toString());
            }
            return 1;
        }
    }

    private static CaseResult runCase(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths) {

        CaseResult result = new CaseResult();
        result.startedAt = now();
        long timeoutMillis = experimentCase.timeoutMillisOrDefault(config.timeoutMillis);
        Process process = null;
        StreamCopyThread stdoutThread = null;
        StreamCopyThread stderrThread = null;

        try {
            paths.ensureDirectories();
            List<String> command = buildChildCommand(config, experimentCase, paths);
            result.command = command;

            ProcessBuilder builder = new ProcessBuilder(command);
            process = builder.start();

            stdoutThread = new StreamCopyThread(process.getInputStream(), paths.stdoutFile);
            stderrThread = new StreamCopyThread(process.getErrorStream(), paths.stderrFile);
            stdoutThread.start();
            stderrThread.start();

            boolean finished;
            if (timeoutMillis <= 0) {
                result.exitCode = Integer.valueOf(process.waitFor());
                finished = true;
            } else {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (finished) {
                    result.exitCode = Integer.valueOf(process.exitValue());
                }
            }

            if (!finished) {
                result.timedOut = true;
                result.status = STATUS_TIMEOUT;
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            } else {
                result.status = statusFromExitCode(result.exitCode.intValue(), paths);
            }
        } catch (IOException e) {
            result.status = STATUS_JVM_START_FAILED;
            result.errorMessage = e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.status = STATUS_EXCEPTION;
            result.errorMessage = e.toString();
        } finally {
            joinQuietly(stdoutThread);
            joinQuietly(stderrThread);
            result.endedAt = now();
        }

        return result;
    }

    private static List<String> buildChildCommand(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths) {

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(config.javaOptions);
        addInheritedSystemProperty(command, "mtsa.evaluation.enabled");
        addInheritedSystemProperty(command, "updating.controller.evaluation.enabled");
        addInheritedSystemProperty(command, "updating.controller.evaluation.printDetailedReport");
        addSystemProperty(command, "mtsa.evaluation.csvFile", paths.evaluationCsvFile.getPath());
        addSystemProperty(command, "mtsa.evaluation.caseId", experimentCase.id);
        addSystemProperty(command, "mtsa.evaluation.example", experimentCase.example);
        addSystemProperty(command, "mtsa.evaluation.method", experimentCase.method);
        addSystemProperty(command, "mtsa.evaluation.variant", experimentCase.variant);
        addSystemProperty(command, "mtsa.evaluation.target", experimentCase.target);
        addSystemProperty(command, "mtsa.evaluation.ltsFile", experimentCase.lts.getPath());
        addSystemProperty(command, "mtsa.evaluation.configFile", config.configFile.getPath());
        addSystemProperty(command, "mtsa.evaluation.runIndex", Integer.toString(paths.runIndex));
        addSystemProperty(command, "mtsa.evaluation.runCount", Integer.toString(paths.runCount));
        if (paths.runLabel != null) {
            addSystemProperty(command, "mtsa.evaluation.runLabel", paths.runLabel);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SingleCompositionRunner.class.getName());
        command.add("--lts");
        command.add(experimentCase.lts.getPath());
        command.add("--target");
        command.add(experimentCase.target);
        command.add("--output");
        command.add(paths.outputFile.getPath());
        command.add("--transitions");
        command.add(paths.transitionsFile.getPath());
        command.add("--minimized-transitions");
        command.add(paths.minimizedTransitionsFile.getPath());
        command.add("--minimized-counts");
        command.add(paths.minimizedCountsFile.getPath());
        if (paths.requirementsCheckEnabled) {
            command.add("--requirements-check");
            command.add(paths.requirementsCheckFile.getPath());
        }
        return command;
    }

    private static void addSystemProperty(List<String> command, String key, String value) {
        if (value != null) {
            command.add("-D" + key + "=" + value);
        }
    }

    private static void addInheritedSystemProperty(List<String> command, String key) {
        String value = System.getProperty(key);
        if (value != null && !containsSystemProperty(command, key)) {
            command.add("-D" + key + "=" + value);
        }
    }

    private static boolean containsSystemProperty(List<String> command, String key) {
        String prefix = "-D" + key + "=";
        for (String arg : command) {
            if (arg.equals("-D" + key) || arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return "java";
        }
        return new File(new File(javaHome, "bin"), "java").getPath();
    }

    private static String statusFromExitCode(int exitCode, CasePaths paths) {
        if (exitCode == SingleCompositionRunner.EXIT_SUCCESS) {
            if (paths.transitionsFile.exists()
                    && paths.minimizedTransitionsFile.exists()
                    && paths.minimizedCountsFile.exists()
                    && (!paths.requirementsCheckEnabled || paths.requirementsCheckFile.exists())) {
                return STATUS_SUCCESS;
            }
            return STATUS_NO_TRANSITION_OUTPUT;
        }
        if (exitCode == SingleCompositionRunner.EXIT_NO_COMPOSITION) {
            return STATUS_NO_COMPOSITION;
        }
        if (exitCode == SingleCompositionRunner.EXIT_NO_TRANSITION_OUTPUT) {
            return STATUS_NO_TRANSITION_OUTPUT;
        }
        if (exitCode == SingleCompositionRunner.EXIT_OUT_OF_MEMORY) {
            return STATUS_OUT_OF_MEMORY;
        }
        if (stderrIndicatesOutOfMemory(paths.stderrFile)) {
            return STATUS_OUT_OF_MEMORY;
        }
        return STATUS_EXCEPTION;
    }

    private static boolean stderrIndicatesOutOfMemory(File stderrFile) {
        if (stderrFile == null || !stderrFile.isFile()) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(stderrFile.toPath()), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            return text.contains("outofmemoryerror")
                    || text.contains("could not reserve enough space")
                    || text.contains("cannot allocate memory")
                    || text.contains("unable to allocate");
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMetaJson(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths,
            CaseResult result) throws IOException {

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("status", result.status);
        values.put("id", experimentCase.id);
        values.put("example", experimentCase.example);
        values.put("method", experimentCase.method);
        values.put("variant", experimentCase.variant);
        values.put("target", experimentCase.target);
        values.put("lts", experimentCase.lts.getPath());
        values.put("runIndex", Integer.valueOf(paths.runIndex));
        values.put("runCount", Integer.valueOf(paths.runCount));
        if (paths.runLabel != null) {
            values.put("runLabel", paths.runLabel);
        }
        values.put("output", paths.outputFile.getPath());
        values.put("transitions", paths.transitionsFile.getPath());
        values.put("minimizedTransitions", paths.minimizedTransitionsFile.getPath());
        values.put("minimizedCounts", paths.minimizedCountsFile.getPath());
        values.put("requirementsCheckEnabled", Boolean.valueOf(paths.requirementsCheckEnabled));
        values.put("traceCheckEnabled", Boolean.valueOf(config.traceCheck));
        values.put("requirementsCheck",
                paths.requirementsCheckFile == null ? null : paths.requirementsCheckFile.getPath());
        values.put("evaluationCsv", paths.evaluationCsvFile.getPath());
        Map<String, String> minimizedCounts = readMetricCsvValues(paths.minimizedCountsFile);
        putMetricIfPresent(values, minimizedCounts,
                "raw_output_update_controller_states",
                "rawOutputStates");
        putMetricIfPresent(values, minimizedCounts,
                "raw_output_update_controller_transitions",
                "rawOutputTransitions");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_states",
                "minimizedOutputStates");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_transitions",
                "minimizedOutputTransitions");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_count_time",
                "minimizedCountTimeMillis");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_minimize_time",
                "minimizeTimeMillis");
        Map<String, String> requirementStatuses = readRequirementCsvValues(paths.requirementsCheckFile);
        if (paths.requirementsCheckEnabled) {
            values.put("requirementsOverall", requirementStatuses.get("OVERALL"));
        }
        values.put("stdout", paths.stdoutFile.getPath());
        values.put("stderr", paths.stderrFile.getPath());
        values.put("startedAt", result.startedAt);
        values.put("endedAt", result.endedAt);
        values.put("timeoutMillis", Long.valueOf(experimentCase.timeoutMillisOrDefault(config.timeoutMillis)));
        values.put("exitCode", result.exitCode);
        values.put("timedOut", Boolean.valueOf(result.timedOut));
        values.put("javaOptions", config.javaOptions);
        values.put("command", result.command);
        if (result.errorMessage != null) {
            values.put("errorMessage", result.errorMessage);
        }

        CliFileLTSOutput.ensureParentDirectory(paths.metaFile);
        Files.write(paths.metaFile.toPath(), toJson(values).getBytes(StandardCharsets.UTF_8));
    }

    private static void notifyBatchCompletion(
            ExperimentConfig config,
            String status,
            int failures,
            String startedAt,
            String endedAt,
            long elapsedMillis,
            String errorMessage) {
        if (!shouldSendSlackNotification(config, status)) {
            return;
        }
        try {
            sendSlackNotification(
                    config.slackWebhookUrl,
                    config.notifyTimeoutSeconds,
                    buildSlackNotificationText(
                            config,
                            status,
                            failures,
                            startedAt,
                            endedAt,
                            elapsedMillis,
                            errorMessage));
            System.out.println("[NOTIFY] Slack notification sent.");
        } catch (Throwable e) {
            System.err.println("[WARN] Failed to send Slack notification: "
                    + sanitizeNotificationError(config, e));
        }
    }

    private static boolean shouldSendSlackNotification(ExperimentConfig config, String status) {
        if (config == null || isBlank(config.slackWebhookUrl)) {
            return false;
        }
        String notifyOn = config.notifyOn == null
                ? "always"
                : config.notifyOn.toLowerCase(Locale.ROOT);
        if ("never".equals(notifyOn)) {
            return false;
        }
        boolean success = STATUS_SUCCESS.equals(status);
        if ("success".equals(notifyOn)) {
            return success;
        }
        if ("failure".equals(notifyOn)) {
            return !success;
        }
        return true;
    }

    private static String buildSlackNotificationText(
            ExperimentConfig config,
            String status,
            int failures,
            String startedAt,
            String endedAt,
            long elapsedMillis,
            String errorMessage) {
        int totalCases = config.runs * config.cases.size();
        StringBuilder builder = new StringBuilder();
        builder.append("*MTSA experiment batch finished*").append('\n');
        builder.append("Status: ").append(status).append('\n');
        builder.append("Case failures: ").append(failures).append(" / ").append(totalCases).append('\n');
        builder.append("Runs: ").append(config.runs)
                .append(", cases/run: ").append(config.cases.size()).append('\n');
        builder.append("Config: ").append(config.configFile.getPath()).append('\n');
        builder.append("Output: ").append(config.outputDir.getPath()).append('\n');
        builder.append("Started: ").append(startedAt).append('\n');
        builder.append("Ended: ").append(endedAt).append('\n');
        builder.append("Elapsed: ").append(formatElapsed(elapsedMillis));
        if (!isBlank(errorMessage)) {
            builder.append('\n').append("Runner error: ").append(truncate(errorMessage, 500));
        }
        return builder.toString();
    }

    private static void sendSlackNotification(
            String webhookUrl,
            int timeoutSeconds,
            String text) throws IOException {
        int timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        byte[] payload = ("{\"text\":\"" + jsonEscape(text) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                String response = readSmallResponse(connection.getErrorStream());
                throw new IOException("Slack webhook returned HTTP "
                        + responseCode
                        + (response.isEmpty() ? "" : ": " + response));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readSmallResponse(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try {
            byte[] buffer = new byte[1024];
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static String formatElapsed(long elapsedMillis) {
        long totalSeconds = Math.max(0L, elapsedMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds",
                    Long.valueOf(hours),
                    Long.valueOf(minutes),
                    Long.valueOf(seconds));
        }
        return String.format(Locale.ROOT, "%dm %02ds",
                Long.valueOf(minutes),
                Long.valueOf(seconds));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private static String sanitizeNotificationError(ExperimentConfig config, Throwable error) {
        String message = error == null ? "" : error.toString();
        if (config != null && config.slackWebhookUrl != null) {
            message = message.replace(config.slackWebhookUrl, "<slack-webhook-url>");
        }
        return message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void writeTraceComparisons(
            File runOutputDir,
            String runLabel,
            List<CompletedCase> completedCases) {
        Map<String, Map<String, CompletedCase>> groups =
                new LinkedHashMap<String, Map<String, CompletedCase>>();
        for (CompletedCase completedCase : completedCases) {
            if (!STATUS_SUCCESS.equals(completedCase.result.status)
                    || completedCase.paths.transitionsFile == null
                    || !completedCase.paths.transitionsFile.isFile()) {
                continue;
            }
            String role = traceRole(completedCase.experimentCase);
            if (role == null) {
                continue;
            }
            String key = completedCase.experimentCase.example
                    + "|"
                    + variantBase(completedCase.experimentCase.variant);
            Map<String, CompletedCase> group = groups.get(key);
            if (group == null) {
                group = new LinkedHashMap<String, CompletedCase>();
                groups.put(key, group);
            }
            group.put(role, completedCase);
        }

        File outputFile = new File(runOutputDir, "trace_comparisons.csv");
        StringBuilder builder = new StringBuilder();
        builder.append("run_label,example,variant_base,pair,left_id,right_id,")
                .append("left_subset_right,right_subset_left,equivalent,")
                .append("left_not_in_right_witness,right_not_in_left_witness,detail\n");
        for (Map.Entry<String, Map<String, CompletedCase>> entry : groups.entrySet()) {
            Map<String, CompletedCase> group = entry.getValue();
            appendTraceComparison(builder, runLabel, group,
                    "traditional", "stepwise_delayed",
                    "Traditional_vs_stepwise_delayed");
            appendTraceComparison(builder, runLabel, group,
                    "traditional", "stepwise_delayed_sbp",
                    "Traditional_vs_stepwise_delayed_SBP");
            appendTraceComparison(builder, runLabel, group,
                    "traditional_sbp", "stepwise_delayed_sbp",
                    "Traditional_SBP_vs_stepwise_delayed_SBP");
        }
        try {
            CliFileLTSOutput.ensureParentDirectory(outputFile);
            Files.write(outputFile.toPath(), builder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[WARN] Failed to write trace comparison CSV: " + e);
        }
    }

    private static void appendTraceComparison(
            StringBuilder builder,
            String runLabel,
            Map<String, CompletedCase> group,
            String leftRole,
            String rightRole,
            String pairName) {
        CompletedCase left = group.get(leftRole);
        CompletedCase right = group.get(rightRole);
        if (left == null || right == null) {
            return;
        }
        String variantBase = variantBase(left.experimentCase.variant);
        try {
            TransitionGraph leftGraph = TransitionGraph.fromPrintTransitionsFile(left.paths.transitionsFile);
            TransitionGraph rightGraph = TransitionGraph.fromPrintTransitionsFile(right.paths.transitionsFile);
            TraceLanguageChecker.EquivalenceResult result =
                    TraceLanguageChecker.compare(leftGraph, rightGraph);
            builder.append(UpdateRequirementChecker.csv(runLabel == null ? "" : runLabel)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.example)).append(',')
                    .append(UpdateRequirementChecker.csv(variantBase)).append(',')
                    .append(UpdateRequirementChecker.csv(pairName)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.id)).append(',')
                    .append(UpdateRequirementChecker.csv(right.experimentCase.id)).append(',')
                    .append(result.leftSubsetRight.included).append(',')
                    .append(result.rightSubsetLeft.included).append(',')
                    .append(result.equivalent()).append(',')
                    .append(UpdateRequirementChecker.csv(result.leftSubsetRight.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(result.rightSubsetLeft.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(traceDetail(result)))
                    .append('\n');
        } catch (Throwable e) {
            builder.append(UpdateRequirementChecker.csv(runLabel == null ? "" : runLabel)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.example)).append(',')
                    .append(UpdateRequirementChecker.csv(variantBase)).append(',')
                    .append(UpdateRequirementChecker.csv(pairName)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.id)).append(',')
                    .append(UpdateRequirementChecker.csv(right.experimentCase.id)).append(',')
                    .append("ERROR,ERROR,ERROR,,,")
                    .append(UpdateRequirementChecker.csv(e.toString()))
                    .append('\n');
        }
    }

    private static String traceDetail(TraceLanguageChecker.EquivalenceResult result) {
        StringBuilder builder = new StringBuilder();
        if (!result.leftSubsetRight.included) {
            builder.append("left_not_subset_right: ").append(result.leftSubsetRight.detail);
        }
        if (!result.rightSubsetLeft.included) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append("right_not_subset_left: ").append(result.rightSubsetLeft.detail);
        }
        return builder.toString();
    }

    private static String traceRole(ExperimentCase experimentCase) {
        String text = (safeLower(experimentCase.method)
                + " "
                + safeLower(experimentCase.variant)
                + " "
                + safeLower(experimentCase.id));
        if (text.contains("otf")) {
            return null;
        }
        boolean sbp = text.contains("sbp")
                || text.contains("safetybackward")
                || text.contains("safety_backward")
                || text.contains("safety-backward");
        boolean delayed = text.contains("stepwise_delayed")
                || text.contains("stepwisedelayed")
                || (text.contains("stepwise") && text.contains("delayed"));
        boolean traditional = text.contains("traditional");
        if (delayed) {
            return sbp ? "stepwise_delayed_sbp" : "stepwise_delayed";
        }
        if (traditional) {
            return sbp ? "traditional_sbp" : "traditional";
        }
        return null;
    }

    private static String variantBase(String variant) {
        String value = variant == null ? "" : variant;
        value = value.replaceAll("(?i)(^|[_-])sbp($|[_-])", "$1");
        value = value.replaceAll("(?i)safety[_-]?backward[_-]?pruning", "");
        value = value.replaceAll("__+", "_");
        value = value.replaceAll("--+", "-");
        while (value.startsWith("_") || value.startsWith("-")) {
            value = value.substring(1);
        }
        while (value.endsWith("_") || value.endsWith("-")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? "default" : value;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> readMetricCsvValues(File csvFile) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (csvFile == null || !csvFile.isFile()) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", -1);
                if (columns.length >= 4) {
                    values.put(unquoteCsv(columns[1]), unquoteCsv(columns[3]));
                }
            }
        } catch (IOException e) {
            // Keep meta writing best-effort; paths are still recorded above.
        }
        return values;
    }

    private static Map<String, String> readRequirementCsvValues(File csvFile) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (csvFile == null || !csvFile.isFile()) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", -1);
                if (columns.length >= 3) {
                    values.put(unquoteCsv(columns[0]), unquoteCsv(columns[2]));
                }
            }
        } catch (IOException e) {
            // Keep meta writing best-effort; paths are still recorded above.
        }
        return values;
    }

    private static String unquoteCsv(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return value == null ? "" : value;
        }
        return value.substring(1, value.length() - 1).replace("\"\"", "\"");
    }

    private static void putMetricIfPresent(
            Map<String, Object> output,
            Map<String, String> metrics,
            String metricKey,
            String jsonKey) {
        String value = metrics.get(metricKey);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            output.put(jsonKey, Long.valueOf(value));
        } catch (NumberFormatException e) {
            output.put(jsonKey, value);
        }
    }

    private static String toJson(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        int index = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            builder.append("  \"").append(jsonEscape(entry.getKey())).append("\": ");
            appendJsonValue(builder, entry.getValue());
            index++;
            if (index < values.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("}\n");
        return builder.toString();
    }

    private static void appendJsonValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Iterable) {
            builder.append('[');
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                if (index > 0) {
                    builder.append(", ");
                }
                appendJsonValue(builder, item);
                index++;
            }
            builder.append(']');
        } else {
            builder.append('"').append(jsonEscape(value.toString())).append('"');
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                builder.append('\\').append(ch);
            } else if (ch == '\n') {
                builder.append("\\n");
            } else if (ch == '\r') {
                builder.append("\\r");
            } else if (ch == '\t') {
                builder.append("\\t");
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
            } else if ("--dry-run".equals(arg)) {
                options.put("dry-run", "true");
            } else if (arg.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                options.put(arg.substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return options;
    }

    private static void applyBooleanOverride(
            Map<String, String> options,
            String optionName,
            ExperimentConfig config,
            String fieldName) {
        if (!options.containsKey(optionName)) {
            return;
        }
        boolean value = parseBooleanOption(options.get(optionName), optionName);
        if ("requirementsCheck".equals(fieldName)) {
            config.requirementsCheck = value;
        } else if ("traceCheck".equals(fieldName)) {
            config.traceCheck = value;
        }
    }

    private static boolean parseBooleanOption(String value, String optionName) {
        if ("true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "no".equalsIgnoreCase(value)
                || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("--" + optionName + " must be true or false: " + value);
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("--" + key + " is required.");
        }
        return value;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp mtsa.jar "
                + "ltsa.updatingControllers.cli.BatchExperimentRunner "
                + "--config Experiment/configs/run.yaml");
        out.println("");
        out.println("Config format:");
        out.println("  outputDir: Experiment/result");
        out.println("  runs: 5  # optional; writes Experiment/run_01/result, Experiment/run_02/result, ...");
        out.println("  timeoutHours: 16");
        out.println("  requirementsCheck: true  # optional; default false");
        out.println("  traceCheck: true         # optional; default false");
        out.println("  notifyOn: always         # optional; always, success, failure, never");
        out.println("  slackWebhookUrl: https://hooks.slack.com/services/...  # optional; keep out of Git");
        out.println("  notifyTimeoutSeconds: 30 # optional; default 30");
        out.println("  javaOptions:");
        out.println("    - -Xmx32g");
        out.println("    - -Dmtsa.evaluation.enabled=true");
        out.println("  cases:");
        out.println("    - id: workflow_traditional_no_tr");
        out.println("      example: Workflow");
        out.println("      method: Traditional");
        out.println("      variant: no_tr");
        out.println("      lts: Experiment/lts/Workflow/workflow_no_tr.lts");
        out.println("      target: UpdCont");
        out.println("      requirementsCheck: false  # optional per-case override");
        out.println("");
        out.println("CLI top-level defaults:");
        out.println("  --requirements-check true|false");
        out.println("  --trace-check true|false");
    }

    private static String now() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String runLabel(int runIndex, int runCount) {
        int width = Math.max(2, Integer.toString(runCount).length());
        return String.format(Locale.ROOT, "run_%0" + width + "d", Integer.valueOf(runIndex));
    }

    private static File outputDirForRun(File outputDir, String runLabel) {
        if (runLabel == null) {
            return outputDir;
        }
        File parent = outputDir.getParentFile();
        if (parent == null) {
            return new File(new File(runLabel), outputDir.getPath());
        }
        return new File(new File(parent, runLabel), outputDir.getName());
    }

    private static final class CasePaths {
        final File caseDirectory;
        final File outputFile;
        final File transitionsFile;
        final File minimizedTransitionsFile;
        final File minimizedCountsFile;
        final File requirementsCheckFile;
        final boolean requirementsCheckEnabled;
        final File evaluationCsvFile;
        final File stdoutFile;
        final File stderrFile;
        final File metaFile;
        final int runIndex;
        final int runCount;
        final String runLabel;

        private CasePaths(
                File caseDirectory,
                File outputFile,
                File transitionsFile,
                File minimizedTransitionsFile,
                File minimizedCountsFile,
                File requirementsCheckFile,
                boolean requirementsCheckEnabled,
                File evaluationCsvFile,
                File stdoutFile,
                File stderrFile,
                File metaFile,
                int runIndex,
                int runCount,
                String runLabel) {
            this.caseDirectory = caseDirectory;
            this.outputFile = outputFile;
            this.transitionsFile = transitionsFile;
            this.minimizedTransitionsFile = minimizedTransitionsFile;
            this.minimizedCountsFile = minimizedCountsFile;
            this.requirementsCheckFile = requirementsCheckFile;
            this.requirementsCheckEnabled = requirementsCheckEnabled;
            this.evaluationCsvFile = evaluationCsvFile;
            this.stdoutFile = stdoutFile;
            this.stderrFile = stderrFile;
            this.metaFile = metaFile;
            this.runIndex = runIndex;
            this.runCount = runCount;
            this.runLabel = runLabel;
        }

        static CasePaths create(
                File outputDir,
                ExperimentCase experimentCase,
                boolean defaultRequirementsCheck,
                int runIndex,
                int runCount,
                String runLabel) {
            File caseDirectory = new File(
                    new File(outputDir, ExperimentConfig.sanitizePreservingCase(experimentCase.example)),
                    experimentCase.methodFolderName());
            String prefix = experimentCase.filePrefix();
            String target = ExperimentConfig.sanitizePreservingCase(experimentCase.target);
            String caseId = ExperimentConfig.sanitize(experimentCase.id);
            boolean requirementsCheckEnabled =
                    experimentCase.requirementsCheckOrDefault(defaultRequirementsCheck);
            return new CasePaths(
                    caseDirectory,
                    new File(caseDirectory, prefix + "_output.txt"),
                    new File(caseDirectory, prefix + "_transitions_" + target + ".txt"),
                    new File(caseDirectory, caseId + "_minimized_transitions_" + target + ".txt"),
                    new File(caseDirectory, caseId + "_minimized_counts_" + target + ".csv"),
                    requirementsCheckEnabled
                            ? new File(caseDirectory, caseId + "_requirements_check_" + target + ".csv")
                            : null,
                    requirementsCheckEnabled,
                    new File(caseDirectory, caseId + "_evaluation_" + target + ".csv"),
                    new File(caseDirectory, prefix + "_stdout.txt"),
                    new File(caseDirectory, prefix + "_stderr.txt"),
                    new File(caseDirectory, prefix + "_meta.json"),
                    runIndex,
                    runCount,
                    runLabel);
        }

        void ensureDirectories() throws IOException {
            if (!caseDirectory.exists() && !caseDirectory.mkdirs()) {
                throw new IOException("Failed to create directory: " + caseDirectory);
            }
        }
    }

    private static final class CaseResult {
        String status = STATUS_EXCEPTION;
        Integer exitCode;
        boolean timedOut;
        String startedAt;
        String endedAt;
        String errorMessage;
        List<String> command;
    }

    private static final class CompletedCase {
        final ExperimentCase experimentCase;
        final CasePaths paths;
        final CaseResult result;

        CompletedCase(ExperimentCase experimentCase, CasePaths paths, CaseResult result) {
            this.experimentCase = experimentCase;
            this.paths = paths;
            this.result = result;
        }
    }

    private static final class StreamCopyThread extends Thread {
        private final InputStream input;
        private final File outputFile;

        StreamCopyThread(InputStream input, File outputFile) {
            super("stream-copy-" + outputFile.getName());
            this.input = input;
            this.outputFile = outputFile;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                CliFileLTSOutput.ensureParentDirectory(outputFile);
                copy(input, outputFile);
            } catch (IOException ignored) {
                // The meta file still records the child process status.
            }
        }

        private static void copy(InputStream input, File outputFile) throws IOException {
            BufferedInputStream bufferedInput = new BufferedInputStream(input);
            OutputStream output = new FileOutputStream(outputFile, false);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = bufferedInput.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            } finally {
                try {
                    bufferedInput.close();
                } finally {
                    output.close();
                }
            }
        }
    }
}
