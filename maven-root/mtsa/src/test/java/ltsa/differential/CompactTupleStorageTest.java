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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.*;

/** Storage changes must preserve public tuple values and the synthesis problem. */
public class CompactTupleStorageTest {
    private static final int[] BOUNDARIES = {Integer.MIN_VALUE, -65536, -32769,
            -32768, -129, -128, -1, 0, 1, 127, 128, 255, 256, 32767, 32768,
            65535, 65536, Integer.MAX_VALUE};

    @Test
    public void signedBoundariesRoundTripWithoutChangingHashOrTextIdentity() {
        for (int environment : BOUNDARIES) {
            assertTupleContract(environment, new int[0]);
            for (int value : BOUNDARIES) {
                assertTupleContract(environment, new int[] {value});
                assertTupleContract(environment, new int[] {0, value, -1, value});
            }
            assertTupleContract(environment, BOUNDARIES.clone());
        }
    }

    @Test
    public void randomizedVectorsRetainAllCoordinatesAndDefensiveCopySemantics() {
        Random random = new Random(0x43a8d191L);
        for (int trial = 0; trial < 2000; trial++) {
            int[] coordinates = new int[random.nextInt(161)];
            for (int i = 0; i < coordinates.length; i++) {
                switch (trial % 4) {
                    case 0: coordinates[i] = random.nextInt(256) - 128; break;
                    case 1: coordinates[i] = random.nextInt(65536) - 32768; break;
                    case 2: coordinates[i] = random.nextInt(); break;
                    default: coordinates[i] = BOUNDARIES[random.nextInt(BOUNDARIES.length)];
                }
            }
            assertTupleContract(random.nextInt(), coordinates);
        }
    }

    @Test
    public void hashCollisionsAcrossCoordinateRangesRemainDistinctMapKeys() {
        // Equal polynomial hashes, but distinct semantic monitor states. The
        // pairs cross both signed-width boundaries and the environment field.
        Tuple[][] collisions = {
                {new Tuple(7, new int[] {0, 128}), new Tuple(7, new int[] {1, 97})},
                {new Tuple(7, new int[] {0, 32768}), new Tuple(7, new int[] {1, 32737})},
                {new Tuple(0, new int[] {31}), new Tuple(1, new int[] {0})},
                {new Tuple(0, new int[] {0, -129}), new Tuple(0, new int[] {-1, -98})}
        };
        Map<Tuple, String> table = new HashMap<>();
        for (int i = 0; i < collisions.length; i++) {
            Tuple left = collisions[i][0], right = collisions[i][1];
            assertEquals(left.hashCode(), right.hashCode());
            assertNotEquals(left, right);
            table.put(left, "left" + i);
            table.put(right, "right" + i);
        }
        assertEquals(2 * collisions.length, table.size());
        for (int i = 0; i < collisions.length; i++) {
            for (int side = 0; side < 2; side++) {
                Tuple original = collisions[i][side];
                Tuple independent = new Tuple(original.environmentState(), original.monitorStates());
                assertEquals((side == 0 ? "left" : "right") + i, table.get(independent));
            }
        }
        assertNotEquals(new Tuple(0, new int[0]), new Tuple(0, new int[] {0}));
        assertNotEquals(new Tuple(0, new int[] {1, 2}), new Tuple(0, new int[] {2, 1}));
        assertNotEquals(new Tuple(0, new int[] {1}), new Tuple(1, new int[] {1}));
        assertFalse(new Tuple(0, new int[0]).equals(null));
        assertFalse(new Tuple(0, new int[0]).equals("0:[]"));
    }

