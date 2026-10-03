package ltsa.updatingControllers.stepwise.delayed;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.condition.FluentPropositionalVariable;
import MTSSynthesis.ar.dc.uba.model.condition.Formula;
import MTSSynthesis.ar.dc.uba.model.condition.OrFormula;
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
import ltsa.updatingControllers.stepwise.StepwiseClassifiedGoal;
import ltsa.updatingControllers.stepwise.StepwiseRequirementKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
    public void ownerAwareProductInterleavesRequiredTauTransitions() {
        MTS<Long, String> first = new MTSImpl<Long, String>(0L);
        first.addState(1L);
        first.addActions(set(MTSConstants.TAU, "firstReady"));
        first.addRequired(0L, MTSConstants.TAU, 1L);
        first.addRequired(1L, "firstReady", 1L);

        MTS<Long, String> second = new MTSImpl<Long, String>(0L);
        second.addState(1L);
        second.addActions(set(MTSConstants.TAU, "secondReady"));
        second.addRequired(0L, MTSConstants.TAU, 1L);
        second.addRequired(1L, "secondReady", 1L);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                emptyValuedFragment(first, Collections.singleton("firstReady")),
                                emptyValuedFragment(second, Collections.singleton("secondReady"))),
                        "TAU_INTERLEAVING_TEST",
                        NO_OUTPUT);

        long initial = product.getEnvironment().getInitialState();
        Set<Long> tauTargets = targets(
                product.getEnvironment(), initial, MTSConstants.TAU);
        assertEquals("Each component tau must interleave instead of synchronizing.",
                2,
                tauTargets.size());

        int firstOnly = 0;
        int secondOnly = 0;
        for (Long target : tauTargets) {
            boolean firstEnabled = hasTransition(
                    product.getEnvironment(), target, "firstReady");
            boolean secondEnabled = hasTransition(
                    product.getEnvironment(), target, "secondReady");
            if (firstEnabled && !secondEnabled) {
                firstOnly++;
            } else if (!firstEnabled && secondEnabled) {
                secondOnly++;
            }
        }
        assertEquals(1, firstOnly);
        assertEquals(1, secondOnly);
    }

    @Test
    public void reservedTauAlphabetDoesNotBlockAnotherComponentsTau() {
        MTS<Long, String> moving = new MTSImpl<Long, String>(0L);
        moving.addState(1L);
        moving.addAction(MTSConstants.TAU);
        moving.addRequired(0L, MTSConstants.TAU, 1L);

        MTS<Long, String> observing = new MTSImpl<Long, String>(0L);
        observing.addAction(MTSConstants.TAU);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.composeProduct(
                        Arrays.asList(
                                emptyValuedFragment(moving, Collections.<String>emptySet()),
                                emptyValuedFragment(observing, Collections.<String>emptySet())),
                        "TAU_RESERVED_ALPHABET_TEST",
                        NO_OUTPUT);

        assertEquals(1,
                targets(
                        product.getEnvironment(),
                        product.getEnvironment().getInitialState(),
                        MTSConstants.TAU).size());
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
    public void fluentProductTracksAFluentBeyondTheFirstSixtyFourBits() {
        MTS<Long, String> environment = oneStateEnvironment("setHigh", "clearHigh");
        Set<Fluent> fluents = new LinkedHashSet<Fluent>();
        for (int index = 0; index < 64; index++) {
            fluents.add(fluent(
                    "inert" + index,
                    false,
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet()));
        }
        Fluent highBit = fluent(
                "highBit",
                false,
                Collections.singleton("setHigh"),
                Collections.singleton("clearHigh"));
        fluents.add(highBit);

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        environment,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        fluents);

        long initial = product.getEnvironment().getInitialState();
        long high = onlyTarget(product.getEnvironment(), initial, "setHigh");
        assertNotEquals("The 65th fluent must distinguish product states.", initial, high);
        assertFalse(product.getValuation().isTrue(initial, highBit));
        assertTrue(product.getValuation().isTrue(high, highBit));
        assertEquals(initial, onlyTarget(product.getEnvironment(), high, "clearHigh"));
        assertEquals(high, onlyTarget(product.getEnvironment(), high, "setHigh"));
    }

    @Test
    public void fluentProductNormalizesOldActionLabelsBeforeUpdatingFluents() {
        MTS<Long, String> environment = oneStateEnvironment(
                "activate" + UpdateConstants.OLD_LABEL,
                "reset" + UpdateConstants.OLD_LABEL);
        Fluent active = fluent(
                "active",
                false,
                Collections.singleton("activate"),
                Collections.singleton("reset"));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv product =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        environment,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        Collections.singleton(active));

        long initial = product.getEnvironment().getInitialState();
        long activeState = onlyTarget(
                product.getEnvironment(),
                initial,
                "activate" + UpdateConstants.OLD_LABEL);
        assertTrue(product.getValuation().isTrue(activeState, active));
        assertEquals(initial, onlyTarget(
                product.getEnvironment(),
                activeState,
                "reset" + UpdateConstants.OLD_LABEL));
    }

    @Test
    public void onlinePostConnectionTerminalizationMatchesBuildThenPruneReference() {
        MTS<Long, String> connected = new MTSImpl<Long, String>(0L);
        connected.addState(1L);
        connected.addState(2L);
        connected.addState(3L);
        connected.addState(4L);
        connected.addActions(set("arm", "unsafe", "escape", "bypass"));
        connected.addRequired(0L, "arm", 1L);
        connected.addRequired(1L, "unsafe", 2L);
        connected.addRequired(2L, "escape", 4L);
        connected.addRequired(1L, "bypass", 3L);

        Fluent unsafe = fluent(
                "unsafe",
                false,
                Collections.singleton("unsafe"),
                Collections.<String>emptySet());
        Set<Fluent> fluents = Collections.singleton(unsafe);
        List<StepwiseClassifiedGoal> goals = Collections.singletonList(
                classifiedGoal(
                        "T_UNSAFE",
                        new FluentPropositionalVariable(unsafe),
                        fluents));

        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv fullProduct =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        connected,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        fluents);
        MTS<Long, String> reference = terminalizeReference(fullProduct, goals);
        MTS<Long, String> online =
                StepwiseDelayedUpdatingControllerSynthesizer
                        .buildPostConnectionMonitorProduct(
                                connected,
                                fluents,
                                goals,
                                "ONLINE_TERMINALIZATION_EQUIVALENCE_TEST",
                                NO_OUTPUT);

        assertEquals(canonicalMtsSnapshot(reference), canonicalMtsSnapshot(online));
        long armed = onlyTarget(online, online.getInitialState(), "arm");
        long violation = onlyTarget(online, armed, "unsafe");
        assertEquals("A violating state is retained as a terminal target.",
                0,
                online.getTransitions(violation, MTS.TransitionType.REQUIRED).size());
        assertEquals("A state with a safe alternate path must remain reachable.",
                1,
                targets(online, armed, "bypass").size());
        assertEquals("Only the state reachable exclusively beyond the violation is omitted.",
                4,
                online.getStates().size());
    }

    @Test
    public void onlinePostConnectionProductSupportsMoreThanSixtyFourFluents() {
        MTS<Long, String> connected = new MTSImpl<Long, String>(0L);
        Set<Fluent> fluents = new LinkedHashSet<Fluent>();
        Formula anySet = Formula.FALSE_FORMULA;
        for (int index = 0; index < 65; index++) {
            String action = "set" + index;
            connected.addAction(action);
            connected.addRequired(0L, action, 0L);
            Fluent fluent = fluent(
                    "bit" + index,
                    false,
                    Collections.singleton(action),
                    Collections.<String>emptySet());
            fluents.add(fluent);
            anySet = new OrFormula(anySet, new FluentPropositionalVariable(fluent));
        }
        List<StepwiseClassifiedGoal> goals = Collections.singletonList(
                classifiedGoal("T_ANY_BIT", anySet, fluents));

        MTS<Long, String> online =
                StepwiseDelayedUpdatingControllerSynthesizer
                        .buildPostConnectionMonitorProduct(
                                connected,
                                fluents,
                                goals,
                                "ONLINE_MORE_THAN_64_FLUENTS_TEST",
                                NO_OUTPUT);

        long initial = online.getInitialState();
        Set<Long> terminalTargets = new HashSet<Long>();
        for (int index = 0; index < 65; index++) {
            long target = onlyTarget(online, initial, "set" + index);
            terminalTargets.add(target);
            assertEquals("Every one-bit valuation is violating and terminal.",
                    0,
                    online.getTransitions(target, MTS.TransitionType.REQUIRED).size());
        }
        assertEquals("Every fluent bit, including bits above 63, must remain distinct.",
                65,
                terminalTargets.size());
        assertEquals(66, online.getStates().size());
        assertEquals(65, totalTransitionCount(online));
    }

    @Test
    public void onlinePostConnectionProductNormalizesOldLabelsBeforeViolationCheck() {
        MTS<Long, String> connected = new MTSImpl<Long, String>(0L);
        connected.addState(1L);
        connected.addState(2L);
        connected.addActions(set(
                "activate" + UpdateConstants.OLD_LABEL,
                "after"));
        connected.addRequired(
                0L,
                "activate" + UpdateConstants.OLD_LABEL,
                1L);
        connected.addRequired(1L, "after", 2L);
        Fluent active = fluent(
                "active",
                false,
                Collections.singleton("activate"),
                Collections.<String>emptySet());
        Set<Fluent> fluents = Collections.singleton(active);

        MTS<Long, String> online =
                StepwiseDelayedUpdatingControllerSynthesizer
                        .buildPostConnectionMonitorProduct(
                                connected,
                                fluents,
                                Collections.singletonList(classifiedGoal(
                                        "T_ACTIVE",
                                        new FluentPropositionalVariable(active),
                                        fluents)),
                                "ONLINE_OLD_LABEL_NORMALIZATION_TEST",
                                NO_OUTPUT);

        long violation = onlyTarget(
                online,
                online.getInitialState(),
                "activate" + UpdateConstants.OLD_LABEL);
        assertEquals(0, online.getTransitions(
                violation, MTS.TransitionType.REQUIRED).size());
        assertEquals("The successor after the normalized-action violation is unreachable.",
                2,
                online.getStates().size());
    }

    @Test
    public void onlinePostConnectionProductAppliesInitiationAfterTermination() {
        MTS<Long, String> connected = oneStateEnvironment(
                "toggle" + UpdateConstants.OLD_LABEL);
        Fluent active = fluent(
                "active",
                false,
                Collections.singleton("toggle"),
                Collections.singleton("toggle"));
        Set<Fluent> fluents = Collections.singleton(active);

        MTS<Long, String> online =
                StepwiseDelayedUpdatingControllerSynthesizer
                        .buildPostConnectionMonitorProduct(
                                connected,
                                fluents,
                                Collections.singletonList(classifiedGoal(
                                        "T_INITIATION_WINS",
                                        new FluentPropositionalVariable(active),
                                        fluents)),
                                "ONLINE_INITIATION_AFTER_TERMINATION_TEST",
                                NO_OUTPUT);

        long violation = onlyTarget(
                online,
                online.getInitialState(),
                "toggle" + UpdateConstants.OLD_LABEL);
        assertEquals("A fluent initiated and terminated by the same action must end true.",
                0,
                online.getTransitions(violation, MTS.TransitionType.REQUIRED).size());
    }

    @Test
    public void onlinePostConnectionProductRejectsAnInitiallyViolatingMonitor() {
        MTS<Long, String> connected = oneStateEnvironment("work");
        Fluent initiallyUnsafe = fluent(
                "initiallyUnsafe",
                true,
                Collections.<String>emptySet(),
                Collections.<String>emptySet());
        Set<Fluent> fluents = Collections.singleton(initiallyUnsafe);

        try {
            StepwiseDelayedUpdatingControllerSynthesizer
                    .buildPostConnectionMonitorProduct(
                            connected,
                            fluents,
                            Collections.singletonList(classifiedGoal(
                                    "T_INITIAL",
                                    new FluentPropositionalVariable(initiallyUnsafe),
                                    fluents)),
                            "ONLINE_INITIAL_VIOLATION_TEST",
                            NO_OUTPUT);
            fail("An initially violating post-connection monitor must be rejected.");
        } catch (LTSException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(
                    "initial state violating"));
        }
    }

    @Test
    public void onlinePostConnectionProductTerminalizesEveryOneToManyTarget() {
        MTS<Long, String> connected = new MTSImpl<Long, String>(0L);
        connected.addState(1L);
        connected.addState(2L);
        connected.addState(3L);
        connected.addState(4L);
        connected.addActions(set(
                UpdateConstants.BEGIN_UPDATE,
                "leftAfter",
                "rightAfter"));
        connected.addRequired(0L, UpdateConstants.BEGIN_UPDATE, 1L);
        connected.addRequired(0L, UpdateConstants.BEGIN_UPDATE, 2L);
        connected.addRequired(1L, "leftAfter", 3L);
        connected.addRequired(2L, "rightAfter", 4L);
        Fluent hotSwapSeen = fluent(
                "hotSwapSeen",
                false,
                Collections.singleton(UpdateConstants.BEGIN_UPDATE),
                Collections.<String>emptySet());
        Set<Fluent> fluents = Collections.singleton(hotSwapSeen);

        MTS<Long, String> online =
                StepwiseDelayedUpdatingControllerSynthesizer
                        .buildPostConnectionMonitorProduct(
                                connected,
                                fluents,
                                Collections.singletonList(classifiedGoal(
                                        "T_HOT_SWAP",
                                        new FluentPropositionalVariable(hotSwapSeen),
                                        fluents)),
                                "ONLINE_ONE_TO_MANY_TEST",
                                NO_OUTPUT);

        Set<Long> targets = targets(
                online,
                online.getInitialState(),
                UpdateConstants.BEGIN_UPDATE);
        assertEquals("Both one-to-many connection targets must be retained.", 2, targets.size());
        for (Long target : targets) {
            assertEquals("Every violating connection target must be terminal.",
                    0,
                    online.getTransitions(target, MTS.TransitionType.REQUIRED).size());
        }
        assertEquals("Neither branch beyond a violating connection may be generated.",
                3,
                online.getStates().size());
    }

    @Test
    public void postHotSwapProductUpdatesEveryOneToManyTargetValuation() {
        MTS<Long, String> oldEnvironment = new MTSImpl<Long, String>(0L);
        oldEnvironment.addAction("a");
        oldEnvironment.addRequired(0L, "a", 0L);

        MTS<Long, String> mappingEnvironment = new MTSImpl<Long, String>(0L);
        mappingEnvironment.addState(1L);
        List<StepwiseDelayedUpdatingControllerSynthesizer.Connection> connections =
                Arrays.asList(
                        new StepwiseDelayedUpdatingControllerSynthesizer.Connection(0L, 0L),
                        new StepwiseDelayedUpdatingControllerSynthesizer.Connection(0L, 1L));
        MTS<Long, String> connected =
                StepwiseDelayedUpdatingControllerSynthesizer.connectOldAndMappingView(
                        oldEnvironment,
                        mappingEnvironment,
                        connections,
                        Collections.singleton("a"));

        Fluent seenA = fluent(
                "SeenA",
                false,
                Collections.singleton("a"),
                Collections.singleton(UpdateConstants.STOP_OLD_SPEC));
        Fluent resetOnHotSwap = fluent(
                "ResetOnHotSwap",
                false,
                Collections.singleton("a"),
                Collections.singleton(UpdateConstants.BEGIN_UPDATE));
        Set<Fluent> monitorFluents = set(seenA, resetOnHotSwap);
        StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv monitored =
                StepwiseDelayedUpdatingControllerSynthesizer.buildFluentProduct(
                        connected,
                        Collections.emptyMap(),
                        Collections.emptyMap(),
                        monitorFluents);

        Long afterA = onlyTarget(
                monitored.getEnvironment(),
                monitored.getEnvironment().getInitialState(),
                "a" + UpdateConstants.OLD_LABEL);
        Set<Long> postHotSwapTargets = new HashSet<Long>();
        for (Pair<String, Long> transition : monitored.getEnvironment().getTransitions(
                afterA,
                MTS.TransitionType.REQUIRED)) {
            if (UpdateConstants.BEGIN_UPDATE.equals(transition.getFirst())) {
                postHotSwapTargets.add(transition.getSecond());
            }
        }

        assertEquals("Both one-to-many mapping targets must survive the monitor product.",
                2,
                postHotSwapTargets.size());
        for (Long target : postHotSwapTargets) {
            assertTrue(monitored.getValuation().isTrue(target, seenA));
            assertFalse(monitored.getValuation().isTrue(target, resetOnHotSwap));
        }
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
                "unreachableOld",
                MTSConstants.TAU));
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
                UpdateConstants.START_NEW_SPEC,
                "mappingMaybeOnly",
                "mappingAlphabetOnly",
                "unreachableMapping",
                MTSConstants.TAU));
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
                "oldAlphabetOnly",
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
                        "oldAlphabetOnly" + UpdateConstants.OLD_LABEL,
                        "observe",
                        UpdateConstants.STOP_OLD_SPEC,
                        "oldMaybeOnly",
                        "unreachableOld",
                        "control",
                        "passive",
                        "branch",
                        UpdateConstants.RECONFIGURE,
                        UpdateConstants.START_NEW_SPEC,
                        "mappingMaybeOnly",
                        "mappingAlphabetOnly",
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
        assertTrue(materialized.getActions().contains("oldMaybeOnly"));
        assertTrue(materialized.getActions().contains("mappingMaybeOnly"));
        assertTrue(materialized.getActions().contains(
                "oldAlphabetOnly" + UpdateConstants.OLD_LABEL));
        assertFalse(materialized.getActions().contains("oldAlphabetOnly"));
        assertTrue(materialized.getActions().contains("mappingAlphabetOnly"));
        assertTrue(materialized.getActions().contains(UpdateConstants.START_NEW_SPEC));
        for (Long state : materialized.getStates()) {
            assertFalse("An alphabet-only startNewSpec must not gain an edge during connection.",
                    hasTransition(materialized, state, UpdateConstants.START_NEW_SPEC));
        }
        assertFalse("Reserved alphabet-only tau must remain excluded.",
                materialized.getActions().contains(MTSConstants.TAU));

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

    @Test
    public void dontDoTwiceDoesNotIntroduceTransitionsMissingFromTheBase() {
        MTS<Long, String> environment = new MTSImpl<Long, String>(0L);
        environment.addState(1L);
        environment.addActions(set(
                "arm",
                UpdateConstants.STOP_OLD_SPEC,
                UpdateConstants.START_NEW_SPEC));
        environment.addRequired(0L, "arm", 1L);
        environment.addRequired(1L, UpdateConstants.STOP_OLD_SPEC, 1L);

        CompactState compactEnvironment = MTSToAutomataConverter.getInstance().convert(
                environment,
                "DONT_DO_TWICE_PROJECTION_INPUT",
                false,
                false);
        MTS<Long, String> decorated =
                UpdatingControllerSafetySynthesizer.getDontDoTwiceGoals(
                        compactEnvironment,
                        new HashSet<String>(environment.getActions()));

        long initial = decorated.getInitialState();
        assertFalse("stopOldSpec is disabled in the base initial state.",
                hasTransition(decorated, initial, UpdateConstants.STOP_OLD_SPEC));
        assertFalse("An alphabet-only startNewSpec must not become a transition.",
                hasTransition(decorated, initial, UpdateConstants.START_NEW_SPEC));

        long armed = onlyTarget(decorated, initial, "arm");
        assertTrue("A base stopOldSpec transition must remain available.",
                hasTransition(decorated, armed, UpdateConstants.STOP_OLD_SPEC));
        for (Long state : decorated.getStates()) {
            assertFalse("The safety decoration must not introduce startNewSpec.",
                    hasTransition(decorated, state, UpdateConstants.START_NEW_SPEC));
        }
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

    private static MTS<Long, String> terminalizeReference(
            StepwiseDelayedUpdatingControllerSynthesizer.DelayedEnv monitored,
            Collection<StepwiseClassifiedGoal> goals) {
        Set<Long> violating = new HashSet<Long>();
        for (StepwiseClassifiedGoal goal : goals) {
            for (Long state : monitored.getEnvironment().getStates()) {
                monitored.getValuation().setActualState(state);
                if (goal.getFormula().evaluate(monitored.getValuation())) {
                    violating.add(state);
                }
            }
        }

        MTS<Long, String> result = new MTSImpl<Long, String>(
                monitored.getEnvironment().getInitialState());
        result.addActions(monitored.getEnvironment().getActions());
        for (Long state : monitored.getEnvironment().getStates()) {
            result.addState(state);
        }
        for (Long state : monitored.getEnvironment().getStates()) {
            if (violating.contains(state)) {
                continue;
            }
            for (Pair<String, Long> transition : monitored.getEnvironment().getTransitions(
                    state, MTS.TransitionType.REQUIRED)) {
                result.addRequired(state, transition.getFirst(), transition.getSecond());
            }
        }
        result.removeUnreachableStates();
        return result;
    }

    private static StepwiseClassifiedGoal classifiedGoal(
            String name,
            Formula formula,
            Set<Fluent> fluents) {
        return new StepwiseClassifiedGoal(
                new ltsa.lts.Symbol(ltsa.lts.Symbol.UPPERIDENT, name),
                StepwiseRequirementKind.TRANSITION,
                0,
                Collections.singleton(0),
                "test",
                formula,
                fluents,
                Collections.<Fluent>emptySet());
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
