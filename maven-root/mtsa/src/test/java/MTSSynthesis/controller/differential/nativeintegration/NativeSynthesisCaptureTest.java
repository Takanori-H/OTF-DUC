package MTSSynthesis.controller.differential.nativeintegration;

import MTSSynthesis.controller.LTSControllerSynthesiserImpl;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.util.ControllerUtils;
import ltsa.lts.LTSOutput;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.*;

public class NativeSynthesisCaptureTest {
    private static final LTSOutput SILENT = new LTSOutput() {
        public void out(String s) { }
        public void outln(String s) { }
        public void clearOutput() { }
    };

    @Test
    public void captureUsesOrdinarySolverAndRetainsCompleteWinningRegion() {
        MTSImpl<Long, String> plant = sparsePlant();
        // An unreachable safe component is in W, but not the reachable controller.
        plant.addStates(Arrays.asList(7L, 9L));
        plant.addRequired(7L, "go", 9L);
        plant.addRequired(9L, "back", 7L);
        ControllerGoal<String> goal = goal();
        MTS<StrategyState<Long, Integer>, String> ordinary =
                new LTSControllerSynthesiserImpl<Long, String>().synthesiseGR(plant, goal);
        NativeSynthesisCapture<Long, String> capture = new NativeSynthesisCapture<>();
        MTS<StrategyState<Long, Integer>, String> measured =
                new LTSControllerSynthesiserImpl<Long, String>().synthesiseGR(plant, goal, capture);
        assertSame(plant, capture.getPlant());
        assertSame(measured, capture.getStrategyController());
        assertEquals(new HashSet<>(Arrays.asList(42L, 100L, 7L, 9L)), capture.getWinningStates());
        assertEquals(ordinary.getStates(), measured.getStates());
        assertEquals(ordinary.getTransitions(MTS.TransitionType.REQUIRED),
                measured.getTransitions(MTS.TransitionType.REQUIRED));
        assertEquals(2, measured.getStates().size());
        assertTrue(capture.getPhaseNanos().get(NativeSynthesisCapture.GAME_UPDATE) > 0);
        assertTrue(capture.getPhaseNanos().get(NativeSynthesisCapture.REQUIREMENTS_ANALYSIS) > 0);
        assertTrue(capture.getPhaseNanos().get(NativeSynthesisCapture.CONTROLLER_GENERATION) > 0);
    }

    @Test
    public void embeddingCorrespondencePreservesSparseIdsAndErrorEdges() {
        MTSImpl<Long, String> source = sparsePlant();
        Map<Long, Long> mapping = new HashMap<>();
        MTS<Long, String> embedded = ControllerUtils.embedFluents(source, goal(), SILENT, mapping);
        assertEquals(source.getInitialState(), mapping.get(embedded.getInitialState()));
        assertEquals(Long.valueOf(-1L), mapping.get(-1L));
        assertEquals(embedded.getStates(), mapping.keySet());
        for (Long from : embedded.getStates()) {
            for (Pair<String, Long> edge : embedded.getTransitions(from, MTS.TransitionType.REQUIRED)) {
                assertTrue(source.getTransitions(mapping.get(from), MTS.TransitionType.REQUIRED)
                        .getImage(edge.getFirst()).contains(mapping.get(edge.getSecond())));
            }
        }
        MTS<Long, String> ordinary = ControllerUtils.embedFluents(source, goal(), SILENT);
        assertEquals(ordinary.getStates(), embedded.getStates());
        assertEquals(ordinary.getTransitions(MTS.TransitionType.REQUIRED),
                embedded.getTransitions(MTS.TransitionType.REQUIRED));
    }

    @Test
    public void conversionAndZeroEdgeCompositionStillExposeCorrespondence() {
        MTSImpl<Long, String> plant = new MTSImpl<>(42L);
        Map<Long, Long> conversion = new HashMap<>();
        MTSToAutomataConverter.getInstance().convert(plant, "DEADLOCK", false, false, conversion);
        assertEquals(Long.valueOf(0L), conversion.get(42L));
        Map<Long, Long> embedding = new HashMap<>();
        MTS<Long, String> result = ControllerUtils.embedFluents(plant, goal(), SILENT, embedding);
        assertEquals(1, embedding.size());
        assertEquals(Long.valueOf(42L), embedding.get(result.getInitialState()));
    }

    @Test
    public void failedSynthesisRetainsAnalysisWithoutInventingController() {
        MTSImpl<Long, String> plant = new MTSImpl<>(0L);
        NativeSynthesisCapture<Long, String> capture = new NativeSynthesisCapture<>();
        assertNull(new LTSControllerSynthesiserImpl<Long, String>().synthesiseGR(plant, goal(), capture));
        assertNotNull(capture.getGame());
        assertTrue(capture.getWinningStates().isEmpty());
        assertNull(capture.getStrategyController());
        assertEquals(Long.valueOf(0L), capture.getPhaseNanos().get(NativeSynthesisCapture.CONTROLLER_GENERATION));
    }

    private static ControllerGoal<String> goal() {
        ControllerGoal<String> goal = new ControllerGoal<>();
        goal.addAllControllableActions(new HashSet<>(Arrays.asList("go", "bad")));
        return goal;
    }

    private static MTSImpl<Long, String> sparsePlant() {
        MTSImpl<Long, String> plant = new MTSImpl<>(42L);
        plant.addStates(Arrays.asList(100L, -1L));
        plant.addActions(Arrays.asList("go", "back", "bad"));
        plant.addRequired(42L, "go", 100L);
        plant.addRequired(100L, "back", 42L);
        plant.addRequired(42L, "bad", -1L);
        return plant;
    }
}
