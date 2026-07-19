package ltsa.updatingControllers.stepwise;

import ltsa.lts.CompactState;
import ltsa.updatingControllers.UpdateConstants;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepwiseActionOwnershipTest {

    @Test
    public void indexesSharedActionsFromEveryMappingComponent() {
        CompactState first = mapping(
                "MAP_FIRST",
                "tau",
                "shared.old",
                "firstOnly",
                UpdateConstants.RECONFIGURE);
        CompactState second = mapping(
                "MAP_SECOND",
                "tau",
                "shared",
                "secondOnly");

        StepwiseActionOwnership ownership = new StepwiseActionOwnership(Arrays.asList(
                stage(0, first),
                stage(1, second)));

        assertEquals(stages(0, 1), ownership.ownersOf("shared"));
        assertEquals(stages(0, 1), ownership.ownersOf("shared.old"));
        assertEquals(stages(0), ownership.ownersOf("firstOnly"));
        assertEquals(stages(1), ownership.ownersOf("secondOnly"));
        assertTrue(ownership.ownersOf(UpdateConstants.RECONFIGURE).isEmpty());
        assertTrue(ownership.ownersOf("unknown").isEmpty());
    }

    private static CompactState mapping(String name, String... alphabet) {
        CompactState result = new CompactState(name);
        result.alphabet = alphabet;
        return result;
    }

    private static StepwiseStage stage(int index, CompactState mapping) {
        return new StepwiseStage(
                index,
                "old" + index,
                "new" + index,
                "relation" + index,
                null,
                null,
                mapping);
    }

    private static Set<Integer> stages(Integer... values) {
        return new LinkedHashSet<Integer>(Arrays.asList(values));
    }
}
