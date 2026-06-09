package ltsa.updatingControllers.structures;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.control.ControllerGoalDefinition;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.Symbol;
import ltsa.lts.util.MTSUtils;

import java.util.Set;
import java.util.Vector;
import java.util.List;
import java.util.Map;

public class UpdatingControllerCompositeState extends CompositeState {

	private CompositeState oldController;
	private CompositeState mapping;
	private ControllerGoalDefinition updateSafetyGoals;
	private ControllerGoal<String> updateGRGoal;
	private Set<String> controllableActions;
	private MTS<Long, String> updateEnvironment;

	//OTF用
	private CompositeState newController;
	private boolean isOTF;
	private boolean fineGrained;
	private UpdateProtocolSpec updateProtocolSpec;
	// ★追加: Mappingを構成するLTSのリスト (OTF探索で利用)
    private Vector<CompactState> mappingComponents;
	// ★追加: Safety & Transition Constraints
    private Vector<CompactState> oldSafetyLTSs;
    private Vector<CompactState> newSafetyLTSs;
    // // ★変更: LTS(Vector<CompactState>) ではなく、定義シンボル(List<Symbol>)として保持
	// //transitionReqは単体のLTSにするとバグることが判明したので，Formulaのまま渡してOTFもFormulaのままチェックする
    // private List<Symbol> transitionGoals;

	// ★変更: List<Symbol> から Vector<CompactState> へ変更
	private Vector<CompactState> transitionRequirements;

	// ★追加: New Environmentを構成するコンポーネント群
    private Vector<CompactState> newEnvironmentComponents;

	private List<Map<Integer, Integer>> mappingMapEnvToNewEnv;

	// ▼▼▼ 追加: New Safety用のFluentを保持するフィールド ▼▼▼
    private Vector<CompactState> synthesisMachines;

	// ★追加: Safetyプロパティと構成要素(Monitor+Fluents)の対応マップ
    private Map<CompactState, List<CompactState>> safetyComponentsMap;
    
    // ★追加: Safetyプロパティの状態追跡マップ (Look-up Table)
    private Map<CompactState, Map<List<Integer>, Integer>> safetyStateMapping;

	public UpdatingControllerCompositeState(CompositeState oldController, CompositeState mapping,
											ControllerGoalDefinition safetyGoals, ControllerGoal<String> updateGRGoal, String name) {
		this(oldController, mapping, safetyGoals, updateGRGoal, name, false, null);
	}

	public UpdatingControllerCompositeState(CompositeState oldController, CompositeState mapping,
											ControllerGoalDefinition safetyGoals, ControllerGoal<String> updateGRGoal,
											String name, boolean fineGrained, UpdateProtocolSpec updateProtocolSpec) {
		super.setMachines(new Vector<CompactState>());
		this.oldController = oldController;
		this.mapping = mapping;
		this.updateSafetyGoals = safetyGoals;
		this.updateGRGoal = updateGRGoal;
		this.controllableActions = this.updateGRGoal.getControllableActions();

		super.setCompositionType(Symbol.UPDATING_CONTROLLER);
		super.name = name;

		//明示的な初期化
		this.newController = null;
		this.isOTF = false;
		this.fineGrained = fineGrained;
		this.updateProtocolSpec = updateProtocolSpec;
		this.mappingComponents = null;
	}

	//OTF用
	public UpdatingControllerCompositeState(CompositeState oldController, CompositeState newController,
											Vector<CompactState> mappingComponents, Vector<CompactState> newEnvironmentComponents,
											List<Map<Integer, Integer>> mappingMapEnvToNewEnv,
											Vector<CompactState> oldSafetyLTSs, Vector<CompactState> newSafetyLTSs,
											// List<Symbol> transitionGoals,
											Vector<CompactState> transitionRequirements,
											Vector<CompactState> synthesisMachines, // Monitor + Fluents
											Map<CompactState, List<CompactState>> safetyComponentsMap,
											Map<CompactState, Map<List<Integer>, Integer>> safetyStateMapping,
											UpdateProtocolSpec updateProtocolSpec,
											Set<String> controllableActions,
											boolean isOTF, boolean fineGrained, String name) {
		super.setMachines(new Vector<CompactState>());
		this.oldController = oldController;
		// OTFモードではこれらはnullにしておく（またはダミー）
		this.mapping = null;
		this.updateSafetyGoals = null; 
        this.updateGRGoal = null;

		this.controllableActions = controllableActions;

		super.setCompositionType(Symbol.UPDATING_CONTROLLER);
		super.name = name;

		//追加フィールド
		this.newController = newController;
		this.isOTF = isOTF;
		this.fineGrained = fineGrained;
		this.updateProtocolSpec = updateProtocolSpec;
		this.mappingComponents = mappingComponents;
		this.newEnvironmentComponents = newEnvironmentComponents;
		this.mappingMapEnvToNewEnv = mappingMapEnvToNewEnv;
		this.oldSafetyLTSs = oldSafetyLTSs;
        this.newSafetyLTSs = newSafetyLTSs;
        // this.transitionGoals = transitionGoals;
		// ★変更: CompactStateのリストとして保存
        this.transitionRequirements = transitionRequirements;

		// ▼▼▼ 追加: フィールドへの代入 ▼▼▼
        this.synthesisMachines = synthesisMachines;
		this.safetyComponentsMap = safetyComponentsMap;
		this.safetyStateMapping = safetyStateMapping;

		// //デバッグ用
		// ★修正: 可視化(Drawタブ)のために、全ての構成要素をmachinesリストにまとめる
        Vector<CompactState> allMachines = new Vector<>();
        if (mappingComponents != null) allMachines.addAll(mappingComponents);
		if (newEnvironmentComponents != null) allMachines.addAll(newEnvironmentComponents);
        if (oldSafetyLTSs != null) allMachines.addAll(oldSafetyLTSs);
        if (newSafetyLTSs != null) allMachines.addAll(newSafetyLTSs);

		// ★追加
        if (transitionRequirements != null) allMachines.addAll(transitionRequirements);

		if (synthesisMachines != null) allMachines.addAll(synthesisMachines);
        
        super.setMachines(allMachines);
	}

