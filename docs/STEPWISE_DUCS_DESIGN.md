# Stepwise DUCS Initial Implementation Design Notes

Last updated: 2026-06-26

Japanese version: `STEPWISE_DUCS_DESIGN_JA.md`.

This note records the current design for Stepwise DUCS, a DUCS variant that borrows the staged synthesis idea from stepwise DCS to reduce the analysis space. The initial implementation deliberately supports only the simplest baseline: no cross goals and no incremental pruning. Traditional DUCS and O-DUCS should remain untouched; Stepwise DUCS should be implemented through separate classes and a separate synthesis path.

## Background

Traditional DUCS first composes all mapping environments into one large mapping environment. It then connects the specified old controller to that mapping environment to build `E_u`. It extracts fluents from old safety, new safety, and transition requirements, composes those fluents with `E_u`, and obtains `metaEnv`.

`metaEnv` is usually the largest intermediate state space. Traditional DUCS then prunes states that violate old safety, new safety, or transition requirements, composes the DontDoTwice constraints for `stopOldSpec` and `startNewSpec`, and obtains `safetyEnv`. Finally, GR(1) solves:

```text
hotSwapIn -> <> stopOldSpec
hotSwapIn -> <> reconfigure
hotSwapIn -> <> startNewSpec
```

The goal of Stepwise DUCS is to avoid building the whole mapping environment and global `metaEnv` up front. Instead, it builds smaller stage-local `E_u_i`, `metaEnv_i`, and `safetyEnv_i`, composes the local safety environments, applies global DontDoTwice once, and then solves one global GR(1) problem.

## Initial Scope

The first implementation should be intentionally limited.

- Cross old safety is not supported.
- Cross new safety is not supported.
- Cross transition requirements are not supported.
- Incremental pruning is not supported.
- `fault`, `assume`, and liveness goals are not supported.
- Local stages do not apply DontDoTwice.
- DontDoTwice is applied once globally after the product of local `safetyEnv_i`.

If any cross goal is detected, the initial implementation should fail with an explicit diagnostic.

## Syntax

Do not introduce a new top-level syntax. Reuse the existing `updatingController` syntax and add a `stepwise` flag.

```lts
updatingController UpdCont = {
    oldController = OldCon,
    oldEnvironment = {CHOCOLATE_OLD_1, CHOCOLATE_OLD_2},
    newEnvironment = {CHOCOLATE_NEW_1, CHOCOLATE_NEW_2},
    mapRelation = {R_CHOCOLATE_1, R_CHOCOLATE_2},
    oldGoal = DRILL_POLISH,
    newGoal = DRILL_PAINT,
    transition = T_NO_TP_1,
    transition = T_NO_TP_2,
    nonblocking,
    stepwise
}
```

When the `stepwise` flag is present, the definition uses the Stepwise DUCS path instead of the Traditional DUCS path. In the initial implementation, the specified `oldController = OldCon` is always ignored. Stepwise DUCS internally synthesizes a local old controller for each stage from the classified local old safety goals. The Output tab must report that the specified old controller was ignored.

The same index in `oldEnvironment`, `newEnvironment`, and `mapRelation` defines a stage.

```text
stage 1: CHOCOLATE_OLD_1, CHOCOLATE_NEW_1, R_CHOCOLATE_1
stage 2: CHOCOLATE_OLD_2, CHOCOLATE_NEW_2, R_CHOCOLATE_2
```

## Requirement Classification

Classify each safety property from `oldGoal`, `newGoal`, and each transition requirement independently. Users may write the requirements in the same style as Traditional DUCS, but each safety property should already be split into a unit that can be classified as either local or cross.

Classification procedure:

1. Collect the fluents referenced by the safety formula.
2. Expand each fluent into its initiating and terminating actions.
3. Normalize `.old` action names to their base actions, using the same convention as Traditional DUCS.
4. Exclude update events from stage ownership classification.
5. For the remaining ordinary actions, check which `MAP_E_i` alphabets contain them.

Classification rules:

| Condition | Classification |
|---|---|
| All ordinary actions belong only to one `MAP_E_i` | stage `i` |
| Actions in one goal span multiple stages | cross, error in initial implementation |
| One action appears in multiple `MAP_E_i` alphabets | cross, error in initial implementation |
| The goal contains only update events and no ordinary action | cross, error in initial implementation |
| An ordinary action appears in no `MAP_E_i` | error |

Diagnostics should distinguish cases such as:

- `GOAL_SPANS_MULTIPLE_STAGES`
- `GOAL_HAS_SHARED_ACTION`
- `GOAL_UPDATE_EVENTS_ONLY`
- `GOAL_ACTION_NOT_FOUND`

## Internal Local Old Controller Synthesis

In the initial Stepwise implementation, the `oldController` written in the `.lts` definition is not used. Instead, Stepwise DUCS synthesizes `C_OLD_i` internally for each stage.

