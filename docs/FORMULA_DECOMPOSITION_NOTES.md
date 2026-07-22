# Formula Decomposition Notes

Last updated: 2026-06-09

This memo records the current discussion about decomposing transition requirements, old safety properties, and new safety properties.

No automatic decomposition is implemented at this point. If decomposition is needed for experiments, write the decomposed formulas explicitly in the `.lts` file.

## Motivation

O-DUCS builds a DCS box list containing mapping components, old safety components, new safety components, transition requirement testers, and fluent machines.

Large formulas can produce large tester models. Splitting an AND-shaped formula into several smaller testers can reduce the cost of local successor generation and may let the search detect bad branches earlier.

However, decomposition is not always beneficial. Splitting also increases the number of box-list components and may duplicate work.

## Current Scope Decision

Transition requirement decomposition may be useful for:

- O-DUCS;
- FG-O-DUCS;
- SFG-O-DUCS.

Old safety and new safety decomposition is considered only for ordinary O-DUCS:

```text
on_the_fly
no fine_grained
no selective_fine_grained
```

Old/new safety decomposition is not planned for FG-O-DUCS or SFG-O-DUCS at this stage because safety names define update-action names such as `stopOldSpec_P` and `startNewSpec_P`.

## Decomposable Top-Level AND

The simplest safe case is a top-level AND after expanding assertion references and removing the leading `[]` where appropriate.

Example:

```fsp
ltl_property T = [](A && B && C)
```

can be treated as:

```fsp
ltl_property T_1 = []A
ltl_property T_2 = []B
ltl_property T_3 = []C
```

The same applies when the AND is hidden behind an assertion:

```fsp
assert S = (A && B && C)
ltl_property T = []S
```

After assertion expansion, this has the same shape.

## Distributed OR/AND Pattern

Some useful formulas are not top-level AND but can still be decomposed by logical distribution.

ProductionCell has this shape:

```fsp
ltl_property T_REMOVE_POLISHED_OR_NEW_REQ_OTF_1 =
    []REMOVE_POLISHED_OR_NEW_REQ_1

assert REMOVE_POLISHED_OR_NEW_REQ_1 =
    ((StopOldSpec && !StartNewSpec) -> (S_NEW_1 || (out[1] -> Faulty[1])))

assert S_NEW_1 =
    (NEW_TOOL_ORDER_1 && NEW_OUT_IF_FINISHED_1 && DRILL_ONCE_1 && PAINT_ONCE_1 && CLEAN_ONCE_1)
```

Expanded shape:

```text
[](G -> ((A && B && C && D && E) || F))
```

where:

```text
G = StopOldSpec && !StartNewSpec
F = out[1] -> Faulty[1]
```

This is equivalent to:

```text
[](G -> (A || F))
&& [](G -> (B || F))
&& [](G -> (C || F))
&& [](G -> (D || F))
&& [](G -> (E || F))
```

The escape branch `F` must be preserved in every decomposed formula. Splitting it into `[](G -> A)`, `[](G -> B)`, etc. would be stronger than the original formula and is not correct.

Useful distribution rules:

```text
X || (A && B)  ==  (X || A) && (X || B)
(A && B) || X  ==  (A || X) && (B || X)
G -> (A && B)  ==  (G -> A) && (G -> B)
```

These rules should be applied only when the transformation is explicitly implemented and tested.

## Old Safety Decomposition

For ordinary O-DUCS, old safety decomposition is conceptually straightforward.

If:

```fsp
ltl_property P = [](A && B)
```

is decomposed into:

```fsp
ltl_property P_1 = []A
ltl_property P_2 = []B
```

both components remain controlled by the same legacy update event:

```text
stopOldSpec
```

This is monitor/tester decomposition only. It must not generate `stopOldSpec_P_1` or `stopOldSpec_P_2` in ordinary O-DUCS.

## New Safety Decomposition

For ordinary O-DUCS, new safety decomposition should be done before building new-safety lookup tables.

The intended pipeline would be:

1. Read the new goal safety list.
2. Expand assertion references.
3. Decompose safe AND-shaped formulas.
4. Generate one tester per decomposed component.
5. Extract the fluent subset for each component.
6. Build `safetyComponentIndicesMap` and `safetyStateLookupMap` for each decomposed component.
7. At `startNewSpec`, initialize each decomposed new safety component using its own lookup table.

All decomposed new safety components are still controlled by the same legacy update event:

```text
startNewSpec
```

This is also monitor/tester decomposition only.

## Why FG/SFG Safety Decomposition Is Different

In FG-O-DUCS and SFG-O-DUCS, safety names define update actions:

```text
stopOldSpec_P
startNewSpec_P
```

If a safety `P` were automatically decomposed into `P_1` and `P_2`, the implementation would need to decide whether to generate:

```text
startNewSpec_P
```

for both components, or:

```text
startNewSpec_P_1
startNewSpec_P_2
```

The latter changes protocol granularity. The former requires a monitor-only split design that keeps both decomposed testers tied to the original update action.

For now, old/new safety decomposition is therefore restricted to ordinary O-DUCS as a future design, not FG/SFG.

## Cost Concern

Decomposition can be slower than a single tester.

It may hurt performance when:

- the original tester is already small;
- decomposed formulas share many fluents;
- the sum of decomposed tester states/transitions exceeds the original;
- box-list length increases enough to dominate successor generation;
- new safety decomposition creates many lookup tables.

A future automatic implementation should compare the original tester with the decomposed testers before choosing decomposition.

Possible decision score:

```text
score = states + alpha * transitions + beta * componentCount
```

Potential conservative rule:

```text
sum(splitStates) <= originalStates
sum(splitTransitions) <= originalTransitions
splitCount <= maxSplitCount
```

This has not been implemented.

## Current Practical Policy

No automatic formula decomposition is currently implemented.

When decomposition is needed, write it in the `.lts` file:

- create individual `assert` definitions;
- create individual `ltl_property` definitions;
- add each property as a separate `transition = ...` entry.

For O-DUCS-style transition requirements, keep the current convention of using `[]` in the OTF property when needed.
