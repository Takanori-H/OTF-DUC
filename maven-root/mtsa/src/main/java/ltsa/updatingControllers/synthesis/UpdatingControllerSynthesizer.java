package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.condition.FluentPropositionalVariable;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.condition.AndFormula;
import MTSSynthesis.ar.dc.uba.model.condition.NotFormula;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSAdapter;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSAdapter;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedLTSAdapter;
import MTSTools.ac.ic.doc.mtstools.model.impl.UpdatingEnvironment;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.ControllerGoalDefinition;
import ltsa.control.util.ControllerUtils;
import ltsa.lts.CompactState;
import ltsa.lts.Diagnostics;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import ltsa.lts.LTSOutput;
import ltsa.lts.Symbol;
import ltsa.lts.chart.util.FormulaUtils;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.FormulaFactory;
import ltsa.lts.ltl.FormulaSyntax;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.DUCHeartbeat;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ResultStatus;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisDUC;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisFineGrainedDUC;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisSelectiveFineGrainedDUC;
import ltsa.lts.EventState;

import java.util.*;

/**
 * Created by lnahabedian on 10/06/15.
 */
/**
 * 更新コントローラ(Updating Controller)を合成するためのメインクラス。
 * 従来のDUCS (Dynamic Update Controller Synthesis) と
 * 提案手法 OTF-DUC (On-The-Fly DUC) の両方のエントリポイントを提供します。
 */
public class UpdatingControllerSynthesizer {

    /**
     * コントローラ生成のメインメソッド。
     * 設定(isOTFフラグ)に基づいて、OTF手法か従来手法かを分岐します。
     * * @param uccs 更新コントローラの構成情報を持つCompositeState
     * @param output ログ出力用オブジェクト
     */
	public static void generateController(UpdatingControllerCompositeState uccs, LTSOutput output) {

        //評価実験用
        long generateControllerStart = System.currentTimeMillis();
        long DUCTime = 0;
        long UpdatingEnvironmentGenerateTime = 0;
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "UpdatingControllerSynthesizer",
                "generateController の全体実行時間");

		// set environment
		MTS<Long, String> oldC = uccs.getOldController();
        DUCHeartbeat.start(uccs.isOTF() ? "OTF-DUC" : "Traditional-DUC", uccs.getName());
        String heartbeatStatus = "completed";

