package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.DUCAbstraction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;

public class DUCExplorationHeuristic<State, Action> {

    Queue<CompostateDUC<State, Action>> frontier;
    DUCAbstraction<State,Action> abstraction;
    DirectedControllerSynthesisDUC<State,Action> dcs;
    
    private final Comparator<CompostateDUC<State, Action>> compostateRanker;
    private List<Set<State>> knownMarked;
    private List<Set<State>> goals;
    public Integer seq = 0;

    public DUCExplorationHeuristic(DirectedControllerSynthesisDUC<State,Action> dcs, int mappingStart, int mappingEnd) {
        this.dcs = dcs;
        this.compostateRanker = new CompostateRanker<>();
        
        this.knownMarked = new ArrayList<>(dcs.ltssSize);
        this.goals = new ArrayList<>(dcs.ltssSize);
        for (int lts = 0; lts < dcs.ltssSize; ++lts){
            this.knownMarked.add(new HashSet<>());
            this.goals.add(new HashSet<>());
        }

        this.abstraction = new DUCAbstraction<>(dcs, dcs.ltss, 0, mappingStart, mappingEnd, dcs.defaultTargets);
        this.frontier = new PriorityQueue<>(compostateRanker);
    }

    /**
     * 探索候補（フロンティア）の優先順位を決定する比較器。
     * 1. Marking Depth (進捗)
     * 2. Sequence ID (新しさ/LIFO)
     * 3. Action category (Uncontrollable -> update -> ordinary controllable)
     * 4. Heuristic Score
     */
    private class CompostateRanker<State, Action> implements Comparator<CompostateDUC<State, Action>> {
        @Override
        public int compare(CompostateDUC<State, Action> o1, CompostateDUC<State, Action> o2) {
            CompostateDUC<State, Action>.RecommendationDUC r1 = o1.peekRecommendation();
            CompostateDUC<State, Action>.RecommendationDUC r2 = o2.peekRecommendation();

            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;

            // --- Tier 1: Marking Depth (進捗フェーズ優先) ---
            int depth1 = getMarkingDepth(o1);
            int depth2 = getMarkingDepth(o2);
            // 深い(ゴールに近い)方を優先するため o2 - o1
            if (depth1 != depth2) return depth2 - depth1;

            // --- Tier 2: Sequence ID (深掘り優先 / LIFO) ---
            // 同じフェーズなら、より新しい状態(大きいseq)を優先する。
            // これにより、環境アクションの先(子)を親より先に調べ、逆伝播を狙う。
            if (o1.seq != o2.seq) return o2.seq - o1.seq;

            // --- Tier 3: Action category (U -> update -> C) ---
            int category1 = explorationCategory(r1);
            int category2 = explorationCategory(r2);
            if (category1 != category2) return category1 - category2;

            // --- Tier 4: Heuristic Score ---
            return r1.compareTo(r2);
        }

        private int explorationCategory(CompostateDUC<State, Action>.RecommendationDUC recommendation) {
            HAction<State, Action> action = recommendation.getAction();
            return DUCExplorationHeuristic.this.dcs.explorationActionCategoryRank(
                    action.toString(), action.isControllable());
        }

        /**
         * 状態ベクトルから Marking LTS の現在の深さを計算する。
         */
        private int getMarkingDepth(CompostateDUC<State, Action> c) {
            Object mStateObj = c.getStates().get(0);
            long mState = (mStateObj instanceof Long) ? (Long)mStateObj : ((Integer)mStateObj).longValue();

            return DUCExplorationHeuristic.this.dcs.markingDepthForHeuristic(mState);
        }
    }

    public Pair<CompostateDUC<State,Action>, HAction<State,Action>> getNextAction() {
        CompostateDUC<State,Action> state = getNextState();
        CompostateDUC<State,Action>.RecommendationDUC recommendation = state.nextRecommendation();
        return new Pair<>(state, recommendation.getAction());
    }

    public CompostateDUC<State,Action> getNextState() {
        long startRec = System.nanoTime();
        // 探索候補を取り出す前に、情報の鮮度をチェックして更新する。
        recomputeEstimates();
        dcs.addHeuristicRecomputeNanos(System.nanoTime() - startRec);

        long startQueue = System.nanoTime();
        removeNotLive();
        CompostateDUC<State,Action> state = frontier.remove();
        state.inOpen = false;
        dcs.addHeuristicFrontierNanos(System.nanoTime() - startQueue);
        
        return state;
    }

