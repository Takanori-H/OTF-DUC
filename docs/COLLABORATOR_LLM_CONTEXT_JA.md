# OTF-DUC 共同研究者LLM向け context

英語版は `COLLABORATOR_LLM_CONTEXT.md` に残している。

この document は、codebase を持たない共同研究者の LLM に渡すための paper-writing memory pack である。研究内容を理解し、paper draft を手伝い、良い質問を出し、実装を誤って説明しないために使う。

## 役割分担

- 実装担当者: MTSA 実装、実験、log、code-level validation を管理する。
- 共同研究者: paper を書き、構成する。
- 共同研究者の LLM: writing、構成、related work、説明、claim、実装担当者への質問を手伝う。実装を inspect / modify できるとは仮定しない。

## Project の一段落要約

この project は OTF-DUC、すなわち Dynamic Update Controller Synthesis (DUCS) の On-the-Fly 手法を提案する。Traditional DUCS は、old controller から new controller への移行中の可能な振る舞いを表す update environment `E_u` を先に構築し、その上で update controller を合成する。この明示的構築は、controller state、environment state、safety property、transition requirement、update-event interleaving を組み合わせるため bottleneck になり得る。OTF-DUC は DUCS の update semantics を保ちながら、`E_u` の full upfront construction を避け、Directed Controller Synthesis (DCS) によって old controller から new controller へ安全に移るために必要な update bridge だけを探索する。

## Paper で中心に置く claim

中心 claim は次のようにする。

> OTF-DUC は、safe dynamic controller update に関する traditional DUCS の見方を保ちながら、full update environment の明示的構築を、関連する update bridge 上の on-the-fly search に置き換える。

OTF-DUC が問題定義を変えていると主張してはいけない。同じ dynamic update problem family に対する scalable synthesis procedure として framing するのがよい。

## Background: Traditional DUCS

Dynamic Update Controller Synthesis は、discrete-event system における controller の runtime replacement を扱う。update は、old specification を満たす Old Controller (OC) から、new specification を満たす New Controller (NC) へ移行する。

Traditional DUCS が扱うもの:

- old controller。
- new controller。
- old / new environment。
- environment mapping または reconfiguration relation。
- old / new safety requirement。
- update 中の transition requirement。
- update completion への progress。

重要な correctness intuition:

- old specification を停止するまでは old safety を守る。
- update 中は transition requirement を守る。
- new specification を開始した後は new safety を守る。
- system は最終的に update を完了し、new controller へ control を渡す。

この project での key limitation は、game が存在した後に final control problem を解くことだけではない。bottleneck は中間 update environment `E_u` 自体を構築することである。

## Background: DCS

Directed Controller Synthesis (DCS) は OTF-DUC の技術的基盤である。DCS は state space 全体を先に compose せず、search 中に composed state を必要に応じて展開する。heuristic で有望な state を優先し、controllable / uncontrollable choice を AND/OR 風に扱える。

paper では、DCS は full `E_u` construction を on-the-fly exploration に置き換える enabling mechanism である。

## OTF-DUC の idea

OTF-DUC は update bridge に注目する。

- old controller が現在動作している可能性のある state から開始する。
- update event と environment behavior を通る安全な path を探索する。
- `stopOldSpec` 後に old safety の enforce を止める。
- `startNewSpec` で new safety の enforce を開始する。
- environment mapping が許すときに `reconfigure` を許可する。
- current update state が new controller に安全に接続できる場合にだけ finish する。

New Controller はすでに合成済みであり、new specification に対して valid であると仮定する。OTF-DUC は update search の一部として full New Controller behavior を探索しない。candidate handoff state が safe な New Controller state に接続できるかだけを知ればよい。

## Update event

主要な update event:

- `hotSwapIn`: update が開始し、control が old controller から update controller へ移る。
- `stopOldSpec`: old safety specification が enforce されなくなる。
- `reconfigure`: mapping relation に従って environment を old-environment state から new-environment state 側へ変換する。
- `startNewSpec`: new safety specification の enforce を開始する。
- `hotSwapOut`: update controller を New Controller へ接続する OTF-DUC 固有の handoff event。

writing では次に注意する。

- `stopOldSpec`, `reconfigure`, `startNewSpec` は update 開始後に起きるべき traditional progress event である。
- `hotSwapOut` は OTF-DUC の handoff / completion event である。OTF-DUC が control を New Controller に安全に移せると証明した点として説明する。

