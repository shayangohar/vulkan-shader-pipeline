package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import net.chimera.shaderpack.PackFrameSchedulePlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackTargetGraphPlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * Reforged 0.3.5 sets a viewport's y from the bound framebuffer's height. Pegasus draws its sky
     * into colortex6 at a third of the screen and composite2 into colortex9 at a tenth; bound to
     * colortex0's full-size stand-in, both passes drew below their images and the sky came out black.
     */
    @Test
    void aPostPassBindsAStandInOfItsOwnSize() throws IOException {
        VulkanImage[][] images = new VulkanImage[2][16];
        images[0][0] = MaterialMapFixtures.markerImage(2560, 1440);
        images[1][0] = MaterialMapFixtures.markerImage(2560, 1440);
        images[0][6] = MaterialMapFixtures.markerImage(844, 475);
        images[0][9] = MaterialMapFixtures.markerImage(256, 144);
        Map<Long, Framebuffer> standIns = new HashMap<>();
        PackPostTargets.addStandIns(standIns, images);
        assertEquals(3, standIns.size(), "one stand-in per extent");
        for (VulkanImage first : new VulkanImage[] {images[1][0], images[0][6], images[0][9]}) {
            Framebuffer bound = PackPostTargets.standIn(standIns, first);
            assertEquals(first.width, bound.getWidth());
            assertEquals(first.height, bound.getHeight(), "the viewport's y comes from this height");
        }
        VulkanImage hdr = MaterialMapFixtures.markerImage(1920, 1080);
        assertEquals(1080, PackPostTargets.standIn(standIns, hdr).getHeight(), "an HDR image no target matches");
        assertEquals(3, standIns.size(), "the window replaces the HDR image, so its stand-in is not kept");

        // prepare binds the first attachment's stand-in before setViewport reads its height, and
        // cleanUp drops the stand-ins with the images they were built on.
        ClassNode targets = MixinBytecode.nativeClass("net/chimera/render/PackPostTargets");
        List<String> prepare = calls(targets, "prepare");
        int bound = Collections.indexOfSubList(prepare, List.of("PackPostTargets$PostAttachments.first",
                "PackPostTargets.standIn", "Renderer.setBoundFramebuffer"));
        assertTrue(bound >= 0, "prepare binds standIn(first attachment): " + prepare);
        assertTrue(bound < prepare.indexOf("Renderer.setViewport"), "the stand-in is bound before setViewport");
        assertTrue(calls(targets, "cleanUp").contains("Map.clear"), "cleanUp drops the stand-ins");
    }

    /**
     * A post stage that throws aborts only its own step: the frame stays open, so the passes after
     * it still record and the frame still commits (TASK-922). Closing the frame stays the window's
     * abort, which turns the pack off.
     */
    @Test
    void aFailedStepLeavesTheFrameOpen() throws IOException {
        ClassNode targets = MixinBytecode.nativeClass("net/chimera/render/PackPostTargets");
        assertFalse(calls(targets, "abortStep").contains("PackTemporalState.abort"),
                "a failed step must not close the frame");
        List<String> abort = calls(targets, "abort");
        assertTrue(abort.contains("PackPostTargets.abortStep") && abort.contains("PackTemporalState.abort"),
                "the window's abort ends the step and closes the frame: " + abort);
        ClassNode chain = MixinBytecode.nativeClass("net/chimera/render/PackPostChain");
        List<String> stage = calls(chain, "runPostStage");
        assertTrue(stage.contains("PackPostTargets.abortStep") && !stage.contains("PackPostTargets.abort"),
                "a stage aborts its own step only: " + stage);
        assertTrue(stage.contains("PostStageFailures.isolate"), "a stage's failure is isolated: " + stage);
    }

    /** The methods {@code method} calls, in bytecode order, as SimpleOwner.name. */
    private static List<String> calls(ClassNode owner, String method) {
        MethodNode node = owner.methods.stream().filter(candidate -> candidate.name.equals(method)).findFirst()
                .orElseThrow(() -> new AssertionError(owner.name + "." + method + " is missing"));
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : node.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        }
        return calls;
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
     * colortexNClear=false persists; every target is cleared on its first frame (PostTargetSides).
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
        Path retained = root.resolve("retained");
        writeFeedbackFixture(retained, false);
        PackProbe.Analysis retainedAnalysis = PackProbe.analyze(retained);
        PackTargetGraphPlan retainedGraph = PackTargetGraphPlan.build(
                retainedAnalysis.plan().programs(), retainedAnalysis.config(), 16, 16, 8, 16384);
        assertTrue(retainedGraph.target(2) != null && retainedGraph.target(2).persistent(),
                "clear=false read-before-write target must persist across frames");
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