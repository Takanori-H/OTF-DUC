package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.differential.nativeintegration.NativeSynthesisCapture;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSSynthesis.controller.model.NativeSafetyGameUpdater;
import MTSSynthesis.controller.util.GRGameBuilder;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.CompactState;

import java.util.*;

/** Native MTSA artifacts retained between two synthesis requests. No replacement game. */
public final class NativeSynthesisSnapshot {
    public final List<CompactState> components;
    public final Set<String> controllable;
    public final MTS<Long, String> plant;
    public final GRGame<Long> game;
    public final Set<Long> winning;
    public final MTS<StrategyState<Long, Integer>, String> controller;
    public final CompactState output;
    public final Map<Long, int[]> tuples;

    public NativeSynthesisSnapshot(List<CompactState> components, Set<String> controllable,
            MTS<Long, String> plant, GRGame<Long> game, Set<Long> winning,
            MTS<StrategyState<Long, Integer>, String> controller, CompactState output,
            Map<Long, int[]> tuples) {
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
        this.controllable = Collections.unmodifiableSet(new HashSet<>(controllable));
        this.plant = plant;
        this.game = game;
        this.winning = Collections.unmodifiableSet(new HashSet<>(winning));
        this.controller = Objects.requireNonNull(controller);
        this.output = Objects.requireNonNull(output);
        this.tuples = new HashMap<>();
        for (Map.Entry<Long, int[]> entry : tuples.entrySet()) {
            this.tuples.put(entry.getKey(), entry.getValue().clone());
        }
        validate();
    }

    public ControllerGoal<String> goal() {
        ControllerGoal<String> goal = new ControllerGoal<>();
        goal.addAllControllableActions(controllable);
        return goal;
    }

    public static GRGame<Long> rebuildGame(MTS<Long, String> plant, Set<String> controls) {
        ControllerGoal<String> goal = new ControllerGoal<>();
        goal.addAllControllableActions(controls);
        return new GRGameBuilder<Long, String>().buildGRGameFrom(plant, goal);
    }

    public static NativeSynthesisSnapshot capture(List<CompactState> components, Set<String> controls,
            NativeSynthesisCapture<Long, String> capture, CompactState finalOutput) {
        if (capture.getPlant() == null || capture.getStrategyController() == null) {
            throw new IllegalStateException("Ordinary MTSA synthesis did not produce native capture");
        }
        Map<Long, int[]> tuples = new HashMap<>();
        for (Long state : capture.getPlant().getStates()) {
            if (state == -1L) continue;
            Long productState = capture.getPlantToProductStates().get(state);
            int[] tuple = productState == null ? null
                    : capture.getProductToComponentStates().get(Math.toIntExact(productState));
            if (tuple == null || tuple.length < components.size()) {
                throw new IllegalStateException("Missing native component correspondence for plant state " + state);
            }
            tuples.put(state, Arrays.copyOf(tuple, components.size()));
        }
        return new NativeSynthesisSnapshot(components, controls, capture.getPlant(), capture.getGame(),
                capture.getWinningStates(), capture.getStrategyController(), finalOutput, tuples);
    }

    public void validate() {
        if (components.isEmpty() || ((plant == null) != (game == null)) || (plant != null &&
                (!plant.getStates().equals(game.getStates()) || !plant.getStates().containsAll(winning)
                        || !winning.contains(plant.getInitialState())))) {
            throw new IllegalArgumentException("Inconsistent native snapshot game/winning region");
        }
        if (plant != null) {
            NativeSafetyGameUpdater.requirePureSafety(game, Collections.emptySet());
            requireOrdinary(plant);
            if (!controller.getInitialState().getState().equals(plant.getInitialState())) {
                throw new IllegalArgumentException("Controller initial state differs from native plant");
            }
            Set<Long> ordinaryStates = new HashSet<>(plant.getStates());
            ordinaryStates.remove(-1L);
            if (!ordinaryStates.equals(tuples.keySet())) {
                throw new IllegalArgumentException("Incomplete native state correspondence");
            }
        }
        requireOrdinary(controller);
        Set<List<Integer>> distinct = new HashSet<>();
        for (int[] tuple : tuples.values()) {
            if (tuple.length != components.size()) throw new IllegalArgumentException("Tuple arity mismatch");
            List<Integer> key = new ArrayList<>();
            for (int i = 0; i < tuple.length; i++) {
                if (tuple[i] < 0 || tuple[i] >= components.get(i).maxStates) {
                    throw new IllegalArgumentException("Component state out of range");
                }
                key.add(tuple[i]);
            }
            if (!distinct.add(key)) throw new IllegalArgumentException("Duplicate semantic product state");
        }
        for (StrategyState<Long, Integer> state : controller.getStates()) {
            if (state.getMemory() != 1 || state.getLazyness() != 0 || !winning.contains(state.getState())) {
                throw new IllegalArgumentException("Controller outside pure-safety winning region");
            }
        }
    }

    private static <S> void requireOrdinary(MTS<S, String> model) {
        for (S state : model.getStates()) {
            if (!model.getTransitions(state, MTS.TransitionType.MAYBE).isEmpty()) {
                throw new IllegalArgumentException("Snapshot cannot discard optional MTS transitions");
            }
        }
    }
}
