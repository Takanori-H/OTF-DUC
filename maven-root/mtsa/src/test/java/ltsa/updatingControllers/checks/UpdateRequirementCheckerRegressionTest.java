package ltsa.updatingControllers.checks;

import ltsa.lts.LTSCompiler;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Focused semantic regressions for the phase and graph checks performed by
 * {@link UpdateRequirementChecker}.  The controller transition graphs are
 * deliberately supplied directly so that these tests do not depend on a
 * particular synthesis algorithm.
 */
public class UpdateRequirementCheckerRegressionTest {

    private static final String TARGET = "CHECKER_TARGET";

    private static final String SPEC = String.join("\n",
            "set ControllableActions = {work, spin, dead, boom, oldBad, newBad}",
            "",
            "OLD_ENV = OLD,",
            "OLD = ({work, spin, dead, boom, oldBad, newBad} -> OLD).",
            "",
            "NEW_ENV = NEW,",
            "NEW = ({work, spin, dead, boom, oldBad, newBad} -> NEW).",
            "",
            "relation R_ENV = {",
            "    OLD@OLD_ENV = reconfigure -> NEW@NEW_ENV",
            "}",
            "",
            "map MAP_ENV = {OLD_ENV, NEW_ENV, R_ENV}",
            "||MAPPING_ENV = (MAP_ENV).",
            "",
            "OLD_CLOSED_LOOP = ({work, spin, dead, boom, oldBad, newBad}",
            "    -> OLD_CLOSED_LOOP).",
            "||OldCon = (OLD_CLOSED_LOOP).",
            "",
            "ltl_property OLD_SAFE = [](!oldBad)",
            "ltl_property NEW_SAFE = [](!newBad)",
            "",
            "controllerSpec OLD_GOAL = {",
            "    safety = {OLD_SAFE}",
            "    controllable = {ControllableActions}",
            "}",
            "",
            "controllerSpec NEW_GOAL = {",
            "    safety = {NEW_SAFE}",
            "    controllable = {ControllableActions}",
            "}",
            "",
            "updatingController " + TARGET + " = {",
            "    oldController = OldCon,",
            "    oldEnvironment = {OLD_ENV},",
            "    newEnvironment = {NEW_ENV},",
            "    mapRelation = {R_ENV},",
            "    oldGoal = OLD_GOAL,",
            "    newGoal = NEW_GOAL,",
            "    nonblocking",
            "}",
            "");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private File specFile;
    private int graphSequence;

    @Before
    public void writeSpec() throws Exception {
        specFile = temporaryFolder.newFile("checker.lts");
        Files.write(specFile.toPath(), SPEC.getBytes(StandardCharsets.UTF_8));
    }

