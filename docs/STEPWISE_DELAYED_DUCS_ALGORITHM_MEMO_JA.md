# Stepwise DUCS アルゴリズム確認メモ

最終更新: 2026-07-14

このメモは、Stepwise DUCS のアルゴリズムを入力から一歩ずつ確認するための作業メモである。
現時点では、入力、mapping environment 生成、local / cross 分類、明示的Errorを用いるlocal / crossの保留SBP、fragmentベースのstaged cross pruning、全component合成後の確定SBP、delayed `hotSwapIn` connection、global DontDoTwice、final analysis spaceをGR(1)に渡す流れまでを記録する。

OTF-DUC との比較はこのメモの対象外とする。
Traditional DUCS との対応は、必要な箇所でだけ最小限に触れる。

## 用語方針

論文上は、この方式を Stepwise DUCS と呼ぶ。
実装上の keyword / class path では `stepwise_delayed` という名前が使われているが、これはコード上の識別子である。
このメモでは、アルゴリズムや論文上の説明では Stepwise DUCS と書き、実装の flag、class、ログ名を指すときだけ `stepwise_delayed` と書く。

このメモでは、Stepwise DUCS を単独のアルゴリズムとして説明する。
他の未採用実装との比較・位置づけは行わない。

## 対象範囲

ここで確認しているのは Stepwise DUCS の主経路である。
`incrementalPruning` / `incrementalPruningCleanup` は、後で別途確認する。
論文上のStepwise DUCSでは `safetyBackwardPruning` を必須処理とする。
実装上の主経路は非incrementalの `stepwise_delayed + safetyBackwardPruning` である。

実装上の主な入口は次である。

```text
ltsa.lts.UpdatingControllersDefinition
ltsa.updatingControllers.stepwise.delayed.StepwiseDelayedUpdatingControllerSynthesizer
ltsa.updatingControllers.stepwise.StepwiseGoalClassifier
ltsa.updatingControllers.stepwise.StepwiseFormulaSupport
ltsa.updatingControllers.synthesis.SafetyBackwardPruner
```

## 入力

概念的な入力は次のように置く。

```text
C_old

E_old = {E_old^1, E_old^2, ..., E_old^i}
E_new = {E_new^1, E_new^2, ..., E_new^i}
R = {R^1, R^2, ..., R^i}
G_old = {G_old^1, G_old^2, ..., G_old^j}
G_new = {G_new^1, G_new^2, ..., G_new^k}
G_T = {G_T^1, G_T^2, ..., G_T^l}
```

`C_old` は指定済みの旧コントローラである。
実装では `.lts` の `oldController = ...` で与えられ、この `oldController` が後段の delayed `hotSwapIn` connection に使われる。

`oldEnvironment`、`newEnvironment`、`mapRelation` は list 形式で与える。
3つの list は同じ長さでなければならない。
同じ index の要素が stage `i` を作る。

```text
stage i:
  Ei_old
  Ei_new
  Ri
```

## 1. Mapping Environment を作る

まず、各 stage について mapping environment を作る。

```text
for each i:
  MapE_i = MappingEnvironment(Ei_old, Ei_new, Ri)
```

実装では `UpdatingControllersDefinition` が `oldEnvironment` / `newEnvironment` / `mapRelation` の list を読み、各 index ごとに `MappingEnvironmentGenerator.generate(...)` を呼ぶ。
生成された `MapE_i` は `StepwiseStage` に格納される。

`MapE_i` は概念的に次を含む。

```text
Ei_old の状態・遷移
Ei_new の状態・遷移
Ri による old-side から new-side への reconfigure 経路
```

relation rule が action sequence を持つ場合、次のような経路として展開される。

```text
old state
  --pre actions-->
  --reconfigure-->
  --post actions-->
new state
```

実装上は、中間状態が必要なら `INTERMEDIATE` 状態として追加される。
また、生成後に到達可能状態だけに振り直される。

Stepwise DUCS では、この時点で全 stage の full product

```text
MapE_1 || MapE_2 || ... || MapE_i
```

は作らない。
ここが Traditional DUCS と最初に大きく違う点である。

### Mapping Metadata

Stepwise DUCS では、後で delayed `hotSwapIn` connection を作るため、mapping state の metadata を保持する。

各 mapping state は概念的に次の情報を持つ。

```text
side:
  OLD_SIDE
  NEW_SIDE
  INTERMEDIATE

oldEnvState:
  OLD_SIDE または INTERMEDIATE の由来 old state

newEnvState:
  NEW_SIDE または INTERMEDIATE の由来 new state
```

この metadata により、後段で「現在の mapping product state が全 stage について old-side か」を判定できる。
state id の大小だけでは判定しない。

## 2. G_old, G_new, G_T を local / cross に分類する

次に、`G_old`、`G_new`、`G_T` を local goal と cross goal に分類する。

注意点として、実装は「requirement がどの `MapE_i` に含まれるか」を直接見るのではない。
次のように、requirement が参照する fluent の action から stage scope を決める。

