package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.TranslateToRS;

import MTSSynthesis.ar.dc.uba.model.condition.*;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TranslateToStrix<State, Action> {

    private HashSet<Action> allUncontrollableLabels  = new HashSet<>();
    private HashMap<Action, String> labelToBinary = new HashMap<>();
    private HashSet<Action> allLabels = new HashSet<>();
    private Set<String> binaryLabels;
    private Set<String> binaryControllableLabels;
    private HashMap<Action, String> controllableLabelToBinary;

    public void translate(List<LTS<State,Action>> ltss, Set<Action> controllable, HashSet<Formula> formulasAss, HashSet<Formula> formulasGar) {

        try {

            Iterator<LTS<State, Action>> it = ltss.iterator();
            LTS<State, Action> lts;
            Integer ltsNumber = 0;

            HashSet<Action> l_e_actions = new HashSet<>();
            HashSet<Action> l_c_actions = new HashSet<>();

            HashMap<Integer, State> initialStates = new HashMap<>();
            HashMap<Integer, Set<State>> statesIndexes = new HashMap<>();
            HashMap<Integer, Set<Action>> labelsByLts = new HashMap<>();
            HashMap<Integer, Set<Action>> controllableLabelsByLts = new HashMap<>();
            HashMap<Integer, Set<Action>> uncontrollableLabelsByLts = new HashMap<>();

            HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts = new HashMap<>();
            Set<Action> uncontrollable = null;

            // Itero entre los LTS de la composición.
            while (it.hasNext()){
                lts = it.next();
                uncontrollable = new HashSet<>(lts.getActions());
                Set<Action> finalControllable = controllable;
                uncontrollable.removeIf(a -> finalControllable.contains(a));

                Set<Action> cleanUncontrollable = new HashSet<>();
                Set<Action> cleanControllable = new HashSet<>();

                for (Action uAction : uncontrollable){
                    cleanUncontrollable.add((Action) uAction.toString().replace(".", ""));
                }
                for (Action cAction : controllable){
                    cleanControllable.add((Action) cAction.toString().replace(".", ""));
                }

                uncontrollable = cleanUncontrollable;
                controllable = cleanControllable;

                // VARIABLE L_e
                l_e_actions.addAll(controllable);
                l_e_actions.addAll(uncontrollable);
                l_e_actions.removeIf(a -> a.equals("tau"));
                // VARIABLE L_c
                l_c_actions.addAll(controllable);

                String classN = lts.getClass().getSimpleName();
                if(classN.equals("LTSAdapter")){

                    // Agrego estado inicial de los LTS que representan la planta y safety.
                    initialStates.put(ltsNumber, lts.getInitialState());

                    // Escribo las variables de estado del autómata
                    statesIndexes.put(ltsNumber, lts.getStates());

                    // Para cada estado
                    // Map<State, BinaryRelation<Action, State>> transitionsLTS = lts.getTransitions();
                    transitionsByLts.put(ltsNumber, lts.getTransitions());

                    Set<Action> ltsActions = new HashSet<>();
                    for(Action actionOfLts : lts.getActions()){
                        if(actionOfLts!="tau"){
                            ltsActions.add((Action) actionOfLts.toString().replace(".", ""));
                        }
                    }
                    labelsByLts.put(ltsNumber, ltsActions);

                    Set<Action> controllableLabels = new HashSet<>();
                    Set<Action> uncontrollableLabels = new HashSet<>();

                    for(Action actionCheck : lts.getActions()){
                        if(controllable.contains(actionCheck.toString().replace(".", ""))){
                            controllableLabels.add((Action) actionCheck.toString().replace(".", ""));
                        }else if(uncontrollable.contains(actionCheck.toString().replace(".", ""))){
                            uncontrollableLabels.add((Action) actionCheck.toString().replace(".", ""));
                        }
                    }

                    allUncontrollableLabels.addAll(uncontrollableLabels);
                    controllableLabelsByLts.put(ltsNumber, controllableLabels);
                    uncontrollableLabelsByLts.put(ltsNumber, uncontrollableLabels);

                    //transitionsLTS.isEmpty();
/*
                    Iterator<BinaryRelation<Action, State>> itTransitions = transitionsLTS.values().iterator();
                     */
                }
                /* else if (classN.equals("MarkedLTSImpl")) {
                    // Caso de LTS que representan los Fluents con estructura de estados marcados
                    ltsNumber=3;
                } */


                ltsNumber++;
            }

            /**
             *
             * STRIX
             */

            // Get input variables
            String inputVariables = "--ins=";
            for(Map.Entry<Integer, Set<State>> indexState : statesIndexes.entrySet()){
                Integer lts_number = indexState.getKey();
                Iterator<State> stateIt = indexState.getValue().iterator();
                inputVariables += "s" + lts_number + "" + stateIt.next()+",";
                while(stateIt.hasNext()){
                    inputVariables += "s" + lts_number + "" + stateIt.next()+",";
                }
            }
            Iterator<String> actionsIt = binaryLabels.iterator();
            while(actionsIt.hasNext()){
                inputVariables += actionsIt.next()+",";
            }

            if(!formulasAss.isEmpty()) {
                Iterator<Formula> formIt = formulasAss.iterator();
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
                while(formIt.hasNext()){
                    inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
                }
            }
            if(!formulasGar.isEmpty()) {
                Iterator<Formula> formIt = formulasGar.iterator();
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
                while(formIt.hasNext()){
                    inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
                }
            }

            // Get output variables
            String outputVariables = "--outs=";
            Iterator<Action> actionsContIt = l_c_actions.iterator();
            outputVariables += "lc" + actionsContIt.next()+",";
            while(actionsContIt.hasNext()){
                outputVariables += "lc" + actionsContIt.next()+",";
            }
            outputVariables += "none";

            /**
             *
             * Assumptions
             *
             **/

            // Mutual Exclusion between variables
            String envMutualExclusion = envMutualExclusionBuilder(formulasAss, formulasGar, statesIndexes, l_e_actions);

            // Variables Initialization
            String envVariableInit = envVariableInit(formulasAss, formulasGar, statesIndexes);

            // Env Rule 4
            String envRule4 = envRule4Builder(transitionsByLts, statesIndexes, labelsByLts);

            // Env Rule 5
            String envRule5 = envRule5Builder(l_c_actions);

            // Env Rule 6
            String envRule6 = envRule6Builder(transitionsByLts, statesIndexes, l_e_actions, labelsByLts);

            // Env Rule 7
            // TODO: only supports conjunctions of FluentPropositionalVariable and requires a guarantee
            String envRule7 = envRule7Builder(formulasAss, formulasGar, l_e_actions);

            String assumptions
                    = "( " + envVariableInit
                    + "&" + envMutualExclusion
                    //+ " & " + envVariableInit
                    + " & (" + envRule4 + ")"
                    + " & (" + envRule5 + ")"
                    + " & (" + envRule6 + ")"
                    + " & (" + envRule7 + "))";


            /**
             *
             * Guarantees
             *
             **/

            // Rule G1
            String sysRule1 = "";
            for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
                Integer LTSKey = transitionsLTS.getKey();
                Set<State> statesLTS = new HashSet<>(statesIndexes.get(LTSKey));
                Set<Action> controllableLabelsInLts = controllableLabelsByLts.get(LTSKey);
                for(State stateToCheck : statesLTS){
                    for(Action controllableAction : controllableLabelsInLts){
                        boolean hasTransition = false;
                        for(Pair<Action, State> transitionInState : transitionsLTS.getValue().get(stateToCheck)){
                            if(!transitionInState.getSecond().toString().equals("-1") && controllableAction.equals(transitionInState.getFirst().toString().replace(".",""))){ hasTransition=true; }
                        }
                        if(!hasTransition){
                            sysRule1 += "G (s"+LTSKey+stateToCheck+" -> (!lc" + controllableAction+ ")) & ";
                        }
                    }
                }
            }
            sysRule1 = sysRule1.substring(0, sysRule1.length() - 2);


            // Rule G2
            Map<Action, Map<Integer, Set<State>>> rule_info = new HashMap<>();
            for(Action uncontrollableAction : allUncontrollableLabels){ // Para toda l \in Sigma_u
                rule_info.put(uncontrollableAction, new HashMap<>());

                for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()) { // Para todo x \in Aut
                    Integer LTSKey = transitionsLTS.getKey();
                    Map<Integer, Set<State>> LTS_StatesFromUnc = rule_info.get(uncontrollableAction);

                    if(uncontrollableLabelsByLts.get(LTSKey).contains(uncontrollableAction)){
                        LTS_StatesFromUnc.put(LTSKey, new HashSet<>());
                        Map<State, BinaryRelation<Action, State>> transitionOfLTS = transitionsLTS.getValue();
                        for(Map.Entry<State, BinaryRelation<Action, State>> transition : transitionOfLTS.entrySet()){
                            for(Pair<Action, State> transitionDest : transition.getValue()){
                                if(transitionDest.getFirst().toString().replace(".","").equals(uncontrollableAction)){
                                    Set<State> conjStates = LTS_StatesFromUnc.get(LTSKey);
                                    conjStates.add(transition.getKey());
                                }
                            }
                        }



                    }

                }
            }
            rule_info.remove("tau");
            String sysRule2 = "";
            if(!rule_info.isEmpty()) {
                sysRule2 += "G (none -> (" ;
                //writerFile.write(("gar always l_c=None -> "));
                Integer cantLabels = 0;
                for (Map.Entry<Action, Map<Integer, Set<State>>> eachRule : rule_info.entrySet()) {
                    Action uncontrollableActionRule = eachRule.getKey();
                    if(!eachRule.getValue().isEmpty() && !uncontrollableActionRule.equals("tau")){
                        sysRule2 += "(";
                        Integer cantAuto = 0;
                        for (Map.Entry<Integer, Set<State>> eachTransition : eachRule.getValue().entrySet()) {
                            Integer LTSKey = eachTransition.getKey();
                            if (!eachTransition.getValue().isEmpty()) {
                                sysRule2 += "(";
                                Integer k = 0;
                                for (State stateFrom : eachTransition.getValue()) {
                                    sysRule2 += "s"+ LTSKey + stateFrom;
                                    k++;
                                    if (k < eachTransition.getValue().size()) {
                                        sysRule2 += " | ";
                                    }
                                }
                                sysRule2 +=")";
                                cantAuto++;
                                if (cantAuto < eachRule.getValue().size()) {
                                    sysRule2 += " & ";
                                }
                            }
                        }
                        sysRule2 +=")";
                        cantLabels++;
                        if (cantLabels < rule_info.keySet().size()) {
                            sysRule2 += " | ";
                        }


                    }else{ cantLabels++; }
                }
                sysRule2 +="))";

            }

            String guarantees = "(" + sysRule1 + ") & (" + sysRule2 + ")";


            // Regla LIVENESS
            String asmLiveness = "";
            for (Formula formA : formulasAss){
                asmLiveness += "(GF (" + getFluentsVariableFromFormula(formA) + ")) & ";
            }
            if(!formulasAss.isEmpty()){
                asmLiveness = asmLiveness.substring(0, asmLiveness.length() - 2);
                assumptions += " & ("+asmLiveness+")";
            }

            String garLiveness = "";
            for (Formula formG : formulasGar){
                garLiveness += "(GF (" + getFluentsVariableFromFormula(formG) + ")) & ";
            }
            if(!formulasGar.isEmpty()){
                garLiveness = garLiveness.substring(0, garLiveness.length() - 2);
                guarantees += " & ("+garLiveness+")";
            }


            String formula = "(" + assumptions + ") -> (" + guarantees + ")";


            // Estados iniciales del sistema
            /* writerFile.write("//// *************************\n" +
                    "//// Estados iniciales del Controlador");
            Set<Action> possibleInitalSystemActions = new HashSet<>();

            for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
                Integer LTSKey = transitionsLTS.getKey();
                State initialStateLTS = ltss.get(LTSKey).getInitialState();
                Set<Action> controllableLabelsInLts = controllableLabelsByLts.get(LTSKey);

                // Agrego todas las transiciones controlables del LTS que estoy chequeando
                for(Pair<Action, State> transitionInState : transitionsLTS.getValue().get(initialStateLTS)){
                    if(controllable.contains(transitionInState.getFirst().toString().replace(".",""))){
                        possibleInitalSystemActions.add((Action) transitionInState.getFirst().toString().replace(".",""));
                    }
                }
                Set<Action> removeInitialActions = new HashSet<>();
                for(Integer otherLTS : transitionsByLts.keySet()){
                    if(!LTSKey.equals(otherLTS)){
                        // Chequeo para cada 'otro' LTS si

                        for(Action possibleInit : possibleInitalSystemActions){
                            State initialStateOtherLTS = ltss.get(otherLTS).getInitialState();
                            Set<Action> controllableLabelsInOtherLts = controllableLabelsByLts.get(otherLTS);
                            boolean possibleInitInOtherInitialState = false;
                            for(Pair<Action, State> transitionInState : transitionsByLts.get(otherLTS).get(initialStateOtherLTS)){
                                if(possibleInit.equals(transitionInState.getFirst().toString().replace(".",""))) {
                                    possibleInitInOtherInitialState=true;
                                }
                            }
                            // remuevo la posible acción si el otro estado tiene la etiqueta entre sus controlables pero no en su estado inicial
                            if(controllableLabelsInOtherLts.contains(possibleInit) && !possibleInitInOtherInitialState){
                                removeInitialActions.add(possibleInit);
                            }
                        }


                    }
                }

                possibleInitalSystemActions.removeAll(removeInitialActions);

            }
            writeLine();

             */

            /*
            if(!possibleInitalSystemActions.isEmpty()){
                writerFile.write("gar ini ");
                int k=1;
                for(Action possibleInitAction : possibleInitalSystemActions){
                    if(k== possibleInitalSystemActions.size()){
                        writerFile.write("l_c=" + possibleInitAction+";");
                    }else{
                        writerFile.write("l_c=" + possibleInitAction + " or ");
                    }
                    k++;
                }
            }
            writeLine();


            boolean initialHasUncontrollable = false;
            for(Map.Entry<Integer, State> initState : initialStates.entrySet()) {
                BinaryRelation<Action, State> transitionsInitial = transitionsByLts.get(initState.getKey()).get(initState.getValue());
                for(Pair<Action, State> transitionFromInitial : transitionsInitial) {
                    if (uncontrollableLabelsByLts.get(initState.getKey()).contains(transitionFromInitial.getFirst())) {
                        initialHasUncontrollable = true;
                    }
                }
            }
            if(!initialHasUncontrollable){
                writerFile.write("gar ini l_c=None;\n");
                writeLine();
            }*/


        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String binaryTranslate(List<LTS<State,Action>> ltss, Set<Action> controllable, HashSet<Formula> formulasAss, HashSet<Formula> formulasGar) {

        try {

            Iterator<LTS<State, Action>> it = ltss.iterator();
            LTS<State, Action> lts;
            Integer ltsNumber = 0;

            HashSet<Action> l_e_actions = new HashSet<>();
            HashSet<Action> l_c_actions = new HashSet<>();

            HashMap<Integer, State> initialStates = new HashMap<>();
            HashMap<Integer, Set<State>> statesIndexes = new HashMap<>();
            HashMap<Integer, Set<Action>> labelsByLts = new HashMap<>();
            HashMap<Integer, Set<Action>> controllableLabelsByLts = new HashMap<>();
            HashMap<Integer, Set<Action>> uncontrollableLabelsByLts = new HashMap<>();

            HashMap<Integer, Integer> statesByLTS = new HashMap<Integer, Integer>();

            HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts = new HashMap<>();
            Set<Action> uncontrollable = null;

            // Itero entre los LTS de la composición.
            Integer idLTS=0;
            while (it.hasNext()){

                lts = it.next();
                statesByLTS.put(idLTS, lts.getStates().size());
                idLTS++;

                uncontrollable = new HashSet<>(lts.getActions());
                Set<Action> finalControllable = controllable;
                uncontrollable.removeIf(a -> finalControllable.contains(a));

                Set<Action> cleanUncontrollable = new HashSet<>();
                Set<Action> cleanControllable = new HashSet<>();
                Set<Action> cleanActions = new HashSet<>();


                for (Action uAction : lts.getActions()){
                    cleanActions.add((Action) uAction.toString().replace(".", ""));
                }
                for (Action uAction : uncontrollable){
                    cleanUncontrollable.add((Action) uAction.toString().replace(".", ""));
                }
                for (Action cAction : controllable){
                    cleanControllable.add((Action) cAction.toString().replace(".", ""));
                }

                uncontrollable = cleanUncontrollable;
                controllable = cleanControllable;

                uncontrollable.retainAll(cleanActions);
                controllable.retainAll(cleanActions);
                // VARIABLE L_e
                l_e_actions.addAll(controllable);
                l_e_actions.addAll(uncontrollable);
                l_e_actions.removeIf(a -> a.equals("tau"));
                // VARIABLE L_c
                l_c_actions.addAll(controllable);

                String classN = lts.getClass().getSimpleName();
                if(classN.equals("LTSAdapter")){

                    // Agrego estado inicial de los LTS que representan la planta y safety.
                    initialStates.put(ltsNumber, lts.getInitialState());

                    // Escribo las variables de estado del autómata
                    statesIndexes.put(ltsNumber, lts.getStates());

                    // Para cada estado
                    // Map<State, BinaryRelation<Action, State>> transitionsLTS = lts.getTransitions();
                    transitionsByLts.put(ltsNumber, lts.getTransitions());

                    Set<Action> ltsActions = new HashSet<>();
                    for(Action actionOfLts : lts.getActions()){
                        if(actionOfLts!="tau"){
                            ltsActions.add((Action) actionOfLts.toString().replace(".", ""));
                        }
                    }
                    labelsByLts.put(ltsNumber, ltsActions);

                    Set<Action> controllableLabels = new HashSet<>();
                    Set<Action> uncontrollableLabels = new HashSet<>();

                    for(Action actionCheck : lts.getActions()){
                        if(controllable.contains(actionCheck.toString().replace(".", ""))){
                            controllableLabels.add((Action) actionCheck.toString().replace(".", ""));
                        }else if(uncontrollable.contains(actionCheck.toString().replace(".", ""))){
                            uncontrollableLabels.add((Action) actionCheck.toString().replace(".", ""));
                        }
                    }
                    allUncontrollableLabels.addAll(uncontrollableLabels);
                    controllableLabelsByLts.put(ltsNumber, controllableLabels);
                    uncontrollableLabelsByLts.put(ltsNumber, uncontrollableLabels);
                    allLabels.addAll(uncontrollableLabels);
                    allLabels.addAll(controllableLabels);
                    allLabels.remove("tau");
                }
                ltsNumber++;
            }


            l_e_actions.remove("tau");
            l_c_actions.remove("tau");
            l_c_actions.remove("tau?");
            l_e_actions.remove("tau?");

            l_e_actions.removeIf(s -> s.toString().endsWith("?"));
            l_c_actions.removeIf(s -> s.toString().endsWith("?"));


            /**
             *
             * STRIX
             */

            // States of each LTS will be represented with log(#states) variables.
            // For example: LTS 0 with 8 states (S00, .., S07) will be represented with 3 variables
            // S00 will be (!S00 && !S01 && !S02)
            // S06 will be (!S00 && S01 && S02)

            // Get input variables
            String inputVariables = "--ins=";


            /** I want to binarize actions, so i will get a dict with binary representation
             * of each label
             * **/
            Pair<Set<String>, HashMap<Action, String>> binaryRepresentationOfLabels = getBinaryRepresentationFromLabels(allLabels);
            labelToBinary = binaryRepresentationOfLabels.getSecond();
            binaryLabels = binaryRepresentationOfLabels.getFirst();

            // controllable label representation
            Pair<Set<String>, HashMap<Action, String>> controllableBinaryRepresentationOfLabels = getControllableBinaryRepresentationFromLabels((HashSet<Action>) l_c_actions);
            controllableLabelToBinary = controllableBinaryRepresentationOfLabels.getSecond();
            binaryControllableLabels = controllableBinaryRepresentationOfLabels.getFirst();

            Iterator<String> actionsIt = binaryLabels.iterator();
            while(actionsIt.hasNext()){
                inputVariables +=  actionsIt.next()+",";
            }

            if(!formulasAss.isEmpty()) {
                Iterator<Formula> formIt = formulasAss.iterator();
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
                while(formIt.hasNext()){
                    inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
                }
            }
            if(!formulasGar.isEmpty()) {
                Iterator<Formula> formIt = formulasGar.iterator();
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
                while(formIt.hasNext()){
                    inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
                }
            }

            // Get output variables
            String outputVariables = "--outs=";
            Iterator<String> actionsContIt = binaryControllableLabels.iterator();
            while(actionsContIt.hasNext()){
                outputVariables +=  actionsContIt.next()+",";
            }
            for(Map.Entry<Integer, Set<State>> indexState : statesIndexes.entrySet()){
                Integer lts_number = indexState.getKey();
                int statesSum = indexState.getValue().size();
                int binarySumVariables = (int) Math.ceil(Math.log(statesSum) / Math.log(2));

                for(int k=0; k<binarySumVariables; k++){
                    outputVariables += "s" + lts_number + "" + k + ",";
                }
            }

            String noneVar = getNoneVar(binaryControllableLabels);

            String variablesOfStrix = inputVariables + " " + outputVariables;
            String assumptions = "";

            /**
             *
             * Guarantees
             *
             **/
            // Variables Initialization
            String sysVariableInit = envVariableInitBinary(formulasAss, formulasGar, statesIndexes);

            // Rule G1
            String sysRule1 = "";
            for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
                Integer LTSKey = transitionsLTS.getKey();
                Set<State> statesLTS = new HashSet<>(statesIndexes.get(LTSKey));
                Set<Action> controllableLabelsInLts = controllableLabelsByLts.get(LTSKey);
                for(State stateToCheck : statesLTS){
                    for(Action controllableAction : controllableLabelsInLts){
                        boolean hasTransition = false;
                        for(Pair<Action, State> transitionInState : transitionsLTS.getValue().get(stateToCheck)){
                            if(!transitionInState.getSecond().toString().equals("-1") && controllableAction.equals(transitionInState.getFirst().toString().replace(".",""))){ hasTransition=true; }
                        }
                        if(!hasTransition){
                            String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);
                            String labelBinaryRep = controllableLabelToBinary.get(controllableAction);
                            sysRule1 += "G ((!"+ binaryRep +") | (!(" + labelBinaryRep+ "))) & ";
                        }
                    }
                }
            }
            sysRule1 = sysRule1.substring(0, sysRule1.length() - 2);

            // Rule G2
            // Regla 2
            Map<Action, Map<Integer, Set<State>>> rule_info = new HashMap<>();
            for(Action uncontrollableAction : allUncontrollableLabels){ // Para toda l \in Sigma_u
                rule_info.put(uncontrollableAction, new HashMap<>());

                for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()) { // Para todo x \in Aut
                    Integer LTSKey = transitionsLTS.getKey();
                    Map<Integer, Set<State>> LTS_StatesFromUnc = rule_info.get(uncontrollableAction);

                    if(uncontrollableLabelsByLts.get(LTSKey).contains(uncontrollableAction)){
                        LTS_StatesFromUnc.put(LTSKey, new HashSet<>());
                        Map<State, BinaryRelation<Action, State>> transitionOfLTS = transitionsLTS.getValue();
                        for(Map.Entry<State, BinaryRelation<Action, State>> transition : transitionOfLTS.entrySet()){
                            for(Pair<Action, State> transitionDest : transition.getValue()){
                                if(transitionDest.getFirst().toString().replace(".","").equals(uncontrollableAction)){
                                    Set<State> conjStates = LTS_StatesFromUnc.get(LTSKey);
                                    conjStates.add(transition.getKey());
                                }
                            }
                        }
                    }
                }
            }
            rule_info.remove("tau");
            String sysRule2 = "";
            if(!rule_info.isEmpty()) {
                sysRule2 += "G (!("+noneVar+") | (" ;
                Integer cantLabels = 0;
                for (Map.Entry<Action, Map<Integer, Set<State>>> eachRule : rule_info.entrySet()) {
                    Action uncontrollableActionRule = eachRule.getKey();
                    if(!eachRule.getValue().isEmpty() && !uncontrollableActionRule.equals("tau")){
                        sysRule2 += "(";
                        Integer cantAuto = 0;
                        for (Map.Entry<Integer, Set<State>> eachTransition : eachRule.getValue().entrySet()) {
                            Integer LTSKey = eachTransition.getKey();
                            if (!eachTransition.getValue().isEmpty()) {
                                sysRule2 += "(";
                                Integer k = 0;
                                for (State stateFrom : eachTransition.getValue()) {
                                    String binaryRep = getBinaryRepresentationFrom(LTSKey, stateFrom, statesIndexes);
                                    // sysRule2 += "s"+ LTSKey + stateFrom;
                                    sysRule2 += binaryRep;
                                    k++;
                                    if (k < eachTransition.getValue().size()) {
                                        sysRule2 += " | ";
                                    }
                                }
                                sysRule2 +=")";
                                cantAuto++;
                                if (cantAuto < eachRule.getValue().size()) {
                                    sysRule2 += " & ";
                                }
                            }
                        }
                        sysRule2 +=")";
                        cantLabels++;
                        if (cantLabels < rule_info.keySet().size()) {
                            sysRule2 += " | ";
                        }


                    }else{ cantLabels++; }
                }
                sysRule2 +="))";
            }

            String notErrorState = "";
            StringBuilder allTrue = new StringBuilder();
            for(Integer ltsNumber2 : transitionsByLts.keySet()){
                if(statesIndexes.get(ltsNumber2).contains((long) -1)){
                    int binarySumVariables = (int) Math.ceil(Math.log(statesIndexes.get(ltsNumber2).size()) / Math.log(2));
                    allTrue.append("!(");
                    for (int i = 0; i < binarySumVariables; i++) {
                        if (i > 0) {
                            allTrue.append(" & ");
                        }
                        allTrue.append("s").append(ltsNumber2).append(i);
                    }
                    allTrue.append(") | ");
                }
            }
            if(allTrue.length()>0){
                allTrue.insert(0, "G(");
                allTrue.setLength(allTrue.length()-2);
                allTrue.append(") && ");
            }
            notErrorState = allTrue.toString();

            // Sys Rule states update
            String sysRuleStateUpdate = sysRuleStateUpdate(transitionsByLts, statesIndexes, labelsByLts);


            String guarantees = sysVariableInit + " & " + notErrorState;
            if(!sysRule1.isEmpty())
                guarantees+= sysRule1;

            if(!sysRule2.isEmpty()){
                if(!sysRule1.isEmpty())
                    guarantees += " & ";

                guarantees += sysRule2 ;
            }
            guarantees += " & " + sysRuleStateUpdate;

            if(l_c_actions.size()>1){
                String sysMutualExclusion = sysMutualExclusionBuilder(l_c_actions);
                //guarantees+= " & " + sysMutualExclusion;
            }

            // Regla LIVENESS
            String asmLiveness = "";
            for (Formula formA : formulasAss){
                asmLiveness += "(G F (" + getFluentsVariableFromFormula(formA) + ")) & ";
            }
            if(!formulasAss.isEmpty()){
                asmLiveness = asmLiveness.substring(0, asmLiveness.length() - 2);
                assumptions += " & ("+asmLiveness+")";
            }

            String garLiveness = "";
            for (Formula formG : formulasGar){
                if(formG!=Formula.TRUE_FORMULA)
                    garLiveness += "(G F (" + getFluentsVariableFromFormula(formG) + ")) & ";
            }
            if(!formulasGar.isEmpty()){
                garLiveness = garLiveness.substring(0, garLiveness.length() - 2);
                guarantees += " & ("+garLiveness+")";
            }

            if(!controllableLabelToBinary.get("invalid").isEmpty()){
                guarantees += "&& G(" + controllableLabelToBinary.get("invalid")+")";
            }
            if(l_e_actions.size()==l_c_actions.size()){ // all actions controllables
                // we have to recommend something
                guarantees += " && G(!(";
                for(String controllableLabelBinary : binaryControllableLabels){
                    guarantees += "!"+controllableLabelBinary+" && ";
                }
                guarantees = guarantees.substring(0, guarantees.length() - 3);
                guarantees += "))";

            }


            /**
             *
             * Assumptions
             *
             **/

            // Mutual Exclusion between variables
            // String envMutualExclusion = envMutualExclusionBuilder(formulasAss, formulasGar, statesIndexes, l_e_actions);

            // Env Rule 5
            String envRule5 = envRule5BuilderBinary(statesIndexes, transitionsByLts, labelsByLts, l_e_actions, l_c_actions, controllableLabelsByLts, sysRule2, sysRule1);

            // Env Rule 6
            String envRule6 = envRule6BuilderBinary(transitionsByLts, statesIndexes, l_e_actions, labelsByLts);

            // Env Rule 7
            // TODO: only supports conjunctions of FluentPropositionalVariable and requires a guarantee
            String envRule7 = envRule7Builder(formulasAss, formulasGar, l_e_actions);

            if(!labelToBinary.get("invalid").isEmpty()){
                assumptions += "G(" + labelToBinary.get("invalid")+") &&";
            }
            // assumptions = envMutualExclusion;
            if(!envRule5.isEmpty())
                assumptions+= envRule5;
            if(!envRule6.isEmpty())
                assumptions+=" & " + envRule6 ;
            if(!envRule7.isEmpty())
                assumptions+=" & " + envRule7;

            String formula = "(" + assumptions + ") -> (" + guarantees + ")";
            return formula;

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Pair<Set<String>, HashMap<Action, String>> getControllableBinaryRepresentationFromLabels(HashSet<Action> labels) {
        HashMap<Action, String> binaryRelation = new HashMap<>();
        Set<String> binaryLabels = new HashSet<>();

        int numLabels = labels.size();
        int numBits = (int) Math.ceil(Math.log(numLabels + 1) / Math.log(2)); // Add 1 to account for skipping 0

        List<String> allBinaryCombinations = new ArrayList<>();
        for (int i = 1; i < (1 << numBits); i++) {
            StringBuilder binaryRepresentation = new StringBuilder();
            for (int bit = 0; bit < numBits; bit++) {
                binaryLabels.add("lc" + bit);
                if (bit > 0) {
                    binaryRepresentation.append(" && ");
                }
                if ((i & (1 << bit)) == 0) {
                    binaryRepresentation.append("!").append("lc").append(bit);
                } else {
                    binaryRepresentation.append("lc").append(bit);
                }
            }
            allBinaryCombinations.add(binaryRepresentation.toString());
        }

// Map each label to its binary representation
        List<Action> labelList = new ArrayList<>(labels);
        Set<String> validBinaryRepresentations = new HashSet<>();
        for (int i = 0; i < numLabels; i++) {
            Action label = labelList.get(i);
            String binaryString = allBinaryCombinations.get(i);
            validBinaryRepresentations.add(binaryString);
            binaryRelation.put(label, binaryString);
        }

// Determine invalid binary representations
        List<String> invalidRepresentations = new ArrayList<>();
        for (String binary : allBinaryCombinations) {
            if (!validBinaryRepresentations.contains(binary)) {
                invalidRepresentations.add("!(" + binary + ")");
            }
        }

        // Join invalid binary representations into a single string
        String invalidBinaryString = String.join(" && ", invalidRepresentations);
        binaryRelation.put((Action) "invalid", invalidBinaryString);
        return new Pair<>(binaryLabels, binaryRelation);
    }


    private Pair<Set<String>, HashMap<Action, String>> getBinaryRepresentationFromLabels(Set<Action> labels) {
        HashMap<Action, String> binaryRelation = new HashMap<>();
        Set<String> binaryLabels = new HashSet<>();

        int numLabels = labels.size();
        int numBits = (int) Math.ceil(Math.log(numLabels) / Math.log(2));

// Generate all possible binary combinations for numBits
        List<String> allBinaryCombinations = new ArrayList<>();
        for (int i = 0; i < (1 << numBits); i++) {
            StringBuilder binaryRepresentation = new StringBuilder();
            for (int bit = 0; bit < numBits; bit++) {
                binaryLabels.add("l" + bit);
                if (bit > 0) {
                    binaryRepresentation.append(" && ");
                }
                if ((i & (1 << bit)) == 0) {
                    binaryRepresentation.append("!").append("l").append(bit);
                } else {
                    binaryRepresentation.append("l").append(bit);
                }
            }
            allBinaryCombinations.add(binaryRepresentation.toString());
        }

// Map each label to its binary representation
        List<Action> labelList = new ArrayList<>(labels);
        Set<String> validBinaryRepresentations = new HashSet<>();
        for (int i = 0; i < numLabels; i++) {
            Action label = labelList.get(i);
            String binaryString = allBinaryCombinations.get(i);
            validBinaryRepresentations.add(binaryString);
            binaryRelation.put(label, binaryString);
        }

// Determine invalid binary representations
        List<String> invalidRepresentations = new ArrayList<>();
        for (String binary : allBinaryCombinations) {
            if (!validBinaryRepresentations.contains(binary)) {
                invalidRepresentations.add("!(" + binary + ")");
            }
        }

        // Join invalid binary representations into a single string
        String invalidBinaryString = String.join(" && ", invalidRepresentations);
        binaryRelation.put((Action) "invalid", invalidBinaryString);

        return new Pair<>(binaryLabels, binaryRelation);

    }

    /**
     *
     * @param labels
     * @return an string representing negation of all l_c_actions
     */
    private String getNoneVar(Set<String> labels) {
        String noneVar = "(";
        for(String controllableAction : labels){
            noneVar += "!" + controllableAction + " & ";
        }
        noneVar = noneVar.substring(0, noneVar.length() - 2);
        noneVar += ")";
        return noneVar;
    }


    // Non-binary
    private String envRule7Builder(HashSet<Formula> formulasAss, HashSet<Formula> formulasGar, HashSet<Action> l_e_actions) throws IOException {
        String assRule = "";
        if(!formulasAss.isEmpty()){
            assRule += "(";
            for (Formula formulaG : formulasAss){
                assRule += envformulasFluentRule7(formulaG, l_e_actions);
            }
            assRule += ") & ";
        }

        String garRule="";
        if(!formulasGar.isEmpty()){
            garRule += "(";
            for (Formula formulaG : formulasGar){
                garRule += envformulasFluentRule7(formulaG, l_e_actions) + " & ";
            }
            garRule = garRule.substring(0, garRule.length() - 2);
            garRule += ")";
        }

        return "(" + assRule + garRule + ")";
    }

    private String envformulasFluentRule7(Formula formG, HashSet<Action> l_e_actions) throws IOException {
        String result = "";
        if (formG instanceof FluentPropositionalVariable) {

            for (Symbol initAction : ((FluentPropositionalVariable) formG).getFluent().getInitiatingActions()){
                String actionBinaryRep = labelToBinary.get(symbolToStringSanitized(initAction));
                result += "G(("+actionBinaryRep+ ") -> ("
                        + getFluentNameSanitized((FluentPropositionalVariable) formG)
                        + ")" + ") & ";
            }
            for (Symbol terminatingAction : ((FluentPropositionalVariable) formG).getFluent().getTerminatingActions()){
                String actionTerminateBinaryRep = labelToBinary.get(symbolToStringSanitized(terminatingAction));
                if(terminatingAction.toString().equals("*")){

                    for (Symbol initAction : ((FluentPropositionalVariable) formG).getFluent().getInitiatingActions()){
                        String actionInitBinaryRep = labelToBinary.get(symbolToStringSanitized(terminatingAction));
                        result += "G ((!("+ actionInitBinaryRep + ")) -> ( !" + getFluentNameSanitized((FluentPropositionalVariable) formG) + ")" + ") & ";
                    }
                }else{
                    result += "G ((" +actionTerminateBinaryRep+") -> ( !" + getFluentNameSanitized((FluentPropositionalVariable) formG) + ")" + ") & ";
                }
            }

            HashSet<Action> actionsNofluent = new HashSet<>(l_e_actions);
            HashSet<Action> actionsToRemove = new HashSet<>();
            for(Symbol initActions : ((FluentPropositionalVariable) formG).getFluent().getInitiatingActions()){
                actionsToRemove.add((Action) initActions.toString().replace(".", ""));
            }
            for(Symbol initActions : ((FluentPropositionalVariable) formG).getFluent().getTerminatingActions()){
                actionsToRemove.add((Action) initActions.toString().replace(".", ""));
            }
            actionsNofluent.removeAll(actionsToRemove);

            for( Action actionWithoutFluent : actionsNofluent ){
                String binaryAction = labelToBinary.get(actionWithoutFluent);
                result += "( G ((X(" +binaryAction+")) & (" + ((FluentPropositionalVariable) formG).getName().replace(".","").toLowerCase().replace("_","").replace("F","").replace(".","") + ")) -> X(" + getFluentNameSanitized((FluentPropositionalVariable) formG) + ")" + ") & ";
                result += "( G ((X(" +binaryAction+")) & (!" + ((FluentPropositionalVariable) formG).getName().replace(".","").toLowerCase().replace("_","").replace("F","").replace(".","") + ")) -> X(!" + getFluentNameSanitized((FluentPropositionalVariable) formG) + ")" + ") & ";
            }
        }
        if(result.length()>0){ result = result.substring(0, result.length() - 2); } else{
            return "true";
        }

        return result;
    }

    private static String getFluentNameSanitized(FluentPropositionalVariable formG) {
        return formG.getFluent().getName().toLowerCase().replace("_", "").replace("F", "").replace(".", "");
    }

    private static String symbolToStringSanitized(Symbol initAction) {
        return initAction.toString().replace(".", "").replace("_", "");
    }

    private <State, Action> String envRule6Builder(HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts, HashMap<Integer, Set<State>> statesIndexes, HashSet<Action> l_e_actions, HashMap<Integer, Set<Action>> labelsByLts) {
        String envRule6 = "";
        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = statesIndexes.get(LTSKey);
            Set<Action> labelsNotInLTS = new HashSet<>(l_e_actions);
            labelsNotInLTS.removeAll(labelsByLts.get(LTSKey));

            for(State stateToCheck : statesLTS){
                for(Action labelNotInLTS : labelsNotInLTS){
                    String binaryRep = labelToBinary.get(labelNotInLTS);
                    envRule6 += "G (s" + LTSKey + stateToCheck + " & X(" + binaryRep + ") -> X(s" + LTSKey  + stateToCheck + ")) & ";
                }
            }
        }
        envRule6 = envRule6.substring(0, envRule6.length() - 2);
        return envRule6;
    }

    private static <State, Action> String sysRule7Builder(HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts, HashMap<Integer, Set<State>> statesIndexes, HashSet<Action> l_e_actions, HashMap<Integer, Set<Action>> labelsByLts) {
        String rule = "";
        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = statesIndexes.get(LTSKey);

            for(State stateToCheck : statesLTS){
                Set<Action> labelsNotInState = new HashSet<>(l_e_actions);
                for(Pair<Action, State> transitionsOfState : transitionsLTS.getValue().get(stateToCheck)){
                    labelsNotInState.remove(transitionsOfState.getFirst());
                }
                for(Action labelNotInLTS : labelsNotInState){
                    String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);
                    rule += "G ((" + binaryRep + " & X l" + labelNotInLTS + ") -> (X " + binaryRep + ")) & ";
                }
            }
        }
        rule = rule.substring(0, rule.length() - 2);
        return rule;
    }

    private <State, Action> String envRule6BuilderBinary(HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts, HashMap<Integer, Set<State>> statesIndexes, HashSet<Action> l_e_actions, HashMap<Integer, Set<Action>> labelsByLts) {
        String envRule6 = "";
        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = statesIndexes.get(LTSKey);
            Set<Action> labelsNotInLTS = new HashSet<>(l_e_actions);
            labelsNotInLTS.removeAll(labelsByLts.get(LTSKey));

            for(State stateToCheck : statesLTS){
                for(Action labelNotInLTS : labelsNotInLTS){
                    String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);
                    String labelBinaryRep = labelToBinary.get(labelNotInLTS);
                    envRule6 += "G ((" + binaryRep + " & X(" + labelBinaryRep + ")) -> (X " + binaryRep + ")) & ";
                }
            }
        }
        if(!envRule6.isEmpty())
            envRule6 = envRule6.substring(0, envRule6.length() - 2);
        return envRule6;
    }

    private <Action> String envRule5Builder(HashSet<Action> l_c_actions) {
        String envRule5 = "";
        for(Action contAction : l_c_actions){
            envRule5 += "G (!lc"+contAction+" -> (X !l"+contAction+")) & ";
        }
        envRule5 = envRule5.substring(0, envRule5.length() - 2);
        return envRule5;
    }
    private <State, Action> String envRule5BuilderBinary(HashMap<Integer, Set<State>> statesIndexes, HashMap<Integer,
                                                                 Map<State, BinaryRelation<Action, State>>> transitionsByLts, HashMap<Integer, Set<Action>> labelsByLts,
                                                         HashSet<Action> l_e_actions, HashSet<Action> l_c_actions, HashMap<Integer, Set<Action>> controllableLabelsByLts, String sysRule2, String sysRule1) {

        String conditionForControllableProblem="";
        if(true){
            conditionForControllableProblem="!(";
            for(String contAction : binaryControllableLabels){
                conditionForControllableProblem+="!" + contAction + " & ";
            }
            conditionForControllableProblem = conditionForControllableProblem.substring(0, conditionForControllableProblem.length() - 2);
            conditionForControllableProblem+=") &&";
        }

        String disableInvalidControllable = "";
        if(!controllableLabelToBinary.get("invalid").isEmpty()){
            disableInvalidControllable += "(" + controllableLabelToBinary.get("invalid")+") &&";
        }

        // if we are in -1 state
        String notErrorState = "";
        StringBuilder allTrue = new StringBuilder();
        for(Integer ltsNumber : transitionsByLts.keySet()){
            if(statesIndexes.get(ltsNumber).contains((long) -1)){
                int binarySumVariables = (int) Math.ceil(Math.log(statesIndexes.get(ltsNumber).size()) / Math.log(2));
                allTrue.append("(!(");
                for (int i = 0; i < binarySumVariables; i++) {
                    if (i > 0) {
                        allTrue.append(" & ");
                    }
                    allTrue.append("s").append(ltsNumber).append(i);
                }
                allTrue.append(")) | ");
            }
        }
        if(allTrue.length()>0){
            allTrue.insert(0, "(");
            allTrue.setLength(allTrue.length()-2);
            allTrue.append(") && ");
        }
        notErrorState = allTrue.toString();



        String envRule5 = " G ";
        if(!sysRule2.isEmpty() || !sysRule1.isEmpty()){
            if(!sysRule2.isEmpty() && !sysRule1.isEmpty())
                envRule5 += "(( "+ notErrorState + conditionForControllableProblem + disableInvalidControllable + "(" + sysRule1.substring(2).replace("G","") + ") && (" +  sysRule2.substring(2) + ")) -> (";
            if(!sysRule2.isEmpty() && sysRule1.isEmpty())
                envRule5 += "(( " + conditionForControllableProblem + disableInvalidControllable + " ("+ sysRule2.substring(2) + ")) -> (";
            if(sysRule2.isEmpty() && !sysRule1.isEmpty())
                envRule5 += "(( " + conditionForControllableProblem + disableInvalidControllable + " (" + sysRule1.substring(2).replace("G","") + ")) -> (";

        }


        // For controllable actions
        for(Action contAction : l_c_actions){
            String binaryRepAction = labelToBinary.get(contAction);
            String binaryRepControllableAction = controllableLabelToBinary.get(contAction);
            if(binaryRepAction==null || binaryRepControllableAction==null){
                System.currentTimeMillis();
            }
            envRule5 += "(( (X ("+binaryRepAction+")) -> ("+binaryRepControllableAction+"))) & ";
        }
        envRule5 = envRule5.substring(0, envRule5.length() - 2);
        envRule5 += ")) & ";

        // For uncontrollable actions
        Set<Action> uncontrollableActions = new HashSet<>(l_e_actions);

        Set<String> l_c_actions_renamed = l_c_actions.stream()
                .map(s -> s.toString().replace(".", ""))
                .collect(Collectors.toSet());
        //uncontrollableActions.removeAll(l_c_actions_renamed);

        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = new HashSet<>(statesIndexes.get(LTSKey));
            Set<Action> controllableLabelsInLts = controllableLabelsByLts.get(LTSKey);
            for(State stateToCheck : statesLTS){
                if((long) stateToCheck!=-1){

                    Set<Action> outgoingTransitionsFromState = getOutgoingTransitionsFromState(stateToCheck, transitionsLTS);
                    Set<Action> invalidTransitionsFromState = new HashSet<>(labelsByLts.get(LTSKey));
                    Set<Action> updatedinvalidTransitionsFromState = (Set<Action>) invalidTransitionsFromState.stream()
                            .map(s -> s.toString().replace(".", ""))
                            .collect(Collectors.toSet());
                    updatedinvalidTransitionsFromState.removeAll(outgoingTransitionsFromState);


                    /*invalidTransitionsFromState.removeAll(l_c_actions_renamed);
                    outgoingTransitionsFromState.removeAll(l_c_actions_renamed);
    */
                    String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);

                    for(Action validLabel : outgoingTransitionsFromState){
                        // envRule5 += "G(((X l" +validLabel.toString().replace(".","") + ") & ("+binaryRep+")) -> true) & ";
                    }
                    for(Action invalidLabel : updatedinvalidTransitionsFromState){
                        String binaryRepLabel = labelToBinary.get(invalidLabel);
                        if(binaryRepLabel=="null"){
                            System.currentTimeMillis();
                        }
                        envRule5 += "G(!((X (" +binaryRepLabel+ ")) & ("+binaryRep+"))) & ";
                    }
                }

            }
        }

        envRule5 = envRule5.substring(0, envRule5.length() - 2);
        return envRule5;
    }

    private static <Action, State> Set<Action> getOutgoingTransitionsFromState(State stateToCheck, Map.Entry<Integer, Map<State, BinaryRelation<Action,State>>> transitionsLTS) {
        Set<Action> outgoingTransitions = new HashSet<>();
        for(Pair<Action, State> transitionInState : transitionsLTS.getValue().get(stateToCheck)){
            outgoingTransitions.add((Action) transitionInState.getFirst().toString().replace(".",""));
        }
        return outgoingTransitions;
    }

    private static <State, Action> String envRule4Builder(HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts, HashMap<Integer, Set<State>> statesIndexes, HashMap<Integer, Set<Action>> labelsByLts) {
        String envRule4 = "";
        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = statesIndexes.get(LTSKey);
            Set<Action> labelsLTS = labelsByLts.get(LTSKey);
            for(Map.Entry<State, BinaryRelation<Action, State>> transition : transitionsLTS.getValue().entrySet()){
                State stateToCheck = transition.getKey();
                for(Action labelToCheck : labelsLTS){
                    if(labelToCheck!="tau"){
                        boolean enabledLabel = false;
                        for(Pair<Action, State> transitionRelation : transition.getValue()){
                            if(!transitionRelation.getSecond().toString().equals("-1")
                                    && labelToCheck.equals(transitionRelation.getFirst().toString().replace(".",""))){
                                // Agrego etiqueta de transición
                                enabledLabel = true;
                                envRule4 += "G (s"+LTSKey+stateToCheck + " & " + "X(l"+ labelToCheck +") -> X(s"+LTSKey + transitionRelation.getSecond() + ")) & ";
                            }
                        }
                        if(!enabledLabel){
                            // Regla de False
                            envRule4 += "(s"+LTSKey+stateToCheck + " & " + "X(l"+ labelToCheck +") -> false) & ";
                        }
                    }
                }
            }
        }
        envRule4 = envRule4.substring(0, envRule4.length() - 2);
        return envRule4;
    }

    private static <State, Action> String
    getBinaryRepresentationFrom(Integer ltsNumber,
                                State stateToCheck,
                                HashMap<Integer, Set<State>> statesIndexes){

        // it must return an string with binary representation of stateToCheck for the ltsNumber
        // for example, for an LTS (with number 2) with 12 states we will have 4 variables (S20, S21, S22, S23)
        // also, state 4 will be represented as "!S20 & !S21 & S22 & S23" (0100)
        long number = (long) stateToCheck;
        StringBuilder binary = new StringBuilder();
        int indexOfVariable = 0;
        int binarySumVariables = (int) Math.ceil(Math.log(statesIndexes.get(ltsNumber).size()) / Math.log(2));

        // Check if the stateToCheck is -1
        if (((long)stateToCheck)  == -1) {
            StringBuilder allTrue = new StringBuilder();
            for (int i = 0; i < binarySumVariables; i++) {
                if (i > 0) {
                    allTrue.append(" & ");
                }
                allTrue.append("s").append(ltsNumber).append(i);
            }
            return "(" + allTrue.toString() + ")";
        }

        while (number > 0) {
            String variableToInsert = (number % 2)==0 ? "!s"+ltsNumber+indexOfVariable : "s"+ltsNumber+indexOfVariable;
            binary.append(variableToInsert);
            number /= 2;
            if(number >0){
                binary.append( " & ");
            }
            indexOfVariable++;
        }
        while(indexOfVariable < (binarySumVariables)){
            if(!(binary.length() ==0))
                binary.append(" & ");
            binary.append("!s"+ltsNumber+indexOfVariable);
            indexOfVariable++;
        }

        return "(" + binary.toString() + ")";
    }

    private <State, Action> String
    sysRuleStateUpdate(HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts,
                       HashMap<Integer, Set<State>> statesIndexes,
                       HashMap<Integer, Set<Action>> labelsByLts) {
        String rule = "";
        for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()){
            Integer LTSKey = transitionsLTS.getKey();
            Set<State> statesLTS = statesIndexes.get(LTSKey);
            Set<Action> labelsLTS = labelsByLts.get(LTSKey);
            for(Map.Entry<State, BinaryRelation<Action, State>> transition : transitionsLTS.getValue().entrySet()){
                State stateToCheck = transition.getKey();
                for(Action labelToCheck : labelsLTS){
                    if(labelToCheck!="tau"){
                        boolean enabledLabel = false;
                        for(Pair<Action, State> transitionRelation : transition.getValue()){
                            if(!transitionRelation.getSecond().toString().equals("-1")
                                    && labelToCheck.equals(transitionRelation.getFirst().toString().replace(".",""))){
                                // Agrego etiqueta de transición
                                enabledLabel = true;
                                String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);
                                String binaryRepDst = getBinaryRepresentationFrom(LTSKey, transitionRelation.getSecond(), statesIndexes);
                                String binaryLabel = labelToBinary.get(labelToCheck);
                                rule += "G ( (("+ binaryRep + " & " + "(X ("+binaryLabel+")))) -> (X " + binaryRepDst + " )) & ";
                            }
                        }
                        if(!enabledLabel){
                            // Regla de False
                            String binaryRep = getBinaryRepresentationFrom(LTSKey, stateToCheck, statesIndexes);
                            // envRule4 += "G (("+ binaryRep + " & " + "(X l"+ labelToCheck +")) -> false) & ";
                        }
                    }
                }
            }
        }
        rule = rule.substring(0, rule.length() - 2);
        return rule;
    }



    private static <State> String envVariableInit(HashSet<Formula> formulasAss, HashSet<Formula> formulasGar, HashMap<Integer, Set<State>> statesIndexes) {
        // 1) S_{x}_{i} (x: automaton, i: number of state)
        String variable_init_lts = "(";
        for(Map.Entry<Integer, Set<State>> indexState : statesIndexes.entrySet()){
            Integer lts_number = indexState.getKey();
            Iterator<State> stateIt = indexState.getValue().iterator();
            variable_init_lts += "s" + lts_number + "0 & ";
        }
        variable_init_lts = variable_init_lts.substring(0, variable_init_lts.length() - 3);
        variable_init_lts += ")";

        // 3) Fl_{i}
        String variable_init_ass="";
        String variable_init_gar="";

        if(!formulasAss.isEmpty()){
            variable_init_ass = initFluentsOf(formulasAss) + " & ";
        }
        if(!formulasGar.isEmpty()){
            variable_init_gar = initFluentsOf(formulasGar);
        }
        return "(" + variable_init_lts + " & " + variable_init_ass + variable_init_gar + ")";
    }

    private static <State> String envVariableInitBinary(HashSet<Formula> formulasAss, HashSet<Formula> formulasGar, HashMap<Integer, Set<State>> statesIndexes) {
        // 1) S_{x}_{i} (x: automaton, i: number of state)
        String variable_init_lts = "(";
        for(Map.Entry<Integer, Set<State>> indexState : statesIndexes.entrySet()){ // for each LTS
            Integer lts_number = indexState.getKey();
            int binarySumVariables = (int) Math.ceil(Math.log(indexState.getValue().size()) / Math.log(2));
            for(int binaryVar=0; binaryVar<binarySumVariables; binaryVar++){
                variable_init_lts += "!s" + lts_number + binaryVar + " & ";
            }
        }
        variable_init_lts = variable_init_lts.substring(0, variable_init_lts.length() - 3);
        variable_init_lts += ")";

        // 3) Fl_{i}
        String variable_init_ass="";
        String variable_init_gar="";

        if(!formulasAss.isEmpty()){
            variable_init_ass = initFluentsOf(formulasAss) + " & ";
        }
        if(!formulasGar.isEmpty()){
            variable_init_gar = initFluentsOf(formulasGar);
        }
        return "(" + variable_init_lts + ")";
    }

    private static String initFluentsOf(HashSet<Formula> formulasAss) {
        String variable_init_ass = "";
        if(!formulasAss.isEmpty()) {
            Iterator<Formula> formIt = formulasAss.iterator();
            variable_init_ass = "(!" + formIt.next().toString().toLowerCase().replace("_","").replace("F","");
            while(formIt.hasNext()){
                variable_init_ass += " && !" + formIt.next().toString().toLowerCase().replace("_","").replace("F","");
            }
            variable_init_ass += ")";
        }
        return variable_init_ass;
    }

    private String envMutualExclusionBuilder(HashSet<Formula> formulasAss, HashSet<Formula> formulasGar, HashMap<Integer, Set<State>> statesIndexes, HashSet<Action> l_e_actions) {
        String mutual_exclusion = "";

        // 1) L_{l} (l: labels in sigma)
        // Imprimo acciones L_e
        String mutualExc = generateExactlyOneTrueFormula(l_e_actions, true,"l");
        mutual_exclusion += "G(" +mutualExc+ ")";
        return mutual_exclusion;
    }

    private String sysMutualExclusionBuilder(HashSet<Action> actions) {

        String mutual_exclusion = "";

        // 1) L_{l} (l: labels in sigma)
        // Imprimo acciones L_e
        //actions.add((Action) "none");
        String mutualExc = generateExactlyOneTrueFormula(actions, true, "lc");
        mutual_exclusion += "(" +mutualExc+ ")";
        return mutual_exclusion;
    }


    private String generateExactlyOneTrueFormula(HashSet<Action> variables, Boolean onlyOne, String prefix) {
        StringBuilder formula = new StringBuilder();
        boolean isFirst = true;

        // Part 1: Mutual exclusion
        for (Action var1 : variables) {
            for (Action var2 : variables) {
                if (!var1.equals(var2)) {
                    if (!isFirst) {
                        formula.append(" && ");
                    }
                    formula.append("!(").append(prefix+var1).append(" && ").append(prefix+var2).append(")");
                    isFirst = false;
                }
            }
        }

        // Part 2: At least one is true
        if(onlyOne){
            StringBuilder atLeastOneTrue = new StringBuilder();
            isFirst = true;
            for (Action var : variables) {
                if (!isFirst) {
                    atLeastOneTrue.append(" || ");
                }
                atLeastOneTrue.append(prefix+var);
                isFirst = false;
            }
            // Combine mutual exclusion and at least one true condition
            if (formula.length() > 0) {
                formula.append(" && ");
            }
            formula.append("(").append(atLeastOneTrue).append(")");
        }

        return formula.toString();
    }



    private String getFluentsVariableFromFormula(Formula formG) throws IOException {
        if (formG instanceof FluentPropositionalVariable) {
            return ((FluentPropositionalVariable) formG).getName().toString().toLowerCase().replace("_","").replace("F","").replace(".","");
        }else if(formG instanceof AndFormula){
            String left = getFluentsVariableFromFormula(((AndFormula) formG).getLeftFormula());
            String right = getFluentsVariableFromFormula(((AndFormula) formG).getRightFormula());
            return "("+left+" & "+right+")";
        }else if(formG instanceof OrFormula){
            String left = getFluentsVariableFromFormula(((OrFormula) formG).getLeftFormula());
            String right = getFluentsVariableFromFormula(((OrFormula) formG).getRightFormula());
            return "("+left+" | "+right+")";
        }
        return "";
    }


    public String getVariablesOfCommandForStrix(List<LTS<Long, String>> ltss, Set<String> controllable, HashSet<Formula> formulasAss, HashSet<Formula> formulasGar) {
        Iterator<LTS<Long, String>> it = ltss.iterator();
        LTS<State, Action> lts;
        Integer ltsNumber = 0;

        HashSet<String> l_e_actions = new HashSet<>();
        HashSet<String> l_c_actions = new HashSet<>();

        HashMap<Integer, State> initialStates = new HashMap<>();
        HashMap<Integer, Set<State>> statesIndexes = new HashMap<>();
        HashMap<Integer, Set<Action>> labelsByLts = new HashMap<>();
        HashMap<Integer, Set<Action>> controllableLabelsByLts = new HashMap<>();
        HashMap<Integer, Set<Action>> uncontrollableLabelsByLts = new HashMap<>();

        HashMap<Integer, Integer> statesByLTS = new HashMap<Integer, Integer>();

        HashMap<Integer, Map<State, BinaryRelation<Action, State>>> transitionsByLts = new HashMap<>();
        Set<String> uncontrollable = null;

        // Itero entre los LTS de la composición.
        Integer idLTS=0;
        while (it.hasNext()){

            lts = (LTS<State, Action>) it.next();
            statesByLTS.put(idLTS, lts.getStates().size());
            idLTS++;

            uncontrollable = new HashSet<>();
            for(Action action : lts.getActions()){
                uncontrollable.add(action.toString());
            }
            Set<String> finalControllable = controllable;
            uncontrollable.removeIf(a -> finalControllable.contains(a));

            Set<String> cleanUncontrollable = new HashSet<>();
            Set<String> cleanControllable = new HashSet<>();

            for (String uAction : uncontrollable){
                cleanUncontrollable.add(uAction.toString().replace(".", ""));
            }
            for (String cAction : controllable){
                cleanControllable.add((String) cAction.toString().replace(".", ""));
            }

            uncontrollable = cleanUncontrollable;
            controllable = cleanControllable;

            // VARIABLE L_e
            l_e_actions.addAll(controllable);
            l_e_actions.addAll(uncontrollable);
            l_e_actions.removeIf(a -> a.equals("tau"));
            // VARIABLE L_c
            l_c_actions.addAll(controllable);

            String classN = lts.getClass().getSimpleName();
            if(classN.equals("LTSAdapter")){

                // Agrego estado inicial de los LTS que representan la planta y safety.
                initialStates.put(ltsNumber, lts.getInitialState());

                // Escribo las variables de estado del autómata
                statesIndexes.put(ltsNumber, lts.getStates());

                // Para cada estado
                // Map<State, BinaryRelation<Action, State>> transitionsLTS = lts.getTransitions();
                transitionsByLts.put(ltsNumber, lts.getTransitions());

                Set<Action> ltsActions = new HashSet<>();
                for(Action actionOfLts : lts.getActions()){
                    if(actionOfLts!="tau"){
                        ltsActions.add((Action) actionOfLts.toString().replace(".", ""));
                    }
                }
                labelsByLts.put(ltsNumber, ltsActions);

                Set<Action> controllableLabels = new HashSet<>();
                Set<Action> uncontrollableLabels = new HashSet<>();

                for(Action actionCheck : lts.getActions()){
                    if(controllable.contains(actionCheck.toString().replace(".", ""))){
                        controllableLabels.add((Action) actionCheck.toString().replace(".", ""));
                    }else if(uncontrollable.contains(actionCheck.toString().replace(".", ""))){
                        uncontrollableLabels.add((Action) actionCheck.toString().replace(".", ""));
                    }
                }
                allUncontrollableLabels.addAll(uncontrollableLabels);
                controllableLabelsByLts.put(ltsNumber, controllableLabels);
                uncontrollableLabelsByLts.put(ltsNumber, uncontrollableLabels);

            }
            ltsNumber++;
        }
        // Get input variables
        String inputVariables = "--ins=";

        Iterator<String> actionsIt = binaryLabels.iterator();
        while(actionsIt.hasNext()){
            inputVariables +=  actionsIt.next()+",";
        }

        if(!formulasAss.isEmpty()) {
            Iterator<Formula> formIt = formulasAss.iterator();
            inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
            while(formIt.hasNext()){
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
            }
        }
        if(!formulasGar.isEmpty()) {
            formulasGar.removeIf(f -> f.toString().toLowerCase().replace("_","").replace("F","").equals("true"));
            Iterator<Formula> formIt = formulasGar.iterator();
            inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","") +",";
            while(formIt.hasNext()){
                inputVariables += formIt.next().toString().toLowerCase().replace("_","").replace("F","").replace(".","")+",";
            }
        }

        // Get output variables
        String outputVariables = "--outs=";
        Iterator<String> actionsContIt = binaryControllableLabels.iterator();
        while(actionsContIt.hasNext()){
            outputVariables +=  actionsContIt.next()+",";
        }
        for(Map.Entry<Integer, Set<State>> indexState : statesIndexes.entrySet()){
            Integer lts_number = indexState.getKey();
            int statesSum = indexState.getValue().size();
            int binarySumVariables = (int) Math.ceil(Math.log(statesSum) / Math.log(2));

            for(int k=0; k<binarySumVariables; k++){
                outputVariables += "s" + lts_number + "" + k + ",";
            }
        }

        String variablesOfStrix = inputVariables + " " + outputVariables;
        return variablesOfStrix;

    }

}