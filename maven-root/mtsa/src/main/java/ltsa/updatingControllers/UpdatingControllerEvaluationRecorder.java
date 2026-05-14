package ltsa.updatingControllers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ltsa.lts.LTSOutput;

/**
 * 更新コントローラ合成の評価値を 1 回の合成単位で集約する recorder。
 */
public final class UpdatingControllerEvaluationRecorder {

    public enum ResultStatus {
        NOT_RECORDED,
        SUCCESS,
        GOAL_NOT_REACHABLE,
        NOT_CONTROLLABLE,
        OUT_OF_MEMORY,
        EXCEPTION,
        UNKNOWN_FAILURE
    }

    private static final Map<String, List<String>> sections = new LinkedHashMap<>();
    private static final Map<String, ActiveTimer> activeTimers = new LinkedHashMap<>();
    private static final Map<String, LineRef> lineRefs = new LinkedHashMap<>();
    private static final Map<String, Long> timeMillisByKey = new LinkedHashMap<>();
    private static final Map<String, DataMetric> dataMetrics = new LinkedHashMap<>();

    private static String mode = "未記録";
    private static ResultStatus resultStatus = ResultStatus.NOT_RECORDED;
    private static String failureMessage = "";
    private static boolean printed = false;
    private static long stateSpaceCountOverheadMillis = 0;
    private static long oldControllerStates = -1;
    private static long beginUpdateReferenceStates = -1;
    private static long memoryBaselineBytes = -1;
    private static long previousMemoryCheckpointBytes = -1;

    private UpdatingControllerEvaluationRecorder() {
    }

    public static synchronized void reset() {
        sections.clear();
        activeTimers.clear();
        lineRefs.clear();
        timeMillisByKey.clear();
        dataMetrics.clear();
        mode = "未記録";
        resultStatus = ResultStatus.NOT_RECORDED;
        failureMessage = "";
        printed = false;
        stateSpaceCountOverheadMillis = 0;
        oldControllerStates = -1;
        beginUpdateReferenceStates = -1;
        memoryBaselineBytes = -1;
        previousMemoryCheckpointBytes = -1;
    }

    public static synchronized void setMode(String value) {
        if (value != null && !value.isEmpty()) {
            mode = value;
        }
    }

    public static synchronized void markSuccess() {
        if (!isFailureStatus(resultStatus)) {
            resultStatus = ResultStatus.SUCCESS;
            failureMessage = "";
        }
    }

    public static synchronized void recordFailure(ResultStatus status, String message) {
        if (status == null) {
            status = ResultStatus.UNKNOWN_FAILURE;
        }
        resultStatus = status;
        failureMessage = message == null ? "" : message;
    }

    public static synchronized void recordFailureIfAbsent(ResultStatus status, String message) {
        if (!isFailureStatus(resultStatus)) {
            recordFailure(status, message);
        }
    }

    public static synchronized void recordTime(String section, String label, long millis) {
        putOrReplaceTime(section, label, millis, "");
    }

    public static synchronized void beginFailureTimer(String section, String label) {
        activeTimers.put(timerKey(section, label), new ActiveTimer(section, label, System.currentTimeMillis()));
        putOrReplace(section, label, label + " : 計測中");
    }

    public static synchronized void endFailureTimer(String section, String label) {
        ActiveTimer timer = activeTimers.remove(timerKey(section, label));
        if (timer != null) {
            putOrReplaceTime(timer.section, timer.label,
                    System.currentTimeMillis() - timer.startMillis,
                    "");
        }
    }

    public static synchronized void recordNanoTime(String section, String label, long nanos) {
        add(section, label + " : " + formatNanos(nanos));
        recordDataMetric(section, label, nanosToMillisText(nanos), "ms");
    }

    public static synchronized void recordAverageNanoTime(String section, String label, long nanos, long count) {
        if (count <= 0) {
            add(section, label + " : 0.000 ms / call (0 calls)");
            recordDataMetric(section, label, "0.000", "ms/call");
            return;
        }
        double averageMillis = nanos / 1_000_000.0 / count;
        add(section, label + " : "
                + String.format(Locale.ROOT, "%.3f ms / call", averageMillis)
                + " (" + count + " calls)");
        recordDataMetric(section, label, String.format(Locale.ROOT, "%.3f", averageMillis), "ms/call");
    }

    public static synchronized void recordCount(String section, String label, long count, String unit) {
        add(section, label + " : " + count + " " + unit);
        recordDataMetric(section, label, Long.toString(count), unit == null ? "count" : unit);
    }

    public static synchronized void recordStateSpace(
            String section, String label, long states, long transitions, long countTimeMillis) {
        recordStateSpace(section, label, states, transitions, countTimeMillis, "");
    }

    public static synchronized void recordStateSpace(
            String section, String label, long states, long transitions, long countTimeMillis, String description) {
        stateSpaceCountOverheadMillis += Math.max(0, countTimeMillis);
        add(section, label + " States: " + states
                + ", Transitions: " + transitions
                + ", CountTime: " + countTimeMillis + " ms");
        if (description != null && !description.isEmpty()) {
            add(section, "  説明: " + description);
        }
        String baseKey = metricKey(section, label);
        recordDataMetric(baseKey + "_states", section, label + " / States", Long.toString(states), "states");
        recordDataMetric(baseKey + "_transitions", section, label + " / Transitions", Long.toString(transitions), "transitions");
        recordDataMetric(baseKey + "_count_time", section, label + " / CountTime", Long.toString(countTimeMillis), "ms");
    }

    public static synchronized void recordOldControllerStateSpace(
            long states, long transitions, long countTimeMillis) {
        oldControllerStates = states;
        recordStateSpace("入力規模 / 事前合成", "Old Controller", states, transitions, countTimeMillis);
    }

    public static synchronized void recordMemory(String section, String label, long bytes) {
        add(section, label + " : " + formatBytes(bytes));
        recordDataMetric(section, label, bytesToMiBText(bytes), "MB");
    }

    public static synchronized void recordMemoryCheckpoint(String label) {
        recordMemoryCheckpoint("メモリ使用量チェックポイント", label);
    }

    public static synchronized void recordMemoryCheckpoint(String section, String label) {
        String normalizedSection = section == null || section.isEmpty()
                ? "メモリ使用量チェックポイント"
                : section;
        ensureMemoryCheckpointHeader(normalizedSection);

        long currentBytes = EvaluationProfiler.getCurrentMemoryUsage();
        long peakBytes = EvaluationProfiler.getPeakMemoryUsage();
        if (memoryBaselineBytes < 0) {
            memoryBaselineBytes = currentBytes;
        }
        long deltaFromBaseline = currentBytes - memoryBaselineBytes;
        long deltaFromPrevious = previousMemoryCheckpointBytes < 0
                ? 0
                : currentBytes - previousMemoryCheckpointBytes;
        previousMemoryCheckpointBytes = currentBytes;

        add(normalizedSection,
                padRight(label, 46)
                        + " 現在ヒープ=" + padLeft(formatMiB(currentBytes), 8)
                        + " ピークヒープ=" + padLeft(formatMiB(peakBytes), 8)
                        + " 開始時からの増減=" + padLeft(formatSignedMiB(deltaFromBaseline), 9)
                        + " 直前からの増減=" + padLeft(formatSignedMiB(deltaFromPrevious), 9));
        String baseKey = metricKey(normalizedSection, label);
        recordDataMetric(baseKey + "_current_heap", normalizedSection, label + " / 現在ヒープ", bytesToMiBText(currentBytes), "MB");
        recordDataMetric(baseKey + "_peak_heap", normalizedSection, label + " / ピークヒープ", bytesToMiBText(peakBytes), "MB");
        recordDataMetric(baseKey + "_delta_from_start", normalizedSection, label + " / 開始時からの増減", bytesToMiBText(deltaFromBaseline), "MB");
        recordDataMetric(baseKey + "_delta_from_previous", normalizedSection, label + " / 直前からの増減", bytesToMiBText(deltaFromPrevious), "MB");
    }

