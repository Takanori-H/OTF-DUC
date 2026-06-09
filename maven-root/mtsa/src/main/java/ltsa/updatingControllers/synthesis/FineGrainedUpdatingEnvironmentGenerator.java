package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.UpdatingEnvironment;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedList;

public class FineGrainedUpdatingEnvironmentGenerator {

    private final MTS<Long, String> oldController;
    private final MTS<Long, String> mapping;
    private final UpdateProtocolSpec updateProtocolSpec;

    private final UpdatingEnvironment updEnv;
    private final Map<Long, Long> mappingToUpdEnv;
    private final ArrayList<Long> eParallelCStates;

    public FineGrainedUpdatingEnvironmentGenerator(
            MTS<Long, String> oldController,
            MTS<Long, String> mapping,
            UpdateProtocolSpec updateProtocolSpec) {
        this.oldController = oldController;
        this.mapping = mapping;
        this.updateProtocolSpec = updateProtocolSpec;

        this.updEnv = new UpdatingEnvironment(oldController);
        this.mappingToUpdEnv = new HashMap<Long, Long>();
        this.eParallelCStates = new ArrayList<Long>(updEnv.getStates());
        this.updEnv.addActions(updateProtocolSpec.getProgressActions());
    }

    public void generateEnvironment() {
        linkWithBeginUpdate();
        completeWithMapping();
    }

    private void linkWithBeginUpdate() {
        updEnv.addAction(UpdateConstants.BEGIN_UPDATE);
        addBeginUpdateTransition(updEnv.getInitialState(), mapping.getInitialState());
        eParallelCStates.add(updEnv.getInitialState());

        Queue<Pair<Long, Long>> toVisit = new LinkedList<Pair<Long, Long>>();
        Pair<Long, Long> firstState = new Pair<Long, Long>(
                oldController.getInitialState(),
                mapping.getInitialState());
        toVisit.add(firstState);
        ArrayList<Pair<Long, Long>> discovered = new ArrayList<Pair<Long, Long>>();

        while (!toVisit.isEmpty()) {
            Pair<Long, Long> actual = toVisit.remove();
            if (!discovered.contains(actual)) {
                discovered.add(actual);
                for (Pair<String, Long> actionToState
                        : oldController.getTransitions(actual.getFirst(), MTS.TransitionType.REQUIRED)) {
                    toVisit.addAll(nextToVisitInParallelComposition(actual, actionToState));
                }
            }
        }
    }

    private ArrayList<Pair<Long, Long>> nextToVisitInParallelComposition(
            Pair<Long, Long> actual,
            Pair<String, Long> transition) {
        ArrayList<Pair<Long, Long>> toVisit = new ArrayList<Pair<Long, Long>>();

        for (Pair<String, Long> actionToStateInMapping
                : mapping.getTransitions(actual.getSecond(), MTS.TransitionType.REQUIRED)) {
            String actionInMapping = actionToStateInMapping.getFirst();
            Long toStateInMapping = actionToStateInMapping.getSecond();

            if (transition.getFirst().equals(actionInMapping)) {
                eParallelCStates.add(transition.getSecond());
                addBeginUpdateTransition(transition.getSecond(), toStateInMapping);
                toVisit.add(new Pair<Long, Long>(transition.getSecond(), toStateInMapping));
            }
        }

        return toVisit;
    }

    private void addBeginUpdateTransition(Long from, Long originalMappingState) {
        if (mappingToUpdEnv.containsKey(originalMappingState)) {
            updEnv.addTransition(from, UpdateConstants.BEGIN_UPDATE, mappingToUpdEnv.get(originalMappingState));
        } else {
            Long freshState = updEnv.newState();
            updEnv.addTransition(from, UpdateConstants.BEGIN_UPDATE, freshState);
            mappingToUpdEnv.put(originalMappingState, freshState);
            addFineGrainedStopAndStartActions(freshState);
        }
    }

    private void completeWithMapping() {
        for (Long originalOldEnvState : mapping.getStates()) {
            for (Pair<String, Long> actionToState
                    : mapping.getTransitions(originalOldEnvState, MTS.TransitionType.REQUIRED)) {
                if (mappingToUpdEnv.containsKey(originalOldEnvState)) {
                    Long updEnvState = mappingToUpdEnv.get(originalOldEnvState);
                    Set<Long> freshStates = addTransitionCreatingNewStates(actionToState, updEnvState);
                    addFineGrainedStopAndStartActions(freshStates);
                } else {
                    Long freshUpdEnvState = addState(originalOldEnvState);
                    addFineGrainedStopAndStartActions(freshUpdEnvState);
                    Set<Long> freshStates = addTransitionCreatingNewStates(actionToState, freshUpdEnvState);
                    addFineGrainedStopAndStartActions(freshStates);
                }
            }
        }
    }

    private void addFineGrainedStopAndStartActions(Set<Long> freshStates) {
        if (freshStates.isEmpty()) {
            return;
        }
        addFineGrainedStopAndStartActions(freshStates.iterator().next());
    }

    private void addFineGrainedStopAndStartActions(Long state) {
        for (String action : updateProtocolSpec.getStopOldSpecActions()) {
            updEnv.addAction(action);
            updEnv.addTransition(state, action, state);
        }
        for (String action : updateProtocolSpec.getStartNewSpecActions()) {
            updEnv.addAction(action);
            updEnv.addTransition(state, action, state);
        }
    }

    private Set<Long> addTransitionCreatingNewStates(Pair<String, Long> actionToState, Long state) {
        Set<Long> result = new HashSet<Long>();
        updEnv.addAction(actionToState.getFirst());
        if (!mappingToUpdEnv.containsKey(actionToState.getSecond())) {
            Long freshUpdEnvState = addState(actionToState.getSecond());
            updEnv.addTransition(state, actionToState.getFirst(), freshUpdEnvState);
            result.add(freshUpdEnvState);
        } else {
            updEnv.addTransition(state, actionToState.getFirst(),
                    mappingToUpdEnv.get(actionToState.getSecond()));
        }
        return result;
    }

    private Long addState(Long originalState) {
        Long newState = updEnv.newState();
        mappingToUpdEnv.put(originalState, newState);
        return newState;
    }

    public UpdatingEnvironment getUpdEnv() {
        return updEnv;
    }
}
