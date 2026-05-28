package ltsa.updatingControllers;

import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.EventState;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSCompositionException;
import ltsa.lts.LTSInput;
import ltsa.lts.LTSOutput;
import ltsa.ui.EnvConfiguration;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ResultStatus;
import ltsa.updatingControllers.synthesis.UpdatePhaseEvaluator;

/**
 * GUI と CLI の両方から使える、合成 1 回分の評価計測 runner。
 */
public final class CompositionEvaluationRunner {

    private static final String COMMON_SECTION = "共通 / HPWindow";

    private CompositionEvaluationRunner() {
    }

    public interface CompilationStep {
        CompositeState compile(LTSOutput output) throws Exception;
    }

    public interface SuccessfulCompositionCallback {
        void afterSuccessfulComposition(CompositeState current) throws Exception;
    }

    public static final class Request {
        private final LTSOutput output;
        private final CompilationStep compilationStep;
        private SuccessfulCompositionCallback successfulCompositionCallback;
        private String openFileName;

        public Request(LTSOutput output, CompilationStep compilationStep) {
            if (output == null) {
                throw new IllegalArgumentException("output must not be null");
            }
            if (compilationStep == null) {
                throw new IllegalArgumentException("compilationStep must not be null");
            }
            this.output = output;
            this.compilationStep = compilationStep;
        }

        public Request withOpenFileName(String value) {
            this.openFileName = value;
            return this;
        }

        public Request withSuccessfulCompositionCallback(SuccessfulCompositionCallback callback) {
            this.successfulCompositionCallback = callback;
            return this;
        }
    }

    public static final class Result {
        private final CompositeState compositeState;
        private final boolean successful;
        private final Throwable failure;
        private final long compileTimeMillis;
        private final long synthesisTimeMillis;
        private final long postCompositionTimeMillis;

        private Result(
                CompositeState compositeState,
                boolean successful,
                Throwable failure,
                long compileTimeMillis,
                long synthesisTimeMillis,
                long postCompositionTimeMillis) {
            this.compositeState = compositeState;
            this.successful = successful;
            this.failure = failure;
            this.compileTimeMillis = compileTimeMillis;
            this.synthesisTimeMillis = synthesisTimeMillis;
            this.postCompositionTimeMillis = postCompositionTimeMillis;
        }

        public CompositeState getCompositeState() {
            return compositeState;
        }

        public boolean isSuccessful() {
            return successful;
        }

        public Throwable getFailure() {
            return failure;
        }

        public long getCompileTimeMillis() {
            return compileTimeMillis;
        }

        public long getSynthesisTimeMillis() {
            return synthesisTimeMillis;
        }

        public long getPostCompositionTimeMillis() {
            return postCompositionTimeMillis;
        }
    }