The local controllable action set is:

```text
oldGoal.controllable intersect alphabet(E_OLD_i)
```

If a stage has local old safety goals:

```text
Controller_i = synthesize(E_OLD_i, localOldGoal_i)
OldCon_i = Controller_i || E_OLD_i
```

`E_u_i` must use `OldCon_i`, not the bare synthesized `Controller_i`. This matches the Traditional DUCS examples, where the synthesized controller is composed with the environment before being passed as the old controller. The synthesis result is a control strategy, while `Controller_i || E_OLD_i` represents the actual closed-loop old system.

If a neutral stage has no local old safety goals:

```text
OldCon_i = E_OLD_i
```

The stage still builds `E_u_i` by connecting `OldCon_i` with `MAP_E_i`.

## Stage-Local E_u_i

For each stage, build the mapping environment using the existing map machinery:

```text
MAP_E_i = map(E_OLD_i, E_NEW_i, R_i)
```

Then connect `OldCon_i` and `MAP_E_i` using the same idea as Traditional DUCS:

```text
E_u_i = UpdatingEnvironmentGenerator(OldCon_i, MAP_E_i)
```

`hotSwapIn` is introduced when `E_u_i` is generated. `stopOldSpec` and `startNewSpec` are added as self-loops on mapping-side states. `reconfigure` comes from the map relation and is already part of the mapping environment.

Even for a neutral stage with no classified local requirements, do not put raw `MAP_E_i` directly into the final product. Always build `E_u_i`. This preserves synchronization on update events across all stages.

## Local metaEnv_i and safetyEnv_i

For each stage, collect fluents from the classified local old safety, local new safety, and local transition requirements.

```text
metaEnv_i = E_u_i || localFluents_i
```

Prune violations with the same phase semantics as Traditional DUCS:

```text
old safety:
  !StopOldSpec && violation(old safety)

new safety:
  StartNewSpec && violation(new safety)

transition requirement:
  violation(transition requirement)
```

Transition requirements are evaluated without an additional phase guard.

The `.old` action handling must match Traditional DUCS. Before `hotSwapIn`, controllable actions are converted to `.old` actions. During fluent valuation, `.old` actions are treated as their base actions.

Do not apply DontDoTwice locally.

```text
safetyEnv_i = prune(metaEnv_i, local safety formulas)
```

## Product and Global DontDoTwice

Compose all local safety environments:

```text
productSafetyEnv = safetyEnv_1 || ... || safetyEnv_n
```

Then apply DontDoTwice globally once:

```text
globalSafetyEnv = DontDoTwice(productSafetyEnv)
```

The initial target set for DontDoTwice is the same as Traditional DUCS:

```text
stopOldSpec
startNewSpec
```

The reason for applying DontDoTwice only globally is to keep the initial baseline close to Traditional DUCS. Since `stopOldSpec` and `startNewSpec` are global synchronized events, one global DontDoTwice before GR(1) is enough to prevent duplicate execution in the final synthesis environment.

Local DontDoTwice may reduce states or transitions before product, but it also duplicates the same monitor in every stage. Treat it as a future evaluation option, not as part of the first implementation.

## GR(1)

Pass `globalSafetyEnv` to the existing GR(1) synthesis path. The update goal has the same meaning as Traditional DUCS:

```text
assumption:
  hotSwapIn

guarantees:
  stopOldSpec
  reconfigure
  startNewSpec
```

The update-event controllability also matches Traditional DUCS:

```text
controllable:
  stopOldSpec
  reconfigure
  startNewSpec

uncontrollable:
  hotSwapIn
```

## Proposed Implementation Classes

Keep the Traditional DUCS implementation stable. Add a separate Stepwise package and classes:

```text
ltsa.updatingControllers.stepwise.StepwiseUpdatingControllerSynthesizer
ltsa.updatingControllers.stepwise.StepwiseUpdatingControllerSafetySynthesizer
ltsa.updatingControllers.stepwise.StepwiseGoalClassifier
ltsa.updatingControllers.stepwise.StepwiseStage
ltsa.updatingControllers.stepwise.StepwiseSynthesisResult
ltsa.updatingControllers.stepwise.StepwiseMetricsLogger
```

At the existing updating-controller synthesis entry point, branch to Stepwise DUCS when the `stepwise` flag is present.

`StepwiseUpdatingControllerSafetySynthesizer` should be separate from `UpdatingControllerSafetySynthesizer`. It can reuse the same ideas, but it needs a safety-pruning method that does not apply DontDoTwice locally.

Required pieces:

```text
makeOldActionsUncontrollable equivalent
buildValuations equivalent
valuateSafety equivalent
applySafetyInEnvironment equivalent
```

