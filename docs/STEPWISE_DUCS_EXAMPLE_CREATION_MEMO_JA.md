# Stepwise DUCS 評価例題作成メモ

最終更新: 2026-07-22

## 1. このメモの目的

このメモは、Stepwise DUCS の評価用として作成した `ComputeCluster` と
`EVCharging` の設計意図、LTS 構成、スケール系列、実験設定、確認状況、
および未解決事項を引き継ぐためのものである。論文本体の記述や本実験結果の
整理を目的としない。

当初は共有された Drone の元 LTS を更新例題へ変換する方針だったが、後続の
ユーザー指示により、Stepwise DUCS の評価に適した例題を一から組み直す方針へ
変更した。現在の新規例題は次の二系列である。

1. ComputeCluster: 計算ノードの電力割当ポリシーをオンライン更新する例題
2. EVCharging: EV 充電器の需要応答ポリシーをオンライン更新する例題

本チャット冒頭で使用禁止とされた既存 ArtGallery/Drone 更新モデル、その
YAML、ログ、CSV、および実験結果は、これらの設計や規模判断の根拠にしていない。
KIVA と OTF-DUC も本メモの対象外である。

### 1.1 現時点の要約

- 主に検討するのは、通常運転中のポリシー更新として説明できる
  ComputeCluster の PowerBudget/AdjacentPowerBudget 系列と、EVCharging の
  DemandResponse 系列である。
- 両系列とも環境モデルを変えず、要求だけを OLD から NEW へ変更する。
- local 要求と scope 2 の隣接 cross 要求により、Stepwise の早期判定と SBP が
  後続 component との不要な組合せを抑える構造を持つ。
- passive transition、GR 入力 alphabet、評価 recorder の修正と小規模回帰は完了した。
- 最終採用スケールは未決定であり、これらの修正を含む同一 revision で
  Traditional/Stepwise と no_tr/T1/T2 を再実行して決める。
- 修正前の smoke、timeout、OOM、時間、メモリ、分析空間値を本実験結果へ流用しない。
- 許可対象ディレクトリにある現行資産は、ComputeCluster 19 LTS、EVCharging 6 LTS、
  対応する local YAML 13 ファイルである。LTS 内には同一入力を使う
  Traditional/Stepwise の比較ペアが合計 59 組ある。

## 2. 固定する評価前提

主比較は次の二手法だけとする。

| 論文上の名称 | LTS の更新制御器仕様 | 構成 |
|---|---|---|
| Traditional DUCS | `UpdCont` 系 | Traditional、SBP なし |
| Stepwise DUCS | `Stepwise_UpdCont` 系 | 非 incremental の `stepwise_delayed + safetyBackwardPruning` |

同じケース内では、両手法へ同一の旧制御器、旧環境、新環境、状態対応関係、
旧要求、新要求、transition requirement を与える。手法ごとに入力仕様を変えない。

主指標は合成中に構築される分析空間の次の二値である。

- 最大状態数
- 最大遷移数

合成時間と最大メモリ使用量は重要な補助指標である。最終更新制御器の状態数は
主指標ではない。

論文の主比較へ、`Traditional+SBP`、SBP なしの `stepwise_delayed`、
incremental pruning、OTF-DUC、診断専用構成を混在させない。

## 3. 両例題に共通する設計方針

### 3.1 環境ではなく運用ポリシーを更新する

両例題とも、OLD と NEW の環境モデルの振舞いと alphabet は同じである。
更新するのはハードウェアの振舞いではなく、稼働中に配備される制御ポリシーで
ある。したがって、各 OLD 状態は同じ運用上の意味を持つ NEW 状態へ
`reconfigure` で対応する。

これは、あらかじめ一つの制御器へ二つのモードを組み込み、通常の mode action
で切り替える例題とは異なる。OLD 制御器は OLD 要求だけを用いて合成済みであり、
稼働後に新しい電力制約が与えられて NEW 制御方針を配備するため、動的更新として
扱う。

### 3.2 mapping component

- ComputeCluster は 1 計算ノードを 1 mapping component とする。
- EVCharging は 1 充電器を 1 mapping component とする。
- `oldEnvironment`、`newEnvironment`、`mapRelation` は同じ長さで、同じ位置の
  要素が同じ物理 component を表す。
- 状態対応関係は全運用状態を覆う total identity correspondence である。
- OLD/NEW 環境の意味を変えないため、状態対応関係を評価効果のために欠落させない。

### 3.3 更新制御器仕様名

全 variant を同一 LTS 内へ併置する場合の名前は次のとおりである。

| variant | Traditional | Stepwise |
|---|---|---|
| `no_tr` | `UpdCont` | `Stepwise_UpdCont` |
| `t1_no_controllable_during_gap` | `UpdCont_T1` | `Stepwise_UpdCont_T1` |
| `t2_start_after_stop_and_reconfigure` | `UpdCont_T2` | `Stepwise_UpdCont_T2` |

