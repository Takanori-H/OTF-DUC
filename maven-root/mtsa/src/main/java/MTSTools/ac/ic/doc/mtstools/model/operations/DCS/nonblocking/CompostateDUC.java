package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.collections.BidirectionalMap;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelationImpl;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HEstimate;

public class CompostateDUC<State, Action> {

    // (フィールド定義は変更なし)
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
    private boolean wasExpanded = false;
    private HEstimate<State, Action> estimate;
    public List<RecommendationDUC> recommendations;
    private Iterator<RecommendationDUC> recommendit;
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

    public CompostateDUC(DirectedControllerSynthesisDUC<State, Action> dcs, List<State> states) {
        this.dcs = dcs;
        this.states = states;
        this.status = Status.NONE;
        this.distance = DirectedControllerSynthesisDUC.INF;
        this.depth = DirectedControllerSynthesisDUC.INF;

        // ★修正: 生成順序(seq)をDCSから取得して設定 (DFS的探索のため)
        // DirectedControllerSynthesisDUC側に seq カウンタが必要だが、
        // ここでは簡易的に System.nanoTime() や、もしdcsにカウンタがあればそれを使う。
        // ヒューリスティック側で管理している場合はそちらに任せるが、初期値は入れておく。
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
        // Case A: State 0 (Observation Phase) - 特殊同期ロジック
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
                boolean isHotswap = cand.endsWith("Update");
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
            
            // Facilitators (前回の状態) を更新して終了
            dcs.facilitators = states;
            return validTransitions;
        }

        // ---------------------------------------------------------
        // Case B: State 1+ (Update Phase) - 標準DCSロジック (差分バグ修正版)
        // ---------------------------------------------------------
        
        // ★修正1: Marking状態が変わったかどうかを判定
        boolean markingChanged = false;
        if (dcs.facilitators != null) {
            Object oldMarking = dcs.facilitators.get(0);
            Object newMarking = states.get(0);
            if (!oldMarking.equals(newMarking)) {
                markingChanged = true;
            }
        }

