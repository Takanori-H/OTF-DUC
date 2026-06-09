package ltsa.updatingControllers.structures;

import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.Diagnostics;
import ltsa.lts.Symbol;
import ltsa.updatingControllers.UpdateConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UpdateProtocolSpec {

    private final Map<String, UpdateActionKind> actionKinds = new LinkedHashMap<>();
    private final Map<Integer, String> mappingIndexToAction = new HashMap<>();
    private final Map<String, String> oldSafetyToStopAction = new LinkedHashMap<>();
    private final Map<String, String> newSafetyToStartAction = new LinkedHashMap<>();
    private final Set<String> stopOldSpecActions = new LinkedHashSet<>();
    private final Set<String> reconfigureActions = new LinkedHashSet<>();
    private final Set<String> startNewSpecActions = new LinkedHashSet<>();
    private final Set<String> progressActions = new LinkedHashSet<>();
    private final Map<String, Integer> progressActionIndices = new LinkedHashMap<>();

    public static UpdateProtocolSpec forFineGrained(
            ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef) {
        UpdateProtocolSpec spec = new UpdateProtocolSpec();
        if (oldGoalDef != null) {
            for (Symbol safety : oldGoalDef.getSafetyDefinitions()) {
                String safetyName = safety.getName();
                String action = UpdateConstants.STOP_OLD_SPEC_PREFIX
                        + validateActionSuffix(safetyName, "old safety");
                spec.registerStopOldSpec(safetyName, action);
            }
        }
        if (newGoalDef != null) {
            for (Symbol safety : newGoalDef.getSafetyDefinitions()) {
                String safetyName = safety.getName();
                String action = UpdateConstants.START_NEW_SPEC_PREFIX
                        + validateActionSuffix(safetyName, "new safety");
                spec.registerStartNewSpec(safetyName, action);
            }
        }
        return spec;
    }

    public void registerReconfigure(int mappingIndex, String actionName) {
        validateReconfigureAction(actionName);
        String previous = mappingIndexToAction.put(mappingIndex, actionName);
        if (previous != null && !previous.equals(actionName)) {
            Diagnostics.fatal("A mapping component can have only one fine-grained reconfigure action: "
                    + previous + " and " + actionName + ".");
        }
        registerAction(actionName, UpdateActionKind.RECONFIGURE);
        reconfigureActions.add(actionName);
    }

    public String getReconfigureActionForMappingIndex(int mappingIndex) {
        return mappingIndexToAction.get(mappingIndex);
    }

    public Set<String> getAllUpdateActions() {
        Set<String> actions = new LinkedHashSet<>(progressActions);
        actions.add(UpdateConstants.BEGIN_UPDATE);
        actions.add(UpdateConstants.FINISH_UPDATE);
        return Collections.unmodifiableSet(actions);
    }

    public Set<String> getProgressActions() {
        return Collections.unmodifiableSet(progressActions);
    }

    public Set<String> getStopOldSpecActions() {
        return Collections.unmodifiableSet(stopOldSpecActions);
    }

    public Set<String> getReconfigureActions() {
        return Collections.unmodifiableSet(reconfigureActions);
    }

    public Set<String> getStartNewSpecActions() {
        return Collections.unmodifiableSet(startNewSpecActions);
    }

    public Map<String, String> getOldSafetyToStopAction() {
        return Collections.unmodifiableMap(oldSafetyToStopAction);
    }

    public Map<String, String> getNewSafetyToStartAction() {
        return Collections.unmodifiableMap(newSafetyToStartAction);
    }

    public UpdateActionKind getKind(String actionName) {
        return actionKinds.get(actionName);
    }

    public boolean isProgressAction(String actionName) {
        return actionKinds.containsKey(actionName);
    }

    public boolean isStopOldSpecAction(String actionName) {
        return UpdateActionKind.STOP_OLD_SPEC.equals(actionKinds.get(actionName));
    }

    public boolean isReconfigureAction(String actionName) {
        return UpdateActionKind.RECONFIGURE.equals(actionKinds.get(actionName));
    }

    public boolean isStartNewSpecAction(String actionName) {
        return UpdateActionKind.START_NEW_SPEC.equals(actionKinds.get(actionName));
    }

    public int getProgressIndex(String actionName) {
        Integer index = progressActionIndices.get(actionName);
        if (index == null) {
            Diagnostics.fatal("Unknown fine-grained update action: " + actionName);
        }
        return index;
    }

    public int progressActionCount() {
        return progressActions.size();
    }

    public List<String> getProgressActionsInIndexOrder() {
        List<String> actions = new ArrayList<>(progressActionIndices.keySet());
        Collections.sort(actions, (a, b) -> progressActionIndices.get(a).compareTo(progressActionIndices.get(b)));
        return actions;
    }

    public static String validateActionSuffix(String suffix, String source) {
        if (suffix == null || suffix.isEmpty() || !suffix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Diagnostics.fatal("Cannot generate fine-grained update action from " + source
                    + " name '" + suffix + "'. Rename the safety so it uses only letters, digits, and underscores.");
        }
        return suffix;
    }

    public static void validateReconfigureAction(String actionName) {
        if (UpdateConstants.RECONFIGURE.equals(actionName)) {
            return;
        }
        if (actionName == null || !actionName.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                || !actionName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            Diagnostics.fatal("Fine-grained relation action must be 'reconfigure' or start with '"
                    + UpdateConstants.RECONFIGURE_PREFIX + "': " + actionName);
        }
    }

    private void registerStopOldSpec(String safetyName, String actionName) {
        registerAction(actionName, UpdateActionKind.STOP_OLD_SPEC);
        oldSafetyToStopAction.put(safetyName, actionName);
        stopOldSpecActions.add(actionName);
    }

    private void registerStartNewSpec(String safetyName, String actionName) {
        registerAction(actionName, UpdateActionKind.START_NEW_SPEC);
        newSafetyToStartAction.put(safetyName, actionName);
        startNewSpecActions.add(actionName);
    }

    private void registerAction(String actionName, UpdateActionKind kind) {
        UpdateActionKind previous = actionKinds.put(actionName, kind);
        if (previous != null && previous != kind) {
            Diagnostics.fatal("Fine-grained update action is used with multiple meanings: " + actionName);
        }
        if (!progressActionIndices.containsKey(actionName)) {
            progressActionIndices.put(actionName, progressActionIndices.size());
        }
        progressActions.add(actionName);
    }
}
