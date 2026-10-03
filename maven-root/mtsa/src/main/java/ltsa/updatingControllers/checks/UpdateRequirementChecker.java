package ltsa.updatingControllers.checks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.CompositionExpression;
import ltsa.lts.EventState;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import ltsa.lts.LTSOutput;
import ltsa.lts.Symbol;
import ltsa.lts.UpdatingControllerCheckFormulaFactory;
import ltsa.lts.UpdatingControllersDefinition;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.PredicateDefinition;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;

public final class UpdateRequirementChecker {

    private static final Pattern TARGET_ALIAS_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\|\\s*%s\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.");

    private UpdateRequirementChecker() {
    }

    public static Report check(File transitionsFile, File ltsFile, String target) throws IOException {
        TransitionGraph controller = TransitionGraph.fromPrintTransitionsFile(transitionsFile);
        SpecContext spec = SpecContext.load(ltsFile, target);
        PhaseAwareUpdateEnvironment reference = spec.phaseAwareReference();
        boolean bulkProtocol = spec.supportsBulkProtocolChecks();

        List<RequirementResult> results = new ArrayList<RequirementResult>();
        results.add(checkHotSwapEnabled(controller, reference));
        results.add(bulkProtocol
                ? checkSafetyMonitors("R2", "old_goal_until_stopOldSpec",
                        compileOldSafetyMonitors(spec), controller)
                : RequirementResult.unknown("R2", "old_goal_until_stopOldSpec",
                        "bulk update protocol was not available"));
        results.add(checkSafetyMonitors("R3", "transition_requirement",
                compileTransitionMonitors(spec), controller));
        results.add(bulkProtocol
                ? checkSafetyMonitors("R4", "new_goal_after_startNewSpec",
                        compileNewSafetyMonitors(spec), controller)
                : RequirementResult.unknown("R4", "new_goal_after_startNewSpec",
                        "bulk update protocol was not available"));
        results.add(bulkProtocol
                ? checkEventually(controller, "R5",
                        "eventually_stopOldSpec_after_hotSwapIn",
                        UpdateConstants.STOP_OLD_SPEC)
                : RequirementResult.unknown("R5",
                        "eventually_stopOldSpec_after_hotSwapIn",
                        "bulk update protocol was not available"));
        results.add(bulkProtocol
                ? checkEventually(controller, "R6",
                        "eventually_reconfigure_after_hotSwapIn",
                        UpdateConstants.RECONFIGURE)
                : RequirementResult.unknown("R6",
                        "eventually_reconfigure_after_hotSwapIn",
                        "bulk update protocol was not available"));
        results.add(bulkProtocol
                ? checkEventually(controller, "R7",
                        "eventually_startNewSpec_after_hotSwapIn",
                        UpdateConstants.START_NEW_SPEC)
                : RequirementResult.unknown("R7",
                        "eventually_startNewSpec_after_hotSwapIn",
                        "bulk update protocol was not available"));
        results.add(checkDeadlockFree(controller));
        results.add(checkLegality(controller, spec, reference));

        return new Report(results);
    }

    static RequirementResult checkHotSwapEnabled(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference) {
        if (reference == null) {
            return RequirementResult.unknown("R1", "hotSwapIn_enabled_in_all_pre_update_states",
                    "phase-aware old behavior was not available");
        }
        Set<ReferencePairState> coverageRelation = greatestPreCoverageRelation(
                controller, reference);
        ReferencePairState initial = new ReferencePairState(0, reference.initialState());
        if (!coverageRelation.contains(initial)) {
            return RequirementResult.fail("R1", "hotSwapIn_enabled_in_all_pre_update_states",
                    "the printed controller does not cover every reachable old behavior "
                            + "or does not enable hotSwapIn where H_T is defined",
                    UpdateConstants.BEGIN_UPDATE);
        }
        return RequirementResult.pass("R1", "hotSwapIn_enabled_in_all_pre_update_states",
                "pre-update coverage relation size=" + coverageRelation.size());
    }

