package MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1;

import MTSTools.ac.ic.doc.commons.collections.BidirectionalMap;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.LTS;
import MTSTools.ac.ic.doc.mtstools.model.impl.LTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedLTSImpl;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.DirectedControllerSynthesis;
import MTSTools.ac.ic.doc.mtstools.model.operations.DCS.gr1.abstraction.*;
import ltsa.ui.EnvConfiguration;

import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import static java.util.Collections.*;
import static org.junit.Assert.*;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


/** This class contains the logic to synthesize a controller for
 *  a deterministic environment using an informed search procedure. */
public class DirectedControllerSynthesisGR1<State, Action> extends DirectedControllerSynthesis<State, Action> {

    /** Constant used to represent an infinite distance to the goal. */
    public static final int INF = Integer.MAX_VALUE;

    /** Constant used to represent an undefined distance to the goal. */
    public static final int UNDEF = -1;

    /** Indicates the abstraction to use in order to compute the heuristic. */
    public static AbstractionMode mode = AbstractionMode.Ready;

    /** Path to the model used to compute the heuristic if ml_model is selected */
    public static String ml_modelPath = "src/main/resources/models/model.onnx";
    public static float threshold = 0.5f;
    /** Paths to the two models if Two_ML_models is selected */ //TODO refactor the options/way to set up
    public static String ml_sameOrigin_modelPath = "src/main/resources/models/model_same_origin.onnx";
    public static String ml_differentOrigin_modelPath = "src/main/resources/models/model_different_origin.onnx";

    /** List of LTS that compose the environment. Note that the marked ltss are a prefix of the list! */
    public List<LTS<State,Action>> ltss;

    /** The number of intervening LTSs. */
    public int ltssSize;

    /** Set of controllable actions. */
    public Set<Action> controllable;

    /** Environment alphabet (implements perfect hashing for actions). */
    public Alphabet<Action> alphabet;

    /** Set of transitions enabled by default by each LTS. */
    public TransitionSet<State, Action> base;

    /** Auxiliary transitions allowed by a given compostate. */
    public TransitionSet<State, Action> allowed;

    /** Last states used to update the allowed transitions. */
    public List<State> facilitators;

    /** Abstraction used to rank the transitions from a state. */
    private ExplorationHeuristic<State, Action> heuristic;

    /** Queue of open states, the most promising state should be expanded first. */
    public Queue<Compostate<State, Action>> open;

    /** Cache of states mapped from their basic components. */
    public Map<List<State>,Compostate<State, Action>> compostates;

    /** List of reachable states after a transition (used during expansion). */
    private Deque<Set<State>> transitions;

    /** Set of visited during iteration. */
    private Set<Compostate<State, Action>> visited;

    //----------------***************

    private Set<Compostate<State, Action>> loop;

    /** HashMap < guaranteeNumber, ltsIndexInLtss > */
    public HashMap<Integer, Integer> guarantees;
    /** HashMap < assumptionNumber, ltsIndexInLtss > */
    public HashMap<Integer, Integer> assumptions;
    public List<Set<Compostate<State, Action>>> composByGuarantee;
    public List<Set<Compostate<State, Action>>> notComposByAssumption;

    /** The strategies we use to build the controller following Nir's triple fixpoint */
    /** HashMap<loopID, Y[j][r] -> Set<Compostate>  >*/
    private HashMap<Integer, HashMap<Integer, HashMap<Integer, Set<Compostate<State, Action>>>> > stratY;
    /** HashMap<loopID, X[j][r][i] -> Set<Compostate>  >*/
    private HashMap<Integer, HashMap<Integer, HashMap<Integer, HashMap<Integer, Set<Compostate<State, Action>>>>> > stratX;

    /** Used to identify each completely marked controlable loop. */
    private int loopID;
    //----------------***********

    /** Directed acyclic graph from a successor state to a precursor state. */
    private BidirectionalMap<Compostate<State, Action>, Compostate<State, Action>> dag;

    /** List of ancestor states considered during the construction of the dag. */
    private List<Compostate<State, Action>> auxiliarListStates;

    /** Contains the marked states per LTS. */
    public List<Set<State>> defaultTargets;

    /** Initial state. */
    private Compostate<State, Action> initial;

    /** Statistic information about the procedure. */
    private final Statistics statistics = new Statistics();

    /** Logger */
    private final Logger logger = Logger.getLogger(DirectedControllerSynthesisGR1.class.getName());
    public static boolean verbose = false;

//    public static String explorationTraceOutputFile = EnvConfiguration.getInstance().getOpenFileName().split("\\.")[0];
    private ArrayList<String> explorationTrace;


