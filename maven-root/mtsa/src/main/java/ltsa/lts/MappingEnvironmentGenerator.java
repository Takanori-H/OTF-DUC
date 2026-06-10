package ltsa.lts;

import java.util.*;

import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.structures.UpdateProtocolSpec;

public class MappingEnvironmentGenerator {

    // 生成されたMappingEnvironmentの状態ID -> NewEnvironmentの状態ID の対応表
    private Map<Integer, Integer> stateMapping;

    public MappingEnvironmentGenerator() {
        this.stateMapping = new HashMap<>();
    }

    // 対応表を取得するためのゲッター
    public Map<Integer, Integer> getStateMapping() {
        return stateMapping;
    }

    // 内部処理用に展開されたルールを保持するクラス
    private static class FlattenedRule {
        String oldLabel;
        String newLabel;
        String reconfigureAction;
        Vector<String> preActions; // 展開済みのアクション文字列リスト
        Vector<String> postActions; // 展開済みのアクション文字列リスト
    }

    public CompactState generate(MapDefinition mapDef,
            Hashtable<String, CompactState> compiled,
            Hashtable<String, RelationDefinition> relations,
            LTSOutput output) {
        return generate(mapDef, compiled, relations, output, null, -1);
    }

    public CompactState generate(MapDefinition mapDef,
            Hashtable<String, CompactState> compiled,
            Hashtable<String, RelationDefinition> relations,
            LTSOutput output,
            UpdateProtocolSpec updateProtocolSpec,
            int mappingIndex) {

        // 生成のたびにマップを初期化
        this.stateMapping.clear();
        String oldName = mapDef.oldProcess.toString();
        String newName = mapDef.newProcess.toString();
        String relName = mapDef.relationName.toString();
        String mapEnvName = mapDef.name.toString();
        CompactState oldM = compiled.get(oldName);
        CompactState newM = compiled.get(newName);
        RelationDefinition relDef = relations.get(relName);

        if (oldM == null || newM == null || relDef == null) {
            return null;
        }

        output.outln("Generating Mapping Environment: " + mapEnvName);
        String componentReconfigureAction = null;

        // 1. ルールの展開と必要な追加状態数の計算
        Vector<FlattenedRule> flatRules = new Vector<>();
        int extraStatesCount = 0;

        for (RelationDefinition.RelationRule rule : relDef.rules) {
            // Vector<FlattenedRule> expanded = expandRule(rule);
            // ★変更: mapDefとrelDefも引数として渡し、変数展開に使えるようにする
            Vector<FlattenedRule> expanded = expandRule(rule, mapDef, relDef);
            flatRules.addAll(expanded);
            for (FlattenedRule fr : expanded) {
                fr.reconfigureAction = normalizeReconfigureAction(fr.reconfigureAction,
                        mapEnvName, updateProtocolSpec != null);
                if (componentReconfigureAction == null) {
                    componentReconfigureAction = fr.reconfigureAction;
                } else if (!componentReconfigureAction.equals(fr.reconfigureAction)) {
                    Diagnostics.fatal("A mapping component can have only one reconfigure action: "
                            + componentReconfigureAction + " and " + fr.reconfigureAction
                            + " in " + mapEnvName + ".");
                }
                // 必要なステップ数: preActions + reconfigure(1) + postActions
                int steps = fr.preActions.size() + 1 + fr.postActions.size();
                // 必要な中間状態数: steps - 1
                if (steps > 1) {
                    extraStatesCount += (steps - 1);
                }
            }
        }
        if (updateProtocolSpec != null) {
            if (componentReconfigureAction == null) {
                componentReconfigureAction = UpdateConstants.RECONFIGURE_PREFIX + mapEnvName;
            }
            updateProtocolSpec.registerReconfigure(mappingIndex, componentReconfigureAction);
        }

        // 2. LTS枠組み作成
        int oldSize = oldM.maxStates;
        int newSize = newM.maxStates;
        int totalStates = oldSize + newSize + extraStatesCount;
        int newOffset = oldSize;
        int extraOffset = oldSize + newSize;
        CompactState res = new CompactState();
        res.name = mapEnvName;
        res.maxStates = totalStates;
        res.states = new EventState[res.maxStates];

        // 3. アルファベット統合
        Vector<String> alphabet = new Vector<>();
        for (String s : oldM.alphabet)
            alphabet.add(s);
        for (String s : newM.alphabet) {
            if (!alphabet.contains(s))
                alphabet.add(s);
        }
        for (FlattenedRule fr : flatRules) {
            if (!alphabet.contains(fr.reconfigureAction))
                alphabet.add(fr.reconfigureAction);
            addActionsToAlphabet(alphabet, fr.preActions);
            addActionsToAlphabet(alphabet, fr.postActions);
        }

        res.alphabet = alphabet.toArray(new String[0]);

        // 4. 既存遷移コピー
        Map<Integer, Integer> rawStateMapping = new HashMap<>();
        for (int i = 0; i < oldSize; i++) {
            res.states[i] = copyTransitions(oldM.states[i], oldM.alphabet, res.alphabet);
        }
        for (int i = 0; i < newSize; i++) {
            int mappingStateId = i + newOffset;
            int newEnvStateId = i;
            res.states[i + newOffset] = copyTransitionsWithOffset(newM.states[i], newM.alphabet, res.alphabet,
                    newOffset);
            rawStateMapping.put(mappingStateId, newEnvStateId);
        }

        // ▼▼▼ 制約5に基づく推測の確認用デバッグ出力を追加 ▼▼▼
        // output.outln("========== DEBUG: STATE ID VERIFICATION ==========");
        // for (FlattenedRule fr : flatRules) {
        //     Integer startNode = oldM.getStateId(fr.oldLabel);
        //     Integer endNode = newM.getStateId(fr.newLabel);
        //     output.outln("Rule: " + fr.oldLabel + " -> " + fr.newLabel);
        //     output.outln("  - oldM.getStateId(" + fr.oldLabel + ") = " + startNode);
        //     output.outln("  - newM.getStateId(" + fr.newLabel + ") = " + endNode);
        // }
        // output.outln("==================================================");
        // ▲▲▲ デバッグ出力ここまで ▲▲▲

        // 5. ルールに基づく遷移の追加（シーケンス処理）
        int currentExtraState = extraOffset;
        for (FlattenedRule fr : flatRules) {
            Integer startNode = oldM.getStateId(fr.oldLabel);
            Integer endNode = newM.getStateId(fr.newLabel);
            if (startNode == null || endNode == null) {
                output.outln("Warning: State label not found: " + fr.oldLabel + " -> " + fr.newLabel);
                continue;
            }
            int targetFinalNode = endNode + newOffset;

            // アクションシーケンスの構築
            Vector<String> sequence = new Vector<>(fr.preActions);
            sequence.add(fr.reconfigureAction);
            sequence.addAll(fr.postActions);
            int currentNode = startNode;
            for (int i = 0; i < sequence.size(); i++) {
                String action = sequence.get(i);
                int actionIdx = getIndex(action, res.alphabet);
                int nextNode;
                if (i == sequence.size() - 1) {
                    nextNode = targetFinalNode;
                } else {
                    nextNode = currentExtraState++;
                }

                res.states[currentNode] = EventStateUtils.add(res.states[currentNode],
                        new EventState(actionIdx, nextNode));
                currentNode = nextNode;
            }
        }

        // 6. 到達可能状態の計算とマッピングの修正
        makeReachableAndFixMapping(res, rawStateMapping);

        return res;
    }

