# FG-DUCS / FG-O-DUCS 設計・実装メモ

最終更新: 2026-06-09

英語版は `FG_DUCS_DESIGN.md` に残している。

このメモは、Traditional DUCS と O-DUCS における fine-grained update event および selective fine-grained update event の現在の設計・実装状態を記録する。

## モード

`.lts` flag `fine_grained` は fine-grained update event を選択する。
`.lts` flag `selective_fine_grained` は selective fine-grained update event を選択する。
この2つの flag は同時に指定できない。

| Definition flags | Mode | 想定 target 名 |
|---|---|---|
| `fine_grained` なし、`on_the_fly` なし | Traditional DUCS | `UpdCont` |
| `fine_grained` あり、`on_the_fly` なし | FG-DUCS | `UpdCont_FG` |
| `selective_fine_grained` あり、`on_the_fly` なし | Selective FG-DUCS | `UpdCont_SFG` |
| `fine_grained` なし、`on_the_fly` あり | O-DUCS | `UpdCont_OTF` |
| `fine_grained` あり、`on_the_fly` あり | FG-O-DUCS | `UpdCont_OTF_FG` |
| `selective_fine_grained` あり、`on_the_fly` あり | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

Fine-grained mode では次の list form が必要である。

- `oldEnvironment = {...}`
- `newEnvironment = {...}`
- `mapRelation = {...}`

legacy の `mapping = ...` form は legacy DUCS / O-DUCS では引き続き使えるが、fine-grained mode と selective fine-grained mode ではサポートしない。

## 生成される update action

各 old safety property `P` について、実装は次を生成する。

```text
stopOldSpec_P
```

各 new safety property `P` について、実装は次を生成する。

```text
startNewSpec_P
```

各 mapping component について、relation action は次のように扱う。

- `reconfigure` は内部で `reconfigure_<mapping component name>` に正規化する。
- 明示的な `reconfigure_*` は許可する。
- 各 mapping component は reconfigure action をちょうど1つ持つ。

生成名は transition requirement から参照できる公開名として扱う。

`fine_grained` の transition requirement では、legacy 名 `stopOldSpec`, `reconfigure`, `startNewSpec` は受け付けない。update event が必要な場合は、`stopOldSpec_P`, `reconfigure_MAP`, `startNewSpec_P` のような生成名を使う。

`selective_fine_grained` の transition requirement では、update kind ごとに legacy 名か生成名/group 名のどちらかを使える。同じ kind について複数の transition requirement 全体で legacy 名と fine-grained 名を混ぜた場合は error にする。例えば `stopOldSpec` と `reconfigure_MAP` は同時に使えるが、`stopOldSpec` と `stopOldSpec_P` は同時に使えない。

生成 action suffix に使う safety 名は次の形式に一致する必要がある。

```text
[A-Za-z_][A-Za-z0-9_]*
```

一致しない場合は compile error とし、safety property 名を変更するよう diagnostic を出す。

suffix `others` は selective grouping 用に予約する。

## Selective fine-grained grouping

Selective mode は、まず full fine-grained mode と同じ candidate update action を作る。その後 transition requirement を scan し、参照された candidate だけを individual action として残す。

scan 対象は次の通り。

- direct action reference。
- `{a, b}` のような action set。
- transition requirement で参照された fluent の initiating / terminating action。
- transition requirement から参照された nested assertion。

update kind ごとに独立して、次のように group 化する。

- legacy action が参照されている kind、または individual action も `*_others` action も参照されていない kind は、legacy action 名 (`stopOldSpec`, `reconfigure`, `startNewSpec`) を使う。
- 参照された candidate action は individual のまま残す。
- 参照されていない candidate は `stopOldSpec_others`, `reconfigure_others`, `startNewSpec_others` にまとめる。
- `*_others` を直接参照した場合は group を強制的に生成する。ただし group に入れる対象が空なら error にする。

reconfiguration では、各 mapping component が1つの candidate action を持つ。relation action `reconfigure` はまず `reconfigure_<mapping component name>` に正規化される。その後 selective grouping によって、参照されていない mapping component は `reconfigure_others` に relabel されるため、group 化された mapping component は同じ action で同期する。

## FG-O-DUCS の意味

