# 実験メモ

英語版は `EXPERIMENTS.md` に残している。

実験 artifact は現在、`mtsa` Git repository の外にある workspace root の `../Experiment/` に置かれている。

## ディレクトリ構成

`../Experiment/` には次のものが含まれる。

- `OldController/`: 生成または保存された old controller。
- `NewController/`: 生成または保存された new controller。
- `LTS/`: LTS benchmark material。
- `OTF-DUC/`: OTF-DUC の出力 log / controller。
- `Traditional DUC/`: Traditional DUC の出力 log / controller。
- `processed/`: 処理済み CSV / XLSX 出力。
- `tools/`: log parsing、controller validation、workbook 構築用 script。

確認されている benchmark / example family には次がある。

- `Surveillance`
- `ProductionCell`
- `Workflow`
- `GSM`
- `Industry`
- `MetaSocket`
- `RailCab`
- `PowerPlant`

## 処理済み出力

重要な処理済み file は次の通り。

- `../Experiment/processed/Experiment_evaluation_results.xlsx`
- `../Experiment/processed/All/extracted_rows_full.csv`
- `../Experiment/processed/All/runs_compact.csv`
- `../Experiment/processed/All/metric_dictionary.csv`
- `../Experiment/processed/ByExample/*_evaluation_results.xlsx`
- `../Experiment/processed/Workflow/Workflow_evaluation_results.xlsx`
- `../Experiment/processed/otf_duc_gr1_validation.csv`
- `../Experiment/processed/otf_duc_gr1_validation.md`

この document 作成時点では validation Markdown file は存在していたが、確認した header には結果行が埋まっていなかった。validation result を引用する前に CSV を確認するか、validation を再生成する。

## Tools

`ltsa.updatingControllers.cli.BatchExperimentRunner`

- YAML で定義した experiment batch を jar / classpath から実行する。
- YAML root key `runs: N` を指定すると、`cases:` list 全体を N 回繰り返す。
- 1 run は、YAML 内の全 case を上から順に1回ずつ実行することを意味する。
- `runs` が指定された場合、出力先は run label で包まれる。例えば `outputDir: Experiment/result` は `Experiment/run_01/result/...`, `Experiment/run_02/result/...` のように出力する。
- `runs` を省略した場合は、互換性のため従来の出力 layout を保つ。
- `runCount` と `repetitions` も alias として受け付けるが、推奨 key は `runs` である。
- 各 case の評価 CSV は runner が自動で case/run 専用 path を生成し、child JVM に `-Dmtsa.evaluation.csvFile=...` として渡す。YAML の `javaOptions` に固定 CSV path を書くと複数 case で衝突し得るため、通常は書かない。
- CSV file path は `meta.json` の `evaluationCsv` に記録される。CSV 本体にも `batch_case_id`、`batch_target`、`batch_run_index`、`batch_run_label` などの Run metadata が含まれる。
- 各 case は通常合成を1回だけ行い、raw transitions と評価 CSV を保存した後、同じ合成結果を minimize して `*_minimized_transitions_*.txt` と `*_minimized_counts_*.csv` を保存する。minimize は post-synthesis 処理であり、`コントローラ合成時間` には含めない。
- `meta.json` には `minimizedTransitions`、`minimizedCounts`、`rawOutputStates`、`rawOutputTransitions`、`minimizedOutputStates`、`minimizedOutputTransitions` も記録される。
- GR(1) にかかる時間は evaluation CSV に記録される。Traditional は `traditional_gr1_solving_time`、stepwise_delayed と stepwise_delayed+SBP は `stepwise_delayed_gr1_solving_time` を見る。SBP 有効時は SBP 後の final safety environment を GR(1) に渡す時間であり、case id / variant / YAML metadata で通常 stepwise_delayed と区別する。
- GR(1) 内訳も CSV に出る。主な `metric_key` は `*_gr1_synthesize_total_time`、`*_gr1_winning_region_time`、`*_gr1_strategy_build_time`、`*_gr1_strategy_to_controller_mts_time`、`*_gr1_compact_state_conversion_time` で、`*` は `traditional` または `stepwise_delayed`。
- Slack 通知は YAML の top-level `slackWebhookUrl`、`notifyOn`、`notifyTimeoutSeconds` で任意に設定できる。runner は全 run / case が終わった後に1回だけ通知し、`--dry-run` では通知しない。実 webhook URL は Git 管理下の file に入れない。

