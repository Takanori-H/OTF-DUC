# Traditional DUCS アルゴリズム確認メモ

最終更新: 2026-07-14

このメモは、Traditional DUCS のアルゴリズムを入力から一歩ずつ確認するための作業メモである。
現時点では、入力から mapping environment、`E_u`、`metaEnv`、`safetyEnv` を作り、最後に GR(1) で update controller `C_u` を得るところまでを記録する。

## 対象範囲

このメモでは、legacy Traditional DUCS の標準的な流れを対象にする。
以下の派生・オプションは、このメモの主対象にはしない。

```text
stepwise DUC
stepwise delayed DUC
fine-grained update events
selective fine-grained update events
safetyBackwardPruning などの optional pruning
```

実装確認の都合で関連する名前が出る場合はあるが、論文用アルゴリズムとしては、full `MapE_all`、full `E_u`、full `metaEnv`、full `safetyEnv` を構築してから GR(1) synthesis を行う Traditional DUCS を対象にする。

## 入力

Traditional DUCS の入力は、ここまでの確認では次のように置く。

```text
C_old

E_old = {E_old^1, E_old^2, ..., E_old^i}
E_new = {E_new^1, E_new^2, ..., E_new^i}
R = {R^1, R^2, ..., R^i}
G_old = {G_old^1, G_old^2, ..., G_old^j}
G_new = {G_new^1, G_new^2, ..., G_new^k}
G_T = {G_T^1, G_T^2, ..., G_T^l}
```

- `C_old` は旧仕様に対して合成済みの旧コントローラである。
- `E_old` は旧環境モデル群である。
- `E_new` は新環境モデル群である。
- `R` は旧環境と新環境の対応関係である。
- `G_old` は旧要求、`G_new` は新要求である。
- `G_T` は更新中に満たすべきtransition requirement集合である。

この段階では、主に `C_old`, `E_old`, `E_new`, `R` を使う。
`G_old`, `G_new`, `G_T` は、後続の safety / transition requirement / progress 条件を組み合わせる段階で効いてくる。

出力は update controller `C_u` である。
ただし、後段の GR(1) synthesis で初期状態が losing と判定された場合、条件を満たす update controller は合成できない。

```text
Output:
  C_u

Failure:
  initial state is losing in the GR(1) game
```

## 1. Mapping Environment を作る

まず、各環境ペアと対応関係から mapping environment を作る。

```text
for each i:
  MapE_i = MappingEnvironment(Ei_old, Ei_new, Ri)
```

`MapE_i` は、概念的には次を含む LTS である。

```text
旧環境 Ei_old の振る舞い
+ 新環境 Ei_new の振る舞い
+ Ri による reconfigure 遷移
```

`Ri` に単純な対応

```text
s_old --reconfigure--> s_new
```

がある場合、`MapE_i` には旧環境側状態 `s_old` から新環境側状態 `s_new` への `reconfigure` 遷移が追加される。

実装上の relation rule は、`reconfigure` の前後に action sequence を持てる。
例えば、

```text
s_old --a1--> ... --reconfigure--> ... --am--> s_new
```

のような rule の場合、必要に応じて中間状態を追加して `MapE_i` を作る。

ここまでの出力は次である。

```text
MapE = {MapE_1, MapE_2, ..., MapE_i}
```

## 2. Mapping Environment を並列合成する

次に、すべての mapping environment を並列合成する。

```text
MapE_all = MapE_1 || MapE_2 || ... || MapE_i
```

`||` は通常の並列合成である。
共通 action は同期し、片方にしかない action は独立に進む。

legacy Traditional DUCS では、各 mapping component の reconfiguration action は通常同じ `reconfigure` である。
そのため `MapE_all` 上の `reconfigure` は、複数の mapping component が同期する更新 action として扱われる。

## 3. C_old と MapE_all から E_u を作る

次に、旧コントローラと mapping environment 全体から update environment を作る。

概念的には次の関数として見られる。

```text
E_u = UpdatingEnvironment(
  C_old,
  MapE_all,
  hotSwapIn,
  stopOldSpec,
  startNewSpec
)
```

実装では `hotSwapIn`, `stopOldSpec`, `startNewSpec` は定数として使われている。

### 3.1 hotSwapIn の接続先を決める

`C_old` の状態がどの `MapE_all` 状態に接続されるかは、状態名で直接決めるのではない。
初期状態から `C_old` と `MapE_all` を同じ action で同期探索し、その reachable pair から決める。

初期ペアは次である。

```text
(c0, m0) = (initial(C_old), initial(MapE_all))
```

探索中に、

```text
c --a--> c'
m --a--> m'
```

があれば、

```text
(c, m) -> (c', m')
```

として次のペアへ進む。

実装では、この reachable pair の列挙はキューを使った幅優先探索、つまり BFS で行われる。
ただし、理論上は DFS でも reachable pair を漏れなく列挙できれば、得られる `hotSwapIn` 接続関係は同じである。

到達したペア集合を次の関係として見る。

```text
H subseteq States(C_old) x States(MapE_all)
```

