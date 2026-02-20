package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;

import java.util.*;

/**
 * New Controller (合成・最小化済み) の状態と、
 * その構成要素 (New Environment, New Safety) の状態の対応表を作成するクラス。
 * * New Controller を初期状態からBFS探索し、並行してコンポーネントの状態遷移を追跡することで
 * マッピングを構築します。
 */
public class StateMapper {

    private final LTS<Long, String> controller;
    private final List<LTS<Long, String>> envComponents;
    private final List<LTS<Long, String>> safeComponents;
    
    // コンポーネントごとのアルファベットキャッシュ (高速化用)
    private final List<Set<String>> envAlphabets;
    private final List<Set<String>> safeAlphabets;

    // 結果のマップ
    // Key: "EnvState1,EnvState2,...|SafeState1,SafeState2,..." (Signature)
    // Value: ControllerStateID
    private final Map<String, Long> mapping;

    /**
     * コンストラクタ
     * @param controller 合成済みのNew Controller
     * @param envComponents New Environmentの構成要素リスト
     * @param safeComponents New Safety Propertyの構成要素リスト
     */
    public StateMapper(LTS<Long, String> controller,
                       List<LTS<Long, String>> envComponents,
                       List<LTS<Long, String>> safeComponents) {
        this.controller = controller;
        this.envComponents = envComponents;
        this.safeComponents = safeComponents;
        this.mapping = new HashMap<>();
        
        // アルファベットのキャッシュ生成 (毎回getActionsを呼ぶコストを削減)
        this.envAlphabets = new ArrayList<>();
        for (LTS<Long, String> lts : envComponents) {
            envAlphabets.add(lts.getActions());
        }
        
        this.safeAlphabets = new ArrayList<>();
        for (LTS<Long, String> lts : safeComponents) {
            safeAlphabets.add(lts.getActions());
        }
    }

    /**
     * マッピングを生成して返します。
     * @return 状態シグネチャからコントローラ状態IDへのマップ
     */
    public Map<String, Long> generateMapping() {
        // BFS用キュー
        Queue<CompositeStateNode> queue = new LinkedList<>();
        // 訪問済みシグネチャの管理 (無限ループ防止)
        Set<String> visitedSignatures = new HashSet<>();

        // 1. 初期状態のノード作成
        Long initCtrl = controller.getInitialState();
        
        List<Long> initEnv = new ArrayList<>();
        for (LTS<Long, String> lts : envComponents) initEnv.add(lts.getInitialState());
        
        List<Long> initSafe = new ArrayList<>();
        for (LTS<Long, String> lts : safeComponents) initSafe.add(lts.getInitialState());

        CompositeStateNode initialNode = new CompositeStateNode(initCtrl, initEnv, initSafe);
        String initSig = initialNode.getSignature();
        
        // 2. 初期状態の登録
        queue.add(initialNode);
        visitedSignatures.add(initSig);
        mapping.put(initSig, initCtrl);

        // 3. BFS探索ループ
        while (!queue.isEmpty()) {
            CompositeStateNode current = queue.poll();
            Long currCtrlState = current.controllerState;

            // コントローラの現在状態から出るすべての遷移を取得
            BinaryRelation<String, Long> transitions = controller.getTransitions(currCtrlState);

            for (Pair<String, Long> transition : transitions) {
                String action = transition.getFirst();
                Long nextCtrlState = transition.getSecond();

                // アクションに基づいて次のコンポーネント状態リストを計算
                // (非決定性がある場合、複数の候補が返る可能性があるためListのListになっています)
                List<List<Long>> nextEnvCandidates = getNextComponentStates(envComponents, envAlphabets, current.envStates, action);
                List<List<Long>> nextSafeCandidates = getNextComponentStates(safeComponents, safeAlphabets, current.safeStates, action);

                // コンポーネント側で遷移不可(null)でなければ進む
                if (nextEnvCandidates != null && nextSafeCandidates != null) {
                    
                    // 非決定性の展開 (直積)
                    // 通常、合成後のコントローラ上の1つの遷移は、各コンポーネントの特定の遷移に対応しますが、
                    // 環境モデルに非決定性がある場合、複数の可能性を全てマップに登録しておきます。
                    
                    for (List<Long> nextEnv : nextEnvCandidates) {
                        for (List<Long> nextSafe : nextSafeCandidates) {
                            
                            CompositeStateNode nextNode = new CompositeStateNode(nextCtrlState, nextEnv, nextSafe);
                            String signature = nextNode.getSignature();
                            
                            // 未訪問の構成であればキューに追加
                            if (!visitedSignatures.contains(signature)) {
                                visitedSignatures.add(signature);
                                mapping.put(signature, nextCtrlState);
                                queue.add(nextNode);
                            } else {
                                // 既に訪問済みでも、別のパスで同じ構成に至った場合、
                                // マップの整合性チェック（同じ構成なら同じCtrlIDのはず）
                                // ここでは上書きしても同じ値になるはずなので何もしない
                            }
                        }
                    }
                }
            }
        }
        
        return mapping;
    }