例:

```yaml
outputDir: Experiment/result
runs: 5
timeoutHours: 16
notifyOn: always  # always, success, failure, never
slackWebhookUrl: "https://hooks.slack.com/services/..."
notifyTimeoutSeconds: 30

javaOptions:
  - -Dmtsa.evaluation.enabled=true

cases:
  - id: productioncell_n_1_k_1_otf_no_tr
    example: "ProductionCell_N=1, K=1"
    method: OTF
    variant: no_tr
    lts: "Experiment/LTS/ProductionCell/ProductionCell_N=1, K=1/ProductionCell_N=1, K=1_otf_no_tr.lts"
    target: UpdCont_OTF
```

### Slack 通知メモ

評価実験用 Windows PC などで長時間 batch の終了時刻が分からなくなる問題に対して、`BatchExperimentRunner` は YAML から Slack Incoming Webhook 通知を送れる。

実装は `maven-root/mtsa/src/main/java/ltsa/updatingControllers/cli/ExperimentConfig.java` と `maven-root/mtsa/src/main/java/ltsa/updatingControllers/cli/BatchExperimentRunner.java` にある。通知は child JVM ではなく batch runner 親プロセスが送る。全 `runs` / `cases` の実行が終わった後に1回だけ送信し、case ごとには送らない。

YAML key は top-level に置く。`cases:` の下には置かない。

- `slackWebhookUrl`: Slack Incoming Webhook URL。alias として `slackIncomingWebhookUrl` と `slackWebhook` も読める。
- `notifyOn`: `always`, `success`, `failure`, `never`。default は `always`。
- `notifyTimeoutSeconds`: Slack POST の接続・読み取り timeout 秒数。default は `30`。

`notifyOn: always` は、batch 全体が成功しても失敗しても通知するという意味である。実験が「終わったこと」を知る用途ではこれを使う。`success` は全 case 成功時のみ、`failure` は timeout / OOM / exception / no composition などを含む失敗時のみ、`never` は通知しない。

`notifyTimeoutSeconds` は、Slack webhook へ通知を送る通信を何秒まで待つかを表す。例えば `30` なら、Slack への接続と Slack からの応答待ちにそれぞれ最大30秒を使う。Slack やネットワークが遅い場合に runner が通知送信で長く止まらないようにするための値であり、通知に失敗しても実験の成功 / 失敗判定は変えない。

通知本文には、`SUCCESS` / `FAILURE`、失敗 case 数、総 case 数、run 数、YAML config path、outputDir、開始・終了時刻、経過時間を入れる。runner 側の例外で終了した場合は runner error も短く入れる。

運用上の注意:

- `--dry-run` では通知しない。
- Slack 通知に失敗しても、実験結果の exit code は変えない。失敗は `stderr` に warning として出す。
- webhook URL は `meta.json` に記録しない。Slack 通知失敗時の warning でも URL は `<slack-webhook-url>` に置き換える。
- 実 webhook URL は Git 管理下の YAML に入れない。評価実験 PC のローカル YAML か、Git 管理外の experiment artifact として扱う。
- webhook URL が漏れた場合は Slack app の Incoming Webhooks で古い URL を削除し、新しい webhook URL を発行する。
- Slack スマホアプリで通知が来ない場合は、対象チャンネルの通知設定を `すべての新規メッセージ` にし、モバイル通知 timing を即時にする。

Mac での smoke test は、存在しない LTS を指す一時 YAML を使うと短時間で通知経路だけ確認できる。期待される通知 status は `FAILURE` である。

