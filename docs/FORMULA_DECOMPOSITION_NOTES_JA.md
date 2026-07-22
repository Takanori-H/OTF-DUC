# Formula decomposition メモ

最終更新: 2026-06-09

英語版は `FORMULA_DECOMPOSITION_NOTES.md` に残している。

このメモは、transition requirement、old safety property、new safety property の分解についての現在の議論を記録する。

現時点では自動分解は実装しない。実験で分解が必要な場合は、`.lts` file 側で分解した formula を明示的に書く。

## 動機

O-DUCS は mapping component、old safety component、new safety component、transition requirement tester、fluent machine を含む DCS box list を構築する。

大きな formula は大きな tester model を生成し得る。AND 形の formula を複数の小さな tester に分割できれば、local successor generation の cost を減らし、悪い branch をより早く検出できる可能性がある。

ただし、分解が常に有利とは限らない。分解によって box-list component 数が増えたり、重複作業が発生したりする。

## 現在の scope 判断

Transition requirement decomposition は次で有用な可能性がある。

- O-DUCS。
- FG-O-DUCS。
- SFG-O-DUCS。

Old safety と new safety の decomposition は、通常 O-DUCS だけを対象に考える。

```text
on_the_fly
no fine_grained
no selective_fine_grained
```

FG-O-DUCS や SFG-O-DUCS では、safety 名が `stopOldSpec_P` や `startNewSpec_P` のような update-action 名を定義する。そのため、この段階では old/new safety decomposition は計画しない。

## 分解可能な top-level AND

最も単純で安全な case は、assertion reference を展開し、必要に応じて先頭の `[]` を取り除いた後に top-level AND がある場合である。

例:

```fsp
ltl_property T = [](A && B && C)
```

これは次のように扱える。

```fsp
ltl_property T_1 = []A
ltl_property T_2 = []B
ltl_property T_3 = []C
```

AND が assertion の裏に隠れている場合も同じである。

```fsp
assert S = (A && B && C)
ltl_property T = []S
```

assertion 展開後は同じ形になる。

## Distributed OR/AND pattern

有用な formula の中には top-level AND ではないが、論理分配によって分解できるものがある。

ProductionCell には次の形がある。

```fsp
ltl_property T_REMOVE_POLISHED_OR_NEW_REQ_OTF_1 =
    []REMOVE_POLISHED_OR_NEW_REQ_1

assert REMOVE_POLISHED_OR_NEW_REQ_1 =
    ((StopOldSpec && !StartNewSpec) -> (S_NEW_1 || (out[1] -> Faulty[1])))

assert S_NEW_1 =
    (NEW_TOOL_ORDER_1 && NEW_OUT_IF_FINISHED_1 && DRILL_ONCE_1 && PAINT_ONCE_1 && CLEAN_ONCE_1)
```

展開後の形は次のようになる。

```text
[](G -> ((A && B && C && D && E) || F))
```

ここで:

```text
G = StopOldSpec && !StartNewSpec
F = out[1] -> Faulty[1]
```

これは次と同値である。

```text
[](G -> (A || F))
&& [](G -> (B || F))
&& [](G -> (C || F))
&& [](G -> (D || F))
&& [](G -> (E || F))
```

escape branch `F` は分解後の各 formula に残さなければならない。`[](G -> A)`, `[](G -> B)` のように分解すると元の formula より強くなり、正しくない。

有用な分配則:

```text
X || (A && B)  ==  (X || A) && (X || B)
(A && B) || X  ==  (A || X) && (B || X)
G -> (A && B)  ==  (G -> A) && (G -> B)
```

これらの rule は、変換を明示的に実装し、test した場合にだけ適用すべきである。

## Old safety decomposition

通常 O-DUCS では、old safety decomposition は概念的には単純である。

例えば:

```fsp
ltl_property P = [](A && B)
```

を次のように分解する。

```fsp
ltl_property P_1 = []A
ltl_property P_2 = []B
```

このとき両 component は同じ legacy update event で制御する。

```text
stopOldSpec
```

これは monitor / tester decomposition だけである。通常 O-DUCS で `stopOldSpec_P_1` や `stopOldSpec_P_2` を生成してはいけない。

## New safety decomposition

通常 O-DUCS では、new safety decomposition は new-safety lookup table を構築する前に行うべきである。

想定 pipeline:

1. new goal safety list を読む。
2. assertion reference を展開する。
3. 安全に分解できる AND-shaped formula を分解する。
4. 分解後 component ごとに tester を生成する。
5. 各 component に必要な fluent subset を抽出する。
6. 分解後 component ごとに `safetyComponentIndicesMap` と `safetyStateLookupMap` を構築する。
7. `startNewSpec` 時に、各分解後 new safety component を自分の lookup table で初期化する。

分解後のすべての new safety component は、同じ legacy update event で制御する。

```text
startNewSpec
```

これも monitor / tester decomposition だけである。

## FG/SFG の safety decomposition が異なる理由

FG-O-DUCS と SFG-O-DUCS では、safety 名が update action を定義する。

```text
stopOldSpec_P
startNewSpec_P
```

safety `P` を自動的に `P_1` と `P_2` に分解すると、実装は次のどちらにするかを決めなければならない。

```text
startNewSpec_P
```

を両 component に使うのか、それとも:

```text
startNewSpec_P_1
startNewSpec_P_2
```

を生成するのか。後者は protocol granularity を変える。前者には、分解後 tester を元の update action に結びつける monitor-only split design が必要になる。

そのため現時点では、old/new safety decomposition は通常 O-DUCS 向けの将来設計に限定し、FG/SFG には入れない。

## Cost concern

分解は単一 tester より遅くなる可能性がある。

性能を悪化させる可能性がある場合:

- 元の tester がすでに小さい。
- 分解後 formula が多くの fluent を共有する。
- 分解後 tester の state / transition 合計が元の tester を超える。
- box-list length の増加が successor generation を支配する。
- new safety decomposition が多くの lookup table を作る。

将来の自動実装では、分解を選ぶ前に元の tester と分解後 tester を比較すべきである。

考えられる decision score:

```text
score = states + alpha * transitions + beta * componentCount
```

保守的な rule の例:

```text
sum(splitStates) <= originalStates
sum(splitTransitions) <= originalTransitions
splitCount <= maxSplitCount
```

これはまだ実装していない。

## 現在の実用方針

自動 formula decomposition は現在実装しない。

分解が必要な場合は `.lts` file に書く。

- individual `assert` definition を作る。
- individual `ltl_property` definition を作る。
- 各 property を別々の `transition = ...` entry として追加する。

O-DUCS-style transition requirement では、必要な場合は OTF property に `[]` を付ける現在の convention を保つ。
