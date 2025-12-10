package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.RankingBased.parseLine;

public class TableBased<State, Action> extends Adhoc<State, Action> {
    HashMap<String, Float> featuresToRanking;
    public HashMap<String, String> missingFeatures;
    public HashSet<String> existingFeatures;
    int[] features_to_use_as_key;

    public TableBased(DirectedControllerSynthesisGR1<State, Action> dcs) {
        super(dcs);

//        String allPairsPath = "src/main/resources/models/DP_monitored_features_to_ranking.csv"; // features to ranking of 3-3
        String allPairsPath = "src/main/resources/models/DP_monitored_less_features_to_ranking.csv"; // features to ranking of 3-3
        features_to_use_as_key = new int[]{0, 1, 2, 3, 4, 5, 6, 15, 17, 25, 26, 32, 34, 38, 42, 48, 50, 52, 53, 54, 55, 56, 57, 58};


        featuresToRanking = loadCSV(allPairsPath);
        missingFeatures = new HashMap<>();
        existingFeatures = new HashSet<>();
    }

    @Override
    public Boolean isSmaller(Transition<State, Action> t1, Transition<State, Action> t2) {
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

        Float ranking1 = getRanking(t1);
        Float ranking2 = getRanking(t2);

        // if both are missing (i.e., are Float.MAX_VALUE), use the adhoc heuristic
        // (to be able to eventually solve the instance)
//        if(ranking1.equals(Float.MAX_VALUE) && ranking2.equals(Float.MAX_VALUE)){
//            return super.isSmaller(t1, t2);
//        }

        // return random if both are missing
//        if(ranking1.equals(Float.MAX_VALUE) && ranking2.equals(Float.MAX_VALUE)){
//            return Math.random() < 0.5;
//        }

        // both of the above decrease the number of missing features, obviously
        // but they do it by a lot, we should not use them

        return ranking1 < ranking2;
    }

    private Float getRanking(Transition<State, Action> t) {
        ArrayList<Float> features = featureCalculator.extractTransitionFeatures(t);

        // get only the feature indexes in features_to_use_as_key
        ArrayList<Float> features_for_key = new ArrayList<>();
        for(int i : features_to_use_as_key){
            features_for_key.add(features.get(i));
        }
        String key = features_for_key.toString();
        // remove brackets and spaces
        key = key.substring(1, key.length() - 1).replace(" ", "");

        Float ranking = Float.MAX_VALUE;
        if(featuresToRanking.containsKey(key)){
            ranking = featuresToRanking.get(key);
            existingFeatures.add(key);
        } else {
            missingFeatures.put(key, t.toString());
        }
        return ranking;
    }

    private HashMap<String, Float> loadCSV(String filePath) {
        HashMap<String, Float> featuresToRanking = new HashMap<>();
        List<String> lines = null;
        try {
            lines = Files.readAllLines(Paths.get(filePath));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        //remove header
        System.out.println(lines.get(0));
        lines.remove(0);

        for (String line : lines) {
            List<String> components = parseLine(line);

            String features = components.get(0);
            Float ranking = Float.parseFloat(components.get(1));
            featuresToRanking.put(features, ranking);
        }

        return featuresToRanking;
    }
}
