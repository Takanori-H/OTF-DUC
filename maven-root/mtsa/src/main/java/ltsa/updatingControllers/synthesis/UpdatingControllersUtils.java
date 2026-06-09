package ltsa.updatingControllers.synthesis;

import MTSSynthesis.ar.dc.uba.model.language.*;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType;
import MTSTools.ac.ic.doc.mtstools.model.impl.MTSImpl;
import MTSTools.ac.ic.doc.mtstools.utils.GraphUtils;
import MTSSynthesis.ar.dc.uba.model.condition.Fluent;
import MTSSynthesis.ar.dc.uba.model.condition.FluentImpl;
import MTSSynthesis.ar.dc.uba.model.condition.FluentPropositionalVariable;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.ac.ic.doc.mtstools.util.fsp.MTSToAutomataConverter;
import ltsa.control.ControllerGoalDefinition;
import MTSSynthesis.controller.model.ControllerGoal;
import ltsa.lts.*;
import MTSTools.ac.ic.doc.mtstools.model.impl.MarkedMTS;
import ltsa.lts.Symbol;
import ltsa.lts.chart.util.FormulaUtils;
import ltsa.lts.ltl.AssertDefinition;
import ltsa.updatingControllers.UpdateConstants;

import java.util.*;
import java.util.stream.Collectors;

import static ltsa.updatingControllers.UpdateConstants.*;

import ltsa.lts.util.MTSUtils; // 追加

public class UpdatingControllersUtils {

	public static final Set<Fluent> UPDATE_FLUENTS = new HashSet<Fluent>();
	public static final String INTERNAL_BEGIN_UPDATE_FLUENT = "__DUC_BeginUpdate";
	public static final String INTERNAL_STOP_OLD_SPEC_FLUENT = "__DUC_StopOldSpec";
	public static final String INTERNAL_RECONFIGURE_FLUENT = "__DUC_Reconfigure";
	public static final String INTERNAL_START_NEW_SPEC_FLUENT = "__DUC_StartNewSpec";
	public static final Fluent beginFluent;
	public static final Fluent stopFluent;
	public static final Fluent reconFluent;
	public static final Fluent startFluent;
	public static final Set<Fluent> ACTION_FLUENTS_FOR_UPDATE = new HashSet<Fluent>();

	static {
		HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> beginAction = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
		HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> stopAction = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
		HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> reconfigureAction = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
		HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> startAction = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
		HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> resetAction = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
		beginAction.add(new SingleSymbol(UpdateConstants.BEGIN_UPDATE));
		stopAction.add(new SingleSymbol(UpdateConstants.STOP_OLD_SPEC));
		reconfigureAction.add(new SingleSymbol(UpdateConstants.RECONFIGURE));
		startAction.add(new SingleSymbol(UpdateConstants.START_NEW_SPEC));
		resetAction.add(new SingleSymbol(UpdateConstants.BEGIN_UPDATE));

		beginFluent = new FluentImpl(INTERNAL_BEGIN_UPDATE_FLUENT, beginAction, new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>(), false);
		stopFluent = new FluentImpl(INTERNAL_STOP_OLD_SPEC_FLUENT, stopAction, resetAction, false);
		reconFluent = new FluentImpl(INTERNAL_RECONFIGURE_FLUENT, reconfigureAction, resetAction, false);
		startFluent = new FluentImpl(INTERNAL_START_NEW_SPEC_FLUENT, startAction, resetAction, false);
		
		UpdatingControllersUtils.UPDATE_FLUENTS.add(beginFluent);
		UpdatingControllersUtils.UPDATE_FLUENTS.add(stopFluent);
		UpdatingControllersUtils.UPDATE_FLUENTS.add(reconFluent);
		UpdatingControllersUtils.UPDATE_FLUENTS.add(startFluent);
	}


	public static Set<Fluent> compileFluents(List<Symbol> updPropertyDef) {
		Set<Fluent> compiledFluents = new HashSet<Fluent>();
		for (Symbol toCompileProperty : updPropertyDef) {

			Set<Fluent> involvedFluents = new HashSet<Fluent>();

			LTSCompiler.makeFluents(toCompileProperty, involvedFluents);

			if (involvedFluents.size() == 1) {
				compiledFluents.add(involvedFluents.iterator().next());
			} else {
//				System.out.println("TWO OR MORE FLUENT NOT EXPECTED");
			}
		}
		return compiledFluents;
	}

