package MTSSynthesis.controller;

import MTSSynthesis.controller.gr.GRGameSolver;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSSynthesis.controller.model.Rank;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import ltsa.ui.EnvConfiguration;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

public class MonolithicFeatureCalculator<S, A> {
    public static String delimiterForComponentsRole = "--";

    private final MTSImpl<S, A> plant;
    private final ControllerGoal<A> goal;
    private final CompactState[] plantComponents;
    private final HashMap<AbstractMap.SimpleEntry<Integer, String>, int[]> plantStatePlusActionToComponentStates;
    public HashMap<Integer, int[]> plantStateToComponentStates;
    private final List<List<String>> outputInfo;
    private final GRGameSolver<S> grSolver;
    private final String[] header;

    private HashMap<Integer, String> stateToRoles;

    private List<String> features = new ArrayList<>();

    public MonolithicFeatureCalculator(MTS<S, A> plant_mts, ControllerGoal<A> goal, GRGameSolver<S> grSolver) {
        this.plant = (MTSImpl<S, A>) plant_mts;
        this.goal = goal;
        this.grSolver = grSolver;

        this.plantComponents = plant.getComponents();
        this.plantStatePlusActionToComponentStates = plant.getStatePlusActionToComponentStates();
        this.plantStateToComponentStates = plant.getStateToComponentStates();

        // queremos armar una tabla
        // | state | action | child | rank | abstract_state (roles) | abstract_action (no index) | abstract_child (roles) | feature1 | feature2 | ... | featureN |
        List<List<String>> rows = new ArrayList<>();

        header = new String[]{"state", "action", "child",
                "state_rank", "child_rank",
                "abstract_state", "abstract_child",
                "state_comp_states", "child_comp_states",
                "is_controllable_action", "action_has_index",
                "child_is_deadlock", "child_is_error", "is_uncontrollable_state", "is_uncontrollable_child",
        };

        stateToRoles = new HashMap<>();

        for (S state : plant.getStates()){
//            if (isDeadlock(state)) rows.add(getDeadlockStateInfo(state));

            for (Pair<A, S> transition : plant.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                A actionName = transition.getFirst();
                S child = transition.getSecond();
                rows.add(getTransitionInfo(state, actionName, child));
            }
        }

        this.outputInfo = rows;
    }

    private List<String> getTransitionInfo(S state, A action, S child) {
        Integer stateInt = castStateToInt(state);
        String actionName = action.toString();
        Integer childInt = castStateToInt(child);

        if(stateInt == null || childInt == null || plantStateToComponentStates == null){
            // TODO check WHEN this happens and fix
            return new ArrayList<>();
        }

        int[] componentStatesFrom = plantStateToComponentStates.get(stateInt);
        int[] componentStatesTo = getComponentStatesForTo(stateInt, actionName, childInt);

        List<String> row = new ArrayList<>();
        row.add(state.toString()); // state
        row.add(actionName); // action
        row.add(child.toString()); // child

        row.add(getRanksInfo(state)); // state rank para el RankingBasedAbstraction
        row.add(getRanksInfo(child)); // child rank para ML

        // Abstractions:
        ArrayList<String> stateRoles = getRolesFor(null, null, componentStatesFrom);
        row.add("\""+stateRoles+"\""); // abstract_state (ROLES)

        ArrayList<String> childRoles = getRolesFor(componentStatesFrom, actionName, componentStatesTo);
        row.add("\""+childRoles+"\""); // abstract_child (ROLES)

        // "extra" features:
        int[] primitiveStatesFrom = getPrimitiveStates(null, null, componentStatesFrom);
        int[] primitiveStatesTo = getPrimitiveStates(componentStatesFrom, actionName, componentStatesTo);
        row.add("\""+Arrays.toString(primitiveStatesFrom)+"\""); // state_comp_states
        row.add("\""+Arrays.toString(primitiveStatesTo)+"\""); // child_comp_states

        row.add(goal.getControllableActions().contains(actionName) ? "true" : "false"); // is controllable action
        row.add(actionName.toString().matches(".*\\d.*") ? "true" : "false"); // action has index

        row.add(isDeadlock(child) ? "true" : "false"); // child is deadlock
        row.add(child.toString().contains("-1") ? "true" : "false"); // child is error state
        row.add(grSolver.getGame().isUncontrollable(state) ? "true" : "false"); // is uncontrollable state
        row.add(grSolver.getGame().isUncontrollable(child) ? "true" : "false"); // is uncontrollable child

        return row;
    }

