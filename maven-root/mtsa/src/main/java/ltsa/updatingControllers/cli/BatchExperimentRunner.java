package ltsa.updatingControllers.cli;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ltsa.updatingControllers.checks.TraceLanguageChecker;
import ltsa.updatingControllers.checks.TransitionGraph;
import ltsa.updatingControllers.checks.UpdateRequirementChecker;
import ltsa.updatingControllers.cli.ExperimentConfig.ExperimentCase;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder;
import ltsa.updatingControllers.UpdatingControllerEvaluationRecorder.ExternalDataMetric;
import ltsa.updatingControllers.memory.MemoryMeasurementProtocol;

public final class BatchExperimentRunner {

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_TIMEOUT = "TIMEOUT";
    private static final String STATUS_OUT_OF_MEMORY = "OUT_OF_MEMORY";
    private static final String STATUS_JVM_START_FAILED = "JVM_START_FAILED";
    private static final String STATUS_EXCEPTION = "EXCEPTION";
    private static final String STATUS_NO_COMPOSITION = "NO_COMPOSITION";
    private static final String STATUS_NO_TRANSITION_OUTPUT = "NO_TRANSITION_OUTPUT";
    private static final String STATUS_MEASUREMENT_FAILED = "MEASUREMENT_FAILED";
    private static final String MEMORY_SAMPLING_ENABLED_PROPERTY =
            "mtsa.evaluation.memorySampling.enabled";
    private static final String MEMORY_SAMPLING_INTERVAL_PROPERTY =
            "mtsa.evaluation.memorySampling.intervalMillis";
    private static final String RSS_SAMPLING_ENABLED_PROPERTY =
            "mtsa.evaluation.rssSampling.enabled";
    private static final String RSS_SAMPLING_INTERVAL_PROPERTY =
            "mtsa.evaluation.rssSampling.intervalMillis";
    private static final String RSS_PROVIDER_TIMEOUT_PROPERTY =
            "mtsa.evaluation.rssSampling.providerTimeoutMillis";
    private static final String WINDOWS_PEAK_WORKING_SET_ENABLED_PROPERTY =
            "mtsa.evaluation.windowsPeakWorkingSet.enabled";
    private static final long CHILD_TERMINATION_WAIT_MILLIS = 10_000L;
    private static final long STREAM_DRAIN_WAIT_MILLIS = 10_000L;
    private static final long STREAM_DRAIN_AFTER_CLOSE_WAIT_MILLIS = 1_000L;

    private BatchExperimentRunner() {
    }

    public static void main(String[] args) {
        int exitCode = runMain(args);
        System.exit(exitCode);
    }