    private static RequirementResult checkEventually(
            TransitionGraph graph,
            String id,
            String name,
            String targetAction) {
        Set<Integer> entries = hotSwapEntryStates(graph);
        if (entries.isEmpty()) {
            return RequirementResult.fail(id, name,
                    "no reachable " + UpdateConstants.BEGIN_UPDATE + " transition", "");
        }

        Set<Integer> avoidReachable = new HashSet<Integer>();
        Queue<Integer> queue = new ArrayDeque<Integer>();
        for (Integer entry : entries) {
            if (avoidReachable.add(entry)) {
                queue.add(entry);
            }
        }
        while (!queue.isEmpty()) {
            int state = queue.remove().intValue();
            for (TransitionGraph.Edge edge : graph.getOutgoing(state)) {
                if (targetAction.equals(edge.action)) {
                    continue;
                }
                if (edge.to < 0) {
                    return RequirementResult.fail(id, name,
                            "reachable ERROR before " + targetAction
                                    + " by " + edge.action + " at Q" + state,
                            edge.action);
                }
                if (avoidReachable.add(Integer.valueOf(edge.to))) {
                    queue.add(Integer.valueOf(edge.to));
                }
            }
        }

        for (Integer state : avoidReachable) {
            if (graph.getOutgoing(state.intValue()).isEmpty()) {
                return RequirementResult.fail(id, name,
                        "deadlock before " + targetAction + " at Q" + state, "");
            }
        }

        CycleWitness cycle = findCycleAvoiding(graph, avoidReachable, targetAction);
        if (cycle != null) {
            return RequirementResult.fail(id, name,
                    "cycle can avoid " + targetAction + " at Q" + cycle.state,
                    cycle.action);
        }
        return RequirementResult.pass(id, name,
                "all paths after " + UpdateConstants.BEGIN_UPDATE + " eventually reach " + targetAction);
    }

    private static Set<Integer> hotSwapEntryStates(TransitionGraph graph) {
        Set<Integer> reachable = graph.reachableStates();
        Set<Integer> entries = new HashSet<Integer>();
        for (Integer state : reachable) {
            for (TransitionGraph.Edge edge : graph.getOutgoing(state.intValue())) {
                if (UpdateConstants.BEGIN_UPDATE.equals(edge.action) && edge.to >= 0) {
                    entries.add(Integer.valueOf(edge.to));
                }
            }
        }
        return entries;
    }

    private static CycleWitness findCycleAvoiding(
            TransitionGraph graph,
            Set<Integer> states,
            String targetAction) {
        Map<Integer, Integer> index = new HashMap<Integer, Integer>();
        Map<Integer, Integer> lowlink = new HashMap<Integer, Integer>();
        ArrayList<Integer> stack = new ArrayList<Integer>();
        Set<Integer> onStack = new HashSet<Integer>();
        int[] nextIndex = new int[] { 0 };
        for (Integer state : states) {
            if (!index.containsKey(state)) {
                CycleWitness witness = strongConnect(
                        graph, states, targetAction, state.intValue(),
                        index, lowlink, stack, onStack, nextIndex);
                if (witness != null) {
                    return witness;
                }
            }
        }
        return null;
    }

