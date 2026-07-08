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
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ltsa.control.ControllerGoalDefinition;
import ltsa.dispatcher.TransitionSystemDispatcher;
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

public final class UpdateRequirementChecker {

    private static final Pattern TARGET_ALIAS_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\|\\s*%s\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\.");

    private UpdateRequirementChecker() {
    }

    public static Report check(File transitionsFile, File ltsFile, String target) throws IOException {
        TransitionGraph controller = TransitionGraph.fromPrintTransitionsFile(transitionsFile);
        SpecContext spec = SpecContext.load(ltsFile, target);

        List<RequirementResult> results = new ArrayList<RequirementResult>();
        results.add(checkHotSwapEnabled(controller));
        results.add(checkSafetyMonitors("R2", "old_goal_until_stopOldSpec",
                compileOldSafetyMonitors(spec), controller));
        results.add(checkSafetyMonitors("R3", "transition_requirement",
                compileTransitionMonitors(spec), controller));
        results.add(checkSafetyMonitors("R4", "new_goal_after_startNewSpec",
                compileNewSafetyMonitors(spec), controller));
        results.add(checkEventually(controller, "R5", "eventually_stopOldSpec_after_hotSwapIn",
                UpdateConstants.STOP_OLD_SPEC));
        results.add(checkEventually(controller, "R6", "eventually_reconfigure_after_hotSwapIn",
                UpdateConstants.RECONFIGURE));
        results.add(checkEventually(controller, "R7", "eventually_startNewSpec_after_hotSwapIn",
                UpdateConstants.START_NEW_SPEC));
        results.add(checkDeadlockFree(controller));
        results.add(checkLegality(controller, spec));

        return new Report(results);
    }

    private static RequirementResult checkHotSwapEnabled(TransitionGraph graph) {
        Set<Integer> preStates = reachableWithoutAction(graph, UpdateConstants.BEGIN_UPDATE);
        List<String> missing = new ArrayList<String>();
        for (Integer state : preStates) {
            if (!graph.hasOutgoingAction(state.intValue(), UpdateConstants.BEGIN_UPDATE)) {
                missing.add("Q" + state);
            }
        }
        if (missing.isEmpty()) {
            return RequirementResult.pass("R1", "hotSwapIn_enabled_in_all_pre_update_states",
                    "pre-update states=" + preStates.size());
        }
        return RequirementResult.fail("R1", "hotSwapIn_enabled_in_all_pre_update_states",
                "missing states=" + join(missing, " "), "");
    }