```text
requirement
  -> formula
  -> fluents
  -> fluent の initiating / terminating actions
  -> 各 action がどの MapE_i の alphabet に含まれるか
  -> stage scope
```

分類規則は次である。

```text
stage scope の大きさが 1:
  local goal

stage scope の大きさが 2 以上:
  cross goal

通常 action がなく update event だけを参照:
  all-stage cross goal
```

`hotSwapIn`、`stopOldSpec`、`reconfigure`、`startNewSpec`、`hotSwapOut` などの update action は stage 判定から除外される。

local goal は stage ごとの bucket に入る。

```text
localOldSafety_i
localNewSafety_i
localTransition_i
```

cross goal は `stageScope` を持ったまま cross phase に回る。

この段階では pruning はまだ行わない。
ここで決めているのは、各 requirement を local stage で処理できるか、複数 stage の product が必要かだけである。

## 3. local MapE_i の準備

実装上の `stepwise_delayed` 本体では、`StepwiseStage` から `MapE_i` を取り出して MTS に変換する。

その後、各 `MapE_i` に `stopOldSpec` と `startNewSpec` の self-loop を足す。

```text
for each state m in MapE_i:
  m --stopOldSpec--> m
  m --startNewSpec--> m
```

これは Traditional DUCS の `E_u` で、mapping/update region にだけ `stopOldSpec` と `startNewSpec` の self-loop が追加されることを、delayed 側で再現するためである。

`reconfigure` は self-loop として追加しない。
`reconfigure` は `R_i` から作られた `MapE_i` の遷移としてすでに存在する。

また、local product や後段の同期で不要な blocking が起きないように、stage に存在しない global action には、raw `MapE_i` の段階で passive self-loop が足される。
この追加は fluent valuation を付ける前に一度だけ行う。fluent product 後は、そのactionによってfluent値が変化し得るため、対応するpassive transitionは必ずしも状態self-loopではない。

## 4. local metaEnv_i を作る

Stepwise DUCS では、ここまで確認した主経路では stage `i` の local goal をまとめて扱う。

```text
localGoals_i =
  localOldSafety_i
  + localNewSafety_i
  + localTransition_i
```

この local goal に必要な fluent を集める。

```text
trackedFluents_i =
  Fluents(localGoals_i)
  + stopFluent
  + reconFluent
  + startFluent
```

その後、`MapE_i` と fluent valuation を product して local `metaEnv_i` 相当を作る。

```text
metaEnv_i = MapE_i || trackedFluents_i
```

実装上のログ名は `mapping meta` である。
Traditional DUCS のように `E_u || goalFluents` を作っているわけではない。

Stepwise DUCS の local `metaEnv_i` の状態は、概念的には次である。

```text
(MapE_i state, tracked fluent valuation)
```

さらに実装上は、各状態に mapping metadata が引き継がれる。

```text
stage index
OLD_SIDE / NEW_SIDE / INTERMEDIATE
origin old env state
origin new env state
```

この段階では old controller はまだ接続しない。
したがって `hotSwapIn` もまだ入らない。
old controller との接続は、local / cross pruning と final product の後に遅延して行う。

## 5. local safety pruning

local `metaEnv_i` の各状態について、local goal の formula を評価する。
formula が true になった状態を violation state として扱う。

ここでの formula は、実装上は「満たすべき性質」そのものではなく、pruning 対象となる違反条件として扱われる。

`StepwiseFormulaSupport` は、old safety / new safety に update phase guard を付ける。

```text
old safety:
  !stopFluent && violation(G_old)

new safety:
  startFluent && violation(G_new)

transition requirement:
  violation(G_T)
```

`G_T` には自動 guard を付けない。
更新フェーズに依存する条件が必要なら、transition requirement 自身がその条件を書く。

direct safety pruning の処理は次である。

```text
violating = {}

for formula in local formulas:
  for state s in metaEnv_i:
    if formula(s) == true:
      violating.add(s)

safetyEnv_i = new MTS(initial(metaEnv_i))

for state s in metaEnv_i:
  safetyEnv_i.addState(s)

  if s not in violating:
    copy outgoing transitions of s
  else:
    do not copy outgoing transitions of s

safetyEnv_i.removeUnreachableStates()
```

つまり、違反状態を共通 `ERROR` にまとめるのではない。
違反状態の outgoing transition をコピーしないことで、違反状態を dead-end 化する。

```text
good --a--> bad
bad  // outgoing なし
```

この incoming edge は direct safety pruning では残り得る。

一方、違反状態の outgoing を消した結果、初期状態から到達不能になった「先の状態」は cleanup で消される。

```text
before:
  s0 --a--> s1 --b--> s2 --c--> s3
            s1 is violating

after direct pruning + cleanup:
  s0 --a--> s1
  s1  // outgoing なし
```

この cleanup は backward propagation ではない。
初期状態から到達可能な dead-end violation state は残り得る。

