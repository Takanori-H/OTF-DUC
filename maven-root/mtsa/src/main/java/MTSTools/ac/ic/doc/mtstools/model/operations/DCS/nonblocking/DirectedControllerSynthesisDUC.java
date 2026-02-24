package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.collections.BidirectionalMap;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedLTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.DirectedControllerSynthesis;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Statistics;

public class DirectedControllerSynthesisDUC<State, Action> extends DirectedControllerSynthesis<State, Action> {

    public static final int INF = Integer.MAX_VALUE;
    public List<LTS<State, Action>> ltss;
    public int ltssSize;
    public Set<Action> controllable;
    public Alphabet<State, Action> alphabet;

    public TransitionSet<State, Action> base;
    public TransitionSet<State, Action> allowed;

    public List<State> facilitators;
    public DUCExplorationHeuristic<State, Action> heuristic;
    public Map<List<State>, CompostateDUC<State, Action>> compostates;

    private Deque<Set<State>> transitions;
    private Set<CompostateDUC<State, Action>> visited;
    private Set<CompostateDUC<State, Action>> loop;
    private Set<CompostateDUC<State, Action>> probablyWinningStates;
    private BidirectionalMap<CompostateDUC<State, Action>, CompostateDUC<State, Action>> dag;
    private List<CompostateDUC<State, Action>> auxiliarListStates;
    private Deque<CompostateDUC<State, Action>> descendants;

    public List<Set<State>> defaultTargets;
    public CompostateDUC<State, Action> initial;

    final public Statistics statistics = new Statistics();

    private boolean debugLogEnabled = false;
    private PrintWriter logWriter;
    private static final String LOG_FILE_PATH = "duc_debug.txt";

    private List<Map<Integer, Integer>> mappingMapEnvToNewEnv;
    private Map<String, Long> newControllerConnectionMap;
    private LTS<Long, String> newController;

    // ★追加: Safety Maps (Rev. 8.0)
    private Map<Integer, List<Integer>> safetyComponentIndicesMap;
    private Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap;
    protected LTSOutput output;

    // --- コンポーネントインデックス範囲 ---
    public int idxMarking = 0;
    public int idxOC = 1;
    public int mappingStart = -1;
    public int mappingEnd = -1;
    public int oldSafeStart = -1;
    public int oldSafeEnd = -1;
    public int newSafeStart = -1;
    public int newSafeEnd = -1;
    public int transReqStart = -1;
    public int transReqEnd = -1;

    // ★追加: Component Indices
    public int synthesisStart = -1; // Monitors + Fluents
    public int synthesisEnd = -1;

    public DirectedControllerSynthesisDUC() {
    }

    @Override
    public LTS<Long, Action> synthesize(List<LTS<State, Action>> ltss, Set<Action> controllable, boolean reachability,
            HashMap<Integer, Integer> guarantees, HashMap<Integer, Integer> assumptions) {
        throw new UnsupportedOperationException("Use synthesizeDUC method.");
    }

    @Override
    public Statistics getStatistics() {
        return statistics;
    }

    public LTS<Long, Action> synthesizeDUC(
            List<LTS<State, Action>> ltss,
            Set<Action> controllable,
            int mappingStart, int mappingEnd,
            int oldSafeStart, int oldSafeEnd,
            int newSafeStart, int newSafeEnd,
            int transReqStart, int transReqEnd,
            int synthesisStart, int synthesisEnd,
            List<Map<Integer, Integer>> mappingMapEnvToNewEnv,
            Map<String, Long> newControllerConnectionMap,
            LTS<Long, String> newController,
            Map<Integer, List<Integer>> safetyComponentIndicesMap,
            Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap,
            LTSOutput output) {
        this.mappingStart = mappingStart;
        this.mappingEnd = mappingEnd;
        this.oldSafeStart = oldSafeStart;
        this.oldSafeEnd = oldSafeEnd;
        this.newSafeStart = newSafeStart;
        this.newSafeEnd = newSafeEnd;
        this.transReqStart = transReqStart;
        this.transReqEnd = transReqEnd;

        this.synthesisStart = synthesisStart;
        this.synthesisEnd = synthesisEnd;

        this.safetyComponentIndicesMap = safetyComponentIndicesMap;
        this.safetyStateLookupMap = safetyStateLookupMap;

        this.mappingMapEnvToNewEnv = mappingMapEnvToNewEnv;
        this.newControllerConnectionMap = newControllerConnectionMap;
        @SuppressWarnings("unchecked")
        LTS<Long, String> nc = (LTS<Long, String>) newController;
        this.newController = nc;
        this.output = output;

        if (debugLogEnabled) {
            try {
                logWriter = new PrintWriter(new FileWriter(LOG_FILE_PATH));
                log("=== Starting OTF-DUC Synthesis ===");
                log(String.format("Config: MapEnv[%d-%d], OldSafe[%d-%d], NewSafe[%d-%d], TransReq[%d-%d]",
                        mappingStart, mappingEnd, oldSafeStart, oldSafeEnd, newSafeStart, newSafeEnd, transReqStart,
                        transReqEnd));
            } catch (IOException e) {
                System.err.println("Failed to open debug log file: " + e.getMessage());
                e.printStackTrace();
            }
        }

        try {
            setupSynthesisDUC(ltss, controllable);
            this.heuristic = new DUCExplorationHeuristic<>(this, mappingStart, mappingEnd);
            setupInitialState();

            // isFinished() は初期状態がGOAL/ERRORになればtrue
            while (heuristic.somethingLeftToExplore() && !isFinished()) {
                statistics.startHeuristicTime();

                // --- 1. ヒューリスティック選択（Recompute + Frontier Ops）の計測 ---
                // ※詳細な内訳を測る場合は getNextState 内に埋めますが、
                //   まずは外側で「選択にかかる総時間」を測ります。
                long startHeuristic = System.nanoTime();

                Pair<CompostateDUC<State, Action>, HAction<State, Action>> next = heuristic.getNextAction();

                // 便宜上、一旦 timeRecompute に加算（後で詳細化可能）
                DUCProfiler.timeRecompute += (System.nanoTime() - startHeuristic);

                statistics.endHeuristicTime();

                if (next == null) break; // 安全策

                CompostateDUC<State, Action> state = next.getFirst();
                HAction<State, Action> action = next.getSecond();

                // ★追加ログ1：ヒューリスティックが何を提案したか
                if(debugLogEnabled) System.out.println(String.format("[Heuristic-Next] State: %s, Action: %s (%s)", state.getStates(), action, action.isControllable() ? "C" : "U"));

                // ★修正点: 探索の効率化ロジック (AND/OR Pruning)
                // 既にその状態で Controllable な勝ち筋 (hasGoalChild) が見つかっている場合、
                // 他の Controllable アクションを探索するのは時間の無駄であるためスキップする。
                if (state.hasGoalChild() && action.isControllable()) {
                    // ヒューリスティックにこのアクションは不要であることを通知（もし通知メソッドがあれば）
                    // 無い場合は、このまま continue して次の候補へ移る。
                    // デバッグログ : なぜスキップしたかを明記
                    // log("  [Pruning] Skipping redundant controllable action '" + action + "' for state " + state.getStates());
                    // 重要：ヒューリスティックに探索終了を「通知だけ」して、expandDUCは実行しない
                    // これにより、ヒューリスティックは次のアクション（Uなど）を提案できるようになる

                    // ★追加ログ2：枝刈りが発生した瞬間を記録
                    if(debugLogEnabled) System.out.println(String.format("  [Pruning-Action] SKIPPING controllable '%s' because state already has a winning path.", action));
                    heuristic.expansionDone(state, action, null);
                    continue;
                }
                // Uncontrollable アクションなら、AND条件（すべてのUでの勝利）を満たすために探索を続行
                else{
                    // ★追加ログ3：勝利パスがあるのにUを探索しようとしている場合
                    if(debugLogEnabled) System.out.println(String.format("  [Verification-Action] MUST expand environment '%s' even with winning path.", action));
                }

                // --- 2. 状態展開（Expansion）の計測 ---
                long startExp = System.nanoTime();

                DUCProfiler.totalLtsExpansions++; // 展開回数をカウント
                expandDUC(state, action);

                DUCProfiler.timeExpansion += (System.nanoTime() - startExp);
            }

            statistics.end();

            if (isGoal(initial)) {
                log("Goal Reached! Building Director...");
                return buildDirectorDUC();
            } else {
                log("Goal NOT Reached. Synthesis Failed.");
            }
            return null;

        } finally {
            if (logWriter != null) {
                log("=== Synthesis Finished ===");
                logWriter.close();
            }
            // 合成完了後
            DUCProfiler.printSummary(this.output);
        }
    }

