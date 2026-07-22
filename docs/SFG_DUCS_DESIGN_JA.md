# Selective Fine-Grained DUCS / O-DUCS メモ

最終更新: 2026-06-09

英語版は `SFG_DUCS_DESIGN.md` に残している。

このメモは、Selective Fine-Grained DUCS と Selective Fine-Grained O-DUCS の現在の設計・実装状態を記録する。

## モード

`.lts` flag `selective_fine_grained` は selective fine-grained update event を選択する。

| Definition flags | Mode | 想定 target 名 |
|---|---|---|
| `selective_fine_grained`, `on_the_fly` なし | Selective FG-DUCS | `UpdCont_SFG` |
| `selective_fine_grained`, `on_the_fly` あり | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

`fine_grained` と `selective_fine_grained` は同時に指定できない。

Selective mode は full FG mode と同じ list-style mapping input を使う。

- `oldEnvironment = {...}`
- `newEnvironment = {...}`
- `mapRelation = {...}`

legacy の `mapping = ...` form は `selective_fine_grained` mode ではサポートしない。

## Candidate action

Selective mode は、まず full FG mode と同じ candidate update action を作る。

各 old safety `P` について:

```text
stopOldSpec_P
```

各 new safety `P` について:

```text
startNewSpec_P
```

各 mapping component について:

- 明示的な relation action `reconfigure_X` はそのまま使う。
- relation action `reconfigure` は `reconfigure_<mapping component name>` に正規化する。
- 各 mapping component は reconfigure action を1つだけ持てる。

suffix `others` は予約語である。`others` という safety 名は拒否し、`.lts` relation definition 内の `reconfigure_others` も拒否する。

## 参照 scan

candidate action 生成後、selective mode は transition requirement を scan し、どの candidate action を individual のまま残す必要があるかを決める。

scan 対象は次の通り。

- direct action reference。
- `{a, b}` のような action set。
- transition requirement から参照された fluent の initiating / terminating action。
- nested assertion reference。

`fine_grained` の transition requirement では、legacy update action 名を禁止する。

```text
stopOldSpec
reconfigure
startNewSpec
```

FG の transition requirement は次のような生成名を使う必要がある。

```text
stopOldSpec_P
reconfigure_MAP
startNewSpec_P
```

selective mode では、update kind ごとに legacy 名か生成名/group 名のどちらかを使える。同じ kind について legacy 名と fine-grained 名を混ぜると error にする。これは別々の transition requirement に書かれている場合も同じである。例えば `stopOldSpec` と `reconfigure_MAINTENANCE` は同時に使えるが、`stopOldSpec` と `stopOldSpec_P` は同時に使えない。

selective mode では、次の group 名も許可する。

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

未知の生成名は fatal diagnostic にする。これにより typo が group 化を黙って変えてしまうことを防ぐ。

## Grouping semantics

Grouping は stop, reconfigure, start action ごとに独立に決める。

ある kind について legacy reference がある場合、または individual reference も明示的な `*_others` reference もない場合、その kind は legacy 名を使う。

```text
stopOldSpec
reconfigure
startNewSpec
```

ある kind の candidate が少なくとも1つ参照された場合、参照された candidate は individual のまま残り、参照されていない candidate は group 化される。

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

`*_others` が直接参照された場合、その group は強制的に生成される。group に入れる対象がなければ compile error にする。

`reconfigure_others` では、参照されていないすべての mapping component が1つの action で同期する。参照された mapping component は individual reconfigure action を保つ。

## Traditional SFG-DUCS

Selective FG-DUCS は Traditional DUCS と full FG-DUCS と同じく、`E_u` を materialize する。

Traditional fine-grained path で使う progress action は生成された progress action だけである。

- generated stop action。
- generated reconfigure action。
- generated start action。
- generated `*_others` group。

GR guarantee set にはすべての generated progress action を入れる。DontDoTwice も grouped action を含むすべての generated progress action に適用する。

Traditional SFG-DUCS には `hotSwapOut` は追加しない。

## Selective FG-O-DUCS

Selective FG-O-DUCS は FG-O-DUCS の progress-slot semantics を再利用する。

DCS box list は単一の synthetic progress component を持つ。`ProgressRegistry` は completion mask を compact な synthetic state ID に map するため、selective mode も 63 progress action に制限されない。

`hotSwapOut` は、すべての generated progress action が完了し、既存の new-controller connection guard が成功した場合にだけ enabled になる。

Grouped action は、その group に割り当てられたすべての member を更新する。

- `stopOldSpec_others` は group に割り当てられたすべての old safety component を停止する。
- `reconfigure_others` は group 化されたすべての mapping component を new-environment 側へ進める。
- `startNewSpec_others` は group に割り当てられたすべての new safety component を開始する。

grouped start action でも、各 new safety component は自分自身の fluent-state lookup を使って正しい initial safety state を選ぶ。Fluent LTS は、後の new safety component が必要とする可能性があるため、update 全体を通して trace を続ける。

## 探索 heuristic

Selective FG-O-DUCS の frontier は次の順で状態を選ぶ。

```text
marking depth -> 新しい state (LIFO) -> heuristic score
```

heuristic score は update action を強く優先する。score が同じ場合は、ordinary controllable action よりも uncontrollable action を優先する。

つまり、局所的な action order は実質的に次の順になる。

```text
update action -> uncontrollable action -> ordinary controllable action
```

update action 内では、grouped `*_others` action よりも、transition requirement で individual に参照された update action を優先する。

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

同じ cost の action は既存の ordering behavior を使う。

## 主な実装ファイル

- `ltsa.updatingControllers.structures.UpdateProtocolSpec`
  - generated progress action とその kind を保持する。
- `ltsa.updatingControllers.structures.SelectiveUpdateProtocolSpecBuilder`
  - full FG candidate と transition-requirement reference から selective protocol を構築する。
- `ltsa.lts.UpdatingControllersDefinition`
  - `selective_fine_grained` を parse する。
  - incompatible flag を拒否する。
  - transition requirement を scan する。
  - generated action reference を validate する。
  - selective mapping component を relabel する。
- `ltsa.lts.CompactStateActionRelabeler`
  - `reconfigure_ENV` などの mapping component action を `reconfigure_others` に relabel する。
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisSelectiveFineGrainedDUC`
  - selective FG-O-DUCS heuristic と debug group logging を実装する。
- `ltsa.updatingControllers.synthesis.UpdatingControllerSynthesizer`
  - `UpdateProtocolSpec.isSelective()` が true のとき selective DCS class を選ぶ。

## 現在の non-goal

Formula decomposition は selective mode の一部としては実装しない。

performance のために decomposed transition requirement が必要な model では、当面は `.lts` file に明示的に分解して書く。

old/new safety decomposition も SFG の一部ではない。FG/SFG mode では safety 名が generated update-action 名を定義するため、自動 safety decomposition は追加の monitor-only design がない限り protocol granularity を変えてしまう。
