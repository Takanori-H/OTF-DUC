package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.lts.CompositeState;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatingControllersUtilsTest {

    @Test
    public void finalPostprocessingNormalizesAndCompletesAlphabetWithoutAddingTransitions() {
        MTS<Long, String> internalController = new MTSImpl<Long, String>(0L);
        internalController.addAction("enabled.old");
        internalController.addAction("alphabetOnly.old");
        internalController.addAction("existing");
        internalController.addRequired(0L, "enabled.old", 0L);
        internalController.addRequired(0L, "existing", 0L);

        CompositeState controller = new CompositeState();
        controller.composition = MTSToAutomataConverter.getInstance().convert(
                internalController, "ControllerBeforeOutput", false, true);

        UpdatingControllersUtils.removeOldTransitions(
                controller,
                new LinkedHashSet<String>(Arrays.asList("declaredOnly")));

        MTS<Long, String> outputController =
                AutomataToMTSConverter.getInstance().convert(controller.composition);
        assertTrue(outputController.getActions().contains("enabled"));
        assertTrue(outputController.getActions().contains("alphabetOnly"));
        assertTrue(outputController.getActions().contains("declaredOnly"));
        assertTrue(outputController.getActions().contains("existing"));
        for (String action : outputController.getActions()) {
            assertFalse(action.endsWith(".old"));
        }

        assertEquals(1, transitionCount(outputController, "enabled"));
        assertEquals(1, transitionCount(outputController, "existing"));
        assertEquals(0, transitionCount(outputController, "alphabetOnly"));
        assertEquals(0, transitionCount(outputController, "declaredOnly"));
        assertEquals(2, totalTransitionCount(outputController));
    }

    private static int transitionCount(MTS<Long, String> environment, String action) {
        int count = 0;
        for (Long state : environment.getStates()) {
            for (Pair<String, Long> transition :
                    environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                if (action.equals(transition.getFirst())) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int totalTransitionCount(MTS<Long, String> environment) {
        int count = 0;
        for (Long state : environment.getStates()) {
            count += environment.getTransitions(
                    state, MTS.TransitionType.REQUIRED).size();
        }
        return count;
    }
}