ただし、初期状態そのものが `violating` に含まれる場合は、その local scope では最初から safety violation である。
Stepwise DUCS のアルゴリズムでは、この時点で unrealizable と判定して終了する。
現在の実装も、direct safety pruning で初期状態が violating になった場合は `Diagnostics.fatal` する。

Traditional DUCS の direct safety pruning でも同じく、違反状態の outgoing を消した後に `removeUnreachableStates()` が実行される。

## 6. local保留Safety Backward Pruning

local direct safety pruningは、違反状態を `DelayedEnv.errorStates` に明示的Errorとして記録し、そのErrorからoutgoing transitionを作らない。
論文上のStepwise DUCSでは、その直後に `SafetyBackwardPruner.pruneDeferred(...)` を適用する。

```text
metaEnv_i
  -> direct safety pruning and explicit Error marking
  -> local fragment
  -> DeferredSafetyBackwardPruning(local fragment, owners, currentScope={i})
```

部分断片では、通常dead-endをlosing起点にしない。
未合成componentの事象を後で合成することで、その状態から進める可能性があるためである。
losing起点は更新用式への違反として明示されたErrorだけである。

概念的な規則は次である。

```text
1. explicit Errorをlosing起点にする。

2. Errorへ進むcontrollable action groupはgroup全体を除去する。

3. Errorへ進むuncontrollable action aについて、
   owner(a) subseteq currentScopeならsourceもErrorとする。

4. owner(a)の一部が未合成ならsourceをErrorにせず、
   aによるError境界遷移を残して判定を保留する。

5. 部分断片で新たに生じたdead-endは、その段階ではErrorとしない。
```

保留した境界遷移は後続合成で全ownerの状態と同期する。
いずれかのownerがactionを無効化していれば境界遷移は合成結果に現れず、全ownerが有効化していれば合成結果はErrorへ進む。
Errorを成分にもつproduct stateもErrorとし、そこからoutgoing transitionを作らない。

この処理は、共有制御不可能事象の全ownerをformula scopeへ事前に追加する閉包とは異なる。
formulaの違反判定に必要なscopeを維持し、同期可否が未確定な制御不可能境界だけを保留する。

明示的Errorまたは全ownerが揃った制御不可能事象の逆伝搬によりinitial stateがlosingになった場合は、その時点でunrealizableと判定する。

## 7. cross component を作る

local `safetyEnv_i` を作った後、cross goal を処理する。

ここでいう cross goal は、stage scope の大きさが2以上の requirement である。
通常 action がなく update event だけを参照する requirement は all-stage cross goal として扱われる。

Stepwise DUCS は、cross goal を1つずつ独立に処理するのではない。
まず、cross goal の `stageScope` が重なるものを `CrossComponent` にまとめる。

```text
for each cross goal g:
  scope(g) = stages referenced by g

  if scope(g) overlaps an existing component:
    add g to that component
    component.scope = component.scope union scope(g)

  if it overlaps multiple components:
    merge those components

  otherwise:
    create a new component with scope(g)
```

例:

```text
G_12 scope {1, 2}
G_23 scope {2, 3}

=> one cross component
   scope {1, 2, 3}
   goals {G_12, G_23}
```

all-stage cross goal がある場合、その goal の scope は全 stage になる。
この all-stage cross goal は任意の他の cross scope と重なるため、同じ component に merge されやすい。

```text
G_12  scope {1, 2}
G_all scope {1, 2, 3, 4}

=> one cross component
   scope {1, 2, 3, 4}
   goals {G_12, G_all}
```

ただし、component の scope が大きいからといって、最初から component scope 全体の product を作るとは限らない。
現在の Stepwise DUCS では、component 内で local `safetyEnv_i` を fragment として保持し、cross goal の scope に応じて必要な fragment だけを段階的に merge する。
all-stage cross goal がある場合も、先に小さい scope の cross safety environment を作れるなら、それらを作ってから最後に all-stage scope へ merge する。

## 8. cross component 内で staged fragment product を作る

各 cross component について、最初は component scope に含まれる local safety environment を独立した fragment として持つ。

```text
fragments =
  { (scope={i}, env=safetyEnv_i) | i in component.scope }
```

実装上は、remaining cross goal を固定順に整列して deterministic な tie-breaker として持ちつつ、通常経路では cost-guided scheduling により次の cross goal を選ぶ。
したがって現在のデフォルトでは、cross goal scheduling は cost-guided である。
固定順は fallback として残しており、`-Dstepwise.delayed.costGuidedCrossScheduling=false` で戻せる。

各時点で、remaining goal `g` について次を見積もる。

```text
selectedFragments(g) =
  { F | F.scope intersects scope(g) }

mergedScope(g) =
  union { F.scope | F in selectedFragments(g) }

batchGoals(g) =
  { h in remainingCrossGoals | scope(h) subseteq mergedScope(g) }

cost(g) =
  product(states(F.env) for F in selectedFragments(g))
```

