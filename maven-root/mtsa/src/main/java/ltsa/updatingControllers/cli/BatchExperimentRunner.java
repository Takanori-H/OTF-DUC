package ltsa.updatingControllers.cli;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
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
        try {
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("help")) {
                printUsage(System.out);
                return 0;
            }

            File configFile = new File(required(options, "config"));
            boolean dryRun = Boolean.parseBoolean(options.get("dry-run"));
            ExperimentConfig config = ExperimentConfig.load(configFile);
            int failures = 0;

            boolean useRunDirectories = config.runsSpecified;
            for (int runIndex = 1; runIndex <= config.runs; runIndex++) {
                String runLabel = useRunDirectories ? runLabel(runIndex, config.runs) : null;
                File runOutputDir = outputDirForRun(config.outputDir, runLabel);
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
                    System.out.println("[" + result.status + "] "
                            + (runLabel == null ? "" : runLabel + " ")
                            + experimentCase.id);
                    if (!STATUS_SUCCESS.equals(result.status)) {
                        failures++;
                    }
                }
            }

            return failures == 0 ? 0 : 1;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            return 64;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
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
        return command;
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
            if (paths.transitionsFile.exists()) {
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
                File stdoutFile,
                File stderrFile,
                File metaFile,
                int runIndex,
                int runCount,
                String runLabel) {
            this.caseDirectory = caseDirectory;
            this.outputFile = outputFile;
            this.transitionsFile = transitionsFile;
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
                int runIndex,
                int runCount,
                String runLabel) {
            File caseDirectory = new File(
                    new File(outputDir, ExperimentConfig.sanitizePreservingCase(experimentCase.example)),
                    experimentCase.methodFolderName());
            String prefix = experimentCase.filePrefix();
            String target = ExperimentConfig.sanitizePreservingCase(experimentCase.target);
            return new CasePaths(
                    caseDirectory,
                    new File(caseDirectory, prefix + "_output.txt"),
                    new File(caseDirectory, prefix + "_transitions_" + target + ".txt"),
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
