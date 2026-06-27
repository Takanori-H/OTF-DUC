package ltsa.updatingControllers.stepwise;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import ltsa.lts.Symbol;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class StepwiseClassifiedGoal {

    private final Symbol symbol;
    private final StepwiseRequirementKind kind;
    private final int stageIndex;
    private final Set<Integer> stageScope;
    private final String classificationReason;
    private final Formula formula;
    private final Set<Fluent> fluents;
    private final Set<Fluent> actionFluentsForUpdate;

    public StepwiseClassifiedGoal(
            Symbol symbol,
            StepwiseRequirementKind kind,
            int stageIndex,
            Set<Integer> stageScope,
            String classificationReason,
            Formula formula,
            Set<Fluent> fluents,
            Set<Fluent> actionFluentsForUpdate) {
        this.symbol = symbol;
        this.kind = kind;
        this.stageIndex = stageIndex;
        this.stageScope = new TreeSet<Integer>(stageScope);
        this.classificationReason = classificationReason;
        this.formula = formula;
        this.fluents = new HashSet<Fluent>(fluents);
        this.actionFluentsForUpdate = new HashSet<Fluent>(actionFluentsForUpdate);
    }

    public Symbol getSymbol() {
        return symbol;
    }

    public String getName() {
        return symbol.getName();
    }

    public StepwiseRequirementKind getKind() {
        return kind;
    }

    public int getStageIndex() {
        return stageIndex;
    }

    public Set<Integer> getStageScope() {
        return Collections.unmodifiableSet(stageScope);
    }

    public String getClassificationReason() {
        return classificationReason;
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
