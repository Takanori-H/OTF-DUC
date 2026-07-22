# FG-DUCS / FG-O-DUCS Design And Implementation Memo

Last updated: 2026-06-09

This memo records the current design and implementation state for fine-grained and selective fine-grained update events in Traditional DUCS and O-DUCS.

## Modes

The `.lts` flag `fine_grained` selects fine-grained update events.
The `.lts` flag `selective_fine_grained` selects selective fine-grained update events.
The two flags are mutually exclusive.

| Definition flags | Mode | Intended target name |
|---|---|---|
| no `fine_grained`, no `on_the_fly` | Traditional DUCS | `UpdCont` |
| `fine_grained`, no `on_the_fly` | FG-DUCS | `UpdCont_FG` |
| `selective_fine_grained`, no `on_the_fly` | Selective FG-DUCS | `UpdCont_SFG` |
| no `fine_grained`, `on_the_fly` | O-DUCS | `UpdCont_OTF` |
| `fine_grained`, `on_the_fly` | FG-O-DUCS | `UpdCont_OTF_FG` |
| `selective_fine_grained`, `on_the_fly` | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

Fine-grained mode requires the list form:

- `oldEnvironment = {...}`
- `newEnvironment = {...}`
- `mapRelation = {...}`

The legacy `mapping = ...` form remains available for legacy DUCS/O-DUCS, but is not supported in fine-grained or selective fine-grained mode.

## Generated Update Actions

For each old safety property `P`, the implementation generates:

```text
stopOldSpec_P
```

For each new safety property `P`, the implementation generates:

```text
startNewSpec_P
```

For each mapping component, relation actions are handled as follows:

- `reconfigure` is normalized internally to `reconfigure_<mapping component name>`;
- explicit `reconfigure_*` is allowed;
- each mapping component has exactly one reconfigure action.

Generated names are public enough to be referenced by transition requirements.

In `fine_grained` transition requirements, the legacy names `stopOldSpec`, `reconfigure`, and `startNewSpec` are not accepted. If a requirement needs update events, it must use generated names such as `stopOldSpec_P`, `reconfigure_MAP`, or `startNewSpec_P`.

In `selective_fine_grained` transition requirements, each update kind may use either the legacy name or generated/group names. Mixing legacy and fine-grained names for the same kind across the transition requirements is an error. For example, `stopOldSpec` and `reconfigure_MAP` may be used together, but `stopOldSpec` and `stopOldSpec_P` may not.

Safety names used for generated action suffixes must match:

```text
[A-Za-z_][A-Za-z0-9_]*
```

Otherwise compilation fails with a diagnostic asking the user to rename the safety property.

The suffix `others` is reserved for selective grouping.

## Selective Fine-Grained Grouping

Selective mode first builds the same candidate update actions as full fine-grained mode. It then scans transition requirements and keeps only the referenced candidates as individual actions.

The scan includes:

- direct action references;
- action sets such as `{a, b}`;
- initiating and terminating actions of fluents referenced by transition requirements;
- nested assertions referenced by transition requirements.

For each update kind independently:

- if a legacy action is referenced, or if no individual action and no `*_others` action is referenced, that kind uses the legacy action name (`stopOldSpec`, `reconfigure`, or `startNewSpec`);
- referenced candidate actions remain individual;
- unreferenced candidates are grouped into `stopOldSpec_others`, `reconfigure_others`, or `startNewSpec_others`;
- directly referencing `*_others` forces that group to exist, and fails if the group would be empty.

For reconfiguration, each mapping component still has one candidate action. A relation action named `reconfigure` is first normalized to `reconfigure_<mapping component name>`. Selective grouping then relabels unreferenced mapping components to `reconfigure_others`, so all grouped mapping components synchronize on that action.

## FG-O-DUCS Semantics

FG-O-DUCS and Selective FG-O-DUCS use a single synthetic progress slot in the DCS box list rather than one marking component per fine-grained action.

