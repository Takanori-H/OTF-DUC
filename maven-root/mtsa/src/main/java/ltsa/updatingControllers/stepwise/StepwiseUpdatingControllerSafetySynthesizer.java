package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentUtils;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.control.util.ControllerUtils;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StepwiseUpdatingControllerSafetySynthesizer {

    public static Set<Fluent> collectFluentsForEnvironment(
            Collection<StepwiseClassifiedGoal> goals,
            Set<String> actions) {
        Set<Fluent> fluents = new HashSet<Fluent>();
        Set<Fluent> actionFluents = new HashSet<Fluent>();
        for (StepwiseClassifiedGoal goal : goals) {
            fluents.addAll(goal.getFluents());
            actionFluents.addAll(goal.getActionFluentsForUpdate());
        }
        fillTerminatingActions(actions, fluents, actionFluents);
        return fluents;
    }

    public static MTS<Long, String> pruneSafetyOnlyInPlace(
            MTS<Long, String> metaEnvironment,
            Collection<StepwiseClassifiedGoal> goals,
            Set<Fluent> goalFluents,
            Set<String> controllableActions,
            LTSOutput output) {
        return pruneSafetyOnlyInPlace(
                metaEnvironment,
                goals,
                goalFluents,
                controllableActions,
                true,
                output);
    }

    public static MTS<Long, String> pruneSafetyOnlyInPlace(
            MTS<Long, String> metaEnvironment,
            Collection<StepwiseClassifiedGoal> goals,
            Set<Fluent> goalFluents,
            Set<String> controllableActions,
            boolean makeOldActionsUncontrollable,
            LTSOutput output) {
        if (makeOldActionsUncontrollable) {
            makeOldActionsUncontrollable(controllableActions, metaEnvironment);
        }
        if (goals == null || goals.isEmpty()) {
            return metaEnvironment;
        }

        List<Formula> safetyFormulas = new ArrayList<Formula>();
        for (StepwiseClassifiedGoal goal : goals) {
            safetyFormulas.add(goal.getFormula());
        }

        FluentStateValuation<Long> fluentStateValuation = buildValuations(metaEnvironment, goalFluents);
        MTS<Long, String> safetyEnv = valuateSafety(safetyFormulas, metaEnvironment, fluentStateValuation);
        if (output != null) {
            output.outln("  safety pruning kept states: " + safetyEnv.getStates().size()
                    + " transitions: " + countTransitions(safetyEnv));
        }
        return safetyEnv;
    }

    public static MTS<Long, String> pruneCrossSafety(
            MTS<Long, String> globalSafetyEnvironment,
            Collection<StepwiseClassifiedGoal> crossGoals,
            Set<String> controllableActions,
            LTSOutput output) {
        if (crossGoals == null || crossGoals.isEmpty()) {
            return globalSafetyEnvironment;
        }

        Set<Fluent> crossFluents =
                collectFluentsForEnvironment(crossGoals, globalSafetyEnvironment.getActions());
        MTS<Long, String> crossMetaEnvironment = crossFluents.isEmpty()
                ? globalSafetyEnvironment
                : ControllerUtils.removeTopStates(globalSafetyEnvironment, crossFluents);
        if (output != null) {
            outputStateSpace(output, "  cross metaEnv", crossMetaEnvironment);
        }

        MTS<Long, String> crossSafetyEnvironment =
                pruneSafetyOnlyInPlace(
                        crossMetaEnvironment,
                        crossGoals,
                        crossFluents,
                        controllableActions,
                        false,
                        output);
        if (output != null) {
            outputStateSpace(output, "  cross safetyEnv", crossSafetyEnvironment);
        }
        return crossSafetyEnvironment;
    }

    private static void fillTerminatingActions(
            Set<String> actions,
            Set<Fluent> goalFluents,
            Set<Fluent> actionFluents) {
        Set<Fluent> resultantFluents = new HashSet<Fluent>();
        for (Fluent fluent : actionFluents) {
            goalFluents.remove(fluent);

            Fluent resultantFluent =
                    UpdatingControllersUtils.completeActionFluentTerminatingActions(
                            actions,
                            fluent);
            resultantFluents.add(resultantFluent);
            goalFluents.add(resultantFluent);
        }
    }

    private static FluentStateValuation<Long> buildValuations(MTS<Long, String> metaEnv, Set<Fluent> fluents) {
        FluentStateValuation<Long> fsv = new FluentStateValuation<Long>(metaEnv.getStates());

        Queue<Long> toVisit = new LinkedList<Long>();
        Long firstState = new Long(metaEnv.getInitialState());
        toVisit.add(firstState);
        Set<Long> discovered = new HashSet<Long>();

        for (Fluent fluent : fluents) {
            if (fluent.getInitialValue()) {
                fsv.addHoldingFluent(firstState, fluent);
            }
        }

        while (!toVisit.isEmpty()) {
            Long actualInMetaEnv = toVisit.remove();
            if (discovered.add(actualInMetaEnv)) {

                for (Pair<String, Long> actionToState : metaEnv.getTransitions(actualInMetaEnv, MTS.TransitionType.REQUIRED)) {
                    String action = actionToState.getFirst();
                    Long toState = actionToState.getSecond();

                    if (UpdatingControllersUtils.isOld(action)) {
                        action = UpdatingControllersUtils.withoutOld(action);
                    }

                    for (Fluent fluent : fsv.getFluentsFromState(actualInMetaEnv)) {
                        if (!fluent.getTerminatingActions().contains(new SingleSymbol(action))) {
                            fsv.addHoldingFluent(toState, fluent);
                        }
                    }

                    for (Fluent fluent : fluents) {
                        if (fluent.getInitiatingActions().contains(new SingleSymbol(action))) {
                            fsv.addHoldingFluent(toState, fluent);
                        }
                    }

                    toVisit.add(toState);
                }
            }
        }
        return fsv;
    }

    private static MTS<Long, String> valuateSafety(
            List<Formula> safetyFormulas,
            MTS<Long, String> metaEnvironment,
            FluentStateValuation<Long> fluentStateValuation) {
        HashSet<Long> toBuild = new HashSet<Long>();
        formulaToStateSet(toBuild, metaEnvironment.getStates(), safetyFormulas, fluentStateValuation);
        return applySafetyInEnvironment(metaEnvironment, toBuild);
    }

    private static void formulaToStateSet(
            Set<Long> toBuild,
            Set<Long> allStates,
            List<Formula> formulas,
            FluentStateValuation<Long> valuation) {
        for (Formula formula : formulas) {
            for (Long state : allStates) {
                formulaToStateSet(toBuild, formula, state, valuation);
            }
            if (toBuild.isEmpty()) {
                Logger.getAnonymousLogger().log(Level.WARNING, "No state satisfies formula: " + formula);
            }
        }
    }

    private static void formulaToStateSet(
            Set<Long> toBuild,
            Formula formula,
            Long state,
            FluentStateValuation<Long> valuation) {
        valuation.setActualState(state);
        if (formula.evaluate(valuation)) {
            toBuild.add(state);
        }
    }

    private static MTS<Long, String> applySafetyInEnvironment(
            MTS<Long, String> metaEnvironment,
            HashSet<Long> toBuild) {
        MTS<Long, String> result = new MTSImpl<Long, String>(metaEnvironment.getInitialState());

        for (Long state : metaEnvironment.getStates()) {
            result.addState(state);
            if (!toBuild.contains(state)) {
                for (Pair<String, Long> transition : metaEnvironment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    result.addState(transition.getSecond());
                    result.addAction(transition.getFirst());
                    result.addRequired(state, transition.getFirst(), transition.getSecond());
                }
            }
        }
        result.removeUnreachableStates();
        return result;
    }

    private static void makeOldActionsUncontrollable(Set<String> controllableActions, MTS<Long, String> env) {
        Set<Fluent> fluentSet = new HashSet<Fluent>();
        fluentSet.add(UpdatingControllersUtils.beginFluent);

        FluentStateValuation<Long> beginUpdateValuation =
                FluentUtils.getInstance().buildValuation(env, fluentSet);

        for (Long state : env.getStates()) {
            if (!beginUpdateValuation.isTrue(state, UpdatingControllersUtils.beginFluent)) {
                List<Pair<String, Long>> toBeChanged = new ArrayList<Pair<String, Long>>();
                for (Pair<String, Long> actionToState : env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    if (controllableActions.contains(actionToState.getFirst())) {
                        toBeChanged.add(actionToState);
                    }
                }
                for (Pair<String, Long> actionToState : toBeChanged) {
                    String action = actionToState.getFirst();
                    Long toState = actionToState.getSecond();
                    env.removeRequired(state, action, toState);
                    String actionWithOld = action + UpdateConstants.OLD_LABEL;
                    env.addAction(actionWithOld);
                    env.addRequired(state, actionWithOld, toState);
                }
            }
        }

        Set<String> envActions = new HashSet<String>(env.getActions());
        for (String action : controllableActions) {
            if (UpdatingControllersUtils.isNotUpdateAction(action)) {
                if (envActions.contains(action)) {
                    env.addAction(action + UpdateConstants.OLD_LABEL);
                }
            }
        }
    }

    private static long countTransitions(MTS<Long, String> mts) {
        return ltsa.updatingControllers.EvaluationTransitionCounter.countRequiredAndMaybe(mts);
    }

    private static void outputStateSpace(LTSOutput output, String label, MTS<Long, String> mts) {
        output.outln(label + " states: " + mts.getStates().size()
                + " transitions: " + countTransitions(mts));
    }
}
