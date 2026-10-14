package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit addition-only scenario, with old states named by action traces and
 * new states named by aliases. This does not extract a difference between FSPs.
 * All traces are resolved in the old game, including its losing states, before
 * the complete batch is applied. Compiled numeric state IDs are not input data.
 */
public final class DifferentialScenario {
    private final Set<String> controllableActions;
    private final Map<String, StateDeclaration> states;
    private final List<EdgeDeclaration> transitions;

    private DifferentialScenario(Set<String> controllableActions,
            Map<String, StateDeclaration> states, List<EdgeDeclaration> transitions) {
        this.controllableActions = Collections.unmodifiableSet(new LinkedHashSet<>(controllableActions));
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        this.transitions = Collections.unmodifiableList(new ArrayList<>(transitions));
    }

    /** Load one strict scenario document; relative paths are resolved by the caller. */
    public static DifferentialScenario load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object document = new Yaml(new StrictSafeConstructor()).load(reader);
            return parse(document);
        } catch (YAMLException | IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid differential scenario " + file + ": "
                    + failure.getMessage(), failure);
        }
    }

    public Set<String> getControllableActions() {
        return controllableActions;
    }

    /**
     * Resolve against exactly the analysis used for the subsequent update.
     * New IDs increase from the greatest old ID, skipping the FSP ERROR ID -1.
     * Exhausting the Long range is rejected rather than wrapping or reusing IDs.
     */
    public SafetyGameDelta<Long, String> resolve(Analysis<Long, String> base) {
        Objects.requireNonNull(base, "base");
        if (!controllableActions.equals(base.getControllableActions())) {
            throw new IllegalArgumentException("Scenario controllableActions differ from the base analysis.");
        }
        Map<String, Long> resolved = new LinkedHashMap<>();
        SafetyGameDelta.Builder<Long, String> builder = SafetyGameDelta.forAnalysis(base);
        long greatest = Collections.max(base.getStates());
        for (Map.Entry<String, StateDeclaration> entry : states.entrySet()) {
            String alias = entry.getKey();
            StateDeclaration declaration = entry.getValue();
            Long state;
            if (declaration.isNew) {
                try {
                    greatest = Math.addExact(greatest, 1L);
                    if (greatest == -1L) {
                        greatest = Math.addExact(greatest, 1L);
                    }
                } catch (ArithmeticException exhausted) {
                    throw new IllegalArgumentException("Cannot allocate a new state for alias '" + alias
                            + "': the state ID range is exhausted at Long.MAX_VALUE.", exhausted);
                }
                state = greatest;
                if (declaration.unsafe) {
                    builder.addUnsafeState(state);
                } else {
                    builder.addState(state);
                }
            } else {
                state = base.getInitialState();
                for (int step = 0; step < declaration.trace.size(); step++) {
                    String action = declaration.trace.get(step);
                    Long target = base.getTransitions(state).get(action);
                    if (target == null) {
                        throw new IllegalArgumentException("State alias '" + alias + "': trace step "
                                + (step + 1) + " has no transition for action '" + action + "'.");
                    }
                    state = target;
                }
            }
            resolved.put(alias, state);
        }
        for (EdgeDeclaration edge : transitions) {
            Long source = resolved.get(edge.from);
            if (states.get(edge.from).unsafe || base.getUnsafeStates().contains(source)) {
                throw new IllegalArgumentException("Cannot add a transition from unsafe alias '" + edge.from + "'.");
            }
            builder.addTransition(source, edge.action, resolved.get(edge.to));
        }
        return builder.build();
    }

    private static DifferentialScenario parse(Object document) {
        Map<String, Object> root = mapping(document, "scenario");
        onlyKeys(root, "scenario", "controllableActions", "states", "transitions");
        List<String> controls = strings(required(root, "controllableActions", "scenario"), "controllableActions");
        if (controls.contains("tau")) {
            throw new IllegalArgumentException("The internal action tau must be uncontrollable.");
        }
        Map<String, Object> declarations = mapping(required(root, "states", "scenario"), "states");
        Map<String, StateDeclaration> states = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : declarations.entrySet()) {
            String alias = nonblank(entry.getKey(), "state alias");
            String location = "states." + alias;
            Map<String, Object> definition = mapping(entry.getValue(), location);
            onlyKeys(definition, location, "trace", "new", "unsafe");
            boolean hasTrace = definition.containsKey("trace");
            boolean hasNew = definition.containsKey("new");
            if (hasTrace == hasNew) {
                throw new IllegalArgumentException(location + " must specify exactly one of trace or new: true.");
            }
            if (hasTrace) {
                if (definition.containsKey("unsafe")) {
                    throw new IllegalArgumentException(location + ".unsafe is allowed only for a new state.");
                }
                states.put(alias, new StateDeclaration(false, false,
                        strings(definition.get("trace"), location + ".trace")));
            } else {
                if (!bool(definition.get("new"), location + ".new")) {
                    throw new IllegalArgumentException(location + ".new must be true.");
                }
                boolean unsafe = definition.containsKey("unsafe")
                        && bool(definition.get("unsafe"), location + ".unsafe");
                states.put(alias, new StateDeclaration(true, unsafe, Collections.emptyList()));
            }
        }
        List<?> rawEdges = sequence(required(root, "transitions", "scenario"), "transitions");
        List<EdgeDeclaration> transitions = new ArrayList<>();
        for (int index = 0; index < rawEdges.size(); index++) {
            String location = "transitions[" + index + "]";
            Map<String, Object> edge = mapping(rawEdges.get(index), location);
            onlyKeys(edge, location, "from", "action", "to");
            String from = nonblank(required(edge, "from", location), location + ".from");
            String action = nonblank(required(edge, "action", location), location + ".action");
            String to = nonblank(required(edge, "to", location), location + ".to");
            if (!states.containsKey(from)) {
                throw new IllegalArgumentException(location + ".from references undefined alias '" + from + "'.");
            }
            if (!states.containsKey(to)) {
                throw new IllegalArgumentException(location + ".to references undefined alias '" + to + "'.");
            }
            transitions.add(new EdgeDeclaration(from, action, to));
        }
        return new DifferentialScenario(new LinkedHashSet<>(controls), states, transitions);
    }

    private static Object required(Map<String, Object> values, String key, String location) {
        if (!values.containsKey(key)) {
            throw new IllegalArgumentException(location + "." + key + " is required.");
        }
        return values.get(key);
    }

    private static void onlyKeys(Map<String, Object> values, String location, String... keys) {
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(keys));
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("Unknown key " + location + "." + key + ".");
            }
        }
    }

    private static Map<String, Object> mapping(Object value, String location) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(location + " must be a mapping.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String key = nonblank(entry.getKey(), location + " key");
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<?> sequence(Object value, String location) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(location + " must be a list.");
        }
        return (List<?>) value;
    }

    private static List<String> strings(Object value, String location) {
        List<?> source = sequence(value, location);
        List<String> result = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            result.add(nonblank(source.get(index), location + "[" + index + "]"));
        }
        return result;
    }

    private static String nonblank(Object value, String location) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(location + " must be a nonempty string.");
        }
        return (String) value;
    }

    private static boolean bool(Object value, String location) {
        if (!(value instanceof Boolean)) {
            throw new IllegalArgumentException(location + " must be a boolean.");
        }
        return (Boolean) value;
    }

    /** SnakeYAML 1.17 otherwise silently overwrites duplicate mapping keys. */
    private static final class StrictSafeConstructor extends SafeConstructor {
        @Override
        protected void constructMapping2ndStep(MappingNode node, Map<Object, Object> mapping) {
            Set<String> keys = new LinkedHashSet<>();
            for (NodeTuple tuple : node.getValue()) {
                Node keyNode = tuple.getKeyNode();
                if (!(keyNode instanceof ScalarNode) || !Tag.STR.equals(keyNode.getTag())) {
                    throw new YAMLException("Mapping keys must be strings; merge keys are not supported.");
                }
                String key = ((ScalarNode) keyNode).getValue();
                if (!keys.add(key)) {
                    throw new YAMLException("Duplicate mapping key '" + key + "' at line "
                            + (keyNode.getStartMark().getLine() + 1) + ".");
                }
            }
            super.constructMapping2ndStep(node, mapping);
        }
    }

    private static final class StateDeclaration {
        final boolean isNew;
        final boolean unsafe;
        final List<String> trace;

        StateDeclaration(boolean isNew, boolean unsafe, List<String> trace) {
            this.isNew = isNew;
            this.unsafe = unsafe;
            this.trace = Collections.unmodifiableList(new ArrayList<>(trace));
        }
    }

    private static final class EdgeDeclaration {
        final String from;
        final String action;
        final String to;

        EdgeDeclaration(String from, String action, String to) {
            this.from = from;
            this.action = action;
            this.to = to;
        }
    }
}