### 3.4 transition requirement

| variant | 内容 | 想定 scope |
|---|---|---|
| `no_tr` | transition requirement なし | なし |
| T1 | `stopOldSpec` 後かつ `startNewSpec` 前は、各 component の通常の制御可能事象を禁止する | component ごとの local scope |
| T2 | `startNewSpec` の前に `stopOldSpec` と `reconfigure` が発生済みであることを要求する | 更新時事象だけを参照するため全 component |

T1 は一つの全体式ではなく component ごとに定義する。T1 下では NEW 要求を満たす
ためのレベル低下、レート低下、停止、pause などを、必要に応じて
`stopOldSpec` より前に完了させる必要がある。

## 4. ComputeCluster

### 4.1 システム

各計算ノードは次の状態を持つ。

- `IDLE`: ジョブなし
- `READY`: ジョブ要求を受け、開始待ち
- `RUN[level]`: 指定性能レベルでジョブ実行中

通常事象の分類は次のとおりである。

| 分類 | 事象 |
|---|---|
| 制御不可能 | `node[i].request`、`node[i].finish` |
| 制御可能 | `node[i].start[level]`、`node[i].setLevel[level]`、`node[i].stop` |

1 component の状態数は `MaxLevel + 2` である。`N` は component 数を、
`MaxLevel` は各 component の状態数、遷移数、通常事象数、および fluent の事象集合を
増加させる。

### 4.2 現在の通常運転更新シナリオ

推奨する意味付けは、冷却障害ではなく、稼働中のラックへ新しい電力割当
ポリシーを配備する通常運転上の更新である。

- OLD ポリシーは原則として各ノードの性能レベル 1..4 を許可する。
- NEW ポリシーは各ノードをローカルにレベル 1..2 へ制限する。
- NEW ポリシーはさらに、小さなノード集合ごとに合計稼働レベルを 2 以下にする。
- 既に NEW 上限を超えて動作中のノードは、`setLevel[1]`、`setLevel[2]`、または
  `stop` により NEW ポリシーへ移行できる。

二つの topology 系列がある。

| 系列 | cross requirement |
|---|---|
| power-domain | N=4 は `{1,2}` と `{3,4}`、N=5 は `{1,2}` と `{3,4,5}` |
| adjacent | `{1,2}`、`{2,3}`、...、`{N-1,N}` の全隣接ペア |

adjacent 系列では cross requirement 数が `N-1`、各 scope の大きさが 2 である。
power-domain の N=5 には scope 3 の `{3,4,5}` が含まれるため、scope 2 だけの
adjacent 系列とは削減機序が異なる。

### 4.3 要求と scope

代表的な adjacent 系列の要求数は次のとおりである。

| 要求 | 個数 | scope |
|---|---:|---|
| OLD node power cap | N | `{i}` |
| NEW node power cap | N | `{i}` |
| NEW adjacent power budget | N-1 | `{i,i+1}` |
| T1 | N | `{i}` |
| T2 | 1 | 全 component |

NEW adjacent budget は各隣接ペアの合計稼働レベルを 2 以下にする。
したがって `(1,1)` は許可し、`(1,2)`、`(2,1)`、`(2,2)` は禁止する。

`MaxLevel=3` と `MaxLevel=4` の N=5 校正版では、OLD がハードウェア上の全レベルを
許可するため OLD node-cap safety property が存在しない。これらは単に
`MaxLevel=6` の定数だけを縮小したモデルではなく、OLD 要求の意味も異なる。
MaxLevel 間の比較では、この差を明記する必要がある。

### 4.4 Stepwise DUCS の削減機序

1. 各 NEW local cap は、そのノードの mapping component だけで判定できる。
2. SBP はレベル 3 以上へ遷移する状態や、NEW 運用へ安全に移れない状態を、
   後続 component との積を作る前に除去できる可能性がある。
3. adjacent budget は二つ目の owner component が入った段階で判定できる。
4. Traditional DUCS は全 mapping component を合成した後に同じ違反を判定するため、
   後に除去される local/cross 違反状態と残りのノード状態との組合せも先に構築する。

実際の削減量は SBP が除去できる状態数と component の処理順序に依存する。
local/cross 要求が存在するだけで大きな削減が保証されるわけではない。

### 4.5 LTS の分類

配置先:
`maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/LTS/ComputeCluster/`

#### 設計導入・診断用

