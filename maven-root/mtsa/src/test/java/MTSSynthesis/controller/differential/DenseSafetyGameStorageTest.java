package MTSSynthesis.controller.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis.Result;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** Dense and generic storage must expose exactly the same safety-game contract. */
public class DenseSafetyGameStorageTest {
    private static final Set<String> CONTROLS = Set.of("c0", "c1", "future");

    @Test
    public void mutableDenseBuilderAndIndependentCopiesKeepMapAndSetContracts() {
        Map<Long, String> builder = DenseStateStorage.newAppendOnlyLongMap();
        assertNull(builder.put(-1L, "error"));
        assertNull(builder.put(0L, "zero"));
        assertNull(builder.put(1L, "one"));
        assertEquals("one", builder.put(1L, "ONE"));
        for (Map.Entry<Long, String> entry : builder.entrySet()) {
            if (entry.getKey().equals(0L)) assertEquals("zero", entry.setValue("ZERO"));
        }
        assertEquals(Map.of(-1L, "error", 0L, "ZERO", 1L, "ONE"), builder);
        assertEquals(Map.of(-1L, "error", 0L, "ZERO", 1L, "ONE").hashCode(), builder.hashCode());
        assertFalse(builder.containsKey(0));
        assertNull(builder.get(0));
        assertNull(builder.get(Long.MAX_VALUE));
        Set<Long> input = new HashSet<>(builder.keySet());
        Set<Long> domain = DenseStateStorage.immutableStateCopy(input);
        input.clear();
        assertEquals(Set.of(-1L, 0L, 1L), domain);
        Map<Long, String> copy = DenseStateStorage.copyMapFor(domain, builder);
        Map<Long, String> empty = DenseStateStorage.mutableMapFor(domain);
        empty.putAll(builder);
        assertEquals(builder, empty);
        assertEquals("ZERO", copy.put(0L, "changed"));
        assertEquals("ZERO", builder.get(0L));
        assertEquals("ZERO", empty.get(0L));
        Set<Long> extended = DenseStateStorage.unionStates(domain, Set.of(2L, 3L));
        assertEquals(Set.of(-1L, 0L, 1L, 2L, 3L), extended);
        assertEquals(Set.of(-1L, 0L, 1L), domain);
        rejects(UnsupportedOperationException.class, () -> domain.clear());
        rejects(UnsupportedOperationException.class, () -> extended.remove(3L));
    }

    @Test
    public void denseSparseExtremeAndGenericIdsMatchIndependentClosedSubsetOracle() {
        List<List<?>> domains = List.of(
                List.of(-1L, 0L, 1L, 2L, 3L, 4L),
                List.of(0L, 1L, 2L, 3L, 4L, 5L),
                List.of(Long.MIN_VALUE, -2L, -1L, 128L, 1L << 32, Long.MAX_VALUE),
                List.of("initial", "next", "other", "bad", "far", "terminal"),
                List.of(0, 0L, 1, 1L, "0", Long.MAX_VALUE));
        for (List<?> domain : domains) {
            exerciseDomain(domain);
        }
    }

    @Test
    public void minusOneIsAnOrdinaryStateUnlessExplicitlyDeclaredUnsafe() {
        Map<Long, Map<String, Long>> rows = Map.of(-1L, Map.of("next", 0L),
                0L, Map.of("back", -1L), 1L, Map.of("leave", 0L));
        Analysis<Long, String> safe = checked(-1L, rows, Set.of(), Set.of(1L));
        assertEquals(Set.of(-1L, 0L), safe.getWinningStates());
        assertEquals(Map.of("next", 0L), safe.getTransitions(-1L));
        assertTrue(safe.getTransitions(1L).isEmpty());
        Analysis<Long, String> unsafe = checked(0L, rows, Set.of(), Set.of(-1L));
        assertTrue(unsafe.getWinningStates().isEmpty());
        checked(-1L, Map.of(-1L, Map.of()), Set.of(), Set.of());
        checked(0L, Map.of(0L, Map.of()), Set.of(), Set.of());
        checked(1L, Map.of(0L, Map.of("go", 1L), 1L, Map.of("back", 0L)), Set.of(), Set.of());
        assertFalse(safe.getStates().contains(0));
        assertFalse(safe.getStates().contains(null));
        rejects(IllegalArgumentException.class, () -> safe.getTransitions(-2L));
        rejects(NullPointerException.class, () -> safe.getTransitions(null));
    }

