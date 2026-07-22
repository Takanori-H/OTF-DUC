# Runtime Options And LTS Mode Memo

Last updated: 2026-06-09

This memo records the Java system properties used when launching the MTSA jar and the `.lts` flags used to choose DUCS/O-DUCS update modes.

## Passing Options To The Jar

The options below are Java system properties.  Put them before `-jar`:

```bash
java -Dotfduc.debug=true -Dotfduc.debug.file=duc_debug.txt -jar <mtsa-jar> <args>
```

If a property is omitted, the default value shown below is used.

Batch experiments are selected by YAML, not by a Java system property.  Use the root key `runs: N` to repeat the entire YAML `cases:` list N times.  With `outputDir: Experiment/result`, repeated runs are written under `Experiment/run_01/result/...`, `Experiment/run_02/result/...`, etc.  If `runs` is omitted, the legacy output layout is preserved.

## OTF-DUC Search And Output Options

These options affect O-DUCS, FG-O-DUCS, and Selective FG-O-DUCS.

| Property | Default | Meaning |
|---|---:|---|
| `otfduc.debug` | `false` | Enables detailed OTF-DUC search/debug logging. |
| `otfduc.profile` | `false` | Writes timing/profile information for the OTF-DUC search and output construction. |
| `otfduc.debug.file` | `duc_debug.txt` | File used by `otfduc.debug` and `otfduc.profile`. |
| `otfduc.debug.mergeProof` | `true` | When debug logging is enabled, includes proof details for pre-update output merging. |
| `otfduc.fairness` | `true` | Enables the marking-state-8 fairness fixed point before `hotSwapOut`. |
| `otfduc.disableFairness` | `false` | If `true`, disables the marking-state-8 fairness fixed point regardless of `otfduc.fairness`. |
| `otfduc.simple.merge` | `false` | Enables the experimental simple merge of pre-update output states. |
| `otfduc.belief.repair` | `false` | Enables belief repair after output merging. |
| `otfduc.nondet.belief.repair` | `true` | Enables repair of nondeterministic action branches during output construction. |

Use either of the following when the generated controller should satisfy ordinary all-traces `hotSwapIn -> <> hotSwapOut` without the marking-state-8 fairness assumption:

```bash
java -Dotfduc.fairness=false -jar <mtsa-jar> <args>
java -Dotfduc.disableFairness=true -jar <mtsa-jar> <args>
```

With the default fairness mode, a safe marking-state-8 SCC may be accepted if fair executions can reach `hotSwapOut`.  Without fairness, `hotSwapOut` reachability is treated as an ordinary all-traces reachability requirement.  This stricter mode can fail examples that the default mode accepts.

## Belief Repair Limits

These advanced limits are used by belief repair and nondeterministic belief repair.

| Property | Default |
|---|---:|
| `otfduc.belief.maxStates` | `5000` |
| `otfduc.belief.maxTimeMs` | `5000` |
| `otfduc.belief.maxNodes.min` | `64` |
| `otfduc.belief.maxNodes.perNewControllerState` | `2` |
| `otfduc.belief.maxNodes.perRawPreState` | `8` |
| `otfduc.belief.maxNodes.perReachableMapValuation` | `4` |
| `otfduc.belief.maxAdditionalConcrete.min` | `128` |
| `otfduc.belief.maxAdditionalConcrete.perNewControllerState` | `4` |
| `otfduc.belief.maxAdditionalConcrete.perRawPreState` | `16` |
| `otfduc.belief.maxAdditionalTransitions.min` | `256` |
| `otfduc.belief.maxAdditionalTransitions.perNewControllerState` | `8` |
| `otfduc.belief.maxAdditionalTransitions.perRawPreState` | `32` |

## Traditional DUCS Debug Options

These options affect the Traditional DUCS path, including FG-DUCS and Selective FG-DUCS.

| Property | Default | Meaning |
|---|---:|---|
| `traditionalduc.debug` | `false` | Enables Traditional DUCS debug output. |
| `duc.traditional.debug` | `false` | Alias for Traditional DUCS debug output. |

## Evaluation And Heartbeat Options

