package ltsa.lts;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.controller.model.ControllerGoal;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.control.ControllerGoalDefinition;
import ltsa.control.util.GoalDefToControllerGoal;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.lts.ltl.FormulaFactory;
import ltsa.lts.ltl.FormulaSyntax;
import ltsa.lts.chart.util.FormulaUtils;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.structures.UpdateActionKind;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
import ltsa.updatingControllers.synthesis.FineGrainedUpdatingControllersUtils;
import ltsa.updatingControllers.synthesis.UpdatingControllersUtils;

import java.util.*;

public class UpdatingControllersDefinition extends CompositionExpression {
    private Symbol oldController;
    private Symbol mapping;
    private Symbol oldGoal;
    private Symbol newGoal;
    private List<Symbol> transitionGoals;
    private Boolean nonblocking;

    // ★追加: OTF用
    private boolean isOTF;
    private boolean fineGrained;
    private boolean selectiveFineGrained;
    private Symbol newController;
    // ▼▼▼ 追加: 新しい構文用のリストフィールド ▼▼▼
    private List<Symbol> oldEnvironmentList;
    private List<Symbol> newEnvironmentList;
    private List<Symbol> mapRelationList;

    // ▲▲▲ 追加ここまで ▲▲▲
    public UpdatingControllersDefinition(Symbol current, LTSOutput output) {
        super();
        super.setName(current);
        oldController = new Symbol();
        mapping = new Symbol();
        transitionGoals = new ArrayList<Symbol>();
        nonblocking = false;

        // OTFで追加
        newController = new Symbol();
        isOTF = false;
        fineGrained = false;
        selectiveFineGrained = false;
        this.output = output;

        // ▼▼▼ 追加: 初期化 ▼▼▼
        oldEnvironmentList = new ArrayList<>();
        newEnvironmentList = new ArrayList<>();
        mapRelationList = new ArrayList<>();
        // ▲▲▲ 追加ここまで ▲▲▲
    }

    public Set<String> generateUpdatingControllableActions(ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef) {
        Set<String> oldControllableActions = compileSet(oldGoalDef.getControllableActionSet());
        Set<String> newControllableActions = compileSet(newGoalDef.getControllableActionSet());
        Set<String> controllable = new HashSet<String>();
        controllable.addAll(oldControllableActions);
        controllable.addAll(newControllableActions);
        controllable.add(UpdateConstants.STOP_OLD_SPEC);
        controllable.add(UpdateConstants.START_NEW_SPEC);
        controllable.add(UpdateConstants.RECONFIGURE);
        return controllable;
    }

    @Override
    protected CompositeState compose(Vector<Value> actuals) {
        //評価実験用：UpdatingControllerDefinition.compose計測開始
        long UCDefStart = System.currentTimeMillis();
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "UpdatingControllersDefinition", "compose の全体実行時間");
        UpdatingControllerEvaluationRecorder.beginCountScope(
                "UpdatingControllersDefinition", "compose の全体実行時間");

