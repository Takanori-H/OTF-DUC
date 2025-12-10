package ltsa.ui;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.*;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.Statistics;
import jargs.gnu.CmdLineParser;
import jargs.gnu.CmdLineParser.Option;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.*;

import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisNonBlocking;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.DirectedControllerSynthesisGR1;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.nonblocking.DirectedControllerSynthesisNonBlocking.HeuristicMode;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.*;

public class LTSABatch
implements LTSManager, LTSInput, LTSOutput, LTSError {

	CompositeState current = null;
	Set<String> compositeNames = new HashSet<String>();
	String currentDirectory = System.getProperty("user.dir");
	Hashtable<String,LabelSet> labelSetConstants = null;
//	SETCompositionalBatch compositionBatch = null;
	String model = "";
	int fPos = -1;
	String fSrc = "\n";

	public LTSABatch (String fileTxt, int modulesCount, boolean memo, boolean ref, String heur, boolean proj) {
		//SymbolTable.init();
//		compositionBatch =
//			new SETCompositionalBatch(this,this,this,this,true,
//								      memo, ref, heur,proj,modulesCount);
		model = fileTxt;
	}

	public void out ( String str ) {
		System.out.print(str);
	}

	public void outln ( String str ) {
		System.out.println(str);
	}

	public void clearOutput () {
		//not needed
	}

	public char nextChar () {
		fPos = fPos + 1;
		if (fPos < fSrc.length ()) {
			return fSrc.charAt (fPos);
		} else {
			//fPos = fPos - 1;
			return '\u0000';
		}
	}

	public char backChar () {
		fPos = fPos - 1;
		if (fPos < 0) {
			fPos = 0;
			return '\u0000';
		}
		else
			return fSrc.charAt (fPos);
	}

	public int getMarker () {
		return fPos;
	}

	public void resetMarker () {
		fPos = -1;
	}


	private void safety() {
		safety(true, false);
	}
	private void safety(boolean checkDeadlock, boolean multiCe) {
		compile();
		if (current != null) {
			//no sirve asi!
			current.analyse(checkDeadlock, this);
		}
	}

	private void compile () {
		if (!parse()) return;
		current = docompile();
	}

	public void displayError(LTSException x) {
		outln("ERROR - "+x.getMessage());
	}

	private CompositeState docompile() {
		fPos = -1;
        fSrc = model;
		CompositeState cs=null;
		LTSCompiler comp=new LTSCompiler(this,this,currentDirectory);
		try {
			comp.compile();
			cs = comp.continueCompilation("ALL");
		} catch (LTSException x) {
			displayError(x);
		}
		return cs;
	}

	private Hashtable doparse () {
		Hashtable cs = new Hashtable();
		Hashtable ps = new Hashtable();
		doparse(cs,ps);
		return cs;
	}

	private void doparse(Hashtable cs, Hashtable ps) {
		fPos = -1;
        fSrc = model;
		LTSCompiler comp = new LTSCompiler(this,this,currentDirectory);
		try {
			comp.parse(cs,ps,null);
		} catch (LTSException x) {
			displayError(x);
			cs=null;
		}
	}

	public void compileIfChange () {
		//not needed
	}

	public boolean parse() {
		// >>> AMES: Enhanced Modularity
		Hashtable cs = new Hashtable();
		Hashtable ps = new Hashtable();
		doparse(cs,ps);
		// <<< AMES

		if (cs==null) return false;
		if (cs.size()==0) {
			compositeNames.add("DEFAULT");
		} else  {
			Enumeration e = cs.keys();
			java.util.List forSort = new ArrayList();
			while( e.hasMoreElements() ) {
				forSort.add( e.nextElement() );
			}
			Collections.sort( forSort );
			for( Iterator i = forSort.iterator() ; i.hasNext() ; ) {
				compositeNames.add((String)i.next());
			}
		}
		current = null;

		return true;
	}

	public CompositeState compile(String name) {
		fPos = -1;
        fSrc = model;
		CompositeState cs=null;
		LTSCompiler comp = new LTSCompiler(this,this,currentDirectory);
		try {
			comp.compile();
			cs = comp.continueCompilation(name);
		} catch (LTSException x) {
			displayError(x);
		}
		return cs;
	}

	/**
	 * Returns the current labeled transition system.
	 */
	public CompositeState getCurrentTarget() {
		return current;
	}

	public Set<String> getCompositeNames() {
		return compositeNames;
	}

	/**
	 * Returns the set of actions which correspond to the label set definition
	 * with the given name.
	 */
	public Set<String> getLabelSet(String name) {
		if (labelSetConstants == null)
			return null;

		Set<String> s = new HashSet<String>();
		LabelSet ls = labelSetConstants.get(name);

		if (ls == null)
			return null;

		for ( String a : (Vector<String>) ls.getActions(null) )
			s.add(a);

		return s;
	}

	public void performAction (final Runnable r, final boolean showOutputPane) {
		//not needed
	}

	public String getTargetChoice() {
		//not needed
		return "";
	}

	public void newMachines(java.util.List<CompactState> machines) {
		//not needed
	}

	private static void showUsage() {
		String usage= "MTSA usage:\n" +
			"\tmtsa.jar -i <FSP FILE> -c <CONTROLLER> -o <OUTPUT FILE> [-m|-d|-g|--coh <HEURISTIC>|--csh <HEURISTIC>] \n\n" +
			"Options:\n\n" +
			"-c <CONTROLLER>: Specifies the controller goal in use. \n\n" +
			"-o <OUTPUT FILE>: Specifies the output format (*.aut for Aldebaran, " +
			"*.pddl for Planning Domain Definition Language, " +
			"*.xml for a Supremica project, *.smv for MBP, and *.py for Party-elli).\n\n" +
			"-m: Use monotonic abstraction during directed synthesis (ready abstraction by default).\n\n" +
			"-d: Skips synthesis during compositional translation.\n\n" +
			"-g: Launches the IDE.\n\n" +
			"--coh <HEURISTIC>: Composition order heuristic.\n\n" +
			"--csh <HEURISTIC>: Candidate selection heuristic.\n\n" +
			"--CSVfile: Saves performance information to specified CSV file.\n\n" +
			"--controllerFile: Saves controller information to specified fsp file.\n\n" +
			"--graphfile: Saves composition graph to specified file as a list of edges.\n\n" +
			"--listdontcombineh: List heuristics marked as dont combine, those are intended to be run only in combination with themselves (ie both heuristics should have the same name).\n\n" +
			"--listcoh: List all available options for coh.\n\n" +
			"--listcsh: List all available options for csh.\n\n" +
			"--listPlantSensers: Lists all available plant sensers.\n\n " +
			"--sensePlantArchitecture: Filepath to save plant architecture graph, must be used with plantSenser.\n\n" +
			"--plantSenser: Plant senser to use, must be used with sensePlantArchitecture.\n\n" +
			"--spectraEngine: Use Spectra engine to perform synthesis.\n\n" +
			"--strixEngine: Use Strix engine to perform synthesis.\n\n" +
            "--monolithicTranslationToRS: Use monolithic translation to RS.\n\n" +
			"--disableWarmupMinimization: Disable minimization before starting algorithm run.\n\n"	;

		System.out.print(usage);
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		CmdLineParser cmdParser= new CmdLineParser();
		Option filename = cmdParser.addStringOption('i', "file");
		Option controller = cmdParser.addStringOption('c', "controller");
		Option output = cmdParser.addStringOption('o', "output");
		Option monotonic = cmdParser.addBooleanOption('m', "monotonic");
		Option ready = cmdParser.addBooleanOption('r', "ready");
		Option dummy = cmdParser.addBooleanOption('d', "dummy");
		Option interactive = cmdParser.addBooleanOption('e', "interactiveEnv");
		Option random = cmdParser.addBooleanOption('t', "random");
		Option bfs = cmdParser.addBooleanOption('b', "bfs");
		Option debugging = cmdParser.addBooleanOption('a', "debugging");
		Option gui = cmdParser.addBooleanOption('g', "gui");
		Option help = cmdParser.addBooleanOption('h', "help");
		Option compositionOrderHeuristic = cmdParser.addStringOption("coh");
		Option candidateSelectionHeuristic = cmdParser.addStringOption("csh");
		Option listCompositionOrderHeuristic = cmdParser.addBooleanOption("listcoh");
		Option listCandidateSelectionHeuristic = cmdParser.addBooleanOption("listcsh");
		Option listDontCombineHeuristics = cmdParser.addBooleanOption("listdontcombineh");
		Option disableWarmupMinimization = cmdParser.addBooleanOption("disableWarmupMinimization");
		Option justExperimentation = cmdParser.addBooleanOption("justExperimentation");
		Option fileCSV = cmdParser.addStringOption("CSVfile");
		Option controllerFSP = cmdParser.addStringOption("controllerFile");
		Option fileGraph = cmdParser.addStringOption("graphfile");
		Option sensePlantArchitecture = cmdParser.addStringOption("sensePlantArchitecture");
		Option plantSenser = cmdParser.addStringOption("plantSenser");
		Option listPlantSensers = cmdParser.addBooleanOption("listPlantSensers");
		Option spectraEngine = cmdParser.addBooleanOption("spectraEngine");
        Option strixEngine = cmdParser.addBooleanOption("strixEngine");
        Option monolithicTranslationToRS = cmdParser.addBooleanOption("monolithicTranslationToRS");

		try {
			cmdParser.parse(args);
		} catch (CmdLineParser.OptionException e) {
			System.out.println("Invalid option: " + e.getMessage() + "\n");
			showUsage();
			System.exit(0);
		}

		String filenameValue = (String)cmdParser.getOptionValue(filename);
		String outputValue = (String)cmdParser.getOptionValue(output);
		String controllerValue = (String)cmdParser.getOptionValue(controller);
		Boolean monotonicValue = (Boolean)cmdParser.getOptionValue(monotonic);
		Boolean readyValue = (Boolean)cmdParser.getOptionValue(ready);
		Boolean bfsValue = (Boolean)cmdParser.getOptionValue(bfs);
		Boolean debuggingValue = (Boolean)cmdParser.getOptionValue(debugging);
		Boolean guiValue = (Boolean)cmdParser.getOptionValue(gui);
		Boolean dummyValue = (Boolean)cmdParser.getOptionValue(dummy);
		Boolean interactiveValue = (Boolean)cmdParser.getOptionValue(interactive);
		Boolean randomValue = (Boolean)cmdParser.getOptionValue(random);
		Boolean helpValue = (Boolean)cmdParser.getOptionValue(help);
		Boolean justExperimentationValue = (Boolean)cmdParser.getOptionValue(justExperimentation);
		String compositionOrderHeuristicValue = (String)cmdParser.getOptionValue(compositionOrderHeuristic);
		String candidateSelectionHeuristicValue = (String)cmdParser.getOptionValue(candidateSelectionHeuristic);
		Boolean listCandidateSelectionHeuristicValue = (Boolean)cmdParser.getOptionValue(listCandidateSelectionHeuristic);
		Boolean listCompositionOrderHeuristicValue = (Boolean)cmdParser.getOptionValue(listCompositionOrderHeuristic);
		String fileCSVValue = (String)cmdParser.getOptionValue(fileCSV);
		String controllerFSPValue = (String)cmdParser.getOptionValue(controllerFSP);
		String fileGraphValue = (String)cmdParser.getOptionValue(fileGraph);
		String sensePlantArchitectureValue = (String)cmdParser.getOptionValue(sensePlantArchitecture);
		String plantSenserValue = (String)cmdParser.getOptionValue(plantSenser);
		Boolean listPlantSensersValue = (Boolean)cmdParser.getOptionValue(listPlantSensers);
		Boolean listDontCombineHeuristicsValue = (Boolean)cmdParser.getOptionValue(listDontCombineHeuristics);
		Boolean disableWarmupMinimizationValue = (Boolean)cmdParser.getOptionValue(disableWarmupMinimization);


		Boolean spectraEngineValue = (Boolean)cmdParser.getOptionValue(spectraEngine);
        Boolean strixEngineValue = (Boolean)cmdParser.getOptionValue(strixEngine);
        Boolean monolithicTranslationToRSValue = (Boolean)cmdParser.getOptionValue(monolithicTranslationToRS);


		if (listDontCombineHeuristicsValue != null && listDontCombineHeuristicsValue) {
			for (Field field : CandidateSelectionHeuristic.class.getDeclaredFields()) {
				if (field.isAnnotationPresent(DontCombine.class)) {
						System.out.println(field.getName());
				}
			}
			System.exit(0);
		}

		if (listCandidateSelectionHeuristicValue != null && listCandidateSelectionHeuristicValue) {
			for(CandidateSelectionHeuristic c : CandidateSelectionHeuristic.values())
				System.out.println(c);
			System.exit(0);
		}else if (listCompositionOrderHeuristicValue != null && listCompositionOrderHeuristicValue) {
			for(CompositionOrderHeuristic c : CompositionOrderHeuristic.values())
				System.out.println(c);
			System.exit(0);
		}else if (listPlantSensersValue != null && listPlantSensersValue) {
			for(Utils.PlantSensers p : Utils.PlantSensers.values())
				System.out.println(p);
			System.exit(0);
		}

		CompositionalApproach.justCSV = false;
		CompositionalApproach.justExperimentation = false;
		CompositionalApproach.sensePlantArchitecture = sensePlantArchitectureValue;
		CompositionalApproach.plantSenser = plantSenserValue;
		CompositionalApproach.enabledWarmupMinimization = disableWarmupMinimizationValue == null || !disableWarmupMinimizationValue;

		if(spectraEngineValue==null)
			spectraEngineValue=false;

		if(strixEngineValue==null)
			strixEngineValue=false;

		if(justExperimentationValue==null)
			justExperimentationValue=false;

		CompositionalApproach.justExperimentation = justExperimentationValue;
		CompositionalApproach.spectraEngine = spectraEngineValue;
		CompositionalApproach.strixEngine = strixEngineValue;
        CompositionalApproach.monolithicTranslationToRS = monolithicTranslationToRSValue;

		MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Statistics.graphFile = fileGraphValue;
		MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Statistics.controllerFile = controllerFSPValue;

		MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional.Statistics.csvFile = fileCSVValue;

		if (helpValue != null && helpValue) {
			showUsage();
			System.exit(0);
		}

		if (controllerValue == null || controllerValue.isEmpty())
			guiValue = true; // if no controller is selected open gui

		if (compositionOrderHeuristicValue == null || compositionOrderHeuristicValue.isEmpty())
			compositionOrderHeuristicValue = "MaxL";
		if (candidateSelectionHeuristicValue == null || candidateSelectionHeuristicValue.isEmpty())
			candidateSelectionHeuristicValue = "MaxS";

		CompositionalApproach.setCompositionOrderHeuristic(compositionOrderHeuristicValue);
		CompositionalApproach.setCandidateSelectionHeuristic(candidateSelectionHeuristicValue);

		if (guiValue != null && guiValue) {
			String[] arg = {filenameValue};
			HPWindow.main(arg);

		} else {
			String fileTxt = readFile(filenameValue);
			process(fileTxt, controllerValue, outputValue);



			System.exit(0);
		}
	}

	private static String readFile(String filename) {
		String result = null;
		try {
			BufferedReader file = new BufferedReader(new FileReader(filename));
			String thisLine;
			StringBuffer buff = new StringBuffer();
			while ((thisLine = file.readLine()) != null)
				buff.append(thisLine+"\n");
			file.close();
			result = buff.toString();
		} catch (Exception e) {
			System.err.print("Error reading FSP file " + filename + ": " + e);
			System.exit(1);
		}
		return result;
	}

	private static void process(final String fileTxt, String controller, String outputFile) {
		CompositeState compositeState= null;
		try {
			compositeState= compileCompositeState(fileTxt, controller);
		} catch (Exception e) {
			System.err.print("Error compiling FSP: " + e);
			System.exit(1);
		}

		if (outputFile == null)
			return;

		int i = outputFile.lastIndexOf(".");
		if (i == -1) {
			System.out.println("Invalid extension for output file.\n");
			showUsage();
			System.exit(1);
		}
		String extension = outputFile.substring(i, outputFile.length());
		AbstractTranslator translator = null;
		switch (extension) {
			case ".xml":   translator = new XMLTranslator(); break;
			case ".pddl":  translator = new PDDLTranslator(true); break;
			//case ".npddl": translator = new NPDDLTranslator(); break;
			case ".smv":   translator = new SMVTranslator(); break;
			case ".py":    translator = new CTLPYTranslator(); break;
			//case ".xltl":  translator = new XLTLTranslator(); break;
			case ".slugs": translator = new SlugsTranslator(); break;
			default:
				System.out.println("Invalid extension for output file.\n");
				showUsage();
				System.exit(1);
		}

		try {
			PrintStream outStream = new PrintStream(new FileOutputStream(new File(outputFile)));
			if (translator != null)
				translator.translate(compositeState, outStream);
			else
				compositeState.composition.printAUT(outStream);
			outStream.close();
			if (translator instanceof PDDLTranslator) {
				String problemFilePath = outputFile.replace(".pddl", "-p.pddl");
				outStream = new PrintStream(new FileOutputStream(problemFilePath));
				outStream.print(((PDDLTranslator)translator).getProblem());
				outStream.close();
			}
		} catch (Exception e) {
			System.err.print("Error while exporting to file " + outputFile);
			System.exit(1);
		}
	}


	private static CompositeState compileCompositeState(String inputString, String modelName) throws IOException {
		return compileComposite(modelName, new LTSInputString(inputString));
	}

	public static CompositeState compileComposite(String modelName, LTSInput input)
			throws IOException {
		LTSOutput output = new LTSOutput() {
			@Override
			public void out(String str) { System.out.println(str);}
			@Override
			public void outln(String str) { System.out.println(str); }
			@Override
			public void clearOutput() {}
		};
		LTSOutput ignoreOutput = new EmptyLTSOuput();

		String currentDirectory = (new File(".")).getCanonicalPath();
		LTSCompiler compiler = new LTSCompiler( input , ignoreOutput , currentDirectory );

		//lts.SymbolTable.init();
		compiler.compile();
		CompositeState c = compiler.continueCompilation(modelName);
		Statistics stats = new Statistics();

		try{
			if(Objects.equals(modelName, "MonolithicController")) {
				TransitionSystemDispatcher.applyComposition(c, output, stats);
			} else {
				TransitionSystemDispatcher.applyComposition(c, ignoreOutput, stats);
			}
		} catch (OutOfMemoryError e){
			System.out.print("OutOfMem error during exploration \n");
			System.out.print(e + "\n");
		} finally {
			if (stats.getExpandedStates() != 0) {
				//we have stats to output
				System.out.print(stats);
			}
		}

		return c;
	}
}
