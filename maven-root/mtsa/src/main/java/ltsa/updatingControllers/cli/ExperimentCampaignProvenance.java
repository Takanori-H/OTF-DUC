package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;

/** Campaign-level provenance and fail-closed output helpers for batch experiments. */
final class ExperimentCampaignProvenance {

    static final String MANIFEST_FILE_NAME = "campaign_manifest.json";
    static final String CONFIG_SNAPSHOT_FILE_NAME = "executed_config.sanitized.yaml";
    static final String HASH_FILE_NAME = "input_hashes.csv";
    static final String ENVIRONMENT_FILE_NAME = "campaign_environment.json";
    static final String JVM_FLAGS_FILE_NAME = "effective_jvm_flags.txt";
    static final String MANIFEST_SCHEMA = "2026-08-04-campaign-v1";

    private ExperimentCampaignProvenance() {
    }

    static void requireFreshOutputs(
            ExperimentConfig config,
            List<File> allRunOutputDirectories,
            List<File> allExpectedCaseArtifacts) throws IOException {
        if (!config.requireFreshOutputDir) {
            return;
        }

        Set<String> checkedDirectories = new LinkedHashSet<String>();
        List<File> directories = new ArrayList<File>();
        directories.add(config.outputDir);
        directories.addAll(allRunOutputDirectories);
        for (File directory : directories) {
            String canonical = directory.getCanonicalPath();
            if (!checkedDirectories.add(canonical) || !directory.exists()) {
                continue;
            }
            if (!directory.isDirectory()) {
                throw new IOException("Fresh output path is not a directory: " + directory);
            }
            if (!isEmptyDirectory(directory.toPath())) {
                throw new IOException("Fresh output directory is not empty: " + directory
                        + ". Choose a new outputDir or empty it explicitly.");
            }
        }

        Set<String> expectedPaths = new LinkedHashSet<String>();
        for (File artifact : allExpectedCaseArtifacts) {
            String canonical = artifact.getCanonicalPath();
            if (!expectedPaths.add(canonical)) {
                throw new IOException("Two configured cases would write the same artifact: "
                        + canonical);
            }
            if (artifact.exists()) {
                throw new IOException("Expected output already exists: " + artifact);
            }
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        }
    }

    static List<ExperimentCase> orderedCasesForRun(ExperimentConfig config, int runIndex) {
        if (!config.alternateMethodOrderByRun) {
            return new ArrayList<ExperimentCase>(config.cases);
        }

        Map<String, Map<String, List<ExperimentCase>>> byVariantAndExample =
                new LinkedHashMap<String, Map<String, List<ExperimentCase>>>();
        for (ExperimentCase experimentCase : config.cases) {
            String variant = normalizeGroupingValue(experimentCase.variant);
            String example = normalizeGroupingValue(experimentCase.example);
            Map<String, List<ExperimentCase>> byExample = byVariantAndExample.get(variant);
            if (byExample == null) {
                byExample = new LinkedHashMap<String, List<ExperimentCase>>();
                byVariantAndExample.put(variant, byExample);
            }
            List<ExperimentCase> group = byExample.get(example);
            if (group == null) {
                group = new ArrayList<ExperimentCase>();
                byExample.put(example, group);
            }
            group.add(experimentCase);
        }

        List<ExperimentCase> ordered = new ArrayList<ExperimentCase>();
        boolean traditionalFirst = runIndex % 2 == 0;
        for (Map<String, List<ExperimentCase>> byExample : byVariantAndExample.values()) {
            for (List<ExperimentCase> group : byExample.values()) {
                ExperimentCase stepwise = null;
                ExperimentCase traditional = null;
                int stepwiseCount = 0;
                int traditionalCount = 0;
                for (ExperimentCase experimentCase : group) {
                    if (isStepwiseMethod(experimentCase.method)) {
                        stepwiseCount++;
                        stepwise = experimentCase;
                    } else if (isTraditionalMethod(experimentCase.method)) {
                        traditionalCount++;
                        traditional = experimentCase;
                    }
                }
                if (group.size() != 2 || stepwiseCount != 1 || traditionalCount != 1) {
                    ExperimentCase first = group.get(0);
                    throw new IllegalArgumentException(
                            "alternateMethodOrderByRun requires exactly one Stepwise and one "
                                    + "Traditional case for each (variant, example), but found "
                                    + group.size() + " cases for variant=" + first.variant
                                    + ", example=" + first.example);
                }
                ordered.add(traditionalFirst ? traditional : stepwise);
                ordered.add(traditionalFirst ? stepwise : traditional);
            }
        }
        return ordered;
    }