    // --- Helper Methods ---
    /**
     * LTSA標準の EventStateUtils を使用して到達可能状態を計算し、
     * 状態IDの振り直しに合わせてマッピング情報(stateMapping)も更新する
     */
    private void makeReachableAndFixMapping(CompactState machine, Map<Integer, Integer> rawMapping) {
        // 1. 到達可能状態の計算と、旧ID→新IDへの変換マップ(otn)の取得
        // EventStateUtils.reachable は LTSA の標準ロジックで到達可能性を判定します
        MyIntHash otn = EventStateUtils.reachable(machine.states);

        // 2. マッピング情報の更新 (Raw ID -> Renumbered ID)
        stateMapping.clear();
        for (Map.Entry<Integer, Integer> entry : rawMapping.entrySet()) {
            int oldId = entry.getKey();
            int newEnvId = entry.getValue();
            // この状態が到達可能(otnに含まれる)であれば、新しいIDで登録し直す
            if (otn.containsKey(oldId)) {
                int newId = otn.get(oldId);
                stateMapping.put(newId, newEnvId);
            }
        }

        // 3. CompactStateの状態配列を更新 (CompactState.reachable のロジックを再現)
        EventState[] oldStates = machine.states;
        machine.maxStates = otn.size(); // 到達可能状態数
        machine.states = new EventState[machine.maxStates];
        for (int oldi = 0; oldi < oldStates.length; ++oldi) {
            // 到達可能な状態のみを処理
            if (otn.containsKey(oldi)) {
                int newi = otn.get(oldi);
                // 遷移先のIDも otn を使って書き換える (renumberStates)
                //machine.states[newi] = EventStateUtils.renumberStates(oldStates[oldi], otn);
                machine.states[newi] = safeRenumberStates(oldStates[oldi], otn);
            }
        }

        // endseq (終了シーケンス番号) の更新 (念のため)
        if (machine.endseq > 0 && otn.containsKey(machine.endseq)) {
            machine.endseq = otn.get(machine.endseq);
        }
    }

