# Stepwise Delayed DUCS 実装メモ

最終更新: 2026-07-22

このメモは、現在の `stepwise_delayed` keyword で動く Stepwise DUCS 実装を、実装ファイルを基準に整理したものである。
非 delayed の `stepwise` 実装は `STEPWISE_DUCS_IMPLEMENTATION_MEMO_JA.md` に分ける。

論文上の説明では、通常の `stepwise_delayed + safetyBackwardPruning` 構成をStepwise DUCSとして扱う。
このメモでは、実装上の flag、class、property を指す場合に `stepwise_delayed` と書く。

## 対象ファイル

主な入口:

```text
maven-root/mtsa/src/main/java/ltsa/lts/UpdatingControllersDefinition.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerCompositeState.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/delayed/StepwiseDelayedUpdatingControllerSynthesizer.java
```

関連実装:

```text
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseGoalClassifier.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseActionOwnership.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseFormulaSupport.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseUpdatingControllerSafetySynthesizer.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/SafetyBackwardPruner.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java
maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java
```

保留SBPの主な単体テスト:

```text
maven-root/mtsa/src/test/java/ltsa/updatingControllers/synthesis/SafetyBackwardPrunerTest.java
maven-root/mtsa/src/test/java/ltsa/updatingControllers/stepwise/StepwiseActionOwnershipTest.java
```

`oldEnvironment` / `newEnvironment` / `mapRelation` の list から stage と mapping environment を作る処理は `UpdatingControllersDefinition` 側で行われる。
`StepwiseDelayedUpdatingControllerSynthesizer` は、生成済みの `StepwiseStage` list と `.lts` で与えた old controller を受け取る。

## 入力と mode option

`stepwise_delayed` は stage list と old controller を必須とする。
`generateController(...)` の冒頭で、stage list が空、または old controller が null の場合は fatal になる。

goal の対応範囲は非 delayed `stepwise` と同じである。
`oldGoal` と `newGoal` は safety、controllable action set、optional nonblocking のみを想定する。
assume、guarantee、fault、buchi/liveness、concurrency/activity fluent、marking、disturbance、permissive、reachability、non-transient、exception handling、latency test は fatal になる。

`.lts` 側の主な option:

```text
stepwise_delayed
safetyBackwardPruning
incrementalPruning
incrementalPruningCleanup
```

`incrementalPruning` / `incrementalPruningCleanup` は実装に残っている変種である。
このメモの主対象は、現在の通常の `stepwise_delayed + safetyBackwardPruning` 経路、つまり local goal を stage ごとにまとめ、cross goal を staged cross component で処理し、部分断片では保留SBPを行う経路である。
論文ではこの構成全体を Stepwise DUCS と呼び、SBPを必須処理として扱う。

Java system property:

```text
-Dstepwise.delayed.costGuidedCrossScheduling=true|false
-Dstepwise.delayed.indexedHotSwapInConnection=true|false
```

デフォルトは次である。

```text
costGuidedCrossScheduling = true
indexedHotSwapInConnection = false
```

したがって、通常経路では cross goal scheduling は cost-guided、hotSwapIn connection は既存の legacy 探索で動く。
indexed hotSwapIn connection はオプションとして残っており、明示的に property を true にした時だけ使われる。

## 全体フロー

通常の `stepwise_delayed` 経路は次である。

```text
1. stage list、old controller、goal を検証する
2. GR(1) 入力 safetyEnv 構築時間の timer を開始する
3. G_old / G_new / G_T を local / cross に分類する
4. cross goal から overlap scope ごとの cross component を作る
5. stage ごとに MapE_i を準備する
6. local scope で metaEnv_i / safetyEnv_i を作り、明示的Errorから保留SBPを行う
7. cross component 内で staged cross pruning と保留SBPを行う
8. cross component と未使用local fragmentをfinal mapping productする
9. 全component上の通常SBPで保留されたError境界を確定する
10. old controller を tracked fluent 付き oldMeta にする
11. oldMeta と mapping product の synchronized reachable pair から delayed hotSwapIn connection を作る
12. old controller 側 normal controllable action を .old に relabel し、mapping product と接続する
13. global DontDoTwice を追加する
14. 接続後の final SBP を行う
15. final safetyEnv を GR(1) に渡す
16. winning strategy から update controller を得る
```

## Requirement 分類

分類は `StepwiseGoalClassifier` が担当し、非 delayed `stepwise` と同じ実装を使う。

対象:

```text
oldGoal safety
newGoal safety
transition requirements
```

分類は、formula から取り出した fluent の initiating / terminating action を使って行う。
`StepwiseActionOwnership`は、各 action がどの stage の mapping environment alphabet に属するかを調べ、`action -> owner stage集合`を作る。
共有actionは、そのactionを含むすべてのmapping componentをownerとして登録する。
分類scopeは、formulaに含まれる通常actionの全ownerの和集合である。

分類規則:

- scope size が 1 なら local goal
- scope size が 2 以上なら cross goal
- 通常 action がなく update event だけを参照する goal は all-stage cross goal
- action がどの stage にも見つからない場合は fatal

update action は stage 判定から除外される。

