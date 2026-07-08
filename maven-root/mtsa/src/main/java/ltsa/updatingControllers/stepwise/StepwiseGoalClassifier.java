package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.CompactState;
import ltsa.lts.Diagnostics;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class StepwiseGoalClassifier {

    private static final int CROSS_STAGE_INDEX = -1;

    private final List<StepwiseStage> stages;
    private final List<Set<String>> stageAlphabets;
    private final LTSOutput output;

    public StepwiseGoalClassifier(List<StepwiseStage> stages, LTSOutput output) {
        this.stages = stages;
        this.output = output;
        this.stageAlphabets = new ArrayList<Set<String>>();
        for (StepwiseStage stage : stages) {
            this.stageAlphabets.add(alphabet(stage.getMappingEnvironment()));
        }
    }

    public Classification classify(
            ControllerGoalDefinition oldGoal,
            ControllerGoalDefinition newGoal,
            List<ltsa.lts.Symbol> transitionGoals) {
        Classification result = new Classification(stages.size());
        output.outln("");
        output.outln("[Stepwise DUCS] Requirement classification:");

        classifyAll(oldGoal.getSafetyDefinitions(), StepwiseRequirementKind.OLD_SAFETY, result);
        classifyAll(newGoal.getSafetyDefinitions(), StepwiseRequirementKind.NEW_SAFETY, result);
        classifyAll(transitionGoals, StepwiseRequirementKind.TRANSITION, result);
        return result;
    }

    private void classifyAll(
            List<ltsa.lts.Symbol> symbols,
            StepwiseRequirementKind kind,
            Classification result) {
        if (symbols == null) {
            return;
        }
        for (ltsa.lts.Symbol symbol : symbols) {
            StepwiseFormulaSupport.ExtractedFormula extracted = StepwiseFormulaSupport.extract(symbol, kind);
            ClassificationTarget target = classifyTarget(symbol.getName(), extracted.getFluents());
            StepwiseClassifiedGoal goal = new StepwiseClassifiedGoal(
                    symbol,
                    kind,
                    target.getStageIndex(),
                    target.getStageScope(),
                    target.getReason(),
                    extracted.getFormula(),
                    extracted.getFluents(),
                    extracted.getActionFluentsForUpdate());
            result.add(goal);
            if (target.isCross()) {
                output.outln("  " + symbol.getName() + " (" + kind + ") -> cross scope "
                        + displayStageScope(target.getStageScope()) + " " + target.getReason());
            } else {
                output.outln("  " + symbol.getName() + " (" + kind + ") -> stage " + (target.getStageIndex() + 1));
            }
        }
    }

    private ClassificationTarget classifyTarget(String goalName, Set<Fluent> fluents) {
        Map<String, Set<Integer>> ownersByAction = new LinkedHashMap<String, Set<Integer>>();
        for (Fluent fluent : fluents) {
            collectOwners(goalName, ownersByAction, fluent.getInitiatingActions());
            collectOwners(goalName, ownersByAction, fluent.getTerminatingActions());
        }
        if (ownersByAction.isEmpty()) {
            return ClassificationTarget.cross(allStageScope(), "GOAL_UPDATE_EVENTS_ONLY");
        }
        Set<Integer> stageScope = new TreeSet<Integer>();
        for (Set<Integer> owners : ownersByAction.values()) {
            stageScope.addAll(owners);
        }
        if (stageScope.size() > 1) {
            return ClassificationTarget.cross(stageScope, "GOAL_SPANS_MULTIPLE_STAGES actions=" + ownersByAction);
        }
        return ClassificationTarget.local(stageScope.iterator().next());
    }

    private void collectOwners(
            String goalName,
            Map<String, Set<Integer>> ownersByAction,
            Set<Symbol> symbols) {
        if (symbols == null) {
            return;
        }
        for (Symbol symbol : symbols) {
            String action = normalizeAction(symbol.toString());
            if (action == null || action.length() == 0 || "tau".equals(action) || "*".equals(action)) {
                continue;
            }
            if (isUpdateAction(action)) {
                continue;
            }
            List<Integer> owners = ownersOf(action);
            if (owners.isEmpty()) {
                Diagnostics.fatal("Stepwise DUCS classification error: GOAL_ACTION_NOT_FOUND in "
                        + goalName + " action=" + action + ".");
            }
            Set<Integer> ownerSet = ownersByAction.get(action);
            if (ownerSet == null) {
                ownerSet = new TreeSet<Integer>();
                ownersByAction.put(action, ownerSet);
            }
            ownerSet.addAll(owners);
        }
    }

    private List<Integer> ownersOf(String action) {
        List<Integer> owners = new ArrayList<Integer>();
        for (int i = 0; i < stageAlphabets.size(); i++) {
            if (stageAlphabets.get(i).contains(action)) {
                owners.add(i);
            }
        }
        return owners;
    }

    private String normalizeAction(String action) {
        if (UpdatingControllersUtils.isOld(action)) {
            return UpdatingControllersUtils.withoutOld(action);
        }
        return action;
    }

    private boolean isUpdateAction(String action) {
        return UpdateConstants.BEGIN_UPDATE.equals(action)
                || UpdateConstants.STOP_OLD_SPEC.equals(action)
                || UpdateConstants.RECONFIGURE.equals(action)
                || UpdateConstants.START_NEW_SPEC.equals(action)
                || UpdateConstants.FINISH_UPDATE.equals(action)
                || action.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)
                || action.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                || action.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX);
    }

    private Set<String> alphabet(CompactState machine) {
        Set<String> result = new HashSet<String>();
        if (machine != null && machine.alphabet != null) {
            result.addAll(Arrays.asList(machine.alphabet));
        }
        return result;
    }

    private Set<Integer> allStageScope() {
        Set<Integer> result = new TreeSet<Integer>();
        for (int i = 0; i < stages.size(); i++) {
            result.add(i);
        }
        return result;
    }

    private String displayStageScope(Set<Integer> stageScope) {
        List<String> display = new ArrayList<String>();
        for (Integer stageIndex : stageScope) {
            display.add(Integer.toString(stageIndex + 1));
        }
        return display.toString();
    }

    public static final class Classification {
        private final List<List<StepwiseClassifiedGoal>> oldSafetyByStage;
        private final List<List<StepwiseClassifiedGoal>> newSafetyByStage;
        private final List<List<StepwiseClassifiedGoal>> transitionByStage;
        private final List<StepwiseClassifiedGoal> crossGoals;

        private Classification(int stageCount) {
            oldSafetyByStage = createBuckets(stageCount);
            newSafetyByStage = createBuckets(stageCount);
            transitionByStage = createBuckets(stageCount);
            crossGoals = new ArrayList<StepwiseClassifiedGoal>();
        }

        private static List<List<StepwiseClassifiedGoal>> createBuckets(int stageCount) {
            List<List<StepwiseClassifiedGoal>> result = new ArrayList<List<StepwiseClassifiedGoal>>();
            for (int i = 0; i < stageCount; i++) {
                result.add(new ArrayList<StepwiseClassifiedGoal>());
            }
            return result;
        }

        private void add(StepwiseClassifiedGoal goal) {
            if (goal.getStageIndex() == CROSS_STAGE_INDEX) {
                crossGoals.add(goal);
                return;
            }
            if (StepwiseRequirementKind.OLD_SAFETY.equals(goal.getKind())) {
                oldSafetyByStage.get(goal.getStageIndex()).add(goal);
            } else if (StepwiseRequirementKind.NEW_SAFETY.equals(goal.getKind())) {
                newSafetyByStage.get(goal.getStageIndex()).add(goal);
            } else {
                transitionByStage.get(goal.getStageIndex()).add(goal);
            }
        }

        public List<StepwiseClassifiedGoal> getOldSafety(int stageIndex) {
            return oldSafetyByStage.get(stageIndex);
        }

        public List<StepwiseClassifiedGoal> getNewSafety(int stageIndex) {
            return newSafetyByStage.get(stageIndex);
        }

        public List<StepwiseClassifiedGoal> getTransitions(int stageIndex) {
            return transitionByStage.get(stageIndex);
        }

        public List<StepwiseClassifiedGoal> getCrossGoals() {
            return crossGoals;
        }
    }

    private static final class ClassificationTarget {
        private final int stageIndex;
        private final Set<Integer> stageScope;
        private final String reason;

        private ClassificationTarget(int stageIndex, Set<Integer> stageScope, String reason) {
            this.stageIndex = stageIndex;
            this.stageScope = new TreeSet<Integer>(stageScope);
            this.reason = reason;
        }

        private static ClassificationTarget local(int stageIndex) {
            Set<Integer> stageScope = new TreeSet<Integer>();
            stageScope.add(stageIndex);
            return new ClassificationTarget(stageIndex, stageScope, "");
        }

        private static ClassificationTarget cross(Set<Integer> stageScope, String reason) {
            return new ClassificationTarget(CROSS_STAGE_INDEX, stageScope, reason);
        }

        private int getStageIndex() {
            return stageIndex;
        }

        private boolean isCross() {
            return stageIndex == CROSS_STAGE_INDEX;
        }

        private Set<Integer> getStageScope() {
            return stageScope;
        }

        private String getReason() {
            return reason;
        }
    }
}
