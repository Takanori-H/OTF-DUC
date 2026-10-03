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
import static org.junit.Assert.assertTrue;

public class StepwiseDelayedHotSwapInOneToManyRegressionTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String JAVA_MODE_PROPERTY =
            "stepwise.delayed.hotSwapMonitorSemantics";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern ALL_OLD_STATES = Pattern.compile(
            "all old-controller states: (\\d+)");
    private static final Pattern REPORTED_CONNECTIONS = Pattern.compile(
            "hotSwapIn connections: (\\d+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 180000)
    public void preservesOneToManyRelationInDefaultTraditionalAndExplicitStutterModes()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            String previousJavaMode = System.getProperty(JAVA_MODE_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");
                System.clearProperty(JAVA_MODE_PROPERTY);
                preservesLegitimateOneToManyHotSwapInRelation();
                traditionalMonitorSemanticsPreservesLegitimateOneToManyRelation();
                stutterAndTraditionalModesUpdateEveryOneToManyBoundaryTarget();
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
                restoreProperty(JAVA_MODE_PROPERTY, previousJavaMode);
            }
        }
    }

    private void preservesLegitimateOneToManyHotSwapInRelation() throws Exception {
        assertPreservesLegitimateOneToManyRelation("Stepwise_OneToMany");
    }

    private void traditionalMonitorSemanticsPreservesLegitimateOneToManyRelation()
            throws Exception {
        assertPreservesLegitimateOneToManyRelation(
                "Stepwise_TraditionalMonitor_NoTransition_OneToMany");
    }

    private void stutterAndTraditionalModesUpdateEveryOneToManyBoundaryTarget()
            throws Exception {
        File model = modelFile();

        System.setProperty(JAVA_MODE_PROPERTY, "stutter");
        RunResult stutterMode = runTarget(
                model,
                "Stepwise_LegacyMonitorBoundary_OneToMany");
        assertNoController(stutterMode, "explicit legacy stutter monitor configuration");
        assertExercisesOneToManyRelation(stutterMode.log);

        RunResult full = runTarget(model, "Stepwise_TraditionalMonitorBoundary_OneToMany");
        assertNoController(full,
                "explicit Traditional monitor configuration overriding the stutter property");
        assertExercisesOneToManyRelation(full.log);
    }

    private static void assertNoController(RunResult run, String configuration) {
        assertTrue(configuration + " unexpectedly produced a controller\n" + run.log,
                run.exitCode != SingleCompositionRunner.EXIT_SUCCESS);
        assertEquals(0L, run.transitions.length());
        assertTrue(run.log,
                run.log.contains("initial state is losing: there is no controller.")
                        || run.log.contains("[Stepwise Delayed DUCS] GR result: losing")
                        || run.log.contains("There is no controller for model"));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private void assertPreservesLegitimateOneToManyRelation(String target) throws Exception {
        RunResult run = runTarget(modelFile(), target);
        String log = run.log;
        assertEquals("Composition failed; see " + run.output.getAbsolutePath(),
                SingleCompositionRunner.EXIT_SUCCESS,
                run.exitCode);
        assertTrue("No transitions were written", run.transitions.length() > 0);

        Matcher connectionResult = requireMatch(CONNECTION_RESULT, log);
        long connectionCount = Long.parseLong(connectionResult.group(1));
        long oldStatesWithoutTargets = Long.parseLong(connectionResult.group(2));

        Matcher uniqueStates = requireMatch(CONNECTION_UNIQUE_STATES, log);
        long uniqueOldStates = Long.parseLong(uniqueStates.group(1));
        long uniqueMappingStates = Long.parseLong(uniqueStates.group(2));
        long eligibleMappingStates = Long.parseLong(uniqueStates.group(3));

        long allOldStates = Long.parseLong(requireMatch(ALL_OLD_STATES, log).group(1));
        long reportedConnections = Long.parseLong(
                requireMatch(REPORTED_CONNECTIONS, log).group(1));

        // H = {(OldCon, OLD_A), (OldCon, OLD_B)}.
        assertEquals(1L, allOldStates);
        assertEquals(1L, uniqueOldStates);
        assertEquals(2L, uniqueMappingStates);
        assertEquals(2L, eligibleMappingStates);
        assertEquals(2L, connectionCount);
        assertEquals(connectionCount, reportedConnections);
        assertEquals(0L, oldStatesWithoutTargets);

        int finalGrMarker = log.lastIndexOf("[Stepwise Delayed DUCS] GR(1)");
        assertTrue("Final Stepwise GR(1) marker is missing\n" + log, finalGrMarker >= 0);
        String finalGrLog = log.substring(finalGrMarker);
        assertTrue(finalGrLog,
                finalGrLog.contains("Environment after safety is non-deterministic"));
        assertTrue(finalGrLog,
                finalGrLog.contains("Solving a non-deterministic controller synthesis"));
        assertFalse(finalGrLog,
                finalGrLog.contains("Solving a deterministic controller synthesis"));
        assertTrue(finalGrLog, finalGrLog.contains("[Stepwise Delayed DUCS] GR result: winning"));
    }

    private File modelFile() {
        File model = new File(
                "src/test/resources/StepwiseDUCS/StepwiseDelayedHotSwapInOneToMany.lts");
        assertTrue("Regression model is missing: " + model.getAbsolutePath(), model.isFile());
        return model;
    }

    private RunResult runTarget(File model, String target) throws Exception {
        File output = temporaryFolder.newFile(target + ".log");
        File transitions = temporaryFolder.newFile(target + ".aut");
        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", output.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });
        return new RunResult(
                exitCode,
                new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8),
                transitions,
                output);
    }

    private static void assertExercisesOneToManyRelation(String log) {
        Matcher connection = requireMatch(CONNECTION_RESULT, log);
        long connectionCount = Long.parseLong(connection.group(1));
        long uniqueOldStates = Long.parseLong(
                requireMatch(CONNECTION_UNIQUE_STATES, log).group(1));
        assertTrue("Expected at least one old analysis state with multiple targets\n" + log,
                connectionCount > uniqueOldStates);
    }

    private static Matcher requireMatch(Pattern pattern, String log) {
        Matcher matcher = pattern.matcher(log);
        assertTrue("Expected log pattern was not found: " + pattern.pattern() + "\n" + log,
                matcher.find());
        return matcher;
    }

    private static final class RunResult {
        private final int exitCode;
        private final String log;
        private final File transitions;
        private final File output;

        private RunResult(int exitCode, String log, File transitions, File output) {
            this.exitCode = exitCode;
            this.log = log;
            this.transitions = transitions;
            this.output = output;
        }
    }
}
