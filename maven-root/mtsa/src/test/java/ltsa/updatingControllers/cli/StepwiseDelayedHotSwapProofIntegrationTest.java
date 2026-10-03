package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Proof-oriented full-pipeline oracles for Traditional hotSwapIn monitor
 * semantics.  All cases use the same no-change plant so that the assertions
 * isolate requirement-monitor behaviour at the delayed connection boundary.
 */
public class StepwiseDelayedHotSwapProofIntegrationTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String JAVA_MODE_PROPERTY =
            "stepwise.delayed.hotSwapMonitorSemantics";
    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/StepwiseDelayedHotSwapProofIntegration.lts";
    private static final String DEFERRED_ONE =
            "1 hotSwapIn-sensitive goal(s) deferred until after delayed connection";
    private static final String POST_CONNECTION_MONITOR =
            "[Stepwise Delayed DUCS] After Traditional hotSwapIn monitor semantics";

    private static final Pattern STATE_COUNT = Pattern.compile(
            "(?m)^States:[ \\t]*\\r?\\n[ \\t]*(\\d+)[ \\t]*$");
    private static final Pattern STATE_BLOCK = Pattern.compile(
            "(?ms)^[ \\t]*Q(\\d+)[ \\t]*=[ \\t]*(.*?)(?=^[ \\t]*Q\\d+[ \\t]*=|\\z)");
    private static final Pattern TRANSITION = Pattern.compile(
            "(\\{[^}]*\\}|[^\\s()|,]+)\\s*->\\s*(Q\\d+|ERROR)");
    private static final Pattern UNUSED_ALPHABET = Pattern.compile("\\+\\{([^}]*)\\}");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private int runSequence;

    @Test(timeout = 180000)
    public void traditionalAndFullStepwiseAgreeOnHotSwapSensitiveProofBoundaries()
            throws Exception {
        // Compiler registries, update-controller definitions, and the Java
        // fallback property are process-global.  Surefire uses parallel test
        // execution, so the complete multi-target oracle is one critical section.
        synchronized (LTSCompiler.class) {
            String previousHeadless = System.getProperty("java.awt.headless");
            String previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
            String previousJavaMode = System.getProperty(JAVA_MODE_PROPERTY);
            try {
                System.setProperty("java.awt.headless", "true");
                System.setProperty(EVALUATION_ENABLED_PROPERTY, "false");
                System.clearProperty(JAVA_MODE_PROPERTY);

                RunResult traditionalTransition = run("Traditional_TransitionWinning");
                RunResult fullTransition = run("FullStepwise_TransitionWinning");
                assertController(traditionalTransition, "Traditional_TransitionWinning");
                assertController(fullTransition, "FullStepwise_TransitionWinning");
                assertDeferredMonitorPath(fullTransition,
                        "local goals by type: oldSafety=0 newSafety=0 transition=1");
                ControllerGraph traditionalTransitionGraph =
                        ControllerGraph.parse(traditionalTransition.transitionText());
                ControllerGraph fullTransitionGraph =
                        ControllerGraph.parse(fullTransition.transitionText());
                assertEquals("The minimized transition-requirement controllers differ",
                        traditionalTransitionGraph.canonicalFingerprint(),
                        fullTransitionGraph.canonicalFingerprint());
                fullTransitionGraph.assertForbiddenBetweenHotSwapAndStop("a");

                RunResult traditionalOldBoundary = run("Traditional_OldSafetyBoundary");
                System.setProperty(JAVA_MODE_PROPERTY, "stutter");
                RunResult legacyOldBoundary = run("LegacyStepwise_OldSafetyBoundary");
                RunResult fullOldBoundary = run("FullStepwise_OldSafetyBoundary");
                assertNoController(traditionalOldBoundary, "Traditional_OldSafetyBoundary");
                assertController(legacyOldBoundary, "LegacyStepwise_OldSafetyBoundary");
                assertNoController(fullOldBoundary, "FullStepwise_OldSafetyBoundary");
                assertDeferredMonitorPath(fullOldBoundary,
                        "local goals by type: oldSafety=1 newSafety=0 transition=0");

                RunResult traditionalNewSafety = run("Traditional_NewSafetyWinning");
                RunResult fullNewSafety = run("FullStepwise_NewSafetyWinning");
                assertController(traditionalNewSafety, "Traditional_NewSafetyWinning");
                assertController(fullNewSafety, "FullStepwise_NewSafetyWinning");
                assertDeferredMonitorPath(fullNewSafety,
                        "cross goals by type: oldSafety=0 newSafety=1 transition=0");
                ControllerGraph traditionalNewSafetyGraph =
                        ControllerGraph.parse(traditionalNewSafety.transitionText());
                ControllerGraph fullNewSafetyGraph =
                        ControllerGraph.parse(fullNewSafety.transitionText());
                assertEquals("The minimized new-safety controllers differ",
                        traditionalNewSafetyGraph.canonicalFingerprint(),
                        fullNewSafetyGraph.canonicalFingerprint());
                fullNewSafetyGraph.assertForbiddenBetweenHotSwapAndStop("startNewSpec");
            } finally {
                restoreProperty("java.awt.headless", previousHeadless);
                restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
                restoreProperty(JAVA_MODE_PROPERTY, previousJavaMode);
            }
        }
    }

    private RunResult run(String target) throws Exception {
        File model = new File(MODEL_PATH);
        assertTrue("Proof model is missing: " + model.getAbsolutePath(), model.isFile());
        String runName = runSequence++ + "_" + target;
        File log = temporaryFolder.newFile(runName + ".log");
        File transitions = temporaryFolder.newFile(runName + ".lts");
        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", log.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath(),
                "--minimize", "true"
        });
        return new RunResult(
                exitCode,
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8),
                transitions);
    }

    private static void assertController(RunResult result, String target) {
        assertEquals("Composition failed for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertTrue("No controller transitions were written for " + target,
                result.transitions.length() > 0L);
        assertFalse(result.log, result.log.contains("Composition not controllable"));
    }

    private static void assertNoController(RunResult result, String target) {
        assertNotEquals("Unexpected controller for " + target + "\n" + result.log,
                SingleCompositionRunner.EXIT_SUCCESS,
                result.exitCode);
        assertEquals("A losing target must not write controller transitions: " + target,
                0L,
                result.transitions.length());
        assertTrue("The failure must be a synthesis losing result, not a parse/runtime error.\n"
                        + result.log,
                result.log.contains("initial state is losing: there is no controller.")
                        || result.log.contains("[Stepwise Delayed DUCS] GR result: losing")
                        || result.log.contains("There is no controller for model"));
    }

    private static void assertDeferredMonitorPath(RunResult result, String kindSummary) {
        assertTrue(result.log, result.log.contains(DEFERRED_ONE));
        assertTrue(result.log, result.log.contains(kindSummary));
        assertTrue(result.log, result.log.contains(POST_CONNECTION_MONITOR));
        assertTrue(result.log, result.log.contains("online safety terminalization kept states"));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class RunResult {
        private final int exitCode;
        private final String log;
        private final File transitions;

        private RunResult(int exitCode, String log, File transitions) {
            this.exitCode = exitCode;
            this.log = log;
            this.transitions = transitions;
        }

        private String transitionText() throws Exception {
            return new String(Files.readAllBytes(transitions.toPath()), StandardCharsets.UTF_8);
        }
    }

    /** A deterministic, initial-state-rooted view of PrintTransitions output. */
    private static final class ControllerGraph {
        private final int declaredStateCount;
        private final Map<Integer, Map<String, Integer>> outgoing;
        private final Set<String> alphabet;

        private ControllerGraph(
                int declaredStateCount,
                Map<Integer, Map<String, Integer>> outgoing,
                Set<String> alphabet) {
            this.declaredStateCount = declaredStateCount;
            this.outgoing = outgoing;
            this.alphabet = alphabet;
        }

        private static ControllerGraph parse(String text) {
            Matcher countMatcher = STATE_COUNT.matcher(text);
            assertTrue("State count is missing from transition output:\n" + text,
                    countMatcher.find());
            int declaredStateCount = Integer.parseInt(countMatcher.group(1));

            Map<Integer, Map<String, Integer>> outgoing =
                    new HashMap<Integer, Map<String, Integer>>();
            Set<String> alphabet = new HashSet<String>();
            Matcher stateMatcher = STATE_BLOCK.matcher(text);
            while (stateMatcher.find()) {
                int source = Integer.parseInt(stateMatcher.group(1));
                Map<String, Integer> edges = outgoing.get(source);
                if (edges == null) {
                    edges = new HashMap<String, Integer>();
                    outgoing.put(source, edges);
                }
                Matcher transitionMatcher = TRANSITION.matcher(stateMatcher.group(2));
                while (transitionMatcher.find()) {
                    assertFalse("Winning controller contains an ERROR edge:\n" + text,
                            "ERROR".equals(transitionMatcher.group(2)));
                    int target = Integer.parseInt(transitionMatcher.group(2).substring(1));
                    for (String action : expandActions(transitionMatcher.group(1))) {
                        alphabet.add(action);
                        Integer previousTarget = edges.put(action, target);
                        if (previousTarget != null && previousTarget.intValue() != target) {
                            fail("Proof fixture produced a nondeterministic controller at Q"
                                    + source + " on " + action);
                        }
                    }
                }
            }

            Matcher unusedMatcher = UNUSED_ALPHABET.matcher(text);
            while (unusedMatcher.find()) {
                alphabet.addAll(splitActions(unusedMatcher.group(1)));
            }
            assertEquals("Not every declared controller state was printed\n" + text,
                    declaredStateCount,
                    outgoing.size());
            assertTrue("Initial state Q0 is missing\n" + text, outgoing.containsKey(0));
            return new ControllerGraph(declaredStateCount, outgoing, alphabet);
        }

        private static List<String> expandActions(String token) {
            if (token.startsWith("{") && token.endsWith("}")) {
                return splitActions(token.substring(1, token.length() - 1));
            }
            return Collections.singletonList(token.trim());
        }

        private static List<String> splitActions(String actions) {
            List<String> result = new ArrayList<String>();
            for (String action : actions.split(",")) {
                String trimmed = action.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        private String canonicalFingerprint() {
            Map<Integer, Integer> canonicalIds = new HashMap<Integer, Integer>();
            Deque<Integer> pending = new ArrayDeque<Integer>();
            canonicalIds.put(0, 0);
            pending.add(0);
            StringBuilder transitions = new StringBuilder();
            while (!pending.isEmpty()) {
                int source = pending.remove();
                int canonicalSource = canonicalIds.get(source);
                transitions.append("Q").append(canonicalSource).append(':');
                List<String> actions = new ArrayList<String>(outgoing.get(source).keySet());
                Collections.sort(actions);
                for (String action : actions) {
                    int target = outgoing.get(source).get(action);
                    Integer canonicalTarget = canonicalIds.get(target);
                    if (canonicalTarget == null) {
                        canonicalTarget = canonicalIds.size();
                        canonicalIds.put(target, canonicalTarget);
                        pending.add(target);
                    }
                    transitions.append(' ').append(action).append("->Q")
                            .append(canonicalTarget).append(';');
                }
                transitions.append('\n');
            }
            assertEquals("Controller contains a state unreachable from Q0",
                    declaredStateCount,
                    canonicalIds.size());
            List<String> sortedAlphabet = new ArrayList<String>(alphabet);
            Collections.sort(sortedAlphabet);
            return "states=" + declaredStateCount + "\nalphabet=" + sortedAlphabet + "\n"
                    + transitions;
        }

        private void assertForbiddenBetweenHotSwapAndStop(String forbiddenAction) {
            Deque<PhaseState> pending = new ArrayDeque<PhaseState>();
            Set<PhaseState> visited = new HashSet<PhaseState>();
            pending.add(new PhaseState(0, false));
            boolean sawHotSwap = false;
            boolean sawStopAfterHotSwap = false;
            while (!pending.isEmpty()) {
                PhaseState current = pending.remove();
                if (!visited.add(current)) {
                    continue;
                }
                for (Map.Entry<String, Integer> edge
                        : outgoing.get(current.controllerState).entrySet()) {
                    String action = edge.getKey();
                    if (current.pendingUpdate && forbiddenAction.equals(action)) {
                        fail("Controller enables " + forbiddenAction
                                + " after hotSwapIn and before stopOldSpec");
                    }
                    boolean nextPending = current.pendingUpdate;
                    if ("hotSwapIn".equals(action)) {
                        nextPending = true;
                        sawHotSwap = true;
                    } else if ("stopOldSpec".equals(action)) {
                        if (current.pendingUpdate) {
                            sawStopAfterHotSwap = true;
                        }
                        nextPending = false;
                    }
                    pending.add(new PhaseState(edge.getValue(), nextPending));
                }
            }
            assertTrue("Controller does not exercise hotSwapIn", sawHotSwap);
            assertTrue("Controller has no reachable stopOldSpec after hotSwapIn",
                    sawStopAfterHotSwap);
        }
    }

    private static final class PhaseState {
        private final int controllerState;
        private final boolean pendingUpdate;

        private PhaseState(int controllerState, boolean pendingUpdate) {
            this.controllerState = controllerState;
            this.pendingUpdate = pendingUpdate;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof PhaseState)) {
                return false;
            }
            PhaseState that = (PhaseState) other;
            return controllerState == that.controllerState
                    && pendingUpdate == that.pendingUpdate;
        }

        @Override
        public int hashCode() {
            return 31 * controllerState + (pendingUpdate ? 1 : 0);
        }
    }
}
