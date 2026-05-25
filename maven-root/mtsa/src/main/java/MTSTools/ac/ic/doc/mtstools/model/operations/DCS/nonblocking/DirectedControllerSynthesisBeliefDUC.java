package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;

/**
 * Experimental Belief OTF-DUC.
 *
 * This variant keeps the pre-update old controller states as the output states,
 * builds a belief root after hotSwapIn for each reachable old-controller state,
 * and solves those roots incrementally over a shared belief graph.
 */
public class DirectedControllerSynthesisBeliefDUC<State, Action> {

    private static final long NORMALIZED_LONG = -2L;
    private static final long ERROR_LONG = -1L;

    private List<LTS<State, Action>> ltss;
    private int ltssSize;
    private Alphabet<State, Action> alphabet;

    private final int idxMarking = 0;
    private final int idxOC = 1;

    private int mappingStart;
    private int mappingEnd;
    private int oldSafeStart;
    private int oldSafeEnd;
    private int newSafeStart;
    private int newSafeEnd;
    private int transReqStart;
    private int transReqEnd;
    private int synthesisStart;
    private int synthesisEnd;

    private List<Map<Integer, Integer>> mappingMapEnvToNewEnv;
    private Map<String, Long> newControllerConnectionMap;
    private LTS<Long, Action> newController;
    private Map<Integer, List<Integer>> safetyComponentIndicesMap;
    private Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap;
    private LTSOutput output;

    private final Map<List<State>, ConcreteState> concreteStates = new LinkedHashMap<>();
    private final Map<List<Integer>, BeliefNode> beliefNodes = new LinkedHashMap<>();
    private int nextConcreteId;
    private int nextBeliefId;
    private long expandedActions;
    private long generatedBeliefEdges;
    private long preUpdateConcreteStates;
    private long reachableOldStates;
    private long solvedRoots;
    private long failedRoots;
    private long rootCount;
    private long multiMemberRootCount;
    private long maxRootMembers;
    private long zeroExpansionRoots;
    private long maxExpansionsForRoot;
    private long controllableExpansionAttempts;
    private long uncontrollableExpansionAttempts;
    private long finishExpansionAttempts;
    private long acceptedControllableActions;
    private long rejectedControllableActions;
    private long acceptedUncontrollableActions;
    private long acceptedFinishActions;
    private long rejectedFinishActions;
    private long synthesizeDUCTime;
    private long preBfsTime;
    private long rootBuildTime;
    private long rootSolveTime;
    private long outputBuildTime;
    private long outputControllerStates = -1L;
    private long outputControllerTransitions = -1L;
    private long outputControllerActions = -1L;
    private long outputControllerCountTime;
    private OutputTransitionStats outputTransitionStats = new OutputTransitionStats();
    private boolean evaluationRecorded;

    private final boolean debugLogEnabled = Boolean.getBoolean("otfduc.debug");
    private PrintWriter logWriter;
    private static final String LOG_FILE_PATH = System.getProperty("otfduc.debug.file", "duc_debug.txt");

    private final int maxBeliefNodes = Integer.getInteger("otfduc.beliefOtf.maxBeliefNodes", 0);
    private final int maxConcreteStates = Integer.getInteger("otfduc.beliefOtf.maxConcreteStates", 0);
    private final int maxExpansionsPerRoot = Integer.getInteger("otfduc.beliefOtf.maxExpansionsPerRoot", 0);

    private final State normalizedValue = castState(NORMALIZED_LONG);
    private final State errorValue = castState(ERROR_LONG);

    public LTS<Long, Action> synthesizeDUC(
            List<LTS<State, Action>> ltss,
            Set<Action> controllable,
            int mappingStart,
            int mappingEnd,
            int oldSafeStart,
            int oldSafeEnd,
            int newSafeStart,
            int newSafeEnd,
            int transReqStart,
            int transReqEnd,
            int synthesisStart,
            int synthesisEnd,
            List<Map<Integer, Integer>> mappingMapEnvToNewEnv,
            Map<String, Long> newControllerConnectionMap,
            Object newController,
            Map<Integer, List<Integer>> safetyComponentIndicesMap,
            Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap,
            LTSOutput output) {

        this.ltss = ltss;
        this.ltssSize = ltss.size();
        this.alphabet = new Alphabet<>(ltss, controllable);
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
        this.mappingMapEnvToNewEnv = mappingMapEnvToNewEnv;
        this.newControllerConnectionMap = newControllerConnectionMap;
        this.safetyComponentIndicesMap = safetyComponentIndicesMap;
        this.safetyStateLookupMap = safetyStateLookupMap;
        this.output = output;

        @SuppressWarnings("unchecked")
        LTS<Long, Action> nc = (LTS<Long, Action>) newController;
        this.newController = nc;

        initializeDebugLog();
        long synthesizeStart = System.currentTimeMillis();

        try {
            UpdatingControllerEvaluationRecorder.setOtfExecutionMode("Belief OTF-DUC");
            outln("Starting Belief OTF-DUC (incremental global-cache mode).");
            log("=== Starting Belief OTF-DUC Synthesis ===");
            log(String.format("Config: MarkingLTS[0], OldController[1], MapEnv[%d-%d], OldSafe[%d-%d], NewSafe[%d-%d], TransReq[%d-%d], Synthesis[%d-%d], maxBeliefNodes=%s, maxConcreteStates=%s, maxExpansionsPerRoot=%s",
                    mappingStart, mappingEnd, oldSafeStart, oldSafeEnd, newSafeStart, newSafeEnd,
                    transReqStart, transReqEnd, synthesisStart, synthesisEnd,
                    describeLimit(maxBeliefNodes),
                    describeLimit(maxConcreteStates),
                    describeLimit(maxExpansionsPerRoot)));

            long preBfsStart = System.currentTimeMillis();
            Map<State, List<ConcreteState>> preBeliefs = explorePreUpdateBeliefs();
            preBfsTime = System.currentTimeMillis() - preBfsStart;
            reachableOldStates = preBeliefs.size();
            preUpdateConcreteStates = concreteStates.size();
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Belief OTF PreBFS 後");
            log("[PreBFS] reachable old states=" + reachableOldStates
                    + ", pre-update concrete states=" + preUpdateConcreteStates);

            long rootBuildStart = System.currentTimeMillis();
            Map<State, BeliefNode> roots = new LinkedHashMap<>();
            Action beginUpdate = actionByName(UpdateConstants.BEGIN_UPDATE);
            if (beginUpdate == null) {
                throw new IllegalStateException("Belief OTF-DUC requires " + UpdateConstants.BEGIN_UPDATE + " in the alphabet.");
            }
            HAction<State, Action> beginAction = alphabet.getHAction(beginUpdate);

            for (Map.Entry<State, List<ConcreteState>> entry : preBeliefs.entrySet()) {
                Set<ConcreteState> rootMembers = new LinkedHashSet<>();
                log("[RootBuild] old=" + entry.getKey()
                        + ", preMembers=" + describeConcreteIds(entry.getValue()));
                for (ConcreteState preState : entry.getValue()) {
                    StepResult step = stepConcrete(preState, beginAction);
                    if (step.invalid) {
                        log("[RootBuild] beginUpdate invalid from " + describeConcrete(preState));
                        continue;
                    }
                    for (ConcreteState child : step.children) {
                        if (!isError(child) && getMarkingState(child) >= 1) {
                            rootMembers.add(child);
                        } else {
                            log("[RootBuild] beginUpdate child rejected from " + describeConcrete(preState)
                                    + " -> " + describeConcrete(child));
                        }
                    }
                }
                if (!rootMembers.isEmpty()) {
                    BeliefNode root = getOrCreateBeliefNode(rootMembers);
                    roots.put(entry.getKey(), root);
                    log("[RootBuild] old=" + entry.getKey() + " -> " + describeBelief(root));
                } else {
                    log("[RootBuild] old=" + entry.getKey() + " has no valid belief root");
                }
            }
            rootBuildTime = System.currentTimeMillis() - rootBuildStart;
            recordRootBuildStats(roots);

            long rootSolveStart = System.currentTimeMillis();
            for (Map.Entry<State, List<ConcreteState>> entry : preBeliefs.entrySet()) {
                BeliefNode root = roots.get(entry.getKey());
                if (root == null || !solveRoot(entry.getKey(), root)) {
                    failedRoots++;
                    log("[Root] FAILED old=" + entry.getKey()
                            + ", root=" + (root == null ? "<missing>" : describeBelief(root)));
                    outln("Belief OTF-DUC failed for old-controller state: " + entry.getKey());
                    rootSolveTime = System.currentTimeMillis() - rootSolveStart;
                    synthesizeDUCTime = System.currentTimeMillis() - synthesizeStart;
                    recordEvaluation();
                    return null;
                }
                solvedRoots++;
            }
            rootSolveTime = System.currentTimeMillis() - rootSolveStart;
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Belief OTF root solve 後");

            outln("Belief OTF-DUC roots solved: " + solvedRoots
                    + ", beliefNodes=" + beliefNodes.size()
                    + ", concreteStates=" + concreteStates.size()
                    + ", expandedActions=" + expandedActions);
            log("[Summary] roots solved=" + solvedRoots
                    + ", failed=" + failedRoots
                    + ", beliefNodes=" + beliefNodes.size()
                    + ", concreteStates=" + concreteStates.size()
                    + ", expandedActions=" + expandedActions
                    + ", generatedBeliefEdges=" + generatedBeliefEdges);
            long outputBuildStart = System.currentTimeMillis();
            LTS<Long, Action> result = buildOutputController(preBeliefs.keySet(), roots, beginUpdate);
            outputBuildTime = System.currentTimeMillis() - outputBuildStart;
            captureOutputControllerStats(result);
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Belief OTF output controller 構築後");
            synthesizeDUCTime = System.currentTimeMillis() - synthesizeStart;
            recordEvaluation();
            return result;
        } finally {
            if (!evaluationRecorded) {
                synthesizeDUCTime = System.currentTimeMillis() - synthesizeStart;
                recordEvaluation();
            }
            closeDebugLog();
        }
    }

