package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.lts.CompactState;
import org.javatuples.Triplet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Vector;

public class SelfLoopRemoval {

    public static Vector<CompactState> selfLoopRemoval(Vector<CompactState> machines) {

        Vector<CompactState> newMachines = new Vector<CompactState>();
        Set<String> selfLoopOnlyLabels = new HashSet<>();

        HashMap<Integer, HashSet<Triplet<Long, String, Long>>> deletedTransitionsForEachMachine = new HashMap<>();
        HashMap<Integer, Set<String>> labelsWithoutTransitionsForEachMachine = new HashMap<>();

        HashSet<Triplet<Long, String, Long>> deletedTransitions = new HashSet<>();
        Set<String> labelsWithoutTransitions = new HashSet<>();

        for(CompactState envCompact : machines ) {
            selfLoopOnlyLabels.addAll(envCompact.getAlphabetV());
        }

        int machineId = 0;
        for(CompactState envCompact : machines ) {

            MTS<Long, String> env = AutomataToMTSConverter.getInstance().convert(envCompact);
            deletedTransitions = new HashSet<>();
            labelsWithoutTransitions = new HashSet<>();
            labelsWithoutTransitions.addAll(env.getActions());
            for (Long state : env.getStates()) {
                for (Pair<String, Long> transition : env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    String transitionLabel = transition.getFirst();
                    Long stateDst = transition.getSecond();
                    boolean isLoopTransition = state.equals(stateDst);
                    labelsWithoutTransitions.remove(transitionLabel);

                    if (isLoopTransition) {
                        Triplet<Long, String, Long> transitionToDelete = new Triplet<Long, String, Long>(state, transitionLabel, stateDst);
                        deletedTransitions.add(transitionToDelete);
                    } else {
                        selfLoopOnlyLabels.remove(transitionLabel);
                    }
                }
            }
            deletedTransitionsForEachMachine.put(machineId, deletedTransitions);
            labelsWithoutTransitionsForEachMachine.put(machineId, labelsWithoutTransitions);
            machineId++;
        }
        if(!selfLoopOnlyLabels.isEmpty()){
            machineId = 0;
            for(CompactState envCompact : machines ){
                MTS<Long, String> env = AutomataToMTSConverter.getInstance().convert(envCompact);
                selfLoopOnlyLabels.remove("tau");
                for(Triplet<Long,String,Long> transitionToDelete : deletedTransitionsForEachMachine.get(machineId)){
                    if(selfLoopOnlyLabels.contains(transitionToDelete.getValue1()))
                        env.removeRequired(transitionToDelete.getValue0(), transitionToDelete.getValue1(),transitionToDelete.getValue2());
                }
                Utils.removeUnusedActions(env, labelsWithoutTransitionsForEachMachine.get(machineId));

                newMachines.add(MTSToAutomataConverter.getInstance().convert(env, envCompact.getName(), true));
                machineId++;
            }
        }else{
            return machines;
        }


        return newMachines;
    }
}
