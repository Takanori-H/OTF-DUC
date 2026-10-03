package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Full-pipeline regression for the raw Traditional H_T connection semantics.
 *
 * <p>The fixture obtains OldCon from ordinary DCS and composes the exact old
 * closed loop.  It therefore exercises the supported DUCS input domain without
 * relying on a hand-written history-merging controller quotient.</p>
 */
public class StepwiseDelayedRawHtCrossHistoryIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedRawHtCrossHistory.lts";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern MONITOR_LIFTED_CONNECTIONS = Pattern.compile(
            "monitor-lifted hotSwapIn transitions: (\\d+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void liftsEveryRawHtTargetToEveryOldMonitorValuation() throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled =
                    System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                RunResult traditional = run("Traditional_RawHtCrossHistory");
                RunResult stepwise = run("Stepwise_RawHtCrossHistory");

                assertExactSynthesizedClosedLoop(traditional);
                assertExactSynthesizedClosedLoop(stepwise);
                assertNoController(traditional, "Traditional_RawHtCrossHistory");
                assertNoController(stepwise, "Stepwise_RawHtCrossHistory");

                assertTrue(stepwise.log,
                        stepwise.log.contains(
                                "Traditional connection-shadow preservation required"));

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals(
                        "The single raw old state must retain both raw H_T targets.",
                        2L,
                        Long.parseLong(connection.group(1)));
                assertEquals(0L, Long.parseLong(connection.group(2)));

                Matcher lifted = requireMatch(MONITOR_LIFTED_CONNECTIONS, stepwise.log);
                assertEquals(
                        "The post-connection monitor product must lift both raw targets "
                                + "to both reachable old monitor valuations.",
                        4L,
                        Long.parseLong(lifted.group(1)));

                Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, stepwise.log);
                assertEquals("Raw H_T is computed over the single raw old state.",
                        1L, Long.parseLong(unique.group(1)));
                assertEquals("The same-word traversal must visit both raw shadow targets.",
                        2L, Long.parseLong(unique.group(2)));
                assertEquals(2L, Long.parseLong(unique.group(3)));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    @Test(timeout = 180000)
    public void keepsTheOneToOneAllOldCaseOnTheOrdinaryWinningPath() throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled =
                    System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                RunResult traditional = run("Traditional_RawHtOneToOne");
                RunResult stepwise = run("Stepwise_RawHtOneToOne");

                assertExactSynthesizedClosedLoop(traditional);
                assertExactSynthesizedClosedLoop(stepwise);
                assertController(traditional, "Traditional_RawHtOneToOne");
                assertController(stepwise, "Stepwise_RawHtOneToOne");
                assertFalse(
                        "A one-to-one all-old H_T relation must not force the preservation "
                                + "fallback.\n" + stepwise.log,
                        stepwise.log.contains(
                                "Traditional connection-shadow preservation required"));

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals(1L, Long.parseLong(connection.group(1)));
                assertEquals(0L, Long.parseLong(connection.group(2)));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
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

    private static void assertExactSynthesizedClosedLoop(RunResult result) {
        assertTrue("The ordinary old DCS declaration was not synthesized.\n" + result.log,
                result.log.contains("Composition:ExactSynthOldController = EXACT_OLD_ENV"));
        assertTrue("The synthesized old controller was not composed with EXACT_OLD_ENV.\n"
                        + result.log,
                result.log.contains("Compiled: ExactSynthOldController"));
    }

    private static void assertController(RunResult result, String target) {
        assertEquals("Composition failed for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertTrue("No controller transitions were written for " + target,
                result.transitions.length() > 0L);
    }

    private static void assertNoController(RunResult result, String target) {
        assertNotEquals("Unexpected controller for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertEquals("A losing target must not write controller transitions: " + target,
                0L,
                result.transitions.length());
        assertTrue("The failure must be a synthesis losing result, not a parse/runtime error.\n"
                        + result.log,
                result.log.contains("initial state is losing: there is no controller.")
                        || result.log.contains("[Stepwise Delayed DUCS] GR result: losing")
                        || result.log.contains("There is no controller for model"));
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
}