    public static synchronized void recordOutputController(long states, long transitions, long countTimeMillis) {
        stateSpaceCountOverheadMillis += Math.max(0, countTimeMillis);
        add("Output Update Controller", "States: " + states
                + ", Transitions: " + transitions
                + ", CountTime: " + countTimeMillis + " ms");
        recordDataMetric("output_update_controller_states", "Output Update Controller", "States", Long.toString(states), "states");
        recordDataMetric("output_update_controller_transitions", "Output Update Controller", "Transitions", Long.toString(transitions), "transitions");
        recordDataMetric("output_update_controller_count_time", "Output Update Controller", "CountTime", Long.toString(countTimeMillis), "ms");
    }

    public static synchronized void recordBeginUpdateCoverage(long beginUpdateStates, long countTimeMillis) {
        stateSpaceCountOverheadMillis += Math.max(0, countTimeMillis);
        long denominator = oldControllerStates >= 0 ? oldControllerStates : beginUpdateReferenceStates;
        if (denominator >= 0 && beginUpdateStates <= denominator) {
            add("要件確認", "beginUpdate が出ている状態数 : " + beginUpdateStates
                    + " / 旧コントローラ状態数 " + denominator
                    + " 状態, CountTime: " + countTimeMillis + " ms");
        } else if (denominator >= 0) {
            add("要件確認", "beginUpdate が出ている状態数 : " + beginUpdateStates
                    + " 状態, 旧コントローラ状態数 : " + denominator
                    + ", CountTime: " + countTimeMillis + " ms");
        } else {
            add("要件確認", "beginUpdate が出ている状態数 : " + beginUpdateStates
                    + ", CountTime: " + countTimeMillis + " ms");
        }
        recordDataMetric("begin_update_outgoing_states", "要件確認", "beginUpdate outgoing states", Long.toString(beginUpdateStates), "states");
        if (denominator >= 0) {
            recordDataMetric("begin_update_reference_states", "要件確認", "beginUpdate reference states", Long.toString(denominator), "states");
            recordDataMetric("old_controller_states_for_begin_update", "要件確認", "旧コントローラ状態数", Long.toString(denominator), "states");
        }
        recordDataMetric("begin_update_coverage_count_time", "要件確認", "beginUpdate coverage CountTime", Long.toString(countTimeMillis), "ms");
    }

    public static synchronized void recordOtfPreUpdateStateOverhead(
            long oldControllerStateCount,
            long preUpdateRawStateCount,
            long preUpdateOutputStateCount) {

        long effectiveOldControllerStates = oldControllerStates >= 0
                ? oldControllerStates
                : oldControllerStateCount;
        if (oldControllerStates < 0 && oldControllerStateCount >= 0) {
            oldControllerStates = oldControllerStateCount;
        }

        long overhead = effectiveOldControllerStates >= 0 && preUpdateOutputStateCount >= 0
                ? Math.max(0, preUpdateOutputStateCount - effectiveOldControllerStates)
                : -1;

        if (effectiveOldControllerStates >= 0) {
            add("要件確認", "旧コントローラ状態数 : " + effectiveOldControllerStates + " 状態");
            recordDataMetric("old_controller_states_for_begin_update", "要件確認",
                    "旧コントローラ状態数", Long.toString(effectiveOldControllerStates), "states");
        }

        add("要件確認", "探索上の旧コントローラ相当状態数（出力時マージ前） : "
                + preUpdateRawStateCount + " 状態");
        add("要件確認", "出力上の旧コントローラ相当状態数（マージ後） : "
                + preUpdateOutputStateCount + " 状態");
        if (overhead >= 0) {
            add("要件確認", "OTF-DUCにより増えた旧コントローラ相当状態数 : "
                    + overhead + " 状態");
        }

        recordDataMetric("otf_pre_update_raw_states", "要件確認",
                "探索上の旧コントローラ相当状態数（出力時マージ前）",
                Long.toString(preUpdateRawStateCount), "states");
        recordDataMetric("otf_pre_update_output_states", "要件確認",
                "出力上の旧コントローラ相当状態数（マージ後）",
                Long.toString(preUpdateOutputStateCount), "states");
        if (overhead >= 0) {
            recordDataMetric("otf_pre_update_state_overhead", "要件確認",
                    "OTF-DUCにより増えた旧コントローラ相当状態数",
                    Long.toString(overhead), "states");
        }
    }

    public static synchronized boolean hasOldControllerStateSpace() {
        return oldControllerStates >= 0;
    }

    public static synchronized void recordBeginUpdateReferenceStates(long states) {
        if (states >= 0) {
            beginUpdateReferenceStates = states;
        }
    }

    public static synchronized void recordValue(String section, String label, String value) {
        add(section, label + " : " + value);
        recordDataMetric(section, label, value == null ? "" : value, "text");
    }

    public static synchronized long getRecordedTimeMillis(String section, String label) {
        return optionalTime(section, label);
    }

    public static synchronized void recordMemorySnapshot(String section) {
        Runtime runtime = Runtime.getRuntime();
        recordMemory(section, "現在のヒープ使用量", EvaluationProfiler.getCurrentMemoryUsage());
        recordMemory(section, "ピークヒープ使用量", EvaluationProfiler.getPeakMemoryUsage());
        recordMemory(section, "JVM 最大ヒープ", runtime.maxMemory());
        recordMemory(section, "JVM totalMemory", runtime.totalMemory());
        recordMemory(section, "JVM freeMemory", runtime.freeMemory());
    }