Progress is represented by `ProgressRegistry`, which maps arbitrary-size `BigInteger` completion masks to compact synthetic state IDs. Therefore the implementation is not limited to 63 fine-grained update actions.

The update can finish only when all generated progress actions are complete:

- all `stopOldSpec_*`;
- all `reconfigure_*`;
- all `startNewSpec_*`.

Only then can `hotSwapOut` be considered, and the existing new-controller stitching guard must also succeed.

### Safety Activation

Old safety `P` is Active/Trace/Enforce until `stopOldSpec_P` completes.

New safety `P` is Active/Trace/Enforce only after `startNewSpec_P` completes.

When `startNewSpec_P` fires, the corresponding new safety LTS state is chosen by looking at the current state of the fluent LTSs extracted from `P`. The implementation builds:

- a per-new-safety list of fluent component indices;
- a lookup table from `[fluent state...]` to the corresponding new safety state.

In FG-O-DUCS, `startNewSpec_P` synchronizes only the new safety property associated with that specific start action. In selective mode, a grouped action such as `startNewSpec_others` synchronizes every new safety property assigned to that group.

The fluent LTSs used for this lookup are synthesis machines. They are not Active or Enforce, but they remain Trace-enabled throughout the OTF search. This is important because the same fluent may be needed later to initialize another new safety property.

## FG-DUCS Semantics

FG-DUCS and Selective FG-DUCS keep the Traditional DUCS strategy: they explicitly construct the update environment `E_u` and then solve the GR control problem.

Fine-grained `E_u` generation is implemented separately from the legacy `E_u` generator:

- `stopOldSpec_*` and `startNewSpec_*` are added as self-loops in update states;
- `reconfigure_*` remains in the generated mapping environment transitions;
- `hotSwapOut` is not added to Traditional DUCS.

The GR goal in FG-DUCS includes all generated progress actions as guarantees:

- every `stopOldSpec_*`;
- every `reconfigure_*`;
- every `startNewSpec_*`.

DontDoTwice is also applied to all generated progress actions, including `reconfigure_*` and any `*_others` groups.

Old/new safety wrapping is per safety:

- old safety `P` is enforced while `stopOldSpec_P` has not occurred;
- new safety `P` is enforced after `startNewSpec_P` has occurred.

## Transition Requirements

Transition requirements may reference generated fine-grained action names.

Traditional DUCS formula conversion was adjusted so safety-style transition requirements such as:

```fsp
ltl_property T = []((StopOldSpecFG && !StartNewSpecFG) -> !{a, b, c})
```

can be handled by:

- stripping the leading `[]` before converting to the synthesis formula representation;
- expanding action sets like `{a, b, c}` into a fluent with multiple initiating actions.

## Exploration Heuristic

The O-DUCS frontier ranks states by:

```text
marking depth -> newest state (LIFO) -> heuristic score
```

The heuristic score strongly prioritizes update actions. If scores are tied, uncontrollable actions are explored before ordinary controllable actions.

In other words, the effective local action order is:

```text
update actions -> uncontrollable actions -> ordinary controllable actions
```

The following FG-O-DUCS action priority costs are used for update actions:

| Action kind | Cost |
|---|---:|
| `hotSwapOut` | 0 |
| `stopOldSpec_*` | 10 |
| `reconfigure_*` | 20 |
| `startNewSpec_*` | 30 |
| `hotSwapIn` | 40 |
| other actions | 100 |

When multiple fine-grained actions have the same cost, existing ordering behavior is used.

Selective FG-O-DUCS uses the same broad order, but prioritizes individually referenced update actions before grouped `*_others` update actions:

| Action kind | Cost |
|---|---:|
| `hotSwapOut` | 0 |
| individual `stopOldSpec_*` | 10 |
| individual `reconfigure_*` | 20 |
| individual `startNewSpec_*` | 30 |
| `stopOldSpec_others` | 40 |
| `reconfigure_others` | 50 |
| `startNewSpec_others` | 60 |
| `hotSwapIn` | 70 |
| other actions | 100 |