    private void log(String message) {
        if (debugLogEnabled && logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private void setupSynthesisDUC(List<LTS<State, Action>> ltss, Set<Action> controllable) {
        this.ltss = ltss;
        this.ltssSize = ltss.size();
        this.controllable = controllable;
        statistics.clear();
        statistics.start();
        compostates = new HashMap<>();
        transitions = new ArrayDeque<>(ltss.size());
        visited = new HashSet<>();
        loop = new HashSet<>();
        probablyWinningStates = new HashSet<>();
        dag = new BidirectionalMap<>();
        auxiliarListStates = new ArrayList<>();
        descendants = new ArrayDeque<>();
        alphabet = new Alphabet<>(this.ltss, this.controllable);
        base = new TransitionSet<>(this.ltss, alphabet);
        allowed = base.clone();
        defaultTargets = buildDefaultTargets();
    }

    private List<Set<State>> buildDefaultTargets() {
        List<Set<State>> result = new ArrayList<>();
        for (int i = 0; i < ltssSize; ++i) {
            LTS<State, Action> lts = ltss.get(i);
            Set<State> markedStates = new HashSet<>();
            if (lts instanceof MarkedLTSImpl) {
                markedStates.addAll(((MarkedLTSImpl<State, Action>) lts).getMarkedStates());
            } else {
                markedStates.addAll(lts.getStates());
                markedStates.remove(-1L);
            }
            result.add(markedStates);
        }
        return result;
    }

    private void setupInitialState() {
        initial = buildInitialState();
        heuristic.setInitialState(initial);
        initial.setExpanded();
    }

    // ★ 復活させたメソッド
    private CompostateDUC<State, Action> buildInitialState() {
        List<State> states = new ArrayList<>(ltss.size());
        for (LTS<State, Action> lts : ltss)
            states.add(lts.getInitialState());
        CompostateDUC<State, Action> initial = buildCompostate(states, null);
        initial.setDepth(0); // ★追加: 初期状態の深さを0に設定
        return initial;
        // return buildCompostate(states, null);
    }

    public CompostateDUC<State, Action> buildCompostate(List<State> states, CompostateDUC<State, Action> parent) {

        // ★修正点：状態の正規化（Canonicalization）ロジック
        // 現在の更新フェーズにおいて追跡（Trace）対象外となっているコンポーネントは、
        // 将来の挙動に影響を与えないため、状態IDを固定値 -2L に統一する。
        // これにより、インターリービングの順序違いなどで生じる等価な状態がハッシュキーレベルで一致するようになる。
        long mState = getMarkingStateFromList(states);
        for (int i = 0; i < ltssSize; i++) {
            if (!isTrace(i, mState)) {
                // 安全性違反（-1L）の状態は判定に必要であるため保持し、それ以外の正常状態を正規化する。
                if (!(states.get(i) instanceof Long && (Long) states.get(i) == -1L)) {
                    states.set(i, (State) Long.valueOf(-2L));
                }
            }
        }

        CompostateDUC<State, Action> result = compostates.get(states);
        if (result == null) {
            statistics.incExpandedStates();
            result = new CompostateDUC<>(this, states);
            compostates.put(states, result);
            heuristic.newState(result, parent);

            // Marking LTSが9ならゴール状態
            if (getMarkingState(result) == 9) {
                result.setStatus(Status.GOAL);

                // ★追加: ゴール状態の距離を0に初期化する (Base Case)
                // これがないと updateDistances が機能しない
                result.setBestControllable(0, null);
            }

            if (checkErrorWithEnforce(result) || heuristic.fullyExplored(result)) {
                setError(result);
            }
        }
        return result;
    }

    /**
     * 状態リストから現在の Marking State ID を抽出するヘルパー。
     * 正規化判定のために buildCompostate 内で使用。
     */
    private long getMarkingStateFromList(List<State> states) {
        Object m = states.get(0);
        if (m instanceof Long)
            return (Long) m;
        else if (m instanceof Integer)
            return ((Integer) m).longValue();
        return -1;
    }

    private boolean isInRange(int index, int start, int end) {
        return start != -1 && index >= start && index <= end;
    }

    public boolean isActive(int ltsIndex, long markingState) {
        // 1. 常に Active なもの
        if (ltsIndex == idxMarking)
            return true;
        if (isInRange(ltsIndex, transReqStart, transReqEnd))
            return true;

        // 2. 常に Inactive なもの (Synthesis Machines)
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd))
            return false;

        // 3. フェーズ依存
        if (markingState == 0) {
            // --- Pre-hotswap (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False (ここで return false されるため)
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-hotswap (State 1-9) ---
            if (ltsIndex == idxOC)
                return false;
            if (isInRange(ltsIndex, mappingStart, mappingEnd))
                return true;

            // Old Safety: stopOldSpec 未完了 (State 1, 3, 5, 7) の間は Active
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return (markingState == 1 || markingState == 3 || markingState == 5 || markingState == 7);
            }

            // New Safety: startNewSpec 完了後 (State 5, 6, 7, 8, 9) は Active
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                return (markingState >= 5);
            }
        }
        return true;
    }

    public boolean isEnforce(int ltsIndex, long markingState) {
        // 1. 常に Enforce なもの
        if (ltsIndex == idxMarking)
            return true;
        if (isInRange(ltsIndex, transReqStart, transReqEnd))
            return true;

        // 2. 常に Enforce = FALSE なもの (Synthesis Machines)
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd))
            return false;

        // 3. フェーズ依存
        if (markingState == 0) {
            // --- Pre-hotswap (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-hotswap (State 1-9) ---
            if (ltsIndex == idxOC)
                return false;

            // Mapping (Env) は更新中常に Enforce
            if (isInRange(ltsIndex, mappingStart, mappingEnd))
                return true;

            // Old Safety: stopOldSpec 未完了 (State 1, 3, 5, 7) の間は Enforce
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return (markingState == 1 || markingState == 3 || markingState == 5 || markingState == 7);
            }

            // New Safety: startNewSpec 完了後 (State 5, 6, 7, 8, 9) は Enforce
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                return (markingState >= 5);
            }
        }
        return true;
    }

    public boolean isTrace(int ltsIndex, long markingState) {

        // 1. Old Controller (OC)
        // ★修正：hotswap_begin が発火した瞬間（State 1以上）にトレースを OFF にする。
        if (ltsIndex == idxOC) {
            return (markingState == 0);
        }

        // 2. Old Safety
        // stopOldSpec発火後 (State 2, 4, 6, 8, 9) は Trace しない (監視終了)
        if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
            // 2: stopOld
            // 5: stopOld, reconfig
            // 6: stopOld, startNew
            // 8: All done
            // 9: Goal
            if (markingState == 2 || markingState == 4 || markingState == 6 || markingState >= 8) {
                return false;
            }
        }

        // 3. New Safety (Original)
        // startNewSpec発火前 (State 0, 1, 2, 3, 4) は Trace しない (監視開始前)
        // startNewSpec完了後 (State 5, 6, 7, 8, 9) は Trace する
        if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
            // 4: startNew
            // 6: stopOld, startNew
            // 7: reconfig, startNew
            // 8, 9: All done
            boolean isStarted = (markingState >= 5);
            if (!isStarted)
                return false;
        }

        // 4. Synthesis Machines (Monitor/Fluent), Mapping, TransReq, Marking
        // これらは常に Trace = TRUE

        return true;
    }

    private long getMarkingState(CompostateDUC<State, Action> compostate) {
        Object m = compostate.getStates().get(0);
        if (m instanceof Long)
            return (Long) m;
        else if (m instanceof Integer)
            return ((Integer) m).longValue();
        return -1;
    }

    private boolean checkErrorWithEnforce(CompostateDUC<State, Action> compostate) {
        long markingState = getMarkingState(compostate);
        List<State> currentStates = compostate.getStates();

        for (int i = 0; i < ltssSize; i++) {
            State s = currentStates.get(i);
            if (s instanceof Long && (Long) s == -1L) {
                if (isEnforce(i, markingState)) {
                    log("[Safety Violation] Component " + i + " reached Error state -1 at Marking " + markingState);
                    return true;
                }
            }
        }
        return false;
    }

    void expandDUC(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        statistics.incExpandedTransitions();

        // 1. ブロック条件のチェック
        boolean blocked = false;
        if (action.toString().equals(UpdateConstants.HOTSWAP_END) && !checkHotswapEndCondition(state)) {
            blocked = true;
        }

        // 2. 次の状態（生のリスト）の取得
        List<State> nextStates = null;
        if (!blocked) {
            // --- 計測：1. Sync Calculation (同期製品の計算) ---
            long s1 = System.nanoTime();

            nextStates = getChildStatesDUC(state, action);

            DUCProfiler.timeSync += (System.nanoTime() - s1);
            // ----------------------------------------------
        }

        // expandDUC メソッド内の getChildStatesDUC 呼び出し直後
        if (nextStates == null) {
            if(debugLogEnabled) System.out.println(String.format("  [Critical-Deadlock] Action '%s' failed to synchronize at %s", action, state.getStates()));
            // どのコンポーネントがブロックしたか詳しく出す
            debugCheckActionAvailability(state, action.toString());
        }

        // 3. 正規化済みの Compostate オブジェクトを先に生成 (ログ表示のため)
        CompostateDUC<State, Action> child = null;
        if (!blocked && nextStates != null) {
            // --- 計測：2. State Lookup (正規化とMap照合) ---
            long s2 = System.nanoTime();

            // ここで内部的に正規化（-2Lへの置き換え）が行われる
            child = buildCompostate(nextStates, state);

            DUCProfiler.timeLookup += (System.nanoTime() - s2);
            // ----------------------------------------------
        }

        // expandDUC メソッド内の buildCompostate 呼び出し直後
        if (child != null && isError(child)) {
            if(debugLogEnabled){
                System.out.println(String.format("  [Safety-Violation] Action '%s' leads to ERROR state from %s", action, state.getStates()));
                System.out.println("    -> Error Vector: " + child.getStates());
            }
        }

        // 4. デバッグログ出力
        if (debugLogEnabled) {
            log("--------------------------------------------------------------------------------");
            log("[Expand Step]");
            log("  Current State: " + state.getStates());

            // ★追加: Compostateのフラグ情報を表示
            log(String.format("  Flags: Status=%s, Live=%s, InOpen=%s, Controlled=%s, Depth=%d, hasGoalChild=%b",
                    state.getStatus(), state.isLive(), state.inOpen, state.isControlled(), state.getDepth(),
                    state.hasGoalChild()));

            long marking = getMarkingState(state);
            log("  Marking State: " + marking);
            log("  Components Status:");

            for (int i = 0; i < ltssSize; i++) {
                boolean a = isActive(i, marking);
                boolean t = isTrace(i, marking);
                boolean e = isEnforce(i, marking);
                String stateStr = String.valueOf(state.getStates().get(i));

                String compName = "LTS " + i;
                if (i == idxMarking)
                    compName += " (Marking)";
                else if (i == idxOC)
                    compName += " (OC)";
                else if (isInRange(i, mappingStart, mappingEnd))
                    compName += " (Env)";
                else if (isInRange(i, oldSafeStart, oldSafeEnd))
                    compName += " (OldSafe)";
                else if (isInRange(i, newSafeStart, newSafeEnd))
                    compName += " (NewSafe)";
                else if (isInRange(i, transReqStart, transReqEnd))
                    compName += " (TransReq)";
                else
                    compName += " (Unknown)";

                log(String.format("    %-20s : State=%-5s [Active=%-5s Trace=%-5s Enforce=%-5s]",
                        compName, stateStr, a, t, e));
            }
            log("");

            // ★変更: Available Transitions の表示フォーマットを変更 (C/U付与)
            StringBuilder transSb = new StringBuilder();
            transSb.append("[");
            Iterator<HAction<State, Action>> it = state.getTransitions().iterator();
            while (it.hasNext()) {
                HAction<State, Action> t = it.next();
                transSb.append(t.toString());
                transSb.append(t.isControllable() ? "(C)" : "(U)");
                if (it.hasNext())
                    transSb.append(", ");
            }
            transSb.append("]");

            log("  Available Transitions:     " + transSb.toString());

            log("  Selected Action:           " + action);

            if (blocked) {
                log("  Result:                    BLOCKED (Intermediate State Check Failed)");
            } else if (nextStates == null) {
                log("  Result:                    INVALID (Synchronization Failed)");
            } else {
                log("  Next Compostate States:    " + nextStates);
            }

            // ★追加: アクション候補とスコアの表示
            if (state.recommendations != null) {
                log("  Candidates & Scores:");
                for (CompostateDUC<State, Action>.RecommendationDUC rec : state.recommendations) {
                    // rec.toString() は "action: score" のような形式を想定
                    // HEstimate.toString() の内容に依存しますが、数値は見えます
                    log("    - " + rec.getAction() + " : " + rec.getEstimate());
                }
            }

            log("  Selected Action:           " + action);

            log("--------------------------------------------------------------------------------");
        }

        // 5. 探索失敗時の早期リターン
        if (blocked || nextStates == null) {
            // 前回の追加: デッドロック/ブロック検出ログをここでも出力（debugLogEnabledがfalseの場合も考慮）
            if (blocked)
                log("[BLOCKED] Transition blocked by HotswapEnd condition: " + action);
            else
                log("[DEADLOCK/INVALID] No valid next states for action: " + action + " at state: "
                        + state.getStates());

            heuristic.expansionDone(state, action, null);
            return;
        }

        // 6. グラフの構築と探索の継続
        state.addChild(action, child);
        child.addParent(action, state);

        // --- 計測：3. NewState Init (評価と通知) ---
        long s3 = System.nanoTime();

        heuristic.notifyExpandingState(state, action, child);

        explore(state, action, child);

        child.setExpanded();

        // 子状態がGOALならこれ以上探索しない
        if (isGoal(child)) {
            heuristic.expansionDone(state, action, null);
        } else {
            heuristic.expansionDone(state, action, child);
        }

        DUCProfiler.timeNewStateInit += (System.nanoTime() - s3);
        // -------------------------------------------

        // expandDUC メソッドの最後、heuristic.expansionDone の直後に追加
        if (state.hasGoalChild()) {
            // この状態からまだ探索可能な（未展開の）アクションがあるかチェック
            for (HAction<State, Action> t : state.getTransitions()) {
                Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(t);
                if (children == null || children.isEmpty()) {
                    if(debugLogEnabled) System.out.println(String.format("  [Heuristic-Check] State %s still has UNEXPLORED transition: %s (%s)", state.getStates(), t, t.isControllable() ? "C" : "U"));
                }
            }
        }
    }

    /**
     * hotswap_end の実行可否を判定するガード条件
     * 1. 環境状態が新環境へ翻訳可能であること
     * 2. 翻訳後の環境と現在の安全性状態の組み合わせが、新コントローラ(NC)に存在すること
     */
    private boolean checkHotswapEndCondition(CompostateDUC<State, Action> state) {
        // シグネチャを仮生成して NC マップとの照合を行う
        String signature = generateNCSignature(state);
        
        // 翻訳に失敗した（環境状態がマップにない）場合は null が返る想定
        if (signature == null) {
            if (debugLogEnabled) log("  [HotswapEnd Guard] BLOCKED: Environment state translation failed.");
            return false;
        }

        // NC の状態空間（newControllerConnectionMap）にキーが存在するかチェック
        boolean isSafeInNC = newControllerConnectionMap.containsKey(signature);

        if (!isSafeInNC && debugLogEnabled) {
            // ユーザー様の不整合発見を検証するためのログ
            log("  [HotswapEnd Guard] BLOCKED: Signature '" + signature + "' is NOT found in New Controller's safe states.");
        }

        return isSafeInNC;
    }

    private List<State> getChildStatesDUC(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        List<State> parentStates = state.getStates();
        List<State> childStates = new ArrayList<>(ltssSize);
        long markingState = getMarkingState(state);

        String actionName = action.toString();
        boolean isOldAction = actionName.endsWith("_old");
        String strippedActionName = isOldAction ? actionName.replace("_old", "") : actionName;

        // 1. まず全コンポーネントの標準的な遷移を計算する
        // (これにより、Action Fluent は startNewSpec に反応して False/State0 に遷移する)
        for (int i = 0; i < ltssSize; ++i) {
            if (!isTrace(i, markingState)) {
                childStates.add(parentStates.get(i));
                continue;
            }

            LTS<State, Action> lts = ltss.get(i);
            State curr = parentStates.get(i);
            Action rawAction = action.getAction();

            if (markingState == 0 && isOldAction && i != 1) {
                boolean found = false;
                for (Pair<Action, State> trans : lts.getTransitions(curr)) {
                    if (trans.getFirst().toString().equals(strippedActionName)) {
                        childStates.add(trans.getSecond());
                        found = true;
                        break;
                    }
                }
                if (!found)
                    childStates.add(curr);
            }
            // Standard Case
            else {
                Set<State> image = lts.getTransitions(curr).getImage(rawAction);
                if (image == null || image.isEmpty()) {
                    if (lts.getActions().contains(rawAction))
                        return null;
                    childStates.add(curr);
                } else {
                    childStates.add(image.iterator().next());
                }
            }
        }

        // 2. startNewSpec の場合、計算された Child State を元に Safety を同期(上書き)する
        if (actionName.equals(UpdateConstants.START_NEW_SPEC)) {
            for (Map.Entry<Integer, List<Integer>> entry : safetyComponentIndicesMap.entrySet()) {
                int safetyIdx = entry.getKey();
                List<Integer> compIndices = entry.getValue();

                // 遷移後(Child)の状態を使ってキーを作成
                List<Integer> lookupKey = new ArrayList<>();
                for (int compIdx : compIndices) {
                    Object sObj = childStates.get(compIdx); // ★修正: parentStatesではなくchildStatesを参照
                    Integer sInt = (sObj instanceof Long) ? ((Long) sObj).intValue() : (Integer) sObj;
                    lookupKey.add(sInt);
                }

                Map<List<Integer>, Integer> lookupTable = safetyStateLookupMap.get(safetyIdx);
                if (lookupTable != null && lookupTable.containsKey(lookupKey)) {
                    // Hit: マップされた状態へ強制変更
                    Integer targetStateInt = lookupTable.get(lookupKey);
                    childStates.set(safetyIdx, (State) Long.valueOf(targetStateInt));

                    // ★追加: State Jumpログ
                    log("  [StateJump] Safety[" + safetyIdx + "] forced to State " + targetStateInt
                            + " based on Monitor/Fluents " + lookupKey);
                } else {
                    // Miss: Monitorの状態(遷移後)をそのまま採用
                    int monitorIdx = compIndices.get(0);
                    Object monitorStateObj = childStates.get(monitorIdx);
                    childStates.set(safetyIdx, (State) monitorStateObj);
                }
            }
        }
        return childStates;
    }

    private void explore(CompostateDUC<State, Action> parent, HAction<State, Action> action,
            CompostateDUC<State, Action> child) {
        if (isError(child) || child.heuristicStronglySuggestsIsError) {
            if (!isError(child))
                setError(child);

            // --- 計測：伝播 (Error) ---
            long sProp = System.nanoTime();

            propagateError(singleton(child), singleton(parent));

            DUCProfiler.timePropagation += (System.nanoTime() - sProp);
        } else if (isGoal(child)) {
            parent.setHasGoalChild(action);

            // --- 計測：伝播 (Goal) ---
            long sProp = System.nanoTime();

            propagateGoal(singleton(child), singleton(parent));

            DUCProfiler.timePropagation += (System.nanoTime() - sProp);
        }
        else {
            // --- 計測：ループ検知 ---
            long sLoop = System.nanoTime();
            boolean isLoop = closingALoop(parent, child);
            if (isLoop) {
                gatherLoopStates(child);
            }
            DUCProfiler.timeLoopCheck += (System.nanoTime() - sLoop);

            if (isLoop) {
                // ループ処理ロジック
                boolean isPreUpdateLoop = true;
                for (CompostateDUC<State, Action> s : loop) {
                    if (getMarkingState(s) != 0) {
                        isPreUpdateLoop = false;
                        break;
                    }
                }

                if (isPreUpdateLoop) {
                    // --- 計測：Phase 2 強制起動 (伝播扱い) ---
                    long sProp = System.nanoTime();
                    propagateGoal(new HashSet<>(), singleton(parent));
                    DUCProfiler.timePropagation += (System.nanoTime() - sProp);
                } else {
                    // --- 計測：不動点計算 (Heavy!) ---
                    long sFP = System.nanoTime();
                    if (probablyWinningStates.size() > 0)
                        findNewGoals();
                    else
                        findNewErrors();
                    DUCProfiler.timeFixedPoint += (System.nanoTime() - sFP);
                }
            } else {
                heuristic.notifyExpansionDidntFindAnything(parent, action, child);
            }
        }
        dag.clear();
    }

    private void propagateGoal(Set<CompostateDUC<State, Action>> goals, Set<CompostateDUC<State, Action>> parents) {
        DUCProfiler.countPropGoalCalls++; // 呼び出し回数をカウント
        long startTotal = System.nanoTime();
        long sP1 = System.nanoTime();

        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>(parents);
        Set<CompostateDUC<State, Action>> winners = new HashSet<>();

        // --- Phase 1: 通常の勝利伝播 (既知のGoalからの波及) ---
        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.poll();
            if (isGoal(current))
                continue;

            // 1. Uncontrollable (AND) 条件のチェック
            boolean allUncontrollableResolved = true;
            boolean hasUncontrollable = false;
            HAction<State, Action> blockingU = null;

            for (HAction<State, Action> action : current.getTransitions()) {
                if (!action.isControllable()) {
                    hasUncontrollable = true;
                    Set<CompostateDUC<State, Action>> children = current.getExploredChildren().getImage(action);
                    if (children == null || children.isEmpty()) {
                        allUncontrollableResolved = false;
                        blockingU = action;
                        break;
                    }
                    for (CompostateDUC<State, Action> child : children) {
                        if (!isGoal(child)) {
                            allUncontrollableResolved = false;
                            blockingU = action;
                            break;
                        }
                    }
                }
                if (!allUncontrollableResolved)
                    break;
            }

            // 2. Controllable (OR) 条件のチェック
            boolean hasWinningC = false;
            HAction<State, Action> winningC = null;
            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> trans : current.getExploredChildren()) {
                if (trans.getFirst().isControllable() && isGoal(trans.getSecond())) {
                    hasWinningC = true;
                    winningC = trans.getFirst();
                    break;
                }
            }

            // 3. 勝利確定判定
            if (allUncontrollableResolved) {
                if (hasWinningC) {
                    applyGoalStatus(current, winningC, winners, queue);
                } else if (hasUncontrollable) {
                    // 強制勝利 (全UがGoalへ行く)
                    HAction<State, Action> anyU = null;
                    for (HAction<State, Action> a : current.getTransitions()) {
                        if (!a.isControllable()) {
                            anyU = a;
                            break;
                        }
                    }
                    applyGoalStatus(current, anyU, winners, queue);
                }
            } else if (hasWinningC) {
                // デバッグログ: 環境動作(U)が解決していないため却下
                if(debugLogEnabled) System.out.println(String.format("  [Rejected] State %s: Winning C (%s) exists, but BLOCKED by U (%s)",
                        current.getStates(), winningC, blockingU));
            }
        }

        DUCProfiler.timeGoalPhase1 += (System.nanoTime() - sP1);

        // --- Phase 2: 不動点計算 (環境ループ/循環依存の救済) ---
        boolean changed;
        do {
            changed = false;

            if(debugLogEnabled) System.out.println("=== Phase 2: Fixed-Point Iteration Start ===");

            long sP2Init = System.nanoTime();

            // 現在の NONE 状態（探索済みかつ生存）を候補セットとして抽出
            Set<CompostateDUC<State, Action>> candidates = new HashSet<>();
            // 各候補が「どの手でループを抜けられるか」を保持するマップ
            Map<CompostateDUC<State, Action>, HAction<State, Action>> exitActions = new HashMap<>();

            for (CompostateDUC<State, Action> s : compostates.values()) {
                if (s.isStatus(Status.NONE) && s.isLive()) {
                    candidates.add(s);
                }
            }

            DUCProfiler.totalCandidatesProcessed += candidates.size();

            DUCProfiler.timeGoalPhase2Init += (System.nanoTime() - sP2Init);

            // ログ追加：候補となった状態の数と一覧（数が多い場合は数だけでも可）
            if(debugLogEnabled) System.out.println("  [Initial Candidates] Size: " + candidates.size());

            if (candidates.isEmpty())
                break;

            // 不動点計算 (Fixed-point iteration)
            long sP2Loop = System.nanoTime();
            boolean innerChanged;
            do {
                innerChanged = false;
                Iterator<CompostateDUC<State, Action>> it = candidates.iterator();
                while (it.hasNext()) {
                    CompostateDUC<State, Action> s = it.next();

                    // 条件A: すでに確定した Status.GOAL へ脱出できる Controllable な手があるか
                    HAction<State, Action> winningCForS = null;
                    for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> trans : s.getExploredChildren()) {
                        if (trans.getFirst().isControllable() && isGoal(trans.getSecond())) {
                            winningCForS = trans.getFirst();
                            break;
                        }
                    }

                    // 条件B: すべての Uncontrollable な遷移先が「確定Goal」または「この候補セット内」か
                    boolean uIsSafe = true;

                    HAction<State, Action> causeU = null;

                    // HAction<State, Action> fatalU = null; // 原因となった環境動作を記録
                    // CompostateDUC<State, Action> fatalChild = null; // 原因となった遷移先を記録

                    for (HAction<State, Action> action : s.getTransitions()) {
                        if (!action.isControllable()) {
                            Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                            if (children == null || children.isEmpty()) {
                                uIsSafe = false;

                                causeU = action;
                                // fatalU = action;
                                if(debugLogEnabled) System.out.println(String.format("  [FixedPoint-FAIL] State %s has unexpanded U: %s", s.getStates(), action));

                                break;
                            }
                            for (CompostateDUC<State, Action> child : children) {
                                if (!isGoal(child) && !candidates.contains(child)) {
                                    uIsSafe = false;

                                    // fatalU = action;
                                    // fatalChild = child;
                                    causeU = action;
                                    if(debugLogEnabled) System.out.println(String.format("  [FixedPoint-FAIL] State %s leads to unsafe state via %s: %s (Status=%s, Live=%b)", 
                                                        s.getStates(), action, child.getStates(), child.getStatus(), child.isLive()));

                                    break;
                                }
                            }
                        }
                        if (!uIsSafe)
                            break;
                    }

                    // 出口がない、または環境動作によって候補外（負け筋）に追い出される可能性があるなら脱落
                    if (winningCForS == null || !uIsSafe) {

                        // ログ追加：なぜ脱落したか
                        // String reason = (winningCForS == null) ? "No Winning C-Exit" : 
                        //                 (fatalChild == null) ? "Unexpanded U-action: " + fatalU : 
                        //                 "U-action '" + fatalU + "' leads to non-candidate: " + fatalChild.getStates();
                        // System.out.println(String.format("  [FixedPoint-Remove] State %s removed. Reason: %s", s.getStates(), reason));

                        it.remove();
                        innerChanged = true;
                    } else {
                        exitActions.put(s, winningCForS);
                    }
                }
            } while (innerChanged);

            if(debugLogEnabled) System.out.println("  [FixedPoint-Result] Remaining winners in this iteration: " + candidates.size());

            // 生き残った候補は「ループしても詰まない」ことが証明されたため、一括で勝利とする
            for (CompostateDUC<State, Action> winner : candidates) {
                if(debugLogEnabled) System.out.println("  [Fixed-Point] Loop-Winner detected: " + winner.getStates());
                // 保持しておいた出口アクションを使って勝利を確定させる
                applyGoalStatus(winner, exitActions.get(winner), winners, queue);
                changed = true;
            }

            // Phase 2 で新たに Goal になった状態がある場合、親たちに Phase 1 の論理を再適用する
            while (!queue.isEmpty()) {
                CompostateDUC<State, Action> current = queue.poll();
                if (isGoal(current))
                    continue;

                // Phase 1 と同じチェックをここでも実行（親への波及）
                boolean allUResolved = true;
                for (HAction<State, Action> a : current.getTransitions()) {
                    if (!a.isControllable()) {
                        Set<CompostateDUC<State, Action>> children = current.getExploredChildren().getImage(a);
                        if (children == null || children.isEmpty()) {
                            allUResolved = false;
                            break;
                        }
                        for (CompostateDUC<State, Action> c : children) {
                            if (!isGoal(c)) {
                                allUResolved = false;
                                break;
                            }
                        }
                    }
                    if (!allUResolved)
                        break;
                }

                HAction<State, Action> winningC = null;
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> trans : current.getExploredChildren()) {
                    if (trans.getFirst().isControllable() && isGoal(trans.getSecond())) {
                        winningC = trans.getFirst();
                        break;
                    }
                }

                if (allUResolved && winningC != null) {
                    applyGoalStatus(current, winningC, winners, queue);
                }
            }
            DUCProfiler.timeGoalPhase2Loop += (System.nanoTime() - sP2Loop);

        } while (changed);

        // 距離情報の更新
        if (!winners.isEmpty()) {
            updateDistances(goals, winners, winners.size());
        }
        DUCProfiler.timePropagateGoalTotal += (System.nanoTime() - startTotal);
    }

    /**
     * ヘルパーメソッド: 状態の勝利確定、統計通知、および親への伝播管理を一括で行う。
     */
    private void applyGoalStatus(CompostateDUC<State, Action> node, HAction<State, Action> action,
            Set<CompostateDUC<State, Action>> winners, Deque<CompostateDUC<State, Action>> queue) {
        if (isGoal(node))
            return;

        if(debugLogEnabled) System.out.println("  [Debug-Success] State " + node.getStates() + " is now marked as GOAL!");
        node.setStatus(Status.GOAL);
        // setterを使用してアクションを登録（privateフィールドへの直接アクセスを回避）
        node.setHasGoalChild(action);
        winners.add(node);
        heuristic.notifyStateSetErrorOrGoal(node);

        // 親をキューに追加し、勝利が伝播するようにする
        for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> parentRel : node.getParents()) {
            CompostateDUC<State, Action> parentNode = parentRel.getSecond();
            // 親に対しても「子の一つがGoalになった」ことを記録
            parentNode.setHasGoalChild(parentRel.getFirst());
            if (!isGoal(parentNode)) {
                queue.add(parentNode);
            }
        }
    }

    private <T> Set<T> singleton(T element) {
        Set<T> set = new HashSet<>();
        set.add(element);
        return set;
    }

    private boolean closingALoop(CompostateDUC<State, Action> parent, CompostateDUC<State, Action> child) {
        if (child.wasExpanded()) {
            buildAncestorsDAG(child, parent);
            return !dag.getK(child).isEmpty();
        }
        return false;
    }

    private void buildAncestorsDAG(CompostateDUC<State, Action> child, CompostateDUC<State, Action> parent) {
        auxiliarListStates.clear();
        visited.clear();
        auxiliarListStates.add(parent);
        visited.add(parent);
        for (int i = 0; i < auxiliarListStates.size(); ++i) {
            CompostateDUC<State, Action> state = auxiliarListStates.get(i);
            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> predecesor : state.getParents()) {
                CompostateDUC<State, Action> predState = predecesor.getSecond();
                // 既に判定済みのノードはDAG探索対象外
                if (isGoal(predState) || isError(predState))
                    continue;

                dag.put(state, predState);
                if (visited.add(predState))
                    auxiliarListStates.add(predState);
            }
        }
        visited.clear();
        auxiliarListStates.clear();
    }

    /**
     * エラー（到達不能または安全性違反）の逆伝播処理
     * Uncontrollableな遷移先がエラーなら親もエラー、
     * Controllableな遷移先が全てエラーなら親もエラー、という論理で伝播します。
     */
    /**
     * キュー（Worklist）方式によるエラーの逆伝播処理。
     * 先祖の全スキャンを避け、ステータスが変化したノードの親のみを再評価します。
     */
    private void propagateError(Set<CompostateDUC<State, Action>> newErrors, Set<CompostateDUC<State, Action>> seedParents) {
        long start = System.nanoTime();
        statistics.incPropagateErrorsCalls();

        // 1. 処理対象を管理するキュー (重複を許さない集合も併用)
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();
        if (newErrors != null) queue.addAll(newErrors);
        if (seedParents != null) {
            for (CompostateDUC<State, Action> p : seedParents) {
                if (!queue.contains(p)) queue.add(p);
            }
        }

        // 2. Worklist 処理
        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.poll();

            // すでにエラー確定済みの場合は、その親たちをチェックリストに入れる
            if (isError(current)) {
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> pRel : current.getParents()) {
                    CompostateDUC<State, Action> parent = pRel.getSecond();
                    if (!isError(parent) && !isGoal(parent)) {
                        if (!queue.contains(parent)) queue.add(parent);
                    }
                }
                continue;
            }

            // 3. エラー判定の再評価 (AND/OR グラフの標準論理)
            if (checkIfShouldBecomeError(current)) {
                if (debugLogEnabled) {
                    output.outln("  [Propagate-Error] State " + current.getStates() + " is now ERROR.");
                }
            
                setError(current); // 内部で Status.ERROR をセット
            
                // 自身がエラーになったので、その親たちをキューへ追加
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> pRel : current.getParents()) {
                    CompostateDUC<State, Action> parent = pRel.getSecond();
                    if (!isError(parent) && !isGoal(parent)) {
                        if (!queue.contains(parent)) queue.add(parent);
                    }
                }
            }
        }
        DUCProfiler.timePropagateErrorTotal += (System.nanoTime() - start);
    }

    /**
     * 補助メソッド: 指定された状態がエラーになるべきか判定する
     */
    private boolean checkIfShouldBecomeError(CompostateDUC<State, Action> state) {
        // A. 環境によって強制的にエラー（安全性違反やデッドロック）へ連れて行かれるか
        if (forcedToError(state)) return true;

        // B. 勝ち筋（GOALへのパス）が残っているか
        // Controllableな遷移のうち、少なくとも1つが「エラーでない（＝勝てる可能性がある）」ならまだ生き残れる
        boolean hasPotentialWinningMove = false;
    
        // 現在展開済みの遷移をチェック
        for (HAction<State, Action> action : state.getTransitions()) {
            if (action.isControllable()) {
                Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
            
                // まだ展開していないアクションがあるなら、それは勝てる可能性があるとみなす
                if (children == null || children.isEmpty()) {
                    hasPotentialWinningMove = true;
                    break;
                }
            
                // 展開済みの子の中に、エラーでないものが1つでもあればOK
                for (CompostateDUC<State, Action> child : children) {
                    if (!isError(child)) {
                        hasPotentialWinningMove = true;
                        break;
                    }
                }
            }
            if (hasPotentialWinningMove) break;
        }

        // Controllable な手が一つも残っていない場合はエラー
        return !hasPotentialWinningMove;
    }

    /**
     * 環境(Uncontrollable)によってエラーに落とされるか、
     * あるいはコントローラ(Controllable)が回避不能かを判定する
     */
    private boolean forcedToError(CompostateDUC<State, Action> state) {
        boolean existsActionLeadingToNoneOrGoal = false;
        boolean fullyExplored = heuristic.fullyExplored(state);

        // 1. Uncontrollableな遷移先にエラーがあるか？ -> あれば即アウト(Safety)
        for (CompostateDUC<State, Action> child : state.getChildrenExploredThroughUncontrollable()) {
            if (isError(child)) {
                return true;
            } else if (child != state) {
                existsActionLeadingToNoneOrGoal = true;
            }
        }

        if (existsActionLeadingToNoneOrGoal) {
            // Uncontrollableで安全なパスが(現時点では)あるなら、まだエラー確定ではない
            heuristic.notifyStateIsNone(state);
            return false;
        }

        // 2. Controllableな遷移で回避できるか？
        for (CompostateDUC<State, Action> child : state.getChildrenExploredThroughControllable()) {
            if (!isError(child)) {
                existsActionLeadingToNoneOrGoal = true;
            }
        }

        if (existsActionLeadingToNoneOrGoal) {
            heuristic.notifyStateIsNone(state);
            return false;
        }

        // 全ての道がエラー、かつ完全に展開済みならエラー確定
        return fullyExplored;
    }

    private void gatherLoopStates(CompostateDUC<State, Action> child) {
        probablyWinningStates.clear(); // ここではError候補として使う
        loop = new HashSet<>();
        auxiliarListStates.clear();
        auxiliarListStates.add(child);

        for (int i = 0; i < auxiliarListStates.size(); ++i) {
            CompostateDUC<State, Action> state = auxiliarListStates.get(i);
            for (CompostateDUC<State, Action> successor : dag.getK(state)) {
                if (loop.add(successor)) {
                    auxiliarListStates.add(successor);
                }
            }
        }
        auxiliarListStates.clear();

        // ループに含まれる状態を全て「勝利候補(＝この文脈では処理対象)」に入れる
        probablyWinningStates.addAll(loop);
    }

    /**
     * OTF-DUCはReachability問題なので、ループはLiveness違反（ゴール到達不可）とみなす。
     * したがって、ループが見つかったら `findNewGoals` ではなく `findNewErrors` で処理すべきだが、
     * 既存ロジックとの整合性のため、ループ状態を全てErrorにする処理を実装する。
     */
    private void findNewGoals() {
        // Reachability to Marking 9 において、Marking 9を含まないループはゴールになり得ない。
        // ここに到達するということはループがあるが、それはLivelock（更新が進まない）を意味する。
        // したがって、これをエラーとして処理する。
        findNewErrors();
    }

    private void findNewErrors() {
        statistics.incFindNewErrorsCalls();

        // ループ内の全状態をエラーにする
        for (CompostateDUC<State, Action> state : loop) {
            setError(state);
        }

        // 初期状態がエラーでなければ伝播させる
        if (!isError(initial)) {
            propagateError(loop, null);
        }
    }

    /**
     * ゴールまでの距離を更新し、Director構築用の最善手(BestChild)を設定する。
     * propagateGoalから呼ばれる想定。
     */
    private void updateDistances(Set<CompostateDUC<State, Action>> seeds,
        Set<CompostateDUC<State, Action>> goalsToUpdate, int amountToUpdate) {
    
        long startTime = System.nanoTime();
    
        // 1. ArrayDequeを使用して、ループごとのHashSet生成（new HashSet）を排除
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();

        // 2. 初期シードのセットアップ
        if (seeds.isEmpty()) {
            // すでにGOAL判定されているノードの中から、終端GOAL（Marking 9等）への直接の親を探す
            for (CompostateDUC<State, Action> s : goalsToUpdate) {
                if (!s.hasGoalChild()) continue;
            
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> childPair : s.getExploredChildren()) {
                    CompostateDUC<State, Action> child = childPair.getSecond();
                    if (isGoal(child)) {
                        int childDist = child.getBestControllable().getFirst();
                        int newDist = (childDist == -1) ? 0 : childDist + 1;

                        // 最短距離を更新できた場合のみシードに追加
                        int currentDist = s.getBestControllable().getFirst();
                        if (currentDist == -1 || newDist < currentDist) {
                            s.setBestControllable(newDist, childPair.getFirst().isControllable() ? child : null);
                            if (!queue.contains(s)) queue.add(s);
                        }
                        break; 
                    }
                }
            }
        } else {
            queue.addAll(seeds);
        }

        // 3. Queueベースの最短経路伝播 (Dijkstra-style BFS)
        // 「距離が縮まった場合のみ親をキューに入れる」ことで、探索範囲を最小化
        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> s = queue.poll();
            int sDist = s.getBestControllable().getFirst();
            if (sDist == -1) continue;

            int newDistForParent = sDist + 1;

            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> pRel : s.getParents()) {
                CompostateDUC<State, Action> parent = pRel.getSecond();
            
                // 距離更新の対象は、今回勝者となった（または既に勝者である）GOAL状態のみ
                if (isGoal(parent) && goalsToUpdate.contains(parent)) {
                    int parentCurrentDist = parent.getBestControllable().getFirst();

                    // 最短距離が更新される場合のみ処理
                    if (parentCurrentDist == -1 || newDistForParent < parentCurrentDist) {
                        parent.setBestControllable(newDistForParent, pRel.getFirst().isControllable() ? s : null);
                    
                        // 距離が変わったので、その親たちも再計算の必要があるためQueueへ
                        if (!queue.contains(parent)) {
                            queue.add(parent);
                        }

                        if (debugLogEnabled) {
                            String type = pRel.getFirst().isControllable() ? "C" : "U";
                            log(String.format("  [DistUpdate] Propagated: %s -> Dist=%d via %s (%s)", 
                                parent.getStates(), newDistForParent, pRel.getFirst(), type));
                        }
                    }
                }
            }
        }

        // 以前の13msの正体をDUCProfilerに記録
        DUCProfiler.timeGoalDistUpdate += (System.nanoTime() - startTime);
    }

    private LTS<Long, Action> buildDirectorDUC() {
        // 1. 結果を格納する LTS の初期化
        // 状態 ID 0 を初期状態として設定（後に更新コントローラの初期 ID で上書き）
        LTSImpl<Long, Action> result = new LTSImpl<>(0L);

        // ★修正: 探索アルゴリズム全体の alphabet.getActions() を一括登録するのをやめる。
        // これにより、遷移図に登場しない "_old" アクションがアルファベット拡張として出力されるのを防ぐ。
        // result.addActions(alphabet.getActions());

        // 1. 全アクションの中から "_old" を含まないものだけを抽出して登録
        // これにより、遷移図に現れる可能性のある全アクションを網羅しつつ、不要な表示を消す
        for (Action a : alphabet.getActions()) {
            if (!a.toString().endsWith("_old")) {
                result.addAction(a);
            }
        }

        @SuppressWarnings("unchecked")
        Set<Action> ncActions = (Set<Action>) newController.getActions();
        result.addActions(ncActions);

        // 状態 ID 管理：NC の状態 ID と衝突しないようにカウンターを管理
        long nextId = 0;
        Map<CompostateDUC<State, Action>, Long> ids = new HashMap<>();

        // ---------------------------------------------------------
        // ステップ 1: 新コントローラ (NC) の完全移設
        // ---------------------------------------------------------
        log("[Stitching] Pre-populating result LTS with New Controller states and transitions.");
        for (Long ncState : newController.getStates()) {
            result.addState(ncState);
            if (ncState >= nextId) nextId = ncState + 1;
        }
        for (Long ncState : newController.getStates()) {
            for (Pair<String, Long> trans : newController.getTransitions(ncState)) {
                @SuppressWarnings("unchecked")
                Action action = (Action) trans.getFirst();
                result.addTransition(ncState, action, trans.getSecond());
            }
        }

        // ---------------------------------------------------------
        // ステップ 2: 更新コントローラのグラフ構築と動的リンク
        // ---------------------------------------------------------
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();
        ids.put(initial, nextId++);
        result.addState(ids.get(initial));
        // 初期状態を更新コントローラの開始点に設定
        result.setInitialState(ids.get(initial));
        queue.add(initial);

        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.remove();
            Long currentId = ids.get(current);

            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition : current.getExploredChildren()) {
                HAction<State, Action> hAction = transition.getFirst();
                CompostateDUC<State, Action> child = transition.getSecond();

                // 採用判定 (Anytime Hotswap 含む)
                boolean toAdd = !hAction.isControllable();
                if (hAction.isControllable()) {
                    if (hAction.toString().equals(UpdateConstants.HOTSWAP_BEGIN) && isGoal(child)) {
                        toAdd = true;
                    } else if (current.actionToGoal != null && current.actionToGoal.equals(hAction)) {
                        toAdd = true;
                    } else {
                        Pair<Integer, CompostateDUC<State, Action>> best = current.getBestControllable();
                        if (best != null && best.getSecond() == child) {
                            toAdd = true;
                        }
                    }
                }

                if (toAdd) {
                    // hotswap_end の場合は NC への接続を試みる
                    if (hAction.toString().equals(UpdateConstants.HOTSWAP_END) && getMarkingState(child) == 9) {

                        // 【検証ログ 1】利用可能なマップのキーをすべて出力（最初の1回のみでOK）
                        if(debugLogEnabled) System.out.println("  [Debug-Stitch] Available keys in NC map: " + newControllerConnectionMap.keySet());

                        // 【検証ログ 2】シグネチャ構築プロセスの詳細化
                        List<State> vs = child.getStates();
                        if(debugLogEnabled) System.out.println("  [Debug-Stitch] Constructing signature for child vector: " + vs);
    
                        StringBuilder envPart = new StringBuilder();
                        for (int k = mappingStart; k <= mappingEnd; k++) {
                            Object mapEnvState = vs.get(k);
                            Integer mapEnvId = (mapEnvState instanceof Long) ? ((Long) mapEnvState).intValue() : (Integer) mapEnvState;
                            Integer newEnvId = mappingMapEnvToNewEnv.get(k - mappingStart).get(mapEnvId);
                            envPart.append(newEnvId).append(",");
                        }
                        if(debugLogEnabled) System.out.println("    -> Env part (translated): " + envPart);

                        StringBuilder safePart = new StringBuilder();
                        for (int k = newSafeStart; k <= newSafeEnd; k++) {
                            safePart.append(vs.get(k)).append(",");
                        }
                        if(debugLogEnabled) System.out.println("    -> Safe part (raw from vector): " + safePart);

                        String signature = generateNCSignature(child);
                        // Long ncStateId = newControllerConnectionMap.get(signature);
                        Long ncStateId = (signature != null) ? newControllerConnectionMap.get(signature) : null;

                        if (ncStateId != null) {
                            // NC マップで見つかった ID へ直接リンクを張る
                            if(debugLogEnabled) log("  [Stitch] Connecting " + current.getStates() + " --(hotswap_end)--> NC State " + ncStateId);
                            result.addTransition(currentId, hAction.getAction(), ncStateId);
                        } else {
                            // 制約5に基づき、エラー時は詳細なベクトルを出力
                            System.err.println("!!! [Stitch-Error] No NC state mapping found for signature: " + signature);
                            System.err.println("    Target child vector: " + child.getStates());
                            throw new IllegalStateException("Missing NC mapping for reached state during stitching.");
                        }
                    } else {
                        // 通常の遷移
                        if (!ids.containsKey(child)) {
                            ids.put(child, nextId++);
                            result.addState(ids.get(child));
                            queue.add(child);
                        }
                        String actionName = hAction.toString().replace("_old", "");
                        @SuppressWarnings("unchecked")
                        Action finalAction = (Action) actionName;
                        result.addTransition(currentId, finalAction, ids.get(child));
                    }
                }
            }
        }
        statistics.setControllerUsedStates(result.getStates().size());
        return result;
    }

    /**
     * 到達した子状態のベクトルから、StateMapper が解釈可能なシグネチャを生成する
     */
    /**
     * 指定された状態から、新コントローラ照合用のシグネチャを生成する
     * 翻訳不能な環境状態が含まれる場合は null を返す
     */
    private String generateNCSignature(CompostateDUC<State, Action> compostate) {
        List<State> vs = compostate.getStates();
        StringBuilder sb = new StringBuilder();

        // 1. Environment セグメントの翻訳と連結
        for (int k = mappingStart; k <= mappingEnd; k++) {
            if (k > mappingStart) sb.append(",");
            Object mapEnvState = vs.get(k);
            Integer mapEnvId = (mapEnvState instanceof Long) ? ((Long) mapEnvState).intValue() : (Integer) mapEnvState;
            
            Map<Integer, Integer> map = mappingMapEnvToNewEnv.get(k - mappingStart);
            if (!map.containsKey(mapEnvId)) {
                return null; // 翻訳不可
            }
            sb.append(map.get(mapEnvId));
        }

        sb.append("|");

        // 2. New Safety セグメントの連結
        for (int k = newSafeStart; k <= newSafeEnd; k++) {
            if (k > newSafeStart) sb.append(",");
            sb.append(vs.get(k));
        }

        return sb.toString();
    }

    public boolean isGoal(CompostateDUC<State, Action> state) {
        return state.isStatus(Status.GOAL);
    }

    public boolean isError(CompostateDUC<State, Action> state) {
        return state.isStatus(Status.ERROR);
    }

    public boolean isFinished() {
        return initial.isStatus(Status.GOAL) || initial.isStatus(Status.ERROR);
    }

    public void setError(CompostateDUC<State, Action> state) {
        // ★追加: エラー判定された状態をログ出力
        log("[ERROR DETECTED] State marked as ERROR: " + state.getStates());
        // ステータス更新
        state.setStatus(Status.ERROR);

        // ★修正点3: エラー発生時もヒューリスティックに通知する
        heuristic.notifyStateSetErrorOrGoal(state);
    }

    // ★追加: アクションがブロックされている原因を探る診断メソッド
    private void debugCheckActionAvailability(CompostateDUC<State, Action> state, String actionName) {
        log("  [DEBUG] Diagnosing action: " + actionName);
        long markingState = getMarkingState(state);

        for (int i = 0; i < ltssSize; ++i) {
            // ★修正: Trace対象外のコンポーネントもスキップせずに診断する
            // if (!isTrace(i, markingState)) continue;

            LTS<State, Action> lts = ltss.get(i);
            State curr = state.getStates().get(i);

            // ★追加: 正規化された状態 (-2L) のチェック
            // トレース対象外のコンポーネントは実機LTSに状態が存在しないため、
            // getTransitions を呼ぶと NPE や例外の原因となる。
            if (curr instanceof Long && (Long) curr == -2L) {
                log(String.format("    LTS %d : IGNORED (Normalized State -2L, Trace=OFF) (State=%s)", i, curr));
                continue;
            }
            
            boolean enforce = isEnforce(i, markingState);
            boolean trace = isTrace(i, markingState); // ★追加: Trace状態を取得

            boolean hasTransition = false;
            boolean hasActionInAlphabet = false;

            // 1. アルファベットに含まれているか確認 (String比較)
            for (Action a : lts.getActions()) {
                if (a.toString().equals(actionName)) {
                    hasActionInAlphabet = true;
                    break;
                }
            }

            // 2. 現在の状態から遷移できるか確認 (String比較)
            BinaryRelation<Action, State> origTrans = lts.getTransitions(curr);
            if (origTrans != null) {
                for (Pair<Action, State> trans : origTrans) {
                    if (trans.getFirst().toString().equals(actionName)) {
                        hasTransition = true;
                        break;
                    }
                }
            }

            String status = "";
            if (hasTransition) {
                if (!trace) {
                    // ★追加: 遷移はあるが、Trace=OFFなので採用されない（これが意図した動作かは文脈によるが、今回のバグの原因ではない）
                    status = "IGNORED (Trace=OFF) (Transition exists but Trace=OFF)";
                } else {
                    status = "OK (Transition found)";
                }
            } else if (hasActionInAlphabet) {
                if (!trace) {
                    // ★追加: Trace=OFF かつ Alphabetに含まれる -> updateAllowedSetForComponent でブロックされる原因
                    status = "!!! BLOCKED (Trace=OFF) !!! (In Alphabet, but Trace=FALSE blocks everything)";
                } else if (enforce) {
                    // アルファベットにあるのに遷移がなく、かつEnforceなら「ブロック」の原因
                    status = "!!! BLOCKED !!! (In Alphabet, Enforce=TRUE, but no transition)";
                } else {
                    status = "ALLOWED (In Alphabet, Enforce=FALSE, so no transition is OK)";
                }
            } else {
                status = "IGNORED (Not in Alphabet)";
            }

            log(String.format("    LTS %d : %s (State=%s)", i, status, curr));
        }
    }

    /**
 * OTF-DUCのパフォーマンス計測用プロファイラ
 */
