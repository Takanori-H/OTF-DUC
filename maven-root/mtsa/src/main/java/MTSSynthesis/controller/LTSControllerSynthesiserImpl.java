package MTSSynthesis.controller;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSSynthesis.ar.dc.uba.model.condition.FluentUtils;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.gr.GRGameSolver;
import MTSSynthesis.controller.gr.GRRank;
import MTSSynthesis.controller.gr.GRRankSystem;
import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.gr.lazy.LazyGRGameSolver;
import MTSSynthesis.controller.gr.perfect.PerfectInfoGRGameSolver;
import MTSSynthesis.controller.model.GameSolver;
import MTSSynthesis.controller.model.Strategy;
import MTSSynthesis.controller.util.GRGameBuilder;
import MTSSynthesis.controller.util.GameStrategyToMTSBuilder;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.CompactState;
import MTSSynthesis.controller.differential.nativeintegration.NativeSynthesisCapture;
import MTSSynthesis.controller.differential.nativeintegration.NativeGenerationTiming;
import MTSSynthesis.controller.differential.nativeintegration.NativeMemoryDiagnostics;

public class LTSControllerSynthesiserImpl<S,A> implements LTSControllerSynthesiser<S,A> {

  private GRGame<S> game;
  
	/* (non-Javadoc)
	 * @see controller.LTSControllerSynthesiser#synthesise(ac.ic.doc.mtstools.model.MTS, controller.model.ControllerGoal)
	 */
	@Override
	public MTS<StrategyState<S, Integer>, A> synthesiseGR(MTS<S, A> plant, ControllerGoal<A> goal) {
        return synthesiseGR(plant, goal, null);
    }

