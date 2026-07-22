# Design Decisions

This file records decisions that future LLM agents should preserve unless the user explicitly changes the research direction.

## D-001: OTF-DUC Keeps DUCS Semantics

OTF-DUC should be described as an implementation/synthesis strategy that preserves the traditional DUCS correctness intent, not as a different update problem.

Traditional DUCS correctness centers on maintaining the old specification until `stopOldSpec`, respecting transition requirements, starting the new specification at `startNewSpec`, performing `reconfigure`, and completing the update.  OTF-DUC keeps these meanings while avoiding full `E_u` materialization.

## D-002: Use `hotSwapIn` and Add `hotSwapOut`

The implementation treats `hotSwapIn` as the start of update.  OTF-DUC adds `hotSwapOut` as the explicit handoff to the New Controller.

In code, `UpdateConstants.BEGIN_UPDATE = "hotSwapIn"` and `UpdateConstants.FINISH_UPDATE = "hotSwapOut"`.  `FINISH_UPDATE` is only the Java constant name, so design notes should use the event name `hotSwapOut`.

`hotSwapOut` is not just another progress marker.  It is guarded by environment translation and New Controller connection checks.

## D-003: Exclude the New Controller from the OTF Box List

The New Controller is not included in the DCS search box list.  This is a central scaling choice.

Instead, `StateMapper` precomputes a map from `(New Environment state, New Safety state)` signatures to New Controller states.  The DCS state space explores the update bridge; the New Controller is connected during output construction.

## D-004: Use a 10-state Marking LTS

The OTF Marking LTS has:

- state 0 before `hotSwapIn`;
- states 1-8 for the bitmask of completed events among `stopOldSpec`, `reconfigure`, and `startNewSpec`;
- state 9 after `hotSwapOut`.

Only state 9 is marked as a DCS goal.

## D-005: Delay New Safety Activation

New Safety should not be enforced before `startNewSpec`.

Fluent-derived synthesis machines trace the background behavior.  At `startNewSpec`, OTF-DUC uses the fluent/monitor state to synchronize the original New Safety component to the correct state, then enforces it.

## D-006: Preserve Active / Trace / Enforce Distinctions

The three flags have separate meanings:

- Active controls participation in action enablement/blocking.
- Trace controls state tracking.
- Enforce controls whether error/sink states kill the path.

Do not collapse these into a single enabled/disabled switch.  That would change the OTF-DUC semantics.

## D-007: Normalize Trace-off Components

Components whose Trace flag is off should not create spurious search-state distinctions.  State normalization is part of the intended state-space reduction.

## D-008: Treat `hotSwapOut` as a Handoff Condition

`hotSwapOut` should be allowed only if:

- Mapping Environment state can be translated to a New Environment state;
- translated New Environment state plus current New Safety state corresponds to a safe New Controller state.

This decision avoids connecting the update controller to the New Controller from an unsafe or semantically unknown state.

## D-009: Keep Evaluation Metrics Machine-readable

`UpdatingControllerEvaluationRecorder` emits structured CSV-like evaluation data.  Experiment scripts should consume these machine-readable blocks rather than scraping informal text when possible.

## D-010: Preserve Long-run Observability

`DUCHeartbeat` exists to observe long-running Traditional/OTF synthesis phases.  Do not remove heartbeat counters just because they look like logging; they can be useful for diagnosing large benchmark runs.

## D-011: Remove OTF-DUC GR(1)-style Loop Judgement

OTF-DUC should not carry a separate GR(1)-style progress fixed point inside its loop analysis.  The OTF search should use its fairness/loop handling directly, while the traditional DUCS GR synthesizer remains a separate implementation path.

This keeps the research claim clear: OTF-DUC is not relying on an internal GR(1)-style loop promotion to justify update progress.

## D-012: Limit OTF-DUC Fairness to Marking State 8

OTF-DUC applies the existing fairness fixed-point method only after `stopOldSpec`, `reconfigure`, and `startNewSpec` have all completed, i.e. in marking state 8 before `hotSwapOut`.

