package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import com.fasterxml.jackson.core.JsonProcessingException;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSOutput;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DefaultWeightedEdge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;

import java.util.*;

public class Utils {

    public static boolean isControllableThroughTranslations(
            String label,
            Vector<HashMap<String, String>> totalTranslator,
            Map<String, String> translatorControllable) {

        String current = label;

        // Seguimos traduciendo mientras haya traducción disponible
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map<String, String> translator : totalTranslator) {
                if (translator.containsKey(current)) {
                    current = translator.get(current);
                    changed = true;
                    break; // volvemos a empezar desde el primer traductor
                }
            }
        }

        // Cuando ya no hay más traducciones, vemos si la forma final es controlable
        return translatorControllable.values().contains(current);
    }

    public static void selfLoopRemoval(Vector<CompactState> machines){

        for(CompactState machine : machines){
            Set<String> localLabelsOfMachine = getLocalLabelsOfMachineWRT(machine, machines);
            MTS<Long, String> lts = AutomataToMTSConverter.getInstance().convert(machine);
            Set<Long> states = lts.getStates();
            for(Long state : states){
                BinaryRelation<String, Long> transitions
                        = lts.getTransitions(state, MTS.TransitionType.REQUIRED);
                Iterator<Pair<String, Long>> transitionsIt = transitions.iterator();
                while(transitionsIt.hasNext()){
                    Pair<String, Long> transition = transitionsIt.next();
                    String label = transition.getFirst();
                    Long dst = transition.getSecond();
                    if(state == dst && localLabelsOfMachine.contains(label)){
                        transitionsIt.remove();
                    }
                }
            }
        }
    }

    private static Set<String> getLocalLabelsOfMachineWRT(CompactState machine, Vector<CompactState> machines) {
        Set<String> totalAlphabet = new HashSet<>(List.of(machine.getAlphabet()));
        Set<String> symbolsToRemove = new HashSet<>();
        for (String symbol : totalAlphabet) {
            for(CompactState anotherMachine : machines){
                if(anotherMachine.equals(machine)) continue;
                if(anotherMachine.getAlphabetV().contains(symbol)){
                    symbolsToRemove.add(symbol);
                };
            }
        }
        totalAlphabet.removeAll(symbolsToRemove);
        return totalAlphabet;
    }

    /**
     * Returns the set of final translated actions reachable from the original label.
     * Optimized with precomputed reverse maps for O(1) lookups.
     */
    public static Set<String> translateFromOriginal(
            String label,
            Vector<HashMap<String, String>> translator) {

        // Precompute reverse maps: value -> list of keys
        List<Map<String, Set<String>>> reverseMaps = new ArrayList<>();
        for (HashMap<String, String> dict : translator) {
            Map<String, Set<String>> reverseMap = new HashMap<>();
            for (Map.Entry<String, String> e : dict.entrySet()) {
                reverseMap
                        .computeIfAbsent(e.getValue(), k -> new HashSet<>())
                        .add(e.getKey());
            }
            reverseMaps.add(reverseMap);
        }

        Set<String> currentLabels = new HashSet<>(Collections.singleton(label));
        boolean changed;

        do {
            changed = false;
            Set<String> nextLabels = new HashSet<>();

            for (String lbl : currentLabels) {
                boolean hasTranslation = false;
                for (Map<String, Set<String>> reverseMap : reverseMaps) {
                    Set<String> translated = reverseMap.get(lbl);
                    if (translated != null && !translated.isEmpty()) {
                        nextLabels.addAll(translated);
                        hasTranslation = true;
                    }
                }
                if (!hasTranslation) {
                    nextLabels.add(lbl);
                } else {
                    changed = true;
                }
            }

            currentLabels = nextLabels;
        } while (changed);

        return currentLabels;
    }

    public static Set<String> translateFromOriginalSet(Set<String> labels, Vector<HashMap<String, String>> translator) {
        Set<String> translatedSet = new HashSet<String>();
        for (String label : labels) {
            translatedSet.addAll(translateFromOriginal(label, translator));
        }
        return translatedSet;
    }

    public static Set<String> translateFromOriginalSet(Vector<String> labels, Vector<HashMap<String, String>> translator) {
        Set<String> translatedSet = new HashSet<String>();
        for (String label : labels) {
            translatedSet.addAll(translateFromOriginal(label, translator));
        }
        return translatedSet;
    }

    private static Set<String> getTranslationsForLabel(String label, HashMap<String, String> translate) {
        Set<String> translations = new HashSet<String>();
        for (Map.Entry<String, String> eachTranslation : translate.entrySet()) {
            if (eachTranslation.getValue().equals(label)) {
                translations.add(eachTranslation.getKey());
            }
        }
        return translations;
    }

    public static boolean checkCorrectGoal(ControllerGoal<String> goal) {
        if (!(goal.getGuarantees().isEmpty() ^ goal.getMarking().isEmpty())) {
            System.out.print("Marking or liveness requirements (exactly one of them) are required for heuristic analysis.");
            return true;
        }
        if (!goal.getMarking().isEmpty() && goal.getAssumptions().size() > 1) {
            System.out.print(("Multiple assumptions are not supported by the heuristic analysis when using marking as goals."));
            return true;
        }
        return false;
    }


    /**
     * @param minimisedEnv
     * @param ignoreActions this method deletes from env the actions that are not being used, except ignoreActions parameter
     */
    public static void removeUnusedActions(MTS<Long, String> minimisedEnv, Set<String> ignoreActions) {

        Set<Long> ltsStates = minimisedEnv.getStates();
        Set<String> ltsActions = minimisedEnv.getActions();
        Set<String> removeActions = new HashSet<>(ltsActions);

        for (Long state : ltsStates) {
            BinaryRelation<String, Long> transitions = minimisedEnv.getTransitions(state, MTS.TransitionType.REQUIRED);
            for (Pair<String, Long> transition : transitions) {
                removeActions.remove(transition.getFirst());
            }
        }
        removeActions.removeAll(ignoreActions);
        for (String action : removeActions) {
            minimisedEnv.removeAction(action);
        }
    }

    public static Integer countTransitions(MTS<Long, String> lts) {

        Integer result = 0;
        for (Long state : lts.getStates()) {
            BinaryRelation<String, Long> transitions = lts.getTransitions(state, MTS.TransitionType.REQUIRED);
            result += transitions.size();
        }
        return result;
    }

    public static Set<String> symbolToString(Set<Symbol> actions) {

        Set<String> result = new HashSet<>();
        for (Symbol initAction : actions) {
            result.add(initAction.toString());
        }
        return result;
    }

    public static Set<Symbol> stringToSymbol(Set<String> actions) {

        Set<Symbol> result = new HashSet<>();
        for (String action : actions) {
            result.add(new SingleSymbol(action));
        }
        return result;
    }

    public static CompositeState getCompositeState(Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> machinesToCompose, LTSOutput output) {
        CompositeState compState = new CompositeState();
        compState.setMachines(machinesToCompose.getFirst());
        compState.setComponentAlphabet(machinesToCompose.getSecond().getFirst());
        compState.compose(output);
        return compState;
    }
    public class LabeledEdge extends DefaultEdge {
        private String label;
        public LabeledEdge(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return "(" + getSource() + " : " + getTarget() + " : " + label + ")";
        }
    }

    public static org.jgrapht.graph.DirectedPseudograph<Long, LabeledEdge> MTSToLTSGraphRestricted(MTS<Long, String> lts,Set<String> availableActions) {
        org.jgrapht.graph.DirectedPseudograph<Long, Utils.LabeledEdge> grafo = new org.jgrapht.graph.DirectedPseudograph<>(Utils.LabeledEdge.class);
        Set<Long> states = lts.getStates();
            for (long state : lts.getStates()){
            grafo.addVertex(state);
        }
            for(Long state : states){
            BinaryRelation<String, Long> transitions
                    = lts.getTransitions(state, MTS.TransitionType.REQUIRED);
            for(Pair<String, Long> transition : transitions){
                if (availableActions.contains(transition.getFirst())){
                    String label = transition.getFirst();
                    Long dst = transition.getSecond();
                    grafo.addEdge(state,dst,new Utils().new LabeledEdge(label));
                }
            }
        }
        return grafo;
    }

    public org.jgrapht.Graph<Long, LabeledEdge> MTSToLTSGraph(MTS<Long, String> lts) {
        org.jgrapht.Graph<Long, LabeledEdge> grafo = new org.jgrapht.graph.DirectedPseudograph<>(LabeledEdge.class);
        Set<Long> states = lts.getStates();
        for (long state : lts.getStates()){
            grafo.addVertex(state);
        }
        for(Long state : states){
            BinaryRelation<String, Long> transitions
                    = lts.getTransitions(state, MTS.TransitionType.REQUIRED);
            for(Pair<String, Long> transition : transitions){
                String label = transition.getFirst();
                Long dst = transition.getSecond();
                grafo.addEdge(state,dst,new LabeledEdge(label));
            }
        }
        return grafo;
    }

    public org.jgrapht.Graph<Long, LabeledEdge> compactStateToLTSGraph(CompactState machine) {
        MTS<Long, String> lts = AutomataToMTSConverter.getInstance().convert(machine);
        return MTSToLTSGraph(lts);
    }

    public Map<CompactState,org.jgrapht.Graph<Long, LabeledEdge>> compactStatesToLTSGraph(Collection<CompactState> machines) {
        Map<CompactState,org.jgrapht.Graph<Long,LabeledEdge>> result = new HashMap<>();
        for (CompactState machine : machines) {
            result.put(machine,compactStateToLTSGraph(machine));
        }
        return result;
    }



    public enum PlantSensers {
        LocalAlphabetSenser {
            String sensePlantArchitecture(CompositeState compositeState) {
                Vector<CompactState> machines = compositeState.getMachines();
                Set<String> transitionsInGoal = FormulaUtils.getActivationEventsGoal(compositeState.goal);
                org.jgrapht.Graph<String,DefaultWeightedEdge> graph = new org.jgrapht.graph.DirectedWeightedPseudograph<>(DefaultWeightedEdge.class);

                // Compare each machine with every other machine
                for (CompactState machine : machines) {
                    graph.addVertex(machine.getName());
                }
                for (int i = 0; i < machines.size(); i++) {
                    for (int j = 0; j < machines.size(); j++) {
                        Set<String> commonAlphabets = new HashSet<>(machines.get(i).getAlphabetV());
                        commonAlphabets.removeAll(transitionsInGoal);
                        commonAlphabets.retainAll(machines.get(j).getAlphabetV());
                        commonAlphabets.remove("tau");
                        commonAlphabets.removeIf(s -> s.endsWith("?"));
                        for (int k = 0; k < machines.size(); k++) {
                            if (k != j && k != i) {
                                machines.get(k).getAlphabetV().forEach(commonAlphabets::remove);
                            }
                        }
                        if (!commonAlphabets.isEmpty()) {
                            DefaultWeightedEdge t = graph.addEdge(machines.get(i).getName(), machines.get(j).getName());
                            graph.setEdgeWeight(t,commonAlphabets.size());
                        }
                    }
                }
                return printGraph(graph);
            }
        },
        MatchedAlphabetSenser {
            String sensePlantArchitecture(CompositeState compositeState) {
                Vector<CompactState> machines = compositeState.getMachines();
                org.jgrapht.Graph<String,DefaultWeightedEdge> graph = new org.jgrapht.graph.DirectedWeightedPseudograph<>(DefaultWeightedEdge.class);
                for (CompactState machine : machines) {
                    graph.addVertex(machine.getName());
                }
                // Compare each machine with every other machine
                for (int i = 0; i < machines.size(); i++) {
                    for (CompactState machine : machines) {
                        Set<String> commonAlphabets = new HashSet<>(machines.get(i).getAlphabetV());
                        commonAlphabets.retainAll(machine.getAlphabetV());
                        commonAlphabets.remove("tau");
                        commonAlphabets.removeIf(s -> s.endsWith("?"));
                        if (!commonAlphabets.isEmpty()) {
                            DefaultWeightedEdge t = graph.addEdge(machines.get(i).getName(), machine.getName());
                            graph.setEdgeWeight(t,commonAlphabets.size());
                        }
                    }
                }
                return printGraph(graph);
            }

        },
        FullExplicitRepresentation {
            String sensePlantArchitecture(CompositeState compositeState) {
                org.jgrapht.Graph<String,DefaultWeightedEdge> graph = new org.jgrapht.graph.DirectedWeightedPseudograph<>(DefaultWeightedEdge.class);
                Set<String> symbols = new HashSet<>();
                for (CompactState machine : compositeState.getMachines()) {
                    graph.addVertex(machine.getName());
                    symbols.addAll(Arrays.asList(machine.getAlphabet()));
                }
                for (String symbol : symbols ) {
                    graph.addVertex(symbol);
                }

                for (CompactState machine : compositeState.getMachines()) {
                    for (String symbol : machine.getAlphabet()) {
                        DefaultWeightedEdge t = graph.addEdge(machine.getName(),symbol);
                        graph.setEdgeWeight(t,0);
                    }
                }
                return printGraph(graph);
            }

        },

        LTSResume {
            class LTSDescription{
                private String name;
                private int n_states;
                private int n_transitions;
                private String[] alphabet;
                private boolean isGoal = false;
                private Map<String,Long> transitions_for_action = new HashMap<>();


                public LTSDescription(CompactState machine){
                    MTS<Long, String> lts = AutomataToMTSConverter.getInstance().convert(machine);
                    n_states = lts.getStates().size();
                    n_transitions = lts.getNumberOfTransitions();
                    name = machine.getName();
                    alphabet = machine.getAlphabet();
                    for (Long state : lts.getStates()){
                        BinaryRelation<String, Long> transitions
                                = lts.getTransitions(state, MTS.TransitionType.REQUIRED);
                        for (Pair<String,Long> transition : transitions) {
                            long value = transitions_for_action.getOrDefault(transition.getFirst(),0L)+1;
                            transitions_for_action.put(transition.getFirst(),value);
                        }
                    }
                }

                public LTSDescription(ControllerGoal<String> goal) {
                    FormulaUtils.getActivationEventsGoal(goal).forEach(e -> transitions_for_action.put(e,1L));
                    name = "Goal";
                    isGoal = true;
                }
            }
            String sensePlantArchitecture(CompositeState compositeState) {
                Vector<LTSDescription> res = new Vector<>();
                compositeState.getMachines().forEach(machine -> res.add(new LTSDescription(machine)));
                res.add(new LTSDescription(compositeState.goal));

                ObjectMapper mapper = new ObjectMapper();
                mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
                try {
                    return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(res);
                } catch (JsonProcessingException e) {
                    return null;
                }
            }
        };

        abstract String sensePlantArchitecture(CompositeState compositeState);
        public static String sensePlantArchitecture(String senser, CompositeState compositeState){
            StringBuilder result = new StringBuilder(valueOf(senser).sensePlantArchitecture(compositeState));
            result.append("\nMachines [");
            for (CompactState machine : compositeState.getMachines()) {
                result.append('\"');result.append(machine.getName());result.append('\"');result.append(',');
            }
            result.append("]\n");
            result.append("\nInFormula [");
            for (Formula formula : compositeState.goal.getGuarantees()) {
                for (String event : FormulaUtils.getActivationDeactivationEvents(formula).getValue0()){
                    result.append('\"');result.append(event);result.append('\"');result.append(',');
                }
            }
            for (Formula formula : compositeState.goal.getAssumptions()) {
                for (String event : FormulaUtils.getActivationDeactivationEvents(formula).getValue0()){
                    result.append('\"');result.append(event);result.append('\"');result.append(',');
                }
            }
            result.append("]\n");
            return result.toString();
        }
            /**
             * Returns a String representation of the graph
             */
        public static String printGraph(org.jgrapht.Graph<String, DefaultWeightedEdge> graph) {
            StringBuilder sb = new StringBuilder();

            boolean directed = graph.getType().isDirected();

            for (String node : graph.vertexSet()) {
                sb.append("Node ").append(node).append(" -> [");

                // Para dirigidos: sólo salientes; para no dirigidos: todas las que tocan a node
                var edges = directed
                        ? graph.outgoingEdgesOf(node)
                        : graph.edgesOf(node);

                for (DefaultWeightedEdge edge : edges) {
                    String target;
                    if (directed) {
                        // en un grafo dirigido, el destino es getEdgeTarget
                        target = graph.getEdgeTarget(edge);
                    } else {
                        // en no dirigido, obtenemos el otro extremo
                        target = org.jgrapht.Graphs.getOppositeVertex(graph, edge, node);
                    }
                    double weight = graph.getEdgeWeight(edge);
                    sb.append("{\"target\":\"")
                            .append(target)
                            .append("\", \"weight\":")
                            .append(weight)
                            .append("},");
                }

                sb.append("]\n");
            }

            return sb.toString();
        }

    }
}
