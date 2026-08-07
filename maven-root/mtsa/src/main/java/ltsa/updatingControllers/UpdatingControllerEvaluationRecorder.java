package ltsa.updatingControllers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ltsa.MultiCore.ComputerOptions;
import ltsa.lts.LTSOutput;
import ltsa.ui.EnvConfiguration;
import ltsa.updatingControllers.memory.RunHeapMemorySampler;

/**
 * 更新コントローラ合成の評価値を 1 回の合成単位で集約する recorder。
 */
public final class UpdatingControllerEvaluationRecorder {

    /**
     * BatchExperimentRunner sets this in its child JVM.  Until the parent has
     * atomically added process-memory metrics and canonicalized the outcome,
     * a child-produced CSV must not look like a completed successful run.
     */
    public static final String PARENT_FINALIZATION_REQUIRED_PROPERTY =
            "mtsa.evaluation.parentFinalizationRequired";
    public static final String PARENT_FINALIZATION_PENDING =
            "PARENT_FINALIZATION_PENDING";
    /**
     * Opt-in switch for fine-grained heap checkpoints used only while
     * diagnosing the Stepwise/Traditional memory difference.
     */
    public static final String PHASE_MEMORY_DIAGNOSTICS_PROPERTY =
            "mtsa.evaluation.phaseMemoryDiagnostics";

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String LEGACY_EVALUATION_ENABLED_PROPERTY = "updating.controller.evaluation.enabled";
    private static final String PRINT_DETAILED_REPORT_PROPERTY = "updating.controller.evaluation.printDetailedReport";
    private static final String CSV_FILE_PROPERTY = "mtsa.evaluation.csvFile";
    private static final String LEGACY_CSV_FILE_PROPERTY = "updating.controller.evaluation.csvFile";
    private static final String CSV_DIR_PROPERTY = "mtsa.evaluation.csvDir";
    private static final String DEFAULT_CSV_DIR = "Experiment/evaluation_csv";
    private static final String DATA_CSV_HEADER = "mode,result,failure_reason,section,metric_key,metric_label,value,unit,formula,"
            + "metric_schema_version,metric_description_id,"
            + "section_readable_ja,metric_readable_ja,metric_category,artifact,phase,action,event,stat";
    private static final int DATA_CSV_COLUMN_COUNT = 19;
    private static final String METRIC_SCHEMA_VERSION = "2026-08-04-evaluation-v4";

    public enum ResultStatus {
        NOT_RECORDED,
        SUCCESS,
        GOAL_NOT_REACHABLE,
        NOT_CONTROLLABLE,
        OUT_OF_MEMORY,
        EXCEPTION,
        UNKNOWN_FAILURE
    }

    private static final Map<String, List<String>> sections = new LinkedHashMap<>();
    private static final Map<String, ActiveTimer> activeTimers = new LinkedHashMap<>();
    private static final Map<String, LineRef> lineRefs = new LinkedHashMap<>();
    private static final Map<String, Long> timeMillisByKey = new LinkedHashMap<>();
    private static final Map<String, DataMetric> dataMetrics = new LinkedHashMap<>();
    // dataMetrics intentionally keeps the latest value per stable key. Peak
    // computation needs every state-space sample, including repeated labels.
    private static final List<StateSpaceObservation> stateSpaceObservations = new ArrayList<>();
    private static final Map<String, CountScope> countScopes = new LinkedHashMap<>();
    private static final List<String> activeCountScopes = new ArrayList<>();

    private static String mode = "未記録";
    private static ResultStatus resultStatus = ResultStatus.NOT_RECORDED;
    private static String failureMessage = "";
    private static boolean printed = false;
    private static long stateSpaceCountOverheadMillis = 0;
    private static long stateSpaceCountOverheadObservedMillis = 0;
    private static long stateSpaceCountOverheadPostObservedMillis = 0;
    private static boolean observedTimeWindowClosed = false;
    private static long oldControllerStates = -1;
    private static long beginUpdateReferenceStates = -1;
    private static long traditionalMetaStates = -1;
    private static long traditionalMetaTransitions = -1;
    private static long traditionalPrunedStates = -1;
    private static long traditionalPrunedTransitions = -1;
    private static long traditionalFinalStates = -1;
    private static long traditionalFinalTransitions = -1;
    private static long otfExploredStates = -1;
    private static long otfExploredTransitions = -1;
    private static long evaluationHeaderOutputMillis = 0;
    private static long evaluationDetailedReportOutputMillis = 0;
    private static long evaluationSummaryOutputMillis = 0;
    private static long evaluationDataCsvOutputMillis = 0;
    private static long evaluationOutputOverheadMillis = 0;
    private static long memoryBaselineBytes = -1;
    private static long previousMemoryCheckpointBytes = -1;
    private static volatile boolean phaseMemoryDiagnosticsEnabled = false;
    private static long phaseMemoryDiagnosticsStartNanos = -1;
    private static long phaseMemoryDiagnosticsSequence = 0;
    private static String currentSummarySection = "";
    private static String otfExecutionMode = "";
    private static String autoCsvFile = "";
    private static long autoCsvSequence = 0;

    private UpdatingControllerEvaluationRecorder() {
    }

    /** Stable schema identifier written to every evaluation CSV row. */
    public static String getMetricSchemaVersion() {
        return METRIC_SCHEMA_VERSION;
    }

    public static boolean isEnabled() {
        String value = System.getProperty(EVALUATION_ENABLED_PROPERTY);
        if (value == null) {
            value = System.getProperty(LEGACY_EVALUATION_ENABLED_PROPERTY);
        }
        return value == null || Boolean.parseBoolean(value);
    }

    public static synchronized void reset() {
        sections.clear();
        activeTimers.clear();
        lineRefs.clear();
        timeMillisByKey.clear();
        dataMetrics.clear();
        stateSpaceObservations.clear();
        countScopes.clear();
        activeCountScopes.clear();
        mode = "未記録";
        resultStatus = ResultStatus.NOT_RECORDED;
        failureMessage = "";
        printed = false;
        stateSpaceCountOverheadMillis = 0;
        stateSpaceCountOverheadObservedMillis = 0;
        stateSpaceCountOverheadPostObservedMillis = 0;
        observedTimeWindowClosed = false;
        oldControllerStates = -1;
        beginUpdateReferenceStates = -1;
        traditionalMetaStates = -1;
        traditionalMetaTransitions = -1;
        traditionalPrunedStates = -1;
        traditionalPrunedTransitions = -1;
        traditionalFinalStates = -1;
        traditionalFinalTransitions = -1;
        otfExploredStates = -1;
        otfExploredTransitions = -1;
        evaluationHeaderOutputMillis = 0;
        evaluationDetailedReportOutputMillis = 0;
        evaluationSummaryOutputMillis = 0;
        evaluationDataCsvOutputMillis = 0;
        evaluationOutputOverheadMillis = 0;
        memoryBaselineBytes = -1;
        previousMemoryCheckpointBytes = -1;
        phaseMemoryDiagnosticsEnabled = isEnabled()
                && Boolean.parseBoolean(System.getProperty(
                        PHASE_MEMORY_DIAGNOSTICS_PROPERTY,
                        "false"));
        phaseMemoryDiagnosticsStartNanos = phaseMemoryDiagnosticsEnabled
                ? System.nanoTime()
                : -1;
        phaseMemoryDiagnosticsSequence = 0;
        currentSummarySection = "";
        otfExecutionMode = "";
        autoCsvFile = "";
    }

    public static synchronized void setMode(String value) {
        if (!isEnabled()) {
            return;
        }
        if (value != null && !value.isEmpty()) {
            mode = value;
        }
    }

    public static synchronized void recordRunEnvironmentMetadata() {
        if (!isEnabled()) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        recordDataMetric("run_allowed_threads", "Run", "allowed threads",
                Long.toString(ComputerOptions.getInstance().getAllowedThreads()), "threads");
        recordDataMetric("run_available_processors", "Run", "available processors",
                Long.toString(runtime.availableProcessors()), "processors");
        recordDataMetric("run_jvm_max_heap", "Run", "JVM max heap", bytesToByteText(runtime.maxMemory()), "B");
        recordDataMetric("run_java_version", "Run", "Java version",
                System.getProperty("java.version", ""), "text");
        recordDataMetric("run_evaluation_profile", "Run", "evaluation profile",
                System.getProperty("updating.controller.evaluation.profile", "paper"), "text");
        recordDataMetric("run_java_vm_name", "Run", "Java VM name",
                System.getProperty("java.vm.name", ""), "text");
        recordDataMetric("run_os_name", "Run", "OS name",
                System.getProperty("os.name", ""), "text");
        recordDataMetric("run_os_arch", "Run", "OS arch",
                System.getProperty("os.arch", ""), "text");
    }

    public static synchronized void closeObservedTimeWindow() {
        if (!isEnabled()) {
            return;
        }
        observedTimeWindowClosed = true;
    }

    public static synchronized void setOtfExecutionMode(String value) {
        if (!isEnabled()) {
            return;
        }
        if (value != null && !value.isEmpty()) {
            otfExecutionMode = value;
        }
    }

    public static synchronized void markSuccess() {
        if (!isEnabled()) {
            return;
        }
        if (!isFailureStatus(resultStatus)) {
            resultStatus = ResultStatus.SUCCESS;
            failureMessage = "";
        }
    }

    public static synchronized void recordFailure(ResultStatus status, String message) {
        if (!isEnabled()) {
            return;
        }
        if (status == null) {
            status = ResultStatus.UNKNOWN_FAILURE;
        }
        resultStatus = status;
        failureMessage = message == null ? "" : message;
    }

    public static synchronized void recordFailureIfAbsent(ResultStatus status, String message) {
        if (!isEnabled()) {
            return;
        }
        if (!isFailureStatus(resultStatus)) {
            recordFailure(status, message);
        }
    }

    public static synchronized void recordTime(String section, String label, long millis) {
        if (!isEnabled()) {
            return;
        }
        putOrReplaceTime(section, label, millis, "");
    }

    public static synchronized void addTime(String section, String label, long millis) {
        if (!isEnabled()) {
            return;
        }
        long existing = optionalTime(section, label);
        putOrReplaceTime(section, label, existing + Math.max(0, millis), "");
    }

    public static synchronized void beginFailureTimer(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        activeTimers.put(timerKey(section, label), new ActiveTimer(section, label, System.nanoTime()));
        putOrReplace(section, label, label + " : 計測中");
    }

    public static synchronized void endFailureTimer(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        ActiveTimer timer = activeTimers.remove(timerKey(section, label));
        if (timer != null) {
            putOrReplaceTime(timer.section, timer.label,
                    elapsedMillis(timer.startNanos),
                    "");
        }
    }

    public static synchronized void recordNanoTime(String section, String label, long nanos) {
        if (!isEnabled()) {
            return;
        }
        add(section, label + " : " + formatNanos(nanos));
        recordDataMetric(section, label, nanosToMillisText(nanos), "ms");
    }

    public static synchronized void recordAverageNanoTime(String section, String label, long nanos, long count) {
        if (!isEnabled()) {
            return;
        }
        if (count <= 0) {
            add(section, label + " : 0.000 ms / call (0 calls)");
            recordDataMetric(section, label, "0.000", "ms/call");
            return;
        }
        double averageMillis = nanos / 1_000_000.0 / count;
        add(section, label + " : "
                + String.format(Locale.ROOT, "%.3f ms / call", averageMillis)
                + " (" + count + " calls)");
        recordDataMetric(section, label, String.format(Locale.ROOT, "%.3f", averageMillis), "ms/call");
    }

    public static synchronized void recordCount(String section, String label, long count, String unit) {
        if (!isEnabled()) {
            return;
        }
        add(section, label + " : " + count + " " + unit);
        recordDataMetric(section, label, Long.toString(count), unit == null ? "count" : unit);
    }

    public static synchronized void recordText(String section, String label, String value, String unit) {
        if (!isEnabled()) {
            return;
        }
        String safeValue = value == null ? "" : value;
        add(section, label + " : " + safeValue);
        recordDataMetric(section, label, safeValue, unit == null ? "text" : unit);
    }

    public static synchronized void recordDouble(String section, String label, double value, String unit) {
        if (!isEnabled()) {
            return;
        }
        String formatted = formatDouble(value);
        add(section, label + " : " + formatted + (unit == null || unit.isEmpty() ? "" : " " + unit));
        recordDataMetric(section, label, formatted, unit == null ? "number" : unit);
    }

    /**
     * Records a metric under a caller-supplied stable key.  This is reserved
     * for repeated/indexed evaluation stages whose labels cannot be covered by
     * the static known-key table without falling back to hash-derived keys.
     */
    public static synchronized void recordStableMetric(
            String metricKey,
            String section,
            String label,
            String value,
            String unit,
            String formula) {
        if (!isEnabled()) {
            return;
        }
        if (metricKey == null || metricKey.trim().isEmpty()) {
            throw new IllegalArgumentException("metricKey must not be empty");
        }
        String safeSection = section == null ? "" : section;
        String safeLabel = label == null ? "" : label;
        String safeValue = value == null ? "" : value;
        String safeUnit = unit == null ? "text" : unit;
        add(safeSection, safeLabel + " : " + safeValue
                + (safeUnit.isEmpty() ? "" : " " + safeUnit));
        recordDataMetricWithFormula(
                metricKey.trim(),
                safeSection,
                safeLabel,
                safeValue,
                safeUnit,
                formula == null ? "" : formula);
    }

    /** Adds a non-negative value to a fixed-key diagnostic metric. */
    public static synchronized void addStableLongMetric(
            String metricKey,
            String section,
            String label,
            long delta,
            String unit,
            String formula) {
        if (!isEnabled()) {
            return;
        }
        if (metricKey == null || metricKey.trim().isEmpty()) {
            throw new IllegalArgumentException("metricKey must not be empty");
        }
        long safeDelta = Math.max(0L, delta);
        Long previous = dataMetricLong(metricKey.trim());
        long safePrevious = previous == null ? 0L : Math.max(0L, previous.longValue());
        long value = safePrevious > Long.MAX_VALUE - safeDelta
                ? Long.MAX_VALUE
                : safePrevious + safeDelta;
        String safeSection = section == null ? "" : section;
        String safeLabel = label == null ? "" : label;
        String safeUnit = unit == null ? "number" : unit;
        putOrReplace(
                safeSection,
                safeLabel,
                safeLabel + " : " + value + (safeUnit.isEmpty() ? "" : " " + safeUnit));
        recordDataMetricWithFormula(
                metricKey.trim(),
                safeSection,
                safeLabel,
                Long.toString(value),
                safeUnit,
                formula == null ? "" : formula);
    }

