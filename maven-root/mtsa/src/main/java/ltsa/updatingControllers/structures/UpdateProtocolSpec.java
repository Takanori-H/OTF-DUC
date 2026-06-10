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
    private final Map<Integer, String> mappingIndexToCandidateAction = new HashMap<>();
    private final Map<String, String> oldSafetyToStopAction = new LinkedHashMap<>();
    private final Map<String, String> newSafetyToStartAction = new LinkedHashMap<>();
    private final Map<String, Set<String>> actionToOldSafeties = new LinkedHashMap<>();
    private final Map<String, Set<String>> actionToNewSafeties = new LinkedHashMap<>();
    private final Map<String, Set<Integer>> actionToMappingIndices = new LinkedHashMap<>();
    private final Set<String> stopOldSpecActions = new LinkedHashSet<>();
    private final Set<String> reconfigureActions = new LinkedHashSet<>();
    private final Set<String> startNewSpecActions = new LinkedHashSet<>();
    private final Set<String> progressActions = new LinkedHashSet<>();
    private final Map<String, Integer> progressActionIndices = new LinkedHashMap<>();
    private boolean selective;

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

    public static UpdateProtocolSpec forSelective(
            UpdateProtocolSpec candidates,
            Set<String> referencedUpdateActions) {
        return SelectiveUpdateProtocolSpecBuilder.build(candidates, referencedUpdateActions);
    }

    public static Set<String> legacyUpdateActionNames() {
        Set<String> actions = new LinkedHashSet<>();
        actions.add(UpdateConstants.STOP_OLD_SPEC);
        actions.add(UpdateConstants.RECONFIGURE);
        actions.add(UpdateConstants.START_NEW_SPEC);
        return actions;
    }

    public static Set<String> othersActionNames() {
        Set<String> actions = new LinkedHashSet<>();
        actions.add(UpdateConstants.STOP_OLD_SPEC_OTHERS);
        actions.add(UpdateConstants.RECONFIGURE_OTHERS);
        actions.add(UpdateConstants.START_NEW_SPEC_OTHERS);
        return actions;
    }

    public static boolean isLegacyUpdateActionName(String actionName) {
        return UpdateConstants.STOP_OLD_SPEC.equals(actionName)
                || UpdateConstants.RECONFIGURE.equals(actionName)
                || UpdateConstants.START_NEW_SPEC.equals(actionName);
    }

    public static boolean isOthersActionName(String actionName) {
        return UpdateConstants.STOP_OLD_SPEC_OTHERS.equals(actionName)
                || UpdateConstants.RECONFIGURE_OTHERS.equals(actionName)
                || UpdateConstants.START_NEW_SPEC_OTHERS.equals(actionName);
    }

    public static boolean looksLikeFineGrainedUpdateAction(String actionName) {
        return actionName != null
                && (actionName.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)
                || actionName.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                || actionName.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX));
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
        Set<Integer> mappingIndices = actionToMappingIndices.get(actionName);
        if (mappingIndices == null) {
            mappingIndices = new LinkedHashSet<>();
            actionToMappingIndices.put(actionName, mappingIndices);
        }
        mappingIndices.add(mappingIndex);
        if (!mappingIndexToCandidateAction.containsKey(mappingIndex)) {
            mappingIndexToCandidateAction.put(mappingIndex, actionName);
        }
    }

    public String getReconfigureActionForMappingIndex(int mappingIndex) {
        return mappingIndexToAction.get(mappingIndex);
    }

    public String getCandidateReconfigureActionForMappingIndex(int mappingIndex) {
        return mappingIndexToCandidateAction.get(mappingIndex);
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

    public Map<String, Set<String>> getActionToOldSafeties() {
        return unmodifiableSetMap(actionToOldSafeties);
    }

    public Map<String, Set<String>> getActionToNewSafeties() {
        return unmodifiableSetMap(actionToNewSafeties);
    }

    public Map<String, Set<Integer>> getActionToMappingIndices() {
        Map<String, Set<Integer>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Integer>> entry : actionToMappingIndices.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private Map<String, Set<String>> unmodifiableSetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
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

    public boolean isSelective() {
        return selective;
    }

    public boolean isOthersProgressAction(String actionName) {
        return isOthersActionName(actionName) && isProgressAction(actionName);
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
        if ("others".equals(suffix)) {
            Diagnostics.fatal("Cannot generate fine-grained update action from " + source
                    + " name 'others'. The suffix 'others' is reserved for selective_fine_grained mode.");
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

    void markSelective() {
        selective = true;
    }

    void recordCandidateReconfigureAction(int mappingIndex, String candidateAction) {
        mappingIndexToCandidateAction.put(mappingIndex, candidateAction);
    }

    void registerStopOldSpec(String safetyName, String actionName) {
        registerAction(actionName, UpdateActionKind.STOP_OLD_SPEC);
        oldSafetyToStopAction.put(safetyName, actionName);
        stopOldSpecActions.add(actionName);
        Set<String> safeties = actionToOldSafeties.get(actionName);
        if (safeties == null) {
            safeties = new LinkedHashSet<>();
            actionToOldSafeties.put(actionName, safeties);
        }
        safeties.add(safetyName);
    }

    void registerStartNewSpec(String safetyName, String actionName) {
        registerAction(actionName, UpdateActionKind.START_NEW_SPEC);
        newSafetyToStartAction.put(safetyName, actionName);
        startNewSpecActions.add(actionName);
        Set<String> safeties = actionToNewSafeties.get(actionName);
        if (safeties == null) {
            safeties = new LinkedHashSet<>();
            actionToNewSafeties.put(actionName, safeties);
        }
        safeties.add(safetyName);
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
