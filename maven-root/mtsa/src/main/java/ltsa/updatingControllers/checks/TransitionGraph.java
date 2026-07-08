package ltsa.updatingControllers.checks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ltsa.lts.CompactState;
import ltsa.lts.EventState;

public final class TransitionGraph {

    private static final Pattern PROCESS_PATTERN = Pattern.compile("(?m)^\\s*Process:\\s*\\R\\s*([^\\r\\n]+)");
    private static final Pattern STATES_PATTERN = Pattern.compile("(?m)^\\s*States:\\s*\\R\\s*(\\d+)");
    private static final Pattern EDGE_PATTERN = Pattern.compile("([^|(),\\r\\n]+?)\\s*->\\s*(Q\\d+|ERROR)");
    private static final Pattern GROUP_EDGE_PATTERN = Pattern.compile("\\{([^}]*)}\\s*->\\s*(Q\\d+|ERROR)");

    private final String name;
    private final int stateCount;
    private final Map<Integer, List<Edge>> outgoing;
    private final Set<String> alphabet;

    private TransitionGraph(String name, int stateCount, Map<Integer, List<Edge>> outgoing, Set<String> alphabet) {
        this.name = name;
        this.stateCount = stateCount;
        this.outgoing = outgoing;
        this.alphabet = alphabet;
    }

    public static TransitionGraph fromPrintTransitionsFile(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return fromPrintTransitionsText(text, file.getName());
    }

    public static TransitionGraph fromCompactState(CompactState state) {
        Map<Integer, List<Edge>> outgoing = new LinkedHashMap<Integer, List<Edge>>();
        Set<String> alphabet = new HashSet<String>();
        int stateCount = Math.max(0, state.maxStates);
        for (int from = 0; from < stateCount; from++) {
            List<Edge> edges = new ArrayList<Edge>();
            EventState eventState = state.states[from];
            if (eventState != null) {
                java.util.Enumeration<?> enumeration = eventState.elements();
                while (enumeration.hasMoreElements()) {
                    EventState edge = (EventState) enumeration.nextElement();
                    int event = edge.getEvent();
                    if (event >= 0 && event < state.alphabet.length) {
                        String action = state.alphabet[event];
                        int to = edge.getNext();
                        edges.add(new Edge(from, action, to));
                        alphabet.add(action);
                    }
                }
            }
            outgoing.put(Integer.valueOf(from), edges);
        }
        return new TransitionGraph(state.name, stateCount, outgoing, alphabet);
    }

    public static TransitionGraph fromPrintTransitionsText(String text, String fallbackName) {
        String name = fallbackName;
        Matcher processMatcher = PROCESS_PATTERN.matcher(text);
        if (processMatcher.find()) {
            name = processMatcher.group(1).trim();
        }

        int stateCount = -1;
        Matcher statesMatcher = STATES_PATTERN.matcher(text);
        if (statesMatcher.find()) {
            stateCount = Integer.parseInt(statesMatcher.group(1));
        }

        Map<Integer, List<Edge>> outgoing = new LinkedHashMap<Integer, List<Edge>>();
        Set<String> alphabet = new HashSet<String>();
        int maxState = -1;
        int transitionsIndex = text.indexOf("Transitions:");
        String transitionText = transitionsIndex >= 0 ? text.substring(transitionsIndex) : text;
        String[] chunks = transitionText.split("(?m)^\\s*Q");
        for (String chunk : chunks) {
            if (chunk.length() == 0) {
                continue;
            }
            int index = 0;
            while (index < chunk.length() && Character.isDigit(chunk.charAt(index))) {
                index++;
            }
            if (index == 0) {
                continue;
            }
            int from = Integer.parseInt(chunk.substring(0, index));
            maxState = Math.max(maxState, from);
            List<Edge> edges = outgoing.get(Integer.valueOf(from));
            if (edges == null) {
                edges = new ArrayList<Edge>();
                outgoing.put(Integer.valueOf(from), edges);
            }
            String body = chunk.substring(index);
            parseGroupedEdges(from, body, edges, alphabet);
            parseSimpleEdges(from, body, edges, alphabet);
            for (Edge edge : edges) {
                if (edge.to >= 0) {
                    maxState = Math.max(maxState, edge.to);
                }
            }
        }

        if (stateCount < 0) {
            stateCount = maxState + 1;
        }
        for (int i = 0; i < stateCount; i++) {
            if (!outgoing.containsKey(Integer.valueOf(i))) {
                outgoing.put(Integer.valueOf(i), new ArrayList<Edge>());
            }
        }
        return new TransitionGraph(name, stateCount, outgoing, alphabet);
    }