    private void addActionsToAlphabet(Vector<String> alpha, Vector<String> actions) {
        for (String s : actions) {
            if (!alpha.contains(s))
                alpha.add(s);
        }
    }

    // ルールを展開してフラットにする (forall対応)
    // ★変更: mapDefとrelDefを引数に受け取るように変更
    private Vector<FlattenedRule> expandRule(RelationDefinition.RelationRule rule, MapDefinition mapDef, RelationDefinition relDef) {
        Vector<FlattenedRule> result = new Vector<>();

        if (rule.range != null) {
            // forall [i:R] の展開
            // イテレータを使って変数をバインドしながら展開する
            Hashtable<String, Value> locals = new Hashtable<>();
            Hashtable<String, Value> globals = new Hashtable<>();

            // ▼▼▼ 追加: リレーションの引数をローカル変数にバインドする ▼▼▼
            if (relDef.parameterName != null && mapDef.relationArg != null) {
                String arg = mapDef.relationArg;
                try {
                    locals.put(relDef.parameterName.toString(), new Value(Integer.parseInt(arg)));
                } catch (NumberFormatException e) {
                    Hashtable<?, ?> constants = Expression.constants;
                    if (constants != null && constants.containsKey(arg)) {
                        locals.put(relDef.parameterName.toString(), (Value) constants.get(arg));
                    }
                }
            }
            // ▲▲▲ 追加ここまで ▲▲▲

            rule.range.initContext(locals, globals);

            while (rule.range.hasMoreNames()) {
                rule.range.nextName();
                // 現在のコンテキストで各要素を展開
                Vector<String> oldLabels = rule.oldStateSelector.getActions(locals, globals);
                Vector<String> newLabels = rule.newStateSelector.getActions(locals, globals);

                // アクションリストも展開 (変数が含まれる可能性があるため)
                Vector<String> pre = expandActionList(rule.preReconfigureActions, locals, globals);
                Vector<String> post = expandActionList(rule.postReconfigureActions, locals, globals);
                String recon = expandSingleAction(rule.reconfigureAction, locals, globals);

                for (String oldL : oldLabels) {
                    for (String newL : newLabels) {
                        FlattenedRule fr = new FlattenedRule();
                        fr.oldLabel = convertFspLabelToInternal(oldL);
                        fr.newLabel = convertFspLabelToInternal(newL);
                        fr.reconfigureAction = recon;
                        fr.preActions = pre;
                        fr.postActions = post;
                        result.add(fr);
                    }
                }
            }
            rule.range.clearContext();

        } else {
            // 単一ルール
            Hashtable<String, Value> locals = new Hashtable<>();
            Hashtable<String, Value> globals = new Hashtable<>();

            // ▼▼▼ 追加: forallが無い単一ルールの場合でもバインドする ▼▼▼
            if (relDef.parameterName != null && mapDef.relationArg != null) {
                String arg = mapDef.relationArg;
                try {
                    locals.put(relDef.parameterName.toString(), new Value(Integer.parseInt(arg)));
                } catch (NumberFormatException e) {
                    Hashtable<?, ?> constants = Expression.constants;
                    if (constants != null && constants.containsKey(arg)) {
                        locals.put(relDef.parameterName.toString(), (Value) constants.get(arg));
                    }
                }
            }
            // ▲▲▲ 追加ここまで ▲▲▲
            
            Vector<String> oldLabels = rule.oldStateSelector.getActions(locals, globals);
            Vector<String> newLabels = rule.newStateSelector.getActions(locals, globals);
            Vector<String> pre = expandActionList(rule.preReconfigureActions, locals, globals);
            Vector<String> post = expandActionList(rule.postReconfigureActions, locals, globals);
            String recon = expandSingleAction(rule.reconfigureAction, locals, globals);

            for (String oldL : oldLabels) {
                for (String newL : newLabels) {
                    FlattenedRule fr = new FlattenedRule();
                    fr.oldLabel = convertFspLabelToInternal(oldL);
                    fr.newLabel = convertFspLabelToInternal(newL);
                    fr.reconfigureAction = recon;
                    fr.preActions = pre;
                    fr.postActions = post;
                    result.add(fr);
                }
            }
        }
        return result;
    }