        try {
        if(uccs.isOTF())
        {
            UpdatingControllerEvaluationRecorder.setMode("OTF-DUC");
            DUCHeartbeat.beginPhase("OTF_GENERATE_DUC");
            // ★確認用ログ出力
            // --- OTF-DUC (提案手法) の実行 ---
            output.outln("=========================================");
            output.outln("Mode: On-the-fly DUC");
            output.outln("=========================================");

            //評価実験用
            long DUCStart = System.currentTimeMillis();
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");
            UpdatingControllerEvaluationRecorder.beginCountScope(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");

            // OTF-DUCの実行メインロジック呼び出し
            try {
                generateDUC(uccs, output);
            } finally {
                UpdatingControllerEvaluationRecorder.endCountScope(
                        "UpdatingControllerSynthesizer",
                        "Traditional solveControlProblem / OTF generateDUC 実行時間");
            }

            //評価実験用
            DUCTime = System.currentTimeMillis() - DUCStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");
        }
        else
        {
            UpdatingControllerEvaluationRecorder.setMode("Traditional DUC");
            DUCHeartbeat.beginPhase("TRADITIONAL_BUILD_UPDATE_ENV");
            // --- 従来手法 (DUCS) の実行 ---
            // 環境モデル全体(UpdatingEnvironment)を構築してから合成を行う
            String updateEventMode = " (legacy update events)";
            if (uccs.isFineGrained()) {
                updateEventMode = uccs.getUpdateProtocolSpec() != null && uccs.getUpdateProtocolSpec().isSelective()
                        ? " (selective fine-grained update events)"
                        : " (fine-grained update events)";
            }
            output.outln("=========================================");
            output.outln("Mode: Traditional DUC" + updateEventMode);
            output.outln("=========================================");

            //評価実験用
            long UpdatingEnvironmentGenerateStart = System.currentTimeMillis();
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional DUC E_u 構築時間");

            MTS<Long, String> mapping = uccs.getMapping();

            //old controllerとmapping environmentからゲームを分析するための空間を作る
            UpdatingEnvironment updEnv;
            if (uccs.isFineGrained()) {
                FineGrainedUpdatingEnvironmentGenerator updEnvGenerator =
                        new FineGrainedUpdatingEnvironmentGenerator(oldC, mapping, uccs.getUpdateProtocolSpec());
                updEnvGenerator.generateEnvironment();
                updEnv = updEnvGenerator.getUpdEnv();
            } else {
                UpdatingEnvironmentGenerator updEnvGenerator = new UpdatingEnvironmentGenerator(oldC, mapping);
                updEnvGenerator.generateEnvironment();
                updEnv = updEnvGenerator.getUpdEnv();
            }
            TraditionalDUCDebugLogger.logOldController(output, oldC);

            UpdatingEnvironmentGenerateTime = System.currentTimeMillis() - UpdatingEnvironmentGenerateStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional DUC E_u 構築時間");

            //評価実験用
            long DUCStart = System.currentTimeMillis();
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");
            UpdatingControllerEvaluationRecorder.beginCountScope(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");

            try {
                solveControlProblem(uccs, updEnv, output);
            } finally {
                UpdatingControllerEvaluationRecorder.endCountScope(
                        "UpdatingControllerSynthesizer",
                        "Traditional solveControlProblem / OTF generateDUC 実行時間");
            }

            //評価実験用
            DUCTime = System.currentTimeMillis() - DUCStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllerSynthesizer",
                    "Traditional solveControlProblem / OTF generateDUC 実行時間");
        }

        //評価実験用
        long generateControllerTime = System.currentTimeMillis() - generateControllerStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "UpdatingControllerSynthesizer",
                "generateController の全体実行時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllerSynthesizer",
                "generateController の全体実行時間",
                generateControllerTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllerSynthesizer",
                "Traditional solveControlProblem / OTF generateDUC 実行時間",
                DUCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllerSynthesizer",
                "Traditional DUC E_u 構築時間",
                UpdatingEnvironmentGenerateTime);
        } catch (RuntimeException | Error e) {
            heartbeatStatus = "failed:" + e.getClass().getSimpleName();
            throw e;
        } finally {
            DUCHeartbeat.stop(heartbeatStatus);
        }

	}

    private static void solveControlProblem(
            UpdatingControllerCompositeState uccs, UpdatingEnvironment updEnv, LTSOutput output) {
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "solveControlProblem 全体時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "solveControlProblem (Traditional DUC)",
                "solveControlProblem 全体時間");
        Set<String> controllableActions = uccs.getControllableActions();

        //UpdatingEnvironmentからMTSへ変換
        DUCHeartbeat.beginPhase("TRADITIONAL_CONVERT_EU_MTS");
        long convertEuStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "UpdatingEnvironment から E_u MTS への変換時間");
        MTS<Long, String> E_u = ControllerUtils.UpdateEnvironment2MTS(updEnv);
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "UpdatingEnvironment から E_u MTS への変換時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "UpdatingEnvironment から E_u MTS への変換時間",
                System.currentTimeMillis() - convertEuStart);
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional E_u MTS 変換後");

        // ▼▼▼ 評価実験用: [1] Updating Environment 生成直後 ▼▼▼
        long euCountStart = 0;
        int euStates = 0;
        int euTrans = 0;
        long euCountTime = 0;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            euCountStart = System.currentTimeMillis();
            euStates = E_u.getStates().size();
            euTrans = countTransitions(E_u);
            euCountTime = System.currentTimeMillis() - euCountStart;
        }
        TraditionalDUCDebugLogger.logStage(
                output,
                "[1. E_u] Updating Environment",
                E_u,
                uccs.getOldController().getStates());
        // ▲▲▲ 追加ここまで ▲▲▲

        //評価実験用
        long extractFluentStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "Old Safety と New Safety から Fluent を抽出する時間");

        //GoalからFluentを抽出
        SafetyFormulaExtractionResult safetyFormulasAndFluents =
                getSafetyFormulas(uccs.getUpdateSafetyGoals(), uccs.getUpdateProtocolSpec(), output); // plain safety(G_u)
        List<Formula> safetyFormulas = safetyFormulasAndFluents.formulas;
        Set<Fluent> goalFluents = safetyFormulasAndFluents.fluents;

        fillTerminatingActions(E_u.getActions(), goalFluents); // set the action events fluents terminating with any action
        int metaEnvironmentFluentCount = goalFluents.size();

        //評価実験用
        long extractFluentTime = System.currentTimeMillis() - extractFluentStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "Old Safety と New Safety から Fluent を抽出する時間");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模",
                "Traditional DUC old safety fluent 数",
                safetyFormulasAndFluents.oldSafetyFluentCount,
                "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模",
                "Traditional DUC new safety fluent 数",
                safetyFormulasAndFluents.newSafetyFluentCount,
                "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模",
                "Traditional DUC old/new safety fluent 数（重複排除後）",
                safetyFormulasAndFluents.oldNewSafetyFluentCount,
                "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模",
                "Traditional DUC transition requirement fluent 数",
                safetyFormulasAndFluents.transitionRequirementFluentCount,
                "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模",
                "Traditional DUC meta env fluent 数（重複排除後）",
                metaEnvironmentFluentCount,
                "個");
        long buildMetaEnvStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_BUILD_META_ENV");
        DUCHeartbeat.setCounter("euStates", E_u.getStates().size());
        DUCHeartbeat.setCounter("metaFluents", metaEnvironmentFluentCount);
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "Fluent とベース環境を並列合成した metaEnv 構築時間");

        //Fluentをオートマトンに変換し，ベース環境と並列合成
        MTS<Long, String> metaEnvironment = ControllerUtils.removeTopStates(E_u, goalFluents);

        //評価実験用
        long buildMetaEnvTime = System.currentTimeMillis() - buildMetaEnvStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "Fluent とベース環境を並列合成した metaEnv 構築時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional metaEnv 構築後");

        // ▼▼▼ 評価実験用: [2] Meta Environment 生成直後 (★最大ピーク★) ▼▼▼
        long metaCountStart = 0;
        int metaStates = 0;
        int metaTrans = 0;
        long metaCountTime = 0;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            metaCountStart = System.currentTimeMillis();
            metaStates = metaEnvironment.getStates().size();
            metaTrans = countTransitions(metaEnvironment);
            metaCountTime = System.currentTimeMillis() - metaCountStart;
        }
        TraditionalDUCDebugLogger.logStage(
                output,
                "[2. Meta] E_u || Safety Fluents",
                metaEnvironment);
        // ▲▲▲ 追加ここまで ▲▲▲

		output.outln("Environment states:"+ metaEnvironment.getStates().size());
        output.outln("Solving safety goals for the controller synthesis");

        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "Traditional DUC 最大状態数と遷移数",
                "[1. E_u] (Old Controller || Mapping Environment)",
                euStates,
                euTrans,
                euCountTime,
                "旧コントローラと Mapping Environment を並列合成した、従来 DUC の基本更新環境。");
        UpdatePhaseEvaluator.recordMtsUpdatePhaseStateSpace(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL,
                "[1. E_u] (Old Controller || Mapping Environment)",
                E_u);
        UpdatePhaseEvaluator.recordMtsUpdateEventTransitionCounts(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_EVENTS,
                "[1. E_u] (Old Controller || Mapping Environment)",
                E_u);
        UpdatePhaseEvaluator.recordMtsUpdatePhaseTransitionAnalysis(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_DETAILS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_FLOW,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_COMPLETION_PATH,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_ACTIONS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NEXT_UPDATE_EVENT_DISTANCE,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PROGRESS_FREE_CYCLES,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_ENABLED_UPDATE_EVENTS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_ORDER_PATTERNS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_RUN_LENGTH,
                "[1. E_u] (Old Controller || Mapping Environment)",
                E_u,
                controllableActions);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "Traditional DUC 最大状態数と遷移数",
                "[2. Meta] Meta Environment (PEAK)",
                metaStates,
                metaTrans,
                metaCountTime,
                "E_u に safety 用 Fluent を組み込んだ環境。状態空間が最大になりやすい段階。");
        UpdatePhaseEvaluator.recordMtsUpdatePhaseStateSpace(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL,
                "[2. Meta] Meta Environment (PEAK)",
                metaEnvironment);
        UpdatePhaseEvaluator.recordMtsUpdateEventTransitionCounts(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_EVENTS,
                "[2. Meta] Meta Environment (PEAK)",
                metaEnvironment);
        UpdatePhaseEvaluator.recordMtsUpdatePhaseTransitionAnalysis(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_DETAILS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_FLOW,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_COMPLETION_PATH,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_ACTIONS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NEXT_UPDATE_EVENT_DISTANCE,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PROGRESS_FREE_CYCLES,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_ENABLED_UPDATE_EVENTS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_ORDER_PATTERNS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_RUN_LENGTH,
                "[2. Meta] Meta Environment (PEAK)",
                metaEnvironment,
                controllableActions);

        //評価実験用
        long buildSafetyEnvStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_SAFETY_ENV_BUILD");
        DUCHeartbeat.setCounter("metaStates", metaEnvironment.getStates().size());
        DUCHeartbeat.setCounter("safetyFormulas", safetyFormulas.size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "metaEnv からエラーを枝刈りして safetyEnv を構築する時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "solveControlProblem (Traditional DUC)",
                "metaEnv からエラーを枝刈りして safetyEnv を構築する時間");

        //論理式（Formula）の評価による状態空間の前処理（Safety違反状態の無効化）
        MTS<Long, String> safetyEnv;
        try {
            if (uccs.isFineGrained()) {
                safetyEnv = FineGrainedUpdatingControllerSafetySynthesizer.synthesizeSafety(
                        metaEnvironment,
                        goalFluents,
                        safetyFormulas,
                        uccs.getUpdateGRGoal().getControllableActions(),
                        uccs.getUpdateProtocolSpec(),
                        output);
            } else {
                safetyEnv = UpdatingControllerSafetySynthesizer.synthesizeSafety(metaEnvironment, goalFluents, safetyFormulas, uccs.getUpdateGRGoal().getControllableActions(), output);
            }
        } finally {
            UpdatingControllerEvaluationRecorder.endCountScope(
                    "solveControlProblem (Traditional DUC)",
                    "metaEnv からエラーを枝刈りして safetyEnv を構築する時間");
        }

        //評価実験用
        long buildSafetyEnvTime = System.currentTimeMillis() - buildSafetyEnvStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "metaEnv からエラーを枝刈りして safetyEnv を構築する時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional safetyEnv 構築後");

        // ▼▼▼ 評価実験用: [4] 最終 Safety Environment 生成直後 ▼▼▼
        long safeCountStart = 0;
        int safeStates = 0;
        int safeTrans = 0;
        long safeCountTime = 0;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            safeCountStart = System.currentTimeMillis();
            safeStates = safetyEnv.getStates().size();
            safeTrans = countTransitions(safetyEnv);
            safeCountTime = System.currentTimeMillis() - safeCountStart;
        }
        TraditionalDUCDebugLogger.logStage(
                output,
                "[4. Final] Safety Environment",
                safetyEnv);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "Traditional DUC 最大状態数と遷移数",
                "[4. Final] Safety Environment",
                safeStates,
                safeTrans,
                safeCountTime,
                "Pruned に DontDoTwice 制約を合成した、GR1 合成に渡す最終 safety 環境。");
        UpdatePhaseEvaluator.recordMtsUpdatePhaseStateSpace(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL,
                "[4. Final] Safety Environment",
                safetyEnv);
        UpdatePhaseEvaluator.recordMtsUpdateEventTransitionCounts(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_EVENTS,
                "[4. Final] Safety Environment",
                safetyEnv);
        UpdatePhaseEvaluator.recordMtsUpdatePhaseTransitionAnalysis(
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_DETAILS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PHASE_FLOW,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_COMPLETION_PATH,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_ACTIONS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NEXT_UPDATE_EVENT_DISTANCE,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_PROGRESS_FREE_CYCLES,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_ENABLED_UPDATE_EVENTS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_UPDATE_ORDER_PATTERNS,
                UpdatePhaseEvaluator.SECTION_TRADITIONAL_NORMAL_RUN_LENGTH,
                "[4. Final] Safety Environment",
                safetyEnv,
                controllableActions);
        // ▲▲▲ 追加ここまで ▲▲▲

        output.outln("Environment states after safety: "+ safetyEnv.getStates().size());

        uccs.setUpdateEnvironment(safetyEnv);

        //MTSからCompactStateへ型変換
        long compactSafetyEnvStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv から CompactState への変換時間");
        CompactState compactSafetyEnv = MTSToAutomataConverter.getInstance().convert(safetyEnv, "E_u||G(safety)", false, true);
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv から CompactState への変換時間");
        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv から CompactState への変換時間",
                System.currentTimeMillis() - compactSafetyEnvStart);
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional safetyEnv CompactState 変換後");
//		CompactState compactMetaEnv = MTSToAutomataConverter.getInstance().convert(metaEnvironment, "meta E_u", false);
//		CompactState compactEnv = MTSToAutomataConverter.getInstance().convert(E_u, "E_u", false);

        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(compactSafetyEnv);
