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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Ensures preconnection pruning cannot hide a Traditional H_T shadow target. */
public class StepwiseDelayedTraditionalShadowSafetyIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedTraditionalShadowSafety.lts";
    private static final Pattern CONNECTION_RESULT = Pattern.compile(
            "hotSwapIn connection result: connections=(\\d+), oldStatesWithoutTargets=(\\d+)");
    private static final Pattern CONNECTION_UNIQUE_STATES = Pattern.compile(
            "hotSwapIn connection unique states: old=(\\d+), mapping=(\\d+), "
                    + "eligibleMappingVisited=(\\d+)");
    private static final Pattern CONNECTION_SHADOW_PHASE_META = Pattern.compile(
            "\\[Stepwise Delayed DUCS\\] Connection-shadow stage (\\d+) phase meta "
                    + "states: (\\d+) transitions: (\\d+)");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void unsafeTraditionalShadowTargetMakesBothAlgorithmsLosing()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                characterizeTraditionalTargets();

                RunResult traditional = run("Traditional_ShadowSafety");
                assertNoController(traditional, "Traditional_ShadowSafety");

                RunResult stepwise = run("Stepwise_ShadowSafety");
                assertNoController(stepwise, "Stepwise_ShadowSafety");
                assertTrue("The connection-shadow preservation path was not selected.\n"
                                + stepwise.log,
                        stepwise.log.contains(
                                "Traditional connection-shadow preservation required"));

                Matcher phaseMeta = requireMatch(CONNECTION_SHADOW_PHASE_META, stepwise.log);
                assertTrue("The preservation phase meta must be non-empty.\n" + stepwise.log,
                        Long.parseLong(phaseMeta.group(2)) > 0L);
                assertOrdered(stepwise.log,
                        phaseMeta.group(0),
                        "Raw connection-shadow product before delayed connection",
                        "hotSwapIn connection result:");

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals("All three H_T targets must be connected.",
                        3L, Long.parseLong(connection.group(1)));
                assertEquals("The result must not be a coverage failure.",
                        0L, Long.parseLong(connection.group(2)));

                Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, stepwise.log);
                assertEquals(1L, Long.parseLong(unique.group(1)));
                assertEquals(3L, Long.parseLong(unique.group(2)));
                assertEquals(3L, Long.parseLong(unique.group(3)));

                assertTrue(stepwise.log,
                        stepwise.log.contains("After delayed hotSwapIn connection"));
                assertTrue(stepwise.log,
                        stepwise.log.contains(
                                "safety backward pruning scope: stepwise-delayed-final"));
                assertTrue(stepwise.log,
                        stepwise.log.contains(
                                "initial state is losing: there is no controller."));
                assertOrdered(stepwise.log,
                        "hotSwapIn connection result:",
                        "After delayed hotSwapIn connection",
                        "safety backward pruning scope: stepwise-delayed-final",
                        "initial state is losing: there is no controller.");
                assertTrue(stepwise.log,
                        !stepwise.log.contains(
                                "stepwise_delayed hotSwapIn connection failed for reachable OldCon states"));
                assertTrue(stepwise.log,
                        !stepwise.log.contains("Relation pre-action 'z' conflicts"));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    @Test(timeout = 180000)
    public void unsafeAllOldTargetCannotBeHiddenByPreconnectionSbp()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                characterizeTraditionalAllOldTargets();

                RunResult traditional = run("Traditional_HiddenAllOldShadow");
                assertNoController(traditional, "Traditional_HiddenAllOldShadow");

                RunResult stepwise = run("Stepwise_HiddenAllOldShadow");
                assertNoController(stepwise, "Stepwise_HiddenAllOldShadow");
                assertTrue("The one-to-many shadow preservation path was not selected.\n"
                                + stepwise.log,
                        stepwise.log.contains(
                                "Traditional connection-shadow preservation required"));

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals("Both all-OLD H_T targets must be connected.",
                        2L, Long.parseLong(connection.group(1)));
                assertEquals("The result must not be a coverage failure.",
                        0L, Long.parseLong(connection.group(2)));

                Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, stepwise.log);
                assertEquals(1L, Long.parseLong(unique.group(1)));
                assertEquals(2L, Long.parseLong(unique.group(2)));
                assertEquals(2L, Long.parseLong(unique.group(3)));
                assertOrdered(stepwise.log,
                        "hotSwapIn connection result:",
                        "After delayed hotSwapIn connection",
                        "safety backward pruning scope: stepwise-delayed-final",
                        "initial state is losing: there is no controller.");
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    @Test(timeout = 180000)
    public void unsafeInterleavedTauTargetCannotBeHiddenByPreconnectionSbp()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");

                characterizeTraditionalTauTargets();

                RunResult traditional = run("Traditional_TauShadowSafety");
                assertNoController(traditional, "Traditional_TauShadowSafety");

                RunResult stepwise = run("Stepwise_TauShadowSafety");
                assertNoController(stepwise, "Stepwise_TauShadowSafety");
                assertTrue("The one-to-many tau shadow preservation path was not selected.\n"
                                + stepwise.log,
                        stepwise.log.contains(
                                "Traditional connection-shadow preservation required"));

                Matcher connection = requireMatch(CONNECTION_RESULT, stepwise.log);
                assertEquals("Initial, visible-a, and interleaved-tau H_T targets must be connected.",
                        3L, Long.parseLong(connection.group(1)));
                assertEquals("The result must not be a coverage failure.",
                        0L, Long.parseLong(connection.group(2)));

                Matcher unique = requireMatch(CONNECTION_UNIQUE_STATES, stepwise.log);
                assertEquals(2L, Long.parseLong(unique.group(1)));
                assertEquals(3L, Long.parseLong(unique.group(2)));
                assertEquals(3L, Long.parseLong(unique.group(3)));
                assertOrdered(stepwise.log,
                        "hotSwapIn connection result:",
                        "After delayed hotSwapIn connection",
                        "safety backward pruning scope: stepwise-delayed-final",
                        "[Stepwise Delayed DUCS] GR result: losing");
                assertTrue(stepwise.log,
                        !stepwise.log.contains(
                                "stepwise_delayed hotSwapIn connection failed for reachable OldCon states"));
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
            }
        }
    }

    private void characterizeTraditionalTargets() throws Exception {
        File model = model();
        String source = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        LTSCompiler compiler = new LTSCompiler(
                new LTSInputString(source),
                new EmptyLTSOuput(),
                model.getAbsoluteFile().getParent());
        compiler.compile();
        CompositeState compiled = compiler.continueCompilation("Traditional_ShadowSafety");
        assertTrue("Expected an updating-controller compilation.",
                compiled instanceof UpdatingControllerCompositeState);

        UpdatingControllerCompositeState definition =
                (UpdatingControllerCompositeState) compiled;
        MTS<Long, String> oldController = definition.getOldController();
        MTS<Long, String> mappingShadow = definition.getMapping();
        assertEquals("The old controlled behaviour must have one reachable state.",
                1, oldController.getStates().size());

        Set<Long> zTargets = targets(
                mappingShadow, mappingShadow.getInitialState(), "z");
        assertEquals("The mapping shadow must retain both z intermediates.",
                2, zTargets.size());
        boolean sawSafe = false;
        boolean sawUnsafe = false;
        for (Long target : zTargets) {
            sawSafe |= !targets(mappingShadow, target, "s").isEmpty();
            sawUnsafe |= !targets(mappingShadow, target, "u").isEmpty();
        }
        assertTrue("The safe z intermediate is missing.", sawSafe);
        assertTrue("The unsafe z intermediate is missing.", sawUnsafe);

        UpdatingEnvironmentGenerator generator =
                new UpdatingEnvironmentGenerator(oldController, mappingShadow);
        generator.generateEnvironment();
        UpdatingEnvironment traditionalEnvironment = generator.getUpdEnv();
        Set<Long> hotSwapTargets = targets(
                traditionalEnvironment,
                oldController.getInitialState(),
                UpdateConstants.BEGIN_UPDATE);
        assertEquals("Traditional H_T must contain initial, safe, and unsafe targets.",
                3, hotSwapTargets.size());
    }

    private void characterizeTraditionalAllOldTargets() throws Exception {
        File model = model();
        String source = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        LTSCompiler compiler = new LTSCompiler(
                new LTSInputString(source),
                new EmptyLTSOuput(),
                model.getAbsoluteFile().getParent());
        compiler.compile();
        CompositeState compiled = compiler.continueCompilation(
                "Traditional_HiddenAllOldShadow");
        assertTrue("Expected an updating-controller compilation.",
                compiled instanceof UpdatingControllerCompositeState);

        UpdatingControllerCompositeState definition =
                (UpdatingControllerCompositeState) compiled;
        MTS<Long, String> oldController = definition.getOldController();
        MTS<Long, String> mappingShadow = definition.getMapping();
        assertEquals(1, oldController.getStates().size());

        UpdatingEnvironmentGenerator generator =
                new UpdatingEnvironmentGenerator(oldController, mappingShadow);
        generator.generateEnvironment();
        Set<Long> hotSwapTargets = targets(
                generator.getUpdEnv(),
                oldController.getInitialState(),
                UpdateConstants.BEGIN_UPDATE);
        assertEquals("Traditional H_T must contain both all-OLD targets.",
                2, hotSwapTargets.size());
    }

    private void characterizeTraditionalTauTargets() throws Exception {
        File model = model();
        String source = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        LTSCompiler compiler = new LTSCompiler(
                new LTSInputString(source),
                new EmptyLTSOuput(),
                model.getAbsoluteFile().getParent());
        compiler.compile();
        CompositeState compiled = compiler.continueCompilation(
                "Traditional_TauShadowSafety");
        assertTrue("Expected an updating-controller compilation.",
                compiled instanceof UpdatingControllerCompositeState);

        UpdatingControllerCompositeState definition =
                (UpdatingControllerCompositeState) compiled;
        MTS<Long, String> oldController = definition.getOldController();
        MTS<Long, String> mappingShadow = definition.getMapping();

        Set<Long> oldATargets = targets(
                oldController, oldController.getInitialState(), "a");
        Set<Long> oldTauTargets = targets(
                oldController, oldController.getInitialState(), "tau");
        assertEquals(1, oldATargets.size());
        assertEquals(1, oldTauTargets.size());
        Long sharedOldTarget = oldATargets.iterator().next();
        assertEquals("Visible a and tau must reach the same OldCon state.",
                sharedOldTarget, oldTauTargets.iterator().next());

        Set<Long> mappingATargets = targets(
                mappingShadow, mappingShadow.getInitialState(), "a");
        Set<Long> mappingTauTargets = targets(
                mappingShadow, mappingShadow.getInitialState(), "tau");
        assertEquals("The visible branch must have one mapping target.",
                1, mappingATargets.size());
        assertEquals("Tau must interleave in exactly one mapping component.",
                1, mappingTauTargets.size());
        Long safeTarget = mappingATargets.iterator().next();
        Long unsafeTarget = mappingTauTargets.iterator().next();
        assertNotEquals("Visible a and tau must reach distinct mapping tuples.",
                safeTarget, unsafeTarget);
        assertTrue("The visible-a target must not enable the unsafe relation action.",
                targets(mappingShadow, safeTarget, "tauFail").isEmpty());
        assertEquals("The interleaved-tau target must enable the unsafe relation action.",
                1, targets(mappingShadow, unsafeTarget, "tauFail").size());

        UpdatingEnvironmentGenerator generator =
                new UpdatingEnvironmentGenerator(oldController, mappingShadow);
        generator.generateEnvironment();
        UpdatingEnvironment traditionalEnvironment = generator.getUpdEnv();
        Set<Long> initialHotSwapTargets = targets(
                traditionalEnvironment,
                oldController.getInitialState(),
                UpdateConstants.BEGIN_UPDATE);
        Set<Long> sharedStateHotSwapTargets = targets(
                traditionalEnvironment,
                sharedOldTarget,
                UpdateConstants.BEGIN_UPDATE);
        assertEquals("Traditional H_T must retain the initial mapping target.",
                1, initialHotSwapTargets.size());
        assertEquals("Traditional H_T must retain both visible and tau targets.",
                2, sharedStateHotSwapTargets.size());
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
        assertTrue("Shadow-safety model is missing: " + model.getAbsolutePath(),
                model.isFile());
        return model;
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

    private static void assertNoController(RunResult result, String target) {
        assertNotEquals("Unexpected controller for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertEquals("A losing target must not write controller transitions: " + target,
                0L,
                result.transitions.length());
        assertTrue("The failure must be a synthesis losing result.\n" + result.log,
                result.log.contains("initial state is losing: there is no controller.")
                        || result.log.contains("There is no controller for model"));
    }

    private static Matcher requireMatch(Pattern pattern, String log) {
        Matcher matcher = pattern.matcher(log);
        assertTrue("Expected log pattern was not found: " + pattern.pattern() + "\n" + log,
                matcher.find());
        return matcher;
    }

    private static void assertOrdered(String log, String... markers) {
        int previous = -1;
        for (String marker : markers) {
            int current = log.indexOf(marker, previous + 1);
            assertTrue("Expected ordered log marker was not found: " + marker + "\n" + log,
                    current > previous);
            previous = current;
        }
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
