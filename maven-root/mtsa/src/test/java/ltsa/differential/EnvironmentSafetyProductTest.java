package ltsa.differential;

import MTSSynthesis.controller.differential.ControllerUpdateSynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyController;
import ltsa.differential.EnvironmentSafetyProduct.Component;
import ltsa.differential.EnvironmentSafetyProduct.EnvEdge;
import ltsa.differential.EnvironmentSafetyProduct.EnvUpdate;
import ltsa.differential.EnvironmentSafetyProduct.Expansion;
import ltsa.differential.EnvironmentSafetyProduct.Tuple;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

public class EnvironmentSafetyProductTest {
    @Test
    public void environmentAdditionCreatesRealTuplesAndMatchesFreshProductAndController() {
        Component env = environment();
        List<Component> monitors = Collections.singletonList(monitor());
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(env, monitors);
        Analysis<Long, String> base = analyze(old);
        SafetyController<Long, String> previous = FullSafetySynthesis.generateControllerSnapshot(base).get();
        Map<String, Map<String, String>> before = semanticRows(old);

        // Existing ENV states, but previously unreachable monitor combinations.
        EnvUpdate update = env.apply(Collections.singletonList(new EnvEdge(1, "c", 2)));
        Expansion expansion = old.expand(update, monitors, base);
        EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(update.updated, monitors);

        assertEquals(3, old.tuples.size());
        assertEquals(5, expansion.product.tuples.size());
        assertEquals(2, expansion.delta.getAddedStates().size());
        assertEquals(4, expansion.delta.getAddedTransitions().size());
        assertEquals(2, expansion.newTuplesExpanded);
        assertEquals(1, expansion.changedOldSources);
        assertEquals(3, expansion.oldTuplesExamined);
        assertEquals(semanticRows(fresh), semanticRows(expansion.product));
        assertEquals(before, semanticRows(old));
        assertFalse(env.row(1).containsKey("c"));
        for (long id = 0; id < old.tuples.size(); id++) {
            assertEquals(Long.valueOf(id), expansion.product.stateIdFor(old, id));
        }
        assertEquals(Long.valueOf(EnvironmentSafetyProduct.ERROR), fresh.stateIdFor(old, -1));

        IncrementalSafetyAnalysis.Result<Long, String> change = IncrementalSafetyAnalysis.update(base, expansion.delta);
        Analysis<Long, String> full = analyze(fresh);
        assertEquals(semanticStates(fresh, full.getWinningStates()),
                semanticStates(expansion.product, change.getAnalysis().getWinningStates()));
        ControllerUpdateSynthesis.Plan<Long, String> plan = ControllerUpdateSynthesis.prepare(previous, change);
        assertTrue(plan.isCompatible());
        SafetyController<Long, String> diff = ControllerUpdateSynthesis.generateDifferential(plan).getController().get();
        SafetyController<Long, String> reference = FullSafetySynthesis.generateControllerSnapshot(full).get();
        assertEquals(semanticController(fresh, reference), semanticController(expansion.product, diff));
    }

