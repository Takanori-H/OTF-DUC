package ltsa.updatingControllers.stepwise.delayed;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.MapSetBinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTSConstants;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.CompactState;
import ltsa.lts.Diagnostics;
import ltsa.lts.LTSOutput;
import ltsa.lts.MappingEnvironmentGenerator;
import ltsa.updatingControllers.DUCHeartbeat;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.stepwise.StepwiseActionOwnership;
import ltsa.updatingControllers.stepwise.StepwiseClassifiedGoal;
import ltsa.updatingControllers.stepwise.StepwiseGoalClassifier;
import ltsa.updatingControllers.stepwise.StepwiseRequirementKind;
import ltsa.updatingControllers.stepwise.StepwiseStage;
import ltsa.updatingControllers.stepwise.StepwiseUpdatingControllerSafetySynthesizer;
import ltsa.updatingControllers.synthesis.UpdatingControllerGRSynthesizer;
import ltsa.updatingControllers.synthesis.SafetyBackwardPruner;
import ltsa.updatingControllers.synthesis.UpdatingControllerSafetySynthesizer;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;

import java.math.BigInteger;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Vector;
import java.util.logging.Level;
import java.util.logging.Logger;

public class StepwiseDelayedUpdatingControllerSynthesizer {

    private static final boolean COST_GUIDED_STAGED_CROSS_SCHEDULING =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.costGuidedCrossScheduling",
                    "true"));
    private static final boolean INDEXED_HOT_SWAP_IN_CONNECTION =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.indexedHotSwapInConnection",
                    "false"));
    // Avoid materializing a second copy of every mapping transition while the
    // connected environment is used only for statistics and the immediate
    // DontDoTwice conversion. The legacy materialized connector remains
    // available as an ablation/oracle with
    // -Dstepwise.delayed.connectedEnvironmentView=false.
    private static final boolean CONNECTED_ENVIRONMENT_VIEW =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.connectedEnvironmentView",
                    "true"));
    // Diagnostic-only instrumentation. It is deliberately disabled by default so
    // paper/evaluation runs do not perform extra graph traversals or emit extra logs.
    private static final boolean DEBUG_ACTION_DIAGNOSTICS =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.debugActionDiagnostics",
                    "false"));
    // Focused hotSwapIn lineage diagnostics. Kept separate from the broader
    // action diagnostics so this tracing can be enabled without their graph scans.
    private static final boolean DEBUG_HOT_SWAP_LINEAGE =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.debugHotSwapLineage",
                    "false"));
    private static final Map<MTS<Long, String>, Map<Long, long[]>> DEBUG_LOCAL_STATE_VECTORS =
            new IdentityHashMap<MTS<Long, String>, Map<Long, long[]>>();
    private static final Map<MTS<Long, String>, Map<Long, Long>> DEBUG_FLUENT_PRODUCT_BASE_STATES =
            new IdentityHashMap<MTS<Long, String>, Map<Long, Long>>();
    private static final Map<Integer, DelayedEnv> DEBUG_LOCAL_ENVIRONMENTS =
            new HashMap<Integer, DelayedEnv>();
    private static int debugLineageStageCount;

    public static void generateController(UpdatingControllerCompositeState uccs, LTSOutput output) {
        final boolean phaseMemoryDiagnostics =
                UpdatingControllerEvaluationRecorder.isPhaseMemoryDiagnosticsEnabled();
        MTS<Long, String> safetyEnv = buildFinalSafetyEnvironment(
                uccs,
                output,
                phaseMemoryDiagnostics);

        uccs.setUpdateEnvironment(safetyEnv);
        long compactSafetyEnvStart = System.currentTimeMillis();
        CompactState compactSafetyEnv = MTSToAutomataConverter.getInstance()
                .convert(safetyEnv, "stepwise_delayed_E_u||G(safety)", false, true);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC",
                "safetyEnv から CompactState への変換時間",
                System.currentTimeMillis() - compactSafetyEnvStart);
        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(compactSafetyEnv);
        uccs.setMachines(machines);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.gr_input.compact_state_ready");
        }

        output.outln("");
        output.outln("[Stepwise Delayed DUCS] GR(1)");
        UpdatingControllerEvaluationRecorder.endCountScope(
                "Stepwise Delayed DUC",
                "GR(1)入力 safetyEnv 構築時間");
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Stepwise Delayed DUC",
                "GR(1)入力 safetyEnv 構築時間");
        long synthesizeGRStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("STEPWISE_DELAYED_GR1_SYNTHESIS");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Stepwise Delayed DUC",
                "safetyEnv を GR1 で解く時間");
        UpdatingControllerGRSynthesizer.synthesizeStepwiseDelayedGR(compactSafetyEnv, uccs, safetyEnv, output);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.gr_synthesis.returned");
        }
        long synthesizeGRTime = System.currentTimeMillis() - synthesizeGRStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "Stepwise Delayed DUC",
                "safetyEnv を GR1 で解く時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Stepwise Delayed GR1 合成後");
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC",
                "safetyEnv を GR1 で解く時間",
                synthesizeGRTime);
        if (uccs.getComposition() == null) {
            output.outln("[Stepwise Delayed DUCS] GR result: losing");
            Diagnostics.fatal("stepwise_delayed GR(1) synthesis was losing for " + uccs.getName() + ".");
        } else {
            output.outln("[Stepwise Delayed DUCS] GR result: winning");
            output.outln("[Stepwise Delayed DUCS] output controller states: " + uccs.getComposition().maxStates
                    + " transitions: " + uccs.getComposition().ntransitionsLong());
        }
    }

    private static MTS<Long, String> buildFinalSafetyEnvironment(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        MTS<Long, String> safetyEnv = buildDontDoTwiceEnvironment(
                uccs,
                output,
                phaseMemoryDiagnostics);
        StateSpaceStats finalSafetyStats =
                outputStateSpace(output, "[Stepwise Delayed DUCS] After global DontDoTwice", safetyEnv);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.dont_do_twice.ready");
        }
        String finalSafetySourceStage = "[Stepwise Delayed DUCS] After global DontDoTwice";
        if (uccs.isSafetyBackwardPruning()) {
            SafetyBackwardPruner.Result backwardPruning = SafetyBackwardPruner.prune(
                    safetyEnv,
                    uccs.getUpdateGRGoal().getControllableActions(),
                    "stepwise-delayed-final",
                    output);
            failIfInitialLosing(backwardPruning, "stepwise-delayed-final");
            safetyEnv = backwardPruning.getEnvironment();
            finalSafetyStats = outputStateSpace(
                    output,
                    "[Stepwise Delayed DUCS] After final safety backward pruning",
                    safetyEnv);
            finalSafetySourceStage = "[Stepwise Delayed DUCS] After final safety backward pruning";
            if (phaseMemoryDiagnostics) {
                UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                        "stepwise.final_safety_sbp_ready");
            }
        }
        UpdatingControllerEvaluationRecorder.recordFinalGrInputStateSpace(
                "stepwise_delayed",
                "Stepwise Delayed DUC",
                finalSafetyStats.states,
                finalSafetyStats.transitions,
                finalSafetySourceStage);
        return safetyEnv;
    }

    private static MTS<Long, String> buildDontDoTwiceEnvironment(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        // End the connected-view frame after conversion but before the LTSA
        // product starts. The product begins with a full GC, so neither the
        // view nor the source old/mapping MTS remains a stack root then.
        DontDoTwiceInputs inputs = prepareDontDoTwiceInputs(
                uccs,
                output,
                phaseMemoryDiagnostics);
        MTS<Long, String> safetyEnv = UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(
                inputs.connectedAutomaton,
                inputs.connectedAlphabet);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC",
                "global DontDoTwice 構築時間",
                System.currentTimeMillis() - inputs.dontDoTwiceStartMillis);
        return safetyEnv;
    }

    private static DontDoTwiceInputs prepareDontDoTwiceInputs(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        MTS<Long, String> connected = buildConnectedEnvironment(
                uccs,
                output,
                phaseMemoryDiagnostics);
        long dontDoTwiceStartMillis = System.currentTimeMillis();
        Set<String> connectedAlphabet = new HashSet<String>(connected.getActions());
        CompactState connectedAutomaton = MTSToAutomataConverter.getInstance().convert(
                connected,
                "safetyEnv",
                false,
                false);
        return new DontDoTwiceInputs(
                connectedAutomaton,
                connectedAlphabet,
                dontDoTwiceStartMillis);
    }

    private static MTS<Long, String> buildConnectedEnvironment(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        ConnectedEnvironmentInputs inputs = prepareConnectedEnvironmentInputs(
                uccs,
                output,
                phaseMemoryDiagnostics);
        return connectPreparedEnvironment(inputs, output, phaseMemoryDiagnostics);
    }

    private static ConnectedEnvironmentInputs prepareConnectedEnvironmentInputs(
            UpdatingControllerCompositeState uccs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        List<StepwiseStage> stages = uccs.getStepwiseStages();
        if (stages == null || stages.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed requires at least one stage.");
        }
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugResetHotSwapLineage(stages.size());
        }
        MTS<Long, String> oldController = uccs.getOldController();
        if (oldController == null) {
            Diagnostics.fatal("stepwise_delayed requires oldController.");
        }

        output.outln("[Stepwise Delayed DUCS]");
        output.outln("oldController is used as global OldCon: " + uccs.getOldControllerName());

        ControllerGoalDefinition oldGoal = uccs.getStepwiseOldGoalDefinition();
        ControllerGoalDefinition newGoal = uccs.getStepwiseNewGoalDefinition();
        validateSupportedGoal("oldGoal", oldGoal);
        validateSupportedGoal("newGoal", newGoal);

        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Stepwise Delayed DUC",
                "GR(1)入力 safetyEnv 構築時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "Stepwise Delayed DUC",
                "GR(1)入力 safetyEnv 構築時間");
        StepwiseActionOwnership actionOwnership = new StepwiseActionOwnership(stages);
        StepwiseGoalClassifier classifier = new StepwiseGoalClassifier(stages, actionOwnership, output);
        StepwiseGoalClassifier.Classification classification =
                classifier.classify(oldGoal, newGoal, uccs.getStepwiseTransitionGoals());
        List<StepwiseClassifiedGoal> crossGoals = classification.getCrossGoals();
        long crossComponentBuildStart = System.currentTimeMillis();
        List<CrossComponent> crossComponents = buildCrossComponents(crossGoals);
        sortCrossComponentsByStageSet(crossComponents);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.requirements.classified");
        }
        long crossComponentBuildTime = System.currentTimeMillis() - crossComponentBuildStart;
        UpdatingControllerEvaluationRecorder.recordTime(
                StepwiseDelayedEvaluationMetrics.CLASSIFICATION_SECTION,
                "cross component 構築時間",
                crossComponentBuildTime);

        List<StageBase> bases = new ArrayList<StageBase>();
        Set<String> globalActions = new HashSet<String>();
        globalActions.addAll(oldController.getActions());
        globalActions.add(UpdateConstants.STOP_OLD_SPEC);
        globalActions.add(UpdateConstants.START_NEW_SPEC);

        for (StepwiseStage stage : stages) {
            MTS<Long, String> mapping = AutomataToMTSConverter.getInstance().convert(stage.getMappingEnvironment());
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata =
                    initialStageMetadata(stage);
            addMappingRegionUpdateSelfLoops(mapping);
            Set<String> realActions = mappingRealActions(mapping);
            globalActions.addAll(mapping.getActions());
            bases.add(new StageBase(stage, mapping, metadata, realActions));
        }
        globalActions.remove(UpdateConstants.BEGIN_UPDATE);
        globalActions.remove(MTSConstants.TAU);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.mapping_components.ready");
        }

        output.outln("[Stepwise Delayed DUCS] mode flags: stepwise_delayed=true"
                + " incrementalPruning=" + uccs.isIncrementalPruning()
                + " incrementalPruningCleanup=" + uccs.isIncrementalPruningCleanup()
                + " safetyBackwardPruning=" + uccs.isSafetyBackwardPruning());
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "incrementalPruning 有効",
                uccs.isIncrementalPruning() ? 1 : 0,
                "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "incrementalPruningCleanup 有効",
                uccs.isIncrementalPruningCleanup() ? 1 : 0,
                "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "safetyBackwardPruning 有効",
                uccs.isSafetyBackwardPruning() ? 1 : 0,
                "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "costGuidedCrossScheduling 有効",
                COST_GUIDED_STAGED_CROSS_SCHEDULING ? 1 : 0,
                "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "indexedHotSwapInConnection 有効",
                INDEXED_HOT_SWAP_IN_CONNECTION ? 1 : 0,
                "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC 設定",
                "GR(1) permissive strategy 有効",
                uccs.getUpdateGRGoal().isPermissive() ? 1 : 0,
                "boolean");
        if (uccs.isIncrementalPruning()) {
            output.outln("[Stepwise Delayed DUCS] incremental pruning policy is enabled; "
                    + "controller permissiveness may differ.");
        }
        long classificationStatsStart = System.currentTimeMillis();
        outputClassificationStatistics(
                output,
                stages.size(),
                classification,
                crossGoals,
                crossComponents);
        long classificationStatsCountTime = System.currentTimeMillis() - classificationStatsStart;
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                StepwiseDelayedEvaluationMetrics.CLASSIFICATION_SECTION,
                "分類統計計算・記録 CountTime",
                classificationStatsCountTime,
                "stepwise_delayed の stage/local/cross goal 数、cross 比率、cross component scope 統計を計算し、ログと評価 recorder に記録する時間。");
        output.outln("[Stepwise Delayed DUCS] classification statistics CountTime: "
                + classificationStatsCountTime + " ms");
        output.outln("[Stepwise Delayed DUCS] cross component build time: "
                + crossComponentBuildTime + " ms");

        DelayedEnv mappingProduct;
        if (uccs.isIncrementalPruning()) {
            IncrementalPruningCounter counter = new IncrementalPruningCounter();
            List<DelayedEnv> localSafetyEnvironments = buildIncrementalLocalEnvironments(
                    bases,
                    stages,
                    classification,
                    globalActions,
                    uccs.isIncrementalPruningCleanup(),
                    uccs.isSafetyBackwardPruning(),
                    uccs.getUpdateGRGoal().getControllableActions(),
                    counter,
                    output);
            mappingProduct = buildIncrementalCrossAndFinalProduct(
                    crossComponents,
                    localSafetyEnvironments,
                    bases,
                    stages,
                    globalActions,
                    uccs.isIncrementalPruningCleanup(),
                    uccs.isSafetyBackwardPruning(),
                    uccs.getUpdateGRGoal().getControllableActions(),
                    counter,
                    output);
        } else {
            List<DelayedEnv> localSafetyEnvironments = new ArrayList<DelayedEnv>();
            for (StageBase base : bases) {
                StepwiseStage stage = base.stage;
                int stageIndex = stage.getIndex();
                List<StepwiseClassifiedGoal> localGoals = new ArrayList<StepwiseClassifiedGoal>();
                localGoals.addAll(classification.getOldSafety(stageIndex));
                localGoals.addAll(classification.getNewSafety(stageIndex));
                localGoals.addAll(classification.getTransitions(stageIndex));

                output.outln("");
                output.outln("[Stepwise Delayed DUCS] Stage " + stage.getDisplayIndex()
                        + " old=" + stage.getOldEnvironmentName()
                        + " new=" + stage.getNewEnvironmentName()
                        + " relation=" + stage.getMapRelationName());
                outputGoalNames(output, "local old safety", classification.getOldSafety(stageIndex));
                outputGoalNames(output, "local new safety", classification.getNewSafety(stageIndex));
                outputGoalNames(output, "local transition", classification.getTransitions(stageIndex));
                outputStateSpace(output, "  mapping before pruning", base.mapping);

                // This is the first valued representation of the raw mapping
                // component, so local and phase fluents are built once here.
                // Delta extension starts only after valued fragments exist.
                addPassiveSelfLoopsForMissingActions(base.mapping, globalActions);
                Set<Fluent> trackedFluents =
                        StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                                localGoals,
                                globalActions);
                addPhaseComparisonFluents(trackedFluents);
                Set<Integer> localScope = singletonStageScope(stageIndex);
                recordScopedFluentCount(
                        "local stage " + stage.getDisplayIndex() + " tracked fluent 数",
                        localScope,
                        trackedFluents);
                DelayedEnv meta = timedBuildFluentProduct(
                        base.mapping,
                        base.stageMetadata,
                        Collections.<Long, Long>emptyMap(),
                        trackedFluents,
                        localScope,
                        "local stage " + stage.getDisplayIndex() + " mapping component から local metaEnv 構築時間");
                StateSpaceStats localMetaStats = outputStateSpace(output, "  mapping meta", meta.env);
                recordScopedStateSpace("local metaEnv", localScope, localMetaStats);
                if (phaseMemoryDiagnostics) {
                    UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                            "stepwise.local.stage." + stage.getDisplayIndex() + ".meta_ready");
                }

                DelayedEnv safety = timedPruneSafety(
                        meta,
                        localGoals,
                        "local stage " + stage.getDisplayIndex(),
                        localScope,
                        "local stage " + stage.getDisplayIndex() + " local metaEnv から local safetyEnv 構築時間",
                        output);
                if (uccs.isSafetyBackwardPruning()) {
                    safety = applyDeferredSafetyBackwardPruning(
                            safety,
                            uccs.getUpdateGRGoal().getControllableActions(),
                            actionOwnership.asMap(),
                            localScope,
                            "local stage " + stage.getDisplayIndex(),
                            output);
                }
                StateSpaceStats localSafetyStats = outputStateSpace(output, "  mapping safety", safety.env);
                recordScopedStateSpace("local safetyEnv", localScope, localSafetyStats);
                if (phaseMemoryDiagnostics) {
                    UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                            "stepwise.local.stage." + stage.getDisplayIndex()
                                    + ".safety_sbp_ready");
                }
                DelayedEnv localSafetyEnvironment = new DelayedEnv(
                        safety.env,
                        safety.trackedFluents,
                        safety.valuation,
                        safety.stageMetadata,
                        safety.oldControllerOrigin,
                        base.realActions,
                        safety.errorStates);
                if (DEBUG_HOT_SWAP_LINEAGE) {
                    debugRegisterLocalSafetyEnvironment(localSafetyEnvironment, stageIndex);
                    debugSemanticMultiplicity(
                            "local_stage_" + stage.getDisplayIndex() + "_safety",
                            localSafetyEnvironment,
                            output);
                }
                localSafetyEnvironments.add(localSafetyEnvironment);
            }

            outputCrossComponents(output, crossComponents);

            List<DelayedEnv> finalProductInputs = new ArrayList<DelayedEnv>();
            List<String> finalProductLabels = new ArrayList<String>();
            Set<Integer> stagesCoveredByCrossComponents = new HashSet<Integer>();
            CrossSchedulingStats totalSchedulingStats = new CrossSchedulingStats();
            for (CrossComponent component : crossComponents) {
                DelayedEnv componentSafetyEnv =
                        buildStagedCrossComponentEnvironment(
                                component,
                                localSafetyEnvironments,
                                bases,
                                stages,
                                globalActions,
                                uccs.isSafetyBackwardPruning(),
                                uccs.getUpdateGRGoal().getControllableActions(),
                                actionOwnership.asMap(),
                                totalSchedulingStats,
                                output);
                if (phaseMemoryDiagnostics) {
                    UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                            "stepwise.cross.component." + component.getId() + ".ready");
                }
                finalProductInputs.add(componentSafetyEnv);
                finalProductLabels.add("cross component " + component.getId()
                        + " stages=" + displayStageScope(component.getStageScope()));
                stagesCoveredByCrossComponents.addAll(component.getStageScope());
            }
            recordCrossSchedulingSummary("all components", totalSchedulingStats);
            for (int i = 0; i < localSafetyEnvironments.size(); i++) {
                if (!stagesCoveredByCrossComponents.contains(i)) {
                    finalProductInputs.add(localSafetyEnvironments.get(i));
                    finalProductLabels.add("stage " + stages.get(i).getDisplayIndex());
                }
            }

            output.outln("");
            output.outln("[Stepwise Delayed DUCS] Final product inputs: " + finalProductLabels);
            mappingProduct = timedComposeProduct(
                    finalProductInputs,
                    "STEPWISE_DELAYED_MAPPING_PRODUCT",
                    allStageScope(stages.size()),
                    "final product 並列合成時間",
                    output);
        }
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.mapping.final_product_ready");
        }
        if (uccs.isSafetyBackwardPruning() && !uccs.isIncrementalPruning()) {
            SafetyBackwardPruner.Result finalMappingPruning = SafetyBackwardPruner.prune(
                    mappingProduct.env,
                    uccs.getUpdateGRGoal().getControllableActions(),
                    "stepwise-delayed-final-mapping",
                    output);
            failIfInitialLosing(finalMappingPruning, "stepwise-delayed-final-mapping");
            if (!finalMappingPruning.isInputEnvironmentReused()) {
                mappingProduct = rebuildMetadata(
                        mappingProduct,
                        finalMappingPruning.getEnvironment());
            }
            if (!mappingProduct.errorStates.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed final mapping product retained unresolved Error states: "
                        + mappingProduct.errorStates + ".");
            }
            outputStateSpace(
                    output,
                    "[Stepwise Delayed DUCS] Product after final mapping safety backward pruning",
                    mappingProduct.env);
            if (phaseMemoryDiagnostics) {
                UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                        "stepwise.mapping.final_sbp_ready");
            }
        }
        outputStateSpace(output, "[Stepwise Delayed DUCS] Product before delayed connection", mappingProduct.env);
        if (DEBUG_ACTION_DIAGNOSTICS) {
            debugActionStatistics(
                    "final_mapping_before_connection",
                    mappingProduct,
                    allStageScope(stages.size()),
                    uccs.getUpdateGRGoal().getControllableActions(),
                    actionOwnership.asMap(),
                    output);
        }

        Set<Fluent> allTrackedFluents = new LinkedHashSet<Fluent>(mappingProduct.trackedFluents);
        addPhaseComparisonFluents(allTrackedFluents);
        UpdatingControllerEvaluationRecorder.recordCount(
                "Stepwise Delayed DUC",
                "final tracked fluent 数",
                allTrackedFluents.size(),
                "fluents");
        long oldMetaStart = System.currentTimeMillis();
        DelayedEnv oldMeta = buildOldControllerMeta(oldController, allTrackedFluents);
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC",
                "old controller meta 構築時間",
                System.currentTimeMillis() - oldMetaStart);
        outputStateSpace(output, "[Stepwise Delayed DUCS] OldCon fluent meta", oldMeta.env);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.old_controller.meta_ready");
        }

        ConnectionPlan connectionPlan = buildConnections(oldMeta, mappingProduct, allTrackedFluents, output);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.hot_swap_in.connection_plan_ready");
        }
        if (DEBUG_ACTION_DIAGNOSTICS) {
            debugConnectionMultiplicity(connectionPlan, oldMeta, mappingProduct, output);
        }
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugHotSwapLineage(connectionPlan, oldMeta, mappingProduct, allTrackedFluents, output);
        }
        output.outln("[Stepwise Delayed DUCS] all old-controller states: " + oldMeta.env.getStates().size());
        output.outln("[Stepwise Delayed DUCS] reachable old-controller states: " + oldMeta.env.getStates().size());
        output.outln("[Stepwise Delayed DUCS] hotSwapIn connections: " + connectionPlan.connections.size());
        output.outln("[Stepwise Delayed DUCS] reachable states without connection target: "
                + connectionPlan.oldStatesWithoutTargets.size());
        output.outln("[Stepwise Delayed DUCS] all states without connection target: "
                + connectionPlan.oldStatesWithoutTargets.size());
        if (!connectionPlan.oldStatesWithoutTargets.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed hotSwapIn connection failed for reachable OldCon states: "
                    + connectionPlan.oldStatesWithoutTargets);
        }

        // Return only the objects needed by the full connected-environment
        // copy. Ending this frame first makes classification data, partial
        // products, fluent valuations, stage metadata, and the unused part of
        // ConnectionPlan unreachable before that allocation-heavy copy starts.
        return new ConnectedEnvironmentInputs(
                oldMeta.env,
                mappingProduct.env,
                connectionPlan.connections,
                uccs.getUpdateGRGoal().getControllableActions());
    }

    private static MTS<Long, String> connectPreparedEnvironment(
            ConnectedEnvironmentInputs inputs,
            LTSOutput output,
            boolean phaseMemoryDiagnostics) {
        long connectionEnvironmentStart = System.currentTimeMillis();
        MTS<Long, String> connected;
        if (CONNECTED_ENVIRONMENT_VIEW) {
            try {
                connected = connectOldAndMappingView(
                        inputs.oldEnvironment,
                        inputs.mappingEnvironment,
                        inputs.connections,
                        inputs.controllableActions);
                output.outln("[Stepwise Delayed DUCS] connected environment representation: read-only view");
            } catch (IllegalArgumentException unsupportedViewInput) {
                output.outln("[Stepwise Delayed DUCS] connected environment view unavailable: "
                        + unsupportedViewInput.getMessage()
                        + "; falling back to materialized copy.");
                connected = connectOldAndMapping(
                        inputs.oldEnvironment,
                        inputs.mappingEnvironment,
                        inputs.connections,
                        inputs.controllableActions);
            }
        } else {
            connected = connectOldAndMapping(
                    inputs.oldEnvironment,
                    inputs.mappingEnvironment,
                    inputs.connections,
                    inputs.controllableActions);
            output.outln("[Stepwise Delayed DUCS] connected environment representation: materialized copy");
        }
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC",
                "hotSwapIn connection 後 environment 構築時間",
                System.currentTimeMillis() - connectionEnvironmentStart);
        outputStateSpace(output, "[Stepwise Delayed DUCS] After delayed hotSwapIn connection", connected);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.hot_swap_in.connected_environment_ready");
        }
        return connected;
    }

    private static void outputClassificationStatistics(
            LTSOutput output,
            int stageCount,
            StepwiseGoalClassifier.Classification classification,
            List<StepwiseClassifiedGoal> crossGoals,
            List<CrossComponent> crossComponents) {
        int localOldSafetyGoals = 0;
        int localNewSafetyGoals = 0;
        int localTransitionGoals = 0;
        for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
            localOldSafetyGoals += classification.getOldSafety(stageIndex).size();
            localNewSafetyGoals += classification.getNewSafety(stageIndex).size();
            localTransitionGoals += classification.getTransitions(stageIndex).size();
        }
        int localGoals = localOldSafetyGoals + localNewSafetyGoals + localTransitionGoals;
        int crossOldSafetyGoals = countGoalsByKind(crossGoals, StepwiseRequirementKind.OLD_SAFETY);
        int crossNewSafetyGoals = countGoalsByKind(crossGoals, StepwiseRequirementKind.NEW_SAFETY);
        int crossTransitionGoals = countGoalsByKind(crossGoals, StepwiseRequirementKind.TRANSITION);
        int crossGoalCount = crossGoals.size();
        int totalGoals = localGoals + crossGoalCount;
        double crossGoalRatio = totalGoals == 0 ? 0.0 : ((double) crossGoalCount) / totalGoals;
        long crossGoalRatioPermille = Math.round(crossGoalRatio * 1000.0);
        int maxGoalScopeSize = maxGoalScopeSize(crossGoals);
        int allStageCrossGoalCount = countAllStageCrossGoals(crossGoals, stageCount);
        int maxComponentScopeSize = maxComponentScopeSize(crossComponents);
        int allStageCrossComponentCount = countAllStageCrossComponents(crossComponents, stageCount);
        int requirementFluentCount = uniqueRequirementFluents(stageCount, classification, crossGoals).size();

        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Requirement classification summary");
        output.outln("  stages: " + stageCount);
        output.outln("  goals: total=" + totalGoals
                + " local=" + localGoals
                + " cross=" + crossGoalCount
                + " crossRatio=" + formatPercent(crossGoalRatio));
        output.outln("  local goals by type: oldSafety=" + localOldSafetyGoals
                + " newSafety=" + localNewSafetyGoals
                + " transition=" + localTransitionGoals);
        output.outln("  cross goals by type: oldSafety=" + crossOldSafetyGoals
                + " newSafety=" + crossNewSafetyGoals
                + " transition=" + crossTransitionGoals);
        output.outln("  cross components: count=" + crossComponents.size()
                + " maxGoalScopeSize=" + maxGoalScopeSize
                + " maxComponentScopeSize=" + maxComponentScopeSize
                + " allStageCrossGoals=" + allStageCrossGoalCount
                + " allStageCrossComponents=" + allStageCrossComponentCount);
        output.outln("  requirement fluents: unique=" + requirementFluentCount);

        String section = StepwiseDelayedEvaluationMetrics.CLASSIFICATION_SECTION;
        UpdatingControllerEvaluationRecorder.recordCount(section, "stage 数", stageCount, "stages");
        UpdatingControllerEvaluationRecorder.recordCount(section, "goal 数", totalGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "local goal 数", localGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross goal 数", crossGoalCount, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross goal 比率（千分率）", crossGoalRatioPermille, "per_mille");
        UpdatingControllerEvaluationRecorder.recordCount(section, "local old safety goal 数", localOldSafetyGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "local new safety goal 数", localNewSafetyGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "local transition goal 数", localTransitionGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross old safety goal 数", crossOldSafetyGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross new safety goal 数", crossNewSafetyGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross transition goal 数", crossTransitionGoals, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross component 数", crossComponents.size(), "components");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross goal 最大 scope size", maxGoalScopeSize, "stages");
        UpdatingControllerEvaluationRecorder.recordCount(section, "cross component 最大 scope size", maxComponentScopeSize, "stages");
        UpdatingControllerEvaluationRecorder.recordCount(section, "all-stage cross goal 数", allStageCrossGoalCount, "goals");
        UpdatingControllerEvaluationRecorder.recordCount(section, "all-stage cross component 数", allStageCrossComponentCount, "components");
        UpdatingControllerEvaluationRecorder.recordCount(section, "all-stage cross goal あり", allStageCrossGoalCount > 0 ? 1 : 0, "boolean");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "requirement fluent 数（重複排除後）",
                requirementFluentCount,
                "fluents");
        recordScopeRequirementCounts(output, stageCount, classification, crossGoals);
    }

    private static Set<Fluent> uniqueRequirementFluents(
            int stageCount,
            StepwiseGoalClassifier.Classification classification,
            List<StepwiseClassifiedGoal> crossGoals) {
        Set<Fluent> fluents = new LinkedHashSet<Fluent>();
        for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
            addRequirementFluents(fluents, classification.getOldSafety(stageIndex));
            addRequirementFluents(fluents, classification.getNewSafety(stageIndex));
            addRequirementFluents(fluents, classification.getTransitions(stageIndex));
        }
        addRequirementFluents(fluents, crossGoals);
        return fluents;
    }

    private static void addRequirementFluents(
            Set<Fluent> fluents,
            List<StepwiseClassifiedGoal> goals) {
        for (StepwiseClassifiedGoal goal : goals) {
            fluents.addAll(goal.getFluents());
        }
    }

    private static void recordScopeRequirementCounts(
            LTSOutput output,
            int stageCount,
            StepwiseGoalClassifier.Classification classification,
            List<StepwiseClassifiedGoal> crossGoals) {
        List<ScopeRequirementCounts> counts = new ArrayList<ScopeRequirementCounts>();
        for (int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
            Set<Integer> scope = new java.util.TreeSet<Integer>();
            scope.add(stageIndex);
            ScopeRequirementCounts localCounts = countsForScope(counts, scope);
            addGoalCounts(localCounts, classification.getOldSafety(stageIndex));
            addGoalCounts(localCounts, classification.getNewSafety(stageIndex));
            addGoalCounts(localCounts, classification.getTransitions(stageIndex));
        }
        for (StepwiseClassifiedGoal goal : crossGoals) {
            ScopeRequirementCounts crossCounts = countsForScope(counts, goal.getStageScope());
            crossCounts.add(goal);
        }
        Collections.sort(counts, new java.util.Comparator<ScopeRequirementCounts>() {
            public int compare(ScopeRequirementCounts left, ScopeRequirementCounts right) {
                return compareStageScopes(left.scope, right.scope);
            }
        });
        output.outln("  scope requirement counts:");
        for (ScopeRequirementCounts count : counts) {
            String prefix = "scope " + displayStageScope(count.scope) + " ";
            output.outln("    " + prefix
                    + "total=" + count.totalGoals()
                    + " oldSafety=" + count.oldSafetyGoals
                    + " newSafety=" + count.newSafetyGoals
                    + " transition=" + count.transitionGoals
                    + " requirementFluents=" + count.requirementFluentCount());
            UpdatingControllerEvaluationRecorder.recordCount(
                    StepwiseDelayedEvaluationMetrics.SCOPE_REQUIREMENT_SECTION,
                    prefix + "goal 数",
                    count.totalGoals(),
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    StepwiseDelayedEvaluationMetrics.SCOPE_REQUIREMENT_SECTION,
                    prefix + "old safety goal 数",
                    count.oldSafetyGoals,
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    StepwiseDelayedEvaluationMetrics.SCOPE_REQUIREMENT_SECTION,
                    prefix + "new safety goal 数",
                    count.newSafetyGoals,
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    StepwiseDelayedEvaluationMetrics.SCOPE_REQUIREMENT_SECTION,
                    prefix + "transition goal 数",
                    count.transitionGoals,
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    StepwiseDelayedEvaluationMetrics.SCOPE_FLUENT_SECTION,
                    prefix + "requirement fluent 数（重複排除後）",
                    count.requirementFluentCount(),
                    "fluents");
        }
    }

    private static ScopeRequirementCounts countsForScope(
            List<ScopeRequirementCounts> counts,
            Set<Integer> scope) {
        for (ScopeRequirementCounts count : counts) {
            if (count.scope.equals(scope)) {
                return count;
            }
        }
        ScopeRequirementCounts count = new ScopeRequirementCounts(scope);
        counts.add(count);
        return count;
    }

    private static void addGoalCounts(
            ScopeRequirementCounts counts,
            List<StepwiseClassifiedGoal> goals) {
        for (StepwiseClassifiedGoal goal : goals) {
            counts.add(goal);
        }
    }

    private static int countGoalsByKind(List<StepwiseClassifiedGoal> goals, StepwiseRequirementKind kind) {
        int count = 0;
        for (StepwiseClassifiedGoal goal : goals) {
            if (kind.equals(goal.getKind())) {
                count++;
            }
        }
        return count;
    }

    private static int maxGoalScopeSize(List<StepwiseClassifiedGoal> goals) {
        int max = 0;
        for (StepwiseClassifiedGoal goal : goals) {
            max = Math.max(max, goal.getStageScope().size());
        }
        return max;
    }

    private static int countAllStageCrossGoals(List<StepwiseClassifiedGoal> goals, int stageCount) {
        int count = 0;
        for (StepwiseClassifiedGoal goal : goals) {
            if (goal.getStageScope().size() == stageCount) {
                count++;
            }
        }
        return count;
    }

    private static int maxComponentScopeSize(List<CrossComponent> crossComponents) {
        int max = 0;
        for (CrossComponent component : crossComponents) {
            max = Math.max(max, component.getStageScope().size());
        }
        return max;
    }

    private static int countAllStageCrossComponents(List<CrossComponent> crossComponents, int stageCount) {
        int count = 0;
        for (CrossComponent component : crossComponents) {
            if (component.getStageScope().size() == stageCount) {
                count++;
            }
        }
        return count;
    }

    private static String formatPercent(double ratio) {
        return String.format(Locale.ROOT, "%.2f%%", ratio * 100.0);
    }

    private static List<DelayedEnv> buildIncrementalLocalEnvironments(
            List<StageBase> bases,
            List<StepwiseStage> stages,
            StepwiseGoalClassifier.Classification classification,
            Set<String> globalActions,
            boolean cleanup,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            IncrementalPruningCounter counter,
            LTSOutput output) {
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Incremental local pruning");

        List<DelayedEnv> currentByStage = new ArrayList<DelayedEnv>();
        for (StageBase base : bases) {
            StepwiseStage stage = base.stage;
            int stageIndex = stage.getIndex();

            output.outln("");
            output.outln("[Stepwise Delayed DUCS] Stage " + stage.getDisplayIndex()
                    + " old=" + stage.getOldEnvironmentName()
                    + " new=" + stage.getNewEnvironmentName()
                    + " relation=" + stage.getMapRelationName());
            outputGoalNames(output, "local old safety", classification.getOldSafety(stageIndex));
            outputGoalNames(output, "local new safety", classification.getNewSafety(stageIndex));
            outputGoalNames(output, "local transition", classification.getTransitions(stageIndex));
            outputStateSpace(output, "  mapping before pruning", base.mapping);

            addPassiveSelfLoopsForMissingActions(base.mapping, globalActions);
            Set<Fluent> phaseFluents = new LinkedHashSet<Fluent>();
            addPhaseComparisonFluents(phaseFluents);
            Set<Integer> localScope = singletonStageScope(stageIndex);
            recordScopedFluentCount(
                    "incremental local stage " + stage.getDisplayIndex() + " phase tracked fluent 数",
                    localScope,
                    phaseFluents);
            DelayedEnv phaseMeta = timedBuildFluentProduct(
                    base.mapping,
                    base.stageMetadata,
                    Collections.<Long, Long>emptyMap(),
                    phaseFluents,
                    localScope,
                    "incremental local stage " + stage.getDisplayIndex() + " phase metaEnv 構築時間");
            DelayedEnv current = new DelayedEnv(
                    phaseMeta.env,
                    phaseMeta.trackedFluents,
                    phaseMeta.valuation,
                    phaseMeta.stageMetadata,
                    phaseMeta.oldControllerOrigin,
                    base.realActions);
            StateSpaceStats phaseMetaStats = outputStateSpace(output, "  mapping phase meta", current.env);
            recordScopedStateSpace("incremental local phase metaEnv", localScope, phaseMetaStats);
            currentByStage.add(current);
        }

        List<StepwiseClassifiedGoal> localGoals = sortedLocalGoals(classification, stages.size());
        for (StepwiseClassifiedGoal goal : localGoals) {
            int stageIndex = goal.getStageIndex();
            DelayedEnv current = currentByStage.get(stageIndex);
            Set<Integer> productScope = new java.util.TreeSet<Integer>(goal.getStageScope());
            current = pruneIncrementalGoal(
                    current,
                    goal,
                    "local",
                    goal.getStageScope(),
                    productScope,
                    Collections.<Integer>emptySet(),
                    globalActions,
                    cleanup,
                    safetyBackwardPruning,
                    controllableActions,
                    counter,
                    output);
            currentByStage.set(stageIndex, current);
        }

        for (int i = 0; i < currentByStage.size(); i++) {
            StateSpaceStats localSafetyStats = outputStateSpace(output,
                    "[Stepwise Delayed DUCS] incremental local safety stage "
                            + stages.get(i).getDisplayIndex(),
                    currentByStage.get(i).env);
            recordScopedStateSpace(
                    "incremental local final safetyEnv",
                    singletonStageScope(i),
                    localSafetyStats);
        }
        return currentByStage;
    }

    private static DelayedEnv buildIncrementalCrossAndFinalProduct(
            List<CrossComponent> crossComponents,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
            Set<String> globalActions,
            boolean cleanup,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            IncrementalPruningCounter counter,
            LTSOutput output) {
        outputCrossComponents(output, crossComponents);

        List<DelayedEnv> finalProductInputs = new ArrayList<DelayedEnv>();
        List<String> finalProductLabels = new ArrayList<String>();
        Set<Integer> stagesCoveredByCrossComponents = new HashSet<Integer>();
        for (CrossComponent component : crossComponents) {
            DelayedEnv componentSafetyEnv = buildIncrementalCrossComponentEnvironment(
                    component,
                    localSafetyEnvironments,
                    bases,
                    stages,
                    globalActions,
                    cleanup,
                    safetyBackwardPruning,
                    controllableActions,
                    counter,
                    output);
            finalProductInputs.add(componentSafetyEnv);
            finalProductLabels.add("incremental cross component " + component.getId()
                    + " stages=" + displayStageScope(component.getStageScope()));
            stagesCoveredByCrossComponents.addAll(component.getStageScope());
        }
        for (int i = 0; i < localSafetyEnvironments.size(); i++) {
            if (!stagesCoveredByCrossComponents.contains(i)) {
                finalProductInputs.add(localSafetyEnvironments.get(i));
                finalProductLabels.add("stage " + stages.get(i).getDisplayIndex());
            }
        }

        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Final product inputs: " + finalProductLabels);
        return timedComposeProduct(
                finalProductInputs,
                "STEPWISE_DELAYED_INCREMENTAL_MAPPING_PRODUCT",
                allStageScope(stages.size()),
                "incremental final product 並列合成時間",
                output);
    }

    private static DelayedEnv buildIncrementalCrossComponentEnvironment(
            CrossComponent component,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
            Set<String> globalActions,
            boolean cleanup,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            IncrementalPruningCounter counter,
            LTSOutput output) {
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Incremental cross component " + component.getId());
        output.outln("  stages: " + displayStageScope(component.getStageScope()));
        outputGoalNames(output, "goals", component.getGoals());

        DelayedEnv current = null;
        Set<Integer> currentScope = new java.util.TreeSet<Integer>();
        List<StepwiseClassifiedGoal> goals = sortedGoalsByScope(component.getGoals());
        for (StepwiseClassifiedGoal goal : goals) {
            Set<Integer> goalScope = new java.util.TreeSet<Integer>(goal.getStageScope());
            if (goalScope.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed incremental cross goal has empty stage scope: "
                        + goal.getName() + ".");
            }

            Set<Integer> addedStages = new java.util.TreeSet<Integer>(goalScope);
            addedStages.removeAll(currentScope);
            if (current == null) {
                currentScope.addAll(goalScope);
                current = composeStageScope(
                        localSafetyEnvironments,
                        currentScope,
                        "STEPWISE_DELAYED_INCREMENTAL_CROSS_COMPONENT_" + component.getId()
                                + "_INITIAL_PRODUCT",
                        output);
                addedStages = new java.util.TreeSet<Integer>(currentScope);
                for (Integer stageIndex : currentScope) {
                    outputStateSpace(output, "  input mapping safety stage "
                            + stages.get(stageIndex).getDisplayIndex(),
                            localSafetyEnvironments.get(stageIndex).env);
                }
            } else if (!addedStages.isEmpty()) {
                List<DelayedEnv> productInputs = new ArrayList<DelayedEnv>();
                productInputs.add(current);
                for (Integer stageIndex : addedStages) {
                    productInputs.add(localSafetyEnvironments.get(stageIndex));
                    outputStateSpace(output, "  added mapping safety stage "
                            + stages.get(stageIndex).getDisplayIndex(),
                            localSafetyEnvironments.get(stageIndex).env);
                }
                Set<Integer> mergedScope = new java.util.TreeSet<Integer>(currentScope);
                mergedScope.addAll(addedStages);
                current = timedComposeProduct(
                        productInputs,
                        "STEPWISE_DELAYED_INCREMENTAL_CROSS_COMPONENT_" + component.getId()
                                + "_ADD_" + displayStageScope(addedStages),
                        mergedScope,
                        "incremental cross component " + component.getId() + " stage 追加 product 並列合成時間",
                        output);
                currentScope.addAll(addedStages);
            }

            if (!currentScope.containsAll(goalScope)) {
                Diagnostics.fatal("stepwise_delayed incremental cross goal scope is not covered: "
                        + goal.getName() + " scope=" + displayStageScope(goalScope)
                        + " productScope=" + displayStageScope(currentScope) + ".");
            }
            Set<String> outOfScopeActions = outOfScopeActionsForScope(currentScope, bases);
            requirePassiveActionAlphabet(
                    current,
                    outOfScopeActions,
                    "incremental cross component " + component.getId());
            current = new DelayedEnv(
                    current.env,
                    current.trackedFluents,
                    current.valuation,
                    current.stageMetadata,
                    current.oldControllerOrigin,
                    realActionsForScope(currentScope, bases),
                    current.errorStates);
            current = pruneIncrementalGoal(
                    current,
                    goal,
                    "cross",
                    goalScope,
                    currentScope,
                    addedStages,
                    globalActions,
                    cleanup,
                    safetyBackwardPruning,
                    controllableActions,
                    counter,
                    output);
        }

        if (current == null) {
            Diagnostics.fatal("stepwise_delayed incremental cross component has no goals.");
        }
        requirePassiveActionAlphabet(
                current,
                outOfScopeActionsForScope(component.getStageScope(), bases),
                "incremental cross component " + component.getId() + " final");
        StateSpaceStats componentSafetyStats =
                outputStateSpace(output, "  incremental cross component safety", current.env);
        recordScopedStateSpace(
                "incremental cross component " + component.getId() + " final safetyEnv",
                component.getStageScope(),
                componentSafetyStats);
        return new DelayedEnv(
                current.env,
                current.trackedFluents,
                current.valuation,
                current.stageMetadata,
                current.oldControllerOrigin,
                realActionsForScope(component.getStageScope(), bases),
                current.errorStates);
    }

    private static DelayedEnv buildStagedCrossComponentEnvironment(
            CrossComponent component,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
            Set<String> globalActions,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            CrossSchedulingStats totalSchedulingStats,
            LTSOutput output) {
        final boolean phaseMemoryDiagnostics =
                UpdatingControllerEvaluationRecorder.isPhaseMemoryDiagnosticsEnabled();
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Staged cross component " + component.getId());
        output.outln("  stages: " + displayStageScope(component.getStageScope()));
        outputGoalNames(output, "goals", component.getGoals());
        output.outln("  cross goal scheduler: "
                + (COST_GUIDED_STAGED_CROSS_SCHEDULING ? "cost-guided" : "fixed-order"));

        List<ScopedDelayedEnv> fragments = initialFragmentsForScope(
                component.getStageScope(),
                localSafetyEnvironments,
                stages,
                output);
        List<StepwiseClassifiedGoal> remaining = sortedGoalsByScope(component.getGoals());
        CrossSchedulingStats componentSchedulingStats = new CrossSchedulingStats();
        componentSchedulingStats.componentCount = 1;
        int step = 1;
        while (!remaining.isEmpty()) {
            long schedulingStart = System.nanoTime();
            CrossGoalScheduleChoice schedule = COST_GUIDED_STAGED_CROSS_SCHEDULING
                    ? selectNextCrossGoalCostGuided(remaining, fragments)
                    : selectNextCrossGoalFixedOrder(remaining, fragments);
            long schedulingNanos = System.nanoTime() - schedulingStart;
            StepwiseClassifiedGoal seed = schedule.goal;
            Set<Integer> seedScope = new java.util.TreeSet<Integer>(seed.getStageScope());
            if (seedScope.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed staged cross goal has empty stage scope: "
                        + seed.getName() + ".");
            }
            long detailRecordCountTime = recordCrossSchedulingStepDetail(
                    component.getId(),
                    step,
                    schedule,
                    schedulingNanos);
            componentSchedulingStats.addChoice(schedule, schedulingNanos, detailRecordCountTime);

            output.outln("  selected cross goal: " + seed.getName()
                    + " scope=" + displayStageScope(seedScope)
                    + " schedulingCost=" + schedule.cost
                    + " candidateBatchGoals=" + schedule.batchGoalCount
                    + " estimatedMergedScope=" + displayStageScope(schedule.mergedScope));
            List<ScopedDelayedEnv> selected = schedule.selectedFragments;
            ScopedDelayedEnv merged = mergeFragments(
                    selected,
                    component.getId(),
                    step,
                    output);
            if (phaseMemoryDiagnostics) {
                UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                        "stepwise.cross.component." + component.getId()
                                + ".step." + step + ".merge_ready");
            }

            if (DEBUG_ACTION_DIAGNOSTICS) {
                debugActionStatistics(
                        "cross_component_" + component.getId() + "_step_" + step + "_after_merge",
                        merged.env,
                        merged.stageScope,
                        controllableActions,
                        ownersByAction,
                        output);
            }

            List<StepwiseClassifiedGoal> batch = goalsCoveredByScope(remaining, merged.stageScope);
            if (batch.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed staged cross goal scope is not covered: "
                        + seed.getName() + " scope=" + displayStageScope(seedScope)
                        + " productScope=" + displayStageScope(merged.stageScope) + ".");
            }
            remaining.removeAll(batch);

            Set<String> outOfScopeActions = outOfScopeActionsForScope(merged.stageScope, bases);
            requirePassiveActionAlphabet(
                    merged.env,
                    outOfScopeActions,
                    "cross component " + component.getId() + " step " + step);
            if (DEBUG_ACTION_DIAGNOSTICS) {
                String checkpoint = "cross_component_" + component.getId()
                        + "_step_" + step + "_after_passive";
                debugPassiveTransitionInvariant(
                        checkpoint,
                        merged.env,
                        merged.stageScope,
                        outOfScopeActions,
                        output);
                debugActionStatistics(
                        checkpoint,
                        merged.env,
                        merged.stageScope,
                        controllableActions,
                        ownersByAction,
                        output);
            }
            DelayedEnv scopedEnv = new DelayedEnv(
                    merged.env.env,
                    merged.env.trackedFluents,
                    merged.env.valuation,
                    merged.env.stageMetadata,
                    merged.env.oldControllerOrigin,
                    realActionsForScope(merged.stageScope, bases),
                    merged.env.errorStates);
            DelayedEnv pruned = pruneStagedCrossGoals(
                    scopedEnv,
                    batch,
                    component.getId(),
                    step,
                    merged.stageScope,
                    merged.addedStageScope,
                    globalActions,
                    safetyBackwardPruning,
                    controllableActions,
                    ownersByAction,
                    output);
            if (phaseMemoryDiagnostics) {
                UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                        "stepwise.cross.component." + component.getId()
                                + ".step." + step + ".safety_sbp_ready");
            }
            fragments.removeAll(selected);
            // Every stage in this fragment has now participated in cross
            // pruning; a later merge must not report it as newly added again.
            fragments.add(new ScopedDelayedEnv(
                    merged.stageScope,
                    pruned,
                    Collections.<Integer>emptySet()));
            sortFragmentsByScope(fragments);
            step++;
        }
        recordCrossSchedulingSummary(
                "component " + component.getId(),
                componentSchedulingStats);
        if (totalSchedulingStats != null) {
            totalSchedulingStats.add(componentSchedulingStats);
        }

        if (fragments.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross component has no goals.");
        }
        ScopedDelayedEnv finalFragment = mergeFragments(
                fragments,
                component.getId(),
                step,
                output);
        requirePassiveActionAlphabet(
                finalFragment.env,
                outOfScopeActionsForScope(component.getStageScope(), bases),
                "cross component " + component.getId() + " final");
        DelayedEnv finalComponent = finalFragment.env;
        if (safetyBackwardPruning) {
            finalComponent = applyDeferredSafetyBackwardPruning(
                    finalComponent,
                    controllableActions,
                    ownersByAction,
                    component.getStageScope(),
                    "cross component " + component.getId() + " final merge",
                    output);
        }
        StateSpaceStats finalComponentSafetyStats =
                outputStateSpace(output, "  staged cross component safety", finalComponent.env);
        recordScopedStateSpace(
                "cross component " + component.getId() + " final safetyEnv",
                component.getStageScope(),
                finalComponentSafetyStats);
        if (phaseMemoryDiagnostics) {
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "stepwise.cross.component." + component.getId() + ".final_ready");
        }
        return new DelayedEnv(
                finalComponent.env,
                finalComponent.trackedFluents,
                finalComponent.valuation,
                finalComponent.stageMetadata,
                finalComponent.oldControllerOrigin,
                realActionsForScope(component.getStageScope(), bases),
                finalComponent.errorStates);
    }

    private static List<ScopedDelayedEnv> initialFragmentsForScope(
            Set<Integer> componentScope,
            List<DelayedEnv> localSafetyEnvironments,
            List<StepwiseStage> stages,
            LTSOutput output) {
        List<ScopedDelayedEnv> fragments = new ArrayList<ScopedDelayedEnv>();
        for (Integer stageIndex : componentScope) {
            Set<Integer> scope = new java.util.TreeSet<Integer>();
            scope.add(stageIndex);
            DelayedEnv env = localSafetyEnvironments.get(stageIndex);
            outputStateSpace(output, "  initial fragment stage "
                    + stages.get(stageIndex).getDisplayIndex(), env.env);
            fragments.add(new ScopedDelayedEnv(scope, env, scope));
        }
        sortFragmentsByScope(fragments);
        return fragments;
    }

    private static List<ScopedDelayedEnv> fragmentsIntersecting(
            List<ScopedDelayedEnv> fragments,
            Set<Integer> goalScope) {
        List<ScopedDelayedEnv> selected = new ArrayList<ScopedDelayedEnv>();
        for (ScopedDelayedEnv fragment : fragments) {
            if (intersects(fragment.stageScope, goalScope)) {
                selected.add(fragment);
            }
        }
        return selected;
    }

    private static boolean intersects(Set<Integer> left, Set<Integer> right) {
        for (Integer stageIndex : left) {
            if (right.contains(stageIndex)) {
                return true;
            }
        }
        return false;
    }

    private static ScopedDelayedEnv mergeFragments(
            List<ScopedDelayedEnv> fragments,
            int componentId,
            int step,
            LTSOutput output) {
        if (fragments.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross merge has no fragments.");
        }
        if (fragments.size() == 1) {
            ScopedDelayedEnv fragment = fragments.get(0);
            return new ScopedDelayedEnv(
                    fragment.stageScope,
                    fragment.env,
                    fragment.addedStageScope);
        }

        Set<Integer> mergedScope = new java.util.TreeSet<Integer>();
        Set<Integer> addedStageScope = new java.util.TreeSet<Integer>();
        List<DelayedEnv> inputs = new ArrayList<DelayedEnv>();
        for (ScopedDelayedEnv fragment : fragments) {
            mergedScope.addAll(fragment.stageScope);
            addedStageScope.addAll(fragment.addedStageScope);
            inputs.add(fragment.env);
        }
        DelayedEnv merged = timedComposeProduct(
                inputs,
                "STEPWISE_DELAYED_STAGED_CROSS_COMPONENT_" + componentId
                        + "_STEP_" + step + "_MERGE_" + displayStageScope(mergedScope),
                mergedScope,
                "cross component " + componentId + " step " + step + " safetyEnv fragment 並列合成時間",
                output);
        return new ScopedDelayedEnv(mergedScope, merged, addedStageScope);
    }

    private static void sortFragmentsByScope(List<ScopedDelayedEnv> fragments) {
        Collections.sort(fragments, new java.util.Comparator<ScopedDelayedEnv>() {
            public int compare(ScopedDelayedEnv left, ScopedDelayedEnv right) {
                return compareStageScopes(left.stageScope, right.stageScope);
            }
        });
    }

    private static List<StepwiseClassifiedGoal> goalsCoveredByScope(
            List<StepwiseClassifiedGoal> goals,
            Set<Integer> scope) {
        List<StepwiseClassifiedGoal> result = new ArrayList<StepwiseClassifiedGoal>();
        for (StepwiseClassifiedGoal goal : goals) {
            if (scope.containsAll(goal.getStageScope())) {
                result.add(goal);
            }
        }
        return result;
    }

    private static CrossGoalScheduleChoice selectNextCrossGoalFixedOrder(
            List<StepwiseClassifiedGoal> remainingGoals,
            List<ScopedDelayedEnv> fragments) {
        if (remainingGoals.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross scheduler has no remaining goals.");
        }
        return buildCrossGoalScheduleChoice(remainingGoals.get(0), remainingGoals, fragments, 0, 1);
    }

    private static CrossGoalScheduleChoice selectNextCrossGoalCostGuided(
            List<StepwiseClassifiedGoal> remainingGoals,
            List<ScopedDelayedEnv> fragments) {
        if (remainingGoals.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross scheduler has no remaining goals.");
        }
        CrossGoalScheduleChoice best = null;
        for (int i = 0; i < remainingGoals.size(); i++) {
            CrossGoalScheduleChoice candidate = buildCrossGoalScheduleChoice(
                    remainingGoals.get(i),
                    remainingGoals,
                    fragments,
                    i,
                    remainingGoals.size());
            if (best == null || compareCrossGoalScheduleChoices(candidate, best) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static CrossGoalScheduleChoice buildCrossGoalScheduleChoice(
            StepwiseClassifiedGoal goal,
            List<StepwiseClassifiedGoal> remainingGoals,
            List<ScopedDelayedEnv> fragments,
            int fixedOrderIndex,
            int candidateEvaluationCount) {
        Set<Integer> goalScope = new java.util.TreeSet<Integer>(goal.getStageScope());
        if (goalScope.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross goal has empty stage scope: "
                    + goal.getName() + ".");
        }
        List<ScopedDelayedEnv> selectedFragments = fragmentsIntersecting(fragments, goalScope);
        if (selectedFragments.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed staged cross goal has no intersecting fragment: "
                    + goal.getName() + " scope=" + displayStageScope(goalScope) + ".");
        }
        Set<Integer> mergedScope = mergedScope(selectedFragments);
        List<StepwiseClassifiedGoal> batchGoals = goalsCoveredByScope(remainingGoals, mergedScope);
        return new CrossGoalScheduleChoice(
                goal,
                selectedFragments,
                mergedScope,
                batchGoals.size(),
                fragmentStateProduct(selectedFragments),
                fixedOrderIndex,
                candidateEvaluationCount);
    }

    private static int compareCrossGoalScheduleChoices(
            CrossGoalScheduleChoice left,
            CrossGoalScheduleChoice right) {
        int cost = left.cost.compareTo(right.cost);
        if (cost != 0) {
            return cost;
        }
        int batchGoals = right.batchGoalCount - left.batchGoalCount;
        if (batchGoals != 0) {
            return batchGoals;
        }
        int scopeSize = left.mergedScope.size() - right.mergedScope.size();
        if (scopeSize != 0) {
            return scopeSize;
        }
        return left.fixedOrderIndex - right.fixedOrderIndex;
    }

    private static Set<Integer> mergedScope(List<ScopedDelayedEnv> fragments) {
        Set<Integer> scope = new java.util.TreeSet<Integer>();
        for (ScopedDelayedEnv fragment : fragments) {
            scope.addAll(fragment.stageScope);
        }
        return scope;
    }

    private static BigInteger fragmentStateProduct(List<ScopedDelayedEnv> fragments) {
        BigInteger product = BigInteger.ONE;
        for (ScopedDelayedEnv fragment : fragments) {
            product = product.multiply(BigInteger.valueOf(fragment.env.env.getStates().size()));
        }
        return product;
    }

    private static long recordCrossSchedulingStepDetail(
            int componentId,
            int step,
            CrossGoalScheduleChoice schedule,
            long schedulingNanos) {
        long recordStart = System.currentTimeMillis();
        String label = "component " + componentId + " step " + step;
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / scheduler mode",
                COST_GUIDED_STAGED_CROSS_SCHEDULING ? "cost-guided" : "fixed-order",
                "text");
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal",
                schedule.goal.getName(),
                "goal");
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal kind",
                schedule.goal.getKind().toString(),
                "kind");
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal scope",
                displayStageScope(schedule.goal.getStageScope()),
                "scope");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / fixed order index",
                schedule.fixedOrderIndex,
                "index");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / candidate evaluations",
                schedule.candidateEvaluationCount,
                "candidates");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected fragment count",
                schedule.selectedFragments.size(),
                "fragments");
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / merged scope",
                displayStageScope(schedule.mergedScope),
                "scope");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / merged scope size",
                schedule.mergedScope.size(),
                "stages");
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected cost",
                schedule.cost.toString(),
                "state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected cost log10",
                log10(schedule.cost),
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / batch goal count",
                schedule.batchGoalCount,
                "goals");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / scheduler selection time",
                schedulingNanos);
        return System.currentTimeMillis() - recordStart;
    }

    private static void recordCrossSchedulingSummary(String label, CrossSchedulingStats stats) {
        if (stats == null) {
            return;
        }
        long recordStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / scheduler mode",
                COST_GUIDED_STAGED_CROSS_SCHEDULING ? "cost-guided" : "fixed-order",
                "text");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / component count",
                stats.componentCount,
                "components");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / scheduling step count",
                stats.stepCount,
                "steps");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / candidate evaluations total",
                stats.candidateEvaluations,
                "candidates");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / non-first selections",
                stats.nonFirstSelections,
                "selections");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / non-first selection rate",
                stats.stepCount == 0 ? 0.0 : ((double) stats.nonFirstSelections) / stats.stepCount,
                "ratio");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / scheduler selection time total",
                stats.selectionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / scheduler selection time average",
                stats.selectionNanos,
                stats.stepCount);
        UpdatingControllerEvaluationRecorder.recordText(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / selected cost max",
                stats.maxCost.toString(),
                "state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / selected cost log10 max",
                stats.maxCostLog10,
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / selected cost log10 average",
                stats.stepCount == 0 ? 0.0 : stats.costLog10Total / stats.stepCount,
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / batch goal count total",
                stats.batchGoalCountTotal,
                "goals");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / batch goal count max",
                stats.batchGoalCountMax,
                "goals");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / batch goal count average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.batchGoalCountTotal) / stats.stepCount,
                "goals/step");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / merged scope size max",
                stats.mergedScopeSizeMax,
                "stages");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / merged scope size average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.mergedScopeSizeTotal) / stats.stepCount,
                "stages/step");
        UpdatingControllerEvaluationRecorder.recordCount(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / selected fragment count max",
                stats.selectedFragmentCountMax,
                "fragments");
        UpdatingControllerEvaluationRecorder.recordDouble(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / selected fragment count average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.selectedFragmentCountTotal) / stats.stepCount,
                "fragments/step");
        stats.metricRecordingCountTimeMillis += System.currentTimeMillis() - recordStart;
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                StepwiseDelayedEvaluationMetrics.CROSS_SCHEDULING_SECTION,
                label + " / scheduling metric recording CountTime",
                stats.metricRecordingCountTimeMillis,
                "cross goal scheduling の step 詳細・集計を CSV に記録するための評価用 CountTime。");
    }

    private static double log10(BigInteger value) {
        if (value == null || value.signum() <= 0) {
            return 0.0;
        }
        String text = value.toString();
        int prefixLength = Math.min(16, text.length());
        double prefix = Double.parseDouble(text.substring(0, prefixLength));
        return Math.log10(prefix) + (text.length() - prefixLength);
    }

    private static DelayedEnv pruneStagedCrossGoals(
            DelayedEnv current,
            List<StepwiseClassifiedGoal> goals,
            int componentId,
            int step,
            Set<Integer> productScope,
            Set<Integer> addedStages,
            Set<String> globalActions,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            LTSOutput output) {
        // The merged fragment already has authoritative valuations for every
        // tracked fluent. Add only fluents first needed by this cross batch.
        Set<Fluent> requiredFluents =
                StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                        goals,
                        globalActions);
        addPhaseComparisonFluents(requiredFluents);
        Set<Fluent> trackedFluents = mergedFluentSet(current.trackedFluents, requiredFluents);
        recordScopedFluentCount(
                "cross component " + componentId + " step " + step + " tracked fluent 数",
                productScope,
                trackedFluents);

        DelayedEnv meta = timedExtendFluentProduct(
                current,
                requiredFluents,
                productScope,
                "cross component " + componentId + " step " + step + " product から cross metaEnv 構築時間");
        if (DEBUG_HOT_SWAP_LINEAGE && meta != current) {
            debugFluentProductConsistency(
                    "cross_component_" + componentId + "_step_" + step,
                    current,
                    meta,
                    output);
        }

        if (DEBUG_ACTION_DIAGNOSTICS) {
            debugActionStatistics(
                    "cross_component_" + componentId + "_step_" + step + "_after_fluent_product",
                    meta,
                    productScope,
                    controllableActions,
                    ownersByAction,
                    output);
        }
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugSemanticMultiplicity(
                    "cross_component_" + componentId + "_step_" + step
                            + "_after_fluent_product",
                    meta,
                    output);
        }

        String scope = "cross component " + componentId + " staged step " + step
                + " productScope=" + displayStageScope(productScope);
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] staged cross step " + step);
        output.outln("  product scope: " + displayStageScope(productScope));
        output.outln("  added stages: " + displayStageScope(addedStages));
        outputGoalNames(output, "  batch goals", goals);
        StateSpaceStats metaStats = outputStateSpace(output, "  before staged cross pruning", meta.env);
        recordScopedStateSpace(
                "cross component " + componentId + " step " + step + " metaEnv",
                productScope,
                metaStats);

        DelayedEnv pruned = timedPruneSafety(
                meta,
                goals,
                scope,
                productScope,
                "cross component " + componentId + " step " + step + " cross metaEnv から cross safetyEnv 構築時間",
                output);
        if (DEBUG_ACTION_DIAGNOSTICS) {
            debugActionStatistics(
                    "cross_component_" + componentId + "_step_" + step + "_after_direct_pruning",
                    pruned,
                    productScope,
                    controllableActions,
                    ownersByAction,
                    output);
        }
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugSemanticMultiplicity(
                    "cross_component_" + componentId + "_step_" + step
                            + "_after_direct_pruning",
                    pruned,
                    output);
        }
        if (safetyBackwardPruning) {
            pruned = applyDeferredSafetyBackwardPruning(
                    pruned,
                    controllableActions,
                    ownersByAction,
                    productScope,
                    scope,
                    output);
        }
        if (DEBUG_ACTION_DIAGNOSTICS) {
            debugActionStatistics(
                    "cross_component_" + componentId + "_step_" + step + "_after_sbp",
                    pruned,
                    productScope,
                    controllableActions,
                    ownersByAction,
                    output);
        }
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugSemanticMultiplicity(
                    "cross_component_" + componentId + "_step_" + step + "_after_sbp",
                    pruned,
                    output);
        }
        StateSpaceStats safetyStats = outputStateSpace(output, "  after staged cross pruning", pruned.env);
        recordScopedStateSpace(
                "cross component " + componentId + " step " + step + " safetyEnv",
                productScope,
                safetyStats);
        return pruned;
    }

    private static DelayedEnv composeStageScope(
            List<DelayedEnv> localSafetyEnvironments,
            Set<Integer> scope,
            String productName,
            LTSOutput output) {
        List<DelayedEnv> inputs = new ArrayList<DelayedEnv>();
        for (Integer stageIndex : scope) {
            inputs.add(localSafetyEnvironments.get(stageIndex));
        }
        return timedComposeProduct(
                inputs,
                productName,
                scope,
                productName + " 並列合成時間",
                output);
    }

    private static DelayedEnv pruneIncrementalGoal(
            DelayedEnv current,
            StepwiseClassifiedGoal goal,
            String localOrCross,
            Set<Integer> goalScope,
            Set<Integer> productScope,
            Set<Integer> addedStages,
            Set<String> globalActions,
            boolean cleanup,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            IncrementalPruningCounter counter,
            LTSOutput output) {
        int step = counter.next();
        Set<Fluent> requiredFluents =
                StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                        Collections.singletonList(goal),
                        globalActions);
        addPhaseComparisonFluents(requiredFluents);
        Set<Fluent> trackedFluents = mergedFluentSet(current.trackedFluents, requiredFluents);

        String stepLabel = "incremental " + localOrCross + " step " + step
                + " " + goal.getKind() + " " + goal.getName();
        recordScopedFluentCount(stepLabel + " tracked fluent 数", productScope, trackedFluents);
        DelayedEnv meta = timedExtendFluentProduct(
                current,
                requiredFluents,
                productScope,
                stepLabel + " metaEnv 構築時間");

        output.outln("");
        output.outln("[Stepwise Delayed DUCS] incremental step " + step);
        output.outln("  local/cross: " + localOrCross);
        output.outln("  scope: " + displayStageScope(goalScope));
        output.outln("  product scope: " + displayStageScope(productScope));
        output.outln("  requirement: " + goal.getKind() + " " + goal.getName());
        output.outln("  added stages: " + displayStageScope(addedStages));
        StateSpaceStats metaStats = outputStateSpace(output, "  before pruning", meta.env);
        recordScopedStateSpace(stepLabel + " metaEnv", productScope, metaStats);

        String pruningScope = "incremental " + localOrCross + " " + goal.getKind() + " " + goal.getName();
        DelayedEnv pruned = timedPruneSafety(
                meta,
                Collections.singletonList(goal),
                pruningScope,
                productScope,
                stepLabel + " safetyEnv 構築時間",
                null,
                false);
        StateSpaceStats safetyStats = outputStateSpace(output, "  after pruning", pruned.env);
        recordScopedStateSpace(stepLabel + " safetyEnv", productScope, safetyStats);
        if (safetyBackwardPruning) {
            pruned = applySafetyBackwardPruning(
                    pruned,
                    controllableActions,
                    pruningScope,
                    output);
            StateSpaceStats sbpStats = outputStateSpace(output, "  after safety backward pruning", pruned.env);
            recordScopedStateSpace(stepLabel + " safetyEnv after SBP", productScope, sbpStats);
        }
        if (!cleanup) {
            return pruned;
        }

        long cleanupStart = System.currentTimeMillis();
        DelayedEnv cleaned = cleanupReachable(pruned);
        recordScopedTime(stepLabel + " reachable cleanup 時間",
                productScope,
                System.currentTimeMillis() - cleanupStart);
        StateSpaceStats cleanupStats = outputStateSpace(output, "  after cleanup", cleaned.env);
        recordScopedStateSpace(stepLabel + " safetyEnv after cleanup", productScope, cleanupStats);
        return cleaned;
    }

    private static DelayedEnv applySafetyBackwardPruning(
            DelayedEnv env,
            Set<String> controllableActions,
            String scope,
            LTSOutput output) {
        SafetyBackwardPruner.Result result = SafetyBackwardPruner.prune(
                env.env,
                controllableActions,
                scope,
                output);
        failIfInitialLosing(result, scope);
        if (result.isInputEnvironmentReused()) {
            return env;
        }
        return rebuildMetadata(env, result.getEnvironment());
    }

    private static DelayedEnv applyDeferredSafetyBackwardPruning(
            DelayedEnv env,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            Set<Integer> currentScope,
            String scope,
            LTSOutput output) {
        SafetyBackwardPruner.DeferredResult result = SafetyBackwardPruner.pruneDeferred(
                env.env,
                env.errorStates,
                controllableActions,
                ownersByAction,
                currentScope,
                scope,
                output);
        failIfInitialLosing(result, scope);
        if (result.isInputEnvironmentReused()
                && env.errorStates.equals(result.getErrorStates())) {
            return env;
        }
        return rebuildMetadata(env, result.getEnvironment(), result.getErrorStates());
    }

    private static void failIfInitialLosing(SafetyBackwardPruner.Result result, String scope) {
        if (result.isInitialLosing()) {
            Diagnostics.fatal("stepwise_delayed safety backward pruning found initial state losing in "
                    + scope + ".");
        }
    }

    private static void failIfInitialLosing(
            SafetyBackwardPruner.DeferredResult result,
            String scope) {
        if (result.isInitialLosing()) {
            Diagnostics.fatal("stepwise_delayed deferred safety backward pruning found initial state losing in "
                    + scope + ".");
        }
    }

    private static DelayedEnv cleanupReachable(DelayedEnv env) {
        MTS<Long, String> result = new MTSImpl<Long, String>(env.env.getInitialState());
        for (Long state : env.env.getStates()) {
            result.addState(state);
        }
        for (String action : env.env.getActions()) {
            result.addAction(action);
        }
        for (Long state : env.env.getStates()) {
            for (Pair<String, Long> transition : env.env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                result.addState(transition.getSecond());
                result.addAction(transition.getFirst());
                result.addRequired(state, transition.getFirst(), transition.getSecond());
            }
            for (Pair<String, Long> transition : env.env.getTransitions(state, MTS.TransitionType.MAYBE)) {
                result.addState(transition.getSecond());
                result.addAction(transition.getFirst());
                result.addPossible(state, transition.getFirst(), transition.getSecond());
            }
        }
        result.removeUnreachableStates();
        return rebuildMetadata(env, result);
    }

    private static DelayedEnv rebuildMetadata(DelayedEnv source, MTS<Long, String> result) {
        return rebuildMetadata(source, result, source.errorStates);
    }

    private static DelayedEnv rebuildMetadata(
            DelayedEnv source,
            MTS<Long, String> result,
            Set<Long> errorStates) {
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(result.getStates());
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        Map<Long, Long> oldOrigins = new HashMap<Long, Long>();
        for (Long state : result.getStates()) {
            Set<Fluent> stateFluents = source.valuation.getFluentsFromState(state);
            if (stateFluents != null) {
                for (Fluent fluent : stateFluents) {
                    valuation.addHoldingFluent(state, fluent);
                }
            }
            if (source.stageMetadata.containsKey(state)) {
                metadata.put(state,
                        new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>(
                                source.stageMetadata.get(state)));
            }
            if (source.oldControllerOrigin.containsKey(state)) {
                oldOrigins.put(state, source.oldControllerOrigin.get(state));
            }
        }
        DelayedEnv rebuilt = new DelayedEnv(
                result,
                source.trackedFluents,
                valuation,
                metadata,
                oldOrigins,
                source.realActions,
                errorStates);
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugCopyLocalStateVectors(source.env, result);
        }
        return rebuilt;
    }

    private static List<StepwiseClassifiedGoal> sortedLocalGoals(
            StepwiseGoalClassifier.Classification classification,
            int stageCount) {
        List<StepwiseClassifiedGoal> goals = new ArrayList<StepwiseClassifiedGoal>();
        for (int i = 0; i < stageCount; i++) {
            goals.addAll(classification.getOldSafety(i));
            goals.addAll(classification.getNewSafety(i));
            goals.addAll(classification.getTransitions(i));
        }
        return sortedGoals(goals);
    }

    private static List<StepwiseClassifiedGoal> sortedGoals(Collection<StepwiseClassifiedGoal> goals) {
        List<StepwiseClassifiedGoal> result = new ArrayList<StepwiseClassifiedGoal>(goals);
        Collections.sort(result, new java.util.Comparator<StepwiseClassifiedGoal>() {
            public int compare(StepwiseClassifiedGoal left, StepwiseClassifiedGoal right) {
                int kind = kindOrder(left.getKind()) - kindOrder(right.getKind());
                if (kind != 0) {
                    return kind;
                }
                int name = left.getName().compareTo(right.getName());
                if (name != 0) {
                    return name;
                }
                return compareStageScopes(left.getStageScope(), right.getStageScope());
            }
        });
        return result;
    }

    private static List<StepwiseClassifiedGoal> sortedGoalsByScope(Collection<StepwiseClassifiedGoal> goals) {
        List<StepwiseClassifiedGoal> result = new ArrayList<StepwiseClassifiedGoal>(goals);
        Collections.sort(result, new java.util.Comparator<StepwiseClassifiedGoal>() {
            public int compare(StepwiseClassifiedGoal left, StepwiseClassifiedGoal right) {
                int scopeSize = left.getStageScope().size() - right.getStageScope().size();
                if (scopeSize != 0) {
                    return scopeSize;
                }
                int scope = compareStageScopes(left.getStageScope(), right.getStageScope());
                if (scope != 0) {
                    return scope;
                }
                int kind = kindOrder(left.getKind()) - kindOrder(right.getKind());
                if (kind != 0) {
                    return kind;
                }
                return left.getName().compareTo(right.getName());
            }
        });
        return result;
    }

    private static int kindOrder(StepwiseRequirementKind kind) {
        if (StepwiseRequirementKind.OLD_SAFETY.equals(kind)) {
            return 0;
        }
        if (StepwiseRequirementKind.NEW_SAFETY.equals(kind)) {
            return 1;
        }
        return 2;
    }

    private static void sortCrossComponentsByStageSet(List<CrossComponent> components) {
        Collections.sort(components, new java.util.Comparator<CrossComponent>() {
            public int compare(CrossComponent left, CrossComponent right) {
                return compareStageScopes(left.getStageScope(), right.getStageScope());
            }
        });
        for (int i = 0; i < components.size(); i++) {
            components.get(i).setId(i + 1);
        }
    }

    private static int compareStageScopes(Set<Integer> left, Set<Integer> right) {
        List<Integer> leftStages = new ArrayList<Integer>(left);
        List<Integer> rightStages = new ArrayList<Integer>(right);
        Collections.sort(leftStages);
        Collections.sort(rightStages);
        int limit = Math.min(leftStages.size(), rightStages.size());
        for (int i = 0; i < limit; i++) {
            int diff = leftStages.get(i) - rightStages.get(i);
            if (diff != 0) {
                return diff;
            }
        }
        return leftStages.size() - rightStages.size();
    }

    private static DelayedEnv buildOldControllerMeta(MTS<Long, String> oldController, Set<Fluent> trackedFluents) {
        Map<Long, Long> oldOrigins = new HashMap<Long, Long>();
        for (Long state : oldController.getStates()) {
            oldOrigins.put(state, state);
        }
        return buildFluentProduct(
                oldController,
                Collections.<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>emptyMap(),
                oldOrigins,
                trackedFluents);
    }

    private static Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> initialStageMetadata(
            StepwiseStage stage) {
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> result =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        for (Map.Entry<Integer, MappingEnvironmentGenerator.MappingStateMetadata> entry :
                stage.getMappingStateMetadata().entrySet()) {
            Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> byStage =
                    new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>();
            byStage.put(stage.getIndex(), entry.getValue());
            result.put(Long.valueOf(entry.getKey().longValue()), byStage);
        }
        return result;
    }

    static DelayedEnv buildFluentProduct(
            MTS<Long, String> base,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Set<Fluent> fluents) {
        return buildFluentProduct(
                base,
                stageMetadata,
                oldOrigins,
                Collections.<Long>emptySet(),
                fluents);
    }

    private static DelayedEnv buildFluentProduct(
            MTS<Long, String> base,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Set<Long> baseErrorStates,
            Set<Fluent> fluents) {
        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        result.addActions(base.getActions());
        Map<ProductStateKey, Long> keyToState = new HashMap<ProductStateKey, Long>();
        Map<Long, Set<Fluent>> trueFluentsByState = new HashMap<Long, Set<Fluent>>();
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> resultMetadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        Map<Long, Long> resultOldOrigins = new HashMap<Long, Long>();
        Set<Long> resultErrorStates = new LinkedHashSet<Long>();
        Queue<ProductStateKey> pending = new LinkedList<ProductStateKey>();

        ProductStateKey initial = new ProductStateKey(base.getInitialState(), initialTrueFluents(fluents));
        keyToState.put(initial, 0L);
        trueFluentsByState.put(0L, initial.trueFluents);
        copyMetadata(initial.baseState, 0L, stageMetadata, oldOrigins, resultMetadata, resultOldOrigins);
        if (baseErrorStates.contains(initial.baseState)) {
            resultErrorStates.add(0L);
        }
        pending.add(initial);
        long nextStateId = 1L;

        while (!pending.isEmpty()) {
            ProductStateKey current = pending.remove();
            Long fromState = keyToState.get(current);
            result.addState(fromState);
            if (resultErrorStates.contains(fromState)) {
                continue;
            }
            for (Pair<String, Long> transition : base.getTransitions(current.baseState, MTS.TransitionType.REQUIRED)) {
                Set<Fluent> nextFluents = nextTrueFluents(current.trueFluents, fluents, transition.getFirst());
                ProductStateKey targetKey = new ProductStateKey(transition.getSecond(), nextFluents);
                Long targetState = keyToState.get(targetKey);
                if (targetState == null) {
                    targetState = nextStateId++;
                    keyToState.put(targetKey, targetState);
                    result.addState(targetState);
                    trueFluentsByState.put(targetState, targetKey.trueFluents);
                    copyMetadata(targetKey.baseState, targetState, stageMetadata, oldOrigins,
                            resultMetadata, resultOldOrigins);
                    if (baseErrorStates.contains(targetKey.baseState)) {
                        resultErrorStates.add(targetState);
                    } else {
                        pending.add(targetKey);
                    }
                }
                result.addAction(transition.getFirst());
                result.addRequired(fromState, transition.getFirst(), targetState);
            }
        }

        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(result.getStates());
        for (Map.Entry<Long, Set<Fluent>> entry : trueFluentsByState.entrySet()) {
            for (Fluent fluent : entry.getValue()) {
                valuation.addHoldingFluent(entry.getKey(), fluent);
            }
        }
        DelayedEnv fluentProduct = new DelayedEnv(
                result,
                fluents,
                valuation,
                resultMetadata,
                resultOldOrigins,
                new HashSet<String>(base.getActions()),
                resultErrorStates);
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugPropagateLocalStateVectorsThroughFluentProduct(base, result, keyToState);
        }
        return fluentProduct;
    }

    /**
     * Extends an already-valuated partial environment with only the fluents that
     * are not tracked yet. Existing valuations are authoritative: replaying an
     * existing fluent from its initial value would duplicate partial-product
     * histories and can make delayed hotSwapIn non-deterministic.
     */
    static DelayedEnv extendFluentProduct(
            DelayedEnv current,
            Set<Fluent> requiredFluents) {
        Set<Fluent> newFluents = missingFluents(current.trackedFluents, requiredFluents);
        if (newFluents.isEmpty()) {
            return current;
        }

        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        result.addActions(current.env.getActions());
        Map<ProductStateKey, Long> keyToState = new HashMap<ProductStateKey, Long>();
        Map<Long, Long> resultToBaseState = new HashMap<Long, Long>();
        Map<Long, Set<Fluent>> newTrueFluentsByState = new HashMap<Long, Set<Fluent>>();
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> resultMetadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        Map<Long, Long> resultOldOrigins = new HashMap<Long, Long>();
        Set<Long> resultErrorStates = new LinkedHashSet<Long>();
        Queue<ProductStateKey> pending = new LinkedList<ProductStateKey>();

        ProductStateKey initial = new ProductStateKey(
                current.env.getInitialState(),
                initialTrueFluents(newFluents));
        keyToState.put(initial, 0L);
        resultToBaseState.put(0L, initial.baseState);
        newTrueFluentsByState.put(0L, initial.trueFluents);
        copyMetadata(
                initial.baseState,
                0L,
                current.stageMetadata,
                current.oldControllerOrigin,
                resultMetadata,
                resultOldOrigins);
        if (current.errorStates.contains(initial.baseState)) {
            resultErrorStates.add(0L);
        }
        pending.add(initial);
        long nextStateId = 1L;

        while (!pending.isEmpty()) {
            ProductStateKey productState = pending.remove();
            Long fromState = keyToState.get(productState);
            result.addState(fromState);
            if (resultErrorStates.contains(fromState)) {
                continue;
            }
            for (Pair<String, Long> transition : current.env.getTransitions(
                    productState.baseState,
                    MTS.TransitionType.REQUIRED)) {
                Set<Fluent> nextFluents = nextTrueFluents(
                        productState.trueFluents,
                        newFluents,
                        transition.getFirst());
                ProductStateKey targetKey = new ProductStateKey(transition.getSecond(), nextFluents);
                Long targetState = keyToState.get(targetKey);
                if (targetState == null) {
                    targetState = nextStateId++;
                    keyToState.put(targetKey, targetState);
                    resultToBaseState.put(targetState, targetKey.baseState);
                    newTrueFluentsByState.put(targetState, targetKey.trueFluents);
                    result.addState(targetState);
                    copyMetadata(
                            targetKey.baseState,
                            targetState,
                            current.stageMetadata,
                            current.oldControllerOrigin,
                            resultMetadata,
                            resultOldOrigins);
                    if (current.errorStates.contains(targetKey.baseState)) {
                        resultErrorStates.add(targetState);
                    } else {
                        pending.add(targetKey);
                    }
                }
                result.addRequired(fromState, transition.getFirst(), targetState);
            }
        }

        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>(current.trackedFluents);
        trackedFluents.addAll(newFluents);
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(result.getStates());
        for (Map.Entry<Long, Long> entry : resultToBaseState.entrySet()) {
            Long resultState = entry.getKey();
            Long baseState = entry.getValue();
            for (Fluent fluent : current.valuation.getFluentsFromState(baseState)) {
                valuation.addHoldingFluent(resultState, fluent);
            }
            Set<Fluent> newTrueFluents = newTrueFluentsByState.get(resultState);
            if (newTrueFluents != null) {
                for (Fluent fluent : newTrueFluents) {
                    valuation.addHoldingFluent(resultState, fluent);
                }
            }
        }

        DelayedEnv extended = new DelayedEnv(
                result,
                trackedFluents,
                valuation,
                resultMetadata,
                resultOldOrigins,
                current.realActions,
                resultErrorStates);
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugPropagateLocalStateVectorsThroughFluentProduct(current.env, result, keyToState);
        }
        return extended;
    }

    private static DelayedEnv timedExtendFluentProduct(
            DelayedEnv current,
            Set<Fluent> requiredFluents,
            Set<Integer> scope,
            String label) {
        long start = System.currentTimeMillis();
        DelayedEnv result = extendFluentProduct(current, requiredFluents);
        recordScopedTime(label, scope, System.currentTimeMillis() - start);
        return result;
    }

    private static DelayedEnv timedBuildFluentProduct(
            MTS<Long, String> base,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Set<Fluent> fluents,
            Set<Integer> scope,
            String label) {
        return timedBuildFluentProduct(
                base,
                stageMetadata,
                oldOrigins,
                Collections.<Long>emptySet(),
                fluents,
                scope,
                label);
    }

    private static DelayedEnv timedBuildFluentProduct(
            MTS<Long, String> base,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Set<Long> baseErrorStates,
            Set<Fluent> fluents,
            Set<Integer> scope,
            String label) {
        long start = System.currentTimeMillis();
        DelayedEnv result = buildFluentProduct(
                base,
                stageMetadata,
                oldOrigins,
                baseErrorStates,
                fluents);
        recordScopedTime(label, scope, System.currentTimeMillis() - start);
        return result;
    }

    private static void copyMetadata(
            Long baseState,
            Long resultState,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> resultMetadata,
            Map<Long, Long> resultOldOrigins) {
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata = stageMetadata.get(baseState);
        if (metadata != null) {
            resultMetadata.put(resultState,
                    new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>(metadata));
        }
        Long oldOrigin = oldOrigins.get(baseState);
        if (oldOrigin != null) {
            resultOldOrigins.put(resultState, oldOrigin);
        }
    }

    private static Set<Fluent> initialTrueFluents(Set<Fluent> fluents) {
        Set<Fluent> result = new LinkedHashSet<Fluent>();
        for (Fluent fluent : fluents) {
            if (fluent.getInitialValue()) {
                result.add(fluent);
            }
        }
        return result;
    }

    private static Set<Fluent> nextTrueFluents(Set<Fluent> current, Set<Fluent> fluents, String action) {
        String normalizedAction = action;
        if (UpdatingControllersUtils.isOld(normalizedAction)) {
            normalizedAction = UpdatingControllersUtils.withoutOld(normalizedAction);
        }
        SingleSymbol symbol = new SingleSymbol(normalizedAction);
        Set<Fluent> result = new LinkedHashSet<Fluent>();
        for (Fluent fluent : current) {
            if (!fluent.getTerminatingActions().contains(symbol)) {
                result.add(fluent);
            }
        }
        for (Fluent fluent : fluents) {
            if (fluent.getInitiatingActions().contains(symbol)) {
                result.add(fluent);
            }
        }
        return result;
    }

    private static Set<Fluent> mergedFluentSet(
            Set<Fluent> trackedFluents,
            Set<Fluent> requiredFluents) {
        Set<Fluent> result = new LinkedHashSet<Fluent>(trackedFluents);
        result.addAll(missingFluents(trackedFluents, requiredFluents));
        return result;
    }

    private static Set<Fluent> missingFluents(
            Set<Fluent> trackedFluents,
            Set<Fluent> requiredFluents) {
        Map<String, Fluent> trackedByName = new HashMap<String, Fluent>();
        for (Fluent fluent : trackedFluents) {
            trackedByName.put(fluent.getName(), fluent);
        }

        Set<Fluent> result = new LinkedHashSet<Fluent>();
        for (Fluent required : requiredFluents) {
            Fluent tracked = trackedByName.get(required.getName());
            if (tracked == null) {
                result.add(required);
            } else if (!sameFluentDefinition(tracked, required)) {
                Diagnostics.fatal("stepwise_delayed fluent definition changed after it was tracked: "
                        + required.getName() + ". Action-fluent termination must use the canonical "
                        + "global synthesis alphabet at every stage.");
            }
        }
        return result;
    }

    private static boolean sameFluentDefinition(Fluent left, Fluent right) {
        return left.getInitialValue() == right.getInitialValue()
                && left.getInitiatingActions().equals(right.getInitiatingActions())
                && left.getTerminatingActions().equals(right.getTerminatingActions());
    }

    private static DelayedEnv pruneSafety(DelayedEnv meta, Collection<StepwiseClassifiedGoal> goals, LTSOutput output) {
        return pruneSafety(meta, goals, "unspecified scope", output, true);
    }

    private static DelayedEnv pruneSafety(
            DelayedEnv meta,
            Collection<StepwiseClassifiedGoal> goals,
            String scope,
            LTSOutput output) {
        return pruneSafety(meta, goals, scope, output, true);
    }

    private static DelayedEnv pruneSafety(
            DelayedEnv meta,
            Collection<StepwiseClassifiedGoal> goals,
            String scope,
            LTSOutput output,
            boolean cleanup) {
        if (goals == null || goals.isEmpty()) {
            return meta;
        }
        List<Formula> formulas = new ArrayList<Formula>();
        for (StepwiseClassifiedGoal goal : goals) {
            formulas.add(goal.getFormula());
        }
        Set<Long> violating = new HashSet<Long>();
        for (Formula formula : formulas) {
            for (Long state : meta.env.getStates()) {
                meta.valuation.setActualState(state);
                if (formula.evaluate(meta.valuation)) {
                    violating.add(state);
                }
            }
            if (violating.isEmpty()) {
                Logger.getAnonymousLogger().log(Level.WARNING, "No state satisfies formula: " + formula);
            }
        }
        Set<Long> errorStates = new LinkedHashSet<Long>(meta.errorStates);
        errorStates.addAll(violating);
        if (errorStates.contains(meta.env.getInitialState())) {
            Diagnostics.fatal("stepwise_delayed safety pruning found initial state violating in "
                    + scope + ".");
        }

        MTS<Long, String> result = new MTSImpl<Long, String>(meta.env.getInitialState());
        result.addActions(meta.env.getActions());
        for (Long state : meta.env.getStates()) {
            result.addState(state);
            if (!errorStates.contains(state)) {
                for (Pair<String, Long> transition : meta.env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    result.addState(transition.getSecond());
                    result.addAction(transition.getFirst());
                    result.addRequired(state, transition.getFirst(), transition.getSecond());
                }
            }
        }
        if (cleanup) {
            result.removeUnreachableStates();
        }

        long afterTransitions = -1;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            long reductionCountStart = System.currentTimeMillis();
            long beforeStates = meta.env.getStates().size();
            long beforeTransitions = countTransitions(meta.env);
            long afterStates = result.getStates().size();
            afterTransitions = countTransitions(result);
            long reductionCountTime = System.currentTimeMillis() - reductionCountStart;
            String safeScope = scope == null || scope.isEmpty() ? "unknown" : scope;
            UpdatingControllerEvaluationRecorder.recordStateTransitionReduction(
                    StepwiseDelayedEvaluationMetrics.DIRECT_PRUNING_REDUCTION_SECTION,
                    safeScope + " / before -> after direct pruning",
                    beforeStates,
                    beforeTransitions,
                    afterStates,
                    afterTransitions);
            UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                    StepwiseDelayedEvaluationMetrics.DIRECT_PRUNING_REDUCTION_SECTION,
                    safeScope + " / direct pruning reduction CountTime",
                    reductionCountTime,
                    "direct safety pruning の削減率を記録するために before/after の遷移数を数える評価用 CountTime。");
        }

        DelayedEnv pruned = rebuildMetadata(meta, result, errorStates);
        if (output != null) {
            if (afterTransitions < 0) {
                afterTransitions = countTransitions(result);
            }
            output.outln("  safety pruning kept states: " + result.getStates().size()
                    + " transitions: " + afterTransitions);
        }
        return pruned;
    }

    private static DelayedEnv timedPruneSafety(
            DelayedEnv meta,
            Collection<StepwiseClassifiedGoal> goals,
            String pruningScope,
            Set<Integer> stageScope,
            String timeLabel,
            LTSOutput output) {
        return timedPruneSafety(meta, goals, pruningScope, stageScope, timeLabel, output, true);
    }

    private static DelayedEnv timedPruneSafety(
            DelayedEnv meta,
            Collection<StepwiseClassifiedGoal> goals,
            String pruningScope,
            Set<Integer> stageScope,
            String timeLabel,
            LTSOutput output,
            boolean cleanup) {
        String scopedLabel = StepwiseDelayedEvaluationMetrics.scopedMetricLabel(timeLabel, stageScope);
        long start = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginCountScope(
                StepwiseDelayedEvaluationMetrics.SCOPE_TIME_SECTION,
                scopedLabel);
        try {
            return pruneSafety(meta, goals, pruningScope, output, cleanup);
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            UpdatingControllerEvaluationRecorder.endCountScope(
                    StepwiseDelayedEvaluationMetrics.SCOPE_TIME_SECTION,
                    scopedLabel);
            UpdatingControllerEvaluationRecorder.recordTime(
                    StepwiseDelayedEvaluationMetrics.SCOPE_TIME_SECTION,
                    scopedLabel,
                    elapsed);
        }
    }

    static DelayedEnv composeProduct(List<DelayedEnv> inputs, String productName, LTSOutput output) {
        if (inputs.size() == 1) {
            return inputs.get(0);
        }

        output.outln("Owner-aware delayed product: " + productName);
        MTS<Long, String> product = new MTSImpl<Long, String>(0L);
        for (DelayedEnv input : inputs) {
            product.addActions(input.env.getActions());
        }
        Map<List<Long>, Long> tupleToState = new HashMap<List<Long>, Long>();
        Map<Long, List<Long>> debugStateToTuple = DEBUG_HOT_SWAP_LINEAGE
                ? new HashMap<Long, List<Long>>()
                : null;
        Set<Long> productErrorStates = new LinkedHashSet<Long>();
        Queue<List<Long>> pending = new ArrayDeque<List<Long>>();

        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>();
        Set<String> realActions = new HashSet<String>();
        for (DelayedEnv input : inputs) {
            trackedFluents.addAll(input.trackedFluents);
            realActions.addAll(input.realActions);
        }
        FluentStateValuation<Long> valuation =
                new FluentStateValuation<Long>(product.getStates());
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();

        List<Long> initialTuple = new ArrayList<Long>();
        for (DelayedEnv input : inputs) {
            initialTuple.add(input.env.getInitialState());
        }
        tupleToState.put(initialTuple, 0L);
        if (debugStateToTuple != null) {
            debugStateToTuple.put(0L, initialTuple);
        }
        addProductStateData(0L, initialTuple, inputs, valuation, metadata);
        if (isErrorTuple(inputs, initialTuple)) {
            productErrorStates.add(0L);
        }
        pending.add(initialTuple);

        long nextStateId = 1L;
        while (!pending.isEmpty()) {
            List<Long> tuple = pending.remove();
            Long fromState = tupleToState.get(tuple);
            product.addState(fromState);
            if (productErrorStates.contains(fromState)) {
                continue;
            }

            TupleTransitionIndex transitionIndex = indexTupleTransitions(inputs, tuple);
            for (String action : transitionIndex.enabledActions) {
                List<List<Long>> targetChoices = targetChoices(
                        action,
                        tuple,
                        inputs,
                        transitionIndex);
                if (targetChoices == null) {
                    continue;
                }
                nextStateId = addCartesianProductTransitions(
                        targetChoices,
                        0,
                        new ArrayList<Long>(targetChoices.size()),
                        inputs,
                        tupleToState,
                        debugStateToTuple,
                        productErrorStates,
                        pending,
                        product,
                        valuation,
                        metadata,
                        fromState,
                        action,
                        nextStateId);
            }
        }

        DelayedEnv composed = new DelayedEnv(
                product,
                trackedFluents,
                valuation,
                metadata,
                Collections.<Long, Long>emptyMap(),
                realActions,
                productErrorStates);
        if (DEBUG_HOT_SWAP_LINEAGE) {
            debugPropagateLocalStateVectorsThroughComposition(inputs, product, debugStateToTuple);
            debugSemanticMultiplicity(productName, composed, output);
        }
        return composed;
    }

    private static DelayedEnv timedComposeProduct(
            List<DelayedEnv> inputs,
            String productName,
            Set<Integer> scope,
            String label,
            LTSOutput output) {
        long start = System.currentTimeMillis();
        DelayedEnv result = composeProduct(inputs, productName, output);
        recordScopedTime(label, scope, System.currentTimeMillis() - start);
        return result;
    }

    private static ConnectionPlan buildConnections(
            DelayedEnv oldMeta,
            DelayedEnv mappingProduct,
            Set<Fluent> comparisonFluents,
            LTSOutput output) {
        ConnectionBuildStats stats = new ConnectionBuildStats(
                INDEXED_HOT_SWAP_IN_CONNECTION ? "indexed" : "legacy");
        stats.oldStates = oldMeta.env.getStates().size();
        stats.mappingStates = mappingProduct.env.getStates().size();
        long totalStart = System.nanoTime();
        ConnectionPlan result = INDEXED_HOT_SWAP_IN_CONNECTION
                ? buildConnectionsIndexed(oldMeta, mappingProduct, comparisonFluents, stats)
                : buildConnectionsLegacy(oldMeta, mappingProduct, comparisonFluents, stats);
        stats.totalNanos = System.nanoTime() - totalStart;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            long eligibleCountStart = System.currentTimeMillis();
            stats.eligibleMappingStatesTotal = countEligibleMappingStates(mappingProduct);
            stats.eligibleMappingStatesCountTimeMillis = System.currentTimeMillis() - eligibleCountStart;
        }
        stats.connectionCount = result.connections.size();
        stats.oldStatesWithoutTargets = result.oldStatesWithoutTargets.size();
        recordConnectionBuildStats(output, stats);
        return result;
    }

    private static ConnectionPlan buildConnectionsIndexed(
            DelayedEnv oldMeta,
            DelayedEnv mappingProduct,
            Set<Fluent> comparisonFluents,
            ConnectionBuildStats stats) {
        List<Connection> connections = new ArrayList<Connection>();
        Set<Long> oldStatesWithTargets = new HashSet<Long>();
        long setupStart = System.nanoTime();
        List<Fluent> signatureFluents = orderedConnectionComparisonFluents(comparisonFluents);
        stats.comparisonFluents = signatureFluents.size();
        TransitionIndex oldTransitionIndex = buildTransitionIndex(oldMeta.env);
        TransitionIndex mappingTransitionIndex = buildTransitionIndex(mappingProduct.env);
        Map<Long, ValuationSignature> oldSignatures = new HashMap<Long, ValuationSignature>();
        Map<Long, ValuationSignature> mappingSignatures = new HashMap<Long, ValuationSignature>();
        Map<Long, Boolean> eligibleMappingStates = new HashMap<Long, Boolean>();
        stats.setupNanos = System.nanoTime() - setupStart;
        Set<Long> visitedOldStates = new HashSet<Long>();
        Set<Long> visitedMappingStates = new HashSet<Long>();
        Set<Long> visitedEligibleMappingStates = new HashSet<Long>();
        Set<Pair<Long, Long>> discovered = new HashSet<Pair<Long, Long>>();
        Queue<Pair<Long, Long>> pending = new LinkedList<Pair<Long, Long>>();
        Pair<Long, Long> initial = new Pair<Long, Long>(
                oldMeta.env.getInitialState(),
                mappingProduct.env.getInitialState());
        pending.add(initial);
        stats.enqueuedPairs++;

        long traversalStart = System.nanoTime();
        while (!pending.isEmpty()) {
            Pair<Long, Long> pair = pending.remove();
            if (!discovered.add(pair)) {
                stats.duplicatePairs++;
                continue;
            }
            stats.visitedPairs++;
            Long oldState = pair.getFirst();
            Long mappingState = pair.getSecond();
            visitedOldStates.add(oldState);
            visitedMappingStates.add(mappingState);
            stats.eligibilityChecks++;
            if (isEligibleMappingState(mappingProduct, mappingState, eligibleMappingStates, stats)) {
                visitedEligibleMappingStates.add(mappingState);
                stats.valuationChecks++;
                ValuationSignature oldSignature = valuationSignature(
                        oldMeta,
                        oldState,
                        signatureFluents,
                        oldSignatures,
                        stats);
                ValuationSignature mappingSignature = valuationSignature(
                        mappingProduct,
                        mappingState,
                        signatureFluents,
                        mappingSignatures,
                        stats);
                if (oldSignature.equals(mappingSignature)) {
                    connections.add(new Connection(oldState, mappingState));
                    oldStatesWithTargets.add(oldState);
                }
            }
            enqueueSynchronizedSuccessors(
                    oldTransitionIndex.targetsByAction(oldState, stats),
                    mappingTransitionIndex.targetsByAction(mappingState, stats),
                    pending,
                    stats);
        }
        stats.traversalNanos = System.nanoTime() - traversalStart;
        stats.visitedOldStates = visitedOldStates.size();
        stats.visitedMappingStates = visitedMappingStates.size();
        stats.visitedEligibleMappingStates = visitedEligibleMappingStates.size();

        Set<Long> oldStatesWithoutTargets = new HashSet<Long>(oldMeta.env.getStates());
        oldStatesWithoutTargets.removeAll(oldStatesWithTargets);
        return new ConnectionPlan(connections, oldStatesWithoutTargets);
    }

    private static boolean isEligibleMappingState(
            DelayedEnv mappingProduct,
            Long state,
            Map<Long, Boolean> eligibleMappingStates,
            ConnectionBuildStats stats) {
        Boolean cached = eligibleMappingStates.get(state);
        if (cached == null) {
            cached = Boolean.valueOf(isMappingOldSide(mappingProduct, state)
                    && phaseInitial(mappingProduct, state));
            eligibleMappingStates.put(state, cached);
            stats.eligibilityComputations++;
        }
        return cached.booleanValue();
    }

    private static ValuationSignature valuationSignature(
            DelayedEnv env,
            Long state,
            List<Fluent> signatureFluents,
            Map<Long, ValuationSignature> signatures,
            ConnectionBuildStats stats) {
        ValuationSignature signature = signatures.get(state);
        if (signature == null) {
            BitSet bits = new BitSet(signatureFluents.size());
            for (int i = 0; i < signatureFluents.size(); i++) {
                if (env.valuation.isTrue(state, signatureFluents.get(i))) {
                    bits.set(i);
                }
            }
            signature = new ValuationSignature(bits);
            signatures.put(state, signature);
            stats.signatureBuilds++;
        }
        return signature;
    }

    private static ConnectionPlan buildConnectionsLegacy(
            DelayedEnv oldMeta,
            DelayedEnv mappingProduct,
            Set<Fluent> comparisonFluents,
            ConnectionBuildStats stats) {
        List<Connection> connections = new ArrayList<Connection>();
        Set<Long> oldStatesWithTargets = new HashSet<Long>();
        long setupStart = System.nanoTime();
        stats.comparisonFluents = orderedConnectionComparisonFluents(comparisonFluents).size();
        stats.setupNanos = System.nanoTime() - setupStart;
        Set<Pair<Long, Long>> discovered = new HashSet<Pair<Long, Long>>();
        Queue<Pair<Long, Long>> pending = new LinkedList<Pair<Long, Long>>();
        Pair<Long, Long> initial = new Pair<Long, Long>(
                oldMeta.env.getInitialState(),
                mappingProduct.env.getInitialState());
        pending.add(initial);
        stats.enqueuedPairs++;
        Set<Long> visitedOldStates = new HashSet<Long>();
        Set<Long> visitedMappingStates = new HashSet<Long>();
        Set<Long> visitedEligibleMappingStates = new HashSet<Long>();

        long traversalStart = System.nanoTime();
        while (!pending.isEmpty()) {
            Pair<Long, Long> pair = pending.remove();
            if (!discovered.add(pair)) {
                stats.duplicatePairs++;
                continue;
            }
            stats.visitedPairs++;
            Long oldState = pair.getFirst();
            Long mappingState = pair.getSecond();
            visitedOldStates.add(oldState);
            visitedMappingStates.add(mappingState);
            stats.eligibilityChecks++;
            if (isMappingOldSide(mappingProduct, mappingState)
                    && phaseInitial(mappingProduct, mappingState)) {
                visitedEligibleMappingStates.add(mappingState);
                stats.valuationChecks++;
                if (valuationsMatch(oldMeta, oldState, mappingProduct, mappingState, comparisonFluents)) {
                    connections.add(new Connection(oldState, mappingState));
                    oldStatesWithTargets.add(oldState);
                }
            }

            Collection<Pair<String, Long>> oldTransitions =
                    oldMeta.env.getTransitions(oldState, MTS.TransitionType.REQUIRED);
            Collection<Pair<String, Long>> mappingTransitions =
                    mappingProduct.env.getTransitions(mappingState, MTS.TransitionType.REQUIRED);
            stats.legacyOldTransitionsScanned += oldTransitions.size();
            stats.legacyMappingTransitionsScanned += mappingTransitions.size();
            for (Pair<String, Long> oldTransition : oldTransitions) {
                for (Pair<String, Long> mappingTransition : mappingTransitions) {
                    stats.legacyTransitionComparisons++;
                    if (oldTransition.getFirst().equals(mappingTransition.getFirst())) {
                        stats.indexedActionMatches++;
                        stats.synchronizedSuccessorPairs++;
                        stats.enqueuedPairs++;
                        pending.add(new Pair<Long, Long>(
                                oldTransition.getSecond(),
                                mappingTransition.getSecond()));
                    }
                }
            }
        }
        stats.traversalNanos = System.nanoTime() - traversalStart;
        stats.visitedOldStates = visitedOldStates.size();
        stats.visitedMappingStates = visitedMappingStates.size();
        stats.visitedEligibleMappingStates = visitedEligibleMappingStates.size();

        Set<Long> oldStatesWithoutTargets = new HashSet<Long>(oldMeta.env.getStates());
        oldStatesWithoutTargets.removeAll(oldStatesWithTargets);
        return new ConnectionPlan(connections, oldStatesWithoutTargets);
    }

    private static void recordConnectionBuildStats(LTSOutput output, ConnectionBuildStats stats) {
        if (output != null) {
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection mode: " + stats.mode);
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection time: total="
                    + formatNanosAsMillis(stats.totalNanos) + " ms, setup="
                    + formatNanosAsMillis(stats.setupNanos) + " ms, traversal="
                    + formatNanosAsMillis(stats.traversalNanos) + " ms");
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection pairs: visited="
                    + stats.visitedPairs + ", enqueued=" + stats.enqueuedPairs
                    + ", duplicateSkipped=" + stats.duplicatePairs
                    + ", synchronizedSuccessorPairs=" + stats.synchronizedSuccessorPairs);
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection result: connections="
                    + stats.connectionCount + ", oldStatesWithoutTargets="
                    + stats.oldStatesWithoutTargets + ", comparisonFluents="
                    + stats.comparisonFluents);
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection unique states: old="
                    + stats.visitedOldStates + ", mapping=" + stats.visitedMappingStates
                    + ", eligibleMappingVisited=" + stats.visitedEligibleMappingStates
                    + ", eligibleMappingTotal=" + stats.eligibleMappingStatesTotal);
            output.outln("[Stepwise Delayed DUCS] hotSwapIn connection cache stats: eligibilityComputations="
                    + stats.eligibilityComputations + ", signatureBuilds="
                    + stats.signatureBuilds + ", transitionIndexStateBuilds="
                    + stats.transitionIndexStateBuilds);
            if ("indexed".equals(stats.mode)) {
                output.outln("[Stepwise Delayed DUCS] hotSwapIn connection indexed stats: actionEntriesScanned="
                        + stats.indexedActionEntriesScanned + ", actionMatches="
                        + stats.indexedActionMatches);
            } else {
                output.outln("[Stepwise Delayed DUCS] hotSwapIn connection legacy stats: transitionComparisons="
                        + stats.legacyTransitionComparisons + ", actionMatches="
                        + stats.indexedActionMatches + ", oldTransitionsScanned="
                        + stats.legacyOldTransitionsScanned + ", mappingTransitionsScanned="
                        + stats.legacyMappingTransitionsScanned);
            }
        }

        String section = StepwiseDelayedEvaluationMetrics.HOT_SWAP_IN_CONNECTION_SECTION;
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                section,
                "total time",
                stats.totalNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                section,
                "setup time",
                stats.setupNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                section,
                "traversal time",
                stats.traversalNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "mode indexed",
                "indexed".equals(stats.mode) ? 1 : 0,
                "bool");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "old states",
                stats.oldStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "mapping states",
                stats.mappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "comparison fluents",
                stats.comparisonFluents,
                "fluents");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "visited synchronized pairs",
                stats.visitedPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "enqueued synchronized pairs",
                stats.enqueuedPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "duplicate skipped pairs",
                stats.duplicatePairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "synchronized successor pairs",
                stats.synchronizedSuccessorPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "visited unique old states",
                stats.visitedOldStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "visited unique mapping states",
                stats.visitedMappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "visited eligible mapping states",
                stats.visitedEligibleMappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "eligible mapping states total",
                stats.eligibleMappingStatesTotal,
                "states");
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                section,
                "eligible mapping states total CountTime",
                stats.eligibleMappingStatesCountTimeMillis,
                "hotSwapIn connection の診断用に mapping product 全状態を走査し、OLD_SIDE かつ phase-initial な state 数を数える評価用 CountTime。");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "eligibility checks",
                stats.eligibilityChecks,
                "checks");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "valuation checks",
                stats.valuationChecks,
                "checks");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "eligibility computations",
                stats.eligibilityComputations,
                "computations");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "signature builds",
                stats.signatureBuilds,
                "signatures");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "transition index state builds",
                stats.transitionIndexStateBuilds,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "connections",
                stats.connectionCount,
                "transitions");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "old states without targets",
                stats.oldStatesWithoutTargets,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "indexed action entries scanned",
                stats.indexedActionEntriesScanned,
                "entries");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "action matches",
                stats.indexedActionMatches,
                "matches");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                "legacy transition comparisons",
                stats.legacyTransitionComparisons,
                "comparisons");
    }

    private static void debugResetHotSwapLineage(int stageCount) {
        DEBUG_LOCAL_STATE_VECTORS.clear();
        DEBUG_FLUENT_PRODUCT_BASE_STATES.clear();
        DEBUG_LOCAL_ENVIRONMENTS.clear();
        debugLineageStageCount = stageCount;
    }

    private static void debugRegisterLocalSafetyEnvironment(
            DelayedEnv environment,
            int stageIndex) {
        if (!DEBUG_HOT_SWAP_LINEAGE) {
            return;
        }
        Map<Long, long[]> vectors = new HashMap<Long, long[]>();
        for (Long state : environment.env.getStates()) {
            long[] vector = debugEmptyLocalStateVector();
            vector[stageIndex] = state.longValue();
            vectors.put(state, vector);
        }
        DEBUG_LOCAL_STATE_VECTORS.put(environment.env, vectors);
        DEBUG_LOCAL_ENVIRONMENTS.put(Integer.valueOf(stageIndex), environment);
    }

    private static void debugCopyLocalStateVectors(
            MTS<Long, String> source,
            MTS<Long, String> result) {
        if (!DEBUG_HOT_SWAP_LINEAGE) {
            return;
        }
        Map<Long, long[]> sourceVectors = DEBUG_LOCAL_STATE_VECTORS.get(source);
        if (sourceVectors == null) {
            return;
        }
        Map<Long, long[]> resultVectors = new HashMap<Long, long[]>();
        for (Long state : result.getStates()) {
            long[] sourceVector = sourceVectors.get(state);
            if (sourceVector != null) {
                resultVectors.put(state, copyLocalStateVector(sourceVector));
            }
        }
        DEBUG_LOCAL_STATE_VECTORS.put(result, resultVectors);
    }

    private static void debugPropagateLocalStateVectorsThroughFluentProduct(
            MTS<Long, String> base,
            MTS<Long, String> result,
            Map<ProductStateKey, Long> keyToState) {
        if (!DEBUG_HOT_SWAP_LINEAGE) {
            return;
        }
        Map<Long, Long> outputToBase = new HashMap<Long, Long>();
        for (Map.Entry<ProductStateKey, Long> entry : keyToState.entrySet()) {
            outputToBase.put(entry.getValue(), entry.getKey().baseState);
        }
        DEBUG_FLUENT_PRODUCT_BASE_STATES.put(result, outputToBase);
        Map<Long, long[]> baseVectors = DEBUG_LOCAL_STATE_VECTORS.get(base);
        if (baseVectors == null) {
            return;
        }
        Map<Long, long[]> resultVectors = new HashMap<Long, long[]>();
        for (Map.Entry<ProductStateKey, Long> entry : keyToState.entrySet()) {
            long[] baseVector = baseVectors.get(entry.getKey().baseState);
            if (baseVector != null) {
                resultVectors.put(entry.getValue(), copyLocalStateVector(baseVector));
            }
        }
        DEBUG_LOCAL_STATE_VECTORS.put(result, resultVectors);
    }

    private static void debugFluentProductConsistency(
            String checkpoint,
            DelayedEnv base,
            DelayedEnv fluentProduct,
            LTSOutput output) {
        if (!DEBUG_HOT_SWAP_LINEAGE || output == null) {
            return;
        }
        Map<Long, Long> outputToBase = DEBUG_FLUENT_PRODUCT_BASE_STATES.get(fluentProduct.env);
        if (outputToBase == null) {
            output.outln("[StepwiseLineage] FLUENT_REPRODUCT checkpoint=" + checkpoint
                    + " baseLineage=<missing>");
            return;
        }
        int statesWithMismatch = 0;
        Map<String, Integer> mismatchCounts = new LinkedHashMap<String, Integer>();
        int samples = 0;
        for (Map.Entry<Long, Long> entry : outputToBase.entrySet()) {
            Long outputState = entry.getKey();
            Long baseState = entry.getValue();
            List<String> stateMismatches = new ArrayList<String>();
            for (Fluent fluent : base.trackedFluents) {
                boolean before = base.valuation.isTrue(baseState, fluent);
                boolean after = fluentProduct.valuation.isTrue(outputState, fluent);
                if (before == after) {
                    continue;
                }
                String key = fluent.getName() + ":" + (before ? "1" : "0")
                        + "->" + (after ? "1" : "0");
                stateMismatches.add(key);
                Integer count = mismatchCounts.get(key);
                mismatchCounts.put(key, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            }
            if (!stateMismatches.isEmpty()) {
                statesWithMismatch++;
                if (samples < 8) {
                    output.outln("[StepwiseLineage] FLUENT_REPRODUCT_SAMPLE checkpoint="
                            + checkpoint + " outputState=" + outputState
                            + " baseState=" + baseState
                            + " mismatches=" + stateMismatches
                            + " outputTrace=" + debugShortestActionTrace(
                                    fluentProduct.env,
                                    outputState)
                            + " baseTrace=" + debugShortestActionTrace(
                                    base.env,
                                    baseState));
                    samples++;
                }
            }
        }
        output.outln("[StepwiseLineage] FLUENT_REPRODUCT checkpoint=" + checkpoint
                + " outputStates=" + fluentProduct.env.getStates().size()
                + " statesWithPriorFluentMismatch=" + statesWithMismatch
                + " mismatchCounts=" + mismatchCounts);
    }

    private static List<String> debugShortestActionTrace(
            MTS<Long, String> environment,
            Long target) {
        Long initial = environment.getInitialState();
        if (initial.equals(target)) {
            return Collections.emptyList();
        }
        Queue<Long> pending = new LinkedList<Long>();
        Set<Long> discovered = new HashSet<Long>();
        Map<Long, Long> predecessor = new HashMap<Long, Long>();
        Map<Long, String> predecessorAction = new HashMap<Long, String>();
        pending.add(initial);
        discovered.add(initial);
        while (!pending.isEmpty()) {
            Long state = pending.remove();
            for (Pair<String, Long> transition :
                    environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                Long successor = transition.getSecond();
                if (!discovered.add(successor)) {
                    continue;
                }
                predecessor.put(successor, state);
                predecessorAction.put(successor, transition.getFirst());
                if (successor.equals(target)) {
                    LinkedList<String> result = new LinkedList<String>();
                    Long cursor = target;
                    while (!initial.equals(cursor)) {
                        result.addFirst(predecessorAction.get(cursor));
                        cursor = predecessor.get(cursor);
                    }
                    return result;
                }
                pending.add(successor);
            }
        }
        return Collections.singletonList("<unreachable>");
    }

    private static void debugPropagateLocalStateVectorsThroughComposition(
            List<DelayedEnv> inputs,
            MTS<Long, String> product,
            Map<Long, List<Long>> stateToTuple) {
        if (!DEBUG_HOT_SWAP_LINEAGE) {
            return;
        }
        Map<Long, long[]> productVectors = new HashMap<Long, long[]>();
        for (Map.Entry<Long, List<Long>> entry : stateToTuple.entrySet()) {
            long[] merged = debugEmptyLocalStateVector();
            List<Long> tuple = entry.getValue();
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                Map<Long, long[]> inputVectors = DEBUG_LOCAL_STATE_VECTORS.get(inputs.get(inputIndex).env);
                if (inputVectors == null) {
                    continue;
                }
                long[] inputVector = inputVectors.get(tuple.get(inputIndex));
                if (inputVector == null) {
                    continue;
                }
                for (int stageIndex = 0; stageIndex < merged.length; stageIndex++) {
                    if (inputVector[stageIndex] < 0L) {
                        continue;
                    }
                    if (merged[stageIndex] >= 0L
                            && merged[stageIndex] != inputVector[stageIndex]) {
                        // Overlapping fragments should have synchronized the same local state.
                        // Preserve an explicit conflict marker for the diagnostic instead of
                        // silently choosing one side.
                        merged[stageIndex] = Long.MIN_VALUE;
                    } else {
                        merged[stageIndex] = inputVector[stageIndex];
                    }
                }
            }
            productVectors.put(entry.getKey(), merged);
        }
        DEBUG_LOCAL_STATE_VECTORS.put(product, productVectors);
    }

    private static long[] debugEmptyLocalStateVector() {
        long[] vector = new long[debugLineageStageCount];
        java.util.Arrays.fill(vector, -1L);
        return vector;
    }

    private static long[] copyLocalStateVector(long[] source) {
        long[] copy = new long[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static void debugSemanticMultiplicity(
            String checkpoint,
            DelayedEnv environment,
            LTSOutput output) {
        if (!DEBUG_HOT_SWAP_LINEAGE || output == null) {
            return;
        }
        Map<String, List<Long>> statesBySemanticSignature =
                new LinkedHashMap<String, List<Long>>();
        for (Long state : environment.env.getStates()) {
            String signature = mappingMetadataFingerprint(environment, state)
                    + "|" + debugValuationFingerprint(environment, state, environment.trackedFluents);
            List<Long> states = statesBySemanticSignature.get(signature);
            if (states == null) {
                states = new ArrayList<Long>();
                statesBySemanticSignature.put(signature, states);
            }
            states.add(state);
        }

        int duplicateGroups = 0;
        int statesInDuplicateGroups = 0;
        int maxGroupSize = 0;
        int groupsWithMultipleLocalStates = 0;
        int groupsWithMultipleLocalValuations = 0;
        Map<Long, long[]> vectors = DEBUG_LOCAL_STATE_VECTORS.get(environment.env);
        for (List<Long> states : statesBySemanticSignature.values()) {
            if (states.size() <= 1) {
                continue;
            }
            duplicateGroups++;
            statesInDuplicateGroups += states.size();
            maxGroupSize = Math.max(maxGroupSize, states.size());
            Set<String> localStates = new LinkedHashSet<String>();
            Set<String> localValuations = new LinkedHashSet<String>();
            for (Long state : states) {
                long[] vector = vectors == null ? null : vectors.get(state);
                localStates.add(debugLocalStateVectorFingerprint(vector));
                localValuations.add(debugLocalValuationFingerprint(vector));
            }
            if (localStates.size() > 1) {
                groupsWithMultipleLocalStates++;
            }
            if (localValuations.size() > 1) {
                groupsWithMultipleLocalValuations++;
            }
        }
        output.outln("[StepwiseLineage] PRODUCT checkpoint=" + checkpoint
                + " states=" + environment.env.getStates().size()
                + " semanticDuplicateGroups=" + duplicateGroups
                + " statesInDuplicateGroups=" + statesInDuplicateGroups
                + " maxGroupSize=" + maxGroupSize
                + " groupsWithMultipleLocalStates=" + groupsWithMultipleLocalStates
                + " groupsWithMultipleLocalValuations=" + groupsWithMultipleLocalValuations);
    }

    private static void debugHotSwapLineage(
            ConnectionPlan connectionPlan,
            DelayedEnv oldMeta,
            DelayedEnv mappingProduct,
            Set<Fluent> comparisonFluents,
            LTSOutput output) {
        if (!DEBUG_HOT_SWAP_LINEAGE || output == null) {
            return;
        }

        debugSemanticMultiplicity("final_mapping_before_connection", mappingProduct, output);
        debugFluentDefinitionConsistency(output);

        Map<Long, List<Long>> targetsByOldState = new LinkedHashMap<Long, List<Long>>();
        for (Connection connection : connectionPlan.connections) {
            List<Long> targets = targetsByOldState.get(connection.oldState);
            if (targets == null) {
                targets = new ArrayList<Long>();
                targetsByOldState.put(connection.oldState, targets);
            }
            targets.add(connection.mappingState);
        }

        Map<Long, long[]> finalVectors = DEBUG_LOCAL_STATE_VECTORS.get(mappingProduct.env);
        int multiTargetSources = 0;
        int sameLocalStateVector = 0;
        int multipleLocalStatesSameValuation = 0;
        int multipleLocalValuations = 0;
        int missingLocalLineage = 0;
        Map<String, Integer> differingLocalFluentSources = new LinkedHashMap<String, Integer>();
        int samples = 0;
        output.outln("[StepwiseLineage] hotSwapIn target comparison");
        for (Map.Entry<Long, List<Long>> entry : targetsByOldState.entrySet()) {
            List<Long> targets = entry.getValue();
            if (targets.size() <= 1) {
                continue;
            }
            multiTargetSources++;
            Set<String> metadataFingerprints = new LinkedHashSet<String>();
            Set<String> finalValuations = new LinkedHashSet<String>();
            Set<String> localStateVectors = new LinkedHashSet<String>();
            Set<String> localValuations = new LinkedHashSet<String>();
            boolean missing = false;
            for (Long target : targets) {
                metadataFingerprints.add(mappingMetadataFingerprint(mappingProduct, target));
                finalValuations.add(debugValuationFingerprint(
                        mappingProduct,
                        target,
                        comparisonFluents));
                long[] vector = finalVectors == null ? null : finalVectors.get(target);
                if (vector == null) {
                    missing = true;
                }
                localStateVectors.add(debugLocalStateVectorFingerprint(vector));
                localValuations.add(debugLocalValuationFingerprint(vector));
            }
            if (missing) {
                missingLocalLineage++;
            } else if (localStateVectors.size() == 1) {
                sameLocalStateVector++;
            } else if (localValuations.size() == 1) {
                multipleLocalStatesSameValuation++;
            } else {
                multipleLocalValuations++;
            }
            for (String differingFluent : debugDifferingLocalFluentKeys(targets, finalVectors)) {
                Integer count = differingLocalFluentSources.get(differingFluent);
                differingLocalFluentSources.put(
                        differingFluent,
                        Integer.valueOf(count == null ? 1 : count.intValue() + 1));
            }

            if (samples < 8) {
                output.outln("  oldMeta=" + entry.getKey()
                        + " oldControllerOrigin=" + oldMeta.oldControllerOrigin.get(entry.getKey())
                        + " targets=" + targets
                        + " distinctMetadata=" + metadataFingerprints.size()
                        + " distinctFinalValuations=" + finalValuations.size()
                        + " distinctLocalStateVectors=" + localStateVectors.size()
                        + " distinctLocalValuations=" + localValuations.size());
                for (Long target : targets) {
                    long[] vector = finalVectors == null ? null : finalVectors.get(target);
                    output.outln("    target=" + target
                            + " localStates=" + debugLocalStateVectorFingerprint(vector)
                            + " localValuationHash="
                            + Integer.toHexString(debugLocalValuationFingerprint(vector).hashCode()));
                }
                output.outln("    differingLocalFluents="
                        + debugDifferingLocalFluents(targets, finalVectors));
                samples++;
            }
        }
        output.outln("[StepwiseLineage] SUMMARY multiTargetSources=" + multiTargetSources
                + " sameLocalStateVector=" + sameLocalStateVector
                + " multipleLocalStatesSameValuation=" + multipleLocalStatesSameValuation
                + " multipleLocalValuations=" + multipleLocalValuations
                + " missingLocalLineage=" + missingLocalLineage);
        output.outln("[StepwiseLineage] differing local fluent source counts="
                + differingLocalFluentSources);
    }

    private static String debugLocalStateVectorFingerprint(long[] vector) {
        return vector == null ? "<missing>" : java.util.Arrays.toString(vector);
    }

    private static String debugLocalValuationFingerprint(long[] vector) {
        if (vector == null) {
            return "<missing>";
        }
        StringBuilder result = new StringBuilder();
        for (int stageIndex = 0; stageIndex < vector.length; stageIndex++) {
            if (stageIndex > 0) {
                result.append('|');
            }
            DelayedEnv local = DEBUG_LOCAL_ENVIRONMENTS.get(Integer.valueOf(stageIndex));
            long localState = vector[stageIndex];
            result.append(stageIndex).append(':');
            if (local == null || localState < 0L || !local.env.getStates().contains(Long.valueOf(localState))) {
                result.append("<missing>");
            } else {
                result.append(debugValuationFingerprint(
                        local,
                        Long.valueOf(localState),
                        local.trackedFluents));
            }
        }
        return result.toString();
    }

    private static String debugValuationFingerprint(
            DelayedEnv environment,
            Long state,
            Collection<Fluent> fluents) {
        List<Fluent> ordered = new ArrayList<Fluent>(fluents);
        Collections.sort(ordered, new java.util.Comparator<Fluent>() {
            public int compare(Fluent left, Fluent right) {
                return left.getName().compareTo(right.getName());
            }
        });
        StringBuilder result = new StringBuilder();
        for (Fluent fluent : ordered) {
            result.append(fluent.getName())
                    .append('=')
                    .append(environment.valuation.isTrue(state, fluent) ? '1' : '0')
                    .append(';');
        }
        return result.toString();
    }

    private static String debugDifferingLocalFluents(
            List<Long> targets,
            Map<Long, long[]> finalVectors) {
        return debugDifferingLocalFluentKeys(targets, finalVectors).toString();
    }

    private static List<String> debugDifferingLocalFluentKeys(
            List<Long> targets,
            Map<Long, long[]> finalVectors) {
        if (finalVectors == null) {
            return Collections.singletonList("<missing>");
        }
        List<String> differences = new ArrayList<String>();
        for (int stageIndex = 0; stageIndex < debugLineageStageCount; stageIndex++) {
            DelayedEnv local = DEBUG_LOCAL_ENVIRONMENTS.get(Integer.valueOf(stageIndex));
            if (local == null) {
                continue;
            }
            List<Fluent> fluents = new ArrayList<Fluent>(local.trackedFluents);
            Collections.sort(fluents, new java.util.Comparator<Fluent>() {
                public int compare(Fluent left, Fluent right) {
                    return left.getName().compareTo(right.getName());
                }
            });
            for (Fluent fluent : fluents) {
                Set<Boolean> values = new LinkedHashSet<Boolean>();
                for (Long target : targets) {
                    long[] vector = finalVectors.get(target);
                    if (vector == null || vector[stageIndex] < 0L) {
                        continue;
                    }
                    values.add(Boolean.valueOf(local.valuation.isTrue(
                            Long.valueOf(vector[stageIndex]),
                            fluent)));
                }
                if (values.size() > 1) {
                    differences.add("stage" + (stageIndex + 1)
                            + ":" + fluent.getName());
                }
            }
        }
        return differences;
    }

    private static void debugFluentDefinitionConsistency(LTSOutput output) {
        Map<String, Set<String>> definitionsByName = new LinkedHashMap<String, Set<String>>();
        Map<String, Integer> occurrencesByName = new LinkedHashMap<String, Integer>();
        for (DelayedEnv local : DEBUG_LOCAL_ENVIRONMENTS.values()) {
            for (Fluent fluent : local.trackedFluents) {
                Set<String> definitions = definitionsByName.get(fluent.getName());
                if (definitions == null) {
                    definitions = new LinkedHashSet<String>();
                    definitionsByName.put(fluent.getName(), definitions);
                }
                definitions.add(debugFluentDefinitionFingerprint(fluent));
                Integer occurrences = occurrencesByName.get(fluent.getName());
                occurrencesByName.put(fluent.getName(),
                        Integer.valueOf(occurrences == null ? 1 : occurrences.intValue() + 1));
            }
        }
        int sharedNames = 0;
        int mismatchedNames = 0;
        int samples = 0;
        for (Map.Entry<String, Set<String>> entry : definitionsByName.entrySet()) {
            Integer occurrences = occurrencesByName.get(entry.getKey());
            if (occurrences != null && occurrences.intValue() > 1) {
                sharedNames++;
            }
            if (entry.getValue().size() > 1) {
                mismatchedNames++;
                if (samples < 8) {
                    output.outln("[StepwiseLineage] FLUENT_DEFINITION_MISMATCH name="
                            + entry.getKey() + " definitions=" + entry.getValue());
                    samples++;
                }
            }
        }
        output.outln("[StepwiseLineage] fluent definitions sharedNames=" + sharedNames
                + " mismatchedNames=" + mismatchedNames);
    }

    private static String debugFluentDefinitionFingerprint(Fluent fluent) {
        List<String> initiating = new ArrayList<String>();
        for (Object symbol : fluent.getInitiatingActions()) {
            initiating.add(String.valueOf(symbol));
        }
        Collections.sort(initiating);
        List<String> terminating = new ArrayList<String>();
        for (Object symbol : fluent.getTerminatingActions()) {
            terminating.add(String.valueOf(symbol));
        }
        Collections.sort(terminating);
        return "initial=" + fluent.getInitialValue()
                + ",initiating=" + initiating
                + ",terminating=" + terminating;
    }

    private static void debugConnectionMultiplicity(
            ConnectionPlan connectionPlan,
            DelayedEnv oldMeta,
            DelayedEnv mappingProduct,
            LTSOutput output) {
        if (!DEBUG_ACTION_DIAGNOSTICS || output == null) {
            return;
        }

        Map<Long, List<Long>> targetsByOldState = new LinkedHashMap<Long, List<Long>>();
        for (Connection connection : connectionPlan.connections) {
            List<Long> targets = targetsByOldState.get(connection.oldState);
            if (targets == null) {
                targets = new ArrayList<Long>();
                targetsByOldState.put(connection.oldState, targets);
            }
            targets.add(connection.mappingState);
        }

        int multiTargetSources = 0;
        int maxTargets = 0;
        int multiTargetSourcesWithOneMetadata = 0;
        int multiTargetSourcesWithMultipleMetadata = 0;
        int samples = 0;
        output.outln("[Stepwise Delayed DUCS][Debug] hotSwapIn multiplicity");
        for (Map.Entry<Long, List<Long>> entry : targetsByOldState.entrySet()) {
            List<Long> targets = entry.getValue();
            if (targets.size() <= 1) {
                continue;
            }
            multiTargetSources++;
            maxTargets = Math.max(maxTargets, targets.size());
            Set<String> metadataFingerprints = new LinkedHashSet<String>();
            for (Long target : targets) {
                metadataFingerprints.add(mappingMetadataFingerprint(mappingProduct, target));
            }
            if (metadataFingerprints.size() == 1) {
                multiTargetSourcesWithOneMetadata++;
            } else {
                multiTargetSourcesWithMultipleMetadata++;
            }
            if (samples < 8) {
                output.outln("  oldMeta=" + entry.getKey()
                        + " oldControllerOrigin=" + oldMeta.oldControllerOrigin.get(entry.getKey())
                        + " targets=" + targets.size()
                        + " distinctMappingMetadata=" + metadataFingerprints.size()
                        + " metadata=" + metadataFingerprints);
                samples++;
            }
        }
        output.outln("  multiTargetSources=" + multiTargetSources
                + " maxTargetsPerSource=" + maxTargets
                + " oneMetadata=" + multiTargetSourcesWithOneMetadata
                + " multipleMetadata=" + multiTargetSourcesWithMultipleMetadata);
    }

    private static String mappingMetadataFingerprint(DelayedEnv mappingProduct, Long state) {
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata =
                mappingProduct.stageMetadata.get(state);
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        List<Integer> stages = new ArrayList<Integer>(metadata.keySet());
        Collections.sort(stages);
        StringBuilder result = new StringBuilder("{");
        for (int i = 0; i < stages.size(); i++) {
            if (i > 0) {
                result.append(",");
            }
            Integer stage = stages.get(i);
            MappingEnvironmentGenerator.MappingStateMetadata stageMetadata = metadata.get(stage);
            result.append(stage)
                    .append(":")
                    .append(stageMetadata.getSide())
                    .append("(")
                    .append(stageMetadata.getOldEnvState())
                    .append(",")
                    .append(stageMetadata.getNewEnvState())
                    .append(")");
        }
        return result.append("}").toString();
    }

    private static String formatNanosAsMillis(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static void enqueueSynchronizedSuccessors(
            Map<String, List<Long>> oldTargetsByAction,
            Map<String, List<Long>> mappingTargetsByAction,
            Queue<Pair<Long, Long>> pending,
            ConnectionBuildStats stats) {
        if (oldTargetsByAction.size() <= mappingTargetsByAction.size()) {
            stats.indexedActionEntriesScanned += oldTargetsByAction.size();
            for (Map.Entry<String, List<Long>> oldEntry : oldTargetsByAction.entrySet()) {
                List<Long> mappingTargets = mappingTargetsByAction.get(oldEntry.getKey());
                if (mappingTargets != null) {
                    stats.indexedActionMatches++;
                    enqueueTargetPairs(oldEntry.getValue(), mappingTargets, pending, stats);
                }
            }
        } else {
            stats.indexedActionEntriesScanned += mappingTargetsByAction.size();
            for (Map.Entry<String, List<Long>> mappingEntry : mappingTargetsByAction.entrySet()) {
                List<Long> oldTargets = oldTargetsByAction.get(mappingEntry.getKey());
                if (oldTargets != null) {
                    stats.indexedActionMatches++;
                    enqueueTargetPairs(oldTargets, mappingEntry.getValue(), pending, stats);
                }
            }
        }
    }

    private static void enqueueTargetPairs(
            List<Long> oldTargets,
            List<Long> mappingTargets,
            Queue<Pair<Long, Long>> pending,
            ConnectionBuildStats stats) {
        for (Long oldTarget : oldTargets) {
            for (Long mappingTarget : mappingTargets) {
                pending.add(new Pair<Long, Long>(oldTarget, mappingTarget));
                stats.enqueuedPairs++;
                stats.synchronizedSuccessorPairs++;
            }
        }
    }

    private static TransitionIndex buildTransitionIndex(MTS<Long, String> env) {
        return new TransitionIndex(env);
    }

    private static List<Fluent> orderedConnectionComparisonFluents(Set<Fluent> comparisonFluents) {
        List<Fluent> result = new ArrayList<Fluent>();
        for (Fluent fluent : comparisonFluents) {
            if (!UpdatingControllersUtils.beginFluent.equals(fluent)) {
                result.add(fluent);
            }
        }
        Collections.sort(result, new java.util.Comparator<Fluent>() {
            public int compare(Fluent left, Fluent right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return result;
    }

    private static Map<Long, ValuationSignature> buildValuationSignatures(
            DelayedEnv env,
            List<Fluent> signatureFluents) {
        Map<Long, ValuationSignature> signatures = new HashMap<Long, ValuationSignature>();
        for (Long state : env.env.getStates()) {
            BitSet bits = new BitSet(signatureFluents.size());
            for (int i = 0; i < signatureFluents.size(); i++) {
                if (env.valuation.isTrue(state, signatureFluents.get(i))) {
                    bits.set(i);
                }
            }
            signatures.put(state, new ValuationSignature(bits));
        }
        return signatures;
    }

    private static Set<Long> buildEligibleMappingStates(DelayedEnv mappingProduct) {
        Set<Long> result = new HashSet<Long>();
        for (Long state : mappingProduct.env.getStates()) {
            if (isMappingOldSide(mappingProduct, state)
                    && phaseInitial(mappingProduct, state)) {
                result.add(state);
            }
        }
        return result;
    }

    private static long countEligibleMappingStates(DelayedEnv mappingProduct) {
        long count = 0;
        for (Long state : mappingProduct.env.getStates()) {
            if (isMappingOldSide(mappingProduct, state)
                    && phaseInitial(mappingProduct, state)) {
                count++;
            }
        }
        return count;
    }

    private static boolean valuationsMatch(
            DelayedEnv left,
            Long leftState,
            DelayedEnv right,
            Long rightState,
            Set<Fluent> comparisonFluents) {
        for (Fluent fluent : comparisonFluents) {
            if (UpdatingControllersUtils.beginFluent.equals(fluent)) {
                continue;
            }
            boolean leftValue = left.valuation.isTrue(leftState, fluent);
            boolean rightValue = right.valuation.isTrue(rightState, fluent);
            if (leftValue != rightValue) {
                return false;
            }
        }
        return true;
    }

    private static boolean phaseInitial(DelayedEnv env, Long state) {
        return !env.valuation.isTrue(state, UpdatingControllersUtils.stopFluent)
                && !env.valuation.isTrue(state, UpdatingControllersUtils.reconFluent)
                && !env.valuation.isTrue(state, UpdatingControllersUtils.startFluent);
    }

    private static boolean isMappingOldSide(DelayedEnv env, Long state) {
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> metadata = env.stageMetadata.get(state);
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        for (MappingEnvironmentGenerator.MappingStateMetadata stateMetadata : metadata.values()) {
            if (!MappingEnvironmentGenerator.MappingStateSide.OLD_SIDE.equals(stateMetadata.getSide())) {
                return false;
            }
        }
        return true;
    }

    static MTS<Long, String> connectOldAndMapping(
            MTS<Long, String> oldEnv,
            MTS<Long, String> mappingEnv,
            List<Connection> connections,
            Set<String> controllableActions) {
        long mappingOffset = nextFreshState(oldEnv);
        MTS<Long, String> result = new MTSImpl<Long, String>(oldEnv.getInitialState());
        for (Long state : oldEnv.getStates()) {
            result.addState(state);
        }
        for (Long state : mappingEnv.getStates()) {
            result.addState(mappingOffset + state);
        }
        for (Long state : oldEnv.getStates()) {
            for (Pair<String, Long> transition : oldEnv.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                String action = transition.getFirst();
                if (controllableActions.contains(action) && UpdatingControllersUtils.isNotUpdateAction(action)) {
                    action = action + UpdateConstants.OLD_LABEL;
                }
                result.addAction(action);
                result.addRequired(state, action, transition.getSecond());
            }
        }
        for (Long state : mappingEnv.getStates()) {
            for (Pair<String, Long> transition : mappingEnv.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                result.addAction(transition.getFirst());
                result.addRequired(mappingOffset + state, transition.getFirst(), mappingOffset + transition.getSecond());
            }
        }
        result.addAction(UpdateConstants.BEGIN_UPDATE);
        for (Connection connection : connections) {
            result.addRequired(connection.oldState, UpdateConstants.BEGIN_UPDATE, mappingOffset + connection.mappingState);
        }
        result.removeUnreachableStates();
        return result;
    }

    static MTS<Long, String> connectOldAndMappingView(
            MTS<Long, String> oldEnv,
            MTS<Long, String> mappingEnv,
            List<Connection> connections,
            Set<String> controllableActions) {
        return new ConnectedEnvironmentView(
                oldEnv,
                mappingEnv,
                connections,
                controllableActions);
    }

    private static long nextFreshState(MTS<Long, String> mts) {
        long next = 0L;
        for (Long state : mts.getStates()) {
            if (state >= next) {
                next = state + 1L;
            }
        }
        return next;
    }

    private static void addMappingRegionUpdateSelfLoops(MTS<Long, String> mapping) {
        mapping.addAction(UpdateConstants.STOP_OLD_SPEC);
        mapping.addAction(UpdateConstants.START_NEW_SPEC);
        for (Long state : new ArrayList<Long>(mapping.getStates())) {
            mapping.addRequired(state, UpdateConstants.STOP_OLD_SPEC, state);
            mapping.addRequired(state, UpdateConstants.START_NEW_SPEC, state);
        }
    }

    private static void addPassiveSelfLoopsForMissingActions(MTS<Long, String> environment, Set<String> globalActions) {
        addPassiveSelfLoopsForMissingActions(
                environment,
                globalActions,
                Collections.<Long>emptySet());
    }

    private static void addPassiveSelfLoopsForMissingActions(
            MTS<Long, String> environment,
            Set<String> globalActions,
            Set<Long> errorStates) {
        Set<String> localActions = new HashSet<String>(environment.getActions());
        for (String action : globalActions) {
            if (UpdateConstants.BEGIN_UPDATE.equals(action)) {
                continue;
            }
            if (!localActions.contains(action)) {
                environment.addAction(action);
                for (Long state : environment.getStates()) {
                    if (!errorStates.contains(state)) {
                        environment.addRequired(state, action, state);
                    }
                }
            }
        }
    }

    private static void requirePassiveActionAlphabet(
            DelayedEnv environment,
            Set<String> passiveActions,
            String context) {
        Set<String> missing = new java.util.TreeSet<String>(passiveActions);
        missing.removeAll(environment.env.getActions());
        if (!missing.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed partial product lost passive action alphabet in "
                    + context + ": " + missing + ". Passive transitions must be retained before "
                    + "fluent valuation is attached; adding raw self-loops here would freeze "
                    + "already tracked fluents.");
        }
    }

    private static void debugActionStatistics(
            String checkpoint,
            DelayedEnv environment,
            Set<Integer> stageScope,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            LTSOutput output) {
        if (!DEBUG_ACTION_DIAGNOSTICS || output == null) {
            return;
        }

        Map<String, DebugActionStats> statsByAction =
                new HashMap<String, DebugActionStats>();
        for (Long state : environment.env.getStates()) {
            for (Pair<String, Long> transition :
                    environment.env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                DebugActionStats stats = statsByAction.get(transition.getFirst());
                if (stats == null) {
                    stats = new DebugActionStats();
                    statsByAction.put(transition.getFirst(), stats);
                }
                stats.transitions++;
                if (state.equals(transition.getSecond())) {
                    stats.selfLoops++;
                } else {
                    stats.nonSelfTransitions++;
                }
            }
        }

        List<String> actions = new ArrayList<String>(statsByAction.keySet());
        Collections.sort(actions);
        output.outln("[StepwiseDebug] CHECKPOINT name=" + checkpoint
                + " scope=" + displayStageScope(stageScope)
                + " states=" + environment.env.getStates().size()
                + " transitions=" + countTransitions(environment.env)
                + " errors=" + environment.errorStates.size());
        for (String action : actions) {
            DebugActionStats stats = statsByAction.get(action);
            String normalizedAction = normalizeOldAction(action);
            Set<Integer> owners = ownersByAction.get(normalizedAction);
            if (owners == null) {
                owners = ownersByAction.get(action);
            }
            if (owners == null) {
                owners = Collections.emptySet();
            }
            boolean controllable = controllableActions.contains(action);
            boolean realInFragment = environment.realActions.contains(normalizedAction)
                    || environment.realActions.contains(action);
            output.outln("[StepwiseDebug] ACTION checkpoint=" + checkpoint
                    + " scope=" + displayStageScope(stageScope)
                    + " action=" + action
                    + " transitions=" + stats.transitions
                    + " selfLoops=" + stats.selfLoops
                    + " nonSelf=" + stats.nonSelfTransitions
                    + " controllable=" + controllable
                    + " realInFragment=" + realInFragment
                    + " owners=" + displayStageScope(owners));
        }
    }

    private static void debugPassiveTransitionInvariant(
            String checkpoint,
            DelayedEnv environment,
            Set<Integer> stageScope,
            Set<String> passiveActions,
            LTSOutput output) {
        if (!DEBUG_ACTION_DIAGNOSTICS || output == null) {
            return;
        }

        List<String> actions = new ArrayList<String>(passiveActions);
        Collections.sort(actions);
        for (String action : actions) {
            long checkedStates = 0L;
            long statesWithTransition = 0L;
            long transitions = 0L;
            long valuationMismatches = 0L;
            long selfLoops = 0L;
            long nonSelfTransitions = 0L;
            for (Long state : environment.env.getStates()) {
                if (environment.errorStates.contains(state)) {
                    continue;
                }
                checkedStates++;
                boolean hasTransition = false;
                Set<Fluent> sourceValuation = new LinkedHashSet<Fluent>(
                        environment.valuation.getFluentsFromState(state));
                Set<Fluent> expectedValuation = nextTrueFluents(
                        sourceValuation,
                        environment.trackedFluents,
                        action);
                for (Pair<String, Long> transition :
                        environment.env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    if (!action.equals(transition.getFirst())) {
                        continue;
                    }
                    hasTransition = true;
                    transitions++;
                    if (state.equals(transition.getSecond())) {
                        selfLoops++;
                    } else {
                        nonSelfTransitions++;
                    }
                    for (Fluent fluent : environment.trackedFluents) {
                        boolean expected = expectedValuation.contains(fluent);
                        boolean actual = environment.valuation.isTrue(
                                transition.getSecond(),
                                fluent);
                        if (expected != actual) {
                            valuationMismatches++;
                        }
                    }
                }
                if (hasTransition) {
                    statesWithTransition++;
                }
            }
            output.outln("[StepwiseDebug] PASSIVE checkpoint=" + checkpoint
                    + " scope=" + displayStageScope(stageScope)
                    + " action=" + action
                    + " checkedStates=" + checkedStates
                    + " statesWithTransition=" + statesWithTransition
                    + " transitions=" + transitions
                    + " valuationMismatches=" + valuationMismatches
                    + " selfLoops=" + selfLoops
                    + " nonSelfTransitions=" + nonSelfTransitions);
        }
    }

    private static void addPhaseComparisonFluents(Set<Fluent> fluents) {
        fluents.add(UpdatingControllersUtils.stopFluent);
        fluents.add(UpdatingControllersUtils.reconFluent);
        fluents.add(UpdatingControllersUtils.startFluent);
    }

    private static List<CrossComponent> buildCrossComponents(List<StepwiseClassifiedGoal> crossGoals) {
        List<CrossComponent> components = new ArrayList<CrossComponent>();
        for (StepwiseClassifiedGoal goal : crossGoals) {
            Set<Integer> scope = goal.getStageScope();
            if (scope == null || scope.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed cross goal has empty stage scope: " + goal.getName() + ".");
            }

            List<CrossComponent> overlapping = new ArrayList<CrossComponent>();
            for (CrossComponent component : components) {
                if (component.overlaps(scope)) {
                    overlapping.add(component);
                }
            }

            if (overlapping.isEmpty()) {
                CrossComponent component = new CrossComponent();
                component.addGoal(goal);
                components.add(component);
            } else {
                CrossComponent primary = overlapping.get(0);
                primary.addGoal(goal);
                for (int i = 1; i < overlapping.size(); i++) {
                    CrossComponent merged = overlapping.get(i);
                    primary.merge(merged);
                    components.remove(merged);
                }
            }
        }

        Collections.sort(components, new java.util.Comparator<CrossComponent>() {
            public int compare(CrossComponent left, CrossComponent right) {
                return left.firstStageIndex() - right.firstStageIndex();
            }
        });
        for (int i = 0; i < components.size(); i++) {
            components.get(i).setId(i + 1);
        }
        return components;
    }

    private static void outputCrossComponents(LTSOutput output, List<CrossComponent> crossComponents) {
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Cross components:");
        if (crossComponents.isEmpty()) {
            output.outln("  none");
            return;
        }
        for (CrossComponent component : crossComponents) {
            output.outln("  component " + component.getId()
                    + " stages=" + displayStageScope(component.getStageScope())
                    + " goals=" + goalNames(component.getGoals()));
        }
    }

    static Set<String> mappingRealActions(MTS<Long, String> mapping) {
        Set<String> realActions = new HashSet<String>(mapping.getActions());
        // CompactState reserves alphabet slot 0 for tau even when the model has
        // no tau transition. Tau is internal, not a component-owned event.
        realActions.remove(MTSConstants.TAU);
        return realActions;
    }

    private static Set<String> realActionsForScope(Set<Integer> stageScope, List<StageBase> bases) {
        Set<String> result = new HashSet<String>();
        for (Integer stageIndex : stageScope) {
            result.addAll(bases.get(stageIndex).realActions);
        }
        return result;
    }

    private static Set<String> outOfScopeActionsForScope(Set<Integer> stageScope, List<StageBase> bases) {
        Set<String> result = new HashSet<String>();
        for (int i = 0; i < bases.size(); i++) {
            if (!stageScope.contains(i)) {
                result.addAll(bases.get(i).realActions);
            }
        }
        return result;
    }

    private static String displayStageScope(Set<Integer> stageScope) {
        StringBuilder display = new StringBuilder();
        display.append("{");
        int index = 0;
        for (Integer stageIndex : stageScope) {
            if (index > 0) {
                display.append(",");
            }
            display.append(stageIndex + 1);
            index++;
        }
        display.append("}");
        return display.toString();
    }

    private static Set<Integer> singletonStageScope(int stageIndex) {
        Set<Integer> scope = new java.util.TreeSet<Integer>();
        scope.add(stageIndex);
        return scope;
    }

    private static Set<Integer> allStageScope(int stageCount) {
        Set<Integer> scope = new java.util.TreeSet<Integer>();
        for (int i = 0; i < stageCount; i++) {
            scope.add(i);
        }
        return scope;
    }

    private static TupleTransitionIndex indexTupleTransitions(
            List<DelayedEnv> inputs,
            List<Long> tuple) {
        Set<String> actions = new HashSet<String>();
        List<Map<String, List<Long>>> targetsByInput =
                new ArrayList<Map<String, List<Long>>>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            Map<String, List<Long>> targetsByAction = new HashMap<String, List<Long>>();
            for (Pair<String, Long> transition :
                    inputs.get(i).env.getTransitions(tuple.get(i), MTS.TransitionType.REQUIRED)) {
                String action = transition.getFirst();
                actions.add(action);
                List<Long> targets = targetsByAction.get(action);
                if (targets == null) {
                    targets = new ArrayList<Long>();
                    targetsByAction.put(action, targets);
                }
                if (!targets.contains(transition.getSecond())) {
                    targets.add(transition.getSecond());
                }
            }
            targetsByInput.add(targetsByAction);
        }
        return new TupleTransitionIndex(actions, targetsByInput);
    }

    private static boolean isErrorTuple(List<DelayedEnv> inputs, List<Long> tuple) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).errorStates.contains(tuple.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static List<List<Long>> targetChoices(
            String action,
            List<Long> tuple,
            List<DelayedEnv> inputs,
            TupleTransitionIndex transitionIndex) {
        List<List<Long>> choices = new ArrayList<List<Long>>(inputs.size());
        boolean updateAction = isUpdateAction(action);
        boolean hasOwner = false;
        String baseAction = normalizeOldAction(action);
        for (int i = 0; i < inputs.size(); i++) {
            List<Long> targets = transitionIndex.targets(i, action);
            boolean owner = updateAction || inputs.get(i).realActions.contains(baseAction);
            if (owner) {
                hasOwner = true;
            }
            if (targets.isEmpty()) {
                if (owner || inputs.get(i).env.getActions().contains(action)) {
                    return null;
                }
                choices.add(Collections.singletonList(tuple.get(i)));
            } else {
                choices.add(targets);
            }
        }
        if (!updateAction && !hasOwner && MTSConstants.TAU.equals(baseAction)) {
            return null;
        }
        return choices;
    }

    private static long addCartesianProductTransitions(
            List<List<Long>> choices,
            int index,
            List<Long> current,
            List<DelayedEnv> inputs,
            Map<List<Long>, Long> tupleToState,
            Map<Long, List<Long>> debugStateToTuple,
            Set<Long> productErrorStates,
            Queue<List<Long>> pending,
            MTS<Long, String> product,
            FluentStateValuation<Long> valuation,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata,
            Long fromState,
            String action,
            long nextStateId) {
        if (index == choices.size()) {
            List<Long> targetTuple = new ArrayList<Long>(current);
            Long targetState = tupleToState.get(targetTuple);
            if (targetState == null) {
                targetState = nextStateId++;
                tupleToState.put(targetTuple, targetState);
                if (debugStateToTuple != null) {
                    debugStateToTuple.put(targetState, targetTuple);
                }
                product.addState(targetState);
                addProductStateData(targetState, targetTuple, inputs, valuation, metadata);
                if (isErrorTuple(inputs, targetTuple)) {
                    productErrorStates.add(targetState);
                } else {
                    pending.add(targetTuple);
                }
            }
            product.addRequired(fromState, action, targetState);
            return nextStateId;
        }
        for (Long target : choices.get(index)) {
            current.add(target);
            nextStateId = addCartesianProductTransitions(
                    choices,
                    index + 1,
                    current,
                    inputs,
                    tupleToState,
                    debugStateToTuple,
                    productErrorStates,
                    pending,
                    product,
                    valuation,
                    metadata,
                    fromState,
                    action,
                    nextStateId);
            current.remove(current.size() - 1);
        }
        return nextStateId;
    }

    private static void addProductStateData(
            Long productState,
            List<Long> tuple,
            List<DelayedEnv> inputs,
            FluentStateValuation<Long> valuation,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata) {
        if (!valuation.getStates().contains(productState)) {
            valuation.addState(productState);
        }
        Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> stateMetadata =
                new HashMap<Integer, MappingEnvironmentGenerator.MappingStateMetadata>();
        for (int i = 0; i < inputs.size(); i++) {
            DelayedEnv input = inputs.get(i);
            Long inputState = tuple.get(i);
            for (Fluent fluent : input.valuation.getFluentsFromState(inputState)) {
                valuation.addHoldingFluent(productState, fluent);
            }
            Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata> inputMetadata =
                    input.stageMetadata.get(inputState);
            if (inputMetadata != null) {
                stateMetadata.putAll(inputMetadata);
            }
        }
        metadata.put(productState, stateMetadata);
    }

    private static final class TupleTransitionIndex {
        final Set<String> enabledActions;
        final List<Map<String, List<Long>>> targetsByInput;

        TupleTransitionIndex(
                Set<String> enabledActions,
                List<Map<String, List<Long>>> targetsByInput) {
            this.enabledActions = enabledActions;
            this.targetsByInput = targetsByInput;
        }

        List<Long> targets(int inputIndex, String action) {
            List<Long> targets = targetsByInput.get(inputIndex).get(action);
            return targets == null ? Collections.<Long>emptyList() : targets;
        }
    }

    private static void validateSupportedGoal(String label, ControllerGoalDefinition goal) {
        if (!isEmpty(goal.getAssumeDefinitions())) {
            Diagnostics.fatal("stepwise_delayed does not support assume in " + label + ".");
        }
        if (!isEmpty(goal.getGuaranteeDefinitions())) {
            Diagnostics.fatal("stepwise_delayed does not support guarantee in " + label + ".");
        }
        if (!isEmpty(goal.getFaultsDefinitions())) {
            Diagnostics.fatal("stepwise_delayed does not support fault in " + label + ".");
        }
        if (!isEmpty(goal.getBuchiDefinitions())) {
            Diagnostics.fatal("stepwise_delayed does not support buchi/liveness in " + label + ".");
        }
        if (!isEmpty(goal.getConcurrencyDefinitions()) || !isEmpty(goal.getActivityDefinitions())) {
            Diagnostics.fatal("stepwise_delayed does not support concurrency/activity fluents in "
                    + label + ".");
        }
        if (!isEmpty(goal.getMarkingDefinitions()) || !isEmpty(goal.getDisturbanceActions())
                || goal.isPermissive() || goal.isReachability() || goal.isNonTransient()
                || goal.isExceptionHandling() || goal.isTestLatency()) {
            Diagnostics.fatal("stepwise_delayed supports only safety, controllable, and optional nonblocking in "
                    + label + ".");
        }
    }

    private static boolean isEmpty(Collection<?> values) {
        return values == null || values.isEmpty();
    }

    private static boolean isUpdateAction(String action) {
        return UpdateConstants.BEGIN_UPDATE.equals(action)
                || UpdateConstants.STOP_OLD_SPEC.equals(action)
                || UpdateConstants.RECONFIGURE.equals(action)
                || UpdateConstants.START_NEW_SPEC.equals(action)
                || UpdateConstants.FINISH_UPDATE.equals(action)
                || action.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)
                || action.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                || action.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX);
    }

    private static String normalizeOldAction(String action) {
        if (UpdatingControllersUtils.isOld(action)) {
            return UpdatingControllersUtils.withoutOld(action);
        }
        return action;
    }

    private static void outputGoalNames(LTSOutput output, String label, List<StepwiseClassifiedGoal> goals) {
        output.outln("  " + label + ": " + goalNames(goals));
    }

    private static List<String> goalNames(List<StepwiseClassifiedGoal> goals) {
        List<String> names = new ArrayList<String>();
        for (StepwiseClassifiedGoal goal : goals) {
            names.add(goal.getName() + "(" + goal.getKind() + ")");
        }
        return names;
    }

    private static StateSpaceStats outputStateSpace(LTSOutput output, String label, MTS<Long, String> mts) {
        long countStart = System.currentTimeMillis();
        int states = mts.getStates().size();
        long transitions = countTransitions(mts);
        long countTime = System.currentTimeMillis() - countStart;
        output.outln(label + " states: " + states
                + " transitions: " + transitions);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "Stepwise Delayed DUC 最大状態数と遷移数",
                label,
                states,
                transitions,
                countTime);
        return new StateSpaceStats(states, transitions, countTime);
    }

    private static void recordScopedStateSpace(
            String label,
            Set<Integer> scope,
            StateSpaceStats stats) {
        StepwiseDelayedEvaluationMetrics.recordScopedStateSpace(
                label,
                scope,
                stats.states,
                stats.transitions,
                stats.countTime);
    }

    private static void recordScopedTime(String label, Set<Integer> scope, long timeMillis) {
        StepwiseDelayedEvaluationMetrics.recordScopedTime(label, scope, timeMillis);
    }

    private static void recordScopedFluentCount(String label, Set<Integer> scope, Set<Fluent> fluents) {
        StepwiseDelayedEvaluationMetrics.recordScopedFluentCount(label, scope, fluents);
    }

    private static long countTransitions(MTS<Long, String> mts) {
        return ltsa.updatingControllers.EvaluationTransitionCounter.countRequiredAndMaybe(mts);
    }

    private static final class StateSpaceStats {
        private final int states;
        private final long transitions;
        private final long countTime;

        private StateSpaceStats(int states, long transitions, long countTime) {
            this.states = states;
            this.transitions = transitions;
            this.countTime = countTime;
        }
    }

    private static final class ScopeRequirementCounts {
        private final Set<Integer> scope;
        private final Set<Fluent> requirementFluents = new LinkedHashSet<Fluent>();
        private int oldSafetyGoals;
        private int newSafetyGoals;
        private int transitionGoals;

        private ScopeRequirementCounts(Set<Integer> scope) {
            this.scope = new java.util.TreeSet<Integer>(scope);
        }

        private void add(StepwiseClassifiedGoal goal) {
            if (StepwiseRequirementKind.OLD_SAFETY.equals(goal.getKind())) {
                oldSafetyGoals++;
            } else if (StepwiseRequirementKind.NEW_SAFETY.equals(goal.getKind())) {
                newSafetyGoals++;
            } else {
                transitionGoals++;
            }
            requirementFluents.addAll(goal.getFluents());
        }

        private int totalGoals() {
            return oldSafetyGoals + newSafetyGoals + transitionGoals;
        }

        private int requirementFluentCount() {
            return requirementFluents.size();
        }
    }

    private static final class IncrementalPruningCounter {
        private int next = 1;

        private int next() {
            return next++;
        }
    }

    private static final class CrossSchedulingStats {
        private long componentCount;
        private long stepCount;
        private long candidateEvaluations;
        private long nonFirstSelections;
        private long selectionNanos;
        private long selectedFragmentCountTotal;
        private long selectedFragmentCountMax;
        private long mergedScopeSizeTotal;
        private long mergedScopeSizeMax;
        private long batchGoalCountTotal;
        private long batchGoalCountMax;
        private BigInteger maxCost = BigInteger.ZERO;
        private double maxCostLog10 = 0.0;
        private double costLog10Total = 0.0;
        private long metricRecordingCountTimeMillis;

        private void addChoice(
                CrossGoalScheduleChoice choice,
                long schedulingNanos,
                long detailRecordCountTimeMillis) {
            stepCount++;
            candidateEvaluations += choice.candidateEvaluationCount;
            if (choice.fixedOrderIndex > 0) {
                nonFirstSelections++;
            }
            selectionNanos += Math.max(0, schedulingNanos);
            selectedFragmentCountTotal += choice.selectedFragments.size();
            selectedFragmentCountMax = Math.max(selectedFragmentCountMax, choice.selectedFragments.size());
            mergedScopeSizeTotal += choice.mergedScope.size();
            mergedScopeSizeMax = Math.max(mergedScopeSizeMax, choice.mergedScope.size());
            batchGoalCountTotal += choice.batchGoalCount;
            batchGoalCountMax = Math.max(batchGoalCountMax, choice.batchGoalCount);
            if (choice.cost.compareTo(maxCost) > 0) {
                maxCost = choice.cost;
            }
            double costLog10 = log10(choice.cost);
            maxCostLog10 = Math.max(maxCostLog10, costLog10);
            costLog10Total += costLog10;
            metricRecordingCountTimeMillis += Math.max(0, detailRecordCountTimeMillis);
        }

        private void add(CrossSchedulingStats other) {
            if (other == null) {
                return;
            }
            componentCount += other.componentCount;
            stepCount += other.stepCount;
            candidateEvaluations += other.candidateEvaluations;
            nonFirstSelections += other.nonFirstSelections;
            selectionNanos += other.selectionNanos;
            selectedFragmentCountTotal += other.selectedFragmentCountTotal;
            selectedFragmentCountMax = Math.max(selectedFragmentCountMax, other.selectedFragmentCountMax);
            mergedScopeSizeTotal += other.mergedScopeSizeTotal;
            mergedScopeSizeMax = Math.max(mergedScopeSizeMax, other.mergedScopeSizeMax);
            batchGoalCountTotal += other.batchGoalCountTotal;
            batchGoalCountMax = Math.max(batchGoalCountMax, other.batchGoalCountMax);
            if (other.maxCost.compareTo(maxCost) > 0) {
                maxCost = other.maxCost;
            }
            maxCostLog10 = Math.max(maxCostLog10, other.maxCostLog10);
            costLog10Total += other.costLog10Total;
            metricRecordingCountTimeMillis += other.metricRecordingCountTimeMillis;
        }
    }

    private static final class CrossGoalScheduleChoice {
        private final StepwiseClassifiedGoal goal;
        private final List<ScopedDelayedEnv> selectedFragments;
        private final Set<Integer> mergedScope;
        private final int batchGoalCount;
        private final BigInteger cost;
        private final int fixedOrderIndex;
        private final int candidateEvaluationCount;

        private CrossGoalScheduleChoice(
                StepwiseClassifiedGoal goal,
                List<ScopedDelayedEnv> selectedFragments,
                Set<Integer> mergedScope,
                int batchGoalCount,
                BigInteger cost,
                int fixedOrderIndex,
                int candidateEvaluationCount) {
            this.goal = goal;
            this.selectedFragments = new ArrayList<ScopedDelayedEnv>(selectedFragments);
            this.mergedScope = new java.util.TreeSet<Integer>(mergedScope);
            this.batchGoalCount = batchGoalCount;
            this.cost = cost;
            this.fixedOrderIndex = fixedOrderIndex;
            this.candidateEvaluationCount = candidateEvaluationCount;
        }
    }

    private static final class CrossComponent {
        private int id;
        private final Set<Integer> stageScope = new java.util.TreeSet<Integer>();
        private final List<StepwiseClassifiedGoal> goals = new ArrayList<StepwiseClassifiedGoal>();

        private int getId() {
            return id;
        }

        private void setId(int id) {
            this.id = id;
        }

        private Set<Integer> getStageScope() {
            return stageScope;
        }

        private List<StepwiseClassifiedGoal> getGoals() {
            return goals;
        }

        private void addGoal(StepwiseClassifiedGoal goal) {
            goals.add(goal);
            stageScope.addAll(goal.getStageScope());
        }

        private void merge(CrossComponent other) {
            stageScope.addAll(other.stageScope);
            goals.addAll(other.goals);
        }

        private boolean overlaps(Set<Integer> otherScope) {
            for (Integer stageIndex : otherScope) {
                if (stageScope.contains(stageIndex)) {
                    return true;
                }
            }
            return false;
        }

        private int firstStageIndex() {
            if (stageScope.isEmpty()) {
                return Integer.MAX_VALUE;
            }
            return stageScope.iterator().next();
        }
    }

    private static final class ScopedDelayedEnv {
        private final Set<Integer> stageScope;
        private final DelayedEnv env;
        // Stages in this fragment that have not yet participated in a staged
        // cross-pruning step. mergeFragments unions this pending set.
        private final Set<Integer> addedStageScope;

        private ScopedDelayedEnv(Set<Integer> stageScope, DelayedEnv env, Set<Integer> addedStageScope) {
            this.stageScope = new java.util.TreeSet<Integer>(stageScope);
            this.env = env;
            this.addedStageScope = new java.util.TreeSet<Integer>(addedStageScope);
        }
    }

    private static final class DebugActionStats {
        private long transitions;
        private long selfLoops;
        private long nonSelfTransitions;
    }

    private static final class StageBase {
        private final StepwiseStage stage;
        private final MTS<Long, String> mapping;
        private final Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata;
        private final Set<String> realActions;

        private StageBase(
                StepwiseStage stage,
                MTS<Long, String> mapping,
                Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
                Set<String> realActions) {
            this.stage = stage;
            this.mapping = mapping;
            this.stageMetadata = stageMetadata;
            this.realActions = realActions;
        }
    }

    static final class DelayedEnv {
        private final MTS<Long, String> env;
        private final Set<Fluent> trackedFluents;
        private final FluentStateValuation<Long> valuation;
        private final Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata;
        private final Map<Long, Long> oldControllerOrigin;
        private final Set<String> realActions;
        private final Set<Long> errorStates;

        DelayedEnv(
                MTS<Long, String> env,
                Set<Fluent> trackedFluents,
                FluentStateValuation<Long> valuation,
                Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
                Map<Long, Long> oldControllerOrigin,
                Set<String> realActions) {
            this(env, trackedFluents, valuation, stageMetadata, oldControllerOrigin,
                    realActions, Collections.<Long>emptySet());
        }

        DelayedEnv(
                MTS<Long, String> env,
                Set<Fluent> trackedFluents,
                FluentStateValuation<Long> valuation,
                Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
                Map<Long, Long> oldControllerOrigin,
                Set<String> realActions,
                Set<Long> errorStates) {
            this.env = env;
            this.trackedFluents = new LinkedHashSet<Fluent>(trackedFluents);
            this.valuation = valuation;
            this.stageMetadata = stageMetadata;
            this.oldControllerOrigin = oldControllerOrigin;
            this.realActions = new HashSet<String>(realActions);
            this.errorStates = new LinkedHashSet<Long>(errorStates);
            this.errorStates.retainAll(env.getStates());
        }

        MTS<Long, String> getEnvironment() {
            return env;
        }

        Set<Fluent> getTrackedFluents() {
            return new LinkedHashSet<Fluent>(trackedFluents);
        }

        FluentStateValuation<Long> getValuation() {
            return valuation;
        }
    }

    private static final class ProductStateKey {
        private final Long baseState;
        private final Set<Fluent> trueFluents;

        private ProductStateKey(Long baseState, Set<Fluent> trueFluents) {
            this.baseState = baseState;
            this.trueFluents = new LinkedHashSet<Fluent>(trueFluents);
        }

        public boolean equals(Object other) {
            if (!(other instanceof ProductStateKey)) {
                return false;
            }
            ProductStateKey that = (ProductStateKey) other;
            return baseState.equals(that.baseState) && trueFluents.equals(that.trueFluents);
        }

        public int hashCode() {
            return 31 * baseState.hashCode() + trueFluents.hashCode();
        }
    }

    static final class Connection {
        private final Long oldState;
        private final Long mappingState;

        Connection(Long oldState, Long mappingState) {
            this.oldState = oldState;
            this.mappingState = mappingState;
        }
    }

    /**
     * Immutable logical union of the old-controller meta environment and the
     * final mapping environment. Mapping states keep the exact logical IDs
     * used by the materialized connector, but their source relations are read
     * through an offset adapter instead of being copied into another MTSImpl.
     *
     * The current consumer reads this object only to record its size and to
     * convert it immediately for DontDoTwice. Keeping this view private to the
     * delayed Stepwise pipeline prevents mutation or ownership assumptions
     * from leaking into general MTS code.
     */
    private static final class ConnectedEnvironmentView implements MTS<Long, String> {
        private static final BinaryRelation<String, Long> EMPTY_RELATION =
                new EmptyBinaryRelation();

        private final Long initialState;
        private final Set<Long> states;
        private final Set<String> actions;
        private final Map<Long, BinaryRelation<String, Long>> requiredTransitions;
        private final Map<Long, BinaryRelation<String, Long>> maybeTransitions;

        private ConnectedEnvironmentView(
                MTS<Long, String> oldEnv,
                MTS<Long, String> mappingEnv,
                List<Connection> connections,
                Set<String> controllableActions) {
            this.initialState = oldEnv.getInitialState();
            long mappingOffset = nextFreshState(oldEnv);

            // Match the insertion and alphabet construction performed by the
            // legacy connector. In particular, alphabet-only input actions are
            // not copied, while labels from unreachable REQUIRED transitions
            // remain after reachability pruning.
            Set<Long> allStates = new HashSet<Long>();
            allStates.add(initialState);
            Map<Long, BinaryRelation<String, Long>> transitions =
                    new HashMap<Long, BinaryRelation<String, Long>>();
            Set<String> connectedActions = new HashSet<String>();

            for (Long state : oldEnv.getStates()) {
                allStates.add(state);
                transitions.put(state, new MapSetBinaryRelation<String, Long>());
            }
            for (Long state : mappingEnv.getStates()) {
                Long connectedState = mappingOffset + state;
                if (!allStates.add(connectedState)) {
                    throw new IllegalArgumentException(
                            "old/mapping state-ID collision at " + connectedState);
                }
                transitions.put(
                        connectedState,
                        new OffsetBinaryRelation(
                                mappingEnv.getTransitions(state, MTS.TransitionType.REQUIRED),
                                mappingOffset));
            }

            for (Long state : oldEnv.getStates()) {
                BinaryRelation<String, Long> oldConnectedTransitions = transitions.get(state);
                for (Pair<String, Long> transition :
                        oldEnv.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    String action = oldConnectedAction(
                            transition.getFirst(),
                            controllableActions);
                    connectedActions.add(action);
                    oldConnectedTransitions.addPair(action, transition.getSecond());
                }
            }
            for (Long state : mappingEnv.getStates()) {
                for (Pair<String, Long> transition :
                        mappingEnv.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    connectedActions.add(transition.getFirst());
                }
            }

            connectedActions.add(UpdateConstants.BEGIN_UPDATE);
            for (Connection connection : connections) {
                Long mappingTarget = mappingOffset + connection.mappingState;
                BinaryRelation<String, Long> sourceTransitions =
                        transitions.get(connection.oldState);
                if (sourceTransitions == null || !allStates.contains(mappingTarget)) {
                    throw new IllegalArgumentException(
                            "invalid hotSwapIn connection " + connection.oldState
                                    + " -> " + connection.mappingState);
                }
                sourceTransitions.addPair(
                        UpdateConstants.BEGIN_UPDATE,
                        mappingTarget);
            }

            Set<Long> reachable = reachableStates(initialState, transitions);
            allStates.retainAll(reachable);
            transitions.keySet().retainAll(reachable);

            this.states = Collections.unmodifiableSet(allStates);
            this.actions = Collections.unmodifiableSet(connectedActions);
            Map<Long, BinaryRelation<String, Long>> readOnlyRequired =
                    new HashMap<Long, BinaryRelation<String, Long>>();
            Map<Long, BinaryRelation<String, Long>> emptyMaybes =
                    new HashMap<Long, BinaryRelation<String, Long>>();
            for (Long state : allStates) {
                readOnlyRequired.put(
                        state,
                        new ReadOnlyBinaryRelation(transitions.get(state)));
                emptyMaybes.put(state, EMPTY_RELATION);
            }
            this.requiredTransitions = Collections.unmodifiableMap(readOnlyRequired);
            this.maybeTransitions = Collections.unmodifiableMap(emptyMaybes);
        }

        private static String oldConnectedAction(
                String action,
                Set<String> controllableActions) {
            if (controllableActions.contains(action)
                    && UpdatingControllersUtils.isNotUpdateAction(action)) {
                return action + UpdateConstants.OLD_LABEL;
            }
            return action;
        }

        private static Set<Long> reachableStates(
                Long initialState,
                Map<Long, BinaryRelation<String, Long>> transitions) {
            Set<Long> reachable = new HashSet<Long>();
            Queue<Long> pending = new ArrayDeque<Long>();
            reachable.add(initialState);
            pending.add(initialState);
            while (!pending.isEmpty()) {
                Long state = pending.remove();
                BinaryRelation<String, Long> outgoing = transitions.get(state);
                if (outgoing == null) {
                    continue;
                }
                for (Pair<String, Long> transition : outgoing) {
                    if (reachable.add(transition.getSecond())) {
                        pending.add(transition.getSecond());
                    }
                }
            }
            return reachable;
        }

        public Set<Long> getStates() {
            return states;
        }

        public Set<String> getActions() {
            return actions;
        }

        public Long getInitialState() {
            return initialState;
        }

        public Map<Long, BinaryRelation<String, Long>> getTransitions(
                MTS.TransitionType type) {
            if (MTS.TransitionType.MAYBE.equals(type)) {
                return maybeTransitions;
            }
            return requiredTransitions;
        }

        public BinaryRelation<String, Long> getTransitions(
                Long state,
                MTS.TransitionType type) {
            if (!states.contains(state)) {
                return null;
            }
            if (MTS.TransitionType.MAYBE.equals(type)) {
                return maybeTransitions.get(state);
            }
            return requiredTransitions.get(state);
        }

        public int getNumberOfTransitions() {
            // Preserve the historical MTSImpl API result. Despite its name,
            // MTSImpl counts the state-key entries in all three transition
            // maps rather than the contained transition pairs.
            return states.size() * MTS.TransitionType.values().length;
        }

        public boolean removeUnreachableStates() {
            return false;
        }

        public boolean addState(Long state) {
            throw readOnly();
        }

        public boolean addStates(Collection<? extends Long> states) {
            throw readOnly();
        }

        public boolean addAction(String action) {
            throw readOnly();
        }

        public boolean addActions(Collection<? extends String> actions) {
            throw readOnly();
        }

        public void removeAction(String action) {
            throw readOnly();
        }

        public void setInitialState(Long state) {
            throw readOnly();
        }

        public boolean addTransition(
                Long from,
                String label,
                Long to,
                MTS.TransitionType type) {
            throw readOnly();
        }

        public boolean addRequired(Long from, String label, Long to) {
            throw readOnly();
        }

        public boolean addPossible(Long from, String label, Long to) {
            throw readOnly();
        }

        public boolean removeTransition(
                Long from,
                String label,
                Long to,
                MTS.TransitionType type) {
            throw readOnly();
        }

        public boolean removeRequired(Long from, String label, Long to) {
            throw readOnly();
        }

        public boolean removePossible(Long from, String label, Long to) {
            throw readOnly();
        }

        public void setWinningStates(Set<Long> winningStatesOfPlant) {
            throw readOnly();
        }

        public void setWinningStatesOfOriginalEnv(Set<Long> winningStates) {
            throw readOnly();
        }

        public Set<Long> getWinningStatesOfOriginalEnv() {
            return Collections.emptySet();
        }

        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException(
                    "connected environment view is read-only");
        }
    }

    private static final class ReadOnlyBinaryRelation
            extends AbstractSet<Pair<String, Long>>
            implements BinaryRelation<String, Long> {
        private final BinaryRelation<String, Long> source;

        private ReadOnlyBinaryRelation(BinaryRelation<String, Long> source) {
            this.source = source;
        }

        public Iterator<Pair<String, Long>> iterator() {
            final Iterator<Pair<String, Long>> sourceIterator = source.iterator();
            return new Iterator<Pair<String, Long>>() {
                public boolean hasNext() {
                    return sourceIterator.hasNext();
                }

                public Pair<String, Long> next() {
                    return sourceIterator.next();
                }

                public void remove() {
                    throw readOnlyRelation();
                }
            };
        }

        public int size() {
            return source.size();
        }

        public Set<Long> getImage(String action) {
            return Collections.unmodifiableSet(source.getImage(action));
        }

        public boolean addPair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean addPair(String first, Long second) {
            throw readOnlyRelation();
        }

        public boolean removePair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean removePair(String first, Long second) {
            throw readOnlyRelation();
        }
    }

    private static final class OffsetBinaryRelation
            extends AbstractSet<Pair<String, Long>>
            implements BinaryRelation<String, Long> {
        private final BinaryRelation<String, Long> source;
        private final long offset;

        private OffsetBinaryRelation(
                BinaryRelation<String, Long> source,
                long offset) {
            this.source = source;
            this.offset = offset;
        }

        public Iterator<Pair<String, Long>> iterator() {
            final Iterator<Pair<String, Long>> sourceIterator = source.iterator();
            return new Iterator<Pair<String, Long>>() {
                public boolean hasNext() {
                    return sourceIterator.hasNext();
                }

                public Pair<String, Long> next() {
                    Pair<String, Long> transition = sourceIterator.next();
                    return Pair.create(
                            transition.getFirst(),
                            offset + transition.getSecond());
                }

                public void remove() {
                    throw readOnlyRelation();
                }
            };
        }

        public int size() {
            return source.size();
        }

        public Set<Long> getImage(String action) {
            Set<Long> result = new HashSet<Long>();
            for (Pair<String, Long> transition : this) {
                if (action.equals(transition.getFirst())) {
                    result.add(transition.getSecond());
                }
            }
            return Collections.unmodifiableSet(result);
        }

        public boolean addPair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean addPair(String first, Long second) {
            throw readOnlyRelation();
        }

        public boolean removePair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean removePair(String first, Long second) {
            throw readOnlyRelation();
        }
    }

    private static final class EmptyBinaryRelation
            extends AbstractSet<Pair<String, Long>>
            implements BinaryRelation<String, Long> {
        public Iterator<Pair<String, Long>> iterator() {
            return Collections.<Pair<String, Long>>emptySet().iterator();
        }

        public int size() {
            return 0;
        }

        public Set<Long> getImage(String action) {
            return Collections.emptySet();
        }

        public boolean addPair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean addPair(String first, Long second) {
            throw readOnlyRelation();
        }

        public boolean removePair(Pair<String, Long> pair) {
            throw readOnlyRelation();
        }

        public boolean removePair(String first, Long second) {
            throw readOnlyRelation();
        }
    }

    private static UnsupportedOperationException readOnlyRelation() {
        return new UnsupportedOperationException(
                "connected environment transition relation is read-only");
    }

    private static final class ConnectedEnvironmentInputs {
        private final MTS<Long, String> oldEnvironment;
        private final MTS<Long, String> mappingEnvironment;
        private final List<Connection> connections;
        private final Set<String> controllableActions;

        private ConnectedEnvironmentInputs(
                MTS<Long, String> oldEnvironment,
                MTS<Long, String> mappingEnvironment,
                List<Connection> connections,
                Set<String> controllableActions) {
            this.oldEnvironment = oldEnvironment;
            this.mappingEnvironment = mappingEnvironment;
            this.connections = connections;
            this.controllableActions = controllableActions;
        }
    }

    private static final class DontDoTwiceInputs {
        private final CompactState connectedAutomaton;
        private final Set<String> connectedAlphabet;
        private final long dontDoTwiceStartMillis;

        private DontDoTwiceInputs(
                CompactState connectedAutomaton,
                Set<String> connectedAlphabet,
                long dontDoTwiceStartMillis) {
            this.connectedAutomaton = connectedAutomaton;
            this.connectedAlphabet = connectedAlphabet;
            this.dontDoTwiceStartMillis = dontDoTwiceStartMillis;
        }
    }

    private static final class TransitionIndex {
        private final MTS<Long, String> env;
        private final Map<Long, Map<String, List<Long>>> cache =
                new HashMap<Long, Map<String, List<Long>>>();

        private TransitionIndex(MTS<Long, String> env) {
            this.env = env;
        }

        private Map<String, List<Long>> targetsByAction(Long state, ConnectionBuildStats stats) {
            Map<String, List<Long>> targetsByAction = cache.get(state);
            if (targetsByAction == null) {
                targetsByAction = new LinkedHashMap<String, List<Long>>();
                for (Pair<String, Long> transition : env.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                    List<Long> targets = targetsByAction.get(transition.getFirst());
                    if (targets == null) {
                        targets = new ArrayList<Long>();
                        targetsByAction.put(transition.getFirst(), targets);
                    }
                    targets.add(transition.getSecond());
                }
                cache.put(state, targetsByAction);
                stats.transitionIndexStateBuilds++;
            }
            return targetsByAction;
        }
    }

    private static final class ValuationSignature {
        private final BitSet bits;

        private ValuationSignature(BitSet bits) {
            this.bits = (BitSet) bits.clone();
        }

        public boolean equals(Object other) {
            if (!(other instanceof ValuationSignature)) {
                return false;
            }
            ValuationSignature that = (ValuationSignature) other;
            return bits.equals(that.bits);
        }

        public int hashCode() {
            return bits.hashCode();
        }
    }

    private static final class ConnectionBuildStats {
        private final String mode;
        private long totalNanos;
        private long setupNanos;
        private long traversalNanos;
        private long oldStates;
        private long mappingStates;
        private long comparisonFluents;
        private long visitedPairs;
        private long enqueuedPairs;
        private long duplicatePairs;
        private long synchronizedSuccessorPairs;
        private long visitedOldStates;
        private long visitedMappingStates;
        private long visitedEligibleMappingStates;
        private long eligibleMappingStatesTotal;
        private long eligibleMappingStatesCountTimeMillis;
        private long eligibilityChecks;
        private long valuationChecks;
        private long eligibilityComputations;
        private long signatureBuilds;
        private long transitionIndexStateBuilds;
        private long connectionCount;
        private long oldStatesWithoutTargets;
        private long indexedActionEntriesScanned;
        private long indexedActionMatches;
        private long legacyTransitionComparisons;
        private long legacyOldTransitionsScanned;
        private long legacyMappingTransitionsScanned;

        private ConnectionBuildStats(String mode) {
            this.mode = mode;
        }
    }

    private static final class ConnectionPlan {
        private final List<Connection> connections;
        private final Set<Long> oldStatesWithoutTargets;

        private ConnectionPlan(List<Connection> connections, Set<Long> oldStatesWithoutTargets) {
            this.connections = connections;
            this.oldStatesWithoutTargets = oldStatesWithoutTargets;
        }
    }
}