    private static String normalizeGroupingValue(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isStepwiseMethod(String method) {
        return normalizeGroupingValue(method).contains("stepwise");
    }

    private static boolean isTraditionalMethod(String method) {
        return normalizeGroupingValue(method).contains("traditional");
    }

    static Snapshot capture(ExperimentConfig config) throws IOException {
        ensureDirectory(config.outputDir);

        File configSnapshot = new File(config.outputDir, CONFIG_SNAPSHOT_FILE_NAME);
        File hashes = new File(config.outputDir, HASH_FILE_NAME);
        File environment = new File(config.outputDir, ENVIRONMENT_FILE_NAME);
        File effectiveJvmFlags = new File(config.outputDir, JVM_FLAGS_FILE_NAME);
        File manifest = new File(config.outputDir, MANIFEST_FILE_NAME);

        byte[] rawConfig = Files.readAllBytes(config.configFile.toPath());
        String sanitized = sanitizeConfig(new String(rawConfig, StandardCharsets.UTF_8));
        writeUtf8Atomically(configSnapshot, sanitized);

        StringBuilder hashCsv = new StringBuilder();
        hashCsv.append("role,path,sha256,size_bytes\n");
        appendHashRow(hashCsv, "config_original", config.configFile);
        appendHashRow(hashCsv, "config_sanitized_snapshot", configSnapshot);
        Set<String> seenLts = new LinkedHashSet<String>();
        for (ExperimentCase experimentCase : config.cases) {
            String canonical = experimentCase.lts.getCanonicalPath();
            if (seenLts.add(canonical)) {
                appendHashRow(hashCsv, "lts", experimentCase.lts);
            }
        }
        File launcher = launcherArtifact();
        if (launcher != null && launcher.isFile()) {
            appendHashRow(hashCsv, "launcher_artifact", launcher);
        } else {
            appendCsvRow(hashCsv, "launcher_artifact", launcher, "UNAVAILABLE", -1L);
        }
        appendClasspathHashes(hashCsv, launcher);
        writeUtf8Atomically(hashes, hashCsv.toString());

        JvmFlagProbe jvmFlagProbe = captureEffectiveJvmFlags(config, effectiveJvmFlags);
        GitInfo git = readGitInfo(config);
        Map<String, Object> environmentValues = new LinkedHashMap<String, Object>();
        environmentValues.put("schemaVersion", MANIFEST_SCHEMA);
        environmentValues.put("capturedAt", now());
        environmentValues.put("javaVersion", System.getProperty("java.version"));
        environmentValues.put("javaVendor", System.getProperty("java.vendor"));
        environmentValues.put("javaHome", System.getProperty("java.home"));
        environmentValues.put("javaVmName", System.getProperty("java.vm.name"));
        environmentValues.put("javaVmVersion", System.getProperty("java.vm.version"));
        environmentValues.put("parentJvmInputArguments",
                ManagementFactory.getRuntimeMXBean().getInputArguments());
        environmentValues.put("configuredChildJavaOptions", config.javaOptions);
        environmentValues.put("childJavaExecutable", javaExecutable());
        environmentValues.put("effectiveJvmFlagsFile", effectiveJvmFlags.getAbsolutePath());
        environmentValues.put("effectiveJvmFlagsProbeCommand", jvmFlagProbe.command);
        environmentValues.put("effectiveJvmFlagsProbeExitCode", jvmFlagProbe.exitCode);
        environmentValues.put("effectiveJvmFlagsProbeTimedOut", jvmFlagProbe.timedOut);
        environmentValues.put("classPath", System.getProperty("java.class.path"));
        environmentValues.put("launcherArtifact",
                launcher == null ? null : launcher.getAbsolutePath());
        environmentValues.put("osName", System.getProperty("os.name"));
        environmentValues.put("osVersion", System.getProperty("os.version"));
        environmentValues.put("osArchitecture", System.getProperty("os.arch"));
        environmentValues.put("availableProcessors",
                Integer.valueOf(Runtime.getRuntime().availableProcessors()));
        captureHardware(environmentValues);
        environmentValues.put("gitRepository", git.repository);
        environmentValues.put("gitCommit", git.commit);
        environmentValues.put("gitDirty", git.dirty);
        environmentValues.put("gitStatus", git.status);
        writeUtf8Atomically(environment, toJson(environmentValues));

        return new Snapshot(
                configSnapshot,
                hashes,
                environment,
                effectiveJvmFlags,
                manifest,
                sha256(config.configFile),
                sha256(configSnapshot),
                launcher == null ? null : launcher.getAbsolutePath(),
                launcher != null && launcher.isFile() ? sha256(launcher) : null,
                git);
    }

    private static JvmFlagProbe captureEffectiveJvmFlags(
            ExperimentConfig config,
            File outputFile) throws IOException {
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(config.javaOptions);
        command.add("-XX:+PrintCommandLineFlags");
        command.add("-XX:+PrintFlagsFinal");
        command.add("-version");
        Process process = null;
        Integer exitCode = null;
        boolean timedOut = false;
        String output;
        Path probeOutput = Files.createTempFile(
                outputFile.getAbsoluteFile().getParentFile().toPath(),
                "jvm-flags-probe-",
                ".txt");
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(probeOutput.toFile())
                    .start();
            if (!process.waitFor(30L, TimeUnit.SECONDS)) {
                timedOut = true;
                process.destroyForcibly();
                process.waitFor(5L, TimeUnit.SECONDS);
            }
            if (!process.isAlive()) {
                exitCode = Integer.valueOf(process.exitValue());
            }
            byte[] bytes = Files.readAllBytes(probeOutput);
            int length = Math.min(bytes.length, 8 * 1024 * 1024);
            output = new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output = "JVM flag probe interrupted: " + e + "\n";
        } catch (IOException e) {
            output = "JVM flag probe failed: " + e + "\n";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(probeOutput);
        }
        StringBuilder recorded = new StringBuilder();
        recorded.append("# command:");
        for (String item : command) {
            recorded.append(' ').append(shellNeutralQuote(item));
        }
        recorded.append('\n')
                .append("# exitCode: ").append(exitCode).append('\n')
                .append("# timedOut: ").append(timedOut).append('\n')
                .append(output);
        writeUtf8Atomically(outputFile, recorded.toString());
        return new JvmFlagProbe(command, exitCode, timedOut);
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return "java";
        }
        return new File(new File(javaHome, "bin"), "java").getPath();
    }

    private static String shellNeutralQuote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String sanitizeConfig(String source) {
        String[] lines = (source == null ? "" : source).split("\\r?\\n", -1);
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("slackwebhookurl:")
                    || trimmed.startsWith("slackincomingwebhookurl:")
                    || trimmed.startsWith("slackwebhook:")) {
                int colon = line.indexOf(':');
                line = line.substring(0, colon + 1) + " \"<redacted>\"";
            }
            line = redactSlackUrl(line);
            output.append(line);
            if (i + 1 < lines.length) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    private static String redactSlackUrl(String text) {
        final String marker = "https://hooks.slack.com/services/";
        String result = text;
        int start = result.indexOf(marker);
        while (start >= 0) {
            int end = start + marker.length();
            while (end < result.length()) {
                char ch = result.charAt(end);
                if (Character.isWhitespace(ch) || ch == '\'' || ch == '"') {
                    break;
                }
                end++;
            }
            result = result.substring(0, start)
                    + "<redacted-slack-webhook-url>"
                    + result.substring(end);
            start = result.indexOf(marker);
        }
        return result;
    }

    private static void appendClasspathHashes(StringBuilder csv, File launcher) throws IOException {
        String classPath = System.getProperty("java.class.path", "");
        String[] entries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        Set<String> seen = new LinkedHashSet<String>();
        if (launcher != null) {
            seen.add(launcher.getCanonicalPath());
        }
        for (String entry : entries) {
            if (entry == null || entry.trim().isEmpty()) {
                continue;
            }
            File file = new File(entry);
            String canonical = file.getCanonicalPath();
            if (seen.add(canonical) && file.isFile()) {
                appendHashRow(csv, "classpath_artifact", file);
            }
        }
    }

    private static void appendHashRow(StringBuilder csv, String role, File file) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Required provenance input is not a regular file: " + file);
        }
        appendCsvRow(csv, role, file, sha256(file), file.length());
    }

    private static void appendCsvRow(
            StringBuilder csv,
            String role,
            File file,
            String sha256,
            long size) throws IOException {
        csv.append(csv(role)).append(',')
                .append(csv(file == null ? "" : file.getCanonicalPath())).append(',')
                .append(csv(sha256)).append(',')
                .append(size < 0L ? "" : Long.toString(size)).append('\n');
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", Integer.valueOf(value & 0xff)));
        }
        return hex.toString();
    }

    private static File launcherArtifact() {
        try {
            URL url = BatchExperimentRunner.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
                return null;
            }
            URI uri = url.toURI();
            return new File(uri).getAbsoluteFile();
        } catch (Exception e) {
            return null;
        }
    }

    private static void captureHardware(Map<String, Object> values) {
        try {
            HardwareAbstractionLayer hardware = new SystemInfo().getHardware();
            CentralProcessor processor = hardware.getProcessor();
            GlobalMemory memory = hardware.getMemory();
            values.put("cpuName", processor.getProcessorIdentifier().getName());
            values.put("cpuIdentifier", processor.getProcessorIdentifier().getIdentifier());
            values.put("cpuPhysicalPackages", Integer.valueOf(processor.getPhysicalPackageCount()));
            values.put("cpuPhysicalCores", Integer.valueOf(processor.getPhysicalProcessorCount()));
            values.put("cpuLogicalProcessors", Integer.valueOf(processor.getLogicalProcessorCount()));
            values.put("physicalMemoryBytes", Long.valueOf(memory.getTotal()));
            values.put("hardwareProvider", "oshi");
        } catch (RuntimeException e) {
            values.put("hardwareProvider", "unavailable");
            values.put("hardwareError", e.toString());
        } catch (LinkageError e) {
            values.put("hardwareProvider", "unavailable");
            values.put("hardwareError", e.toString());
        }
    }

    private static GitInfo readGitInfo(ExperimentConfig config) {
        File repository = findGitRepository(new File(System.getProperty("user.dir", ".")));
        if (repository == null) {
            repository = findGitRepository(config.configFile.getAbsoluteFile().getParentFile());
        }
        if (repository == null) {
            return new GitInfo(null, null, null, "unavailable");
        }
        String commit = runGit(repository, "rev-parse", "HEAD");
        String status = runGit(repository, "status", "--porcelain");
        if (commit == null || status == null) {
            return new GitInfo(repository.getAbsolutePath(), commit, null, "unavailable");
        }
        return new GitInfo(
                repository.getAbsolutePath(),
                commit.trim(),
                Boolean.valueOf(!status.trim().isEmpty()),
                status.trim().isEmpty() ? "clean" : "dirty");
    }

    private static File findGitRepository(File start) {
        File current = start;
        while (current != null) {
            if (new File(current, ".git").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return null;
    }

    private static String runGit(File repository, String... args) {
        List<String> command = new ArrayList<String>();
        command.add("git");
        command.add("-C");
        command.add(repository.getAbsolutePath());
        Collections.addAll(command, args);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(3L, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            byte[] output = readAllBytes(process.getInputStream(), 1024 * 1024);
            return process.exitValue() == 0
                    ? new String(output, StandardCharsets.UTF_8)
                    : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static byte[] readAllBytes(InputStream input, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            int remaining = limit - output.size();
            if (remaining <= 0) {
                break;
            }
            output.write(buffer, 0, Math.min(read, remaining));
        }
        return output.toByteArray();
    }

    static void ensureDirectory(File directory) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Failed to create directory: " + directory);
        }
        if (!directory.isDirectory()) {
            throw new IOException("Not a directory: " + directory);
        }
    }

    static void writeUtf8Atomically(File file, String content) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        ensureDirectory(parent);
        Path temporary = Files.createTempFile(parent.toPath(), file.getName(), ".tmp");
        boolean moved = false;
        try {
            Files.write(
                    temporary,
                    content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(
                        temporary,
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(
                        temporary,
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static String toJson(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder();
        appendJsonValue(builder, values, 0);
        builder.append('\n');
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendJsonValue(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            builder.append("{\n");
            int index = 0;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                indent(builder, indent + 2);
                builder.append('"').append(jsonEscape(entry.getKey())).append("\": ");
                appendJsonValue(builder, entry.getValue(), indent + 2);
                if (++index < map.size()) {
                    builder.append(',');
                }
                builder.append('\n');
            }
            indent(builder, indent);
            builder.append('}');
        } else if (value instanceof Iterable) {
            builder.append('[');
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                if (index++ > 0) {
                    builder.append(", ");
                }
                appendJsonValue(builder, item, indent);
            }
            builder.append(']');
        } else {
            builder.append('"').append(jsonEscape(value.toString())).append('"');
        }
    }

    private static void indent(StringBuilder builder, int count) {
        for (int i = 0; i < count; i++) {
            builder.append(' ');
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

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String now() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static final class Snapshot {
        final File configSnapshotFile;
        final File hashFile;
        final File environmentFile;
        final File effectiveJvmFlagsFile;
        final File manifestFile;
        final String originalConfigSha256;
        final String sanitizedConfigSha256;
        final String launcherArtifact;
        final String launcherArtifactSha256;
        final GitInfo git;

        Snapshot(
                File configSnapshotFile,
                File hashFile,
                File environmentFile,
                File effectiveJvmFlagsFile,
                File manifestFile,
                String originalConfigSha256,
                String sanitizedConfigSha256,
                String launcherArtifact,
                String launcherArtifactSha256,
                GitInfo git) {
            this.configSnapshotFile = configSnapshotFile;
            this.hashFile = hashFile;
            this.environmentFile = environmentFile;
            this.effectiveJvmFlagsFile = effectiveJvmFlagsFile;
            this.manifestFile = manifestFile;
            this.originalConfigSha256 = originalConfigSha256;
            this.sanitizedConfigSha256 = sanitizedConfigSha256;
            this.launcherArtifact = launcherArtifact;
            this.launcherArtifactSha256 = launcherArtifactSha256;
            this.git = git;
        }
    }

    private static final class JvmFlagProbe {
        final List<String> command;
        final Integer exitCode;
        final boolean timedOut;

        JvmFlagProbe(List<String> command, Integer exitCode, boolean timedOut) {
            this.command = new ArrayList<String>(command);
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }
    }

    static final class GitInfo {
        final String repository;
        final String commit;
        final Boolean dirty;
        final String status;

        GitInfo(String repository, String commit, Boolean dirty, String status) {
            this.repository = repository;
            this.commit = commit;
            this.dirty = dirty;
            this.status = status;
        }
    }
}
