package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.Compositional;

import MTSTools.ac.ic.doc.commons.relations.Pair;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.PrintTransitions;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Vector;

/**
 * This class holds statistic information about the heuristic procedure.
 */
public class Statistics {


    /**
     * System wall clock in milliseconds at the start of the procedure.
     */
    private long started;

    /**
     * System wall clock in milliseconds at the end of the procedure.
     */
    private long ended;

    private boolean isRunning = false;
    private long endedTransitiveClosure;
    private long startedTransitiveClosure;
    private long accumTransitiveClosure = 0;
    private ArrayList<Pair<Integer, Pair<String, String>>> compositionGraph = new ArrayList<>();

    private final Vector<Iteration> iterations_history = new Vector<>();
    private Iteration current_iteration = null;
    public static String graphFile = null;
    public static String csvFile = null;
    private long timeToReduce = 0;
    public static String controllerFile = null;
    private long startLastIteration = 0;
    private long endLastIteration = 0;

    public void registerControlablePathStates(int controlablePathStates) {
        current_iteration.controlablePathStates = controlablePathStates;
    }

    public void registerCountTransitions(int countTransitions) {
        current_iteration.countTransitions = countTransitions;
    }

    public void registerControllablePathms(long duration) {
        current_iteration.controllablePathms = duration;
    }

    public void registerFindPathMS(long duration) {
        current_iteration.findPathMS = duration;
    }

    public void registerMinimizedStates(int nStates) {
        current_iteration.minimizedStates = nStates;
    }

    public void registerComposition(CompositeState composedSubSys, Integer iteration) {
        for (CompactState machine : composedSubSys.machines) {
            compositionGraph.add(new Pair<>(iteration, new Pair<>(machine.getName(), composedSubSys.composition.name)));
        }
    }

    public void registerHeuristicRunningMS(long duration) {
        current_iteration.heuristicRunningTimeMS = duration;
    }

    public void registerTimeToReduce(long time) {
        timeToReduce += time;}

    public void registerWsoeTime(long t) {
        current_iteration.wsoeTimeMS = t;
    }

    public void registerLocalControllerTime(long l) {
        current_iteration.localControllerMS = l;
    }

    public void registerPreprocessMinimizationStates(int states, long time) {
        current_iteration.preprocessMinimizationStates = states;
        current_iteration.preprocessMinimizationMS = time;
    }

    public void registerCompositionMS(long time) {
        current_iteration.compositionMS = time;
    }

    class Iteration {
        public long iteration = -1;
        public Vector<Integer> machineStates = new Vector<>();
        public long compositionStates = -1;
        public long localControllerStates = -1;
        public int controlablePathStates = -1;
        public int countTransitions = -1;
        public long controllablePathms = -1;
        public long findPathMS = -1;
        public int minimizedStates = -1;
        public long totalTimeMS = -1;
        public long wsoeTimeMS = -1;
        public long localControllerMS = -1;
        public long heuristicRunningTimeMS = -1;
        public long preprocessMinimizationStates = -1;
        public long preprocessMinimizationMS = -1;
        public long lastIterationMS = -1;
        public long compositionMS = -1;
    }

    /**
     * Marks the start of the procedure to measure elapsed time.
     */
    public void start() {
        isRunning = true;
        started = System.currentTimeMillis();
    }

    /**
     * Marks the end of the procedure to measure elapsed time.
     */
    public void end() {
        isRunning = false;
        ended = System.currentTimeMillis();
        current_iteration.totalTimeMS = ended - started - timeToReduce;
        iterations_history.add(current_iteration);
        current_iteration = null;
    }

    /**
     * Marks the start of the procedure to measure elapsed time.
     */
    public void startTransitiveClosure() {
        isRunning = true;
        startedTransitiveClosure = System.currentTimeMillis();
    }

    /**
     * Marks the end of the procedure to measure elapsed time.
     */
    public void endTransitiveClosure() {
        isRunning = false;
        endedTransitiveClosure = System.currentTimeMillis();
        accumTransitiveClosure += (endedTransitiveClosure - startedTransitiveClosure);
    }

    public void startLastIteration(){
        startLastIteration = System.currentTimeMillis();
    }
    public void endLastIteration(){
        endLastIteration = System.currentTimeMillis();
        current_iteration.lastIterationMS = (endLastIteration-startLastIteration);
    }


    /**
     * Indicates if the heuristic procedure is running.
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Resets the statistics.
     */
    public void clear() {
        started = 0;
        ended = 0;
    }