During marking states 1-7, fairness must not rescue update-path loops.  If a detected update-path loop can be continued by an uncontrollable action, the source state of that uncontrollable loop-continuation action is losing, even when an update-protocol or other action could leave the loop.  OTF-DUC marks those states as errors and lets the standard error propagation mark states that can be forced into them.  Under the marking state 8 fairness fixed point, uncontrollable exits may be used as fair progress, and update-protocol actions may also be used as progress exits, but ordinary controllable actions are not treated as exits from uncontrollable loops.  Ordinary controllable actions are pruned in the generated controller unless selected as the winning progress action.

## D-013: Keep Pre-update Closure Separate from Fairness

Marking state 0 is before `hotSwapIn`, so old-controller loops there should not be justified by update fairness.  Instead, OTF-DUC uses a pre-update GOAL closure: an m0 state can be promoted when its explored `hotSwapIn` edge reaches an already winning update path and the explored uncontrollable old-controller actions stay inside the same promoted m0 region or already winning states.

This keeps the concepts separate: m0 closure represents ordinary old-controller operation before the update starts; marking state 8 fairness represents fair progress from the fully prepared update bridge to `hotSwapOut`.

## D-014: Fine-grained Update Events Are an Opt-in Mode

The `.lts` keyword `fine_grained` selects fine-grained update events.  With `on_the_fly`, this runs fine-grained OTF-DUC; without `on_the_fly`, this runs fine-grained Traditional DUC.  Files without this flag keep the legacy update protocol.  The fine-grained implementation is intentionally separated from the legacy class path where practical so the two modes can be compared and preserved independently.

In fine-grained mode, `stopOldSpec_<safety>` and `startNewSpec_<safety>` are generated from the old/new controllerSpec safety names.  Relation rules may use either legacy `reconfigure`, which is normalized to `reconfigure_<mapping component>`, or an explicit `reconfigure_*` action.  Each mapping component has exactly one reconfigure action.  These generated action names are public enough to be referenced by transition requirements.

Fine-grained progress is represented as one synthetic progress slot in the DCS state vector, not as one LTS per flag.  A `ProgressRegistry` maps arbitrary-size BigInteger completion masks to compact state IDs, so the number of fine-grained update actions is not limited to 63.  `hotSwapOut` is enabled only after all fine-grained stop/reconfigure/start actions have completed and the existing new-controller stitching guard succeeds.

Traditional fine-grained DUC still materializes `E_u`.  Its fine-grained `E_u` generation uses per-safety `stopOldSpec_*` and `startNewSpec_*` self-loops in update states, keeps per-mapping `reconfigure_*` transitions in the mapping environment, applies DontDoTwice to all fine-grained progress actions, and puts all fine-grained progress actions in the GR guarantee set.  Traditional DUC does not add `hotSwapOut`.

## D-015: Separate Update Safety from New-controller Handoff Completion

Marking state 8 means that the update protocol body has completed: `stopOldSpec`, `reconfigure`, and `startNewSpec` are all done, and the new safety specification is active. A marking-state-8 state can therefore be update-safe even when the `hotSwapOut` handoff to the pre-synthesized New Controller has not happened yet.

This separates two correctness notions:

- update safety: the update bridge preserves the intended old/new safety semantics and does not violate the active new safety in marking state 8;
- handoff completion: after `hotSwapIn`, the controller is guaranteed to eventually take `hotSwapOut` and enter the pre-synthesized New Controller.

The default OTF-DUC mode may use the marking-state-8 fairness fixed point as a fair-completion assumption. Under this interpretation, a safe marking-state-8 SCC can be accepted if fair executions can reach `hotSwapOut`, even though ordinary all-traces model checking of `hotSwapIn -> <> hotSwapOut` may fail because the environment can keep taking uncontrollable actions in the SCC.