        // ---------------------------------------------------------
        // 1. Old Controller のコンパイル (Monolithic)
        // ---------------------------------------------------------
        //評価実験用:Old Controllerの計測開始
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "UpdatingControllersDefinition", "Old Controller 合成時間");
        long oldCStart = System.currentTimeMillis();

        CompositeState oldC = composeLTS(this.getOldController().toString());

        //評価実験用：Old Controllerの計測終了
        long oldCTime = System.currentTimeMillis() - oldCStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "UpdatingControllersDefinition", "Old Controller 合成時間");
        if (UpdatingControllerEvaluationRecorder.isEnabled() && oldC.composition != null) {
            long oldCCountStart = System.currentTimeMillis();
            int oldControllerStates = oldC.composition.maxStates;
            int oldControllerTransitions = oldC.composition.ntransitions();
            long oldCCountTime = System.currentTimeMillis() - oldCCountStart;
            UpdatingControllerEvaluationRecorder.recordOldControllerStateSpace(
                    oldControllerStates,
                    oldControllerTransitions,
                    oldCCountTime);
        }
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Old Controller 合成後");

        // ---------------------------------------------------------
        // 2. Goal 定義の準備
        // ---------------------------------------------------------
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間");
        long goalDefStart = System.currentTimeMillis();
        ControllerGoalDefinition oldGoalDef = ControllerGoalDefinition.getDefinition(this.getOldGoal());
        ControllerGoalDefinition newGoalDef = ControllerGoalDefinition.getDefinition(this.getNewGoal());
        if (fineGrained && selectiveFineGrained) {
            Diagnostics.fatal("fine_grained and selective_fine_grained cannot be used together.");
        }
        boolean fineGrainedMode = fineGrained || selectiveFineGrained;

        // 全体のControllable Action (OTF探索用)
        Set<String> controllableSet = this.generateUpdatingControllableActions(oldGoalDef, newGoalDef);
        UpdateProtocolSpec updateProtocolSpec = fineGrainedMode
                ? UpdateProtocolSpec.forFineGrained(oldGoalDef, newGoalDef)
                : null;
        long goalDefTime = System.currentTimeMillis() - goalDefStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間");

        // ---------------------------------------------------------
        // 3. Mapping Environment の生成
        // ---------------------------------------------------------
        
        //評価実験：Mapping Environment Component計測開始
        UpdatingControllerEvaluationRecorder.beginFailureTimer(
                "UpdatingControllersDefinition", "Mapping Environment Component 合成時間");
        long mapEComponentStart = System.currentTimeMillis();

        // LTSCompilerのstaticメソッドから参照を取得
        Hashtable<String, RelationDefinition> relations = LTSCompiler.getRelations();
        Hashtable<String, ProcessSpec> processes = LTSCompiler.getProcesses();
        Vector<CompactState> mappingComponents = new Vector<>();

        // ★追加: MappingEnv -> NewEnv の状態ID対応マップを保持するリスト
        List<Map<Integer, Integer>> mappingMapEnvToNewEnv = new ArrayList<>();

        // ケースA: 従来の 'mapping = MapEnv' 指定がある場合
        // ★修正: Symbol.UNKNOWN ではなく、かつ "unknown" 文字列でないことを確認
        // mappingが未指定の場合、kindはUNKNOWN、toString()は"unknown"となるため
        if (this.getMapping() != null && !this.getMapping().toString().equals("unknown")
                && !this.getMapping().toString().isEmpty()) {
            if (fineGrainedMode) {
                Diagnostics.fatal("fine_grained/selective_fine_grained mode requires oldEnvironment/newEnvironment/mapRelation lists, not mapping = ...");
            }
            String mappingName = this.getMapping().toString();
            mappingComponents = getComponentsWithoutComposition(mappingName);
            output.outln(" - Mapping components loaded from composite '" + mappingName + "': "
                    + mappingComponents.size() + " machines.");
        }
        // ケースB: 新しいリスト形式指定 (oldEnv, newEnv, mapRel) がある場合
        else if (oldEnvironmentList != null && !oldEnvironmentList.isEmpty() &&
                newEnvironmentList != null && !newEnvironmentList.isEmpty() &&
                mapRelationList != null && !mapRelationList.isEmpty()) {
            // サイズ整合性チェック
            if (oldEnvironmentList.size() != newEnvironmentList.size()
                    || oldEnvironmentList.size() != mapRelationList.size()) {
                Diagnostics.fatal("Size mismatch in Updating Controller definition: oldEnvironment, newEnvironment, and mapRelation must have the same number of elements.");
            }

            output.outln(" - Synthesizing Mapping Environment from lists (size=" + oldEnvironmentList.size() + ")...");

            MappingEnvironmentGenerator generator = new MappingEnvironmentGenerator();
            Hashtable<String, CompactState> compiledProcesses = LTSCompiler.getCompiled();

            for (int i = 0; i < oldEnvironmentList.size(); i++) {
                Symbol oldSym = oldEnvironmentList.get(i);
                Symbol newSym = newEnvironmentList.get(i);
                Symbol relSym = mapRelationList.get(i);
                String oldName = oldSym.toString();
                String newName = newSym.toString();

                // ★メソッド引数に processes を渡す
                ensureCompiled(oldName, processes);
                ensureCompiled(newName, processes);

                String mapEnvName = "MAP_" + oldName + "_" + newName;
                MapDefinition mapDef = new MapDefinition(new Symbol(Symbol.UPPERIDENT, mapEnvName));
                mapDef.oldProcess = oldSym;
                mapDef.newProcess = newSym;
                // mapDef.relationName = relSym;
                // ▼▼▼ 修正: リレーション名と引数を分離して MapDefinition に登録する ▼▼▼
                String relNameStr = relSym.toString();
                if (relNameStr.contains("(")) {
                    String baseName = relNameStr.substring(0, relNameStr.indexOf('(')).trim();
                    String argStr = relNameStr.substring(relNameStr.indexOf('(') + 1, relNameStr.lastIndexOf(')')).trim();
                    mapDef.relationName = new Symbol(Symbol.UPPERIDENT, baseName);
                    mapDef.relationArg = argStr;
                } else {
                    mapDef.relationName = relSym;
                }
                // ▲▲▲ 修正ここまで ▲▲▲

                // relations を渡す
                CompactState mapComp = generator.generate(mapDef, compiledProcesses, relations, this.output,
                        updateProtocolSpec, i);

                if (mapComp != null) {
                    mapComp.name = mapEnvName;
                    mappingComponents.add(mapComp);
                    if (!compiledProcesses.containsKey(mapEnvName)) {
                        compiledProcesses.put(mapEnvName, mapComp);
                    }

                    // ★追加: 生成されたマッピング情報をリストに保存 (コピーを作成)
                    Map<Integer, Integer> currentMap = new HashMap<>(generator.getStateMapping());
                    mappingMapEnvToNewEnv.add(currentMap);

                    // // ▼▼▼▼▼▼▼▼▼ デバッグ出力：対応付けの確認 ▼▼▼▼▼▼▼▼▼
                    // output.outln("--------------------------------------------------");
                    // output.outln("DEBUG: Verifying Mapping for " + mapEnvName);

                    // // 1. State Mapping の表示 (Generatorから取得)
                    // output.outln(" [State Mapping (MapEnvID -> NewEnvID)]");
                    // if (currentMap.isEmpty()) {
                    //     output.outln(" (No mapping recorded. Check Generator implementation)");
                    // } else {
                    //     List<Integer> sortedKeys = new ArrayList<>(currentMap.keySet());
                    //     Collections.sort(sortedKeys);
                    //     for (Integer mapStateId : sortedKeys) {
                    //         output.outln(" MapState " + mapStateId + " -> NewEnvState " + currentMap.get(mapStateId));
                    //     }
                    // }

                    // // 2. Mapping Environment の構造表示
                    // output.outln(" [Structure: " + mapEnvName + "]");
                    // printLTSStructure(mapComp, output);

                    // // 3. New Environment の構造表示 (比較用)
                    // if (compiledProcesses.containsKey(newName)) {
                    //     CompactState newEnvComp = compiledProcesses.get(newName);
                    //     output.outln(" [Structure: " + newName + " (Reference)]");
                    //     printLTSStructure(newEnvComp, output);
                    // }
                    // output.outln("--------------------------------------------------");
                    // // ▲▲▲▲▲▲▲▲▲ デバッグ出力ここまで ▲▲▲▲▲▲▲▲▲
                } else {
                    Diagnostics.fatal("Failed to generate mapping component '" + mapEnvName + "'.");
                }
            }
        }
        else {
            Diagnostics.fatal("Mapping environment not defined.");
        }

        //評価実験用：Mapping Environment Component計測終了
        long mapEComponentTime = System.currentTimeMillis() - mapEComponentStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "UpdatingControllersDefinition", "Mapping Environment Component 合成時間");
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Mapping Environment Component 生成後");

        if (fineGrainedMode && updateProtocolSpec != null) {
            Set<String> referencedUpdateActions = collectTransitionRequirementActionReferences();
            validateFineGrainedTransitionRequirementReferences(updateProtocolSpec, referencedUpdateActions);
            if (selectiveFineGrained) {
                UpdateProtocolSpec candidateProtocolSpec = updateProtocolSpec;
                updateProtocolSpec = UpdateProtocolSpec.forSelective(candidateProtocolSpec, referencedUpdateActions);
                relabelSelectiveMappingComponents(mappingComponents, candidateProtocolSpec, updateProtocolSpec);
            }
        }

        long newCTime = 0;
        long mapETime = 0;
        long oldSafetyToTesterTime = 0;
        long newSafetyToTesterTime = 0;
        long transitionRequirementToTesterTime = 0;
        long safetyToTesterTime = 0;
        long newSafetyToFluentTime = 0;
        long goalTime = 0;
        long grGoalTime = 0;
        long safetyGoalTime = 0;

        int mapStateCount = 0;
        int mapTransCount = 0;

        if (fineGrainedMode) {
            controllableSet.remove(UpdateConstants.STOP_OLD_SPEC);
            controllableSet.remove(UpdateConstants.RECONFIGURE);
            controllableSet.remove(UpdateConstants.START_NEW_SPEC);
            controllableSet.addAll(updateProtocolSpec.getProgressActions());
        }

        // ---------------------------------------------------------
        // 4. モード別処理 (OTF / Traditional)
        // ---------------------------------------------------------
        UpdatingControllerCompositeState ucce;
        if (this.isOTF)
        {
            output.outln("Mode: On-The-Fly Updating Controller Synthesis"
                    + (fineGrainedMode
                    ? (selectiveFineGrained ? " (selective fine-grained update events)" : " (fine-grained update events)")
                    : " (legacy update events)"));

            // OTF固有の設定
            // hotSwapIn は従来 DUC の hotSwap と同様に、更新開始を制限しない
            // 事象として扱うため controllable 集合から外す。
            controllableSet.remove(UpdateConstants.BEGIN_UPDATE);
            controllableSet.add(UpdateConstants.FINISH_UPDATE);

            // =========================================================
            // New Controller の内部合成
            // =========================================================

            //評価実験用:New Controllerの計測開始
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "New Controller 合成時間");
            long newCStart = System.currentTimeMillis();

            // (1) New Environment Components の取得
            Vector<CompactState> newEnvComponents = new Vector<>();
            if (newEnvironmentList != null) {
                Hashtable<String, CompactState> compiledProcesses = LTSCompiler.getCompiled();
                for (Symbol sym : newEnvironmentList) {
                    String name = sym.toString();
                    ensureCompiled(name, LTSCompiler.getProcesses());
                    if (compiledProcesses.containsKey(name)) {
                        newEnvComponents.add(compiledProcesses.get(name));
                    } else {
                        Diagnostics.fatal("New environment component not found: " + name);
                    }
                }
            }

            // (2) CompositeState の構築 (Environmentのみ)
            // Safetyは後でGoalから抽出されて追加されるため、ここではEnvのみで初期化
            Vector<CompactState> machinesForNewCtrl = new Vector<>();
            machinesForNewCtrl.addAll(newEnvComponents);
            CompositeState newCtrlComposite = new CompositeState("NEW_CONTROLLER_SYNTHESIZED", machinesForNewCtrl);
            newCtrlComposite.priorityIsLow = true;
            newCtrlComposite.makeController = true; // Synthesisフラグ

            // (3) Goal の設定 (CompositionExpression.buildAndSetGoal のロジック)
            // Safety LTSの生成とマシンの追加、およびControllerGoalオブジェクトの生成を行う
            Collection<CompactState> newSafeCol = CompositionExpression.preProcessSafetyReqs(newGoalDef, output);
            if (newSafeCol != null) {
                newCtrlComposite.machines.addAll(newSafeCol);
            }

            // GoalDefToControllerGoal を使用して正しい ControllerGoal を生成
            newCtrlComposite.goal = GoalDefToControllerGoal.getInstance().buildControllerGoal(newGoalDef);

            // (4) 合成の実行 (TransitionSystemDispatcher.applyComposition のフローを模倣)
            output.outln(" - Synthesizing New Controller from " + newEnvComponents.size() + " env components...");

            // Step 4-1: 並行合成 (Environment || Safety) -> Game Structure
            newCtrlComposite.compose(output);

            // Step 4-2: コントローラ合成 (Solving the Game)
            // applyOperations を呼ぶことで synthesise メソッドが呼ばれ、
            // 成功すれば machines[0] がコントローラに置換され、再合成が行われます。
            try {
                newCtrlComposite.applyOperations(output);
            } catch (Exception e) {
                // e.printStackTrace();
                Diagnostics.fatal("Error during New Controller synthesis operations: " + e.toString());
            }

            CompositeState newC = newCtrlComposite;
            
            if (newC.composition == null) {
                Diagnostics.fatal("Failed to synthesize New Controller (uncontrollable or deadlock).");
            }

            output.outln(" - New Controller synthesized successfully. States: " + newC.composition.maxStates);
            if (UpdatingControllerEvaluationRecorder.isEnabled()) {
                long newCCountStart = System.currentTimeMillis();
                int newControllerStates = newC.composition.maxStates;
                int newControllerTransitions = newC.composition.ntransitions();
                long newCCountTime = System.currentTimeMillis() - newCCountStart;
                UpdatingControllerEvaluationRecorder.recordStateSpace(
                        "入力規模 / 事前合成",
                        "New Controller",
                        newControllerStates,
                        newControllerTransitions,
                        newCCountTime);
            }
            
            //評価実験用:New Controllerの計測終了
            newCTime = System.currentTimeMillis() - newCStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "New Controller 合成時間");
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("New Controller 合成後");

            // // ▼▼▼ デバッグ出力：状態数と遷移数のカウント ▼▼▼
            // int stateCount = newC.composition.maxStates;
            // int transitionCount = 0;

            // // 全状態の遷移リストを走査してカウント
            // for (int i = 0; i < stateCount; i++) {
            //     ltsa.lts.EventState current = newC.composition.states[i];
            //     while (current != null) {
            //         transitionCount++;
            //         current = current.list;
            //     }
            // }

            // output.outln("---------------------------------------------------------");
            // output.outln("DEBUG: New Controller Synthesis Result");
            // output.outln(" - Name: " + newC.composition.name);
            // output.outln(" - States: " + stateCount);
            // output.outln(" - Transitions: " + transitionCount);
            // output.outln(" - Alphabet Size: " + newC.composition.alphabet.length);
            // output.outln("---------------------------------------------------------");
            // // ▲▲▲ 追加ここまで ▲▲▲

            //評価実験用：Safety計測開始
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Safety の tester 変換全体時間");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Old Safety の tester 変換時間");
            long safetyToTesterStart = System.currentTimeMillis();

            // Safety Goals の取得
            Vector<CompactState> oldSafetyLTSs = new Vector<>();
            Collection<CompactState> oldSafeCol = CompositionExpression.preProcessSafetyReqs(oldGoalDef, output);
            if (oldSafeCol != null)
                oldSafetyLTSs.addAll(oldSafeCol);

            oldSafetyToTesterTime = System.currentTimeMillis() - safetyToTesterStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Old Safety の tester 変換時間");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "New Safety の tester 変換時間");
            long newSafetyToMonitorStart = System.currentTimeMillis();

            // New Safety Goals の保持 (Vector変換)
            Vector<CompactState> newSafetyLTSs = new Vector<>();
            if (newSafeCol != null)
                newSafetyLTSs.addAll(newSafeCol);

            newSafetyToTesterTime = System.currentTimeMillis() - newSafetyToMonitorStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "New Safety の tester 変換時間");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Transition Requirement の tester 変換時間");
            long transitionRequirementToTesterStart = System.currentTimeMillis();

            // ★変更: ここで Transition Goals を CompactState に変換する
            // 元の getTransitionGoals() (List<Symbol>) を渡して変換
            Vector<CompactState> transitionLTSs = UpdatingControllersUtils
                    .compileTransitionRequirements(this.getTransitionGoals(), output);
            
            transitionRequirementToTesterTime = System.currentTimeMillis() - transitionRequirementToTesterStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Transition Requirement の tester 変換時間");
            safetyToTesterTime = System.currentTimeMillis() - safetyToTesterStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Safety の tester 変換全体時間");

            // ★追加: ログ出力して確認
            // if (transitionLTSs != null) {
            //     for (CompactState cs : transitionLTSs) {
            //         UpdatingControllersUtils.logCompactState(cs, output);
            //     }
            // }

            // =========================================================
            // Updating Controller 用の Monitor & Action Fluent 生成
            // =========================================================

            //評価実験用：New Safety to Fluent測定開始
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間");
            long newSafetyToFluentStart = System.currentTimeMillis();

            // 全体でユニークなアクションFluentを保持するキャッシュ (ActionName -> CompactState)
            Map<String, CompactState> globalFluentCache = new HashMap<>();

            // Synthesizerに渡すための対応マップ (Original -> {Monitor, Fluents})
            // ★変更1: 構成要素マップ (Monitor + Fluents)
            Map<CompactState, List<CompactState>> safetyComponentsMap = new HashMap<>();

            // ★変更: 状態追跡マップ (State Look-up Table) - キーと値をIntegerに統一
            Map<CompactState, Map<List<Integer>, Integer>> safetyStateMapping = new HashMap<>();

            // 合成（Synthesizer）に渡す全マシンのリスト（モニター + ユニークなFluent）
            Vector<CompactState> synthesisMachines = new Vector<>();
            Set<String> alphaSet = new HashSet<>();

            // (A) Mapping Components のアルファベット (これでドメイン事象は網羅される)
            for (CompactState cs : mappingComponents) {
                if (cs.alphabet != null) {
                    for (String s : cs.alphabet)
                        alphaSet.add(s);
                }
            }

            // (B) 更新プロセス固有のイベントを追加
            // これらをFluentが参照している場合(例: fluent UpdateMode = <hotSwapIn, hotSwapOut>)
            // アルファベットに含まれていないと遷移が生成されないため、明示的に追加します。
            alphaSet.add(UpdateConstants.BEGIN_UPDATE); // "hotSwapIn"
            alphaSet.add(UpdateConstants.FINISH_UPDATE); // "hotSwapOut"
            if (fineGrainedMode && updateProtocolSpec != null) {
                alphaSet.addAll(updateProtocolSpec.getAllUpdateActions());
            } else {
                alphaSet.add(UpdateConstants.STOP_OLD_SPEC); // "stopOldSpec"
                alphaSet.add(UpdateConstants.START_NEW_SPEC);// "startNewSpec"
            }

            // =========================================================
            // アルファベットのクリーニング (?付きを除外)
            // =========================================================
            Set<String> cleanAlphaSet = new HashSet<>();
            for (String action : alphaSet) {
                // 末尾が '?' でないものだけを採用
                if (!action.endsWith("?")) {
                    cleanAlphaSet.add(action);
                }
            }
            cleanAlphaSet.remove("tau");
            // 配列に変換 (これがモニタの最終的なアルファベットになる)
            String[] monitorAlphabet = cleanAlphaSet.toArray(new String[0]);
            if (newSafeCol != null) {
                List<Symbol> newSafetySpecs = newGoalDef.getSafetyDefinitions();
                
                // ▼▼▼ 修正: Collectionのイテレータ順序ズレを防ぐため、名前で引けるMapを作成 ▼▼▼
                Map<String, CompactState> newSafeMap = new HashMap<>();
                for (CompactState cs : newSafeCol) {
                    newSafeMap.put(cs.name, cs);
                }

                output.outln(" - Extracting New Safety Fluents and Building Look-up Tables...");

                for (Symbol sym : newSafetySpecs) {
                    String name = sym.getName();

                    // 名前を使って確実に正しいCompactState(LTS)を取得する
                    CompactState originalSafe = newSafeMap.get(name);

                    // (フェイルセーフ) もしLTSAの内部処理で名前に接頭辞がついていた場合の検索
                    if (originalSafe == null) {
                        for (CompactState cs : newSafeCol) {
                            if (cs.name.endsWith(name)) {
                                originalSafe = cs;
                                break;
                            }
                        }
                    }
                    
                    if (originalSafe == null) {
                        Diagnostics.fatal("Compiled LTS not found for safety property: " + name);
                    }

                    Set<Fluent> propertyFluents = new HashSet<>();

                    AssertDefinition def = AssertDefinition.getDefinition(name);
                    if (def == null) {
                        def = AssertDefinition.getConstraint(name);
                    }

                    if (def != null) {
                        // 1. 構文木(Syntax Tree)を取得
                        FormulaSyntax syntax = def.getLTLFormula();

                        if (syntax != null) {
                            // 2. 時間演算子(Always/Until)を構文レベルで除去
                            // UpdatingControllersGoalsMakerと同じロジックを使用
                            FormulaSyntax strippedSyntax = syntax.removeLeftTemporalOperators();

                            // 3. 一時的なFormulaFactoryを作成して再コンパイル
                            // これにより、時間演算子を含まない単純な論理式(Formula)が生成される
                		    FormulaFactory tempFactory = new FormulaFactory();

                            // パラメータ展開 (init_paramsを取得するために手順1のゲッターが必要)
                		    // もしゲッター追加が不可なら new Hashtable() で代用(パラメータ無しと仮定)
                		    Hashtable params = (def.getInitParams() != null) ? def.getInitParams() : new Hashtable();

                            tempFactory.setFormula(strippedSyntax.expand(tempFactory, new Hashtable(), params));

                            // 4. コンパイル済みFormulaからFluentを抽出
                		    // (これでVisitorはUntilに遭遇しないためエラーにならない)
                		    FormulaUtils.adaptFormulaAndCreateFluents(tempFactory.getFormula(), propertyFluents);
                        }
                    }
                    else {
                        Diagnostics.fatal("Assertion/Property not defined [" + name + "].");
                    }

                    // 順序を固定するためにソート
                    List<Fluent> sortedFluents = new ArrayList<>(propertyFluents);
                    Collections.sort(sortedFluents, new Comparator<Fluent>() {
                        public int compare(Fluent f1, Fluent f2) {
                            return f1.getName().compareTo(f2.getName());
                        }
                    });

                    // 抽出したFluentをLTSに変換しキャッシュ・リストに登録
                    List<CompactState> propertyFluentAutomata = new ArrayList<>();
                    for (Fluent f : sortedFluents) {
                        // ① キャッシュにそのFluent名が存在しない場合のみ中に入る
                        if (!globalFluentCache.containsKey(f.getName())) {
                            CompactState fLts = convertFluentToLTS(f, monitorAlphabet);
                            globalFluentCache.put(f.getName(), fLts);
                            synthesisMachines.add(fLts); // ← ② 新規のときだけここが実行される
                        }
                        // ③ 現在処理中のSafety Property用のリストには、キャッシュから取得して追加する
                        // (新規作成されたものも、既存のものもここを通る)
                        propertyFluentAutomata.add(globalFluentCache.get(f.getName()));
                    }

                    // 元のSafetyプロパティに対応するFluentのLTSリストをマッピング
                    safetyComponentsMap.put(originalSafe, propertyFluentAutomata);

                    // BFSで全状態空間を探索し、状態追跡マップ（Look-up Table）を生成
                    Map<List<Integer>, Integer> stateMap = generateStateMappingBFS(originalSafe, sortedFluents, propertyFluentAutomata, monitorAlphabet);
                    safetyStateMapping.put(originalSafe, stateMap);
                

                    // デバッグ用
                    // /*
                    // output.outln("--------------------------");
                    // output.outln(originalSafe.name + " Mapped");
                    // for(Fluent fluent : sortedFluents){
                    //     output.outln(fluent.getName());
                    // }
                    //  */

                    // =========================================================
                    // Debug: State Mapping Visualization
                    // =========================================================
                    // // ▼▼▼ デバッグ出力処理の追加箇所（Monitor廃止版） ▼▼▼
                    // if (output != null) {
                    //     StringBuilder headerBuilder = new StringBuilder();
                    //     headerBuilder.append("    Mapping Table [");
                    //     boolean firstFluent = true;
                    //     for (Fluent f : sortedFluents) {
                    //         if (!firstFluent) {
                    //             headerBuilder.append(", ");
                    //         }
                    //         headerBuilder.append(f.getName());
                    //         firstFluent = false;
                    //     }
                    //     headerBuilder.append("] -> [").append(originalSafe.name).append("]");
                    //     output.outln(headerBuilder.toString());

                    //     // 状態の組み合わせ（List<Integer>）を辞書順にソート
                    //     List<Map.Entry<List<Integer>, Integer>> sortedEntries = new ArrayList<>(stateMap.entrySet());
                    //     Collections.sort(sortedEntries, new Comparator<Map.Entry<List<Integer>, Integer>>() {
                    //         public int compare(Map.Entry<List<Integer>, Integer> e1, Map.Entry<List<Integer>, Integer> e2) {
                    //             List<Integer> k1 = e1.getKey();
                    //             List<Integer> k2 = e2.getKey();
                    //             int size = Math.min(k1.size(), k2.size());
                    //             for (int i = 0; i < size; i++) {
                    //                 int cmp = k1.get(i).compareTo(k2.get(i));
                    //                 if (cmp != 0) return cmp;
                    //             }
                    //             return Integer.compare(k1.size(), k2.size());
                    //         }
                    //     });

                    //     // ソートしたマッピングの出力
                    //     for (Map.Entry<List<Integer>, Integer> entry : sortedEntries) {
                    //         String targetStateStr = (entry.getValue() == ltsa.lts.Declaration.ERROR) ? "ERROR (-1)" : String.valueOf(entry.getValue());
                    //         output.outln("      " + entry.getKey().toString() + " -> " + targetStateStr);
                    //     }
                    //     output.outln("--------------------------------------------------");
                    // }
                    // // ▲▲▲ デバッグ出力処理の追加箇所ここまで ▲▲▲
                }
            }

            //評価実験用：New Safety to Fluent測定終了
            newSafetyToFluentTime = System.currentTimeMillis() - newSafetyToFluentStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間");
            UpdatingControllerEvaluationRecorder.recordCount(
                    "入力規模",
                    "OTF-DUC new safety fluent 数（重複排除後）",
                    globalFluentCache.size(),
                    "個");
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("New Safety Fluent 抽出後");

            ucce = new UpdatingControllerCompositeState(oldC, newC, mappingComponents, newEnvComponents, mappingMapEnvToNewEnv,
                                                        oldSafetyLTSs, newSafetyLTSs,
                                                        // transitionGoals,
                                                        transitionLTSs, synthesisMachines, safetyComponentsMap, safetyStateMapping,
                                                        updateProtocolSpec,
                                                        controllableSet,true, fineGrainedMode, name.getName());
            

        }
        else
        {
            // Traditional Mode
            output.outln("Mode: Traditional Updating Controller Synthesis");

            //評価実験用
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間");
            long goalStart = System.currentTimeMillis();

            ControllerGoal<String> grGoal = fineGrainedMode
                    ? FineGrainedUpdatingControllersUtils.generateGRUpdateGoal(this, oldGoalDef, newGoalDef,
                            controllableSet, updateProtocolSpec)
                    : UpdatingControllersUtils.generateGRUpdateGoal(this, oldGoalDef, newGoalDef,
                            controllableSet);

            grGoalTime = System.currentTimeMillis() - goalStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間");
            long safetyGoalStart = System.currentTimeMillis();

            ControllerGoalDefinition safetyGoal = fineGrainedMode
                    ? FineGrainedUpdatingControllersUtils.generateSafetyGoalDef(this, oldGoalDef,
                            newGoalDef, controllableSet, updateProtocolSpec, output)
                    : UpdatingControllersUtils.generateSafetyGoalDef(this, oldGoalDef,
                            newGoalDef, controllableSet, output);
            
            safetyGoalTime = System.currentTimeMillis() - safetyGoalStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間");
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間");
            long mapEStart = System.currentTimeMillis();

            CompositeState mappingComposite = new CompositeState(mappingComponents);

            mappingComposite.name = "MAPPING_ENV";
            // 合成を実行
            mappingComposite.compose(output);

            mapETime = System.currentTimeMillis() - mapEStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間");
            recordTraditionalMappingEnvironmentStateSpace(mappingComposite);
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("Traditional Mapping Environment 並列合成後");

            // ▼▼▼ 評価実験用: 従来DUCの Mapping Environment ピーク状態数・遷移数 ▼▼▼
            // if (mappingComposite.composition != null) {
            //     mapStateCount = mappingComposite.composition.maxStates;
            //     for (int i = 0; i < mapStateCount; i++) {
            //         ltsa.lts.EventState current = mappingComposite.composition.states[i];
            //         while (current != null) {
            //             mapTransCount++;
            //             current = current.list;
            //         }
            //     }
            // }

        //     // ▼▼▼ 制約5に基づく構造の一致確認用デバッグ表示 ▼▼▼
        // output.outln("========== DEBUG: VERIFYING LTS ISOMORPHISM ==========");
        // for (CompactState cs : mappingComponents) {
        //     if (cs.name.equals("MAP_PRODUCTION_CELL") || cs.name.equals("PRODUCTION_CELL_MAP")) {
        //         output.outln("--- Component: " + cs.name + " ---");
        //         printLTSStructure(cs, output);
        //     }
        // }
        // output.outln("======================================================");
        // // ▲▲▲ デバッグ表示ここまで ▲▲▲

            ucce = new UpdatingControllerCompositeState(oldC, mappingComposite, safetyGoal, grGoal,
                    name.getName(), fineGrainedMode, updateProtocolSpec);
        }

        //評価実験用：UpdatingControllersDefinition.compose測定終了
        UpdatingControllerEvaluationRecorder.endCountScope(
                "UpdatingControllersDefinition", "compose の全体実行時間");
        long UCDefTime = System.currentTimeMillis() - UCDefStart;
        UpdatingControllerEvaluationRecorder.endFailureTimer(
                "UpdatingControllersDefinition", "compose の全体実行時間");
        long inputScaleTime = 0;
        if (UpdatingControllerEvaluationRecorder.isEnabled()) {
            UpdatingControllerEvaluationRecorder.beginFailureTimer(
                    "UpdatingControllersDefinition", "入力規模集計時間");
            long inputScaleStart = System.currentTimeMillis();
            UpdatingControllerEvaluationRecorder.beginCountScope(
                    "UpdatingControllersDefinition", "入力規模集計時間");
            try {
                recordInputScale(oldGoalDef, newGoalDef, controllableSet, mappingComponents, ucce, oldC);
            } finally {
                UpdatingControllerEvaluationRecorder.endCountScope(
                        "UpdatingControllersDefinition", "入力規模集計時間");
            }
            inputScaleTime = System.currentTimeMillis() - inputScaleStart;
            UpdatingControllerEvaluationRecorder.endFailureTimer(
                    "UpdatingControllersDefinition", "入力規模集計時間");
        }
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("UpdatingControllersDefinition compose 後");
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "compose の全体実行時間", UCDefTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Old Controller 合成時間", oldCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間", goalDefTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Mapping Environment Component 合成時間", mapEComponentTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "New Controller 合成時間", newCTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Old Safety の tester 変換時間", oldSafetyToTesterTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "New Safety の tester 変換時間", newSafetyToTesterTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Transition Requirement の tester 変換時間", transitionRequirementToTesterTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Safety の tester 変換全体時間", safetyToTesterTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間", newSafetyToFluentTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間", grGoalTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間", safetyGoalTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間", mapETime);
        UpdatingControllerEvaluationRecorder.recordTime(
                "UpdatingControllersDefinition", "入力規模集計時間", inputScaleTime);

        return ucce;
    }

    private Set<String> collectTransitionRequirementActionReferences() {
        Set<String> references = new HashSet<>();
        for (Symbol transitionGoal : transitionGoals) {
            AssertDefinition definition = AssertDefinition.getConstraint(transitionGoal.getName());
            if (definition == null) {
                definition = AssertDefinition.getDefinition(transitionGoal.getName());
            }
            if (definition == null || definition.getLTLFormula() == null) {
                Diagnostics.fatal("Transition requirement is not defined: " + transitionGoal.getName());
            }
            references.addAll(definition.getLTLFormula().collectActionReferences());
        }
        return references;
    }

    private void validateFineGrainedTransitionRequirementReferences(
            UpdateProtocolSpec candidateProtocolSpec,
            Set<String> referencedActions) {
        Set<UpdateActionKind> legacyKinds = EnumSet.noneOf(UpdateActionKind.class);
        Set<UpdateActionKind> fineGrainedKinds = EnumSet.noneOf(UpdateActionKind.class);

        for (String action : referencedActions) {
            UpdateActionKind legacyKind = legacyUpdateActionKind(action);
            if (legacyKind != null) {
                if (!selectiveFineGrained) {
                    Diagnostics.fatal("Transition requirement in fine_grained mode must not use legacy update action '"
                            + action + "'. Use generated fine-grained action names.");
                }
                legacyKinds.add(legacyKind);
                continue;
            }
            if (!UpdateProtocolSpec.looksLikeFineGrainedUpdateAction(action)
                    && !UpdateProtocolSpec.isOthersActionName(action)) {
                continue;
            }
            if (UpdateProtocolSpec.isOthersActionName(action)) {
                if (!selectiveFineGrained) {
                    Diagnostics.fatal("Transition requirement action '" + action
                            + "' requires selective_fine_grained mode.");
                }
                fineGrainedKinds.add(fineGrainedUpdateActionKind(action));
                continue;
            }
            if (action.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)
                    && !candidateProtocolSpec.getStopOldSpecActions().contains(action)) {
                Diagnostics.fatal("Transition requirement references unknown fine-grained stopOldSpec action: "
                        + action + ". Check the old safety name.");
            }
            if (action.startsWith(UpdateConstants.RECONFIGURE_PREFIX)
                    && !candidateProtocolSpec.getReconfigureActions().contains(action)) {
                Diagnostics.fatal("Transition requirement references unknown fine-grained reconfigure action: "
                        + action + ". Check the map relation action name.");
            }
            if (action.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX)
                    && !candidateProtocolSpec.getStartNewSpecActions().contains(action)) {
                Diagnostics.fatal("Transition requirement references unknown fine-grained startNewSpec action: "
                        + action + ". Check the new safety name.");
            }
            fineGrainedKinds.add(fineGrainedUpdateActionKind(action));
        }

        if (selectiveFineGrained) {
            for (UpdateActionKind kind : UpdateActionKind.values()) {
                if (legacyKinds.contains(kind) && fineGrainedKinds.contains(kind)) {
                    Diagnostics.fatal("Transition requirements in selective_fine_grained mode mix legacy and fine-grained "
                            + updateKindDescription(kind) + " actions. Use either the legacy action or generated "
                            + updateKindDescription(kind) + " actions for that kind, not both.");
                }
            }
        }
    }

    private UpdateActionKind legacyUpdateActionKind(String action) {
        if (UpdateConstants.STOP_OLD_SPEC.equals(action)) {
            return UpdateActionKind.STOP_OLD_SPEC;
        }
        if (UpdateConstants.RECONFIGURE.equals(action)) {
            return UpdateActionKind.RECONFIGURE;
        }
        if (UpdateConstants.START_NEW_SPEC.equals(action)) {
            return UpdateActionKind.START_NEW_SPEC;
        }
        return null;
    }

    private UpdateActionKind fineGrainedUpdateActionKind(String action) {
        if (action.startsWith(UpdateConstants.STOP_OLD_SPEC_PREFIX)) {
            return UpdateActionKind.STOP_OLD_SPEC;
        }
        if (action.startsWith(UpdateConstants.RECONFIGURE_PREFIX)) {
            return UpdateActionKind.RECONFIGURE;
        }
        if (action.startsWith(UpdateConstants.START_NEW_SPEC_PREFIX)) {
            return UpdateActionKind.START_NEW_SPEC;
        }
        Diagnostics.fatal("Unknown fine-grained update action: " + action);
        return null;
    }

    private String updateKindDescription(UpdateActionKind kind) {
        if (UpdateActionKind.STOP_OLD_SPEC.equals(kind)) {
            return "stopOldSpec";
        }
        if (UpdateActionKind.RECONFIGURE.equals(kind)) {
            return "reconfigure";
        }
        if (UpdateActionKind.START_NEW_SPEC.equals(kind)) {
            return "startNewSpec";
        }
        return "update";
    }

    private void relabelSelectiveMappingComponents(
            Vector<CompactState> mappingComponents,
            UpdateProtocolSpec candidateProtocolSpec,
            UpdateProtocolSpec finalProtocolSpec) {
        for (int i = 0; i < mappingComponents.size(); i++) {
            String candidateAction = candidateProtocolSpec.getReconfigureActionForMappingIndex(i);
            String finalAction = finalProtocolSpec.getReconfigureActionForMappingIndex(i);
            if (candidateAction != null && finalAction != null && !candidateAction.equals(finalAction)) {
                CompactStateActionRelabeler.relabelAction(mappingComponents.get(i), candidateAction, finalAction);
            }
        }
    }

    private void recordInputScale(
            ControllerGoalDefinition oldGoalDef,
            ControllerGoalDefinition newGoalDef,
            Set<String> controllableSet,
            Vector<CompactState> mappingComponents,
            UpdatingControllerCompositeState ucce,
            CompositeState oldC) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }

        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "old env component 数", safeSize(oldEnvironmentList), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "new env component 数", safeSize(newEnvironmentList), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "map relation 数", safeSize(mapRelationList), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "mapping component 数", safeSize(mappingComponents), "個");
        recordEnvironmentComponentStateSpaces("Old", oldEnvironmentList);
        recordEnvironmentComponentStateSpaces("New", newEnvironmentList);
        recordMappingComponentStateSpaces(mappingComponents);
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "old safety 数", safeSize(oldGoalDef.getSafetyDefinitions()), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "new safety 数", safeSize(newGoalDef.getSafetyDefinitions()), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "transition requirement 数", safeSize(transitionGoals), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "controllable action 数", safeSize(controllableSet), "個");

        MTS<Long, String> oldControllerMTS = null;
        if (ucce != null) {
            long oldMtsCountStart = System.currentTimeMillis();
            oldControllerMTS = ucce.getOldController();
            if (oldControllerMTS != null
                    && !UpdatingControllerEvaluationRecorder.hasOldControllerStateSpace()) {
                UpdatingControllerEvaluationRecorder.recordOldControllerStateSpace(
                        oldControllerMTS.getStates().size(),
                        countTransitions(oldControllerMTS),
                        System.currentTimeMillis() - oldMtsCountStart);
            }
        }

        Set<String> knownActions = new HashSet<>();
        if (oldC != null && oldC.composition != null) {
            addAlphabet(knownActions, oldC.composition);
        }
        if (oldControllerMTS != null && oldControllerMTS.getActions() != null) {
            knownActions.addAll(oldControllerMTS.getActions());
        }
        if (ucce != null) {
            addAlphabet(knownActions, ucce.machines);
            if (ucce.getOldSafetyLTSs() != null) addAlphabet(knownActions, ucce.getOldSafetyLTSs());
            if (ucce.getNewSafetyLTSs() != null) addAlphabet(knownActions, ucce.getNewSafetyLTSs());
            if (ucce.getTransitionRequirements() != null) addAlphabet(knownActions, ucce.getTransitionRequirements());
            if (ucce.getSynthesisMachines() != null) addAlphabet(knownActions, ucce.getSynthesisMachines());
        }
        addAlphabet(knownActions, mappingComponents);
        knownActions.add(UpdateConstants.BEGIN_UPDATE);
        knownActions.add(UpdateConstants.STOP_OLD_SPEC);
        knownActions.add(UpdateConstants.RECONFIGURE);
        knownActions.add(UpdateConstants.START_NEW_SPEC);
        if (ucce != null && ucce.isOTF()) {
            knownActions.add(UpdateConstants.FINISH_UPDATE);
        }
        knownActions.remove("tau");

        Set<String> normalizedControllableActions = new HashSet<>();
        if (controllableSet != null) {
            normalizedControllableActions.addAll(controllableSet);
        }
        normalizedControllableActions.remove("tau");
        Set<String> allActions = new HashSet<>(knownActions);
        allActions.addAll(normalizedControllableActions);
        allActions.remove("tau");
        int controllableCount = normalizedControllableActions.size();
        int uncontrollableCount = Math.max(0, allActions.size() - controllableCount);
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "全 action 数（controllable + uncontrollable）", allActions.size(), "個");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "uncontrollable action 数", uncontrollableCount, "個");
    }

    private void recordEnvironmentComponentStateSpaces(String kind, List<Symbol> environmentList) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (environmentList == null) {
            return;
        }

        Hashtable<String, ProcessSpec> processes = LTSCompiler.getProcesses();
        Hashtable<String, CompactState> compiledProcesses = LTSCompiler.getCompiled();
        String section = "入力規模 / " + kind + " Environment Component";

        for (int i = 0; i < environmentList.size(); i++) {
            Symbol symbol = environmentList.get(i);
            String name = symbol == null ? "" : symbol.toString();
            CompactState component = null;
            if (name != null && !name.isEmpty() && !"unknown".equals(name)) {
                ensureCompiled(name, processes);
                component = compiledProcesses.get(name);
            }

            long countStart = System.currentTimeMillis();
            long states = compactStateCount(component);
            long transitions = countTransitions(component);
            long countTime = System.currentTimeMillis() - countStart;

            UpdatingControllerEvaluationRecorder.recordStateSpace(
                    section,
                    kind + " Environment Component[" + i + "] " + componentDisplayName(name, component),
                    states,
                    transitions,
                    countTime,
                    kind + " Environment を構成する個別 component の状態数・遷移数。"
                            + "同じ index の Mapping Environment Component と対応する。");
        }
    }

    private static void recordMappingComponentStateSpaces(Vector<CompactState> mappingComponents) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        long totalStates = 0;
        long totalTransitions = 0;
        long maxStates = 0;
        long maxTransitions = 0;

        if (mappingComponents != null) {
            for (int i = 0; i < mappingComponents.size(); i++) {
                CompactState component = mappingComponents.get(i);
                long countStart = System.currentTimeMillis();
                long states = compactStateCount(component);
                long transitions = countTransitions(component);
                long countTime = System.currentTimeMillis() - countStart;

                totalStates += states;
                totalTransitions += transitions;
                maxStates = Math.max(maxStates, states);
                maxTransitions = Math.max(maxTransitions, transitions);

                UpdatingControllerEvaluationRecorder.recordStateSpace(
                        "入力規模 / Mapping Environment Component",
                        "Mapping Environment Component[" + i + "] " + compactStateName(component),
                        states,
                        transitions,
                        countTime,
                        "Mapping Environment を構成する個別 component の状態数・遷移数。");
            }
        }

        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "mapping component 状態数合計", totalStates, "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "mapping component 遷移数合計", totalTransitions, "transitions");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "mapping component 最大状態数", maxStates, "states");
        UpdatingControllerEvaluationRecorder.recordCount(
                "入力規模", "mapping component 最大遷移数", maxTransitions, "transitions");
    }

    private static String componentDisplayName(String declaredName, CompactState component) {
        if (declaredName != null && !declaredName.isEmpty() && !"unknown".equals(declaredName)) {
            return declaredName;
        }
        return compactStateName(component);
    }

    private static void recordTraditionalMappingEnvironmentStateSpace(CompositeState mappingComposite) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (mappingComposite == null || mappingComposite.composition == null) {
            return;
        }

        long countStart = System.currentTimeMillis();
        long states = compactStateCount(mappingComposite.composition);
        long transitions = countTransitions(mappingComposite.composition);
        long countTime = System.currentTimeMillis() - countStart;
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                "入力規模 / Traditional Mapping Environment",
                "Traditional Mapping Environment",
                states,
                transitions,
                countTime,
                "Traditional DUC で Mapping Environment Component 群を並列合成した Mapping Environment。旧コントローラとはまだ合成していない。");
    }

    private static int countTransitions(MTS<Long, String> mts) {
        if (mts == null || mts.getStates() == null) {
            return 0;
        }
        int transitions = 0;
        for (Long state : mts.getStates()) {
            transitions += mts.getTransitions(state, MTS.TransitionType.REQUIRED).size();
        }
        return transitions;
    }

    private static long compactStateCount(CompactState machine) {
        return machine == null ? 0 : Math.max(0, machine.maxStates);
    }

    private static long countTransitions(CompactState machine) {
        if (machine == null || machine.states == null) {
            return 0;
        }
        return machine.ntransitions();
    }

    private static String compactStateName(CompactState machine) {
        if (machine == null || machine.name == null || machine.name.isEmpty()) {
            return "(unnamed)";
        }
        return machine.name;
    }

    private static int safeSize(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void addAlphabet(Set<String> actions, Collection<CompactState> machines) {
        if (actions == null || machines == null) {
            return;
        }
        for (CompactState machine : machines) {
            if (machine == null || machine.alphabet == null) {
                continue;
            }
            actions.addAll(Arrays.asList(machine.alphabet));
        }
    }

    private static void addAlphabet(Set<String> actions, CompactState machine) {
        if (actions == null || machine == null || machine.alphabet == null) {
            return;
        }
        actions.addAll(Arrays.asList(machine.alphabet));
    }

    private CompositeState composeLTS(String target) {
        CompositionExpression lts = LTSCompiler.getComposite(target);
        // ★修正: 定義が見つからない場合のチェックを追加
        if (lts == null) {
            Diagnostics.fatal("Composite Definition not found for: " + target +
                    ". Please ensure 'newController' (or other components) are correctly defined in your .lts file.");
        }
        return lts.compose(null);
    }

    private HashSet<String> compileSet(Vector<String> actions) {
        if (actions == null)
            Diagnostics.fatal("Set not defined.");
        return new HashSet<String>(actions);
    }

    private HashSet<String> compileSet(Symbol setSymbol) {
        Hashtable<?, ?> constants = LabelSet.getConstants();
        LabelSet labelSet = (LabelSet) constants.get(setSymbol.toString());
        if (labelSet == null) {
            Diagnostics.fatal("Set not defined.");
        }
        Vector<String> actions = labelSet.getActions(null);
        return new HashSet<String>(actions);
    }

    public void setOldController(ArrayList<Symbol> oldController) {
        this.oldController = oldController.get(0);
    }

    public void setMapping(ArrayList<Symbol> mapping) {
        this.mapping = mapping.get(0);
    }

    public void addTransitionGoal(Symbol safety) {
        this.transitionGoals.add(safety);
    }

    public void setNewGoal(Symbol newGoal) {
        this.newGoal = newGoal;
    }

    public void setOldGoal(Symbol oldGoal) {
        this.oldGoal = oldGoal;
    }

    public void setNonblocking() {
        this.nonblocking = true;
    }

    public Symbol getOldController() {
        return oldController;
    }

    public Symbol getMapping() {
        return mapping;
    }

    public List<Symbol> getTransitionGoals() {
        return transitionGoals;
    }

    public Symbol getNewGoal() {
        return newGoal;
    }

    public Symbol getOldGoal() {
        return oldGoal;
    }

    public Boolean isNonblocking() {
        return nonblocking;
    }

    // ★追加: セッターメソッド
    public void setIsOTF() {
        this.isOTF = true;
    }

    public void setFineGrained() {
        this.fineGrained = true;
    }

    public void setSelectiveFineGrained() {
        this.selectiveFineGrained = true;
    }

    public boolean isFineGrained() {
        return fineGrained || selectiveFineGrained;
    }

    public boolean isSelectiveFineGrained() {
        return selectiveFineGrained;
    }

    public void setNewController(ArrayList<Symbol> newController) {
        this.newController = newController.get(0);
    }

    public Symbol getNewController() {
        return newController;
    }

    // ▼▼▼ 追加: セッターメソッド (LTSCompilerから呼ばれる) ▼▼▼
    public void setOldEnvironment(List<Symbol> list) {
        this.oldEnvironmentList = list;
    }

    public void setNewEnvironment(List<Symbol> list) {
        this.newEnvironmentList = list;
    }

    public void setMapRelation(List<Symbol> list) {
        this.mapRelationList = list;
    }
    // ▲▲▲ 追加ここまで ▲▲▲

    /**
    Retrieves the components of a composite definition without performing
    parallel composition.
    Handles nested compositions recursively.
     */
    // ★追加メソッド: 定義名から合成せずに構成要素だけを取得
    // ★修正: clone()を使わず、フラグの一時変更で対応
    private Vector<CompactState> getComponentsWithoutComposition(String targetName)
    {
        // 定義を取得
        CompositionExpression ce = LTSCompiler.getComposite(targetName);
        if (ce == null)
        {
            Diagnostics.fatal("Definition not found: " + targetName);
        }

        // 現在のフラグ状態を保存
        boolean originalMakeCompose = ce.makeCompose;
        boolean originalMakeController = ce.makeController;
        boolean originalMakeAbstract = ce.makeAbstract;
        boolean originalMakeMinimal = ce.makeMinimal;
        boolean originalMakeDeterministic = ce.makeDeterministic;

        // 合成計算を回避するためにフラグをFalseに設定
        ce.makeCompose = false;
        ce.makeController = false;
        ce.makeAbstract = false;
        ce.makeMinimal = false;
        ce.makeDeterministic = false;
        Vector<CompactState> machines = new Vector<>();
        try {
            // compose(null) を呼ぶと、makeCompose=false なので合成計算はスキップされ、
            // 構成要素が machines ベクタに格納された状態が返る (はず)
            CompositeState cs = ce.compose(null);
            
            // もしCompositeState自体が空で、bodyがある場合は再帰収集を行う
            // (LTSAの構造上、ce.compose()でmachinesが埋まらないケースへの保険)
            if ((cs.getMachines() == null || cs.getMachines().isEmpty()) && ce.body != null) {
                collectMachines(ce.body, machines);
            }
            else if (cs.getMachines() != null) {
                machines.addAll(cs.getMachines());
            }
            // else {
            // machines = cs.getMachines();
            // }
        } finally {
            // 必ず元の状態に戻す
            ce.makeCompose = originalMakeCompose;
            ce.makeController = originalMakeController;
            ce.makeAbstract = originalMakeAbstract;
            ce.makeMinimal = originalMakeMinimal;
            ce.makeDeterministic = originalMakeDeterministic;
        }
        return machines;
    }

    // ★修正: 再帰的にマシンを収集（clone()回避版）
    private void collectMachines(CompositeBody body, Vector<CompactState> machines)
    {
        // 1. シングルトン(ProcessRef)の場合
        if (body.singleton != null)
        {
            String procName = body.singleton.name.toString();
            // A. まず基本プロセスとして検索
            // CompactState cs = LTSCompiler.getCompiled().get(procName);
            Hashtable<String, CompactState> compiledMap = LTSCompiler.getCompiled();
            CompactState cs = (compiledMap != null) ? compiledMap.get(procName) : null;
            if (cs != null)
            {
                machines.add(cs.myclone());
            }
            else
            {
                // B. 基本プロセスになければ、Composite定義として検索
                CompositionExpression ce = LTSCompiler.getComposite(procName);
                if (ce != null)
                {
                    // Composite定義が見つかった場合、その中身も再帰的に収集
                    if (ce.body != null) {
                        collectMachines(ce.body, machines);
                    } else {
                        // bodyがない（定義済みコンポーネントとして扱える）場合
                        // フラグ一時変更でmachinesを取得
                        boolean originalMakeCompose = ce.makeCompose;
                        ce.makeCompose = false;
                        try {
                            CompositeState compiledCS = ce.compose(null);
                            // machines.addAll(compiledCS.getMachines());
                            if (compiledCS.getMachines() != null) {
                                machines.addAll(compiledCS.getMachines());
                            }
                        } finally {
                            ce.makeCompose = originalMakeCompose;
                        }
                    }
                }
                else
                {
                    Diagnostics.fatal("Component process not found: " + procName);
                }
            }
        }
        // 2. 複数のプロセス(procRefs)の場合 (A || B の構造)
        if (body.procRefs != null)
        {
            for (CompositeBody subBody : body.procRefs)
            {
                collectMachines(subBody, machines);
            }
        }
    }

    private boolean isControllerDefinition(String name) {
        CompositionExpression ce = LTSCompiler.getComposite(name);
        return ce != null && ce.makeController;
    }

    // ★追加: コンパイルを保証するメソッド (MapBody.java からロジックを流用)
    private void ensureCompiled(String name, Hashtable<String, ProcessSpec> processes) {
        Hashtable<String, CompactState> compiled = LTSCompiler.getCompiled();
        if (compiled.containsKey(name))
            return;

        // ▼▼▼ 修正: ベース名とパラメータの分離 ▼▼▼
        String baseName = name;
        Vector<Value> actuals = new Vector<>();
        if (name.contains("(")) {
            baseName = name.substring(0, name.indexOf('(')).trim();
            String paramStr = name.substring(name.indexOf('(') + 1, name.lastIndexOf(')'));
            String[] params = paramStr.split(",");
            for (String p : params) {
                p = p.trim();
                try {
                    actuals.add(new Value(Integer.parseInt(p)));
                } catch (NumberFormatException ex) {
                    Hashtable<?, ?> constants = Expression.constants;
                    if (constants != null && constants.containsKey(p)) {
                        actuals.add((Value) constants.get(p));
                    } else {
                        output.outln("Warning: Cannot parse parameter '" + p + "'. Using fallback 0.");
                        actuals.add(new Value(0));
                    }
                }
            }
        }
        // ▲▲▲ 修正ここまで ▲▲▲

        ProcessSpec p = (processes != null) ? processes.get(baseName) : null;
        if (p != null) {
            try {
                output.outln("INFO: Auto-compiling dependency: " + name);
                StateMachine sm = new StateMachine(p, actuals);
                CompactState cs = sm.makeCompactState();
                cs.name = name;
                compiled.put(name, cs);
            } catch (Exception e) {
                Diagnostics.fatal("Error compiling dependency '" + name + "': " + e);
            }
        } else {
            Diagnostics.fatal("Environment process '" + baseName + "' not found.");
        }
    }

    /**
    デバッグ用: CompactState(LTS)の全状態と遷移をコンソールに出力する
    クラスの末尾などに追加してください
     */
    private void printLTSStructure(CompactState lts, LTSOutput output) {
        output.outln(" Alphabet: " + Arrays.toString(lts.alphabet));
        for (int i = 0; i < lts.maxStates; i++) {
            StringBuilder sb = new StringBuilder();
            sb.append(" State ").append(i).append(" : ");
            ltsa.lts.EventState current = lts.states[i];
            if (current == null) {
                sb.append("(terminal)");
            } else {
                boolean first = true;
                while (current != null) {
                    if (!first)
                        sb.append(", ");
                    String eventName = (current.event < lts.alphabet.length) ? lts.alphabet[current.event]
                            : "unknown(" + current.event + ")";
                    sb.append(eventName).append(" -> ").append(current.next);
                    current = current.list;
                    first = false;
                }
            }
            output.outln(sb.toString());
        }
    }

    /**
     * BFS探索のキュー要素を保持するための内部クラス
     */
    private static class StateCombo {
        int safeState;
        List<Integer> fluentStates;
        StateCombo(int s, List<Integer> f) {
            this.safeState = s;
            this.fluentStates = f;
        }
    }

    /**
     * 区間FluentをCompactStateに変換する。
     * initiating/terminatingアクションに基づき、システムフェーズをまたいで状態を正しく保持する。
     */
    private CompactState convertFluentToLTS(Fluent fluent, String[] alphabet) {
        String name = fluent.getName();
        CompactState machine = new CompactState();
        machine.name = name;
        machine.maxStates = 2; // State 0 (False), State 1 (True)
        machine.alphabet = alphabet;
        machine.states = new EventState[2];

        Set<String> initiating = new HashSet<>();
        for (MTSSynthesis.ar.dc.uba.model.language.Symbol s : fluent.getInitiatingActions()) {
            initiating.add(s.toString());
        }

        Set<String> terminating = new HashSet<>();
        for (MTSSynthesis.ar.dc.uba.model.language.Symbol s : fluent.getTerminatingActions()) {
            terminating.add(s.toString());
        }

        for (int i = 0; i < alphabet.length; i++) {
            String action = alphabet[i];

            // State 0 (False) からの遷移
            int nextFrom0 = 0; 
            if (initiating.contains(action)) {
                nextFrom0 = 1;
            }
            machine.states[0] = EventStateUtils.add(machine.states[0], new EventState(i, nextFrom0));

            // State 1 (True) からの遷移
            int nextFrom1 = 1; 
            if (terminating.contains(action) || terminating.contains("*")) {
                if (!initiating.contains(action)) {
                    nextFrom1 = 0;
                }
            }
            machine.states[1] = EventStateUtils.add(machine.states[1], new EventState(i, nextFrom1));
        }
        return machine;
    }

    /**
     * BFSを用いてSafety Propertyと各Fluentを並行探索し、
     * [Fluent1状態, Fluent2状態...] -> Safety状態 の完全な対応マップを構築する。
     */
    private Map<List<Integer>, Integer> generateStateMappingBFS(CompactState originalSafe, List<Fluent> fluents, List<CompactState> fluentAutomata, String[] alphabet) {
        Map<List<Integer>, Integer> mapping = new HashMap<>();
        Queue<StateCombo> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();

        // 初期状態の特定
        int initialSafe = 0; // LTSAのCompactStateにおける初期状態は常に0
        List<Integer> initialFluents = new ArrayList<>();
        for (Fluent f : fluents) {
            initialFluents.add(f.getInitialValue() ? 1 : 0);
        }

        mapping.put(initialFluents, initialSafe);
        queue.add(new StateCombo(initialSafe, initialFluents));
        visited.add(initialSafe + "|" + initialFluents.toString());

        while (!queue.isEmpty()) {
            StateCombo current = queue.poll();
            int currSafe = current.safeState;
            List<Integer> currFluents = current.fluentStates;

            for (String action : alphabet) {
                int nextSafe = getNextState(originalSafe, currSafe, action);

                List<Integer> nextFluents = new ArrayList<>();
                for (int fIdx = 0; fIdx < fluentAutomata.size(); fIdx++) {
                    CompactState fLts = fluentAutomata.get(fIdx);
                    int fCurrState = currFluents.get(fIdx);
                    int fNextState = getNextState(fLts, fCurrState, action);
                    nextFluents.add(fNextState);
                }

                String sig = nextSafe + "|" + nextFluents.toString();
                if (!visited.contains(sig)) {
                    visited.add(sig);
                    
                    // ★修正: 既にマップに登録されていて、かつ今回がERRORでないなら上書きしない
                    // (ただし、ERRORに行き着くパスがあるならERRORを優先して上書きする)
                    if (!mapping.containsKey(nextFluents)) {
                        mapping.put(nextFluents, nextSafe);
                    } else if (nextSafe == ltsa.lts.Declaration.ERROR) {
                        mapping.put(nextFluents, nextSafe);
                    }

                    // エラー状態からは遷移しないためキューに入れない
                    if (nextSafe != ltsa.lts.Declaration.ERROR) {
                        queue.add(new StateCombo(nextSafe, nextFluents));
                    }
                }
            }
        }
        return mapping;
    }

    /**
     * 指定されたアクションに対するLTSの次状態を取得するヘルパーメソッド。
     * (修正版: アルファベット外のアクションは自己ループとして無視する)
     */
    private int getNextState(CompactState lts, int currState, String actionStr) {
        // すでにエラー状態ならエラーのまま
        if (currState == ltsa.lts.Declaration.ERROR) return ltsa.lts.Declaration.ERROR;

        // 1. そのアクションが自身のアルファベットに含まれているかチェック
        boolean inAlphabet = false;
        for (String a : lts.alphabet) {
            if (a.equals(actionStr)) {
                inAlphabet = true;
                break;
            }
        }

        // 2. アルファベットに含まれていない場合は「無視(自己ループ)」
        if (!inAlphabet) {
            return currState;
        }

        // 3. アルファベットに含まれている場合は遷移を探す
        EventState current = lts.states[currState];
        while (current != null) {
            if (lts.alphabet[current.event].equals(actionStr)) {
                return current.next; // 遷移先を返す
            }
            current = current.list;
        }

        // 4. アルファベットに含まれているのに遷移定義がない場合は「安全性違反 (ERROR)」
        return ltsa.lts.Declaration.ERROR;
    }
}
