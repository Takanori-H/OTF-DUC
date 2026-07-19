package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepwiseUpdatingControllerSafetySynthesizerTest {

    @Test
    public void indexedActionFluentUsesConcreteInitiatingActions() {
        String initiatingAction = "charger.1.startCharge.1";
        String otherAction = "charger.1.pause";
        Fluent source = actionFluent(
                "charger[1].startCharge[1]_a",
                initiatingAction);
        StepwiseClassifiedGoal goal = new StepwiseClassifiedGoal(
                new ltsa.lts.Symbol(ltsa.lts.Symbol.UPPERIDENT, "T1_CHARGER_1"),
                StepwiseRequirementKind.TRANSITION,
                0,
                Collections.singleton(0),
                "test",
                null,
                Collections.singleton(source),
                Collections.singleton(source));

        Set<Fluent> completedFluents =
                StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                        Collections.singletonList(goal),
                        set(initiatingAction, otherAction, "tau"));

        assertEquals(1, completedFluents.size());
        Fluent completed = completedFluents.iterator().next();
        assertEquals(set(initiatingAction), symbolNames(completed.getInitiatingActions()));
        assertEquals(set(otherAction), symbolNames(completed.getTerminatingActions()));
        assertTrue(Collections.disjoint(
                completed.getInitiatingActions(),
                completed.getTerminatingActions()));
    }

    private static Fluent actionFluent(String name, String... initiatingActions) {
        Set<Symbol> initiating = new HashSet<Symbol>();
        for (String action : initiatingActions) {
            initiating.add(new SingleSymbol(action));
        }
        return new FluentImpl(
                name,
                initiating,
                Collections.<Symbol>emptySet(),
                false);
    }

    private static Set<String> symbolNames(Collection<Symbol> symbols) {
        Set<String> names = new HashSet<String>();
        for (Symbol symbol : symbols) {
            names.add(symbol.toString());
        }
        return names;
    }

    private static Set<String> set(String... values) {
        return new HashSet<String>(Arrays.asList(values));
    }
}
