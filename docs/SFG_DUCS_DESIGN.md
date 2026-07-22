# Selective Fine-Grained DUCS / O-DUCS Memo

Last updated: 2026-06-09

This memo records the current design and implementation state for Selective Fine-Grained DUCS and Selective Fine-Grained O-DUCS.

## Mode

The `.lts` flag `selective_fine_grained` selects selective fine-grained update events.

| Definition flags | Mode | Intended target name |
|---|---|---|
| `selective_fine_grained`, no `on_the_fly` | Selective FG-DUCS | `UpdCont_SFG` |
| `selective_fine_grained`, `on_the_fly` | Selective FG-O-DUCS | `UpdCont_OTF_SFG` |

`fine_grained` and `selective_fine_grained` are mutually exclusive.

Selective mode uses the same list-style mapping input as full FG mode:

- `oldEnvironment = {...}`
- `newEnvironment = {...}`
- `mapRelation = {...}`

The legacy `mapping = ...` form is not supported in `selective_fine_grained` mode.

## Candidate Actions

Selective mode first creates the same candidate update actions as full FG mode.

For each old safety `P`:

```text
stopOldSpec_P
```

For each new safety `P`:

```text
startNewSpec_P
```

For each mapping component:

- explicit relation action `reconfigure_X` is used as-is;
- relation action `reconfigure` is normalized to `reconfigure_<mapping component name>`;
- each mapping component may have only one reconfigure action.

The suffix `others` is reserved. A safety named `others` is rejected, and relation action `reconfigure_others` is rejected in `.lts` relation definitions.

## Reference Scan

After candidate action generation, selective mode scans transition requirements and decides which candidate actions must stay individual.

The scan includes:

- direct action references;
- action sets such as `{a, b}`;
- initiating and terminating actions of fluents referenced by transition requirements;
- nested assertion references.

In `fine_grained` transition requirements, legacy update action names are forbidden:

```text
stopOldSpec
reconfigure
startNewSpec
```

FG transition requirements must use generated names such as:

```text
stopOldSpec_P
reconfigure_MAP
startNewSpec_P
```

In selective mode, transition requirements may use either a legacy name or generated/group names for each update kind independently.  Mixing legacy and fine-grained names for the same kind is an error, even if the names appear in different transition requirements.  For example, `stopOldSpec` and `reconfigure_MAINTENANCE` may be used together, but `stopOldSpec` and `stopOldSpec_P` may not.

In selective mode, these group names are also allowed:

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

Unknown generated names cause a fatal diagnostic so spelling mistakes do not silently change the grouping.

## Grouping Semantics

Grouping is decided independently for stop, reconfigure, and start actions.

If a kind has a legacy reference, or has no individual reference and no explicit `*_others` reference, the kind uses the legacy name:

```text
stopOldSpec
reconfigure
startNewSpec
```

If at least one candidate of a kind is referenced, referenced candidates stay individual and all unreferenced candidates are grouped:

```text
stopOldSpec_others
reconfigure_others
startNewSpec_others
```

If `*_others` is referenced directly, the group is forced to exist. If there is nothing to put in the group, compilation fails.

For `reconfigure_others`, all unreferenced mapping components synchronize on that one action. Referenced mapping components keep their individual reconfigure actions.

## Traditional SFG-DUCS

Selective FG-DUCS still materializes `E_u`, like Traditional DUCS and full FG-DUCS.

The generated progress actions are the only progress actions used by the Traditional fine-grained path:

- the generated stop actions;
- the generated reconfigure actions;
- the generated start actions;
- any generated `*_others` groups.

The GR guarantee set contains all generated progress actions. DontDoTwice is also applied to all generated progress actions, including grouped actions.

Traditional SFG-DUCS does not add `hotSwapOut`.

## Selective FG-O-DUCS

Selective FG-O-DUCS reuses the FG-O-DUCS progress-slot semantics.

The DCS box list still has a single synthetic progress component. `ProgressRegistry` maps completion masks to compact synthetic state IDs, so selective mode is not limited to 63 progress actions.

`hotSwapOut` is enabled only when every generated progress action is complete and the existing new-controller connection guard succeeds.

Grouped actions update every member assigned to the group:

- `stopOldSpec_others` stops all old safety components assigned to that group;
- `reconfigure_others` moves all grouped mapping components to their new-environment side;
- `startNewSpec_others` starts all new safety components assigned to that group.

For grouped start actions, each new safety component still uses its own fluent-state lookup to choose the correct initial safety state. Fluent LTSs continue tracing throughout the update because later new safety components may still need them.

## Exploration Heuristic

Selective FG-O-DUCS prioritizes individually referenced update actions before grouped `*_others` actions.

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

Actions with the same cost use the existing ordering behavior.

## Main Implementation Files

- `ltsa.updatingControllers.structures.UpdateProtocolSpec`
  - stores generated progress actions and their kind.
- `ltsa.updatingControllers.structures.SelectiveUpdateProtocolSpecBuilder`
  - builds the selective protocol from full FG candidates and transition-requirement references.
- `ltsa.lts.UpdatingControllersDefinition`
  - parses `selective_fine_grained`;
  - rejects incompatible flags;
  - scans transition requirements;
  - validates generated action references;
  - relabels selective mapping components.
- `ltsa.lts.CompactStateActionRelabeler`
  - relabels mapping component actions such as `reconfigure_ENV` to `reconfigure_others`.
- `MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisSelectiveFineGrainedDUC`
  - implements the selective FG-O-DUCS heuristic and debug group logging.
- `ltsa.updatingControllers.synthesis.UpdatingControllerSynthesizer`
  - selects the selective DCS class when `UpdateProtocolSpec.isSelective()` is true.

## Current Non-Goals

Formula decomposition is not implemented as part of selective mode.

If a model needs decomposed transition requirements for performance, write those requirements explicitly in the `.lts` file for now.

Old/new safety decomposition is also not part of SFG. In FG/SFG modes, safety names define generated update-action names, so automatic safety decomposition would change protocol granularity unless an additional monitor-only design is added.