    private static CycleWitness strongConnect(
            TransitionGraph graph,
            Set<Integer> states,
            String targetAction,
            int state,
            Map<Integer, Integer> index,
            Map<Integer, Integer> lowlink,
            ArrayList<Integer> stack,
            Set<Integer> onStack,
            int[] nextIndex) {
        Integer stateKey = Integer.valueOf(state);
        index.put(stateKey, Integer.valueOf(nextIndex[0]));
        lowlink.put(stateKey, Integer.valueOf(nextIndex[0]));
        nextIndex[0]++;
        stack.add(stateKey);
        onStack.add(stateKey);

        for (TransitionGraph.Edge edge : graph.getOutgoing(state)) {
            if (targetAction.equals(edge.action) || edge.to < 0
                    || !states.contains(Integer.valueOf(edge.to))) {
                continue;
            }
            Integer toKey = Integer.valueOf(edge.to);
            if (!index.containsKey(toKey)) {
                CycleWitness witness = strongConnect(
                        graph, states, targetAction, edge.to,
                        index, lowlink, stack, onStack, nextIndex);
                if (witness != null) {
                    return witness;
                }
                lowlink.put(stateKey, Integer.valueOf(Math.min(
                        lowlink.get(stateKey).intValue(), lowlink.get(toKey).intValue())));
            } else if (onStack.contains(toKey)) {
                lowlink.put(stateKey, Integer.valueOf(Math.min(
                        lowlink.get(stateKey).intValue(), index.get(toKey).intValue())));
            }
        }

        if (lowlink.get(stateKey).equals(index.get(stateKey))) {
            List<Integer> component = new ArrayList<Integer>();
            Integer popped;
            do {
                popped = stack.remove(stack.size() - 1);
                onStack.remove(popped);
                component.add(popped);
            } while (!popped.equals(stateKey));
            if (component.size() > 1) {
                return new CycleWitness(component.get(0).intValue(), "scc_size=" + component.size());
            }
            int only = component.get(0).intValue();
            for (TransitionGraph.Edge edge : graph.getOutgoing(only)) {
                if (!targetAction.equals(edge.action) && edge.to == only) {
                    return new CycleWitness(only, edge.action);
                }
            }
        }
        return null;
    }

    private static RequirementResult checkDeadlockFree(TransitionGraph graph) {
        Set<Integer> reachable = graph.reachableStates();
        for (Integer state : reachable) {
            for (TransitionGraph.Edge edge : graph.getOutgoing(state.intValue())) {
                if (edge.to < 0) {
                    return RequirementResult.fail("R8", "deadlock_free",
                            "reachable ERROR by " + edge.action + " at Q" + state,
                            edge.action);
                }
            }
            if (graph.getOutgoing(state.intValue()).isEmpty()) {
                return RequirementResult.fail("R8", "deadlock_free",
                        "reachable deadlock at Q" + state, "");
            }
        }
        return RequirementResult.pass("R8", "deadlock_free",
                "reachable states=" + reachable.size());
    }

    private static List<Monitor> compileOldSafetyMonitors(SpecContext spec) {
        List<Symbol> wrappers = new ArrayList<Symbol>();
        for (Symbol symbol : spec.oldSafety) {
            wrappers.add(addGuardedFormula(symbol, UpdateConstants.STOP_OLD_SPEC, false,
                    "__DUC_CHECK_OLD_"));
        }
        return compileMonitors(wrappers, spec.output);
    }

    private static List<Monitor> compileNewSafetyMonitors(SpecContext spec) {
        List<Symbol> wrappers = new ArrayList<Symbol>();
        for (Symbol symbol : spec.newSafety) {
            wrappers.add(addGuardedFormula(symbol, UpdateConstants.START_NEW_SPEC, true,
                    "__DUC_CHECK_NEW_"));
        }
        return compileMonitors(wrappers, spec.output);
    }

    private static List<Monitor> compileTransitionMonitors(SpecContext spec) {
        return compileMonitors(spec.transitionSafety, spec.output);
    }

    private static Symbol addGuardedFormula(
            Symbol originalName,
            String guardAction,
            boolean positiveGuard,
            String prefix) {
        return UpdatingControllerCheckFormulaFactory.addGuardedSafetyFormula(
                originalName, guardAction, positiveGuard, prefix);
    }

    private static List<Monitor> compileMonitors(List<Symbol> symbols, LTSOutput output) {
        List<Monitor> monitors = new ArrayList<Monitor>();
        PredicateDefinition.compileAll();
        AssertDefinition.compileAll(output);
        for (Symbol symbol : symbols) {
            CompactState state = AssertDefinition.compileConstraint(output, symbol.getName());
            if (state == null) {
                throw new IllegalArgumentException("ltl_property could not be compiled: " + symbol.getName());
            }
            monitors.add(new Monitor(symbol.getName(), state));
        }
        return monitors;
    }

