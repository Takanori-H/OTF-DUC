package ltsa.lts;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public class ModelExplorerContext {
	public int Nmach;											// number of machines to be composed
	public boolean canTerminate;								// alpha(nonTerm) subset alpha(term)
	public CompactState[] sm;									// array of state machines to be composed
	public PartialOrder partial;
	public int asteriskEvent = -1; 								// number of asterisk event
	public int[] actionCount;									// number of machines which share this action;
	public BitSet highAction = null;							// actions with high priority
	public int acceptEvent = -1;								// number of acceptance label @NAME
	public BitSet visible;										// BitSet of visible actions
	public int endSequence = LTSConstants.NO_SEQUENCE_FOUND;
	public int stateCount = 0;									// number of states analysed
	public MyList compTrans;									// list of transitions
	public boolean[] violated;									// true if this property already violated
	public String[] actionNames;

	// information for ROLES feature for ML //TODO refactor
	// here component refers to the Plant's submachines and
	// submachines refers to the Process' names on the specification for each machine
	// ex. Philosopher = IDLE, ... (IDLE would be a "submachine" and the Philosopher one of the Plant's components)
	// plant state to component states
	public HashMap<Integer, int[]> stateToComponentStates = new HashMap<>();
	public void addStateToComponentStates(int state, int[] childAndAction) {
		// the last index of childAndAction is the action number
		int action = childAndAction[childAndAction.length - 1];
		int[] childComponentStates = java.util.Arrays.copyOf(childAndAction, childAndAction.length - 1);

		stateToComponentStates.put(state, childComponentStates);
	}

	// plant state + action to component states (of the child) // TODO refactor
	// (mostly because -1 leads to multiple combinations of component states)
	public HashMap<SimpleEntry<Integer, String>, int[]> statePlusActionToComponentStates = new HashMap<>();
	public void addStatePlusActionToComponentStates(int state, int[] childAndAction, byte[] code) {
		// code is the codification of the child (byte[] code= coder.encode(nextState);)

		// the last index of childAndAction is the action number
		int action = childAndAction[childAndAction.length - 1];
		int[] childComponentStates = java.util.Arrays.copyOf(childAndAction, childAndAction.length - 1);

		String actionName = actionNames[action];

		if (sm.length == 1 && sm[0].isComposition()) {
			// this is already a composition, the information of statesToComponentStates is already there
			return;
		}

//		if(components.length>0 && components[0].name.equals(" ")){
//			// this is composition with fluents from ltsa.control.util.ControllerUtils.embedFluents
//			// TODO here is where we need to add the goal hints?
//			return;
//		}

		SimpleEntry<Integer, String> key = new SimpleEntry<>(state, actionName);

		if (!statePlusActionToComponentStates.containsKey(key)) {
			statePlusActionToComponentStates.put(new SimpleEntry<>(state, actionName), childComponentStates);
		}
	}

	private int getComponentStateFor(int state, int i) {
		if (stateToComponentStates.containsKey(state)) {
			int[] componentStates = stateToComponentStates.get(state);
			return componentStates[i];
		} else {
			System.err.println("WARNING: stateToComponentStates for state " + state + " is null.");
			return -1;
		}
	}

	public String[] legalityActions;
}