各 `(c, m) in H` について、`E_u` に次の遷移を追加する。

```text
c --hotSwapIn--> m
```

ここで `hotSwapIn` 接続は一般には関数ではなく関係である。
つまり、次のどちらも起こり得る。

```text
同じ C_old 状態 c から複数の MapE_all 状態へ接続される
c --hotSwapIn--> m1
c --hotSwapIn--> m2

複数の C_old 状態から同じ MapE_all 状態へ接続される
c1 --hotSwapIn--> m
c2 --hotSwapIn--> m
```

これは、`C_old` の状態だけでは環境側状態が一意に決まらない場合や、逆に環境側状態だけでは旧コントローラ側の内部状態が一意に決まらない場合があるためである。

### 3.2 MapE_all 側状態を E_u にコピーする

`E_u` は大きく見ると次の領域を持つ。

```text
C_old region
  --hotSwapIn-->
MapE_all / update region
```

`hotSwapIn` 後に入る `MapE_all` 側の状態と遷移は、`E_u` の update region としてコピーされる。
`reconfigure` は `MapE_all` にもともと含まれる遷移として、この update region 内に現れる。

### 3.3 stopOldSpec と startNewSpec の自己ループを付ける

`stopOldSpec` と `startNewSpec` は、`C_old` 側状態ではなく、`MapE_all` 側として `E_u` に入った状態に自己ループとして追加される。

```text
m --stopOldSpec--> m
m --startNewSpec--> m
```

一方で、`reconfigure` は自己ループとして追加するのではなく、`MapE_all` 内の旧環境側から新環境側への遷移として存在する。

## 4. G_old, G_new, G_T から update 用 safety formula と fluent を作る

`E_u` を作った後、`G_old`, `G_new`, `G_T` を使って safety pruning 用の formula と、その評価に必要な fluent を抽出する。

重要なのは、`G_old` と `G_new` はそのまま fluent 抽出に回すのではなく、update 用に guard 付きの formula に変換してから fluent を抽出する点である。

### 4.1 G_old の変換

`G_old` は集合全体を1つの大きな式にまとめるのではなく、各 old safety ごとに処理する。

```text
G_old = {G1_old, G2_old, ..., Gj_old}
```

各 `Gx_old` について、まず update 用の safety definition 名を作る。

```text
Gx_old -> Gx_old_UPD_OLD
```

意味としては、次である。

```text
stopOldSpec がまだ起きていないなら Gx_old を守る
```

性質として書けば次に近い。

```text
not stopOldSpec -> Gx_old
```

ただし実装の safety pruning では、formula が true になる状態を違反状態として削る。
そのため内部的には、old safety の違反条件として次の形にする。

```text
formula_x = !stopFluent && violation(Gx_old)
```

ここで `stopFluent` は、瞬間的な action fluent ではなく persistent fluent である。

```text
stopFluent:
  initially false
  initiating action  = stopOldSpec
  terminating action = hotSwapIn
```

したがって、1回の update execution の中では、`stopOldSpec` が起きるまでは false、`stopOldSpec` 後は true と見られる。
`hotSwapIn` は次の update 開始時の reset として働く。

各 `Gx_old` について、次を行う。

```text
Gx_old
  -> formula_x = !stopFluent && violation(Gx_old)
  -> formulaFluents_x = Fluents(formula_x)

oldSafetyFluents += formulaFluents_x
goalFluents      += formulaFluents_x
safetyFormulas   += formula_x
```

### 4.2 G_new の変換

`G_new` も各 new safety ごとに処理する。

```text
G_new = {G1_new, G2_new, ..., Gk_new}
```

各 `Gy_new` について、update 用の safety definition 名を作る。

```text
Gy_new -> Gy_new_UPD_NEW
```

意味としては、次である。

```text
startNewSpec 後は Gy_new を守る
```

性質として書けば次に近い。

```text
startNewSpec -> Gy_new
```

実装の pruning formula としては、new safety の違反条件を次の形にする。

```text
formula_y = startFluent && violation(Gy_new)
```

`startFluent` も persistent fluent である。

```text
startFluent:
  initially false
  initiating action  = startNewSpec
  terminating action = hotSwapIn
```

したがって、`startNewSpec` が起きるまでは new safety 違反を見ない。
`startNewSpec` 後だけ、new safety 違反が pruning 対象になる。

各 `Gy_new` について、次を行う。

```text
Gy_new
  -> formula_y = startFluent && violation(Gy_new)
  -> formulaFluents_y = Fluents(formula_y)

newSafetyFluents += formulaFluents_y
goalFluents      += formulaFluents_y
safetyFormulas   += formula_y
```

### 4.3 G_T の扱い

transition requirement集合`G_T`は、old/new safety のような `_UPD_OLD` / `_UPD_NEW` wrapper を付けない。

```text
G_T = {G_T^1, G_T^2, ..., G_T^l}
```

各`G_T^z`について、transition requirement の formula を作り、その中で使う fluent を抽出する。
`G_T^z`自体が `StopOldSpec` や `StartNewSpec` などの update-phase fluent を参照していれば、それらも fluent として抽出される。

