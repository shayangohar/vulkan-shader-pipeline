package net.chimera.render;

import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.TargetSpec;
import net.chimera.shaderpack.TargetStep;
import net.vulkanmod.vulkan.texture.VulkanImage;
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
    /** The bounded post target bridge: target directives, device MRT limits and output formats. */
    @Test
    void targetBridge() {
        PostTargetPlan.ParseResult route = PostTargetPlan.parse(
                "composite", "#version 120\n/* RENDERTARGETS: 0,4,7 */\n"
                        + "gl_FragData[0] = vec4(1.0);\n"
                        + "gl_FragData[1] = vec4(0.5);\n"
                        + "gl_FragData[2] = vec4(0.25);\n");
        assert route.executable() : route.deviations();
        assert route.plan().targetSlots().equals(List.of(0, 4, 7));
        assert route.plan().targetForOutput(2) == 7;

        PostTargetPlan.ParseResult duplicate = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,4,4 */");
        assert duplicate.deviations().contains("POST_TARGET_DIRECTIVE_DUPLICATE:4");

        assert PackPostTargets.deviceSupportsMrt(4, 4);
        assert !PackPostTargets.deviceSupportsMrt(5, 4);

        Map<Integer, Integer> formats = Map.of(4, 37, 7, 109);
        PostTargetPlan formatted = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,4,7 */", formats).plan();
        assert formatted.outputFormats().equals(List.of(37, 37, 109)); // colortex0 undeclared: RGBA8, as Iris
    }

    /**
     * Rethinking Voxels' prepare passes do not run, so the chain drops colortex8, which its
     * terrain reads. The terrain reads the image colortex8 holds, as under Iris; the resolver
     * found no image and the game crashed (RESOURCE_BINDING_UNAVAILABLE:gbuffers_terrain:Sampler22).
     */
    @Test
    void worldProgramsReadWhatADroppedTargetHolds() {
        var held = MaterialMapFixtures.markerImage();
        var source = MaterialMapFixtures.markerImage();
        var hdr = MaterialMapFixtures.markerImage();
        assertEquals(held, PackPostTargets.worldImage(8, null, true, held), "a dropped target reads its image");
        assertEquals(source, PackPostTargets.worldImage(8, source, true, held), "a live source wins");
        assertEquals(null, PackPostTargets.worldImage(9, null, false, null), "the session made no colortex9");
        assertEquals(null, PackPostTargets.worldImage(0, null, true, held), "colortex0 is left to the resolver");
        var colors = new VulkanImage[16];
        colors[8] = PackPostTargets.worldImage(8, null, true, held);
        assertEquals(held, PackPostChain.resolvePackColorInput(8, colors, hdr));
        assertEquals(hdr, PackPostChain.resolvePackColorInput(0, colors, hdr));
    }

    /**
     * Glimmer's prepare2 builds colortex7's mips (its sky), and its translucents and composite3
     * read them with textureLod. Iris resets a target's filter only after final
     * (FinalPassRenderer.resetRenderTarget), so the chain is still sampled through its mips in
     * every later window and in final. Chimera reset it as each window opened.
     */
    @Test
    void mipSamplingLastsUntilTheFrameEnds() {
        PackPostTargets targets = new PackPostTargets();
        VulkanImage sky = MaterialMapFixtures.markerImage();
        targets.noteMipSampling(sky);
        for (PackFrameSchedulePlan.PostWindow window : PackFrameSchedulePlan.PostWindow.values()) {
            targets.openPostWindow(null, window);
            assertTrue(targets.samplesMips(sky), "still mip-sampled as " + window + " opens");
        }
        targets.generateMipmaps(null, "final");
        assertTrue(targets.samplesMips(sky), "still mip-sampled at final");
    }

    /**
     * Glimmer's prepare2 names colortex0 and writes nothing. Iris's flip there lands on the side the
     * world draws into, where its pre-prepare sky draw left zero; Chimera handed back last frame's
     * final image, which Glimmer's deferred sky adds under the sky, so the horizon glowed red and
     * green. Later windows flip onto the older side, and a prepare pass that wrote colortex0 first
     * has moved it off the world image.
     */
    @Test
    void anUnwrittenColortex0InPrepareKeepsTheWorldDraws() {
        assertTrue(PackPostTargets.keepsWorldDraws(PackFrameSchedulePlan.PostWindow.PREPARE, true));
        for (PackFrameSchedulePlan.PostWindow window : PackFrameSchedulePlan.PostWindow.values()) {
            if (window == PackFrameSchedulePlan.PostWindow.PREPARE) continue;
            assertTrue(!PackPostTargets.keepsWorldDraws(window, true), window + " flips onto the older side");
        }
        assertTrue(!PackPostTargets.keepsWorldDraws(PackFrameSchedulePlan.PostWindow.PREPARE, false),
                "colortex0 a prepare pass wrote is off the world image");
        assertTrue(!PackPostTargets.keepsWorldDraws(null, true), "no window has opened");
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

    /**
     * Iris flips every draw buffer of a composite pass, written or not. Bloop's composite names
     * colortex13 and writes it only with Distant Horizons, so the water's refraction offsets there
     * never outlive their frame: the flip hands back what the frame started with.
     */
    @Test
    void unwrittenTargetFlipsBackToFrameStart(@TempDir Path root) throws Exception {
        writeUnwrittenFixture(root);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        TargetStep composite = graph.step("composite");
        assertEquals(List.of(2), composite.unwrittenTargets(), "the unwritten draw buffer was not found");
        assertTrue(composite.writes(2), "the unwritten draw buffer must still flip");
        assertTrue(graph.target(2).doubled(), "the unwritten draw buffer needs another side");
        assertTrue(graph.requiresInitialSeed(2), "the flip must not hand back undefined memory");

        int sides = graph.target(2).requiresHistory() ? 3 : 2;
        int[] active = new int[3];
        int[] previous = new int[3];
        int[] waterFrames = new int[sides];
        for (int frame = 0; frame < 5; frame++) {
            // Frame open (matchOtherSide), then the water draw, then composite.
            int other = PackPostTargets.nextSide(active[2], sides);
            waterFrames[other] = waterFrames[active[2]];
            previous[2] = other;
            waterFrames[active[2]]++;
            int side = PackPostTargets.writeSide(true, true, active[2], previous[2], sides);
            previous[2] = active[2];
            active[2] = side;
            assertEquals(0, waterFrames[active[2]], "frame " + frame + ": the water outlived its frame");
        }
    }

    /**
     * Kappa's composite2 samples its reflection capture colortex12, whose only writer composite1 is
     * off by default. Iris creates the sampled target and clears it on its first frame
     * (clearPassesFull), so composite2 runs on black. Unseeded, the target stayed unavailable and
     * the whole pass, Kappa's reflections and fog, fell back to identity.
     */
    @Test
    void aTargetNoPassWritesIsSeededForItsReader(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        String header = "#version 120\nconst bool colortex12Clear = false;\nuniform sampler2D colortex0;\n"
                + "uniform sampler2D colortex12;\nvarying vec2 texcoord;\n";
        Files.writeString(shaders.resolve("composite1.fsh"), header
                + "/* RENDERTARGETS: 12 */\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("composite2.fsh"), header + "/* RENDERTARGETS: 0 */\nvoid main() {\n"
                + "    gl_FragColor = texture2D(colortex0, texcoord) + texture2D(colortex12, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), "program.composite1.enabled=false\n");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        TargetStep reader = graph.step("composite2");
        assertTrue(reader != null && reader.executable() && reader.reads(12), graph.steps().toString());
        assertTrue(graph.steps().stream().noneMatch(step -> step.executable() && step.writes(12)),
                graph.steps().toString());
        assertTrue(graph.target(12) != null && graph.target(12).persistent(), String.valueOf(graph.target(12)));
        assertTrue(graph.requiresInitialSeed(12), "a sampled target no pass writes must hold its clear colour");
    }

    private static void writeUnwrittenFixture(Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nconst bool colortex2Clear = false;\n"
                + "uniform sampler2D colortex0;\nuniform sampler2D colortex2;\nvarying vec2 texcoord;\n"
                + "/* RENDERTARGETS: 0,2 */\nvoid main() {\n"
                + "    gl_FragData[0] = texture2D(colortex0, texcoord + texture2D(colortex2, texcoord).xy);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
    }

    /**
     * Iris flips a {@code flip.composite_pre} target once as the composite group opens, whatever its
     * passes are called (CompositeRenderer's explicitPreFlips). Shrimple has no program named
     * composite; its composite_pre flip of colortex2 hands the composite passes its deferred
     * shadows. Iris has no renderer for composite1_pre or final_pre, so they flip nothing.
     */
    @Test
    void aGroupPreFlipFlipsAsTheGroupOpens(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        String header = "#version 120\nuniform sampler2D colortex2;\nvarying vec2 texcoord;\n";
        Files.writeString(shaders.resolve("deferred1.fsh"),
                "#version 120\n/* RENDERTARGETS: 2 */\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n");
        Files.writeString(shaders.resolve("composite1.fsh"), header
                + "/* RENDERTARGETS: 0 */\nvoid main() {\n    gl_FragColor = texture2D(colortex2, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"), "flip.composite_pre.colortex2 = true\n"
                + "flip.composite1_pre.colortex2 = true\nflip.final_pre.colortex2 = true\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"deferred1\",\"fragment\":\"deferred1.fsh\"},"
                        + "{\"name\":\"composite1\",\"fragment\":\"composite1.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        assertEquals(List.of(2), graph.preFlips(PackFrameSchedulePlan.PostWindow.LATE));
        assertEquals(List.of(), graph.preFlips(PackFrameSchedulePlan.PostWindow.EARLY));
        assertEquals(List.of(), graph.step("composite1").flipsAfter());
        assertTrue(graph.target(2).doubled(), "a pre-flipped target needs its other side");
    }

    /**
     * Shrimple's bloom: composite21 reads colortex0 and flips it, and composite38 adds the bloom
     * onto colortex0 by blending, without reading it. In Iris that write lands on the side current
     * before the flip, the scene. Chimera skipped flips of colortex0, so composite38 blended onto
     * its other side, last frame's final image, and the VANILLA profile went white.
     */
    @Test
    void aBlendAfterAFlipLandsOnTheSideCurrentBeforeIt(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        String header = "#version 120\nconst bool colortex0Clear = false;\nvarying vec2 texcoord;\n";
        Files.writeString(shaders.resolve("composite21.fsh"), header + "uniform sampler2D colortex0;\n"
                + "/* RENDERTARGETS: 15 */\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("composite38.fsh"), header + "uniform sampler2D colortex15;\n"
                + "/* RENDERTARGETS: 0 */\nvoid main() {\n    gl_FragColor = texture2D(colortex15, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"),
                "flip.composite21.colortex0 = true\nblend.composite38 = ONE ONE ONE ZERO\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"composite21\",\"fragment\":\"composite21.fsh\"},"
                        + "{\"name\":\"composite38\",\"fragment\":\"composite38.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        assertEquals(List.of(0), graph.step("composite21").flipsAfter());
        assertTrue(graph.target(0).doubled() && !graph.target(0).requiresHistory(), String.valueOf(graph.target(0)));
        assertTrue(PackPostTargets.flips(0, true), "a flip of colortex0 must flip it");

        int[] active = new int[1];
        int[] previous = new int[1];
        for (int frame = 0; frame < 4; frame++) {
            // The scene: the world's HDR image, adopted into colortex0's current side at the flip.
            int scene = active[0];
            PackPostTargets.flipSides(active, previous, 0, 2);
            assertTrue(active[0] != scene, "frame " + frame + ": the flip must hand composite38 the other side");
            int side = PackPostTargets.writeSide(false, true, active[0], previous[0], 2);
            assertEquals(scene, side, "frame " + frame + ": the bloom must blend onto the scene");
            previous[0] = active[0];
            active[0] = side;
        }
    }

    /**
     * Shrimple keeps its opaque and translucent deferred data apart by flipping a target only
     * geometry writes: flip.deferred12.colortex1 sends the translucent geometry to colortex1's other
     * side, and flip.composite_pre hands the composite passes the opaque side again. Iris's flip
     * toggles the side whether a pass wrote the target or not. Swapping with no side recorded left
     * both on one image, so the translucent data overwrote the opaque, and the sun shadows went.
     */
    @Test
    void aFlipOfATargetOnlyGeometryWritesMovesToItsOtherSide(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("deferred1.fsh"),
                "#version 120\n/* RENDERTARGETS: 6 */\nvoid main() {\n    gl_FragColor = vec4(1.0);\n}\n");
        Files.writeString(shaders.resolve("composite1.fsh"), "#version 120\nuniform sampler2D colortex1;\n"
                + "varying vec2 texcoord;\n/* RENDERTARGETS: 0 */\nvoid main() {\n"
                + "    gl_FragColor = texture2D(colortex1, texcoord);\n}\n");
        Files.writeString(shaders.resolve("final.fsh"), "#version 120\nuniform sampler2D colortex0;\n"
                + "varying vec2 texcoord;\n\nvoid main() {\n    gl_FragColor = texture2D(colortex0, texcoord);\n}\n");
        Files.writeString(shaders.resolve("shaders.properties"),
                "flip.deferred1.colortex1 = true\nflip.composite_pre.colortex1 = true\n");
        Files.writeString(shaders.resolve("shaders.json"),
                "{\"programs\":[{\"name\":\"deferred1\",\"fragment\":\"deferred1.fsh\"},"
                        + "{\"name\":\"composite1\",\"fragment\":\"composite1.fsh\"},"
                        + "{\"name\":\"final\",\"fragment\":\"final.fsh\"}]}\n");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        assertEquals(List.of(1), graph.step("deferred1").flipsAfter());
        assertEquals(List.of(1), graph.preFlips(PackFrameSchedulePlan.PostWindow.LATE));
        assertTrue(graph.target(1).doubled() && !graph.target(1).requiresHistory(), String.valueOf(graph.target(1)));

        int[] active = new int[2];
        int[] previous = new int[2];
        for (int frame = 0; frame < 3; frame++) {
            int opaque = active[1];
            PackPostTargets.flipSides(active, previous, 1, 2);
            assertTrue(active[1] != opaque, "frame " + frame + ": the translucent geometry drew over the opaque data");
            PackPostTargets.flipSides(active, previous, 1, 2);
            assertEquals(opaque, active[1], "frame " + frame + ": the composite passes must read the opaque data");
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
