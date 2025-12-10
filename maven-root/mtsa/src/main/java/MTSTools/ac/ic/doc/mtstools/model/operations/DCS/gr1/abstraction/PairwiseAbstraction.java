package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;

import java.util.ArrayList;
import java.util.HashMap;

public abstract class PairwiseAbstraction<State, Action> {
    final DirectedControllerSynthesisGR1<State, Action> dcs;
    final DCSFeatureCalculator<State, Action> featureCalculator;

    public PairwiseAbstraction(DirectedControllerSynthesisGR1<State, Action> dcs) {
        this.dcs = dcs;
        this.featureCalculator = new DCSFeatureCalculator<>(dcs);
    }

    public abstract Boolean isSmaller(Transition<State, Action> t1, Transition<State, Action> t2);

    protected static float[][] featureConcatenation(ArrayList<Float> features_t1, ArrayList<Float> features_t2) {
        // features is a concatenation of both transitions features
        ArrayList<Float> features = new ArrayList<>();
        features.addAll(features_t1);
        features.addAll(features_t2);

        float[][] featureVector = new float[1][features.size()];
        for (int i = 0; i < features.size(); i++) {
            featureVector[0][i] = features.get(i);
        }
        return featureVector;
    }

    protected static float[][] featureDifference(ArrayList<Float> features_t1, ArrayList<Float> features_t2) {
        float[][] featureVector = new float[1][features_t1.size()];
        for (int i = 0; i < features_t1.size(); i++) {
            featureVector[0][i] = (features_t1.get(i) - features_t2.get(i));
        }
        return featureVector;
    }

    protected static float[][] featureOneHotEncodeDiff(ArrayList<Float> features_t1, ArrayList<Float> features_t2) {
        int numClasses = 3;
        int numFeatures = features_t1.size();
        float[] diffVector = new float[numFeatures * numClasses];

        for (int i = 0; i < numFeatures; i++) {
            int t1Class = Math.round(features_t1.get(i));
            int t2Class = Math.round(features_t2.get(i));

            if (t1Class >= 0 && t1Class < numClasses) {
                diffVector[i * numClasses + t1Class] += 1.0f;
            }
            if (t2Class >= 0 && t2Class < numClasses) {
                diffVector[i * numClasses + t2Class] -= 1.0f;
            }
        }

        return new float[][]{diffVector};
    }
    
    protected HashMap<String, Float> getFeaturesMap(Transition<State, Action> t){
        ArrayList<Float> features = featureCalculator.extractTransitionFeatures(t);
        ArrayList<String> featureNames = featureCalculator.getFeatureNamesForDebug();

        HashMap<String, Float> featuresMap = new HashMap<>();
        for(int i = 0; i < features.size(); i++){
            featuresMap.put(featureNames.get(i), features.get(i));
        }
        return featuresMap;
    }
}
