package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ExternalDataMetric;
import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;

public class BatchExperimentRunnerDifferentialTest {
    private static final String INPUT_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OUTPUT_HASH = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void onlyExplicitModeSelectsDedicatedChildAndPreservesCurrentJvm() throws Exception {
        ExperimentConfig differential = loadConfig("", "differential-synthesis", "full", true, "");
        ExperimentCase experimentCase = differential.cases.get(0);
        assertEquals("FULL", experimentCase.method);
        Object paths = paths(differential);
        List<String> command = command(differential, paths);
        assertEquals(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath(),
                command.get(0));
        assertEquals(System.getProperty("java.class.path"), command.get(command.indexOf("-cp") + 1));
        assertTrue(command.contains("ltsa.differential.DifferentialSynthesisRunner"));
        assertEquals("FULL", command.get(command.indexOf("--method") + 1));
        assertEquals("generation", command.get(command.indexOf("--measurement-scope") + 1));
        assertEquals(experimentCase.scenario.getPath(), command.get(command.indexOf("--scenario") + 1));
        assertFalse(command.contains("--minimized-transitions"));
        assertFalse(command.contains("--expected-method"));

        ExperimentConfig ordinary = loadConfig("", null, "FULL", false, "");
        List<String> ordinaryCommand = command(ordinary, paths(ordinary));
        assertTrue(ordinaryCommand.contains(SingleCompositionRunner.class.getName()));
        assertTrue(ordinaryCommand.contains("--minimized-transitions"));
        assertFalse(ordinaryCommand.contains("--scenario"));
    }

    @Test
    public void rejectsInvalidOrAmbiguousDifferentialConfiguration() throws Exception {
        assertRejected("", "differential-synthesis", "OTHER", true, "", "FULL, DIFFERENTIAL, or FROM_SCRATCH");
        assertRejected("", "differential-synthesis", "FULL", false, "", "scenario file");
        assertRejected("", null, "FULL", true, "", "scenario requires");
        assertRejected("", "differential-synthesi", "FULL", true, "", "Unknown experiment mode");
        assertRejected("traceCheck: true\n", "differential-synthesis", "FULL", true, "",
                "does not support");
        assertRejected("requirementsCheck: true\n", "differential-synthesis", "FULL", true, "",
                "does not support");
        assertRejected("", "differential-synthesis", "FULL", true, "    requirementsCheck: true\n",
                "does not support");
    }

