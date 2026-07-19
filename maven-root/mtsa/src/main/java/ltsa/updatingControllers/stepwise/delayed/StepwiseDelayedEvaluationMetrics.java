package ltsa.updatingControllers.stepwise.delayed;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;

import java.util.Set;

final class StepwiseDelayedEvaluationMetrics {

    static final String MAIN_SECTION = "Stepwise Delayed DUC";
    static final String CLASSIFICATION_SECTION = "Stepwise Delayed DUC 分類統計";
    static final String SCOPE_REQUIREMENT_SECTION = "Stepwise Delayed DUC scope別要求数";
    static final String SCOPE_FLUENT_SECTION = "Stepwise Delayed DUC scope別fluent数";
    static final String SCOPE_STATE_SPACE_SECTION = "Stepwise Delayed DUC scope別状態空間";
    static final String SCOPE_TIME_SECTION = "Stepwise Delayed DUC scope別時間";
    static final String CROSS_SCHEDULING_SECTION = "Stepwise Delayed DUC cross scheduling";
    static final String CROSS_SCHEDULING_DETAIL_SECTION =
            "Stepwise Delayed DUC cross scheduling detail";
    static final String DIRECT_PRUNING_REDUCTION_SECTION =
            "Stepwise Delayed DUC direct pruning 削減率";
    static final String HOT_SWAP_IN_CONNECTION_SECTION =
            "Stepwise Delayed DUC hotSwapIn connection";

    private StepwiseDelayedEvaluationMetrics() {
    }

    static void recordScopedStateSpace(
            String label,
            Set<Integer> scope,
            int states,
            int transitions,
            long countTime) {
        String scopedLabel = scopedMetricLabel(label, scope);
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / States",
                states,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / Transitions",
                transitions,
                "transitions");
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / CountTime",
                countTime,
                "ms");
    }

    static void recordScopedTime(String label, Set<Integer> scope, long timeMillis) {
        UpdatingControllerEvaluationRecorder.recordTime(
                SCOPE_TIME_SECTION,
                scopedMetricLabel(label, scope),
                Math.max(0, timeMillis));
    }

    static void recordScopedFluentCount(String label, Set<Integer> scope, Set<Fluent> fluents) {
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_FLUENT_SECTION,
                scopedMetricLabel(label, scope),
                fluents == null ? 0 : fluents.size(),
                "fluents");
    }

    static String scopedMetricLabel(String label, Set<Integer> scope) {
        return "scope " + displayStageScope(scope) + " " + label;
    }

    private static String displayStageScope(Set<Integer> stageScope) {
        StringBuilder display = new StringBuilder();
        display.append("{");
        int index = 0;
        for (Integer stageIndex : stageScope) {
            if (index > 0) {
                display.append(",");
            }
            display.append(stageIndex + 1);
            index++;
        }
        display.append("}");
        return display.toString();
    }
}