通常経路では `cost(g)` が最小の goal を選ぶ。
tie-breaker は、`batchGoals(g)` が多い、`mergedScope(g)` が小さい、固定順が早い、の順である。

次の cross goal `g` を処理するとき、`scope(g)` と交差する fragment だけを選ぶ。

```text
selectedFragments =
  { F | F.scope intersects scope(g) }
```

選んだ fragment を owner-aware product で合成し、新しい product scope を得る。

```text
mergedEnv =
  ownerAwareProduct({ F.env | F in selectedFragments })

mergedScope =
  union { F.scope | F in selectedFragments }
```

この `mergedScope` に完全に含まれる remaining cross goal は、同じ product 上で評価できる。
したがって、それらを batch としてまとめて扱う。

```text
batchGoals =
  { h in remainingCrossGoals | scope(h) subseteq mergedScope }
```

その後、`batchGoals` を用いて cross metaEnv と cross safetyEnv を作り、選ばれた fragment を新しい fragment で置き換える。

```text
fragments =
  (fragments - selectedFragments)
  + { (scope=mergedScope, env=crossSafety_merged) }
```

この処理を cross component 内の cross goal がなくなるまで繰り返す。

例:

```text
local:
  safetyEnv_1, safetyEnv_2, safetyEnv_3, safetyEnv_4, safetyEnv_5

cross goals:
  G12_1, G12_2     scope {1,2}
  G45_1, G45_2     scope {4,5}
  G123             scope {1,2,3}
  G124             scope {1,2,4}
  G12345           scope {1,2,3,4,5}

staged cross:
  safetyEnv_1 || safetyEnv_2
    + G12_1, G12_2
    -> safetyEnv_12

  safetyEnv_4 || safetyEnv_5
    + G45_1, G45_2
    -> safetyEnv_45

  safetyEnv_12 || safetyEnv_3
    + G123
    -> safetyEnv_123

  safetyEnv_123 || safetyEnv_45
    + G124, G12345
    -> safetyEnv_12345
```

論文用には「必要な fragment の並列合成」と書ける。
ただし実装では、通常の MTSA composition をそのまま呼ぶのではなく、`composeProduct` という owner-aware delayed product を使う。

### owner-aware delayed product

実装上の product state は tuple である。

```text
(s_i, s_j, ...)
```

product の enabled action は、各 input の現在状態から出ている action の和集合から候補を作る。
その上で、次の条件を満たす action だけを product に追加する。

```text
update action:
  全 input がその update action を enabled しているときだけ同期する。

normal action:
  現在のpartial product内にreal ownerがあれば、
  そのowner componentがenabledしている必要がある。

  real ownerがまだpartial scopeに入っていなければ、
  input内に既にあるpassive transitionを保持する。

owner でない component:
  action を持たなければ状態を変えない。
  action が alphabet にあるが enabled でない場合は block する。
```

successor が複数ある場合は、各 component の successor choice の Cartesian product を取る。

この owner-aware product が必要な理由は、各raw local mappingに足したpassive self-loop由来のtransitionを、後続のpartial productでも保持するためである。
単に alphabet だけを見ると、本来その stage が所有していない action も含まれる。
そのため、実装では `realActions` を保持し、action の real owner が有効化しているかを見て product 遷移を作る。

まとめると、実装上の product は次に近い。

```text
ordinary synchronous product
+ passive transition support
+ owner-aware action enabling
+ fluent valuation / mapping metadata propagation
```

stage scope外のactionに対するpassive transitionは、local raw `MapE_i` に追加したself-loopをlocal fluent productへ通した時点で作られる。
staged productでは、その既存passive transitionを保持する。

```text
outOfScopeActions =
  actions owned by stages not in mergedScope

for each action a in outOfScopeActions:
  require a in alphabet(mergedEnv)
  retain the passive transition already carried by each fragment
```

fluent product後にraw self-loopを後付けしてはならない。既に追跡中のaction-fluentがそのactionで変化する場合、後付けself-loopはfluent値を凍結してしまうためである。
後からreal ownerを含むfragmentが合成されれば、以後はそのownerのenabled/disabledによって同じactionがgateされる。

## 9. cross metaEnv を作る

staged product に、その時点の `batchGoals` で新たに必要になった fluent だけを追加して cross `metaEnv` を作る。

```text
requiredFluents =
  Fluents(batchGoals, canonicalGlobalActions)
  + stopFluent
  + reconFluent
  + startFluent

newFluents =
  requiredFluents
  - fluents already tracked in mergedEnv

trackedFluents_merged =
  existing tracked fluents
  + newFluents

crossMeta_merged =
  ExtendFluentProduct(mergedEnv, newFluents)
```

既存tracked fluentのvaluationは、mergedEnvの各状態に保存された値を正としてコピーする。初期値から再生成しない。
新規fluentだけはmergedEnvの初期状態からtransitionをたどるproductによってvaluationを構築する。
`newFluents` が空ならproductを作り直さず、同じ`DelayedEnv`を使う。

