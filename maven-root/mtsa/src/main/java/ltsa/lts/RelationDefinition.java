package ltsa.lts;

import java.util.Vector;
// import java.util.Hashtable;
import java.util.Stack;

public class RelationDefinition {
    public Symbol name;
    public Vector<RelationRule> rules = new Vector<>();

    // ★追加: パラメータ名を保持するフィールド
    public Symbol parameterName;

    public RelationDefinition(Symbol name) {
        this.name = name;
    }

    public void addRule(RelationRule rule) {
        rules.add(rule);
    }

    /**
     * 1つのマッピングルールを表す内部クラス
     * 構文: (forall[range])? oldState@oldProc = reconfigure -> newState@newProc
     */
    public static class RelationRule {
        // forall [i:0..N] の部分。なければ null
        public ActionLabels range; 
        
        // 左辺: BAT[i]@BATTERY_COUNTER
        public ActionLabels oldStateSelector; // BAT[i]
        public Symbol oldProcessName;         // BATTERY_COUNTER

        // 右辺: BAT[i]@NEW_BATTERY_COUNTER
        public ActionLabels newStateSelector; // BAT[i]
        public Symbol newProcessName;         // NEW_BATTERY_COUNTER

        // ▼▼▼ 追加 ▼▼▼
        // reconfigureの前に行うアクション列 (例: jump)
        public Vector<ActionLabels> preReconfigureActions = new Vector<>();

        // mapping componentを旧環境側から新環境側へ切り替える中心アクション
        public ActionLabels reconfigureAction;
        
        // reconfigureの後に行うアクション列 (例: down)
        public Vector<ActionLabels> postReconfigureActions = new Vector<>();
        // ▲▲▲ 追加ここまで ▲▲▲
        
        // ガード条件: when (i>0) ... (拡張用、今回はnullでも可)
        public Stack<Symbol> guard; 
    }
    
}
