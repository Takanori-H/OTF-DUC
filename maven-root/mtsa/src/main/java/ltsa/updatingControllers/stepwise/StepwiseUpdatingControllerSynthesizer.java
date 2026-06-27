package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.UpdatingEnvironment;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.ControllerGoalDefinition;
import ltsa.control.util.ControlConstants;
import ltsa.control.util.ControllerUtils;
import ltsa.control.util.GoalDefToControllerGoal;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.CompactState;
import ltsa.lts.CompactStateActionRelabeler;
import ltsa.lts.CompositeState;
import ltsa.lts.CompositionExpression;
import ltsa.lts.Diagnostics;
import ltsa.lts.LTSOutput;
import ltsa.lts.Symbol;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.UpdatingControllerGRSynthesizer;
import ltsa.updatingControllers.synthesis.UpdatingControllerSafetySynthesizer;
import ltsa.updatingControllers.synthesis.UpdatingEnvironmentGenerator;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Vector;

public class StepwiseUpdatingControllerSynthesizer {

    public static void generateController(UpdatingControllerCompositeState uccs, LTSOutput output) {
        List<StepwiseStage> stages = uccs.getStepwiseStages();
        if (stages == null || stages.isEmpty()) {
            Diagnostics.fatal("Stepwise DUCS requires at least one stage.");
        }

        output.outln("[Stepwise DUCS]");
        output.outln("oldController is ignored in initial stepwise mode: " + uccs.getOldControllerName());

        ControllerGoalDefinition oldGoal = uccs.getStepwiseOldGoalDefinition();
        ControllerGoalDefinition newGoal = uccs.getStepwiseNewGoalDefinition();
        validateSupportedGoal("oldGoal", oldGoal);
        validateSupportedGoal("newGoal", newGoal);

        StepwiseGoalClassifier classifier = new StepwiseGoalClassifier(stages, output);
        StepwiseGoalClassifier.Classification classification =
                classifier.classify(oldGoal, newGoal, uccs.getStepwiseTransitionGoals());
        List<StepwiseClassifiedGoal> crossGoals = classification.getCrossGoals();

        List<StageWork> stageWorks = new ArrayList<StageWork>();
        Set<String> globalUpdatingActions = new HashSet<String>();

        for (StepwiseStage stage : stages) {
            int stageIndex = stage.getIndex();
            List<StepwiseClassifiedGoal> localOldSafety = classification.getOldSafety(stageIndex);
            List<StepwiseClassifiedGoal> localNewSafety = classification.getNewSafety(stageIndex);
            List<StepwiseClassifiedGoal> localTransitions = classification.getTransitions(stageIndex);
            List<StepwiseClassifiedGoal> localGoals = new ArrayList<StepwiseClassifiedGoal>();
            localGoals.addAll(localOldSafety);
            localGoals.addAll(localNewSafety);
            localGoals.addAll(localTransitions);

            output.outln("");
            output.outln("[Stepwise DUCS] Stage " + stage.getDisplayIndex()
                    + " old=" + stage.getOldEnvironmentName()
                    + " new=" + stage.getNewEnvironmentName()
                    + " relation=" + stage.getMapRelationName());
            outputGoalNames(output, "local old safety", localOldSafety);
            outputGoalNames(output, "local new safety", localNewSafety);
            outputGoalNames(output, "local transition", localTransitions);

            MTS<Long, String> oldClosedLoop = buildOldClosedLoop(stage, uccs.getName(), oldGoal, localOldSafety, output);
            MTS<Long, String> mapping = AutomataToMTSConverter.getInstance().convert(stage.getMappingEnvironment());

            UpdatingEnvironmentGenerator updEnvGenerator = new UpdatingEnvironmentGenerator(oldClosedLoop, mapping);
            updEnvGenerator.generateEnvironment();
            UpdatingEnvironment updatingEnvironment = updEnvGenerator.getUpdEnv();
            MTS<Long, String> eU = ControllerUtils.UpdateEnvironment2MTS(updatingEnvironment);
            outputStateSpace(output, "  E_u", eU);

            globalUpdatingActions.addAll(eU.getActions());
            stageWorks.add(new StageWork(
                    stage,
                    localGoals,
                    eU,
                    eU.getActions()));
        }

        output.outln("");
        output.outln("[Stepwise DUCS] Global action alphabet for local action fluents: "
                + globalUpdatingActions.size());

        List<MTS<Long, String>> localSafetyEnvironments = new ArrayList<MTS<Long, String>>();
        for (StageWork work : stageWorks) {
            output.outln("");
            output.outln("[Stepwise DUCS] Stage " + work.stage.getDisplayIndex() + " safety pruning");
            addPassiveSelfLoopsForMissingActions(work.updatingEnvironment, globalUpdatingActions);
            Set<Fluent> localFluents =
                    StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                            work.localGoals,
                            globalUpdatingActions);
            MTS<Long, String> metaEnv = localFluents.isEmpty()
                    ? work.updatingEnvironment
                    : ControllerUtils.removeTopStates(work.updatingEnvironment, localFluents);
            outputStateSpace(output, "  metaEnv", metaEnv);

            MTS<Long, String> safetyEnv =
                    StepwiseUpdatingControllerSafetySynthesizer.pruneSafetyOnlyInPlace(
                            metaEnv,
                            work.localGoals,
                            localFluents,
                            uccs.getUpdateGRGoal().getControllableActions(),
                            output);
            outputStateSpace(output, "  safetyEnv", safetyEnv);
            localSafetyEnvironments.add(safetyEnv);
        }

