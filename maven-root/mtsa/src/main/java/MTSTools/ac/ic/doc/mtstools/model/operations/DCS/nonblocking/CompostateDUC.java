package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.collections.BidirectionalMap;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelationImpl;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HEstimate;
import ltsa.updatingControllers.UpdateConstants;

public class CompostateDUC<State, Action> {

    private final DirectedControllerSynthesisDUC<State, Action> dcs;
    private final List<State> states;
    public Status status;
    private int distance;
    private int depth;
    public Integer seq = -1;
    public Set<HAction<State, Action>> vertices;
    public BidirectionalMap<HAction<State, Action>, HAction<State, Action>> edges;
    public Map<HAction<State, Action>, Set<Integer>> readyInLTS;
    final boolean marked;
    private final BinaryRelation<HAction<State, Action>, CompostateDUC<State, Action>> exploredChildren;
    private final Set<CompostateDUC<State, Action>> childrenExploredThroughUncontrollable;
    private final Set<CompostateDUC<State, Action>> childrenExploredThroughControllable;
    private final BinaryRelation<HAction<State, Action>, CompostateDUC<State, Action>> parents;
    public final Set<HAction<State, Action>> transitions;
    private Pair<Integer, CompostateDUC<State, Action>> bestControllableChild;
    private HAction<State, Action> potentiallyGoodTransition;
    private boolean hasGoalChild = false;
    public HAction<State,Action> actionToGoal;
    private HAction<State,Action> directorActionToGoal;
    private boolean wasExpanded = false;
    private HEstimate<State, Action> estimate;
    public List<RecommendationDUC> recommendations;

    private int nextRecommendationIndex = 0; // 探索済みの境界線

    RecommendationDUC recommendation;
    public boolean live;
    boolean inOpen;
    public List<Set<State>> targets;
    public boolean controlled;
    public boolean heuristicStronglySuggestsIsError = false;
    public int uncontrollableTransitions;
    public int unexploredTransitions;
    int uncontrollableUnexploredTransitions;
    HashMap<HAction<State, Action>, HEstimate<State, Action>> estimates;
    public final Integer uncontrollablesCount;

    // hotSwapOutがこの状態で発火可能かどうかのフラグ
    private boolean finishUpdateBlocked = false;

    public CompostateDUC(DirectedControllerSynthesisDUC<State, Action> dcs, List<State> states) {
        this.dcs = dcs;
        this.states = states;
        this.status = Status.NONE;
        this.distance = DirectedControllerSynthesisDUC.INF;
        this.depth = DirectedControllerSynthesisDUC.INF;

        // 生成順序はヒューリスティック側で管理する。ここでは初期値だけを入れる。
        this.seq = 0;

        this.exploredChildren = new BinaryRelationImpl<>();
        this.childrenExploredThroughUncontrollable = new HashSet<>();
        this.childrenExploredThroughControllable = new HashSet<>();
        this.parents = new BinaryRelationImpl<>();
        this.bestControllableChild = new Pair<>(-1,null);
        
        boolean marked = true;
        for (int lts = 0; marked && lts < this.dcs.ltssSize; ++lts)
            marked = this.dcs.defaultTargets.get(lts).contains(states.get(lts));
        this.marked = marked;
        
        this.transitions = buildTransitions();
        dcs.heuristic.initialize(this);
        this.uncontrollablesCount = countUncontrollables();
    }

    public class RecommendationDUC implements Comparable<RecommendationDUC> {
        public HAction<State, Action> action;
        public HEstimate<State, Action> estimate;
        public RecommendationDUC(HAction<State, Action> action, HEstimate<State, Action> estimate) {
            this.action = action;
            this.estimate = estimate;
        }
        public HAction<State, Action> getAction() { return action; }
        public HEstimate<State, Action> getEstimate() { return estimate; }
        @Override
        public int compareTo(RecommendationDUC o) { return estimate.compareTo(o.estimate); }
        @Override
        public String toString() { return action.toString() + estimate; }
    }

