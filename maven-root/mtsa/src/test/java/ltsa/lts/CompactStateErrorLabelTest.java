package ltsa.lts;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class CompactStateErrorLabelTest {

    @Test
    public void reachablePreservesErrorAliasesAndRenumbersOrdinaryLabels() {
        CompactState machine = new CompactState("ERROR_LABELS");
        machine.alphabet = new String[]{"tau", "next", "fail", "idle"};
        machine.maxStates = 3;
        machine.states = new EventState[]{
                new EventState(1, 2),
                new EventState(3, 1),
                new EventState(2, Declaration.ERROR)
        };
        machine.addStateLabel("START", 0);
        machine.addStateLabel("UNREACHABLE", 1);
        machine.addStateLabel("REACHABLE", 2);
        machine.addStateLabel("ERROR", Declaration.ERROR);
        machine.addStateLabel("FAILURE_ALIAS", Declaration.ERROR);

        machine.reachable();

        assertEquals(2, machine.maxStates);
        assertEquals(Integer.valueOf(0), machine.getStateId("START"));
        assertEquals(Integer.valueOf(1), machine.getStateId("REACHABLE"));
        assertNull(machine.getStateId("UNREACHABLE"));
        assertEquals(Integer.valueOf(Declaration.ERROR), machine.getStateId("ERROR"));
        assertEquals(Integer.valueOf(Declaration.ERROR), machine.getStateId("FAILURE_ALIAS"));
        assertEquals(1, machine.states[0].getNext());
        assertEquals(Declaration.ERROR, machine.states[1].getNext());

        machine.reachable();
        assertEquals(Integer.valueOf(1), machine.getStateId("REACHABLE"));
        assertEquals(Integer.valueOf(Declaration.ERROR), machine.getStateId("ERROR"));
        assertEquals(Integer.valueOf(Declaration.ERROR), machine.getStateId("FAILURE_ALIAS"));
    }

    @Test
    public void reachableKeepsErrorAliasEvenWithoutAnErrorTransition() {
        CompactState machine = oneStateWithErrorAlias();

        machine.reachable();

        assertEquals(1, machine.maxStates);
        assertEquals(Integer.valueOf(0), machine.getStateId("START"));
        assertEquals(Integer.valueOf(Declaration.ERROR), machine.getStateId("FAILURE_ALIAS"));
        assertEquals(0, machine.states[0].getNext());
    }

    private static CompactState oneStateWithErrorAlias() {
        CompactState machine = new CompactState("UNUSED_ERROR_LABEL");
        machine.alphabet = new String[]{"tau", "idle"};
        machine.maxStates = 1;
        machine.states = new EventState[]{new EventState(1, 0)};
        machine.addStateLabel("START", 0);
        machine.addStateLabel("FAILURE_ALIAS", Declaration.ERROR);
        return machine;
    }
}