## update actionを含む要求のscope判定とreconfigureの扱い

### 論理的なcomponent scopeの定義

更新事象をcomponent scopeへ加えない理由は、「現在の実装がupdate actionを除外しているから」ではなく、component scopeが表す依存関係と更新事象が表す依存関係を分けて定義できるからである。

全体状態を概念的に次のように表す。

```text
x = (x_1, x_2, ..., x_n, q_U)
```

ここで、`x_i`はmapping component `i`の局所状態、`q_U`は`stopOldSpec`、`reconfigure`、`startNewSpec`などの発生履歴を保持する共通更新モニタの状態である。
`q_U`はmapping componentの一つではなく、更新プロトコルを観測する大域的な状態として扱う。

requirement `g`のcomponent scope `S`は、`g`の真偽を決めるために局所状態を知る必要があるmapping componentの集合と定義する。
形式的には、任意の二つの全体状態`x`と`y`について、

```text
x|_S = y|_S
and
q_U(x) = q_U(y)
```

ならば、

```text
g(x) = g(y)
```

となるとき、`g`はcomponent scope `S`で判定可能である。
言い換えると、`g`への依存を次の二種類に分ける。

```text
component依存:
  通常fluent/actionの真偽を決める局所component状態

update依存:
  共通更新モニタq_Uが保持する更新事象・phase履歴
```

更新事象をcomponent scopeから除外することは、更新事象をformulaから削除したり、違反判定で無視したりすることではない。
部分空間も必要な更新モニタを保持し、formula評価には更新事象の値を使う。
除外するのは、更新事象を理由としてmapping componentをcomponent scopeへ追加する処理だけである。

この定義により、通常条件`V_i`がcomponent `i`だけに依存するなら、次の式はいずれもcomponent scope `{i}`を持つ。

```text
!Stop && V_i
Start && V_i
ReconfigureAction && V_i
Stop && !Start && Action_i
```

更新事象またはphase fluentの値は共通更新モニタから得られ、`V_i`または`Action_i`の値だけがcomponent `i`の局所状態に依存するためである。

一方、更新事象だけを参照するrequirementは、論理的なcomponent scopeとしては空集合であり、共通更新モニタだけから真偽を決められる。
ただし段階的構築では所属させるlocal componentがなく、全体同期後の更新プロトコル上で判定する必要があるため、実行上はall-stage goalとして最後に配置する。
all-stageという配置は「formulaの真偽が全componentの局所状態に依存する」という意味ではなく、空のcomponent scopeを大域的な更新プロトコル判定へ割り当てるための保守的な処理である。

### formulaの判定scopeと事象の同期scopeを分ける

更新事象については、次の二つを区別する必要がある。

```text
formula scope:
  requirementの真偽を決めるために必要なcomponent集合

synchronization scope:
  その事象が全体遷移として実行可能かを決めるcomponent集合
```

`stopOldSpec`と`startNewSpec`では、各mapping componentが同じphase事象を受け入れるため、formula判定と全体同期の分離は直接的である。
`reconfigure`では、formulaがlocalでも、全体遷移としての実行可能性は各componentのrelation状態に依存する。
したがって、`reconfigure`をcomponent scopeから除外する論拠は「常に実行可能だから」ではなく、部分遷移と全体遷移の射影関係、および`reconfigure`の制御可能性にある。

scope `S`の部分状態で、

```text
s_S --reconfigure--> t_S
```

がformulaの直接違反へ到達するとする。
全componentを含む全体状態へ拡張したとき、scope外componentが`reconfigure`を無効にすれば、対応する全体遷移は存在しない。
全componentが`reconfigure`を有効にすれば、全体遷移をscope `S`へ射影した遷移は上のlocal遷移になり、formulaの真偽は`S`と共通更新モニタだけで決まるため、全体遷移先でも同じ直接違反になる。

したがって、部分空間で危険と判定した`reconfigure`は、全体空間では次のいずれかになる。

1. 後続componentとの同期が成立せず、全体には存在しない遷移。
2. 同期が成立し、全体でも同じlocal違反を生じる遷移。

さらに`reconfigure`がcontrollableなら、部分段階では危険なsource/action groupを禁止でき、遷移元状態そのものをlosingとして確定する必要はない。
このため、同期可否が未確定でも、local違反を生む`reconfigure`を早期除去できる。

この説明は、次の論理的条件に依存する。

- formulaの直接違反が、scope `S`の局所状態と共通更新モニタの値だけで確定する。
- 全体の`reconfigure`遷移を`S`へ射影すると、部分空間の`reconfigure`遷移になる。
- scope外componentは全体遷移を無効化できるが、scope `S`側の遷移先やformula valuationを変更しない。
- `reconfigure`がcontrollableであり、危険な遷移を禁止できる。

最後の条件を満たさず`reconfigure`がuncontrollableなら、部分空間だけに存在して全体には存在しない遷移からlosingを誤って逆伝搬する可能性がある。
その場合は、同期に関係するcomponentをscopeへ含めるか、同期可否が確定するまで判定を保留する別の論証が必要になる。

### 論理的条件と現在実装の対応

