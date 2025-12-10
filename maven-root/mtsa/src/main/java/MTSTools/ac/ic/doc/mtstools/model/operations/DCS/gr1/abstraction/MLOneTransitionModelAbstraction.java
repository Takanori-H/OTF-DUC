package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.util.*;

public class MLOneTransitionModelAbstraction<State, Action> implements OneCompostateAbstraction<State, Action> {
    private final DirectedControllerSynthesisGR1<State, Action> dcs;
    private final DCSFeatureCalculator<State, Action> featureCalculator;

    private OrtEnvironment ortEnv;
    private OrtSession.SessionOptions opts;
    private OrtSession session;
    private HashMap<String, Integer> transitionsRanking;

    private Boolean printFeatures = true;

    public MLOneTransitionModelAbstraction(DirectedControllerSynthesisGR1<State, Action> dcs, String modelPath) {
        ortEnv = null;
        opts = null;
        session = null;
        loadModelFromPath(modelPath);

        this.dcs = dcs;
        this.featureCalculator = new DCSFeatureCalculator<>(dcs);

        transitionsRanking = new HashMap<>();
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

    @Override
    public void eval(Compostate<State, Action> compostate) {
        if (!compostate.isEvaluated()) {
            compostate.setupRecommendations(); //initiliazing recommendations, necesary for compostates

            // explore HAction and children
            for(HAction<Action> action :compostate.getAvailableActions()){
                Compostate<State, Action> child = dcs.buildCompostate(compostate, action);

                int predicted_child_rank = this.predictRanking(compostate, action, child);

                // esto es bastante horrible
                HEstimate estimate = new HEstimate(1, new HDist(predicted_child_rank, 1));
                compostate.addRecommendation(new Recommendation<>(action, estimate));
            }
            compostate.rankRecommendations(); // TODO esto ordena no controlable primero, cambiarlo?
            compostate.initRecommendations();
        }
    }

    private int predictRanking(Compostate<State, Action> compostate,
                                HAction<Action> action,
                                Compostate<State, Action> child){
        String transitionRep = compostate.toString() + " -- " + action.toString() + " --> " + child.toString();
        // if ranking was already predicted, return it
        if(transitionsRanking.containsKey(transitionRep)){
            return transitionsRanking.get(transitionRep);
        }

        int predictedRanking = 1; // Ranking is between 0 (best) and 1 (worst) (both inclusive)

        try {
            // predict ranking
            Transition<State, Action> transition = new Transition<>(compostate, action, child);
            ArrayList<Float> features = featureCalculator.extractTransitionFeatures(transition);

            float [][] featureVector = new float[1][features.size()]; // TODO receive the expected size from the model?
            // TODO we have to be careful because of new "not seen" roles will change the size of the feature vector
            //  this should not happen with our benchmark but it is a restriction
            for (int i = 0; i < features.size(); i++) {
                featureVector[0][i] = features.get(i);
            }

            // call the model
            OnnxTensor inputTensor = OnnxTensor.createTensor(this.ortEnv, featureVector);
            OnnxTensor tRes = (OnnxTensor)session.run(Collections.singletonMap("input", inputTensor)).get(0);
            float[][] res = (float[][])tRes.getValue();
            float predictedRankingFloat = res[0][0];
            // TODO check this, it's mostly because HDist uses integers
            // convert from a float between 0 and 1 to an int between 0 and 100000
            predictedRanking = (int) (predictedRankingFloat * 100000);

        } catch (OrtException e) {
            System.err.println("Error: " + e.getMessage() + "\npredicting ranking for transition '" + action.toString()
                    + "' from compostate '" + compostate.toString() + "' to compostate '" + child.toString() + "'");
            e.printStackTrace();
        }

        transitionsRanking.put(transitionRep, predictedRanking);
        return predictedRanking;
    }
}
