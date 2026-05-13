package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import MTSTools.ac.ic.doc.commons.collections.BidirectionalMap;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedLTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.DirectedControllerSynthesis;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
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
    // public Map<List<State>, CompostateDUC<State, Action>> compostates;

    // Mapの型を専用キーに変更
    public Map<StateKey, CompostateDUC<State, Action>> compostates;

    // --- 最適化用バッファ ---
    private int[] traceMasks = new int[10];     // isTrace判定用ビットマスク
    private long[] lookupBuffer;               // アンボクシング＆正規化用
    private StateKey reusableKey;              // 検索専用（new しない）

    // ボクシング抑制用定数
    private final State NORMALIZED_VAL = (State) Long.valueOf(-2L);
    private final State ERROR_VAL = (State) Long.valueOf(-1L);

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

    private boolean debugLogEnabled = Boolean.getBoolean("otfduc.debug");
    private PrintWriter logWriter;
    private static final String LOG_FILE_PATH = System.getProperty("otfduc.debug.file", "duc_debug.txt");

    private List<Map<Integer, Integer>> mappingMapEnvToNewEnv;
    private Map<String, Long> newControllerConnectionMap;
    private LTS<Long, String> newController;

    // 合成済み安全モニタと元の環境コンポーネントを対応づけるための表。
    private Map<Integer, List<Integer>> safetyComponentIndicesMap;
    private Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap;
    protected LTSOutput output;

    private long errorMarkCount = 0;
    private long loopErrorCount = 0;
    private long fairControllableExitRejectedCount = 0;
    private String lastErrorSummary = "none";
    private String lastLoopErrorSummary = "none";
    private String lastFairControllableExitRejectedSummary = "none";
    private long generatedChildCount = 0;
    private long existingCompostateHitCount = 0;
    private long newCompostateCount = 0;
    private long safetyViolationChildCount = 0;
    private long finishUpdateGuardBlockedCount = 0;
    private long detectedLoopCount = 0;
    private long preUpdateLoopExceptionCount = 0;
    private long fairPromotedLoopCount = 0;
    private long directorCandidateTransitions = 0;
    private long directorOutputTransitions = 0;
    private long prunedControllableTransitions = 0;
    private long finishUpdateTransitions = 0;
    private long ncConnectionSuccessCount = 0;
    private long ncConnectionMissCount = 0;
    private long preUpdateOutputMergedStates = 0;
    private long preUpdateOutputMergeRemovedStates = 0;

    private int totalLtsExpansions = 0;
    private long synthesizeDUCTime = 0;
    private long searchTime = 0;
    private long countTime = 0;
    private long buildDirectorDUCTime = 0;
    private long transferNCTime = 0;
    private long stitchingNCTime = 0;
    private int otfPeakStates = 0;
    private int otfPeakTrans = 0;

    private long heuristicSelectionNanos = 0;
    private long heuristicRecomputeNanos = 0;
    private long heuristicFrontierNanos = 0;
    private long heuristicEvaluationNanos = 0;
    private long stateExpansionNanos = 0;
    private long successorGenerationNanos = 0;
    private long componentSyncNanos = 0;
    private long cartesianProductNanos = 0;
    private long safetySyncNanos = 0;
    private long stateCanonicalizationNanos = 0;
    private long stateLookupNanos = 0;
    private long newStateRegistrationNanos = 0;
    private long enforceSafetyCheckNanos = 0;
    private long finishUpdateGuardNanos = 0;
    private long childRegistrationNanos = 0;
    private long exploreNanos = 0;
    private long loopDetectionNanos = 0;
    private long fairnessAnalysisNanos = 0;
    private long fairPromotionNanos = 0;
    private long propagateGoalNanos = 0;
    private long propagateErrorNanos = 0;
    private long propagateGoalPhase1Nanos = 0;
    private long propagateGoalPhase2Nanos = 0;
    private long propagateGoalDistanceUpdateNanos = 0;
    private long outputPruningDecisionNanos = 0;
    private long directorTraversalNanos = 0;

    private long heuristicSelectionCalls = 0;
    private long heuristicRecomputeRuns = 0;
    private long heuristicRecomputedStates = 0;
    private long heuristicEvaluationCalls = 0;
    private long stateLookupCalls = 0;
    private long finishUpdateGuardChecks = 0;
    private long loopDetectionCalls = 0;
    private long fairnessAnalysisCalls = 0;
    private long propagateGoalCalls = 0;
    private long propagateErrorCalls = 0;
    private long outputPruningDecisionCalls = 0;
    private long totalFairnessCandidatesProcessed = 0;

    // OTF-DUC の直積モデルにおけるコンポーネントのインデックス範囲。
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

    public int synthesisStart = -1; // モニタと fluent
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
        
        long synthesizeDUCStart = System.currentTimeMillis();

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
                log(String.format("Config: MarkingLTS[0], OldController[1], MapEnv[%d-%d], OldSafe[%d-%d], NewSafe[%d-%d], TransReq[%d-%d]",
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

            long searchStart = System.currentTimeMillis();

            // isFinished() は初期状態がGOAL/ERRORになればtrue
            while (heuristic.somethingLeftToExplore() && !isFinished()) {
                statistics.startHeuristicTime();

                // --- 1. ヒューリスティック選択（Recompute + Frontier Ops）の計測 ---
                // ※詳細な内訳を測る場合は getNextState 内に埋めますが、
                //   まずは外側で「選択にかかる総時間」を測ります。
                // long startHeuristic = System.currentTimeMillis();

                long heuristicSelectionStart = System.nanoTime();
                Pair<CompostateDUC<State, Action>, HAction<State, Action>> next = heuristic.getNextAction();
                heuristicSelectionNanos += System.nanoTime() - heuristicSelectionStart;
                heuristicSelectionCalls++;

                statistics.endHeuristicTime();

                if (next == null) break; // 安全策

                CompostateDUC<State, Action> state = next.getFirst();
                HAction<State, Action> action = next.getSecond();

                // log(String.format("[Heuristic-Next] State: %s, Action: %s (%s)", state.getStates(), action, action.isControllable() ? "C" : "U"));

                // 状態自体が GOAL と確定した後は、追加の controllable 分岐は
                // 出力 controller に採用しない。一方で、GOAL 子を 1 つ見ただけの
                // 暫定段階では、非決定分岐や他の controllable 候補の探索を止めない。
                if (isGoal(state) && action.isControllable()) {
                    // log("  [Pruning] Skipping redundant controllable action '" + action + "' for state " + state.getStates());
                    heuristic.expansionDone(state, action, null);
                    continue;
                }

                // --- 2. 状態展開（Expansion）の計測 ---
                // long startExp = System.currentTimeMillis();

                totalLtsExpansions++; // 展開回数をカウント
                long expansionStart = System.nanoTime();
                expandDUC(state, action);
                stateExpansionNanos += System.nanoTime() - expansionStart;

            }

            statistics.end();

            //評価実験用
            searchTime = System.currentTimeMillis() - searchStart;
            long countStart = System.currentTimeMillis();
            otfPeakStates = compostates.size();
            otfPeakTrans = countOTFTransitions();
            countTime = System.currentTimeMillis() - countStart;
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF DCS 探索終了後");

            if (isGoal(initial)) {
                log("Goal Reached! Building Director...");

                long buildDirectorDUCStart = System.currentTimeMillis();
                LTS<Long, Action> result = buildDirectorDUC();
                buildDirectorDUCTime = System.currentTimeMillis() - buildDirectorDUCStart;
                UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF buildDirectorDUC 後");

                return result;
                // return buildDirectorDUC();
            } else {
                log("Goal NOT Reached. Synthesis Failed.");
                if (debugLogEnabled) {
                    emitFailureDiagnostics();
                }
            }
            return null;

        } finally {
            if (logWriter != null) {
                log("=== Synthesis Finished ===");
                logWriter.close();
            }
            synthesizeDUCTime = System.currentTimeMillis() - synthesizeDUCStart;
            // 合成完了後
            recordOtfDcsTimingEvaluation();
            recordOtfDetailedEvaluation();
        }
    }

    public void log(String message) {
        if (debugLogEnabled && logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    void addHeuristicRecomputeNanos(long nanos) {
        heuristicRecomputeNanos += nanos;
    }

    void addHeuristicFrontierNanos(long nanos) {
        heuristicFrontierNanos += nanos;
    }

    void addHeuristicEvaluationNanos(long nanos) {
        heuristicEvaluationNanos += nanos;
    }

    void incrementHeuristicEvaluationCalls() {
        heuristicEvaluationCalls++;
    }

    void incrementHeuristicRecomputeRuns() {
        heuristicRecomputeRuns++;
    }

    void addHeuristicRecomputedStates(long states) {
        heuristicRecomputedStates += states;
    }

    private void setupSynthesisDUC(List<LTS<State, Action>> ltss, Set<Action> controllable) {
        this.ltss = ltss;
        this.ltssSize = ltss.size();
        this.controllable = controllable;
        statistics.clear();
        statistics.start();
        errorMarkCount = 0;
        loopErrorCount = 0;
        fairControllableExitRejectedCount = 0;
        totalLtsExpansions = 0;
        synthesizeDUCTime = 0;
        searchTime = 0;
        countTime = 0;
        buildDirectorDUCTime = 0;
        transferNCTime = 0;
        stitchingNCTime = 0;
        otfPeakStates = 0;
        otfPeakTrans = 0;
        heuristicSelectionNanos = 0;
        heuristicRecomputeNanos = 0;
        heuristicFrontierNanos = 0;
        heuristicEvaluationNanos = 0;
        stateExpansionNanos = 0;
        successorGenerationNanos = 0;
        componentSyncNanos = 0;
        cartesianProductNanos = 0;
        safetySyncNanos = 0;
        stateCanonicalizationNanos = 0;
        stateLookupNanos = 0;
        newStateRegistrationNanos = 0;
        enforceSafetyCheckNanos = 0;
        finishUpdateGuardNanos = 0;
        childRegistrationNanos = 0;
        exploreNanos = 0;
        loopDetectionNanos = 0;
        fairnessAnalysisNanos = 0;
        fairPromotionNanos = 0;
        propagateGoalNanos = 0;
        propagateErrorNanos = 0;
        propagateGoalPhase1Nanos = 0;
        propagateGoalPhase2Nanos = 0;
        propagateGoalDistanceUpdateNanos = 0;
        outputPruningDecisionNanos = 0;
        directorTraversalNanos = 0;
        heuristicSelectionCalls = 0;
        heuristicRecomputeRuns = 0;
        heuristicRecomputedStates = 0;
        heuristicEvaluationCalls = 0;
        stateLookupCalls = 0;
        finishUpdateGuardChecks = 0;
        loopDetectionCalls = 0;
        fairnessAnalysisCalls = 0;
        propagateGoalCalls = 0;
        propagateErrorCalls = 0;
        outputPruningDecisionCalls = 0;
        totalFairnessCandidatesProcessed = 0;
        lastErrorSummary = "none";
        lastLoopErrorSummary = "none";
        lastFairControllableExitRejectedSummary = "none";
        generatedChildCount = 0;
        existingCompostateHitCount = 0;
        newCompostateCount = 0;
        safetyViolationChildCount = 0;
        finishUpdateGuardBlockedCount = 0;
        detectedLoopCount = 0;
        preUpdateLoopExceptionCount = 0;
        fairPromotedLoopCount = 0;
        directorCandidateTransitions = 0;
        directorOutputTransitions = 0;
        prunedControllableTransitions = 0;
        finishUpdateTransitions = 0;
        ncConnectionSuccessCount = 0;
        ncConnectionMissCount = 0;
        preUpdateOutputMergedStates = 0;
        preUpdateOutputMergeRemovedStates = 0;
        compostates = new HashMap<>();
        setupLookupOptimizations();
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

    /**
     * [最適化] 探索開始前に一度だけ実行し、重い判定処理を事前計算する。
     */
    private void setupLookupOptimizations() {
        this.lookupBuffer = new long[ltssSize];
        this.reusableKey = new StateKey(); // 検索専用インスタンス
    
        // isTraceの結果をビットマスク化 (Marking 0-9)
        // [最適化] ループ内での isTrace メソッド呼び出し(仮想関数オーバーヘッド)を排除するため、
        // 各フェーズにおける Trace 対象コンポーネントをビットマスク(int)として保持。
        for (int m = 0; m <= 9; m++) {
            int mask = 0;
            for (int i = 0; i < ltssSize; i++) {
                if (isTrace(i, (long) m)) mask |= (1 << i);
            }
            traceMasks[m] = mask;
        }
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

    private CompostateDUC<State, Action> buildInitialState() {
        List<State> states = new ArrayList<>(ltss.size());
        for (LTS<State, Action> lts : ltss)
            states.add(lts.getInitialState());
        CompostateDUC<State, Action> initial = buildCompostate(states, null);
        initial.setDepth(0); // 初期状態の深さを 0 に固定する。
        return initial;
        // return buildCompostate(states, null);
    }

    public CompostateDUC<State, Action> buildCompostate(List<State> states, CompostateDUC<State, Action> parent) {
        // 状態の正規化（Canonicalization）ロジック
        // 現在の更新フェーズにおいて追跡（Trace）対象外となっているコンポーネントは、
        // 将来の挙動に影響を与えないため、状態IDを固定値 -2L に統一する。
        // これにより、インターリービングの順序違いなどで生じる等価な状態がハッシュキーレベルで一致するようになる。

        long canonicalizationStart = System.nanoTime();

        // [最適化] getMarkingStateFromList 内の instanceof 呼び出しを削減するための型キャスト
        long mState = getMarkingStateFromList(states);
        // [最適化] ビットマスクを取得。これにより 19要素のループ内での分岐が極めて高速になる。
        int currentMask = traceMasks[(int) mState];

        // 1. プリミティブ配列バッファへの転記と同時に正規化
        // 1. 正規化とアンボクシングの同時実行
        for (int i = 0; i < ltssSize; i++) {
            // [最適化] 一度だけ Long -> long に変換。以降、Map照合まで数値として扱う。
            long val = (Long) states.get(i); // ここで一度だけアンボクシング
            // i番目のビットが 0 (Trace対象外) かつ エラー状態でない場合
            if (((currentMask >> i) & 1) == 0 && val != -1L) {
                // [最適化] ループ内での Long.valueOf 呼び出しを避けるため、事前に作成した定数を代入。
                // これによりメモリ上のポインタ書き換えだけで正規化が完了する。
                val = -2L;
                states.set(i, NORMALIZED_VAL); // リスト側も更新（戻り値の型維持のため）
            }
            // [最適化] Map検索用のプリミティブ配列バッファを構築
            lookupBuffer[i] = val;
        }
        stateCanonicalizationNanos += System.nanoTime() - canonicalizationStart;

        // 2. Map検索
        // [最適化] 検索のたびに new StateKey(...) せず、既存の reusableKey の中身を書き換えて再利用。
        // Mapにヒットする場合、ここでのヒープメモリ確保は一切発生しない。
        long lookupStart = System.nanoTime();
        reusableKey.wrap(lookupBuffer);
        CompostateDUC<State, Action> result = compostates.get(reusableKey);
        stateLookupNanos += System.nanoTime() - lookupStart;
        stateLookupCalls++;

        if (result == null) {
            long registrationStart = System.nanoTime();
            // 新しい状態が見つかったときのみ、永続化のためのオブジェクトを生成
            statistics.incExpandedStates();
            newCompostateCount++;
        
            // 3. 新しい状態の場合のみ、永続的なオブジェクトを生成
            List<State> persistentList = new ArrayList<>(states);
            StateKey permanentKey = new StateKey(lookupBuffer);
        
            result = new CompostateDUC<>(this, persistentList);
            compostates.put(permanentKey, result);

            // Marking 8 では finishUpdate ガードを先に評価する。
            // 状態生成時にあらかじめチェックすることで、ヒューリスティックがこの手を選ばないようにする
            if (mState == 8) {
                long guardStart = System.nanoTime();
                finishUpdateGuardChecks++;
                if (!checkHotswapEndCondition(result)) {
                    result.setFinishUpdateBlocked(true);
                }
                finishUpdateGuardNanos += System.nanoTime() - guardStart;
            }
            // ======================================================
        
            heuristic.newState(result, parent);
            if (mState == 9) {
                result.setStatus(Status.GOAL);
                // result.setStatus(CompostateDUC.Status.GOAL);
                result.setBestControllable(0, null);
            }
            long safetyCheckStart = System.nanoTime();
            boolean enforceError = checkErrorWithEnforce(result);
            enforceSafetyCheckNanos += System.nanoTime() - safetyCheckStart;
            if (enforceError || heuristic.fullyExplored(result)) {
                setError(result);
            }
            newStateRegistrationNanos += System.nanoTime() - registrationStart;
        } else {
            existingCompostateHitCount++;
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
            // --- Pre-beginUpdate (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False (ここで return false されるため)
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-beginUpdate (State 1-9) ---
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
            // --- Pre-beginUpdate (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-beginUpdate (State 1-9) ---
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
        // beginUpdate 発火後（State 1 以上）は旧コントローラを trace から外す。
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

    // =====================================================================
    // expandDUC: 非決定的遷移を考慮し、複数の子状態をまとめて展開する。
    // =====================================================================
    void expandDUC(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        // 同じ状態・アクションの重複展開を検知する。
        Set<CompostateDUC<State, Action>> alreadyExplored = state.getExploredChildren().getImage(action);
        if (alreadyExplored != null && !alreadyExplored.isEmpty()) {
            log("!!! [ALARM] Redundant Expansion detected!");
            log("    State:  " + state.getStates());
            log("    Action: " + action + " has been explored before.");
            log("    Current Explored Children: " + alreadyExplored);
        }

        statistics.incExpandedTransitions();

        // 1. ブロック条件のチェック
        boolean blocked = false;
        if (action.toString().equals(UpdateConstants.FINISH_UPDATE)) {
            long guardStart = System.nanoTime();
            finishUpdateGuardChecks++;
            boolean hotswapEndAllowed = checkHotswapEndCondition(state);
            finishUpdateGuardNanos += System.nanoTime() - guardStart;
            if (!hotswapEndAllowed) {
                blocked = true;
                finishUpdateGuardBlockedCount++;
            }
        }

        // 2. 次の状態（生のリストの直積リスト）の取得
        List<List<State>> allNextStates = null;
        if (!blocked) {
            long successorStart = System.nanoTime();
            allNextStates = getChildStatesDUC_Nondet(state, action);
            successorGenerationNanos += System.nanoTime() - successorStart;
        }

        if (allNextStates == null || allNextStates.isEmpty()) {
            if(debugLogEnabled) log(String.format("  [Critical-Deadlock] Action '%s' failed to synchronize at %s", action, state.getStates()));
            // debugCheckActionAvailability(state, action.toString());
        }

        // デバッグログ出力
        if (debugLogEnabled) {
            log("--------------------------------------------------------------------------------");
            log("[Expand Step] (Nondeterministic Support)");
            log("  Current State: " + state.getStates());
            // log(String.format("  Flags: Status=%s, Live=%s, InOpen=%s, Controlled=%s, Depth=%d, hasGoalChild=%b",
            //         state.getStatus(), state.isLive(), state.inOpen, state.isControlled(), state.getDepth(),
            //         state.hasGoalChild()));
            //log("  Marking State: " + getMarkingState(state));
            // 利用可能な遷移に C/U を付けてログへ出力する。
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
            if (blocked) log("  Result:                    BLOCKED");
            else if (allNextStates == null) log("  Result:                    INVALID");
            // else {
            //     for(List<State> s : allNextStates)
            //     log("  Next Compostate States:    " + s);
            // }
            // else log("  Generated Branches:        " + allNextStates.size() + " possible outcomes");
        }

        // 失敗・ブロック時の早期リターン
        if (blocked || allNextStates == null || allNextStates.isEmpty()) {
            if (blocked) log("[BLOCKED] Transition blocked by finishUpdate condition: " + action);
            else log("[DEADLOCK/INVALID] No valid next states for action: " + action);
            heuristic.expansionDone(state, action, null);
            // if (debugLogEnabled) log("--------------------------------------------------------------------------------");
            return;
        }

        // 3. 非決定的分岐（全ての次状態）を一つずつ生成し、全て探索ツリーに接続する。
        List<CompostateDUC<State, Action>> children = new ArrayList<>();
        generatedChildCount += allNextStates.size();
        for (List<State> nextStates : allNextStates) {
            CompostateDUC<State, Action> child = buildCompostate(nextStates, state);

            if (isError(child)) {
                safetyViolationChildCount++;
                if (debugLogEnabled) {
                    log(String.format("  [Safety-Violation] Action '%s' leads to ERROR state -> %s", action, child.getStates()));
                }
            }

            // ツリー構造への登録だけを先に行う
            long childRegistrationStart = System.nanoTime();
            state.addChild(action, child);
            child.addParent(action, state);
            children.add(child);
            childRegistrationNanos += System.nanoTime() - childRegistrationStart;
        }

        // 全ての分岐を登録し終わってから explore を評価する。
        for (CompostateDUC<State, Action> child : children) {
            heuristic.notifyExpandingState(state, action, child);
            long exploreStart = System.nanoTime();
            explore(state, action, child);
            exploreNanos += System.nanoTime() - exploreStart;
            child.setExpanded();
        }

        // 4. heuristic.expansionDone への完了通知
        CompostateDUC<State, Action> sampleChild = children.get(0);
        boolean allGoals = true;
        for (CompostateDUC<State, Action> c : children) {
            if (!isGoal(c)) {
                sampleChild = c;
                allGoals = false;
                break;
            }
        }
        if (allGoals) {
            heuristic.notifyExpansionDidntFindAnything(state, action, sampleChild); // 全分岐が GOAL の場合も代表子を通知する。
            heuristic.expansionDone(state, action, null);
        } else {
            heuristic.expansionDone(state, action, sampleChild);
        }
    }

    // =====================================================================
    // getChildStatesDUC_Nondet: 直積を計算し、全ての遷移先を列挙する。
    // =====================================================================
    private List<List<State>> getChildStatesDUC_Nondet(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        List<State> parentStates = state.getStates();
        long markingState = getMarkingState(state);

        String actionName = action.toString();
        boolean isOldAction = actionName.endsWith("_old");
        String strippedActionName = isOldAction ? actionName.replace("_old", "") : actionName;

        // 各LTSコンポーネントが取り得る「次状態の集合」をリストに保持
        List<Set<State>> possibleStatesPerLTS = new ArrayList<>(ltssSize);

        long componentSyncStart = System.nanoTime();
        for (int i = 0; i < ltssSize; ++i) {
            if (!isTrace(i, markingState)) {
                Set<State> s = new HashSet<>();
                s.add(parentStates.get(i));
                possibleStatesPerLTS.add(s);
                continue;
            }

            LTS<State, Action> lts = ltss.get(i);
            State curr = parentStates.get(i);
            Action rawAction = action.getAction();

            if (markingState == 0 && isOldAction && i != 1) {
                boolean found = false;
                Set<State> s = new HashSet<>();
                for (Pair<Action, State> trans : lts.getTransitions(curr)) {
                    if (trans.getFirst().toString().equals(strippedActionName)) {
                        s.add(trans.getSecond());
                        found = true;
                        // break しない！ 非決定的な分岐をすべて拾う
                    }
                }
                if (!found) s.add(curr);
                possibleStatesPerLTS.add(s);
            }
            // 通常の同期ケース。
            else {
                Set<State> image = lts.getTransitions(curr).getImage(rawAction);
                if (image == null || image.isEmpty()) {
                    // if (lts.getActions().contains(rawAction)) return null; // 無効アクション
                    if (isActive(i, markingState) && lts.getActions().contains(rawAction)) {
                        return null; // Active component だけが veto できる
                    }
                    // trace 専用または inactive のコンポーネントは自己ループとして扱う。
                    Set<State> s = new HashSet<>();
                    s.add(curr);
                    possibleStatesPerLTS.add(s);
                } else {
                    possibleStatesPerLTS.add(image); // 取れる行き先すべてをセット
                }
            }
        }
        componentSyncNanos += System.nanoTime() - componentSyncStart;

        // 全LTSの次状態候補から直積（Cartesian Product）を生成
        List<List<State>> cartesianProduct = new ArrayList<>();
        long cartesianStart = System.nanoTime();
        generateCartesianProduct(possibleStatesPerLTS, 0, new ArrayList<State>(), cartesianProduct);
        cartesianProductNanos += System.nanoTime() - cartesianStart;

        // startNewSpec の場合の Safety の同期(上書き)
        if (actionName.equals(UpdateConstants.START_NEW_SPEC)) {
            long safetySyncStart = System.nanoTime();
            for (List<State> childVector : cartesianProduct) {
                applySafetySync(childVector);
            }
            safetySyncNanos += System.nanoTime() - safetySyncStart;
        }

        return cartesianProduct;
    }

    // =====================================================================
    // 直積計算と Safety 同期のためのヘルパーメソッド。
    // =====================================================================
    private void generateCartesianProduct(List<Set<State>> sets, int index, List<State> current, List<List<State>> result) {
        if (index == sets.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (State s : sets.get(index)) {
            current.add(s);
            generateCartesianProduct(sets, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void applySafetySync(List<State> childStates) {
        for (Map.Entry<Integer, List<Integer>> entry : safetyComponentIndicesMap.entrySet()) {
            int safetyIdx = entry.getKey();
            List<Integer> compIndices = entry.getValue();

            // 遷移後(Child)の状態を使ってキーを作成 (純粋なFluentの組み合わせ)
            List<Integer> lookupKey = new ArrayList<>();
            for (int compIdx : compIndices) {
                Object sObj = childStates.get(compIdx);
                Integer sInt = (sObj instanceof Long) ? ((Long) sObj).intValue() : (Integer) sObj;
                lookupKey.add(sInt);
            }

            Map<List<Integer>, Integer> lookupTable = safetyStateLookupMap.get(safetyIdx);
            if (lookupTable != null && lookupTable.containsKey(lookupKey)) {
                // Hit: マップされた状態へ強制変更 (正常状態 または ERROR(-1))
                Integer targetStateInt = lookupTable.get(lookupKey);
                childStates.set(safetyIdx, (State) Long.valueOf(targetStateInt));

                // log("  [StateJump] Safety[" + safetyIdx + "] forced to State " + targetStateInt
                //         + " based on Fluents " + lookupKey);
            } else {
                // Miss: 完全なLook-up Tableに存在しない組み合わせ＝到達不能な不正状態なので無条件でERROR(-1)
                childStates.set(safetyIdx, (State) Long.valueOf(-1L));

                // log("  [StateJump-Error] Safety[" + safetyIdx + "] forced to ERROR (-1) due to unknown Fluent combination: " + lookupKey);
            }
        }
    }

    /**
     * finishUpdate の実行可否を判定するガード条件
     * 1. 環境状態が新環境へ翻訳可能であること
     * 2. 翻訳後の環境と現在の安全性状態の組み合わせが、新コントローラ(NC)に存在すること
     */
    private boolean checkHotswapEndCondition(CompostateDUC<State, Action> state) {
        // シグネチャを仮生成して NC マップとの照合を行う
        String signature = generateNCSignature(state);
        
        // 翻訳に失敗した（環境状態がマップにない）場合は null が返る想定
        if (signature == null) {
            log("  [finishUpdate Guard] BLOCKED: Environment state translation failed.");
            return false;
        }

        // NC の状態空間（newControllerConnectionMap）にキーが存在するかチェック
        boolean isSafeInNC = newControllerConnectionMap.containsKey(signature);

        if (!isSafeInNC && debugLogEnabled) {
            String debugSignature = generateMapSignature(state);
            // 不整合発見を検証するためのログ
            log("  [finishUpdate Guard] BLOCKED: MapSignature '(MapEnv) " + debugSignature + " (New Safety)' = Signature '(NewEnv) " + signature + " (New Safety)' is NOT found in New Controller's safe states.");
        }

        return isSafeInNC;
    }

    private String generateMapSignature(CompostateDUC<State, Action> compostate) {
        List<State> vs = compostate.getStates();
        StringBuilder sb = new StringBuilder();

        // 1. MapEnvironment セグメント
        for (int k = mappingStart; k <= mappingEnd; k++) {
            if (k > mappingStart) sb.append(",");
            Object mapEnvState = vs.get(k);
            Integer mapEnvId = (mapEnvState instanceof Long) ? ((Long) mapEnvState).intValue() : (Integer) mapEnvState;
            sb.append(mapEnvId);
        }

        sb.append("|");

        // 2. New Safety セグメントの連結
        for (int k = newSafeStart; k <= newSafeEnd; k++) {
            if (k > newSafeStart) sb.append(",");
            sb.append(vs.get(k));
        }

        return sb.toString();
    }

    private void explore(CompostateDUC<State, Action> parent, HAction<State, Action> action,
            CompostateDUC<State, Action> child) {
        if (isError(child) || child.heuristicStronglySuggestsIsError) {
            if (!isError(child))
                setError(child);

            propagateError(singleton(child), singleton(parent));
        } else if (isGoal(child)) {
            parent.setHasGoalChild(action);

            propagateGoal(singleton(child), singleton(parent));
        }
        else {
            long loopStart = System.nanoTime();
            loopDetectionCalls++;
            boolean isLoop = closingALoop(parent, child);
            if (isLoop) {
                gatherLoopStates(child);
            }
            loopDetectionNanos += System.nanoTime() - loopStart;

            if (isLoop) {
                detectedLoopCount++;
                // 更新前のループは、旧コントローラを環境化して探索しているために現れる。
                // これは更新進行の失敗ではなく、旧コントローラ上の別状態からも
                // beginUpdate への経路を確認する必要があることを意味する。
                boolean isPreUpdateLoop = true;
                for (CompostateDUC<State, Action> s : loop) {
                    if (getMarkingState(s) != 0) {
                        isPreUpdateLoop = false;
                        break;
                    }
                }

                if (isPreUpdateLoop) {
                    preUpdateLoopExceptionCount++;
                    // 旧コントローラ上のループは ERROR にせず、勝ち状態の固定点計算を進める。
                    propagateGoal(new HashSet<>(), singleton(parent));
                } else {
                    // 更新中のループは fairness の方針に従って判定する。
                    long fairnessStart = System.nanoTime();
                    fairnessAnalysisCalls++;
                    if (probablyWinningStates.size() > 0)
                        findNewGoals();
                    else
                        findNewErrors();
                    fairnessAnalysisNanos += System.nanoTime() - fairnessStart;
                }
            } else {
                heuristic.notifyExpansionDidntFindAnything(parent, action, child);
            }
        }
        dag.clear();
    }

    private void propagateGoal(Set<CompostateDUC<State, Action>> goals, Set<CompostateDUC<State, Action>> parents) {
        propagateGoalCalls++;
        long startTotal = System.nanoTime();
        long phase1Start = System.nanoTime();

        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>(parents);
        Set<CompostateDUC<State, Action>> winners = new HashSet<>();

        // --- Phase 1: 通常の勝利伝播 ---
        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.poll();
            if (isGoal(current)) continue;

            boolean allUncontrollableResolved = true;
            boolean hasUncontrollable = false;

            for (HAction<State, Action> action : current.getTransitions()) {
                if (!action.isControllable()) {
                    hasUncontrollable = true;
                    Set<CompostateDUC<State, Action>> children = current.getExploredChildren().getImage(action);
                    if (children == null || children.isEmpty()) {
                        allUncontrollableResolved = false;
                        break;
                    }
                    for (CompostateDUC<State, Action> child : children) {
                        if (!isGoal(child)) {
                            allUncontrollableResolved = false;
                            break;
                        }
                    }
                }
                if (!allUncontrollableResolved) break;
            }

            boolean hasWinningC = false;
            HAction<State, Action> winningC = null;
            for (HAction<State, Action> action : current.getTransitions()) {
                if (action.isControllable()) {
                    Set<CompostateDUC<State, Action>> children = current.getExploredChildren().getImage(action);
                    if (children != null && !children.isEmpty()) {
                        boolean allGoals = true;
                        for (CompostateDUC<State, Action> child : children) {
                            if (!isGoal(child)) {
                                allGoals = false;
                                break;
                            }
                        }
                        if (allGoals) {
                            hasWinningC = true;
                            winningC = action;
                            break;
                        }
                    }
                }
            }

            if (allUncontrollableResolved) {
                if (hasWinningC) {
                    applyGoalStatus(current, winningC, winners, queue);
                } else if (hasUncontrollable) {
                    HAction<State, Action> anyU = null;
                    for (HAction<State, Action> a : current.getTransitions()) {
                        if (!a.isControllable()) { anyU = a; break; }
                    }
                    applyGoalStatus(current, anyU, winners, queue);
                }
            }
        }
        propagateGoalPhase1Nanos += System.nanoTime() - phase1Start;

        // Phase 2: fairness のもとで勝ちとなる SCC を固定点計算する。
        //
        // 候補 SCC は、すべての uncontrollable 遷移が SCC 内または既に証明済みの
        // GOAL に向かい、かつ finishUpdate へ fair に到達できる場合だけ採用する。
        // ただし、uncontrollable 遷移で SCC 内に留まり続けられる場合、通常の
        // controllable 脱出口だけでは fairness の根拠にしない。更新プロトコルの
        // action は canUseActionForFairReachability で別扱いする。
        long phase2Start = System.nanoTime();
        boolean changed;
        do {
            changed = false;
            // long sP2Init = System.nanoTime();

            Set<CompostateDUC<State, Action>> candidates = new HashSet<>();
            for (CompostateDUC<State, Action> s : compostates.values()) {
                if (s.isStatus(Status.NONE) && s.isLive()) {
                    candidates.add(s);
                }
            }
            totalFairnessCandidatesProcessed += candidates.size();

            if (candidates.isEmpty()) break;

            // long sP2Loop = System.nanoTime();
            boolean innerChanged;
            Map<CompostateDUC<State, Action>, Integer> dist = new HashMap<>();

            // U-safety と fair 到達性の両方が安定するまで候補集合を絞り込む。
            do {
                innerChanged = false;

                // 1. U-safety フィルタ: 環境が候補集合の外へ出られるのは、
                // 既に証明済みの GOAL に向かう場合だけでなければならない。
                Iterator<CompostateDUC<State, Action>> it = candidates.iterator();
                while (it.hasNext()) {
                    CompostateDUC<State, Action> s = it.next();
                    boolean uIsSafe = true;
                    for (HAction<State, Action> action : s.getTransitions()) {
                        if (!action.isControllable()) {
                            Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                            if (children == null || children.isEmpty()) {
                                uIsSafe = false; break;
                            }
                            for (CompostateDUC<State, Action> child : children) {
                                if (!isGoal(child) && !candidates.contains(child)) {
                                    uIsSafe = false; break;
                                }
                            }
                        }
                        if (!uIsSafe) break;
                    }
                    if (!uIsSafe) {
                        it.remove();
                        innerChanged = true;
                    }
                }

                if (candidates.isEmpty()) break;

                // 2. fair 到達性フィルタ。
                dist.clear();
                Deque<CompostateDUC<State, Action>> distQueue = new ArrayDeque<>();

                // 証明済み GOAL へ 1 手で到達できる状態を距離計算の始点にする。
                for (CompostateDUC<State, Action> s : candidates) {
                    for (HAction<State, Action> action : s.getTransitions()) {
                        if (!canUseActionForFairReachability(s, action, candidates)) {
                            continue;
                        }
                        Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                        if (children != null && !children.isEmpty()) {
                            boolean allGoals = true;
                            for (CompostateDUC<State, Action> child : children) {
                                if (!isGoal(child)) {
                                    allGoals = false; break;
                                }
                            }
                            if (allGoals) {
                                dist.put(s, 1);
                                distQueue.add(s);
                                break;
                            }
                        }
                    }
                }

                // 候補集合の内側で fair 距離を逆向きに伝播する。
                while (!distQueue.isEmpty()) {
                    CompostateDUC<State, Action> current = distQueue.poll();
                    
                    for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> pRel : current.getParents()) {
                        CompostateDUC<State, Action> parent = pRel.getSecond();
                        if (candidates.contains(parent)) {
                            HAction<State, Action> actionFromParent = pRel.getFirst();
                            if (!canUseActionForFairReachability(parent, actionFromParent, candidates)) {
                                continue;
                            }
                            
                            boolean validMove = true;
                            int maxChildD = 0;
                            Set<CompostateDUC<State, Action>> siblings = parent.getExploredChildren().getImage(actionFromParent);
                            for (CompostateDUC<State, Action> sibling : siblings) {
                                if (isGoal(sibling)) {
                                    maxChildD = Math.max(maxChildD, 0);
                                } else if (candidates.contains(sibling) && dist.containsKey(sibling)) {
                                    maxChildD = Math.max(maxChildD, dist.get(sibling));
                                } else {
                                    validMove = false; break;
                                }
                            }

                            if (validMove) {
                                int newDist = maxChildD + 1;
                                int oldDist = dist.getOrDefault(parent, Integer.MAX_VALUE);
                                if (newDist < oldDist) {
                                    dist.put(parent, newDist);
                                    if (!distQueue.contains(parent)) {
                                        distQueue.add(parent);
                                    }
                                }
                            }
                        }
                    }
                }

                // finishUpdate への fair 経路を持たない閉じた成分を候補から外す。
                it = candidates.iterator();
                while (it.hasNext()) {
                    CompostateDUC<State, Action> s = it.next();
                    if (!dist.containsKey(s)) {
                        it.remove();
                        innerChanged = true;
                    }
                }

            } while (innerChanged);

            // 残った候補は U-safe であり、GOAL への fair 経路を持つ。
            if (!candidates.isEmpty()) {
                Map<CompostateDUC<State, Action>, HAction<State, Action>> exitActions = new HashMap<>();
                for (CompostateDUC<State, Action> s : candidates) {
                    HAction<State, Action> bestAction = null;
                    int bestDist = Integer.MAX_VALUE;

                    for (HAction<State, Action> action : s.getTransitions()) {
                        if (!canUseActionForFairReachability(s, action, candidates)) {
                            continue;
                        }
                        Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                        if (children != null && !children.isEmpty()) {
                            boolean validMove = true;
                            int maxChildD = 0;
                            for (CompostateDUC<State, Action> child : children) {
                                if (isGoal(child)) {
                                    maxChildD = Math.max(maxChildD, 0);
                                } else if (candidates.contains(child)) {
                                    maxChildD = Math.max(maxChildD, dist.get(child));
                                } else {
                                    validMove = false; break;
                                }
                            }
                            if (validMove) {
                                if (maxChildD < bestDist) {
                                    bestDist = maxChildD;
                                    bestAction = action;
                                } else if (maxChildD == bestDist && bestAction != null && !bestAction.isControllable() && action.isControllable()) {
                                    bestAction = action;
                                }
                            }
                        }
                    }
                    exitActions.put(s, bestAction);
                }

                for (CompostateDUC<State, Action> winner : candidates) {
                    applyGoalStatus(winner, exitActions.get(winner), winners, queue);
                    changed = true;
                }

                // 新しく証明された SCC から通常の GOAL 伝播を再開する。
                while (!queue.isEmpty()) {
                    CompostateDUC<State, Action> current = queue.poll();
                    if (isGoal(current)) continue;

                    boolean allUResolved = true;
                    boolean hasU = false;
                    for (HAction<State, Action> a : current.getTransitions()) {
                        if (!a.isControllable()) {
                            hasU = true;
                            Set<CompostateDUC<State, Action>> children = current.getExploredChildren().getImage(a);
                            if (children == null || children.isEmpty()) {
                                allUResolved = false; break;
                            }
                            for (CompostateDUC<State, Action> c : children) {
                                if (!isGoal(c)) {
                                    allUResolved = false; break;
                                }
                            }
                        }
                        if (!allUResolved) break;
                    }

                    HAction<State, Action> winningC = null;
                    for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> trans : current.getExploredChildren()) {
                        if (trans.getFirst().isControllable() && isGoal(trans.getSecond())) {
                            winningC = trans.getFirst();
                            break;
                        }
                    }

                    if (allUResolved) {
                        if (winningC != null) {
                            applyGoalStatus(current, winningC, winners, queue);
                        } else if (hasU) {
                            HAction<State, Action> anyU = null;
                            for (HAction<State, Action> a : current.getTransitions()) {
                                if (!a.isControllable()) { anyU = a; break; }
                            }
                            if (anyU != null) applyGoalStatus(current, anyU, winners, queue);
                        }
                    }
                }
            }
        } while (changed);
        propagateGoalPhase2Nanos += System.nanoTime() - phase2Start;
        if (!winners.isEmpty()) {
            updateDistances(goals, winners, winners.size());
        }
        propagateGoalNanos += System.nanoTime() - startTotal;
    }

    private boolean canUseActionForFairReachability(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action,
            Set<CompostateDUC<State, Action>> candidates) {

        if (!action.isControllable()) {
            return true;
        }

        // 更新前状態は、更新中に仮定する fairness の対象外である。
        // 旧コントローラ上の action が uncontrollable loop を作っていても、
        // beginUpdate は旧コントローラ状態空間からの有効な進行辺として残す。
        if (getMarkingState(state) == 0 && action.toString().equals(UpdateConstants.BEGIN_UPDATE)) {
            return true;
        }

        // 更新プロトコル action は update controller 内部の進行ステップである。
        // 環境 action が同じ SCC に戻れる場合でも fair 脱出口として扱う。
        // これを許さないと、環境の interleaving だけで通常の更新手順まで
        // 勝てない扱いになってしまう。
        if (isUpdateProtocolAction(action)) {
            return true;
        }

        // 環境 fairness は controllable 脱出口の発火を強制できない。
        // uncontrollable 遷移で fair SCC 内に留まり続けられる場合、
        // 同じ状態の通常 controllable 脱出口は finishUpdate への進行根拠にしない。
        boolean rejected = hasUncontrollableSuccessorIn(state, candidates);
        if (rejected) {
            fairControllableExitRejectedCount++;
            lastFairControllableExitRejectedSummary =
                    "action=" + action + ", " + summarizeStateForDiagnostics(state)
                    + ", candidatesByMarking=" + summarizeMarkingHistogram(candidates);
        }
        return !rejected;
    }

    private boolean isUpdateProtocolAction(HAction<State, Action> action) {
        String actionName = action.toString();
        return actionName.equals(UpdateConstants.STOP_OLD_SPEC)
            || actionName.equals(UpdateConstants.RECONFIGURE)
            || actionName.equals(UpdateConstants.START_NEW_SPEC)
            || actionName.equals(UpdateConstants.FINISH_UPDATE);
    }

    private boolean hasUncontrollableSuccessorIn(
            CompostateDUC<State, Action> state,
            Set<CompostateDUC<State, Action>> candidates) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (action.isControllable()) {
                continue;
            }

            Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
            if (children == null || children.isEmpty()) {
                continue;
            }

            for (CompostateDUC<State, Action> child : children) {
                if (candidates.contains(child)) {
                    return true;
                }
            }
        }
        return false;
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

        // GOAL 証明済みの action と、GOAL 子を見た暫定情報を分けて保持する。
        node.setDirectorActionToGoal(action);
        node.setHasGoalChild(action);
        winners.add(node);
        heuristic.notifyStateSetErrorOrGoal(node);

        // 親をキューに追加し、勝利が伝播するようにする
        for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> parentRel : node.getParents()) {
            CompostateDUC<State, Action> parentNode = parentRel.getSecond();
            // 親に対しては「子の一つが GOAL になった」暫定情報だけを記録する。
            // 親自身の director action は、親が GOAL と証明された時点で設定する。
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
     * キュー（Worklist）方式によるエラーの逆伝播処理。
     * uncontrollable な遷移先が 1 つでも ERROR なら親も ERROR とし、
     * controllable な遷移先が全て ERROR の場合も親を ERROR として伝播する。
     * 先祖の全スキャンを避け、ステータスが変化したノードの親のみを再評価します。
     */
    private void propagateError(Set<CompostateDUC<State, Action>> newErrors, Set<CompostateDUC<State, Action>> seedParents) {
        propagateErrorCalls++;
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
                // if (debugLogEnabled) {
                //     output.outln("  [Propagate-Error] State " + current.getStates() + " is now ERROR.");
                // }
            
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
        propagateErrorNanos += System.nanoTime() - start;
    }

    /**
     * 補助メソッド: 指定された状態がエラーになるべきか判定する
     */
    // reconfigure の非決定性を処理し、uncontrollable action だけが残っても待機しない。
    private boolean checkIfShouldBecomeError(CompostateDUC<State, Action> state) {
        // A. 環境によって強制的にエラー（安全性違反やデッドロック）へ連れて行かれるか
        if (forcedToError(state)) return true;

        // B. 勝ち筋（GOALへのパス）または「安全な待機パス（Uアクション）」が残っているか
        boolean hasPotentialWinningMove = false;
        boolean hasSafeUncontrollable = false;

        // 現在展開済みの遷移をチェック
        for (HAction<State, Action> action : state.getTransitions()) {
            Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);

            // まだ展開していないアクションがあるなら、それは希望があるとみなす
            if (children == null || children.isEmpty()) {
                if (action.isControllable()) hasPotentialWinningMove = true;
                else hasSafeUncontrollable = true;
            } else {
                if (action.isControllable()) {
                    // Cアクションは「すべての分岐が安全」な場合のみ有効
                    boolean allSafe = true;
                    for (CompostateDUC<State, Action> child : children) {
                        if (isError(child)) { 
                            allSafe = false; 
                            break; 
                        }
                    }
                    if (allSafe) hasPotentialWinningMove = true;
                } else {
                    hasSafeUncontrollable = true;
                }
            }
            if (hasPotentialWinningMove) break; // Cアクションでの勝ち筋が見つかればそれ以上探す必要なし
        }

        if (!hasPotentialWinningMove && hasSafeUncontrollable && debugLogEnabled) {
            log("  [Fairness-Hypothesis-Check] State " + state.getStates() + " has NO Controllable escape hatch, but SAFE UNCONTROLLABLE actions exist.");
        }

        // Cアクションでの勝ち筋がなくても、安全なUアクションがあるなら待機（エラーにしない）
        if (!hasPotentialWinningMove && hasSafeUncontrollable) {
            if (debugLogEnabled) log("  [Optimistic-Wait] State " + state.getStates() + " relies on safe Uncontrollable actions.");
            return false;
        }

        // Controllable な手も、安全な Uncontrollable な手も残っていない場合はエラー
        return !hasPotentialWinningMove && !hasSafeUncontrollable;
    }

    /**
     * 環境がエラーを強制できる場合、または完全探索後に安全な手が残っていない場合に true を返す。
     */
    private boolean forcedToError(CompostateDUC<State, Action> state) {
        boolean fullyExplored = heuristic.fullyExplored(state);
        boolean existsSafeMove = false;

        for (HAction<State, Action> action : state.getTransitions()) {
            Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
            
            if (!action.isControllable()) {
                // uncontrollable action は環境が選べるため、1 つでも ERROR 後続があれば失敗を強制され得る。
                if (children != null) {
                    for (CompostateDUC<State, Action> child : children) {
                        if (isError(child)) {

                            if (debugLogEnabled) log("  [Forced-Error] Uncontrollable action '" + action + "' leads to ERROR. Environment can force failure.");

                            return true;
                        }
                    }
                }
                existsSafeMove = true;
            } else {
                // controllable action は、非決定的な後続が全て ERROR を避ける場合だけ安全に選べる。
                if (children != null && !children.isEmpty()) {
                    boolean allSafe = true;
                    for (CompostateDUC<State, Action> child : children) {
                        if (isError(child)) {
                            allSafe = false;
                            break;
                        }
                    }
                    if (allSafe) existsSafeMove = true;
                }
            }
        }

        if (existsSafeMove) {
            heuristic.notifyStateIsNone(state);
            return false;
        }

        if (fullyExplored && debugLogEnabled) {
            log("  [Forced-Error] State " + state.getStates() + " has NO safe moves left (fully explored). Marking as ERROR.");
        }

        return fullyExplored;
    }

    private void gatherLoopStates(CompostateDUC<State, Action> child) {
        probablyWinningStates.clear();
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

        // ループ内の全状態を fair winning 固定点計算の候補にする。
        probablyWinningStates.addAll(loop);
    }

    /**
     * OTF-DUC は到達性問題だが、更新中の進行は環境 fairness のもとで判定する。
     * beginUpdate 後のループは、固定点計算で finishUpdate への fair 経路を
     * 証明できる場合だけ勝ちとし、それ以外は ERROR 候補として解析する。
     */
    private void findNewGoals() {
        statistics.incFindNewGoalsCalls();

        if (tryPromoteFairLoopToGoal()) {
            return;
        }

        findNewErrors();
    }

    private void findNewErrors() {
        statistics.incFindNewErrorsCalls();

        boolean hasEscapeHatch = false;

        boolean hasUncontrollableWait = false;
        boolean hasUnexploredUncontrollable = false;
        boolean hasOpenUncontrollableExit = false;

        if (debugLogEnabled) {
            log("  [Loop-Analysis] Analyzing detected loop for escape hatches (Unexplored C-actions)...");
        }

        for (CompostateDUC<State, Action> s : loop) {

            if (debugLogEnabled) {
                log("    Analyzing State: " + s.getStates());
            }

            for (HAction<State, Action> a : s.getTransitions()) {

                Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(a);

                if (a.isControllable()) {
                    if (debugLogEnabled) {
                        if (children != null && !children.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("      -> Controllable [").append(a).append("]: Explored. Children: ");
                            for (CompostateDUC<State, Action> child : children) {
                                sb.append(child.getStates()).append(" (Status: ").append(child.getStatus()).append("), ");
                            }
                            log(sb.toString());
                        } else {
                            log("      -> Controllable [" + a + "]: UNEXPLORED (Escape Hatch Found!)");
                        }
                    }

                    if (children == null || children.isEmpty()) {
                        hasEscapeHatch = true;
                    }
                    else{
                        // controllable action は、探索済みの非決定分岐がすべて
                        // ERROR を避ける場合に限り、まだ有効な脱出口候補である。
                        boolean leadsToError = false;
                        for (CompostateDUC<State, Action> child : children) {
                            if (isError(child)) {
                                leadsToError = true;
                                break;
                            }
                        }
                        if (!leadsToError) {
                            hasEscapeHatch = true;
                        }
                    }
                }
                else{
                    if (children == null || children.isEmpty()) {
                        hasUncontrollableWait = true;
                        hasUnexploredUncontrollable = true;
                        if (debugLogEnabled) {
                            log("      -> Uncontrollable [" + a + "]: UNEXPLORED (Potential Wait Found!)");
                        }
                    } else {
                        boolean allSafe = true;
                        for (CompostateDUC<State, Action> child : children) {
                            if (isError(child)) {
                                allSafe = false;
                                break;
                            }
                        }
                        if (allSafe) {
                            hasUncontrollableWait = true;
                            for (CompostateDUC<State, Action> child : children) {
                                if (child.isStatus(Status.NONE) && !loop.contains(child)) {
                                    hasOpenUncontrollableExit = true;
                                    break;
                                }
                            }
                            if (debugLogEnabled) {
                                log("      -> Uncontrollable [" + a + "]: Explored and Safe (Potential Wait Found!)");
                            }
                        } else {
                            if (debugLogEnabled) {
                                log("      -> Uncontrollable [" + a + "]: Explored but leads to ERROR.");
                            }
                        }
                    }
                }
            }
        }

        // 未探索の uncontrollable 挙動がある場合、ループを勝ち/負けと
        // 判定するにはまだ情報が足りない。
        if (hasUnexploredUncontrollable) {
            if (debugLogEnabled) {
                log("  [Fairness-Wait] Loop has an unexplored Uncontrollable action. Postponing ERROR marking.");
            }
            return;
        }

        // uncontrollable 遷移でループ外の NONE 状態へ出られるなら、
        // まだ閉じた負けループとは扱わない。
        if (hasOpenUncontrollableExit) {
            if (debugLogEnabled) {
                log("  [Fairness-Wait] Loop has a safe Uncontrollable exit to a NONE state. Postponing ERROR marking.");
            }
            return;
        }

        // 閉じた安全な uncontrollable ループは、fair 固定点計算で
        // finishUpdate への経路を証明できる場合だけ受理する。
        if (hasUncontrollableWait) {
            if (tryPromoteFairLoopToGoal()) {
                if (debugLogEnabled) {
                    log("  [Fairness-Goal] Safe Uncontrollable loop was accepted by fair goal propagation.");
                }
                return;
            }
        }

        // controllable 脱出口だけを持つループは、さらに探索する余地を残す。
        // ただし安全な uncontrollable ループも残っている場合、通常 controllable
        // 脱出口だけでは fairness の根拠にしない。
        if (hasEscapeHatch && !hasUncontrollableWait) {
            if (debugLogEnabled) {
                log("  [Livelock-Relaxation] Loop detected, but Controllable escape hatches exist and no Uncontrollable loop remains. Postponing ERROR marking.");
            }
            return;
        }

        if (hasUncontrollableWait && debugLogEnabled) {
            log("  [Fairness-Hypothesis-Check] Loop has NO Controllable escape hatch, but SAFE UNCONTROLLABLE actions exist!");
            log("  [Fairness-Hypothesis-Check] Fair propagation could not prove a path to finishUpdate. Marking as ERROR.");
        }

        if (debugLogEnabled) {
            log("  [Loop-Mark-Error] NO unexplored Controllable actions found in this cycle. Marking the entire loop as ERROR.");
        }

        loopErrorCount++;
        lastLoopErrorSummary = buildLoopErrorSummary(
                hasEscapeHatch,
                hasUncontrollableWait,
                hasUnexploredUncontrollable,
                hasOpenUncontrollableExit);

        for (CompostateDUC<State, Action> state : loop) {
            setError(state);
        }

        if (!isError(initial)) {
            propagateError(loop, null);
        }
    }

    private boolean tryPromoteFairLoopToGoal() {
        if (loop == null || loop.isEmpty()) {
            return false;
        }

        long promotionStart = System.nanoTime();
        Set<CompostateDUC<State, Action>> loopSnapshot = new HashSet<>(loop);
        propagateGoal(new HashSet<>(), loopSnapshot);

        for (CompostateDUC<State, Action> state : loopSnapshot) {
            if (isGoal(state)) {
                fairPromotedLoopCount++;
                fairPromotionNanos += System.nanoTime() - promotionStart;
                return true;
            }
        }
        fairPromotionNanos += System.nanoTime() - promotionStart;
        return false;
    }

    private String buildLoopErrorSummary(
            boolean hasEscapeHatch,
            boolean hasUncontrollableWait,
            boolean hasUnexploredUncontrollable,
            boolean hasOpenUncontrollableExit) {

        StringBuilder sb = new StringBuilder();
        sb.append("loopSize=").append(loop == null ? 0 : loop.size());
        sb.append(", hasControllableEscape=").append(hasEscapeHatch);
        sb.append(", hasSafeUncontrollable=").append(hasUncontrollableWait);
        sb.append(", hasUnexploredUncontrollable=").append(hasUnexploredUncontrollable);
        sb.append(", hasOpenUncontrollableExit=").append(hasOpenUncontrollableExit);
        sb.append(", markings=").append(summarizeMarkingHistogram(loop));

        if (loop != null && !loop.isEmpty()) {
            sb.append(", sampleStates=[");
            int shown = 0;
            for (CompostateDUC<State, Action> state : loop) {
                if (shown >= 5) {
                    sb.append("...");
                    break;
                }
                if (shown > 0) sb.append("; ");
                sb.append(summarizeStateForDiagnostics(state));
                sb.append(", actions=").append(summarizeActionsForDiagnostics(state, 6));
                shown++;
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private void emitFailureDiagnostics() {
        if (!debugLogEnabled || output == null) {
            return;
        }

        output.outln("================ OTF-DUC FAILURE DIAGNOSTICS ================");
        output.outln("Initial: " + summarizeStateForDiagnostics(initial));
        output.outln("Compostates: " + (compostates == null ? 0 : compostates.size())
                + ", exploredTransitions: " + countOTFTransitions());
        output.outln("StatusByMarking: " + summarizeStatusByMarking());
        output.outln("OpenNoneStates: " + countOpenNoneStates());
        output.outln("ErrorMarks: " + errorMarkCount + ", LoopErrors: " + loopErrorCount);
        output.outln("LastError: " + lastErrorSummary);
        output.outln("LastLoopError: " + lastLoopErrorSummary);
        output.outln("RejectedOrdinaryControllableFairExits: " + fairControllableExitRejectedCount);
        if (fairControllableExitRejectedCount > 0) {
            output.outln("LastRejectedOrdinaryControllableFairExit: " + lastFairControllableExitRejectedSummary);
        }
        output.outln("Debug trace file: " + LOG_FILE_PATH);
        output.outln("=============================================================");
    }

    private String summarizeStatusByMarking() {
        Map<String, Integer> counts = new HashMap<>();
        if (compostates != null) {
            for (CompostateDUC<State, Action> state : compostates.values()) {
                String key = "m" + getMarkingState(state) + ":" + state.getStatus();
                counts.put(key, counts.getOrDefault(key, 0) + 1);
            }
        }
        return counts.toString();
    }

    private int countOpenNoneStates() {
        int count = 0;
        if (compostates != null && heuristic != null) {
            for (CompostateDUC<State, Action> state : compostates.values()) {
                if (state.isStatus(Status.NONE) && state.isLive() && !heuristic.fullyExplored(state)) {
                    count++;
                }
            }
        }
        return count;
    }

    private String summarizeMarkingHistogram(Set<CompostateDUC<State, Action>> states) {
        Map<Long, Integer> counts = new HashMap<>();
        if (states != null) {
            for (CompostateDUC<State, Action> state : states) {
                Long marking = getMarkingState(state);
                counts.put(marking, counts.getOrDefault(marking, 0) + 1);
            }
        }
        return counts.toString();
    }

    private String summarizeStateForDiagnostics(CompostateDUC<State, Action> state) {
        if (state == null) {
            return "null";
        }

        boolean fullyExplored = heuristic != null && heuristic.fullyExplored(state);
        return "m=" + getMarkingState(state)
                + ", status=" + state.getStatus()
                + ", live=" + state.isLive()
                + ", expanded=" + state.wasExpanded()
                + ", fullyExplored=" + fullyExplored
                + ", vector=" + state.getStates();
    }

    private String summarizeActionsForDiagnostics(CompostateDUC<State, Action> state, int maxActions) {
        StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (HAction<State, Action> action : state.getTransitions()) {
            if (shown >= maxActions) {
                sb.append("...");
                break;
            }
            if (shown > 0) sb.append(", ");
            sb.append(action).append(action.isControllable() ? "(C)" : "(U)");
            Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
            if (children == null || children.isEmpty()) {
                sb.append("->UNEXP");
            } else {
                int goals = 0;
                int errors = 0;
                int none = 0;
                for (CompostateDUC<State, Action> child : children) {
                    if (isGoal(child)) {
                        goals++;
                    } else if (isError(child)) {
                        errors++;
                    } else {
                        none++;
                    }
                }
                sb.append("->G").append(goals).append("/E").append(errors).append("/N").append(none);
            }
            shown++;
        }
        sb.append("]");
        return sb.toString();
    }

    private void recordOtfDetailedEvaluation() {
        recordOtfMarkingAndStatusBreakdown();

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "生成した child 数", generatedChildCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "新規 compostate 生成数", newCompostateCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "既存 compostate hit 数", existingCompostateHitCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "ERROR child 到達数", safetyViolationChildCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "finishUpdate guard block 回数", finishUpdateGuardBlockedCount, "回");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "検出した loop 数", detectedLoopCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "更新前 m0 loop 例外扱い数", preUpdateLoopExceptionCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "fairness により GOAL 昇格した loop 数", fairPromotedLoopCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "ERROR と判定した loop 数", loopErrorCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "ordinary controllable fair exit 拒否回数", fairControllableExitRejectedCount, "回");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "pruning 前の候補遷移数", directorCandidateTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "pruning 後の出力遷移数", directorOutputTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "削除した controllable 遷移数", prunedControllableTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "出力時マージ対象の旧コントローラ状態数", preUpdateOutputMergedStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "出力時マージで削減した旧コントローラ状態数", preUpdateOutputMergeRemovedStates, "状態");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "finishUpdate 遷移数", finishUpdateTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "NC 接続成功数", ncConnectionSuccessCount, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "NC mapping miss 数", ncConnectionMissCount, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC lookup table 統計", "new controller connection map entries", safeSize(newControllerConnectionMap), "件");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC lookup table 統計", "safety lookup table entries", countSafetyLookupEntries(), "件");
    }

    private void recordOtfMarkingAndStatusBreakdown() {
        long countStart = System.currentTimeMillis();
        Map<Long, long[]> byMarking = new TreeMap<>();
        Map<String, Long> statusCounts = new TreeMap<>();

        if (compostates != null) {
            for (CompostateDUC<State, Action> state : compostates.values()) {
                long marking = getMarkingState(state);
                long[] counts = byMarking.computeIfAbsent(marking, k -> new long[2]);
                counts[0]++;
                counts[1] += countExploredTransitions(state);

                String statusKey = "m" + marking + ":" + state.getStatus();
                statusCounts.put(statusKey, statusCounts.getOrDefault(statusKey, 0L) + 1L);
            }
        }

        long countTime = System.currentTimeMillis() - countStart;
        boolean first = true;
        for (Map.Entry<Long, long[]> entry : byMarking.entrySet()) {
            long[] counts = entry.getValue();
            if (entry.getKey() == 0L) {
                UpdatingControllerEvaluationRecorder.recordBeginUpdateReferenceStates(counts[0]);
            }
            UpdatingControllerEvaluationRecorder.recordStateSpace(
                    "OTF-DUC markingState 別探索規模",
                    "markingState=" + entry.getKey(),
                    counts[0],
                    counts[1],
                    first ? countTime : 0);
            first = false;
        }

        for (Map.Entry<String, Long> entry : statusCounts.entrySet()) {
            UpdatingControllerEvaluationRecorder.recordCount(
                    "OTF-DUC 状態 status 内訳",
                    entry.getKey(),
                    entry.getValue(),
                    "状態");
        }
    }

    private long countExploredTransitions(CompostateDUC<State, Action> state) {
        long count = 0;
        for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> ignored : state.getExploredChildren()) {
            count++;
        }
        return count;
    }

    private int countSafetyLookupEntries() {
        int count = 0;
        if (safetyStateLookupMap != null) {
            for (Map<List<Integer>, Integer> map : safetyStateLookupMap.values()) {
                if (map != null) {
                    count += map.size();
                }
            }
        }
        return count;
    }

    private int safeSize(Map<?, ?> map) {
        return map == null ? 0 : map.size();
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
                    if (isGoal(child) && !goalsToUpdate.contains(child)) {
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

        propagateGoalDistanceUpdateNanos += System.nanoTime() - startTime;
    }

    private LTS<Long, Action> buildDirectorDUC() {
        // 1. 結果を格納する LTS の初期化
        // 状態 ID 0 を初期状態として設定（後に更新コントローラの初期 ID で上書き）
        LTSImpl<Long, Action> result = new LTSImpl<>(0L);

        // 探索用 alphabet を丸ごと登録すると、遷移図に出ない "_old" action まで
        // アルファベット拡張として出力されるため、出力対象 action だけを登録する。
        // result.addActions(alphabet.getActions());

        // 1. 全 action の中から "_old" を含まないものだけを登録する。
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

        //評価実験用
        long transferNCStart = System.currentTimeMillis();

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

        transferNCTime = System.currentTimeMillis() - transferNCStart;

        // ---------------------------------------------------------
        // ステップ 2: pruning 後の更新コントローラグラフを一度収集する
        // ---------------------------------------------------------
        Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges = new LinkedHashMap<>();
        List<CompostateDUC<State, Action>> reachableOrder = new ArrayList<>();
        Set<CompostateDUC<State, Action>> reached = new HashSet<>();
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();
        reached.add(initial);
        reachableOrder.add(initial);
        queue.add(initial);

        long directorTraversalStart = System.nanoTime();
        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.remove();

            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition : current.getExploredChildren()) {
                HAction<State, Action> hAction = transition.getFirst();
                CompostateDUC<State, Action> child = transition.getSecond();
                directorCandidateTransitions++;

                long pruningStart = System.nanoTime();
                boolean toAdd = shouldAddDirectorTransition(current, hAction, child);
                outputPruningDecisionNanos += System.nanoTime() - pruningStart;
                outputPruningDecisionCalls++;

                if (toAdd) {
                    // finishUpdate の場合は NC への接続を試みる
                    if (hAction.toString().equals(UpdateConstants.FINISH_UPDATE) && getMarkingState(child) == 9) {
                        finishUpdateTransitions++;

                        //評価実験用
                        long stitchingNCStart = System.currentTimeMillis();

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
                            ncConnectionSuccessCount++;
                            if(debugLogEnabled) log("  [Stitch] Connecting " + current.getStates() + " --(finishUpdate)--> NC State " + ncStateId);
                            directorEdges.computeIfAbsent(current, k -> new ArrayList<>())
                                    .add(new DirectorEdge(toOutputAction(hAction), child, ncStateId));
                        } else {
                            ncConnectionMissCount++;
                            // 制約5に基づき、エラー時は詳細なベクトルを出力
                            System.err.println("!!! [Stitch-Error] No NC state mapping found for signature: " + signature);
                            System.err.println("    Target child vector: " + child.getStates());
                            throw new IllegalStateException("Missing NC mapping for reached state during stitching.");
                        }

                        //評価実験用
                        stitchingNCTime += (System.currentTimeMillis() - stitchingNCStart);
                    } else {
                        // 通常の遷移
                        directorEdges.computeIfAbsent(current, k -> new ArrayList<>())
                                .add(new DirectorEdge(toOutputAction(hAction), child, null));
                        if (reached.add(child)) {
                            reachableOrder.add(child);
                            queue.add(child);
                        }
                    }
                } else if (hAction.isControllable()) {
                    prunedControllableTransitions++;
                }
            }
        }

        // ---------------------------------------------------------
        // ステップ 3: 出力時のみ、旧コントローラ上の同値状態をマージする
        // ---------------------------------------------------------
        Map<CompostateDUC<State, Action>, Integer> preUpdateClasses =
                computePreUpdateOutputMergeClasses(reachableOrder, directorEdges);

        Map<CompostateDUC<State, Action>, Long> ids = new HashMap<>();
        Map<Integer, Long> preUpdateClassIds = new HashMap<>();
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (isPreUpdateOutputState(state)) {
                Integer classId = preUpdateClasses.get(state);
                Long id = preUpdateClassIds.get(classId);
                if (id == null) {
                    id = nextId++;
                    preUpdateClassIds.put(classId, id);
                    result.addState(id);
                }
                ids.put(state, id);
            } else {
                Long id = nextId++;
                ids.put(state, id);
                result.addState(id);
            }
        }
        result.setInitialState(ids.get(initial));

        for (CompostateDUC<State, Action> source : reachableOrder) {
            Long sourceId = ids.get(source);
            List<DirectorEdge> edges = directorEdges.get(source);
            if (edges == null) {
                continue;
            }
            for (DirectorEdge edge : edges) {
                Long targetId = edge.isNewControllerConnection()
                        ? edge.ncTargetId
                        : ids.get(edge.child);
                if (targetId == null) {
                    throw new IllegalStateException("Missing output state id for director edge target.");
                }
                if (result.addTransition(sourceId, edge.outputAction, targetId)) {
                    directorOutputTransitions++;
                }
            }
        }

        directorTraversalNanos += System.nanoTime() - directorTraversalStart;
        statistics.setControllerUsedStates(result.getStates().size());
        return result;
    }

    private boolean isPreUpdateOutputState(CompostateDUC<State, Action> state) {
        return getMarkingState(state) == 0;
    }

    @SuppressWarnings("unchecked")
    private Action toOutputAction(HAction<State, Action> hAction) {
        return (Action) hAction.toString().replace("_old", "");
    }

    /**
     * 探索では new safety fluent の履歴を保持するが、出力上で同じ旧コントローラ
     * 状態かつ同じ遷移構造を持つ m=0 状態は同一状態としてまとめる。
     *
     * 初期分割は旧コントローラ成分で行い、beginUpdate と旧コントローラ遷移を含む
     * 出力遷移の行き先が同じ同値クラスになるまで partition refinement する。
     */
    private Map<CompostateDUC<State, Action>, Integer> computePreUpdateOutputMergeClasses(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges) {

        List<CompostateDUC<State, Action>> preUpdateStates = new ArrayList<>();
        Map<State, Integer> oldControllerClassIds = new HashMap<>();
        Map<CompostateDUC<State, Action>, Integer> classOf = new HashMap<>();
        int nextClassId = 0;

        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }
            preUpdateStates.add(state);
            State oldControllerState = state.getStates().get(idxOC);
            Integer classId = oldControllerClassIds.get(oldControllerState);
            if (classId == null) {
                classId = nextClassId++;
                oldControllerClassIds.put(oldControllerState, classId);
            }
            classOf.put(state, classId);
        }

        if (preUpdateStates.isEmpty()) {
            preUpdateOutputMergedStates = 0;
            preUpdateOutputMergeRemovedStates = 0;
            return classOf;
        }

        Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds = new HashMap<>();
        int nextNonPreUpdateId = 0;
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                nonPreUpdateIds.put(state, nextNonPreUpdateId++);
            }
        }

        boolean changed;
        do {
            changed = false;
            Map<Integer, List<CompostateDUC<State, Action>>> statesByClass = new LinkedHashMap<>();
            for (CompostateDUC<State, Action> state : preUpdateStates) {
                statesByClass.computeIfAbsent(classOf.get(state), k -> new ArrayList<>()).add(state);
            }

            Map<CompostateDUC<State, Action>, Integer> refinedClassOf = new HashMap<>();
            int refinedClassId = 0;

            for (List<CompostateDUC<State, Action>> candidates : statesByClass.values()) {
                Map<List<String>, Integer> signatureToClass = new LinkedHashMap<>();
                for (CompostateDUC<State, Action> state : candidates) {
                    List<String> signature = buildPreUpdateOutputSignature(
                            state, directorEdges, classOf, nonPreUpdateIds);
                    Integer classId = signatureToClass.get(signature);
                    if (classId == null) {
                        classId = refinedClassId++;
                        signatureToClass.put(signature, classId);
                    }
                    refinedClassOf.put(state, classId);
                }
                if (signatureToClass.size() > 1) {
                    changed = true;
                }
            }

            classOf = refinedClassOf;
            nextClassId = refinedClassId;
        } while (changed);

        preUpdateOutputMergedStates = preUpdateStates.size();
        preUpdateOutputMergeRemovedStates = preUpdateStates.size() - nextClassId;
        if (debugLogEnabled && preUpdateOutputMergeRemovedStates > 0) {
            log("  [Director-Merge] merged pre-update output states: raw="
                    + preUpdateOutputMergedStates
                    + ", classes=" + nextClassId
                    + ", removed=" + preUpdateOutputMergeRemovedStates);
        }
        return classOf;
    }

    private List<String> buildPreUpdateOutputSignature(
            CompostateDUC<State, Action> state,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds) {

        List<String> signature = new ArrayList<>();
        List<DirectorEdge> edges = directorEdges.get(state);
        if (edges != null) {
            for (DirectorEdge edge : edges) {
                signature.add(edge.outputAction.toString() + "->" + outputMergeTargetToken(
                        edge, preUpdateClasses, nonPreUpdateIds));
            }
        }
        Collections.sort(signature);
        return signature;
    }

    private String outputMergeTargetToken(
            DirectorEdge edge,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds) {

        if (edge.isNewControllerConnection()) {
            return "NC:" + edge.ncTargetId;
        }
        if (isPreUpdateOutputState(edge.child)) {
            return "PRE:" + preUpdateClasses.get(edge.child);
        }
        return "UPD:" + nonPreUpdateIds.get(edge.child);
    }

    private class DirectorEdge {
        private final Action outputAction;
        private final CompostateDUC<State, Action> child;
        private final Long ncTargetId;

        private DirectorEdge(
                Action outputAction,
                CompostateDUC<State, Action> child,
                Long ncTargetId) {
            this.outputAction = outputAction;
            this.child = child;
            this.ncTargetId = ncTargetId;
        }

        private boolean isNewControllerConnection() {
            return ncTargetId != null;
        }
    }

    /**
     * OTF-DUC の 2 フェーズ構造に従って出力 controller の遷移を選ぶ。
     *
     * - uncontrollable 遷移は合法性のため常に残す。
     * - 更新前の beginUpdate は、勝ち更新パスを持つ旧コントローラ状態から残す。
     * - 更新中の通常 controllable 遷移は、controllable livelock を出力しないように
     *   選択済みの進行 action だけに pruning する。
     * - 旧コントローラ部分と移設済み新コントローラ部分は、事前合成済み controller
     *   として扱い、不当に pruning しない。
     */
    private boolean shouldAddDirectorTransition(
            CompostateDUC<State, Action> current,
            HAction<State, Action> hAction,
            CompostateDUC<State, Action> child) {

        if (!hAction.isControllable()) {
            logDirectorPruningDecision(current, hAction, child, true,
                    "uncontrollable action is always preserved");
            return true;
        }

        // anytime hotswap 要件: 勝ち更新パスを持つ旧コントローラ状態からは
        // beginUpdate を出力に残す。
        if (getMarkingState(current) == 0
                && hAction.toString().equals(UpdateConstants.BEGIN_UPDATE)
                && isGoal(child)) {
            logDirectorPruningDecision(current, hAction, child, true,
                    "beginUpdate from a winning pre-update state");
            return true;
        }

        HAction<State, Action> selected = getSelectedControllableAction(current);
        boolean toAdd = selected != null && selected.equals(hAction);
        if (toAdd) {
            logDirectorPruningDecision(current, hAction, child, true,
                    "selected controllable action");
        } else {
            String selectedName = selected == null ? "none" : selected.toString();
            logDirectorPruningDecision(current, hAction, child, false,
                    "not selected; selected controllable=" + selectedName
                            + ", directorAction=" + describeAction(current.getDirectorActionToGoal())
                            + ", actionToGoal=" + describeAction(current.actionToGoal)
                            + ", bestControllable=" + describeBestControllable(current));
        }
        return toAdd;
    }

    private void logDirectorPruningDecision(
            CompostateDUC<State, Action> current,
            HAction<State, Action> hAction,
            CompostateDUC<State, Action> child,
            boolean kept,
            String reason) {
        if (!debugLogEnabled) {
            return;
        }
        log("  [Director-Pruning] " + (kept ? "KEEP " : "PRUNE ")
                + hAction
                + " from " + summarizeStateForDiagnostics(current)
                + " to " + summarizeStateForDiagnostics(child)
                + " :: " + reason);
    }

    private String describeAction(HAction<State, Action> action) {
        if (action == null) {
            return "none";
        }
        return action.toString() + (action.isControllable() ? "(C)" : "(U)");
    }

    private String describeBestControllable(CompostateDUC<State, Action> state) {
        Pair<Integer, CompostateDUC<State, Action>> best = state.getBestControllable();
        if (best == null || best.getFirst() == null || best.getFirst() < 0) {
            return "none";
        }
        CompostateDUC<State, Action> bestChild = best.getSecond();
        if (bestChild == null) {
            return "distance=" + best.getFirst() + ", child=none";
        }
        return "distance=" + best.getFirst()
                + ", child=" + summarizeStateForDiagnostics(bestChild);
    }

    private HAction<State, Action> getSelectedControllableAction(CompostateDUC<State, Action> current) {
        HAction<State, Action> directorAction = current.getDirectorActionToGoal();
        if (directorAction != null && directorAction.isControllable()) {
            return directorAction;
        }

        Pair<Integer, CompostateDUC<State, Action>> best = current.getBestControllable();

        if (best != null && best.getFirst() != null && best.getFirst() >= 0) {
            CompostateDUC<State, Action> bestChild = best.getSecond();

            // bestChild が null の場合、最短の証明済み経路は uncontrollable 経由で
            // 進むことを意味する。directorAction が controllable でなければ、
            // 出力として追加すべき controllable はない。
            if (bestChild == null) {
                // fall through
            } else {
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition : current.getExploredChildren()) {
                    HAction<State, Action> action = transition.getFirst();
                    if (action.isControllable() && transition.getSecond() == bestChild) {
                        return action;
                    }
                }
            }
        }

        return null;
    }

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
        if (!isError(state)) {
            errorMarkCount++;
        }
        lastErrorSummary = summarizeStateForDiagnostics(state);

        log("[ERROR DETECTED] State marked as ERROR: " + state.getStates());
        state.setStatus(Status.ERROR);

        heuristic.notifyStateSetErrorOrGoal(state);
    }

    /**
     * 直積を構成する各コンポーネントで、指定 action がなぜ有効/無効かを調べる
     * デバッグ用ヘルパ。
     */
    private void debugCheckActionAvailability(CompostateDUC<State, Action> state, String actionName) {
        log("  [DEBUG] Diagnosing action: " + actionName);
        long markingState = getMarkingState(state);

        for (int i = 0; i < ltssSize; ++i) {
            LTS<State, Action> lts = ltss.get(i);
            State curr = state.getStates().get(i);

            // trace 対象外のコンポーネントは正規化されており、元の LTS 上に
            // 対応する具象状態を持たない。
            if (curr instanceof Long && (Long) curr == -2L) {
                log(String.format("    LTS %d : IGNORED (Normalized State -2L, Trace=OFF) (State=%s)", i, curr));
                continue;
            }
            
            boolean enforce = isEnforce(i, markingState);
            boolean trace = isTrace(i, markingState);

            boolean hasTransition = false;
            boolean hasActionInAlphabet = false;

            for (Action a : lts.getActions()) {
                if (a.toString().equals(actionName)) {
                    hasActionInAlphabet = true;
                    break;
                }
            }

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
                    status = "IGNORED (Trace=OFF) (Transition exists but Trace=OFF)";
                } else {
                    status = "OK (Transition found)";
                }
            } else if (hasActionInAlphabet) {
                if (!trace) {
                    status = "!!! BLOCKED (Trace=OFF) !!! (In Alphabet, but Trace=FALSE blocks everything)";
                } else if (enforce) {
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

    private void recordOtfDcsTimingEvaluation() {
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "synthesizeDUC 実行時間", synthesizeDUCTime);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "DCS (OTF-DUC)",
                "DCS で探索した状態数と遷移数の最大値",
                otfPeakStates,
                otfPeakTrans,
                countTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "DCS で探索した時間", searchTime);
        UpdatingControllerEvaluationRecorder.recordCount(
                "DCS (OTF-DUC)",
                "expandDUC 呼び出し回数",
                totalLtsExpansions,
                "回");

        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 探索時間内訳", "ヒューリスティックによる次アクション選択時間", heuristicSelectionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 探索時間内訳", "ヒューリスティック選択の平均時間", heuristicSelectionNanos, heuristicSelectionCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 探索時間内訳", "フロンティア再評価時間", heuristicRecomputeNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 探索時間内訳", "フロンティア再評価回数", heuristicRecomputeRuns, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 探索時間内訳", "再評価した状態数", heuristicRecomputedStates, "状態");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 探索時間内訳", "フロンティア操作時間", heuristicFrontierNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 探索時間内訳", "ヒューリスティック評価(eval)時間", heuristicEvaluationNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 探索時間内訳", "ヒューリスティック評価(eval)平均時間", heuristicEvaluationNanos, heuristicEvaluationCalls);

        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "expandDUC 全体時間", stateExpansionNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "次状態候補生成時間", successorGenerationNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "各 LTS の同期候補収集時間", componentSyncNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "非決定分岐の直積生成時間", cartesianProductNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "New Safety 同期 lookup 時間", safetySyncNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "状態正規化時間", stateCanonicalizationNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "既存状態 lookup / 新規登録判定時間", stateLookupNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 展開時間内訳", "状態 lookup 平均時間", stateLookupNanos, stateLookupCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "新規状態初期化時間", newStateRegistrationNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "Safety / requirement 違反判定時間", enforceSafetyCheckNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "finishUpdate guard 判定時間", finishUpdateGuardNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開時間内訳", "finishUpdate guard 判定回数", finishUpdateGuardChecks, "回");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "子状態と探索木の接続時間", childRegistrationNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 展開時間内訳", "explore 全体時間", exploreNanos);

        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC loop / fairness 時間内訳", "loop 検出時間", loopDetectionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC loop / fairness 時間内訳", "loop 検出平均時間", loopDetectionNanos, loopDetectionCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC loop / fairness 時間内訳", "fairness / loop 判定時間", fairnessAnalysisNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC loop / fairness 時間内訳", "fairness / loop 判定平均時間", fairnessAnalysisNanos, fairnessAnalysisCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC loop / fairness 時間内訳", "fair loop GOAL 昇格試行時間", fairPromotionNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC loop / fairness 時間内訳", "fairness 固定点で処理した候補状態数", totalFairnessCandidatesProcessed, "状態");

        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 伝播時間内訳", "GOAL 伝播時間", propagateGoalNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 伝播時間内訳", "GOAL 伝播平均時間", propagateGoalNanos, propagateGoalCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 伝播時間内訳", "GOAL 伝播 Phase1 時間", propagateGoalPhase1Nanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 伝播時間内訳", "GOAL 伝播 fairness Phase2 時間", propagateGoalPhase2Nanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 伝播時間内訳", "GOAL 距離更新時間", propagateGoalDistanceUpdateNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 伝播時間内訳", "ERROR 伝播時間", propagateErrorNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 伝播時間内訳", "ERROR 伝播平均時間", propagateErrorNanos, propagateErrorCalls);

        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "buildDirectorDUC 実行時間", buildDirectorDUCTime);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "出力遷移 pruning 判定時間", outputPruningDecisionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 出力構築時間内訳", "出力遷移 pruning 判定平均時間", outputPruningDecisionNanos, outputPruningDecisionCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "director グラフ走査・遷移構築時間", directorTraversalNanos);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 移設時間", transferNCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 接続時間", stitchingNCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 移設時間 + NC 接続時間", transferNCTime + stitchingNCTime);
    }

    /**
     * [最適化] 高速Map検索のためのキー。
     * 1. ArrayList のイテレータを介したハッシュ計算を避け、Arrays.hashCode(long[]) を使用。
     * 2. 検索時にインスタンスを new しないための wrap メソッドを提供。
     */
    private static class StateKey {
        private long[] values;
        private int hash;

        public StateKey() {} // 検索用(reusableKey)の空コンストラクタ

        /**
         * [最適化] 既存のバッファを一時的に借用してハッシュを計算する。
         * 既知の状態を Map から探す際、このメソッドによりオブジェクト生成(Allocation)をゼロにする。
         */
        public void wrap(long[] buffer) {
            this.values = buffer;
            this.hash = Arrays.hashCode(buffer);
        }

        /**
         * [保存用] Map に新しく登録する際、配列をコピーして永続化する。
         */
        public StateKey(long[] buffer) {
            this.values = Arrays.copyOf(buffer, buffer.length);
            this.hash = Arrays.hashCode(this.values);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StateKey)) return false;
            // [最適化] Longオブジェクトの equals ではなく、CPUネイティブな数値配列比較を実行
            return Arrays.equals(this.values, ((StateKey) o).values);
        }
    }

    // 評価実験用: OTF-DUC が実際に展開した遷移数を数える。
    private int countOTFTransitions() {
        int count = 0;
        for (CompostateDUC<State, Action> state : compostates.values()) {
            MTSTools.ac.ic.doc.commons.relations.BinaryRelation<HAction<State, Action>, CompostateDUC<State, Action>> children = state.getExploredChildren();
            
            if (children != null) {
                // BinaryRelation は Pair<HAction, CompostateDUC> のコレクション
                for (MTSTools.ac.ic.doc.commons.relations.Pair<HAction<State, Action>, CompostateDUC<State, Action>> edge : children) {
                    count++;
                }
            }
        }
        return count;
    }
}
