# Stepwise DUCS Implementation and Discussion Memo

Last updated: 2026-06-27

Japanese version: `STEPWISE_DUCS_IMPLEMENTATION_MEMO_JA.md`.

This memo records implementation decisions, discussion outcomes, verification observations, and future design notes for Stepwise DUCS. It is a handoff memo, not a formal specification. If it disagrees with the implementation, prefer the code and update this memo.

## CLI Run Notes

For Stepwise / `stepwise_delayed` example checks, running `SingleCompositionRunner` from the built jar is usually more reproducible than using the GUI.

Build:

```bash
cd /Users/takanori/Waseda\ University/research/git/as_of_20251210_mtsa/mtsa/maven-root/mtsa
mvn -q -DskipTests install
```

Notes:

- `mvn install` writes to the local Maven repository (`~/.m2`). Sandboxed agents may need escalation for this command.
- The jar is normally at `~/.m2/repository/mtsa/mtsa/1.0-SNAPSHOT/mtsa-1.0-SNAPSHOT.jar`.
- Run Java with `-Djava.awt.headless=true`. Without it, diagnostics / output code may try to initialize AWT and hang or fail in a headless environment.
- `.lts`-only changes do not require rebuilding. Java code changes require `mvn install` before rerunning the jar.

Example:

```bash
java -Djava.awt.headless=true \
  -cp /Users/takanori/.m2/repository/mtsa/mtsa/1.0-SNAPSHOT/mtsa-1.0-SNAPSHOT.jar \
  ltsa.updatingControllers.cli.SingleCompositionRunner \
  --lts /Users/takanori/Waseda\ University/research/git/as_of_20251210_mtsa/mtsa/maven-root/mtsa/MODEL/StepwiseDUCS/SimpleExampleSharedAction.lts \
  --target STEPWISE_DELAYED_UPDATE_CONTROLLER \
  --output /private/tmp/simple_shared_delayed_output.txt \
  --transitions /private/tmp/simple_shared_delayed_transitions.txt
```

Minimized output:

```bash
java -Djava.awt.headless=true \
  -cp /Users/takanori/.m2/repository/mtsa/mtsa/1.0-SNAPSHOT/mtsa-1.0-SNAPSHOT.jar \
  ltsa.updatingControllers.cli.SingleCompositionRunner \
  --lts /Users/takanori/Waseda\ University/research/git/as_of_20251210_mtsa/mtsa/maven-root/mtsa/MODEL/StepwiseDUCS/SimpleExampleSharedAction.lts \
  --target STEPWISE_DELAYED_UPDATE_CONTROLLER \
  --output /private/tmp/simple_shared_delayed_min_output.txt \
  --transitions /private/tmp/simple_shared_delayed_min_transitions.txt \
  --minimize true
```

Use `--target UPDATE_CONTROLLER` for Traditional and `--target STEPWISE_DELAYED_UPDATE_CONTROLLER` for delayed.

Extra note:

- On some macOS setups, system `python3` may fail due to CommandLineTools / `xcrun` issues. If available, use the Codex bundled Python for transition-output aggregation scripts.

## Current Stepwise Position

The current `stepwise` mode is implemented as a separate path from Traditional DUCS.

It builds stage-local updating environments, prunes local safety, handles cross requirements by scope-based staged cross pruning, applies global DontDoTwice once after the final product, and then invokes the existing GR(1) update-controller synthesis path.

The current implementation should be treated as a research baseline:

- It does not guarantee an identical controller to Traditional DUCS.
- It may return a more conservative update policy than Traditional DUCS.
- Observed policy differences are currently understood as permissiveness differences, not as known violations of Dynamic Update Controller requirements.
- Existing `stepwise` behavior should not be changed when adding future variants.

## Main Implemented Path

`stepwise` requires list-form stage definitions:

```lts
oldEnvironment = {E_OLD_1, E_OLD_2}
newEnvironment = {E_NEW_1, E_NEW_2}
mapRelation = {R_1, R_2}
stepwise
```

The three lists must have the same length. The same index defines one stage.

Important mode decisions:

- `stepwise` cannot be combined with `on_the_fly`, `fine_grained`, or `selective_fine_grained`.
- `mapping = ...` is not supported in `stepwise`.
- `oldController` is not pre-composed in the current `stepwise` path.
- The specified `oldController` name is kept for logging, but current `stepwise` uses internal stage-local old-controller synthesis.
- Existing Traditional DUCS behavior is preserved.