        // ★修正2: Markingが変わった、または初回の場合は「全更新」
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
                    updateAllowedSetForComponent(i, markingState, false); // false = not remove phase (already removed)
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
    // /*
    private void updateAllowedSetForComponent(int i, long markingState, boolean isFullUpdate) {
        // 1. Trace = OFF の場合 (Update中のOC)
        // -> 何も追加しない (Block All)
        // if (!dcs.isTrace(i, markingState)) {
        //     return; 
        // }

        // 1. Trace = OFF の場合
        if (!dcs.isTrace(i, markingState)) {
            // ★修正: OC (Index 1) は切り離し中なのでアクションを生成させない (Block All)
            if (i == dcs.idxOC) return;

            // ★修正: Safetyなど他のコンポーネントは、監視停止中＝制約なし (Allow All)
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    dcs.allowed.add(i, hAction);
                }
            }
            return; 
        }

        // 2. Active の場合
        // -> 自身の遷移定義に従って allow
        if (dcs.isActive(i, markingState)) {
            for (Pair<Action,State> transition : dcs.ltss.get(i).getTransitions(states.get(i))) {
                HAction<State, Action> action = dcs.alphabet.getHAction(transition.getFirst());
                dcs.allowed.add(i, action);
            }
        } 
        // 3. Inactive (Passive) の場合
        // -> 全アクションを allow (Monitor)
        else {
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    dcs.allowed.add(i, hAction);
                }
            }
        }
    }
    // */
    /*
    private void updateAllowedSetForComponent(int i, long markingState, boolean isFullUpdate) {
        // 1. Trace = OFF の場合
        if (!dcs.isTrace(i, markingState)) {
            // OC (Index 1) は切り離し中なのでアクションを生成させない (Block All)
            if (i == dcs.idxOC) return;

            // Safetyなど他のコンポーネントは、監視停止中＝制約なし (Allow All)
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    dcs.allowed.add(i, hAction);
                }
            }
            return; 
        }

        // 2. Active の場合
        if (dcs.isActive(i, markingState)) {
            for (Pair<Action,State> transition : dcs.ltss.get(i).getTransitions(states.get(i))) {
                HAction<State, Action> action = dcs.alphabet.getHAction(transition.getFirst());
                dcs.allowed.add(i, action);
            }
        } 
        // 3. Inactive (Passive) の場合
        else {
            for (Action action : dcs.ltss.get(i).getActions()) {
                HAction<State, Action> hAction = dcs.alphabet.getHAction(action);
                if (hAction != null) {
                    // ★修正: OC (Index 1) が Inactive の場合、
                    // OC固有のアクション(_old)は Allow しない (Block)。
                    // ただし、更新事象など「他と共有しているアクション」は Allow する必要があるかもしれないが、
                    // OCのアクションは全て `_old` がついている (RenamedLTS) ため、
                    // 単純に「InactiveなOCは何もAllowしない」とすると、
                    // システムアクション(drill)に対するOCの反応(drill_old)もブロックされてしまう...？
                    
                    // いや、待ってください。
                    // TransitionSetの仕組みでは：
                    // 「OCが drill を知らない」 -> OCのBitは1 (Enable)
                    // 「OCが drill_old を知っている」 -> OCのBitは0 (Disable)。addすれば1になる。
                    
                    // Envが `drill` を提案する。 -> OCは `drill` を知らないので自動OK。 -> `drill` はEnabledになる。
                    // 以前の修正で `getChildStatesDUC` 内で `drill` -> `drill_old` 変換をしているので、
                    // 探索上は `drill` が選ばれれば OC も動ける。
                    
                    // 問題は `drill_old` が候補に出てくること。
                    // Envは `drill_old` を知らない -> 自動OK。
                    // OCは `drill_old` を知っている -> Inactiveだからここで add してしまう -> OKになる。
                    // 結果、`drill_old` がEnabledになる。
                    
                    // したがって、**「OCがInactiveのときは、_old付きのアクションを allowed に入れてはいけない」** が正解です。
                    
                    if (i == dcs.idxOC) {
                         // OCのアクションは全て _old 付きである前提。
                         // InactiveなOCは、自身のアルファベット（_oldアクション）をAllowしてはいけない。
                         // なぜなら、それをAllowすると「誰も知らないアクション」として成立してしまうから。
                         // 一方で、システムアクション(drill)に対しては「知らない」ので自動Allowされており、阻害しない。
                         continue; // Skip adding (Block _old actions)
                    }

                    dcs.allowed.add(i, hAction);
                }
            }
        }
    }
    */

    private boolean checkComponentAllows(int ltsIndex, String actionName) {
        Action matchedAction = null;
        for(Action a : dcs.ltss.get(ltsIndex).getActions()) {
            if(a.toString().equals(actionName)) {
                matchedAction = a;
                break;
            }
        }
        if (matchedAction == null) return true;
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

    // (以下、Getter/Setter等 変更なし)
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
    public void setHasGoalChild(HAction<State, Action> actionToGoal) { this.actionToGoal = actionToGoal; this.hasGoalChild = true; }
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
        recommendit = recommendations.iterator();
        updateRecommendation();
    }

    /**
     * アクション候補を更新する。
     * 子状態のステータスを確認し、既に勝利(GOAL)が確定したControllableな枝があるなら、
     * 他のControllableアクションは探索せずにスキップする。
     */
    private void updateRecommendation() {
        while (recommendit.hasNext()) {
            recommendation = recommendit.next();
            HAction<State, Action> action = recommendation.getAction();

            // === 修正箇所：重複探索防止のためのフィルタリング ===
            // 既に expandDUC によって探索（同期・展開）が行われたアクションであれば、
            // リストの順序変更やリセットに関わらずスキップして次の未探索アクションを探す。
            Set<CompostateDUC<State, Action>> alreadyExplored = exploredChildren.getImage(action);
            if (alreadyExplored != null && !alreadyExplored.isEmpty()) {
                continue;
            }
            // ===============================================

            // ★修正：子状態の状態を直接確認する（ご指摘のデバッグポイント）
            if (action.isControllable()) {
                boolean alreadyWon = false;
                // exploredChildren（既に展開済みの遷移先）を走査
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> explored : getExploredChildren()) {
                    // 他のControllableなアクションで、その先が既にStatus.GOALになっているものがあるか？
                    if (explored.getFirst().isControllable() && explored.getSecond().isStatus(Status.GOAL)) {
                        alreadyWon = true;
                        break;
                    }
                }
                
                if (alreadyWon) {
                    // すでに1つ勝ち筋が見つかっているため、この(C)はスキップして(U)を探す
                    continue; 
                }
            }

            estimate = recommendation.getEstimate(); 
            return; 
        }
        recommendation = null;
    }

    /** アクション候補を更新し、OR条件が充足している場合はControllableをスキップする */
    /*
    private void updateRecommendation() {
        // if を while に変更し、条件に合致するまでイテレータを進めるように修正
        while (recommendit.hasNext()) {
            recommendation = recommendit.next();

            // ★デバッグ表示: アクション評価時のフラグ状態
            System.out.println("  [Debug-Eval] State: " + this.states + " | Action: " + recommendation.getAction() + " | hasGoalChild: " + this.hasGoalChild);

            // ★修正点：OR条件の枝刈り（Pruning）
            // この状態で既にControllableな勝ち筋（hasGoalChild）が見つかっている場合、
            // 他のControllableアクションを探索するのは冗長であるためスキップし、
            // 未解決のUncontrollableアクション（AND枝）の検証を優先させる。
            if (this.hasGoalChild && recommendation.getAction().isControllable()) {
                // ★デバッグ表示: スキップの発生
                System.out.println("  [Debug-Pruning] Skipping controllable action: " + recommendation.getAction() + " because hasGoalChild is true.");
                continue;
            }

            estimate = recommendation.getEstimate(); 
            return; // 有効な候補（スキップ対象でないもの）が見つかれば戻る
        }
        // 候補が尽きた場合
        recommendation = null;
    }
    */

    /*
    private void updateRecommendation() {
        if (recommendit.hasNext()) {
            recommendation = recommendit.next();
            estimate = recommendation.getEstimate(); 
        } else {
            recommendation = null;
        }
    }
    */

    public void clearRecommendations() {
        if (isEvaluated()) {
            recommendations.clear();
            recommendit = null;
            recommendation = null;
        }
    }
    public boolean isLive() { return live; }
    public boolean isControlled() { return controlled; }
    private Integer countUncontrollables() {
        Integer result = 0;
        for (HAction<State, Action> a : this.transitions) { if (!a.isControllable()) result++; }
        return result;
    }
    @Override public String toString() { return states.toString(); }
}