    private Set<HAction<State, Action>> buildTransitions() {
        long markingState = getMarkingState();

        // ---------------------------------------------------------
        // 場合 A: markingState 0（更新前 / 旧コントローラフェーズ）
        //
        // 旧コントローラの action は "<action>_old" として公開し、OTF 探索上は
        // uncontrollable として扱う。これにより、1 回の on-the-fly 探索で
        // 旧コントローラ全状態からの更新パスを調べられる。
        // hotSwapIn/hotSwapOut などの hotswap action は元の名前のまま扱う。
        // ---------------------------------------------------------
        if (markingState == 0) {
            Set<String> candidates = new HashSet<>();
            for (int i = 0; i < states.size(); ++i) {
                if (dcs.isActive(i, markingState)) {
                    for (Pair<Action, State> transition : dcs.ltss.get(i).getTransitions(states.get(i))) {
                        candidates.add(transition.getFirst().toString());
                    }
                }
            }
            Set<HAction<State, Action>> validTransitions = new HashSet<>();
            for (String cand : candidates) {
                String baseName = cand.replace("_old", "");
                String ocName = baseName + "_old"; 
                boolean isHotswap = UpdateConstants.BEGIN_UPDATE.equals(cand)
                        || UpdateConstants.FINISH_UPDATE.equals(cand);
                if (isHotswap) ocName = cand; 

                boolean allAgreed = true;
                for (int i = 0; i < dcs.ltssSize; ++i) {
                    if (!dcs.isActive(i, markingState) && !dcs.isTrace(i, markingState)) {
                        continue;
                    }
                    String targetName = (i == dcs.idxOC && !isHotswap) ? ocName : baseName;
                    if (!checkComponentAllows(i, targetName)) {
                        allAgreed = false;
                        break;
                    }
                }
                if (allAgreed) {
                    String finalName = isHotswap ? cand : ocName;
                    @SuppressWarnings("unchecked")
                    Action actionObj = (Action) finalName;
                    HAction<State, Action> hAction = dcs.alphabet.getHAction(actionObj);
                    if (hAction != null) validTransitions.add(hAction);
                }
            }
            dcs.facilitators = states;
            return validTransitions;
        }

        // ---------------------------------------------------------
        // 場合 B: markingState 1+（更新中フェーズ）
        //
        // 旧コントローラは trace から外れ、mapping/new-safety コンポーネントは
        // 更新フェーズに応じて有効化される。以降は通常の同期 action 集合を
        // 差分更新で構築する。
        // ---------------------------------------------------------
        
        // Marking 状態が変わったかどうかを判定する。
        boolean markingChanged = false;
        if (dcs.facilitators != null) {
            Object oldMarking = dcs.facilitators.get(0);
            Object newMarking = states.get(0);
            if (!oldMarking.equals(newMarking)) {
                markingChanged = true;
            }
        }

        // Marking が変わった場合、または初回の場合は「全更新」とする。
        // そうでなければ「差分更新」
        boolean fullUpdate = (dcs.facilitators == null) || markingChanged;

        if (fullUpdate) {
            // --- フル更新 (全コンポーネント再計算) ---
            // 既存のallowedを一旦クリアするのが理想だが、TransitionSetにclearがない場合、
            // 全LTSについてremove相当のことをするか、あるいはTransitionSetの実装依存。
            // ここでは安全のため、facilitators=nullと同じロジックで全件addする前に
            // 以前の状態(もしあれば)のものをremoveしておく必要がある。
            // しかし、dcs.allowedの実装によっては重複addは問題ない。
            // 重複removeは副作用があるかもしれないので、Markingが変わった時は
            // 「前のMarkingでの設定を全解除」->「今のMarkingでの設定を全適用」が必要。
            
            if (dcs.facilitators != null) {
                // 前の状態の設定を全解除
                // ※非効率だがActive/Inactiveが切り替わるため、前の状態での全アクションをremoveするのが安全
                for (int i = 0; i < dcs.ltssSize; ++i) {
                    for (Action action : dcs.ltss.get(i).getActions()) {
                        HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                        if(hAction != null) dcs.allowed.remove(i, hAction);
                    }
                }
            }

            // 新しい状態の設定を適用
            for (int i = 0; i < states.size(); ++i) {
                updateAllowedSetForComponent(i, markingState, true);
            }

        } else {
            // --- 差分更新 (Markingが変わっていない場合のみ) ---
            for (int i = 0; i < states.size(); ++i) {
                if (!dcs.facilitators.get(i).equals(states.get(i))) {
                    // 1. 古い状態の遷移を削除 (以前はこうだった)
                    // -> ここでは簡易的に「全アクションをremove」してから再登録する方式にする
                    //    (Active/Inactiveが変わらない前提なら遷移だけで良いが、念のため)
                    for (Action action : dcs.ltss.get(i).getActions()) {
                        HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                        if(hAction != null) dcs.allowed.remove(i, hAction);
                    }

                    // 2. 新しい状態の遷移を追加
                    updateAllowedSetForComponent(i, markingState, false); // false: 削除処理は既に済んでいる。
                }
            }
        }
        
        Set<HAction<State, Action>> result = new HashSet<>(dcs.allowed.getEnabled());
        dcs.facilitators = states;
        return result;
    }