## Main Implementation Files

- `ltsa.updatingControllers.structures.UpdateProtocolSpec`
  - generates and classifies fine-grained progress actions.
- `ltsa.updatingControllers.structures.SelectiveUpdateProtocolSpecBuilder`
  - groups candidate fine-grained progress actions according to transition-requirement references.
- `ltsa.lts.MappingEnvironmentGenerator`
  - normalizes legacy `reconfigure` to `reconfigure_<mapping component name>` in fine-grained mode.
- `ltsa.lts.UpdatingControllersDefinition`
  - creates `UpdateProtocolSpec`;
  - injects fine-grained controllable actions;
  - builds New Safety fluent lookup data for OTF modes.
- `ltsa.updatingControllers.structures.UpdatingControllerCompositeState`
  - carries `fineGrained` and `UpdateProtocolSpec` for both Traditional and OTF paths.
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingEnvironmentGenerator`
  - builds Traditional FG-DUCS `E_u`.
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingControllersUtils`
  - builds Traditional FG-DUCS GR and safety goal definitions.
- `ltsa.updatingControllers.synthesis.FineGrainedUpdatingControllerSafetySynthesizer`
  - delegates Traditional safety pruning while passing all progress actions to DontDoTwice.
- `ltsa.updatingControllers.synthesis.UpdatingControllerSynthesizer`
  - selects legacy vs fine-grained Traditional path;
  - selects legacy vs fine-grained OTF DCS engine;
  - translates New Safety fluent lookup maps to box-list indices.
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisFineGrainedDUC`
  - fine-grained OTF-DUC semantics;
  - progress-slot transitions;
  - per-safety `startNewSpec_*` synchronization;
  - fine-grained action heuristic costs.
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisSelectiveFineGrainedDUC`
  - selective FG-O-DUCS exploration heuristic costs;
  - reuses the FG-O-DUCS progress-slot semantics.
- `ltsa.lts.CompactStateActionRelabeler`
  - relabels mapping component actions after selective grouping.
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.ProgressRegistry`
  - maps BigInteger progress masks to synthetic progress states.
- `ltsa.updatingControllers.synthesis.UpdatePhaseEvaluator`
  - aggregates `stopOldSpec_*`, `reconfigure_*`, and `startNewSpec_*` into legacy update-event categories for evaluation logs.

## Example Naming Convention

The example files should use:

```text
UpdCont         // Traditional DUCS
UpdCont_FG      // FG-DUCS
UpdCont_SFG     // Selective FG-DUCS
UpdCont_OTF     // O-DUCS
UpdCont_OTF_FG  // FG-O-DUCS
UpdCont_OTF_SFG // Selective FG-O-DUCS
```

Currently updated examples:

- `Experiment/Example/FineGrainedSmall.lts`
- `Experiment/Example/PowerPlant_FG.lts`

## Current Verification

As of 2026-06-09:

- `mvn -DskipTests=true compile` succeeds.
- `Experiment/Example/FineGrainedSmall.lts` was checked with all four targets:
  - `UpdCont`
  - `UpdCont_FG`
  - `UpdCont_OTF`
  - `UpdCont_OTF_FG`
- `Experiment/Example/PowerPlant_FG.lts` was checked with all four targets:
  - `UpdCont`
  - `UpdCont_FG`
  - `UpdCont_OTF`
  - `UpdCont_OTF_FG`

The implementation should be considered complete enough to start evaluation runs. Larger benchmark regression and log-processing validation should still be done before treating the results as final paper data.

Selective FG-DUCS / FG-O-DUCS implementation was added on 2026-06-09. `Experiment/Example/FineGrainedSmall.lts` and `Experiment/Example/PowerPlant_FG.lts` now include `UpdCont_SFG` and `UpdCont_OTF_SFG` targets. Example synthesis for those targets was intentionally left to the user in this work session.