| Property | Default | Meaning |
|---|---:|---|
| `mtsa.evaluation.enabled` | `true` | Enables update-controller evaluation recording. |
| `updating.controller.evaluation.enabled` | `true` | Legacy alias used if `mtsa.evaluation.enabled` is not set. |
| `updating.controller.evaluation.printDetailedReport` | `false` | Prints the detailed evaluation report in addition to summary/data metrics. |
| `updating.controller.evaluation.profile` | `full` | Use `compact` or `lean` to skip some full evaluation details. |
| `duc.heartbeat` | `false` | Enables a heartbeat log for long-running experiments. |
| `duc.heartbeat.intervalSec` | `600` | Heartbeat interval in seconds. |
| `duc.heartbeat.file` | `duc_heartbeat.log` | Heartbeat output file. |
| `duc.heartbeat.append` | `false` | Appends to the heartbeat file instead of overwriting it. |

## LTS Mode Flags

Update mode is selected inside each `updatingController` definition.  It is not selected by a jar option.

| `.lts` flags in `updatingController` | Mode | Example target name |
|---|---|---|
| no `on_the_fly`, no `fine_grained`, no `selective_fine_grained` | Traditional DUCS | `UpdCont` |
| `on_the_fly` | O-DUCS | `UpdCont_OTF` |
| `fine_grained` | FG-DUCS | `UpdCont_FG` |
| `on_the_fly`, `fine_grained` | FG-O-DUCS | `UpdCont_OTF_FG` |
| `selective_fine_grained` | Selective FG-DUCS | `UpdCont_SFG` |
| `on_the_fly`, `selective_fine_grained` | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

`fine_grained` and `selective_fine_grained` are mutually exclusive.  Using both in the same `updatingController` definition is a compile-time error.

Example:

```fsp
updatingController UpdCont_OTF_SFG = {
    oldController = OldSpec,
    oldEnvironment = {OLD_ENV_1, OLD_ENV_2},
    newEnvironment = {NEW_ENV_1, NEW_ENV_2},
    mapRelation = {R_ENV_1_FG, R_ENV_2_FG},
    oldGoal = OldSpec,
    newGoal = NewSpec,
    transition = T_SELECTIVE,
    newController = NewSpec,
    on_the_fly,
    selective_fine_grained
}
```

## Mapping Input By Mode

Legacy DUCS and legacy O-DUCS may use either an existing mapping environment via:

```fsp
mapping = MAP_ENV
```

or the list form:

```fsp
oldEnvironment = {...}
newEnvironment = {...}
mapRelation = {...}
```

FG-DUCS, FG-O-DUCS, Selective FG-DUCS, and Selective FG-O-DUCS require the list form.  In these modes, `mapping = ...` is rejected.

In legacy mode, each relation must use the action `reconfigure`.  In FG/SFG modes, each mapping component still has exactly one reconfiguration action.  If a relation uses `reconfigure`, it is normalized internally to `reconfigure_<mapping component name>`.  A relation may also explicitly use a valid `reconfigure_*` action.  The name `reconfigure_others` is reserved for selective grouping and must not be written in a relation definition.

## Update Action Names In Transition Requirements

In legacy DUCS/O-DUCS transition requirements, use the legacy update actions:

```text
stopOldSpec
reconfigure
startNewSpec
```

In FG transition requirements, do not use those legacy names.  Use generated names:

```text
stopOldSpec_<old safety name>
reconfigure_<mapping component name>
startNewSpec_<new safety name>
```

Selective mode may use either a legacy action or generated/grouped actions for each update kind.  Mixing legacy and fine-grained names for the same kind across the transition requirements is an error.  For example, `stopOldSpec` and `reconfigure_MAINTENANCE` may be used together, but `stopOldSpec` and `stopOldSpec_P` may not.

Selective mode may also expose group actions:

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

If selective mode has no individual reference and no explicit `*_others` reference for one update kind, that kind collapses internally to the legacy action name.

Traditional DUCS now strips a leading `[]` from safety-style transition requirements during formula conversion.  This keeps the `.lts` writing style closer between Traditional DUCS and O-DUCS.

## Constant Names

The implementation constants are:

```java
UpdateConstants.BEGIN_UPDATE = "hotSwapIn";
UpdateConstants.FINISH_UPDATE = "hotSwapOut";
```

`FINISH_UPDATE` is only the Java constant name.  Design notes and paper text should use the event name `hotSwapOut`.