    private Map<State, List<ConcreteState>> explorePreUpdateBeliefs() {
        Map<State, List<ConcreteState>> byOldState = new LinkedHashMap<>();
        Deque<ConcreteState> queue = new ArrayDeque<>();
        Set<ConcreteState> reached = new LinkedHashSet<>();
        log("[PreBFS] start");

        List<State> initialVector = new ArrayList<>(ltssSize);
        for (LTS<State, Action> lts : ltss) {
            initialVector.add(lts.getInitialState());
        }
        ConcreteState initial = getOrCreateConcreteState(initialVector);
        queue.add(initial);
        reached.add(initial);
        log("[PreBFS] initial " + describeConcrete(initial));

        while (!queue.isEmpty()) {
            ConcreteState current = queue.remove();
            if (getMarkingState(current) != 0 || isError(current)) {
                log("[PreBFS] skip non-pre/error " + describeConcrete(current));
                continue;
            }

            State oldState = current.states.get(idxOC);
            byOldState.computeIfAbsent(oldState, key -> new ArrayList<>()).add(current);
            log("[PreBFS] visit old=" + oldState + ", " + describeConcrete(current));

            LTS<State, Action> oldController = ltss.get(idxOC);
            for (Pair<Action, State> oldTransition : oldController.getTransitions(current.states.get(idxOC))) {
                HAction<State, Action> action = alphabet.getHAction(oldTransition.getFirst());
                if (action == null || !isActionEnabled(current, action)) {
                    log("[PreBFS] skip disabled/unmapped old action="
                            + oldTransition.getFirst() + " at " + describeConcrete(current));
                    continue;
                }
                String actionName = action.toString();
                if (isUpdateProtocolActionName(actionName)
                        || actionName.equals(UpdateConstants.BEGIN_UPDATE)
                        || actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                    log("[PreBFS] skip update protocol action=" + actionName
                            + " at " + describeConcrete(current));
                    continue;
                }
                StepResult step = stepConcrete(current, action);
                if (step.invalid) {
                    log("[PreBFS] invalid old action=" + actionName
                            + " at " + describeConcrete(current));
                    continue;
                }
                log("[PreBFS] action=" + actionName + " from " + describeConcrete(current)
                        + " -> " + describeConcreteIds(step.children));
                for (ConcreteState child : step.children) {
                    if (getMarkingState(child) == 0 && !isError(child) && reached.add(child)) {
                        queue.add(child);
                        log("[PreBFS] enqueue " + describeConcrete(child));
                    }
                }
            }
        }
        log("[PreBFS] finished groups=" + byOldState.size()
                + ", reachedConcrete=" + reached.size());
        return byOldState;
    }

    private boolean solveRoot(State oldState, BeliefNode root) {
        int expansionsForRoot = 0;
        boolean changed = true;
        log("[Root] solve start old=" + oldState + ", " + describeBelief(root));

        while (root.status == BeliefStatus.UNKNOWN) {
            if (changed) {
                changed = recomputeStatuses();
                if (root.status != BeliefStatus.UNKNOWN) {
                    break;
                }
            }

            ExpansionCandidate candidate = selectExpansion(root);
            if (candidate == null) {
                boolean marked = markClosedErrors(root);
                changed = marked || recomputeStatuses();
                if (!changed || root.status == BeliefStatus.UNKNOWN) {
                    break;
                }
                continue;
            }

            if (reachedLimit(beliefNodes.size(), maxBeliefNodes)
                    || reachedLimit(concreteStates.size(), maxConcreteStates)
                    || reachedLimit(expansionsForRoot, maxExpansionsPerRoot)) {
                log("[Limit] root old=" + oldState
                        + ", root=" + describeBelief(root)
                        + ", expansionsForRoot=" + expansionsForRoot
                        + ", beliefNodes=" + describeLimitUsage(beliefNodes.size(), maxBeliefNodes)
                        + ", concreteStates=" + describeLimitUsage(concreteStates.size(), maxConcreteStates)
                        + ", maxExpansionsPerRoot=" + describeLimit(maxExpansionsPerRoot));
                break;
            }

            log("[Root] expand old=" + oldState
                    + ", candidate=" + describeBelief(candidate.node)
                    + ", action=" + candidate.actionName);
            expandBeliefAction(candidate.node, candidate.actionName);
            expansionsForRoot++;
            expandedActions++;
            changed = true;
        }
        log("[Root] solve end old=" + oldState
                + ", rootStatus=" + root.status
                + ", expansionsForRoot=" + expansionsForRoot
                + ", root=" + describeBelief(root));
        if (expansionsForRoot == 0) {
            zeroExpansionRoots++;
        }
        maxExpansionsForRoot = Math.max(maxExpansionsForRoot, expansionsForRoot);
        return root.status == BeliefStatus.WINNING;
    }

    private boolean reachedLimit(long value, int limit) {
        return limit > 0 && value >= limit;
    }

    private String describeLimit(int limit) {
        return limit > 0 ? String.valueOf(limit) : "unlimited";
    }

    private String describeLimitUsage(long value, int limit) {
        return value + "/" + describeLimit(limit);
    }

    private ExpansionCandidate selectExpansion(BeliefNode root) {
        List<BeliefNode> reachable = collectReachableBeliefNodes(root);
        reachable.sort((left, right) -> {
            int cmp = Integer.compare(markingDepth(right), markingDepth(left));
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(right.id, left.id);
        });

        for (BeliefNode node : reachable) {
            if (node.status != BeliefStatus.UNKNOWN) {
                continue;
            }
            String action = firstExpandableUpdateAction(node);
            if (action != null) {
                return new ExpansionCandidate(node, action);
            }
        }
        for (BeliefNode node : reachable) {
            if (node.status != BeliefStatus.UNKNOWN) {
                continue;
            }
            String action = firstExpandableUncontrollableAction(node);
            if (action != null) {
                return new ExpansionCandidate(node, action);
            }
        }
        for (BeliefNode node : reachable) {
            if (node.status != BeliefStatus.UNKNOWN) {
                continue;
            }
            String action = firstExpandableCommonControllableAction(node);
            if (action != null) {
                return new ExpansionCandidate(node, action);
            }
        }
        return null;
    }

    private String firstExpandableUpdateAction(BeliefNode node) {
        for (String actionName : updateActionPriority()) {
            if (actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                if (!node.finishExpanded && isActionEnabledInAllMembers(node, actionName)) {
                    return actionName;
                }
            } else if (!node.expandedActions.contains(actionName)
                    && isActionEnabledInAllMembers(node, actionName)) {
                return actionName;
            }
        }
        return null;
    }

