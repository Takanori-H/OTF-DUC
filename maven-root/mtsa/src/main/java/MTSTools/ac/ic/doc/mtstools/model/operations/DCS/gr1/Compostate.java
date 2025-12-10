package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelationImpl;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.HAction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.HEstimate;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.Ranker;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.Recommendation;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptySet;
import static org.junit.Assert.assertSame;

/** This class represents a state in the parallel composition of the LTSs
 *  in the environment. These states are used to build the fragment of the
 *  environment required to reach the goal on-the-fly. The controller for
 *  the given problem can then be extracted directly from the partial
 *  construction achieved by using these states. */
public class Compostate<State, Action> {
    private final DirectedControllerSynthesisGR1<State, Action> directedControllerSynthesisGR1;
    /** States by each LTS in the environment that conform this state. */
    private final List<State> states; // Note: should be a set of lists for non-deterministic LTSs

    /** Indicates whether this state is a goal (1) or an error (-1) or not yet known (0). */
    private Status status;

    /** The real distance to the goal state from this state. */
    private int distance;

    /** Depth at which this state has been expanded. */
    private int depth;

    /** A ranking of the outgoing transitions from this state. */
    public List<Recommendation<State, Action>> recommendations;

    /** An iterator for the ranking of recommendations. */
    private Iterator<Recommendation<State, Action>> recommendit;

    /** Current recommendation (or null). */
    public Recommendation<State, Action> recommendation;

    /** Indicates whether the state is actively being used. */
    boolean live;

    /** Indicates whether the state is being currently explored. */
    public boolean inExplorationList;

    /** Indicates whether the state is controlled or not. */
    boolean controlled;

    /** Indicates what guarantees the compostate fulfills */
    public final Set<Integer> markedByGuarantee;

    /** Indicates what assumptions the compostate negates */
    public final Set<Integer> markedByAssumption;

    private Integer loopID;
    private Integer bestDistToWinLoop;

    /** Children states expanded following a recommendation of this state. */
    private final BinaryRelation<HAction<Action>, Compostate<State, Action>> exploredChildren;

    /** Children states expanded through uncontrollable transitions. */
    private final Set<Compostate<State, Action>> childrenExploredThroughUncontrollable;

    /** Children states expanded through controllable transitions. */
    private final Set<Compostate<State, Action>> childrenExploredThroughControllable;

    /** Parents that expanded into this state. */
    private final BinaryRelation<HAction<Action>, Compostate<State, Action>> parents;

    /** Set of actions enabled from this state. */
    private final Set<HAction<Action>> availableActions;

    /** Stores target states (i.e., already visited marked states) to reach from this state. */
    List<Set<State>> targets = emptyList();

    private boolean hasGoalChild = false;
    private boolean hasErrorChild = false;
    private int uncontrollableUnexplored = 0;


    /** Constructor for a Composed State. */
    public Compostate(DirectedControllerSynthesisGR1<State, Action> directedControllerSynthesisGR1, List<State> states) {
        this.directedControllerSynthesisGR1 = directedControllerSynthesisGR1;
        this.states = states;
        this.status = Status.NONE;
        this.distance = DirectedControllerSynthesisGR1.INF;
        this.depth = DirectedControllerSynthesisGR1.INF;
        this.live = false;
        this.inExplorationList = false;
        this.exploredChildren = new BinaryRelationImpl<>();
        this.childrenExploredThroughUncontrollable = new HashSet<>();
        this.childrenExploredThroughControllable = new HashSet<>();
        this.parents = new BinaryRelationImpl<>();
        this.loopID = -1;
        this.bestDistToWinLoop = -1;
        this.markedByGuarantee = new HashSet<>();
        for(Map.Entry<Integer, Integer> entry : directedControllerSynthesisGR1.guarantees.entrySet()) {
            int gNumber = entry.getKey();
            int gIndex = entry.getValue();

            if (directedControllerSynthesisGR1.defaultTargets.get(gIndex).contains(states.get(gIndex))) {
                markedByGuarantee.add(gNumber);
                directedControllerSynthesisGR1.composByGuarantee.get(gNumber).add(this);
            }
        }

        this.markedByAssumption = new HashSet<>();
        for(Map.Entry<Integer, Integer> entry : directedControllerSynthesisGR1.assumptions.entrySet()) {
            int aNumber = entry.getKey();
            int aIndex = entry.getValue();

            if (directedControllerSynthesisGR1.defaultTargets.get(aIndex).contains(states.get(aIndex))) {
                markedByAssumption.add(aNumber);
                directedControllerSynthesisGR1.notComposByAssumption.get(aNumber).add(this);
            }
        }

        this.availableActions = buildTransitions();
    }


