# Stepwise DUCS 実装メモ（非 delayed stepwise）

最終更新: 2026-07-14

このメモは、現在の `stepwise` keyword で動く非 delayed の Stepwise 実装を、実装ファイルを基準に整理したものである。
`stepwise_delayed` 実装は別メモ `STEPWISE_DELAYED_DUCS_IMPLEMENTATION_MEMO_JA.md` に分ける。

論文上のStepwise DUCSは、通常の`stepwise_delayed + safetyBackwardPruning`構成を指す。
このファイルは、初期に実装された非 delayed `stepwise` 経路を理解・保守するためのメモとして扱う。
非 delayed `stepwise`は、論文の提案アルゴリズムおよび相対的完全性の証明対象に含めない。

## 対象ファイル

主な入口:

```text
maven-root/mtsa/src/main/java/ltsa/lts/UpdatingControllersDefinition.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerCompositeState.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseUpdatingControllerSynthesizer.java
```

関連実装:

```text
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseUpdatingControllerSafetySynthesizer.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseGoalClassifier.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseActionOwnership.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseFormulaSupport.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/environment/UpdatingEnvironmentGenerator.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerGRSynthesizer.java
```

`oldEnvironment` / `newEnvironment` / `mapRelation` の list から `StepwiseStage` と mapping environment を作る処理は、`UpdatingControllersDefinition` 側で行われる。
`StepwiseUpdatingControllerSynthesizer` は、すでに生成された stage list を受け取って処理する。

## 入力と制約

`stepwise` mode は stage list を必須とする。
各 stage は同じ index の old environment、新 environment、map relation から構成される。

```text
stage i:
  E_old_i
  E_new_i
  R_i
  MapE_i
```

`StepwiseUpdatingControllerSynthesizer.generateController(...)` の冒頭で、stage list が空なら fatal になる。

この mode では `.lts` の `oldController` は使われない。
実装はログで `oldController is ignored in initial stepwise mode` と出し、旧側の制約は stage ごとの old environment と old safety goal から局所的に合成する。

`oldGoal` と `newGoal` は次だけを想定している。

- safety
- controllable action set
- optional nonblocking

assume、guarantee、fault、buchi/liveness、concurrency/activity fluent、marking、disturbance、permissive、reachability、non-transient、exception handling、latency test は `validateSupportedGoal(...)` で fatal になる。

`safetyBackwardPruning` は `stepwise` とは併用できない。
入口側で、SBP は Traditional または `stepwise_delayed` 用として扱われる。

## Requirement 分類

`StepwiseGoalClassifier` が、次をまとめて分類する。

```text
G_old safety
G_new safety
transition requirements
```

分類は、formula が参照する fluent の initiating / terminating action を見て決める。
通常 action がどの stage の mapping environment alphabet に含まれるかを調べ、stage scope を作る。
`StepwiseActionOwnership`はすべてのmapping component alphabetを調べ、共有actionには
そのactionを含む全stageをownerとして登録する。分類scopeには参照actionの全ownerが入る。

分類規則:

- scope size が 1 なら local goal
- scope size が 2 以上なら cross goal
- 通常 action がなく update event だけを参照する goal は all-stage cross goal
- action がどの stage にも見つからない場合は fatal

stage 判定から除外される update action は、`hotSwapIn`、`stopOldSpec`、`reconfigure`、`startNewSpec`、`finishUpdate` と fine-grained prefix 系である。

old safety / new safety / transition は `StepwiseRequirementKind` で区別される。
分類後は次の bucket に入る。

```text
localOldSafety_i
localNewSafety_i
localTransition_i
crossGoals
```

## Formula の扱い

`StepwiseFormulaSupport.extract(...)` は assertion / constraint から leading temporal operator を外し、formula と fluent set を取り出す。

old safety には `stopFluent` guard が追加される。

```text
!stopFluent && originalFormula
```

new safety には `startFluent` guard が追加される。

```text
startFluent && originalFormula
```

transition requirement は追加 guard なしで扱う。

実装上、direct safety pruning では、formula が true になる state を「遷移を残さない state」として扱う。
該当 state 自体は一度 result に追加されるが、その state からの outgoing transition はコピーされず、その後 unreachable state が削除される。

## 全体フロー

非 delayed `stepwise` の主経路は次である。

```text
1. stage list と goal を検証する
2. G_old / G_new / G_T を local / cross に分類する
3. stage ごとに local old safety から OldCon_i を作る
4. OldCon_i と MapE_i から E_u_i を作る
5. stage local goal で E_u_i を direct safety pruning する
6. cross goal を overlap する scope ごとの component にまとめる
7. component ごとに local safetyEnv_i を owner-aware product する
8. 必要なら cross old controller restriction を合成して product する
9. component 内の cross requirement で direct safety pruning する
10. cross component と未使用 local safetyEnv_i を final product する
11. global DontDoTwice を入れる
12. final safetyEnv を GR(1) に渡す
```

## Stage local 処理

各 stage について、まず local goal を集める。

```text
localGoals_i =
  localOldSafety_i
  + localNewSafety_i
  + localTransition_i
```

次に `buildOldClosedLoop(...)` で `OldCon_i` を作る。

local old safety が空の場合:

```text
OldCon_i = E_old_i
```

local old safety がある場合:

```text
Controller_i = synthesize(E_old_i, localOldSafety_i)
OldCon_i = Controller_i || E_old_i
```

ここで使う controllable action は、`oldGoal` の controllable action set と `E_old_i` の alphabet の intersection である。
local old controller synthesis が losing なら fatal になる。

その後、stage の mapping environment を MTS に変換し、`UpdatingEnvironmentGenerator` で per-stage update environment を作る。

```text
E_u_i = UpdatingEnvironment(OldCon_i, MapE_i)
```

