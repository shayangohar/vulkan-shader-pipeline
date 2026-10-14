package net.chimera.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame schedule, the depth seam, and temporal state. */
final class FrameScheduleTest {
    @Test
    void frameSchedule() throws Exception {
        Path root = TestPacks.fixtures();
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
        verifyUnsupported(unsupportedPath);
        verifyBaseline(root.resolve("baselines/m7_7.json"), supported, schedule);
    }

    private static void verifySchedule(
            PackFrameSchedulePlan schedule,
            PackFrameSchedulePlan scheduleAgain,
            PackTargetGraphPlan graph,
            PackTargetGraphPlan graphAgain
    ) {
        assertEquals(schedule.snapshot(), scheduleAgain.snapshot(),
                "schedule snapshot stability");
        assertEquals(graph.fingerprint(), graphAgain.fingerprint(),
                "graph stability");
        assertEquals(List.of("deferred"), names(schedule,
                PackFrameSchedulePlan.PostWindow.EARLY),
                "early post order");
        assertEquals(List.of("composite", "composite1"), names(schedule,
                PackFrameSchedulePlan.PostWindow.LATE),
                "late post order");
        assertEquals(List.of("final"), names(schedule,
                PackFrameSchedulePlan.PostWindow.FINAL),
                "final post order");
        assertTrue(schedule.requiresDepthtex1(), "depthtex1 was not scheduled");
        assertTrue(!schedule.requiresDepthtex0(), "fixture unexpectedly requires depthtex0");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.EARLY_POST)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.TRANSLUCENT),
                "early post is not before translucent terrain");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.LATE_POST)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.FINAL),
                "late post is not before final");
        assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.FINAL)
                        < schedule.phaseIndex(PackFrameSchedulePlan.Phase.GUI),
                "final is not before GUI");
        assertTrue(!schedule.handBeforeEarlyPost()
                        && schedule.deviations().contains("HAND_PHASE_HOST_BOUNDARY"),
                "hand boundary was reported as fully relocated");
    }

    @Test
    void temporalState() {
        PackTemporalState state = new PackTemporalState();
        state.beginFrame(true);
        assertTrue(state.currentAvailable(0), "HDR identity is unavailable");
        assertTrue(!state.currentAvailable(3), "unwritten target is available");
        state.stageWrite(3);
        assertTrue(state.commit(), "first temporal commit failed");
        assertTrue(state.currentAvailable(3), "committed target is unavailable");
        assertTrue(state.previousAvailable(3), "previous target was not retained");

        state.beginFrame(true);
        assertTrue(state.previousAvailable(3), "previous target was lost at frame start");
        state.stageWrite(2);
        state.abort();
        assertTrue(!state.currentAvailable(2), "aborted target became available");
        state.reset();
        assertTrue(state.firstFrame() && !state.previousAvailable(3),
                "temporal reset did not clear history");

        verifyTwoFrameHistory();
    }

    /**
     * Models two complete frames plus the failure seams a temporal reader
     * depends on. History must advance every committed frame, a skipped
     * writer must retain (not invent) previous availability exactly as the
     * runtime seeds it, an aborted frame must keep the last commit, and a
     * resize/reload must restart with no stale previous frame.
     */
    private static void verifyTwoFrameHistory() {
        PackTemporalState frames = new PackTemporalState();
        frames.beginFrame(true);
        frames.seedCurrent(2);
        frames.stageWrite(2);
        assertTrue(frames.commit(), "first history commit failed");
        assertTrue(frames.previousAvailable(2), "first frame produced no history");

        frames.beginFrame(true);
        assertTrue(frames.previousAvailable(2), "history was lost between frames");
        assertTrue(!frames.currentAvailable(2), "new frame kept stale current state");
        frames.seedCurrent(2);
        frames.stageWrite(2);
        assertTrue(frames.commit(), "second history commit failed");
        assertTrue(frames.previousAvailable(2), "second frame produced no history");

        // Skipped writer: the runtime still seeds a valid persistent
        // target at frame start, so the previous frame stays available
        // instead of degrading to unseeded history.
        frames.beginFrame(true);
        frames.seedCurrent(2);
        assertTrue(frames.commit(), "skipped-writer commit failed");
        assertTrue(frames.previousAvailable(2),
                "skipped writer must retain the last committed history");

        // Failed writer: abort drops the pending write and the commit is
        // refused, so the last good history survives untouched.
        frames.beginFrame(true);
        frames.seedCurrent(2);
        frames.stageWrite(2);
        frames.abort();
        assertTrue(frames.previousAvailable(2),
                "aborted frame must keep the last committed history");

        // Resize/reload restarts the sequence with no stale previous frame.
        frames.reset();
        assertTrue(frames.firstFrame(), "reset did not restart the frame sequence");
        frames.beginFrame(true);
        assertTrue(!frames.previousAvailable(2) && !frames.currentAvailable(2),
                "reloaded session exposed stale temporal state");
    }

    @Test
    void staticAvailability() {
        boolean[] written = new boolean[PackTargetGraphPlan.MAX_TARGET + 1];
        assertTrue(PackPostTargets.isTargetAvailable(0, true, written),
                "target 0 HDR identity is unavailable");
        assertTrue(!PackPostTargets.isTargetAvailable(1, true, written),
                "target 1 is available before a write");
        written[1] = true;
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0, 1), true, written),
                "committed target set was rejected");
        PackPostTargets.invalidateWrittenTargets(written, List.of(1));
        assertTrue(!PackPostTargets.areTargetsAvailable(List.of(1), true, written),
                "invalidated target remained available");
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0), true, written),
                "HDR identity was lost after target invalidation");
    }

    private static void verifyUnsupported(Path path) {
        PackProbe.Analysis analysis = PackProbe.analyze(path);
        PackProgramPlan composite = analysis.plan().program("composite");
        assertTrue(composite != null && !composite.executable(),
                "unsupported post stage remained executable");
        assertTrue(!analysis.report().shouldAttempt("composite"),
                "unsupported post stage remained eligible");
        PackProgramPlan finalPlan = analysis.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "unrelated final stage was rejected");
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
            assertEquals(report, analysis.report().sha256(), "report baseline");
        }
        if (!"TO_BE_FILLED".equals(scheduleHash)) {
            assertEquals(scheduleHash, schedule.fingerprint(), "schedule baseline");
        }
    }
}