    /** Returns the states that conform this composed state. */
    public List<State> getStates() {
        return states;
    }


    /** Returns the distance from this state to the goal state (INF if not yet computed). */
    public int getDistance() {
        return distance;
    }


    /** Sets the distance from this state to the goal state. */
    public void setDistance(int distance) {
        this.distance = distance;
    }


    /** Returns the depth of this state in the exploration tree. */
    public int getDepth() {
        return depth;
    }


    /** Sets the depth for this state. */
    public void setDepth(int depth) {
        if (this.depth > depth)
            this.depth = depth;
    }


    /** Indicates whether this state has been evaluated, that is, if it has
     *  a valid ranking of recommendations. */
    public boolean isEvaluated() {
        return recommendations != null;
    }

    public boolean isDeadlock(){
        return getAvailableActions().isEmpty();
    }


    ///** Returns whether this state is marked (i.e., all its components are marked). */
    //public boolean isMarked() { // removed during aggressive inlining
    //  return marked;
    //}


    /** Returns the target states to be reached from this state as a list of sets,
     *  which at the i-th position holds the set of target states of the i-th LTS. */
    public List<Set<State>> getTargets() {
        return targets;
    }


    /** Returns the target states of a given LTS to be reached from this state. */
    @SuppressWarnings("unchecked")
    public Set<State> getTargets(int lts) {
        return targets.isEmpty() ? (Set<State>)emptySet() : targets.get(lts);
    }


    /** Sets the given set as target states for this state (creates
     *  aliasing with the argument set). */
    public void setTargets(List<Set<State>> targets) {
        this.targets = targets;
    }


    /** Adds a state to this state's targets. */
    public void addTargets(Compostate<State, Action> compostate) {
        List<State> states = compostate.getStates();
        if (targets.isEmpty()) {
            targets = new ArrayList<>(directedControllerSynthesisGR1.ltssSize);
            for (int lts = 0; lts < directedControllerSynthesisGR1.ltssSize; ++lts)
                targets.add(new HashSet<>());
        }
        for (int lts = 0; lts < directedControllerSynthesisGR1.ltssSize; ++lts)
            targets.get(lts).add(states.get(lts));
    }


    /** Returns this state's status. */
    public Status getStatus() {
        return status;
    }


    /** Sets this state's status. */
    public void setStatus(Status status) {
//            logger.fine(this.toString() + " status was: " + this.status + " now is: " + status);
        if (this.status != Status.ERROR || status == Status.ERROR)
            this.status = status;
    }


    /** Indicates whether this state's status equals some other status. */
    public boolean isStatus(Status status) {
        return this.status == status;
    }

    public void setLoopID(Integer loopID){
        this.loopID = loopID;
    }

    public Integer getLoopID(){
        return this.loopID;
    }

    public void setBestDistToWinLoop(Integer bestDistToWinLoop){
        this.bestDistToWinLoop = bestDistToWinLoop;
    }

    public Integer getBestDistToWinLoop(){
        return this.bestDistToWinLoop;
    }

    /** Sorts this state's recommendations in order to be iterated properly. */
    public Recommendation<State, Action> rankRecommendations() {
        Recommendation<State, Action> result = null;
        if (!recommendations.isEmpty()) {
            recommendations.sort(new Ranker<>());
            result = recommendations.get(0);
        }
        return result;
    }


