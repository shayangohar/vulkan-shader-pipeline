package net.chimera.render;

import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.chimera.shaderpack.PackTargetGraphPlan;

import java.util.Map;
import java.util.List;

/** Deterministic, Vulkan-free checks for pack post target availability. */
public final class PackPostTargetsHarness {
    private PackPostTargetsHarness() {}

    public static void run() {
        boolean[] unwritten = new boolean[4];
        assertTrue(PackPostTargets.isTargetAvailable(0, true, unwritten),
                "target 0 must use HDR identity before a pack write");
        assertTrue(!PackPostTargets.isTargetAvailable(1, true, unwritten),
                "target 1 must be unavailable before a pack write");
        assertTrue(!PackPostTargets.isTargetAvailable(2, true, unwritten),
                "target 2 must be unavailable before a pack write");
        assertTrue(!PackPostTargets.isTargetAvailable(3, true, unwritten),
                "target 3 must be unavailable before a pack write");
        boolean[] targetZeroWritten = unwritten.clone();
        targetZeroWritten[0] = true;
        assertTrue(PackPostTargets.isTargetAvailable(0, false, targetZeroWritten),
                "target 0 must become pack-owned after a successful write");
        PackPostTargets.invalidateWrittenTargets(targetZeroWritten, List.of(0));
        assertTrue(PackPostTargets.isTargetAvailable(0, true, targetZeroWritten),
                "target 0 invalidation must restore HDR identity");
        assertTrue(!PackPostTargets.isTargetAvailable(0, false, targetZeroWritten),
                "target 0 must not remain pack-owned after invalidation");

        boolean[] targetOneWritten = unwritten.clone();
        targetOneWritten[1] = true;
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0, 1), true, targetOneWritten),
                "written target 1 must remain available with HDR target 0");
        assertTrue(!PackPostTargets.areTargetsAvailable(List.of(0, 2), true, targetOneWritten),
                "target 2 must remain unavailable until written");

        boolean[] targetThreeWritten = targetOneWritten.clone();
        targetThreeWritten[3] = true;
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0, 3), true, targetThreeWritten),
                "a successful target 3 writer must make target 3 available");
        PackPostTargets.invalidateWrittenTargets(targetThreeWritten, List.of(3));
        assertTrue(!PackPostTargets.areTargetsAvailable(List.of(0, 3), true, targetThreeWritten),
                "a skipped target 3 writer must invalidate target 3");
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0), true, targetThreeWritten),
                "an unrelated target 0 consumer must remain executable");
        PackPostTargets.invalidateWrittenTargets(targetThreeWritten, List.of(1));
        assertTrue(!PackPostTargets.isTargetAvailable(1, true, targetThreeWritten),
                "invalidated auxiliary targets must remain unavailable until rewritten");
        targetThreeWritten[3] = true;
        assertTrue(PackPostTargets.isTargetAvailable(3, true, targetThreeWritten),
                "a later successful write must restore target availability");

        // A failed destination bank does not change the committed source set.
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0, 1), true, targetOneWritten),
                "failed destination bank must preserve committed targets");
        assertTrue(PackPostTargets.bankAfterFinish(0, 1, false) == 0,
                "runtime failure must not swap the destination bank");
        assertTrue(PackPostTargets.bankAfterFinish(0, 1, true) == 1,
                "successful finish must commit the destination bank");
        PackPostTargets.invalidateWrittenTargets(targetThreeWritten, List.of(3));
        assertTrue(PackPostTargets.areTargetsAvailable(List.of(0), true, targetThreeWritten),
                "final fallback must resolve through target 0 when target 3 is unavailable");

        TargetSpec persistent = new TargetSpec(2, 97, 16, 16, false,
                new float[] {0, 0, 0, 0}, true, true, List.of());
        TargetStep feedback = new TargetStep("feedback", List.of(2), List.of(2),
                List.of(97), Map.of(2, 0), Map.of(2, 1), 16, 16,
                false, true, List.of());
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(persistent, List.of(feedback)),
                "persistent feedback target must be seeded before its first read");
        assertTrue(!PackTargetGraphPlan.requiresInitialSeed(persistent, List.of(
                        new TargetStep("producer", List.of(0), List.of(2),
                                List.of(97), Map.of(0, 0), Map.of(2, 1), 16, 16,
                                false, true, List.of()),
                        feedback)),
                "persistent target with a prior producer must not be reseeded");

        PackTemporalState frame = new PackTemporalState();
        frame.beginFrame(true);
        frame.seedCurrent(6); // Geometry material output, before the first post window.
        frame.requireFrameStarted();
        assertTrue(frame.currentAvailable(6), "post boundary discarded geometry target 6");
        boolean duplicateRejected = false;
        try {
            frame.beginFrame(true);
        } catch (IllegalStateException expected) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected && frame.currentAvailable(6),
                "late initialization must fail before clearing geometry target 6");
        assertTrue(frame.commit() && frame.previousAvailable(6),
                "geometry target 6 must survive the temporal frame commit");
        frame.beginFrame(true);
        assertTrue(!frame.currentAvailable(6) && frame.previousAvailable(6),
                "next frame must reset current availability without losing committed history");
        frame.abort();

        System.out.println("[chimera] post target availability harness: PASS");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}
