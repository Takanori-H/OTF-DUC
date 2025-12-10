package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import ltsa.lts.CompactState;

import java.util.Set;
import java.util.Vector;

public class OutputUtils {

    public static void printIterationNumber(int k) {
        /* Statistics */
        System.out.print("--------" + "\n");
        System.out.print(k + " composition:\n");
    }

    public static void printMachinesInformation(Pair<Vector<CompactState>, Pair<Set<String>, Set<String>>> subsys, Statistics statistics) {
        for(CompactState machine : subsys.getFirst()){
            System.out.print(machine.getName() + " - " + machine.maxStates + "\n");
            statistics.registerMachineStates(machine.maxStates);
        }
    }
}
