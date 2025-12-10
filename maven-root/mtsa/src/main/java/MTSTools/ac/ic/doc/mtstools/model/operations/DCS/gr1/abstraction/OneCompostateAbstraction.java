package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;

/**
 * Abstract class for abstractions.
 */
public interface OneCompostateAbstraction<State, Action> {
    void eval(Compostate<State, Action> compostate);
}
