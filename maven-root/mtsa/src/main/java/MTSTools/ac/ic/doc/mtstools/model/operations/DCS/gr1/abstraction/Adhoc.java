package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;

import java.util.HashMap;

public class Adhoc<State, Action> extends MLPairwiseModelAbstraction<State, Action> {
    private final boolean useDiff;

    public Adhoc(DirectedControllerSynthesisGR1<State, Action> dcs) {
        super(dcs, "", 0.5f, true);
        this.useDiff = true;
    }

    @Override
    public Boolean isSmaller(Transition<State, Action> t1, Transition<State, Action> t2) {
        HashMap<String, Float> features1 = getFeaturesMap(t1);
        HashMap<String, Float> features2 = getFeaturesMap(t2);

        // get the features that differ in value, with a tuple (feature, value1, value2)
        HashMap<String, String> diffFeatures = new HashMap<>();
        for(String feature : features1.keySet()){
            if(!(features1.get(feature).equals(features2.get(feature)))){
                diffFeatures.put(feature, features1.get(feature) + " - " + features2.get(feature));
            }
        }

        if(useDiff){
            //calculate difference between features and create a new hashmap with feature_name: (value1 - value2)
            HashMap<String, Float> diff = new HashMap<>();
            for(String feature : features1.keySet()){
                diff.put(feature, features1.get(feature) - features2.get(feature));
            }
            return isSmallerUsingDiff(diff);
        }

        // META HEURISTICS FOR OUR SPECIFIC BENCHMARK -----------------------------------------------------------
        // if at some point we see F_s1 then the child is a goal, we want to explore it first
        if(features1.get("abstract_child_local__F_s1") == 1){
            return true;
        } else if(features2.get("abstract_child_local__F_s1") == 1){
            return false;
        }

        // DINNING PHILOSOPHERS HEURISTICS ----------------------------------------------------------------------
        // monitor||phil composed version:
        // if the child has "more people eating" we want to explore it first
        if(features1.get("abstract_child__Philosopher_Eating--Monitor_Monitor--") >
                features2.get("abstract_child__Philosopher_Eating--Monitor_Monitor--")){
            return true;
        } else if(features1.get("abstract_child__Philosopher_Eating--Monitor_Monitor--") <
                features2.get("abstract_child__Philosopher_Eating--Monitor_Monitor--")){
            return false;
        }

        // if the action is "take" and the philosopher is about to eat, we want to explore it first
        if(isATakeActionThatMakesSomeoneEat(features1)){
            return true;
        } else if(isATakeActionThatMakesSomeoneEat(features2)){
            return false;
        }

        // if it's a take action and the philosopher is finishing etiquette, we want to explore it first
        if(isATakeActionThatMakesSomeoneFinishEtiquette(features1)){
            return true;
        } else if(isATakeActionThatMakesSomeoneFinishEtiquette(features2)){
            return false;
        }

        // if it's a take action that is not done, we want to explore it first (even if it's not about to eat)
        // explanation: it's better to advance 'some' that is not done, even if it's not about to eat
        if(isATakeActionOfSomeoneNotDone(features1)){
            return true;
        } else if(isATakeActionOfSomeoneNotDone(features2)){
            return false;
        }

        return true;
    }

    private boolean isATakeActionThatMakesSomeoneEat(HashMap<String, Float> features) {
        if(features.get("abstract_action_take") > 0){
            return features.get("abstract_child_local__Philosopher_Eating--Monitor_Monitor--") > 0;
        }
        return false;
    }

    private boolean isATakeActionThatMakesSomeoneFinishEtiquette(HashMap<String, Float> features) {
        if(features.get("abstract_action_take") > 0){
            return features.get("abstract_child_local__Philosopher_Ready-Etiquete--Monitor_Monitor--") > 0;
        }
        return false;
    }

    private boolean isATakeActionOfSomeoneNotDone(HashMap<String, Float> features) {
        if(features.get("abstract_action_take") > 0){
            return features.get("abstract_child_local__Philosopher_Hungry--Monitor_Monitor--") > 0;
        }
        return false;
    }

    // with diff ---------------------------------------------------------------------------------

    private Boolean isSmallerUsingDiff(HashMap<String, Float> featureDiffs) {
        // META HEURISTICS FOR OUR SPECIFIC BENCHMARK -----------------------------------------------------------
        // if at some point we see F_s1 then the child is a goal, we want to explore it first
        // this can be: 1-1 => 0, 1-0 => 1, 0-1 => -1, 0-0 => 0
        if(featureDiffs.get("abstract_child_local__F_s1") == 1){
            return true;
        } else if(featureDiffs.get("abstract_child_local__F_s1") == -1){
            return false;
        }

        // DINNING PHILOSOPHERS HEURISTICS ----------------------------------------------------------------------
        // each role feature is [0,1,2] then the combinations of diff are:
        // 0-0 => 0,    0-1 => -1,  0-2 => -2,
        // 1-0 => 1,    1-1 => 0,   1-2 => -1,
        // 2-0 => 2,    2-1 => 1,   2-2 => 0

        // monitor||phil composed version:
        // if the child has "more people eating" we want to explore it first
        if(featureDiffs.get("abstract_child__Philosopher_Eating--Monitor_Monitor--") > 0){
            return true;
        } else if(featureDiffs.get("abstract_child__Philosopher_Eating--Monitor_Monitor--") < 0){
            return false;
        }

        // for take action we have:
        // 1-1 => 0, meaning BOTH are take actions              <- this is problematic
        // 1-0 => 1, only the first one is take action
        // 0-1 => -1, only the second one is take action
        // 0-0 => 0, meaning NONE are take actions              <- this is problematic

        // if the action is "take" and HAS MORE philosopher about to eat, we want to explore it first
        if(featureDiffs.get("abstract_child_local__Philosopher_Eating--Monitor_Monitor--") > 0){
            return true;
        } else if (featureDiffs.get("abstract_child_local__Philosopher_Eating--Monitor_Monitor--") < 0){
            return false;
        }

        // if it's a take action and HAS MORE philosopher finishing etiquette, we want to explore it first
        if(featureDiffs.get("abstract_child_local__Philosopher_Ready-Etiquete--Monitor_Monitor--") > 0){
            return true;
        } else if (featureDiffs.get("abstract_child_local__Philosopher_Ready-Etiquete--Monitor_Monitor--") < 0){
            return false;
        }

        // if it's a take action AND HAS MORE that is not done, we want to explore it first (even if it's not about to eat)
        // explanation: it's better to advance 'some' that is not done, even if it's not about to eat
        if(featureDiffs.get("abstract_child_local__Philosopher_Hungry--Monitor_Monitor--") > 0){
            return true;
        } else if (featureDiffs.get("abstract_child_local__Philosopher_Hungry--Monitor_Monitor--") < 0){
            return false;
        }

        return true;
    }
}
