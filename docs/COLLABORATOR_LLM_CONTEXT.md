# OTF-DUC Collaborator LLM Context

Generated on 2026-05-21 from the implementer's local project files.

This document is intended for a collaborator's LLM that does not have the codebase.  It is a paper-writing memory pack: use it to understand the research, help draft the paper, ask good questions, and avoid misrepresenting the implementation.

## Role Split

- Implementer: owns the MTSA implementation, experiments, logs, and code-level validation.
- Collaborator: writes and shapes the paper.
- Collaborator's LLM: should help with writing, structure, related work, explanations, claims, and questions for the implementer.  It should not assume it can inspect or modify the implementation.

## Project in One Paragraph

This project proposes OTF-DUC, an On-the-Fly approach to Dynamic Update Controller Synthesis (DUCS).  Traditional DUCS synthesizes an update controller by first constructing an update environment `E_u` that represents possible behaviors during the transition from an old controller to a new controller.  That explicit construction can become the bottleneck because it combines controller states, environment states, safety properties, transition requirements, and update-event interleavings.  OTF-DUC keeps the DUCS update semantics but avoids full upfront construction of `E_u`; it uses Directed Controller Synthesis (DCS) to explore only the update bridge needed to move safely from the old controller to the new controller.

## Intended Paper Claim

The central claim should be:

> OTF-DUC preserves the traditional DUCS view of safe dynamic controller update while replacing explicit construction of the full update environment with on-the-fly search over the relevant update bridge.

The paper should not claim that OTF-DUC changes the problem definition.  It is better framed as a scalable synthesis procedure for the same family of dynamic update problems.

## Background: Traditional DUCS

Dynamic Update Controller Synthesis addresses runtime replacement of a controller in a discrete-event system.  The update should move from an Old Controller (OC), which satisfies the old specification, to a New Controller (NC), which satisfies the new specification.

Traditional DUCS handles:

- the old controller;
- the new controller;
- old and new environments;
- an environment mapping or reconfiguration relation;
- old and new safety requirements;
- transition requirements during the update;
- progress toward completing the update.

The important correctness intuition:

- before the old specification is stopped, old safety must be respected;
- transition requirements must be respected during the update;
- after the new specification starts, new safety must be respected;
- the system must eventually complete the update and hand over control to the new controller.

The key limitation for this project is not necessarily solving the final control problem after the game exists.  The bottleneck is constructing the intermediate update environment `E_u` itself.

## Background: DCS

Directed Controller Synthesis (DCS) is used as the technical basis for OTF-DUC.  Instead of composing the whole state space first, DCS expands composed states on demand during search.  It can prioritize promising states with heuristics and handle controllable/uncontrollable choices in an AND/OR style.

For the paper, DCS is the enabling mechanism for replacing full `E_u` construction with on-the-fly exploration.

## OTF-DUC Idea

OTF-DUC focuses on the update bridge:

- Start from states where the old controller may currently be running.
- Search for a safe path through update events and environment behavior.
- Stop enforcing old safety after `stopOldSpec`.
- Start enforcing new safety at `startNewSpec`.
- Allow `reconfigure` when the environment mapping supports it.
- Finish only when the current update state can safely connect to the new controller.

The New Controller is assumed to already be synthesized and valid for the new specification.  OTF-DUC does not explore the full New Controller behavior as part of the update search.  It only needs to know whether a candidate handoff state can connect to some safe New Controller state.

## Update Events

The key update events are:

- `hotSwapIn`: the update starts; control moves from the old controller to the update controller.
- `stopOldSpec`: the old safety specification is no longer enforced.
- `reconfigure`: the environment is translated from old-environment state toward new-environment state according to a mapping relation.
- `startNewSpec`: the new safety specification begins to be enforced.
- `hotSwapOut`: OTF-DUC-specific handoff event that connects the update controller to the New Controller.

For writing, be careful:

- `stopOldSpec`, `reconfigure`, and `startNewSpec` are the traditional progress events that must occur after update begins.
- `hotSwapOut` is an OTF-DUC handoff/completion event.  It should be described as the point where OTF-DUC proves that control can safely move into the New Controller.

## Update Phase Model

The current implementation uses a 10-state update-phase model:

| Phase | Meaning |
|---:|---|
| 0 | before `hotSwapIn` |
| 1 | after `hotSwapIn`, before the three main update events |
| 2 | only `stopOldSpec` has occurred |
| 3 | only `reconfigure` has occurred |
| 4 | only `startNewSpec` has occurred |
| 5 | `stopOldSpec` and `reconfigure` have occurred |
| 6 | `stopOldSpec` and `startNewSpec` have occurred |
| 7 | `reconfigure` and `startNewSpec` have occurred |
| 8 | all three main update events have occurred |
| 9 | `hotSwapOut` has occurred |

The bitmask convention used in validation notes is:

- `1 = stopOldSpec`
- `2 = reconfigure`
- `4 = startNewSpec`

State 9 is the goal state for OTF-DUC exploration.

## Safety Activation Logic

OTF-DUC uses a delayed activation idea for New Safety.

Before `startNewSpec`, the New Safety property is not enforced.  However, fluent-derived monitor machines trace relevant behavior in the background.  When `startNewSpec` occurs, the implementation uses the monitor/fluent state to determine the correct current state of the New Safety property, then starts enforcing New Safety from that state.

This matters for the paper because it explains how OTF-DUC can avoid enforcing the new specification too early while still enforcing it correctly once the update declares that the new specification has started.

## hotSwapOut Guard

OTF-DUC does not allow `hotSwapOut` merely because the three main update events have occurred.

`hotSwapOut` is allowed only when:

- the current mapping-environment state can be translated into a New Environment state;
- the pair consisting of that New Environment state and the current New Safety state corresponds to a valid state in the New Controller.

This is the handoff safety check.  In paper language, OTF-DUC completes the update only at states that are known to be connectable to the New Controller.

## What the Implementation Contributes Conceptually

Even without code access, the following implementation-derived points are part of the project knowledge:

- OTF-DUC builds a DCS input list containing a marking/update-phase component, the old controller, mapping environment components, old safety properties, new safety properties, transition requirements, and fluent-derived monitor machines.
- The New Controller is not included in this on-the-fly search list.
- The old controller's actions are renamed internally so that pre-update behavior can be distinguished from update/environment behavior.
- During search, components have separate roles for action enablement, state tracing, and safety enforcement.
- Components that do not need to be traced in a phase can be normalized so that irrelevant differences do not inflate the search space.
- After search, the output update controller is connected to the New Controller through `hotSwapOut` edges.

These points can support the algorithm description, but exact names, line numbers, and low-level implementation details should be confirmed by the implementer if the paper needs them.

## Evaluation Plan and Available Artifacts

The project contains experiment artifacts for Traditional DUC and OTF-DUC.  The implementer has directories for:

- old controllers;
- new controllers;
- LTS benchmark inputs;
- OTF-DUC logs and outputs;
- Traditional DUC logs and outputs;
- processed CSV/XLSX results;
- scripts for compacting logs and validating OTF-DUC outputs.

Benchmark families observed in the project include:

- Surveillance
- ProductionCell
- Workflow
- GSM
- Industry
- MetaSocket
- RailCab
- PowerPlant

Important metric categories:

- state counts;
- transition counts;
- runtime;
- memory checkpoints;
- update-event transition counts;
- update phase distribution;
- distance from `hotSwapIn` to completion;
- enabled update events by phase;
- `hotSwapOut` guard blocks;
- New Controller connection success/miss counts.

Do not invent results.  Ask the implementer for the current processed workbook or CSV before writing numerical claims.

## Validation Notes

A validation script exists in the project to check OTF-DUC output controllers against the traditional DUC GR(1) progress intention.  The checked idea is:

- after every reachable `hotSwapIn`, the controller should not be able to remain forever in a cycle while any of `stopOldSpec`, `reconfigure`, or `startNewSpec` is missing.

This validation treats `hotSwapOut` separately.  That matches the conceptual distinction: `hotSwapOut` is an OTF handoff condition, while the traditional progress obligations concern the three main update events.

Before using validation results in the paper, ask the implementer whether the validation CSV/Markdown is current and complete.

## Suggested Paper Structure

One possible structure:

1. Introduction
   - high-availability systems require safe runtime controller updates;
   - Traditional DUCS gives formal safety/progress framing;
   - explicit construction of `E_u` limits scalability;
   - OTF-DUC replaces full construction with on-the-fly bridge search.

2. Background
   - LTS/controller synthesis basics;
   - Traditional DUCS;
   - DCS and on-the-fly composition.

3. Problem and Motivation
   - dynamic update from OC to NC;
   - update events and safety requirements;
   - `E_u` construction bottleneck.

4. OTF-DUC Method
   - update bridge search;
   - phase/marking model;
   - delayed New Safety activation;
   - `hotSwapOut` guard;
   - connection to New Controller.

5. Correctness Argument
   - old safety enforced until `stopOldSpec`;
   - transition requirements enforced during update;
   - new safety enforced after `startNewSpec`;
   - `hotSwapOut` only from states connectable to NC;
   - progress toward the update events under the explored strategy.

6. Evaluation
   - benchmarks;
   - Traditional DUC vs OTF-DUC;
   - state-space, time, memory, phase, and completion metrics.

7. Related Work
   - Dynamic Update Controller Synthesis;
   - Directed Controller Synthesis;
   - dynamic software updating;
   - self-adaptive systems;
   - safe runtime reconfiguration and staged updates.

8. Conclusion
   - OTF-DUC keeps formal update semantics while reducing upfront state-space construction.

## Claims That Are Safe to Make

The following are safe as qualitative project claims:

- OTF-DUC is motivated by the state-space cost of constructing the full traditional update environment.
- OTF-DUC searches update paths on the fly rather than preconstructing the entire update environment.
- OTF-DUC keeps the conceptual DUCS events `hotSwapIn`, `stopOldSpec`, `reconfigure`, and `startNewSpec`.
- OTF-DUC adds `hotSwapOut` as a guarded handoff event to the New Controller.
- OTF-DUC delays New Safety enforcement until `startNewSpec`.
- The intended evaluation compares OTF-DUC and Traditional DUC on benchmark examples using state-space and runtime-related metrics.

## Claims That Need Confirmation

Ask the implementer before making these claims:

- exact percentage or order-of-magnitude improvements;
- exact benchmark successes/failures;
- exact memory usage;
- whether all processed results were generated from the current implementation;
- whether a particular OTF-DUC variant, such as repair or merge, is part of the final proposed method;
- whether `hotSwapOut` is required in the final validation statement or only treated as a handoff condition;
- whether all theoretical proof obligations are fully established or still argued informally.

## Useful Wording

Good framing:

> OTF-DUC does not redefine dynamic controller update; it changes how the update game is explored.

> The method treats the update as a bridge synthesis problem between an already valid old controller and an already valid new controller.

> The new controller is represented at the handoff boundary rather than unfolded throughout the update search.

> The `hotSwapOut` guard prevents the update controller from handing off control from an intermediate state that cannot be interpreted as a safe New Controller state.

Avoid wording that suggests:

- OTF-DUC proves the New Controller correct from scratch;
- OTF-DUC eliminates all state explosion;
- `hotSwapOut` is identical to the traditional GR(1) progress guarantees;
- all experimental results are final unless the implementer confirms them.

## Questions for the Implementer

When drafting the paper, ask:

- What is the final name of the method: OTF-DUC, On-the-Fly DUC, or another variant?
- Which OTF-DUC variant is the final one evaluated in the paper?
- Which workbook or CSV is the source of truth for the latest results?
- Are Traditional DUC and OTF-DUC run on exactly the same benchmark inputs?
- Are all benchmark failures expected, timeout-related, or implementation limitations?
- What is the exact proof claim: theorem, proposition, soundness argument, or implementation validation?
- Which figures should be included: update event phase graph, box-list architecture, handoff guard, or evaluation pipeline?

## Reference Context Available in the Project

The local project contains reference material related to:

- Dynamic Update of Discrete Event Controllers;
- Directed Controller Synthesis of discrete event systems;
- On-the-fly informed search for non-blocking directed controllers;
- paper draft notes for OTF-DUC;
- experiment outputs and processed workbooks.

If the paper needs exact bibliographic details, ask the implementer to provide the BibTeX or citation list.
