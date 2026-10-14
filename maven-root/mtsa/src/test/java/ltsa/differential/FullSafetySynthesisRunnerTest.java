package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSException;
import ltsa.lts.LTSInputString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FullSafetySynthesisRunnerTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void doesNotSilentlyCompileAnotherTarget() throws Exception {
        rejects("P=(a->P). ||GAME=(P).", "TYPO", Set.of(), "Unknown FSP composition target");
    }

    @Test
    public void rejectsModalTransitionsInsteadOfDroppingThem() throws Exception {
        rejects("P=(a?->P). ||GAME=(P).", "GAME", Set.of(), "Modal (maybe)");
    }

    @Test
    public void rejectsProbabilitiesInsideAnOrdinaryComposition() throws Exception {
        rejects("P=(t->{0.4:P + 0.6:Q}), Q=(back->P). ||GAME=(P).",
                "GAME", Set.of(), "Probabilistic components");
    }

    @Test
    public void rejectsNondeterministicOutcomesAfterFspComposition() throws Exception {
        rejects("P=(a->P | a->STOP). ||GAME=(P).", "GAME", Set.of("a"),
                "label-deterministic");
    }

    @Test
    public void ordinaryCompilationIsUnchangedAfterDifferentialSuccessAndFailure() throws Exception {
        String normal = "P=(idle->P | start->WORK), WORK=(finish->P). ||NORMAL=(P).";
        Set<String> before = ordinaryGraph(normal);
        Path input = temporary.newFile("error-alias.lts").toPath();
        // ERROR aliases use the same shared compiler correction in both entry points.
        Files.write(input, ("P=(idle->P | start->WORK), WORK=(finish->P | fail->BROKEN), "
                + "BROKEN=ERROR. ||GAME=(P).").getBytes(StandardCharsets.UTF_8));
        FullSafetySynthesis.Analysis<Long, String> analysis = FullSafetySynthesisRunner.analyze(
                input, "GAME", Set.of("idle", "start"), new EmptyLTSOuput());
        assertTrue(analysis.isRealizable());
        assertEquals(1, FullSafetySynthesis.generateController(analysis).get().getStates().size());
        assertEquals(before, ordinaryGraph(normal));

        rejects("P=(a->P). ||GAME=(P).", "TYPO", Set.of(), "Unknown FSP composition target");
        assertEquals(before, ordinaryGraph(normal));
    }

    @Test
    public void ordinaryCompilationStillWorksAfterParserException() throws Exception {
        String normal = "P=(idle->P | start->WORK), WORK=(finish->P). ||NORMAL=(P).";
        Set<String> before = ordinaryGraph(normal);
        Path input = temporary.newFile("invalid.lts").toPath();
        Files.write(input, "P=(a->). ||GAME=(P).".getBytes(StandardCharsets.UTF_8));
        try {
            FullSafetySynthesisRunner.analyze(input, "GAME", Set.of(), new EmptyLTSOuput());
            fail("The malformed FSP must fail to compile.");
        } catch (LTSException expected) {
            // A later compilation must replace the failed parser state.
        }
        assertEquals(before, ordinaryGraph(normal));
    }

    private Set<String> ordinaryGraph(String source) {
        synchronized (LTSCompiler.class) {
            EmptyLTSOuput output = new EmptyLTSOuput();
            LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), output, ".");
            compiler.compile();
            CompositeState composite = compiler.continueCompilation("NORMAL");
            composite.compose(output);
            MTS<Long, String> graph = AutomataToMTSConverter.getInstance().convert(composite.composition);
            Set<String> result = new HashSet<>();
            result.add("initial:" + graph.getInitialState());
            result.add("states:" + new TreeSet<>(graph.getStates()));
            result.add("alphabet:" + new TreeSet<>(graph.getActions()));
            for (Long state : graph.getStates()) {
                for (Pair<String, Long> edge : graph.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    result.add(state + ":" + edge.getFirst() + ":" + edge.getSecond());
                }
            }
            return result;
        }
    }

    private void rejects(String source, String target, Set<String> controls, String message)
            throws Exception {
        Path file = temporary.newFile("model.lts").toPath();
        Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        try {
            FullSafetySynthesisRunner.analyze(file, target, controls, new EmptyLTSOuput());
            fail("Expected input rejection: " + message);
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(message));
        }
    }
}
