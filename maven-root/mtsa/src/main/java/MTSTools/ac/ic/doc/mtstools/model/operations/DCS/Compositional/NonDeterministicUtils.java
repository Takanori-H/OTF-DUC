package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import org.javatuples.Triplet;

import java.util.*;

import static org.junit.Assert.*;

public class NonDeterministicUtils {


    public static HashMap<Long, Set<String>> checkStatesToBeFixedForNonDeterminism(MTS<Long, String> env, Map<String, String> translatorControllable) {
        HashMap<Long, Set<String>> statesToBeFixed = new HashMap<Long, Set<String>>();
        Set<String> repeatedTransitions = new HashSet<>();

        for( Long state : env.getStates() ){
            repeatedTransitions.clear();
            for(Pair<String, Long> transition : env.getTransitions(state, MTS.TransitionType.REQUIRED)){
                String transitionLabel = transition.getFirst();
                if (repeatedTransitions.contains(transitionLabel)) {
                    statesToBeFixed.computeIfAbsent(state, k -> new HashSet<>()).add(transitionLabel);
                }

                repeatedTransitions.add(transitionLabel);

            }
        }

        return statesToBeFixed;
    }


    /**
     * @param minimised
     * @param original
     * @param statesToBeFixed
     * @param totalTranslator
     * @param referenceStateToClass
     * @return
     */
    public static Triplet<HashMap<String, String>, MTS<Long, String>, MTS<Long, String>>
        makeDistinguisher(MTS<Long, String> minimised,
                          MTS<Long, String> original,
                          HashMap<Long, Set<String>> statesToBeFixed,
                          Vector<HashMap<String, String>> totalTranslator,
                          HashMap<Long, Long> referenceStateToClass) {

        Set<Long> originalStates = original.getStates();
        Set<Long> minimisedStates = minimised.getStates();

        Set<Long> minimisedStatesNonDeterministic = new HashSet<>(statesToBeFixed.keySet());
        Set<Long> minimisedStatesDeterministic = new HashSet<>(minimisedStates);
        minimisedStatesDeterministic.removeAll(minimisedStatesNonDeterministic);

        assertEquals(minimisedStates.size(), (minimisedStatesDeterministic.size()+minimisedStatesNonDeterministic.size()));

        // renamedMinimised will be minimised automaton after non-deterministic fix and right renaming
        MTSImpl<Long, String> renamedMinimised = new MTSImpl<>(minimised.getInitialState());
        renamedMinimised.addStates(minimisedStates);

        HashMap<String, String> translator = new HashMap<>();

        // this method updates renamedMinimised only adding non-deterministic states transitions
        // important: builds translator variable!
        for(Long stateOfMinimised : minimisedStatesNonDeterministic){
            fixNonDeterministic(stateOfMinimised, minimised, renamedMinimised, translator, statesToBeFixed);
        }

        Set<Triplet<Long,String,Long>> bothTranslations = new HashSet<Triplet<Long,String,Long>>();
        for(Long stateOfMinimised : minimisedStatesNonDeterministic){
            assertTrue(statesToBeFixed.containsKey(stateOfMinimised));
            if(statesToBeFixed.containsKey(stateOfMinimised)){
                BinaryRelation<String, Long> transitionsFromState = minimised.getTransitions(stateOfMinimised, MTS.TransitionType.REQUIRED);

                for(Pair<String, Long> transition : transitionsFromState){
                    String label = transition.getFirst();

                    // if label has translation
                    if(translator.containsValue(label) && !statesToBeFixed.get(stateOfMinimised).contains(label)){
                        for(Map.Entry<String, String> translation : translator.entrySet()){
                            if(translation.getValue().equals(label) ){
                                renamedMinimised.addAction(translation.getKey());
                                renamedMinimised.addRequired(stateOfMinimised, translation.getKey(), transition.getSecond());
                                bothTranslations.add(new Triplet<>(stateOfMinimised, translation.getKey(), transition.getSecond()));
                            }
                        }
                    }else if(!translator.containsValue(label)){
                        renamedMinimised.addAction(label);
                        renamedMinimised.addRequired(stateOfMinimised, label, transition.getSecond());
                    }
                }
            }
        }

        // now i have to update possible translation of deterministic transitions of all states
        for(Long stateOfMinimised : minimisedStatesDeterministic){
            assertFalse(statesToBeFixed.containsKey(stateOfMinimised));
            if(!statesToBeFixed.containsKey(stateOfMinimised)){
                BinaryRelation<String, Long> transitionsFromState = minimised.getTransitions(stateOfMinimised, MTS.TransitionType.REQUIRED);

                for(Pair<String, Long> transition : transitionsFromState){
                    String label = transition.getFirst();

                    // if label has no translation
                    if(!translator.containsValue(label)){
                        renamedMinimised.addAction(label);
                        renamedMinimised.addRequired(stateOfMinimised, label, transition.getSecond());
                    }else{
                        for(Map.Entry<String, String> translation : translator.entrySet()){
                            if(translation.getValue().equals(label)){
                                bothTranslations.add(new Triplet<>(stateOfMinimised, translation.getKey(), transition.getSecond()));
                                renamedMinimised.addAction(translation.getKey());
                                renamedMinimised.addRequired(stateOfMinimised, translation.getKey(), transition.getSecond());
                                bothTranslations.add(new Triplet<>(stateOfMinimised, translation.getKey(), transition.getSecond()));
                            }
                        }
                    }
                }
            }
        }
        MTS<Long,String> translatedOriginal = translate(original, translator, renamedMinimised, referenceStateToClass, bothTranslations);

        if(!totalTranslator.contains(translator))
            totalTranslator.add(translator);


        // Utils.removeUnusedActions(renamedMinimised, new HashSet<>());

        // we will use translator to build translated original LTS
        //MTS<Long,String> translatedOriginal = translate(original, translator, renamedMinimised, referenceStateToClass);
        // Utils.removeUnusedActions(translatedOriginal, new HashSet<>());

        return new Triplet<HashMap<String, String>, MTS<Long,String>, MTS<Long,String>>(translator,
                renamedMinimised,
                translatedOriginal);
    }

