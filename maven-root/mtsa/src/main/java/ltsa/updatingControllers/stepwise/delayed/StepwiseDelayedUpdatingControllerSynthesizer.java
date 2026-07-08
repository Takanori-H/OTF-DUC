package ltsa.updatingControllers.stepwise.delayed;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
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
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final String SCOPE_REQUIREMENT_SECTION = "Stepwise Delayed DUC scope別要求数";
    private static final String SCOPE_STATE_SPACE_SECTION = "Stepwise Delayed DUC scope別状態空間";
    private static final String CROSS_SCHEDULING_SECTION =
            "Stepwise Delayed DUC cross scheduling";
    private static final String CROSS_SCHEDULING_DETAIL_SECTION =
            "Stepwise Delayed DUC cross scheduling detail";
    private static final String DIRECT_PRUNING_REDUCTION_SECTION =
            "Stepwise Delayed DUC direct pruning 削減率";
    private static final String HOT_SWAP_IN_CONNECTION_SECTION =
            "Stepwise Delayed DUC hotSwapIn connection";
    private static final boolean COST_GUIDED_STAGED_CROSS_SCHEDULING =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.costGuidedCrossScheduling",
                    "true"));
    private static final boolean INDEXED_HOT_SWAP_IN_CONNECTION =
            Boolean.parseBoolean(System.getProperty(
                    "stepwise.delayed.indexedHotSwapInConnection",
                    "false"));

    public static void generateController(UpdatingControllerCompositeState uccs, LTSOutput output) {
        List<StepwiseStage> stages = uccs.getStepwiseStages();
        if (stages == null || stages.isEmpty()) {
            Diagnostics.fatal("stepwise_delayed requires at least one stage.");
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

        StepwiseGoalClassifier classifier = new StepwiseGoalClassifier(stages, output);
        StepwiseGoalClassifier.Classification classification =
                classifier.classify(oldGoal, newGoal, uccs.getStepwiseTransitionGoals());
        List<StepwiseClassifiedGoal> crossGoals = classification.getCrossGoals();
        long crossComponentBuildStart = System.currentTimeMillis();
        List<CrossComponent> crossComponents = buildCrossComponents(crossGoals);
        sortCrossComponentsByStageSet(crossComponents);
        long crossComponentBuildTime = System.currentTimeMillis() - crossComponentBuildStart;
        UpdatingControllerEvaluationRecorder.recordTime(
                "Stepwise Delayed DUC 分類統計",
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
            Set<String> realActions = new HashSet<String>(mapping.getActions());
            globalActions.addAll(mapping.getActions());
            bases.add(new StageBase(stage, mapping, metadata, realActions));
        }
        globalActions.remove(UpdateConstants.BEGIN_UPDATE);

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
                "Stepwise Delayed DUC 分類統計",
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

                addPassiveSelfLoopsForMissingActions(base.mapping, globalActions);
                Set<Fluent> trackedFluents =
                        StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                                localGoals,
                                globalActions);
                addPhaseComparisonFluents(trackedFluents);
                DelayedEnv meta = buildFluentProduct(
                        base.mapping,
                        base.stageMetadata,
                        Collections.<Long, Long>emptyMap(),
                        trackedFluents);
                Set<Integer> localScope = singletonStageScope(stageIndex);
                StateSpaceStats localMetaStats = outputStateSpace(output, "  mapping meta", meta.env);
                recordScopedStateSpace("local metaEnv", localScope, localMetaStats);

                DelayedEnv safety = pruneSafety(meta, localGoals, "local stage " + stage.getDisplayIndex(), output);
                if (uccs.isSafetyBackwardPruning()) {
                    safety = applySafetyBackwardPruning(
                            safety,
                            uccs.getUpdateGRGoal().getControllableActions(),
                            "local stage " + stage.getDisplayIndex(),
                            output);
                }
                StateSpaceStats localSafetyStats = outputStateSpace(output, "  mapping safety", safety.env);
                recordScopedStateSpace("local safetyEnv", localScope, localSafetyStats);
                localSafetyEnvironments.add(new DelayedEnv(
                        safety.env,
                        safety.trackedFluents,
                        safety.valuation,
                        safety.stageMetadata,
                        safety.oldControllerOrigin,
                        base.realActions));
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
                                uccs.isSafetyBackwardPruning(),
                                uccs.getUpdateGRGoal().getControllableActions(),
                                totalSchedulingStats,
                                output);
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
            mappingProduct = composeProduct(finalProductInputs, "STEPWISE_DELAYED_MAPPING_PRODUCT", output);
        }
        outputStateSpace(output, "[Stepwise Delayed DUCS] Product before delayed connection", mappingProduct.env);

        Set<Fluent> allTrackedFluents = new LinkedHashSet<Fluent>(mappingProduct.trackedFluents);
        addPhaseComparisonFluents(allTrackedFluents);
        DelayedEnv oldMeta = buildOldControllerMeta(oldController, allTrackedFluents);
        outputStateSpace(output, "[Stepwise Delayed DUCS] OldCon fluent meta", oldMeta.env);

        ConnectionPlan connectionPlan = buildConnections(oldMeta, mappingProduct, allTrackedFluents, output);
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

        MTS<Long, String> relabeledOld = relabelOldControllableActions(
                oldMeta.env,
                uccs.getUpdateGRGoal().getControllableActions());
        MTS<Long, String> connected = connectOldAndMapping(relabeledOld, mappingProduct.env, connectionPlan.connections);
        outputStateSpace(output, "[Stepwise Delayed DUCS] After delayed hotSwapIn connection", connected);

        MTS<Long, String> safetyEnv = UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(connected);
        StateSpaceStats finalSafetyStats =
                outputStateSpace(output, "[Stepwise Delayed DUCS] After global DontDoTwice", safetyEnv);
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
        }
        UpdatingControllerEvaluationRecorder.recordFinalGrInputStateSpace(
                "stepwise_delayed",
                "Stepwise Delayed DUC",
                finalSafetyStats.states,
                finalSafetyStats.transitions,
                finalSafetySourceStage);

        uccs.setUpdateEnvironment(safetyEnv);
        CompactState compactSafetyEnv = MTSToAutomataConverter.getInstance()
                .convert(safetyEnv, "stepwise_delayed_E_u||G(safety)", false, true);
        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(compactSafetyEnv);
        uccs.setMachines(machines);

        output.outln("");
        output.outln("[Stepwise Delayed DUCS] GR(1)");
        long synthesizeGRStart = System.currentTimeMillis();
        DUCHeartbeat.beginPhase("STEPWISE_DELAYED_GR1_SYNTHESIS");
        DUCHeartbeat.setCounter("safetyStates", safetyEnv.getStates().size());
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "Stepwise Delayed DUC",
                "safetyEnv を GR1 で解く時間");
        UpdatingControllerGRSynthesizer.synthesizeStepwiseDelayedGR(compactSafetyEnv, uccs, safetyEnv, output);
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
                    + " transitions: " + uccs.getComposition().ntransitions());
        }
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

        String section = "Stepwise Delayed DUC 分類統計";
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
        recordScopeRequirementCounts(output, stageCount, classification, crossGoals);
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
                    + " transition=" + count.transitionGoals);
            UpdatingControllerEvaluationRecorder.recordCount(
                    SCOPE_REQUIREMENT_SECTION,
                    prefix + "goal 数",
                    count.totalGoals(),
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    SCOPE_REQUIREMENT_SECTION,
                    prefix + "old safety goal 数",
                    count.oldSafetyGoals,
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    SCOPE_REQUIREMENT_SECTION,
                    prefix + "new safety goal 数",
                    count.newSafetyGoals,
                    "goals");
            UpdatingControllerEvaluationRecorder.recordCount(
                    SCOPE_REQUIREMENT_SECTION,
                    prefix + "transition goal 数",
                    count.transitionGoals,
                    "goals");
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
            DelayedEnv phaseMeta = buildFluentProduct(
                    base.mapping,
                    base.stageMetadata,
                    Collections.<Long, Long>emptyMap(),
                    phaseFluents);
            DelayedEnv current = new DelayedEnv(
                    phaseMeta.env,
                    phaseMeta.trackedFluents,
                    phaseMeta.valuation,
                    phaseMeta.stageMetadata,
                    phaseMeta.oldControllerOrigin,
                    base.realActions);
            Set<Integer> localScope = singletonStageScope(stageIndex);
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
        return composeProduct(finalProductInputs, "STEPWISE_DELAYED_INCREMENTAL_MAPPING_PRODUCT", output);
    }

    private static DelayedEnv buildIncrementalCrossComponentEnvironment(
            CrossComponent component,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
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
                current = composeProduct(
                        productInputs,
                        "STEPWISE_DELAYED_INCREMENTAL_CROSS_COMPONENT_" + component.getId()
                                + "_ADD_" + displayStageScope(addedStages),
                        output);
                currentScope.addAll(addedStages);
            }

            if (!currentScope.containsAll(goalScope)) {
                Diagnostics.fatal("stepwise_delayed incremental cross goal scope is not covered: "
                        + goal.getName() + " scope=" + displayStageScope(goalScope)
                        + " productScope=" + displayStageScope(currentScope) + ".");
            }
            addPassiveSelfLoopsForMissingActions(current.env, outOfScopeActionsForScope(currentScope, bases));
            current = new DelayedEnv(
                    current.env,
                    current.trackedFluents,
                    current.valuation,
                    current.stageMetadata,
                    current.oldControllerOrigin,
                    realActionsForScope(currentScope, bases));
            current = pruneIncrementalGoal(
                    current,
                    goal,
                    "cross",
                    goalScope,
                    currentScope,
                    addedStages,
                    cleanup,
                    safetyBackwardPruning,
                    controllableActions,
                    counter,
                    output);
        }

        if (current == null) {
            Diagnostics.fatal("stepwise_delayed incremental cross component has no goals.");
        }
        addPassiveSelfLoopsForMissingActions(current.env, outOfScopeActionsForScope(component.getStageScope(), bases));
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
                realActionsForScope(component.getStageScope(), bases));
    }

    private static DelayedEnv buildStagedCrossComponentEnvironment(
            CrossComponent component,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            CrossSchedulingStats totalSchedulingStats,
            LTSOutput output) {
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

            List<StepwiseClassifiedGoal> batch = goalsCoveredByScope(remaining, merged.stageScope);
            if (batch.isEmpty()) {
                Diagnostics.fatal("stepwise_delayed staged cross goal scope is not covered: "
                        + seed.getName() + " scope=" + displayStageScope(seedScope)
                        + " productScope=" + displayStageScope(merged.stageScope) + ".");
            }
            remaining.removeAll(batch);

            addPassiveSelfLoopsForMissingActions(merged.env.env, outOfScopeActionsForScope(merged.stageScope, bases));
            DelayedEnv scopedEnv = new DelayedEnv(
                    merged.env.env,
                    merged.env.trackedFluents,
                    merged.env.valuation,
                    merged.env.stageMetadata,
                    merged.env.oldControllerOrigin,
                    realActionsForScope(merged.stageScope, bases));
            DelayedEnv pruned = pruneStagedCrossGoals(
                    scopedEnv,
                    batch,
                    component.getId(),
                    step,
                    merged.stageScope,
                    merged.addedStageScope,
                    safetyBackwardPruning,
                    controllableActions,
                    output);
            fragments.removeAll(selected);
            fragments.add(new ScopedDelayedEnv(merged.stageScope, pruned, merged.stageScope));
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
        addPassiveSelfLoopsForMissingActions(finalFragment.env.env, outOfScopeActionsForScope(component.getStageScope(), bases));
        StateSpaceStats finalComponentSafetyStats =
                outputStateSpace(output, "  staged cross component safety", finalFragment.env.env);
        recordScopedStateSpace(
                "cross component " + component.getId() + " final safetyEnv",
                component.getStageScope(),
                finalComponentSafetyStats);
        return new DelayedEnv(
                finalFragment.env.env,
                finalFragment.env.trackedFluents,
                finalFragment.env.valuation,
                finalFragment.env.stageMetadata,
                finalFragment.env.oldControllerOrigin,
                realActionsForScope(component.getStageScope(), bases));
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
            return new ScopedDelayedEnv(fragment.stageScope, fragment.env, fragment.stageScope);
        }

        Set<Integer> mergedScope = new java.util.TreeSet<Integer>();
        List<DelayedEnv> inputs = new ArrayList<DelayedEnv>();
        for (ScopedDelayedEnv fragment : fragments) {
            mergedScope.addAll(fragment.stageScope);
            inputs.add(fragment.env);
        }
        DelayedEnv merged = composeProduct(
                inputs,
                "STEPWISE_DELAYED_STAGED_CROSS_COMPONENT_" + componentId
                        + "_STEP_" + step + "_MERGE_" + displayStageScope(mergedScope),
                output);
        return new ScopedDelayedEnv(mergedScope, merged, mergedScope);
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
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / scheduler mode",
                COST_GUIDED_STAGED_CROSS_SCHEDULING ? "cost-guided" : "fixed-order",
                "text");
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal",
                schedule.goal.getName(),
                "goal");
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal kind",
                schedule.goal.getKind().toString(),
                "kind");
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected goal scope",
                displayStageScope(schedule.goal.getStageScope()),
                "scope");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / fixed order index",
                schedule.fixedOrderIndex,
                "index");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / candidate evaluations",
                schedule.candidateEvaluationCount,
                "candidates");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected fragment count",
                schedule.selectedFragments.size(),
                "fragments");
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / merged scope",
                displayStageScope(schedule.mergedScope),
                "scope");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / merged scope size",
                schedule.mergedScope.size(),
                "stages");
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected cost",
                schedule.cost.toString(),
                "state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / selected cost log10",
                log10(schedule.cost),
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_DETAIL_SECTION,
                label + " / batch goal count",
                schedule.batchGoalCount,
                "goals");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                CROSS_SCHEDULING_DETAIL_SECTION,
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
                CROSS_SCHEDULING_SECTION,
                label + " / scheduler mode",
                COST_GUIDED_STAGED_CROSS_SCHEDULING ? "cost-guided" : "fixed-order",
                "text");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / component count",
                stats.componentCount,
                "components");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / scheduling step count",
                stats.stepCount,
                "steps");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / candidate evaluations total",
                stats.candidateEvaluations,
                "candidates");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / non-first selections",
                stats.nonFirstSelections,
                "selections");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / non-first selection rate",
                stats.stepCount == 0 ? 0.0 : ((double) stats.nonFirstSelections) / stats.stepCount,
                "ratio");
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                CROSS_SCHEDULING_SECTION,
                label + " / scheduler selection time total",
                stats.selectionNanos);
        UpdatingControllerEvaluationRecorder.recordAverageNanoTime(
                CROSS_SCHEDULING_SECTION,
                label + " / scheduler selection time average",
                stats.selectionNanos,
                stats.stepCount);
        UpdatingControllerEvaluationRecorder.recordText(
                CROSS_SCHEDULING_SECTION,
                label + " / selected cost max",
                stats.maxCost.toString(),
                "state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / selected cost log10 max",
                stats.maxCostLog10,
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / selected cost log10 average",
                stats.stepCount == 0 ? 0.0 : stats.costLog10Total / stats.stepCount,
                "log10_state_product");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / batch goal count total",
                stats.batchGoalCountTotal,
                "goals");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / batch goal count max",
                stats.batchGoalCountMax,
                "goals");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / batch goal count average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.batchGoalCountTotal) / stats.stepCount,
                "goals/step");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / merged scope size max",
                stats.mergedScopeSizeMax,
                "stages");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / merged scope size average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.mergedScopeSizeTotal) / stats.stepCount,
                "stages/step");
        UpdatingControllerEvaluationRecorder.recordCount(
                CROSS_SCHEDULING_SECTION,
                label + " / selected fragment count max",
                stats.selectedFragmentCountMax,
                "fragments");
        UpdatingControllerEvaluationRecorder.recordDouble(
                CROSS_SCHEDULING_SECTION,
                label + " / selected fragment count average",
                stats.stepCount == 0 ? 0.0 : ((double) stats.selectedFragmentCountTotal) / stats.stepCount,
                "fragments/step");
        stats.metricRecordingCountTimeMillis += System.currentTimeMillis() - recordStart;
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                CROSS_SCHEDULING_SECTION,
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
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            LTSOutput output) {
        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>(current.trackedFluents);
        trackedFluents.addAll(StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                goals,
                current.env.getActions()));
        addPhaseComparisonFluents(trackedFluents);

        DelayedEnv metaRaw = buildFluentProduct(
                current.env,
                current.stageMetadata,
                current.oldControllerOrigin,
                trackedFluents);
        DelayedEnv meta = new DelayedEnv(
                metaRaw.env,
                metaRaw.trackedFluents,
                metaRaw.valuation,
                metaRaw.stageMetadata,
                metaRaw.oldControllerOrigin,
                current.realActions);

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

        DelayedEnv pruned = pruneSafety(meta, goals, scope, output);
        if (safetyBackwardPruning) {
            pruned = applySafetyBackwardPruning(
                    pruned,
                    controllableActions,
                    scope,
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
        return composeProduct(inputs, productName, output);
    }

    private static DelayedEnv pruneIncrementalGoal(
            DelayedEnv current,
            StepwiseClassifiedGoal goal,
            String localOrCross,
            Set<Integer> goalScope,
            Set<Integer> productScope,
            Set<Integer> addedStages,
            boolean cleanup,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            IncrementalPruningCounter counter,
            LTSOutput output) {
        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>(current.trackedFluents);
        trackedFluents.addAll(StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                Collections.singletonList(goal),
                current.env.getActions()));
        addPhaseComparisonFluents(trackedFluents);

        DelayedEnv metaRaw = buildFluentProduct(
                current.env,
                current.stageMetadata,
                current.oldControllerOrigin,
                trackedFluents);
        DelayedEnv meta = new DelayedEnv(
                metaRaw.env,
                metaRaw.trackedFluents,
                metaRaw.valuation,
                metaRaw.stageMetadata,
                metaRaw.oldControllerOrigin,
                current.realActions);

        int step = counter.next();
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] incremental step " + step);
        output.outln("  local/cross: " + localOrCross);
        output.outln("  scope: " + displayStageScope(goalScope));
        output.outln("  product scope: " + displayStageScope(productScope));
        output.outln("  requirement: " + goal.getKind() + " " + goal.getName());
        output.outln("  added stages: " + displayStageScope(addedStages));
        String stepLabel = "incremental " + localOrCross + " step " + step
                + " " + goal.getKind() + " " + goal.getName();
        StateSpaceStats metaStats = outputStateSpace(output, "  before pruning", meta.env);
        recordScopedStateSpace(stepLabel + " metaEnv", productScope, metaStats);

        String pruningScope = "incremental " + localOrCross + " " + goal.getKind() + " " + goal.getName();
        DelayedEnv pruned = pruneSafety(meta, Collections.singletonList(goal), pruningScope, null, false);
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

        DelayedEnv cleaned = cleanupReachable(pruned);
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
        return rebuildMetadata(env, result.getEnvironment());
    }

    private static void failIfInitialLosing(SafetyBackwardPruner.Result result, String scope) {
        if (result.isInitialLosing()) {
            Diagnostics.fatal("stepwise_delayed safety backward pruning found initial state losing in "
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
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(result.getStates());
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        Map<Long, Long> oldOrigins = new HashMap<Long, Long>();
        for (Long state : result.getStates()) {
            for (Fluent fluent : source.valuation.getFluentsFromState(state)) {
                valuation.addHoldingFluent(state, fluent);
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
        return new DelayedEnv(result, source.trackedFluents, valuation, metadata, oldOrigins, source.realActions);
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

    private static DelayedEnv buildFluentProduct(
            MTS<Long, String> base,
            Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
            Map<Long, Long> oldOrigins,
            Set<Fluent> fluents) {
        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        Map<ProductStateKey, Long> keyToState = new HashMap<ProductStateKey, Long>();
        Map<Long, Set<Fluent>> trueFluentsByState = new HashMap<Long, Set<Fluent>>();
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> resultMetadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        Map<Long, Long> resultOldOrigins = new HashMap<Long, Long>();
        Queue<ProductStateKey> pending = new LinkedList<ProductStateKey>();

        ProductStateKey initial = new ProductStateKey(base.getInitialState(), initialTrueFluents(fluents));
        keyToState.put(initial, 0L);
        trueFluentsByState.put(0L, initial.trueFluents);
        copyMetadata(initial.baseState, 0L, stageMetadata, oldOrigins, resultMetadata, resultOldOrigins);
        pending.add(initial);
        long nextStateId = 1L;

        while (!pending.isEmpty()) {
            ProductStateKey current = pending.remove();
            Long fromState = keyToState.get(current);
            result.addState(fromState);
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
                    pending.add(targetKey);
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
        return new DelayedEnv(result, fluents, valuation, resultMetadata, resultOldOrigins,
                new HashSet<String>(base.getActions()));
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
        if (violating.contains(meta.env.getInitialState())) {
            Diagnostics.fatal("stepwise_delayed safety pruning found initial state violating in "
                    + scope + ".");
        }

        MTS<Long, String> result = new MTSImpl<Long, String>(meta.env.getInitialState());
        for (Long state : meta.env.getStates()) {
            result.addState(state);
            if (!violating.contains(state)) {
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
                    DIRECT_PRUNING_REDUCTION_SECTION,
                    safeScope + " / before -> after direct pruning",
                    beforeStates,
                    beforeTransitions,
                    afterStates,
                    afterTransitions);
            UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                    DIRECT_PRUNING_REDUCTION_SECTION,
                    safeScope + " / direct pruning reduction CountTime",
                    reductionCountTime,
                    "direct safety pruning の削減率を記録するために before/after の遷移数を数える評価用 CountTime。");
        }

        DelayedEnv pruned = rebuildMetadata(meta, result);
        if (output != null) {
            if (afterTransitions < 0) {
                afterTransitions = countTransitions(result);
            }
            output.outln("  safety pruning kept states: " + result.getStates().size()
                    + " transitions: " + afterTransitions);
        }
        return pruned;
    }

    private static DelayedEnv buildCrossComponentEnvironment(
            CrossComponent component,
            List<DelayedEnv> localSafetyEnvironments,
            List<StageBase> bases,
            List<StepwiseStage> stages,
            boolean safetyBackwardPruning,
            Set<String> controllableActions,
            LTSOutput output) {
        output.outln("");
        output.outln("[Stepwise Delayed DUCS] Cross component " + component.getId());
        output.outln("  stages: " + displayStageScope(component.getStageScope()));
        outputGoalNames(output, "goals", component.getGoals());

        List<DelayedEnv> componentInputs = new ArrayList<DelayedEnv>();
        for (Integer stageIndex : component.getStageScope()) {
            DelayedEnv safetyEnv = localSafetyEnvironments.get(stageIndex);
            outputStateSpace(output, "  input mapping safety stage "
                    + stages.get(stageIndex).getDisplayIndex(), safetyEnv.env);
            componentInputs.add(safetyEnv);
        }

        DelayedEnv componentProduct = composeProduct(
                componentInputs,
                "STEPWISE_DELAYED_CROSS_COMPONENT_" + component.getId() + "_PRODUCT",
                output);
        outputStateSpace(output, "  component product", componentProduct.env);

        Set<String> outOfScopeActions = outOfScopeActionsForScope(component.getStageScope(), bases);
        addPassiveSelfLoopsForMissingActions(componentProduct.env, outOfScopeActions);
        outputStateSpace(output, "  component product with out-of-scope passive actions", componentProduct.env);
        Set<String> componentRealActions = realActionsForScope(component.getStageScope(), bases);
        output.outln("  realActions count: " + componentRealActions.size());
        output.outln("  outOfScope passive action count: " + outOfScopeActions.size());
        output.outln("  action alphabet count: " + componentProduct.env.getActions().size());

        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>(componentProduct.trackedFluents);
        trackedFluents.addAll(StepwiseUpdatingControllerSafetySynthesizer.collectFluentsForEnvironment(
                component.getGoals(),
                componentProduct.env.getActions()));
        addPhaseComparisonFluents(trackedFluents);

        DelayedEnv crossMeta = buildFluentProduct(
                componentProduct.env,
                componentProduct.stageMetadata,
                Collections.<Long, Long>emptyMap(),
                trackedFluents);
        outputStateSpace(output, "  cross meta", crossMeta.env);

        DelayedEnv crossSafety = pruneSafety(
                crossMeta,
                component.getGoals(),
                "cross component " + component.getId(),
                output);
        if (safetyBackwardPruning) {
            crossSafety = applySafetyBackwardPruning(
                    crossSafety,
                    controllableActions,
                    "cross component " + component.getId(),
                    output);
        }
        outputStateSpace(output, "  after cross safety pruning", crossSafety.env);
        return new DelayedEnv(
                crossSafety.env,
                crossSafety.trackedFluents,
                crossSafety.valuation,
                crossSafety.stageMetadata,
                crossSafety.oldControllerOrigin,
                componentRealActions);
    }

    private static DelayedEnv composeProduct(List<DelayedEnv> inputs, String productName, LTSOutput output) {
        if (inputs.size() == 1) {
            return inputs.get(0);
        }

        output.outln("Owner-aware delayed product: " + productName);
        MTS<Long, String> product = new MTSImpl<Long, String>(0L);
        Map<List<Long>, Long> tupleToState = new HashMap<List<Long>, Long>();
        Map<Long, List<Long>> stateToTuple = new HashMap<Long, List<Long>>();
        Queue<List<Long>> pending = new LinkedList<List<Long>>();

        List<Long> initialTuple = new ArrayList<Long>();
        for (DelayedEnv input : inputs) {
            initialTuple.add(input.env.getInitialState());
        }
        tupleToState.put(initialTuple, 0L);
        stateToTuple.put(0L, initialTuple);
        pending.add(initialTuple);

        long nextStateId = 1L;
        while (!pending.isEmpty()) {
            List<Long> tuple = pending.remove();
            Long fromState = tupleToState.get(tuple);
            product.addState(fromState);

            Set<String> enabledActions = enabledActions(inputs, tuple);
            for (String action : enabledActions) {
                if (!hasRealOwnerEnabled(action, tuple, inputs)) {
                    continue;
                }
                List<List<Long>> targetChoices = targetChoices(action, tuple, inputs);
                if (targetChoices == null) {
                    continue;
                }
                for (List<Long> targetTuple : cartesianProduct(targetChoices)) {
                    Long targetState = tupleToState.get(targetTuple);
                    if (targetState == null) {
                        targetState = nextStateId++;
                        tupleToState.put(targetTuple, targetState);
                        stateToTuple.put(targetState, targetTuple);
                        product.addState(targetState);
                        pending.add(targetTuple);
                    }
                    product.addAction(action);
                    product.addRequired(fromState, action, targetState);
                }
            }
        }

        Set<Fluent> trackedFluents = new LinkedHashSet<Fluent>();
        Set<String> realActions = new HashSet<String>();
        for (DelayedEnv input : inputs) {
            trackedFluents.addAll(input.trackedFluents);
            realActions.addAll(input.realActions);
        }
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(product.getStates());
        Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> metadata =
                new HashMap<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>>();
        for (Map.Entry<Long, List<Long>> entry : stateToTuple.entrySet()) {
            Long productState = entry.getKey();
            List<Long> tuple = entry.getValue();
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
        return new DelayedEnv(product, trackedFluents, valuation, metadata,
                Collections.<Long, Long>emptyMap(), realActions);
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

        UpdatingControllerEvaluationRecorder.recordNanoTime(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "total time",
                stats.totalNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "setup time",
                stats.setupNanos);
        UpdatingControllerEvaluationRecorder.recordNanoTime(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "traversal time",
                stats.traversalNanos);
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "mode indexed",
                "indexed".equals(stats.mode) ? 1 : 0,
                "bool");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "old states",
                stats.oldStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "mapping states",
                stats.mappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "comparison fluents",
                stats.comparisonFluents,
                "fluents");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "visited synchronized pairs",
                stats.visitedPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "enqueued synchronized pairs",
                stats.enqueuedPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "duplicate skipped pairs",
                stats.duplicatePairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "synchronized successor pairs",
                stats.synchronizedSuccessorPairs,
                "pairs");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "visited unique old states",
                stats.visitedOldStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "visited unique mapping states",
                stats.visitedMappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "visited eligible mapping states",
                stats.visitedEligibleMappingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "eligible mapping states total",
                stats.eligibleMappingStatesTotal,
                "states");
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "eligible mapping states total CountTime",
                stats.eligibleMappingStatesCountTimeMillis,
                "hotSwapIn connection の診断用に mapping product 全状態を走査し、OLD_SIDE かつ phase-initial な state 数を数える評価用 CountTime。");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "eligibility checks",
                stats.eligibilityChecks,
                "checks");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "valuation checks",
                stats.valuationChecks,
                "checks");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "eligibility computations",
                stats.eligibilityComputations,
                "computations");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "signature builds",
                stats.signatureBuilds,
                "signatures");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "transition index state builds",
                stats.transitionIndexStateBuilds,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "connections",
                stats.connectionCount,
                "transitions");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "old states without targets",
                stats.oldStatesWithoutTargets,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "indexed action entries scanned",
                stats.indexedActionEntriesScanned,
                "entries");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "action matches",
                stats.indexedActionMatches,
                "matches");
        UpdatingControllerEvaluationRecorder.recordCount(
                HOT_SWAP_IN_CONNECTION_SECTION,
                "legacy transition comparisons",
                stats.legacyTransitionComparisons,
                "comparisons");
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

    private static MTS<Long, String> relabelOldControllableActions(
            MTS<Long, String> oldEnv,
            Set<String> controllableActions) {
        MTS<Long, String> result = new MTSImpl<Long, String>(oldEnv.getInitialState());
        for (Long state : oldEnv.getStates()) {
            result.addState(state);
        }
        for (Long state : oldEnv.getStates()) {
            for (Pair<String, Long> transition : oldEnv.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                String action = transition.getFirst();
                if (controllableActions.contains(action) && UpdatingControllersUtils.isNotUpdateAction(action)) {
                    action = action + UpdateConstants.OLD_LABEL;
                }
                result.addAction(action);
                result.addState(transition.getSecond());
                result.addRequired(state, action, transition.getSecond());
            }
        }
        return result;
    }

    private static MTS<Long, String> connectOldAndMapping(
            MTS<Long, String> oldEnv,
            MTS<Long, String> mappingEnv,
            List<Connection> connections) {
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
                result.addAction(transition.getFirst());
                result.addRequired(state, transition.getFirst(), transition.getSecond());
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
        Set<String> localActions = new HashSet<String>(environment.getActions());
        for (String action : globalActions) {
            if (UpdateConstants.BEGIN_UPDATE.equals(action)) {
                continue;
            }
            if (!localActions.contains(action)) {
                environment.addAction(action);
                for (Long state : environment.getStates()) {
                    environment.addRequired(state, action, state);
                }
            }
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

    private static Set<String> enabledActions(List<DelayedEnv> inputs, List<Long> tuple) {
        Set<String> actions = new HashSet<String>();
        for (int i = 0; i < inputs.size(); i++) {
            for (Pair<String, Long> transition :
                    inputs.get(i).env.getTransitions(tuple.get(i), MTS.TransitionType.REQUIRED)) {
                actions.add(transition.getFirst());
            }
        }
        return actions;
    }

    private static boolean hasRealOwnerEnabled(String action, List<Long> tuple, List<DelayedEnv> inputs) {
        if (isUpdateAction(action)) {
            for (int i = 0; i < inputs.size(); i++) {
                if (enabledTargets(inputs.get(i).env, tuple.get(i), action).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        boolean hasOwner = false;
        String baseAction = normalizeOldAction(action);
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).realActions.contains(baseAction)) {
                hasOwner = true;
                if (enabledTargets(inputs.get(i).env, tuple.get(i), action).isEmpty()) {
                    return false;
                }
            }
        }
        return hasOwner;
    }

    private static List<List<Long>> targetChoices(String action, List<Long> tuple, List<DelayedEnv> inputs) {
        List<List<Long>> choices = new ArrayList<List<Long>>();
        String baseAction = normalizeOldAction(action);
        for (int i = 0; i < inputs.size(); i++) {
            List<Long> targets = enabledTargets(inputs.get(i).env, tuple.get(i), action);
            boolean owner = isUpdateAction(action) || inputs.get(i).realActions.contains(baseAction);
            if (targets.isEmpty()) {
                if (owner || inputs.get(i).env.getActions().contains(action)) {
                    return null;
                }
                choices.add(Collections.singletonList(tuple.get(i)));
            } else {
                choices.add(targets);
            }
        }
        return choices;
    }

    private static List<Long> enabledTargets(MTS<Long, String> environment, Long state, String action) {
        List<Long> targets = new ArrayList<Long>();
        for (Pair<String, Long> transition : environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst()) && !targets.contains(transition.getSecond())) {
                targets.add(transition.getSecond());
            }
        }
        return targets;
    }

    private static List<List<Long>> cartesianProduct(List<List<Long>> choices) {
        List<List<Long>> result = new ArrayList<List<Long>>();
        buildCartesianProduct(choices, 0, new ArrayList<Long>(), result);
        return result;
    }

    private static void buildCartesianProduct(
            List<List<Long>> choices,
            int index,
            List<Long> current,
            List<List<Long>> result) {
        if (index == choices.size()) {
            result.add(new ArrayList<Long>(current));
            return;
        }
        for (Long target : choices.get(index)) {
            current.add(target);
            buildCartesianProduct(choices, index + 1, current, result);
            current.remove(current.size() - 1);
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
        int transitions = countTransitions(mts);
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
        String scopedLabel = "scope " + displayStageScope(scope) + " " + label;
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / States",
                stats.states,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / Transitions",
                stats.transitions,
                "transitions");
        UpdatingControllerEvaluationRecorder.recordCount(
                SCOPE_STATE_SPACE_SECTION,
                scopedLabel + " / CountTime",
                stats.countTime,
                "ms");
    }

    private static int countTransitions(MTS<Long, String> mts) {
        int count = 0;
        for (Long state : mts.getStates()) {
            count += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
            count += mts.getTransitions(state, MTS.TransitionType.MAYBE).size();
        }
        return count;
    }

    private static final class StateSpaceStats {
        private final int states;
        private final int transitions;
        private final long countTime;

        private StateSpaceStats(int states, int transitions, long countTime) {
            this.states = states;
            this.transitions = transitions;
            this.countTime = countTime;
        }
    }

    private static final class ScopeRequirementCounts {
        private final Set<Integer> scope;
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
        }

        private int totalGoals() {
            return oldSafetyGoals + newSafetyGoals + transitionGoals;
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
        private final Set<Integer> addedStageScope;

        private ScopedDelayedEnv(Set<Integer> stageScope, DelayedEnv env, Set<Integer> addedStageScope) {
            this.stageScope = new java.util.TreeSet<Integer>(stageScope);
            this.env = env;
            this.addedStageScope = new java.util.TreeSet<Integer>(addedStageScope);
        }
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

    private static final class DelayedEnv {
        private final MTS<Long, String> env;
        private final Set<Fluent> trackedFluents;
        private final FluentStateValuation<Long> valuation;
        private final Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata;
        private final Map<Long, Long> oldControllerOrigin;
        private final Set<String> realActions;

        private DelayedEnv(
                MTS<Long, String> env,
                Set<Fluent> trackedFluents,
                FluentStateValuation<Long> valuation,
                Map<Long, Map<Integer, MappingEnvironmentGenerator.MappingStateMetadata>> stageMetadata,
                Map<Long, Long> oldControllerOrigin,
                Set<String> realActions) {
            this.env = env;
            this.trackedFluents = new LinkedHashSet<Fluent>(trackedFluents);
            this.valuation = valuation;
            this.stageMetadata = stageMetadata;
            this.oldControllerOrigin = oldControllerOrigin;
            this.realActions = new HashSet<String>(realActions);
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

    private static final class Connection {
        private final Long oldState;
        private final Long mappingState;

        private Connection(Long oldState, Long mappingState) {
            this.oldState = oldState;
            this.mappingState = mappingState;
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