状態は概念的に次のように拡張される。

```text
(mergedEnv state, new fluent valuation)
```

このとき、staged product の既存fluent valuation、mapping metadata、Error印、realActionsも引き継がれる。
action-fluentのterminating action集合は、段階ごとのfragment alphabetではなく、全段階で同じcanonical global action alphabetを用いて完成させる。

## 10. cross safety pruning

cross `metaEnv` 上で、その時点の `batchGoals` を評価する。

```text
badStates_merged =
  { s | exists formula in batchGoals. formula(s) = true }
```

その後、local safety pruning と同じ direct pruning を行う。

```text
crossSafety_merged =
  crossMeta_merged
  with outgoing transitions removed from badStates_merged
  and unreachable states cleaned up from the initial state
```

ここでも違反状態を一つの共通 `ERROR` へまとめないが、各違反状態を明示的Errorとして記録する。
違反状態のoutgoingをコピーせず、Error印を後続productへ引き継ぐ。

論文上のStepwise DUCSでは、このcross safety pruningの直後に現在のproduct scopeを用いる保留SBPを適用する。

```text
crossSafety_merged =
  DeferredSafetyBackwardPruning(
    crossSafety_merged,
    controllableActions,
    ownersByAction,
    mergedScope)
```

direct safety pruning で初期状態が `badStates_merged` に含まれる場合、または `safetyBackwardPruning` により初期状態が losing と判定された場合は、その時点で unrealizable として終了する。

## 11. final mapping product の入力を作る

各 cross component の staged fragment 処理が終わったら、component 内に残った fragment を必要に応じて final fragment に merge する。
得られた final cross safety environment は、後段の final product input になる。

```text
finalProductInputs += crossSafety_component_final
```

cross component に含まれた stage は、その component に吸収されたとみなす。
どの cross component にも含まれなかった stage については、local `safetyEnv_i` をそのまま final product input に追加する。

```text
for each stage i:
  if i is not covered by any cross component:
    finalProductInputs += safetyEnv_i
```

したがって、cross phase 後の final product input は次の混合になる。

```text
{ crossSafety_component_1, crossSafety_component_2, ... }
+ { uncovered local safetyEnv_i }
```

この時点ではまだ old controller とは接続していない。
delayed `hotSwapIn` connection は、final mapping product を作った後に行う。

## 12. final mapping product を作る

cross phase 後に得られた final product input を owner-aware product で統合する。

```text
mappingProduct =
  ownerAwareProduct(finalProductInputs)
```

全componentを含むmapping productでは、保留していた制御不可能境界の同期可否がすべて確定している。
このため、owner判定を保留しない通常のSafety Backward Pruningを適用する。

```text
mappingProduct =
  SafetyBackwardPruning(mappingProduct, controllableActions)
```

ここでは通常dead-endもlosing起点に含める。
initial stateがlosingならunrealizableであり、通常SBP後に明示的Errorが残る場合は保留境界の解決漏れとして扱う。

ここでの入力は、cross component 由来の `crossSafety_component_final` と、どの cross component にも含まれなかった local `safetyEnv_i` の混合である。

```text
finalProductInputs =
  { crossSafety_component_1, crossSafety_component_2, ... }
  + { uncovered local safetyEnv_i }
```

この段階の状態空間は、概念的には次である。

```text
(component/local safety state tuple,
 tracked fluent valuation,
 mapping metadata for each included stage)
```

まだ old controller とは接続していない。
したがって、`hotSwapIn` はまだ final mapping product の内部遷移としては入っていない。

## 13. old controller meta を作る

final mapping product が追跡している fluent をすべて集める。
実装では、ここに phase comparison 用の fluent も追加する。

```text
allTrackedFluents =
  Fluents(mappingProduct)
  + stopFluent
  + reconFluent
  + startFluent
```

次に、指定済みの旧コントローラ `C_old` に対して fluent product を作る。

```text
oldMeta =
  C_old || allTrackedFluents
```

`oldMeta` の状態は概念的には次である。

```text
(old controller state, tracked fluent valuation)
```

`oldMeta` 側には mapping metadata はない。
一方、old controller の由来状態は `oldControllerOrigin` として保持される。

ここで重要なのは、Stepwise DUCS では `C_old` を local / cross pruning の前に product しないことである。
`C_old` は、local / cross safety environment をすべて作った後、delayed `hotSwapIn` connection のために初めて meta 化される。

## 14. delayed hotSwapIn connection を作る

`oldMeta` と `mappingProduct` の初期状態 pair から、同じ action で同期できる pair を探索する。

```text
pending = {(oldMeta.initial, mappingProduct.initial)}

while pending is not empty:
  (o, m) = pop(pending)

  if m is all-stage OLD_SIDE
     and m is phase-initial
     and fluent valuations of o and m match:
       add connection o --hotSwapIn--> m

for each action a:
  if oldMeta has o --a--> o'
     and mappingProduct has m --a--> m':
       pending += (o', m')
```

