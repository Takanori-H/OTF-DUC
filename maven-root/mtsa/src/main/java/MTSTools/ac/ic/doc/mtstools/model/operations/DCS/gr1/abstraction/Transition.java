package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;

public class Transition<State, Action> {
    private Compostate<State, Action> state;
    private HAction<Action> action;
    private Compostate<State, Action> child;

    public Transition(Compostate<State, Action> parent, HAction<Action> action, Compostate<State, Action> child) {
        this.state = parent;
        this.action = action;
        this.child = child;
    }

    public Compostate<State, Action> getState() {
        return state;
    }

    public HAction<Action> getAction() {
        return action;
    }

    public Compostate<State, Action> getChild() {
        return child;
    }

    @Override
    public String toString() {
        return "Transition(" + state.toString() + " -- " + action.toString() + " --> " + child.toString() + ")";
    }
}
