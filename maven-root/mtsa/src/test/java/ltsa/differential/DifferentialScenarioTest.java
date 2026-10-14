package ltsa.differential;

import MTSSynthesis.controller.differential.ControllerUpdateSynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import MTSSynthesis.controller.differential.SafetyGameDelta.Transition;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import ltsa.lts.EmptyLTSOuput;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class DifferentialScenarioTest {
    private static final Set<String> CONTROLS = set("idle", "start");
    private static final String HEADER = "controllableActions: [idle, start]\n";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void cliFixturesCoverSuccessfulIncompatibleAndUnrealizableUpdates() throws Exception {
        assertFixture("batch-good.yaml", "B_GAME_OLD", ControllerUpdateSynthesis.Status.SUCCESS, 2, 0);
        assertFixture("batch-incompatible.yaml", "B_GAME_OLD",
                ControllerUpdateSynthesis.Status.SIMULATION_INCOMPATIBLE, 1, 2);
        assertFixture("batch-unrealizable.yaml", "B_GAME_OLD",
                ControllerUpdateSynthesis.Status.UNREALIZABLE, 0, 3);
        assertFixture("recovery-good.yaml", "NS_GAME_OLD", ControllerUpdateSynthesis.Status.SUCCESS, 3, 0);
    }

    @Test
    public void tracesBindToOldStateRolesAndNewIdsFollowExistingMaximum() throws Exception {
        Analysis<Long, String> base = twoStateGame();
        DifferentialScenario scenario = load(HEADER
                + "states:\n"
                + "  initial: {trace: []}\n"
                + "  working: {trace: [start]}\n"
                + "  recovery: {new: true}\n"
                + "  error: {new: true, unsafe: true}\n"
                + "transitions:\n"
                + "  - {from: working, action: divert, to: recovery}\n"
                + "  - {from: recovery, action: resume, to: working}\n"
                + "  - {from: recovery, action: fail, to: error}\n");
        SafetyGameDelta<Long, String> delta = scenario.resolve(base);
        assertEquals(set(71L, 72L), delta.getAddedStates());
        assertEquals(Collections.singleton(72L), delta.getAddedUnsafeStates());
        assertEquals(set(new Transition<>(70L, "divert", 71L),
                new Transition<>(71L, "resume", 70L), new Transition<>(71L, "fail", 72L)),
                delta.getAddedTransitions());
        assertEquals(set(10L, 70L), base.getStates());
        assertEquals(CONTROLS, scenario.getControllableActions());
    }

    @Test
    public void differentAliasesMayReferToTheSameOldState() throws Exception {
        DifferentialScenario scenario = load(HEADER
                + "states:\n"
                + "  initial: {trace: []}\n"
                + "  same: {trace: [start, finish]}\n"
                + "transitions: [{from: initial, action: retry, to: same}]\n");
        assertEquals(Collections.singleton(new Transition<>(10L, "retry", 10L)),
                scenario.resolve(twoStateGame()).getAddedTransitions());
    }

    @Test
    public void reachableOldErrorMayBeReferencedWithoutDeclaringANewUnsafeState() throws Exception {
        LTS<Long, String> game = new LTSImpl<>(0L);
        game.addState(-1L);
        game.addActions(CONTROLS);
        game.addTransition(0L, "idle", 0L);
        game.addTransition(0L, "start", -1L);
        Analysis<Long, String> base = FullSafetySynthesis.analyze(game, CONTROLS, Collections.singleton(-1L));
        DifferentialScenario scenario = load(HEADER
                + "states: {initial: {trace: []}, error: {trace: [start]}}\n"
                + "transitions: [{from: initial, action: fail, to: error}]\n");
        SafetyGameDelta<Long, String> delta = scenario.resolve(base);
        assertTrue(delta.getAddedStates().isEmpty());
        assertTrue(delta.getAddedUnsafeStates().isEmpty());
        assertEquals(Collections.singleton(new Transition<>(0L, "fail", -1L)), delta.getAddedTransitions());
    }

    @Test
    public void emptyScenarioSupportsAllUncontrollableGames() throws Exception {
        DifferentialScenario scenario = load("controllableActions: []\nstates: {}\ntransitions: []\n");
        LTS<Long, String> game = new LTSImpl<>(0L);
        game.addAction("tick");
        game.addTransition(0L, "tick", 0L);
        Analysis<Long, String> base = FullSafetySynthesis.analyze(game, Collections.emptySet(), Collections.emptySet());
        SafetyGameDelta<Long, String> delta = scenario.resolve(base);
        assertTrue(delta.getAddedStates().isEmpty());
        assertTrue(delta.getAddedTransitions().isEmpty());
        assertTrue(IncrementalSafetyAnalysis.update(base, delta).getAnalysis().isRealizable());
    }

    @Test
    public void newlyAllocatedStateNeverUsesFspErrorNumber() throws Exception {
        DifferentialScenario scenario = load(HEADER + "states: {extra: {new: true}}\ntransitions: []\n");
        assertEquals(Collections.singleton(0L), scenario.resolve(singleStateGame(-2L)).getAddedStates());
    }

    @Test
    public void allocationOverflowIsRejectedWithoutChangingTheBase() throws Exception {
        DifferentialScenario scenario = load(HEADER
                + "states: {first: {new: true}, second: {new: true}}\ntransitions: []\n");
        Analysis<Long, String> base = singleStateGame(Long.MAX_VALUE - 1L);
        assertResolveRejected(scenario, base, "Long.MAX_VALUE");
        assertEquals(Collections.singleton(Long.MAX_VALUE - 1L), base.getStates());
        DifferentialScenario empty = load(HEADER + "states: {}\ntransitions: []\n");
        assertTrue(empty.resolve(singleStateGame(Long.MAX_VALUE)).getAddedStates().isEmpty());
    }

    @Test
    public void missingTraceStepIsRejectedAndCannotUseAnEdgeFromTheDelta() throws Exception {
        DifferentialScenario scenario = load(HEADER
                + "states: {initial: {trace: []}, afterAlarm: {trace: [alarm]}}\n"
                + "transitions: [{from: initial, action: alarm, to: initial}]\n");
        assertResolveRejected(scenario, twoStateGame(), "afterAlarm", "trace step 1", "alarm");
    }

    @Test
    public void controllabilityMismatchIsRejected() throws Exception {
        DifferentialScenario scenario = load("controllableActions: [idle]\nstates: {}\ntransitions: []\n");
        assertResolveRejected(scenario, twoStateGame(), "controllableActions differ");
    }

    @Test
    public void transitionsFromNewOrExistingUnsafeAliasesAreRejected() throws Exception {
        DifferentialScenario scenario = load(HEADER
                + "states: {error: {new: true, unsafe: true}}\n"
                + "transitions: [{from: error, action: fail, to: error}]\n");
        assertResolveRejected(scenario, twoStateGame(), "unsafe alias 'error'");
        LTS<Long, String> game = new LTSImpl<>(0L);
        game.addState(-1L);
        game.addActions(CONTROLS);
        game.addTransition(0L, "start", -1L);
        Analysis<Long, String> base = FullSafetySynthesis.analyze(game, CONTROLS, Collections.singleton(-1L));
        DifferentialScenario oldError = load(HEADER
                + "states: {error: {trace: [start]}}\n"
                + "transitions: [{from: error, action: idle, to: error}]\n");
        assertResolveRejected(oldError, base, "unsafe alias 'error'");
    }

    @Test
    public void duplicateMappingKeysAreRejectedAtEverySchemaLevel() throws Exception {
        assertLoadRejected(HEADER + "controllableActions: []\nstates: {}\ntransitions: []\n", "Duplicate mapping key");
        assertLoadRejected(HEADER + "states:\n  a: {trace: []}\n  a: {new: true}\ntransitions: []\n", "Duplicate mapping key");
        assertLoadRejected(HEADER + "states: {a: {new: true, new: true}}\ntransitions: []\n", "Duplicate mapping key");
        assertLoadRejected(HEADER + "states: {a: {trace: []}}\n"
                + "transitions: [{from: a, action: idle, action: retry, to: a}]\n", "Duplicate mapping key");
    }

    @Test
    public void unknownKeysAndUndefinedAliasesAreRejected() throws Exception {
        assertLoadRejected(HEADER + "states: {}\ntransitions: []\nunknown: true\n", "Unknown key scenario.unknown");
        assertLoadRejected(HEADER + "states: {a: {trace: [], id: 0}}\ntransitions: []\n", "Unknown key states.a.id");
        assertLoadRejected(HEADER + "states: {a: {trace: []}}\n"
                + "transitions: [{from: a, action: idle, to: a, remove: true}]\n", "Unknown key transitions[0].remove");
        assertLoadRejected(HEADER + "states: {}\ntransitions: [{from: missing, action: idle, to: missing}]\n", "undefined alias 'missing'");
        assertLoadRejected(HEADER + "states: {a: {trace: []}}\ntransitions: [{from: a, action: idle, to: missing}]\n", "undefined alias 'missing'");
    }

    @Test
    public void malformedTypesEmptyLabelsAndAmbiguousStateDeclarationsAreRejected() throws Exception {
        String[][] invalid = {
                {"states: {}\ntransitions: []\n", "controllableActions is required"},
                {"controllableActions: idle\nstates: {}\ntransitions: []\n", "controllableActions must be a list"},
                {HEADER + "states: []\ntransitions: []\n", "states must be a mapping"},
                {HEADER + "states: {}\ntransitions: {}\n", "transitions must be a list"},
                {HEADER + "states: {'': {trace: []}}\ntransitions: []\n", "nonempty string"},
                {HEADER + "states: {a: {trace: start}}\ntransitions: []\n", "trace must be a list"},
                {HEADER + "states: {a: {trace: [1]}}\ntransitions: []\n", "nonempty string"},
                {HEADER + "states: {a: {new: 'true'}}\ntransitions: []\n", "new must be a boolean"},
                {HEADER + "states: {a: {new: false}}\ntransitions: []\n", "new must be true"},
                {HEADER + "states: {a: {new: true, unsafe: 'false'}}\ntransitions: []\n", "unsafe must be a boolean"},
                {HEADER + "states: {a: {trace: [], new: true}}\ntransitions: []\n", "exactly one"},
                {HEADER + "states: {a: {}}\ntransitions: []\n", "exactly one"},
                {HEADER + "states: {a: {trace: [], unsafe: false}}\ntransitions: []\n", "only for a new state"},
                {HEADER + "states: {a: {trace: []}}\ntransitions: [{from: a, action: '', to: a}]\n", "nonempty string"},
                {HEADER + "states: {a: {trace: []}}\ntransitions: [{from: a, to: a}]\n", "action is required"},
                {"controllableActions: [tau]\nstates: {}\ntransitions: []\n", "tau must be uncontrollable"}
        };
        for (String[] example : invalid) {
            assertLoadRejected(example[0], example[1]);
        }
    }

    @Test
    public void safeYamlLoaderRejectsObjectTagsAndMultipleDocuments() throws Exception {
        assertLoadRejected("controllableActions: !!java.util.ArrayList []\nstates: {}\ntransitions: []\n",
                "constructor for the tag");
        assertLoadRejected(HEADER + "states: {}\ntransitions: []\n---\n" + HEADER
                + "states: {}\ntransitions: []\n", "single document");
    }

    @Test
    public void exposedControllableActionsAreImmutable() throws Exception {
        DifferentialScenario scenario = load(HEADER + "states: {}\ntransitions: []\n");
        try {
            scenario.getControllableActions().add("fail");
            fail("Controllability must be immutable");
        } catch (UnsupportedOperationException expected) {
            assertEquals(CONTROLS, scenario.getControllableActions());
        }
    }

    private static void assertFixture(String scenarioFile, String target,
            ControllerUpdateSynthesis.Status expected, int winning, int losing) throws Exception {
        DifferentialScenario scenario = DifferentialScenario.load(resource("cli/" + scenarioFile));
        Analysis<Long, String> base = FullSafetySynthesisRunner.analyze(resource("BatchSafety.lts"),
                target, scenario.getControllableActions(), new EmptyLTSOuput());
        var oldController = FullSafetySynthesis.generateControllerSnapshot(base).get();
        var change = IncrementalSafetyAnalysis.update(base, scenario.resolve(base));
        assertEquals(scenarioFile, winning, change.getAnalysis().getWinningStates().size());
        assertEquals(scenarioFile, losing, change.getAnalysis().getLosingStates().size());
        var plan = ControllerUpdateSynthesis.prepare(oldController, change);
        assertEquals(scenarioFile, expected, ControllerUpdateSynthesis.generateFull(plan).getStatus());
        assertEquals(scenarioFile, expected, ControllerUpdateSynthesis.generateDifferential(plan).getStatus());
    }

    private DifferentialScenario load(String yaml) throws Exception {
        Path file = temporary.newFile().toPath();
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return DifferentialScenario.load(file);
    }

    private void assertLoadRejected(String yaml, String expectedMessage) throws Exception {
        try {
            load(yaml);
            fail("Expected rejection: " + expectedMessage);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private static void assertResolveRejected(DifferentialScenario scenario,
            Analysis<Long, String> base, String... expectedMessages) {
        try {
            scenario.resolve(base);
            fail("Expected scenario resolution to fail");
        } catch (IllegalArgumentException expected) {
            for (String message : expectedMessages) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(message));
            }
        }
    }

    private static Path resource(String name) throws Exception {
        URL resource = DifferentialScenarioTest.class.getResource("/DifferentialSynthesis/" + name);
        assertNotNull(name, resource);
        return Paths.get(resource.toURI());
    }

    private static Analysis<Long, String> twoStateGame() {
        LTS<Long, String> game = new LTSImpl<>(10L);
        game.addState(70L);
        game.addActions(set("idle", "start", "finish"));
        game.addTransition(10L, "idle", 10L);
        game.addTransition(10L, "start", 70L);
        game.addTransition(70L, "finish", 10L);
        return FullSafetySynthesis.analyze(game, CONTROLS, Collections.emptySet());
    }

    private static Analysis<Long, String> singleStateGame(long state) {
        LTS<Long, String> game = new LTSImpl<>(state);
        game.addAction("idle");
        game.addTransition(state, "idle", state);
        return FullSafetySynthesis.analyze(game, CONTROLS, Collections.emptySet());
    }

    @SafeVarargs
    private static <T> Set<T> set(T... values) {
        return new HashSet<>(Arrays.asList(values));
    }
}
