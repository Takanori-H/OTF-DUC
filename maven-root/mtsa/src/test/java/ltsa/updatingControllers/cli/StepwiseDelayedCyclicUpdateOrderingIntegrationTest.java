package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Full-pipeline regression for update events removed before delayed connection. */
public class StepwiseDelayedCyclicUpdateOrderingIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedCyclicUpdateOrdering.lts";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void individuallyRealizableOrdersRemainWinningButTheirCycleIsLosing()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                assertController(run("TRADITIONAL_STOP_AFTER_START"),
                        "TRADITIONAL_STOP_AFTER_START");
                assertController(run("STEPWISE_STOP_AFTER_START"),
                        "STEPWISE_STOP_AFTER_START");
                assertController(run("TRADITIONAL_START_AFTER_STOP"),
                        "TRADITIONAL_START_AFTER_STOP");
                assertController(run("STEPWISE_START_AFTER_STOP"),
                        "STEPWISE_START_AFTER_STOP");

                assertNoController(run("TRADITIONAL_CYCLIC_ORDERING"),
                        "TRADITIONAL_CYCLIC_ORDERING");
                RunResult stepwise = run("STEPWISE_CYCLIC_ORDERING");
                assertNoController(stepwise, "STEPWISE_CYCLIC_ORDERING");
                assertTrue("The regression must reach delayed connection before rejecting.\n"
                                + stepwise.log,
                        stepwise.log.contains("After delayed hotSwapIn connection"));
                assertTrue("The cyclic requirements must be rejected by the final game.\n"
                                + stepwise.log,
                        stepwise.log.contains("[Stepwise Delayed DUCS] GR result: losing"));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    private RunResult run(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Regression model is missing: " + model.getAbsolutePath(), model.isFile());
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
        assertTrue("The failure must be a losing synthesis result, not a parse/runtime error.\n"
                        + result.log,
                result.log.contains("initial state is losing: there is no controller.")
                        || result.log.contains("[Stepwise Delayed DUCS] GR result: losing")
                        || result.log.contains("There is no controller for model"));
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
