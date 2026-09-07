package net.chimera.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.ConformanceReport;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PackProgramPlan;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Deterministic M7.7 schedule and temporal-state checks. */
public final class M77ConformanceHarness {
    private M77ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supportedPath = root.resolve("m7_7/schedule");
        Path unsupportedPath = root.resolve("m7_7/unsupported_schedule");
        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                supported.plan().programs(), supported.config(), supported.plan().resources(),
                1920, 1080, 8, 16384);
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(
                supported.plan().programs(), graph);
        PackTargetGraphPlan graphAgain = PackTargetGraphPlan.build(
                supportedAgain.plan().programs(), supportedAgain.config(),
                supportedAgain.plan().resources(), 1920, 1080, 8, 16384);
        PackFrameSchedulePlan scheduleAgain = PackFrameSchedulePlan.build(
                supportedAgain.plan().programs(), graphAgain);

        verifySchedule(schedule, scheduleAgain, graph, graphAgain);
        verifyTemporalState();
        verifyStaticAvailability();
        verifyUnsupported(unsupportedPath);
        verifyBaseline(root.resolve("baselines/m7_7.json"), supported, schedule);
        System.out.println("[chimera] M7.7 frame schedule and temporal conformance: PASS");
    }

    private static void verifySchedule(
            PackFrameSchedulePlan schedule,
            PackFrameSchedulePlan scheduleAgain,
            PackTargetGraphPlan graph,
            PackTargetGraphPlan graphAgain
    ) {
        assertEquals(schedule.snapshot(), scheduleAgain.snapshot(),
                "M7.7 schedule snapshot stability");
        assertEquals(graph.fingerprint(), graphAgain.fingerprint(),
                "M7.7 graph stability");
        assertEquals(List.of("deferred"), names(schedule,
                PackFrameSchedulePlan.PostWindow.EARLY),
                "M7.7 early post order");
        assertEquals(List.of("composite", "composite1"), names(schedule,
                PackFrameSchedulePlan.PostWindow.LATE),
                "M7.7 late post order");
        assertEquals(List.of("final"), names(schedule,
                PackFrameSchedulePlan.PostWindow.FINAL),
                "M7.7 final post order");
        assertTrue(schedule.requiresDepthtex1(), "M7.7 depthtex1 was not scheduled");
        assertTrue(!schedule.requiresDepthtex0(), "M7.7 fixture unexpectedly requires depthtex0");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.EARLY_POST)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.TRANSLUCENT),
                "M7.7 early post is not before translucent terrain");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.LATE_POST)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.FINAL),
                "M7.7 late post is not before final");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.FINAL)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.GUI),
                "M7.7 final is not before GUI");
        assertTrue(!schedule.handBeforeEarlyPost()
                        && schedule.deviations().contains("HAND_PHASE_HOST_BOUNDARY"),
                "M7.7 hand boundary was reported as fully relocated");
    }

    private static void verifyTemporalState() {
        PackTemporalState state = new PackTemporalState();
        state.beginFrame(true);
        assertTrue(state.currentAvailable(0), "M7.7 HDR identity is unavailable");
        assertTrue(!state.currentAvailable(3), "M7.7 unwritten target is available");
        state.stageWrite(3);
        assertTrue(state.commit(), "M7.7 first temporal commit failed");
        assertTrue(state.currentAvailable(3), "M7.7 committed target is unavailable");
        assertTrue(state.previousAvailable(3), "M7.7 previous target was not retained");

        state.beginFrame(true);
        assertTrue(state.previousAvailable(3), "M7.7 previous target was lost at frame start");
        state.stageWrite(2);
        state.abort();
        assertTrue(!state.currentAvailable(2), "M7.7 aborted target became available");
        state.reset();
        assertTrue(state.firstFrame() && !state.previousAvailable(3),
                "M7.7 temporal reset did not clear history");
    }

    private static void verifyStaticAvailability() {
        boolean[] written = new boolean[8];
        assertTrue(PackPostTargets.isTargetAvailable(0, true, written),
                "M7.7 target 0 HDR identity is unavailable");
        assertTrue(!PackPostTargets.isTargetAvailable(1, true, written),
                "M7.7 target 1 is available before a write");
        written[1] = true;
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0, 1), true, written),
                "M7.7 committed target set was rejected");
        PackPostTargets.invalidateWrittenTargets(written, List.of(1));
        assertTrue(!PackPostTargets.areTargetsAvailable(List.of(1), true, written),
                "M7.7 invalidated target remained available");
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0), true, written),
                "M7.7 HDR identity was lost after target invalidation");
    }

    private static void verifyUnsupported(Path path) {
        PackProbe.Analysis analysis = PackProbe.analyze(path);
        PackProgramPlan composite = analysis.plan().program("composite");
        assertTrue(composite != null && !composite.executable(),
                "M7.7 unsupported post stage remained executable");
        assertTrue(!analysis.report().shouldAttempt("composite"),
                "M7.7 unsupported post stage remained eligible");
        PackProgramPlan finalPlan = analysis.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "M7.7 unrelated final stage was rejected");
    }

    private static List<String> names(PackFrameSchedulePlan schedule,
                                      PackFrameSchedulePlan.PostWindow window) {
        return schedule.stages(window).stream().map(PackFrameSchedulePlan.PostStage::name).toList();
    }

    private static void verifyBaseline(
            Path baselinePath,
            PackProbe.Analysis analysis,
            PackFrameSchedulePlan schedule
    ) throws Exception {
        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        String report = baseline.get("reportSha256").getAsString();
        String scheduleHash = baseline.get("scheduleFingerprint").getAsString();
        if ("TO_BE_FILLED".equals(report) || "TO_BE_FILLED".equals(scheduleHash)) {
            System.out.println("[chimera] M7.7 reportSha256=" + analysis.report().sha256());
            System.out.println("[chimera] M7.7 scheduleFingerprint=" + schedule.fingerprint());
        }
        if (!"TO_BE_FILLED".equals(report)) {
            assertEquals(report, analysis.report().sha256(), "M7.7 report baseline");
        }
        if (!"TO_BE_FILLED".equals(scheduleHash)) {
            assertEquals(scheduleHash, schedule.fingerprint(), "M7.7 schedule baseline");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
