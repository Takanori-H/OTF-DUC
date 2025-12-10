package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.PairwiseAbstraction;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.Transition;

import java.util.List;

public class TransitionComparisonMultipleModelsHeuristic<State, Action> extends TransitionComparisonHeuristic<State, Action> {
    private PairwiseAbstraction<State, Action> modelSameOrigin;
    private PairwiseAbstraction<State, Action> modelDifferentOrigin;

    public TransitionComparisonMultipleModelsHeuristic(DirectedControllerSynthesisGR1<State, Action> dcs,
                                                       String sameOriginModelPath,
                                                       String differentOriginModelPath, float threshold) {
        super(dcs, sameOriginModelPath, threshold);

        this.model = null; // set to null to avoid confusion
        System.err.println("setup SAME origin model:");
        modelSameOrigin = loadModel(sameOriginModelPath, threshold);
        System.err.println("setup DIFFERENT origin model:");
        modelDifferentOrigin = loadModel(differentOriginModelPath, threshold);
    }

    public boolean isSmallerWithMetaHeuristics(Transition<State, Action> t1, Transition<State, Action> t2,
                                               boolean sameOrigin) {
        // First use the metaHeuristics to compare the transitions
        Boolean isSmaller = metaHeuristics(t1, t2);
        if (isSmaller == null) { // if metaHeuristics is inconclusive, use the model
            if (sameOrigin) {
                isSmaller = modelSameOrigin.isSmaller(t1, t2);
            } else {
                isSmaller = modelDifferentOrigin.isSmaller(t1, t2);
            }
        }
        return isSmaller;
    }

    @Override
    public void addStateToExplore(Compostate<State, Action> state) {
        if (state.inExplorationList || fullyExplored(state) || dcs.isError(state)) {
            return;
        }

        // compare this state's best transition with the other states to explore and insert it in the right place
        Transition<State, Action> stateBestTransition = getBestTransition(state);

        int i = 0;
        while (i < statesToExplore.size()) {
            boolean isSmaller = isSmallerWithMetaHeuristics(getBestTransition(statesToExplore.get(i)), stateBestTransition, false);
            if(isSmaller){
                i++;
            } else {
                break;
            }
        }
        statesToExplore.add(i, state);
        state.inExplorationList = true;
    }

    @Override
    public void selectionSort(List<Transition<State, Action>> list) {
        int n = list.size();

        for (int i = 0; i < n - 1; i++) {
            int minIndex = i;

            // Find the minimum element in the remaining unsorted part of the list
            for (int j = i + 1; j < n; j++) {
                boolean isSmaller = isSmallerWithMetaHeuristics(list.get(j), list.get(minIndex), true);

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