前節はscope除外の論理的な根拠であり、以下はその前提を現在の実装がどのように実現しているかの対応である。
「実装がこの形だから正しい」のではなく、前節で置いた論理的条件を実装が満たしているかを確認するために用いる。

| 論理上の役割・条件 | 現在実装での対応 |
|---|---|
| 共通更新モニタ`q_U` | `UpdatingControllersUtils`の`stopFluent`、`reconFluent`、`startFluent`が更新phase履歴を保持する。各fluentは対応する更新事象でtrueになり、`hotSwapIn`でresetされる。 |
| old/new safetyのphase guard | `StepwiseFormulaSupport.extract(...)`がold safetyへ`!stopFluent`、new safetyへ`startFluent`を付加する。transition requirementは記述されたformulaをそのまま使う。 |
| action propositionの観測 | formula中のaction propositionから作ったfluentは、`completeActionFluentTerminatingActions(...)`により、合成alphabet中の`tau`を除くinitiating action以外の事象で終了する形へ補完される。 |
| component scopeの計算 | `StepwiseGoalClassifier`が各fluentのinitiating/terminating actionを走査し、通常actionの全ownerの和集合を作る。update actionはowner集合へ加えず、通常ownerが空なら`GOAL_UPDATE_EVENTS_ONLY`としてall-stageへ配置する。 |
| 各部分空間での更新phase観測 | local/cross metaEnvのtracked fluentへ`stopFluent`、`reconFluent`、`startFluent`を追加し、部分空間でも更新モニタのvaluationを保持する。 |
| stop/startの共通受理 | `addMappingRegionUpdateSelfLoops(...)`が全raw mapping状態へ`stopOldSpec`と`startNewSpec`のself-loopを追加する。self-loopなのでraw mapping状態は変えないが、fluent product後のphase valuationは変わり得る。 |
| reconfigureの局所射影 | `MappingEnvironmentGenerator`が各relation ruleを`pre actions; reconfigure; post actions`の経路として生成する。必要な中間状態も各mapping component内に保持される。 |
| reconfigureの大域同期 | owner-aware delayed productの`hasRealOwnerEnabled(...)`と`targetChoices(...)`は、update actionについて現在の全inputに有効な遷移がある場合だけproduct遷移を生成する。いずれかのfragmentが無効なら全体遷移を生成しない。 |
| reconfigureの制御可能性 | `UpdatingControllersDefinition.generateUpdatingControllableActions(...)`がold/new goalのcontrollable集合に`stopOldSpec`、`startNewSpec`、`reconfigure`を追加する。 |
| 危険な制御可能遷移の早期除去 | `SafetyBackwardPruner.pruneDeferred(...)`はErrorへ到達するcontrollableなsource/action group全体を除去するが、そのsourceをuncontrollable predecessorとしてlosingへ逆伝搬しない。 |
| legacy共有reconfigureへの限定 | legacy relationではaction名を`reconfigure`へ限定する。`stepwise_delayed`は`fine_grained`および`selective_fine_grained`との同時指定を拒否する。 |

実装上、scope分類とformula評価は次の順で行われる。

```text
1. old/new/transition requirementからformulaとfluentを抽出する。
2. old/new safetyなら共通phase guardをformulaとfluent集合へ追加する。
3. fluentが参照する通常actionのownerだけからcomponent scopeを計算する。
4. update actionだけならall-stageへ配置する。
5. 各local/cross mappingへ必要なformula fluentと共通phase fluentを合成する。
6. formulaの直接違反をErrorとして記録する。
7. controllableな危険遷移をsource/action group単位で除去し、後続productへ渡す。
8. product時にはupdate actionを全inputで同期し、無効な全体遷移を生成しない。
```

この対応により、前節の「formulaの真偽はcomponent scopeと共通更新モニタから決まる」「scope外componentはreconfigureを無効化できるがlocal遷移先を変更しない」「危険なreconfigureを制御器が禁止できる」という条件を現在の主経路が実現している。
将来、phase fluentの定義、update actionの同期規則、`reconfigure`の制御可能性、またはfine-grained modeとの組合せを変更する場合は、単にclassifierの除外集合を維持するだけでは不十分であり、前節の論理的条件から再検証する必要がある。

### scope計算上の共通点と遷移意味の相違

`stopOldSpec`、`startNewSpec`、`reconfigure`は、いずれもupdate actionとして通常actionのowner計算から除外される。
したがって、これらとlocalな通常fluent/actionを同時に参照する式は、残った通常actionのownerが一stageだけならlocal goalに分類される。
一方、通常actionを一つも参照しないupdate-onlyの式は、論理的なcomponent scopeは空であるが、段階的構築上はall-stage cross goalに配置する。

ただし、scope計算から同様に除外できることと、mapping environment上で同じ遷移として扱えることは別である。

| 性質 | `stopOldSpec` / `startNewSpec` | `reconfigure` |
|---|---|---|
| raw mapping上の遷移 | 全mapping状態へ追加するself-loop | relationから生成される実遷移 |
| mapping状態への依存 | 現在のraw mapping状態によらず実行可能 | relationの始点・中間状態でだけ実行可能 |
| raw mapping状態 | self-loopなので変えない | OLD側からNEW側または中間状態を経て変える |
| fluent valuation | 対応するphase fluentを変更し得る | relation遷移とreconfigure関連fluentを変更し得る |
| 後続componentによる同期阻止 | raw mapping self-loop追加後は起こらない | 他componentが現在状態で無効なら全体遷移は存在しない |

