package net.chimera.render;

import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which pack post targets a pass may sample, and which persist across frames. */
final class PackPostTargetsTest {
    @Test
    void availability() {
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
    }

    @Test
    void feedbackSeeding() {
        TargetSpec persistent = new TargetSpec(2, 97, 16, 16, false,
                new float[] {0, 0, 0, 0}, true, true, false, false, List.of());
        TargetStep feedback = new TargetStep("feedback", List.of(2), List.of(2),
                List.of(97), Map.of(2, 0), Map.of(2, 1), List.of(), 16, 16,
                false, true, List.of());
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(persistent, List.of(feedback)),
                "persistent feedback target must be seeded before its first read");
        assertTrue(!PackTargetGraphPlan.requiresInitialSeed(persistent, List.of(
                        new TargetStep("producer", List.of(0), List.of(2),
                                List.of(97), Map.of(0, 0), Map.of(2, 1), List.of(), 16, 16,
                                false, true, List.of()),
                        feedback)),
                "persistent target with a prior producer must not be reseeded");

        // A declared-persistent feedback target seeds once at install so
        // its first read cannot sample undefined memory. Seeding fixes
        // first-frame contents only; it never redefines clear semantics.
        TargetSpec clearedFeedback = new TargetSpec(2, 97, 16, 16, true,
                new float[] {0, 0, 0, 0}, true, true, false, false, List.of());
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(clearedFeedback, List.of(feedback)),
                "declared feedback target must seed even when the pack clears it");
    }

    @Test
    void temporalGeometryTarget() {
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
    }

    /**
     * Persistence comes only from the pack's clear contract. A nonzero
     * target cleared every frame stays nonpersistent even when a pass
     * reads it before writing it; the same shape with
     * colortexNClear=false persists and seeds its first frame once.
     */
    @Test
    void declarationDrivenPersistence(@TempDir Path root) throws Exception {
        Path cleared = root.resolve("cleared");
        writeFeedbackFixture(cleared, true);
        PackProbe.Analysis clearedAnalysis = PackProbe.analyze(cleared);
        PackTargetGraphPlan clearedGraph = PackTargetGraphPlan.build(
                clearedAnalysis.plan().programs(), clearedAnalysis.config(), 16, 16, 8, 16384);
        assertTrue(clearedGraph.target(2) != null && clearedGraph.target(2).clear(),
                "clear=true declaration was not retained for target 2");
        assertTrue(!clearedGraph.target(2).persistent(),
                "clear=true read-before-write target must not persist across frames");
        assertTrue(!PackTargetGraphPlan.requiresInitialSeed(clearedGraph.target(2), clearedGraph.steps()),
                "clear=true target must not seed persistent history");

        Path retained = root.resolve("retained");
        writeFeedbackFixture(retained, false);
        PackProbe.Analysis retainedAnalysis = PackProbe.analyze(retained);
        PackTargetGraphPlan retainedGraph = PackTargetGraphPlan.build(
                retainedAnalysis.plan().programs(), retainedAnalysis.config(), 16, 16, 8, 16384);
        assertTrue(retainedGraph.target(2) != null && retainedGraph.target(2).persistent(),
                "clear=false read-before-write target must persist across frames");
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(retainedGraph.target(2), retainedGraph.steps()),
                "clear=false feedback target must seed its first frame");
    }

    /**
     * Iris's explicit flip, as Photon uses flip.composite1.colortex6: deferred3 keeps AO history
     * in colortex6, composite then writes fog transmittance there for composite1, and the flip
     * after composite1 makes the AO image current again for the next frame's deferred3.
     */
    @Test
    void explicitFlipRestoresHistory(@TempDir Path root) throws Exception {
        writeFlipFixture(root);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        assertEquals(List.of(2), graph.step("composite2").flipsAfter(), "the flip directive did not reach its step");
        assertEquals(List.of(), graph.step("composite").flipsAfter(), "a step without a directive flipped");
        assertTrue(graph.target(2).doubled() && graph.target(2).requiresHistory(),
                "the flipped history target must keep three sides");

        int sides = 3;
        int[] active = new int[3];
        int[] previous = new int[3];
        int history = active[2];
        for (int frame = 0; frame < 4; frame++) {
            assertEquals(history, active[2], "frame " + frame + ": the AO pass must read last frame's AO");
            previous[2] = active[2];
            active[2] = PackPostTargets.nextSide(active[2], sides);
            int ambient = active[2];
            previous[2] = active[2];
            active[2] = PackPostTargets.nextSide(active[2], sides);
            assertTrue(active[2] != ambient, "the fog write must not overwrite this frame's AO");
            PackPostTargets.swapSides(active, previous, 2);
            assertEquals(ambient, active[2], "the flip must make this frame's AO current");
            history = ambient;
        }
    }

    private static void writeFlipFixture(Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        String header = "#version 120\nuniform sampler2D colortex2;\nvarying vec2 texcoord;\n";
        Files.writeString(shaders.resolve("composite.fsh"), header + "const bool colortex2Clear = false;\n"
                + "/* RENDERTARGETS: 2 */\nvoid main() {\n"
                + "    gl_FragColor = texture2D(colortex2, texcoord) * 0.5;\n}\n");
        Files.writeString(shaders.resolve("composite1.fsh"),
                "#version 120\n/* RENDERTARGETS: 2 */\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n");
        Files.writeString(shaders.resolve("composite2.fsh"), header
                + "/* RENDERTARGETS: 0 */\nvoid main() {\n    gl_FragColor = texture2D(colortex2, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), "flip.composite2.colortex2 = true\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"},"
                        + "{\"name\":\"composite1\",\"fragment\":\"composite1.fsh\"},"
                        + "{\"name\":\"composite2\",\"fragment\":\"composite2.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
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
}