## Requirement Classification

Stepwise classification expands formula fluents into actions and maps those actions to stage alphabets.

Classification rules:

- A requirement that refers to one stage is local.
- A requirement that refers to multiple stages is cross.
- A requirement that refers only to update events is treated as all-stage scope.
- `.old` actions are normalized to their base actions for classification.
- `hotSwapIn`, `stopOldSpec`, `reconfigure`, `startNewSpec`, and fine-grained update actions are excluded from normal stage detection.
- Unsupported goal kinds such as assumptions, faults, Buchi/liveness requirements, action-not-found cases, and shared-action cases are explicit errors.

A cross requirement must retain its full `stageScope`. The classifier must not stop after seeing the first two stages, because a requirement may actually depend on stages 1, 2, and 3.

## Scope-Based Staged Cross Pruning

Cross requirements are grouped by overlapping stage scopes. Each connected component is pruned only over the stages it needs.

Example:

```text
cross G_12 -> scope {1, 2}
cross G_23 -> scope {2, 3}
component -> scope {1, 2, 3}
```

Key decisions:

- A cross requirement over stages 1 and 2 should not involve stage 3.
- Overlapping scopes are merged into connected components.
- All-stage scope is handled by global cross pruning before the final global DontDoTwice.
- Out-of-scope actions are represented with passive self-loops to avoid accidental blocking during the final product.
- Final integration uses owner-aware product logic.
- DontDoTwice is applied only once globally, after all local and cross products.

## Cross Old Safety

Cross old safety cannot be handled only by post-update pruning. It must also restrict the pre-update old-controller-equivalent behavior.

Current `stepwise` behavior:

1. Compose the old environments in the relevant scope.
2. Synthesize a scoped cross old controller.
3. Use controllable actions from `oldGoal.controllable` intersected with the scoped old alphabet.
4. Compose the scoped restriction with the component safety environment.
5. Then perform cross safety pruning.

This is not the same as using the global Traditional `OldCon`, but it makes the scoped pre-update phase respect cross old safety.

## Policy Difference from Traditional DUCS

A simple 3-stage example showed that current `stepwise` can be more conservative than Traditional DUCS.

Observed case:

```text
Traditional-only trace:
  old_1 -> hotSwapIn -> reconfigure
```

When scoped stages 1 and 2 allowed busy / mid-execution `reconfigure`, Traditional DUCS allowed some immediate reconfiguration traces that current Stepwise did not. When those scoped stages allowed reconfiguration only from idle/done states, minimized Traditional and Stepwise outputs matched.

Current interpretation:

- The difference is caused by scoped busy / mid-execution reconfiguration.
- It is not caused only by out-of-scope stage 3.
- It is not caused only by the presence or absence of local old safety.
- The difference is treated as a policy/permissiveness difference, not as a known correctness violation.

## Variant: stepwise_delayed

To investigate and possibly reduce the policy difference, a separate variant named `stepwise_delayed` was added.

This variant must not change existing `stepwise` behavior. It should be implemented as a separate keyword, class path, and package-level design.

Syntax and mode decisions:

- The keyword is `stepwise_delayed`.
- `stepwise` and `stepwise_delayed` are mutually exclusive.
- `stepwise_delayed` uses only list-form `oldEnvironment`, `newEnvironment`, and `mapRelation`.
- `mapping = ...` is not allowed.
- It cannot be combined with `on_the_fly`, `fine_grained`, or `selective_fine_grained`.
- `oldController` is required. Missing `oldController` is an explicit error.
- The `.lts` `oldController = OldCon` is used under the same premise as Traditional DUCS.
- `OldCon` is assumed to include all old safety requirements.
- There is no fallback to internal old-controller synthesis when `oldController` is absent.

### Design Intuition

The current `stepwise` path builds `E_u_i` per stage before composing stages. The delayed variant instead considers pruning the mapping side first and connecting the global old controller later, just before GR(1) synthesis.

Traditional `E_u` contains an old-controller region and mapping region, connected by `hotSwapIn`:

```text
old-controller state --hotSwapIn--> mapping old-side state
```

`stepwise_delayed` should reproduce this connection later using origin metadata and fluent valuations.

### Old-Controller Side

The delayed variant uses the specified `oldController`.

Old-controller side decisions:

- Build requirement fluent valuations on the old-controller side for connection matching.
- `OldCon` is assumed to be the closed-loop controller that already includes all old safety requirements, so `stepwise_delayed` should not prune OldCon by old safety.
- Do not add `stopOldSpec` / `startNewSpec` self-loops to the old-controller side. Traditional DUCS adds these self-loops only to the mapping region reached after `hotSwapIn`.
- Transition requirements are unguarded, but `stepwise_delayed` should not use them to prune OldCon. If needed, handle this later as a separate diagnostic or correctness checker.
- `beginFluent` is excluded from hotSwapIn connection valuation matching.
- `beginFluent` is treated mainly as an internal phase marker used by Traditional safety pruning for `.old` relabeling.
- Connection-matching valuations are built over base actions. `.old` is not part of semantic matching.
- If valuation is ever computed over a relabeled MTS, normalize `.old` actions to their base actions as Traditional DUCS does.

### Mapping Side

For each stage:

```text
MAP_E_i = map(E_OLD_i, E_NEW_i, R_i)
```

Mapping-side pruning should follow Traditional safety semantics:

```text
old safety:
  !StopOldSpec && violation

new safety:
  StartNewSpec && violation

transition requirement:
  violation
```

The public `MTS<Long, String>` type should not change. Metadata should be carried by an internal wrapper only for `stepwise_delayed`.

Before mapping-side pruning, add `stopOldSpec` / `startNewSpec` self-loops to the mapping side. This reproduces the self-loops that Traditional `UpdatingEnvironmentGenerator` adds only in the mapping region.

Mapping old-side detection should not rely on a state-id condition such as `state < oldSize`, because reachable-state renumbering can change ids. The delayed variant should preserve mapping-generation metadata. Keep at least:

- `state -> stageIndex`;
- `state -> side` (`OLD_SIDE`, `NEW_SIDE`, or `INTERMEDIATE`);
- `state -> oldEnvState`.

Possible wrapper:

```text
DelayedStepwiseEnv:
  MTS<Long, String> env
  Set<Fluent> trackedFluents
  FluentStateValuation<Long> fluentValuation
  state -> origin mapping state / stage state metadata
```

The wrapper must preserve origin and valuation metadata through pruning. Minimization should not be performed before delayed connection.

### hotSwapIn Connection

The delayed connection is many-to-many.

Connect an old-controller state to a mapping old-side state when:

- the base-action lockstep relation, analogous to Traditional `UpdatingEnvironmentGenerator`, relates the old-controller state to the mapping old-side state; and
- the selected fluent valuation matches.

Connection matching decisions:

- Targets are limited to mapping old-side states that remain after safety pruning.
- Compare requirement-derived fluents.
- Include `stopOldSpec`, `reconfigure`, and `startNewSpec` phase-related fluents when they are tracked.
- Exclude `beginFluent`.
- The target immediately after `hotSwapIn` must have update-phase valuation `stopOldSpec=false`, `reconfigure=false`, and `startNewSpec=false`.
- Do not choose only one target if multiple mapping states match.
- Use reachable old-controller states for fatal diagnostics.

If a reachable old-controller state has no `hotSwapIn` target, that should be an explicit error. Missing targets for unreachable states can be logged but should not be fatal.

### Traditional Update-Phase Fluents

Traditional DUCS builds fluent valuations over the entire `E_u`. Since `E_u` includes the old-controller region, fluents passed to safety pruning are valued on old-controller states too.

Important distinction:

- `stopFluent` and `startFluent` are introduced by old/new safety guards.
- Transition requirements use the fluents referenced by their formulas.
- `beginFluent` is mainly used to detect the pre-`hotSwapIn` phase for `.old` relabeling.
- Therefore `beginFluent` should not be used as semantic equality for delayed connection.

### Safety Pruning and No-Controller Results

Safety pruning itself does not decide "there is no controller." It removes violation states and transitions.

No-controller results normally arise later:

- in final GR(1) synthesis; or
- in old-controller / scoped cross-old-controller synthesis.

The `stepwise_delayed` implementation does not prune OldCon. Old-controller states and valuations are used for connection diagnostics.

However, `stepwise_delayed` should add explicit diagnostics before GR(1):

- old-controller side states/transitions;
- mapping-side states/transitions before and after pruning;
- number of reachable old-controller states;
- number of all old-controller states;
- number of `hotSwapIn` connections;
- number of reachable states without a connection target;
- number of all states without a connection target;
- product size, after-DontDoTwice size, and GR result.

