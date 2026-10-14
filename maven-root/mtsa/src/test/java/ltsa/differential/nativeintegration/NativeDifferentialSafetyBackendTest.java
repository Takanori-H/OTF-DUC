package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.gr.GRRankSystem;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.gr.perfect.PerfectInfoGRGameSolver;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSSynthesis.controller.util.GRGameBuilder;
import MTSSynthesis.controller.util.GameStrategyToMTSBuilder;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.Analyser;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import static MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType.REQUIRED;
import static org.junit.Assert.*;

public class NativeDifferentialSafetyBackendTest {
    private final EmptyLTSOuput output = new EmptyLTSOuput();

    @Test
    public void addedNonselfEdgeCreatesNewTuplesAndMatchesOrdinaryNativeSynthesis() {
        List<CompactState> components = components(false, false);
        Model old = compose(components);
        Set<String> controls = new HashSet<>(Arrays.asList("a", "back"));
        GRGame<Long> game = game(old.product, controls);
        PerfectInfoGRGameSolver<Long> oldSolver = solve(game);
        Set<Long> oldWinning = new HashSet<>(oldSolver.getWinningStates());
        MTS<StrategyState<Long, Integer>, String> oldController = controller(old.product, oldSolver);
        Set<String> oldControllerEdges = controllerEdges(oldController, old.tuples);
        NativeDifferentialSafetyBackend.ProductIndex index = index(old, components.size());
        NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                old.product, index, components,
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2), controls, output);

        assertEquals(2, delta.getNewStates().size());
        assertEquals(1, delta.getChangedOldSources().size());
        assertFalse(EventState.hasEvent(components.get(0).states[1], 4));
        Model fresh = compose(delta.getUpdatedComponents());
        assertEquals(edges(fresh.product, fresh.tuples), edges(old.product, index.copyTuples()));
        NativeDifferentialSafetyBackend.updateGame(game, delta);
        GRGame<Long> rebuilt = game(old.product, controls);
        assertGameIndexes(rebuilt, game);
        NativeDifferentialSafetyBackend.Analysis analysis = NativeDifferentialSafetyBackend.analyze(game, oldWinning, delta);
        PerfectInfoGRGameSolver<Long> expectedSolver = solve(rebuilt);
        assertEquals(expectedSolver.getWinningStates(), analysis.getWinning());
        MTS<StrategyState<Long, Integer>, String> full = NativeDifferentialSafetyBackend.fullController(old.product, analysis.getWinning());
        MTS<StrategyState<Long, Integer>, String> differential = NativeDifferentialSafetyBackend.differentialController(
                old.product, oldWinning, analysis.getWinning(), oldController, delta);
        Set<String> expected = controllerEdges(controller(old.product, expectedSolver), index.copyTuples());
        assertEquals(expected, controllerEdges(full, index.copyTuples()));
        assertEquals(expected, controllerEdges(differential, index.copyTuples()));
        assertEquals(oldControllerEdges, controllerEdges(oldController, old.tuples));
        assertTrue(differential.getStates().size() > oldController.getStates().size());
    }

    @Test
    public void sharedActionMissingFromMonitorRowBlocksInsteadOfStuttering() {
        List<CompactState> components = components(false, true);
        Model model = compose(components);
        NativeDifferentialSafetyBackend.ProductIndex index = index(model, components.size());
        Set<String> before = edges(model.product, model.tuples);
        NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                model.product, index, components,
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2), Collections.emptySet(), output);
        assertTrue(delta.getAddedEdges().isEmpty());
        assertEquals(before, edges(model.product, index.copyTuples()));
        Model fresh = compose(delta.getUpdatedComponents());
        assertEquals(edges(fresh.product, fresh.tuples), edges(model.product, index.copyTuples()));
    }

    @Test
    public void errorAdditionPropagatesLossAndRejectsIncompatibleController() {
        List<CompactState> components = components(true, false);
        Model model = compose(components);
        Set<String> controls = Collections.singleton("a");
        GRGame<Long> game = game(model.product, controls);
        PerfectInfoGRGameSolver<Long> oldSolver = solve(game);
        Set<Long> oldWinning = new HashSet<>(oldSolver.getWinningStates());
        MTS<StrategyState<Long, Integer>, String> oldController = controller(model.product, oldSolver);
        NativeDifferentialSafetyBackend.ProductIndex index = index(model, components.size());
        NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                model.product, index, components,
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2), controls, output);
        assertTrue(delta.getNewStates().contains(-1L));
        NativeDifferentialSafetyBackend.updateGame(game, delta);
        NativeDifferentialSafetyBackend.Analysis analysis = NativeDifferentialSafetyBackend.analyze(game, oldWinning, delta);
        assertEquals(solve(game(model.product, controls)).getWinningStates(), analysis.getWinning());
        assertFalse(analysis.getWinning().contains(-1L));
        assertFalse(analysis.getWinning().containsAll(oldWinning));
        try {
            NativeDifferentialSafetyBackend.differentialController(model.product, oldWinning, analysis.getWinning(), oldController, delta);
            fail("Old-W preservation must be checked before attaching behavior");
        } catch (IllegalArgumentException expected) { }
    }

    @Test
    public void firstUncontrollableEdgeRepairsGameIndexesButAnalysisGuardIsExplicit() {
        List<CompactState> components = components(false, false);
        Model model = compose(components);
        Set<String> controls = new HashSet<>(Arrays.asList("a", "b", "d", "back"));
        GRGame<Long> game = game(model.product, controls);
        Set<Long> oldWinning = new HashSet<>(solve(game).getWinningStates());
        NativeDifferentialSafetyBackend.ProductIndex index = index(model, components.size());
        NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                model.product, index, components,
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2), controls, output);
        NativeDifferentialSafetyBackend.updateGame(game, delta);
        assertGameIndexes(game(model.product, controls), game);
        try {
            NativeDifferentialSafetyBackend.analyze(game, oldWinning, delta);
            fail("The cached-loss guard must not silently fall back to another solver");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void absentOrNondeterministicActionsAreRejectedBeforeProductMutation() {
        List<CompactState> components = components(false, false);
        Model model = compose(components);
        NativeDifferentialSafetyBackend.ProductIndex index = index(model, components.size());
        Set<String> before = edges(model.product, model.tuples);
        for (String action : Arrays.asList("newAction", "b")) {
            try {
                NativeDifferentialSafetyBackend.updateProduct(model.product, index, components,
                        new NativeDifferentialSafetyBackend.EnvironmentEdge(1, action, 2), Collections.emptySet(), output);
                fail("Unsupported addition must fail before changing native product");
            } catch (IllegalArgumentException expected) { }
        }
        assertEquals(before, edges(model.product, index.copyTuples()));
    }

    @Test
    public void suppliedTupleCorrespondenceWorksAfterNativeStateRenumbering() {
        List<CompactState> components = components(false, false);
        Model original = compose(components);
        MTS<Long, String> renamed = new MTSImpl<>(original.product.getInitialState() + 100L);
        renamed.addActions(original.product.getActions());
        Map<Long, int[]> tuples = new HashMap<>();
        for (Long state : original.product.getStates()) {
            renamed.addState(state + 100L);
            tuples.put(state + 100L, original.tuples.get(state));
        }
        for (Long state : original.product.getStates()) {
            for (Pair<String, Long> edge : original.product.getTransitions(state, REQUIRED)) {
                renamed.addRequired(state + 100L, edge.getFirst(), edge.getSecond() + 100L);
            }
        }
        NativeDifferentialSafetyBackend.ProductIndex index = new NativeDifferentialSafetyBackend.ProductIndex(renamed, tuples, components.size());
        Set<String> controls = Collections.singleton("a");
        GRGame<Long> game = game(renamed, controls);
        Set<Long> oldWinning = new HashSet<>(solve(game).getWinningStates());
        NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                renamed, index, components,
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2), controls, output);
        NativeDifferentialSafetyBackend.updateGame(game, delta);
        Model fresh = compose(delta.getUpdatedComponents());
        assertEquals(edges(fresh.product, fresh.tuples), edges(renamed, index.copyTuples()));
        assertEquals(solve(game(renamed, controls)).getWinningStates(),
                NativeDifferentialSafetyBackend.analyze(game, oldWinning, delta).getWinning());
        for (Long state : delta.getNewStates()) assertTrue(state >= 100L);
    }

    @Test
    public void secondNonselfAdditionReusesUpdatedNativeSession() {
        List<CompactState> components = components(false, false);
        Model model = compose(components);
        Set<String> controls = Collections.singleton("a");
        GRGame<Long> game = game(model.product, controls);
        PerfectInfoGRGameSolver<Long> solver = solve(game);
        Set<Long> winning = new HashSet<>(solver.getWinningStates());
        MTS<StrategyState<Long, Integer>, String> controller = controller(model.product, solver);
        NativeDifferentialSafetyBackend.ProductIndex index = index(model, components.size());
        for (NativeDifferentialSafetyBackend.EnvironmentEdge edge : Arrays.asList(
                new NativeDifferentialSafetyBackend.EnvironmentEdge(1, "c", 2),
                new NativeDifferentialSafetyBackend.EnvironmentEdge(2, "c", 1))) {
            NativeDifferentialSafetyBackend.Delta delta = NativeDifferentialSafetyBackend.updateProduct(
                    model.product, index, components, edge, controls, output);
            NativeDifferentialSafetyBackend.updateGame(game, delta);
            Set<Long> updatedWinning = NativeDifferentialSafetyBackend.analyze(game, winning, delta).getWinning();
            controller = NativeDifferentialSafetyBackend.differentialController(model.product, winning, updatedWinning, controller, delta);
            components = delta.getUpdatedComponents();
            winning = updatedWinning;
            Model fresh = compose(components);
            assertEquals(edges(fresh.product, fresh.tuples), edges(model.product, index.copyTuples()));
            PerfectInfoGRGameSolver<Long> fullSolver = solve(game(model.product, controls));
            assertEquals(fullSolver.getWinningStates(), winning);
            assertEquals(controllerEdges(controller(model.product, fullSolver), index.copyTuples()),
                    controllerEdges(controller, index.copyTuples()));
        }
    }

    private NativeDifferentialSafetyBackend.ProductIndex index(Model model, int size) {
        return new NativeDifferentialSafetyBackend.ProductIndex(model.product, model.tuples, size);
    }

    private List<CompactState> components(boolean error, boolean blocked) {
        CompactState env = machine("ENV", 3, "tau", "a", "b", "d", "c", "back");
        add(env, 0, "a", 1); add(env, 0, "d", 2); add(env, 1, "b", 0); add(env, 2, "back", 0);
        CompactState monitor = error || blocked
                ? machine("SAFETY", 2, "tau", "a", "b", "d", "c")
                : machine("SAFETY", 2, "tau", "a", "b", "d");
        for (int s = 0; s < 2; s++) {
            add(monitor, s, "a", 1); add(monitor, s, "b", 0); add(monitor, s, "d", 0);
        }
        if (error || blocked) add(monitor, 0, "c", 0);
        if (error) add(monitor, 1, "c", -1);
        return Arrays.asList(env, monitor);
    }

    private CompactState machine(String name, int states, String... alphabet) {
        CompactState result = new CompactState();
        result.name = name; result.maxStates = states; result.alphabet = alphabet; result.states = new EventState[states];
        return result;
    }

    private void add(CompactState machine, int from, String action, int to) {
        int event = Arrays.asList(machine.alphabet).indexOf(action);
        machine.states[from] = EventStateUtils.add(machine.states[from], new EventState(event, to));
    }

    private Model compose(List<CompactState> components) {
        CompactState compact = new Analyser(new CompositeState(new Vector<>(components)), output, null).composeNoHide();
        MTS<Long, String> product = AutomataToMTSConverter.getInstance().convert(compact);
        Map<Long, int[]> tuples = new HashMap<>();
        for (Map.Entry<Integer, int[]> entry : compact.stateToComponentStates.entrySet()) tuples.put(entry.getKey().longValue(), entry.getValue().clone());
        return new Model(product, tuples);
    }

    private GRGame<Long> game(MTS<Long, String> product, Set<String> controls) {
        ControllerGoal<String> goal = new ControllerGoal<>();
        goal.addAllControllableActions(controls);
        return new GRGameBuilder<Long, String>().buildGRGameFrom(product, goal);
    }

    private PerfectInfoGRGameSolver<Long> solve(GRGame<Long> game) {
        GRRankSystem<Long> ranks = new GRRankSystem<>(game.getStates(), game.getGoal().getGuarantees(),
                game.getGoal().getAssumptions(), game.getGoal().getFailures());
        PerfectInfoGRGameSolver<Long> solver = new PerfectInfoGRGameSolver<>(game, ranks);
        solver.solveGame();
        return solver;
    }

    private MTS<StrategyState<Long, Integer>, String> controller(MTS<Long, String> product, PerfectInfoGRGameSolver<Long> solver) {
        return GameStrategyToMTSBuilder.getInstance().buildMTSFrom(product, solver.buildStrategy(), solver.getWorseRank());
    }

    private void assertGameIndexes(GRGame<Long> expected, GRGame<Long> actual) {
        assertEquals(expected.getStates(), actual.getStates());
        for (Long state : expected.getStates()) {
            assertEquals(expected.getControllableSuccessors(state), actual.getControllableSuccessors(state));
            assertEquals(expected.getUncontrollableSuccessors(state), actual.getUncontrollableSuccessors(state));
            assertEquals(expected.getPredecessors(state), actual.getPredecessors(state));
            assertEquals(expected.isUncontrollable(state), actual.isUncontrollable(state));
        }
    }

    private Set<String> edges(MTS<Long, String> model, Map<Long, int[]> tuples) {
        Set<String> result = new HashSet<>();
        for (Long source : model.getStates()) for (Pair<String, Long> edge : model.getTransitions(source, REQUIRED)) {
            result.add(tuple(source, tuples) + " " + edge.getFirst() + " " + tuple(edge.getSecond(), tuples));
        }
        return result;
    }

    private Set<String> controllerEdges(MTS<StrategyState<Long, Integer>, String> model, Map<Long, int[]> tuples) {
        Set<String> result = new HashSet<>();
        for (StrategyState<Long, Integer> source : model.getStates()) {
            for (Pair<String, StrategyState<Long, Integer>> edge : model.getTransitions(source, REQUIRED)) {
                result.add(tuple(source.getState(), tuples) + " " + edge.getFirst() + " " + tuple(edge.getSecond().getState(), tuples));
            }
        }
        return result;
    }

    private String tuple(long state, Map<Long, int[]> tuples) {
        return state == -1 ? "ERROR" : Arrays.toString(tuples.get(state));
    }

    private static final class Model {
        final MTS<Long, String> product;
        final Map<Long, int[]> tuples;
        Model(MTS<Long, String> product, Map<Long, int[]> tuples) { this.product = product; this.tuples = tuples; }
    }
}