    @Test
    public void multipleEnvironmentEdgesAndSequentialUpdatePreserveSnapshotsAndMatchFreshSynthesis() {
        Component env = environment();
        Map<Integer, Map<String, Integer>> monitorRows = new HashMap<>();
        monitorRows.put(0, row("a", 1));
        monitorRows.put(1, row("b", 0, "f", 2));
        monitorRows.put(2, row("reset", 0));
        List<Component> monitors = Collections.singletonList(component("ThreeStates", monitorRows));
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(env, monitors);
        Analysis<Long, String> base = analyze(old);
        SafetyController<Long, String> oldController =
                FullSafetySynthesis.generateControllerSnapshot(base).get();
        Map<String, Map<String, String>> oldRows = semanticRows(old);

        // Two additions connect different existing ENV states and expose new
        // environment/monitor tuples; neither addition is a self-loop.
        EnvUpdate first = env.apply(Arrays.asList(new EnvEdge(1, "c", 2), new EnvEdge(2, "e", 1)));
        Expansion firstExpansion = old.expand(first, monitors, base);
        EnvironmentSafetyProduct firstFresh = EnvironmentSafetyProduct.compose(first.updated, monitors);
        IncrementalSafetyAnalysis.Result<Long, String> firstAnalysis =
                IncrementalSafetyAnalysis.update(base, firstExpansion.delta);
        Analysis<Long, String> firstReference = analyze(firstFresh);
        assertTrue(firstExpansion.newTuplesExpanded > 0);
        assertEquals(semanticRows(firstFresh), semanticRows(firstExpansion.product));
        assertEquals(semanticStates(firstFresh, firstReference.getWinningStates()),
                semanticStates(firstExpansion.product, firstAnalysis.getAnalysis().getWinningStates()));
        assertEquals(semanticStates(firstFresh, firstReference.getLosingStates()),
                semanticStates(firstExpansion.product, firstAnalysis.getAnalysis().getLosingStates()));
        ControllerUpdateSynthesis.Plan<Long, String> firstPlan =
                ControllerUpdateSynthesis.prepare(oldController, firstAnalysis);
        assertTrue(firstPlan.isCompatible());
        SafetyController<Long, String> firstController =
                ControllerUpdateSynthesis.generateDifferential(firstPlan).getController().get();
        assertEquals(semanticController(firstFresh,
                        FullSafetySynthesis.generateControllerSnapshot(firstReference).get()),
                semanticController(firstExpansion.product, firstController));

        Map<String, Map<String, String>> firstRows = semanticRows(firstExpansion.product);
        EnvUpdate second = first.updated.apply(Collections.singletonList(new EnvEdge(1, "f", 2)));
        Expansion secondExpansion = firstExpansion.product.expand(second, monitors, firstAnalysis.getAnalysis());
        EnvironmentSafetyProduct secondFresh = EnvironmentSafetyProduct.compose(second.updated, monitors);
        IncrementalSafetyAnalysis.Result<Long, String> secondAnalysis =
                IncrementalSafetyAnalysis.update(firstAnalysis.getAnalysis(), secondExpansion.delta);
        Analysis<Long, String> secondReference = analyze(secondFresh);
        assertTrue(secondExpansion.newTuplesExpanded > 0);
        assertEquals(semanticRows(secondFresh), semanticRows(secondExpansion.product));
        assertEquals(semanticStates(secondFresh, secondReference.getWinningStates()),
                semanticStates(secondExpansion.product, secondAnalysis.getAnalysis().getWinningStates()));
        assertEquals(semanticStates(secondFresh, secondReference.getLosingStates()),
                semanticStates(secondExpansion.product, secondAnalysis.getAnalysis().getLosingStates()));
        ControllerUpdateSynthesis.Plan<Long, String> secondPlan =
                ControllerUpdateSynthesis.prepare(firstController, secondAnalysis);
        assertTrue(secondPlan.isCompatible());
        SafetyController<Long, String> secondController =
                ControllerUpdateSynthesis.generateDifferential(secondPlan).getController().get();
        assertEquals(semanticController(secondFresh,
                        FullSafetySynthesis.generateControllerSnapshot(secondReference).get()),
                semanticController(secondExpansion.product, secondController));

        // State 0's row is unchanged in both updates and can safely be shared.
        // Require snapshot behavior, without assuming any JDK object identity.
        assertEquals(old.rows.get(0L), firstExpansion.product.rows.get(0L));
        assertEquals(old.rows.get(0L), secondExpansion.product.rows.get(0L));
        expectImmutable(() -> secondExpansion.product.rows.get(0L).put("injected", 0L));
        expectImmutable(() -> secondExpansion.product.rows.get(1L).clear());
        secondExpansion.product.copyLts().removeTransition(0L, "a", 1L);
        assertEquals(oldRows, semanticRows(old));
        assertEquals(firstRows, semanticRows(firstExpansion.product));
        assertEquals(semanticRows(secondFresh), semanticRows(secondExpansion.product));
        for (long state = 0; state < old.tuples.size(); state++) {
            assertEquals(old.rows.get(state), base.getTransitions(state));
        }
        for (long state = 0; state < firstExpansion.product.tuples.size(); state++) {
            assertEquals(firstExpansion.product.rows.get(state), firstAnalysis.getAnalysis().getTransitions(state));
        }
    }

