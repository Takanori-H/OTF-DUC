package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import ltsa.updatingControllers.checks.TraceLanguageChecker;
import ltsa.updatingControllers.checks.TransitionGraph;
import ltsa.updatingControllers.checks.UpdateRequirementChecker;

public final class TraceCheckCli {

    private TraceCheckCli() {
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
            File leftFile = new File(required(options, "left"));
            File rightFile = new File(required(options, "right"));
            String leftLabel = option(options, "left-label", leftFile.getName());
            String rightLabel = option(options, "right-label", rightFile.getName());
            File output = new File(required(options, "output"));

            TransitionGraph left = TransitionGraph.fromPrintTransitionsFile(leftFile);
            TransitionGraph right = TransitionGraph.fromPrintTransitionsFile(rightFile);
            TraceLanguageChecker.EquivalenceResult result = TraceLanguageChecker.compare(left, right);

            StringBuilder csv = new StringBuilder();
            csv.append("left,right,left_subset_right,right_subset_left,equivalent,")
                    .append("left_not_in_right_witness,right_not_in_left_witness,detail\n");
            csv.append(UpdateRequirementChecker.csv(leftLabel)).append(',')
                    .append(UpdateRequirementChecker.csv(rightLabel)).append(',')
                    .append(result.leftSubsetRight.included).append(',')
                    .append(result.rightSubsetLeft.included).append(',')
                    .append(result.equivalent()).append(',')
                    .append(UpdateRequirementChecker.csv(result.leftSubsetRight.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(result.rightSubsetLeft.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(detail(result)))
                    .append('\n');

            CliFileLTSOutput.ensureParentDirectory(output);
            Files.write(output.toPath(), csv.toString().getBytes(StandardCharsets.UTF_8));
            System.out.println(result.equivalent() ? "EQUIVALENT" : "DIFFERENT");
            return 0;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static String detail(TraceLanguageChecker.EquivalenceResult result) {
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

    private static String option(Map<String, String> options, String key, String defaultValue) {
        String value = options.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp mtsa.jar "
                + "ltsa.updatingControllers.cli.TraceCheckCli "
                + "--left traditional_transitions.txt "
                + "--right stepwise_delayed_transitions.txt "
                + "--output trace_check.csv");
    }
}
