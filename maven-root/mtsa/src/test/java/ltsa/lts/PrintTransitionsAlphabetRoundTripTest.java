package ltsa.lts;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.*;

/** Exported FSP must retain inactive actions, including at a terminal last state. */
public class PrintTransitionsAlphabetRoundTripTest {
    private static final EmptyLTSOuput SILENT = new EmptyLTSOuput();

    @Test
    public void singleUnusedActionIsClosedAndCanBeParsed() {
        String exported = roundTrip("P=(go->P)+{inactive}.");
        assertTrue(exported, exported.contains("+{inactive}."));
    }

    @Test
    public void multipleUnusedActionsHaveOneOuterSet() {
        String exported = roundTrip("P=(go->P)+{pickupFail,putFail}.");
        assertTrue(exported, exported.contains("+{pickupFail, putFail}."));
        assertFalse(exported, exported.contains("+{{"));
    }

    @Test
    public void indexedUnusedActionsKeepTheirActionNames() {
        roundTrip("P=(go->P)+{[0..2].fail,arrive[1][2],left.finish,right.finish}.");
    }

    @Test
    public void lastStopStateRetainsTheAlphabetExtension() {
        String exported = roundTrip("P=(next->STOP)+{pickupFail,putFail}.");
        assertTrue(exported, exported.contains("STOP+{pickupFail, putFail}."));
    }

    @Test
    public void lastEndStateRetainsTheAlphabetAndTermination() {
        String exported = roundTrip("P=(next->END)+{[0].fail,[1].fail}.");
        assertTrue(exported, exported.contains("END+{"));
    }

    @Test
    public void fullyUsedAlphabetNeedsNoExtensionAndPreservesError() {
        String exported = roundTrip("P=(go->P | fail->ERROR).");
        assertFalse(exported, exported.contains("+{"));
        assertTrue(exported, exported.contains("ERROR"));
    }

    private String roundTrip(String source) {
        synchronized (LTSCompiler.class) {
            CompactState original = compile(source);
            String exported = new PrintTransitions(original).getFSP(Integer.MAX_VALUE);
            CompactState restored = compile(exported);
            assertEquals(exported, new HashSet<>(Arrays.asList(original.alphabet)),
                    new HashSet<>(Arrays.asList(restored.alphabet)));
            assertBehaviorAndTermination(original, restored);
            return exported;
        }
    }

    private CompactState compile(String source) {
        LTSCompiler compiler = new LTSCompiler(new LTSInputString(source + "\n||CHECK=(P)."),
                SILENT, ".");
        compiler.compile();
        return compiler.continueCompilation("CHECK").machines.firstElement();
    }

    private void assertBehaviorAndTermination(CompactState original, CompactState restored) {
        Queue<int[]> pending = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        pending.add(new int[] {0, 0});
        while (!pending.isEmpty()) {
            int[] pair = pending.remove();
            if (!seen.add(pair[0] + ":" + pair[1])) continue;
            assertEquals("ERROR must stay distinct from ordinary terminal states", pair[0] < 0, pair[1] < 0);
            if (pair[0] < 0) continue;
            assertEquals("Successful termination must be preserved", pair[0] == original.endseq,
                    pair[1] == restored.endseq);
            Map<String, Integer> left = row(original, pair[0]);
            Map<String, Integer> right = row(restored, pair[1]);
            assertEquals("Enabled actions differ at " + Arrays.toString(pair), left.keySet(), right.keySet());
            for (String action : left.keySet()) pending.add(new int[] {left.get(action), right.get(action)});
        }
    }

    private Map<String, Integer> row(CompactState model, int state) {
        Map<String, Integer> row = new HashMap<>();
        if (model.states[state] != null) {
            Enumeration<?> edges = model.states[state].elements();
            while (edges.hasMoreElements()) {
                EventState edge = (EventState) edges.nextElement();
                assertNull("Test fixture must be deterministic", row.put(model.alphabet[edge.getEvent()], edge.getNext()));
            }
        }
        return row;
    }
}