    /** This method starts the directed synthesis of a controller.
     *  @param ltss, a list of MarkedLTSs that compose the environment.
     *  @param controllable, the set of controllable actions.
     *  @param reachability, a boolean indicating whether to pursue reachability or liveness.
     *  @param guarantees, a list of LTS Formulas representing guarantees
     *  @param assumptions, a list of LTS Formulas representing assumptions
     *  @return the controller for the environment in the form of an LTS that
     *      when composed with the environment reaches the goal, only by
     *      enabling or disabling controllable actions and monitoring
     *      uncontrollable actions. Or null if no such controller exists. */
    public LTS<Long,Action> synthesize(
        List<LTS<State, Action>> ltss,
        Set<Action> controllable,
        boolean reachability,
        HashMap<Integer, Integer> guarantees,
        HashMap<Integer, Integer> assumptions)
    {

        //LOGGER
        //set logger formatter for more control over logs
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        LogFormatter formatter = new LogFormatter();
        handler.setFormatter(formatter);
        logger.addHandler(handler);
        //Sets the minimum level required for messages to be logged
        // SEVERE > WARNING > INFO > CONFIG > FINE > FINER > FINEST
        // we use fine/finer/finest to log exploration info
        if(verbose){
            logger.setLevel(Level.FINEST);
            handler.setLevel(Level.FINEST);
        }else{
            logger.setLevel(Level.WARNING);
            handler.setLevel(Level.WARNING);
        }
        explorationTrace = new ArrayList<>();

        //
        this.ltss = ltss;
        this.ltssSize = ltss.size();
        this.controllable = controllable;
        /** Indicates whether the procedure tries to reach a goal state (by default we look for live controllers). */

        statistics.clear();
        statistics.start();

        compostates = new HashMap<>();
        transitions = new ArrayDeque<>(ltss.size());
        visited = new HashSet<>();
        loop = new HashSet<>();
        dag = new BidirectionalMap<>();
        auxiliarListStates = new ArrayList<>();
        /** List of descendants of an state (used to close unnecessary descendants). */
        Deque<Compostate<State, Action>> descendants = new ArrayDeque<>();

        alphabet = new Alphabet<>(this, ltss);
        base = new TransitionSet<>(ltss, alphabet);
        this.guarantees = guarantees;
        this.assumptions = assumptions;
        checkGuarantees();
        allowed = base.clone();
        defaultTargets = buildDefaultTargets();
        composByGuarantee = new ArrayList<>();
        for (int i = 0; i < guarantees.size(); i++) {
            composByGuarantee.add(new HashSet<>());
        }
        notComposByAssumption = new ArrayList<>();
        for (int i = 0; i < assumptions.size(); i++) {
            notComposByAssumption.add(new HashSet<>());
        }
        loopID = 1;
        stratY = new HashMap<>();
        stratX = new HashMap<>();

        setupHeuristic();

        open = new PriorityQueue<>();

        initial = buildInitialState();
        heuristic.setInitialState(initial);

        while(heuristic.somethingLeftToExplore() && initial.isStatus(Status.NONE)) {
            Pair<Compostate<State, Action>, HAction<Action>> next = heuristic.getNextStep();

            expand(next.getFirst(), next.getSecond());
        }

        assertFalse("Finished because open was empty, shouldn't be the case", initial.isStatus(Status.NONE));

        statistics.end();

        LTS<Long,Action> result;

        if(isGoal(initial)){
            result = buildController();
        } else {
            result = null;
        }

        // save the exploration trace to a file
//        try (FileWriter writer = new FileWriter(explorationTraceOutputFile + "_exploration_trace.txt")) {
//            for (String line : explorationTrace) {
//                writer.write(line);
//                writer.write("\n");
//            }
//        } catch (IOException e) {
//            e.printStackTrace();
//        }

        return result;
    }

    private void setupHeuristic() {
        // FIXME, this is only done here until it can be chosen from the FSP instead of hardcoded
//        mode = AbstractionMode.Two_ML_models;
        if (mode == AbstractionMode.ML_model){
            heuristic = new TransitionComparisonHeuristic<>(this, ml_modelPath, threshold);
        } else if (mode == AbstractionMode.Two_ML_models) {
//            ml_sameOrigin_modelPath = "src/main/resources/models/DP-3-3_pairwise_same_origin_12_05_2025_RF.onnx";
//            ml_differentOrigin_modelPath = "src/main/resources/models/DP-3-3_pairwise_diff_origin_12_05_2025_RF.onnx";
            heuristic = new TransitionComparisonMultipleModelsHeuristic<>(this, ml_sameOrigin_modelPath,
                    ml_differentOrigin_modelPath, threshold);
        } else {
            heuristic = new OpenSetExplorationHeuristic<>(this, mode);
        }
    }

    private void checkGuarantees(){
        for(Integer gIndex : guarantees.values()){
            LTS<State, Action> lts = ltss.get(gIndex);
            assertNoAsterisk(lts);
        }
        for(Integer aIndex : assumptions.values()){
            LTS<State, Action> lts = ltss.get(aIndex);
            assertNoAsterisk(lts);
        }
    }

    private void assertNoAsterisk(LTS<State, Action> lts){
        for(State s : lts.getStates()) {
            for (Pair<Action, State> transition : lts.getTransitions(s)) {
                Action a = transition.getFirst();
                assertNotSame("guarantees and assumptions cant have asterisks", "*", a);
            }
        }
    }

    /** Returns a list of marked states per LTS. */
    private List<Set<State>> buildDefaultTargets() {
        List<Set<State>> result = new ArrayList<>();
        for (int i = 0; i < ltssSize; ++i) {
            LTS<State,Action> lts = ltss.get(i);
            Set<State> markedStates = new HashSet<>();
            if (lts instanceof MarkedLTSImpl) {
                markedStates.addAll(((MarkedLTSImpl<State,Action>)lts).getMarkedStates());
            } else {
                markedStates.addAll(lts.getStates());
                markedStates.remove(-1L); // -1 is never marked since it is used to represent errors
            }
            result.add(markedStates);
        }
        return result;
    }


    /** Returns the statistic information about the procedure. */
    public Statistics getStatistics() {
        return statistics;
    }


    /**
     * Creates (or retrieves from cache) a state in the composition given
     * a list with its base components.
     *
     * @param states,    a list of states with one state per LTS in the
     *                   environment, the position of each state in the list (its index)
     *                   reflects to which LTS that state belongs.
     */
    private Compostate<State, Action> buildCompostate(List<State> states) {
        Compostate<State, Action> result = compostates.get(states);
        if (result == null) {
            statistics.incExpandedStates();
            result = new Compostate<>(this, states);
            compostates.put(states, result);

            heuristic.newState(result);

            if (result.getStates().contains(-1L) || heuristic.fullyExplored(result)) {
                setError(result);
            }
        }
        return result;
    }

    public Compostate<State, Action> buildCompostate(Compostate<State, Action> compostate, HAction<Action> action) {
        return buildCompostate(getChildStates(compostate, action));
    }