つまり、`G_T` は `G_old` / `G_new` のように DUCS 側が自動で有効範囲 guard を付けるものではない。
更新フェーズに依存した transition requirement にしたい場合、その条件は`G_T^z`の式の中に明示的に書く。

例えば、更新中の特定区間だけ `move` を禁止したいなら、transition requirement 自身を次のように書く。

```text
T_no_move = ((StopOldSpec && !StartNewSpec) -> !move)
```

この場合、`StopOldSpec` や `StartNewSpec` は `T_no_move` の中で使われる fluent として抽出される。

```text
Tz
  -> formula_z
  -> formulaFluents_z = Fluents(formula_z)

transitionRequirementFluents += formulaFluents_z
goalFluents                  += formulaFluents_z
safetyFormulas               += formula_z
```

### 4.4 safetyFormulas, goalFluents, oldSafetyFluents の違い

ここで使われる集合やリストの役割は次の通りである。

```text
safetyFormulas:
  後で metaEnv の各状態に対して評価し、true になった状態を削る formula のリスト。

goalFluents:
  safetyFormulas を評価するために必要な全 fluent の重複なし集合。
  metaEnv = E_u || goalFluents を作るために使う。

oldSafetyFluents:
  old safety 由来 formula から抽出された fluent の重複なし集合。
  主に入力規模や評価ログ用のカテゴリ別集計である。
```

同様に、実装ではカテゴリ別集計として次も持つ。

```text
newSafetyFluents
transitionRequirementFluents
```

小例:

```text
G1_old -> formula_1, fluents {stopFluent, f_a}
G2_old -> formula_2, fluents {stopFluent, f_b}
G1_new -> formula_3, fluents {startFluent, f_c}
G_T^1  -> formula_4, fluents {f_d}
```

このとき、概念的には次のようになる。

```text
safetyFormulas =
  [formula_1, formula_2, formula_3, formula_4]

oldSafetyFluents =
  {stopFluent, f_a, f_b}

goalFluents =
  {stopFluent, startFluent, f_a, f_b, f_c, f_d}
```

実装では、明示的な `requirement name -> fluent set` の対応表は保持しない。
各 formula を処理するときに一時的な `formulaFluents` を作り、それをカテゴリ別集合と全体集合へ足す。

## 5. E_u と fluent から metaEnv を作る

抽出した全 fluent を `goalFluents` として、`E_u` と組み合わせて `metaEnv` を作る。

```text
metaEnv = E_u || goalFluents
```

Traditional DUCS では、この `metaEnv` が一番大きくなりやすい。
理由は、`E_u` 自体がすでに `C_old` と大きな `MapE_all` から作られ、その上にすべての fluent valuation が乗るためである。

概念的には、`metaEnv` の状態は次の組として見られる。

```text
(E_u state, fluent valuation)
```

そのため、理論的には次のように増え得る。

```text
|States(metaEnv)| <= |States(E_u)| * 2^(#goalFluents)
```

実際には到達不能な valuation が多いため、この上限まで増えるとは限らない。
それでも、Traditional DUCS の状態爆発では、full `E_u` を作った後に全 fluent を合成して `metaEnv` を作る段階が大きな要因になりやすい。

実装上は、`goalFluents` に対して terminating actions を補った後、`E_u` と fluent automata を組み合わせる。

```text
fillTerminatingActions(E_u.actions, goalFluents)
metaEnv = E_u || goalFluents
```

そのため、`E_u` では自己ループだった `stopOldSpec` / `startNewSpec` も、`metaEnv` では fluent valuation を変える遷移になる。

例えば、

```text
E_u:
  m --stopOldSpec--> m

metaEnv:
  (m, stopFluent=false) --stopOldSpec--> (m, stopFluent=true)
```

同様に、

```text
E_u:
  m --startNewSpec--> m

metaEnv:
  (m, startFluent=false) --startNewSpec--> (m, startFluent=true)
```

ただし、`stopFluent` や `startFluent` は2回目の同じ action を禁止するものではない。
`metaEnv` までの段階では、`stopOldSpec` / `startNewSpec` は複数回起こるパスを持ち得る。

例えば、1回目の `stopOldSpec` で `stopFluent` が true になった後でも、2回目以降の `stopOldSpec` は次のように残り得る。

```text
(m, stopFluent=true) --stopOldSpec--> (m, stopFluent=true)
```

2回目以降の `stopOldSpec` / `startNewSpec` を禁止するのは、後段の `DontDoTwice` monitor である。

Traditional DUCS の段階差は次のように整理できる。

```text
E_u:
  stopOldSpec/startNewSpec は update region の自己ループなので複数回起こり得る。

metaEnv:
  fluent valuation が乗る。
  1回目の stopOldSpec/startNewSpec は phase fluent を true にする。
  ただし、同じ event の2回目以降もまだ起こり得る。

safetyEnv after DontDoTwice:
  stopOldSpec/startNewSpec の2回目は error へ行くため禁止される。
```

## 6. metaEnv から safetyEnv を作る

