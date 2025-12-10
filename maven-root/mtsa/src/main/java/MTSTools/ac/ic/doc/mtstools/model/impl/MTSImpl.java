package MTSTools.ac.ic.doc.mtstools.model.impl;

import java.util.*;

import MTSSynthesis.controller.MonolithicFeatureCalculator;
import ltsa.lts.CompactState;
import org.apache.commons.lang.Validate;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;

public class MTSImpl<State, Action> extends AbstractTransitionSystem<State, Action> implements MTS<State, Action> {
	// for ROLES feature in Monolithic (receives a Plant that is a composition of "components"):
	private CompactState[] components;
	private HashMap<AbstractMap.SimpleEntry<Integer, String>, int[]> statePlusActionToComponentStates;
	private HashMap<Integer, int[]> stateToComponentStates;
	private HashMap<Integer, String> stateToRole;
	//---------------------------------------------------------------------------------------

	private EnumMap<TransitionType, Map<State, BinaryRelation<Action, State>>> transitionsByType;
	private Set<Long> winningStates = new HashSet<>();
	private Set<Long> winningStatesOfOriginalEnv = new HashSet<>();

	public MTSImpl(State initialState, CompactState automata) {
		super(initialState);
		this.components = automata.components;
		this.statePlusActionToComponentStates = automata.statePlusActionToComponentStates;
		this.stateToComponentStates = automata.stateToComponentStates;
		this.stateToRole = automata.getStateToRoleMap();
	}

	public MTSImpl(State initialState) {
		super(initialState);
	}

	public boolean addPossible(State from, Action label, State to) {
		this.validateNewTransition(from, label, to);
		boolean added = this.getTransitionsForInternalUpdate(from, TransitionType.POSSIBLE).addPair(label, to);
		if (added) {
			this.getTransitionsForInternalUpdate(from, TransitionType.MAYBE).addPair(label, to);
		}
		return added;
	}

	public boolean addRequired(State from, Action label, State to) {
		this.validateNewTransition(from, label, to);
		this.getTransitionsForInternalUpdate(from, TransitionType.POSSIBLE).addPair(label, to);
		boolean added = this.getTransitionsForInternalUpdate(from, TransitionType.REQUIRED).addPair(label, to);
		if (added) {
			this.getTransitionsForInternalUpdate(from, TransitionType.MAYBE).removePair(label, to);
		}
		return added;
	}

	public boolean addState(State state) {
		if (super.addState(state)) {
			for (TransitionType type : TransitionType.values()) {
				this.getTransitionsByType().get(type).put(state, this.newRelation());
			}
			return true;
		}
		return false;
	}

	public boolean addTransition(State from, Action label, State to, TransitionType type) {
		this.validateNewTransition(from, label, to);
		return type.addTransition(this, from, label, to);
	}

	public BinaryRelation<Action, State> getTransitions(State state, TransitionType type) {
		return this.getTransitions(type).get(state);
	}

	protected BinaryRelation<Action, State> getTransitionsForInternalUpdate(State state, TransitionType type) {
		return this.getTransitions(state, type);
	}

	public Map<State, BinaryRelation<Action, State>> getTransitions(TransitionType type) {
		return this.getTransitionsByType().get(type);
	}

	protected EnumMap<TransitionType, Map<State, BinaryRelation<Action, State>>> getTransitionsByType() {
		if (this.transitionsByType == null) {
			this.setTransitionsByType(new EnumMap<TransitionType, Map<State, BinaryRelation<Action, State>>>
					(TransitionType.class));
			for (TransitionType type : TransitionType.values()) {
				this.transitionsByType.put(type, new HashMap<State, BinaryRelation<Action, State>>());
			}
		}
		return this.transitionsByType;
	}

	protected void setTransitionsByType(EnumMap<TransitionType, Map<State, BinaryRelation<Action, State>>>
			                                    transitionsByType) {
		this.transitionsByType = transitionsByType;
	}

	private void validateExistingTransition(State from, Action label, State to, TransitionType possible) {
		Validate.isTrue(getTransitions(from, possible).contains(Pair.create(label, to)));
	}

	/**
	 * Elimina la transicion si existe y ademas elimina los posibles estados que
	 * quedaran huerfanos luego de borrar la transicion.
	 * 
	 */
	public boolean removeTransition(State from, Action label, State to, TransitionType type) {
		this.validateNewTransition(from, label, to);
		this.validateExistingTransition(from, label, to, type);
		return type.removeTransition(this, from, label, to);
	}

	public boolean removePossible(State from, Action label, State to) {
		boolean removed = this.getTransitionsForInternalUpdate(from, TransitionType.POSSIBLE).removePair(label, to);
		if (removed) {
			this.getTransitionsForInternalUpdate(from, TransitionType.MAYBE).removePair(label, to);
			this.getTransitionsForInternalUpdate(from, TransitionType.REQUIRED).removePair(label, to);
		}
		return removed;
	}

	public boolean removeRequired(State from, Action label, State to) {
		boolean removed = this.getTransitionsForInternalUpdate(from, TransitionType.REQUIRED).removePair(label, to);
		if (removed) {
			removed &= this.getTransitionsForInternalUpdate(from, TransitionType.POSSIBLE).removePair(label, to);
		}
		return removed;
	}

	@Override
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("States: ").append(this.getStates()).append("\r\n");
		sb.append("Actions: ").append(this.getActions()).append("\r\n");
		sb.append("Required Transitions: ").append(this.getTransitions(TransitionType.REQUIRED)).append("\r\n");
		sb.append("Maybe Transitions: ").append(this.getTransitions(TransitionType.MAYBE)).append("\r\n");
		return sb.toString();
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		if (!super.equals(o)) {
			return false;
		}

