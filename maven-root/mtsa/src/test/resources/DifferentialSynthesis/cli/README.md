# 差分 synthesis の YAML 実験

作業ディレクトリは `maven-root/mtsa`。実装を含むクラスパスで、
既存の `ltsa.updatingControllers.cli.BatchExperimentRunner` を実行する。

```sh
java -cp <更新済みのクラスパス> ltsa.updatingControllers.cli.BatchExperimentRunner \
  --config src/test/resources/DifferentialSynthesis/cli/experiments.yaml
```

`experiments.yaml` は4シナリオ × 2方式 × 2回を別JVMで実行する。
campaign情報はワークスペースの `Experiment/differential-synthesis/cli-example`、
各runの結果は `Experiment/differential-synthesis/run_01/cli-example` 等に保存する。
既存結果への上書きを防ぐため、再実行時は YAML の `outputDir` を新しい場所へ変える。
パスは YAML 所在地ではなく、プロセスの作業ディレクトリから解決する。
通知は `notifyOn: never`。既存の通常合成・O-DUCS・Stepwise ケースに
`mode` を追加する必要はない。

3方式を更新処理全体で比較する例は `experiments-three-way.yaml`。
4シナリオ × 3方式 × 3回を別JVMで実行し、全ケースで `measurementScope: update` を指定する。
上のコマンドの設定ファイルを次のように置き換える。

```sh
java -cp <更新済みのクラスパス> ltsa.updatingControllers.cli.BatchExperimentRunner \
  --config src/test/resources/DifferentialSynthesis/cli/experiments-three-way.yaml
```

campaign情報は `Experiment/differential-synthesis/three-way-example`、
各runの結果は `Experiment/differential-synthesis/run_01/three-way-example` 等に保存する。

## ケースの指定

```yaml
cases:
  - id: recovery-full
    example: recovery
    mode: differential-synthesis
    method: FULL
    variant: fixed-safety
    lts: src/test/resources/DifferentialSynthesis/BatchSafety.lts
    target: NS_GAME_OLD
    scenario: src/test/resources/DifferentialSynthesis/cli/recovery-good.yaml
  - id: recovery-differential
    example: recovery
    mode: differential-synthesis
    method: DIFFERENTIAL
    variant: fixed-safety
    lts: src/test/resources/DifferentialSynthesis/BatchSafety.lts
    target: NS_GAME_OLD
    scenario: src/test/resources/DifferentialSynthesis/cli/recovery-good.yaml
```

| method | 更新後の要求解析 | 制御器生成 |
| --- | --- | --- |
| FULL | 差分要求分析 | 勝利領域から到達可能な制御器全体を生成 |
| DIFFERENTIAL | 差分要求分析 | 旧制御器を再利用し、追加部分と新制御器を生成 |
| FROM_SCRATCH | 更新済みゲーム全体を再解析 | 勝利領域から到達可能な制御器全体を生成 |

既存の `FULL` は引き続き、差分要求分析の結果を入力とする制御器生成の比較方式。
全再解析を含める方式は `FROM_SCRATCH` として分ける。
3方式は同じ初期準備・ゲーム更新・旧制御器とのsimulation判定を使用する。
`FROM_SCRATCH` も共通の互換性判定を通過した場合だけ生成する。
全再解析の対象は更新済みの安全性ゲームであり、FSPコンパイル等は共通の初期準備に含まれる。

同一run・同一FSPファイル・同一target・同一scenarioファイル・同一計測scopeに
異なる方式を指定した場合、campaignの検証は準備済み入力のハッシュ・結果status・
制御器出力のハッシュ一致を確認する。1方式だけ、または3方式のうち2方式だけでも実行できる。

`alternateMethodOrderByRun: true` は同じ `example, variant, measurementScope` ごとに
実行順を変える。既存の FULL/DIFFERENTIAL ペアは、奇数runではDIFFERENTIALが先、
偶数runではFULLが先。3方式では1回目が FULL → DIFFERENTIAL → FROM_SCRATCH、
2回目が DIFFERENTIAL → FROM_SCRATCH → FULL、3回目が FROM_SCRATCH → FULL → DIFFERENTIAL
となる。FROM_SCRATCHを含む2方式も、この並びに含まれる方式をrunごとに巡回する。
`requirementsCheck` と `traceCheck` はDUCS用なので、このモードでは無効にする。