`metaEnv` を作った後、safetyEnv 構築処理に入る。
この処理の最初に、`hotSwapIn` 前の旧コントローラ側 action を内部的に `.old` 付き action に変える。

### 6.1 hotSwapIn 前の old action を uncontrollable 化する

Traditional DUCS では、`hotSwapIn` 前はまだ旧コントローラ `C_old` が動いている段階である。
この段階の旧コントローラの通常 action を update controller が勝手に止められると困る。

そこで、safetyEnv を作る最初の段階で、`hotSwapIn` 前の状態にある controllable action を内部的にリネームする。

```text
a -> a.old
```

実装上は、`beginFluent` が false の状態を `hotSwapIn` 前とみなす。

```text
beginFluent = false
  => hotSwapIn 前

beginFluent = true
  => hotSwapIn 後
```

`hotSwapIn` 前で、かつ action が `controllableActions` に含まれる場合、その遷移を次のように置き換える。

```text
s --a--> t

を

s --a.old--> t

にする。
```

理由は、GR game の controllable / uncontrollable の区別が action 名単位で決まるためである。
同じ `a` のままだと、

```text
hotSwapIn 前の a は uncontrollable にしたい
hotSwapIn 後の a は controllable として扱いたい
```

という区別がしにくい。
そこで、`hotSwapIn` 前の action を `a.old` という別 action 名にしておく。
`a.old` は controllable action 集合に含まれないため、GR(1) では uncontrollable として扱われる。

一方で、safety formula の fluent 評価では `.old` を外して元の action として扱う。

```text
a.old を fluent 評価するときは a として扱う
```

したがって、`.old` はあくまで controller synthesis 内部で controllability を切り替えるためのラベルである。
要求式の意味を変えるためのラベルではない。

### 6.2 safety formula を評価して badStates を作る

`.old` による uncontrollable 化の後、各 `metaEnv` 状態における fluent valuation を使って `safetyFormulas` を評価する。

`metaEnv` の状態は概念的に次の組である。

```text
(E_u state, fluent valuation)
```

したがって各状態で、例えば次のような fluent 値が決まる。

```text
stopFluent = false
startFluent = true
f_a = true
f_b = false
```

この valuation を使って、各 safety formula を評価する。
ここでの `safetyFormulas` は、守るべき性質そのものというより、実装上は違反条件として作られている。

例えば:

```text
old safety:
  formula_x = !stopFluent && violation(Gx_old)

new safety:
  formula_y = startFluent && violation(Gy_new)
```

したがって、formula が true になる状態は pruning 対象の違反状態である。

処理は2段階で行われる。

```text
1. formula 評価フェーズ
   metaEnv の全状態・全 formula を評価して、違反状態集合 badStates を作る。

2. pruning 適用フェーズ
   badStates に含まれる状態の outgoing transitions をコピーしないことで、
   safetyEnv を作る。
```

擬似コードでは次の通りである。

```text
badStates = {}

for formula in safetyFormulas:
  for s in metaEnv.states:
    if formula(s) == true:
      badStates.add(s)

safetyEnv = new MTS(initial(metaEnv))

for s in metaEnv.states:
  safetyEnv.addState(s)

  if s not in badStates:
    for each transition s --a--> t in metaEnv:
      safetyEnv.addTransition(s, a, t)
  else:
    // s は違反状態。
    // outgoing transition はコピーしない。

safetyEnv.removeUnreachableStates()
```

最後の `removeUnreachableStates()` は、違反状態の outgoing を消した結果、初期状態から到達できなくなった状態と、それらに関わる遷移を削除する cleanup である。
これは safety losing の逆伝搬ではない。
初期状態から到達可能な違反状態は、outgoing なしの dead-end 状態として残り得る。

この段階では、違反状態への逆伝搬はしない。
つまり、次のような遷移は残り得る。

```text
good --a--> bad
bad  // outgoing なし
```

また、この段階では複数の違反状態を1つの共通 `ERROR` 状態にまとめない。
違反状態は元の state ID のまま、出辺なしの dead-end 状態として残る。

```text
bad_10  // outgoing なし
bad_27  // outgoing なし
bad_45  // outgoing なし
```

したがって、この safety formula pruning は次のように整理できる。

```text
bad を ERROR にまとめない。
bad への incoming edge は消さない。
bad から先だけ消す。
bad から先を消した結果、初期状態から到達不能になった状態は cleanup で消す。
逆伝搬もしない。
```

後段の controller synthesis / game solving が、違反状態へ制御不能に入る可能性などを考慮して losing state を判断する。

### 6.3 この段階での backward propagation はしない

この safety formula pruning では、formula 違反状態の outgoing transition を消すだけである。

```text
metaEnv -> safetyEnv_before_DDT:
  badStates の outgoing を消す。
  その後、初期状態から到達不能になった状態を cleanup する。
  badStates への incoming は残す。
  badStates を共通 ERROR にまとめない。
  badStates からの backward propagation はしない。
```

ただし、後段の GR(1) game solving では dead-end や ERROR から rank/fixpoint 計算を通じて losing 情報が backward 的に伝播する。

