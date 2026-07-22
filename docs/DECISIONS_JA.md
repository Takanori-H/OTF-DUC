# 設計判断メモ

このファイルは、ユーザーが明示的に研究方針を変えない限り、将来のローカルLLMエージェントが維持すべき設計判断を記録する。

英語版は `DECISIONS.md` に残している。

## D-001: OTF-DUC は DUCS の意味を保つ

OTF-DUC は、従来 DUCS とは別の更新問題としてではなく、従来 DUCS の正しさの意図を保つ実装・合成戦略として説明する。

従来 DUCS の正しさは、`stopOldSpec` まで旧仕様を維持し、transition requirement を満たし、`startNewSpec` で新仕様を開始し、`reconfigure` を実行し、更新を完了することを中心にしている。OTF-DUC は full `E_u` の materialization を避けながら、これらの意味を保つ。

## D-002: `hotSwapIn` を使い、`hotSwapOut` を追加する

実装では `hotSwapIn` を更新開始として扱う。OTF-DUC は New Controller への明示的な handoff として `hotSwapOut` を追加する。

実装上は `UpdateConstants.BEGIN_UPDATE = "hotSwapIn"`、`UpdateConstants.FINISH_UPDATE = "hotSwapOut"` である。`FINISH_UPDATE` は Java 定数名として残っているだけなので、設計メモでは event 名として `hotSwapOut` を使う。

`hotSwapOut` は単なる進捗マーカではない。環境状態の変換と New Controller への接続チェックによって guard される。

## D-003: OTF の box list から New Controller を除外する

New Controller は DCS 探索の box list には入れない。これは状態数削減の中心的な設計判断である。

代わりに、`StateMapper` が `(New Environment state, New Safety state)` の signature から New Controller state への map を事前計算する。DCS の状態空間は update bridge だけを探索し、New Controller への接続は出力構築時に行う。

## D-004: 10状態の Marking LTS を使う

OTF Marking LTS は次の状態を持つ。

- state 0: `hotSwapIn` 前。
- states 1-8: `stopOldSpec`, `reconfigure`, `startNewSpec` の完了 bitmask。
- state 9: `hotSwapOut` 後。

DCS の goal として marked なのは state 9 だけである。

## D-005: New Safety の有効化を遅らせる

New Safety は `startNewSpec` 前に enforce してはいけない。

Fluent 由来の synthesis machine は背景挙動を trace する。`startNewSpec` 時に、OTF-DUC は fluent/monitor state を使って元の New Safety component を正しい状態へ同期し、その後 enforce する。

## D-006: Active / Trace / Enforce の区別を保つ

3つの flag は別々の意味を持つ。

- Active: action enablement / blocking への参加を制御する。
- Trace: component state の追跡を制御する。
- Enforce: error / sink state 到達を path の致命的違反として扱うかを制御する。

これらを単一の enabled/disabled switch に潰してはいけない。OTF-DUC の意味が変わってしまう。

## D-007: Trace-off component を正規化する

Trace flag が off の component は、不要な探索状態の違いを作ってはいけない。State normalization は意図した状態空間削減の一部である。

## D-008: `hotSwapOut` を handoff 条件として扱う

`hotSwapOut` は次の条件を満たす場合にだけ許可する。

- Mapping Environment state が New Environment state に変換できる。
- 変換後の New Environment state と現在の New Safety state が、safe な New Controller state に対応する。

これにより、安全でない状態や意味的に不明な状態から update controller を New Controller に接続することを避ける。

## D-009: 評価 metric は機械可読に保つ

`UpdatingControllerEvaluationRecorder` は構造化された CSV 風の評価データを出力する。実験スクリプトは informal text を scrape するのではなく、可能な限りこの machine-readable block を使う。

## D-010: 長時間実行の観測性を保つ

`DUCHeartbeat` は Traditional / OTF synthesis の長時間 phase を観測するために存在する。単なる logging に見えても、大きな benchmark run の診断に役立つので削除しない。

## D-011: OTF-DUC 内部の GR(1) 風 loop 判定を削除する

OTF-DUC の loop 解析に、別個の GR(1) 風 progress fixed point を持ち込まない。OTF 探索は自身の fairness / loop handling を使い、traditional DUCS の GR synthesizer とは別の実装 path として保つ。

これにより、OTF-DUC が update progress を正当化するために内部 GR(1) 風 loop promotion に依存していない、という研究上の主張が明確になる。

## D-012: OTF-DUC の fairness は marking state 8 に限定する

OTF-DUC は、`stopOldSpec`, `reconfigure`, `startNewSpec` がすべて完了した後、すなわち `hotSwapOut` 前の marking state 8 に限って既存の fairness fixed-point method を適用する。

