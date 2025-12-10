package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.controller.ControllerSynthesisFacade;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.util.ControllerUtils;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSOutput;

import java.util.*;
import java.util.stream.Collectors;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.NonDeterministicUtils.translateLabelsAllLast;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.RSEngineUtils.synthesiseWithRSEngine;
import static org.junit.Assert.*;

public class LocalSynt {

    /**
     * @param composedSubSys
     * @param goal
     * @param output
     * @param statistics
     * @param translatorControllable
     * @param spectraEngine
     * @param strixEngine
     * @param totalTranslator
     * @return winning states of local synthesis
     * @throws CloneNotSupportedException
     */
    public static CompactState localSynthesis(CompositeState composedSubSys,
                                              ControllerGoal<String> goal,
                                              LTSOutput output, Statistics statistics,
                                              Map<String, String> translatorControllable, boolean spectraEngine, boolean strixEngine, boolean monolithicTranslationToRS, Vector<HashMap<String, String>> totalTranslator) throws CloneNotSupportedException {

        ControllerGoal<String> localGoal = (ControllerGoal<String>) goal.clone();
        composedSubSys.goal = localGoal;

        if (localGoal.getFluents().isEmpty())
            return composedSubSys.composition;

        // if spectraEngine/strixEngine is on
        synthesiseWithRSEngine(composedSubSys, output, statistics, spectraEngine, monolithicTranslationToRS, strixEngine);

        output.outln("Building winning states from Local Synthesis ..");
        MTS<Long, String> winningStatesFromEnv = getWinningStatesFromEnv(composedSubSys, localGoal, translatorControllable, totalTranslator, output);
        if (winningStatesFromEnv == null)
            return null;

        String partialControllerName = " (" +
                composedSubSys.getMachines().stream()
                        .map(CompactState::getName)
                        .collect(Collectors.joining("||")) +
                ")^C) ";

        int filteredSize = (int) winningStatesFromEnv.getActions().stream()
                .filter(action -> !action.equals("tau"))
                .count();
        return MTSToAutomataConverter.getInstance().convert(winningStatesFromEnv, partialControllerName, false);
    }


    /**
     * @param compositeState
     * @param goal
     * @param translatorControllable
     * @param totalTranslator
     * @param output
     * @return winning states with permissive transitions
     */
    public static MTS<Long, String>
        getWinningStatesFromEnv(CompositeState compositeState,
                                ControllerGoal<String> goal,
                                Map<String, String> translatorControllable, Vector<HashMap<String, String>> totalTranslator, LTSOutput output) {

        CompactState c = compositeState.composition;

        MTS<Long, String> env = AutomataToMTSConverter.getInstance().convert(c);
        Set<String> controllableActionsOfGoal = goal.getControllableActions();
        // I have to change the self-loop local transitions to "c"_{label}
        Set<String> translatorControllableValuesSet = new HashSet<>(translatorControllable.values());
        Set<String> translatorControllableTranslatedByRenaming = translateLabelsAllLast(translatorControllableValuesSet, totalTranslator);
        translatorControllableTranslatedByRenaming.addAll(translatorControllable.values());

        if(!translatorControllableValuesSet.isEmpty()){
            for(Long envState : env.getStates()){
                Iterator<Pair<String, Long>> transitionsFromEnvIterator = env.getTransitions(envState, MTS.TransitionType.REQUIRED).iterator();
                while(transitionsFromEnvIterator.hasNext()){
                    Pair<String, Long> transition = transitionsFromEnvIterator.next();

                    boolean isFakeSelfLoop =
                            translatorControllableTranslatedByRenaming.contains(transition.getFirst())
                                    && envState.equals(transition.getSecond());
                    if(isFakeSelfLoop){
                        env.addAction("c_"+transition.getFirst());
                        controllableActionsOfGoal.add("c_"+transition.getFirst());
                        env.addRequired(envState, "c_"+transition.getFirst(), transition.getSecond());
                        transitionsFromEnvIterator.remove();
                    }
                }
            }
        }

        goal.setControllableActions(controllableActionsOfGoal);
        MTS<Long, String> plant = ControllerUtils.embedFluents(env, goal, output);

        /** result mtsimpl**/
        ControllerSynthesisFacade<Long, String, Integer> facade = new ControllerSynthesisFacade<>();
        MTS<StrategyState<Long, Integer>, String> synthesised = facade.synthesiseController(plant, goal);

        if(synthesised == null)
        {
            return  null;
        }

        Set<Long> winningStatesOfEnv = plant.getWinningStatesOfOriginalEnv();

        // Winning States of Env must be a subset of environment states
        assertTrue(env.getStates().containsAll(winningStatesOfEnv));

        // If all states are winning, we return Env.
        if(winningStatesOfEnv.size()==env.getStates().size()){
            // i have to change "c_{label}" transitions by "{label}"
            for(Long envState : env.getStates()){
                Iterator<Pair<String, Long>> transitionsFromEnvIterator = env.getTransitions(envState, MTS.TransitionType.REQUIRED).iterator();
                while(transitionsFromEnvIterator.hasNext()){
                    Pair<String, Long> transition = transitionsFromEnvIterator.next();
                    boolean isFakeSelfLoop = transition.getFirst().startsWith("c_") && envState.equals(transition.getSecond());
                    if(isFakeSelfLoop){
                        env.addRequired(envState, transition.getFirst().substring(2), transition.getSecond());
                        transitionsFromEnvIterator.remove();
                    }
                }
            }
            Set<String> actionsToRemove = new HashSet<>(env.getActions());
            actionsToRemove.removeIf(e -> !e.startsWith("c_"));
            for(String action : actionsToRemove)
                env.removeAction(action);
            return env;
        }

        // If winning states is a smaller subset, we have to remove losing states of Env.
        MTSImpl<Long, String> result = new MTSImpl<>(env.getInitialState());

        // adding winning states and original transitions to result
        Long newState = 0L;
        Map<Long, Long> oldToNewStates = new HashMap<Long, Long>();
        for(Long winningState : winningStatesOfEnv ) {
            if(!oldToNewStates.containsKey(winningState)) {
                oldToNewStates.put(winningState, newState);
                newState++;
            }
            result.addState(oldToNewStates.get(winningState));
            BinaryRelation<String, Long> transitionsFromWinningState = env.getTransitions(winningState, MTS.TransitionType.REQUIRED);
            for(Pair<String, Long> transition : transitionsFromWinningState){
                String label = transition.getFirst();
                Long dstState = transition.getSecond();
                if(winningStatesOfEnv.contains(dstState)){
                    if(!oldToNewStates.containsKey(dstState)){
                        oldToNewStates.put(dstState, newState);
                        newState++;
                    }
                    result.addState(oldToNewStates.get(dstState));
                    result.addAction(normalizeLabel(label));
                    result.addRequired(oldToNewStates.get(winningState), normalizeLabel(label), oldToNewStates.get(dstState));
                }
            }
        }
        assertTrue("Result could not be bigger than original environment.",
                result.getNumberOfTransitions()<=env.getNumberOfTransitions() && result.getStates().size()<=env.getStates().size());
        return result;
    }

    private static String normalizeLabel(String label) {
        if(label.startsWith("c_")){
            return label.substring(2);
        }
        return label;
    }

}