    private static Set<Integer> reachableWithoutAction(TransitionGraph graph, String blockedAction) {
        Set<Integer> reached = new HashSet<Integer>();
        Queue<Integer> queue = new ArrayDeque<Integer>();
        reached.add(Integer.valueOf(0));
        queue.add(Integer.valueOf(0));
        while (!queue.isEmpty()) {
            int state = queue.remove().intValue();
            for (TransitionGraph.Edge edge : graph.getOutgoing(state)) {
                if (blockedAction.equals(edge.action) || edge.to < 0) {
                    continue;
                }
                if (reached.add(Integer.valueOf(edge.to))) {
                    queue.add(Integer.valueOf(edge.to));
                }
            }
        }
        return reached;
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
                if (targetAction.equals(edge.action) || edge.to < 0) {
                    continue;
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

    private static RequirementResult checkLegality(TransitionGraph controller, SpecContext spec) {
        if (spec.controllableActions == null || spec.controllableActions.isEmpty()) {
            return RequirementResult.unknown("R9", "legality",
                    "controllable action set was not available");
        }
        TransitionGraph environment = spec.compileMappingEnvironment();
        if (environment == null) {
            return RequirementResult.unknown("R9", "legality",
                    "mapping environment was not available");
        }

        Set<String> controllable = new HashSet<String>(spec.controllableActions);
        controllable.remove(UpdateConstants.BEGIN_UPDATE);
        controllable.remove(UpdateConstants.STOP_OLD_SPEC);
        controllable.remove(UpdateConstants.START_NEW_SPEC);

        Set<String> relevantEnvironmentActions = new HashSet<String>(environment.getAlphabet());
        relevantEnvironmentActions.remove(UpdateConstants.BEGIN_UPDATE);
        relevantEnvironmentActions.remove(UpdateConstants.STOP_OLD_SPEC);
        relevantEnvironmentActions.remove(UpdateConstants.START_NEW_SPEC);

        Queue<PairState> queue = new ArrayDeque<PairState>();
        Set<PairState> visited = new HashSet<PairState>();
        PairState initial = new PairState(0, 0);
        queue.add(initial);
        visited.add(initial);
        while (!queue.isEmpty()) {
            PairState pair = queue.remove();
            Set<String> controllerEnabled = enabled(controller, pair.left);
            Set<String> environmentEnabled = enabled(environment, pair.right);

            for (String action : relevantEnvironmentActions) {
                boolean controllerHas = controllerEnabled.contains(action);
                boolean environmentHas = environmentEnabled.contains(action);
                if (!controllable.contains(action) && environmentHas && !controllerHas) {
                    return RequirementResult.fail("R9", "legality",
                            "controller disables uncontrollable action enabled by environment: " + action
                                    + " at Q" + pair.left + "/E" + pair.right,
                            action);
                }
            }

            Set<String> productActions = new HashSet<String>(controllerEnabled);
            productActions.addAll(environmentEnabled);
            for (String action : productActions) {
                if (UpdateConstants.BEGIN_UPDATE.equals(action)
                        || UpdateConstants.STOP_OLD_SPEC.equals(action)
                        || UpdateConstants.START_NEW_SPEC.equals(action)) {
                    enqueueControllerOnly(controller, queue, visited, pair, action);
                } else if (environment.getAlphabet().contains(action)) {
                    enqueueSynchronized(controller, environment, queue, visited, pair, action);
                } else {
                    enqueueControllerOnly(controller, queue, visited, pair, action);
                }
            }
        }
        return RequirementResult.pass("R9", "legality",
                "reachable product states=" + visited.size());
    }

    private static Set<String> enabled(TransitionGraph graph, int state) {
        Set<String> result = new HashSet<String>();
        for (TransitionGraph.Edge edge : graph.getOutgoing(state)) {
            result.add(edge.action);
        }
        return result;
    }

    private static void enqueueControllerOnly(
            TransitionGraph controller,
            Queue<PairState> queue,
            Set<PairState> visited,
            PairState pair,
            String action) {
        for (TransitionGraph.Edge edge : controller.getOutgoing(pair.left)) {
            if (action.equals(edge.action) && edge.to >= 0) {
                PairState next = new PairState(edge.to, pair.right);
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
    }

    private static void enqueueSynchronized(
            TransitionGraph controller,
            TransitionGraph environment,
            Queue<PairState> queue,
            Set<PairState> visited,
            PairState pair,
            String action) {
        for (TransitionGraph.Edge left : controller.getOutgoing(pair.left)) {
            if (!action.equals(left.action) || left.to < 0) {
                continue;
            }
            for (TransitionGraph.Edge right : environment.getOutgoing(pair.right)) {
                if (action.equals(right.action) && right.to >= 0) {
                    PairState next = new PairState(left.to, right.to);
                    if (visited.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }
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

    private static final class PairState {
        final int left;
        final int right;

        PairState(int left, int right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PairState)) {
                return false;
            }
            PairState that = (PairState) other;
            return left == that.left && right == that.right;
        }

        @Override
        public int hashCode() {
            return 31 * left + right;
        }
    }

    private static final class SpecContext {
        final File ltsFile;
        final String target;
        final String source;
        final String currentDirectory;
        final LTSOutput output;
        final UpdatingControllersDefinition updateDefinition;
        final List<Symbol> oldSafety;
        final List<Symbol> newSafety;
        final List<Symbol> transitionSafety;
        final Set<String> controllableActions;

        SpecContext(
                File ltsFile,
                String target,
                String source,
                String currentDirectory,
                LTSOutput output,
                UpdatingControllersDefinition updateDefinition,
                List<Symbol> oldSafety,
                List<Symbol> newSafety,
                List<Symbol> transitionSafety,
                Set<String> controllableActions) {
            this.ltsFile = ltsFile;
            this.target = target;
            this.source = source;
            this.currentDirectory = currentDirectory;
            this.output = output;
            this.updateDefinition = updateDefinition;
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
            Hashtable<String, CompositionExpression> composites = new Hashtable<String, CompositionExpression>();
            Hashtable processes = new Hashtable();
            Hashtable explorers = new Hashtable();
            compiler.parse(composites, processes, explorers);

            String updateName = findUpdateControllerName(source, target);
            CompositionExpression expression = composites.get(updateName);
            if (!(expression instanceof UpdatingControllersDefinition)) {
                expression = composites.get(target);
            }
            if (!(expression instanceof UpdatingControllersDefinition)) {
                throw new IllegalArgumentException("Updating controller definition was not found for target: " + target);
            }
            UpdatingControllersDefinition definition = (UpdatingControllersDefinition) expression;
            ControllerGoalDefinition oldGoal = ControllerGoalDefinition.getDefinition(definition.getOldGoal());
            ControllerGoalDefinition newGoal = ControllerGoalDefinition.getDefinition(definition.getNewGoal());
            Set<String> controllable = definition.generateUpdatingControllableActions(oldGoal, newGoal);
            return new SpecContext(
                    ltsFile,
                    target,
                    source,
                    currentDirectory,
                    output,
                    definition,
                    new ArrayList<Symbol>(oldGoal.getSafetyDefinitions()),
                    new ArrayList<Symbol>(newGoal.getSafetyDefinitions()),
                    new ArrayList<Symbol>(definition.getTransitionGoals()),
                    controllable);
        }

        TransitionGraph compileMappingEnvironment() {
            List<String> candidates = new ArrayList<String>();
            candidates.add("MAPPING_ENV");
            candidates.add("MapEnvironment");
            candidates.add("MAPPING_ENVIRONMENT");
            if (updateDefinition.getMapping() != null
                    && !"unknown".equals(updateDefinition.getMapping().toString())) {
                candidates.add(updateDefinition.getMapping().toString());
            }
            for (String candidate : candidates) {
                try {
                    CompactState state = compileComposite(candidate);
                    if (state != null) {
                        return TransitionGraph.fromCompactState(state);
                    }
                } catch (Throwable ignored) {
                    // Try the next conventional mapping-environment name.
                }
            }
            return null;
        }

        private CompactState compileComposite(String name) throws Exception {
            LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), output, currentDirectory);
            compiler.compile();
            CompositeState current = compiler.continueCompilation(name);
            if (current == null) {
                return null;
            }
            TransitionSystemDispatcher.applyComposition(current, output);
            return current.composition;
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