    /** Creates the controller's initial state. */
    private Compostate<State, Action> buildInitialState() {
        List<State> states = new ArrayList<>(ltss.size());
        for (LTS<State,Action> lts : ltss)
            states.add(lts.getInitialState());
        Compostate<State, Action> initialComp = buildCompostate(states); // for non-deterministic LTS I need the tau-closure as a set of initial states
        if (!initialComp.markedByGuarantee.isEmpty())
            initialComp.addTargets(initialComp);
        initialComp.setDepth(0);

        if(isError(initialComp)){
            setError(initialComp);
            logger.finer("Initial compostate " + initialComp.toString() + " is an error");
        }

        return initialComp;
    }

    /** Expands a state following a given recommendation from a parent compostate.
     *  Internally this populates the transitions and expanded lists. */
    private void expand(Compostate<State, Action> state, HAction<Action> action) {
        statistics.incExpandedTransitions();

        Compostate<State, Action> child = buildCompostate(getChildStates(state, action));

        state.addChild(action, child);
        child.addParent(action, state);

        child.setTargets(state.getTargets());
        if (!child.markedByGuarantee.isEmpty())
            child.addTargets(child);
        if (isError(child)){
            setError(child);
            logger.finer("Expanding child compostate " + child.toString() + " is an error");
        }

        explore(state, action, child);

        heuristic.expansionDone(state, action, child);
    }

    private List<State> getChildStates(Compostate<State, Action> compostate, HAction<Action> action) {
        List<State> parentStates = compostate.getStates();
        int size = parentStates.size();
        for (int i = 0; i < size; ++i) {
            Set<State> image = ltss.get(i).getTransitions(parentStates.get(i)).getImage(action.getAction());
            if (image == null || image.isEmpty()) {
                if (ltss.get(i).getActions().contains(action.getAction())) { // tau does not belong to the lts alphabet, yet it may have a valid image, but I do not want taus at this stage
                    transitions.clear();
                    logger.severe("Invalid action: " + action);
                    return null; // do not expand a state through an invalid action
                }
                image = singleton(parentStates.get(i));
            }
            transitions.add(image);
        }
        List<State> childStates = new ArrayList<>();
        for (Set<State> singleton : transitions) // Note: Cartesian product for non-deterministic systems
            childStates.add(singleton.iterator().next());
        transitions.clear();
        return childStates;
    }

    private static <T> Set<T> newHashSet(T element) {
        Set<T> set = new HashSet<>();
        set.add(element);
        return set;
    }

    /** Opens a given child state following a recommendation from a parent.
     *  This method distinguishes between reachability and liveness.
     *  If the child to "open" is a goal (or closes a loop over a marked state)
     *  it propagates the goal through the ancestors. If the child is an error
     *  (or closes a loop over non-marked states) it propagates an error.
     *  If the child is already live this method does nothing, otherwise the
     *  child is evaluated with the heuristic and opened. */
    private void explore(Compostate<State, Action> parent,
                         HAction<Action> action,
                         Compostate<State, Action> child) {
        assert(!isError(parent) && !isGoal(parent));  //the parent should be NONE, otherwise exploring is useless
        logger.fine(parent.toString() + " -> " + action.toString() + " -> " + child.toString());
        // save the transition to the exploration trace
        Transition<State, Action> t = new Transition<>(parent, action, child);
        explorationTrace.add(t.toString());

        if (isError(child)) {
            propagateError(newHashSet(child), newHashSet(parent));

        } else if (isGoal(child) ){
            propagateGoal(newHashSet(child), newHashSet(parent));

        } else if(closingALoop(child, parent)){
            gatherLoopStates(child);
            Set<Compostate<State, Action>> c;

            if(!canBeWinningLoop(loop)){
                c = findNewErrors();
                if(!c.isEmpty()) propagateError(c, null);
            }else{
                c = findNewGoals();
                if(c.isEmpty()){
                    c = findNewErrors();
                    if(!c.isEmpty() && !isError(initial)) propagateError(c, null);
                }else{
                    propagateGoal(c, null);
                }
            }

        } else { // otherwise we keep exploring
            heuristic.notifyExpansionDidntFindAnything(parent, action, child);
        }

        clearLoopDetection();
    }

    private boolean canBeWinningLoop(Set<Compostate<State, Action>> c){
        Set<Integer> markedInLoop = new HashSet<>();
        for(Compostate<State, Action> comp : c){
            markedInLoop.addAll(comp.markedByGuarantee);
            if(!comp.markedByAssumption.isEmpty()) return true;
        }
        return markedInLoop.size() == guarantees.size();
    }

    /**checks if the player can force any of set target, Goals or (if allowNones) any Nones outside of nonesNotWanted */
    private boolean playerCanForce(Compostate<State, Action> v,
                                   Set<Compostate<State, Action>> target,
                                   boolean unexploredAreGoals,
                                   Set<Compostate<State, Action>> nonesInLoop) {
        boolean hasControllableToUnexplored = false;
        boolean hasUncontrollableToUnexplored = false;
        boolean hasUncontrollableToTargetOrGoal = false;

        if(v.hasValidRecommendation()){
            if(v.hasUncontrollableUnexplored()){
                hasUncontrollableToUnexplored = true;
            } else{
                hasControllableToUnexplored = true;
            }
        }

        for (Compostate<State, Action> child : v.getChildrenExploredThroughUncontrollable()) {
            if(child.isStatus(Status.ERROR)){
                return false;
            }else{
                if(target.contains(child) || child.isStatus(Status.GOAL)) hasUncontrollableToTargetOrGoal = true;
                if(!nonesInLoop.contains(child) && child.isStatus(Status.NONE)) {
                    if(!unexploredAreGoals) return false;
                    hasUncontrollableToUnexplored = true;
                }
                if(nonesInLoop.contains(child) && !target.contains(child) && child.isStatus(Status.NONE)) return false;
            }
        }

        if(hasUncontrollableToUnexplored){
            return unexploredAreGoals;
        }else if(hasUncontrollableToTargetOrGoal){
            return true;
        }

        if(hasControllableToUnexplored && unexploredAreGoals) return true;

        for (Compostate<State, Action> child : v.getChildrenExploredThroughControllable()) {
            if(target.contains(child) || child.isStatus(Status.GOAL)) return true;
            if(!nonesInLoop.contains(child) && child.isStatus(Status.NONE) && unexploredAreGoals) return true;
        }

        return false;
    }