    @Test
    public void denseIdsBeyondBoxedLongCacheKeepExactStatesAndTransitions() {
        Map<Long, Map<String, Long>> rows = new HashMap<>();
        rows.put(-1L, Map.of());
        for (long i = 0; i < 257; i++) {
            rows.put(i, Map.of("next", (i + 1) % 257));
        }
        Analysis<Long, String> base = FullSafetySynthesis.analyze(
                FullSafetySynthesis.prepare(128L, rows, Set.of("next"), Set.of(), Set.of(-1L)));
        assertEquals(rows.keySet(), base.getStates());
        assertEquals(rows.keySet().hashCode(), base.getStates().hashCode());
        assertEquals(rows.keySet(), new HashSet<>(List.of(base.getStates().toArray(new Long[0]))));
        assertEquals(257, base.getWinningStates().size());
        assertEquals(Set.of(-1L), base.getLosingStates());
        assertEquals(Map.of("next", 129L), base.getTransitions(Long.valueOf("128")));
        Result<Long, String> next = compareUpdate(base, SafetyGameDelta.forAnalysis(base)
                .addState(257L).addTransition(256L, "branch", 257L)
                .addTransition(257L, "return", 128L).build(), false);
        assertEquals(258, next.getAnalysis().getWinningStates().size());
        assertFalse(base.getStates().contains(257L));
        assertEquals(Set.of(255L), base.game.predecessors(256L));
    }

    @Test
    public void nonSelfSequentialAdditionsPreserveEarlierDenseSnapshots() {
        Analysis<Long, String> base = cycle();
        Map<Long, Map<String, Long>> oldRows = rows(base);
        Result<Long, String> first = compareUpdate(base, SafetyGameDelta.forAnalysis(base)
                .addState(2L).addState(3L).addTransition(1L, "fault", 2L)
                .addTransition(2L, "next", 3L).addTransition(3L, "return", 2L).build(), true);
        assertEquals(Set.of(0L, 1L, 2L, 3L), first.getAnalysis().getWinningStates());
        Map<Long, Map<String, Long>> firstRows = rows(first.getAnalysis());
        Result<Long, String> second = compareUpdate(first.getAnalysis(),
                SafetyGameDelta.forAnalysis(first.getAnalysis()).addState(4L).addUnsafeState(5L)
                        .addTransition(3L, "c0", 4L)
                        .addTransition(3L, "failure", 5L).build(), true);
        assertEquals(Set.of(-1L, 0L, 1L, 2L, 3L, 4L, 5L), second.getAnalysis().getLosingStates());
        assertEquals(oldRows, rows(base));
        assertEquals(firstRows, rows(first.getAnalysis()));
        assertEquals(Set.of(0L, 1L), base.getWinningStates());
        assertFalse(first.getAnalysis().getStates().contains(4L));
        assertEquals(2L, second.getAnalysis().getRevision());
    }

