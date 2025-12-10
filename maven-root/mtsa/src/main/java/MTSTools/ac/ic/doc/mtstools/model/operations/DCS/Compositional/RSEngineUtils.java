package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSOutput;

import java.io.IOException;

import static org.junit.Assert.assertFalse;

public class RSEngineUtils {

    public static void synthesiseWithRSEngine(CompositeState composedSubSys, LTSOutput output, Statistics statistics, boolean spectraEngine, boolean strixEngine, boolean monolithicTranslationToRS) {
        assertFalse("Spectra and Strix engines cannot be set simultaneously.", spectraEngine && strixEngine);
        try {
            TransitionSystemDispatcher.synthesiseWithAnotherEngine(composedSubSys, spectraEngine, strixEngine, monolithicTranslationToRS, output, statistics);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
