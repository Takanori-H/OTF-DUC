# Project knowledge 日本語版

英語版は `PROJECT_KNOWLEDGE.md` に残している。

この file は、実装を持つローカル LLM 向けの共有 memory layer であり、形式仕様ではない。実装とこの document が食い違う場合は、code を確認してこの document を更新する。

codebase を持たず paper 執筆だけを手伝う共同研究者 LLM には、`COLLABORATOR_LLM_CONTEXT.md` を使う。

## 一文要約

この project は MTSA に OTF-DUC を拡張する。OTF-DUC は On-the-Fly Dynamic Update Controller Synthesis 手法であり、従来 DUCS の full update environment `E_u` を明示的に構築せず、old controller から new controller への update bridge だけを探索する。

## 研究 context

MTSA は FSP / LTS / MTS と controller synthesis algorithm による concurrent software system の modeling, analysis, synthesis をサポートする。

研究問題は Dynamic Update Controller Synthesis (DUCS) である。old controller、new controller、old/new environment、safety property、transition requirement、environment mapping が与えられたとき、実行中 system を old specification から new specification へ停止せずに移行する update controller を合成する。

Traditional DUCS は update environment `E_u` を構築してから control problem を解く。bottleneck は、`E_u` が old controller、mapping environment、safety monitor、transition requirement、update-event interleaving を encode するため、合成前に size が爆発し得ることである。

OTF-DUC は DUCS の意味を保ちながら、full `E_u` construction を DCS-style On-the-Fly exploration に置き換える。paper での重要な framing は、OTF-DUC は DUCS の correctness goal を変えているのではなく、materialize する update game の量を変えている、という点である。

## Core update event

実装は次の update event を使う。

- `hotSwapIn`: update phase を開始し、old controller から update controller へ control を渡す。
- `stopOldSpec`: old safety specification を enforce する必要がなくなったことを宣言する。
- `reconfigure`: mapping environment を通して old-environment state から new-environment state 側へ system を進める。
- `startNewSpec`: new safety specification の enforce を開始する。
- `hotSwapOut`: OTF-DUC 固有の completion event。current mapping/new-safety state から new controller へ安全に接続できる場合にだけ許可する。

実装上の定数名では、`UpdateConstants.BEGIN_UPDATE = "hotSwapIn"`、`UpdateConstants.FINISH_UPDATE = "hotSwapOut"` である。`FINISH_UPDATE` という Java 定数名は歴史的に残っているが、設計・メモ・論文上の event 名は `hotSwapOut` と書く。

`hotSwapOut` は traditional DUCS で使う GR(1) guarantee とは意図的に区別する。update controller を new controller へ接続する OTF handoff condition である。

## OTF-DUC design

OTF-DUC は full update environment ではなく DCS box list を構築する。box list は `UpdatingControllerSynthesizer.generateDUC` で作られる。

現在の box-list 構造:

| Segment | 役割 |
|---|---|
| index 0 | update progress 用 Marking LTS |
| index 1 | action を `_old` 付きに rename した Old Controller |
| mapping range | Mapping Environment component |
| old safety range | Old Safety property |
| new safety range | Original New Safety property |
| transition requirement range | Transition Requirement |
| synthesis range | fluent-derived synthesis machine / monitor |

New controller は意図的に box list から除外する。代わりに `StateMapper` が `(New Environment state, New Safety state)` signature から new-controller state への map を事前計算する。探索中、`hotSwapOut` は current mapping environment が new-environment state に変換でき、得られた new-environment/new-safety signature が new controller に存在する場合にだけ enabled になる。

## Marking LTS

OTF Marking LTS は `UpdatingControllerSynthesizer` の `createOTFMarkingLTS` で生成する。

実装は10状態を使う。

| State | 意味 |
|---:|---|
| 0 | `hotSwapIn` 前。old controller がまだ動作する |
| 1 | `hotSwapIn` 後。主要3 update event は未完了 |
| 2 | `stopOldSpec` 完了 |
| 3 | `reconfigure` 完了 |
| 4 | `startNewSpec` 完了 |
| 5 | `stopOldSpec` と `reconfigure` 完了 |
| 6 | `stopOldSpec` と `startNewSpec` 完了 |
| 7 | `reconfigure` と `startNewSpec` 完了 |
| 8 | `stopOldSpec`, `reconfigure`, `startNewSpec` がすべて完了 |
| 9 | `hotSwapOut` 完了。OTF goal state |

marked goal state は state 9 だけである。analysis script が使う bit 意味は `1=stopOldSpec`, `2=reconfigure`, `4=startNewSpec` である。

## Fine-grained update event

`.lts` keyword `fine_grained` は fine-grained update event を選択する。`on_the_fly` と一緒なら fine-grained OTF-DUC、`on_the_fly` なしなら fine-grained Traditional DUC になる。この flag がなければ、両 mode は legacy bulk update event を使う。

Fine-grained mode は3つの bulk update event を per-component progress action に置き換える。

