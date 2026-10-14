package ltsa.differential;

import MTSSynthesis.controller.differential.FullSafetySynthesis;
import MTSSynthesis.controller.differential.FullSafetySynthesis.Analysis;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSAdapter;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.lts.CompositeState;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import ltsa.lts.LTSOutput;
import ltsa.lts.util.MTSUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Explicit differential-synthesis entry point, separate from existing synthesis modes. */
public final class FullSafetySynthesisRunner {
    private FullSafetySynthesisRunner() {
    }

    /**
     * The target must be an ordinary composition of the environment and its
     * safety monitors, with violations represented by ERROR. This does not
     * infer safety goals from a controllerSpec or compare two FSP versions.
     */
    public static Analysis<Long, String> analyze(
            Path file, String target, Set<String> controllableActions, LTSOutput output)
            throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(controllableActions, "controllableActions");
        Objects.requireNonNull(output, "output");
        if (controllableActions.contains("tau")) {
            throw new IllegalArgumentException("The internal action tau must be uncontrollable.");
        }
        Path absolute = file.toAbsolutePath();
        String source = new String(Files.readAllBytes(absolute), StandardCharsets.UTF_8);
        LTS<Long, String> game;
        // LTSCompiler has static parsing tables. Calls through this entry point
        // are serialized; other compiler clients must use the same lock too.
        synchronized (LTSCompiler.class) {
            LTSCompiler compiler = new LTSCompiler(
                    new LTSInputString(source), output, absolute.getParent().toString());
            compiler.compile();
            // continueCompilation otherwise silently falls back to another target.
            if (!compiler.getComposites().containsKey(target)) {
                throw new IllegalArgumentException("Unknown FSP composition target: " + target);
            }
            CompositeState composite = compiler.continueCompilation(target);
            if (composite.goal != null || composite.makeController
                    || composite.makeRTCController || composite.makeRTCAnalysisController
                    || composite.makeControlStack || composite.isHeuristic
                    || composite.isProbabilistic || composite.makeMDP
                    || composite.makeMinimal || composite.makeDeterministic) {
                throw new IllegalArgumentException(
                        "Use an ordinary safety-game composition, without synthesis, "
                                + "minimization, determinization or probabilities.");
            }
            if (MTSUtils.isMTSRepresentation(composite)) {
                throw new IllegalArgumentException("Modal (maybe) transitions are not supported.");
            }
            composite.compose(output);
            if (composite.isProbabilistic) {
                throw new IllegalArgumentException("Probabilistic components are not supported.");
            }
            if (composite.composition == null) {
                throw new IllegalArgumentException("No composed safety game for target: " + target);
            }
            MTS<Long, String> mts = AutomataToMTSConverter.getInstance().convert(composite.composition);
            game = new LTSAdapter<>(mts, MTS.TransitionType.REQUIRED);
        }
        Set<Long> unsafe = game.getStates().contains(-1L)
                ? Collections.singleton(-1L) : Collections.emptySet();
        return FullSafetySynthesis.analyze(game, controllableActions, unsafe);
    }

    /** Prints analysis status and raw controller edges; compiler logs go to stderr. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: FullSafetySynthesisRunner <model.lts> <game-target> "
                            + "<comma-separated-controllable-actions, or ->");
        }
        Set<String> controllable = new HashSet<>();
        if (!args[2].equals("-")) {
            for (String action : Arrays.asList(args[2].split(",", -1))) {
                if (action.trim().isEmpty()) {
                    throw new IllegalArgumentException("Empty controllable action name.");
                }
                controllable.add(action.trim());
            }
        }
        LTSOutput output = new LTSOutput() {
            public void out(String text) { System.err.print(text); }
            public void outln(String text) { System.err.println(text); }
            public void clearOutput() { }
        };
        Analysis<Long, String> analysis = analyze(Paths.get(args[0]), args[1], controllable, output);
        Optional<LTS<Long, String>> generated = FullSafetySynthesis.generateController(analysis);
        System.out.println("STATUS\t" + (generated.isPresent() ? "REALIZABLE" : "UNREALIZABLE"));
        System.out.println("WINNING\t" + new TreeSet<>(analysis.getWinningStates()));
        System.out.println("LOSING\t" + new TreeSet<>(analysis.getLosingStates()));
        if (generated.isPresent()) {
            LTS<Long, String> controller = generated.get();
            System.out.println("INITIAL\t" + controller.getInitialState());
            System.out.println("STATES\t" + new TreeSet<>(controller.getStates()));
            System.out.println("ALPHABET\t" + new TreeSet<>(controller.getActions()));
            List<String> edges = new ArrayList<>();
            for (Long state : controller.getStates()) {
                for (Pair<String, Long> edge : controller.getTransitions(state)) {
                    edges.add("EDGE\t" + state + "\t" + edge.getFirst() + "\t" + edge.getSecond());
                }
            }
            Collections.sort(edges);
            System.out.println("TRANSITIONS\t" + edges.size());
            for (String edge : edges) {
                System.out.println(edge);
            }
        }
    }
}
