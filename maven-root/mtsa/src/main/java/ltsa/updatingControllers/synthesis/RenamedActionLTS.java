package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.MapSetBinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair; // ★必須
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RenamedActionLTS<State, Action> extends LTSImpl<State, Action> {
    private final LTS<State, Action> original;
    private final String suffix;

    public RenamedActionLTS(LTS<State, Action> original, String suffix) {
        super(original.getInitialState());
        this.original = original;
        this.suffix = suffix;
    }

    @Override
    public Set<State> getStates() {
        return original.getStates();
    }

    @Override
    public Set<Action> getActions() {
        Set<Action> renamed = new HashSet<>();
        for (Action a : original.getActions()) {
            renamed.add(rename(a));
        }
        return renamed;
    }

    @Override
    public State getInitialState() {
        return original.getInitialState();
    }

    @Override
    public Map<State, BinaryRelation<Action, State>> getTransitions() {
        throw new UnsupportedOperationException("Use getTransitions(state) for on-the-fly access");
    }

    @Override
    public BinaryRelation<Action, State> getTransitions(State state) {
        // 元のLTSから遷移を取得
        BinaryRelation<Action, State> origTrans = original.getTransitions(state);
        
        // 新しいリレーションを作成 (LTSImplで使用されているものと互換性のある実装)
        BinaryRelation<Action, State> newTrans = new MapSetBinaryRelation<>();
        
        // ★修正: Map.Entry ではなく Pair を使用
        for (Pair<Action, State> entry : origTrans) {
            // ★修正: add ではなく addPair を使用 (LTSImpl 39行目準拠)
            // getKey()/getValue() ではなく getFirst()/getSecond()
            newTrans.addPair(rename(entry.getFirst()), entry.getSecond());
        }
        return newTrans;
    }

    @SuppressWarnings("unchecked")
    private Action rename(Action action) {
        // 文字列として結合して、Action型(通常はString)にキャスト
        if (action.toString().equals("tau")) return action;
        return (Action) (action.toString() + suffix);
    }
}