FG-O-DUCS と Selective FG-O-DUCS は、fine-grained action ごとに marking component を作るのではなく、DCS box list 内の単一の synthetic progress slot を使う。

Progress は `ProgressRegistry` で表現する。`ProgressRegistry` は任意サイズの `BigInteger` completion mask を compact な synthetic state ID に map する。そのため、fine-grained update action 数は 63 個に制限されない。

update は、生成されたすべての progress action が完了した場合にだけ finish できる。

- すべての `stopOldSpec_*`。
- すべての `reconfigure_*`。
- すべての `startNewSpec_*`。

その後にだけ `hotSwapOut` を検討できる。さらに既存の new-controller stitching guard も成功する必要がある。

### Safety activation

old safety `P` は、`stopOldSpec_P` が完了するまで Active / Trace / Enforce である。

new safety `P` は、`startNewSpec_P` が完了した後にだけ Active / Trace / Enforce になる。

`startNewSpec_P` が発火したとき、対応する new safety LTS state は、`P` から抽出された fluent LTS の現在 state を見て決める。実装は次を構築する。

- new safety ごとの fluent component index list。
- `[fluent state...]` から対応する new safety state への lookup table。

FG-O-DUCS では、`startNewSpec_P` はその start action に対応する new safety property だけを同期する。Selective mode では、`startNewSpec_others` のような grouped action は、その group に割り当てられたすべての new safety property を同期する。

この lookup に使う fluent LTS は synthesis machine である。Active でも Enforce でもないが、OTF search 全体で Trace-enabled のまま残る。同じ fluent が後で別の new safety property の初期状態決定に必要になる可能性があるためである。

## FG-DUCS の意味

FG-DUCS と Selective FG-DUCS は Traditional DUCS の戦略を保つ。つまり update environment `E_u` を明示的に構築してから GR control problem を解く。

Fine-grained `E_u` generation は legacy `E_u` generator とは分けて実装する。

- `stopOldSpec_*` と `startNewSpec_*` は update state に self-loop として追加する。
- `reconfigure_*` は generated mapping environment transition に残す。
- Traditional DUCS には `hotSwapOut` は追加しない。

FG-DUCS の GR goal は、生成されたすべての progress action を guarantee として含む。

- すべての `stopOldSpec_*`。
- すべての `reconfigure_*`。
- すべての `startNewSpec_*`。

DontDoTwice も、`reconfigure_*` と `*_others` group を含むすべての generated progress action に適用する。

old/new safety wrapping は safety ごとに行う。

- old safety `P` は `stopOldSpec_P` がまだ起きていない間 enforce する。
- new safety `P` は `startNewSpec_P` が起きた後 enforce する。

## Transition requirement

Transition requirement は生成された fine-grained action 名を参照できる。

Traditional DUCS の formula conversion は、次のような safety-style transition requirement を扱えるように調整した。

```fsp
ltl_property T = []((StopOldSpecFG && !StartNewSpecFG) -> !{a, b, c})
```

処理は次の通り。

- synthesis formula representation に変換する前に先頭の `[]` を取り除く。
- `{a, b, c}` のような action set を、複数 initiating action を持つ fluent に展開する。

## 探索 heuristic

O-DUCS の frontier は次の順で状態を選ぶ。

```text
marking depth -> 新しい state (LIFO) -> heuristic score
```

heuristic score は update action を強く優先する。score が同じ場合は、ordinary controllable action よりも uncontrollable action を優先する。

つまり、局所的な action order は実質的に次の順になる。

```text
update action -> uncontrollable action -> ordinary controllable action
```

FG-O-DUCS の action priority cost は、update action の順序として使う。

| Action kind | Cost |
|---|---:|
| `hotSwapOut` | 0 |
| `stopOldSpec_*` | 10 |
| `reconfigure_*` | 20 |
| `startNewSpec_*` | 30 |
| `hotSwapIn` | 40 |
| other actions | 100 |

同じ cost の fine-grained action が複数ある場合は、既存の ordering behavior を使う。

Selective FG-O-DUCS は大まかな順序は同じだが、update action 内では grouped `*_others` action よりも individual に参照された action を優先する。

