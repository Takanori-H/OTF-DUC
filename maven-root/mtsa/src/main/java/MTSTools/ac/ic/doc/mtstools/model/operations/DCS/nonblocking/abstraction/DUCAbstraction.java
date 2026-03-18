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

    private static final int COST_FINISH_UPDATE = 0;
    private static final int COST_STOP_OLD = 10;
    private static final int COST_RECONFIGURE = 20;
    private static final int COST_START_NEW = 30;
    private static final int COST_BEGIN_UPDATE = 40;
    private static final int COST_DEFAULT = 100;

    private static final int W_MARKING = 1000;

    private final int markingLTSIndex; 
    private final int mappingStart;
    private final int mappingEnd;
    private List<Map<State, Integer>> envBFSDistanceMaps;

    public DUCAbstraction(List<LTS<State, Action>> ltss, int markingIndex, int mappingStart, int mappingEnd, List<Set<State>> defaultTargets) {
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
        if (markingState == 1) return 1; // beginUpdate 完了
        if (markingState == 2 || markingState == 3 || markingState == 5) return 2; // 更新イベントのいずれか1つ完了 (stopOld, reconfig, startNew)
        if (markingState == 4 || markingState == 6 || markingState == 7) return 3; // いずれか2つ完了
        if (markingState == 8) return 4; // 3つ全て完了 (Ready for finishUpdate)
        if (markingState == 9) return 5; // finishUpdate 完了 (Goal)
        return 0; // Fallback
    }

    /**
     * アグレッシブな更新戦略に基づく eval 実装。
     * 1. 進捗スコア (Predicted Depth) により更新事象を最優先する。
     * 2. スコアが同じ場合は、安全性の検証のために Uncontrollable を優先する。
     */
    public void eval(CompostateDUC<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        // 1. 計測開始（ナノ秒単位）
        long start = System.nanoTime();
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations();
            List<State> currentStates = compostate.getStates();
            
            // 1. 現在の進捗状況 (Marking Depth) の取得
            long currentMarkingId = -1;
            Object mStateObj = currentStates.get(markingLTSIndex);
            if (mStateObj instanceof Long) {
                currentMarkingId = (Long) mStateObj;
            } else if (mStateObj instanceof Integer) {
                currentMarkingId = ((Integer) mStateObj).longValue();
            }
            
            int currentDepth = (currentMarkingId != -1) ? getMarkingDepth(currentMarkingId) : 0;

            // 2. 各アクションのスコア計算
            for (HAction<State, Action> action : compostate.getTransitions()) {
                String actionName = action.toString();
                int actionCost = getActionPriorityCost(actionName);
                int predictedDepth = currentDepth;
                
                // 更新事象によって Marking Depth が進むと予想される場合、スコアを大幅に良くする
                if (actionName.equals(UpdateConstants.BEGIN_UPDATE) && currentDepth == 0) {
                    predictedDepth = 1;
                } else if (actionName.equals(UpdateConstants.STOP_OLD_SPEC) || 
                           actionName.equals(UpdateConstants.RECONFIGURE) || 
                           actionName.equals(UpdateConstants.START_NEW_SPEC)) {
                    if (currentDepth >= 1 && currentDepth < 4) {
                        predictedDepth = currentDepth + 1;
                    }
                } else if (actionName.equals(UpdateConstants.FINISH_UPDATE) && currentDepth == 4) {
                    predictedDepth = 5;
                }

                // スコア計算式: W_MARKING * (5 - 予測進捗) + 環境距離 + アクションコスト
                // 更新事象は 3000台、環境アクションは 4000台のスコアになる
                double markingScore = W_MARKING * (5 - predictedDepth);
                int envDist = getEnvHeuristic(currentStates);
                int totalScore = (int) markingScore + envDist + actionCost;
                
                HEstimate<State, Action> estimate = new HEstimate<>(1, new HDist(totalScore, 1));
                compostate.addRecommendation(action, estimate);
            }

            // 3. ハイブリッド・ソート (進捗スコア > 役割優先)
            if (compostate.recommendations != null && !compostate.recommendations.isEmpty()) {
                Collections.sort(compostate.recommendations, new Comparator<CompostateDUC<State, Action>.RecommendationDUC>() {
                    @Override
                    public int compare(CompostateDUC<State, Action>.RecommendationDUC r1, 
                                       CompostateDUC<State, Action>.RecommendationDUC r2) {

                        // 第一優先: 進捗スコア (HEstimate)
                        // ここで更新事象 (3000台) が環境アクション (4000台) よりも先に並ぶ
                        int costCompare = r1.compareTo(r2);
                        if (costCompare != 0) return costCompare;

                        // 第二優先: スコアが同じ場合 (例: 共に環境アクション、または共に非更新のC)
                        // 安全性確認を優先するため、Uncontrollable (false) を Controllable (true) より先にする
                        boolean c1 = r1.getAction().isControllable();
                        boolean c2 = r2.getAction().isControllable();
                        
                        if (c1 != c2) {
                            return c1 ? 1 : -1; // c1がControllableなら後ろへ、Uncontrollableなら前へ
                        }

                        return 0;
                    }
                });
            }
            compostate.initRecommendations();
        }
        // 3. 計測終了と積算
        // DirectedControllerSynthesisDUC.DUCProfiler のように、
        // プロファイラが定義されている場所に合わせてアクセスしてください。
        DirectedControllerSynthesisDUC.DUCProfiler.timeEval += (System.nanoTime() - start);
    }

    /*
    public void eval(CompostateDUC<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations();
            List<State> currentStates = compostate.getStates();
            
            long currentMarkingId = -1;
            Object mStateObj = currentStates.get(markingLTSIndex);
            if(mStateObj instanceof Long) currentMarkingId = (Long)mStateObj;
            else if (mStateObj instanceof Integer) currentMarkingId = ((Integer)mStateObj).longValue();
            
            int currentDepth = (currentMarkingId != -1) ? getMarkingDepth(currentMarkingId) : 0;

            for (HAction<State, Action> action : compostate.getTransitions()) {
                String actionName = action.toString();
                int actionCost = getActionPriorityCost(actionName);
                
                // ★修正: 遷移後の予想Depthに基づいてスコア計算を行う
                int predictedDepth = currentDepth;
                
                if (actionName.equals(UpdateConstants.HOTSWAP_BEGIN) && currentDepth == 0) {
                    predictedDepth = 1;
                }
                else if (actionName.equals(UpdateConstants.STOP_OLD_SPEC) || actionName.equals(UpdateConstants.RECONFIGURE) || actionName.equals(UpdateConstants.START_NEW_SPEC)) {
                    // 更新アクションなら、基本的にはDepthが増える方向へ誘導
                    if (currentDepth >= 1 && currentDepth < 4) predictedDepth = currentDepth + 1;
                }
                else if (actionName.equals(UpdateConstants.HOTSWAP_END) && currentDepth == 4) {
                    predictedDepth = 5;
                }

                // Goal(Depth 5)に近いほどスコアが小さくなる（良くなる）
                double markingScore = W_MARKING * (5 - predictedDepth);
                
                int envDist = getEnvHeuristic(currentStates);
                int totalScore = (int)markingScore + envDist + actionCost;
                
                // 元の実装形式(HDist利用)に戻し、かつ型引数を明示
                HEstimate<State, Action> estimate = new HEstimate<State, Action>(1, new HDist(totalScore, 1));

                compostate.addRecommendation(action, estimate);
            }

            if (compostate.recommendations != null && !compostate.recommendations.isEmpty()) {
                Collections.sort(compostate.recommendations, new Comparator<CompostateDUC<State, Action>.RecommendationDUC>() {
                    @Override
                    public int compare(CompostateDUC<State, Action>.RecommendationDUC r1, CompostateDUC<State, Action>.RecommendationDUC r2) {

                        // まずコスト(HEstimate)で比較する (Depth優先)
                         int costCompare = r1.compareTo(r2);
                         if (costCompare != 0) return costCompare;

                        // コストが同じ(タイブレーク)の場合のみ、Controllableを優先する
                        boolean c1 = r1.getAction().isControllable();
                        boolean c2 = r2.getAction().isControllable();
                        int i1 = c1 ? 1 : 0;
                        int i2 = c2 ? 1 : 0;
                        return i2 - i1;
                        
                        //Uncontrollable (0) が Controllable (1) より小さい = 先に来る
                        //  int res = i1 - i2;

                        //  Controllable (1) が Uncontrollable (0) より小さい = 先に来るようにする
                        // int res = i2 - i1;
                        // if (res == 0) return r1.compareTo(r2);
                        // return res;
                    }
                });
            }
            compostate.initRecommendations();
        }
    }
    // */

    /**
     * 現在の状態における各アクションの評価（推定スコア）を計算し、
     * 探索の優先順位に従ってソートした推薦リストを作成します。
     */
    /*
    public void eval(CompostateDUC<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations();
            List<State> currentStates = compostate.getStates();
            
            // 1. 現在の Marking State ID とそれに基づく Depth の取得
            long currentMarkingId = -1;
            Object mStateObj = currentStates.get(markingLTSIndex);
            if (mStateObj instanceof Long) {
                currentMarkingId = (Long) mStateObj;
            } else if (mStateObj instanceof Integer) {
                currentMarkingId = ((Integer) mStateObj).longValue();
            }
            
            int currentDepth = (currentMarkingId != -1) ? getMarkingDepth(currentMarkingId) : 0;

            // 2. 各遷移（アクション）の推定コスト計算
            for (HAction<State, Action> action : compostate.getTransitions()) {
                String actionName = action.toString();
                int actionCost = getActionPriorityCost(actionName);
                
                // 遷移後の予想される更新進捗（Depth）を推定
                int predictedDepth = currentDepth;
                
                if (actionName.equals(UpdateConstants.HOTSWAP_BEGIN) && currentDepth == 0) {
                    predictedDepth = 1;
                }
                else if (actionName.equals(UpdateConstants.STOP_OLD_SPEC) || 
                         actionName.equals(UpdateConstants.RECONFIGURE) || 
                         actionName.equals(UpdateConstants.START_NEW_SPEC)) {
                    // 更新イベントが発火すれば Depth が進むと推定
                    if (currentDepth >= 1 && currentDepth < 4) {
                        predictedDepth = currentDepth + 1;
                    }
                }
                else if (actionName.equals(UpdateConstants.HOTSWAP_END) && currentDepth == 4) {
                    predictedDepth = 5; // Goal 達成
                }

                // スコア計算：Goal（Depth 5）に近いほど合計値が小さくなるように設計
                // 式: W_MARKING * (5 - 予測Depth) + 環境依存距離 + アクション固有コスト
                double markingScore = W_MARKING * (5 - predictedDepth);
                int envDist = getEnvHeuristic(currentStates);
                int totalScore = (int) markingScore + envDist + actionCost;
                
                // 推定値オブジェクトの生成
                HEstimate<State, Action> estimate = new HEstimate<State, Action>(1, new HDist(totalScore, 1));
                compostate.addRecommendation(action, estimate);
            }

            // 3. ハイブリッド優先戦略に基づくソートの実行
            if (compostate.recommendations != null && !compostate.recommendations.isEmpty()) {
                final long sortMarkingId = currentMarkingId;

                Collections.sort(compostate.recommendations, new Comparator<CompostateDUC<State, Action>.RecommendationDUC>() {
                    @Override
                    public int compare(CompostateDUC<State, Action>.RecommendationDUC r1, 
                                       CompostateDUC<State, Action>.RecommendationDUC r2) {

                        boolean c1 = r1.getAction().isControllable();
                        boolean c2 = r2.getAction().isControllable();

                        if (sortMarkingId == 0) {
                            // --- Marking 0 (旧仕様フェーズ) ---
                            // Anytime Hotswap 実現のため、進捗コスト（hotswap_begin の優先度）を第一優先とする
                            int costCompare = r1.compareTo(r2);
                            if (costCompare != 0) return costCompare;

                            // コストが同じ場合のみ、Controllable を優先
                            return (c1 == c2) ? 0 : (c1 ? -1 : 1);
                        } else {
                            // --- Marking 1-9 (更新プロセス中) ---
                            // ★修正: アクションの種類 (Uncontrollable) を進捗スコアよりも優先
                            // これにより、進捗が悪くても環境動作 (in 等) を先に探索し、安全性を確認する
                            if (c1 != c2) {
                                // c1がU(false)でc2がC(true)なら -1 (c1を優先)
                                return c1 ? 1 : -1;
                            }

                            // アクションの種類が同じ (U同士、またはC同士) であれば、進捗コストで比較
                            return r1.compareTo(r2);
                        }
                    }
                });
            }
            
            compostate.initRecommendations();
        }
    }
    */

    /*
    public void eval(CompostateDUC<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations();
            List<State> currentStates = compostate.getStates();
            
            long currentMarkingId = -1;
            Object mStateObj = currentStates.get(markingLTSIndex);
            if(mStateObj instanceof Long) currentMarkingId = (Long)mStateObj;
            
            for (HAction<State, Action> action : compostate.getTransitions()) {
                // Formula: TotalScore = W * (5 - MarkingDepth) + BFS_Env + ActionCost
                
                int actionCost = getActionPriorityCost(action.toString());
                double markingScore = 0;
                
                // ★修正: Marking IDそのものではなく、Depthに基づいてスコア計算
                if (currentMarkingId != -1) {
                    int depth = getMarkingDepth(currentMarkingId);
                    // ゴール(Depth 5)に近いほどスコアが小さくなる（優先される）
                    markingScore = W_MARKING * (5 - depth);
                }
                
                int envDist = getEnvHeuristic(currentStates);
                int totalScore = (int)markingScore + envDist + actionCost;
                
                HEstimate<State, Action> estimate = new HEstimate<>(1, new HDist(totalScore, 1));
                compostate.addRecommendation(action, estimate);
            }

            if (compostate.recommendations != null && !compostate.recommendations.isEmpty()) {
                Collections.sort(compostate.recommendations, new Comparator<CompostateDUC<State, Action>.RecommendationDUC>() {
                     @Override
                     public int compare(CompostateDUC<State, Action>.RecommendationDUC r1, CompostateDUC<State, Action>.RecommendationDUC r2) {
                         boolean c1 = r1.getAction().isControllable();
                         boolean c2 = r2.getAction().isControllable();
                         int i1 = c1 ? 1 : 0;
                         int i2 = c2 ? 1 : 0;
                         int res = i1 - i2;
                         if (res == 0) return r1.compareTo(r2);
                         return res;
                     }
                });
            }
            compostate.initRecommendations();
        }
    }
    */

    private int getActionPriorityCost(String actionName) {
        if (actionName.equals(UpdateConstants.FINISH_UPDATE)) return COST_FINISH_UPDATE;
        if (actionName.equals(UpdateConstants.STOP_OLD_SPEC)) return COST_STOP_OLD;
        if (actionName.equals(UpdateConstants.RECONFIGURE)) return COST_RECONFIGURE;
        if (actionName.equals(UpdateConstants.START_NEW_SPEC)) return COST_START_NEW;
        if (actionName.equals(UpdateConstants.BEGIN_UPDATE)) return COST_BEGIN_UPDATE;
        return COST_DEFAULT;
    }
}