    /**
     * コンポーネントi の allowed セットを現在のフラグ状況に基づいて更新する
     * @param i コンポーネントIndex
     * @param markingState 現在のMarking
     * @param isFullUpdate 呼び出し元が全更新モードか (使用していないが拡張性のため)
     */
    private void updateAllowedSetForComponent(int i, long markingState, boolean isFullUpdate) {
        if (dcs.isFineGrainedMode() && i == dcs.idxMarking) {
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null && dcs.isFineGrainedProgressActionEnabled(markingState, action.toString())) {
                    dcs.allowed.add(i, hAction);
                }
            }
            return;
        }

        // 1. Trace = OFF の場合。
        if (!dcs.isTrace(i, markingState)) {
            // OC (Index 1) は切り離し中なので action を生成させない。
            if (i == dcs.idxOC) return;

            // Safety など他のコンポーネントは監視停止中なので制約なしとして扱う。
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    dcs.allowed.add(i, hAction);
                }
            }
            return; 
        }

        // 2. Active の場合は、自身の遷移定義に従って許可する。
        if (dcs.isActive(i, markingState)) {
            for (Pair<Action,State> transition : dcs.ltss.get(i).getTransitions(states.get(i))) {
                HAction<State, Action> action = dcs.alphabet.getHAction(transition.getFirst());
                dcs.allowed.add(i, action);
            }
        } 
        // 3. Inactive の場合は、モニタとして全 action を許可する。
        else {
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    dcs.allowed.add(i, hAction);
                }
            }
        }
    }

    private boolean checkComponentAllows(int ltsIndex, String actionName) {
        if (dcs.isFineGrainedMode() && ltsIndex == dcs.idxMarking) {
            return dcs.isFineGrainedProgressActionEnabled(getMarkingState(), actionName);
        }
        Action matchedAction = null;
        for(Action a : dcs.ltss.get(ltsIndex).getActions()) {
            if(a.toString().equals(actionName)) {
                matchedAction = a;
                break;
            }
        }
        if (matchedAction == null) return true;
        if (!dcs.isActive(ltsIndex, getMarkingState())) return true;
        State curr = states.get(ltsIndex);
        Set<State> targets = dcs.ltss.get(ltsIndex).getTransitions(curr).getImage(matchedAction);
        if (targets != null && !targets.isEmpty()) return true;
        return false; 
    }

    private long getMarkingState() {
        Object mStateObj = states.get(0);
        if (mStateObj instanceof Long) return (Long) mStateObj;
        if (mStateObj instanceof Integer) return ((Integer) mStateObj).longValue();
        return -1;
    }

    // Getter / Setter など。
    public HEstimate<State, Action> getEstimate() { return estimate; }
    public List<State> getStates() { return states; }
    public int getDistance() { return distance; }
    public void setDistance(int distance) { this.distance = distance; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { if (this.depth > depth) this.depth = depth; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { if (this.status != Status.ERROR || status == Status.ERROR) this.status = status; }
    public boolean isStatus(Status status) { return this.status == status; }
    public boolean hasGoalChild(){ return hasGoalChild; }

    /**
     * GOAL 子を見つけたことを記録する暫定情報。
     * この情報だけでは、この状態自体の winning action とは限らない。
     */
    public void setHasGoalChild(HAction<State, Action> actionToGoal) { this.actionToGoal = actionToGoal; this.hasGoalChild = true; }

    /**
     * 状態が GOAL と証明されたときに、director 出力で採用できる action を記録する。
     */
    public void setDirectorActionToGoal(HAction<State, Action> actionToGoal) { this.directorActionToGoal = actionToGoal; }
    public HAction<State, Action> getDirectorActionToGoal() { return directorActionToGoal; }
    public Set<HAction<State, Action>> getTransitions() { return transitions; }
    public void addChild(HAction<State, Action> action, CompostateDUC<State, Action> child) {
        if(action.isControllable()){
            childrenExploredThroughControllable.add(child);
        } else {
            childrenExploredThroughUncontrollable.add(child);
        }
        exploredChildren.addPair(action, child);
    }
    public BinaryRelation<HAction<State, Action>,CompostateDUC<State, Action>> getExploredChildren() { return exploredChildren; }
    public Set<CompostateDUC<State, Action>> getChildrenExploredThroughUncontrollable() { return childrenExploredThroughUncontrollable; }
    public Set<CompostateDUC<State, Action>> getChildrenExploredThroughControllable() { return childrenExploredThroughControllable; }
    public int getChildDistance(HAction<State, Action> action) {
        int result = -1; 
        for (CompostateDUC<State, Action> compostate : exploredChildren.getImage(action)) { 
            if (result < compostate.getDistance())
                result = compostate.getDistance();
        }
        return result;
    }
    public void addParent(HAction<State, Action> action, CompostateDUC<State, Action> parent) {
        parents.addPair(action, parent);
        setDepth(parent.getDepth() + 1);
    }
    public BinaryRelation<HAction<State, Action>,CompostateDUC<State, Action>> getParents() { return parents; }
    public HAction<State, Action> getPotentiallyGoodTransition() { return potentiallyGoodTransition; }
    public void setPotentiallyGoodTransition(HAction<State, Action> potentiallyGoodTransition) { this.potentiallyGoodTransition = potentiallyGoodTransition; }
    public void setBestControllable(Integer i, CompostateDUC<State, Action> c) { bestControllableChild = new Pair<>(i,c); }
    public Pair<Integer, CompostateDUC<State, Action>> getBestControllable() { return bestControllableChild; }
    public boolean wasExpanded() { return wasExpanded; }
    public void setExpanded() { wasExpanded = true; }
    public boolean isEvaluated() { return recommendations != null; }
    public List<Set<State>> getTargets() { return targets; }
    public void setTargets(List<Set<State>> targets) { this.targets = targets; }
    public void addTargets(CompostateDUC<State, Action> compostate) {
        List<State> states = compostate.getStates();
        if (targets.isEmpty()) {
            targets = new ArrayList<>(dcs.ltssSize);
            for (int lts = 0; lts < dcs.ltssSize; ++lts)
                targets.add(new HashSet<>());
        }
        for (int lts = 0; lts < dcs.ltssSize; ++lts)
            targets.get(lts).add(states.get(lts));
    }
    public void setupRecommendations() {
        if (recommendations == null) recommendations = new ArrayList<>();
    }
    public void addRecommendation(HAction<State, Action> action, HEstimate<State, Action> estimate) {
        boolean controllableAction = action.isControllable();
        controlled &= controllableAction; 
        if(!estimate.isConflict())
            recommendations.add(new RecommendationDUC(action, estimate));
        if(!controllableAction && estimate.isConflict()){
            this.heuristicStronglySuggestsIsError = true;
        }
    }
    public RecommendationDUC nextRecommendation() {
        RecommendationDUC result = recommendation;
        updateRecommendation();
        return result;
    }
    public RecommendationDUC peekRecommendation() { return recommendation; }

    public void initRecommendations() {
        // 初回のみ updateRecommendation を呼び、現在候補を初期化する。
        if (recommendation == null && nextRecommendationIndex == 0) {
            updateRecommendation();
        }
    }

    /**
     * アクション候補を更新する。
     * 子状態のステータスを確認し、既に勝利(GOAL)が確定したControllableな枝があるなら、
     * 他のControllableアクションは探索せずにスキップする。
     */
    private void updateRecommendation() {
        // インデックスを用いて候補リストを走査する。
        // リセット（seq更新）が発生しても nextRecommendationIndex は維持されるため、
        // 構造的に重複探索を防止する。
        while (nextRecommendationIndex < recommendations.size()) {

            // 現在のインデックスを保持
            int currentIndex = nextRecommendationIndex;

            recommendation = recommendations.get(nextRecommendationIndex++);
            HAction<State, Action> action = recommendation.getAction();

            // 構造的な候補管理を確認するためのデバッグログ。
            // dcs.log("    [Debug-Structural] State " + this.states + ": Yielding action at index [" + currentIndex + "/" + recommendations.size() + "]: " + action);

            // 状態自体が GOAL と確定した後は、追加の controllable 候補は出力戦略に不要。
            // ただし、GOAL 子を 1 つ見ただけの暫定段階では、非決定分岐や他の候補の
            // 探索を止めてはいけない。
            if (action.isControllable() && isStatus(Status.GOAL)) {
                // dcs.log("    [Debug-Structural]   -> Skipped (State already won)");
                continue;
            }

            estimate = recommendation.getEstimate();
            return;
        }
        recommendation = null;
    }

    public void clearRecommendations() {
        if (isEvaluated()) {
            recommendations.clear();
            // イテレータの代わりにインデックスをリセットし、現在の候補を null にする。
            nextRecommendationIndex = 0; 
            recommendation = null;
        }
    }

    public int getNextRecommendationIndex() {
        return nextRecommendationIndex;
    }
    public boolean isLive() { return live; }
    public boolean isControlled() { return controlled; }
    private Integer countUncontrollables() {
        Integer result = 0;
        for (HAction<State, Action> a : this.transitions) { if (!a.isControllable()) result++; }
        return result;
    }
    @Override public String toString() { return states.toString(); }
    public void log(String message) {
        if (this.dcs != null) {
            this.dcs.log(message);
        }
    }

    public void setFinishUpdateBlocked(boolean b) {
        this.finishUpdateBlocked = b;
    }

    public boolean isFinishUpdateBlocked() {
        return finishUpdateBlocked;
    }
}