したがって、`reconfigure`を`stopOldSpec`や`startNewSpec`と同じ全状態self-loopとして追加してはならない。
owner-aware productでは、update actionについて現在合成する全fragmentがそのactionを有効にしている場合だけ同期させる。

### stopOldSpec / startNewSpecを含むlocal式

old safetyとnew safetyへ自動追加される違反guardは概念的に次である。

```text
old violation = !Stop && V_old
new violation = Start && V_new
```

`Stop`と`Start`は全mapping componentで同じ同期履歴を追跡するphase fluentである。
`V_old`または`V_new`が参照する通常fluentの全ownerがscope `S`に含まれるなら、式全体の真偽はscope `S`だけで確定する。
scope外componentを後から合成しても、scope `S`で確定した直接違反は安全状態へ変わらない。

T1をcomponentごとに次の違反条件として書く場合も同じである。

```text
Stop && !Start && AnyControllableAction_i
```

`Stop`と`Start`は共通phase履歴、`AnyControllableAction_i`はcomponent `i`の通常action propositionなので、各T1はstage `i`のlocal scopeで判定できる。
これに対し、`startNewSpec`、`stopOldSpec`、`reconfigure`だけからなるT2はupdate-onlyなのでall-stageで判定する。

### reconfigureとlocal条件を含む式

例えば次のtransition requirementを考える。

```text
[](reconfigure -> !LOCAL_BAD)
```

違反条件は概念的に次である。

```text
ReconfigureAction && LOCAL_BAD
```

`LOCAL_BAD=true`だけでは要求違反ではない。
`reconfigure`が発生した時点で`LOCAL_BAD`もtrueである場合にだけ違反となる。

式中でaction propositionとして直接参照した`reconfigure`はaction-fluentへ変換され、reconfigure直後から次の別事象までtrueになる。
一方、内部のpersistentな`reconFluent`や、LTSで明示定義したpersistent fluentとは区別する必要がある。
`reconfigure`が`LOCAL_BAD`のinitiating/terminating actionでなければ、reconfigure時の`LOCAL_BAD`は直前のaction履歴を保持する。
この場合、上の式は「`LOCAL_BAD`である状態からreconfigureを実行してはならない」という更新開始条件を表す。

`LOCAL_BAD`の値とrelation後のraw状態名が一致することは、この更新開始条件をlocal判定するための必要条件ではない。
要求の正式な意味はfluent monitorの値で決まる。
relation後の現在状態がBADかどうかを要求したい場合だけ、更新後状態を表すfluentを別に設計し、relation中の事象列でその値を適切に更新する必要がある。

前節の論証をこの式へ適用すると、component scopeは`LOCAL_BAD`を決める通常actionのowner集合になる。
`ReconfigureAction`は共通更新モニタから得られるため、その事象だけを理由としてcomponentを追加しない。
後続componentが同期を阻止した遷移は全体に現れず、同期が成立した遷移では`ReconfigureAction && LOCAL_BAD`というlocal違反が全体にも残る。

現在のlegacy DUCSでこの論理的条件に対応する前提は次である。

- 論文対象のlegacy共有`reconfigure`を使う。
- `reconfigure`は全mapping fragmentで同期する。
- `reconfigure`はcontrollable actionである。
- 式が参照する通常fluent/actionの全ownerをformula scopeへ含める。
- update actionだけを参照する式はall-stageにする。

`reconfigure`をuncontrollableに変更する場合、またはcomponentごとに異なるfine-grained reconfigurationへ拡張する場合には、この結論をそのまま適用しない。

### 2026-07-22の診断検証

production実装を変更せず、一時領域に二componentの最小fixtureを作って検証した。

- stage 1は`LOCAL_BAD`を持ち、SAFE/BADの両OLD状態から対応するNEW状態へ`reconfigure`できる。
- stage 2はrelation中の`gate.prepare`後の中間状態でだけ`reconfigure`できる。
- transition requirementは`[](reconfigure -> !LOCAL_BAD)`である。
- TraditionalとStepwiseには同一のold/new environment、relation、goal、transition requirementを与えた。

Stepwiseの分類とlocal pruningは次のとおりだった。

```text
T_LOCAL_SAFE_AT_RECONFIGURE (TRANSITION) -> stage 1

stage 1 mapping meta:       24 states / 128 transitions
direct safety後:            24 states / 108 transitions
explicit Error seeds:       4
removed controllable groups: 4
deferred SBP後:             20 states / 104 transitions

stage 2 local requirement:  none
stage 2 Error seeds:        0
```

部分productと後続合成について、次を確認した。

- `gate.prepare`前は、stage 1にlocalな`reconfigure`遷移があっても全体では同期不能である。
- BADかつprepare済みの状態では、危険な`reconfigure`は除去されている。
- SAFEかつprepare済みの状態では、安全な`reconfigure`が残る。
- BAD状態から`local.leaveBad`で回復してから更新できる。
- 早期SBPで除去した危険な`reconfigure`をstage 2との合成が復活させない。

