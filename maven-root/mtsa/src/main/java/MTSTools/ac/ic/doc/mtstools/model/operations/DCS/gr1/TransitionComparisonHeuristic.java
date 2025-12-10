package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.*;

import ltsa.ui.EnvConfiguration;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class TransitionComparisonHeuristic<State, Action> implements ExplorationHeuristic<State, Action> {
    final DirectedControllerSynthesisGR1<State, Action> dcs;
    PairwiseAbstraction<State, Action> model;

    ArrayList<Compostate<State, Action>> statesToExplore = new ArrayList<>();

    Set<Compostate<State, Action>> evaluatedStates = new HashSet<>();
    Set<Compostate<State, Action>> notEvaluatedStates = new HashSet<>();
    // evaluatedStates + notEvaluatedStates = all states
    // evaluatedStates = states that have been expanded, their transitions are in the list
    // notEvaluatedStates = states that have been created but not expanded yet
    Boolean openingAState = false;

    public TransitionComparisonHeuristic(DirectedControllerSynthesisGR1<State, Action> dcs, String modelPath, float threshold) {
        this.dcs = dcs;
        model = loadModel(modelPath, threshold);
    }

    PairwiseAbstraction<State, Action> loadModel(String modelPath, float threshold) {
        PairwiseAbstraction<State, Action> loadedModel = null;
        if (modelPath.contains("Adhoc")) { // TODO refactor, just to test
            System.err.println("using Adhoc!!");
            loadedModel = new Adhoc<>(dcs);
        } else if (modelPath.contains("RankingBased")) {
            System.err.println("using RankingBased!!");
            // split the modelPath on '=' and take the second part as the ranking info path
            String ranking_info_path = modelPath.split("=")[1];
            loadedModel = new RankingBased<>(dcs, ranking_info_path);
        } else if (modelPath.contains("TableBased")) { // TODO refactor, just to test
            System.err.println("using TableBased!!");
            loadedModel = new TableBased<>(dcs);
        } else {
            System.err.println("using MLModel " + modelPath + " with threshold " + threshold);
            loadedModel = new MLPairwiseModelAbstraction<>(dcs, modelPath, threshold, false);
        }
        return loadedModel;
    }

    @Override
    public void setInitialState(Compostate<State, Action> initial) {
        // initial state is created with newState, nothing to do here
    }

    public void addStateToExplore(Compostate<State, Action> state) {
        if (state.inExplorationList || fullyExplored(state) || dcs.isError(state)) {
            return;
        }

        // compare this state's best transition with the other states to explore and insert it in the right place
        Transition<State, Action> stateBestTransition = getBestTransition(state);

        int i = 0;
        while (i < statesToExplore.size()) {
            boolean isSmaller = isSmallerWithMetaHeuristics(getBestTransition(statesToExplore.get(i)), stateBestTransition);
            if(isSmaller){
                i++;
            } else {
                break;
            }
        }
        statesToExplore.add(i, state);
        state.inExplorationList = true;
    }

    Transition<State, Action> getBestTransition(Compostate<State, Action> state) {
        // best transition from UNEXPLORED ones
        Recommendation<State, Action> recommendation = state.peekRecommendation();
        return new Transition<>(state, recommendation.getAction(), recommendation.getChild());
    }

    public void printMissingFeaturePairs() {
        // TODO remove this function ****
        // if its table based, print the size of missing features and the features
        if (model instanceof TableBased) {
            int missingFeaturesSize = ((TableBased<State, Action>) model).missingFeatures.size();
            int existingFeaturesSize = ((TableBased<State, Action>) model).existingFeatures.size();
            System.out.println("missing features: " + missingFeaturesSize + " out of " + (missingFeaturesSize + existingFeaturesSize));
            System.out.println("( aka. existing features: " + existingFeaturesSize + " )");

            // save the missing features to a file, with instance name and one feature per line
            String openFileName = EnvConfiguration.getInstance().getOpenFileName();
            String instanceName = openFileName.split("\\.")[0];
            String filepath = "src/main/resources/models/" + instanceName + "_missing_features_output.csv";
            try {
                FileWriter writer = new FileWriter(filepath);
                // print the above message first
                writer.write("missing features: " + missingFeaturesSize + " out of " + (missingFeaturesSize + existingFeaturesSize) + "\n");
                writer.write("( aka. existing features: " + existingFeaturesSize + " )\n");
                writer.write("dcs_key;transition\n");
                for (Map.Entry<String, String> entry : ((TableBased<State, Action>) model).missingFeatures.entrySet()) {
                    writer.write(entry.getKey() + ";" + entry.getValue() + "\n");
                }

                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean somethingLeftToExplore() {
        return !statesToExplore.isEmpty();
    }

    @Override
    public void expansionDone(Compostate<State, Action> state, HAction<Action> action, Compostate<State, Action> child) {
        if (child.isStatus(Status.NONE)) {
            notifyStateIsNone(child);
        }
    }

    @Override
    public Pair<Compostate<State, Action>, HAction<Action>> getNextStep() {
        Compostate<State, Action> state = statesToExplore.remove(0);
        state.inExplorationList = false;
        // advance the recommendations of the state
        Recommendation<State, Action> recommendation = state.nextRecommendation();
        return new Pair<>(state, recommendation.getAction());
    }

    @Override
    public void notifyStateIsNone(Compostate<State, Action> state) {
        if (notEvaluatedStates.contains(state)) {
            notEvaluatedStates.remove(state);
            expandStateTransitions(state);
            evaluatedStates.add(state);
        }
        addStateToExplore(state);
    }

    @Override
    public void notifyStateSetErrorOrGoal(Compostate<State, Action> state) {
        if(state.inExplorationList){
            statesToExplore.remove(state);
            state.inExplorationList = false;
        }
        state.live = false;
        state.clearRecommendations();
    }

    @Override
    public void newState(Compostate<State, Action> state) {
        // openingAState is used to avoid adding the complete chain of children of a state to 'transitions'
        // because dcs.buildCompostate calls newState, so we need to "cut" the recursion at the first level
        if (openingAState) {
            if (!evaluatedStates.contains(state)) {
                notEvaluatedStates.add(state);
            }
        } else {
            if (!evaluatedStates.contains(state)) {
                notEvaluatedStates.remove(state);
                evaluatedStates.add(state);

                expandStateTransitions(state);

                addStateToExplore(state);
            }
        }
    }

    @Override
    public void notifyExpansionDidntFindAnything(Compostate<State, Action> parent, HAction<Action> action, Compostate<State, Action> child) {
        if (child.isStatus(Status.NONE)) {
            notifyStateIsNone(child);
        }
    }

    @Override
    public boolean fullyExplored(Compostate<State, Action> state) {
        assert evaluatedStates.contains(state) || notEvaluatedStates.contains(state);
        // if the state is in notEvaluatedStates, it has not been expanded yet, otherwise we need to check if it has a valid recommendation
        boolean fullyExplored = !notEvaluatedStates.contains(state) && !state.hasValidRecommendation();
        return fullyExplored;
    }

    private void expandStateTransitions(Compostate<State, Action> state) {
        // only evaluate the state transitions if it is not an error
        if (dcs.isError(state)) {
            return;
        }
        openingAState = true;

        state.setupRecommendations();
        ArrayList<Transition<State, Action>> stateTransitions = new ArrayList<>();
        Set<HAction<Action>> actions = state.getAvailableActions();

        for (HAction<Action> action : actions) {
            Compostate<State, Action> child = dcs.buildCompostate(state, action);
            stateTransitions.add(new Transition<>(state, action, child));
        }

        selectionSort(stateTransitions);

        // add the transitions as recommendations to the state, ordered, with index as the estimate
        for (int i = 0; i < stateTransitions.size(); i++) {
            Transition<State, Action> transition = stateTransitions.get(i);
            state.addRecommendation(new Recommendation<>(transition.getAction(), i, transition.getChild()));
        }

        state.initRecommendations();
        openingAState = false;
    }

    Boolean metaHeuristics(Transition<State, Action> t1, Transition<State, Action> t2) {
        // if it's uncontrollable, we want to see it first, so it's smaller
        if(!t1.getAction().isControllable()){
            return true;
        } else if(!t2.getAction().isControllable()){
            return false;
        }

        // if a child is known (i.e., it already appeared in the exploration) we want to see it first
        if(t1.getChild().isEvaluated()){
            return true;
        } else if(t2.getChild().isEvaluated()){
            return false;
        }
        return null;
    }

    public boolean isSmallerWithMetaHeuristics(Transition<State, Action> t1, Transition<State, Action> t2) {
        // First use the metaHeuristics to compare the transitions
        Boolean isSmaller = metaHeuristics(t1, t2);
        if (isSmaller == null) { // if metaHeuristics is inconclusive, use the model
            isSmaller = model.isSmaller(t1, t2);
        }
        return isSmaller;
    }

    public void selectionSort(List<Transition<State, Action>> list) {
        int n = list.size();

        for (int i = 0; i < n - 1; i++) {
            int minIndex = i;

            // Find the minimum element in the remaining unsorted part of the list
            for (int j = i + 1; j < n; j++) {
                boolean isSmaller = isSmallerWithMetaHeuristics(list.get(j), list.get(minIndex));

                if (isSmaller) {
                    minIndex = j;
                }
            }

            // Swap the found minimum element with the i element
            Transition<State, Action> temp = list.get(i);
            list.set(i, list.get(minIndex));
            list.set(minIndex, temp);
        }
    }
}
