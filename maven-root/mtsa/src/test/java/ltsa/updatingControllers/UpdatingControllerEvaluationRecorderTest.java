package ltsa.updatingControllers;

import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ExternalDataMetric;
import ltsa.updatingControllers.memory.RunHeapMemorySampler;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatingControllerEvaluationRecorderTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String CSV_FILE_PROPERTY = "mtsa.evaluation.csvFile";
    private static final String STEPWISE_STATE_SPACE_SECTION =
            "Stepwise Delayed DUC 最大状態数と遷移数";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String previousEvaluationEnabled;
    private String previousCsvFile;
    private String previousParentFinalizationRequired;
    private String previousPhaseMemoryDiagnostics;

    @Before
    public void enableEvaluationWithTemporaryCsv() throws Exception {
        previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
        previousCsvFile = System.getProperty(CSV_FILE_PROPERTY);
        previousParentFinalizationRequired = System.getProperty(
                UpdatingControllerEvaluationRecorder
                        .PARENT_FINALIZATION_REQUIRED_PROPERTY);
        previousPhaseMemoryDiagnostics = System.getProperty(
                UpdatingControllerEvaluationRecorder
                        .PHASE_MEMORY_DIAGNOSTICS_PROPERTY);
        System.setProperty(EVALUATION_ENABLED_PROPERTY, "true");
        System.clearProperty(
                UpdatingControllerEvaluationRecorder
                        .PHASE_MEMORY_DIAGNOSTICS_PROPERTY);
        System.setProperty(
                CSV_FILE_PROPERTY,
                temporaryFolder.newFile("evaluation.csv").getAbsolutePath());
        UpdatingControllerEvaluationRecorder.reset();
    }

    @After
    public void restoreRecorderAndProperties() {
        restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
        restoreProperty(CSV_FILE_PROPERTY, previousCsvFile);
        restoreProperty(
                UpdatingControllerEvaluationRecorder
                        .PARENT_FINALIZATION_REQUIRED_PROPERTY,
                previousParentFinalizationRequired);
        restoreProperty(
                UpdatingControllerEvaluationRecorder
                        .PHASE_MEMORY_DIAGNOSTICS_PROPERTY,
                previousPhaseMemoryDiagnostics);
        UpdatingControllerEvaluationRecorder.reset();
    }

    @Test
    public void repeatedStepwiseStateSpaceLabelPreservesPeakAndItsPairedValue()
            throws Exception {
        UpdatingControllerEvaluationRecorder.setMode("Stepwise Delayed DUC");
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "repeated stage",
                100,
                200,
                0);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "repeated stage",
                50,
                500,
                0);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "[Stepwise Delayed DUCS] After global DontDoTwice",
                7,
                9,
                3);
        UpdatingControllerEvaluationRecorder.markSuccess();

        RecordingOutput output = new RecordingOutput();
        UpdatingControllerEvaluationRecorder.printSummary(output);

        String summary = output.joined();
        assertTrue(summary, summary.contains("最大状態数時の状態数 : 100 states"));
        assertTrue(summary, summary.contains("最大状態数時の遷移数 : 200 transitions"));
        assertTrue(summary, summary.contains("最大遷移数時の状態数 : 50 states"));
        assertTrue(summary, summary.contains("最大遷移数時の遷移数 : 500 transitions"));

        Map<String, String> metrics = readMetricValues(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals("100", metrics.get("peak_state_space_states"));
        assertEquals("200", metrics.get("peak_state_space_states_stage_transitions"));
        assertEquals("500", metrics.get("peak_state_space_transitions"));
        assertEquals("50", metrics.get("peak_state_space_transitions_stage_states"));
        assertEquals("repeated stage", metrics.get("peak_state_space_states_stage"));
        assertEquals("repeated stage", metrics.get("peak_state_space_transitions_stage"));
        assertEquals("7", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_states"));
        assertEquals("9", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_transitions"));
        assertEquals("3", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_count_time"));

        List<List<String>> rows = readCsvRows(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals(
                Arrays.asList("100", "50"),
                observationValues(rows, "repeated stage / States", "states"));
        assertEquals(
                Arrays.asList("200", "500"),
                observationValues(rows, "repeated stage / Transitions", "transitions"));
        assertEquals(
                Arrays.asList("50"),
                stableBaseValues(rows, "repeated stage / States", "states"));
        assertEquals(
                Arrays.asList("500"),
                stableBaseValues(rows, "repeated stage / Transitions", "transitions"));
    }

    @Test
    public void sampledHeapIsPrimaryAndLegacyPoolSumKeepsExplicitAlias()
            throws Exception {
        RunHeapMemorySampler sampler = new RunHeapMemorySampler(60_000L);
        sampler.start();
        RunHeapMemorySampler.Result sampled = sampler.stop();

        UpdatingControllerEvaluationRecorder.setMode("Traditional DUC");
        UpdatingControllerEvaluationRecorder.recordSampledHeapMemory(sampled);
        UpdatingControllerEvaluationRecorder.recordMemory(
                "共通 / HPWindow", "コントローラ合成のベースラインメモリ", 100L);
        UpdatingControllerEvaluationRecorder.recordMemory(
                "共通 / HPWindow", "コントローラ合成全体のピークメモリ", 500L);
        UpdatingControllerEvaluationRecorder.recordMemory(
                "共通 / HPWindow", "コントローラ合成により増えたメモリ", 400L);
        UpdatingControllerEvaluationRecorder.recordLegacyPoolPeakMemoryAliases(
                100L, 500L, 400L);
        UpdatingControllerEvaluationRecorder.markSuccess();
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        Map<String, String> metrics = readMetricValues(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals(Long.toString(sampled.getPeakHeapUsedBytes()),
                metrics.get("controller_synthesis_sampled_peak_heap_used"));
        assertEquals("500", metrics.get("controller_synthesis_peak_memory"));
        assertEquals("500",
                metrics.get("controller_synthesis_peak_memory_legacy_pool_sum"));
        assertEquals("true", metrics.get("heap_memory_sampling_available"));
    }

    @Test
    public void phaseMemoryDiagnosticsAreDisabledByDefault() throws Exception {
        assertFalse(UpdatingControllerEvaluationRecorder
                .isPhaseMemoryDiagnosticsEnabled());

        UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                "test.phase.disabled");
        UpdatingControllerEvaluationRecorder.markSuccess();
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        for (List<String> fields : readCsvRows(
                new File(System.getProperty(CSV_FILE_PROPERTY)))) {
            assertFalse(fields.size() > 3
                    && "診断 / phase memory checkpoints".equals(fields.get(3)));
        }
    }

    @Test
    public void enabledPhaseMemoryDiagnosticsRecordHeapEpochElapsedAndOrder()
            throws Exception {
        System.setProperty(
                UpdatingControllerEvaluationRecorder
                        .PHASE_MEMORY_DIAGNOSTICS_PROPERTY,
                "true");
        UpdatingControllerEvaluationRecorder.reset();
        assertTrue(UpdatingControllerEvaluationRecorder
                .isPhaseMemoryDiagnosticsEnabled());

        UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                "test.phase.first");
        UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                "test.phase.second");
        UpdatingControllerEvaluationRecorder.markSuccess();
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        Map<String, String> metrics = readMetricValues(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals("1", metrics.get(
                "diagnostic_phase_memory_test_phase_first_sequence"));
        assertEquals("2", metrics.get(
                "diagnostic_phase_memory_test_phase_second_sequence"));

        long firstEpoch = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_first_epoch_ms"));
        long secondEpoch = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_second_epoch_ms"));
        long firstElapsed = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_first_elapsed_ms"));
        long secondElapsed = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_second_elapsed_ms"));
        long firstHeap = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_first_current_heap_used"));
        long secondHeap = Long.parseLong(metrics.get(
                "diagnostic_phase_memory_test_phase_second_current_heap_used"));

        assertTrue(firstEpoch > 0L);
        assertTrue(secondEpoch >= firstEpoch);
        assertTrue(firstElapsed >= 0L);
        assertTrue(secondElapsed >= firstElapsed);
        assertTrue(firstHeap >= 0L);
        assertTrue(secondHeap >= 0L);
    }

    @Test
    public void externalMetricsUseNormalFormatterAndDistinctDescriptionIds()
            throws Exception {
        File csv = temporaryFolder.newFile("external.csv");
        List<ExternalDataMetric> metrics = Arrays.asList(
                new ExternalDataMetric(
                        "controller_synthesis_sampled_peak_process_rss",
                        "Parent process memory measurement",
                        "合成区間のsampled peak process RSS",
                        "1234",
                        "B"),
                new ExternalDataMetric(
                        "child_lifetime_sampled_peak_process_rss",
                        "Parent process memory measurement",
                        "子JVM lifetimeのsampled peak RSS",
                        "2345",
                        "B"),
                new ExternalDataMetric(
                        "process_rss_sampling_provider_thread_cpu_time_ns",
                        "Parent process memory measurement",
                        "RSS provider thread CPU時間",
                        "3456",
                        "ns"));

        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv, "Stepwise Delayed DUC", "SUCCESS", "", metrics);

        List<List<String>> rows = readCsvRows(csv);
        assertEquals(4, rows.size());
        assertEquals("controller_synthesis_sampled_peak_process_rss", rows.get(1).get(4));
        assertEquals("child_lifetime_sampled_peak_process_rss", rows.get(2).get(4));
        assertFalse(rows.get(1).get(10).isEmpty());
        assertFalse(rows.get(2).get(10).isEmpty());
        assertFalse(rows.get(1).get(10).equals(rows.get(2).get(10)));
        assertEquals("メモリ", rows.get(1).get(13));
        assertEquals("メモリ", rows.get(2).get(13));
        assertEquals("時間", rows.get(3).get(13));
    }

    @Test
    public void externalMetricsAtomicallyPreserveExistingRowsAcrossAppends()
            throws Exception {
        File csv = temporaryFolder.newFile("external-append.csv");
        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv,
                "Traditional DUC",
                "SUCCESS",
                "",
                Arrays.asList(new ExternalDataMetric(
                        "first_parent_metric",
                        "Parent process memory measurement",
                        "first",
                        "1",
                        "B")));
        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv,
                "Traditional DUC",
                "SUCCESS",
                "",
                Arrays.asList(new ExternalDataMetric(
                        "second_parent_metric",
                        "Parent process memory measurement",
                        "second",
                        "2",
                        "B")));

        List<List<String>> rows = readCsvRows(csv);
        assertEquals(3, rows.size());
        assertEquals("metric_key", rows.get(0).get(4));
        assertEquals("first_parent_metric", rows.get(1).get(4));
        assertEquals("second_parent_metric", rows.get(2).get(4));
        File[] leftovers = csv.getParentFile().listFiles((directory, name) ->
                name.startsWith(csv.getName() + ".parent.")
                        && name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void parentFinalizeMakesBatchOutcomeCanonicalAcrossExistingRows()
            throws Exception {
        File csv = new File(System.getProperty(CSV_FILE_PROPERTY));
        UpdatingControllerEvaluationRecorder.setMode("Stepwise Delayed DUC");
        UpdatingControllerEvaluationRecorder.markSuccess();
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv,
                "Stepwise Delayed DUC",
                "TIMEOUT",
                "Batch timeout",
                Arrays.asList(new ExternalDataMetric(
                        "batch_result",
                        "Run",
                        "batch final result",
                        "TIMEOUT",
                        "text")));

        List<List<String>> rows = readCsvRows(csv);
        Map<String, String> values = readMetricValues(csv);
        assertEquals("TIMEOUT", values.get("result"));
        assertEquals("Batch timeout", values.get("failure_reason"));
        assertEquals("TIMEOUT", values.get("batch_result"));
        for (int index = 1; index < rows.size(); index++) {
            assertEquals("TIMEOUT", rows.get(index).get(1));
            assertEquals("Batch timeout", rows.get(index).get(2));
        }
    }

    @Test
    public void batchChildCsvStaysPendingUntilParentAtomicFinalize()
            throws Exception {
        File csv = new File(System.getProperty(CSV_FILE_PROPERTY));
        System.setProperty(
                UpdatingControllerEvaluationRecorder
                        .PARENT_FINALIZATION_REQUIRED_PROPERTY,
                "true");
        UpdatingControllerEvaluationRecorder.setMode("Stepwise Delayed DUC");
        UpdatingControllerEvaluationRecorder.markSuccess();
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        List<List<String>> pendingRows = readCsvRows(csv);
        Map<String, String> pendingValues = readMetricValues(csv);
        assertEquals(
                UpdatingControllerEvaluationRecorder.PARENT_FINALIZATION_PENDING,
                pendingValues.get("result"));
        for (int index = 1; index < pendingRows.size(); index++) {
            assertEquals(
                    UpdatingControllerEvaluationRecorder
                            .PARENT_FINALIZATION_PENDING,
                    pendingRows.get(index).get(1));
        }

        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv,
                "Stepwise Delayed DUC",
                "SUCCESS",
                "",
                Arrays.asList(new ExternalDataMetric(
                        "batch_result",
                        "Run",
                        "batch final result",
                        "SUCCESS",
                        "text")));

        List<List<String>> finalizedRows = readCsvRows(csv);
        Map<String, String> finalizedValues = readMetricValues(csv);
        assertEquals("SUCCESS", finalizedValues.get("result"));
        assertEquals("SUCCESS", finalizedValues.get("batch_result"));
        for (int index = 1; index < finalizedRows.size(); index++) {
            assertEquals("SUCCESS", finalizedRows.get(index).get(1));
        }
    }

    @Test
    public void batchChildFailureIsNotHiddenByPendingStatus()
            throws Exception {
        File csv = new File(System.getProperty(CSV_FILE_PROPERTY));
        System.setProperty(
                UpdatingControllerEvaluationRecorder
                        .PARENT_FINALIZATION_REQUIRED_PROPERTY,
                "true");
        UpdatingControllerEvaluationRecorder.setMode("Traditional DUC");
        UpdatingControllerEvaluationRecorder.recordFailure(
                UpdatingControllerEvaluationRecorder.ResultStatus.OUT_OF_MEMORY,
                "child OOM");
        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        List<List<String>> rows = readCsvRows(csv);
        Map<String, String> values = readMetricValues(csv);
        assertEquals("OUT_OF_MEMORY", values.get("result"));
        assertEquals("child OOM", values.get("failure_reason"));
        for (int index = 1; index < rows.size(); index++) {
            assertEquals("OUT_OF_MEMORY", rows.get(index).get(1));
            assertEquals("child OOM", rows.get(index).get(2));
        }
    }

    @Test
    public void parentFinalizeDoesNotLegitimizeAnUnterminatedCsvRecord()
            throws Exception {
        File csv = temporaryFolder.newFile("external-corrupt.csv");
        Files.write(csv.toPath(), "stale,\"partial".getBytes(StandardCharsets.UTF_8));

        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                csv,
                "Traditional DUC",
                "TIMEOUT",
                "Batch timeout",
                Arrays.asList(new ExternalDataMetric(
                        "result",
                        "Run",
                        "result",
                        "TIMEOUT",
                        "text")));

        List<List<String>> rows = readCsvRows(csv);
        assertEquals(2, rows.size());
        assertEquals("metric_key", rows.get(0).get(4));
        assertEquals("result", rows.get(1).get(4));
        assertEquals("TIMEOUT", rows.get(1).get(6));
        assertFalse(new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8)
                .contains("stale"));
    }

    @Test
    public void completedCsvAtomicallyReplacesStaleTargetAndRemovesTempFile()
            throws Exception {
        File csv = new File(System.getProperty(CSV_FILE_PROPERTY));
        Files.write(csv.toPath(), "stale,\"partial".getBytes(StandardCharsets.UTF_8));
        UpdatingControllerEvaluationRecorder.setMode("Traditional DUC");
        UpdatingControllerEvaluationRecorder.markSuccess();

        UpdatingControllerEvaluationRecorder.printSummary(new RecordingOutput());

        String content = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.startsWith("mode,result,failure_reason,section,metric_key"));
        assertFalse(content.contains("stale,\"partial"));
        File[] leftovers = csv.getParentFile().listFiles((directory, name) ->
                name.startsWith(csv.getName() + ".") && name.endsWith(".tmp"));
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    private static Map<String, String> readMetricValues(File csvFile) throws Exception {
        Map<String, String> result = new HashMap<String, String>();
        for (List<String> fields : readCsvRows(csvFile)) {
            if (fields.size() > 6 && !"metric_key".equals(fields.get(4))) {
                result.put(fields.get(4), fields.get(6));
            }
        }
        return result;
    }

    private static List<List<String>> readCsvRows(File csvFile) throws Exception {
        List<List<String>> result = new ArrayList<List<String>>();
        for (String line : Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8)) {
            result.add(parseCsvLine(line));
        }
        return result;
    }

    private static List<String> observationValues(
            List<List<String>> rows,
            String label,
            String unit) {
        List<String> result = new ArrayList<String>();
        for (List<String> fields : rows) {
            if (fields.size() > 7
                    && STEPWISE_STATE_SPACE_SECTION.equals(fields.get(3))
                    && fields.get(4).contains("_observation_")
                    && label.equals(fields.get(5))
                    && unit.equals(fields.get(7))) {
                result.add(fields.get(6));
            }
        }
        return result;
    }

    private static List<String> stableBaseValues(
            List<List<String>> rows,
            String label,
            String unit) {
        List<String> result = new ArrayList<String>();
        for (List<String> fields : rows) {
            if (fields.size() > 7
                    && STEPWISE_STATE_SPACE_SECTION.equals(fields.get(3))
                    && !fields.get(4).contains("_observation_")
                    && label.equals(fields.get(5))
                    && unit.equals(fields.get(7))) {
                result.add(fields.get(6));
            }
        }
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class RecordingOutput implements LTSOutput {
        private final List<String> lines = new ArrayList<String>();

        public void out(String str) {
            lines.add(str);
        }

        public void outln(String str) {
            lines.add(str + "\n");
        }

        public void clearOutput() {
            lines.clear();
        }

        private String joined() {
            StringBuilder result = new StringBuilder();
            for (String line : lines) {
                result.append(line);
            }
            return result.toString();
        }
    }
}