    /** Sets up the recommendation list. */
    public void setupRecommendations() {
        if (recommendations == null)
            recommendations = new ArrayList<>();
    }


    /** Adds a new recommendation to this state
     *  Recommendations should not be added after they have been sorted and
     *  with an iterator in use. */
    public void addRecommendation(Recommendation<State, Action> recommendation) {
        if (!recommendation.isControllable()){
            uncontrollableUnexplored++;
        }
        recommendations.add(recommendation);
    }


    /** Returns whether the iterator points to a valid recommendation. */
    public boolean hasValidRecommendation() {
        return recommendations != null && recommendation != null;
    }


    /** Returns whether the iterator points to a valid uncontrollable recommendation. */
    public boolean hasUncontrollableUnexplored() {
        return uncontrollableUnexplored > 0;
    }

    /** Advances the iterator to the next recommendation. */
    public Recommendation<State, Action> nextRecommendation() {
        Recommendation<State, Action> result = recommendation;
        if (!result.getAction().isControllable()){
            uncontrollableUnexplored--;
        }
        updateRecommendation();
        return result;
    }


    /** Initializes the recommendation iterator guaranteeing representation invariants. */
    public void initRecommendations() {
        recommendit = recommendations.iterator();
        updateRecommendation();
    }


    /** Initializes the recommendation iterator and current estimate for the state. */
    private void updateRecommendation() {
        if (recommendit.hasNext()) {
            recommendation = recommendit.next();
        } else {
            recommendation = null;
        }
    }


    /** Clears all recommendations from this state. */
    public void clearRecommendations() {
        if (isEvaluated()) {
            recommendations.clear();
            recommendit = null;
            recommendation = null;
        }
    }


    /** Returns whether this state is being actively used. */
    public boolean isLive() {
        return live;
    }

    public boolean hasGoalChild(){
        return hasGoalChild;
    }

    public void setHasGoalChild() {
        this.hasGoalChild = true;
    }


    public boolean hasErrorChild(){
        return hasErrorChild;
    }

    public void setHasErrorChild() {
        this.hasErrorChild = true;
    }


    /** Returns whether this state has a child with the given status. */
    public boolean hasStatusChild(Status status) {
        boolean result = false;
        for (Pair<HAction<Action>, Compostate<State, Action>> transition : getExploredChildren()) {
            if (result = transition.getSecond().status == status) break;
        }
        return result;
    }

    /** Closes this state to avoid further exploration. */
    public void close() {
        live = false;
    }


    /** Returns whether this state is controllable or not. */
    public boolean isControlled() {
        return controlled;
    }


    /** Returns the set of actions enabled from this composed state. */
    public Set<HAction<Action>> getAvailableActions() {
        return availableActions;
    }

    /** Initializes the set of actions enabled from this composed state. */
    private Set<HAction<Action>> buildTransitions() { // Note: here I can code the wia and ia behavior for non-deterministic ltss
        Set<HAction<Action>> result = new HashSet<>();
        if (directedControllerSynthesisGR1.facilitators == null) {
            for (int i = 0; i < states.size(); ++i) {
                for (Pair<Action,State> transition : directedControllerSynthesisGR1.ltss.get(i).getTransitions(states.get(i))) {
                    HAction<Action> action = directedControllerSynthesisGR1.alphabet.getHAction(transition.getFirst());
                    directedControllerSynthesisGR1.allowed.add(i, action);
                }
            }
        } else {
            for (int i = 0; i < states.size(); ++i)
                if (!directedControllerSynthesisGR1.facilitators.get(i).equals(states.get(i)))
                    for (Pair<Action,State> transition : directedControllerSynthesisGR1.ltss.get(i).getTransitions(directedControllerSynthesisGR1.facilitators.get(i))) {
                        HAction<Action> action = directedControllerSynthesisGR1.alphabet.getHAction(transition.getFirst());
                        directedControllerSynthesisGR1.allowed.remove(i, action); // remove old non-shared facilitators transitions
                    }
            for (int i = 0; i < states.size(); ++i)
                if (!directedControllerSynthesisGR1.facilitators.get(i).equals(states.get(i)))
                    for (Pair<Action,State> transition : directedControllerSynthesisGR1.ltss.get(i).getTransitions(states.get(i))) {
                        HAction<Action> action = directedControllerSynthesisGR1.alphabet.getHAction(transition.getFirst());
                        directedControllerSynthesisGR1.allowed.add(i, action); // add new non-shared facilitators transitions
                    }
        }
        result.addAll(directedControllerSynthesisGR1.allowed.getEnabled());
        directedControllerSynthesisGR1.facilitators = states;

        boolean hasUncontrollableActions = false;
        for(HAction<Action> ha : result){
            if (!ha.isControllable()){
                hasUncontrollableActions = true;
                break;
            }
        }
        controlled = !hasUncontrollableActions;
        return result;
    }


