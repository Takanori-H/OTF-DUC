package ltsa.differential.nativeintegration;

import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class NativeDifferentialGuardTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void inheritedGlobalReductionAndTruncationCannotChangeNativeSemantics() throws Exception {
        synchronized (LTSCompiler.class) {
            boolean por = Analyser.partialOrderReduction;
            long cap = Options.getMaxStatesGeneration();
            try {
                Analyser.partialOrderReduction = true;
                rejected(target());
                Analyser.partialOrderReduction = false;
                Options.setMaxStatesGeneration(2);
                rejected(target());
            } finally {
                Analyser.partialOrderReduction = por;
                Options.setMaxStatesGeneration(cap);
            }
        }
    }

    @Test public void programmaticOperationsAreRejectedInsteadOfBeingDroppedBySessionCopy() throws Exception {
        synchronized (LTSCompiler.class) {
            for (Consumer<CompositeState> change : Arrays.<Consumer<CompositeState>>asList(
                    c -> c.isMonolithicDirector = true,
                    c -> c.isPartialOrderReduction = true,
                    c -> c.checkCompatible = true,
                    c -> c.setMakeComponent(true),
                    c -> c.setCompositionType(Symbol.MERGE),
                    c -> c.setCompositionType(Symbol.PLUS_CR),
                    c -> c.setCompositionType(Symbol.UPDATING_CONTROLLER))) {
                CompositeState c = target();
                change.accept(c);
                rejected(c);
            }
        }
    }

    private CompositeState target() {
        LTSCompiler compiler = new LTSCompiler(new LTSInputString("E=(a->E).\n"
                + "controllerSpec G={controllable={a}}\n"
                + "controller || C=(E)~{G} differential {mode=prepare,snapshot=\"initial.ds\"}."),
                new EmptyLTSOuput(), temporary.getRoot().getAbsolutePath());
        compiler.compile();
        return compiler.continueCompilation("C");
    }

    private void rejected(CompositeState target) {
        try {
            TransitionSystemDispatcher.applyComposition(target, new EmptyLTSOuput());
            fail("Unsupported operation was silently dropped");
        } catch (IllegalArgumentException expected) { }
        assertNull(target.differentialResult);
        assertNull(target.differentialCapture);
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("initial.ds")));
    }
}
