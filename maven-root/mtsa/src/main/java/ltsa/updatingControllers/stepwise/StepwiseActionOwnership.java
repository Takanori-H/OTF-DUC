package ltsa.updatingControllers.stepwise;

import ltsa.lts.CompactState;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StepwiseActionOwnership {

    private final Map<String, Set<Integer>> ownersByAction;

    public StepwiseActionOwnership(List<StepwiseStage> stages) {
        Map<String, Set<Integer>> owners = new LinkedHashMap<String, Set<Integer>>();
        for (int stageIndex = 0; stageIndex < stages.size(); stageIndex++) {
            CompactState mappingEnvironment = stages.get(stageIndex).getMappingEnvironment();
            if (mappingEnvironment == null || mappingEnvironment.alphabet == null) {
                continue;
            }
            for (String rawAction : mappingEnvironment.alphabet) {
                String action = normalizeAction(rawAction);
                if (action == null || action.length() == 0
                        || "tau".equals(action) || "*".equals(action)
                        || isUpdateAction(action)) {
                    continue;
                }
                Set<Integer> actionOwners = owners.get(action);
                if (actionOwners == null) {
                    actionOwners = new LinkedHashSet<Integer>();
                    owners.put(action, actionOwners);
                }
                actionOwners.add(stageIndex);
            }
        }

        Map<String, Set<Integer>> immutableOwners =
                new LinkedHashMap<String, Set<Integer>>();
        for (Map.Entry<String, Set<Integer>> entry : owners.entrySet()) {
            immutableOwners.put(entry.getKey(), Collections.unmodifiableSet(
                    new LinkedHashSet<Integer>(entry.getValue())));
        }
        this.ownersByAction = Collections.unmodifiableMap(immutableOwners);
    }

    public Set<Integer> ownersOf(String action) {
        Set<Integer> owners = ownersByAction.get(normalizeAction(action));
        return owners == null ? Collections.<Integer>emptySet() : owners;
    }

    public Map<String, Set<Integer>> asMap() {
        return ownersByAction;
    }

    private static String normalizeAction(String action) {
        if (action != null && UpdatingControllersUtils.isOld(action)) {
            return UpdatingControllersUtils.withoutOld(action);
        }
        return action;
    }

    private static boolean isUpdateAction(String action) {
        return UpdateConstants.BEGIN_UPDATE.equals(action)
                || UpdateConstants.STOP_OLD_SPEC.equals(action)
                || UpdateConstants.RECONFIGURE.equals(action)
                || UpdateConstants.START_NEW_SPEC.equals(action)
                || UpdateConstants.FINISH_UPDATE.equals(action)
                || action.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)
                || action.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                || action.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX);
    }
}
