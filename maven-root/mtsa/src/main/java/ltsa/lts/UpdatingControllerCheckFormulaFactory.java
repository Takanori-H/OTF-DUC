package ltsa.lts;

import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.FormulaSyntax;
import ltsa.lts.ltl.PredicateDefinition;
import ltsa.updatingControllers.UpdateConstants;

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
        Symbol phaseName = new Symbol(Symbol.UPPERIDENT,
                prefix + "PHASE_" + guardAction.toUpperCase());
        if (!PredicateDefinition.contains(phaseName)) {
            PredicateDefinition.put(
                    phaseName,
                    null,
                    new ActionName(new Symbol(Symbol.IDENTIFIER, guardAction)),
                    new ActionName(new Symbol(Symbol.IDENTIFIER, UpdateConstants.BEGIN_UPDATE)),
                    null);
        }
        FormulaSyntax guard = FormulaSyntax.make(phaseName);
        FormulaSyntax antecedent = positiveGuard
                ? guard
                : FormulaSyntax.make(null, new Symbol(Symbol.PLING), guard);
        FormulaSyntax implication = FormulaSyntax.make(
                antecedent, new Symbol(Symbol.ARROW), original);
        FormulaSyntax wrapped = FormulaSyntax.make(
                null, new Symbol(Symbol.ALWAYS), implication);
        Symbol wrapperName = new Symbol(Symbol.UPPERIDENT,
                prefix + originalName.getName() + "_" + Math.abs(wrapped.hashCode()));
        AssertDefinition.put(
                wrapperName,
                wrapped,
                definition.getAlphaExtension(),
                definition.getInitParams(),
                definition.getParams(),
                true,
                false);
        return wrapperName;
    }
}