    static int runMain(String[] args) {
        ExperimentConfig config = null;
        boolean dryRun = false;
        int failures = 0;
        String batchStartedAt = now();
        long batchStartedMillis = System.currentTimeMillis();
        try {
            Map<String, String> options = parseArgs(args);
            if (options.containsKey("help")) {
                printUsage(System.out);
                return 0;
            }

            File configFile = new File(required(options, "config"));
            dryRun = Boolean.parseBoolean(options.get("dry-run"));
            config = ExperimentConfig.load(configFile);
            applyBooleanOverride(options, "requirements-check", config, "requirementsCheck");
            applyBooleanOverride(options, "trace-check", config, "traceCheck");

            boolean useRunDirectories = config.runsSpecified;
            for (int runIndex = 1; runIndex <= config.runs; runIndex++) {
                String runLabel = useRunDirectories ? runLabel(runIndex, config.runs) : null;
                File runOutputDir = outputDirForRun(config.outputDir, runLabel);
                List<CompletedCase> completedCases = new ArrayList<CompletedCase>();
                if (runLabel != null) {
                    System.out.println("=== " + runLabel + " / " + config.runs + " ===");
                }

                for (ExperimentCase experimentCase : config.cases) {
                    for (String warning : experimentCase.validationWarnings()) {
                        System.err.println("[WARN] " + experimentCase.id + ": " + warning);
                    }

                    CasePaths paths = CasePaths.create(
                            runOutputDir,
                            experimentCase,
                            config.requirementsCheck,
                            runIndex,
                            config.runs,
                            runLabel);
                    if (dryRun) {
                        System.out.println("[DRY-RUN] "
                                + (runLabel == null ? "" : runLabel + " ")
                                + experimentCase.id
                                + " -> " + paths.outputFile.getPath());
                        continue;
                    }

                    CaseResult result = runCase(config, experimentCase, paths);
                    writeMetaJson(config, experimentCase, paths, result);
                    completedCases.add(new CompletedCase(experimentCase, paths, result));
                    System.out.println("[" + result.status + "] "
                            + (runLabel == null ? "" : runLabel + " ")
                            + experimentCase.id);
                    if (!STATUS_SUCCESS.equals(result.status)) {
                        failures++;
                    }
                }
                if (!dryRun && config.traceCheck) {
                    writeTraceComparisons(runOutputDir, runLabel, completedCases);
                }
            }

            int exitCode = failures == 0 ? 0 : 1;
            if (!dryRun) {
                notifyBatchCompletion(
                        config,
                        failures == 0 ? STATUS_SUCCESS : "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        null);
            }
            return exitCode;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printUsage(System.err);
            if (config != null && !dryRun) {
                notifyBatchCompletion(
                        config,
                        "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        e.toString());
            }
            return 64;
        } catch (Throwable e) {
            e.printStackTrace(System.err);
            if (config != null && !dryRun) {
                notifyBatchCompletion(
                        config,
                        "FAILURE",
                        failures,
                        batchStartedAt,
                        now(),
                        System.currentTimeMillis() - batchStartedMillis,
                        e.toString());
            }
            return 1;
        }
    }

    private static CaseResult runCase(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths) {

        CaseResult result = new CaseResult();
        result.startedAt = now();
        long timeoutMillis = experimentCase.timeoutMillisOrDefault(config.timeoutMillis);
        result.heapSamplingEnabled = configuredBooleanSystemProperty(
                config.javaOptions,
                MEMORY_SAMPLING_ENABLED_PROPERTY,
                true);
        result.rssSamplingEnabled = configuredBooleanSystemProperty(
                config.javaOptions,
                RSS_SAMPLING_ENABLED_PROPERTY,
                result.heapSamplingEnabled);
        result.windowsPeakWorkingSetEnabled = configuredBooleanSystemProperty(
                config.javaOptions,
                WINDOWS_PEAK_WORKING_SET_ENABLED_PROPERTY,
                false);
        Process process = null;
        StreamCopyThread stdoutThread = null;
        StreamCopyThread stderrThread = null;
        ProcessRssSampler rssSampler = null;
        WindowsPeakWorkingSetProbe windowsPeakWorkingSetProbe = null;
        MemoryWindowHandshakeCoordinator memoryProtocol = null;

        try {
            paths.ensureDirectories();
            Files.write(paths.evaluationCsvFile.toPath(), new byte[0]);
            List<String> command = buildChildCommand(config, experimentCase, paths);
            result.command = command;

            ProcessBuilder builder = new ProcessBuilder(command);
            process = builder.start();
            result.processStartedAtEpochMillis = System.currentTimeMillis();
            result.processStartedAtNanos = System.nanoTime();

            final MemoryWindowHandshakeCoordinator protocolForListener =
                    result.rssSamplingEnabled
                            ? new MemoryWindowHandshakeCoordinator(process.getOutputStream())
                            : null;
            memoryProtocol = protocolForListener;
            MemoryWindowMarkerDetector markerDetector = protocolForListener == null
                    ? null
                    : new MemoryWindowMarkerDetector(protocolForListener);
            stdoutThread = new StreamCopyThread(
                    process.getInputStream(),
                    paths.stdoutFile,
                    markerDetector);
            stderrThread = new StreamCopyThread(process.getErrorStream(), paths.stderrFile);
            stdoutThread.start();
            stderrThread.start();

            if (result.rssSamplingEnabled) {
                if (result.windowsPeakWorkingSetEnabled) {
                    try {
                        windowsPeakWorkingSetProbe =
                                WindowsPeakWorkingSetProbe.open(process.pid());
                    } catch (RuntimeException e) {
                        result.rssMeasurementError = appendError(
                                result.rssMeasurementError,
                                "Windows peak probe open failed: " + e);
                    } catch (LinkageError e) {
                        result.rssMeasurementError = appendError(
                                result.rssMeasurementError,
                                "Windows peak probe linkage failed: " + e);
                    } catch (OutOfMemoryError e) {
                        result.rssMeasurementError = appendError(
                                result.rssMeasurementError,
                                "Windows peak probe open out of memory");
                    }
                }
                try {
                    rssSampler = new ProcessRssSampler(
                            process.pid(),
                            configuredRssSamplingInterval(config.javaOptions),
                            configuredRssProviderTimeout(config.javaOptions));
                    rssSampler.start();
                    result.rssSamplerAttachedAtEpochMillis = System.currentTimeMillis();
                    result.rssSamplerAttachElapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - result.processStartedAtNanos);
                } catch (RuntimeException e) {
                    result.rssMeasurementError = e.toString();
                    rssSampler = null;
                } catch (LinkageError e) {
                    result.rssMeasurementError = e.toString();
                    rssSampler = null;
                } finally {
                    // A missing provider must disable RSS data, not leave the
                    // child blocked waiting for the protocol ACK.
                    protocolForListener.samplerReady(rssSampler);
                }
            }

            boolean finished;
            if (timeoutMillis <= 0) {
                result.exitCode = Integer.valueOf(process.waitFor());
                finished = true;
            } else {
                finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (finished) {
                    result.exitCode = Integer.valueOf(process.exitValue());
                }
            }

            if (!finished) {
                result.timedOut = true;
                result.status = STATUS_TIMEOUT;
                process.destroy();
                if (!process.waitFor(
                        CHILD_TERMINATION_WAIT_MILLIS,
                        TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(
                            CHILD_TERMINATION_WAIT_MILLIS,
                            TimeUnit.MILLISECONDS)) {
                        result.errorMessage = appendError(
                                result.errorMessage,
                                "Child process remained alive after forced termination");
                    }
                }
            } else {
                result.status = statusFromExitCode(result.exitCode.intValue(), paths);
            }
        } catch (IOException e) {
            result.status = STATUS_JVM_START_FAILED;
            result.errorMessage = e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.status = STATUS_EXCEPTION;
            result.errorMessage = e.toString();
        } finally {
            if (!terminateIfAlive(process)) {
                result.errorMessage = appendError(
                        result.errorMessage,
                        "Child process remained alive during final cleanup");
            }
            boolean stdoutDrained = drainStreamThread(
                    stdoutThread,
                    process == null ? null : process.getInputStream());
            boolean stderrDrained = drainStreamThread(
                    stderrThread,
                    process == null ? null : process.getErrorStream());
            if (!stdoutDrained) {
                result.stdoutCopyError = appendError(
                        result.stdoutCopyError,
                        "stdout_drain_timeout");
            }
            if (!stderrDrained) {
                result.stderrCopyError = appendError(
                        result.stderrCopyError,
                        "stderr_drain_timeout");
            }
            if (memoryProtocol != null) {
                if (stdoutThread != null && stdoutThread.getErrorMessage() != null) {
                    result.stdoutCopyError = appendError(
                            result.stdoutCopyError,
                            stdoutThread.getErrorMessage());
                }
                if (stdoutDrained) {
                    result.memoryProtocolResult = memoryProtocol.snapshot();
                    memoryProtocol.close();
                } else {
                    // The copy thread may still be inside a protocol callback
                    // holding the coordinator lock. Do not turn a stream
                    // timeout into an unbounded snapshot/close wait.
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            "memory_protocol_unavailable_after_stdout_drain_timeout");
                }
            }
            if (stderrThread != null && stderrThread.getErrorMessage() != null) {
                result.stderrCopyError = appendError(
                        result.stderrCopyError,
                        stderrThread.getErrorMessage());
            }
            if (rssSampler != null) {
                try {
                    result.rssResult = rssSampler.stop();
                } catch (RuntimeException e) {
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            e.toString());
                } catch (LinkageError e) {
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            e.toString());
                }
            }
            if (windowsPeakWorkingSetProbe != null) {
                try {
                    result.windowsPeakWorkingSetResult =
                            windowsPeakWorkingSetProbe.closeAndGet();
                } catch (RuntimeException e) {
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            "Windows peak probe final read failed: " + e);
                } catch (LinkageError e) {
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            "Windows peak probe final linkage failed: " + e);
                } catch (OutOfMemoryError e) {
                    result.rssMeasurementError = appendError(
                            result.rssMeasurementError,
                            "Windows peak probe final read out of memory");
                }
            }
            result.endedAt = now();
            appendProcessMemoryMetrics(config, paths, experimentCase, result);
        }

        return result;
    }

    private static boolean terminateIfAlive(Process process) {
        if (process == null || !process.isAlive()) {
            return true;
        }
        boolean interrupted = Thread.interrupted();
        boolean terminated = false;
        try {
            process.destroy();
            try {
                if (!process.waitFor(
                        CHILD_TERMINATION_WAIT_MILLIS,
                        TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(
                            CHILD_TERMINATION_WAIT_MILLIS,
                            TimeUnit.MILLISECONDS);
                }
                terminated = !process.isAlive();
            } catch (InterruptedException e) {
                interrupted = true;
                process.destroyForcibly();
                terminated = !process.isAlive();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return terminated;
    }

    private static boolean drainStreamThread(Thread thread, InputStream stream) {
        if (joinBounded(thread, STREAM_DRAIN_WAIT_MILLIS)) {
            return true;
        }
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The timeout is reported by the caller.
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
        return joinBounded(thread, STREAM_DRAIN_AFTER_CLOSE_WAIT_MILLIS);
    }

    private static String appendError(String existing, String additional) {
        if (additional == null || additional.isEmpty()) {
            return existing;
        }
        if (existing == null || existing.isEmpty()) {
            return additional;
        }
        return existing + "; " + additional;
    }

    private static void appendProcessMemoryMetrics(
            ExperimentConfig config,
            CasePaths paths,
            ExperimentCase experimentCase,
            CaseResult result) {
        List<ExternalDataMetric> metrics = new ArrayList<ExternalDataMetric>();
        String section = "Parent process memory measurement";
        Map<String, String> existingMetrics =
                readEvaluationCsvValues(paths.evaluationCsvFile);
        MemoryWindowHandshakeCoordinator.Result protocol =
                result.memoryProtocolResult;
        boolean protocolComplete = protocol != null && protocol.isComplete();
        boolean boundarySamplesComplete = protocolComplete
                && protocol.isStartBoundarySampleSucceeded()
                && protocol.isEndBoundarySampleSucceeded();
        ProcessRssSampler.Result rss = result.rssResult;
        boolean completeSynthesisRssWindow = rss != null
                && rss.isSynthesisWindowAvailable()
                && rss.isSynthesisWindowCompleted()
                && boundarySamplesComplete;
        boolean heapMeasurementComplete = !result.heapSamplingEnabled
                || Boolean.parseBoolean(existingMetrics.get(
                        "heap_memory_sampling_available"));
        boolean rssMeasurementComplete = !result.rssSamplingEnabled
                || completeSynthesisRssWindow;
        boolean requiredMemoryDataComplete =
                heapMeasurementComplete && rssMeasurementComplete;
        if (STATUS_SUCCESS.equals(result.status) && !requiredMemoryDataComplete) {
            result.status = STATUS_MEASUREMENT_FAILED;
            result.errorMessage = appendError(
                    result.errorMessage,
                    "Required memory measurement incomplete"
                            + " (heap=" + heapMeasurementComplete
                            + ", rss=" + rssMeasurementComplete + ")");
        }
        String childRecordedResult = existingMetrics.get("result");
        if (UpdatingControllerEvaluationRecorder.PARENT_FINALIZATION_PENDING
                .equals(childRecordedResult)) {
            childRecordedResult = STATUS_SUCCESS;
        }
        String childRecordedFailureReason = existingMetrics.get("failure_reason");
        String finalFailureReason = batchFailureReason(result, existingMetrics);
        // A successful child already writes these rows. Add them only as a
        // fallback for timeout/JVM-start-failure cases so one run never has
        // duplicate stable metric keys.
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("result", "Run", "result",
                        result.status, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("failure_reason", "Run", "failure reason",
                        finalFailureReason, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_result", "Run", "batch final result",
                        result.status, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric(
                        "batch_failure_reason",
                        "Run",
                        "batch final failure reason",
                        finalFailureReason,
                        "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric(
                        "memory_measurement_required_data_complete",
                        section,
                        "有効化された主メモリ指標が完結",
                        Boolean.toString(requiredMemoryDataComplete),
                        "boolean"));
        if (childRecordedResult != null
                && !childRecordedResult.equals(result.status)) {
            addExternalMetricIfAbsent(metrics, existingMetrics,
                    externalMetric(
                            "child_recorded_result_before_batch_finalize",
                            "Run",
                            "child recorder result before batch finalize",
                            childRecordedResult,
                            "text"));
        }
        if (childRecordedFailureReason != null
                && !childRecordedFailureReason.equals(finalFailureReason)) {
            addExternalMetricIfAbsent(metrics, existingMetrics,
                    externalMetric(
                            "child_recorded_failure_reason_before_batch_finalize",
                            "Run",
                            "child recorder failure reason before batch finalize",
                            childRecordedFailureReason,
                            "text"));
        }
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("evaluation_csv_file", "Run", "evaluation CSV file",
                        paths.evaluationCsvFile.getPath(), "path"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_case_id", "Run", "batch case id",
                        experimentCase.id, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_example", "Run", "batch example",
                        experimentCase.example, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_method", "Run", "batch method",
                        experimentCase.method, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_variant", "Run", "batch variant",
                        experimentCase.variant, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_target", "Run", "batch target",
                        experimentCase.target, "text"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_lts_file", "Run", "batch LTS file",
                        experimentCase.lts.getPath(), "path"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_config_file", "Run", "batch config file",
                        config.configFile.getPath(), "path"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_run_index", "Run", "batch run index",
                        Integer.toString(paths.runIndex), "count"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric("batch_run_count", "Run", "batch run count",
                        Integer.toString(paths.runCount), "count"));
        if (paths.runLabel != null) {
            addExternalMetricIfAbsent(metrics, existingMetrics,
                    externalMetric("batch_run_label", "Run", "batch run label",
                            paths.runLabel, "text"));
        }
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric(
                        "heap_memory_sampling_enabled",
                        section,
                        "child JVM aggregate heap周期計測の有効化",
                        Boolean.toString(result.heapSamplingEnabled),
                        "boolean"));
        addExternalMetricIfAbsent(metrics, existingMetrics,
                externalMetric(
                        "heap_memory_sampling_available",
                        section,
                        "child heap計測値取得可否",
                        "false",
                        "boolean"));
        if (result.heapSamplingEnabled
                && !existingMetrics.containsKey("heap_memory_sampling_available")
                && !existingMetrics.containsKey("heap_memory_sampling_error")) {
            metrics.add(externalMetric(
                    "heap_memory_sampling_error",
                    section,
                    "child heap計測エラー",
                    "child_metric_unavailable:" + result.status,
                    "text"));
        }
        metrics.add(externalMetric(
                "process_rss_sampling_enabled",
                section,
                "子JVM RSS周期計測の有効化",
                Boolean.toString(result.rssSamplingEnabled),
                "boolean"));
        metrics.add(externalMetric(
                "process_rss_sampling_available",
                section,
                "child lifetime RSS取得可否",
                Boolean.toString(result.rssResult != null
                        && result.rssResult.isAvailable()),
                "boolean"));
        if (result.processStartedAtEpochMillis >= 0L) {
            metrics.add(externalMetric(
                    "child_process_started_at_epoch_ms",
                    section,
                    "子JVM開始epoch時刻",
                    Long.toString(result.processStartedAtEpochMillis),
                    "epoch_ms"));
        }
        if (result.rssSamplerAttachedAtEpochMillis >= 0L) {
            metrics.add(externalMetric(
                    "process_rss_sampler_attached_at_epoch_ms",
                    section,
                    "RSS sampler attach epoch時刻",
                    Long.toString(result.rssSamplerAttachedAtEpochMillis),
                    "epoch_ms"));
            metrics.add(externalMetric(
                    "process_rss_sampler_attach_elapsed_ms",
                    section,
                    "子JVM開始からRSS sampler attachまで",
                    Long.toString(result.rssSamplerAttachElapsedMillis),
                    "ms"));
        }

        metrics.add(externalMetric(
                "memory_protocol_version",
                section,
                "RSS計測境界protocol version",
                MemoryMeasurementProtocol.VERSION,
                "text"));
        metrics.add(externalMetric(
                "memory_protocol_enabled",
                section,
                "RSS計測境界protocol有効化",
                Boolean.toString(result.rssSamplingEnabled),
                "boolean"));
        metrics.add(externalMetric(
                "memory_protocol_ack_timeout_ms",
                section,
                "子JVMの境界ACK待機上限",
                Long.toString(configuredMemoryProtocolAckTimeout(config.javaOptions)),
                "ms"));
        metrics.add(externalMetric(
                "memory_protocol_handshake_complete",
                section,
                "合成RSS区間のSTART/END handshake完了",
                Boolean.toString(protocolComplete),
                "boolean"));
        metrics.add(externalMetric(
                "memory_protocol_boundary_samples_complete",
                section,
                "合成RSS区間の両境界sample成功",
                Boolean.toString(boundarySamplesComplete),
                "boolean"));
        if (protocol != null) {
            metrics.add(externalMetric("memory_protocol_start_marker_count", section,
                    "START marker受信数",
                    Integer.toString(protocol.getStartMarkerCount()), "count"));
            metrics.add(externalMetric("memory_protocol_end_marker_count", section,
                    "END marker受信数",
                    Integer.toString(protocol.getEndMarkerCount()), "count"));
            metrics.add(externalMetric("memory_protocol_start_ack_sent", section,
                    "START ACK送信",
                    Boolean.toString(protocol.isStartAckSent()), "boolean"));
            metrics.add(externalMetric("memory_protocol_end_ack_sent", section,
                    "END ACK送信",
                    Boolean.toString(protocol.isEndAckSent()), "boolean"));
            metrics.add(externalMetric("memory_protocol_start_boundary_sample_success", section,
                    "START境界RSS sample成功",
                    Boolean.toString(protocol.isStartBoundarySampleSucceeded()), "boolean"));
            metrics.add(externalMetric("memory_protocol_end_boundary_sample_success", section,
                    "END境界RSS sample成功",
                    Boolean.toString(protocol.isEndBoundarySampleSucceeded()), "boolean"));
            metrics.add(externalMetric("memory_protocol_start_ack_wait_ms", section,
                    "START marker受信からACKまで",
                    Long.toString(protocol.getStartAckWaitMillis()), "ms"));
            metrics.add(externalMetric("memory_protocol_end_ack_wait_ms", section,
                    "END marker受信からACKまで",
                    Long.toString(protocol.getEndAckWaitMillis()), "ms"));
            metrics.add(externalMetric("memory_protocol_error", section,
                    "RSS計測境界protocol error",
                    protocol.getProtocolError(), "text"));
        }

        if (result.rssMeasurementError != null && !result.rssMeasurementError.isEmpty()) {
            metrics.add(externalMetric(
                    "process_rss_sampling_error",
                    section,
                    "子JVM RSS計測エラー",
                    result.rssMeasurementError,
                    "text"));
        }
        if (result.stdoutCopyError != null && !result.stdoutCopyError.isEmpty()) {
            metrics.add(externalMetric(
                    "memory_protocol_stdout_copy_error",
                    section,
                    "子JVM stdout保存エラー（protocol drainは継続）",
                    result.stdoutCopyError,
                    "text"));
        }
        if (result.stderrCopyError != null && !result.stderrCopyError.isEmpty()) {
            metrics.add(externalMetric(
                    "child_stderr_copy_error",
                    section,
                    "子JVM stderr保存エラー",
                    result.stderrCopyError,
                    "text"));
        }

        metrics.add(externalMetric(
                "process_rss_synthesis_window_complete_and_valid",
                section,
                "合成RSS区間が完結し主評価に利用可能",
                Boolean.toString(completeSynthesisRssWindow),
                "boolean"));
        if (rss != null) {
            metrics.add(externalMetric("process_rss_sampling_provider", section,
                    "RSS provider", rss.getProvider(), "text"));
            metrics.add(externalMetric("process_rss_sampling_pid", section,
                    "計測対象の子JVM PID", Long.toString(rss.getPid()), "pid"));
            metrics.add(externalMetric("process_rss_sampling_interval_ms", section,
                    "RSS計測設定間隔", Long.toString(rss.getIntervalMillis()), "ms"));
            metrics.add(externalMetric("process_rss_sampling_provider_timeout_ms", section,
                    "RSS provider 1回の読取上限",
                    Long.toString(rss.getProviderTimeoutMillis()), "ms"));
            metrics.add(externalMetric("process_rss_sampling_sample_count", section,
                    "child lifetime有効RSS sample数",
                    Long.toString(rss.getSampleCount()), "samples"));
            metrics.add(externalMetric("process_rss_sampling_failure_count", section,
                    "child lifetime失敗RSS sample数",
                    Long.toString(rss.getSampleFailureCount()), "samples"));
            metrics.add(externalMetric("process_rss_sampling_provider_failure_count", section,
                    "RSS provider例外数",
                    Long.toString(rss.getProviderFailureCount()), "samples"));
            metrics.add(externalMetric("process_rss_sampling_provider_timeout_count", section,
                    "RSS provider timeout数",
                    Long.toString(rss.getProviderTimeoutCount()), "samples"));
            metrics.add(externalMetric("process_rss_sampling_attempt_count", section,
                    "RSS sample試行数",
                    Long.toString(rss.getSampleAttemptCount()), "samples"));
            metrics.add(externalMetric("process_rss_sampling_max_gap_ms", section,
                    "RSS sample開始間隔の最大値",
                    Long.toString(rss.getMaxSampleGapMillis()), "ms"));
            metrics.add(externalMetric("process_rss_sampling_total_wall_time_ns", section,
                    "RSS provider call待機wall time合計（timeout上限で打切り）",
                    Long.toString(rss.getTotalSamplingWallTimeNanos()), "ns"));
            metrics.add(externalMetric("process_rss_sampling_thread_cpu_time_ns", section,
                    "RSS scheduling daemon threadのCPU時間",
                    Long.toString(rss.getSamplerThreadCpuTimeNanos()), "ns"));
            metrics.add(externalMetric("process_rss_sampling_thread_cpu_time_available", section,
                    "RSS scheduling daemon thread CPU時間取得可否",
                    Boolean.toString(rss.isSamplerThreadCpuTimeAvailable()), "boolean"));
            metrics.add(externalMetric("process_rss_sampling_provider_thread_cpu_time_ns", section,
                    "完了したRSS provider taskのCPU時間合計",
                    Long.toString(rss.getProviderThreadCpuTimeNanos()), "ns"));
            metrics.add(externalMetric(
                    "process_rss_sampling_provider_thread_cpu_time_available",
                    section,
                    "RSS provider thread CPU時間取得可否",
                    Boolean.toString(rss.isProviderThreadCpuTimeAvailable()),
                    "boolean"));
            metrics.add(externalMetric(
                    "process_rss_sampling_provider_thread_cpu_measurement_complete",
                    section,
                    "RSS provider thread CPU計測完結",
                    Boolean.toString(rss.isProviderThreadCpuMeasurementComplete()),
                    "boolean"));
            metrics.add(externalMetric("process_rss_sampling_total_thread_cpu_time_ns", section,
                    "RSS schedulingとproviderのCPU時間合計",
                    Long.toString(rss.getTotalMeasurementThreadCpuTimeNanos()), "ns"));
            metrics.add(externalMetric(
                    "process_rss_sampling_total_thread_cpu_time_available",
                    section,
                    "RSS計測thread CPU時間合計の利用可否",
                    Boolean.toString(rss.isTotalMeasurementThreadCpuTimeAvailable()),
                    "boolean"));
            metrics.add(externalMetric("process_rss_sampling_provider_circuit_open", section,
                    "Batch内RSS provider circuit breaker作動",
                    Boolean.toString(rss.isProviderCircuitOpen()), "boolean"));
            metrics.add(externalMetric(
                    "process_rss_sampling_provider_disabled_by_circuit_breaker",
                    section,
                    "先行hard timeoutにより当該caseのRSS providerを無効化",
                    Boolean.toString(rss.isProviderDisabledByCircuitBreaker()),
                    "boolean"));
            metrics.add(externalMetric("process_rss_synthesis_window_started", section,
                    "合成RSS区間開始marker検出",
                    Boolean.toString(rss.isSynthesisWindowStarted()), "boolean"));
            metrics.add(externalMetric("process_rss_synthesis_window_completed", section,
                    "合成RSS区間終了marker検出",
                    Boolean.toString(rss.isSynthesisWindowCompleted()), "boolean"));
            metrics.add(externalMetric("process_rss_synthesis_window_available", section,
                    "合成区間RSS取得可否",
                    Boolean.toString(rss.isSynthesisWindowAvailable()), "boolean"));
            metrics.add(externalMetric("process_rss_synthesis_window_sample_count", section,
                    "合成区間有効RSS sample数",
                    Long.toString(rss.getSynthesisWindowSampleCount()), "samples"));
            metrics.add(externalMetric("process_rss_synthesis_window_failure_count", section,
                    "合成区間失敗RSS sample数",
                    Long.toString(rss.getSynthesisWindowSampleFailureCount()), "samples"));
            metrics.add(externalMetric("process_rss_synthesis_window_provider_failure_count", section,
                    "合成区間RSS provider例外数",
                    Long.toString(rss.getSynthesisWindowProviderFailureCount()), "samples"));
            metrics.add(externalMetric(
                    "process_rss_synthesis_window_sampling_wait_wall_time_ns",
                    section,
                    "合成RSS区間内のprovider結果待機wall time合計",
                    Long.toString(rss.getSynthesisWindowSamplingWallTimeNanos()),
                    "ns"));
            metrics.add(externalMetric(
                    "process_rss_synthesis_window_provider_thread_cpu_time_ns",
                    section,
                    "合成RSS区間内の完了provider task CPU時間合計",
                    Long.toString(rss.getSynthesisWindowProviderThreadCpuTimeNanos()),
                    "ns"));
            metrics.add(externalMetric(
                    "process_rss_synthesis_window_provider_thread_cpu_time_available",
                    section,
                    "合成RSS区間provider thread CPU時間取得可否",
                    Boolean.toString(
                            rss.isSynthesisWindowProviderThreadCpuTimeAvailable()),
                    "boolean"));
            metrics.add(externalMetric(
                    "process_rss_synthesis_window_provider_thread_cpu_measurement_complete",
                    section,
                    "合成RSS区間provider thread CPU計測完結",
                    Boolean.toString(
                            rss.isSynthesisWindowProviderThreadCpuMeasurementComplete()),
                    "boolean"));

            if (rss.isAvailable()) {
                metrics.add(externalMetric(
                        "child_lifetime_sampled_peak_process_rss",
                        section,
                        "子JVM lifetimeのsampled peak RSS",
                        Long.toString(rss.getLifetimePeakRssBytes()),
                        "B",
                        "RSS sampler attach後から終了までの周期sample最大値。"
                                + "attach前とsample間の瞬間peakは含まない下限。"));
                metrics.add(externalMetric(
                        "child_lifetime_sampled_peak_process_rss_epoch_ms",
                        section,
                        "子JVM lifetime sampled peak RSSの観測epoch時刻",
                        Long.toString(rss.getLifetimePeakAtEpochMillis()),
                        "epoch_ms"));
                metrics.add(externalMetric(
                        "child_lifetime_sampled_peak_process_rss_elapsed_ms",
                        section,
                        "RSS sampler attachからlifetime sampled peak RSSまでの時間",
                        Long.toString(rss.getLifetimePeakAtElapsedMillis()),
                        "ms"));
            }
            if (completeSynthesisRssWindow) {
                metrics.add(externalMetric(
                        "controller_synthesis_sampled_peak_process_rss",
                        section,
                        "合成区間のsampled peak process RSS",
                        Long.toString(rss.getSynthesisWindowPeakRssBytes()),
                        "B",
                        "子JVMの合成開始・終了marker間に周期sampleしたRSS最大値。真の瞬間peakの下限。"));
                metrics.add(externalMetric(
                        "controller_synthesis_sampled_peak_process_rss_epoch_ms",
                        section,
                        "合成区間sampled peak RSSの観測epoch時刻",
                        Long.toString(rss.getSynthesisWindowPeakAtEpochMillis()),
                        "epoch_ms"));
                metrics.add(externalMetric(
                        "controller_synthesis_sampled_peak_process_rss_elapsed_ms",
                        section,
                        "合成区間開始からsampled peak RSSまでの時間",
                        Long.toString(rss.getSynthesisWindowPeakAtElapsedMillis()),
                        "ms"));
            } else if (rss.isSynthesisWindowAvailable()) {
                metrics.add(externalMetric(
                        "controller_synthesis_partial_sampled_peak_process_rss",
                        section,
                        "未完結・品質未確認の合成区間sampled peak process RSS",
                        Long.toString(rss.getSynthesisWindowPeakRssBytes()),
                        "B",
                        "START/END handshakeまたは境界sampleが完結していない"
                                + "censored区間の診断値。主評価値へ混在させない。"));
            }
        }

        WindowsPeakWorkingSetProbe.Result windowsPeak =
                result.windowsPeakWorkingSetResult;
        metrics.add(externalMetric(
                "windows_peak_working_set_enabled",
                section,
                "Windows OS high-water計測の有効化",
                Boolean.toString(result.windowsPeakWorkingSetEnabled),
                "boolean"));
        if (windowsPeak != null) {
            metrics.add(externalMetric(
                    "windows_peak_working_set_available",
                    section,
                    "Windows OS high-water取得可否",
                    Boolean.toString(windowsPeak.isAvailable()),
                    "boolean"));
            metrics.add(externalMetric(
                    "windows_peak_working_set_provider",
                    section,
                    "Windows OS high-water provider",
                    windowsPeak.getProvider(),
                    "text"));
            metrics.add(externalMetric(
                    "windows_peak_working_set_failure_reason",
                    section,
                    "Windows OS high-water取得失敗理由",
                    windowsPeak.getFailureReason(),
                    "text"));
            metrics.add(externalMetric(
                    "windows_peak_working_set_native_error_code",
                    section,
                    "Windows OS high-water native error code",
                    Integer.toString(windowsPeak.getNativeErrorCode()),
                    "code"));
            metrics.add(externalMetric(
                    "windows_peak_working_set_final_read",
                    section,
                    "Windows OS high-water終了時read実施",
                    Boolean.toString(windowsPeak.isFinalRead()),
                    "boolean"));
            if (windowsPeak.isAvailable()) {
                metrics.add(externalMetric(
                        "child_lifetime_windows_peak_working_set_size",
                        section,
                        "子JVM lifetimeのWindows PeakWorkingSetSize",
                        Long.toString(windowsPeak.getPeakWorkingSetSizeBytes()),
                        "B",
                        "Windows GetProcessMemoryInfoが保持するprocess lifetimeの"
                                + "PeakWorkingSetSize high-water mark。"));
            }
        }

        try {
            result.memoryMetricsWriteAttempted = true;
            UpdatingControllerEvaluationRecorder.appendExternalDataMetrics(
                    paths.evaluationCsvFile,
                    evaluationMode(experimentCase.method),
                    result.status,
                    finalFailureReason,
                    metrics);
            result.memoryMetricsWriteSucceeded = true;
        } catch (IOException e) {
            recordMemoryMetricsWriteFailure(result, e);
        } catch (RuntimeException e) {
            recordMemoryMetricsWriteFailure(result, e);
        }
    }

    private static void recordMemoryMetricsWriteFailure(
            CaseResult result,
            Throwable failure) {
        result.memoryMetricsWriteSucceeded = false;
        String message = "Failed to finalize memory metrics: " + failure;
        result.rssMeasurementError = appendError(
                result.rssMeasurementError,
                message);
        result.errorMessage = appendError(result.errorMessage, message);
        if (STATUS_SUCCESS.equals(result.status)) {
            result.status = STATUS_MEASUREMENT_FAILED;
        }
        System.err.println("[WARN] Memory metric CSV finalize failed: " + failure);
    }

    private static ExternalDataMetric externalMetric(
            String key,
            String section,
            String label,
            String value,
            String unit) {
        return new ExternalDataMetric(key, section, label, value, unit);
    }

    private static ExternalDataMetric externalMetric(
            String key,
            String section,
            String label,
            String value,
            String unit,
            String formula) {
        return new ExternalDataMetric(key, section, label, value, unit, formula);
    }

    private static void addExternalMetricIfAbsent(
            List<ExternalDataMetric> output,
            Map<String, String> existingMetrics,
            ExternalDataMetric metric) {
        if (!existingMetrics.containsKey(metric.getKey())) {
            output.add(metric);
        }
    }

    private static String evaluationMode(String method) {
        String normalized = safeLower(method);
        if (normalized.contains("traditional")) {
            return "Traditional DUC";
        }
        if (normalized.contains("stepwise")) {
            return "Stepwise Delayed DUC";
        }
        if (normalized.contains("otf")) {
            return "OTF-DUC";
        }
        return method == null ? "未記録" : method;
    }

    private static String batchFailureReason(
            CaseResult result,
            Map<String, String> existingMetrics) {
        if (STATUS_SUCCESS.equals(result.status)) {
            return "";
        }
        if (result.errorMessage != null && !result.errorMessage.isEmpty()) {
            return result.errorMessage;
        }
        if (result.timedOut) {
            return "Batch timeout";
        }
        String childFailureReason = existingMetrics == null
                ? null
                : existingMetrics.get("failure_reason");
        if (childFailureReason != null && !childFailureReason.isEmpty()) {
            return childFailureReason;
        }
        if (result.exitCode != null) {
            return result.status + " (child exit code " + result.exitCode + ")";
        }
        return result.status;
    }

    private static List<String> buildChildCommand(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths) {

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.addAll(config.javaOptions);
        addInheritedSystemProperty(command, "mtsa.evaluation.enabled");
        addInheritedSystemProperty(command, "updating.controller.evaluation.enabled");
        addInheritedSystemProperty(command, "updating.controller.evaluation.printDetailedReport");
        addInheritedSystemProperty(command, MEMORY_SAMPLING_ENABLED_PROPERTY);
        addInheritedSystemProperty(command, MEMORY_SAMPLING_INTERVAL_PROPERTY);
        addInheritedSystemProperty(command, RSS_SAMPLING_ENABLED_PROPERTY);
        addInheritedSystemProperty(command, RSS_SAMPLING_INTERVAL_PROPERTY);
        addInheritedSystemProperty(command, RSS_PROVIDER_TIMEOUT_PROPERTY);
        addInheritedSystemProperty(command,
                MemoryMeasurementProtocol.ACK_TIMEOUT_MILLIS_PROPERTY);
        boolean rssSamplingEnabled = configuredBooleanSystemProperty(
                config.javaOptions,
                RSS_SAMPLING_ENABLED_PROPERTY,
                configuredBooleanSystemProperty(
                        config.javaOptions,
                        MEMORY_SAMPLING_ENABLED_PROPERTY,
                        true));
        addSystemProperty(command,
                MemoryMeasurementProtocol.ENABLED_PROPERTY,
                Boolean.toString(rssSamplingEnabled));
        addSystemProperty(command,
                UpdatingControllerEvaluationRecorder
                        .PARENT_FINALIZATION_REQUIRED_PROPERTY,
                "true");
        addSystemProperty(command, "mtsa.evaluation.csvFile", paths.evaluationCsvFile.getPath());
        addSystemProperty(command, "mtsa.evaluation.caseId", experimentCase.id);
        addSystemProperty(command, "mtsa.evaluation.example", experimentCase.example);
        addSystemProperty(command, "mtsa.evaluation.method", experimentCase.method);
        addSystemProperty(command, "mtsa.evaluation.variant", experimentCase.variant);
        addSystemProperty(command, "mtsa.evaluation.target", experimentCase.target);
        addSystemProperty(command, "mtsa.evaluation.ltsFile", experimentCase.lts.getPath());
        addSystemProperty(command, "mtsa.evaluation.configFile", config.configFile.getPath());
        addSystemProperty(command, "mtsa.evaluation.runIndex", Integer.toString(paths.runIndex));
        addSystemProperty(command, "mtsa.evaluation.runCount", Integer.toString(paths.runCount));
        if (paths.runLabel != null) {
            addSystemProperty(command, "mtsa.evaluation.runLabel", paths.runLabel);
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(SingleCompositionRunner.class.getName());
        command.add("--lts");
        command.add(experimentCase.lts.getPath());
        command.add("--target");
        command.add(experimentCase.target);
        command.add("--output");
        command.add(paths.outputFile.getPath());
        command.add("--transitions");
        command.add(paths.transitionsFile.getPath());
        command.add("--minimized-transitions");
        command.add(paths.minimizedTransitionsFile.getPath());
        command.add("--minimized-counts");
        command.add(paths.minimizedCountsFile.getPath());
        if (paths.requirementsCheckEnabled) {
            command.add("--requirements-check");
            command.add(paths.requirementsCheckFile.getPath());
        }
        return command;
    }

    private static void addSystemProperty(List<String> command, String key, String value) {
        if (value != null) {
            command.add("-D" + key + "=" + value);
        }
    }

    private static void addInheritedSystemProperty(List<String> command, String key) {
        String value = System.getProperty(key);
        if (value != null && !containsSystemProperty(command, key)) {
            command.add("-D" + key + "=" + value);
        }
    }

    private static boolean containsSystemProperty(List<String> command, String key) {
        String prefix = "-D" + key + "=";
        for (String arg : command) {
            if (arg.equals("-D" + key) || arg.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean configuredBooleanSystemProperty(
            List<String> javaOptions,
            String key,
            boolean defaultValue) {
        String value = configuredSystemProperty(javaOptions, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static long configuredRssSamplingInterval(List<String> javaOptions) {
        String value = configuredSystemProperty(javaOptions, RSS_SAMPLING_INTERVAL_PROPERTY);
        if (value == null) {
            value = configuredSystemProperty(javaOptions, MEMORY_SAMPLING_INTERVAL_PROPERTY);
        }
        if (value != null) {
            try {
                long interval = Long.parseLong(value.trim());
                if (interval > 0L) {
                    return interval;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the documented default.
            }
        }
        return ProcessRssSampler.DEFAULT_INTERVAL_MILLIS;
    }

    private static long configuredMemoryProtocolAckTimeout(List<String> javaOptions) {
        String value = configuredSystemProperty(
                javaOptions,
                MemoryMeasurementProtocol.ACK_TIMEOUT_MILLIS_PROPERTY);
        if (value != null) {
            try {
                long timeout = Long.parseLong(value.trim());
                if (timeout > 0L) {
                    return timeout;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the protocol default.
            }
        }
        return MemoryMeasurementProtocol.DEFAULT_ACK_TIMEOUT_MILLIS;
    }

    private static long configuredRssProviderTimeout(List<String> javaOptions) {
        String value = configuredSystemProperty(javaOptions, RSS_PROVIDER_TIMEOUT_PROPERTY);
        if (value != null) {
            try {
                long timeout = Long.parseLong(value.trim());
                if (timeout > 0L) {
                    return timeout;
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the sampler default.
            }
        }
        return ProcessRssSampler.DEFAULT_PROVIDER_TIMEOUT_MILLIS;
    }

    private static String configuredSystemProperty(List<String> javaOptions, String key) {
        String configured = null;
        String exact = "-D" + key;
        String prefix = exact + "=";
        if (javaOptions != null) {
            for (String option : javaOptions) {
                if (exact.equals(option)) {
                    configured = "true";
                } else if (option != null && option.startsWith(prefix)) {
                    configured = option.substring(prefix.length());
                }
            }
        }
        return configured == null ? System.getProperty(key) : configured;
    }

    private static String javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isEmpty()) {
            return "java";
        }
        return new File(new File(javaHome, "bin"), "java").getPath();
    }

    private static String statusFromExitCode(int exitCode, CasePaths paths) {
        if (exitCode == SingleCompositionRunner.EXIT_SUCCESS) {
            if (paths.transitionsFile.exists()
                    && paths.minimizedTransitionsFile.exists()
                    && paths.minimizedCountsFile.exists()
                    && (!paths.requirementsCheckEnabled || paths.requirementsCheckFile.exists())) {
                return STATUS_SUCCESS;
            }
            return STATUS_NO_TRANSITION_OUTPUT;
        }
        if (exitCode == SingleCompositionRunner.EXIT_NO_COMPOSITION) {
            return STATUS_NO_COMPOSITION;
        }
        if (exitCode == SingleCompositionRunner.EXIT_NO_TRANSITION_OUTPUT) {
            return STATUS_NO_TRANSITION_OUTPUT;
        }
        if (exitCode == SingleCompositionRunner.EXIT_OUT_OF_MEMORY) {
            return STATUS_OUT_OF_MEMORY;
        }
        if (stderrIndicatesOutOfMemory(paths.stderrFile)) {
            return STATUS_OUT_OF_MEMORY;
        }
        return STATUS_EXCEPTION;
    }

    private static boolean stderrIndicatesOutOfMemory(File stderrFile) {
        if (stderrFile == null || !stderrFile.isFile()) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(stderrFile.toPath()), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
            return text.contains("outofmemoryerror")
                    || text.contains("could not reserve enough space")
                    || text.contains("cannot allocate memory")
                    || text.contains("unable to allocate");
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMetaJson(
            ExperimentConfig config,
            ExperimentCase experimentCase,
            CasePaths paths,
            CaseResult result) throws IOException {

        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("status", result.status);
        values.put("id", experimentCase.id);
        values.put("example", experimentCase.example);
        values.put("method", experimentCase.method);
        values.put("variant", experimentCase.variant);
        values.put("target", experimentCase.target);
        values.put("lts", experimentCase.lts.getPath());
        values.put("runIndex", Integer.valueOf(paths.runIndex));
        values.put("runCount", Integer.valueOf(paths.runCount));
        if (paths.runLabel != null) {
            values.put("runLabel", paths.runLabel);
        }
        values.put("output", paths.outputFile.getPath());
        values.put("transitions", paths.transitionsFile.getPath());
        values.put("minimizedTransitions", paths.minimizedTransitionsFile.getPath());
        values.put("minimizedCounts", paths.minimizedCountsFile.getPath());
        values.put("requirementsCheckEnabled", Boolean.valueOf(paths.requirementsCheckEnabled));
        values.put("traceCheckEnabled", Boolean.valueOf(config.traceCheck));
        values.put("requirementsCheck",
                paths.requirementsCheckFile == null ? null : paths.requirementsCheckFile.getPath());
        values.put("evaluationCsv", paths.evaluationCsvFile.getPath());
        values.put("memoryMetricSchemaVersion",
                UpdatingControllerEvaluationRecorder.getMetricSchemaVersion());
        values.put("memoryMetricsWriteAttempted",
                Boolean.valueOf(result.memoryMetricsWriteAttempted));
        values.put("memoryMetricsWriteSucceeded",
                Boolean.valueOf(result.memoryMetricsWriteSucceeded));
        values.put("heapSamplingEnabled", Boolean.valueOf(result.heapSamplingEnabled));
        Map<String, String> evaluationMetrics = readEvaluationCsvValues(paths.evaluationCsvFile);
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_sampled_base_heap_used",
                "controllerSynthesisSampledBaseHeapUsedBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_sampled_peak_heap_used",
                "controllerSynthesisSampledPeakHeapUsedBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_sampled_heap_increase",
                "controllerSynthesisSampledHeapIncreaseBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_sampled_peak_process_rss",
                "controllerSynthesisSampledPeakProcessRssBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_partial_sampled_peak_process_rss",
                "controllerSynthesisPartialSampledPeakProcessRssBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "child_lifetime_sampled_peak_process_rss",
                "childProcessLifetimeSampledPeakRssBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "child_lifetime_windows_peak_working_set_size",
                "childProcessLifetimeWindowsPeakWorkingSetSizeBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "controller_synthesis_peak_memory_legacy_pool_sum",
                "legacyPoolPeakSumBytes");
        putMetricIfPresent(values, evaluationMetrics,
                "heap_memory_sampling_interval_ms",
                "heapSamplingIntervalMillis");
        putMetricIfPresent(values, evaluationMetrics,
                "heap_memory_sampling_sample_count",
                "heapSamplingSampleCount");
        putMetricIfPresent(values, evaluationMetrics,
                "process_rss_sampling_interval_ms",
                "rssSamplingIntervalMillis");
        putMetricIfPresent(values, evaluationMetrics,
                "process_rss_sampling_sample_count",
                "rssSamplingSampleCount");
        putBooleanMetricIfPresent(values, evaluationMetrics,
                "heap_memory_sampling_enabled",
                "heapSamplingEnabled");
        putBooleanMetricIfPresent(values, evaluationMetrics,
                "heap_memory_sampling_available",
                "heapSamplingAvailable");
        putBooleanMetricIfPresent(values, evaluationMetrics,
                "memory_measurement_required_data_complete",
                "memoryMeasurementRequiredDataComplete");
        putTextMetricIfPresent(values, evaluationMetrics,
                "heap_memory_sampling_error",
                "heapSamplingError");
        values.put("rssSamplingEnabled", Boolean.valueOf(result.rssSamplingEnabled));
        values.put("windowsPeakWorkingSetEnabled",
                Boolean.valueOf(result.windowsPeakWorkingSetEnabled));
        values.put("rssAvailable", Boolean.valueOf(
                result.rssResult != null && result.rssResult.isAvailable()));
        values.put("childProcessStartedAtEpochMillis",
                result.processStartedAtEpochMillis < 0L
                        ? null
                        : Long.valueOf(result.processStartedAtEpochMillis));
        values.put("rssSamplerAttachedAtEpochMillis",
                result.rssSamplerAttachedAtEpochMillis < 0L
                        ? null
                        : Long.valueOf(result.rssSamplerAttachedAtEpochMillis));
        values.put("rssSamplerAttachElapsedMillis",
                result.rssSamplerAttachElapsedMillis < 0L
                        ? null
                        : Long.valueOf(result.rssSamplerAttachElapsedMillis));
        if (result.rssMeasurementError != null) {
            values.put("rssMeasurementError", result.rssMeasurementError);
        }
        if (result.stdoutCopyError != null) {
            values.put("memoryProtocolStdoutCopyError", result.stdoutCopyError);
        }
        if (result.stderrCopyError != null) {
            values.put("childStderrCopyError", result.stderrCopyError);
        }
        MemoryWindowHandshakeCoordinator.Result protocol = result.memoryProtocolResult;
        values.put("memoryProtocolVersion", MemoryMeasurementProtocol.VERSION);
        values.put("memoryProtocolEnabled", Boolean.valueOf(result.rssSamplingEnabled));
        values.put("memoryProtocolAckTimeoutMillis",
                Long.valueOf(configuredMemoryProtocolAckTimeout(config.javaOptions)));
        values.put("memoryProtocolComplete",
                Boolean.valueOf(protocol != null && protocol.isComplete()));
        values.put("memoryProtocolBoundarySamplesComplete",
                Boolean.valueOf(protocol != null
                        && protocol.isComplete()
                        && protocol.isStartBoundarySampleSucceeded()
                        && protocol.isEndBoundarySampleSucceeded()));
        values.put("rssSynthesisWindowCompleteAndValid",
                Boolean.valueOf(result.rssResult != null
                        && result.rssResult.isSynthesisWindowAvailable()
                        && result.rssResult.isSynthesisWindowCompleted()
                        && protocol != null
                        && protocol.isComplete()
                        && protocol.isStartBoundarySampleSucceeded()
                        && protocol.isEndBoundarySampleSucceeded()));
        if (protocol != null) {
            values.put("memoryProtocolStartMarkerCount",
                    Integer.valueOf(protocol.getStartMarkerCount()));
            values.put("memoryProtocolEndMarkerCount",
                    Integer.valueOf(protocol.getEndMarkerCount()));
            values.put("memoryProtocolStartAckSent",
                    Boolean.valueOf(protocol.isStartAckSent()));
            values.put("memoryProtocolEndAckSent",
                    Boolean.valueOf(protocol.isEndAckSent()));
            values.put("memoryProtocolStartBoundarySampleSucceeded",
                    Boolean.valueOf(protocol.isStartBoundarySampleSucceeded()));
            values.put("memoryProtocolEndBoundarySampleSucceeded",
                    Boolean.valueOf(protocol.isEndBoundarySampleSucceeded()));
            values.put("memoryProtocolStartAckWaitMillis",
                    Long.valueOf(protocol.getStartAckWaitMillis()));
            values.put("memoryProtocolEndAckWaitMillis",
                    Long.valueOf(protocol.getEndAckWaitMillis()));
            values.put("memoryProtocolError", protocol.getProtocolError());
        }
        if (result.rssResult != null) {
            values.put("rssProvider", result.rssResult.getProvider());
            values.put("rssSynthesisWindowStarted",
                    Boolean.valueOf(result.rssResult.isSynthesisWindowStarted()));
            values.put("rssSynthesisWindowCompleted",
                    Boolean.valueOf(result.rssResult.isSynthesisWindowCompleted()));
            values.put("rssSynthesisWindowAvailable",
                    Boolean.valueOf(result.rssResult.isSynthesisWindowAvailable()));
            values.put("rssSynthesisWindowStartBoundarySampleSucceeded",
                    Boolean.valueOf(result.rssResult
                            .isSynthesisWindowStartBoundarySampleSucceeded()));
            values.put("rssSynthesisWindowEndBoundarySampleSucceeded",
                    Boolean.valueOf(result.rssResult
                            .isSynthesisWindowEndBoundarySampleSucceeded()));
            values.put("rssSamplingFailureCount",
                    Long.valueOf(result.rssResult.getSampleFailureCount()));
            values.put("rssProviderFailureCount",
                    Long.valueOf(result.rssResult.getProviderFailureCount()));
            values.put("rssProviderTimeoutMillis",
                    Long.valueOf(result.rssResult.getProviderTimeoutMillis()));
            values.put("rssProviderTimeoutCount",
                    Long.valueOf(result.rssResult.getProviderTimeoutCount()));
            values.put("rssSamplingMaxGapMillis",
                    Long.valueOf(result.rssResult.getMaxSampleGapMillis()));
            values.put("rssSamplingTotalWallTimeNanos",
                    Long.valueOf(result.rssResult.getTotalSamplingWallTimeNanos()));
            values.put("rssSamplerThreadCpuTimeNanos",
                    Long.valueOf(result.rssResult.getSamplerThreadCpuTimeNanos()));
            values.put("rssSamplerThreadCpuTimeAvailable",
                    Boolean.valueOf(result.rssResult.isSamplerThreadCpuTimeAvailable()));
            values.put("rssProviderThreadCpuTimeNanos",
                    Long.valueOf(result.rssResult.getProviderThreadCpuTimeNanos()));
            values.put("rssProviderThreadCpuTimeAvailable",
                    Boolean.valueOf(result.rssResult.isProviderThreadCpuTimeAvailable()));
            values.put("rssProviderThreadCpuMeasurementComplete",
                    Boolean.valueOf(result.rssResult
                            .isProviderThreadCpuMeasurementComplete()));
            values.put("rssTotalMeasurementThreadCpuTimeNanos",
                    Long.valueOf(result.rssResult
                            .getTotalMeasurementThreadCpuTimeNanos()));
            values.put("rssTotalMeasurementThreadCpuTimeAvailable",
                    Boolean.valueOf(result.rssResult
                            .isTotalMeasurementThreadCpuTimeAvailable()));
            values.put("rssProviderCircuitOpen",
                    Boolean.valueOf(result.rssResult.isProviderCircuitOpen()));
            values.put("rssProviderDisabledByCircuitBreaker",
                    Boolean.valueOf(result.rssResult
                            .isProviderDisabledByCircuitBreaker()));
            values.put("rssSynthesisWindowSamplingWaitWallTimeNanos",
                    Long.valueOf(result.rssResult
                            .getSynthesisWindowSamplingWallTimeNanos()));
            values.put("rssSynthesisWindowProviderThreadCpuTimeNanos",
                    Long.valueOf(result.rssResult
                            .getSynthesisWindowProviderThreadCpuTimeNanos()));
            values.put("rssSynthesisWindowProviderThreadCpuTimeAvailable",
                    Boolean.valueOf(result.rssResult
                            .isSynthesisWindowProviderThreadCpuTimeAvailable()));
            values.put("rssSynthesisWindowProviderThreadCpuMeasurementComplete",
                    Boolean.valueOf(result.rssResult
                            .isSynthesisWindowProviderThreadCpuMeasurementComplete()));
        }
        if (result.windowsPeakWorkingSetResult != null) {
            values.put("windowsPeakWorkingSetAvailable",
                    Boolean.valueOf(result.windowsPeakWorkingSetResult.isAvailable()));
            values.put("windowsPeakWorkingSetProvider",
                    result.windowsPeakWorkingSetResult.getProvider());
            values.put("windowsPeakWorkingSetFailureReason",
                    result.windowsPeakWorkingSetResult.getFailureReason());
            values.put("windowsPeakWorkingSetNativeErrorCode",
                    Integer.valueOf(result.windowsPeakWorkingSetResult.getNativeErrorCode()));
        }
        Map<String, String> minimizedCounts = readMetricCsvValues(paths.minimizedCountsFile);
        putMetricIfPresent(values, minimizedCounts,
                "raw_output_update_controller_states",
                "rawOutputStates");
        putMetricIfPresent(values, minimizedCounts,
                "raw_output_update_controller_transitions",
                "rawOutputTransitions");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_states",
                "minimizedOutputStates");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_transitions",
                "minimizedOutputTransitions");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_count_time",
                "minimizedCountTimeMillis");
        putMetricIfPresent(values, minimizedCounts,
                "minimized_output_update_controller_minimize_time",
                "minimizeTimeMillis");
        Map<String, String> requirementStatuses = readRequirementCsvValues(paths.requirementsCheckFile);
        if (paths.requirementsCheckEnabled) {
            values.put("requirementsOverall", requirementStatuses.get("OVERALL"));
        }
        values.put("stdout", paths.stdoutFile.getPath());
        values.put("stderr", paths.stderrFile.getPath());
        values.put("startedAt", result.startedAt);
        values.put("endedAt", result.endedAt);
        values.put("timeoutMillis", Long.valueOf(experimentCase.timeoutMillisOrDefault(config.timeoutMillis)));
        values.put("exitCode", result.exitCode);
        values.put("timedOut", Boolean.valueOf(result.timedOut));
        values.put("javaOptions", config.javaOptions);
        values.put("command", result.command);
        if (result.errorMessage != null) {
            values.put("errorMessage", result.errorMessage);
        }

        CliFileLTSOutput.ensureParentDirectory(paths.metaFile);
        Files.write(paths.metaFile.toPath(), toJson(values).getBytes(StandardCharsets.UTF_8));
    }

    private static void notifyBatchCompletion(
            ExperimentConfig config,
            String status,
            int failures,
            String startedAt,
            String endedAt,
            long elapsedMillis,
            String errorMessage) {
        if (!shouldSendSlackNotification(config, status)) {
            return;
        }
        try {
            sendSlackNotification(
                    config.slackWebhookUrl,
                    config.notifyTimeoutSeconds,
                    buildSlackNotificationText(
                            config,
                            status,
                            failures,
                            startedAt,
                            endedAt,
                            elapsedMillis,
                            errorMessage));
            System.out.println("[NOTIFY] Slack notification sent.");
        } catch (Throwable e) {
            System.err.println("[WARN] Failed to send Slack notification: "
                    + sanitizeNotificationError(config, e));
        }
    }

    private static boolean shouldSendSlackNotification(ExperimentConfig config, String status) {
        if (config == null || isBlank(config.slackWebhookUrl)) {
            return false;
        }
        String notifyOn = config.notifyOn == null
                ? "always"
                : config.notifyOn.toLowerCase(Locale.ROOT);
        if ("never".equals(notifyOn)) {
            return false;
        }
        boolean success = STATUS_SUCCESS.equals(status);
        if ("success".equals(notifyOn)) {
            return success;
        }
        if ("failure".equals(notifyOn)) {
            return !success;
        }
        return true;
    }

    private static String buildSlackNotificationText(
            ExperimentConfig config,
            String status,
            int failures,
            String startedAt,
            String endedAt,
            long elapsedMillis,
            String errorMessage) {
        int totalCases = config.runs * config.cases.size();
        StringBuilder builder = new StringBuilder();
        builder.append("*MTSA experiment batch finished*").append('\n');
        builder.append("Status: ").append(status).append('\n');
        builder.append("Case failures: ").append(failures).append(" / ").append(totalCases).append('\n');
        builder.append("Runs: ").append(config.runs)
                .append(", cases/run: ").append(config.cases.size()).append('\n');
        builder.append("Config: ").append(config.configFile.getPath()).append('\n');
        builder.append("Output: ").append(config.outputDir.getPath()).append('\n');
        builder.append("Started: ").append(startedAt).append('\n');
        builder.append("Ended: ").append(endedAt).append('\n');
        builder.append("Elapsed: ").append(formatElapsed(elapsedMillis));
        if (!isBlank(errorMessage)) {
            builder.append('\n').append("Runner error: ").append(truncate(errorMessage, 500));
        }
        return builder.toString();
    }

    private static void sendSlackNotification(
            String webhookUrl,
            int timeoutSeconds,
            String text) throws IOException {
        int timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        byte[] payload = ("{\"text\":\"" + jsonEscape(text) + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        HttpURLConnection connection = (HttpURLConnection) new URL(webhookUrl).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                String response = readSmallResponse(connection.getErrorStream());
                throw new IOException("Slack webhook returned HTTP "
                        + responseCode
                        + (response.isEmpty() ? "" : ": " + response));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readSmallResponse(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        try {
            byte[] buffer = new byte[1024];
            int read = input.read(buffer);
            if (read <= 0) {
                return "";
            }
            return new String(buffer, 0, read, StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static String formatElapsed(long elapsedMillis) {
        long totalSeconds = Math.max(0L, elapsedMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds",
                    Long.valueOf(hours),
                    Long.valueOf(minutes),
                    Long.valueOf(seconds));
        }
        return String.format(Locale.ROOT, "%dm %02ds",
                Long.valueOf(minutes),
                Long.valueOf(seconds));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private static String sanitizeNotificationError(ExperimentConfig config, Throwable error) {
        String message = error == null ? "" : error.toString();
        if (config != null && config.slackWebhookUrl != null) {
            message = message.replace(config.slackWebhookUrl, "<slack-webhook-url>");
        }
        return message;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void writeTraceComparisons(
            File runOutputDir,
            String runLabel,
            List<CompletedCase> completedCases) {
        Map<String, Map<String, CompletedCase>> groups =
                new LinkedHashMap<String, Map<String, CompletedCase>>();
        for (CompletedCase completedCase : completedCases) {
            if (!STATUS_SUCCESS.equals(completedCase.result.status)
                    || completedCase.paths.transitionsFile == null
                    || !completedCase.paths.transitionsFile.isFile()) {
                continue;
            }
            String role = traceRole(completedCase.experimentCase);
            if (role == null) {
                continue;
            }
            String key = completedCase.experimentCase.example
                    + "|"
                    + variantBase(completedCase.experimentCase.variant);
            Map<String, CompletedCase> group = groups.get(key);
            if (group == null) {
                group = new LinkedHashMap<String, CompletedCase>();
                groups.put(key, group);
            }
            group.put(role, completedCase);
        }

        File outputFile = new File(runOutputDir, "trace_comparisons.csv");
        StringBuilder builder = new StringBuilder();
        builder.append("run_label,example,variant_base,pair,left_id,right_id,")
                .append("left_subset_right,right_subset_left,equivalent,")
                .append("left_not_in_right_witness,right_not_in_left_witness,detail\n");
        for (Map.Entry<String, Map<String, CompletedCase>> entry : groups.entrySet()) {
            Map<String, CompletedCase> group = entry.getValue();
            appendTraceComparison(builder, runLabel, group,
                    "traditional", "stepwise_delayed",
                    "Traditional_vs_stepwise_delayed");
            appendTraceComparison(builder, runLabel, group,
                    "traditional", "stepwise_delayed_sbp",
                    "Traditional_vs_stepwise_delayed_SBP");
            appendTraceComparison(builder, runLabel, group,
                    "traditional_sbp", "stepwise_delayed_sbp",
                    "Traditional_SBP_vs_stepwise_delayed_SBP");
        }
        try {
            CliFileLTSOutput.ensureParentDirectory(outputFile);
            Files.write(outputFile.toPath(), builder.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[WARN] Failed to write trace comparison CSV: " + e);
        }
    }

    private static void appendTraceComparison(
            StringBuilder builder,
            String runLabel,
            Map<String, CompletedCase> group,
            String leftRole,
            String rightRole,
            String pairName) {
        CompletedCase left = group.get(leftRole);
        CompletedCase right = group.get(rightRole);
        if (left == null || right == null) {
            return;
        }
        String variantBase = variantBase(left.experimentCase.variant);
        try {
            TransitionGraph leftGraph = TransitionGraph.fromPrintTransitionsFile(left.paths.transitionsFile);
            TransitionGraph rightGraph = TransitionGraph.fromPrintTransitionsFile(right.paths.transitionsFile);
            TraceLanguageChecker.EquivalenceResult result =
                    TraceLanguageChecker.compare(leftGraph, rightGraph);
            builder.append(UpdateRequirementChecker.csv(runLabel == null ? "" : runLabel)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.example)).append(',')
                    .append(UpdateRequirementChecker.csv(variantBase)).append(',')
                    .append(UpdateRequirementChecker.csv(pairName)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.id)).append(',')
                    .append(UpdateRequirementChecker.csv(right.experimentCase.id)).append(',')
                    .append(result.leftSubsetRight.included).append(',')
                    .append(result.rightSubsetLeft.included).append(',')
                    .append(result.equivalent()).append(',')
                    .append(UpdateRequirementChecker.csv(result.leftSubsetRight.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(result.rightSubsetLeft.witnessText())).append(',')
                    .append(UpdateRequirementChecker.csv(traceDetail(result)))
                    .append('\n');
        } catch (Throwable e) {
            builder.append(UpdateRequirementChecker.csv(runLabel == null ? "" : runLabel)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.example)).append(',')
                    .append(UpdateRequirementChecker.csv(variantBase)).append(',')
                    .append(UpdateRequirementChecker.csv(pairName)).append(',')
                    .append(UpdateRequirementChecker.csv(left.experimentCase.id)).append(',')
                    .append(UpdateRequirementChecker.csv(right.experimentCase.id)).append(',')
                    .append("ERROR,ERROR,ERROR,,,")
                    .append(UpdateRequirementChecker.csv(e.toString()))
                    .append('\n');
        }
    }

    private static String traceDetail(TraceLanguageChecker.EquivalenceResult result) {
        StringBuilder builder = new StringBuilder();
        if (!result.leftSubsetRight.included) {
            builder.append("left_not_subset_right: ").append(result.leftSubsetRight.detail);
        }
        if (!result.rightSubsetLeft.included) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append("right_not_subset_left: ").append(result.rightSubsetLeft.detail);
        }
        return builder.toString();
    }

    private static String traceRole(ExperimentCase experimentCase) {
        String text = (safeLower(experimentCase.method)
                + " "
                + safeLower(experimentCase.variant)
                + " "
                + safeLower(experimentCase.id));
        if (text.contains("otf")) {
            return null;
        }
        boolean sbp = text.contains("sbp")
                || text.contains("safetybackward")
                || text.contains("safety_backward")
                || text.contains("safety-backward");
        boolean delayed = text.contains("stepwise_delayed")
                || text.contains("stepwisedelayed")
                || (text.contains("stepwise") && text.contains("delayed"));
        boolean traditional = text.contains("traditional");
        if (delayed) {
            return sbp ? "stepwise_delayed_sbp" : "stepwise_delayed";
        }
        if (traditional) {
            return sbp ? "traditional_sbp" : "traditional";
        }
        return null;
    }

    private static String variantBase(String variant) {
        String value = variant == null ? "" : variant;
        value = value.replaceAll("(?i)(^|[_-])sbp($|[_-])", "$1");
        value = value.replaceAll("(?i)safety[_-]?backward[_-]?pruning", "");
        value = value.replaceAll("__+", "_");
        value = value.replaceAll("--+", "-");
        while (value.startsWith("_") || value.startsWith("-")) {
            value = value.substring(1);
        }
        while (value.endsWith("_") || value.endsWith("-")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.isEmpty() ? "default" : value;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> readMetricCsvValues(File csvFile) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (csvFile == null || !csvFile.isFile()) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", -1);
                if (columns.length >= 4) {
                    values.put(unquoteCsv(columns[1]), unquoteCsv(columns[3]));
                }
            }
        } catch (IOException e) {
            // Keep meta writing best-effort; paths are still recorded above.
        }
        return values;
    }

    private static Map<String, String> readEvaluationCsvValues(File csvFile) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (csvFile == null || !csvFile.isFile()) {
            return values;
        }
        try {
            String csv = new String(
                    Files.readAllBytes(csvFile.toPath()),
                    StandardCharsets.UTF_8);
            List<List<String>> records = parseCsvRecords(csv);
            for (int i = 1; i < records.size(); i++) {
                List<String> columns = records.get(i);
                if (columns.size() >= 7) {
                    values.put(columns.get(4), columns.get(6));
                }
            }
        } catch (IOException e) {
            // Keep meta writing best-effort; the evaluation CSV remains primary.
        }
        return values;
    }

    /** Parses RFC-style quoted CSV records, including embedded newlines. */
    static List<List<String>> parseCsvRecords(String csv) {
        List<List<String>> records = new ArrayList<List<String>>();
        List<String> columns = new ArrayList<String>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean recordHasContent = false;
        String input = csv == null ? "" : csv;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    value.append(ch);
                }
            } else if (ch == '"' && value.length() == 0) {
                quoted = true;
                recordHasContent = true;
            } else if (ch == ',') {
                columns.add(value.toString());
                value.setLength(0);
                recordHasContent = true;
            } else if (ch == '\n' || ch == '\r') {
                columns.add(value.toString());
                value.setLength(0);
                if (recordHasContent || columns.size() > 1
                        || !columns.get(0).isEmpty()) {
                    records.add(columns);
                }
                columns = new ArrayList<String>();
                recordHasContent = false;
                if (ch == '\r' && i + 1 < input.length()
                        && input.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                value.append(ch);
                recordHasContent = true;
            }
        }
        // An unterminated quoted record is deliberately discarded. With the
        // child-side atomic writer it can only come from an older/corrupt file,
        // and appending parent metrics must not legitimize that partial row.
        if (!quoted && (recordHasContent || !columns.isEmpty() || value.length() > 0)) {
            columns.add(value.toString());
            records.add(columns);
        }
        return records;
    }

    private static Map<String, String> readRequirementCsvValues(File csvFile) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        if (csvFile == null || !csvFile.isFile()) {
            return values;
        }
        try {
            List<String> lines = Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
            for (int i = 1; i < lines.size(); i++) {
                String[] columns = lines.get(i).split(",", -1);
                if (columns.length >= 3) {
                    values.put(unquoteCsv(columns[0]), unquoteCsv(columns[2]));
                }
            }
        } catch (IOException e) {
            // Keep meta writing best-effort; paths are still recorded above.
        }
        return values;
    }

    private static String unquoteCsv(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            return value == null ? "" : value;
        }
        return value.substring(1, value.length() - 1).replace("\"\"", "\"");
    }

    private static void putMetricIfPresent(
            Map<String, Object> output,
            Map<String, String> metrics,
            String metricKey,
            String jsonKey) {
        String value = metrics.get(metricKey);
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        try {
            output.put(jsonKey, Long.valueOf(value));
        } catch (NumberFormatException e) {
            output.put(jsonKey, value);
        }
    }

    private static void putBooleanMetricIfPresent(
            Map<String, Object> output,
            Map<String, String> metrics,
            String metricKey,
            String jsonKey) {
        String value = metrics.get(metricKey);
        if (value != null && !value.trim().isEmpty()) {
            output.put(jsonKey, Boolean.valueOf(value));
        }
    }

    private static void putTextMetricIfPresent(
            Map<String, Object> output,
            Map<String, String> metrics,
            String metricKey,
            String jsonKey) {
        String value = metrics.get(metricKey);
        if (value != null) {
            output.put(jsonKey, value);
        }
    }

    private static String toJson(Map<String, Object> values) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        int index = 0;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            builder.append("  \"").append(jsonEscape(entry.getKey())).append("\": ");
            appendJsonValue(builder, entry.getValue());
            index++;
            if (index < values.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("}\n");
        return builder.toString();
    }

    private static void appendJsonValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Iterable) {
            builder.append('[');
            int index = 0;
            for (Object item : (Iterable<?>) value) {
                if (index > 0) {
                    builder.append(", ");
                }
                appendJsonValue(builder, item);
                index++;
            }
            builder.append(']');
        } else {
            builder.append('"').append(jsonEscape(value.toString())).append('"');
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                builder.append('\\').append(ch);
            } else if (ch == '\n') {
                builder.append("\\n");
            } else if (ch == '\r') {
                builder.append("\\r");
            } else if (ch == '\t') {
                builder.append("\\t");
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                options.put("help", "true");
            } else if ("--dry-run".equals(arg)) {
                options.put("dry-run", "true");
            } else if (arg.startsWith("--")) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                options.put(arg.substring(2), args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        return options;
    }

    private static void applyBooleanOverride(
            Map<String, String> options,
            String optionName,
            ExperimentConfig config,
            String fieldName) {
        if (!options.containsKey(optionName)) {
            return;
        }
        boolean value = parseBooleanOption(options.get(optionName), optionName);
        if ("requirementsCheck".equals(fieldName)) {
            config.requirementsCheck = value;
        } else if ("traceCheck".equals(fieldName)) {
            config.traceCheck = value;
        }
    }

    private static boolean parseBooleanOption(String value, String optionName) {
        if ("true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "no".equalsIgnoreCase(value)
                || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("--" + optionName + " must be true or false: " + value);
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("--" + key + " is required.");
        }
        return value;
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -cp mtsa.jar "
                + "ltsa.updatingControllers.cli.BatchExperimentRunner "
                + "--config Experiment/configs/run.yaml");
        out.println("");
        out.println("Config format:");
        out.println("  outputDir: Experiment/result");
        out.println("  runs: 5  # optional; writes Experiment/run_01/result, Experiment/run_02/result, ...");
        out.println("  timeoutHours: 16");
        out.println("  requirementsCheck: true  # optional; default false");
        out.println("  traceCheck: true         # optional; default false");
        out.println("  notifyOn: always         # optional; always, success, failure, never");
        out.println("  slackWebhookUrl: https://hooks.slack.com/services/...  # optional; keep out of Git");
        out.println("  notifyTimeoutSeconds: 30 # optional; default 30");
        out.println("  javaOptions:");
        out.println("    - -Xmx32g");
        out.println("    - -Dmtsa.evaluation.enabled=true");
        out.println("  cases:");
        out.println("    - id: workflow_traditional_no_tr");
        out.println("      example: Workflow");
        out.println("      method: Traditional");
        out.println("      variant: no_tr");
        out.println("      lts: Experiment/lts/Workflow/workflow_no_tr.lts");
        out.println("      target: UpdCont");
        out.println("      requirementsCheck: false  # optional per-case override");
        out.println("");
        out.println("CLI top-level defaults:");
        out.println("  --requirements-check true|false");
        out.println("  --trace-check true|false");
    }

    private static String now() {
        return ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static boolean joinBounded(Thread thread, long timeoutMillis) {
        if (thread == null) {
            return true;
        }
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (thread.isAlive()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                long waitMillis = Math.max(
                        1L,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                thread.join(waitMillis);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private static String runLabel(int runIndex, int runCount) {
        int width = Math.max(2, Integer.toString(runCount).length());
        return String.format(Locale.ROOT, "run_%0" + width + "d", Integer.valueOf(runIndex));
    }

    private static File outputDirForRun(File outputDir, String runLabel) {
        if (runLabel == null) {
            return outputDir;
        }
        File parent = outputDir.getParentFile();
        if (parent == null) {
            return new File(new File(runLabel), outputDir.getPath());
        }
        return new File(new File(parent, runLabel), outputDir.getName());
    }

    private static final class CasePaths {
        final File caseDirectory;
        final File outputFile;
        final File transitionsFile;
        final File minimizedTransitionsFile;
        final File minimizedCountsFile;
        final File requirementsCheckFile;
        final boolean requirementsCheckEnabled;
        final File evaluationCsvFile;
        final File stdoutFile;
        final File stderrFile;
        final File metaFile;
        final int runIndex;
        final int runCount;
        final String runLabel;

        private CasePaths(
                File caseDirectory,
                File outputFile,
                File transitionsFile,
                File minimizedTransitionsFile,
                File minimizedCountsFile,
                File requirementsCheckFile,
                boolean requirementsCheckEnabled,
                File evaluationCsvFile,
                File stdoutFile,
                File stderrFile,
                File metaFile,
                int runIndex,
                int runCount,
                String runLabel) {
            this.caseDirectory = caseDirectory;
            this.outputFile = outputFile;
            this.transitionsFile = transitionsFile;
            this.minimizedTransitionsFile = minimizedTransitionsFile;
            this.minimizedCountsFile = minimizedCountsFile;
            this.requirementsCheckFile = requirementsCheckFile;
            this.requirementsCheckEnabled = requirementsCheckEnabled;
            this.evaluationCsvFile = evaluationCsvFile;
            this.stdoutFile = stdoutFile;
            this.stderrFile = stderrFile;
            this.metaFile = metaFile;
            this.runIndex = runIndex;
            this.runCount = runCount;
            this.runLabel = runLabel;
        }

        static CasePaths create(
                File outputDir,
                ExperimentCase experimentCase,
                boolean defaultRequirementsCheck,
                int runIndex,
                int runCount,
                String runLabel) {
            File caseDirectory = new File(
                    new File(outputDir, ExperimentConfig.sanitizePreservingCase(experimentCase.example)),
                    experimentCase.methodFolderName());
            String prefix = experimentCase.filePrefix();
            String target = ExperimentConfig.sanitizePreservingCase(experimentCase.target);
            String caseId = ExperimentConfig.sanitize(experimentCase.id);
            boolean requirementsCheckEnabled =
                    experimentCase.requirementsCheckOrDefault(defaultRequirementsCheck);
            return new CasePaths(
                    caseDirectory,
                    new File(caseDirectory, prefix + "_output.txt"),
                    new File(caseDirectory, prefix + "_transitions_" + target + ".txt"),
                    new File(caseDirectory, caseId + "_minimized_transitions_" + target + ".txt"),
                    new File(caseDirectory, caseId + "_minimized_counts_" + target + ".csv"),
                    requirementsCheckEnabled
                            ? new File(caseDirectory, caseId + "_requirements_check_" + target + ".csv")
                            : null,
                    requirementsCheckEnabled,
                    new File(caseDirectory, caseId + "_evaluation_" + target + ".csv"),
                    new File(caseDirectory, prefix + "_stdout.txt"),
                    new File(caseDirectory, prefix + "_stderr.txt"),
                    new File(caseDirectory, prefix + "_meta.json"),
                    runIndex,
                    runCount,
                    runLabel);
        }

        void ensureDirectories() throws IOException {
            if (!caseDirectory.exists() && !caseDirectory.mkdirs()) {
                throw new IOException("Failed to create directory: " + caseDirectory);
            }
        }
    }

    private static final class CaseResult {
        String status = STATUS_EXCEPTION;
        Integer exitCode;
        boolean timedOut;
        String startedAt;
        String endedAt;
        String errorMessage;
        List<String> command;
        boolean rssSamplingEnabled;
        boolean heapSamplingEnabled;
        boolean windowsPeakWorkingSetEnabled;
        boolean memoryMetricsWriteAttempted;
        boolean memoryMetricsWriteSucceeded;
        String rssMeasurementError;
        String stdoutCopyError;
        String stderrCopyError;
        long processStartedAtEpochMillis = -1L;
        long processStartedAtNanos = -1L;
        long rssSamplerAttachedAtEpochMillis = -1L;
        long rssSamplerAttachElapsedMillis = -1L;
        ProcessRssSampler.Result rssResult;
        MemoryWindowHandshakeCoordinator.Result memoryProtocolResult;
        WindowsPeakWorkingSetProbe.Result windowsPeakWorkingSetResult;
    }

    private static final class CompletedCase {
        final ExperimentCase experimentCase;
        final CasePaths paths;
        final CaseResult result;

        CompletedCase(ExperimentCase experimentCase, CasePaths paths, CaseResult result) {
            this.experimentCase = experimentCase;
            this.paths = paths;
            this.result = result;
        }
    }

    private static final class StreamCopyThread extends Thread {
        private final InputStream input;
        private final File outputFile;
        private final MemoryWindowMarkerDetector markerDetector;
        private volatile String errorMessage;

        StreamCopyThread(InputStream input, File outputFile) {
            this(input, outputFile, null);
        }

        StreamCopyThread(
                InputStream input,
                File outputFile,
                MemoryWindowMarkerDetector markerDetector) {
            super("stream-copy-" + outputFile.getName());
            this.input = input;
            this.outputFile = outputFile;
            this.markerDetector = markerDetector;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                CliFileLTSOutput.ensureParentDirectory(outputFile);
            } catch (IOException e) {
                recordError(e);
            }
            copy(input, outputFile, markerDetector);
        }

        String getErrorMessage() {
            return errorMessage;
        }

        private void copy(
                InputStream input,
                File outputFile,
                MemoryWindowMarkerDetector markerDetector) {
            BufferedInputStream bufferedInput = new BufferedInputStream(input);
            OutputStream output = null;
            try {
                try {
                    output = new FileOutputStream(outputFile, false);
                } catch (IOException e) {
                    // Continue draining the pipe and recognizing protocol
                    // markers even when the diagnostic file cannot be opened.
                    recordError(e);
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = bufferedInput.read(buffer)) >= 0) {
                    if (markerDetector != null) {
                        markerDetector.accept(buffer, 0, read);
                    }
                    if (output != null) {
                        try {
                            output.write(buffer, 0, read);
                            output.flush();
                        } catch (IOException e) {
                            recordError(e);
                            try {
                                output.close();
                            } catch (IOException closeError) {
                                recordError(closeError);
                            }
                            output = null;
                        }
                    }
                }
            } catch (IOException e) {
                recordError(e);
            } finally {
                try {
                    if (markerDetector != null) {
                        markerDetector.endOfInput();
                    }
                    try {
                        bufferedInput.close();
                    } catch (IOException e) {
                        recordError(e);
                    }
                } finally {
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException e) {
                            recordError(e);
                        }
                    }
                }
            }
        }

        private void recordError(IOException error) {
            if (errorMessage == null) {
                errorMessage = error.toString();
            } else {
                errorMessage += "; " + error;
            }
        }
    }
}