    @Test
    public void sparseExtremeAdditionsCanFollowDenseGamesAndBeUpdatedAgain() {
        Analysis<Long, String> base = cycle();
        Result<Long, String> first = compareUpdate(base, SafetyGameDelta.forAnalysis(base)
                .addState(Long.MAX_VALUE).addState(Long.MIN_VALUE)
                .addTransition(1L, "far", Long.MAX_VALUE)
                .addTransition(Long.MAX_VALUE, "next", Long.MIN_VALUE)
                .addTransition(Long.MIN_VALUE, "return", Long.MAX_VALUE).build(), true);
        Map<Long, Map<String, Long>> saved = rows(first.getAnalysis());
        long large = 1L << 32;
        Result<Long, String> second = compareUpdate(first.getAnalysis(),
                SafetyGameDelta.forAnalysis(first.getAnalysis()).addState(large)
                        .addTransition(Long.MAX_VALUE, "farther", large)
                        .addTransition(large, "return", 0L).build(), true);
        assertEquals(Set.of(-1L, 0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, large), second.getAnalysis().getStates());
        assertEquals(Set.of(Long.MAX_VALUE), second.getChangedOldSources());
        assertEquals(saved, rows(first.getAnalysis()));
        assertEquals(Set.of(-1L, 0L, 1L), base.getStates());
        assertFalse(second.getAnalysis().getStates().contains(0));
    }

    @Test
    public void duplicateTargetsAndUncontrollableOnlyIndexChangesPropagateOnce() {
        Analysis<Long, String> base = checked(3L, Map.of(
                -1L, Map.of(), 0L, Map.of("c0", 1L, "c1", 1L),
                1L, Map.of("back", 2L), 2L, Map.of("next", 0L),
                3L, Map.of("c0", 4L), 4L, Map.of("back", 3L)), CONTROLS, Set.of(-1L));
        Result<Long, String> first = compareUpdate(base, SafetyGameDelta.forAnalysis(base)
                .addTransition(0L, "fault", 1L) // Existing all-edge pair; new U predecessor.
                .addTransition(3L, "c1", 0L).build(), true); // Split equal all/U rows at 0.
        assertEquals(Set.of(0L), first.getAnalysis().game.predecessors(1L));
        assertEquals(Set.of(0L), first.getAnalysis().game.uncontrollablePredecessors(1L));
        assertTrue(base.game.uncontrollablePredecessors(1L).isEmpty());
        assertEquals(Set.of(2L, 3L), first.getAnalysis().game.predecessors(0L));
        assertEquals(Set.of(2L), first.getAnalysis().game.uncontrollablePredecessors(0L));
        Result<Long, String> second = compareUpdate(first.getAnalysis(),
                SafetyGameDelta.forAnalysis(first.getAnalysis()).addTransition(1L, "failure", -1L).build(), true);
        assertEquals(Set.of(3L, 4L), second.getAnalysis().getWinningStates());
        assertEquals(Set.of(-1L, 0L, 1L, 2L), second.getAnalysis().getLosingStates());
        assertEquals(Set.of(0L, 1L, 2L, 3L, 4L), first.getAnalysis().getWinningStates());
    }

    @Test
    public void collectionViewsAndInputOwnershipRemainImmutable() {
        Map<Long, Map<String, Long>> input = new HashMap<>();
        input.put(0L, new HashMap<>(Map.of("go", 1L)));
        input.put(1L, new HashMap<>(Map.of("back", 0L)));
        Analysis<Long, String> base = checked(0L, input, Set.of(), Set.of());
        Set<Long> savedStates = base.getStates();
        Map<String, Long> savedRow = base.getTransitions(0L);
        Set<Long> savedReverse = base.game.predecessors(1L);
        input.get(0L).clear();
        input.clear();
        base.copyGame().removeTransition(0L, "go", 1L);
        rejects(UnsupportedOperationException.class, () -> savedStates.clear());
        rejects(UnsupportedOperationException.class, () -> removeFirst(savedStates.iterator()));
        rejects(UnsupportedOperationException.class, () -> savedRow.entrySet().iterator().next().setValue(0L));
        rejects(UnsupportedOperationException.class, () -> savedRow.keySet().remove("go"));
        rejects(UnsupportedOperationException.class, () -> savedRow.values().clear());
        rejects(UnsupportedOperationException.class, () -> removeFirst(savedReverse.iterator()));
        compareUpdate(base, SafetyGameDelta.forAnalysis(base).addState(2L)
                .addTransition(1L, "branch", 2L).addTransition(2L, "return", 0L).build(), true);
        assertEquals(Set.of(0L, 1L), savedStates);
        assertEquals(Map.of("go", 1L), savedRow);
        assertEquals(Set.of(0L), savedReverse);
    }