## 7. DontDoTwice monitor を合成する

safety formula pruning 後の `safetyEnv` に対して、`DontDoTwice` monitor を合成する。

legacy Traditional DUCS では、標準の対象は次の2つである。

```text
stopOldSpec
startNewSpec
```

`DontDoTwice` は fluent ではなく、小さな monitor LTS である。
例えば `stopOldSpec` 用の monitor は概念的には次の形になる。

```text
D0 --stopOldSpec--> D1
D1 --stopOldSpec--> ERROR
```

`stopOldSpec` 以外の action は、`D0` と `D1` で自己ループする。

```text
D0 --a--> D0
D1 --a--> D1
```

`startNewSpec` 用も同様である。

この monitor を safety-pruned environment と並列合成する。

```text
safetyEnv_after_DDT =
  safetyEnv_before_DDT
  || DontDoTwice(stopOldSpec)
  || DontDoTwice(startNewSpec)
```

その結果、1回目の `stopOldSpec` / `startNewSpec` は許されるが、2回目は `ERROR` に行く。

```text
1回目の stopOldSpec:
  allowed

2回目の stopOldSpec:
  ERROR

1回目の startNewSpec:
  allowed

2回目の startNewSpec:
  ERROR
```

`stopFluent` / `startFluent` と `DontDoTwice` の役割は異なる。

```text
stopFluent/startFluent:
  G_old/G_new の有効範囲を状態ごとに判定するための記憶。
  metaEnv 上で formula を評価するために使う。

DontDoTwice:
  update action の2回目を構造的に ERROR にする monitor。
  fluent ではなく LTS として safetyEnv に合成する。
```

`DontDoTwice` も fluent と formula で表せなくはない。
例えば `stopFluent && stopOldSpec` のような条件を違反にする方法も考えられる。
ただし現在の Traditional DUCS 実装では、専用の monitor LTS を合成して制約している。

Draw タブに表示される `E_u||G(safety)` の `ERROR` は、この `DontDoTwice` monitor 由来の `ERROR(-1)` である可能性が高い。
一方、`G_old/G_new/G_T` の formula 違反状態は共通 `ERROR` にまとめられず、元の state ID のまま dead-end として残る。

```text
Draw の単一 ERROR:
  DontDoTwice monitor 由来。

formula 違反状態:
  ERROR にまとめない。
  それぞれ dead-end 状態として残る。
```

legacy Traditional DUCS では、標準の `DontDoTwice` 対象に `reconfigure` は含まれない。
`reconfigure` の進行は mapping environment の遷移構造と、後段の GR progress guarantee で扱う。

## 7.5 Optional: Safety Backward Pruning (SBP)

`safetyBackwardPruning` は、標準 Traditional DUCS の必須ステップではなく optional preprocessing である。
有効な場合、`DontDoTwice` 合成後の `safetyEnv` に対して、GR(1) synthesis に入る直前に実行される。

```text
safetyEnv_after_DDT
  -> SafetyBackwardPruning
  -> pruned safetyEnv
  -> GR(1) synthesis
```

SBP の入力は次である。

```text
safetyEnv
controllableActions
```

SBP は、dead-end を起点として safety losing な状態を backward に広げ、GR(1) に渡す前の `safetyEnv` を縮小する。
概念的な処理は次である。

```text
1. outgoing transition を持たない dead-end states を losingStates の初期集合にする。

2. predecessor rule により losingStates を backward に拡張する。

3. losingStates を取り除く。

4. losing state に到達する controllable action を削る。

5. 到達不能状態を削除する。
```

predecessor rule は概念的には次のように見られる。

```text
state s が losing になる条件:

1. s に outgoing transition がない。

2. s から uncontrollable action で losing state に到達できる。

3. s に uncontrollable action がなく、
   losing state を避けられる controllable action もない。
```

ただし、SBP は GR(1) の代替ではない。
SBP は safety / dead-end 起点の losing propagation を事前に行うだけであり、`stopOldSpec`、`reconfigure`、`startNewSpec` の progress guarantee までは解かない。
最終的な liveness / progress 条件は、SBP 後の `safetyEnv` 上で GR(1) が解く。

Traditional DUCS での SBP の効果は限定的になりやすい。
理由は、SBP が次の重い処理をすべて終えた後に実行されるためである。

```text
MapE_all 構築
E_u 構築
metaEnv 構築
safety formula 評価
safetyEnv 構築
DontDoTwice 合成
  ↓
SBP
  ↓
GR(1)
```

つまり SBP は、Traditional DUCS の根本的に重い full `metaEnv` 構築や full `safetyEnv` 構築のコストを減らせない。
減らせるのは、主に GR(1) に渡す `safetyEnv` の状態・遷移数である。

したがって、GR(1) が支配的なケースでは SBP により GR(1) 時間が下がる可能性がある。
一方で、実験で `metaEnv -> safetyEnv` 構築が支配的な場合、Traditional DUCS における SBP は全体時間を大きく改善しにくい。

短くまとめると次の通りである。