ケースの `measurementScope` は `generation` または `update`。
省略時は `generation` で、既存の `experiments.yaml` の計測範囲を維持する。
専用childを直接実行する場合は `--measurement-scope generation|update` で指定する。

## 更新シナリオ

```yaml
controllableActions: [idle, start]
states:
  initial: {trace: []}
  working: {trace: [start]}
  recovery: {new: true}
transitions:
  - {from: working, action: divert, to: recovery}
  - {from: recovery, action: resume, to: working}
  - {from: recovery, action: wait, to: recovery}
```

`trace` は旧ゲームの初期状態からたどるアクション列。
空列は初期状態で、`[start]` は `start` の到達先を表す。
すべてのtraceを旧ゲーム上で解決してから、遷移と新状態を一括追加する。
旧制御器が禁止している旧ゲームの遷移もtraceでは使用できる。
コンパイル後の整数状態番号は入力しない。

`new: true` は新しい**ゲーム状態**を宣言する。
`unsafe: true` を併記すると、固定した安全性モニタにおける違反状態を宣言する。
unsafe状態からの遷移追加はできない。安全性要求を変更する指定ではない。
新状態は呼出側が環境・モニタの整合した積状態として定義する責任を持つ。
この入口は成分FSPの差分抽出やモニタ状態の自動計算を行わない。

`controllableActions` にないアクションは制御不能。新しい制御可能アクションも
この固定集合に最初から含める。`tau` は制御可能にできない。
アクション `on` / `off` などYAMLがbooleanと解釈する文字列は引用符で囲む。
重複キー、未知キー、不正なtrace、未定義alias、同一ラベルで異なる遷移先などは拒否する。
削除、要求緩和、非決定的ゲームには対応していない。

## 結果と計測

`ds_status` は次のいずれか。

| 値 | 意味 |
| --- | --- |
| SUCCESS | 旧振舞いを保存した新制御器を生成 |
| SIMULATION_INCOMPATIBLE | 更新後に安全な制御器は存在するが、旧制御器を保存できない |
| UNREALIZABLE | 更新後の初期状態が敗北領域 |
| BASE_UNREALIZABLE | 更新前の制御器が存在せず、比較の前提を満たさない |

正常に得られた否定結果もbatchの実行statusはSUCCESSになる。
比較対象の抽出には必ず `ds_status` を使う。
生成しなかった場合の `ds_generation_ns` は空欄とし、0秒の合成成功とは扱わない。

現在の差分実験CSVは `ds_schema_version=2`。
ゲーム更新と要求解析を分離し、次の時間をナノ秒単位で記録する。
旧schema 1のCSV・実験成果物は変更せず、新しい実行結果とschemaを区別する。

| 指標 | 対象 |
| --- | --- |
| ds_initial_preparation_ns | FSPコンパイル・並列合成・初回の安全性解析 |
| ds_base_controller_ns | 旧制御器生成 |
| ds_delta_resolve_ns | シナリオの状態対応解決 |
| ds_game_update_ns | 追加バッチの適用とゲーム・索引の更新 |
| ds_analysis_ns | 要求解析のみ。FULL/DIFFERENTIALは差分解析、FROM_SCRATCHは全再解析 |
| ds_compatibility_ns | simulation判定と切替状態対応の準備 |
| ds_generation_ns | 選択した制御器生成APIの実行 |
| ds_update_pipeline_ns | update scopeのゲーム更新開始から解析・判定・生成終了までのwall時間 |
| ds_export_ns | 制御器・パッチ・状態対応のファイル出力 |

`ds_game_update_ns` と `ds_analysis_ns` は4種類の正常なstatusすべてで記録する。
旧制御器が存在しない場合の互換性判定や、否定結果での生成など、未実行の区間は空欄にする。
`ds_update_pipeline_ns` はupdate scopeで記録し、generation scopeでは空欄にする。