The existing `UpdatingControllerSafetySynthesizer.synthesizeSafety` combines `.old` action conversion, valuation, pruning, and DontDoTwice. Using it directly for local stages would apply DontDoTwice too early, so the Stepwise path needs a separated implementation.

## Output Tab

Stepwise DUCS should log enough information to make classification and state-space changes visible.

```text
[Stepwise DUCS]
oldController is ignored in initial stepwise mode: OldCon

Requirement classification:
  P_OLD_TOOL_ORDER_1 -> stage 1
  P_OLD_TOOL_ORDER_2 -> stage 2
  ...

Stage 1:
  local old safety: ...
  local new safety: ...
  local transition: ...
  E_u states/transitions
  metaEnv states/transitions
  safetyEnv states/transitions

Stage 2:
  ...

Product:
  safetyEnv product states/transitions
  after global DontDoTwice states/transitions

GR(1):
  winning / losing
  output controller states/transitions
```

Failure diagnostics should be distinguishable:

- unsupported goal kind
- cross goal detected
- goal action not found
- stage old controller synthesis failed
- stage initial state pruned
- product composition failed
- global DontDoTwice failed
- GR(1) losing

## Initial Validation: Chocolate 2 Robots

The first validation target is:

```text
MODEL/StepwiseDUCS/ChocolateExample2Robots.lts
```

In this example, old safety and new safety are cleanly split between robot 1 and robot 2. Therefore, the internal local old controllers classified from `DRILL_POLISH` should produce `OldCon_1 || OldCon_2`, which is expected to be behaviorally equivalent to the existing `OldCon`. Exact state numbers or LTS shapes do not need to match.

The previous `UpdCont_1 || UpdCont_2` approach synthesized local GR(1) controllers first and then composed them, which produced behavior different from Traditional `UpdCont`. The new Stepwise baseline should compose local `safetyEnv_i` first and solve GR(1) only once. This avoids committing to local strategies before the global game is solved.

Validation should check:

- requirement classification is as expected
- local `C_OLD_i` can be internally synthesized
- `E_u_i`, `metaEnv_i`, and `safetyEnv_i` can be built
- product plus global DontDoTwice can be built
- GR(1) is winning
- the output controller satisfies the old/new safety and transition requirements

## Future Work: Incremental Pruning

Incremental pruning is a future optimization. Instead of composing all fluents with `E_u` at once, it repeatedly composes only the fluents needed by a requirement or a fluent-dependency cluster and prunes immediately.

```text
currentEnv
  || fluents required by requirement cluster
  -> metaEnv_k
  -> prune by requirement cluster
  -> safetyEnv_k
```

Example:

```text
E_u_1
  -> fluent_A + G_OLD_A
  -> prune
  -> safetyEnv_A
  -> fluent_B + G_OLD_B
  -> prune
  -> safetyEnv_B
  -> fluent_C + {G_NEW_A, G_NEW_B, T_1}
  -> prune
  -> safetyEnv_C
```

The benefit is that early pruning may reduce the base environment before later fluent products are built. The risk is that fluents composed in earlier steps leave state distinctions in later environments even if the later requirements do not use them. If pruning is weak, incremental pruning may add overhead. Safe minimization after pruning is a possible future topic.

If incremental pruning is implemented, `.old` conversion should still happen once at the beginning, and DontDoTwice should still happen once at the end. Do not repeatedly call the current all-in-one `synthesizeSafety` method.

The useful evaluation split is:

```text
Traditional DUCS:
  global all-at-once

Global incremental DUCS:
  incremental pruning on global E_u

Stepwise incremental DUCS:
  incremental pruning per stage + product
```

This separates the benefit of incremental pruning from the benefit of stage decomposition.

## Future Work: Cross Goals

A cross goal is a safety or transition requirement that spans multiple stages, or a requirement that uses an action appearing in multiple `MAP_E_i` alphabets.

Cross old safety, cross new safety, and cross transition requirements could be handled after local product:

```text
localProductSafetyEnv
  || crossFluents
  -> crossMetaEnv
  -> cross pruning
  -> globalSafetyEnv
```

In this design, the old-region part of `safetyEnv_all` corresponds to `C_OLD_1 || ... || C_OLD_n`. If the cross old-safety fluents are composed on top of that product and violation states are pruned, then the old region is constrained by `G_OLD_cross`. The important point is that this pruning is not the final controller: `safetyEnv_cross` is still passed to the final GR(1) solver. The GR(1) game then propagates losing states while respecting controllable and uncontrollable actions. Therefore, cross old safety may be handled inside Stepwise DUCS if cross pruning is followed by the final game solving step.

This is close to the structure of stepwise controller synthesis:

```text
C_OLD_i = synthesize(E_OLD_i, G_OLD_i)
C_OLD_all = C_OLD_1 || ... || C_OLD_n
constrain C_OLD_all with G_OLD_cross and let the final game propagate losing states
```

