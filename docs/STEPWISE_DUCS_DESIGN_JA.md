# Stepwise DUCS 初期実装設計メモ

最終更新: 2026-06-26

このメモは、DUCS の分析空間を削減するために、DCS の段階的制御器合成に近い考え方を DUCS に取り入れる Stepwise DUCS の設計方針を記録する。対象はまず cross goal なし、incremental pruning なしの初期実装である。Traditional DUCS や O-DUCS の既存実装を壊さず、別クラス・別経路として実装する。

## 背景

Traditional DUCS は、すべての mapping environment を先に並列合成し、大きな mapping environment を作る。その後、指定された old controller と mapping environment を接続して `E_u` を作り、old safety、new safety、transition requirement から抽出した fluent を `E_u` と合成して `metaEnv` を作る。

`metaEnv` は状態数が最も大きくなりやすい。Traditional DUCS はこの `metaEnv` 上で old safety、new safety、transition requirement の違反状態を pruning し、さらに `stopOldSpec` と `startNewSpec` の DontDoTwice 制約を合成して `safetyEnv` を作る。その後、`hotSwapIn -> <> stopOldSpec`、`hotSwapIn -> <> reconfigure`、`hotSwapIn -> <> startNewSpec` を GR(1) で解く。

Stepwise DUCS の狙いは、mapping environment 全体を先に合成せず、stage ごとに小さな `E_u_i`、`metaEnv_i`、`safetyEnv_i` を作ってから product することで、特に `metaEnv` 構築時の状態爆発を抑えることである。

## 初期実装の対象範囲

初期実装では、問題を切り分けるために次を前提とする。

- cross old safety は未対応。
- cross new safety は未対応。
- cross transition requirement は未対応。
- incremental pruning は未対応。
- `fault`、`assume`、liveness goal は未対応。
- local stage では DontDoTwice を適用しない。
- product 後に global DontDoTwice を1回だけ適用する。

cross goal が検出された場合、初期実装では明示エラーにする。

## 構文

新しい専用構文は作らず、既存の `updatingController` に `stepwise` フラグを追加する。

```lts
updatingController UpdCont = {
    oldController = OldCon,
    oldEnvironment = {CHOCOLATE_OLD_1, CHOCOLATE_OLD_2},
    newEnvironment = {CHOCOLATE_NEW_1, CHOCOLATE_NEW_2},
    mapRelation = {R_CHOCOLATE_1, R_CHOCOLATE_2},
    oldGoal = DRILL_POLISH,
    newGoal = DRILL_PAINT,
    transition = T_NO_TP_1,
    transition = T_NO_TP_2,
    nonblocking,
    stepwise
}
```

`stepwise` フラグがある場合、Traditional DUCS ではなく Stepwise DUCS の合成経路に入る。初期実装では、`oldController = OldCon` が書かれていても Stepwise 合成では使わない。local old safety から stage ごとに old controller を内部合成する。`oldController` を無視したことは Output tab に明示する。

`oldEnvironment`、`newEnvironment`、`mapRelation` は同じ index の要素が同じ stage に対応する。

```text
stage 1: CHOCOLATE_OLD_1, CHOCOLATE_NEW_1, R_CHOCOLATE_1
stage 2: CHOCOLATE_OLD_2, CHOCOLATE_NEW_2, R_CHOCOLATE_2
```

## Requirement 分類

`oldGoal`、`newGoal`、`transition` に含まれる safety property を1つずつ分類する。ユーザーは従来と同じように複数 safety を列挙する。ただし各 safety は、local または cross に分類できる粒度まで分解済みであることを前提にする。

分類手順は次の通り。

1. safety formula が参照する fluent を集める。
2. fluent の initiating action と terminating action を展開する。
3. action 名が `.old` 付きなら、Traditional DUCS と同じように base action へ正規化する。
4. update event は stage 所属判定から除外する。
5. 残った通常 action がどの `MAP_E_i` の alphabet に含まれるか調べる。

分類規則は次の通り。

| 条件 | 判定 |
|---|---|
| 通常 action がすべて単一の `MAP_E_i` にだけ属する | stage `i` |
| goal 内の action が複数 stage に分散する | cross、初期実装では error |
| 1つの action が複数 `MAP_E_i` に含まれる | cross、初期実装では error |
| 通常 action がなく update event だけ | cross、初期実装では error |
| どの `MAP_E_i` にも含まれない通常 action がある | error |