    private static RequirementResult checkSafetyMonitors(
            String id,
            String name,
            List<Monitor> monitors,
            TransitionGraph controller) {
        if (monitors.isEmpty()) {
            return RequirementResult.pass(id, name, "no formulas");
        }
        for (Monitor monitor : monitors) {
            MonitorViolation violation = monitor.findViolation(controller);
            if (violation != null) {
                return RequirementResult.fail(id, name,
                        monitor.name + " violated by action " + violation.action
                                + " at controller Q" + violation.controllerState,
                        join(violation.trace, " "));
            }
        }
        return RequirementResult.pass(id, name, "checked formulas=" + monitors.size());
    }

    private static RequirementResult checkLegality(
            TransitionGraph controller,
            SpecContext spec,
            PhaseAwareUpdateEnvironment reference) {
        return checkLegalityAgainstReference(
                controller, reference, spec.controllableActions);
    }

    static RequirementResult checkLegalityAgainstReference(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference,
            Set<String> controllableActions) {
        if (controllableActions == null || controllableActions.isEmpty()) {
            return RequirementResult.unknown("R9", "legality",
                    "controllable action set was not available");
        }
        if (reference == null) {
            return RequirementResult.unknown("R9", "legality",
                    "phase-aware update environment was not available");
        }

        Set<String> controllable = new HashSet<String>(controllableActions);
        controllable.remove(UpdateConstants.BEGIN_UPDATE);
        Set<ReferencePairState> simulation = greatestAlternatingSimulation(
                controller, reference, controllable);
        ReferencePairState initial = new ReferencePairState(0, reference.initialState());
        if (!simulation.contains(initial)) {
            return RequirementResult.fail("R9", "legality",
                    "the printed controller is not an alternating refinement of the "
                            + "phase-aware update environment",
                    "");
        }
        return RequirementResult.pass("R9", "legality",
                "alternating simulation relation size=" + simulation.size());
    }

    private static Map<String, List<TransitionGraph.Edge>> controllerByAction(
            TransitionGraph controller,
            int state) {
        Map<String, List<TransitionGraph.Edge>> result =
                new HashMap<String, List<TransitionGraph.Edge>>();
        for (TransitionGraph.Edge edge : controller.getOutgoing(state)) {
            List<TransitionGraph.Edge> edges = result.get(edge.action);
            if (edges == null) {
                edges = new ArrayList<TransitionGraph.Edge>();
                result.put(edge.action, edges);
            }
            edges.add(edge);
        }
        return result;
    }

    private static Map<String, List<PhaseAwareUpdateEnvironment.Transition>> referenceByAction(
            PhaseAwareUpdateEnvironment reference,
            PhaseAwareUpdateEnvironment.State state) {
        Map<String, List<PhaseAwareUpdateEnvironment.Transition>> result =
                new HashMap<String, List<PhaseAwareUpdateEnvironment.Transition>>();
        for (PhaseAwareUpdateEnvironment.Transition transition : reference.outgoing(state)) {
            List<PhaseAwareUpdateEnvironment.Transition> transitions =
                    result.get(transition.action());
            if (transitions == null) {
                transitions = new ArrayList<PhaseAwareUpdateEnvironment.Transition>();
                result.put(transition.action(), transitions);
            }
            transitions.add(transition);
        }
        return result;
    }