    @Test
    public void explicitMonitorViolationBecomesSharedErrorAndMatchesFullAnalysis() {
        Component env = environment();
        Map<Integer, Map<String, Integer>> monitorRows = new HashMap<>();
        monitorRows.put(0, row("a", 1));
        monitorRows.put(1, row("b", 0, "fault", -1));
        List<Component> monitors = Collections.singletonList(component("Safety", monitorRows));
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(env, monitors);
        Analysis<Long, String> base = analyze(old);
        EnvUpdate update = env.apply(Collections.singletonList(new EnvEdge(1, "fault", 2)));
        Expansion expanded = old.expand(update, monitors, base);
        long source = old.stateId(new Tuple(1, new int[] {1}));
        assertEquals(Long.valueOf(-1), expanded.product.rows.get(source).get("fault"));
        assertEquals(0, expanded.newTuplesExpanded);
        EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(update.updated, monitors);
        Analysis<Long, String> reference = analyze(fresh);
        Analysis<Long, String> local = IncrementalSafetyAnalysis.update(base, expanded.delta).getAnalysis();
        assertEquals(semanticRows(fresh), semanticRows(expanded.product));
        assertEquals(semanticStates(fresh, reference.getLosingStates()),
                semanticStates(expanded.product, local.getLosingStates()));
    }

    @Test
    public void stateCorrespondenceUsesTuplesWhenIndependentBfsAssignsDifferentIds() {
        Map<Integer, Map<String, Integer>> envRows = new HashMap<>();
        envRows.put(0, row("middle", 1, "zlast", 2));
        envRows.put(1, row("next", 0));
        envRows.put(2, row("next", 0));
        Component env = component("Environment", envRows);
        List<Component> monitors = Collections.emptyList();
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(env, monitors);
        EnvUpdate update = env.apply(Collections.singletonList(new EnvEdge(0, "afirst", 2)));
        EnvironmentSafetyProduct incremental = old.expand(update, monitors, analyze(old)).product;
        EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(update.updated, monitors);
        // No new tuple here, but the action row sort can change BFS discovery.
        assertEquals(semanticRows(fresh), semanticRows(incremental));
        assertNotEquals(Long.valueOf(1L), fresh.stateIdFor(incremental, 1L));
        for (long id = 0; id < incremental.tuples.size(); id++) {
            Long mapped = fresh.stateIdFor(incremental, id);
            assertNotNull(mapped);
            assertEquals(incremental.key(id), fresh.key(mapped));
        }
        assertNull(fresh.stateId(new Tuple(100, new int[] {0})));
    }

    @Test
    public void componentAndProductSnapshotsDoNotExposeMutableInputs() {
        Map<Integer, Map<String, Integer>> source = new HashMap<>();
        Map<String, Integer> zero = row("a", 1);
        source.put(0, zero);
        source.put(1, row("b", 0));
        Component env = component("Environment", source);
        zero.put("injected", 0);
        source.clear();
        assertFalse(env.row(0).containsKey("injected"));
        EnvironmentSafetyProduct product = EnvironmentSafetyProduct.compose(env, Collections.emptyList());
        Map<String, Map<String, String>> before = semanticRows(product);
        product.copyLts().removeTransition(0L, "a", 1L);
        assertEquals(before, semanticRows(product));
        expectImmutable(() -> env.rows.clear());
        expectImmutable(() -> env.row(0).clear());
        expectImmutable(() -> product.rows.get(0L).clear());
        expectImmutable(() -> product.ids.clear());
        int[] monitorStates = {0};
        Tuple tuple = new Tuple(0, monitorStates);
        monitorStates[0] = 99;
        tuple.monitorStates()[0] = 99;
        assertEquals(new Tuple(0, new int[] {0}), tuple);
    }