Implementation scope:

- Local requirements are pruned per stage on the mapping side.
- Cross old safety, cross new safety, and cross transition requirements are handled by connected-component mapping-side product, cross fluent product, and pruning.
- OldCon is still not pruned. Cross old safety relies on the premise that global `OldCon` already includes all old safety; delayed handles the mapping region and connection consistency.
- Preserve origin metadata through pruning.
- After product, origin metadata should be a stage tuple, e.g. `state -> Map<stageIndex, originMappingState>`.
- Do not minimize before delayed connection.
- If the same action name appears in multiple stage alphabets, it is treated as an LTSA/MTSA synchronizing action. Classification stores an owner set for that action and unions it into the requirement scope.

Shared-action example notes:

- `maven-root/mtsa/MODEL/StepwiseDUCS/SimpleExampleSharedAction.lts` is a small example where stages 1 and 2 share the same action names `shared` / `sharedDone`.
- Its purpose is to verify that shared actions are classified with owner sets such as `shared=[0,1]` and `sharedDone=[0,1]`, so a requirement mentioning them becomes cross scope `[1,2]`.
- The expected classifier log classifies `P_OLD_SHARED_SYNC_REFERENCE` as `cross scope [1, 2]`.
- Do not use an over-constraining safety property for this classification check. For example, plain `!SharedSeen` forbids the shared action itself and can lead to no-controller in both Traditional and delayed synthesis.
- Tautological properties are also a poor classifier test. Compiler / formula rewriting may remove the fluent support, so the classifier cannot observe the intended action scope.
- In this example, `shared` / `sharedDone` are included in the stage 1 and 2 old/new controllable sets. Making `sharedDone` uncontrollable made this small test no-controller and distracted from the shared-action scope check.
- After `mvn install`, headless CLI runs showed Traditional and `stepwise_delayed` agree before and after minimization.
- Observed values:
  - before minimization: both Traditional and `stepwise_delayed` have 314 states and 1420 expanded transitions;
  - after minimization: both have 56 states. LTSA text has 212 displayed arcs, and 241 transitions after expanding grouped labels such as `{shared, sharedDone}`;
  - grouped output `{shared, sharedDone} -> Qx` is shorthand for separate `shared -> Qx` and `sharedDone -> Qx` transitions;
  - trace equivalence is true both before and after minimization.

Implementation note:

- The `stepwise_delayed` path is implemented in `stepwise/delayed/StepwiseDelayedUpdatingControllerSynthesizer.java`.
- Incremental pruning is a separate optional mode described below. It should not change the baseline `stepwise_delayed` behavior.

### `incrementalPruning` / `incrementalPruningCleanup`

Incremental pruning is enabled by additional keywords so that the baseline `stepwise_delayed` result remains directly comparable.

In `.lts` files these are mode keywords, not `= true` assignments:

```text
stepwise_delayed
incrementalPruning
incrementalPruningCleanup
```

Meaning:

- If a keyword is present, the flag is true. If it is absent, the flag is false.
- Defaults are `incrementalPruning=false` and `incrementalPruningCleanup=false`.
- `incrementalPruning` is only valid together with `stepwise_delayed`; otherwise it is an explicit error.
- `incrementalPruningCleanup` is an additional mode of `incrementalPruning`; it is an explicit error without `incrementalPruning`.

Comparison modes:

- `stepwise_delayed`: baseline local plus connected-component cross pruning.
- `stepwise_delayed` + `incrementalPruning`: prune one requirement at a time, without per-step unreachable cleanup.
- `stepwise_delayed` + `incrementalPruning` + `incrementalPruningCleanup`: prune one requirement at a time and run unreachable cleanup after each pruning step.

Scope:

- Do not incrementally prune OldCon. `OldCon` remains the old controller assumed to include all old safety requirements, and is used for reachable-state / valuation diagnostics.
- Incremental pruning applies only to the mapping side.
- Both local and cross requirements are pruned one goal at a time. Requirements with the same scope are not grouped; this keeps local pruning incremental too.

Ordering:

1. Process local requirements first.
2. Process cross requirements after local pruning.
3. Requirement type order is `OLD_SAFETY`, `NEW_SAFETY`, `TRANSITION`.
4. Within the same type, sort by goal name.
5. If multiple cross components are processed, sort components deterministically by their stage set.

Product and pruning policy:

- For local goals, start from each stage's `MAP_E_i` and prune goals one by one.
- For cross goals, add stages to the current product until the current goal's scope is covered, then prune that goal.
- If the current product scope already covers the next goal's scope, reuse the product.
- A requirement may be pruned only when its scope is fully contained in the currently composed stage set.
- Shared actions contribute their full owner set to the requirement scope, so they are covered by the same soundness condition.

Cleanup:

- Without `incrementalPruningCleanup`, do not run additional unreachable cleanup after each goal pruning step.
- With `incrementalPruningCleanup`, run unreachable cleanup after each goal pruning step.
- This flag controls only the extra cleanup between incremental steps. It must not change standard reachable-state handling or later baseline processing.

Logging:

- mode flags: `stepwise_delayed`, `incrementalPruning`, `incrementalPruningCleanup`, `safetyBackwardPruning`;
- incremental step number;
- local / cross;
- stage scope;
- requirement type and goal name;
- stages added by cross product, if any;
- states/transitions before pruning;
- states/transitions after pruning;
- states/transitions after cleanup when cleanup is enabled.

Permissiveness:

- Incremental pruning may return a different controller from baseline `stepwise_delayed`.
- Such a difference should be treated as a pruning-policy / permissiveness difference, not immediately as an update-controller requirement violation.

### `safetyBackwardPruning`

`safetyBackwardPruning` is an experimental keyword that performs safety-only backward pruning on safety environments. It may change permissiveness, so the default is false and `.lts` files use keyword syntax rather than `= true`.

Scope:

- Valid only for Traditional DUCS and `stepwise_delayed`.
- Not used for OTF, fine-grained, selective fine-grained, or the existing non-delayed `stepwise` mode.
- In `stepwise_delayed`, do not directly prune OldCon. Prune only the mapping side and the final safety environment.
- Independent of `incrementalPruning`.

Insertion points:

- Traditional DUCS: after existing safety pruning and DontDoTwice, immediately before GR(1).
- Baseline `stepwise_delayed`: after local safety pruning, after cross safety pruning, and after the final global DontDoTwice.
- `stepwise_delayed + incrementalPruning`: after each goal's direct safety pruning.
- Cleanup order is direct safety pruning, backward pruning, then reachable cleanup.

Predecessor rule:

- Group REQUIRED transitions by `(state, action)`. Multiple successors for the same action are treated as one nondeterministic action group.
- An uncontrollable action group is adversarial. If any successor is losing, the source state is losing.
- A controllable action group is unsafe if any successor is losing; remove the whole action group.
- A state becomes losing only when it has no uncontrollable action and all controllable action groups are losing / removed.
- Preserve the original MTS action alphabet after pruning.

No controller:

- If the initial state becomes losing, synthesis has lost at that point.
- The implementation logs this and returns a single-initial-state dead-end MTS so the later GR(1) path can report no controller through the existing runner behavior.

Logging:

- Include `safetyBackwardPruning` in mode flags.
- Log pruning scope, before states/transitions, dead-end seed states, backward losing states, losing controllable action groups, after backward states/transitions, and after cleanup states/transitions.

2026-06-28 smoke:

- A temporary `/private/tmp/SimpleExampleSharedAction_safetyBackwardSmoke.lts` added Traditional, `stepwise_delayed`, and `stepwise_delayed + incrementalPruning` targets with `safetyBackwardPruning`.
- Parser, mode flags, pruning logs, and GR(1) synthesis all completed.
- All three targets produced 314 states / 1420 transitions. Exact transition-language comparison was not completed in this smoke check; policy / permissiveness comparisons should use the one-thread setting.

2026-06-29 example experiments:

- Ran the Example suite excluding Production Cell arm=2 for Traditional, Traditional+SBP, `stepwise_delayed`, `stepwise_delayed+SBP`, `stepwise_delayed+incrementalPruning+SBP`, and `stepwise_delayed+incrementalPruning+incrementalPruningCleanup+SBP`.
- Trace-equivalence / permissiveness checks used a one-thread GR solver. Performance measurements used the default multi-threaded setting.
- Except for Railcab, no accepted-trace difference was found between SBP / no-SBP or between incremental / cleanup variants.
- All SBP-enabled modes, namely Traditional+SBP, `stepwise_delayed+SBP`, `incrementalPruning+SBP`, and `incrementalPruning+cleanup+SBP`, were trace-equivalent both before and after minimization, including Railcab.
- In Railcab, SBP made the controller more permissive than the no-SBP controller. It accepted `hotSwapIn stopOldSpec endOfTS reconfigure startNewSpec`, which no-SBP Traditional rejected.
- This is not treated as a safety violation by itself. The interpretation is that SBP removes losing / dead-end branches from the safety environment before GR(1), reducing conservatism in subset construction / game solving and allowing a more permissive winning policy.
- Therefore SBP is not a language-preserving optimization. Treat it as an experimental variant that changes the safety-backward-pruning policy and may change permissiveness.