| LTS | パラメータ | 目的 |
|---|---|---|
| `ComputeCluster_2Nodes_NoChange_Update.lts` | N=2, MaxLevel=3 | identity update の最小確認 |
| `ComputeCluster_2Nodes_LocalThermalCap_Update.lts` | N=2, MaxLevel=3 | local NEW 要求の導入 |
| `ComputeCluster_2Nodes_LocalAndPairwiseThermal_Update.lts` | N=2, MaxLevel=3 | local と scope 2 cross の導入 |
| `ComputeCluster_2Nodes_StrongThermalDerating_Update.lts` | N=2, MaxLevel=6 | 強い local/cross pruning の確認 |
| `ComputeCluster_3Nodes_StrongThermalDerating_Update.lts` | N=3, MaxLevel=6 | 隣接 cross の連鎖確認 |
| `ComputeCluster_2Nodes_PowerBudgetPolicyRollout_Update.lts` | N=2, MaxLevel=6 | 通常運転の電力ポリシー更新の最小確認 |

これらは原則 `no_tr` の Traditional/Stepwise ターゲットだけを持ち、最終的な
主評価用バッチではなく設計確認用である。

#### 既存のスケール系列

| 系列 | LTS | 備考 |
|---|---|---|
| StrongThermalDerating | `ComputeCluster_{4,5,6,7}Nodes_StrongThermalDerating_Update.lts` | N=4..7, MaxLevel=6、隣接 scope 2 |
| PowerBudgetPolicyRollout | `ComputeCluster_4Nodes_PowerBudgetPolicyRollout_Update.lts` | N=4, MaxLevel=6、2 個の scope 2 domain |
| PowerBudgetPolicyRollout | `ComputeCluster_5Nodes_PowerBudgetPolicyRollout_Update.lts` | N=5, MaxLevel=6、scope 2 と scope 3 domain |

StrongThermalDerating は障害後の制限強化として作った先行系列である。現在の
通常運転シナリオを主にする場合は、PowerBudgetPolicyRollout または
AdjacentPowerBudgetPolicyRollout を優先する。

#### 時間・規模調整用の候補

| LTS | パラメータ | topology | YAML |
|---|---|---|---|
| `ComputeCluster_5Nodes_MaxLevel3_PowerBudgetPolicyRollout_Update.lts` | N=5, MaxLevel=3 | domain | なし |
| `ComputeCluster_5Nodes_MaxLevel4_PowerBudgetPolicyRollout_Update.lts` | N=5, MaxLevel=4 | domain | 1 回、2 時間上限 |
| `ComputeCluster_3Nodes_MaxLevel10_AdjacentPowerBudgetPolicyRollout_Update.lts` | N=3, MaxLevel=10 | adjacent | なし |
| `ComputeCluster_4Nodes_MaxLevel8_AdjacentPowerBudgetPolicyRollout_Update.lts` | N=4, MaxLevel=8 | adjacent | 1 回、2 時間上限 |
| `ComputeCluster_4Nodes_MaxLevel10_AdjacentPowerBudgetPolicyRollout_Update.lts` | N=4, MaxLevel=10 | adjacent | 1 回、2 時間上限 |

`ComputeCluster_4Nodes_StrongThermalDerating_T1_Update.lts` と
`ComputeCluster_4Nodes_StrongThermalDerating_T2_Update.lts` は、T1/T2 を別ファイルで
確認していた時期の旧診断ファイルである。combined の N=4 LTS に同じ variant が
存在するため、新しい実験では combined LTS を使う。

### 4.6 YAML

配置先:
`maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/configs/local/`

| YAML 群 | runs | timeout | 対象 |
|---|---:|---:|---|
| `compute_cluster_n4_stepwise_ducs_local.yaml` から `n7` | 3 | 12 h | StrongThermalDerating N=4..7 |
| `compute_cluster_n4_power_budget_policy_rollout_stepwise_ducs_local.yaml` | 3 | 12 h | domain N=4, MaxLevel=6 |
| `compute_cluster_n5_power_budget_policy_rollout_stepwise_ducs_local.yaml` | 3 | 12 h | domain N=5, MaxLevel=6 |
| `compute_cluster_n5_maxlevel4_power_budget_policy_rollout_stepwise_ducs_local.yaml` | 1 | 2 h | domain N=5, MaxLevel=4 |
| `compute_cluster_n4_maxlevel8_adjacent_power_budget_policy_rollout_stepwise_ducs_local.yaml` | 1 | 2 h | adjacent N=4, MaxLevel=8 |
| `compute_cluster_n4_maxlevel10_adjacent_power_budget_policy_rollout_stepwise_ducs_local.yaml` | 1 | 2 h | adjacent N=4, MaxLevel=10 |

各 YAML は両手法の `no_tr`、T1、T2 を列挙する。現行 YAML の method 表記は
Traditional が `Traditional`、Stepwise が `stepwise_delayed+SBP` である。

### 4.7 現在までの確認状況

