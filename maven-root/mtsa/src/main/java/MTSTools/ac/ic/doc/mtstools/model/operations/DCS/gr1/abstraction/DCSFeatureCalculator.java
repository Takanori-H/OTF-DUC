package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSAdapter;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedLTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Compostate;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Status;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.util.ControllerUtils;
import ltsa.lts.CompactState;

import java.util.*;

public class DCSFeatureCalculator<State, Action> {
    private final DirectedControllerSynthesisGR1<State, Action> dcs;
    private final List<String> rolesEncoder;
    private final List<String> actionEncoder;

    private List<Set<Action>> ltsActions;
    private List<HashMap<Integer, String>> ltsRoles;

    private ArrayList<String> featureNamesForDebug;
    private Boolean printFeatures = true;

    public DCSFeatureCalculator(DirectedControllerSynthesisGR1<State, Action> dcs){
        this.dcs = dcs;

        // Roles
        ltsActions = new ArrayList<>();
        ltsRoles = new ArrayList<>();
        Set<String> allAbstractRoles = new HashSet<>();
        for (int i = 0; i < dcs.ltss.size(); i++) {
            LTS<State, Action> lts = dcs.ltss.get(i);
            LTSAdapter<State, Action> ltsAdapter;
            Set<Action> actions;
            HashMap<Integer, String> stateToRole;

            if(lts instanceof MarkedLTSImpl){
                MarkedLTSImpl<State, Action> markedLTS = (MarkedLTSImpl<State, Action>) lts;

                if(markedLTS.getFluent() == null){
                    actions = markedLTS.getActions();
                    stateToRole = new HashMap<>();
                    // add all marked states as the submachine "marked" and the rest as "not-marked"
                    for (State state : markedLTS.getStates()) {
                        Integer stateAsInt = ((Long) state).intValue();
                        stateToRole.put(stateAsInt, markedLTS.getMarkedStates().contains(state) ? "marked" : "not-marked");
                    }

                } else {
                    Fluent fluent = markedLTS.getFluent();
                    MTS<Long, String> modelFrom = ControllerUtils.getModelFrom(fluent);
                    CompactState automataWithRoles = MTSToAutomataConverter.getInstance()
                            .convert(modelFrom, fluent.getName(), true);

                    actions = markedLTS.getActions();
                    stateToRole = automataWithRoles.getStateToRoleMap();
                }

            } else if (lts instanceof LTSAdapter){
                ltsAdapter = (LTSAdapter<State, Action>) lts;
                actions = ltsAdapter.getActions();
                stateToRole = ltsAdapter.buildStateToRoleMap();
            } else {
                throw new IllegalArgumentException("LTS type not supported");
            }

            ltsActions.add(actions);
            ltsRoles.add(stateToRole);
            // add the abstract version of the role (without index)
            for (String role : stateToRole.values()) {
                allAbstractRoles.add(removeIndexFrom(role));
            }
        }

        // add the abstract roles alphabetically
        rolesEncoder = new ArrayList<>(new TreeSet<>(allAbstractRoles));

        // Abstract actions
        Set<String> allActions = new HashSet<>();
        for (Set<Action> actions : ltsActions) {
            for (Action action : actions) {
                String abstractAction = removeIndexFrom(action.toString());
                if (abstractAction.equals("tau")) continue; // TODO add tau to the FeatureCalculator so it appears in the train data
                allActions.add(abstractAction);
            }
        }
        // add the abstract actions alphabetically
        actionEncoder = new ArrayList<>(new TreeSet<>(allActions));
    }

