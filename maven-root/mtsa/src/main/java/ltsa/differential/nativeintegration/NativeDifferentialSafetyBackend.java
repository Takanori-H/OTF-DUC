package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.differential.nativeintegration.NativeGenerationTiming;

import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.NativeSafetyGameUpdater;
import MTSSynthesis.controller.model.Strategy;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSSynthesis.controller.util.GameStrategyToMTSBuilder;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.Analyser;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.EventState;
import ltsa.lts.EventStateUtils;
import ltsa.lts.LTSOutput;
import ltsa.lts.MyList;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.Vector;

import static MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType.REQUIRED;

/**
 * Incremental operations on MTSA's native MTS and GRGame, for fixed safety.
 * This backend has no full solver: the reference is the ordinary MTSA solver.
 * Callers own an isolated working session; product/game updates mutate it.
 * Tuple identities must be captured/validated across MTSA conversions by the
 * caller, never inferred from equal numeric state identifiers.
 */
public final class NativeDifferentialSafetyBackend {
    private NativeDifferentialSafetyBackend() { }

    public static final class EnvironmentEdge {
        public final int source;
        public final String action;
        public final int target;

        public EnvironmentEdge(int source, String action, int target) {
            this.source = source;
            this.action = action;
            this.target = target;
        }
    }

    public static final class Edge {
        public final long source;
        public final String action;
        public final long target;

        private Edge(long source, String action, long target) {
            this.source = source;
            this.action = action;
            this.target = target;
        }
    }

    /** Supplemental correspondence and reverse lookup, not a second graph. */
    public static final class ProductIndex {
        private final Map<Long, int[]> tuples = new HashMap<>();
        private final Map<Tuple, Long> ids = new HashMap<>();
        private final Map<Integer, Set<Long>> environmentStates = new HashMap<>();
        private final int arity;
        private long nextId;

        public ProductIndex(MTS<Long, String> product, Map<Long, int[]> stateTuples,
                            int componentCount) {
            if (componentCount < 1) throw new IllegalArgumentException("Missing environment component");
            arity = componentCount;
            for (Long state : product.getStates()) {
                if (!product.getTransitions(state, MTS.TransitionType.MAYBE).isEmpty()) {
                    throw new UnsupportedOperationException("Native differential snapshot requires ordinary LTS transitions");
                }
                if (state == -1L) continue;
                int[] tuple = stateTuples.get(state);
                if (state < 0 || tuple == null || tuple.length != arity) {
                    throw new IllegalArgumentException("Missing validated component tuple for state " + state);
                }
                put(state, tuple);
                nextId = Math.max(nextId, state + 1);
            }
        }

        private void put(long state, int[] sourceTuple) {
            int[] tuple = sourceTuple.clone();
            if (ids.put(new Tuple(tuple), state) != null) {
                throw new IllegalArgumentException("Duplicate semantic product tuple");
            }
            tuples.put(state, tuple);
            environmentStates.computeIfAbsent(tuple[0], ignored -> new LinkedHashSet<>()).add(state);
        }

        public Map<Long, int[]> copyTuples() {
            Map<Long, int[]> result = new HashMap<>();
            for (Map.Entry<Long, int[]> entry : tuples.entrySet()) {
                result.put(entry.getKey(), entry.getValue().clone());
            }
            return result;
        }
    }

    public static final class Delta {
        private final Set<Long> newStates = new LinkedHashSet<>();
        private final Set<Long> changedOldSources = new LinkedHashSet<>();
        private final Set<Long> oldSourcesWithoutUncontrollable = new LinkedHashSet<>();
        private final List<Edge> addedEdges = new ArrayList<>();
        private final Set<String> controls;
        private List<CompactState> updatedComponents;

        private Delta(Set<String> controls) { this.controls = new HashSet<>(controls); }
        public Set<Long> getNewStates() { return Collections.unmodifiableSet(newStates); }
        public Set<Long> getChangedOldSources() { return Collections.unmodifiableSet(changedOldSources); }
        public List<Edge> getAddedEdges() { return Collections.unmodifiableList(addedEdges); }
        public List<CompactState> getUpdatedComponents() { return updatedComponents; }
    }

