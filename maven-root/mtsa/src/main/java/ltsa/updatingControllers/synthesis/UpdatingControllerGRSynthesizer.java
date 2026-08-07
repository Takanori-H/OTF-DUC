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
import ltsa.updatingControllers.EvaluationTransitionCounter;
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

    public static final String TRADITIONAL_GR1_TIMING_SECTION = "Traditional DUC GR1 時間内訳";
    public static final String STEPWISE_DELAYED_GR1_TIMING_SECTION = "Stepwise Delayed DUC GR1 時間内訳";

    private static final String TRADITIONAL_GR1_HEARTBEAT_PREFIX = "TRADITIONAL_GR1";
    private static final String STEPWISE_DELAYED_GR1_HEARTBEAT_PREFIX = "STEPWISE_DELAYED_GR1";

    public static void synthesizeGR(CompactState compactSafetyEnv, UpdatingControllerCompositeState uccs, MTS<Long, String> safetyEnv, LTSOutput output) {
        synthesizeGR(
                compactSafetyEnv,
                uccs,
                safetyEnv,
                output,
                TRADITIONAL_GR1_TIMING_SECTION,
                TRADITIONAL_GR1_HEARTBEAT_PREFIX);
    }

    public static void synthesizeStepwiseDelayedGR(CompactState compactSafetyEnv, UpdatingControllerCompositeState uccs, MTS<Long, String> safetyEnv, LTSOutput output) {
        synthesizeGR(
                compactSafetyEnv,
                uccs,
                safetyEnv,
                output,
                STEPWISE_DELAYED_GR1_TIMING_SECTION,
                STEPWISE_DELAYED_GR1_HEARTBEAT_PREFIX);
    }

    private static void synthesizeGR(
            CompactState compactSafetyEnv,
            UpdatingControllerCompositeState uccs,
            MTS<Long, String> safetyEnv,
            LTSOutput output,
            String timingSection,
            String heartbeatPrefix) {
        // Safety/SBP may remove every transition carrying a declared
        // controllable action.  Keep those labels in the GR input interface so
        // validation and the generated controller still know that the action
        // is controllable.  Only the declared controllable set is added here:
        // copying the whole source alphabet would also copy the reserved tau
        // label and can turn it into a real DontDoTwice self-loop upstream.
        safetyEnv.addActions(uccs.getUpdateGRGoal().getControllableActions());
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                compactSafetyEnv.isNonDeterministic() ? "nondeterministic" : "deterministic",
                "input_alphabet_ready");
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "synthesizeGR 全体時間");
        output.outln("Synthezising GR");
        if (compactSafetyEnv.isNonDeterministic()){
            output.outln("Environment after safety is non-deterministic");
            output.outln("Solving a non-deterministic controller synthesis");
            nonBlockingGR(uccs, output, safetyEnv, timingSection, heartbeatPrefix);
        } else {
            output.outln("Environment after safety is deterministic");
            output.outln("Solving a deterministic controller synthesis");
            synthesizeGRDeterministic(uccs, output, safetyEnv, timingSection, heartbeatPrefix);
        }
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                compactSafetyEnv.isNonDeterministic() ? "nondeterministic" : "deterministic",
                "branch_returned");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "synthesizeGR 全体時間");

    }

    private static void nonBlockingGR(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            MTS<Long, String> safetyEnv,
            String timingSection,
            String heartbeatPrefix) {

        KnowledgeGRGame<Long, String> game;
        GRGoal<Set<Long>> grGoal;
        MTS<Set<Long>, String> perfectInfoGame;
        SubsetConstructionBuilder<Long, String> subsetConstructionBuilder;

        FluentUtils fluentUtils = FluentUtils.getInstance();

        long subsetStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_SUBSET_CONSTRUCTION");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "非決定環境の subset construction 時間");
        subsetConstructionBuilder = new SubsetConstructionBuilder<Long, String>(safetyEnv);
        perfectInfoGame = subsetConstructionBuilder.build();
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "nondeterministic",
                "subset_game_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "非決定環境の subset construction 時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "非決定環境の subset construction 時間",
                monotonicMillis() - subsetStart);
        long perfectInfoCountStart = monotonicMillis();
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                timingSection,
                "Perfect-info game after subset construction",
                perfectInfoGame.getStates().size(),
                EvaluationTransitionCounter.countRequiredAndMaybe(perfectInfoGame),
                monotonicMillis() - perfectInfoCountStart,
                "非決定 safety environment から subset construction で作った perfect-info game。");
        if (uccs.isShowGRGameInDraw()) {
            addGRGameDrawMachine(uccs, buildPerfectInfoGRGameCompactState(perfectInfoGame), output);
        }

        FluentStateValuation<Set<Long>> valuation = fluentUtils.buildValuation(perfectInfoGame, uccs.getUpdateGRGoal().getFluents());
        long goalBuildStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_GOAL_BUILD");
        DUCHeartbeat.setCounter("perfectInfoStates", perfectInfoGame.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "GR goal 構築時間");
        Assumptions<Set<Long>> assumptions = formulasToAssumptions(perfectInfoGame.getStates(), uccs.getUpdateGRGoal().getAssumptions(), valuation);
        Guarantees<Set<Long>> guarantees = formulasToGuarantees(perfectInfoGame.getStates(), uccs.getUpdateGRGoal().getGuarantees(), valuation);
        Set<Set<Long>> faults = new HashSet<Set<Long>>();

        grGoal = new GRGoal<Set<Long>>(guarantees, assumptions, faults, uccs.getUpdateGRGoal().isPermissive());
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "nondeterministic",
                "goal_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "GR goal 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "GR goal 構築時間",
                monotonicMillis() - goalBuildStart);
        Set<Set<Long>> initialStates = new HashSet<Set<Long>>();
        Set<Long> initialState = new HashSet<Long>();
        initialState.add(safetyEnv.getInitialState());
        initialStates.add(initialState);

        long gameBuildStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_GAME_BUILD");
        DUCHeartbeat.setCounter("perfectInfoStates", perfectInfoGame.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "Knowledge GR game 構築時間");
        game = new KnowledgeGRGame<Long, String>(initialStates, safetyEnv, perfectInfoGame, uccs.getUpdateGRGoal().getControllableActions(), grGoal);
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "nondeterministic",
                "game_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "Knowledge GR game 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "Knowledge GR game 構築時間",
                monotonicMillis() - gameBuildStart);
        recordGrGoalCounts(timingSection, grGoal);
        recordGrGameStateSpace(timingSection, "Knowledge GR game", game);

        long rankSystemStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_RANK_SYSTEM_BUILD");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "Rank system 構築時間");
        GRRankSystem<Set<Long>> system = new GRRankSystem<Set<Long>>(game.getStates(), grGoal.getGuarantees(), grGoal.getAssumptions(), grGoal.getFailures());
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "nondeterministic",
                "rank_system_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "Rank system 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "Rank system 構築時間",
                monotonicMillis() - rankSystemStart);

        KnowledgeGRGameSolver<Long, String> solver = new KnowledgeGRGameSolver<Long, String>(game, system);
        long solveStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_WINNING_REGION");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "Winning region 計算時間");
        solver.solveGame();
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "nondeterministic",
                "winning_region_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "Winning region 計算時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "Winning region 計算時間",
                monotonicMillis() - solveStart);

        if (solver.isWinning(perfectInfoGame.getInitialState())) {
            long strategyBuildStart = monotonicMillis();
            DUCHeartbeat.beginPhase(heartbeatPrefix + "_STRATEGY_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    timingSection,
                    "Strategy 構築時間");
            Strategy<Set<Long>, Integer> strategy = solver.buildStrategy();
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "strategy_ready");
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    timingSection,
                    "Strategy 構築時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    timingSection,
                    "Strategy 構築時間",
                    monotonicMillis() - strategyBuildStart);

            Set<Pair<StrategyState<Set<Long>, Integer>, StrategyState<Set<Long>, Integer>>> worseRank = solver.getWorseRank();
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "controller_mts_build_before");
            long strategyToMtsStart = monotonicMillis();
            DUCHeartbeat.beginPhase(heartbeatPrefix + "_CONTROLLER_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    timingSection,
                    "Strategy から controller MTS を構築する時間");
            MTS<StrategyState<Set<Long>, Integer>, String> result = GameStrategyToMTSBuilder.getInstance().buildMTSFrom(perfectInfoGame, strategy, worseRank);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "controller_mts_build_after");

            result.removeUnreachableStates();
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "remove_unreachable_after");
            LTSAdapter<StrategyState<Set<Long>, Integer>, String> ltsAdapter = new LTSAdapter<StrategyState<Set<Long>,Integer>, String>(result, MTS.TransitionType.POSSIBLE);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "lts_adapter_ready");
            MTS<StrategyState<Set<Long>, Integer>, String> synthesised  = new MTSAdapter<StrategyState<Set<Long>,Integer>, String>(ltsAdapter);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "mts_adapter_ready");
            MTS<Long, String> plainController = new GenericMTSToLongStringMTSConverter<StrategyState<Set<Long>, Integer>, String>().transform(synthesised);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "plain_controller_ready");
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    timingSection,
                    "Strategy から controller MTS を構築する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    timingSection,
                    "Strategy から controller MTS を構築する時間",
                    monotonicMillis() - strategyToMtsStart);

            output.outln("Controller [" + plainController.getStates().size() + "] generated successfully.");
            long compactConvertStart = monotonicMillis();
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    timingSection,
                    "Controller を CompactState に変換する時間");
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "compact_controller_convert_before");
            CompactState compactState = MTSToAutomataConverter.getInstance().convert(plainController, uccs.getName(), false, true);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "compact_controller_convert_after");
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    timingSection,
                    "Controller を CompactState に変換する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    timingSection,
                    "Controller を CompactState に変換する時間",
                    monotonicMillis() - compactConvertStart);
            uccs.setComposition(compactState);
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "nondeterministic",
                    "composition_published");
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

    private static void synthesizeGRDeterministic(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            MTS<Long, String> safetyEnv,
            String timingSection,
            String heartbeatPrefix) {
        GRGame<Long> game;

        long gameBuildStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_GAME_BUILD");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "GR game 構築時間");
        game = new GRGameBuilder<Long, String>().buildGRGameFrom(safetyEnv,uccs.getUpdateGRGoal());
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "deterministic",
                "game_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "GR game 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "GR game 構築時間",
                monotonicMillis() - gameBuildStart);
        recordGrGoalCounts(timingSection, game.getGoal());
        recordGrGameStateSpace(timingSection, "GR game", game);
        if (uccs.isShowGRGameInDraw()) {
            addGRGameDrawMachine(uccs, buildDeterministicGRGameCompactState(game), output);
        }
        long rankSystemStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_RANK_SYSTEM_BUILD");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "Rank system 構築時間");
        GRRankSystem<Long> system = new GRRankSystem<Long>(game.getStates(),game.getGoal().getGuarantees(),
                game.getGoal().getAssumptions(), game.getGoal().getFailures());
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "deterministic",
                "rank_system_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "Rank system 構築時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "Rank system 構築時間",
                monotonicMillis() - rankSystemStart);
        PerfectInfoGRGameSolver<Long> solver = new PerfectInfoGRGameSolver<Long>(game, system);
        long solveStart = monotonicMillis();
        DUCHeartbeat.beginPhase(heartbeatPrefix + "_WINNING_REGION");
        DUCHeartbeat.setCounter("grGameStates", game.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                timingSection,
                "Winning region 計算時間");
        solver.solveGame();
        recordGrPhaseMemoryCheckpoint(
                heartbeatPrefix,
                "deterministic",
                "winning_region_ready");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                timingSection,
                "Winning region 計算時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                timingSection,
                "Winning region 計算時間",
                monotonicMillis() - solveStart);

        if (solver.isWinning(safetyEnv.getInitialState())) {
            long strategyBuildStart = monotonicMillis();
            DUCHeartbeat.beginPhase(heartbeatPrefix + "_STRATEGY_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    timingSection,
                    "Strategy 構築時間");
            Strategy<Long, Integer> strategy = solver.buildStrategy();
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "deterministic",
                    "strategy_ready");
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    timingSection,
                    "Strategy 構築時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    timingSection,
                    "Strategy 構築時間",
                    monotonicMillis() - strategyBuildStart);
            GRGameSolver<Long> grSolver = (GRGameSolver<Long>) solver;
            Set<Pair<StrategyState<Long, Integer>, StrategyState<Long, Integer>>> worseRank = grSolver.getWorseRank();
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "deterministic",
                    "controller_mts_build_before");
            long strategyToMtsStart = monotonicMillis();
            DUCHeartbeat.beginPhase(heartbeatPrefix + "_CONTROLLER_BUILD");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    timingSection,
                    "Strategy から controller MTS を構築する時間");
            MTS<StrategyState<Long, Integer>, String> result = GameStrategyToMTSBuilder.getInstance().buildMTSFrom(safetyEnv, strategy, worseRank, uccs.getUpdateGRGoal().getLazyness());
            recordGrPhaseMemoryCheckpoint(
                    heartbeatPrefix,
                    "deterministic",
                    "controller_mts_build_after");
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    timingSection,
                    "Strategy から controller MTS を構築する時間");
            UpdatingControllerEvaluationRecorder.recordTime(
                    timingSection,
                    "Strategy から controller MTS を構築する時間",
                    monotonicMillis() - strategyToMtsStart);

            if (result == null) {
                output.outln("There is no controller for model " + uccs.name + " for the given setting.");
                uccs.setComposition(null);
            } else {
                long plainTransformStart = monotonicMillis();
                UpdatingControllerEvaluationRecorder.beginFailureTimer(
                        timingSection,
                        "StrategyState controller を Long/String MTS に変換する時間");
                recordGrPhaseMemoryCheckpoint(
                        heartbeatPrefix,
                        "deterministic",
                        "plain_controller_transform_before");
                GenericMTSToLongStringMTSConverter<StrategyState<Long, Integer>, String> transformer = new GenericMTSToLongStringMTSConverter<StrategyState<Long, Integer>, String>();
                MTS<Long, String> plainController = transformer.transform(result);
                recordGrPhaseMemoryCheckpoint(
                        heartbeatPrefix,
                        "deterministic",
                        "plain_controller_ready");
                UpdatingControllerEvaluationRecorder.endFailureTimer(
                        timingSection,
                        "StrategyState controller を Long/String MTS に変換する時間");
                UpdatingControllerEvaluationRecorder.recordTime(
                        timingSection,
                        "StrategyState controller を Long/String MTS に変換する時間",
                        monotonicMillis() - plainTransformStart);

                output.outln("Controller [" + plainController.getStates().size() + "] generated successfully.");
                long compactConvertStart = monotonicMillis();
                UpdatingControllerEvaluationRecorder.beginFailureTimer(
                        timingSection,
                        "Controller を CompactState に変換する時間");
                recordGrPhaseMemoryCheckpoint(
                        heartbeatPrefix,
                        "deterministic",
                        "compact_controller_convert_before");
                CompactState convert = MTSToAutomataConverter.getInstance().convert(plainController, uccs.getName(), true);
                recordGrPhaseMemoryCheckpoint(
                        heartbeatPrefix,
                        "deterministic",
                        "compact_controller_convert_after");
                UpdatingControllerEvaluationRecorder.endFailureTimer(
                        timingSection,
                        "Controller を CompactState に変換する時間");
                UpdatingControllerEvaluationRecorder.recordTime(
                        timingSection,
                        "Controller を CompactState に変換する時間",
                        monotonicMillis() - compactConvertStart);
                uccs.setComposition(convert);
                recordGrPhaseMemoryCheckpoint(
                        heartbeatPrefix,
                        "deterministic",
                        "composition_published");
            }
        } else {
            output.outln("There is no controller for model " + uccs.name + " for the given setting.");
            uccs.setComposition(null);
        }

    }

    private static void recordGrPhaseMemoryCheckpoint(
            String heartbeatPrefix,
            String branch,
            String boundary) {
        if (!UpdatingControllerEvaluationRecorder.isPhaseMemoryDiagnosticsEnabled()) {
            return;
        }
        UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                "gr." + heartbeatPrefix + "." + branch + "." + boundary);
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

    private static <S> void recordGrGoalCounts(String timingSection, GRGoal<S> goal) {
        UpdatingControllerEvaluationRecorder.recordCount(
                timingSection,
                "GR assumption 数",
                goal.getAssumptionsQuantity(),
                "assumptions");
        UpdatingControllerEvaluationRecorder.recordCount(
                timingSection,
                "GR guarantee 数",
                goal.getGuaranteesQuantity(),
                "guarantees");
        UpdatingControllerEvaluationRecorder.recordCount(
                timingSection,
                "GR failure state 数",
                goal.getFailures().size(),
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                timingSection,
                "GR permissive strategy 有効",
                goal.buildPermissiveStrategy() ? 1 : 0,
                "boolean");
    }

    private static <S> void recordGrGameStateSpace(String timingSection, String label, Game<S> game) {
        long countStart = monotonicMillis();
        long controllableSuccessors = 0;
        long uncontrollableSuccessors = 0;
        for (S state : game.getStates()) {
            controllableSuccessors += game.getControllableSuccessors(state).size();
            uncontrollableSuccessors += game.getUncontrollableSuccessors(state).size();
        }
        long countTime = monotonicMillis() - countStart;
        UpdatingControllerEvaluationRecorder.recordGrGameStateSpace(
                timingSection,
                label,
                game.getStates().size(),
                controllableSuccessors,
                uncontrollableSuccessors,
                countTime);
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

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

}