    public ArrayList<Float> extractTransitionFeatures(Transition<State, Action> transition){
        Compostate<State, Action> parent = transition.getState();
        HAction<Action> action = transition.getAction();
        Compostate<State, Action> child = transition.getChild();

        ArrayList<String> featureNames = new ArrayList<>();
        ArrayList<Float> features = new ArrayList<>();
        // Ejemplo para BW

        // "usual" features ************
        // is_controllable_action, is_uncontrollable_state, is_uncontrollable_child, child_is_deadlock, action_has_index,
        featureNames.add("is_controllable_action");
        features.add((action.isControllable()) ? 1.0f : 0.0f); // is_controllable_action
        featureNames.add("is_uncontrollable_state");
        features.add((parent.isControlled()) ? 0.0f : 1.0f); // is_uncontrollable_state
        featureNames.add("is_uncontrollable_child");
        features.add((child.isControlled()) ? 0.0f : 1.0f); // is_uncontrollable_child
        featureNames.add("child_is_deadlock");
        features.add((child.isDeadlock()) ? 1.0f : 0.0f); // child_is_deadlock
        featureNames.add("child_is_error");
        features.add((child.isStatus(Status.ERROR)) ? 1.0f : 0.0f); // child_is_error
        // ^ this differs from the MonolithicFeatureCalculator definition of ERROR (it may cause problems?)
        featureNames.add("action_has_index");
        features.add((actionHasIndex(action)) ? 1.0f : 0.0f); // action_has_index

        // ternary binned abstract roles for parent, child and "local" ************
        // for each lts get the submachine (role) for the parent and child
        ArrayList<String> roles_parent = new ArrayList<>();
        ArrayList<String> roles_child = new ArrayList<>();
        ArrayList<String> roles_local = new ArrayList<>(); //only machines that changed roles between parent and child

        for (int i = 0; i < ltsRoles.size(); i++) {
            HashMap<Integer, String> stateToRole = ltsRoles.get(i);

            String stateRole = stateToRole.get(getIntStateOfSubmachine(parent, i));
            roles_parent.add(stateRole);

            String childRole = stateToRole.get(getIntStateOfSubmachine(child, i));
            roles_child.add(childRole);

            if(!stateRole.equals(childRole)){
                roles_local.add(childRole);
            }
        }

        ArrayList<Float> encodedRoles_parent = binarizeAndEncodeRoles(roles_parent, "abstract_state", featureNames);
        features.addAll(encodedRoles_parent);

        ArrayList<Float> encodedRoles_child = binarizeAndEncodeRoles(roles_child, "abstract_child", featureNames);
        features.addAll(encodedRoles_child);

        ArrayList<Float> encodedRoles_local = binarizeAndEncodeRoles(roles_local, "abstract_child_local", featureNames);
        features.addAll(encodedRoles_local);

        ArrayList<Float> encodedAction = encodeAction(action.getAction(), featureNames);
        features.addAll(encodedAction);

        featureNamesForDebug = featureNames;

        if (printFeatures){ // print only once
            System.out.println("Transition Features: " + featureNames);
            System.out.println("#Features (per transition): " + featureNames.size());
            printFeatures = false;
        }

        return features;
    }

    public ArrayList<String> getFeatureNamesForDebug(){
        return featureNamesForDebug;
    }

    private Boolean actionHasIndex(HAction<Action> action){
        return action.toString().matches(".*\\d.*");
    }

    private Integer getIntStateOfSubmachine(Compostate<State, Action> compostate, int i) {
        Long stateOfSubmachine = (Long) compostate.getStates().get(i);
        return stateOfSubmachine.intValue();
    }

    private ArrayList<Float> binarizeAndEncodeRoles(ArrayList<String> roles, String namePrefix, ArrayList<String> featuresNames){
        HashMap<String, Integer> roleCounts = new HashMap<>();
        for (String abstractRole : rolesEncoder) {
            roleCounts.put(abstractRole, 0);
        }

        for (String role : roles) {
            String abstractRole = removeIndexFrom(role);
            roleCounts.put(abstractRole, roleCounts.get(abstractRole) + 1);
        }

        // encode to columns, in alphabetical order
        ArrayList<Float> encodedRoles = new ArrayList<>();

        TreeSet<String> keysOrdered = new TreeSet<>(roleCounts.keySet());

        for (String role : keysOrdered) {
            featuresNames.add(namePrefix + "__" + role);
            Integer amount = roleCounts.get(role);
            if(amount > 1){
                encodedRoles.add(2.0f); // +1 is encoded as 2
            } else {
                encodedRoles.add((float) amount); //0 or 1
            }
        }

        return encodedRoles;
    }

    private ArrayList<Float> encodeAction(Action action, ArrayList<String> featuresNames){
        String actionStr = removeIndexFrom(action.toString());

        ArrayList<Float> encodedAction = new ArrayList<>();
        for (String abstractAction : actionEncoder) {
            featuresNames.add("abstract_action_" + abstractAction);
            if (abstractAction.equals(actionStr)) {
                encodedAction.add(1.0f);
            } else {
                encodedAction.add(0.0f);
            }
        }
        long count = encodedAction.stream().filter(f -> f == 1.0f).count();
        assert count == 1 : "Action encoding has more/less than one 1.0f";
        return encodedAction;
    }

    private String removeIndexFrom(String role){
        // Removes the index (any number after a dot), used to abstract the roles and actions
        return role.replaceAll("\\.\\d+", "");
    }

}
