package ltsa.updatingControllers.cli;

import org.junit.After;
import org.junit.Before;
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

public class StepwiseDelayedHotSwapInRegressionTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern ALL_OLD_STATES = Pattern.compile(
            "all old-controller states: (\\d+)");
    private static final Pattern REPORTED_CONNECTIONS = Pattern.compile(
            "hotSwapIn connections: (\\d+)");
    private static final Pattern STAGED_CROSS_STEP = Pattern.compile(
            "\\[Stepwise Delayed DUCS\\] staged cross step (\\d+)\\r?\\n"
                    + "\\s*product scope: ([^\\r\\n]+)\\r?\\n"
                    + "\\s*added stages: ([^\\r\\n]+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String previousEvaluationEnabled;

    @Before
    public void disableEvaluationArtifacts() {
        System.setProperty("java.awt.headless", "true");
        previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
        System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");
    }

    @After
    public void restoreEvaluationSetting() {
        if (previousEvaluationEnabled == null) {
            System.clearProperty(EVALUATION_ENABLED_PROPERTY);
        } else {
            System.setProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
        }
    }

    @Test(timeout = 180000)
    public void identityRelationDoesNotCreateSpuriousHotSwapInTargets() throws Exception {
        File model = new File(
                "MODEL/StepwiseDUCS/Experiment/LTS/EVCharging/"
                        + "EVCharging_3Chargers_MaxSOC1_AdjacentDemandResponsePolicyRollout_Update.lts");
        assertTrue("Regression model is missing: " + model.getAbsolutePath(), model.isFile());

        File output = temporaryFolder.newFile("stepwise_t1.log");
        File transitions = temporaryFolder.newFile("stepwise_t1.aut");
        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", "Stepwise_UpdCont_T1",
                "--output", output.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });

        String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertEquals("Composition failed; see " + output.getAbsolutePath(),
                SingleCompositionRunner.EXIT_SUCCESS,
                exitCode);
        assertTrue("No transitions were written", transitions.length() > 0);

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

        assertTrue("The regression must exercise reachable old-controller states", allOldStates > 0);
        assertEquals("Every old-controller state must have a hotSwapIn target",
                0L,
                oldStatesWithoutTargets);
        assertEquals("A one-to-one identity relation must yield one target per old state",
                allOldStates,
                connectionCount);
        assertEquals(allOldStates, reportedConnections);
        assertEquals(allOldStates, uniqueOldStates);
        assertEquals("Spurious fluent histories must not create extra mapping targets",
                uniqueOldStates,
                uniqueMappingStates);
        assertEquals(uniqueOldStates, eligibleMappingStates);

        int finalGrMarker = log.lastIndexOf("[Stepwise Delayed DUCS] GR(1)");
        assertTrue("Final Stepwise GR(1) marker is missing\n" + log, finalGrMarker >= 0);
        String finalGrLog = log.substring(finalGrMarker);
        assertTrue(finalGrLog, finalGrLog.contains("Environment after safety is deterministic"));
        assertTrue(finalGrLog, finalGrLog.contains("Solving a deterministic controller synthesis"));
        assertFalse(finalGrLog,
                finalGrLog.contains("Solving a non-deterministic controller synthesis"));
        assertTrue(finalGrLog, finalGrLog.contains("[Stepwise Delayed DUCS] GR result: winning"));

        assertCrossStep(log, 1, "{1,2}", "{1,2}");
        assertCrossStep(log, 2, "{1,2,3}", "{3}");
    }

    private static void assertCrossStep(
            String log,
            int expectedStep,
            String expectedProductScope,
            String expectedAddedStages) {
        Matcher matcher = STAGED_CROSS_STEP.matcher(log);
        while (matcher.find()) {
            if (Integer.toString(expectedStep).equals(matcher.group(1))) {
                assertEquals(expectedProductScope, matcher.group(2).trim());
                assertEquals(expectedAddedStages, matcher.group(3).trim());
                return;
            }
        }
        assertTrue("Staged cross step " + expectedStep + " was not found.\n" + log, false);
    }

    private static Matcher requireMatch(Pattern pattern, String log) {
        Matcher matcher = pattern.matcher(log);
        assertTrue("Expected log pattern was not found: " + pattern.pattern() + "\n" + log,
                matcher.find());
        return matcher;
    }
}