    @Test
    public void noOpGraphIdentityDoesNotEraseAnalysisVersionIdentity() {
        Analysis<Long, String> base = cycle();
        for (SafetyGameDelta<Long, String> delta : List.of(SafetyGameDelta.forAnalysis(base).build(),
                SafetyGameDelta.forAnalysis(base).addTransition(0L, "go", 1L).build())) {
            SafetyGameUpdate<Long, String> update = SafetyGameUpdate.apply(base, delta);
            assertSame(base.game, update.game);
            Result<Long, String> first = IncrementalSafetyAnalysis.analyze(update);
            Result<Long, String> branch = IncrementalSafetyAnalysis.analyze(update);
            assertEquals(1L, first.getAnalysis().getRevision());
            assertNotSame(first.getAnalysis().versionIdentity, branch.getAnalysis().versionIdentity);
            assertTrue(first.getAddedTransitions().isEmpty());
            assertTrue(first.getChangedOldSources().isEmpty());
            SafetyGameDelta<Long, String> next = SafetyGameDelta.forAnalysis(first.getAnalysis())
                    .addState(2L).addTransition(1L, "next", 2L).addTransition(2L, "back", 0L).build();
            rejects(IllegalArgumentException.class, () -> SafetyGameUpdate.apply(branch.getAnalysis(), next));
            rejects(IllegalArgumentException.class, () -> SafetyGameUpdate.apply(cycle(), delta));
        }
    }