    public MTS<StrategyState<S, Integer>, A> synthesiseGR(MTS<S, A> plant, ControllerGoal<A> goal,
            NativeSynthesisCapture<S, A> capture) {
        NativeMemoryDiagnostics diagnostics = capture == null ? null : capture.getMemoryDiagnostics();
        long phaseStart = capture == null ? 0L : System.nanoTime();
        if (capture != null) capture.setPlant(plant);

		//TODO: Remove this when ConcurrencyGRGameSolver is ready.
		int maxLazyness = goal.getLazyness();
		GameSolver<S, Integer> solver;
		GRGameSolver<S> gSolver;
		
        NativeMemoryDiagnostics.Token gameBuild = diagnostics == null ? null : diagnostics.begin(
                "native_game_build", NativeMemoryDiagnostics.counts("plant_states", plant.getStates().size()));
        game = new GRGameBuilder<S, A>().buildGRGameFrom(plant, goal);
        if (diagnostics != null) diagnostics.end(gameBuild, NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
        if (capture != null) {
            capture.setGame(game);
            capture.addNanos(NativeSynthesisCapture.GAME_UPDATE, System.nanoTime() - phaseStart);
            phaseStart = System.nanoTime();
        }
        NativeMemoryDiagnostics.Token ranks = diagnostics == null ? null : diagnostics.begin(
                "native_rank_setup", NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
		if(maxLazyness > 0 ){
			GRRankSystem<S> system = new GRRankSystem<S>(game.getStates(), game.getGoal().getGuarantees(), game.getGoal().getAssumptions(), game.getGoal().getFailures());
			gSolver = new LazyGRGameSolver<S>(game, system, maxLazyness);
			solver = gSolver;
		}else{
			GRRankSystem<S> system = new GRRankSystem<S>(game.getStates(), game.getGoal().getGuarantees(), game.getGoal().getAssumptions(), game.getGoal().getFailures());
			gSolver = new PerfectInfoGRGameSolver<S>(game, system);
			solver = gSolver;
		}
		
        if (diagnostics != null) diagnostics.end(ranks, NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
        NativeMemoryDiagnostics.Token solve = diagnostics == null ? null : diagnostics.begin(
                "native_solve", NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
		solver.solveGame();
        if (diagnostics != null) diagnostics.end(solve, NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
        if (capture != null) {
            NativeMemoryDiagnostics.Token winningCapture = diagnostics == null ? null : diagnostics.begin(
                    "native_winning_capture", NativeMemoryDiagnostics.counts("plant_states", plant.getStates().size()));
            Set<S> winning = new HashSet<>();
            for (S state : plant.getStates()) {
                if (solver.isWinning(state)) winning.add(state);
            }
            capture.setWinningStates(winning);
            if (diagnostics != null) diagnostics.end(winningCapture, NativeMemoryDiagnostics.counts("winning_states", winning.size()));
        }

		GRGameSolver<S> grSolver = gSolver;
		MTS<StrategyState<S, Integer>, A> result = null;

		if(!solver.isWinning(plant.getInitialState())){
            if (capture != null) {
                capture.addNanos(NativeSynthesisCapture.REQUIREMENTS_ANALYSIS, System.nanoTime() - phaseStart);
            }
			return null;
		}else{
			MTSImpl<?, ?> mtsImplOfPlant = (MTSImpl<?, ?>) plant;

            if(((MTSImpl) plant).getStateToComponentStates()!=null){
                NativeMemoryDiagnostics.Token winningMetadata = diagnostics == null ? null : diagnostics.begin(
                        "native_winning_metadata", NativeMemoryDiagnostics.counts("plant_states", plant.getStates().size()));
                // Set winning states in plant
                Set<Long> winningStatesOfPlant = new HashSet<Long>();
                Set<Long> winningStatesOfOriginalEnv = new HashSet<>();
                for(S stateInPlant : plant.getStates()){
                    if(solver.isWinning(stateInPlant)){
                        winningStatesOfPlant.add((Long) stateInPlant);
                        winningStatesOfOriginalEnv.add((long) ((MTSImpl<S, A>) plant).getStateToComponentStates().get(Math.toIntExact((Long) stateInPlant))[0]);
                    }
                }
                plant.setWinningStatesOfOriginalEnv(winningStatesOfOriginalEnv);
                plant.setWinningStates(winningStatesOfPlant);
                if (diagnostics != null) diagnostics.end(winningMetadata, NativeMemoryDiagnostics.counts(
                        "plant_winning_states", winningStatesOfPlant.size(),
                        "environment_winning_states", winningStatesOfOriginalEnv.size()));
            }


            if (capture != null) {
                capture.addNanos(NativeSynthesisCapture.REQUIREMENTS_ANALYSIS, System.nanoTime() - phaseStart);
                phaseStart = System.nanoTime();
            }
            NativeGenerationTiming.Stamp generationStart = capture == null ? null
                    : capture.getGenerationTiming().start();
            NativeMemoryDiagnostics.Token controllerBuild = diagnostics == null ? null : diagnostics.begin(
                    "native_controller_build", NativeMemoryDiagnostics.counts("game_states", game.getStates().size()));
			Strategy<S, Integer> strategy = solver.buildStrategy();
			//TODO refactor permissive
			Set<Pair<StrategyState<S, Integer>, StrategyState<S, Integer>>> worseRank = grSolver.getWorseRank();
			result = GameStrategyToMTSBuilder.getInstance().buildMTSFrom(plant, strategy, worseRank, maxLazyness);
			result.removeUnreachableStates();
            if (diagnostics != null) diagnostics.end(controllerBuild, NativeMemoryDiagnostics.counts(
                    "controller_states", result.getStates().size()));
            if (capture != null) {
                capture.setStrategyController(result);
                capture.getGenerationTiming().finishGenerationSegment("controller_build", generationStart);
                capture.addNanos(NativeSynthesisCapture.CONTROLLER_GENERATION, System.nanoTime() - phaseStart);
            }

			return result;
		}
    }

	

	/* (non-Javadoc)
	 * @see controller.LTSControllerSynthesiser#checkAssumptionsCompatibility(ac.ic.doc.mtstools.model.MTS, controller.model.ControllerGoal)
	 */
	@Override
	public boolean checkGRAssumptionsCompatibility(MTS<S, A> plant, ControllerGoal<A> goal) {
		
		if (!goal.getFaults().isEmpty()){
			Set<Symbol> symbols = FluentUtils.getInstance().getInitiatingSymbols(goal.getFluentsInFaults());
			goal.addAllControllableActions(this.symbolsToActions(symbols));
		}

		//no goals needed.
		goal.getGuarantees().clear();
		goal.getFaults().clear();
		
		goal.addGuarantee(Formula.FALSE_FORMULA);
		GRGame<S> game = new GRGameBuilder<S,A>().buildGRGameFrom(plant, goal);
		GRRankSystem<S> system = new GRRankSystem<S>(game.getStates(), game.getGoal().getGuarantees(), game.getGoal().getAssumptions(), game.getGoal().getFailures());
		
		GameSolver<S, Integer> solver = new PerfectInfoGRGameSolver<S>(game, system);
		solver.solveGame();
		
		/* 
		 * If for every reachable state the controller cannot force the assumptions 
		 * not to hold, then the assumptions are compatible with the plant.  
		 */
		Boolean stateRemoved = plant.removeUnreachableStates();
		Logger.getAnonymousLogger().log(Level.INFO, "Environment model with"+ (stateRemoved?"": "out") + " unreachable states");
		for (S state : plant.getStates()) {
			GRRank rank = system.getRank(new StrategyState<S,Integer>(state, 1));
			if (!rank.isInfinity()) {
				return false;
			}
		}
		return true;
	}

	/*
	 * This method is a hack needed to handle Symbols
	 */
	@SuppressWarnings("unchecked")
	private Set<A> symbolsToActions(Set<Symbol> symbols) {
		Set<A> actions = new HashSet<A>();
		for (Symbol symbol : symbols) {
			//TODO Symbol class needs refactor. Actually it's not needed. 
			actions.add((A)symbol.toString());
		}
		return actions;
	}

  @Override
  public GRGame<S> getGame()
  {
    return game;
  }
}
