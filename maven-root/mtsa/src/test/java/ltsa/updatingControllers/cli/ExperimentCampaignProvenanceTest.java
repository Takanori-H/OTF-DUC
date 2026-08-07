package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;

public class ExperimentCampaignProvenanceTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void sanitizedSnapshotNeverContainsSlackWebhookSecret() {
        String secret = "https://hooks.slack.com/services/T/A/secret-token";
        String sanitized = ExperimentCampaignProvenance.sanitizeConfig(
                "outputDir: result\n"
                        + "slackWebhookUrl: \"" + secret + "\"\n"
                        + "notifyOn: always\n");

        assertFalse(sanitized.contains(secret));
        assertFalse(sanitized.contains("secret-token"));
        assertTrue(sanitized.contains("<redacted>"));
    }

    @Test
    public void alternatesOnlyMethodOrderWithinVariantAndExamplePair() throws Exception {
        ExperimentConfig config = loadConfig(
                "alternateMethodOrderByRun: true\n"
                        + pairedCases());

        assertOrder(
                ExperimentCampaignProvenance.orderedCasesForRun(config, 1),
                "step_no", "trad_no", "step_t1", "trad_t1", "step_t2", "trad_t2");
        assertOrder(
                ExperimentCampaignProvenance.orderedCasesForRun(config, 2),
                "trad_no", "step_no", "trad_t1", "step_t1", "trad_t2", "step_t2");
    }

    @Test
    public void alternationFailsFastForIncompletePair() throws Exception {
        ExperimentConfig config = loadConfig(
                "alternateMethodOrderByRun: true\n"
                        + "cases:\n"
                        + "  - id: only_step\n"
                        + "    example: Drone\n"
                        + "    method: stepwise_delayed+SBP\n"
                        + "    variant: no_tr\n"
                        + "    lts: input.lts\n"
                        + "    target: Stepwise_UpdCont\n");
        try {
            ExperimentCampaignProvenance.orderedCasesForRun(config, 1);
            fail("Expected incomplete method pair to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exactly one Stepwise"));
        }
    }

    @Test
    public void freshPreflightChecksAllPlannedRunDirectoriesBeforeExecution()
            throws Exception {
        ExperimentConfig config = loadConfig(
                "requireFreshOutputDir: true\n"
                        + pairedCases());
        File emptyCampaignOutput = temporaryFolder.newFolder("campaign-output");
        config.outputDir = emptyCampaignOutput;
        File runOne = new File(temporaryFolder.getRoot(), "run_01/result");
        File runThree = new File(temporaryFolder.getRoot(), "run_03/result");
        assertTrue(runThree.mkdirs());
        Files.write(
                new File(runThree, "stale.csv").toPath(),
                "stale".getBytes(StandardCharsets.UTF_8));

        try {
            ExperimentCampaignProvenance.requireFreshOutputs(
                    config,
                    Arrays.asList(runOne, runThree),
                    Arrays.asList(new File(runOne, "case.csv")));
            fail("Expected run_03 collision to be rejected before execution");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("not empty"));
        }
    }

    @Test
    public void nonStrictMethodValidationStillRecordsActualType() {
        ltsa.lts.CompositeState ordinary = new ltsa.lts.CompositeState();
        ordinary.name = "CHECK";
        SingleCompositionRunner.MethodValidation validation =
                SingleCompositionRunner.MethodValidation.inspect(
                        ordinary,
                        "CHECK",
                        null);
        assertTrue(validation.matches);
        assertEquals("non_updating_controller", validation.actualMethod);
    }

    @Test
    public void targetValidationRejectsUnexpectedCompiledTargetEvenWithoutMethodCheck() {
        ltsa.lts.CompositeState ordinary = new ltsa.lts.CompositeState();
        ordinary.name = "UNEXPECTED";
        SingleCompositionRunner.MethodValidation validation =
                SingleCompositionRunner.MethodValidation.inspect(
                        ordinary,
                        "CHECK",
                        null);
        assertFalse(validation.targetMatches);
        assertFalse(validation.matches);
    }

    @Test
    public void parsesSeparateRssAndHeapGapLimits() throws Exception {
        ExperimentConfig config = loadConfig(
                "maxRssSamplingGapMillis: 1000\n"
                        + "maxHeapSamplingGapMillis: 2000\n"
                        + singleCase());

        assertEquals(Long.valueOf(1000L), config.maxRssSamplingGapMillis);
        assertEquals(Long.valueOf(2000L), config.maxHeapSamplingGapMillis);
        assertNull(config.maxMemorySamplingGapMillis);
        assertEquals(Long.valueOf(1000L),
                config.effectiveMaxRssSamplingGapMillis());
        assertEquals(Long.valueOf(2000L),
                config.effectiveMaxHeapSamplingGapMillis());
    }

    @Test
    public void preservesLegacyJointGapLimitMeaning() throws Exception {
        ExperimentConfig config = loadConfig(
                "maxMemorySamplingGapMillis: 1500\n" + singleCase());

        assertEquals(Long.valueOf(1500L),
                config.effectiveMaxRssSamplingGapMillis());
        assertEquals(Long.valueOf(1500L),
                config.effectiveMaxHeapSamplingGapMillis());
    }

    @Test
    public void rejectsMixingLegacyAndSeparateGapLimits() throws Exception {
        try {
            loadConfig(
                    "maxMemorySamplingGapMillis: 1000\n"
                            + "maxRssSamplingGapMillis: 1000\n"
                            + singleCase());
            fail("Expected mixed legacy and separate gap limits to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cannot be combined"));
        }
    }

    private ExperimentConfig loadConfig(String body) throws Exception {
        File configFile = temporaryFolder.newFile("config-" + System.nanoTime() + ".yaml");
        String yaml = "outputDir: result\n" + body;
        Files.write(configFile.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return ExperimentConfig.load(configFile);
    }

    private static String pairedCases() {
        return "cases:\n"
                + caseYaml("step_no", "stepwise_delayed+SBP", "no_tr", "Stepwise_UpdCont")
                + caseYaml("step_t1", "stepwise_delayed+SBP", "T1", "Stepwise_UpdCont_T1")
                + caseYaml("step_t2", "stepwise_delayed+SBP", "T2", "Stepwise_UpdCont_T2")
                + caseYaml("trad_no", "Traditional", "no_tr", "UpdCont")
                + caseYaml("trad_t1", "Traditional", "T1", "UpdCont_T1")
                + caseYaml("trad_t2", "Traditional", "T2", "UpdCont_T2");
    }

    private static String singleCase() {
        return "cases:\n"
                + caseYaml("trad_no", "Traditional", "no_tr", "UpdCont");
    }

    private static String caseYaml(String id, String method, String variant, String target) {
        return "  - id: " + id + "\n"
                + "    example: Drone\n"
                + "    method: " + method + "\n"
                + "    variant: " + variant + "\n"
                + "    lts: input.lts\n"
                + "    target: " + target + "\n";
    }

    private static void assertOrder(List<ExperimentCase> cases, String... expectedIds) {
        assertEquals(expectedIds.length, cases.size());
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], cases.get(i).id);
        }
    }
}
