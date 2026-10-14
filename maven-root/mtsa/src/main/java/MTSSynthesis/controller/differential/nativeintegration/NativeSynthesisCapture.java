package MTSSynthesis.controller.differential.nativeintegration;

import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.model.gr.GRGame;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.lts.CompactState;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Per-invocation observations of the ordinary MTSA synthesis pipeline.
 * Models are retained native objects, not copies in a second synthesis engine.
 * A caller must give each invocation a fresh capture and take ownership of the
 * retained models before allowing subsequent transformations to mutate them.
 */
public final class NativeSynthesisCapture<S, A> {
    public static final String GAME_UPDATE = "game_update";
    public static final String REQUIREMENTS_ANALYSIS = "requirements_analysis";
    public static final String CONTROLLER_GENERATION = "controller_generation";

    private CompactState product;
    private Map<Integer, int[]> productToComponentStates = Collections.emptyMap();
    private MTS<S, A> plant;
    private GRGame<S> game;
    private Set<S> winningStates = Collections.emptySet();
    private MTS<StrategyState<S, Integer>, A> strategyController;
    private MTS<Long, String> plainController;
    private CompactState controller;
    private CompactState finalController;
    private Map<StrategyState<S, Integer>, Long> strategyToPlainStates = Collections.emptyMap();
    private Map<Long, Long> plainToCompactStates = Collections.emptyMap();
    private Map<Long, Long> plantToProductStates = Collections.emptyMap();
    private final Map<String, Long> phaseNanos = new LinkedHashMap<>();
    private final NativeGenerationTiming generationTiming = new NativeGenerationTiming();
    private NativeMemoryDiagnostics memoryDiagnostics;

    public NativeSynthesisCapture() {
        phaseNanos.put(GAME_UPDATE, 0L);
        phaseNanos.put(REQUIREMENTS_ANALYSIS, 0L);
        phaseNanos.put(CONTROLLER_GENERATION, 0L);
    }

    public void addNanos(String phase, long nanos) {
        if (!phaseNanos.containsKey(phase) || nanos < 0) {
            throw new IllegalArgumentException("Unknown phase or negative duration: " + phase);
        }
        phaseNanos.put(phase, Math.addExact(phaseNanos.get(phase), nanos));
    }

    public Map<String, Long> getPhaseNanos() { return Collections.unmodifiableMap(phaseNanos); }
    public NativeGenerationTiming getGenerationTiming() { return generationTiming; }
    public NativeMemoryDiagnostics getMemoryDiagnostics() { return memoryDiagnostics; }
    public void setMemoryDiagnostics(NativeMemoryDiagnostics diagnostics) { memoryDiagnostics = diagnostics; }
    public CompactState getProduct() { return product; }
    public void setProduct(CompactState product) { this.product = product; }
    public Map<Integer, int[]> getProductToComponentStates() { return productToComponentStates; }
    /** Takes ownership of Analyser's detached correspondence copy. */
    public void setProductToComponentStates(Map<Integer, int[]> mapping) {
        productToComponentStates = Collections.unmodifiableMap(mapping);
    }
    public MTS<S, A> getPlant() { return plant; }
    public void setPlant(MTS<S, A> plant) { this.plant = plant; }
    public GRGame<S> getGame() { return game; }
    public void setGame(GRGame<S> game) { this.game = game; }
    public Set<S> getWinningStates() { return winningStates; }

    /** Takes ownership of a newly collected, complete winning-state set. */
    public void setWinningStates(Set<S> winningStates) {
        this.winningStates = Collections.unmodifiableSet(winningStates);
    }

    public MTS<StrategyState<S, Integer>, A> getStrategyController() { return strategyController; }
    public void setStrategyController(MTS<StrategyState<S, Integer>, A> controller) {
        this.strategyController = controller;
    }
    public MTS<Long, String> getPlainController() { return plainController; }
    public void setPlainController(MTS<Long, String> controller) { this.plainController = controller; }
    public CompactState getController() { return controller; }
    public void setController(CompactState controller) { this.controller = controller; }
    public CompactState getFinalController() { return finalController; }
    public void setFinalController(CompactState controller) { this.finalController = controller; }
    public Map<StrategyState<S, Integer>, Long> getStrategyToPlainStates() { return strategyToPlainStates; }
    public void setStrategyToPlainStates(Map<StrategyState<S, Integer>, Long> mapping) {
        strategyToPlainStates = Collections.unmodifiableMap(new HashMap<>(mapping));
    }
    public Map<Long, Long> getPlainToCompactStates() { return plainToCompactStates; }
    public void setPlainToCompactStates(Map<Long, Long> mapping) {
        plainToCompactStates = Collections.unmodifiableMap(new HashMap<>(mapping));
    }
    public Map<Long, Long> getPlantToProductStates() { return plantToProductStates; }
    public void setPlantToProductStates(Map<Long, Long> mapping) {
        plantToProductStates = Collections.unmodifiableMap(new HashMap<>(mapping));
    }
}