## Update phase model

現在の実装は10状態の update-phase model を使う。

| Phase | 意味 |
|---:|---|
| 0 | `hotSwapIn` 前 |
| 1 | `hotSwapIn` 後、主要3 update event 前 |
| 2 | `stopOldSpec` だけが起きた |
| 3 | `reconfigure` だけが起きた |
| 4 | `startNewSpec` だけが起きた |
| 5 | `stopOldSpec` と `reconfigure` が起きた |
| 6 | `stopOldSpec` と `startNewSpec` が起きた |
| 7 | `reconfigure` と `startNewSpec` が起きた |
| 8 | 主要3 update event がすべて起きた |
| 9 | `hotSwapOut` が起きた |

validation note で使う bitmask convention:

- `1 = stopOldSpec`
- `2 = reconfigure`
- `4 = startNewSpec`

state 9 が OTF-DUC exploration の goal state である。

## Safety activation logic

OTF-DUC は New Safety の delayed activation を使う。

`startNewSpec` 前は New Safety property を enforce しない。ただし、fluent-derived monitor machine が関連 behavior を background で trace する。`startNewSpec` が起きたとき、実装は monitor/fluent state を使って New Safety property の正しい current state を決定し、その state から New Safety の enforce を開始する。

これは paper にとって重要である。OTF-DUC が new specification を早すぎる時点で enforce せず、update が new specification 開始を宣言した後は正しく enforce できる理由を説明するためである。

## hotSwapOut Guard

OTF-DUC は主要3 update event が起きただけでは `hotSwapOut` を許可しない。

`hotSwapOut` は次の場合にだけ許可される。

- current mapping-environment state が New Environment state に変換できる。
- その New Environment state と current New Safety state の pair が New Controller 内の valid state に対応する。

これは handoff safety check である。paper では、OTF-DUC は New Controller に接続できると分かっている state でだけ update を完了する、と説明する。

## 実装が概念的に貢献している点

code access がなくても、次の実装由来の点は project knowledge として使える。

- OTF-DUC は marking/update-phase component、old controller、mapping environment component、old safety property、new safety property、transition requirement、fluent-derived monitor machine を含む DCS input list を構築する。
- New Controller はこの on-the-fly search list には含まれない。
- pre-update behavior と update/environment behavior を区別するため、old controller の action は内部で rename される。
- search 中、component は action enablement、state tracing、safety enforcement について別々の役割を持つ。
- ある phase で trace する必要がない component は normalize でき、irrelevant difference による search space 膨張を避ける。
- search 後、output update controller は `hotSwapOut` edge を通して New Controller に接続される。

algorithm description に使えるが、paper で exact name、line number、low-level implementation detail が必要な場合は実装担当者に確認する。

## Evaluation plan と artifact

project には Traditional DUC と OTF-DUC の experiment artifact がある。実装担当者は次の directory を持っている。

- old controller。
- new controller。
- LTS benchmark input。
- OTF-DUC log / output。
- Traditional DUC log / output。
- processed CSV / XLSX result。
- log compacting と OTF-DUC output validation 用 script。

確認されている benchmark family:

- Surveillance
- ProductionCell
- Workflow
- GSM
- Industry
- MetaSocket
- RailCab
- PowerPlant

重要な metric category:

- state count。
- transition count。
- runtime。
- memory checkpoint。
- update-event transition count。
- update phase distribution。
- `hotSwapIn` から completion までの distance。
- phase ごとの enabled update event。
- `hotSwapOut` guard block。
- New Controller connection success / miss count。

結果を捏造しない。numerical claim を書く前に、現在の processed workbook または CSV を実装担当者に確認する。

## Validation note

project には、OTF-DUC output controller を traditional DUC の GR(1) progress intention に対して check する validation script がある。

check する idea:

- reachable なすべての `hotSwapIn` の後、`stopOldSpec`, `reconfigure`, `startNewSpec` のいずれかが未完了のまま cycle に永久に留まれてはいけない。

この validation は `hotSwapOut` を別扱いにする。これは conceptual distinction と一致する。`hotSwapOut` は OTF handoff condition であり、traditional progress obligation は主要3 update event に関するものである。

paper で validation result を使う前に、validation CSV / Markdown が最新かつ complete であるかを実装担当者に確認する。

## Suggested paper structure

考えられる構成:

