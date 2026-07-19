package ltsa.updatingControllers.cli;

import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionRangeStepwiseDelayedIntegrationTest {

    @BeforeClass
    public static void runWithoutInitializingTheDesktopToolkit() {
        System.setProperty("java.awt.headless", "true");
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void composesUpdatingControllersWithMultiIndexActionRange() throws Exception {
        File model = new File(
                "MODEL/StepwiseDUCS/SmallStepwiseDelayedActionRangeExample.lts");
        assertTrue("Regression model is missing: " + model.getAbsolutePath(), model.isFile());

        assertCompositionSucceeds(model, "UPDATE_CONTROLLER");
        assertCompositionSucceeds(model, "UPDATE_CONTROLLER_SBP");
        assertCompositionSucceeds(model, "STEPWISE_DELAYED_UPDATE_CONTROLLER");
        assertCompositionSucceeds(model, "STEPWISE_DELAYED_UPDATE_CONTROLLER_SBP");
    }

    private void assertCompositionSucceeds(File model, String target) throws Exception {
        File output = temporaryFolder.newFile(target + ".log");
        File transitions = temporaryFolder.newFile(target + ".aut");

        int exitCode = SingleCompositionRunner.runMain(new String[] {
                "--lts", model.getAbsolutePath(),
                "--target", target,
                "--output", output.getAbsolutePath(),
                "--transitions", transitions.getAbsolutePath()
        });

        assertEquals("Composition failed; see " + output.getAbsolutePath(),
                SingleCompositionRunner.EXIT_SUCCESS, exitCode);
        assertTrue("No transitions were written for " + target, transitions.length() > 0);

        String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        assertFalse(log, log.contains("GOAL_ACTION_NOT_FOUND"));
        assertFalse(log, log.contains("Composition not controllable"));
        if (target.startsWith("STEPWISE_DELAYED")) {
            assertTrue(log, log.contains(
                    "P_OLD_RANGE_REFERENCE (OLD_SAFETY) -> stage 1"));
        }
    }
}