    private static boolean hasNonErrorTarget(List<TransitionGraph.Edge> edges) {
        if (edges == null) {
            return false;
        }
        for (TransitionGraph.Edge edge : edges) {
            if (edge.to >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsErrorTarget(List<TransitionGraph.Edge> edges) {
        if (edges == null) {
            return false;
        }
        for (TransitionGraph.Edge edge : edges) {
            if (edge.to < 0) {
                return true;
            }
        }
        return false;
    }

    private static Set<ReferencePairState> greatestPreCoverageRelation(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference) {
        Set<ReferencePairState> relation = candidatePairs(controller, reference, true);
        boolean changed;
        do {
            changed = false;
            for (ReferencePairState pair
                    : new HashSet<ReferencePairState>(relation)) {
                if (!preCoveragePairIsValid(controller, reference, pair, relation)) {
                    relation.remove(pair);
                    changed = true;
                }
            }
        } while (changed);
        return relation;
    }

    private static boolean preCoveragePairIsValid(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference,
            ReferencePairState pair,
            Set<ReferencePairState> relation) {
        Map<String, List<TransitionGraph.Edge>> controllerActions =
                controllerByAction(controller, pair.controllerState);
        Map<String, List<PhaseAwareUpdateEnvironment.Transition>> referenceActions =
                referenceByAction(reference, pair.referenceState);
        if (!hasNonErrorTarget(controllerActions.get(UpdateConstants.BEGIN_UPDATE))
                || referenceActions.get(UpdateConstants.BEGIN_UPDATE) == null
                || referenceActions.get(UpdateConstants.BEGIN_UPDATE).isEmpty()) {
            return false;
        }
        for (Map.Entry<String, List<PhaseAwareUpdateEnvironment.Transition>> entry
                : referenceActions.entrySet()) {
            String action = entry.getKey();
            if (UpdateConstants.BEGIN_UPDATE.equals(action)) {
                continue;
            }
            List<TransitionGraph.Edge> controllerEdges = controllerActions.get(action);
            if (!hasNonErrorTarget(controllerEdges)) {
                return false;
            }
            for (PhaseAwareUpdateEnvironment.Transition referenceTransition
                    : entry.getValue()) {
                if (!hasRelatedControllerTarget(
                        controllerEdges, referenceTransition.target(), relation)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<ReferencePairState> greatestAlternatingSimulation(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference,
            Set<String> controllable) {
        Set<ReferencePairState> relation = candidatePairs(controller, reference, false);
        boolean changed;
        do {
            changed = false;
            for (ReferencePairState pair
                    : new HashSet<ReferencePairState>(relation)) {
                if (!alternatingPairIsValid(
                        controller, reference, controllable, pair, relation)) {
                    relation.remove(pair);
                    changed = true;
                }
            }
        } while (changed);
        return relation;
    }

    private static boolean alternatingPairIsValid(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference,
            Set<String> controllable,
            ReferencePairState pair,
            Set<ReferencePairState> relation) {
        Map<String, List<TransitionGraph.Edge>> controllerActions =
                controllerByAction(controller, pair.controllerState);
        Map<String, List<PhaseAwareUpdateEnvironment.Transition>> referenceActions =
                referenceByAction(reference, pair.referenceState);

        Set<String> actions = new HashSet<String>(controllerActions.keySet());
        actions.addAll(referenceActions.keySet());
        for (String action : actions) {
            List<TransitionGraph.Edge> controllerEdges = controllerActions.get(action);
            List<PhaseAwareUpdateEnvironment.Transition> referenceEdges =
                    referenceActions.get(action);
            if (containsErrorTarget(controllerEdges)) {
                return false;
            }
            boolean controllerHas = hasNonErrorTarget(controllerEdges);
            boolean referenceHas = referenceEdges != null && !referenceEdges.isEmpty();
            boolean controllerMayDisable = pair.referenceState.isPost()
                    && controllable.contains(action);
            if (referenceHas && !controllerHas && !controllerMayDisable) {
                return false;
            }
            if (controllerHas && !referenceHas) {
                return false;
            }
            if (!controllerHas || !referenceHas) {
                continue;
            }
            for (TransitionGraph.Edge controllerEdge : controllerEdges) {
                if (controllerEdge.to >= 0 && !hasRelatedReferenceTarget(
                        controllerEdge.to, referenceEdges, relation)) {
                    return false;
                }
            }
            for (PhaseAwareUpdateEnvironment.Transition referenceEdge : referenceEdges) {
                if (!hasRelatedControllerTarget(
                        controllerEdges, referenceEdge.target(), relation)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Set<ReferencePairState> candidatePairs(
            TransitionGraph controller,
            PhaseAwareUpdateEnvironment reference,
            boolean preOnly) {
        Set<ReferencePairState> result = new HashSet<ReferencePairState>();
        for (Integer controllerState : controller.reachableStates()) {
            for (PhaseAwareUpdateEnvironment.State referenceState : reference.states()) {
                if (!preOnly || referenceState.isPre()) {
                    result.add(new ReferencePairState(
                            controllerState.intValue(), referenceState));
                }
            }
        }
        return result;
    }

    private static boolean hasRelatedControllerTarget(
            List<TransitionGraph.Edge> controllerEdges,
            PhaseAwareUpdateEnvironment.State referenceTarget,
            Set<ReferencePairState> relation) {
        if (controllerEdges == null) {
            return false;
        }
        for (TransitionGraph.Edge edge : controllerEdges) {
            if (edge.to >= 0 && relation.contains(new ReferencePairState(
                    edge.to, referenceTarget))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRelatedReferenceTarget(
            int controllerTarget,
            List<PhaseAwareUpdateEnvironment.Transition> referenceEdges,
            Set<ReferencePairState> relation) {
        if (referenceEdges == null) {
            return false;
        }
        for (PhaseAwareUpdateEnvironment.Transition edge : referenceEdges) {
            if (relation.contains(new ReferencePairState(
                    controllerTarget, edge.target()))) {
                return true;
            }
        }
        return false;
    }

    private static String join(List<?> values, String separator) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }

    private static final class CycleWitness {
        final int state;
        final String action;

        CycleWitness(int state, String action) {
            this.state = state;
            this.action = action;
        }
    }

    public static final class Report {
        public final List<RequirementResult> results;

        Report(List<RequirementResult> results) {
            this.results = results;
        }

        public Status overallStatus() {
            boolean unknown = false;
            for (RequirementResult result : results) {
                if (result.status == Status.FAIL) {
                    return Status.FAIL;
                }
                if (result.status == Status.UNKNOWN) {
                    unknown = true;
                }
            }
            return unknown ? Status.UNKNOWN : Status.PASS;
        }

        public String toCsv() {
            StringBuilder builder = new StringBuilder();
            builder.append("requirement_id,requirement_name,status,detail,witness\n");
            for (RequirementResult result : results) {
                builder.append(csv(result.id)).append(',')
                        .append(csv(result.name)).append(',')
                        .append(result.status).append(',')
                        .append(csv(result.detail)).append(',')
                        .append(csv(result.witness)).append('\n');
            }
            builder.append(csv("OVERALL")).append(',')
                    .append(csv("all_requirements")).append(',')
                    .append(overallStatus()).append(',')
                    .append(csv("FAIL if any requirement fails; UNKNOWN if no failures but at least one unknown"))
                    .append(',')
                    .append(csv(""))
                    .append('\n');
            return builder.toString();
        }
    }

    public static final class RequirementResult {
        public final String id;
        public final String name;
        public final Status status;
        public final String detail;
        public final String witness;

        private RequirementResult(String id, String name, Status status, String detail, String witness) {
            this.id = id;
            this.name = name;
            this.status = status;
            this.detail = detail == null ? "" : detail;
            this.witness = witness == null ? "" : witness;
        }

        static RequirementResult pass(String id, String name, String detail) {
            return new RequirementResult(id, name, Status.PASS, detail, "");
        }

        static RequirementResult fail(String id, String name, String detail, String witness) {
            return new RequirementResult(id, name, Status.FAIL, detail, witness);
        }

        static RequirementResult unknown(String id, String name, String detail) {
            return new RequirementResult(id, name, Status.UNKNOWN, detail, "");
        }
    }

    public enum Status {
        PASS,
        FAIL,
        UNKNOWN
    }

    private static final class Monitor {
        final String name;
        final CompactState state;
        final Set<String> alphabet = new HashSet<String>();
        final Map<Integer, Map<String, List<Integer>>> transitions =
                new HashMap<Integer, Map<String, List<Integer>>>();

        Monitor(String name, CompactState state) {
            this.name = name;
            this.state = state;
            for (int i = 0; i < state.alphabet.length; i++) {
                alphabet.add(state.alphabet[i]);
            }
            for (int from = 0; from < state.maxStates; from++) {
                Map<String, List<Integer>> byAction = new HashMap<String, List<Integer>>();
                EventState eventState = state.states[from];
                if (eventState != null) {
                    java.util.Enumeration<?> enumeration = eventState.elements();
                    while (enumeration.hasMoreElements()) {
                        EventState edge = (EventState) enumeration.nextElement();
                        int event = edge.getEvent();
                        if (event >= 0 && event < state.alphabet.length) {
                            String action = state.alphabet[event];
                            List<Integer> next = byAction.get(action);
                            if (next == null) {
                                next = new ArrayList<Integer>();
                                byAction.put(action, next);
                            }
                            next.add(Integer.valueOf(edge.getNext()));
                        }
                    }
                }
                transitions.put(Integer.valueOf(from), byAction);
            }
        }

        MonitorViolation findViolation(TransitionGraph controller) {
            Queue<MonitorNode> queue = new ArrayDeque<MonitorNode>();
            Set<MonitorNodeKey> visited = new HashSet<MonitorNodeKey>();
            MonitorNode initial = new MonitorNode(0, 0, new ArrayList<String>());
            queue.add(initial);
            visited.add(initial.key());
            while (!queue.isEmpty()) {
                MonitorNode node = queue.remove();
                for (TransitionGraph.Edge edge : controller.getOutgoing(node.controllerState)) {
                    List<Integer> nextMonitorStates = step(node.monitorState, edge.action);
                    for (Integer nextMonitorState : nextMonitorStates) {
                        List<String> trace = new ArrayList<String>(node.trace);
                        trace.add(edge.action);
                        if (nextMonitorState.intValue() < 0) {
                            return new MonitorViolation(node.controllerState, edge.action, trace);
                        }
                        if (edge.to < 0) {
                            continue;
                        }
                        MonitorNode next = new MonitorNode(edge.to, nextMonitorState.intValue(), trace);
                        if (visited.add(next.key())) {
                            queue.add(next);
                        }
                    }
                }
            }
            return null;
        }

        private List<Integer> step(int monitorState, String action) {
            if (!alphabet.contains(action)) {
                return Arrays.asList(Integer.valueOf(monitorState));
            }
            Map<String, List<Integer>> byAction = transitions.get(Integer.valueOf(monitorState));
            if (byAction == null) {
                return Arrays.asList(Integer.valueOf(-1));
            }
            List<Integer> next = byAction.get(action);
            if (next == null || next.isEmpty()) {
                return Arrays.asList(Integer.valueOf(-1));
            }
            return next;
        }
    }

    private static final class MonitorNode {
        final int controllerState;
        final int monitorState;
        final List<String> trace;

        MonitorNode(int controllerState, int monitorState, List<String> trace) {
            this.controllerState = controllerState;
            this.monitorState = monitorState;
            this.trace = trace;
        }

        MonitorNodeKey key() {
            return new MonitorNodeKey(controllerState, monitorState);
        }
    }

    private static final class MonitorNodeKey {
        final int controllerState;
        final int monitorState;

        MonitorNodeKey(int controllerState, int monitorState) {
            this.controllerState = controllerState;
            this.monitorState = monitorState;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MonitorNodeKey)) {
                return false;
            }
            MonitorNodeKey that = (MonitorNodeKey) other;
            return controllerState == that.controllerState && monitorState == that.monitorState;
        }

        @Override
        public int hashCode() {
            return 31 * controllerState + monitorState;
        }
    }

    private static final class MonitorViolation {
        final int controllerState;
        final String action;
        final List<String> trace;

        MonitorViolation(int controllerState, String action, List<String> trace) {
            this.controllerState = controllerState;
            this.action = action;
            this.trace = trace;
        }
    }

    private static final class ReferencePairState {
        final int controllerState;
        final PhaseAwareUpdateEnvironment.State referenceState;

        ReferencePairState(
                int controllerState,
                PhaseAwareUpdateEnvironment.State referenceState) {
            this.controllerState = controllerState;
            this.referenceState = referenceState;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ReferencePairState)) {
                return false;
            }
            ReferencePairState that = (ReferencePairState) other;
            return controllerState == that.controllerState
                    && referenceState.equals(that.referenceState);
        }

        @Override
        public int hashCode() {
            return 31 * controllerState + referenceState.hashCode();
        }
    }

    private static final class SpecContext {
        final LTSOutput output;
        final UpdatingControllerCompositeState preparedUpdate;
        final List<Symbol> oldSafety;
        final List<Symbol> newSafety;
        final List<Symbol> transitionSafety;
        final Set<String> controllableActions;

        SpecContext(
                LTSOutput output,
                UpdatingControllerCompositeState preparedUpdate,
                List<Symbol> oldSafety,
                List<Symbol> newSafety,
                List<Symbol> transitionSafety,
                Set<String> controllableActions) {
            this.output = output;
            this.preparedUpdate = preparedUpdate;
            this.oldSafety = oldSafety;
            this.newSafety = newSafety;
            this.transitionSafety = transitionSafety;
            this.controllableActions = controllableActions;
        }

        static SpecContext load(File ltsFile, String target) throws IOException {
            String source = new String(Files.readAllBytes(ltsFile.toPath()), StandardCharsets.UTF_8);
            File parent = ltsFile.getAbsoluteFile().getParentFile();
            String currentDirectory = parent == null ? new File(".").getAbsolutePath() : parent.getAbsolutePath();
            LTSOutput output = new BufferingOutput();
            LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), output, currentDirectory);
            String updateName = findUpdateControllerName(source, target);
            compiler.compile();
            CompositionExpression expression = LTSCompiler.getComposite(updateName);
            if (!(expression instanceof UpdatingControllersDefinition)) {
                expression = LTSCompiler.getComposite(target);
            }
            if (!(expression instanceof UpdatingControllersDefinition)) {
                throw new IllegalArgumentException("Updating controller definition was not found for target: " + target);
            }
            UpdatingControllersDefinition definition = (UpdatingControllersDefinition) expression;
            ControllerGoalDefinition oldGoal = ControllerGoalDefinition.getDefinition(definition.getOldGoal());
            ControllerGoalDefinition newGoal = ControllerGoalDefinition.getDefinition(definition.getNewGoal());
            Set<String> controllable = definition.generateUpdatingControllableActions(oldGoal, newGoal);
            UpdatingControllerCompositeState prepared = null;
            CompositeState preparedComposite = compiler.continueCompilation(updateName);
            if (preparedComposite instanceof UpdatingControllerCompositeState) {
                prepared = (UpdatingControllerCompositeState) preparedComposite;
            }
            return new SpecContext(
                    output,
                    prepared,
                    new ArrayList<Symbol>(oldGoal.getSafetyDefinitions()),
                    new ArrayList<Symbol>(newGoal.getSafetyDefinitions()),
                    new ArrayList<Symbol>(definition.getTransitionGoals()),
                    controllable);
        }

        PhaseAwareUpdateEnvironment phaseAwareReference() {
            if (preparedUpdate == null
                    || preparedUpdate.isOTF()
                    || preparedUpdate.isFineGrained()
                    || preparedUpdate.getOldController() == null
                    || preparedUpdate.getStepwiseStages() == null
                    || preparedUpdate.getStepwiseStages().isEmpty()) {
                return null;
            }
            try {
                return PhaseAwareUpdateEnvironment.build(
                        preparedUpdate.getOldController(),
                        preparedUpdate.getStepwiseStages());
            } catch (RuntimeException failure) {
                return null;
            }
        }

        boolean supportsBulkProtocolChecks() {
            return preparedUpdate != null
                    && !preparedUpdate.isOTF()
                    && !preparedUpdate.isFineGrained();
        }

        private static String findUpdateControllerName(String source, String target) {
            Pattern pattern = Pattern.compile(String.format(TARGET_ALIAS_PATTERN.pattern(), Pattern.quote(target)));
            Matcher matcher = pattern.matcher(source);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return target;
        }
    }

    private static final class BufferingOutput implements LTSOutput {
        final StringBuilder builder = new StringBuilder();

        @Override
        public void out(String str) {
            builder.append(str == null ? "" : str);
        }

        @Override
        public void outln(String str) {
            builder.append(str == null ? "" : str).append('\n');
        }

        @Override
        public void clearOutput() {
            builder.setLength(0);
        }
    }

    public static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"') {
                builder.append("\"\"");
            } else {
                builder.append(ch);
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
