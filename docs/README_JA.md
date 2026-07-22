# 日本語メモ一覧

英語版の研究メモは既存 file として残し、日本語版は `*_JA.md` として追加している。

## 対応表

| 英語版 | 日本語版 | 内容 |
|---|---|---|
| `PROJECT_KNOWLEDGE.md` | `PROJECT_KNOWLEDGE_JA.md` | 実装を持つローカル coding agent 向けの project knowledge |
| `COLLABORATOR_LLM_CONTEXT.md` | `COLLABORATOR_LLM_CONTEXT_JA.md` | 実装を持たない paper-writing LLM 向け context |
| `HANDOFF_PROMPT.md` | `HANDOFF_PROMPT_JA.md` | paper-writing LLM へ渡す初期 prompt |
| `DECISIONS.md` | `DECISIONS_JA.md` | 保持すべき設計判断 |
| `EXPERIMENTS.md` | `EXPERIMENTS_JA.md` | 実験 artifact、処理済み出力、評価 script |
| `FG_DUCS_DESIGN.md` | `FG_DUCS_DESIGN_JA.md` | FG-DUCS / FG-O-DUCS の設計・実装メモ |
| `SFG_DUCS_DESIGN.md` | `SFG_DUCS_DESIGN_JA.md` | Selective FG-DUCS / Selective FG-O-DUCS の設計・実装メモ |
| `FORMULA_DECOMPOSITION_NOTES.md` | `FORMULA_DECOMPOSITION_NOTES_JA.md` | transition requirement / safety formula decomposition の議論メモ |
| `RUNTIME_OPTIONS_AND_LTS_MODES.md` | `RUNTIME_OPTIONS_AND_LTS_MODES_JA.md` | jar 起動オプションと `.lts` mode flag の運用メモ |
| `STEPWISE_DUCS_IMPLEMENTATION_MEMO.md` | `STEPWISE_DUCS_IMPLEMENTATION_MEMO_JA.md` | 英語版は旧 mixed discussion memo。日本語版は非 delayed `stepwise` 現在実装メモ |
| - | `STEPWISE_DELAYED_DUCS_ALGORITHM_MEMO_JA.md` | Stepwise DUCS / `stepwise_delayed` アルゴリズム確認メモ |
| - | `STEPWISE_DELAYED_DUCS_IMPLEMENTATION_MEMO_JA.md` | `stepwise_delayed` 現在実装メモ |

## 運用方針

- ユーザーと議論した設計メモは、原則として日本語版にも反映する。
- 英語版は、英語圏の LLM や共同研究者に渡す用途のために残す。
- 片方だけ更新した場合は、できるだけ早くもう片方にも反映する。
