package ltsa.differential.nativeintegration;

import MTSSynthesis.controller.gr.StrategyState;
import MTSSynthesis.controller.differential.nativeintegration.NativeMemoryDiagnostics;
import MTSTools.ac.ic.doc.commons.relations.Pair;
import MTSTools.ac.ic.doc.mtstools.model.MTS;
import ltsa.ac.ic.doc.mtstools.util.fsp.AutomataToMTSConverter;
import ltsa.dispatcher.TransitionSystemDispatcher;
import ltsa.lts.CompactState;
import ltsa.lts.CompositeState;
import ltsa.lts.EmptyLTSOuput;
import ltsa.lts.LTSCompiler;
import ltsa.lts.LTSInputString;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static MTSTools.ac.ic.doc.mtstools.model.MTS.TransitionType.REQUIRED;
import static org.junit.Assert.*;

/** Exercises the real FSP parser, ordinary dispatcher, native saved session, and update modes. */
public class NativeDifferentialSynthesisIntegrationTest {
    private static final String MODEL =
            "E=(a->E1 | d->E2 | bad->E2), E1=(b->E), E2=(back->E)+{c}.\n"
            + "SAFE=(a->ACTIVE | b->SAFE | d->SAFE | bad->ERROR), "
            + "ACTIVE=(a->ACTIVE | b->SAFE | d->SAFE | bad->ERROR).\n"
            + "controllerSpec G={controllable={a,back,bad} safety={SAFE}}\n";
    private static final EmptyLTSOuput SILENT = new EmptyLTSOuput();

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void ordinaryPreparationSurvivesNativeSnapshotRoundTrip() throws Exception {
        synchronized (LTSCompiler.class) {
            CompositeState ordinary = run("");
            CompositeState prepared = run("mode=prepare,snapshot=\"initial.ds\"");
            assertEquivalent(ordinary.composition, prepared.composition);
            Path initial = path("initial.ds");
            NativeSynthesisSnapshot original = NativeSnapshotIO.read(initial, true);
            assertNotNull(original.plant);
            assertNotNull(original.game);
            assertEquals(original.plant.getStates(), original.game.getStates());
            assertTrue(original.plant.getStates().contains(-1L));
            assertFalse(original.winning.contains(-1L));
            assertEquals(original.plant.getStates().size() - 1, original.tuples.size());
            assertEquivalent(prepared.composition, original.output);
            Path second = path("roundtrip.ds");
            NativeSnapshotIO.write(second, original);
            NativeSynthesisSnapshot restored = NativeSnapshotIO.read(second, true);
            assertEquals(original.winning, restored.winning);
            assertEquals(original.plant.getTransitions(REQUIRED), restored.plant.getTransitions(REQUIRED));
            assertEquals(controllerEdges(original), controllerEdges(restored));
            assertEquals(original.tuples.keySet(), restored.tuples.keySet());
            for (Long state : original.tuples.keySet()) {
                assertArrayEquals(original.tuples.get(state), restored.tuples.get(state));
            }
            NativeSynthesisSnapshot fullInput = NativeSnapshotIO.read(initial, false);
            assertNull(fullInput.plant);
            assertNull(fullInput.game);
            assertEquals(controllerEdges(original), controllerEdges(fullInput));
        }
    }