    private void recomputeEstimates() {
        // 以前のseq管理ロジックに基づき、変化（新しいGOALの発見など）があれば再評価を実行
        boolean updateNeeded = false;
        for (CompostateDUC<State, Action> s : this.frontier) {
            if (s.seq < this.seq) {
                updateNeeded = true;
                break;
            }
        }
        if (updateNeeded) {
            int recomputedStates = 0;
            // dcs.log("  [Debug-Heuristic] Global seq updated to " + this.seq + ". Recomputing frontier...");
            Queue<CompostateDUC<State, Action>> newFrontier = new PriorityQueue<>(this.compostateRanker);
            for (CompostateDUC<State, Action> s : this.frontier) {
                // Status.NONE 以外の状態（GOAL, UNSAFE, TRAPPED）はフロンティアから除外
                if (fullyExplored(s) || !s.isLive()
                    || !s.isStatus(Status.NONE)
                    /*s.status != CompostateDUC.Status.NONE*/) continue;
                if (s.seq < this.seq) {
                    // dcs.log("    Resetting state: " + s.getStates() + " (old seq: " + s.seq + ")");
                    // ここで eval -> updateRecommendation が呼ばれ、最新の子ステータスを反映する。
                    long evalStart = System.nanoTime();
                    abstraction.eval(s, this.knownMarked, this.goals);
                    dcs.addHeuristicEvaluationNanos(System.nanoTime() - evalStart);
                    dcs.incrementHeuristicEvaluationCalls();
                    recomputedStates++;
                    s.seq = this.seq;
                }
                newFrontier.add(s);
            }
            dcs.incrementHeuristicRecomputeRuns();
            dcs.addHeuristicRecomputedStates(recomputedStates);
            this.frontier = newFrontier;
        }
    }

    private void removeNotLive() {
        while (!frontier.isEmpty() && (!frontier.peek().isStatus(Status.NONE) /*frontier.peek().status != CompostateDUC.Status.NONE*/ || fullyExplored(frontier.peek()) || !frontier.peek().isLive())) {
            frontier.remove();
        }
    }
    
    private void maybeAddToFrontier(CompostateDUC<State, Action> state) {
        if (state.isStatus(Status.NONE) /*state.status == CompostateDUC.Status.NONE*/ && !fullyExplored(state) && !state.inOpen) {
            state.inOpen = true;
            state.live = true;
            this.frontier.add(state);
        }
    }

    public boolean somethingLeftToExplore() {
        removeNotLive();
        return !frontier.isEmpty();
    }

    public int frontierSize() {
        return frontier.size();
    }

    public void setInitialState(CompostateDUC<State, Action> state) {
        newState(state, null);
        maybeAddToFrontier(state);
    }

    /**
     * 新しい状態が生成された際の初期化処理。
     * 探索の時間軸（seq）を更新し、ヒューリスティック評価を実行します。
     */
    public void newState(CompostateDUC<State, Action> state, CompostateDUC<State, Action> parent) {
        // ★修正: 常に seq をインクリメントし、LIFO (子優先) のための時間軸を管理する
        this.seq++; 
        state.seq = this.seq; // この状態のユニークな ID を確定

        if(parent != null) state.setTargets(parent.getTargets());
        
        if (state.marked) {
            // ゴール(Marking 9)発見時は、他の状態の再評価を促すためにさらに seq を進める
            this.seq++; 
            state.addTargets(state);
            for (int lts = 0; lts < dcs.ltssSize; ++lts)
                this.knownMarked.get(lts).add(state.getStates().get(lts));
        }

        // DUCAbstraction.eval を呼び出し、アクション優先順位を決定する。
        long evalStart = System.nanoTime();
        abstraction.eval(state, this.knownMarked, this.goals);
        dcs.addHeuristicEvaluationNanos(System.nanoTime() - evalStart);
        dcs.incrementHeuristicEvaluationCalls();
    }

    public void notifyExpandingState(CompostateDUC<State, Action> parent, HAction<State, Action> action, CompostateDUC<State, Action> state) {
        if(state.wasExpanded()){
            state.setTargets(parent.getTargets());
            if (state.marked) state.addTargets(state);
        }
    }

    public void notifyStateSetErrorOrGoal(CompostateDUC<State, Action> state) {
        // 1. この状態を「生存（探索対象）」から外す
        state.live = false;
        // 2. この状態のアクション候補をクリアして、二度と expand されないようにする
        state.clearRecommendations();
        // 3. ステータスに応じた処理
        // Status 列挙型は CompostateDUC クラス内で定義されているため、完全修飾名で参照
        if (state.isStatus(Status.GOAL) /*state.status == CompostateDUC.Status.GOAL*/) {
            // 勝利確定時：時間軸（seq）を更新し、他の状態の再評価（recomputeEstimates）を促す
            this.seq++;
            for (int lts = 0; lts < dcs.ltssSize; ++lts) this.goals.get(lts).add(state.getStates().get(lts));
        }
    }

    public void expansionDone(CompostateDUC<State, Action> state, HAction<State, Action> action, CompostateDUC<State, Action> child) {
        maybeAddToFrontier(state);
        if (child != null) {
            maybeAddToFrontier(child);
        }
    }
    
    public void notifyExpansionDidntFindAnything(CompostateDUC<State, Action> p, HAction<State, Action> a, CompostateDUC<State, Action> c) {}
    public void notifyStateIsNone(CompostateDUC<State, Action> state) {}
    public boolean fullyExplored(CompostateDUC<State, Action> state) { return state.recommendations == null || state.recommendation == null; }
    public boolean hasUncontrollableUnexplored(CompostateDUC<State, Action> state) { return state.recommendation != null && !state.recommendation.getAction().isControllable(); }
    public void initialize(CompostateDUC<State, Action> state) {
        state.live = false;
        state.inOpen = false;
        state.controlled = true;
        state.targets = new ArrayList<>();
    }
}
