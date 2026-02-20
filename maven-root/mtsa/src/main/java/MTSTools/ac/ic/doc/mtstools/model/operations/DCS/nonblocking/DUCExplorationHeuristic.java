package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisDUC.DUCProfiler;
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

        this.abstraction = new DUCAbstraction<>(dcs.ltss, 0, mappingStart, mappingEnd, dcs.defaultTargets);
        this.frontier = new PriorityQueue<>(compostateRanker);
    }

    /**
     * 探索候補（フロンティア）の優先順位を決定する比較器。
     * 1. Marking Depth (進捗)
     * 2. Sequence ID (新しさ/LIFO)
     * 3. Heuristic Score (アグレッシブ度)
     */
    private static class CompostateRanker<State, Action> implements Comparator<CompostateDUC<State, Action>> {
        @Override
        public int compare(CompostateDUC<State, Action> o1, CompostateDUC<State, Action> o2) {
            
            // --- Tier 1: Marking Depth (進捗フェーズ優先) ---
            int depth1 = getMarkingDepth(o1);
            int depth2 = getMarkingDepth(o2);
            // 深い(ゴールに近い)方を優先するため o2 - o1
            if (depth1 != depth2) return depth2 - depth1;

            // --- Tier 2: Sequence ID (深掘り優先 / LIFO) ---
            // 同じフェーズなら、より新しい状態(大きいseq)を優先する。
            // これにより、環境アクションの先(子)を親より先に調べ、逆伝播を狙う。
            if (o1.seq != o2.seq) return o2.seq - o1.seq;

            // --- Tier 3: Heuristic Score (アクション役割優先) ---
            // 進捗も新しさも同じなら、evalで計算されたスコア(Update > U > C)で選ぶ。
            CompostateDUC<State, Action>.RecommendationDUC r1 = o1.peekRecommendation();
            CompostateDUC<State, Action>.RecommendationDUC r2 = o2.peekRecommendation();
            
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;

            return r1.compareTo(r2);
        }

        /**
         * 状態ベクトルから Marking LTS の現在の深さを計算する。
         */
        private int getMarkingDepth(CompostateDUC<State, Action> c) {
            Object mStateObj = c.getStates().get(0);
            long mState = (mStateObj instanceof Long) ? (Long)mStateObj : ((Integer)mStateObj).longValue();

            if (mState == 0) return 0; // 初期
            if (mState == 1) return 1; // hotswap_begin
            if (mState == 9) return 5; // Goal (hotswap_end)
            
            // 2,3,5(1つ完了) -> Depth 2
            // 4,6,7(2つ完了) -> Depth 3
            // 8(3つ完了)     -> Depth 4
            long mask = mState - 1;
            return 1 + Long.bitCount(mask);
        }
    }

    /*
    private static class CompostateRanker<State, Action> implements Comparator<CompostateDUC<State, Action>> {
        @Override
        public int compare(CompostateDUC<State, Action> o1, CompostateDUC<State, Action> o2) {
            // ★修正: 最優先で「現在のMarking Depth」を比較する (深い方が優先)
            int depth1 = getMarkingDepth(o1);
            int depth2 = getMarkingDepth(o2);
            // 大きい方を優先(先頭)にするため o2 - o1
            if (depth1 != depth2) {
                return depth2 - depth1;
            }

            // Depthが同じなら、ヒューリスティック・スコアで比較
            CompostateDUC<State, Action>.RecommendationDUC r1 = o1.peekRecommendation();
            CompostateDUC<State, Action>.RecommendationDUC r2 = o2.peekRecommendation();
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;

            int comparison = r1.compareTo(r2);
            if (comparison != 0) return comparison;

            // スコアも同じなら、LIFO (DFS的)
            return o2.seq - o1.seq;
        }

        //uncontrollableの子を先に探索する実装
        @Override
        public int compare(CompostateDUC<State, Action> o1, CompostateDUC<State, Action> o2) {
            // 優先順位 1: Marking Depth (進捗フェーズを合わせる)
            int depth1 = getMarkingDepth(o1);
            int depth2 = getMarkingDepth(o2);
            if (depth1 != depth2) return depth2 - depth1;

            // 優先順位 2: Sequence ID (LIFO/DFS的な深掘り)
            // ★修正: スコア比較の前に seq を比較することで、子(新しい方)を確実に優先する
            if (o1.seq != o2.seq) return o2.seq - o1.seq;

            // 優先順位 3: ヒューリスティック・スコア (内部の U/C 優先順位など)
            CompostateDUC<State, Action>.RecommendationDUC r1 = o1.peekRecommendation();
            CompostateDUC<State, Action>.RecommendationDUC r2 = o2.peekRecommendation();
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;

            return r1.compareTo(r2);
        }

        // Marking State ID から Depth を計算するヘルパー
        private int getMarkingDepth(CompostateDUC<State, Action> c) {
            Object mStateObj = c.getStates().get(0);
            long markingState = -1;
            if (mStateObj instanceof Long) markingState = (Long) mStateObj;
            else if (mStateObj instanceof Integer) markingState = ((Integer) mStateObj).longValue();

            if (markingState == 0) return 0;
            if (markingState == 1) return 1;
            if (markingState == 9) return 5;
            
            // State 1..8 は mask = state - 1
            // Depth = 1 + bitCount(mask)
            // State 2 (Mask 1: 001) -> Depth 2
            // State 4 (Mask 3: 011) -> Depth 3
            // State 8 (Mask 7: 111) -> Depth 4
            long mask = markingState - 1;
            return 1 + Long.bitCount(mask);
        }
    }
    */

    /*
    private static class CompostateRanker<State, Action> implements Comparator<CompostateDUC<State, Action>> {
        @Override
        public int compare(CompostateDUC<State, Action> o1, CompostateDUC<State, Action> o2) {
            CompostateDUC<State, Action>.RecommendationDUC r1 = o1.peekRecommendation();
            CompostateDUC<State, Action>.RecommendationDUC r2 = o2.peekRecommendation();
            if (r1 == null && r2 == null) return 0;
            if (r1 == null) return 1;
            if (r2 == null) return -1;

            // 1. 推定スコア (HEstimate) で比較
            // (Depthが進んでいる方が圧倒的に小さくなるはず)
            int comparison = r1.compareTo(r2);
            if (comparison != 0) return comparison;

            // 2. スコアが同じ場合、生成順序 (seq) で比較 (降順 = LIFO = DFS的)
            // 新しい状態(seqが大きい)を先に探索する
            // seq は Integer なので o2 - o1
            return o2.seq - o1.seq;

            // return r1.compareTo(r2);
        }
    }
    */

    public Pair<CompostateDUC<State,Action>, HAction<State,Action>> getNextAction() {
        CompostateDUC<State,Action> state = getNextState();
        CompostateDUC<State,Action>.RecommendationDUC recommendation = state.nextRecommendation();
        return new Pair<>(state, recommendation.getAction());
    }

    public CompostateDUC<State,Action> getNextState() {
        long startRec = System.nanoTime();
        // ★修正した点：探索候補を取り出す前に、情報の鮮度をチェックして更新する
        recomputeEstimates();
        DUCProfiler.timeRecompute += (System.nanoTime() - startRec);

        long startQueue = System.nanoTime();
        removeNotLive();
        CompostateDUC<State,Action> state = frontier.remove();
        state.inOpen = false;
        DUCProfiler.timeFrontier += (System.nanoTime() - startQueue);
        
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
            Queue<CompostateDUC<State, Action>> newFrontier = new PriorityQueue<>(this.compostateRanker);
            for (CompostateDUC<State, Action> s : this.frontier) {
                if (fullyExplored(s) || !s.isLive() || !s.isStatus(Status.NONE)) continue;
                if (s.seq < this.seq) {
                    s.clearRecommendations();
                    s.recommendations = null;
                    // ここで eval -> updateRecommendation が呼ばれ、最新の「子のStatus」がチェックされる
                    abstraction.eval(s, this.knownMarked, this.goals);
                    s.seq = this.seq;
                }
                newFrontier.add(s);
            }
            this.frontier = newFrontier;
        }
    }
    /**
     * 他の状態でゴールやマーク済み状態が見つかった場合（seqが進んだ場合）、
     * 現在待ちリストにある状態のアクション候補を再計算させる。
     */
    /*
    private void recomputeEstimates() {
        boolean updateNeeded = false;
        for (CompostateDUC<State, Action> state : this.frontier) {
            if (state.seq < this.seq) {
                updateNeeded = true;
                break;
            }
        }

        if (updateNeeded) {
            // PriorityQueueを再構築してソート順もリセットする
            Queue<CompostateDUC<State, Action>> newFrontier = new PriorityQueue<>(this.compostateRanker);
            for (CompostateDUC<State, Action> state : this.frontier) {
                if (fullyExplored(state) || !state.isLive()) continue;

                if (state.seq < this.seq) {
                    // 情報が古い場合、以前の推薦リストをクリアして再評価を行う
                    // これにより、CompostateDUC.updateRecommendation() が呼ばれ、
                    // 最新の hasGoalChild フラグに基づいた枝刈りが行われる。
                    state.clearRecommendations();
                    state.recommendations = null;
                    abstraction.eval(state, this.knownMarked, this.goals);
                    state.seq = this.seq;
                }
                newFrontier.add(state);
            }
            this.frontier = newFrontier;
        }
    }
    */

    private void removeNotLive() {
        while (!frontier.isEmpty() && (!frontier.peek().isStatus(Status.NONE) || fullyExplored(frontier.peek()) || !frontier.peek().isLive())) {
            frontier.remove();
        }
    }
    
    private void maybeAddToFrontier(CompostateDUC<State, Action> state) {
        if (state.isStatus(Status.NONE) && !fullyExplored(state) && !state.inOpen) {
            state.inOpen = true;
            state.live = true;
            this.frontier.add(state);
        }
    }

    public boolean somethingLeftToExplore() {
        removeNotLive();
        return !frontier.isEmpty();
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

        // DUCAbstraction.eval を呼び出し、アグレッシブなアクション優先順位を決定
        abstraction.eval(state, this.knownMarked, this.goals);
    }

    /**
     * 新しい状態が生成されるたびに呼ばれる。
     * 条件を外して seq を常にインクリメントし、時間軸を厳格に管理する。
     * uncontrollableアクションを先に探索した後，そのまま子を探索する
     */
    /*
    public void newState(CompostateDUC<State, Action> state, CompostateDUC<State, Action> parent) {
        this.seq++; // ★修正: state.marked に関わらず常にインクリメント
        
        if(parent != null) state.setTargets(parent.getTargets());
        if (state.marked) {
            // marked (Goal) 発見時は seq をさらに進めて情報を伝搬させる既存ロジックは維持
            state.addTargets(state);
            for (int lts = 0; lts < dcs.ltssSize; ++lts)
                this.knownMarked.get(lts).add(state.getStates().get(lts));
        }
        
        abstraction.eval(state, this.knownMarked, this.goals);
        state.seq = this.seq; // この状態の「新しさ」を確定
    }
    // */

    /*
    public void newState(CompostateDUC<State, Action> state, CompostateDUC<State, Action> parent) {
        if(parent != null) state.setTargets(parent.getTargets());
        if (state.marked) {
            this.seq++;
            state.addTargets(state);
            for (int lts = 0; lts < dcs.ltssSize; ++lts)
                this.knownMarked.get(lts).add(state.getStates().get(lts));
        }
        abstraction.eval(state, this.knownMarked, this.goals);
        state.seq = this.seq;
    }
    // */

    public void notifyExpandingState(CompostateDUC<State, Action> parent, HAction<State, Action> action, CompostateDUC<State, Action> state) {
        if(state.wasExpanded()){
            state.setTargets(parent.getTargets());
            if (state.marked) state.addTargets(state);
        }
    }

    public void notifyStateSetErrorOrGoal(CompostateDUC<State, Action> state) {
        state.live = false;
        state.clearRecommendations();
        if (state.isStatus(Status.GOAL)) {
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