# Evaluation CSV / Recorder Memo

更新日: 2026-07-22

このメモは、`UpdatingControllerEvaluationRecorder` が何を記録し、evaluation CSV に何が入るかを確認するための作業メモである。
実装の正は Java 側だが、実験や論文用データ確認では、まずこのメモを見る。

主対象は以下である。

- Traditional
- Traditional+SBP
- `stepwise_delayed`
- `stepwise_delayed+SBP`

## 網羅範囲

このメモは、Traditional / `stepwise_delayed` 系の実験で evaluation CSV に入り得るデータを対象にする。
固定 `metric_key` を持つものは表で列挙し、scope 名、component 名、phase 名、step 番号などで行数が変わるものは key pattern と suffix 規則で説明する。

つまり、このメモでの「すべて」は次の意味である。

- Traditional / `stepwise_delayed` 実験CSVに出る全カテゴリを扱う。
- 動的に増える行は、個別行を全列挙せず、生成規則と代表例で扱う。
- 後処理や論文表で使うべき主要固定キーは明示する。
- この範囲外の古い mode / 別手法専用 metric は、ここでは対象外にする。

CSVを見て意味が分からない行があった場合は、まず `section` と `metric_key` の prefix をこのメモの pattern に照合する。
どの pattern にも当てはまらない場合は、このメモが未更新なので、recorder 実装を確認して追記する。

## 実装上の入口

主な実装ファイル:

- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
  - 評価値を1 run単位で集約する recorder。
  - CSV の行生成、metric key 生成、comparison summary、CountTime 集計を担当する。
