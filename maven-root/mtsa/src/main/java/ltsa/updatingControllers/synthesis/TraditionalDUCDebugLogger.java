package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentUtils;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.CompactState;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 従来 DUC の旧コントローラ相当状態を確認するためのデバッグ出力。
 * 通常の評価ログに混ざらないよう、System property が有効な場合だけ出力する。
 */
public final class TraditionalDUCDebugLogger {

    private static final boolean ENABLED =
            Boolean.getBoolean("traditionalduc.debug")
                    || Boolean.getBoolean("duc.traditional.debug");

    private TraditionalDUCDebugLogger() {
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void logOldController(LTSOutput output, MTS<Long, String> oldController) {
        if (!isEnabled() || output == null || oldController == null) {
            return;
        }

        output.outln("");
        output.outln("================ Traditional DUC Old-State Debug ================");
        output.outln("有効化フラグ: -Dtraditionalduc.debug=true");
        output.outln("旧コントローラ状態数: " + oldController.getStates().size());
        output.outln("旧コントローラ遷移数: " + countTransitions(oldController));
        output.outln("旧コントローラ該当状態数の数え方:");
        output.outln("  E_u: 旧コントローラの元状態IDと一致する状態数も併記する。");
        output.outln("  metaEnv/safetyEnv/出力UC: 内部 BeginUpdate fluent（hotSwapIn で true）が false の状態を hotSwapIn 前の旧コントローラ相当状態として数える。");
        output.outln("================================================================");
    }

    public static void logStage(LTSOutput output, String stageName, MTS<Long, String> mts) {
        logStage(output, stageName, mts, null);
    }

    public static void logStage(
            LTSOutput output,
            String stageName,
            MTS<Long, String> mts,
            Set<Long> originalOldControllerStates) {
        if (!isEnabled() || output == null || mts == null) {
            return;
        }

        StageStats stats = inspect(mts, originalOldControllerStates);
        output.outln("");
        output.outln("[Traditional-DUC Debug] " + stageName);
        output.outln("  全状態数: " + stats.totalStates);
        output.outln("  全遷移数: " + stats.totalTransitions);
        if (stats.oldIdMatchedStates >= 0) {
            output.outln("  旧コントローラ状態IDと一致する状態数: " + stats.oldIdMatchedStates);
        }
        output.outln("  旧コントローラ該当状態数（内部BeginUpdate=false / hotSwapIn前）: " + stats.preBeginStates);
        output.outln("  hotSwapIn が出ている状態数: " + stats.beginUpdateSourceStates);
        output.outln("  hotSwapIn 遷移数: " + stats.beginUpdateTransitions);
        output.outln("  hotSwapIn 前状態から出る hotSwapIn 遷移数: " + stats.preBeginBeginUpdateTransitions);
    }

    public static void logCompactState(LTSOutput output, String stageName, CompactState compactState) {
        if (!isEnabled() || output == null || compactState == null) {
            return;
        }

        MTS<Long, String> mts = AutomataToMTSConverter.getInstance().convert(compactState);
        logStage(output, stageName, mts);
    }

    private static StageStats inspect(MTS<Long, String> mts, Set<Long> originalOldControllerStates) {
        StageStats stats = new StageStats();
        stats.totalStates = mts.getStates().size();
        stats.totalTransitions = countTransitions(mts);
        stats.oldIdMatchedStates = countOldIdMatches(mts, originalOldControllerStates);

        Set<Long> preBeginStates = getPreBeginStates(mts);
        stats.preBeginStates = preBeginStates.size();

        Set<Long> beginUpdateSources = new HashSet<Long>();
        for (Long state : mts.getStates()) {
            for (Pair<String, Long> transition : mts.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                if (UpdateConstants.BEGIN_UPDATE.equals(transition.getFirst())) {
                    beginUpdateSources.add(state);
                    stats.beginUpdateTransitions++;
                    if (preBeginStates.contains(state)) {
                        stats.preBeginBeginUpdateTransitions++;
                    }
                }
            }
        }
        stats.beginUpdateSourceStates = beginUpdateSources.size();

        return stats;
    }

    private static int countTransitions(MTS<Long, String> mts) {
        int count = 0;
        for (Long state : mts.getStates()) {
            count += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
            count += mts.getTransitions(state, MTS.TransitionType.MAYBE).size();
        }
        return count;
    }

    private static long countOldIdMatches(MTS<Long, String> mts, Set<Long> originalOldControllerStates) {
        if (originalOldControllerStates == null) {
            return -1;
        }

        long count = 0;
        for (Long state : mts.getStates()) {
            if (originalOldControllerStates.contains(state)) {
                count++;
            }
        }
        return count;
    }

    private static Set<Long> getPreBeginStates(MTS<Long, String> mts) {
        try {
            Set<Fluent> fluents = new HashSet<Fluent>();
            fluents.add(UpdatingControllersUtils.beginFluent);
            FluentStateValuation<Long> valuation = FluentUtils.getInstance().buildValuation(mts, fluents);

            Set<Long> result = new HashSet<Long>();
            for (Long state : mts.getStates()) {
                if (!valuation.isTrue(state, UpdatingControllersUtils.beginFluent)) {
                    result.add(state);
                }
            }
            return result;
        } catch (RuntimeException ex) {
            return Collections.emptySet();
        }
    }

    private static final class StageStats {
        private int totalStates;
        private int totalTransitions;
        private long oldIdMatchedStates = -1;
        private int preBeginStates;
        private int beginUpdateSourceStates;
        private int beginUpdateTransitions;
        private int preBeginBeginUpdateTransitions;
    }
}
