package ltsa.updatingControllers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ltsa.lts.LTSOutput;

/**
 * 更新コントローラ合成の評価値を 1 回の合成単位で集約する recorder。
 */
public final class UpdatingControllerEvaluationRecorder {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String LEGACY_EVALUATION_ENABLED_PROPERTY = "updating.controller.evaluation.enabled";
    private static final String PRINT_DETAILED_REPORT_PROPERTY = "updating.controller.evaluation.printDetailedReport";
    private static final String METRIC_SCHEMA_VERSION = "2026-05-19";

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
    private static final Map<String, CountScope> countScopes = new LinkedHashMap<>();
    private static final List<String> activeCountScopes = new ArrayList<>();

    private static String mode = "未記録";
    private static ResultStatus resultStatus = ResultStatus.NOT_RECORDED;
    private static String failureMessage = "";
    private static boolean printed = false;
    private static long stateSpaceCountOverheadMillis = 0;
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
    private static String currentSummarySection = "";
    private static String otfExecutionMode = "";

    private UpdatingControllerEvaluationRecorder() {
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
        countScopes.clear();
        activeCountScopes.clear();
        mode = "未記録";
        resultStatus = ResultStatus.NOT_RECORDED;
        failureMessage = "";
        printed = false;
        stateSpaceCountOverheadMillis = 0;
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
        currentSummarySection = "";
        otfExecutionMode = "";
    }

