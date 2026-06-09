package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.condition.FluentPropositionalVariable;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.Diagnostics;
import ltsa.lts.Symbol;
import ltsa.lts.UpdatingControllersDefinition;
import ltsa.lts.UpdatingControllersGoalsMaker;
import ltsa.lts.chart.util.FormulaUtils;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public class FineGrainedUpdatingControllersUtils {

    public static ControllerGoal<String> generateGRUpdateGoal(
            UpdatingControllersDefinition updContDef,
            ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef,
            Set<String> controllableSet,
            UpdateProtocolSpec updateProtocolSpec) {
        ControllerGoal<String> grcg = new ControllerGoal<String>();
        grcg.setNonBlocking(updContDef.isNonblocking());
        grcg.addAllControllableActions(controllableSet);

        Set<Fluent> involvedFluents = new HashSet<Fluent>();
        addFluentAndAssumption(grcg, involvedFluents, UpdateConstants.BEGIN_UPDATE);
        for (String progressAction : updateProtocolSpec.getProgressActionsInIndexOrder()) {
            addFluentAndGuarantee(grcg, involvedFluents, progressAction);
        }
        addFailures(grcg, involvedFluents, oldGoalDef);
        addFailures(grcg, involvedFluents, newGoalDef);
        grcg.addAllFluents(involvedFluents);
        return grcg;
    }

    public static ControllerGoalDefinition generateSafetyGoalDef(
            UpdatingControllersDefinition updContDef,
            ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef,
            Set<String> controllableSetSymbol,
            UpdateProtocolSpec updateProtocolSpec,
            ltsa.lts.LTSOutput output) {
        ControllerGoalDefinition cgd = new ControllerGoalDefinition(updContDef.getName());
        cgd.addAssumeDefinition(new Symbol(123, "BeginUpdate"));
        addFailures(cgd, oldGoalDef);
        addFailures(cgd, newGoalDef);
        cgd.setControllableActionSet(new Vector<String>(controllableSetSymbol));
        cgd.setNonBlocking(updContDef.isNonblocking());

        generateUpdateGoals(oldGoalDef, newGoalDef, updContDef.getTransitionGoals(), cgd);
        ControllerGoalDefinition.addDefinition(cgd.getNameString(), cgd);
        AssertDefinition.compileAll(output);
        return cgd;
    }

    public static Fluent createPersistentActionFluent(String actionName) {
        HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> initiatingActions =
                new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
        initiatingActions.add(new SingleSymbol(actionName));
        return new FluentImpl(actionName, initiatingActions,
                new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>(), false);
    }

    private static void addFluentAndAssumption(
            ControllerGoal<String> grcg,
            Set<Fluent> fluents,
            String action) {
        grcg.addAssume(new FluentPropositionalVariable(addFluent(fluents, action)));
    }

    private static void addFluentAndGuarantee(
            ControllerGoal<String> grcg,
            Set<Fluent> fluents,
            String action) {
        grcg.addGuarantee(new FluentPropositionalVariable(addFluent(fluents, action)));
    }

    private static Fluent addFluent(Set<Fluent> fluents, String action) {
        Fluent fluent = createPersistentActionFluent(action);
        fluents.add(fluent);
        return fluent;
    }

    private static void addFailures(ControllerGoalDefinition cgd, ControllerGoalDefinition controllerGoalDef) {
        List<Symbol> faultsDefinitions = controllerGoalDef.getFaultsDefinitions();
        for (Symbol faultsDefinition : faultsDefinitions) {
            cgd.addFaultDefinition(faultsDefinition);
        }
    }

    private static void addFailures(
            ControllerGoal<String> grcg,
            Set<Fluent> involvedFluents,
            ControllerGoalDefinition controllerGoalDefinition) {
        Set<Fluent> fluentsInFaults = new HashSet<Fluent>();
        for (Symbol faultsDefinition : controllerGoalDefinition.getFaultsDefinitions()) {
            AssertDefinition def = AssertDefinition.getDefinition(faultsDefinition.getName());
            if (def != null) {
                grcg.addFault(FormulaUtils.adaptFormulaAndCreateFluents(def.getFormula(true), fluentsInFaults));
            } else {
                Diagnostics.fatal("Assertion not defined [" + faultsDefinition.getName() + "].");
            }
        }
        involvedFluents.addAll(fluentsInFaults);
        grcg.addAllFluentsInFaults(fluentsInFaults);
    }

    private static void generateUpdateGoals(
            ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef,
            List<Symbol> transitionGoalDef,
            ControllerGoalDefinition cgd) {
        for (Symbol formula : oldGoalDef.getSafetyDefinitions()) {
            UpdatingControllersGoalsMaker.addOldGoals(formula, cgd);
        }
        for (Symbol formula : newGoalDef.getSafetyDefinitions()) {
            UpdatingControllersGoalsMaker.addImplyUpdatingGoal(formula, cgd);
        }
        for (Symbol transitionSymbol : transitionGoalDef) {
            cgd.addSafetyDefinition(transitionSymbol);
        }
    }
}
