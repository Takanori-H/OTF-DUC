package ltsa.lts;

import ltsa.updatingControllers.structures.UpdatingControllerCompositeState;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TraditionalHotSwapMonitorSemanticsConfigurationTest {

    @Test
    public void supportsLtsFlagJavaFallbackAndClonePropagation() {
        // Surefire runs methods in parallel.  LTSA compiler registries and JVM
        // properties are process-global, so keep this characterization atomic
        // with the full-pipeline tests that use the same lock.
        synchronized (LTSCompiler.class) {
            String originalSystemProperty = System.getProperty(
                    UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY);
            try {
                System.clearProperty(
                        UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY);
                lexerRecognisesBareUpdatingControllerFlag();
                parserStoresBareUpdatingControllerFlag();
                systemPropertyDefaultsToTraditionalAndAcceptsNamedModes();
                systemPropertyRejectsUnknownMode();
                onlyStepwiseDelayedCompositeDefaultsToTraditionalAndClonePreservesOverrides();
            } finally {
                restoreProperty(originalSystemProperty);
            }
        }
    }

    private void lexerRecognisesBareUpdatingControllerFlag() {
        Lex lexer = new Lex(new LTSInputString("traditionalHotSwapMonitorSemantics"));

        assertTrue(lexer.next_symbol().kind == Symbol.TRADITIONAL_HOT_SWAP_MONITOR_SEMANTICS);
    }

    private void parserStoresBareUpdatingControllerFlag() {
        LTSCompiler compiler = new LTSCompiler(
                new LTSInputString("updatingController U = {traditionalHotSwapMonitorSemantics}"),
                new EmptyLTSOuput(),
                ".");

        compiler.compile();

        UpdatingControllersDefinition definition =
                (UpdatingControllersDefinition) compiler.getComposites().get("U");
        assertNotNull(definition);
        assertTrue(definition.isTraditionalHotSwapMonitorSemantics());
    }

    private void systemPropertyDefaultsToTraditionalAndAcceptsNamedModes() {
        assertTrue(UpdatingControllersDefinition.traditionalHotSwapMonitorSemanticsFromSystemProperty());

        System.setProperty(UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY, "  ");
        assertTrue(UpdatingControllersDefinition.traditionalHotSwapMonitorSemanticsFromSystemProperty());

        System.setProperty(UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY, "traditional");
        assertTrue(UpdatingControllersDefinition.traditionalHotSwapMonitorSemanticsFromSystemProperty());

        System.setProperty(UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY, "stutter");
        assertFalse(UpdatingControllersDefinition.traditionalHotSwapMonitorSemanticsFromSystemProperty());
    }

    private void systemPropertyRejectsUnknownMode() {
        System.setProperty(UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY, "unknown");
        try {
            UpdatingControllersDefinition.traditionalHotSwapMonitorSemanticsFromSystemProperty();
            fail("Unknown monitor mode must be rejected");
        } catch (LTSException expected) {
            // Expected.
        } finally {
            System.clearProperty(
                    UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY);
        }
    }

    private void onlyStepwiseDelayedCompositeDefaultsToTraditionalAndClonePreservesOverrides() {
        UpdatingControllerCompositeState ordinaryStepwise =
                new UpdatingControllerCompositeState(
                        null,
                        "OldCon",
                        Collections.emptyList(),
                        null,
                        null,
                        Collections.emptyList(),
                        null,
                        Collections.emptySet(),
                        "U");
        assertFalse(ordinaryStepwise.isTraditionalHotSwapMonitorSemantics());

        UpdatingControllerCompositeState state = UpdatingControllerCompositeState.stepwiseDelayed(
                null,
                "OldCon",
                Collections.emptyList(),
                null,
                null,
                Collections.emptyList(),
                null,
                Collections.emptySet(),
                "U",
                false,
                false);
        assertTrue(state.isTraditionalHotSwapMonitorSemantics());

        state.setTraditionalHotSwapMonitorSemantics(false);
        assertFalse(state.clone().isTraditionalHotSwapMonitorSemantics());

        state.setTraditionalHotSwapMonitorSemantics(true);
        assertTrue(state.clone().isTraditionalHotSwapMonitorSemantics());
    }

    private static void restoreProperty(String value) {
        if (value == null) {
            System.clearProperty(
                    UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY);
        } else {
            System.setProperty(
                    UpdatingControllersDefinition.HOT_SWAP_MONITOR_SEMANTICS_PROPERTY,
                    value);
        }
    }
}
