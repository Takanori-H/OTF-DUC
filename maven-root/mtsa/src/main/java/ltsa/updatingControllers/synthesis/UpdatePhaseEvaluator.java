package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.CompactState;
import ltsa.lts.EventState;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;

/**
 * Counts update-controller state spaces by update progress phase.
 *
 * Phases are represented by the three completion bits for stopOldSpec,
 * reconfigure, and startNewSpec after hotSwapIn. PRE is before hotSwapIn;
 * POST is after hotSwapOut, which only exists in OTF-DUC outputs.
 */
public final class UpdatePhaseEvaluator {

    private static final String EVALUATION_PROFILE_PROPERTY = "updating.controller.evaluation.profile";

    public static final String SECTION_TRADITIONAL = "Traditional DUC update phase 別状態空間";
    public static final String SECTION_OTF_EXPLORED = "OTF-DUC update phase 別探索規模";
    public static final String SECTION_OUTPUT = "Output Update Controller update phase 別状態空間";
    public static final String SECTION_TRADITIONAL_UPDATE_EVENTS = "Traditional DUC update event 別遷移数";
    public static final String SECTION_OTF_EXPLORED_UPDATE_EVENTS = "OTF-DUC update event 別探索遷移数";
    public static final String SECTION_OUTPUT_UPDATE_EVENTS = "Output Update Controller update event 別遷移数";
    public static final String SECTION_TRADITIONAL_PHASE_DETAILS = "Traditional DUC update phase 別遷移詳細";
    public static final String SECTION_OTF_EXPLORED_PHASE_DETAILS = "OTF-DUC update phase 別探索遷移詳細";
    public static final String SECTION_OUTPUT_PHASE_DETAILS = "Output Update Controller update phase 別遷移詳細";
    public static final String SECTION_TRADITIONAL_PHASE_FLOW = "Traditional DUC update phase 間遷移数";
    public static final String SECTION_OTF_EXPLORED_PHASE_FLOW = "OTF-DUC update phase 間探索遷移数";
    public static final String SECTION_OUTPUT_PHASE_FLOW = "Output Update Controller update phase 間遷移数";
    public static final String SECTION_TRADITIONAL_COMPLETION_PATH = "Traditional DUC hotSwapIn から更新完了までの距離";
    public static final String SECTION_OTF_EXPLORED_COMPLETION_PATH = "OTF-DUC hotSwapIn から更新完了までの探索距離";
    public static final String SECTION_OUTPUT_COMPLETION_PATH = "Output Update Controller hotSwapIn から更新完了までの距離";
    public static final String SECTION_TRADITIONAL_NORMAL_ACTIONS = "Traditional DUC update phase 別通常 action 遷移数";
    public static final String SECTION_OTF_EXPLORED_NORMAL_ACTIONS = "OTF-DUC update phase 別通常 action 探索遷移数";
    public static final String SECTION_OUTPUT_NORMAL_ACTIONS = "Output Update Controller update phase 別通常 action 遷移数";
    public static final String SECTION_TRADITIONAL_NEXT_UPDATE_EVENT_DISTANCE = "Traditional DUC 次更新事象までの距離";
    public static final String SECTION_OTF_EXPLORED_NEXT_UPDATE_EVENT_DISTANCE = "OTF-DUC 次更新事象までの探索距離";
    public static final String SECTION_OUTPUT_NEXT_UPDATE_EVENT_DISTANCE = "Output Update Controller 次更新事象までの距離";
    public static final String SECTION_TRADITIONAL_PROGRESS_FREE_CYCLES = "Traditional DUC progress-free cycle 統計";
    public static final String SECTION_OTF_EXPLORED_PROGRESS_FREE_CYCLES = "OTF-DUC progress-free cycle 統計";
    public static final String SECTION_OUTPUT_PROGRESS_FREE_CYCLES = "Output Update Controller progress-free cycle 統計";
    public static final String SECTION_TRADITIONAL_ENABLED_UPDATE_EVENTS = "Traditional DUC update phase 別 enabled update event 状態数";
    public static final String SECTION_OTF_EXPLORED_ENABLED_UPDATE_EVENTS = "OTF-DUC update phase 別 enabled update event 状態数";
    public static final String SECTION_OUTPUT_ENABLED_UPDATE_EVENTS = "Output Update Controller update phase 別 enabled update event 状態数";
    public static final String SECTION_TRADITIONAL_UPDATE_ORDER_PATTERNS = "Traditional DUC 更新順序パターン別状態空間";
    public static final String SECTION_OTF_EXPLORED_UPDATE_ORDER_PATTERNS = "OTF-DUC 更新順序パターン別探索規模";
    public static final String SECTION_OUTPUT_UPDATE_ORDER_PATTERNS = "Output Update Controller 更新順序パターン別状態空間";
    public static final String SECTION_TRADITIONAL_NORMAL_RUN_LENGTH = "Traditional DUC 更新事象間の通常遷移連続長";
    public static final String SECTION_OTF_EXPLORED_NORMAL_RUN_LENGTH = "OTF-DUC 更新事象間の通常遷移連続長";
    public static final String SECTION_OUTPUT_NORMAL_RUN_LENGTH = "Output Update Controller 更新事象間の通常遷移連続長";
    public static final String NORMAL_TRANSITION = "normal";

    public static final int PHASE_PRE = 0;
    public static final int PHASE_000 = 1;
    public static final int PHASE_100 = 2;
    public static final int PHASE_010 = 3;
    public static final int PHASE_110 = 4;
    public static final int PHASE_001 = 5;
    public static final int PHASE_101 = 6;
    public static final int PHASE_011 = 7;
    public static final int PHASE_111 = 8;
    public static final int PHASE_POST = 9;

    private static final int BIT_STOP = 1;
    private static final int BIT_RECONFIGURE = 2;
    private static final int BIT_START = 4;
    private static final String WORSE_RANK_PREFIX = "#w#_";

    private static final int[] PHASE_ORDER = {
            PHASE_PRE,
            PHASE_000,
            PHASE_100,
            PHASE_010,
            PHASE_110,
            PHASE_001,
            PHASE_101,
            PHASE_011,
            PHASE_111,
            PHASE_POST
    };

    private UpdatePhaseEvaluator() {
    }

    private static boolean isFullEvaluationProfile() {
        String profile = System.getProperty(EVALUATION_PROFILE_PROPERTY, "full");
        return !"compact".equalsIgnoreCase(profile) && !"lean".equalsIgnoreCase(profile);
    }

    public static int[] phaseOrder() {
        return PHASE_ORDER.clone();
    }

    public static int phaseFromMarkingState(long markingState) {
        if (markingState == 0) {
            return PHASE_PRE;
        }
        if (markingState >= 1 && markingState <= 8) {
            return (int) markingState;
        }
        if (markingState == 9) {
            return PHASE_POST;
        }
        return PHASE_PRE;
    }

    public static String phaseLabelForMarkingState(long markingState) {
        return phaseLabel(phaseFromMarkingState(markingState));
    }

    public static String phaseLabel(int phase) {
        switch (phase) {
            case PHASE_PRE:
                return "PRE";
            case PHASE_000:
                return "000";
            case PHASE_100:
                return "100";
            case PHASE_010:
                return "010";
            case PHASE_110:
                return "110";
            case PHASE_001:
                return "001";
            case PHASE_101:
                return "101";
            case PHASE_011:
                return "011";
            case PHASE_111:
                return "111";
            case PHASE_POST:
                return "POST";
            default:
                return "UNKNOWN";
        }
    }