//		machines.add(compactMetaEnv);
//		machines.add(compactEnv);

        uccs.setMachines(machines);

        //評価実験用
        long synthesizeGRStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("TRADITIONAL_GR1_SYNTHESIS");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            DUCHeartbeat.setCounter("safetyTransitions", safeTrans);
        }
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv を GR1 で解く時間");

        UpdatingControllerGRSynthesizer.synthesizeGR(compactSafetyEnv, uccs, safetyEnv, output);
        TraditionalDUCDebugLogger.logCompactState(
                output,
                "[5. Output Update Controller] GR1 result before removeOldTransitions",
                uccs.getComposition());

        //評価実験用
        long synthesizeGRTime = System.currentTimeMillis() - synthesizeGRStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv を GR1 で解く時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional GR1 合成後");

//        if (uccs.getComposition() == null){
//            output.outln("Running in debug mode");
//            machines.clear();
//            machines.add(compactEnv); // 0
//            uccs.setComposition(machines.get(0));
//
//        }

        UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.clear();

        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "Old Safety と New Safety から Fluent を抽出する時間",
                extractFluentTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "Fluent とベース環境を並列合成した metaEnv 構築時間",
                buildMetaEnvTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "metaEnv からエラーを枝刈りして safetyEnv を構築する時間",
                buildSafetyEnvTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "solveControlProblem (Traditional DUC)",
                "safetyEnv を GR1 で解く時間",
                synthesizeGRTime);
        UpdatingControllerEvaluationRecorder.endCountScope(
                "solveControlProblem (Traditional DUC)",
                "solveControlProblem 全体時間");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "solveControlProblem (Traditional DUC)",
                "solveControlProblem 全体時間");
	}

    /**
     *  get the list of safety formulas from old and new and the set of propositions used in every formula
     *
     * @param newGoalDef
     * @param output
     * @return
     */
    private static SafetyFormulaExtractionResult getSafetyFormulas(
            ControllerGoalDefinition newGoalDef,
            LTSOutput output) {
        return getSafetyFormulas(newGoalDef, null, output);
    }

    private static SafetyFormulaExtractionResult getSafetyFormulas(
            ControllerGoalDefinition newGoalDef,
            UpdateProtocolSpec updateProtocolSpec,
            LTSOutput output) {
        Set<Fluent> safetyFluents = new HashSet<Fluent>();
        Set<Fluent> oldSafetyFluents = new HashSet<Fluent>();
        Set<Fluent> newSafetyFluents = new HashSet<Fluent>();
        Set<Fluent> transitionRequirementFluents = new HashSet<Fluent>();
        List<Formula> safetyFormulas = new ArrayList<Formula>();
        for (Symbol safetyDefinition : newGoalDef.getSafetyDefinitions()) {

            output.outln("Processing formula for update: " + safetyDefinition.getName());
            String safetyName = safetyDefinition.getName();
            Set<Fluent> formulaFluents = new HashSet<Fluent>();
            Formula safetyFormula;
            if (safetyName.endsWith(UpdateConstants.OLD_SUFFIX)) {
                safetyFormula = updateProtocolSpec == null
                        ? buildInternalOldSafetyFormula(safetyName, formulaFluents)
                        : buildFineGrainedOldSafetyFormula(safetyName, formulaFluents, updateProtocolSpec);
                oldSafetyFluents.addAll(formulaFluents);
            } else if (safetyName.endsWith(UpdateConstants.NEW_SUFFIX)) {
                safetyFormula = updateProtocolSpec == null
                        ? buildInternalNewSafetyFormula(safetyName, formulaFluents)
                        : buildFineGrainedNewSafetyFormula(safetyName, formulaFluents, updateProtocolSpec);
                newSafetyFluents.addAll(formulaFluents);
            } else {
                safetyFormula = adaptTransitionRequirementFormula(safetyName, formulaFluents);
                transitionRequirementFluents.addAll(formulaFluents);
            }
            safetyFormulas.add(safetyFormula);
            safetyFluents.addAll(formulaFluents);
        }
        return new SafetyFormulaExtractionResult(
                safetyFormulas,
                safetyFluents,
                oldSafetyFluents.size(),
                newSafetyFluents.size(),
                unionSize(oldSafetyFluents, newSafetyFluents),
                transitionRequirementFluents.size());
    }

    private static Formula buildInternalOldSafetyFormula(String generatedSafetyName, Set<Fluent> formulaFluents) {
        Formula originalViolationFormula = adaptOriginalSafetyFormula(
                stripSuffix(generatedSafetyName, UpdateConstants.OLD_SUFFIX),
                formulaFluents);
        formulaFluents.add(UpdatingControllersUtils.stopFluent);
        return new AndFormula(
                new NotFormula(new FluentPropositionalVariable(UpdatingControllersUtils.stopFluent)),
                originalViolationFormula);
    }

    private static Formula buildFineGrainedOldSafetyFormula(
            String generatedSafetyName,
            Set<Fluent> formulaFluents,
            UpdateProtocolSpec updateProtocolSpec) {
        String originalSafetyName = stripSuffix(generatedSafetyName, UpdateConstants.OLD_SUFFIX);
        Formula originalViolationFormula = adaptOriginalSafetyFormula(originalSafetyName, formulaFluents);
        String stopAction = updateProtocolSpec.getOldSafetyToStopAction().get(originalSafetyName);
        if (stopAction == null) {
            Diagnostics.fatal("No fine-grained stopOldSpec action generated for old safety: "
                    + originalSafetyName);
        }
        Fluent stopFluent = FineGrainedUpdatingControllersUtils.createPersistentActionFluent(stopAction);
        formulaFluents.add(stopFluent);
        return new AndFormula(
                new NotFormula(new FluentPropositionalVariable(stopFluent)),
                originalViolationFormula);
    }

    private static Formula buildInternalNewSafetyFormula(String generatedSafetyName, Set<Fluent> formulaFluents) {
        Formula originalViolationFormula = adaptOriginalSafetyFormula(
                stripSuffix(generatedSafetyName, UpdateConstants.NEW_SUFFIX),
                formulaFluents);
        formulaFluents.add(UpdatingControllersUtils.startFluent);
        return new AndFormula(
                new FluentPropositionalVariable(UpdatingControllersUtils.startFluent),
                originalViolationFormula);
    }

    private static Formula buildFineGrainedNewSafetyFormula(
            String generatedSafetyName,
            Set<Fluent> formulaFluents,
            UpdateProtocolSpec updateProtocolSpec) {
        String originalSafetyName = stripSuffix(generatedSafetyName, UpdateConstants.NEW_SUFFIX);
        Formula originalViolationFormula = adaptOriginalSafetyFormula(originalSafetyName, formulaFluents);
        String startAction = updateProtocolSpec.getNewSafetyToStartAction().get(originalSafetyName);
        if (startAction == null) {
            Diagnostics.fatal("No fine-grained startNewSpec action generated for new safety: "
                    + originalSafetyName);
        }
        Fluent startFluent = FineGrainedUpdatingControllersUtils.createPersistentActionFluent(startAction);
        formulaFluents.add(startFluent);
        return new AndFormula(
                new FluentPropositionalVariable(startFluent),
                originalViolationFormula);
    }

    private static Formula adaptOriginalSafetyFormula(String originalSafetyName, Set<Fluent> formulaFluents) {
        return adaptFormulaWithoutLeadingTemporalOperators(originalSafetyName, formulaFluents);
    }

    private static Formula adaptTransitionRequirementFormula(String requirementName, Set<Fluent> formulaFluents) {
        return adaptFormulaWithoutLeadingTemporalOperators(requirementName, formulaFluents);
    }

    private static Formula adaptFormulaWithoutLeadingTemporalOperators(String assertionName, Set<Fluent> formulaFluents) {
        AssertDefinition originalDef = AssertDefinition.getConstraint(assertionName);
        if (originalDef == null) {
            Diagnostics.fatal("Assertion not defined [" + assertionName + "].");
        }
        FormulaSyntax strippedSyntax = originalDef.getLTLFormula().removeLeftTemporalOperators();
        FormulaFactory factory = new FormulaFactory();
        Hashtable initParams = originalDef.getInitParams() != null ? originalDef.getInitParams() : new Hashtable();
        factory.setFormula(strippedSyntax.expand(factory, new Hashtable(), initParams));
        return FormulaUtils.adaptFormulaAndCreateFluents(factory.getFormula(), formulaFluents);
    }

    private static String stripSuffix(String value, String suffix) {
        return value.substring(0, value.length() - suffix.length());
    }

    private static int unionSize(Set<Fluent> left, Set<Fluent> right) {
        Set<Fluent> union = new HashSet<Fluent>();
        if (left != null) {
            union.addAll(left);
        }
        if (right != null) {
            union.addAll(right);
        }
        return union.size();
    }

    private static final class SafetyFormulaExtractionResult {
        private final List<Formula> formulas;
        private final Set<Fluent> fluents;
        private final int oldSafetyFluentCount;
        private final int newSafetyFluentCount;
        private final int oldNewSafetyFluentCount;
        private final int transitionRequirementFluentCount;

        private SafetyFormulaExtractionResult(
                List<Formula> formulas,
                Set<Fluent> fluents,
                int oldSafetyFluentCount,
                int newSafetyFluentCount,
                int oldNewSafetyFluentCount,
                int transitionRequirementFluentCount) {
            this.formulas = formulas;
            this.fluents = fluents;
            this.oldSafetyFluentCount = oldSafetyFluentCount;
            this.newSafetyFluentCount = newSafetyFluentCount;
            this.oldNewSafetyFluentCount = oldNewSafetyFluentCount;
            this.transitionRequirementFluentCount = transitionRequirementFluentCount;
        }
    }

    /**
     * Event actions fluents are define as <"actionName",{}, false>. This method fill
     * the empty set with all actions from the alphabet but not the action "actionName".
     *
     * @param actions
     * @param goalFluents
     */
    private static void fillTerminatingActions(Set<String> actions, Set<Fluent> goalFluents) {

        Set<Fluent> resultantFluents = new HashSet<Fluent>();
        for (Fluent fl : UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE){

            goalFluents.remove(fl);

            Set<MTSSynthesis.ar.dc.uba.model.language.Symbol> terminating = new HashSet<>();
            for (String action : actions){

                if (!action.equals(fl.getName().split("_a")[0])){
                    if (!action.equals("tau")) {
                        terminating.add(new SingleSymbol(action));
                    }
                }
            }

            Fluent resultantFl = new FluentImpl(fl.getName(), fl.getInitiatingActions(), terminating, fl.getInitialValue());
            resultantFluents.add(resultantFl);
            goalFluents.add(resultantFl);
        }

        UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.clear();
        UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.addAll(resultantFluents);
    }

    /**
     * OTF-DUC (提案手法) のコアロジック。
     * 必要なLTSリスト(Box List)を構築し、DCSを実行します。
     * * 手順:
     * 1. Box Listの構築 (Marking LTS, OC, Env, Safety...)
     * - OCは _old にリネーム
     * - NCは探索対象のリストには含めない
     * 2. State Mapping Tableの作成
     * - 更新完了後の接続先となるNCの状態を特定するためのマップを作成
     * 3. DCSの実行
     */
    private static void generateDUC(UpdatingControllerCompositeState uccs, LTSOutput output)
    {
        output.outln("Starting On-The-Fly Controller Synthesis (Box List & Mapping Table Strategy)...");
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "generateDUC 全体時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "generateDUC (OTF-DUC)",
                "generateDUC 全体時間");

        //評価実験用
        long boxListStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("OTF_BOXLIST_PREPARE");
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "boxList 準備時間");

        // ---------------------------------------------------------
        // 1. Build Box List (DCS探索用のLTSリスト構築)
        // 構成順序: [Marking(0), OldCont(1), Mapping..., OldSafe..., NewSafe..., TransReq...]
        // ※ New Controller はリストに含めない
        // ---------------------------------------------------------
        List<LTS<Long, String>> boxList = new ArrayList<>();
        AutomataToMTSConverter converter = AutomataToMTSConverter.getInstance();

        // リスト内のインデックス管理用変数
        int markingIndex = 0;
        int oldContIndex;
        int newContIndex = -1; // NCはリストに入れないため -1 とする
        int mappingStartIndex, mappingEndIndex;
        int oldSafeStartIndex, oldSafeEndIndex;
        int newSafeStartIndex, newSafeEndIndex;
        int transReqStartIndex, transReqEndIndex;
        // ★追加: Synthesis Machines (Monitor+Fluents) のインデックス
        int synthesisStartIndex, synthesisEndIndex;

        //評価実験用
        long createMarkingLTSStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "MarkingLTS 生成時間");

        // --- A. Marking LTS (Goal & Process Management) ---
        UpdateProtocolSpec updateProtocolSpec = uccs.getUpdateProtocolSpec();
        boolean fineGrained = uccs.isFineGrained();
        // システム全体のアクション集合を収集して、Marking LTSのアルファベットとする
        Set<String> allActions = new HashSet<>();
        //allActions.addAll(uccs.getControllableActions());
        for(CompactState cs : uccs.getMappingComponents()) {
            if(cs.getAlphabet() != null) Collections.addAll(allActions, cs.getAlphabet());
        }
        allActions.addAll(uccs.getOldController().getActions());
        // NCのアクションも含めておく（_old同期などのため）
        if (uccs.getNewController() != null) {
            allActions.addAll(uccs.getNewController().getActions());
        }
        allActions.add(UpdateConstants.BEGIN_UPDATE);
        allActions.add(UpdateConstants.FINISH_UPDATE);
        if (fineGrained && updateProtocolSpec != null) {
            allActions.addAll(updateProtocolSpec.getProgressActions());
        } else {
            allActions.add(UpdateConstants.STOP_OLD_SPEC);
            allActions.add(UpdateConstants.RECONFIGURE);
            allActions.add(UpdateConstants.START_NEW_SPEC);
        }
        // 内部遷移(tau)は除外
        allActions.remove("tau");
        
        // 10状態の Marking LTS を作成
        // 0:Pre ->(hotSwapIn)-> 1-8:In ->(hotSwapOut)-> 9:Post
        // OTF用の進捗管理Marking LTSを作成 (createOTFMarkingLTS使用)
        MTS<Long, String> markingMTS = fineGrained
                ? createFineGrainedProgressSlotLTS(
                        UpdateConstants.BEGIN_UPDATE,
                        UpdateConstants.FINISH_UPDATE,
                        allActions,
                        updateProtocolSpec)
                : createOTFMarkingLTS(
                        UpdateConstants.BEGIN_UPDATE,
                        UpdateConstants.FINISH_UPDATE,
                        allActions);
        
        // ゴール設定: legacyは状態9、fine-grainedはsynthetic goal状態2のみをMarkedとする
        LTS<Long, String> markingLTS = new LTSAdapter<>(markingMTS, TransitionType.REQUIRED);
        MarkedLTSAdapter<Long, String> markedMarkingLTS = new MarkedLTSAdapter<>(markingLTS);
        if (fineGrained) {
            markedMarkingLTS.unmark(0L);
            markedMarkingLTS.unmark(1L);
            markedMarkingLTS.mark(2L);
        } else {
            for(long i = 0; i <= 8; i++) markedMarkingLTS.unmark(i);
            markedMarkingLTS.mark(9L);
        }

        //評価実験章
        long createMarkingLTSTime = System.currentTimeMillis() - createMarkingLTSStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "MarkingLTS 生成時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF MarkingLTS 生成後");

        boxList.add(markedMarkingLTS); // Index 0
        output.outln(" - Added OTF Marking LTS (Index 0)");

        // --- B. Old Controller (Index 1) ---
        // OCのアクションをすべて `_old` 付きにリネームする
        // これにより、環境アクション(a)と区別し、Uncontrollableとして扱う
        oldContIndex = boxList.size();
        LTS<Long, String> originalOldContLTS = new LTSAdapter<>(uccs.getOldController(), TransitionType.REQUIRED);
        LTS<Long, String> renamedOldCont = new RenamedActionLTS<>(originalOldContLTS, "_old");
        boxList.add(renamedOldCont);
        output.outln(" - Added Old Controller (Index " + oldContIndex + ") [Renamed with _old]");

        // ★デバッグ出力: Old Controller (Renamed)
        // 期待値: アクションがすべて "_old" 付きになっていること
        // printLTSDebugInfo(renamedOldCont, "Old Controller (Renamed)", output);

        // --- C. New Controller (Excluded) ---
        // 探索コスト削減のため、NC自体はリストに入れない
        output.outln(" - New Controller is excluded from the Box List (Optimization).");

        // --- D. Mapping Environment ---
        mappingStartIndex = boxList.size();
        if(uccs.getMappingComponents() != null)
        {
            int i = mappingStartIndex;
            for(CompactState cs : uccs.getMappingComponents())
            {

                // output.outln("MapEnv (index" + i + "): " + cs.name);
                boxList.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
                i++;
            }
        }
        mappingEndIndex = boxList.size() - 1;

        output.outln("Added Mapping Environment Components (Index " + mappingStartIndex + " to " + mappingEndIndex + ") to BoxList:");

        // --- E. Old Safety ---
        oldSafeStartIndex = boxList.size();
        Map<Integer, String> oldSafetyStopActionsByIndex = new HashMap<>();
        if (uccs.getOldSafetyLTSs() != null)
        {
            for (CompactState cs : uccs.getOldSafetyLTSs())
            {
                LTS<Long, String> lts = new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED);
                boxList.add(lts);
                if (fineGrained && updateProtocolSpec != null) {
                    int currentIndex = boxList.size() - 1;
                    String stopAction = updateProtocolSpec.getOldSafetyToStopAction().get(cs.name);
                    if (stopAction == null) {
                        Diagnostics.fatal("No fine-grained stopOldSpec action generated for old safety: " + cs.name);
                    }
                    oldSafetyStopActionsByIndex.put(currentIndex, stopAction);
                }
            }
        }
        oldSafeEndIndex = boxList.size() - 1;

        output.outln("Added Old Safety Properties (Index " + oldSafeStartIndex + " to " + oldSafeEndIndex + ") to BoxList:");

        // --- F. New Safety ---
        // StateMapper用には、元の定義(LTSAdapter)を別途リスト化して保持しておく必要がある
        newSafeStartIndex = boxList.size();
        Map<Integer, String> newSafetyStartActionsByIndex = new HashMap<>();
        List<LTS<Long, String>> stateMapperSafetyAdapters = new ArrayList<>(); // Mapper用(非Synched)

        // ★追加: CompactState -> LTS<Long, String> の変換対応を保持するマップ
        // これを使って safetyComponentsMap 等の CompactState を boxList 内の実体(LTS)に紐付ける
        Map<CompactState, LTS<Long, String>> compactToLtsMap = new HashMap<>();
        
        //デバッグ用
        // output.outln("Adding New Safety Properties to BoxList:"); // ★見出し追加

        if (uccs.getNewSafetyLTSs() != null)
        {
            for (CompactState cs : uccs.getNewSafetyLTSs())
            {
                // BoxList用
                LTS<Long, String> originalForBox = new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED);
                boxList.add(originalForBox);
                if (fineGrained && updateProtocolSpec != null) {
                    int currentIndex = boxList.size() - 1;
                    String startAction = updateProtocolSpec.getNewSafetyToStartAction().get(cs.name);
                    if (startAction == null) {
                        Diagnostics.fatal("No fine-grained startNewSpec action generated for new safety: " + cs.name);
                    }
                    newSafetyStartActionsByIndex.put(currentIndex, startAction);
                }
                
                // Mapper用(NCとの接続計算用)
                stateMapperSafetyAdapters.add(originalForBox);

                // ★追加: 変換マップに登録 (Original Safety)
                compactToLtsMap.put(cs, originalForBox);

                // ★追加: 追加したインデックスと名前を表示
                // int currentIndex = boxList.size() - 1;
                // output.outln("  [Index " + currentIndex + "] " + cs.name);
            }
        }
        newSafeEndIndex = boxList.size() - 1;

        output.outln("Added New Safety Properties (Index " + newSafeStartIndex + " to " + newSafeEndIndex + ") to BoxList:");

        // --- G. Transition Requirements ---
        transReqStartIndex = boxList.size();
        if (uccs.getTransitionRequirements() != null)
        {
            for (CompactState cs : uccs.getTransitionRequirements())
            {
                boxList.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
            }
        }
        transReqEndIndex = boxList.size() - 1;

        output.outln("Added Transition Requirements (Index " + transReqStartIndex + " to " + transReqEndIndex + ") to BoxList:");

        // --- H. Synthesis Machines (Fluents) ---
        // ★追加: Transition Requirements の後に追加する

        //デバッグ用
        // output.outln("Adding Synthesis Machines (Fluents) to BoxList:"); // ★見出し追加

        synthesisStartIndex = boxList.size();
        if (uccs.getSynthesisMachines() != null)
        {
            for (CompactState cs : uccs.getSynthesisMachines())
            {
                LTS<Long, String> lts = new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED);
                boxList.add(lts);
                
                // ★追加: 変換マップに登録 (Monitors, Fluents)
                compactToLtsMap.put(cs, lts);

                // ★追加: 追加したインデックスと名前を表示
                // int currentIndex = boxList.size() - 1;
                // output.outln("  [Index " + currentIndex + "] " + cs.name);
            }
        }
        synthesisEndIndex = boxList.size() - 1;

        output.outln("Added New Safety Fluents (Index " + synthesisStartIndex + " to " + synthesisEndIndex + ") to BoxList:");

        // output.outln(" - Synthesis Machines added at indices: " + synthesisStartIndex + " to " + synthesisEndIndex);

        output.outln("Box List Created. Total Components: " + boxList.size());

        //評価実験用
        long stateMappingStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("OTF_STATE_MAPPING");
        DUCHeartbeat.setCounter("boxListComponents", boxList.size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "New Controller の接続先の事前計算");

        // ---------------------------------------------------------
        // 2. Build State Mapping Table (NC接続先の事前計算)
        // ---------------------------------------------------------
        output.outln("Building State Mapping Table (NewEnv || NewSafe -> NewCont)...");

        // (1) New Controller (実体) の準備
        LTS<Long, String> realNewContLTS = new LTSAdapter<>(uccs.getNewController(), TransitionType.REQUIRED);

        // (2) New Environment (実体) の準備
        List<LTS<Long, String>> stateMapperEnvAdapters = new ArrayList<>();
        if (uccs.getNewEnvironmentComponents() != null) {
             for (CompactState cs : uccs.getNewEnvironmentComponents()) {
                stateMapperEnvAdapters.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
            }
        } else {
            // フォールバック: 定義が見つからない場合は MappingComponents を代用
            // (通常、UpdatingControllersDefinition で正しくセットされていればここは通りません)
            output.outln("Warning: NewEnvironmentComponents not found in UCCS. Using MappingComponents as fallback.");
            // Mapping Compを代用する場合
            for (CompactState cs : uccs.getMappingComponents()) {
                stateMapperEnvAdapters.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
            }
        }

        // // ▼▼▼▼▼▼▼▼▼▼▼▼ DEBUG: StateMapper Input/Output Logging ▼▼▼▼▼▼▼▼▼▼▼▼
        // output.outln("\n========== DEBUG: StateMapper Verification START ==========");

        // // 1. New Controller の構造表示
        // output.outln(">> [New Controller Structure]");
        // printLTSDetails(realNewContLTS, output);

        // // 2. New Environment Components の構造表示
        // output.outln(">> [New Environment Components (" + stateMapperEnvAdapters.size() + ")]");
        // for (int i = 0; i < stateMapperEnvAdapters.size(); i++) {
        //     output.outln("  -- Env Component " + i + " --");
        //     printLTSDetails(stateMapperEnvAdapters.get(i), output);
        // }

        // // 3. New Safety Components の構造表示
        // output.outln(">> [New Safety Components (" + stateMapperSafetyAdapters.size() + ")]");
        // for (int i = 0; i < stateMapperSafetyAdapters.size(); i++) {
        //     output.outln("  -- Safe Component " + i + " --");
        //     printLTSDetails(stateMapperSafetyAdapters.get(i), output);
        // }
        // // ▲▲▲▲▲▲▲▲▲▲▲▲ END INPUT LOGGING ▲▲▲▲▲▲▲▲▲▲▲▲

        // (3) StateMapper の実行
        StateMapper mapper = new StateMapper(realNewContLTS, stateMapperEnvAdapters, stateMapperSafetyAdapters);
        // マップ生成: Key="EnvState,SafeState..." -> Value=ControllerStateID
        Map<String, Long> newControllerConnectionMap = mapper.generateMapping();

        // // ▼▼▼▼▼▼▼▼▼▼▼▼ DEBUG: Mapping Result Logging ▼▼▼▼▼▼▼▼▼▼▼▼
        // output.outln("\n>> [StateMapper Result (Signature -> ControllerStateID)]");
        // if (newControllerConnectionMap.isEmpty()) {
        //     output.outln("!! WARNING: Mapping is EMPTY !!");
        // } else {
        //     // キーをソートして表示（見やすくするため）
        //     List<String> sortedKeys = new ArrayList<>(newControllerConnectionMap.keySet());
        //     Collections.sort(sortedKeys);
        
        //     for (String sig : sortedKeys) {
        //         Long ctrlState = newControllerConnectionMap.get(sig);
        //         output.outln("  Sig [" + sig + "]  ->  CtrlState " + ctrlState);
        //     }
        // }
        // output.outln("========== DEBUG: StateMapper Verification END ==========\n");
        // // ▲▲▲▲▲▲▲▲▲▲▲▲ END RESULT LOGGING ▲▲▲▲▲▲▲▲▲▲▲▲

        // // ▼▼▼ ここにデバッグ出力を追加 ▼▼▼
        // output.outln("\n========== DEBUG: NC Connection Map Signatures ==========");
        // for (String sig : newControllerConnectionMap.keySet()) {
        //     output.outln("NC Map Key: " + sig);
        // }
        // output.outln("=========================================================\n");
        // // ▲▲▲ 追加ここまで ▲▲▲

        output.outln(" - State Mapper generated " + newControllerConnectionMap.size() + " mapping entries.");

        long stateMappingTime = System.currentTimeMillis() - stateMappingStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "New Controller の接続先の事前計算");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF New Controller 接続先事前計算後");

        long translateFluentMapStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("OTF_SAFETY_MAP_CONVERSION");
        DUCHeartbeat.setCounter("newControllerConnectionEntries", newControllerConnectionMap.size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "New Safety と Fluent の対応表の変換作業時間");

        // ---------------------------------------------------------
        // 3. Convert CompactState Maps to LTS Maps
        // CompactStateベースのマップをLTSベースのマップに変換する
        // ---------------------------------------------------------
        output.outln("Converting Safety Maps to LTS-based Maps...");

        // (1) Components Map (Monitor/Fluent lists)
        // ★変更1: Keyを LTS ではなく Integer (boxList index) にする
        // new safety propertyのboxListインデックス -> 対応するnew safety monitorとアクションFluentのboxListインデックス
        Map<Integer, List<Integer>> safetyComponentIndicesMap = new HashMap<>();

        // (2) State Lookup Map
        // ★変更2: State Lookup Map の Key も Integer にする
        // 外側のMap : new safety propertyのboxListインデックス -> 内側のMap
        // 内側のMap : new safety monitorとアクションFluentの状態 -> new safety propertyのエラー状態．
        // new safety propertyのエラー状態になるようなnew safety monitorとアクションFluentの状態でなければMapに登録されない
        Map<Integer, Map<List<Integer>, Integer>> safetyStateLookupMap = new HashMap<>();

        if (uccs.getSafetyComponentsMap() != null) {
            for (Map.Entry<CompactState, List<CompactState>> entry : uccs.getSafetyComponentsMap().entrySet()) {
                CompactState originalKey = entry.getKey();
                
                // 1. Original Safety Property の boxList インデックスを特定
                LTS<Long, String> ltsKey = compactToLtsMap.get(originalKey);
                int safetyIndex = boxList.indexOf(ltsKey); // 線形探索だが、構築時の1回だけなのでOK
                
                if (safetyIndex == -1) {
                    Diagnostics.fatal("Error: Original Safety Property not found in boxList: " + originalKey.name);
                }

                // 2. 構成要素 (Monitor + Fluents) のインデックスリストを作成
                List<Integer> componentIndices = new ArrayList<>();
                for (CompactState comp : entry.getValue()) {
                    LTS<Long, String> ltsComp = compactToLtsMap.get(comp);
                    int compIndex = boxList.indexOf(ltsComp);
                    
                    if (compIndex == -1) {
                        Diagnostics.fatal("Error: Component not found in boxList: " + comp.name);
                    }
                    componentIndices.add(compIndex);
                }
                
                // マップに登録 (Integer -> List<Integer>)
                safetyComponentIndicesMap.put(safetyIndex, componentIndices);

                // 3. State Lookup Map も Integer キーで登録
                if (uccs.getSafetyStateMapping() != null) {
                    Map<List<Integer>, Integer> stateMap = uccs.getSafetyStateMapping().get(originalKey);
                    if (stateMap != null) {
                        safetyStateLookupMap.put(safetyIndex, stateMap);
                    }
                }
            }
        }
        output.outln("Map Conversion Completed.");

        long translateFluentMapTime = System.currentTimeMillis() - translateFluentMapStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "New Safety と Fluent の対応表の変換作業時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF New Safety/Fluent 対応表変換後");

        // // ▼▼▼▼▼▼▼▼▼▼▼▼ デバッグ表示 (Integer Key 確認用) ▼▼▼▼▼▼▼▼▼▼▼▼
        // output.outln("\n========== DEBUG: Index-based Safety Map Verification ==========");
        // for (Map.Entry<Integer, List<Integer>> entry : safetyComponentIndicesMap.entrySet()) {
        //     int safetyIdx = entry.getKey();
        //     List<Integer> compIndices = entry.getValue();
            
        //     // boxListからLTSを取り出して確認（デバッグ用）
        //     // ※実際にはLTSAdapterなので名前は取れないかもしれないが、クラス名などで確認
        //     output.outln("Safety Property [Index " + safetyIdx + "]:");
            
        //     for (int i = 0; i < compIndices.size(); i++) {
        //         int idx = compIndices.get(i);
        //         String type = (i == 0) ? "Monitor" : "Fluent ";
        //         output.outln("     [" + i + "] " + type + " => boxList Index: " + idx);
        //     }
            
        //     // Lookup Map の確認
        //     Map<List<Integer>, Integer> lookup = safetyStateLookupMap.get(safetyIdx);
        //     output.outln("     -> Lookup Table Size: " + (lookup != null ? lookup.size() : "null"));
        // }
        // output.outln("========== END VERIFICATION ==========\n");
        // // ▲▲▲▲▲▲▲▲▲▲▲▲ 追加終了 ▲▲▲▲▲▲▲▲▲▲▲▲

        //評価実験用
        long boxListTime = System.currentTimeMillis() - boxListStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "boxList 準備時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF boxList 準備後");

        output.outln("Initializing DCS...");

        long dcsStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("OTF_DCS_INITIALIZE");
        DUCHeartbeat.setCounter("boxListComponents", boxList.size());
        DUCHeartbeat.setCounter("newControllerConnectionEntries", newControllerConnectionMap.size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "DCS で Update Controller を合成する時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "generateDUC (OTF-DUC)",
                "DCS で Update Controller を合成する時間");
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "generateDUC (OTF-DUC)",
                "DCS 実行時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "generateDUC (OTF-DUC)",
                "DCS 実行時間");

        DirectedControllerSynthesisDUC<Long, String> ducSynthesis = fineGrained
                ? (updateProtocolSpec != null && updateProtocolSpec.isSelective()
                        ? new DirectedControllerSynthesisSelectiveFineGrainedDUC<>()
                        : new DirectedControllerSynthesisFineGrainedDUC<>())
                : new DirectedControllerSynthesisDUC<>();

        LTS<Long, String> result;
        try {
            if (fineGrained) {
                result = ((DirectedControllerSynthesisFineGrainedDUC<Long, String>) ducSynthesis).synthesizeDUC(
                    boxList,
                    uccs.getControllableActions(),
                    mappingStartIndex, mappingEndIndex,
                    oldSafeStartIndex, oldSafeEndIndex,
                    newSafeStartIndex, newSafeEndIndex,
                    transReqStartIndex, transReqEndIndex,
                    synthesisStartIndex, synthesisEndIndex,
                    uccs.getMappingMapEnvToNewEnv(),
                    newControllerConnectionMap,
                    realNewContLTS,
                    safetyComponentIndicesMap,
                    safetyStateLookupMap,
                    updateProtocolSpec,
                    oldSafetyStopActionsByIndex,
                    newSafetyStartActionsByIndex,
                    output
                );
            } else {
                result = ducSynthesis.synthesizeDUC(
                    boxList,
                    uccs.getControllableActions(),
                    mappingStartIndex, mappingEndIndex,
                    oldSafeStartIndex, oldSafeEndIndex,
                    newSafeStartIndex, newSafeEndIndex,
                    transReqStartIndex, transReqEndIndex,
                    synthesisStartIndex, synthesisEndIndex,
                    uccs.getMappingMapEnvToNewEnv(),
                    newControllerConnectionMap,
                    realNewContLTS,
                    safetyComponentIndicesMap,  // ★追加: LTSベースのコンポーネントマップ
                    safetyStateLookupMap,    // ★追加: LTSベースの状態追跡マップ
                    output
                );
            }
        } finally {
            UpdatingControllerEvaluationRecorder.endCountScope(
                    "generateDUC (OTF-DUC)",
                    "DCS 実行時間");
        }

        long dcsTmp = System.currentTimeMillis() - dcsStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "DCS 実行時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF DCS 実行後");

        if (result != null) {
            DUCHeartbeat.beginPhase("OTF_OUTPUT_CONTROLLER_BUILD");
            DUCHeartbeat.setCounter("resultStates", result.getStates().size());
            output.outln("DUC Generated Successfully! States: " + result.getStates().size());
            CompactState res = MTSToAutomataConverter.getInstance().convert(new MTSAdapter<Long, String>(result), uccs.getName(), false);
            res.reachable();
            uccs.setComposition(res);
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("OTF Update Controller 出力構築後");
        } else {
            UpdatingControllerEvaluationRecorder.recordFailure(
                    ResultStatus.GOAL_NOT_REACHABLE,
                    "Failed to generate DUC (Goal not reachable).");
            output.outln("Failed to generate DUC (Goal not reachable).");
        }

        long dcsTime = System.currentTimeMillis() - dcsStart;
        UpdatingControllerEvaluationRecorder.endCountScope(
                "generateDUC (OTF-DUC)",
                "DCS で Update Controller を合成する時間");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "DCS で Update Controller を合成する時間");

        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "boxList 準備時間", boxListTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "MarkingLTS 生成時間", createMarkingLTSTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "New Controller の接続先の事前計算", stateMappingTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "New Safety と Fluent の対応表の変換作業時間", translateFluentMapTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間", dcsTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "generateDUC (OTF-DUC)", "DCS 実行時間", dcsTmp);
        UpdatingControllerEvaluationRecorder.endCountScope(
                "generateDUC (OTF-DUC)",
                "generateDUC 全体時間");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "generateDUC (OTF-DUC)",
                "generateDUC 全体時間");
    }

    /**
     * OTF探索用の進捗管理機能付き Marking LTS を作成する。
     * 4つの更新事象が全て完了するまで hotSwapOut を許可しないロジックをLTS構造として埋め込む。
     * * ■ 状態定義 (States):
     * - State 0: Pre-Update (hotSwapIn 前)
     * - State 1..8: In-Update (更新中。3つの事象の完了状況をビットマスクで管理)
     * - Base Offset = 1
     * - State ID = 1 + mask (mask: 0..7)
     * - Bit 0 (1): stopOldSpec 完了
     * - Bit 1 (2): reconfigure 完了
     * - Bit 2 (4): startNewSpec 完了
     * - State 9: Post-Update (Goal。hotSwapOut 後)
     * * @param startAction 更新開始アクション (例: hotSwapIn)
     * @param endAction   更新終了アクション (例: hotSwapOut)
     * @param alphabet    システム全体のアルファベット集合
     * @return 進捗管理ロジックを含むMTS
     */
    /**
     * OTF探索用の進捗管理機能付き Marking LTS を作成する。
     * (修正版: 完了済みの更新アクションはブロックし、自己ループさせない)
     */
    private static MTS<Long, String> createFineGrainedProgressSlotLTS(
            String startAction, String endAction, Set<String> alphabet, UpdateProtocolSpec protocolSpec)
    {
        MTS<Long, String> progressMTS = new MTSImpl<>(0L);
        progressMTS.addState(0L); // pre-update
        progressMTS.addState(1L); // in-update, empty completed set
        progressMTS.addState(2L); // post-update goal
        progressMTS.setInitialState(0L);

        Set<String> fullAlphabet = new HashSet<>(alphabet);
        fullAlphabet.add(startAction);
        fullAlphabet.add(endAction);
        if (protocolSpec != null) {
            fullAlphabet.addAll(protocolSpec.getProgressActions());
        }
        fullAlphabet.remove("tau");
        progressMTS.addActions(fullAlphabet);

        for (String action : fullAlphabet) {
            boolean updateAction = action.equals(startAction)
                    || action.equals(endAction)
                    || (protocolSpec != null && protocolSpec.isProgressAction(action));
            if (action.equals(startAction)) {
                progressMTS.addTransition(0L, action, 1L, TransitionType.REQUIRED);
            } else if (!updateAction) {
                progressMTS.addTransition(0L, action, 0L, TransitionType.REQUIRED);
                progressMTS.addTransition(1L, action, 1L, TransitionType.REQUIRED);
                progressMTS.addTransition(2L, action, 2L, TransitionType.REQUIRED);
            }
        }
        return progressMTS;
    }

    private static MTS<Long, String> createOTFMarkingLTS(
            String startAction, String endAction, Set<String> alphabet)
    {
        MTS<Long, String> markingMTS = new MTSImpl<>(0L);
        
        // --- 1. 状態の作成 (0 ～ 9) ---
        for(long i = 0; i <= 9; i++) {
            markingMTS.addState(i);
        }
        markingMTS.setInitialState(0L);

        // --- 2. アルファベットの設定 ---
        Set<String> fullAlphabet = new HashSet<>(alphabet);
        fullAlphabet.add(startAction);       // hotSwapIn
        fullAlphabet.add(endAction);         // hotSwapOut
        fullAlphabet.add(UpdateConstants.STOP_OLD_SPEC);
        fullAlphabet.add(UpdateConstants.RECONFIGURE);
        fullAlphabet.add(UpdateConstants.START_NEW_SPEC);
        
        markingMTS.addActions(fullAlphabet);

        // --- 3. 定数定義 ---
        final int BIT_STOP     = 1; 
        final int BIT_RECONFIG = 2; 
        final int BIT_START    = 4; 
        final int ALL_DONE     = 7; 
        
        final long STATE_PRE    = 0L;
        final long STATE_OFFSET = 1L;
        final long STATE_GOAL   = 9L;

        // --- 4. 遷移の定義 ---
        for (String action : fullAlphabet) {
            
            // =========================================================
            // A. State 0: Pre-Update
            // =========================================================
            if (action.equals(startAction)) {
                markingMTS.addTransition(STATE_PRE, action, STATE_OFFSET, TransitionType.REQUIRED);
            } 
            else if (action.equals(endAction) || 
                     action.equals(UpdateConstants.STOP_OLD_SPEC) || 
                     action.equals(UpdateConstants.RECONFIGURE) || 
                     action.equals(UpdateConstants.START_NEW_SPEC)) {
                // 更新イベントはブロック
            }
            else {
                // その他(システムアクション)は自己ループ
                markingMTS.addTransition(STATE_PRE, action, STATE_PRE, TransitionType.REQUIRED);
            }

            // =========================================================
            // B. State 1..8: In-Update
            // =========================================================
            for (int mask = 0; mask <= 7; mask++) {
                long currentState = STATE_OFFSET + mask;
                long nextState = currentState;
                boolean isUpdateAction = false; // 更新制御アクションかどうかのフラグ

                if (action.equals(UpdateConstants.STOP_OLD_SPEC)) {
                    isUpdateAction = true;
                    // ★修正: 既に完了している(ビットが立っている)場合はブロック
                    if ((mask & BIT_STOP) != 0) continue; 
                    
                    int newMask = mask | BIT_STOP;
                    nextState = STATE_OFFSET + newMask;
                } 
                else if (action.equals(UpdateConstants.RECONFIGURE)) {
                    isUpdateAction = true;
                    // ★修正: 既に完了している場合はブロック
                    if ((mask & BIT_RECONFIG) != 0) continue;

                    int newMask = mask | BIT_RECONFIG;
                    nextState = STATE_OFFSET + newMask;
                } 
                else if (action.equals(UpdateConstants.START_NEW_SPEC)) {
                    isUpdateAction = true;
                    // ★修正: 既に完了している場合はブロック
                    if ((mask & BIT_START) != 0) continue;

                    int newMask = mask | BIT_START;
                    nextState = STATE_OFFSET + newMask;
                } 
                else if (action.equals(endAction)) {
                    isUpdateAction = true;
                    if (mask == ALL_DONE) {
                        nextState = STATE_GOAL;
                    } else {
                        continue; // Blocked if not ready
                    }
                } 
                else if (action.equals(startAction)) {
                    isUpdateAction = true;
                    continue; // 既に開始しているのでブロック
                }
                
                // システムアクション(in, out等)の場合のみ自己ループ(nextState=currentState)
                // 更新アクションは上記if文で処理され、ブロックされなかった場合のみ遷移追加
                markingMTS.addTransition(currentState, action, nextState, TransitionType.REQUIRED);
            }

            // =========================================================
            // C. State 9: Post-Update (Goal)
            // =========================================================
            // ゴール後はシステムアクションのみ許可し、更新イベントはブロックする
            if (!action.equals(startAction) && 
                !action.equals(endAction) &&
                !action.equals(UpdateConstants.STOP_OLD_SPEC) &&
                !action.equals(UpdateConstants.RECONFIGURE) &&
                !action.equals(UpdateConstants.START_NEW_SPEC)) {
                
                markingMTS.addTransition(STATE_GOAL, action, STATE_GOAL, TransitionType.REQUIRED);
            }
        }

        return markingMTS;
    }

    /**
     * デバッグ用: LTS<Long, String> の状態遷移詳細を出力する
     */
    private static void printLTSDetails(LTS<Long, String> lts, LTSOutput output) {
        output.outln("    Alphabet: " + lts.getActions());
        
        // 状態リストを取得してソート
        List<Long> states = new ArrayList<>(lts.getStates());
        Collections.sort(states);

        for (Long state : states) {
            StringBuilder sb = new StringBuilder();
            sb.append("    State ").append(state);
            if (state.equals(lts.getInitialState())) {
                sb.append(" (INIT)");
            }
            sb.append(":");

            // その状態からの遷移を取得
            boolean hasTransitions = false;
            for (Pair<String, Long> trans : lts.getTransitions(state)) {
                if (hasTransitions) sb.append(",");
                sb.append(" ").append(trans.getFirst()).append("->").append(trans.getSecond());
                hasTransitions = true;
            }
            
            if (!hasTransitions) {
                sb.append(" (terminal or blocked)");
            }
            output.outln(sb.toString());
        }
    }

    /**
     * デバッグ用: LTSの状態と遷移（アクション -> 次の状態）を詳細に出力する
     */
    private static void printLTSDebugInfo(LTS<Long, String> lts, String componentName, LTSOutput output) {
        output.outln("\n--- DEBUG: " + componentName + " Structure ---");
        output.outln("Total States: " + lts.getStates().size());
        
        // 状態IDを見やすくするためにソート
        List<Long> states = new ArrayList<>(lts.getStates());
        Collections.sort(states);

        for (Long state : states) {
            StringBuilder sb = new StringBuilder();
            sb.append("  State ").append(state);
            if (state.equals(lts.getInitialState())) {
                sb.append(" (INIT)");
            }
            sb.append(":");

            // 動的ラッパー(Renamed/Synched)に対応するため、必ず getTransitions(state) を呼ぶ
            boolean hasTrans = false;
            // 遷移先状態ID順、アクション名順などでソートして表示すると見やすいが、
            // ここでは簡易的にそのまま出力する
            for (Pair<String, Long> trans : lts.getTransitions(state)) {
                String action = trans.getFirst();
                Long toState = trans.getSecond();
                
                sb.append("\n    --[").append(action).append("]--> ").append(toState);
                hasTrans = true;
            }

            if (!hasTrans) {
                sb.append(" (Terminal)");
            }
            output.outln(sb.toString());
        }
        output.outln("--------------------------------------------------");
    }

    /**
     * デバッグ用: コンポーネントの状態遷移（State -> Action -> NextState）を表示する
     */
    private static void logComponentTransitions(CompactState cs, LTSOutput output) {
        output.outln("  [LTS Structure for " + cs.name + "]");
        
        // 全状態を走査
        for (int i = 0; i < cs.maxStates; i++) {
            EventState head = cs.states[i];
            
            // 遷移がない状態はスキップ（または "Terminal" と表示してもよい）
            if (head == null) continue;

            // その状態からの全遷移を取得
            Enumeration<EventState> transitions = head.elements();
            
            while (transitions.hasMoreElements()) {
                EventState t = transitions.nextElement();
                
                // アクション名と次の状態IDを取得
                String action = cs.alphabet[t.getEvent()];
                int next = t.getNext();
                
                // ログ出力: State X -> (action) -> State Y
                output.outln("    State " + i + " --[" + action + "]--> State " + next);
            }
        }
    }

    // ▼▼▼ 評価実験用: MTSの遷移数をカウントするヘルパーメソッド ▼▼▼
    private static int countTransitions(MTS<Long, String> mts) {
        int count = 0;
        for (Long state : mts.getStates()) {
            // REQUIRED と MAYBE の両方の遷移をカウントする（通常はREQUIREDのみですが念のため両方）
            count += mts.getTransitions(state, MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType.REQUIRED).size();
            count += mts.getTransitions(state, MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType.MAYBE).size();
        }
        return count;
    }
    // ▲▲▲ 追加ここまで ▲▲▲
}