- 例題作成中には N=2/3 の診断系列と一部 N=4 系列で、両手法が合成できることを
  小規模確認した。ただし、その後に passive transition、GR 入力 alphabet、
  評価 recorder を修正しているため、これらは現在 revision の本実験値ではない。
- 旧 Stepwise 実装では、cross component の順序により結果が変わる診断事象があり、
  passive action が既存 fluent を誤って凍結することと、cross 段階で既存 fluent を
  初期値から再生成することを特定した。現在の修正と回帰は 6 章に記す。
- N=5 以上の一部旧 smoke には timeout/OOM がある。これは合成不能や成功値を
  意味せず、性能値として集計しない。
- N=5 PowerBudgetPolicyRollout、MaxLevel 校正版、adjacent MaxLevel 校正版は、
  現在 revision で揃えた Traditional/Stepwise、no_tr/T1/T2 の本実験をまだ完了していない。
- 過去に同時実行で観測した時間・メモリ値は、最大メモリの帰属が不明で、かつ
  実装修正前であるため、本メモには評価値として採用しない。

上記はモデル調整時の参考に限り、論文用結果として再利用しない。

## 5. EVCharging

### 5.1 システム

各 component は一台の EV 充電器であり、次の状態を持つ。

- `VACANT`: 車両未接続
- `CONNECTED[soc]`: 接続中だが充電していない
- `CHARGING[soc][rate]`: 指定レートで充電中
- `RELEASED`: 充電器側のラッチを解放済みで、物理切断待ち

通常事象の分類は次のとおりである。

| 分類 | 事象 |
|---|---|
| 制御不可能 | `connect`、`socIncrease`、`complete`、`disconnect` |
| 制御可能 | `startCharge[rate]`、`setRate[rate]`、`pause`、`release` |

`release` の後に制御不可能な `disconnect` が発生する二段階プロトコルとした。
これにより、制御不可能な connect/disconnect だけの循環が更新完了を無期限に
先送りする構造を避ける。

### 5.2 動的更新シナリオ

充電サービスの稼働中に、系統運用者から需要応答指令が到着し、新しい充電電力
ポリシーを配備する。

- OLD は各充電器のレート 1..4 を独立に許可する。
- NEW は各充電器をローカルにレート 1..2 へ制限する。
- NEW は各隣接充電器ペアの active-rate 合計を 2 以下にする。
- 各ペアで `(1,1)` は許可し、`(1,2)`、`(2,1)`、`(2,2)` は禁止する。
- 接続中でも pause している充電器は active-rate に寄与しない。
- NEW cap を超えて充電中なら、`setRate`、`pause`、または `release` により移行する。

N=3 では `{1,2}` と `{2,3}`、N=4 ではさらに `{3,4}` がある。中央の充電器は
二つの cross requirement に属する。

### 5.3 要求、scope、スケール

| 要求 | 個数 | scope |
|---|---:|---|
| OLD charger rate cap | N | `{i}` |
| NEW charger rate cap | N | `{i}` |
| NEW adjacent feeder budget | N-1 | `{i,i+1}` |
| T1 | N | `{i}` |
| T2 | 1 | 全 component |

`MaxRate=6` は全モデルで固定している。スケール軸は `N` と `MaxSOC` である。

MaxRate=6 のとき、1 充電器の状態数は次の式になる。

`2 + (MaxSOC + 1) + MaxSOC * 6 = 3 + 7 * MaxSOC`

| MaxSOC | 1 component の状態数 |
|---:|---:|
| 1 | 10 |
| 2 | 17 |
| 3 | 24 |
| 4 | 31 |

`N` を増やすと component 数と adjacent cross requirement 数を増やす。
`MaxSOC` を増やすと各 component の SOC 状態数を増やすが、要求数と scope は変えない。
したがって、二つの拡張軸の効果を分けて説明できる。

### 5.4 Stepwise DUCS の削減機序

1. 各 NEW local rate cap を一台の充電器だけで判定し、rate 3..6 に関係する状態を
   後続充電器との積より前に除去できる可能性がある。
2. adjacent feeder budget は隣接二台が入った時点で判定し、違反する充電組合せを
   残りの充電器と組み合わせる前に除去できる可能性がある。
3. Traditional DUCS は全充電器の mapping environment を合成してから同じ要求を
   判定するため、後に除去する状態と未関係な充電器状態との組合せも構築する。

`MaxSOC` の増加は、早期に除去される rate 状態へ付随する SOC 状態数を増やす。
ただし実際の削減率は、到達可能性、SBP、および scope 順序を実測して確認する。

### 5.5 LTS の分類

配置先:
`maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/LTS/EVCharging/`

