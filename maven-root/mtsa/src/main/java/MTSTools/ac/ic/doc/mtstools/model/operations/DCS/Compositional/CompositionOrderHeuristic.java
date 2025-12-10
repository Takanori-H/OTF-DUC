package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.lts.CompactState;

import java.util.*;

public enum CompositionOrderHeuristic {

    /** minS. Choose the candidate for which the product of the number of states in the automata is smallest **/
    MinS {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            Set<CompactState> minCandidate = candidates.iterator().next();
            long minStatesOfCandidate = HeuristicsFunctions.getStatesOfCompositionCandidate(minCandidate);
            for(Set<CompactState> candidate : candidates){
                long possibleMin = HeuristicsFunctions.getStatesOfCompositionCandidate(candidate);
                if(possibleMin<minStatesOfCandidate){
                    minStatesOfCandidate = possibleMin;
                    minCandidate = candidate;
                }
            }
            return minCandidate;
        }
    },

    /** maxL. Choose the candidate with the highest proportion of local events. **/
    MaxL {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            Set<CompactState> minCandidate = candidates.iterator().next();
            long minStatesOfCandidate = HeuristicsFunctions.getLocalEventsNumberOfCompositionCandidate(minCandidate, machines);

            for(Set<CompactState> candidate : candidates){
                long possibleMin = HeuristicsFunctions.getLocalEventsNumberOfCompositionCandidate(candidate, machines);
                if(minStatesOfCandidate<possibleMin){
                    minStatesOfCandidate = possibleMin;
                    minCandidate = candidate;
                }
            }
            return minCandidate;
        }
    },

    /** maxC. Choose the candidate with the highest proportion of common events. **/
    MaxC {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            Set<CompactState> maxCandidate = candidates.iterator().next();
            double commonEventsOfCandidate = HeuristicsFunctions.getProportionOfCommonEvents(maxCandidate);

            for(Set<CompactState> candidate : candidates){
                 double possibleMax = HeuristicsFunctions.getProportionOfCommonEvents(candidate);
                    if(commonEventsOfCandidate<possibleMax){
                        commonEventsOfCandidate = possibleMax;
                        maxCandidate = candidate;
                    }
            }
            return maxCandidate;
        }
    },

    /** Choose the candidate with the highest number of events in the formula. If two candidates have the lowest number, chooses one of them. **/
    MaxLGoal {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            long max = -1;
            Set<CompactState> result = Simple.select(CandidateSelectionHeuristic.Simple.select(machines,originalGoal),machines,originalGoal);
            for (Set<CompactState> candidate : candidates) {
                long t = numberOfEventsOnFormula(candidate, originalGoal);
                if ((max == -1 || t > max) && candidate.size() > 1) {
                    max = t;
                    result = candidate;
                }
            }
            return result;
        }
    },

    /** Choose the candidate with the lowest number of events in the formula. If two candidates have the lowest number, chooses one of them. **/
    MinLGoal {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            long min = -1;
            Set<CompactState> result = Simple.select(CandidateSelectionHeuristic.Simple.select(machines,originalGoal),machines,originalGoal);
            for (Set<CompactState> candidate : candidates) {
                long t = numberOfEventsOnFormula(candidate, originalGoal);
                if ((min == -1 || t < min) && candidate.size() > 1) {
                    min = t;
                    result = candidate;
                }
            }
            return result;
        }
    },

    /** Uses MaxL, MaxC, MinS in that order, uses the first one capable of distinguishing a top candidate **/
    FlordalMalik {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            Set<CompactState> result = MaxLFaillable(candidates, machines);
            if (result != null) {
                return result;
            }
            result = MaxCFaillable(candidates);
            if(result != null) {
                return result;
            }
            return MinS.select(candidates, machines, originalGoal);
        }
        private Set<CompactState> MaxLFaillable(Set<Set<CompactState>> candidates, Vector<CompactState> machines){
            Set<CompactState> minCandidate = candidates.iterator().next();
            ArrayList<Long> minStatesOfCandidateList = new ArrayList<>();
            long minStatesOfCandidate = HeuristicsFunctions.getLocalEventsNumberOfCompositionCandidate(minCandidate, machines);
            minStatesOfCandidateList.add(minStatesOfCandidate);

            for(Set<CompactState> candidate : candidates){
                long possibleMin = HeuristicsFunctions.getLocalEventsNumberOfCompositionCandidate(candidate, machines);
                if(minStatesOfCandidate<possibleMin){
                    minStatesOfCandidateList.add(possibleMin);
                    minStatesOfCandidate = possibleMin;
                    minCandidate = candidate;
                }
            }
            long max = Collections.max(minStatesOfCandidateList);
            long count = minStatesOfCandidateList.stream().filter(n -> n == max).count();
            if (count > 1) {
                return null;
            }
            return minCandidate;
        }
        private Set<CompactState> MaxCFaillable(Set<Set<CompactState>> candidates){
            Set<CompactState> maxCandidate = candidates.iterator().next();
            ArrayList<Double> commonEventsOfCandidateList = new ArrayList<>();
            double commonEventsOfCandidate = HeuristicsFunctions.getProportionOfCommonEvents(maxCandidate);
            commonEventsOfCandidateList.add(commonEventsOfCandidate);

            for(Set<CompactState> candidate : candidates){
                double possibleMax = HeuristicsFunctions.getProportionOfCommonEvents(candidate);
                if(commonEventsOfCandidate<possibleMax){
                    commonEventsOfCandidateList.add(possibleMax);
                    commonEventsOfCandidate = possibleMax;
                    maxCandidate = candidate;
                }
            }
            double max = Collections.max(commonEventsOfCandidateList);
            long count = commonEventsOfCandidateList.stream().filter(n -> n == max).count();
            if (count > 1) {
                return null;
            }
            return maxCandidate;
        }
    },

    /** Returns the biggest cluster. If all clusters are of size 1, do monolithic **/
    CommonAlphabetCluster {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            Set<CompactState> result =  candidates.stream()
                    .max(Comparator.comparingInt(Set::size))
                    .orElse(null);
            if (result.size() > 1) {
                return result;
            } else {
                result = new HashSet<>();
                for (Set<CompactState> subconjunto : candidates) {
                    result.addAll(subconjunto);
                }
                return result;
            }
        }
    },

    /** Fall back to Simple-Simple if failed to find a top candidate in the set of candidates (there is no candidate with at least two CompactState). Otherwise, it returns the first element of candidates with more than two CompactState **/
    MinCutMatchedAlphabet {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            for (Set<CompactState> element : candidates) {
                if (element.size() > 1) {
                    return element;
                }
            }
            return Simple.select(CandidateSelectionHeuristic.Simple.select(machines, originalGoal),machines, originalGoal);
        }
    },

    /** Fall back to Simple-Simple if failed to find a top candidate in the set of candidates (there is no candidate with at least two CompactState). Otherwise, it returns the first element of candidates with more than two CompactState **/
    MinCutMatchedAlphabetCartesian {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            return MinCutMatchedAlphabet.select(candidates,machines, originalGoal);
        }
    },


    /** Take two by two **/
    Simple {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            return candidates.iterator().next();
        }
    },

    /** random. It does what you think it does. **/
    RandomSelection {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            int n = (new Random()).nextInt(candidates.size());
            Iterator<Set<CompactState>> iter = candidates.iterator();
            while (n-- > 0) {
                iter.next();
            }
            return iter.next();
        }
    },

    /** Does monolithic, consider that it is not exactly equivalent to running MTSA in monolithic mode since compositional also minimizes the result **/
    Mono {
        public Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal){
            return candidates.iterator().next();
        }
    };

    public abstract Set<CompactState> select(Set<Set<CompactState>> candidates, Vector<CompactState> machines, ControllerGoal<String> originalGoal);

    public static long numberOfEventsOnFormula(Set<CompactState> machines, ControllerGoal<String> formula) {
        Set<String> formulaActivators = FormulaUtils.getActivationEventsGoal(formula);
        long numberOfEventsOnMachine = 0;
        for (CompactState machine : machines) {
            for (String event : formulaActivators) {
                numberOfEventsOnMachine += machine.getAlphabetV().contains(event)?1:0;
            }
        }
        return numberOfEventsOnMachine;
    }
}
