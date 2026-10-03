package ltsa.updatingControllers.checks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTSConstants;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.stepwise.StepwiseStage;

/**
 * Reachable Traditional-DUCS reference environment.
 *
 * <p>The visible PRE region is exactly the reachable old-controller behavior.
 * Separately, the builder follows the old controller and the full mapping
 * product with the same words.  The resulting relation H_T supplies every
 * correlated mapping tuple, including relation intermediate tuples, as a
 * {@code hotSwapIn} target.  The POST region then follows the full mapping
 * product with the mapping components' static alphabet ownership.</p>
 */
final class PhaseAwareUpdateEnvironment {

    private final State initialState;
    private final Set<State> states;
    private final Map<State, Set<Transition>> transitions;
    private final Set<String> alphabet;

    private PhaseAwareUpdateEnvironment(
            State initialState,
            Set<State> states,
            Map<State, Set<Transition>> transitions,
            Set<String> alphabet) {
        this.initialState = initialState;
        this.states = states;
        this.transitions = transitions;
        this.alphabet = alphabet;
    }

    static PhaseAwareUpdateEnvironment build(
            MTS<Long, String> oldController,
            List<StepwiseStage> stages) {
        if (oldController == null) {
            throw new IllegalArgumentException("oldController must not be null");
        }
        if (stages == null) {
            throw new IllegalArgumentException("stages must not be null");
        }
        return new Builder(oldController, stages).build();
    }

    State initialState() {
        return initialState;
    }

    Set<State> states() {
        return states;
    }

    Set<Transition> outgoing(State state) {
        Set<Transition> result = transitions.get(state);
        return result == null ? Collections.<Transition>emptySet() : result;
    }

    Set<String> alphabet() {
        return alphabet;
    }

    static final class State {
        private final boolean pre;
        private final Long oldControllerState;
        private final List<Long> componentStates;

        private State(boolean pre, Long oldControllerState, List<Long> componentStates) {
            this.pre = pre;
            this.oldControllerState = oldControllerState;
            this.componentStates = Collections.unmodifiableList(
                    new ArrayList<Long>(componentStates));
        }

        static State pre(Long oldControllerState) {
            if (oldControllerState == null) {
                throw new IllegalArgumentException("PRE state requires an old-controller state");
            }
            return new State(true, oldControllerState, Collections.<Long>emptyList());
        }

        static State post(List<Long> mappingStates) {
            return new State(false, null, mappingStates);
        }

        boolean isPre() {
            return pre;
        }

        boolean isPost() {
            return !pre;
        }

        Long oldControllerState() {
            return oldControllerState;
        }

        List<Long> componentStates() {
            return componentStates;
        }

        List<Long> oldEnvironmentStates() {
            return Collections.emptyList();
        }

