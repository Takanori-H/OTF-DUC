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
import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;
import ltsa.updatingControllers.memory.RunHeapMemorySampler;
import ltsa.updatingControllers.synthesis.UpdatePhaseEvaluator;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GUI と CLI の両方から使える、合成 1 回分の評価計測 runner。
 */
public final class CompositionEvaluationRunner {

    private static final String COMMON_SECTION = "共通 / HPWindow";
    private static final String MEMORY_SAMPLING_ENABLED_PROPERTY =
            "mtsa.evaluation.memorySampling.enabled";
    private static final String MEMORY_SAMPLING_INTERVAL_PROPERTY =
            "mtsa.evaluation.memorySampling.intervalMillis";

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

        MemoryMeasurementSession memoryMeasurement = MemoryMeasurementSession.create();
        try {
            return runWithEvaluation(request, memoryMeasurement);
        } finally {
            memoryMeasurement.finishAndRecord();
        }
    }

    private static Result runWithEvaluation(
            Request request,
            MemoryMeasurementSession memoryMeasurement) {

        long runStart = System.nanoTime();
        System.gc();
        UpdatingControllerEvaluationRecorder.reset();
        UpdatingControllerEvaluationRecorder.recordRunEnvironmentMetadata();
        EvaluationProfiler.resetPeakMemory();
        long baselineMemory = EvaluationProfiler.getCurrentMemoryUsage();
        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("合成開始時");
        UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                "run.synthesis_reset_ready");

        if (request.openFileName != null) {
            EnvConfiguration.getInstance().setOpenFileName(request.openFileName);
        }

        request.output.clearOutput();

        long compileTime = 0;
        long synthesisTime = 0;
        long postCompositionTime = 0;
        CompositeState current = null;
        Throwable failure = null;

        memoryMeasurement.start();
        long compileStart = System.nanoTime();
        try {
            current = request.compilationStep.compile(request.output);
            compileTime = elapsedMillis(compileStart);
            UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("構文解析・合成問題準備後");
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "run.compilation_ready");
        } catch (OutOfMemoryError e) {
            compileTime = elapsedMillis(compileStart);
            failure = e;
            memoryMeasurement.finishAndRecord();
            UpdatingControllerEvaluationRecorder.recordFailure(
                    ResultStatus.OUT_OF_MEMORY,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (RuntimeException e) {
            compileTime = elapsedMillis(compileStart);
            failure = e;
            memoryMeasurement.finishAndRecord();
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.EXCEPTION,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        } catch (Exception e) {
            compileTime = elapsedMillis(compileStart);
            failure = e;
            memoryMeasurement.finishAndRecord();
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.EXCEPTION,
                    failureMessage(e));
            UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
            finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
            return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
        }

        if (current != null) {
            long synthesisStart = System.nanoTime();
            try {
                TransitionSystemDispatcher.applyComposition(current, request.output);
                synthesisTime = elapsedMillis(synthesisStart);
            } catch (OutOfMemoryError e) {
                synthesisTime = elapsedMillis(synthesisStart);
                failure = e;
                memoryMeasurement.finishAndRecord();
                UpdatingControllerEvaluationRecorder.recordFailure(
                        ResultStatus.OUT_OF_MEMORY,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (LTSCompositionException e) {
                synthesisTime = elapsedMillis(synthesisStart);
                failure = e;
                memoryMeasurement.finishAndRecord();
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                synthesisTime = elapsedMillis(synthesisStart);
                failure = e;
                memoryMeasurement.finishAndRecord();
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }

            // The measured synthesis window is parsing/problem preparation
            // plus controller composition. CLI artifact generation and GUI
            // callbacks are covered only by the parent child-lifetime RSS.
            memoryMeasurement.finishAndRecord();

            long postCompositionStart = System.nanoTime();
            try {
                if (current.composition == null) {
                    UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                            ResultStatus.NOT_CONTROLLABLE,
                            "Composition not controllable.");
                    UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                    finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                    return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
                }

                if (request.successfulCompositionCallback != null) {
                    request.successfulCompositionCallback.afterSuccessfulComposition(current);
                }
                postCompositionTime = elapsedMillis(postCompositionStart);
            } catch (OutOfMemoryError e) {
                postCompositionTime = elapsedMillis(postCompositionStart);
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailure(
                        ResultStatus.OUT_OF_MEMORY,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (RuntimeException e) {
                postCompositionTime = elapsedMillis(postCompositionStart);
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            } catch (Exception e) {
                postCompositionTime = elapsedMillis(postCompositionStart);
                failure = e;
                UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                        ResultStatus.EXCEPTION,
                        failureMessage(e));
                UpdatingControllerEvaluationRecorder.recordMemorySnapshot("失敗時メモリ");
                finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
                return new Result(current, false, failure, compileTime, synthesisTime, postCompositionTime);
            }
        }

        memoryMeasurement.finishAndRecord();

        boolean successful = current != null && current.composition != null;
        if (successful) {
            UpdatingControllerEvaluationRecorder.markSuccess();
        } else {
            UpdatingControllerEvaluationRecorder.recordFailureIfAbsent(
                    ResultStatus.UNKNOWN_FAILURE,
                    "Composition was not generated.");
        }

        finishAndPrint(request.output, runStart, compileTime, synthesisTime, postCompositionTime, baselineMemory, current, memoryMeasurement);
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
                    compileOnlyStart = System.nanoTime();
                    compiler.compile();
                    long compileOnlyTime = elapsedMillis(compileOnlyStart);
                    if (UpdatingControllerEvaluationRecorder.isEnabled()) {
                        output.outln("comp.compile time : " + compileOnlyTime + "ms");
                        UpdatingControllerEvaluationRecorder.recordTime(
                                COMMON_SECTION,
                                "構文解析時間",
                                compileOnlyTime);
                    }
                    compileOnlyRecorded = true;

                    continueCompilationStart = System.nanoTime();
                    CompositeState current = compiler.continueCompilation(targetName);
                    long continueCompilationTime = elapsedMillis(continueCompilationStart);
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
            CompositeState current,
            MemoryMeasurementSession memoryMeasurement) {

        long runTime = elapsedMillis(runStart);
        memoryMeasurement.finishAndRecord();
        UpdatingControllerEvaluationRecorder.closeObservedTimeWindow();
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
        UpdatingControllerEvaluationRecorder.recordLegacyPoolPeakMemoryAliases(
                baselineMemory,
                overallPeakMemory,
                netPeakMemory);

        if (current != null && current.composition != null) {
            long outputCountStart = System.nanoTime();
            long outputStates = current.composition.maxStates;
            long outputTransitions = current.composition.ntransitionsLong();
            long outputCountTime = elapsedMillis(outputCountStart);
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

            long beginUpdateCountStart = System.nanoTime();
            long beginUpdateStates = countStatesWithOutgoingAction(
                    current.composition,
                    UpdateConstants.BEGIN_UPDATE);
            long beginUpdateCountTime = elapsedMillis(beginUpdateCountStart);
            if (beginUpdateStates > 0 || UpdatingControllerEvaluationRecorder.hasOldControllerStateSpace()) {
                UpdatingControllerEvaluationRecorder.recordBeginUpdateCoverage(
                        beginUpdateStates,
                        beginUpdateCountTime);
            }
        }

        UpdatingControllerEvaluationRecorder.recordMemoryCheckpoint("合成終了時");
        UpdatingControllerEvaluationRecorder.printSummary(output);
    }

    /** Owns the heap sampler and the child-to-parent RSS phase markers. */
    private static final class MemoryMeasurementSession {
        private final boolean heapSamplingEnabled;
        private final long intervalMillis;
        private RunHeapMemorySampler heapSampler;
        private GcSnapshot gcStart;
        private boolean started;
        private boolean finished;

        private MemoryMeasurementSession(boolean heapSamplingEnabled, long intervalMillis) {
            this.heapSamplingEnabled = heapSamplingEnabled;
            this.intervalMillis = intervalMillis;
        }

        private static MemoryMeasurementSession create() {
            boolean enabled = Boolean.parseBoolean(System.getProperty(
                    MEMORY_SAMPLING_ENABLED_PROPERTY,
                    "true"));
            return new MemoryMeasurementSession(enabled, configuredMemorySamplingInterval());
        }

        private synchronized void start() {
            if (started || finished) {
                return;
            }
            // In batch mode the parent opens the RSS window and acknowledges
            // this boundary before compilation is allowed to begin.
            MemoryMeasurementProtocol.emitSynthesisWindowStart();
            started = true;
            gcStart = GcSnapshot.capture();
            if (heapSamplingEnabled) {
                recordHeapSamplingStatusSafely(true, false, "");
                try {
                    heapSampler = new RunHeapMemorySampler(intervalMillis);
                    heapSampler.start();
                } catch (RuntimeException e) {
                    heapSampler = null;
                    recordHeapSamplingStatusSafely(true, false, e.toString());
                } catch (LinkageError e) {
                    heapSampler = null;
                    recordHeapSamplingStatusSafely(true, false, e.toString());
                } catch (OutOfMemoryError e) {
                    // Measurement must not replace the synthesis outcome when
                    // memory is already exhausted.
                    heapSampler = null;
                    recordHeapSamplingStatusSafely(
                            true, false, "heap_sampler_start_out_of_memory");
                }
            } else {
                recordHeapSamplingStatusSafely(false, false, "");
            }
        }

        private synchronized void finishAndRecord() {
            if (finished) {
                return;
            }
            GcSnapshot gcEnd = GcSnapshot.capture();
            UpdatingControllerEvaluationRecorder.recordPhaseMemoryCheckpoint(
                    "run.synthesis_window_end");
            finished = true;
            RunHeapMemorySampler.Result sampledHeap = null;
            if (heapSampler != null) {
                try {
                    sampledHeap = heapSampler.stop();
                } catch (RuntimeException e) {
                    recordHeapSamplingStatusSafely(true, false, e.toString());
                } catch (LinkageError e) {
                    recordHeapSamplingStatusSafely(true, false, e.toString());
                } catch (OutOfMemoryError e) {
                    // Preserve the original synthesis result/failure.
                    recordHeapSamplingStatusSafely(
                            true, false, "heap_sampler_stop_out_of_memory");
                }
            }
            if (started) {
                // The parent takes the synchronous final RSS sample before it
                // acknowledges this boundary and lets post-processing begin.
                MemoryMeasurementProtocol.emitSynthesisWindowEnd();
            }
            // Persist the MXBean deltas only after closing the sampled window,
            // so recorder allocations cannot inflate its heap/RSS peak.
            recordGcDelta(gcStart, gcEnd);
            if (sampledHeap != null) {
                try {
                    UpdatingControllerEvaluationRecorder.recordSampledHeapMemory(sampledHeap);
                } catch (OutOfMemoryError ignored) {
                    // Never replace the synthesis result with recorder OOME.
                }
            }
        }

        private static void recordHeapSamplingStatusSafely(
                boolean enabled,
                boolean available,
                String error) {
            try {
                UpdatingControllerEvaluationRecorder.recordHeapMemorySamplingStatus(
                        enabled, available, error);
            } catch (OutOfMemoryError ignored) {
                // Best effort only when the measured JVM is exhausted.
            }
        }

        private static void recordGcDelta(GcSnapshot start, GcSnapshot end) {
            final String section = "Controller synthesis GC measurement";
            final String formula = "Difference between GC MXBean counters at the measured window boundaries "
                    + "(compilation/problem preparation plus controller composition). Diagnostic only; "
                    + "it is not subtracted from controller synthesis time.";
            if (start == null || end == null || !start.available || !end.available) {
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_measurement_available",
                        section,
                        "GC measurement available",
                        "false",
                        "boolean",
                        formula);
                String error = firstNonEmpty(
                        start == null ? "start snapshot missing" : start.error,
                        end == null ? "end snapshot missing" : end.error);
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_measurement_error",
                        section,
                        "GC measurement error",
                        error,
                        "text",
                        formula);
                return;
            }

            long totalCount = 0L;
            long totalTime = 0L;
            int comparableCollectors = 0;
            for (Map.Entry<String, GcCounters> entry : end.counters.entrySet()) {
                GcCounters before = start.counters.get(entry.getKey());
                if (before == null) {
                    continue;
                }
                GcCounters after = entry.getValue();
                long countDelta = nonNegativeDelta(after.collectionCount, before.collectionCount);
                long timeDelta = nonNegativeDelta(after.collectionTimeMillis, before.collectionTimeMillis);
                totalCount += countDelta;
                totalTime += timeDelta;
                comparableCollectors++;
                String collectorToken = metricToken(entry.getKey());
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_" + collectorToken + "_collection_count",
                        section,
                        entry.getKey() + " collection count",
                        Long.toString(countDelta),
                        "collections",
                        formula);
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_" + collectorToken + "_collection_time",
                        section,
                        entry.getKey() + " collection time",
                        Long.toString(timeDelta),
                        "ms",
                        formula);
            }
            boolean available = comparableCollectors > 0;
            UpdatingControllerEvaluationRecorder.recordStableMetric(
                    "controller_synthesis_gc_measurement_available",
                    section,
                    "GC measurement available",
                    Boolean.toString(available),
                    "boolean",
                    formula);
            if (available) {
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_collection_count",
                        section,
                        "GC collection count",
                        Long.toString(totalCount),
                        "collections",
                        formula);
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_collection_time",
                        section,
                        "GC collection time",
                        Long.toString(totalTime),
                        "ms",
                        formula);
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_collector_count",
                        section,
                        "comparable GC collector count",
                        Integer.toString(comparableCollectors),
                        "collectors",
                        formula);
            } else {
                UpdatingControllerEvaluationRecorder.recordStableMetric(
                        "controller_synthesis_gc_measurement_error",
                        section,
                        "GC measurement error",
                        "No collector exposed comparable non-negative counters.",
                        "text",
                        formula);
            }
        }
    }

    private static final class GcSnapshot {
        private final Map<String, GcCounters> counters;
        private final boolean available;
        private final String error;

        private GcSnapshot(Map<String, GcCounters> counters, boolean available, String error) {
            this.counters = counters;
            this.available = available;
            this.error = error == null ? "" : error;
        }

        private static GcSnapshot capture() {
            Map<String, GcCounters> counters = new LinkedHashMap<String, GcCounters>();
            try {
                for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                    long count = bean.getCollectionCount();
                    long time = bean.getCollectionTime();
                    if (count >= 0L && time >= 0L) {
                        counters.put(bean.getName(), new GcCounters(count, time));
                    }
                }
                return new GcSnapshot(
                        counters,
                        !counters.isEmpty(),
                        counters.isEmpty() ? "No GC MXBean exposed supported counters." : "");
            } catch (RuntimeException e) {
                return new GcSnapshot(counters, false, e.toString());
            } catch (LinkageError e) {
                return new GcSnapshot(counters, false, e.toString());
            }
        }
    }

    private static final class GcCounters {
        private final long collectionCount;
        private final long collectionTimeMillis;

        private GcCounters(long collectionCount, long collectionTimeMillis) {
            this.collectionCount = collectionCount;
            this.collectionTimeMillis = collectionTimeMillis;
        }
    }

    private static long nonNegativeDelta(long after, long before) {
        return after >= before ? after - before : 0L;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String metricToken(String value) {
        StringBuilder result = new StringBuilder();
        boolean underscore = false;
        String source = value == null ? "" : value;
        for (int index = 0; index < source.length(); index++) {
            char ch = Character.toLowerCase(source.charAt(index));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                result.append(ch);
                underscore = false;
            } else if (!underscore && result.length() > 0) {
                result.append('_');
                underscore = true;
            }
        }
        if (result.length() > 0 && result.charAt(result.length() - 1) == '_') {
            result.deleteCharAt(result.length() - 1);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }

    private static long configuredMemorySamplingInterval() {
        String raw = System.getProperty(MEMORY_SAMPLING_INTERVAL_PROPERTY);
        if (raw == null || raw.trim().isEmpty()) {
            return RunHeapMemorySampler.DEFAULT_INTERVAL_MILLIS;
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0L ? value : RunHeapMemorySampler.DEFAULT_INTERVAL_MILLIS;
        } catch (NumberFormatException e) {
            return RunHeapMemorySampler.DEFAULT_INTERVAL_MILLIS;
        }
    }

    private static void recordPartialCompilationTimes(
            long compileOnlyStart,
            long continueCompilationStart,
            boolean compileOnlyRecorded,
            boolean continueCompilationRecorded) {
        if (!UpdatingControllerEvaluationRecorder.isEnabled()) {
            return;
        }
        if (compileOnlyStart >= 0 && !compileOnlyRecorded) {
            UpdatingControllerEvaluationRecorder.recordTime(
                    COMMON_SECTION,
                    "構文解析時間",
                    elapsedMillis(compileOnlyStart));
        }
        if (continueCompilationStart >= 0 && !continueCompilationRecorded) {
            UpdatingControllerEvaluationRecorder.recordTime(
                    COMMON_SECTION,
                    "合成問題準備時間",
                    elapsedMillis(continueCompilationStart));
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
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