    private Set<Compostate<State, Action>> playerCanForceSetIn1Step(Set<Compostate<State, Action>> c,
                                                     Set<Compostate<State, Action>> target,
                                                     boolean allowNones,
                                                     Set<Compostate<State, Action>> presentLoop){
        Set<Compostate<State, Action>> result = new HashSet<>();
        for (Compostate<State, Action> state : c){
            if (playerCanForce(state,target,allowNones,presentLoop)) result.add(state);
        }

//        logger.fine("playerCanForceSetIn1Step with allowNones = " + allowNones + ", target = " + target.toString() + " and c = " + c.toString() + "\n is = " + result.toString());
        return result;
    }

    private Set<Compostate<State, Action>> findNewGoals() {
        logger.finest("we are gatheringGoals with c: " + loop.toString());
        statistics.incFindNewGoalsCalls();

        Set<Compostate<State, Action>> newGoals = tripleFixPoint(loop, true, false);
        logger.finest("newGoals: " + newGoals.toString());
        for(Compostate<State, Action> state:newGoals){
            setGoal(state, null);
            state.setLoopID(loopID);
            state.setBestDistToWinLoop(0);
        }

        if(newGoals.size()!=0){
            ++loopID;
        }
        for (Compostate<State, Action> state:loop){
            if(!newGoals.contains(state) && state.hasValidRecommendation()){
                //reopen state
                heuristic.notifyStateIsNone(state);

            }
        }
        return newGoals;
    }


    /**funciona porque ya se sabe que o no tiene todos los markings dentro del loop, o gatherGoal dio vacio*/
    private Set<Compostate<State, Action>> findNewErrors() {
        logger.finer("we are gatheringErrors with loop: " + loop.toString());
        statistics.incFindNewErrorsCalls();

        Set<Compostate<State, Action>> c = new HashSet<>(loop);
        int previous_size = 0;
        while(previous_size != c.size()) {
            previous_size = c.size();
            c.removeIf(state -> hasControllableWayOut(state,c));
        }

        Set<Compostate<State, Action>> trueErrors = c;
        Set<Compostate<State, Action>> p = new HashSet<>(loop);
        if(canBeWinningLoop(c)) { //if our optimization to avoid the fixPoint doesn't work, we do the fixpoint
            Set<Compostate<State, Action>> allZ = tripleFixPoint(loop, false, true);
            p.removeAll(allZ);
            trueErrors = p;
        }

        logger.finer("newErrors: " + trueErrors.toString());
        for(Compostate<State, Action> state:trueErrors){
            setError(state);
        }
        for (Compostate<State, Action> state:loop){
            if(!trueErrors.contains(state) && state.hasValidRecommendation()){
                //reopen state
                heuristic.notifyStateIsNone(state);
            }
        }

        return trueErrors;
    }

    /** adapted idea from Synthesis of Reactive(1) Designs Nir Piterman, Amir Pnueli, and Yaniv Sa’ar also used in buildController*/
    private Set<Compostate<State, Action>> tripleFixPoint(Set<Compostate<State, Action>> c, boolean setR, boolean unexploredAreGoals) {
        int n = guarantees.size();

        Set<Compostate<State, Action>> z = new HashSet<>(c);
        int previous_sizeZ = 0;

        //fixpointZ
        while(previous_sizeZ != z.size()){
            stratY.put(loopID,  new HashMap<>());
            stratX.put(loopID,  new HashMap<>());
            previous_sizeZ = z.size();
            for(int j=0; j<n; ++j){
                int r=1;
                Set<Compostate<State, Action>> y = new HashSet<>();
                Set<Compostate<State, Action>> start;

                //fixpointY
                int previous_size_y = 1;
                while(previous_size_y != y.size()) {
                    previous_size_y = y.size();
                    Set<Compostate<State, Action>> coxY = playerCanForceSetIn1Step(c, y, unexploredAreGoals, c);
                    start = new HashSet<>(composByGuarantee.get(j));
                    start.retainAll(c);
                    start = playerCanForceSetIn1Step(start, z, unexploredAreGoals, c);
                    start.addAll(coxY);
                    y = new HashSet<>(start);

                    for (int i = 0; i<assumptions.size(); ++i){
                        //fixpointX
                        Set<Compostate<State, Action>> x = new HashSet<>(c);
                        int previous_sizeX = 0;
                        while(previous_sizeX != x.size()) {
                            previous_sizeX = x.size();
                            x = playerCanForceSetIn1Step(z, x, unexploredAreGoals, c);
                            x.retainAll(notComposByAssumption.get(i));
                            x.addAll(start);
                        }
                        if(setR){
                            if(!stratX.get(loopID).containsKey(j)) stratX.get(loopID).put(j,new HashMap<>());
                            if(!stratX.get(loopID).get(j).containsKey(r)) stratX.get(loopID).get(j).put(r,new HashMap<>());
                            stratX.get(loopID).get(j).get(r).put(i, x);
                        }
                        y.addAll(x);
                    }

                    if(setR){
                        if(!stratY.get(loopID).containsKey(j)) stratY.get(loopID).put(j,new HashMap<>());
                        stratY.get(loopID).get(j).put(r, y);
                    }
                    ++r;
                }

                z = new HashSet<>(y);
            }
        }

        if(setR){
            //all compostates in z should have a stratY for every guarantee j with some r
            for(Compostate<State, Action> s : z){
                for(int j=0; j<n; ++j){
                    boolean isInAny = false;
                    for(int r : stratY.get(loopID).get(j).keySet()){
                        if(stratY.get(loopID).get(j).get(r).contains(s)) isInAny=true;
                    }
                    assertTrue("state "+s.toString()+" in z is not in stratY with j: "+j, isInAny);
                }
            }
        }

        return z;
    }


