package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.TranslateToRS;

import MTSSynthesis.ar.dc.uba.model.condition.*;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.HAction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Status;
import ltsa.lts.MyHashQueue;

import java.util.*;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TranslateToSpectra<State, Action> {

    private FileWriter writerFile;
    private HashSet<FluentPropositionalVariable> fluentsG = new HashSet<FluentPropositionalVariable>();
    private HashSet<String> allUncontrollableLabels  = new HashSet<>();
    private Set<String> processedFluents = new HashSet<>();

    public void translate(List<MTS<Long, String>> ltss,
                          Set controllable,
                          List<Formula> formulasAss, List<Formula> formulasGar) {
        System.out.print("Translating ..");
        File myObj = new File("/tmp/"+"filename"+".spectra");

        try {
            if (myObj.createNewFile()) {}
            FileWriter myWriter = new FileWriter(myObj);
            this.writerFile = myWriter;


        }catch (IOException e) {
            System.out.println("An error occurred.");
            e.printStackTrace();
        }

        try {
            writeSpecTitle("pruebaLTS");

            Iterator<MTS<Long, String>> it = ltss.iterator();
            MTS<Long, String> lts;
            Integer ltsNumber = 0;

            Set<String> l_e_actions = new HashSet<>();
            Set<String> l_c_actions = new HashSet<>();

            HashMap<Integer, Long> initialStates = new HashMap<>();
            HashMap<Integer, Set<Long>> statesIndexes = new HashMap<>();
            HashMap<Integer, Set<String>> labelsByLts = new HashMap<>();
            HashMap<Integer, Set<String>> controllableLabelsByLts = new HashMap<>();
            HashMap<Integer, Set<String>> uncontrollableLabelsByLts = new HashMap<>();

            HashMap<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsByLts = new HashMap<>();
            HashSet<String> uncontrollable = null;

            // Itero entre los LTS de la composición.
            while (it.hasNext()){
                lts = it.next();
                uncontrollable = new HashSet<>(lts.getActions());
                Set<String> finalControllable = controllable;
                uncontrollable.removeIf(a -> finalControllable.contains(a));

                HashSet<String> cleanUncontrollable = new HashSet<>();
                Set<String> cleanControllable = new HashSet<>();

                for (String uAction : uncontrollable){
                    cleanUncontrollable.add(uAction.toString().replace(".", ""));
                }
                for (Object cAction : controllable){
                    cleanControllable.add(getCleanStringAction(cAction));
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
                if(classN.equals("MTSImpl")){

                    // Agrego estado inicial de los LTS que representan la planta y safety.
                    initialStates.put(ltsNumber, lts.getInitialState());

                    // Escribo las variables de estado del autÃ³mata
                    statesIndexes.put(ltsNumber, lts.getStates());

                    // Para cada estado
                    // Map<State, BinaryRelation<Action, State>> transitionsLTS = lts.getTransitions();
                    transitionsByLts.put(ltsNumber, lts.getTransitions(MTS.TransitionType.REQUIRED));

                    Set<String> ltsActions = new HashSet<>();
                    for(String actionOfLts : lts.getActions()){
                        if(actionOfLts!="tau"){
                            ltsActions.add(getCleanStringAction(actionOfLts));
                        }
                    }
                    labelsByLts.put(ltsNumber, ltsActions);

                    Set<String> controllableLabels = new HashSet<>();
                    Set<String> uncontrollableLabels = new HashSet<>();

                    for(String actionCheck : lts.getActions()){
                        if(controllable.contains(getCleanStringAction(actionCheck))){
                            controllableLabels.add(getCleanStringAction(actionCheck));
                        }else if(uncontrollable.contains(getCleanStringAction(actionCheck))){
                            uncontrollableLabels.add(getCleanStringAction(actionCheck));
                        }
                    }

                    allUncontrollableLabels.addAll(uncontrollableLabels);
                    controllableLabelsByLts.put(ltsNumber, controllableLabels);
                    uncontrollableLabelsByLts.put(ltsNumber, uncontrollableLabels);


                }
                ltsNumber++;
            }

            /******************************************/
            // Imprimo acciones L_e
            writerFile.write("// Environment variables \n");
            writerFile.write("env { " + String.join(", ", l_e_actions) + " } l_e; \n");

            // Seteo las variables que representan a los estados de cada LTS
            for (Map.Entry<Integer, Set<Long>> entry : statesIndexes.entrySet()) {
                String states = entry.getValue().stream()
                        .map(state -> "s" + state)
                        .collect(Collectors.joining(", "));
                writerFile.write("env { " + states + "} states_" + entry.getKey() + ";\n");
            }


            // Imprimo acciones L_c
            writerFile.write("// System variables \n");
            writerFile.write("sys { None, " + String.join(", ", l_c_actions) + " } l_c; \n");

            // Imprimo estados iniciales
            for(Map.Entry<Integer, Long> initState : initialStates.entrySet()) {
                writerFile.write("asm ini states_" + initState.getKey() + " = s" + initState.getValue() + "; \n");
            }

            // Imprimo l_e con cualquier valor inicial
            writerFile.write("asm ini l_e=" + l_e_actions.iterator().next() + "; \n");

            // Agarro fórmulas Assumptions y extraigo todos sus fluents

            for(Formula formG : formulasAss){
                if (formG instanceof FluentPropositionalVariable) {
                    fluentsG.add(((FluentPropositionalVariable) formG));
                }
                writeEnvVarFluent(formG);
            }
            // Agarro fórmulas Guarantees y extraigo todos sus fluents
            for(Formula formG : formulasGar){
                if (formG instanceof FluentPropositionalVariable) {
                    fluentsG.add(((FluentPropositionalVariable) formG));
                }
                writeEnvVarFluent(formG);
            }

            // Estados iniciales del sistema
            boolean initialWithControllable = false;
            boolean initialWithUncontrollable = false;
            writerFile.write("// System initial states");
            for(Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS :
                    transitionsByLts.entrySet()) {
                Integer LTSKey = transitionsLTS.getKey();
                Long initialStateLTS = ltss.get(LTSKey).getInitialState();
                Set<String> uncontrollableLabelsInLts = uncontrollableLabelsByLts.get(LTSKey);

                BinaryRelation<String, Long> transitionsFromInitial = transitionsLTS.getValue().get(initialStateLTS);

                if (transitionsFromInitial != null) {
                    for (Pair<String, Long> transition : transitionsFromInitial) {
                        if(isCompositionTransition(LTSKey, transition.getFirst(), transitionsByLts, labelsByLts)){
                            if (uncontrollableLabelsInLts.contains(transition.getFirst().replace(".",""))) {
                                // Me fijo si es una transición posible en la composición
                                initialWithUncontrollable = true;
                            }else{
                                initialWithControllable = true;
                            }
                        }
                    }
                }
            }

            // Set l_c initial values if it is needed
            if(initialWithControllable && !initialWithUncontrollable){
                writerFile.write("// Controllable initial states\n");
                writerFile.write("gar ini l_c!=None; \n");
            } else if (!initialWithControllable && initialWithUncontrollable) {
                writerFile.write("// Unontrollable initial states\n");
                writerFile.write("gar ini l_c=None; \n");
            }


            // Evolución de los estados una vez que el ambiente eligió la etiqueta a tomar.
            writerFile.write("// Regla 4 -> Env");
            writeLine();
            for (Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS : transitionsByLts.entrySet()) {
                Integer LTSKey = transitionsLTS.getKey();
                Set<String> labelsLTS = labelsByLts.get(LTSKey);
                Map<Long, BinaryRelation<String, Long>> ltsTransitions = transitionsLTS.getValue();

                for (Map.Entry<Long, BinaryRelation<String, Long>> transition : ltsTransitions.entrySet()) {
                    Long stateFrom = transition.getKey();
                    BinaryRelation<String, Long> relations = transition.getValue();

                    // --- Paso 1: calcular etiquetas habilitadas ---
                    Set<String> enabledLabels = new HashSet<>();
                    for (Pair<String, Long> rel : relations) {
                        String label = rel.getFirst().replace(".", "");
                        enabledLabels.add(label);

                        String targetStr = rel.getSecond().toString();
                        if ("-1".equals(targetStr)) {
                            targetStr = "e";  // estado especial
                        } else {
                            targetStr = "s" + targetStr;
                        }

                        // Regla de transición válida
                        writerFile.write(
                                "asm always (states_" + LTSKey + "=s" + stateFrom +
                                        " & next(l_e=" + label + ")) -> next(states_" + LTSKey + "=" + targetStr + ");"
                        );
                        writeLine();
                    }

                    // --- Paso 2: calcular etiquetas prohibidas ---
                    Set<String> disabledLabels = new HashSet<>(labelsLTS);
                    disabledLabels.remove("tau");
                    disabledLabels.removeAll(enabledLabels);

                    if (!disabledLabels.isEmpty()) {
                        // Construyo la conjunción l_e!=a & l_e!=b & ...
                        StringJoiner joiner = new StringJoiner(" & ");
                        for (String lbl : disabledLabels) {
                            joiner.add("l_e!=" + lbl);
                        }

                        writerFile.write(
                                "asm always (states_" + LTSKey + "=s" + stateFrom +
                                        " -> next(" + joiner.toString() + "));"
                        );
                        writeLine();
                    }
                }
            }


            // La decisi´on que toma el ambiente es una transici´on no contro-
            //lable o una transici´on controlable que haya propuesto el sistema
            writeLine();
            for(String contActions : l_c_actions){
                writerFile.write("asm always (next(l_e=" + contActions +") -> l_c=" + contActions + ");");
                writeLine();
            }

//
           // Si un aut´omata no contiene en su alfabeto la etiqueta que se
           // decide tomar, entonces no cambia de estado
            writeLine();
            for (Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS : transitionsByLts.entrySet()) {
                Integer LTSKey = transitionsLTS.getKey();
                Set<Long> statesLTS = statesIndexes.get(LTSKey);
                Set<String> labelsNotInLTS = new HashSet<>(l_e_actions);
                labelsNotInLTS.removeAll(labelsByLts.get(LTSKey));

                if (!labelsNotInLTS.isEmpty()) {
                    // Construyo la disyunción de acciones
                    StringJoiner joiner = new StringJoiner(" | ");
                    for (String label : labelsNotInLTS) {
                        joiner.add("l_e=" + label);
                    }

                    for (Long stateToCheck : statesLTS) {
                        writerFile.write(
                                "asm always (states_" + LTSKey + "=s" + stateToCheck +
                                        " & next(" + joiner.toString() + ")) -> next(states_" + LTSKey + "=s" + stateToCheck + ");"
                        );
                        writeLine();
                    }
                }
            }

            writeLine();
            for (Formula formulaG : formulasAss ){
                writeFluentsIn(formulaG, l_e_actions);
            }
            writeLine();
            for (Formula formulaG : formulasGar ){
                writeFluentsIn(formulaG, l_e_actions);
            }

            // Regla de garantÃ­as del sistema
            // Regla 1
            writeLine();
            for (Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS : transitionsByLts.entrySet()) {
                Integer LTSKey = transitionsLTS.getKey();
                Set<Long> statesLTS = new HashSet<>(statesIndexes.get(LTSKey));
                Set<String> controllableLabelsInLts = controllableLabelsByLts.get(LTSKey);

                for (Long stateToCheck : statesLTS) {
                    Set<String> disabled = new HashSet<>();

                    // calcular acciones controlables que no tienen transición en este estado
                    for (String controllableAction : controllableLabelsInLts) {
                        boolean hasTransition = false;
                        for (Pair<String, Long> transitionInState : transitionsLTS.getValue().get(stateToCheck)) {
                            if (!transitionInState.getSecond().toString().equals("-1")
                                    && controllableAction.equals(transitionInState.getFirst().replace(".", ""))) {
                                hasTransition = true;
                                break;
                            }
                        }
                        if (!hasTransition) {
                            disabled.add(controllableAction);
                        }
                    }

                    // escribir una sola regla con conjunción
                    if (!disabled.isEmpty()) {
                        StringJoiner joiner = new StringJoiner(" & ");
                        for (String lbl : disabled) {
                            joiner.add("l_c!=" + lbl);
                        }

                        writerFile.write(
                                "gar always (states_" + LTSKey + "=s" + stateToCheck +
                                        " -> (" + joiner.toString() + "));"
                        );
                        writeLine();
                    }
                }
            }

            // SÃ³lo puede proponer None cuando hay una no controlable en ese estado.
            Map<Integer, Set<Long>> statesWithOnlyControllableByLTS = new HashMap<>();
            for(Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS :
                    transitionsByLts.entrySet()){
                statesWithOnlyControllableByLTS.put(transitionsLTS.getKey(), new HashSet<>());
                for(Map.Entry<Long, BinaryRelation<String, Long>> entry : transitionsLTS.getValue().entrySet()){
                    Long stateNumber = entry.getKey();
                    BinaryRelation<String, Long> transitionsFromState = entry.getValue();
                    Iterator<Pair<String, Long>> transitionsFromStateIt = transitionsFromState.iterator();
                    Boolean stateHasUncontrollable = false;
                    Set<String> controllableActions = controllable;

                    while(transitionsFromStateIt.hasNext()){
                        if(!controllableActions.contains(transitionsFromStateIt.next().getFirst().replace(".", ""))){
                            stateHasUncontrollable = true;
                            break;
                        }
                    }
                    if(!stateHasUncontrollable){
                        statesWithOnlyControllableByLTS.get(transitionsLTS.getKey()).add(stateNumber);
//                        writerFile.write("gar always states_" + transitionsLTS.getKey() + "=s" + stateNumber + " -> l_c!=None;");
//                        writeLine();
                    }
                }
            }

            StringBuilder rule2Gar = new StringBuilder();
            boolean firstClause = true;

            rule2Gar.append("gar always (");

            for (Map.Entry<Integer, Set<Long>> mapStates : statesWithOnlyControllableByLTS.entrySet()) {
                Integer ltsKey = mapStates.getKey();
                Set<Long> statesWithOnlyCont = mapStates.getValue();

                if (!statesWithOnlyCont.isEmpty()) {
                    if (!firstClause) {
                        rule2Gar.append(" & ");
                    } else {
                        firstClause = false;
                    }

                    rule2Gar.append("(");
                    Iterator<Long> itStatesWithOnlyCont = statesWithOnlyCont.iterator();
                    while (itStatesWithOnlyCont.hasNext()) {
                        rule2Gar.append("(states_").append(ltsKey).append("=s").append(itStatesWithOnlyCont.next()).append(")");
                        if (itStatesWithOnlyCont.hasNext()) {
                            rule2Gar.append(" | ");
                        }
                    }
                    rule2Gar.append(")");
                }
            }

            rule2Gar.append(" -> (l_c!=None));");
            writerFile.write(rule2Gar.toString());
            writeLine();

                        // Regla LIVENESS
            writerFile.write("//// *************************\n" +
                    "//// Regla Liveness");

            writeLine();

            for (Formula formA : formulasAss){
                writerFile.write("asm alwEv (");
                writeFormula(formA);
                writerFile.write(");");
                writeLine();
            }

            for (Formula formG : formulasGar){
                writerFile.write("gar alwEv (");
                writeFormula(formG);
                writerFile.write(");");
                writeLine();
            }

            writerFile.close();
            replaceAll("/tmp/"+"filename"+".spectra", ".", "");
            replaceAll("/tmp/"+"filename"+".spectra", "-1", "e");
            replaceAll("/tmp/"+"filename"+".spectra", "or ;", ";");
            System.currentTimeMillis();


//            Map<Action, Map<Integer, Set<State>>> rule_info = new HashMap<>();
//
//            for(Action uncontrollableAction : allUncontrollableLabels){ // Para toda l \in Sigma_u
//                rule_info.put(uncontrollableAction, new HashMap<>());
//
//                for(Map.Entry<Integer, Map<State, BinaryRelation<Action, State>>> transitionsLTS : transitionsByLts.entrySet()) { // Para todo x \in Aut
//                    Integer LTSKey = transitionsLTS.getKey();
//                    Map<Integer, Set<State>> LTS_StatesFromUnc = rule_info.get(uncontrollableAction);
//
//                    if(uncontrollableLabelsByLts.get(LTSKey).contains(uncontrollableAction)){
//                        LTS_StatesFromUnc.put(LTSKey, new HashSet<>());
//                        Map<State, BinaryRelation<Action, State>> transitionOfLTS = transitionsLTS.getValue();
//                        for(Map.Entry<State, BinaryRelation<Action, State>> transition : transitionOfLTS.entrySet()){
//                            for(Pair<Action, State> transitionDest : transition.getValue()){
//                                if(transitionDest.getFirst().toString().replace(".","").equals(uncontrollableAction)){
//                                    Set<State> conjStates = LTS_StatesFromUnc.get(LTSKey);
//                                    conjStates.add(transition.getKey());
//                                }
//                            }
//                        }
//                        if(LTS_StatesFromUnc.get(LTSKey).isEmpty()){
//                            LTS_StatesFromUnc.remove(LTSKey, new HashSet<>());
//                        }
//
//                    }
//                }
//
//            }
//
//
//            rule_info.remove("tau");
//
//            if(!rule_info.isEmpty()) {
//                writerFile.write(("gar always l_c=None -> "));
//                Integer cantLabels = 0;
//                for (Map.Entry<Action, Map<Integer, Set<State>>> eachRule : rule_info.entrySet()) {
//                    Action uncontrollableActionRule = eachRule.getKey();
//                    if(!eachRule.getValue().isEmpty() && !uncontrollableActionRule.equals("tau")){
//                        writerFile.write(" (");
//                        Integer cantAuto = 0;
//                        for (Map.Entry<Integer, Set<State>> eachTransition : eachRule.getValue().entrySet()) {
//                            Integer LTSKey = eachTransition.getKey();
//                            if (!eachTransition.getValue().isEmpty()) {
//                                writerFile.write("(");
//                                Integer k = 0;
//                                for (State stateFrom : eachTransition.getValue()) {
//                                    writerFile.write("states_" + LTSKey + "=s" + stateFrom);
//                                    k++;
//                                    if (k < eachTransition.getValue().size()) {
//                                        writerFile.write(" or ");
//                                    }
//                                }
//                                writerFile.write(")");
//                                cantAuto++;
//                                if (cantAuto < eachRule.getValue().size()) {
//                                    writerFile.write(" and ");
//                                }
//                            }
//                        }
//                        writerFile.write(")");
//                        cantLabels++;
//                        if (cantLabels < rule_info.keySet().size()) {
//                            writerFile.write(" or ");
//                        }
//                    }else{ cantLabels++; }
//                }
//            }
//
//            writerFile.write(";");
//            writeLine();
//

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** this function checks if transitionLabel is a possible transition from composition **/
    private boolean isCompositionTransition(Integer ltsKey,
                                            String transitionLabel,
                                            HashMap<Integer, Map<Long, BinaryRelation<String, Long>>>
                                                    transitionsByLts,
                                            HashMap<Integer, Set<String>> labelsByLts) {

        for(Map.Entry<Integer, Map<Long, BinaryRelation<String, Long>>> transitionsLTS :
                transitionsByLts.entrySet()) {
            Integer anotherLTS =  transitionsLTS.getKey();
            if(ltsKey!=anotherLTS) { // check another ltss
                if(labelsByLts.get(anotherLTS).contains(transitionLabel)){
                    if(!transitionsByLts.get(anotherLTS).get(0L).contains(transitionLabel)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static String getCleanStringAction(Object cAction) {
        return cAction.toString().replace(".", "");
    }


    private void writeEnvVarFluent(Formula formG) throws IOException{
        if (formG instanceof FluentPropositionalVariable) {
            // 👇 chequeo si ya procesé este formG
            String fluentName = ((FluentPropositionalVariable) formG).getName();
            if (processedFluents.contains(fluentName)) {
                return; // no lo vuelvo a escribir
            }
            processedFluents.add(fluentName);

            writerFile.write("env boolean " + fluentName + ";");
            writerFile.write("asm ini !" + fluentName + ";");
            writeLine();

            // Initiating Actions
            Iterator<Symbol> initiatingActionsIt = ((FluentPropositionalVariable) formG).getFluent().getInitiatingActions().iterator();
            writerFile.write("asm always !" + fluentName + " & " );
            writerFile.write("next(l_e!=" + initiatingActionsIt.next());

            while(initiatingActionsIt.hasNext()){
                writerFile.write(" & l_e!=" + initiatingActionsIt.next());
            }

            writerFile.write(") -> next(!"+ fluentName +");");
            writeLine();

            /*
            // TErminating Actions
            Iterator<Symbol> terminatingActionsIt = ((FluentPropositionalVariable) formG).getFluent().getTerminatingActions().iterator();
            writerFile.write("asm always " + ((FluentPropositionalVariable) formG).getName() + " & " );
            writerFile.write("next(l_e!=" + terminatingActionsIt.next());

            while(terminatingActionsIt.hasNext()){
                writerFile.write(" & l_e!=" + terminatingActionsIt.next());
            }

            writerFile.write(") -> next("+ ((FluentPropositionalVariable) formG).getName() +");");
            writeLine();

             */


        }else if(formG instanceof AndFormula){
            writeEnvVarFluent(((AndFormula) formG).getLeftFormula());
            writeEnvVarFluent(((AndFormula) formG).getRightFormula());
        }else if(formG instanceof OrFormula){
            writeEnvVarFluent(((OrFormula) formG).getLeftFormula());
            writeEnvVarFluent(((OrFormula) formG).getRightFormula());
        }else if(formG instanceof NotFormula){
            Formula form = ((NotFormula) formG).getFormula();
            // idem para evitar repetir
            writeEnvVarFluent(form);
        }

    }

    private void writeFluentsIn(Formula formG, Set<String> l_e_actions) throws IOException{
        if (formG instanceof FluentPropositionalVariable) {

            FluentPropositionalVariable var = (FluentPropositionalVariable) formG;
            String fluentName = var.getFluent().getName();

            // 1. Iniciadores
            List<String> initiators = var.getFluent().getInitiatingActions()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());

            if (!initiators.isEmpty()) {
                String cond = initiators.stream()
                        .map(a -> "next(l_e=" + a + ")")
                        .collect(Collectors.joining(" | "));
                writerFile.write("asm always (" + cond + ") -> next(" + fluentName + ");");
                writeLine();
            }

            // 2. Terminadores
            List<String> terminators = var.getFluent().getTerminatingActions()
                    .stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());

            if (!terminators.isEmpty()) {
                String cond = terminators.stream()
                        .map(a -> "next(l_e=" + a + ")")
                        .collect(Collectors.joining(" | "));
                writerFile.write("asm always (" + cond + ") -> next(!" + fluentName + ");");
                writeLine();
            }

            // 3. Acciones neutras
            HashSet<String> actionsNofluent = new HashSet<>(l_e_actions);
            actionsNofluent.removeAll(initiators);
            actionsNofluent.removeAll(terminators);

            if (!actionsNofluent.isEmpty()) {
                String cond = actionsNofluent.stream()
                        .map(a -> "next(l_e=" + a + ")")
                        .collect(Collectors.joining(" | "));

                writerFile.write("asm always ((" + cond + ") & " + fluentName + ") -> next(" + fluentName + ");");
                writeLine();
                writerFile.write("asm always ((" + cond + ") & !" + fluentName + ") -> next(!" + fluentName + ");");
                writeLine();
            }



            //writerFile.write(((FluentPropositionalVariable) formG).getName());
        }else if(formG instanceof AndFormula){
            writeFluentsIn(((AndFormula) formG).getLeftFormula(), l_e_actions);
            writeLine();
            writeFluentsIn(((AndFormula) formG).getRightFormula(), l_e_actions);
            writeLine();
        }else if(formG instanceof OrFormula){
            writeFluentsIn(((OrFormula) formG).getLeftFormula(), l_e_actions);
            writeLine();
            writeFluentsIn(((OrFormula) formG).getRightFormula(), l_e_actions);
            writeLine();
        }else if(formG instanceof NotFormula){
            writeFluentsIn(((NotFormula) formG).getFormula(), l_e_actions);
            writeLine();
        }

    }

    private void writeFormula(Formula formG) throws IOException {
        if (formG instanceof FluentPropositionalVariable) {
            writerFile.write(((FluentPropositionalVariable) formG).getName());
        }else if(formG instanceof AndFormula){
            writerFile.write("(");
            writeFormula(((AndFormula) formG).getLeftFormula());
            writerFile.write(" & ");
            writeFormula(((AndFormula) formG).getRightFormula());
            writerFile.write(")");
        }else if(formG instanceof OrFormula){
            writerFile.write("(");
            writeFormula(((OrFormula) formG).getLeftFormula());
            writerFile.write(" | ");
            writeFormula(((OrFormula) formG).getRightFormula());
            writerFile.write(")");
        }else{
            writerFile.write(formG.toString().toLowerCase());
        }

    }


    public void writeLine() throws IOException{
        try{
            writerFile.write("\n");
        }catch (IOException e){
            e.printStackTrace();
        }
    }
    public void writeSpecTitle(String title) throws IOException{

        try {
            writerFile.write("spec " + title);
            writeLine();
            writeLine();
        } catch (IOException e){
            e.printStackTrace();
        }

    }

    public void closeWriter(){
        try {
            writerFile.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void addLivenessPropertyFromFormula(Formula formula){
        if (formula instanceof FluentPropositionalVariable) {
            fluentsG.add(((FluentPropositionalVariable) formula));
        }
        /* else if (formula instanceof NotFormula) {
            result = not(((NotFormula) formula).getFormula());
        } else if (formula instanceof AndFormula) {
            AndFormula andFormula = (AndFormula) formula;
            result = binary(FormulaToMarkedLTS.BinaryOperation.AND, andFormula.getLeftFormula(), andFormula.getRightFormula());
        } else if (formula instanceof OrFormula) {
            OrFormula orFormula = (OrFormula) formula;
            result = binary(FormulaToMarkedLTS.BinaryOperation.OR, orFormula.getLeftFormula(), orFormula.getRightFormula());
        } else if (formula == Formula.FALSE_FORMULA) {
            result = new MarkedLTSImpl<>(0L);
        } else if (formula == Formula.TRUE_FORMULA) {
            result = new MarkedLTSImpl<>(0L);
            result.mark(0L);
        } else {
            throw new RuntimeException("Invalid formula " + formula);
        } */
    }

    private static void replaceAll(String filePath, String text, String replacement) {

        Path path = Paths.get(filePath);
        // Get all the lines
        try (Stream<String> stream = Files.lines(path, StandardCharsets.UTF_8)) {
            // Do the replace operation
            List<String> list = stream.map(line -> line.replace(text, replacement)).collect(Collectors.toList());
            // Write the content back
            Files.write(path, list, StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