SBP plus incremental-pruning performance trend:

- Comparing baseline `stepwise_delayed` with `stepwise_delayed+SBP`, synthesis time and peak memory were almost unchanged. Across 8 examples, synthesis time improved in 4, worsened in 3, and was effectively unchanged in 1; the average improvement was only about 1.4%. Peak memory improved in 3, worsened in 3, and was effectively unchanged in 2, so SBP is not a stable memory-reduction technique.
- `stepwise_delayed+SBP` reduced peak states in 4/8 examples and peak transitions in 2/8 examples, with no observed worsening in peak states/transitions. Thus SBP is better understood as an option that slightly cleans up the safety game before GR(1), not as a strong performance optimization.
- In Railcab, SBP accepted a trace rejected by no-SBP and therefore increased permissiveness. Its main value may be avoiding over-conservatism caused by losing / dead-end branches in models with nondeterministic mapping, rather than reducing synthesis time or memory.
- For now, keep SBP as a keyword-enabled variant rather than making it default, and evaluate performance, permissiveness, and correctness separately.
- Compared with Traditional+SBP, `incrementalPruning+SBP` reduced synthesis time in all 8 examples, by about 34% on average. Peak memory decreased in 7/8 examples, and peak states/transitions decreased in all 8.
- Compared with Traditional+SBP, `incrementalPruning+cleanup+SBP` also reduced synthesis time in all 8 examples, but peak memory increased in 4/8 examples and increased on average.
- Compared with `stepwise_delayed+SBP`, the incremental variants substantially reduced intermediate peak states/transitions, but often increased synthesis time and peak memory.
- In this run, cleanup did not further reduce peak states/transitions relative to incremental without cleanup, and it tended to worsen time and memory.

Temporary outputs:

- strict counts: `/private/tmp/mtsa_sbp_examples/results/incremental_sbp_strict_counts_official.csv`
- performance: `/private/tmp/mtsa_sbp_examples/results/incremental_sbp_performance_default_threads.csv`
- trace comparison: `/private/tmp/mtsa_sbp_examples/results/incremental_sbp_trace_equivalence_expanded.csv`

### Railcab Nondeterminism and Comparison Policy

Railcab contains nondeterministic `reconfigure` transitions on the mapping side:

- `ENDOFTS_OLD = (... | reconfigure -> ENDOFTS_NEW | reconfigure -> AC_NEW)`;
- two `R_MILESTONES` entries from `ENDOFTS@MILESTONES_OLD` with the same `reconfigure` action.

This nondeterminism can make the final synthesized controller vary across runs under the default multi-threaded GR(1) solver. This is not specific to `stepwise_delayed`: the same effect was observed for Traditional DUCS. The GR safety environment and the subset-construction MTS were stable; the variation first appeared after GR ranking / strategy extraction / strategy-to-controller conversion.

Observed Railcab behavior:

- With the default multi-threaded setting, Traditional DUCS itself produced multiple final controllers across repeated runs, and some of them were not trace-equivalent.
- With `ComputerOptions.allowedThreads=1`, Traditional, `stepwise_delayed`, `incrementalPruning`, and `incrementalPruningCleanup` were stable across repeated runs.
- Under the one-thread setting, the final controllers were trace-equivalent across these modes.

One-thread Railcab controller sizes:

| mode | controller states | controller transitions | trace |
|---|---:|---:|---|
| Traditional | 443 | 796 | baseline |
| `stepwise_delayed` | 444 | 798 | trace-equivalent to Traditional |
| `stepwise_delayed + incrementalPruning` | 444 | 798 | trace-equivalent to Traditional |
| `stepwise_delayed + incrementalPruning + incrementalPruningCleanup` | 444 | 798 | trace-equivalent to Traditional |

Thus the `stepwise_delayed` `+1` state / `+2` transitions in this run are a redundant representation difference, not an observed language difference. No trace accepted by delayed but rejected by Traditional was found under the one-thread comparison.