    public static synchronized void printSummary(LTSOutput output) {
        if (output == null || printed) {
            return;
        }
        printed = true;

        if (resultStatus == ResultStatus.NOT_RECORDED) {
            resultStatus = ResultStatus.UNKNOWN_FAILURE;
        }
        flushActiveTimers();
        recordComparisonSummary();

        output.outln("");
        output.outln("================ EVALUATION ================");
        output.outln("Mode: " + mode);
        output.outln("Result: " + resultStatus);
        if (isFailureStatus(resultStatus) && !failureMessage.isEmpty()) {
            output.outln("Failure reason: " + failureMessage);
        }
        output.outln("State/transition count overhead total: " + stateSpaceCountOverheadMillis + " ms");
        recordDataMetric("state_transition_count_overhead_total", "Evaluation Summary",
                "State/transition count overhead total", Long.toString(stateSpaceCountOverheadMillis), "ms");

        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            output.outln("");
            output.outln("[" + entry.getKey() + "]");
            printSectionDescription(output, entry.getKey());
            for (String line : entry.getValue()) {
                output.outln(line);
            }
        }
        output.outln("====================================================");
        output.outln("");
        printEvaluationSummary(output);
        printDataCsv(output);
    }

    private static boolean isFailureStatus(ResultStatus status) {
        return status == ResultStatus.GOAL_NOT_REACHABLE
                || status == ResultStatus.NOT_CONTROLLABLE
                || status == ResultStatus.OUT_OF_MEMORY
                || status == ResultStatus.EXCEPTION
                || status == ResultStatus.UNKNOWN_FAILURE;
    }

    private static void printSectionDescription(LTSOutput output, String section) {
        String description = sectionDescription(section);
        if (!description.isEmpty()) {
            output.outln("説明: " + description);
        }

        List<String> notes = sectionMetricNotes(section);
        if (!notes.isEmpty()) {
            output.outln("主な項目:");
            for (String note : notes) {
                output.outln("  - " + note);
            }
        }
    }

    private static String sectionDescription(String section) {
        if ("UpdatingControllersDefinition".equals(section)) {
            return "更新コントローラ定義を読み取り、旧コントローラ・Mapping Environment・要求・手法固有の補助モデルを準備する前処理。";
        }
        if ("UpdatingControllerSynthesizer".equals(section)) {
            return "準備済みモデルから Traditional DUC または OTF-DUC の実際の合成処理を起動する入口。";
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            return "Traditional DUC で E_u から safetyEnv を作り、最後に GR1 で update controller を合成する処理。";
        }
        if ("Traditional DUC safetyEnv 構築時間内訳".equals(section)) {
            return "Traditional DUC の safetyEnv を作る内部処理。Fluent 評価、safety 違反 pruning、DontDoTwice 合成を含む。";
        }
        if ("Traditional DUC GR1 時間内訳".equals(section)) {
            return "Traditional DUC の最終 safety 環境を GR1 合成器に渡し、出力コントローラを得る処理。";
        }
        if ("generateDUC (OTF-DUC)".equals(section)) {
            return "OTF-DUC で on-the-fly 探索用の boxList や対応表を準備し、DCS を実行して update controller を生成する処理。";
        }
        if ("DCS (OTF-DUC)".equals(section)) {
            return "OTF-DUC の on-the-fly 探索本体。状態展開、fairness/loop 判定、出力構築を行う。";
        }
        if ("OTF-DUC 探索時間内訳".equals(section)) {
            return "OTF-DUC の探索順序を決めるヒューリスティックと frontier 操作に関する時間内訳。";
        }
        if ("OTF-DUC 展開時間内訳".equals(section)) {
            return "OTF-DUC で action を展開し、同期先・安全性・次状態を計算する処理の時間内訳。";
        }
        if ("OTF-DUC loop / fairness 時間内訳".equals(section)) {
            return "OTF-DUC の loop 検出と fairness 判定に関する時間内訳。";
        }
        if ("OTF-DUC 伝播時間内訳".equals(section)) {
            return "探索木上で GOAL / ERROR の判定結果を親状態へ伝播する処理の時間内訳。";
        }
        if ("OTF-DUC 出力構築時間内訳".equals(section)) {
            return "探索結果から最終 update controller を構築し、出力時 pruning を判定する処理の時間内訳。";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            return "Traditional DUC の中間状態空間サイズ。E_u、Meta、Pruned、Final の各段階を比較するための値。";
        }
        if ("入力規模".equals(section)) {
            return "合成問題として与えられた環境コンポーネント、要求、controllable action などの入力サイズ。";
        }
        if ("入力規模 / 事前合成".equals(section)) {
            return "旧コントローラや新コントローラなど、手法本体の前に合成・参照される主要モデルのサイズ。";
        }
        if ("メモリ使用量チェックポイント".equals(section)) {
            return "合成の各段階で取得したヒープ使用量。ピーク増加箇所を確認するための参考値。";
        }
        if ("共通 / HPWindow".equals(section)) {
            return "GUI から合成を起動した場合の全体時間、前処理時間、描画時間、メモリなどの共通計測。";
        }
        if ("TransitionSystemDispatcher".equals(section)) {
            return "合成後の CompactState に対する共通後処理。Traditional DUC では .old action の relabel などを行う。";
        }
        if ("Output Update Controller".equals(section)) {
            return "最終的に出力された update controller の状態数・遷移数。";
        }
        if ("要件確認".equals(section)) {
            return "update controller の要件に関する簡易チェック。例: beginUpdate が旧コントローラの何状態から出ているか。";
        }
        if ("比較用時間集計".equals(section)) {
            return "OTF-DUC と Traditional DUC を比較しやすいように、共通前処理や評価用オーバーヘッドを差し引いた集計。";
        }
        return "";
    }

    private static List<String> sectionMetricNotes(String section) {
        List<String> notes = new ArrayList<>();
        if ("UpdatingControllersDefinition".equals(section)) {
            notes.add("compose の全体実行時間: 更新コントローラ定義から合成用データ構造を作る前処理全体の時間。");
            notes.add("Old Controller 合成時間: 旧環境と旧要求から旧コントローラを事前合成する時間。");
            notes.add("Mapping Environment Component 合成時間: old/new 環境と対応関係から mapping component を作る時間。");
            notes.add("New Controller 合成時間: OTF-DUC で接続先として使う新コントローラを事前合成する時間。");
            notes.add("Safety の tester 変換全体時間: safety / transition requirement を探索用 tester LTS に変換する時間。");
            notes.add("Traditional DUC grGoal/safetyGoal 生成時間: Traditional DUC 用の GR1 目標と safety 目標を生成する時間。");
        } else if ("UpdatingControllerSynthesizer".equals(section)) {
            notes.add("generateController の全体実行時間: 手法本体を呼び出して update controller を生成する外側の時間。");
            notes.add("Traditional solveControlProblem / OTF generateDUC 実行時間: Traditional では GR1 合成処理、OTF では on-the-fly DUC 生成処理の時間。");
            notes.add("Traditional DUC E_u 構築時間: 旧コントローラと Mapping Environment から更新環境 E_u を構築する時間。");
        } else if ("solveControlProblem (Traditional DUC)".equals(section)) {
            notes.add("Fluent とベース環境を並列合成した metaEnv 構築時間: safety 評価用に E_u と Fluent を組み合わせる時間。");
            notes.add("metaEnv からエラーを枝刈りして safetyEnv を構築する時間: safety formula 違反状態を除去する時間。");
            notes.add("safetyEnv を GR1 で解く時間: 最終 safety 環境から controller を合成する中核時間。");
        } else if ("generateDUC (OTF-DUC)".equals(section)) {
            notes.add("boxList 準備時間: on-the-fly 探索に渡す Marking LTS、旧コントローラ、MapEnv、安全性などを並べる時間。");
            notes.add("New Controller の接続先の事前計算: finishUpdate 後に新コントローラへ接続する状態対応表を作る時間。");
            notes.add("DCS で Update Controller を合成する時間: OTF-DUC の探索から出力 controller 構築までの中心時間。");
        } else if ("Traditional DUC GR1 時間内訳".equals(section)) {
            notes.add("GR goal 構築時間: guarantee / assumption などから GR1 目標を構築する時間。");
            notes.add("Winning region 計算時間: GR1 game 上で勝ち領域を求める時間。");
            notes.add("Strategy 構築時間: 勝ち領域から controller strategy を作る時間。");
            notes.add("Strategy から controller MTS を構築する時間: strategy を出力 controller の MTS に変換する時間。");
        } else if ("比較用時間集計".equals(section)) {
            notes.add("除外する共通前処理時間: 両手法に共通する旧コントローラ合成、Goal 準備、Mapping component 生成の合計。");
            notes.add("手法固有時間: OTF-DUC または Traditional DUC に固有の準備・中核・後処理を合計した時間。");
            notes.add("主比較対象の中核合成時間: OTF では DCS、Traditional では E_u 構築 + solveControlProblem を対象にした時間。");
        }
        return notes;
    }

    private static void add(String section, String line) {
        String normalizedSection = section == null || section.isEmpty() ? "その他" : section;
        sections.computeIfAbsent(normalizedSection, k -> new ArrayList<>()).add(line);
    }

    private static void putOrReplace(String section, String label, String line) {
        String normalizedSection = section == null || section.isEmpty() ? "その他" : section;
        String key = timerKey(normalizedSection, label);
        LineRef ref = lineRefs.get(key);
        if (ref != null) {
            List<String> lines = sections.get(ref.section);
            if (lines != null && ref.index >= 0 && ref.index < lines.size()) {
                lines.set(ref.index, line);
                return;
            }
        }

        List<String> lines = sections.computeIfAbsent(normalizedSection, k -> new ArrayList<>());
        lines.add(line);
        lineRefs.put(key, new LineRef(normalizedSection, lines.size() - 1));
    }

    private static void putOrReplaceTime(String section, String label, long millis, String suffix) {
        long normalizedMillis = Math.max(0, millis);
        timeMillisByKey.put(timerKey(section, label), normalizedMillis);
        putOrReplace(section, label, label + suffix + " : " + normalizedMillis + " ms");
        recordDataMetric(metricKey(section, label), section, label + suffix, Long.toString(normalizedMillis), "ms");
    }

    private static String formatNanos(long nanos) {
        return String.format(Locale.ROOT, "%.3f ms", nanos / 1_000_000.0);
    }

    private static String nanosToMillisText(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0);
    }

    private static String bytesToMiBText(long bytes) {
        return String.format(Locale.ROOT, "%.2f", bytes / 1024.0 / 1024.0);
    }

    private static String formatBytes(long bytes) {
        return bytes + " B"
                + " (" + formatKiB(bytes) + ", " + formatMiB(bytes) + ")";
    }

    private static String formatSignedBytes(long bytes) {
        String sign = bytes > 0 ? "+" : "";
        return sign + formatBytes(bytes);
    }

    private static void ensureMemoryCheckpointHeader(String section) {
        String key = section + "\u0000__memory_checkpoint_header__";
        if (lineRefs.containsKey(key)) {
            return;
        }
        List<String> lines = sections.computeIfAbsent(section, k -> new ArrayList<>());
        lines.add("段階                                           現在ヒープ ピークヒープ 開始時からの増減 直前からの増減");
        lines.add("------------------------------------------------------------------------------------------------");
        lineRefs.put(key, new LineRef(section, lines.size() - 2));
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.2fMB", bytes / 1024.0 / 1024.0);
    }

    private static String formatSignedMiB(long bytes) {
        String sign = bytes > 0 ? "+" : "";
        return sign + formatMiB(bytes);
    }

    private static String formatKiB(long bytes) {
        return String.format(Locale.ROOT, "%.2fKB", bytes / 1024.0);
    }

    private static String padRight(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        StringBuilder builder = new StringBuilder(text);
        while (builder.length() < width) {
            builder.append(' ');
        }
        return builder.toString();
    }

    private static String padLeft(String value, int width) {
        String text = value == null ? "" : value;
        if (text.length() >= width) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        while (builder.length() + text.length() < width) {
            builder.append(' ');
        }
        builder.append(text);
        return builder.toString();
    }

    private static String timerKey(String section, String label) {
        return (section == null ? "" : section) + "\u0000" + (label == null ? "" : label);
    }

    private static void flushActiveTimers() {
        if (activeTimers.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ActiveTimer timer : activeTimers.values()) {
            putOrReplaceTime(timer.section, timer.label,
                    now - timer.startMillis,
                    " (失敗時点まで)");
        }
        activeTimers.clear();
    }

    private static void recordComparisonSummary() {
        final String comparisonSection = "比較用時間集計";
        if (sections.containsKey(comparisonSection)) {
            return;
        }

        Long totalTime = firstRecordedTime(
                timeKey("共通 / HPWindow", "合成ボタンを押してから合成完了までの時間"),
                timeKey("一時 runner", "合成全体実行時間"));

        long commonPreprocessTime = sumRecordedTimes(
                timeKey("UpdatingControllersDefinition", "Old Controller 合成時間"),
                timeKey("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                timeKey("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"));

        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        long methodSpecificTime = methodSpecificTime();
        long methodCoreTime = methodCoreTime();
        boolean hasControllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間");
        Long controllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間")
                ? optionalTime("共通 / HPWindow", "コントローラ合成時間")
                : null;
        long adjustedObservedTime = totalTime == null
                ? -1
                : Math.max(0, totalTime - commonPreprocessTime - stateSpaceCountOverheadMillis - drawTime);
        long unclassifiedTime = totalTime == null
                ? -1
                : Math.max(0, totalTime - commonPreprocessTime - methodSpecificTime
                        - stateSpaceCountOverheadMillis - drawTime);

        if (totalTime == null) {
            addMetricValue(comparisonSection,
                    "実測総時間",
                    "未記録",
                    "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        } else {
            addMetric(comparisonSection,
                    "実測総時間",
                    totalTime,
                    "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        }
        addMetric(comparisonSection,
                "除外する共通前処理時間",
                commonPreprocessTime,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        addMetric(comparisonSection,
                "評価用カウント時間（状態数・遷移数）",
                stateSpaceCountOverheadMillis,
                "状態数・遷移数を数えるための CountTime の合計。合成本来の処理ではない評価用オーバーヘッド。");
        addMetric(comparisonSection,
                "入力規模集計時間（評価用・参考）",
                optionalTime("UpdatingControllersDefinition", "入力規模集計時間"),
                "入力規模セクションを作る時間。状態数・遷移数 CountTime と重なる可能性があるため、差し引き式には入れない参考値。");
        if (hasRecordedTime("共通 / HPWindow", "コントローラ描画時間")) {
            addMetric(comparisonSection,
                    "GUI描画時間（比較から除外候補）",
                    drawTime,
                    "HPWindow の「コントローラ描画時間」。アルゴリズム比較からは除外する候補。");
        }
        if (totalTime != null) {
            addMetric(comparisonSection,
                    "共通前処理などを除いた実測時間",
                    adjustedObservedTime,
                    "実測総時間 - 除外する共通前処理時間 - 評価用カウント時間 - GUI描画時間。");
            addMetric(comparisonSection,
                    "実測総時間ベースの未分類時間（参考）",
                    unclassifiedTime,
                    "実測総時間 - 除外する共通前処理時間 - 手法固有として個別計測できた時間 - 評価用カウント時間 - GUI描画時間。"
                            + " GUI 周辺なども含むため参考値。");
        }
        addMetric(comparisonSection,
                "手法固有として個別計測できた時間",
                methodSpecificTime,
                methodSpecificFormula());
        if (hasControllerSynthesisTime) {
            long controllerSynthesisWithoutCommon = controllerSynthesisTime - commonPreprocessTime;
            long unclassifiedNonCommonTime = controllerSynthesisWithoutCommon - methodSpecificTime;
            addMetric(comparisonSection,
                    "共通処理を除いたコントローラ合成時間",
                    controllerSynthesisWithoutCommon,
                    "コントローラ合成時間 - 除外する共通前処理時間。");
            addMetric(comparisonSection,
                    "未分類の非共通時間",
                    unclassifiedNonCommonTime,
                    "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。");
        } else {
            addMetricValue(comparisonSection,
                    "共通処理を除いたコントローラ合成時間",
                    "未記録",
                    "コントローラ合成時間 - 除外する共通前処理時間。");
            addMetricValue(comparisonSection,
                    "未分類の非共通時間",
                    "未記録",
                    "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。");
        }
        addMetric(comparisonSection,
                "主比較対象の中核合成時間",
                methodCoreTime,
                methodCoreFormula());
        recordModeSpecificComparisonDetails(comparisonSection);
    }

    private static long methodSpecificTime() {
        if ("OTF-DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "generateDUC 全体時間"));
        }

        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                    timeKey("UpdatingControllerSynthesizer", "generateController の全体実行時間"),
                    timeKey("TransitionSystemDispatcher", "removeOldTransitions 実行時間"));
        }

        return 0;
    }

    private static long methodCoreTime() {
        if ("OTF-DUC".equals(mode)) {
            return optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間");
        }

        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "solveControlProblem 全体時間"));
        }

        return 0;
    }

    private static void recordModeSpecificComparisonDetails(String comparisonSection) {
        if ("OTF-DUC".equals(mode)) {
            long otfPreparation = sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "boxList 準備時間"),
                    timeKey("generateDUC (OTF-DUC)", "MarkingLTS 生成時間"),
                    timeKey("generateDUC (OTF-DUC)", "New Controller の接続先の事前計算"),
                    timeKey("generateDUC (OTF-DUC)", "New Safety と Fluent の対応表の変換作業時間"));
            addMetric(comparisonSection,
                    "OTF-DUC 固有準備時間",
                    otfPreparation,
                    "New Controller 合成時間 + Safety の tester 変換全体時間 + New Safety から Fluent を抽出する時間"
                            + " + boxList 準備時間 + MarkingLTS 生成時間 + New Controller の接続先の事前計算"
                            + " + New Safety と Fluent の対応表の変換作業時間。");
            addMetric(comparisonSection,
                    "OTF-DUC のDCS時間（中核）",
                    optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間"),
                    "generateDUC (OTF-DUC) の「DCS で Update Controller を合成する時間」。");
        } else if ("Traditional DUC".equals(mode)) {
            long traditionalPreparation = sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"));
            addMetric(comparisonSection,
                    "Traditional DUC 固有準備時間",
                    traditionalPreparation,
                    "Traditional DUC grGoal 生成時間 + Traditional DUC safetyGoal 生成時間"
                            + " + Traditional DUC Mapping Environment Component 並列合成時間。");
            addMetric(comparisonSection,
                    "Traditional DUC のE_u構築+GR1合成時間（中核）",
                    methodCoreTime(),
                    "Traditional DUC E_u 構築時間 + solveControlProblem 全体時間。");
            addMetric(comparisonSection,
                    "Traditional DUC の.old後処理時間",
                    optionalTime("TransitionSystemDispatcher", "removeOldTransitions 実行時間"),
                    "TransitionSystemDispatcher の removeOldTransitions 実行時間。OTF-DUC では実行しない。");
        }
    }

    private static String methodSpecificFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "New Controller 合成時間 + Safety の tester 変換全体時間"
                    + " + New Safety から Fluent を抽出する時間 + generateDUC 全体時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC grGoal 生成時間 + Traditional DUC safetyGoal 生成時間"
                    + " + Traditional DUC Mapping Environment Component 並列合成時間"
                    + " + generateController の全体実行時間 + removeOldTransitions 実行時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static String methodCoreFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "DCS で Update Controller を合成する時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC E_u 構築時間 + solveControlProblem 全体時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static void addMetric(String section, String label, long millis, String formula) {
        recordDataMetric(section, label, Long.toString(millis), "ms");
        addMetricValue(section, label, millis + " ms", formula);
    }

    private static void addMetricValue(String section, String label, String value, String formula) {
        add(section, label + " : " + value);
        add(section, "  算出: " + formula);
    }

    private static void printEvaluationSummary(LTSOutput output) {
        Long totalTime = firstRecordedTime(
                timeKey("共通 / HPWindow", "合成ボタンを押してから合成完了までの時間"),
                timeKey("一時 runner", "合成全体実行時間"));
        long parseTime = optionalTime("共通 / HPWindow", "構文解析時間");
        long compileIfChangeTotalTime = optionalTime("共通 / HPWindow", "compileIfChange 全体時間（参考）");
        long problemPreparationTime = optionalTime("共通 / HPWindow", "合成問題準備時間");
        long updateControllerGenerationTime = optionalTime("共通 / HPWindow", "update controller 生成時間");
        Long controllerSynthesisTime = hasRecordedTime("共通 / HPWindow", "コントローラ合成時間")
                ? optionalTime("共通 / HPWindow", "コントローラ合成時間")
                : null;
        long drawTime = optionalTime("共通 / HPWindow", "コントローラ描画時間");
        long commonTotal = commonPreparationTime();
        long methodSpecificTotal = methodSpecificTime();
        long methodPreparationTotal = methodPreparationTime();
        long actualSynthesisTime = actualSynthesisTime();
        long methodOtherTime = methodSpecificTotal - methodPreparationTotal - actualSynthesisTime;
        long totalPreparationTime = commonTotal + methodPreparationTotal;
        Long totalWithoutCountAndDraw = totalTime == null
                ? null
                : totalTime - stateSpaceCountOverheadMillis - drawTime;
        Long controllerSynthesisWithoutCommon = controllerSynthesisTime == null
                ? null
                : controllerSynthesisTime - commonTotal;
        Long unclassifiedNonCommonTime = controllerSynthesisWithoutCommon == null
                ? null
                : controllerSynthesisWithoutCommon - methodSpecificTotal;

        output.outln("================ EVALUATION SUMMARY ================");
        printSummarySectionHeader(output, "全体");
        printSummaryValue(output, "手法", mode, "");
        printSummaryValue(output, "結果", resultStatus.toString(), "");
        if (isFailureStatus(resultStatus) && !failureMessage.isEmpty()) {
            printSummaryValue(output, "失敗理由", failureMessage, "");
        }
        printSummaryMillis(output, "合成の全体時間", totalTime,
                "HPWindow の「合成ボタンを押してから合成完了までの時間」または runner の「合成全体実行時間」。");
        printSummaryMillis(output, "カウントによるオーバーヘッド", stateSpaceCountOverheadMillis,
                "状態数・遷移数を数える CountTime の合計。");
        printSummaryMillis(output, "コントローラ描画時間", drawTime,
                "HPWindow の「コントローラ描画時間」。");
        printSummaryMillis(output, "描画とカウントを除いた実測時間", totalWithoutCountAndDraw,
                "合成の全体時間 - カウントによるオーバーヘッド - コントローラ描画時間。");
        printSummaryMillis(output, "構文解析時間", parseTime,
                "HPWindow.docompile() 内の comp.compile() 実行時間。FSP/LTL/update controller 定義の解析と定義登録。");
        printSummaryMillis(output, "compileIfChange 全体時間（参考）", compileIfChangeTotalTime,
                "HPWindow の compileIfChange() 全体。構文解析時間 + 合成問題準備時間を含む参考値。");
        printSummaryMillis(output, "合成問題準備時間", problemPreparationTime,
                "HPWindow.docompile() 内の comp.continueCompilation(target) 実行時間。UpdatingControllersDefinition.compose などを含む。");
        printSummaryMillis(output, "update controller 生成時間", updateControllerGenerationTime,
                "HPWindow の TransitionSystemDispatcher.applyComposition(...) 実行時間。");
        printSummaryMillis(output, "コントローラ合成時間", controllerSynthesisTime,
                "合成問題準備時間 + update controller 生成時間。");
        printSummaryMillis(output, "共通準備時間", commonTotal,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        printSummaryMillis(output, "共通処理を除いたコントローラ合成時間", controllerSynthesisWithoutCommon,
                "コントローラ合成時間 - 共通準備時間。"
                        + " 手法固有として個別計測できた時間と、未分類の非共通時間を含む。");
        printSummaryMillis(output, "手法固有準備時間", methodPreparationTotal,
                methodPreparationFormula());
        printSummaryMillis(output, "合成用モデル準備時間", totalPreparationTime,
                "共通準備時間 + 手法固有準備時間。");
        printSummaryMillis(output, "実際の中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "手法固有内のその他時間", methodOtherTime,
                "手法固有として個別計測できた時間 - 手法固有準備時間 - 実際の中核合成時間。"
                        + " 主に出力構築・型変換・後処理など。");
        printSummaryMillis(output, "手法固有として個別計測できた時間", methodSpecificTotal,
                "手法固有準備時間 + 実際の中核合成時間 + 手法固有内のその他時間。");
        printSummaryMillis(output, "未分類の非共通時間", unclassifiedNonCommonTime,
                "共通処理を除いたコントローラ合成時間 - 手法固有として個別計測できた時間。"
                        + " 0 でない場合、共通ではないが個別計測項目に分類していない処理が残っている。");
        printSummaryDataMetric(output, "出力 update controller 状態数", "output_update_controller_states", "");
        printSummaryDataMetric(output, "出力 update controller 遷移数", "output_update_controller_transitions", "");
        printSummaryDataMetric(output, "全体ピークメモリ", "controller_synthesis_peak_memory",
                "共通 / HPWindow の「コントローラ合成全体のピークメモリ」。");
        printSummaryDataMetric(output, "増加メモリ", "controller_synthesis_memory_increase",
                "コントローラ合成全体のピークメモリ - コントローラ合成のベースラインメモリ。");

        printCommonSummary(output, commonTotal);
        if ("OTF-DUC".equals(mode)) {
            printOtfSummary(output, methodSpecificTotal, methodPreparationTotal, actualSynthesisTime, methodOtherTime);
        } else if ("Traditional DUC".equals(mode)) {
            printTraditionalSummary(output, methodSpecificTotal, methodPreparationTotal, actualSynthesisTime, methodOtherTime);
        }
        printOutputSummary(output);
        printMemorySummary(output);
        output.outln("====================================================");
        output.outln("");
    }

    private static void printCommonSummary(LTSOutput output, long commonTotal) {
        printSummarySectionHeader(output, "共通");
        printSummaryMillis(output, "共通準備時間（共通合計）", commonTotal,
                "Old Controller 合成時間 + Goal 定義と controllable action 集合生成時間 + Mapping Environment Component 合成時間。");
        printSummaryMillis(output, "Old Controller 合成時間",
                optionalTime("UpdatingControllersDefinition", "Old Controller 合成時間"),
                "");
        printSummaryMillis(output, "Goal 定義と controllable action 集合生成時間",
                optionalTime("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                "");
        printSummaryMillis(output, "Mapping Environment Component 合成時間",
                optionalTime("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"),
                "");
        printSummaryDataMetric(output, "Old Controller 状態数", "old_controller_states", "");
        printSummaryDataMetric(output, "Old Controller 遷移数", "old_controller_transitions", "");
        printSummaryDataMetric(output, "mapping component 数", metricKey("入力規模", "mapping component 数"), "");
        printSummaryDataMetric(output, "old safety 数", metricKey("入力規模", "old safety 数"), "");
        printSummaryDataMetric(output, "new safety 数", metricKey("入力規模", "new safety 数"), "");
        printSummaryDataMetric(output, "transition requirement 数", metricKey("入力規模", "transition requirement 数"), "");
        printSummaryDataMetric(output, "controllable action 数", metricKey("入力規模", "controllable action 数"), "");
    }

    private static void printOtfSummary(
            LTSOutput output,
            long methodSpecificTotal,
            long methodPreparationTotal,
            long actualSynthesisTime,
            long methodOtherTime) {
        printSummarySectionHeader(output, "OTF-DUC固有");
        printSummaryMillis(output, "OTF-DUC 固有準備時間", methodPreparationTotal,
                "New Controller 合成時間 + Safety の tester 変換全体時間 + New Safety から Fluent を抽出する時間 + boxList 準備時間。"
                        + " MarkingLTS 生成時間などの boxList 内訳は二重計上しない。");
        printSummaryMillis(output, "OTF-DUC 中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "OTF-DUC 固有内のその他時間", methodOtherTime,
                "OTF-DUC 固有として個別計測できた時間 - OTF-DUC 固有準備時間 - OTF-DUC 中核合成時間。"
                        + " 主に generateDUC 内の型変換・出力構築など。");
        printSummaryMillis(output, "OTF-DUC 固有として個別計測できた時間", methodSpecificTotal,
                "OTF-DUC 固有準備時間 + OTF-DUC 中核合成時間 + OTF-DUC 固有内のその他時間。");
        printSummaryMillis(output, "New Controller 合成時間",
                optionalTime("UpdatingControllersDefinition", "New Controller 合成時間"),
                "");
        printSummaryDataMetric(output, "New Controller 状態数", "new_controller_states", "");
        printSummaryDataMetric(output, "New Controller 遷移数", "new_controller_transitions", "");
        printSummaryMillis(output, "Safety の tester 変換全体時間",
                optionalTime("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                "");
        printSummaryMillis(output, "New Safety から Fluent を抽出する時間",
                optionalTime("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                "");
        printSummaryMillis(output, "boxList 準備時間",
                optionalTime("generateDUC (OTF-DUC)", "boxList 準備時間"),
                "MarkingLTS、旧コントローラ、MapEnv、safety、対応表などを DCS に渡す形へ準備する時間。");
        printSummaryMillis(output, "MarkingLTS 生成時間（boxList 内訳）",
                optionalTime("generateDUC (OTF-DUC)", "MarkingLTS 生成時間"),
                "boxList 準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "New Controller 接続先事前計算（boxList 内訳）",
                optionalTime("generateDUC (OTF-DUC)", "New Controller の接続先の事前計算"),
                "boxList 準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "New Safety と Fluent 対応表変換（boxList 内訳）",
                optionalTime("generateDUC (OTF-DUC)", "New Safety と Fluent の対応表の変換作業時間"),
                "boxList 準備時間に含まれるため、OTF-DUC 固有準備合計には個別加算しない。");
        printSummaryMillis(output, "DCS 探索時間",
                optionalTime("DCS (OTF-DUC)", "DCS で探索した時間"),
                "");
        printSummaryDataMetric(output, "DCS 探索最大状態数", "otf_dcs_peak_states", "");
        printSummaryDataMetric(output, "DCS 探索最大遷移数", "otf_dcs_peak_transitions", "");
        printSummaryDataMetric(output, "expandDUC 呼び出し回数", "otf_expand_duc_calls", "");
        printSummaryMillis(output, "buildDirectorDUC 実行時間",
                optionalTime("DCS (OTF-DUC)", "buildDirectorDUC 実行時間"),
                "");
    }

    private static void printTraditionalSummary(
            LTSOutput output,
            long methodSpecificTotal,
            long methodPreparationTotal,
            long actualSynthesisTime,
            long methodOtherTime) {
        printSummarySectionHeader(output, "従来DUC固有");
        printSummaryMillis(output, "従来DUC 固有準備時間", methodPreparationTotal,
                "Traditional DUC grGoal 生成時間 + Traditional DUC safetyGoal 生成時間"
                        + " + Traditional DUC Mapping Environment Component 並列合成時間 + E_u 構築時間"
                        + " + Old/New Safety から Fluent を抽出する時間 + metaEnv 構築時間"
                        + " + safetyEnv 構築時間 + safetyEnv から CompactState への変換時間。");
        printSummaryMillis(output, "従来DUC 中核合成時間", actualSynthesisTime,
                actualSynthesisFormula());
        printSummaryMillis(output, "従来DUC 固有内のその他時間", methodOtherTime,
                "従来DUC 固有として個別計測できた時間 - 従来DUC 固有準備時間 - 従来DUC 中核合成時間。"
                        + " 主に controller MTS/CompactState 構築や .old 後処理など。");
        printSummaryMillis(output, "従来DUC 固有として個別計測できた時間", methodSpecificTotal,
                "従来DUC 固有準備時間 + 従来DUC 中核合成時間 + 従来DUC 固有内のその他時間。");
        printSummaryMillis(output, "Traditional DUC grGoal 生成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                "");
        printSummaryMillis(output, "Traditional DUC safetyGoal 生成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                "");
        printSummaryMillis(output, "Traditional DUC Mapping Environment Component 並列合成時間",
                optionalTime("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                "");
        printSummaryMillis(output, "E_u 構築時間",
                optionalTime("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                "");
        printSummaryMillis(output, "metaEnv 構築時間",
                optionalTime("solveControlProblem (Traditional DUC)", "Fluent とベース環境を並列合成した metaEnv 構築時間"),
                "");
        printSummaryMillis(output, "safetyEnv 構築時間",
                optionalTime("solveControlProblem (Traditional DUC)", "metaEnv からエラーを枝刈りして safetyEnv を構築する時間"),
                "");
        printSummaryMillis(output, "GR1 で解く時間",
                optionalTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間"),
                "");
        printSummaryMillis(output, "Winning region 計算時間",
                optionalTime("Traditional DUC GR1 時間内訳", "Winning region 計算時間"),
                "");
        printSummaryMillis(output, "Strategy 構築時間",
                optionalTime("Traditional DUC GR1 時間内訳", "Strategy 構築時間"),
                "");
        printSummaryMillis(output, "removeOldTransitions 実行時間",
                optionalTime("TransitionSystemDispatcher", "removeOldTransitions 実行時間"),
                "");
        printSummaryDataMetric(output, "E_u 状態数", "traditional_eu_states", "");
        printSummaryDataMetric(output, "E_u 遷移数", "traditional_eu_transitions", "");
        printSummaryDataMetric(output, "Meta 状態数", "traditional_meta_states", "");
        printSummaryDataMetric(output, "Meta 遷移数", "traditional_meta_transitions", "");
        printSummaryDataMetric(output, "Pruned 状態数", "traditional_pruned_states", "");
        printSummaryDataMetric(output, "Pruned 遷移数", "traditional_pruned_transitions", "");
        printSummaryDataMetric(output, "Final 状態数", "traditional_final_states", "");
        printSummaryDataMetric(output, "Final 遷移数", "traditional_final_transitions", "");
    }

    private static void printOutputSummary(LTSOutput output) {
        printSummarySectionHeader(output, "出力");
        printSummaryDataMetric(output, "update controller 状態数", "output_update_controller_states", "");
        printSummaryDataMetric(output, "update controller 遷移数", "output_update_controller_transitions", "");
        printSummaryDataMetric(output, "出力状態数・遷移数 CountTime", "output_update_controller_count_time", "");
        printSummaryDataMetric(output, "beginUpdate が出ている状態数", "begin_update_outgoing_states", "");
        printSummaryDataMetric(output, "旧コントローラ状態数", "old_controller_states_for_begin_update",
                "beginUpdate が出るべき基準状態数。");
        if ("OTF-DUC".equals(mode)) {
            printSummaryDataMetric(output, "探索上の旧コントローラ相当状態数（マージ前）",
                    "otf_pre_update_raw_states",
                    "OTF-DUC の探索で markingState=0 として現れた状態数。new safety fluent などで旧コントローラ状態が分割される。");
            printSummaryDataMetric(output, "出力上の旧コントローラ相当状態数（マージ後）",
                    "otf_pre_update_output_states",
                    "出力時マージ後に update controller 側へ残る旧コントローラ相当状態数。");
            printSummaryDataMetric(output, "OTF-DUCにより増えた旧コントローラ相当状態数",
                    "otf_pre_update_state_overhead",
                    "出力上の旧コントローラ相当状態数（マージ後） - 旧コントローラ状態数。マージできなかった分を OTF-DUC の状態数オーバーヘッドとして数える。");
        }
        printSummaryDataMetric(output, "beginUpdate coverage CountTime", "begin_update_coverage_count_time", "");
    }

    private static void printMemorySummary(LTSOutput output) {
        printSummarySectionHeader(output, "メモリ");
        printSummaryDataMetric(output, "ベースラインメモリ", "controller_synthesis_base_memory", "");
        printSummaryDataMetric(output, "全体ピークメモリ", "controller_synthesis_peak_memory", "");
        printSummaryDataMetric(output, "増加メモリ", "controller_synthesis_memory_increase", "");
        printSummaryDataMetric(output, "合成終了時の現在ヒープ", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_current_heap", "");
        printSummaryDataMetric(output, "合成終了時のピークヒープ", metricKey("メモリ使用量チェックポイント", "合成終了時") + "_peak_heap", "");
    }

    private static long commonPreparationTime() {
        return sumRecordedTimes(
                timeKey("UpdatingControllersDefinition", "Old Controller 合成時間"),
                timeKey("UpdatingControllersDefinition", "Goal 定義と controllable action 集合生成時間"),
                timeKey("UpdatingControllersDefinition", "Mapping Environment Component 合成時間"));
    }

    private static long methodPreparationTime() {
        if ("OTF-DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "New Controller 合成時間"),
                    timeKey("UpdatingControllersDefinition", "Safety の tester 変換全体時間"),
                    timeKey("UpdatingControllersDefinition", "New Safety から Fluent を抽出する時間"),
                    timeKey("generateDUC (OTF-DUC)", "boxList 準備時間"));
        }
        if ("Traditional DUC".equals(mode)) {
            return sumRecordedTimes(
                    timeKey("UpdatingControllersDefinition", "Traditional DUC grGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC safetyGoal 生成時間"),
                    timeKey("UpdatingControllersDefinition", "Traditional DUC Mapping Environment Component 並列合成時間"),
                    timeKey("UpdatingControllerSynthesizer", "Traditional DUC E_u 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "Old Safety と New Safety から Fluent を抽出する時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "Fluent とベース環境を並列合成した metaEnv 構築時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "metaEnv からエラーを枝刈りして safetyEnv を構築する時間"),
                    timeKey("solveControlProblem (Traditional DUC)", "safetyEnv から CompactState への変換時間"));
        }
        return 0;
    }

    private static long actualSynthesisTime() {
        if ("OTF-DUC".equals(mode)) {
            return optionalTime("generateDUC (OTF-DUC)", "DCS で Update Controller を合成する時間");
        }
        if ("Traditional DUC".equals(mode)) {
            return optionalTime("solveControlProblem (Traditional DUC)", "safetyEnv を GR1 で解く時間");
        }
        return 0;
    }

    private static String actualSynthesisFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "generateDUC (OTF-DUC) の「DCS で Update Controller を合成する時間」。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "solveControlProblem (Traditional DUC) の「safetyEnv を GR1 で解く時間」。";
        }
        return "手法が未記録のため 0。";
    }

    private static String methodPreparationFormula() {
        if ("OTF-DUC".equals(mode)) {
            return "New Controller 合成時間 + Safety の tester 変換全体時間"
                    + " + New Safety から Fluent を抽出する時間 + boxList 準備時間。";
        }
        if ("Traditional DUC".equals(mode)) {
            return "Traditional DUC grGoal 生成時間 + Traditional DUC safetyGoal 生成時間"
                    + " + Traditional DUC Mapping Environment Component 並列合成時間"
                    + " + E_u 構築時間 + Old/New Safety から Fluent を抽出する時間"
                    + " + metaEnv 構築時間 + safetyEnv 構築時間 + safetyEnv から CompactState への変換時間。";
        }
        return "手法が未記録のため 0。";
    }

    private static void printSummarySectionHeader(LTSOutput output, String title) {
        output.outln("");
        output.outln("[" + title + "]");
    }

    private static void printSummaryValue(LTSOutput output, String label, String value, String formula) {
        output.outln(label + " : " + (value == null || value.isEmpty() ? "未記録" : value));
        if (formula != null && !formula.isEmpty()) {
            output.outln("  算出: " + formula);
        }
    }

    private static void printSummaryMillis(LTSOutput output, String label, long millis, String formula) {
        printSummaryMillis(output, label, Long.valueOf(millis), formula);
    }

    private static void printSummaryMillis(LTSOutput output, String label, Long millis, String formula) {
        String value = millis == null ? "未記録" : millis + " ms";
        printSummaryValue(output, label, value, formula);
    }

    private static void printSummaryDataMetric(LTSOutput output, String label, String metricKey, String formula) {
        DataMetric metric = dataMetrics.get(metricKey);
        if (metric == null) {
            printSummaryValue(output, label, "未記録", formula);
            return;
        }
        String value = metric.value == null || metric.value.isEmpty() ? "未記録" : metric.value;
        if (metric.unit != null && !metric.unit.isEmpty() && !"text".equals(metric.unit)) {
            value = value + " " + metric.unit;
        }
        printSummaryValue(output, label, value, formula);
    }

    private static void printDataCsv(LTSOutput output) {
        output.outln("================ EVALUATION DATA CSV ================");
        output.outln("mode,result,failure_reason,section,metric_key,metric_label,value,unit");
        outputDataRow(output, new DataMetric("mode", "Run", "mode", mode, "text"));
        outputDataRow(output, new DataMetric("result", "Run", "result", resultStatus.toString(), "text"));
        outputDataRow(output, new DataMetric("failure_reason", "Run", "failure reason", failureMessage, "text"));
        for (DataMetric metric : dataMetrics.values()) {
            outputDataRow(output, metric);
        }
        output.outln("=====================================================");
        output.outln("");
    }

    private static void outputDataRow(LTSOutput output, DataMetric metric) {
        output.outln(csv(mode)
                + "," + csv(resultStatus.toString())
                + "," + csv(failureMessage)
                + "," + csv(metric.section)
                + "," + csv(metric.key)
                + "," + csv(metric.label)
                + "," + csv(metric.value)
                + "," + csv(metric.unit));
    }

    private static void recordDataMetric(String section, String label, String value, String unit) {
        recordDataMetric(metricKey(section, label), section, label, value, unit);
    }

    private static void recordDataMetric(String key, String section, String label, String value, String unit) {
        String normalizedKey = key == null || key.isEmpty() ? metricKey(section, label) : key;
        dataMetrics.put(normalizedKey, new DataMetric(
                normalizedKey,
                section == null ? "" : section,
                label == null ? "" : label,
                value == null ? "" : value,
                unit == null ? "" : unit));
    }

    private static String metricKey(String section, String label) {
        String knownKey = knownMetricKey(section, label);
        if (!knownKey.isEmpty()) {
            return knownKey;
        }
        return "auto_" + Integer.toHexString(timerKey(section, label).hashCode());
    }

    private static String knownMetricKey(String section, String label) {
        if ("共通 / HPWindow".equals(section)) {
            if ("合成ボタンを押してから合成完了までの時間".equals(label)) {
                return "total_time";
            }
            if ("構文解析時間".equals(label)) {
                return "parse_time";
            }
            if ("compileIfChange 全体時間（参考）".equals(label)) {
                return "compile_if_change_total_time";
            }
            if ("合成問題準備時間".equals(label)) {
                return "synthesis_problem_preparation_time";
            }
            if ("update controller 生成時間".equals(label)) {
                return "update_controller_generation_time";
            }
            if ("コントローラ合成時間".equals(label)) {
                return "controller_synthesis_related_time";
            }
            if ("コントローラ描画時間".equals(label)) {
                return "controller_draw_time";
            }
            if ("コントローラ合成のベースラインメモリ".equals(label)) {
                return "controller_synthesis_base_memory";
            }
            if ("コントローラ合成全体のピークメモリ".equals(label)) {
                return "controller_synthesis_peak_memory";
            }
            if ("コントローラ合成により増えたメモリ".equals(label)) {
                return "controller_synthesis_memory_increase";
            }
        }
        if ("UpdatingControllersDefinition".equals(section)) {
            if ("compose の全体実行時間".equals(label)) {
                return "definition_prepare_total_time";
            }
            if ("Old Controller 合成時間".equals(label)) {
                return "old_controller_synthesis_time";
            }
            if ("Goal 定義と controllable action 集合生成時間".equals(label)) {
                return "goal_and_controllable_set_time";
            }
            if ("Mapping Environment Component 合成時間".equals(label)) {
                return "mapping_component_generation_time";
            }
            if ("New Controller 合成時間".equals(label)) {
                return "new_controller_synthesis_time";
            }
            if ("Safety の tester 変換全体時間".equals(label)) {
                return "safety_tester_conversion_total_time";
            }
            if ("New Safety から Fluent を抽出する時間".equals(label)) {
                return "new_safety_fluent_extraction_time";
            }
            if ("Traditional DUC grGoal 生成時間".equals(label)) {
                return "traditional_gr_goal_generation_time";
            }
            if ("Traditional DUC safetyGoal 生成時間".equals(label)) {
                return "traditional_safety_goal_generation_time";
            }
            if ("Traditional DUC Mapping Environment Component 並列合成時間".equals(label)) {
                return "traditional_mapping_parallel_composition_time";
            }
            if ("入力規模集計時間".equals(label)) {
                return "input_scale_summary_time";
            }
        }
        if ("UpdatingControllerSynthesizer".equals(section)) {
            if ("generateController の全体実行時間".equals(label)) {
                return "generate_controller_total_time";
            }
            if ("Traditional solveControlProblem / OTF generateDUC 実行時間".equals(label)) {
                return "method_main_execution_time";
            }
            if ("Traditional DUC E_u 構築時間".equals(label)) {
                return "traditional_eu_construction_time";
            }
        }
        if ("generateDUC (OTF-DUC)".equals(section)) {
            if ("generateDUC 全体時間".equals(label)) {
                return "otf_generate_duc_total_time";
            }
            if ("boxList 準備時間".equals(label)) {
                return "otf_box_list_preparation_time";
            }
            if ("MarkingLTS 生成時間".equals(label)) {
                return "otf_marking_lts_generation_time";
            }
            if ("New Controller の接続先の事前計算".equals(label)) {
                return "otf_new_controller_connection_precompute_time";
            }
            if ("New Safety と Fluent の対応表の変換作業時間".equals(label)) {
                return "otf_new_safety_fluent_map_conversion_time";
            }
            if ("DCS で Update Controller を合成する時間".equals(label)) {
                return "otf_dcs_update_controller_synthesis_time";
            }
            if ("DCS 実行時間".equals(label)) {
                return "otf_dcs_execution_time";
            }
        }
        if ("DCS (OTF-DUC)".equals(section)) {
            if ("synthesizeDUC 実行時間".equals(label)) {
                return "otf_synthesize_duc_time";
            }
            if ("DCS で探索した時間".equals(label)) {
                return "otf_dcs_search_time";
            }
            if ("expandDUC 呼び出し回数".equals(label)) {
                return "otf_expand_duc_calls";
            }
            if ("buildDirectorDUC 実行時間".equals(label)) {
                return "otf_build_director_duc_time";
            }
            if ("NC 移設時間".equals(label)) {
                return "otf_new_controller_transfer_time";
            }
            if ("NC 接続時間".equals(label)) {
                return "otf_new_controller_stitching_time";
            }
            if ("NC 移設時間 + NC 接続時間".equals(label)) {
                return "otf_new_controller_transfer_and_stitching_time";
            }
        }
        if ("solveControlProblem (Traditional DUC)".equals(section)) {
            if ("solveControlProblem 全体時間".equals(label)) {
                return "traditional_solve_control_problem_total_time";
            }
            if ("Fluent とベース環境を並列合成した metaEnv 構築時間".equals(label)) {
                return "traditional_meta_environment_construction_time";
            }
            if ("metaEnv からエラーを枝刈りして safetyEnv を構築する時間".equals(label)) {
                return "traditional_safety_environment_pruning_time";
            }
            if ("safetyEnv を GR1 で解く時間".equals(label)) {
                return "traditional_gr1_solving_time";
            }
        }
        if ("TransitionSystemDispatcher".equals(section)
                && "removeOldTransitions 実行時間".equals(label)) {
            return "traditional_remove_old_transitions_time";
        }
        if ("比較用時間集計".equals(section)) {
            if ("実測総時間".equals(label)) {
                return "comparison_observed_total_time";
            }
            if ("除外する共通前処理時間".equals(label)) {
                return "comparison_common_preprocess_time";
            }
            if ("評価用カウント時間（状態数・遷移数）".equals(label)) {
                return "comparison_count_overhead_time";
            }
            if ("共通前処理などを除いた実測時間".equals(label)) {
                return "comparison_observed_time_without_common_preprocess";
            }
            if ("実測総時間ベースの未分類時間（参考）".equals(label)) {
                return "comparison_observed_total_based_unclassified_time";
            }
            if ("共通処理を除いたコントローラ合成時間".equals(label)) {
                return "comparison_controller_synthesis_time_without_common";
            }
            if ("手法固有として個別計測できた時間".equals(label)) {
                return "comparison_method_specific_time";
            }
            if ("未分類の非共通時間".equals(label)) {
                return "comparison_unclassified_non_common_time";
            }
            if ("主比較対象の中核合成時間".equals(label)) {
                return "comparison_core_synthesis_time";
            }
            if ("OTF-DUC 固有準備時間".equals(label)) {
                return "comparison_otf_specific_preparation_time";
            }
            if ("OTF-DUC のDCS時間（中核）".equals(label)) {
                return "comparison_otf_dcs_core_time";
            }
            if ("Traditional DUC 固有準備時間".equals(label)) {
                return "comparison_traditional_specific_preparation_time";
            }
            if ("Traditional DUC のE_u構築+GR1合成時間（中核）".equals(label)) {
                return "comparison_traditional_eu_and_gr1_core_time";
            }
            if ("Traditional DUC の.old後処理時間".equals(label)) {
                return "comparison_traditional_old_action_postprocess_time";
            }
        }
        if ("入力規模 / 事前合成".equals(section) && "Old Controller".equals(label)) {
            return "old_controller";
        }
        if ("入力規模 / 事前合成".equals(section) && "New Controller".equals(label)) {
            return "new_controller";
        }
        if ("Traditional DUC 最大状態数と遷移数".equals(section)) {
            if (label.startsWith("[1. E_u]")) {
                return "traditional_eu";
            }
            if (label.startsWith("[2. Meta]")) {
                return "traditional_meta";
            }
            if (label.startsWith("[3. Pruned]")) {
                return "traditional_pruned";
            }
            if (label.startsWith("[4. Final]")) {
                return "traditional_final";
            }
        }
        if ("DCS (OTF-DUC)".equals(section)
                && "DCS で探索した状態数と遷移数の最大値".equals(label)) {
            return "otf_dcs_peak";
        }
        return "";
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        boolean needsQuote = text.indexOf(',') >= 0
                || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0;
        if (!needsQuote) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static long sumRecordedTimes(String... keys) {
        long total = 0;
        for (String key : keys) {
            Long value = timeMillisByKey.get(key);
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    private static long optionalTime(String section, String label) {
        Long value = timeMillisByKey.get(timeKey(section, label));
        return value == null ? 0 : value;
    }

    private static boolean hasRecordedTime(String section, String label) {
        return timeMillisByKey.containsKey(timeKey(section, label));
    }

    private static Long firstRecordedTime(String... keys) {
        for (String key : keys) {
            Long value = timeMillisByKey.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String timeKey(String section, String label) {
        return timerKey(section, label);
    }

    private static final class ActiveTimer {
        private final String section;
        private final String label;
        private final long startMillis;

        private ActiveTimer(String section, String label, long startMillis) {
            this.section = section;
            this.label = label;
            this.startMillis = startMillis;
        }
    }

    private static final class LineRef {
        private final String section;
        private final int index;

        private LineRef(String section, int index) {
            this.section = section;
            this.index = index;
        }
    }

    private static final class DataMetric {
        private final String key;
        private final String section;
        private final String label;
        private final String value;
        private final String unit;

        private DataMetric(String key, String section, String label, String value, String unit) {
            this.key = key;
            this.section = section;
            this.label = label;
            this.value = value;
            this.unit = unit;
        }
    }
}