The jar option `-Dotfduc.fairness=false` or `-Dotfduc.disableFairness=true` disables this marking-state-8 fairness promotion. This stricter mode treats handoff completion as an ordinary all-traces reachability requirement; it can fail examples that the fairness mode accepts, but it is the appropriate mode when the generated controller should satisfy ordinary `hotSwapIn -> <> hotSwapOut` without fairness assumptions.

This does not change the treatment of marking states 1-7. Those states are still update-protocol-incomplete states, and fairness must not rescue their update-path loops. If a closed uncontrollable loop on the update path prevents progress, it is losing. During on-the-fly exploration the implementation may postpone an error decision until enough successors are explored, but an unexplored controllable update action does not by itself make a state winning in the presence of an uncontrollable self-loop.

## D-016: Use a State-based Two-room Art Gallery Baseline

`MODEL/StepwiseDUCS/ArtGallery2RoomsBase.lts` is the cleaned static baseline for a future Stepwise Delayed DUCS Art Gallery benchmark.  It deliberately contains no update scenario yet.

The shared action names, shared `resRoomStatus`, and shared `UNLOCK_TIME` fluent are preserved.  All allow/deny decisions are controllable; this fixes the two-room source model's omission of `allow_Out`, `deny_Hall`, and `deny_A`.  `resRoomStatus` remains controllable to preserve the original benchmark convention.  Treating it as an uncontrollable sensor response must be a separately named experimental variant.

The four requirements that previously contained nested `X` and weak-until operators use guarded pending-obligation fluents instead.  A lock obligation is pending after the threshold-arrival action until either lock-state transition, and applies while the corresponding door remains unlocked.  An unlock obligation is pending after the outward allow action until either lock-state transition, and applies while the door remains locked.  The resulting requirements are state-based and therefore compatible with the current Stepwise formula path.

As a regression check at the original scale, the cleaned Traditional Controller was transition-for-transition identical to the original two-room Traditional Controller.  The cleaned Traditional and manual Stepwise controllers were also identical apart from their process names; both had 153 states and 225 transitions for `N=10` and threshold 2.  The checked-in baseline now uses `N=4` with threshold 2 for initial operation checks; larger-scale experiments should vary `N` explicitly.

## D-017: First Art Gallery Update Adds Coordinated Entry Control

`MODEL/StepwiseDUCS/ArtGallery2RoomsCoordinatedEntryUpdate.lts` is the first DUCS scenario derived from the cleaned two-room baseline.  The old goal contains the twelve independent Hall/Room A rules.  The new goal keeps all twelve and adds `A_LOCKED -> !allow_Hall`, so a full/locked Room A stops new Hall admission without preventing Hall-to-Room-A movement.

The physical environment and action names do not change.  The update uses five old/new component pairs—Visitor, Hall count, Room A count, Hall door, and Room A door—with identity relations.  Visitor phases, count-update phases, and locked door states are explicitly named so `reconfigure` preserves in-flight requests and partially completed allow/deny-to-arrival transactions instead of discarding them.

Update-event ordering is no longer fixed in this model.  D-020 records the state-based gap-safety requirement that replaced the original ordering constraints.

The public synthesis targets are `UPDATE_CONTROLLER` for Traditional DUCS and `STEPWISE_DELAYED_UPDATE_CONTROLLER` for Stepwise Delayed DUCS.  Parsing, old/new environment composition, all five mapping components, and old/new static controller synthesis were checked.  Full update-controller synthesis was intentionally left for the experiment run.

## D-018: Extend the Art Gallery Benchmark by Adjacent Backpressure

`MODEL/StepwiseDUCS/ArtGallery3RoomsBase.lts` and `MODEL/StepwiseDUCS/ArtGallery3RoomsCoordinatedEntryUpdate.lts` extend the two-room benchmark with Room B.  Visitor flow is `Outside -> Hall -> Room A -> Room B -> Outside`.  The checked-in operation-check scale remains `N=4`, and Hall, Room A, and Room B each use threshold 2.

The old goal has six state-based local occupancy/lock requirements per area, for eighteen requirements in total.  The new goal retains all eighteen and adds adjacent backpressure: `A_LOCKED -> !allow_Hall` and `B_LOCKED -> !allow_A`.  These are two cross requirements, while the physical environment and shared action names remain unchanged.

