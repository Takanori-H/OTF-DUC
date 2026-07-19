package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class SafetyBackwardPruner {

    private SafetyBackwardPruner() {
    }

    public static Result prune(
            MTS<Long, String> environment,
            Set<String> controllableActions,
            String scope,
            LTSOutput output) {
        long pruneStart = System.currentTimeMillis();
        long beforeCountStart = System.currentTimeMillis();
        long beforeStates = environment.getStates().size();
        long beforeTransitions = countTransitions(environment);
        long beforeCountTime = System.currentTimeMillis() - beforeCountStart;

        Map<Long, Map<String, Set<Long>>> transitionsByAction = groupTransitions(environment);
        Set<Long> losingStates = new LinkedHashSet<Long>();
        for (Long state : environment.getStates()) {
            Map<String, Set<Long>> actionTargets = transitionsByAction.get(state);
            if (actionTargets == null || actionTargets.isEmpty()) {
                losingStates.add(state);
            }
        }
        int deadEndSeeds = losingStates.size();

        long backwardStart = System.currentTimeMillis();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long state : environment.getStates()) {
                if (losingStates.contains(state)) {
                    continue;
                }
                if (isLosingByPredecessorRule(
                        transitionsByAction.get(state),
                        controllableActions,
                        losingStates)) {
                    losingStates.add(state);
                    changed = true;
                }
            }
        }
        long backwardTime = System.currentTimeMillis() - backwardStart;

        long losingActionGroupCountStart = System.currentTimeMillis();
        long losingActionGroups = countLosingControllableActionGroups(
                environment,
                transitionsByAction,
                controllableActions,
                losingStates);
        long losingActionGroupCountTime = System.currentTimeMillis() - losingActionGroupCountStart;
        boolean initialLosing = losingStates.contains(environment.getInitialState());
        MTS<Long, String> pruned = buildPrunedEnvironment(
                environment,
                transitionsByAction,
                controllableActions,
                losingStates,
                initialLosing);
        long afterBeforeCleanupCountStart = System.currentTimeMillis();
        long afterBeforeCleanupStates = pruned.getStates().size();
        long afterBeforeCleanupTransitions = countTransitions(pruned);
        long afterBeforeCleanupCountTime = System.currentTimeMillis() - afterBeforeCleanupCountStart;
        pruned.removeUnreachableStates();
        long afterCountStart = System.currentTimeMillis();
        long afterStates = pruned.getStates().size();
        long afterTransitions = countTransitions(pruned);
        long afterCountTime = System.currentTimeMillis() - afterCountStart;

        Result result = new Result(
                pruned,
                initialLosing,
                beforeStates,
                beforeTransitions,
                afterBeforeCleanupStates,
                afterBeforeCleanupTransitions,
                afterStates,
                afterTransitions,
                losingStates.size(),
                deadEndSeeds,
                losingActionGroups);
        long pruneTime = System.currentTimeMillis() - pruneStart;
        recordEvaluationMetrics(
                scope,
                result,
                beforeCountTime,
                afterBeforeCleanupCountTime,
                afterCountTime,
                backwardTime,
                losingActionGroupCountTime,
                pruneTime);
        log(output, scope, result);
        return result;
    }

    public static DeferredResult pruneDeferred(
            MTS<Long, String> environment,
            Set<Long> errorStates,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            Set<Integer> currentScope,
            String scope,
            LTSOutput output) {
        long start = System.currentTimeMillis();
        long beforeStates = environment.getStates().size();
        long beforeTransitions = countTransitions(environment);
        Map<Long, Map<String, Set<Long>>> transitionsByAction = groupTransitions(environment);

        // A partial fragment can have ordinary dead ends that disappear after composition.
        // Only states explicitly marked as Error are losing seeds at this point.
        Set<Long> losingStates = new LinkedHashSet<Long>();
        if (errorStates != null) {
            losingStates.addAll(errorStates);
            losingStates.retainAll(environment.getStates());
        }
        int explicitErrorSeeds = losingStates.size();

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long state : environment.getStates()) {
                if (losingStates.contains(state)) {
                    continue;
                }
                if (isLosingByCompleteUncontrollablePredecessor(
                        transitionsByAction.get(state),
                        controllableActions,
                        ownersByAction,
                        currentScope,
                        losingStates)) {
                    losingStates.add(state);
                    changed = true;
                }
            }
        }

        boolean initialLosing = losingStates.contains(environment.getInitialState());
        DeferredBuild build = buildDeferredPrunedEnvironment(
                environment,
                transitionsByAction,
                controllableActions,
                ownersByAction,
                currentScope,
                losingStates,
                errorStates,
                initialLosing);
        build.environment.removeUnreachableStates();
        build.errorStates.retainAll(build.environment.getStates());

        DeferredResult result = new DeferredResult(
                build.environment,
                build.errorStates,
                initialLosing,
                beforeStates,
                beforeTransitions,
                build.environment.getStates().size(),
                countTransitions(build.environment),
                losingStates.size(),
                explicitErrorSeeds,
                build.deferredActionGroups,
                build.removedControllableActionGroups,
                System.currentTimeMillis() - start);
        logDeferred(output, scope, currentScope, result);
        return result;
    }

    private static boolean isLosingByCompleteUncontrollablePredecessor(
            Map<String, Set<Long>> actionTargets,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            Set<Integer> currentScope,
            Set<Long> losingStates) {
        if (actionTargets == null || actionTargets.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Set<Long>> entry : actionTargets.entrySet()) {
            String action = entry.getKey();
            if (!controllableActions.contains(action)
                    && reachesLosing(entry.getValue(), losingStates)
                    && ownersComplete(action, ownersByAction, currentScope)) {
                return true;
            }
        }
        return false;
    }

    private static DeferredBuild buildDeferredPrunedEnvironment(
            MTS<Long, String> environment,
            Map<Long, Map<String, Set<Long>>> transitionsByAction,
            Set<String> controllableActions,
            Map<String, Set<Integer>> ownersByAction,
            Set<Integer> currentScope,
            Set<Long> losingStates,
            Set<Long> explicitErrorStates,
            boolean initialLosing) {
        MTS<Long, String> result = new MTSImpl<Long, String>(environment.getInitialState());
        result.addActions(environment.getActions());
        Set<Long> resultErrorStates = new LinkedHashSet<Long>();
        if (initialLosing) {
            return new DeferredBuild(result, resultErrorStates, 0L, 0L);
        }

        for (Long state : environment.getStates()) {
            if (!losingStates.contains(state)) {
                result.addState(state);
            }
        }

        Long representativeError = representativeError(explicitErrorStates, losingStates);
        long deferredActionGroups = 0L;
        long removedControllableActionGroups = 0L;
        for (Long state : environment.getStates()) {
            if (losingStates.contains(state)) {
                continue;
            }
            Map<String, Set<Long>> actionTargets = transitionsByAction.get(state);
            if (actionTargets == null) {
                continue;
            }
            for (Map.Entry<String, Set<Long>> entry : actionTargets.entrySet()) {
                String action = entry.getKey();
                Set<Long> targets = entry.getValue();
                boolean reachesLosing = reachesLosing(targets, losingStates);
                if (controllableActions.contains(action) && reachesLosing) {
                    removedControllableActionGroups++;
                    continue;
                }

                boolean deferred = reachesLosing
                        && !ownersComplete(action, ownersByAction, currentScope);
                for (Long target : targets) {
                    if (!losingStates.contains(target)) {
                        result.addRequired(state, action, target);
                    }
                }
                if (deferred && representativeError != null) {
                    // Keep the boundary observable so a later owner can disable or enable it.
                    result.addState(representativeError);
                    result.addRequired(state, action, representativeError);
                    resultErrorStates.add(representativeError);
                    deferredActionGroups++;
                }
            }
        }
        return new DeferredBuild(
                result,
                resultErrorStates,
                deferredActionGroups,
                removedControllableActionGroups);
    }

    private static Long representativeError(
            Set<Long> explicitErrorStates,
            Set<Long> losingStates) {
        if (explicitErrorStates != null) {
            for (Long state : explicitErrorStates) {
                if (losingStates.contains(state)) {
                    return state;
                }
            }
        }
        for (Long state : losingStates) {
            return state;
        }
        return null;
    }

    private static boolean ownersComplete(
            String action,
            Map<String, Set<Integer>> ownersByAction,
            Set<Integer> currentScope) {
        String normalizedAction = UpdatingControllersUtils.isOld(action)
                ? UpdatingControllersUtils.withoutOld(action)
                : action;
        Set<Integer> owners = ownersByAction == null ? null : ownersByAction.get(normalizedAction);
        return owners == null || owners.isEmpty()
                || (currentScope != null && currentScope.containsAll(owners));
    }

    private static void logDeferred(
            LTSOutput output,
            String scope,
            Set<Integer> currentScope,
            DeferredResult result) {
        if (output == null) {
            return;
        }
        output.outln("  deferred safety backward pruning scope: " + scope);
        output.outln("    component scope: " + currentScope);
        output.outln("    before states: " + result.beforeStates
                + " transitions: " + result.beforeTransitions);
        output.outln("    explicit Error seeds: " + result.explicitErrorSeeds);
        output.outln("    definite losing states: " + result.losingStates);
        output.outln("    deferred uncontrollable action groups: " + result.deferredActionGroups);
        output.outln("    removed controllable action groups: " + result.removedControllableActionGroups);
        output.outln("    after states: " + result.afterStates
                + " transitions: " + result.afterTransitions);
        output.outln("    elapsed: " + result.elapsedMillis + " ms");
        if (result.initialLosing) {
            output.outln("    initial state is definitely losing.");
        }
    }

    private static void recordEvaluationMetrics(
            String scope,
            Result result,
            long beforeCountTime,
            long afterBeforeCleanupCountTime,
            long afterCountTime,
            long backwardTime,
            long losingActionGroupCountTime,
            long pruneTime) {
        String section = "Safety Backward Pruning";
        String safeScope = scope == null || scope.isEmpty() ? "unknown" : scope;
        UpdatingControllerEvaluationRecorder.recordTime(
                section,
                safeScope + " / SBP 全体時間",
                pruneTime);
        UpdatingControllerEvaluationRecorder.addTime(
                section,
                "SBP 全体時間合計",
                pruneTime);
        String modeSection = sbpModeSection(safeScope);
        if (!modeSection.isEmpty()) {
            UpdatingControllerEvaluationRecorder.addTime(
                    modeSection,
                    "SBP 全体時間合計",
                    pruneTime);
        }
        UpdatingControllerEvaluationRecorder.recordTime(
                section,
                safeScope + " / backward losing propagation 時間",
                backwardTime);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                section,
                safeScope + " / before SBP",
                result.beforeStates,
                result.beforeTransitions,
                beforeCountTime);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                section,
                safeScope + " / after SBP before cleanup",
                result.afterBeforeCleanupStates,
                result.afterBeforeCleanupTransitions,
                afterBeforeCleanupCountTime);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                section,
                safeScope + " / after SBP cleanup",
                result.afterStates,
                result.afterTransitions,
                afterCountTime);
        UpdatingControllerEvaluationRecorder.recordStateTransitionReduction(
                "Safety Backward Pruning 削減率",
                safeScope + " / before -> after cleanup",
                result.beforeStates,
                result.beforeTransitions,
                result.afterStates,
                result.afterTransitions);
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                safeScope + " / dead-end seed states",
                result.deadEndSeeds,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                safeScope + " / backward losing states",
                result.losingStates,
                "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                safeScope + " / losing controllable action groups",
                result.losingActionGroups,
                "action_groups");
        UpdatingControllerEvaluationRecorder.recordEvaluationCountTime(
                section,
                safeScope + " / losing controllable action group CountTime",
                losingActionGroupCountTime,
                "SBP により除去される controllable action group 数を数える評価用 CountTime。");
        UpdatingControllerEvaluationRecorder.recordCount(
                section,
                safeScope + " / initial state losing",
                result.initialLosing ? 1 : 0,
                "boolean");
    }

    private static String sbpModeSection(String scope) {
        String normalized = scope == null ? "" : scope.toLowerCase();
        if (normalized.startsWith("traditional")) {
            return "Traditional DUC";
        }
        if (normalized.startsWith("stepwise")
                || normalized.startsWith("local ")
                || normalized.startsWith("cross ")) {
            return "Stepwise Delayed DUC";
        }
        return "";
    }

    private static boolean isLosingByPredecessorRule(
            Map<String, Set<Long>> actionTargets,
            Set<String> controllableActions,
            Set<Long> losingStates) {
        if (actionTargets == null || actionTargets.isEmpty()) {
            return true;
        }
        boolean hasUncontrollable = false;
        boolean hasSafeControllable = false;
        for (Map.Entry<String, Set<Long>> entry : actionTargets.entrySet()) {
            boolean reachesLosing = reachesLosing(entry.getValue(), losingStates);
            if (controllableActions.contains(entry.getKey())) {
                if (!reachesLosing) {
                    hasSafeControllable = true;
                }
            } else {
                hasUncontrollable = true;
                if (reachesLosing) {
                    return true;
                }
            }
        }
        return !hasUncontrollable && !hasSafeControllable;
    }

    private static long countLosingControllableActionGroups(
            MTS<Long, String> environment,
            Map<Long, Map<String, Set<Long>>> transitionsByAction,
            Set<String> controllableActions,
            Set<Long> losingStates) {
        long count = 0L;
        for (Long state : environment.getStates()) {
            if (losingStates.contains(state)) {
                continue;
            }
            Map<String, Set<Long>> actionTargets = transitionsByAction.get(state);
            if (actionTargets == null) {
                continue;
            }
            for (Map.Entry<String, Set<Long>> entry : actionTargets.entrySet()) {
                if (controllableActions.contains(entry.getKey())
                        && reachesLosing(entry.getValue(), losingStates)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static MTS<Long, String> buildPrunedEnvironment(
            MTS<Long, String> environment,
            Map<Long, Map<String, Set<Long>>> transitionsByAction,
            Set<String> controllableActions,
            Set<Long> losingStates,
            boolean initialLosing) {
        MTS<Long, String> result = new MTSImpl<Long, String>(environment.getInitialState());
        result.addActions(environment.getActions());
        if (initialLosing) {
            return result;
        }

        for (Long state : environment.getStates()) {
            if (!losingStates.contains(state)) {
                result.addState(state);
            }
        }
        for (Long state : environment.getStates()) {
            if (losingStates.contains(state)) {
                continue;
            }
            Map<String, Set<Long>> actionTargets = transitionsByAction.get(state);
            if (actionTargets == null) {
                continue;
            }
            for (Map.Entry<String, Set<Long>> entry : actionTargets.entrySet()) {
                String action = entry.getKey();
                if (controllableActions.contains(action)
                        && reachesLosing(entry.getValue(), losingStates)) {
                    continue;
                }
                for (Long target : entry.getValue()) {
                    if (!losingStates.contains(target)) {
                        result.addRequired(state, action, target);
                    }
                }
            }
        }
        return result;
    }

    private static boolean reachesLosing(Set<Long> targets, Set<Long> losingStates) {
        for (Long target : targets) {
            if (losingStates.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Long, Map<String, Set<Long>>> groupTransitions(MTS<Long, String> environment) {
        Map<Long, Map<String, Set<Long>>> result = new HashMap<Long, Map<String, Set<Long>>>();
        for (Long state : environment.getStates()) {
            Map<String, Set<Long>> actionTargets = new LinkedHashMap<String, Set<Long>>();
            for (Pair<String, Long> transition : environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
                Set<Long> targets = actionTargets.get(transition.getFirst());
                if (targets == null) {
                    targets = new LinkedHashSet<Long>();
                    actionTargets.put(transition.getFirst(), targets);
                }
                targets.add(transition.getSecond());
            }
            result.put(state, actionTargets);
        }
        return result;
    }

    public static long countTransitions(MTS<Long, String> environment) {
        long count = 0L;
        for (Long state : environment.getStates()) {
            count += environment.getTransitions(state, MTS.TransitionType.REQUIRED).size();
        }
        return count;
    }

    private static void log(LTSOutput output, String scope, Result result) {
        if (output == null) {
            return;
        }
        output.outln("  safety backward pruning scope: " + scope);
        output.outln("    before states: " + result.beforeStates
                + " transitions: " + result.beforeTransitions);
        output.outln("    dead-end seed states: " + result.deadEndSeeds);
        output.outln("    backward losing states: " + result.losingStates);
        output.outln("    losing controllable action groups: " + result.losingActionGroups);
        output.outln("    after backward states: " + result.afterBeforeCleanupStates
                + " transitions: " + result.afterBeforeCleanupTransitions);
        output.outln("    after cleanup states: " + result.afterStates
                + " transitions: " + result.afterTransitions);
        if (result.initialLosing) {
            output.outln("    initial state is losing: there is no controller.");
        }
    }

    public static final class Result {
        private final MTS<Long, String> environment;
        private final boolean initialLosing;
        private final long beforeStates;
        private final long beforeTransitions;
        private final long afterBeforeCleanupStates;
        private final long afterBeforeCleanupTransitions;
        private final long afterStates;
        private final long afterTransitions;
        private final long losingStates;
        private final long deadEndSeeds;
        private final long losingActionGroups;

        private Result(
                MTS<Long, String> environment,
                boolean initialLosing,
                long beforeStates,
                long beforeTransitions,
                long afterBeforeCleanupStates,
                long afterBeforeCleanupTransitions,
                long afterStates,
                long afterTransitions,
                long losingStates,
                long deadEndSeeds,
                long losingActionGroups) {
            this.environment = environment;
            this.initialLosing = initialLosing;
            this.beforeStates = beforeStates;
            this.beforeTransitions = beforeTransitions;
            this.afterBeforeCleanupStates = afterBeforeCleanupStates;
            this.afterBeforeCleanupTransitions = afterBeforeCleanupTransitions;
            this.afterStates = afterStates;
            this.afterTransitions = afterTransitions;
            this.losingStates = losingStates;
            this.deadEndSeeds = deadEndSeeds;
            this.losingActionGroups = losingActionGroups;
        }

        public MTS<Long, String> getEnvironment() {
            return environment;
        }

        public boolean isInitialLosing() {
            return initialLosing;
        }
    }

    private static final class DeferredBuild {
        private final MTS<Long, String> environment;
        private final Set<Long> errorStates;
        private final long deferredActionGroups;
        private final long removedControllableActionGroups;

        private DeferredBuild(
                MTS<Long, String> environment,
                Set<Long> errorStates,
                long deferredActionGroups,
                long removedControllableActionGroups) {
            this.environment = environment;
            this.errorStates = errorStates;
            this.deferredActionGroups = deferredActionGroups;
            this.removedControllableActionGroups = removedControllableActionGroups;
        }
    }

    public static final class DeferredResult {
        private final MTS<Long, String> environment;
        private final Set<Long> errorStates;
        private final boolean initialLosing;
        private final long beforeStates;
        private final long beforeTransitions;
        private final long afterStates;
        private final long afterTransitions;
        private final long losingStates;
        private final long explicitErrorSeeds;
        private final long deferredActionGroups;
        private final long removedControllableActionGroups;
        private final long elapsedMillis;

        private DeferredResult(
                MTS<Long, String> environment,
                Set<Long> errorStates,
                boolean initialLosing,
                long beforeStates,
                long beforeTransitions,
                long afterStates,
                long afterTransitions,
                long losingStates,
                long explicitErrorSeeds,
                long deferredActionGroups,
                long removedControllableActionGroups,
                long elapsedMillis) {
            this.environment = environment;
            this.errorStates = new LinkedHashSet<Long>(errorStates);
            this.initialLosing = initialLosing;
            this.beforeStates = beforeStates;
            this.beforeTransitions = beforeTransitions;
            this.afterStates = afterStates;
            this.afterTransitions = afterTransitions;
            this.losingStates = losingStates;
            this.explicitErrorSeeds = explicitErrorSeeds;
            this.deferredActionGroups = deferredActionGroups;
            this.removedControllableActionGroups = removedControllableActionGroups;
            this.elapsedMillis = elapsedMillis;
        }

        public MTS<Long, String> getEnvironment() {
            return environment;
        }

        public Set<Long> getErrorStates() {
            return new LinkedHashSet<Long>(errorStates);
        }

        public boolean isInitialLosing() {
            return initialLosing;
        }

        public long getDeferredActionGroups() {
            return deferredActionGroups;
        }
    }
}
