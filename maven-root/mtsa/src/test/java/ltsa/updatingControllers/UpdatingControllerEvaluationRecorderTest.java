package ltsa.updatingControllers;

import ltsa.lts.LTSOutput;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UpdatingControllerEvaluationRecorderTest {

    private static final String EVALUATION_ENABLED_PROPERTY = "mtsa.evaluation.enabled";
    private static final String CSV_FILE_PROPERTY = "mtsa.evaluation.csvFile";
    private static final String STEPWISE_STATE_SPACE_SECTION =
            "Stepwise Delayed DUC 最大状態数と遷移数";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private String previousEvaluationEnabled;
    private String previousCsvFile;

    @Before
    public void enableEvaluationWithTemporaryCsv() throws Exception {
        previousEvaluationEnabled = System.getProperty(EVALUATION_ENABLED_PROPERTY);
        previousCsvFile = System.getProperty(CSV_FILE_PROPERTY);
        System.setProperty(EVALUATION_ENABLED_PROPERTY, "true");
        System.setProperty(
                CSV_FILE_PROPERTY,
                temporaryFolder.newFile("evaluation.csv").getAbsolutePath());
        UpdatingControllerEvaluationRecorder.reset();
    }

    @After
    public void restoreRecorderAndProperties() {
        UpdatingControllerEvaluationRecorder.reset();
        restoreProperty(EVALUATION_ENABLED_PROPERTY, previousEvaluationEnabled);
        restoreProperty(CSV_FILE_PROPERTY, previousCsvFile);
    }

    @Test
    public void repeatedStepwiseStateSpaceLabelPreservesPeakAndItsPairedValue()
            throws Exception {
        UpdatingControllerEvaluationRecorder.setMode("Stepwise Delayed DUC");
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "repeated stage",
                100,
                200,
                0);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "repeated stage",
                50,
                500,
                0);
        UpdatingControllerEvaluationRecorder.recordStateSpace(
                STEPWISE_STATE_SPACE_SECTION,
                "[Stepwise Delayed DUCS] After global DontDoTwice",
                7,
                9,
                3);
        UpdatingControllerEvaluationRecorder.markSuccess();

        RecordingOutput output = new RecordingOutput();
        UpdatingControllerEvaluationRecorder.printSummary(output);

        String summary = output.joined();
        assertTrue(summary, summary.contains("最大状態数時の状態数 : 100 states"));
        assertTrue(summary, summary.contains("最大状態数時の遷移数 : 200 transitions"));
        assertTrue(summary, summary.contains("最大遷移数時の状態数 : 50 states"));
        assertTrue(summary, summary.contains("最大遷移数時の遷移数 : 500 transitions"));

        Map<String, String> metrics = readMetricValues(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals("100", metrics.get("peak_state_space_states"));
        assertEquals("200", metrics.get("peak_state_space_states_stage_transitions"));
        assertEquals("500", metrics.get("peak_state_space_transitions"));
        assertEquals("50", metrics.get("peak_state_space_transitions_stage_states"));
        assertEquals("repeated stage", metrics.get("peak_state_space_states_stage"));
        assertEquals("repeated stage", metrics.get("peak_state_space_transitions_stage"));
        assertEquals("7", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_states"));
        assertEquals("9", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_transitions"));
        assertEquals("3", metrics.get(
                "stepwise_delayed_intermediate_after_global_dont_do_twice_count_time"));

        List<List<String>> rows = readCsvRows(
                new File(System.getProperty(CSV_FILE_PROPERTY)));
        assertEquals(
                Arrays.asList("100", "50"),
                observationValues(rows, "repeated stage / States", "states"));
        assertEquals(
                Arrays.asList("200", "500"),
                observationValues(rows, "repeated stage / Transitions", "transitions"));
        assertEquals(
                Arrays.asList("50"),
                stableBaseValues(rows, "repeated stage / States", "states"));
        assertEquals(
                Arrays.asList("500"),
                stableBaseValues(rows, "repeated stage / Transitions", "transitions"));
    }

    private static Map<String, String> readMetricValues(File csvFile) throws Exception {
        Map<String, String> result = new HashMap<String, String>();
        for (List<String> fields : readCsvRows(csvFile)) {
            if (fields.size() > 6 && !"metric_key".equals(fields.get(4))) {
                result.put(fields.get(4), fields.get(6));
            }
        }
        return result;
    }

    private static List<List<String>> readCsvRows(File csvFile) throws Exception {
        List<List<String>> result = new ArrayList<List<String>>();
        for (String line : Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8)) {
            result.add(parseCsvLine(line));
        }
        return result;
    }

    private static List<String> observationValues(
            List<List<String>> rows,
            String label,
            String unit) {
        List<String> result = new ArrayList<String>();
        for (List<String> fields : rows) {
            if (fields.size() > 7
                    && STEPWISE_STATE_SPACE_SECTION.equals(fields.get(3))
                    && fields.get(4).contains("_observation_")
                    && label.equals(fields.get(5))
                    && unit.equals(fields.get(7))) {
                result.add(fields.get(6));
            }
        }
        return result;
    }

    private static List<String> stableBaseValues(
            List<List<String>> rows,
            String label,
            String unit) {
        List<String> result = new ArrayList<String>();
        for (List<String> fields : rows) {
            if (fields.size() > 7
                    && STEPWISE_STATE_SPACE_SECTION.equals(fields.get(3))
                    && !fields.get(4).contains("_observation_")
                    && label.equals(fields.get(5))
                    && unit.equals(fields.get(7))) {
                result.add(fields.get(6));
            }
        }
        return result;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class RecordingOutput implements LTSOutput {
        private final List<String> lines = new ArrayList<String>();

        public void out(String str) {
            lines.add(str);
        }

        public void outln(String str) {
            lines.add(str + "\n");
        }

        public void clearOutput() {
            lines.clear();
        }

        private String joined() {
            StringBuilder result = new StringBuilder();
            for (String line : lines) {
                result.append(line);
            }
            return result.toString();
        }
    }
}
