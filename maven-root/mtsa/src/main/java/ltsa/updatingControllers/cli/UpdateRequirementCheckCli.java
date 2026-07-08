package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import ltsa.updatingControllers.checks.UpdateRequirementChecker;

public final class UpdateRequirementCheckCli {

    private UpdateRequirementCheckCli() {
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
            File transitions = new File(required(options, "transitions"));
            File lts = new File(required(options, "lts"));
            String target = required(options, "target");
            File output = new File(required(options, "output"));
            UpdateRequirementChecker.Report report =
                    UpdateRequirementChecker.check(transitions, lts, target);
            CliFileLTSOutput.ensureParentDirectory(output);
            Files.write(output.toPath(), report.toCsv().getBytes(StandardCharsets.UTF_8));
            System.out.println(report.overallStatus());
            return 0;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
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
                + "ltsa.updatingControllers.cli.UpdateRequirementCheckCli "
                + "--lts file.lts --target Target "
                + "--transitions transitions.txt --output requirements_check.csv");
    }
}