- `maven-root/mtsa/src/main/java/ltsa/lts/UpdatingControllersDefinition.java`
  - 入力準備、mapping component 生成、Traditional の mapping environment 並列合成時間などを記録する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java`
  - Traditional の `E_u` / metaEnv / safetyEnv / GR(1) 関連時間を記録する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/stepwise/delayed/StepwiseDelayedUpdatingControllerSynthesizer.java`
  - `stepwise_delayed` の分類、local/cross/final safetyEnv、hotSwapIn connection、GR(1) 関連時間を記録する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/SafetyBackwardPruner.java`
  - SBP 個別時間、SBP 合計時間、SBP 前後の状態空間を記録する。

## CSV の出力先

`UpdatingControllerEvaluationRecorder` は detailed evaluation CSV を long format で出す。

- 明示指定: `-Dmtsa.evaluation.csvFile=<file>`
- 旧指定: `-Dupdating.controller.evaluation.csvFile=<file>`
- directory 指定: `-Dmtsa.evaluation.csvDir=<dir>`
- 未指定時の default directory: `Experiment/evaluation_csv`

YAML の `BatchExperimentRunner` 実行では、child process に `-Dmtsa.evaluation.csvFile=<case output>/...csv` を渡すため、case ごとに evaluation CSV が作られる。
通常の MTSA 実行や `SingleCompositionRunner` で `csvFile` 未指定の場合は、JVM の current working directory から見た `Experiment/evaluation_csv` 配下に自動生成される。

## CSV 形式

CSV header:

```text
mode,result,failure_reason,section,metric_key,metric_label,value,unit,formula,metric_schema_version,metric_description_id,section_readable_ja,metric_readable_ja,metric_category,artifact,phase,action,event,stat
```

重要なのは `metric_key`, `value`, `unit` である。
`section` と `metric_label` は人間が読むための説明で、後処理では基本的に `metric_key` を使う。

単位の基本:

- 時間: `ms`
- 状態数: `states`
- 遷移数: `transitions`
- メモリ: `B`
- boolean: `0` または `1`
- text: path、stage 名、mode など

## metric_key の生成規則

固定キーは `UpdatingControllerEvaluationRecorder.knownMetricKey(...)` に定義されている。
固定キーがない場合は `auto_<hash>` になるため、論文実験で後処理に使う metric は固定キーを追加しておく。

状態空間を `recordStateSpace(section, label, states, transitions, countTime)` で記録した場合、base key から次の3行が出る。

```text
<base_key>_states
<base_key>_transitions
<base_key>_count_time
```

section `Stepwise Delayed DUC 最大状態数と遷移数` では、local stage などが同じ label を複数回記録する場合がある。
既存の `<base_key>_{states,transitions,count_time}` は後処理互換性のため従来どおり最後の値を保持し、監査用には各観測を次の一意keyでも保存する。

```text
<base_key>_observation_<run内sequence>_states
<base_key>_observation_<run内sequence>_transitions
<base_key>_observation_<run内sequence>_count_time
```

`run内sequence` は1回の合成内で観測を区別するための値であり、後処理で固定keyとして参照してはならない。
全段階を再検算する場合は `_observation_` 行を使い、論文表では後述の `peak_state_space_*` 固定keyを使う。
この監査用行は既存の状態空間計測の再掲であり、CountTime overheadを二重加算しない。

`recordFinalGrInputStateSpace(methodKey, ...)` は次の形式で出す。

```text
<methodKey>_final_gr_input_states
<methodKey>_final_gr_input_transitions
<methodKey>_final_gr_input_source_stage
```

現在使う主な `methodKey`:

- `traditional`
- `stepwise_delayed`

## 評価プロファイル

`-Dupdating.controller.evaluation.profile=<profile>` で評価用診断の重さを切り替える。

現在の default は `paper` である。`paper` では論文用の入力規模、段階別時間、状態数・遷移数、SBP、GR(1)、出力 controller、CountTime は記録するが、追加グラフ解析である update phase 詳細、距離系、normal action 詳細分布、update order pattern、progress-free SCC / Tarjan は実行しない。

詳細診断を明示的に取りたい場合だけ `full` または `diagnostic` を指定する。

実行時に使われた値は `run_evaluation_profile` として CSV に入る。

## 実験でまず見るキー

### Run status

| metric_key | 意味 |
|---|---|
| `mode` | 実行 mode |
| `result` | `SUCCESS` / failure status |
| `failure_reason` | failure 理由 |
| `failure_stage` | failure 時点で最後に active だった failure timer。成功時は出ない |
| `failure_active_timer_count` | failure 時点で active だった failure timer 数。成功時は出ない |
| `failure_active_timer_<n>` | failure 時点で active だった timer の `section / label` |
| `evaluation_csv_file` | この CSV 自体の出力先 |

### Run environment / batch metadata

| metric_key | 意味 |
|---|---|
| `run_allowed_threads` | MTSA が使う thread 数 |
| `run_available_processors` | JVM から見える processor 数 |
| `run_jvm_max_heap` | JVM 最大 heap |
| `run_java_version` | Java version |
| `run_evaluation_profile` | 評価プロファイル。default は `paper` |
| `run_java_vm_name` | Java VM 名 |
| `run_os_name` | OS 名 |
| `run_os_arch` | OS architecture |
| `batch_case_id` | YAML batch の case id |
| `batch_example` | YAML batch の example 名 |
| `batch_method` | YAML batch の method |
| `batch_variant` | YAML batch の variant |
| `batch_target` | YAML batch の target |
| `batch_lts_file` | 実行対象 `.lts` file |
| `batch_config_file` | YAML config file |
| `batch_run_index` | run index |
| `batch_run_count` | run count |
| `batch_run_label` | run label |

### 共通時間

| metric_key | 意味 |
|---|---|
| `total_time` | 合成ボタンまたは runner から見た実測総時間 |
| `parse_time` | 構文解析時間 |
| `synthesis_problem_preparation_time` | 合成問題準備時間 |
| `update_controller_generation_time` | update controller 生成時間 |
| `controller_synthesis_related_time` | 合成問題準備時間 + update controller 生成時間 |
| `controller_draw_time` | GUI 描画時間 |

### 共通入力・規模

| metric_key | 意味 |
|---|---|
| `old_controller_synthesis_time` | old controller 合成時間 |
| `goal_and_controllable_set_time` | goal 定義と controllable action 集合生成時間 |
| `mapping_component_generation_time` | 個別 mapping component 生成時間 |
| `input_old_env_components` | old environment component 数 |
| `input_new_env_components` | new environment component 数 |
| `input_map_relations` | map relation 数 |
| `input_mapping_components` | mapping component 数 |
| `input_old_safety` | old safety 数 |
| `input_new_safety` | new safety 数 |
| `input_transition_requirements` | transition requirement 数 |
| `input_controllable_actions` | controllable action 数 |
| `input_uncontrollable_actions` | uncontrollable action 数 |
| `input_total_actions` | controllable + uncontrollable action 数 |

### 評価出力時間

評価結果を Output や CSV に出すための recorder 側 overhead。
アルゴリズム本体時間ではない。

| metric_key | 意味 |
|---|---|
| `evaluation_output_header_time` | 評価ヘッダ出力時間 |
| `evaluation_output_detailed_report_time` | 詳細評価レポート出力時間 |
| `evaluation_output_summary_time` | EVALUATION SUMMARY 出力時間 |
| `evaluation_output_data_csv_time` | evaluation CSV 出力時間 |
| `evaluation_output_overhead_before_csv` | CSV出力前までの評価出力 overhead |
| `evaluation_output_overhead_total_including_csv` | CSV出力も含む評価出力 overhead |

### メモリ

| metric_key | 意味 |
|---|---|
| `controller_synthesis_base_memory` | コントローラ合成のベースラインメモリ |
| `controller_synthesis_peak_memory` | コントローラ合成全体のピークメモリ |
| `controller_synthesis_memory_increase` | ピークメモリ - ベースラインメモリ |

`recordMemoryCheckpoint(section, label)` は次の suffix を出す。

```text
<base_key>_current_heap
<base_key>_peak_heap
<base_key>_delta_from_start
<base_key>_delta_from_previous
```

default section は `メモリ使用量チェックポイント` である。

## GR(1) 入力と GR(1) solver

論文実験で重要な分解は次である。

| metric_key | 意味 |
|---|---|
| `traditional_final_gr_input_construction_time` | Traditional で GR(1) に渡す final safetyEnv / CompactState を作るまでの時間 |
| `stepwise_delayed_final_gr_input_construction_time` | `stepwise_delayed` で GR(1) に渡す final safetyEnv / CompactState を作るまでの時間 |
| `traditional_eu_mts_conversion_time` | Traditional の `UpdatingEnvironment` から `E_u` MTS への変換時間 |
| `traditional_fluent_extraction_time` | Traditional の safety goal から Fluent を抽出する時間 |
| `traditional_meta_environment_construction_time` | Traditional の `E_u` から metaEnv を作る時間 |
| `traditional_safety_environment_pruning_time` | Traditional の metaEnv から safetyEnv を作る時間 |
| `traditional_safety_env_compact_state_conversion_time` | Traditional の safetyEnv から CompactState への変換時間 |
| `stepwise_delayed_old_controller_meta_construction_time` | `stepwise_delayed` の old controller meta 構築時間 |
| `stepwise_delayed_connection_environment_construction_time` | `stepwise_delayed` の hotSwapIn connection 後 environment 構築時間 |
| `stepwise_delayed_global_dont_do_twice_time` | `stepwise_delayed` の global DontDoTwice 構築時間 |
| `stepwise_delayed_safety_env_compact_state_conversion_time` | `stepwise_delayed` の safetyEnv から CompactState への変換時間 |
| `traditional_gr1_solving_time` | Traditional の GR(1) solver 本体時間 |
| `stepwise_delayed_gr1_solving_time` | `stepwise_delayed` の GR(1) solver 本体時間 |
| `traditional_final_gr_input_states` | Traditional の GR(1) 入力状態数 |
| `traditional_final_gr_input_transitions` | Traditional の GR(1) 入力遷移数 |
| `traditional_final_gr_input_source_stage` | Traditional の GR(1) 入力がどの段階の生成物か |
| `stepwise_delayed_final_gr_input_states` | `stepwise_delayed` の GR(1) 入力状態数 |
| `stepwise_delayed_final_gr_input_transitions` | `stepwise_delayed` の GR(1) 入力遷移数 |
| `stepwise_delayed_final_gr_input_source_stage` | `stepwise_delayed` の GR(1) 入力がどの段階の生成物か |
| `traditional_final_gr_input_construction_time_scope_count_overhead_time` | Traditional の GR(1) 入力構築区間に含まれる評価用 CountTime |
| `traditional_final_gr_input_construction_time_time_without_scope_count_overhead` | Traditional の GR(1) 入力構築時間から上記 CountTime を引いた参考値 |
| `stepwise_delayed_final_gr_input_construction_time_scope_count_overhead_time` | `stepwise_delayed` の GR(1) 入力構築区間に含まれる評価用 CountTime |
| `stepwise_delayed_final_gr_input_construction_time_time_without_scope_count_overhead` | `stepwise_delayed` の GR(1) 入力構築時間から上記 CountTime を引いた参考値 |

測定区間:

- Traditional: mapping environment component の並列合成直前から、最終 safety environment を `CompactState` に変換して GR(1) に渡す直前まで。
- `stepwise_delayed`: `G_old` / `G_new` / `G_T` を scope ごとに分類し始める直前から、final safety environment を `CompactState` に変換して GR(1) に渡す直前まで。

注意:

- `*_final_gr_input_construction_time` は wall-clock time であり、途中の評価用 CountTime を含み得る。
- CountTime を差し引く場合は、対応する `*_time_without_scope_count_overhead` を使う。
- SBP 有効時は、GR(1) 入力構築時間に SBP も含まれる。
- GR(1) solver 本体は `*_gr1_solving_time` に分離される。

### 主要中間状態空間

section `主要中間状態空間` には、論文分析でよく使う中間生成物の状態数・遷移数を固定キーで再掲する。
これは既存の `recordStateSpace` のカウント結果を再利用する alias であり、状態数・遷移数を再カウントしない。
そのため、`*_count_time` は元の状態空間行で既に CountTime overhead に加算済みであり、この alias では二重加算しない。

| metric_key prefix | 意味 |
|---|---|
| `traditional_mapping_environment` | Traditional の巨大 mapping environment |
| `traditional_intermediate_eu` | Traditional の `E_u = Old Controller || Mapping Environment` |
| `traditional_intermediate_meta_environment` | Traditional の metaEnv |
| `traditional_intermediate_final_safety_environment` | Traditional の final safetyEnv |
| `stepwise_delayed_intermediate_product_before_delayed_connection` | `stepwise_delayed` の delayed connection 前 product |
| `stepwise_delayed_intermediate_old_controller_fluent_meta` | `stepwise_delayed` の old controller fluent meta |
| `stepwise_delayed_intermediate_after_delayed_hotswapin_connection` | `stepwise_delayed` の hotSwapIn connection 後 environment |
| `stepwise_delayed_intermediate_after_global_dont_do_twice` | `stepwise_delayed` の global DontDoTwice 後 environment |
| `stepwise_delayed_intermediate_after_final_sbp` | `stepwise_delayed_SBP` の final SBP 後 environment |

各 prefix から次の suffix が出る。

```text
<prefix>_states
<prefix>_transitions
<prefix>_count_time
```

## GR(1) 内訳

Traditional と `stepwise_delayed` で prefix が変わる。

| Traditional prefix | stepwise_delayed prefix | 意味 |
|---|---|---|
| `traditional_gr1_synthesize_total_time` | `stepwise_delayed_gr1_synthesize_total_time` | GR synthesize 全体時間 |
| `traditional_gr1_subset_construction_time` | `stepwise_delayed_gr1_subset_construction_time` | subset construction 時間 |
| `traditional_gr1_goal_build_time` | `stepwise_delayed_gr1_goal_build_time` | GR goal 構築時間 |
| `traditional_gr1_game_build_time` | `stepwise_delayed_gr1_game_build_time` | GR game 構築時間 |
| `traditional_gr1_winning_region_time` | `stepwise_delayed_gr1_winning_region_time` | winning region 計算時間 |
| `traditional_gr1_strategy_build_time` | `stepwise_delayed_gr1_strategy_build_time` | strategy 構築時間 |
| `traditional_gr1_strategy_to_controller_mts_time` | `stepwise_delayed_gr1_strategy_to_controller_mts_time` | strategy から controller MTS 構築 |
| `traditional_gr1_compact_state_conversion_time` | `stepwise_delayed_gr1_compact_state_conversion_time` | controller を CompactState に変換 |

GR game の状態空間系は、`recordStateSpace` / `recordGrGameStateSpace` により `_states`, `_transitions`, `_count_time` や successor 系の suffix を持つ。

## 出力 controller

| metric_key | 意味 |
|---|---|
| `output_update_controller_states` | 出力 update controller 状態数 |
| `output_update_controller_transitions` | 出力 update controller 遷移数 |
| `output_update_controller_count_time` | 出力状態数・遷移数 CountTime |
| `minimized_output_update_controller_states` | minimized 後の出力状態数 |
| `minimized_output_update_controller_transitions` | minimized 後の出力遷移数 |
| `minimized_output_update_controller_count_time` | minimized 後の状態数・遷移数 CountTime |
| `minimized_output_update_controller_minimize_time` | minimize 処理時間 |

Batch 実験では minimized counts は別 CSV にも出る。
評価の主時間には minimize は含めない。

## Peak state space summary

EVALUATION SUMMARY 用に、中間状態空間の最大値も CSV に出る。

| metric_key | 意味 |
|---|---|
| `peak_state_space_states` | 記録された中で最大の状態数 |
| `peak_state_space_states_stage` | 最大状態数を記録した stage |
| `peak_state_space_states_stage_transitions` | 最大状態数 stage の遷移数 |
| `peak_state_space_transitions` | 記録された中で最大の遷移数 |
| `peak_state_space_transitions_stage` | 最大遷移数を記録した stage |
| `peak_state_space_transitions_stage_states` | 最大遷移数 stage の状態数 |

Traditional では `traditional_eu` / `traditional_meta` / `traditional_final` などから peak を作る。
`stepwise_delayed` では local / cross / final product / delayed connection / final safetyEnv などの記録から peak を作る。
Stepwiseのpeakは同名labelを含む全観測を対象とし、最大値と対になる状態数・遷移数は必ず同一観測から取得する。

## Traditional 固有

| metric_key | 意味 |
|---|---|
| `traditional_gr_goal_generation_time` | Traditional GR goal 生成時間 |
| `traditional_safety_goal_generation_time` | Traditional safety goal 生成時間 |
| `traditional_mapping_parallel_composition_time` | Traditional の mapping environment component 並列合成時間 |
| `traditional_eu_construction_time` | old controller と mapping environment から更新用環境を作る時間 |
| `traditional_solve_control_problem_total_time` | `solveControlProblem` 全体時間 |
| `traditional_eu_mts_conversion_time` | `UpdatingEnvironment` から `E_u` MTS への変換時間 |
| `traditional_fluent_extraction_time` | safety goal から Fluent を抽出する時間 |
| `traditional_meta_environment_construction_time` | Fluent とベース環境を合成した metaEnv 構築時間 |
| `traditional_safety_environment_pruning_time` | metaEnv から error を除去して safetyEnv を作る時間 |
| `traditional_safety_env_old_action_uncontrollable_time` | hotSwapIn 前の旧 action を uncontrollable 化する時間 |
| `traditional_safety_env_fluent_valuation_time` | Fluent valuation 構築時間 |
| `traditional_safety_env_formula_eval_and_pruning_time` | formula 評価と違反状態 pruning 全体時間 |
| `traditional_safety_env_formula_evaluation_time` | safety formula を全状態で評価する時間 |
| `traditional_safety_env_violation_pruning_mts_build_time` | 違反状態を除去した MTS 構築時間 |
| `traditional_safety_env_dont_do_twice_time` | DontDoTwice goal 合成時間 |
| `traditional_safety_env_compact_state_conversion_time` | safetyEnv から CompactState への変換時間 |
| `traditional_remove_old_transitions_time` | `.old` action 後処理時間 |
| `traditional_old_safety_fluents` | Traditional old safety fluent 数 |
| `traditional_new_safety_fluents` | Traditional new safety fluent 数 |
| `traditional_old_new_safety_fluents_unique` | old/new safety fluent 重複排除後 |
| `traditional_transition_requirement_fluents` | transition requirement fluent 数 |
| `traditional_meta_environment_fluents` | metaEnv fluent 数 |

Traditional 状態空間:

| base key | 意味 |
|---|---|
| `traditional_mapping_environment` | mapping environment 並列合成結果 |
| `traditional_eu` | `[1. E_u]` |
| `traditional_meta` | `[2. Meta]` |
| `traditional_pruned` | `[3. Pruned]` |
| `traditional_final` | `[4. Final]` |

各 base key には `_states`, `_transitions`, `_count_time` が付く。

## stepwise_delayed 固有

### 設定

| metric_key | 意味 |
|---|---|
| `stepwise_delayed_config_incrementalpruning` | incremental pruning 有効 |
| `stepwise_delayed_config_incrementalpruningcleanup` | incremental pruning cleanup 有効 |
| `stepwise_delayed_config_safetybackwardpruning` | SBP 有効 |
| `stepwise_delayed_config_costguidedcrossscheduling` | cost-guided cross scheduling 有効 |
| `stepwise_delayed_config_indexedhotswapinconnection` | indexed hotSwapIn connection 有効 |
| `stepwise_delayed_config_gr_1_permissive_strategy` | permissive strategy 有効 |

### 分類統計

| metric_key | 意味 |
|---|---|
| `stepwise_delayed_stage_count` | stage 数 |
| `stepwise_delayed_goal_count` | goal 数 |
| `stepwise_delayed_local_goal_count` | local goal 数 |
| `stepwise_delayed_cross_goal_count` | cross goal 数 |
| `stepwise_delayed_cross_goal_ratio_per_mille` | cross goal 比率 |
| `stepwise_delayed_local_old_safety_goal_count` | local old safety goal 数 |
| `stepwise_delayed_local_new_safety_goal_count` | local new safety goal 数 |
| `stepwise_delayed_local_transition_goal_count` | local transition goal 数 |
| `stepwise_delayed_cross_old_safety_goal_count` | cross old safety goal 数 |
| `stepwise_delayed_cross_new_safety_goal_count` | cross new safety goal 数 |
| `stepwise_delayed_cross_transition_goal_count` | cross transition goal 数 |
| `stepwise_delayed_cross_component_count` | cross component 数 |
| `stepwise_delayed_cross_goal_max_scope_size` | cross goal 最大 scope size |
| `stepwise_delayed_cross_component_max_scope_size` | cross component 最大 scope size |
| `stepwise_delayed_all_stage_cross_goal_count` | all-stage cross goal 数 |
| `stepwise_delayed_has_all_stage_cross_goal` | all-stage cross goal あり |
| `stepwise_delayed_requirement_fluent_count` | 分類済み requirement が参照する fluent 数（重複排除後） |
| `stepwise_delayed_cross_component_build_time` | cross component 構築時間 |
| `stepwise_delayed_classification_statistics_count_time` | 分類統計の CountTime |
| `stepwise_delayed_final_tracked_fluent_count` | final product と old controller meta の接続前に追跡する fluent 数 |

### scope 別要求数

section:

```text
Stepwise Delayed DUC scope別要求数
```

key pattern:

```text
stepwise_delayed_scope_requirements_<metricToken(label)>
```

例:

- scope `{1}` の old safety goal 数
- scope `{1}` の new safety goal 数
- scope `{1}` の transition goal 数
- scope `{1,2}` などの cross scope の各 requirement 数

local / cross goal 数は scope から分かるため、scope別CSVでは出さない。

### scope 別fluent数

section:

```text
Stepwise Delayed DUC scope別fluent数
```

key pattern:

```text
stepwise_delayed_scope_fluents_<metricToken(label)>
```

主な対象:

- scope ごとの requirement fluent 数（重複排除後）
- local stage ごとの tracked fluent 数
- cross step ごとの tracked fluent 数
- incremental pruning path の phase / step ごとの tracked fluent 数

tracked fluent 数は、実際にその scope の metaEnv 構築へ渡す fluent 集合のサイズであり、phase comparison fluent も含む。

### scope 別状態空間

section:

```text
Stepwise Delayed DUC scope別状態空間
```

key pattern:

```text
stepwise_delayed_scope_state_space_<metricToken(label)>_states
stepwise_delayed_scope_state_space_<metricToken(label)>_transitions
stepwise_delayed_scope_state_space_<metricToken(label)>_count_time
```

対象例:

- local metaEnv
- local safetyEnv
- cross metaEnv
- cross safetyEnv
- final product / final safetyEnv に近い中間生成物

### scope 別時間

section:

```text
Stepwise Delayed DUC scope別時間
```

key pattern:

```text
stepwise_delayed_scope_time_<metricToken(label)>
stepwise_delayed_scope_time_<metricToken(label)>_scope_count_overhead_time
stepwise_delayed_scope_time_<metricToken(label)>_time_without_scope_count_overhead
```

主な対象:

- local stage の mapping component から local metaEnv を作る時間
- local metaEnv から local safetyEnv を作る時間
- cross step の safetyEnv fragment 並列合成時間
- cross step の product から cross metaEnv を作る時間
- cross metaEnv から cross safetyEnv を作る時間
- final product 並列合成時間

`pruneSafety` を含む safetyEnv 構築時間には評価用 CountTime が含まれ得るため、同じ scope で CountScope を張り、差し引き用の `_time_without_scope_count_overhead` も出す。

### cross scheduling

集計 section:

```text
Stepwise Delayed DUC cross scheduling
```

key pattern:

```text
stepwise_delayed_cross_scheduling_<metricToken(label)>
```

詳細 section:

```text
Stepwise Delayed DUC cross scheduling detail
```

key pattern:

```text
stepwise_delayed_cross_scheduling_detail_<metricToken(label)>
```

主に見るもの:

- step count
- candidate evaluations
- non-first selections
- selected fragments
- merged scope size
- cost / log10 cost
- batch goal count
- selection time
- scheduling metric recording CountTime

`non-first selections` が 0 より大きければ、cost-guided scheduling が固定順先頭以外を選んだ step がある。

### hotSwapIn connection

section:

```text
Stepwise Delayed DUC hotSwapIn connection
```

key pattern:

```text
stepwise_delayed_hot_swap_in_connection_<metricToken(label)>
```

主に見る項目:

- connection mode
- total time
- BFS / matching / eligibility / signature / index build time
- visited pair 数
- transition comparisons
- indexed action entries scanned
- unique old state 数
- unique mapping state 数
- eligible mapping state 数
- old states without target

現在の通常経路では indexed hotSwapIn connection は default off である。

### direct pruning 削減率

section:

```text
Stepwise Delayed DUC direct pruning 削減率
```

key pattern:

```text
stepwise_delayed_direct_pruning_reduction_<metricToken(label)>
```

local / cross の direct safety pruning 前後の削減率を見るための section である。

## Update phase diagnostics

`UpdatePhaseEvaluator` が、Traditional と `stepwise_delayed` の中間生成物に対して update phase 関連の診断を出す。
section 名と artifact label は生成物により変わるため、固定 key の全列挙ではなく suffix 規則で読む。

主な section family:

- update phase state space
- update event transitions
- update phase transition detail
- phase flow
- completion path
- normal action controllability
- next update event distance
- progress-free SCC
- enabled update event states
- update order patterns
- normal run length

### update phase state space

`recordStateSpace(...)` と同じ形式。

```text
<base_key>_states
<base_key>_transitions
<base_key>_count_time
```

### update event transition counts

update event 種別ごとの遷移数を出す。

```text
<base_key>_hot_swap_in
<base_key>_stop_old_spec
<base_key>_reconfigure
<base_key>_start_new_spec
<base_key>_hot_swap_out
<base_key>_normal
<base_key>_update_event_total
<base_key>_total
<base_key>_count_time
```

より詳細な分析では次も出る。

```text
<base_key>_same_phase_normal
<base_key>_normal_rate
<base_key>_avg_out_degree
<base_key>_max_out_degree
```

### completion path / distance

更新開始から完了 phase までの到達距離を出す。

```text
<base_key>_completion_phase
<base_key>_hot_swap_in_transitions
<base_key>_reachable_hot_swap_in_transitions
<base_key>_unreachable_hot_swap_in_transitions
<base_key>_min_length
<base_key>_max_shortest_length
<base_key>_avg_shortest_length
<base_key>_count_time
```

phase 別距離は以下のような suffix になる。

```text
<base_key>_states
<base_key>_reachable_states
<base_key>_unreachable_states
<base_key>_min_distance
<base_key>_max_distance
<base_key>_avg_distance
```

### normal action controllability

normal action の controllable / uncontrollable / unknown 内訳。

```text
<base_key>_controllable
<base_key>_uncontrollable
<base_key>_unknown
<base_key>_total
```

### progress-free SCC

更新事象を含まない通常遷移 cycle / SCC の統計。

```text
<base_key>_normal_cycle_sccs
<base_key>_normal_states_in_cycle_sccs
<base_key>_normal_max_cycle_scc_size
<base_key>_normal_self_loop_cycle_sccs
<base_key>_controllable_cycle_sccs
<base_key>_controllable_states_in_cycle_sccs
<base_key>_controllable_max_cycle_scc_size
```

### enabled update events

各 update event が enabled な状態数。

```text
<base_key>_any
<base_key>_hot_swap_in
<base_key>_stop_old_spec
<base_key>_reconfigure
<base_key>_start_new_spec
<base_key>_hot_swap_out
```

### update order patterns / normal run length

update event の出現順序や normal run length の分布。

```text
<base_key>_dominant_phase
<base_key>_state_occurrences
<base_key>_unique_states
<base_key>_transitions
<base_key>_normal_transitions
<base_key>_update_event_transitions
<base_key>_samples
<base_key>_reachable_samples
<base_key>_unreachable_samples
<base_key>_min_length
<base_key>_max_length
<base_key>_avg_length
```

### shared diagnostic CountTime

複数診断で共有している探索・カウント時間。

```text
<metricKey(section,label)>
```

unit は `ms`。

## Generic reduction / rate metrics

削減率や率を記録する helper は、以下の suffix を出す。

### state / transition reduction

```text
<base_key>_source_states
<base_key>_target_states
<base_key>_removed_states
<base_key>_state_reduction_rate
<base_key>_state_remain_rate
<base_key>_source_transitions
<base_key>_target_transitions
<base_key>_removed_transitions
<base_key>_transition_reduction_rate
<base_key>_transition_remain_rate
```

### transition reduction only

```text
<base_key>_source_transitions
<base_key>_target_transitions
<base_key>_removed_transitions
<base_key>_transition_reduction_rate
<base_key>_transition_remain_rate
```

### decision rate

```text
<base_key>_count
<base_key>_denominator
<base_key>_rate
```

## 要件確認 / hotSwapIn coverage

実装や checker の補助として、hotSwapIn coverage などが `要件確認` section に出る場合がある。

| metric_key | 意味 |
|---|---|
| `hot_swap_in_outgoing_states` | hotSwapIn が outgoing にある状態数 |
| `hot_swap_in_reference_states` | coverage の分母 |
| `old_controller_states_for_hot_swap_in` | hotSwapIn coverage 用の old controller 状態数 |
| `hot_swap_in_coverage_count_time` | hotSwapIn coverage CountTime |

この section は主性能比較ではなく、生成された controller の構造確認用である。

## SBP

個別 SBP は section `Safety Backward Pruning` に出る。

主な metric:

| metric_key | 意味 |
|---|---|
| `sbp_total_time` | `SafetyBackwardPruner.prune(...)`で記録された通常SBP呼び出しの合計 |
| `traditional_sbp_total_time` | Traditional の SBP 合計 |
| `stepwise_delayed_sbp_total_time` | `stepwise_delayed`で記録された通常SBPの合計。通常の非incremental経路ではfinal mapping productと接続後final分析空間が対象 |

個別呼び出しは label に scope が入る。

例:

- `traditional-final / SBP 全体時間`
- `incremental local ... / SBP 全体時間`
- `incremental cross ... / SBP 全体時間`
- `stepwise-delayed-final-mapping / SBP 全体時間`
- `stepwise-delayed-final / SBP 全体時間`

個別 key pattern:

```text
sbp_<metricToken(label)>
```

SBP 前後の状態空間は `recordStateSpace` と同じ規則で出る。

```text
sbp_<scope>_before_sbp_states
sbp_<scope>_before_sbp_transitions
sbp_<scope>_after_sbp_cleanup_states
sbp_<scope>_after_sbp_cleanup_transitions
```

注意:

- `sbp_total_time` は `SafetyBackwardPruner.prune(...)` 全体の wall-clock time。
- before / after の状態数・遷移数 CountTime も含み得る。
- CountTime は別途 `_count_time` や `... CountTime` としても記録される。
- 通常の非incrementalな`stepwise_delayed + safetyBackwardPruning`経路では、localおよびcrossに
  `SafetyBackwardPruner.pruneDeferred(...)`を使う。保留SBPは現時点で
  `sbp_total_time`および`stepwise_delayed_sbp_total_time`へ加算されない。
- 保留SBPの標準出力には、component scope、SBP前後の状態数・遷移数、
  明示的Error seed数、確定losing state数、保留した制御不可能action group数、
  除去した制御可能action group数、および所要時間が出る。これらは現時点でCSV未収録である。

## 比較用時間集計

section:

```text
比較用時間集計
```

主な metric:

| metric_key | 意味 |
|---|---|
| `comparison_observed_total_time` | 実測総時間 |
| `comparison_common_preprocess_time` | 除外する共通前処理時間 |
| `comparison_count_overhead_time` | 実測総時間内の評価用 CountTime |
| `comparison_count_overhead_total` | CountTime 合計 |
| `comparison_count_overhead_after_observed_time` | 実測時間外の CountTime |
| `comparison_evaluation_output_time` | 評価結果出力時間 |
| `comparison_observed_time_without_parse_count_evaluation_output_and_draw` | 構文解析・評価・描画を除いた大枠比較用時間 |
| `comparison_strict_observed_time_without_parse_common_preprocess_count_evaluation_output_and_draw` | 共通前処理も除いた厳密比較用時間 |
| `comparison_method_specific_time` | 手法固有として個別計測できた時間 |
| `comparison_controller_synthesis_time_without_common` | 共通処理を除いたコントローラ合成時間 |
| `comparison_unclassified_non_common_time` | 未分類の非共通時間 |
| `comparison_core_synthesis_time` | 内部計測の中核処理時間 |
| `comparison_traditional_specific_preparation_time` | Traditional 固有準備時間 |
| `comparison_traditional_eu_and_gr1_core_time` | Traditional 更新用環境構築 + GR(1) 参考中核時間 |
| `comparison_stepwise_delayed_gr1_core_time` | `stepwise_delayed` の GR(1) 参考中核時間 |
| `comparison_stepwise_delayed_remove_old_transitions_time` | `stepwise_delayed` removeOldTransitions 時間 |

注意:

- comparison 系は便利だが、式に含まれる範囲を確認して使う。
- 論文用のアルゴリズム差分を見るなら、まず `*_final_gr_input_construction_time` と `*_gr1_solving_time` を分けて見る。
- CountTime を除外したい場合は `comparison_count_overhead_time` や scope 別 CountTime を確認する。

## CountTime と overhead

`recordStateSpace(...)` などで状態数・遷移数を数える時間は、評価用 overhead として recorder が集計する。

主な考え方:

- `CountTime` はアルゴリズム本体そのものではなく、評価用に状態数・遷移数を数える時間。
- wall-clock の各 construction time には、途中の CountTime が含まれ得る。
- recorder は CountTime を `comparison_count_overhead_time` や `_count_time` 系 metric にも出す。
- 厳密な性能比較では、必要に応じて CountTime を差し引いた値も見る。

主な overhead metric:

| metric_key | 意味 |
|---|---|
| `state_transition_count_overhead_total` | 状態数・遷移数 CountTime 合計 |
| `state_transition_count_overhead_in_observed_time` | 実測時間内の CountTime |
| `state_transition_count_overhead_after_observed_time` | 実測時間外の CountTime |

`beginCountScope(...)` / `endCountScope(...)` で囲まれた関数スコープについては、次も出る。

```text
<base_key>_scope_count_overhead_time
<base_key>_time_without_scope_count_overhead
```

後者は、そのスコープの raw time から scope 内 CountTime を引いた参考値である。

Traditional / `stepwise_delayed` の `*_final_gr_input_construction_time` については、この CountScope が測定区間全体に張られている。そのため、GR(1) 入力構築時間から評価用カウント overhead を差し引く場合は、対応する `*_time_without_scope_count_overhead` を使えばよい。

## メモ更新ルール

recorder に新しい metric を追加したら、このメモも更新する。

推奨手順:

1. Java 側で `recordTime` / `recordCount` / `recordStateSpace` などを追加する。
2. 後処理で使う metric なら `knownMetricKey(...)` に固定 key を追加する。
3. `unit` が `ms`, `states`, `transitions`, `B`, `boolean`, `text` のどれかを確認する。
4. このメモの該当 section に key と意味を追記する。
5. 比較用 summary に含める場合は、式と注意点も書く。

`auto_<hash>` のままの key は、後処理や論文用表では使わない方がよい。