    private String firstExpandableUncontrollableAction(BeliefNode node) {
        List<String> names = new ArrayList<>();
        for (ConcreteState member : node.members) {
            for (HAction<State, Action> action : enabledActions(member)) {
                String actionName = action.toString();
                if (action.isControllable()
                        || node.expandedActions.contains(actionName)
                        || actionName.equals(UpdateConstants.BEGIN_UPDATE)
                        || actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                    continue;
                }
                if (!names.contains(actionName)) {
                    names.add(actionName);
                }
            }
        }
        Collections.sort(names);
        return names.isEmpty() ? null : names.get(0);
    }

    private String firstExpandableCommonControllableAction(BeliefNode node) {
        List<String> names = commonControllableActionNames(node);
        for (String name : names) {
            if (!node.expandedActions.contains(name) && !node.rejectedActions.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private void expandBeliefAction(BeliefNode node, String actionName) {
        log("[Expand] " + describeBelief(node) + ", action=" + actionName);
        if (actionName.equals(UpdateConstants.FINISH_UPDATE)) {
            expandFinishUpdate(node);
            return;
        }

        Action action = actionByName(actionName);
        if (action == null) {
            node.rejectedActions.add(actionName);
            log("[Expand] rejected action not found: " + actionName + " at " + describeBelief(node));
            return;
        }
        HAction<State, Action> hAction = alphabet.getHAction(action);
        if (hAction == null) {
            node.rejectedActions.add(actionName);
            log("[Expand] rejected HAction not found: " + actionName + " at " + describeBelief(node));
            return;
        }

        if (hAction.isControllable()) {
            expandControllable(node, hAction);
        } else {
            expandUncontrollable(node, hAction);
        }
    }

    private void expandControllable(BeliefNode node, HAction<State, Action> action) {
        String actionName = action.toString();
        controllableExpansionAttempts++;
        Set<ConcreteState> children = new LinkedHashSet<>();
        for (ConcreteState member : node.members) {
            if (!isActionEnabled(member, action)) {
                node.rejectedActions.add(actionName);
                node.expandedActions.add(actionName);
                rejectedControllableActions++;
                log("[Expand-C] rejected because member cannot enable action: "
                        + actionName + ", member=" + describeConcrete(member)
                        + ", node=" + describeBelief(node));
                return;
            }
            StepResult step = stepConcrete(member, action);
            if (step.invalid) {
                node.rejectedActions.add(actionName);
                node.expandedActions.add(actionName);
                rejectedControllableActions++;
                log("[Expand-C] rejected because step is invalid: "
                        + actionName + ", member=" + describeConcrete(member)
                        + ", node=" + describeBelief(node));
                return;
            }
            for (ConcreteState child : step.children) {
                if (isError(child)) {
                    node.rejectedActions.add(actionName);
                    node.expandedActions.add(actionName);
                    rejectedControllableActions++;
                    log("[Expand-C] rejected because child is error: "
                            + actionName + ", member=" + describeConcrete(member)
                            + ", child=" + describeConcrete(child)
                            + ", node=" + describeBelief(node));
                    return;
                }
                children.add(child);
            }
        }

        if (!children.isEmpty()) {
            BeliefNode target = getOrCreateBeliefNode(children);
            addEdge(node, new BeliefEdge(action.getAction(), true, target));
            acceptedControllableActions++;
            log("[Expand-C] accepted " + actionName + ": "
                    + describeBelief(node) + " -> " + describeBelief(target));
        } else {
            log("[Expand-C] no children for " + actionName + " at " + describeBelief(node));
        }
        node.expandedActions.add(actionName);
    }

    private void expandUncontrollable(BeliefNode node, HAction<State, Action> action) {
        String actionName = action.toString();
        uncontrollableExpansionAttempts++;
        Set<ConcreteState> children = new LinkedHashSet<>();
        int enabledMembers = 0;
        for (ConcreteState member : node.members) {
            if (!isActionEnabled(member, action)) {
                continue;
            }
            enabledMembers++;
            StepResult step = stepConcrete(member, action);
            if (step.invalid) {
                setStatus(node, BeliefStatus.ERROR, "uncontrollable action became invalid: " + actionName);
                node.expandedActions.add(actionName);
                return;
            }
            for (ConcreteState child : step.children) {
                if (isError(child)) {
                    setStatus(node, BeliefStatus.ERROR, "uncontrollable action reaches an error state: " + actionName);
                    node.expandedActions.add(actionName);
                    return;
                }
                children.add(child);
            }
        }

        if (!children.isEmpty()) {
            BeliefNode target = getOrCreateBeliefNode(children);
            addEdge(node, new BeliefEdge(action.getAction(), false, target));
            acceptedUncontrollableActions++;
            log("[Expand-U] accepted " + actionName + ": enabledMembers=" + enabledMembers
                    + "/" + node.members.size()
                    + ", " + describeBelief(node) + " -> " + describeBelief(target));
        } else {
            log("[Expand-U] no enabled members for " + actionName + " at " + describeBelief(node));
        }
        node.expandedActions.add(actionName);
    }

    private void expandFinishUpdate(BeliefNode node) {
        node.finishExpanded = true;
        finishExpansionAttempts++;
        Action finishAction = actionByName(UpdateConstants.FINISH_UPDATE);
        if (finishAction == null) {
            rejectedFinishActions++;
            log("[Finish] rejected because finishUpdate action is missing at " + describeBelief(node));
            return;
        }
        HAction<State, Action> hAction = alphabet.getHAction(finishAction);
        if (hAction == null) {
            rejectedFinishActions++;
            log("[Finish] rejected because finishUpdate HAction is missing at " + describeBelief(node));
            return;
        }

        Set<Long> ncTargets = new LinkedHashSet<>();
        for (ConcreteState member : node.members) {
            if (!isActionEnabled(member, hAction)) {
                rejectedFinishActions++;
                log("[Finish] rejected because member cannot enable finishUpdate: "
                        + describeConcrete(member) + ", node=" + describeBelief(node));
                return;
            }
            StepResult step = stepConcrete(member, hAction);
            if (step.invalid || step.children.isEmpty()) {
                rejectedFinishActions++;
                log("[Finish] rejected because step is invalid/empty: "
                        + describeConcrete(member) + ", node=" + describeBelief(node));
                return;
            }
            boolean foundFinishChild = false;
            for (ConcreteState child : step.children) {
                if (getMarkingState(child) != 9 || isError(child)) {
                    log("[Finish] ignored non-finish/error child: "
                            + describeConcrete(member) + " -> " + describeConcrete(child));
                    continue;
                }
                String signature = generateNCSignature(child);
                Long ncTarget = signature == null ? null : newControllerConnectionMap.get(signature);
                if (ncTarget != null) {
                    ncTargets.add(ncTarget);
                    foundFinishChild = true;
                    log("[Finish] member target: " + describeConcrete(member)
                            + " -> NC " + ncTarget + ", signature=" + signature);
                } else {
                    log("[Finish] no NC target for child: "
                            + describeConcrete(child) + ", signature=" + signature);
                }
            }
            if (!foundFinishChild) {
                rejectedFinishActions++;
                log("[Finish] rejected because member has no NC target: "
                        + describeConcrete(member) + ", node=" + describeBelief(node));
                return;
            }
        }

        if (ncTargets.size() == 1) {
            node.finishAction = finishAction;
            node.finishNcTarget = ncTargets.iterator().next();
            acceptedFinishActions++;
            log("[Finish] accepted " + describeBelief(node) + " -> NC " + node.finishNcTarget);
        } else {
            rejectedFinishActions++;
            log("[Finish] rejected because NC targets are not identical: "
                    + ncTargets + ", node=" + describeBelief(node));
        }
    }

    private boolean recomputeStatuses() {
        boolean changed;
        boolean anyChanged = false;
        do {
            changed = false;
            for (BeliefNode node : beliefNodes.values()) {
                if (node.status != BeliefStatus.UNKNOWN) {
                    continue;
                }
                if (hasErrorUncontrollableSuccessor(node)) {
                    setStatus(node, BeliefStatus.ERROR, "uncontrollable successor is ERROR");
                    changed = true;
                    anyChanged = true;
                    continue;
                }

                boolean hasUnexpandedUncontrollable = firstExpandableUncontrollableAction(node) != null;
                boolean allUncontrollableWinning = !hasUnexpandedUncontrollable;
                for (BeliefEdge edge : node.uncontrollableEdges) {
                    if (edge.target.status != BeliefStatus.WINNING) {
                        allUncontrollableWinning = false;
                        break;
                    }
                }
                if (!allUncontrollableWinning) {
                    continue;
                }

                if (node.finishNcTarget != null) {
                    setStatus(node, BeliefStatus.WINNING, "finishUpdate reaches NC " + node.finishNcTarget);
                    node.selectedFinish = true;
                    changed = true;
                    anyChanged = true;
                    continue;
                }
                BeliefEdge winningControllable = findWinningControllableEdge(node);
                if (winningControllable != null) {
                    setStatus(node, BeliefStatus.WINNING,
                            "controllable edge reaches WINNING: " + winningControllable.action);
                    node.selectedControllable = winningControllable;
                    changed = true;
                    anyChanged = true;
                    continue;
                }
                if (!node.uncontrollableEdges.isEmpty()) {
                    setStatus(node, BeliefStatus.WINNING,
                            "all uncontrollable successors are WINNING");
                    changed = true;
                    anyChanged = true;
                }
            }
            if (promoteFairBeliefLoops()) {
                changed = true;
                anyChanged = true;
            }
        } while (changed);
        return anyChanged;
    }

    private boolean promoteFairBeliefLoops() {
        Set<BeliefNode> candidates = new LinkedHashSet<>();
        for (BeliefNode node : beliefNodes.values()) {
            if (node.status == BeliefStatus.UNKNOWN && isFairnessEligible(node)) {
                candidates.add(node);
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        log("[Fairness] candidates=" + describeBeliefIds(candidates));

        boolean pruned;
        Map<BeliefNode, Integer> dist;
        do {
            pruned = false;
            for (BeliefNode node : new ArrayList<>(candidates)) {
                if (!isBeliefUSafe(node, candidates)) {
                    candidates.remove(node);
                    pruned = true;
                    log("[Fairness] prune unsafe candidate " + describeBelief(node));
                }
            }
            if (candidates.isEmpty()) {
                log("[Fairness] no candidates after U-safety pruning");
                return false;
            }

            dist = computeFairDistances(candidates);
            for (BeliefNode node : new ArrayList<>(candidates)) {
                if (!dist.containsKey(node)) {
                    candidates.remove(node);
                    pruned = true;
                    log("[Fairness] prune candidate without progress distance " + describeBelief(node));
                }
            }
        } while (pruned);

        if (candidates.isEmpty()) {
            log("[Fairness] no candidates after distance pruning");
            return false;
        }

        boolean promoted = false;
        dist = computeFairDistances(candidates);
        for (BeliefNode node : candidates) {
            if (node.status != BeliefStatus.UNKNOWN) {
                continue;
            }
            if (node.finishNcTarget != null) {
                setStatus(node, BeliefStatus.WINNING,
                        "fairness promotion with finishUpdate target NC " + node.finishNcTarget);
                node.selectedFinish = true;
                promoted = true;
                continue;
            }
            BeliefEdge edge = selectFairProgressEdge(node, candidates, dist);
            if (edge != null) {
                setStatus(node, BeliefStatus.WINNING,
                        "fairness promotion via " + edge.action);
                if (edge.controllable) {
                    node.selectedControllable = edge;
                }
                promoted = true;
            }
        }
        if (promoted) {
            log("[Fairness] promoted candidates=" + describeBeliefIds(candidates));
        }
        return promoted;
    }

    private boolean isFairnessEligible(BeliefNode node) {
        if (hasUnexpandedUncontrollable(node)) {
            return false;
        }
        for (ConcreteState member : node.members) {
            if (getMarkingState(member) != 8) {
                return false;
            }
        }
        return true;
    }

    private boolean isBeliefUSafe(BeliefNode node, Set<BeliefNode> candidates) {
        for (BeliefEdge edge : node.uncontrollableEdges) {
            if (edge.target.status != BeliefStatus.WINNING && !candidates.contains(edge.target)) {
                return false;
            }
        }
        return true;
    }

    private Map<BeliefNode, Integer> computeFairDistances(Set<BeliefNode> candidates) {
        Map<BeliefNode, Integer> dist = new HashMap<>();
        boolean changed;
        do {
            changed = false;
            for (BeliefNode node : candidates) {
                int best = fairDistance(node, candidates, dist);
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

    private int fairDistance(
            BeliefNode node,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        int best = node.finishNcTarget == null ? Integer.MAX_VALUE : 1;
        for (BeliefEdge edge : allBeliefEdges(node)) {
            if (!canUseForFairReachability(node, edge, candidates)) {
                continue;
            }
            int childDistance = targetFairDistance(edge.target, candidates, dist);
            if (childDistance != Integer.MAX_VALUE) {
                best = Math.min(best, childDistance + 1);
            }
        }
        return best;
    }

    private int targetFairDistance(
            BeliefNode target,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        if (target.status == BeliefStatus.WINNING) {
            return 0;
        }
        if (!candidates.contains(target)) {
            return Integer.MAX_VALUE;
        }
        Integer value = dist.get(target);
        return value == null ? Integer.MAX_VALUE : value;
    }

    private BeliefEdge selectFairProgressEdge(
            BeliefNode node,
            Set<BeliefNode> candidates,
            Map<BeliefNode, Integer> dist) {

        BeliefEdge best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BeliefEdge edge : allBeliefEdges(node)) {
            if (!canUseForFairReachability(node, edge, candidates)) {
                continue;
            }
            int childDistance = targetFairDistance(edge.target, candidates, dist);
            if (childDistance == Integer.MAX_VALUE) {
                continue;
            }
            if (childDistance < bestDistance
                    || (childDistance == bestDistance && shouldPreferFairEdge(best, edge))) {
                bestDistance = childDistance;
                best = edge;
            }
        }
        return best;
    }

    private boolean shouldPreferFairEdge(BeliefEdge current, BeliefEdge candidate) {
        if (current == null) {
            return true;
        }
        if (!current.controllable && candidate.controllable) {
            return true;
        }
        return !isUpdateProtocolActionName(current.action.toString())
                && isUpdateProtocolActionName(candidate.action.toString());
    }

    private boolean canUseForFairReachability(
            BeliefNode node,
            BeliefEdge edge,
            Set<BeliefNode> candidates) {

        if (!edge.controllable) {
            return true;
        }
        if (isUpdateProtocolActionName(edge.action.toString())
                || edge.action.toString().equals(UpdateConstants.FINISH_UPDATE)) {
            return true;
        }
        return !hasUncontrollableSuccessorIn(node, candidates);
    }

    private boolean hasUncontrollableSuccessorIn(BeliefNode node, Set<BeliefNode> candidates) {
        for (BeliefEdge edge : node.uncontrollableEdges) {
            if (candidates.contains(edge.target)) {
                return true;
            }
        }
        return false;
    }

    private List<BeliefEdge> allBeliefEdges(BeliefNode node) {
        List<BeliefEdge> result = new ArrayList<>(
                node.uncontrollableEdges.size() + node.controllableEdges.size());
        result.addAll(node.uncontrollableEdges);
        result.addAll(node.controllableEdges.values());
        return result;
    }

    private boolean hasUnexpandedUncontrollable(BeliefNode node) {
        return firstExpandableUncontrollableAction(node) != null;
    }

    private boolean markClosedErrors(BeliefNode root) {
        boolean changed = false;
        for (BeliefNode node : collectReachableBeliefNodes(root)) {
            if (node.status != BeliefStatus.UNKNOWN) {
                continue;
            }
            if (firstExpandableUpdateAction(node) != null
                    || firstExpandableUncontrollableAction(node) != null
                    || firstExpandableCommonControllableAction(node) != null) {
                continue;
            }
            boolean hasSafeUncontrollable = false;
            for (BeliefEdge edge : node.uncontrollableEdges) {
                if (edge.target.status != BeliefStatus.ERROR) {
                    hasSafeUncontrollable = true;
                    break;
                }
            }
            boolean hasSafeControllable = false;
            for (BeliefEdge edge : node.controllableEdges.values()) {
                if (edge.target.status != BeliefStatus.ERROR) {
                    hasSafeControllable = true;
                    break;
                }
            }
            if (node.finishNcTarget == null && !hasSafeUncontrollable && !hasSafeControllable) {
                setStatus(node, BeliefStatus.ERROR, "no safe expanded or expandable action remains");
                changed = true;
            }
        }
        return changed;
    }

    private boolean hasErrorUncontrollableSuccessor(BeliefNode node) {
        for (BeliefEdge edge : node.uncontrollableEdges) {
            if (edge.target.status == BeliefStatus.ERROR) {
                return true;
            }
        }
        return false;
    }

    private BeliefEdge findWinningControllableEdge(BeliefNode node) {
        List<BeliefEdge> edges = new ArrayList<>(node.controllableEdges.values());
        edges.sort(Comparator.comparing(edge -> edge.action.toString()));
        for (BeliefEdge edge : edges) {
            if (edge.target.status == BeliefStatus.WINNING) {
                return edge;
            }
        }
        return null;
    }

    private List<BeliefNode> collectReachableBeliefNodes(BeliefNode root) {
        List<BeliefNode> result = new ArrayList<>();
        Set<BeliefNode> seen = new HashSet<>();
        Deque<BeliefNode> queue = new ArrayDeque<>();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty()) {
            BeliefNode node = queue.remove();
            result.add(node);
            for (BeliefEdge edge : node.uncontrollableEdges) {
                if (seen.add(edge.target)) {
                    queue.add(edge.target);
                }
            }
            for (BeliefEdge edge : node.controllableEdges.values()) {
                if (seen.add(edge.target)) {
                    queue.add(edge.target);
                }
            }
        }
        return result;
    }

    private StepResult stepConcrete(ConcreteState source, HAction<State, Action> action) {
        long markingState = getMarkingState(source);
        List<Set<State>> possibleStatesPerLts = new ArrayList<>(ltssSize);
        for (int i = 0; i < ltssSize; i++) {
            ComponentStep step = componentStep(i, markingState, source.states.get(i), action);
            if (step.invalid) {
                return new StepResult(true, Collections.<ConcreteState>emptySet());
            }
            possibleStatesPerLts.add(step.successors);
        }

        List<List<State>> rawChildren = new ArrayList<>();
        buildCartesianProduct(possibleStatesPerLts, 0, new ArrayList<State>(), rawChildren);
        if (action.toString().equals(UpdateConstants.START_NEW_SPEC)) {
            for (List<State> childVector : rawChildren) {
                applySafetySync(childVector);
            }
        }

        Set<ConcreteState> children = new LinkedHashSet<>();
        for (List<State> rawChild : rawChildren) {
            children.add(getOrCreateConcreteState(rawChild));
        }
        return new StepResult(false, children);
    }

    private ComponentStep componentStep(
            int ltsIndex,
            long markingState,
            State currentState,
            HAction<State, Action> action) {

        if (!isTrace(ltsIndex, markingState)) {
            return new ComponentStep(false, Collections.singleton(currentState));
        }

        LTS<State, Action> lts = ltss.get(ltsIndex);
        Action rawAction = action.getAction();
        Set<State> image = lts.getTransitions(currentState).getImage(rawAction);
        if (image == null || image.isEmpty()) {
            if (isActive(ltsIndex, markingState) && lts.getActions().contains(rawAction)) {
                return new ComponentStep(true, Collections.<State>emptySet());
            }
            return new ComponentStep(false, Collections.singleton(currentState));
        }
        return new ComponentStep(false, image);
    }

    private Set<HAction<State, Action>> enabledActions(ConcreteState state) {
        Set<HAction<State, Action>> result = new LinkedHashSet<>();
        List<HAction<State, Action>> actions = new ArrayList<>(alphabet.getHActions());
        actions.sort(this::compareActions);
        for (HAction<State, Action> action : actions) {
            if (isActionEnabled(state, action)) {
                result.add(action);
            }
        }
        return result;
    }

    private boolean isActionEnabled(ConcreteState state, HAction<State, Action> action) {
        long markingState = getMarkingState(state);
        for (int i = 0; i < ltssSize; i++) {
            if (!componentAllows(i, markingState, state.states.get(i), action.getAction())) {
                return false;
            }
        }
        return true;
    }

    private boolean componentAllows(int ltsIndex, long markingState, State currentState, Action action) {
        if (!isTrace(ltsIndex, markingState)) {
            return true;
        }
        LTS<State, Action> lts = ltss.get(ltsIndex);
        boolean inAlphabet = lts.getActions().contains(action);
        if (!isActive(ltsIndex, markingState)) {
            return true;
        }
        if (!inAlphabet) {
            return true;
        }
        Set<State> image = lts.getTransitions(currentState).getImage(action);
        return image != null && !image.isEmpty();
    }

    private ConcreteState getOrCreateConcreteState(List<State> rawStates) {
        List<State> canonical = canonicalize(rawStates);
        ConcreteState existing = concreteStates.get(canonical);
        if (existing != null) {
            return existing;
        }
        ConcreteState created = new ConcreteState(nextConcreteId++, canonical);
        concreteStates.put(canonical, created);
        log("[Concrete] create " + describeConcrete(created));
        return created;
    }

    private List<State> canonicalize(List<State> rawStates) {
        List<State> result = new ArrayList<>(rawStates);
        long markingState = getMarkingState(result);
        for (int i = 0; i < result.size(); i++) {
            State value = result.get(i);
            if (!isTrace(i, markingState) && !isErrorValue(value)) {
                result.set(i, normalizedValue);
            }
        }
        return result;
    }

    private BeliefNode getOrCreateBeliefNode(Set<ConcreteState> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        List<ConcreteState> ordered = new ArrayList<>(members);
        ordered.sort(Comparator.comparingInt(state -> state.id));
        List<Integer> key = new ArrayList<>();
        for (ConcreteState member : ordered) {
            key.add(member.id);
        }
        BeliefNode existing = beliefNodes.get(key);
        if (existing != null) {
            return existing;
        }
        BeliefNode created = new BeliefNode(nextBeliefId++, ordered, key);
        beliefNodes.put(key, created);
        log("[Belief] create " + describeBelief(created));
        return created;
    }

    private void addEdge(BeliefNode source, BeliefEdge edge) {
        if (edge == null || edge.target == null) {
            return;
        }
        if (edge.controllable) {
            source.controllableEdges.put(edge.action.toString(), edge);
        } else {
            source.uncontrollableEdges.add(edge);
        }
        edge.target.parents.add(source);
        generatedBeliefEdges++;
        log("[Edge] " + describeBelief(source)
                + " --" + edge.action + (edge.controllable ? " [C]" : " [U]")
                + "--> " + describeBelief(edge.target));
    }

    private LTS<Long, Action> buildOutputController(
            Set<State> oldStatesToUpdate,
            Map<State, BeliefNode> roots,
            Action beginUpdate) {

        log("[Output] build start oldStatesToUpdate=" + oldStatesToUpdate.size()
                + ", roots=" + roots.size());
        long nextId = 0L;
        LTSImpl<Long, Action> result = new LTSImpl<>(0L);
        result.addActions(alphabet.getActions());
        result.addActions(newController.getActions());
        result.addAction(beginUpdate);

        for (Long ncState : newController.getStates()) {
            result.addState(ncState);
            nextId = Math.max(nextId, ncState + 1L);
        }
        for (Long ncState : newController.getStates()) {
            for (Pair<Action, Long> transition : newController.getTransitions(ncState)) {
                result.addTransition(ncState, transition.getFirst(), transition.getSecond());
            }
        }

        LTS<State, Action> oldController = ltss.get(idxOC);
        Map<State, Long> oldIds = new LinkedHashMap<>();
        for (State oldState : oldController.getStates()) {
            Long id = nextId++;
            oldIds.put(oldState, id);
            result.addState(id);
        }
        for (State oldState : oldController.getStates()) {
            Long sourceId = oldIds.get(oldState);
            for (Pair<Action, State> transition : oldController.getTransitions(oldState)) {
                Long targetId = oldIds.get(transition.getSecond());
                if (targetId != null) {
                    result.addAction(transition.getFirst());
                    result.addTransition(sourceId, transition.getFirst(), targetId);
                }
            }
        }

        Set<BeliefNode> outputBeliefs = collectWinningBeliefNodesFromRoots(roots.values());
        log("[Output] winning belief nodes=" + describeBeliefIds(outputBeliefs));
        Map<BeliefNode, Long> beliefIds = new LinkedHashMap<>();
        for (BeliefNode node : outputBeliefs) {
            Long id = nextId++;
            beliefIds.put(node, id);
            result.addState(id);
            log("[Output] belief id B" + node.id + " -> " + id);
        }

        Long oldInitial = oldIds.get(oldController.getInitialState());
        if (oldInitial == null) {
            throw new IllegalStateException("Missing output id for old-controller initial state.");
        }
        result = resetInitialState(result, oldInitial);

        for (State oldState : oldStatesToUpdate) {
            BeliefNode root = roots.get(oldState);
            Long oldId = oldIds.get(oldState);
            Long rootId = beliefIds.get(root);
            if (root != null && root.status == BeliefStatus.WINNING && oldId != null && rootId != null) {
                result.addTransition(oldId, beginUpdate, rootId);
                log("[Output] hotSwapIn old=" + oldState + " id=" + oldId
                        + " -> B" + root.id + " id=" + rootId);
            }
        }

        for (BeliefNode node : outputBeliefs) {
            Long sourceId = beliefIds.get(node);
            for (BeliefEdge edge : node.uncontrollableEdges) {
                if (edge.target.status == BeliefStatus.WINNING) {
                    Long targetId = beliefIds.get(edge.target);
                    if (targetId != null) {
                        result.addAction(edge.action);
                        result.addTransition(sourceId, edge.action, targetId);
                        log("[Output] U edge B" + node.id + "(" + sourceId + ") --"
                                + edge.action + "--> B" + edge.target.id + "(" + targetId + ")");
                    }
                }
            }
            if (node.selectedFinish && node.finishAction != null && node.finishNcTarget != null) {
                result.addAction(node.finishAction);
                result.addTransition(sourceId, node.finishAction, node.finishNcTarget);
                log("[Output] finish B" + node.id + "(" + sourceId + ") --"
                        + node.finishAction + "--> NC " + node.finishNcTarget);
            } else if (node.selectedControllable != null
                    && node.selectedControllable.target.status == BeliefStatus.WINNING) {
                Long targetId = beliefIds.get(node.selectedControllable.target);
                if (targetId != null) {
                    result.addAction(node.selectedControllable.action);
                    result.addTransition(sourceId, node.selectedControllable.action, targetId);
                    log("[Output] selected C edge B" + node.id + "(" + sourceId + ") --"
                            + node.selectedControllable.action + "--> B"
                            + node.selectedControllable.target.id + "(" + targetId + ")");
                }
            }
        }

        log("[Output] build finished states=" + result.getStates().size()
                + ", actions=" + result.getActions().size());
        return result;
    }

    private LTSImpl<Long, Action> resetInitialState(LTSImpl<Long, Action> source, Long initialState) {
        LTSImpl<Long, Action> result = new LTSImpl<>(initialState);
        result.addActions(source.getActions());
        result.addStates(source.getStates());
        for (Long state : source.getStates()) {
            for (Pair<Action, Long> transition : source.getTransitions(state)) {
                result.addTransition(state, transition.getFirst(), transition.getSecond());
            }
        }
        return result;
    }

    private Set<BeliefNode> collectWinningBeliefNodesFromRoots(Iterable<BeliefNode> roots) {
        Set<BeliefNode> result = new LinkedHashSet<>();
        Deque<BeliefNode> queue = new ArrayDeque<>();
        for (BeliefNode root : roots) {
            if (root != null && root.status == BeliefStatus.WINNING && result.add(root)) {
                queue.add(root);
            }
        }
        while (!queue.isEmpty()) {
            BeliefNode node = queue.remove();
            for (BeliefEdge edge : node.uncontrollableEdges) {
                if (edge.target.status == BeliefStatus.WINNING && result.add(edge.target)) {
                    queue.add(edge.target);
                }
            }
            if (node.selectedControllable != null
                    && node.selectedControllable.target.status == BeliefStatus.WINNING
                    && result.add(node.selectedControllable.target)) {
                queue.add(node.selectedControllable.target);
            }
        }
        return result;
    }

    private List<String> commonControllableActionNames(BeliefNode node) {
        Set<String> common = null;
        for (ConcreteState member : node.members) {
            Set<String> names = new LinkedHashSet<>();
            for (HAction<State, Action> action : enabledActions(member)) {
                if (action.isControllable()
                        && !action.toString().equals(UpdateConstants.BEGIN_UPDATE)
                        && !action.toString().equals(UpdateConstants.FINISH_UPDATE)) {
                    names.add(action.toString());
                }
            }
            if (common == null) {
                common = names;
            } else {
                common.retainAll(names);
            }
        }
        if (common == null || common.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(common);
        result.sort((left, right) -> {
            int cmp = Integer.compare(actionPriority(left), actionPriority(right));
            if (cmp != 0) {
                return cmp;
            }
            return left.compareTo(right);
        });
        return result;
    }

    private boolean isActionEnabledInAllMembers(BeliefNode node, String actionName) {
        Action action = actionByName(actionName);
        if (action == null) {
            return false;
        }
        HAction<State, Action> hAction = alphabet.getHAction(action);
        if (hAction == null) {
            return false;
        }
        for (ConcreteState member : node.members) {
            if (!isActionEnabled(member, hAction)) {
                return false;
            }
        }
        return true;
    }

    private void applySafetySync(List<State> childStates) {
        if (safetyComponentIndicesMap == null || safetyStateLookupMap == null) {
            return;
        }
        for (Map.Entry<Integer, List<Integer>> entry : safetyComponentIndicesMap.entrySet()) {
            int safetyIdx = entry.getKey();
            List<Integer> compIndices = entry.getValue();
            List<Integer> lookupKey = new ArrayList<>();
            for (int compIdx : compIndices) {
                lookupKey.add(intValue(childStates.get(compIdx)));
            }
            Map<List<Integer>, Integer> lookupTable = safetyStateLookupMap.get(safetyIdx);
            if (lookupTable != null && lookupTable.containsKey(lookupKey)) {
                childStates.set(safetyIdx, castState(lookupTable.get(lookupKey).longValue()));
            } else {
                childStates.set(safetyIdx, errorValue);
            }
        }
    }

    private String generateNCSignature(ConcreteState state) {
        List<State> vector = state.states;
        StringBuilder sb = new StringBuilder();
        for (int k = mappingStart; k <= mappingEnd; k++) {
            if (k > mappingStart) {
                sb.append(",");
            }
            Integer mapEnvId = intValue(vector.get(k));
            Integer newEnvId = mappingMapEnvToNewEnv.get(k - mappingStart).get(mapEnvId);
            if (newEnvId == null) {
                return null;
            }
            sb.append(newEnvId);
        }
        sb.append("|");
        for (int k = newSafeStart; k <= newSafeEnd; k++) {
            if (k > newSafeStart) {
                sb.append(",");
            }
            sb.append(vector.get(k));
        }
        return sb.toString();
    }

    private void buildCartesianProduct(
            List<Set<State>> sets,
            int index,
            List<State> current,
            List<List<State>> result) {
        if (index == sets.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (State state : sets.get(index)) {
            current.add(state);
            buildCartesianProduct(sets, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private boolean isError(ConcreteState state) {
        long markingState = getMarkingState(state);
        for (int i = 0; i < ltssSize; i++) {
            if (isErrorValue(state.states.get(i)) && isEnforce(i, markingState)) {
                return true;
            }
        }
        return false;
    }

    private boolean isActive(int ltsIndex, long markingState) {
        if (ltsIndex == idxMarking || isInRange(ltsIndex, transReqStart, transReqEnd)) {
            return true;
        }
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd)) {
            return false;
        }
        if (markingState == 0) {
            if (ltsIndex == idxOC || isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return true;
            }
            return false;
        }
        if (markingState >= 1 && markingState <= 9) {
            if (ltsIndex == idxOC) {
                return false;
            }
            if (isInRange(ltsIndex, mappingStart, mappingEnd)) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return markingState == 1 || markingState == 3 || markingState == 5 || markingState == 7;
            }
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                return markingState >= 5;
            }
        }
        return true;
    }

    private boolean isEnforce(int ltsIndex, long markingState) {
        if (ltsIndex == idxMarking || isInRange(ltsIndex, transReqStart, transReqEnd)) {
            return true;
        }
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd)) {
            return false;
        }
        if (markingState == 0) {
            if (ltsIndex == idxOC || isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return true;
            }
            return false;
        }
        if (markingState >= 1 && markingState <= 9) {
            if (ltsIndex == idxOC) {
                return false;
            }
            if (isInRange(ltsIndex, mappingStart, mappingEnd)) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return markingState == 1 || markingState == 3 || markingState == 5 || markingState == 7;
            }
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                return markingState >= 5;
            }
        }
        return true;
    }

    private boolean isTrace(int ltsIndex, long markingState) {
        if (ltsIndex == idxOC) {
            return markingState == 0;
        }
        if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
            if (markingState == 2 || markingState == 4 || markingState == 6 || markingState >= 8) {
                return false;
            }
        }
        if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
            return markingState >= 5;
        }
        return true;
    }

    private boolean isInRange(int index, int start, int end) {
        return start >= 0 && end >= start && index >= start && index <= end;
    }

    private long getMarkingState(ConcreteState state) {
        return getMarkingState(state.states);
    }

    private long getMarkingState(List<State> states) {
        return longValue(states.get(idxMarking));
    }

    private int markingDepth(BeliefNode node) {
        long marking = 0L;
        for (ConcreteState member : node.members) {
            marking = Math.max(marking, getMarkingState(member));
        }
        if (marking == 0) {
            return 0;
        }
        if (marking == 1) {
            return 1;
        }
        if (marking == 9) {
            return 5;
        }
        return 1 + Long.bitCount(marking - 1L);
    }

    private int compareActions(HAction<State, Action> left, HAction<State, Action> right) {
        int cmp = Integer.compare(actionPriority(left.toString()), actionPriority(right.toString()));
        if (cmp != 0) {
            return cmp;
        }
        if (left.isControllable() != right.isControllable()) {
            return left.isControllable() ? 1 : -1;
        }
        return left.toString().compareTo(right.toString());
    }

    private int actionPriority(String actionName) {
        if (isUpdateProtocolActionName(actionName) || actionName.equals(UpdateConstants.FINISH_UPDATE)) {
            return 0;
        }
        Action action = actionByName(actionName);
        HAction<State, Action> hAction = action == null ? null : alphabet.getHAction(action);
        if (hAction != null && !hAction.isControllable()) {
            return 1;
        }
        return 2;
    }

    private List<String> updateActionPriority() {
        List<String> result = new ArrayList<>();
        result.add(UpdateConstants.STOP_OLD_SPEC);
        result.add(UpdateConstants.RECONFIGURE);
        result.add(UpdateConstants.START_NEW_SPEC);
        result.add(UpdateConstants.FINISH_UPDATE);
        return result;
    }

    private boolean isUpdateProtocolActionName(String actionName) {
        return actionName.equals(UpdateConstants.STOP_OLD_SPEC)
                || actionName.equals(UpdateConstants.RECONFIGURE)
                || actionName.equals(UpdateConstants.START_NEW_SPEC);
    }

    private Action actionByName(String actionName) {
        for (Action action : alphabet.getActions()) {
            if (action.toString().equals(actionName)) {
                return action;
            }
        }
        return null;
    }

    private boolean isErrorValue(State state) {
        return longValue(state) == ERROR_LONG;
    }

    private long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }

