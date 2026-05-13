package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking;

/** 探索状態に付与する判定結果を表す。 */
enum Status {
    ERROR(-1, "ERROR"),
    NONE(0, "NONE"),
    GOAL(1, "GOAL");
    private final int precedence;
    private final String name;
    Status(int p, String n) {
        precedence = p;
        name = n;
    }
    @Override
    public String toString() {
        return name;
    }
}