    /**
     *
     * @param stateOfMinimised
     * @param minimised
     * @param renamed
     * @param translator
     * @param statesToBeFixed
     *
     * @return translator hashmap but also repair minimised lts nondeterministic issues
     */
    private static HashMap<String, String> fixNonDeterministic(Long stateOfMinimised,
                                                               MTS<Long, String> minimised,
                                                               MTS<Long, String> renamed,
                                                               HashMap<String, String> translator,
                                                               HashMap<Long, Set<String>> statesToBeFixed) {

        BinaryRelation<String, Long> transitionsFromState = minimised.getTransitions(stateOfMinimised, MTS.TransitionType.REQUIRED);

        Set<String> transitionsToRename = statesToBeFixed.get(stateOfMinimised);

        int k = 0;
        for (Pair<String, Long> transition : transitionsFromState) {
            String label = transition.getFirst();
            Long dstState = transition.getSecond();

            if (transitionsToRename.contains(label)) {
                String renamedLabel = "r_" + k + label;
                renamed.addAction(renamedLabel);
                renamed.addTransition(stateOfMinimised, renamedLabel, dstState, MTS.TransitionType.REQUIRED);
                translator.put(renamedLabel, label);
                k++;
            }
        }
        return translator;
    }

    // this method translates original LTS alphabet Sigma_1 to renamed alphabet Sigma_2
    private static MTS<Long, String> translate(MTS<Long, String> original, HashMap<String, String> translator,
                                               MTSImpl<Long, String> renamedMinimised, HashMap<Long, Long> referenceStateToClass, Set<Triplet<Long, String, Long>> bothTranslations) {

        MTSImpl<Long, String> translated = new MTSImpl<>(original.getInitialState());
        Set<Long> originalStates = original.getStates();
        Set<String> originalActions = original.getActions();
        Set<String> translatedOriginalActions = translateActions(originalActions, translator);

        translated.addActions(translatedOriginalActions);
        translated.addStates(originalStates);

        Collection<String> translatedValues = translator.values();

        for(Long originalState : originalStates){
            for(Pair<String, Long> originalTransition : original.getTransitions(originalState, MTS.TransitionType.REQUIRED)){
                String label = originalTransition.getFirst();
                Long dstState = originalTransition.getSecond();

                if(translatedValues.contains(label)){
                    Set<String> setOfTranslationLabel = inverseTranslateLabel(label, translator);
                    String translatedDistinguisherLabel = getTranslatedDistinguisherLabel(originalState, dstState, setOfTranslationLabel, renamedMinimised, referenceStateToClass, bothTranslations);
                    if(translatedDistinguisherLabel==null) {
                        if(referenceStateToClass.get(originalState).equals(referenceStateToClass.get(dstState))){
                            BinaryRelation<String, Long> translatesFromOriginalClass
                                    = renamedMinimised.getTransitions(referenceStateToClass.get(originalState), MTS.TransitionType.REQUIRED);
                            for(Pair<String,Long> translateFromOrgClass : translatesFromOriginalClass){
                                if(translateFromOrgClass.getFirst().equals("c_"+label)){
                                    translated.addAction("c_"+label);
                                    translated.addRequired(originalState, "c_"+label, dstState);
                                }
                            }
                        }else{
                            for(String labelAll : setOfTranslationLabel){
                                translated.addAction(labelAll);
                                translatedDistinguisherLabel=labelAll;
                                translated.addRequired(originalState, translatedDistinguisherLabel, dstState);
                            }
                        }

                    }else{
                        translated.addRequired(originalState, translatedDistinguisherLabel, dstState);
                    }
                }else{
                    translated.addRequired(originalState, label, dstState);
                }
            }
        }
        return translated;
    }