- 各 old safety property に対する `stopOldSpec_<safety>`。
- 各 new safety property に対する `startNewSpec_<safety>`。
- 生成された mapping component ごとに1つの `reconfigure_*` action。

relation rule は `reconfigure_*` を明示的に書いてもよいし、legacy `reconfigure` を書いてもよい。legacy `reconfigure` は内部で `reconfigure_<mapping component name>` に正規化される。各 mapping component は reconfigure action を1つだけ持つ。

DCS box list には引き続き index 0 に1つの progress component だけを置く。これは small synthetic slot であり、concrete progress ID は `ProgressRegistry` が BigInteger completion mask から割り当てる。そのため large model でも fine-grained update action 数は 63 個に制限されず、flag ごとに1つの LTS を必要としない。`hotSwapOut` は handoff action として残り、すべての fine-grained progress action が完了し New Controller stitching guard が成功した後にだけ available になる。

Traditional fine-grained DUC は traditional explicit `E_u` construction を保つ。fine-grained `E_u` は、update state に per-safety `stopOldSpec_*` / `startNewSpec_*` self-loop を持ち、generated mapping environment からの per-mapping `reconfigure_*` transition を持ち、すべての fine-grained progress action に DontDoTwice constraint と GR guarantee を設定する。OTF-only completion action である `hotSwapOut` などは追加しない。

## Active / Trace / Enforce semantics

OTF-DUC は各 component を3つの概念 flag で制御する。

- Active: action が component alphabet に含まれるが current state で enabled でない場合に、その component が action を block できる。
- Trace: observed action に応じて component の internal state を更新する。
- Enforce: error / sink state に到達したときに fatal violation として扱う。

`hotSwapIn` 前は old controller が active であり、その action は `_old` 付きに rename される。environment、safety、fluent machine は必要に応じて対応する unrenamed action を trace する。

`hotSwapIn` 後、old controller は update path から切り離される。mapping environment が main active environment になる。Old Safety は `stopOldSpec` まで enforce され、New Safety は `startNewSpec` 後にだけ enforce される。

Trace-off component は、不要な component-state difference を distinct search state として扱わないように normalize する。

## Delayed New Safety Activation

New Safety は update の最初から enforce しない。fluent から派生した synthesis machine が background tracer として動く。`startNewSpec` が発火すると、`DirectedControllerSynthesisDUC` は `safetyStateLookupMap` を使い、current fluent/monitor state が示す original New Safety component state へ jump する。

これは重要である。update path が new safety specification の有効化時点を遅らせつつ、有効化後は正しい New Safety state を enforce できるためである。

## hotSwapOut Guard and Stitching

`hotSwapOut` は Marking state 8 で常に available とは限らない。

`DirectedControllerSynthesisDUC` の hotSwapOut guard 実装は次を要求する。

- current Mapping Environment state が New Environment state に変換できる。
- 変換後の New Environment state と current New Safety state が、New Controller 内の safe state に対応する。

DCS search が成功した後、`buildDirectorDUC` は output update controller を構築し、`hotSwapOut` edge を対応する New Controller state へ接続する。これが exit stitching step である。

output-building logic には、old-controller-like pre-update state の entry-side handling もある。action-name restoration と controllability restoration を含む。

marking state 8 の意味は注意して読む必要がある。m8 では更新プロトコル本体は完了しており、新安全性も有効になっている。残っている問題は、update bridge から `hotSwapOut` によって、事前に合成した New Controller へ移行できるかどうかである。実装では次の2つの解釈をサポートする。

- デフォルトモード: m8 の SCC は既存の fairness 固定点のもとで受理され得る。この場合、移行完了性は fairness 仮定付きの性質として扱う。
- `-Dotfduc.fairness=false` または `-Dotfduc.disableFairness=true`: m8 の fairness による GOAL 昇格を無効化する。この場合、移行完了性は通常の全トレース到達性として扱う。fairness 仮定なしで `hotSwapIn -> <> hotSwapOut` を確認したい場合はこちらを使う。

## 主な実装 map

- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java`
  - Traditional DUC と OTF-DUC の entry point。
  - legacy / fine-grained Traditional DUC environment と safety synthesis path を選ぶ。
  - OTF box list を構築する。
  - Marking LTS を作る。
  - New Controller connection map を作る。
  - New Safety / Fluent map を box-list index に変換する。
  - `DirectedControllerSynthesisDUC` を呼び出す。
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DirectedControllerSynthesisDUC.java`
  - OTF-DUC search engine。
  - DUC compostate を expand する。
  - Active / Trace / Enforce behavior を扱う。
  - `startNewSpec` safety synchronization を扱う。
  - `hotSwapOut` guard を確認する。
  - fairness / loop reasoning と output construction を扱う。
  - detailed evaluation metric を記録する。
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DirectedControllerSynthesisFineGrainedDUC.java`
  - fine-grained OTF-DUC entry class。
  - fine-grained protocol semantics、progress-slot transition、update-action priority、per-safety New Safety synchronization を持つ。
  - protected hook を通して `DirectedControllerSynthesisDUC` の common search/output machinery を再利用する。
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/ProgressRegistry.java`
  - fine-grained completion mask を synthetic progress state ID に map する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/structures/UpdateProtocolSpec.java`
  - generated stop/reconfigure/start progress action を構築・分類する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/FineGrainedUpdatingEnvironmentGenerator.java`
  - Traditional DUC fine-grained `E_u` generator。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/FineGrainedUpdatingControllersUtils.java`
  - Traditional DUC fine-grained GR / safety goal generation helper。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/FineGrainedUpdatingControllerSafetySynthesizer.java`
  - Traditional DUC fine-grained safety pruning entry point。
  - all fine-grained progress action を DontDoTwice に渡しながら shared logic に委譲する。
