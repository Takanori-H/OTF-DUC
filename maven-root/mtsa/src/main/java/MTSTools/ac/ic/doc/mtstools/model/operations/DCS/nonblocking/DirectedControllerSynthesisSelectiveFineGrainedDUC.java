package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

import ltsa.updatingControllers.UpdateConstants;

public class DirectedControllerSynthesisSelectiveFineGrainedDUC<State, Action>
        extends DirectedControllerSynthesisFineGrainedDUC<State, Action> {

    @Override
    protected String describeOtfExecutionMode() {
        return "selective " + super.describeOtfExecutionMode();
    }

    @Override
    protected void onDebugLogOpened() {
        super.onDebugLogOpened();
        log("[SelectiveFineGrained-Protocol] oldSafetyGroups="
                + updateProtocolSpec.getActionToOldSafeties());
        log("[SelectiveFineGrained-Protocol] mappingGroups="
                + updateProtocolSpec.getActionToMappingIndices());
        log("[SelectiveFineGrained-Protocol] newSafetyGroups="
                + updateProtocolSpec.getActionToNewSafeties());
    }

    @Override
    public int actionPriorityCost(String actionName) {
        if (UpdateConstants.FINISH_UPDATE.equals(actionName)) {
            return 0;
        }
        if (updateProtocolSpec.isStopOldSpecAction(actionName)) {
            return updateProtocolSpec.isOthersProgressAction(actionName) ? 40 : 10;
        }
        if (updateProtocolSpec.isReconfigureAction(actionName)) {
            return updateProtocolSpec.isOthersProgressAction(actionName) ? 50 : 20;
        }
        if (updateProtocolSpec.isStartNewSpecAction(actionName)) {
            return updateProtocolSpec.isOthersProgressAction(actionName) ? 60 : 30;
        }
        if (UpdateConstants.BEGIN_UPDATE.equals(actionName)) {
            return 70;
        }
        return 100;
    }
}