    @Test
    public void revalidatesCliOverridesAndAllowsExplicitlyDisabledRequirementCheck() throws Exception {
        ExperimentConfig config = loadConfig("requirementsCheck: true\n", "differential-synthesis",
                "DIFFERENTIAL", true, "    requirementsCheck: false\n");
        config.traceCheck = true;
        try {
            config.validateModeOptions();
            fail("Expected a command-line trace-check override to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("does not support"));
        }
    }

    @Test
    public void usesCaseIdentityForMultipleScenariosAndAlternatesDifferentialPair() throws Exception {
        ExperimentConfig config = loadConfig("alternateMethodOrderByRun: true\n",
                "differential-synthesis", "FULL", true, "");
        ExperimentCase full = config.cases.get(0);
        ExperimentCase differential = new ExperimentCase();
        differential.id = "second_scenario_case";
        differential.example = full.example;
        differential.variant = full.variant;
        differential.mode = full.mode;
        differential.method = "DIFFERENTIAL";
        differential.lts = full.lts;
        differential.scenario = full.scenario;
        differential.target = full.target;
        config.cases.add(differential);
        assertEquals(Arrays.asList(differential, full),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 1));
        assertEquals(Arrays.asList(full, differential),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 2));
        assertEquals("case_one", full.filePrefix());
        assertEquals("second_scenario_case", differential.filePrefix());
    }

    @Test
    public void dedicatedArtifactsDoNotRequireMinimizedOrDucMetrics() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "DIFFERENTIAL", true, "");
        Object paths = paths(config);
        writeArtifacts(paths, "SUCCESS");
        assertEquals(new ArrayList<String>(), validate(config, paths));
        assertEquals("SUCCESS", statusFromExitCode(paths));
        @SuppressWarnings("unchecked")
        List<File> artifacts = (List<File>) invoke(paths, "artifactFiles", new Class<?>[0]);
        assertFalse(artifacts.contains(field(paths, "minimizedTransitionsFile")));

        ExperimentConfig ordinary = loadConfig("", null, "Traditional", false, "");
        Object ordinaryPaths = paths(ordinary);
        writeArtifacts(ordinaryPaths, "SUCCESS");
        assertEquals("NO_TRANSITION_OUTPUT", statusFromExitCode(ordinaryPaths));
        assertFalse(validate(ordinary, ordinaryPaths).isEmpty());
    }

    @Test
    public void normalNegativeOutcomesDoNotRequireGenerationMemory() throws Exception {
        for (String status : new String[] {"BASE_UNREALIZABLE", "UNREALIZABLE", "SIMULATION_INCOMPATIBLE"}) {
            ExperimentConfig config = loadConfig("validateMemoryQuality: true\n",
                    "differential-synthesis", "FULL", true, "");
            Object paths = paths(config);
            writeArtifacts(paths, status);
            assertTrue(validate(config, paths).isEmpty());
            Object result = newResult(true, true);
            appendMemory(config, paths, result);
            assertEquals("SUCCESS", field(result, "status"));
            assertEquals("false", metrics(paths).get("batch_memory_quality_validation_applicable"));
            assertEquals(status, metrics(paths).get("ds_status"));
            assertEquals("Differential synthesis", metrics(paths).get("mode"));
            assertTrue(validateCampaign(config, paths, result).isEmpty());
        }
    }

    @Test
    public void successfulGenerationStillRequiresEnabledMemoryMeasurements() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "FULL", true, "");
        Object paths = paths(config);
        writeArtifacts(paths, "SUCCESS");
        Object result = newResult(true, true);
        appendMemory(config, paths, result);
        assertEquals("MEASUREMENT_FAILED", field(result, "status"));
    }

    @Test
    public void rejectsUnknownStatusAndMissingOrInvalidPhaseTiming() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "FULL", true, "");
        Object paths = paths(config);
        writeArtifacts(paths, "NOT_A_STATUS");
        assertFalse(validate(config, paths).isEmpty());
        writeArtifacts(paths, "SUCCESS");
        File csv = (File) field(paths, "evaluationCsvFile");
        String contents = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        Files.write(csv.toPath(), contents.replace("ds_generation_ns", "missing_generation_ns")
                .getBytes(StandardCharsets.UTF_8));
        assertTrue(validate(config, paths).toString().contains("ds_generation_ns"));
    }

    @Test
    public void pairedNormalRunsMustAgreeOnPreparedInputOutcomeAndOutput() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "FULL", true, "");
        ExperimentCase full = config.cases.get(0);
        ExperimentCase differential = new ExperimentCase();
        differential.id = "differential_case";
        differential.example = full.example;
        differential.mode = full.mode;
        differential.method = "DIFFERENTIAL";
        differential.variant = full.variant;
        differential.lts = full.lts;
        differential.scenario = full.scenario;
        differential.target = full.target;
        config.cases.add(differential);
        Object fullPaths = paths(config);
        Object differentialPaths = paths(config, differential);
        writeArtifacts(fullPaths, "SUCCESS");
        writeArtifacts(differentialPaths, "SUCCESS");
        Object fullResult = newResult(false, false);
        Object differentialResult = newResult(false, false);
        Object fullCompleted = completed(full, fullPaths, fullResult);
        Object differentialCompleted = completed(differential, differentialPaths, differentialResult);
        List<Object> cases = Arrays.asList(fullCompleted, differentialCompleted);
        assertTrue(validatePairs(cases).isEmpty());

        File csv = (File) field(differentialPaths, "evaluationCsvFile");
        String validCsv = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        write(csv, validCsv.replace(OUTPUT_HASH, INPUT_HASH));
        assertTrue(validatePairs(cases).toString().contains("ds_controller_output_sha256"));
        write(csv, validCsv.replace(INPUT_HASH, OUTPUT_HASH));
        assertTrue(validatePairs(cases).toString().contains("ds_prepared_input_sha256"));
        writeArtifacts(differentialPaths, "UNREALIZABLE");
        assertTrue(validatePairs(cases).toString().contains("ds_status"));
        assertTrue(validatePairs(Arrays.asList(fullCompleted)).isEmpty());
        setField(differentialResult, "status", "MEASUREMENT_FAILED");
        assertTrue(validatePairs(cases).isEmpty());
    }

    @Test
    public void acceptsFromScratchAndPassesExplicitUpdateScopeOnlyToDedicatedChild() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "from_scratch", true,
                "    measurementScope: update\n");
        assertEquals("FROM_SCRATCH", config.cases.get(0).method);
        assertEquals("update", config.cases.get(0).effectiveMeasurementScope());
        List<String> command = command(config, paths(config));
        assertEquals("FROM_SCRATCH", command.get(command.indexOf("--method") + 1));
        assertEquals("update", command.get(command.indexOf("--measurement-scope") + 1));
        assertRejected("", "differential-synthesis", "FULL", true,
                "    measurementScope: wrong\n", "generation or update");
        assertRejected("", null, "Traditional", false,
                "    measurementScope: generation\n", "measurementScope requires");
    }

    @Test
    public void rotatesThreeMethodsWithinEachScopeAndPermitsSubsetOrSingleMethod() throws Exception {
        ExperimentConfig config = loadConfig("alternateMethodOrderByRun: true\n",
                "differential-synthesis", "FULL", true, "");
        ExperimentCase full = config.cases.get(0);
        ExperimentCase differential = otherMethod(full, "DIFFERENTIAL", "generation", "diff");
        ExperimentCase scratch = otherMethod(full, "FROM_SCRATCH", "generation", "scratch");
        ExperimentCase updateFull = otherMethod(full, "FULL", "update", "update_full");
        ExperimentCase updateDifferential = otherMethod(full, "DIFFERENTIAL", "update", "update_diff");
        ExperimentCase updateScratch = otherMethod(full, "FROM_SCRATCH", "update", "update_scratch");
        config.cases.addAll(Arrays.asList(differential, scratch, updateFull, updateDifferential, updateScratch));
        assertEquals(Arrays.asList(full, differential, scratch, updateFull, updateDifferential, updateScratch),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 1));
        assertEquals(Arrays.asList(differential, scratch, full, updateDifferential, updateScratch, updateFull),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 2));
        assertEquals(Arrays.asList(scratch, full, differential, updateScratch, updateFull, updateDifferential),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 3));
        config.cases.clear();
        config.cases.addAll(Arrays.asList(scratch, differential));
        assertEquals(Arrays.asList(differential, scratch),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 1));
        assertEquals(Arrays.asList(scratch, differential),
                ExperimentCampaignProvenance.orderedCasesForRun(config, 2));
        config.cases.clear();
        config.cases.add(scratch);
        assertEquals(Arrays.asList(scratch), ExperimentCampaignProvenance.orderedCasesForRun(config, 3));
    }

    @Test
    public void negativeUpdateOutcomesStillRequireEnabledMeasurement() throws Exception {
        for (String status : new String[] {"BASE_UNREALIZABLE", "UNREALIZABLE", "SIMULATION_INCOMPATIBLE"}) {
            ExperimentConfig config = loadConfig("validateMemoryQuality: true\n",
                    "differential-synthesis", "FROM_SCRATCH", true, "    measurementScope: update\n");
            Object paths = paths(config);
            writeArtifacts(paths, status, "update");
            assertTrue(validate(config, paths).isEmpty());
            Object failedMeasurement = newResult(true, true);
            appendMemory(config, paths, failedMeasurement);
            assertEquals("MEASUREMENT_FAILED", field(failedMeasurement, "status"));
            assertEquals("true", metrics(paths).get("batch_memory_quality_validation_applicable"));

            writeArtifacts(paths, status, "update");
            Object disabledMeasurement = newResult(false, false);
            appendMemory(config, paths, disabledMeasurement);
            assertEquals("SUCCESS", field(disabledMeasurement, "status"));
            assertTrue(validateCampaign(config, paths, disabledMeasurement).isEmpty());
        }
    }

    @Test
    public void scopeAndExecutedAnalysisTimingMustMatchSchemaTwo() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "FROM_SCRATCH", true,
                "    measurementScope: update\n");
        Object paths = paths(config);
        writeArtifacts(paths, "UNREALIZABLE", "update");
        assertTrue(validate(config, paths).isEmpty());
        File csv = (File) field(paths, "evaluationCsvFile");
        String valid = new String(Files.readAllBytes(csv.toPath()), StandardCharsets.UTF_8);
        write(csv, valid.replace(",ds_update_pipeline_ns,ds_update_pipeline_ns,300,",
                ",ds_update_pipeline_ns,ds_update_pipeline_ns,,"));
        assertTrue(validate(config, paths).toString().contains("ds_update_pipeline_ns"));
        write(csv, valid.replace(",ds_game_update_ns,ds_game_update_ns,100,",
                ",ds_game_update_ns,ds_game_update_ns,-1,"));
        assertTrue(validate(config, paths).toString().contains("ds_game_update_ns"));
        write(csv, valid.replace(",ds_analysis_ns,ds_analysis_ns,100,",
                ",ds_analysis_ns,ds_analysis_ns,,"));
        assertTrue(validate(config, paths).toString().contains("ds_analysis_ns"));
        write(csv, valid.replace(",ds_schema_version,ds_schema_version,2,",
                ",ds_schema_version,ds_schema_version,1,"));
        assertTrue(validate(config, paths).toString().contains("ds_schema_version"));
        writeArtifacts(paths, "UNREALIZABLE", "generation");
        assertTrue(validate(config, paths).toString().contains("ds_measurement_scope"));
    }

    @Test
    public void comparesFromScratchWithEitherMethodButSeparatesMeasurementScopes() throws Exception {
        ExperimentConfig config = loadConfig("", "differential-synthesis", "FULL", true, "");
        ExperimentCase full = config.cases.get(0);
        ExperimentCase differential = otherMethod(full, "DIFFERENTIAL", "generation", "diff");
        ExperimentCase scratch = otherMethod(full, "FROM_SCRATCH", "generation", "scratch");
        Object fullPaths = paths(config, full);
        Object differentialPaths = paths(config, differential);
        Object scratchPaths = paths(config, scratch);
        writeArtifacts(fullPaths, "SUCCESS");
        writeArtifacts(differentialPaths, "SUCCESS");
        writeArtifacts(scratchPaths, "SUCCESS");
        Object fullCase = completed(full, fullPaths, newResult(false, false));
        Object differentialCase = completed(differential, differentialPaths, newResult(false, false));
        Object scratchCase = completed(scratch, scratchPaths, newResult(false, false));
        assertTrue(validatePairs(Arrays.asList(fullCase, differentialCase, scratchCase)).isEmpty());
        writeArtifacts(scratchPaths, "UNREALIZABLE");
        assertTrue(validatePairs(Arrays.asList(fullCase, scratchCase)).toString().contains("ds_status"));
        assertTrue(validatePairs(Arrays.asList(differentialCase, scratchCase)).toString().contains("ds_status"));
        scratch.measurementScope = "update";
        writeArtifacts(scratchPaths, "UNREALIZABLE", "update");
        assertTrue(validatePairs(Arrays.asList(fullCase, differentialCase, scratchCase)).isEmpty());
    }

    private static ExperimentCase otherMethod(ExperimentCase original, String method, String scope, String id) {
        ExperimentCase copy = new ExperimentCase();
        copy.id = id;
        copy.example = original.example;
        copy.mode = original.mode;
        copy.method = method;
        copy.measurementScope = scope;
        copy.variant = original.variant;
        copy.lts = original.lts;
        copy.scenario = original.scenario;
        copy.target = original.target;
        return copy;
    }

    private ExperimentConfig loadConfig(String configOptions, String mode, String method,
                                        boolean scenario, String caseOptions) throws Exception {
        File directory = temporaryFolder.newFolder();
        File input = new File(directory, "input.lts");
        File scenarioFile = new File(directory, "scenario.yaml");
        write(input, "P=(a->P).\n||GAME=P.\n");
        write(scenarioFile, "scenario\n");
        File configFile = new File(directory, "config.yaml");
        write(configFile, "outputDir: " + new File(directory, "result") + "\nnotifyOn: never\n"
                + configOptions + "cases:\n  - id: case_one\n    example: Example\n"
                + (mode == null ? "" : "    mode: " + mode + "\n")
                + "    method: " + method + "\n    lts: " + input + "\n    target: GAME\n"
                + (scenario ? "    scenario: " + scenarioFile + "\n" : "") + caseOptions);
        return ExperimentConfig.load(configFile);
    }

    private void assertRejected(String options, String mode, String method, boolean scenario,
                                String caseOptions, String expectedMessage) throws Exception {
        try {
            loadConfig(options, mode, method, scenario, caseOptions);
            fail("Expected invalid differential configuration to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private Object paths(ExperimentConfig config) throws Exception {
        return paths(config, config.cases.get(0));
    }

    private Object paths(ExperimentConfig config, ExperimentCase experimentCase) throws Exception {
        return invokeStatic("create", nested("CasePaths"),
                new Class<?>[] {File.class, ExperimentCase.class, boolean.class, int.class, int.class, String.class},
                config.outputDir, experimentCase, false, 1, 1, null);
    }

    @SuppressWarnings("unchecked")
    private List<String> command(ExperimentConfig config, Object paths) throws Exception {
        return (List<String>) invokeStatic("buildChildCommand", BatchExperimentRunner.class,
                new Class<?>[] {ExperimentConfig.class, ExperimentCase.class, nested("CasePaths")},
                config, config.cases.get(0), paths);
    }

    private void writeArtifacts(Object paths, String status) throws Exception {
        writeArtifacts(paths, status, "generation");
    }

    private void writeArtifacts(Object paths, String status, String scope) throws Exception {
        invoke(paths, "ensureDirectories", new Class<?>[0]);
        write((File) field(paths, "outputFile"), "Status: " + status + "\n");
        write((File) field(paths, "transitionsFile"), "Status: " + status + "\n");
        write((File) field(paths, "executionMetadataFile"), "{\"matches\": true}\n");
        write((File) field(paths, "stdoutFile"), "");
        write((File) field(paths, "stderrFile"), "");
        File csv = (File) field(paths, "evaluationCsvFile");
        Files.deleteIfExists(csv.toPath());
        List<ExternalDataMetric> values = new ArrayList<>();
        add(values, "mode", "Differential synthesis");
        add(values, "result", "SUCCESS");
        add(values, "failure_reason", "");
        add(values, "ds_status", status);
        add(values, "ds_schema_version", "2");
        add(values, "ds_measurement_scope", scope);
        add(values, "ds_prepared_input_sha256", INPUT_HASH);
        add(values, "ds_controller_output_sha256", OUTPUT_HASH);
        add(values, "ds_analysis_ns", "100");
        add(values, "ds_game_update_ns", "100");
        add(values, "ds_compatibility_ns", "100");
        add(values, "ds_generation_ns", "SUCCESS".equals(status) ? "100" : "");
        add(values, "ds_export_ns", "100");
        add(values, "ds_update_pipeline_ns", "update".equals(scope) ? "300" : "");
        add(values, "ds_memory_scope", "update".equals(scope) ? "update_with_old_inputs"
                : ("SUCCESS".equals(status) ? "generation_with_prepared_inputs" : "not_run"));
        add(values, "heap_memory_sampling_available", "false");
        UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(csv,
                "Differential synthesis", "SUCCESS", "", values);
    }

    private static void add(List<ExternalDataMetric> values, String key, String value) {
        values.add(new ExternalDataMetric(key, "test", key, value, "text", ""));
    }

    @SuppressWarnings("unchecked")
    private List<String> validate(ExperimentConfig config, Object paths) throws Exception {
        return (List<String>) invokeStatic("validateCaseArtifactsBeforeFinalize", BatchExperimentRunner.class,
                new Class<?>[] {ExperimentCase.class, nested("CasePaths")}, config.cases.get(0), paths);
    }

    private String statusFromExitCode(Object paths) throws Exception {
        return (String) invokeStatic("statusFromExitCode", BatchExperimentRunner.class,
                new Class<?>[] {int.class, nested("CasePaths")}, 0, paths);
    }

    private Object newResult(boolean heap, boolean rss) throws Exception {
        Constructor<?> constructor = nested("CaseResult").getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        setField(result, "status", "SUCCESS");
        setField(result, "heapSamplingEnabled", heap);
        setField(result, "rssSamplingEnabled", rss);
        return result;
    }

    private void appendMemory(ExperimentConfig config, Object paths, Object result) throws Exception {
        invokeStatic("appendProcessMemoryMetrics", BatchExperimentRunner.class,
                new Class<?>[] {ExperimentConfig.class, nested("CasePaths"), ExperimentCase.class, nested("CaseResult")},
                config, paths, config.cases.get(0), result);
    }

    @SuppressWarnings("unchecked")
    private List<String> validateCampaign(ExperimentConfig config, Object paths, Object result) throws Exception {
        invokeStatic("writeMetaJson", BatchExperimentRunner.class,
                new Class<?>[] {ExperimentConfig.class, ExperimentCase.class, nested("CasePaths"), nested("CaseResult")},
                config, config.cases.get(0), paths, result);
        Object completed = completed(config.cases.get(0), paths, result);
        return (List<String>) invokeStatic("validateCampaignArtifacts", BatchExperimentRunner.class,
                new Class<?>[] {ExperimentConfig.class, List.class}, config, Arrays.asList(completed));
    }

    private static Object completed(ExperimentCase experimentCase, Object paths, Object result) throws Exception {
        Constructor<?> constructor = nested("CompletedCase").getDeclaredConstructor(
                ExperimentCase.class, nested("CasePaths"), nested("CaseResult"));
        constructor.setAccessible(true);
        return constructor.newInstance(experimentCase, paths, result);
    }

    private static List<String> validatePairs(List<Object> cases) throws Exception {
        List<String> errors = new ArrayList<>();
        invokeStatic("validateDifferentialPairs", BatchExperimentRunner.class,
                new Class<?>[] {List.class, List.class}, errors, cases);
        return errors;
    }

    private Map<String, String> metrics(Object paths) throws Exception {
        String csv = new String(Files.readAllBytes(((File) field(paths, "evaluationCsvFile")).toPath()),
                StandardCharsets.UTF_8);
        List<List<String>> records = BatchExperimentRunner.parseCsvRecords(csv);
        int keyColumn = records.get(0).indexOf("metric_key");
        int valueColumn = records.get(0).indexOf("value");
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < records.size(); i++) {
            values.put(records.get(i).get(keyColumn), records.get(i).get(valueColumn));
        }
        return values;
    }

    private static Class<?> nested(String name) throws ClassNotFoundException {
        return Class.forName(BatchExperimentRunner.class.getName() + "$" + name);
    }

    private static Object invokeStatic(String name, Class<?> owner, Class<?>[] types, Object... args)
            throws Exception {
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Object invoke(Object owner, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(owner, args);
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void setField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void write(File file, String contents) throws Exception {
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
    }
}