        List<CrossComponent> crossComponents = buildCrossComponents(crossGoals);
        outputCrossComponents(output, crossComponents);

        List<ProductInput> finalProductInputs = new ArrayList<ProductInput>();
        Set<Integer> stagesCoveredByCrossComponents = new HashSet<Integer>();
        for (CrossComponent component : crossComponents) {
            MTS<Long, String> componentSafetyEnv =
                    buildCrossComponentEnvironment(
                            component,
                            localSafetyEnvironments,
                            stageWorks,
                            stages,
                            oldGoal,
                            uccs,
                            output);
            finalProductInputs.add(new ProductInput(
                    "cross component " + component.getId() + " stages=" + displayStageScope(component.getStageScope()),
                    componentSafetyEnv,
                    realActionsForScope(component.getStageScope(), stageWorks)));
            stagesCoveredByCrossComponents.addAll(component.getStageScope());
        }

        for (int i = 0; i < localSafetyEnvironments.size(); i++) {
            if (!stagesCoveredByCrossComponents.contains(i)) {
                finalProductInputs.add(new ProductInput(
                        "stage " + stages.get(i).getDisplayIndex(),
                        localSafetyEnvironments.get(i),
                        stageWorks.get(i).realActions));
            }
        }

        output.outln("");
        output.outln("[Stepwise DUCS] Final product inputs: " + productInputLabels(finalProductInputs));
        MTS<Long, String> globalSafetyEnv = composeProduct(
                finalProductInputs,
                "STEPWISE_FINAL_SAFETY_PRODUCT",
                output);
        globalSafetyEnv = trimActionsToTransitionLabels(globalSafetyEnv);
        outputStateSpace(output, "[Stepwise DUCS] Final product before DontDoTwice", globalSafetyEnv);

        globalSafetyEnv = UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(globalSafetyEnv);
        outputStateSpace(output, "[Stepwise DUCS] After global DontDoTwice", globalSafetyEnv);

        uccs.setUpdateEnvironment(globalSafetyEnv);
        CompactState compactSafetyEnv = MTSToAutomataConverter.getInstance()
                .convert(globalSafetyEnv, "stepwise_E_u||G(safety)", false, true);
        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(compactSafetyEnv);
        uccs.setMachines(machines);