```text
Traditional SBP:
  GR(1) 前の掃除としては意味がある。
  ただし full metaEnv / full safetyEnv を作った後に走るため、
  Traditional DUCS の根本的な状態爆発は避けられない。
```

## 8. safetyEnv 上で GR(1) を解く

`DontDoTwice` まで合成した後の `safetyEnv` が、GR(1) synthesis の入力になる。
`safetyBackwardPruning` が有効な場合は、SBP 後の `safetyEnv` が GR(1) に渡される。

```text
safetyEnv
  -> optional SBP
  -> GR(1) synthesis
```

GR(1) goal には、更新開始と更新完了に関する progress 条件を入れる。
legacy Traditional DUCS では、概念的には次の形である。

```text
assumption:
  hotSwapIn

guarantees:
  stopOldSpec
  reconfigure
  startNewSpec
```

これは3つを別々に合成するという意味ではない。
1つの GR(1) game に、3つの guarantee を同時に入れて解く。

概念的には次に近い。

```text
hotSwapIn -> (<> stopOldSpec && <> reconfigure && <> startNewSpec)
```

実装寄りには、それぞれの action を「一度起きたら true のままになる fluent」として作り、GR(1) の assumption / guarantee に入れる。

```text
GF hotSwapIn_fluent
  -> GF stopOldSpec_fluent
   && GF reconfigure_fluent
   && GF startNewSpec_fluent
```

ここでの `stopOldSpec`, `reconfigure`, `startNewSpec` の順序は、この GR(1) goal だけでは強制されない。
GR(1) が要求しているのは、`hotSwapIn` 後にこれらが最終的に達成できることである。
順序を制約したい場合は、mapping environment の構造、safety formula、またはtransition requirement集合`G_T`側で表現する必要がある。

また、GR(1) は `safetyEnv` 上の全パスをそのまま残す処理ではない。
`safetyEnv` から、safety を破らず、かつ progress guarantee を満たせる winning strategy を選ぶ。
そのため、`safetyEnv` に存在する複数の安全な順序やパスが、最終 controller にすべて残るとは限らない。

### 8.1 GR(1) で winning / losing を計算する

GR(1) solver は rank/fixpoint 計算により、状態が winning か losing かを判定する。

```text
rank が finite:
  winning

rank が infinity:
  losing
```

前段の safety formula pruning では backward propagation をしなかった。
しかし GR(1) では、dead-end や ERROR、または liveness を達成できない状態から、predecessor へ losing 情報が伝播する。

```text
dead-end / ERROR / liveness failure
  -> predecessor に影響
  -> rank 更新
  -> winning / losing を計算
```

ただし、これは単純に「bad state から全 predecessor を消す」処理ではない。
game として controllable / uncontrollable を区別して判定する。

```text
controllable predecessor:
  controller が別の winning successor を選べるなら losing にならない。

uncontrollable predecessor:
  environment が losing successor に進めるなら、その predecessor は losing になりやすい。
```

### 8.2 非決定的な safetyEnv の場合

`safetyEnv` が非決定的な場合、実装では subset construction により perfect-information game を作ってから GR(1) を解く。

```text
nondeterministic safetyEnv
  -> subset construction
  -> perfect-information game
  -> GR(1) solving
```

`safetyEnv` が決定的な場合は、そのまま GR game を作って解く。

## 9. winning strategy から update controller を作る

GR(1) の rank/fixpoint 計算が終わったら、まず初期状態が winning か確認する。

```text
if initial(safetyEnv) is losing:
  controller は合成できない

if initial(safetyEnv) is winning:
  winning strategy を作る
```

初期状態が winning の場合、solver は winning states と rank に基づいて strategy を構築する。

この strategy では、controllable action については controller が選べるため、rank がよくなる winning successor を選ぶ。
一方、uncontrollable action は環境が選ぶため、winning state から出る uncontrollable successor は戦略側に残す必要がある。

```text
winning region + rank
  -> winning strategy
  -> controller MTS
  -> CompactState
  -> C_u
```

ここで重要なのは、最終的な update controller `C_u` は `safetyEnv` 全体ではないという点である。
`C_u` は、`safetyEnv` 上で GR(1) の winning strategy として選ばれた部分を LTS/MTS として具体化したものである。

```text
C_u =
  safetyEnv 上で
  safety を破らず
  hotSwapIn 後に stopOldSpec / reconfigure / startNewSpec を達成できる
  winning strategy
```

したがって、`safetyEnv` に存在した全パスが `C_u` に残るわけではない。
同じ `safetyEnv` に複数の安全な候補パスが存在しても、最終 controller には winning strategy として選ばれたパスだけが残る場合がある。
これは、最終 controller が「全候補パス」ではなく「選ばれた winning strategy」だからである。

## 10. removeOldTransitions で .old を外す

`.old` は合成内部で controllability を切り替えるための一時的なラベルである。
そのため、最終出力前に `.old` を外して、元の action 名に戻す。

```text
a.old -> a
```

処理としては、GR(1) 後に得られた controller の各遷移を見て、`.old` 付き action なら suffix を外してコピーする。