Under the assumptions of the stepwise controller-synthesis equivalence theorem, this is expected to match solving `G_OLD_1 && ... && G_OLD_cross` on the global old environment. The initial implementation still does not support this cross phase; it reports cross goals as errors.

### Scope-Based Staged Cross Pruning

Cross goals do not necessarily need to be processed all at once on `safetyEnv_all`. If each goal's stage dependency scope is known, Stepwise DUCS can product only the required stages and prune there.

```text
scope(G) = { i | a fluent action of G is related to MAP_E_i }
```

For example, if `G_12` uses only actions from stages 1 and 2:

```text
S12_base = safetyEnv_1 || safetyEnv_2
M12 = S12_base || fluents(G_12)
S12 = prune(M12, G_12)
```

If `G_23` then uses actions from stages 2 and 3, it is not necessary to product `{1,2,3}` from scratch. One possible staged order is:

```text
S123_base = S12 || safetyEnv_3
M23 = S123_base || fluents(G_23)
S123 = prune(M23, G_23)
```

This scope-based staged cross pruning adds only the stages needed by the next cross goal and prunes immediately. It may produce smaller intermediate spaces than composing a whole connected component up front.

The order matters for intermediate state size and runtime. Future work should discuss ordering strategies such as:

- `.lts` declaration order
- smallest scope first
- next goal with maximum overlap with the current component
- estimated smallest product size first

The initial implementation should not include this generalization. It should first build the no-cross baseline.

### Global Incremental Fallback

Another possible extension is to switch to a global incremental fallback when cross goals exist.

The discussed fallback is:

```text
if any cross goal exists:
  compose MAP_E_1 || ... || MAP_E_n
  build E_u from specified oldController and global mapping environment
  perform incremental pruning on global E_u
  apply global DontDoTwice
  solve GR(1)
```

This is not strictly stagewise reduction. It is a Traditional-DUCS-like global path with incremental pruning. It avoids the cross-old-safety problem but must be evaluated separately from true stage decomposition.

### Delayed E_u Connection / Fluent-Aware safetyEnv

Another research idea is to use the specified global old controller directly, avoid building `E_u_i` first, build `safetyEnv_i` and `safetyEnv_cross` only on the mapping side, and connect the old controller later.

The motivation is that the `oldController` specified in `UpdCont` may already have been synthesized from `E_OLD_all` and `G_OLD_all`. If so, this approach could avoid internal local old-controller synthesis and some cross-old-safety complications.

However, Traditional DUCS `E_u` is not just a product. It creates the following connection:

```text
old-controller state
  --hotSwapIn-->
old-side state of the mapping environment
```

Therefore, connecting a mapping-side `safetyEnv_cross` to the old controller later requires matching old-controller states with old-side mapping states. If fluents were already composed and pruned on the mapping side, the old-controller side must also be composed with the same fluents, and the connection should match both the old environment state and the fluent valuation.

Conceptually:

```text
1. Build safetyEnv_i / safetyEnv_cross from MAP_E_i
   Keep, for each state, the origin mapping state and fluent valuation.

2. Compose the specified oldController with the same fluent set.
   Keep the old environment state and fluent valuation on that side too.

3. Add hotSwapIn connections when:
     the old environment state matches
     the fluent valuation matches
```

This should not change the existing `MTS<Long, String>` type. A Stepwise-internal wrapper is preferable:

```text
StepwiseSafetyEnv:
  MTS<Long, String> env
  Set<Fluent> trackedFluents
  FluentStateValuation<Long> fluentValuation
  origin mapping state / source stage states
```

The existing GR(1) path can still receive only `env`, while delayed connection and valuation matching can use the wrapper metadata.

This delayed-connection approach is promising, but it is significantly heavier because it must reproduce the semantic role of `E_u` with fluent-aware state matching. The main path for now is to build `E_u_i` for each stage, build `safetyEnv_i`, product them, and then add the cross phase later.

## Future Work: Local DontDoTwice

The initial implementation applies DontDoTwice globally after the product.

A future experimental option can apply DontDoTwice locally:

```text
local:
  safetyEnv_i -> DontDoTwice -> localSafetyEnv_i

global:
  productSafetyEnv -> DontDoTwice -> globalSafetyEnv
```

Local DontDoTwice may reduce transitions or states before product by removing repeated `stopOldSpec` and `startNewSpec` behavior. It also duplicates the same monitor in every stage, which may introduce redundant history components. This is example-dependent and should be evaluated after the baseline works.

## Summary

The first implementation should build the baseline:

```text
no cross goals
no incremental pruning
global DontDoTwice once
local safetyEnv product
one global GR(1) solve
```

After the baseline is validated, extend and evaluate incremental pruning, cross-goal handling, and local DontDoTwice in separate steps.