    /** Records a fixed-key evaluation CountTime and includes it in overhead totals. */
    public static synchronized void recordStableEvaluationCountTime(
            String metricKey,
            String section,
            String label,
            long countTimeMillis,
            String formula) {
        if (!isEnabled()) {
            return;
        }
        long safeCountTime = Math.max(0L, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        recordStableMetric(
                metricKey,
                section,
                label,
                Long.toString(safeCountTime),
                "ms",
                formula);
    }

    public static synchronized void beginCountScope(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        String key = timerKey(section, label);
        CountScope scope = countScopes.get(key);
        if (scope == null) {
            scope = new CountScope(section, label, metricKey(section, label));
            countScopes.put(key, scope);
        }
        activeCountScopes.add(key);
    }

    public static synchronized void endCountScope(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        String key = timerKey(section, label);
        for (int i = activeCountScopes.size() - 1; i >= 0; i--) {
            if (activeCountScopes.get(i).equals(key)) {
                activeCountScopes.remove(i);
                return;
            }
        }
    }

    private static void addStateSpaceCountOverhead(long countTimeMillis) {
        if (!isEnabled()) {
            return;
        }
        long safeCountTime = Math.max(0, countTimeMillis);
        stateSpaceCountOverheadMillis += safeCountTime;
        if (observedTimeWindowClosed) {
            stateSpaceCountOverheadPostObservedMillis += safeCountTime;
        } else {
            stateSpaceCountOverheadObservedMillis += safeCountTime;
        }
        if (safeCountTime == 0 || activeCountScopes.isEmpty()) {
            return;
        }
        for (String key : activeCountScopes) {
            CountScope scope = countScopes.get(key);
            if (scope != null) {
                scope.countTimeMillis += safeCountTime;
            }
        }
    }

    private static void refreshStateSpaceCountOverheadDataMetrics() {
        recordDataMetric("state_transition_count_overhead_total", "Evaluation Summary",
                "State/transition count overhead total", Long.toString(stateSpaceCountOverheadMillis), "ms");
        recordDataMetric("state_transition_count_overhead_in_observed_time", "Evaluation Summary",
                "State/transition count overhead in observed time",
                Long.toString(stateSpaceCountOverheadObservedMillis), "ms");
        recordDataMetric("state_transition_count_overhead_after_observed_time", "Evaluation Summary",
                "State/transition count overhead after observed time",
                Long.toString(stateSpaceCountOverheadPostObservedMillis), "ms");

        recordDataMetricWithFormula(
                "comparison_count_overhead_time",
                "比較用時間集計",
                "評価用カウント時間（状態数・遷移数）",
                Long.toString(stateSpaceCountOverheadObservedMillis),
                "ms",
                "実測総時間に含まれる状態数・遷移数 CountTime。合成本来の処理ではない評価用オーバーヘッドで、比較用時間から差し引く。");
        recordDataMetricWithFormula(
                "comparison_count_overhead_total",
                "比較用時間集計",
                "評価用カウント時間（合計）",
                Long.toString(stateSpaceCountOverheadMillis),
                "ms",
                "実測総時間内と実測総時間外の状態数・遷移数 CountTime の合計。診断用の総量であり、実測総時間から丸ごとは差し引かない。");
        recordDataMetricWithFormula(
                "comparison_count_overhead_after_observed_time",
                "比較用時間集計",
                "評価用カウント時間（実測時間外）",
                Long.toString(stateSpaceCountOverheadPostObservedMillis),
                "ms",
                "実測総時間を確定した後に、出力 controller や post-synthesis 診断のために数えた CountTime。主比較用時間からは差し引かない。");

        recordDataMetricWithFormula(
                "Evaluation Summary / 全体",
                "カウントによるオーバーヘッド（実測時間内）",
                Long.toString(stateSpaceCountOverheadObservedMillis),
                "ms",
                "実測総時間に含まれる状態数・遷移数 CountTime。比較用時間から差し引く対象。");
        recordDataMetricWithFormula(
                "Evaluation Summary / 全体",
                "カウントによるオーバーヘッド（実測時間外）",
                Long.toString(stateSpaceCountOverheadPostObservedMillis),
                "ms",
                "実測総時間確定後に出力 controller や post-synthesis 診断のために数えた CountTime。比較用時間からは差し引かない。");
        recordDataMetricWithFormula(
                "Evaluation Summary / 全体",
                "カウントによるオーバーヘッド（合計）",
                Long.toString(stateSpaceCountOverheadMillis),
                "ms",
                "実測時間内と実測時間外の状態数・遷移数 CountTime の合計。");
    }

    public static synchronized void recordEvaluationCountTime(
            String section,
            String label,
            long countTimeMillis,
            String description) {
        if (!isEnabled()) {
            return;
        }
        long safeCountTime = Math.max(0, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        add(section, label + " : " + safeCountTime + " ms");
        if (description != null && !description.isEmpty()) {
            add(section, "  説明: " + description);
        }
        recordDataMetric(metricKey(section, label), section, label, Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordStateSpace(
            String section, String label, long states, long transitions, long countTimeMillis) {
        recordStateSpace(section, label, states, transitions, countTimeMillis, "");
    }

    public static synchronized void recordStateSpace(
            String section, String label, long states, long transitions, long countTimeMillis, String description) {
        if (!isEnabled()) {
            return;
        }
        addStateSpaceCountOverhead(countTimeMillis);
        add(section, label + " States: " + states
                + ", Transitions: " + transitions
                + ", CountTime: " + countTimeMillis + " ms");
        if (description != null && !description.isEmpty()) {
            add(section, "  説明: " + description);
        }
        stateSpaceObservations.add(new StateSpaceObservation(
                section,
                label,
                states,
                transitions));
        String baseKey = metricKey(section, label);
        if ("Stepwise Delayed DUC 最大状態数と遷移数".equals(section)) {
            // Preserve every raw Stepwise observation for CSV auditability,
            // while the stable base key below keeps its historical latest-value
            // behaviour for existing consumers.
            String observationBaseKey = baseKey + "_observation_"
                    + stateSpaceObservations.size();
            recordDataMetric(
                    observationBaseKey + "_states",
                    section,
                    label + " / States",
                    Long.toString(states),
                    "states");
            recordDataMetric(
                    observationBaseKey + "_transitions",
                    section,
                    label + " / Transitions",
                    Long.toString(transitions),
                    "transitions");
            recordDataMetric(
                    observationBaseKey + "_count_time",
                    section,
                    label + " / CountTime",
                    Long.toString(countTimeMillis),
                    "ms");
        }
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_transitions", section, label + " / Transitions", Long.toString(transitions), "transitions");
        recordDataMetric(baseKey + "_count_time", section, label + " / CountTime", Long.toString(countTimeMillis), "ms");
        recordStableStateSpaceAlias(section, label, states, transitions, countTimeMillis);
        captureReferenceStateSpace(section, label, states, transitions);
    }

    public static synchronized void recordFinalGrInputStateSpace(
            String methodKey,
            String methodLabel,
            long states,
            long transitions,
            String sourceStage) {
        if (!isEnabled()) {
            return;
        }
        String safeMethodKey = methodKey == null || methodKey.isEmpty()
                ? "duc"
                : metricToken(methodKey);
        String safeMethodLabel = methodLabel == null || methodLabel.isEmpty()
                ? "DUC"
                : methodLabel;
        String section = "Final GR(1) Input";
        add(section, safeMethodLabel + " final safety environment States: " + states
                + ", Transitions: " + transitions
                + (sourceStage == null || sourceStage.isEmpty() ? "" : ", SourceStage: " + sourceStage));
        recordDataMetric(safeMethodKey + "_final_gr_input_states",
                section,
                safeMethodLabel + " final safety environment / States",
                Long.toString(states),
                "states");
        recordDataMetric(safeMethodKey + "_final_gr_input_transitions",
                section,
                safeMethodLabel + " final safety environment / Transitions",
                Long.toString(transitions),
                "transitions");
        // Symmetric fixed aliases used by the Traditional/Stepwise campaign
        // validator and comparison scripts.  Both aliases describe exactly
        // the same final GR(1) input as the explicit keys above.
        recordDataMetric(safeMethodKey + "_final_states",
                section,
                safeMethodLabel + " final GR(1) input / States",
                Long.toString(states),
                "states");
        recordDataMetric(safeMethodKey + "_final_transitions",
                section,
                safeMethodLabel + " final GR(1) input / Transitions",
                Long.toString(transitions),
                "transitions");
        if (sourceStage != null && !sourceStage.isEmpty()) {
            recordDataMetric(safeMethodKey + "_final_gr_input_source_stage",
                    section,
                    safeMethodLabel + " final safety environment / source stage",
                    sourceStage,
                    "text");
        }
    }

    public static synchronized void recordGrGameStateSpace(
            String section,
            String label,
            long states,
            long controllableSuccessors,
            long uncontrollableSuccessors,
            long countTimeMillis) {
        if (!isEnabled()) {
            return;
        }
        long safeCountTime = Math.max(0, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        long totalSuccessors = controllableSuccessors + uncontrollableSuccessors;
        add(section, label + ": states=" + states
                + ", controllableSuccessors=" + controllableSuccessors
                + ", uncontrollableSuccessors=" + uncontrollableSuccessors
                + ", totalSuccessors=" + totalSuccessors
                + ", CountTime=" + safeCountTime + " ms");
        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_states", section, label + " / States",
                Long.toString(states), "states");
        recordDataMetric(baseKey + "_controllable_successors", section,
                label + " / controllable successors",
                Long.toString(controllableSuccessors), "successors");
        recordDataMetric(baseKey + "_uncontrollable_successors", section,
                label + " / uncontrollable successors",
                Long.toString(uncontrollableSuccessors), "successors");
        recordDataMetric(baseKey + "_total_successors", section,
                label + " / total successors",
                Long.toString(totalSuccessors), "successors");
        recordDataMetric(baseKey + "_count_time", section,
                label + " / CountTime",
                Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordUpdatePhaseCountTimeTotal(
            String section,
            String artifactLabel,
            long totalCountTimeMillis) {
        add(section, artifactLabel + " / update phase CountTime total : "
                + Math.max(0, totalCountTimeMillis) + " ms");
        String label = artifactLabel + " / update phase CountTime total";
        recordDataMetric(metricKey(section, label), section, label,
                Long.toString(Math.max(0, totalCountTimeMillis)), "ms");
    }

    public static synchronized void recordUpdateEventTransitionCounts(
            String section,
            String artifactLabel,
            long beginUpdateTransitions,
            long stopOldSpecTransitions,
            long reconfigureTransitions,
            long startNewSpecTransitions,
            long finishUpdateTransitions,
            long normalTransitions,
            long countTimeMillis) {
        long safeCountTime = Math.max(0, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        long updateEventTransitions = beginUpdateTransitions
                + stopOldSpecTransitions
                + reconfigureTransitions
                + startNewSpecTransitions
                + finishUpdateTransitions;
        long totalTransitions = updateEventTransitions + normalTransitions;

        add(section, artifactLabel
                + " transitions by update event: hotSwapIn=" + beginUpdateTransitions
                + ", stopOldSpec=" + stopOldSpecTransitions
                + ", reconfigure=" + reconfigureTransitions
                + ", startNewSpec=" + startNewSpecTransitions
                + ", hotSwapOut=" + finishUpdateTransitions
                + ", normal=" + normalTransitions
                + ", CountTime: " + safeCountTime + " ms");

        String baseKey = metricKey(section, artifactLabel + " / update event transitions");
        recordDataMetric(baseKey + "_hot_swap_in", section,
                artifactLabel + " / hotSwapIn transitions", Long.toString(beginUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_stop_old_spec", section,
                artifactLabel + " / stopOldSpec transitions", Long.toString(stopOldSpecTransitions), "transitions");
        recordDataMetric(baseKey + "_reconfigure", section,
                artifactLabel + " / reconfigure transitions", Long.toString(reconfigureTransitions), "transitions");
        recordDataMetric(baseKey + "_start_new_spec", section,
                artifactLabel + " / startNewSpec transitions", Long.toString(startNewSpecTransitions), "transitions");
        recordDataMetric(baseKey + "_hot_swap_out", section,
                artifactLabel + " / hotSwapOut transitions", Long.toString(finishUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_normal", section,
                artifactLabel + " / normal transitions", Long.toString(normalTransitions), "transitions");
        recordDataMetric(baseKey + "_update_event_total", section,
                artifactLabel + " / update event transitions total", Long.toString(updateEventTransitions), "transitions");
        recordDataMetric(baseKey + "_total", section,
                artifactLabel + " / total transitions", Long.toString(totalTransitions), "transitions");
        recordDataMetric(baseKey + "_count_time", section,
                artifactLabel + " / update event transition CountTime", Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordUpdatePhaseTransitionDetail(
            String section,
            String artifactLabel,
            String phaseLabel,
            long states,
            long beginUpdateTransitions,
            long stopOldSpecTransitions,
            long reconfigureTransitions,
            long startNewSpecTransitions,
            long finishUpdateTransitions,
            long normalTransitions,
            long samePhaseNormalTransitions,
            double averageOutDegree,
            long maxOutDegree,
            double normalTransitionRate) {

        long updateEventTransitions = beginUpdateTransitions
                + stopOldSpecTransitions
                + reconfigureTransitions
                + startNewSpecTransitions
                + finishUpdateTransitions;
        long totalTransitions = updateEventTransitions + normalTransitions;
        String label = artifactLabel + " / updatePhase=" + phaseLabel;

        add(section, label
                + ": states=" + states
                + ", hotSwapIn=" + beginUpdateTransitions
                + ", stopOldSpec=" + stopOldSpecTransitions
                + ", reconfigure=" + reconfigureTransitions
                + ", startNewSpec=" + startNewSpecTransitions
                + ", hotSwapOut=" + finishUpdateTransitions
                + ", normal=" + normalTransitions
                + ", samePhaseNormal=" + samePhaseNormalTransitions
                + ", total=" + totalTransitions
                + ", normalRate=" + formatRatio(normalTransitionRate)
                + ", avgOutDegree=" + formatDouble(averageOutDegree)
                + ", maxOutDegree=" + maxOutDegree);

        String baseKey = metricKey(section, label + " / transition detail");
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_hot_swap_in", section, label + " / hotSwapIn transitions", Long.toString(beginUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_stop_old_spec", section, label + " / stopOldSpec transitions", Long.toString(stopOldSpecTransitions), "transitions");
        recordDataMetric(baseKey + "_reconfigure", section, label + " / reconfigure transitions", Long.toString(reconfigureTransitions), "transitions");
        recordDataMetric(baseKey + "_start_new_spec", section, label + " / startNewSpec transitions", Long.toString(startNewSpecTransitions), "transitions");
        recordDataMetric(baseKey + "_hot_swap_out", section, label + " / hotSwapOut transitions", Long.toString(finishUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_normal", section, label + " / normal transitions", Long.toString(normalTransitions), "transitions");
        recordDataMetric(baseKey + "_same_phase_normal", section, label + " / same-phase normal transitions", Long.toString(samePhaseNormalTransitions), "transitions");
        recordDataMetric(baseKey + "_update_event_total", section, label + " / update event transitions total", Long.toString(updateEventTransitions), "transitions");
        recordDataMetric(baseKey + "_total", section, label + " / total transitions", Long.toString(totalTransitions), "transitions");
        recordDataMetric(baseKey + "_normal_rate", section, label + " / normal transition rate", formatDouble(normalTransitionRate), "ratio");
        recordDataMetric(baseKey + "_avg_out_degree", section, label + " / average out-degree", formatDouble(averageOutDegree), "transitions/state");
        recordDataMetric(baseKey + "_max_out_degree", section, label + " / max out-degree", Long.toString(maxOutDegree), "transitions/state");
    }

    public static synchronized void recordUpdatePhaseTransitionDetailCountTime(
            String section,
            String artifactLabel,
            long countTimeMillis) {
        long safeCountTime = Math.max(0, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        String label = artifactLabel + " / update phase transition detail CountTime";
        add(section, label + " : " + safeCountTime + " ms");
        recordDataMetric(metricKey(section, label), section, label, Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordUpdatePhaseFlowCount(
            String section,
            String artifactLabel,
            String fromPhase,
            String toPhase,
            long transitions) {
        String label = artifactLabel + " / " + fromPhase + " -> " + toPhase;
        add(section, label + " : " + transitions + " transitions");
        recordDataMetric(metricKey(section, label), section, label, Long.toString(transitions), "transitions");
    }

    public static synchronized void recordUpdatePhaseFlowCountTime(
            String section,
            String artifactLabel,
            long countTimeMillis) {
        long safeCountTime = Math.max(0, countTimeMillis);
        String label = artifactLabel + " / update phase flow CountTime";
        add(section, label + " : " + safeCountTime + " ms");
        recordDataMetric(metricKey(section, label), section, label, Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordUpdateCompletionPathStats(
            String section,
            String artifactLabel,
            String completionPhase,
            long beginUpdateTransitions,
            long reachableBeginUpdateTransitions,
            long unreachableBeginUpdateTransitions,
            long minPathLength,
            long maxShortestPathLength,
            double averageShortestPathLength,
            long countTimeMillis) {

        long safeMin = reachableBeginUpdateTransitions > 0 ? minPathLength : -1;
        long safeMax = reachableBeginUpdateTransitions > 0 ? maxShortestPathLength : -1;
        String label = artifactLabel + " / hotSwapIn-to-completion";
        add(section, label
                + ": completionPhase=" + completionPhase
                + ", hotSwapInTransitions=" + beginUpdateTransitions
                + ", reachable=" + reachableBeginUpdateTransitions
                + ", unreachable=" + unreachableBeginUpdateTransitions
                + ", minLength=" + safeMin
                + ", maxShortestLength=" + safeMax
                + ", avgShortestLength=" + formatDouble(averageShortestPathLength)
                + ", shared CountTime=" + Math.max(0, countTimeMillis) + " ms");

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_completion_phase", section, label + " / completion phase", completionPhase, "phase");
        recordDataMetric(baseKey + "_hot_swap_in_transitions", section, label + " / hotSwapIn transitions", Long.toString(beginUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_reachable_hot_swap_in_transitions", section, label + " / reachable hotSwapIn transitions", Long.toString(reachableBeginUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_unreachable_hot_swap_in_transitions", section, label + " / unreachable hotSwapIn transitions", Long.toString(unreachableBeginUpdateTransitions), "transitions");
        recordDataMetric(baseKey + "_min_length", section, label + " / min path length", Long.toString(safeMin), "transitions");
        recordDataMetric(baseKey + "_max_shortest_length", section, label + " / max shortest path length", Long.toString(safeMax), "transitions");
        recordDataMetric(baseKey + "_avg_shortest_length", section, label + " / average shortest path length", formatDouble(averageShortestPathLength), "transitions");
        recordDataMetric(baseKey + "_count_time", section, label + " / shared CountTime", Long.toString(Math.max(0, countTimeMillis)), "ms");
    }

    public static synchronized void recordUpdateCompletionPathLengthDistribution(
            String section,
            String artifactLabel,
            Map<Long, Long> lengthDistribution) {
        String baseLabel = artifactLabel + " / hotSwapIn-to-completion shortest length distribution";
        if (lengthDistribution == null || lengthDistribution.isEmpty()) {
            add(section, baseLabel + " : empty");
            recordDataMetric(metricKey(section, baseLabel), section, baseLabel, "empty", "text");
            return;
        }

        for (Map.Entry<Long, Long> entry : lengthDistribution.entrySet()) {
            String label = baseLabel + " / length=" + entry.getKey();
            add(section, label + " : " + entry.getValue() + " hotSwapIn transitions");
            recordDataMetric(metricKey(section, label), section, label,
                    Long.toString(entry.getValue()), "transitions");
        }
    }

    public static synchronized void recordUpdateCompletionDistanceByPhase(
            String section,
            String artifactLabel,
            String phaseLabel,
            long states,
            long reachableStates,
            long unreachableStates,
            long minDistance,
            long maxDistance,
            double averageDistance) {

        String label = artifactLabel + " / updatePhase=" + phaseLabel + " / distance-to-completion";
        add(section, label
                + ": states=" + states
                + ", reachable=" + reachableStates
                + ", unreachable=" + unreachableStates
                + ", minDistance=" + minDistance
                + ", maxDistance=" + maxDistance
                + ", avgDistance=" + formatDouble(averageDistance));

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_reachable_states", section, label + " / reachable states", Long.toString(reachableStates), "states");
        recordDataMetric(baseKey + "_unreachable_states", section, label + " / unreachable states", Long.toString(unreachableStates), "states");
        recordDataMetric(baseKey + "_min_distance", section, label + " / min distance", Long.toString(minDistance), "transitions");
        recordDataMetric(baseKey + "_max_distance", section, label + " / max distance", Long.toString(maxDistance), "transitions");
        recordDataMetric(baseKey + "_avg_distance", section, label + " / average distance", formatDouble(averageDistance), "transitions");
    }

    public static synchronized void recordUpdatePhaseNormalActionTransitionCount(
            String section,
            String artifactLabel,
            String phaseLabel,
            String action,
            long transitions) {
        String label = artifactLabel + " / updatePhase=" + phaseLabel
                + " / normalAction=" + action;
        add(section, label + " : " + transitions + " transitions");
        recordDataMetric(metricKey(section, label), section, label,
                Long.toString(transitions), "transitions");
    }

    public static synchronized void recordUpdatePhaseNormalControllability(
            String section,
            String artifactLabel,
            String phaseLabel,
            long controllableTransitions,
            long uncontrollableTransitions,
            long unknownTransitions) {
        String label = artifactLabel + " / updatePhase=" + phaseLabel
                + " / normal action controllability";
        long total = controllableTransitions + uncontrollableTransitions + unknownTransitions;
        add(section, label
                + ": controllable=" + controllableTransitions
                + ", uncontrollable=" + uncontrollableTransitions
                + ", unknown=" + unknownTransitions
                + ", total=" + total);

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_controllable", section,
                label + " / controllable normal transitions", Long.toString(controllableTransitions), "transitions");
        recordDataMetric(baseKey + "_uncontrollable", section,
                label + " / uncontrollable normal transitions", Long.toString(uncontrollableTransitions), "transitions");
        recordDataMetric(baseKey + "_unknown", section,
                label + " / unknown normal transitions", Long.toString(unknownTransitions), "transitions");
        recordDataMetric(baseKey + "_total", section,
                label + " / total normal transitions", Long.toString(total), "transitions");
    }

    public static synchronized void recordNextUpdateEventDistanceByPhase(
            String section,
            String artifactLabel,
            String phaseLabel,
            long states,
            long reachableStates,
            long unreachableStates,
            long minDistance,
            long maxDistance,
            double averageDistance) {

        String label = artifactLabel + " / updatePhase=" + phaseLabel + " / distance-to-next-update-event";
        add(section, label
                + ": states=" + states
                + ", reachable=" + reachableStates
                + ", unreachable=" + unreachableStates
                + ", minDistance=" + minDistance
                + ", maxDistance=" + maxDistance
                + ", avgDistance=" + formatDouble(averageDistance));

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_reachable_states", section, label + " / reachable states", Long.toString(reachableStates), "states");
        recordDataMetric(baseKey + "_unreachable_states", section, label + " / unreachable states", Long.toString(unreachableStates), "states");
        recordDataMetric(baseKey + "_min_distance", section, label + " / min distance", Long.toString(minDistance), "transitions");
        recordDataMetric(baseKey + "_max_distance", section, label + " / max distance", Long.toString(maxDistance), "transitions");
        recordDataMetric(baseKey + "_avg_distance", section, label + " / average distance", formatDouble(averageDistance), "transitions");
    }

    public static synchronized void recordProgressFreeCycleStats(
            String section,
            String artifactLabel,
            String phaseLabel,
            long normalCycleSccs,
            long normalStatesInCycleSccs,
            long normalMaxCycleSccSize,
            long normalSelfLoopCycleSccs,
            long controllableCycleSccs,
            long controllableStatesInCycleSccs,
            long controllableMaxCycleSccSize) {

        String label = artifactLabel + " / updatePhase=" + phaseLabel + " / progress-free SCC";
        add(section, label
                + ": normalCycleSCCs=" + normalCycleSccs
                + ", normalStatesInCycleSCCs=" + normalStatesInCycleSccs
                + ", normalMaxSCCSize=" + normalMaxCycleSccSize
                + ", normalSelfLoopSCCs=" + normalSelfLoopCycleSccs
                + ", controllableOnlyCycleSCCs=" + controllableCycleSccs
                + ", controllableOnlyStatesInCycleSCCs=" + controllableStatesInCycleSccs
                + ", controllableOnlyMaxSCCSize=" + controllableMaxCycleSccSize);

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_normal_cycle_sccs", section,
                label + " / normal cycle SCCs", Long.toString(normalCycleSccs), "sccs");
        recordDataMetric(baseKey + "_normal_states_in_cycle_sccs", section,
                label + " / normal states in cycle SCCs", Long.toString(normalStatesInCycleSccs), "states");
        recordDataMetric(baseKey + "_normal_max_cycle_scc_size", section,
                label + " / normal max cycle SCC size", Long.toString(normalMaxCycleSccSize), "states");
        recordDataMetric(baseKey + "_normal_self_loop_cycle_sccs", section,
                label + " / normal self-loop cycle SCCs", Long.toString(normalSelfLoopCycleSccs), "sccs");
        recordDataMetric(baseKey + "_controllable_cycle_sccs", section,
                label + " / controllable-only cycle SCCs", Long.toString(controllableCycleSccs), "sccs");
        recordDataMetric(baseKey + "_controllable_states_in_cycle_sccs", section,
                label + " / controllable-only states in cycle SCCs", Long.toString(controllableStatesInCycleSccs), "states");
        recordDataMetric(baseKey + "_controllable_max_cycle_scc_size", section,
                label + " / controllable-only max cycle SCC size", Long.toString(controllableMaxCycleSccSize), "states");
    }

    public static synchronized void recordEnabledUpdateEventStates(
            String section,
            String artifactLabel,
            String phaseLabel,
            long anyUpdateEventStates,
            long beginUpdateStates,
            long stopOldSpecStates,
            long reconfigureStates,
            long startNewSpecStates,
            long finishUpdateStates) {

        String label = artifactLabel + " / updatePhase=" + phaseLabel
                + " / enabled update event states";
        add(section, label
                + ": any=" + anyUpdateEventStates
                + ", hotSwapIn=" + beginUpdateStates
                + ", stopOldSpec=" + stopOldSpecStates
                + ", reconfigure=" + reconfigureStates
                + ", startNewSpec=" + startNewSpecStates
                + ", hotSwapOut=" + finishUpdateStates);

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_any", section,
                label + " / any update event states", Long.toString(anyUpdateEventStates), "states");
        recordDataMetric(baseKey + "_hot_swap_in", section,
                label + " / hotSwapIn-enabled states", Long.toString(beginUpdateStates), "states");
        recordDataMetric(baseKey + "_stop_old_spec", section,
                label + " / stopOldSpec-enabled states", Long.toString(stopOldSpecStates), "states");
        recordDataMetric(baseKey + "_reconfigure", section,
                label + " / reconfigure-enabled states", Long.toString(reconfigureStates), "states");
        recordDataMetric(baseKey + "_start_new_spec", section,
                label + " / startNewSpec-enabled states", Long.toString(startNewSpecStates), "states");
        recordDataMetric(baseKey + "_hot_swap_out", section,
                label + " / hotSwapOut-enabled states", Long.toString(finishUpdateStates), "states");
    }

    public static synchronized void recordSharedDiagnosticCountTime(
            String section,
            String artifactLabel,
            String labelSuffix,
            long countTimeMillis) {
        String label = artifactLabel + " / " + labelSuffix;
        add(section, label + " : " + Math.max(0, countTimeMillis) + " ms (shared)");
        recordDataMetric(metricKey(section, label), section, label,
                Long.toString(Math.max(0, countTimeMillis)), "ms");
    }

    public static synchronized void recordUpdateOrderPatternStats(
            String section,
            String artifactLabel,
            String pattern,
            String dominantPhase,
            long stateOccurrences,
            long uniqueStates,
            long transitions,
            long normalTransitions,
            long updateEventTransitions) {

        String label = artifactLabel + " / updateOrder=" + pattern;
        add(section, label
                + ": dominantPhase=" + dominantPhase
                + ", stateOccurrences=" + stateOccurrences
                + ", uniqueStates=" + uniqueStates
                + ", transitions=" + transitions
                + ", normalTransitions=" + normalTransitions
                + ", updateEventTransitions=" + updateEventTransitions);

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_dominant_phase", section,
                label + " / dominant phase", dominantPhase, "phase");
        recordDataMetric(baseKey + "_state_occurrences", section,
                label + " / state occurrences", Long.toString(stateOccurrences), "states");
        recordDataMetric(baseKey + "_unique_states", section,
                label + " / unique states", Long.toString(uniqueStates), "states");
        recordDataMetric(baseKey + "_transitions", section,
                label + " / transitions", Long.toString(transitions), "transitions");
        recordDataMetric(baseKey + "_normal_transitions", section,
                label + " / normal transitions", Long.toString(normalTransitions), "transitions");
        recordDataMetric(baseKey + "_update_event_transitions", section,
                label + " / update event transitions", Long.toString(updateEventTransitions), "transitions");
    }

    public static synchronized void recordNormalRunLengthStats(
            String section,
            String artifactLabel,
            String afterUpdateEvent,
            long samples,
            long reachableSamples,
            long unreachableSamples,
            long minLength,
            long maxLength,
            double averageLength) {

        String label = artifactLabel + " / afterUpdateEvent=" + afterUpdateEvent
                + " / normal-run-before-next-update-event";
        add(section, label
                + ": samples=" + samples
                + ", reachable=" + reachableSamples
                + ", unreachable=" + unreachableSamples
                + ", minLength=" + minLength
                + ", maxLength=" + maxLength
                + ", avgLength=" + formatDouble(averageLength));

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_samples", section,
                label + " / samples", Long.toString(samples), "samples");
        recordDataMetric(baseKey + "_reachable_samples", section,
                label + " / reachable samples", Long.toString(reachableSamples), "samples");
        recordDataMetric(baseKey + "_unreachable_samples", section,
                label + " / unreachable samples", Long.toString(unreachableSamples), "samples");
        recordDataMetric(baseKey + "_min_length", section,
                label + " / min normal-run length", Long.toString(minLength), "transitions");
        recordDataMetric(baseKey + "_max_length", section,
                label + " / max normal-run length", Long.toString(maxLength), "transitions");
        recordDataMetric(baseKey + "_avg_length", section,
                label + " / average normal-run length", formatDouble(averageLength), "transitions");
    }

    public static synchronized void recordNormalRunLengthDistribution(
            String section,
            String artifactLabel,
            String afterUpdateEvent,
            Map<Long, Long> lengthDistribution) {

        String baseLabel = artifactLabel + " / afterUpdateEvent=" + afterUpdateEvent
                + " / normal-run length distribution";
        if (lengthDistribution == null || lengthDistribution.isEmpty()) {
            add(section, baseLabel + " : empty");
            recordDataMetric(metricKey(section, baseLabel), section, baseLabel, "empty", "text");
            return;
        }

        for (Map.Entry<Long, Long> entry : lengthDistribution.entrySet()) {
            String label = baseLabel + " / length=" + entry.getKey();
            add(section, label + " : " + entry.getValue() + " samples");
            recordDataMetric(metricKey(section, label), section, label,
                    Long.toString(entry.getValue()), "samples");
        }
    }

    public static synchronized void recordProjectionSplitStats(
            String section,
            String artifactLabel,
            String projectionLabel,
            long totalStates,
            long distinctProjectionValues,
            long splitProjectionValues,
            long maxStatesPerProjectionValue,
            double averageStatesPerProjectionValue,
            long countTimeMillis) {

        long safeCountTime = Math.max(0, countTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        String label = artifactLabel + " / projection=" + projectionLabel;
        add(section, label
                + ": totalStates=" + totalStates
                + ", distinctProjectionValues=" + distinctProjectionValues
                + ", splitProjectionValues=" + splitProjectionValues
                + ", maxStatesPerProjectionValue=" + maxStatesPerProjectionValue
                + ", avgStatesPerProjectionValue=" + formatDouble(averageStatesPerProjectionValue)
                + ", CountTime=" + safeCountTime + " ms");

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_total_states", section,
                label + " / total states", Long.toString(totalStates), "states");
        recordDataMetric(baseKey + "_distinct_projection_values", section,
                label + " / distinct projection values", Long.toString(distinctProjectionValues), "values");
        recordDataMetric(baseKey + "_split_projection_values", section,
                label + " / split projection values", Long.toString(splitProjectionValues), "values");
        recordDataMetric(baseKey + "_max_states_per_projection_value", section,
                label + " / max states per projection value", Long.toString(maxStatesPerProjectionValue), "states/value");
        recordDataMetric(baseKey + "_avg_states_per_projection_value", section,
                label + " / average states per projection value", formatDouble(averageStatesPerProjectionValue), "states/value");
        recordDataMetric(baseKey + "_count_time", section,
                label + " / CountTime", Long.toString(safeCountTime), "ms");
    }

    public static synchronized void recordStateTransitionReduction(
            String section,
            String label,
            long sourceStates,
            long sourceTransitions,
            long targetStates,
            long targetTransitions) {

        long removedStates = sourceStates - targetStates;
        long removedTransitions = sourceTransitions - targetTransitions;
        double stateReductionRate = sourceStates <= 0 ? 0.0 : ((double) removedStates) / sourceStates;
        double transitionReductionRate = sourceTransitions <= 0 ? 0.0 : ((double) removedTransitions) / sourceTransitions;
        double stateRemainRate = sourceStates <= 0 ? 0.0 : ((double) targetStates) / sourceStates;
        double transitionRemainRate = sourceTransitions <= 0 ? 0.0 : ((double) targetTransitions) / sourceTransitions;

        add(section, label
                + ": sourceStates=" + sourceStates
                + ", targetStates=" + targetStates
                + ", removedStates=" + removedStates
                + ", stateReductionRate=" + formatRatio(stateReductionRate)
                + ", sourceTransitions=" + sourceTransitions
                + ", targetTransitions=" + targetTransitions
                + ", removedTransitions=" + removedTransitions
                + ", transitionReductionRate=" + formatRatio(transitionReductionRate));

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_source_states", section, label + " / source states", Long.toString(sourceStates), "states");
        recordDataMetric(baseKey + "_target_states", section, label + " / target states", Long.toString(targetStates), "states");
        recordDataMetric(baseKey + "_removed_states", section, label + " / removed states", Long.toString(removedStates), "states");
        recordDataMetric(baseKey + "_state_reduction_rate", section, label + " / state reduction rate", formatDouble(stateReductionRate), "ratio");
        recordDataMetric(baseKey + "_state_remain_rate", section, label + " / state remain rate", formatDouble(stateRemainRate), "ratio");
        recordDataMetric(baseKey + "_source_transitions", section, label + " / source transitions", Long.toString(sourceTransitions), "transitions");
        recordDataMetric(baseKey + "_target_transitions", section, label + " / target transitions", Long.toString(targetTransitions), "transitions");
        recordDataMetric(baseKey + "_removed_transitions", section, label + " / removed transitions", Long.toString(removedTransitions), "transitions");
        recordDataMetric(baseKey + "_transition_reduction_rate", section, label + " / transition reduction rate", formatDouble(transitionReductionRate), "ratio");
        recordDataMetric(baseKey + "_transition_remain_rate", section, label + " / transition remain rate", formatDouble(transitionRemainRate), "ratio");
    }

    public static synchronized void recordTransitionReduction(
            String section,
            String label,
            long sourceTransitions,
            long targetTransitions) {

        long removedTransitions = sourceTransitions - targetTransitions;
        double transitionReductionRate = sourceTransitions <= 0 ? 0.0 : ((double) removedTransitions) / sourceTransitions;
        double transitionRemainRate = sourceTransitions <= 0 ? 0.0 : ((double) targetTransitions) / sourceTransitions;
        add(section, label
                + ": sourceTransitions=" + sourceTransitions
                + ", targetTransitions=" + targetTransitions
                + ", removedTransitions=" + removedTransitions
                + ", transitionReductionRate=" + formatRatio(transitionReductionRate));

        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_source_transitions", section, label + " / source transitions", Long.toString(sourceTransitions), "transitions");
        recordDataMetric(baseKey + "_target_transitions", section, label + " / target transitions", Long.toString(targetTransitions), "transitions");
        recordDataMetric(baseKey + "_removed_transitions", section, label + " / removed transitions", Long.toString(removedTransitions), "transitions");
        recordDataMetric(baseKey + "_transition_reduction_rate", section, label + " / transition reduction rate", formatDouble(transitionReductionRate), "ratio");
        recordDataMetric(baseKey + "_transition_remain_rate", section, label + " / transition remain rate", formatDouble(transitionRemainRate), "ratio");
    }

    public static synchronized void recordDecisionRate(
            String section,
            String label,
            long numerator,
            long denominator,
            String numeratorUnit) {

        double rate = denominator <= 0 ? 0.0 : ((double) numerator) / denominator;
        add(section, label + " : " + numerator + " / " + denominator
                + " (" + formatRatio(rate) + ")");
        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_count", section, label + " / count", Long.toString(numerator),
                numeratorUnit == null ? "count" : numeratorUnit);
        recordDataMetric(baseKey + "_denominator", section, label + " / denominator", Long.toString(denominator), "count");
        recordDataMetric(baseKey + "_rate", section, label + " / rate", formatDouble(rate), "ratio");
    }

    public static synchronized void recordOldControllerStateSpace(
            long states, long transitions, long countTimeMillis) {
        if (!isEnabled()) {
            return;
        }
        oldControllerStates = states;
        recordStateSpace("入力規模 / 事前合成", "Old Controller", states, transitions, countTimeMillis);
    }

    public static synchronized void recordMemory(String section, String label, long bytes) {
        if (!isEnabled()) {
            return;
        }
        add(section, label + " : " + formatBytes(bytes));
        recordDataMetric(section, label, bytesToByteText(bytes), "B");
    }

    /**
     * Records the same-time aggregate heap samples collected for one synthesis
     * window.  These metrics are the primary heap-memory measurements; the
     * MemoryPoolMXBean peak sum is retained separately as a legacy diagnostic.
     */
    public static synchronized void recordSampledHeapMemory(
            RunHeapMemorySampler.Result result) {
        if (!isEnabled() || result == null) {
            return;
        }

        String section = "共通 / sampled heap memory";
        boolean available = result.getSampleCount() > 0L;
        long baselineBytes = result.getFirstHeapUsedBytes();
        long peakBytes = result.getPeakHeapUsedBytes();
        long increaseBytes = baselineBytes >= 0L && peakBytes >= 0L
                ? Math.max(0L, peakBytes - baselineBytes)
                : -1L;

        recordDataMetric(
                "heap_memory_sampling_enabled",
                section,
                "同時点ヒープ周期計測の有効化",
                "true",
                "boolean");
        recordDataMetric(
                "heap_memory_sampling_available",
                section,
                "同時点ヒープsample取得可否",
                Boolean.toString(available),
                "boolean");
        if (available) {
            recordDataMetric(
                    "controller_synthesis_sampled_base_heap_used",
                    section,
                    "合成区間の最初の有効sampleにおける同時点ヒープ使用量",
                    Long.toString(baselineBytes),
                    "B");
            recordDataMetric(
                    "controller_synthesis_sampled_peak_heap_used",
                    section,
                    "合成区間の同時点ヒープ使用量最大値",
                    Long.toString(peakBytes),
                    "B");
            recordDataMetric(
                    "controller_synthesis_sampled_heap_increase",
                    section,
                    "同時点ヒープ最大値と最初の有効sampleとの差",
                    Long.toString(increaseBytes),
                    "B");
            recordDataMetric(
                    "controller_synthesis_sampled_peak_heap_epoch_ms",
                    section,
                    "同時点ヒープ最大値の観測epoch時刻",
                    Long.toString(result.getPeakTimestampEpochMillis()),
                    "epoch_ms");
            recordDataMetric(
                    "heap_memory_sampling_first_sample_epoch_ms",
                    section,
                    "最初の有効ヒープsampleのepoch時刻",
                    Long.toString(result.getFirstTimestampEpochMillis()),
                    "epoch_ms");
        }
        recordDataMetric(
                "heap_memory_sampling_interval_ms",
                section,
                "ヒープ計測設定間隔",
                Long.toString(result.getIntervalMillis()),
                "ms");
        recordDataMetric(
                "heap_memory_sampling_sample_count",
                section,
                "有効ヒープsample数",
                Long.toString(result.getSampleCount()),
                "samples");
        recordDataMetric(
                "heap_memory_sampling_failure_count",
                section,
                "失敗ヒープsample数",
                Long.toString(result.getFailedSampleCount()),
                "samples");
        recordDataMetric(
                "heap_memory_sampling_max_gap_ms",
                section,
                "ヒープsample開始間隔の最大値",
                Long.toString(result.getMaxSampleGapMillis()),
                "ms");
        recordDataMetric(
                "heap_memory_sampling_total_wall_time_ns",
                section,
                "ヒープsample取得wall time合計",
                Long.toString(result.getTotalSamplingWallTimeNanos()),
                "ns");
        recordDataMetric(
                "heap_memory_sampling_thread_cpu_time_ns",
                section,
                "ヒープsampler thread CPU時間",
                Long.toString(result.getSamplerThreadCpuTimeNanos()),
                "ns");
        recordDataMetric(
                "heap_memory_sampling_thread_cpu_time_available",
                section,
                "ヒープsampler thread CPU時間取得可否",
                Boolean.toString(result.isSamplerThreadCpuTimeAvailable()),
                "boolean");
    }

    public static synchronized void recordHeapMemorySamplingStatus(
            boolean enabled,
            boolean available,
            String failure) {
        if (!isEnabled()) {
            return;
        }
        String section = "共通 / sampled heap memory";
        recordDataMetric("heap_memory_sampling_enabled", section,
                "同時点ヒープ周期計測の有効化",
                Boolean.toString(enabled), "boolean");
        recordDataMetric("heap_memory_sampling_available", section,
                "同時点ヒープsample取得可否",
                Boolean.toString(available), "boolean");
        if (failure != null && !failure.isEmpty()) {
            recordDataMetric("heap_memory_sampling_error", section,
                    "同時点ヒープ計測エラー", failure, "text");
        }
    }

    /**
     * Adds explicit aliases for the historical pool-wise peak sum without
     * changing the meaning of the pre-existing CSV keys.
     */
    public static synchronized void recordLegacyPoolPeakMemoryAliases(
            long baselineBytes,
            long peakPoolSumBytes,
            long increaseBytes) {
        if (!isEnabled()) {
            return;
        }
        String section = "共通 / legacy memory metric";
        String formula = "各heap MemoryPoolMXBeanが別々の時刻に記録したpeak usedの合計。"
                + "同一時点のJVM heap最大値ではない。";
        recordDataMetricWithFormula(
                "controller_synthesis_base_memory_legacy_pool_api",
                section,
                "旧指標の合成開始時ヒープ使用量",
                Long.toString(baselineBytes),
                "B",
                "各heap poolのcurrent usedを順に取得して合計。" );
        recordDataMetricWithFormula(
                "controller_synthesis_peak_memory_legacy_pool_sum",
                section,
                "旧指標のpool別peak合計",
                Long.toString(peakPoolSumBytes),
                "B",
                formula);
        recordDataMetricWithFormula(
                "controller_synthesis_memory_increase_legacy_pool_sum",
                section,
                "旧指標のpool別peak合計と開始値との差",
                Long.toString(increaseBytes),
                "B",
                "旧指標のpool別peak合計 - 旧指標の合成開始時ヒープ使用量。" );

        attachDataMetricFormula("controller_synthesis_base_memory",
                "後方互換key。各heap poolのcurrent usedを順に取得して合計。" );
        attachDataMetricFormula("controller_synthesis_peak_memory", formula);
        attachDataMetricFormula("controller_synthesis_memory_increase",
                "後方互換key。pool別peak合計 - 旧指標の合成開始時ヒープ使用量。" );
    }

    public static synchronized void recordMemoryInterval(
            String section,
            String labelPrefix,
            long beforeBytes,
            long peakBytes) {
        if (!isEnabled()) {
            return;
        }

        long increaseBytes = peakBytes - beforeBytes;
        recordMemory(section, labelPrefix + "直前メモリ", beforeBytes);
        recordMemory(section, labelPrefix + "中ピークメモリ", peakBytes);
        recordMemory(section, labelPrefix + "中増加メモリ", increaseBytes);
    }

    public static synchronized void recordMemoryCheckpoint(String label) {
        recordMemoryCheckpoint("メモリ使用量チェックポイント", label);
    }

    public static synchronized void recordMemoryCheckpoint(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        String normalizedSection = section == null || section.isEmpty()
                ? "メモリ使用量チェックポイント"
                : section;
        ensureMemoryCheckpointHeader(normalizedSection);

        long currentBytes = EvaluationProfiler.getCurrentMemoryUsage();
        long peakBytes = EvaluationProfiler.getPeakMemoryUsage();
        if (memoryBaselineBytes < 0) {
            memoryBaselineBytes = currentBytes;
        }
        long deltaFromBaseline = currentBytes - memoryBaselineBytes;
        long deltaFromPrevious = previousMemoryCheckpointBytes < 0
                ? 0
                : currentBytes - previousMemoryCheckpointBytes;
        previousMemoryCheckpointBytes = currentBytes;

        add(normalizedSection,
                padRight(label, 46)
                        + " 現在ヒープ=" + padLeft(formatMiB(currentBytes), 8)
                        + " 旧pool peak合計=" + padLeft(formatMiB(peakBytes), 8)
                        + " 開始時からの増減=" + padLeft(formatSignedMiB(deltaFromBaseline), 9)
                        + " 直前からの増減=" + padLeft(formatSignedMiB(deltaFromPrevious), 9));
        String baseKey = metricKey(normalizedSection, label);
        recordDataMetric(baseKey + "_current_heap", normalizedSection, label + " / 現在ヒープ", bytesToByteText(currentBytes), "B");
        recordDataMetricWithFormula(baseKey + "_peak_heap", normalizedSection,
                label + " / 旧pool別peak合計", bytesToByteText(peakBytes), "B",
                "各heap MemoryPoolMXBeanが別々の時刻に記録したpeak usedの合計。" );
        recordDataMetric(baseKey + "_delta_from_start", normalizedSection, label + " / 開始時からの増減", bytesToByteText(deltaFromBaseline), "B");
        recordDataMetric(baseKey + "_delta_from_previous", normalizedSection, label + " / 直前からの増減", bytesToByteText(deltaFromPrevious), "B");
    }

    /**
     * Returns whether the opt-in phase-memory diagnostics are active for the
     * current synthesis run.  The property is sampled by {@link #reset()} so
     * ordinary runs do not repeatedly parse a system property at every phase.
     */
    public static boolean isPhaseMemoryDiagnosticsEnabled() {
        return phaseMemoryDiagnosticsEnabled;
    }

    /**
     * Records one instantaneous aggregate-heap observation and timestamps it
     * for correlation with GC logs and JFR.  This method deliberately does not
     * call {@link #recordMemoryCheckpoint(String, String)}: that legacy method
     * also reads the sum of independently timed pool peaks and mutates the
     * ordinary checkpoint delta chain.
     */
    public static synchronized void recordPhaseMemoryCheckpoint(String label) {
        if (!isEnabled() || !phaseMemoryDiagnosticsEnabled) {
            return;
        }

        try {
            recordPhaseMemoryCheckpointEnabled(label);
        } catch (RuntimeException ignored) {
            // Diagnostics must never replace or invalidate synthesis.
            phaseMemoryDiagnosticsEnabled = false;
        } catch (LinkageError ignored) {
            phaseMemoryDiagnosticsEnabled = false;
        } catch (OutOfMemoryError ignored) {
            phaseMemoryDiagnosticsEnabled = false;
        }
    }

    private static void recordPhaseMemoryCheckpointEnabled(String label) {

        final String section = "診断 / phase memory checkpoints";
        final String normalizedLabel = label == null || label.isEmpty()
                ? "unnamed"
                : label;
        final long sequence = ++phaseMemoryDiagnosticsSequence;
        final long epochMillis = System.currentTimeMillis();
        final long elapsedMillis = phaseMemoryDiagnosticsStartNanos < 0
                ? -1
                : Math.max(0L,
                        (System.nanoTime() - phaseMemoryDiagnosticsStartNanos)
                                / 1_000_000L);
        final long currentHeapBytes = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage()
                .getUsed();

        add(section,
                padRight(normalizedLabel, 58)
                        + " sequence=" + padLeft(Long.toString(sequence), 4)
                        + " elapsed=" + padLeft(Long.toString(elapsedMillis), 10) + " ms"
                        + " currentHeap=" + padLeft(formatMiB(currentHeapBytes), 8));

        String token = metricToken(normalizedLabel);
        if (token.isEmpty()) {
            token = Integer.toHexString(normalizedLabel.hashCode());
        }
        String baseKey = "diagnostic_phase_memory_" + token;
        recordDataMetric(baseKey + "_sequence", section,
                normalizedLabel + " / sequence", Long.toString(sequence), "count");
        recordDataMetric(baseKey + "_epoch_ms", section,
                normalizedLabel + " / epoch", Long.toString(epochMillis), "epoch_ms");
        recordDataMetric(baseKey + "_elapsed_ms", section,
                normalizedLabel + " / elapsed from synthesis reset",
                Long.toString(elapsedMillis), "ms");
        recordDataMetric(baseKey + "_current_heap_used", section,
                normalizedLabel + " / current aggregate heap used",
                bytesToByteText(currentHeapBytes), "B");
    }

    public static synchronized void recordOutputController(long states, long transitions, long countTimeMillis) {
        addStateSpaceCountOverhead(countTimeMillis);
        add("Output Update Controller", "States: " + states
                + ", Transitions: " + transitions
                + ", CountTime: " + countTimeMillis + " ms");
        recordDataMetric("output_update_controller_states", "Output Update Controller", "States", Long.toString(states), "states");
        recordDataMetric("output_update_controller_transitions", "Output Update Controller", "Transitions", Long.toString(transitions), "transitions");
        recordDataMetric("output_update_controller_count_time", "Output Update Controller", "CountTime", Long.toString(countTimeMillis), "ms");
        recordOutputReductionIfAvailable(states, transitions);
    }

    public static synchronized void recordMinimizedOutputController(
            long states,
            long transitions,
            long countTimeMillis,
            long minimizeTimeMillis) {
        if (!isEnabled()) {
            return;
        }
        long safeCountTime = Math.max(0, countTimeMillis);
        long safeMinimizeTime = Math.max(0, minimizeTimeMillis);
        addStateSpaceCountOverhead(safeCountTime);
        add("Minimized Output Update Controller", "States: " + states
                + ", Transitions: " + transitions
                + ", CountTime: " + safeCountTime + " ms"
                + ", MinimizeTime: " + safeMinimizeTime + " ms");
        recordDataMetric("minimized_output_update_controller_states",
                "Minimized Output Update Controller",
                "States",
                Long.toString(states),
                "states");
        recordDataMetric("minimized_output_update_controller_transitions",
                "Minimized Output Update Controller",
                "Transitions",
                Long.toString(transitions),
                "transitions");
        recordDataMetric("minimized_output_update_controller_count_time",
                "Minimized Output Update Controller",
                "CountTime",
                Long.toString(safeCountTime),
                "ms");
        recordDataMetric("minimized_output_update_controller_minimize_time",
                "Minimized Output Update Controller",
                "MinimizeTime",
                Long.toString(safeMinimizeTime),
                "ms");
    }

    public static synchronized void recordBeginUpdateCoverage(long beginUpdateStates, long countTimeMillis) {
        addStateSpaceCountOverhead(countTimeMillis);
        long denominator = oldControllerStates >= 0 ? oldControllerStates : beginUpdateReferenceStates;
        if (denominator >= 0 && beginUpdateStates <= denominator) {
            add("要件確認", "hotSwapIn が出ている状態数 : " + beginUpdateStates
                    + " / 旧コントローラ状態数 " + denominator
                    + " 状態, CountTime: " + countTimeMillis + " ms");
        } else if (denominator >= 0) {
            add("要件確認", "hotSwapIn が出ている状態数 : " + beginUpdateStates
                    + " 状態, 旧コントローラ状態数 : " + denominator
                    + ", CountTime: " + countTimeMillis + " ms");
        } else {
            add("要件確認", "hotSwapIn が出ている状態数 : " + beginUpdateStates
                    + ", CountTime: " + countTimeMillis + " ms");
        }
        recordDataMetric("hot_swap_in_outgoing_states", "要件確認", "hotSwapIn outgoing states", Long.toString(beginUpdateStates), "states");
        if (denominator >= 0) {
            recordDataMetric("hot_swap_in_reference_states", "要件確認", "hotSwapIn reference states", Long.toString(denominator), "states");
            recordDataMetric("old_controller_states_for_hot_swap_in", "要件確認", "旧コントローラ状態数", Long.toString(denominator), "states");
        }
        recordDataMetric("hot_swap_in_coverage_count_time", "要件確認", "hotSwapIn coverage CountTime", Long.toString(countTimeMillis), "ms");
    }

    public static synchronized void recordOtfPreUpdateStateOverhead(
            long oldControllerStateCount,
            long preUpdateRawStateCount,
            long preUpdateOutputStateCount) {
        if (!isEnabled()) {
            return;
        }

        long effectiveOldControllerStates = oldControllerStates >= 0
                ? oldControllerStates
                : oldControllerStateCount;
        if (oldControllerStates < 0 && oldControllerStateCount >= 0) {
            oldControllerStates = oldControllerStateCount;
        }

        long overhead = effectiveOldControllerStates >= 0 && preUpdateOutputStateCount >= 0
                ? Math.max(0, preUpdateOutputStateCount - effectiveOldControllerStates)
                : -1;

        if (effectiveOldControllerStates >= 0) {
            add("要件確認", "旧コントローラ状態数 : " + effectiveOldControllerStates + " 状態");
            recordDataMetric("old_controller_states_for_hot_swap_in", "要件確認",
                    "旧コントローラ状態数", Long.toString(effectiveOldControllerStates), "states");
        }

        add("要件確認", "探索上の旧コントローラ相当状態数（出力時マージ前） : "
                + preUpdateRawStateCount + " 状態");
        add("要件確認", "出力上の旧コントローラ相当状態数（マージ後） : "
                + preUpdateOutputStateCount + " 状態");
        if (overhead >= 0) {
            add("要件確認", "OTF-DUCにより増えた旧コントローラ相当状態数 : "
                    + overhead + " 状態");
        }

        recordDataMetric("otf_pre_update_raw_states", "要件確認",
                "探索上の旧コントローラ相当状態数（出力時マージ前）",
                Long.toString(preUpdateRawStateCount), "states");
        recordDataMetric("otf_pre_update_output_states", "要件確認",
                "出力上の旧コントローラ相当状態数（マージ後）",
                Long.toString(preUpdateOutputStateCount), "states");
        if (overhead >= 0) {
            recordDataMetric("otf_pre_update_state_overhead", "要件確認",
                    "OTF-DUCにより増えた旧コントローラ相当状態数",
                    Long.toString(overhead), "states");
        }
    }

    public static synchronized void recordOtfSimpleMergeSplitStats(
            long splitOldControllerStates,
            long maxSplitPerOldControllerState) {

        add("要件確認", "簡単マージ後も分裂している旧コントローラ状態数 : "
                + splitOldControllerStates + " 状態");
        add("要件確認", "1つの旧コントローラ状態あたりの最大分裂数 : "
                + maxSplitPerOldControllerState + " 個");

        recordDataMetric("otf_simple_merge_split_old_controller_states", "要件確認",
                "簡単マージ後も分裂している旧コントローラ状態数",
                Long.toString(splitOldControllerStates), "states");
        recordDataMetric("otf_simple_merge_max_split_per_old_controller_state", "要件確認",
                "1つの旧コントローラ状態あたりの最大分裂数",
                Long.toString(maxSplitPerOldControllerState), "classes");
    }

    public static synchronized boolean hasOldControllerStateSpace() {
        return isEnabled() && oldControllerStates >= 0;
    }

    public static synchronized boolean isUpdatingControllerMode() {
        return isEnabled() && ("OTF-DUC".equals(mode)
                || "Traditional DUC".equals(mode)
                || "Stepwise DUC".equals(mode)
                || "Stepwise Delayed DUC".equals(mode));
    }

    public static synchronized void recordBeginUpdateReferenceStates(long states) {
        if (!isEnabled()) {
            return;
        }
        if (states >= 0) {
            beginUpdateReferenceStates = states;
        }
    }

    public static synchronized void recordValue(String section, String label, String value) {
        if (!isEnabled()) {
            return;
        }
        add(section, label + " : " + value);
        recordDataMetric(section, label, value == null ? "" : value, "text");
    }

    public static synchronized long getRecordedTimeMillis(String section, String label) {
        if (!isEnabled()) {
            return 0;
        }
        return optionalTime(section, label);
    }

    public static synchronized void recordMemorySnapshot(String section) {
        if (!isEnabled()) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        recordMemory(section, "現在のヒープ使用量", EvaluationProfiler.getCurrentMemoryUsage());
        recordMemory(section, "ピークヒープ使用量", EvaluationProfiler.getPeakMemoryUsage());
        recordMemory(section, "JVM 最大ヒープ", runtime.maxMemory());
        recordMemory(section, "JVM totalMemory", runtime.totalMemory());
        recordMemory(section, "JVM freeMemory", runtime.freeMemory());
    }

    public static synchronized void printSummary(LTSOutput output) {
        if (!isEnabled() || output == null || printed) {
            return;
        }
        printed = true;

        if (resultStatus == ResultStatus.NOT_RECORDED) {
            resultStatus = ResultStatus.UNKNOWN_FAILURE;
        }
        flushActiveTimers();

        refreshStateSpaceCountOverheadDataMetrics();
        evaluationHeaderOutputMillis = 0;

        long detailedReportOutputStart = System.nanoTime();
        if (shouldPrintDetailedReport()) {
            output.outln("");
            output.outln("================ DETAILED EVALUATION METRICS ================");
            for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
                output.outln("");
                output.outln("[" + entry.getKey() + "]");
                printSectionDescription(output, entry.getKey());
                for (String line : entry.getValue()) {
                    output.outln(line);
                }
            }
            output.outln("==============================================================");
            output.outln("");
        }
        evaluationDetailedReportOutputMillis = elapsedMillis(detailedReportOutputStart);

        long summaryOutputStart = System.nanoTime();
        printEvaluationSummary(output);
        evaluationSummaryOutputMillis = elapsedMillis(summaryOutputStart);

        recordEvaluationOutputMetrics(false);
        recordCountScopeMetrics();
        recordComparisonSummary();
        writeDataCsvFileIfConfigured(output);
        // CSV output to the Output tab remains disabled for all DUCS modes.
    }

    private static boolean shouldPrintDetailedReport() {
        return Boolean.parseBoolean(System.getProperty(PRINT_DETAILED_REPORT_PROPERTY, "false"));
    }

    private static void recordEvaluationOutputMetrics(boolean includeDataCsv) {
        long dataCsvMillis = includeDataCsv ? Math.max(0, evaluationDataCsvOutputMillis) : 0;
        evaluationOutputOverheadMillis = Math.max(0, evaluationHeaderOutputMillis)
                + Math.max(0, evaluationDetailedReportOutputMillis)
                + Math.max(0, evaluationSummaryOutputMillis)
                + dataCsvMillis;

        recordDataMetricWithFormula(
                "evaluation_output_header_time",
                "評価出力時間",
                "評価ヘッダ出力時間",
                Long.toString(Math.max(0, evaluationHeaderOutputMillis)),
                "ms",
                "通常表示では出力しない評価ヘッダ部分の時間。");
        recordDataMetricWithFormula(
                "evaluation_output_detailed_report_time",
                "評価出力時間",
                "詳細評価レポート出力時間",
                Long.toString(Math.max(0, evaluationDetailedReportOutputMillis)),
                "ms",
                "詳細評価レポート本文を Output に出す時間。デフォルトでは出力しない。");
        recordDataMetricWithFormula(
                "evaluation_output_summary_time",
                "評価出力時間",
                "評価サマリ出力時間",
                Long.toString(Math.max(0, evaluationSummaryOutputMillis)),
                "ms",
                "EVALUATION SUMMARY を Output に出す時間。");
        if (includeDataCsv) {
            recordDataMetricWithFormula(
                    "evaluation_output_data_csv_time",
                    "評価出力時間",
                    "評価CSV出力時間",
                    Long.toString(Math.max(0, evaluationDataCsvOutputMillis)),
                    "ms",
                    "EVALUATION DATA CSV を Output に出す時間。末尾の追加計測行自身はほぼ含まない。");
        }
        recordDataMetricWithFormula(
                includeDataCsv
                        ? "evaluation_output_overhead_total_including_csv"
                        : "evaluation_output_overhead_before_csv",
                "評価出力時間",
                includeDataCsv ? "評価出力時間合計（CSV含む）" : "評価出力時間（CSV出力前まで）",
                Long.toString(evaluationOutputOverheadMillis),
                "ms",
                includeDataCsv
                        ? "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間 + 評価CSV出力時間。"
                        : "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間。CSV出力時間はCSV出力後に別途記録する。");
    }

    private static void recordCountScopeMetrics() {
        String section = "評価用カウント時間 / 関数スコープ別";
        for (CountScope scope : countScopes.values()) {
            long countMillis = Math.max(0, scope.countTimeMillis);
            String scopeLabel = scope.section + " / " + scope.label;
            String countKey = scope.baseMetricKey + "_scope_count_overhead_time";

            add(section, scopeLabel + " / 評価用カウント時間 : " + countMillis + " ms");
            recordDataMetricWithFormula(
                    countKey,
                    section,
                    scopeLabel + " / 評価用カウント時間",
                    Long.toString(countMillis),
                    "ms",
                    "この関数スコープが開いている間に、状態数・遷移数などの評価用カウントとして加算された時間。");

            Long rawMillis = timeMillisByKey.get(timeKey(scope.section, scope.label));
            if (rawMillis != null) {
                long withoutCountMillis = Math.max(0, rawMillis - countMillis);
                recordDataMetricWithFormula(
                        scope.baseMetricKey + "_time_without_scope_count_overhead",
                        section,
                        scopeLabel + " / 評価用カウント時間除外後",
                        Long.toString(withoutCountMillis),
                        "ms",
                        scope.baseMetricKey + " - " + countKey);
            }
        }
    }

    private static boolean isFailureStatus(ResultStatus status) {
        return status == ResultStatus.GOAL_NOT_REACHABLE
                || status == ResultStatus.NOT_CONTROLLABLE
                || status == ResultStatus.OUT_OF_MEMORY
                || status == ResultStatus.EXCEPTION
                || status == ResultStatus.UNKNOWN_FAILURE;
    }

    private static void printSectionDescription(LTSOutput output, String section) {
        String description = sectionDescription(section);
        if (!description.isEmpty()) {
            output.outln("説明: " + description);
        }

        List<String> notes = sectionMetricNotes(section);
        if (!notes.isEmpty()) {
            output.outln("主な項目:");
            for (String note : notes) {
                output.outln("  - " + note);
            }
        }
    }

    private static String sectionDescription(String section) {
        if ("UpdatingControllersDefinition".equals(section)) {
            return "更新コントローラ定義を読み取り、旧コントローラ・Mapping Environment・要求・手法固有の補助モデルを準備する前処理。";
        }
        if ("UpdatingControllerSynthesizer".equals(section)) {
            return "準備済みモデルから Traditional DUC、OTF-DUC、Stepwise Delayed DUC などの実際の合成処理を起動する入口。";
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            return "Traditional DUC で更新用環境から安全性制約反映後の環境を作り、最後に update controller を合成する処理。";
        }
        if ("Traditional DUC".equals(section)) {
            return "Traditional DUC の final safety environment 構築と、GR(1) による出力コントローラ合成処理。";
        }
        if ("Traditional DUC safetyEnv 構築時間内訳".equals(section)) {
            return "Traditional DUC の安全性制約反映後の環境を作る内部処理。Fluent 評価、安全性違反 pruning、DontDoTwice 合成を含む。";
        }
        if ("Traditional DUC GR1 時間内訳".equals(section)) {
            return "Traditional DUC の安全性制約反映後の環境を最終コントローラ合成器に渡し、出力コントローラを得る処理。";
        }
        if ("Stepwise Delayed DUC".equals(section)) {
            return "Stepwise Delayed DUC の final safety environment 構築と、GR(1) による出力コントローラ合成処理。";
        }
        if ("Stepwise Delayed DUC GR1 時間内訳".equals(section)) {
            return "Stepwise Delayed DUC の final safety environment を最終コントローラ合成器に渡し、出力コントローラを得る処理。";
        }
        if ("generateDUC (OTF-DUC)".equals(section)) {
            return "OTF-DUC本体として、探索入力モデルの準備、on-the-fly探索、出力UC構築、MTSA側への反映を行う処理。";
        }
        if ("DCS (OTF-DUC)".equals(section)) {
            return "OTF-DUC の探索器内部で、状態展開、fairness/loop 判定、出力UC構築を行う処理。";
        }
        if ("OTF-DUC 探索時間内訳".equals(section)) {
            return "OTF-DUC の探索順序を決めるヒューリスティックと frontier 操作に関する時間内訳。";
        }
        if ("OTF-DUC 展開時間内訳".equals(section)) {
            return "OTF-DUC で action を展開し、同期先・安全性・次状態を計算する処理の時間内訳。";
        }
        if ("OTF-DUC loop / fairness 時間内訳".equals(section)) {
            return "OTF-DUC の loop 検出と fairness 判定に関する時間内訳。";
        }
        if ("OTF-DUC 伝播時間内訳".equals(section)) {
            return "探索木上で GOAL / ERROR の判定結果を親状態へ伝播する処理の時間内訳。";
        }
        if ("OTF-DUC 出力構築時間内訳".equals(section)) {
            return "探索結果から最終 update controller を構築し、出力時 pruning を判定する処理の時間内訳。";
        }
        if ("OTF-DUC 出力 pruning 削減率".equals(section)) {
            return "OTF-DUC の探索済み director 候補から、pruning・merge・belief repair 後に実際に出力される update-controller 断片への削減率。";
        }
        if ("OTF-DUC ブロック・棄却率".equals(section)) {
            return "OTF-DUC 探索・出力構築中に safety violation、hotSwapOut guard、出力 pruning によって候補が棄却された割合。";
        }
        if ("OTF-DUC projection 別分裂度".equals(section)) {
            return "OTF-DUC の探索終了時 compostate を各構成要素へ射影し、同じ射影値を持つ状態が何個に分裂しているかを記録する。old controller、mapping env、safety、transition requirement など、状態爆発の由来を調べるための値。";
        }
        if ("評価用カウント時間 / 関数スコープ別".equals(section)) {
            return "関数タイマーのスコープごとに、そのスコープ内で発生した状態数・遷移数などの評価用カウント時間を集計した値。各関数の生時間から差し引くために使う。";
        }
        if ("OTF-DUC update phase 別探索規模".equals(section)) {
            return "OTF-DUC の探索終了時グラフを update phase ごとに分けた状態数・遷移数。各 phase の CountTime と合計 CountTime を記録する。";
        }
        if ("OTF-DUC update event 別探索遷移数".equals(section)) {
            return "OTF-DUC の探索終了時グラフに含まれる hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut と通常遷移の本数。";
        }
        if ("OTF-DUC update phase 別探索遷移詳細".equals(section)) {
            return "OTF-DUC の探索終了時グラフを update phase ごとに分け、更新事象別遷移数、通常遷移数、通常遷移率、平均/最大分岐数を記録する。";
        }
        if ("OTF-DUC update phase 間探索遷移数".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、どの update phase からどの update phase へ遷移しているかを数えた値。";
        }
        if ("OTF-DUC hotSwapIn から更新完了までの探索距離".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、hotSwapIn 遷移から更新完了 phase までの最短距離を数えた値。hotSwapOut 後の phase が存在する場合はそこを、存在しない場合は stopOldSpec・reconfigure・startNewSpec が全て実行済みの phase を完了 phase とする。";
        }
        if ("OTF-DUC update phase 別通常 action 探索遷移数".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、update phase ごとに通常 action 名別の遷移数と controllable/uncontrollable 内訳を記録する。";
        }
        if ("OTF-DUC 次更新事象までの探索距離".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、各 update phase の状態から次に何らかの更新事象が enabled になるまでの通常遷移距離を記録する。距離 0 は更新事象が即時 enabled であることを表す。";
        }
        if ("OTF-DUC progress-free cycle 統計".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、更新事象を含まない通常遷移のみの SCC/cycle を update phase ごとに数える。controllable-only cycle も併記する。";
        }
        if ("OTF-DUC update phase 別 enabled update event 状態数".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、各 update phase において hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut が enabled な状態数を記録する。";
        }
        if ("OTF-DUC 更新順序パターン別探索規模".equals(section)) {
            return "OTF-DUC の探索終了時グラフを hotSwapIn 後の更新事象順序パターンごとに分け、状態出現数・ユニーク状態数・遷移数を記録する。";
        }
        if ("OTF-DUC 更新事象間の通常遷移連続長".equals(section)) {
            return "OTF-DUC の探索終了時グラフで、各更新事象の直後から次の更新事象が enabled になるまでに必要な通常遷移の最短連続長を記録する。";
        }
        if ("OTF-DUC 方針1 時間・メモリ内訳".equals(section)) {
            return "方針1実行時に、通常の OTF 探索 + 簡単マージと belief repair を分けて測った時間・メモリ内訳。";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            return "Traditional DUC の中間状態空間サイズ。更新用環境、安全性評価用合成環境、安全性違反除去後、最終コントローラ合成入力の各段階を比較するための値。";
        }
        if ("Stepwise Delayed DUC 最大状態数と遷移数".equals(section)) {
            return "Stepwise Delayed DUC の中間状態空間サイズ。local / cross / final product / delayed connection / final safety environment の各段階を比較するための値。";
        }
        if ("Stepwise Delayed DUC 分類統計".equals(section)) {
            return "Stepwise Delayed DUC の requirement 分類統計。local / cross goal 数、cross goal 比率、cross component scope の大きさを記録する。";
        }
        if ("Stepwise Delayed DUC scope別要求数".equals(section)) {
            return "Stepwise Delayed DUC の requirement を stage scope ごとに集計した値。各 scope について local / cross と old safety / new safety / transition の内訳を記録する。";
        }
        if ("Stepwise Delayed DUC scope別fluent数".equals(section)) {
            return "Stepwise Delayed DUC の requirement fluent と metaEnv 構築に使う tracked fluent を stage scope ごとに記録する。";
        }
        if ("Stepwise Delayed DUC scope別状態空間".equals(section)) {
            return "Stepwise Delayed DUC の local / cross pruning で作られる metaEnv と safetyEnv を stage scope ごとに記録した状態数・遷移数。";
        }
        if ("Stepwise Delayed DUC scope別時間".equals(section)) {
            return "Stepwise Delayed DUC の local / cross / final product の metaEnv、safetyEnv、product 構築時間を stage scope ごとに記録する。";
        }
        if ("Stepwise Delayed DUC hotSwapIn connection".equals(section)) {
            return "Stepwise Delayed DUC の old controller meta と mapping product を接続する hotSwapIn connection 構築統計。";
        }
        if ("Traditional DUC update phase 別状態空間".equals(section)) {
            return "Traditional DUC の中間状態空間を、hotSwapIn 前後、および stopOldSpec・reconfigure・startNewSpec の実行済み組合せごとに分けた状態数・遷移数。";
        }
        if ("Traditional DUC update event 別遷移数".equals(section)) {
            return "Traditional DUC の中間状態空間に含まれる hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut と通常遷移の本数。";
        }
        if ("Traditional DUC 状態空間削減率".equals(section)) {
            return "Traditional DUC の安全性評価用合成環境、安全性違反除去後、最終コントローラ合成入力の間で、状態数・遷移数がどれだけ削減されたかを示す値。";
        }
        if ("Traditional DUC update phase 別遷移詳細".equals(section)) {
            return "Traditional DUC の中間状態空間を update phase ごとに分け、更新事象別遷移数、通常遷移数、通常遷移率、平均/最大分岐数を記録する。";
        }
        if ("Traditional DUC update phase 間遷移数".equals(section)) {
            return "Traditional DUC の中間状態空間で、どの update phase からどの update phase へ遷移しているかを数えた値。";
        }
        if ("Traditional DUC hotSwapIn から更新完了までの距離".equals(section)) {
            return "Traditional DUC の中間状態空間で、hotSwapIn 遷移から更新完了 phase までの最短距離を数えた値。hotSwapOut がない場合は stopOldSpec・reconfigure・startNewSpec が全て実行済みの phase を完了 phase とする。";
        }
        if ("Traditional DUC update phase 別通常 action 遷移数".equals(section)) {
            return "Traditional DUC の中間状態空間で、update phase ごとに通常 action 名別の遷移数と controllable/uncontrollable 内訳を記録する。";
        }
        if ("Traditional DUC 次更新事象までの距離".equals(section)) {
            return "Traditional DUC の中間状態空間で、各 update phase の状態から次に何らかの更新事象が enabled になるまでの通常遷移距離を記録する。距離 0 は更新事象が即時 enabled であることを表す。";
        }
        if ("Traditional DUC progress-free cycle 統計".equals(section)) {
            return "Traditional DUC の中間状態空間で、更新事象を含まない通常遷移のみの SCC/cycle を update phase ごとに数える。controllable action 集合から controllable-only cycle も併記する。";
        }
        if ("Traditional DUC update phase 別 enabled update event 状態数".equals(section)) {
            return "Traditional DUC の中間状態空間で、各 update phase において hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut が enabled な状態数を記録する。";
        }
        if ("Traditional DUC 更新順序パターン別状態空間".equals(section)) {
            return "Traditional DUC の中間状態空間を hotSwapIn 後の更新事象順序パターンごとに分け、状態出現数・ユニーク状態数・遷移数を記録する。";
        }
        if ("Traditional DUC 更新事象間の通常遷移連続長".equals(section)) {
            return "Traditional DUC の中間状態空間で、各更新事象の直後から次の更新事象が enabled になるまでに必要な通常遷移の最短連続長を記録する。";
        }
        if ("入力規模".equals(section)) {
            return "合成問題として与えられた環境コンポーネント、要求、controllable/uncontrollable action などの入力サイズ。";
        }
        if ("入力規模 / 事前合成".equals(section)) {
            return "旧コントローラや新コントローラなど、手法本体の前に合成・参照される主要モデルのサイズ。";
        }
        if ("メモリ使用量チェックポイント".equals(section)) {
            return "合成の各段階で取得したヒープ使用量。ピーク増加箇所を確認するための参考値。";
        }
        if ("共通 / HPWindow".equals(section)) {
            return "GUI から合成を起動した場合の全体時間、前処理時間、描画時間、メモリなどの共通計測。";
        }
        if ("TransitionSystemDispatcher".equals(section)) {
            return "合成後の CompactState に対する共通後処理。Traditional DUC や Stepwise Delayed DUC では .old action の relabel などを行う。";
        }
        if ("Output Update Controller".equals(section)) {
            return "最終的に出力された update controller の状態数・遷移数。";
        }
        if ("Output Update Controller update phase 別状態空間".equals(section)) {
            return "最終的に出力された update controller を update phase ごとに分けた状態数・遷移数。";
        }
        if ("Output Update Controller update event 別遷移数".equals(section)) {
            return "最終的に出力された update controller に含まれる hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut と通常遷移の本数。";
        }
        if ("Output Update Controller 削減率".equals(section)) {
            return "手法ごとの主要な出力前状態空間から、最終的な Output Update Controller への状態数・遷移数の削減率。";
        }
        if ("Output Update Controller update phase 別遷移詳細".equals(section)) {
            return "最終的に出力された update controller を update phase ごとに分け、更新事象別遷移数、通常遷移数、通常遷移率、平均/最大分岐数を記録する。";
        }
        if ("Output Update Controller update phase 間遷移数".equals(section)) {
            return "最終的に出力された update controller で、どの update phase からどの update phase へ遷移しているかを数えた値。";
        }
        if ("Output Update Controller hotSwapIn から更新完了までの距離".equals(section)) {
            return "最終的に出力された update controller で、hotSwapIn 遷移から更新完了 phase までの最短距離を数えた値。hotSwapOut 後の phase が存在する場合はそこを、存在しない場合は stopOldSpec・reconfigure・startNewSpec が全て実行済みの phase を完了 phase とする。";
        }
        if ("Output Update Controller update phase 別通常 action 遷移数".equals(section)) {
            return "最終的に出力された update controller で、update phase ごとに通常 action 名別の遷移数を記録する。CompactState から controllability が取れない場合、その内訳は unknown として記録する。";
        }
        if ("Output Update Controller 次更新事象までの距離".equals(section)) {
            return "最終的に出力された update controller で、各 update phase の状態から次に何らかの更新事象が enabled になるまでの通常遷移距離を記録する。距離 0 は更新事象が即時 enabled であることを表す。";
        }
        if ("Output Update Controller progress-free cycle 統計".equals(section)) {
            return "最終的に出力された update controller で、更新事象を含まない通常遷移のみの SCC/cycle を update phase ごとに数える。CompactState では controllable-only cycle が unknown の場合 -1 になる。";
        }
        if ("Output Update Controller update phase 別 enabled update event 状態数".equals(section)) {
            return "最終的に出力された update controller で、各 update phase において hotSwapIn/stopOldSpec/reconfigure/startNewSpec/hotSwapOut が enabled な状態数を記録する。";
        }
        if ("Output Update Controller 更新順序パターン別状態空間".equals(section)) {
            return "最終的に出力された update controller を hotSwapIn 後の更新事象順序パターンごとに分け、状態出現数・ユニーク状態数・遷移数を記録する。";
        }
        if ("Output Update Controller 更新事象間の通常遷移連続長".equals(section)) {
            return "最終的に出力された update controller で、各更新事象の直後から次の更新事象が enabled になるまでに必要な通常遷移の最短連続長を記録する。";
        }
        if ("要件確認".equals(section)) {
            return "update controller の要件に関する簡易チェック。例: hotSwapIn が旧コントローラの何状態から出ているか。";
        }
        if ("比較用時間集計".equals(section)) {
            return "各 DUC 手法を比較しやすいように、共通前処理や評価用オーバーヘッドを差し引いた集計。";
        }
        if ("評価出力時間".equals(section)) {
            return "評価結果を Output タブへ表示するために使った時間。合成本来の処理ではない評価用オーバーヘッド。";
        }
        return "";
    }

    private static List<String> sectionMetricNotes(String section) {
        List<String> notes = new ArrayList<>();
        if ("UpdatingControllersDefinition".equals(section)) {
            notes.add("compose の全体実行時間: 更新コントローラ定義から合成用データ構造を作る前処理全体の時間。");
            notes.add("Old Controller 合成時間: 旧環境と旧要求から旧コントローラを事前合成する時間。");
            notes.add("Mapping Environment Component 合成時間: old/new 環境と対応関係から mapping component を作る時間。");
            notes.add("New Controller 合成時間: OTF-DUC で接続先として使う新コントローラを事前合成する時間。");
            notes.add("Safety の tester 変換全体時間: safety / transition requirement を探索用 tester LTS に変換する時間。");
            notes.add("Traditional DUC ゴール条件/安全性ゴール条件生成時間: Traditional DUC 用のゴール条件と安全性ゴール条件を生成する時間。");
        } else if ("入力規模".equals(section)) {
            notes.add("controllable action 数: 入力で controllable として宣言された action 数。");
            notes.add("uncontrollable action 数: 入力 action 全体から controllable action を除いた action 数。hotSwapIn は Traditional DUC と OTF-DUC の両方で uncontrollable として数え、hotSwapOut は OTF-DUC のみで数える。");
            notes.add("全 action 数（controllable + uncontrollable）: 入力 action 全体の大きさ。通常 action と更新事象を含む。");
        } else if ("UpdatingControllerSynthesizer".equals(section)) {
            notes.add("generateController の全体実行時間: 手法本体を呼び出して update controller を生成する外側の時間。");
            notes.add("手法別の本体呼び出し時間: Traditional、OTF、Stepwise Delayed など各手法の本体処理時間。");
            notes.add("Traditional DUC 更新用環境構築時間: 旧コントローラと Mapping Environment から更新中の振る舞いを表す環境を構築する時間。");
        } else if ("solveControlProblem (Traditional DUC)".equals(section)) {
            notes.add("安全性評価用合成環境構築時間: 安全性評価用に更新用環境と Fluent を組み合わせる時間。");
            notes.add("安全性制約反映後の環境構築時間: 安全性違反状態を除去する時間。");
            notes.add("最終コントローラ合成時間: 安全性制約反映後の環境から controller を合成する中核時間。");
        } else if ("generateDUC (OTF-DUC)".equals(section)) {
            notes.add("探索入力モデル準備時間: on-the-fly 探索に渡す Marking LTS、旧コントローラ、MapEnv、安全性などを並べる時間。");
            notes.add("New Controller の接続先の事前計算: hotSwapOut 後に新コントローラへ接続する状態対応表を作る時間。");
            notes.add("探索呼び出しから出力UC反映までの時間: OTF-DUC の探索器呼び出しから、出力UCをMTSA側の表現へ反映するまでの中心時間。");
        } else if ("Stepwise Delayed DUC".equals(section)) {
            notes.add("最終コントローラ合成時間: final safety environment から controller を合成する中核時間。");
        } else if ("Traditional DUC GR1 時間内訳".equals(section)
                || "Stepwise Delayed DUC GR1 時間内訳".equals(section)) {
            notes.add("ゴール条件構築時間: guarantee / assumption などから最終コントローラ合成用のゴール条件を構築する時間。");
            notes.add("勝ち領域計算時間: 最終コントローラ合成ゲーム上で勝ち領域を求める時間。");
            notes.add("コントローラ戦略構築時間: 勝ち領域から controller strategy を作る時間。");
            notes.add("コントローラ戦略から出力用モデルを構築する時間: strategy を出力 controller の MTS に変換する時間。");
        } else if ("比較用時間集計".equals(section)) {
            notes.add("除外する共通前処理時間: 両手法に共通する旧コントローラ合成、Goal 準備、Mapping component 生成の合計。");
            notes.add("大枠比較用時間: 実測総時間から構文解析、評価用カウント、評価出力、GUI描画を除いた時間。");
            notes.add("厳密比較用時間: 大枠比較用時間からさらに共通前処理時間を除いた時間。");
            notes.add("手法固有時間: 各 DUC 手法に固有の準備・中核・後処理を合計した時間。");
            notes.add("手法別中核内訳は区間が非対称: Traditional DUC は更新用環境構築+最終合成、Stepwise Delayed DUC は最終GR(1)のみ。手法間の主比較には controller_synthesis_related_time を使う。");
        } else if ("OTF-DUC 方針1 時間・メモリ内訳".equals(section)) {
            notes.add("通常OTF探索+簡単マージ時間: on-the-fly探索開始から、belief repair 直前の簡単マージ完了までの時間。");
            notes.add("通常OTF探索+簡単マージ中増加メモリ: 同区間のピークメモリ - 同区間直前メモリ。");
            notes.add("belief repair時間: raw graph 収集を含む方針1 repair 区間の時間。");
            notes.add("belief repair中増加メモリ: repair 区間のピークメモリ - repair 直前メモリ。");
        }
        return notes;
    }

    private static void add(String section, String line) {
        if (!isEnabled()) {
            return;
        }
        String normalizedSection = section == null || section.isEmpty() ? "その他" : section;
        sections.computeIfAbsent(normalizedSection, k -> new ArrayList<>()).add(line);
    }

    private static void captureReferenceStateSpace(
            String section,
            String label,
            long states,
            long transitions) {
        if (!isEnabled()) {
            return;
        }

        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            if (label != null && label.contains("[2. Meta]")) {
                traditionalMetaStates = states;
                traditionalMetaTransitions = transitions;
            } else if (label != null && label.contains("[3. Pruned]")) {
                traditionalPrunedStates = states;
                traditionalPrunedTransitions = transitions;
                if (traditionalMetaStates >= 0 && traditionalMetaTransitions >= 0) {
                    recordStateTransitionReduction(
                            "Traditional DUC 状態空間削減率",
                            "Meta -> Pruned Safety",
                            traditionalMetaStates,
                            traditionalMetaTransitions,
                            traditionalPrunedStates,
                            traditionalPrunedTransitions);
                }
            } else if (label != null && label.contains("[4. Final]")) {
                traditionalFinalStates = states;
                traditionalFinalTransitions = transitions;
                if (traditionalPrunedStates >= 0 && traditionalPrunedTransitions >= 0) {
                    recordStateTransitionReduction(
                            "Traditional DUC 状態空間削減率",
                            "Pruned Safety -> Final Safety",
                            traditionalPrunedStates,
                            traditionalPrunedTransitions,
                            traditionalFinalStates,
                            traditionalFinalTransitions);
                }
            }
        }

        if ("DCS (OTF-DUC)".equals(section)
                && "DCS で探索した状態数と遷移数の最大値".equals(label)) {
            otfExploredStates = states;
            otfExploredTransitions = transitions;
        }
    }

    private static void recordOutputReductionIfAvailable(long outputStates, long outputTransitions) {
        if (!isEnabled()) {
            return;
        }
        if ("Traditional DUC".equals(mode)
                && traditionalFinalStates >= 0
                && traditionalFinalTransitions >= 0) {
            recordStateTransitionReduction(
                    "Output Update Controller 削減率",
                    "Traditional Final Safety -> Output Update Controller",
                    traditionalFinalStates,
                    traditionalFinalTransitions,
                    outputStates,
                    outputTransitions);
        }

        if ("OTF-DUC".equals(mode)
                && otfExploredStates >= 0
                && otfExploredTransitions >= 0) {
            recordStateTransitionReduction(
                    "Output Update Controller 削減率",
                    "OTF explored graph -> Output Update Controller",
                    otfExploredStates,
                    otfExploredTransitions,
                    outputStates,
                    outputTransitions);
        }
    }

    private static void putOrReplace(String section, String label, String line) {
        if (!isEnabled()) {
            return;
        }
        String normalizedSection = section == null || section.isEmpty() ? "その他" : section;
        String key = timerKey(normalizedSection, label);
        LineRef ref = lineRefs.get(key);
        if (ref != null) {
            List<String> lines = sections.get(ref.section);
            if (lines != null && ref.index >= 0 && ref.index < lines.size()) {
                lines.set(ref.index, line);
                return;
            }
        }

        List<String> lines = sections.computeIfAbsent(normalizedSection, k -> new ArrayList<>());
        lines.add(line);
        lineRefs.put(key, new LineRef(normalizedSection, lines.size() - 1));
    }

    private static void putOrReplaceTime(String section, String label, long millis, String suffix) {
        if (!isEnabled()) {
            return;
        }
        long normalizedMillis = Math.max(0, millis);
        timeMillisByKey.put(timerKey(section, label), normalizedMillis);
        putOrReplace(section, label, label + suffix + " : " + normalizedMillis + " ms");
        recordDataMetric(metricKey(section, label), section, label + suffix, Long.toString(normalizedMillis), "ms");
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }

    private static String formatDouble(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.000000";
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String formatRatio(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.000%";
        }
        return String.format(Locale.ROOT, "%.3f%%", value * 100.0);
    }

    private static String nanosToMillisText(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String formatMillisForOutput(long millis) {
        return formatMillisForOutput((double) millis);
    }

    private static String formatMillisForOutput(double millis) {
        if (Double.isNaN(millis) || Double.isInfinite(millis)) {
            return "未記録";
        }
        boolean negative = millis < 0;
        long totalCentiseconds = Math.round(Math.abs(millis) / 10.0);
        long minutes = totalCentiseconds / 6000L;
        long centisecondsInMinute = totalCentiseconds % 6000L;
        long seconds = centisecondsInMinute / 100L;
        long centiseconds = centisecondsInMinute % 100L;
        return (negative ? "-" : "")
                + minutes
                + "分"
                + String.format(Locale.ROOT, "%02d.%02d秒",
                        Long.valueOf(seconds),
                        Long.valueOf(centiseconds));
    }

    private static String formatBytesAsGbForOutput(long bytes) {
        double gb = bytes / 1024.0 / 1024.0 / 1024.0;
        return String.format(Locale.ROOT, "%.2f GB", gb);
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String bytesToByteText(long bytes) {
        return Long.toString(bytes);
    }

    private static String formatBytes(long bytes) {
        return bytes + " B"
                + " (" + formatKiB(bytes) + ", " + formatMiB(bytes) + ")";
    }

    private static String formatSignedBytes(long bytes) {
        String sign = bytes > 0 ? "+" : "";
        return sign + formatBytes(bytes);
    }

    private static void ensureMemoryCheckpointHeader(String section) {
        if (!isEnabled()) {
            return;
        }
        String key = section + "\u0000__memory_checkpoint_header__";
        if (lineRefs.containsKey(key)) {
            return;
        }
        List<String> lines = sections.computeIfAbsent(section, k -> new ArrayList<>());
        lines.add("段階                                           現在ヒープ 旧pool peak合計 開始時からの増減 直前からの増減");
        lines.add("------------------------------------------------------------------------------------------------");
        lineRefs.put(key, new LineRef(section, lines.size() - 2));
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.2fMB", bytes / 1024.0 / 1024.0);
    }

    private static String formatSignedMiB(long bytes) {
        String sign = bytes > 0 ? "+" : "";
        return sign + formatMiB(bytes);
    }

    private static String formatKiB(long bytes) {
        return String.format(Locale.ROOT, "%.2fKB", bytes / 1024.0);
    }

    private static String padRight(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String padLeft(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        while (builder.length() + text.length() < width) {
            builder.append(' ');
        }
        builder.append(text);
        return builder.toString();
    }

    private static String timerKey(String section, String label) {
        return (section == null ? "" : section) + "\u0000" + (label == null ? "" : label);
    }

    private static long elapsedMillis(long startNanos) {
        return nanosToMillis(System.nanoTime() - startNanos);
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos) / 1_000_000L;
    }

    private static void flushActiveTimers() {
        if (activeTimers.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (isFailureStatus(resultStatus)) {
            int index = 0;
            String lastActiveTimer = "";
            for (ActiveTimer timer : activeTimers.values()) {
                String timerText = timer.section + " / " + timer.label;
                recordDataMetric(
                        "failure_active_timer_" + index,
                        "Run",
                        "failure active timer " + index,
                        timerText,
                        "text");
                lastActiveTimer = timerText;
                index++;
            }
            recordDataMetric(
                    "failure_active_timer_count",
                    "Run",
                    "failure active timer count",
                    Integer.toString(index),
                    "timers");
            if (!lastActiveTimer.isEmpty()) {
                recordDataMetric(
                        "failure_stage",
                        "Run",
                        "failure stage",
                        lastActiveTimer,
                        "text");
            }
        }
        for (ActiveTimer timer : activeTimers.values()) {
            putOrReplaceTime(timer.section, timer.label,
                    nanosToMillis(now - timer.startNanos),
                    " (失敗時点まで)");
        }
        activeTimers.clear();
    }

    private static void recordStableStateSpaceAlias(
            String section,
            String label,
            long states,
            long transitions,
            long countTimeMillis) {
        String prefix = stableStateSpaceMetricPrefix(section, label);
        if (prefix.isEmpty()) {
            return;
        }
        String aliasLabel = stableStateSpaceMetricLabel(section, label);
        String safeCountTime = Long.toString(Math.max(0, countTimeMillis));
        String formula = "既存の状態数・遷移数計測の再掲。CountTime は既に元の状態空間行で評価用オーバーヘッドに加算済み。";
        recordDataMetricWithFormula(
                prefix + "_states",
                "主要中間状態空間",
                aliasLabel + " / States",
                Long.toString(states),
                "states",
                formula);
        recordDataMetricWithFormula(
                prefix + "_transitions",
                "主要中間状態空間",
                aliasLabel + " / Transitions",
                Long.toString(transitions),
                "transitions",
                formula);
        recordDataMetricWithFormula(
                prefix + "_count_time",
                "主要中間状態空間",
                aliasLabel + " / CountTime",
                safeCountTime,
                "ms",
                formula);
    }

    private static String stableStateSpaceMetricPrefix(String section, String label) {
        if ("入力規模 / Traditional Mapping Environment".equals(section)
                && "Traditional Mapping Environment".equals(label)) {
            return "traditional_mapping_environment";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            if ("[1. E_u] (Old Controller || Mapping Environment)".equals(label)) {
                return "traditional_intermediate_eu";
            }
            if ("[2. Meta] Meta Environment (PEAK)".equals(label)) {
                return "traditional_intermediate_meta_environment";
            }
            if ("[4. Final] Safety Environment".equals(label)) {
                return "traditional_intermediate_final_safety_environment";
            }
        }
        if ("Stepwise Delayed DUC 最大状態数と遷移数".equals(section)) {
            if ("[Stepwise Delayed DUCS] Product before delayed connection".equals(label)) {
                return "stepwise_delayed_intermediate_product_before_delayed_connection";
            }
            if ("[Stepwise Delayed DUCS] OldCon fluent meta".equals(label)) {
                return "stepwise_delayed_intermediate_old_controller_fluent_meta";
            }
            if ("[Stepwise Delayed DUCS] After delayed hotSwapIn connection".equals(label)) {
                return "stepwise_delayed_intermediate_after_delayed_hotswapin_connection";
            }
            if ("[Stepwise Delayed DUCS] After global DontDoTwice".equals(label)) {
                return "stepwise_delayed_intermediate_after_global_dont_do_twice";
            }
            if ("[Stepwise Delayed DUCS] After final safety backward pruning".equals(label)) {
                return "stepwise_delayed_intermediate_after_final_sbp";
            }
        }
        return "";
    }

    private static String stableStateSpaceMetricLabel(String section, String label) {
        if ("入力規模 / Traditional Mapping Environment".equals(section)
                && "Traditional Mapping Environment".equals(label)) {
            return "Traditional DUC mapping environment";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            if ("[1. E_u] (Old Controller || Mapping Environment)".equals(label)) {
                return "Traditional DUC E_u";
            }
            if ("[2. Meta] Meta Environment (PEAK)".equals(label)) {
                return "Traditional DUC metaEnv";
            }
            if ("[4. Final] Safety Environment".equals(label)) {
                return "Traditional DUC final safetyEnv";
            }
        }
        if ("Stepwise Delayed DUC 最大状態数と遷移数".equals(section)) {
            if ("[Stepwise Delayed DUCS] Product before delayed connection".equals(label)) {
                return "Stepwise Delayed DUC product before delayed connection";
            }
            if ("[Stepwise Delayed DUCS] OldCon fluent meta".equals(label)) {
                return "Stepwise Delayed DUC old controller fluent meta";
            }
            if ("[Stepwise Delayed DUCS] After delayed hotSwapIn connection".equals(label)) {
                return "Stepwise Delayed DUC after delayed hotSwapIn connection";
            }
            if ("[Stepwise Delayed DUCS] After global DontDoTwice".equals(label)) {
                return "Stepwise Delayed DUC after global DontDoTwice";
            }
            if ("[Stepwise Delayed DUCS] After final safety backward pruning".equals(label)) {
                return "Stepwise Delayed DUC after final SBP";
            }
        }
        return label == null ? "" : label;
    }

    private static void recordComparisonSummary() {
        final String comparisonSection = "比較用時間集計";
        if (sections.containsKey(comparisonSection)) {
            return;
        }

        Long totalTime = firstRecordedTime(
                timeKey("共通 / HPWindow", "合成ボタンを押してから合成完了までの時間"),
                timeKey("一時 runner", "合成全体実行時間"));

        long commonPreprocessTime = sumRecordedTimes(
                timeKey("UpdatingControllersDefinition", "Old Controller 合成時間"),
                timeKey("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                timeKey("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"));

        long parseTime = optionalTime("共通 / HPWindow", "構文解析時間");
        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        long methodSpecificTime = methodSpecificTime();
        boolean hasControllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間");
        Long controllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間")
                ? optionalTime("共通 / HPWindow", "コントローラ合成時間")
                : null;
        long broadObservedTime = totalTime == null
                ? -1
                : Math.max(0, totalTime - parseTime - stateSpaceCountOverheadObservedMillis
                        - drawTime);
        long strictObservedTime = totalTime == null
                ? -1
                : Math.max(0, broadObservedTime - commonPreprocessTime);
        long unclassifiedTime = totalTime == null
                ? -1
                : Math.max(0, strictObservedTime - methodSpecificTime);

        if (totalTime == null) {
            addMetricValue(comparisonSection,
                    "実測総時間",
                    "未記録",
                    "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        } else {
            addMetric(comparisonSection,
                    "実測総時間",
                    totalTime,
                    "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        }
        addMetric(comparisonSection,
                "除外する共通前処理時間",
                commonPreprocessTime,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        addMetric(comparisonSection,
                "評価用カウント時間（状態数・遷移数）",
                stateSpaceCountOverheadObservedMillis,
                "実測総時間に含まれる状態数・遷移数 CountTime。合成本来の処理ではない評価用オーバーヘッドで、比較用時間から差し引く。");
        addMetric(comparisonSection,
                "評価用カウント時間（合計）",
                stateSpaceCountOverheadMillis,
                "実測総時間内と実測総時間外の状態数・遷移数 CountTime の合計。診断用の総量であり、実測総時間から丸ごとは差し引かない。");
        addMetric(comparisonSection,
                "評価用カウント時間（実測時間外）",
                stateSpaceCountOverheadPostObservedMillis,
                "実測総時間を確定した後に、出力 controller や post-synthesis 診断のために数えた CountTime。主比較用時間からは差し引かない。");
        addMetric(comparisonSection,
                "評価結果出力時間（実測総時間外・参考）",
                Math.max(0, evaluationOutputOverheadMillis),
                "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間。"
                        + "これらは実測総時間の確定後に実行されるため、実測総時間からは差し引かない。"
                        + " CSV 出力時間は CSV 出力後に追加行として記録する。");
        addMetric(comparisonSection,
                "入力規模集計時間（評価用・参考）",
                optionalTime("UpdatingControllersDefinition", "入力規模集計時間"),
                "入力規模セクションを作る時間。状態数・遷移数 CountTime と重なる可能性があるため、差し引き式には入れない参考値。");
        if (hasRecordedTime("共通 / HPWindow", "コントローラ描画時間")) {
            addMetric(comparisonSection,
                    "GUI描画時間（比較から除外候補）",
                    drawTime,
                    "HPWindow の「コントローラ描画時間」。アルゴリズム比較からは除外する候補。");
        }
        if (totalTime != null) {
            addMetric(comparisonSection,
                    "大枠比較用時間（構文解析・カウント・描画除外）",
                    broadObservedTime,
                    "実測総時間 - 構文解析時間 - 実測総時間内の評価用カウント時間 - GUI描画時間。"
                            + "評価結果出力は実測総時間の確定後なので差し引かない。共通前処理は差し引かない。");
            addMetric(comparisonSection,
                    "厳密比較用時間（構文解析・共通前処理・カウント・描画除外）",
                    strictObservedTime,
                    "大枠比較用時間 - 除外する共通前処理時間。");
            addMetric(comparisonSection,
                    "実測総時間ベースの未分類時間（参考）",
                    unclassifiedTime,
                    "厳密比較用時間 - 手法固有として個別計測できた時間。"
                            + " GUI 周辺なども含むため参考値。");
        }
        addMetric(comparisonSection,
                "手法固有として個別計測できた時間",
                methodSpecificTime,
                methodSpecificFormula());
        if (hasControllerSynthesisTime) {
            addMetric(comparisonSection,
                    "主比較用コントローラ合成時間",
                    controllerSynthesisTime,
                    "共通 / HPWindow のコントローラ合成時間と同値。"
                            + "Traditional DUC と Stepwise Delayed DUC の主時間比較にはこの対称な区間を使う。");
            long controllerSynthesisWithoutCommon = controllerSynthesisTime - commonPreprocessTime;
            long unclassifiedNonCommonTime = controllerSynthesisWithoutCommon - methodSpecificTime;
            addMetric(comparisonSection,
                    "共通処理を除いたコントローラ合成時間",
                    controllerSynthesisWithoutCommon,
                    "コントローラ合成時間 - 除外する共通前処理時間。");
            addMetric(comparisonSection,
                    "未分類の非共通時間",
                    unclassifiedNonCommonTime,
                    "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。");
        } else {
            addMetricValue(comparisonSection,
                    "共通処理を除いたコントローラ合成時間",
                    "未記録",
                    "コントローラ合成時間 - 除外する共通前処理時間。");
            addMetricValue(comparisonSection,
                    "未分類の非共通時間",
                    "未記録",
                    "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。");
        }
        addMetricValue(comparisonSection,
                "手法別中核内訳の手法間比較可能性",
                "false",
                "Traditional は E_u 構築+最終合成、Stepwise Delayed は最終GR(1)のみを表すため、"
                        + "手法別中核内訳同士を比較しない。主比較には主比較用コントローラ合成時間を使う。");
        recordModeSpecificComparisonDetails(comparisonSection);
    }

    private static long methodSpecificTime() {
        if ("OTF-DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "generateDUC 全体時間"));
        }

        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                    timeKey("UpdatingControllerSynthesizer", "generateController の全体実行時間"),
                    timeKey("TransitionSystemDispatcher", "removeOldTransitions 実行時間"));
        }

        if ("Stepwise Delayed DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllerSynthesizer", "generateController の全体実行時間"),
                    timeKey("TransitionSystemDispatcher", "Stepwise Delayed DUC removeOldTransitions 実行時間"));
        }

        return 0;
    }

    private static long methodCoreTime() {
        if ("OTF-DUC".equals(mode)) {
            return optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間");
        }

        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "solveControlProblem 全体時間"));
        }

        if ("Stepwise Delayed DUC".equals(mode)) {
            return optionalTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間");
        }

        return 0;
    }

    private static void recordModeSpecificComparisonDetails(String comparisonSection) {
        if ("OTF-DUC".equals(mode)) {
            long otfPreparation = sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "boxList 準備時間"));
            addMetric(comparisonSection,
                    "OTF-DUC 固有準備時間",
                    otfPreparation,
                    "New Controller 合成時間 + Safety の tester 変換全体時間 + New Safety から Fluent を抽出する時間"
                            + " + 探索入力モデル準備時間。MarkingLTS 生成時間、New Controller の接続先の事前計算、"
                            + "New Safety と Fluent の対応表の変換作業時間は探索入力モデル準備時間に含まれるため個別加算しない。");
            addMetric(comparisonSection,
                    "OTF-DUC の探索呼び出しから出力UC反映までの時間（中核）",
                    optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間"),
                    "OTF-DUC本体処理内の、探索器呼び出しから出力UCをMTSA側の表現へ反映するまでの時間。");
        } else if ("Traditional DUC".equals(mode)) {
            long traditionalPreparation = sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"));
            addMetric(comparisonSection,
                    "Traditional DUC 固有準備時間",
                    traditionalPreparation,
                    "Traditional DUC ゴール条件生成時間 + Traditional DUC 安全性ゴール条件生成時間"
                            + " + Traditional DUC Mapping Environment Component 並列合成時間。");
            addMetric(comparisonSection,
                    "Traditional DUC の更新用環境構築+最終コントローラ合成時間（中核）",
                    methodCoreTime(),
                    "Traditional DUC 更新用環境構築時間 + 最終コントローラ合成処理全体時間。");
            addMetric(comparisonSection,
                    "Traditional DUC の.old後処理時間",
                    optionalTime("TransitionSystemDispatcher", "removeOldTransitions 実行時間"),
                    "TransitionSystemDispatcher の removeOldTransitions 実行時間。OTF-DUC では実行しない。");
        } else if ("Stepwise Delayed DUC".equals(mode)) {
            addMetric(comparisonSection,
                    "Stepwise Delayed DUC の最終コントローラ合成時間（中核）",
                    optionalTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間"),
                    "Stepwise Delayed DUC の final safety environment から GR(1) で controller を合成する時間。");
            addMetric(comparisonSection,
                    "Stepwise Delayed DUC の removeOldTransitions 実行時間",
                    optionalTime("TransitionSystemDispatcher", "Stepwise Delayed DUC removeOldTransitions 実行時間"),
                    "TransitionSystemDispatcher の removeOldTransitions 実行時間。出力前の共通後処理として実行する。");
        }
    }

    private static String methodSpecificFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "New Controller 合成時間 + Safety の tester 変換全体時間"
                    + " + New Safety から Fluent を抽出する時間 + OTF-DUC本体処理全体時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC ゴール条件生成時間 + Traditional DUC 安全性ゴール条件生成時間"
                    + " + Traditional DUC Mapping Environment Component 並列合成時間"
                    + " + generateController の全体実行時間 + removeOldTransitions 実行時間。";
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return "Stepwise Delayed DUC の generateController 全体実行時間 + removeOldTransitions 実行時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static String methodCoreFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "探索器呼び出しから出力UC反映までの内部タイマー値。評価用カウント時間を含み得るため参考値。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC 更新用環境構築時間 + 最終コントローラ合成処理全体時間の内部タイマー値。評価用カウント時間を含み得るため参考値。";
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return "Stepwise Delayed DUC の final safety environment から GR(1) で controller を合成する時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static void addMetric(String section, String label, long millis, String formula) {
        recordDataMetricWithFormula(section, label, Long.toString(millis), "ms", formula);
        addMetricValue(section, label, millis + " ms", formula);
    }

    private static void addMetricValue(String section, String label, String value, String formula) {
        String key = metricKey(section, label);
        if (!dataMetrics.containsKey(key)) {
            recordDataMetricWithFormula(key, section, label, value, "text", formula);
        } else {
            attachDataMetricFormula(key, formula);
        }
        add(section, label + " : " + value);
        add(section, "  算出: " + formula);
    }

    private static void printEvaluationSummary(LTSOutput output) {
        Long totalTime = firstRecordedTime(
                timeKey("共通 / HPWindow", "合成ボタンを押してから合成完了までの時間"),
                timeKey("一時 runner", "合成全体実行時間"));
        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        Long comparisonTime = totalTime == null
                ? null
                : Math.max(0, totalTime - stateSpaceCountOverheadObservedMillis - drawTime);
        recordPeakStateSpaceSummaryMetrics();

        output.outln("================ EVALUATION SUMMARY ================");
        printSummarySectionHeader(output, "結果");
        printSummaryValueCompact(output, "手法", mode);
        printModeFlagSummary(output);
        if ("OTF-DUC".equals(mode)) {
            printSummaryValueCompact(output, "OTF-DUC実行モード", otfExecutionMode);
        }
        printSummaryValueCompact(output, "結果", resultStatus.toString());
        if (isFailureStatus(resultStatus) && !failureMessage.isEmpty()) {
            printSummaryValueCompact(output, "失敗理由", failureMessage);
        }
        printSummaryMillisCompact(output, "比較用時間（合成全体-CountTime-描画）", comparisonTime,
                "合成の全体時間 - 実測時間内のカウントによるオーバーヘッド - コントローラ描画時間。");

        printSummarySectionHeader(output, "規模");
        printSummaryDataMetricCompact(output, "最大状態数時の状態数", "peak_state_space_states",
                peakStateSpaceFormula("状態数"));
        printSummaryDataMetricCompact(output, "最大状態数時の遷移数", "peak_state_space_states_stage_transitions",
                "中間状態空間ピーク状態数を記録した段階における遷移数。");
        printSummaryDataMetricIfPresentCompact(output, "最大状態数時の段階", "peak_state_space_states_stage",
                "中間状態空間ピーク状態数を記録した段階。");
        printSummaryDataMetricCompact(output, "最大遷移数時の状態数", "peak_state_space_transitions_stage_states",
                "中間状態空間ピーク遷移数を記録した段階における状態数。");
        printSummaryDataMetricCompact(output, "最大遷移数時の遷移数", "peak_state_space_transitions",
                peakStateSpaceFormula("遷移数"));
        printSummaryDataMetricIfPresentCompact(output, "最大遷移数時の段階", "peak_state_space_transitions_stage",
                "中間状態空間ピーク遷移数を記録した段階。");
        printSummaryDataMetricCompact(output, "出力状態数", "output_update_controller_states", "");
        printSummaryDataMetricCompact(output, "出力遷移数", "output_update_controller_transitions", "");
        if (dataMetrics.containsKey("minimized_output_update_controller_states")
                || dataMetrics.containsKey("minimized_output_update_controller_transitions")) {
            printSummaryDataMetricIfPresentCompact(output, "minimized 出力状態数",
                    "minimized_output_update_controller_states", "");
            printSummaryDataMetricIfPresentCompact(output, "minimized 出力遷移数",
                    "minimized_output_update_controller_transitions", "");
        }

        printGrSummary(output);
        printSummarySectionHeader(output, "メモリ");
        if (dataMetrics.containsKey("controller_synthesis_sampled_peak_heap_used")) {
            printSummaryDataMetricCompact(output, "合成区間の同時点ヒープ最大値",
                    "controller_synthesis_sampled_peak_heap_used",
                    "MemoryMXBeanのaggregate heap usedを周期sampleした主指標。");
            printSummaryDataMetricCompact(output, "最初の有効sampleからのヒープ増加",
                    "controller_synthesis_sampled_heap_increase", "");
        } else {
            printSummaryDataMetricCompact(output, "旧pool別peak合計の増加（参考）",
                    "controller_synthesis_memory_increase",
                    "後方互換指標。poolごとに異なる時刻のpeakを合計している。");
        }

        if ("Stepwise Delayed DUC".equals(mode)) {
            printStepwiseDelayedClassificationSummary(output);
        }
        output.outln("====================================================");
        output.outln("");
    }

    private static void printCommonSummary(LTSOutput output, long commonTotal) {
        printSummarySectionHeader(output, "共通");
        printSummaryMillis(output, "共通準備時間（共通合計）", commonTotal,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        printSummaryMillis(output, "Old Controller 合成時間",
                optionalTime("UpdatingControllersDefinition", "Old Controller 合成時間"),
                "");
        printSummaryMillis(output, "Goal 定義と controllable action 集合生成時間",
                optionalTime("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                "");
        printSummaryMillis(output, "Mapping Environment Component 合成時間",
                optionalTime("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"),
                "");
        printSummaryDataMetric(output, "Old Controller 状態数", "old_controller_states", "");
        printSummaryDataMetric(output, "Old Controller 遷移数", "old_controller_transitions", "");
        printSummaryDataMetric(output, "mapping component 数", metricKey("入力規模", "mapping component 数"), "");
        printSummaryDataMetric(output, "old safety 数", metricKey("入力規模", "old safety 数"), "");
        printSummaryDataMetric(output, "new safety 数", metricKey("入力規模", "new safety 数"), "");
        printSummaryDataMetric(output, "OTF-DUC new safety fluent 数（重複排除後）",
                "otf_new_safety_fluents", "");
        printSummaryDataMetric(output, "transition requirement 数", metricKey("入力規模", "transition requirement 数"), "");
        printSummaryDataMetric(output, "controllable action 数", metricKey("入力規模", "controllable action 数"), "");
        printSummaryDataMetric(output, "uncontrollable action 数",
                metricKey("入力規模", "uncontrollable action 数"), "");
        printSummaryDataMetric(output, "全 action 数（controllable + uncontrollable）",
                metricKey("入力規模", "全 action 数（controllable + uncontrollable）"), "");
    }

    private static void printStepwiseDelayedSummary(LTSOutput output) {
        String section = "Stepwise Delayed DUC 分類統計";
        printSummarySectionHeader(output, "Stepwise Delayed");
        printSummaryMillis(output, "GR(1)入力 safetyEnv 構築時間",
                optionalTime("Stepwise Delayed DUC", "GR(1)入力 safetyEnv 構築時間"),
                "要求を scope ごとに分類し始める直前から、final safety environment を CompactState に変換して GR(1) に渡す直前までの時間。");
        printSummaryMillis(output, "SBP 全体時間合計",
                optionalTime("Stepwise Delayed DUC", "SBP 全体時間合計"),
                "local / cross / final で実行された Safety Backward Pruning の全呼び出し時間の合計。SBP 無効時は未記録または 0。");
        printSummaryMillis(output, "最終コントローラ合成時間",
                optionalTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間"),
                "final safety environment から GR(1) で controller を合成する時間。SBP 有効時は SBP 後の final safety environment が入力になる。");
        printSummaryMillis(output, "勝ち領域計算時間",
                optionalTime("Stepwise Delayed DUC GR1 時間内訳", "Winning region 計算時間"),
                "");
        printSummaryMillis(output, "コントローラ戦略構築時間",
                optionalTime("Stepwise Delayed DUC GR1 時間内訳", "Strategy 構築時間"),
                "");
        printSummaryDataMetric(output, "stage 数",
                metricKey(section, "stage 数"), "");
        printSummaryDataMetric(output, "goal 数",
                metricKey(section, "goal 数"), "");
        printSummaryDataMetric(output, "local goal 数",
                metricKey(section, "local goal 数"), "");
        printSummaryDataMetric(output, "cross goal 数",
                metricKey(section, "cross goal 数"), "");
        printSummaryDataMetric(output, "cross goal 比率（千分率）",
                metricKey(section, "cross goal 比率（千分率）"),
                "cross goal 数 / goal 数 * 1000。Output の分類 summary では percent 表記も出す。");
        printSummaryDataMetric(output, "local old safety goal 数",
                metricKey(section, "local old safety goal 数"), "");
        printSummaryDataMetric(output, "local new safety goal 数",
                metricKey(section, "local new safety goal 数"), "");
        printSummaryDataMetric(output, "local transition goal 数",
                metricKey(section, "local transition goal 数"), "");
        printSummaryDataMetric(output, "cross old safety goal 数",
                metricKey(section, "cross old safety goal 数"), "");
        printSummaryDataMetric(output, "cross new safety goal 数",
                metricKey(section, "cross new safety goal 数"), "");
        printSummaryDataMetric(output, "cross transition goal 数",
                metricKey(section, "cross transition goal 数"), "");
        printSummaryDataMetric(output, "cross component 数",
                metricKey(section, "cross component 数"), "");
        printSummaryDataMetric(output, "cross goal 最大 scope size",
                metricKey(section, "cross goal 最大 scope size"), "");
        printSummaryDataMetric(output, "cross component 最大 scope size",
                metricKey(section, "cross component 最大 scope size"), "");
        printSummaryDataMetric(output, "all-stage cross goal 数",
                metricKey(section, "all-stage cross goal 数"), "");
        printSummaryDataMetric(output, "all-stage cross component 数",
                metricKey(section, "all-stage cross component 数"), "");
        printSummaryDataMetric(output, "all-stage cross goal あり",
                metricKey(section, "all-stage cross goal あり"), "");
        printSummaryMillis(output, "cross component 構築時間",
                optionalTime(section, "cross component 構築時間"),
                "分類結果から cross component を構築し、stage scope 順に並べる時間。後続の cross 合成でも同じ component list を使うため、分類統計専用の二重実行ではない。");
        printSummaryDataMetric(output, "分類統計計算・記録 CountTime",
                metricKey(section, "分類統計計算・記録 CountTime"),
                "stage/local/cross goal 数、cross 比率、cross component scope 統計を計算し、ログと評価 recorder に記録する評価用 CountTime。");
    }

    private static void printOtfSummary(
            LTSOutput output,
            long methodSpecificTotal,
            long methodPreparationTotal,
            long actualSynthesisTime,
            long methodOtherTime) {
        printSummarySectionHeader(output, "OTF-DUC固有");
        printSummaryMillis(output, "OTF-DUC 固有準備時間", methodPreparationTotal,
                "New Controller 合成時間 + Safety の tester 変換全体時間 + New Safety から Fluent を抽出する時間 + 探索入力モデル準備時間。"
                        + " MarkingLTS 生成時間などの探索入力モデル準備内訳は二重計上しない。");
        printSummaryMillis(output, "OTF-DUC 中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "OTF-DUC 固有内のその他時間", methodOtherTime,
                "OTF-DUC 固有として個別計測できた時間 - OTF-DUC 固有準備時間 - OTF-DUC 中核合成時間。"
                        + " 主にOTF-DUC本体処理内の型変換・出力構築など。");
        printSummaryMillis(output, "OTF-DUC 固有として個別計測できた時間", methodSpecificTotal,
                "OTF-DUC 固有準備時間 + OTF-DUC 中核合成時間 + OTF-DUC 固有内のその他時間。");
        printSummaryMillis(output, "New Controller 合成時間",
                optionalTime("UpdatingControllersDefinition", "New Controller 合成時間"),
                "");
        printSummaryDataMetric(output, "New Controller 状態数", "new_controller_states", "");
        printSummaryDataMetric(output, "New Controller 遷移数", "new_controller_transitions", "");
        printSummaryMillis(output, "Safety の tester 変換全体時間",
                optionalTime("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                "");
        printSummaryMillis(output, "New Safety から Fluent を抽出する時間",
                optionalTime("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                "");
        printSummaryMillis(output, "探索入力モデル準備時間",
                optionalTime("generateDUC (OTF-DUC)", "boxList 準備時間"),
                "MarkingLTS、旧コントローラ、MapEnv、safety、対応表などを on-the-fly探索に渡す形へ準備する時間。");
        printSummaryMillis(output, "MarkingLTS 生成時間（探索入力モデル準備内訳）",
                optionalTime("generateDUC (OTF-DUC)", "MarkingLTS 生成時間"),
                "探索入力モデル準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "New Controller 接続先事前計算（探索入力モデル準備内訳）",
                optionalTime("generateDUC (OTF-DUC)", "New Controller の接続先の事前計算"),
                "探索入力モデル準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "New Safety と Fluent 対応表変換（探索入力モデル準備内訳）",
                optionalTime("generateDUC (OTF-DUC)", "New Safety と Fluent の対応表の変換作業時間"),
                "探索入力モデル準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "on-the-fly探索時間",
                optionalTime("DCS (OTF-DUC)", "DCS で探索した時間"),
                "");
        printSummaryDataMetric(output, "探索終了時グラフの最大状態数", "otf_dcs_peak_states", "");
        printSummaryDataMetric(output, "探索終了時グラフの最大遷移数", "otf_dcs_peak_transitions", "");
        printSummaryDataMetric(output, "状態展開呼び出し回数", "otf_expand_duc_calls", "");
        printSummaryMillis(output, "出力UC構築時間",
                optionalTime("DCS (OTF-DUC)", "buildDirectorDUC 実行時間"),
                "");
        printStrategy1Summary(output);
    }

    private static void printStrategy1Summary(LTSOutput output) {
        final String section = "OTF-DUC 方針1 時間・メモリ内訳";
        boolean hasStrategy1Metrics =
                hasRecordedTime(section, "通常OTF探索+簡単マージ時間")
                        || hasRecordedTime(section, "belief repair時間");
        if (!hasStrategy1Metrics) {
            return;
        }

        printSummarySectionHeader(output, "方針1");
        printSummaryMillis(output, "通常OTF探索+簡単マージ時間",
                optionalTime(section, "通常OTF探索+簡単マージ時間"),
                "on-the-fly探索開始から、belief repair 直前の簡単マージ完了まで。");
        printSummaryDataMetric(output, "通常OTF探索+簡単マージ直前メモリ",
                metricKey(section, "通常OTF探索+簡単マージ直前メモリ"), "");
        printSummaryDataMetric(output, "通常OTF探索+簡単マージ中ピークメモリ",
                metricKey(section, "通常OTF探索+簡単マージ中ピークメモリ"), "");
        printSummaryDataMetric(output, "通常OTF探索+簡単マージ中増加メモリ",
                metricKey(section, "通常OTF探索+簡単マージ中増加メモリ"),
                "通常OTF探索+簡単マージ中ピークメモリ - 通常OTF探索+簡単マージ直前メモリ。");
        printSummaryMillis(output, "belief repair時間",
                optionalTime(section, "belief repair時間"),
                "raw graph 収集を含む方針1 repair 区間。");
        printSummaryDataMetric(output, "belief repair直前メモリ",
                metricKey(section, "belief repair直前メモリ"), "");
        printSummaryDataMetric(output, "belief repair中ピークメモリ",
                metricKey(section, "belief repair中ピークメモリ"), "");
        printSummaryDataMetric(output, "belief repair中増加メモリ",
                metricKey(section, "belief repair中増加メモリ"),
                "belief repair中ピークメモリ - belief repair直前メモリ。");

        final String detailSection = "OTF-DUC 出力構築時間内訳";
        printSummaryDataMetric(output, "unsafe controllable discard 数",
                metricKey(detailSection, "belief 再探索で破棄した unsafe controllable action 数"),
                "belief node 全体で共通の安全な controllable 戦略として使えず破棄した action 数。");
        printSummaryDataMetric(output, "未展開 uncontrollable warning 数",
                metricKey(detailSection, "belief 再探索で未展開 uncontrollable warning 数"),
                "未展開 uncontrollable が残り、warning を出した回数。");
        printSummaryDataMetric(output, "未展開 uncontrollable belief node 数",
                metricKey(detailSection, "belief 再探索で未展開 uncontrollable が残った belief node 数"),
                "未展開 uncontrollable が残ったため勝ち判定から除外された belief node 数。");
        printSummaryDataMetric(output, "未展開 uncontrollable action 数",
                metricKey(detailSection, "belief 再探索で未展開 uncontrollable action 数"),
                "belief graph 内で未展開のまま残った uncontrollable action の数。");
        printSummaryDataMetric(output, "resourceLimit fallback 数",
                metricKey(detailSection, "belief 再探索で資源上限により fallback した旧状態数"), "");
        printSummaryDataMetric(output, "resourceLimit belief node 上限 fallback 数",
                metricKey(detailSection, "belief 再探索で belief node 上限により fallback した旧状態数"), "");
        printSummaryDataMetric(output, "resourceLimit 追加 concrete state 上限 fallback 数",
                metricKey(detailSection, "belief 再探索で追加 concrete state 上限により fallback した旧状態数"), "");
        printSummaryDataMetric(output, "resourceLimit 追加 transition 上限 fallback 数",
                metricKey(detailSection, "belief 再探索で追加 transition 上限により fallback した旧状態数"), "");
        printSummaryDataMetric(output, "resourceLimit 時間上限 fallback 数",
                metricKey(detailSection, "belief 再探索で時間上限により fallback した旧状態数"), "");
    }

    private static void printTraditionalSummary(
            LTSOutput output,
            long methodSpecificTotal,
            long methodPreparationTotal,
            long actualSynthesisTime,
            long methodOtherTime) {
        printSummarySectionHeader(output, "従来DUC固有");
        printSummaryMillis(output, "従来DUC 固有準備時間", methodPreparationTotal,
                "Traditional DUC ゴール条件生成時間 + Traditional DUC 安全性ゴール条件生成時間"
                        + " + Traditional DUC Mapping Environment Component 並列合成時間 + 更新用環境構築時間"
                        + " + Old/New Safety から Fluent を抽出する時間 + 安全性評価用合成環境構築時間"
                        + " + 安全性制約反映後の環境構築時間 + 安全性制約反映後の環境から出力用モデルへの変換時間。");
        printSummaryMillis(output, "従来DUC 中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "従来DUC 固有内のその他時間", methodOtherTime,
                "従来DUC 固有として個別計測できた時間 - 従来DUC 固有準備時間 - 従来DUC 中核合成時間。"
                        + " 主に controller MTS/CompactState 構築や .old 後処理など。");
        printSummaryMillis(output, "従来DUC 固有として個別計測できた時間", methodSpecificTotal,
                "従来DUC 固有準備時間 + 従来DUC 中核合成時間 + 従来DUC 固有内のその他時間。");
        printSummaryMillis(output, "Traditional DUC ゴール条件生成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                "");
        printSummaryMillis(output, "Traditional DUC 安全性ゴール条件生成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                "");
        printSummaryMillis(output, "Traditional DUC Mapping Environment Component 並列合成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                "");
        printSummaryMillis(output, "更新用環境構築時間",
                optionalTime("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                "");
        printSummaryMillis(output, "安全性評価用合成環境構築時間",
                optionalTime("solveControlProblem (Traditional DUC)", "Fluent とベース環境を並列合成した metaEnv 構築時間"),
                "");
        printSummaryMillis(output, "安全性制約反映後の環境構築時間",
                optionalTime("solveControlProblem (Traditional DUC)", "metaEnv からエラーを枝刈りして safetyEnv を構築する時間"),
                "");
        printSummaryMillis(output, "GR(1)入力 safetyEnv 構築時間",
                optionalTime("Traditional DUC", "GR(1)入力 safetyEnv 構築時間"),
                "mapping environment component の並列合成直前から、最終 safety environment を CompactState に変換して GR(1) に渡す直前までの時間。");
        printSummaryMillis(output, "SBP 全体時間合計",
                optionalTime("Traditional DUC", "SBP 全体時間合計"),
                "final Safety Backward Pruning の呼び出し時間。SBP 無効時は未記録または 0。");
        printSummaryMillis(output, "最終コントローラ合成時間",
                optionalTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間"),
                "");
        printSummaryMillis(output, "勝ち領域計算時間",
                optionalTime("Traditional DUC GR1 時間内訳", "Winning region 計算時間"),
                "");
        printSummaryMillis(output, "コントローラ戦略構築時間",
                optionalTime("Traditional DUC GR1 時間内訳", "Strategy 構築時間"),
                "");
        printSummaryMillis(output, "removeOldTransitions 実行時間",
                optionalTime("TransitionSystemDispatcher", "removeOldTransitions 実行時間"),
                "");
        printSummaryDataMetric(output, "Mapping Environment 状態数", "traditional_mapping_environment_states", "");
        printSummaryDataMetric(output, "Mapping Environment 遷移数", "traditional_mapping_environment_transitions", "");
        printSummaryDataMetric(output, "old safety fluent 数", "traditional_old_safety_fluents", "");
        printSummaryDataMetric(output, "new safety fluent 数", "traditional_new_safety_fluents", "");
        printSummaryDataMetric(output, "old/new safety fluent 数（重複排除後）",
                "traditional_old_new_safety_fluents_unique", "");
        printSummaryDataMetric(output, "transition requirement fluent 数",
                "traditional_transition_requirement_fluents", "");
        printSummaryDataMetric(output, "meta env fluent 数（重複排除後）",
                "traditional_meta_environment_fluents", "");
        printSummaryDataMetric(output, "更新用環境状態数", "traditional_eu_states", "");
        printSummaryDataMetric(output, "更新用環境遷移数", "traditional_eu_transitions", "");
        printSummaryDataMetric(output, "安全性評価用合成環境状態数", "traditional_meta_states", "");
        printSummaryDataMetric(output, "安全性評価用合成環境遷移数", "traditional_meta_transitions", "");
        printSummaryDataMetric(output, "安全性違反除去後状態数", "traditional_pruned_states", "");
        printSummaryDataMetric(output, "安全性違反除去後遷移数", "traditional_pruned_transitions", "");
        printSummaryDataMetric(output, "最終コントローラ合成入力状態数", "traditional_final_states", "");
        printSummaryDataMetric(output, "最終コントローラ合成入力遷移数", "traditional_final_transitions", "");
    }

    private static void printOutputSummary(LTSOutput output) {
        printSummarySectionHeader(output, "出力");
        printSummaryDataMetric(output, "update controller 状態数", "output_update_controller_states", "");
        printSummaryDataMetric(output, "update controller 遷移数", "output_update_controller_transitions", "");
        printSummaryDataMetric(output, "出力状態数・遷移数 CountTime", "output_update_controller_count_time", "");
        printSummaryDataMetric(output, "hotSwapIn が出ている状態数", "hot_swap_in_outgoing_states", "");
        printSummaryDataMetric(output, "旧コントローラ状態数", "old_controller_states_for_hot_swap_in",
                "hotSwapIn が出るべき基準状態数。");
        if ("OTF-DUC".equals(mode)) {
            printSummaryDataMetric(output, "探索上の旧コントローラ相当状態数（マージ前）",
                    "otf_pre_update_raw_states",
                    "OTF-DUC の探索で markingState=0 として現れた状態数。new safety fluent などで旧コントローラ状態が分割される。");
            printSummaryDataMetric(output, "出力上の旧コントローラ相当状態数（マージ後）",
                    "otf_pre_update_output_states",
                    "出力時マージ後に update controller 側へ残る旧コントローラ相当状態数。");
            printSummaryDataMetric(output, "OTF-DUCにより増えた旧コントローラ相当状態数",
                    "otf_pre_update_state_overhead",
                    "出力上の旧コントローラ相当状態数（マージ後） - 旧コントローラ状態数。マージできなかった分を OTF-DUC の状態数オーバーヘッドとして数える。");
            printSummaryDataMetric(output, "簡単マージ後も分裂している旧コントローラ状態数",
                    "otf_simple_merge_split_old_controller_states",
                    "簡単マージ後、同じ旧コントローラ状態に対応する出力クラスが 2 個以上残った旧状態数。");
            printSummaryDataMetric(output, "1つの旧コントローラ状態あたりの最大分裂数",
                    "otf_simple_merge_max_split_per_old_controller_state",
                    "簡単マージ後、1 つの旧コントローラ状態に対応して残った出力クラス数の最大値。");
        }
        printSummaryDataMetric(output, "hotSwapIn coverage CountTime", "hot_swap_in_coverage_count_time", "");
    }

    private static void printMemorySummary(LTSOutput output) {
        printSummarySectionHeader(output, "メモリ");
        printSummaryDataMetric(output, "同時点ヒープ開始値",
                "controller_synthesis_sampled_base_heap_used", "");
        printSummaryDataMetric(output, "同時点ヒープ最大値",
                "controller_synthesis_sampled_peak_heap_used",
                "MemoryMXBeanのaggregate heap usedを周期sampleした主指標。");
        printSummaryDataMetric(output, "同時点ヒープ増加最大値",
                "controller_synthesis_sampled_heap_increase", "");
        printSummaryDataMetric(output, "旧ベースラインメモリ（参考）",
                "controller_synthesis_base_memory",
                "後方互換用のheap pool API指標。");
        printSummaryDataMetric(output, "旧pool別peak合計（参考）",
                "controller_synthesis_peak_memory",
                "poolごとに異なる時刻のpeakを合計するため、同一時点最大値ではない。");
        printSummaryDataMetric(output, "旧pool別peak合計の増加（参考）",
                "controller_synthesis_memory_increase", "");
        printSummaryDataMetric(output, "合成終了時の現在ヒープ", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_current_heap", "");
        printSummaryDataMetric(output, "合成終了時の旧pool別peak合計", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_peak_heap", "");
    }

    private static long commonPreparationTime() {
        return sumRecordedTimes(
                timeKey("UpdatingControllersDefinition", "Old Controller 合成時間"),
                timeKey("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                timeKey("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"));
    }

    private static void recordPeakStateSpaceSummaryMetrics() {
        if ("OTF-DUC".equals(mode)) {
            recordOtfPeakStateSpaceSummaryMetrics();
            return;
        }
        if ("Traditional DUC".equals(mode)) {
            recordTraditionalPeakStateSpaceSummaryMetrics();
            return;
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            recordStepwiseDelayedPeakStateSpaceSummaryMetrics();
        }
    }

    private static void recordOtfPeakStateSpaceSummaryMetrics() {
        Long peakStates = dataMetricLong("otf_dcs_peak_states");
        Long peakTransitions = dataMetricLong("otf_dcs_peak_transitions");
        if (peakStates != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_states",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク状態数",
                    Long.toString(peakStates),
                    "states",
                    peakStateSpaceFormula("状態数"));
            recordDataMetricWithFormula(
                    "peak_state_space_states_stage",
                    "Evaluation Summary / 全体",
                    "状態数ピークの段階",
                    "on-the-fly探索最大状態数",
                    "text",
                    "中間状態空間ピーク状態数を記録した段階。");
        }
        if (peakTransitions != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_transitions",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク遷移数",
                    Long.toString(peakTransitions),
                    "transitions",
                    peakStateSpaceFormula("遷移数"));
            recordDataMetricWithFormula(
                    "peak_state_space_transitions_stage",
                    "Evaluation Summary / 全体",
                    "遷移数ピークの段階",
                    "on-the-fly探索最大遷移数",
                    "text",
                    "中間状態空間ピーク遷移数を記録した段階。");
        }
    }

    private static void recordTraditionalPeakStateSpaceSummaryMetrics() {
        PeakValue peakStates = maxDataMetric(
                new PeakCandidate("traditional_mapping_environment_states",
                        "traditional_mapping_environment_transitions", "[0. Mapping product]"),
                new PeakCandidate("traditional_eu_states", "traditional_eu_transitions", "[1. E_u]"),
                new PeakCandidate("traditional_meta_states", "traditional_meta_transitions", "[2. Meta]"),
                new PeakCandidate("traditional_pruned_states", "traditional_pruned_transitions", "[3. Pruned]"),
                new PeakCandidate("traditional_final_states", "traditional_final_transitions", "[4. Final]"));
        PeakValue peakTransitions = maxDataMetric(
                new PeakCandidate("traditional_mapping_environment_transitions",
                        "traditional_mapping_environment_states", "[0. Mapping product]"),
                new PeakCandidate("traditional_eu_transitions", "traditional_eu_states", "[1. E_u]"),
                new PeakCandidate("traditional_meta_transitions", "traditional_meta_states", "[2. Meta]"),
                new PeakCandidate("traditional_pruned_transitions", "traditional_pruned_states", "[3. Pruned]"),
                new PeakCandidate("traditional_final_transitions", "traditional_final_states", "[4. Final]"));

        if (peakStates != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_states",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク状態数",
                    Long.toString(peakStates.value),
                    "states",
                    peakStateSpaceFormula("状態数"));
            recordDataMetricWithFormula(
                    "peak_state_space_states_stage",
                    "Evaluation Summary / 全体",
                    "状態数ピークの段階",
                    peakStates.stage,
                    "text",
                    "中間状態空間ピーク状態数を記録した段階。");
            if (peakStates.pairedValue >= 0) {
                recordDataMetricWithFormula(
                        "peak_state_space_states_stage_transitions",
                        "Evaluation Summary / 全体",
                        "状態数ピーク時の遷移数",
                        Long.toString(peakStates.pairedValue),
                        "transitions",
                        "中間状態空間ピーク状態数を記録した段階における遷移数。");
            }
        }
        if (peakTransitions != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_transitions",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク遷移数",
                    Long.toString(peakTransitions.value),
                    "transitions",
                    peakStateSpaceFormula("遷移数"));
            recordDataMetricWithFormula(
                    "peak_state_space_transitions_stage",
                    "Evaluation Summary / 全体",
                    "遷移数ピークの段階",
                    peakTransitions.stage,
                    "text",
                    "中間状態空間ピーク遷移数を記録した段階。");
            if (peakTransitions.pairedValue >= 0) {
                recordDataMetricWithFormula(
                        "peak_state_space_transitions_stage_states",
                        "Evaluation Summary / 全体",
                        "遷移数ピーク時の状態数",
                        Long.toString(peakTransitions.pairedValue),
                        "states",
                        "中間状態空間ピーク遷移数を記録した段階における状態数。");
            }
        }
    }

    private static void recordStepwiseDelayedPeakStateSpaceSummaryMetrics() {
        PeakValue peakStates = maxStateSpaceMetricInSection(
                "Stepwise Delayed DUC 最大状態数と遷移数",
                true);
        PeakValue peakTransitions = maxStateSpaceMetricInSection(
                "Stepwise Delayed DUC 最大状態数と遷移数",
                false);

        if (peakStates != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_states",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク状態数",
                    Long.toString(peakStates.value),
                    "states",
                    peakStateSpaceFormula("状態数"));
            recordDataMetricWithFormula(
                    "peak_state_space_states_stage",
                    "Evaluation Summary / 全体",
                    "状態数ピークの段階",
                    peakStates.stage,
                    "text",
                    "中間状態空間ピーク状態数を記録した段階。");
            if (peakStates.pairedValue >= 0) {
                recordDataMetricWithFormula(
                        "peak_state_space_states_stage_transitions",
                        "Evaluation Summary / 全体",
                        "状態数ピーク時の遷移数",
                        Long.toString(peakStates.pairedValue),
                        "transitions",
                        "中間状態空間ピーク状態数を記録した段階における遷移数。");
            }
        }
        if (peakTransitions != null) {
            recordDataMetricWithFormula(
                    "peak_state_space_transitions",
                    "Evaluation Summary / 全体",
                    "中間状態空間ピーク遷移数",
                    Long.toString(peakTransitions.value),
                    "transitions",
                    peakStateSpaceFormula("遷移数"));
            recordDataMetricWithFormula(
                    "peak_state_space_transitions_stage",
                    "Evaluation Summary / 全体",
                    "遷移数ピークの段階",
                    peakTransitions.stage,
                    "text",
                    "中間状態空間ピーク遷移数を記録した段階。");
            if (peakTransitions.pairedValue >= 0) {
                recordDataMetricWithFormula(
                        "peak_state_space_transitions_stage_states",
                        "Evaluation Summary / 全体",
                        "遷移数ピーク時の状態数",
                        Long.toString(peakTransitions.pairedValue),
                        "states",
                        "中間状態空間ピーク遷移数を記録した段階における状態数。");
            }
        }
    }

    private static PeakValue maxStateSpaceMetricInSection(
            String section,
            boolean maximizeStates) {
        PeakValue max = null;
        for (StateSpaceObservation observation : stateSpaceObservations) {
            if (!section.equals(observation.section)) {
                continue;
            }
            long value = maximizeStates ? observation.states : observation.transitions;
            long pairedValue = maximizeStates ? observation.transitions : observation.states;
            String stage = observation.label == null ? "" : observation.label.trim();
            if (max == null || value > max.value) {
                max = new PeakValue(value, pairedValue, stage);
            }
        }
        return max;
    }

    private static String peakStateSpaceFormula(String target) {
        if ("OTF-DUC".equals(mode)) {
            return "OTF-DUC: on-the-fly探索中に観測した最大" + target
                    + "。出力 update controller 状態数・遷移数とは別に、探索中のピーク状態空間を表す。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC: 更新用環境、安全性評価用合成環境、安全性違反除去後、最終コントローラ合成入力の各段階で計測した"
                    + target + "の最大値。出力 update controller 状態数・遷移数とは別に、中間状態空間のピークを表す。";
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return "Stepwise Delayed DUC: local / cross / final product / delayed connection / final safety environment の各段階で計測した"
                    + target + "の最大値。出力 update controller 状態数・遷移数とは別に、中間状態空間のピークを表す。";
        }
        return "手法が未記録のため未記録。";
    }

    private static PeakValue maxDataMetric(PeakCandidate... candidates) {
        PeakValue max = null;
        for (PeakCandidate candidate : candidates) {
            Long value = dataMetricLong(candidate.key);
            if (value == null) {
                continue;
            }
            long pairedValue = -1;
            if (candidate.pairedKey != null && !candidate.pairedKey.isEmpty()) {
                Long paired = dataMetricLong(candidate.pairedKey);
                if (paired != null) {
                    pairedValue = paired;
                }
            }
            if (max == null || value > max.value) {
                max = new PeakValue(value, pairedValue, candidate.stage);
            }
        }
        return max;
    }

    private static Long dataMetricLong(String key) {
        DataMetric metric = dataMetrics.get(key);
        if (metric == null || metric.value == null || metric.value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(metric.value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static long methodPreparationTime() {
        if ("OTF-DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "boxList 準備時間"));
        }
        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                    timeKey("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "Old Safety と New Safety から Fluent を抽出する時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "Fluent とベース環境を並列合成した metaEnv 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "metaEnv からエラーを枝刈りして safetyEnv を構築する時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "safetyEnv から CompactState への変換時間"));
        }
        return 0;
    }

    private static long actualSynthesisTime() {
        if ("OTF-DUC".equals(mode)) {
            return optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間");
        }
        if ("Traditional DUC".equals(mode)) {
            return optionalTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間");
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return optionalTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間");
        }
        return 0;
    }

    private static String actualSynthesisFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "OTF-DUC本体処理内の、探索器呼び出しから出力UCをMTSA側の表現へ反映するまでの時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC の「最終コントローラ合成時間」。";
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return "Stepwise Delayed DUC の「最終コントローラ合成時間」。SBP 有効時は SBP 後の final safety environment が GR(1) 入力になる。";
        }
        return "手法が未記録のため 0。";
    }

    private static String methodPreparationFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "New Controller 合成時間 + Safety の tester 変換全体時間"
                    + " + New Safety から Fluent を抽出する時間 + 探索入力モデル準備時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC ゴール条件生成時間 + Traditional DUC 安全性ゴール条件生成時間"
                    + " + Traditional DUC Mapping Environment Component 並列合成時間"
                    + " + 更新用環境構築時間 + Old/New Safety から Fluent を抽出する時間"
                    + " + 安全性評価用合成環境構築時間 + 安全性制約反映後の環境構築時間"
                    + " + 安全性制約反映後の環境から出力用モデルへの変換時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static void printSummarySectionHeader(LTSOutput output, String title) {
        currentSummarySection = title == null ? "" : title;
        output.outln("");
        output.outln("[" + title + "]");
    }

    private static void printSummaryValue(LTSOutput output, String label, String value, String formula) {
        output.outln(label + " : " + (value == null || value.isEmpty() ? "未記録" : value));
        if (formula != null && !formula.isEmpty()) {
            output.outln("  算出: " + formula);
        }
    }

    private static void printSummaryValueCompact(LTSOutput output, String label, String value) {
        output.outln(label + " : " + (value == null || value.isEmpty() ? "未記録" : value));
    }

    private static void printSummaryMillis(LTSOutput output, String label, long millis, String formula) {
        printSummaryMillis(output, label, Long.valueOf(millis), formula);
    }

    private static void printSummaryMillis(LTSOutput output, String label, Long millis, String formula) {
        String value = millis == null ? "未記録" : formatMillisForOutput(millis.longValue());
        printSummaryValue(output, label, value, formula);
        recordSummaryMillisMetric(label, millis, formula);
    }

    private static void printSummaryMillisCompact(LTSOutput output, String label, Long millis, String formula) {
        String value = millis == null ? "未記録" : formatMillisForOutput(millis.longValue());
        printSummaryValueCompact(output, label, value);
        recordSummaryMillisMetric(label, millis, formula);
    }

    private static void printSummaryDataMetric(LTSOutput output, String label, String metricKey, String formula) {
        DataMetric metric = dataMetrics.get(metricKey);
        if (metric == null) {
            printSummaryValue(output, label, "未記録", formula);
            return;
        }
        attachDataMetricFormula(metricKey, formula);
        printSummaryValue(output, label, dataMetricDisplayValue(metric), formula);
    }

    private static void printSummaryDataMetricCompact(
            LTSOutput output,
            String label,
            String metricKey,
            String formula) {
        if (!printSummaryDataMetricIfPresentCompact(output, label, metricKey, formula)) {
            printSummaryValueCompact(output, label, "未記録");
        }
    }

    private static boolean printSummaryDataMetricIfPresentCompact(
            LTSOutput output,
            String label,
            String metricKey,
            String formula) {
        DataMetric metric = dataMetrics.get(metricKey);
        if (metric == null) {
            return false;
        }
        if (formula != null && !formula.isEmpty()) {
            attachDataMetricFormula(metricKey, formula);
        }
        printSummaryValueCompact(output, label, dataMetricDisplayValue(metric));
        return true;
    }

    private static String dataMetricDisplayValue(DataMetric metric) {
        if (metric == null) {
            return "未記録";
        }
        String value = metric.value == null || metric.value.isEmpty() ? "未記録" : metric.value;
        if ("未記録".equals(value)) {
            return value;
        }
        if ("ms".equals(metric.unit)) {
            Double millis = parseDouble(value);
            if (millis != null) {
                return formatMillisForOutput(millis.doubleValue());
            }
        }
        if ("B".equals(metric.unit)) {
            Long bytes = parseLong(value);
            if (bytes != null) {
                return formatBytesAsGbForOutput(bytes.longValue());
            }
        }
        if (metric.unit != null && !metric.unit.isEmpty() && !"text".equals(metric.unit)) {
            value = value + " " + metric.unit;
        }
        return value;
    }

    private static void printModeFlagSummary(LTSOutput output) {
        if ("Traditional DUC".equals(mode)) {
            printSummaryValueCompact(output, "設定",
                    "safetyBackwardPruning="
                            + booleanMetricValue(metricKey("Traditional DUC 設定", "safetyBackwardPruning 有効")));
        } else if ("Stepwise Delayed DUC".equals(mode)) {
            printSummaryValueCompact(output, "設定",
                    "safetyBackwardPruning="
                            + booleanMetricValue(metricKey("Stepwise Delayed DUC 設定", "safetyBackwardPruning 有効"))
                            + ", incrementalPruning="
                            + booleanMetricValue(metricKey("Stepwise Delayed DUC 設定", "incrementalPruning 有効"))
                            + ", incrementalPruningCleanup="
                            + booleanMetricValue(metricKey("Stepwise Delayed DUC 設定", "incrementalPruningCleanup 有効")));
        }
    }

    private static String booleanMetricValue(String metricKey) {
        DataMetric metric = dataMetrics.get(metricKey);
        if (metric == null) {
            return "未記録";
        }
        String value = metric.value == null ? "" : metric.value.trim();
        if ("1".equals(value)) {
            return "true";
        }
        if ("0".equals(value)) {
            return "false";
        }
        return value.isEmpty() ? "未記録" : value;
    }

    private static void printGrSummary(LTSOutput output) {
        String prefix = finalGrInputMetricPrefix();
        Long grSolvingTime = finalGrSolvingTime();
        boolean hasFinalGrInput = !prefix.isEmpty()
                && (dataMetrics.containsKey(prefix + "_final_gr_input_states")
                || dataMetrics.containsKey(prefix + "_final_gr_input_transitions"));
        if (!hasFinalGrInput && grSolvingTime == null) {
            return;
        }

        printSummarySectionHeader(output, "GR(1)");
        if (hasFinalGrInput) {
            printSummaryDataMetricIfPresentCompact(output, "final GR input 状態数",
                    prefix + "_final_gr_input_states", "");
            printSummaryDataMetricIfPresentCompact(output, "final GR input 遷移数",
                    prefix + "_final_gr_input_transitions", "");
        }
        printSummaryMillisCompact(output, "GR(1)合成時間", grSolvingTime,
                "final safety environment から GR(1) で controller を合成する時間。");
    }

    private static String finalGrInputMetricPrefix() {
        if ("Traditional DUC".equals(mode)) {
            return "traditional";
        }
        if ("Stepwise Delayed DUC".equals(mode)) {
            return "stepwise_delayed";
        }
        return "";
    }

    private static Long finalGrSolvingTime() {
        if ("Traditional DUC".equals(mode)
                && hasRecordedTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間")) {
            return Long.valueOf(optionalTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間"));
        }
        if ("Stepwise Delayed DUC".equals(mode)
                && hasRecordedTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間")) {
            return Long.valueOf(optionalTime("Stepwise Delayed DUC", "safetyEnv を GR1 で解く時間"));
        }
        return null;
    }

    private static void printStepwiseDelayedClassificationSummary(LTSOutput output) {
        String section = "Stepwise Delayed DUC 分類統計";
        printSummarySectionHeader(output, "Stepwise Delayed 要求分類");
        printSummaryDataMetricCompact(output, "旧要求 local 数",
                metricKey(section, "local old safety goal 数"), "");
        printSummaryDataMetricCompact(output, "旧要求 cross 数",
                metricKey(section, "cross old safety goal 数"), "");
        printSummaryDataMetricCompact(output, "新要求 local 数",
                metricKey(section, "local new safety goal 数"), "");
        printSummaryDataMetricCompact(output, "新要求 cross 数",
                metricKey(section, "cross new safety goal 数"), "");
        printSummaryDataMetricCompact(output, "transition requirement local 数",
                metricKey(section, "local transition goal 数"), "");
        printSummaryDataMetricCompact(output, "transition requirement cross 数",
                metricKey(section, "cross transition goal 数"), "");
    }

    private static void recordSummaryMillisMetric(String label, Long millis, String formula) {
        if (millis == null || currentSummarySection == null || currentSummarySection.isEmpty()) {
            return;
        }
        if (formula == null || formula.isEmpty()) {
            return;
        }
        String section = "Evaluation Summary / " + currentSummarySection;
        recordDataMetricWithFormula(section, label, Long.toString(millis), "ms", formula);
    }

    private static void printDataCsv(LTSOutput output) {
        long csvOutputStart = System.nanoTime();
        output.outln("================ EVALUATION DATA CSV ================");
        output.outln(DATA_CSV_HEADER);
        outputDataRow(output, new DataMetric("mode", "Run", "mode", mode, "text"));
        if ("OTF-DUC".equals(mode)) {
            outputDataRow(output, new DataMetric(
                    "otf_execution_mode",
                    "Run",
                    "OTF-DUC実行モード",
                    otfExecutionMode,
                    "text",
                    "otfduc.simple.merge と otfduc.belief.repair の設定から分類。"));
        }
        outputDataRow(output, new DataMetric("result", "Run", "result", resultStatus.toString(), "text"));
        outputDataRow(output, new DataMetric("failure_reason", "Run", "failure reason", failureMessage, "text"));
        for (DataMetric metric : dataMetrics.values()) {
            if (isRecomputedAfterDataCsvMetric(metric.key)) {
                continue;
            }
            outputDataRow(output, metric);
        }
        evaluationDataCsvOutputMillis = elapsedMillis(csvOutputStart);
        long totalEvaluationOutputIncludingCsv = evaluationOutputOverheadMillis
                + Math.max(0, evaluationDataCsvOutputMillis);
        outputDataRow(output, new DataMetric(
                "evaluation_output_data_csv_time",
                "評価出力時間",
                "評価CSV出力時間",
                Long.toString(Math.max(0, evaluationDataCsvOutputMillis)),
                "ms",
                "EVALUATION DATA CSV を Output に出す時間。末尾の追加計測行自身はほぼ含まない。"));
        outputDataRow(output, new DataMetric(
                "evaluation_output_overhead_total_including_csv",
                "評価出力時間",
                "評価出力時間合計（CSV含む）",
                Long.toString(totalEvaluationOutputIncludingCsv),
                "ms",
                "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間 + 評価CSV出力時間。"));
        outputDataRow(output, new DataMetric(
                "comparison_evaluation_output_time",
                "比較用時間集計",
                "評価結果出力時間（実測総時間外・参考）",
                Long.toString(totalEvaluationOutputIncludingCsv),
                "ms",
                "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間 + 評価CSV出力時間。"));
        outputStrictComparisonRows(output, totalEvaluationOutputIncludingCsv);
        output.outln("=====================================================");
        output.outln("");
    }

    public static synchronized void writeDataCsvFileIfConfigured(LTSOutput output) {
        String csvFile = configuredCsvFile();
        if (csvFile == null || csvFile.trim().isEmpty()) {
            return;
        }

        refreshStateSpaceCountOverheadDataMetrics();
        recordCsvFileMetadata(csvFile);

        File file = new File(csvFile);
        try {
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory: " + parent);
            }

            Path destination = file.toPath().toAbsolutePath();
            boolean parentFinalizationRequired = Boolean.parseBoolean(
                    System.getProperty(
                            PARENT_FINALIZATION_REQUIRED_PROPERTY,
                            "false"));
            boolean parentFinalizationPending = parentFinalizationRequired
                    && resultStatus == ResultStatus.SUCCESS;
            String csvResult = parentFinalizationPending
                    ? PARENT_FINALIZATION_PENDING
                    : resultStatus.toString();
            String csvFailureReason = parentFinalizationPending
                    ? ""
                    : failureMessage;
            Path temporary = Files.createTempFile(
                    destination.getParent(),
                    file.getName() + ".",
                    ".tmp");
            try {
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(temporary.toFile(), false),
                        StandardCharsets.UTF_8));
                try {
                    writer.write(DATA_CSV_HEADER);
                    writer.newLine();
                    writeDataRow(writer,
                            new DataMetric("mode", "Run", "mode", mode, "text"),
                            csvResult,
                            csvFailureReason);
                    if ("OTF-DUC".equals(mode)) {
                        writeDataRow(writer, new DataMetric(
                                "otf_execution_mode",
                                "Run",
                                "OTF-DUC実行モード",
                                otfExecutionMode,
                                "text",
                                "otfduc.simple.merge と otfduc.belief.repair の設定から分類。"),
                                csvResult,
                                csvFailureReason);
                    }
                    writeDataRow(writer,
                            new DataMetric("result", "Run", "result", csvResult, "text"),
                            csvResult,
                            csvFailureReason);
                    writeDataRow(writer,
                            new DataMetric(
                                    "failure_reason",
                                    "Run",
                                    "failure reason",
                                    csvFailureReason,
                                    "text"),
                            csvResult,
                            csvFailureReason);
                    for (DataMetric metric : dataMetrics.values()) {
                        writeDataRow(writer, metric, csvResult, csvFailureReason);
                    }
                } finally {
                    writer.close();
                }
                try {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(
                            temporary,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (output != null) {
                output.outln("Evaluation data CSV written to: " + file.getPath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write evaluation data CSV: " + file, e);
        }
    }

    /**
     * Appends metrics observed by the parent batch process, such as child RSS.
     * The same row formatter and description-id generator as child-side
     * metrics are used so downstream compaction does not collapse distinct
     * external rows.
     */
    public static synchronized void appendExternalDataMetrics(
            File file,
            String rowMode,
            String rowResult,
            String rowFailureReason,
            List<ExternalDataMetric> metrics) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        if (metrics == null || metrics.isEmpty()) {
            return;
        }

        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }

        Path destination = file.toPath().toAbsolutePath();
        List<List<String>> existingRecords = readCompleteDataCsvRecords(destination);
        boolean hasValidExistingHeader = !existingRecords.isEmpty()
                && isExpectedDataCsvHeader(existingRecords.get(0));
        Path temporary = Files.createTempFile(
                destination.getParent(),
                file.getName() + ".parent.",
                ".tmp");
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(temporary.toFile(), false),
                    StandardCharsets.UTF_8));
            try {
                writer.write(DATA_CSV_HEADER);
                writer.newLine();
                if (hasValidExistingHeader) {
                    for (int index = 1; index < existingRecords.size(); index++) {
                        List<String> existing = existingRecords.get(index);
                        if (existing.size() != DATA_CSV_COLUMN_COUNT) {
                            continue;
                        }
                        List<String> normalized = new ArrayList<String>(existing);
                        normalizeExistingOutcome(
                                normalized,
                                rowMode,
                                rowResult,
                                rowFailureReason);
                        writeCsvRecord(writer, normalized);
                    }
                }
                for (ExternalDataMetric metric : metrics) {
                    if (metric == null) {
                        continue;
                    }
                    DataMetric dataMetric = new DataMetric(
                            metric.key,
                            metric.section,
                            metric.label,
                            metric.value,
                            metric.unit,
                            metric.formula);
                    writer.write(dataCsvRow(
                            dataMetric,
                            rowMode,
                            rowResult,
                            rowFailureReason));
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<List<String>> readCompleteDataCsvRecords(Path file)
            throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) <= 0L) {
            return new ArrayList<List<String>>();
        }
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return parseCsvRecords(text);
    }

    private static boolean isExpectedDataCsvHeader(List<String> record) {
        if (record == null || record.size() != DATA_CSV_COLUMN_COUNT) {
            return false;
        }
        StringBuilder rebuilt = new StringBuilder();
        for (int index = 0; index < record.size(); index++) {
            if (index > 0) {
                rebuilt.append(',');
            }
            rebuilt.append(record.get(index));
        }
        return DATA_CSV_HEADER.equals(rebuilt.toString());
    }

    private static void normalizeExistingOutcome(
            List<String> record,
            String rowMode,
            String rowResult,
            String rowFailureReason) {
        record.set(0, safeCsvValue(rowMode));
        record.set(1, safeCsvValue(rowResult));
        record.set(2, safeCsvValue(rowFailureReason));
        String metricKey = record.get(4);
        if ("mode".equals(metricKey)) {
            record.set(6, safeCsvValue(rowMode));
        } else if ("result".equals(metricKey)) {
            record.set(6, safeCsvValue(rowResult));
        } else if ("failure_reason".equals(metricKey)) {
            record.set(6, safeCsvValue(rowFailureReason));
        }
    }

    private static String safeCsvValue(String value) {
        return value == null ? "" : value;
    }

    private static void writeCsvRecord(
            BufferedWriter writer,
            List<String> record) throws IOException {
        for (int index = 0; index < record.size(); index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(csv(record.get(index)));
        }
        writer.newLine();
    }

    /** Parses quoted CSV records and discards an unterminated final record. */
    private static List<List<String>> parseCsvRecords(String csvText) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> columns = new ArrayList<String>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean recordHasContent = false;
        String input = csvText == null ? "" : csvText;
        for (int index = 0; index < input.length(); index++) {
            char ch = input.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < input.length()
                            && input.charAt(index + 1) == '"') {
                        value.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    value.append(ch);
                }
            } else if (ch == '"' && value.length() == 0) {
                quoted = true;
                recordHasContent = true;
            } else if (ch == ',') {
                columns.add(value.toString());
                value.setLength(0);
                recordHasContent = true;
            } else if (ch == '\n' || ch == '\r') {
                columns.add(value.toString());
                value.setLength(0);
                if (recordHasContent || columns.size() > 1
                        || !columns.get(0).isEmpty()) {
                    records.add(columns);
                }
                columns = new ArrayList<String>();
                recordHasContent = false;
                if (ch == '\r' && index + 1 < input.length()
                        && input.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                value.append(ch);
                recordHasContent = true;
            }
        }
        if (!quoted && (recordHasContent || !columns.isEmpty()
                || value.length() > 0)) {
            columns.add(value.toString());
            records.add(columns);
        }
        return records;
    }

    private static String configuredCsvFile() {
        String value = System.getProperty(CSV_FILE_PROPERTY);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(LEGACY_CSV_FILE_PROPERTY);
        }
        if (value != null && !value.trim().isEmpty()) {
            return value;
        }
        if (autoCsvFile == null || autoCsvFile.trim().isEmpty()) {
            autoCsvFile = defaultCsvFile();
        }
        return autoCsvFile;
    }

    private static String defaultCsvFile() {
        String directory = System.getProperty(CSV_DIR_PROPERTY);
        if (directory == null || directory.trim().isEmpty()) {
            directory = DEFAULT_CSV_DIR;
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        String modeToken = metricToken(mode);
        String openFileName = EnvConfiguration.getInstance().getOpenFileName();
        String sourceToken = openFileName == null || openFileName.trim().isEmpty()
                ? "no_file"
                : metricToken(new File(openFileName).getName());
        String sequence = Long.toString(++autoCsvSequence);
        String hash = stableShortHash(String.valueOf(openFileName) + "|" + timestamp + "|" + sequence);
        return new File(directory, timestamp + "_" + modeToken + "_" + sourceToken + "_" + hash + ".csv").getPath();
    }

    private static void recordCsvFileMetadata(String csvFile) {
        recordDataMetric("evaluation_csv_file", "Run", "evaluation CSV file", csvFile, "path");
        recordSystemPropertyMetric("batch_case_id", "mtsa.evaluation.caseId", "batch case id");
        recordSystemPropertyMetric("batch_example", "mtsa.evaluation.example", "batch example");
        recordSystemPropertyMetric("batch_method", "mtsa.evaluation.method", "batch method");
        recordSystemPropertyMetric("batch_variant", "mtsa.evaluation.variant", "batch variant");
        recordSystemPropertyMetric("batch_target", "mtsa.evaluation.target", "batch target");
        recordSystemPropertyMetric("batch_lts_file", "mtsa.evaluation.ltsFile", "batch LTS file");
        recordSystemPropertyMetric("batch_config_file", "mtsa.evaluation.configFile", "batch config file");
        recordSystemPropertyMetric("batch_run_index", "mtsa.evaluation.runIndex", "batch run index");
        recordSystemPropertyMetric("batch_run_count", "mtsa.evaluation.runCount", "batch run count");
        recordSystemPropertyMetric("batch_run_label", "mtsa.evaluation.runLabel", "batch run label");
    }

    private static void recordSystemPropertyMetric(String metricKey, String propertyKey, String label) {
        String value = System.getProperty(propertyKey);
        if (value != null && !value.trim().isEmpty()) {
            recordDataMetric(metricKey, "Run", label, value, "text");
        }
    }

    private static boolean isRecomputedAfterDataCsvMetric(String metricKey) {
        return "comparison_evaluation_output_time".equals(metricKey);
    }

    private static void outputStrictComparisonRows(LTSOutput output, long ignoredEvaluationOutputMillis) {
        Long totalTime = firstRecordedTime(
                timeKey("共通 / HPWindow", "合成ボタンを押してから合成完了までの時間"),
                timeKey("一時 runner", "合成全体実行時間"));
        if (totalTime == null) {
            return;
        }

        long commonPreprocessTime = sumRecordedTimes(
                timeKey("UpdatingControllersDefinition", "Old Controller 合成時間"),
                timeKey("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                timeKey("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"));
        long parseTime = optionalTime("共通 / HPWindow", "構文解析時間");
        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        long methodSpecificTime = methodSpecificTime();
        long observedWithoutParseCountAndDraw = Math.max(0, totalTime - parseTime
                - stateSpaceCountOverheadObservedMillis - drawTime);
        long strictObservedTime = Math.max(0, observedWithoutParseCountAndDraw - commonPreprocessTime);
        long strictUnclassifiedTime = Math.max(0, strictObservedTime - methodSpecificTime);

        outputDataRow(output, new DataMetric(
                "comparison_observed_time_without_parse_count_and_draw",
                "比較用時間集計",
                "大枠比較用時間（構文解析・カウント・描画除外）",
                Long.toString(observedWithoutParseCountAndDraw),
                "ms",
                "実測総時間 - 構文解析時間 - 実測総時間内の評価用カウント時間 - GUI描画時間。"
                        + "評価出力は実測総時間の確定後なので差し引かない。共通前処理は差し引かない。"));
        outputDataRow(output, new DataMetric(
                "comparison_strict_observed_time_without_parse_common_preprocess_count_and_draw",
                "比較用時間集計",
                "厳密比較用時間（構文解析・共通前処理・カウント・描画除外）",
                Long.toString(strictObservedTime),
                "ms",
                "大枠比較用時間 - 除外する共通前処理時間。"));
        outputDataRow(output, new DataMetric(
                "comparison_observed_total_based_unclassified_time",
                "比較用時間集計",
                "実測総時間ベースの未分類時間（参考）",
                Long.toString(strictUnclassifiedTime),
                "ms",
                "厳密比較用時間 - 手法固有として個別計測できた時間。"));
    }

    private static void outputDataRow(LTSOutput output, DataMetric metric) {
        output.outln(dataCsvRow(metric));
    }

    private static void writeDataRow(BufferedWriter writer, DataMetric metric) throws IOException {
        writer.write(dataCsvRow(metric));
        writer.newLine();
    }

    private static void writeDataRow(
            BufferedWriter writer,
            DataMetric metric,
            String rowResult,
            String rowFailureReason) throws IOException {
        writer.write(dataCsvRow(metric, mode, rowResult, rowFailureReason));
        writer.newLine();
    }

    private static String dataCsvRow(DataMetric metric) {
        return dataCsvRow(metric, mode, resultStatus.toString(), failureMessage);
    }

    private static String dataCsvRow(
            DataMetric metric,
            String rowMode,
            String rowResult,
            String rowFailureReason) {
        MetricView view = metricView(metric);
        return csv(rowMode)
                + "," + csv(rowResult)
                + "," + csv(rowFailureReason)
                + "," + csv(metric.section)
                + "," + csv(metric.key)
                + "," + csv(metric.label)
                + "," + csv(metric.value)
                + "," + csv(metric.unit)
                + "," + csv(metric.formula)
                + "," + csv(METRIC_SCHEMA_VERSION)
                + "," + csv(view.descriptionId)
                + "," + csv(view.sectionReadableJa)
                + "," + csv(view.metricReadableJa)
                + "," + csv(view.category)
                + "," + csv(view.artifact)
                + "," + csv(view.phase)
                + "," + csv(view.action)
                + "," + csv(view.event)
                + "," + csv(view.stat);
    }

    private static MetricView metricView(DataMetric metric) {
        String section = metric.section == null ? "" : metric.section;
        String label = metric.label == null ? "" : metric.label;
        String key = metric.key == null ? "" : metric.key;
        String unit = metric.unit == null ? "" : metric.unit;

        String sectionReadable = readableSection(section);
        String artifact = extractArtifact(section, label);
        String phase = readablePhase(extractToken(label, "updatePhase="));
        String action = extractToken(label, "normalAction=");
        String event = firstNonEmpty(
                extractToken(label, "afterUpdateEvent="),
                extractUpdateEventFromLabel(label));
        String updateOrder = extractToken(label, "updateOrder=");
        String stat = readableStat(label, key, unit);
        String category = metricCategory(section, label, key, unit);
        String metricReadable = readableMetricLabel(label, artifact, phase, action, event, updateOrder, stat);
        String displayEvent = event.isEmpty() ? updateOrder : event;
        String descriptionId = metricDescriptionId(
                section,
                label,
                key,
                unit,
                sectionReadable,
                metricReadable,
                category,
                artifact,
                phase,
                action,
                displayEvent,
                stat);
        return new MetricView(
                descriptionId,
                sectionReadable,
                metricReadable,
                category,
                artifact,
                phase,
                action,
                displayEvent,
                stat);
    }

    private static String readableSection(String section) {
        if (section == null || section.isEmpty()) {
            return "";
        }
        if ("Run".equals(section)) {
            return "実行情報";
        }
        if ("Evaluation Summary".equals(section) || section.startsWith("Evaluation Summary /")) {
            return "評価サマリ";
        }
        if ("評価出力時間".equals(section)) {
            return "評価出力時間";
        }
        if ("共通 / HPWindow".equals(section)) {
            return "共通処理: GUI/全体計測";
        }
        if ("UpdatingControllersDefinition".equals(section)) {
            return "入力準備: 更新コントローラ定義の構築";
        }
        if ("UpdatingControllerSynthesizer".equals(section)) {
            return "合成入口: 手法選択と呼び出し";
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            return "Traditional DUC: 合成本体";
        }
        if ("Traditional DUC safetyEnv 構築時間内訳".equals(section)) {
            return "Traditional DUC: 安全性制約反映後の環境構築時間";
        }
        if ("Traditional DUC GR1 時間内訳".equals(section)) {
            return "Traditional DUC: 最終コントローラ合成時間";
        }
        if ("Stepwise Delayed DUC".equals(section)) {
            return "Stepwise Delayed DUC: 合成本体";
        }
        if ("Stepwise Delayed DUC GR1 時間内訳".equals(section)) {
            return "Stepwise Delayed DUC: 最終コントローラ合成時間";
        }
        if ("generateDUC (OTF-DUC)".equals(section)) {
            return "OTF-DUC: 本体処理";
        }
        if ("DCS (OTF-DUC)".equals(section)) {
            return "OTF-DUC: On-the-fly探索と出力UC構築";
        }
        if (section.contains("探索時間内訳")) {
            return "OTF-DUC: 探索ヒューリスティック時間";
        }
        if (section.contains("展開時間内訳")) {
            return "OTF-DUC: 状態展開時間";
        }
        if (section.contains("loop / fairness")) {
            return "OTF-DUC: loop/fairness判定時間";
        }
        if (section.contains("伝播時間内訳")) {
            return "OTF-DUC: GOAL/ERROR伝播時間";
        }
        if (section.contains("出力構築時間内訳")) {
            return "OTF-DUC: 出力UC構築時間";
        }
        if (section.contains("projection 別分裂度")) {
            return "OTF-DUC探索グラフ: 成分別の状態分裂";
        }
        if (section.contains("出力 pruning 削減率")) {
            return "OTF-DUC: 出力時pruning/mergeの削減率";
        }
        if (section.contains("ブロック・棄却率")) {
            return "OTF-DUC: 探索・出力時の候補棄却率";
        }
        if (section.contains("方針1 時間・メモリ内訳")) {
            return "OTF-DUC方針1: 簡単マージとbelief repairの時間・メモリ";
        }
        if (section.contains("cache 統計")) {
            return "OTF-DUC: キャッシュ統計";
        }
        if (section.contains("NC 接続統計")) {
            return "OTF-DUC: 新コントローラ接続統計";
        }
        if (section.contains("update phase 別") && section.contains("状態空間")) {
            return methodPrefix(section) + ": 更新段階ごとの状態数・遷移数";
        }
        if (section.contains("update phase 別") && section.contains("遷移詳細")) {
            return methodPrefix(section) + ": 更新段階ごとの遷移内訳";
        }
        if (section.contains("update phase 間")) {
            return methodPrefix(section) + ": 更新段階間の遷移数";
        }
        if (section.contains("update event 別")) {
            return methodPrefix(section) + ": 更新事象別の遷移数";
        }
        if (section.contains("hotSwapIn から更新完了まで")) {
            return methodPrefix(section) + ": 更新開始から完了までの距離";
        }
        if (section.contains("通常 action")) {
            return methodPrefix(section) + ": 通常action別の遷移数";
        }
        if (section.contains("次更新事象まで")) {
            return methodPrefix(section) + ": 次の更新事象までの距離";
        }
        if (section.contains("progress-free cycle")) {
            return methodPrefix(section) + ": 更新が進まない通常遷移サイクル";
        }
        if (section.contains("enabled update event")) {
            return methodPrefix(section) + ": 更新事象が実行可能な状態数";
        }
        if (section.contains("更新順序パターン")) {
            return methodPrefix(section) + ": 更新事象の順序パターン別規模";
        }
        if (section.contains("通常遷移連続長")) {
            return methodPrefix(section) + ": 更新事象間に挟まる通常遷移数";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            return "Traditional DUC: 中間生成物の状態空間サイズ";
        }
        if ("Stepwise Delayed DUC 最大状態数と遷移数".equals(section)) {
            return "Stepwise Delayed DUC: 中間生成物の状態空間サイズ";
        }
        if ("Stepwise Delayed DUC 分類統計".equals(section)) {
            return "Stepwise Delayed DUC: requirement 分類統計";
        }
        if ("Stepwise Delayed DUC scope別要求数".equals(section)) {
            return "Stepwise Delayed DUC: scope 別 requirement 数";
        }
        if ("Stepwise Delayed DUC scope別fluent数".equals(section)) {
            return "Stepwise Delayed DUC: scope 別 fluent 数";
        }
        if ("Stepwise Delayed DUC scope別状態空間".equals(section)) {
            return "Stepwise Delayed DUC: scope 別 meta/safety 状態空間";
        }
        if ("Stepwise Delayed DUC scope別時間".equals(section)) {
            return "Stepwise Delayed DUC: scope 別 meta/safety/product 構築時間";
        }
        if ("Stepwise Delayed DUC cross scheduling".equals(section)) {
            return "Stepwise Delayed DUC: cross goal scheduling 集計";
        }
        if ("Stepwise Delayed DUC cross scheduling detail".equals(section)) {
            return "Stepwise Delayed DUC: cross goal scheduling step 詳細";
        }
        if ("Stepwise Delayed DUC direct pruning 削減率".equals(section)) {
            return "Stepwise Delayed DUC: direct safety pruning 削減率";
        }
        if ("Stepwise Delayed DUC hotSwapIn connection".equals(section)) {
            return "Stepwise Delayed DUC: hotSwapIn connection 構築統計";
        }
        if ("Traditional DUC 状態空間削減率".equals(section)) {
            return "Traditional DUC: 中間生成物間の削減率";
        }
        if ("Output Update Controller".equals(section)) {
            return "出力UC: 最終結果の状態空間サイズ";
        }
        if ("Output Update Controller 削減率".equals(section)) {
            return "出力UC: 出力前状態空間からの削減率";
        }
        if ("入力規模".equals(section)) {
            return "入力規模";
        }
        if ("入力規模 / 事前合成".equals(section)) {
            return "入力規模: 事前合成されたコントローラ";
        }
        if ("メモリ使用量チェックポイント".equals(section)) {
            return "メモリ使用量";
        }
        if ("要件確認".equals(section)) {
            return "Update Controller要件確認";
        }
        if ("比較用時間集計".equals(section)) {
            return "比較用時間集計";
        }
        return section;
    }

    private static String methodPrefix(String section) {
        if (section == null) {
            return "";
        }
        if (section.contains("Traditional DUC")) {
            return "Traditional DUC";
        }
        if (section.contains("OTF-DUC")) {
            return "OTF-DUC探索グラフ";
        }
        if (section.contains("Output Update Controller")) {
            return "出力UC";
        }
        return "共通";
    }

    private static String readableMetricLabel(
            String label,
            String artifact,
            String phase,
            String action,
            String event,
            String updateOrder,
            String stat) {
        List<String> parts = new ArrayList<>();
        if (!artifact.isEmpty()) {
            parts.add(artifact);
        }
        if (!phase.isEmpty()) {
            parts.add(phase);
        }
        if (!updateOrder.isEmpty()) {
            parts.add("更新順序=" + readableUpdateOrder(updateOrder));
        }
        if (!action.isEmpty()) {
            parts.add("action=" + action);
        }
        if (!event.isEmpty()) {
            parts.add("更新事象=" + event);
        }
        if (!stat.isEmpty()) {
            parts.add(stat);
        }
        if (!parts.isEmpty()) {
            return join(" / ", parts);
        }
        return readableFallback(label);
    }

    private static String extractArtifact(String section, String label) {
        String text = (section == null ? "" : section) + " " + (label == null ? "" : label);
        if (text.contains("[1. E_u]")) {
            return "Traditional DUC: 更新用環境";
        }
        if (text.contains("[2. Meta]")) {
            return "Traditional DUC: 安全性評価用合成環境";
        }
        if (text.contains("[3. Pruned]")) {
            return "Traditional DUC: 安全性違反除去後";
        }
        if (text.contains("[4. Final]")) {
            return "Traditional DUC: 最終コントローラ合成入力";
        }
        if (text.contains("DCS explored graph") || text.contains("DCS で探索した")) {
            return "OTF-DUC探索グラフ";
        }
        if (text.contains("Output Update Controller")) {
            return "出力Update Controller";
        }
        if (text.contains("Old Controller")) {
            return "旧コントローラ";
        }
        if (text.contains("New Controller")) {
            return "新コントローラ";
        }
        if (text.contains("projection=")) {
            return "OTF-DUC探索グラフ";
        }
        return "";
    }

    private static String readablePhase(String phase) {
        if (phase == null || phase.isEmpty()) {
            return "";
        }
        if ("PRE".equals(phase)) {
            return "hotSwapIn前";
        }
        if ("000".equals(phase)) {
            return "hotSwapIn後、stopOldSpec・reconfigure・startNewSpecは未実行";
        }
        if ("100".equals(phase)) {
            return "hotSwapIn後、stopOldSpec実行済み";
        }
        if ("010".equals(phase)) {
            return "hotSwapIn後、reconfigure実行済み";
        }
        if ("001".equals(phase)) {
            return "hotSwapIn後、startNewSpec実行済み";
        }
        if ("110".equals(phase)) {
            return "hotSwapIn後、stopOldSpec・reconfigure実行済み";
        }
        if ("101".equals(phase)) {
            return "hotSwapIn後、stopOldSpec・startNewSpec実行済み";
        }
        if ("011".equals(phase)) {
            return "hotSwapIn後、reconfigure・startNewSpec実行済み";
        }
        if ("111".equals(phase)) {
            return "hotSwapIn後、stopOldSpec・reconfigure・startNewSpec全て実行済み";
        }
        if ("POST".equals(phase)) {
            return "hotSwapOut後";
        }
        return phase;
    }

    private static String metricCategory(String section, String label, String key, String unit) {
        String text = lower(section + " " + label + " " + key + " " + unit);
        String normalizedUnit = lower(unit);
        if (isTimeUnit(normalizedUnit)) {
            return "時間";
        }
        if (isMemoryUnit(normalizedUnit)) {
            return "メモリ";
        }
        if (isStateUnit(normalizedUnit)) {
            return "状態数";
        }
        if (isTransitionUnit(normalizedUnit)) {
            return "遷移数";
        }
        if ("ratio".equals(normalizedUnit) || "percent".equals(normalizedUnit)) {
            return text.contains("reduction") || text.contains("削減率")
                    || text.contains("remain rate")
                    ? "削減率"
                    : "割合";
        }
        if ("parent process memory measurement".equals(lower(section))) {
            return "メモリ";
        }
        if (text.contains("time") || text.contains("時間") || text.contains("counttime")) {
            return "時間";
        }
        if (text.contains("memory") || text.contains("メモリ")) {
            return "メモリ";
        }
        if (text.contains("reduction") || text.contains("削減率") || text.contains("remain rate")) {
            return "削減率";
        }
        if (text.contains("projection") || text.contains("分裂度")) {
            return "状態分裂";
        }
        if (text.contains("cycle") || text.contains("scc")) {
            return "サイクル";
        }
        if (text.contains("distance") || text.contains("length") || text.contains("距離") || text.contains("連続長")) {
            return "距離・パス長";
        }
        if (text.contains("updateorder") || text.contains("更新順序")) {
            return "更新順序";
        }
        if (containsMetricToken(text, "rate")) {
            return "割合";
        }
        if (text.contains("transition") || text.contains("遷移")) {
            return "遷移数";
        }
        if (text.contains("state") || text.contains("状態")) {
            return "状態数";
        }
        return "その他";
    }

    private static String readableStat(String label, String key, String unit) {
        String text = lower(label + " " + key);
        String normalizedUnit = lower(unit);
        if (isTimeUnit(normalizedUnit)) {
            if (text.contains("counttime") || text.contains("評価用カウント時間")) {
                return "評価用カウント時間";
            }
            return "時間";
        }
        if (isMemoryUnit(normalizedUnit)) {
            return "メモリ";
        }
        if ("ratio".equals(normalizedUnit) || "percent".equals(normalizedUnit)) {
            return text.contains("normal transition rate") ? "通常遷移率" : "割合";
        }
        if (text.contains("評価用カウント時間除外後")) {
            return "時間";
        }
        if (text.contains("counttime") || text.contains("評価用カウント時間")) {
            return "評価用カウント時間";
        }
        if ("states/value".equals(normalizedUnit)
                || text.contains("states/value")
                || text.contains("states per projection value")
                || text.contains("states_per_projection_value")) {
            return "射影値あたりの状態数";
        }
        if (text.contains("distinct_projection_values")) {
            return "異なる射影値の数";
        }
        if (text.contains("split_projection_values")) {
            return "複数状態に分裂した射影値の数";
        }
        if (isStateUnit(normalizedUnit)) {
            if (text.contains("unreachable")) {
                return "到達不能状態数";
            }
            if (text.contains("reachable")) {
                return "到達可能状態数";
            }
            return "状態数";
        }
        if (isTransitionUnit(normalizedUnit)) {
            if (text.contains("normal")) {
                return "通常遷移数";
            }
            if (text.contains("update event")) {
                return "更新事象遷移数";
            }
            return "遷移数";
        }
        if (text.contains("states") || text.contains("状態数")) {
            if (text.contains("unreachable")) {
                return "到達不能状態数";
            }
            if (text.contains("reachable")) {
                return "到達可能状態数";
            }
            return "状態数";
        }
        if (text.contains("transitions") || text.contains("遷移")) {
            if (text.contains("normal")) {
                return "通常遷移数";
            }
            if (text.contains("update event")) {
                return "更新事象遷移数";
            }
            return "遷移数";
        }
        if (text.contains("normal transition rate")) {
            return "通常遷移率";
        }
        if (text.contains("average out-degree")) {
            return "平均分岐数";
        }
        if (text.contains("max out-degree")) {
            return "最大分岐数";
        }
        if (containsMetricToken(text, "min") || containsMetricToken(text, "minimum")) {
            return "最小値";
        }
        if (containsMetricToken(text, "max") || containsMetricToken(text, "maximum")) {
            return "最大値";
        }
        if (text.contains("avg") || text.contains("average")) {
            return "平均値";
        }
        if (containsMetricToken(text, "rate") || "ratio".equals(normalizedUnit)) {
            return "割合";
        }
        if (text.contains("samples")) {
            return "サンプル数";
        }
        if (text.contains("scc")) {
            return "SCC数/サイズ";
        }
        if (text.contains("phase")) {
            return "更新段階";
        }
        if (!unit.isEmpty() && !"text".equals(unit)) {
            return unit;
        }
        return readableFallback(label);
    }

    private static boolean isTimeUnit(String normalizedUnit) {
        return "ms".equals(normalizedUnit)
                || "ns".equals(normalizedUnit)
                || "epoch_ms".equals(normalizedUnit)
                || "ms/call".equals(normalizedUnit);
    }

    private static boolean isMemoryUnit(String normalizedUnit) {
        return "b".equals(normalizedUnit)
                || "byte".equals(normalizedUnit)
                || "bytes".equals(normalizedUnit)
                || "kb".equals(normalizedUnit)
                || "mb".equals(normalizedUnit)
                || "gb".equals(normalizedUnit)
                || "kib".equals(normalizedUnit)
                || "mib".equals(normalizedUnit)
                || "gib".equals(normalizedUnit);
    }

    private static boolean isStateUnit(String normalizedUnit) {
        return "state".equals(normalizedUnit)
                || "states".equals(normalizedUnit)
                || normalizedUnit.startsWith("states/");
    }

    private static boolean isTransitionUnit(String normalizedUnit) {
        return "transition".equals(normalizedUnit)
                || "transitions".equals(normalizedUnit)
                || normalizedUnit.startsWith("transitions/");
    }

    private static boolean containsMetricToken(String text, String token) {
        if (text == null || token == null || token.isEmpty()) {
            return false;
        }
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            int before = index - 1;
            int after = index + token.length();
            boolean startsToken = before < 0 || !Character.isLetterOrDigit(text.charAt(before));
            boolean endsToken = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
            if (startsToken && endsToken) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static String metricDescriptionId(
            String section,
            String label,
            String key,
            String unit,
            String sectionReadable,
            String metricReadable,
            String category,
            String artifact,
            String phase,
            String action,
            String event,
            String stat) {

        StringBuilder basis = new StringBuilder();
        appendIdBasis(basis, "schema", METRIC_SCHEMA_VERSION);
        appendIdBasis(basis, "section", section);
        appendIdBasis(basis, "label", label);
        appendIdBasis(basis, "key", key);
        appendIdBasis(basis, "unit", unit);
        appendIdBasis(basis, "section_readable_ja", sectionReadable);
        appendIdBasis(basis, "metric_readable_ja", metricReadable);
        appendIdBasis(basis, "metric_category", category);
        appendIdBasis(basis, "artifact", artifact);
        appendIdBasis(basis, "phase", phase);
        appendIdBasis(basis, "action", action);
        appendIdBasis(basis, "event", event);
        appendIdBasis(basis, "stat", stat);

        return descriptionIdPrefix(category, stat, unit) + "." + stableShortHash(basis.toString());
    }

    private static void appendIdBasis(StringBuilder builder, String key, String value) {
        builder.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static String descriptionIdPrefix(String category, String stat, String unit) {
        String categoryToken = categoryToken(category);
        String statToken = statToken(stat, unit);
        if (categoryToken.isEmpty()) {
            categoryToken = "metric";
        }
        if (statToken.isEmpty()) {
            statToken = "value";
        }
        return categoryToken + "." + statToken;
    }

    private static String categoryToken(String category) {
        String text = category == null ? "" : category;
        if (text.contains("時間")) {
            return "time";
        }
        if (text.contains("メモリ")) {
            return "memory";
        }
        if (text.contains("削減率")) {
            return "reduction";
        }
        if (text.contains("状態分裂")) {
            return "projection_split";
        }
        if (text.contains("サイクル")) {
            return "cycle";
        }
        if (text.contains("距離") || text.contains("パス長")) {
            return "distance";
        }
        if (text.contains("更新順序")) {
            return "update_order";
        }
        if (text.contains("割合")) {
            return "rate";
        }
        if (text.contains("遷移数")) {
            return "transitions";
        }
        if (text.contains("状態数")) {
            return "states";
        }
        return idToken(text);
    }

    private static String statToken(String stat, String unit) {
        String text = stat == null ? "" : stat;
        if (text.contains("評価用カウント時間")) {
            return "count_time";
        }
        if (text.contains("通常遷移率")) {
            return "normal_transition_rate";
        }
        if (text.contains("通常遷移数")) {
            return "normal_transitions";
        }
        if (text.contains("更新事象遷移数")) {
            return "update_event_transitions";
        }
        if (text.contains("到達不能状態数")) {
            return "unreachable_states";
        }
        if (text.contains("到達可能状態数")) {
            return "reachable_states";
        }
        if (text.contains("状態数")) {
            return "states";
        }
        if (text.contains("遷移数")) {
            return "transitions";
        }
        if (text.contains("平均分岐数")) {
            return "average_out_degree";
        }
        if (text.contains("最大分岐数")) {
            return "max_out_degree";
        }
        if (text.contains("最小値")) {
            return "min_value";
        }
        if (text.contains("最大値")) {
            return "max_value";
        }
        if (text.contains("平均値")) {
            return "average_value";
        }
        if (text.contains("サンプル数")) {
            return "samples";
        }
        if (text.contains("SCC")) {
            return "scc";
        }
        if (text.contains("更新段階")) {
            return "update_phase";
        }
        if (text.contains("時間")) {
            return "time";
        }
        if (text.contains("メモリ")) {
            return "memory";
        }
        if (text.contains("割合")) {
            return "rate";
        }
        String unitToken = idToken(unit);
        if (!unitToken.isEmpty() && !"text".equals(unitToken)) {
            return unitToken;
        }
        return idToken(text);
    }

    private static String idToken(String value) {
        String token = lower(value).replaceAll("[^a-z0-9]+", "_");
        token = token.replaceAll("^_+", "").replaceAll("_+$", "");
        return token;
    }

    private static String stableShortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("md_");
            int bytes = Math.min(8, hash.length);
            for (int i = 0; i < bytes; i++) {
                int unsigned = hash[i] & 0xff;
                if (unsigned < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(unsigned));
            }
            return builder.toString();
        } catch (Exception e) {
            return "md_" + Integer.toHexString((value == null ? "" : value).hashCode());
        }
    }

    private static String extractToken(String text, String prefix) {
        if (text == null || prefix == null) {
            return "";
        }
        int index = text.indexOf(prefix);
        if (index < 0) {
            return "";
        }
        int start = index + prefix.length();
        int end = text.length();
        String[] delimiters = {" / ", ",", ":", ")"};
        for (String delimiter : delimiters) {
            int delimiterIndex = text.indexOf(delimiter, start);
            if (delimiterIndex >= 0 && delimiterIndex < end) {
                end = delimiterIndex;
            }
        }
        return text.substring(start, end).trim();
    }

    private static String extractUpdateEventFromLabel(String label) {
        String text = label == null ? "" : label;
        String[] events = {
                "hotSwapIn",
                "stopOldSpec",
                "reconfigure",
                "startNewSpec",
                "hotSwapOut"
        };
        for (String event : events) {
            if (text.contains(event)) {
                return event;
            }
        }
        return "";
    }

    private static String readableUpdateOrder(String order) {
        if (order == null || order.isEmpty()) {
            return "";
        }
        if ("PRE".equals(order)) {
            return "更新前";
        }
        return order.replace(">", " -> ");
    }

    private static String readableFallback(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("Traditional solveControlProblem / OTF generateDUC 実行時間", "手法別の本体呼び出し時間")
                .replace("Traditional DUC grGoal 生成時間", "Traditional DUC ゴール条件生成時間")
                .replace("Traditional DUC safetyGoal 生成時間", "Traditional DUC 安全性ゴール条件生成時間")
                .replace("Traditional DUC: Safety環境構築時間", "Traditional DUC: 安全性制約反映後の環境構築時間")
                .replace("Safety環境構築時間", "安全性制約反映後の環境構築時間")
                .replace("Traditional DUC: 基本更新環境 E_u", "Traditional DUC: 更新用環境")
                .replace("Traditional DUC: 基本更新環境 更新用環境", "Traditional DUC: 更新用環境")
                .replace("基本更新環境 更新用環境", "更新用環境")
                .replace("Traditional DUC: safety評価用Meta環境", "Traditional DUC: 安全性評価用合成環境")
                .replace("safety評価用Meta環境", "安全性評価用合成環境")
                .replace("Traditional DUC: safety違反枝刈り後", "Traditional DUC: 安全性違反除去後")
                .replace("safety違反枝刈り後", "安全性違反除去後")
                .replace("Traditional DUC: 最終コントローラ合成入力の最終Safety環境", "Traditional DUC: 最終コントローラ合成入力")
                .replace("最終Safety環境", "最終コントローラ合成入力")
                .replace("最終コントローラ合成入力の最終コントローラ合成入力", "最終コントローラ合成入力")
                .replace("[1. E_u]", "[1. 更新用環境]")
                .replace("[2. Meta]", "[2. 安全性評価用合成環境]")
                .replace("[3. Pruned]", "[3. 安全性違反除去後]")
                .replace("[4. Final] Safety Environment", "[4. 最終コントローラ合成入力]")
                .replace("Safety Env (Before DontDoTwice)", "安全性違反除去後")
                .replace("Meta Environment (PEAK)", "安全性評価用合成環境")
                .replace("DontDoTwice goal 合成時間", "更新事象の重複禁止条件合成時間")
                .replace("DontDoTwice", "更新事象の重複禁止条件")
                .replace("Safety formula", "安全性条件")
                .replace("Safety 違反", "安全性違反")
                .replace("エラーを枝刈りして", "エラー状態を除去して")
                .replace("pruning", "除去")
                .replace("Goal/controllable action", "ゴール条件/controllable action")
                .replace("Goal 定義", "ゴール条件定義")
                .replace("Goal準備", "ゴール条件準備")
                .replace("Traditional DUC のE_u構築+GR1合成時間（中核）", "Traditional DUC の更新用環境構築+最終コントローラ合成時間（中核）")
                .replace("Traditional DUC E_u 構築時間", "Traditional DUC 更新用環境構築時間")
                .replace("Fluent とベース環境を並列合成した metaEnv 構築時間", "安全性評価用合成環境構築時間")
                .replace("metaEnv からエラーを枝刈りして safetyEnv を構築する時間", "安全性制約反映後の環境構築時間")
                .replace("safetyEnv から CompactState への変換時間", "安全性制約反映後の環境から出力用モデルへの変換時間")
                .replace("safetyEnv を GR1 で解く時間", "最終コントローラ合成時間")
                .replace("solveControlProblem 全体時間", "最終コントローラ合成処理全体時間")
                .replace("solveControlProblem", "Traditional DUC合成本体処理")
                .replace("E_u 構築時間", "更新用環境構築時間")
                .replace("E_u 状態数", "更新用環境状態数")
                .replace("E_u 遷移数", "更新用環境遷移数")
                .replace("E_u", "更新用環境")
                .replace("metaEnv 構築時間", "安全性評価用合成環境構築時間")
                .replace("metaEnv", "安全性評価用合成環境")
                .replace("safetyEnv 構築時間", "安全性制約反映後の環境構築時間")
                .replace("safetyEnv", "安全性制約反映後の環境")
                .replace("GR1 で解く時間", "最終コントローラ合成時間")
                .replace("GR1合成時間", "最終コントローラ合成時間")
                .replace("GR(1)合成処理全体時間", "最終コントローラ合成処理全体時間")
                .replace("GR(1)合成処理その他時間", "最終コントローラ合成処理内のその他時間")
                .replace("GR(1)求解時間", "最終コントローラ合成時間")
                .replace("GR(1)合成時間", "最終コントローラ合成時間")
                .replace("GR(1)入力", "最終コントローラ合成入力")
                .replace("GR(1)", "最終コントローラ合成")
                .replace("Winning region", "勝ち領域")
                .replace("Strategy から controller MTS を構築する時間", "コントローラ戦略から出力用モデルを構築する時間")
                .replace("Strategy 構築時間", "コントローラ戦略構築時間")
                .replace("generateDUC 全体時間", "OTF-DUC本体処理全体時間")
                .replace("synthesizeDUC 実行時間", "on-the-fly探索と出力UC構築時間")
                .replace("DCS で Update Controller を合成する時間", "探索呼び出しから出力UC反映までの時間")
                .replace("DCS 実行時間", "探索器呼び出し全体時間")
                .replace("DCS で探索した時間", "on-the-fly探索時間")
                .replace("buildDirectorDUC 実行時間", "出力UC構築時間")
                .replace("expandDUC 呼び出し回数", "状態展開呼び出し回数")
                .replace("boxList 準備時間", "探索入力モデル準備時間")
                .replace("boxList 内訳", "探索入力モデル準備内訳")
                .replace("boxList", "探索入力モデル群")
                .replace("States", "状態数")
                .replace("Transitions", "遷移数")
                .replace("CountTime", "評価用カウント時間")
                .replace("hotSwapIn-to-completion", "hotSwapInから更新完了まで")
                .replace("distance-to-completion", "更新完了までの距離")
                .replace("distance-to-next-update-event", "次更新事象までの距離")
                .replace("normal-run-before-next-update-event", "次更新事象までの通常遷移連続長")
                .replace("normal action controllability", "通常遷移の制御可能性内訳")
                .replace("progress-free SCC", "更新が進まない通常遷移SCC")
                .replace("enabled update event states", "更新事象が実行可能な状態数")
                .replace("update event transitions", "更新事象遷移数")
                .replace("normal transitions", "通常遷移数")
                .replace("average out-degree", "平均分岐数")
                .replace("max out-degree", "最大分岐数");
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isEmpty() ? first : (second == null ? "" : second);
    }

    private static String join(String delimiter, List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(delimiter);
            }
            builder.append(value);
        }
        return builder.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void recordDataMetric(String section, String label, String value, String unit) {
        recordDataMetric(metricKey(section, label), section, label, value, unit);
    }

    private static void recordDataMetricWithFormula(
            String section,
            String label,
            String value,
            String unit,
            String formula) {
        recordDataMetricWithFormula(metricKey(section, label), section, label, value, unit, formula);
    }

    private static void recordDataMetric(String key, String section, String label, String value, String unit) {
        recordDataMetricWithFormula(key, section, label, value, unit, "");
    }

    private static void recordDataMetricWithFormula(
            String key,
            String section,
            String label,
            String value,
            String unit,
            String formula) {
        if (!isEnabled()) {
            return;
        }
        String normalizedKey = key == null || key.isEmpty() ? metricKey(section, label) : key;
        dataMetrics.put(normalizedKey, new DataMetric(
                normalizedKey,
                section == null ? "" : section,
                label == null ? "" : label,
                value == null ? "" : value,
                unit == null ? "" : unit,
                formula == null ? "" : formula));
    }

    private static void attachDataMetricFormula(String key, String formula) {
        if (!isEnabled()) {
            return;
        }
        if (formula == null || formula.isEmpty()) {
            return;
        }
        DataMetric metric = dataMetrics.get(key);
        if (metric == null) {
            return;
        }
        dataMetrics.put(key, new DataMetric(
                metric.key,
                metric.section,
                metric.label,
                metric.value,
                metric.unit,
                formula));
    }

    private static String metricKey(String section, String label) {
        String knownKey = knownMetricKey(section, label);
        if (!knownKey.isEmpty()) {
            return knownKey;
        }
        return "auto_" + Integer.toHexString(timerKey(section, label).hashCode());
    }

    private static String metricToken(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        boolean previousUnderscore = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = Character.toLowerCase(value.charAt(i));
            boolean tokenChar = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
            if (tokenChar) {
                builder.append(ch);
                previousUnderscore = false;
            } else if (!previousUnderscore && builder.length() > 0) {
                builder.append('_');
                previousUnderscore = true;
            }
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '_') {
            builder.deleteCharAt(length - 1);
        }
        return builder.length() == 0 ? "metric" : builder.toString();
    }

    private static String knownMetricKey(String section, String label) {
        if ("共通 / HPWindow".equals(section)) {
            if ("合成ボタンを押してから合成完了までの時間".equals(label)) {
                return "total_time";
            }
            if ("構文解析時間".equals(label)) {
                return "parse_time";
            }
            if ("compileIfChange 全体時間（参考）".equals(label)) {
                return "compile_if_change_total_time";
            }
            if ("合成問題準備時間".equals(label)) {
                return "synthesis_problem_preparation_time";
            }
            if ("update controller 生成時間".equals(label)) {
                return "update_controller_generation_time";
            }
            if ("コントローラ合成時間".equals(label)) {
                return "controller_synthesis_related_time";
            }
            if ("コントローラ描画時間".equals(label)) {
                return "controller_draw_time";
            }
            if ("コントローラ合成のベースラインメモリ".equals(label)) {
                return "controller_synthesis_base_memory";
            }
            if ("コントローラ合成全体のピークメモリ".equals(label)) {
                return "controller_synthesis_peak_memory";
            }
            if ("コントローラ合成により増えたメモリ".equals(label)) {
                return "controller_synthesis_memory_increase";
            }
        }
        if ("UpdatingControllersDefinition".equals(section)) {
            if ("compose の全体実行時間".equals(label)) {
                return "definition_prepare_total_time";
            }
            if ("Old Controller 合成時間".equals(label)) {
                return "old_controller_synthesis_time";
            }
            if ("Goal 定義と controllable action 集合生成時間".equals(label)) {
                return "goal_and_controllable_set_time";
            }
            if ("Mapping Environment Component 合成時間".equals(label)) {
                return "mapping_component_generation_time";
            }
            if ("New Controller 合成時間".equals(label)) {
                return "new_controller_synthesis_time";
            }
            if ("Safety の tester 変換全体時間".equals(label)) {
                return "safety_tester_conversion_total_time";
            }
            if ("New Safety から Fluent を抽出する時間".equals(label)) {
                return "new_safety_fluent_extraction_time";
            }
            if ("Traditional DUC grGoal 生成時間".equals(label)) {
                return "traditional_gr_goal_generation_time";
            }
            if ("Traditional DUC safetyGoal 生成時間".equals(label)) {
                return "traditional_safety_goal_generation_time";
            }
            if ("Traditional DUC Mapping Environment Component 並列合成時間".equals(label)) {
                return "traditional_mapping_parallel_composition_time";
            }
            if ("入力規模集計時間".equals(label)) {
                return "input_scale_summary_time";
            }
        }
        if ("UpdatingControllerSynthesizer".equals(section)) {
            if ("generateController の全体実行時間".equals(label)) {
                return "generate_controller_total_time";
            }
            if ("Traditional solveControlProblem / OTF generateDUC 実行時間".equals(label)) {
                return "method_main_execution_time";
            }
            if ("Stepwise DUC 本体実行時間".equals(label)) {
                return "stepwise_method_main_execution_time";
            }
            if ("Stepwise Delayed DUC 本体実行時間".equals(label)) {
                return "stepwise_delayed_method_main_execution_time";
            }
            if ("Traditional DUC E_u 構築時間".equals(label)) {
                return "traditional_eu_construction_time";
            }
        }
        if ("generateDUC (OTF-DUC)".equals(section)) {
            if ("generateDUC 全体時間".equals(label)) {
                return "otf_generate_duc_total_time";
            }
            if ("boxList 準備時間".equals(label)) {
                return "otf_box_list_preparation_time";
            }
            if ("MarkingLTS 生成時間".equals(label)) {
                return "otf_marking_lts_generation_time";
            }
            if ("New Controller の接続先の事前計算".equals(label)) {
                return "otf_new_controller_connection_precompute_time";
            }
            if ("New Safety と Fluent の対応表の変換作業時間".equals(label)) {
                return "otf_new_safety_fluent_map_conversion_time";
            }
            if ("DCS で Update Controller を合成する時間".equals(label)) {
                return "otf_dcs_update_controller_synthesis_time";
            }
            if ("DCS 実行時間".equals(label)) {
                return "otf_dcs_execution_time";
            }
        }
        if ("DCS (OTF-DUC)".equals(section)) {
            if ("synthesizeDUC 実行時間".equals(label)) {
                return "otf_synthesize_duc_time";
            }
            if ("DCS で探索した時間".equals(label)) {
                return "otf_dcs_search_time";
            }
            if ("expandDUC 呼び出し回数".equals(label)) {
                return "otf_expand_duc_calls";
            }
            if ("buildDirectorDUC 実行時間".equals(label)) {
                return "otf_build_director_duc_time";
            }
            if ("NC 移設時間".equals(label)) {
                return "otf_new_controller_transfer_time";
            }
            if ("NC 接続時間".equals(label)) {
                return "otf_new_controller_stitching_time";
            }
            if ("NC 移設時間 + NC 接続時間".equals(label)) {
                return "otf_new_controller_transfer_and_stitching_time";
            }
        }
        if ("OTF-DUC 出力構築時間内訳".equals(section)) {
            if ("belief 再探索で資源上限により fallback した旧状態数".equals(label)) {
                return "strategy1_belief_repair_resource_limit_fallbacks";
            }
            if ("belief 再探索で belief node 上限により fallback した旧状態数".equals(label)) {
                return "strategy1_belief_repair_resource_limit_belief_node_fallbacks";
            }
            if ("belief 再探索で追加 concrete state 上限により fallback した旧状態数".equals(label)) {
                return "strategy1_belief_repair_resource_limit_additional_concrete_fallbacks";
            }
            if ("belief 再探索で追加 transition 上限により fallback した旧状態数".equals(label)) {
                return "strategy1_belief_repair_resource_limit_additional_transition_fallbacks";
            }
            if ("belief 再探索で時間上限により fallback した旧状態数".equals(label)) {
                return "strategy1_belief_repair_resource_limit_time_fallbacks";
            }
            if ("belief 再探索で破棄した unsafe controllable action 数".equals(label)) {
                return "strategy1_belief_repair_unsafe_controllable_discards";
            }
            if ("belief 再探索で未展開 uncontrollable warning 数".equals(label)) {
                return "strategy1_belief_repair_unexplored_uncontrollable_warnings";
            }
            if ("belief 再探索で未展開 uncontrollable が残った belief node 数".equals(label)) {
                return "strategy1_belief_repair_unexplored_uncontrollable_belief_nodes";
            }
            if ("belief 再探索で未展開 uncontrollable action 数".equals(label)) {
                return "strategy1_belief_repair_unexplored_uncontrollable_actions";
            }
        }
        if ("OTF-DUC 方針1 時間・メモリ内訳".equals(section)) {
            if ("通常OTF探索+簡単マージ時間".equals(label)) {
                return "strategy1_normal_otf_search_simple_merge_time";
            }
            if ("通常OTF探索+簡単マージ直前メモリ".equals(label)) {
                return "strategy1_normal_otf_search_simple_merge_before_memory";
            }
            if ("通常OTF探索+簡単マージ中ピークメモリ".equals(label)) {
                return "strategy1_normal_otf_search_simple_merge_peak_memory";
            }
            if ("通常OTF探索+簡単マージ中増加メモリ".equals(label)) {
                return "strategy1_normal_otf_search_simple_merge_memory_increase";
            }
            if ("belief repair時間".equals(label)) {
                return "strategy1_belief_repair_time";
            }
            if ("belief repair直前メモリ".equals(label)) {
                return "strategy1_belief_repair_before_memory";
            }
            if ("belief repair中ピークメモリ".equals(label)) {
                return "strategy1_belief_repair_peak_memory";
            }
            if ("belief repair中増加メモリ".equals(label)) {
                return "strategy1_belief_repair_memory_increase";
            }
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            if ("solveControlProblem 全体時間".equals(label)) {
                return "traditional_solve_control_problem_total_time";
            }
            if ("UpdatingEnvironment から E_u MTS への変換時間".equals(label)) {
                return "traditional_eu_mts_conversion_time";
            }
            if ("Old Safety と New Safety から Fluent を抽出する時間".equals(label)) {
                return "traditional_fluent_extraction_time";
            }
            if ("Fluent とベース環境を並列合成した metaEnv 構築時間".equals(label)) {
                return "traditional_meta_environment_construction_time";
            }
            if ("metaEnv からエラーを枝刈りして safetyEnv を構築する時間".equals(label)) {
                return "traditional_safety_environment_pruning_time";
            }
            if ("safetyEnv から CompactState への変換時間".equals(label)) {
                return "traditional_safety_env_compact_state_conversion_time";
            }
            if ("safetyEnv を GR1 で解く時間".equals(label)) {
                return "traditional_gr1_solving_time";
            }
        }
        if ("Traditional DUC".equals(section)) {
            if ("GR(1)入力 safetyEnv 構築時間".equals(label)) {
                return "traditional_final_gr_input_construction_time";
            }
            if ("SBP 全体時間合計".equals(label)) {
                return "traditional_sbp_total_time";
            }
        }
        if ("Traditional DUC safetyEnv 構築時間内訳".equals(section)) {
            if ("hotSwapIn 前の旧 action を uncontrollable 化する時間".equals(label)) {
                return "traditional_safety_env_old_action_uncontrollable_time";
            }
            if ("Fluent valuation 構築時間".equals(label)) {
                return "traditional_safety_env_fluent_valuation_time";
            }
            if ("Safety formula 評価と違反状態 pruning 時間".equals(label)) {
                return "traditional_safety_env_formula_eval_and_pruning_time";
            }
            if ("Safety formula を全状態で評価する時間".equals(label)) {
                return "traditional_safety_env_formula_evaluation_time";
            }
            if ("Safety 違反状態を除去した MTS 構築時間".equals(label)) {
                return "traditional_safety_env_violation_pruning_mts_build_time";
            }
            if ("DontDoTwice goal 合成時間".equals(label)) {
                return "traditional_safety_env_dont_do_twice_time";
            }
        }
        if ("Stepwise Delayed DUC".equals(section)) {
            if ("GR(1)入力 safetyEnv 構築時間".equals(label)) {
                return "stepwise_delayed_final_gr_input_construction_time";
            }
            if ("SBP 全体時間合計".equals(label)) {
                return "stepwise_delayed_sbp_total_time";
            }
            if ("old controller meta 構築時間".equals(label)) {
                return "stepwise_delayed_old_controller_meta_construction_time";
            }
            if ("final tracked fluent 数".equals(label)) {
                return "stepwise_delayed_final_tracked_fluent_count";
            }
            if ("hotSwapIn connection 後 environment 構築時間".equals(label)) {
                return "stepwise_delayed_connection_environment_construction_time";
            }
            if ("global DontDoTwice 構築時間".equals(label)) {
                return "stepwise_delayed_global_dont_do_twice_time";
            }
            if ("safetyEnv から CompactState への変換時間".equals(label)) {
                return "stepwise_delayed_safety_env_compact_state_conversion_time";
            }
            if ("safetyEnv を GR1 で解く時間".equals(label)) {
                return "stepwise_delayed_gr1_solving_time";
            }
        }
        if ("Traditional DUC 設定".equals(section)) {
            return "traditional_config_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC 設定".equals(section)) {
            return "stepwise_delayed_config_" + metricToken(label);
        }
        if ("Safety Backward Pruning".equals(section)) {
            if ("SBP 全体時間合計".equals(label)) {
                return "sbp_total_time";
            }
            return "sbp_" + metricToken(label);
        }
        if ("Safety Backward Pruning 削減率".equals(section)) {
            return "sbp_reduction_" + metricToken(label);
        }
        String gr1BreakdownPrefix = "";
        if ("Traditional DUC GR1 時間内訳".equals(section)) {
            gr1BreakdownPrefix = "traditional";
        } else if ("Stepwise Delayed DUC GR1 時間内訳".equals(section)) {
            gr1BreakdownPrefix = "stepwise_delayed";
        }
        if (!gr1BreakdownPrefix.isEmpty()) {
            if ("synthesizeGR 全体時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_synthesize_total_time";
            }
            if ("非決定環境の subset construction 時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_subset_construction_time";
            }
            if ("Perfect-info game after subset construction".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_perfect_info_game";
            }
            if ("GR goal 構築時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_goal_build_time";
            }
            if ("GR assumption 数".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_assumptions";
            }
            if ("GR guarantee 数".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_guarantees";
            }
            if ("GR failure state 数".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_failure_states";
            }
            if ("GR permissive strategy 有効".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_permissive_strategy";
            }
            if ("GR game 構築時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_game_build_time";
            }
            if ("GR game".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_game";
            }
            if ("Knowledge GR game 構築時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_knowledge_game_build_time";
            }
            if ("Knowledge GR game".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_knowledge_game";
            }
            if ("Rank system 構築時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_rank_system_build_time";
            }
            if ("Winning region 計算時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_winning_region_time";
            }
            if ("Strategy 構築時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_strategy_build_time";
            }
            if ("Strategy から controller MTS を構築する時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_strategy_to_controller_mts_time";
            }
            if ("StrategyState controller を Long/String MTS に変換する時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_strategy_state_to_long_string_mts_time";
            }
            if ("Controller を CompactState に変換する時間".equals(label)) {
                return gr1BreakdownPrefix + "_gr1_compact_state_conversion_time";
            }
        }
        if ("TransitionSystemDispatcher".equals(section)) {
            if ("removeOldTransitions 実行時間".equals(label)) {
                return "traditional_remove_old_transitions_time";
            }
            if ("Stepwise DUC removeOldTransitions 実行時間".equals(label)) {
                return "stepwise_remove_old_transitions_time";
            }
            if ("Stepwise Delayed DUC removeOldTransitions 実行時間".equals(label)) {
                return "stepwise_delayed_remove_old_transitions_time";
            }
        }
        if ("比較用時間集計".equals(section)) {
            if ("実測総時間".equals(label)) {
                return "comparison_observed_total_time";
            }
            if ("除外する共通前処理時間".equals(label)) {
                return "comparison_common_preprocess_time";
            }
            if ("評価用カウント時間（状態数・遷移数）".equals(label)) {
                return "comparison_count_overhead_time";
            }
            if ("評価用カウント時間（合計）".equals(label)) {
                return "comparison_count_overhead_total";
            }
            if ("評価用カウント時間（実測時間外）".equals(label)) {
                return "comparison_count_overhead_after_observed_time";
            }
            if ("大枠比較用時間（構文解析・カウント・描画除外）".equals(label)) {
                return "comparison_observed_time_without_parse_count_and_draw";
            }
            if ("厳密比較用時間（構文解析・共通前処理・カウント・描画除外）".equals(label)) {
                return "comparison_strict_observed_time_without_parse_common_preprocess_count_and_draw";
            }
            if ("共通前処理などを除いた実測時間".equals(label)) {
                return "comparison_observed_time_without_common_preprocess";
            }
            if ("共通前処理・評価用カウント・評価出力を除いた実測時間".equals(label)) {
                return "comparison_observed_time_without_common_preprocess_count_and_evaluation_output";
            }
            if ("実測総時間ベースの未分類時間（参考）".equals(label)) {
                return "comparison_observed_total_based_unclassified_time";
            }
            if ("評価結果出力時間（実測総時間外・参考）".equals(label)
                    || "評価結果出力時間（比較から除外）".equals(label)) {
                return "comparison_evaluation_output_time";
            }
            if ("共通処理を除いたコントローラ合成時間".equals(label)) {
                return "comparison_controller_synthesis_time_without_common";
            }
            if ("手法固有として個別計測できた時間".equals(label)) {
                return "comparison_method_specific_time";
            }
            if ("未分類の非共通時間".equals(label)) {
                return "comparison_unclassified_non_common_time";
            }
            if ("主比較用コントローラ合成時間".equals(label)) {
                return "comparison_primary_controller_synthesis_time";
            }
            if ("手法別中核内訳の手法間比較可能性".equals(label)) {
                return "comparison_internal_core_time_cross_method_comparable";
            }
            if ("OTF-DUC 固有準備時間".equals(label)) {
                return "comparison_otf_specific_preparation_time";
            }
            if ("OTF-DUC のDCS時間（中核）".equals(label)) {
                return "comparison_otf_dcs_core_time";
            }
            if ("OTF-DUC の探索呼び出しから出力UC反映までの時間（中核）".equals(label)) {
                return "comparison_otf_dcs_core_time";
            }
            if ("Traditional DUC 固有準備時間".equals(label)) {
                return "comparison_traditional_specific_preparation_time";
            }
            if ("Traditional DUC のE_u構築+GR1合成時間（中核）".equals(label)
                    || "Traditional DUC の更新用環境構築+最終コントローラ合成時間（中核）".equals(label)) {
                return "comparison_traditional_eu_and_gr1_core_time";
            }
            if ("Traditional DUC の.old後処理時間".equals(label)) {
                return "comparison_traditional_old_action_postprocess_time";
            }
            if ("Stepwise Delayed DUC の最終コントローラ合成時間（中核）".equals(label)) {
                return "comparison_stepwise_delayed_gr1_core_time";
            }
            if ("Stepwise Delayed DUC の removeOldTransitions 実行時間".equals(label)) {
                return "comparison_stepwise_delayed_remove_old_transitions_time";
            }
        }
        if ("入力規模".equals(section)) {
            if ("old env component 数".equals(label)) {
                return "input_old_env_components";
            }
            if ("new env component 数".equals(label)) {
                return "input_new_env_components";
            }
            if ("map relation 数".equals(label)) {
                return "input_map_relations";
            }
            if ("mapping component 数".equals(label)) {
                return "input_mapping_components";
            }
            if ("old safety 数".equals(label)) {
                return "input_old_safety";
            }
            if ("new safety 数".equals(label)) {
                return "input_new_safety";
            }
            if ("Traditional DUC old safety fluent 数".equals(label)) {
                return "traditional_old_safety_fluents";
            }
            if ("Traditional DUC new safety fluent 数".equals(label)) {
                return "traditional_new_safety_fluents";
            }
            if ("Traditional DUC old/new safety fluent 数（重複排除後）".equals(label)) {
                return "traditional_old_new_safety_fluents_unique";
            }
            if ("Traditional DUC transition requirement fluent 数".equals(label)) {
                return "traditional_transition_requirement_fluents";
            }
            if ("Traditional DUC meta env fluent 数（重複排除後）".equals(label)) {
                return "traditional_meta_environment_fluents";
            }
            if ("OTF-DUC new safety fluent 数（重複排除後）".equals(label)) {
                return "otf_new_safety_fluents";
            }
            if ("transition requirement 数".equals(label)) {
                return "input_transition_requirements";
            }
            if ("controllable action 数".equals(label)) {
                return "input_controllable_actions";
            }
            if ("uncontrollable action 数".equals(label)) {
                return "input_uncontrollable_actions";
            }
            if ("uncontrollable action 数（推定）".equals(label)) {
                return "input_uncontrollable_actions_estimated";
            }
            if ("全 action 数（controllable + uncontrollable）".equals(label)) {
                return "input_total_actions";
            }
        }
        if ("入力規模 / 事前合成".equals(section) && "Old Controller".equals(label)) {
            return "old_controller";
        }
        if ("入力規模 / 事前合成".equals(section) && "New Controller".equals(label)) {
            return "new_controller";
        }
        if ("入力規模 / Mapping Environment Component".equals(section)
                && label != null
                && label.startsWith("Mapping Environment Component[")) {
            int start = label.indexOf('[');
            int end = label.indexOf(']', start + 1);
            if (start >= 0 && end > start + 1) {
                return "input_mapping_component_" + label.substring(start + 1, end);
            }
        }
        if ("入力規模 / Old Environment Component".equals(section)
                && label != null
                && label.startsWith("Old Environment Component[")) {
            int start = label.indexOf('[');
            int end = label.indexOf(']', start + 1);
            if (start >= 0 && end > start + 1) {
                return "input_old_env_component_" + label.substring(start + 1, end);
            }
        }
        if ("入力規模 / New Environment Component".equals(section)
                && label != null
                && label.startsWith("New Environment Component[")) {
            int start = label.indexOf('[');
            int end = label.indexOf(']', start + 1);
            if (start >= 0 && end > start + 1) {
                return "input_new_env_component_" + label.substring(start + 1, end);
            }
        }
        if ("入力規模 / Traditional Mapping Environment".equals(section)
                && "Traditional Mapping Environment".equals(label)) {
            return "traditional_mapping_environment";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            if (label.startsWith("[1. E_u]")) {
                return "traditional_eu";
            }
            if (label.startsWith("[2. Meta]")) {
                return "traditional_meta";
            }
            if (label.startsWith("[3. Pruned]")) {
                return "traditional_pruned";
            }
            if (label.startsWith("[4. Final]")) {
                return "traditional_final";
            }
        }
        if ("Stepwise Delayed DUC 分類統計".equals(section)) {
            if ("stage 数".equals(label)) {
                return "stepwise_delayed_stage_count";
            }
            if ("goal 数".equals(label)) {
                return "stepwise_delayed_goal_count";
            }
            if ("local goal 数".equals(label)) {
                return "stepwise_delayed_local_goal_count";
            }
            if ("cross goal 数".equals(label)) {
                return "stepwise_delayed_cross_goal_count";
            }
            if ("cross goal 比率（千分率）".equals(label)) {
                return "stepwise_delayed_cross_goal_ratio_per_mille";
            }
            if ("local old safety goal 数".equals(label)) {
                return "stepwise_delayed_local_old_safety_goal_count";
            }
            if ("local new safety goal 数".equals(label)) {
                return "stepwise_delayed_local_new_safety_goal_count";
            }
            if ("local transition goal 数".equals(label)) {
                return "stepwise_delayed_local_transition_goal_count";
            }
            if ("cross old safety goal 数".equals(label)) {
                return "stepwise_delayed_cross_old_safety_goal_count";
            }
            if ("cross new safety goal 数".equals(label)) {
                return "stepwise_delayed_cross_new_safety_goal_count";
            }
            if ("cross transition goal 数".equals(label)) {
                return "stepwise_delayed_cross_transition_goal_count";
            }
            if ("cross component 数".equals(label)) {
                return "stepwise_delayed_cross_component_count";
            }
            if ("cross goal 最大 scope size".equals(label)) {
                return "stepwise_delayed_cross_goal_max_scope_size";
            }
            if ("cross component 最大 scope size".equals(label)) {
                return "stepwise_delayed_cross_component_max_scope_size";
            }
            if ("all-stage cross goal 数".equals(label)) {
                return "stepwise_delayed_all_stage_cross_goal_count";
            }
            if ("all-stage cross component 数".equals(label)) {
                return "stepwise_delayed_all_stage_cross_component_count";
            }
            if ("all-stage cross goal あり".equals(label)) {
                return "stepwise_delayed_has_all_stage_cross_goal";
            }
            if ("requirement fluent 数（重複排除後）".equals(label)) {
                return "stepwise_delayed_requirement_fluent_count";
            }
            if ("cross component 構築時間".equals(label)) {
                return "stepwise_delayed_cross_component_build_time";
            }
            if ("分類統計計算・記録 CountTime".equals(label)) {
                return "stepwise_delayed_classification_statistics_count_time";
            }
        }
        if ("Stepwise Delayed DUC scope別要求数".equals(section)) {
            return "stepwise_delayed_scope_requirements_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC scope別fluent数".equals(section)) {
            return "stepwise_delayed_scope_fluents_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC scope別状態空間".equals(section)) {
            return "stepwise_delayed_scope_state_space_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC scope別時間".equals(section)) {
            return "stepwise_delayed_scope_time_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC cross scheduling".equals(section)) {
            return "stepwise_delayed_cross_scheduling_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC cross scheduling detail".equals(section)) {
            return "stepwise_delayed_cross_scheduling_detail_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC direct pruning 削減率".equals(section)) {
            return "stepwise_delayed_direct_pruning_reduction_" + metricToken(label);
        }
        if ("Stepwise Delayed DUC hotSwapIn connection".equals(section)) {
            return "stepwise_delayed_hot_swap_in_connection_" + metricToken(label);
        }
        if ("DCS (OTF-DUC)".equals(section)
                && "DCS で探索した状態数と遷移数の最大値".equals(label)) {
            return "otf_dcs_peak";
        }
        return "";
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        boolean needsQuote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!needsQuote) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static long sumRecordedTimes(String... keys) {
        long total = 0;
        for (String key : keys) {
            Long value = timeMillisByKey.get(key);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    private static long optionalTime(String section, String label) {
        Long value = timeMillisByKey.get(timeKey(section, label));
        return value == null ? 0 : value;
    }

    private static boolean hasRecordedTime(String section, String label) {
        return timeMillisByKey.containsKey(timeKey(section, label));
    }

    private static Long firstRecordedTime(String... keys) {
        for (String key : keys) {
            Long value = timeMillisByKey.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String timeKey(String section, String label) {
        return timerKey(section, label);
    }

    private static final class ActiveTimer {
        private final String section;
        private final String label;
        private final long startNanos;

        private ActiveTimer(String section, String label, long startNanos) {
            this.section = section;
            this.label = label;
            this.startNanos = startNanos;
        }
    }

    private static final class CountScope {
        private final String section;
        private final String label;
        private final String baseMetricKey;
        private long countTimeMillis;

        private CountScope(String section, String label, String baseMetricKey) {
            this.section = section;
            this.label = label;
            this.baseMetricKey = baseMetricKey;
        }
    }

    private static final class LineRef {
        private final String section;
        private final int index;

        private LineRef(String section, int index) {
            this.section = section;
            this.index = index;
        }
    }

    private static final class PeakCandidate {
        private final String key;
        private final String pairedKey;
        private final String stage;

        private PeakCandidate(String key, String stage) {
            this(key, "", stage);
        }

        private PeakCandidate(String key, String pairedKey, String stage) {
            this.key = key;
            this.pairedKey = pairedKey;
            this.stage = stage;
        }
    }

    private static final class PeakValue {
        private final long value;
        private final long pairedValue;
        private final String stage;

        private PeakValue(long value, String stage) {
            this(value, -1, stage);
        }

        private PeakValue(long value, long pairedValue, String stage) {
            this.value = value;
            this.pairedValue = pairedValue;
            this.stage = stage;
        }
    }

    private static final class StateSpaceObservation {
        private final String section;
        private final String label;
        private final long states;
        private final long transitions;

        private StateSpaceObservation(
                String section,
                String label,
                long states,
                long transitions) {
            this.section = section;
            this.label = label;
            this.states = states;
            this.transitions = transitions;
        }
    }

    /** A metric produced outside the synthesis child JVM. */
    public static final class ExternalDataMetric {
        private final String key;
        private final String section;
        private final String label;
        private final String value;
        private final String unit;
        private final String formula;

        public ExternalDataMetric(
                String key,
                String section,
                String label,
                String value,
                String unit) {
            this(key, section, label, value, unit, "");
        }

        public ExternalDataMetric(
                String key,
                String section,
                String label,
                String value,
                String unit,
                String formula) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("External metric key must not be empty");
            }
            this.key = key;
            this.section = section == null ? "" : section;
            this.label = label == null ? "" : label;
            this.value = value == null ? "" : value;
            this.unit = unit == null ? "" : unit;
            this.formula = formula == null ? "" : formula;
        }

        public String getKey() {
            return key;
        }
    }

    private static final class DataMetric {
        private final String key;
        private final String section;
        private final String label;
        private final String value;
        private final String unit;
        private final String formula;

        private DataMetric(String key, String section, String label, String value, String unit) {
            this(key, section, label, value, unit, "");
        }

        private DataMetric(String key, String section, String label, String value, String unit, String formula) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.formula = formula;
        }
    }

    private static final class MetricView {
        private final String descriptionId;
        private final String sectionReadableJa;
        private final String metricReadableJa;
        private final String category;
        private final String artifact;
        private final String phase;
        private final String action;
        private final String event;
        private final String stat;

        private MetricView(
                String descriptionId,
                String sectionReadableJa,
                String metricReadableJa,
                String category,
                String artifact,
                String phase,
                String action,
                String event,
                String stat) {
            this.descriptionId = descriptionId;
            this.sectionReadableJa = sectionReadableJa;
            this.metricReadableJa = metricReadableJa;
            this.category = category;
            this.artifact = artifact;
            this.phase = phase;
            this.action = action;
            this.event = event;
            this.stat = stat;
        }
    }
}