現在のデフォルトでは、この同期 successor 列挙は既存相当の legacy 実装で行う。
legacy 実装では、reachable pair ごとに old 側 outgoing transitions と mapping 側 outgoing transitions を二重ループで突き合わせる。

比較用に indexed connection 経路も残している。
indexed connection は `-Dstepwise.delayed.indexedHotSwapInConnection=true` で有効化できる。
indexed 経路では、同期探索で触れた各 state の outgoing transitions を action ごとに lazy index 化してから同期 successor を列挙する。
また、`beginFluent` を除いた comparison fluent valuation は state ごとの signature として lazy cache し、mapping state の `OLD_SIDE` / phase-initial eligibility も到達した state だけ cache する。
これは上の同期到達 pair と connection 条件を変えず、reachable pair ごとの遷移突き合わせと valuation 判定だけを軽くするためである。
全 state の transition index / signature / eligibility を先に作ると、小中規模例題では setup cost が traversal 削減分を上回ることがあるため、indexed 経路では到達した同期 pair に必要な分だけ計算する。
計測ログには legacy / indexed の mode、所要時間、探索 pair 数、work counter が出る。
現時点の例題群では indexed 経路は work counter を減らす一方、hotSwapIn connection 自体が数 ms 程度であるため総時間は mixed であり、通常経路は legacy のままにしている。

connection target になる mapping state は、全 stage が `OLD_SIDE` でなければならない。
これは、old controller 実行中に `hotSwapIn` した直後は、mapping environment 側も旧環境側から更新を始めるためである。

また、phase は初期状態でなければならない。

```text
!stopFluent
!reconFluent
!startFluent
```

さらに、`beginFluent` を除いた tracked fluent valuation が old controller 側と mapping product 側で一致する必要がある。
これにより、old controller がその時点で観測している safety / transition 関連の履歴と、mapping product 側の履歴が合っている状態にだけ `hotSwapIn` する。

oldMeta に含まれる state のうち connection target が1つもない state が残る場合、Stepwise DUCS はその時点で unrealizable として終了する。
現在の実装では、synchronized pair 探索で訪問した old state だけではなく、`oldMeta.env.getStates()` 全体から connection target を持つ old state を差し引いて判定する。
実装では `Diagnostics.fatal` する。

connection は関係であり、1つの old state から複数の mapping state へ `hotSwapIn` が付く可能性がある。

## 15. old controller と mapping product を接続する

接続前に、old controller 側の通常 controllable action は `.old` 付きに relabel される。

```text
a in controllable normal actions:
  a -> a.old
```

update action は relabel しない。

その後、old controller 側と mapping product 側の状態空間を disjoint union し、connection plan に従って `hotSwapIn` 遷移を追加する。

```text
connected =
  oldMeta
  + mappingProduct
  + { o --hotSwapIn--> m | (o, m) in connections }
```

最後に到達不能状態を cleanup する。

## 16. global DontDoTwice と final safetyBackwardPruning

`connected` に対して global DontDoTwice を入れる。

```text
globalSafety =
  DontDoTwice(connected)
```

現在の `stepwise_delayed` 実装では、DontDoTwice の対象は default の `stopOldSpec` と `startNewSpec` である。
`reconfigure` は対象に含めていない。
つまり、この段階で追加される global safety は、`stopOldSpec` と `startNewSpec` を複数回行う trace を禁止するための制約である。

これを old controller と mapping product の接続後に行う理由は、old controller 側には `stopOldSpec` / `startNewSpec` がなく、update phase 全体を表す単一の connected model 上で制約する必要があるためである。
final mapping product の段階だけで DontDoTwice を入れると、`hotSwapIn` 前後をまたぐ global update sequence としての意味がずれる。

論文上のStepwise DUCSでは、global DontDoTwiceの後、GR(1)に渡す直前の `globalSafety` に対して通常SBPをもう一度適用する。

```text
globalSafety =
  SafetyBackwardPruning(globalSafety, controllableActions)
```

ここでも initial state が losing になれば unrealizable として終了する。

## 17. final safetyEnv を GR(1) に渡す

最終的な safety environment は次である。

```text
finalSafetyEnv =
  globalSafety
```

実装ではこれを `CompactState` に変換し、`uccs.setUpdateEnvironment(finalSafetyEnv)` と `uccs.setMachines(...)` により GR(1) 合成入力として設定する。

```text
compactSafetyEnv =
  MTSToAutomata(finalSafetyEnv)

synthesizeStepwiseDelayedGR(compactSafetyEnv, uccs, finalSafetyEnv)
```

GR(1) 合成では、final safety environment が非決定的なら subset construction により perfect-information game を作り、deterministic ならそのまま GR game を作る。
その後、assumption / guarantee から GR goal を構築し、winning region を計算する。