marking state 1-7 では、fairness によって update-path loop を救済してはいけない。検出された update-path loop が uncontrollable action で継続できる場合、その uncontrollable loop-continuation action の source state は losing である。これは update-protocol action や他の action が loop から出られる場合でも同じである。OTF-DUC はそのような state を error にし、標準の error propagation によって、それらへ強制され得る state を error にする。marking state 8 の fairness fixed point のもとでは、uncontrollable exit を fair progress として使ってよく、update-protocol action も progress exit として使える。ただし ordinary controllable action は uncontrollable loop からの exit としては扱わない。ordinary controllable action は winning progress action として選ばれない限り、生成 controller から prune される。

## D-013: Pre-update closure と fairness を分ける

Marking state 0 は `hotSwapIn` 前なので、そこにある old-controller loop を update fairness で正当化してはいけない。代わりに OTF-DUC は pre-update GOAL closure を使う。m0 state は、探索済みの `hotSwapIn` edge が既に winning な update path に到達し、探索済みの uncontrollable old-controller action が同じ promoted m0 領域または既に winning な state 内に留まる場合に promote できる。

これにより、m0 closure と marking state 8 fairness の概念を分ける。m0 closure は update 開始前の通常の old-controller operation を表し、marking state 8 fairness は十分に準備された update bridge から `hotSwapOut` へ進む fair progress を表す。

## D-014: Fine-grained update event は opt-in mode にする

`.lts` keyword `fine_grained` は fine-grained update event を選択する。`on_the_fly` と一緒に使うと fine-grained OTF-DUC、`on_the_fly` なしで使うと fine-grained Traditional DUC になる。この flag がない file は legacy update protocol を使う。比較可能性を保つため、fine-grained implementation は実用上可能な範囲で legacy class path から分離する。

fine-grained mode では、`stopOldSpec_<safety>` と `startNewSpec_<safety>` を old/new controllerSpec の safety 名から生成する。Relation rule は legacy `reconfigure` か明示的な `reconfigure_*` action を使える。legacy `reconfigure` は `reconfigure_<mapping component>` に正規化される。各 mapping component は reconfigure action をちょうど1つ持つ。生成された action 名は transition requirement から参照可能である。

Fine-grained progress は DCS state vector 内の1つの synthetic progress slot として表現する。action ごとに1つの LTS を追加しない。`ProgressRegistry` は任意サイズの BigInteger completion mask を compact な state ID に map するため、fine-grained update action 数は 63 個に制限されない。`hotSwapOut` はすべての fine-grained stop/reconfigure/start action が完了し、既存の new-controller stitching guard が成功した後にだけ enabled になる。

Traditional fine-grained DUC は依然として `E_u` を materialize する。fine-grained `E_u` generation は、update state に per-safety `stopOldSpec_*` と `startNewSpec_*` self-loop を追加し、generated mapping environment transition に per-mapping `reconfigure_*` を保ち、すべての fine-grained progress action に DontDoTwice を適用し、すべての fine-grained progress action を GR guarantee set に入れる。Traditional DUC には `hotSwapOut` は追加しない。

## D-015: 更新安全性と新コントローラへの移行完了を分けて扱う

marking state 8 は、更新プロトコル本体が完了した状態を表す。つまり、`stopOldSpec`, `reconfigure`, `startNewSpec` はすべて完了しており、新安全性も有効になっている。そのため、marking state 8 の状態は、事前に合成した New Controller への `hotSwapOut` による移行がまだ起きていなくても、更新安全性の観点では安全であり得る。

ここでは、次の2つの正しさを分けて考える。

- 更新安全性: update bridge が旧安全性・新安全性の意図した意味を保ち、marking state 8 で有効な新安全性を違反しないこと。
- 移行完了性: `hotSwapIn` の後、必ずいつか `hotSwapOut` を発火して、事前に合成した New Controller へ移行すること。

デフォルトの OTF-DUC では、marking state 8 の fairness 固定点を「fairness 仮定付きの移行完了性」として使う。この解釈では、安全な marking state 8 の SCC から fair な実行で `hotSwapOut` に到達できるなら、その SCC を受理してよい。ただし、環境がその SCC 内で uncontrollable action を選び続けるトレースは残るため、通常の全トレースに対する model checking では `hotSwapIn -> <> hotSwapOut` が失敗することがある。

jar 起動オプション `-Dotfduc.fairness=false` または `-Dotfduc.disableFairness=true` は、この marking state 8 の fairness による GOAL 昇格を無効化する。この stricter mode では、移行完了性を通常の全トレース到達性として扱う。そのため、fairness ありでは合成できる例題が失敗することがあるが、fairness 仮定なしで生成コントローラが通常の `hotSwapIn -> <> hotSwapOut` を満たすことを確認したい場合はこちらを使う。

これは marking state 1-7 の扱いを変えるものではない。marking state 1-7 は更新プロトコルが未完了の状態であり、fairness によってその更新途中の loop を救済してはいけない。更新途中で閉じた uncontrollable loop が進捗を妨げるなら、その状態は負けである。ただし on-the-fly 探索中は、後続状態が十分に探索されるまでエラー判定を遅らせることがある。uncontrollable self-loop がある状態では、未探索の controllable 更新 action が存在するだけでは winning とは言えない。
