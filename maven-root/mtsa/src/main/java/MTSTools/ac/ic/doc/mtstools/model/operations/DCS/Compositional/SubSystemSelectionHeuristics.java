package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import ltsa.lts.CompactState;

import java.util.*;
import static org.junit.Assert.*;


public class SubSystemSelectionHeuristics {
    /** returns < machinesToCompose, <alphabetOfMachines, localAlphabetOfMachines> > */
    static Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> selectSubSystemWithHeuristic(Vector<CompactState> machines,
                                                                                                         CandidateSelectionHeuristic candidateSelection,
                                                                                                         CompositionOrderHeuristic compositionOrder, ControllerGoal<String> originalGoal){

        Set<Set<CompactState>> candidates = candidateSelection.select(machines,originalGoal);

        Set<CompactState> minCandidate = compositionOrder.select(candidates,machines,originalGoal);

        assertTrue("Candidates should be a machines selection", new HashSet<>(machines).containsAll(minCandidate));

        return postprocessSelectedCandidate(minCandidate,machines);

    }

    public static Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> postprocessSelectedCandidate(Set<CompactState> minCandidate,Vector<CompactState> machines) {
        // now i have to extract localLabels from minStatesOfCandidate
        Set<String> sharedAlphabet = new HashSet<>();
        Set<String> localAlphabet = new HashSet<>();

        for(CompactState machine : minCandidate){
            //sharedAlphabet.addAll(getLabelsOfActiveTransitions(AutomataToMTSConverter.getInstance().convert(machine)));
            //localAlphabet.addAll(getLabelsOfActiveTransitions(AutomataToMTSConverter.getInstance().convert(machine)));
            sharedAlphabet.addAll(machine.getAlphabetV());
            localAlphabet.addAll(machine.getAlphabetV());
        }

        for(CompactState machine : machines){
            if(!minCandidate.contains(machine)){
                machine.getAlphabetV().forEach(localAlphabet::remove);
            }
        }

        Vector<String> sharedAlphabetV = new Vector<>(sharedAlphabet);
        Vector<String> localAlphabetV = new Vector<>(localAlphabet);

        return new Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>>(new Vector<>(minCandidate), new Pair(sharedAlphabet, localAlphabet));
    }

}


class HeuristicsFunctions {
    public static Set<CompactState> getAutomatasWithMostStates(Vector<CompactState> machines){

        Set<CompactState> result = new HashSet<CompactState>();
        int maxStates = -1;
        for(CompactState machine : machines){
            if(machine.maxStates>maxStates){
                result.clear();
                result.add(machine);
                maxStates = machine.maxStates;
            }else if(machine.maxStates==maxStates){
                result.add(machine);
            }
        }
        return result;
    }


    public static Set<CompactState> getAutomatasWithLessStates(Vector<CompactState> machines) {
        Set<CompactState> result = new HashSet<CompactState>();
        int maxStates = -1;
        for(CompactState machine : machines){
            if(machine.maxStates<maxStates || maxStates==-1){
                result.clear();
                result.add(machine);
                maxStates = machine.maxStates;
            }else if(machine.maxStates==maxStates){
                result.add(machine);
            }
        }
        return result;
    }

    public static int getStatesOfCompositionCandidate(Set<CompactState> candidate) {
        int minStatesOfCandidate = 1;
        for(CompactState automata : candidate){
            minStatesOfCandidate = minStatesOfCandidate * automata.maxStates;
        }
        return minStatesOfCandidate;
    }

    public static long getLocalEventsNumberOfCompositionCandidate(Set<CompactState> candidates,
                                                                   Vector<CompactState> machines) {
        // now i have to extract localLabels from minStatesOfCandidate
        Set<String> totalAlphabet = new HashSet<>();
        Set<String> sharedAlphabet = new HashSet<>();
        Set<String> localAlphabet = new HashSet<>();


        for(CompactState machine : candidates){
            Vector<String> machineAlphabet = machine.getAlphabetV();
            //Set<String> machineAlphabet = getLabelsOfActiveTransitions(AutomataToMTSConverter.getInstance().convert(machine));
            sharedAlphabet.addAll(machineAlphabet);
            localAlphabet.addAll(machineAlphabet);
        }

        for(CompactState machine : machines){
            if(!candidates.contains(machine)){
                Vector<String> machineAlphabet = machine.getAlphabetV();
                //Set<String> machineAlphabet = getLabelsOfActiveTransitions(AutomataToMTSConverter.getInstance().convert(machine));
                machineAlphabet.forEach(localAlphabet::remove);
            }
        }
        return localAlphabet.size();
    }

    public static double getProportionOfCommonEvents(Set<CompactState> candidates) {
        // Get the union of all alphabets, then get those symbols that are shared by more than one candidate (shared events). Divide the number of shared events by the number of total events
        Hashtable<String,Long> totalAlphabet = new Hashtable<>();
        for(CompactState candidate : candidates) {
            for (String symbol : candidate.getAlphabet()) {
                totalAlphabet.put(symbol, totalAlphabet.getOrDefault(symbol, 0L)+1L);
            }
        }
        totalAlphabet.replaceAll((key, value) -> value > 1L ? 1L : 0L);
        long commonEvents = totalAlphabet.values().stream().mapToLong(Long::longValue).sum();
        return (double) commonEvents / (double) totalAlphabet.size();
    }
//
//
//
//    private static Set<String> getLabelsOfActiveTransitions(MTS<Long, String> env) {
//
//        Set<String> labels = new HashSet<String>();
//        Set<Long> toConvertStates = env.getStates();
//
//        for(Long state : toConvertStates){
//            BinaryRelation<String, Long> transitions = env.getTransitions(state, MTS.TransitionType.REQUIRED);
//            for(Pair<String, Long> transition : transitions){
//                labels.add(transition.getFirst());
//            }
//        }
//
//        return labels;
//    }
}