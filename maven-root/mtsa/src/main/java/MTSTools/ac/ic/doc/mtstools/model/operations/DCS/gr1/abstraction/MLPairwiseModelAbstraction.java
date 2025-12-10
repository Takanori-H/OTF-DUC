package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.*;

public class MLPairwiseModelAbstraction<State, Action> extends PairwiseAbstraction<State, Action> {
    final float threshold;
    private final boolean concatenation;

    OrtEnvironment ortEnv;
    OrtSession.SessionOptions opts;
    OrtSession session;
    HashMap<Pair<Transition<State, Action>, Transition<State, Action>>, Boolean> cache;

    public MLPairwiseModelAbstraction(DirectedControllerSynthesisGR1<State, Action> dcs, String modelPath, float threshold, boolean concatenation) {
        super(dcs);
        ortEnv = null;
        opts = null;
        session = null;
        loadModelFromPath(modelPath);

        this.threshold = threshold;
        this.concatenation = concatenation;

        cache = new HashMap<>();
    }

    private void loadModelFromPath(String modelPath) {
        if(!modelPath.isEmpty()) {
            ortEnv = OrtEnvironment.getEnvironment();
            opts = new OrtSession.SessionOptions();
            try {
                opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT);
                session = ortEnv.createSession(modelPath);
            } catch (OrtException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Boolean isSmaller(Transition<State, Action> t1, Transition<State, Action> t2){
        Pair<Transition<State, Action>, Transition<State, Action>> key = new Pair<>(t1, t2);
        if(cache.containsKey(key)){
            return cache.get(key);
        }
        Boolean isSmaller = null;

        try {
            // predict ranking
            ArrayList<Float> features_t1 = featureCalculator.extractTransitionFeatures(t1);
            ArrayList<Float> features_t2 = featureCalculator.extractTransitionFeatures(t2);

            // TODO receive the expected size from the model?
            // TODO we have to be careful because the new "not seen" roles will change the size of the feature vector
            //  this should not happen with our benchmark but it is a restriction
            float[][] featureVector;
            if (concatenation){
                featureVector = featureConcatenation(features_t1, features_t2);
            } else {
//                featureVector = featureDifference(features_t1, features_t2);
                featureVector = featureOneHotEncodeDiff(features_t1, features_t2);
            }

//            System.err.println("Final feature vector size: " + featureVector[0].length);

            // call the model
            OnnxTensor inputTensor = OnnxTensor.createTensor(this.ortEnv, featureVector);
            OnnxTensor tRes = (OnnxTensor)session.run(Collections.singletonMap("input", inputTensor)).get(0);
            float predictedProbability = 0.0f;
            // if the value of tRes is of type long[] then safely cast it to float[]
            Object value = tRes.getValue();
            if(value instanceof long[]){
                long longValue = ((long[]) value)[0];
                predictedProbability = (float) longValue;
            } else if(value instanceof float[][]){
                predictedProbability = ((float[][]) value)[0][0];
            } else if(value instanceof float[]){
                predictedProbability = ((float[]) value)[0];
            }
            // model returns the probability of the first transition being <= the second one
//            System.err.println("Predicted probability of " + t1 + " being <= " + t2 + ": " + predictedProbability);
            isSmaller = predictedProbability > threshold;

        } catch (OrtException e) {
            System.err.println("Error: " + e.getMessage() + "\npredicting order between transitions" + t1 + " and " + t2);
            e.printStackTrace();
            //raise error
            System.exit(1);
        }

        cache.put(key, isSmaller);

        return isSmaller;
    }
}