        output.outln("");
        output.outln("[Stepwise DUCS] GR(1)");
        UpdatingControllerGRSynthesizer.synthesizeGR(compactSafetyEnv, uccs, globalSafetyEnv, output);
        if (uccs.getComposition() == null) {
            output.outln("[Stepwise DUCS] GR result: losing");
            Diagnostics.fatal("Stepwise DUCS GR(1) synthesis was losing for " + uccs.getName() + ".");
        } else {
            output.outln("[Stepwise DUCS] GR result: winning");
            output.outln("[Stepwise DUCS] output controller states: " + uccs.getComposition().maxStates
                    + " transitions: " + uccs.getComposition().ntransitions());
        }
    }

    private static MTS<Long, String> buildCrossComponentEnvironment(
            CrossComponent component,
            List<MTS<Long, String>> localSafetyEnvironments,
            List<StageWork> stageWorks,
            List<StepwiseStage> stages,
            ControllerGoalDefinition oldGoal,
            UpdatingControllerCompositeState uccs,
            LTSOutput output) {
        output.outln("");
        output.outln("[Stepwise DUCS] Cross component " + component.getId());
        output.outln("  stages: " + displayStageScope(component.getStageScope()));
        outputGoalNames(output, "goals", component.getGoals());

        List<ProductInput> componentInputs = new ArrayList<ProductInput>();
        for (Integer stageIndex : component.getStageScope()) {
            MTS<Long, String> safetyEnv = localSafetyEnvironments.get(stageIndex);
            outputStateSpace(output, "  input safetyEnv stage " + stages.get(stageIndex).getDisplayIndex(), safetyEnv);
            componentInputs.add(new ProductInput(
                    "stage " + stages.get(stageIndex).getDisplayIndex(),
                    safetyEnv,
                    stageWorks.get(stageIndex).realActions));
        }

        MTS<Long, String> componentSafetyEnv = composeProduct(
                componentInputs,
                "STEPWISE_CROSS_COMPONENT_" + component.getId() + "_PRODUCT",
                output);
        componentSafetyEnv = trimActionsToTransitionLabels(componentSafetyEnv);
        outputStateSpace(output, "  component product", componentSafetyEnv);

        Set<String> outOfScopeActions = outOfScopeActionsForScope(component.getStageScope(), stageWorks);
        addPassiveSelfLoopsForMissingActions(componentSafetyEnv, outOfScopeActions);
        outputStateSpace(output, "  component product with out-of-scope passive actions", componentSafetyEnv);
        output.outln("  realActions count: " + realActionsForScope(component.getStageScope(), stageWorks).size());
        output.outln("  outOfScope passive action count: " + outOfScopeActions.size());
        output.outln("  action alphabet count: " + componentSafetyEnv.getActions().size());

        List<StepwiseClassifiedGoal> crossOldSafetyGoals =
                filterGoals(component.getGoals(), StepwiseRequirementKind.OLD_SAFETY);
        if (!crossOldSafetyGoals.isEmpty()) {
            output.outln("  cross old controller restriction");
            outputGoalNames(output, "cross old safety", crossOldSafetyGoals);
            CompactState crossOldController =
                    buildCrossOldController(
                            uccs.getName(),
                            component.getId(),
                            stagesForScope(component.getStageScope(), stages),
                            oldGoal,
                            crossOldSafetyGoals,
                            output);
            componentSafetyEnv = composeWithCrossOldController(componentSafetyEnv, crossOldController, output);
            componentSafetyEnv = trimActionsToTransitionLabels(componentSafetyEnv);
            outputStateSpace(output, "  after cross old controller restriction", componentSafetyEnv);
        }

        output.outln("  cross safety pruning");
        outputGoalNames(output, "cross requirements", component.getGoals());
        componentSafetyEnv =
                StepwiseUpdatingControllerSafetySynthesizer.pruneCrossSafety(
                        componentSafetyEnv,
                        component.getGoals(),
                        uccs.getUpdateGRGoal().getControllableActions(),
                        output);
        componentSafetyEnv = trimActionsToTransitionLabels(componentSafetyEnv);
        outputStateSpace(output, "  after cross safety pruning", componentSafetyEnv);
        return componentSafetyEnv;
    }

    private static CompactState buildCrossOldController(
            String controllerName,
            int componentId,
            List<StepwiseStage> stages,
            ControllerGoalDefinition oldGoal,
            List<StepwiseClassifiedGoal> crossOldSafetyGoals,
            LTSOutput output) {
        ControllerGoalDefinition crossOldGoal =
                new ControllerGoalDefinition("STEPWISE_CROSS_OLD_GOAL_"
                        + controllerName + "_COMPONENT_" + componentId);
        for (StepwiseClassifiedGoal goal : crossOldSafetyGoals) {
            crossOldGoal.addSafetyDefinition(goal.getSymbol());
        }
        Vector<String> controllableActions = new Vector<String>();
        controllableActions.addAll(scopedOldControllableActions(oldGoal, stages));
        crossOldGoal.setControllableActionSet(controllableActions);
        if (oldGoal.isNonBlocking()) {
            crossOldGoal.setNonBlocking(true);
        }
        ControllerGoalDefinition.addDefinition(crossOldGoal.getNameString(), crossOldGoal);

        Vector<CompactState> synthesisMachines = new Vector<CompactState>();
        for (StepwiseStage stage : stages) {
            synthesisMachines.add(stage.getOldEnvironment().myclone());
        }
        Collection<CompactState> safetyReqs = CompositionExpression.preProcessSafetyReqs(crossOldGoal, output);
        if (safetyReqs != null) {
            synthesisMachines.addAll(safetyReqs);
        }

        CompositeState controllerProblem =
                new CompositeState("STEPWISE_CROSS_OLD_CONTROLLER_COMPONENT_" + componentId, synthesisMachines);
        controllerProblem.goal = GoalDefToControllerGoal.getInstance().buildControllerGoal(crossOldGoal);
        controllerProblem.compose(output);
        TransitionSystemDispatcher.synthesiseGRNoText(controllerProblem, output);
        if (controllerProblem.getComposition() == null
                || controllerProblem.getComposition().getName().contains(ControlConstants.NO_CONTROLLER)) {
            Diagnostics.fatal("Stepwise DUCS failed to synthesize cross old controller.");
        }

        CompactState controller = controllerProblem.getComposition().myclone();
        controller.name = "STEPWISE_CROSS_OLD_CONTROLLER_COMPONENT_" + componentId;
        relabelOldControllableActions(controller, controllableActions);
        output.outln("  cross old controllable actions: " + controllableActions.size());
        output.outln("  cross old Controller states: " + controller.maxStates
                + " transitions: " + controller.ntransitions());
        return controller;
    }

    private static void relabelOldControllableActions(
            CompactState controller,
            Collection<String> controllableActions) {
        for (String action : controllableActions) {
            if (ltsa.updatingControllers.synthesis.UpdatingControllersUtils.isNotUpdateAction(action)) {
                CompactStateActionRelabeler.relabelAction(
                        controller,
                        action,
                        action + UpdateConstants.OLD_LABEL);
            }
        }
    }

    private static MTS<Long, String> composeWithCrossOldController(
            MTS<Long, String> globalSafetyEnv,
            CompactState crossOldController,
            LTSOutput output) {
        CompactState phaseAwareController =
                disableControllerAfterBeginUpdate(crossOldController, globalSafetyEnv.getActions());
        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(MTSToAutomataConverter.getInstance()
                .convert(globalSafetyEnv, "stepwise_cross_old_base", false, true));
        machines.add(phaseAwareController);

        CompositeState restricted =
                new CompositeState("STEPWISE_CROSS_OLD_RESTRICTED", machines);
        restricted.compose(output);
        if (restricted.getComposition() == null) {
            Diagnostics.fatal("Stepwise DUCS failed to compose cross old controller restriction.");
        }
        return AutomataToMTSConverter.getInstance().convert(restricted.getComposition());
    }

    private static CompactState disableControllerAfterBeginUpdate(
            CompactState controller,
            Set<String> globalActions) {
        MTS<Long, String> controllerMts = AutomataToMTSConverter.getInstance().convert(controller);
        Set<Long> preUpdateStates = new HashSet<Long>(controllerMts.getStates());
        Set<String> controllerActions = new HashSet<String>(controllerMts.getActions());
        Set<String> allActions = new HashSet<String>(globalActions);
        allActions.addAll(controllerActions);
        allActions.add(UpdateConstants.BEGIN_UPDATE);
        allActions.remove("tau");

        Long unrestrictedState = nextFreshState(controllerMts);
        controllerMts.addState(unrestrictedState);
        controllerMts.addAction(UpdateConstants.BEGIN_UPDATE);
        for (Long state : preUpdateStates) {
            controllerMts.addRequired(state, UpdateConstants.BEGIN_UPDATE, unrestrictedState);
        }

        for (String action : allActions) {
            controllerMts.addAction(action);
            controllerMts.addRequired(unrestrictedState, action, unrestrictedState);
            if (!controllerActions.contains(action)
                    && !UpdateConstants.BEGIN_UPDATE.equals(action)) {
                for (Long state : preUpdateStates) {
                    controllerMts.addRequired(state, action, state);
                }
            }
        }
        return MTSToAutomataConverter.getInstance()
                .convert(controllerMts, "STEPWISE_CROSS_OLD_CONTROLLER_PHASED", false, true);
    }

    private static Long nextFreshState(MTS<Long, String> mts) {
        long next = 0L;
        for (Long state : mts.getStates()) {
            if (state >= next) {
                next = state + 1L;
            }
        }
        return next;
    }

    private static List<StepwiseClassifiedGoal> filterGoals(
            List<StepwiseClassifiedGoal> goals,
            StepwiseRequirementKind kind) {
        List<StepwiseClassifiedGoal> result = new ArrayList<StepwiseClassifiedGoal>();
        for (StepwiseClassifiedGoal goal : goals) {
            if (kind.equals(goal.getKind())) {
                result.add(goal);
            }
        }
        return result;
    }

    private static List<CrossComponent> buildCrossComponents(List<StepwiseClassifiedGoal> crossGoals) {
        List<CrossComponent> components = new ArrayList<CrossComponent>();
        for (StepwiseClassifiedGoal goal : crossGoals) {
            Set<Integer> scope = goal.getStageScope();
            if (scope == null || scope.isEmpty()) {
                Diagnostics.fatal("Stepwise DUCS cross goal has empty stage scope: " + goal.getName() + ".");
            }

            List<CrossComponent> overlapping = new ArrayList<CrossComponent>();
            for (CrossComponent component : components) {
                if (component.overlaps(scope)) {
                    overlapping.add(component);
                }
            }

            if (overlapping.isEmpty()) {
                CrossComponent component = new CrossComponent();
                component.addGoal(goal);
                components.add(component);
            } else {
                CrossComponent primary = overlapping.get(0);
                primary.addGoal(goal);
                for (int i = 1; i < overlapping.size(); i++) {
                    CrossComponent merged = overlapping.get(i);
                    primary.merge(merged);
                    components.remove(merged);
                }
            }
        }

        Collections.sort(components, new java.util.Comparator<CrossComponent>() {
            public int compare(CrossComponent left, CrossComponent right) {
                return left.firstStageIndex() - right.firstStageIndex();
            }
        });
        for (int i = 0; i < components.size(); i++) {
            components.get(i).setId(i + 1);
        }
        return components;
    }

    private static void outputCrossComponents(LTSOutput output, List<CrossComponent> crossComponents) {
        output.outln("");
        output.outln("[Stepwise DUCS] Cross components:");
        if (crossComponents.isEmpty()) {
            output.outln("  none");
            return;
        }
        for (CrossComponent component : crossComponents) {
            output.outln("  component " + component.getId()
                    + " stages=" + displayStageScope(component.getStageScope())
                    + " goals=" + goalNames(component.getGoals()));
        }
    }

    private static List<StepwiseStage> stagesForScope(
            Set<Integer> stageScope,
            List<StepwiseStage> stages) {
        List<StepwiseStage> result = new ArrayList<StepwiseStage>();
        for (Integer stageIndex : stageScope) {
            result.add(stages.get(stageIndex));
        }
        return result;
    }

    private static Set<String> realActionsForScope(
            Set<Integer> stageScope,
            List<StageWork> stageWorks) {
        Set<String> result = new HashSet<String>();
        for (Integer stageIndex : stageScope) {
            result.addAll(stageWorks.get(stageIndex).realActions);
        }
        return result;
    }

    private static Set<String> outOfScopeActionsForScope(
            Set<Integer> stageScope,
            List<StageWork> stageWorks) {
        Set<String> result = new HashSet<String>();
        for (int i = 0; i < stageWorks.size(); i++) {
            if (!stageScope.contains(i)) {
                result.addAll(stageWorks.get(i).realActions);
            }
        }
        return result;
    }

    private static Set<String> scopedOldControllableActions(
            ControllerGoalDefinition oldGoal,
            List<StepwiseStage> stages) {
        if (oldGoal.getControllableActionSet() == null) {
            Diagnostics.fatal("Stepwise DUCS requires oldGoal controllable actions for cross old safety.");
        }

        Set<String> scopedOldAlphabet = new HashSet<String>();
        for (StepwiseStage stage : stages) {
            if (stage.getOldEnvironment().alphabet != null) {
                for (String action : stage.getOldEnvironment().alphabet) {
                    scopedOldAlphabet.add(action);
                }
            }
        }

        Set<String> result = new HashSet<String>();
        for (String action : oldGoal.getControllableActionSet()) {
            if (scopedOldAlphabet.contains(action)) {
                result.add(action);
            }
        }
        return result;
    }

    private static MTS<Long, String> buildOldClosedLoop(
            StepwiseStage stage,
            String controllerName,
            ControllerGoalDefinition oldGoal,
            List<StepwiseClassifiedGoal> localOldSafety,
            LTSOutput output) {
        if (localOldSafety.isEmpty()) {
            output.outln("  no local old safety; OldCon_i = E_OLD_i");
            return AutomataToMTSConverter.getInstance().convert(stage.getOldEnvironment());
        }

        Set<String> localControllable = localControllableActions(oldGoal, stage.getOldEnvironment());
        ControllerGoalDefinition localGoal =
                buildLocalOldGoal(stage, controllerName, oldGoal, localOldSafety, localControllable);

        Vector<CompactState> synthesisMachines = new Vector<CompactState>();
        synthesisMachines.add(stage.getOldEnvironment().myclone());
        Collection<CompactState> safetyReqs = CompositionExpression.preProcessSafetyReqs(localGoal, output);
        if (safetyReqs != null) {
            synthesisMachines.addAll(safetyReqs);
        }

        CompositeState controllerProblem =
                new CompositeState("STEPWISE_OLD_CONTROLLER_STAGE_" + stage.getDisplayIndex(), synthesisMachines);
        controllerProblem.goal = GoalDefToControllerGoal.getInstance().buildControllerGoal(localGoal);
        controllerProblem.compose(output);
        TransitionSystemDispatcher.synthesiseGRNoText(controllerProblem, output);
        if (controllerProblem.getComposition() == null
                || controllerProblem.getComposition().getName().contains(ControlConstants.NO_CONTROLLER)) {
            Diagnostics.fatal("Stepwise DUCS failed to synthesize local old controller for stage "
                    + stage.getDisplayIndex() + ".");
        }
        output.outln("  local Controller_i states: " + controllerProblem.getComposition().maxStates
                + " transitions: " + controllerProblem.getComposition().ntransitions());

        Vector<CompactState> closedMachines = new Vector<CompactState>();
        closedMachines.add(controllerProblem.getComposition().myclone());
        closedMachines.add(stage.getOldEnvironment().myclone());
        CompositeState closedLoop =
                new CompositeState("STEPWISE_OLD_CLOSED_STAGE_" + stage.getDisplayIndex(), closedMachines);
        closedLoop.compose(output);
        output.outln("  OldCon_i states: " + closedLoop.getComposition().maxStates
                + " transitions: " + closedLoop.getComposition().ntransitions());
        return AutomataToMTSConverter.getInstance().convert(closedLoop.getComposition());
    }

    private static ControllerGoalDefinition buildLocalOldGoal(
            StepwiseStage stage,
            String controllerName,
            ControllerGoalDefinition oldGoal,
            List<StepwiseClassifiedGoal> localOldSafety,
            Set<String> localControllable) {
        String goalName = "STEPWISE_OLD_GOAL_" + controllerName + "_STAGE_" + stage.getDisplayIndex();
        ControllerGoalDefinition localGoal = new ControllerGoalDefinition(goalName);
        for (StepwiseClassifiedGoal goal : localOldSafety) {
            localGoal.addSafetyDefinition(goal.getSymbol());
        }
        localGoal.setControllableActionSet(new Vector<String>(localControllable));
        if (oldGoal.isNonBlocking()) {
            localGoal.setNonBlocking(true);
        }
        ControllerGoalDefinition.addDefinition(localGoal.getNameString(), localGoal);
        return localGoal;
    }

    private static Set<String> localControllableActions(
            ControllerGoalDefinition oldGoal,
            CompactState oldEnvironment) {
        if (oldGoal.getControllableActionSet() == null) {
            Diagnostics.fatal("Stepwise DUCS requires oldGoal controllable actions.");
        }
        Set<String> oldAlphabet = new HashSet<String>();
        if (oldEnvironment.alphabet != null) {
            for (String action : oldEnvironment.alphabet) {
                oldAlphabet.add(action);
            }
        }
        Set<String> result = new HashSet<String>();
        for (String action : oldGoal.getControllableActionSet()) {
            if (oldAlphabet.contains(action)) {
                result.add(action);
            }
        }
        return result;
    }

    private static MTS<Long, String> composeProduct(
            List<ProductInput> inputs,
            String productName,
            LTSOutput output) {
        if (inputs.size() == 1) {
            return inputs.get(0).environment;
        }

        output.outln("Owner-aware product: " + productName);
        MTS<Long, String> product = new MTSImpl<Long, String>(0L);
        Map<List<Long>, Long> tupleToState = new HashMap<List<Long>, Long>();
        Queue<List<Long>> pending = new LinkedList<List<Long>>();

        List<Long> initialTuple = new ArrayList<Long>();
        for (ProductInput input : inputs) {
            initialTuple.add(input.environment.getInitialState());
        }
        tupleToState.put(initialTuple, 0L);
        pending.add(initialTuple);

        long nextStateId = 1L;
        long nextProgressReport = 10000L;
        while (!pending.isEmpty()) {
            List<Long> tuple = pending.remove();
            Long fromState = tupleToState.get(tuple);
            product.addState(fromState);

            Set<String> enabledActions = enabledActions(inputs, tuple);
            for (String action : enabledActions) {
                if (!hasRealOwnerEnabled(action, tuple, inputs)) {
                    continue;
                }
                List<List<Long>> targetChoices =
                        targetChoices(action, tuple, inputs);
                if (targetChoices == null) {
                    continue;
                }
                for (List<Long> targetTuple : cartesianProduct(targetChoices)) {
                    Long targetState = tupleToState.get(targetTuple);
                    if (targetState == null) {
                        targetState = nextStateId++;
                        tupleToState.put(targetTuple, targetState);
                        product.addState(targetState);
                        pending.add(targetTuple);
                    }
                    product.addAction(action);
                    product.addRequired(fromState, action, targetState);
                }
            }

            if (tupleToState.size() >= nextProgressReport) {
                output.outln("-- States: " + tupleToState.size()
                        + " Transitions: " + countTransitions(product));
                nextProgressReport += 10000L;
            }
        }

        return product;
    }

    private static void validateSupportedGoal(String label, ControllerGoalDefinition goal) {
        if (!isEmpty(goal.getAssumeDefinitions())) {
            Diagnostics.fatal("Stepwise DUCS initial mode does not support assume in " + label + ".");
        }
        if (!isEmpty(goal.getGuaranteeDefinitions())) {
            Diagnostics.fatal("Stepwise DUCS initial mode does not support guarantee in " + label + ".");
        }
        if (!isEmpty(goal.getFaultsDefinitions())) {
            Diagnostics.fatal("Stepwise DUCS initial mode does not support fault in " + label + ".");
        }
        if (!isEmpty(goal.getBuchiDefinitions())) {
            Diagnostics.fatal("Stepwise DUCS initial mode does not support buchi/liveness in " + label + ".");
        }
        if (!isEmpty(goal.getConcurrencyDefinitions()) || !isEmpty(goal.getActivityDefinitions())) {
            Diagnostics.fatal("Stepwise DUCS initial mode does not support concurrency/activity fluents in " + label + ".");
        }
        if (!isEmpty(goal.getMarkingDefinitions()) || !isEmpty(goal.getDisturbanceActions())
                || goal.isPermissive() || goal.isReachability() || goal.isNonTransient()
                || goal.isExceptionHandling() || goal.isTestLatency()) {
            Diagnostics.fatal("Stepwise DUCS initial mode supports only safety, controllable, and optional nonblocking in "
                    + label + ".");
        }
    }

    private static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private static void outputGoalNames(
            LTSOutput output,
            String label,
            List<StepwiseClassifiedGoal> goals) {
        output.outln("  " + label + ": " + goalNames(goals));
    }

    private static List<String> goalNames(List<StepwiseClassifiedGoal> goals) {
        List<String> names = new ArrayList<String>();
        for (StepwiseClassifiedGoal goal : goals) {
            names.add(goal.getName());
        }
        return names;
    }

    private static String displayStageScope(Set<Integer> stageScope) {
        List<String> display = new ArrayList<String>();
        for (Integer stageIndex : stageScope) {
            display.add(Integer.toString(stageIndex + 1));
        }
        return display.toString();
    }

    private static List<String> productInputLabels(List<ProductInput> inputs) {
        List<String> labels = new ArrayList<String>();
        for (ProductInput input : inputs) {
            labels.add(input.label);
        }
        return labels;
    }

    private static void outputStateSpace(LTSOutput output, String label, MTS<Long, String> mts) {
        output.outln(label + " states: " + mts.getStates().size()
                + " transitions: " + countTransitions(mts));
    }

    private static int countTransitions(MTS<Long, String> mts) {
        int count = 0;
        for (Long state : mts.getStates()) {
            count += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
            count += mts.getTransitions(state, MTS.TransitionType.MAYBE).size();
        }
        return count;
    }

    private static MTS<Long, String> trimActionsToTransitionLabels(MTS<Long, String> source) {
        MTS<Long, String> result = new MTSImpl<Long, String>(source.getInitialState());
        for (Long state : source.getStates()) {
            result.addState(state);
        }
        for (Long state : source.getStates()) {
            for (MTSTools.ac.ic.doc.commons.relations.Pair<String, Long> transition :
                    source.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                result.addState(transition.getSecond());
                result.addAction(transition.getFirst());
                result.addRequired(state, transition.getFirst(), transition.getSecond());
            }
            for (MTSTools.ac.ic.doc.commons.relations.Pair<String, Long> transition :
                    source.getTransitions(state, MTS.TransitionType.MAYBE)) {
                result.addState(transition.getSecond());
                result.addAction(transition.getFirst());
                result.addPossible(state, transition.getFirst(), transition.getSecond());
            }
        }
        result.removeUnreachableStates();
        return result;
    }

    private static void addPassiveSelfLoopsForMissingActions(MTS<Long, String> environment, Set<String> globalActions) {
        Set<String> localActions = new HashSet<String>(environment.getActions());
        for (String action : globalActions) {
            if (!localActions.contains(action)) {
                environment.addAction(action);
                for (Long state : environment.getStates()) {
                    environment.addRequired(state, action, state);
                }
            }
        }
    }

    private static Set<String> enabledActions(
            List<ProductInput> inputs,
            List<Long> tuple) {
        Set<String> actions = new HashSet<String>();
        for (int i = 0; i < inputs.size(); i++) {
            for (MTSTools.ac.ic.doc.commons.relations.Pair<String, Long> transition :
                    inputs.get(i).environment.getTransitions(tuple.get(i), MTS.TransitionType.REQUIRED)) {
                actions.add(transition.getFirst());
            }
        }
        return actions;
    }

    private static boolean hasRealOwnerEnabled(
            String action,
            List<Long> tuple,
            List<ProductInput> inputs) {
        if (isUpdateAction(action)) {
            for (int i = 0; i < inputs.size(); i++) {
                if (enabledTargets(inputs.get(i).environment, tuple.get(i), action).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        boolean hasOwner = false;
        String baseAction = normalizeOldAction(action);
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).realActions.contains(baseAction)) {
                hasOwner = true;
                if (enabledTargets(inputs.get(i).environment, tuple.get(i), action).isEmpty()) {
                    return false;
                }
            }
        }
        return hasOwner;
    }

    private static List<List<Long>> targetChoices(
            String action,
            List<Long> tuple,
            List<ProductInput> inputs) {
        List<List<Long>> choices = new ArrayList<List<Long>>();
        String baseAction = normalizeOldAction(action);
        for (int i = 0; i < inputs.size(); i++) {
            List<Long> targets = enabledTargets(inputs.get(i).environment, tuple.get(i), action);
            boolean owner = isUpdateAction(action) || inputs.get(i).realActions.contains(baseAction);
            if (targets.isEmpty()) {
                if (owner || inputs.get(i).environment.getActions().contains(action)) {
                    return null;
                }
                choices.add(Collections.singletonList(tuple.get(i)));
            } else {
                choices.add(targets);
            }
        }
        return choices;
    }

    private static List<Long> enabledTargets(MTS<Long, String> environment, Long state, String action) {
        List<Long> targets = new ArrayList<Long>();
        for (MTSTools.ac.ic.doc.commons.relations.Pair<String, Long> transition :
                environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst()) && !targets.contains(transition.getSecond())) {
                targets.add(transition.getSecond());
            }
        }
        return targets;
    }

    private static List<List<Long>> cartesianProduct(List<List<Long>> choices) {
        List<List<Long>> result = new ArrayList<List<Long>>();
        buildCartesianProduct(choices, 0, new ArrayList<Long>(), result);
        return result;
    }

    private static void buildCartesianProduct(
            List<List<Long>> choices,
            int index,
            List<Long> current,
            List<List<Long>> result) {
        if (index == choices.size()) {
            result.add(new ArrayList<Long>(current));
            return;
        }
        for (Long target : choices.get(index)) {
            current.add(target);
            buildCartesianProduct(choices, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private static boolean isUpdateAction(String action) {
        return ltsa.updatingControllers.UpdateConstants.BEGIN_UPDATE.equals(action)
                || ltsa.updatingControllers.UpdateConstants.STOP_OLD_SPEC.equals(action)
                || ltsa.updatingControllers.UpdateConstants.RECONFIGURE.equals(action)
                || ltsa.updatingControllers.UpdateConstants.START_NEW_SPEC.equals(action)
                || ltsa.updatingControllers.UpdateConstants.FINISH_UPDATE.equals(action)
                || action.startsWith(ltsa.updatingControllers.UpdateConstants.STOP_OLD_SPEC_PREFIX)
                || action.startsWith(ltsa.updatingControllers.UpdateConstants.RECONFIGURE_PREFIX)
                || action.startsWith(ltsa.updatingControllers.UpdateConstants.START_NEW_SPEC_PREFIX);
    }

    private static String normalizeOldAction(String action) {
        if (ltsa.updatingControllers.synthesis.UpdatingControllersUtils.isOld(action)) {
            return ltsa.updatingControllers.synthesis.UpdatingControllersUtils.withoutOld(action);
        }
        return action;
    }

    private static final class CrossComponent {
        private int id;
        private final Set<Integer> stageScope = new java.util.TreeSet<Integer>();
        private final List<StepwiseClassifiedGoal> goals = new ArrayList<StepwiseClassifiedGoal>();

        private int getId() {
            return id;
        }

        private void setId(int id) {
            this.id = id;
        }

        private Set<Integer> getStageScope() {
            return stageScope;
        }

        private List<StepwiseClassifiedGoal> getGoals() {
            return goals;
        }

        private void addGoal(StepwiseClassifiedGoal goal) {
            goals.add(goal);
            stageScope.addAll(goal.getStageScope());
        }

        private void merge(CrossComponent other) {
            stageScope.addAll(other.stageScope);
            goals.addAll(other.goals);
        }

        private boolean overlaps(Set<Integer> otherScope) {
            for (Integer stageIndex : otherScope) {
                if (stageScope.contains(stageIndex)) {
                    return true;
                }
            }
            return false;
        }

        private int firstStageIndex() {
            if (stageScope.isEmpty()) {
                return Integer.MAX_VALUE;
            }
            return stageScope.iterator().next();
        }
    }

    private static final class ProductInput {
        private final String label;
        private final MTS<Long, String> environment;
        private final Set<String> realActions;

        private ProductInput(
                String label,
                MTS<Long, String> environment,
                Set<String> realActions) {
            this.label = label;
            this.environment = environment;
            this.realActions = new HashSet<String>(realActions);
        }
    }

    private static final class StageWork {
        private final StepwiseStage stage;
        private final List<StepwiseClassifiedGoal> localGoals;
        private final MTS<Long, String> updatingEnvironment;
        private final Set<String> realActions;

        private StageWork(
                StepwiseStage stage,
                List<StepwiseClassifiedGoal> localGoals,
                MTS<Long, String> updatingEnvironment,
                Set<String> realActions) {
            this.stage = stage;
            this.localGoals = new ArrayList<StepwiseClassifiedGoal>();
            this.localGoals.addAll(localGoals);
            this.updatingEnvironment = updatingEnvironment;
            this.realActions = new HashSet<String>(realActions);
        }
    }
}
