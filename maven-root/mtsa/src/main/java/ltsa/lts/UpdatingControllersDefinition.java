package ltsa.lts;

import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.control.ControllerGoalDefinition;
import ltsa.control.util.GoalDefToControllerGoal;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
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
        long start = System.currentTimeMillis();

        // ---------------------------------------------------------
        // 1. Old Controller のコンパイル (Monolithic)
        // ---------------------------------------------------------
        CompositeState oldC = composeLTS(this.getOldController().toString());
        output.outln("UpdatingControllersDefinition Generate OldController time : "
                + (System.currentTimeMillis() - start) + "ms");

        // ---------------------------------------------------------
        // 2. Goal 定義の準備
        // ---------------------------------------------------------
        ControllerGoalDefinition oldGoalDef = ControllerGoalDefinition.getDefinition(this.getOldGoal());
        ControllerGoalDefinition newGoalDef = ControllerGoalDefinition.getDefinition(this.getNewGoal());

        // 全体のControllable Action (OTF探索用)
        Set<String> controllableSet = this.generateUpdatingControllableActions(oldGoalDef, newGoalDef);
        // Symbol controllableSetSymbol =
        // LTSCompiler.saveControllableSet(controllableSet, this.getName().getName());

        // ---------------------------------------------------------
        // 3. Mapping Environment の生成
        // ---------------------------------------------------------
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
                mapDef.relationName = relSym;

                // relations を渡す
                CompactState mapComp = generator.generate(mapDef, compiledProcesses, relations, this.output);

                if (mapComp != null) {
                    mapComp.name = mapEnvName;
                    mappingComponents.add(mapComp);
                    if (!compiledProcesses.containsKey(mapEnvName)) {
                        compiledProcesses.put(mapEnvName, mapComp);
                    }

                    // ★追加: 生成されたマッピング情報をリストに保存 (コピーを作成)
                    Map<Integer, Integer> currentMap = new HashMap<>(generator.getStateMapping());
                    mappingMapEnvToNewEnv.add(currentMap);

                    // ▼▼▼▼▼▼▼▼▼ デバッグ出力：対応付けの確認 ▼▼▼▼▼▼▼▼▼
                    output.outln("--------------------------------------------------");
                    output.outln("DEBUG: Verifying Mapping for " + mapEnvName);

                    // 1. State Mapping の表示 (Generatorから取得)
                    output.outln(" [State Mapping (MapEnvID -> NewEnvID)]");
                    if (currentMap.isEmpty()) {
                        output.outln(" (No mapping recorded. Check Generator implementation)");
                    } else {
                        List<Integer> sortedKeys = new ArrayList<>(currentMap.keySet());
                        Collections.sort(sortedKeys);
                        for (Integer mapStateId : sortedKeys) {
                            output.outln(" MapState " + mapStateId + " -> NewEnvState " + currentMap.get(mapStateId));
                        }
                    }

                    // 2. Mapping Environment の構造表示
                    output.outln(" [Structure: " + mapEnvName + "]");
                    printLTSStructure(mapComp, output);

                    // 3. New Environment の構造表示 (比較用)
                    if (compiledProcesses.containsKey(newName)) {
                        CompactState newEnvComp = compiledProcesses.get(newName);
                        output.outln(" [Structure: " + newName + " (Reference)]");
                        printLTSStructure(newEnvComp, output);
                    }
                    output.outln("--------------------------------------------------");
                    // ▲▲▲▲▲▲▲▲▲ デバッグ出力ここまで ▲▲▲▲▲▲▲▲▲
                } else {
                    Diagnostics.fatal("Failed to generate mapping component '" + mapEnvName + "'.");
                }
            }
        }
        else {
            Diagnostics.fatal("Mapping environment not defined.");
        }

        output.outln("UpdatingControllersDefinition Generate MappingEnvironment time : "
                + (System.currentTimeMillis() - start) + "ms");
        long tmp = System.currentTimeMillis();

        // ---------------------------------------------------------
        // 4. モード別処理 (OTF / Traditional)
        // ---------------------------------------------------------
        UpdatingControllerCompositeState ucce;
        if (this.isOTF)
        {
            output.outln("Mode: On-The-Fly Updating Controller Synthesis");

            // OTF固有の設定
            controllableSet.add(UpdateConstants.BEGIN_UPDATE);
            controllableSet.add(UpdateConstants.FINISH_UPDATE);
            long newCgenerate = System.currentTimeMillis();

            // =========================================================
            // New Controller の内部合成
            // =========================================================
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
            output.outln("UpdatingControllersDefinition NewC generate time : "
                    + (System.currentTimeMillis() - newCgenerate) + "ms");

            // ▼▼▼ デバッグ出力：状態数と遷移数のカウント ▼▼▼
            int stateCount = newC.composition.maxStates;
            int transitionCount = 0;

            // 全状態の遷移リストを走査してカウント
            for (int i = 0; i < stateCount; i++) {
                ltsa.lts.EventState current = newC.composition.states[i];
                while (current != null) {
                    transitionCount++;
                    current = current.list;
                }
            }

            output.outln("---------------------------------------------------------");
            output.outln("DEBUG: New Controller Synthesis Result");
            output.outln(" - Name: " + newC.composition.name);
            output.outln(" - States: " + stateCount);
            output.outln(" - Transitions: " + transitionCount);
            output.outln(" - Alphabet Size: " + newC.composition.alphabet.length);
            output.outln("---------------------------------------------------------");
            // ▲▲▲ 追加ここまで ▲▲▲

            // Safety Goals の取得
            Vector<CompactState> oldSafetyLTSs = new Vector<>();
            Collection<CompactState> oldSafeCol = CompositionExpression.preProcessSafetyReqs(oldGoalDef, output);
            if (oldSafeCol != null)
                oldSafetyLTSs.addAll(oldSafeCol);

            // New Safety Goals の保持 (Vector変換)
            Vector<CompactState> newSafetyLTSs = new Vector<>();
            if (newSafeCol != null)
                newSafetyLTSs.addAll(newSafeCol);

            // ★変更: ここで Transition Goals を CompactState に変換する
            // 元の getTransitionGoals() (List<Symbol>) を渡して変換
            Vector<CompactState> transitionLTSs = UpdatingControllersUtils
                    .compileTransitionRequirements(this.getTransitionGoals(), output);

            // ★追加: ログ出力して確認
            if (transitionLTSs != null) {
                for (CompactState cs : transitionLTSs) {
                    UpdatingControllersUtils.logCompactState(cs, output);
                }
            }

            // =========================================================
            // Updating Controller 用の Monitor & Action Fluent 生成
            // =========================================================
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
            // これらをFluentが参照している場合(例: fluent UpdateMode = <beginUpdate, finishUpdate>)
            // アルファベットに含まれていないと遷移が生成されないため、明示的に追加します。
            alphaSet.add(UpdateConstants.BEGIN_UPDATE); // "beginUpdate"
            alphaSet.add(UpdateConstants.FINISH_UPDATE); // "finishUpdate"
            alphaSet.add(UpdateConstants.STOP_OLD_SPEC); // "stopOldSpec"
            alphaSet.add(UpdateConstants.START_NEW_SPEC);// "startNewSpec"

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
                for (CompactState originalSafe : newSafeCol) {
                    Set<String> errorActions = new HashSet<>();
                    List<CompactState> components = new ArrayList<>();

                    // 1. モニター変換 (同時に errorActions を収集)
                    CompactState monitor = convertToMonitor(originalSafe, errorActions);
                    synthesisMachines.add(monitor); // モニターはプロパティごとに固有なので追加
                    components.add(monitor); // リストの0番目は常にモニター

                    // 2. アクションFluentの取得 (キャッシュ利用)
                    // ListとMapの整合性を保つため、ソートして順序を固定
                    List<String> sortedErrorActions = new ArrayList<>(errorActions);
                    Collections.sort(sortedErrorActions);
                    for (String action : sortedErrorActions) {
                        if (!globalFluentCache.containsKey(action)) {
                            // キャッシュになければ生成して登録
                            // output.outln(" -> Generating unique Action Fluent: " + action);
                            CompactState fluent = buildActionFluentLTS(action, monitorAlphabet);
                            // buildActionFluentLTSには全体のアクションが必要ではないか？
                            // mapping componentからとupdateconstant
                            globalFluentCache.put(action, fluent);
                            synthesisMachines.add(fluent); // ユニークなものだけを合成用リストに追加
                        }
                        // このプロパティの構成要素リストに追加 (1番目以降はFluent)
                        components.add(globalFluentCache.get(action));
                    }

                    // 3. 状態マッピングの生成 (MonitorState + FluentStates -> OriginalState)
                    // ★Integer型で生成
                    Map<List<Integer>, Integer> stateMap = generateStateMapping(originalSafe, sortedErrorActions);

                    // 4. マップへの登録
                    safetyComponentsMap.put(originalSafe, components);
                    safetyStateMapping.put(originalSafe, stateMap);

                    // デバッグ用
                    /*
                    output.outln("--------------------------");
                    output.outln(originalSafe.name + " Mapped");
                    for(CompactState fluent : components){
                        output.outln(fluent.name);
                    }
                     */

                    // =========================================================
                    // Debug: State Mapping Visualization
                    // =========================================================
                    /*
                    // 1. ヘッダーの作成: [MonitorName, FluentName1, FluentName2...] -> [PropertyName]
                    StringBuilder headerBuilder = new StringBuilder();
                    headerBuilder.append("Mapping Table [").append(monitor.name);
                    // sortedErrorActions の順序に従ってFluent名を追加
                    for (String action : sortedErrorActions) {
                        // buildActionFluentLTS で生成した命名規則 ("FLUENT_" + action + "_a") に合わせる
                        String fluentName = "FLUENT_" + action + "_a";
                        headerBuilder.append(", ").append(fluentName);
                    }
                    headerBuilder.append("] -> [").append(originalSafe.name).append("]");
                    output.outln(headerBuilder.toString());
                    // 2. マップのエントリーをキー（List<Integer>）でソート
                    // ([0,0] -> [0,1] -> [1,0]... の順に並べるため)
                    List<Map.Entry<List<Integer>, Integer>> sortedEntries = new
                    ArrayList<>(stateMap.entrySet());
                    Collections.sort(sortedEntries, new Comparator<Map.Entry<List<Integer>,
                    Integer>>() {
                        @Override
                        public int compare(Map.Entry<List<Integer>, Integer> e1,
                                            Map.Entry<List<Integer>, Integer> e2) {
                            List<Integer> k1 = e1.getKey();
                            List<Integer> k2 = e2.getKey();
                            int size = Math.min(k1.size(), k2.size());
                            for (int i = 0; i < size; i++) {
                                int cmp = k1.get(i).compareTo(k2.get(i));
                                if (cmp != 0) return cmp;
                            }
                            return Integer.compare(k1.size(), k2.size());
                        }
                    });

                    // 3. ソートされたエントリーの出力
                    // 形式: [MonitorState, FluentState...] -> SafetyState
                    for (Map.Entry<List<Integer>, Integer> entry : sortedEntries) {
                        List<Integer> keyStateList = entry.getKey();
                        Integer targetState = entry.getValue();
                        // JavaのList.toString()は "[0, 1, 0]" のような形式になるため、そのまま利用できます
                        output.outln(keyStateList.toString() + " -> " + targetState);
                    }
                    output.outln("--------------------------");
                     */
                }
            }

            ucce = new UpdatingControllerCompositeState(oldC, newC, mappingComponents, newEnvComponents, mappingMapEnvToNewEnv,
                                                        oldSafetyLTSs, newSafetyLTSs,
                                                        // transitionGoals,
                                                        transitionLTSs, synthesisMachines, safetyComponentsMap, safetyStateMapping,
                                                        controllableSet,true, name.getName());
        }
        else
        {
            // Traditional Mode
            output.outln("Mode: Traditional Updating Controller Synthesis");
            ControllerGoal<String> grGoal = UpdatingControllersUtils.generateGRUpdateGoal(this, oldGoalDef, newGoalDef,
                    controllableSet);
            ControllerGoalDefinition safetyGoal = UpdatingControllersUtils.generateSafetyGoalDef(this, oldGoalDef,
                    newGoalDef, controllableSet, output);
            CompositeState mappingComposite = new CompositeState(mappingComponents);

            mappingComposite.name = "MAPPING_ENV";
            // 合成を実行
            mappingComposite.compose(output);
            ucce = new UpdatingControllerCompositeState(oldC, mappingComposite, safetyGoal, grGoal, name.getName());
        }

        output.outln("UpdatingControllersDefinition mode time : " + (System.currentTimeMillis() - tmp) + "ms");
        output.outln("UpdatingControllersDefinition total time : " + (System.currentTimeMillis() - start) + "ms");
        return ucce;
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
        ProcessSpec p = (processes != null) ? processes.get(name) : null;
        if (p != null) {
            try {
                output.outln("INFO: Auto-compiling dependency: " + name);
                StateMachine sm = new StateMachine(p, new Vector<>());
                CompactState cs = sm.makeCompactState();
                cs.name = name;
                compiled.put(name, cs);
            } catch (Exception e) {
                Diagnostics.fatal("Error compiling dependency '" + name + "': " + e);
            }
        } else {
            Diagnostics.fatal("Environment process '" + name + "' not found.");
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
    Safety LTS を Monitor Automaton に変換するメソッド。
    ERROR (-1) への遷移を、現在の状態への自己ループに書き換えます。
    同時に、ERROR遷移を引き起こすアクション名を収集します。
     */
    private CompactState convertToMonitor(CompactState original, Set<String> errorActionsCollector) {
        CompactState monitor = original.myclone();
        monitor.name = "MONITOR_" + original.name;
        String[] alphabet = monitor.alphabet;
        output.outln(" -> Converting Safety Property '" + original.name + "' to Error-Free Monitor.");
        for (int i = 0; i < monitor.maxStates; i++) {
            EventState current = monitor.states[i];
            while (current != null) {
                if (current.next == Declaration.ERROR) {
                    current.next = i; // 自己ループ
                    errorActionsCollector.add(alphabet[current.event]);
                }
                EventState nd = current.nondet;
                while (nd != null) {
                    if (nd.next == Declaration.ERROR) {
                        nd.next = i; // 自己ループ
                        errorActionsCollector.add(alphabet[current.event]);
                    }
                    nd = nd.nondet;
                }
                current = current.list;
            }
        }
        return monitor;
    }

    /*
    指定されたアクションに対する Action Fluent LTS を構築する。
     */
    private CompactState buildActionFluentLTS(String targetAction, String[] originalAlphabet) {
        String name = "FLUENT_" + targetAction + "_a";
        CompactState fluent = new CompactState();
        fluent.name = name;
        fluent.maxStates = 2;
        fluent.alphabet = originalAlphabet;
        fluent.states = new EventState[2];

        int targetEventIdx = -1;
        for (int i = 0; i < originalAlphabet.length; i++) {
            if (originalAlphabet[i].equals(targetAction)) {
                targetEventIdx = i;
                break;
            }
        }

        EventState state0 = null;
        EventState state1 = null;

        for (int i = 0; i < originalAlphabet.length; i++) {
            if (i == targetEventIdx) {
                state0 = EventStateUtils.add(state0, new EventState(i, 1));
                state1 = EventStateUtils.add(state1, new EventState(i, 1));
            } else {
                state0 = EventStateUtils.add(state0, new EventState(i, 0));
                state1 = EventStateUtils.add(state1, new EventState(i, 0));
            }
        }

        fluent.states[0] = state0;
        fluent.states[1] = state1;

        return fluent;
    }

    /**
    元のSafetyプロパティに基づいて、MonitorとFluentの状態の組み合わせから、
    元のSafetyプロパティの状態へのマッピングを生成する。
    ★修正: 遷移先が ERROR (-1) の場合のみ登録する。
    (正常な遷移はMonitorが正しく追跡しているため、Mapでの上書き衝突を防ぐためにも登録しない)
    Key: [MonitorState(Int), Fluent1State(Int)...]
    Value: OriginalState(Int) -> 常に -1 (ERROR)
     */
    private Map<List<Integer>, Integer> generateStateMapping(CompactState originalSafe,
            List<String> sortedErrorActions) {
        Map<List<Integer>, Integer> mapping = new HashMap<>();
        String[] alphabet = originalSafe.alphabet;

        // 全状態を走査
        for (int s = 0; s < originalSafe.maxStates; s++) {
            EventState current = originalSafe.states[s];

            // 存在する遷移だけをトレース
            while (current != null) {
                int eventIdx = current.event;
                String actionName = alphabet[eventIdx];
                int nextState = current.next;

                // ★重要: ERROR (-1) に遷移する場合のみマップに登録する
                if (nextState == Declaration.ERROR) {
                    // キーの生成: [MonitorState, Fluent1, Fluent2...]
                    List<Integer> key = new ArrayList<>();
                    key.add(s); // Monitor State

                    // 各Fluentの状態を決定
                    for (String errorAction : sortedErrorActions) {
                        if (errorAction.equals(actionName)) {
                            key.add(1); // True
                        } else {
                            key.add(0); // False
                        }
                    }

                    // マップに登録 (値は常に -1)
                    mapping.put(key, nextState);
                }

                // 非決定性遷移の考慮（Safety Propertyでは稀だが念のため）
                EventState nd = current.nondet;
                while (nd != null) {
                    if (nd.next == Declaration.ERROR) {
                        List<Integer> key = new ArrayList<>();
                        key.add(s);
                        for (String errorAction : sortedErrorActions) {
                            if (errorAction.equals(actionName))
                                key.add(1);
                            else
                                key.add(0);
                        }
                        mapping.put(key, nd.next);
                    }
                    nd = nd.nondet;
                }
                current = current.list;
            }
        }
        return mapping;
    }
}