    public static Result run(Request request) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return runWithoutEvaluation(request);
        }

        long runStart = System.currentTimeMillis();
        System.gc();
        UpdatingControllerEvaluationRecorder.reset();
        EvaluationProfiler.resetPeakMemory();
        long baselineMemory = EvaluationProfiler.getCurrentMemoryUsage();
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("合成開始時");

        if (request.openFileName != null) {
            EnvConfiguration.getInstance().setOpenFileName(request.openFileName);
        }

        request.output.clearOutput();

        long compileTime = 0;
        long synthesisTime = 0;
        long postCompositionTime = 0;
        CompositeState current = null;
        Throwable failure = null;

        long compileStart = System.currentTimeMillis();
        try {
            current = request.compilationStep.compile(request.output);
            compileTime = System.currentTimeMillis() - compileStart;
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("構文解析・合成問題準備後");
        } catch (OutOfMemoryError e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            UpdatingControllerEvaluationRecorder.recordFailure(
                    ResultStatus.OUT_OF_MEMORY,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (RuntimeException e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.EXCEPTION,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (Exception e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.EXCEPTION,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        }

        if (current != null) {
            long synthesisStart = System.currentTimeMillis();
            try {
                TransitionSystemDispatcher.applyComposition(current, request.output);
                synthesisTime = System.currentTimeMillis() - synthesisStart;
            } catch (OutOfMemoryError e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailure(
                        ResultStatus.OUT_OF_MEMORY,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (LTSCompositionException e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }

            long postCompositionStart = System.currentTimeMillis();
            try {
                if (current.composition == null) {
                    UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                            ResultStatus.NOT_CONTROLLABLE,
                            "Composition not controllable.");
                    UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                    finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                    return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
                }

                if (request.successfulCompositionCallback != null) {
                    request.successfulCompositionCallback.afterSuccessfulComposition(current);
                }
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
            } catch (OutOfMemoryError e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailure(
                        ResultStatus.OUT_OF_MEMORY,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (Exception e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }
        }

        boolean successful = current != null && current.composition != null;
        if (successful) {
            UpdatingControllerEvaluationRecorder.markSuccess();
        } else {
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.UNKNOWN_FAILURE,
                    "Composition was not generated.");
        }

        finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current);
        return new Result(current, successful, failure, compileTime, synthesisTime, postCompositionTime);
    }

    private static Result runWithoutEvaluation(Request request) {
        if (request.openFileName != null) {
            EnvConfiguration.getInstance().setOpenFileName(request.openFileName);
        }

        request.output.clearOutput();

        long compileTime = 0;
        long synthesisTime = 0;
        long postCompositionTime = 0;
        CompositeState current = null;
        Throwable failure = null;

        long compileStart = System.currentTimeMillis();
        try {
            current = request.compilationStep.compile(request.output);
            compileTime = System.currentTimeMillis() - compileStart;
        } catch (OutOfMemoryError e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            reportFailureWithoutEvaluation(request.output, e);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (RuntimeException e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            reportFailureWithoutEvaluation(request.output, e);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (Exception e) {
            compileTime = System.currentTimeMillis() - compileStart;
            failure = e;
            reportFailureWithoutEvaluation(request.output, e);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        }

        if (current != null) {
            long synthesisStart = System.currentTimeMillis();
            try {
                TransitionSystemDispatcher.applyComposition(current, request.output);
                synthesisTime = System.currentTimeMillis() - synthesisStart;
            } catch (OutOfMemoryError e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (LTSCompositionException e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                synthesisTime = System.currentTimeMillis() - synthesisStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }

            if (current.composition == null) {
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }

            long postCompositionStart = System.currentTimeMillis();
            try {
                if (request.successfulCompositionCallback != null) {
                    request.successfulCompositionCallback.afterSuccessfulComposition(current);
                }
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
            } catch (OutOfMemoryError e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (Exception e) {
                postCompositionTime = System.currentTimeMillis() - postCompositionStart;
                failure = e;
                reportFailureWithoutEvaluation(request.output, e);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }
        }

        return new Result(
                current,
                current != null && current.composition != null,
                failure,
                compileTime,
                synthesisTime,
                postCompositionTime);
    }

    public static CompilationStep ltsCompilerStep(
            final LTSInput input,
            final String targetName,
            final String currentDirectory) {
        return new CompilationStep() {
            @Override
            public CompositeState compile(LTSOutput output) throws Exception {
                LTSCompiler compiler = new LTSCompiler(input, output, currentDirectory);
                long compileOnlyStart = -1;
                long continueCompilationStart = -1;
                boolean compileOnlyRecorded = false;
                boolean continueCompilationRecorded = false;
                try {
                    compileOnlyStart = System.currentTimeMillis();
                    compiler.compile();
                    long compileOnlyTime = System.currentTimeMillis() - compileOnlyStart;
                    if (UpdatingControllerEvaluationRecorder.isEnabled()) {
                        output.outln("comp.compile time : " + compileOnlyTime + "ms");
                        UpdatingControllerEvaluationRecorder.recordTime(
                                COMMON_SECTION,
                                "構文解析時間",
                                compileOnlyTime);
                    }
                    compileOnlyRecorded = true;

                    continueCompilationStart = System.currentTimeMillis();
                    CompositeState current = compiler.continueCompilation(targetName);
                    long continueCompilationTime = System.currentTimeMillis() - continueCompilationStart;
                    if (UpdatingControllerEvaluationRecorder.isEnabled()) {
                        output.outln("comp.continueCompilation time : " + continueCompilationTime + "ms");
                        UpdatingControllerEvaluationRecorder.recordTime(
                                COMMON_SECTION,
                                "合成問題準備時間",
                                continueCompilationTime);
                    }
                    continueCompilationRecorded = true;
                    return current;
                } catch (RuntimeException e) {
                    recordPartialCompilationTimes(
                            compileOnlyStart,
                            continueCompilationStart,
                            compileOnlyRecorded,
                            continueCompilationRecorded);
                    throw e;
                } catch (Exception e) {
                    recordPartialCompilationTimes(
                            compileOnlyStart,
                            continueCompilationStart,
                            compileOnlyRecorded,
                            continueCompilationRecorded);
                    throw e;
                }
            }
        };
    }

    private static void finishAndPrint(
            LTSOutput output,
            long runStart,
            long compileTime,
            long synthesisTime,
            long postCompositionTime,
            long baselineMemory,
            CompositeState current) {

        long runTime = System.currentTimeMillis() - runStart;
        long overallPeakMemory = EvaluationProfiler.getPeakMemoryUsage();
        long netPeakMemory = overallPeakMemory - baselineMemory;
        long problemPreparationTime = UpdatingControllerEvaluationRecorder.getRecordedTimeMillis(
                COMMON_SECTION,
                "合成問題準備時間");
        long updateControllerGenerationTime = synthesisTime;
        long controllerSynthesisRelatedTime = problemPreparationTime + updateControllerGenerationTime;

        UpdatingControllerEvaluationRecorder.recordTime(
                COMMON_SECTION,
                "合成ボタンを押してから合成完了までの時間",
                runTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                COMMON_SECTION,
                "compileIfChange 全体時間（参考）",
                compileTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                COMMON_SECTION,
                "update controller 生成時間",
                updateControllerGenerationTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                COMMON_SECTION,
                "コントローラ合成時間",
                controllerSynthesisRelatedTime);
        UpdatingControllerEvaluationRecorder.recordTime(
                COMMON_SECTION,
                "コントローラ描画時間",
                postCompositionTime);
        UpdatingControllerEvaluationRecorder.recordMemory(
                COMMON_SECTION,
                "コントローラ合成のベースラインメモリ",
                baselineMemory);
        UpdatingControllerEvaluationRecorder.recordMemory(
                COMMON_SECTION,
                "コントローラ合成全体のピークメモリ",
                overallPeakMemory);
        UpdatingControllerEvaluationRecorder.recordMemory(
                COMMON_SECTION,
                "コントローラ合成により増えたメモリ",
                netPeakMemory);

        if (current != null && current.composition != null) {
            long outputCountStart = System.currentTimeMillis();
            long outputStates = current.composition.maxStates;
            long outputTransitions = current.composition.ntransitions();
            long outputCountTime = System.currentTimeMillis() - outputCountStart;
            UpdatingControllerEvaluationRecorder.recordOutputController(
                    outputStates,
                    outputTransitions,
                    outputCountTime);
            if (UpdatingControllerEvaluationRecorder.isUpdatingControllerMode()) {
                UpdatePhaseEvaluator.recordCompactStateUpdatePhaseStateSpace(
                        UpdatePhaseEvaluator.SECTION_OUTPUT,
                        "Output Update Controller",
                        current.composition);
                UpdatePhaseEvaluator.recordCompactStateUpdateEventTransitionCounts(
                        UpdatePhaseEvaluator.SECTION_OUTPUT_UPDATE_EVENTS,
                        "Output Update Controller",
                        current.composition);
                UpdatePhaseEvaluator.recordCompactStateUpdatePhaseTransitionAnalysis(
                        UpdatePhaseEvaluator.SECTION_OUTPUT_PHASE_DETAILS,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_PHASE_FLOW,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_COMPLETION_PATH,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_NORMAL_ACTIONS,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_NEXT_UPDATE_EVENT_DISTANCE,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_PROGRESS_FREE_CYCLES,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_ENABLED_UPDATE_EVENTS,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_UPDATE_ORDER_PATTERNS,
                        UpdatePhaseEvaluator.SECTION_OUTPUT_NORMAL_RUN_LENGTH,
                        "Output Update Controller",
                        current.composition,
                        null);
            }

            long beginUpdateCountStart = System.currentTimeMillis();
            long beginUpdateStates = countStatesWithOutgoingAction(
                    current.composition,
                    UpdateConstants.BEGIN_UPDATE);
            long beginUpdateCountTime = System.currentTimeMillis() - beginUpdateCountStart;
            if (beginUpdateStates > 0 || UpdatingControllerEvaluationRecorder.hasOldControllerStateSpace()) {
                UpdatingControllerEvaluationRecorder.recordBeginUpdateCoverage(
                        beginUpdateStates,
                        beginUpdateCountTime);
            }
        }

        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("合成終了時");
        UpdatingControllerEvaluationRecorder.printSummary(output);
    }

    private static void recordPartialCompilationTimes(
            long compileOnlyStart,
            long continueCompilationStart,
            boolean compileOnlyRecorded,
            boolean continueCompilationRecorded) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (compileOnlyStart >= 0 && !compileOnlyRecorded) {
            UpdatingControllerEvaluationRecorder.recordTime(
                    COMMON_SECTION,
                    "構文解析時間",
                    now - compileOnlyStart);
        }
        if (continueCompilationStart >= 0 && !continueCompilationRecorded) {
            UpdatingControllerEvaluationRecorder.recordTime(
                    COMMON_SECTION,
                    "合成問題準備時間",
                    now - continueCompilationStart);
        }
    }

    private static long countStatesWithOutgoingAction(CompactState machine, String actionName) {
        int actionIndex = findActionIndex(machine, actionName);
        if (actionIndex < 0 || machine.states == null) {
            return 0;
        }

        long count = 0;
        for (int i = 0; i < machine.states.length; i++) {
            if (EventState.hasEvent(machine.states[i], actionIndex)) {
                count++;
            }
        }
        return count;
    }

    private static int findActionIndex(CompactState machine, String actionName) {
        if (machine == null || machine.alphabet == null || actionName == null) {
            return -1;
        }
        for (int i = 0; i < machine.alphabet.length; i++) {
            if (actionName.equals(machine.alphabet[i])) {
                return i;
            }
        }
        return -1;
    }

    private static String failureMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        if (throwable.getMessage() != null && !throwable.getMessage().isEmpty()) {
            return throwable.getMessage();
        }
        return throwable.getClass().getSimpleName();
    }

    private static void reportFailureWithoutEvaluation(LTSOutput output, Throwable throwable) {
        if (output == null || throwable == null) {
            return;
        }
        output.outln("Composition failed: " + failureMessage(throwable));
    }
}