    private static String getTranslatedDistinguisherLabel(Long originalState,
                                                          Long dstState,
                                                          Set<String> setOfTranslationLabel,
                                                          MTSImpl<Long, String> renamedMinimised,
                                                          HashMap<Long, Long> referenceStateToClass, Set<Triplet<Long, String, Long>> bothTranslations) {




        Long originalPartitionSrcState = referenceStateToClass.get(originalState);
        Long originalPartitionDstState = referenceStateToClass.get(dstState);


        BinaryRelation<String, Long> originalStateTransitions = renamedMinimised.getTransitions(originalPartitionSrcState, MTS.TransitionType.REQUIRED);

        for(Pair<String,Long> transition : originalStateTransitions){
            String label = transition.getFirst();
            Long dstReducedState = transition.getSecond();

            if(setOfTranslationLabel.contains(label) && dstReducedState.equals(originalPartitionDstState)){
                // its the reduced label
                return label;
            }

        }
        if(!originalPartitionDstState.equals(originalPartitionSrcState))
            System.currentTimeMillis();
        return null;
    }

    /**
     *
     * @param label
     * @param translator
     * @return returns inverse translation from label using translator
     */
    private static Set<String> inverseTranslateLabel(String label, HashMap<String, String> translator) {
        Set<String> result = new HashSet<>();
        for(String indexTranslator : translator.keySet()){
            if(translator.get(indexTranslator).equals(label)){
                result.add(indexTranslator);
            }
        }
        return result;
    }

    /**
     *
     * @param actions
     * @param translator
     * @return returns a set of actions after translate using translator and parameter actions
     */
    public static Set<String> translateActions(Set<String> actions, HashMap<String, String> translator) {

        Set<String> translatedActions = new HashSet<>();
        for(String action : actions){
            translatedActions.addAll(
                    translator.containsValue(action)
                            ? inverseTranslateLabel(action, translator)
                            : Collections.singleton(action)
            );
        }
        return translatedActions;
    }


    public static Set<String> translateLabelsAllLast(
            Set<String> labels,
            Vector<HashMap<String, String>> totalTranslator) {

        Set<String> results = new HashSet<>();

        for (String startLabel : labels) {
            // usamos una pila para explorar todas las traducciones posibles
            Deque<String> stack = new ArrayDeque<>();
            stack.push(startLabel);

            while (!stack.isEmpty()) {
                String current = stack.pop();
                boolean foundNext = false;

                for (HashMap<String, String> translatorDict : totalTranslator) {
                    for (Map.Entry<String, String> entry : translatorDict.entrySet()) {
                        if (current.equals(entry.getValue())) {
                            stack.push(entry.getKey());
                            foundNext = true;
                        }
                    }
                }

                // si no encontramos más traducciones para current, es un "último"
                if (!foundNext) {
                    results.add(current);
                }
            }
        }

        return results;
    }


    public static Set<String> translateLabelsLastOnly(Set<String> labels, Vector<HashMap<String, String>> totalTranslator) {

        Set<String> lastTranslatedLabels = new HashSet<>();

        for (String startLabel : labels) {
            String current = startLabel;
            boolean foundNext;

            do {
                foundNext = false;
                for (HashMap<String, String> translatorDict : totalTranslator) {
                    // buscar si current es VALUE en este diccionario
                    for (Map.Entry<String, String> entry : translatorDict.entrySet()) {
                        if (current.equals(entry.getValue())) {
                            current = entry.getKey(); // avanzar en la cadena
                            foundNext = true;
                            break;
                        }
                    }
                    if (foundNext) break; // salimos para seguir la nueva traducción
                }
            } while (foundNext);

            lastTranslatedLabels.add(current);
        }
        return lastTranslatedLabels;
    }


}
