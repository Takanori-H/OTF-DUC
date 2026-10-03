package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ltsa.lts.CompositeState;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.CompositionEvaluationRunner;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Full-run regression for the evaluation-v6 primary timing boundary. */
public class CompositionEvaluationRunnerPrimaryTimingIntegrationTest {

    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedHotSwapProofIntegration.lts";
    private static final String EVALUATION_ENABLED_PROPERTY =
            "mtsa.evaluation.enabled";
    private static final String EVALUATION_CSV_PROPERTY =
            "mtsa.evaluation.csvFile";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 180000)
    public void traditionalAndFullStepwiseExportCountAdjustedPrimaryTime()
            throws Exception {
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            String previousCsv = System.getProperty(EVALUATION_CSV_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "true");
                assertPrimaryTiming("Traditional_TransitionWinning");
                assertPrimaryTiming("FullStepwise_TransitionWinning");
                assertPostGenerationCountIsOutsidePrimaryWindow();
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEnabled);
                restoreProperty(EVALUATION_CSV_PROPERTY, previousCsv);
            }
        }
    }

    private void assertPrimaryTiming(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Timing model is missing: " + model.getAbsolutePath(), model.isFile());
        File output = temporaryFolder.newFile(target + "_output.txt");
        File transitions = temporaryFolder.newFile(target + "_transitions.txt");
        File csv = temporaryFolder.newFile(target + "_evaluation.csv");
        System.setProperty(EVALUATION_CSV_PROPERTY, csv.getAbsolutePath());

        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", output.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });
        assertEquals("Composition failed for " + target + "\n"
                        + new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8),
                SingleCompositionRunner.EXIT_SUCCESS,
                exitCode);

        Map<String, String> metrics = metricValues(csv);
        long parse = metricLong(metrics, "parse_time");
        long compile = metricLong(metrics, "compile_if_change_total_time");
        long preparation = metricLong(metrics, "synthesis_problem_preparation_time");
        long generation = metricLong(metrics, "update_controller_generation_time");
        long related = metricLong(metrics, "controller_synthesis_related_time");
        long raw = metricLong(metrics, "total_time");
        long overhead = metricLong(
                metrics,
                "total_time_scope_count_overhead_time");
        long adjusted = metricLong(
                metrics,
                "total_time_time_without_scope_count_overhead");
        long primary = metricLong(
                metrics,
                "comparison_primary_controller_synthesis_time");

        assertEquals(preparation + generation, related);
        assertTrue("Primary raw time excludes part of compilation for " + target,
                raw >= compile + generation);
        assertTrue("Primary raw time excludes parsing for " + target,
                raw >= parse + preparation + generation);
        assertEquals(Math.max(0L, raw - overhead), adjusted);
        assertEquals(adjusted, primary);
    }

    private void assertPostGenerationCountIsOutsidePrimaryWindow() throws Exception {
        final long inWindowCountMillis = 13L;
        final long postGenerationCountMillis = 17L;
        final long artificialCallerLeadMillis = 10000L;
        final long callbackDelayMillis = 120L;
        final long[] callbackEntryElapsedMillis = new long[] {-1L};
        final String target = "Traditional_TransitionWinning";
        File model = new File(MODEL_PATH);
        File outputFile = temporaryFolder.newFile("callback_boundary_output.txt");
        File csv = temporaryFolder.newFile("callback_boundary_evaluation.csv");
        System.setProperty(EVALUATION_CSV_PROPERTY, csv.getAbsolutePath());

        String source = new String(Files.readAllBytes(model.toPath()), StandardCharsets.UTF_8);
        File parent = model.getAbsoluteFile().getParentFile();
        String currentDirectory = parent == null
                ? new File(".").getAbsolutePath()
                : parent.getAbsolutePath();
        final CompositionEvaluationRunner.CompilationStep delegate =
                CompositionEvaluationRunner.ltsCompilerStep(
                        new LTSInputString(source), target, currentDirectory);
        CliFileLTSOutput output = new CliFileLTSOutput(outputFile);
        CompositionEvaluationRunner.Result result;
        long operationStartNanos = System.nanoTime()
                - TimeUnit.MILLISECONDS.toNanos(artificialCallerLeadMillis);
        try {
            CompositionEvaluationRunner.Request request =
                    new CompositionEvaluationRunner.Request(
                            output,
                            new CompositionEvaluationRunner.CompilationStep() {
                                @Override
                                public CompositeState compile(LTSOutput compilerOutput)
                                        throws Exception {
                                    UpdatingControllerEvaluationRecorder.recordStateSpace(
                                            "test",
                                            "synthetic in-window count",
                                            1L,
                                            1L,
                                            inWindowCountMillis);
                                    return delegate.compile(compilerOutput);
                                }
                            })
                            .withCompositionStartNanos(operationStartNanos)
                            .withSuccessfulCompositionCallback(
                                    new CompositionEvaluationRunner.SuccessfulCompositionCallback() {
                                        @Override
                                        public void afterSuccessfulComposition(CompositeState current)
                                                throws Exception {
                                            callbackEntryElapsedMillis[0] = TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - operationStartNanos);
                                            UpdatingControllerEvaluationRecorder.recordStateSpace(
                                                    "test",
                                                    "synthetic post-generation count",
                                                    1L,
                                                    1L,
                                                    postGenerationCountMillis);
                                            Thread.sleep(callbackDelayMillis);
                                        }
                                    });
            result = CompositionEvaluationRunner.run(request);
        } finally {
            output.close();
        }
        assertTrue(result.isSuccessful());

        Map<String, String> metrics = metricValues(csv);
        long raw = metricLong(metrics, "total_time");
        long scoped = metricLong(metrics, "total_time_scope_count_overhead_time");
        long observed = metricLong(metrics, "comparison_count_overhead_time");
        long post = metricLong(metrics, "comparison_count_overhead_after_observed_time");
        long primary = metricLong(metrics, "comparison_primary_controller_synthesis_time");
        assertTrue("Caller-supplied composition start was ignored",
                raw >= artificialCallerLeadMillis);
        assertTrue("Callback entry time was not recorded",
                callbackEntryElapsedMillis[0] >= 0L);
        assertTrue("Post-generation callback wall time leaked into total_time",
                raw <= callbackEntryElapsedMillis[0]);
        assertTrue(scoped >= inWindowCountMillis);
        assertEquals("Every observed CountTime must be inside the primary window",
                observed, scoped);
        assertTrue("Callback CountTime was not classified as post-generation",
                post >= postGenerationCountMillis);
        assertEquals(Math.max(0L, raw - scoped), primary);
    }

    private static Map<String, String> metricValues(File csv) throws Exception {
        String text = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        List<List<String>> records = BatchExperimentRunner.parseCsvRecords(text);
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (records.isEmpty()) {
            return values;
        }
        List<String> header = records.get(0);
        int keyIndex = header.indexOf("metric_key");
        int valueIndex = header.indexOf("value");
        for (int index = 1; index < records.size(); index++) {
            List<String> record = records.get(index);
            if (record.size() > Math.max(keyIndex, valueIndex)) {
                values.put(record.get(keyIndex), record.get(valueIndex));
            }
        }
        return values;
    }

    private static long metricLong(Map<String, String> metrics, String key) {
        String value = metrics.get(key);
        assertTrue("Missing timing metric: " + key, value != null && !value.isEmpty());
        return Long.parseLong(value);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