    public static synchronized void setMode(String value) {
        if (!isEnabled()) {
            return;
        }
        if (value != null && !value.isEmpty()) {
            mode = value;
        }
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

    public static synchronized void beginFailureTimer(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        activeTimers.put(timerKey(section, label), new ActiveTimer(section, label, System.currentTimeMillis()));
        putOrReplace(section, label, label + " : 計測中");
    }

    public static synchronized void endFailureTimer(String section, String label) {
        if (!isEnabled()) {
            return;
        }
        ActiveTimer timer = activeTimers.remove(timerKey(section, label));
        if (timer != null) {
            putOrReplaceTime(timer.section, timer.label,
                    System.currentTimeMillis() - timer.startMillis,
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
        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_transitions", section, label + " / Transitions", Long.toString(transitions), "transitions");
        recordDataMetric(baseKey + "_count_time", section, label + " / CountTime", Long.toString(countTimeMillis), "ms");
        captureReferenceStateSpace(section, label, states, transitions);
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
                        + " ピークヒープ=" + padLeft(formatMiB(peakBytes), 8)
                        + " 開始時からの増減=" + padLeft(formatSignedMiB(deltaFromBaseline), 9)
                        + " 直前からの増減=" + padLeft(formatSignedMiB(deltaFromPrevious), 9));
        String baseKey = metricKey(normalizedSection, label);
        recordDataMetric(baseKey + "_current_heap", normalizedSection, label + " / 現在ヒープ", bytesToByteText(currentBytes), "B");
        recordDataMetric(baseKey + "_peak_heap", normalizedSection, label + " / ピークヒープ", bytesToByteText(peakBytes), "B");
        recordDataMetric(baseKey + "_delta_from_start", normalizedSection, label + " / 開始時からの増減", bytesToByteText(deltaFromBaseline), "B");
        recordDataMetric(baseKey + "_delta_from_previous", normalizedSection, label + " / 直前からの増減", bytesToByteText(deltaFromPrevious), "B");
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
        return isEnabled() && ("OTF-DUC".equals(mode) || "Traditional DUC".equals(mode));
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

        long headerOutputStart = System.currentTimeMillis();
        output.outln("");
        output.outln("================ EVALUATION ================");
        output.outln("Mode: " + mode);
        if ("OTF-DUC".equals(mode) && !otfExecutionMode.isEmpty()) {
            output.outln("OTF-DUC Execution Mode: " + otfExecutionMode);
        }
        output.outln("Result: " + resultStatus);
        if (isFailureStatus(resultStatus) && !failureMessage.isEmpty()) {
            output.outln("Failure reason: " + failureMessage);
        }
        output.outln("State/transition count overhead total: " + stateSpaceCountOverheadMillis + " ms");
        recordDataMetric("state_transition_count_overhead_total", "Evaluation Summary",
                "State/transition count overhead total", Long.toString(stateSpaceCountOverheadMillis), "ms");
        evaluationHeaderOutputMillis = System.currentTimeMillis() - headerOutputStart;

        long detailedReportOutputStart = System.currentTimeMillis();
        if (shouldPrintDetailedReport()) {
            for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
                output.outln("");
                output.outln("[" + entry.getKey() + "]");
                printSectionDescription(output, entry.getKey());
                for (String line : entry.getValue()) {
                    output.outln(line);
                }
            }
        } else {
            output.outln("Detailed metric report: omitted. Use -D"
                    + PRINT_DETAILED_REPORT_PROPERTY
                    + "=true to print the verbose human-readable report.");
        }
        output.outln("====================================================");
        output.outln("");
        evaluationDetailedReportOutputMillis = System.currentTimeMillis() - detailedReportOutputStart;

        long summaryOutputStart = System.currentTimeMillis();
        printEvaluationSummary(output);
        evaluationSummaryOutputMillis = System.currentTimeMillis() - summaryOutputStart;

        recordEvaluationOutputMetrics(false);
        recordCountScopeMetrics();
        recordComparisonSummary();
        printDataCsv(output);
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
                "EVALUATION ヘッダ、手法、結果、カウントオーバーヘッド行を Output に出す時間。");
        recordDataMetricWithFormula(
                "evaluation_output_detailed_report_time",
                "評価出力時間",
                "詳細評価レポート出力時間",
                Long.toString(Math.max(0, evaluationDetailedReportOutputMillis)),
                "ms",
                "詳細評価レポート本文を Output に出す時間。デフォルトでは省略メッセージのみ。");
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
            return "準備済みモデルから Traditional DUC または OTF-DUC の実際の合成処理を起動する入口。";
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            return "Traditional DUC で更新用環境から安全性制約反映後の環境を作り、最後に update controller を合成する処理。";
        }
        if ("Traditional DUC safetyEnv 構築時間内訳".equals(section)) {
            return "Traditional DUC の安全性制約反映後の環境を作る内部処理。Fluent 評価、安全性違反 pruning、DontDoTwice 合成を含む。";
        }
        if ("Traditional DUC GR1 時間内訳".equals(section)) {
            return "Traditional DUC の安全性制約反映後の環境を最終コントローラ合成器に渡し、出力コントローラを得る処理。";
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
            return "合成後の CompactState に対する共通後処理。Traditional DUC では .old action の relabel などを行う。";
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
            return "OTF-DUC と Traditional DUC を比較しやすいように、共通前処理や評価用オーバーヘッドを差し引いた集計。";
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
            notes.add("手法別の本体呼び出し時間: Traditional では最終コントローラ合成処理、OTF では探索入力準備から出力UC反映までの本体処理時間。");
            notes.add("Traditional DUC 更新用環境構築時間: 旧コントローラと Mapping Environment から更新中の振る舞いを表す環境を構築する時間。");
        } else if ("solveControlProblem (Traditional DUC)".equals(section)) {
            notes.add("安全性評価用合成環境構築時間: 安全性評価用に更新用環境と Fluent を組み合わせる時間。");
            notes.add("安全性制約反映後の環境構築時間: 安全性違反状態を除去する時間。");
            notes.add("最終コントローラ合成時間: 安全性制約反映後の環境から controller を合成する中核時間。");
        } else if ("generateDUC (OTF-DUC)".equals(section)) {
            notes.add("探索入力モデル準備時間: on-the-fly 探索に渡す Marking LTS、旧コントローラ、MapEnv、安全性などを並べる時間。");
            notes.add("New Controller の接続先の事前計算: hotSwapOut 後に新コントローラへ接続する状態対応表を作る時間。");
            notes.add("探索呼び出しから出力UC反映までの時間: OTF-DUC の探索器呼び出しから、出力UCをMTSA側の表現へ反映するまでの中心時間。");
        } else if ("Traditional DUC GR1 時間内訳".equals(section)) {
            notes.add("ゴール条件構築時間: guarantee / assumption などから最終コントローラ合成用のゴール条件を構築する時間。");
            notes.add("勝ち領域計算時間: 最終コントローラ合成ゲーム上で勝ち領域を求める時間。");
            notes.add("コントローラ戦略構築時間: 勝ち領域から controller strategy を作る時間。");
            notes.add("コントローラ戦略から出力用モデルを構築する時間: strategy を出力 controller の MTS に変換する時間。");
        } else if ("比較用時間集計".equals(section)) {
            notes.add("除外する共通前処理時間: 両手法に共通する旧コントローラ合成、Goal 準備、Mapping component 生成の合計。");
            notes.add("大枠比較用時間: 実測総時間から構文解析、評価用カウント、評価出力、GUI描画を除いた時間。");
            notes.add("厳密比較用時間: 大枠比較用時間からさらに共通前処理時間を除いた時間。");
            notes.add("手法固有時間: OTF-DUC または Traditional DUC に固有の準備・中核・後処理を合計した時間。");
            notes.add("内部計測の中核処理時間（参考）: OTF-DUC では on-the-fly探索から出力UC反映まで、Traditional DUC では更新用環境構築から最終コントローラ合成までを対象にした内部タイマー値。評価用カウント時間を含み得るため、主比較には実測時間から評価用オーバーヘッドを差し引いた項目を使う。");
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
        lines.add("段階                                           現在ヒープ ピークヒープ 開始時からの増減 直前からの増減");
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

    private static void flushActiveTimers() {
        if (activeTimers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ActiveTimer timer : activeTimers.values()) {
            putOrReplaceTime(timer.section, timer.label,
                    now - timer.startMillis,
                    " (失敗時点まで)");
        }
        activeTimers.clear();
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
        long methodCoreTime = methodCoreTime();
        long evaluationOutputTime = Math.max(0, evaluationOutputOverheadMillis);
        boolean hasControllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間");
        Long controllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間")
                ? optionalTime("共通 / HPWindow", "コントローラ合成時間")
                : null;
        long broadObservedTime = totalTime == null
                ? -1
                : Math.max(0, totalTime - parseTime - stateSpaceCountOverheadMillis
                        - evaluationOutputTime - drawTime);
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
                stateSpaceCountOverheadMillis,
                "状態数・遷移数を数えるための CountTime の合計。合成本来の処理ではない評価用オーバーヘッド。");
        addMetric(comparisonSection,
                "評価結果出力時間（比較から除外）",
                evaluationOutputTime,
                "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間。"
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
                    "大枠比較用時間（構文解析・評価・描画除外）",
                    broadObservedTime,
                    "実測総時間 - 構文解析時間 - 評価用カウント時間 - 評価結果出力時間 - GUI描画時間。共通前処理は差し引かない。");
            addMetric(comparisonSection,
                    "厳密比較用時間（共通前処理も除外）",
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
        addMetric(comparisonSection,
                "内部計測の中核処理時間（参考）",
                methodCoreTime,
                methodCoreFormula());
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
        return "手法が未記録のため 0。";
    }

    private static String methodCoreFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "探索器呼び出しから出力UC反映までの内部タイマー値。評価用カウント時間を含み得るため参考値。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC 更新用環境構築時間 + 最終コントローラ合成処理全体時間の内部タイマー値。評価用カウント時間を含み得るため参考値。";
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
        long parseTime = optionalTime("共通 / HPWindow", "構文解析時間");
        long compileIfChangeTotalTime = optionalTime("共通 / HPWindow", "compileIfChange 全体時間（参考）");
        long problemPreparationTime = optionalTime("共通 / HPWindow", "合成問題準備時間");
        long updateControllerGenerationTime = optionalTime("共通 / HPWindow", "update controller 生成時間");
        Long controllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間")
                ? optionalTime("共通 / HPWindow", "コントローラ合成時間")
                : null;
        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        long commonTotal = commonPreparationTime();
        long methodSpecificTotal = methodSpecificTime();
        long methodPreparationTotal = methodPreparationTime();
        long actualSynthesisTime = actualSynthesisTime();
        long methodOtherTime = methodSpecificTotal - methodPreparationTotal - actualSynthesisTime;
        long totalPreparationTime = commonTotal + methodPreparationTotal;
        Long totalWithoutCountAndDraw = totalTime == null
                ? null
                : totalTime - stateSpaceCountOverheadMillis - drawTime;
        Long controllerSynthesisWithoutCommon = controllerSynthesisTime == null
                ? null
                : controllerSynthesisTime - commonTotal;
        Long unclassifiedNonCommonTime = controllerSynthesisWithoutCommon == null
                ? null
                : controllerSynthesisWithoutCommon - methodSpecificTotal;

        output.outln("================ EVALUATION SUMMARY ================");
        printSummarySectionHeader(output, "全体");
        printSummaryValue(output, "手法", mode, "");
        if ("OTF-DUC".equals(mode)) {
            printSummaryValue(output, "OTF-DUC実行モード", otfExecutionMode,
                    "otfduc.simple.merge と otfduc.belief.repair の設定から分類。");
        }
        printSummaryValue(output, "結果", resultStatus.toString(), "");
        if (isFailureStatus(resultStatus) && !failureMessage.isEmpty()) {
            printSummaryValue(output, "失敗理由", failureMessage, "");
        }
        printSummaryMillis(output, "合成の全体時間", totalTime,
                "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        printSummaryMillis(output, "カウントによるオーバーヘッド", stateSpaceCountOverheadMillis,
                "状態数・遷移数を数える CountTime の合計。");
        printSummaryMillis(output, "コントローラ描画時間", drawTime,
                "HPWindow の「コントローラ描画時間」。");
        printSummaryMillis(output, "描画とカウントを除いた実測時間", totalWithoutCountAndDraw,
                "合成の全体時間 - カウントによるオーバーヘッド - コントローラ描画時間。");
        printSummaryMillis(output, "構文解析時間", parseTime,
                "HPWindow.docompile() 内の comp.compile() 実行時間。FSP/LTL/update controller 定義の解析と定義登録。");
        printSummaryMillis(output, "compileIfChange 全体時間（参考）", compileIfChangeTotalTime,
                "HPWindow の compileIfChange() 全体。構文解析時間 + 合成問題準備時間を含む参考値。");
        printSummaryMillis(output, "合成問題準備時間", problemPreparationTime,
                "HPWindow.docompile() 内の comp.continueCompilation(target) 実行時間。UpdatingControllersDefinition.compose などを含む。");
        printSummaryMillis(output, "update controller 生成時間", updateControllerGenerationTime,
                "HPWindow の TransitionSystemDispatcher.applyComposition(...) 実行時間。");
        printSummaryMillis(output, "コントローラ合成時間", controllerSynthesisTime,
                "合成問題準備時間 + update controller 生成時間。");
        printSummaryMillis(output, "共通準備時間", commonTotal,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        printSummaryMillis(output, "共通処理を除いたコントローラ合成時間", controllerSynthesisWithoutCommon,
                "コントローラ合成時間 - 共通準備時間。"
                        + " 手法固有として個別計測できた時間と、未分類の非共通時間を含む。");
        printSummaryMillis(output, "手法固有準備時間", methodPreparationTotal,
                methodPreparationFormula());
        printSummaryMillis(output, "合成用モデル準備時間", totalPreparationTime,
                "共通準備時間 + 手法固有準備時間。");
        printSummaryMillis(output, "実際の中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "手法固有内のその他時間", methodOtherTime,
                "手法固有として個別計測できた時間 - 手法固有準備時間 - 実際の中核合成時間。"
                        + " 主に出力構築・型変換・後処理など。");
        printSummaryMillis(output, "手法固有として個別計測できた時間", methodSpecificTotal,
                "手法固有準備時間 + 実際の中核合成時間 + 手法固有内のその他時間。");
        printSummaryMillis(output, "未分類の非共通時間", unclassifiedNonCommonTime,
                "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。"
                        + " 0 でない場合、共通ではないが個別計測項目に分類していない処理が残っている。");
        recordPeakStateSpaceSummaryMetrics();
        printSummaryDataMetric(output, "中間状態空間ピーク状態数", "peak_state_space_states",
                peakStateSpaceFormula("状態数"));
        printSummaryDataMetric(output, "中間状態空間ピーク遷移数", "peak_state_space_transitions",
                peakStateSpaceFormula("遷移数"));
        printSummaryDataMetric(output, "状態数ピークの段階", "peak_state_space_states_stage",
                "中間状態空間ピーク状態数を記録した段階。");
        printSummaryDataMetric(output, "遷移数ピークの段階", "peak_state_space_transitions_stage",
                "中間状態空間ピーク遷移数を記録した段階。");
        printSummaryDataMetric(output, "出力 update controller 状態数", "output_update_controller_states", "");
        printSummaryDataMetric(output, "出力 update controller 遷移数", "output_update_controller_transitions", "");
        printSummaryDataMetric(output, "全体ピークメモリ", "controller_synthesis_peak_memory",
                "共通 / HPWindow の「コントローラ合成全体のピークメモリ」。");
        printSummaryDataMetric(output, "増加メモリ", "controller_synthesis_memory_increase",
                "コントローラ合成全体のピークメモリ - コントローラ合成のベースラインメモリ。");

        printCommonSummary(output, commonTotal);
        if ("OTF-DUC".equals(mode)) {
            printOtfSummary(output, methodSpecificTotal, methodPreparationTotal, actualSynthesisTime, methodOtherTime);
        } else if ("Traditional DUC".equals(mode)) {
            printTraditionalSummary(output, methodSpecificTotal, methodPreparationTotal, actualSynthesisTime, methodOtherTime);
        }
        printOutputSummary(output);
        printMemorySummary(output);
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
        printSummaryDataMetric(output, "mapping component 状態数合計",
                metricKey("入力規模", "mapping component 状態数合計"), "");
        printSummaryDataMetric(output, "mapping component 遷移数合計",
                metricKey("入力規模", "mapping component 遷移数合計"), "");
        printSummaryDataMetric(output, "mapping component 最大状態数",
                metricKey("入力規模", "mapping component 最大状態数"), "");
        printSummaryDataMetric(output, "mapping component 最大遷移数",
                metricKey("入力規模", "mapping component 最大遷移数"), "");
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
        printSummaryDataMetric(output, "ベースラインメモリ", "controller_synthesis_base_memory", "");
        printSummaryDataMetric(output, "全体ピークメモリ", "controller_synthesis_peak_memory", "");
        printSummaryDataMetric(output, "増加メモリ", "controller_synthesis_memory_increase", "");
        printSummaryDataMetric(output, "合成終了時の現在ヒープ", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_current_heap", "");
        printSummaryDataMetric(output, "合成終了時のピークヒープ", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_peak_heap", "");
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
                new PeakCandidate("traditional_eu_states", "[1. E_u]"),
                new PeakCandidate("traditional_meta_states", "[2. Meta]"),
                new PeakCandidate("traditional_pruned_states", "[3. Pruned]"),
                new PeakCandidate("traditional_final_states", "[4. Final]"));
        PeakValue peakTransitions = maxDataMetric(
                new PeakCandidate("traditional_eu_transitions", "[1. E_u]"),
                new PeakCandidate("traditional_meta_transitions", "[2. Meta]"),
                new PeakCandidate("traditional_pruned_transitions", "[3. Pruned]"),
                new PeakCandidate("traditional_final_transitions", "[4. Final]"));

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
        }
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
        return "手法が未記録のため未記録。";
    }

    private static PeakValue maxDataMetric(PeakCandidate... candidates) {
        PeakValue max = null;
        for (PeakCandidate candidate : candidates) {
            Long value = dataMetricLong(candidate.key);
            if (value == null) {
                continue;
            }
            if (max == null || value > max.value) {
                max = new PeakValue(value, candidate.stage);
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
        return 0;
    }

    private static String actualSynthesisFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "OTF-DUC本体処理内の、探索器呼び出しから出力UCをMTSA側の表現へ反映するまでの時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC の「最終コントローラ合成時間」。";
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

    private static void printSummaryMillis(LTSOutput output, String label, long millis, String formula) {
        printSummaryMillis(output, label, Long.valueOf(millis), formula);
    }

    private static void printSummaryMillis(LTSOutput output, String label, Long millis, String formula) {
        String value = millis == null ? "未記録" : millis + " ms";
        printSummaryValue(output, label, value, formula);
        recordSummaryMillisMetric(label, millis, formula);
    }

    private static void printSummaryDataMetric(LTSOutput output, String label, String metricKey, String formula) {
        DataMetric metric = dataMetrics.get(metricKey);
        if (metric == null) {
            printSummaryValue(output, label, "未記録", formula);
            return;
        }
        String value = metric.value == null || metric.value.isEmpty() ? "未記録" : metric.value;
        if (metric.unit != null && !metric.unit.isEmpty() && !"text".equals(metric.unit)) {
            value = value + " " + metric.unit;
        }
        attachDataMetricFormula(metricKey, formula);
        printSummaryValue(output, label, value, formula);
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
        long csvOutputStart = System.currentTimeMillis();
        output.outln("================ EVALUATION DATA CSV ================");
        output.outln("mode,result,failure_reason,section,metric_key,metric_label,value,unit,formula,"
                + "metric_schema_version,metric_description_id,"
                + "section_readable_ja,metric_readable_ja,metric_category,artifact,phase,action,event,stat");
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
        evaluationDataCsvOutputMillis = System.currentTimeMillis() - csvOutputStart;
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
                "評価結果出力時間（比較から除外）",
                Long.toString(totalEvaluationOutputIncludingCsv),
                "ms",
                "評価ヘッダ出力時間 + 詳細評価レポート出力時間 + 評価サマリ出力時間 + 評価CSV出力時間。"));
        outputStrictComparisonRows(output, totalEvaluationOutputIncludingCsv);
        output.outln("=====================================================");
        output.outln("");
    }

    private static boolean isRecomputedAfterDataCsvMetric(String metricKey) {
        return "comparison_evaluation_output_time".equals(metricKey)
                || "comparison_observed_time_without_parse_count_evaluation_output_and_draw".equals(metricKey)
                || "comparison_strict_observed_time_without_parse_common_preprocess_count_evaluation_output_and_draw".equals(metricKey)
                || "comparison_observed_total_based_unclassified_time".equals(metricKey);
    }

    private static void outputStrictComparisonRows(LTSOutput output, long evaluationOutputMillis) {
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
        long observedWithoutParseCountOutputAndDraw = Math.max(0, totalTime - parseTime
                - stateSpaceCountOverheadMillis - evaluationOutputMillis - drawTime);
        long strictObservedTime = Math.max(0, observedWithoutParseCountOutputAndDraw - commonPreprocessTime);
        long strictUnclassifiedTime = Math.max(0, strictObservedTime - methodSpecificTime);

        outputDataRow(output, new DataMetric(
                "comparison_observed_time_without_parse_count_evaluation_output_and_draw",
                "比較用時間集計",
                "大枠比較用時間（構文解析・評価・描画除外）",
                Long.toString(observedWithoutParseCountOutputAndDraw),
                "ms",
                "実測総時間 - 構文解析時間 - 評価用カウント時間 - 評価出力時間合計（CSV含む） - GUI描画時間。共通前処理は差し引かない。"));
        outputDataRow(output, new DataMetric(
                "comparison_strict_observed_time_without_parse_common_preprocess_count_evaluation_output_and_draw",
                "比較用時間集計",
                "厳密比較用時間（共通前処理も除外）",
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
        MetricView view = metricView(metric);
        output.outln(csv(mode)
                + "," + csv(resultStatus.toString())
                + "," + csv(failureMessage)
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
                + "," + csv(view.stat));
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
        if (text.contains("time") || text.contains("時間") || text.contains("counttime") || "ms".equals(unit)) {
            return "時間";
        }
        if (text.contains("memory") || text.contains("メモリ") || "mb".equals(lower(unit))) {
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
        if (text.contains("rate") || "ratio".equals(lower(unit))) {
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
        if (text.contains("評価用カウント時間除外後")) {
            return "時間";
        }
        if (text.contains("counttime") || text.contains("評価用カウント時間")) {
            return "評価用カウント時間";
        }
        if (text.contains("states/value")
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
        if (text.contains("min")) {
            return "最小値";
        }
        if (text.contains("max")) {
            return "最大値";
        }
        if (text.contains("avg") || text.contains("average")) {
            return "平均値";
        }
        if (text.contains("rate")) {
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
        if ("ms".equals(unit)) {
            return "時間";
        }
        if ("mb".equals(lower(unit))) {
            return "メモリ";
        }
        if (!unit.isEmpty() && !"text".equals(unit)) {
            return unit;
        }
        return readableFallback(label);
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
            if ("Fluent とベース環境を並列合成した metaEnv 構築時間".equals(label)) {
                return "traditional_meta_environment_construction_time";
            }
            if ("metaEnv からエラーを枝刈りして safetyEnv を構築する時間".equals(label)) {
                return "traditional_safety_environment_pruning_time";
            }
            if ("safetyEnv を GR1 で解く時間".equals(label)) {
                return "traditional_gr1_solving_time";
            }
        }
        if ("TransitionSystemDispatcher".equals(section)
                && "removeOldTransitions 実行時間".equals(label)) {
            return "traditional_remove_old_transitions_time";
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
            if ("大枠比較用時間（構文解析・評価・描画除外）".equals(label)) {
                return "comparison_observed_time_without_parse_count_evaluation_output_and_draw";
            }
            if ("厳密比較用時間（共通前処理も除外）".equals(label)) {
                return "comparison_strict_observed_time_without_parse_common_preprocess_count_evaluation_output_and_draw";
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
            if ("評価結果出力時間（比較から除外）".equals(label)) {
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
            if ("内部計測の中核処理時間（参考）".equals(label)) {
                return "comparison_core_synthesis_time";
            }
            if ("手法本体の中核合成時間".equals(label)) {
                return "comparison_core_synthesis_time";
            }
            if ("主比較対象の中核合成時間".equals(label)) {
                return "comparison_core_synthesis_time";
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
            if ("mapping component 状態数合計".equals(label)) {
                return "input_mapping_component_states_total";
            }
            if ("mapping component 遷移数合計".equals(label)) {
                return "input_mapping_component_transitions_total";
            }
            if ("mapping component 最大状態数".equals(label)) {
                return "input_mapping_component_states_max";
            }
            if ("mapping component 最大遷移数".equals(label)) {
                return "input_mapping_component_transitions_max";
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
        private final long startMillis;

        private ActiveTimer(String section, String label, long startMillis) {
            this.section = section;
            this.label = label;
            this.startMillis = startMillis;
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
        private final String stage;

        private PeakCandidate(String key, String stage) {
            this.key = key;
            this.stage = stage;
        }
    }

    private static final class PeakValue {
        private final long value;
        private final String stage;

        private PeakValue(long value, String stage) {
            this.value = value;
            this.stage = stage;
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
