package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ExperimentConfig {

    static final long DEFAULT_TIMEOUT_MILLIS = 16L * 60L * 60L * 1000L;

    final File configFile;
    File outputDir = new File("Experiment/result");
    long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;
    int runs = 1;
    boolean runsSpecified = false;
    final List<String> javaOptions = new ArrayList<String>();
    final List<ExperimentCase> cases = new ArrayList<ExperimentCase>();

    private ExperimentConfig(File configFile) {
        this.configFile = configFile;
    }

    static ExperimentConfig load(File configFile) throws IOException {
        ExperimentConfig config = new ExperimentConfig(configFile);
        List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
        String section = "";
        ExperimentCase currentCase = null;

        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String rawLine = lines.get(lineNumber);
            String line = stripComment(rawLine).trim();
            if (line.isEmpty()) {
                continue;
            }

            if ("javaOptions:".equals(line)) {
                section = "javaOptions";
                continue;
            }
            if ("cases:".equals(line)) {
                section = "cases";
                continue;
            }

            if ("javaOptions".equals(section) && line.startsWith("- ")) {
                config.javaOptions.add(unquote(line.substring(2).trim()));
                continue;
            }

            if ("cases".equals(section) && line.startsWith("- ")) {
                currentCase = new ExperimentCase();
                config.cases.add(currentCase);
                String inline = line.substring(2).trim();
                if (!inline.isEmpty()) {
                    setCaseValue(currentCase, inline, lineNumber);
                }
                continue;
            }

            if ("cases".equals(section) && currentCase != null) {
                setCaseValue(currentCase, line, lineNumber);
                continue;
            }

            setConfigValue(config, line, lineNumber);
        }

        config.validate();
        return config;
    }

    private static void setConfigValue(ExperimentConfig config, String line, int lineNumber) {
        KeyValue keyValue = parseKeyValue(line, lineNumber);
        if ("outputDir".equals(keyValue.key)) {
            config.outputDir = new File(keyValue.value);
        } else if ("runs".equals(keyValue.key)
                || "runCount".equals(keyValue.key)
                || "repetitions".equals(keyValue.key)) {
            config.runs = parsePositiveInt(keyValue.value, keyValue.key, lineNumber);
            config.runsSpecified = true;
        } else if ("timeoutHours".equals(keyValue.key)) {
            config.timeoutMillis = hoursToMillis(keyValue.value);
        } else if ("timeoutMinutes".equals(keyValue.key)) {
            config.timeoutMillis = minutesToMillis(keyValue.value);
        } else {
            throw new IllegalArgumentException("Unknown config key at line "
                    + (lineNumber + 1) + ": " + keyValue.key);
        }
    }

    private static void setCaseValue(ExperimentCase experimentCase, String line, int lineNumber) {
        KeyValue keyValue = parseKeyValue(line, lineNumber);
        if ("id".equals(keyValue.key)) {
            experimentCase.id = keyValue.value;
        } else if ("example".equals(keyValue.key)) {
            experimentCase.example = keyValue.value;
        } else if ("method".equals(keyValue.key)) {
            experimentCase.method = keyValue.value;
        } else if ("variant".equals(keyValue.key)
                || "transitionRequirement".equals(keyValue.key)) {
            experimentCase.variant = keyValue.value;
        } else if ("lts".equals(keyValue.key)) {
            experimentCase.lts = new File(keyValue.value);
        } else if ("target".equals(keyValue.key)) {
            experimentCase.target = keyValue.value;
        } else if ("timeoutHours".equals(keyValue.key)) {
            experimentCase.timeoutMillis = Long.valueOf(hoursToMillis(keyValue.value));
        } else if ("timeoutMinutes".equals(keyValue.key)) {
            experimentCase.timeoutMillis = Long.valueOf(minutesToMillis(keyValue.value));
        } else {
            throw new IllegalArgumentException("Unknown case key at line "
                    + (lineNumber + 1) + ": " + keyValue.key);
        }
    }

    private void validate() {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("No cases are defined in " + configFile);
        }

        for (int i = 0; i < cases.size(); i++) {
            ExperimentCase experimentCase = cases.get(i);
            if (isBlank(experimentCase.example)) {
                throw new IllegalArgumentException("cases[" + i + "].example is required.");
            }
            if (isBlank(experimentCase.method)) {
                throw new IllegalArgumentException("cases[" + i + "].method is required.");
            }
            if (experimentCase.lts == null) {
                throw new IllegalArgumentException("cases[" + i + "].lts is required.");
            }
            if (isBlank(experimentCase.target)) {
                throw new IllegalArgumentException("cases[" + i + "].target is required.");
            }
            if (isBlank(experimentCase.variant)) {
                experimentCase.variant = "no_tr";
            }
            if (isBlank(experimentCase.id)) {
                experimentCase.id = sanitize(experimentCase.example)
                        + "_"
                        + sanitize(experimentCase.method)
                        + "_"
                        + sanitize(experimentCase.variant);
            }
        }
    }

    private static KeyValue parseKeyValue(String line, int lineNumber) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Expected key: value at line "
                    + (lineNumber + 1) + ": " + line);
        }
        String key = line.substring(0, colon).trim();
        String value = unquote(line.substring(colon + 1).trim());
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Empty key at line " + (lineNumber + 1));
        }
        return new KeyValue(key, value);
    }

    private static String stripComment(String line) {
        boolean singleQuote = false;
        boolean doubleQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\'' && !doubleQuote) {
                singleQuote = !singleQuote;
            } else if (ch == '"' && !singleQuote) {
                doubleQuote = !doubleQuote;
            } else if (ch == '#' && !singleQuote && !doubleQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value == null ? "" : value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static long hoursToMillis(String value) {
        return Math.round(Double.parseDouble(value) * 60.0 * 60.0 * 1000.0);
    }

    private static long minutesToMillis(String value) {
        return Math.round(Double.parseDouble(value) * 60.0 * 1000.0);
    }

    private static int parsePositiveInt(String value, String key, int lineNumber) {
        try {
            int result = Integer.parseInt(value);
            if (result < 1) {
                throw new IllegalArgumentException(key + " must be >= 1 at line " + (lineNumber + 1));
            }
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer at line "
                    + (lineNumber + 1) + ": " + value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    static String sanitize(String value) {
        return sanitize(value, true);
    }

    static String sanitizePreservingCase(String value) {
        return sanitize(value, false);
    }

    private static String sanitize(String value, boolean lowerCase) {
        String source = value == null ? "" : value.trim();
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                builder.append(lowerCase ? Character.toLowerCase(ch) : ch);
            } else if (ch == '_' || ch == '-') {
                builder.append('_');
            } else if (builder.length() == 0
                    || builder.charAt(builder.length() - 1) != '_') {
                builder.append('_');
            }
        }
        String result = builder.toString();
        while (result.startsWith("_")) {
            result = result.substring(1);
        }
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "case" : result;
    }

    static final class ExperimentCase {
        String id;
        String example;
        String method;
        String variant;
        File lts;
        String target;
        Long timeoutMillis;

        long timeoutMillisOrDefault(long defaultValue) {
            return timeoutMillis == null ? defaultValue : timeoutMillis.longValue();
        }

        String methodFolderName() {
            String normalized = method == null
                    ? ""
                    : method.toLowerCase(Locale.ROOT);
            if (normalized.contains("traditional")) {
                return "Traditional";
            }
            if (normalized.contains("otf")) {
                return "OTF";
            }
            return sanitizePreservingCase(method);
        }

        String methodFilePrefix() {
            String normalized = method == null
                    ? ""
                    : method.toLowerCase(Locale.ROOT);
            if (normalized.contains("traditional")) {
                return "traditional";
            }
            if (normalized.contains("otf")) {
                return "otf";
            }
            return sanitize(method);
        }

        String filePrefix() {
            return methodFilePrefix() + "_" + sanitize(variant);
        }

        List<String> validationWarnings() {
            if (lts != null && !lts.exists()) {
                return Collections.singletonList("LTS file does not exist yet: " + lts);
            }
            return Collections.emptyList();
        }
    }

    private static final class KeyValue {
        final String key;
        final String value;

        KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }
}