| LTS | N | MaxSOC | 用途 |
|---|---:|---:|---|
| `EVCharging_2Chargers_DemandResponsePolicyRollout_Update.lts` | 2 | 2 | 最小 validation/source |
| `EVCharging_3Chargers_AdjacentDemandResponsePolicyRollout_Update.lts` | 3 | 2 | N/topology 拡張 smoke |
| `EVCharging_4Chargers_AdjacentDemandResponsePolicyRollout_Update.lts` | 4 | 2 | N/topology 拡張 smoke |
| `EVCharging_3Chargers_MaxSOC1_AdjacentDemandResponsePolicyRollout_Update.lts` | 3 | 1 | T1 非決定性の最小回帰診断 |
| `EVCharging_3Chargers_MaxSOC3_AdjacentDemandResponsePolicyRollout_Update.lts` | 3 | 3 | SOC state-space 評価候補 |
| `EVCharging_3Chargers_MaxSOC4_AdjacentDemandResponsePolicyRollout_Update.lts` | 3 | 4 | SOC state-space 評価候補 |

全 LTS が `no_tr`、T1、T2 の Traditional/Stepwise、計 6 ターゲットを持つ。

### 5.6 YAML

| YAML | 設定 | 用途 |
|---|---|---|
| `ev_charging_n3_n4_adjacent_demand_response_smoke_5min.yaml` | 5 分、10 GB | N=3/N=4, MaxSOC=2 の precheck と全 variant smoke |
| `ev_charging_n3_maxsoc1_t1_smoke_5min.yaml` | 5 分、10 GB | N=3, MaxSOC=1 の T1 smoke |
| `ev_charging_n3_maxsoc1_no_tr_diagnostic_5min.yaml` | 5 分、10 GB | N=3, MaxSOC=1 の no_tr 診断 |
| `ev_charging_n3_maxsoc3_maxsoc4_adjacent_demand_response_stepwise_ducs_local.yaml` | 1 回、2 時間、220 GB | MaxSOC=3/4 の評価候補 |

5 分上限の timeout は合成不能や unrealizable を意味せず、その時間内に完了しなかった
ことだけを意味する。

### 5.7 現在までの確認状況

保存済み smoke artifact、現在 revision の correctness regression、今後の本実験を
分けて扱う。現時点の correctness 確認は次のとおりである。

- N=2, MaxSOC=2 の同一 LTS で、Traditional/Stepwise の no_tr、T1、T2、計 6 target が
  すべて deterministic/winning として合成できた。
- 上記 6 controller を直接 BFS し、到達可能範囲について deadlock、更新時事象の重複、
  OLD/NEW の phase 別 safety 違反、T1 gap 中の通常制御可能事象、T2 の不正な
  `startNewSpec` がいずれも 0 であること、および更新完了へ到達可能であることを確認した。
- N=3, MaxSOC=2 の Stepwise no_tr は、2 段階の cross 判定と SBP を通って
  deterministic/winning となった。段階ログは次の correctness 監査値であり、
  Traditional との本実験値には使用しない。

| N=3 Stepwise no_tr の段階 | pruning 前 | pruning 後 |
|---|---:|---:|
| cross step 1, scope `{1,2}` | 472 states / 13,500 transitions | 460 / 13,102 |
| cross step 2, scope `{1,2,3}` | 3,816 / 65,340 | 3,764 / 64,434 |

最終 GR(1) 入力は 4,276 states / 68,748 transitions、出力は 3,869 states だった。
この一回の値は削減機序の小規模確認用であり、論文の性能値として集計しない。

- N=3, MaxSOC=1, T1 では、passive transition 修正後に spurious な multi-target
  hotSwapIn が 0 となり、Stepwise が deterministic/winning で合成できた。
- MaxSOC=3/4 の評価 YAML は存在するが、現在 revision で揃えた全 target の本実験は
  未完了である。
- pruning 後に遷移がなくなった宣言済み制御可能事象が GR goal から欠落する問題は、
  6.5 の alphabet 修正で対処し、両手法の final alphabet 回帰で確認した。

## 6. 現在の Stepwise DUCS 実装と修正

### 6.1 論文対象の主経路

論文対象は、非 incremental な `stepwise_delayed + safetyBackwardPruning` である。
現在の通常経路は次の順で処理する。

1. 通常事象の owner と requirement scope を計算する。
2. mapping component ごとに local metaEnv/safetyEnv を作り、local safety 判定と保留SBPを行う。
3. cross component 内で必要な fragment だけを段階的に合成し、判定可能になった
   cross requirement と保留SBPを適用する。
4. 全 mapping component の final product を作り、owner が揃った通常SBPを行う。
5. old controller を tracked fluent と phase fluentでmeta化し、delayed `hotSwapIn` で接続する。
6. global DontDoTwice、接続後の通常SBP、最終 GR(1) の順に実行する。