`GOAL_SPANS_MULTIPLE_STAGES`、`GOAL_HAS_SHARED_ACTION`、`GOAL_UPDATE_EVENTS_ONLY`、`GOAL_ACTION_NOT_FOUND` のように、診断理由を区別して Output tab に出す。

## local old controller の内部合成

Stepwise 初期実装では、`.lts` に書かれた `oldController` は使わず、stage ごとに `C_OLD_i` を内部合成する。

local controllable action は次で作る。

```text
oldGoal.controllable ∩ alphabet(E_OLD_i)
```

local old safety がある stage では、次のように old closed-loop system を作る。

```text
Controller_i = synthesize(E_OLD_i, localOldGoal_i)
OldCon_i = Controller_i || E_OLD_i
```

DUCS の `E_u_i` に使うのは裸の `Controller_i` ではなく `OldCon_i` である。これは Traditional DUCS の `.lts` 例でも、合成された controller と environment を並列合成したものを `oldController` として渡しているためである。controller synthesis の結果は制御戦略であり、旧仕様下で実際に動く閉ループ系は `Controller_i || E_OLD_i` で表される。

local old safety がない neutral stage では、制限すべき old controllable action がないため、次のように扱う。

```text
OldCon_i = E_OLD_i
```

この場合も、`E_u_i` は `OldCon_i` と `MAP_E_i` を接続して作る。

## stage ごとの E_u_i 構築

各 stage について、既存の mapping environment 生成処理で `MAP_E_i` を作る。

```text
MAP_E_i = map(E_OLD_i, E_NEW_i, R_i)
```

その後、Traditional DUCS と同じ考え方で `OldCon_i` と `MAP_E_i` を接続して `E_u_i` を作る。

```text
E_u_i = UpdatingEnvironmentGenerator(OldCon_i, MAP_E_i)
```

`hotSwapIn` は `E_u_i` 生成時に追加される。`stopOldSpec` と `startNewSpec` は mapping 側状態に self-loop として追加される。`reconfigure` は map relation 由来で mapping environment に含まれる。

要求なしの neutral stage でも、raw `MAP_E_i` だけを product に入れず、必ず `OldCon_i` と `MAP_E_i` を接続した `E_u_i` を作る。これにより update event が全 stage で同期できる。

## local metaEnv_i と safetyEnv_i

各 stage で、分類された local old safety、local new safety、local transition requirement から fluent を集める。

```text
metaEnv_i = E_u_i || localFluents_i
```

その後、Traditional DUCS と同じ guard 意味で violation pruning を行う。

```text
old safety:
  !StopOldSpec && violation(old safety)

new safety:
  StartNewSpec && violation(new safety)

transition requirement:
  violation(transition requirement)
```

transition requirement は guard なしで常時評価する。

`.old` action の扱いは Traditional DUCS と同じにする。具体的には、`hotSwapIn` 前の controllable action は `.old` action 化され、fluent valuation では `.old` を base action として扱う。

local stage では DontDoTwice を適用しない。

```text
safetyEnv_i = prune(metaEnv_i, local safety formulas)
```

## product と global DontDoTwice

全 stage の `safetyEnv_i` を並列合成する。

```text
productSafetyEnv = safetyEnv_1 || ... || safetyEnv_n
```

その後、global に DontDoTwice を1回だけ適用する。

```text
globalSafetyEnv = DontDoTwice(productSafetyEnv)
```

DontDoTwice の対象は初期実装では Traditional DUCS と同じく次の2つである。

```text
stopOldSpec
startNewSpec
```

local で DontDoTwice を適用しない理由は、Traditional DUCS に近い baseline を作るためである。`stopOldSpec` と `startNewSpec` は global event として同期されるので、最終的な GR(1) 前の環境で1回だけ DontDoTwice を入れれば、2回実行は防げる。

local DontDoTwice は状態数・遷移数削減に効く可能性があるが、同じ monitor を stage 数だけ複製するコストもある。これは将来の評価用オプションとして扱う。

## GR(1)

