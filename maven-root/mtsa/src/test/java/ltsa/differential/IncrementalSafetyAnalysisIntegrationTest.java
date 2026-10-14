package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSSynthesis.controller.differential.IncrementalSafetyAnalysis;
import MTSSynthesis.controller.differential.SafetyGameDelta;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import org.junit.Test;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * FSP references for manually bound graph additions, not an FSP diff extractor.
 * State roles are bound by action paths within each separately compiled game.
 */
public class IncrementalSafetyAnalysisIntegrationTest {
    private static final Set<String> CONTROLS =
            new HashSet<>(Arrays.asList("idle", "start"));

    @Test
    public void safeBatchAdditionsMatchNewFspGameAndController() throws Exception {
        Analysis<Long, String> base = analyze("B_GAME_OLD");
        Long working = follow(base.copyGame(), "start");
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(base)
                .addTransition(base.getInitialState(), "alarm", working)
                .addTransition(working, "retry", working)
                .build();

        Analysis<Long, String> updated = IncrementalSafetyAnalysis.update(base, delta).getAnalysis();

        assertMatchesFsp("B_GAME_GOOD", updated, 2, 0);
        assertUnchangedBase(base);
    }

    @Test
    public void unsafeBatchAdditionsMatchUnrealizableNewFspGame() throws Exception {
        Analysis<Long, String> base = analyze("B_GAME_OLD");
        LTS<Long, String> oldGame = base.copyGame();
        Long working = follow(oldGame, "start");
        Long error = freshState(oldGame);
        // B_GAME_BAD enables alarm, retry and fail together. Its compiled ERROR
        // number is irrelevant: this delta declares its own unsafe state ID.
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(base)
                .addUnsafeState(error)
                .addTransition(base.getInitialState(), "alarm", working)
                .addTransition(working, "retry", working)
                .addTransition(working, "fail", error)
                .build();

        Analysis<Long, String> updated = IncrementalSafetyAnalysis.update(base, delta).getAnalysis();

        assertMatchesFsp("B_GAME_BAD", updated, 0, 3);
        assertUnchangedBase(base);
    }

    @Test
    public void completeRecoveryStateAdditionMatchesNewFspGameAndController() throws Exception {
        Analysis<Long, String> base = analyze("NS_GAME_OLD");
        LTS<Long, String> oldGame = base.copyGame();
        Long working = follow(oldGame, "start");
        Long recovery = freshState(oldGame);
        SafetyGameDelta<Long, String> delta = SafetyGameDelta.forAnalysis(base)
                .addState(recovery)
                .addTransition(working, "divert", recovery)
                .addTransition(recovery, "resume", working)
                .addTransition(recovery, "wait", recovery)
                .build();

        Analysis<Long, String> updated = IncrementalSafetyAnalysis.update(base, delta).getAnalysis();

        assertMatchesFsp("NS_GAME_GOOD", updated, 3, 0);
        assertUnchangedBase(base);
    }

    private static Analysis<Long, String> analyze(String target) throws Exception {
        URL input = IncrementalSafetyAnalysisIntegrationTest.class
                .getResource("/DifferentialSynthesis/BatchSafety.lts");
        assertNotNull("Missing BatchSafety.lts fixture", input);
        // Other integration tests also use the compiler's shared static tables.
        synchronized (LTSCompiler.class) {
            return FullSafetySynthesisRunner.analyze(
                    Paths.get(input.toURI()), target, CONTROLS, new EmptyLTSOuput());
        }
    }

