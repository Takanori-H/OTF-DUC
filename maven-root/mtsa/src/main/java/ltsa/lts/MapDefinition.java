package ltsa.lts;

import java.util.Vector;

public class MapDefinition {
    public Symbol name;
    public Symbol oldProcess;
    public Symbol newProcess;
    public Symbol relationName; // 参照するRelation定義の名前

    public MapDefinition(Symbol n) {
        this.name = n;
    }
}