`globalSafetyEnv` を既存の GR(1) 合成器に渡す。GR update goal は Traditional DUCS と同じ意味を使う。

```text
assumption:
  hotSwapIn

guarantees:
  stopOldSpec
  reconfigure
  startNewSpec
```

update event の controllable / uncontrollable の扱いも Traditional DUCS と同じである。

```text
controllable:
  stopOldSpec
  reconfigure
  startNewSpec

uncontrollable:
  hotSwapIn
```

## 実装クラス案

既存 Traditional DUCS 実装は壊さず、Stepwise 用 package/class を追加する。

```text
ltsa.updatingControllers.stepwise.StepwiseUpdatingControllerSynthesizer
ltsa.updatingControllers.stepwise.StepwiseUpdatingControllerSafetySynthesizer
ltsa.updatingControllers.stepwise.StepwiseGoalClassifier
ltsa.updatingControllers.stepwise.StepwiseStage
ltsa.updatingControllers.stepwise.StepwiseSynthesisResult
ltsa.updatingControllers.stepwise.StepwiseMetricsLogger
```

`UpdatingControllerSynthesizer` または `UpdatingControllersDefinition` の入口で `stepwise` フラグを見て、Traditional と Stepwise を分岐する。

`StepwiseUpdatingControllerSafetySynthesizer` は `UpdatingControllerSafetySynthesizer` とは別クラスにする。中身は Traditional と同じ考え方を再利用するが、local stage では DontDoTwice を行わない `pruneSafetyOnly` 相当の処理が必要である。

必要な処理は次の通り。

```text
makeOldActionsUncontrollable 相当
buildValuations 相当
valuateSafety 相当
applySafetyInEnvironment 相当
```

既存 `UpdatingControllerSafetySynthesizer.synthesizeSafety` は `.old` 化、valuation、pruning、DontDoTwice まで一体化しているため、そのまま local stage に使うと local DontDoTwice が入ってしまう。Stepwise 初期実装では、DontDoTwice を product 後に1回だけ行うため、別クラスで分離する。

## Output tab

Stepwise 実行時は Output tab に次を出す。

```text
[Stepwise DUCS]
oldController is ignored in initial stepwise mode: OldCon

Requirement classification:
  P_OLD_TOOL_ORDER_1 -> stage 1
  P_OLD_TOOL_ORDER_2 -> stage 2
  ...

Stage 1:
  local old safety: ...
  local new safety: ...
  local transition: ...
  E_u states/transitions
  metaEnv states/transitions
  safetyEnv states/transitions

Stage 2:
  ...

Product:
  safetyEnv product states/transitions
  after global DontDoTwice states/transitions

GR(1):
  winning / losing
  output controller states/transitions
```

失敗時は理由を区別して出す。

- unsupported goal kind。
- cross goal detected。
- goal action not found。
- stage old controller synthesis failed。
- stage initial state pruned。
- product composition failed。
- global DontDoTwice failed。
- GR(1) losing。

## Chocolate 2 robots での初期検証

最初の検証対象は `MODEL/StepwiseDUCS/ChocolateExample2Robots.lts` とする。

この例では old safety と new safety が robot 1 / robot 2 にきれいに分かれている。したがって、`DRILL_POLISH` から分類される local old safety によって内部合成される `OldCon_1 || OldCon_2` は、既存の `OldCon` と挙動として同等になることが期待できる。ただし、状態番号や LTS の形が完全一致することまでは期待しない。

以前の `UpdCont_1 || UpdCont_2` は local controller を先に GR(1) 合成してから product していたため、Traditional `UpdCont` と挙動が一致しなかった。Stepwise 初期実装では local controller ではなく local safetyEnv を product し、その後に1回だけ GR(1) を解くため、この問題を避ける。

検証では、状態数の完全一致ではなく、次を見る。

- requirement 分類が期待通りになるか。
- local `C_OLD_i` が内部合成できるか。
- `E_u_i`、`metaEnv_i`、`safetyEnv_i` が作れるか。
- product 後の GR(1) が winning になるか。
- 出力 controller が old/new safety と transition requirement を満たすか。

## 今後の拡張: incremental pruning

incremental pruning は、すべての fluent を一度に `E_u` と合成して大きな `metaEnv` を作る代わりに、要求または fluent 依存 cluster ごとに次を繰り返す方式である。

