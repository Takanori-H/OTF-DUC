package ltsa.updatingControllers.structures;

import ltsa.lts.Diagnostics;
import ltsa.updatingControllers.UpdateConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SelectiveUpdateProtocolSpecBuilder {

    private SelectiveUpdateProtocolSpecBuilder() {
    }

    static UpdateProtocolSpec build(UpdateProtocolSpec candidates, Set<String> referencedUpdateActions) {
        UpdateProtocolSpec spec = new UpdateProtocolSpec();
        spec.markSelective();
        Set<String> references = referencedUpdateActions == null
                ? Collections.<String>emptySet()
                : referencedUpdateActions;

        assignStopActions(candidates, spec, references);
        assignReconfigureActions(candidates, spec, references);
        assignStartActions(candidates, spec, references);
        return spec;
    }

    private static void assignStopActions(
            UpdateProtocolSpec candidates,
            UpdateProtocolSpec spec,
            Set<String> references) {
        boolean othersRequested = references.contains(UpdateConstants.STOP_OLD_SPEC_OTHERS);
        Set<String> referenced = intersection(candidates.getStopOldSpecActions(), references);
        Map<String, String> safetyToAction = candidates.getOldSafetyToStopAction();
        if (safetyToAction.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("stopOldSpec_others is referenced, but there are no old safety properties to group.");
            }
            return;
        }
        if (referenced.isEmpty() && !othersRequested) {
            for (String safety : safetyToAction.keySet()) {
                spec.registerStopOldSpec(safety, UpdateConstants.STOP_OLD_SPEC);
            }
            return;
        }

        Set<String> othersSafeties = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : safetyToAction.entrySet()) {
            String safety = entry.getKey();
            String candidateAction = entry.getValue();
            if (referenced.contains(candidateAction)) {
                spec.registerStopOldSpec(safety, candidateAction);
            } else {
                othersSafeties.add(safety);
            }
        }
        if (othersSafeties.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("stopOldSpec_others is referenced, but all old safety properties are already individually referenced.");
            }
            return;
        }
        for (String safety : othersSafeties) {
            spec.registerStopOldSpec(safety, UpdateConstants.STOP_OLD_SPEC_OTHERS);
        }
    }

    private static void assignReconfigureActions(
            UpdateProtocolSpec candidates,
            UpdateProtocolSpec spec,
            Set<String> references) {
        boolean othersRequested = references.contains(UpdateConstants.RECONFIGURE_OTHERS);
        Set<String> referenced = intersection(candidates.getReconfigureActions(), references);
        Map<Integer, String> mappingIndexToAction = mappingActionsByIndex(candidates);
        if (mappingIndexToAction.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("reconfigure_others is referenced, but there are no mapping components to group.");
            }
            return;
        }
        if (referenced.isEmpty() && !othersRequested) {
            for (Integer mappingIndex : sortedIndices(mappingIndexToAction.keySet())) {
                String candidateAction = mappingIndexToAction.get(mappingIndex);
                spec.registerReconfigure(mappingIndex, UpdateConstants.RECONFIGURE);
                spec.recordCandidateReconfigureAction(mappingIndex, candidateAction);
            }
            return;
        }

        Set<Integer> othersMappings = new LinkedHashSet<Integer>();
        for (Integer mappingIndex : sortedIndices(mappingIndexToAction.keySet())) {
            String candidateAction = mappingIndexToAction.get(mappingIndex);
            if (referenced.contains(candidateAction)) {
                spec.registerReconfigure(mappingIndex, candidateAction);
            } else {
                othersMappings.add(mappingIndex);
            }
            spec.recordCandidateReconfigureAction(mappingIndex, candidateAction);
        }
        if (othersMappings.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("reconfigure_others is referenced, but all mapping components are already individually referenced.");
            }
            return;
        }
        for (Integer mappingIndex : othersMappings) {
            spec.registerReconfigure(mappingIndex, UpdateConstants.RECONFIGURE_OTHERS);
        }
    }

    private static void assignStartActions(
            UpdateProtocolSpec candidates,
            UpdateProtocolSpec spec,
            Set<String> references) {
        boolean othersRequested = references.contains(UpdateConstants.START_NEW_SPEC_OTHERS);
        Set<String> referenced = intersection(candidates.getStartNewSpecActions(), references);
        Map<String, String> safetyToAction = candidates.getNewSafetyToStartAction();
        if (safetyToAction.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("startNewSpec_others is referenced, but there are no new safety properties to group.");
            }
            return;
        }
        if (referenced.isEmpty() && !othersRequested) {
            for (String safety : safetyToAction.keySet()) {
                spec.registerStartNewSpec(safety, UpdateConstants.START_NEW_SPEC);
            }
            return;
        }

        Set<String> othersSafeties = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : safetyToAction.entrySet()) {
            String safety = entry.getKey();
            String candidateAction = entry.getValue();
            if (referenced.contains(candidateAction)) {
                spec.registerStartNewSpec(safety, candidateAction);
            } else {
                othersSafeties.add(safety);
            }
        }
        if (othersSafeties.isEmpty()) {
            if (othersRequested) {
                Diagnostics.fatal("startNewSpec_others is referenced, but all new safety properties are already individually referenced.");
            }
            return;
        }
        for (String safety : othersSafeties) {
            spec.registerStartNewSpec(safety, UpdateConstants.START_NEW_SPEC_OTHERS);
        }
    }

    private static Map<Integer, String> mappingActionsByIndex(UpdateProtocolSpec candidates) {
        Map<Integer, String> result = new LinkedHashMap<Integer, String>();
        for (Map.Entry<String, Set<Integer>> entry : candidates.getActionToMappingIndices().entrySet()) {
            for (Integer mappingIndex : entry.getValue()) {
                result.put(mappingIndex, entry.getKey());
            }
        }
        return result;
    }

    private static Set<String> intersection(Set<String> candidates, Set<String> references) {
        Set<String> result = new LinkedHashSet<String>();
        for (String action : candidates) {
            if (references.contains(action)) {
                result.add(action);
            }
        }
        return result;
    }

    private static List<Integer> sortedIndices(Set<Integer> indices) {
        List<Integer> result = new ArrayList<Integer>(indices);
        Collections.sort(result);
        return result;
    }
}
