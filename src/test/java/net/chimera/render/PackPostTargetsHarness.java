package net.chimera.render;

import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;

import java.nio.file.Files;
import java.nio.file.Path;
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

        // A declared-persistent feedback target seeds once at install so
        // its first read cannot sample undefined memory. Seeding fixes
        // first-frame contents only; it never redefines clear semantics.
        TargetSpec clearedFeedback = new TargetSpec(2, 97, 16, 16, true,
                new float[] {0, 0, 0, 0}, true, true, List.of());
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(clearedFeedback, List.of(feedback)),
                "declared feedback target must seed even when the pack clears it");

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

        try {
            verifyDeclarationDrivenPersistence();
        } catch (AssertionError failure) {
            throw failure;
        } catch (Exception failure) {
            throw new AssertionError("declaration-driven persistence check failed", failure);
        }

        System.out.println("[chimera] post target availability harness: PASS");
    }

    /**
     * Persistence comes only from the pack's clear contract. A nonzero
     * target cleared every frame stays nonpersistent even when a pass
     * reads it before writing it; the same shape with
     * colortexNClear=false persists and seeds its first frame once.
     */
    private static void verifyDeclarationDrivenPersistence() throws Exception {
        Path cleared = Files.createTempDirectory("chimera-target-clear-contract-");
        try {
            writeFeedbackFixture(cleared.resolve("pack"), true);
            PackProbe.Analysis analysis = PackProbe.analyze(cleared.resolve("pack"));
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
            assertTrue(graph.target(2) != null && graph.target(2).clear(),
                    "clear=true declaration was not retained for target 2");
            assertTrue(!graph.target(2).persistent(),
                    "clear=true read-before-write target must not persist across frames");
            assertTrue(!PackTargetGraphPlan.requiresInitialSeed(graph.target(2), graph.steps()),
                    "clear=true target must not seed persistent history");
        } finally {
            deleteTree(cleared);
        }

        Path retained = Files.createTempDirectory("chimera-target-retain-contract-");
        try {
            writeFeedbackFixture(retained.resolve("pack"), false);
            PackProbe.Analysis analysis = PackProbe.analyze(retained.resolve("pack"));
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                    analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
            assertTrue(graph.target(2) != null && graph.target(2).persistent(),
                    "clear=false read-before-write target must persist across frames");
            assertTrue(PackTargetGraphPlan.requiresInitialSeed(graph.target(2), graph.steps()),
                    "clear=false feedback target must seed its first frame");
        } finally {
            deleteTree(retained);
        }
    }

    private static void writeFeedbackFixture(Path root, boolean clear) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"),
                "#version 120\nconst bool colortex2Clear = " + clear + ";\n"
                        + "/* RENDERTARGETS: 2 */\nuniform sampler2D colortex2;\n"
                        + "varying vec2 texcoord;\n\nvoid main() {\n"
                        + "    gl_FragColor = texture2D(colortex2, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"),
                "#version 120\nuniform sampler2D colortex0;\n"
                        + "varying vec2 texcoord;\n\nvoid main() {\n"
                        + "    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}
