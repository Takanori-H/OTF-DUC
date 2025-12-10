package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import java.util.HashSet;
import java.util.Set;

public class SOEUtils {
    public static Set<String> getLocalUncontrollableAndFormulaLabels(Set<String> localLabels, Set<String> controllableLabels, Set<String> relevantLabelsFromFormula) {
        Set<String> uncontrollableLocalLabels = new HashSet<>(localLabels);
        uncontrollableLocalLabels.removeAll(controllableLabels);
        // now i have to remove all initiating labels from fluents in formula
        uncontrollableLocalLabels.removeAll(relevantLabelsFromFormula);
        uncontrollableLocalLabels.removeIf(
                l -> l.endsWith("?") || l.equals("tau")
        );
        return uncontrollableLocalLabels;
    }
}