| Action kind | Cost |
|---|---:|
| `hotSwapOut` | 0 |
| individual `stopOldSpec_*` | 10 |
| individual `reconfigure_*` | 20 |
| individual `startNewSpec_*` | 30 |
| `stopOldSpec_others` | 40 |
| `reconfigure_others` | 50 |
| `startNewSpec_others` | 60 |
| `hotSwapIn` | 70 |
| other actions | 100 |

## 主な実装ファイル

- `ltsa.updatingControllers.structures.UpdateProtocolSpec`
  - fine-grained progress action を生成・分類する。
- `ltsa.updatingControllers.structures.SelectiveUpdateProtocolSpecBuilder`
  - transition requirement の参照に基づいて candidate fine-grained progress action を group 化する。
- `ltsa.lts.MappingEnvironmentGenerator`
  - fine-grained mode で legacy `reconfigure` を `reconfigure_<mapping component name>` に正規化する。
- `ltsa.lts.UpdatingControllersDefinition`
  - `UpdateProtocolSpec` を作る。
  - fine-grained controllable action を注入する。
  - OTF mode 用に New Safety fluent lookup data を構築する。
- `ltsa.updatingControllers.structures.UpdatingControllerCompositeState`
  - Traditional path と OTF path の両方に `fineGrained` と `UpdateProtocolSpec` を渡す。
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingEnvironmentGenerator`
  - Traditional FG-DUCS の `E_u` を構築する。
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingControllersUtils`
  - Traditional FG-DUCS の GR goal と safety goal definition を構築する。
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingControllerSafetySynthesizer`
  - Traditional safety pruning に委譲しつつ、すべての progress action を DontDoTwice に渡す。
- `ltsa.updatingControllers.synthesis.UpdatingControllerSynthesizer`
  - legacy / fine-grained Traditional path を選ぶ。
  - legacy / fine-grained OTF DCS engine を選ぶ。
  - New Safety fluent lookup map を box-list index に変換する。
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisFineGrainedDUC`
  - fine-grained OTF-DUC semantics。
  - progress-slot transition。
  - per-safety `startNewSpec_*` synchronization。
  - fine-grained action heuristic cost。
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisSelectiveFineGrainedDUC`
  - selective FG-O-DUCS の exploration heuristic cost。
  - FG-O-DUCS の progress-slot semantics を再利用する。
- `ltsa.lts.CompactStateActionRelabeler`
  - selective grouping 後に mapping component action を relabel する。
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.ProgressRegistry`
  - BigInteger progress mask を synthetic progress state に map する。
- `ltsa.updatingControllers.synthesis.UpdatePhaseEvaluator`
  - `stopOldSpec_*`, `reconfigure_*`, `startNewSpec_*` を legacy update-event category に集約して evaluation log に出す。

## Example naming convention

example file では次の名前を使う。

```text
UpdCont         // Traditional DUCS
UpdCont_FG      // FG-DUCS
UpdCont_SFG     // Selective FG-DUCS
UpdCont_OTF     // O-DUCS
UpdCont_OTF_FG  // FG-O-DUCS
UpdCont_OTF_SFG // Selective FG-O-DUCS
```

更新済み example:

- `Experiment/Example/FineGrainedSmall.lts`
- `Experiment/Example/PowerPlant_FG.lts`

## 現在の確認状況

2026-06-09 時点:

- `mvn -DskipTests=true compile` は成功している。
- `Experiment/Example/FineGrainedSmall.lts` は次の4 target で確認済み。
  - `UpdCont`
  - `UpdCont_FG`
  - `UpdCont_OTF`
  - `UpdCont_OTF_FG`
- `Experiment/Example/PowerPlant_FG.lts` は次の4 target で確認済み。
  - `UpdCont`
  - `UpdCont_FG`
  - `UpdCont_OTF`
  - `UpdCont_OTF_FG`

実装は evaluation run を始めるには十分完了していると考える。ただし、結果を最終的な paper data として扱う前に、大きな benchmark regression と log-processing validation を行う必要がある。

Selective FG-DUCS / FG-O-DUCS implementation は 2026-06-09 に追加した。`Experiment/Example/FineGrainedSmall.lts` と `Experiment/Example/PowerPlant_FG.lts` には `UpdCont_SFG` と `UpdCont_OTF_SFG` target が含まれている。これらの target の example synthesis は、この作業 session ではユーザーが実行する前提で残した。
