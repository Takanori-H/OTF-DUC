package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.Compostate;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.*;
import ltsa.updatingControllers.UpdateConstants;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * OTF探索用の抽象化クラス。
 * BFSAbstractionを継承せず、独自のアクション優先順位に基づいて推奨遷移(Recommendation)を作成する。
 * 更新プロセスを迅速に完了させるため、更新事象およびゴール到達を最優先する。
 * * Priority Order:
 * 0: hotSwapOut (Goal)
 * 1: stopOldSpec
 * 2: reconfigure
 * 3: startNewSpec
 * 4: hotSwapIn
 * 10: Others
 */
public class OTFDFSAbstraction<State, Action> extends Abstraction<State, Action> {

    public OTFDFSAbstraction() {
    }

    @Override
    public void eval(Compostate<State, Action> compostate, List<Set<State>> knownMarked, List<Set<State>> goals) {
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations(); 

            for (HAction<State, Action> action : compostate.getTransitions()) {
                String actionName = action.getAction().toString();
                
                // 1. アクションの優先度（コスト）を取得
                int priority = getActionPriority(actionName);
                
                // 2. 推定値(Estimate)の作成
                // HDist(priority, 1) -> priorityが小さいほど高評価（リストの先頭に来る）
                HEstimate<State, Action> estimate = new HEstimate<>(1, new HDist(priority, 1));
                
                compostate.addRecommendation(action, estimate);
            }

            // 3. 優先順位に基づいてソート
            if (!compostate.recommendations.isEmpty()) {
                Collections.sort(compostate.recommendations, new OTFActionRanker());
            }

            compostate.initRecommendations(); 
        }
    }

    /**
     * アクション名に基づいて優先度（コスト）を返す。
     * 0に近いほど高優先（DFS/Greedy的に即座に選ばれる）。
     */
    private int getActionPriority(String actionName) {
        // [Priority 0] ゴールへの脱出 (最優先)
        if (actionName.equals(UpdateConstants.HOTSWAP_END)) {
            return 0; 
        }
        
        // [Priority 1] 旧仕様の停止
        if (actionName.equals("stopOldSpec")) {
            return 1;
        }

        // [Priority 2] 再構成
        if (actionName.equals("reconfigure")) {
            return 2;
        }

        // [Priority 3] 新仕様の開始
        if (actionName.equals("startNewSpec")) {
            return 3;
        }
        
        // [Priority 4] 更新開始 (更新中でなければこれを優先)
        if (actionName.equals(UpdateConstants.HOTSWAP_BEGIN)) {
            return 4;
        }
        
        // [Priority 10] その他のアクション（中間状態の事後処理や、通常の制御）
        return 10;
    }

    /**
     * Recommendationを優先度順（昇順）にソートするComparator
     */
    private class OTFActionRanker implements Comparator<Recommendation<State, Action>> {
        @Override
        public int compare(Recommendation<State, Action> r1, Recommendation<State, Action> r2) {
            return r1.compareTo(r2);
        }
    }
}
