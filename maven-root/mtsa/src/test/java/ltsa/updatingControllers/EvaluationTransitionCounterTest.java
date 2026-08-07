package ltsa.updatingControllers;

import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EvaluationTransitionCounterTest {

    @Test
    public void countsActualRequiredAndMaybeEdgesInsteadOfTransitionMapEntries() {
        MTSImpl<Long, String> mts = new MTSImpl<Long, String>(0L);
        mts.addState(1L);
        mts.addState(2L);
        mts.addAction("requiredA");
        mts.addAction("requiredB");
        mts.addAction("maybe");
        mts.addRequired(0L, "requiredA", 1L);
        mts.addRequired(1L, "requiredB", 2L);
        mts.addPossible(0L, "maybe", 2L);

        assertEquals(2L, EvaluationTransitionCounter.countRequired(mts));
        assertEquals(3L, EvaluationTransitionCounter.countRequiredAndMaybe(mts));
    }

    @Test
    public void compactStateLongCounterMatchesAllDeterministicAndNondeterministicEdges() {
        CompactState machine = new CompactState("counter-test");
        machine.maxStates = 2;
        machine.alphabet = new String[] { "tau", "a", "b" };
        machine.states = new EventState[2];
        machine.states[0] = EventStateUtils.add(machine.states[0], new EventState(1, 0));
        machine.states[0] = EventStateUtils.add(machine.states[0], new EventState(1, 1));
        machine.states[1] = EventStateUtils.add(machine.states[1], new EventState(2, 0));

        assertEquals(3L, machine.ntransitionsLong());
        assertEquals(3L, EvaluationTransitionCounter.count(machine));
    }
}
