package ltsa.updatingControllers.cli;

import ltsa.lts.LTSCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Regression coverage for state-local relation pre-action conflicts. */
public class MappingRelationPreActionConflictIntegrationTest {

    private static final String MODEL_PATH =
            "src/test/resources/StepwiseDUCS/MappingRelationPreActionConflict.lts";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test(timeout = 120000)
    public void rejectsOnlyAConflictAtTheRelationRulesOwnOldState() throws Exception {
        synchronized (LTSCompiler.class) {
            RunResult conflict = run("CONFLICTING_MAPPING");
            assertEquals(SingleCompositionRunner.EXIT_EXCEPTION, conflict.exitCode);
            assertEquals(0L, conflict.transitions.length());
            assertTrue(conflict.log,
                    conflict.log.contains("Relation pre-action 'a' conflicts with an "
                            + "old-environment transition from state 'CONFLICT_ROOT'"));

            RunResult accepted = run("NONCONFLICTING_MAPPING");
            assertEquals(accepted.log, SingleCompositionRunner.EXIT_SUCCESS, accepted.exitCode);
            assertTrue("The non-conflicting mapping was not written.",
                    accepted.transitions.length() > 0L);
            String acceptedTransitions = new String(
                    Files.readAllBytes(accepted.transitions.toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(acceptedTransitions, acceptedTransitions.contains("x ->"));
            assertTrue(acceptedTransitions, acceptedTransitions.contains("y ->"));
            assertTrue(acceptedTransitions, acceptedTransitions.contains("reconfigure ->"));

            RunResult postAction = run("POSTACTION_MAPPING");
            assertEquals(postAction.log, SingleCompositionRunner.EXIT_SUCCESS, postAction.exitCode);
            String postActionTransitions = new String(
                    Files.readAllBytes(postAction.transitions.toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(postActionTransitions, postActionTransitions.contains("x ->"));
            assertTrue(postActionTransitions, postActionTransitions.contains("reconfigure ->"));
            assertTrue(postActionTransitions, postActionTransitions.contains("a ->"));
            assertTrue(postActionTransitions, postActionTransitions.contains("postDone ->"));

            RunResult directReconfigure = run("DIRECT_RECONFIGURE_MAPPING");
            assertEquals(directReconfigure.log,
                    SingleCompositionRunner.EXIT_SUCCESS,
                    directReconfigure.exitCode);
            String directTransitions = new String(
                    Files.readAllBytes(directReconfigure.transitions.toPath()),
                    StandardCharsets.UTF_8);
            assertTrue(directTransitions, directTransitions.contains("reconfigure ->"));

            RunResult forallConflict = run("FORALL_CONFLICTING_MAPPING");
            assertEquals(SingleCompositionRunner.EXIT_EXCEPTION, forallConflict.exitCode);
            assertEquals(0L, forallConflict.transitions.length());
            assertTrue(forallConflict.log,
                    forallConflict.log.contains("Relation pre-action 'collide.1' conflicts with an "
                            + "old-environment transition from state 'FORALL_ROOT.1'"));
        }
    }

    private RunResult run(String target) throws Exception {
        File model = new File(MODEL_PATH);
        File log = temporaryFolder.newFile(target + ".log");
        File transitions = temporaryFolder.newFile(target + ".lts");
        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", log.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });
        return new RunResult(
                exitCode,
                new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8),
                transitions);
    }

    private static final class RunResult {
        private final int exitCode;
        private final String log;
        private final File transitions;

        private RunResult(int exitCode, String log, File transitions) {
            this.exitCode = exitCode;
            this.log = log;
            this.transitions = transitions;
        }
    }
}