The update model has seven old/new component pairs: Visitor, three counters, and three door locks.  Every pair uses a phase-preserving identity relation.  For `N=4`, both old and new environments have 14,080 states and 61,600 transitions; their composed mapping environment has 28,160 states and 137,280 transitions.  Static synthesis produced an old controller with 661 states and 1,030 transitions and a new controller with 463 states and 707 transitions.  In the Base file, Traditional and manual Stepwise synthesis both produce 661 states and 1,030 transitions and differ only in the public process name.

The update targets remain `UPDATE_CONTROLLER` for Traditional DUCS and `STEPWISE_DELAYED_UPDATE_CONTROLLER` for Stepwise Delayed DUCS with safety backward pruning.  Their definitions parse and all synthesis prerequisites compose, but full three-room update-controller synthesis is intentionally left for the experiment run.

## D-019: Use a Lightweight State-based Kiva Baseline Before DUCS Conversion

`MODEL/StepwiseDUCS/Kiva2RobotsStateBasedBase.lts` is the first static Kiva-derived baseline for a future Stepwise Delayed DUCS benchmark.  It deliberately contains no Directed Controller Synthesis target and no update scenario yet.

The source Kiva specification has 69 active safety properties, of which 48 contain nested `X` / weak-until behavior.  The baseline rewrites the 32 temporal properties that can be represented by post-event pending fluents and keeps the 21 already state-based properties, giving 53 state-based safety goals.  Sixteen properties whose trigger action is itself forbidden while the obligation is pending are explicitly deferred; a post-event fluent valuation cannot distinguish the first trigger from a forbidden repetition.

Do not move all sixteen deferred properties into independent plant-side protocol guards by default.  The attempted guard composition increased the N=2 plant from 2,304 states and 55,680 transitions to 857,088 states and 14,701,824 transitions before controller synthesis, which defeats the purpose of a lightweight state-based model.

For N=2, the lightweight plant remains identical in size to the source plant: 2,304 states and 55,680 transitions.  The synthesized state-based controller has 15,178 raw states and 60,847 raw transitions; after minimization it has 6,489 states and 31,050 transitions.  The original controller minimizes to 2,475 states and 5,797 transitions.

A deterministic finite-trace inclusion check found that every trace of the original controller is accepted by the lightweight state-based controller.  The inclusion is strict because the sixteen deferred properties make the new baseline more permissive.  The shortest found additional trace is `reqBringBox, robot1.move.boxGettingArea, supplyBox, supplyBox`, corresponding to deferred `P_BRING_BOX_RULE_4`.  Therefore this baseline must not be described as trace-equivalent to the original Kiva model.

## D-020: Let DUCS Choose the Two-room Art Gallery Update Order

`MODEL/StepwiseDUCS/ArtGallery2RoomsCoordinatedEntryUpdate.lts` does not impose an order on `stopOldSpec`, `reconfigure`, and `startNewSpec`.  The earlier `reconfigure -> startNewSpec -> stopOldSpec` transition constraints reduced the update-order search space and therefore hid part of the behavior that DUCS is intended to synthesize.

The replacement transition requirement is phase based rather than order based.  While `stopOldSpec` has occurred and `startNewSpec` has not, all controllable gallery operational actions—status response, lock/unlock, and allow/deny decisions—are frozen.  Update-protocol actions and uncontrollable Visitor/Monitor actions remain available, so the controller can leave the unprotected phase without an imposed event order.  If `startNewSpec` occurs before `stopOldSpec`, the gap predicate is false and the compatible old/new overlap is handled directly by DUCS.

## D-021: Defer Incomplete Uncontrollable Error Propagation in Stepwise DUCS

The paper's Stepwise DUCS is the normal non-incremental `stepwise_delayed` path with safety backward pruning and delayed `hotSwapIn` connection.  Local and staged cross fragments use explicit Error states: direct update-formula violations are Error seeds, while an ordinary dead-end in a partial fragment is not an Error seed merely because some components have not yet been composed.

