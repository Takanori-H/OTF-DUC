package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.CompostateDUC;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisDUC;
import ltsa.updatingControllers.UpdateConstants;

public class DUCAbstraction<State, Action> {

    private static final int W_MARKING = 1000;

    private final int markingLTSIndex; 
    private final int mappingStart;
    private final int mappingEnd;
    private final DirectedControllerSynthesisDUC<State, Action> dcs;
    private List<Map<State, Integer>> envBFSDistanceMaps;

    public DUCAbstraction(DirectedControllerSynthesisDUC<State, Action> dcs, List<LTS<State, Action>> ltss,
            int markingIndex, int mappingStart, int mappingEnd, List<Set<State>> defaultTargets) {
        this.dcs = dcs;
        this.markingLTSIndex = markingIndex;
        this.mappingStart = mappingStart;
        this.mappingEnd = mappingEnd;
        
        this.envBFSDistanceMaps = new ArrayList<>();
        
        for (int i = mappingStart; i <= mappingEnd; i++) {
            Map<State, Integer> map = computeEnvBFSDistance(ltss.get(i), defaultTargets.get(i));
            envBFSDistanceMaps.add(map);
        }
    }

    private Map<State, Integer> computeEnvBFSDistance(LTS<State, Action> lts, Set<State> targets) {
        Map<State, Integer> distanceMap = new HashMap<>();
        Map<State, List<State>> reverseGraph = new HashMap<>();
        // for (State source : lts.getStates()) {
        //     for (Pair<Action, State> trans : lts.getTransitions(source)) {
        //         State target = trans.getSecond();
        //         reverseGraph.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
        //     }
        // }

        for (State source : lts.getStates()) {
            for (Pair<Action, State> trans : lts.getTransitions(source)) {
                State target = trans.getSecond();
                if (!reverseGraph.containsKey(target)) {
                    reverseGraph.put(target, new ArrayList<>());
                }
                reverseGraph.get(target).add(source);
            }
        }

        Queue<State> queue = new LinkedList<>();
        Set<State> visited = new HashSet<>();

        // for (State target : targets) {
        //     distanceMap.put(target, 0);
        //     visited.add(target);
        //     queue.add(target);
        // }

        for (State target : targets) {
            // 有効な状態のみ追加
            if (target != null && !target.equals(-1L)) {
                distanceMap.put(target, 0);
                visited.add(target);
                queue.add(target);
            }
        }

        while (!queue.isEmpty()) {
            State current = queue.poll();
            int dist = distanceMap.get(current);
            if (reverseGraph.containsKey(current)) {
                for (State pred : reverseGraph.get(current)) {
                    if (!visited.contains(pred)) {
                        visited.add(pred);
                        distanceMap.put(pred, dist + 1);
                        queue.add(pred);
                    }
                }
            }
        }
        return distanceMap;
    }

    private int getEnvHeuristic(List<State> currentStates) {
        int totalDist = 0;
        for (int i = mappingStart; i <= mappingEnd; i++) {
            State s = currentStates.get(i);
            Map<State, Integer> map = envBFSDistanceMaps.get(i - mappingStart);
            totalDist += map.getOrDefault(s, 10000); 
        }
        return totalDist;
    }

    // ★追加: Marking State ID を更新プロセスの深さ (0-5) に変換するヘルパーメソッド
    private int getMarkingDepth(long markingState) {
        if (markingState == 0) return 0; // 初期状態
        if (markingState == 1) return 1; // hotSwapIn 完了
        if (markingState == 2 || markingState == 3 || markingState == 5) return 2; // 更新イベントのいずれか1つ完了 (stopOld, reconfig, startNew)
        if (markingState == 4 || markingState == 6 || markingState == 7) return 3; // いずれか2つ完了
        if (markingState == 8) return 4; // 3つ全て完了 (Ready for hotSwapOut)
        if (markingState == 9) return 5; // hotSwapOut 完了 (Goal)
        return 0; // Fallback
    }

