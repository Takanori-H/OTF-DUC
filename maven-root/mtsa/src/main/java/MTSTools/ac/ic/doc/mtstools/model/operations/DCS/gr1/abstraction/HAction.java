package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Alphabet;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;

/**
 * This class serves as a proxy for the Action type argument.
 * Internally it is represented simply by its hash value.
 * Together with the alphabet allows for a high-performance
 * representation of transitions.
 */
public class HAction<Action> implements Comparable<HAction<Action>> {

    private final DirectedControllerSynthesisGR1<?, Action> directedControllerSynthesisGR1;
    /**
     * Hash value for this action.
     */
    public final int hash;


    /**
     * Constructor for an HAction given a common Action.
     */
    public HAction(DirectedControllerSynthesisGR1<?, Action> directedControllerSynthesisGR1, Alphabet<Action> alphabet, Action action) {
        this.directedControllerSynthesisGR1 = directedControllerSynthesisGR1;
        hash = alphabet.actions.size();
        alphabet.actions.add(action);
        alphabet.hactions.add(this);
        if (directedControllerSynthesisGR1.controllable.isEmpty() || directedControllerSynthesisGR1.controllable.contains(action))
            alphabet.controlbits.set(hash);
    }


    /**
     * Returns true if two HActions are equals.
     */
    @Override
    public boolean equals(Object obj) {
        boolean result = false;
        if (obj instanceof HAction) {
            @SuppressWarnings("unchecked")
            HAction other = (HAction) obj;
            result = this.hash == other.hash;
        }
        return result;
    }


    /**
     * Returns the hash code for this HAction.
     */
    @Override
    public int hashCode() {
        return hash;
    }


    /**
     * Indicates whether this action is controllable.
     */
    public boolean isControllable() {
        return directedControllerSynthesisGR1.alphabet.controlbits.get(hash);
    }


    /**
     * Returns the Action this HAction proxies.
     */
    public Action getAction() {
        return directedControllerSynthesisGR1.alphabet.actions.get(hash);
    }

    /**
     * Return the String representation of this HAction.
     */
    @Override
    public String toString() {
        return getAction().toString();
    }


    /**
     * Compares two actions.
     */
    @Override
    public int compareTo(HAction o) {
        return hash - o.hash;
    }

}
