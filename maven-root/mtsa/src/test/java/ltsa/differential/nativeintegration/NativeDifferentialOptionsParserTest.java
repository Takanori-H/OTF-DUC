package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.differential.nativeintegration.NativeSynthesisCapture;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSException;
import ltsa.lts.LTSInputString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Parses real FSP targets without running synthesis or performing snapshot I/O. */
public class NativeDifferentialOptionsParserTest {
    private static final String PREFIX = "E=(a->E | b->F), F=(a->E).\n"
            + "controllerSpec G={controllable={a}}\n";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void prepareResolvesPathWithoutReadingOrWritingIt() throws Exception {
        Path base = temporary.getRoot().toPath();
        CompositeState target = compile(PREFIX
                + "controller || C=(E)~{G} differential {mode=prepare, snapshot=\"absent/old.ds\"}.", "C");
        NativeDifferentialOptions options = target.differentialOptions;
        assertEquals(NativeDifferentialOptions.Mode.PREPARE, options.getMode());
        assertTrue(options.isPrepare());
        assertFalse(options.isUpdate());
        assertEquals(base.resolve("absent/old.ds").toString(), options.getSnapshotPath());
        assertNull(options.getDeltaPath());
        assertNull(options.getOutputPath());
        assertFalse(Files.exists(base.resolve("absent")));
        assertNull(target.differentialCapture);
    }

    @Test public void parsesAllThreeUpdateChoicesAndOptionalOutputSnapshot() throws Exception {
        for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
            CompositeState target = compile(PREFIX + "controller || C=(E)~{G} differential {"
                    + "mode=" + mode + ", snapshot=\"old.ds\", delta=\"changes/add.json\","
                    + "output=\"next.ds\"}.", "C");
            NativeDifferentialOptions options = target.differentialOptions;
            assertEquals(NativeDifferentialOptions.parseMode(mode), options.getMode());
            assertTrue(options.isUpdate());
            assertEquals(temporary.getRoot().toPath().resolve("changes/add.json").toString(),
                    options.getDeltaPath());
            assertEquals(temporary.getRoot().toPath().resolve("next.ds").toString(),
                    options.getOutputPath());
            assertFalse(Files.exists(temporary.getRoot().toPath().resolve("next.ds")));
        }
    }

    @Test public void targetSettingsDoNotLeakAndCloneDoesNotShareCapture() throws Exception {
        synchronized (LTSCompiler.class) {
            LTSCompiler compiler = parser(PREFIX
                    + "controller || C=(E)~{G} differential {mode=prepare,snapshot=\"old.ds\"}.\n"
                    + "controller || NORMAL=(E)~{G}.");
            compiler.compile();
            CompositeState captured = compiler.continueCompilation("C");
            captured.differentialCapture = new NativeSynthesisCapture<Long, String>();
            CompositeState copy = captured.clone();
            assertSame(captured.differentialOptions, copy.differentialOptions);
            assertNull(copy.differentialCapture);
            CompositeState normal = compiler.continueCompilation("NORMAL");
            assertNull(normal.differentialOptions);
            assertNull(normal.differentialCapture);
        }
    }

    @Test public void contextualWordRemainsAnOrdinaryActionName() throws Exception {
        CompositeState target = compile("E=(differential->E).\n"
                + "controllerSpec G={controllable={differential}}\n"
                + "controller || C=(E)~{G}.", "C");
        assertNull(target.differentialOptions);
        assertTrue(java.util.Arrays.asList(target.machines.firstElement().alphabet)
                .contains("differential"));
    }

    @Test public void rejectsUnknownDuplicateAndMissingOptions() throws Exception {
        assertInvalid("mode=other,snapshot=\"old.ds\"", "Unknown differential mode");
        assertInvalid("mode=prepare,mode=prepare,snapshot=\"old.ds\"", "Duplicate");
        assertInvalid("mode=prepare,snapshot=\"old.ds\",extra=\"x\"", "Unknown differential option");
        assertInvalid("snapshot=\"old.ds\"", "mode is required");
        assertInvalid("mode=prepare", "snapshot is required");
        assertInvalid("mode=full,snapshot=\"old.ds\"", "delta is required");
        assertInvalid("mode=prepare,snapshot=\" \"", "snapshot is required");
        assertInvalid("mode=prepare,snapshot=old", "quoted path expected");
        assertInvalid("mode=prepare,snapshot=\"old.ds\",delta=\"d.json\"", "prepare accepts snapshot only");
        assertInvalid("mode=prepare,snapshot=\"old.ds\",output=\"next.ds\"", "prepare accepts snapshot only");
        assertInvalid("mode=prepare,snapshot=\"old.ds\",", "option expected after comma");
    }

    @Test public void rejectsNonControllerAndConflictingOperations() throws Exception {
        assertBadSource(PREFIX + "|| C=(E)~{G} differential {mode=prepare,snapshot=\"s\"}.",
                "require a controller");
        assertBadSource(PREFIX + "controller || C=(E) differential {mode=prepare,snapshot=\"s\"}.",
                "require a controller");
        assertBadSource(PREFIX + "minimal controller || C=(E)~{G} differential {mode=prepare,snapshot=\"s\"}.",
                "cannot be combined");
        assertNull(compile(PREFIX + "controller || C=(E)~{G}.", "C").differentialOptions);
    }

    private void assertInvalid(String body, String message) throws Exception {
        assertBadSource(PREFIX + "controller || C=(E)~{G} differential {" + body + "}.", message);
    }

    private void assertBadSource(String source, String message) throws Exception {
        synchronized (LTSCompiler.class) {
            try {
                parser(source).compile();
                fail("Accepted invalid differential settings: " + source);
            } catch (LTSException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains(message));
            }
        }
    }

    private CompositeState compile(String source, String target) throws Exception {
        synchronized (LTSCompiler.class) {
            LTSCompiler compiler = parser(source);
            compiler.compile();
            return compiler.continueCompilation(target);
        }
    }

    private LTSCompiler parser(String source) {
        return new LTSCompiler(new LTSInputString(source), new EmptyLTSOuput(),
                temporary.getRoot().getAbsolutePath());
    }
}
