package ltsa.updatingControllers.cli;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.UpdatingEnvironment;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
import ltsa.updatingControllers.synthesis.UpdatingEnvironmentGenerator;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Characterizes the historical Traditional H_T mapping-shadow relation. */
public class TraditionalRelationPreActionShadowConnectionIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/TraditionalRelationPreActionShadowConnection.lts";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void relationPreActionShadowConnectionIsPreservedByStepwise()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                characterizeTraditionalConnection();

                RunResult traditional = run("Traditional_ShadowIntermediateConnection");
                assertEquals("Traditional H_T must accept the characterized connection.\n"
                                + traditional.log,
                        SingleCompositionRunner.EXIT_SUCCESS,
                        traditional.exitCode);
                assertTrue("Traditional did not write a controller.",
                        traditional.transitions.length() > 0L);

                RunResult stepwise = run("Stepwise_ShadowIntermediateConnection");
                assertEquals("Stepwise must preserve the Traditional INTERMEDIATE "
                                + "shadow connection.\n" + stepwise.log,
                        SingleCompositionRunner.EXIT_SUCCESS,
                        stepwise.exitCode);
                assertTrue("Stepwise did not write a controller.",
                        stepwise.transitions.length() > 0L);
                assertTrue("The z-successor OldCon state still lacks a delayed connection.\n"
                                + stepwise.log,
                        stepwise.log.contains("oldStatesWithoutTargets=0"));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    private void characterizeTraditionalConnection() throws Exception {
        File model = model();
        String source = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        LTSCompiler compiler = new LTSCompiler(
                new LTSInputString(source),
                new EmptyLTSOuput(),
                model.getAbsoluteFile().getParent());
        compiler.compile();
        CompositeState compiled = compiler.continueCompilation(
                "Traditional_ShadowIntermediateConnection");
        assertTrue("Expected an updating-controller compilation.",
                compiled instanceof UpdatingControllerCompositeState);

        UpdatingControllerCompositeState definition =
                (UpdatingControllerCompositeState) compiled;
        MTS<Long, String> oldController = definition.getOldController();
        MTS<Long, String> mappingShadow = definition.getMapping();

        // In the full mapping shadow, B's ordinary z synchronizes with A's
        // relation pre-action.  The resulting state is structurally the first
        // relation INTERMEDIATE state because its next A action is y.
        Long shadowAfterZ = onlyTarget(
                mappingShadow, mappingShadow.getInitialState(), "z");
        assertTrue("The full mapping shadow did not enter the relation intermediate on z.",
                !targets(mappingShadow, shadowAfterZ, "y").isEmpty());

        Long oldAfterZ = onlyTarget(
                oldController, oldController.getInitialState(), "z");
        UpdatingEnvironmentGenerator generator =
                new UpdatingEnvironmentGenerator(oldController, mappingShadow);
        generator.generateEnvironment();
        UpdatingEnvironment traditionalEnvironment = generator.getUpdEnv();

        Set<Long> hotSwapTargets = targets(
                traditionalEnvironment, oldAfterZ, UpdateConstants.BEGIN_UPDATE);
        assertTrue("Traditional H_T did not connect the z-successor OldCon state.",
                !hotSwapTargets.isEmpty());
        boolean reachesIntermediate = false;
        for (Long target : hotSwapTargets) {
            if (!targets(traditionalEnvironment, target, "y").isEmpty()) {
                reachesIntermediate = true;
                break;
            }
        }
        assertTrue("Traditional H_T must connect directly to the mapping-shadow "
                        + "INTERMEDIATE state reached by z.",
                reachesIntermediate);
    }

    private RunResult run(String target) throws Exception {
        File model = model();
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

    private static File model() {
        File model = new File(MODEL_PATH);
        assertTrue("Characterization model is missing: " + model.getAbsolutePath(),
                model.isFile());
        return model;
    }

    private static Long onlyTarget(MTS<Long, String> mts, Long source, String action) {
        Set<Long> result = targets(mts, source, action);
        assertEquals("Expected one " + action + " target from state " + source,
                1, result.size());
        return result.iterator().next();
    }

    private static Set<Long> targets(
            MTS<Long, String> mts,
            Long source,
            String action) {
        Set<Long> result = new LinkedHashSet<Long>();
        for (Pair<String, Long> transition : mts.getTransitions(
                source, MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst())) {
                result.add(transition.getSecond());
            }
        }
        return result;
    }

    private static Set<Long> targets(
            UpdatingEnvironment environment,
            Long source,
            String action) {
        Set<Long> result = new LinkedHashSet<Long>();
        for (Pair<String, Long> transition : environment.getTransitionsFrom(source)) {
            if (action.equals(transition.getFirst())) {
                result.add(transition.getSecond());
            }
        }
        return result;
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