部分段階ごとに通常の GR(1) 合成を繰り返すのではなく、部分段階では安全性判定と
SBPを行い、GR(1) は最後に一度解く。`incrementalPruning` は実装に残る派生構成であり、
論文の主対象へ含めない。

### 6.2 scope と action-fluent

- owner は各 mapping component の alphabet から求め、共有事象は全 owner を持つ。
- `.old` は通常事象名へ正規化し、予約内部事象と更新時事象は通常 owner から除外する。
- formula が参照する fluent の initiating/terminating action の owner の和集合を
  requirement scope とする。
- scope size 1 は local、size 2 以上は cross、更新時事象しか参照しない式は
  全 component scope になる。
- 参照した通常事象に owner がなければ `GOAL_ACTION_NOT_FOUND` で失敗する。
- action proposition fluent の wildcard terminating side は scope に寄与せず、
  initiating action だけで scope を決める。metaEnv 構築時には canonical global
  alphabet を使い、initiating action 以外の通常事象を terminating side へ補完する。

最後の action-fluent 補完 helper は Traditional と Stepwise の共通経路で使われる。

### 6.3 passive self-loop / passive transition 修正

未合成 component の事象でも、すでに追跡中の fluent 履歴を更新できなければならない。
このため、raw mapping component に欠けている global action の self-loop を、最初の
fluent product より前に一度だけ追加する。fluent valuation を付けた後は、その事象が
valuation を変えるなら積状態上では non-self の passive transition になる。

旧実装は valued partial product へ raw self-loop を後付けし、既存 action-fluent を
誤って凍結していた。さらに cross 段階で既存 fluent を初期値から再生成したため、
同じ mapping 状態に複数の誤った fluent 履歴が生まれ、identity relation でも
delayed hotSwapIn が偽の一対多になり得た。

現在の規則は次のとおりである。

- valued partial product へ raw self-loop を後付けしない。
- 既存 tracked fluent は再生成せず、その valuation を正とする。
- cross 段階で初めて必要になった fluent だけを `extendFluentProduct` で追加する。
- 同名 fluent の定義が途中で変わった場合は失敗させる。
- owner がまだ scope にない passive transition を partial product に保持する。
- real owner が参加した後は、その全 owner の enable/disable で事象を同期・制限する。
- 早期SBPが transitionを削除しても alphabetだけを保持し、後続 owner の参加時に
  削除済みtransitionを復活させない。

### 6.4 保留 Safety Backward Pruning

local/cross の部分 fragment では、通常SBPではなく owner-aware な保留SBPを使う。

- formula の直接違反状態だけを losing seed とし、部分 fragment の一時的な
  dead-end は losing seed にしない。
- Errorへ進む制御可能事象は、同一 source/action の transition group 全体を除去する。
- Errorへ進む制御不可能事象の全 owner が現在 scope に含まれる場合だけ、
  losing を後向きに伝播する。
- 未合成 owner が残る場合は伝播を保留し、Error 境界を後続 product へ残す。

全 mapping component 合成後と old controller 接続後は owner が揃っているため、
dead-end も扱う通常SBPを使う。

### 6.5 delayed hotSwapIn と alphabet 修正

delayed hotSwapIn の候補は、全 mapping component が `OLD_SIDE`、
`stopOldSpec`/`reconfigure`/`startNewSpec` が未発生、old controller と全比較 fluent の
valuation が一致し、初期 pair から通常事象の同期で到達可能な mapping state に限る。
`beginFluent` は比較から除外する。relation は一般の関係として保持するため、正当な
一対多接続は維持する一方、fluent 履歴不具合による偽の一対多だけを解消した。

安全性判定やSBPで、宣言済み制御可能事象を持つ transition がすべて消える場合がある。
その事象を GR goal で制御不可能扱いに変えないため、Traditional/Stepwise 共通の
GR synthesizer は、宣言済み controllable action 集合を safetyEnv の alphabet へ
追加する。追加するのは label だけであり、transition は追加しない。

最終 `.old` 後処理も、alphabet 上だけに残った `.old` label を元名へ正規化し、
宣言済み controllable label を final controller alphabet へ保持する。ここでも
削除済み transition は復活させない。この修正は Traditional と Stepwise の両方へ効く。

### 6.6 `addedStageScope` の意味

段階ログの `addedStageScope` は、「fragment に含まれるが、まだ cross pruning に
参加していない stage」の集合とする。

- 初期 singleton fragment: pending = `{i}`
- fragment merge: pending 集合の和
- cross pruning 後: pending = 空集合
- fragment が一つだけなら pending をそのまま保持

N=3 の回帰では、step 1 が product `{1,2}` / added `{1,2}`、step 2 が
product `{1,2,3}` / added `{3}` になる。これは診断値の意味を正す修正であり、
合成される遷移系の意味は変更しない。