```text
if action is not .old:
  add s --action--> t

if action is .old:
  add s --withoutOld(action)--> t
```

例:

```text
move.old -> move
a.old    -> a
```

したがって、`.old` のライフサイクルは次のように整理できる。

```text
metaEnv:
  まだ .old は付いていない。

safetyEnv 構築開始:
  hotSwapIn 前の controllable action に .old を付ける。
  これにより GR(1) では旧コントローラ側 action が uncontrollable として扱われる。

fluent valuation / safety formula 評価:
  .old を外して元の action として扱う。

GR(1):
  .old 付き action は uncontrollable action として扱われる。

最終出力前:
  removeOldTransitions で .old を外す。
```

## ここまでの流れ

ここまでの Traditional DUCS は次のように整理できる。

```text
Input:
  C_old
  E_old, E_new
  R
  G_old, G_new
  G_T

1. for each i:
     MapE_i = MappingEnvironment(Ei_old, Ei_new, Ri)

2. MapE_all = MapE_1 || MapE_2 || ... || MapE_i

3. E_u = UpdatingEnvironment(C_old, MapE_all, hotSwapIn, stopOldSpec, startNewSpec)

   3.1 Explore reachable pairs:
       (initial(C_old), initial(MapE_all))
       by lockstep actions.

   3.2 For each reachable pair (c, m):
       add c --hotSwapIn--> m.

   3.3 Copy MapE_all states/transitions into update region.

   3.4 Add self-loops on update-region states:
       stopOldSpec
       startNewSpec

4. Build update safety formulas and extract fluents:
     for each Gx_old:
       formula_x = !stopFluent && violation(Gx_old)
       formulaFluents_x = Fluents(formula_x)

     for each Gy_new:
       formula_y = startFluent && violation(Gy_new)
       formulaFluents_y = Fluents(formula_y)

     for each Tz:
       formula_z = transition requirement formula
       formulaFluents_z = Fluents(formula_z)

     safetyFormulas = all formula_x/formula_y/formula_z
     goalFluents    = union of all formula fluents

5. metaEnv = E_u || goalFluents

6. Build safetyEnv:
     first, rename pre-hotSwapIn controllable actions:
       a -> a.old

     evaluate safetyFormulas on metaEnv:
     badStates = {s | exists formula. formula(s) = true}
     safetyEnv_before_DDT =
       metaEnv with outgoing transitions removed from badStates
       and unreachable states cleaned up from the initial state

7. Compose DontDoTwice monitors:
     safetyEnv =
       safetyEnv_before_DDT
       || DontDoTwice(stopOldSpec)
       || DontDoTwice(startNewSpec)

7.5 Optional:
     if safetyBackwardPruning is enabled:
       safetyEnv = SafetyBackwardPruning(safetyEnv, controllableActions)

8. Solve GR(1) on safetyEnv:
     assumption:
       hotSwapIn

     guarantees:
       stopOldSpec
       reconfigure
       startNewSpec

     These guarantees are solved together in one GR(1) game.

9. If the initial state is winning:
     build winning strategy
     convert strategy to controller MTS / CompactState
     obtain C_u

10. Final output cleanup:
      removeOldTransitions:
        a.old -> a
```

## 時間・メモリがかかる箇所

Traditional DUCS で時間やメモリがかかりやすい箇所は、現時点では次の4箇所として整理できる。

```text
1. MapE_all の構築
   MapE_1 || MapE_2 || ... || MapE_i

2. metaEnv の構築
   E_u || goalFluents

3. safetyEnv の構築
   metaEnv 上で safety formula を評価し、違反状態を dead-end 化する

4. GR(1) synthesis
   safetyEnv 上で winning / losing rank を計算し、strategy を作る
```

このうち、最初に効く可能性があるのは `MapE_all` の並列合成である。
各 mapping environment の直積になるため、概念的には次のように増え得る。

```text
|States(MapE_all)| ~= |States(MapE_1)| * |States(MapE_2)| * ... * |States(MapE_i)|
```

実際には同期 action や到達不能状態の削除により、この上限通りになるとは限らない。
しかし `MapE_all` が大きくなると、その後の `E_u`、`metaEnv`、`safetyEnv`、GR(1) のすべてに影響する。

`E_u` 構築自体も、`C_old` と `MapE_all` の reachable pair 探索や `hotSwapIn` 接続を作るため重くなり得る。
ただし、状態爆発の主因としては、`MapE_all` の並列合成と、後段の `metaEnv` / `safetyEnv` 構築の方が目立ちやすい。

### metaEnv から safetyEnv を作る段階が重い理由

実験上、`metaEnv` から `safetyEnv` を作る段階が最も時間を使っているように見える場合がある。
これは自然である。
この段階は単なる枝刈りではなく、Traditional DUCS で最大級に大きい `metaEnv` に対して複数の全体走査を行うためである。

主な処理は次の通りである。