    /**
     * 探索順を決める eval 実装。
     * 1. Uncontrollable action を最優先する。
     * 2. 次に update action を優先する。
     * 3. 最後に ordinary controllable action を探索する。
     */
    public void eval(CompostateDUC<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        // long start = System.nanoTime();
        
        // ケース1：初回評価（リストの構築と初期ソート）
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations();
            for (HAction<State, Action> action : compostate.getTransitions()) {
                HEstimate<State, Action> estimate = calculateEstimate(compostate, action);
                // compostate.addRecommendation(action, estimate);
                // === 修正：estimate が null の場合は候補リスト（Recommendations）に入れない ===
                if (estimate != null) {
                    compostate.addRecommendation(action, estimate);
                }
                // =======================================================================
            }
            // 最初はインデックス 0 から全件ソート
            sortRecommendations(compostate.recommendations, 0);
            compostate.initRecommendations();
        } 
        // ケース2：再評価（構造的管理：ポインタを維持したまま未探索分を更新）
        else {
            int startIndex = compostate.getNextRecommendationIndex();
            int totalSize = compostate.recommendations.size();
            
            // 未探索のアクションが残っている場合のみ処理
            if (startIndex < totalSize) {

                // ★追加: 構造的管理の確認ログ (再評価の範囲を表示)
                // ※Compostateに getDcs() メソッドがあるか、dcsフィールドが可視であることを前提としています
                // compostate.log("    [Debug-Abstraction] Knowledge Update: Re-evaluating/sorting indices [" + startIndex + " to " + (totalSize - 1) + "] for state " + compostate.getStates());

                // startIndex 以降の要素（未探索セクション）のみスコアを計算し直す
                for (int i = startIndex; i < totalSize; i++) {
                    CompostateDUC<State, Action>.RecommendationDUC rec = compostate.recommendations.get(i);
                    rec.estimate = calculateEstimate(compostate, rec.getAction());
                }

                // startIndex 以降のサブリストのみをソート（既探索領域の順序は固定）
                sortRecommendations(compostate.recommendations, startIndex);
            }
            // else{compostate.log("    [Debug-Abstraction] Knowledge Update: All branches already explored for state " + compostate.getStates());}
        }
        
    }

    /**
     * 指定されたインデックス以降のサブリストをソートする。
     */
    private void sortRecommendations(List<CompostateDUC<State, Action>.RecommendationDUC> list, int fromIndex) {
        if (fromIndex >= list.size()) return;
        
        // サブリストを取得（Javaの subList は元のリストと連動しているため、これでソートが可能）
        List<CompostateDUC<State, Action>.RecommendationDUC> subList = list.subList(fromIndex, list.size());
        
        Collections.sort(subList, new Comparator<CompostateDUC<State, Action>.RecommendationDUC>() {
            @Override
            public int compare(CompostateDUC<State, Action>.RecommendationDUC r1, 
                               CompostateDUC<State, Action>.RecommendationDUC r2) {
                // 1. 探索カテゴリで比較: Uncontrollable -> update -> ordinary controllable。
                int categoryCompare = Integer.compare(explorationCategory(r1), explorationCategory(r2));
                if (categoryCompare != 0) return categoryCompare;

                // 2. 同じカテゴリ内では進捗スコア (HEstimate) で比較。
                int costCompare = r1.compareTo(r2);
                if (costCompare != 0) return costCompare;

                // 3. 同じカテゴリ・同じスコアなら辞書順にする。
                return r1.getAction().toString().compareTo(r2.getAction().toString());
            }
        });
    }

    private int explorationCategory(CompostateDUC<State, Action>.RecommendationDUC recommendation) {
        HAction<State, Action> action = recommendation.getAction();
        return dcs.explorationActionCategoryRank(action.toString(), action.isControllable());
    }

    /**
     * 個別のアクションに対するヒューリスティック評価値を計算する（共通ロジック）。
     */
    private HEstimate<State, Action> calculateEstimate(CompostateDUC<State, Action> compostate, HAction<State, Action> action) {

        // === 追加：先行ブロックフラグの反映 ===
        // 状態生成時に「不可」と判定された hotSwapOut は、評価値を与えず null を返す
        if (action.toString().equals(UpdateConstants.FINISH_UPDATE) && compostate.isFinishUpdateBlocked()) {
            return null; 
        }
        // ===================================

        List<State> currentStates = compostate.getStates();
        
        // 現在の進捗状況 (Marking Depth) の取得
        long currentMarkingId = -1;
        Object mStateObj = currentStates.get(markingLTSIndex);
        if (mStateObj instanceof Long) {
            currentMarkingId = (Long) mStateObj;
        } else if (mStateObj instanceof Integer) {
            currentMarkingId = ((Integer) mStateObj).longValue();
        }
        
        int currentDepth = (currentMarkingId != -1) ? dcs.markingDepthForHeuristic(currentMarkingId) : 0;
        String actionName = action.toString();
        int actionCost = dcs.actionPriorityCost(actionName);
        int predictedDepth = currentDepth;

        // 更新事象による進捗予測
        if (dcs.isUpdateActionForExploration(actionName)) {
            predictedDepth = currentDepth + 1;
        }

        // スコア計算: Marking重み * (最大深さ - 予測深さ) + 環境距離 + アクション固有コスト
        double markingScore = W_MARKING * (dcs.maxMarkingDepthForHeuristic() - predictedDepth);
        int envDist = getEnvHeuristic(currentStates);
        int totalScore = (int) markingScore + envDist + actionCost;

        return new HEstimate<>(1, new HDist(totalScore, 1));
    }
    
}