    private void propagateGoal(Set<Compostate<State, Action>> newGoals, Set<Compostate<State, Action>> parent) {
        logger.finest("we are propagatingGoals from: " + newGoals.toString());
        statistics.incPropagateGoalsCalls();

        Set<Compostate<State, Action>> toCheck = new HashSet<>();
        if (parent == null) {
            for (Compostate<State, Action> state : newGoals) {
                toCheck.addAll(state.getParentsOfStatus(Status.NONE));
            }
        } else {
            toCheck.addAll(parent);
        }

        Set<Compostate<State, Action>> propagatedGoals = new HashSet<>();
        Set<Compostate<State, Action>> statesToReopen = new HashSet<>();
        Set<Compostate<State, Action>> toCheckLoop = new HashSet<>();

        while (!toCheck.isEmpty() || !toCheckLoop.isEmpty()){
            while (!toCheck.isEmpty()) {
                Compostate<State, Action> current = toCheck.iterator().next();
                toCheck.remove(current);

                if (playerCanForce(current, new HashSet<>(), false, new HashSet<>())) { //player can force Goal
                    setGoal(current, null);
                    current.setLoopID(0);
                    propagatedGoals.add(current);
                    toCheck.addAll(current.getParentsOfStatus(Status.NONE));
                    updateDistanceToWinLoop(current);
                    toCheckLoop.remove(current);
                }else {
                    toCheckLoop.add(current);
                }
            }

            if(!toCheckLoop.isEmpty()) {
                Compostate<State, Action> current = toCheckLoop.iterator().next();
                toCheckLoop.remove(current);
                if (closingALoop(current, current)) {
                    gatherLoopStates(current);
                    Set<Compostate<State, Action>> winningLoop = findNewGoals();

                    for (Compostate<State, Action> s : winningLoop) {
                        propagatedGoals.add(s);
                        toCheck.addAll(s.getParentsOfStatus(Status.NONE));
                    }
                }
                if (current.isStatus(Status.NONE)) statesToReopen.add(current);
            }
        }

        statesToReopen.removeAll(propagatedGoals);
        for(Compostate<State, Action> state : statesToReopen){
            if(state.hasValidRecommendation() && state.isStatus(Status.NONE)){
                //reopen state
                heuristic.notifyStateIsNone(state);
            }
        }

        logger.finest("Propagated goal to: " + propagatedGoals.toString());
    }


    private void propagateError(Set<Compostate<State, Action>> newErrors, Set<Compostate<State, Action>> parent) {
        logger.finer("we are propagatingErrors from: " + newErrors.toString());
        statistics.incPropagateErrorsCalls();

        Set<Compostate<State, Action>> toCheck = new HashSet<>();
        if(parent == null){
            for (Compostate<State, Action> state : newErrors) {
                toCheck.addAll(state.getParentsOfStatus(Status.NONE));
            }
        }else{
            toCheck.addAll(parent);
        }

        Set<Compostate<State, Action>> propagatedErrors = new HashSet<>();
        Set<Compostate<State, Action>> statesToReopen = new HashSet<>();
        Set<Compostate<State, Action>> toCheckLoop = new HashSet<>();

        while (!toCheck.isEmpty() || !toCheckLoop.isEmpty()){
            while (!toCheck.isEmpty()) {
                Compostate<State, Action> current = toCheck.iterator().next();
                toCheck.remove(current);

                if(forcedToError(current)){ //ambient can force error from current
                    setError(current);
                    propagatedErrors.add(current);
                    toCheck.addAll(current.getParentsOfStatus(Status.NONE));
                    toCheckLoop.remove(current);
                }else {
                    toCheckLoop.add(current);
                }
            }

            if(!toCheckLoop.isEmpty()) {
                Compostate<State, Action> current = toCheckLoop.iterator().next();
                toCheckLoop.remove(current);
                if (closingALoop(current, current)) {
                    gatherLoopStates(current);
                    Set<Compostate<State, Action>> winningLoop = findNewErrors();

                    for (Compostate<State, Action> s : winningLoop) {
                        propagatedErrors.add(s);
                        toCheck.addAll(s.getParentsOfStatus(Status.NONE));
                    }
                }
                if (current.isStatus(Status.NONE)) statesToReopen.add(current);
            }
        }

        statesToReopen.removeAll(propagatedErrors);
        for(Compostate<State, Action> state : statesToReopen){
            if(state.hasValidRecommendation()){
                //reopen state
                heuristic.notifyStateIsNone(state);
            }
        }

        logger.finer("Propagated error to: " + propagatedErrors.toString());
    }

    private Set<Compostate<State, Action>> ambientCanForceLoseMax(Set<Compostate<State, Action>> c, boolean unexploredAreGoals) {
        Set<Compostate<State, Action>> k = new HashSet<>(c);
        auxiliarListStates.addAll(c);

        int old_size = -1;

        while(old_size != k.size()){
            old_size = k.size();

            for (int i = 0; i < auxiliarListStates.size(); ++i) {
                Compostate<State, Action> state = auxiliarListStates.get(i);

                if(!ambientCanForce(state, k, unexploredAreGoals, c)){
                    k.remove(state);
                    auxiliarListStates.remove(state);
                }
            }
        }

        auxiliarListStates.clear();
        logger.fine("ambientCanForceLose with allowNones = " + unexploredAreGoals + " and c = " + c.toString() + "\n\t\t\t - is = " + k.toString());
        return k;
    }

