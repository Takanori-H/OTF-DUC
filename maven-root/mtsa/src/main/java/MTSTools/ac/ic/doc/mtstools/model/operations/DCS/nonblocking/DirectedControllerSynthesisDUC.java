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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import ltsa.updatingControllers.DUCHeartbeat;
import ltsa.updatingControllers.EvaluationProfiler;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.UpdatePhaseEvaluator;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;
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
    private Map<ComponentStepCacheKey, ComponentStepResult<State>> componentStepCache = new HashMap<>();
    private Map<String, Boolean> finishUpdateGuardCache = new HashMap<>();
    private Map<ActionChildrenGoalCacheKey, AllChildrenGoalCacheEntry> allChildrenGoalCache = new HashMap<>();

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
    private boolean profileLogEnabled = Boolean.getBoolean("otfduc.profile");
    private boolean finishUpdateFairnessEnabled =
            Boolean.parseBoolean(System.getProperty("otfduc.fairness", "true"))
                    && !Boolean.getBoolean("otfduc.disableFairness");
    private boolean mergeProofLogEnabled = Boolean.parseBoolean(System.getProperty("otfduc.debug.mergeProof", "true"));
    private boolean beliefRepairEnabled = Boolean.parseBoolean(System.getProperty("otfduc.belief.repair", "false"));
    private boolean nondeterministicActionBeliefRepairEnabled =
            Boolean.parseBoolean(System.getProperty("otfduc.nondet.belief.repair", "true"));
    private boolean preUpdateSimpleMergeEnabled =
            Boolean.parseBoolean(System.getProperty("otfduc.simple.merge", "false"));
    private int beliefRepairAbsoluteMaxStates = Integer.getInteger("otfduc.belief.maxStates", 5000);
    private int beliefRepairMinBeliefNodes = Integer.getInteger("otfduc.belief.maxNodes.min", 64);
    private int beliefRepairBeliefNodesPerNewControllerState =
            Integer.getInteger("otfduc.belief.maxNodes.perNewControllerState", 2);
    private int beliefRepairBeliefNodesPerRawPreState =
            Integer.getInteger("otfduc.belief.maxNodes.perRawPreState", 8);
    private int beliefRepairBeliefNodesPerReachableMapValuation =
            Integer.getInteger("otfduc.belief.maxNodes.perReachableMapValuation", 4);
    private int beliefRepairMinAdditionalConcreteStates =
            Integer.getInteger("otfduc.belief.maxAdditionalConcrete.min", 128);
    private int beliefRepairAdditionalConcretePerNewControllerState =
            Integer.getInteger("otfduc.belief.maxAdditionalConcrete.perNewControllerState", 4);
    private int beliefRepairAdditionalConcretePerRawPreState =
            Integer.getInteger("otfduc.belief.maxAdditionalConcrete.perRawPreState", 16);
    private int beliefRepairMinAdditionalTransitions =
            Integer.getInteger("otfduc.belief.maxAdditionalTransitions.min", 256);
    private int beliefRepairAdditionalTransitionsPerNewControllerState =
            Integer.getInteger("otfduc.belief.maxAdditionalTransitions.perNewControllerState", 8);
    private int beliefRepairAdditionalTransitionsPerRawPreState =
            Integer.getInteger("otfduc.belief.maxAdditionalTransitions.perRawPreState", 32);
    private long beliefRepairMaxTimeMs = Long.getLong("otfduc.belief.maxTimeMs", 5000L);
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
    private long preUpdateOutputClassStates = 0;
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
    private long strategy1NormalOtfSimpleMergeStartMillis = -1;
    private long strategy1NormalOtfSimpleMergeTime = -1;
    private long strategy1NormalOtfSimpleMergeBeforeMemory = -1;
    private long strategy1NormalOtfSimpleMergePeakMemory = -1;
    private long strategy1NormalOtfSimpleMergeMemoryIncrease = -1;
    private long strategy1RepairStartMillis = -1;
    private long strategy1RepairTime = -1;
    private long strategy1RepairBeforeMemory = -1;
    private long strategy1RepairPeakMemory = -1;
    private long strategy1RepairMemoryIncrease = -1;

    private long heuristicSelectionNanos = 0;
    private long heuristicRecomputeNanos = 0;
    private long heuristicFrontierNanos = 0;
    private long heuristicEvaluationNanos = 0;
    private long stateExpansionNanos = 0;
    private long successorGenerationNanos = 0;
    private long componentSyncNanos = 0;
    private long cartesianProductNanos = 0;
    private long safetySyncNanos = 0;
    private long buildCompostateNanos = 0;
    private long stateCanonicalizationNanos = 0;
    private long stateLookupNanos = 0;
    private long newStateRegistrationNanos = 0;
    private long enforceSafetyCheckNanos = 0;
    private long finishUpdateGuardNanos = 0;
    private long childRegistrationNanos = 0;
    private long exploreNanos = 0;
    private long isErrorCheckNanos = 0;
    private long setErrorNanos = 0;
    private long loopDetectionNanos = 0;
    private long fairnessAnalysisNanos = 0;
    private long fairPromotionNanos = 0;
    private long propagateGoalNanos = 0;
    private long propagateErrorNanos = 0;
    private long propagateGoalPhase1Nanos = 0;
    private long propagateGoalPhase2Nanos = 0;
    private long propagateGoalDistanceUpdateNanos = 0;
    private long phase2CandidateBuildNanos = 0;
    private long phase2USafetyFilterNanos = 0;
    private long phase2DistanceSeedNanos = 0;
    private long phase2DistancePropagationNanos = 0;
    private long phase2DistancePruneNanos = 0;
    private long phase2ExitActionSelectionNanos = 0;
    private long phase2GoalApplyNanos = 0;
    private long phase2PostPromotionPropagationNanos = 0;
    private long fairReachabilityActionCheckNanos = 0;
    private long fairReachabilityRejectedSummaryNanos = 0;
    private long hasUncontrollableSuccessorInNanos = 0;
    private long outputPruningDecisionNanos = 0;
    private long directorTraversalNanos = 0;
    private long directorActionRegistrationNanos = 0;
    private long directorNcStateTransferNanos = 0;
    private long directorNcTransitionTransferNanos = 0;
    private long directorEdgeCollectionNanos = 0;
    private long directorNcConnectionNanos = 0;
    private long directorNcConnectionDebugPrepNanos = 0;
    private long directorNcConnectionSignatureNanos = 0;
    private long directorPreUpdateMergeNanos = 0;
    private long directorBeliefRepairNanos = 0;
    private long directorNondetBeliefRepairNanos = 0;
    private long directorIdAssignmentNanos = 0;
    private long directorTransitionEmissionNanos = 0;

    private long heuristicSelectionCalls = 0;
    private long heuristicRecomputeRuns = 0;
    private long heuristicRecomputedStates = 0;
    private long heuristicEvaluationCalls = 0;
    private long successorGenerationCalls = 0;
    private long componentSyncCalls = 0;
    private long cartesianProductCalls = 0;
    private long safetySyncCalls = 0;
    private long buildCompostateCalls = 0;
    private long stateLookupCalls = 0;
    private long enforceSafetyCheckCalls = 0;
    private long finishUpdateGuardChecks = 0;
    private long childRegistrationCalls = 0;
    private long exploreCalls = 0;
    private long isErrorChecks = 0;
    private long setErrorCalls = 0;
    private long loopDetectionCalls = 0;
    private long fairnessAnalysisCalls = 0;
    private long propagateGoalCalls = 0;
    private long propagateErrorCalls = 0;
    private long outputPruningDecisionCalls = 0;
    private long directorActionRegistrationCount = 0;
    private long directorNcStatesTransferred = 0;
    private long directorNcTransitionsTransferred = 0;
    private long directorReachableStates = 0;
    private long directorEdgesCollected = 0;
    private long directorNcConnectionAttempts = 0;
    private long directorNcConnectionSignatureCalls = 0;
    private long directorOutputStatesAssigned = 0;
    private long directorTransitionEmissionAttempts = 0;
    private long beliefRepairCandidateGroups = 0;
    private long beliefRepairSuccessGroups = 0;
    private long beliefRepairFallbackGroups = 0;
    private long beliefRepairGeneratedNodes = 0;
    private long beliefLazyExpansionRounds = 0;
    private long beliefLazyExpansionAttempts = 0;
    private long beliefLazyExpansionExpandedActions = 0;
    private long beliefLazyExpansionAddedEdges = 0;
    private long beliefUnsafeControllableDiscardCount = 0;
    private long beliefUnexploredUncontrollableWarningPlans = 0;
    private long beliefUnexploredUncontrollableWarningNodes = 0;
    private long beliefUnexploredUncontrollableWarningActions = 0;
    private long beliefRepairResourceLimitFallbacks = 0;
    private long beliefRepairResourceLimitBeliefNodeFallbacks = 0;
    private long beliefRepairResourceLimitAdditionalConcreteFallbacks = 0;
    private long beliefRepairResourceLimitAdditionalTransitionFallbacks = 0;
    private long beliefRepairResourceLimitTimeFallbacks = 0;
    private long beliefRepairMaxObservedBeliefNodes = 0;
    private long beliefRepairMaxObservedAdditionalConcreteStates = 0;
    private long beliefRepairMaxObservedAdditionalTransitions = 0;
    private long nondetBeliefRepairCandidateGroups = 0;
    private long nondetBeliefRepairSuccessGroups = 0;
    private long nondetBeliefRepairFallbackGroups = 0;
    private long nondetBeliefRepairReplacedTransitions = 0;
    private long totalFairnessCandidatesProcessed = 0;
    private long phase2OuterIterations = 0;
    private long phase2InnerIterations = 0;
    private long phase2CandidateBuildCalls = 0;
    private long phase2USafetyFilterCalls = 0;
    private long phase2DistanceSeedCalls = 0;
    private long phase2DistancePropagationCalls = 0;
    private long phase2DistancePruneCalls = 0;
    private long phase2ExitActionSelectionCalls = 0;
    private long phase2GoalApplyCalls = 0;
    private long phase2PostPromotionPropagationCalls = 0;
    private long phase2CandidatesBuiltTotal = 0;
    private long phase2CandidatesAfterUSafetyTotal = 0;
    private long phase2CandidatesAfterDistancePruneTotal = 0;
    private long phase2MaxCandidatesBuilt = 0;
    private long phase2DistanceSeededStates = 0;
    private long phase2DistancePropagatedStates = 0;
    private long fairReachabilityActionCheckCalls = 0;
    private long fairReachabilityRejectedSummaryCalls = 0;
    private long hasUncontrollableSuccessorInCalls = 0;
    private long componentStepCacheHits = 0;
    private long componentStepCacheMisses = 0;
    private long componentStepCacheInvalidHits = 0;
    private long finishUpdateGuardCacheHits = 0;
    private long finishUpdateGuardCacheMisses = 0;
    private long allChildrenGoalCacheHits = 0;
    private long allChildrenGoalCacheMisses = 0;
    private long allChildrenGoalCacheInvalidations = 0;

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

    private static final class ComponentStepCacheKey {
        private final int ltsIndex;
        private final long markingState;
        private final Object componentState;
        private final Object action;
        private final boolean oldAction;
        private final int hash;

        private ComponentStepCacheKey(int ltsIndex, long markingState, Object componentState, Object action,
                boolean oldAction) {
            this.ltsIndex = ltsIndex;
            this.markingState = markingState;
            this.componentState = componentState;
            this.action = action;
            this.oldAction = oldAction;

            int h = Integer.hashCode(ltsIndex);
            h = 31 * h + Long.hashCode(markingState);
            h = 31 * h + Objects.hashCode(componentState);
            h = 31 * h + Objects.hashCode(action);
            h = 31 * h + Boolean.hashCode(oldAction);
            this.hash = h;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ComponentStepCacheKey)) return false;
            ComponentStepCacheKey other = (ComponentStepCacheKey) obj;
            return ltsIndex == other.ltsIndex
                    && markingState == other.markingState
                    && oldAction == other.oldAction
                    && Objects.equals(componentState, other.componentState)
                    && Objects.equals(action, other.action);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class ComponentStepResult<S> {
        private final boolean invalid;
        private final Set<S> successors;

        private ComponentStepResult(boolean invalid, Set<S> successors) {
            this.invalid = invalid;
            this.successors = successors;
        }

        private static <S> ComponentStepResult<S> invalid() {
            return new ComponentStepResult<>(true, null);
        }

        private static <S> ComponentStepResult<S> successors(Set<S> successors) {
            return new ComponentStepResult<>(false, successors);
        }
    }

    private static final class ActionChildrenGoalCacheKey {
        private final CompostateDUC<?, ?> state;
        private final Object action;
        private final int hash;

        private ActionChildrenGoalCacheKey(CompostateDUC<?, ?> state, Object action) {
            this.state = state;
            this.action = action;
            this.hash = 31 * System.identityHashCode(state) + Objects.hashCode(action);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ActionChildrenGoalCacheKey)) return false;
            ActionChildrenGoalCacheKey other = (ActionChildrenGoalCacheKey) obj;
            return state == other.state && Objects.equals(action, other.action);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class AllChildrenGoalCacheEntry {
        private final int childCount;
        private final boolean allGoals;

        private AllChildrenGoalCacheEntry(int childCount, boolean allGoals) {
            this.childCount = childCount;
            this.allGoals = allGoals;
        }
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

    private void publishOtfHeartbeat() {
        DUCHeartbeat.setCounter("expandedStates", statistics.getExpandedStates());
        DUCHeartbeat.setCounter("expandedTransitions", statistics.getExpandedTransitions());
        DUCHeartbeat.setCounter("knownStates", compostates == null ? 0 : compostates.size());
        DUCHeartbeat.setCounter("frontierStates", heuristic == null ? 0 : heuristic.frontierSize());
        DUCHeartbeat.setCounter("newStates", newCompostateCount);
        DUCHeartbeat.setCounter("generatedChildren", generatedChildCount);
        DUCHeartbeat.setCounter("errorStates", errorMarkCount);
        DUCHeartbeat.setCounter("loopErrors", loopErrorCount);
        DUCHeartbeat.setCounter("detectedLoops", detectedLoopCount);
        DUCHeartbeat.setCounter("fairPromotedLoops", fairPromotedLoopCount);
        DUCHeartbeat.setCounter("hotSwapOutBlocked", finishUpdateGuardBlockedCount);
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
        return synthesizeDUC(
                ltss,
                controllable,
                mappingStart, mappingEnd,
                oldSafeStart, oldSafeEnd,
                newSafeStart, newSafeEnd,
                transReqStart, transReqEnd,
                synthesisStart, synthesisEnd,
                mappingMapEnvToNewEnv,
                newControllerConnectionMap,
                newController,
                safetyComponentIndicesMap,
                safetyStateLookupMap,
                null,
                null,
                null,
                output);
    }

    protected LTS<Long, Action> synthesizeDUC(
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
            UpdateProtocolSpec updateProtocolSpec,
            Map<Integer, String> oldSafetyStopActionsByIndex,
            Map<Integer, String> newSafetyStartActionsByIndex,
            LTSOutput output) {
        
        long synthesizeDUCStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "DCS (OTF-DUC)",
                "synthesizeDUC 実行時間");

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
        configureUpdateProtocol(
                updateProtocolSpec,
                oldSafetyStopActionsByIndex,
                newSafetyStartActionsByIndex);

        this.mappingMapEnvToNewEnv = mappingMapEnvToNewEnv;
        this.newControllerConnectionMap = newControllerConnectionMap;
        @SuppressWarnings("unchecked")
        LTS<Long, String> nc = (LTS<Long, String>) newController;
        this.newController = nc;
        this.output = output;
        UpdatingControllerEvaluationRecorder.setOtfExecutionMode(describeOtfExecutionMode());

        if (debugLogEnabled || profileLogEnabled) {
            try {
                logWriter = new PrintWriter(new FileWriter(LOG_FILE_PATH));
                if (debugLogEnabled) {
                    log("=== Starting OTF-DUC Synthesis ===");
                    log(String.format("Config: MarkingLTS[0], OldController[1], MapEnv[%d-%d], OldSafe[%d-%d], NewSafe[%d-%d], TransReq[%d-%d], Synthesis[%d-%d], Fairness[%s], MergeProof[%s], NondetActionBeliefRepair[%s], SimpleMerge[%s]",
                            mappingStart, mappingEnd, oldSafeStart, oldSafeEnd, newSafeStart, newSafeEnd, transReqStart,
                            transReqEnd, synthesisStart, synthesisEnd, finishUpdateFairnessEnabled, mergeProofLogEnabled,
                            nondeterministicActionBeliefRepairEnabled, preUpdateSimpleMergeEnabled));
                }
                if (profileLogEnabled) {
                    profileLog("=== Starting OTF-DUC Profiling ===");
                    profileLog(String.format("[Profile-Config] debug=%s, profile=%s, fairness=%s, file=%s",
                            debugLogEnabled, profileLogEnabled, finishUpdateFairnessEnabled, LOG_FILE_PATH));
                    profileLog(String.format("[Profile-Config] MarkingLTS[0], OldController[1], MapEnv[%d-%d], OldSafe[%d-%d], NewSafe[%d-%d], TransReq[%d-%d], Synthesis[%d-%d]",
                            mappingStart, mappingEnd, oldSafeStart, oldSafeEnd, newSafeStart, newSafeEnd, transReqStart,
                            transReqEnd, synthesisStart, synthesisEnd));
                }
            } catch (IOException e) {
                System.err.println("Failed to open debug log file: " + e.getMessage());
                e.printStackTrace();
            }
        }
        onDebugLogOpened();

        try {
            setupSynthesisDUC(ltss, controllable);
            this.heuristic = new DUCExplorationHeuristic<>(this, mappingStart, mappingEnd);
            setupInitialState();

            beginStrategy1NormalOtfSimpleMergeMeasurement();
            DUCHeartbeat.beginPhase("OTF_DCS_SEARCH");
            publishOtfHeartbeat();
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
                if ((totalLtsExpansions & 0x3ffL) == 0L) {
                    publishOtfHeartbeat();
                }

            }
            publishOtfHeartbeat();

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
                UpdatingControllerEvaluationRecorder.beginCountScope(
                        "DCS (OTF-DUC)",
                        "buildDirectorDUC 実行時間");
                LTS<Long, Action> result;
                try {
                    result = buildDirectorDUC();
                } finally {
                    UpdatingControllerEvaluationRecorder.endCountScope(
                            "DCS (OTF-DUC)",
                            "buildDirectorDUC 実行時間");
                }
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
            synthesizeDUCTime = System.currentTimeMillis() - synthesizeDUCStart;
            if (logWriter != null) {
                if (debugLogEnabled) {
                    emitCacheDiagnostics();
                    log("=== Synthesis Finished ===");
                }
                if (profileLogEnabled) {
                    emitProfilingDiagnostics();
                    profileLog("=== Profiling Finished ===");
                }
                logWriter.close();
            }
            // 合成完了後
            recordOtfDcsTimingEvaluation();
            UpdatingControllerEvaluationRecorder.endCountScope(
                    "DCS (OTF-DUC)",
                    "synthesizeDUC 実行時間");
            recordOtfDetailedEvaluation();
        }
    }

    public void log(String message) {
        if (debugLogEnabled && logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    protected void configureUpdateProtocol(
            UpdateProtocolSpec updateProtocolSpec,
            Map<Integer, String> oldSafetyStopActionsByIndex,
            Map<Integer, String> newSafetyStartActionsByIndex) {
        // Legacy O-DUCS has a fixed update protocol; subclasses may install a richer one.
    }

    protected boolean isFineGrainedMode() {
        return false;
    }

    protected void onDebugLogOpened() {
        // Extension hook for mode-specific debug headers.
    }

    private void beginStrategy1NormalOtfSimpleMergeMeasurement() {
        if (!beliefRepairEnabled) {
            return;
        }
        strategy1NormalOtfSimpleMergeBeforeMemory = EvaluationProfiler.getCurrentMemoryUsage();
        EvaluationProfiler.checkpointAndResetIntervalPeakMemory();
        strategy1NormalOtfSimpleMergeStartMillis = System.currentTimeMillis();
    }

    private void endStrategy1NormalOtfSimpleMergeMeasurement() {
        if (!beliefRepairEnabled || strategy1NormalOtfSimpleMergeStartMillis < 0
                || strategy1NormalOtfSimpleMergeTime >= 0) {
            return;
        }
        strategy1NormalOtfSimpleMergeTime =
                System.currentTimeMillis() - strategy1NormalOtfSimpleMergeStartMillis;
        strategy1NormalOtfSimpleMergePeakMemory =
                EvaluationProfiler.getCurrentIntervalPeakMemoryUsage();
        strategy1NormalOtfSimpleMergeMemoryIncrease =
                strategy1NormalOtfSimpleMergePeakMemory - strategy1NormalOtfSimpleMergeBeforeMemory;
    }

    private void beginStrategy1RepairMeasurement() {
        if (!beliefRepairEnabled) {
            return;
        }
        strategy1RepairBeforeMemory = EvaluationProfiler.getCurrentMemoryUsage();
        EvaluationProfiler.checkpointAndResetIntervalPeakMemory();
        strategy1RepairStartMillis = System.currentTimeMillis();
    }

    private void endStrategy1RepairMeasurement() {
        if (!beliefRepairEnabled || strategy1RepairStartMillis < 0 || strategy1RepairTime >= 0) {
            return;
        }
        strategy1RepairTime = System.currentTimeMillis() - strategy1RepairStartMillis;
        strategy1RepairPeakMemory = EvaluationProfiler.getCurrentIntervalPeakMemoryUsage();
        strategy1RepairMemoryIncrease = strategy1RepairPeakMemory - strategy1RepairBeforeMemory;
    }

    private void profileLog(String message) {
        if (profileLogEnabled && logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private void emitCacheDiagnostics() {
        log("  [Cache-Stats] componentStep entries=" + safeSize(componentStepCache)
                + ", hits=" + componentStepCacheHits
                + ", misses=" + componentStepCacheMisses
                + ", invalidHits=" + componentStepCacheInvalidHits);
        log("  [Cache-Stats] hotSwapOutGuard entries=" + safeSize(finishUpdateGuardCache)
                + ", hits=" + finishUpdateGuardCacheHits
                + ", misses=" + finishUpdateGuardCacheMisses);
        log("  [Cache-Stats] allChildrenGoal entries=" + safeSize(allChildrenGoalCache)
                + ", hits=" + allChildrenGoalCacheHits
                + ", misses=" + allChildrenGoalCacheMisses
                + ", invalidations=" + allChildrenGoalCacheInvalidations);
    }

    private void emitProfilingDiagnostics() {
        long synthesizeNanos = millisToNanos(synthesizeDUCTime);
        long searchNanos = millisToNanos(searchTime);
        long effectiveSearchNanos = searchNanos > 0 ? searchNanos : synthesizeNanos;

        profileLog("=== OTF-DUC Profiling Summary ===");
        profileLog(String.format(Locale.ROOT,
                "[Profile-Time] synthesizeDUC total=%s, search=%s, count=%s, buildDirector=%s, transferNC=%s, stitchNC=%s",
                formatNanos(synthesizeNanos),
                formatNanos(searchNanos),
                formatNanos(millisToNanos(countTime)),
                formatNanos(millisToNanos(buildDirectorDUCTime)),
                formatNanos(millisToNanos(transferNCTime)),
                formatNanos(millisToNanos(stitchingNCTime))));

        profileLog(String.format(Locale.ROOT,
                "[Profile-Counts] expansions=%d, generatedChildren=%d, peakStates=%d, peakTransitions=%d, newStates=%d, existingStateHits=%d",
                totalLtsExpansions, generatedChildCount, otfPeakStates, otfPeakTrans,
                newCompostateCount, existingCompostateHitCount));
        profileLog(String.format(Locale.ROOT,
                "[Profile-Counts] errors=%d, safetyViolationChildren=%d, forcedLoopErrors=%d, detectedLoops=%d, fairPromotedLoops=%d",
                errorMarkCount, safetyViolationChildCount, loopErrorCount, detectedLoopCount,
                fairPromotedLoopCount));
        profileLog(String.format(Locale.ROOT,
                "[Profile-Cache] componentStep entries=%d, hits=%d, misses=%d, invalidHits=%d, hitRate=%s",
                safeSize(componentStepCache), componentStepCacheHits, componentStepCacheMisses,
                componentStepCacheInvalidHits,
                formatRatio(componentStepCacheHits, componentStepCacheHits + componentStepCacheMisses)));
        profileLog(String.format(Locale.ROOT,
                "[Profile-Cache] hotSwapOutGuard entries=%d, hits=%d, misses=%d, hitRate=%s",
                safeSize(finishUpdateGuardCache), finishUpdateGuardCacheHits, finishUpdateGuardCacheMisses,
                formatRatio(finishUpdateGuardCacheHits, finishUpdateGuardCacheHits + finishUpdateGuardCacheMisses)));
        profileLog(String.format(Locale.ROOT,
                "[Profile-Cache] allChildrenGoal entries=%d, hits=%d, misses=%d, invalidations=%d, hitRate=%s",
                safeSize(allChildrenGoalCache), allChildrenGoalCacheHits, allChildrenGoalCacheMisses,
                allChildrenGoalCacheInvalidations,
                formatRatio(allChildrenGoalCacheHits, allChildrenGoalCacheHits + allChildrenGoalCacheMisses)));

        profileLog("[Profile-Note] Percentages use search time as the denominator. Nested timings may overlap.");
        profileTime("heuristic selection", heuristicSelectionNanos, heuristicSelectionCalls, effectiveSearchNanos);
        profileTime("heuristic recompute", heuristicRecomputeNanos, heuristicRecomputeRuns, effectiveSearchNanos);
        profileTime("heuristic frontier", heuristicFrontierNanos, -1, effectiveSearchNanos);
        profileTime("heuristic evaluation", heuristicEvaluationNanos, heuristicEvaluationCalls, effectiveSearchNanos);
        profileTime("expandDUC total", stateExpansionNanos, totalLtsExpansions, effectiveSearchNanos);
        profileTime("successor generation", successorGenerationNanos, successorGenerationCalls, effectiveSearchNanos);
        profileTime("component sync", componentSyncNanos, componentSyncCalls, effectiveSearchNanos);
        profileTime("cartesian product", cartesianProductNanos, cartesianProductCalls, effectiveSearchNanos);
        profileTime("new safety sync", safetySyncNanos, safetySyncCalls, effectiveSearchNanos);
        profileTime("buildCompostate total", buildCompostateNanos, buildCompostateCalls, effectiveSearchNanos);
        profileTime("state canonicalization", stateCanonicalizationNanos, buildCompostateCalls, effectiveSearchNanos);
        profileTime("state lookup", stateLookupNanos, stateLookupCalls, effectiveSearchNanos);
        profileTime("new state registration", newStateRegistrationNanos, newCompostateCount, effectiveSearchNanos);
        profileTime("enforce safety check", enforceSafetyCheckNanos, enforceSafetyCheckCalls, effectiveSearchNanos);
        profileTime("hotSwapOut guard", finishUpdateGuardNanos, finishUpdateGuardChecks, effectiveSearchNanos);
        profileTime("child registration", childRegistrationNanos, childRegistrationCalls, effectiveSearchNanos);
        profileTime("explore total", exploreNanos, exploreCalls, effectiveSearchNanos);
        profileTime("isError checks", isErrorCheckNanos, isErrorChecks, effectiveSearchNanos);
        profileTime("setError", setErrorNanos, setErrorCalls, effectiveSearchNanos);
        profileTime("loop detection", loopDetectionNanos, loopDetectionCalls, effectiveSearchNanos);
        profileTime("fairness analysis", fairnessAnalysisNanos, fairnessAnalysisCalls, effectiveSearchNanos);
        profileTime("fair loop promotion", fairPromotionNanos, -1, effectiveSearchNanos);
        profileTime("propagate GOAL", propagateGoalNanos, propagateGoalCalls, effectiveSearchNanos);
        profileTime("propagate GOAL phase1", propagateGoalPhase1Nanos, propagateGoalCalls, effectiveSearchNanos);
        profileTime("propagate GOAL fairness phase2", propagateGoalPhase2Nanos, propagateGoalCalls, effectiveSearchNanos);
        profileLog(String.format(Locale.ROOT,
                "[Profile-Phase2] outerIterations=%d, innerIterations=%d, candidatesBuiltTotal=%d, maxCandidatesBuilt=%d, avgCandidatesBuilt=%s, avgCandidatesAfterUSafety=%s, avgCandidatesAfterDistancePrune=%s, distanceSeededStates=%d, distancePropagatedStates=%d",
                phase2OuterIterations,
                phase2InnerIterations,
                phase2CandidatesBuiltTotal,
                phase2MaxCandidatesBuilt,
                formatAverage(phase2CandidatesBuiltTotal, phase2CandidateBuildCalls),
                formatAverage(phase2CandidatesAfterUSafetyTotal, phase2USafetyFilterCalls),
                formatAverage(phase2CandidatesAfterDistancePruneTotal, phase2DistancePruneCalls),
                phase2DistanceSeededStates,
                phase2DistancePropagatedStates));
        profileTime("phase2 candidate build", phase2CandidateBuildNanos, phase2CandidateBuildCalls, effectiveSearchNanos);
        profileTime("phase2 U-safety filter", phase2USafetyFilterNanos, phase2USafetyFilterCalls, effectiveSearchNanos);
        profileTime("phase2 distance seed", phase2DistanceSeedNanos, phase2DistanceSeedCalls, effectiveSearchNanos);
        profileTime("phase2 distance propagation", phase2DistancePropagationNanos, phase2DistancePropagationCalls, effectiveSearchNanos);
        profileTime("phase2 distance prune", phase2DistancePruneNanos, phase2DistancePruneCalls, effectiveSearchNanos);
        profileTime("phase2 exit action selection", phase2ExitActionSelectionNanos, phase2ExitActionSelectionCalls, effectiveSearchNanos);
        profileTime("phase2 goal apply", phase2GoalApplyNanos, phase2GoalApplyCalls, effectiveSearchNanos);
        profileTime("phase2 post-promotion propagation", phase2PostPromotionPropagationNanos, phase2PostPromotionPropagationCalls, effectiveSearchNanos);
        profileTime("fair reachability action check", fairReachabilityActionCheckNanos, fairReachabilityActionCheckCalls, effectiveSearchNanos);
        profileTime("fair rejected summary build", fairReachabilityRejectedSummaryNanos, fairReachabilityRejectedSummaryCalls, effectiveSearchNanos);
        profileTime("has U successor in candidates", hasUncontrollableSuccessorInNanos, hasUncontrollableSuccessorInCalls, effectiveSearchNanos);
        profileTime("propagate GOAL distance update", propagateGoalDistanceUpdateNanos, -1, effectiveSearchNanos);
        profileTime("propagate ERROR", propagateErrorNanos, propagateErrorCalls, effectiveSearchNanos);

        long directorNanos = millisToNanos(buildDirectorDUCTime);
        long effectiveDirectorNanos = directorNanos > 0 ? directorNanos : synthesizeNanos;
        profileLog(String.format(Locale.ROOT,
                "[Profile-Director] reachableStates=%d, collectedEdges=%d, outputStatesAssigned=%d, outputTransitionAttempts=%d, outputTransitions=%d, prunedControllable=%d",
                directorReachableStates,
                directorEdgesCollected,
                directorOutputStatesAssigned,
                directorTransitionEmissionAttempts,
                directorOutputTransitions,
                prunedControllableTransitions));
        profileLog(String.format(Locale.ROOT,
                "[Profile-Director] ncStates=%d, ncTransitions=%d, hotSwapOutAttempts=%d, ncConnectionSuccess=%d, ncConnectionMiss=%d, preUpdateRaw=%d, preUpdateClasses=%d, preUpdateRemoved=%d",
                directorNcStatesTransferred,
                directorNcTransitionsTransferred,
                directorNcConnectionAttempts,
                ncConnectionSuccessCount,
                ncConnectionMissCount,
                preUpdateOutputMergedStates,
                preUpdateOutputClassStates,
                preUpdateOutputMergeRemovedStates));
        profileTime("director action registration", directorActionRegistrationNanos, directorActionRegistrationCount, effectiveDirectorNanos);
        profileTime("director NC state transfer", directorNcStateTransferNanos, directorNcStatesTransferred, effectiveDirectorNanos);
        profileTime("director NC transition transfer", directorNcTransitionTransferNanos, directorNcTransitionsTransferred, effectiveDirectorNanos);
        profileTime("director edge collection", directorEdgeCollectionNanos, directorReachableStates, effectiveDirectorNanos);
        profileTime("output pruning decision", outputPruningDecisionNanos, outputPruningDecisionCalls, effectiveDirectorNanos);
        profileTime("director NC connection", directorNcConnectionNanos, directorNcConnectionAttempts, effectiveDirectorNanos);
        profileTime("director NC debug prep", directorNcConnectionDebugPrepNanos, directorNcConnectionAttempts, effectiveDirectorNanos);
        profileTime("director NC signature", directorNcConnectionSignatureNanos, directorNcConnectionSignatureCalls, effectiveDirectorNanos);
        profileTime("director pre-update merge", directorPreUpdateMergeNanos, -1, effectiveDirectorNanos);
        profileTime("director ID assignment", directorIdAssignmentNanos, directorOutputStatesAssigned, effectiveDirectorNanos);
        profileTime("director transition emission", directorTransitionEmissionNanos, directorTransitionEmissionAttempts, effectiveDirectorNanos);
        profileTime("director build total", directorTraversalNanos, -1, effectiveDirectorNanos);
    }

    private void profileTime(String label, long nanos, long calls, long denominatorNanos) {
        String callsText = calls >= 0 ? Long.toString(calls) : "-";
        String averageText = calls > 0 ? formatNanos(nanos / calls) : "-";
        profileLog(String.format(Locale.ROOT,
                "[Profile-Time] %-34s total=%s, pct=%s, calls=%s, avg=%s",
                label, formatNanos(nanos), formatPercent(nanos, denominatorNanos), callsText, averageText));
    }

    private long millisToNanos(long millis) {
        return millis * 1_000_000L;
    }

    private String formatNanos(long nanos) {
        double millis = nanos / 1_000_000.0;
        return String.format(Locale.ROOT, "%.3f ms", millis);
    }

    private String formatPercent(long nanos, long denominatorNanos) {
        if (denominatorNanos <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f%%", (100.0 * nanos) / denominatorNanos);
    }

    private String formatRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f%%", (100.0 * numerator) / denominator);
    }

    private String formatAverage(long numerator, long denominator) {
        if (denominator <= 0) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f", ((double) numerator) / denominator);
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
        strategy1NormalOtfSimpleMergeStartMillis = -1;
        strategy1NormalOtfSimpleMergeTime = -1;
        strategy1NormalOtfSimpleMergeBeforeMemory = -1;
        strategy1NormalOtfSimpleMergePeakMemory = -1;
        strategy1NormalOtfSimpleMergeMemoryIncrease = -1;
        strategy1RepairStartMillis = -1;
        strategy1RepairTime = -1;
        strategy1RepairBeforeMemory = -1;
        strategy1RepairPeakMemory = -1;
        strategy1RepairMemoryIncrease = -1;
        heuristicSelectionNanos = 0;
        heuristicRecomputeNanos = 0;
        heuristicFrontierNanos = 0;
        heuristicEvaluationNanos = 0;
        stateExpansionNanos = 0;
        successorGenerationNanos = 0;
        componentSyncNanos = 0;
        cartesianProductNanos = 0;
        safetySyncNanos = 0;
        buildCompostateNanos = 0;
        stateCanonicalizationNanos = 0;
        stateLookupNanos = 0;
        newStateRegistrationNanos = 0;
        enforceSafetyCheckNanos = 0;
        finishUpdateGuardNanos = 0;
        childRegistrationNanos = 0;
        exploreNanos = 0;
        isErrorCheckNanos = 0;
        setErrorNanos = 0;
        loopDetectionNanos = 0;
        fairnessAnalysisNanos = 0;
        fairPromotionNanos = 0;
        propagateGoalNanos = 0;
        propagateErrorNanos = 0;
        propagateGoalPhase1Nanos = 0;
        propagateGoalPhase2Nanos = 0;
        propagateGoalDistanceUpdateNanos = 0;
        phase2CandidateBuildNanos = 0;
        phase2USafetyFilterNanos = 0;
        phase2DistanceSeedNanos = 0;
        phase2DistancePropagationNanos = 0;
        phase2DistancePruneNanos = 0;
        phase2ExitActionSelectionNanos = 0;
        phase2GoalApplyNanos = 0;
        phase2PostPromotionPropagationNanos = 0;
        fairReachabilityActionCheckNanos = 0;
        fairReachabilityRejectedSummaryNanos = 0;
        hasUncontrollableSuccessorInNanos = 0;
        outputPruningDecisionNanos = 0;
        directorTraversalNanos = 0;
        directorActionRegistrationNanos = 0;
        directorNcStateTransferNanos = 0;
        directorNcTransitionTransferNanos = 0;
        directorEdgeCollectionNanos = 0;
        directorNcConnectionNanos = 0;
        directorNcConnectionDebugPrepNanos = 0;
        directorNcConnectionSignatureNanos = 0;
        directorPreUpdateMergeNanos = 0;
        directorBeliefRepairNanos = 0;
        directorNondetBeliefRepairNanos = 0;
        directorIdAssignmentNanos = 0;
        directorTransitionEmissionNanos = 0;
        heuristicSelectionCalls = 0;
        heuristicRecomputeRuns = 0;
        heuristicRecomputedStates = 0;
        heuristicEvaluationCalls = 0;
        successorGenerationCalls = 0;
        componentSyncCalls = 0;
        cartesianProductCalls = 0;
        safetySyncCalls = 0;
        buildCompostateCalls = 0;
        stateLookupCalls = 0;
        enforceSafetyCheckCalls = 0;
        finishUpdateGuardChecks = 0;
        childRegistrationCalls = 0;
        exploreCalls = 0;
        isErrorChecks = 0;
        setErrorCalls = 0;
        loopDetectionCalls = 0;
        fairnessAnalysisCalls = 0;
        propagateGoalCalls = 0;
        propagateErrorCalls = 0;
        outputPruningDecisionCalls = 0;
        directorActionRegistrationCount = 0;
        directorNcStatesTransferred = 0;
        directorNcTransitionsTransferred = 0;
        directorReachableStates = 0;
        directorEdgesCollected = 0;
        directorNcConnectionAttempts = 0;
        directorNcConnectionSignatureCalls = 0;
        directorOutputStatesAssigned = 0;
        directorTransitionEmissionAttempts = 0;
        beliefRepairCandidateGroups = 0;
        beliefRepairSuccessGroups = 0;
        beliefRepairFallbackGroups = 0;
        beliefRepairGeneratedNodes = 0;
        beliefLazyExpansionRounds = 0;
        beliefLazyExpansionAttempts = 0;
        beliefLazyExpansionExpandedActions = 0;
        beliefLazyExpansionAddedEdges = 0;
        beliefUnsafeControllableDiscardCount = 0;
        beliefUnexploredUncontrollableWarningPlans = 0;
        beliefUnexploredUncontrollableWarningNodes = 0;
        beliefUnexploredUncontrollableWarningActions = 0;
        beliefRepairResourceLimitFallbacks = 0;
        beliefRepairResourceLimitBeliefNodeFallbacks = 0;
        beliefRepairResourceLimitAdditionalConcreteFallbacks = 0;
        beliefRepairResourceLimitAdditionalTransitionFallbacks = 0;
        beliefRepairResourceLimitTimeFallbacks = 0;
        beliefRepairMaxObservedBeliefNodes = 0;
        beliefRepairMaxObservedAdditionalConcreteStates = 0;
        beliefRepairMaxObservedAdditionalTransitions = 0;
        nondetBeliefRepairCandidateGroups = 0;
        nondetBeliefRepairSuccessGroups = 0;
        nondetBeliefRepairFallbackGroups = 0;
        nondetBeliefRepairReplacedTransitions = 0;
        totalFairnessCandidatesProcessed = 0;
        phase2OuterIterations = 0;
        phase2InnerIterations = 0;
        phase2CandidateBuildCalls = 0;
        phase2USafetyFilterCalls = 0;
        phase2DistanceSeedCalls = 0;
        phase2DistancePropagationCalls = 0;
        phase2DistancePruneCalls = 0;
        phase2ExitActionSelectionCalls = 0;
        phase2GoalApplyCalls = 0;
        phase2PostPromotionPropagationCalls = 0;
        phase2CandidatesBuiltTotal = 0;
        phase2CandidatesAfterUSafetyTotal = 0;
        phase2CandidatesAfterDistancePruneTotal = 0;
        phase2MaxCandidatesBuilt = 0;
        phase2DistanceSeededStates = 0;
        phase2DistancePropagatedStates = 0;
        fairReachabilityActionCheckCalls = 0;
        fairReachabilityRejectedSummaryCalls = 0;
        hasUncontrollableSuccessorInCalls = 0;
        componentStepCacheHits = 0;
        componentStepCacheMisses = 0;
        componentStepCacheInvalidHits = 0;
        finishUpdateGuardCacheHits = 0;
        finishUpdateGuardCacheMisses = 0;
        allChildrenGoalCacheHits = 0;
        allChildrenGoalCacheMisses = 0;
        allChildrenGoalCacheInvalidations = 0;
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
        preUpdateOutputClassStates = 0;
        preUpdateOutputMergeRemovedStates = 0;
        componentStepCache.clear();
        finishUpdateGuardCache.clear();
        allChildrenGoalCache.clear();
        log("  [Cache-Config] componentStepCache=true, hotSwapOutGuardCache=true, allChildrenGoalCache=true");
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
    
        if (isFineGrainedMode()) {
            return;
        }

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
        long buildStart = System.nanoTime();
        buildCompostateCalls++;

        // 状態の正規化（Canonicalization）ロジック
        // 現在の更新フェーズにおいて追跡（Trace）対象外となっているコンポーネントは、
        // 将来の挙動に影響を与えないため、状態IDを固定値 -2L に統一する。
        // これにより、インターリービングの順序違いなどで生じる等価な状態がハッシュキーレベルで一致するようになる。

        long canonicalizationStart = System.nanoTime();

        // [最適化] getMarkingStateFromList 内の instanceof 呼び出しを削減するための型キャスト
        long mState = getMarkingStateFromList(states);
        // [最適化] ビットマスクを取得。これにより 19要素のループ内での分岐が極めて高速になる。
        int currentMask = isFineGrainedMode() ? traceMaskFor(mState) : traceMasks[(int) mState];

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

            // Marking 8 では hotSwapOut ガードを先に評価する。
            // 状態生成時にあらかじめチェックすることで、ヒューリスティックがこの手を選ばないようにする
            if (isFinishUpdateReadyStateId(mState)) {
                long guardStart = System.nanoTime();
                finishUpdateGuardChecks++;
                if (!checkHotswapEndCondition(result)) {
                    result.setFinishUpdateBlocked(true);
                }
                finishUpdateGuardNanos += System.nanoTime() - guardStart;
            }
            // ======================================================
        
            heuristic.newState(result, parent);
            if (isGoalProgressStateId(mState)) {
                result.setStatus(Status.GOAL);
                // result.setStatus(CompostateDUC.Status.GOAL);
                result.setBestControllable(0, null);
            }
            long safetyCheckStart = System.nanoTime();
            enforceSafetyCheckCalls++;
            boolean enforceError = checkErrorWithEnforce(result);
            enforceSafetyCheckNanos += System.nanoTime() - safetyCheckStart;
            if (enforceError || heuristic.fullyExplored(result)) {
                setError(result);
            }
            newStateRegistrationNanos += System.nanoTime() - registrationStart;
        } else {
            existingCompostateHitCount++;
        }
        buildCompostateNanos += System.nanoTime() - buildStart;
        return result;
    }

    private int traceMaskFor(long markingState) {
        int mask = 0;
        for (int i = 0; i < ltssSize; i++) {
            if (isTrace(i, markingState)) {
                mask |= (1 << i);
            }
        }
        return mask;
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

    protected boolean isInRange(int index, int start, int end) {
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
            // --- Pre-hotSwapIn (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False (ここで return false されるため)
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-hotSwapIn (State 1-9) ---
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
            // --- Pre-hotSwapIn (State 0) ---
            if (ltsIndex == idxOC)
                return true;
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd))
                return true;

            // Mapping, New Safety は False
            return false;
        } else if (markingState >= 1 && markingState <= 9) {
            // --- Post-hotSwapIn (State 1-9) ---
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
        // hotSwapIn 発火後（State 1 以上）は旧コントローラを trace から外す。
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

    private boolean isFairnessEligibleState(CompostateDUC<State, Action> compostate) {
        return isFinishUpdateReadyStateId(getMarkingState(compostate));
    }

    private boolean isFairnessEligibleLoop(Set<CompostateDUC<State, Action>> states) {
        if (states == null || states.isEmpty()) {
            return false;
        }
        for (CompostateDUC<State, Action> state : states) {
            if (!isFairnessEligibleState(state)) {
                return false;
            }
        }
        return true;
    }

    protected boolean isUpdateProgressState(CompostateDUC<State, Action> compostate) {
        long markingState = getMarkingState(compostate);
        return markingState >= 1 && markingState <= 7;
    }

    protected boolean isFinishUpdateReadyStateId(long markingState) {
        return markingState == 8;
    }

    protected boolean isGoalProgressStateId(long markingState) {
        return markingState == 9;
    }

    public boolean isFineGrainedProgressActionEnabled(long progressState, String actionName) {
        return false;
    }

    public int markingDepthForHeuristic(long markingState) {
        if (markingState == 0) {
            return 0;
        }
        if (markingState == 1) {
            return 1;
        }
        if (markingState == 9) {
            return 5;
        }
        long mask = markingState - 1;
        return 1 + Long.bitCount(mask);
    }

    public int maxMarkingDepthForHeuristic() {
        return 5;
    }

    public int actionPriorityCost(String actionName) {
        if (UpdateConstants.FINISH_UPDATE.equals(actionName)) {
            return 0;
        }
        if (UpdateConstants.STOP_OLD_SPEC.equals(actionName)) {
            return 10;
        }
        if (UpdateConstants.RECONFIGURE.equals(actionName)) {
            return 20;
        }
        if (UpdateConstants.START_NEW_SPEC.equals(actionName)) {
            return 30;
        }
        if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
            return 40;
        }
        return 100;
    }

    public boolean isUpdateActionForExploration(String actionName) {
        return UpdateConstants.BEGIN_UPDATE.equals(actionName)
                || UpdateConstants.FINISH_UPDATE.equals(actionName)
                || UpdateConstants.STOP_OLD_SPEC.equals(actionName)
                || UpdateConstants.RECONFIGURE.equals(actionName)
                || UpdateConstants.START_NEW_SPEC.equals(actionName);
    }

    public int explorationActionCategoryRank(String actionName, boolean controllableAction) {
        if (!controllableAction) {
            return 0;
        }
        if (isUpdateActionForExploration(actionName)) {
            return 1;
        }
        return 2;
    }

    protected boolean usesSyntheticProgressSlot(int ltsIndex) {
        return false;
    }

    protected Set<State> progressSlotSuccessors(long progressState, String actionName) {
        return null;
    }

    private boolean checkErrorWithEnforce(CompostateDUC<State, Action> compostate) {
        long markingState = getMarkingState(compostate);
        List<State> currentStates = compostate.getStates();

        for (int i = 0; i < ltssSize; i++) {
            State s = currentStates.get(i);
            if (s instanceof Long && (Long) s == -1L) {
                if (isEnforce(i, markingState)) {
                    if(debugLogEnabled)log("[Safety Violation] Component " + i + " reached Error state -1 at Marking " + markingState);
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
        if (debugLogEnabled && alreadyExplored != null && !alreadyExplored.isEmpty()) {
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
            successorGenerationCalls++;
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
            if(debugLogEnabled){
                if (blocked) log("[BLOCKED] Transition blocked by hotSwapOut condition: " + action);
                else log("[DEADLOCK/INVALID] No valid next states for action: " + action);
            }
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
            childRegistrationCalls++;
            state.addChild(action, child);
            invalidateAllChildrenGoalCache(state, action);
            child.addParent(action, state);
            children.add(child);
            childRegistrationNanos += System.nanoTime() - childRegistrationStart;
        }

        // 全ての分岐を登録し終わってから explore を評価する。
        for (CompostateDUC<State, Action> child : children) {
            heuristic.notifyExpandingState(state, action, child);
            long exploreStart = System.nanoTime();
            exploreCalls++;
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
        componentSyncCalls++;
        for (int i = 0; i < ltssSize; ++i) {
            ComponentStepResult<State> step = getCachedComponentStep(
                    i, markingState, parentStates.get(i), action, isOldAction, strippedActionName);
            if (step.invalid) {
                return null;
            }
            possibleStatesPerLTS.add(step.successors);
        }
        componentSyncNanos += System.nanoTime() - componentSyncStart;

        // 全LTSの次状態候補から直積（Cartesian Product）を生成
        List<List<State>> cartesianProduct = new ArrayList<>();
        long cartesianStart = System.nanoTime();
        cartesianProductCalls++;
        generateCartesianProduct(possibleStatesPerLTS, 0, new ArrayList<State>(), cartesianProduct);
        cartesianProductNanos += System.nanoTime() - cartesianStart;

        // startNewSpec の場合の Safety の同期(上書き)
        if (isStartNewSpecActionForSafetySync(actionName)) {
            long safetySyncStart = System.nanoTime();
            safetySyncCalls++;
            for (List<State> childVector : cartesianProduct) {
                applyStartNewSpecSafetySync(childVector, actionName);
            }
            safetySyncNanos += System.nanoTime() - safetySyncStart;
        }

        return cartesianProduct;
    }

    private ComponentStepResult<State> getCachedComponentStep(int ltsIndex, long markingState, State currentState,
            HAction<State, Action> action, boolean isOldAction, String strippedActionName) {

        ComponentStepCacheKey key = new ComponentStepCacheKey(
                ltsIndex, markingState, currentState, action.getAction(), isOldAction);
        ComponentStepResult<State> cached = componentStepCache.get(key);
        if (cached != null) {
            componentStepCacheHits++;
            if (cached.invalid) {
                componentStepCacheInvalidHits++;
            }
            return cached;
        }

        componentStepCacheMisses++;
        ComponentStepResult<State> computed = computeComponentStep(
                ltsIndex, markingState, currentState, action, isOldAction, strippedActionName);
        componentStepCache.put(key, computed);
        return computed;
    }

    private ComponentStepResult<State> computeComponentStep(int ltsIndex, long markingState, State currentState,
            HAction<State, Action> action, boolean isOldAction, String strippedActionName) {

        if (usesSyntheticProgressSlot(ltsIndex)) {
            long progressState = ((Long) currentState).longValue();
            Set<State> successors = progressSlotSuccessors(progressState, action.toString());
            if (successors == null || successors.isEmpty()) {
                return ComponentStepResult.invalid();
            }
            return ComponentStepResult.successors(successors);
        }

        if (!isTrace(ltsIndex, markingState)) {
            return ComponentStepResult.successors(Collections.singleton(currentState));
        }

        LTS<State, Action> lts = ltss.get(ltsIndex);
        Action rawAction = action.getAction();

        if (markingState == 0 && isOldAction && ltsIndex != idxOC) {
            boolean found = false;
            Set<State> successors = new HashSet<>();
            for (Pair<Action, State> trans : lts.getTransitions(currentState)) {
                if (trans.getFirst().toString().equals(strippedActionName)) {
                    successors.add(trans.getSecond());
                    found = true;
                    // 非決定的な分岐はすべて拾う。
                }
            }
            if (!found) {
                successors.add(currentState);
            }
            return ComponentStepResult.successors(successors);
        }

        Set<State> image = lts.getTransitions(currentState).getImage(rawAction);
        if (image == null || image.isEmpty()) {
            if (isActive(ltsIndex, markingState) && lts.getActions().contains(rawAction)) {
                return ComponentStepResult.invalid();
            }
            return ComponentStepResult.successors(Collections.singleton(currentState));
        }

        return ComponentStepResult.successors(image);
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
            applySafetySyncForIndex(childStates, entry.getKey(), entry.getValue());
        }
    }

    protected boolean isStartNewSpecActionForSafetySync(String actionName) {
        return UpdateConstants.START_NEW_SPEC.equals(actionName);
    }

    protected void applyStartNewSpecSafetySync(List<State> childStates, String actionName) {
        applySafetySync(childStates);
    }

    protected void applySafetySyncForIndex(List<State> childStates, int safetyIdx) {
        List<Integer> compIndices = safetyComponentIndicesMap.get(safetyIdx);
        if (compIndices == null) {
            return;
        }
        applySafetySyncForIndex(childStates, safetyIdx, compIndices);
    }

    private void applySafetySyncForIndex(List<State> childStates, int safetyIdx, List<Integer> compIndices) {
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

    /**
     * hotSwapOut の実行可否を判定するガード条件
     * 1. 環境状態が新環境へ翻訳可能であること
     * 2. 翻訳後の環境と現在の安全性状態の組み合わせが、新コントローラ(NC)に存在すること
     */
    private boolean checkHotswapEndCondition(CompostateDUC<State, Action> state) {
        String cacheKey = generateMapSignature(state);
        Boolean cached = finishUpdateGuardCache.get(cacheKey);
        if (cached != null) {
            finishUpdateGuardCacheHits++;
            return cached;
        }
        finishUpdateGuardCacheMisses++;

        // シグネチャを仮生成して NC マップとの照合を行う
        String signature = generateNCSignature(state);
        
        // 翻訳に失敗した（環境状態がマップにない）場合は null が返る想定
        if (signature == null) {
            log("  [hotSwapOut Guard] BLOCKED: Environment state translation failed.");
            finishUpdateGuardCache.put(cacheKey, false);
            return false;
        }

        // NC の状態空間（newControllerConnectionMap）にキーが存在するかチェック
        boolean isSafeInNC = newControllerConnectionMap.containsKey(signature);

        if (!isSafeInNC && debugLogEnabled) {
            // 不整合発見を検証するためのログ
            log("  [hotSwapOut Guard] BLOCKED: MapSignature '(MapEnv) " + cacheKey + " (New Safety)' = Signature '(NewEnv) " + signature + " (New Safety)' is NOT found in New Controller's safe states.");
        }

        finishUpdateGuardCache.put(cacheKey, isSafeInNC);
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
                // hotSwapIn への経路を確認する必要があることを意味する。
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
                    // 更新中のループは、marking state 8 だけ fairness で扱う。
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
        propagateGoalPhase1Queue(queue, winners);
        propagateGoalPhase1Nanos += System.nanoTime() - phase1Start;

        // Phase 2: marking state 8 の SCC だけを fairness のもとで固定点計算する。
        long phase2Start = System.nanoTime();
        if (finishUpdateFairnessEnabled) {
            propagateGoalPhase2Fair(winners, queue);
        }
        propagateGoalPhase2Nanos += System.nanoTime() - phase2Start;

        // Phase 3: hotSwapIn 前の旧コントローラ通常運転を GOAL 側へ閉包する。
        // これは fairness ではなく、m0 で hotSwapIn 可能な旧状態集合を戻す処理である。
        propagateGoalPreUpdateClosure(winners, queue);

        if (!winners.isEmpty()) {
            updateDistances(goals, winners, winners.size());
        }
        propagateGoalNanos += System.nanoTime() - startTotal;
    }

    private void propagateGoalPhase2Fair(
            Set<CompostateDUC<State, Action>> winners,
            Deque<CompostateDUC<State, Action>> queue) {
        boolean changed;
        do {
            changed = false;
            if (profileLogEnabled) {
                phase2OuterIterations++;
            }

            long candidateBuildStart = profileLogEnabled ? System.nanoTime() : 0L;
            Set<CompostateDUC<State, Action>> candidates = new HashSet<>();
            for (CompostateDUC<State, Action> s : compostates.values()) {
                if (s.isStatus(Status.NONE)
                        && s.isLive()
                        && isFairnessEligibleState(s)) {
                    candidates.add(s);
                }
            }
            totalFairnessCandidatesProcessed += candidates.size();
            if (profileLogEnabled) {
                phase2CandidateBuildNanos += System.nanoTime() - candidateBuildStart;
                phase2CandidateBuildCalls++;
                phase2CandidatesBuiltTotal += candidates.size();
                phase2MaxCandidatesBuilt = Math.max(phase2MaxCandidatesBuilt, candidates.size());
            }

            if (candidates.isEmpty()) break;

            boolean innerChanged;
            Map<CompostateDUC<State, Action>, Integer> dist = new HashMap<>();

            // U-safety と fair 到達性の両方が安定するまで候補集合を絞り込む。
            do {
                innerChanged = false;
                if (profileLogEnabled) {
                    phase2InnerIterations++;
                }

                // 1. U-safety フィルタ: 環境が候補集合の外へ出られるのは、
                // 既に証明済みの GOAL に向かう場合だけでなければならない。
                long uSafetyStart = profileLogEnabled ? System.nanoTime() : 0L;
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
                if (profileLogEnabled) {
                    phase2USafetyFilterNanos += System.nanoTime() - uSafetyStart;
                    phase2USafetyFilterCalls++;
                    phase2CandidatesAfterUSafetyTotal += candidates.size();
                }

                if (candidates.isEmpty()) break;

                // 2. fair 到達性フィルタ。
                dist.clear();
                Deque<CompostateDUC<State, Action>> distQueue = new ArrayDeque<>();

                // 証明済み GOAL へ 1 手で到達できる状態を距離計算の始点にする。
                long distanceSeedStart = profileLogEnabled ? System.nanoTime() : 0L;
                int distSizeBeforeSeed = dist.size();
                for (CompostateDUC<State, Action> s : candidates) {
                    for (HAction<State, Action> action : s.getTransitions()) {
                        Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                        if (children == null || children.isEmpty()) {
                            continue;
                        }
                        if (!areAllExploredChildrenGoal(s, action, children)) {
                            continue;
                        }
                        if (!canUseActionForFairReachability(s, action, candidates)) {
                            continue;
                        }
                        dist.put(s, 1);
                        distQueue.add(s);
                        break;
                    }
                }
                if (profileLogEnabled) {
                    phase2DistanceSeedNanos += System.nanoTime() - distanceSeedStart;
                    phase2DistanceSeedCalls++;
                    phase2DistanceSeededStates += Math.max(0, dist.size() - distSizeBeforeSeed);
                }

                // 候補集合の内側で fair 距離を逆向きに伝播する。
                long distancePropagationStart = profileLogEnabled ? System.nanoTime() : 0L;
                long propagatedStatesThisRound = 0;
                while (!distQueue.isEmpty()) {
                    CompostateDUC<State, Action> current = distQueue.poll();
                    propagatedStatesThisRound++;
                    
                    for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> pRel : current.getParents()) {
                        CompostateDUC<State, Action> parent = pRel.getSecond();
                        if (candidates.contains(parent)) {
                            HAction<State, Action> actionFromParent = pRel.getFirst();
                            Set<CompostateDUC<State, Action>> siblings = parent.getExploredChildren().getImage(actionFromParent);
                            if (siblings == null || siblings.isEmpty()) {
                                continue;
                            }
                            if (!canUseActionForFairReachability(parent, actionFromParent, candidates)) {
                                continue;
                            }
                            
                            boolean validMove = true;
                            int maxChildD = 0;
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
                if (profileLogEnabled) {
                    phase2DistancePropagationNanos += System.nanoTime() - distancePropagationStart;
                    phase2DistancePropagationCalls++;
                    phase2DistancePropagatedStates += propagatedStatesThisRound;
                }

                // hotSwapOut への fair 経路を持たない閉じた成分を候補から外す。
                long distancePruneStart = profileLogEnabled ? System.nanoTime() : 0L;
                it = candidates.iterator();
                while (it.hasNext()) {
                    CompostateDUC<State, Action> s = it.next();
                    if (!dist.containsKey(s)) {
                        it.remove();
                        innerChanged = true;
                    }
                }
                if (profileLogEnabled) {
                    phase2DistancePruneNanos += System.nanoTime() - distancePruneStart;
                    phase2DistancePruneCalls++;
                    phase2CandidatesAfterDistancePruneTotal += candidates.size();
                }

            } while (innerChanged);

            // 残った候補は U-safe であり、GOAL への fair 経路を持つ。
            if (!candidates.isEmpty()) {
                long exitActionSelectionStart = profileLogEnabled ? System.nanoTime() : 0L;
                Map<CompostateDUC<State, Action>, HAction<State, Action>> exitActions = new HashMap<>();
                for (CompostateDUC<State, Action> s : candidates) {
                    HAction<State, Action> bestAction = null;
                    int bestDist = Integer.MAX_VALUE;

                    for (HAction<State, Action> action : s.getTransitions()) {
                        Set<CompostateDUC<State, Action>> children = s.getExploredChildren().getImage(action);
                        if (children == null || children.isEmpty()) {
                            continue;
                        }
                        if (!canUseActionForFairReachability(s, action, candidates)) {
                            continue;
                        }
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
                    exitActions.put(s, bestAction);
                }
                if (profileLogEnabled) {
                    phase2ExitActionSelectionNanos += System.nanoTime() - exitActionSelectionStart;
                    phase2ExitActionSelectionCalls++;
                }

                long goalApplyStart = profileLogEnabled ? System.nanoTime() : 0L;
                for (CompostateDUC<State, Action> winner : candidates) {
                    applyGoalStatus(winner, exitActions.get(winner), winners, queue);
                    changed = true;
                }
                if (profileLogEnabled) {
                    phase2GoalApplyNanos += System.nanoTime() - goalApplyStart;
                    phase2GoalApplyCalls++;
                }

                // 新しく証明された SCC から通常の GOAL 伝播を再開する。
                long postPromotionStart = profileLogEnabled ? System.nanoTime() : 0L;
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
                if (profileLogEnabled) {
                    phase2PostPromotionPropagationNanos += System.nanoTime() - postPromotionStart;
                    phase2PostPromotionPropagationCalls++;
                }
            }
        } while (changed);
    }

    private void propagateGoalPreUpdateClosure(
            Set<CompostateDUC<State, Action>> winners,
            Deque<CompostateDUC<State, Action>> queue) {

        boolean changed;
        do {
            changed = false;

            Set<CompostateDUC<State, Action>> candidates = new HashSet<>();
            Map<CompostateDUC<State, Action>, HAction<State, Action>> beginUpdateActions =
                    new HashMap<>();

            for (CompostateDUC<State, Action> state : compostates.values()) {
                if (!state.isStatus(Status.NONE) || !state.isLive() || getMarkingState(state) != 0) {
                    continue;
                }
                HAction<State, Action> beginUpdateAction = findWinningBeginUpdateAction(state);
                if (beginUpdateAction != null) {
                    candidates.add(state);
                    beginUpdateActions.put(state, beginUpdateAction);
                }
            }

            if (candidates.isEmpty()) {
                return;
            }

            boolean pruned;
            do {
                pruned = false;
                Iterator<CompostateDUC<State, Action>> it = candidates.iterator();
                while (it.hasNext()) {
                    CompostateDUC<State, Action> state = it.next();
                    if (!isPreUpdateClosureSafe(state, candidates)) {
                        it.remove();
                        pruned = true;
                    }
                }
            } while (pruned);

            if (candidates.isEmpty()) {
                return;
            }

            if (debugLogEnabled) {
                log("  [PreUpdate-Closure] Promoting marking state 0 states as GOAL: "
                        + candidates.size());
            }

            for (CompostateDUC<State, Action> state : candidates) {
                HAction<State, Action> beginUpdateAction = beginUpdateActions.get(state);
                if (beginUpdateAction == null) {
                    beginUpdateAction = findWinningBeginUpdateAction(state);
                }
                if (beginUpdateAction != null && !isGoal(state)) {
                    applyGoalStatus(state, beginUpdateAction, winners, queue);
                    changed = true;
                }
            }

            if (changed) {
                propagateGoalPhase1Queue(queue, winners);
            }
        } while (changed);
    }

    private HAction<State, Action> findWinningBeginUpdateAction(
            CompostateDUC<State, Action> state) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (!isBeginUpdateAction(action)) {
                continue;
            }
            Set<CompostateDUC<State, Action>> children =
                    state.getExploredChildren().getImage(action);
            if (children != null
                    && !children.isEmpty()
                    && areAllExploredChildrenGoal(state, action, children)) {
                return action;
            }
        }
        return null;
    }

    private boolean isPreUpdateClosureSafe(
            CompostateDUC<State, Action> state,
            Set<CompostateDUC<State, Action>> candidates) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (action.isControllable()) {
                continue;
            }

            Set<CompostateDUC<State, Action>> children =
                    state.getExploredChildren().getImage(action);
            if (children == null || children.isEmpty()) {
                return false;
            }

            if (isBeginUpdateAction(action)) {
                if (!areAllExploredChildrenGoal(state, action, children)) {
                    return false;
                }
                continue;
            }

            for (CompostateDUC<State, Action> child : children) {
                if (isError(child)) {
                    return false;
                }
                if (!isGoal(child) && !candidates.contains(child)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void propagateGoalPhase1Queue(
            Deque<CompostateDUC<State, Action>> queue,
            Set<CompostateDUC<State, Action>> winners) {
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
    }

    private boolean areAllExploredChildrenGoal(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action,
            Set<CompostateDUC<State, Action>> children) {

        ActionChildrenGoalCacheKey key = new ActionChildrenGoalCacheKey(state, action);
        AllChildrenGoalCacheEntry cached = allChildrenGoalCache.get(key);
        if (cached != null && cached.childCount == children.size()) {
            allChildrenGoalCacheHits++;
            return cached.allGoals;
        }

        allChildrenGoalCacheMisses++;
        boolean allGoals = true;
        for (CompostateDUC<State, Action> child : children) {
            if (!isGoal(child)) {
                allGoals = false;
                break;
            }
        }
        allChildrenGoalCache.put(key, new AllChildrenGoalCacheEntry(children.size(), allGoals));
        return allGoals;
    }

    private void invalidateAllChildrenGoalCache(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action) {
        if (allChildrenGoalCache.remove(new ActionChildrenGoalCacheKey(state, action)) != null) {
            allChildrenGoalCacheInvalidations++;
        }
    }

    private boolean canUseActionForFairReachability(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action,
            Set<CompostateDUC<State, Action>> candidates) {

        long actionCheckStart = 0L;
        if (profileLogEnabled) {
            actionCheckStart = System.nanoTime();
            fairReachabilityActionCheckCalls++;
        }
        try {
            if (!action.isControllable()) {
                return true;
            }

            // 更新前状態は、更新中に仮定する fairness の対象外である。
            // 旧コントローラ上の action が uncontrollable loop を作っていても、
            // hotSwapIn は旧コントローラ状態空間からの有効な進行辺として残す。
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
            // 同じ状態の通常 controllable 脱出口は hotSwapOut への進行根拠にしない。
            boolean rejected = hasUncontrollableSuccessorIn(state, candidates);
            if (rejected) {
                fairControllableExitRejectedCount++;
                if (debugLogEnabled) {
                    long summaryStart = profileLogEnabled ? System.nanoTime() : 0L;
                    lastFairControllableExitRejectedSummary =
                            "action=" + action + ", " + summarizeStateForDiagnostics(state)
                            + ", candidatesByMarking=" + summarizeMarkingHistogram(candidates);
                    if (profileLogEnabled) {
                        fairReachabilityRejectedSummaryNanos += System.nanoTime() - summaryStart;
                        fairReachabilityRejectedSummaryCalls++;
                    }
                }
            }
            return !rejected;
        } finally {
            if (profileLogEnabled) {
                fairReachabilityActionCheckNanos += System.nanoTime() - actionCheckStart;
            }
        }
    }

    protected boolean isUpdateProtocolAction(HAction<State, Action> action) {
        String actionName = action.toString();
        return actionName.equals(UpdateConstants.STOP_OLD_SPEC)
            || actionName.equals(UpdateConstants.RECONFIGURE)
            || actionName.equals(UpdateConstants.START_NEW_SPEC)
            || actionName.equals(UpdateConstants.FINISH_UPDATE);
    }

    private boolean isBeginUpdateAction(HAction<State, Action> action) {
        return action.toString().equals(UpdateConstants.BEGIN_UPDATE);
    }

    private boolean isUnexploredControllableEscapeRelevantForLoop(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action,
            Set<CompostateDUC<State, Action>> loopSnapshot) {

        if (getMarkingState(state) == 0 && isBeginUpdateAction(action)) {
            return true;
        }

        if (isFairnessEligibleLoop(loopSnapshot) && isUpdateProtocolAction(action)) {
            return true;
        }

        return !hasSafeUncontrollableSuccessorInLoop(state, loopSnapshot);
    }

    private boolean hasSafeUncontrollableSuccessorInLoop(
            CompostateDUC<State, Action> state,
            Set<CompostateDUC<State, Action>> loopSnapshot) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (action.isControllable()) {
                continue;
            }

            Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
            if (children == null || children.isEmpty()) {
                continue;
            }

            for (CompostateDUC<State, Action> child : children) {
                if (loopSnapshot.contains(child) && !isError(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUncontrollableSuccessorIn(
            CompostateDUC<State, Action> state,
            Set<CompostateDUC<State, Action>> candidates) {

        long start = 0L;
        if (profileLogEnabled) {
            start = System.nanoTime();
            hasUncontrollableSuccessorInCalls++;
        }
        try {
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
        } finally {
            if (profileLogEnabled) {
                hasUncontrollableSuccessorInNanos += System.nanoTime() - start;
            }
        }
    }

    private Set<CompostateDUC<State, Action>> collectUpdatePathUncontrollableLoopContinuationStates(
            Set<CompostateDUC<State, Action>> loopSnapshot) {

        Set<CompostateDUC<State, Action>> badContinuationStates = new HashSet<>();
        if (loopSnapshot == null || loopSnapshot.isEmpty()) {
            return badContinuationStates;
        }

        for (CompostateDUC<State, Action> state : loopSnapshot) {
            if (!isUpdateProgressState(state)) {
                continue;
            }
            for (HAction<State, Action> action : state.getTransitions()) {
                if (action.isControllable()) {
                    continue;
                }
                Set<CompostateDUC<State, Action>> children =
                        state.getExploredChildren().getImage(action);
                if (children == null || children.isEmpty()) {
                    continue;
                }
                for (CompostateDUC<State, Action> child : children) {
                    if (loopSnapshot.contains(child) && isUpdateProgressState(child) && !isError(child)) {
                        badContinuationStates.add(state);
                        break;
                    }
                }
                if (badContinuationStates.contains(state)) {
                    break;
                }
            }
        }
        return badContinuationStates;
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
            invalidateAllChildrenGoalCache(parentNode, parentRel.getFirst());
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
            log("  [Uncontrollable-Pending] State " + state.getStates()
                    + " has no safe Controllable action yet, but non-error Uncontrollable actions remain.");
        }

        // Cアクションでの勝ち筋がなくても、安全なUアクションがあるなら待機（エラーにしない）
        if (!hasPotentialWinningMove && hasSafeUncontrollable) {
            if (debugLogEnabled) log("  [Uncontrollable-Pending] State " + state.getStates()
                    + " remains undecided while Uncontrollable successors are unresolved.");
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

        // fairness は、更新三事象が完了して hotSwapOut 待ちになった
        // marking state 8 のループにだけ適用する。
        if (finishUpdateFairnessEnabled && isFairnessEligibleLoop(loop)) {
            probablyWinningStates.addAll(loop);
        }
    }

    /**
     * OTF-DUC は到達性問題だが、marking state 8 の hotSwapOut 待ちだけは
     * 環境 fairness のもとで判定する。marking state 1-7 の更新パス探索では
     * fairness による救済を行わず、閉じた uncontrollable ループを ERROR 候補として解析する。
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

        boolean fairnessEligibleLoop = isFairnessEligibleLoop(loop);
        Set<CompostateDUC<State, Action>> updatePathUncontrollableContinuationStates =
                fairnessEligibleLoop ? new HashSet<>()
                        : collectUpdatePathUncontrollableLoopContinuationStates(loop);
        if (!updatePathUncontrollableContinuationStates.isEmpty()) {
            loopErrorCount++;
            if (debugLogEnabled) {
                lastLoopErrorSummary = "reason=updatePathUncontrollableLoopContinuation"
                        + ", badContinuationStates=" + updatePathUncontrollableContinuationStates.size()
                        + ", badContinuationMarkings="
                        + summarizeMarkingHistogram(updatePathUncontrollableContinuationStates)
                        + ", detectedLoopMarkings=" + summarizeMarkingHistogram(loop);
                log("  [Update-Path-U-Loop] Uncontrollable loop-continuation action detected in marking state 1-7."
                        + " Marking the source state(s) as ERROR: " + lastLoopErrorSummary);
            }
            for (CompostateDUC<State, Action> state : updatePathUncontrollableContinuationStates) {
                setError(state);
            }
            if (!isError(initial)) {
                propagateError(updatePathUncontrollableContinuationStates, null);
            }
            return;
        }

        boolean hasEscapeHatch = false;
        boolean hasUnexploredControllableEscape = false;
        boolean hasExploredControllableEscape = false;
        boolean hasUpdateProtocolEscape = false;

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
                    if (children == null || children.isEmpty()) {
                        hasEscapeHatch = true;
                        boolean relevantEscape = isUnexploredControllableEscapeRelevantForLoop(s, a, loop);
                        if (relevantEscape) {
                            hasUnexploredControllableEscape = true;
                        }
                        if (debugLogEnabled) {
                            if (relevantEscape) {
                                log("      -> Controllable [" + a + "]: UNEXPLORED (progress-relevant escape hatch found)");
                            } else {
                                log("      -> Controllable [" + a + "]: UNEXPLORED"
                                        + " (ordinary controllable ignored while safe Uncontrollable wait remains)");
                            }
                        }
                    }
                    else{
                        if (debugLogEnabled) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("      -> Controllable [").append(a).append("]: Explored. Children: ");
                            for (CompostateDUC<State, Action> child : children) {
                                sb.append(child.getStates()).append(" (Status: ").append(child.getStatus()).append("), ");
                            }
                            log(sb.toString());
                        }
                        // controllable action は、探索済みの非決定分岐がすべて
                        // ERROR を避ける場合に限り、まだ有効な脱出口候補である。
                        boolean leadsToError = false;
                        boolean exitsLoop = false;
                        for (CompostateDUC<State, Action> child : children) {
                            if (isError(child)) {
                                leadsToError = true;
                                break;
                            }
                            if (!loop.contains(child) || isGoal(child)) {
                                exitsLoop = true;
                            }
                        }
                        if (!leadsToError) {
                            hasEscapeHatch = true;
                            hasExploredControllableEscape = true;
                            if (fairnessEligibleLoop && isUpdateProtocolAction(a) && exitsLoop) {
                                hasUpdateProtocolEscape = true;
                            }
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
                log("  [Loop-Wait] Loop has an unexplored Uncontrollable action. Postponing ERROR marking.");
            }
            return;
        }

        // uncontrollable 遷移でループ外の NONE 状態へ出られるなら、
        // まだ閉じた負けループとは扱わない。
        if (hasOpenUncontrollableExit) {
            if (debugLogEnabled) {
                log("  [Loop-Wait] Loop has a safe Uncontrollable exit to a NONE state. Postponing ERROR marking.");
            }
            return;
        }

        // 未探索 controllable が残っているループは、更新進行に使える
        // 可能性がある場合だけ、現時点では負けと断定しない。通常 controllable は、
        // 同じ状態から safe uncontrollable wait が残るなら fair 到達性の根拠にしない。
        if (hasUnexploredControllableEscape) {
            if (debugLogEnabled) {
                log("  [Loop-Wait] Loop has unexplored progress-relevant Controllable escape hatches. Postponing ERROR marking.");
            }
            return;
        }

        if (!fairnessEligibleLoop && (hasEscapeHatch || hasUncontrollableWait)) {
            if (debugLogEnabled) {
                log("  [Loop-Wait] No uncontrollable cycle was found in marking state 1-7."
                        + " Leaving the loop unresolved for further exploration/output pruning.");
            }
            return;
        }

        // marking state 8 の閉じた安全な uncontrollable ループだけは、
        // fair 固定点計算で hotSwapOut への経路を証明できる場合に受理する。
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
            log("  [Fairness-Loop-Check] Loop has NO Controllable escape hatch, but SAFE UNCONTROLLABLE actions exist!");
            log("  [Fairness-Loop-Check] Fair propagation could not prove a path to hotSwapOut. Marking as ERROR.");
        }

        if (debugLogEnabled) {
            log("  [Loop-Mark-Error] No pending escape can justify this cycle. Marking the entire loop as ERROR.");
        }

        loopErrorCount++;
        if (debugLogEnabled) {
            lastLoopErrorSummary = buildLoopErrorSummary(
                hasEscapeHatch,
                hasUnexploredControllableEscape,
                hasExploredControllableEscape,
                hasUpdateProtocolEscape,
                hasUncontrollableWait,
                hasUnexploredUncontrollable,
                hasOpenUncontrollableExit);
        }

        for (CompostateDUC<State, Action> state : loop) {
            setError(state);
        }

        if (!isError(initial)) {
            propagateError(loop, null);
        }
    }

    private boolean tryPromoteFairLoopToGoal() {
        if (!finishUpdateFairnessEnabled) {
            return false;
        }
        if (!isFairnessEligibleLoop(loop)) {
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
            boolean hasUnexploredControllableEscape,
            boolean hasExploredControllableEscape,
            boolean hasUpdateProtocolEscape,
            boolean hasUncontrollableWait,
            boolean hasUnexploredUncontrollable,
            boolean hasOpenUncontrollableExit) {

        StringBuilder sb = new StringBuilder();
        sb.append("loopSize=").append(loop == null ? 0 : loop.size());
        sb.append(", hasControllableEscape=").append(hasEscapeHatch);
        sb.append(", hasUnexploredControllableEscape=").append(hasUnexploredControllableEscape);
        sb.append(", hasExploredControllableEscape=").append(hasExploredControllableEscape);
        sb.append(", hasUpdateProtocolEscape=").append(hasUpdateProtocolEscape);
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

    protected void recordOtfDetailedEvaluation() {
        recordOtfMarkingAndStatusBreakdown();
        recordOtfExploredUpdateEventTransitionCounts();
        recordOtfExploredUpdatePhaseTransitionAnalysis();
        recordOtfProjectionSplitStats();

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "生成した child 数", generatedChildCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "新規 compostate 生成数", newCompostateCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "既存 compostate hit 数", existingCompostateHitCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "ERROR child 到達数", safetyViolationChildCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開統計", "hotSwapOut guard block 回数", finishUpdateGuardBlockedCount, "回");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "検出した loop 数", detectedLoopCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "更新前 m0 loop 例外扱い数", preUpdateLoopExceptionCount, "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC fairness / loop 統計", "marking state 8 fairness 有効", finishUpdateFairnessEnabled ? 1 : 0, "boolean");
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
                "OTF-DUC 出力 pruning 統計", "探索上の旧コントローラ相当状態数（出力時マージ前）", preUpdateOutputMergedStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "出力上の旧コントローラ相当状態数（マージ後）", preUpdateOutputClassStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力 pruning 統計", "出力時マージで削減した旧コントローラ相当状態数", preUpdateOutputMergeRemovedStates, "状態");
        UpdatingControllerEvaluationRecorder.recordStateTransitionReduction(
                "OTF-DUC 出力 pruning 削減率",
                "reachable director graph -> emitted update-controller fragment",
                directorReachableStates,
                directorCandidateTransitions,
                directorOutputStatesAssigned,
                directorOutputTransitions);
        UpdatingControllerEvaluationRecorder.recordTransitionReduction(
                "OTF-DUC 出力 pruning 削減率",
                "candidate transitions -> emitted transitions",
                directorCandidateTransitions,
                directorOutputTransitions);
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "OTF-DUC ブロック・棄却率",
                "safety violation child / generated child",
                safetyViolationChildCount,
                generatedChildCount,
                "children");
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "OTF-DUC ブロック・棄却率",
                "hotSwapOut guard block / generated child",
                finishUpdateGuardBlockedCount,
                generatedChildCount,
                "children");
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "OTF-DUC ブロック・棄却率",
                "pruned controllable transition / director candidate transition",
                prunedControllableTransitions,
                directorCandidateTransitions,
                "transitions");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "hotSwapOut 遷移数", finishUpdateTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "NC 接続成功数", ncConnectionSuccessCount, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC NC 接続統計", "NC mapping miss 数", ncConnectionMissCount, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC lookup table 統計", "new controller connection map entries", safeSize(newControllerConnectionMap), "件");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC lookup table 統計", "safety lookup table entries", countSafetyLookupEntries(), "件");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "component successor cache entries", safeSize(componentStepCache), "件");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "component successor cache hits", componentStepCacheHits, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "component successor cache misses", componentStepCacheMisses, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "component successor invalid cache hits", componentStepCacheInvalidHits, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "hotSwapOut guard cache entries", safeSize(finishUpdateGuardCache), "件");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "hotSwapOut guard cache hits", finishUpdateGuardCacheHits, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "hotSwapOut guard cache misses", finishUpdateGuardCacheMisses, "回");

        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "allChildrenGoal cache entries", safeSize(allChildrenGoalCache), "件");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "allChildrenGoal cache hits", allChildrenGoalCacheHits, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "allChildrenGoal cache misses", allChildrenGoalCacheMisses, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC cache 統計", "allChildrenGoal cache invalidations", allChildrenGoalCacheInvalidations, "回");
        UpdatingControllerEvaluationRecorder.recordValue(
                "OTF-DUC cache 統計","allChildrenGoal cache hit rate", formatRatio(allChildrenGoalCacheHits, allChildrenGoalCacheHits + allChildrenGoalCacheMisses));
    }

    protected void recordOtfExploredUpdateEventTransitionCounts() {
        long countStart = System.currentTimeMillis();
        UpdatePhaseEvaluator.TransitionCategoryCount counts =
                new UpdatePhaseEvaluator.TransitionCategoryCount();

        if (compostates != null) {
            for (CompostateDUC<State, Action> state : compostates.values()) {
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition
                        : state.getExploredChildren()) {
                    counts.add(transition.getFirst().toString());
                }
            }
        }

        long countTime = System.currentTimeMillis() - countStart;
        counts.record(
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_UPDATE_EVENTS,
                "DCS explored graph",
                countTime);
    }

    private void recordOtfExploredUpdatePhaseTransitionAnalysis() {
        long countStart = System.currentTimeMillis();
        UpdatePhaseEvaluator.PhaseTransitionAnalysis<CompostateDUC<State, Action>> analysis =
                new UpdatePhaseEvaluator.PhaseTransitionAnalysis<>();
        analysis.setInitialNode(initial);

        if (compostates != null) {
            for (CompostateDUC<State, Action> state : compostates.values()) {
                int fromPhase = UpdatePhaseEvaluator.phaseFromMarkingState(getMarkingState(state));
                analysis.addState(state, fromPhase);
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition
                        : state.getExploredChildren()) {
                    CompostateDUC<State, Action> child = transition.getSecond();
                    int toPhase = UpdatePhaseEvaluator.phaseFromMarkingState(getMarkingState(child));
                    analysis.addTransition(
                            state,
                            fromPhase,
                            child,
                            toPhase,
                            transition.getFirst().toString(),
                            transition.getFirst().isControllable());
                }
            }
        }

        analysis.record(
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_PHASE_DETAILS,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_PHASE_FLOW,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_COMPLETION_PATH,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_NORMAL_ACTIONS,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_NEXT_UPDATE_EVENT_DISTANCE,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_PROGRESS_FREE_CYCLES,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_ENABLED_UPDATE_EVENTS,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_UPDATE_ORDER_PATTERNS,
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED_NORMAL_RUN_LENGTH,
                "DCS explored graph",
                System.currentTimeMillis() - countStart);
    }

    protected void recordOtfProjectionSplitStats() {
        recordProjectionSplitStats("marking", idxMarking, idxMarking);
        recordProjectionSplitStats("oldController", idxOC, idxOC);
        recordProjectionSplitStats("mappingEnv", mappingStart, mappingEnd);
        recordProjectionSplitStats("oldSafety", oldSafeStart, oldSafeEnd);
        recordProjectionSplitStats("newSafety", newSafeStart, newSafeEnd);
        recordProjectionSplitStats("transitionRequirements", transReqStart, transReqEnd);
        recordProjectionSplitStats("synthesisMonitors", synthesisStart, synthesisEnd);

        recordProjectionSplitStatsPerComponent("mappingEnv", mappingStart, mappingEnd);
        recordProjectionSplitStatsPerComponent("oldSafety", oldSafeStart, oldSafeEnd);
        recordProjectionSplitStatsPerComponent("newSafety", newSafeStart, newSafeEnd);
        recordProjectionSplitStatsPerComponent("transitionRequirement", transReqStart, transReqEnd);
        recordProjectionSplitStatsPerComponent("synthesisMonitor", synthesisStart, synthesisEnd);
    }

    private void recordProjectionSplitStatsPerComponent(String labelPrefix, int start, int end) {
        if (!isValidProjectionRange(start, end)) {
            return;
        }
        for (int component = start; component <= end; component++) {
            recordProjectionSplitStats(
                    labelPrefix + "[" + (component - start) + "]",
                    component,
                    component);
        }
    }

    private void recordProjectionSplitStats(String projectionLabel, int start, int end) {
        if (!isValidProjectionRange(start, end) || compostates == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        Map<String, Long> statesByProjectionValue = new LinkedHashMap<>();
        long totalStates = 0;
        for (CompostateDUC<State, Action> state : compostates.values()) {
            List<State> vector = state.getStates();
            if (vector == null || end >= vector.size()) {
                continue;
            }
            String key = projectionSignature(vector, start, end);
            statesByProjectionValue.put(key, statesByProjectionValue.getOrDefault(key, 0L) + 1L);
            totalStates++;
        }

        long splitProjectionValues = 0;
        long maxStatesPerProjectionValue = 0;
        for (Long count : statesByProjectionValue.values()) {
            if (count > 1) {
                splitProjectionValues++;
            }
            maxStatesPerProjectionValue = Math.max(maxStatesPerProjectionValue, count);
        }
        long distinctProjectionValues = statesByProjectionValue.size();
        double averageStatesPerProjectionValue = distinctProjectionValues == 0
                ? 0.0
                : ((double) totalStates) / distinctProjectionValues;
        long countTime = System.currentTimeMillis() - countStart;

        UpdatingControllerEvaluationRecorder.recordProjectionSplitStats(
                "OTF-DUC projection 別分裂度",
                "DCS explored graph",
                projectionLabel,
                totalStates,
                distinctProjectionValues,
                splitProjectionValues,
                maxStatesPerProjectionValue,
                averageStatesPerProjectionValue,
                countTime);
    }

    private boolean isValidProjectionRange(int start, int end) {
        return start >= 0 && end >= start;
    }

    private String projectionSignature(List<State> vector, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i > start) {
                builder.append('|');
            }
            builder.append(vector.get(i));
        }
        return builder.toString();
    }

    private void recordOtfMarkingAndStatusBreakdown() {
        Map<String, Long> statusCounts = new TreeMap<>();
        long totalPhaseCountTime = 0;

        for (int phase : UpdatePhaseEvaluator.phaseOrder()) {
            long countStart = System.currentTimeMillis();
            long states = 0;
            long transitions = 0;

            if (compostates != null) {
                for (CompostateDUC<State, Action> state : compostates.values()) {
                    long marking = getMarkingState(state);
                    if (UpdatePhaseEvaluator.phaseFromMarkingState(marking) != phase) {
                        continue;
                    }
                    states++;
                    transitions += countExploredTransitions(state);

                    String statusKey = "phase="
                            + UpdatePhaseEvaluator.phaseLabelForMarkingState(marking)
                            + ":" + state.getStatus();
                    statusCounts.put(statusKey, statusCounts.getOrDefault(statusKey, 0L) + 1L);
                }
            }

            long countTime = System.currentTimeMillis() - countStart;
            totalPhaseCountTime += countTime;
            UpdatingControllerEvaluationRecorder.recordStateSpace(
                    UpdatePhaseEvaluator.SECTION_OTF_EXPLORED,
                    UpdatePhaseEvaluator.labelWithPhase("DCS explored graph", phase),
                    states,
                    transitions,
                    countTime,
                    UpdatePhaseEvaluator.phaseDescription(phase));
        }
        UpdatingControllerEvaluationRecorder.recordUpdatePhaseCountTimeTotal(
                UpdatePhaseEvaluator.SECTION_OTF_EXPLORED,
                "DCS explored graph",
                totalPhaseCountTime);

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
        long directorBuildStart = profileLogEnabled ? System.nanoTime() : 0L;

        // 1. 結果を格納する LTS の初期化
        // 状態 ID 0 を初期状態として設定（後に更新コントローラの初期 ID で上書き）
        LTSImpl<Long, Action> result = new LTSImpl<>(0L);

        // 探索用 alphabet を丸ごと登録すると、遷移図に出ない "_old" action まで
        // アルファベット拡張として出力されるため、出力対象 action だけを登録する。
        // result.addActions(alphabet.getActions());

        // 1. 全 action の中から "_old" を含まないものだけを登録する。
        long actionRegistrationStart = profileLogEnabled ? System.nanoTime() : 0L;
        for (Action a : alphabet.getActions()) {
            if (!a.toString().endsWith("_old")) {
                result.addAction(a);
                directorActionRegistrationCount++;
            }
        }

        @SuppressWarnings("unchecked")
        Set<Action> ncActions = (Set<Action>) newController.getActions();
        result.addActions(ncActions);
        directorActionRegistrationCount += ncActions.size();
        if (profileLogEnabled) {
            directorActionRegistrationNanos += System.nanoTime() - actionRegistrationStart;
        }

        // 状態 ID 管理：NC の状態 ID と衝突しないようにカウンターを管理
        long nextId = 0;

        //評価実験用
        long transferNCStart = System.currentTimeMillis();

        // ---------------------------------------------------------
        // ステップ 1: 新コントローラ (NC) の完全移設
        // ---------------------------------------------------------
        log("[Stitching] Pre-populating result LTS with New Controller states and transitions.");
        long ncStateTransferStart = profileLogEnabled ? System.nanoTime() : 0L;
        for (Long ncState : newController.getStates()) {
            result.addState(ncState);
            directorNcStatesTransferred++;
            if (ncState >= nextId) nextId = ncState + 1;
        }
        if (profileLogEnabled) {
            directorNcStateTransferNanos += System.nanoTime() - ncStateTransferStart;
        }
        long ncTransitionTransferStart = profileLogEnabled ? System.nanoTime() : 0L;
        for (Long ncState : newController.getStates()) {
            for (Pair<String, Long> trans : newController.getTransitions(ncState)) {
                @SuppressWarnings("unchecked")
                Action action = (Action) trans.getFirst();
                result.addTransition(ncState, action, trans.getSecond());
                directorNcTransitionsTransferred++;
            }
        }
        if (profileLogEnabled) {
            directorNcTransitionTransferNanos += System.nanoTime() - ncTransitionTransferStart;
        }

        transferNCTime = System.currentTimeMillis() - transferNCStart;

        // ---------------------------------------------------------
        // ステップ 2: pruning 後の更新コントローラグラフを一度収集する
        // ---------------------------------------------------------
        Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges = new LinkedHashMap<>();
        Map<RawNondetActionKey, CompostateDUC<State, Action>> rawNondetActionFirstTargets =
                new LinkedHashMap<>();
        Set<RawNondetActionKey> rawNondetActionCandidateKeys = new LinkedHashSet<>();
        List<CompostateDUC<State, Action>> reachableOrder = new ArrayList<>();
        Set<CompostateDUC<State, Action>> reached = new HashSet<>();
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();
        reached.add(initial);
        reachableOrder.add(initial);
        queue.add(initial);

        long directorEdgeCollectionStart = profileLogEnabled ? System.nanoTime() : 0L;
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
                    // hotSwapOut の場合は NC への接続を試みる
                    if (hAction.toString().equals(UpdateConstants.FINISH_UPDATE)
                            && isGoalProgressStateId(getMarkingState(child))) {
                        finishUpdateTransitions++;
                        directorNcConnectionAttempts++;

                        long ncConnectionStart = profileLogEnabled ? System.nanoTime() : 0L;

                        // 評価実験用
                        long stitchingNCStart = System.currentTimeMillis();

        
                        // ---------------------------------------------------------
                        // Debug-Stitch:
                        // 通常合成には不要な署名確認用ログなので debug 時だけ構築する
                        // ---------------------------------------------------------
                        if (debugLogEnabled) {
                            long debugPrepStart = profileLogEnabled ? System.nanoTime() : 0L;

                            List<State> vs = child.getStates();

                            log("  [Debug-Stitch] Available keys in NC map: " + newControllerConnectionMap.keySet());
                            log("  [Debug-Stitch] Constructing signature for child vector: " + vs);
                            
                            StringBuilder envPart = new StringBuilder();
                            for (int k = mappingStart; k <= mappingEnd; k++) {
                                Object mapEnvState = vs.get(k);
                                Integer mapEnvId = (mapEnvState instanceof Long) ? ((Long) mapEnvState).intValue() : (Integer) mapEnvState;
                                
                                Integer newEnvId = mappingMapEnvToNewEnv.get(k - mappingStart).get(mapEnvId);

                                envPart.append(newEnvId).append(",");
                            }
                            log("    -> Env part (translated): " + envPart);

                            StringBuilder safePart = new StringBuilder();
                            for (int k = newSafeStart; k <= newSafeEnd; k++) {
                                safePart.append(vs.get(k)).append(",");
                            }
                            log("    -> Safe part (raw from vector): " + safePart);
                            
                            if (profileLogEnabled) {
                                directorNcConnectionDebugPrepNanos += System.nanoTime() - debugPrepStart;
                            }
                        }
                        // ---------------------------------------------------------
                        // ここから下は通常合成に必要な処理
                        // hotSwapOut の接続先 NC 状態を求める本体
                        // ---------------------------------------------------------
                        long signatureStart = profileLogEnabled ? System.nanoTime() : 0L;
                        
                        String signature = generateNCSignature(child);
                        directorNcConnectionSignatureCalls++;
                        
                        if (profileLogEnabled) {
                            directorNcConnectionSignatureNanos += System.nanoTime() - signatureStart;
                        }
                        
                        Long ncStateId = (signature != null) ? newControllerConnectionMap.get(signature) : null;
                        
                        if (ncStateId != null) {
                            ncConnectionSuccessCount++;
                            
                            if (debugLogEnabled) {
                                log("  [Stitch] Connecting " + current.getStates() + " --(hotSwapOut)--> NC State " + ncStateId);
                            }

                            DirectorEdge edge = new DirectorEdge(toOutputAction(hAction), child, ncStateId);
                            directorEdges.computeIfAbsent(current, k -> new ArrayList<>()).add(edge);
                            directorEdgesCollected++;
                        } else {
                            ncConnectionMissCount++;
                            // これは単なるdebugではなく、出力controller構築不能な異常なので残す
                            System.err.println("!!! [Stitch-Error] No NC state mapping found for signature: " + signature);
                            System.err.println("    Target child vector: " + child.getStates());
                            
                            throw new IllegalStateException("Missing NC mapping for reached state during stitching.");
                        }
                        // 評価実験用
                        stitchingNCTime += (System.currentTimeMillis() - stitchingNCStart);
                        if (profileLogEnabled) {
                            directorNcConnectionNanos += System.nanoTime() - ncConnectionStart;
                        }
                    } else {
                        // 通常の遷移
                        DirectorEdge edge = new DirectorEdge(toOutputAction(hAction), child, null);
                        directorEdges.computeIfAbsent(current, k -> new ArrayList<>()).add(edge);
                        recordRawNondetActionCandidate(
                                rawNondetActionFirstTargets,
                                rawNondetActionCandidateKeys,
                                current,
                                edge);
                        
                        directorEdgesCollected++;
                        
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
        directorReachableStates = reachableOrder.size();
        if (profileLogEnabled) {
            directorEdgeCollectionNanos += System.nanoTime() - directorEdgeCollectionStart;
        }

        // ---------------------------------------------------------
        // ステップ 3: 出力時マージを準備する。
        // non-pre-update 側は raw concrete state 単位に保ち、必要なら
        // 旧コントローラ相当状態の簡単マージと belief repair を行う。
        // ---------------------------------------------------------
        long preUpdateMergeStart = profileLogEnabled ? System.nanoTime() : 0L;
        Map<CompostateDUC<State, Action>, Integer> nonPreUpdateClasses;
        Map<CompostateDUC<State, Action>, Integer> fallbackPreUpdateClasses;
        try {
            nonPreUpdateClasses = computeRawNonPreUpdateOutputClasses(reachableOrder);
            if (preUpdateSimpleMergeEnabled) {
                fallbackPreUpdateClasses = computePreUpdateOutputMergeClasses(
                        reachableOrder, directorEdges, nonPreUpdateClasses);
            } else {
                fallbackPreUpdateClasses = computeRawPreUpdateOutputClasses(reachableOrder);
                if (debugLogEnabled) {
                    log("  [Director-Merge] pre-update simple merge disabled by -Dotfduc.simple.merge=false");
                }
            }
        } finally {
            if (profileLogEnabled) {
                directorPreUpdateMergeNanos += System.nanoTime() - preUpdateMergeStart;
            }
            endStrategy1NormalOtfSimpleMergeMeasurement();
        }
        recordSimpleMergeSplitStats(reachableOrder, fallbackPreUpdateClasses);

        BeliefRepairResult beliefRepairResult = new BeliefRepairResult();
        if (beliefRepairEnabled) {
            long beliefRepairStart = System.nanoTime();
            beginStrategy1RepairMeasurement();
            try {
                Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges =
                        collectRawDirectorEdges();
                beliefRepairResult = repairPreUpdateBeliefs(
                        reachableOrder, directorEdges, fallbackPreUpdateClasses, rawDirectorEdges);
            } finally {
                directorBeliefRepairNanos += System.nanoTime() - beliefRepairStart;
                endStrategy1RepairMeasurement();
            }
        } else if (debugLogEnabled) {
            log("  [Belief-Repair] disabled by -Dotfduc.belief.repair=false");
        }

        Map<CompostateDUC<State, Action>, Integer> preUpdateClasses =
                buildPreUpdateClassesAfterBeliefRepair(
                        reachableOrder, fallbackPreUpdateClasses, beliefRepairResult);
        recordPreUpdateOutputStateOverhead(reachableOrder, preUpdateClasses);

        Map<CompostateDUC<State, Action>, Integer> outputClasses =
                buildOutputClasses(reachableOrder, preUpdateClasses, nonPreUpdateClasses);
        Set<CompostateDUC<State, Action>> concreteReachableAfterPreUpdateBeliefRepair = null;
        if (beliefRepairEnabled) {
            concreteReachableAfterPreUpdateBeliefRepair =
                    computeConcreteReachableAfterBeliefRepair(initial, directorEdges, beliefRepairResult);
            if (debugLogEnabled) {
                log("  [Nondet-Belief-Repair] concrete reachable filter after pre-update repair: "
                        + concreteReachableAfterPreUpdateBeliefRepair.size()
                        + "/" + reachableOrder.size());
            }
        }

        NondetActionBeliefRepairResult nondetBeliefRepairResult = new NondetActionBeliefRepairResult();
        if (nondeterministicActionBeliefRepairEnabled) {
            long nondetRepairStart = System.nanoTime();
            try {
                if (rawNondetActionCandidateKeys.isEmpty()) {
                    if (debugLogEnabled) {
                        log("  [Nondet-Belief-Repair] skipped: no raw nondeterministic action candidates");
                    }
                } else {
                    Map<NondetActionRepairKey, NondetActionBeliefRepairPlan> nondetCandidates =
                            collectNondetActionBeliefRepairCandidates(
                                    rawNondetActionCandidateKeys,
                                    directorEdges,
                                    outputClasses,
                                    concreteReachableAfterPreUpdateBeliefRepair);
                    if (nondetCandidates.isEmpty()) {
                        if (debugLogEnabled) {
                            log("  [Nondet-Belief-Repair] skipped: rawCandidates="
                                    + rawNondetActionCandidateKeys.size()
                                    + ", exactCandidates=0 after output-class verification");
                        }
                    } else {
                        Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges =
                                collectRawDirectorEdges(false);
                        nondetBeliefRepairResult = repairNondeterministicActionBeliefs(
                                nondetCandidates, rawDirectorEdges);
                    }
                }
            } finally {
                directorNondetBeliefRepairNanos += System.nanoTime() - nondetRepairStart;
            }
        } else if (debugLogEnabled) {
            log("  [Nondet-Belief-Repair] disabled by -Dotfduc.nondet.belief.repair=false");
        }

        Map<CompostateDUC<State, Action>, Long> ids = new HashMap<>();
        Map<Integer, Long> outputClassIds = new HashMap<>();
        Map<BeliefNode, Long> beliefIds = new IdentityHashMap<>();
        long idAssignmentStart = profileLogEnabled ? System.nanoTime() : 0L;
        for (CompostateDUC<State, Action> state : reachableOrder) {
            Integer classId = outputClasses.get(state);
            if (classId == null) {
                throw new IllegalStateException("Missing output class for reached DUC state.");
            }
            Long id = outputClassIds.get(classId);
            if (id == null) {
                id = nextId++;
                outputClassIds.put(classId, id);
                result.addState(id);
                directorOutputStatesAssigned++;
            }
            ids.put(state, id);
        }
        for (BeliefRepairPlan plan : beliefRepairResult.successPlans.values()) {
            for (BeliefNode node : plan.nodes) {
                if (!beliefIds.containsKey(node)) {
                    Long id = nextId++;
                    beliefIds.put(node, id);
                    result.addState(id);
                    directorOutputStatesAssigned++;
                }
            }
        }
        for (NondetActionBeliefRepairPlan plan : nondetBeliefRepairResult.successPlans) {
            for (BeliefNode node : plan.beliefPlan.nodes) {
                if (!beliefIds.containsKey(node)) {
                    Long id = nextId++;
                    beliefIds.put(node, id);
                    result.addState(id);
                    directorOutputStatesAssigned++;
                }
            }
        }
        result.setInitialState(ids.get(initial));
        if (profileLogEnabled) {
            directorIdAssignmentNanos += System.nanoTime() - idAssignmentStart;
        }

        long transitionEmissionStart = profileLogEnabled ? System.nanoTime() : 0L;
        for (CompostateDUC<State, Action> source : reachableOrder) {
            Long sourceId = ids.get(source);
            List<DirectorEdge> edges = directorEdges.get(source);
            if (edges == null) {
                continue;
            }
            for (DirectorEdge edge : edges) {
                if (isBeliefRepairReplacedDirectorEdge(source, edge, beliefRepairResult)) {
                    // belief 再探索に成功した旧状態では、具象 m=0 状態ごとの
                    // hotSwapIn を出さず、1 本の hotSwapIn から belief 状態へ入る。
                    continue;
                }
                if (isNondetActionBeliefRepairReplacedDirectorEdge(
                        source, edge, outputClasses, nondetBeliefRepairResult)) {
                    continue;
                }
                Long targetId = edge.isNewControllerConnection()
                        ? edge.ncTargetId
                        : ids.get(edge.child);
                if (targetId == null) {
                    throw new IllegalStateException("Missing output state id for director edge target.");
                }
                directorTransitionEmissionAttempts++;
                if (result.addTransition(sourceId, edge.outputAction, targetId)) {
                    directorOutputTransitions++;
                }
            }
        }
        emitBeliefRepairTransitions(result, ids, beliefIds, beliefRepairResult);
        emitNondetActionBeliefRepairTransitions(
                result, ids, beliefIds, outputClasses, nondetBeliefRepairResult);
        if (profileLogEnabled) {
            directorTransitionEmissionNanos += System.nanoTime() - transitionEmissionStart;
            directorTraversalNanos += System.nanoTime() - directorBuildStart;
        }

        statistics.setControllerUsedStates(result.getStates().size());
        return result;
    }

    private boolean isPreUpdateOutputState(CompostateDUC<State, Action> state) {
        return getMarkingState(state) == 0;
    }

    private long countOldControllerStates() {
        if (ltss == null || idxOC < 0 || idxOC >= ltss.size() || ltss.get(idxOC) == null) {
            return -1;
        }
        return ltss.get(idxOC).getStates().size();
    }

    @SuppressWarnings("unchecked")
    private Action toOutputAction(HAction<State, Action> hAction) {
        return (Action) hAction.toString().replace("_old", "");
    }

    private void recordRawNondetActionCandidate(
            Map<RawNondetActionKey, CompostateDUC<State, Action>> firstTargets,
            Set<RawNondetActionKey> candidateKeys,
            CompostateDUC<State, Action> source,
            DirectorEdge edge) {

        if (isPreUpdateOutputState(source) || edge.isNewControllerConnection() || edge.child == null) {
            return;
        }
        String actionName = edge.outputAction.toString();
        if (actionName.equals(UpdateConstants.BEGIN_UPDATE)
                || actionName.equals(UpdateConstants.FINISH_UPDATE)) {
            return;
        }

        RawNondetActionKey key = new RawNondetActionKey(source, actionName);
        CompostateDUC<State, Action> firstTarget = firstTargets.get(key);
        if (firstTarget == null) {
            firstTargets.put(key, edge.child);
        } else if (firstTarget != edge.child) {
            candidateKeys.add(key);
        }
    }

    private Map<CompostateDUC<State, Action>, Integer> computeRawNonPreUpdateOutputClasses(
            List<CompostateDUC<State, Action>> reachableOrder) {

        Map<CompostateDUC<State, Action>, Integer> classOf = new HashMap<>();
        int nextClassId = 0;
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                classOf.put(state, nextClassId++);
            }
        }
        return classOf;
    }

    /**
     * 探索では new safety fluent の履歴を保持するが、出力上で同じ旧コントローラ
     * 状態かつ同じ遷移構造を持つ m=0 状態は同一状態としてまとめる。
     *
     * 初期分割は旧コントローラ成分で行い、hotSwapIn と旧コントローラ遷移を含む
     * 出力遷移の行き先が同じ同値クラスになるまで partition refinement する。
     */
    private Map<CompostateDUC<State, Action>, Integer> computePreUpdateOutputMergeClasses(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds) {

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
            preUpdateOutputClassStates = 0;
            preUpdateOutputMergeRemovedStates = 0;
            return classOf;
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
        preUpdateOutputClassStates = nextClassId;
        preUpdateOutputMergeRemovedStates = preUpdateStates.size() - nextClassId;
        if (debugLogEnabled && preUpdateOutputMergeRemovedStates > 0) {
            log("  [Director-Merge] merged pre-update output states: raw="
                    + preUpdateOutputMergedStates
                    + ", classes=" + nextClassId
                    + ", removed=" + preUpdateOutputMergeRemovedStates);
        }
        logPreUpdateOutputMergeProof(preUpdateStates, classOf, directorEdges, nonPreUpdateIds, nextClassId);
        return classOf;
    }

    private Map<CompostateDUC<State, Action>, Integer> computeRawPreUpdateOutputClasses(
            List<CompostateDUC<State, Action>> reachableOrder) {

        Map<CompostateDUC<State, Action>, Integer> classOf = new HashMap<>();
        int nextClassId = 0;
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }
            classOf.put(state, nextClassId++);
        }

        preUpdateOutputMergedStates = classOf.size();
        preUpdateOutputClassStates = classOf.size();
        preUpdateOutputMergeRemovedStates = 0;
        return classOf;
    }

    private void logPreUpdateOutputMergeProof(
            List<CompostateDUC<State, Action>> preUpdateStates,
            Map<CompostateDUC<State, Action>, Integer> finalClassOf,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds,
            int finalClassCount) {

        if (!debugLogEnabled || !mergeProofLogEnabled) {
            return;
        }

        Map<State, List<CompostateDUC<State, Action>>> statesByOldController = new LinkedHashMap<>();
        for (CompostateDUC<State, Action> state : preUpdateStates) {
            State oldControllerState = state.getStates().get(idxOC);
            statesByOldController.computeIfAbsent(oldControllerState, k -> new ArrayList<>()).add(state);
        }

        int splitOldControllerStates = 0;
        int duplicatedRawStates = 0;
        int duplicatedFinalClasses = 0;
        for (List<CompostateDUC<State, Action>> states : statesByOldController.values()) {
            List<Integer> finalClasses = collectFinalClasses(states, finalClassOf);
            if (states.size() > 1 || finalClasses.size() > 1) {
                splitOldControllerStates++;
            }
            duplicatedRawStates += Math.max(0, states.size() - 1);
            duplicatedFinalClasses += Math.max(0, finalClasses.size() - 1);
        }

        log("  [PreUpdate-MergeProof] rawPreUpdateStates=" + preUpdateStates.size()
                + ", oldControllerStates=" + statesByOldController.size()
                + ", finalClasses=" + finalClassCount
                + ", rawStatesBeyondOldController=" + duplicatedRawStates
                + ", finalClassesBeyondOldController=" + duplicatedFinalClasses
                + ", splitOldControllerStates=" + splitOldControllerStates);

        for (Map.Entry<State, List<CompostateDUC<State, Action>>> entry : statesByOldController.entrySet()) {
            State oldControllerState = entry.getKey();
            List<CompostateDUC<State, Action>> states = entry.getValue();
            List<Integer> finalClasses = collectFinalClasses(states, finalClassOf);

            if (states.size() == 1 && finalClasses.size() == 1) {
                continue;
            }

            log("  [PreUpdate-MergeProof] oldControllerState=" + oldControllerState
                    + ", rawStates=" + states.size()
                    + ", finalClasses=" + finalClasses
                    + ", varyingComponents=" + describeVaryingComponents(states));

            Map<Integer, List<CompostateDUC<State, Action>>> statesByFinalClass = new LinkedHashMap<>();
            for (CompostateDUC<State, Action> state : states) {
                Integer classId = finalClassOf.get(state);
                statesByFinalClass.computeIfAbsent(classId, k -> new ArrayList<>()).add(state);
            }

            for (Map.Entry<Integer, List<CompostateDUC<State, Action>>> classEntry : statesByFinalClass.entrySet()) {
                Integer classId = classEntry.getKey();
                List<CompostateDUC<State, Action>> members = classEntry.getValue();
                CompostateDUC<State, Action> representative = members.get(0);
                List<String> signature = buildPreUpdateOutputSignature(
                        representative, directorEdges, finalClassOf, nonPreUpdateIds);

                log("    [PreUpdate-MergeProof-Class] class=" + classId
                        + ", members=" + members.size()
                        + ", outputActions=" + describeOutputActions(members, directorEdges)
                        + ", hotSwapInTargets=" + describeActionTargets(
                                members, UpdateConstants.BEGIN_UPDATE, directorEdges, finalClassOf, nonPreUpdateIds));
                log("      representative=" + summarizeStateForDiagnostics(representative));
                log("      signature=" + signature);
            }
        }
    }

    private List<Integer> collectFinalClasses(
            List<CompostateDUC<State, Action>> states,
            Map<CompostateDUC<State, Action>, Integer> finalClassOf) {

        List<Integer> finalClasses = new ArrayList<>();
        for (CompostateDUC<State, Action> state : states) {
            Integer classId = finalClassOf.get(state);
            if (!finalClasses.contains(classId)) {
                finalClasses.add(classId);
            }
        }
        return finalClasses;
    }

    private String describeVaryingComponents(List<CompostateDUC<State, Action>> states) {
        if (states.isEmpty()) {
            return "none";
        }

        int componentCount = states.get(0).getStates().size();
        List<String> variations = new ArrayList<>();
        for (int component = 0; component < componentCount; component++) {
            List<State> values = new ArrayList<>();
            for (CompostateDUC<State, Action> state : states) {
                State value = state.getStates().get(component);
                if (!values.contains(value)) {
                    values.add(value);
                }
            }
            if (values.size() > 1) {
                variations.add(componentName(component) + "=" + limitedValues(values, 6));
            }
        }

        if (variations.isEmpty()) {
            return "none";
        }

        int maxComponents = 16;
        if (variations.size() <= maxComponents) {
            return variations.toString();
        }
        List<String> head = new ArrayList<>(variations.subList(0, maxComponents));
        head.add("... +" + (variations.size() - maxComponents) + " components");
        return head.toString();
    }

    private String limitedValues(List<State> values, int maxValues) {
        if (values.size() <= maxValues) {
            return values.toString();
        }
        List<String> limited = new ArrayList<>();
        for (int i = 0; i < maxValues; i++) {
            limited.add(String.valueOf(values.get(i)));
        }
        limited.add("... +" + (values.size() - maxValues) + " values");
        return limited.toString();
    }

    private String componentName(int component) {
        if (component == idxMarking) {
            return "marking[" + component + "]";
        }
        if (component == idxOC) {
            return "oldController[" + component + "]";
        }
        if (inComponentRange(component, mappingStart, mappingEnd)) {
            return "mapEnv[" + component + "]";
        }
        if (inComponentRange(component, oldSafeStart, oldSafeEnd)) {
            return "oldSafe[" + component + "]";
        }
        if (inComponentRange(component, newSafeStart, newSafeEnd)) {
            return "newSafe[" + component + "]";
        }
        if (inComponentRange(component, transReqStart, transReqEnd)) {
            return "transReq[" + component + "]";
        }
        if (inComponentRange(component, synthesisStart, synthesisEnd)) {
            return "synthesis[" + component + "]";
        }
        return "component[" + component + "]";
    }

    private boolean inComponentRange(int component, int start, int end) {
        return start >= 0 && end >= start && component >= start && component <= end;
    }

    private String describeOutputActions(
            List<CompostateDUC<State, Action>> states,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges) {

        List<String> actions = new ArrayList<>();
        for (CompostateDUC<State, Action> state : states) {
            List<DirectorEdge> edges = directorEdges.get(state);
            if (edges == null) {
                continue;
            }
            for (DirectorEdge edge : edges) {
                String action = edge.outputAction.toString();
                if (!actions.contains(action)) {
                    actions.add(action);
                }
            }
        }
        Collections.sort(actions);
        return actions.isEmpty() ? "none" : actions.toString();
    }

    private String describeActionTargets(
            List<CompostateDUC<State, Action>> states,
            String actionName,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateIds) {

        List<String> targets = new ArrayList<>();
        for (CompostateDUC<State, Action> state : states) {
            List<DirectorEdge> edges = directorEdges.get(state);
            if (edges == null) {
                continue;
            }
            for (DirectorEdge edge : edges) {
                if (!edge.outputAction.toString().equals(actionName)) {
                    continue;
                }
                String target = outputMergeTargetToken(edge, preUpdateClasses, nonPreUpdateIds);
                if (!targets.contains(target)) {
                    targets.add(target);
                }
            }
        }
        Collections.sort(targets);
        return targets.isEmpty() ? "none" : targets.toString();
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

    private Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> collectRawDirectorEdges() {
        return collectRawDirectorEdges(true);
    }

    private Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> collectRawDirectorEdges(boolean logSummary) {
        Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawEdges = new LinkedHashMap<>();
        if (compostates == null) {
            return rawEdges;
        }

        long rawTransitions = 0;
        for (CompostateDUC<State, Action> state : compostates.values()) {
            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> transition : state.getExploredChildren()) {
                RawDirectorEdge edge = new RawDirectorEdge(transition.getFirst(), transition.getSecond());
                rawEdges.computeIfAbsent(state, k -> new ArrayList<>()).add(edge);
                rawTransitions++;
            }
        }
        otfPeakStates = Math.max(otfPeakStates, compostates.size());
        otfPeakTrans = Math.max(otfPeakTrans, (int) Math.min(Integer.MAX_VALUE, rawTransitions));
        if (debugLogEnabled && logSummary) {
            log("  [Belief-Repair] collected raw explored graph: states="
                    + rawEdges.size() + ", transitions=" + rawTransitions);
        }
        return rawEdges;
    }

    private long countRawDirectorTransitions(
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        long transitions = 0;
        if (rawDirectorEdges == null) {
            return transitions;
        }
        for (List<RawDirectorEdge> edges : rawDirectorEdges.values()) {
            if (edges != null) {
                transitions += edges.size();
            }
        }
        return transitions;
    }

    private long countCurrentExploredTransitions() {
        long transitions = 0;
        if (compostates == null) {
            return transitions;
        }
        for (CompostateDUC<State, Action> state : compostates.values()) {
            for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> ignored : state.getExploredChildren()) {
                transitions++;
            }
        }
        return transitions;
    }

    private BeliefRepairResourceLimits createBeliefRepairResourceLimits(
            int rawPreStates,
            int reachableMapValuations) {
        int newControllerStates = newController == null || newController.getStates() == null
                ? 0
                : newController.getStates().size();

        int dynamicBeliefNodes = maxPositive(
                beliefRepairMinBeliefNodes,
                safeMultiplyToInt(beliefRepairBeliefNodesPerNewControllerState, newControllerStates),
                safeMultiplyToInt(beliefRepairBeliefNodesPerRawPreState, rawPreStates),
                safeMultiplyToInt(
                        beliefRepairBeliefNodesPerReachableMapValuation,
                        reachableMapValuations));
        int maxBeliefNodes = Math.min(beliefRepairAbsoluteMaxStates, dynamicBeliefNodes);

        int maxAdditionalConcreteStates = maxPositive(
                beliefRepairMinAdditionalConcreteStates,
                safeMultiplyToInt(beliefRepairAdditionalConcretePerNewControllerState, newControllerStates),
                safeMultiplyToInt(beliefRepairAdditionalConcretePerRawPreState, rawPreStates));

        int maxAdditionalTransitions = maxPositive(
                beliefRepairMinAdditionalTransitions,
                safeMultiplyToInt(beliefRepairAdditionalTransitionsPerNewControllerState, newControllerStates),
                safeMultiplyToInt(beliefRepairAdditionalTransitionsPerRawPreState, rawPreStates));

        return new BeliefRepairResourceLimits(
                maxBeliefNodes,
                maxAdditionalConcreteStates,
                maxAdditionalTransitions,
                beliefRepairMaxTimeMs,
                reachableMapValuations);
    }

    private int maxPositive(int first, int second, int third) {
        return Math.max(1, Math.max(first, Math.max(second, third)));
    }

    private int maxPositive(int first, int second, int third, int fourth) {
        return Math.max(1, Math.max(Math.max(first, second), Math.max(third, fourth)));
    }

    private int safeMultiplyToInt(int left, int right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        long value = (long) left * (long) right;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private int countReachableMapValuations(
            Iterable<CompostateDUC<State, Action>> seeds,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        if (seeds == null || mappingStart < 0 || mappingEnd < mappingStart) {
            return 0;
        }

        Set<CompostateDUC<State, Action>> visitedStates = new HashSet<>();
        Set<List<State>> mapValuations = new HashSet<>();
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();

        for (CompostateDUC<State, Action> seed : seeds) {
            if (seed != null && visitedStates.add(seed)) {
                queue.add(seed);
            }
        }

        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> state = queue.remove();
            List<State> vector = state.getStates();
            if (vector != null && mappingEnd < vector.size()) {
                List<State> mapValuation = new ArrayList<>();
                for (int k = mappingStart; k <= mappingEnd; k++) {
                    mapValuation.add(vector.get(k));
                }
                mapValuations.add(mapValuation);
            }

            List<RawDirectorEdge> edges = rawDirectorEdges == null ? null : rawDirectorEdges.get(state);
            if (edges == null) {
                continue;
            }
            for (RawDirectorEdge edge : edges) {
                if (edge.child != null && visitedStates.add(edge.child)) {
                    queue.add(edge.child);
                }
            }
        }

        return mapValuations.size();
    }

    private void refreshBeliefRepairResourceLimits(
            BeliefRepairPlan plan,
            Iterable<CompostateDUC<State, Action>> seeds,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        int reachableMapValuations = countReachableMapValuations(seeds, rawDirectorEdges);
        plan.reachableMapValuations = Math.max(plan.reachableMapValuations, reachableMapValuations);
        plan.resourceLimits = createBeliefRepairResourceLimits(
                plan.preUpdateStates.size(),
                plan.reachableMapValuations);
    }

    private BeliefRepairResult repairPreUpdateBeliefs(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> fallbackPreUpdateClasses,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        BeliefRepairResult result = new BeliefRepairResult();
        Map<State, List<CompostateDUC<State, Action>>> statesByOldController =
                groupPreUpdateStatesByOldController(reachableOrder);

        if (debugLogEnabled) {
            log("  [Belief-Repair] start: oldControllerGroups="
                    + statesByOldController.size()
                    + ", absoluteMaxBeliefNodes=" + beliefRepairAbsoluteMaxStates
                    + ", resourceLimitFormula="
                    + "beliefNodes=max(" + beliefRepairMinBeliefNodes
                    + ", " + beliefRepairBeliefNodesPerNewControllerState + "*newControllerStates"
                    + ", " + beliefRepairBeliefNodesPerRawPreState + "*rawPreStates"
                    + ", " + beliefRepairBeliefNodesPerReachableMapValuation + "*reachableMapValuations)"
                    + ", additionalConcrete=max(" + beliefRepairMinAdditionalConcreteStates
                    + ", " + beliefRepairAdditionalConcretePerNewControllerState + "*newControllerStates"
                    + ", " + beliefRepairAdditionalConcretePerRawPreState + "*rawPreStates)"
                    + ", additionalTransitions=max(" + beliefRepairMinAdditionalTransitions
                    + ", " + beliefRepairAdditionalTransitionsPerNewControllerState + "*newControllerStates"
                    + ", " + beliefRepairAdditionalTransitionsPerRawPreState + "*rawPreStates)"
                    + ", maxTimeMs=" + beliefRepairMaxTimeMs);
        }

        for (Map.Entry<State, List<CompostateDUC<State, Action>>> entry : statesByOldController.entrySet()) {
            State oldControllerState = entry.getKey();
            List<CompostateDUC<State, Action>> preUpdateStates = entry.getValue();
            List<Integer> fallbackClasses = collectFinalClasses(preUpdateStates, fallbackPreUpdateClasses);

            // 既存の簡単マージで既に 1 状態へまとまる場合は belief 再探索不要。
            if (fallbackClasses.size() <= 1) {
                continue;
            }

            beliefRepairCandidateGroups++;
            BeliefRepairPlan plan = runBeliefRepairForOldState(
                    oldControllerState,
                    preUpdateStates,
                    fallbackClasses,
                    directorEdges,
                    rawDirectorEdges);

            if (plan.success) {
                beliefRepairSuccessGroups++;
                result.addSuccess(plan);
            } else {
                beliefRepairFallbackGroups++;
                result.addFallback(plan);
            }
        }

        if (debugLogEnabled) {
            log("  [Belief-Repair] finished: candidates=" + beliefRepairCandidateGroups
                    + ", success=" + beliefRepairSuccessGroups
                    + ", fallback=" + beliefRepairFallbackGroups
                    + ", generatedBeliefNodes=" + beliefRepairGeneratedNodes);
        }
        return result;
    }

    private Map<State, List<CompostateDUC<State, Action>>> groupPreUpdateStatesByOldController(
            List<CompostateDUC<State, Action>> reachableOrder) {

        Map<State, List<CompostateDUC<State, Action>>> statesByOldController = new LinkedHashMap<>();
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }
            State oldControllerState = state.getStates().get(idxOC);
            statesByOldController.computeIfAbsent(oldControllerState, k -> new ArrayList<>()).add(state);
        }
        return statesByOldController;
    }

    private BeliefRepairPlan runBeliefRepairForOldState(
            State oldControllerState,
            List<CompostateDUC<State, Action>> preUpdateStates,
            List<Integer> fallbackClasses,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        BeliefRepairPlan plan = new BeliefRepairPlan(oldControllerState, preUpdateStates, fallbackClasses);
        plan.initialConcreteStateCount = compostates == null ? 0 : compostates.size();
        plan.initialRawTransitionCount = countRawDirectorTransitions(rawDirectorEdges);
        plan.repairStartNanos = System.nanoTime();

        Set<CompostateDUC<State, Action>> beginTargets = new LinkedHashSet<>();
        CompostateDUC<State, Action> missingBeginUpdateState = null;

        for (CompostateDUC<State, Action> preUpdateState : preUpdateStates) {
            List<RawDirectorEdge> edges = rawDirectorEdges.get(preUpdateState);
            boolean foundBeginUpdate = false;
            if (edges != null) {
                for (RawDirectorEdge edge : edges) {
                    if (!edge.actionName.equals(UpdateConstants.BEGIN_UPDATE)) {
                        continue;
                    }
                    if (!edge.hAction.isControllable()) {
                        continue;
                    }
                    if (!isSafeWinningBeliefChild(edge.child)) {
                        continue;
                    }
                    foundBeginUpdate = true;
                    beginTargets.add(edge.child);
                    if (plan.beginUpdateAction == null) {
                        plan.beginUpdateAction = edge.outputAction;
                    }
                }
            }

            if (!foundBeginUpdate) {
                missingBeginUpdateState = preUpdateState;
                break;
            }
        }

        refreshBeliefRepairResourceLimits(
                plan,
                beginTargets.isEmpty() ? preUpdateStates : beginTargets,
                rawDirectorEdges);

        if (debugLogEnabled) {
            log("  [Belief-Repair] candidate oldControllerState=" + oldControllerState
                    + ", rawPreStates=" + preUpdateStates.size()
                    + ", fallbackClasses=" + fallbackClasses
                    + ", varyingComponents=" + describeVaryingComponents(preUpdateStates)
                    + ", outputActions=" + describeOutputActions(preUpdateStates, directorEdges)
                    + ", reachableMapValuations=" + plan.reachableMapValuations
                    + ", limits=" + plan.resourceLimits);
        }

        if (missingBeginUpdateState != null) {
            plan.fail("hotSwapIn で到達できる GOAL 子がない pre-update 状態がある: "
                    + summarizeStateForDiagnostics(missingBeginUpdateState));
            logBeliefRepairPlan(plan);
            return plan;
        }

        return runBeliefRepairLoop(
                plan,
                preUpdateStates,
                beginTargets,
                rawDirectorEdges,
                "belief graph 上で hotSwapOut まで到達可能",
                "belief graph 上で全候補に共通する勝ち更新戦略を証明できない"
                        + "（追加展開しても新しい遷移を発見できない）");
    }

    private NondetActionBeliefRepairResult repairNondeterministicActionBeliefs(
            Map<NondetActionRepairKey, NondetActionBeliefRepairPlan> candidates,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        NondetActionBeliefRepairResult result = new NondetActionBeliefRepairResult();
        if (debugLogEnabled) {
            log("  [Nondet-Belief-Repair] start: candidates=" + candidates.size());
        }

        for (NondetActionBeliefRepairPlan candidate : candidates.values()) {
            if (candidate.targetClasses.size() <= 1) {
                continue;
            }

            nondetBeliefRepairCandidateGroups++;
            runNondetActionBeliefRepairPlan(candidate, rawDirectorEdges);
            if (candidate.beliefPlan.success) {
                nondetBeliefRepairSuccessGroups++;
                result.addSuccess(candidate);
            } else {
                nondetBeliefRepairFallbackGroups++;
                result.addFallback(candidate);
            }
        }

        if (debugLogEnabled) {
            log("  [Nondet-Belief-Repair] finished: candidates="
                    + nondetBeliefRepairCandidateGroups
                    + ", success=" + nondetBeliefRepairSuccessGroups
                    + ", fallback=" + nondetBeliefRepairFallbackGroups);
        }
        return result;
    }

    private Set<CompostateDUC<State, Action>> computeConcreteReachableAfterBeliefRepair(
            CompostateDUC<State, Action> initialState,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            BeliefRepairResult beliefRepairResult) {

        Set<CompostateDUC<State, Action>> reached = new HashSet<>();
        Deque<CompostateDUC<State, Action>> queue = new ArrayDeque<>();
        reached.add(initialState);
        queue.add(initialState);

        while (!queue.isEmpty()) {
            CompostateDUC<State, Action> current = queue.remove();
            List<DirectorEdge> edges = directorEdges.get(current);
            if (edges == null) {
                continue;
            }

            for (DirectorEdge edge : edges) {
                if (isBeliefRepairReplacedDirectorEdge(current, edge, beliefRepairResult)
                        || edge.isNewControllerConnection()
                        || edge.child == null) {
                    continue;
                }
                if (reached.add(edge.child)) {
                    queue.add(edge.child);
                }
            }
        }

        return reached;
    }

    private Map<NondetActionRepairKey, NondetActionBeliefRepairPlan> collectNondetActionBeliefRepairCandidates(
            Set<RawNondetActionKey> rawNondetCandidateKeys,
            Map<CompostateDUC<State, Action>, List<DirectorEdge>> directorEdges,
            Map<CompostateDUC<State, Action>, Integer> outputClasses,
            Set<CompostateDUC<State, Action>> concreteReachableFilter) {

        Map<NondetActionRepairKey, NondetActionBeliefRepairPlan> candidates = new LinkedHashMap<>();
        for (RawNondetActionKey rawKey : rawNondetCandidateKeys) {
            CompostateDUC<State, Action> source = rawKey.source;
            if (isPreUpdateOutputState(source)) {
                continue;
            }
            if (concreteReachableFilter != null && !concreteReachableFilter.contains(source)) {
                continue;
            }
            Integer sourceClass = outputClasses.get(source);
            if (sourceClass == null) {
                continue;
            }

            List<DirectorEdge> edges = directorEdges.get(source);
            if (edges == null) {
                continue;
            }

            for (DirectorEdge edge : edges) {
                if (edge.isNewControllerConnection() || edge.child == null) {
                    continue;
                }
                String actionName = edge.outputAction.toString();
                if (!actionName.equals(rawKey.actionName)) {
                    continue;
                }
                if (actionName.equals(UpdateConstants.BEGIN_UPDATE)
                        || actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                    continue;
                }

                Integer targetClass = outputClasses.get(edge.child);
                if (targetClass == null) {
                    continue;
                }
                if (concreteReachableFilter != null && !concreteReachableFilter.contains(edge.child)) {
                    continue;
                }

                NondetActionRepairKey key = new NondetActionRepairKey(sourceClass, actionName);
                NondetActionBeliefRepairPlan candidate = candidates.get(key);
                if (candidate == null) {
                    candidate = new NondetActionBeliefRepairPlan(sourceClass, edge.outputAction, actionName);
                    candidates.put(key, candidate);
                }
                candidate.sourceStates.add(source);
                candidate.rootMembers.add(edge.child);
                candidate.targetClasses.add(targetClass);
            }
        }

        Iterator<Map.Entry<NondetActionRepairKey, NondetActionBeliefRepairPlan>> it =
                candidates.entrySet().iterator();
        while (it.hasNext()) {
            NondetActionBeliefRepairPlan candidate = it.next().getValue();
            if (candidate.targetClasses.size() <= 1) {
                it.remove();
            }
        }
        return candidates;
    }

    private void runNondetActionBeliefRepairPlan(
            NondetActionBeliefRepairPlan candidate,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        List<CompostateDUC<State, Action>> seedStates = new ArrayList<>(candidate.rootMembers);
        List<Integer> fallbackClasses = new ArrayList<>(candidate.targetClasses);
        Collections.sort(fallbackClasses);

        BeliefRepairPlan plan = new BeliefRepairPlan(null, seedStates, fallbackClasses);
        plan.label = "nondetAction sourceClass=" + candidate.sourceClass
                + ", action=" + candidate.actionName;
        plan.initialConcreteStateCount = compostates == null ? 0 : compostates.size();
        plan.initialRawTransitionCount = countRawDirectorTransitions(rawDirectorEdges);
        plan.repairStartNanos = System.nanoTime();
        plan.beginUpdateAction = candidate.outputAction;
        candidate.beliefPlan = plan;

        for (CompostateDUC<State, Action> rootMember : candidate.rootMembers) {
            if (!isSafeWinningBeliefChild(rootMember)) {
                plan.fail("非決定 action の target に GOAL でない子が含まれる: "
                        + summarizeStateForDiagnostics(rootMember));
                logBeliefRepairPlan(plan);
                return;
            }
        }

        refreshBeliefRepairResourceLimits(plan, candidate.rootMembers, rawDirectorEdges);

        if (debugLogEnabled) {
            log("  [Nondet-Belief-Repair] candidate "
                    + plan.label()
                    + ", sourceStates=" + candidate.sourceStates.size()
                    + ", rootMembers=" + candidate.rootMembers.size()
                    + ", targetClasses=" + candidate.targetClasses
                    + ", reachableMapValuations=" + plan.reachableMapValuations
                    + ", limits=" + plan.resourceLimits);
        }

        runBeliefRepairLoop(
                plan,
                seedStates,
                candidate.rootMembers,
                rawDirectorEdges,
                "非決定 action の belief graph 上で hotSwapOut まで到達可能",
                "非決定 action の belief graph 上で共通する勝ち更新戦略を証明できない"
                        + "（追加展開しても新しい遷移を発見できない）");
    }

    private BeliefRepairPlan runBeliefRepairLoop(
            BeliefRepairPlan plan,
            List<CompostateDUC<State, Action>> seedStates,
            Set<CompostateDUC<State, Action>> rootMembers,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges,
            String successReason,
            String failureReason) {

        Set<String> lazyNoProgressActions = new HashSet<>();
        Set<String> rejectedBeliefActions = new HashSet<>();
        int lazyRound = 0;
        while (true) {
            BeliefSearchContext context = buildBeliefGraphForPlan(
                    plan, seedStates, rootMembers, rawDirectorEdges);
            updateBeliefRepairResourceUsage(plan, rawDirectorEdges);

            if (context.limitExceeded) {
                plan.failByResource("belief node 数が上限を超えた: "
                        + describeBeliefRepairResourceUsage(plan));
                recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause.BELIEF_NODES);
                logBeliefRepairPlan(plan);
                return plan;
            }
            if (checkBeliefRepairResourceLimit(plan, rawDirectorEdges)) {
                logBeliefRepairPlan(plan);
                return plan;
            }

            BeliefLazyExpansionResult updateExpansion = expandBeliefControllableFrontier(
                    plan, lazyNoProgressActions, rejectedBeliefActions, lazyRound + 1, true);
            if (checkBeliefRepairResourceLimit(plan, rawDirectorEdges)) {
                logBeliefRepairPlan(plan);
                return plan;
            }
            if (updateExpansion.hasProgress()) {
                rawDirectorEdges.clear();
                rawDirectorEdges.putAll(collectRawDirectorEdges(false));
                refreshBeliefRepairResourceLimits(plan, rootMembers, rawDirectorEdges);
                updateBeliefRepairResourceUsage(plan, rawDirectorEdges);
                lazyRound++;
                continue;
            }

            BeliefLazyExpansionResult uncontrollableExpansion = expandBeliefUncontrollableClosure(
                    plan, lazyNoProgressActions, lazyRound + 1);
            if (uncontrollableExpansion.hasProgress()) {
                rawDirectorEdges.clear();
                rawDirectorEdges.putAll(collectRawDirectorEdges(false));
                refreshBeliefRepairResourceLimits(plan, rootMembers, rawDirectorEdges);
                updateBeliefRepairResourceUsage(plan, rawDirectorEdges);
                continue;
            }
            markRemainingUnexploredUncontrollablesBad(plan);

            solveBeliefReachability(plan);
            if (plan.root != null && plan.root.winning) {
                plan.success = true;
                plan.reason = successReason;
                logBeliefRepairPlan(plan);
                return plan;
            }

            if (checkBeliefRepairResourceLimit(plan, rawDirectorEdges)) {
                logBeliefRepairPlan(plan);
                return plan;
            }

            BeliefLazyExpansionResult lazyResult = expandBeliefControllableFrontier(
                    plan, lazyNoProgressActions, rejectedBeliefActions, lazyRound + 1, false);
            if (checkBeliefRepairResourceLimit(plan, rawDirectorEdges)) {
                logBeliefRepairPlan(plan);
                return plan;
            }
            if (!lazyResult.hasProgress()) {
                plan.fail(failureReason);
                logBeliefRepairPlan(plan);
                return plan;
            }

            rawDirectorEdges.clear();
            rawDirectorEdges.putAll(collectRawDirectorEdges(false));
            refreshBeliefRepairResourceLimits(plan, rootMembers, rawDirectorEdges);
            updateBeliefRepairResourceUsage(plan, rawDirectorEdges);
            lazyRound++;
        }
    }

    private BeliefSearchContext buildBeliefGraphForPlan(
            BeliefRepairPlan plan,
            List<CompostateDUC<State, Action>> preUpdateStates,
            Set<CompostateDUC<State, Action>> beginTargets,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        Map<CompostateDUC<State, Action>, Integer> concreteIds =
                buildConcreteStateIds(preUpdateStates, rawDirectorEdges);
        BeliefSearchContext context = new BeliefSearchContext(
                concreteIds, plan.resourceLimits.maxBeliefNodes);

        plan.nodes.clear();
        plan.root = context.getOrCreateNode(beginTargets);
        if (plan.root == null) {
            context.limitExceeded = true;
            return context;
        }

        while (!context.queue.isEmpty() && !context.limitExceeded) {
            BeliefNode node = context.queue.remove();
            expandBeliefNode(node, context, rawDirectorEdges);
        }

        plan.nodes.addAll(context.nodes);
        beliefRepairGeneratedNodes += context.nodes.size();
        return context;
    }

    private void updateBeliefRepairResourceUsage(
            BeliefRepairPlan plan,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        int concreteStateCount = compostates == null ? 0 : compostates.size();
        int additionalConcreteStates = Math.max(0, concreteStateCount - plan.initialConcreteStateCount);
        long additionalTransitions = Math.max(
                0L,
                countCurrentExploredTransitions() - plan.initialRawTransitionCount);

        plan.maxObservedBeliefNodes = Math.max(plan.maxObservedBeliefNodes, plan.nodes.size());
        plan.maxObservedAdditionalConcreteStates =
                Math.max(plan.maxObservedAdditionalConcreteStates, additionalConcreteStates);
        plan.maxObservedAdditionalTransitions =
                Math.max(plan.maxObservedAdditionalTransitions, additionalTransitions);

        beliefRepairMaxObservedBeliefNodes =
                Math.max(beliefRepairMaxObservedBeliefNodes, plan.maxObservedBeliefNodes);
        beliefRepairMaxObservedAdditionalConcreteStates = Math.max(
                beliefRepairMaxObservedAdditionalConcreteStates,
                plan.maxObservedAdditionalConcreteStates);
        beliefRepairMaxObservedAdditionalTransitions = Math.max(
                beliefRepairMaxObservedAdditionalTransitions,
                plan.maxObservedAdditionalTransitions);
    }

    private boolean checkBeliefRepairResourceLimit(
            BeliefRepairPlan plan,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        updateBeliefRepairResourceUsage(plan, rawDirectorEdges);
        BeliefRepairResourceLimits limits = plan.resourceLimits;

        if (plan.maxObservedBeliefNodes > limits.maxBeliefNodes) {
            plan.failByResource("belief node 数が上限を超えた: "
                    + describeBeliefRepairResourceUsage(plan));
            recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause.BELIEF_NODES);
            return true;
        }
        if (plan.maxObservedAdditionalConcreteStates > limits.maxAdditionalConcreteStates) {
            plan.failByResource("追加 concrete state 数が上限を超えた: "
                    + describeBeliefRepairResourceUsage(plan));
            recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause.ADDITIONAL_CONCRETE_STATES);
            return true;
        }
        if (plan.maxObservedAdditionalTransitions > limits.maxAdditionalTransitions) {
            plan.failByResource("追加 transition 数が上限を超えた: "
                    + describeBeliefRepairResourceUsage(plan));
            recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause.ADDITIONAL_TRANSITIONS);
            return true;
        }
        if (limits.maxTimeMs > 0) {
            long elapsedMs = (System.nanoTime() - plan.repairStartNanos) / 1_000_000L;
            if (elapsedMs > limits.maxTimeMs) {
                plan.failByResource("belief repair 時間が上限を超えた: elapsedMs=" + elapsedMs
                        + ", " + describeBeliefRepairResourceUsage(plan));
                recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause.TIME);
                return true;
            }
        }
        return false;
    }

    private void recordBeliefRepairResourceLimitFallback(BeliefRepairResourceLimitCause cause) {
        beliefRepairResourceLimitFallbacks++;
        switch (cause) {
            case BELIEF_NODES:
                beliefRepairResourceLimitBeliefNodeFallbacks++;
                break;
            case ADDITIONAL_CONCRETE_STATES:
                beliefRepairResourceLimitAdditionalConcreteFallbacks++;
                break;
            case ADDITIONAL_TRANSITIONS:
                beliefRepairResourceLimitAdditionalTransitionFallbacks++;
                break;
            case TIME:
                beliefRepairResourceLimitTimeFallbacks++;
                break;
            default:
                break;
        }
    }

    private String describeBeliefRepairResourceUsage(BeliefRepairPlan plan) {
        if (plan.resourceLimits == null) {
            return "resourceLimits=not-initialized";
        }
        return "beliefNodes=" + plan.maxObservedBeliefNodes + "/" + plan.resourceLimits.maxBeliefNodes
                + ", additionalConcreteStates=" + plan.maxObservedAdditionalConcreteStates
                + "/" + plan.resourceLimits.maxAdditionalConcreteStates
                + ", additionalTransitions=" + plan.maxObservedAdditionalTransitions
                + "/" + plan.resourceLimits.maxAdditionalTransitions
                + ", maxTimeMs=" + plan.resourceLimits.maxTimeMs
                + ", reachableMapValuations=" + plan.reachableMapValuations;
    }

    private Map<CompostateDUC<State, Action>, Integer> buildConcreteStateIds(
            List<CompostateDUC<State, Action>> seedStates,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        Map<CompostateDUC<State, Action>, Integer> ids = new HashMap<>();
        for (CompostateDUC<State, Action> state : seedStates) {
            getConcreteStateId(ids, state);
        }
        for (Map.Entry<CompostateDUC<State, Action>, List<RawDirectorEdge>> entry : rawDirectorEdges.entrySet()) {
            getConcreteStateId(ids, entry.getKey());
            for (RawDirectorEdge edge : entry.getValue()) {
                getConcreteStateId(ids, edge.child);
            }
        }
        return ids;
    }

    private int getConcreteStateId(
            Map<CompostateDUC<State, Action>, Integer> concreteIds,
            CompostateDUC<State, Action> state) {

        Integer id = concreteIds.get(state);
        if (id == null) {
            id = concreteIds.size();
            concreteIds.put(state, id);
        }
        return id;
    }

    private BeliefLazyExpansionResult expandBeliefControllableFrontier(
            BeliefRepairPlan plan,
            Set<String> noProgressActions,
            Set<String> rejectedBeliefActions,
            int round,
            boolean updateProtocolOnly) {

        BeliefLazyExpansionResult result = new BeliefLazyExpansionResult(round);
        while (true) {
            BeliefExpansionCandidate candidate = selectNextControllableBeliefCandidate(
                    plan, noProgressActions, rejectedBeliefActions, updateProtocolOnly);
            if (candidate == null) {
                break;
            }
            if (expandBeliefExpansionCandidate(
                    candidate, noProgressActions, rejectedBeliefActions, result)) {
                return result;
            }
        }

        if (debugLogEnabled && (result.attemptedActions > 0 || result.addedEdges > 0)) {
            log("  [Belief-LazyExpansion] " + plan.label()
                    + ", round=" + result.round
                    + ", mode=C-one-action"
                    + ", attemptedActions=" + result.attemptedActions
                    + ", expandedActions=" + result.expandedActions
                    + ", addedEdges=" + result.addedEdges);
        }
        return result;
    }

    private BeliefLazyExpansionResult expandBeliefUncontrollableClosure(
            BeliefRepairPlan plan,
            Set<String> noProgressActions,
            int round) {

        BeliefLazyExpansionResult result = new BeliefLazyExpansionResult(round);
        BeliefExpansionCandidate candidate = selectNextUncontrollableBeliefCandidate(plan, noProgressActions);
        if (candidate != null) {
            expandBeliefExpansionCandidate(candidate, noProgressActions, Collections.emptySet(), result);
        }
        return result;
    }

    private BeliefExpansionCandidate selectNextControllableBeliefCandidate(
            BeliefRepairPlan plan,
            Set<String> noProgressActions,
            Set<String> rejectedBeliefActions,
            boolean updateProtocolOnly) {

        List<BeliefExpansionCandidate> candidates = new ArrayList<>();
        for (BeliefNode node : plan.nodes) {
            if (node.bad || node.winning) {
                continue;
            }

            // controllable は共通戦略として選ぶ必要があるため、belief 内の全候補で
            // 同じ出力 action が有効なものだけ候補にする。
            for (String actionName : collectCommonControllableActionNames(node)) {
                if (isUpdateProtocolOutputActionName(actionName) != updateProtocolOnly) {
                    continue;
                }
                if (rejectedBeliefActions.contains(beliefActionKey(node, actionName))) {
                    continue;
                }
                if (hasExpandableBeliefAction(node, actionName, true, noProgressActions)) {
                    candidates.add(new BeliefExpansionCandidate(
                            plan, node, actionName, true, isUpdateProtocolOutputActionName(actionName)));
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(this::compareBeliefExpansionCandidates);
        return candidates.get(0);
    }

    private BeliefExpansionCandidate selectNextUncontrollableBeliefCandidate(
            BeliefRepairPlan plan,
            Set<String> noProgressActions) {

        List<BeliefExpansionCandidate> candidates = new ArrayList<>();
        for (BeliefNode node : plan.nodes) {
            if (node.bad) {
                continue;
            }
            for (String actionName : collectUnexploredUncontrollableActionNames(node, noProgressActions)) {
                candidates.add(new BeliefExpansionCandidate(
                        plan, node, actionName, false, false));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(this::compareBeliefExpansionCandidates);
        return candidates.get(0);
    }

    private boolean expandBeliefExpansionCandidate(
            BeliefExpansionCandidate candidate,
            Set<String> noProgressActions,
            Set<String> rejectedBeliefActions,
            BeliefLazyExpansionResult result) {

        int beforeAddedEdges = result.addedEdges;
        int beforeExpandedActions = result.expandedActions;
        long beforeGlobalExpandedActions = beliefLazyExpansionExpandedActions;
        long beforeGlobalAddedEdges = beliefLazyExpansionAddedEdges;
        for (CompostateDUC<State, Action> member : candidate.node.members) {
            HAction<State, Action> memberAction = candidate.controllable
                    ? findEnabledActionByOutputName(member, candidate.actionName, true)
                    : findEnabledUncontrollableActionByOutputName(member, candidate.actionName);
            if (memberAction != null) {
                expandConcreteActionForBelief(member, memberAction, noProgressActions, result);
            }
        }

        if (result.addedEdges > beforeAddedEdges) {
            if (candidate.controllable && !isControllableBeliefCandidateUsable(candidate)) {
                rejectedBeliefActions.add(beliefActionKey(candidate.node, candidate.actionName));
                beliefUnsafeControllableDiscardCount++;
                if (debugLogEnabled) {
                    log("  [Belief-LazyExpansion] discard unsafe controllable candidate: "
                            + candidate.plan.label()
                            + ", node=" + candidate.node.name()
                            + ", action=" + candidate.actionName
                            + ", reason=ERROR child を持つため共通 controllable 戦略として使えない");
                }
                result.expandedActions = beforeExpandedActions;
                result.addedEdges = beforeAddedEdges;
                beliefLazyExpansionExpandedActions = beforeGlobalExpandedActions;
                beliefLazyExpansionAddedEdges = beforeGlobalAddedEdges;
                return false;
            }
            if (debugLogEnabled) {
                log("  [Belief-LazyExpansion] " + candidate.plan.label()
                        + ", round=" + result.round
                        + ", mode=" + (candidate.controllable ? "C-frontier" : "U-closure")
                        + ", selectedNode=" + candidate.node.name()
                        + ", nodeDepth=" + beliefNodeMaxMarkingDepth(candidate.node)
                        + ", nodeSeq=" + beliefNodeMaxSeq(candidate.node)
                        + ", action=" + candidate.actionName
                        + ", actionClass=" + candidate.actionClass()
                        + ", attemptedActions=" + result.attemptedActions
                        + ", expandedActions=" + result.expandedActions
                        + ", addedEdges=" + result.addedEdges);
            }
            beliefLazyExpansionRounds++;
            return true;
        }
        return false;
    }

    private int compareBeliefExpansionCandidates(
            BeliefExpansionCandidate left,
            BeliefExpansionCandidate right) {

        int cmp = Integer.compare(
                beliefNodeMaxMarkingDepth(right.node),
                beliefNodeMaxMarkingDepth(left.node));
        if (cmp != 0) {
            return cmp;
        }

        // 同じ深さなら、通常探索の LIFO に近づけるため、新しく生成された具象状態を
        // 含む belief node を優先する。
        cmp = Integer.compare(beliefNodeMaxSeq(right.node), beliefNodeMaxSeq(left.node));
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(
                beliefActionPriority(left.actionName, left.controllable),
                beliefActionPriority(right.actionName, right.controllable));
        if (cmp != 0) {
            return cmp;
        }

        cmp = Long.compare(
                beliefNodeMaxMarkingState(right.node),
                beliefNodeMaxMarkingState(left.node));
        if (cmp != 0) {
            return cmp;
        }

        cmp = Integer.compare(right.node.localId, left.node.localId);
        if (cmp != 0) {
            return cmp;
        }

        return left.actionName.compareTo(right.actionName);
    }

    private int beliefActionPriority(String actionName, boolean controllableAction) {
        int categoryRank = explorationActionCategoryRank(actionName, controllableAction);
        int actionCost = categoryRank == 1 ? actionPriorityCost(actionName) : 0;
        return categoryRank * 1000 + actionCost;
    }

    private int beliefNodeMaxMarkingDepth(BeliefNode node) {
        int maxDepth = 0;
        for (CompostateDUC<State, Action> member : node.members) {
            maxDepth = Math.max(maxDepth, markingDepth(getMarkingState(member)));
        }
        return maxDepth;
    }

    private long beliefNodeMaxMarkingState(BeliefNode node) {
        long maxMarking = 0;
        for (CompostateDUC<State, Action> member : node.members) {
            maxMarking = Math.max(maxMarking, getMarkingState(member));
        }
        return maxMarking;
    }

    private int beliefNodeMaxSeq(BeliefNode node) {
        int maxSeq = 0;
        for (CompostateDUC<State, Action> member : node.members) {
            maxSeq = Math.max(maxSeq, member.seq);
        }
        return maxSeq;
    }

    private int markingDepth(long markingState) {
        return markingDepthForHeuristic(markingState);
    }

    private void markRemainingUnexploredUncontrollablesBad(BeliefRepairPlan plan) {
        int count = 0;
        int affectedNodes = 0;
        List<String> samples = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (BeliefNode node : plan.nodes) {
            String firstReason = null;
            for (CompostateDUC<State, Action> member : node.members) {
                for (HAction<State, Action> action : member.getTransitions()) {
                    if (action.isControllable() || hasExploredAction(member, action)) {
                        continue;
                    }

                    String key = node.name() + "|" + lazyExpansionKey(member, action);
                    if (!seen.add(key)) {
                        continue;
                    }
                    count++;
                    if (samples.size() < 10) {
                        samples.add(node.name()
                                + ":" + toOutputAction(action) + "(U)"
                                + " at " + summarizeStateForDiagnostics(member));
                    }
                    if (firstReason == null) {
                        firstReason = toOutputAction(action) + "(U) が未展開: "
                                + summarizeStateForDiagnostics(member);
                    }
                }
            }
            if (firstReason != null) {
                node.markBad("未展開 uncontrollable が残っているため belief 勝ち判定から除外: "
                        + firstReason);
                affectedNodes++;
            }
        }

        if (count > 0) {
            beliefUnexploredUncontrollableWarningPlans++;
            beliefUnexploredUncontrollableWarningNodes += affectedNodes;
            beliefUnexploredUncontrollableWarningActions += count;
        }

        if (debugLogEnabled && count > 0) {
            log("  [Belief-Warning] " + plan.label()
                    + " has unexplored uncontrollable actions inside belief graph: count=" + count
                    + ", samples=" + samples
                    + ". 未展開 uncontrollable を持つ belief node は勝ち判定から除外します。");
        }
    }

    private List<String> collectCommonControllableActionNames(BeliefNode node) {
        Set<String> commonNames = null;

        for (CompostateDUC<State, Action> member : node.members) {
            Set<String> names = new LinkedHashSet<>();
            for (HAction<State, Action> action : member.getTransitions()) {
                if (!action.isControllable()) {
                    continue;
                }
                if (isBlockedFinishUpdateAction(member, action)) {
                    continue;
                }
                String actionName = toOutputAction(action).toString();
                names.add(actionName);
            }
            if (commonNames == null) {
                commonNames = names;
            } else {
                commonNames.retainAll(names);
            }
        }

        if (commonNames == null || commonNames.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>(commonNames);
        result.sort((left, right) -> {
            int cmp = Integer.compare(
                    beliefActionPriority(left, true),
                    beliefActionPriority(right, true));
            if (cmp != 0) {
                return cmp;
            }
            return left.compareTo(right);
        });
        return result;
    }

    private List<String> collectUnexploredUncontrollableActionNames(
            BeliefNode node,
            Set<String> noProgressActions) {

        Set<String> actionNames = new LinkedHashSet<>();
        for (CompostateDUC<State, Action> member : node.members) {
            for (HAction<State, Action> action : member.getTransitions()) {
                if (action.isControllable() || hasExploredAction(member, action)) {
                    continue;
                }
                if (noProgressActions.contains(lazyExpansionKey(member, action))) {
                    continue;
                }
                actionNames.add(toOutputAction(action).toString());
            }
        }

        List<String> result = new ArrayList<>(actionNames);
        Collections.sort(result);
        return result;
    }

    private boolean hasExpandableBeliefAction(
            BeliefNode node,
            String actionName,
            boolean controllableAction,
            Set<String> noProgressActions) {

        for (CompostateDUC<State, Action> member : node.members) {
            HAction<State, Action> action = controllableAction
                    ? findEnabledActionByOutputName(member, actionName, true)
                    : findEnabledUncontrollableActionByOutputName(member, actionName);
            if (action == null || hasExploredAction(member, action)) {
                continue;
            }
            if (!noProgressActions.contains(lazyExpansionKey(member, action))) {
                return true;
            }
        }
        return false;
    }

    private HAction<State, Action> findEnabledActionByOutputName(
            CompostateDUC<State, Action> state,
            String outputActionName,
            boolean controllableOnly) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (controllableOnly && !action.isControllable()) {
                continue;
            }
            if (isBlockedFinishUpdateAction(state, action)) {
                continue;
            }
            if (toOutputAction(action).toString().equals(outputActionName)) {
                return action;
            }
        }
        return null;
    }

    private HAction<State, Action> findEnabledUncontrollableActionByOutputName(
            CompostateDUC<State, Action> state,
            String outputActionName) {

        for (HAction<State, Action> action : state.getTransitions()) {
            if (action.isControllable()) {
                continue;
            }
            if (toOutputAction(action).toString().equals(outputActionName)) {
                return action;
            }
        }
        return null;
    }

    private boolean isBlockedFinishUpdateAction(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action) {

        return action != null
                && action.toString().equals(UpdateConstants.FINISH_UPDATE)
                && state.isFinishUpdateBlocked();
    }

    private void expandConcreteActionForBelief(
            CompostateDUC<State, Action> state,
            HAction<State, Action> action,
            Set<String> noProgressActions,
            BeliefLazyExpansionResult result) {

        if (hasExploredAction(state, action)) {
            return;
        }

        String key = lazyExpansionKey(state, action);
        if (noProgressActions.contains(key)) {
            return;
        }

        result.attemptedActions++;
        beliefLazyExpansionAttempts++;
        int beforeChildren = exploredChildCount(state, action);

        totalLtsExpansions++;
        long expansionStart = System.nanoTime();
        expandDUC(state, action);
        stateExpansionNanos += System.nanoTime() - expansionStart;

        int addedChildren = exploredChildCount(state, action) - beforeChildren;
        if (addedChildren > 0) {
            result.expandedActions++;
            result.addedEdges += addedChildren;
            beliefLazyExpansionExpandedActions++;
            beliefLazyExpansionAddedEdges += addedChildren;
            if (debugLogEnabled) {
                log("    [Belief-LazyExpansion] expanded action=" + toOutputAction(action)
                        + (action.isControllable() ? "(C)" : "(U)")
                        + ", addedEdges=" + addedChildren
                        + ", state=" + summarizeStateForDiagnostics(state));
            }
        } else {
            noProgressActions.add(key);
        }
    }

    private boolean isControllableBeliefCandidateUsable(BeliefExpansionCandidate candidate) {
        for (CompostateDUC<State, Action> member : candidate.node.members) {
            HAction<State, Action> action =
                    findEnabledActionByOutputName(member, candidate.actionName, true);
            if (action == null) {
                return false;
            }

            Set<CompostateDUC<State, Action>> children =
                    member.getExploredChildren().getImage(action);
            if (children == null || children.isEmpty()) {
                return false;
            }

            // controllable は belief 全体で同じ action を選ぶため、各候補状態で
            // その action のすべての探索済み子が安全な勝ち状態でなければならない。
            for (CompostateDUC<State, Action> child : children) {
                if (!isSafeWinningBeliefChild(child)) {
                    return false;
                }
            }
        }
        return true;
    }

    private String beliefActionKey(BeliefNode node, String actionName) {
        List<Integer> identities = new ArrayList<>();
        for (CompostateDUC<State, Action> member : node.members) {
            identities.add(System.identityHashCode(member));
        }
        Collections.sort(identities);
        return identities + "|" + actionName;
    }

    private boolean hasExploredAction(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        return exploredChildCount(state, action) > 0;
    }

    private int exploredChildCount(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        Set<CompostateDUC<State, Action>> children = state.getExploredChildren().getImage(action);
        return children == null ? 0 : children.size();
    }

    private String lazyExpansionKey(CompostateDUC<State, Action> state, HAction<State, Action> action) {
        return System.identityHashCode(state) + "|" + action.hashCode();
    }

    private void expandBeliefNode(
            BeliefNode node,
            BeliefSearchContext context,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        buildBeliefFinishEdge(node, rawDirectorEdges);
        buildBeliefActionEdges(node, context, rawDirectorEdges);

        if (debugLogEnabled) {
            log("    [Belief-Node] " + node.name()
                    + " members=" + describeBeliefMembers(node)
                    + ", finish=" + (node.finishNcTargetId == null ? "none" : "NC:" + node.finishNcTargetId)
                    + ", uncontrollable=" + describeBeliefEdges(node.uncontrollableEdges)
                    + ", controllable=" + describeBeliefEdges(node.controllableEdges)
                    + (node.bad ? ", BAD=" + node.badReason : ""));
        }
    }

    private void buildBeliefFinishEdge(
            BeliefNode node,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        Set<Long> ncTargets = new LinkedHashSet<>();
        Action finishAction = null;

        for (CompostateDUC<State, Action> member : node.members) {
            List<RawDirectorEdge> edges = rawDirectorEdges.get(member);
            boolean hasFinish = false;
            if (edges != null) {
                for (RawDirectorEdge edge : edges) {
                    if (!edge.actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                        continue;
                    }
                    if (!isGoalProgressStateId(getMarkingState(edge.child)) || !isSafeWinningBeliefChild(edge.child)) {
                        continue;
                    }
                    String signature = generateNCSignature(edge.child);
                    Long ncTarget = signature == null ? null : newControllerConnectionMap.get(signature);
                    if (ncTarget == null) {
                        continue;
                    }
                    hasFinish = true;
                    ncTargets.add(ncTarget);
                    finishAction = edge.outputAction;
                }
            }
            if (!hasFinish) {
                return;
            }
        }

        if (ncTargets.size() == 1) {
            node.finishNcTargetId = ncTargets.iterator().next();
            node.finishAction = finishAction;
        } else if (debugLogEnabled) {
            log("    [Belief-Repair] hotSwapOut postponed at " + node.name()
                    + " because NC targets differ: " + ncTargets);
        }
    }

    private void buildBeliefActionEdges(
            BeliefNode node,
            BeliefSearchContext context,
            Map<CompostateDUC<State, Action>, List<RawDirectorEdge>> rawDirectorEdges) {

        Map<String, BeliefActionBucket> controllableBuckets = new LinkedHashMap<>();
        Map<String, BeliefActionBucket> uncontrollableBuckets = new LinkedHashMap<>();

        for (int memberIndex = 0; memberIndex < node.members.size(); memberIndex++) {
            CompostateDUC<State, Action> member = node.members.get(memberIndex);
            List<RawDirectorEdge> edges = rawDirectorEdges.get(member);
            if (edges == null) {
                continue;
            }
            for (RawDirectorEdge edge : edges) {
                if (edge.actionName.equals(UpdateConstants.BEGIN_UPDATE)
                        || edge.actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                    continue;
                }
                Map<String, BeliefActionBucket> buckets = edge.hAction.isControllable()
                        ? controllableBuckets
                        : uncontrollableBuckets;
                BeliefActionBucket bucket = buckets.get(edge.actionName);
                if (bucket == null) {
                    bucket = new BeliefActionBucket(edge.outputAction, edge.hAction.isControllable());
                    buckets.put(edge.actionName, bucket);
                }
                bucket.presentMemberIndexes.add(memberIndex);
                if (isSafeWinningBeliefChild(edge.child)) {
                    bucket.children.add(edge.child);
                } else {
                    bucket.hasUnsafeChild = true;
                    bucket.unsafeReason = "action=" + edge.actionName
                            + " child=" + summarizeStateForDiagnostics(edge.child);
                }
            }
        }

        for (BeliefActionBucket bucket : uncontrollableBuckets.values()) {
            if (bucket.hasUnsafeChild) {
                node.markBad("uncontrollable action が安全でない子へ進む可能性: " + bucket.unsafeReason);
                return;
            }
            BeliefNode target = context.getOrCreateNode(bucket.children);
            if (target == null) {
                node.markBad("uncontrollable action の先で belief 状態数の上限を超えた: " + bucket.actionName());
                return;
            }
            node.uncontrollableEdges.add(new BeliefTransition(bucket.outputAction, false, target));
        }

        int memberCount = node.members.size();
        for (BeliefActionBucket bucket : controllableBuckets.values()) {
            // controllable は controller が同じ action を選ぶ必要があるため、
            // belief 内の全候補で有効な action だけを共通戦略として採用できる。
            if (bucket.presentMemberIndexes.size() != memberCount || bucket.hasUnsafeChild) {
                continue;
            }
            BeliefNode target = context.getOrCreateNode(bucket.children);
            if (target == null) {
                continue;
            }
            node.controllableEdges.add(new BeliefTransition(bucket.outputAction, true, target));
        }
    }

    private void solveBeliefReachability(BeliefRepairPlan plan) {
        boolean changed;
        do {
            changed = propagateBeliefWinning(plan);
            if (promoteFairBeliefLoops(plan)) {
                changed = true;
            }
        } while (changed);

        if (debugLogEnabled) {
            int winningNodes = 0;
            for (BeliefNode node : plan.nodes) {
                if (node.winning) {
                    winningNodes++;
                }
            }
            log("  [Belief-Repair] fixed point " + plan.label()
                    + ": winningBeliefNodes=" + winningNodes + "/" + plan.nodes.size());
        }
    }

    private boolean propagateBeliefWinning(BeliefRepairPlan plan) {
        boolean changed = false;
        for (int i = plan.nodes.size() - 1; i >= 0; i--) {
            BeliefNode node = plan.nodes.get(i);
            if (node.winning || node.bad) {
                continue;
            }

            boolean allUncontrollableWinning = true;
            for (BeliefTransition edge : node.uncontrollableEdges) {
                if (!edge.target.winning) {
                    allUncontrollableWinning = false;
                    break;
                }
            }
            if (!allUncontrollableWinning) {
                continue;
            }

            if (markBeliefNodeWinningByDirectProgress(node)) {
                changed = true;
                continue;
            }

            // controllable による次手がなくても、全ての uncontrollable 後続が
            // 既に勝ちなら、この belief 状態も勝ちとして扱える。ここで出力する
            // controller は controllable を選ばず、環境の uncontrollable 遷移を
            // 受け入れて次の勝ち belief へ進む。
            if (!node.uncontrollableEdges.isEmpty()) {
                node.winning = true;
                changed = true;
            }
        }
        return changed;
    }

    private boolean markBeliefNodeWinningByDirectProgress(BeliefNode node) {
        if (node.finishNcTargetId != null) {
            node.winning = true;
            node.selectedFinish = true;
            return true;
        }

        for (BeliefTransition edge : node.controllableEdges) {
            if (edge.target.winning) {
                node.winning = true;
                node.selectedControllableEdge = edge;
                return true;
            }
        }
        return false;
    }

    private boolean promoteFairBeliefLoops(BeliefRepairPlan plan) {
        Set<BeliefNode> candidates = new LinkedHashSet<>();
        for (BeliefNode node : plan.nodes) {
            if (!node.winning && !node.bad) {
                candidates.add(node);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }

        boolean innerChanged;
        Map<BeliefNode, Integer> dist = new HashMap<>();
        do {
            innerChanged = false;

            Iterator<BeliefNode> it = candidates.iterator();
            while (it.hasNext()) {
                BeliefNode node = it.next();
                if (!isBeliefUSafe(node, candidates)) {
                    it.remove();
                    innerChanged = true;
                }
            }
            if (candidates.isEmpty()) {
                return false;
            }

            dist = computeBeliefFairDistances(candidates);
            it = candidates.iterator();
            while (it.hasNext()) {
                BeliefNode node = it.next();
                if (!dist.containsKey(node)) {
                    it.remove();
                    innerChanged = true;
                }
            }
        } while (innerChanged);

        if (candidates.isEmpty()) {
            return false;
        }

        int promoted = 0;
        for (BeliefNode node : candidates) {
            if (node.winning) {
                continue;
            }
            BeliefTransition selected = selectBeliefFairProgress(node, candidates, dist);
            boolean usesFinish = node.finishNcTargetId != null
                    && isBeliefFinishUsableForFairReachability(node, candidates);
            if (!usesFinish && selected == null) {
                continue;
            }
            if (usesFinish) {
                node.selectedFinish = true;
            } else if (selected != null && selected.controllable) {
                node.selectedControllableEdge = selected;
            } else if (selected != null) {
                node.selectedFairUncontrollableEdge = selected;
            }
            node.winning = true;
            promoted++;
        }

        if (debugLogEnabled && promoted > 0) {
            log("  [Belief-Fairness] " + plan.label()
                    + " promoted belief nodes by fair SCC: " + promoted);
        }
        return promoted > 0;
    }

    private boolean isBeliefUSafe(BeliefNode node, Set<BeliefNode> candidates) {
        for (BeliefTransition edge : node.uncontrollableEdges) {
            if (!edge.target.winning && !candidates.contains(edge.target)) {
                return false;
            }
        }
        return true;
    }

    private Map<BeliefNode, Integer> computeBeliefFairDistances(Set<BeliefNode> candidates) {
        Map<BeliefNode, Integer> dist = new HashMap<>();
        boolean changed;
        do {
            changed = false;
            for (BeliefNode node : candidates) {
                int best = distanceToFairProgress(node, candidates, dist);
                if (best == Integer.MAX_VALUE) {
                    continue;
                }
                Integer old = dist.get(node);
                if (old == null || best < old) {
                    dist.put(node, best);
                    changed = true;
                }
            }
        } while (changed);
        return dist;
    }

    private int distanceToFairProgress(
            BeliefNode node,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        int best = Integer.MAX_VALUE;
        if (node.finishNcTargetId != null && isBeliefFinishUsableForFairReachability(node, candidates)) {
            best = 1;
        }

        for (BeliefTransition edge : getBeliefTransitions(node)) {
            if (!canUseBeliefTransitionForFairReachability(node, edge, candidates)) {
                continue;
            }
            int childDistance = beliefTargetDistance(edge.target, candidates, dist);
            if (childDistance != Integer.MAX_VALUE) {
                best = Math.min(best, childDistance + 1);
            }
        }
        return best;
    }

    private int beliefTargetDistance(
            BeliefNode target,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        if (target.winning) {
            return 0;
        }
        if (!candidates.contains(target)) {
            return Integer.MAX_VALUE;
        }
        Integer childDistance = dist.get(target);
        return childDistance == null ? Integer.MAX_VALUE : childDistance;
    }

    private BeliefTransition selectBeliefFairProgress(
            BeliefNode node,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        BeliefTransition bestEdge = null;
        int bestDistance = node.finishNcTargetId != null
                && isBeliefFinishUsableForFairReachability(node, candidates)
                        ? 0
                        : Integer.MAX_VALUE;

        for (BeliefTransition edge : getBeliefTransitions(node)) {
            if (!canUseBeliefTransitionForFairReachability(node, edge, candidates)) {
                continue;
            }
            int childDistance = beliefTargetDistance(edge.target, candidates, dist);
            if (childDistance == Integer.MAX_VALUE) {
                continue;
            }
            if (childDistance < bestDistance) {
                bestDistance = childDistance;
                bestEdge = edge;
            } else if (childDistance == bestDistance && shouldPreferBeliefFairEdge(bestEdge, edge)) {
                bestEdge = edge;
            }
        }
        return bestEdge;
    }

    private boolean shouldPreferBeliefFairEdge(BeliefTransition current, BeliefTransition candidate) {
        if (current == null) {
            return true;
        }
        if (!current.controllable && candidate.controllable) {
            return true;
        }
        return !isUpdateProtocolOutputAction(current.outputAction)
                && isUpdateProtocolOutputAction(candidate.outputAction);
    }

    private List<BeliefTransition> getBeliefTransitions(BeliefNode node) {
        List<BeliefTransition> transitions = new ArrayList<>(
                node.uncontrollableEdges.size() + node.controllableEdges.size());
        transitions.addAll(node.uncontrollableEdges);
        transitions.addAll(node.controllableEdges);
        return transitions;
    }

    private boolean isBeliefFinishUsableForFairReachability(
            BeliefNode node,
            Set<BeliefNode> candidates) {

        return node.finishNcTargetId != null;
    }

    private boolean canUseBeliefTransitionForFairReachability(
            BeliefNode node,
            BeliefTransition edge,
            Set<BeliefNode> candidates) {

        if (!edge.controllable) {
            return true;
        }
        if (isUpdateProtocolOutputAction(edge.outputAction)) {
            return true;
        }
        return !hasBeliefUncontrollableSuccessorIn(node, candidates);
    }

    private boolean hasBeliefUncontrollableSuccessorIn(
            BeliefNode node,
            Set<BeliefNode> candidates) {

        for (BeliefTransition edge : node.uncontrollableEdges) {
            if (candidates.contains(edge.target)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUpdateProtocolOutputAction(Action action) {
        return isUpdateProtocolOutputActionName(action.toString());
    }

    protected boolean isUpdateProtocolOutputActionName(String actionName) {
        return actionName.equals(UpdateConstants.STOP_OLD_SPEC)
            || actionName.equals(UpdateConstants.RECONFIGURE)
            || actionName.equals(UpdateConstants.START_NEW_SPEC)
            || actionName.equals(UpdateConstants.FINISH_UPDATE);
    }

    private Map<CompostateDUC<State, Action>, Integer> buildPreUpdateClassesAfterBeliefRepair(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, Integer> fallbackPreUpdateClasses,
            BeliefRepairResult beliefRepairResult) {

        if (beliefRepairResult.successPlans.isEmpty()) {
            return fallbackPreUpdateClasses;
        }

        Map<CompostateDUC<State, Action>, Integer> result = new HashMap<>();
        Map<State, Integer> repairedClassByOldState = new LinkedHashMap<>();
        Map<Integer, Integer> fallbackClassRemap = new LinkedHashMap<>();
        int nextClassId = 0;

        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }

            State oldControllerState = state.getStates().get(idxOC);
            if (beliefRepairResult.isRepairedPreUpdateState(state)) {
                Integer classId = repairedClassByOldState.get(oldControllerState);
                if (classId == null) {
                    classId = nextClassId++;
                    repairedClassByOldState.put(oldControllerState, classId);
                }
                result.put(state, classId);
            } else {
                Integer fallbackClass = fallbackPreUpdateClasses.get(state);
                Integer classId = fallbackClassRemap.get(fallbackClass);
                if (classId == null) {
                    classId = nextClassId++;
                    fallbackClassRemap.put(fallbackClass, classId);
                }
                result.put(state, classId);
            }
        }

        if (debugLogEnabled) {
            log("  [Belief-Repair] final pre-update classes after repair: " + nextClassId
                    + " (repairedOldStates=" + repairedClassByOldState.size()
                    + ", fallbackClasses=" + fallbackClassRemap.size() + ")");
        }
        return result;
    }

    private void recordPreUpdateOutputStateOverhead(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses) {

        Set<CompostateDUC<State, Action>> preUpdateStates = new LinkedHashSet<>();
        Set<Integer> outputClasses = new HashSet<>();
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }
            preUpdateStates.add(state);
            Integer classId = preUpdateClasses.get(state);
            if (classId != null) {
                outputClasses.add(classId);
            }
        }

        preUpdateOutputMergedStates = preUpdateStates.size();
        preUpdateOutputClassStates = outputClasses.size();
        preUpdateOutputMergeRemovedStates = preUpdateOutputMergedStates - preUpdateOutputClassStates;
        UpdatingControllerEvaluationRecorder.recordOtfPreUpdateStateOverhead(
                countOldControllerStates(),
                preUpdateOutputMergedStates,
                preUpdateOutputClassStates);
    }

    private void recordSimpleMergeSplitStats(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, Integer> simpleMergeClasses) {

        long[] stats = computePreUpdateSplitStats(reachableOrder, simpleMergeClasses);
        UpdatingControllerEvaluationRecorder.recordOtfSimpleMergeSplitStats(stats[0], stats[1]);
    }

    private long[] computePreUpdateSplitStats(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses) {

        Map<State, Set<Integer>> classesByOldControllerState = new LinkedHashMap<>();
        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (!isPreUpdateOutputState(state)) {
                continue;
            }
            Integer classId = preUpdateClasses.get(state);
            if (classId == null) {
                continue;
            }
            State oldControllerState = state.getStates().get(idxOC);
            classesByOldControllerState
                    .computeIfAbsent(oldControllerState, key -> new LinkedHashSet<>())
                    .add(classId);
        }

        long splitOldControllerStates = 0;
        long maxSplitPerOldControllerState = 0;
        for (Set<Integer> classes : classesByOldControllerState.values()) {
            int split = classes.size();
            if (split > 1) {
                splitOldControllerStates++;
            }
            maxSplitPerOldControllerState = Math.max(maxSplitPerOldControllerState, split);
        }
        return new long[] { splitOldControllerStates, maxSplitPerOldControllerState };
    }

    private Map<CompostateDUC<State, Action>, Integer> buildOutputClasses(
            List<CompostateDUC<State, Action>> reachableOrder,
            Map<CompostateDUC<State, Action>, Integer> preUpdateClasses,
            Map<CompostateDUC<State, Action>, Integer> nonPreUpdateClasses) {

        Map<Integer, Integer> preUpdateClassToOutputClass = new LinkedHashMap<>();
        Map<Integer, Integer> nonPreUpdateClassToOutputClass = new LinkedHashMap<>();
        Map<CompostateDUC<State, Action>, Integer> result = new HashMap<>();
        int nextClassId = 0;

        for (CompostateDUC<State, Action> state : reachableOrder) {
            if (isPreUpdateOutputState(state)) {
                Integer preUpdateClass = preUpdateClasses.get(state);
                if (preUpdateClass == null) {
                    throw new IllegalStateException("Missing pre-update output class.");
                }
                Integer outputClass = preUpdateClassToOutputClass.get(preUpdateClass);
                if (outputClass == null) {
                    outputClass = nextClassId++;
                    preUpdateClassToOutputClass.put(preUpdateClass, outputClass);
                }
                result.put(state, outputClass);
            } else {
                Integer nonPreUpdateClass = nonPreUpdateClasses.get(state);
                if (nonPreUpdateClass == null) {
                    throw new IllegalStateException("Missing non-pre-update output class.");
                }
                Integer outputClass = nonPreUpdateClassToOutputClass.get(nonPreUpdateClass);
                if (outputClass == null) {
                    outputClass = nextClassId++;
                    nonPreUpdateClassToOutputClass.put(nonPreUpdateClass, outputClass);
                }
                result.put(state, outputClass);
            }
        }

        if (debugLogEnabled) {
            log("  [Director-Output-Classes] rawOutputStates=" + reachableOrder.size()
                    + ", classes=" + nextClassId
                    + ", removed=" + Math.max(0, reachableOrder.size() - nextClassId));
        }
        return result;
    }

    private boolean isBeliefRepairReplacedDirectorEdge(
            CompostateDUC<State, Action> source,
            DirectorEdge edge,
            BeliefRepairResult beliefRepairResult) {

        return beliefRepairResult != null
                && beliefRepairResult.isRepairedPreUpdateState(source)
                && edge.outputAction.toString().equals(UpdateConstants.BEGIN_UPDATE);
    }

    private boolean isNondetActionBeliefRepairReplacedDirectorEdge(
            CompostateDUC<State, Action> source,
            DirectorEdge edge,
            Map<CompostateDUC<State, Action>, Integer> outputClasses,
            NondetActionBeliefRepairResult repairResult) {

        if (repairResult == null || edge.isNewControllerConnection() || edge.child == null) {
            return false;
        }
        Integer sourceClass = outputClasses.get(source);
        if (sourceClass == null) {
            return false;
        }
        NondetActionBeliefRepairPlan plan = repairResult.find(sourceClass, edge.outputAction);
        if (plan == null) {
            return false;
        }
        Integer targetClass = outputClasses.get(edge.child);
        return targetClass != null && plan.targetClasses.contains(targetClass);
    }

    private void emitBeliefRepairTransitions(
            LTSImpl<Long, Action> result,
            Map<CompostateDUC<State, Action>, Long> concreteIds,
            Map<BeliefNode, Long> beliefIds,
            BeliefRepairResult beliefRepairResult) {

        for (BeliefRepairPlan plan : beliefRepairResult.successPlans.values()) {
            if (plan.preUpdateStates.isEmpty()) {
                continue;
            }
            Long sourceId = concreteIds.get(plan.preUpdateStates.get(0));
            Long rootId = beliefIds.get(plan.root);
            if (sourceId == null || rootId == null) {
                throw new IllegalStateException("Missing output id for belief repair hotSwapIn.");
            }

            directorTransitionEmissionAttempts++;
            if (result.addTransition(sourceId, plan.beginUpdateAction, rootId)) {
                directorOutputTransitions++;
            }

            emitBeliefPlanInternalTransitions(result, beliefIds, plan);
        }
    }

    private void emitNondetActionBeliefRepairTransitions(
            LTSImpl<Long, Action> result,
            Map<CompostateDUC<State, Action>, Long> concreteIds,
            Map<BeliefNode, Long> beliefIds,
            Map<CompostateDUC<State, Action>, Integer> outputClasses,
            NondetActionBeliefRepairResult repairResult) {

        for (NondetActionBeliefRepairPlan repairPlan : repairResult.successPlans) {
            Long sourceId = null;
            for (CompostateDUC<State, Action> source : repairPlan.sourceStates) {
                Integer sourceClass = outputClasses.get(source);
                if (sourceClass != null && sourceClass == repairPlan.sourceClass) {
                    sourceId = concreteIds.get(source);
                    break;
                }
            }
            Long rootId = beliefIds.get(repairPlan.beliefPlan.root);
            if (sourceId == null || rootId == null) {
                throw new IllegalStateException("Missing output id for nondeterministic action belief repair.");
            }

            directorTransitionEmissionAttempts++;
            if (result.addTransition(sourceId, repairPlan.outputAction, rootId)) {
                directorOutputTransitions++;
                nondetBeliefRepairReplacedTransitions++;
            }

            emitBeliefPlanInternalTransitions(result, beliefIds, repairPlan.beliefPlan);
        }
    }

    private void emitBeliefPlanInternalTransitions(
            LTSImpl<Long, Action> result,
            Map<BeliefNode, Long> beliefIds,
            BeliefRepairPlan plan) {

        for (BeliefNode node : plan.nodes) {
            if (!node.winning) {
                continue;
            }
            Long nodeId = beliefIds.get(node);
            if (nodeId == null) {
                throw new IllegalStateException("Missing output id for belief state.");
            }

            for (BeliefTransition edge : node.uncontrollableEdges) {
                if (!edge.target.winning) {
                    continue;
                }
                Long targetId = beliefIds.get(edge.target);
                if (targetId == null) {
                    throw new IllegalStateException("Missing output id for belief uncontrollable target.");
                }
                directorTransitionEmissionAttempts++;
                if (result.addTransition(nodeId, edge.outputAction, targetId)) {
                    directorOutputTransitions++;
                }
            }

            if (node.selectedFinish) {
                directorTransitionEmissionAttempts++;
                if (result.addTransition(nodeId, node.finishAction, node.finishNcTargetId)) {
                    directorOutputTransitions++;
                }
            } else if (node.selectedControllableEdge != null) {
                Long targetId = beliefIds.get(node.selectedControllableEdge.target);
                if (targetId == null) {
                    throw new IllegalStateException("Missing output id for belief controllable target.");
                }
                directorTransitionEmissionAttempts++;
                if (result.addTransition(nodeId, node.selectedControllableEdge.outputAction, targetId)) {
                    directorOutputTransitions++;
                }
            }
        }
    }

    private boolean isSafeWinningBeliefChild(CompostateDUC<State, Action> child) {
        return child != null && child.isStatus(Status.GOAL) && !child.isStatus(Status.ERROR);
    }

    private void logBeliefRepairPlan(BeliefRepairPlan plan) {
        if (!debugLogEnabled) {
            return;
        }

        log("  [Belief-Repair] " + (plan.success ? "SUCCESS" : "FALLBACK")
                + " " + plan.label()
                + ", rawPreStates=" + plan.preUpdateStates.size()
                + ", fallbackClasses=" + plan.fallbackClasses
                + ", beliefNodes=" + plan.nodes.size()
                + ", resourceLimit=" + plan.failedByResourceLimit
                + ", resources=" + describeBeliefRepairResourceUsage(plan)
                + ", reason=" + plan.reason);

        if (plan.success) {
            for (BeliefNode node : plan.nodes) {
                if (!node.winning) {
                    continue;
                }
                String selected = node.selectedFinish
                        ? UpdateConstants.FINISH_UPDATE + "->NC:" + node.finishNcTargetId
                        : node.selectedControllableEdge != null
                                ? node.selectedControllableEdge.toString()
                                : node.selectedFairUncontrollableEdge != null
                                        ? "fair-wait:" + node.selectedFairUncontrollableEdge
                                        : (node.uncontrollableEdges.isEmpty() ? "none" : "wait-uncontrollable");
                log("    [Belief-Strategy] " + node.name()
                        + " U=" + describeBeliefEdges(node.uncontrollableEdges)
                        + " selected=" + selected
                        + " members=" + describeBeliefMembers(node));
            }
        }
    }

    private String describeBeliefMembers(BeliefNode node) {
        List<String> members = new ArrayList<>();
        int max = Math.min(4, node.members.size());
        for (int i = 0; i < max; i++) {
            members.add(summarizeStateForDiagnostics(node.members.get(i)));
        }
        if (node.members.size() > max) {
            members.add("... +" + (node.members.size() - max) + " states");
        }
        return members.toString();
    }

    private String describeBeliefEdges(List<BeliefTransition> edges) {
        if (edges.isEmpty()) {
            return "none";
        }
        List<String> descriptions = new ArrayList<>();
        for (BeliefTransition edge : edges) {
            descriptions.add(edge.toString());
        }
        return descriptions.toString();
    }

    private class RawDirectorEdge {
        private final HAction<State, Action> hAction;
        private final Action outputAction;
        private final CompostateDUC<State, Action> child;
        private final String actionName;

        private RawDirectorEdge(HAction<State, Action> hAction, CompostateDUC<State, Action> child) {
            this.hAction = hAction;
            this.outputAction = toOutputAction(hAction);
            this.child = child;
            this.actionName = outputAction.toString();
        }
    }

    private class RawNondetActionKey {
        private final CompostateDUC<State, Action> source;
        private final String actionName;

        private RawNondetActionKey(CompostateDUC<State, Action> source, String actionName) {
            this.source = source;
            this.actionName = actionName;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(source) + Objects.hashCode(actionName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DirectedControllerSynthesisDUC.RawNondetActionKey)) {
                return false;
            }
            RawNondetActionKey other = (RawNondetActionKey) obj;
            return source == other.source && Objects.equals(actionName, other.actionName);
        }
    }

    private class NondetActionRepairKey {
        private final int sourceClass;
        private final String actionName;

        private NondetActionRepairKey(int sourceClass, String actionName) {
            this.sourceClass = sourceClass;
            this.actionName = actionName;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceClass, actionName);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DirectedControllerSynthesisDUC.NondetActionRepairKey)) {
                return false;
            }
            NondetActionRepairKey other = (NondetActionRepairKey) obj;
            return sourceClass == other.sourceClass && Objects.equals(actionName, other.actionName);
        }
    }

    private class NondetActionBeliefRepairPlan {
        private final int sourceClass;
        private final Action outputAction;
        private final String actionName;
        private final Set<CompostateDUC<State, Action>> sourceStates = new LinkedHashSet<>();
        private final Set<CompostateDUC<State, Action>> rootMembers = new LinkedHashSet<>();
        private final Set<Integer> targetClasses = new LinkedHashSet<>();
        private BeliefRepairPlan beliefPlan;

        private NondetActionBeliefRepairPlan(int sourceClass, Action outputAction, String actionName) {
            this.sourceClass = sourceClass;
            this.outputAction = outputAction;
            this.actionName = actionName;
        }
    }

    private class NondetActionBeliefRepairResult {
        private final List<NondetActionBeliefRepairPlan> successPlans = new ArrayList<>();
        private final List<NondetActionBeliefRepairPlan> fallbackPlans = new ArrayList<>();
        private final Map<NondetActionRepairKey, NondetActionBeliefRepairPlan> successByKey =
                new LinkedHashMap<>();

        private void addSuccess(NondetActionBeliefRepairPlan plan) {
            successPlans.add(plan);
            successByKey.put(new NondetActionRepairKey(plan.sourceClass, plan.actionName), plan);
        }

        private void addFallback(NondetActionBeliefRepairPlan plan) {
            fallbackPlans.add(plan);
        }

        private NondetActionBeliefRepairPlan find(int sourceClass, Action outputAction) {
            return successByKey.get(new NondetActionRepairKey(sourceClass, outputAction.toString()));
        }
    }

    private class BeliefRepairResult {
        private final Map<State, BeliefRepairPlan> successPlans = new LinkedHashMap<>();
        private final Map<State, BeliefRepairPlan> fallbackPlans = new LinkedHashMap<>();
        private final Set<CompostateDUC<State, Action>> repairedPreUpdateStates = new HashSet<>();

        private void addSuccess(BeliefRepairPlan plan) {
            successPlans.put(plan.oldControllerState, plan);
            repairedPreUpdateStates.addAll(plan.preUpdateStates);
        }

        private void addFallback(BeliefRepairPlan plan) {
            fallbackPlans.put(plan.oldControllerState, plan);
        }

        private boolean isRepairedPreUpdateState(CompostateDUC<State, Action> state) {
            return repairedPreUpdateStates.contains(state);
        }
    }

    private class BeliefRepairPlan {
        private final State oldControllerState;
        private final List<CompostateDUC<State, Action>> preUpdateStates;
        private final List<Integer> fallbackClasses;
        private final List<BeliefNode> nodes = new ArrayList<>();
        private BeliefRepairResourceLimits resourceLimits;
        private int initialConcreteStateCount;
        private long initialRawTransitionCount;
        private long repairStartNanos;
        private int maxObservedBeliefNodes;
        private int maxObservedAdditionalConcreteStates;
        private long maxObservedAdditionalTransitions;
        private int reachableMapValuations;
        private Action beginUpdateAction;
        private BeliefNode root;
        private boolean success;
        private boolean failedByResourceLimit;
        private String reason = "not evaluated";
        private String label;

        private BeliefRepairPlan(
                State oldControllerState,
                List<CompostateDUC<State, Action>> preUpdateStates,
                List<Integer> fallbackClasses) {
            this.oldControllerState = oldControllerState;
            this.preUpdateStates = preUpdateStates;
            this.fallbackClasses = fallbackClasses;
            this.label = "oldControllerState=" + oldControllerState;
        }

        private String label() {
            return label;
        }

        private void fail(String reason) {
            this.success = false;
            this.reason = reason;
        }

        private void failByResource(String reason) {
            this.failedByResourceLimit = true;
            fail(reason);
        }
    }

    private class BeliefRepairResourceLimits {
        private final int maxBeliefNodes;
        private final int maxAdditionalConcreteStates;
        private final int maxAdditionalTransitions;
        private final long maxTimeMs;
        private final int reachableMapValuations;

        private BeliefRepairResourceLimits(
                int maxBeliefNodes,
                int maxAdditionalConcreteStates,
                int maxAdditionalTransitions,
                long maxTimeMs,
                int reachableMapValuations) {
            this.maxBeliefNodes = maxBeliefNodes;
            this.maxAdditionalConcreteStates = maxAdditionalConcreteStates;
            this.maxAdditionalTransitions = maxAdditionalTransitions;
            this.maxTimeMs = maxTimeMs;
            this.reachableMapValuations = reachableMapValuations;
        }

        @Override
        public String toString() {
            return "beliefNodes<=" + maxBeliefNodes
                    + ", additionalConcreteStates<=" + maxAdditionalConcreteStates
                    + ", additionalTransitions<=" + maxAdditionalTransitions
                    + ", timeMs<=" + maxTimeMs
                    + ", reachableMapValuations=" + reachableMapValuations;
        }
    }

    private enum BeliefRepairResourceLimitCause {
        BELIEF_NODES,
        ADDITIONAL_CONCRETE_STATES,
        ADDITIONAL_TRANSITIONS,
        TIME
    }

    private class BeliefSearchContext {
        private final Map<CompostateDUC<State, Action>, Integer> concreteIds;
        private final Map<List<Integer>, BeliefNode> nodesByKey = new LinkedHashMap<>();
        private final List<BeliefNode> nodes = new ArrayList<>();
        private final Deque<BeliefNode> queue = new ArrayDeque<>();
        private final int maxBeliefNodes;
        private boolean limitExceeded = false;

        private BeliefSearchContext(
                Map<CompostateDUC<State, Action>, Integer> concreteIds,
                int maxBeliefNodes) {
            this.concreteIds = concreteIds;
            this.maxBeliefNodes = maxBeliefNodes;
        }

        private BeliefNode getOrCreateNode(Set<CompostateDUC<State, Action>> members) {
            if (members == null || members.isEmpty()) {
                return null;
            }

            List<CompostateDUC<State, Action>> orderedMembers = new ArrayList<>(members);
            orderedMembers.sort((left, right) -> Integer.compare(
                    getConcreteStateId(concreteIds, left),
                    getConcreteStateId(concreteIds, right)));

            List<Integer> key = new ArrayList<>();
            for (CompostateDUC<State, Action> member : orderedMembers) {
                key.add(getConcreteStateId(concreteIds, member));
            }

            BeliefNode existing = nodesByKey.get(key);
            if (existing != null) {
                return existing;
            }

            if (nodes.size() >= maxBeliefNodes) {
                limitExceeded = true;
                return null;
            }

            BeliefNode created = new BeliefNode(nodes.size(), orderedMembers, key);
            nodesByKey.put(key, created);
            nodes.add(created);
            queue.add(created);
            return created;
        }
    }

    private class BeliefNode {
        private final int localId;
        private final List<CompostateDUC<State, Action>> members;
        private final List<Integer> memberIds;
        private final List<BeliefTransition> uncontrollableEdges = new ArrayList<>();
        private final List<BeliefTransition> controllableEdges = new ArrayList<>();
        private Action finishAction;
        private Long finishNcTargetId;
        private BeliefTransition selectedControllableEdge;
        private BeliefTransition selectedFairUncontrollableEdge;
        private boolean selectedFinish;
        private boolean winning;
        private boolean bad;
        private String badReason = "";

        private BeliefNode(
                int localId,
                List<CompostateDUC<State, Action>> members,
                List<Integer> memberIds) {
            this.localId = localId;
            this.members = members;
            this.memberIds = memberIds;
        }

        private String name() {
            return "B" + localId;
        }

        private void markBad(String reason) {
            bad = true;
            badReason = reason;
        }

        @Override
        public int hashCode() {
            return memberIds.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DirectedControllerSynthesisDUC.BeliefNode)) {
                return false;
            }
            BeliefNode other = (BeliefNode) obj;
            return memberIds.equals(other.memberIds);
        }
    }

    private class BeliefTransition {
        private final Action outputAction;
        private final boolean controllable;
        private final BeliefNode target;

        private BeliefTransition(Action outputAction, boolean controllable, BeliefNode target) {
            this.outputAction = outputAction;
            this.controllable = controllable;
            this.target = target;
        }

        @Override
        public String toString() {
            return outputAction + (controllable ? "(C)" : "(U)") + "->" + target.name();
        }
    }

    private class BeliefActionBucket {
        private final Action outputAction;
        private final boolean controllable;
        private final Set<Integer> presentMemberIndexes = new HashSet<>();
        private final Set<CompostateDUC<State, Action>> children = new LinkedHashSet<>();
        private boolean hasUnsafeChild;
        private String unsafeReason = "";

        private BeliefActionBucket(Action outputAction, boolean controllable) {
            this.outputAction = outputAction;
            this.controllable = controllable;
        }

        private String actionName() {
            return outputAction.toString() + (controllable ? "(C)" : "(U)");
        }
    }

    private class BeliefExpansionCandidate {
        private final BeliefRepairPlan plan;
        private final BeliefNode node;
        private final String actionName;
        private final boolean controllable;
        private final boolean updateProtocolAction;

        private BeliefExpansionCandidate(
                BeliefRepairPlan plan,
                BeliefNode node,
                String actionName,
                boolean controllable,
                boolean updateProtocolAction) {
            this.plan = plan;
            this.node = node;
            this.actionName = actionName;
            this.controllable = controllable;
            this.updateProtocolAction = updateProtocolAction;
        }

        private String actionClass() {
            if (!controllable) {
                return "uncontrollable";
            }
            return updateProtocolAction ? "update-event" : "ordinary-controllable";
        }
    }

    private class BeliefLazyExpansionResult {
        private final int round;
        private int attemptedActions;
        private int expandedActions;
        private int addedEdges;

        private BeliefLazyExpansionResult(int round) {
            this.round = round;
        }

        private boolean hasProgress() {
            return addedEdges > 0;
        }
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
     * - 更新前の hotSwapIn は、勝ち更新パスを持つ旧コントローラ状態から残す。
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
            if(debugLogEnabled){
                logDirectorPruningDecision(current, hAction, child, true,
                        "uncontrollable action is always preserved");
            }
            return true;
        }

        // anytime hotswap 要件: 勝ち更新パスを持つ旧コントローラ状態からは
        // hotSwapIn を出力に残す。
        if (getMarkingState(current) == 0
                && hAction.toString().equals(UpdateConstants.BEGIN_UPDATE)
                && isGoal(child)) {
            if(debugLogEnabled){
                logDirectorPruningDecision(current, hAction, child, true,
                        "hotSwapIn from a winning pre-update state");
            }
            return true;
        }

        HAction<State, Action> selected = getSelectedControllableAction(current);
        boolean toAdd = selected != null && selected.equals(hAction);
        if(debugLogEnabled){
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
        if (!profileLogEnabled) {
            return state.isStatus(Status.ERROR);
        }
        long start = System.nanoTime();
        isErrorChecks++;
        try {
            return state.isStatus(Status.ERROR);
        } finally {
            isErrorCheckNanos += System.nanoTime() - start;
        }
    }

    public boolean isFinished() {
        return initial.isStatus(Status.GOAL) || initial.isStatus(Status.ERROR);
    }

    public void setError(CompostateDUC<State, Action> state) {
        long start = 0;
        if (profileLogEnabled) {
            start = System.nanoTime();
            setErrorCalls++;
        }
        try {
            if (!isError(state)) {
                errorMarkCount++;
            }

            if (debugLogEnabled) {
                lastErrorSummary = summarizeStateForDiagnostics(state);
                log("[ERROR DETECTED] State marked as ERROR: " + state.getStates());
            }

            if (isGoal(state)) {
                for (Pair<HAction<State, Action>, CompostateDUC<State, Action>> parentRel : state.getParents()) {
                    invalidateAllChildrenGoalCache(parentRel.getSecond(), parentRel.getFirst());
                }
            }
            state.setStatus(Status.ERROR);

            heuristic.notifyStateSetErrorOrGoal(state);
        } finally {
            if (profileLogEnabled) {
                setErrorNanos += System.nanoTime() - start;
            }
        }
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

    protected String describeOtfExecutionMode() {
        String nondetRepair = nondeterministicActionBeliefRepairEnabled ? "+非決定action repair" : "";
        String fairness = finishUpdateFairnessEnabled ? "" : "+m8 fairnessなし";
        if (beliefRepairEnabled) {
            if (preUpdateSimpleMergeEnabled) {
                return "通常OTF-DUC+簡単マージ+repair" + nondetRepair + fairness;
            }
            return "通常OTF-DUC+repair" + nondetRepair + fairness;
        }
        if (preUpdateSimpleMergeEnabled) {
            return "通常OTF-DUC+簡単マージ" + nondetRepair + fairness;
        }
        return "通常OTF-DUC" + nondetRepair + fairness;
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
                "OTF-DUC 展開時間内訳", "hotSwapOut guard 判定時間", finishUpdateGuardNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 展開時間内訳", "hotSwapOut guard 判定回数", finishUpdateGuardChecks, "回");
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
        recordStrategy1TimingAndMemoryEvaluation();
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "出力遷移 pruning 判定時間", outputPruningDecisionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                "OTF-DUC 出力構築時間内訳", "出力遷移 pruning 判定平均時間", outputPruningDecisionNanos, outputPruningDecisionCalls);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "director グラフ走査・遷移構築時間", directorTraversalNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "旧コントローラ相当状態の belief 再探索時間", directorBeliefRepairNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                "OTF-DUC 出力構築時間内訳", "非決定 action belief repair 時間", directorNondetBeliefRepairNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "非決定 action belief repair 対象数", nondetBeliefRepairCandidateGroups, "箇所");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "非決定 action belief repair 成功数", nondetBeliefRepairSuccessGroups, "箇所");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "非決定 action belief repair fallback 数", nondetBeliefRepairFallbackGroups, "箇所");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "非決定 action belief repair 置換遷移数", nondetBeliefRepairReplacedTransitions, "本");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索の対象旧状態数", beliefRepairCandidateGroups, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で 1 対 1 に修復できた旧状態数", beliefRepairSuccessGroups, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で fallback した旧状態数", beliefRepairFallbackGroups, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で生成した belief 状態数", beliefRepairGeneratedNodes, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で資源上限により fallback した旧状態数", beliefRepairResourceLimitFallbacks, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で belief node 上限により fallback した旧状態数",
                beliefRepairResourceLimitBeliefNodeFallbacks, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で追加 concrete state 上限により fallback した旧状態数",
                beliefRepairResourceLimitAdditionalConcreteFallbacks, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で追加 transition 上限により fallback した旧状態数",
                beliefRepairResourceLimitAdditionalTransitionFallbacks, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で時間上限により fallback した旧状態数",
                beliefRepairResourceLimitTimeFallbacks, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で観測した最大 belief node 数", beliefRepairMaxObservedBeliefNodes, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で追加生成した最大 concrete state 数", beliefRepairMaxObservedAdditionalConcreteStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で追加生成した最大 transition 数", beliefRepairMaxObservedAdditionalTransitions, "遷移");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索中に有効な追加展開を行ったラウンド数", beliefLazyExpansionRounds, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索中に追加展開を試みた action 数", beliefLazyExpansionAttempts, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索中に実際に追加展開できた action 数", beliefLazyExpansionExpandedActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索の追加展開で増えた遷移数", beliefLazyExpansionAddedEdges, "遷移");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で破棄した unsafe controllable action 数",
                beliefUnsafeControllableDiscardCount, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で未展開 uncontrollable warning 数",
                beliefUnexploredUncontrollableWarningPlans, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で未展開 uncontrollable が残った belief node 数",
                beliefUnexploredUncontrollableWarningNodes, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "OTF-DUC 出力構築時間内訳", "belief 再探索で未展開 uncontrollable action 数",
                beliefUnexploredUncontrollableWarningActions, "回");
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 移設時間", transferNCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 接続時間", stitchingNCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "NC 移設時間 + NC 接続時間", transferNCTime + stitchingNCTime);
    }

    private void recordStrategy1TimingAndMemoryEvaluation() {
        if (!beliefRepairEnabled
                || strategy1NormalOtfSimpleMergeTime < 0
                || strategy1RepairTime < 0) {
            return;
        }

        final String section = "OTF-DUC 方針1 時間・メモリ内訳";
        UpdatingControllerEvaluationRecorder.recordTime(
                section, "通常OTF探索+簡単マージ時間", strategy1NormalOtfSimpleMergeTime);
        UpdatingControllerEvaluationRecorder.recordMemoryInterval(
                section,
                "通常OTF探索+簡単マージ",
                strategy1NormalOtfSimpleMergeBeforeMemory,
                strategy1NormalOtfSimpleMergePeakMemory);

        UpdatingControllerEvaluationRecorder.recordTime(
                section, "belief repair時間", strategy1RepairTime);
        UpdatingControllerEvaluationRecorder.recordMemoryInterval(
                section,
                "belief repair",
                strategy1RepairBeforeMemory,
                strategy1RepairPeakMemory);
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