	public static ControllerGoal<String> generateGRUpdateGoal(UpdatingControllersDefinition updContDef,
															  ControllerGoalDefinition oldGoalDef,
															  ControllerGoalDefinition newGoalDef, Set<String>
																	controllableSet) {
		ControllerGoal<String> grcg = new ControllerGoal<String>();

		grcg.setNonBlocking(updContDef.isNonblocking());
		grcg.addAllControllableActions(controllableSet);
		Set<Fluent> involvedFluents = new HashSet<Fluent>();

		addFluentAndAssumption(grcg, involvedFluents, BEGIN_UPDATE);
		addFluentAndGuarantee(grcg, involvedFluents, STOP_OLD_SPEC);
		addFluentAndGuarantee(grcg, involvedFluents, START_NEW_SPEC);
		addFluentAndGuarantee(grcg, involvedFluents, RECONFIGURE);
		addFailures(grcg, involvedFluents, oldGoalDef);
		addFailures(grcg, involvedFluents, newGoalDef);
		grcg.addAllFluents(involvedFluents);
		return grcg;
	}

	public static ControllerGoalDefinition generateSafetyGoalDef(UpdatingControllersDefinition updContDef,
																 ControllerGoalDefinition oldGoalDef,
																 ControllerGoalDefinition newGoalDef,
																 Set<String> controllableSetSymbol,
																 LTSOutput output) {
		ControllerGoalDefinition cgd = new ControllerGoalDefinition(updContDef.getName());
		cgd.addAssumeDefinition(new Symbol(123, "BeginUpdate")); //is this useless?we use the assumption in GR not here
		cgd.addGuaranteeDefinition(new Symbol(123, "StopOldSpec")); //is this useless? we use Guarantee in GR not here
		cgd.addGuaranteeDefinition(new Symbol(123, "StartNewSpec")); //besides the symbol redirects to nothing.
		cgd.addGuaranteeDefinition(new Symbol(123, "Reconfigure"));
		addFailures(cgd, oldGoalDef); //same here, failures are for liveness right?
		addFailures(cgd, newGoalDef);

		cgd.setControllableActionSet(new Vector<String>(controllableSetSymbol));
		cgd.setNonBlocking(updContDef.isNonblocking());

		generateUpdateGoals(oldGoalDef, newGoalDef, updContDef.getTransitionGoals(), cgd);

		// adding ControllerGoalDefinition
		ControllerGoalDefinition.addDefinition(cgd.getNameString(), cgd);
		AssertDefinition.compileAll(output); // this is for filling the fac attribute in constraints added before
		return cgd;
	}

	/**
	 * @param action
	 * @return whether action is not one of the controller update special actions.
	 */
	public static boolean isNotUpdateAction(String action) {
		return !START_NEW_SPEC.equals(action) && !STOP_OLD_SPEC.equals(action) &&
			!RECONFIGURE.equals(action) &&
			!action.startsWith(START_NEW_SPEC_PREFIX) &&
			!action.startsWith(STOP_OLD_SPEC_PREFIX) &&
			!action.startsWith(RECONFIGURE_PREFIX);
	}

	/**
	 * @param cgd
	 * @param controllerGoalDef
	 */
	private static void addFailures(ControllerGoalDefinition cgd, ControllerGoalDefinition controllerGoalDef) {
		//Check with dipi. we are not sure if this will work as expected
		List<Symbol> faultsDefinitions = controllerGoalDef.getFaultsDefinitions();
		for (Symbol faultsDefinition : faultsDefinitions) {
			cgd.addFaultDefinition(faultsDefinition);
		}
	}

	/**
	 * @param grcg
	 * @param involvedFluents
	 * @param controllerGoalDefinition
	 */
	private static void addFailures(ControllerGoal<String> grcg, Set<Fluent> involvedFluents,
									ControllerGoalDefinition controllerGoalDefinition) {
		// TODO: refactor, code copied from GoalDefToControllerGoal.
		// Check with dipi. we are not sure if this will work as expected
		Set<Fluent> fluentsInFaults = new HashSet<Fluent>();
		for (ltsa.lts.Symbol faultsDefinition : controllerGoalDefinition.getFaultsDefinitions()) {
			AssertDefinition def = AssertDefinition.getDefinition(faultsDefinition.getName());
			if (def != null) {
				grcg.addFault(FormulaUtils.adaptFormulaAndCreateFluents(def.getFormula(true), fluentsInFaults));
			} else {
				Diagnostics.fatal("Assertion not defined [" + faultsDefinition.getName() + "].");
			}
		}
		involvedFluents.addAll(fluentsInFaults);
		grcg.addAllFluentsInFaults(fluentsInFaults);
	}

	/**
	 * @param grcg
	 * @param fluents
	 * @param action
	 */
	private static void addFluentAndAssumption(ControllerGoal<String> grcg, Set<Fluent> fluents, String action) {
		FluentPropositionalVariable fluentPropositionalVariable = generateAndAddFluent(fluents, action);
		grcg.addAssume(fluentPropositionalVariable);
	}