1. Introduction
   - high-availability system には safe runtime controller update が必要。
   - Traditional DUCS は formal safety/progress framing を与える。
   - `E_u` の明示的構築が scalability を制限する。
   - OTF-DUC は full construction を on-the-fly bridge search に置き換える。

2. Background
   - LTS / controller synthesis basics。
   - Traditional DUCS。
   - DCS と on-the-fly composition。

3. Problem and Motivation
   - OC から NC への dynamic update。
   - update event と safety requirement。
   - `E_u` construction bottleneck。

4. OTF-DUC Method
   - update bridge search。
   - phase / marking model。
   - delayed New Safety activation。
   - `hotSwapOut` guard。
   - New Controller への接続。

5. Correctness Argument
   - `stopOldSpec` まで old safety を enforce する。
   - update 中 transition requirement を enforce する。
   - `startNewSpec` 後 new safety を enforce する。
   - `hotSwapOut` は NC に接続可能な state からのみ許可する。
   - explored strategy のもとで update event へ progress する。

6. Evaluation
   - benchmark。
   - Traditional DUC vs OTF-DUC。
   - state-space、time、memory、phase、completion metric。

7. Related Work
   - Dynamic Update Controller Synthesis。
   - Directed Controller Synthesis。
   - dynamic software updating。
   - self-adaptive systems。
   - safe runtime reconfiguration と staged update。

8. Conclusion
   - OTF-DUC は formal update semantics を保ちながら upfront state-space construction を削減する。

## 安全に書ける claim

定性的 project claim として安全なもの:

- OTF-DUC は full traditional update environment construction の state-space cost に動機づけられている。
- OTF-DUC は update environment 全体を事前構築するのではなく、update path を on the fly に探索する。
- OTF-DUC は DUCS の概念的 event `hotSwapIn`, `stopOldSpec`, `reconfigure`, `startNewSpec` を保つ。
- OTF-DUC は New Controller への guarded handoff event として `hotSwapOut` を追加する。
- OTF-DUC は `startNewSpec` まで New Safety enforcement を遅らせる。
- intended evaluation は OTF-DUC と Traditional DUC を benchmark example 上で state-space と runtime-related metric によって比較する。

## 確認が必要な claim

次の claim は書く前に実装担当者へ確認する。

- exact percentage や order-of-magnitude improvement。
- exact benchmark success / failure。
- exact memory usage。
- processed result がすべて current implementation から生成されたものか。
- repair や merge など、特定の OTF-DUC variant が final proposed method の一部か。
- `hotSwapOut` が final validation statement に必要か、それとも handoff condition としてのみ扱うか。
- theoretical proof obligation が完全に確立されているか、informal argument の段階か。

## Useful wording

良い framing:

> OTF-DUC does not redefine dynamic controller update; it changes how the update game is explored.

> The method treats the update as a bridge synthesis problem between an already valid old controller and an already valid new controller.

> The new controller is represented at the handoff boundary rather than unfolded throughout the update search.

> The `hotSwapOut` guard prevents the update controller from handing off control from an intermediate state that cannot be interpreted as a safe New Controller state.

避けるべき wording:

- OTF-DUC が New Controller の正しさを scratch から証明するという表現。
- OTF-DUC がすべての state explosion を消すという表現。
- `hotSwapOut` が traditional GR(1) progress guarantee と同一であるという表現。
- 実装担当者が確認していないのに、すべての実験結果が final であるという表現。

## 実装担当者への質問

paper draft 時に確認すること:

- method の final name は OTF-DUC, On-the-Fly DUC, または別 variant か。
- paper で評価する final OTF-DUC variant はどれか。
- latest result の source of truth はどの workbook / CSV か。
- Traditional DUC と OTF-DUC は完全に同じ benchmark input で実行されているか。
- benchmark failure は expected、timeout-related、implementation limitation のどれか。
- exact proof claim は theorem、proposition、soundness argument、implementation validation のどれか。
- 含めるべき figure は update event phase graph、box-list architecture、handoff guard、evaluation pipeline のどれか。

## Project 内の reference context

local project には次に関する reference material がある。

- Dynamic Update of Discrete Event Controllers。
- Directed Controller Synthesis of discrete event systems。
- On-the-fly informed search for non-blocking directed controllers。
- OTF-DUC paper draft note。
- experiment output と processed workbook。

paper に exact bibliographic detail が必要な場合は、BibTeX または citation list を実装担当者に依頼する。
