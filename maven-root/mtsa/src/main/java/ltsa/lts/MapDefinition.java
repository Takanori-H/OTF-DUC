package ltsa.lts;

import java.util.Vector;

public class MapDefinition {
    public Symbol name;
    public Symbol oldProcess;
    public Symbol newProcess;
    public Symbol relationName; // 参照するRelation定義の名前

    // ★追加: リレーションに渡す引数を保持するフィールド
    public String relationArg;

    public MapDefinition(Symbol n) {
        this.name = n;
    }
}
