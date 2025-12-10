package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.lts.CompactState;

import java.util.*;

import static org.junit.Assert.assertTrue;
import org.jgrapht.Graph;
import org.jgrapht.alg.StoerWagnerMinimumCut;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.WeightedPseudograph;

public enum CandidateSelectionHeuristic {

    /**
     * minT. Candidates are all automata pairs containing the automaton with the
     * fewest transitions.
     **/
    MinT {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<Set<CompactState>> candidates = new HashSet<Set<CompactState>>();
            for (CompactState fstCandidate : HeuristicsFunctions.getAutomatasWithLessStates(machines)) {
                for (CompactState sndCandidate : machines) {
                    if (!fstCandidate.equals(sndCandidate)) {
                        Set<CompactState> actualPair = new HashSet<>();
                        actualPair.add(fstCandidate);
                        actualPair.add(sndCandidate);
                        candidates.add(actualPair);
                    }
                }
            }
            return candidates;
        }
    },

    /**
     * maxS. Candidates are all automata pairs containing the automaton with the most states.
     **/
    MaxS {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<Set<CompactState>> candidates = new HashSet<Set<CompactState>>();
            for (CompactState fstCandidate : HeuristicsFunctions.getAutomatasWithMostStates(machines)) {
                for (CompactState sndCandidate : machines) {
                    if (!fstCandidate.equals(sndCandidate)) {
                        Set<CompactState> actualPair = new HashSet<>();
                        actualPair.add(fstCandidate);
                        actualPair.add(sndCandidate);
                        candidates.add(actualPair);
                    }
                }
            }
            return candidates;
        }
    },

    /**
     * mustL. For each event there is a candidate, which is the set of automata using that particular event.
     **/
    MustL {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            HashMap<String, Set<CompactState>> automatasWithLabel = new HashMap<>();
            for (CompactState machine : machines) {
                for (String label : machine.getAlphabetV()) {
                    if (!label.equals("tau") && !label.equals("tau?"))
                        automatasWithLabel.computeIfAbsent(label, k -> new HashSet<>()).add(machine);
                }
            }
            automatasWithLabel.entrySet().removeIf(value -> value.getValue().size() < 2);
            return new HashSet<>(automatasWithLabel.values());
        }
    },

    /**
     * For each event there is a candidate, which is the set of automata using that particular event. Only considers events that enables a fluent in the goal.
     **/
    MustLGoal {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<Set<CompactState>> automatasWithLabel = new HashSet<>();
            Set<String> formulaEvents = FormulaUtils.getActivationEventsGoal(originalGoal);
            for (String event : formulaEvents) {
                Set<CompactState> current = new HashSet<>();
                for (CompactState machine : machines) {
                    if (machine.getAlphabetV().contains(event)) {
                        current.add(machine);
                    }
                }
                automatasWithLabel.add(current);
            }
            return automatasWithLabel;
        }
    },

    /**
     * Uses MinT, MaxS, MustL in that order, uses the first one capable of distinguishing a top candidate
     **/
    @DontCombine
    FlordalMalik {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<CompactState> set = HeuristicsFunctions.getAutomatasWithLessStates(machines);
            if (set.size() < 2) {
                return MinT.select(machines, originalGoal);
            }
            set = HeuristicsFunctions.getAutomatasWithMostStates(machines);
            if (set.size() < 2) {
                return MaxS.select(machines, originalGoal);
            }
            return MustL.select(machines, originalGoal);

        }
    },

    /**
     * Builds the graph of LTSs based on the alphabet that is local to a pair of LTS (two LTS are connected by a vertex V with value N if they share N symbols of their respective alphabets and no other LTS has those symbols in their alphabets). Then it takes as candidates for composition the connected components of the builded graph. Each connected component is a candidate.
     **/
    @DontCombine
    CommonAlphabetCluster {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Graph<CompactState, DefaultWeightedEdge> g = buildCommonAlphabetClusterGraph(machines);
            return new HashSet<>((new ConnectivityInspector<>(g)).connectedSets());
        }
        Graph<CompactState, DefaultWeightedEdge> buildCommonAlphabetClusterGraph(Vector<CompactState> machines) {
            Graph<CompactState, DefaultWeightedEdge> graph = new WeightedPseudograph<>(DefaultWeightedEdge.class);

            // Compare each machine with every other machine
            for (CompactState machine : machines) {
                graph.addVertex(machine);
            }
            for (int i = 0; i < machines.size(); i++) {
                for (int j = 0; j < machines.size(); j++) {
                    Set<String> commonAlphabets = new HashSet<>(machines.get(i).getAlphabetV());
                    commonAlphabets.retainAll(machines.get(j).getAlphabetV());
                    commonAlphabets.remove("tau");
                    commonAlphabets.removeIf(s -> s.endsWith("?"));
                    for (int k = 0; k < machines.size(); k++) {
                        if (k != j && k != i) {
                            machines.get(k).getAlphabetV().forEach(commonAlphabets::remove);
                        }
                    }
                    if (!commonAlphabets.isEmpty()) {
                        DefaultWeightedEdge t = graph.addEdge(machines.get(i), machines.get(j));
                        graph.setEdgeWeight(t, commonAlphabets.size());
                    }
                }
            }
            return graph;
        }
    },

    /**
     * Builds the graph of LTSs based on the alphabet shared (two LTS are connected by a vertex V with value N if they share N symbols of their respective alphabets) and starts taking minimum cut recursively until MinCutMatchedAlphabetStopCutCriteria succeeds on selecting candidates for composition or all the partitions have only one LTS.
     **/
    @DontCombine
    MinCutMatchedAlphabet {
        /**
         * The maximum number of states in the estimated size of the composition that can a set have to be considered as a possible candidate
         **/
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Graph<CompactState,DefaultWeightedEdge> graph = new WeightedPseudograph<>(DefaultWeightedEdge.class);
            // Compare each machine with every other machine
            for (CompactState machine : machines) {
                graph.addVertex(machine);
            }
            for (int i = 0; i < machines.size(); i++) {
                for (CompactState machine : machines) {
                    Set<String> commonAlphabets = new HashSet<>(machines.get(i).getAlphabetV());
                    commonAlphabets.retainAll(machine.getAlphabetV());
                    commonAlphabets.remove("tau");
                    commonAlphabets.removeIf(s -> s.endsWith("?"));
                    if (!commonAlphabets.isEmpty() && machines.get(i) != machine) {
                        DefaultWeightedEdge t = graph.addEdge(machines.get(i), machine);
                        graph.setEdgeWeight(t, commonAlphabets.size());
                    }
                }
            }
            Map<CompactState,Graph<Long,Utils.LabeledEdge>> ltsMap = new Utils().compactStatesToLTSGraph(machines);
            Set<Set<CompactState>> cuts = new HashSet<>();
            Set<CompactState> cut = new StoerWagnerMinimumCut<>(graph).minCut();
            Set<CompactState> otherSide = new HashSet<>(machines);
            otherSide.removeAll(cut);
            cuts.add(cut);cuts.add(otherSide);
            while (true){
                Set<Set<CompactState>> result = MinCutMatchedAlphabetStopCutCriteria(cuts,ltsMap);
                if (result != null) {
                    return result;
                }
                Set<Set<CompactState>> currentCuts = new HashSet<>();
                for (Set<CompactState> previousCut : cuts) {
                    if (previousCut.size() == 1){
                        currentCuts.add(previousCut);
                    } else {
                        Graph<CompactState, DefaultWeightedEdge> cutSubgraph = new AsSubgraph<>(graph, previousCut);
                        cut = new StoerWagnerMinimumCut<>(cutSubgraph).minCut();
                        otherSide = new HashSet<>(previousCut);
                        otherSide.removeAll(cut);
                        currentCuts.add(otherSide);currentCuts.add(cut);
                    }
                }
                cuts = currentCuts;
            }
        }

        /**
         * Decides weather to stop doing recursive cuts based on the size of the local alphabet of LTS. If it finds at least one set inside the biggest set for which LA * B + LB * A < threshold, then it returns a set containing the sets matching that condition. otherwise returns null. X is the number of states in LTS X and LX is the number of states having a local outgoing transition (considering local alphabet relative to the rest of automatas in the set). If all sets inside candidates have size one, returns candidates to avoid infinite looping in MinCutMatchedAlphabet. Since it is a pessimistic approach, it will try to overestimate the number of states in the composition of each set (at least the non-linear part of the composition), it will return at most the product of the number of states of all LTS. Notice that right now it only implements LA * B < threshold for simplicity reasons.
         **/
        public Set<Set<CompactState>> MinCutMatchedAlphabetStopCutCriteria(Set<Set<CompactState>> candidates,Map<CompactState,Graph<Long,Utils.LabeledEdge>> ltsMap) {
            if (candidates.stream().map(Set::size).allMatch(e -> e == 1)){
                return candidates;
            }
            Set<Set<CompactState>> result = new HashSet<>();
            for (Set<CompactState> candidate : candidates) {
                long estimation = 1;
                Vector<String> usedAlphabet = new Vector<>();
                for (CompactState machine : candidate) {
                    Graph<Long,Utils.LabeledEdge> lts = ltsMap.get(machine);
                    Set<String> localAlphabet = new HashSet<>(Set.of(machine.getAlphabet()));
                    usedAlphabet.forEach(localAlphabet::remove); // Local alphabet taken as the alphabet of the LTS - the already used alphabet
                    Set<Long> involvedNodes = new HashSet<>(); // Nodes that have an outgoing transition in local alphabet
                    for (Utils.LabeledEdge e : lts.edgeSet()) {
                        if (localAlphabet.contains(e.getLabel())) {
                            involvedNodes.add(lts.getEdgeSource(e));
                        }
                    }
                    estimation *= involvedNodes.size();
                    usedAlphabet.addAll(localAlphabet);
                }
                if (estimation < number_of_states_threshold) {
                    result.add(candidate);
                }
            }
            return result.isEmpty()?null:result;
        }

    },

    @DontCombine
    MinCutMatchedAlphabetCartesian {
        /**
         * The maximum number of states in the estimated size of the composition that can a set have to be considered as a possible candidate
         **/
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Graph<CompactState,DefaultWeightedEdge> graph = new WeightedPseudograph<>(DefaultWeightedEdge.class);
            // Compare each machine with every other machine
            for (CompactState machine : machines) {
                graph.addVertex(machine);
            }
            for (int i = 0; i < machines.size(); i++) {
                for (CompactState machine : machines) {
                    Set<String> commonAlphabets = new HashSet<>(machines.get(i).getAlphabetV());
                    commonAlphabets.retainAll(machine.getAlphabetV());
                    commonAlphabets.remove("tau");
                    commonAlphabets.removeIf(s -> s.endsWith("?"));
                    if (!commonAlphabets.isEmpty() && machines.get(i) != machine) {
                        DefaultWeightedEdge t = graph.addEdge(machines.get(i), machine);
                        graph.setEdgeWeight(t, commonAlphabets.size());
                    }
                }
            }
            Map<CompactState,Graph<Long,Utils.LabeledEdge>> ltsMap = new Utils().compactStatesToLTSGraph(machines);
            Set<Set<CompactState>> cuts = new HashSet<>();
            Set<CompactState> cut = new StoerWagnerMinimumCut<>(graph).minCut();
            Set<CompactState> otherSide = new HashSet<>(machines);
            otherSide.removeAll(cut);
            cuts.add(cut);cuts.add(otherSide);
            while (true){
                Set<Set<CompactState>> result = MinCutMatchedAlphabetStopCutCriteriaCartesian(cuts,ltsMap);
                if (result != null) {
                    return result;
                }
                Set<Set<CompactState>> currentCuts = new HashSet<>();
                for (Set<CompactState> previousCut : cuts) {
                    if (previousCut.size() == 1){
                        currentCuts.add(previousCut);
                    } else {
                        Graph<CompactState, DefaultWeightedEdge> cutSubgraph = new AsSubgraph<>(graph, previousCut);
                        cut = new StoerWagnerMinimumCut<>(cutSubgraph).minCut();
                        otherSide = new HashSet<>(previousCut);
                        otherSide.removeAll(cut);
                        currentCuts.add(otherSide);currentCuts.add(cut);
                    }
                }
                cuts = currentCuts;
            }
        }

        /**
         * Decides weather to stop doing recursive cuts based on the size of the local alphabet of LTS. If it finds at least one set inside the biggest set for which LA * B + LB * A < threshold, then it returns a set containing the sets matching that condition. otherwise returns null. X is the number of states in LTS X and LX is the number of states having a local outgoing transition (considering local alphabet relative to the rest of automatas in the set). If all sets inside candidates have size one, returns candidates to avoid infinite looping in MinCutMatchedAlphabet. Since it is a pessimistic approach, it will try to overestimate the number of states in the composition of each set (at least the non-linear part of the composition), it will return at most the product of the number of states of all LTS. Notice that right now it only implements LA * B < threshold for simplicity reasons.
         **/
        public Set<Set<CompactState>> MinCutMatchedAlphabetStopCutCriteriaCartesian(Set<Set<CompactState>> candidates,Map<CompactState,Graph<Long,Utils.LabeledEdge>> ltsMap) {
            if (candidates.stream().map(Set::size).allMatch(e -> e == 1)) {
                return candidates;
            }

            Set<Set<CompactState>> result = new HashSet<>();
            for (Set<CompactState> candidate : candidates) {
                long estimation = 1;
                for (CompactState machine : candidate) {
                    estimation *= ltsMap.get(machine).vertexSet().size();
                }
                if (estimation < number_of_states_threshold) {
                    result.add(candidate);
                }
            }
            return result.isEmpty()?null:result;
        }
    },

    /**
     * Take two by two
     **/
    @DontCombine
    Simple {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<Set<CompactState>> result = new HashSet<>();
            if (machines.size() >= 2) {
                Set<CompactState> subset = new HashSet<>();
                subset.add(machines.get(0));
                subset.add(machines.get(1));
                result.add(subset);
            } else {
                return null;
            }

            return result;

        }
    },

    /**
     * random. It does what you think it does, returns 10 randomly selected subsets. It might break completeness of the algorithm
     **/
    @DontCombine
    RandomSelection {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            int n = 10;
            Random r = new Random();
            Set<Set<CompactState>> result = new HashSet<>();
            for (int i = 0; i < n; i++) {
                int m = 2 + r.nextInt(machines.size() - 1);
                Collections.shuffle(machines);
                HashSet<CompactState> selection = new HashSet<>(machines.subList(0, m));
                result.add(selection);
                assertTrue("Random selection must return candidates with size >1", selection.size() > 1);
            }
            return result;
        }
    },

    /** Does monolithic, consider that it is not exactly equivalent to running MTSA in monolithic mode since compositional also minimizes the result **/
    @DontCombine
    Mono {
        public Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal) {
            Set<Set<CompactState>> result = new HashSet<>();
            HashSet<CompactState> selection = new HashSet<>(machines.subList(0, machines.size()));
            result.add(selection);
            return result;
        }
    };

    public abstract Set<Set<CompactState>> select(Vector<CompactState> machines, ControllerGoal<String> originalGoal);
    public final long number_of_states_threshold = 1_500_000;

}