    @Test
    public void manyMonitorUpdatesPreserveGraphAnalysisControllerAndOlderSnapshots() {
        // Each variant starts at 72 zero monitor coordinates, then reaches
        // real nonzero monitor states. Later transitions cross width boundaries
        // again. No representation-specific field or allocation is asserted.
        int[][] levels = {{1, 126, 127}, {128, 255, 256, 32767},
                {32768, 65535, 65536, Integer.MAX_VALUE}};
        for (int[] values : levels) {
            List<Component> monitors = new ArrayList<>();
            for (int i = 0; i < 72; i++) {
                int first = values[i % values.length];
                int second = first == Integer.MAX_VALUE ? first - 1 : first + 1;
                Map<Integer, Map<String, Integer>> rows = new HashMap<>();
                Map<String, Integer> zero = row("enter", first);
                Map<String, Integer> firstRow = row("leave", 0, "shift", second);
                Map<String, Integer> secondRow = row("leave", 0, "shift", 0);
                // ERROR after one coordinate has changed exercises early exit
                // during target computation; following targets must be clean.
                if (i == 0) {
                    zero.put("bad", first);
                } else if (i == 1) {
                    zero.put("bad", -1);
                    firstRow.put("fault", -1);
                    secondRow.put("fault", -1);
                }
                rows.put(0, zero);
                rows.put(first, firstRow);
                rows.put(second, secondRow);
                monitors.add(component("M" + i, rows));
            }
            Component environment = component("E", Map.of(
                    0, row("bad", 2, "enter", 1, "skip", 2),
                    1, row("leave", 0), 2, row("back", 0)));
            checkUpdates(environment, monitors, Set.of("bad", "fault"), Arrays.asList(
                    Collections.singletonList(new EnvEdge(1, "skip", 2)),
                    Collections.singletonList(new EnvEdge(1, "shift", 2)),
                    Collections.singletonList(new EnvEdge(1, "fault", 2))),
                    new boolean[] {true, true, false});
        }
    }

    @Test
    public void emptyMonitorVectorKeepsEnvironmentErrorDistinctFromSafetyError() {
        Component environment = component("E", Map.of(0, row("enter", 1),
                1, row("back", 0), 2, row("return", 0)));
        checkUpdates(environment, Collections.emptyList(), Collections.singleton("fail"), Arrays.asList(
                Collections.singletonList(new EnvEdge(1, "jump", 2)),
                Collections.singletonList(new EnvEdge(1, "fail", -1))),
                new boolean[] {true, true});
    }

    private static void assertTupleContract(int environment, int[] input) {
        int[] expected = input.clone();
        Tuple tuple = new Tuple(environment, input);
        Tuple equal = new Tuple(environment, expected.clone());
        int hash = 31 * environment + Arrays.hashCode(expected);
        String key = environment + ":" + Arrays.toString(expected);
        Map<Tuple, String> table = new HashMap<>();
        table.put(tuple, key);
        if (input.length != 0) {
            input[0] ^= 0x76543210;
            int[] exported = tuple.monitorStates();
            exported[exported.length - 1] ^= 0x12345678;
        }
        assertEquals(environment, tuple.environmentState());
        assertArrayEquals(expected, tuple.monitorStates());
        assertEquals(hash, tuple.hashCode());
        assertEquals(key, tuple.key());
        assertEquals(equal, tuple);
        assertEquals(tuple, equal);
        assertEquals(key, table.get(equal));
        assertEquals(key, table.get(new Tuple(environment, expected)));
    }

