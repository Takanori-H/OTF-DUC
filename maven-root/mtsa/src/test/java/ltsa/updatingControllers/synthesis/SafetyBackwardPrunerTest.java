package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class SafetyBackwardPrunerTest {

    @Test
    public void completePruningReusesReachableRequiredOnlyInputWhenThereAreNoSeeds() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "next", 1L);
        addTransition(environment, 1L, "back", 0L);

        SafetyBackwardPruner.Result result = SafetyBackwardPruner.prune(
                environment,
                set("next", "back"),
                "no-op",
                null);

        assertSame(environment, result.getEnvironment());
        assertTrue(result.isInputEnvironmentReused());
    }

    @Test
    public void completePruningStillRemovesUnreachableStatesWhenThereAreNoSeeds() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "stay", 0L);
        addTransition(environment, 1L, "hidden", 1L);

        SafetyBackwardPruner.Result result = SafetyBackwardPruner.prune(
                environment,
                set("stay", "hidden"),
                "cleanup",
                null);

        assertNotSame(environment, result.getEnvironment());
        assertFalse(result.isInputEnvironmentReused());
        assertEquals(set(0L), result.getEnvironment().getStates());
        assertTrue(result.getEnvironment().getActions().contains("hidden"));
    }

    @Test
    public void completePruningStillDropsMaybeOnlyTransitions() {
        MTS<Long, String> environment = environment(0L);
        environment.addAction("stay");
        environment.addAction("maybe");
        environment.addRequired(0L, "stay", 0L);
        environment.addPossible(0L, "maybe", 0L);

        SafetyBackwardPruner.Result result = SafetyBackwardPruner.prune(
                environment,
                set("stay"),
                "maybe-projection",
                null);

        assertNotSame(environment, result.getEnvironment());
        assertFalse(result.isInputEnvironmentReused());
        assertTrue(result.getEnvironment()
                .getTransitions(0L, MTS.TransitionType.MAYBE).isEmpty());
        assertTrue(result.getEnvironment().getActions().contains("maybe"));
    }

    @Test
    public void deferredPruningDoesNotTreatOrdinaryDeadEndAsError() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "a", 1L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                Collections.<Long>emptySet(),
                set("a"),
                owners("a", 0),
                stages(0),
                "test",
                null);

        assertFalse(result.isInitialLosing());
        assertSame(environment, result.getEnvironment());
        assertTrue(result.isInputEnvironmentReused());
        assertTrue(result.getEnvironment().getStates().contains(1L));
        assertTransition(result.getEnvironment(), 0L, "a", 1L);
        assertTrue(result.getErrorStates().isEmpty());
    }

    @Test
    public void deferredPruningStillCleansUnreachableStatesWithoutErrorSeeds() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "stay", 0L);
        addTransition(environment, 1L, "hidden", 1L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                Collections.<Long>emptySet(),
                set("stay", "hidden"),
                owners("stay", 0),
                stages(0),
                "cleanup",
                null);

        assertNotSame(environment, result.getEnvironment());
        assertFalse(result.isInputEnvironmentReused());
        assertEquals(set(0L), result.getEnvironment().getStates());
        assertTrue(result.getEnvironment().getActions().contains("hidden"));
    }

    @Test
    public void deferredPruningStillDropsMaybeOnlyTransitionsWithoutErrorSeeds() {
        MTS<Long, String> environment = environment(0L);
        environment.addAction("stay");
        environment.addAction("maybe");
        environment.addRequired(0L, "stay", 0L);
        environment.addPossible(0L, "maybe", 0L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                Collections.<Long>emptySet(),
                set("stay"),
                owners("stay", 0),
                stages(0),
                "maybe-projection",
                null);

        assertNotSame(environment, result.getEnvironment());
        assertFalse(result.isInputEnvironmentReused());
        assertTrue(result.getEnvironment()
                .getTransitions(0L, MTS.TransitionType.MAYBE).isEmpty());
        assertTrue(result.getEnvironment().getActions().contains("maybe"));
    }

    @Test
    public void deferredPruningPreservesIncompleteUncontrollableBoundary() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "u", 1L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                set(1L),
                Collections.<String>emptySet(),
                owners("u", 0, 1),
                stages(0),
                "test",
                null);

        assertFalse(result.isInitialLosing());
        assertEquals(1L, result.getDeferredActionGroups());
        assertEquals(set(1L), result.getErrorStates());
        assertTransition(result.getEnvironment(), 0L, "u", 1L);
        assertTrue(result.getEnvironment()
                .getTransitions(1L, MTS.TransitionType.REQUIRED).isEmpty());
    }

    @Test
    public void deferredPruningPropagatesWhenAllUncontrollableOwnersArePresent() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        addTransition(environment, 0L, "u", 1L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                set(1L),
                Collections.<String>emptySet(),
                owners("u", 0),
                stages(0),
                "test",
                null);

        assertTrue(result.isInitialLosing());
    }

    @Test
    public void deferredPruningRemovesWholeControllableActionGroup() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        environment.addState(2L);
        addTransition(environment, 0L, "c", 1L);
        addTransition(environment, 0L, "c", 2L);
        addTransition(environment, 0L, "stay", 2L);

        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                set(1L),
                set("c", "stay"),
                owners("c", 0),
                stages(0),
                "test",
                null);

        MTS<Long, String> pruned = result.getEnvironment();
        assertFalse(result.isInitialLosing());
        assertTrue(pruned.getActions().contains("c"));
        assertTrue(pruned.getTransitions(0L, MTS.TransitionType.REQUIRED)
                .getImage("c").isEmpty());
        assertTransition(pruned, 0L, "stay", 2L);
        assertFalse(pruned.getStates().contains(1L));
        assertTrue(result.getErrorStates().isEmpty());
    }

    @Test
    public void deferredPruningRetargetsBoundaryPastDefiniteLosingStates() {
        MTS<Long, String> environment = environment(0L);
        environment.addState(1L);
        environment.addState(2L);
        addTransition(environment, 0L, "u", 1L);
        addTransition(environment, 1L, "v", 2L);

        Map<String, Set<Integer>> owners = new LinkedHashMap<String, Set<Integer>>();
        owners.put("u", stages(0, 1));
        owners.put("v", stages(0));
        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                environment,
                set(2L),
                Collections.<String>emptySet(),
                owners,
                stages(0),
                "test",
                null);

        assertFalse(result.isInitialLosing());
        assertFalse(result.getEnvironment().getStates().contains(1L));
        assertEquals(set(2L), result.getErrorStates());
        assertTransition(result.getEnvironment(), 0L, "u", 2L);
    }

    private static MTS<Long, String> environment(Long initialState) {
        return new MTSImpl<Long, String>(initialState);
    }

    private static void addTransition(
            MTS<Long, String> environment,
            Long source,
            String action,
            Long target) {
        environment.addAction(action);
        environment.addRequired(source, action, target);
    }

    private static Map<String, Set<Integer>> owners(String action, Integer... ownerStages) {
        Map<String, Set<Integer>> result = new LinkedHashMap<String, Set<Integer>>();
        result.put(action, stages(ownerStages));
        return result;
    }

    private static Set<Integer> stages(Integer... values) {
        return new LinkedHashSet<Integer>(Arrays.asList(values));
    }

    @SafeVarargs
    private static <T> Set<T> set(T... values) {
        return new LinkedHashSet<T>(Arrays.asList(values));
    }

    private static void assertTransition(
            MTS<Long, String> environment,
            Long source,
            String action,
            Long target) {
        assertTrue(environment.getTransitions(source, MTS.TransitionType.REQUIRED)
                .getImage(action).contains(target));
    }
}