    public static String phaseDescription(int phase) {
        switch (phase) {
            case PHASE_PRE:
                return "hotSwapIn 前。";
            case PHASE_000:
                return "hotSwapIn 後、stopOldSpec/reconfigure/startNewSpec は全て未完了。";
            case PHASE_100:
                return "hotSwapIn 後、stopOldSpec のみ完了。";
            case PHASE_010:
                return "hotSwapIn 後、reconfigure のみ完了。";
            case PHASE_110:
                return "hotSwapIn 後、stopOldSpec と reconfigure が完了。";
            case PHASE_001:
                return "hotSwapIn 後、startNewSpec のみ完了。";
            case PHASE_101:
                return "hotSwapIn 後、stopOldSpec と startNewSpec が完了。";
            case PHASE_011:
                return "hotSwapIn 後、reconfigure と startNewSpec が完了。";
            case PHASE_111:
                return "hotSwapIn 後、stopOldSpec/reconfigure/startNewSpec が全て完了。";
            case PHASE_POST:
                return "hotSwapOut 後。Traditional DUC には通常存在しない。";
            default:
                return "";
        }
    }

    public static String labelWithPhase(String artifactLabel, int phase) {
        return artifactLabel + " / updatePhase=" + phaseLabel(phase);
    }

    public static String transitionCategory(String action) {
        String normalizedAction = normalizeAction(action);
        if (UpdateConstants.BEGIN_UPDATE.equals(normalizedAction)
                || UpdateConstants.FINISH_UPDATE.equals(normalizedAction)) {
            return normalizedAction;
        }
        if (UpdateConstants.STOP_OLD_SPEC.equals(normalizedAction)
                || normalizedAction.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)) {
            return UpdateConstants.STOP_OLD_SPEC;
        }
        if (UpdateConstants.RECONFIGURE.equals(normalizedAction)
                || normalizedAction.startsWith(UpdateConstants.RECONFIGURE_PREFIX)) {
            return UpdateConstants.RECONFIGURE;
        }
        if (UpdateConstants.START_NEW_SPEC.equals(normalizedAction)
                || normalizedAction.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX)) {
            return UpdateConstants.START_NEW_SPEC;
        }
        return NORMAL_TRANSITION;
    }

    public static void recordMtsUpdatePhaseStateSpace(
            String section,
            String artifactLabel,
            MTS<Long, String> mts) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (mts == null) {
            return;
        }

        long totalCountTime = 0;
        for (int phase : PHASE_ORDER) {
            PhaseCount count = countMtsPhase(mts, phase);
            totalCountTime += count.countTimeMillis;
            UpdatingControllerEvaluationRecorder.recordStateSpace(
                    section,
                    labelWithPhase(artifactLabel, phase),
                    count.states,
                    count.transitions,
                    count.countTimeMillis,
                    phaseDescription(phase));
        }
        UpdatingControllerEvaluationRecorder.recordUpdatePhaseCountTimeTotal(
                section,
                artifactLabel,
                totalCountTime);
    }

    public static void recordMtsUpdateEventTransitionCounts(
            String section,
            String artifactLabel,
            MTS<Long, String> mts) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (mts == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        TransitionCategoryCount counts = new TransitionCategoryCount();
        for (Long state : mts.getStates()) {
            countMtsUpdateEventTransitions(mts, state, MTS.TransitionType.REQUIRED, counts);
            countMtsUpdateEventTransitions(mts, state, MTS.TransitionType.MAYBE, counts);
        }
        long countTime = System.currentTimeMillis() - countStart;
        counts.record(section, artifactLabel, countTime);
    }

    public static void recordMtsUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String artifactLabel,
            MTS<Long, String> mts) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        recordMtsUpdatePhaseTransitionAnalysis(
                detailSection,
                flowSection,
                completionPathSection,
                null,
                null,
                null,
                null,
                artifactLabel,
                mts,
                null);
    }

    public static void recordMtsUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String normalActionSection,
            String nextUpdateEventDistanceSection,
            String progressFreeCycleSection,
            String enabledUpdateEventSection,
            String artifactLabel,
            MTS<Long, String> mts,
            Set<String> controllableActions) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        recordMtsUpdatePhaseTransitionAnalysis(
                detailSection,
                flowSection,
                completionPathSection,
                normalActionSection,
                nextUpdateEventDistanceSection,
                progressFreeCycleSection,
                enabledUpdateEventSection,
                null,
                null,
                artifactLabel,
                mts,
                controllableActions);
    }

    public static void recordMtsUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String normalActionSection,
            String nextUpdateEventDistanceSection,
            String progressFreeCycleSection,
            String enabledUpdateEventSection,
            String updateOrderPatternSection,
            String normalRunLengthSection,
            String artifactLabel,
            MTS<Long, String> mts,
            Set<String> controllableActions) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (mts == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        PhaseTransitionAnalysis<MtsPhaseNode> analysis = new PhaseTransitionAnalysis<>();
        Set<MtsPhaseNode> visited = new HashSet<>();
        Queue<MtsPhaseNode> queue = new ArrayDeque<>();

        MtsPhaseNode initial = new MtsPhaseNode(mts.getInitialState(), PHASE_PRE);
        analysis.setInitialNode(initial);
        visited.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            MtsPhaseNode current = queue.remove();
            analysis.addState(current, current.phase);
            visitMtsAnalysisTransitions(mts, current, analysis, visited, queue, MTS.TransitionType.REQUIRED, controllableActions);
            visitMtsAnalysisTransitions(mts, current, analysis, visited, queue, MTS.TransitionType.MAYBE, controllableActions);
        }

        analysis.record(
                detailSection,
                flowSection,
                completionPathSection,
                normalActionSection,
                nextUpdateEventDistanceSection,
                progressFreeCycleSection,
                enabledUpdateEventSection,
                updateOrderPatternSection,
                normalRunLengthSection,
                artifactLabel,
                System.currentTimeMillis() - countStart);
    }

    public static void recordCompactStateUpdatePhaseStateSpace(
            String section,
            String artifactLabel,
            CompactState machine) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (machine == null || machine.states == null) {
            return;
        }

        long totalCountTime = 0;
        for (int phase : PHASE_ORDER) {
            PhaseCount count = countCompactStatePhase(machine, phase);
            totalCountTime += count.countTimeMillis;
            UpdatingControllerEvaluationRecorder.recordStateSpace(
                    section,
                    labelWithPhase(artifactLabel, phase),
                    count.states,
                    count.transitions,
                    count.countTimeMillis,
                    phaseDescription(phase));
        }
        UpdatingControllerEvaluationRecorder.recordUpdatePhaseCountTimeTotal(
                section,
                artifactLabel,
                totalCountTime);
    }

    public static void recordCompactStateUpdateEventTransitionCounts(
            String section,
            String artifactLabel,
            CompactState machine) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (machine == null || machine.states == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        TransitionCategoryCount counts = new TransitionCategoryCount();
        for (int i = 0; i < machine.states.length && i < machine.maxStates; i++) {
            EventState head = machine.states[i];
            if (head == null) {
                continue;
            }

            Enumeration<EventState> transitions = head.elements();
            while (transitions.hasMoreElements()) {
                counts.add(actionName(machine, transitions.nextElement()));
            }
        }
        long countTime = System.currentTimeMillis() - countStart;
        counts.record(section, artifactLabel, countTime);
    }

    public static void recordCompactStateUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String artifactLabel,
            CompactState machine) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        recordCompactStateUpdatePhaseTransitionAnalysis(
                detailSection,
                flowSection,
                completionPathSection,
                null,
                null,
                null,
                null,
                artifactLabel,
                machine,
                null);
    }

    public static void recordCompactStateUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String normalActionSection,
            String nextUpdateEventDistanceSection,
            String progressFreeCycleSection,
            String enabledUpdateEventSection,
            String artifactLabel,
            CompactState machine,
            Set<String> controllableActions) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        recordCompactStateUpdatePhaseTransitionAnalysis(
                detailSection,
                flowSection,
                completionPathSection,
                normalActionSection,
                nextUpdateEventDistanceSection,
                progressFreeCycleSection,
                enabledUpdateEventSection,
                null,
                null,
                artifactLabel,
                machine,
                controllableActions);
    }

    public static void recordCompactStateUpdatePhaseTransitionAnalysis(
            String detailSection,
            String flowSection,
            String completionPathSection,
            String normalActionSection,
            String nextUpdateEventDistanceSection,
            String progressFreeCycleSection,
            String enabledUpdateEventSection,
            String updateOrderPatternSection,
            String normalRunLengthSection,
            String artifactLabel,
            CompactState machine,
            Set<String> controllableActions) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (machine == null || machine.states == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        PhaseTransitionAnalysis<CompactPhaseNode> analysis = new PhaseTransitionAnalysis<>();
        Set<CompactPhaseNode> visited = new HashSet<>();
        Queue<CompactPhaseNode> queue = new ArrayDeque<>();

        CompactPhaseNode initial = new CompactPhaseNode(0, PHASE_PRE);
        analysis.setInitialNode(initial);
        visited.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            CompactPhaseNode current = queue.remove();
            if (!isValidCompactState(machine, current.state)) {
                continue;
            }

            analysis.addState(current, current.phase);
            EventState head = machine.states[current.state];
            if (head == null) {
                continue;
            }

            Enumeration<EventState> transitions = head.elements();
            while (transitions.hasMoreElements()) {
                EventState transition = transitions.nextElement();
                String action = actionName(machine, transition);
                int nextState = transition.getNext();
                if (!isValidCompactState(machine, nextState)) {
                    continue;
                }
                int nextPhase = nextPhase(current.phase, action);
                CompactPhaseNode next = new CompactPhaseNode(nextState, nextPhase);
                analysis.addTransition(current, current.phase, next, nextPhase, action,
                        isControllable(action, controllableActions));
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        analysis.record(
                detailSection,
                flowSection,
                completionPathSection,
                normalActionSection,
                nextUpdateEventDistanceSection,
                progressFreeCycleSection,
                enabledUpdateEventSection,
                updateOrderPatternSection,
                normalRunLengthSection,
                artifactLabel,
                System.currentTimeMillis() - countStart);
    }

    private static void countMtsUpdateEventTransitions(
            MTS<Long, String> mts,
            Long state,
            MTS.TransitionType transitionType,
            TransitionCategoryCount counts) {
        for (Pair<String, Long> transition : mts.getTransitions(state, transitionType)) {
            counts.add(transition.getFirst());
        }
    }

    private static void visitMtsAnalysisTransitions(
            MTS<Long, String> mts,
            MtsPhaseNode current,
            PhaseTransitionAnalysis<MtsPhaseNode> analysis,
            Set<MtsPhaseNode> visited,
            Queue<MtsPhaseNode> queue,
            MTS.TransitionType transitionType,
            Set<String> controllableActions) {
        for (Pair<String, Long> transition : mts.getTransitions(current.state, transitionType)) {
            String action = transition.getFirst();
            int nextPhase = nextPhase(current.phase, action);
            MtsPhaseNode next = new MtsPhaseNode(transition.getSecond(), nextPhase);
            analysis.addTransition(current, current.phase, next, nextPhase, action,
                    isControllable(action, controllableActions));
            if (visited.add(next)) {
                queue.add(next);
            }
        }
    }

    private static PhaseCount countMtsPhase(MTS<Long, String> mts, int targetPhase) {
        long countStart = System.currentTimeMillis();

        Set<MtsPhaseNode> visited = new HashSet<>();
        Queue<MtsPhaseNode> queue = new ArrayDeque<>();
        Set<Long> phaseStates = new HashSet<>();
        long phaseTransitions = 0;

        MtsPhaseNode initial = new MtsPhaseNode(mts.getInitialState(), PHASE_PRE);
        visited.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            MtsPhaseNode current = queue.remove();
            if (current.phase == targetPhase) {
                phaseStates.add(current.state);
            }

            phaseTransitions += visitMtsTransitions(
                    mts,
                    current,
                    targetPhase,
                    visited,
                    queue,
                    MTS.TransitionType.REQUIRED);
            phaseTransitions += visitMtsTransitions(
                    mts,
                    current,
                    targetPhase,
                    visited,
                    queue,
                    MTS.TransitionType.MAYBE);
        }

        long countTime = System.currentTimeMillis() - countStart;
        return new PhaseCount(phaseStates.size(), phaseTransitions, countTime);
    }

    private static long visitMtsTransitions(
            MTS<Long, String> mts,
            MtsPhaseNode current,
            int targetPhase,
            Set<MtsPhaseNode> visited,
            Queue<MtsPhaseNode> queue,
            MTS.TransitionType transitionType) {
        long countedTransitions = 0;
        for (Pair<String, Long> transition : mts.getTransitions(current.state, transitionType)) {
            if (current.phase == targetPhase) {
                countedTransitions++;
            }
            int nextPhase = nextPhase(current.phase, transition.getFirst());
            MtsPhaseNode next = new MtsPhaseNode(transition.getSecond(), nextPhase);
            if (visited.add(next)) {
                queue.add(next);
            }
        }
        return countedTransitions;
    }

    private static PhaseCount countCompactStatePhase(CompactState machine, int targetPhase) {
        long countStart = System.currentTimeMillis();

        Set<CompactPhaseNode> visited = new HashSet<>();
        Queue<CompactPhaseNode> queue = new ArrayDeque<>();
        Set<Integer> phaseStates = new HashSet<>();
        long phaseTransitions = 0;

        CompactPhaseNode initial = new CompactPhaseNode(0, PHASE_PRE);
        visited.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            CompactPhaseNode current = queue.remove();
            if (!isValidCompactState(machine, current.state)) {
                continue;
            }
            if (current.phase == targetPhase) {
                phaseStates.add(current.state);
            }

            EventState head = machine.states[current.state];
            if (head == null) {
                continue;
            }

            Enumeration<EventState> transitions = head.elements();
            while (transitions.hasMoreElements()) {
                EventState transition = transitions.nextElement();
                String action = actionName(machine, transition);
                if (current.phase == targetPhase) {
                    phaseTransitions++;
                }
                int nextState = transition.getNext();
                if (!isValidCompactState(machine, nextState)) {
                    continue;
                }
                int nextPhase = nextPhase(current.phase, action);
                CompactPhaseNode next = new CompactPhaseNode(nextState, nextPhase);
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }

        long countTime = System.currentTimeMillis() - countStart;
        return new PhaseCount(phaseStates.size(), phaseTransitions, countTime);
    }

    private static boolean isValidCompactState(CompactState machine, int state) {
        return state >= 0 && state < machine.maxStates && state < machine.states.length;
    }

    private static String actionName(CompactState machine, EventState transition) {
        int event = transition.getEvent();
        if (machine.alphabet == null || event < 0 || event >= machine.alphabet.length) {
            return "";
        }
        return machine.alphabet[event];
    }

    private static int nextPhase(int phase, String action) {
        String category = transitionCategory(action);
        if (phase == PHASE_POST) {
            return PHASE_POST;
        }
        if (phase == PHASE_PRE) {
            return UpdateConstants.BEGIN_UPDATE.equals(category) ? PHASE_000 : PHASE_PRE;
        }

        if (UpdateConstants.FINISH_UPDATE.equals(category)) {
            return PHASE_POST;
        }
        if (UpdateConstants.BEGIN_UPDATE.equals(category)) {
            return phase;
        }

        int mask = phase - 1;
        if (UpdateConstants.STOP_OLD_SPEC.equals(category)) {
            mask |= BIT_STOP;
        } else if (UpdateConstants.RECONFIGURE.equals(category)) {
            mask |= BIT_RECONFIGURE;
        } else if (UpdateConstants.START_NEW_SPEC.equals(category)) {
            mask |= BIT_START;
        }
        return 1 + mask;
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            return "";
        }
        String normalized = action;
        if (normalized.startsWith(WORSE_RANK_PREFIX)) {
            normalized = normalized.substring(WORSE_RANK_PREFIX.length());
        }
        if (normalized.endsWith(".old")) {
            normalized = normalized.substring(0, normalized.length() - ".old".length());
        }
        return normalized;
    }

    private static Boolean isControllable(String action, Set<String> controllableActions) {
        if (controllableActions == null) {
            return null;
        }
        String normalizedAction = normalizeAction(action);
        return controllableActions.contains(normalizedAction)
                || controllableActions.contains(action);
    }

    public static final class PhaseTransitionAnalysis<N> {
        private final PhaseTransitionDetail[] details = new PhaseTransitionDetail[PHASE_POST + 1];
        private final long[][] phaseFlow = new long[PHASE_POST + 1][PHASE_POST + 1];
        private final Map<N, Integer> nodePhase = new HashMap<>();
        private final Map<N, Integer> outDegree = new HashMap<>();
        private final Map<N, List<N>> reverseEdges = new HashMap<>();
        private final Map<N, List<AnalysisEdge<N>>> normalEdges = new HashMap<>();
        private final Map<N, List<AnalysisEdge<N>>> allEdges = new HashMap<>();
        private final Map<N, List<N>> reverseNormalEdges = new HashMap<>();
        private final Map<N, List<N>> controllableNormalEdges = new HashMap<>();
        private final Map<N, Set<String>> enabledUpdateEventsByNode = new HashMap<>();
        private final Map<String, List<N>> updateEventTargets = new HashMap<>();
        private final List<N> beginUpdateTargets = new ArrayList<>();
        private final Set<N> postCompletionNodes = new HashSet<>();
        private final Set<N> allThreeUpdateEventsDoneNodes = new HashSet<>();
        private N initialNode;
        private boolean hasControllabilityInfo;

        public PhaseTransitionAnalysis() {
            for (int phase : PHASE_ORDER) {
                details[phase] = new PhaseTransitionDetail();
            }
        }

        public void setInitialNode(N initialNode) {
            this.initialNode = initialNode;
        }

        public void addState(N node, int phase) {
            if (!isKnownPhase(phase) || node == null) {
                return;
            }
            if (!nodePhase.containsKey(node)) {
                nodePhase.put(node, phase);
                outDegree.put(node, 0);
                reverseEdges.computeIfAbsent(node, ignored -> new ArrayList<>());
                if (phase == PHASE_POST) {
                    postCompletionNodes.add(node);
                } else if (phase == PHASE_111) {
                    allThreeUpdateEventsDoneNodes.add(node);
                }
            }
        }

        public void addTransition(N source, int fromPhase, N target, int toPhase, String action) {
            addTransition(source, fromPhase, target, toPhase, action, null);
        }

        public void addTransition(
                N source,
                int fromPhase,
                N target,
                int toPhase,
                String action,
                Boolean controllable) {
            if (!isKnownPhase(fromPhase) || !isKnownPhase(toPhase) || source == null || target == null) {
                return;
            }

            addState(source, fromPhase);
            addState(target, toPhase);
            String category = transitionCategory(action);
            boolean fullEvaluationProfile = isFullEvaluationProfile();
            String normalizedAction = fullEvaluationProfile ? normalizeAction(action) : "";
            details[fromPhase].addTransition(action, fromPhase == toPhase, controllable);
            phaseFlow[fromPhase][toPhase]++;
            outDegree.put(source, outDegree.getOrDefault(source, 0) + 1);
            reverseEdges.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
            if (fullEvaluationProfile) {
                allEdges.computeIfAbsent(source, ignored -> new ArrayList<>())
                        .add(new AnalysisEdge<>(target, normalizedAction, controllable));
            }

            if (controllable != null) {
                hasControllabilityInfo = true;
            }
            if (NORMAL_TRANSITION.equals(category)) {
                reverseNormalEdges.computeIfAbsent(target, ignored -> new ArrayList<>()).add(source);
                if (fullEvaluationProfile) {
                    normalEdges.computeIfAbsent(source, ignored -> new ArrayList<>())
                            .add(new AnalysisEdge<>(target, normalizedAction, controllable));
                    if (Boolean.TRUE.equals(controllable)) {
                        controllableNormalEdges.computeIfAbsent(source, ignored -> new ArrayList<>()).add(target);
                    }
                }
            } else {
                enabledUpdateEventsByNode
                        .computeIfAbsent(source, ignored -> new HashSet<>())
                        .add(category);
                updateEventTargets
                        .computeIfAbsent(category, ignored -> new ArrayList<>())
                        .add(target);
            }

            if (fromPhase == PHASE_PRE && UpdateConstants.BEGIN_UPDATE.equals(category)) {
                beginUpdateTargets.add(target);
            }
        }

        public void record(
                String detailSection,
                String flowSection,
                String completionPathSection,
                String normalActionSection,
                String nextUpdateEventDistanceSection,
                String progressFreeCycleSection,
                String enabledUpdateEventSection,
                String updateOrderPatternSection,
                String normalRunLengthSection,
                String artifactLabel,
                long countTimeMillis) {

            for (Map.Entry<N, Integer> entry : nodePhase.entrySet()) {
                int phase = entry.getValue();
                if (isKnownPhase(phase)) {
                    details[phase].addStateOutDegree(outDegree.getOrDefault(entry.getKey(), 0));
                }
            }

            for (int phase : PHASE_ORDER) {
                PhaseTransitionDetail detail = details[phase];
                UpdatingControllerEvaluationRecorder.recordUpdatePhaseTransitionDetail(
                        detailSection,
                        artifactLabel,
                        phaseLabel(phase),
                        detail.states,
                        detail.counts.getBeginUpdateTransitions(),
                        detail.counts.getStopOldSpecTransitions(),
                        detail.counts.getReconfigureTransitions(),
                        detail.counts.getStartNewSpecTransitions(),
                        detail.counts.getFinishUpdateTransitions(),
                        detail.counts.getNormalTransitions(),
                        detail.samePhaseNormalTransitions,
                        detail.averageOutDegree(),
                        detail.maxOutDegree,
                        detail.normalTransitionRate());
            }
            UpdatingControllerEvaluationRecorder.recordUpdatePhaseTransitionDetailCountTime(
                    detailSection,
                    artifactLabel,
                    countTimeMillis);

            for (int fromPhase : PHASE_ORDER) {
                for (int toPhase : PHASE_ORDER) {
                    long transitions = phaseFlow[fromPhase][toPhase];
                    if (transitions == 0) {
                        continue;
                    }
                    UpdatingControllerEvaluationRecorder.recordUpdatePhaseFlowCount(
                            flowSection,
                            artifactLabel,
                            phaseLabel(fromPhase),
                            phaseLabel(toPhase),
                            transitions);
                }
            }
            UpdatingControllerEvaluationRecorder.recordUpdatePhaseFlowCountTime(
                    flowSection,
                    artifactLabel,
                    countTimeMillis);

            CompletionPathStats<N> stats = computeCompletionPathStats();
            UpdatingControllerEvaluationRecorder.recordUpdateCompletionPathStats(
                    completionPathSection,
                    artifactLabel,
                    stats.completionPhaseLabel,
                    stats.beginUpdateTransitions,
                    stats.reachableBeginUpdateTransitions,
                    stats.unreachableBeginUpdateTransitions,
                    stats.minPathLength,
                    stats.maxShortestPathLength,
                    stats.averageShortestPathLength,
                    countTimeMillis);
            if (isFullEvaluationProfile()) {
                UpdatingControllerEvaluationRecorder.recordUpdateCompletionPathLengthDistribution(
                        completionPathSection,
                        artifactLabel,
                        stats.lengthDistribution);
                recordCompletionDistanceByPhase(completionPathSection, artifactLabel, stats.distanceToCompletion);
            }

            Map<N, Integer> distanceToNextUpdateEvent = null;
            if (shouldRecord(normalRunLengthSection)
                    || (isFullEvaluationProfile() && shouldRecord(nextUpdateEventDistanceSection))) {
                distanceToNextUpdateEvent = computeDistanceToNextUpdateEvent();
            }

            if (isFullEvaluationProfile()) {
                recordNormalActionBreakdown(normalActionSection, artifactLabel, countTimeMillis);
                recordNextUpdateEventDistances(nextUpdateEventDistanceSection, artifactLabel, countTimeMillis,
                        distanceToNextUpdateEvent);
                recordProgressFreeCycles(progressFreeCycleSection, artifactLabel, countTimeMillis);
                recordEnabledUpdateEvents(enabledUpdateEventSection, artifactLabel, countTimeMillis);
                recordUpdateOrderPatterns(updateOrderPatternSection, artifactLabel, countTimeMillis);
            }
            recordNormalRunLengths(normalRunLengthSection, artifactLabel, countTimeMillis,
                    distanceToNextUpdateEvent);
        }

        private boolean shouldRecord(String section) {
            return section != null && !section.isEmpty();
        }

        private void recordNormalActionBreakdown(
                String section,
                String artifactLabel,
                long countTimeMillis) {
            if (section == null || section.isEmpty()) {
                return;
            }

            for (int phase : PHASE_ORDER) {
                PhaseTransitionDetail detail = details[phase];
                UpdatingControllerEvaluationRecorder.recordUpdatePhaseNormalControllability(
                        section,
                        artifactLabel,
                        phaseLabel(phase),
                        detail.normalControllableTransitions,
                        detail.normalUncontrollableTransitions,
                        detail.normalUnknownControllabilityTransitions);
                for (Map.Entry<String, Long> entry : detail.normalActionTransitions.entrySet()) {
                    UpdatingControllerEvaluationRecorder.recordUpdatePhaseNormalActionTransitionCount(
                            section,
                            artifactLabel,
                            phaseLabel(phase),
                            entry.getKey(),
                            entry.getValue());
                }
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "normal action transition CountTime",
                    countTimeMillis);
        }

        private void recordNextUpdateEventDistances(
                String section,
                String artifactLabel,
                long countTimeMillis,
                Map<N, Integer> distanceToNextUpdateEvent) {
            if (section == null || section.isEmpty()) {
                return;
            }

            CompletionDistanceByPhase[] distances = new CompletionDistanceByPhase[PHASE_POST + 1];
            for (int phase : PHASE_ORDER) {
                distances[phase] = new CompletionDistanceByPhase();
            }

            for (Map.Entry<N, Integer> entry : nodePhase.entrySet()) {
                int phase = entry.getValue();
                if (!isKnownPhase(phase)) {
                    continue;
                }
                distances[phase].add(distanceToNextUpdateEvent.get(entry.getKey()));
            }

            for (int phase : PHASE_ORDER) {
                CompletionDistanceByPhase distance = distances[phase];
                UpdatingControllerEvaluationRecorder.recordNextUpdateEventDistanceByPhase(
                        section,
                        artifactLabel,
                        phaseLabel(phase),
                        distance.states,
                        distance.reachableStates,
                        distance.unreachableStates,
                        distance.minDistance(),
                        distance.maxDistance(),
                        distance.averageDistance());
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "next update event distance CountTime",
                    countTimeMillis);
        }

        private Map<N, Integer> computeDistanceToNextUpdateEvent() {
            Map<N, Integer> distance = new HashMap<>();
            Queue<N> queue = new ArrayDeque<>();
            for (N node : enabledUpdateEventsByNode.keySet()) {
                distance.put(node, 0);
                queue.add(node);
            }

            while (!queue.isEmpty()) {
                N current = queue.remove();
                int currentDistance = distance.get(current);
                List<N> predecessors = reverseNormalEdges.get(current);
                if (predecessors == null) {
                    continue;
                }
                for (N predecessor : predecessors) {
                    if (!distance.containsKey(predecessor)) {
                        distance.put(predecessor, currentDistance + 1);
                        queue.add(predecessor);
                    }
                }
            }
            return distance;
        }

        private void recordProgressFreeCycles(
                String section,
                String artifactLabel,
                long countTimeMillis) {
            if (section == null || section.isEmpty()) {
                return;
            }

            CycleStats[] normalCycleStats = computeCycleStats(normalEdges);
            CycleStats[] controllableCycleStats = hasControllabilityInfo
                    ? computeCycleStatsFromTargets(controllableNormalEdges)
                    : null;
            for (int phase : PHASE_ORDER) {
                CycleStats normalStats = normalCycleStats[phase];
                CycleStats controllableStats = controllableCycleStats == null
                        ? CycleStats.unavailable()
                        : controllableCycleStats[phase];
                UpdatingControllerEvaluationRecorder.recordProgressFreeCycleStats(
                        section,
                        artifactLabel,
                        phaseLabel(phase),
                        normalStats.cycleSccs,
                        normalStats.statesInCycleSccs,
                        normalStats.maxCycleSccSize,
                        normalStats.selfLoopCycleSccs,
                        controllableStats.cycleSccs,
                        controllableStats.statesInCycleSccs,
                        controllableStats.maxCycleSccSize);
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "progress-free cycle CountTime",
                    countTimeMillis);
        }

        private void recordEnabledUpdateEvents(
                String section,
                String artifactLabel,
                long countTimeMillis) {
            if (section == null || section.isEmpty()) {
                return;
            }

            EnabledUpdateEventsByPhase[] enabled = new EnabledUpdateEventsByPhase[PHASE_POST + 1];
            for (int phase : PHASE_ORDER) {
                enabled[phase] = new EnabledUpdateEventsByPhase();
            }

            for (Map.Entry<N, Set<String>> entry : enabledUpdateEventsByNode.entrySet()) {
                Integer phase = nodePhase.get(entry.getKey());
                if (phase == null || !isKnownPhase(phase)) {
                    continue;
                }
                enabled[phase].add(entry.getValue());
            }

            for (int phase : PHASE_ORDER) {
                EnabledUpdateEventsByPhase count = enabled[phase];
                UpdatingControllerEvaluationRecorder.recordEnabledUpdateEventStates(
                        section,
                        artifactLabel,
                        phaseLabel(phase),
                        count.anyUpdateEventStates,
                        count.beginUpdateStates,
                        count.stopOldSpecStates,
                        count.reconfigureStates,
                        count.startNewSpecStates,
                        count.finishUpdateStates);
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "enabled update event CountTime",
                    countTimeMillis);
        }

        private void recordUpdateOrderPatterns(
                String section,
                String artifactLabel,
                long countTimeMillis) {
            if (section == null || section.isEmpty()) {
                return;
            }

            Map<String, OrderPatternStats<N>> patternStats = computeUpdateOrderPatternStats();
            for (Map.Entry<String, OrderPatternStats<N>> entry : patternStats.entrySet()) {
                OrderPatternStats<N> stats = entry.getValue();
                UpdatingControllerEvaluationRecorder.recordUpdateOrderPatternStats(
                        section,
                        artifactLabel,
                        entry.getKey(),
                        stats.phaseLabel(),
                        stats.stateOccurrences,
                        stats.uniqueStates.size(),
                        stats.transitions,
                        stats.normalTransitions,
                        stats.updateEventTransitions);
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "update order pattern CountTime",
                    countTimeMillis);
        }

        private Map<String, OrderPatternStats<N>> computeUpdateOrderPatternStats() {
            Map<String, OrderPatternStats<N>> stats = new TreeMap<>();
            if (initialNode == null) {
                return stats;
            }

            Set<OrderPatternNode<N>> visited = new HashSet<>();
            Queue<OrderPatternNode<N>> queue = new ArrayDeque<>();
            OrderPatternNode<N> initial = new OrderPatternNode<>(initialNode, "PRE");
            visited.add(initial);
            queue.add(initial);

            while (!queue.isEmpty()) {
                OrderPatternNode<N> current = queue.remove();
                int phase = nodePhase.getOrDefault(current.node, PHASE_PRE);
                stats.computeIfAbsent(current.pattern, ignored -> new OrderPatternStats<>())
                        .addState(current.node, phase);

                List<AnalysisEdge<N>> edges = allEdges.get(current.node);
                if (edges == null) {
                    continue;
                }
                for (AnalysisEdge<N> edge : edges) {
                    String category = transitionCategory(edge.action);
                    OrderPatternStats<N> currentStats =
                            stats.computeIfAbsent(current.pattern, ignored -> new OrderPatternStats<>());
                    currentStats.addTransition(category);

                    String nextPattern = nextUpdateOrderPattern(current.pattern, edge.action);
                    OrderPatternNode<N> next = new OrderPatternNode<>(edge.target, nextPattern);
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
            return stats;
        }

        private String nextUpdateOrderPattern(String currentPattern, String action) {
            String category = transitionCategory(action);
            if (NORMAL_TRANSITION.equals(category)) {
                return currentPattern;
            }
            if ("PRE".equals(currentPattern)) {
                return category;
            }
            if (patternContainsEvent(currentPattern, category)) {
                return currentPattern;
            }
            return currentPattern + ">" + category;
        }

        private boolean patternContainsEvent(String pattern, String event) {
            if (pattern == null || event == null) {
                return false;
            }
            String[] events = pattern.split(">");
            for (String existing : events) {
                if (event.equals(existing)) {
                    return true;
                }
            }
            return false;
        }

        private void recordNormalRunLengths(
                String section,
                String artifactLabel,
                long countTimeMillis,
                Map<N, Integer> distanceToNextUpdateEvent) {
            if (section == null || section.isEmpty()) {
                return;
            }

            for (String updateEvent : updateEventOrder()) {
                NormalRunLengthStats stats = new NormalRunLengthStats();
                List<N> targets = updateEventTargets.get(updateEvent);
                if (targets != null) {
                    for (N target : targets) {
                        stats.add(distanceToNextUpdateEvent.get(target));
                    }
                }
                UpdatingControllerEvaluationRecorder.recordNormalRunLengthStats(
                        section,
                        artifactLabel,
                        updateEvent,
                        stats.samples,
                        stats.reachableSamples,
                        stats.unreachableSamples,
                        stats.minLength(),
                        stats.maxLength(),
                        stats.averageLength());
                if (isFullEvaluationProfile()) {
                    UpdatingControllerEvaluationRecorder.recordNormalRunLengthDistribution(
                            section,
                            artifactLabel,
                            updateEvent,
                            stats.lengthDistribution);
                }
            }
            UpdatingControllerEvaluationRecorder.recordSharedDiagnosticCountTime(
                    section,
                    artifactLabel,
                    "normal run length CountTime",
                    countTimeMillis);
        }

        private void recordCompletionDistanceByPhase(
                String section,
                String artifactLabel,
                Map<N, Integer> distanceToCompletion) {

            CompletionDistanceByPhase[] distances = new CompletionDistanceByPhase[PHASE_POST + 1];
            for (int phase : PHASE_ORDER) {
                distances[phase] = new CompletionDistanceByPhase();
            }

            for (Map.Entry<N, Integer> entry : nodePhase.entrySet()) {
                int phase = entry.getValue();
                if (!isKnownPhase(phase)) {
                    continue;
                }
                distances[phase].add(distanceToCompletion.get(entry.getKey()));
            }

            for (int phase : PHASE_ORDER) {
                CompletionDistanceByPhase distance = distances[phase];
                UpdatingControllerEvaluationRecorder.recordUpdateCompletionDistanceByPhase(
                        section,
                        artifactLabel,
                        phaseLabel(phase),
                        distance.states,
                        distance.reachableStates,
                        distance.unreachableStates,
                        distance.minDistance(),
                        distance.maxDistance(),
                        distance.averageDistance());
            }
        }

        private CompletionPathStats<N> computeCompletionPathStats() {
            Set<N> completionNodes;
            String completionPhaseLabel;
            if (!postCompletionNodes.isEmpty()) {
                completionNodes = postCompletionNodes;
                completionPhaseLabel = phaseLabel(PHASE_POST);
            } else {
                completionNodes = allThreeUpdateEventsDoneNodes;
                completionPhaseLabel = phaseLabel(PHASE_111);
            }

            Map<N, Integer> distanceToCompletion = new HashMap<>();
            Queue<N> queue = new ArrayDeque<>();
            for (N node : completionNodes) {
                distanceToCompletion.put(node, 0);
                queue.add(node);
            }

            while (!queue.isEmpty()) {
                N current = queue.remove();
                int distance = distanceToCompletion.get(current);
                List<N> predecessors = reverseEdges.get(current);
                if (predecessors == null) {
                    continue;
                }
                for (N predecessor : predecessors) {
                    if (!distanceToCompletion.containsKey(predecessor)) {
                        distanceToCompletion.put(predecessor, distance + 1);
                        queue.add(predecessor);
                    }
                }
            }

            long reachable = 0;
            long unreachable = 0;
            long minLength = Long.MAX_VALUE;
            long maxLength = 0;
            long totalLength = 0;
            Map<Long, Long> lengthDistribution = new TreeMap<>();

            for (N beginTarget : beginUpdateTargets) {
                Integer remainingDistance = distanceToCompletion.get(beginTarget);
                if (remainingDistance == null) {
                    unreachable++;
                    continue;
                }

                long lengthIncludingBeginUpdate = remainingDistance + 1L;
                reachable++;
                totalLength += lengthIncludingBeginUpdate;
                minLength = Math.min(minLength, lengthIncludingBeginUpdate);
                maxLength = Math.max(maxLength, lengthIncludingBeginUpdate);
                lengthDistribution.put(
                        lengthIncludingBeginUpdate,
                        lengthDistribution.getOrDefault(lengthIncludingBeginUpdate, 0L) + 1L);
            }

            double average = reachable == 0 ? 0.0 : ((double) totalLength) / reachable;
            return new CompletionPathStats<>(
                    completionPhaseLabel,
                    beginUpdateTargets.size(),
                    reachable,
                    unreachable,
                    reachable == 0 ? -1 : minLength,
                    reachable == 0 ? -1 : maxLength,
                    average,
                    lengthDistribution,
                    distanceToCompletion);
        }

        private CycleStats[] computeCycleStats(Map<N, List<AnalysisEdge<N>>> adjacency) {
            Map<N, List<N>> targetAdjacency = new HashMap<>();
            for (Map.Entry<N, List<AnalysisEdge<N>>> entry : adjacency.entrySet()) {
                List<N> targets = new ArrayList<>();
                for (AnalysisEdge<N> edge : entry.getValue()) {
                    targets.add(edge.target);
                }
                targetAdjacency.put(entry.getKey(), targets);
            }
            return computeCycleStatsFromTargets(targetAdjacency);
        }

        private CycleStats[] computeCycleStatsFromTargets(Map<N, List<N>> adjacency) {
            CycleStats[] stats = new CycleStats[PHASE_POST + 1];
            for (int phase : PHASE_ORDER) {
                stats[phase] = new CycleStats();
            }

            TarjanScc<N> tarjan = new TarjanScc<>(nodePhase.keySet(), adjacency);
            for (Set<N> component : tarjan.components()) {
                if (component.isEmpty()) {
                    continue;
                }
                boolean selfLoop = hasSelfLoop(component, adjacency);
                if (component.size() <= 1 && !selfLoop) {
                    continue;
                }

                int phase = phaseForComponent(component);
                if (!isKnownPhase(phase)) {
                    continue;
                }
                stats[phase].cycleSccs++;
                stats[phase].statesInCycleSccs += component.size();
                stats[phase].maxCycleSccSize = Math.max(stats[phase].maxCycleSccSize, component.size());
                if (selfLoop) {
                    stats[phase].selfLoopCycleSccs++;
                }
            }
            return stats;
        }

        private boolean hasSelfLoop(Set<N> component, Map<N, List<N>> adjacency) {
            for (N node : component) {
                List<N> targets = adjacency.get(node);
                if (targets != null && targets.contains(node)) {
                    return true;
                }
            }
            return false;
        }

        private int phaseForComponent(Set<N> component) {
            for (N node : component) {
                Integer phase = nodePhase.get(node);
                if (phase != null) {
                    return phase;
                }
            }
            return PHASE_PRE;
        }
    }

    private static boolean isKnownPhase(int phase) {
        return phase >= PHASE_PRE && phase <= PHASE_POST;
    }

    private static String[] updateEventOrder() {
        return new String[] {
                UpdateConstants.BEGIN_UPDATE,
                UpdateConstants.STOP_OLD_SPEC,
                UpdateConstants.RECONFIGURE,
                UpdateConstants.START_NEW_SPEC,
                UpdateConstants.FINISH_UPDATE
        };
    }

    private static final class AnalysisEdge<N> {
        private final N target;
        private final String action;
        private final Boolean controllable;

        private AnalysisEdge(N target, String action, Boolean controllable) {
            this.target = target;
            this.action = action;
            this.controllable = controllable;
        }
    }

    private static final class OrderPatternNode<N> {
        private final N node;
        private final String pattern;

        private OrderPatternNode(N node, String pattern) {
            this.node = node;
            this.pattern = pattern;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof OrderPatternNode)) {
                return false;
            }
            OrderPatternNode<?> that = (OrderPatternNode<?>) o;
            return Objects.equals(node, that.node) && Objects.equals(pattern, that.pattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node, pattern);
        }
    }

    private static final class OrderPatternStats<N> {
        private long stateOccurrences;
        private long transitions;
        private long normalTransitions;
        private long updateEventTransitions;
        private final Set<N> uniqueStates = new HashSet<>();
        private final Map<Integer, Long> phaseCounts = new HashMap<>();

        private void addState(N node, int phase) {
            stateOccurrences++;
            uniqueStates.add(node);
            phaseCounts.put(phase, phaseCounts.getOrDefault(phase, 0L) + 1L);
        }

        private void addTransition(String category) {
            transitions++;
            if (NORMAL_TRANSITION.equals(category)) {
                normalTransitions++;
            } else {
                updateEventTransitions++;
            }
        }

        private String phaseLabel() {
            int bestPhase = PHASE_PRE;
            long bestCount = -1;
            for (Map.Entry<Integer, Long> entry : phaseCounts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    bestPhase = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            return UpdatePhaseEvaluator.phaseLabel(bestPhase);
        }
    }

    private static final class NormalRunLengthStats {
        private long samples;
        private long reachableSamples;
        private long unreachableSamples;
        private long minLength = Long.MAX_VALUE;
        private long maxLength;
        private long totalLength;
        private final Map<Long, Long> lengthDistribution = new TreeMap<>();

        private void add(Integer length) {
            samples++;
            if (length == null) {
                unreachableSamples++;
                return;
            }
            reachableSamples++;
            minLength = Math.min(minLength, length);
            maxLength = Math.max(maxLength, length);
            totalLength += length;
            long longLength = length;
            lengthDistribution.put(longLength, lengthDistribution.getOrDefault(longLength, 0L) + 1L);
        }

        private long minLength() {
            return reachableSamples == 0 ? -1 : minLength;
        }

        private long maxLength() {
            return reachableSamples == 0 ? -1 : maxLength;
        }

        private double averageLength() {
            return reachableSamples == 0 ? 0.0 : ((double) totalLength) / reachableSamples;
        }
    }

    private static final class CycleStats {
        private long cycleSccs;
        private long statesInCycleSccs;
        private long maxCycleSccSize;
        private long selfLoopCycleSccs;

        private static CycleStats unavailable() {
            CycleStats stats = new CycleStats();
            stats.cycleSccs = -1;
            stats.statesInCycleSccs = -1;
            stats.maxCycleSccSize = -1;
            stats.selfLoopCycleSccs = -1;
            return stats;
        }
    }

    private static final class EnabledUpdateEventsByPhase {
        private long anyUpdateEventStates;
        private long beginUpdateStates;
        private long stopOldSpecStates;
        private long reconfigureStates;
        private long startNewSpecStates;
        private long finishUpdateStates;

        private void add(Set<String> enabledEvents) {
            if (enabledEvents == null || enabledEvents.isEmpty()) {
                return;
            }
            anyUpdateEventStates++;
            if (enabledEvents.contains(UpdateConstants.BEGIN_UPDATE)) {
                beginUpdateStates++;
            }
            if (enabledEvents.contains(UpdateConstants.STOP_OLD_SPEC)) {
                stopOldSpecStates++;
            }
            if (enabledEvents.contains(UpdateConstants.RECONFIGURE)) {
                reconfigureStates++;
            }
            if (enabledEvents.contains(UpdateConstants.START_NEW_SPEC)) {
                startNewSpecStates++;
            }
            if (enabledEvents.contains(UpdateConstants.FINISH_UPDATE)) {
                finishUpdateStates++;
            }
        }
    }

    private static final class TarjanScc<N> {
        private final Set<N> nodes;
        private final Map<N, List<N>> adjacency;
        private final Map<N, Integer> indexByNode = new HashMap<>();
        private final Map<N, Integer> lowLinkByNode = new HashMap<>();
        private final ArrayDeque<N> stack = new ArrayDeque<>();
        private final Set<N> onStack = new HashSet<>();
        private final List<Set<N>> components = new ArrayList<>();
        private int nextIndex;

        private TarjanScc(Set<N> nodes, Map<N, List<N>> adjacency) {
            this.nodes = nodes;
            this.adjacency = adjacency;
        }

        private List<Set<N>> components() {
            for (N node : nodes) {
                if (!indexByNode.containsKey(node)) {
                    strongConnect(node);
                }
            }
            return components;
        }

        private void strongConnect(N node) {
            indexByNode.put(node, nextIndex);
            lowLinkByNode.put(node, nextIndex);
            nextIndex++;
            stack.push(node);
            onStack.add(node);

            List<N> targets = adjacency.get(node);
            if (targets != null) {
                for (N target : targets) {
                    if (!indexByNode.containsKey(target)) {
                        strongConnect(target);
                        lowLinkByNode.put(node, Math.min(lowLinkByNode.get(node), lowLinkByNode.get(target)));
                    } else if (onStack.contains(target)) {
                        lowLinkByNode.put(node, Math.min(lowLinkByNode.get(node), indexByNode.get(target)));
                    }
                }
            }

            if (!Objects.equals(lowLinkByNode.get(node), indexByNode.get(node))) {
                return;
            }

            Set<N> component = new HashSet<>();
            N member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!Objects.equals(member, node));
            components.add(component);
        }
    }

    private static final class PhaseTransitionDetail {
        private long states;
        private long maxOutDegree;
        private long totalOutDegree;
        private long samePhaseNormalTransitions;
        private long normalControllableTransitions;
        private long normalUncontrollableTransitions;
        private long normalUnknownControllabilityTransitions;
        private final Map<String, Long> normalActionTransitions = new TreeMap<>();
        private final TransitionCategoryCount counts = new TransitionCategoryCount();

        private void addStateOutDegree(long outDegree) {
            states++;
            totalOutDegree += outDegree;
            maxOutDegree = Math.max(maxOutDegree, outDegree);
        }

        private void addTransition(String action, boolean samePhase, Boolean controllable) {
            counts.add(action);
            if (NORMAL_TRANSITION.equals(transitionCategory(action))) {
                if (isFullEvaluationProfile()) {
                    String normalizedAction = normalizeAction(action);
                    normalActionTransitions.put(
                            normalizedAction,
                            normalActionTransitions.getOrDefault(normalizedAction, 0L) + 1L);
                }
                if (samePhase) {
                    samePhaseNormalTransitions++;
                }
                if (controllable == null) {
                    normalUnknownControllabilityTransitions++;
                } else if (controllable) {
                    normalControllableTransitions++;
                } else {
                    normalUncontrollableTransitions++;
                }
            }
        }

        private double averageOutDegree() {
            return states == 0 ? 0.0 : ((double) totalOutDegree) / states;
        }

        private double normalTransitionRate() {
            long total = counts.totalTransitions();
            return total == 0 ? 0.0 : ((double) counts.getNormalTransitions()) / total;
        }
    }

    private static final class CompletionDistanceByPhase {
        private long states;
        private long reachableStates;
        private long unreachableStates;
        private long minDistance = Long.MAX_VALUE;
        private long maxDistance;
        private long totalDistance;

        private void add(Integer distance) {
            states++;
            if (distance == null) {
                unreachableStates++;
                return;
            }
            reachableStates++;
            minDistance = Math.min(minDistance, distance);
            maxDistance = Math.max(maxDistance, distance);
            totalDistance += distance;
        }

        private long minDistance() {
            return reachableStates == 0 ? -1 : minDistance;
        }

        private long maxDistance() {
            return reachableStates == 0 ? -1 : maxDistance;
        }

        private double averageDistance() {
            return reachableStates == 0 ? 0.0 : ((double) totalDistance) / reachableStates;
        }
    }

    private static final class CompletionPathStats<N> {
        private final String completionPhaseLabel;
        private final long beginUpdateTransitions;
        private final long reachableBeginUpdateTransitions;
        private final long unreachableBeginUpdateTransitions;
        private final long minPathLength;
        private final long maxShortestPathLength;
        private final double averageShortestPathLength;
        private final Map<Long, Long> lengthDistribution;
        private final Map<N, Integer> distanceToCompletion;

        private CompletionPathStats(
                String completionPhaseLabel,
                long beginUpdateTransitions,
                long reachableBeginUpdateTransitions,
                long unreachableBeginUpdateTransitions,
                long minPathLength,
                long maxShortestPathLength,
                double averageShortestPathLength,
                Map<Long, Long> lengthDistribution,
                Map<N, Integer> distanceToCompletion) {
            this.completionPhaseLabel = completionPhaseLabel;
            this.beginUpdateTransitions = beginUpdateTransitions;
            this.reachableBeginUpdateTransitions = reachableBeginUpdateTransitions;
            this.unreachableBeginUpdateTransitions = unreachableBeginUpdateTransitions;
            this.minPathLength = minPathLength;
            this.maxShortestPathLength = maxShortestPathLength;
            this.averageShortestPathLength = averageShortestPathLength;
            this.lengthDistribution = lengthDistribution;
            this.distanceToCompletion = distanceToCompletion;
        }
    }

    public static final class TransitionCategoryCount {
        private long beginUpdateTransitions;
        private long stopOldSpecTransitions;
        private long reconfigureTransitions;
        private long startNewSpecTransitions;
        private long finishUpdateTransitions;
        private long normalTransitions;

        public void add(String action) {
            String category = transitionCategory(action);
            if (UpdateConstants.BEGIN_UPDATE.equals(category)) {
                beginUpdateTransitions++;
            } else if (UpdateConstants.STOP_OLD_SPEC.equals(category)) {
                stopOldSpecTransitions++;
            } else if (UpdateConstants.RECONFIGURE.equals(category)) {
                reconfigureTransitions++;
            } else if (UpdateConstants.START_NEW_SPEC.equals(category)) {
                startNewSpecTransitions++;
            } else if (UpdateConstants.FINISH_UPDATE.equals(category)) {
                finishUpdateTransitions++;
            } else {
                normalTransitions++;
            }
        }

        public void record(String section, String artifactLabel, long countTimeMillis) {
            UpdatingControllerEvaluationRecorder.recordUpdateEventTransitionCounts(
                    section,
                    artifactLabel,
                    beginUpdateTransitions,
                    stopOldSpecTransitions,
                    reconfigureTransitions,
                    startNewSpecTransitions,
                    finishUpdateTransitions,
                    normalTransitions,
                    countTimeMillis);
        }

        public long getBeginUpdateTransitions() {
            return beginUpdateTransitions;
        }

        public long getStopOldSpecTransitions() {
            return stopOldSpecTransitions;
        }

        public long getReconfigureTransitions() {
            return reconfigureTransitions;
        }

        public long getStartNewSpecTransitions() {
            return startNewSpecTransitions;
        }

        public long getFinishUpdateTransitions() {
            return finishUpdateTransitions;
        }

        public long getNormalTransitions() {
            return normalTransitions;
        }

        public long totalTransitions() {
            return beginUpdateTransitions
                    + stopOldSpecTransitions
                    + reconfigureTransitions
                    + startNewSpecTransitions
                    + finishUpdateTransitions
                    + normalTransitions;
        }
    }

    private static final class PhaseCount {
        private final long states;
        private final long transitions;
        private final long countTimeMillis;

        private PhaseCount(long states, long transitions, long countTimeMillis) {
            this.states = states;
            this.transitions = transitions;
            this.countTimeMillis = countTimeMillis;
        }
    }

    private static final class MtsPhaseNode {
        private final Long state;
        private final int phase;

        private MtsPhaseNode(Long state, int phase) {
            this.state = state;
            this.phase = phase;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MtsPhaseNode)) {
                return false;
            }
            MtsPhaseNode that = (MtsPhaseNode) o;
            return phase == that.phase && Objects.equals(state, that.state);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, phase);
        }
    }

    private static final class CompactPhaseNode {
        private final int state;
        private final int phase;

        private CompactPhaseNode(int state, int phase) {
            this.state = state;
            this.phase = phase;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CompactPhaseNode)) {
                return false;
            }
            CompactPhaseNode that = (CompactPhaseNode) o;
            return state == that.state && phase == that.phase;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, phase);
        }
    }
}
