package ltsa.updatingControllers.cli;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSInputString;
import ltsa.updatingControllers.CompositionEvaluationRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Full-pipeline regression for the three transition-requirement variants used
 * by the Stepwise DUCS evaluation.  Traditional and Stepwise targets are kept
 * in the same LTS so that they use the same controller, environments, mapping
 * relations, and old/new goals.
 */
public class StepwiseTransitionRequirementsFullPipelineIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "MODEL/StepwiseDUCS/Experiment/LTS/EVCharging/"
                    + "EVCharging_2Chargers_DemandResponsePolicyRollout_Update.lts";

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

    @Test(timeout = 600000)
    public void noTrT1AndT2SucceedForTraditionalAndStepwise() throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Validation model is missing: " + model.getAbsolutePath(), model.isFile());

        assertCompositionSucceeds(model, "UpdCont", false, TransitionVariant.NO_TR);
        assertCompositionSucceeds(model, "UpdCont_T1", false, TransitionVariant.T1);
        assertCompositionSucceeds(model, "UpdCont_T2", false, TransitionVariant.T2);
        assertCompositionSucceeds(model, "Stepwise_UpdCont", true, TransitionVariant.NO_TR);
        assertCompositionSucceeds(model, "Stepwise_UpdCont_T1", true, TransitionVariant.T1);
        assertCompositionSucceeds(model, "Stepwise_UpdCont_T2", true, TransitionVariant.T2);
    }

    @Test(timeout = 300000)
    public void t1PreservesGloballyDisabledRatesInBothFinalAlphabets() throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Validation model is missing: " + model.getAbsolutePath(), model.isFile());

        assertT1FinalAlphabet(model, "UpdCont_T1");
        assertT1FinalAlphabet(model, "Stepwise_UpdCont_T1");
    }

    private void assertT1FinalAlphabet(File model, String target) throws Exception {
        File logFile = temporaryFolder.newFile(target + "_alphabet.log");
        CliFileLTSOutput output = new CliFileLTSOutput(logFile);
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

        String log = new String(Files.readAllBytes(logFile.toPath()), StandardCharsets.UTF_8);
        assertTrue(log, result.isSuccessful());
        assertNotNull(result.getCompositeState());
        CompositeState current = result.getCompositeState();
        assertNotNull(current.composition);

        MTS<Long, String> controller =
                AutomataToMTSConverter.getInstance().convert(current.composition);
        Set<String> globallyDisabledRates = new LinkedHashSet<String>(Arrays.asList(
                "charger.1.startCharge.5",
                "charger.1.startCharge.6",
                "charger.1.setRate.5",
                "charger.1.setRate.6",
                "charger.2.startCharge.5",
                "charger.2.startCharge.6",
                "charger.2.setRate.5",
                "charger.2.setRate.6"));

        for (String action : globallyDisabledRates) {
            assertTrue("Missing final controller alphabet entry: " + action,
                    controller.getActions().contains(action));
            assertEquals("Alphabet preservation must not restore transitions for " + action,
                    0,
                    transitionCount(controller, action));
        }
        for (String action : controller.getActions()) {
            assertFalse(".old is an internal pre-output label: " + action,
                    action.endsWith(".old"));
        }
    }

    private void assertCompositionSucceeds(
            File model,
            String target,
            boolean stepwise,
            TransitionVariant transitionVariant) throws Exception {
        File output = temporaryFolder.newFile(target + ".log");
        File transitions = temporaryFolder.newFile(target + ".aut");

        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", output.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });

        String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertEquals("Composition failed for " + target + "; see " + output.getAbsolutePath(),
                SingleCompositionRunner.EXIT_SUCCESS,
                exitCode);
        assertTrue("No transitions were written for " + target, transitions.length() > 0);
        assertFalse(log, log.contains("Composition not controllable"));
        assertFalse(log, log.contains("GOAL_ACTION_NOT_FOUND"));
        assertTrue(log, log.contains("Environment after safety is deterministic"));
        assertTrue(log, log.contains("Solving a deterministic controller synthesis"));
        assertFalse(log, log.contains("Solving a non-deterministic controller synthesis"));

        if (!stepwise) {
            return;
        }

        assertTrue(log, log.contains("[Stepwise Delayed DUCS] GR result: winning"));
        switch (transitionVariant) {
            case NO_TR:
                assertFalse(log, log.contains(" (TRANSITION) -> "));
                break;
            case T1:
                assertTrue(log, log.contains(
                        "T1_NO_CONTROLLABLE_DURING_GAP_CHARGER_1 (TRANSITION) -> stage 1"));
                assertTrue(log, log.contains(
                        "T1_NO_CONTROLLABLE_DURING_GAP_CHARGER_2 (TRANSITION) -> stage 2"));
                break;
            case T2:
                assertTrue(log, log.contains(
                        "T2_START_AFTER_STOP_AND_RECONFIGURE (TRANSITION) -> "
                                + "cross scope [1, 2] GOAL_UPDATE_EVENTS_ONLY"));
                break;
            default:
                throw new AssertionError("Unhandled transition variant: " + transitionVariant);
        }
    }

    private enum TransitionVariant {
        NO_TR,
        T1,
        T2
    }

    private static int transitionCount(MTS<Long, String> environment, String action) {
        int count = 0;
        for (Long state : environment.getStates()) {
            for (Pair<String, Long> transition :
                    environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                if (action.equals(transition.getFirst())) {
                    count++;
                }
            }
        }
        return count;
    }
}