### 6.7 評価 recorder の peak 修正

従来は、同じ label の状態空間観測が stable metric key を上書きし、先に現れた
より大きい観測が peak 集計から消える可能性があった。現在は次の二層で保持する。

- 従来の stable key は後処理互換性のため最新値を保持する。
- append-only な raw observation list は、同名 label を含む全観測を保持する。

Stepwise の各 raw 観測は、監査用に次の一意 key でも CSV へ保存する。

```text
<base_key>_observation_<run内sequence>_{states,transitions,count_time}
```

状態数 peak と同時点の遷移数、遷移数 peak と同時点の状態数は、必ず同じ一観測から
取得する。この修正は計測だけに作用し、生成 controller は変更しない。

### 6.8 診断、実tau、検証状況

次の診断プロパティは既定で false であり、通常実行では追加走査を行わない。

- `stepwise.delayed.debugActionDiagnostics=false`
- `stepwise.delayed.debugHotSwapLineage=false`

評価 LTS には実tau transitionを記述しない。内部予約 alphabet 上の `tau` は、
component owner、real action、action-fluent の通常 terminating action から除外する。
実tau transitionを持つ入力全般の意味や対応は、今回の評価範囲外である。

修正後は、分離した JVM/process で単体 20 件と統合 5 件、計 25 件が成功している。
主な確認内容は次のとおりである。

- local/shared/action-fluent の owner と scope、`.old` 正規化
- passive transition、delta fluent、既存 valuation の保持
- staged product と monolithic product の意味スナップショット一致
- shared real owner の同期と、SBPで削除した passive transition の非復活
- identity relation で偽の一対多がないことと、正当な一対多 relation の保持
- no_tr/T1/T2 の Traditional/Stepwise 全6targetと final controllerの意味監査
- pruning 後に transition がない制御可能事象の alphabet 保持
- 同名 label を含む peak と対になる state/transition 値

複数の統合テスト class を同一 JVM で連続実行すると、MTSA の static state または
test isolation に起因するとみられる終了時停止が残る。各 class を別 process で
実行した場合は成功する。本実験前に実装 revision を固定し、全ケースを同じ revision
で再実行する。

## 7. 実験設定と結果管理

### 7.1 現在のバッチ設定

- 初期の ComputeCluster 主評価 YAML 6本: `runs: 3`、`timeoutHours: 12`
- 規模校正後の ComputeCluster YAML 3本と EVCharging MaxSOC=3/4 YAML:
  `runs: 1`、`timeoutHours: 2`
- EVCharging smoke/diagnostic YAML 3本: 5分上限、10 GB heap

これらを同じ反復実験セットとして集計しない。最終的に何回実行するかを決めた後、
採用ケース間で runs、timeout、heap、実行機、revision を揃える。
期限優先の最新設定を使う場合、旧 `runs: 3` / 12時間 YAML をそのまま混在させない。

評価用 PC で一ケースの Traditional を約 1 時間以内に収めたいという運用上の要望が
ある。これは no_tr/T1/T2 と反復実行を提出期限内に完了するためであり、モデルの
意味を変える根拠にはしない。規模調整は `N`、`MaxLevel`、`MaxSOC` で行う。

### 7.2 通知設定

一部 YAML には Slack webhook が直接設定されている。本メモには値を転載しない。
webhook URL は認証情報として扱い、共有・公開・コミット時の取扱いを確認する。

### 7.3 smoke と本実験を分離する

- smoke/diagnostic の output directory と本実験の output directory を分ける。
- debug property は本実験で無効にする。
- timeout、OOM、losing、非決定的 GR(1) への fallback を成功値として扱わない。
- 同時実行は最大メモリ値を汚すため、正式なメモリ測定では一ケースずつ実行する。
- 自動生成された CSV の method、variant、revision、success/failure を確認してから
  集計する。
- 主指標には `peak_state_space_states` と `peak_state_space_transitions` を使い、
  必要に応じて `_observation_` 行から同名 label を含む全 raw 観測を再検算する。
- 既知の問題がある更新制御器 9 要件チェッカーの `OVERALL=PASS` だけを正しさの
  根拠にしない。

## 8. 本実験前のチェックリスト

各採用 LTS と各 no_tr/T1/T2 について、少なくとも次を確認する。

