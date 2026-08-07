package ltsa.updatingControllers.stepwise.delayed;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.language.SingleSymbol;
import MTSSynthesis.ar.dc.uba.model.language.Symbol;
import MTSSynthesis.controller.util.FluentStateValuation;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTSConstants;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.lts.CompactState;
import ltsa.lts.LTSException;
import ltsa.lts.LTSOutput;
import ltsa.updatingControllers.UpdateConstants;
import ltsa.updatingControllers.synthesis.SafetyBackwardPruner;
import ltsa.updatingControllers.synthesis.UpdatingControllerSafetySynthesizer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StepwiseDelayedUpdatingControllerSynthesizerTest {

    private static final LTSOutput NO_OUTPUT = new LTSOutput() {
        public void out(String str) {
        }

        public void outln(String str) {
        }

        public void clearOutput() {
        }
    };

    @Test
    public void reservedTauAlphabetEntryIsNotAComponentRealAction() {
        MTS<Long, String> mapping = new MTSImpl<Long, String>(0L);
        mapping.addAction(MTSConstants.TAU);
        mapping.addAction("work");
        mapping.addRequired(0L, "work", 0L);

        Set<String> realActions =
                StepwiseDelayedUpdatingControllerSynthesizer.mappingRealActions(mapping);

        assertFalse(realActions.contains(MTSConstants.TAU));
        assertTrue(realActions.contains("work"));
        assertTrue("The diagnostic/fix must not mutate the mapping MTS alphabet.",
                mapping.getActions().contains(MTSConstants.TAU));
    }

    @Test
    public void initialLocalProductMakesPassiveActionUpdateActionFluent() {
        MTS<Long, String> mapping = oneStateEnvironment("activate", "later");
        Fluent active = fluent("active", false, set("activate"), set("later"));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv local =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        mapping,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton(active));

        long initial = local.getEnvironment().getInitialState();
        long activeState = onlyTarget(local.getEnvironment(), initial, "activate");
        assertTrue(local.getValuation().isTrue(activeState, active));

        long inactiveState = onlyTarget(local.getEnvironment(), activeState, "later");
        assertFalse(local.getValuation().isTrue(inactiveState, active));
        assertNotEquals("A passive mapping self-loop may change the fluent-product state.",
                activeState,
                inactiveState);
    }

    @Test
    public void ownerAwareProductRetainsPurePassiveFluentChangingTransition() {
        MTS<Long, String> mapping = oneStateEnvironment("activate", "later");
        Fluent active = fluent("active", false, set("activate"), set("later"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv local =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        mapping,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton(active));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv passiveFragment =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        local.getEnvironment(),
                        local.getTrackedFluents(),
                        local.getValuation(),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton("activate"));

        MTS<Long, String> dummy = new MTSImpl<Long, String>(0L);
        dummy.addAction("later");
        dummy.addAction("blocked");
        dummy.addRequired(0L, "later", 0L);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv dummyFragment =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        dummy,
                        Collections.<Fluent>emptySet(),
                        new FluentStateValuation<Long>(dummy.getStates()),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.<String>emptySet());

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(passiveFragment, dummyFragment),
                        "PASSIVE_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        long activeState = onlyTarget(product.getEnvironment(), initial, "activate");
        long inactiveState = onlyTarget(product.getEnvironment(), activeState, "later");
        assertTrue(product.getValuation().isTrue(activeState, active));
        assertFalse(product.getValuation().isTrue(inactiveState, active));
        assertNotEquals(activeState, inactiveState);

        assertTrue("A disabled input action must stay in the product alphabet.",
                product.getEnvironment().getActions().contains("blocked"));
        assertFalse(hasTransition(product.getEnvironment(), initial, "blocked"));

        MTS<Long, String> disabledOwner = new MTSImpl<Long, String>(0L);
        disabledOwner.addAction("later");
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv ownerFragment =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        disabledOwner,
                        Collections.<Fluent>emptySet(),
                        new FluentStateValuation<Long>(disabledOwner.getStates()),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton("later"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv ownerGatedProduct =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(product, ownerFragment),
                        "OWNER_GATE_TEST",
                        NO_OUTPUT);
        assertTrue(ownerGatedProduct.getEnvironment().getActions().contains("later"));
        assertFalse("Once the real owner joins, its disabled state must gate the passive action.",
                hasTransition(
                        ownerGatedProduct.getEnvironment(),
                        ownerGatedProduct.getEnvironment().getInitialState(),
                        "later"));
    }

    @Test
    public void extendingWithNoNewFluentIsANoOp() {
        MTS<Long, String> environment = oneStateEnvironment("tick");
        Fluent existing = fluent("existing", false, set("never"), Collections.<String>emptySet());
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(environment.getStates());
        valuation.addHoldingFluent(0L, existing);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv current =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        environment,
                        Collections.singleton(existing),
                        valuation,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton("tick"));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv extended =
                StepwiseDelayedUpdatingControllerSynthesizer.extendFluentProduct(
                        current,
                        Collections.singleton(
                                fluent("existing", false, set("never"), Collections.<String>emptySet())));

        assertSame(current, extended);
    }

    @Test(expected = LTSException.class)
    public void extensionRejectsAChangedDefinitionForAnAlreadyTrackedFluentName() {
        MTS<Long, String> environment = oneStateEnvironment("tick");
        Fluent existing = fluent("existing", false, set("tick"), Collections.<String>emptySet());
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv current =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        environment,
                        Collections.singleton(existing),
                        new FluentStateValuation<Long>(environment.getStates()),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton("tick"));

        StepwiseDelayedUpdatingControllerSynthesizer.extendFluentProduct(
                current,
                Collections.singleton(
                        fluent("existing", false, set("tick"), set("other"))));
    }

    @Test
    public void extensionAddsOnlyMissingCrossFluentAndPreservesExistingValuation() {
        MTS<Long, String> environment = oneStateEnvironment(
                "crossOn", "crossOff", "laterStageAction");
        Fluent existing = fluent("existing", false, set("never"), Collections.<String>emptySet());
        Fluent cross = fluent(
                "cross",
                false,
                set("crossOn"),
                set("crossOff", "laterStageAction"));
        FluentStateValuation<Long> valuation = new FluentStateValuation<Long>(environment.getStates());
        valuation.addHoldingFluent(0L, existing);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv current =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        environment,
                        Collections.singleton(existing),
                        valuation,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        set("crossOn", "crossOff"));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv extended =
                StepwiseDelayedUpdatingControllerSynthesizer.extendFluentProduct(
                        current,
                        set(existing, cross));

        assertEquals(2, extended.getTrackedFluents().size());
        assertEquals(2, extended.getEnvironment().getStates().size());
        long initial = extended.getEnvironment().getInitialState();
        assertTrue("The existing valuation, not its initial value, is authoritative.",
                extended.getValuation().isTrue(initial, existing));
        assertFalse(extended.getValuation().isTrue(initial, cross));

        long crossState = onlyTarget(extended.getEnvironment(), initial, "crossOn");
        assertTrue(extended.getValuation().isTrue(crossState, existing));
        assertTrue(extended.getValuation().isTrue(crossState, cross));

        long terminatedState = onlyTarget(
                extended.getEnvironment(),
                crossState,
                "laterStageAction");
        assertTrue(extended.getValuation().isTrue(terminatedState, existing));
        assertFalse(extended.getValuation().isTrue(terminatedState, cross));
    }

    @Test
    public void stagedThreeComponentPassiveProductMatchesMonolithicFluentProduct() {
        Set<String> globalActions = set("a", "b", "c", "enableC");
        MTS<Long, String> stage1 = oneStateEnvironment(
                globalActions.toArray(new String[globalActions.size()]));
        MTS<Long, String> stage2 = oneStateEnvironment(
                globalActions.toArray(new String[globalActions.size()]));
        MTS<Long, String> stage3 = gatedThirdStage(globalActions);

        Fluent firstLocal = fluent("firstLocal", false, set("a"), set("c"));
        Fluent secondLocal = fluent("secondLocal", false, set("b"), set("c"));
        Fluent cross = fluent("cross", false, set("enableC"), set("c"));
        Set<Fluent> allFluents = set(firstLocal, secondLocal, cross);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv local1 = valuedFragment(
                stage1, Collections.singleton(firstLocal), Collections.singleton("a"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv local2 = valuedFragment(
                stage2, Collections.singleton(secondLocal), Collections.singleton("b"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv firstTwo =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(local1, local2),
                        "THREE_STAGE_FIRST_TWO",
                        NO_OUTPUT);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv withCross =
                StepwiseDelayedUpdatingControllerSynthesizer.extendFluentProduct(
                        firstTwo,
                        allFluents);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv local3 = valuedFragment(
                stage3,
                Collections.<Fluent>emptySet(),
                set("c", "enableC"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv staged =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(withCross, local3),
                        "THREE_STAGE_STAGED",
                        NO_OUTPUT);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv monolithic =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                valuedFragment(stage1, allFluents, Collections.singleton("a")),
                                valuedFragment(stage2, allFluents, Collections.singleton("b")),
                                valuedFragment(stage3, allFluents, set("c", "enableC"))),
                        "THREE_STAGE_MONOLITHIC_REFERENCE",
                        NO_OUTPUT);

        assertEquals(monolithic.getEnvironment().getStates().size(),
                staged.getEnvironment().getStates().size());
        assertEquals(totalTransitionCount(monolithic.getEnvironment()),
                totalTransitionCount(staged.getEnvironment()));
        assertEquals(semanticSnapshot(monolithic, allFluents),
                semanticSnapshot(staged, allFluents));

        long initial = staged.getEnvironment().getInitialState();
        assertFalse("The late owner disables c in its initial state.",
                hasTransition(staged.getEnvironment(), initial, "c"));
        long ownerEnabled = onlyTarget(staged.getEnvironment(), initial, "enableC");
        assertTrue("The passive enableC transition must update the cross fluent.",
                staged.getValuation().isTrue(ownerEnabled, cross));
        assertFalse("The owner disables enableC after entering its enabled state.",
                hasTransition(staged.getEnvironment(), ownerEnabled, "enableC"));

        long afterA = onlyTarget(staged.getEnvironment(), ownerEnabled, "a");
        long afterB = onlyTarget(staged.getEnvironment(), afterA, "b");
        assertTrue(staged.getValuation().isTrue(afterB, firstLocal));
        assertTrue(staged.getValuation().isTrue(afterB, secondLocal));
        assertTrue(staged.getValuation().isTrue(afterB, cross));

        long afterC = onlyTarget(staged.getEnvironment(), afterB, "c");
        assertFalse(staged.getValuation().isTrue(afterC, firstLocal));
        assertFalse(staged.getValuation().isTrue(afterC, secondLocal));
        assertFalse(staged.getValuation().isTrue(afterC, cross));
        assertFalse("c must be gated again after its owner returns to the initial state.",
                hasTransition(staged.getEnvironment(), afterC, "c"));
    }

    @Test
    public void sharedActionSynchronizesAllRealOwnersAndUpdatesPassiveFluent() {
        Set<String> actions = set("shared", "enableSecond", "owner1Ready", "owner2Ready");

        MTS<Long, String> owner1 = new MTSImpl<Long, String>(0L);
        owner1.addState(1L);
        owner1.addActions(actions);
        owner1.addRequired(0L, "shared", 1L);
        owner1.addRequired(0L, "enableSecond", 0L);
        owner1.addRequired(1L, "enableSecond", 1L);
        owner1.addRequired(1L, "owner1Ready", 1L);
        owner1.addRequired(0L, "owner2Ready", 0L);
        owner1.addRequired(1L, "owner2Ready", 1L);

        MTS<Long, String> owner2 = new MTSImpl<Long, String>(0L);
        owner2.addState(1L);
        owner2.addState(2L);
        owner2.addActions(actions);
        owner2.addRequired(0L, "enableSecond", 1L);
        owner2.addRequired(1L, "shared", 2L);
        owner2.addRequired(0L, "owner1Ready", 0L);
        owner2.addRequired(1L, "owner1Ready", 1L);
        owner2.addRequired(2L, "owner1Ready", 2L);
        owner2.addRequired(2L, "owner2Ready", 2L);

        Fluent sharedSeen = fluent(
                "sharedSeen",
                false,
                Collections.singleton("shared"),
                Collections.<String>emptySet());
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                valuedFragment(
                                        owner1,
                                        Collections.<Fluent>emptySet(),
                                        set("shared", "owner1Ready")),
                                valuedFragment(
                                        owner2,
                                        Collections.<Fluent>emptySet(),
                                        set("shared", "enableSecond", "owner2Ready")),
                                valuedFragment(
                                        oneStateEnvironment(
                                                actions.toArray(new String[actions.size()])),
                                        Collections.singleton(sharedSeen),
                                        Collections.<String>emptySet())),
                        "SHARED_OWNER_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        assertTrue(product.getEnvironment().getActions().contains("shared"));
        assertFalse("A shared action must be blocked while one real owner disables it.",
                hasTransition(product.getEnvironment(), initial, "shared"));
        assertFalse(product.getValuation().isTrue(initial, sharedSeen));

        long secondEnabled = onlyTarget(product.getEnvironment(), initial, "enableSecond");
        assertTrue(hasTransition(product.getEnvironment(), secondEnabled, "shared"));
        assertFalse(product.getValuation().isTrue(secondEnabled, sharedSeen));

        long afterShared = onlyTarget(product.getEnvironment(), secondEnabled, "shared");
        assertTrue("The passive fragment must observe the shared owner transition.",
                product.getValuation().isTrue(afterShared, sharedSeen));
        assertTrue("The first real owner must move on the shared action.",
                hasTransition(product.getEnvironment(), afterShared, "owner1Ready"));
        assertTrue("The second real owner must move on the shared action.",
                hasTransition(product.getEnvironment(), afterShared, "owner2Ready"));
    }

    @Test
    public void sharedNondeterministicActionPreservesEveryCartesianTargetCombination() {
        MTS<Long, String> first = new MTSImpl<Long, String>(0L);
        first.addState(1L);
        first.addState(2L);
        first.addAction("branch");
        first.addRequired(0L, "branch", 1L);
        first.addRequired(0L, "branch", 2L);

        MTS<Long, String> second = new MTSImpl<Long, String>(0L);
        second.addState(10L);
        second.addState(20L);
        second.addAction("branch");
        second.addRequired(0L, "branch", 10L);
        second.addRequired(0L, "branch", 20L);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                emptyValuedFragment(first, Collections.singleton("branch")),
                                emptyValuedFragment(second, Collections.singleton("branch"))),
                        "NONDETERMINISTIC_CARTESIAN_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        Set<Long> targets = targets(product.getEnvironment(), initial, "branch");
        assertEquals("Two targets from each owner must produce all 2 x 2 combinations.",
                4,
                targets.size());
        assertEquals(5, product.getEnvironment().getStates().size());
        assertEquals(4, totalTransitionCount(product.getEnvironment()));
    }

    @Test
    public void updateActionSynchronizesOnlyWhenEveryInputEnablesIt() {
        MTS<Long, String> alwaysReady = new MTSImpl<Long, String>(0L);
        alwaysReady.addAction(UpdateConstants.STOP_OLD_SPEC);
        alwaysReady.addRequired(0L, UpdateConstants.STOP_OLD_SPEC, 0L);

        MTS<Long, String> initiallyNotReady = new MTSImpl<Long, String>(0L);
        initiallyNotReady.addState(1L);
        initiallyNotReady.addActions(set("enableSecond", UpdateConstants.STOP_OLD_SPEC));
        initiallyNotReady.addRequired(0L, "enableSecond", 1L);
        initiallyNotReady.addRequired(1L, UpdateConstants.STOP_OLD_SPEC, 1L);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                emptyValuedFragment(
                                        alwaysReady,
                                        Collections.<String>emptySet()),
                                emptyValuedFragment(
                                        initiallyNotReady,
                                        Collections.singleton("enableSecond"))),
                        "UPDATE_ACTION_ALL_INPUTS_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        assertTrue(product.getEnvironment().getActions().contains(
                UpdateConstants.STOP_OLD_SPEC));
        assertFalse("An update action must wait until every input enables it.",
                hasTransition(product.getEnvironment(), initial, UpdateConstants.STOP_OLD_SPEC));

        long ready = onlyTarget(product.getEnvironment(), initial, "enableSecond");
        assertTrue("The update action must synchronize once every input enables it.",
                hasTransition(product.getEnvironment(), ready, UpdateConstants.STOP_OLD_SPEC));
    }

    @Test
    public void oldActionUsesTheOwnerOfItsNormalizedBaseAction() {
        MTS<Long, String> normalOwner = new MTSImpl<Long, String>(0L);
        normalOwner.addAction("move");

        MTS<Long, String> oldActionFragment = oneStateEnvironment("move.old");

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                emptyValuedFragment(
                                        normalOwner,
                                        Collections.singleton("move")),
                                emptyValuedFragment(
                                        oldActionFragment,
                                        Collections.<String>emptySet())),
                        "OLD_ACTION_OWNER_NORMALIZATION_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        assertTrue(product.getEnvironment().getActions().contains("move.old"));
        assertFalse("move.old must be gated by the component that owns normal action move.",
                hasTransition(product.getEnvironment(), initial, "move.old"));
    }

    @Test
    public void errorTupleIsRecordedButNeverExpanded() {
        MTS<Long, String> failing = new MTSImpl<Long, String>(0L);
        failing.addState(1L);
        failing.addState(2L);
        failing.addActions(set("enterError", "escape"));
        failing.addRequired(0L, "enterError", 1L);
        failing.addRequired(1L, "escape", 2L);

        MTS<Long, String> observer = oneStateEnvironment("enterError", "escape");
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv failingFragment =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        failing,
                        Collections.<Fluent>emptySet(),
                        new FluentStateValuation<Long>(failing.getStates()),
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        set("enterError", "escape"),
                        Collections.singleton(1L));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                failingFragment,
                                emptyValuedFragment(observer, set("enterError", "escape"))),
                        "ERROR_TUPLE_TERMINAL_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        long error = onlyTarget(product.getEnvironment(), initial, "enterError");
        assertFalse("An error tuple is terminal even if its inputs have outgoing transitions.",
                hasTransition(product.getEnvironment(), error, "escape"));
        assertEquals("The successor beyond the error tuple must not be discovered.",
                2,
                product.getEnvironment().getStates().size());
        assertEquals(0, product.getEnvironment().getTransitions(
                error, MTS.TransitionType.REQUIRED).size());
    }

    @Test
    public void safetyPrunedPassiveTransitionStaysDisabledAfterOwnerJoins() {
        Fluent unsafe = fluent(
                "unsafe",
                false,
                Collections.singleton("enterUnsafe"),
                Collections.<String>emptySet());
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv partial = valuedFragment(
                oneStateEnvironment("enterUnsafe", "stay"),
                Collections.singleton(unsafe),
                Collections.singleton("stay"));

        long initial = partial.getEnvironment().getInitialState();
        long error = onlyTarget(partial.getEnvironment(), initial, "enterUnsafe");
        assertNotEquals("The passive action must update the tracked fluent state.", initial, error);
        assertTrue(partial.getValuation().isTrue(error, unsafe));

        Map<String, Set<Integer>> ownersByAction = new HashMap<String, Set<Integer>>();
        ownersByAction.put("enterUnsafe", Collections.singleton(1));
        SafetyBackwardPruner.DeferredResult pruning = SafetyBackwardPruner.pruneDeferred(
                partial.getEnvironment(),
                Collections.singleton(error),
                Collections.singleton("enterUnsafe"),
                ownersByAction,
                Collections.singleton(0),
                "PASSIVE_SAFETY_TEST",
                null);

        assertFalse(pruning.isInitialLosing());
        assertTrue("SBP must preserve the disabled action in the interface alphabet.",
                pruning.getEnvironment().getActions().contains("enterUnsafe"));
        assertFalse(hasTransition(pruning.getEnvironment(), initial, "enterUnsafe"));
        assertTrue(hasTransition(pruning.getEnvironment(), initial, "stay"));

        FluentStateValuation<Long> prunedValuation =
                new FluentStateValuation<Long>(pruning.getEnvironment().getStates());
        for (Long state : pruning.getEnvironment().getStates()) {
            for (Fluent fluent : partial.getValuation().getFluentsFromState(state)) {
                prunedValuation.addHoldingFluent(state, fluent);
            }
        }
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv prunedPartial =
                new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                        pruning.getEnvironment(),
                        partial.getTrackedFluents(),
                        prunedValuation,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton("stay"),
                        pruning.getErrorStates());
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv owner = valuedFragment(
                oneStateEnvironment("enterUnsafe"),
                Collections.<Fluent>emptySet(),
                Collections.singleton("enterUnsafe"));
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(prunedPartial, owner),
                        "PASSIVE_SAFETY_OWNER_TEST",
                        NO_OUTPUT);

        assertTrue(product.getEnvironment().getActions().contains("enterUnsafe"));
        assertFalse("A later real owner must not reintroduce a transition removed by early SBP.",
                hasTransition(
                        product.getEnvironment(),
                        product.getEnvironment().getInitialState(),
                        "enterUnsafe"));
        assertTrue(hasTransition(
                product.getEnvironment(),
                product.getEnvironment().getInitialState(),
                "stay"));
    }

    @Test
    public void connectedEnvironmentViewMatchesMaterializedGraphAndCompactStateConversion() {
        MTS<Long, String> oldEnvironment = new MTSImpl<Long, String>(5L);
        oldEnvironment.addState(9L);
        oldEnvironment.addState(100L);
        oldEnvironment.addActions(set(
                "control",
                "observe",
                UpdateConstants.STOP_OLD_SPEC,
                "oldMaybeOnly",
                "oldAlphabetOnly",
                "unreachableOld"));
        oldEnvironment.addRequired(5L, "control", 9L);
        oldEnvironment.addRequired(5L, "observe", 5L);
        oldEnvironment.addRequired(9L, UpdateConstants.STOP_OLD_SPEC, 9L);
        oldEnvironment.addRequired(100L, "unreachableOld", 100L);
        oldEnvironment.addPossible(5L, "oldMaybeOnly", 9L);

        MTS<Long, String> mappingEnvironment = new MTSImpl<Long, String>(3L);
        mappingEnvironment.addState(7L);
        mappingEnvironment.addState(11L);
        mappingEnvironment.addState(90L);
        mappingEnvironment.addActions(set(
                "control",
                "passive",
                "branch",
                UpdateConstants.RECONFIGURE,
                "mappingMaybeOnly",
                "mappingAlphabetOnly",
                "unreachableMapping"));
        mappingEnvironment.addRequired(3L, "control", 11L);
        mappingEnvironment.addRequired(3L, "passive", 7L);
        mappingEnvironment.addRequired(3L, "branch", 7L);
        mappingEnvironment.addRequired(3L, "branch", 11L);
        mappingEnvironment.addRequired(7L, UpdateConstants.RECONFIGURE, 11L);
        mappingEnvironment.addRequired(11L, "passive", 11L);
        mappingEnvironment.addRequired(90L, "unreachableMapping", 90L);
        mappingEnvironment.addPossible(3L, "mappingMaybeOnly", 11L);

        List<StepwiseDelayedUpdatingControllerSynthesizer.Connection> connections =
                Arrays.asList(
                        new StepwiseDelayedUpdatingControllerSynthesizer.Connection(5L, 3L),
                        new StepwiseDelayedUpdatingControllerSynthesizer.Connection(5L, 7L));
        Set<String> controllableActions = set(
                "control",
                UpdateConstants.STOP_OLD_SPEC,
                UpdateConstants.RECONFIGURE);

        List<String> oldBefore = canonicalMtsSnapshot(oldEnvironment);
        List<String> mappingBefore = canonicalMtsSnapshot(mappingEnvironment);
        MTS<Long, String> materialized =
                StepwiseDelayedUpdatingControllerSynthesizer.connectOldAndMapping(
                        oldEnvironment,
                        mappingEnvironment,
                        connections,
                        controllableActions);
        MTS<Long, String> view =
                StepwiseDelayedUpdatingControllerSynthesizer.connectOldAndMappingView(
                        oldEnvironment,
                        mappingEnvironment,
                        connections,
                        controllableActions);

        assertEquals(5L, materialized.getInitialState().longValue());
        assertEquals(set(5L, 9L, 104L, 108L, 112L), materialized.getStates());
        assertEquals(set(
                        "control" + UpdateConstants.OLD_LABEL,
                        "observe",
                        UpdateConstants.STOP_OLD_SPEC,
                        "unreachableOld",
                        "control",
                        "passive",
                        "branch",
                        UpdateConstants.RECONFIGURE,
                        "unreachableMapping",
                        UpdateConstants.BEGIN_UPDATE),
                materialized.getActions());
        assertEquals(set(
                        "5 --control.old--> 9",
                        "5 --observe--> 5",
                        "5 --hotSwapIn--> 104",
                        "5 --hotSwapIn--> 108",
                        "9 --stopOldSpec--> 9",
                        "104 --branch--> 108",
                        "104 --branch--> 112",
                        "104 --control--> 112",
                        "104 --passive--> 108",
                        "108 --reconfigure--> 112",
                        "112 --passive--> 112"),
                transitionSnapshot(materialized, MTS.TransitionType.REQUIRED));
        assertEquals(
                transitionSnapshot(materialized, MTS.TransitionType.REQUIRED),
                transitionSnapshot(materialized, MTS.TransitionType.POSSIBLE));
        assertTrue(transitionSnapshot(
                materialized,
                MTS.TransitionType.MAYBE).isEmpty());
        assertEquals(set(104L, 108L), targets(
                materialized,
                5L,
                UpdateConstants.BEGIN_UPDATE));
        assertEquals(set(108L, 112L), targets(materialized, 104L, "branch"));
        assertFalse(materialized.getActions().contains("oldMaybeOnly"));
        assertFalse(materialized.getActions().contains("mappingMaybeOnly"));
        assertFalse(materialized.getActions().contains("oldAlphabetOnly"));
        assertFalse(materialized.getActions().contains("mappingAlphabetOnly"));

        assertEquals(
                "The read-only view must expose exactly the materialized connected MTS, "
                        + "including numeric state IDs and empty transition relations.",
                canonicalMtsSnapshot(materialized),
                canonicalMtsSnapshot(view));
        assertEquals(materialized.getNumberOfTransitions(), view.getNumberOfTransitions());
        for (MTS.TransitionType type : MTS.TransitionType.values()) {
            assertEquals(
                    "The transition-map state domain must match for " + type,
                    materialized.getTransitions(type).keySet(),
                    view.getTransitions(type).keySet());
        }
        try {
            view.getTransitions(5L, MTS.TransitionType.REQUIRED)
                    .addPair("mutate", 5L);
            fail("The connected-environment view exposed a mutable old-side relation.");
        } catch (UnsupportedOperationException expected) {
            // Expected: the view must be read-only through both MTS and relation APIs.
        }

        CompactState materializedCompact = MTSToAutomataConverter.getInstance().convert(
                materialized,
                "MATERIALIZED_CONNECTED",
                false,
                false);
        CompactState viewCompact = MTSToAutomataConverter.getInstance().convert(
                view,
                "VIEW_CONNECTED",
                false,
                false);
        assertEquals(materializedCompact.maxStates, viewCompact.maxStates);
        assertArrayEquals(
                materializedCompact.getTransitionsLabels(),
                viewCompact.getTransitionsLabels());

        MTS<Long, String> materializedRoundTrip =
                AutomataToMTSConverter.getInstance().convert(materializedCompact);
        MTS<Long, String> viewRoundTrip =
                AutomataToMTSConverter.getInstance().convert(viewCompact);
        assertEquals(
                canonicalMtsSnapshot(materializedRoundTrip),
                canonicalMtsSnapshot(viewRoundTrip));

        assertEquals("Building either representation must not mutate the old input MTS.",
                oldBefore,
                canonicalMtsSnapshot(oldEnvironment));
        assertEquals("Building either representation must not mutate the mapping input MTS.",
                mappingBefore,
                canonicalMtsSnapshot(mappingEnvironment));
    }

    @Test
    public void compactDontDoTwiceEntryMatchesTheMtsEntry() {
        MTS<Long, String> environment = new MTSImpl<Long, String>(0L);
        environment.addActions(set(
                "work",
                UpdateConstants.STOP_OLD_SPEC,
                UpdateConstants.START_NEW_SPEC));
        environment.addRequired(0L, "work", 0L);
        environment.addRequired(0L, UpdateConstants.STOP_OLD_SPEC, 0L);
        environment.addRequired(0L, UpdateConstants.START_NEW_SPEC, 0L);

        MTS<Long, String> throughMts =
                UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(
                        environment,
                        false);
        CompactState compactEnvironment = MTSToAutomataConverter.getInstance().convert(
                environment,
                "DONT_DO_TWICE_INPUT",
                false,
                false);
        MTS<Long, String> throughCompact =
                UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(
                        compactEnvironment,
                        new HashSet<String>(environment.getActions()));

        assertEquals(
                canonicalMtsSnapshot(throughMts),
                canonicalMtsSnapshot(throughCompact));
    }

    private static MTS<Long, String> oneStateEnvironment(String... actions) {
        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        for (String action : actions) {
            result.addAction(action);
            result.addRequired(0L, action, 0L);
        }
        return result;
    }

    private static MTS<Long, String> gatedThirdStage(Set<String> actions) {
        MTS<Long, String> result = new MTSImpl<Long, String>(0L);
        result.addState(1L);
        result.addActions(actions);
        result.addRequired(0L, "a", 0L);
        result.addRequired(1L, "a", 1L);
        result.addRequired(0L, "b", 0L);
        result.addRequired(1L, "b", 1L);
        result.addRequired(0L, "enableC", 1L);
        result.addRequired(1L, "c", 0L);
        return result;
    }

    private static StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv valuedFragment(
            MTS<Long, String> environment,
            Set<Fluent> fluents,
            Set<String> realActions) {
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv valued =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        environment,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        fluents);
        return new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                valued.getEnvironment(),
                valued.getTrackedFluents(),
                valued.getValuation(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                realActions);
    }

    private static StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv emptyValuedFragment(
            MTS<Long, String> environment,
            Set<String> realActions) {
        return new StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv(
                environment,
                Collections.<Fluent>emptySet(),
                new FluentStateValuation<Long>(environment.getStates()),
                Collections.emptyMap(),
                Collections.emptyMap(),
                realActions);
    }

    private static Set<String> semanticSnapshot(
            StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv environment,
            Set<Fluent> fluents) {
        Map<Long, String> stateSignatures = new HashMap<Long, String>();
        for (Long state : environment.getEnvironment().getStates()) {
            stateSignatures.put(state, stateSignature(environment, state, fluents));
        }

        Set<String> snapshot = new TreeSet<String>();
        snapshot.add("INITIAL=" + stateSignatures.get(
                environment.getEnvironment().getInitialState()));
        for (Long state : environment.getEnvironment().getStates()) {
            String source = stateSignatures.get(state);
            snapshot.add("STATE=" + source);
            for (Pair<String, Long> transition : environment.getEnvironment().getTransitions(
                    state, MTS.TransitionType.REQUIRED)) {
                snapshot.add("TRANS=" + source + " --" + transition.getFirst()
                        + "--> " + stateSignatures.get(transition.getSecond()));
            }
        }
        return snapshot;
    }

    private static String stateSignature(
            StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv environment,
            Long state,
            Set<Fluent> fluents) {
        Set<String> values = new TreeSet<String>();
        for (Fluent fluent : fluents) {
            values.add(fluent.getName() + "="
                    + environment.getValuation().isTrue(state, fluent));
        }
        Set<String> enabled = new TreeSet<String>();
        for (Pair<String, Long> transition : environment.getEnvironment().getTransitions(
                state, MTS.TransitionType.REQUIRED)) {
            enabled.add(transition.getFirst());
        }
        return "values=" + values + ";enabled=" + enabled;
    }

    private static int totalTransitionCount(MTS<Long, String> environment) {
        int count = 0;
        for (Long state : environment.getStates()) {
            count += environment.getTransitions(
                    state, MTS.TransitionType.REQUIRED).size();
        }
        return count;
    }

    private static List<String> canonicalMtsSnapshot(MTS<Long, String> environment) {
        List<String> result = new ArrayList<String>();
        result.add("INITIAL=" + environment.getInitialState());

        List<Long> states = new ArrayList<Long>(environment.getStates());
        Collections.sort(states);
        for (Long state : states) {
            result.add("STATE=" + state);
        }

        List<String> actions = new ArrayList<String>(environment.getActions());
        Collections.sort(actions);
        for (String action : actions) {
            result.add("ACTION=" + action);
        }

        for (MTS.TransitionType type : Arrays.asList(
                MTS.TransitionType.REQUIRED,
                MTS.TransitionType.POSSIBLE,
                MTS.TransitionType.MAYBE)) {
            for (Long state : states) {
                result.add("TRANSITION_SET=" + type + "@" + state);
                Set<String> transitions = new TreeSet<String>();
                for (Pair<String, Long> transition : environment.getTransitions(state, type)) {
                    transitions.add(state + " --" + transition.getFirst()
                            + "--> " + transition.getSecond());
                }
                for (String transition : transitions) {
                    result.add("TRANSITION=" + type + ":" + transition);
                }
            }
        }
        return result;
    }

    private static Set<String> transitionSnapshot(
            MTS<Long, String> environment,
            MTS.TransitionType type) {
        Set<String> result = new TreeSet<String>();
        for (Long state : environment.getStates()) {
            for (Pair<String, Long> transition : environment.getTransitions(state, type)) {
                result.add(state + " --" + transition.getFirst()
                        + "--> " + transition.getSecond());
            }
        }
        return result;
    }

    private static Fluent fluent(
            String name,
            boolean initial,
            Set<String> initiatingActions,
            Set<String> terminatingActions) {
        Set<Symbol> initiating = new HashSet<Symbol>();
        for (String action : initiatingActions) {
            initiating.add(new SingleSymbol(action));
        }
        Set<Symbol> terminating = new HashSet<Symbol>();
        for (String action : terminatingActions) {
            terminating.add(new SingleSymbol(action));
        }
        return new FluentImpl(name, initiating, terminating, initial);
    }

    private static long onlyTarget(MTS<Long, String> environment, long state, String action) {
        Set<Long> targets = targets(environment, state, action);
        assertEquals("Expected one target for action " + action + " from state " + state,
                1,
                targets.size());
        return targets.iterator().next();
    }

    private static Set<Long> targets(
            MTS<Long, String> environment,
            long state,
            String action) {
        Set<Long> targets = new LinkedHashSet<Long>();
        for (Pair<String, Long> transition :
                environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst())) {
                targets.add(transition.getSecond());
            }
        }
        return targets;
    }

    private static boolean hasTransition(MTS<Long, String> environment, long state, String action) {
        for (Pair<String, Long> transition :
                environment.getTransitions(state, MTS.TransitionType.REQUIRED)) {
            if (action.equals(transition.getFirst())) {
                return true;
            }
        }
        return false;
    }

    @SafeVarargs
    private static <T> Set<T> set(T... values) {
        return new LinkedHashSet<T>(Arrays.asList(values));
    }
}
