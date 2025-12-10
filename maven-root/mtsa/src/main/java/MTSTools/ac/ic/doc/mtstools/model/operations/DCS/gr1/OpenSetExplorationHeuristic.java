package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.*;

import java.util.*;

public class OpenSetExplorationHeuristic<State, Action> implements ExplorationHeuristic<State, Action> {

    /** Queue of open states, the most promising state should be expanded first. */
    Queue<Compostate<State, Action>> open;

    /** Abstraction used to rank the transitions from a state. */
    OneCompostateAbstraction<State,Action> abstraction;

    DirectedControllerSynthesisGR1<State,Action> dcs;

    private List<Set<State>> knownMarked;
    private List<Set<State>> goals;

    public OpenSetExplorationHeuristic(
            DirectedControllerSynthesisGR1<State,Action> dcs,
            AbstractionMode mode) {

        Comparator<Compostate<State, Action>> compostateRanker = new DefaultCompostateRanker<>();
        this.dcs = dcs;
        this.knownMarked = new ArrayList<>(dcs.ltssSize); // TODO check if this is correct
        this.goals = new ArrayList<>(dcs.ltssSize);
        for (int lts = 0; lts < dcs.ltssSize; ++lts){
            this.knownMarked.add(new HashSet<>());
            this.goals.add(new HashSet<>());
        }

        switch (mode){
            case Monotonic:
                abstraction = new MonotonicAbstraction<>(dcs);
                System.err.println("Using MonotonicAbstraction");
                break;
            case Ready:
                abstraction = new ReadyAbstraction<>(dcs.ltss, dcs.defaultTargets, dcs.alphabet);
                compostateRanker = new ReadyAbstraction.CompostateRanker<>();
                System.err.println("Using ReadyAbstraction");
                break;
            case Debugging:
                abstraction = new DebuggingAbstraction<>();
                System.err.println("Using DebuggingAbstraction");
                break;
        }
        open = new PriorityQueue<>(compostateRanker);
    }

    public Pair<Compostate<State,Action>, HAction<Action>> getNextStep() {
        Compostate<State,Action> state = getNextState();
        Recommendation<State, Action> recommendation = state.nextRecommendation();
        return new Pair<>(state, recommendation.getAction());
    }

    public Compostate<State,Action> getNextState() {
        Compostate<State,Action> state = open.remove();
        state.inExplorationList = false;
        return state;
    }

    public boolean somethingLeftToExplore() {
        return !open.isEmpty();
    }

    /** Adds this state to the open queue (reopening it if was previously closed). */
    public boolean open(Compostate<State,Action> state) {
        // System.err.println("opening" + state);
        boolean result = false;
        state.live = true;
        if (!state.inExplorationList) {
            if (!state.hasStatusChild(Status.NONE)) {
                result = addToOpen(state);
            } else { // we are reopening a state, thus we reestablish it's exploredChildren instead
                for (Pair<HAction<Action>, Compostate<State, Action>> transition : state.getExploredChildren()) {
                    Compostate<State, Action> child = transition.getSecond();
                    if (!child.isLive() && child.isStatus(Status.NONE) && !fullyExplored(child)) // !isGoal(child)
                        result |= open(child);
                }
                if (!result || state.isControlled()){
                    result = addToOpen(state);
                }
            }
        }
        return result;
    }

    public boolean addToOpen(Compostate<State, Action> state) {
        state.inExplorationList = true;
        return open.add(state);
    }

    public void setInitialState(Compostate<State, Action> state) {
        open(state);
        newState(state);
    }

    public void newState(Compostate<State, Action> state) {
        abstraction.eval(state);
    }

    public void notifyStateIsNone(Compostate<State, Action> state) {
        if(!fullyExplored(state))
            open(state);
    }

    public void notifyStateSetErrorOrGoal(Compostate<State, Action> state) {
        state.live = false;
        state.clearRecommendations();
    }

    public void expansionDone(Compostate<State, Action> state, HAction<Action> action, Compostate<State, Action> child) {
        if (state.isControlled() && state.isStatus(Status.NONE) && !fullyExplored(state)) {
            open(state);
        }
    }

    public void notifyExpansionDidntFindAnything(Compostate<State, Action> parent, HAction<Action> action, Compostate<State, Action> child) {
        if (!child.isLive() && !fullyExplored(child)) {
            open(child);
        }
    }

    public boolean fullyExplored(Compostate<State, Action> state) {
        return !state.hasValidRecommendation();
    }
}