    /**
     * Add one deterministic U edge between existing composed-ENV states.
     * The action must already be in its alphabet, so synchronization ownership
     * of actions is unchanged. Only changed old sources and newly found tuples
     * are enumerated by MTSA's existing Analyser successor implementation.
     */
    public static Delta updateProduct(MTS<Long, String> product, ProductIndex index,
                                      List<CompactState> components, EnvironmentEdge addition,
                                      Set<String> controls, LTSOutput output) {
        if (components.size() != index.arity) throw new IllegalArgumentException("Component order/arity changed");
        List<CompactState> updated = updatedComponents(components, addition, controls);
        Delta result = new Delta(controls);
        result.updatedComponents = updated;
        Analyser explorer = new Analyser(new CompositeState(new Vector<>(updated)), output, null);
        String[] labels = explorer.getTransitionsLabels();
        for (String label : labels) {
            if (!product.getActions().contains(label)) {
                throw new IllegalArgumentException("Saved product alphabet does not match the components");
            }
        }
        Queue<Long> pending = new ArrayDeque<>();
        Set<Long> seeds = index.environmentStates.getOrDefault(addition.source, Collections.emptySet());
        pending.addAll(seeds);
        while (!pending.isEmpty()) {
            long source = pending.remove();
            boolean wasOldSource = !result.newStates.contains(source);
            boolean hadUncontrollable = false;
            if (wasOldSource) {
                for (Pair<String, Long> oldEdge : product.getTransitions(source, REQUIRED)) {
                    hadUncontrollable |= !controls.contains(oldEdge.getFirst());
                }
            }
            MyList transitions = explorer.getTransitions(explorer.coder.encode(index.tuples.get(source)));
            Map<String, Long> seenActions = new HashMap<>();
            while (!transitions.empty()) {
                String label = labels[transitions.getAction()];
                byte[] encoded = transitions.getTo();
                long target;
                if (encoded == null) {
                    target = -1L;
                    if (product.addState(target)) result.newStates.add(target);
                } else {
                    int[] tuple = Arrays.copyOf(explorer.coder.decode(encoded), index.arity);
                    Long existing = index.ids.get(new Tuple(tuple));
                    if (existing == null) {
                        target = index.nextId++;
                        product.addState(target);
                        index.put(target, tuple);
                        result.newStates.add(target);
                        pending.add(target);
                    } else {
                        target = existing;
                    }
                }
                Long otherTarget = seenActions.put(label, target);
                if (otherTarget != null && otherTarget != target) {
                    throw new IllegalArgumentException("Nondeterministic native product is unsupported");
                }
                if (product.addRequired(source, label, target)) {
                    result.addedEdges.add(new Edge(source, label, target));
                    if (wasOldSource) {
                        result.changedOldSources.add(source);
                        if (!hadUncontrollable) result.oldSourcesWithoutUncontrollable.add(source);
                    }
                }
                transitions.next();
            }
        }
        return result;
    }

    /** Shared environment-delta validation/application for ordinary FULL and both delta modes. */
    public static List<CompactState> updatedComponents(List<CompactState> components,
                                                       EnvironmentEdge addition, Set<String> controls) {
        if (components.isEmpty()) throw new IllegalArgumentException("Missing saved environment component");
        if (controls.contains(addition.action)) throw new IllegalArgumentException("Expected uncontrollable addition");
        CompactState environment = components.get(0);
        if (addition.source < 0 || addition.source >= environment.maxStates
                || addition.target < -1 || addition.target >= environment.maxStates) {
            throw new IllegalArgumentException("Environment endpoint is outside the saved model");
        }
        int action = Arrays.asList(environment.alphabet).indexOf(addition.action);
        if (action < 0 || action == 0 || addition.action.contains("?")
                || addition.action.equals("*") || addition.action.startsWith("@")) {
            throw new IllegalArgumentException("Expected existing visible ordinary-LTS action");
        }
        if (EventState.hasEvent(environment.states[addition.source], action)) {
            throw new IllegalArgumentException("Addition would duplicate a source/action or introduce nondeterminism");
        }
        List<CompactState> updated = new ArrayList<>(components);
        CompactState newEnvironment = environment.myclone();
        newEnvironment.states[addition.source] = EventStateUtils.add(newEnvironment.states[addition.source],
                new EventState(action, addition.target));
        updated.set(0, newEnvironment);
        return Collections.unmodifiableList(updated);
    }

    /** Apply product additions to the already captured ordinary native game. */
    public static void updateGame(GRGame<Long> game, Delta delta) {
        NativeSafetyGameUpdater.requirePureSafety(game, delta.newStates);
        NativeSafetyGameUpdater.addStates(game, delta.newStates);
        for (Edge edge : delta.addedEdges) {
            NativeSafetyGameUpdater.addTransition(game, edge.source, edge.target,
                    delta.controls.contains(edge.action));
        }
    }

    public static final class Analysis {
        private final Set<Long> winning;
        private final Set<Long> evaluated;
        private Analysis(Set<Long> winning, Set<Long> evaluated) {
            this.winning = Collections.unmodifiableSet(winning);
            this.evaluated = Collections.unmodifiableSet(evaluated);
        }
        public Set<Long> getWinning() { return winning; }
        public Set<Long> getEvaluated() { return evaluated; }
    }

