package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.*;

/** Full-generation reference graphs; these tests do not exercise a delta solver. */
@RunWith(Parameterized.class)
public class FullSafetySynthesisIntegrationTest {
    private static final Set<String> TINY_CONTROLS = set("idle", "start", "shortcut");
    private static final Set<String> BATCH_CONTROLS = set("idle", "start");
    private static final Set<String> BASE = set(
            "e0 idle e0", "e0 start e1", "e1 finish e0");
    private static final Set<String> RECOVERY = with(BASE,
            "e1 divert e2", "e2 wait e2", "e2 resume e1");

    private final String resource;
    private final String target;
    private final Set<String> controls;
    private final Set<String> expectedEdges;

    @Parameterized.Parameters(name = "{index}: {1}")
    public static Collection<Object[]> fixtures() {
        return Arrays.asList(new Object[][] {
                {"TinySafety.lts", "GAME_OLD", TINY_CONTROLS, BASE},
                {"TinySafety.lts", "GAME_GOOD", TINY_CONTROLS,
                        with(BASE, "e1 retry e1")},
                {"TinySafety.lts", "GAME_BAD", TINY_CONTROLS, set("e0 idle e0")},
                // This target explicitly removes the monitor; the solver does not choose a relaxation.
                {"TinySafety.lts", "GAME_RELAXED", TINY_CONTROLS,
                        with(BASE, "e1 fail e1", "e0 shortcut e1")},
                {"BatchSafety.lts", "NS_GAME_OLD", BATCH_CONTROLS, BASE},
                {"BatchSafety.lts", "NS_GAME_GOOD", BATCH_CONTROLS, RECOVERY},
                {"BatchSafety.lts", "NS_GAME_PARTIAL", BATCH_CONTROLS, set("e0 idle e0")},
                {"BatchSafety.lts", "NS_GAME_BAD", BATCH_CONTROLS, set("e0 idle e0")},
                {"BatchSafety.lts", "B_GAME_OLD", BATCH_CONTROLS, BASE},
                {"BatchSafety.lts", "B_GAME_ALARM", BATCH_CONTROLS,
                        with(BASE, "e0 alarm e1")},
                {"BatchSafety.lts", "B_GAME_RETRY", BATCH_CONTROLS,
                        with(BASE, "e1 retry e1")},
                {"BatchSafety.lts", "B_GAME_GOOD", BATCH_CONTROLS,
                        with(BASE, "e0 alarm e1", "e1 retry e1")},
                {"BatchSafety.lts", "B_GAME_FAIL", BATCH_CONTROLS, set("e0 idle e0")},
                {"BatchSafety.lts", "B_GAME_BAD", BATCH_CONTROLS, Collections.emptySet()},
                {"BatchSafety.lts", "P_GAME_OLD", BATCH_CONTROLS, withClock(BASE)},
                {"BatchSafety.lts", "P_GAME_GOOD", BATCH_CONTROLS, withClock(RECOVERY)}
        });
    }

    public FullSafetySynthesisIntegrationTest(String resource, String target,
                                              Set<String> controls, Set<String> expectedEdges) {
        this.resource = resource;
        this.target = target;
        this.controls = controls;
        this.expectedEdges = expectedEdges;
    }

    @Test
    public void generatedControllerMatchesExpectedBehavior() throws Exception {
        URL input = getClass().getResource("/DifferentialSynthesis/" + resource);
        assertNotNull("Missing fixture " + resource, input);
        Analysis<Long, String> analysis;
        // The FSP compiler owns static tables, including across parameterized test instances.
        synchronized (LTSCompiler.class) {
            analysis = FullSafetySynthesisRunner.analyze(
                    Paths.get(input.toURI()), target, controls, new EmptyLTSOuput());
        }

        Set<Long> overlap = new HashSet<>(analysis.getWinningStates());
        overlap.retainAll(analysis.getLosingStates());
        assertTrue(target + ": winning and losing states overlap", overlap.isEmpty());
        Optional<LTS<Long, String>> generated = FullSafetySynthesis.generateController(analysis);
        if (expectedEdges.isEmpty()) {
            assertFalse(target + ": initial state must be losing", analysis.isRealizable());
            assertTrue(target, analysis.getLosingStates().contains(analysis.getInitialState()));
            assertFalse(target + ": no controller may be returned", generated.isPresent());
            return;
        }

        assertTrue(target + ": expected a realizable game", analysis.isRealizable());
        assertTrue(target + ": missing controller", generated.isPresent());
        LTS<Long, String> controller = generated.get();
        assertEquals(target, analysis.getInitialState(), controller.getInitialState());

        // Match semantic roles by action paths within this graph. Separately compiled
        // old/new targets are never assumed to have the same integer state numbers.
        Set<String> expectedStates = statesOf(expectedEdges);
        Map<Long, String> roles = new HashMap<>();
        for (String role : expectedStates) {
            Long state = follow(controller, pathTo(role));
            assertNull(target + ": distinct roles collapsed at " + role, roles.put(state, role));
        }
        assertEquals(target + ": unexpected or unreachable controller states",
                roles.keySet(), controller.getStates());
        // All winning states in these small fixtures are reachable in the full controller.
        assertEquals(target, analysis.getWinningStates(), controller.getStates());
        assertEquals(target, expectedStates.size(), controller.getStates().size());

        Set<String> actualEdges = new HashSet<>();
        for (Long source : controller.getStates()) {
            for (Pair<String, Long> transition : controller.getTransitions(source)) {
                assertTrue(target + ": transition leaves the controller",
                        roles.containsKey(transition.getSecond()));
                actualEdges.add(roles.get(source) + " " + transition.getFirst()
                        + " " + roles.get(transition.getSecond()));
            }
        }
        assertEquals(target + ": controller behavior differs", expectedEdges, actualEdges);
    }

    private Long follow(LTS<Long, String> controller, List<String> actions) {
        Long state = controller.getInitialState();
        for (String action : actions) {
            Set<Long> successors = controller.getTransitions(state).getImage(action);
            assertEquals(target + ": expected one successor for path " + actions
                    + " at action " + action, 1, successors.size());
            state = successors.iterator().next();
        }
        return state;
    }

    private static List<String> pathTo(String role) {
        String plantRole = role.split("\\.")[0];
        List<String> path = new ArrayList<>();
        if (plantRole.equals("e1") || plantRole.equals("e2")) {
            path.add("start");
        } else {
            assertEquals("Unknown fixture state role", "e0", plantRole);
        }
        if (plantRole.equals("e2")) {
            path.add("divert");
        }
        if (role.endsWith(".q1")) {
            path.add("tick");
        }
        return path;
    }

    private static Set<String> withClock(Set<String> plantEdges) {
        Set<String> result = new HashSet<>();
        for (String edge : plantEdges) {
            String[] parts = edge.split(" ");
            for (int clockState = 0; clockState < 2; clockState++) {
                result.add(parts[0] + ".q" + clockState + " " + parts[1]
                        + " " + parts[2] + ".q" + clockState);
            }
        }
        for (String state : statesOf(plantEdges)) {
            result.add(state + ".q0 tick " + state + ".q1");
            result.add(state + ".q1 tock " + state + ".q0");
        }
        return result;
    }

    private static Set<String> statesOf(Set<String> edges) {
        Set<String> states = new HashSet<>();
        for (String edge : edges) {
            String[] parts = edge.split(" ");
            states.add(parts[0]);
            states.add(parts[2]);
        }
        return states;
    }

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Set<String> with(Set<String> original, String... additions) {
        Set<String> result = new HashSet<>(original);
        result.addAll(Arrays.asList(additions));
        return result;
    }
}