full pipelineではTraditionalとStepwiseがともにdeterministic GR(1)でwinningとなった。
生成controllerの全到達状態を、`LOCAL_BAD`と`gate.prepare`の履歴を付けて走査し、両手法についてprepare前またはBAD時の`reconfigure`が0、安全な到達可能`reconfigure`が存在し、BADかつprepare済み状態からの回復動作も存在することを確認した。

この値はscopeと遷移除去機序の小規模診断結果であり、論文の性能評価値として用いない。
また、このfixtureの成功から、任意のrelationに対するcontroller同一性、trace同一性、最大許容性、または両手法の一般的な合成可否関係までは主張しない。

## Formula と phase guard

`StepwiseFormulaSupport.extract(...)` は、assertion / constraint から leading temporal operator を外し、formula と fluent set を取り出す。

old safety:

```text
!stopFluent && originalFormula
```

new safety:

```text
startFluent && originalFormula
```

transition requirement:

```text
originalFormula
```

direct safety pruning では、formula が true の state から outgoing transition をコピーしない。
`stepwise_delayed` では、direct pruning 対象の initial state が formula true の場合、その時点で fatal になる。

SBP が有効な場合、`SafetyBackwardPruner` の結果で initial state が losing でも fatal になる。

## Stage base の準備

各 stage について、`StepwiseStage` が持つ mapping environment を MTS に変換する。
同時に、mapping state metadata を `DelayedEnv` 用の形に変換する。

metadata は stage ごとに次を保持する。

```text
OLD_SIDE
NEW_SIDE
INTERMEDIATE
oldEnvState
newEnvState
```

これは後段の delayed `hotSwapIn` connection で、mapping product state が全 stage について `OLD_SIDE` かどうかを判定するために使われる。

mapping region には `stopOldSpec` と `startNewSpec` の self-loop を追加する。

```text
state --stopOldSpec--> state
state --startNewSpec--> state
```

`reconfigure` は mapping relation から作られた遷移として存在するため、ここでは self-loop を追加しない。

`globalActions` は次から作る。

```text
oldController actions
+ stopOldSpec
+ startNewSpec
+ all MapE_i actions
- hotSwapIn
```

各 stage の `realActions` は、passive self-loop 追加前の mapping action set として保持される。
owner-aware delayed product は、この `realActions` を使って action owner を判定する。

## Local metaEnv / safetyEnv

通常経路では、stage `i` の local goal をまとめて処理する。

```text
localGoals_i =
  localOldSafety_i
  + localNewSafety_i
  + localTransition_i
```

まず、stage mapping に global action の passive self-loop を足す。
これはfluent valuationを付ける前のraw mappingに対して一度だけ行う。
ただし `hotSwapIn` は除外する。

```text
if action not in MapE_i and action != hotSwapIn:
  state --action--> state
```

次に local goal に必要な fluent を集め、phase comparison fluent を追加する。

```text
trackedFluents_i =
  fluents(localGoals_i)
  + stopFluent
  + reconFluent
  + startFluent
```

`buildFluentProduct(...)` で mapping と fluent valuation を product し、local meta environment を作る。

```text
metaEnv_i = MapE_i || trackedFluents_i
```

raw mapping上のpassive self-loopは、fluent product後にはaction-fluent値を更新するtransitionになる。
したがって、product後の遷移は同じmapping stateに対応していても、`DelayedEnv`の状態としてはself-loopとは限らない。

実装上の wrapper は `DelayedEnv` で、次をまとめて保持する。

- MTS 本体
- tracked fluent set
- state ごとの fluent valuation
- mapping metadata
- old controller origin
- realActions
- 明示的Error state集合

その後、local goal を direct safety pruning する。
違反状態は削除済みの通常状態として扱わず、outgoing transitionを持たない明示的Errorとして識別する。
`safetyBackwardPruning` が有効なら、local direct pruning 後に保留SBPを行う。

local meta / safety の状態数・遷移数は recorder に scope 付きで記録される。

## 明示的Errorと保留SBP

部分断片に通常の `SafetyBackwardPruner.prune(...)` をそのまま適用すると、未合成componentとの同期後には無効になり得る制御不可能遷移を根拠に、遷移元を早期にlosingと誤判定する可能性がある。
通常経路のlocal処理とcross処理では、代わりに `SafetyBackwardPruner.pruneDeferred(...)` を使う。

保留SBPの規則は次である。

```text
1. 部分断片のlosing起点は、更新用式への違反として明示されたErrorだけとする
2. 部分断片の通常dead-endは、それだけを理由にErrorとしない
3. Errorへ進む制御可能action groupは、group全体を無効化する
4. Errorへ進む制御不可能action aについて、owner(a)がcurrentScopeにすべて含まれるなら、遷移元をErrorへ伝播する
5. owner(a)の一部が未合成なら、遷移元をErrorにせず、aによるError境界遷移を残して判定を保留する
```

