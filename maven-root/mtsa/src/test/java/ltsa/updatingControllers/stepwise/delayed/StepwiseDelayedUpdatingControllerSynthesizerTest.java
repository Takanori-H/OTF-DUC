package ltsa.updatingControllers.stepwise.delayed;

import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTSConstants;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StepwiseDelayedUpdatingControllerSynthesizerTest {

    @Test
    public void reservedTauAlphabetEntryIsNotAComponentRealAction() {
        MTS<Long, String> mapping = new MTSImpl<Long, String>(0L);
        mapping.addAction(MTSConstants.TAU);
        mapping.addAction("work");
        mapping.addRequired(0L, "work", 0L);

        Set<String> realActions =
                StepwiseDelayedUpdatingControllerSynthesizer.mappingRealActions(mapping);

        assertFalse(realActions.contains(MTSConstants.TAU));
        assertTrue(realActions.contains("work"));
        assertTrue("The diagnostic/fix must not mutate the mapping MTS alphabet.",
                mapping.getActions().contains(MTSConstants.TAU));
    }
}