| measurementScope | heap/RSSの計測窓 | 否定結果での計測 | ds_memory_scope |
| --- | --- | --- | --- |
| generation（既定） | 準備済みの解析結果と互換性判定結果から制御器を生成 | 窓を開かない | 成功時generation_with_prepared_inputs、生成なしはnot_run |
| update | 準備済み旧解析・旧制御器と解決済みdeltaを起点に、ゲーム更新・解析・共通simulation判定・生成 | 実行した更新・解析・判定を計測 | update_with_old_inputs |

update窓と `ds_update_pipeline_ns` に、FSPの再コンパイル、旧ゲームの初回解析、旧制御器生成、
CLI起動、シナリオの読込・状態対応解決、ハッシュ計算、ファイル出力は含めない。
`ds_update_pipeline_ns` は各区間の単純な合計ではなく、一続きの処理を囲んだwall時間である。
RSS境界の待合せとsamplerの開始・終了処理は、この時間と `ds_generation_ns` の外に置く。

heap/RSSは、選択した窓における**プロセス全体の使用量**を測る。
全方式で旧解析・旧制御器・解決済みdeltaを終端サンプルまで保持し、各実装の中間ゲーム、
更新後解析、simulation判定結果、新制御器も保持する。
FROM_SCRATCHでは更新済み入力graphと、全再解析で独立に作ったsnapshotの双方がliveになる。
これは実装の保持条件を含む測定であり、各方式を最小のliveデータまで削減したメモリ比較ではない。
生成処理だけの割当量や正確な保持オブジェクトサイズとも区別する。
短い一時ピークは周期サンプリングでは捕捉できない場合がある。
初期準備を含む全工程のピーク測定とは区別する。

これは1更新ごとのfresh JVM・warmupなしの実験プロトコル。
同一Planを1つのJVMで共有するAPIの正しさテストとは区別し、
実験では準備経路を各JVMで再実行して `ds_prepared_input_sha256` の一致を検証する。
入力ファイルはcampaign中に編集しない。

制御器の出力はソートしたTSV（アクション欄はJSON文字列）。
成功時は `*.switch.tsv` に旧状態→新状態対応、
差分生成時は `*.patch.tsv` に追加状態・アクション・遷移も出力する。
これらは実験・確認用の出力であり、実行中システムへの適用やhotswapを実施する入口ではない。
否定結果の制御器TSVにはSTATUS行のみがあり、ダミー制御器を出力しない。

## スケーリング用入力

生成した規模別の入力は、ワークスペースの
`Experiment/differential-synthesis/scaling-diagnostics/2026-10-03` に置いている。
5条件 × 3方式 × 3回の45実行が完了し、状態数・遷移数・再解析範囲・再利用数、
3方式の入力と制御器出力の一致、メモリ計測を検証済み。
集計は同ディレクトリの `summary-final/summary.md` と `summary.json` に保存する。
新規JVM・ウォームアップなしの予備実験であり、一般的な速度・メモリの優位性は主張しない。

より大きいモデルでの生成部分の比較は、ワークスペースの
`Experiment/differential-synthesis/large-scaling/2026-10-03/report.md` に記録した。
50万状態でFULL/DIFFERENTIALを各6回、`measurementScope: generation`、ヒープ上限2 GiBで測定した。
この入力はアクション数が状態数とともに増えない二分木であり、先のモデルと同じ系列の倍率比較はしない。
約325万遷移の別入力で起きた準備段階のタイムアウトも保存している。

変更範囲を広げる比較は、ワークスペースの
`Experiment/differential-synthesis/change-scope/2026-10-04/` に保存する。
旧モデルを20万状態に固定し、変更する旧状態数と追加する新状態数を別々に増やす。
全条件で追加アクションを `delta.0` に共有し、アクション名の種類の増加と区別する。
生成用スクリプトと監査・集計手順は `Experiment/differential-synthesis/tools/README_scaling_models.md` を参照。
`ds_expanded_states` は生成時に出遷移を走査した状態数であり、遷移mapの新規割当数ではない。
FULLも安全なゲームの遷移mapを共有するため、全遷移のdeep copyとの比較ではない。
