package MTSSynthesis.controller.model;

import MTSSynthesis.controller.model.gr.GRGame;

import java.util.HashSet;
import java.util.Set;

/** Request-local mutation support for an ordinary, pure-safety GRGame. */
public final class NativeSafetyGameUpdater {
    private NativeSafetyGameUpdater() { }

    public static <S> void addStates(GRGame<S> game, Set<S> addedStates) {
        // GRGameBuilder normally retains MTS.getStates(), an unmodifiable view
        // already reflecting product additions. Deserialized games may own a
        // separate set; copy its membership once in that case.
        if (!game.states.containsAll(addedStates)) {
            game.states = new HashSet<>(game.states);
            game.states.addAll(addedStates);
        }
        for (S state : addedStates) initializeState(game, state);
    }

    private static <S> void initializeState(GRGame<S> game, S state) {
        game.predecessors.computeIfAbsent(state, ignored -> new HashSet<>());
        game.controllableSuccessors.computeIfAbsent(state, ignored -> new HashSet<>());
        game.uncontrollableSuccessors.computeIfAbsent(state, ignored -> new HashSet<>());
        // Empty FSP assumptions/guarantees are represented by TRUE by GRGameBuilder.
        game.getGoal().getAssumption(1).addState(state);
        game.getGoal().getGuarantee(1).addState(state);
    }

    public static <S> void addTransition(GRGame<S> game, S source, S target,
                                        boolean controllable) {
        if (!game.predecessors.containsKey(source) || !game.predecessors.containsKey(target)) {
            throw new IllegalArgumentException("Initialize native game states before adding edges");
        }
        if (controllable) {
            game.addControllableSuccessor(source, target);
            if (!game.isUncontrollable(source)) {
                game.addPredecessor(source, target);
            }
        } else {
            if (!game.isUncontrollable(source)) {
                // GRGameBuilder only includes controllable predecessors for sources
                // with no uncontrollable successor. A first U edge changes that.
                for (S oldTarget : game.getControllableSuccessors(source)) {
                    game.predecessors.get(oldTarget).remove(source);
                }
            }
            game.addUncontrollableSuccessor(source, target);
        }
    }

    public static <S> void requirePureSafety(GRGame<S> game, Set<S> addedStates) {
        if (game.getGoal().getAssumptionsQuantity() != 1
                || game.getGoal().getGuaranteesQuantity() != 1
                || !game.getGoal().getFailures().isEmpty()) {
            throw new IllegalArgumentException("Native differential backend supports fixed safety only");
        }
        for (S state : game.getStates()) {
            if (!addedStates.contains(state)
                    && (!game.getGoal().getAssumption(1).contains(state)
                    || !game.getGoal().getGuarantee(1).contains(state))) {
                throw new IllegalArgumentException("Native differential game requires TRUE GR memberships");
            }
        }
    }
}
