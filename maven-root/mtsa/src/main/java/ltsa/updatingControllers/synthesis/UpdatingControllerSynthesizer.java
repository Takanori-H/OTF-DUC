package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
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
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisDUC;
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

		// set environment
		MTS<Long, String> oldC = uccs.getOldController();

        if(uccs.isOTF())
        {
            // ★確認用ログ出力
            // --- OTF-DUC (提案手法) の実行 ---
            output.outln("=========================================");
            output.outln(" OTF Mode Verification");
            output.outln("=========================================");

            output.outln("Mode: On-The-Fly Updating Controller Synthesis");

            long tStart = System.currentTimeMillis();

            // OTF-DUCの実行メインロジック呼び出し
            generateDUC(uccs, output);

            long tEnd = System.currentTimeMillis();
            long tDelta = tEnd - tStart;
            output.outln("DCU built in: " + tDelta +"ms");
        }
        else
        {
            // --- 従来手法 (DUCS) の実行 ---
            // 環境モデル全体(UpdatingEnvironment)を構築してから合成を行う
            MTS<Long, String> mapping = uccs.getMapping();

            UpdatingEnvironmentGenerator updEnvGenerator = new UpdatingEnvironmentGenerator(oldC, mapping);
		    updEnvGenerator.generateEnvironment();

            long tStart = System.currentTimeMillis();

            solveControlProblem(uccs, updEnvGenerator.getUpdEnv(), output);

            long tEnd = System.currentTimeMillis();
            long tDelta = tEnd - tStart;
            output.outln("DCU built in: " + tDelta +"ms");
        }

	}

	private static void solveControlProblem(
            UpdatingControllerCompositeState uccs, UpdatingEnvironment updEnv, LTSOutput output) {

        MTS<Long, String> E_u = ControllerUtils.UpdateEnvironment2MTS(updEnv);
        Pair<List<Formula>,Set<Fluent>> safetyFormulasAndFluents = getSafetyFormulas(uccs.getUpdateSafetyGoals(), output); // plain safety(G_u)
        List<Formula> safetyFormulas = safetyFormulasAndFluents.getFirst();
        Set<Fluent> goalFluents = safetyFormulasAndFluents.getSecond();

        fillTerminatingActions(E_u.getActions(), goalFluents); // set the action events fluents terminating with any action

        MTS<Long, String> metaEnvironment = ControllerUtils.removeTopStates(E_u, goalFluents);

		output.outln("Environment states:"+ metaEnvironment.getStates().size());
        output.outln("Solving safety goals for the controller synthesis");

        MTS<Long, String> safetyEnv = UpdatingControllerSafetySynthesizer.synthesizeSafety(metaEnvironment, goalFluents, safetyFormulas, uccs.getUpdateGRGoal().getControllableActions());

        output.outln("Environment states after safety: "+ safetyEnv.getStates().size());

        uccs.setUpdateEnvironment(safetyEnv);

        CompactState compactSafetyEnv = MTSToAutomataConverter.getInstance().convert(safetyEnv, "E_u||G(safety)", false, true);
//		CompactState compactMetaEnv = MTSToAutomataConverter.getInstance().convert(metaEnvironment, "meta E_u", false);
//		CompactState compactEnv = MTSToAutomataConverter.getInstance().convert(E_u, "E_u", false);

        Vector<CompactState> machines = new Vector<CompactState>();
        machines.add(compactSafetyEnv);
//		machines.add(compactMetaEnv);
//		machines.add(compactEnv);

        uccs.setMachines(machines);

        UpdatingControllerGRSynthesizer.synthesizeGR(compactSafetyEnv, uccs, safetyEnv, output);

//        if (uccs.getComposition() == null){
//            output.outln("Running in debug mode");
//            machines.clear();
//            machines.add(compactEnv); // 0
//            uccs.setComposition(machines.get(0));
//
//        }

        UpdatingControllersUtils.ACTION_FLUENTS_FOR_UPDATE.clear();

	}

    /**
     *  get the list of safety formulas from old and new and the set of propositions used in every formula
     *
     * @param newGoalDef
     * @param output
     * @return
     */
    private static Pair<List<Formula>, Set<Fluent>> getSafetyFormulas(ControllerGoalDefinition newGoalDef, LTSOutput output) {
        Set<Fluent> safetyFluents = new HashSet<Fluent>();
        List<Formula> safetyFormulas = new ArrayList<Formula>();
        for (Symbol safetyDefinition : newGoalDef.getSafetyDefinitions()) {

            output.outln("Processing formula for update: " + safetyDefinition.getName());
            AssertDefinition def = AssertDefinition.getConstraint(safetyDefinition.getName());

            if (def != null) {
                safetyFormulas.add(FormulaUtils.adaptFormulaAndCreateFluents(def.getFormula(false), safetyFluents));

            } else {
                Diagnostics.fatal("Assertion not defined ["	+ safetyDefinition.getName() + "].");
            }
        }
        return new Pair<List<Formula>,Set<Fluent>>(safetyFormulas,safetyFluents);
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
        long tStart = System.currentTimeMillis();
        output.outln("Starting On-The-Fly Controller Synthesis (Box List & Mapping Table Strategy)...");

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

        // --- A. Marking LTS (Goal & Process Management) ---
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
        allActions.add(UpdateConstants.STOP_OLD_SPEC);
        allActions.add(UpdateConstants.RECONFIGURE);
        allActions.add(UpdateConstants.START_NEW_SPEC);
        allActions.add(UpdateConstants.FINISH_UPDATE);
        // 内部遷移(tau)は除外
        allActions.remove("tau");
        
        // 10状態の Marking LTS を作成
        // 0:Pre ->(beginUpdate)-> 1-8:In ->(finishUpdate)-> 9:Post
        // OTF用の進捗管理Marking LTSを作成 (createOTFMarkingLTS使用)
        MTS<Long, String> markingMTS = createOTFMarkingLTS(
            UpdateConstants.BEGIN_UPDATE, 
            UpdateConstants.FINISH_UPDATE, 
            allActions
        );
        
        // ゴール設定: 状態9のみをMarkedとする
        LTS<Long, String> markingLTS = new LTSAdapter<>(markingMTS, TransitionType.REQUIRED);
        MarkedLTSAdapter<Long, String> markedMarkingLTS = new MarkedLTSAdapter<>(markingLTS);
        for(long i = 0; i <= 8; i++) markedMarkingLTS.unmark(i);
        markedMarkingLTS.mark(9L);

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
            for(CompactState cs : uccs.getMappingComponents())
            {
                boxList.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
            }
        }
        mappingEndIndex = boxList.size() - 1;

        // --- E. Old Safety ---
        oldSafeStartIndex = boxList.size();
        if (uccs.getOldSafetyLTSs() != null)
        {
            for (CompactState cs : uccs.getOldSafetyLTSs())
            {
                boxList.add(new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED));
            }
        }
        oldSafeEndIndex = boxList.size() - 1;

        // --- F. New Safety ---
        // StateMapper用には、元の定義(LTSAdapter)を別途リスト化して保持しておく必要がある
        newSafeStartIndex = boxList.size();
        List<LTS<Long, String>> stateMapperSafetyAdapters = new ArrayList<>(); // Mapper用(非Synched)

        // ★追加: CompactState -> LTS<Long, String> の変換対応を保持するマップ
        // これを使って safetyComponentsMap 等の CompactState を boxList 内の実体(LTS)に紐付ける
        Map<CompactState, LTS<Long, String>> compactToLtsMap = new HashMap<>();
        
        //デバッグ用
        output.outln("Adding New Safety Properties to BoxList:"); // ★見出し追加

        if (uccs.getNewSafetyLTSs() != null)
        {
            for (CompactState cs : uccs.getNewSafetyLTSs())
            {
                // BoxList用
                LTS<Long, String> originalForBox = new LTSAdapter<>(converter.convert(cs), TransitionType.REQUIRED);
                boxList.add(originalForBox);
                
                // Mapper用(NCとの接続計算用)
                stateMapperSafetyAdapters.add(originalForBox);

                // ★追加: 変換マップに登録 (Original Safety)
                compactToLtsMap.put(cs, originalForBox);

                // ★追加: 追加したインデックスと名前を表示
                int currentIndex = boxList.size() - 1;
                output.outln("  [Index " + currentIndex + "] " + cs.name);
            }
        }
        newSafeEndIndex = boxList.size() - 1;

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

        // --- H. Synthesis Machines (Monitors + Fluents) ---
        // ★追加: Transition Requirements の後に追加する

        //デバッグ用
        output.outln("Adding Synthesis Machines (Monitors & Fluents) to BoxList:"); // ★見出し追加

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
                int currentIndex = boxList.size() - 1;
                output.outln("  [Index " + currentIndex + "] " + cs.name);
            }
        }
        synthesisEndIndex = boxList.size() - 1;

        output.outln(" - Synthesis Machines added at indices: " + synthesisStartIndex + " to " + synthesisEndIndex);

        output.outln("Box List Created. Total Components: " + boxList.size());

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

        // ▼▼▼ ここにデバッグ出力を追加 ▼▼▼
        output.outln("\n========== DEBUG: NC Connection Map Signatures ==========");
        for (String sig : newControllerConnectionMap.keySet()) {
            output.outln("NC Map Key: " + sig);
        }
        output.outln("=========================================================\n");
        // ▲▲▲ 追加ここまで ▲▲▲

        output.outln(" - State Mapper generated " + newControllerConnectionMap.size() + " mapping entries.");

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

        // ▼▼▼▼▼▼▼▼▼▼▼▼ デバッグ表示 (Integer Key 確認用) ▼▼▼▼▼▼▼▼▼▼▼▼
        output.outln("\n========== DEBUG: Index-based Safety Map Verification ==========");
        for (Map.Entry<Integer, List<Integer>> entry : safetyComponentIndicesMap.entrySet()) {
            int safetyIdx = entry.getKey();
            List<Integer> compIndices = entry.getValue();
            
            // boxListからLTSを取り出して確認（デバッグ用）
            // ※実際にはLTSAdapterなので名前は取れないかもしれないが、クラス名などで確認
            output.outln("Safety Property [Index " + safetyIdx + "]:");
            
            for (int i = 0; i < compIndices.size(); i++) {
                int idx = compIndices.get(i);
                String type = (i == 0) ? "Monitor" : "Fluent ";
                output.outln("     [" + i + "] " + type + " => boxList Index: " + idx);
            }
            
            // Lookup Map の確認
            Map<List<Integer>, Integer> lookup = safetyStateLookupMap.get(safetyIdx);
            output.outln("     -> Lookup Table Size: " + (lookup != null ? lookup.size() : "null"));
        }
        output.outln("========== END VERIFICATION ==========\n");
        // ▲▲▲▲▲▲▲▲▲▲▲▲ 追加終了 ▲▲▲▲▲▲▲▲▲▲▲▲

        long tEnd = System.currentTimeMillis();
        long tDelta = tEnd - tStart;
        output.outln("OTF-DUC前処理: " + tDelta +"ms");

        output.outln("Initializing DCS...");

        DirectedControllerSynthesisDUC<Long, String> ducSynthesis = new DirectedControllerSynthesisDUC<>();

        LTS<Long, String> result = ducSynthesis.synthesizeDUC(
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

        if (result != null) {
            output.outln("DUC Generated Successfully! States: " + result.getStates().size());
            CompactState res = MTSToAutomataConverter.getInstance().convert(new MTSAdapter<Long, String>(result), uccs.getName(), false);
            res.reachable();
            uccs.setComposition(res);
        } else {
            output.outln("Failed to generate DUC (Goal not reachable).");
        }
    }

    /**
     * OTF探索用の進捗管理機能付き Marking LTS を作成する。
     * 4つの更新事象が全て完了するまで finishUpdate を許可しないロジックをLTS構造として埋め込む。
     * * ■ 状態定義 (States):
     * - State 0: Pre-Update (beginUpdate 前)
     * - State 1..8: In-Update (更新中。3つの事象の完了状況をビットマスクで管理)
     * - Base Offset = 1
     * - State ID = 1 + mask (mask: 0..7)
     * - Bit 0 (1): stopOldSpec 完了
     * - Bit 1 (2): reconfigure 完了
     * - Bit 2 (4): startNewSpec 完了
     * - State 9: Post-Update (Goal。finishUpdate 後)
     * * @param startAction 更新開始アクション (例: beginUpdate)
     * @param endAction   更新終了アクション (例: finishUpdate)
     * @param alphabet    システム全体のアルファベット集合
     * @return 進捗管理ロジックを含むMTS
     */
    /**
     * OTF探索用の進捗管理機能付き Marking LTS を作成する。
     * (修正版: 完了済みの更新アクションはブロックし、自己ループさせない)
     */
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
        fullAlphabet.add(startAction);       // beginUpdate
        fullAlphabet.add(endAction);         // finishUpdate
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
}