制御可能action groupを除去して部分断片がdead-endになっても、その段階ではdead-endをError起点にしない。
後続のcomponent合成により別actionが追加される可能性があるためである。

保留した制御不可能境界は、ownerを含む後続合成で次のように解決される。

```text
いずれかのownerがaを無効化する -> 合成結果にa遷移を作らない
すべてのownerがaを有効化する   -> 合成結果にErrorへのa遷移を作る
```

Errorは状態に対する明示的な印として `DelayedEnv.errorStates` に保持する。
Error stateから遷移は作らない。
後続のfluent productとowner-aware productはError印を引き継ぎ、Errorを含むproduct tupleもErrorとする。

この方式では、共有制御不可能事象をもつcomponentをscopeへ事前に追加する閉包は行わない。
未合成ownerが残る場合だけ判定を保留するため、formulaの違反判定に必要なscopeを不必要に広げずに済む。

## Cross component

cross goal は、stage scope が overlap するものを同じ component にまとめる。
component は first stage index で sort され、1から id が振られる。

通常経路では、component 内の local `safetyEnv_i` を fragment として保持する。

```text
fragment_i = safetyEnv_i, scope {i}
```

cross goal を処理するたびに、必要な fragment だけを merge し、その merge scope に含まれる goal を batch としてまとめて pruning する。

## Cost-guided staged cross scheduling

現在の通常経路では cost-guided scheduling がデフォルトで有効である。
fixed ordering は fallback として `selectNextCrossGoalFixedOrder(...)` に残っており、`-Dstepwise.delayed.costGuidedCrossScheduling=false` で戻せる。

まず `remainingGoals` は fixed ordering で sort される。
fixed ordering は次の順である。

```text
1. scope size が小さい
2. stage scope が小さい
3. kind order: OLD_SAFETY, NEW_SAFETY, TRANSITION
4. goal name
```

cost-guided scheduling では、各 candidate goal `g` について次を計算する。

```text
selectedFragments(g) =
  scope(g) と交差する現在の fragments

mergedScope(g) =
  selectedFragments(g) の stage scope union

batchGoals(g) =
  remainingGoals のうち scope(h) subseteq mergedScope(g)

cost(g) =
  product(states(F) for F in selectedFragments(g))
```

choice の比較順は次である。

```text
1. cost が小さい
2. batchGoals が多い
3. mergedScope size が小さい
4. fixed ordering index が小さい
```

選ばれた goal は seed としてログに出る。
実際に pruning される goal は seed だけではなく、merge された fragment scope に収まる `batchGoals` 全体である。

各 scheduling step では、詳細 metric と summary metric が evaluation recorder に記録される。

## Staged cross pruning

各 cross step の処理は次である。

```text
1. selectedFragments を owner-aware delayed product で merge する
2. merged scope 外の real action のalphabetと既存passive transitionを保持する
3. batch goal に必要で、まだtrackedでないfluentだけを求める
4. extendFluentProduct で新規fluentだけをcross metaEnvへ追加する
5. batch goal を direct safety pruning する
6. safetyBackwardPruning が有効なら、現在のproduct scopeを用いて保留SBPを行う
7. selectedFragments を削除し、pruned fragment を fragments に戻す
8. fragments を scope 順に sort する
```

cross stepでのfluent集合は次のように扱う。

```text
requiredFluents =
  fluents(batchGoals, canonicalGlobalActions)
  + phase comparison fluents

newFluents = requiredFluents - current.trackedFluents

crossMeta = extendFluentProduct(current, newFluents)
```

既存tracked fluentは初期値から再生成せず、current stateに保存済みのvaluationをそのまま継承する。
`newFluents`が空ならproductを再構築しない。
同名fluentが既にtrackedでも、initial valueまたはinitiating/terminating action集合が異なる場合はfatalとする。
action-fluentのterminating action補完には、fragmentごとに変わるalphabetではなく、全段階で同じcanonical global action alphabetを使う。

fluent product後にmerged scope外actionのraw self-loopを後付けしてはならない。
その方法では、そのactionで変化すべき既存tracked fluentのvaluationが凍結される。

すべての remaining goal を処理した後、component 内に残った fragments を merge して component final safety environment にする。

scope ごとの metaEnv / safetyEnv の state 数、transition 数、CountTime は recorder に記録される。

## Owner-aware delayed product

`composeProduct(...)` は delayed 用の owner-aware product である。
通常の composition 呼び出しではなく、reachable tuple を BFS 的に構築する。

action 発火条件:

- update action は、すべての input がその action を enabled している時だけ同期発火できる
- normal action は、その action の real owner input がpartial product内にあれば、そのownerがenabledしている時だけ発火できる
- normal actionのreal ownerがまだpartial scopeに存在しない場合、inputに保持されているpure-passive transitionを発火可能候補として残す
- owner ではない input が action を alphabet に持たなければ、その input は同じ state に留まれる
- owner ではない input でも action を alphabet に持つのに enabled していない場合、その action は発火できない

productのalphabetはinput alphabetの和集合を明示的に保持する。
これにより、「alphabetにあるが現在disabledなのでblock」と「alphabetにないためstutter」を後続productでも区別できる。