    private static void checkUpdates(Component environment, List<Component> monitors, Set<String> controls,
            List<List<EnvEdge>> updates, boolean[] addsStates) {
        EnvironmentSafetyProduct current = EnvironmentSafetyProduct.compose(environment, monitors);
        Analysis<Long, String> analysis = analyze(current, controls);
        SafetyController<Long, String> controller = FullSafetySynthesis.generateControllerSnapshot(analysis).get();
        List<EnvironmentSafetyProduct> snapshots = new ArrayList<>();
        List<Analysis<Long, String>> oldAnalyses = new ArrayList<>();
        List<SafetyController<Long, String>> oldControllers = new ArrayList<>();
        List<Map<String, Map<String, String>>> savedGraphs = new ArrayList<>();
        List<Map<String, Map<String, String>>> savedControllers = new ArrayList<>();
        assertMatchesReference(environment, monitors, current, analysis, controller, controls);

        for (int step = 0; step < updates.size(); step++) {
            snapshots.add(current);
            oldAnalyses.add(analysis);
            oldControllers.add(controller);
            savedGraphs.add(semanticRows(current));
            savedControllers.add(semanticController(current, controller));
            EnvUpdate update = environment.apply(updates.get(step));
            Expansion expansion = current.expand(update, monitors, analysis);
            assertEquals(addsStates[step], expansion.newTuplesExpanded > 0);
            for (EnvEdge edge : updates.get(step)) {
                assertNotEquals("Updates must not be self-loops", edge.from, edge.to);
            }
            IncrementalSafetyAnalysis.Result<Long, String> changed =
                    IncrementalSafetyAnalysis.update(analysis, expansion.delta);
            ControllerUpdateSynthesis.Plan<Long, String> plan =
                    ControllerUpdateSynthesis.prepare(controller, changed);
            assertTrue(plan.isCompatible());
            controller = ControllerUpdateSynthesis.generateDifferential(plan).getController().get();
            current = expansion.product;
            analysis = changed.getAnalysis();
            environment = update.updated;
            assertMatchesReference(environment, monitors, current, analysis, controller, controls);

            EnvironmentSafetyProduct fresh = EnvironmentSafetyProduct.compose(environment, monitors);
            Analysis<Long, String> full = analyze(fresh, controls);
            assertEquals(semanticRows(fresh), semanticRows(current));
            assertEquals(semanticStates(fresh, full.getWinningStates()),
                    semanticStates(current, analysis.getWinningStates()));
            assertEquals(semanticStates(fresh, full.getLosingStates()),
                    semanticStates(current, analysis.getLosingStates()));
            assertEquals(semanticController(fresh, FullSafetySynthesis.generateControllerSnapshot(full).get()),
                    semanticController(current, controller));

            // Recreate every public key independently, including values reached
            // through the builder's lookup path, and corrupt returned copies.
            for (long id = 0; id < current.tuples.size(); id++) {
                Tuple tuple = current.tuple(id);
                int[] exported = tuple.monitorStates();
                Tuple publicKey = new Tuple(tuple.environmentState(), exported);
                assertEquals(Long.valueOf(id), current.stateId(publicKey));
                assertEquals(tuple, publicKey);
                assertEquals(publicKey, tuple);
                if (exported.length > 0) {
                    Arrays.fill(exported, Integer.MIN_VALUE);
                }
                assertEquals(Long.valueOf(id), current.stateId(publicKey));
                Long freshId = fresh.stateIdFor(current, id);
                assertNotNull(freshId);
                assertEquals(current.key(id), fresh.key(freshId));
            }
            for (int earlier = 0; earlier < snapshots.size(); earlier++) {
                EnvironmentSafetyProduct prior = snapshots.get(earlier);
                assertEquals(savedGraphs.get(earlier), semanticRows(prior));
                assertEquals(savedControllers.get(earlier), semanticController(prior, oldControllers.get(earlier)));
                for (long id : prior.rows.keySet()) {
                    assertEquals(prior.rows.get(id), oldAnalyses.get(earlier).getTransitions(id));
                    assertEquals(Long.valueOf(id), current.stateIdFor(prior, id));
                }
            }
        }
    }

    private static void assertMatchesReference(Component environment, List<Component> monitors,
            EnvironmentSafetyProduct product, Analysis<Long, String> analysis,
            SafetyController<Long, String> controller, Set<String> controls) {
        Map<String, Map<String, String>> reference = referenceProduct(environment, monitors);
        assertEquals(reference, semanticRows(product));
        Analysis<String, String> oracle = FullSafetySynthesis.analyze(FullSafetySynthesis.prepare(
                key(0, new int[monitors.size()]), reference, environment.alphabet,
                controls, Collections.singleton("ERROR")));
        assertEquals(oracle.getWinningStates(), semanticStates(product, analysis.getWinningStates()));
        assertEquals(oracle.getLosingStates(), semanticStates(product, analysis.getLosingStates()));
        SafetyController<String, String> expected = FullSafetySynthesis.generateControllerSnapshot(oracle).get();
        Map<String, Map<String, String>> expectedRows = new TreeMap<>();
        for (String state : expected.getStates()) {
            expectedRows.put(state, new TreeMap<>(expected.getTransitions(state)));
        }
        assertEquals(expectedRows, semanticController(product, controller));
    }