    private ArrayList<String> getRolesFor(int[] componentStatesFrom, String actionName, int[] componentStatesTo) {
        // Returns the role for state 'To'
        ArrayList<String> roles = new ArrayList<>();

        for (int i = 0; i < plantComponents.length; i++) {
            CompactState component = plantComponents[i];
            Integer componentFromState = componentStatesFrom == null ? null : componentStatesFrom[i];
            int componentToState = componentStatesTo[i];

            ArrayList<String> componentRoles = component.getComponentsRole(componentFromState, actionName, componentToState);
            roles.addAll(componentRoles);
        }

        return roles;
    }

    private Integer castStateToInt(S state) {
        return state == null ? null : ((Long) state).intValue();
    }

    private int[] getPrimitiveStates(int[] componentStatesFrom, String actionName, int[] componentStatesTo) {
        ArrayList<Integer> primitiveStates = new ArrayList<>();

        // we need to "decompose" the "plant" (without the fluent) state into the states of each component
        CompactState fspComposition = plantComponents[0]; // Is it always the first one?

        Integer fspStateFrom = componentStatesFrom == null ? null : componentStatesFrom[0];
        int fspStateTo = componentStatesTo[0];
        int[] fspCompositionComponentStates = fspComposition.getComponentStates(fspStateFrom, actionName, fspStateTo);

        for (int fspCompositionComponentState : fspCompositionComponentStates) {
            primitiveStates.add(fspCompositionComponentState);
        }

        // add the rest as is
        for (int i = 1; i < plantComponents.length; i++) {
            primitiveStates.add(componentStatesTo[i]);
        }

        return primitiveStates.stream().mapToInt(i -> i).toArray();
    }

    private int[] getComponentStatesForTo(Integer fromState, String actionName, int toState) {
        int[] componentStates;

        // if it's the deadlock state it represents multiple composition states at once
        // we need the action to know which one to return
        if(plantStateToComponentStates.containsKey(toState)){
            componentStates = plantStateToComponentStates.get(toState);
        } else {
            SimpleEntry<Integer, String> key = new SimpleEntry<>(fromState, actionName);
            componentStates = plantStatePlusActionToComponentStates.get(key);
        }
        return componentStates;
    }

    private String getRanksInfo(S state) {
        ArrayList<Rank> ranks = grSolver.getAllRanks(state);
        if(ranks == null) {
            return "[]";
        }else{
            StringBuilder ranksString = new StringBuilder("[");
            for (Rank rank : ranks) {
                if (rank.isInfinity()){
                    String rankString = rank.toString();
                    // replace its value (the number after 'value:') with -1 (representing infinity)
                    rankString = rankString.replaceAll("value: \\d+", "value: -1");
                    ranksString.append(rankString).append(", ");
                }
            }
            return "\""+ ranks +"\"";
        }
    }

    private boolean isDeadlock(S state) {
        return plant.getTransitions(state, MTS.TransitionType.REQUIRED).isEmpty();
    }

    public void outputCsvWithFeatures() {
        // output to a csv file for now
        String openFileName = EnvConfiguration.getInstance().getOpenFileName().split("\\.")[0];
        try (FileWriter writer = new FileWriter(openFileName + "_features.csv")) {
            writer.append(String.join(",", Arrays.asList(header)));
            writer.write("\n");
            for (List<String> row : outputInfo) {
                writer.write(String.join(",", row));
                writer.write("\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
