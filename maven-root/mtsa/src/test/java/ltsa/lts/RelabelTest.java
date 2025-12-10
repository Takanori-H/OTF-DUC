package ltsa.lts;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Vector;
import java.lang.Throwable;
import org.junit.Test;

import FSP2MTS.ac.ic.doc.mtstools.test.util.TestLTSOuput;
import IntegrationTests.LTSTestsUtils;
import MTSTools.ac.ic.doc.commons.relations.BinaryRelation;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.SemanticType;
import junit.framework.TestCase;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.ui.RefinementOptions;

public class RelabelTest extends TestCase {
	

	@Test
	public void testBisimilar() throws IOException {
		String resourceFolder = "./Relabel";
		String fileInput = "bisimilar.lts";
        File ltsFile = LTSTestsUtils.getFile(resourceFolder, fileInput);
        LTSTestsUtils myUtils = new LTSTestsUtils(ltsFile);
        FileInput lts = new FileInput(ltsFile);

        LTSOutput output = new TestLTSOuput();
        LTSCompiler compiled = myUtils.compileWithLtsCompiler(lts, output);
        
        System.out.println(compiled.getCompiled());
        CompositeState compositeState = compiled.continueCompilation("C"); //if no C exists then "Default" will be use
        CompactState c =compositeState.machines.get(0); // compiled.getCompiled().get("C");
        CompactState b = compositeState.machines.get(1);
        
        //TransitionSystemDispatcher.applyComposition(compositeState, output);
        
        BinaryRelation<?, ?> ref = TransitionSystemDispatcher.getRefinement(
        		c,
        		b,
        		SemanticType.WEAK, output);
        
        assertNotNull(ref);
        //TransitionSystemDispatcher.isWeaklyBisimilar(null, resourceFolder, null, fileInput, output)
	}

	public void testProgress() throws IOException {
	        String resourceFolder = "./Relabel";
	        String fileInput = "museum_progress.lts";
	        File ltsFile = LTSTestsUtils.getFile(resourceFolder, fileInput);
	        LTSTestsUtils myUtils = new LTSTestsUtils(ltsFile);
	        FileInput lts = new FileInput(ltsFile);

	        LTSOutput output = new TestLTSOuput();
	        LTSCompiler compiled = myUtils.compileWithLtsCompiler(lts, output);
	        
	        System.out.println(compiled.getCompiled());
	        CompositeState compositeState = compiled.continueCompilation("LCR"); //if no C exists then "Default" will be use
	        
	        TransitionSystemDispatcher.applyComposition(compositeState, output);

	        boolean progress = compositeState.checkProgress(output);
	        
	        assertTrue(progress);

	    }
	

}
