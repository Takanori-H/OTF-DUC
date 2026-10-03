package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Full-pipeline regression for the default Traditional hotSwapIn monitor
 * semantics and the explicit legacy-stutter opt-out in Stepwise Delayed DUCS.
 */
public class StepwiseDelayedTraditionalHotSwapMonitorSemanticsIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String JAVA_MODE_PROPERTY =
            "stepwise.delayed.hotSwapMonitorSemantics";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/"
                    + "StepwiseDelayedTraditionalHotSwapMonitorSemantics.lts";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void defaultModeMatchesTraditionalAndExplicitStutterPreservesFastPaths()
            throws Exception {
        // LTSA compiler registries and the Java fallback are process-global,
        // while Surefire is configured for method-level parallelism.
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            String previousJavaMode = System.getProperty(JAVA_MODE_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");
                System.clearProperty(JAVA_MODE_PROPERTY);
                actionPropositionBoundaryUsesTraditionalDefaultAndExplicitStutterOptOut();
                explicitFluentTransitionsAreAppliedAtHotSwapIn();
                noTransitionControlPreservesStutterControllerExactly();
                stutteringTransitionMonitorPreservesStutterControllerExactly();
                javaPropertySelectsBothModesWithoutLtsFlag();
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
                restoreProperty(JAVA_MODE_PROPERTY, previousJavaMode);
            }
        }
    }

    private void actionPropositionBoundaryUsesTraditionalDefaultAndExplicitStutterOptOut()
            throws Exception {
        RunResult traditional = runWithJavaMode(
                "Traditional_HotSwapMonitorBoundary", null);
        assertNoController(traditional, "Traditional_HotSwapMonitorBoundary");

        RunResult defaultMode = runWithJavaMode(
                "Stepwise_LegacyHotSwapMonitorBoundary", null);
        assertNoController(defaultMode, "default Stepwise hotSwap monitor mode");

        RunResult stutter = runWithJavaMode(
                "Stepwise_LegacyHotSwapMonitorBoundary", "stutter");
        assertController(stutter, "explicit stutter mode");
        assertTrue(stutter.log, stutter.log.contains(
                "[Stepwise Delayed DUCS] GR result: winning"));

        RunResult full = runWithJavaMode(
                "Stepwise_TraditionalHotSwapMonitorBoundary", "stutter");
        assertNoController(full, "Stepwise_TraditionalHotSwapMonitorBoundary");
        assertTrue(full.log, full.log.contains(
                "1 hotSwapIn-sensitive goal(s) deferred until after delayed connection"));
        assertTrue(full.log, full.log.contains("goals: total=1 local=1 cross=0"));
    }

    private void explicitFluentTransitionsAreAppliedAtHotSwapIn() throws Exception {
        RunResult traditionalReset = runWithJavaMode(
                "Traditional_ExplicitResetBoundary", null);
        RunResult legacyReset = runWithJavaMode(
                "Stepwise_LegacyExplicitResetBoundary", "stutter");
        RunResult fullReset = runWithJavaMode(
                "Stepwise_TraditionalExplicitResetBoundary", "stutter");
        assertNoController(traditionalReset, "Traditional_ExplicitResetBoundary");
        assertController(legacyReset, "Stepwise_LegacyExplicitResetBoundary");
        assertNoController(fullReset, "Stepwise_TraditionalExplicitResetBoundary");
        assertTrue(fullReset.log, fullReset.log.contains(
                "1 hotSwapIn-sensitive goal(s) deferred until after delayed connection"));

        RunResult traditionalInitiate = runWithJavaMode(
                "Traditional_ExplicitInitiateBoundary", null);
        RunResult legacyInitiate = runWithJavaMode(
                "Stepwise_LegacyExplicitInitiateBoundary", "stutter");
        RunResult fullInitiate = runWithJavaMode(
                "Stepwise_TraditionalExplicitInitiateBoundary", "stutter");
        assertNoController(traditionalInitiate, "Traditional_ExplicitInitiateBoundary");
        assertController(legacyInitiate, "Stepwise_LegacyExplicitInitiateBoundary");
        assertNoController(fullInitiate, "Stepwise_TraditionalExplicitInitiateBoundary");
        assertTrue(fullInitiate.log, fullInitiate.log.contains(
                "1 hotSwapIn-sensitive goal(s) deferred until after delayed connection"));
    }

    private void noTransitionControlPreservesStutterControllerExactly() throws Exception {
        RunResult legacy = runWithJavaMode(
                "Stepwise_LegacyNoTransitionControl", "stutter");
        RunResult full = runWithJavaMode(
                "Stepwise_TraditionalNoTransitionControl", "stutter");

        assertController(legacy, "Stepwise_LegacyNoTransitionControl");
        assertController(full, "Stepwise_TraditionalNoTransitionControl");
        assertEquals(
                "Traditional semantics must not change the no-monitor fast path",
                canonicalTransitions(legacy, "Stepwise_LegacyNoTransitionControl"),
                canonicalTransitions(full, "Stepwise_TraditionalNoTransitionControl"));
        assertTrue(full.log, full.log.contains(
                "0 hotSwapIn-sensitive goal(s) deferred until after delayed connection"));
        assertFalse(full.log, full.log.contains(
                "After Traditional hotSwapIn monitor semantics"));
    }

    private void stutteringTransitionMonitorPreservesStutterControllerExactly()
            throws Exception {
        RunResult legacy = runWithJavaMode(
                "Stepwise_LegacyStutterControl", "stutter");
        RunResult full = runWithJavaMode(
                "Stepwise_TraditionalStutterControl", "stutter");

        assertController(legacy, "Stepwise_LegacyStutterControl");
        assertController(full, "Stepwise_TraditionalStutterControl");
        assertEquals(
                "An already-stuttering monitor must preserve the stutter-mode controller",
                canonicalTransitions(legacy, "Stepwise_LegacyStutterControl"),
                canonicalTransitions(full, "Stepwise_TraditionalStutterControl"));
        assertTrue(full.log, full.log.contains(
                "0 hotSwapIn-sensitive goal(s) deferred until after delayed connection"));
        assertFalse(full.log, full.log.contains(
                "After Traditional hotSwapIn monitor semantics"));
    }

    private void javaPropertySelectsBothModesWithoutLtsFlag() throws Exception {
        RunResult traditional = runWithJavaMode(
                "Stepwise_LegacyHotSwapMonitorBoundary", "traditional");
        assertNoController(traditional, "property-selected Traditional mode");

        RunResult stutter = runWithJavaMode(
                "Stepwise_LegacyHotSwapMonitorBoundary", "stutter");
        assertController(stutter, "property-selected stutter mode");
    }

    private RunResult runWithJavaMode(String target, String mode) throws Exception {
        if (mode == null) {
            System.clearProperty(JAVA_MODE_PROPERTY);
        } else {
            System.setProperty(JAVA_MODE_PROPERTY, mode);
        }
        return run(target);
    }

    private RunResult run(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Regression model is missing: " + model.getAbsolutePath(), model.isFile());
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

    private static void assertController(RunResult result, String target) {
        assertEquals("Composition failed for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertTrue("No transitions were written for " + target,
                result.transitions.length() > 0L);
        assertFalse(result.log, result.log.contains("Composition not controllable"));
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

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String canonicalTransitions(RunResult result, String target)
            throws Exception {
        return new String(Files.readAllBytes(result.transitions.toPath()),
                StandardCharsets.UTF_8).replace(target, "STEPWISE_NO_TRANSITION_CONTROL");
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
