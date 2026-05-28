package ltsa.updatingControllers.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSInputString;
import ltsa.lts.PrintTransitions;
import ltsa.updatingControllers.CompositionEvaluationRunner;

public final class SingleCompositionRunner {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_NO_COMPOSITION = 2;
    static final int EXIT_NO_TRANSITION_OUTPUT = 3;
    static final int EXIT_EXCEPTION = 4;
    static final int EXIT_OUT_OF_MEMORY = 5;
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

            output = new CliFileLTSOutput(outputFile);
            String source = new String(Files.readAllBytes(ltsFile.toPath()), StandardCharsets.UTF_8);
            File parent = ltsFile.getAbsoluteFile().getParentFile();
            String currentDirectory = parent == null
                    ? new File(".").getAbsolutePath()
                    : parent.getAbsolutePath();

            CompositionEvaluationRunner.Request request =
                    new CompositionEvaluationRunner.Request(
                            output,
                            CompositionEvaluationRunner.ltsCompilerStep(
                                    new LTSInputString(source),
                                    target,
                                    currentDirectory))
                            .withOpenFileName(ltsFile.getAbsolutePath());

            CompositionEvaluationRunner.Result result = CompositionEvaluationRunner.run(request);
            Throwable failure = result.getFailure();
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

            writeTransitions(selected, transitionsFile);
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

        return current.composition;
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

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp mtsa.jar "
                + "ltsa.updatingControllers.cli.SingleCompositionRunner "
                + "--lts file.lts --target Target "
                + "--output output.txt --transitions transitions.txt");
    }
}