    @Test
    public void conflictingUpdatesAreRejectedAtomicallyAndMonitorChangesAreRejected() {
        Component env = environment();
        List<Component> monitors = Collections.singletonList(monitor());
        EnvironmentSafetyProduct old = EnvironmentSafetyProduct.compose(env, monitors);
        Analysis<Long, String> base = analyze(old);
        expectInvalid(() -> env.apply(Arrays.asList(new EnvEdge(1, "x", 2), new EnvEdge(0, "a", 2))));
        assertFalse(env.row(1).containsKey("x"));
        expectInvalid(() -> env.apply(Collections.singletonList(new EnvEdge(1, "x", 99))));
        expectInvalid(() -> new EnvEdge(0, "tau", 1));
        expectInvalid(() -> old.expand(env.apply(Collections.emptyList()),
                Collections.singletonList(monitor()), base));
        expectInvalid(() -> old.expand(environment().apply(Collections.emptyList()), monitors, base));
        Map<Integer, Map<String, Integer>> terminalRows = new HashMap<>();
        terminalRows.put(0, Collections.emptyMap());
        Component terminalMonitor = component("UnsupportedTerminal", terminalRows);
        expectInvalid(() -> EnvironmentSafetyProduct.compose(env, Collections.singletonList(terminalMonitor)));
    }

    @Test
    public void environmentErrorCoordinateIsTerminalAndNotAMonitorViolation() {
        Map<Integer, Map<String, Integer>> envRows = new HashMap<>();
        envRows.put(0, row("fail", -1));
        EnvironmentSafetyProduct product = EnvironmentSafetyProduct.compose(component("Environment", envRows),
                Collections.emptyList());
        Long target = product.rows.get(0L).get("fail");
        assertNotEquals(Long.valueOf(-1), target);
        assertEquals(-1, product.tuple(target).environmentState());
        assertTrue(product.rows.get(target).isEmpty());
        assertEquals(1L, product.deadlocks());
    }

    private static Component environment() {
        Map<Integer, Map<String, Integer>> rows = new HashMap<>();
        rows.put(0, row("a", 1, "c", 2));
        rows.put(1, row("b", 0));
        rows.put(2, row("d", 0));
        return component("Environment", rows);
    }

    private static Component monitor() {
        Map<Integer, Map<String, Integer>> rows = new HashMap<>();
        rows.put(0, row("a", 1));
        rows.put(1, row("b", 0));
        return component("Monitor", rows);
    }

    private static Component component(String name, Map<Integer, Map<String, Integer>> rows) {
        return new Component(name, rows, Collections.emptySet());
    }

    private static Map<String, Integer> row(Object... pairs) {
        Map<String, Integer> row = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return row;
    }

    private static Analysis<Long, String> analyze(EnvironmentSafetyProduct product) {
        return FullSafetySynthesis.analyze(product.copyLts(), Collections.singleton("a"),
                Collections.singleton(EnvironmentSafetyProduct.ERROR));
    }

    private static Set<String> semanticStates(EnvironmentSafetyProduct product, Set<Long> ids) {
        Set<String> result = new HashSet<>();
        for (long id : ids) {
            result.add(product.key(id));
        }
        return result;
    }

    private static Map<String, Map<String, String>> semanticRows(EnvironmentSafetyProduct product) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (Map.Entry<Long, Map<String, Long>> row : product.rows.entrySet()) {
            Map<String, String> edges = new TreeMap<>();
            for (Map.Entry<String, Long> edge : row.getValue().entrySet()) {
                edges.put(edge.getKey(), product.key(edge.getValue()));
            }
            result.put(product.key(row.getKey()), edges);
        }
        return result;
    }

    private static Map<String, Map<String, String>> semanticController(
            EnvironmentSafetyProduct product, SafetyController<Long, String> controller) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (long state : controller.getStates()) {
            Map<String, String> edges = new TreeMap<>();
            for (Map.Entry<String, Long> edge : controller.getTransitions(state).entrySet()) {
                edges.put(edge.getKey(), product.key(edge.getValue()));
            }
            result.put(product.key(state), edges);
        }
        return result;
    }

    private static void expectInvalid(Runnable work) {
        try {
            work.run();
            fail("Expected invalid input to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectImmutable(Runnable work) {
        try {
            work.run();
            fail("Expected immutable snapshot");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