```text
1. hotSwapIn 前の旧 action を .old にリネームする
   metaEnv の状態・遷移を走査する。

2. Fluent valuation を構築する
   metaEnv を BFS し、各状態でどの fluent が true かを計算する。

3. safety formula を全状態で評価する
   全状態 x 全 formula を評価する。

4. 違反状態の outgoing を消した MTS を作る
   metaEnv の状態・遷移を見ながら、新しい safetyEnv_before_DDT を構築する。

5. DontDoTwice monitor を合成する
   safetyEnv_before_DDT と monitor LTS を合成する。
```

`safetyBackwardPruning` を有効にしている場合は、この後で SBP も走る。
ただし、SBP は `metaEnv` 構築や safety formula 評価の後に実行されるため、この段階までのコストは減らせない。

計算量のイメージは次のように見られる。

```text
Fluent valuation 構築:
  O(|Transitions(metaEnv)| * #goalFluents)

Safety formula 評価:
  O(|States(metaEnv)| * #safetyFormulas * formula評価コスト)

Pruned MTS 構築:
  O(|States(metaEnv)| + |Transitions(metaEnv)|)
```

ここで重要なのは、`Fluent valuation` の構築自体は基本的に1回だけであるという点である。
ただし、その1回で `metaEnv` 全体を BFS し、遷移ごとに fluent の継続・開始・終了を確認するため、大きな `metaEnv` では十分に重い。

```text
Fluent valuation 構築:
  1回だけ。
  ただし metaEnv の遷移全体を走査する。

Safety formula 評価:
  valuation を作り直すわけではない。
  作った valuation を使い、全状態 x 全 formula を評価する。
```

したがって、「何度も valuation を作っている」わけではない。
正確には、次のように複数種類の重い処理が連続している。

```text
valuation 構築で metaEnv を大きく走査する。
formula 評価で metaEnv の全状態を formula 数ぶん評価する。
MTS 再構築で metaEnv の状態・遷移をもう一度見る。
DontDoTwice 合成でも safetyEnv を合成する。
```

実験ログを見る場合は、次の内訳を確認すると、どこが支配的か分かりやすい。

```text
Traditional DUC safetyEnv 構築時間内訳:
  hotSwapIn 前の旧 action を uncontrollable 化する時間
  Fluent valuation 構築時間
  Safety formula を全状態で評価する時間
  Safety 違反状態を除去した MTS 構築時間
  DontDoTwice goal 合成時間
```

`Fluent valuation 構築時間` が大きい場合は、`metaEnv` の遷移数と `#goalFluents` が効いている可能性が高い。
`Safety formula を全状態で評価する時間` が大きい場合は、`metaEnv` の状態数、formula 数、formula の複雑さが効いている可能性が高い。

論文や説明では、次のようにまとめられる。

```text
Traditional DUCS では、full metaEnv を構築した後に、
全 fluent valuation を構築し、
全状態に対して全 safety formula を評価する。
そのため metaEnv が大きい場合、safetyEnv 構築が最も重い段階になりやすい。
```

## 論文用の短い擬似コード

論文用Algorithm 1では、上で確認したfluent、metaEnv、違反状態の生成、DontDoTwiceなどの
内部処理を補助関数へ隠し、Traditional DUCSの構築順序だけを次のように示す。

```text
Algorithm TraditionalDUCS

Input:
  C_old
  E_old = {E_old^1, ..., E_old^i}
  E_new = {E_new^1, ..., E_new^i}
  R     = {R^1, ..., R^i}
  G_old = {G_old^1, ..., G_old^j}
  G_new = {G_new^1, ..., G_new^k}
  G_T   = {G_T^1, ..., G_T^l}

Output:
  C_u

1. for each x in {1, 2, ..., i} do
       E_map^x <- MappingComponent(E_old^x, E_new^x, R^x)

2. E_map <- ParallelCompose(E_map^1, E_map^2, ..., E_map^i)

3. E_u <- UpdatingEnvironment(C_old, E_map)

4. P <- UpdateFormulas(G_old, G_new, G_T)

5. AS_safe <- SafetyAnalysisSpace(E_u, P)

6. C_u <- GR1Synthesis(AS_safe, Gamma_DCU)

7. return C_u
```

`UpdatingEnvironment`の入力へ`hotSwap`、`stopOldSpec`、`startNewSpec`を明示的に並べない。
これらは本文で定義済みの更新時事象として補助関数内部で用いる。
`UpdateFormulas`は更新前要求、更新後要求、transition requirementを第II章の変換規則に従って
更新用式集合`P`へ変換する。`SafetyAnalysisSpace`は`E_u`上で`P`への違反判定を反映した
分析空間`AS_safe`を構築する。

Traditional DUCSの論文用アルゴリズムにはSBPを含めない。
`SafetyAnalysisSpace`で初期状態の要求違反が確定した場合、または`GR1Synthesis`で
winning strategyが存在しない場合の合成不能は、アルゴリズム中に分岐を増やさず本文で説明する。

## 次に確認すること

以降の確認では、必要に応じて各実装詳細をさらに分解する。
特に、GR(1) の rank 計算、strategy から MTS への変換、論文用アルゴリズムとしてどこまで抽象化するかを詳しく確認する。