    private boolean ambientCanForce(Compostate<State, Action> v,
                                    Set<Compostate<State, Action>> target,
                                    boolean unexploredAreGoals,
                                    Set<Compostate<State, Action>> nonesInLoop) {
        boolean hasControllableToUnexplored = false;
        boolean hasUncontrollableToUnexplored = false;

        if (v.hasValidRecommendation()) {
            if (v.hasUncontrollableUnexplored()) {
                hasUncontrollableToUnexplored = true;
            } else {
                hasControllableToUnexplored = true;
            }
        }

        for (Compostate<State, Action> child : v.getChildrenExploredThroughUncontrollable()) {
            if (child.isStatus(Status.ERROR) || target.contains(child)) {
                return true;
            } else {
                if(child.isStatus(Status.NONE)){
                    if (!nonesInLoop.contains(child)) {
                        hasUncontrollableToUnexplored = true;
                    }
                }
            }
        }

        if (hasUncontrollableToUnexplored && !unexploredAreGoals) {
            return true;
        }else{
            if(v.getChildrenExploredThroughUncontrollable().size()>0) return false;
        }
        //no hay ninguna u
        if (hasControllableToUnexplored && unexploredAreGoals) return false;

        boolean hasControllableToTarget = false;
        for (Compostate<State, Action> child : v.getChildrenExploredThroughControllable()) {
            if (target.contains(child) || child.isStatus(Status.ERROR) || (!nonesInLoop.contains(child) && !unexploredAreGoals && child.isStatus(Status.NONE))){
                hasControllableToTarget = true;
            }else{
                return false;
            }
        }

        return hasControllableToTarget;
    }

    private boolean canForceOutside(
            Compostate<State, Action> state,
            Set<Compostate<State, Action>> ancestors) {
        boolean canAvoidAncestors = true;
        for (Compostate<State, Action> child : state.getChildrenExploredThroughUncontrollable()) {
            if(ancestors.contains(child)) {
                canAvoidAncestors = false;
                break;
            }
        }

        return state.hasValidRecommendation() && canAvoidAncestors;
    }

    private boolean updateDistanceToWinLoop(Compostate<State, Action> state) {
        boolean rta = false;
        int maxUncontrollableDist = -1;
        int minControllableDist = INF;
        int distToWinLoopState = state.getBestDistToWinLoop();

        for (Compostate<State, Action> child : state.getChildrenExploredThroughUncontrollable()) {
            int distToWinLoopChild = child.getBestDistToWinLoop();
            maxUncontrollableDist = Math.max(distToWinLoopChild, maxUncontrollableDist);
        }

        if(maxUncontrollableDist==-1){
            for (Compostate<State, Action> child : state.getChildrenExploredThroughControllable()) {
                int distToWinLoopChild = child.getBestDistToWinLoop();
                if(distToWinLoopChild == -1) continue;
                minControllableDist = Math.min(distToWinLoopChild, minControllableDist);
            }
            rta = distToWinLoopState != minControllableDist;
            state.setBestDistToWinLoop(minControllableDist+1);
        }else{
            rta = distToWinLoopState != maxUncontrollableDist;
            state.setBestDistToWinLoop(maxUncontrollableDist+1);
        }

        return rta;
    }

    /** Note that the logic of this function is seeing if having memory in our controller
     * we could disable a controllable transition that stays in the loop after 1 lap. Otherwise
     * the ambient could keep us here for an infinite time.*/
    private boolean hasControllableWayOut(Compostate<State, Action> state, Set<Compostate<State, Action>> c){
        boolean hasUncontrollableOut = false;

        for (Compostate<State, Action> child : state.getChildrenExploredThroughUncontrollable()) {
            if(c.contains(child) || child.isStatus(Status.ERROR)){
                return false;
            }else{
                hasUncontrollableOut = true; //we don't return true here bc there can still be one uncontrollable that stays
            }
        }

        if(state.hasValidRecommendation() || hasUncontrollableOut){
            return true;
        }

        for (Compostate<State, Action> child : state.getChildrenExploredThroughControllable()) {
            if(!c.contains(child) && !child.isStatus(Status.ERROR)){
                return true;
            }
        }

        return false;
    }

//------------------------

    private boolean forcedToError(Compostate<State, Action> state) {
        boolean existsActionLeadingToNoneOrGoal = false;
        boolean fullyExplored = state.recommendation == null;

        for (Compostate<State, Action> child : state.getChildrenExploredThroughUncontrollable()){
            if(isError(child)){
                return true;
            }else{
                existsActionLeadingToNoneOrGoal = true;
            }
        }

        if(existsActionLeadingToNoneOrGoal){
            return false;
        }

        for (Compostate<State, Action> child : state.getChildrenExploredThroughControllable()){
            if(!isError(child)) existsActionLeadingToNoneOrGoal = true;
        }

        return !existsActionLeadingToNoneOrGoal && fullyExplored;
    }

//-----------------------------------------------

    private boolean closingALoop(Compostate<State, Action> child, Compostate<State, Action> parent) {
        if (child.isEvaluated()) // if the child has already been considered then we might be closing a loop
            buildAncestorsDAG(child, parent);
        return !dag.getK(child).isEmpty(); // if we closed a loop
    }

    /** Clears internal data used for efficient loop detection. */
    private void clearLoopDetection() {
        dag.clear();
    }