        List<Long> mappingStates() {
            return pre ? Collections.<Long>emptyList() : componentStates;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof State)) {
                return false;
            }
            State that = (State) other;
            if (pre != that.pre) {
                return false;
            }
            if (oldControllerState == null
                    ? that.oldControllerState != null
                    : !oldControllerState.equals(that.oldControllerState)) {
                return false;
            }
            return componentStates.equals(that.componentStates);
        }

        @Override
        public int hashCode() {
            int result = pre ? 1 : 0;
            result = 31 * result
                    + (oldControllerState == null ? 0 : oldControllerState.hashCode());
            result = 31 * result + componentStates.hashCode();
            return result;
        }

        @Override
        public String toString() {
            if (pre) {
                return "PRE(" + oldControllerState + ")";
            }
            return "POST(" + componentStates + ")";
        }
    }

    static final class Transition {
        private final String action;
        private final State target;

        Transition(String action, State target) {
            if (action == null || target == null) {
                throw new IllegalArgumentException("transition action and target must not be null");
            }
            this.action = action;
            this.target = target;
        }

        String action() {
            return action;
        }

        State target() {
            return target;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Transition)) {
                return false;
            }
            Transition that = (Transition) other;
            return action.equals(that.action) && target.equals(that.target);
        }

        @Override
        public int hashCode() {
            return 31 * action.hashCode() + target.hashCode();
        }

        @Override
        public String toString() {
            return action + " -> " + target;
        }
    }

    private static final class Builder {
        private final MTS<Long, String> oldController;
        private final List<StageModel> stages;
        private final LinkedHashSet<String> alphabet;
        private final LinkedHashMap<State, State> canonicalStates;
        private final LinkedHashMap<State, LinkedHashSet<Transition>> transitions;
        private final Queue<State> pending;
        private Map<Long, LinkedHashSet<List<Long>>> hotSwapTargets;

        Builder(MTS<Long, String> oldController, List<StepwiseStage> stageDefinitions) {
            this.oldController = oldController;
            this.stages = new ArrayList<StageModel>(stageDefinitions.size());
            this.alphabet = new LinkedHashSet<String>();
            this.canonicalStates = new LinkedHashMap<State, State>();
            this.transitions = new LinkedHashMap<State, LinkedHashSet<Transition>>();
            this.pending = new ArrayDeque<State>();

            alphabet.addAll(oldController.getActions());
            for (int i = 0; i < stageDefinitions.size(); i++) {
                StepwiseStage stage = stageDefinitions.get(i);
                if (stage == null || stage.getMappingEnvironment() == null) {
                    throw new IllegalArgumentException(
                            "stage " + i + " requires a mapping environment");
                }
                StageModel model = new StageModel(stage);
                stages.add(model);
                alphabet.addAll(model.mappingEnvironment.getActions());
            }
            alphabet.add(UpdateConstants.BEGIN_UPDATE);
            alphabet.add(UpdateConstants.STOP_OLD_SPEC);
            alphabet.add(UpdateConstants.START_NEW_SPEC);
        }

        PhaseAwareUpdateEnvironment build() {
            hotSwapTargets = computeHotSwapTargets();
            State initial = intern(State.pre(oldController.getInitialState()));

            while (!pending.isEmpty()) {
                State state = pending.remove();
                if (state.isPre()) {
                    expandPre(state);
                } else {
                    expandPost(state);
                }
            }

            LinkedHashMap<State, Set<Transition>> frozenTransitions =
                    new LinkedHashMap<State, Set<Transition>>();
            for (State state : canonicalStates.values()) {
                LinkedHashSet<Transition> outgoing = transitions.get(state);
                if (outgoing == null) {
                    frozenTransitions.put(state, Collections.<Transition>emptySet());
                } else {
                    frozenTransitions.put(state, Collections.unmodifiableSet(
                            new LinkedHashSet<Transition>(outgoing)));
                }
            }
            return new PhaseAwareUpdateEnvironment(
                    initial,
                    Collections.unmodifiableSet(
                            new LinkedHashSet<State>(canonicalStates.values())),
                    Collections.unmodifiableMap(frozenTransitions),
                    Collections.unmodifiableSet(new LinkedHashSet<String>(alphabet)));
        }

        private Map<Long, LinkedHashSet<List<Long>>> computeHotSwapTargets() {
            LinkedHashMap<Long, LinkedHashSet<List<Long>>> result =
                    new LinkedHashMap<Long, LinkedHashSet<List<Long>>>();
            Queue<CorrelationState> toVisit = new ArrayDeque<CorrelationState>();
            Set<CorrelationState> discovered = new LinkedHashSet<CorrelationState>();

            List<Long> initialMappingTuple = new ArrayList<Long>(stages.size());
            for (StageModel stage : stages) {
                initialMappingTuple.add(stage.mappingEnvironment.getInitialState());
            }
            toVisit.add(new CorrelationState(
                    oldController.getInitialState(), initialMappingTuple));

            while (!toVisit.isEmpty()) {
                CorrelationState actual = toVisit.remove();
                if (!discovered.add(actual)) {
                    continue;
                }
                LinkedHashSet<List<Long>> targets = result.get(actual.oldState);
                if (targets == null) {
                    targets = new LinkedHashSet<List<Long>>();
                    result.put(actual.oldState, targets);
                }
                targets.add(actual.mappingTuple);

                for (Pair<String, Long> oldTransition : oldController.getTransitions(
                        actual.oldState, MTS.TransitionType.REQUIRED)) {
                    String action = oldTransition.getFirst();
                    if (UpdateConstants.BEGIN_UPDATE.equals(action)) {
                        continue;
                    }
                    for (List<Long> mappingTarget
                            : mappingProductTargets(actual.mappingTuple, action)) {
                        toVisit.add(new CorrelationState(
                                oldTransition.getSecond(), mappingTarget));
                    }
                }
            }
            return result;
        }

        private void expandPre(State state) {
            for (Pair<String, Long> controllerTransition : oldController.getTransitions(
                    state.oldControllerState(), MTS.TransitionType.REQUIRED)) {
                String action = controllerTransition.getFirst();
                if (!UpdateConstants.BEGIN_UPDATE.equals(action)) {
                    addTransition(state, action, State.pre(controllerTransition.getSecond()));
                }
            }

            Set<List<Long>> targets = hotSwapTargets.get(state.oldControllerState());
            if (targets != null) {
                for (List<Long> target : targets) {
                    addTransition(state, UpdateConstants.BEGIN_UPDATE, State.post(target));
                }
            }
        }

        private void expandPost(State state) {
            addTransition(state, UpdateConstants.STOP_OLD_SPEC, state);
            addTransition(state, UpdateConstants.START_NEW_SPEC, state);

            LinkedHashSet<String> candidates = new LinkedHashSet<String>();
            for (int i = 0; i < stages.size(); i++) {
                Long componentState = state.componentStates().get(i);
                for (Pair<String, Long> transition
                        : stages.get(i).mappingEnvironment.getTransitions(
                                componentState, MTS.TransitionType.REQUIRED)) {
                    candidates.add(transition.getFirst());
                }
            }

            for (String action : candidates) {
                if (UpdateConstants.BEGIN_UPDATE.equals(action)
                        || UpdateConstants.STOP_OLD_SPEC.equals(action)
                        || UpdateConstants.START_NEW_SPEC.equals(action)) {
                    continue;
                }
                for (List<Long> target
                        : mappingProductTargets(state.componentStates(), action)) {
                    addTransition(state, action, State.post(target));
                }
            }
        }

        /**
         * Successors in the full mapping product.  Observable actions use the
         * components' static alphabets, as in the materialized Traditional
         * composition.  Tau transitions interleave.
         */
        private List<List<Long>> mappingProductTargets(
                List<Long> tuple,
                String action) {
            if (MTSConstants.TAU.equals(action)) {
                List<List<Long>> result = new ArrayList<List<Long>>();
                for (int i = 0; i < stages.size(); i++) {
                    for (Long target : targets(
                            stages.get(i).mappingEnvironment, tuple.get(i), action)) {
                        List<Long> successor = new ArrayList<Long>(tuple);
                        successor.set(i, target);
                        result.add(immutableTuple(successor));
                    }
                }
                return result;
            }

            boolean hasOwner = false;
            List<List<Long>> choices = new ArrayList<List<Long>>(stages.size());
            for (int i = 0; i < stages.size(); i++) {
                MTS<Long, String> component = stages.get(i).mappingEnvironment;
                if (!component.getActions().contains(action)) {
                    choices.add(Collections.singletonList(tuple.get(i)));
                    continue;
                }
                hasOwner = true;
                List<Long> enabledTargets = targets(component, tuple.get(i), action);
                if (enabledTargets.isEmpty()) {
                    return Collections.emptyList();
                }
                choices.add(enabledTargets);
            }
            if (!hasOwner) {
                return Collections.emptyList();
            }

            List<List<Long>> result = new ArrayList<List<Long>>();
            addCartesianTargets(
                    choices, 0, new ArrayList<Long>(choices.size()), result);
            return result;
        }

        private void addCartesianTargets(
                List<List<Long>> choices,
                int index,
                List<Long> current,
                List<List<Long>> result) {
            if (index == choices.size()) {
                result.add(immutableTuple(current));
                return;
            }
            for (Long target : choices.get(index)) {
                current.add(target);
                addCartesianTargets(choices, index + 1, current, result);
                current.remove(current.size() - 1);
            }
        }

        private void addTransition(State source, String action, State targetCandidate) {
            State target = intern(targetCandidate);
            LinkedHashSet<Transition> outgoing = transitions.get(source);
            if (outgoing == null) {
                outgoing = new LinkedHashSet<Transition>();
                transitions.put(source, outgoing);
            }
            outgoing.add(new Transition(action, target));
            alphabet.add(action);
        }

        private State intern(State candidate) {
            State existing = canonicalStates.get(candidate);
            if (existing != null) {
                return existing;
            }
            canonicalStates.put(candidate, candidate);
            pending.add(candidate);
            return candidate;
        }

        private static List<Long> targets(
                MTS<Long, String> environment,
                Long state,
                String action) {
            LinkedHashSet<Long> result = new LinkedHashSet<Long>();
            for (Pair<String, Long> transition : environment.getTransitions(
                    state, MTS.TransitionType.REQUIRED)) {
                if (action.equals(transition.getFirst())) {
                    result.add(transition.getSecond());
                }
            }
            return new ArrayList<Long>(result);
        }

        private static List<Long> immutableTuple(List<Long> tuple) {
            return Collections.unmodifiableList(new ArrayList<Long>(tuple));
        }
    }

    private static final class CorrelationState {
        final Long oldState;
        final List<Long> mappingTuple;

        CorrelationState(Long oldState, List<Long> mappingTuple) {
            this.oldState = oldState;
            this.mappingTuple = Builder.immutableTuple(mappingTuple);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CorrelationState)) {
                return false;
            }
            CorrelationState that = (CorrelationState) other;
            return oldState.equals(that.oldState)
                    && mappingTuple.equals(that.mappingTuple);
        }

        @Override
        public int hashCode() {
            return 31 * oldState.hashCode() + mappingTuple.hashCode();
        }
    }

    private static final class StageModel {
        final MTS<Long, String> mappingEnvironment;

        StageModel(StepwiseStage stage) {
            this.mappingEnvironment = AutomataToMTSConverter.getInstance().convert(
                    stage.getMappingEnvironment());
        }
    }
}