winning の場合は strategy を構築し、`GameStrategyToMTSBuilder` により strategy から controller MTS を作る。
最後に `GenericMTSToLongStringMTSConverter` と `MTSToAutomataConverter` を通して、出力 update controller の `CompactState` に変換する。

losing の場合は、実装では `uccs.getComposition() == null` になり、`Diagnostics.fatal` する。

## ここまでの流れ

ここまでの Stepwise DUCS は次のように整理できる。

```text
Input:
  C_old
  E_old, E_new
  R
  G_old, G_new
  G_T

1. for each stage i:
     MapE_i = MappingEnvironment(Ei_old, Ei_new, Ri)
     keep mapping metadata:
       side = OLD_SIDE / NEW_SIDE / INTERMEDIATE
       origin old/new env states

2. Classify G_old, G_new, G_T:
     requirement -> fluents -> actions -> owner stages

     if owner stage count == 1:
       local goal

     if owner stage count >= 2:
       cross goal

     if update events only:
       all-stage cross goal

3. for each stage i:
     add stopOldSpec/startNewSpec self-loops to MapE_i
     add passive self-loops for missing global actions

4. for each stage i:
     localGoals_i =
       local old safety
       + local new safety
       + local transition

     trackedFluents_i =
       Fluents(localGoals_i)
       + stopFluent
       + reconFluent
       + startFluent

     metaEnv_i = MapE_i || trackedFluents_i

5. for each stage i:
     evaluate local formulas on metaEnv_i
     badStates_i = {s | exists local formula. formula(s) = true}

     safetyEnv_i =
       metaEnv_i with badStates_i explicitly marked as Error,
       no outgoing transitions from Error,
       and unreachable states cleaned up from the initial state

6. localFragment_i =
     DeferredSafetyBackwardPruning(
       safetyEnv_i,
       controllableActions,
       ownersByAction,
       currentScope={i})

7. Build cross components:
     group cross goals by overlapping stage scopes

8. for each cross component C:
     fragments =
       { (scope={i}, env=localFragment_i) | i in C.scope }

     remainingGoals =
       cross goals in C sorted by fixed order
       // fixed order is used as deterministic tie-breaker

     while remainingGoals is not empty:
       for each g in remainingGoals:
         selectedFragments(g) =
           { F in fragments | F.scope intersects scope(g) }
         mergedScope(g) =
           union { F.scope | F in selectedFragments(g) }
         batchGoals(g) =
           { h in remainingGoals | scope(h) subseteq mergedScope(g) }
         cost(g) =
           product(states(F.env) for F in selectedFragments(g))

       seed =
         goal with minimum cost(g)
         tie-breaker:
           larger |batchGoals(g)|
           smaller |mergedScope(g)|
           earlier fixed order

       selectedFragments =
         { F in fragments | F.scope intersects scope(seed) }

       mergedEnv =
         ownerAwareProduct({ F.env | F in selectedFragments })
       mergedScope =
         union { F.scope | F in selectedFragments }

       batchGoals =
         { g in remainingGoals | scope(g) subseteq mergedScope }

       require passive action alphabets for actions outside mergedScope
       retain passive transitions already carried from local fluent products

       requiredFluents =
         Fluents(batchGoals, canonicalGlobalActions)
         + stopFluent
         + reconFluent
         + startFluent

       newFluents =
         requiredFluents - existing tracked fluents

       trackedFluents =
         existing tracked fluents + newFluents

       crossMeta =
         ExtendFluentProduct(mergedEnv, newFluents)

       crossSafety =
         crossMeta with bad states explicitly marked as Error,
         no outgoing transitions from Error,
         and unreachable states cleaned up from the initial state

       if initial state is bad:
         return unrealizable

       crossSafety =
         DeferredSafetyBackwardPruning(
           crossSafety,
           controllableActions,
           ownersByAction,
           mergedScope)
       if initial state is definitely losing:
         return unrealizable

       fragments =
         (fragments - selectedFragments)
         + { (scope=mergedScope, env=crossSafety) }

       remainingGoals =
         remainingGoals - batchGoals

9. finalProductInputs =
     all final crossSafety_C
     + localFragment_i for stages not covered by any cross component

10. mappingProduct =
      ownerAwareProduct(finalProductInputs)

11. mappingProduct =
      SafetyBackwardPruning(mappingProduct, controllableActions)

    if initial state is losing or unresolved Error remains:
      return unrealizable

12. oldMeta =
      C_old || all tracked fluents in mappingProduct

13. Build delayed hotSwapIn connections:
      search reachable pairs (oldMeta state, mappingProduct state)

      if mappingProduct state is all-stage OLD_SIDE
         and phase-initial
         and fluent valuations match:
           add oldState --hotSwapIn--> mappingState

      if any oldMeta state has no connection target:
        return unrealizable

14. connected =
      oldMeta
      + mappingProduct
      + delayed hotSwapIn connections

15. globalSafety =
      DontDoTwice(connected)

16. globalSafety =
      SafetyBackwardPruning(globalSafety, controllableActions)

    if initial state is losing:
      return unrealizable

17. finalSafetyEnv =
      globalSafety

18. GR(1):
      solve GR game on finalSafetyEnv

      if winning:
        strategy -> controller MTS -> output CompactState

      if losing:
        return unrealizable
```