    /** Returns a map representing a DAG from parent to child.
     *  The child state in a terminal node indicates a loop. */
    private void buildAncestorsDAG(Compostate<State, Action> child, Compostate<State, Action> parent) {
        dag.clear();
        auxiliarListStates.add(parent);
        visited.add(parent);
        for (int i = 0; i < auxiliarListStates.size(); ++i) {
            Compostate<State, Action> state = auxiliarListStates.get(i);
            for (Pair<HAction<Action>,Compostate<State, Action>> predecesor : state.getParents()) {
                Compostate<State, Action> predState = predecesor.getSecond();
                if (isError(predState)||isGoal(predState))
                    continue;
                dag.put(state, predState);
                //fixme esto se puede mejorar. lo cambiamos viendo si el loop era maximal!
                if (visited.add(predState))// && state != child) // Stop the DAG on child leaves
                    auxiliarListStates.add(predState);
            }
        }
        visited.clear();
        auxiliarListStates.clear();
    }

    /** Gathers the states enclosed by a loop over parent and child states.
     *  Additionally gathers the marked states in the loop. */
    private void gatherLoopStates(Compostate<State, Action> child) {
        loop.clear();
        auxiliarListStates.add(child);

        for (int i = 0; i < auxiliarListStates.size(); ++i) {
            Compostate<State, Action> state = auxiliarListStates.get(i);
            for (Compostate<State, Action> successor : dag.getK(state)) {
                if (loop.add(successor)){
                    auxiliarListStates.add(successor);
                }
            }
        }
        auxiliarListStates.clear();
        assert loop.contains(child); // Check this is an actual loop.
    }

    /** Returns whether a given state is an error or not. */
    public boolean isError(Compostate<State, Action> compostate) {
        return compostate.isStatus(Status.ERROR) || compostate.getStates().contains(-1L) || compostate.isDeadlock();
    }


    /** Marks a given state as an error. */
    private void setError(Compostate<State, Action> state) {
        assertFalse(state.isStatus(Status.GOAL));

        state.setStatus(Status.ERROR);
        broadcastNewClosedChildToParents(state, Status.ERROR);
        setFinal(state);
    }

    private void broadcastNewClosedChildToParents(Compostate<State, Action> state, Status status) {
        for (Pair<HAction<Action>,Compostate<State, Action>> ancestorActionAndState : state.getParents()) {
            Compostate<State, Action> ancestor = ancestorActionAndState.getSecond();
            if(status == Status.ERROR) ancestor.setHasErrorChild();
            if(status == Status.GOAL) ancestor.setHasGoalChild();
        }
    }


    /** Returns whether a given state is a goal or not. */
    private boolean isGoal(Compostate<State, Action> compostate) {
        return compostate.isStatus(Status.GOAL);
    }


    /** Marks a given state as a goal. */
    private void setGoal(Compostate<State, Action> state, HAction<Action> action) {
        assertFalse(state.isStatus(Status.ERROR));

        if(action == null){
            for (Pair<HAction<Action>,Compostate<State, Action>> transition : state.getExploredChildren()) {
                action = transition.getFirst();
                Compostate<State, Action> child = transition.getSecond();

                if(child.isStatus(Status.GOAL)) break; //fixme una mejor manera de hacer esto sin recorrer todo
                //antes usaba state.potenciallyGoodTransition, ver de usar eso o hacer algo mejor todavia
            }
        }


        state.setStatus(Status.GOAL);
        broadcastNewClosedChildToParents(state, Status.GOAL);
        int distance = state.getChildDistance(action);
        if (distance < INF) distance++;
//        logger.finest(state.toString() + "marked as goal with distance " + distance);
        setDistanceToGoal(state, distance);
    }


    /** Marks a given state as a goal. */
    private void setDistanceToGoal(Compostate<State, Action> compostate, int distance) {
        if (distance < compostate.getDistance())
            compostate.setDistance(distance);
        setFinal(compostate);
    }


    /** Marks a given state as final, closing it and releasing some resources. */
    private void setFinal(Compostate<State, Action> compostate) {
        heuristic.notifyStateSetErrorOrGoal(compostate);
    }

    private Pair<Compostate<State, Action>,Integer> cPair(Compostate<State, Action> c, Integer j){
        return new Pair<>(c,j);
    }

