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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Full-pipeline oracle for a safe/unsafe one-to-many delayed connection. */
public class StepwiseDelayedMixedConnectionTargetsIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedMixedConnectionTargets.lts";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern TERMINAL_STATES = Pattern.compile(
            "online safety terminalization kept states: \\d+ transitions: \\d+ "
                    + "terminal states: (\\d+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void uncontrollableMixedTargetMakesTraditionalAndStepwiseLosing()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                // Establish that the two suffixes are on opposite sides of
                // the synthesis boundary for both algorithms.
                assertController(run("Traditional_SafeConnectionTarget"),
                        "Traditional_SafeConnectionTarget");
                assertController(run("Stepwise_SafeConnectionTarget"),
                        "Stepwise_SafeConnectionTarget");
                assertNoController(run("Traditional_UnsafeConnectionTarget"),
                        "Traditional_UnsafeConnectionTarget");
                assertNoController(run("Stepwise_UnsafeConnectionTarget"),
                        "Stepwise_UnsafeConnectionTarget");

                RunResult traditional = run("Traditional_MixedConnectionTargets");
                RunResult stepwise = run("Stepwise_MixedConnectionTargets");
                assertNoController(traditional, "Traditional_MixedConnectionTargets");
                assertNoController(stepwise, "Stepwise_MixedConnectionTargets");

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals("The mixed relation must retain both hotSwapIn targets.",
                        2L, Long.parseLong(connection.group(1)));
                assertEquals("Coverage must not fail before the mixed branch is analysed.",
                        0L, Long.parseLong(connection.group(2)));

                Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, stepwise.log);
                assertEquals(1L, Long.parseLong(unique.group(1)));
                assertEquals(2L, Long.parseLong(unique.group(2)));
                assertEquals(2L, Long.parseLong(unique.group(3)));

                assertTrue(stepwise.log,
                        stepwise.log.contains("After delayed hotSwapIn connection"));
                assertTrue(stepwise.log,
                        stepwise.log.contains("After Traditional hotSwapIn monitor semantics"));
                Matcher terminalStates = requireMatch(TERMINAL_STATES, stepwise.log);
                assertTrue("The unsafe target must create at least one Error terminal.\n"
                                + stepwise.log,
                        Long.parseLong(terminalStates.group(1)) > 0L);
                assertTrue(stepwise.log,
                        stepwise.log.contains(
                                "safety backward pruning scope: stepwise-delayed-final"));
                assertTrue(stepwise.log,
                        stepwise.log.contains(
                                "initial state is losing: there is no controller."));
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
        File transitions = temporaryFolder.newFile(runName + ".lts");
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