	public MTS<Long, String> getUpdateController() {
		return MTSUtils.getMTSComposition(this);
	}

	public MTS<Long, String> getOldController() {
		return MTSUtils.getMTSComposition(oldController);
	}

	public MTS<Long, String> getMapping() {
		if (mapping == null) return null;
		return MTSUtils.getMTSComposition(mapping);
	}

	public Set<String> getControllableActions() {
		return controllableActions;
	}

	public ControllerGoalDefinition getUpdateSafetyGoals() {
		return updateSafetyGoals;
	}

	public ControllerGoal<String> getUpdateGRGoal() {
		return updateGRGoal;
	}

	public boolean isShowGRGameInDraw() {
		return Boolean.getBoolean("updating.controller.draw.grGame");
	}

	public void setUpdateEnvironment(MTS<Long, String> updateEnvironment) {
		this.updateEnvironment = updateEnvironment;
	}

	// ★追加: newControllerの取得
    public MTS<Long, String> getNewController()
	{
        if (newController == null) return null;
        return MTSUtils.getMTSComposition(newController);
    }

	// OTF: Mapping Components
    public Vector<CompactState> getMappingComponents() {
        return mappingComponents;
    }

	public Vector<CompactState> getOldSafetyLTSs() { return oldSafetyLTSs; }
    public Vector<CompactState> getNewSafetyLTSs() { return newSafetyLTSs; }
    // public List<Symbol> getTransitionGoals() { return transitionGoals; }
	// ★変更: ゲッターの戻り値も変更
    public Vector<CompactState> getTransitionRequirements() {
        return transitionRequirements;
    }

	// ★追加: OTFフラグの確認
    public boolean isOTF()
	{
        return isOTF;
    }

	public boolean isFineGrained()
	{
		return fineGrained;
	}

	public UpdateProtocolSpec getUpdateProtocolSpec()
	{
		return updateProtocolSpec;
	}

	public void setNewEnvironmentComponents(Vector<CompactState> components)
	{
        this.newEnvironmentComponents = components;
    }

    public List<CompactState> getNewEnvironmentComponents()
	{
        return newEnvironmentComponents;
    }

	public List<Map<Integer, Integer>> getMappingMapEnvToNewEnv()
	{
		return mappingMapEnvToNewEnv;
	}

	// ▼▼▼ 追加: ゲッター ▼▼▼
    public Vector<CompactState> getSynthesisMachines() {
        return synthesisMachines;
    }

	// ★追加: 安全性コンポーネントマップのゲッター
    public Map<CompactState, List<CompactState>> getSafetyComponentsMap() {
        return safetyComponentsMap;
    }

    // ★追加: 状態追跡マップのゲッター
    public Map<CompactState, Map<List<Integer>, Integer>> getSafetyStateMapping() {
        return safetyStateMapping;
    }

	@Override
	public UpdatingControllerCompositeState clone() {
		UpdatingControllerCompositeState clone;

		// ★修正: OTFモードかどうかでコンストラクタを使い分ける
        if (this.isOTF) {
            clone = new UpdatingControllerCompositeState(oldController, newController, mappingComponents, newEnvironmentComponents,
														mappingMapEnvToNewEnv,
														oldSafetyLTSs, newSafetyLTSs,
														// transitionGoals,
														transitionRequirements,
														synthesisMachines,
														safetyComponentsMap,
														safetyStateMapping,
														updateProtocolSpec,
														controllableActions,
														isOTF, fineGrained, name);
		} else {
			clone = new UpdatingControllerCompositeState(oldController, mapping, updateSafetyGoals,
						updateGRGoal, name, fineGrained, updateProtocolSpec);
		}
		clone.setCompositionType(getCompositionType());
		clone.makeAbstract = makeAbstract;
		clone.makeClousure = makeClousure;
		clone.makeCompose = makeCompose;
		clone.makeDeterministic = makeDeterministic;
		clone.makeMinimal = makeMinimal;
		clone.makeControlStack = makeControlStack;
		clone.makeOptimistic = makeOptimistic;
		clone.makePessimistic = makePessimistic;
		clone.makeController = makeController;
		clone.setMakeComponent(isMakeComponent());
		clone.setComponentAlphabet(getComponentAlphabet());
		clone.goal = goal;
		clone.controlStackEnvironments = controlStackEnvironments;
		clone.controlStackSpecificTier = controlStackSpecificTier;
		clone.isProbabilistic = isProbabilistic;
		return clone;
	}

}