    /** After the synthesis procedure this method builds a controller in the
     *  form of an LTS by starting in the initial state and following all the
     *  non-closed descendants. If there is no controller for the given
     *  environment this method returns null. */
    private LTS<Long, Action> buildController() {
        class ContComp{
            //Controller Compostates
            Compostate<State, Action> c; //the compostate
            int j; //the guarantee we are trying to reach
            //if we dont know that we can avoid an assumption (i.e. c is not in a strategy x[i][r]) then i=-1
            public ContComp(Compostate<State, Action> c, int j){
                this.c=c;
                this.j=j;
            }

            @Override
            public boolean equals(Object obj){
                if (this == obj)
                    return true;
                if (obj == null)
                    return false;
                if (getClass() != obj.getClass()) {
                    return false;
                }else{
                    ContComp obj2 = (ContComp) obj;
                    return (this.c==obj2.c && this.j==obj2.j);
                }
            }

            public int hashCode() {
                return (c.toString()+j).hashCode();
            }
        }

        long id = 0;
        LTSImpl<Long, Action> result = new LTSImpl<>(id);
        //ids takes as a key a Compostate a "j" the guarantee (and "i" the assumption) wich strategy we follow with this state.
        //The same Compostate can be present with different strategies being represented with multiple states in the controller
        Map<ContComp, Long> ids = new HashMap<>();
        ids.put(new ContComp(initial,0), id++);

        Deque<ContComp> controllerDescendants = new ArrayDeque<>();

        result.addActions(alphabet.getActions());

        if(initial.isStatus(Status.GOAL)) {
            result.addState(ids.get(new ContComp(initial,0)));
            controllerDescendants.add(new ContComp(initial,0));
        }

        while (!controllerDescendants.isEmpty()) {
            ContComp pSucc = controllerDescendants.remove();
            Compostate<State, Action> current = pSucc.c;
            int j = pSucc.j;
            if(current.getLoopID()==0){
                //we are trying to reach a winning loop

                for (Pair<HAction<Action>, Compostate<State, Action>> transition : current.getExploredChildren()) {
                    HAction<Action> action = transition.getFirst();
                    Compostate<State, Action> child = transition.getSecond();

                    boolean includeTransition = false;
                    includeTransition = !action.isControllable();
                    includeTransition |= child.getLoopID() != 0 && child.isStatus(Status.GOAL);
                    includeTransition |= (child.getLoopID() == 0) && (child.getBestDistToWinLoop()<current.getBestDistToWinLoop()) && child.isStatus(Status.GOAL);

                    if (includeTransition) {
                        if (!ids.containsKey(new ContComp(child,0))) {
                            ids.put(new ContComp(child,0), id++);
                            controllerDescendants.add(new ContComp(child,0));
                            result.addState(ids.get(new ContComp(child,0)));
                        }

                        Long i1 = ids.get(new ContComp(current,0));
                        Long i2 = ids.get(new ContComp(child,0));
                        assertTrue(i1!=null && i1>=0 && i2!=null && i2>=0);
                        result.addTransition(ids.get(new ContComp(current,0)), action.getAction(), ids.get(new ContComp(child,0)));
                    }
                }
            }else{
                //current is already in a winning loop
                int thisLoop = current.getLoopID();
                for (Pair<HAction<Action>, Compostate<State, Action>> transition : current.getExploredChildren()) {
                    HAction<Action> action = transition.getFirst();
                    Compostate<State, Action> child = transition.getSecond();

                    boolean includeTransition = false;
                    includeTransition = !action.isControllable();
                    int childJ = j;

                    if(child.getLoopID() == thisLoop){
                        if(current.markedByGuarantee.contains(j)){ //like asking that J^2_j
                            //case where current is marked (ro1)
                            includeTransition = true;
                            childJ = (j+1)%guarantees.size(); //we transition to strategy j+1
                        }else{
                            //case where current is not marked
                            //we minimize r for stratY (ro2)
                            int minRForChildInY = minRInStratYFor(child,j,thisLoop);
                            includeTransition |= minRForChildInY != -1 &&
                                    minRForChildInY < minRInStratYFor(current,j,thisLoop);
                        }
                        Pair<Integer,Integer> minRIForCurrentInX = minRIInStratXFor(current,j,thisLoop);
                        if(minRIForCurrentInX != null){
                            //check if current avoids an assumption
                            //we go to any state in the same stratX (ro3)
                            int currentI = minRIForCurrentInX.getSecond();
                            int currentR = minRIForCurrentInX.getFirst();
                            if(current.markedByAssumption.contains(currentI)){
                                includeTransition |= stratX.get(thisLoop).get(j).get(currentR).get(currentI).contains(child);
                            }
                        }
                    }

                    if (includeTransition) {
                        if (!ids.containsKey(new ContComp(child,childJ))) {
                            ids.put(new ContComp(child,childJ), id++);
                            controllerDescendants.add(new ContComp(child,childJ));
                            result.addState(ids.get(new ContComp(child,childJ)));
                        }

                        Long i1 = ids.get(new ContComp(current,j));
                        Long i2 = ids.get(new ContComp(child,childJ));
                        assertTrue("succ: "+current.toString()+" child: "+child.toString()+" i1: "+i1+" i2: "+i2,i1!=null && i1>=0 && i2!=null && i2>=0);

                        result.addTransition(ids.get(new ContComp(current,j)), action.getAction(), ids.get(new ContComp(child,childJ)));
                    }
                }
            }

        }
        statistics.setControllerUsedStates(result.getStates().size());
        statistics.setControllerUsedTransitions(result.getTransitionsNumber());
        return result;
    }

    private Pair<Integer, Integer> minRIInStratXFor(Compostate<State, Action> state, int j, int thisLoop) {
        if(!stratX.get(thisLoop).containsKey(j)){
            assertEquals("we have an incomplete stratX", 0, assumptions.size());
            return null;
        }
        int maxR = Collections.max(stratX.get(thisLoop).get(j).keySet());
        for(int r = 1; r<=maxR ; ++r){
            int maxI = Collections.max(stratX.get(thisLoop).get(j).get(r).keySet());
            for(int i = 0; i<=maxI ; ++i){
                if(stratX.get(thisLoop).get(j).get(r).get(i).contains(state)){
                    return new Pair<>(r,i);
                }
            }
        }
        return null;
    }

    private int minRInStratYFor(Compostate<State, Action> state, int j, int thisLoop) {
        int maxR = Collections.max(stratY.get(thisLoop).get(j).keySet());
        for(int r = 1; r<=maxR ; ++r){
            if(stratY.get(thisLoop).get(j).get(r).contains(state)){
                return r;
            }
        }
        return -1;
    }

    /** Auxiliary function to add a value to a set contained as value of a map. */
    private static <K,V> boolean putadd(Map<K, Set<V>> map, K key, V value) {
        Set<V> set = map.get(key);
        if (set == null)
            map.put(key, set = new HashSet<>());
        return set.add(value);
    }


    /** Auxiliary function to put an element into a map, but keeping the minimum if already set. */
    public static <K, V extends Comparable<V>> boolean putmin(Map<K, V> map, K key, V value) {
        boolean result;
        V old = map.get(key);
        if (result = (old == null || old.compareTo(value) > 0)) {
            map.put(key, value);
        }
        return result;
    }


    /** Auxiliary function to put an element into a map, but keeping the maximum if already set. */
    public static <K, V extends Comparable<V>> boolean putmax(Map<K, V> map, K key, V value) {
        boolean result;
        V old = map.get(key);
        if (result = (old == null || old.compareTo(value) < 0)) {
            map.put(key, value);
        }
        return result;
    }
}