package ltsa.updatingControllers.synthesis;

import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.MapSetBinaryRelation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * OTF-DUC用のラッパーLTSクラス。
 * 既存のLTSのアクション 'a' に対して、元の 'a' による遷移に加え、
 * 指定された接尾辞（例: "_old"）を付けたアクション 'a_old' による同一状態への遷移を動的に追加します。
 * * これにより、EnvironmentやSafety Propertyが、Old ControllerとNew Controllerの両方のアクションに
 * 同期して状態遷移できるようになります。
 */
public class SynchedOldActionLTS<State, Action> extends LTSImpl<State, Action> {

    /** ラップ対象の元のLTS */
    private final LTS<State, Action> original;
    /** 追加するアクション名の接尾辞（例: "_old"） */
    private final String suffix;

    /**
     * コンストラクタ
     * @param original ラップする元のLTS
     * @param suffix 追加するアクションの接尾辞
     */
    public SynchedOldActionLTS(LTS<State, Action> original, String suffix) {
        // LTSImplのコンストラクタには初期状態を渡す
        super(original.getInitialState());
        this.original = original;
        this.suffix = suffix;
    }

    /**
     * 全状態を取得します。
     * 状態集合は元のLTSと同じです。
     */
    @Override
    public Set<State> getStates() {
        return original.getStates();
    }

    /**
     * 全アクション集合を取得します。
     * 元のアクション集合に加え、接尾辞付きのアクション集合を含みます。
     * 例: {a, b} -> {a, b, a_old, b_old}
     */
    @Override
    public Set<Action> getActions() {
        Set<Action> expandedActions = new HashSet<>();
        for (Action a : original.getActions()) {
            // 元のアクションを追加
            expandedActions.add(a);
            // 同期用のアクションを追加（tau以外）
            if (!a.toString().equals("tau")) {
                expandedActions.add(createSynchedAction(a));
            }
        }
        return expandedActions;
    }

    /**
     * 初期状態を取得します。
     */
    @Override
    public State getInitialState() {
        return original.getInitialState();
    }

    /**
     * すべての遷移マップを取得しようとすると例外を投げます。
     * On-The-Fly探索では全遷移の構築を行わないため、誤用を防ぐために無効化しています。
     */
    @Override
    public Map<State, BinaryRelation<Action, State>> getTransitions() {
        throw new UnsupportedOperationException("Use getTransitions(state) for on-the-fly access to save memory.");
    }

    /**
     * 指定された状態からの遷移を取得します。
     * ここで動的に遷移を複製・追加します。
     * * 元: s -- a --> s'
     * 新: s -- a --> s', s -- a_old --> s'
     */
    @Override
    public BinaryRelation<Action, State> getTransitions(State state) {
        // 元のLTSから遷移を取得
        BinaryRelation<Action, State> origTrans = original.getTransitions(state);
        
        // 新しいリレーションコンテナを作成
        BinaryRelation<Action, State> newTrans = new MapSetBinaryRelation<>();
        
        for (Pair<Action, State> transition : origTrans) {
            Action originalAction = transition.getFirst();
            State toState = transition.getSecond();

            // 1. 元のアクションでの遷移を追加 (New Controller/通常の同期用)
            newTrans.addPair(originalAction, toState);

            // 2. 接尾辞付きアクションでの遷移を追加 (Old Controllerとの同期用)
            // ただし、内部遷移アクション "tau" は複製しない
            if (!originalAction.toString().equals("tau")) {
                Action synchedAction = createSynchedAction(originalAction);
                newTrans.addPair(synchedAction, toState);
            }
        }
        return newTrans;
    }

    /**
     * アクション名に接尾辞を追加した新しいアクションオブジェクトを生成します。
     * キャストが必要になります。
     */
    @SuppressWarnings("unchecked")
    private Action createSynchedAction(Action action) {
        return (Action) (action.toString() + suffix);
    }
}