One-thread Railcab values with SBP:

| mode | raw states | raw transitions | minimized states | minimized transitions | trace |
|---|---:|---:|---:|---:|---|
| Traditional | 443 | 796 | 155 | 321 | no-SBP baseline |
| Traditional + SBP | 443 | 800 | 157 | 328 | more permissive than no-SBP Traditional |
| `stepwise_delayed` | 444 | 798 | 155 | 321 | trace-equivalent to Traditional |
| `stepwise_delayed + SBP` | 444 | 802 | 157 | 328 | trace-equivalent to Traditional+SBP |
| `stepwise_delayed + incrementalPruning + SBP` | 444 | 802 | 157 | 328 | trace-equivalent to Traditional+SBP |
| `stepwise_delayed + incrementalPruning + cleanup + SBP` | 444 | 802 | 157 | 328 | trace-equivalent to Traditional+SBP |

With SBP, Railcab gains +4 raw transitions and +2 minimized states / +7 minimized transitions. This is an example where pruning does not monotonically reduce the final controller size. SBP prunes the safety environment, but the final GR(1) controller can become larger because the resulting game admits a more permissive policy.

Interpretation:

- Direct safety pruning alone leaves safety-violating branches as dead-end / losing states in the safety environment.
- Railcab has nondeterministic mapping-side `reconfigure` transitions, so knowledge states after subset construction can include dead-end / losing successors.
- Without SBP, those losing branches make the game more conservative and remove the immediate `startNewSpec` after the prefix `hotSwapIn stopOldSpec endOfTS reconfigure`.
- SBP removes backward-losing branches before GR(1), so the knowledge state after the same prefix is smaller and immediate `startNewSpec` is allowed.
- In Railcab, transition requirements R1 / R4 are commented out, so this additional trace is not considered a safety-requirement violation in the checked configuration.

Experiment policy:

- Use a one-thread GR solver for strict trace-equivalence / permissiveness comparisons.
- Use the default multi-threaded setting for performance evaluation such as synthesis time, peak memory, and peak state/transition counts.
- State this distinction explicitly in experiment notes and papers.

Suggested wording:

```text
Trace-equivalence and permissiveness comparisons were performed with the GR solver fixed to one thread to avoid nondeterministic strategy selection caused by parallel rank updates. Performance measurements were performed under the default multi-threaded configuration.
```

Temporary diagnostic outputs:

- default-thread Railcab stage comparison: `/private/tmp/railcab_stage_diff_20260628_133816/`
- one-thread Railcab stage comparison: `/private/tmp/railcab_stage_diff_20260628_134415/`
- default-thread Traditional 10-run stability: `/private/tmp/mode_stage_stability_Railcab_traditional_default_UpdCont_20260628_141730/`
- one-thread Traditional 10-run stability: `/private/tmp/mode_stage_stability_Railcab_traditional_1thread_UpdCont_20260628_141832/`

## Dynamic Update Controller Requirements

From the dynamic update controller paper, the implementation's `hotSwapIn` corresponds to the paper's `hotSwap`.

Discussion summary:

1. `C` should be hot-swappable to `C'` at any point.
2. Old goal `G` holds until `stopOldSpec`.
3. Transition requirement `T` holds.
4. New goal `G'` holds after `startNewSpec`.
5. After `hotSwap`, `stopOldSpec`, `reconfigure`, and `startNewSpec` eventually occur.

Formula-level summary:

```text
G W stopOldSpec
T
[](startNewSpec -> []G')
[](hotSwap -> (<>stopOldSpec && <>reconfigure && <>startNewSpec))
```

Current transition-text checks found no missing `hotSwapIn` from pre-update reachable states, no reachable final deadlocks, and no closed completion traps in the tested examples. A complete checker for all requirements is still future work.

## Not Implemented Yet

Not yet implemented:

- local DontDoTwice option;
- automatic checker for the Dynamic Update Controller requirements;
- automatic trace-inclusion / permissiveness comparison against Traditional DUCS.

## Current Conclusion

The existing `stepwise` implementation is an initial research baseline that supports local stages, cross old/new/transition requirements, scope-based staged cross pruning, one global DontDoTwice pass, and GR(1) synthesis.

The `stepwise_delayed` path is now separate from existing `stepwise` and supports local plus connected-component cross pruning on the mapping side before delayed OldCon connection.