		MTSImpl<?, ?> mts = (MTSImpl<?, ?>) o;
		boolean maybeEquals = !(getTransitions(TransitionType.MAYBE) != null ? !getTransitions(TransitionType.MAYBE)
				.equals(mts.getTransitions(TransitionType.MAYBE)) : mts.getTransitions(TransitionType.MAYBE) != null);

		boolean requiredEquals = !(getTransitions(TransitionType.REQUIRED) != null ? !getTransitions(TransitionType
				.REQUIRED).equals(mts.getTransitions(TransitionType.REQUIRED)) : mts.getTransitions(TransitionType
				.REQUIRED) != null);
		return maybeEquals && requiredEquals;
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + (getTransitions(TransitionType.MAYBE) != null ? getTransitions(TransitionType.MAYBE)
				.hashCode() : 0) + (getTransitions(TransitionType.REQUIRED) != null ? getTransitions(TransitionType
				.REQUIRED).hashCode() : 0);
		return result;
	}

	protected BinaryRelation<Action, State> getTransitionsFrom(State state) {
		return getTransitions(state, TransitionType.REQUIRED);
	}

	public void removeAction(Action action) {
		if (!hasTransitionOn(action)) {
			getInternalActions().remove(action);
		}

	}

	public boolean hasTransitionOn(Action action) {
		for (State state : getStates()) {
			if (!getTransitionsFrom(state).getImage(action).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	// TODO Dipi, refactorizar
	// public Set<State> getReachableStatesBy(State state, TransitionType
	// transitionType) {
	// Set<State> reachableStates = new
	// HashSet<State>((int)(this.getStates().size()/.75f + 1),0.75f);
	// Queue<State> toProcess = new LinkedList<State>();
	// toProcess.offer(state);
	// reachableStates.add(state);
	// while(!toProcess.isEmpty()) {
	// for (Pair<Action, State> transition : getTransitions(toProcess.poll(),
	// transitionType)) {
	// if (!reachableStates.contains(transition.getSecond())) {
	// toProcess.offer(transition.getSecond());
	// reachableStates.add(transition.getSecond());
	// }
	// }
	// }
	// return reachableStates;
	// }
	//
	// @Override
	// protected Collection<State> getReachableStatesBy(State state) {
	// return getReachableStatesBy(state, TransitionType.POSSIBLE);
	// }

	@Override
	protected void removeTransitions(Collection<State> unreachableStates) {
		for (Map<State, BinaryRelation<Action, State>> transitions : this.transitionsByType.values()) {
			this.removeTransitions(transitions, unreachableStates);
		}
	}

	public int getNumberOfTransitions(){
		int s = 0;
		for (Map<State, BinaryRelation<Action, State>> v : transitionsByType.values()){
			s += v.size();
		}
		return s;
	}

	public void setWinningStates(Set<Long> winningStatesOfPlant){
		winningStates = winningStatesOfPlant;
	}

    public void setWinningStatesOfOriginalEnv(Set<Long> winningStates) {
		winningStatesOfOriginalEnv = winningStates;
    }

	public Set<Long> getWinningStatesOfOriginalEnv() {
		return winningStatesOfOriginalEnv;
	}

    // ROLES feature for Monolithic
	public HashMap<AbstractMap.SimpleEntry<Integer, String>, int[]> getStatePlusActionToComponentStates() {
		return statePlusActionToComponentStates;
	}

	public HashMap<Integer, String> buildStateToRoleMap() {
		if(!this.isComposition()){
			return stateToRole;
		}

		HashMap<Integer, String> buildedStateToRoleMap = new HashMap<>();
		// for each state, get the component states and build the submachine name
		for (Map.Entry<Integer, int[]> entry : stateToComponentStates.entrySet()) {
			int state = entry.getKey();
			int[] componentStatesTo = entry.getValue();

			String role = CompactState.buildRole(components, null, null, componentStatesTo);
			buildedStateToRoleMap.put(state, role);
		}

		// do the same for the trap states (deadlocks) for this we need the fromState and action

		// first define a hashmap that is the inverse of stateToComponentStates to quickly retrieve toState
		HashMap<String, Integer> componentStatesToState = new HashMap<>();
		for (Integer state : stateToComponentStates.keySet()) {
			int[] componentStates = stateToComponentStates.get(state);
			componentStatesToState.put(Arrays.toString(componentStates), state);
		}

		// now we can build the role for the trap states
		for (AbstractMap.SimpleEntry<Integer, String> mapKey : statePlusActionToComponentStates.keySet()) {
			int fromState = mapKey.getKey();
			int[] componentStatesFrom = stateToComponentStates.get(fromState);
			String action = mapKey.getValue();
			int[] componentStatesTo = statePlusActionToComponentStates.get(mapKey);
			int toState = componentStatesToState.get(Arrays.toString(componentStatesTo));

			String role = CompactState.buildRole(components, componentStatesFrom, action, componentStatesTo);
			buildedStateToRoleMap.put(toState, role);
			// TODO toState always exists on componentStatesToState?
		}

		return buildedStateToRoleMap;
	}

	public HashMap<Integer, int[]> getStateToComponentStates() {
		return stateToComponentStates;
	}

	public CompactState[] getComponents() {
		return components;
	}

	public void setComponents(CompactState[] components) {
		this.components = components;
	}

	public Boolean isComposition() {
		return this.components != null && this.components.length > 0;
	}
}
