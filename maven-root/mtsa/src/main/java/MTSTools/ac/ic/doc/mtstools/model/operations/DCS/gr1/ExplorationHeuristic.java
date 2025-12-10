package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.HAction;

public interface ExplorationHeuristic<State, Action> {

    void setInitialState(Compostate<State, Action> initial);

    boolean somethingLeftToExplore();

    void expansionDone(Compostate<State, Action> first, HAction<Action> second, Compostate<State, Action> child);

    Pair<Compostate<State,Action>, HAction<Action>> getNextStep();

    void notifyStateIsNone(Compostate<State, Action> state);

    void notifyStateSetErrorOrGoal(Compostate<State, Action> state);

    void newState(Compostate<State, Action> state);

    void notifyExpansionDidntFindAnything(Compostate<State, Action> parent, HAction<Action> action, Compostate<State, Action> child);

    boolean fullyExplored(Compostate<State, Action> state);
}