    private static void assertMatchesFsp(String target, Analysis<Long, String> actual,
                                         int winningCount, int losingCount) throws Exception {
        Analysis<Long, String> reference = analyze(target);
        assertEquals(target + ": winning count", winningCount, actual.getWinningStates().size());
        assertEquals(target + ": losing count", losingCount, actual.getLosingStates().size());
        assertEquals(target + ": realizability", reference.isRealizable(), actual.isRealizable());

        LTS<Long, String> actualGame = actual.copyGame();
        LTS<Long, String> referenceGame = reference.copyGame();
        Map<Long, String> actualRoles = roles(actualGame);
        Map<Long, String> referenceRoles = roles(referenceGame);
        assertEquals(target + ": game alphabet", referenceGame.getActions(), actualGame.getActions());
        assertEquals(target + ": game edges", edges(referenceGame, referenceRoles),
                edges(actualGame, actualRoles));
        assertEquals(target + ": winning roles", names(reference.getWinningStates(), referenceRoles),
                names(actual.getWinningStates(), actualRoles));
        assertEquals(target + ": losing roles", names(reference.getLosingStates(), referenceRoles),
                names(actual.getLosingStates(), actualRoles));

        Optional<LTS<Long, String>> actualController = FullSafetySynthesis.generateController(actual);
        Optional<LTS<Long, String>> referenceController = FullSafetySynthesis.generateController(reference);
        assertEquals(target + ": controller availability",
                referenceController.isPresent(), actualController.isPresent());
        if (referenceController.isPresent()) {
            LTS<Long, String> expected = referenceController.get();
            LTS<Long, String> generated = actualController.get();
            assertEquals(target + ": controller initial role",
                    referenceRoles.get(expected.getInitialState()),
                    actualRoles.get(generated.getInitialState()));
            assertEquals(target + ": controller states", names(expected.getStates(), referenceRoles),
                    names(generated.getStates(), actualRoles));
            assertEquals(target + ": controller alphabet", expected.getActions(), generated.getActions());
            assertEquals(target + ": controller edges", edges(expected, referenceRoles),
                    edges(generated, actualRoles));
        }
    }

    private static void assertUnchangedBase(Analysis<Long, String> base) {
        assertTrue("Updating must preserve base realizability", base.isRealizable());
        assertEquals(2, base.getWinningStates().size());
        assertTrue(base.getLosingStates().isEmpty());
        LTS<Long, String> game = base.copyGame();
        assertEquals(new HashSet<>(Arrays.asList(
                "initial idle initial", "initial start working", "working finish initial")),
                edges(game, roles(game)));
    }

    private static Map<Long, String> roles(LTS<Long, String> game) {
        Map<Long, String> result = new HashMap<>();
        result.put(game.getInitialState(), "initial");
        Long working = follow(game, "start");
        assertNull("Initial and working roles must be distinct", result.put(working, "working"));
        if (!game.getTransitions(working).getImage("divert").isEmpty()) {
            assertNull("Recovery role must be distinct",
                    result.put(follow(game, "start", "divert"), "recovery"));
        }
        if (!game.getTransitions(working).getImage("fail").isEmpty()) {
            assertNull("ERROR role must be distinct",
                    result.put(follow(game, "start", "fail"), "error"));
        }
        assertEquals("Every fixture state must have a semantic role", game.getStates(), result.keySet());
        return result;
    }

    private static Set<String> names(Set<Long> states, Map<Long, String> roles) {
        Set<String> result = new HashSet<>();
        for (Long state : states) {
            assertTrue("Unknown fixture state " + state, roles.containsKey(state));
            result.add(roles.get(state));
        }
        return result;
    }

    private static Set<String> edges(LTS<Long, String> game, Map<Long, String> roles) {
        Set<String> result = new HashSet<>();
        for (Long source : game.getStates()) {
            assertTrue("Unknown source role", roles.containsKey(source));
            for (Pair<String, Long> edge : game.getTransitions(source)) {
                assertTrue("Unknown target role", roles.containsKey(edge.getSecond()));
                result.add(roles.get(source) + " " + edge.getFirst() + " " + roles.get(edge.getSecond()));
            }
        }
        return result;
    }

    private static Long follow(LTS<Long, String> game, String... actions) {
        Long state = game.getInitialState();
        for (String action : actions) {
            Set<Long> successors = game.getTransitions(state).getImage(action);
            assertEquals("Expected one successor for action " + action, 1, successors.size());
            state = successors.iterator().next();
        }
        return state;
    }

    private static Long freshState(LTS<Long, String> game) {
        long candidate = 1_000_000L;
        while (game.getStates().contains(candidate)) {
            candidate++;
        }
        return candidate;
    }
}
