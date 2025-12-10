package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.*;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.lts.CompactState;
import org.javatuples.Pair;

import java.util.*;
import java.util.stream.Collectors;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.NonDeterministicUtils.translateLabelsAllLast;
import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.NonDeterministicUtils.translateLabelsLastOnly;

public class FormulaUtils {


    public static Formula copyFormula(Formula formulaToCopy, Set<Fluent> fluentsCopy) {
        Formula result = null;

        if(formulaToCopy.equals(Formula.TRUE_FORMULA))
            return Formula.TRUE_FORMULA;
        if(formulaToCopy.equals(Formula.FALSE_FORMULA))
            return Formula.FALSE_FORMULA;
        if(isAndFormula(formulaToCopy)){
            Formula leftFormula = ((AndFormula) formulaToCopy).getLeftFormula();
            Formula rightFormula = ((AndFormula) formulaToCopy).getRightFormula();
            result = new AndFormula(copyFormula(leftFormula, fluentsCopy), copyFormula(rightFormula, fluentsCopy));
        } else if (isOrFormula(formulaToCopy)) {
            Formula leftFormula = ((OrFormula) formulaToCopy).getLeftFormula();
            Formula rightFormula = ((OrFormula) formulaToCopy).getRightFormula();
            result = new OrFormula(copyFormula(leftFormula, fluentsCopy), copyFormula(rightFormula, fluentsCopy));
        } else if (isNotFormula(formulaToCopy)) {
            result = new NotFormula(copyFormula(((NotFormula) formulaToCopy).getFormula(), fluentsCopy));
        } else if(isFluentPropositionalVariable(formulaToCopy)){
            result = new FluentPropositionalVariable(
                    new FluentImpl(((FluentPropositionalVariable) formulaToCopy).getName(),
                            new HashSet<>( ((FluentPropositionalVariable) formulaToCopy).getFluent().getInitiatingActions()),
                            new HashSet<>( ((FluentPropositionalVariable) formulaToCopy).getFluent().getTerminatingActions()),
                            false)
            );
            fluentsCopy.add(((FluentPropositionalVariable) result).getFluent());
        }
        return result;
    }

    private static boolean isFluentPropositionalVariable(Formula formulaToCopy) {
        return formulaToCopy.getClass().getSimpleName().equals("FluentPropositionalVariable");
    }

    private static boolean isNotFormula(Formula formulaToCopy) {
        return formulaToCopy.getClass().getSimpleName().equals("NotFormula");
    }

    private static boolean isOrFormula(Formula formulaToCopy) {
        return formulaToCopy.getClass().getSimpleName().equals("OrFormula");
    }

    private static boolean isAndFormula(Formula formulaToCopy) {
        return formulaToCopy.getClass().getSimpleName().equals("AndFormula");
    }

    /**
     *
     * @param goal
     * @return set of strings according relevant labels into formula's fluentImpl
     */
    public static Set<String> getRelevantLabelsFrom(ControllerGoal<String> goal) {

        Set<String> relevantLabels = new HashSet<>();
        for(Fluent fluent : goal.getFluents()){
            Set<Symbol> initiatingActions = fluent.getInitiatingActions();
            relevantLabels.addAll(Utils.symbolToString(initiatingActions));
        }
        return relevantLabels;
    }

    /**
     * @param machine
     * @param goal
     * @param controllableLabels
     * @param translator
     * @param translatorControllable
     * @param localAlphabet
     * @return ControllerGoal
     * <p>
     * this method returns a goal with the controllable set and formula modified
     */
    public static ControllerGoal<String> adaptGoal(CompactState machine,
                                                   ControllerGoal<String> goal,
                                                   Set<String> controllableLabels,
                                                   Vector<HashMap<String, String>> translator,
                                                   Map<String, String> translatorControllable,
                                                   Set<String> localAlphabet)
            throws CloneNotSupportedException {


        ControllerGoal<String> resultGoal = (ControllerGoal<String>) goal.clone();

        // Firstly, I want to add new controllable actions to goal
        // the new controllable actions will be also shared uncontrollable actions
        Set<String> adjustedControllableLabels = translateLabelsAllLast(controllableLabels, translator);

        Set<String> nonLocalAlphabet = new HashSet<>(machine.getAlphabetV());
        nonLocalAlphabet.removeAll(localAlphabet);
        adjustedControllableLabels.addAll(nonLocalAlphabet);
        adjustedControllableLabels.retainAll(machine.getAlphabetV());
        adjustedControllableLabels.addAll(translatorControllable.keySet());

        resultGoal.setControllableActions(adjustedControllableLabels);

        // Lastly, we have to adapt formulae
        List<Formula> adaptedAssumptions = new ArrayList<>();
        List<Formula> adaptedGuarantees = new ArrayList<>();
        Set<Fluent> adaptedFluents = new HashSet<>();

        for(Formula originalAssumption : goal.getAssumptions()){
            Formula projectedAssumption = projectFormulaAndFluents(originalAssumption, machine, adaptedFluents, translator, translatorControllable);
            assert projectedAssumption != null;
            adaptedAssumptions.add(projectedAssumption);
        }
        for(Formula originalGuarantee : goal.getGuarantees()){
            Formula projectedGuarantee = projectFormulaAndFluents(originalGuarantee, machine, adaptedFluents, translator, translatorControllable);
            assert projectedGuarantee != null;
            adaptedGuarantees.add(projectedGuarantee);
        }
        resultGoal.setAssumptions(adaptedAssumptions);
        resultGoal.setGuarantees(adaptedGuarantees);
        resultGoal.setFluents(adaptedFluents);

        for (Fluent fluent : adaptedFluents) {
            fluent.getTerminatingActions()
                    .removeIf(action -> !machine.getAlphabetV().contains(action.toString()));
        }
        return resultGoal;
    }