    @Test
    public void invalidBatchesAndMalformedUnsafeRowsNeverPublishPartialChanges() {
        Analysis<Long, String> base = cycle();
        Map<Long, Map<String, Long>> before = rows(base);
        rejects(IllegalArgumentException.class, () -> SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addState(2L).addTransition(1L, "branch", 2L)
                        .addTransition(0L, "go", 2L).build()));
        rejects(IllegalArgumentException.class, () -> SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(-1L, "escape", 0L).build()));
        rejects(IllegalArgumentException.class, () -> SafetyGameUpdate.apply(base,
                SafetyGameDelta.forAnalysis(base).addTransition(0L, "unknown", Long.MAX_VALUE).build()));
        rejects(IllegalArgumentException.class, () -> FullSafetySynthesis.prepare(0L,
                Map.of(0L, Map.of(), -1L, Map.of("bad", Long.MAX_VALUE)),
                Set.of("bad"), Set.of(), Set.of(-1L)));
        rejects(IllegalArgumentException.class, () -> FullSafetySynthesis.prepare(0L,
                Map.of(0L, Map.of("undeclared", -1L), -1L, Map.of()), Set.of(), Set.of(), Set.of(-1L)));
        assertEquals(before, rows(base));
        assertEquals(Set.of(0L, 1L), base.getWinningStates());
        compareUpdate(base, SafetyGameDelta.forAnalysis(base).addState(2L)
                .addTransition(1L, "branch", 2L).addTransition(2L, "return", 0L).build(), true);
    }

    private static <S> void exerciseDomain(List<S> ids) {
        Random random = new Random(20261007L);
        for (int trial = 0; trial < 48; trial++) {
            Map<S, Map<String, S>> rows = new HashMap<>();
            Set<S> unsafe = new HashSet<>();
            for (S source : ids) {
                Map<String, S> row = new HashMap<>();
                for (String action : List.of("c0", "c1", "u0", "u1")) {
                    if (random.nextBoolean()) row.put(action, ids.get(random.nextInt(ids.size())));
                }
                rows.put(source, row);
                if (random.nextInt(5) == 0) unsafe.add(source);
            }
            checked(ids.get(trial % ids.size()), rows, CONTROLS, unsafe);
        }
    }

    private static Analysis<Long, String> cycle() {
        return checked(0L, Map.of(-1L, Map.of(), 0L, Map.of("go", 1L), 1L, Map.of("back", 0L)),
                CONTROLS, Set.of(-1L));
    }

    private static <S> Analysis<S, String> checked(S initial, Map<S, Map<String, S>> input,
            Set<String> controls, Set<S> unsafe) {
        Set<String> alphabet = new HashSet<>(Set.of("unused"));
        input.values().forEach(row -> alphabet.addAll(row.keySet()));
        Analysis<S, String> actual = FullSafetySynthesis.analyze(
                FullSafetySynthesis.prepare(initial, input, alphabet, controls, unsafe));
        Set<S> expected = closedSubsetOracle(input, controls, unsafe);
        assertEquals(expected, actual.getWinningStates());
        Set<S> losing = new HashSet<>(input.keySet());
        losing.removeAll(expected);
        assertEquals(losing, actual.getLosingStates());
        assertEquals(input.keySet(), actual.getStates());
        assertEquals(input.keySet().hashCode(), actual.getStates().hashCode());
        assertReverseIndexes(actual);
        LTS<S, String> materialized = new LTSImpl<>(initial);
        materialized.addStates(input.keySet());
        materialized.addActions(alphabet);
        input.forEach((state, row) -> row.forEach((action, target) -> materialized.addTransition(state, action, target)));
        Analysis<S, String> reference = FullSafetySynthesis.analyze(materialized, controls, unsafe);
        assertEquals(expected, reference.getWinningStates());
        assertEquals(rows(reference), rows(actual));
        assertController(actual, expected);
        return actual;
    }

    private static <S> Result<S, String> compareUpdate(Analysis<S, String> base,
            SafetyGameDelta<S, String> delta, boolean smallOracle) {
        SafetyGameUpdate<S, String> update = SafetyGameUpdate.apply(base, delta);
        Result<S, String> full = FullSafetySynthesis.analyzeUpdate(update);
        Result<S, String> incremental = IncrementalSafetyAnalysis.analyze(update);
        Result<S, String> cached = CachedLosingSafetyAnalysis.analyze(update).getResult();
        Set<S> expected = smallOracle ? closedSubsetOracle(rows(full.getAnalysis()),
                full.getAnalysis().getControllableActions(), full.getAnalysis().getUnsafeStates())
                : full.getAnalysis().getWinningStates();
        for (Result<S, String> result : List.of(full, incremental, cached)) {
            assertEquals(expected, result.getAnalysis().getWinningStates());
            assertEquals(full.getAnalysis().getLosingStates(), result.getAnalysis().getLosingStates());
            assertEquals(rows(full.getAnalysis()), rows(result.getAnalysis()));
            assertEquals(full.getAddedStates(), result.getAddedStates());
            assertEquals(full.getAddedTransitions(), result.getAddedTransitions());
            assertEquals(full.getChangedOldSources(), result.getChangedOldSources());
            assertEquals(full.getNewlyWinningStates(), result.getNewlyWinningStates());
            assertEquals(full.getNewlyLosingStates(), result.getNewlyLosingStates());
            assertReverseIndexes(result.getAnalysis());
            assertController(result.getAnalysis(), expected);
            if (base.isRealizable()) {
                SafetyController<S, String> old = FullSafetySynthesis.generateControllerSnapshot(base).get();
                ControllerUpdateSynthesis.Plan<S, String> plan = ControllerUpdateSynthesis.prepare(old, result);
                assertEquals(ControllerUpdateSynthesis.prepare(old, full).getStatus(), plan.getStatus());
                if (plan.isCompatible()) {
                    SafetyController<S, String> diff = ControllerUpdateSynthesis.generateDifferential(plan).getController().get();
                    SafetyController<S, String> fresh = FullSafetySynthesis.generateControllerSnapshot(result.getAnalysis()).get();
                    assertEquals(fresh.getStates(), diff.getStates());
                    for (S state : fresh.getStates()) assertEquals(fresh.getTransitions(state), diff.getTransitions(state));
                }
            }
        }
        return incremental;
    }

    /** Enumerate closed safe subsets, independently of predecessor propagation. */
    private static <S> Set<S> closedSubsetOracle(Map<S, Map<String, S>> rows,
            Set<String> controls, Set<S> unsafe) {
        List<S> states = new ArrayList<>(rows.keySet());
        assertTrue("Oracle is only for small games", states.size() < 20);
        Set<S> winning = new HashSet<>();
        for (int mask = 0; mask < (1 << states.size()); mask++) {
            Set<S> candidate = new HashSet<>();
            for (int i = 0; i < states.size(); i++) if ((mask & (1 << i)) != 0) candidate.add(states.get(i));
            boolean closed = true;
            for (S state : candidate) {
                boolean move = false;
                if (unsafe.contains(state)) { closed = false; break; }
                for (Map.Entry<String, S> edge : rows.get(state).entrySet()) {
                    boolean inside = candidate.contains(edge.getValue());
                    move |= inside;
                    if (!controls.contains(edge.getKey()) && !inside) closed = false;
                }
                closed &= move;
            }
            if (closed) winning.addAll(candidate);
        }
        return winning;
    }

    private static <S> void assertController(Analysis<S, String> analysis, Set<S> winning) {
        if (!winning.contains(analysis.getInitialState())) {
            assertFalse(FullSafetySynthesis.generateControllerSnapshot(analysis).isPresent());
            return;
        }
        SafetyController<S, String> controller = FullSafetySynthesis.generateControllerSnapshot(analysis).get();
        Set<S> reached = new HashSet<>();
        Queue<S> pending = new ArrayDeque<>();
        pending.add(analysis.getInitialState());
        while (!pending.isEmpty()) {
            S source = pending.remove();
            if (!reached.add(source)) continue;
            Map<String, S> expected = new HashMap<>();
            analysis.getTransitions(source).forEach((action, target) -> {
                if (winning.contains(target)) { expected.put(action, target); pending.add(target); }
            });
            assertEquals(expected, controller.getTransitions(source));
        }
        assertEquals(reached, controller.getStates());
        assertEquals(analysis.getActions(), controller.getActions());
    }

    private static <S> void assertReverseIndexes(Analysis<S, String> analysis) {
        for (S target : analysis.getStates()) {
            Set<S> all = new HashSet<>(), uncontrollable = new HashSet<>();
            for (S source : analysis.getStates()) {
                for (Map.Entry<String, S> edge : analysis.getTransitions(source).entrySet()) {
                    if (target.equals(edge.getValue())) {
                        all.add(source);
                        if (!analysis.getControllableActions().contains(edge.getKey())) uncontrollable.add(source);
                    }
                }
            }
            assertEquals(all, analysis.game.predecessors(target));
            assertEquals(uncontrollable, analysis.game.uncontrollablePredecessors(target));
        }
    }

    private static <S> Map<S, Map<String, S>> rows(Analysis<S, String> analysis) {
        Map<S, Map<String, S>> result = new HashMap<>();
        for (S state : analysis.getStates()) result.put(state, new HashMap<>(analysis.getTransitions(state)));
        return result;
    }

    private static void removeFirst(Iterator<?> iterator) {
        iterator.next();
        iterator.remove();
    }

    private static void rejects(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
            fail("Expected " + expected.getSimpleName());
        } catch (RuntimeException actual) {
            if (!expected.isInstance(actual)) throw actual;
        }
    }
}
