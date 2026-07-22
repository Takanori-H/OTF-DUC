# jar 起動オプションと LTS モード指定メモ

最終更新: 2026-06-09

このメモは、MTSA jar 起動時に使う Java system property と、`.lts` の `updatingController` で DUCS / O-DUCS の各モードを選ぶための flag を記録する。

## jar へのオプション指定

以下のオプションは Java system property である。`-jar` より前に置く。

```bash
java -Dotfduc.debug=true -Dotfduc.debug.file=duc_debug.txt -jar <mtsa-jar> <args>
```

property を省略した場合は、下の表に書いた default value が使われる。

batch experiment の繰り返し回数は Java system property ではなく YAML で指定する。root key `runs: N` を使うと、YAML の `cases:` list 全体を N 回繰り返す。`outputDir: Experiment/result` の場合、出力は `Experiment/run_01/result/...`, `Experiment/run_02/result/...` のように分かれる。`runs` を省略した場合は従来の出力 layout を保つ。

## OTF-DUC 探索・出力オプション

これらは O-DUCS、FG-O-DUCS、Selective FG-O-DUCS に影響する。

| Property | Default | 意味 |
|---|---:|---|
| `otfduc.debug` | `false` | OTF-DUC の詳細な探索・debug log を有効にする。 |
| `otfduc.profile` | `false` | OTF-DUC の探索・出力構築の timing/profile 情報を出力する。 |
| `otfduc.debug.file` | `duc_debug.txt` | `otfduc.debug` と `otfduc.profile` が使う出力 file。 |
| `otfduc.debug.mergeProof` | `true` | debug log 有効時、pre-update output merge の proof 詳細を出す。 |
| `otfduc.fairness` | `true` | `hotSwapOut` 前の marking state 8 fairness fixed point を有効にする。 |
| `otfduc.disableFairness` | `false` | `true` の場合、`otfduc.fairness` の値に関係なく marking state 8 fairness fixed point を無効にする。 |
| `otfduc.simple.merge` | `false` | pre-update output state の experimental simple merge を有効にする。 |
| `otfduc.belief.repair` | `false` | output merge 後の belief repair を有効にする。 |
| `otfduc.nondet.belief.repair` | `true` | output 構築時の nondeterministic action branch repair を有効にする。 |

生成 controller が fairness 仮定なしの通常の全トレース性質 `hotSwapIn -> <> hotSwapOut` を満たすか確認したい場合は、次のどちらかを使う。

```bash
java -Dotfduc.fairness=false -jar <mtsa-jar> <args>
java -Dotfduc.disableFairness=true -jar <mtsa-jar> <args>
```

default の fairness ありでは、safe な marking-state-8 SCC から fair execution によって `hotSwapOut` へ到達できるなら、その SCC を受理し得る。fairness なしでは、`hotSwapOut` への到達を通常の全トレース到達性として扱う。この strict mode では、default mode で合成できる例題が失敗することがある。

## Belief repair の上限

以下は belief repair と nondeterministic belief repair が使う advanced limit である。

| Property | Default |
|---|---:|
| `otfduc.belief.maxStates` | `5000` |
| `otfduc.belief.maxTimeMs` | `5000` |
| `otfduc.belief.maxNodes.min` | `64` |
| `otfduc.belief.maxNodes.perNewControllerState` | `2` |
| `otfduc.belief.maxNodes.perRawPreState` | `8` |
| `otfduc.belief.maxNodes.perReachableMapValuation` | `4` |
| `otfduc.belief.maxAdditionalConcrete.min` | `128` |
| `otfduc.belief.maxAdditionalConcrete.perNewControllerState` | `4` |
| `otfduc.belief.maxAdditionalConcrete.perRawPreState` | `16` |
| `otfduc.belief.maxAdditionalTransitions.min` | `256` |
| `otfduc.belief.maxAdditionalTransitions.perNewControllerState` | `8` |
| `otfduc.belief.maxAdditionalTransitions.perRawPreState` | `32` |

## Traditional DUCS debug オプション

これらは Traditional DUCS path、FG-DUCS、Selective FG-DUCS に影響する。

| Property | Default | 意味 |
|---|---:|---|
| `traditionalduc.debug` | `false` | Traditional DUCS の debug output を有効にする。 |
| `duc.traditional.debug` | `false` | Traditional DUCS debug output の alias。 |

## 評価・heartbeat オプション

