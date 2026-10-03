package ltsa.updatingControllers.checks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import ltsa.lts.MappingEnvironmentGenerator;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.stepwise.StepwiseStage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhaseAwareUpdateEnvironmentTest {

    @Test
    public void oldWordAdvancesRelationPreActionInTheMappingShadow() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L, 1L), "z");
        oldController.addRequired(0L, "z", 1L);

        CompactState firstOld = machine(
                "FIRST_OLD", 1, actions("tau", "firstOnly"));
        CompactState firstMapping = machine(
                "FIRST_MAPPING", 3,
                actions("tau", "firstOnly", "z", UpdateConstants.RECONFIGURE),
                edge(0, "z", 1),
                edge(1, UpdateConstants.RECONFIGURE, 2));
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> firstMetadata =
                metadata(
                        oldSide(0, 0),
                        intermediate(1, 0, 0),
                        newSide(2, 0));

        CompactState secondOld = machine(
                "SECOND_OLD", 2, actions("tau", "z"),
                edge(0, "z", 1),
                edge(1, "z", 1));
        CompactState secondMapping = machine(
                "SECOND_MAPPING", 3,
                actions("tau", "z", UpdateConstants.RECONFIGURE),
                edge(0, "z", 1),
                edge(1, "z", 1),
                edge(2, "z", 2),
                edge(0, UpdateConstants.RECONFIGURE, 2),
                edge(1, UpdateConstants.RECONFIGURE, 2));
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> secondMetadata =
                metadata(oldSide(0, 0), oldSide(1, 1), newSide(2, 0));

        PhaseAwareUpdateEnvironment environment = PhaseAwareUpdateEnvironment.build(
                oldController,
                Arrays.asList(
                        stage(0, firstOld, firstMapping, firstMetadata),
                        stage(1, secondOld, secondMapping, secondMetadata)));

        PhaseAwareUpdateEnvironment.State initial = environment.initialState();
        assertTrue(initial.isPre());
        assertEquals(Long.valueOf(0L), initial.oldControllerState());
        assertTrue(initial.oldEnvironmentStates().isEmpty());

        PhaseAwareUpdateEnvironment.State afterOldZ = onlyTarget(environment, initial, "z");
        assertTrue(afterOldZ.isPre());
        assertEquals(Long.valueOf(1L), afterOldZ.oldControllerState());

        PhaseAwareUpdateEnvironment.State post = onlyTarget(
                environment, afterOldZ, UpdateConstants.BEGIN_UPDATE);
        assertTrue(post.isPost());
        assertNull(post.oldControllerState());
        assertEquals(
                "The old z must advance the first stage's relation shadow and the "
                        + "second stage's old behavior before hotSwapIn.",
                statesList(1L, 1L),
                post.mappingStates());

        PhaseAwareUpdateEnvironment.State afterReconfigure = onlyTarget(
                environment, post, UpdateConstants.RECONFIGURE);
        assertEquals(statesList(2L, 2L), afterReconfigure.mappingStates());
        assertTrue(
                "Static mapping-alphabet ownership blocks the second stage's z when "
                        + "the first stage has no z transition in its new-side state.",
                outgoing(environment, afterReconfigure, "z").isEmpty());
    }

    @Test
    public void preservesAllPreAndPostCartesianTargetsAndUpdateEdges() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L, 1L, 2L), "a");
        oldController.addRequired(0L, "a", 1L);
        oldController.addRequired(0L, "a", 2L);

        CompactState firstOld = nondeterministicOld("FIRST_OLD");
        CompactState secondOld = nondeterministicOld("SECOND_OLD");
        CompactState firstMapping = nondeterministicMapping("FIRST_MAPPING");
        CompactState secondMapping = nondeterministicMapping("SECOND_MAPPING");
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> mappingMetadata =
                metadata(
                        oldSide(0, 0),
                        oldSide(1, 1),
                        oldSide(2, 2),
                        newSide(3, 0),
                        newSide(4, 1));

        PhaseAwareUpdateEnvironment environment = PhaseAwareUpdateEnvironment.build(
                oldController,
                Arrays.asList(
                        stage(0, firstOld, firstMapping, mappingMetadata),
                        stage(1, secondOld, secondMapping, mappingMetadata)));

        PhaseAwareUpdateEnvironment.State initial = environment.initialState();
        Set<PhaseAwareUpdateEnvironment.Transition> preTargets =
                outgoing(environment, initial, "a");
        assertEquals("The visible PRE graph is exactly the old controller.",
                2, preTargets.size());

        Set<List<Long>> expectedShadowTargets =
                new LinkedHashSet<List<Long>>(Arrays.asList(
                        statesList(1L, 1L),
                        statesList(1L, 2L),
                        statesList(2L, 1L),
                        statesList(2L, 2L)));
        for (PhaseAwareUpdateEnvironment.Transition preTarget : preTargets) {
            Set<PhaseAwareUpdateEnvironment.Transition> hotSwapTargets = outgoing(
                    environment, preTarget.target(), UpdateConstants.BEGIN_UPDATE);
            assertEquals(4, hotSwapTargets.size());
            assertEquals(expectedShadowTargets, targetTuples(hotSwapTargets));
        }

        PhaseAwareUpdateEnvironment.State post = onlyTarget(
                environment, initial, UpdateConstants.BEGIN_UPDATE);
        assertEquals(statesList(0L, 0L), post.mappingStates());

        Set<PhaseAwareUpdateEnvironment.Transition> reconfigureTargets =
                outgoing(environment, post, UpdateConstants.RECONFIGURE);
        assertEquals(4, reconfigureTargets.size());
        assertEquals(
                new LinkedHashSet<List<Long>>(Arrays.asList(
                        statesList(3L, 3L),
                        statesList(3L, 4L),
                        statesList(4L, 3L),
                        statesList(4L, 4L))),
                targetTuples(reconfigureTargets));

        assertSelfLoop(environment, post, UpdateConstants.STOP_OLD_SPEC);
        assertSelfLoop(environment, post, UpdateConstants.START_NEW_SPEC);
        assertTrue(environment.alphabet().contains("disabled"));
        assertTrue(outgoing(environment, post, "disabled").isEmpty());
    }

    @Test
    public void mappingTauTransitionsInterleaveInsteadOfSynchronizing() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L));
        CompactState old = machine("OLD", 1, actions("tau"));
        CompactState firstMapping = machine(
                "FIRST_MAPPING", 2, actions("tau"), edge(0, "tau", 1));
        CompactState secondMapping = machine(
                "SECOND_MAPPING", 2, actions("tau"), edge(0, "tau", 1));
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata =
                metadata(oldSide(0, 0), intermediate(1, 0, 0));

        PhaseAwareUpdateEnvironment environment = PhaseAwareUpdateEnvironment.build(
                oldController,
                Arrays.asList(
                        stage(0, old, firstMapping, metadata),
                        stage(1, old, secondMapping, metadata)));
        PhaseAwareUpdateEnvironment.State post = onlyTarget(
                environment, environment.initialState(), UpdateConstants.BEGIN_UPDATE);

        Set<PhaseAwareUpdateEnvironment.Transition> tauTransitions =
                outgoing(environment, post, "tau");
        assertEquals(2, tauTransitions.size());
        assertEquals(
                new LinkedHashSet<List<Long>>(Arrays.asList(
                        statesList(1L, 0L), statesList(0L, 1L))),
                targetTuples(tauTransitions));
        assertFalse(targetTuples(tauTransitions).contains(statesList(1L, 1L)));
    }

    @Test
    public void staticMappingAlphabetOwnershipBlocksAnotherStageAtAnIntermediateState() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L));

        CompactState firstOld = machine(
                "FIRST_OLD", 1, actions("tau", "a"));
        CompactState firstMapping = machine(
                "FIRST_MAPPING", 3,
                actions("tau", "p", "a", UpdateConstants.RECONFIGURE),
                edge(0, "p", 1),
                edge(1, UpdateConstants.RECONFIGURE, 2));
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> firstMetadata =
                metadata(
                        oldSide(0, 0),
                        intermediate(1, 0, 0),
                        newSide(2, 0));

        CompactState secondOld = machine(
                "SECOND_OLD", 2, actions("tau", "a"),
                edge(0, "a", 1),
                edge(1, "a", 1));
        CompactState secondMapping = machine(
                "SECOND_MAPPING", 3,
                actions("tau", "a", UpdateConstants.RECONFIGURE),
                edge(0, "a", 1),
                edge(1, "a", 1),
                edge(0, UpdateConstants.RECONFIGURE, 2),
                edge(1, UpdateConstants.RECONFIGURE, 2));
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> secondMetadata =
                metadata(oldSide(0, 0), oldSide(1, 1), newSide(2, 0));

        PhaseAwareUpdateEnvironment environment = PhaseAwareUpdateEnvironment.build(
                oldController,
                Arrays.asList(
                        stage(0, firstOld, firstMapping, firstMetadata),
                        stage(1, secondOld, secondMapping, secondMetadata)));
        PhaseAwareUpdateEnvironment.State post = onlyTarget(
                environment, environment.initialState(), UpdateConstants.BEGIN_UPDATE);
        PhaseAwareUpdateEnvironment.State followingRelation = onlyTarget(
                environment, post, "p");
        assertEquals(statesList(1L, 0L), followingRelation.mappingStates());

        assertTrue(
                "The first mapping component owns a statically; because a is disabled "
                        + "at its intermediate state, the full product must block a.",
                outgoing(environment, followingRelation, "a").isEmpty());
    }

    @Test
    public void oldTauSynchronizesWithOneMappingTauInTheCorrelationShadow() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L, 1L), "tau");
        oldController.addRequired(0L, "tau", 1L);
        CompactState old = machine("OLD", 1, actions("tau"));
        CompactState mapping = machine(
                "MAPPING", 2, actions("tau"), edge(0, "tau", 1));

        PhaseAwareUpdateEnvironment environment = PhaseAwareUpdateEnvironment.build(
                oldController,
                Collections.singletonList(
                        stage(0, old, mapping, metadata(
                                oldSide(0, 0), intermediate(1, 0, 0)))));

        PhaseAwareUpdateEnvironment.State afterTau = onlyTarget(
                environment, environment.initialState(), "tau");
        assertEquals(Long.valueOf(1L), afterTau.oldControllerState());
        PhaseAwareUpdateEnvironment.State post = onlyTarget(
                environment, afterTau, UpdateConstants.BEGIN_UPDATE);
        assertEquals(statesList(1L), post.mappingStates());
    }

    @Test
    public void checkerAcceptsSameLabelTauCoverageAndCorrelation() {
        PhaseAwareUpdateEnvironment reference = tauReferenceEnvironment();
        TransitionGraph controller = TransitionGraph.fromPrintTransitionsText(
                "Process:\n"
                        + "\tTAU_COMPLETE\n"
                        + "States:\n"
                        + "\t4\n"
                        + "Transitions:\n"
                        + "\tTAU_COMPLETE = Q0,\n"
                        + "\tQ0 = (tau -> Q1 |hotSwapIn -> Q2),\n"
                        + "\tQ1 = (hotSwapIn -> Q3),\n"
                        + "\tQ2 = (tau -> Q3 |stopOldSpec -> Q2 |startNewSpec -> Q2),\n"
                        + "\tQ3 = (stopOldSpec -> Q3 |startNewSpec -> Q3).\n",
                "TAU_COMPLETE");

        assertEquals(UpdateRequirementChecker.Status.PASS,
                UpdateRequirementChecker.checkHotSwapEnabled(
                        controller, reference).status);
        assertEquals(UpdateRequirementChecker.Status.PASS,
                UpdateRequirementChecker.checkLegalityAgainstReference(
                        controller, reference, checkerControllableActions()).status);
    }

    @Test
    public void checkerRejectsControllerThatOmitsTheReferenceTau() {
        PhaseAwareUpdateEnvironment reference = tauReferenceEnvironment();
        TransitionGraph controller = TransitionGraph.fromPrintTransitionsText(
                "Process:\n"
                        + "\tTAU_OMITTED\n"
                        + "States:\n"
                        + "\t3\n"
                        + "Transitions:\n"
                        + "\tTAU_OMITTED = Q0,\n"
                        + "\tQ0 = (hotSwapIn -> Q1),\n"
                        + "\tQ1 = (stopOldSpec -> Q1 |startNewSpec -> Q1),\n"
                        + "\tQ2 = (stopOldSpec -> Q2 |startNewSpec -> Q2).\n",
                "TAU_OMITTED");

        assertEquals(UpdateRequirementChecker.Status.FAIL,
                UpdateRequirementChecker.checkHotSwapEnabled(
                        controller, reference).status);
        assertEquals(UpdateRequirementChecker.Status.FAIL,
                UpdateRequirementChecker.checkLegalityAgainstReference(
                        controller, reference, checkerControllableActions()).status);
    }

    private static PhaseAwareUpdateEnvironment tauReferenceEnvironment() {
        MTSImpl<Long, String> oldController = mts(0L, states(0L, 1L), "tau");
        oldController.addRequired(0L, "tau", 1L);
        CompactState old = machine("OLD", 1, actions("tau"));
        CompactState mapping = machine(
                "MAPPING", 2, actions("tau"), edge(0, "tau", 1));
        return PhaseAwareUpdateEnvironment.build(
                oldController,
                Collections.singletonList(
                        stage(0, old, mapping, metadata(
                                oldSide(0, 0), intermediate(1, 0, 0)))));
    }

    private static Set<String> checkerControllableActions() {
        return new LinkedHashSet<String>(Arrays.asList(
                UpdateConstants.STOP_OLD_SPEC,
                UpdateConstants.RECONFIGURE,
                UpdateConstants.START_NEW_SPEC));
    }

    private static CompactState nondeterministicOld(String name) {
        return machine(
                name, 3, actions("tau", "a"),
                edge(0, "a", 1),
                edge(0, "a", 2));
    }

    private static CompactState nondeterministicMapping(String name) {
        return machine(
                name, 5,
                actions("tau", "a", "disabled", UpdateConstants.RECONFIGURE),
                edge(0, "a", 1),
                edge(0, "a", 2),
                edge(0, UpdateConstants.RECONFIGURE, 3),
                edge(0, UpdateConstants.RECONFIGURE, 4));
    }

    private static void assertSelfLoop(
            PhaseAwareUpdateEnvironment environment,
            PhaseAwareUpdateEnvironment.State state,
            String action) {
        Set<PhaseAwareUpdateEnvironment.Transition> transitions =
                outgoing(environment, state, action);
        assertEquals(1, transitions.size());
        assertEquals(state, transitions.iterator().next().target());
    }

    private static PhaseAwareUpdateEnvironment.State onlyTarget(
            PhaseAwareUpdateEnvironment environment,
            PhaseAwareUpdateEnvironment.State source,
            String action) {
        Set<PhaseAwareUpdateEnvironment.Transition> transitions =
                outgoing(environment, source, action);
        assertEquals("Expected one " + action + " transition from " + source,
                1, transitions.size());
        return transitions.iterator().next().target();
    }

    private static Set<PhaseAwareUpdateEnvironment.Transition> outgoing(
            PhaseAwareUpdateEnvironment environment,
            PhaseAwareUpdateEnvironment.State source,
            String action) {
        Set<PhaseAwareUpdateEnvironment.Transition> result =
                new LinkedHashSet<PhaseAwareUpdateEnvironment.Transition>();
        for (PhaseAwareUpdateEnvironment.Transition transition : environment.outgoing(source)) {
            if (action.equals(transition.action())) {
                result.add(transition);
            }
        }
        return result;
    }

    private static Set<List<Long>> targetTuples(
            Set<PhaseAwareUpdateEnvironment.Transition> transitions) {
        Set<List<Long>> result = new LinkedHashSet<List<Long>>();
        for (PhaseAwareUpdateEnvironment.Transition transition : transitions) {
            result.add(transition.target().componentStates());
        }
        return result;
    }

    private static StepwiseStage stage(
            int index,
            CompactState oldEnvironment,
            CompactState mappingEnvironment,
            Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata) {
        return new StepwiseStage(
                index,
                "OLD_" + index,
                "NEW_" + index,
                "RELATION_" + index,
                oldEnvironment,
                oldEnvironment,
                mappingEnvironment,
                metadata);
    }

    private static MTSImpl<Long, String> mts(
            Long initialState,
            Set<Long> states,
            String... actions) {
        MTSImpl<Long, String> result = new MTSImpl<Long, String>(initialState);
        result.addStates(states);
        result.addActions(Arrays.asList(actions));
        return result;
    }

    private static CompactState machine(
            String name,
            int stateCount,
            String[] alphabet,
            Edge... edges) {
        CompactState result = new CompactState(name);
        result.maxStates = stateCount;
        result.alphabet = alphabet;
        result.states = new EventState[stateCount];
        for (Edge edge : edges) {
            int actionIndex = actionIndex(alphabet, edge.action);
            result.states[edge.from] = EventStateUtils.add(
                    result.states[edge.from],
                    new EventState(actionIndex, edge.to));
        }
        return result;
    }

    private static int actionIndex(String[] alphabet, String action) {
        for (int i = 0; i < alphabet.length; i++) {
            if (action.equals(alphabet[i])) {
                return i;
            }
        }
        throw new IllegalArgumentException("Missing action " + action);
    }

    private static Edge edge(int from, String action, int to) {
        return new Edge(from, action, to);
    }

    private static String[] actions(String... actions) {
        return actions;
    }

    private static Set<Long> states(Long... states) {
        return new LinkedHashSet<Long>(Arrays.asList(states));
    }

    private static List<Long> statesList(Long... states) {
        return Collections.unmodifiableList(new ArrayList<Long>(Arrays.asList(states)));
    }

    @SafeVarargs
    private static Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata(
            Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata>... entries) {
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> result =
                new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>();
        for (Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> oldSide(
            int mappingState,
            int oldEnvironmentState) {
        return entry(mappingState, new MappingEnvironmentGenerator.MappingStateMetadata(
                MappingEnvironmentGenerator.MappingStateSide.OLD_SIDE,
                Integer.valueOf(oldEnvironmentState),
                null));
    }

    private static Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> newSide(
            int mappingState,
            int newEnvironmentState) {
        return entry(mappingState, new MappingEnvironmentGenerator.MappingStateMetadata(
                MappingEnvironmentGenerator.MappingStateSide.NEW_SIDE,
                null,
                Integer.valueOf(newEnvironmentState)));
    }

    private static Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> intermediate(
            int mappingState,
            int oldEnvironmentState,
            int newEnvironmentState) {
        return entry(mappingState, new MappingEnvironmentGenerator.MappingStateMetadata(
                MappingEnvironmentGenerator.MappingStateSide.INTERMEDIATE,
                Integer.valueOf(oldEnvironmentState),
                Integer.valueOf(newEnvironmentState)));
    }

    private static Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> entry(
            Integer key,
            MappingEnvironmentGenerator.MappingStateMetadata value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<Integer,
                MappingEnvironmentGenerator.MappingStateMetadata>(key, value);
    }

    private static final class Edge {
        final int from;
        final String action;
        final int to;

        Edge(int from, String action, int to) {
            this.from = from;
            this.action = action;
            this.to = to;
        }
    }
}