    /**
     * Returns a string with the statistic data.
     */
    public String toString(PrintWriter writer) {
        long WSOETime = 0; long SyntTime=0;
        for(Iteration i : iterations_history) {
            WSOETime += i.wsoeTimeMS;
            SyntTime+=i.localControllerMS;
        }
        writer.write("\n\n");
        writer.write(
                "Elapsed in Total: " + (ended - started) + " ms\n" +
                        "Elapsed in Local Synt: " + SyntTime + " ms\n" +
                        "Elapsed in WSOE: " + WSOETime + " ms\n");
        return "";
    }

    public String toString() {
        long WSOETime = 0; long SyntTime=0;
        for(Iteration i : iterations_history) {
            WSOETime += i.wsoeTimeMS;
            SyntTime+=i.localControllerMS;
        }
        long lastIterationMS = iterations_history.get(iterations_history.size() - 1).lastIterationMS;
        return "Elapsed in Comp. Synthesis: " + (ended - started) + " ms\n" +
                "Elapsed in Last Synt: " + lastIterationMS + " ms\n" +
                "Elapsed in WSOE: " + WSOETime + " ms\n" +
                "Elapsed in Local Synt: " + SyntTime + " ms\n";
    }

    public void startNewIteration(long k) {
        if (current_iteration == null) {
            current_iteration = new Iteration();
            current_iteration.iteration = k;
            return;
        }
        iterations_history.add(current_iteration);
        current_iteration = new Iteration();
        current_iteration.iteration = k;
    }

    public void registerMachineStates(int machineStates) {
        current_iteration.machineStates.add(machineStates);
    }

    public void registerCompositionStates(long compositionStates) {
        current_iteration.compositionStates = compositionStates;
    }

    public void registerLocalControllerStates(long localControllerStates) {
        current_iteration.localControllerStates = localControllerStates;
    }


    public void saveController(Vector<CompactState> controlled) {
        if (controllerFile == null) {
            return;
        }
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(controllerFile)); Writer writer = new PrintWriter(outputStream)) {
            int k=0;
            for(CompactState controller : controlled){
                controller.name = "Controller"+k;
                k++;
                PrintTransitions printer = new PrintTransitions(controller);
                String fsp = printer.getFSP(999999);
                writer.write(fsp + "\n \n");
            }
            writer.write("||ControllerComposed = (");
            for(int controllerNumber=0;controllerNumber<k;controllerNumber++){
                writer.write("Controller" + controllerNumber);
                if(controllerNumber<k-1)
                    writer.write(" || ");
            }
            writer.write(").");
            writer.flush();
        } catch (IOException e) {
            System.err.println("Could not save controller file");
        }

    }
    public void saveGraph() {
        if (graphFile == null) {
            return;
        }
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(graphFile)); Writer writer = new PrintWriter(outputStream)) {
            writer.write('[');
            for (Pair<Integer, Pair<String, String>> line : compositionGraph) {
                writer.write('(');
                writer.write(line.getFirst().toString());
                writer.write(",(\"");
                writer.write(line.getSecond().getFirst());
                writer.write("\",\"");
                writer.write(line.getSecond().getSecond());
                writer.write("\")),");

            }
            writer.write(']');
            writer.flush();
        } catch (IOException e) {
            System.err.println("Could not save graph");
        }
    }

    public void saveCSV() {
        if (csvFile == null) {
            return;
        }
        try (OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(csvFile)); Writer writer = new PrintWriter(outputStream)) {
            writer.write(this.toCSV());
        } catch (IOException e) {
            System.err.println("Could not save csv");
        }
    }

    private String toCSV() {

        StringBuilder sb = new StringBuilder();
        Class<?> clazz = Iteration.class;
        Field[] fields = clazz.getDeclaredFields();
        fields = java.util.Arrays.stream(fields)
                .filter(f -> !f.getName().startsWith("this$"))
                .toArray(Field[]::new);


        // Write headline
        for (int i = 0; i < fields.length; i++) {
            sb.append(fields[i].getName());
            if (i < fields.length - 1) sb.append(";");
        }
        sb.append("\n");
        if (iterations_history.isEmpty()) return sb.toString(); // If empty return empty csv

        //  Write Values
        for (Object obj : iterations_history) {
            for (int i = 0; i < fields.length; i++) {
                fields[i].setAccessible(true); // Allow access to private fields
                try {
                    sb.append(fields[i].get(obj));
                } catch (IllegalAccessException ignored) {
                }
                if (i < fields.length - 1) sb.append(";");
            }
            sb.append("\n");
        }

        return sb.toString();
    }


}
