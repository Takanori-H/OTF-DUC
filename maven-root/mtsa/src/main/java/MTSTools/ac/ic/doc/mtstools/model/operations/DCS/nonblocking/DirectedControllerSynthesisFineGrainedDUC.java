package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.abstraction.HAction;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DirectedControllerSynthesisFineGrainedDUC<State, Action>
        extends DirectedControllerSynthesisDUC<State, Action> {

    protected UpdateProtocolSpec updateProtocolSpec;
    protected ProgressRegistry progressRegistry;
    protected Map<Integer, String> oldSafetyStopActionsByIndex = Collections.emptyMap();
    protected Map<Integer, String> newSafetyStartActionsByIndex = Collections.emptyMap();
    protected Map<String, List<Integer>> newSafetyIndicesByStartAction = Collections.emptyMap();

    public LTS<Long, Action> synthesizeDUC(
            List<LTS<State, Action>> ltss,
            Set<Action> controllable,
            int mappingStart, int mappingEnd,
            int oldSafeStart, int oldSafeEnd,
            int newSafeStart, int newSafeEnd,
            int transReqStart, int transReqEnd,
            int synthesisStart, int synthesisEnd,
            List<Map<Integer, Integer>> mappingMapEnvToNewEnv,
            Map<String, Long> newControllerConnectionMap,
            LTS<Long, String> newController,
            Map<Integer, List<Integer>> safetyComponentIndicesMap,
            Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap,
            UpdateProtocolSpec updateProtocolSpec,
            Map<Integer, String> oldSafetyStopActionsByIndex,
            Map<Integer, String> newSafetyStartActionsByIndex,
            LTSOutput output) {
        return super.synthesizeDUC(
                ltss,
                controllable,
                mappingStart, mappingEnd,
                oldSafeStart, oldSafeEnd,
                newSafeStart, newSafeEnd,
                transReqStart, transReqEnd,
                synthesisStart, synthesisEnd,
                mappingMapEnvToNewEnv,
                newControllerConnectionMap,
                newController,
                safetyComponentIndicesMap,
                safetyStateLookupMap,
                updateProtocolSpec,
                oldSafetyStopActionsByIndex,
                newSafetyStartActionsByIndex,
                output);
    }

    @Override
    protected void configureUpdateProtocol(
            UpdateProtocolSpec updateProtocolSpec,
            Map<Integer, String> oldSafetyStopActionsByIndex,
            Map<Integer, String> newSafetyStartActionsByIndex) {
        if (updateProtocolSpec == null) {
            throw new IllegalArgumentException("Fine-grained O-DUCS requires an UpdateProtocolSpec.");
        }
        this.updateProtocolSpec = updateProtocolSpec;
        this.progressRegistry = new ProgressRegistry(updateProtocolSpec);
        this.oldSafetyStopActionsByIndex = oldSafetyStopActionsByIndex == null
                ? Collections.<Integer, String>emptyMap()
                : oldSafetyStopActionsByIndex;
        this.newSafetyStartActionsByIndex = newSafetyStartActionsByIndex == null
                ? Collections.<Integer, String>emptyMap()
                : newSafetyStartActionsByIndex;
        this.newSafetyIndicesByStartAction = new HashMap<>();
        for (Map.Entry<Integer, String> entry : this.newSafetyStartActionsByIndex.entrySet()) {
            List<Integer> indices = this.newSafetyIndicesByStartAction.get(entry.getValue());
            if (indices == null) {
                indices = new java.util.ArrayList<>();
                this.newSafetyIndicesByStartAction.put(entry.getValue(), indices);
            }
            indices.add(entry.getKey());
        }
    }

    @Override
    protected boolean isFineGrainedMode() {
        return true;
    }

    @Override
    protected String describeOtfExecutionMode() {
        return "fine-grained " + super.describeOtfExecutionMode();
    }

    @Override
    protected void onDebugLogOpened() {
        log("[FineGrained-Protocol] progressActions=" + updateProtocolSpec.getProgressActionsInIndexOrder());
        log("[FineGrained-Protocol] stopOldSpecActions=" + updateProtocolSpec.getStopOldSpecActions());
        log("[FineGrained-Protocol] reconfigureActions=" + updateProtocolSpec.getReconfigureActions());
        log("[FineGrained-Protocol] startNewSpecActions=" + updateProtocolSpec.getStartNewSpecActions());
    }

    @Override
    public boolean isActive(int ltsIndex, long progressState) {
        if (ltsIndex == idxMarking) {
            return true;
        }
        if (isInRange(ltsIndex, transReqStart, transReqEnd)) {
            return true;
        }
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd)) {
            return false;
        }
        if (progressRegistry.isPreUpdate(progressState)) {
            if (ltsIndex == idxOC) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return true;
            }
            return false;
        }
        if (progressRegistry.isUpdate(progressState) || progressRegistry.isGoal(progressState)) {
            if (ltsIndex == idxOC) {
                return false;
            }
            if (isInRange(ltsIndex, mappingStart, mappingEnd)) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                String stopAction = oldSafetyStopActionsByIndex.get(ltsIndex);
                return stopAction == null || !progressRegistry.isCompleted(progressState, stopAction);
            }
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                String startAction = newSafetyStartActionsByIndex.get(ltsIndex);
                return startAction != null && progressRegistry.isCompleted(progressState, startAction);
            }
        }
        return true;
    }

    @Override
    public boolean isEnforce(int ltsIndex, long progressState) {
        if (ltsIndex == idxMarking) {
            return true;
        }
        if (isInRange(ltsIndex, transReqStart, transReqEnd)) {
            return true;
        }
        if (isInRange(ltsIndex, synthesisStart, synthesisEnd)) {
            return false;
        }
        if (progressRegistry.isPreUpdate(progressState)) {
            if (ltsIndex == idxOC) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                return true;
            }
            return false;
        }
        if (progressRegistry.isUpdate(progressState) || progressRegistry.isGoal(progressState)) {
            if (ltsIndex == idxOC) {
                return false;
            }
            if (isInRange(ltsIndex, mappingStart, mappingEnd)) {
                return true;
            }
            if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
                String stopAction = oldSafetyStopActionsByIndex.get(ltsIndex);
                return stopAction == null || !progressRegistry.isCompleted(progressState, stopAction);
            }
            if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
                String startAction = newSafetyStartActionsByIndex.get(ltsIndex);
                return startAction != null && progressRegistry.isCompleted(progressState, startAction);
            }
        }
        return true;
    }

    @Override
    public boolean isTrace(int ltsIndex, long progressState) {
        if (ltsIndex == idxOC) {
            return progressRegistry.isPreUpdate(progressState);
        }
        if (isInRange(ltsIndex, oldSafeStart, oldSafeEnd)) {
            String stopAction = oldSafetyStopActionsByIndex.get(ltsIndex);
            return stopAction == null || !progressRegistry.isCompleted(progressState, stopAction);
        }
        if (isInRange(ltsIndex, newSafeStart, newSafeEnd)) {
            String startAction = newSafetyStartActionsByIndex.get(ltsIndex);
            return startAction != null && progressRegistry.isCompleted(progressState, startAction);
        }
        return true;
    }

    @Override
    protected boolean isUpdateProgressState(CompostateDUC<State, Action> compostate) {
        long progressState = markingStateOf(compostate);
        return progressRegistry.isUpdate(progressState) && !progressRegistry.isAllDone(progressState);
    }

    @Override
    protected boolean isFinishUpdateReadyStateId(long progressState) {
        return progressRegistry.isAllDone(progressState);
    }

    @Override
    protected boolean isGoalProgressStateId(long progressState) {
        return progressRegistry.isGoal(progressState);
    }

    @Override
    public boolean isFineGrainedProgressActionEnabled(long progressState, String actionName) {
        if (progressRegistry.isPreUpdate(progressState)) {
            if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
                return true;
            }
            return !isFineGrainedUpdateProtocolActionName(actionName);
        }
        if (progressRegistry.isGoal(progressState)) {
            return !isFineGrainedUpdateProtocolActionName(actionName);
        }
        if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
            return false;
        }
        if (UpdateConstants.FINISH_UPDATE.equals(actionName)) {
            return progressRegistry.isAllDone(progressState);
        }
        if (updateProtocolSpec.isProgressAction(actionName)) {
            return !progressRegistry.isCompleted(progressState, actionName);
        }
        return true;
    }

    @Override
    public int markingDepthForHeuristic(long progressState) {
        if (progressRegistry.isPreUpdate(progressState)) {
            return 0;
        }
        if (progressRegistry.isGoal(progressState)) {
            return updateProtocolSpec.progressActionCount() + 2;
        }
        return 1 + progressRegistry.completedCount(progressState);
    }

    @Override
    public int maxMarkingDepthForHeuristic() {
        return updateProtocolSpec.progressActionCount() + 2;
    }

    @Override
    public int actionPriorityCost(String actionName) {
        if (UpdateConstants.FINISH_UPDATE.equals(actionName)) {
            return 0;
        }
        if (updateProtocolSpec.isStopOldSpecAction(actionName)) {
            return 10;
        }
        if (updateProtocolSpec.isReconfigureAction(actionName)) {
            return 20;
        }
        if (updateProtocolSpec.isStartNewSpecAction(actionName)) {
            return 30;
        }
        if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
            return 40;
        }
        return 100;
    }

    @Override
    public boolean isUpdateActionForExploration(String actionName) {
        return UpdateConstants.BEGIN_UPDATE.equals(actionName)
                || UpdateConstants.FINISH_UPDATE.equals(actionName)
                || updateProtocolSpec.isProgressAction(actionName);
    }

    @Override
    protected boolean usesSyntheticProgressSlot(int ltsIndex) {
        return ltsIndex == idxMarking;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Set<State> progressSlotSuccessors(long progressState, String actionName) {
        if (!isFineGrainedProgressActionEnabled(progressState, actionName)) {
            return null;
        }
        long next = progressState;
        if (progressRegistry.isPreUpdate(progressState)) {
            if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
                next = ProgressRegistry.UPDATE_EMPTY;
            }
        } else if (progressRegistry.isUpdate(progressState)) {
            if (UpdateConstants.FINISH_UPDATE.equals(actionName)) {
                next = ProgressRegistry.GOAL;
            } else if (updateProtocolSpec.isProgressAction(actionName)) {
                next = progressRegistry.nextFor(progressState, actionName);
            }
        }
        if (next != progressState && isFineGrainedUpdateProtocolActionName(actionName)) {
            log("[FineGrained-Progress] " + progressState + " --" + actionName + "--> " + next
                    + ", completed=" + progressRegistry.completedCount(next)
                    + "/" + updateProtocolSpec.progressActionCount());
        }
        return Collections.singleton((State) Long.valueOf(next));
    }

    @Override
    protected boolean isStartNewSpecActionForSafetySync(String actionName) {
        return updateProtocolSpec.isStartNewSpecAction(actionName);
    }

    @Override
    protected void applyStartNewSpecSafetySync(List<State> childStates, String actionName) {
        List<Integer> safetyIndices = newSafetyIndicesByStartAction.get(actionName);
        if (safetyIndices == null || safetyIndices.isEmpty()) {
            return;
        }
        log("[FineGrained-SafetySync] action=" + actionName + ", newSafetyIndices=" + safetyIndices);
        for (Integer safetyIdx : safetyIndices) {
            applySafetySyncForIndex(childStates, safetyIdx);
        }
    }

    @Override
    protected boolean isUpdateProtocolAction(HAction<State, Action> action) {
        String actionName = action.toString();
        return UpdateConstants.FINISH_UPDATE.equals(actionName)
                || updateProtocolSpec.isProgressAction(actionName);
    }

    @Override
    protected boolean isUpdateProtocolOutputActionName(String actionName) {
        return UpdateConstants.FINISH_UPDATE.equals(actionName)
                || updateProtocolSpec.isProgressAction(actionName);
    }

    @Override
    protected void recordOtfDetailedEvaluation() {
        recordOtfExploredUpdateEventTransitionCounts();
        recordOtfProjectionSplitStats();
    }

    private boolean isFineGrainedUpdateProtocolActionName(String actionName) {
        return UpdateConstants.BEGIN_UPDATE.equals(actionName)
                || UpdateConstants.FINISH_UPDATE.equals(actionName)
                || updateProtocolSpec.isProgressAction(actionName);
    }

    private long markingStateOf(CompostateDUC<State, Action> compostate) {
        Object marking = compostate.getStates().get(idxMarking);
        if (marking instanceof Long) {
            return ((Long) marking).longValue();
        }
        if (marking instanceof Integer) {
            return ((Integer) marking).longValue();
        }
        return -1L;
    }
}