```text
currentEnv
  || fluents required by requirement cluster
  -> metaEnv_k
  -> prune by requirement cluster
  -> safetyEnv_k
```

例:

```text
E_u_1
  -> fluent_A + G_OLD_A
  -> prune
  -> safetyEnv_A
  -> fluent_B + G_OLD_B
  -> prune
  -> safetyEnv_B
  -> fluent_C + {G_NEW_A, G_NEW_B, T_1}
  -> prune
  -> safetyEnv_C
```

この方式の利点は、早い段階で pruning できれば、その後に合成する fluent の積空間を小さくできることである。

ただし、前段で合成した fluent による状態分割が後段にも残る可能性がある。早い pruning が弱い場合、一括方式より overhead が大きくなることもあり得る。将来的には pruning 後の安全な minimization も検討対象になる。

incremental pruning を入れる場合も、`.old` 化は最初に1回だけ行い、DontDoTwice は最後に1回だけ行うのが自然である。既存 `synthesizeSafety` をそのまま繰り返すのは避ける。

評価上は、次の3つを分けて比較できるとよい。

```text
Traditional DUCS:
  global all-at-once

Global incremental DUCS:
  global E_u 上で incremental pruning

Stepwise incremental DUCS:
  stage ごとに incremental pruning + product
```

これにより、incremental pruning 自体の効果と、stage 分割の効果を分けて評価できる。

## 今後の拡張: cross goal

cross goal は、複数 stage の action にまたがる safety / transition requirement、または1つの action が複数 `MAP_E_i` に含まれて local stage を一意に決められない requirement である。

cross old safety、cross new safety、cross transition requirement は、local safetyEnv product 後に cross fluent を合成し、global pruning する方式が考えられる。

```text
localProductSafetyEnv
  || crossFluents
  -> crossMetaEnv
  -> cross pruning
  -> globalSafetyEnv
```

この方式では、`safetyEnv_all` の old 領域は `C_OLD_1 || ... || C_OLD_n` 相当である。そこに `G_OLD_cross` の fluent を重ねて violation pruning すると、old 領域にも cross old safety が課される。その後、`safetyEnv_cross` を GR(1) で解く過程で controllable / uncontrollable を考慮した losing の逆伝搬が起きる。したがって、cross old safety を単に pruning して終わりにするのではなく、最終 GR(1) の game solving まで含めて扱うなら、cross old safety も Stepwise DUCS 内で扱える可能性がある。

これは段階的制御器合成の構造に近い。

```text
C_OLD_i = synthesize(E_OLD_i, G_OLD_i)
C_OLD_all = C_OLD_1 || ... || C_OLD_n
C_OLD_all に G_OLD_cross を課して最終 game solving で losing を伝搬
```

段階的制御器合成の同等性定理の前提が満たされるなら、global old environment 上で `G_OLD_1 && ... && G_OLD_cross` を一括で解く場合との同等性が期待できる。ただし、初期実装ではこの cross phase は扱わず、cross goal を明示エラーにする。

### scope-based staged cross pruning

cross goal をすべて `safetyEnv_all` 上で一括処理する必要はない。各 goal が依存する stage 集合を計算すれば、必要な stage だけを段階的に product して pruning できる。

```text
scope(G) = { i | G の fluent action が MAP_E_i に関係する }
```

例えば、`G_12` が stage 1 と stage 2 の action だけを使うなら、次のように処理できる。

```text
S12_base = safetyEnv_1 || safetyEnv_2
M12 = S12_base || fluents(G_12)
S12 = prune(M12, G_12)
```

さらに `G_23` が stage 2 と stage 3 の action を使う場合、最初から `{1,2,3}` を一括 product するだけでなく、先に作った `S12` に stage 3 を足して処理することも考えられる。

```text
S123_base = S12 || safetyEnv_3
M23 = S123_base || fluents(G_23)
S123 = prune(M23, G_23)
```

このように、必要な stage を順に足しながら、その都度 cross fluent 合成と pruning を行う方式を scope-based staged cross pruning と呼ぶ。これは connected component を最初から一括で product するより、中間状態数を小さくできる可能性がある。

ただし、処理順序によって中間状態数や計算時間が変わる。将来的には次のような順序戦略が議論点になる。