    private String expandSingleAction(ActionLabels action, Hashtable<String, Value> locals,
            Hashtable<String, Value> globals) {
        Vector<String> expanded = action.getActions(locals, globals);
        if (expanded.size() != 1) {
            Diagnostics.fatal("A relation reconfigure action must expand to exactly one action.");
        }
        return expanded.get(0);
    }

    private String normalizeReconfigureAction(String actionName, String mapEnvName, boolean fineGrained) {
        if (!fineGrained) {
            if (!UpdateConstants.RECONFIGURE.equals(actionName)) {
                Diagnostics.fatal("Relation action '" + actionName
                        + "' requires fine_grained mode. Legacy O-DUCS expects 'reconfigure'.");
            }
            return actionName;
        }
        if (UpdateConstants.RECONFIGURE.equals(actionName)) {
            return UpdateConstants.RECONFIGURE_PREFIX + mapEnvName;
        }
        if (UpdateConstants.RECONFIGURE_OTHERS.equals(actionName)) {
            Diagnostics.fatal("Relation action '" + actionName
                    + "' is reserved for selective_fine_grained mode.");
        }
        UpdateProtocolSpec.validateReconfigureAction(actionName);
        return actionName;
    }

    private Vector<String> expandActionList(Vector<ActionLabels> actions, Hashtable<String, Value> locals,
            Hashtable<String, Value> globals) {
        Vector<String> res = new Vector<>();
        for (ActionLabels al : actions) {
            // getActionsはVectorを返すが、通常アクション列定義では単一の展開結果を期待する
            // もし set {a,b} が指定されたら？ -> 枝分かれする？
            // ここでは単純化のため、getActionsの結果を全てシーケンスとして追加するのではなく、
            // ActionLabelsごとに1つのアクション文字列になると仮定する（あるいは全ての展開結果を追加する）
            // 仕様として「パス」なので、セットの使用は避けるべきだが、サポートするなら展開結果を並列ではなく直列にするか？
            // -> 文脈上、 直列(Sequence)として扱うのが自然。
            Vector<String> expanded = al.getActions(locals, globals);
            res.addAll(expanded);
        }
        return res;
    }

    // 既存のヘルパーメソッド (convertFspLabelToInternal, copyTransitions など) はそのまま維持
    private String convertFspLabelToInternal(String fspLabel) {
        if (!fspLabel.contains("["))
            return fspLabel;
        return fspLabel.replace("[", ".").replace("]", "");
    }

    private EventState copyTransitions(EventState src, String[] srcAlpha, String[] destAlpha) {
        EventState head = null;
        EventState current = src;
        while (current != null) {
            String action = srcAlpha[current.event];
            int newEventIdx = getIndex(action, destAlpha);
            if (newEventIdx != -1) {
                head = EventStateUtils.add(head, new EventState(newEventIdx, current.next));
            }
            current = current.list;
        }
        return head;
    }

    private EventState copyTransitionsWithOffset(EventState src, String[] srcAlpha, String[] destAlpha, int offset) {
        EventState head = null;
        EventState current = src;
        while (current != null) {
            String action = srcAlpha[current.event];
            int newEventIdx = getIndex(action, destAlpha);
            if (newEventIdx != -1) {
                head = EventStateUtils.add(head, new EventState(newEventIdx, current.next + offset));
            }
            current = current.list;
        }
        return head;
    }

    private int getIndex(String action, String[] alphabet) {
        for (int i = 0; i < alphabet.length; i++) {
            if (alphabet[i].equals(action))
                return i;
        }
        return -1;
    }

    /**
     * LTSA標準の EventStateUtils.renumberStates が nondet (非決定性遷移) を
     * 切り捨ててしまう問題を回避するため、nondet リストも完全に走査して
     * 状態IDを振り直す安全なメソッド。
     */
    private EventState safeRenumberStates(EventState head, ltsa.lts.MyIntHash otn) {
        if (head == null) return null;

        EventState newHead = null;
        EventState current = head;

        // メインの遷移リストを走査
        while (current != null) {
            // 行き先が到達可能(otnに含まれる)場合のみ追加
            if (otn.containsKey(current.next)) {
                int newNext = otn.get(current.next);
                newHead = EventStateUtils.add(newHead, new EventState(current.event, newNext));
            }

            // 非決定性遷移 (nondet) のリストも走査して追加
            EventState nd = current.nondet;
            while (nd != null) {
                if (otn.containsKey(nd.next)) {
                    int newNdNext = otn.get(nd.next);
                    newHead = EventStateUtils.add(newHead, new EventState(current.event, newNdNext));
                }
                nd = nd.nondet;
            }

            current = current.list;
        }

        return newHead;
    }
}
