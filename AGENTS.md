# Agent Instructions

このリポジトリは MTSA (Modal Transition System Analyser) を基盤に、Dynamic Update Controller Synthesis (DUCS) の On-the-Fly 版である OTF-DUC を研究・実装している作業ツリーです。

この `AGENTS.md` は、実装を持っているローカルのコーディングエージェント向けです。共同研究者の LLM が実装を持たず、論文執筆だけを担当する場合は、まず `docs/COLLABORATOR_LLM_CONTEXT.md` と `docs/HANDOFF_PROMPT.md` を共有してください。

## 最初に読むもの

1. `README.md`: MTSA 本体の概要と Maven ビルド入口。
2. `docs/PROJECT_KNOWLEDGE.md`: 実装を持つエージェント向けの研究背景、OTF-DUC の設計、主要実装ファイル。
3. `docs/COLLABORATOR_LLM_CONTEXT.md`: 実装を持たない論文執筆LLM向けの自己完結コンテキスト。
4. `docs/HANDOFF_PROMPT.md`: 共同研究者の LLM に渡す初期プロンプト。
5. `docs/DECISIONS.md`: これまでの設計判断。
6. `docs/EXPERIMENTS.md`: 実験ディレクトリ、評価スクリプト、結果ファイルの読み方。

## 現在の研究目標

- 従来 DUCS が構築する更新用環境 `E_u` の状態爆発を、Directed Controller Synthesis (DCS) の On-the-Fly 探索で抑える。
- 旧コントローラから新コントローラへ、安全な更新パスだけを探索する。
- 従来 DUCS の意味、特に `hotSwapIn`, `stopOldSpec`, `reconfigure`, `startNewSpec` の正しさ条件を保つ。
- OTF-DUC では `hotSwapOut` を追加し、新コントローラへ安全に接続できる状態だけを更新完了として扱う。
- 実験では Traditional DUC と OTF-DUC の状態数、遷移数、時間、メモリ、更新フェーズ、完了距離などを比較する。

## 重要な実装領域

- OTF/Traditional の分岐:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatingControllerSynthesizer.java`
- OTF-DUC 探索器:
  `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DirectedControllerSynthesisDUC.java`
- OTF-DUC ヒューリスティック:
  `maven-root/mtsa/src/main/java/MTSTools/ac/ic/doc/mtstools/model/operations/DCS/nonblocking/DUCExplorationHeuristic.java`
- 新コントローラ接続先の事前計算:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/StateMapper.java`
- 更新フェーズ評価:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/synthesis/UpdatePhaseEvaluator.java`
- 評価ログ・CSV・ワークブック出力:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/UpdatingControllerEvaluationRecorder.java`
- 長時間実行の heartbeat:
  `maven-root/mtsa/src/main/java/ltsa/updatingControllers/DUCHeartbeat.java`

## 作業時の注意

- この作業ツリーには未コミット変更が存在することがある。ユーザーが作った変更を戻さない。
- OTF-DUC 関連のファイルは研究途中の実装であり、コメントや日本語ログも研究メモとして意味を持つ場合がある。
- 大きな整形、命名変更、ログ削除は、研究比較や実験再現性に影響するので避ける。
- 変更した設計・実験前提は `docs/PROJECT_KNOWLEDGE.md` または `docs/DECISIONS.md` に追記する。
- 実験結果や処理済みデータはリポジトリ外の `../Experiment/` に置かれている。論文草稿は `../paper_drafts/` にある。

## よく使うコマンド

```bash
cd maven-root/mtsa
mvn clean install -DskipTests=true
```

検索は `rg` を優先する。

```bash
rg -n "hotSwapOut|hotSwapIn|OTF-DUC" maven-root/mtsa/src/main/java
```

評価用データの処理は、ワークスペース直下から `Experiment/tools/` のスクリプトを確認する。