| Property | Default | 意味 |
|---|---:|---|
| `mtsa.evaluation.enabled` | `true` | update-controller evaluation recording を有効にする。 |
| `updating.controller.evaluation.enabled` | `true` | `mtsa.evaluation.enabled` が未指定の場合に使う legacy alias。 |
| `updating.controller.evaluation.printDetailedReport` | `false` | summary/data metric に加えて詳細 report を出力する。 |
| `updating.controller.evaluation.profile` | `full` | `compact` または `lean` にすると、一部の full evaluation detail を省略する。 |
| `duc.heartbeat` | `false` | 長時間実験用の heartbeat log を有効にする。 |
| `duc.heartbeat.intervalSec` | `600` | heartbeat 間隔。単位は秒。 |
| `duc.heartbeat.file` | `duc_heartbeat.log` | heartbeat 出力 file。 |
| `duc.heartbeat.append` | `false` | heartbeat file を上書きせず追記する。 |

## LTS の mode flag

update mode は各 `updatingController` 定義の中で選ぶ。jar option では選ばない。

| `updatingController` 内の `.lts` flag | Mode | 例題での target 名 |
|---|---|---|
| `on_the_fly` なし、`fine_grained` なし、`selective_fine_grained` なし | Traditional DUCS | `UpdCont` |
| `on_the_fly` | O-DUCS | `UpdCont_OTF` |
| `fine_grained` | FG-DUCS | `UpdCont_FG` |
| `on_the_fly`, `fine_grained` | FG-O-DUCS | `UpdCont_OTF_FG` |
| `selective_fine_grained` | Selective FG-DUCS | `UpdCont_SFG` |
| `on_the_fly`, `selective_fine_grained` | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

`fine_grained` と `selective_fine_grained` は同時に指定できない。同じ `updatingController` 定義で両方を指定すると compile-time error になる。

例:

```fsp
updatingController UpdCont_OTF_SFG = {
    oldController = OldSpec,
    oldEnvironment = {OLD_ENV_1, OLD_ENV_2},
    newEnvironment = {NEW_ENV_1, NEW_ENV_2},
    mapRelation = {R_ENV_1_FG, R_ENV_2_FG},
    oldGoal = OldSpec,
    newGoal = NewSpec,
    transition = T_SELECTIVE,
    newController = NewSpec,
    on_the_fly,
    selective_fine_grained
}
```

## mode ごとの mapping input

legacy DUCS と legacy O-DUCS は、既存の mapping environment を指定する形:

```fsp
mapping = MAP_ENV
```

または list form:

```fsp
oldEnvironment = {...}
newEnvironment = {...}
mapRelation = {...}
```

を使える。

FG-DUCS、FG-O-DUCS、Selective FG-DUCS、Selective FG-O-DUCS では list form が必須である。これらの mode で `mapping = ...` を書くと reject される。

legacy mode では、relation 内の reconfigure action は `reconfigure` でなければならない。FG/SFG mode では、各 mapping component はちょうど1つの reconfigure action を持つ。relation に `reconfigure` と書いた場合、内部で `reconfigure_<mapping component name>` に normalize される。relation に有効な `reconfigure_*` を明示してもよい。`reconfigure_others` は selective grouping 用に予約されているため、relation definition には書かない。

## Transition requirement で使う update action 名

legacy DUCS/O-DUCS の transition requirement では legacy update action を使う。

```text
stopOldSpec
reconfigure
startNewSpec
```

FG の transition requirement では、これらの legacy 名は使わない。生成名を使う。

```text
stopOldSpec_<old safety name>
reconfigure_<mapping component name>
startNewSpec_<new safety name>
```

Selective mode では、update kind ごとに legacy action か生成名/group action のどちらかを使える。同じ kind について、複数の transition requirement 全体で legacy 名と fine-grained 名を混ぜた場合は error にする。例えば `stopOldSpec` と `reconfigure_MAINTENANCE` は同時に使えるが、`stopOldSpec` と `stopOldSpec_P` は同時に使えない。

Selective mode では group action も参照可能である。

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

Selective mode で、ある update kind について individual reference も明示的な `*_others` reference も無い場合、その kind は内部的に legacy action 名へ collapse する。

Traditional DUCS では、safety-style の transition requirement を formula 変換する際に leading `[]` を外すようにした。これにより、Traditional DUCS と O-DUCS の `.lts` の書き方を近づけられる。

## 定数名

実装上の定数は次である。

```java
UpdateConstants.BEGIN_UPDATE = "hotSwapIn";
UpdateConstants.FINISH_UPDATE = "hotSwapOut";
```

`FINISH_UPDATE` は Java 定数名として残っているだけである。設計メモや論文では event 名として `hotSwapOut` を使う。