	/**
	 * @param grcg
	 * @param fluents
	 * @param action
	 */
	private static void addFluentAndGuarantee(ControllerGoal<String> grcg, Set<Fluent> fluents, String action) {
		FluentPropositionalVariable fluentPropositionalVariable = generateAndAddFluent(fluents, action);
		grcg.addGuarantee(fluentPropositionalVariable);
	}

	/**
	 * @param fluents
	 * @param action
	 * @return
	 */
	private static FluentPropositionalVariable generateAndAddFluent(Set<Fluent> fluents, String action) {
		Fluent turnOnFluent = createOnlyTurnOnFluent(action);
		fluents.add(turnOnFluent);
		FluentPropositionalVariable fluentPropositionalVariable = new FluentPropositionalVariable(turnOnFluent);
		return fluentPropositionalVariable;
	}

	/**
	 * Registers the generated update safety goal names. Traditional DUC later
	 * rebuilds the old/new wrappers with internal update fluents to avoid
	 * depending on user-defined FSP fluents of the same names.
	 *
	 * @param oldGoalDef
	 * @param newGoalDef
	 * @param transitionGoalDef
	 * @param cgd
	 */
	private static void generateUpdateGoals(ControllerGoalDefinition oldGoalDef, ControllerGoalDefinition newGoalDef,
											List<Symbol> transitionGoalDef, ControllerGoalDefinition cgd) {
		
		for (Symbol formula : oldGoalDef.getSafetyDefinitions()) {
			UpdatingControllersGoalsMaker.addOldGoals(formula, cgd);
		}
		for (Symbol formula : newGoalDef.getSafetyDefinitions()) {
			UpdatingControllersGoalsMaker.addImplyUpdatingGoal(formula, cgd);
		}
		for (Symbol transitionSymbol : transitionGoalDef) {
			cgd.addSafetyDefinition(transitionSymbol);
		}
	}

	/**
	 * Marks the states of the update controller (Cu) that belong to the terminal set.
	 *
	 * @param updateController
	 * @return a minimized CompactState of updateController.
	 */
	public static MarkedMTS<Long, String> markCuTerminalSet(MTS<Long, String> updateController) {
		Set<Set<Long>> stronglyConnectedComponents = GraphUtils.getStronglyConnectedComponents(updateController);
		Set<Set<Long>> terminalSets = GraphUtils.getTerminalSets(stronglyConnectedComponents, updateController);
		//		MarkedCompactState markedCu = new MarkedCompactState(updateController, terminalSet, name);
		//		CompactState minimised = TransitionSystemDispatcher.minimise(markedCu, output);
		return new MarkedMTS<Long, String>(updateController, updateController.getInitialState(), terminalSets);
	}

    private static Fluent createOnlyTurnOnFluent(String initAction) {
        HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol> initiatingActions = new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>();
        initiatingActions.add(new SingleSymbol(initAction));

        Fluent onlyTurnOnFluent = new FluentImpl(new String(initAction), initiatingActions, new HashSet<MTSSynthesis.ar.dc.uba.model.language.Symbol>(), false);
        return onlyTurnOnFluent;
    }

	/////////////////////////////THIS CODE IS FOR RELABELING ACTIONS ///////////////////////////////
	public static void removeOldTransitions(CompositeState cs) {

		MTS<Long, String> mts = AutomataToMTSConverter.getInstance().convert(cs.composition);
		MTS<Long, String> resultMts = new MTSImpl<Long, String>(mts.getInitialState());
		for (String action : mts.getActions()) {
			if (!isOld(action)){
				resultMts.addAction(action);
			}
		}

		for (Long state : mts.getStates()) {

			resultMts.addState(state);

			for (Pair<String, Long> action_toState : mts.getTransitions(state, MTS.TransitionType.REQUIRED)) {

				if (!isOld(action_toState.getFirst())){
					resultMts.addState(action_toState.getSecond());
					resultMts.addRequired(state, action_toState.getFirst(), action_toState.getSecond());

				} else {
					resultMts.addState(action_toState.getSecond());
					resultMts.addRequired(state, withoutOld(action_toState.getFirst()), action_toState.getSecond());
				}
			}
		}
		cs.composition = MTSToAutomataConverter.getInstance().convert(resultMts, cs.composition.getName(), false, true);

	}

	public static String withoutOld(String action) {

		return action.substring(0, action.length() - UpdateConstants.OLD_LABEL.length());
	}

	public static boolean isOld(String action) {

		return action.contains(UpdateConstants.OLD_LABEL);
	}

