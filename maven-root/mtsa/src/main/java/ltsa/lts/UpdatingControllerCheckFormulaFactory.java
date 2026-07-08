package ltsa.lts;

import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.FormulaSyntax;

public final class UpdatingControllerCheckFormulaFactory {

    private UpdatingControllerCheckFormulaFactory() {
    }

    public static Symbol addGuardedSafetyFormula(
            Symbol originalName,
            String guardAction,
            boolean positiveGuard,
            String prefix) {
        AssertDefinition definition = AssertDefinition.getConstraint(originalName.getName());
        if (definition == null) {
            throw new IllegalArgumentException("ltl_property not found: " + originalName.getName());
        }
        FormulaSyntax original = definition.getLTLFormula().removeLeftTemporalOperators();
        FormulaSyntax guard = FormulaSyntax.make(new ActionName(new Symbol(123, guardAction)));
        FormulaSyntax antecedent = positiveGuard
                ? guard
                : FormulaSyntax.make(null, new Symbol(Symbol.PLING), guard);
        FormulaSyntax wrapped = FormulaSyntax.make(antecedent, new Symbol(Symbol.ARROW), original);
        Symbol wrapperName = new Symbol(Symbol.UPPERIDENT,
                prefix + originalName.getName() + "_" + Math.abs(wrapped.hashCode()));
        AssertDefinition.put(wrapperName, wrapped, null, null, null, true, false);
        return wrapperName;
    }
}