    /**
     *
     * @param formula
     * @param machine
     * @param adaptedFluents
     * @param translator
     * @param translatorControllable
     * @return
     */
    private static Formula projectFormulaAndFluents(Formula formula,
                                                    CompactState machine,
                                                    Set<Fluent> adaptedFluents,
                                                    Vector<HashMap<String, String>> translator, Map<String, String> translatorControllable){

        if(isAndFormula(formula)){
            Formula leftFormula = projectFormulaAndFluents(((AndFormula) formula).getLeftFormula(), machine, adaptedFluents, translator, translatorControllable);
            Formula rightFormula = projectFormulaAndFluents(((AndFormula) formula).getRightFormula(), machine, adaptedFluents, translator, translatorControllable);
            return new AndFormula(leftFormula, rightFormula);
        } else if (isOrFormula(formula)) {
            Formula leftFormula = projectFormulaAndFluents(((OrFormula) formula).getLeftFormula(), machine, adaptedFluents, translator, translatorControllable);
            Formula rightFormula = projectFormulaAndFluents(((OrFormula) formula).getRightFormula(), machine, adaptedFluents, translator, translatorControllable);
            return new OrFormula(leftFormula, rightFormula);

        } else if (isNotFormula(formula)) {
            return new NotFormula(copyFormula(((NotFormula) formula).getFormula(), adaptedFluents));
        } else if(isFluentPropositionalVariable(formula)) {
            Fluent atomicFluent = ((FluentPropositionalVariable) formula).getFluent();

            Set<Symbol> fluentInitiatingActions = atomicFluent.getInitiatingActions();

            Set<String> fluentInitiatingActionsStr = Utils.symbolToString(fluentInitiatingActions);
            Set<String> translatedInitiatingActions = Utils.translateFromOriginalSet(fluentInitiatingActionsStr, translator);

            Set<String> machineAlphabet = new HashSet<>(machine.getAlphabetV());
            boolean fluentHasAction = translatedInitiatingActions.stream()
                .anyMatch(machineAlphabet::contains);

            if(!fluentHasAction)
                return Formula.TRUE_FORMULA;

            Set<String> fluentTerminatingActionsStr = Utils.symbolToString(atomicFluent.getTerminatingActions());
            Set<String> translatedTerminatingActions = Utils.translateFromOriginalSet(fluentTerminatingActionsStr, translator);
            translatedTerminatingActions.retainAll(machineAlphabet);

            FluentPropositionalVariable fluentToAdd = new FluentPropositionalVariable(new FluentImpl(((FluentPropositionalVariable) formula).getName(),
                    Utils.stringToSymbol(translatedInitiatingActions),
                    Utils.stringToSymbol(translatedTerminatingActions), false));
            adaptedFluents.add(fluentToAdd.getFluent());
            return fluentToAdd;
        }else{
            return Formula.TRUE_FORMULA;
        }
    }

    /**
     * @return the first component are fluent activation events and the second fluent deactivation
     */
    public static Pair<Set<String>,Set<String>> getActivationDeactivationEvents(Formula formula){
        Set<String> resultl = new HashSet<>();
        Set<String> resultr = new HashSet<>();
        if(isAndFormula(formula)){
            Pair<Set<String>,Set<String>> lhs = getActivationDeactivationEvents(((AndFormula) formula).getLeftFormula());
            Pair<Set<String>,Set<String>> rhs = getActivationDeactivationEvents(((AndFormula) formula).getRightFormula());
            resultl.addAll(rhs.getValue0());
            resultl.addAll(lhs.getValue0());
            resultr.addAll(rhs.getValue1());
            resultr.addAll(lhs.getValue1());
        } else if (isOrFormula(formula)) {
            Pair<Set<String>,Set<String>> lhs = getActivationDeactivationEvents(((OrFormula) formula).getLeftFormula());
            Pair<Set<String>,Set<String>> rhs = getActivationDeactivationEvents(((OrFormula) formula).getRightFormula());
            resultl.addAll(rhs.getValue0());
            resultl.addAll(lhs.getValue0());
            resultr.addAll(rhs.getValue1());
            resultr.addAll(lhs.getValue1());

        } else if (isNotFormula(formula)) {
            Pair<Set<String>,Set<String>> fluents = getActivationDeactivationEvents(((NotFormula) formula).getFormula());
            resultl = fluents.getValue1();
            resultr = fluents.getValue0();
        }else if(isFluentPropositionalVariable(formula)) {
            resultl = ((FluentPropositionalVariable) formula).getFluent().getInitiatingActions().stream().map(Objects::toString).collect(Collectors.toSet());
            resultr = ((FluentPropositionalVariable) formula).getFluent().getTerminatingActions().stream().map(Objects::toString).collect(Collectors.toSet());
        }else{
            throw new java.lang.Error("Unknown formula subclass");
        }
        return Pair.with(resultl,resultr);
    }

    public static Set<String> getActivationEventsGoal(ControllerGoal<String> goal) {
        Set<String> transitionsInGoal = new HashSet<>();
        for (Formula setLabels : goal.getAssumptions()) {
            transitionsInGoal.addAll(getActivationDeactivationEvents(setLabels).getValue0());
        }
        for (Formula setLabels : goal.getGuarantees()) {
            transitionsInGoal.addAll(getActivationDeactivationEvents(setLabels).getValue0());
        }
        return transitionsInGoal;
    }
}