    @Test(timeout = 120000)
    public void oldSafetyRemainsActiveUntilStopAndTurnsOffAfterIt() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report beforeStop = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (oldBad -> Q2)",
                    "Q2 = (stopOldSpec -> Q3)",
                    "Q3 = (reconfigure -> Q4)",
                    "Q4 = (startNewSpec -> Q5)",
                    "Q5 = (work -> Q5)"));
            assertStatus(beforeStop, "R2", UpdateRequirementChecker.Status.FAIL);

            UpdateRequirementChecker.Report afterStop = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (stopOldSpec -> Q2)",
                    "Q2 = (oldBad -> Q3)",
                    "Q3 = (reconfigure -> Q4)",
                    "Q4 = (startNewSpec -> Q5)",
                    "Q5 = (work -> Q5)"));
            assertStatus(afterStop, "R2", UpdateRequirementChecker.Status.PASS);
        }
    }

    @Test(timeout = 120000)
    public void newSafetyTurnsOnPersistentlyAtStartAndIsOffBeforeIt() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report afterStart = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (newBad -> Q5)",
                    "Q5 = (work -> Q5)"));
            assertStatus(afterStart, "R4", UpdateRequirementChecker.Status.FAIL);

            UpdateRequirementChecker.Report beforeStart = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (newBad -> Q2)",
                    "Q2 = (stopOldSpec -> Q3)",
                    "Q3 = (reconfigure -> Q4)",
                    "Q4 = (startNewSpec -> Q5)",
                    "Q5 = (work -> Q5)"));
            assertStatus(beforeStart, "R4", UpdateRequirementChecker.Status.PASS);
        }
    }

    @Test(timeout = 120000)
    public void completeNonblockingGraphPassesAllProgressAndDeadlockChecks() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (work -> Q4)"));
            assertStatuses(report, UpdateRequirementChecker.Status.PASS,
                    "R5", "R6", "R7", "R8");
        }
    }

    @Test(timeout = 120000)
    public void aReachableAvoidingCycleFailsPendingProgressButNotDeadlockFreedom()
            throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (spin -> Q1 | stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (work -> Q4)"));
            assertStatuses(report, UpdateRequirementChecker.Status.FAIL, "R5", "R6", "R7");
            assertStatus(report, "R8", UpdateRequirementChecker.Status.PASS);
        }
    }

    @Test(timeout = 120000)
    public void anEventualityStopsTrackingAPathWhenItsTargetOccurs() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (stopOldSpec -> Q2)",
                    "Q2 = (spin -> Q2)"));
            assertStatus(report, "R5", UpdateRequirementChecker.Status.PASS);
            assertStatuses(report, UpdateRequirementChecker.Status.FAIL, "R6", "R7");
            assertStatus(report, "R8", UpdateRequirementChecker.Status.PASS);
        }
    }

    @Test(timeout = 120000)
    public void aReachableDeadEndFailsPendingProgressAndDeadlockFreedom() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (dead -> Q5 | stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (work -> Q4)",
                    "Q5 = STOP"));
            assertStatuses(report, UpdateRequirementChecker.Status.FAIL,
                    "R5", "R6", "R7", "R8");
        }
    }

    @Test(timeout = 120000)
    public void aReachableErrorBeforeProgressFailsClosed() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (boom -> ERROR | stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (work -> Q4)"));
            assertStatuses(report, UpdateRequirementChecker.Status.FAIL,
                    "R5", "R6", "R7", "R8");
            assertEquals(UpdateRequirementChecker.Status.FAIL, report.overallStatus());
        }
    }

    @Test(timeout = 120000)
    public void aReachableErrorAfterProgressFailsDeadlockFreedomOnly() throws Exception {
        synchronized (LTSCompiler.class) {
            UpdateRequirementChecker.Report report = check(graph(
                    "Q0 = (hotSwapIn -> Q1)",
                    "Q1 = (stopOldSpec -> Q2)",
                    "Q2 = (reconfigure -> Q3)",
                    "Q3 = (startNewSpec -> Q4)",
                    "Q4 = (boom -> ERROR | work -> Q4)"));
            assertStatuses(report, UpdateRequirementChecker.Status.PASS, "R5", "R6", "R7");
            assertStatus(report, "R8", UpdateRequirementChecker.Status.FAIL);
            assertEquals(UpdateRequirementChecker.Status.FAIL, report.overallStatus());
        }
    }

    private UpdateRequirementChecker.Report check(String transitions) throws Exception {
        File graphFile = temporaryFolder.newFile("graph_" + graphSequence++ + ".txt");
        Files.write(graphFile.toPath(), transitions.getBytes(StandardCharsets.UTF_8));
        return UpdateRequirementChecker.check(graphFile, specFile, TARGET);
    }

    private static String graph(String... states) {
        StringBuilder builder = new StringBuilder();
        builder.append("Process:\n\tCHECKER_GRAPH\nStates:\n\t")
                .append(states.length)
                .append("\nTransitions:\n\tCHECKER_GRAPH = Q0,\n");
        for (int i = 0; i < states.length; i++) {
            builder.append("\t").append(states[i]);
            builder.append(i + 1 == states.length ? ".\n" : ",\n");
        }
        return builder.toString();
    }

    private static void assertStatuses(
            UpdateRequirementChecker.Report report,
            UpdateRequirementChecker.Status expected,
            String... ids) {
        for (String id : ids) {
            assertStatus(report, id, expected);
        }
    }

    private static void assertStatus(
            UpdateRequirementChecker.Report report,
            String id,
            UpdateRequirementChecker.Status expected) {
        UpdateRequirementChecker.RequirementResult result = find(report, id);
        assertNotNull("Missing result " + id, result);
        assertEquals(id + ": " + result.detail + " witness=" + result.witness,
                expected,
                result.status);
    }

    private static UpdateRequirementChecker.RequirementResult find(
            UpdateRequirementChecker.Report report,
            String id) {
        for (UpdateRequirementChecker.RequirementResult result : report.results) {
            if (id.equals(result.id)) {
                return result;
            }
        }
        return null;
    }
}
