package ltsa.updatingControllers.cli;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class BatchExperimentRunnerTimingMetricsTest {

    private static final String RAW_KEY =
            "total_time";
    private static final String PARSE_KEY =
            "parse_time";
    private static final String COMPILATION_KEY =
            "compile_if_change_total_time";
    private static final String PREPARATION_KEY =
            "synthesis_problem_preparation_time";
    private static final String GENERATION_KEY =
            "update_controller_generation_time";
    private static final String RELATED_KEY =
            "controller_synthesis_related_time";
    private static final String OVERHEAD_KEY =
            "total_time_scope_count_overhead_time";
    private static final String ADJUSTED_KEY =
            "total_time_time_without_scope_count_overhead";
    private static final String PRIMARY_KEY =
            "comparison_primary_controller_synthesis_time";

    @Test
    public void acceptsPrimaryTimeEqualToRawMinusScopedCountOverhead()
            throws Exception {
        List<String> errors = validate(timingMetrics("700", "10", "690"));

        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void rejectsMissingPrimaryTimingMetric() throws Exception {
        Map<String, String> metrics = timingMetrics("700", "10", "690");
        metrics.remove(PRIMARY_KEY);

        List<String> errors = validate(metrics);

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, PRIMARY_KEY));
    }

    @Test
    public void rejectsPrimaryTimeThatDoesNotMatchTheFormula()
            throws Exception {
        List<String> errors = validate(timingMetrics("700", "10", "700"));

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, "expected=max(0,700-10)=690"));
    }

    @Test
    public void rejectsAdjustedScopeMetricThatDoesNotMatchTheFormula()
            throws Exception {
        Map<String, String> metrics = timingMetrics("700", "10", "690");
        metrics.put(ADJUSTED_KEY, "700");

        List<String> errors = validate(metrics);

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, ADJUSTED_KEY));
        assertTrue(errors.toString(), contains(errors, "expected=max(0,700-10)=690"));
    }

    @Test
    public void rejectsScopedCountOverheadGreaterThanRawTime()
            throws Exception {
        List<String> errors = validate(timingMetrics("700", "710", "0"));

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors,
                OVERHEAD_KEY + "=710 exceeds " + RAW_KEY + "=700"));
    }

    @Test
    public void rejectsNonnumericPrimaryTimingInput() throws Exception {
        List<String> errors = validate(
                timingMetrics("not-a-number", "10", "690"));

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, RAW_KEY));
    }

    @Test
    public void acceptsRawTimeThatIncludesParsingAndOperationEntryOverhead()
            throws Exception {
        List<String> errors = validate(timingMetrics("701", "10", "691"));

        assertTrue(errors.toString(), errors.isEmpty());
    }

    @Test
    public void rejectsRelatedTimeDifferentFromPreparationPlusGeneration()
            throws Exception {
        Map<String, String> metrics = timingMetrics("701", "10", "691");
        metrics.put(RELATED_KEY, "501");

        List<String> errors = validate(metrics);

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, RELATED_KEY));
        assertTrue(errors.toString(), contains(errors, PREPARATION_KEY));
        assertTrue(errors.toString(), contains(errors, GENERATION_KEY));
    }

    @Test
    public void rejectsRawTimeShorterThanCompilationPlusGeneration()
            throws Exception {
        List<String> errors = validate(timingMetrics("600", "10", "590"));

        assertFalse(errors.toString(), errors.isEmpty());
        assertTrue(errors.toString(), contains(errors, RAW_KEY));
        assertTrue(errors.toString(), contains(errors, COMPILATION_KEY));
        assertTrue(errors.toString(), contains(errors, GENERATION_KEY));
    }

    private static Map<String, String> timingMetrics(
            String raw,
            String overhead,
            String primary) {
        Map<String, String> metrics = new LinkedHashMap<String, String>();
        metrics.put(PARSE_KEY, "50");
        metrics.put(COMPILATION_KEY, "250");
        metrics.put(PREPARATION_KEY, "100");
        metrics.put(GENERATION_KEY, "400");
        metrics.put(RELATED_KEY, "500");
        metrics.put(RAW_KEY, raw);
        metrics.put(OVERHEAD_KEY, overhead);
        metrics.put(ADJUSTED_KEY, primary);
        metrics.put(PRIMARY_KEY, primary);
        return metrics;
    }

    private static List<String> validate(Map<String, String> metrics)
            throws Exception {
        Method method = BatchExperimentRunner.class.getDeclaredMethod(
                "validatePrimaryTimingMetrics",
                List.class,
                String.class,
                Map.class);
        method.setAccessible(true);
        List<String> errors = new ArrayList<String>();
        method.invoke(null, errors, "case: ", metrics);
        return errors;
    }

    private static boolean contains(List<String> errors, String expected) {
        for (String error : errors) {
            if (error != null && error.contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