    @Test
    public void diagnosticsRecordNativeBoundariesWithoutChangingOrdinaryOrPreparedControllers() throws Exception {
        synchronized (LTSCompiler.class) {
            String previousPath = System.getProperty(NativeMemoryDiagnostics.PATH_PROPERTY);
            String previousHistograms = System.getProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY);
            try {
                System.clearProperty(NativeMemoryDiagnostics.PATH_PROPERTY);
                CompositeState baseline = run("");
                CompositeState preparedOff = run("mode=prepare,snapshot=\"off.ds\"");
                System.setProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY, "false");
                Path events = path("diagnostics-on/memory-events.jsonl");
                System.setProperty(NativeMemoryDiagnostics.PATH_PROPERTY, events.toString());
                CompositeState ordinary = run("");
                assertEquivalent(baseline.composition, ordinary.composition);
                assertNull(ordinary.differentialCapture);
                assertFalse("Ordinary mode must not open diagnostics even when the request property is set",
                        Files.exists(events));
                CompositeState preparedOn = run("mode=prepare,snapshot=\"on.ds\"");
                assertEquivalent(preparedOff.composition, preparedOn.composition);
                NativeSynthesisSnapshot off = NativeSnapshotIO.read(path("off.ds"), true);
                NativeSynthesisSnapshot on = NativeSnapshotIO.read(path("on.ds"), true);
                assertEquals(productEdges(off), productEdges(on));
                assertEquals(winningTuples(off), winningTuples(on));
                assertEquals(controllerEdges(off), controllerEdges(on));
                List<JSONObject> rows = diagnosticRows(events);
                assertSuccessfulScopes(rows);
                Set<String> completed = new HashSet<>();
                for (JSONObject row : rows) if ("end".equals(row.get("event"))) completed.add((String) row.get("phase"));
                for (String phase : Arrays.asList("initial_product_analyser_setup", "initial_product_machine_clone",
                        "initial_product_exploration", "initial_product_compact_build", "initial_product_capture",
                        "component_to_mts", "product_to_mts", "fluent_to_compact", "fluent_recompose",
                        "fluent_product_analyser_setup", "fluent_product_machine_clone", "fluent_product_exploration",
                        "fluent_product_compact_build", "fluent_correspondence", "fluent_to_mts", "plant_capture",
                        "native_game_build", "native_rank_setup", "native_solve", "native_winning_capture",
                        "native_controller_build", "controller_conversion", "controller_capture", "controller_postcompose",
                        "controller_product_exploration", "controller_product_compact_build")) {
                    assertTrue("Missing diagnostic phase " + phase, completed.contains(phase));
                }
                assertFalse("Internal fluent composition must not capture a replacement native product",
                        completed.contains("fluent_product_capture"));
                JSONObject product = diagnosticEnd(rows, "initial_product_exploration");
                Map<?, ?> counts = (Map<?, ?>) product.get("counts");
                assertTrue(((Number) counts.get("transition_records")).longValue() > 0);
                assertTrue(((Number) counts.get("state_tuple_entries")).longValue() > 0);
                assertEquals(0L, Files.size(events.getParent().resolve("memory-oom.txt")));
                assertFalse(Files.exists(events.getParent().resolve("memory-histograms")));
                writeDelta(on, NativeSnapshotIO.verifiedDigest(path("on.ds")), "diagnostic-change.json", "c");
                NativeSynthesisSnapshot expected = null;
                for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
                    Path updateEvents = path("diagnostics-" + mode + "/memory-events.jsonl");
                    System.setProperty(NativeMemoryDiagnostics.PATH_PROPERTY, updateEvents.toString());
                    CompositeState updated = run("mode=" + mode + ",snapshot=\"on.ds\","
                            + "delta=\"diagnostic-change.json\",output=\"diagnostic-" + mode + ".ds\"");
                    NativeSynthesisSnapshot snapshot = NativeSnapshotIO.read(path("diagnostic-" + mode + ".ds"), true);
                    assertTrue(snapshot.plant.getStates().size() > on.plant.getStates().size());
                    assertEquivalent(updated.composition, snapshot.output);
                    assertSuccessfulScopes(diagnosticRows(updateEvents));
                    if (expected == null) expected = snapshot;
                    else {
                        assertEquals(productEdges(expected), productEdges(snapshot));
                        assertEquals(winningTuples(expected), winningTuples(snapshot));
                        assertEquals(controllerEdges(expected), controllerEdges(snapshot));
                    }
                }
            } finally {
                restoreProperty(NativeMemoryDiagnostics.PATH_PROPERTY, previousPath);
                restoreProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY, previousHistograms);
            }
        }
    }

    @Test
    public void diagnosticsPreserveOldGuaranteeRejectionAndRecordFailureInAllThreeModes() throws Exception {
        synchronized (LTSCompiler.class) {
            String previousPath = System.getProperty(NativeMemoryDiagnostics.PATH_PROPERTY);
            String previousHistograms = System.getProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY);
            try {
                System.clearProperty(NativeMemoryDiagnostics.PATH_PROPERTY);
                System.setProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY, "false");
                String rejectingModel = MODEL.replace("bad->ERROR),", "bad->ERROR | c->SAFE),")
                        .replace("bad->ERROR).", "bad->ERROR | c->ERROR).");
                CompositeState prepare = compile(rejectingModel, "mode=prepare,snapshot=\"guard.ds\"");
                TransitionSystemDispatcher.applyComposition(prepare, SILENT);
                NativeSynthesisSnapshot old = NativeSnapshotIO.read(path("guard.ds"), true);
                String digest = NativeSnapshotIO.verifiedDigest(path("guard.ds"));
                writeDelta(old, digest, "guard-loss.json", "c");
                for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
                    Path events = path("guard-" + mode + "/memory-events.jsonl");
                    System.setProperty(NativeMemoryDiagnostics.PATH_PROPERTY, events.toString());
                    CompositeState target = compile(rejectingModel, "mode=" + mode
                            + ",snapshot=\"guard.ds\",delta=\"guard-loss.json\",output=\"guard-" + mode + ".ds\"");
                    try {
                        TransitionSystemDispatcher.applyComposition(target, SILENT);
                        fail("Diagnostics must preserve old controller guarantee rejection: " + mode);
                    } catch (IllegalStateException | IllegalArgumentException expected) {
                        assertTrue(expected.getMessage(), expected.getMessage().contains("old controller guarantee"));
                    }
                    assertFalse(Files.exists(path("guard-" + mode + ".ds")));
                    assertNull(target.differentialResult);
                    List<JSONObject> rows = diagnosticRows(events);
                    List<JSONObject> failures = new ArrayList<>();
                    for (JSONObject row : rows) if ("failure".equals(row.get("event"))) failures.add(row);
                    assertEquals("The wrapper records the original failure once", 1, failures.size());
                    assertTrue(((String) failures.get(0).get("message")).contains("old controller guarantee"));
                    assertEquals("FAILED", rows.get(rows.size() - 1).get("status"));
                    String stack = new String(Files.readAllBytes(events.getParent().resolve("memory-oom.txt")),
                            StandardCharsets.UTF_8);
                    assertTrue(stack.contains("old controller guarantee"));
                    assertTrue(stack.contains("phase=" + failures.get(0).get("phase")));
                    Path ordinaryEvents = path("ordinary-after-" + mode + "/memory-events.jsonl");
                    System.setProperty(NativeMemoryDiagnostics.PATH_PROPERTY, ordinaryEvents.toString());
                    CompositeState ordinary = run("");
                    assertNull(ordinary.differentialCapture);
                    assertNull(ordinary.differentialOptions);
                    assertFalse(Files.exists(ordinaryEvents));
                }
                assertEquals(digest, NativeSnapshotIO.verifiedDigest(path("guard.ds")));
            } finally {
                restoreProperty(NativeMemoryDiagnostics.PATH_PROPERTY, previousPath);
                restoreProperty(NativeMemoryDiagnostics.HISTOGRAM_PROPERTY, previousHistograms);
            }
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }

    private static List<JSONObject> diagnosticRows(Path events) throws IOException {
        List<JSONObject> rows = new ArrayList<>();
        for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
            Object row = JSONValue.parse(line);
            assertTrue("Every diagnostic line must be complete JSON", row instanceof JSONObject);
            rows.add((JSONObject) row);
        }
        assertFalse(rows.isEmpty());
        return rows;
    }

    private static JSONObject diagnosticEnd(List<JSONObject> rows, String phase) {
        for (JSONObject row : rows) {
            if ("end".equals(row.get("event")) && phase.equals(row.get("phase"))) return row;
        }
        throw new AssertionError("No completed phase " + phase);
    }

    private static void assertSuccessfulScopes(List<JSONObject> rows) {
        Deque<String> phases = new ArrayDeque<>();
        long sequence = 0, elapsed = -1;
        for (JSONObject row : rows) {
            assertEquals(++sequence, ((Number) row.get("sequence")).longValue());
            long now = ((Number) row.get("elapsed_nanos")).longValue();
            assertTrue(now >= elapsed); elapsed = now;
            assertTrue(((Number) row.get("heap_used_bytes")).longValue() >= 0);
            for (String gc : Arrays.asList("gc_collection_count", "gc_collection_millis")) {
                assertTrue(row.get(gc) == null || ((Number) row.get(gc)).longValue() >= 0);
            }
            String event = (String) row.get("event"), phase = (String) row.get("phase");
            if ("begin".equals(event)) phases.push(phase);
            if ("end".equals(event)) {
                assertFalse("Every end must match an entered phase", phases.isEmpty());
                assertEquals(phases.pop(), phase);
                assertTrue(((Number) row.get("phase_elapsed_nanos")).longValue() >= 0);
            }
            assertNotEquals("No failure in successful synthesis", "failure", event);
        }
        assertTrue("All scopes must end successfully", phases.isEmpty());
        assertEquals("SUCCESS", rows.get(rows.size() - 1).get("status"));
    }

    @Test
    public void allThreeModesAgreeForNonselfAdditionWithNewProductStates() throws Exception {
        synchronized (LTSCompiler.class) {
            run("mode=prepare,snapshot=\"initial.ds\"");
            Path initial = path("initial.ds");
            NativeSynthesisSnapshot old = NativeSnapshotIO.read(initial, true);
            String originalDigest = NativeSnapshotIO.verifiedDigest(initial);
            writeDelta(old, originalDigest, "change.json", "c");
            NativeSynthesisSnapshot expected = null;
            CompositeState expectedOutput = null;
            for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
                CompositeState result = compile("mode=" + mode + ",snapshot=\"initial.ds\","
                        + "delta=\"change.json\",output=\"" + mode + ".ds\"");
                MetricsOutput output = new MetricsOutput();
                TransitionSystemDispatcher.applyComposition(result, output);
                NativeSynthesisSnapshot updated = NativeSnapshotIO.read(path(mode + ".ds"), true);
                assertTrue("Addition must create actual new product states", updated.plant.getStates().size()
                        > old.plant.getStates().size());
                assertTrue("New behavior must also appear in the controller", updated.controller.getStates().size()
                        > old.controller.getStates().size());
                assertTrue(controllerEdges(updated).containsAll(controllerEdges(old)));
                assertTrue(controllerEdges(updated).stream().anyMatch(edge -> edge.contains(" c ")));
                assertEquivalent(result.composition, updated.output);
                assertNotNull(result.differentialResult);
                Map<String, Long> times = result.differentialResult.getPhaseNanos();
                long phases = 0;
                for (String phase : Arrays.asList("game_update", "requirements_analysis", "compatibility", "controller_generation")) {
                    assertTrue("Missing/nonpositive phase " + phase, times.containsKey(phase) && times.get(phase) > 0);
                    phases += times.get(phase);
                }
                assertTrue(times.get("update_total") >= phases);
                assertGenerationMeasurements(result.differentialResult, mode, output.metrics);
                if (expected == null) { expected = updated; expectedOutput = result; }
                else {
                    assertEquals(productEdges(expected), productEdges(updated));
                    assertEquals(winningTuples(expected), winningTuples(updated));
                    assertEquals(controllerEdges(expected), controllerEdges(updated));
                    assertEquivalent(expectedOutput.composition, result.composition);
                }
            }
            assertEquals("Updating a saved session must not mutate its input", originalDigest,
                    NativeSnapshotIO.verifiedDigest(initial));
        }
    }

    @Test
    public void updateAndFailureDoNotLeakModesIntoOrdinarySynthesis() throws Exception {
        synchronized (LTSCompiler.class) {
            CompositeState before = run("");
            run("mode=prepare,snapshot=\"initial.ds\"");
            NativeSynthesisSnapshot old = NativeSnapshotIO.read(path("initial.ds"), true);
            writeDelta(old, NativeSnapshotIO.verifiedDigest(path("initial.ds")), "good.json", "c");
            run("mode=differential,snapshot=\"initial.ds\",delta=\"good.json\"");
            CompositeState after = run("");
            assertEquivalent(before.composition, after.composition);
            assertNull(after.differentialOptions);
            assertNull(after.differentialCapture);
            assertNull(after.differentialResult);
            writeDelta(old, "not-the-snapshot-digest", "bad.json", "c");
            CompositeState invalid = compile("mode=differential,snapshot=\"initial.ds\",delta=\"bad.json\"");
            try {
                TransitionSystemDispatcher.applyComposition(invalid, SILENT);
                fail("The snapshot/delta version mismatch must fail");
            } catch (IllegalArgumentException | IllegalStateException expected) { }
            assertNull(invalid.differentialCapture);
            CompositeState afterFailure = run("");
            assertEquivalent(before.composition, afterFailure.composition);
            assertNull(afterFailure.differentialOptions);
            assertNull(afterFailure.differentialCapture);
            assertNull(afterFailure.differentialResult);
        }
    }

    @Test
    public void corruptedSnapshotIsRejectedBeforeModelsAreRestored() throws Exception {
        synchronized (LTSCompiler.class) {
            run("mode=prepare,snapshot=\"initial.ds\"");
            Path data = path("initial.ds").resolve("models.bin");
            byte[] content = Files.readAllBytes(data);
            content[content.length - 1] ^= 1;
            Files.write(data, content);
            try {
                NativeSnapshotIO.read(path("initial.ds"), true);
                fail("Modified snapshot payload must fail checksum validation");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("checksum"));
            }
        }
    }

    @Test
    public void allThreeModesRejectLossOfOldControllerGuarantee() throws Exception {
        synchronized (LTSCompiler.class) {
            String rejectingModel = MODEL.replace("bad->ERROR),", "bad->ERROR | c->SAFE),")
                    .replace("bad->ERROR).", "bad->ERROR | c->ERROR).");
            CompositeState prepare = compile(rejectingModel, "mode=prepare,snapshot=\"initial.ds\"");
            TransitionSystemDispatcher.applyComposition(prepare, SILENT);
            NativeSynthesisSnapshot old = NativeSnapshotIO.read(path("initial.ds"), true);
            String digest = NativeSnapshotIO.verifiedDigest(path("initial.ds"));
            writeDelta(old, digest, "loss.json", "c");
            for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
                CompositeState target = compile(rejectingModel, "mode=" + mode
                        + ",snapshot=\"initial.ds\",delta=\"loss.json\",output=\"" + mode + ".ds\"");
                try {
                    TransitionSystemDispatcher.applyComposition(target, SILENT);
                    fail("A controller losing old guaranteed behavior must be rejected: " + mode);
                } catch (IllegalStateException | IllegalArgumentException expected) {
                    assertTrue(expected.getMessage(), expected.getMessage().contains("old controller guarantee"));
                }
                assertFalse(Files.exists(path(mode + ".ds")));
                assertNull(target.differentialResult);
            }
            assertEquals(digest, NativeSnapshotIO.verifiedDigest(path("initial.ds")));
        }
    }

    @Test
    public void multipleSafetyMonitorsMayCompileInDifferentOrder() throws Exception {
        synchronized (LTSCompiler.class) {
            String model = MODEL.replace("controllerSpec G=",
                    "SAFE2=(d->SECOND | back->SAFE2 | bad->ERROR), "
                            + "SECOND=(d->SECOND | back->SAFE2 | bad->ERROR).\ncontrollerSpec G=")
                    .replace("safety={SAFE}", "safety={SAFE,SAFE2}");
            CompositeState prepare = compile(model, "mode=prepare,snapshot=\"multi.ds\"");
            TransitionSystemDispatcher.applyComposition(prepare, SILENT);
            NativeSynthesisSnapshot old = NativeSnapshotIO.read(path("multi.ds"), true);
            assertEquals(3, old.components.size());
            writeDelta(old, NativeSnapshotIO.verifiedDigest(path("multi.ds")), "multi-change.json", "c");

            // A separate ordinary synthesis uses its own compiler-selected monitor order.
            CompositeState ordinaryUpdated = compile(model.replace("E1=(b->E)", "E1=(b->E | c->E2)"), "");
            TransitionSystemDispatcher.applyComposition(ordinaryUpdated, SILENT);
            NativeSynthesisSnapshot expected = null;
            for (String mode : new String[] {"full", "analysis_reuse", "differential"}) {
                CompositeState target = compile(model, "mode=" + mode + ",snapshot=\"multi.ds\","
                        + "delta=\"multi-change.json\",output=\"multi-" + mode + ".ds\"");
                Map<String, CompactState> byName = new HashMap<>();
                for (int i = 1; i < target.machines.size(); i++) {
                    byName.put(target.machines.get(i).name, target.machines.get(i));
                }
                List<CompactState> monitors = new ArrayList<>();
                for (int i = 1; i < old.components.size(); i++) {
                    monitors.add(Objects.requireNonNull(byName.get(old.components.get(i).name)));
                }
                Collections.reverse(monitors);
                CompactState environment = target.machines.firstElement();
                target.machines = new Vector<>();
                target.machines.add(environment);
                target.machines.addAll(monitors);
                assertNotEquals(old.components.get(1).name, target.machines.get(1).name);
                TransitionSystemDispatcher.applyComposition(target, SILENT);
                assertEquivalent(ordinaryUpdated.composition, target.composition);
                NativeSynthesisSnapshot result = NativeSnapshotIO.read(path("multi-" + mode + ".ds"), true);
                for (int i = 0; i < old.components.size(); i++) {
                    assertEquals("Saved tuple order must remain stable", old.components.get(i).name,
                            result.components.get(i).name);
                }
                assertTrue(result.plant.getStates().size() > old.plant.getStates().size());
                if (expected == null) expected = result;
                else {
                    assertEquals(productEdges(expected), productEdges(result));
                    assertEquals(winningTuples(expected), winningTuples(result));
                    assertEquals(controllerEdges(expected), controllerEdges(result));
                }
            }
        }
    }

    private CompositeState run(String options) {
        CompositeState target = compile(options);
        TransitionSystemDispatcher.applyComposition(target, SILENT);
        return target;
    }

    private static void assertGenerationMeasurements(NativeDifferentialResult result,
            String mode, JSONObject raw) {
        Map<String, Long> times = result.getPhaseNanos();
        long children = 0;
        for (String child : Arrays.asList("controller_build", "controller_conversion", "controller_postcompose")) {
            assertTrue("Missing generation child " + mode + "/" + child, times.containsKey(child));
            assertTrue("Nonpositive generation child " + child, times.get(child) > 0);
            children += times.get(child);
        }
        assertTrue("Generation children must not be double-counted", children <= times.get("controller_generation"));
        if (mode.equals("differential")) {
            assertTrue(times.get("controller_copy") > 0);
            assertTrue(times.get("controller_patch") > 0);
            assertTrue("Copy and patch are nested within build", times.get("controller_copy")
                    + times.get("controller_patch") <= times.get("controller_build"));
        } else {
            assertFalse(times.containsKey("controller_copy"));
            assertFalse(times.containsKey("controller_patch"));
        }
        for (Map<String, Long> gc : Arrays.asList(result.getGcCollectionMillis(), result.getGcCollectionCounts())) {
            for (Map.Entry<String, Long> entry : gc.entrySet()) {
                assertTrue("GC must describe an executed phase: " + entry.getKey(), times.containsKey(entry.getKey()));
                assertTrue("Unsupported counters must be absent, not negative", entry.getValue() >= 0);
            }
            if (!mode.equals("differential")) {
                assertFalse(gc.containsKey("controller_copy"));
                assertFalse(gc.containsKey("controller_patch"));
            }
        }
        assertNotNull("Native raw metrics must be emitted", raw);
        assertEquals(1L, raw.get("generation_timing_version"));
        assertEquals(times, raw.get("phase_nanos"));
        assertEquals(result.getGcCollectionMillis(), raw.get("phase_gc_collection_millis"));
        assertEquals(result.getGcCollectionCounts(), raw.get("phase_gc_collection_counts"));
    }

    private static final class MetricsOutput extends EmptyLTSOuput {
        private JSONObject metrics;

        @Override public void outln(String text) {
            String prefix = "NATIVE_DIFFERENTIAL_METRICS ";
            if (text.startsWith(prefix)) {
                assertNull("Exactly one native result record is expected", metrics);
                metrics = (JSONObject) JSONValue.parse(text.substring(prefix.length()));
                assertNotNull("Malformed native result JSON", metrics);
            }
        }
    }

    private CompositeState compile(String options) {
        return compile(MODEL, options);
    }

    private CompositeState compile(String model, String options) {
        String source = model + "controller || C=(E)~{G}"
                + (options.isEmpty() ? "" : " differential {" + options + "}") + ".";
        LTSCompiler compiler = new LTSCompiler(new LTSInputString(source), SILENT,
                temporary.getRoot().getAbsolutePath());
        compiler.compile();
        return compiler.continueCompilation("C");
    }

    private Path path(String name) { return temporary.getRoot().toPath().resolve(name); }

    private void writeDelta(NativeSynthesisSnapshot snapshot, String digest, String filename, String action)
            throws IOException {
        MTS<Long, String> env = AutomataToMTSConverter.getInstance().convert(snapshot.components.get(0));
        long source = soleTarget(env, env.getInitialState(), "a");
        long target = soleTarget(env, env.getInitialState(), "d");
        assertNotEquals("Use a nonself environmental change", source, target);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put("snapshot_sha256", digest);
        delta.put("source", source);
        delta.put("action", action);
        delta.put("target", target);
        Files.write(path(filename), JSONObject.toJSONString(delta).getBytes(StandardCharsets.UTF_8));
    }

    private long soleTarget(MTS<Long, String> model, long source, String action) {
        Set<Long> successors = model.getTransitions(source, REQUIRED).getImage(action);
        assertEquals(1, successors.size());
        return successors.iterator().next();
    }

    private Set<String> productEdges(NativeSynthesisSnapshot snapshot) {
        Set<String> edges = new HashSet<>();
        for (Long source : snapshot.plant.getStates()) for (Pair<String, Long> edge : snapshot.plant.getTransitions(source, REQUIRED)) {
            edges.add(tuple(snapshot, source) + " " + edge.getFirst() + " " + tuple(snapshot, edge.getSecond()));
        }
        return edges;
    }

    private Set<String> controllerEdges(NativeSynthesisSnapshot snapshot) {
        Set<String> edges = new HashSet<>();
        for (StrategyState<Long, Integer> source : snapshot.controller.getStates()) {
            for (Pair<String, StrategyState<Long, Integer>> edge : snapshot.controller.getTransitions(source, REQUIRED)) {
                edges.add(tuple(snapshot, source.getState()) + " " + edge.getFirst() + " "
                        + tuple(snapshot, edge.getSecond().getState()));
            }
        }
        return edges;
    }

    private Set<String> winningTuples(NativeSynthesisSnapshot snapshot) {
        Set<String> result = new HashSet<>();
        for (Long state : snapshot.winning) result.add(tuple(snapshot, state));
        return result;
    }

    private String tuple(NativeSynthesisSnapshot snapshot, long state) {
        return state == -1 ? "ERROR" : Arrays.toString(snapshot.tuples.get(state));
    }

    /** Independent rooted deterministic bisimulation, insensitive to exported numbering. */
    private void assertEquivalent(CompactState left, CompactState right) {
        MTS<Long, String> a = AutomataToMTSConverter.getInstance().convert(left);
        MTS<Long, String> b = AutomataToMTSConverter.getInstance().convert(right);
        Deque<Pair<Long, Long>> pending = new ArrayDeque<>();
        Set<Pair<Long, Long>> visited = new HashSet<>();
        pending.add(Pair.create(a.getInitialState(), b.getInitialState()));
        while (!pending.isEmpty()) {
            Pair<Long, Long> states = pending.remove();
            if (!visited.add(states)) continue;
            assertEquals(states.getFirst() == -1L, states.getSecond() == -1L);
            Map<String, Long> nextA = row(a, states.getFirst()), nextB = row(b, states.getSecond());
            assertEquals(nextA.keySet(), nextB.keySet());
            for (String action : nextA.keySet()) pending.add(Pair.create(nextA.get(action), nextB.get(action)));
        }
    }

    private Map<String, Long> row(MTS<Long, String> model, long state) {
        Map<String, Long> result = new HashMap<>();
        for (Pair<String, Long> edge : model.getTransitions(state, REQUIRED)) {
            assertNull("Expected deterministic controller", result.put(edge.getFirst(), edge.getSecond()));
        }
        return result;
    }
}
