package ltsa.lts.ltl;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import ltsa.lts.ActionLabels;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

public class FormulaTransformerVisitorTest {

    @Test
    public void expandsCompactMultiIndexActionPredicateToConcreteActions() {
        Fluent fluent = transformActionPredicate(
                "drone.1.arrive.2.1.1",
                "drone.1.arrive.2.1.2",
                "drone.1.arrive.2.2.1",
                "drone.1.arrive.2.2.2");

        Set<String> expected = new HashSet<String>(Arrays.asList(
                "drone.1.arrive.2.1.1",
                "drone.1.arrive.2.1.2",
                "drone.1.arrive.2.2.1",
                "drone.1.arrive.2.2.2"));

        assertEquals(expected, symbolNames(fluent.getInitiatingActions()));
        assertFalse(symbolNames(fluent.getInitiatingActions()).contains("drone.1"));
        assertEquals("drone[1].arrive[2][1..2][1..2]_a", fluent.getName());
    }

    @Test
    public void actionPredicatesWithTheSameFirstIndexHaveDistinctFluentNames() {
        Fluent arrive = transformActionPredicate(
                "drone.1.arrive.2.1",
                "drone.1.arrive.2.2");
        Fluent depart = transformActionPredicate(
                "drone.1.depart.2.1",
                "drone.1.depart.2.2");

        assertNotEquals(arrive.getName(), depart.getName());
    }

    @Test
    public void preservesEveryMemberOfAnExplicitActionSet() {
        Fluent fluent = transformActionPredicate("alpha", "beta", "gamma");

        assertEquals(
                new HashSet<String>(Arrays.asList("alpha", "beta", "gamma")),
                symbolNames(fluent.getInitiatingActions()));
    }

    private Fluent transformActionPredicate(String... actions) {
        FormulaFactory factory = new FormulaFactory();
        Formula formula = factory.make(
                new FixedActionLabels(Arrays.asList(actions)),
                new Hashtable(),
                new Hashtable());
        FormulaTransformerVisitor visitor = new FormulaTransformerVisitor();
        formula.accept(visitor);

        assertEquals(1, visitor.getActionFluentsForUpdate().size());
        return visitor.getActionFluentsForUpdate().iterator().next();
    }

    private Set<String> symbolNames(Collection<Symbol> symbols) {
        Set<String> names = new HashSet<String>();
        for (Symbol symbol : symbols) {
            names.add(symbol.toString());
        }
        return names;
    }

    private static final class FixedActionLabels extends ActionLabels {
        private final Vector<String> actions;

        private FixedActionLabels(Collection<String> actions) {
            this.actions = new Vector<String>(actions);
        }

        @Override
        public Vector<String> getActions(Hashtable locals, Hashtable constants) {
            return new Vector<String>(actions);
        }

        @Override
        public boolean hasMoreNames() {
            return false;
        }

        @Override
        protected String computeName() {
            return null;
        }

        @Override
        protected void next() {
        }

        @Override
        protected void initialise() {
        }

        @Override
        protected ActionLabels make() {
            return new FixedActionLabels(actions);
        }
    }
}
