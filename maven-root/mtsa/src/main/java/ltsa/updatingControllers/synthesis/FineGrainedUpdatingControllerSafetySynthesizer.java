package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;

import java.util.List;
import java.util.Set;

public class FineGrainedUpdatingControllerSafetySynthesizer {

    public static MTS<Long, String> synthesizeSafety(
            MTS<Long, String> metaEnvironment,
            Set<Fluent> goalFluents,
            List<Formula> safetyFormulas,
            Set<String> controllableActions,
            UpdateProtocolSpec updateProtocolSpec,
            LTSOutput output) {
        return UpdatingControllerSafetySynthesizer.synthesizeSafety(
                metaEnvironment,
                goalFluents,
                safetyFormulas,
                controllableActions,
                updateProtocolSpec.getProgressActions(),
                output);
    }
}
