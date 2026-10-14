package ltsa.lts;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** ERROR is a transition target, never an ordinary relation endpoint. */
public class MappingEnvironmentErrorStateTest {

    @Test
    public void rejectsAnErrorAliasAsTheOldRelationEndpoint() {
        rejectsErrorEndpoint("OLD_FAILURE", "NEW_ROOT");
    }

    @Test
    public void rejectsAnErrorAliasAsTheNewRelationEndpoint() {
        rejectsErrorEndpoint("OLD_ROOT", "NEW_FAILURE");
    }

    @Test
    public void normalRelationPreservesOldAndNewEnvironmentErrorTransitions() {
        synchronized (LTSCompiler.class) {
            CompactState mapping = compileMapping("OLD_ROOT", "NEW_ROOT");
            assertEquals(2, mapping.maxStates);
            int newRoot = onlyTarget(mapping, 0, "reconfigure");
            assertTrue("Reconfiguration must reach a distinct ordinary state.", newRoot > 0);
            assertEquals(Set.of("idleOld:0", "failOld:-1", "reconfigure:" + newRoot),
                    edgesFrom(mapping, 0));
            assertEquals(Set.of("idleNew:" + newRoot, "failNew:-1"),
                    edgesFrom(mapping, newRoot));
        }
    }

    private void rejectsErrorEndpoint(String oldLabel, String newLabel) {
        synchronized (LTSCompiler.class) {
            try {
                compileMapping(oldLabel, newLabel);
                fail("An ERROR alias cannot be connected as an ordinary mapping state.");
            } catch (LTSException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(
                        "Relation endpoints must be ordinary states, not ERROR"));
                assertTrue(expected.getMessage(), expected.getMessage().contains(
                        oldLabel + " -> " + newLabel));
                assertTrue(expected.getMessage(), expected.getMessage().contains("MAP"));
            }
        }
    }

    private CompactState compileMapping(String oldLabel, String newLabel) {
        String source = "OLD = OLD_ROOT, "
                + "OLD_ROOT = (idleOld -> OLD_ROOT | failOld -> OLD_FAILURE), "
                + "OLD_FAILURE = ERROR.\n"
                + "NEW = NEW_ROOT, "
                + "NEW_ROOT = (idleNew -> NEW_ROOT | failNew -> NEW_FAILURE), "
                + "NEW_FAILURE = ERROR.\n"
                + "relation REL = { " + oldLabel + "@OLD = reconfigure -> "
                + newLabel + "@NEW }\n"
                + "map MAP = { OLD, NEW, REL }\n"
                + "||MAPPED = (MAP).\n";
        EmptyLTSOuput output = new EmptyLTSOuput();
        LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), output, ".");
        compiler.compile();
        CompositeState composite = compiler.continueCompilation("MAPPED");
        composite.compose(output);
        return composite.composition;
    }

    private int onlyTarget(CompactState machine, int state, String action) {
        Set<Integer> targets = new HashSet<>();
        for (EventState head = machine.states[state]; head != null; head = head.list) {
            for (EventState edge = head; edge != null; edge = edge.nondet) {
                if (action.equals(machine.alphabet[edge.event])) {
                    targets.add(edge.next);
                }
            }
        }
        assertEquals("Expected one target for " + action, 1, targets.size());
        return targets.iterator().next();
    }

    private Set<String> edgesFrom(CompactState machine, int state) {
        Set<String> edges = new HashSet<>();
        for (EventState head = machine.states[state]; head != null; head = head.list) {
            for (EventState edge = head; edge != null; edge = edge.nondet) {
                edges.add(machine.alphabet[edge.event] + ":" + edge.next);
            }
        }
        return edges;
    }
}