public static class DUCProfiler {
    public static long timeExpansion = 0;   // 状態展開（LTS同期）
    public static long timeEval = 0;        // evalメソッド（ヒューリスティック計算+ソート）
    public static long timeRecompute = 0;   // recomputeEstimates（フロンティアの再評価）
    public static long timeFrontier = 0;    // PriorityQueueの操作

    // --- LTS Expansion の内訳 ---
    public static long timeSync = 0;        // 19個のLTSを走査して次の状態ベクトルを作る時間
    public static long timeLookup = 0;      // 既存の状態かMapで検索し、新しければ生成する時間
    public static long timeNewStateInit = 0; // heuristic.newState() (eval含む) の時間

    // --- explore 内部の内訳 ---
    public static long timePropagation = 0; // propagateGoal / propagateError
    public static long timeLoopCheck = 0;   // closingALoop / gatherLoopStates
    public static long timeFixedPoint = 0;  // findNewGoals / findNewErrors

    // メソッドごとの合計
    public static long timePropagateGoalTotal = 0;
    public static long timePropagateErrorTotal = 0;

    // propagateGoal 内部の内訳
    public static long timeGoalPhase1 = 0;      // 通常の波及 (Queue)
    public static long timeGoalPhase2Init = 0;  // Phase 2 の準備 (全状態スキャン)
    public static long timeGoalPhase2Loop = 0;  // Phase 2 の不動点計算ループ
    public static long timeGoalDistUpdate = 0;