    private int intValue(Object value) {
        return (int) longValue(value);
    }

    @SuppressWarnings("unchecked")
    private State castState(long value) {
        return (State) Long.valueOf(value);
    }

    private void initializeDebugLog() {
        if (!debugLogEnabled) {
            return;
        }
        try {
            File file = new File(LOG_FILE_PATH);
            File parent = file.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            logWriter = new PrintWriter(new FileWriter(file));
            outln("Belief OTF-DUC debug trace file: " + LOG_FILE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to open Belief OTF-DUC debug log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void closeDebugLog() {
        if (logWriter == null) {
            return;
        }
        log("=== Belief OTF-DUC Synthesis Finished ===");
        logWriter.close();
        logWriter = null;
    }

    public void log(String message) {
        if (debugLogEnabled && logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    private void outln(String message) {
        if (output != null) {
            output.outln(message);
        }
        log("[LTSOutput] " + message);
    }

    private void setStatus(BeliefNode node, BeliefStatus status, String reason) {
        if (node == null) {
            return;
        }
        BeliefStatus oldStatus = node.status;
        if (oldStatus != status) {
            log("[Status] B" + node.id + " " + oldStatus + " -> " + status
                    + (reason == null || reason.isEmpty() ? "" : " : " + reason));
        }
        node.status = status;
        if (status == BeliefStatus.ERROR && reason != null) {
            node.errorReason = reason;
        }
    }

    private String describeConcrete(ConcreteState state) {
        if (state == null) {
            return "<null concrete>";
        }
        return "C" + state.id
                + "(marking=" + getMarkingState(state)
                + ", old=" + state.states.get(idxOC)
                + ", states=" + state.states + ")";
    }

    private String describeConcreteIds(Iterable<ConcreteState> states) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (ConcreteState state : states) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("C").append(state.id)
                    .append("(m=").append(getMarkingState(state))
                    .append(",old=").append(state.states.get(idxOC))
                    .append(")");
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }

    private String describeBelief(BeliefNode node) {
        if (node == null) {
            return "<null belief>";
        }
        return "B" + node.id
                + "(status=" + node.status
                + ", members=" + describeConcreteIds(node.members)
                + ", finishTarget=" + node.finishNcTarget
                + (node.errorReason == null || node.errorReason.isEmpty()
                        ? ""
                        : ", error=\"" + node.errorReason + "\"")
                + ")";
    }

    private String describeBeliefIds(Iterable<BeliefNode> nodes) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (BeliefNode node : nodes) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("B").append(node.id).append(":").append(node.status);
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }

    private void recordRootBuildStats(Map<State, BeliefNode> roots) {
        rootCount = roots.size();
        multiMemberRootCount = 0L;
        maxRootMembers = 0L;
        for (BeliefNode root : roots.values()) {
            if (root == null) {
                continue;
            }
            int memberCount = root.members.size();
            if (memberCount > 1) {
                multiMemberRootCount++;
            }
            maxRootMembers = Math.max(maxRootMembers, memberCount);
        }
    }

    private void captureOutputControllerStats(LTS<Long, Action> result) {
        long countStart = System.currentTimeMillis();
        if (result == null) {
            outputControllerStates = -1L;
            outputControllerTransitions = -1L;
            outputControllerActions = -1L;
            outputTransitionStats = new OutputTransitionStats();
            outputControllerCountTime = System.currentTimeMillis() - countStart;
            return;
        }

        outputControllerStates = result.getStates().size();
        outputControllerTransitions = countTransitions(result);
        outputControllerActions = result.getActions().size();
        outputTransitionStats = countOutputTransitionStats(result);
        outputControllerCountTime = System.currentTimeMillis() - countStart;
    }

    private long countTransitions(LTS<Long, Action> lts) {
        long count = 0L;
        for (Long state : lts.getStates()) {
            for (Pair<Action, Long> ignored : lts.getTransitions(state)) {
                count++;
            }
        }
        return count;
    }

    private OutputTransitionStats countOutputTransitionStats(LTS<Long, Action> lts) {
        OutputTransitionStats stats = new OutputTransitionStats();
        for (Long state : lts.getStates()) {
            boolean hasBeginUpdate = false;
            for (Pair<Action, Long> transition : lts.getTransitions(state)) {
                String actionName = transition.getFirst().toString();
                if (actionName.equals(UpdateConstants.BEGIN_UPDATE)) {
                    stats.beginUpdateTransitions++;
                    hasBeginUpdate = true;
                } else if (actionName.equals(UpdateConstants.STOP_OLD_SPEC)) {
                    stats.stopOldSpecTransitions++;
                } else if (actionName.equals(UpdateConstants.RECONFIGURE)) {
                    stats.reconfigureTransitions++;
                } else if (actionName.equals(UpdateConstants.START_NEW_SPEC)) {
                    stats.startNewSpecTransitions++;
                } else if (actionName.equals(UpdateConstants.FINISH_UPDATE)) {
                    stats.finishUpdateTransitions++;
                } else {
                    stats.normalTransitions++;
                }
            }
            if (hasBeginUpdate) {
                stats.beginUpdateOutgoingStates++;
            }
        }
        return stats;
    }

    private long countBeliefNodesWithStatus(BeliefStatus status) {
        long count = 0L;
        for (BeliefNode node : beliefNodes.values()) {
            if (node.status == status) {
                count++;
            }
        }
        return count;
    }

    private long countMultiMemberBeliefNodes() {
        long count = 0L;
        for (BeliefNode node : beliefNodes.values()) {
            if (node.members.size() > 1) {
                count++;
            }
        }
        return count;
    }

    private long maxBeliefMemberCount() {
        long max = 0L;
        for (BeliefNode node : beliefNodes.values()) {
            max = Math.max(max, node.members.size());
        }
        return max;
    }

    private String averageBeliefMemberCountText() {
        if (beliefNodes.isEmpty()) {
            return "0.000";
        }
        long total = 0L;
        for (BeliefNode node : beliefNodes.values()) {
            total += node.members.size();
        }
        return String.format(java.util.Locale.ROOT, "%.3f", (double) total / beliefNodes.size());
    }

    private long oldControllerStateCount() {
        if (ltss == null || ltss.size() <= idxOC || ltss.get(idxOC) == null) {
            return -1L;
        }
        return ltss.get(idxOC).getStates().size();
    }

    private void recordEvaluation() {
        if (evaluationRecorded) {
            return;
        }
        evaluationRecorded = true;

        long oldControllerStates = oldControllerStateCount();
        long searchTime = preBfsTime + rootBuildTime + rootSolveTime;
        long winningBeliefs = countBeliefNodesWithStatus(BeliefStatus.WINNING);
        long errorBeliefs = countBeliefNodesWithStatus(BeliefStatus.ERROR);
        long unknownBeliefs = countBeliefNodesWithStatus(BeliefStatus.UNKNOWN);
        long multiMemberBeliefs = countMultiMemberBeliefNodes();
        long preUpdateOutputStates = oldControllerStates >= 0L
                ? oldControllerStates
                : reachableOldStates;

        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "synthesizeDUC 実行時間", synthesizeDUCTime);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "DCS (OTF-DUC)",
                "DCS で探索した状態数と遷移数の最大値",
                beliefNodes.size(),
                generatedBeliefEdges,
                0L,
                "Belief OTF-DUC では状態数=belief node 数, 遷移数=belief edge 数");
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "DCS で探索した時間", searchTime);
        UpdatingControllerEvaluationRecorder.recordCount(
                "DCS (OTF-DUC)",
                "expandDUC 呼び出し回数",
                expandedActions,
                "回");
        UpdatingControllerEvaluationRecorder.recordTime(
                "DCS (OTF-DUC)", "buildDirectorDUC 実行時間", outputBuildTime);

        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "synthesizeDUC 実行時間", synthesizeDUCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "PreBFS 実行時間", preBfsTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "belief root 構築時間", rootBuildTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "incremental root solve 時間", rootSolveTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "出力 controller 構築時間", outputBuildTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Belief OTF-DUC", "出力 controller 計数時間", outputControllerCountTime);
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "到達可能旧コントローラ状態数", reachableOldStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "pre-update concrete 状態数", preUpdateConcreteStates, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "belief root 数", rootCount, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "multi-member belief root 数", multiMemberRootCount, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "root member 最大数", maxRootMembers, "状態");
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "Belief OTF-DUC", "multi-member belief root 率",
                multiMemberRootCount, rootCount, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "zero expansion root 数", zeroExpansionRoots, "状態");
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "Belief OTF-DUC", "global cache reuse root 率（expansion=0）",
                zeroExpansionRoots, rootCount, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "root あたり最大展開数", maxExpansionsForRoot, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "belief node 数", beliefNodes.size(), "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "multi-member belief node 数", multiMemberBeliefs, "状態");
        UpdatingControllerEvaluationRecorder.recordDecisionRate(
                "Belief OTF-DUC", "multi-member belief node 率",
                multiMemberBeliefs, beliefNodes.size(), "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "belief node member 最大数", maxBeliefMemberCount(), "状態");
        UpdatingControllerEvaluationRecorder.recordValue(
                "Belief OTF-DUC", "belief node member 平均数", averageBeliefMemberCountText());
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "concrete 状態数", concreteStates.size(), "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "belief action 展開数", expandedActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "controllable action 展開試行数", controllableExpansionAttempts, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "uncontrollable action 展開試行数", uncontrollableExpansionAttempts, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "finishUpdate 展開試行数", finishExpansionAttempts, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "accepted controllable action 数", acceptedControllableActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "rejected controllable action 数", rejectedControllableActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "accepted uncontrollable action 数", acceptedUncontrollableActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "accepted finishUpdate 数", acceptedFinishActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "rejected finishUpdate 数", rejectedFinishActions, "回");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "belief edge 数", generatedBeliefEdges, "遷移");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "WINNING belief node 数", winningBeliefs, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "ERROR belief node 数", errorBeliefs, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "UNKNOWN belief node 数", unknownBeliefs, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "解けた旧状態 root 数", solvedRoots, "状態");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Belief OTF-DUC", "失敗した旧状態 root 数", failedRoots, "状態");
        UpdatingControllerEvaluationRecorder.recordValue(
                "Belief OTF-DUC", "maxBeliefNodes", describeLimit(maxBeliefNodes));
        UpdatingControllerEvaluationRecorder.recordValue(
                "Belief OTF-DUC", "maxConcreteStates", describeLimit(maxConcreteStates));
        UpdatingControllerEvaluationRecorder.recordValue(
                "Belief OTF-DUC", "maxExpansionsPerRoot", describeLimit(maxExpansionsPerRoot));

        if (outputControllerStates >= 0L) {
            UpdatingControllerEvaluationRecorder.recordOutputController(
                    outputControllerStates,
                    outputControllerTransitions,
                    outputControllerCountTime);
            UpdatingControllerEvaluationRecorder.recordBeginUpdateCoverage(
                    outputTransitionStats.beginUpdateOutgoingStates,
                    outputControllerCountTime);
            UpdatingControllerEvaluationRecorder.recordCount(
                    "Belief OTF-DUC 出力",
                    "出力 controller action 数",
                    outputControllerActions,
                    "actions");
            UpdatingControllerEvaluationRecorder.recordUpdateEventTransitionCounts(
                    "Belief OTF-DUC 出力 update events",
                    "Output Update Controller",
                    outputTransitionStats.beginUpdateTransitions,
                    outputTransitionStats.stopOldSpecTransitions,
                    outputTransitionStats.reconfigureTransitions,
                    outputTransitionStats.startNewSpecTransitions,
                    outputTransitionStats.finishUpdateTransitions,
                    outputTransitionStats.normalTransitions,
                    outputControllerCountTime);
        }

        UpdatingControllerEvaluationRecorder.recordOtfPreUpdateStateOverhead(
                oldControllerStates,
                preUpdateConcreteStates,
                preUpdateOutputStates);
    }

    private enum BeliefStatus {
        UNKNOWN,
        WINNING,
        ERROR
    }

    private static final class OutputTransitionStats {
        private long beginUpdateTransitions;
        private long stopOldSpecTransitions;
        private long reconfigureTransitions;
        private long startNewSpecTransitions;
        private long finishUpdateTransitions;
        private long normalTransitions;
        private long beginUpdateOutgoingStates;
    }

    private final class ConcreteState {
        private final int id;
        private final List<State> states;

        private ConcreteState(int id, List<State> states) {
            this.id = id;
            this.states = states;
        }
    }

    private final class BeliefNode {
        private final int id;
        private final List<ConcreteState> members;
        private final List<Integer> key;
        private final List<BeliefEdge> uncontrollableEdges = new ArrayList<>();
        private final Map<String, BeliefEdge> controllableEdges = new LinkedHashMap<>();
        private final Set<String> expandedActions = new HashSet<>();
        private final Set<String> rejectedActions = new HashSet<>();
        private final Set<BeliefNode> parents = new LinkedHashSet<>();
        private BeliefStatus status = BeliefStatus.UNKNOWN;
        private Action finishAction;
        private Long finishNcTarget;
        private boolean finishExpanded;
        private boolean selectedFinish;
        private BeliefEdge selectedControllable;
        private String errorReason = "";

        private BeliefNode(int id, List<ConcreteState> members, List<Integer> key) {
            this.id = id;
            this.members = members;
            this.key = key;
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof DirectedControllerSynthesisBeliefDUC.BeliefNode)) {
                return false;
            }
            BeliefNode other = (BeliefNode) obj;
            return key.equals(other.key);
        }
    }

    private final class BeliefEdge {
        private final Action action;
        private final boolean controllable;
        private final BeliefNode target;

        private BeliefEdge(Action action, boolean controllable, BeliefNode target) {
            this.action = action;
            this.controllable = controllable;
            this.target = target;
        }
    }

    private final class ExpansionCandidate {
        private final BeliefNode node;
        private final String actionName;

        private ExpansionCandidate(BeliefNode node, String actionName) {
            this.node = node;
            this.actionName = actionName;
        }
    }

    private final class StepResult {
        private final boolean invalid;
        private final Set<ConcreteState> children;

        private StepResult(boolean invalid, Set<ConcreteState> children) {
            this.invalid = invalid;
            this.children = children;
        }
    }

    private final class ComponentStep {
        private final boolean invalid;
        private final Set<State> successors;

        private ComponentStep(boolean invalid, Set<State> successors) {
            this.invalid = invalid;
            this.successors = successors;
        }
    }
}
