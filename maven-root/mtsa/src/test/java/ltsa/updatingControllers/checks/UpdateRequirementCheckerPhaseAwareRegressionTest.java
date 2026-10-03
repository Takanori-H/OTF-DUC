package ltsa.updatingControllers.checks;

import ltsa.lts.LTSCompiler;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Regression oracles for update checks that need the update phase and the
 * complete reachable old-controller behavior, rather than the raw mapping LTS.
 */
public class UpdateRequirementCheckerPhaseAwareRegressionTest {

    private static final String RESOURCE_ROOT =
            "src/test/resources/StepwiseDUCS/UpdateRequirementChecker/";

    @Test(timeout = 120000)
    public void rejectsControllerThatOmitsACrossStageRelationShadowTarget()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "PhaseAwareRelationPreAction.lts",
                "PhaseAwareRelationPreAction.aut",
                "PHASE_A");

        assertEquals(
                "The old z also advances the first mapping stage's relation shadow.  "
                        + "The printed controller omits that hotSwapIn target and its u path.",
                UpdateRequirementChecker.Status.FAIL,
                result(report, "R9").status);
        assertEquals(UpdateRequirementChecker.Status.FAIL, report.overallStatus());
    }

    @Test(timeout = 120000)
    public void acceptsControllerThatPreservesEveryCrossStageRelationShadowTarget()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "PhaseAwareRelationPreAction.lts",
                "PhaseAwareRelationPreActionComplete.aut",
                "PHASE_A");

        assertEquals(UpdateRequirementChecker.Status.PASS,
                result(report, "R1").status);
        assertEquals(UpdateRequirementChecker.Status.PASS,
                result(report, "R9").status);
        assertEquals(UpdateRequirementChecker.Status.PASS, report.overallStatus());
    }

    @Test(timeout = 120000)
    public void rejectsControllableActionDisabledInTheCurrentPostHotSwapEnvironmentState()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "CoverageAndLegality.lts",
                "EnvironmentDisabledControllable.aut",
                "PROBE_B");

        assertEquals(
                "A controller must not add a controllable transition that is disabled "
                        + "in the correlated post-hotSwap environment state.",
                UpdateRequirementChecker.Status.FAIL,
                result(report, "R9").status);
    }

    @Test(timeout = 120000)
    public void rejectsControllerThatOmitsAReachableOldControllerStateAndItsHotSwap()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "CoverageAndLegality.lts",
                "OmittedReachableOldState.aut",
                "PROBE_C");

        assertEquals(
                "R1 must quantify over every reachable old-controller state/run, "
                        + "including one omitted entirely from the printed controller.",
                UpdateRequirementChecker.Status.FAIL,
                result(report, "R1").status);
    }

    @Test(timeout = 120000)
    public void acceptsCorrelatedSameLabelNondeterministicTargets()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "NondeterministicTargetCorrelation.lts",
                "CorrelatedNondeterministicTargets.aut",
                "NONDET_PASS");

        assertEquals(
                "Each reconfigure successor has a compatible reference successor.  "
                        + "Unrelated left/right successors must not be paired Cartesianly.",
                UpdateRequirementChecker.Status.PASS,
                result(report, "R9").status);
    }

    @Test(timeout = 120000)
    public void rejectsOmittedSameLabelNondeterministicTarget()
            throws Exception {
        UpdateRequirementChecker.Report report = check(
                "NondeterministicTargetCorrelation.lts",
                "OmittedNondeterministicTarget.aut",
                "NONDET_FAIL");

        assertEquals(
                "The right-hand reference target of reconfigure is not represented "
                        + "by any compatible controller successor.",
                UpdateRequirementChecker.Status.FAIL,
                result(report, "R9").status);
    }

    private static UpdateRequirementChecker.Report check(
            String ltsName,
            String transitionsName,
            String target) throws Exception {
        File lts = resource(ltsName);
        File transitions = resource(transitionsName);
        synchronized (LTSCompiler.class) {
            return UpdateRequirementChecker.check(transitions, lts, target);
        }
    }

    private static File resource(String name) {
        File file = new File(RESOURCE_ROOT + name);
        if (!file.isFile()) {
            fail("Missing regression fixture: " + file.getAbsolutePath());
        }
        return file;
    }

    private static UpdateRequirementChecker.RequirementResult result(
            UpdateRequirementChecker.Report report,
            String id) {
        for (UpdateRequirementChecker.RequirementResult result : report.results) {
            if (id.equals(result.id)) {
                return result;
            }
        }
        fail("Requirement result not found: " + id);
        throw new AssertionError("unreachable");
    }
}