    // --- 実行回数と密度のカウンタ ---
    public static int countPropGoalCalls = 0;   // propagateGoal が呼ばれた回数
    public static int totalCandidatesProcessed = 0; // Phase 2 で処理した累積状態数
    public static int totalLtsExpansions = 0;   // 実際に expandDUC された回数

    /**
     * 計測結果をLTSAコンソールに表示
     */
    public static void printSummary(LTSOutput output) {
        output.outln("---- OTF-DUC Propagation Detailed (ms) ----");
        output.outln("1. propagateError Total   : " + String.format("%.2f", timePropagateErrorTotal / 1_000_000.0));
        output.outln("2. propagateGoal Total    : " + String.format("%.2f", timePropagateGoalTotal / 1_000_000.0));
        output.outln("   -> Phase 1 (Queue)     : " + String.format("%.2f", timeGoalPhase1 / 1_000_000.0));
        output.outln("   -> Phase 2 Init (Scan) : " + String.format("%.2f", timeGoalPhase2Init / 1_000_000.0));
        output.outln("   -> Phase 2 Loop        : " + String.format("%.2f", timeGoalPhase2Loop / 1_000_000.0));
        output.outln("   -> UpdateDistances        : " + String.format("%.2f", timeGoalDistUpdate / 1_000_000.0));
        output.outln("-------------------------------------------");

        output.outln("---- OTF-DUC explore Breakdown (ms) ----");
        output.outln("1. Goal/Error Prop  : " + String.format("%.2f", timePropagation / 1_000_000.0));
        output.outln("2. Loop Check       : " + String.format("%.2f", timeLoopCheck / 1_000_000.0));
        output.outln("3. Fixed-Point Calc : " + String.format("%.2f", timeFixedPoint / 1_000_000.0));
        output.outln("----------------------------------------");

        output.outln("---- OTF-DUC Performance Breakdown (ms) ----");
        output.outln("1. Sync Calculation : " + String.format("%.2f", timeSync / 1_000_000.0));
        output.outln("2. State Lookup/Map  : " + String.format("%.2f", timeLookup / 1_000_000.0));
        output.outln("3. NewState Init     : " + String.format("%.2f", timeNewStateInit / 1_000_000.0));
        output.outln("--------------------------------------------");
        output.outln("Total LTS Expansion  : " + String.format("%.2f", timeExpansion / 1_000_000.0));

        output.outln("---- OTF-DUC Performance Summary (ms) ----");
        output.outln("LTS Expansion      : " + String.format("%.2f", timeExpansion / 1_000_000.0));
        output.outln("Heuristic Eval     : " + String.format("%.2f", timeEval / 1_000_000.0));
        output.outln("Recompute Estimates: " + String.format("%.2f", timeRecompute / 1_000_000.0));
        output.outln("Frontier Ops       : " + String.format("%.2f", timeFrontier / 1_000_000.0));
        output.outln("------------------------------------------");

        output.outln("---- OTF-DUC Workload Stats ----");
        output.outln("Total Expansions    : " + totalLtsExpansions);
        output.outln("PropagateGoal Calls : " + countPropGoalCalls);
        output.outln("Total Candidates P2 : " + totalCandidatesProcessed);
        if (countPropGoalCalls > 0) {
            output.outln("Avg Candidates/Call : " + (totalCandidatesProcessed / (double)countPropGoalCalls));
        }
        output.outln("--------------------------------");
    }

    /**
     * 全てのカウンタをリセット
     */
    public static void reset() {
        timeExpansion = 0;
        timeEval = 0;
        timeRecompute = 0;
        timeFrontier = 0;

        timeSync = 0;        // 19個のLTSを走査して次の状態ベクトルを作る時間
        timeLookup = 0;      // 既存の状態かMapで検索し、新しければ生成する時間
        timeNewStateInit = 0; 
    }
}
}