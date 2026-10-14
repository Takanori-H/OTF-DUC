package ltsa.updatingControllers.cli;

import ltsa.differential.nativeintegration.NativeDifferentialOptions;
import ltsa.differential.nativeintegration.NativeDifferentialResult;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Native modes use the ordinary runner, with explicit method validation and phase units. */
public class NativeDifferentialBatchIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeCasesUseOrdinaryChildAndAlwaysValidateMethod() throws Exception {
        ExperimentConfig config = config(false);
        for (ExperimentCase experimentCase : config.cases) {
            assertTrue(experimentCase.isNativeDifferentialSynthesis());
            assertFalse(experimentCase.isDifferentialSynthesis());
            Class<?> pathsClass = Class.forName(BatchExperimentRunner.class.getName() + "$CasePaths");
            Method create = pathsClass.getDeclaredMethod("create", File.class, ExperimentCase.class,
                    boolean.class, int.class, int.class, String.class);
            create.setAccessible(true);
            Object paths = create.invoke(null, config.outputDir, experimentCase, false, 1, 1, null);
            Method build = BatchExperimentRunner.class.getDeclaredMethod("buildChildCommand",
                    ExperimentConfig.class, ExperimentCase.class, pathsClass);
            build.setAccessible(true);
            @SuppressWarnings("unchecked") List<String> command = (List<String>) build.invoke(null,
                    config, experimentCase, paths);
            assertTrue(command.contains(SingleCompositionRunner.class.getName()));
            assertFalse(command.contains("ltsa.differential.DifferentialSynthesisRunner"));
            assertFalse(command.contains("--scenario"));
            assertEquals(experimentCase.method, command.get(command.indexOf("--expected-method") + 1));
        }
    }

    @Test public void rotatesThreeNativeMethodsWithoutChangingTheirFspTargets() throws Exception {
        ExperimentConfig config = config(true);
        ExperimentCase full = config.cases.get(0), analysis = config.cases.get(1), differential = config.cases.get(2);
        assertEquals(Arrays.asList(full, analysis, differential),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 1));
        assertEquals(Arrays.asList(analysis, differential, full),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 2));
        assertEquals(Arrays.asList(differential, full, analysis),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 3));
    }

    @Test public void expectedModeMustMatchActualFspOptions() {
        for (NativeDifferentialOptions.Mode mode : NativeDifferentialOptions.Mode.values()) {
            CompositeState current = state(mode);
            String method = NativeDifferentialResult.methodName(mode);
            assertTrue(SingleCompositionRunner.MethodValidation.inspect(current, "C", method).matches);
            assertFalse(SingleCompositionRunner.MethodValidation.inspect(current, "C", "OTF").matches);
            assertFalse(SingleCompositionRunner.MethodValidation.inspect(current, "C",
                    "native_differential_unknown_otf").matches);
            assertEquals(method, SingleCompositionRunner.MethodValidation.inspect(current, "C", null).actualMethod);
        }
        CompositeState normal = new CompositeState();
        normal.name = "C";
        assertFalse(SingleCompositionRunner.MethodValidation.inspect(normal, "C",
                "native_differential_full").matches);
    }

    @Test public void exportsNanosAndSampledHeapSeparatelyAndKeepsSkippedPhasesAbsent() throws Exception {
        synchronized (UpdatingControllerEvaluationRecorder.class) {
            String enabled = System.getProperty("mtsa.evaluation.enabled");
            String csvProperty = System.getProperty("mtsa.evaluation.csvFile");
            try {
                File csv = new File(temporary.getRoot(), "native.csv");
                System.setProperty("mtsa.evaluation.enabled", "true");
                System.setProperty("mtsa.evaluation.csvFile", csv.getAbsolutePath());
                UpdatingControllerEvaluationRecorder.reset();
                CompositeState current = state(NativeDifferentialOptions.Mode.DIFFERENTIAL);
                Map<String, Long> phases = new LinkedHashMap<>();
                phases.put("snapshot_restore", 111L);
                phases.put("game_update", 222L);
                phases.put("requirements_analysis", 333L);
                phases.put("controller_generation", 444000000L);
                phases.put("controller_build", 111000000L);
                phases.put("controller_conversion", 55000000L);
                phases.put("controller_postcompose", 200000000L);
                phases.put("controller_copy", 10000000L);
                phases.put("controller_patch", 99000000L);
                phases.put("compatibility", 555L);
                phases.put("update_total", 1554000000L);
                Map<String, Long> memory = new LinkedHashMap<>();
                memory.put("restored_start_heap", 4000L);
                memory.put("update_peak_heap", 5000L);
                Map<String, Long> gcMillis = new LinkedHashMap<>();
                gcMillis.put("controller_generation", 7L);
                gcMillis.put("controller_build", 1L);
                gcMillis.put("controller_conversion", 0L);
                gcMillis.put("controller_postcompose", 6L);
                Map<String, Long> gcCounts = new LinkedHashMap<>();
                gcCounts.put("controller_generation", 3L);
                gcCounts.put("controller_build", 1L);
                gcCounts.put("controller_conversion", 0L);
                gcCounts.put("controller_postcompose", 2L);
                current.differentialResult = new NativeDifferentialResult(current.differentialOptions.getMode(),
                        phases, memory, gcMillis, gcCounts);
                phases.put("game_update", 999L);
                memory.clear();
                gcMillis.put("controller_generation", 999L);
                gcCounts.clear();
                for (Map<String, Long> measurements : Arrays.asList(current.differentialResult.getPhaseNanos(),
                        current.differentialResult.getMemoryBytes(), current.differentialResult.getGcCollectionMillis(),
                        current.differentialResult.getGcCollectionCounts())) {
                    assertImmutable(measurements);
                }
                SingleCompositionRunner.recordNativeDifferentialMetrics(current);
                UpdatingControllerEvaluationRecorder.writeDataCsvFileIfConfigured(new EmptyLTSOuput());
                Method read = BatchExperimentRunner.class.getDeclaredMethod("readEvaluationCsvValues", File.class);
                read.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) read.invoke(null, csv);
                assertEquals("mtsa_native", values.get("native_ds_backend"));
                assertEquals("1", values.get("native_ds_generation_timing_version"));
                assertEquals("222", values.get("native_ds_game_update_ns"));
                assertEquals("111000000", values.get("native_ds_controller_build_ns"));
                assertEquals("10000000", values.get("native_ds_controller_copy_ns"));
                assertEquals("99000000", values.get("native_ds_controller_patch_ns"));
                assertEquals("55000000", values.get("native_ds_controller_conversion_ns"));
                assertEquals("200000000", values.get("native_ds_controller_postcompose_ns"));
                assertEquals("7", values.get("native_ds_controller_generation_gc_collection_ms"));
                assertEquals("3", values.get("native_ds_controller_generation_gc_collection_count"));
                assertEquals("0", values.get("native_ds_controller_conversion_gc_collection_ms"));
                assertEquals("0", values.get("native_ds_controller_conversion_gc_collection_count"));
                assertCsvUnit(csv, "native_ds_controller_build_ns", "ns");
                assertCsvUnit(csv, "native_ds_controller_generation_gc_collection_ms", "ms");
                assertCsvUnit(csv, "native_ds_controller_generation_gc_collection_count", "count");
                assertEquals("4000", values.get("native_ds_restored_start_heap_bytes"));
                assertEquals("5000", values.get("native_ds_update_peak_heap_bytes"));
                assertFalse(values.containsKey("native_ds_snapshot_save_ns"));
                assertFalse(values.containsKey("native_ds_initial_total_ns"));
                assertFalse("A missing counter is unsupported, not zero",
                        values.containsKey("native_ds_controller_copy_gc_collection_ms"));
                assertFalse(values.containsKey("native_ds_controller_patch_gc_collection_count"));
                assertFalse(values.containsKey("native_ds_snapshot_save_gc_collection_ms"));
                assertFalse("GC milliseconds must never be exported as nanoseconds",
                        values.containsKey("native_ds_controller_generation_gc_collection_ns"));
            } finally {
                restore("mtsa.evaluation.enabled", enabled);
                restore("mtsa.evaluation.csvFile", csvProperty);
                UpdatingControllerEvaluationRecorder.reset();
            }
        }
    }

    @Test public void noGenerationIntervalsDoNotFabricateTimingVersionOrGcMeasurements() throws Exception {
        synchronized (UpdatingControllerEvaluationRecorder.class) {
            String enabled = System.getProperty("mtsa.evaluation.enabled");
            String csvProperty = System.getProperty("mtsa.evaluation.csvFile");
            try {
                File csv = new File(temporary.getRoot(), "without-generation.csv");
                System.setProperty("mtsa.evaluation.enabled", "true");
                System.setProperty("mtsa.evaluation.csvFile", csv.getAbsolutePath());
                UpdatingControllerEvaluationRecorder.reset();
                CompositeState current = state(NativeDifferentialOptions.Mode.PREPARE);
                current.differentialResult = new NativeDifferentialResult(current.differentialOptions.getMode(),
                        Collections.singletonMap("initial_total", 111L));
                assertTrue(current.differentialResult.getGcCollectionMillis().isEmpty());
                assertTrue(current.differentialResult.getGcCollectionCounts().isEmpty());
                SingleCompositionRunner.recordNativeDifferentialMetrics(current);
                UpdatingControllerEvaluationRecorder.writeDataCsvFileIfConfigured(new EmptyLTSOuput());
                Method read = BatchExperimentRunner.class.getDeclaredMethod("readEvaluationCsvValues", File.class);
                read.setAccessible(true);
                @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) read.invoke(null, csv);
                assertEquals("111", values.get("native_ds_initial_total_ns"));
                assertFalse(values.containsKey("native_ds_generation_timing_version"));
                assertFalse(values.containsKey("native_ds_controller_generation_ns"));
                assertFalse(values.keySet().stream().anyMatch(key -> key.contains("_gc_collection_")));
            } finally {
                restore("mtsa.evaluation.enabled", enabled);
                restore("mtsa.evaluation.csvFile", csvProperty);
                UpdatingControllerEvaluationRecorder.reset();
            }
        }
    }

    private static void assertImmutable(Map<String, Long> measurements) {
        try {
            measurements.put("unexpected_mutation", 1L);
            fail("Measurement maps must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void assertCsvUnit(File csv, String key, String expectedUnit) throws Exception {
        List<List<String>> rows = BatchExperimentRunner.parseCsvRecords(
                new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8));
        int keyIndex = rows.get(0).indexOf("metric_key"), unitIndex = rows.get(0).indexOf("unit");
        assertTrue(keyIndex >= 0 && unitIndex >= 0);
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.size() > Math.max(keyIndex, unitIndex) && key.equals(row.get(keyIndex))) {
                assertEquals(expectedUnit, row.get(unitIndex));
                return;
            }
        }
        fail("No metric row for " + key);
    }

    @Test public void missingNativeResultFailsInsteadOfWritingSuccessfulMetrics() {
        try {
            SingleCompositionRunner.recordNativeDifferentialMetrics(state(NativeDifferentialOptions.Mode.FULL));
            fail("Missing native result was accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("without matching measurements"));
        }
        SingleCompositionRunner.recordNativeDifferentialMetrics(new CompositeState());
    }

    private CompositeState state(NativeDifferentialOptions.Mode mode) {
        CompositeState current = new CompositeState();
        current.name = "C";
        current.differentialOptions = new NativeDifferentialOptions(mode, "old.ds",
                mode == NativeDifferentialOptions.Mode.PREPARE ? null : "change.json", null,
                temporary.getRoot().getAbsolutePath());
        return current;
    }

    private ExperimentConfig config(boolean alternate) throws Exception {
        File source = new File(temporary.getRoot(), "input.lts");
        Files.write(source.toPath(), "E=(a->E).\n".getBytes(StandardCharsets.UTF_8));
        StringBuilder yaml = new StringBuilder("outputDir: " + new File(temporary.getRoot(), "out")
                + "\nnotifyOn: never\nvalidateMethodTarget: false\nalternateMethodOrderByRun: " + alternate + "\ncases:\n");
        for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
            yaml.append("  - id: ").append(mode).append("\n    example: Native\n    method: native_differential_")
                    .append(mode).append("\n    lts: ").append(source).append("\n    target: C_").append(mode).append('\n');
        }
        File config = new File(temporary.getRoot(), "config.yaml");
        Files.write(config.toPath(), yaml.toString().getBytes(StandardCharsets.UTF_8));
        return ExperimentConfig.load(config);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
