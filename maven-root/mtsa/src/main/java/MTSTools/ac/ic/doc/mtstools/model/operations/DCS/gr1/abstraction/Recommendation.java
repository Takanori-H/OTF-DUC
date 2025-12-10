package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;

/**
 * This class represents a recommended course of action computed by the
 * heuristic procedure.
 */
public class Recommendation<State, Action> implements Comparable<Recommendation<State, Action>> {

    /**
     * The action recommended to explore.
     */
    private final HAction<Action> action;

    /**
     * The estimated distance to the goal.
     */
    private final HEstimate estimate;

    private Compostate<State, Action> child = null;

    /**
     * Constructor for a recommendation.
     */
    public Recommendation(HAction<Action> action, HEstimate estimate) {
        this.action = action;
        this.estimate = estimate;
    }

    /**
     * Constructor for a recommendation with a numeric estimate.
     * NumericEstimates are used to wrap a single number as an HEstimate.
     */
    public Recommendation(HAction<Action> action, Integer value) {
        this(action, new NumericEstimate(value));
    }

    public Recommendation(HAction<Action> action, Integer value, Compostate<State, Action> child) {
        this(action, value);
        this.child = child;
    }

    /**
     * Returns the action this recommendation suggests.
     */
    public HAction<Action> getAction() {
        return action;
    }


    /**
     * Returns the estimate distance to the goal for this recommendation.
     */
    public HEstimate getEstimate() {
        return estimate;
    }

    /**
     * Returns the child compostate of this recommendation, if it was set.
     */
    public Compostate<State, Action> getChild() {
        return child;
    }


    /**
     * Compares two recommendations by (<=).
     */
    @Override
    public int compareTo(Recommendation<State, Action> o) {
        return estimate.compareTo(o.estimate);
    }


    /**
     * Returns the string representation of this recommendation.
     */
    @Override
    public String toString() {
        return action.toString() + estimate;
    }

    public boolean isControllable() {
        return action.isControllable();
    }
}