```yaml
outputDir: "/tmp/mtsa-slack-test/result"
runs: 1
timeoutMinutes: 1

notifyOn: always
slackWebhookUrl: "https://hooks.slack.com/services/..."
notifyTimeoutSeconds: 10

javaOptions:
  - -Djava.awt.headless=true
  - -Xmx512m

cases:
  - id: slack_notification_smoke_test
    example: SlackNotificationSmoke
    method: OTF
    variant: notification_test
    lts: "/tmp/mtsa-slack-test/does-not-exist.lts"
    target: UpdCont_OTF
```

2026-07-07 時点で、Mac からの direct Slack webhook test と YAML 経由の `BatchExperimentRunner` smoke test は成功確認済み。YAML 経由では意図通り `Status: FAILURE`, `Case failures: 1 / 1` の通知が届いた。

`../Experiment/tools/compact_mtsa_evaluation.py`

- MTSA output log 内の machine-readable な `EVALUATION DATA CSV` block、または batch runner が出力した `*_evaluation_*.csv` を読み込む。
- file path から推定した run metadata を追加する。
- `run_01` のような batch-run folder を検出し、`batch_run` と `run_id` に入れる。そのため YAML の複数回 run は、後で平均を取るための別 row として残る。
- standalone CSV に `batch_case_id`、`batch_run_label` などの Run metadata が含まれる場合は、それを優先して compact row に反映する。
- compact CSV file と metric dictionary を生成する。

`../Experiment/tools/build_experiment_workbooks.py`

- 処理済み evaluation CSV data から Excel workbook を構築する。
- aggregate experiment result を更新するときに使う。

`../Experiment/tools/build_workflow_workbook.mjs`

- Workflow に特化した workbook を構築する。

`../Experiment/tools/validate_otf_uc_gr1.py`

- OTF-DUC output controller を traditional DUC の GR(1) progress goal に対して検証する。
- reachable な `hotSwapIn` の後に、`stopOldSpec`, `reconfigure`, `startNewSpec` のいずれかが未完了のまま cycle に永久に留まれないことを確認する。
- mask bit は `1=stopOldSpec`, `2=reconfigure`, `4=startNewSpec`。
- `hotSwapOut` は3つの traditional GR(1) guarantee とは別に扱う。

## Java 側の metric source

多くの評価 metric は次の file で記録される。

- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatePhaseEvaluator.java`
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DirectedControllerSynthesisDUC.java`
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java`

metric category には次が含まれる。

- input size。
- Traditional DUC の `E_u`, meta environment, safety environment, GR1 time。
- stepwise_delayed / stepwise_delayed+SBP の final GR(1) time と GR(1) breakdown。
- OTF-DUC の box-list preparation, Marking LTS generation, StateMapper time, DCS search, output construction。
- state / transition count。
- update-event transition count。
- update phase flow。
- `hotSwapIn` から completion までの distance。
- phase ごとの enabled update event。
- hotSwapOut guard block。
- New Controller connection success / miss。
- cache と heartbeat diagnostics。

## 再現性メモ

- 実験結果を引用する前に、processed workbook が現在の Java code に対応する log から生成されたものか確認する。
- MTSA UI output から log をコピーする場合、`EVALUATION DATA CSV` block を source of truth として優先する。
- filename と metadata では Traditional DUC と OTF-DUC run を明確に分ける。processing script は path から method と benchmark 情報を推定する。
- Java 側で metric label や section を変更した場合は、aggregate workbook だけでなく `metric_dictionary.csv` も更新する。

## 推奨 validation flow

1. Traditional DUC と OTF-DUC の両方について MTSA output log を生成または収集する。
2. log directory に対して compact extraction script を実行する。
3. aggregate workbook と by-example workbook を構築する。
4. OTF-DUC output controller に対して GR(1)-progress validation を実行する。
5. state-space と timing metric を paper claim と比較する。