    /** Independent semantic product: int arrays and text keys, no Tuple calls. */
    private static Map<String, Map<String, String>> referenceProduct(Component environment,
            List<Component> monitors) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        result.put("ERROR", Collections.emptyMap());
        Queue<int[]> pending = new ArrayDeque<>();
        pending.add(new int[monitors.size() + 1]);
        while (!pending.isEmpty()) {
            int[] source = pending.remove();
            String sourceKey = key(source[0], Arrays.copyOfRange(source, 1, source.length));
            if (result.containsKey(sourceKey)) {
                continue;
            }
            Map<String, String> row = new TreeMap<>();
            result.put(sourceKey, row);
            Map<String, Integer> environmentRow = environment.rows.getOrDefault(source[0], Collections.emptyMap());
            for (Map.Entry<String, Integer> edge : environmentRow.entrySet()) {
                int[] target = source.clone();
                target[0] = edge.getValue();
                boolean violation = false;
                for (int i = 0; i < monitors.size(); i++) {
                    Map<String, Integer> monitorRow = monitors.get(i).rows.get(source[i + 1]);
                    target[i + 1] = monitorRow.getOrDefault(edge.getKey(), source[i + 1]);
                    violation |= target[i + 1] == -1;
                }
                if (violation) {
                    row.put(edge.getKey(), "ERROR");
                } else {
                    row.put(edge.getKey(), key(target[0], Arrays.copyOfRange(target, 1, target.length)));
                    pending.add(target);
                }
            }
        }
        return result;
    }

    private static String key(int environment, int[] monitors) {
        return environment + ":" + Arrays.toString(monitors);
    }

    private static Component component(String name, Map<Integer, Map<String, Integer>> rows) {
        return new Component(name, rows, Collections.emptySet());
    }

    private static Map<String, Integer> row(Object... pairs) {
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return result;
    }

    private static Analysis<Long, String> analyze(EnvironmentSafetyProduct product, Set<String> controls) {
        return FullSafetySynthesis.analyze(FullSafetySynthesis.prepare(
                0L, product.rows, product.alphabet, controls, Collections.singleton(EnvironmentSafetyProduct.ERROR)));
    }

    private static Set<String> semanticStates(EnvironmentSafetyProduct product, Set<Long> states) {
        Set<String> result = new HashSet<>();
        for (long state : states) {
            result.add(product.key(state));
        }
        return result;
    }

    private static Map<String, Map<String, String>> semanticRows(EnvironmentSafetyProduct product) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (Map.Entry<Long, Map<String, Long>> entry : product.rows.entrySet()) {
            Map<String, String> row = new TreeMap<>();
            for (Map.Entry<String, Long> edge : entry.getValue().entrySet()) {
                row.put(edge.getKey(), product.key(edge.getValue()));
            }
            result.put(product.key(entry.getKey()), row);
        }
        return result;
    }

    private static Map<String, Map<String, String>> semanticController(EnvironmentSafetyProduct product,
            SafetyController<Long, String> controller) {
        Map<String, Map<String, String>> result = new TreeMap<>();
        for (long state : controller.getStates()) {
            Map<String, String> row = new TreeMap<>();
            for (Map.Entry<String, Long> edge : controller.getTransitions(state).entrySet()) {
                row.put(edge.getKey(), product.key(edge.getValue()));
            }
            result.put(product.key(state), row);
        }
        return result;
    }
}
