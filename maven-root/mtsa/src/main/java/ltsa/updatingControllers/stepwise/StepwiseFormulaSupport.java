package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.AndFormula;
import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentPropositionalVariable;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.condition.NotFormula;
import ltsa.lts.Diagnostics;
import ltsa.lts.Symbol;
import ltsa.lts.chart.util.FormulaUtils;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.FormulaFactory;
import ltsa.lts.ltl.FormulaSyntax;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;

import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

public final class StepwiseFormulaSupport {

    private StepwiseFormulaSupport() {
    }

    public static ExtractedFormula extract(Symbol symbol, StepwiseRequirementKind kind) {
        Set<Fluent> fluents = new HashSet<Fluent>();
        UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.clear();
        try {
            Formula formula = adaptFormulaWithoutLeadingTemporalOperators(symbol.getName(), fluents);
            Set<Fluent> actionFluents =
                    new HashSet<Fluent>(UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE);

            if (StepwiseRequirementKind.OLD_SAFETY.equals(kind)) {
                fluents.add(UpdatingControllersUtils.stopFluent);
                formula = new AndFormula(
                        new NotFormula(new FluentPropositionalVariable(UpdatingControllersUtils.stopFluent)),
                        formula);
            } else if (StepwiseRequirementKind.NEW_SAFETY.equals(kind)) {
                fluents.add(UpdatingControllersUtils.startFluent);
                formula = new AndFormula(
                        new FluentPropositionalVariable(UpdatingControllersUtils.startFluent),
                        formula);
            }

            return new ExtractedFormula(formula, fluents, actionFluents);
        } finally {
            UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.clear();
        }
    }

    private static Formula adaptFormulaWithoutLeadingTemporalOperators(String assertionName, Set<Fluent> formulaFluents) {
        AssertDefinition originalDef = AssertDefinition.getConstraint(assertionName);
        if (originalDef == null) {
            originalDef = AssertDefinition.getDefinition(assertionName);
        }
        if (originalDef == null) {
            Diagnostics.fatal("Assertion not defined [" + assertionName + "].");
        }
        FormulaSyntax strippedSyntax = originalDef.getLTLFormula().removeLeftTemporalOperators();
        FormulaFactory factory = new FormulaFactory();
        Hashtable initParams = originalDef.getInitParams() != null ? originalDef.getInitParams() : new Hashtable();
        factory.setFormula(strippedSyntax.expand(factory, new Hashtable(), initParams));
        return FormulaUtils.adaptFormulaAndCreateFluents(factory.getFormula(), formulaFluents);
    }

    public static final class ExtractedFormula {
        private final Formula formula;
        private final Set<Fluent> fluents;
        private final Set<Fluent> actionFluentsForUpdate;

        private ExtractedFormula(
                Formula formula,
                Set<Fluent> fluents,
                Set<Fluent> actionFluentsForUpdate) {
            this.formula = formula;
            this.fluents = fluents;
            this.actionFluentsForUpdate = actionFluentsForUpdate;
        }

        public Formula getFormula() {
            return formula;
        }

        public Set<Fluent> getFluents() {
            return new HashSet<Fluent>(fluents);
        }

        public Set<Fluent> getActionFluentsForUpdate() {
            return new HashSet<Fluent>(actionFluentsForUpdate);
        }
    }
}