- `.lts` の指定順。
- scope が小さい goal から処理する。
- 現在の component と重なりが大きい goal から処理する。
- 推定 product size が小さい順に処理する。

初期実装では、ここまで一般化せず、まず cross なし baseline を実装する。

### global incremental fallback

別案として、cross がある場合に stagewise path ではなく global incremental fallback に切り替えることも考えられる。

議論中の fallback 案は次である。

```text
cross がある場合:
  MAP_E_1 || ... || MAP_E_n を合成
  指定 oldController と global mapping environment から E_u を作る
  global E_u 上で incremental pruning
  global DontDoTwice
  GR(1)
```

これは厳密な stagewise reduction ではなく、Traditional DUCS の metaEnv 構築を incremental pruning に置き換える fallback である。cross old safety 問題を避けられる一方、stage 分割の効果とは別の手法になるため、評価では区別する必要がある。

### delayed E_u connection / fluent-aware safetyEnv

別の研究案として、指定された global old controller をそのまま使い、`E_u_i` を先に作らずに mapping 側だけで `safetyEnv_i` や `safetyEnv_cross` を作った後、最後に old controller と接続する方式も考えられる。

この方式の狙いは、`UpdCont` の仕様で指定された old controller が `E_OLD_all` と `G_OLD_all` から合成済みであることを利用し、local old controller の内部合成や cross old safety の扱いを避けることである。

ただし、Traditional DUCS の `E_u` は単なる product ではなく、次の対応を作っている。

```text
old controller の状態
  --hotSwapIn-->
mapping environment の旧側状態
```

したがって、mapping 側の `safetyEnv_cross` を後から old controller に接続するには、`safetyEnv_cross` の旧側状態と old controller 側状態を対応付ける必要がある。さらに、mapping 側で fluent を合成・pruning した後に接続するなら、old controller 側にも同じ fluent を合成し、fluent valuation が一致する状態同士を接続する必要がある。

概念的には次のようになる。

```text
1. MAP_E_i 側から safetyEnv_i / safetyEnv_cross を作る
   ただし state ごとに元の mapping state と fluent valuation を保持する

2. 指定 oldController に同じ fluent set を合成する
   old controller 側でも old environment state と fluent valuation を保持する

3. hotSwapIn 接続を張る
   条件:
     old environment state が対応する
     fluent valuation が一致する
```

このためには、既存 `MTS<Long, String>` の型を変えるのではなく、Stepwise 内部で fluent 情報付きの wrapper を使うのが望ましい。

```text
StepwiseSafetyEnv:
  MTS<Long, String> env
  Set<Fluent> trackedFluents
  FluentStateValuation<Long> fluentValuation
  origin mapping state / source stage states
```

既存 GR(1) には `env` だけを渡し、delayed connection や valuation matching が必要な場面では wrapper 内の valuation / origin 情報を使う。

この delayed connection 方式は有望だが、`E_u` の接続意味を fluent valuation 付きで再現する必要があり、初期実装には重い。現時点の本筋は、まず stage ごとに `E_u_i` を作り、`safetyEnv_i` を作ってから product し、その後 cross phase を追加する方向とする。

## 今後の拡張: local DontDoTwice

初期実装では DontDoTwice は product 後に global で1回だけ入れる。

将来的には、各 `safetyEnv_i` に local DontDoTwice を入れる評価用オプションも考えられる。

```text
local:
  safetyEnv_i -> DontDoTwice -> localSafetyEnv_i

global:
  productSafetyEnv -> DontDoTwice -> globalSafetyEnv
```

local DontDoTwice は、product 前に `stopOldSpec` / `startNewSpec` の2回目以降を削れるため、遷移数や状態数を減らす可能性がある。一方で、同じ monitor を stage 数だけ複製するため、冗長な履歴成分が増える可能性もある。これは例題依存なので、初期実装後に比較対象として追加する。

## まとめ

初期実装では、まず cross なし、incremental pruning なし、global DontDoTwice 1回の Stepwise DUCS baseline を作る。この baseline で、local safetyEnv product 後に GR(1) を1回だけ解く構造を確認する。その後、incremental pruning、cross goal handling、local DontDoTwice を順に拡張・評価する。