    /** Adds an expanded child to this state. */
    public void addChild(HAction<Action> action, Compostate<State, Action> child) {
        if(action.isControllable()){
            childrenExploredThroughControllable.add(child);
        }else {
            childrenExploredThroughUncontrollable.add(child);
        }

        exploredChildren.addPair(action, child);
    }


    /** Returns all transition leading to exploredChildren of this state. */
    public BinaryRelation<HAction<Action>, Compostate<State, Action>> getExploredChildren() {
        return exploredChildren;
    }

    public Set<Compostate<State, Action>> getChildrenExploredThroughUncontrollable() {
        return childrenExploredThroughUncontrollable;
    }

    public Set<Compostate<State, Action>> getChildrenExploredThroughControllable() {
        return childrenExploredThroughControllable;
    }


    /** Returns all exploredChildren of this state. */
    public List<Compostate<State, Action>> getExploredChildrenCompostates() {
        List<Compostate<State, Action>> childrenCompostates = new ArrayList<>();
        for (Pair<HAction<Action>, Compostate<State, Action>> transition : exploredChildren){
            Compostate<State, Action> child = transition.getSecond();
            childrenCompostates.add(child);
        }
        return childrenCompostates;
    }


    /** Returns the distance to the goal of a child of this compostate following a given action. */
    public int getChildDistance(HAction<Action> action) {
        int result = DirectedControllerSynthesisGR1.UNDEF; // exploredChildren should never be empty or null
        for (Compostate<State, Action> compostate : exploredChildren.getImage(action)) { // Note: maximum of non-deterministic exploredChildren
            if (result < compostate.getDistance())
                result = compostate.getDistance();
        }
        return result;
    }


    /** Adds an expanded parent to this state. */
    public void addParent(HAction<Action> action, Compostate<State, Action> parent) {
        parents.addPair(action, parent);
        setDepth(parent.getDepth() + 1);
    }

    /** Returns the inverse transition leading to parents of this state. */
    public BinaryRelation<HAction<Action>, Compostate<State, Action>> getParents() {
        return parents;
    }

    public Set<Compostate<State, Action>> getParentsOfStatus(Status st){
        Set<Compostate<State, Action>> result = new HashSet<>();
        for (Pair<HAction<Action>, Compostate<State, Action>> ancestorActionAndState : parents) {
            Compostate<State, Action> ancestor = ancestorActionAndState.getSecond();
            if(ancestor.isStatus(st)) result.add(ancestor);
        }
        return result;
    }


    /** Clears the internal state removing parent and exploredChildren. */
    public void clear() {
        exploredChildren.clear();
        /** Indicates whether the procedure consider a non-blocking requirement (by default we consider a stronger goal). */
        boolean nonblocking = false;
        if (!nonblocking) // this is a quick fix to allow reopening weak states marked as errors
            directedControllerSynthesisGR1.compostates.remove(states);
    }


    /** Returns the string representation of a composed state. */
    @Override
    public String toString() {
        return states.toString();
    }

    public HEstimate getEstimate() {
        return recommendation.getEstimate();
    }

    /**
     * Peek the next recommendation, without advancing the iterator.
     */
    public Recommendation<State, Action> peekRecommendation() {
        return recommendation;
    }
}
