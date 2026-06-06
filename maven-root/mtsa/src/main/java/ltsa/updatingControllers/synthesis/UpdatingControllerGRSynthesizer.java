package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.FluentUtils;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.controller.gr.GRGameSolver;
import MTSSynthesis.controller.gr.GRRankSystem;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.gr.knowledge.KnowledgeGRGame;
import MTSSynthesis.controller.gr.knowledge.KnowledgeGRGameSolver;
import MTSSynthesis.controller.gr.perfect.PerfectInfoGRGameSolver;
import MTSSynthesis.controller.model.*;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSSynthesis.controller.util.GRGameBuilder;
import MTSSynthesis.controller.util.GameStrategyToMTSBuilder;
import MTSSynthesis.controller.util.SubsetConstructionBuilder;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSSynthesis.controller.model.gr.GRGoal;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSAdapter;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSAdapter;
import MTSTools.ac.ic.doc.mtstools.utils.GenericMTSToLongStringMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.lts.CompactState;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.DUCHeartbeat;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Logger;

/**
 * Created by lnahabedian on 06/07/16.
 */
public class UpdatingControllerGRSynthesizer {

    public static void synthesizeGR(CompactState compactSafetyEnv, UpdatingControllerCompositeState uccs, MTS<Long, String> safetyEnv, LTSOutput output) {

        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "synthesizeGR 全体時間");
        output.outln("Synthezising GR");
        if (compactSafetyEnv.isNonDeterministic()){
            output.outln("Environment after safety is non-deterministic");
            output.outln("Solving a non-deterministic controller synthesis");
            nonBlockingGR(uccs, output, safetyEnv);
        } else {
            output.outln("Environment after safety is deterministic");
            output.outln("Solving a deterministic controller synthesis");
            synthesizeGRDeterministic(uccs, output, safetyEnv);
        }
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "synthesizeGR 全体時間");

    }

    private static void nonBlockingGR(UpdatingControllerCompositeState uccs, LTSOutput output, MTS<Long, String> safetyEnv) {

        KnowledgeGRGame<Long, String> game;
        GRGoal<Set<Long>> grGoal;
        MTS<Set<Long>, String> perfectInfoGame;
        SubsetConstructionBuilder<Long, String> subsetConstructionBuilder;

        FluentUtils fluentUtils = FluentUtils.getInstance();

        long subsetStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_SUBSET_CONSTRUCTION");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "非決定環境の subset construction 時間");
        subsetConstructionBuilder = new SubsetConstructionBuilder<Long, String>(safetyEnv);
        perfectInfoGame = subsetConstructionBuilder.build();
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "非決定環境の subset construction 時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "非決定環境の subset construction 時間",
                System.currentTimeMillis() - subsetStart);
        if (uccs.isShowGRGameInDraw()) {
            addGRGameDrawMachine(uccs, buildPerfectInfoGRGameCompactState(perfectInfoGame), output);
        }

        FluentStateValuation<Set<Long>> valuation = fluentUtils.buildValuation(perfectInfoGame, uccs.getUpdateGRGoal().getFluents());
        long goalBuildStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_GOAL_BUILD");
        DUCHeartbeat.setCounter("perfectInfoStates", perfectInfoGame.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "GR goal 構築時間");
        Assumptions<Set<Long>> assumptions = formulasToAssumptions(perfectInfoGame.getStates(), uccs.getUpdateGRGoal().getAssumptions(), valuation);
        Guarantees<Set<Long>> guarantees = formulasToGuarantees(perfectInfoGame.getStates(), uccs.getUpdateGRGoal().getGuarantees(), valuation);
        Set<Set<Long>> faults = new HashSet<Set<Long>>();

        grGoal = new GRGoal<Set<Long>>(guarantees, assumptions, faults, uccs.getUpdateGRGoal().isPermissive());
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "GR goal 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "GR goal 構築時間",
                System.currentTimeMillis() - goalBuildStart);
        Set<Set<Long>> initialStates = new HashSet<Set<Long>>();
        Set<Long> initialState = new HashSet<Long>();
        initialState.add(safetyEnv.getInitialState());
        initialStates.add(initialState);

        long gameBuildStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_GAME_BUILD");
        DUCHeartbeat.setCounter("perfectInfoStates", perfectInfoGame.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Knowledge GR game 構築時間");
        game = new KnowledgeGRGame<Long, String>(initialStates, safetyEnv, perfectInfoGame, uccs.getUpdateGRGoal().getControllableActions(), grGoal);
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Knowledge GR game 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "Knowledge GR game 構築時間",
                System.currentTimeMillis() - gameBuildStart);

        long rankSystemStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_RANK_SYSTEM_BUILD");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間");
        GRRankSystem<Set<Long>> system = new GRRankSystem<Set<Long>>(game.getStates(), grGoal.getGuarantees(), grGoal.getAssumptions(), grGoal.getFailures());
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間",
                System.currentTimeMillis() - rankSystemStart);

        KnowledgeGRGameSolver<Long, String> solver = new KnowledgeGRGameSolver<Long, String>(game, system);
        long solveStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_WINNING_REGION");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間");
        solver.solveGame();
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間",
                System.currentTimeMillis() - solveStart);

        if (solver.isWinning(perfectInfoGame.getInitialState())) {
            long strategyBuildStart = System.currentTimeMillis();
            DUCHeartbeat.beginPhase("TRADITIONAL_GR1_STRATEGY_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間");
            Strategy<Set<Long>, Integer> strategy = solver.buildStrategy();
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間",
                    System.currentTimeMillis() - strategyBuildStart);

            Set<Pair<StrategyState<Set<Long>, Integer>, StrategyState<Set<Long>, Integer>>> worseRank = solver.getWorseRank();
            long strategyToMtsStart = System.currentTimeMillis();
            DUCHeartbeat.beginPhase("TRADITIONAL_GR1_CONTROLLER_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間");
            MTS<StrategyState<Set<Long>, Integer>, String> result = GameStrategyToMTSBuilder.getInstance().buildMTSFrom(perfectInfoGame, strategy, worseRank);

            result.removeUnreachableStates();
            LTSAdapter<StrategyState<Set<Long>, Integer>, String> ltsAdapter = new LTSAdapter<StrategyState<Set<Long>,Integer>, String>(result, MTS.TransitionType.POSSIBLE);
            MTS<StrategyState<Set<Long>, Integer>, String> synthesised  = new MTSAdapter<StrategyState<Set<Long>,Integer>, String>(ltsAdapter);
            MTS<Long, String> plainController = new GenericMTSToLongStringMTSConverter<StrategyState<Set<Long>, Integer>, String>().transform(synthesised);
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間",
                    System.currentTimeMillis() - strategyToMtsStart);

            output.outln("Controller [" + plainController.getStates().size() + "] generated successfully.");
            long compactConvertStart = System.currentTimeMillis();
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Controller を CompactState に変換する時間");
            CompactState compactState = MTSToAutomataConverter.getInstance().convert(plainController, uccs.getName(), false, true);
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Controller を CompactState に変換する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    "Traditional DUC GR1 時間内訳",
                    "Controller を CompactState に変換する時間",
                    System.currentTimeMillis() - compactConvertStart);
            uccs.setComposition(compactState);
        } else {
            output.outln("There is no controller for model " + uccs.name + " for the given setting.");
            uccs.setComposition(null);
        }
    }

    private static Assumptions<Set<Long>> formulasToAssumptions(Set<Set<Long>> states, List<Formula> formulas, FluentStateValuation<Set<Long>> valuation) {

        Assumptions<Set<Long>> assumptions = new Assumptions<Set<Long>>();
        for (Formula formula : formulas) {
            Assume<Set<Long>> assume = new Assume<Set<Long>>();
            for (Set<Long> state : states) {
                valuation.setActualState(state);
                if (formula.evaluate(valuation)) {
                    assume.addState(state);
                }
            }
            if (assume.isEmpty()) {
                Logger.getAnonymousLogger().warning("There is no state satisfying formula:" + formula);
            }
            assumptions.addAssume(assume);
        }

        if (assumptions.isEmpty()) {
            Assume<Set<Long>> trueAssume = new Assume<Set<Long>>();
            trueAssume.addStates(states);
            assumptions.addAssume(trueAssume);
        }

        return assumptions;
    }

    private static Guarantees<Set<Long>> formulasToGuarantees(Set<Set<Long>> states, List<Formula> formulas, FluentStateValuation<Set<Long>> valuation) {

        Guarantees<Set<Long>> guarantees = new Guarantees<Set<Long>>();
        for (Formula formula : formulas) {
            Guarantee<Set<Long>> guarantee = new Guarantee<Set<Long>>();
            for (Set<Long> state : states) {
                valuation.setActualState(state);
                if (formula.evaluate(valuation)) {
                    guarantee.addState(state);
                }
            }
            if (guarantee.isEmpty()) {
                Logger.getAnonymousLogger().warning("There is no state satisfying formula:" + formula);
            }
            guarantees.addGuarantee(guarantee);
        }

        if (guarantees.isEmpty()) {
            Guarantee<Set<Long>> trueAssume = new Guarantee<Set<Long>>();
            trueAssume.addStates(states);
            guarantees.addGuarantee(trueAssume);
        }

        return guarantees;
    }

    private static void synthesizeGRDeterministic(UpdatingControllerCompositeState uccs, LTSOutput output, MTS<Long, String> safetyEnv) {
        GRGame<Long> game;

        long gameBuildStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_GAME_BUILD");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "GR game 構築時間");
        game = new GRGameBuilder<Long, String>().buildGRGameFrom(safetyEnv,uccs.getUpdateGRGoal());
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "GR game 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "GR game 構築時間",
                System.currentTimeMillis() - gameBuildStart);
        if (uccs.isShowGRGameInDraw()) {
            addGRGameDrawMachine(uccs, buildDeterministicGRGameCompactState(game), output);
        }
        long rankSystemStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_RANK_SYSTEM_BUILD");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間");
        GRRankSystem<Long> system = new GRRankSystem<Long>(game.getStates(),game.getGoal().getGuarantees(),
                game.getGoal().getAssumptions(), game.getGoal().getFailures());
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "Rank system 構築時間",
                System.currentTimeMillis() - rankSystemStart);
        PerfectInfoGRGameSolver<Long> solver = new PerfectInfoGRGameSolver<Long>(game, system);
        long solveStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_WINNING_REGION");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間");
        solver.solveGame();
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Traditional DUC GR1 時間内訳",
                "Winning region 計算時間",
                System.currentTimeMillis() - solveStart);

        if (solver.isWinning(safetyEnv.getInitialState())) {
            long strategyBuildStart = System.currentTimeMillis();
            DUCHeartbeat.beginPhase("TRADITIONAL_GR1_STRATEGY_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間");
            Strategy<Long, Integer> strategy = solver.buildStrategy();
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy 構築時間",
                    System.currentTimeMillis() - strategyBuildStart);
            GRGameSolver<Long> grSolver = (GRGameSolver<Long>) solver;
            Set<Pair<StrategyState<Long, Integer>, StrategyState<Long, Integer>>> worseRank = grSolver.getWorseRank();
            long strategyToMtsStart = System.currentTimeMillis();
            DUCHeartbeat.beginPhase("TRADITIONAL_GR1_CONTROLLER_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間");
            MTS<StrategyState<Long, Integer>, String> result = GameStrategyToMTSBuilder.getInstance().buildMTSFrom(safetyEnv, strategy, worseRank, uccs.getUpdateGRGoal().getLazyness());
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    "Traditional DUC GR1 時間内訳",
                    "Strategy から controller MTS を構築する時間",
                    System.currentTimeMillis() - strategyToMtsStart);

            if (result == null) {
                output.outln("There is no controller for model " + uccs.name + " for the given setting.");
                uccs.setComposition(null);
            } else {
                long plainTransformStart = System.currentTimeMillis();
                UpdatingControllerEvaluationRecorder.beginFailureTimer(
                        "Traditional DUC GR1 時間内訳",
                        "StrategyState controller を Long/String MTS に変換する時間");
                GenericMTSToLongStringMTSConverter<StrategyState<Long, Integer>, String> transformer = new GenericMTSToLongStringMTSConverter<StrategyState<Long, Integer>, String>();
                MTS<Long, String> plainController = transformer.transform(result);
                UpdatingControllerEvaluationRecorder.endFailureTimer(
                        "Traditional DUC GR1 時間内訳",
                        "StrategyState controller を Long/String MTS に変換する時間");
                UpdatingControllerEvaluationRecorder.recordTime(
                        "Traditional DUC GR1 時間内訳",
                        "StrategyState controller を Long/String MTS に変換する時間",
                        System.currentTimeMillis() - plainTransformStart);

                output.outln("Controller [" + plainController.getStates().size() + "] generated successfully.");
                long compactConvertStart = System.currentTimeMillis();
                UpdatingControllerEvaluationRecorder.beginFailureTimer(
                        "Traditional DUC GR1 時間内訳",
                        "Controller を CompactState に変換する時間");
                CompactState convert = MTSToAutomataConverter.getInstance().convert(plainController, uccs.getName(), true);
                UpdatingControllerEvaluationRecorder.endFailureTimer(
                        "Traditional DUC GR1 時間内訳",
                        "Controller を CompactState に変換する時間");
                UpdatingControllerEvaluationRecorder.recordTime(
                        "Traditional DUC GR1 時間内訳",
                        "Controller を CompactState に変換する時間",
                        System.currentTimeMillis() - compactConvertStart);
                uccs.setComposition(convert);
            }
        } else {
            output.outln("There is no controller for model " + uccs.name + " for the given setting.");
            uccs.setComposition(null);
        }

    }

    private static CompactState buildDeterministicGRGameCompactState(GRGame<Long> game) {
        Long initialState = game.getInitialStates().iterator().next();
        MTS<Long, String> gameMts = new MTSImpl<Long, String>(initialState);
        gameMts.addStates(game.getStates());
        gameMts.addAction("controllable");
        gameMts.addAction("uncontrollable");

        for (Long state : game.getStates()) {
            for (Long successor : game.getControllableSuccessors(state)) {
                gameMts.addRequired(state, "controllable", successor);
            }
            for (Long successor : game.getUncontrollableSuccessors(state)) {
                gameMts.addRequired(state, "uncontrollable", successor);
            }
        }

        return MTSToAutomataConverter.getInstance().convert(gameMts, "GRGame", false, true);
    }

    private static CompactState buildPerfectInfoGRGameCompactState(MTS<Set<Long>, String> perfectInfoGame) {
        GenericMTSToLongStringMTSConverter<Set<Long>, String> transformer =
                new GenericMTSToLongStringMTSConverter<Set<Long>, String>();
        MTS<Long, String> plainGame = transformer.transform(perfectInfoGame);
        return MTSToAutomataConverter.getInstance().convert(plainGame, "GRGame(perfect-info)", false, true);
    }

    private static void addGRGameDrawMachine(
            UpdatingControllerCompositeState uccs,
            CompactState compactGRGame,
            LTSOutput output) {
        if (compactGRGame == null) {
            return;
        }

        Vector<CompactState> machines = uccs.getMachines();
        if (machines == null) {
            machines = new Vector<CompactState>();
            uccs.setMachines(machines);
        }
        machines.add(compactGRGame);
        output.outln("GR game graph added to Draw tab as " + compactGRGame.name + ".");
    }

}