- [ ] 実装 revision と LTS/YAML の hash を固定した。
- [ ] `OldCon` が合成可能である。
- [ ] 必要に応じて `NewCon` も単独合成可能である。
- [ ] Traditional が合成可能である。
- [ ] Stepwise が合成可能である。
- [ ] `oldEnvironment`、`newEnvironment`、`mapRelation` のリスト長が一致する。
- [ ] mapping component と状態対応関係が意図した node/charger を表す。
- [ ] 実装が算出した local/cross scope が LTS コメントと一致する。
- [ ] T1 が component ごとの local requirement として分類される。
- [ ] T2 が update-event-only の全 component scope として分類される。
- [ ] 評価 LTS に実tau transition がない。
- [ ] 各段階の safety 判定と SBP で実際に除去した状態・遷移数を記録する。
- [ ] `addedStageScope` が新規参加 stage だけを表している。
- [ ] passive transition の valuation mismatch が診断時に 0 である。
- [ ] hotSwapIn の多重度が状態対応関係と fluent valuation から説明できる。
- [ ] GR(1) 入力が意図せず非決定的になっていない。
- [ ] pruning で全 transition が消えた制御可能事象も、GR入力と最終controllerの
      alphabetでは制御可能labelとして保持され、transition自体は復活していない。
- [ ] Stepwise の raw observation の最大値と `peak_state_space_*` summary が一致する。
- [ ] 両手法が同一入力仕様を使用している。
- [ ] Traditional は SBP なし、Stepwise は非incrementalな
      `stepwise_delayed + safetyBackwardPruning` である。
- [ ] timeout/OOM/losing を成功 CSV として集計していない。
- [ ] 修正前ログ、smoke CSV、本実験 CSV を分離した。

## 9. 現在の未決事項

1. ComputeCluster の最終採用規模を、N=4 MaxLevel=8/10 と N=5 MaxLevel=4 の
   修正後 smoke から選ぶ。
2. ComputeCluster で adjacent 系列を主にするか、scope 3 を含む power-domain
   N=5 も含めるか決める。
3. EVCharging MaxSOC=3/4 の両手法、no_tr/T1/T2 を修正後実装で実行し、
   2 時間以内に収まるケースを選ぶ。
4. 既存の ComputeCluster/EVCharging の全性能値を、passive transition、GR入力
   alphabet、recorder peak 修正後の同一 revision で再取得する。
5. runs=1 の期限優先実験を論文の正式値にするか、可能なケースだけ追加反復するか
   決める。
6. 各ケースで「どの scope の要求が、どの段階で、何状態・何遷移を除去したか」を
   ログから表にする。
7. 同一 JVM 内で複数の統合テストを連続実行した際の終了時停止を、評価runnerへ
   影響しない test-isolation 問題として切り分けたままでよいか確認する。

## 10. 結果から主張してはいけないこと

個別実験の成功や分析空間削減から、次を一般的性質として主張しない。

- Traditional と Stepwise が同一の更新制御器を返す。
- 両手法が同じ実行系列を受理する。
- 両手法の最大許容性が等しい。
- Stepwise が成功すれば Traditional も成功する。
- local/cross requirement があれば必ず大幅に削減できる。

主張できるのは、同一入力ケースについて、実測した分析空間の最大状態数・最大遷移数、
時間、メモリ、および各段階の pruning との因果関係である。

## 11. 主要パス

- ComputeCluster LTS:
  `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/LTS/ComputeCluster/`
- EVCharging LTS:
  `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/LTS/EVCharging/`
- local YAML:
  `maven-root/mtsa/MODEL/StepwiseDUCS/Experiment/configs/local/`
- Stepwise delayed 実装:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/delayed/StepwiseDelayedUpdatingControllerSynthesizer.java`
- scope/owner と SBP:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseGoalClassifier.java`
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/StepwiseActionOwnership.java`
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/SafetyBackwardPruner.java`
- GR と最終 alphabet:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerGRSynthesizer.java`
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllersUtils.java`
- 評価 recorder:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
- 主な関連テスト:
  `maven-root/mtsa/src/test/java/ltsa/updatingControllers/stepwise/delayed/StepwiseDelayedUpdatingControllerSynthesizerTest.java`
  `maven-root/mtsa/src/test/java/ltsa/updatingControllers/cli/StepwiseDelayedHotSwapInRegressionTest.java`
  `maven-root/mtsa/src/test/java/ltsa/updatingControllers/cli/StepwiseDelayedHotSwapInOneToManyRegressionTest.java`
  `maven-root/mtsa/src/test/java/ltsa/updatingControllers/cli/StepwiseTransitionRequirementsFullPipelineIntegrationTest.java`
  `maven-root/mtsa/src/test/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorderTest.java`
- アルゴリズムメモ:
  `docs/STEPWISE_DELAYED_DUCS_ALGORITHM_MEMO_JA.md`
- 実装メモ:
  `docs/STEPWISE_DELAYED_DUCS_IMPLEMENTATION_MEMO_JA.md`
- CSV recorder メモ:
  `docs/EVALUATION_CSV_RECORDER_MEMO_JA.md`