この `E_u_i` には、per-stage の update protocol と `hotSwapIn` などの更新用 action が含まれる。
非 delayed `stepwise` では、この時点で旧側閉ループと mapping environment を接続済みにしている。

## Local metaEnv と safetyEnv

全 stage の `E_u_i` を作った後、`globalUpdatingActions` を作る。
これは各 `E_u_i` の action alphabet の union である。

各 stage の local pruning 前に、存在しない global action を passive self-loop として足す。

```text
if action not in E_u_i:
  state --action--> state
```

その後、local goal に必要な fluent を集める。
action fluent については、環境の action alphabet に基づいて terminating action が補われる。

```text
localFluents_i = fluents(localGoals_i)
metaEnv_i = E_u_i || localFluents_i
```

実装では `ControllerUtils.removeTopStates(...)` を使って fluent product 相当の meta environment を作る。

`StepwiseUpdatingControllerSafetySynthesizer.pruneSafetyOnlyInPlace(...)` が local safety pruning を行う。
local pruning では、pre-`hotSwapIn` の controllable normal action を `.old` 付き action に relabel する処理が入る。

```text
pre hotSwapIn:
  a -> a.old   if a is controllable normal action
```

この処理は `makeOldActionsUncontrollable(...)` にあり、`beginFluent` が true ではない state を旧側として扱う。

非 delayed `stepwise` の direct pruning は、initial state が formula true でも、その場で fatal にはしない。
結果として outgoing transition が削除され、後続の GR(1) で losing になる可能性がある。

## Cross component

cross goal は、stage scope が overlap するものを同じ component にまとめる。

例:

```text
goal A scope {1,2}
goal B scope {2,3}

=> component scope {1,2,3}
```

component ごとに、scope 内の local safety environment を owner-aware product する。

```text
componentProduct = safetyEnv_i || safetyEnv_j || ...
```

product 後、component scope 外の stage が持つ real action は passive self-loop として足される。
これにより、後段の final product で scope 外 action による不必要な blocking を避ける。

## Cross old controller restriction

component 内に cross old safety goal が含まれる場合、非 delayed `stepwise` は別途 cross old controller restriction を作る。

処理は次である。

```text
1. component scope 内の E_old_i を合成対象にする
2. cross old safety goal だけを持つ ControllerGoal を作る
3. oldGoal の controllable action のうち scope 内 old alphabet にあるものを使う
4. GR synthesis で cross old controller を作る
5. controllable normal action を .old に relabel する
6. hotSwapIn 後は制約しない unrestricted state を追加する
7. componentProduct と cross old controller restriction を compose する
```

`disableControllerAfterBeginUpdate(...)` は、旧 controller restriction の各 pre-update state から `hotSwapIn` で unrestricted state に移る遷移を追加する。
unrestricted state では global action に self-loop を張り、`hotSwapIn` 後に cross old controller が mapping 側を制約し続けないようにしている。

## Cross safety pruning

cross component product に対して、component 内の cross requirement をまとめて direct pruning する。

```text
crossMeta = componentProduct || crossFluents
crossSafety = prune(crossMeta, componentGoals)
```

cross pruning では `makeOldActionsUncontrollable` は使わない。
local pruning で旧側 controllable action の `.old` 化は済んでいるためである。

## Final product

final product の入力は次である。

- cross component がある scope では、その component の `componentSafetyEnv`
- cross component に含まれなかった stage では、その stage の local `safetyEnv_i`

```text
globalSafetyEnv =
  product(crossComponentSafetyEnvs + uncoveredLocalSafetyEnvs)
```

ここでも owner-aware product を使う。
product 後、transition label に出ない action を alphabet から落とすため `trimActionsToTransitionLabels(...)` が呼ばれる。

その後、global DontDoTwice が追加される。
現在の `UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(...)` は、デフォルトでは `stopOldSpec` と `startNewSpec` を対象にする。

最後に `globalSafetyEnv` を CompactState に変換し、`UpdatingControllerGRSynthesizer.synthesizeGR(...)` に渡す。
composition が null なら losing として fatal になる。

## Owner-aware product

非 delayed `stepwise` の `composeProduct(...)` は、通常の MTSA composition ではなく、実装内の owner-aware product である。

基本方針:

- update action は、すべての input がその action を enabled している時だけ同期発火できる
- normal action は、その action を本来所有する input が enabled している時だけ発火できる
- action を所有しない input は、その action を alphabet に持たなければ同じ state に留まれる
- action を所有しない input でも、その action を alphabet に持つのに enabled していない場合は、その action は発火できない

normal action の owner 判定では、`.old` suffix を外した base action を `realActions` と照合する。

この product により、各 stage に passive self-loop を足しつつ、本来の owner が action を許可していない場合に勝手に進むことを防いでいる。

## 非 delayed stepwise に含まれないもの

この実装には、次は含まれない。

- `.lts` で与えた global old controller を delayed に接続する処理
- final mapping product 作成後の synchronized reachable pair 探索
- delayed `hotSwapIn` connection
- cost-guided staged cross scheduling
- `safetyBackwardPruning`
- `stepwise_delayed` 向けの CSV 計測群

これらは `stepwise_delayed` 側の実装メモで扱う。

## 実装上の注意

非 delayed `stepwise` は、各 stage で `E_u_i` を作る時点で旧側閉ループと mapping environment を接続する。
そのため、`stepwise_delayed` のように「まず mapping product を作り、後から old controller と delayed `hotSwapIn` で接続する」構造ではない。

また、cross old safety に対する扱いも異なる。
非 delayed `stepwise` は cross old controller restriction を component ごとに合成するが、`stepwise_delayed` の通常経路では global old controller は local / cross pruning の後、final mapping product との connection で初めて使われる。