## 正しさに関する現在の方針

論文では、次の二点を証明対象とする。

1. Stepwise DUCSが合成に成功した場合、出力は正しい更新制御器の条件を満たす。
2. Traditional DUCSが合成に成功する場合、Stepwise DUCSも合成に成功する。

2は一方向の相対的完全性であり、同じLTS、同じ遷移列、同じ最大許容性、または逆方向を主張しない。
証明の中心は次である。

- formula scope上で確定した直接違反は、scope外componentを後から合成しても違反である。
- Errorへ進む制御不可能事象の全ownerが現在のfragmentに含まれる場合、その遷移は後続合成でも残るため、後向き伝播してよい。
- 未合成ownerがある場合はError境界を残して伝播を保留し、全ownerの同期可否が確定した後に処理する。
- したがって、部分fragmentのSBPはTraditional DUCSのwinning strategyに必要な状態と遷移を除去しない。
- 遅延`hotSwapIn`接続はTraditional DUCSと同じ接続条件を後段で適用するため、保存されたwinning strategyに必要な接続を構築できる。

この証明は通常の非incrementalな `stepwise_delayed + safetyBackwardPruning` 経路を対象とする。
`incrementalPruning`経路は対象外である。

## 実装詳細と概念的アルゴリズムの違い

論文用アルゴリズムに変換するときは、次の差を意識する。

- 実装では `MapE_i` は `CompactState` として生成され、`stepwise_delayed` 本体で `MTS<Long, String>` に変換される。
- 実装では local `metaEnv_i` を `DelayedEnv` wrapper として扱い、MTS 本体に加えて fluent valuation と mapping metadata を保持する。
- 実装の formula は pruning 用の violation condition である。
- direct safety pruning は違反状態を明示的Errorとして記録するが、全違反を一つの共通ERROR sinkへまとめず、違反状態のoutgoingをコピーしない。
- direct safety pruning 後の cleanup は到達不能状態の削除であり、safety backward propagation ではない。
- `safetyBackwardPruning` は実装フラグとして切替可能だが、論文のStepwise DUCSでは必須であり、direct pruningとは別のbackward pruningである。
- ここまで確認した主経路では、local goal を stage ごとにまとめて pruning する。
- cross goal は stage scope の重なりにより component 化されるが、component scope 全体を最初から product するとは限らない。
- component 内では local safety environment を fragment として保持し、cross goal の scope に応じて必要な fragment だけを段階的に owner-aware product する。
- 同じ product scope で評価できる cross goal は batch としてまとめて pruning する。これは goal ごとに pruning する `incrementalPruning` とは異なる。
- all-stage cross goal がある component でも、小さい scope の fragment を先に作れる場合は先に作り、all-stage goal は最終的に component 全体が merge された段階で評価する。
- 実装の component product は通常の composition 呼び出しではなく、owner-aware delayed product である。
- owner-aware delayed product は、raw mappingから引き継いだpassive transitionと`realActions`を使う。real ownerがpartial scope内にあればownerのenabled状態でgateし、まだownerがなければpassive transitionを保持する。
- fluent valuationを付けた後に単純なpassive self-loopを追加しない。既存tracked fluentを凍結しないためである。
- cross段階では既存tracked fluentを再生成せず、そのbatchで新しく必要になったfluentだけを差分productで追加する。
- product 後も fluent valuation と mapping metadata を wrapper に引き継ぐ。
- old controller は local / cross pruning の前には product しない。final mapping product の後で、tracked fluent を追加した `oldMeta` として接続する。
- delayed `hotSwapIn` connection は、old controller state と mapping product state の fluent valuation が一致し、mapping product state が全 stage `OLD_SIDE` かつ phase-initial のときだけ追加する。
- connection target がない oldMeta state があれば、その時点で失敗とする。
- global DontDoTwice は old controller と mapping product を接続した後に一度だけ入れる。
- final `safetyBackwardPruning` は global DontDoTwice 後、GR(1) 入力直前に適用する。
- GR(1) 合成では、winning strategy から controller MTS を作り、最後に output `CompactState` に変換する。
- `incrementalPruning` variant は goal ごとに段階的に pruning するが、このメモではまだ主対象にしない。

## 次に確認すること

アルゴリズム本体は、入力から final GR(1) / output controller 変換まで一通り確認した。
次に深掘りする候補は次である。

```text
評価例題ごとの scope 別 requirement 数
scope 別 local/cross metaEnv・safetyEnv の状態数・遷移数
final safetyEnv と GR game / strategy のサイズ関係
保留した制御不可能Error境界の件数と、後続合成での解決結果
3回実行した時間・メモリ計測の集計
```