Action ownership is indexed once from every mapping component alphabet.  A shared action is owned by every mapping component whose alphabet contains it.  When an uncontrollable action reaches Error, backward propagation is immediate only if all owners of that action are already in the current fragment.  If an owner is still uncomposed, the source is not marked Error; the Error-boundary transition is preserved for later composition.  Owner-aware composition then retains the transition only in product states where every owner enables the action.  Once all mapping components, and finally the delayed old-controller connection, are present, ordinary full-scope pruning resolves all remaining Error boundaries and deadlocks.

Do not expand formula scopes by taking an uncontrollable-owner closure.  Formula scope remains the component set needed to decide the formula, while deferred Error boundaries handle synchronization that cannot yet be decided.  The paper proof targets both soundness and the one-way relative-completeness claim that Traditional DUCS success implies Stepwise DUCS success.  It does not claim identical LTSs, languages, permissiveness, or the converse implication.  The experimental incremental-pruning path is outside this theorem.

## D-022: Use Three Repetitions for the Main Stepwise Evaluation

Every main evaluation configuration under `MODEL/StepwiseDUCS/Experiment/configs/local` uses `runs: 3`.  Diagnostic-only configurations keep `runs: 1`.  Runtime and peak-memory results should be summarized over the three runs; deterministic state and transition counts may be reported once.  The paper comparison is limited to Traditional DUCS and Stepwise DUCS, and the current trace and nine-requirement checker outputs are not evaluation success criteria.

## D-023: Exclude Reserved `tau` from Stepwise Delayed Action Ownership

Every `CompactState` alphabet reserves slot 0 for `tau`, even when the source model has no `tau` transition.  `stepwise_delayed` must therefore exclude `tau` from each stage's `realActions` and from the global passive-action completion set.  `tau` is an internal action, not a mapping-component-owned event.

Before this exclusion, cost-guided scheduling could complete `tau` with passive self-loops in two independently built cross fragments.  Merging those fragments synchronized and retained one uncontrollable `tau` self-loop at every final mapping state.  A connected fixed-order schedule happened to remove the same artifact when the growing fragment was composed with the next local stage, making realizability incorrectly depend on scheduling order.

The diagnostic property `-Dstepwise.delayed.debugActionDiagnostics=true` records per-action transition/self-loop counts and passive-transition valuation invariants at staged checkpoints.  It is disabled by default; ordinary evaluation runs do not traverse graphs or emit diagnostic records.  The N=4 ComputeCluster reproduction changed from losing to winning after the `tau` exclusion, and its cost-guided output transition file matched the successful fixed-order output byte for byte in this diagnostic case.  This observation is a regression result for this model, not a general equivalence claim between synthesis methods or schedules.

## D-024: Preserve Passive Fluent Transitions and Extend Fluent Products by Delta

Stepwise Delayed DUCS adds missing global normal actions as passive self-loops only to each raw mapping environment, before any fluent valuation is attached.  After the local fluent product, such an action may change an action-fluent valuation and therefore need not remain a self-loop in the valued partial environment.  Staged owner-aware products must retain this existing pure-passive transition while the real owner is outside the current scope.  Once a real owner enters the product, its enabled/disabled state gates the action in the usual way.  Product alphabets preserve the union of input alphabets so that “present but disabled” remains distinguishable from “absent and stuttering.”  Do not add raw passive self-loops after a fluent product; doing so freezes already tracked fluent values.

The non-incremental paper path still constructs every local stage's local and phase fluents once from its raw mapping environment.  Beginning with the first staged cross step, an already tracked fluent is never regenerated from its initial value.  Each step completes action-fluent definitions with the same canonical global synthesis alphabet, computes only the newly required cross fluents, and extends the current valued environment with those fluents.  Existing valuations, mapping metadata, Error states, old origins, and real-action ownership are authoritative and are carried forward.  Reusing a fluent name with a different initial value or initiating/terminating action set is a synthesis error.