	/**
     * Transition Requirements (Symbol list) を CompactState (LTS) の Vector に変換する。
     * FSP記述において、ltl_property T = [](...) のように Always演算子が含まれている必要がある。
     *
     * @param transitionGoals FSPで定義された遷移要件のシンボルリスト
     * @param output ログ出力用
     * @return コンパイルされたCompactStateのベクター
     */
    public static Vector<CompactState> compileTransitionRequirements(List<Symbol> transitionGoals, LTSOutput output) {
        Vector<CompactState> transitionLTSs = new Vector<>();

        if (transitionGoals == null) {
            return transitionLTSs;
        }

        for (Symbol transitionSym : transitionGoals) {
            String name = transitionSym.getName();
            
            // FSP内の ltl_property 定義を利用して LTS (CompactState) にコンパイル
            // AssertDefinition.compileConstraint は定義名からProperty LTSを生成します
            CompactState cs = AssertDefinition.compileConstraint(output, name);

            if (cs != null) {
                cs.name = name; // 名前を確実に設定
                
                // 【重要】FSP記述で [] (Always) を忘れている場合の警告
                // OTF合成ではLTSの構造として制約を表現する必要があるため、全受理LTSは無意味です。
                if (isUniversal(cs)) {
                    output.outln("WARNING: Transition Requirement '" + name + "' produced a Universal LTS (accepts everything).");
                    output.outln("       This usually happens if the LTL formula lacks '[]' (Always).");
                    output.outln("       Please ensure the FSP definition is 'ltl_property " + name + " = [](...)' for OTF synthesis.");
                }

                transitionLTSs.add(cs);
                // output.outln("Compiled Transition Requirement: " + name);
            } else {
                // 定義が見つからない、またはコンパイル失敗時
                output.outln("Error: Transition Requirement '" + name + "' could not be compiled. Check if 'ltl_property' is defined correctly.");
            }
        }
        return transitionLTSs;
    }

    /**
     * LTSが実質的に制約なし（全受理）かどうかを判定するヘルパーメソッド。
     * 状態数が1で、かつエラー状態(-1)への遷移を持たない場合、それは制約として機能していないとみなす。
     * * @param cs 判定対象のCompactState
     * @return 全受理(Universal)であれば true
     */
    private static boolean isUniversal(CompactState cs) {
        // 状態数が1以外なら、何らかの状態変化（制約）があるか、または受理/非受理の区別があるはず
        if (cs.maxStates != 1) return false;

        // 唯一の状態 (index 0) からの遷移リストを確認
        // CompactStateのstatesフィールドはpublicなので直接アクセス可能
        EventState current = cs.states[0];
        
        while (current != null) {
            // フィールド .next の代わりに getNext() を使用
            if (current.getNext() == -1) return false;
            
            // 注: Property LTSは通常決定化(determinise)されているため、
            // nondet (非決定性遷移) のチェックは省略していますが、
            // 念のためチェックするならここで current.nondet も走査が必要です。
            // 現状の用途(AssertDefinition経由)では決定化されている前提で進めます。

            // フィールド .list の代わりに getList() を使用
            current = current.getList();
        }

        // 状態が1つだけで、どこからもエラー状態への遷移がない
        // = どんなアクションが来ても自分自身(0)に戻る = 全てを許容する
        return true;
    }

	/**
     * CompactStateの構造を詳細にログ出力するメソッド
     * デバッグ用：LTLプロパティが正しく変換されているか確認する
     */
    public static void logCompactState(CompactState cs, LTSOutput output) {
        if (cs == null) {
            output.outln("CompactState is null.");
            return;
        }

        output.outln("--- Debug: CompactState Structure [" + cs.name + "] ---");
        output.outln("States: " + cs.maxStates);
        output.outln("Alphabet: " + java.util.Arrays.toString(cs.alphabet));

        for (int i = 0; i < cs.maxStates; i++) {
            output.outln("State " + i + ":");
            ltsa.lts.EventState current = cs.states[i];
            
            if (current == null) {
                // 遷移がない場合（通常ありえないが、末端状態など）
                output.outln("  (no transitions)");
            }

            while (current != null) {
                int eventIdx = current.getEvent();
                int nextState = current.getNext();
                
                String eventName = (eventIdx < cs.alphabet.length) ? cs.alphabet[eventIdx] : "Unknown(" + eventIdx + ")";
                String dest = (nextState == -1) ? "ERROR (-1)" : String.valueOf(nextState);

                output.outln("  -> " + eventName + " -> " + dest);

                current = current.getList();
            }
        }
        output.outln("-------------------------------------------------------");
    }

}
