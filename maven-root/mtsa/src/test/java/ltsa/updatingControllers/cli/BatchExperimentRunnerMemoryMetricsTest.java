package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ExternalDataMetric;

public class BatchExperimentRunnerMemoryMetricsTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesSynthesisStartRssAndPeakMinusStartIncrease()
            throws Exception {
        AtomicLong currentRss = new AtomicLong(100L);
        ProcessRssSampler sampler = new ProcessRssSampler(
                1234L,
                60_000L,
                "fake",
                currentRss::get);
        sampler.start();

        ByteArrayOutputStream childInput = new ByteArrayOutputStream();
        MemoryWindowHandshakeCoordinator coordinator =
                new MemoryWindowHandshakeCoordinator(childInput);
        coordinator.samplerReady(sampler);
        currentRss.set(300L);
        coordinator.synthesisWindowStarted();
        currentRss.set(500L);
        coordinator.synthesisWindowEnded();

        ProcessRssSampler.Result rssResult = sampler.stop();
        MemoryWindowHandshakeCoordinator.Result protocolResult =
                coordinator.snapshot();
        coordinator.close();
        assertTrue(protocolResult.isComplete());

        File root = temporaryFolder.newFolder("rss-csv");
        File configFile = new File(root, "config.yaml");
        String yaml = "outputDir: " + new File(root, "result").getPath() + "\n"
                + "cases:\n"
                + "  - id: memory_metric_test\n"
                + "    example: MemoryMetricTest\n"
                + "    method: Traditional\n"
                + "    variant: no_tr\n"
                + "    lts: dummy.lts\n"
                + "    target: UpdCont\n";
        Files.write(configFile.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        ExperimentConfig config = ExperimentConfig.load(configFile);
        ExperimentConfig.ExperimentCase experimentCase = config.cases.get(0);

        Class<?> casePathsClass = nestedClass("CasePaths");
        Method createPaths = casePathsClass.getDeclaredMethod(
                "create",
                File.class,
                ExperimentConfig.ExperimentCase.class,
                boolean.class,
                int.class,
                int.class,
                String.class);
        createPaths.setAccessible(true);
        Object paths = createPaths.invoke(
                null,
                config.outputDir,
                experimentCase,
                Boolean.FALSE,
                Integer.valueOf(1),
                Integer.valueOf(1),
                "run_01");

        Class<?> caseResultClass = nestedClass("CaseResult");
        Constructor<?> caseResultConstructor =
                caseResultClass.getDeclaredConstructor();
        caseResultConstructor.setAccessible(true);
        Object caseResult = caseResultConstructor.newInstance();
        setField(caseResult, "status", "SUCCESS");
        setField(caseResult, "rssSamplingEnabled", Boolean.TRUE);
        setField(caseResult, "heapSamplingEnabled", Boolean.FALSE);
        setField(caseResult, "windowsPeakWorkingSetEnabled", Boolean.FALSE);
        setField(caseResult, "rssResult", rssResult);
        setField(caseResult, "memoryProtocolResult", protocolResult);

        Method appendMetrics = BatchExperimentRunner.class.getDeclaredMethod(
                "appendProcessMemoryMetrics",
                ExperimentConfig.class,
                casePathsClass,
                ExperimentConfig.ExperimentCase.class,
                caseResultClass);
        appendMetrics.setAccessible(true);
        appendMetrics.invoke(null, config, paths, experimentCase, caseResult);

        File evaluationCsv = (File) getField(paths, "evaluationCsvFile");
        String csv = new String(
                Files.readAllBytes(evaluationCsv.toPath()),
                StandardCharsets.UTF_8);
        Map<String, String> metrics = metricValues(
                BatchExperimentRunner.parseCsvRecords(csv));

        assertEquals("300",
                metrics.get("controller_synthesis_sampled_base_process_rss"));
        assertEquals("500",
                metrics.get("controller_synthesis_sampled_peak_process_rss"));
        assertEquals("200",
                metrics.get("controller_synthesis_sampled_process_rss_increase"));
        assertEquals("SUCCESS", getField(caseResult, "status"));
    }

    @Test
    public void heapGapAboveRssLimitIsWarningWithoutCaseFailure()
            throws Exception {
        HeapOutcome outcome = appendHeapMetrics(
                "validateMemoryQuality: true\n"
                        + "maxRssSamplingGapMillis: 1000\n",
                1143L,
                0L);

        assertEquals("SUCCESS", outcome.status);
        assertEquals("true",
                outcome.metrics.get("batch_memory_quality_validation_passed"));
        assertEquals("true",
                outcome.metrics.get("batch_heap_sampling_gap_warning"));
        assertEquals("1000",
                outcome.metrics.get(
                        "batch_heap_sampling_gap_warning_reference_ms"));
        assertEquals("1000",
                outcome.metrics.get("batch_rss_sampling_max_gap_limit_ms"));
        assertFalse(outcome.metrics.containsKey(
                "batch_heap_sampling_max_gap_limit_ms"));
        assertEquals("2026-08-06-rss-primary-v1",
                outcome.metrics.get("batch_memory_quality_policy"));
    }

    @Test
    public void heapSamplingFailureStillFailsMemoryQuality()
            throws Exception {
        HeapOutcome outcome = appendHeapMetrics(
                "validateMemoryQuality: true\n"
                        + "maxRssSamplingGapMillis: 1000\n",
                50L,
                1L);

        assertEquals("MEASUREMENT_FAILED", outcome.status);
        assertEquals("false",
                outcome.metrics.get("batch_memory_quality_validation_passed"));
        assertTrue(outcome.metrics.get("batch_memory_quality_validation_errors")
                .contains("heap_memory_sampling_failure_count=1"));
    }

    @Test
    public void legacyJointGapLimitStillRejectsHeapGap()
            throws Exception {
        HeapOutcome outcome = appendHeapMetrics(
                "validateMemoryQuality: true\n"
                        + "maxMemorySamplingGapMillis: 1000\n",
                1143L,
                0L);

        assertEquals("MEASUREMENT_FAILED", outcome.status);
        assertEquals("1000",
                outcome.metrics.get("batch_memory_sampling_max_gap_limit_ms"));
        assertTrue(outcome.metrics.get("batch_memory_quality_validation_errors")
                .contains("heap_memory_sampling_max_gap_ms=1143>1000"));
    }

    @Test
    public void explicitHeapGapLimitRejectsHeapGap()
            throws Exception {
        HeapOutcome outcome = appendHeapMetrics(
                "validateMemoryQuality: true\n"
                        + "maxRssSamplingGapMillis: 1000\n"
                        + "maxHeapSamplingGapMillis: 1000\n",
                1143L,
                0L);

        assertEquals("MEASUREMENT_FAILED", outcome.status);
        assertEquals("1000",
                outcome.metrics.get("batch_heap_sampling_max_gap_limit_ms"));
        assertTrue(outcome.metrics.get("batch_memory_quality_validation_errors")
                .contains("heap_memory_sampling_max_gap_ms=1143>1000"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void rssGapAboveRssLimitRemainsHardQualityFailure()
            throws Exception {
        AtomicLong currentRss = new AtomicLong(100L);
        ProcessRssSampler sampler = new ProcessRssSampler(
                1234L,
                60_000L,
                "fake",
                currentRss::get);
        sampler.start();
        sampler.beginSynthesisWindow();
        Thread.sleep(20L);
        sampler.endSynthesisWindow();
        ProcessRssSampler.Result rssResult = sampler.stop();
        assertTrue(rssResult.getSynthesisWindowMaxSampleGapMillis() > 1L);

        File root = temporaryFolder.newFolder("rss-gap");
        ExperimentConfig config = loadConfig(
                root,
                "validateMemoryQuality: true\n"
                        + "maxRssSamplingGapMillis: 1\n");
        Object caseResult = newCaseResult(false, true);
        setField(caseResult, "rssResult", rssResult);

        Method qualityErrors = BatchExperimentRunner.class.getDeclaredMethod(
                "memoryQualityErrors",
                ExperimentConfig.class,
                nestedClass("CaseResult"),
                Map.class,
                boolean.class);
        qualityErrors.setAccessible(true);
        List<String> errors = (List<String>) qualityErrors.invoke(
                null,
                config,
                caseResult,
                new LinkedHashMap<String, String>(),
                Boolean.TRUE);

        assertTrue(errors.toString().contains(
                "process_rss_synthesis_window_max_gap_ms="));
        assertTrue(errors.toString().contains(">1"));
    }

    private HeapOutcome appendHeapMetrics(
            String configOptions,
            long maxGapMillis,
            long failureCount) throws Exception {
        File root = temporaryFolder.newFolder("heap-gap-" + System.nanoTime());
        ExperimentConfig config = loadConfig(root, configOptions);
        ExperimentConfig.ExperimentCase experimentCase = config.cases.get(0);

        Class<?> casePathsClass = nestedClass("CasePaths");
        Method createPaths = casePathsClass.getDeclaredMethod(
                "create",
                File.class,
                ExperimentConfig.ExperimentCase.class,
                boolean.class,
                int.class,
                int.class,
                String.class);
        createPaths.setAccessible(true);
        Object paths = createPaths.invoke(
                null,
                config.outputDir,
                experimentCase,
                Boolean.FALSE,
                Integer.valueOf(1),
                Integer.valueOf(1),
                "run_01");
        File evaluationCsv = (File) getField(paths, "evaluationCsvFile");
        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                evaluationCsv,
                "Traditional DUC",
                UpdatingControllerEvaluationRecorder.PARENT_FINALIZATION_PENDING,
                "",
                Arrays.asList(
                        heapMetric("heap_memory_sampling_available", "true", "boolean"),
                        heapMetric("controller_synthesis_sampled_peak_heap_used", "4096", "B"),
                        heapMetric("heap_memory_sampling_sample_count", "100", "count"),
                        heapMetric("heap_memory_sampling_failure_count",
                                Long.toString(failureCount), "count"),
                        heapMetric("heap_memory_sampling_max_gap_ms",
                                Long.toString(maxGapMillis), "ms")));

        Object caseResult = newCaseResult(true, false);
        Method appendMetrics = BatchExperimentRunner.class.getDeclaredMethod(
                "appendProcessMemoryMetrics",
                ExperimentConfig.class,
                casePathsClass,
                ExperimentConfig.ExperimentCase.class,
                nestedClass("CaseResult"));
        appendMetrics.setAccessible(true);
        appendMetrics.invoke(null, config, paths, experimentCase, caseResult);

        String csv = new String(
                Files.readAllBytes(evaluationCsv.toPath()),
                StandardCharsets.UTF_8);
        return new HeapOutcome(
                (String) getField(caseResult, "status"),
                metricValues(BatchExperimentRunner.parseCsvRecords(csv)));
    }

    private ExperimentConfig loadConfig(File root, String configOptions)
            throws Exception {
        File configFile = new File(root, "config.yaml");
        String yaml = "outputDir: " + new File(root, "result").getPath() + "\n"
                + configOptions
                + "cases:\n"
                + "  - id: memory_metric_test\n"
                + "    example: MemoryMetricTest\n"
                + "    method: Traditional\n"
                + "    variant: no_tr\n"
                + "    lts: dummy.lts\n"
                + "    target: UpdCont\n";
        Files.write(configFile.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        return ExperimentConfig.load(configFile);
    }

    private static Object newCaseResult(
            boolean heapSamplingEnabled,
            boolean rssSamplingEnabled) throws Exception {
        Class<?> caseResultClass = nestedClass("CaseResult");
        Constructor<?> constructor = caseResultClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        setField(result, "status", "SUCCESS");
        setField(result, "heapSamplingEnabled", Boolean.valueOf(heapSamplingEnabled));
        setField(result, "rssSamplingEnabled", Boolean.valueOf(rssSamplingEnabled));
        setField(result, "windowsPeakWorkingSetEnabled", Boolean.FALSE);
        return result;
    }

    private static ExternalDataMetric heapMetric(
            String key,
            String value,
            String unit) {
        return new ExternalDataMetric(
                key,
                "Heap memory measurement",
                key,
                value,
                unit);
    }

    private static Class<?> nestedClass(String simpleName)
            throws ClassNotFoundException {
        return Class.forName(BatchExperimentRunner.class.getName()
                + "$" + simpleName);
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Map<String, String> metricValues(
            List<List<String>> records) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (int index = 1; index < records.size(); index++) {
            List<String> row = records.get(index);
            if (row.size() > 6) {
                values.put(row.get(4), row.get(6));
            }
        }
        return values;
    }

    private static final class HeapOutcome {
        final String status;
        final Map<String, String> metrics;

        HeapOutcome(String status, Map<String, String> metrics) {
            this.status = status;
            this.metrics = metrics;
        }
    }
}
