package ltsa.updatingControllers;

import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.CompactState;
import ltsa.lts.EventStateUtils;

/**
 * Exact transition counters used by evaluation metrics.
 *
 * <p>The historical MTSA APIs return {@code int}.  Keeping those APIs avoids
 * changing synthesis behaviour, while accumulating each per-state count in a
 * {@code long} prevents evaluation values from wrapping at 2^31 transitions.</p>
 */
public final class EvaluationTransitionCounter {

    private EvaluationTransitionCounter() {
    }

    /** Counts distinct REQUIRED and MAYBE edges of an MTS. */
    public static <S, A> long countRequiredAndMaybe(MTS<S, A> mts) {
        if (mts == null || mts.getStates() == null) {
            return 0L;
        }
        long count = 0L;
        for (S state : mts.getStates()) {
            count += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
            count += mts.getTransitions(state, MTS.TransitionType.MAYBE).size();
        }
        return count;
    }

    /** Counts REQUIRED edges only. */
    public static <S, A> long countRequired(MTS<S, A> mts) {
        if (mts == null || mts.getStates() == null) {
            return 0L;
        }
        long count = 0L;
        for (S state : mts.getStates()) {
            count += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
        }
        return count;
    }

    /** Counts all LTSA compact-state edges without an {@code int} accumulator. */
    public static long count(CompactState machine) {
        if (machine == null || machine.states == null) {
            return 0L;
        }
        long count = 0L;
        for (int state = 0; state < machine.states.length; state++) {
            count += EventStateUtils.count(machine.states[state]);
        }
        return count;
    }
}