    private static void parseGroupedEdges(
            int from,
            String body,
            List<Edge> edges,
            Set<String> alphabet) {
        Matcher matcher = GROUP_EDGE_PATTERN.matcher(body);
        while (matcher.find()) {
            int to = parseTarget(matcher.group(2));
            String[] actions = matcher.group(1).split(",");
            for (int i = 0; i < actions.length; i++) {
                String action = cleanAction(actions[i]);
                if (action.length() > 0) {
                    edges.add(new Edge(from, action, to));
                    alphabet.add(action);
                }
            }
        }
    }

    private static void parseSimpleEdges(
            int from,
            String body,
            List<Edge> edges,
            Set<String> alphabet) {
        String noGroups = body.replaceAll("\\{[^}]*}\\s*->\\s*(Q\\d+|ERROR)", "");
        Matcher matcher = EDGE_PATTERN.matcher(noGroups);
        while (matcher.find()) {
            String action = cleanAction(matcher.group(1));
            if (action.length() == 0 || action.indexOf(" ") >= 0 || action.indexOf("=") >= 0) {
                continue;
            }
            int to = parseTarget(matcher.group(2));
            edges.add(new Edge(from, action, to));
            alphabet.add(action);
        }
    }

    private static String cleanAction(String raw) {
        String action = raw == null ? "" : raw.trim();
        while (action.startsWith("|")) {
            action = action.substring(1).trim();
        }
        while (action.startsWith("(")) {
            action = action.substring(1).trim();
        }
        while (action.endsWith(",")) {
            action = action.substring(0, action.length() - 1).trim();
        }
        return action;
    }

    private static int parseTarget(String target) {
        if ("ERROR".equals(target)) {
            return -1;
        }
        return Integer.parseInt(target.substring(1));
    }

    public String getName() {
        return name;
    }

    public int getStateCount() {
        return stateCount;
    }

    public Set<String> getAlphabet() {
        return Collections.unmodifiableSet(alphabet);
    }

    public List<Edge> getOutgoing(int state) {
        List<Edge> edges = outgoing.get(Integer.valueOf(state));
        return edges == null ? Collections.<Edge>emptyList() : edges;
    }

    public boolean hasOutgoingAction(int state, String action) {
        for (Edge edge : getOutgoing(state)) {
            if (edge.action.equals(action)) {
                return true;
            }
        }
        return false;
    }

    public Set<Integer> successors(int state, String action) {
        Set<Integer> result = new HashSet<Integer>();
        for (Edge edge : getOutgoing(state)) {
            if (edge.action.equals(action) && edge.to >= 0) {
                result.add(Integer.valueOf(edge.to));
            }
        }
        return result;
    }

    public Set<Integer> reachableStates() {
        Set<Integer> reached = new HashSet<Integer>();
        Queue<Integer> queue = new ArrayDeque<Integer>();
        reached.add(Integer.valueOf(0));
        queue.add(Integer.valueOf(0));
        while (!queue.isEmpty()) {
            int state = queue.remove().intValue();
            for (Edge edge : getOutgoing(state)) {
                if (edge.to >= 0 && reached.add(Integer.valueOf(edge.to))) {
                    queue.add(Integer.valueOf(edge.to));
                }
            }
        }
        return reached;
    }

    public Map<String, Integer> countActions(Collection<String> actions) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (String action : actions) {
            counts.put(action, Integer.valueOf(0));
        }
        for (List<Edge> edges : outgoing.values()) {
            for (Edge edge : edges) {
                Integer count = counts.get(edge.action);
                if (count != null) {
                    counts.put(edge.action, Integer.valueOf(count.intValue() + 1));
                }
            }
        }
        return counts;
    }

    public static final class Edge {
        public final int from;
        public final String action;
        public final int to;

        public Edge(int from, String action, int to) {
            this.from = from;
            this.action = action;
            this.to = to;
        }

        @Override
        public String toString() {
            return "Q" + from + " -" + action + "-> " + (to < 0 ? "ERROR" : "Q" + to);
        }
    }
}