product 後は、input の tracked fluent valuation と mapping metadata を product state に引き継ぐ。
この metadata が後段の `isMappingOldSide(...)` で使われる。

input tupleのいずれかが明示的Errorならproduct stateもErrorにする。
Error tupleからoutgoing transitionは構築しない。
保留された制御不可能境界は、`realActions`で識別した全ownerがその状態でactionをenabledにしている場合だけproduct上に現れる。

## Final mapping product

cross component の処理後、final mapping product の入力を作る。

- cross component に含まれる stage は component final safety environment を使う
- cross component に含まれない stage は local safety environment を使う

```text
mappingProduct =
  ownerAwareProduct(crossComponentSafetyEnvs + uncoveredLocalSafetyEnvs)
```

この時点では、まだ old controller とは接続していない。
`mappingProduct` は、stage mapping 側だけから作った product である。

通常経路でSBPが有効な場合、全stageを含む `mappingProduct` に通常の `SafetyBackwardPruner.prune(...)` を適用する。
この段階では全ownerのenabled/disabledが確定しているため、保留SBPではなく、dead-endをlosing起点に含む通常SBPを使う。
初期状態がlosingなら合成不能である。
通常SBP後に明示的Errorが残る場合は、保留境界の解決漏れとしてfatalにする。

## Old controller meta

final mapping product の tracked fluent に phase comparison fluent を追加する。

```text
allTrackedFluents =
  mappingProduct.trackedFluents
  + stopFluent
  + reconFluent
  + startFluent
```

old controller に同じ fluent valuation を付ける。

```text
oldMeta = oldController || allTrackedFluents
```

実装では `buildOldControllerMeta(...)` が `oldControllerOrigin` を保持する。
oldMeta state から元の old controller state を追跡できるようにするためである。

## Delayed hotSwapIn connection

`buildConnections(...)` は、oldMeta と mappingProduct の initial pair から synchronized reachable pair を探索する。

探索対象:

```text
(oldMeta state, mappingProduct state)
```

successor は、oldMeta と mappingProduct の両方が同じ action を enabled している時だけ enqueue される。
この synchronized reachable pair の集合を変えないことが、connection 高速化の前提である。

pair `(o, m)` に対して、次をすべて満たす場合に connection を作る。

```text
1. mapping state m が全 stage OLD_SIDE
2. mapping state m が phase-initial
   - !stopFluent
   - !reconFluent
   - !startFluent
3. beginFluent を除いた tracked fluent valuation が oldMeta state o と mapping state m で一致する
```

connection は次として追加される。

```text
o --hotSwapIn--> m
```

現在の default は legacy connection builder である。
legacy builder は reachable pair ごとに old outgoing transitions と mapping outgoing transitions を二重 loop で突き合わせる。

indexed builder は `-Dstepwise.delayed.indexedHotSwapInConnection=true` で有効化できる。
indexed builder は次を cache / index 化する。

- state ごとの transitions by action
- mapping eligibility
- fluent valuation signature

ただし、reachable synchronized pair の探索と connection 条件は変えない。
valuation が一致する全組を接続する実装ではない。

connection target がない oldMeta state が残る場合は fatal になる。
現在の実装では、oldMeta の全 state から target あり state を引いた集合を `oldStatesWithoutTargets` として扱う。

## Old side relabel と接続

connection plan を作った後、oldMeta 側の normal controllable action を `.old` に relabel する。
update action は relabel しない。

```text
a -> a.old   if a is controllable normal action
```

その後、oldMeta と mappingProduct を disjoint union し、connection plan に基づいて `hotSwapIn` transition を追加する。

```text
connected =
  oldMeta(.old relabeled)
  + mappingProduct(offset)
  + hotSwapIn connections
```

最後に unreachable state を削除する。

## Global DontDoTwice と final SBP

接続後、global DontDoTwice を追加する。
現在の `UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(...)` は、デフォルトでは `stopOldSpec` と `startNewSpec` を対象にする。
`reconfigure` は default target には含まれない。

```text
safetyEnv = DontDoTwice(connected)
```

`safetyBackwardPruning` が有効なら、この final safetyEnv に対して final SBP を行う。
final SBP でも initial state が losing なら fatal になる。

final GR(1) input の state 数、transition 数、source stage は recorder に記録される。

## GR(1)

final safetyEnv を CompactState に変換し、`UpdatingControllerGRSynthesizer.synthesizeStepwiseDelayedGR(...)` に渡す。

```text
compactSafetyEnv = convert(safetyEnv, "stepwise_delayed_E_u||G(safety)")
synthesizeStepwiseDelayedGR(compactSafetyEnv, uccs, safetyEnv, output)
```

GR(1) solver 本体時間は `safetyEnv を GR1 で解く時間` として記録される。
composition が null なら losing として fatal になる。

## CSV / recorder に記録される主な情報

`stepwise_delayed` 実装は `UpdatingControllerEvaluationRecorder` に多くの評価値を記録する。
詳細な一覧は `EVALUATION_CSV_RECORDER_MEMO_JA.md` を正とする。

実装メモとして重要なカテゴリは次である。