- `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DUCExplorationHeuristic.java`
  - OTF-DUC exploration の priority ordering。
  - より深い Marking progress、新しい state (LIFO)、recommendation score の順に優先する。
  - recommendation score は update action を強く優先し、score が同じ場合だけ ordinary controllable action よりも uncontrollable action を優先する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/StateMapper.java`
  - New Controller と component state を traverse する。
  - New Environment / New Safety state から New Controller state への signature mapping を構築する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatePhaseEvaluator.java`
  - update phase、update-event transition、completion distance、phase flow、progress-free cycle、enabled update event を分析する。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
  - timing、memory、state-space、phase、output、CSV metric の central recorder。
- `maven-root/mtsa/src/main/java/ltsa/updatingControllers/DUCHeartbeat.java`
  - `duc.heartbeat` などの Java system property で制御される optional heartbeat logger。

## Non-code knowledge source

- `docs/FG_DUCS_DESIGN.md` / `docs/FG_DUCS_DESIGN_JA.md`
  - FG-DUCS と FG-O-DUCS の設計・実装メモ。
- `docs/STEPWISE_DUCS_DESIGN.md` / `docs/STEPWISE_DUCS_DESIGN_JA.md`
  - Stepwise DUCS の初期設計メモ。cross なし構成から始めた段階の設計方針。
- `docs/STEPWISE_DUCS_IMPLEMENTATION_MEMO.md` / `docs/STEPWISE_DUCS_IMPLEMENTATION_MEMO_JA.md`
  - 英語版は以前の混在 discussion memo として残っている。日本語版は非 delayed `stepwise` 現在実装メモで、stage ごとの `E_u_i` 構築、local / cross pruning、cross old controller restriction、final GR(1) 入力までを扱う。
- `docs/STEPWISE_DELAYED_DUCS_ALGORITHM_MEMO_JA.md`
  - Stepwise DUCS / `stepwise_delayed` のアルゴリズム確認メモ。local / staged cross / delayed `hotSwapIn` connection / final GR(1) までの流れを扱う。
- `docs/STEPWISE_DELAYED_DUCS_IMPLEMENTATION_MEMO_JA.md`
  - `stepwise_delayed` の現在実装メモ。cost-guided cross scheduling、SBP、hotSwapIn connection、CSV 計測を含む。
- `docs/SFG_DUCS_DESIGN.md` / `docs/SFG_DUCS_DESIGN_JA.md`
  - Selective FG-DUCS と Selective FG-O-DUCS の focused design / implementation memo。
- `docs/FORMULA_DECOMPOSITION_NOTES.md` / `docs/FORMULA_DECOMPOSITION_NOTES_JA.md`
  - transition requirement、old safety、new safety decomposition の議論メモ。現在は未実装。
- `docs/RUNTIME_OPTIONS_AND_LTS_MODES.md` / `docs/RUNTIME_OPTIONS_AND_LTS_MODES_JA.md`
  - jar 起動オプションと `.lts` mode flag の運用メモ。
- `maven-root/OTF-DUC 実装・研究設計仕様書_ver8.1.txt`
  - OTF-DUC の詳細な日本語設計仕様書。
- `../paper_drafts/otf_duc_intro_drafts.tex`
  - paper introduction draft と positioning。
- `../Experiment/`
  - generated controller、log、processed data、script。
- workspace root の PDF
  - DUC、DCS、dynamic update の参考論文。

## Current working tree note

この knowledge layer 作成時点で、`mtsa` Git repository には OTF-DUC 関連 Java file の local modification と untracked research / implementation file が既に存在していた。これらを disposable な変更だと仮定しない。編集前に `git status --short` を確認する。

## Future agent 向け open question

- paper で canonical として扱う OTF-DUC variant はどれか。normal OTF のみか、repair、simple merge、または組み合わせか。
- paper number の current source of truth として扱うべき experiment workbook はどれか。
- `hotSwapOut` はすべての final claim において GR(1) guarantee set の外に置くべきか。それとも separate reachability condition を持つ handoff event として paper に書くべきか。
- すべての benchmark log は current Java code から再生成されているか。それとも processed file の一部は以前の実装に対応しているか。
