package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSException;
import ltsa.lts.LTSInputString;
import ltsa.updatingControllers.CompositionEvaluationRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Full-pipeline checks for proof-critical delayed-connection obligations. */
public class StepwiseDelayedConnectionProofObligationsIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/"
                    + "StepwiseDelayedConnectionProofObligations.lts";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern MONITOR_LIFTED_CONNECTIONS = Pattern.compile(
            "monitor-lifted hotSwapIn transitions: (\\d+)");
    private static final Pattern ALL_OLD_STATES = Pattern.compile(
            "all old-controller states: (\\d+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void preservesRawHtAndRejectsMissingConnectionCoverage()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled =
                    System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");
                liftsRawTargetsAcrossReachableMonitorHistories();
                failsFastWhenAnOldAnalysisStateHasNoConnectionTarget();
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    private void liftsRawTargetsAcrossReachableMonitorHistories() throws Exception {
        RunResult result = run("Stepwise_CorrelatedHistory");
        assertEquals(result.log, SingleCompositionRunner.EXIT_SUCCESS, result.exitCode);
        assertTrue(result.log, result.transitions.length() > 0L);

        Matcher connection = requireMatch(CONNECTION_RESULT, result.log);
        long connectionCount = Long.parseLong(connection.group(1));
        long oldStatesWithoutTargets = Long.parseLong(connection.group(2));
        Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, result.log);
        long uniqueOldStates = Long.parseLong(unique.group(1));
        long uniqueMappingStates = Long.parseLong(unique.group(2));
        long eligibleMappingStates = Long.parseLong(unique.group(3));
        long allOldStates = Long.parseLong(
                requireMatch(ALL_OLD_STATES, result.log).group(1));
        long monitorLiftedConnections = Long.parseLong(
                requireMatch(MONITOR_LIFTED_CONNECTIONS, result.log).group(1));

        // Raw H_T is constructed before the monitor product: the one raw
        // OldCon state has both mapping targets reached by the alternating
        // old words.  The post-connection monitor product then represents the
        // two SeenMark valuations and lifts both raw targets to each one.
        assertEquals(1L, allOldStates);
        assertEquals(2L, connectionCount);
        assertEquals(4L, monitorLiftedConnections);
        assertEquals(0L, oldStatesWithoutTargets);
        assertEquals(allOldStates, uniqueOldStates);
        assertEquals(2L, uniqueMappingStates);
        assertEquals(uniqueMappingStates, eligibleMappingStates);
        assertTrue(result.log,
                result.log.contains("[Stepwise Delayed DUCS] GR result: winning"));
    }

    private void failsFastWhenAnOldAnalysisStateHasNoConnectionTarget() throws Exception {
        FailureRunResult result = runExpectingFailure("Stepwise_MissingConnectionTarget");
        assertTrue("A partial delayed connection relation must be rejected.\n" + result.log,
                result.failure != null);
        assertTrue("Unexpected failure type: " + result.failure,
                result.failure instanceof LTSException);
        assertTrue("Unexpected failure: " + result.failure,
                result.failure.getMessage() != null
                        && result.failure.getMessage().startsWith(
                        "stepwise_delayed hotSwapIn connection failed for reachable OldCon states:"));

        Matcher connection = requireMatch(CONNECTION_RESULT, result.log);
        assertEquals(1L, Long.parseLong(connection.group(1)));
        assertEquals(1L, Long.parseLong(connection.group(2)));
        Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, result.log);
        assertEquals(1L, Long.parseLong(unique.group(1)));
        assertEquals(1L, Long.parseLong(unique.group(2)));
        assertEquals(1L, Long.parseLong(unique.group(3)));
        assertEquals(2L, Long.parseLong(
                requireMatch(ALL_OLD_STATES, result.log).group(1)));
    }

    private RunResult run(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Proof model is missing: " + model.getAbsolutePath(), model.isFile());
        String runName = runSequence++ + "_" + target;
        File log = temporaryFolder.newFile(runName + ".log");
        File transitions = temporaryFolder.newFile(runName + ".aut");
        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", log.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });
        return new RunResult(
                exitCode,
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8),
                transitions);
    }

    private FailureRunResult runExpectingFailure(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Proof model is missing: " + model.getAbsolutePath(), model.isFile());
        File log = temporaryFolder.newFile(runSequence++ + "_" + target + ".log");
        CliFileLTSOutput output = new CliFileLTSOutput(log);
        CompositionEvaluationRunner.Result result;
        try {
            String source = new String(
                    Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
            File parent = model.getAbsoluteFile().getParentFile();
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
                            .withOpenFileName(model.getAbsolutePath());
            result = CompositionEvaluationRunner.run(request);
        } finally {
            output.close();
        }
        return new FailureRunResult(
                result.getFailure(),
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8));
    }

    private static Matcher requireMatch(Pattern pattern, String log) {
        Matcher matcher = pattern.matcher(log);
        assertTrue("Expected log pattern was not found: " + pattern.pattern() + "\n" + log,
                matcher.find());
        return matcher;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class RunResult {
        private final int exitCode;
        private final String log;
        private final File transitions;

        private RunResult(int exitCode, String log, File transitions) {
            this.exitCode = exitCode;
            this.log = log;
            this.transitions = transitions;
        }
    }

    private static final class FailureRunResult {
        private final Throwable failure;
        private final String log;

        private FailureRunResult(Throwable failure, String log) {
            this.failure = failure;
            this.log = log;
        }
    }
}