    /**
     * Published-style retained-losing propagation, with the same conservative
     * applicability guard as the prior implementation. No general GR warm-start
     * or predecessor-closure fallback is silently substituted here.
     */
    public static Analysis analyze(GRGame<Long> game, Set<Long> oldWinning, Delta delta) {
        NativeSafetyGameUpdater.requirePureSafety(game, Collections.emptySet());
        if (!delta.oldSourcesWithoutUncontrollable.isEmpty()) {
            throw new UnsupportedOperationException("Cached-loss analysis requires an old U successor at every changed old source");
        }
        for (Edge edge : delta.addedEdges) {
            if (!delta.newStates.contains(edge.source) && delta.controls.contains(edge.action)) {
                throw new UnsupportedOperationException("Cached-loss analysis cannot reuse an old source gaining a C edge");
            }
        }
        Set<Long> losing = new HashSet<>(game.getStates());
        losing.removeAll(oldWinning);
        losing.removeAll(delta.newStates);
        Set<Long> queued = new HashSet<>();
        Set<Long> evaluated = new HashSet<>();
        Queue<Long> pending = new ArrayDeque<>();
        for (Long state : delta.newStates) if (queued.add(state)) pending.add(state);
        for (Long state : delta.changedOldSources) if (queued.add(state)) pending.add(state);
        while (!pending.isEmpty()) {
            long state = pending.remove();
            queued.remove(state);
            if (losing.contains(state)) continue;
            evaluated.add(state);
            boolean loss = state == -1L;
            if (game.isUncontrollable(state)) {
                for (Long successor : game.getUncontrollableSuccessors(state)) loss |= losing.contains(successor);
            } else {
                loss |= losing.containsAll(game.getControllableSuccessors(state));
            }
            if (loss) {
                losing.add(state);
                for (Long predecessor : game.getPredecessors(state)) {
                    if (!losing.contains(predecessor) && queued.add(predecessor)) pending.add(predecessor);
                }
            }
        }
        Set<Long> winning = new HashSet<>(game.getStates());
        winning.removeAll(losing);
        return new Analysis(winning, evaluated);
    }

    public static MTS<StrategyState<Long, Integer>, String> fullController(
            MTS<Long, String> product, Set<Long> winning) {
        requireWinningInitial(product, winning);
        Strategy<Long, Integer> strategy = new Strategy<>();
        for (Long source : winning) {
            Set<StrategyState<Long, Integer>> successors = new HashSet<>();
            for (Pair<String, Long> edge : product.getTransitions(source, REQUIRED)) {
                if (winning.contains(edge.getSecond())) successors.add(strategyState(edge.getSecond()));
            }
            if (successors.isEmpty()) throw new IllegalArgumentException("Winning state has no winning successor");
            strategy.addSuccessors(strategyState(source), successors);
        }
        return GameStrategyToMTSBuilder.getInstance().buildMTSFrom(product, strategy, Collections.emptySet());
    }

    /**
     * Copy the retained native controller, then expand only affected existing
     * controller sources and newly reached winning states. Copying is part of
     * this method so its cost belongs to controller-generation measurement.
     */
    public static MTS<StrategyState<Long, Integer>, String> differentialController(
            MTS<Long, String> product, Set<Long> oldWinning, Set<Long> winning,
            MTS<StrategyState<Long, Integer>, String> oldController, Delta delta) {
        return differentialController(product, oldWinning, winning, oldController, delta, null);
    }

    public static MTS<StrategyState<Long, Integer>, String> differentialController(
            MTS<Long, String> product, Set<Long> oldWinning, Set<Long> winning,
            MTS<StrategyState<Long, Integer>, String> oldController, Delta delta, NativeGenerationTiming timing) {
        requireWinningInitial(product, winning);
        if (!winning.containsAll(oldWinning)) {
            throw new IllegalArgumentException("Updated winning region does not preserve the old winning region");
        }
        NativeGenerationTiming.Stamp copyStart = timing == null ? null : timing.start();
        MTS<StrategyState<Long, Integer>, String> result = new MTSImpl<>(oldController.getInitialState());
        result.addActions(product.getActions());
        result.addStates(oldController.getStates());
        for (StrategyState<Long, Integer> source : oldController.getStates()) {
            for (Pair<String, StrategyState<Long, Integer>> edge : oldController.getTransitions(source, REQUIRED)) {
                result.addRequired(source, edge.getFirst(), edge.getSecond());
            }
        }
        if (timing != null) timing.finish("controller_copy", copyStart);
        NativeGenerationTiming.Stamp patchStart = timing == null ? null : timing.start();
        Queue<Long> pending = new ArrayDeque<>();
        for (Long source : delta.changedOldSources) {
            if (result.getStates().contains(strategyState(source))) pending.add(source);
        }
        while (!pending.isEmpty()) {
            long source = pending.remove();
            for (Pair<String, Long> edge : product.getTransitions(source, REQUIRED)) {
                if (!winning.contains(edge.getSecond())) continue;
                StrategyState<Long, Integer> target = strategyState(edge.getSecond());
                if (result.addState(target)) pending.add(edge.getSecond());
                result.addRequired(strategyState(source), edge.getFirst(), target);
            }
        }
        if (timing != null) timing.finish("controller_patch", patchStart);
        return result;
    }

    private static void requireWinningInitial(MTS<Long, String> product, Set<Long> winning) {
        if (!winning.contains(product.getInitialState())) throw new IllegalArgumentException("No winning controller");
    }

    private static StrategyState<Long, Integer> strategyState(long state) {
        return new StrategyState<>(state, 1);
    }

    private static final class Tuple {
        private final int[] states;
        private final int hash;
        private Tuple(int[] states) { this.states = states; hash = Arrays.hashCode(states); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof Tuple && Arrays.equals(states, ((Tuple) other).states);
        }
    }
}