- mode flag
  - `incrementalPruning`
  - `incrementalPruningCleanup`
  - `safetyBackwardPruning`
  - `costGuidedCrossScheduling`
  - `indexedHotSwapInConnection`
- classification / scope 統計
  - local / cross goal 数
  - scope ごとの old safety / new safety / transition requirement 数
  - cross component scope 統計
- scope ごとの state space
  - local metaEnv
  - local safetyEnv
  - cross component step metaEnv
  - cross component step safetyEnv
  - cross component final safetyEnv
- direct pruning reduction
  - before / after state 数
  - before / after transition 数
  - CountTime
- 通常SBP（CSV）
  - scope ごとの SBP 時間
  - SBP 合計時間
  - SBP 前後 state space
  - initial losing 判定
- 保留SBPの標準出力診断（現時点ではCSV未収録）
  - SBP前後の状態数・遷移数と所要時間
  - 明示的Error seed数
  - 確定losing state数
  - 保留した制御不可能action group数
  - 除去した制御可能action group数
- cost-guided cross scheduling
  - scheduler mode
  - selected goal
  - selected fragment 数
  - merged scope
  - cost
  - batch goal 数
  - scheduling overhead
- delayed hotSwapIn connection
  - legacy / indexed mode
  - oldMeta state 数
  - mappingProduct state 数
  - visited pair 数
  - connection 数
  - eligibility / valuation / transition matching 関連 count
  - setup / traversal / total 時間
- GR(1) input
  - final safetyEnv 構築時間
  - final GR input state 数
  - final GR input transition 数
  - GR solver 時間

CSV の列や固定 `metric_key` は `EVALUATION_CSV_RECORDER_MEMO_JA.md` を参照する。

## Incremental pruning 経路

`incrementalPruning` は実装に残っている。
有効な場合、通常経路とは別に次のような処理を通る。

```text
buildIncrementalLocalEnvironments(...)
buildIncrementalCrossAndFinalProduct(...)
pruneIncrementalGoal(...)
```

local / cross goal を sorted order で1つずつ処理し、必要に応じて product scope を広げながら pruning する。
`incrementalPruningCleanup` が有効なら、各 step 後に reachable cleanup を行う。

通常の `stepwise_delayed` 経路とは controller permissiveness が変わり得るため、論文上の主経路とは分けて扱う。
現在のincremental経路は各goal後に通常SBPを呼ぶ箇所を残しており、保留SBPによる相対的完全性の証明対象には含めない。

## 現在の実装上の要点

`stepwise_delayed` は、local / cross pruning の前に old controller を product しない。
old controller は、final mapping product を作った後、delayed `hotSwapIn` connection のために初めて fluent meta 化される。

cross goal の通常処理は cost-guided staged cross scheduling である。
fixed ordering は fallback として残っている。

hotSwapIn connection の通常処理は legacy builder である。
indexed builder はオプションとして残っているが、デフォルトでは使わない。

SBP は local、staged cross、final mapping product、old controller接続後のfinal safetyEnvで使われる。
localとstaged crossでは保留SBP、全component合成後の2箇所では通常SBPを使う。
direct pruning の initial violation と、確定したSBPの initial losingは、その時点でfatalになる。

## 正しさに関する実装上の対応

論文では、合成成功時の健全性に加え、Traditional DUCSが合成に成功するならStepwise DUCSも合成に成功するという一方向の相対的完全性を示す方針である。
実装上は、次の対応がその証明義務を支える。

- formulaの直接違反だけを部分fragmentの初期Errorとする
- 全ownerが現在のscopeに含まれる制御不可能事象だけを直ちに逆伝搬する
- 未合成ownerがあるError境界は削除せず、owner-aware productまで保留する
- controllable actionは同一source・同一actionのgroup全体で無効化する
- 全mapping component合成後とold controller接続後に通常SBPを行い、未確定Errorとdeadlockを残さない
- 遅延`hotSwapIn`接続では、Traditional DUCSと同じ接続条件を後段で評価する

これらは同一LTS、同一遷移列、同一最大許容性、または逆方向の合成可能性を主張するものではない。

## 検証と実験上の注意

- `SafetyBackwardPrunerTest`は、部分dead-endをErrorにしないこと、不完全owner境界を残すこと、全ownerが揃った場合の逆伝搬、制御可能action group全体の除去、確定losing stateを越えた境界の付け替えを確認する。
- `StepwiseActionOwnershipTest`は、共有actionの全ownerを登録し、`.old` actionを正規化することを確認する。
- 既存の更新制御器9要件チェッカーは要件9の判定に既知の不具合がある。`OVERALL=PASS`だけを実装または論文の正しさの根拠にしない。
- GSMでは、生成されたStepwise DUCSの更新制御器を直接到達解析し、旧・新要求、更新完了、deadlock freedom、接続全域性、mappingとの合法性、更新時事象の規律を確認した。Traditional DUCS失敗・Stepwise DUCS成功という合成可否の差は内部回帰情報にとどめ、論文の主張には使わない。
- `MODEL/StepwiseDUCS/Experiment/configs/local`の本評価用YAMLは、各構成を`runs: 3`で実行する。別用途の診断用YAMLにある`runs: 1`は維持する。