    /**
     * コンポーネントリストの次の状態を計算する
     * 非決定性に対応するため、可能な状態の組み合わせリストを返します。
     * @return 次の状態リストのリスト。遷移不可の場合はnull
     */
    private List<List<Long>> getNextComponentStates(List<LTS<Long, String>> components, 
                                                    List<Set<String>> alphabets, 
                                                    List<Long> currentStates, 
                                                    String action) {
        // 各コンポーネントごとの可能な次状態セットを収集
        List<Set<Long>> nextStatesPerComponent = new ArrayList<>();
        
        for (int i = 0; i < components.size(); i++) {
            LTS<Long, String> lts = components.get(i);
            Set<String> alpha = alphabets.get(i);
            Long currState = currentStates.get(i);
            Set<Long> nextCandidates = new HashSet<>();
            
            if (alpha.contains(action)) {
                // アクションがアルファベットに含まれる -> 同期遷移
                BinaryRelation<String, Long> trans = lts.getTransitions(currState);
                Set<Long> image = trans.getImage(action);
                
                if (image == null || image.isEmpty()) {
                    // アルファベットに含まれるのに遷移先がない -> ブロック (このパスは無効)
                    return null; 
                }
                nextCandidates.addAll(image);
            } else {
                // アクションがアルファベットに含まれない -> 状態維持
                nextCandidates.add(currState);
            }
            nextStatesPerComponent.add(nextCandidates);
        }
        
        // 直積をとって、可能な状態リストのリストを作成する
        return cartesianProduct(nextStatesPerComponent);
    }

    /**
     * 集合のリストの直積を生成するヘルパーメソッド
     * [[A, B], [C]] -> [[A, C], [B, C]]
     */
    private List<List<Long>> cartesianProduct(List<Set<Long>> sets) {
        List<List<Long>> result = new ArrayList<>();
        if (sets.isEmpty()) {
            result.add(new ArrayList<>());
            return result;
        }
        
        cartesianProductRecursive(sets, 0, new ArrayList<>(), result);
        return result;
    }
    
    private void cartesianProductRecursive(List<Set<Long>> sets, int index, List<Long> current, List<List<Long>> result) {
        if (index == sets.size()) {
            result.add(new ArrayList<>(current));
            return;
        }
        
        for (Long val : sets.get(index)) {
            current.add(val);
            cartesianProductRecursive(sets, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    // --- Inner Class for BFS Queue ---
    
    private static class CompositeStateNode {
        Long controllerState;
        List<Long> envStates;
        List<Long> safeStates;

        public CompositeStateNode(Long controllerState, List<Long> envStates, List<Long> safeStates) {
            this.controllerState = controllerState;
            this.envStates = envStates;
            this.safeStates = safeStates;
        }

        public String getSignature() {
            StringBuilder sb = new StringBuilder();
            
            // Env States
            for (int i = 0; i < envStates.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(envStates.get(i));
            }
            sb.append("|");
            
            // Safe States
            for (int i = 0; i < safeStates.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(safeStates.get(i));
            }
            return sb.toString();
        }
    }
}