package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.SafetyController;
import ltsa.differential.EnvironmentSafetyProduct.Tuple;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Old-state facts needed by fresh synthesis, independent of the old game/index.
 * The tuple dictionary is still retained for the old-losing-state diagnostic.
 * This is NOT a replacement for the analysis cache used by incremental methods.
 */
final class FullUpdateWitness {
    private final Map<Tuple, Long> oldIds;
    private final Map<Long, Tuple> winning;
    private final Set<Long> controllerStates;
    private final Set<String> alphabet;

    FullUpdateWitness(EnvironmentSafetyProduct product, Analysis<Long, String> analysis,
            SafetyController<Long, String> controller) {
        // This immutable dictionary does not point back to the product or rows.
        oldIds = product.ids;
        Map<Long, Tuple> saved = new HashMap<>();
        for (Long state : analysis.getWinningStates()) saved.put(state, product.tuple(state));
        winning = Collections.unmodifiableMap(saved);
        controllerStates = Collections.unmodifiableSet(new HashSet<>(controller.getStates()));
        if (!winning.keySet().containsAll(controllerStates)) {
            throw new IllegalArgumentException("Old controller states must be winning");
        }
        alphabet = Collections.unmodifiableSet(new HashSet<>(product.alphabet));
    }

    Set<Long> winningStates() { return winning.keySet(); }
    Set<Long> controllerStates() { return controllerStates; }
    Set<String> alphabet() { return alphabet; }
    int gameStateCount() { return oldIds.size() + 1; } // shared ERROR has no tuple

    Long mapWinningState(EnvironmentSafetyProduct updated, Long oldState) {
        Tuple tuple = winning.get(oldState);
        if (tuple == null) throw new IllegalArgumentException("Uncaptured old winning state: " + oldState);
        return updated.stateId(tuple);
    }

    boolean wasLosing(EnvironmentSafetyProduct updated, Long state) {
        if (state == EnvironmentSafetyProduct.ERROR) return true;
        Long oldState = oldIds.get(updated.tuple(state));
        return oldState != null && !winning.containsKey(oldState);
    }
}
