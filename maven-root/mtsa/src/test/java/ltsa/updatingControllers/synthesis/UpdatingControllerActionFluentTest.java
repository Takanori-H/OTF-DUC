package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.control.util.ControllerUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdatingControllerActionFluentTest {

    @Test
    public void indexedInitiatingActionIsNotAlsoTerminating() {
        String initiatingAction = "charger.1.startCharge.1";
        String otherAction = "charger.1.pause";
        Fluent actionFluent = actionFluent(
                "charger[1].startCharge[1]_a",
                initiatingAction);

        Fluent completed = UpdatingControllersUtils.completeActionFluentTerminatingActions(
                set(initiatingAction, otherAction, "tau"),
                actionFluent);

        assertEquals(set(initiatingAction), symbolNames(completed.getInitiatingActions()));
        assertEquals(set(otherAction), symbolNames(completed.getTerminatingActions()));
        assertTrue(Collections.disjoint(
                completed.getInitiatingActions(),
                completed.getTerminatingActions()));

        MTS<Long, String> monitor = ControllerUtils.getModelFrom(completed);
        assertDeterministic(monitor);
        assertEquals(Collections.singleton(0L),
                monitor.getTransitions(1L, MTS.TransitionType.REQUIRED)
                        .getImage(initiatingAction));
        assertEquals(Collections.singleton(1L),
                monitor.getTransitions(0L, MTS.TransitionType.REQUIRED)
                        .getImage(otherAction));
    }

    @Test
    public void everyMemberOfMultiActionPredicateIsExcludedFromTerminatingSet() {
        Fluent actionFluent = actionFluent("{alpha,beta}_a", "alpha", "beta");

        Fluent completed = UpdatingControllersUtils.completeActionFluentTerminatingActions(
                set("alpha", "beta", "gamma", "tau"),
                actionFluent);

        assertEquals(set("alpha", "beta"),
                symbolNames(completed.getInitiatingActions()));
        assertEquals(set("gamma"), symbolNames(completed.getTerminatingActions()));
        assertFalse(symbolNames(completed.getTerminatingActions()).contains("tau"));
        assertTrue(Collections.disjoint(
                completed.getInitiatingActions(),
                completed.getTerminatingActions()));
        assertDeterministic(ControllerUtils.getModelFrom(completed));
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

    private static void assertDeterministic(MTS<Long, String> monitor) {
        for (Long state : monitor.getStates()) {
            for (String action : monitor.getActions()) {
                assertTrue(
                        "Multiple successors for state=" + state + ", action=" + action,
                        monitor.getTransitions(state, MTS.TransitionType.REQUIRED)
                                .getImage(action).size() <= 1);
            }
        }
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
