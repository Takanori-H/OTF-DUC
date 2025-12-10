package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import ltsa.lts.*;
import org.junit.Test;
import ltsa.lts.LTSOutput;

import java.io.File;
import java.util.*;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.CompositionalApproach;
import static org.junit.Assert.assertTrue;


public class TestSynthesisEquivalence {

    private File ltsFile;



    /*
    @Parameterized.Parameters(name = "{index}: {0}")
    public static List<File> controllerFiles() throws IOException {
        List<File> allFiles = LTSTestsUtils.getFiles(resourceFolder);
        return allFiles;
    }

     */


    @Test
    public void testPathsBetweenStates() throws Exception {

        Long initialState = Long.valueOf(0);
        MTSImpl<Long, String> lts = new MTSImpl<Long, String>(initialState);

        // alphabet
        Set<String> actions = new HashSet<>();
        actions.add("a");
        actions.add("b");
        actions.add("c");
        lts.addActions(actions);

        prepareLTS(lts);

        LTSOutput output = new EmptyLTSOuput();
        HashSet<String> controllableLabels = new HashSet<>();
        // controllableLabels.add("a");


        Vector<String> allLabels = new Vector<>(actions);
        Vector<String> localLabels = new Vector<>();
        localLabels.add("a");
        localLabels.add("c");
        Pair<Vector<String>, Vector<String>> labels = new Pair<Vector<String>, Vector<String>>(allLabels, localLabels);

        CompositionalApproach compositionalapproach = new CompositionalApproach();

        System.currentTimeMillis();
        return;


    }

    private void prepareLTS(MTSImpl<Long, String> lts) {
        // adding states
        lts.addState(1L);
        lts.addState(2L);
        lts.addState(3L);
        lts.addState(4L);
        lts.addState(5L);
        lts.addState(6L);
        lts.addState(7L);

        // adding transitions
        lts.addRequired(0L, "a", 1L);
        lts.addRequired(0L, "c", 2L);
        lts.addRequired(0L, "b", 6L);
        lts.addRequired(6L, "c", 1L);

        lts.addRequired(1L, "a", 7L);

        lts.addRequired(2L, "a", 6L);
        lts.addRequired(2L, "b", 3L);

        lts.addRequired(3L, "a", 4L);

        lts.addRequired(4L, "c", 5L);
        lts.addRequired(4L, "b", 6L);

        lts.addRequired(7L, "b", 6L);
        lts.addRequired(7L, "c", 5L);
    }

}



