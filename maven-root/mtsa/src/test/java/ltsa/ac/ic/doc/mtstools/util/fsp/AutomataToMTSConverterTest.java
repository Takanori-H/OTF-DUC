package ltsa.ac.ic.doc.mtstools.util.fsp;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public class AutomataToMTSConverterTest {

    @Test
    public void singletonRetainsNoPerConversionObjectGraph() {
        for (Field field : AutomataToMTSConverter.class.getDeclaredFields()) {
            assertTrue(
                    "Converter singleton must not retain per-conversion field " + field.getName(),
                    Modifier.isStatic(field.getModifiers()));
        }
    }

    @Test
    public void sequentialConversionsRemainIndependent() {
        CompactState firstAutomaton = compactState("FIRST", "first");
        CompactState secondAutomaton = compactState("SECOND", "second");

        MTS<Long, String> first =
                AutomataToMTSConverter.getInstance().convert(firstAutomaton);
        MTS<Long, String> second =
                AutomataToMTSConverter.getInstance().convert(secondAutomaton);

        assertNotSame(first, second);
        assertTrue(first.getActions().contains("first"));
        assertFalse(first.getActions().contains("second"));
        assertTrue(second.getActions().contains("second"));
        assertFalse(second.getActions().contains("first"));
        assertTrue(hasRequiredSelfLoop(first, "first"));
        assertTrue(hasRequiredSelfLoop(second, "second"));
    }

    private static CompactState compactState(String name, String action) {
        MTS<Long, String> mts = new MTSImpl<Long, String>(0L);
        mts.addAction(action);
        mts.addRequired(0L, action, 0L);
        return MTSToAutomataConverter.getInstance().convert(
                mts,
                name,
                false,
                false);
    }

    private static boolean hasRequiredSelfLoop(
            MTS<Long, String> mts,
            String action) {
        for (Pair<String, Long> transition :
                mts.getTransitions(mts.getInitialState(), MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst())
                    && mts.getInitialState().equals(transition.getSecond())) {
                return true;
            }
        }
        return false;
    }
}
