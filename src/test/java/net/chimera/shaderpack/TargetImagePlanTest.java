package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

/** Iris's colorimgN in the post passes and the hand: the colour target as a storage image (TargetImages). */
final class TargetImagePlanTest {
    /**
     * Rethinking Voxels' prepare clears colorimg9, its R32UI colortex9, with imageStore, and
     * prepare1 and prepare2 reproject through it. All three fell back, so colortex8, which its
     * terrain and deferred1 read, lost every writer. The pass binds colortex9 as a storage image
     * at the slot it is sampled through, in its own format, and reads it as its samplers read
     * their targets.
     */
    @Test
    void aPostPassStoresToAColourTarget(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        writeProgram(shaders, "prepare", """
                #version 430 compatibility
                const int colortex9Format = R32UI;
                layout(r32ui) uniform writeonly uimage2D colorimg9;
                void main() {
                    imageStore(colorimg9, ivec2(gl_FragCoord.xy), uvec4(1u << 31));
                    /* RENDERTARGETS: 8 */
                    gl_FragData[0] = vec4(2.0);
                }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(plan.shouldAttempt("prepare") && advanced.imagesServable("prepare"),
                advanced.deviations().toString());
        PackAdvancedResourcePlan.ProgramBindingLayout layout = advanced.bindingLayout("prepare");
        PackAdvancedResourcePlan.GraphicsImageBinding binding = layout.storageImages().get(0);
        assertEquals(List.of("colorimg9", "colortex9", "uimage2D", "r32ui", 9, SelectorNamespace.colorTargetSlot(9)),
                List.of(binding.symbol(), binding.imageName(), binding.glslType(), binding.viewFormat(),
                        binding.target(), binding.selectorSlot()));
        String fragment = PackDescriptorContracts.bindStorageImages(plan.program("prepare").convertedFragment(),
                "prepare", advanced, layout, VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(fragment.contains("layout(r32ui, binding = " + layout.imageBase()
                + ") uniform uimage2D colorimg9;"), fragment);
        assertEquals(Map.of(9, Set.of(IrisColorFormats.of("r32ui").vkFormat())), advanced.targetImageFormats());
        ProgramImageBindingManifest.Entry entry = PackPipelines.preparePost(plan.program("prepare"), "", advanced)
                .imageBindings().entries().stream().filter(value -> value.sourceSymbol().equals("colorimg9"))
                .findFirst().orElseThrow();
        assertEquals(List.of(ProgramImageBindingManifest.Kind.TARGET_IMAGE, "colortex9", 3),
                List.of(entry.kind(), entry.resourceKey(), entry.descriptorType()));
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(plan.programs(), analysis.config(), 16, 16, 8, 16384);
        assertTrue(graph.step("prepare").readTargets().contains(9) && graph.target(9) != null, graph.snapshot());
        assertTrue(!analysis.report().program("prepare").deviations().contains("ADVANCED_RESOURCE_UNSUPPORTED"),
                analysis.report().program("prepare").deviations().toString());
    }

    /**
     * Spring's hand stores the held item's light colour to colorimg7 from its geometry stage under
     * COLORED_LIGHT and PATH_TRACING, and its deferred1 reads it back; Chimera refused the hand, so
     * the held item fell back. The hand binds colortex7 as a storage image at the slot it would
     * sample it through, in its geometry stage, and the target graph counts it as a world read.
     */
    @Test
    void aHandProgramStoresToAColourTarget(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_hand.vsh"), """
                #version 450 compatibility
                out vec2 v_texcoord;
                void main() {
                    v_texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.gsh"), """
                #version 450 compatibility
                layout(triangles) in;
                layout(triangle_strip, max_vertices = 3) out;
                layout(rgba16f) uniform image2D colorimg7;
                in vec2 v_texcoord[];
                out vec2 texcoord;
                void main() {
                    vec4 held = vec4(v_texcoord[0], 0.0, 1.0);
                    if (gl_in[0].gl_Position.x > 0.0) imageStore(colorimg7, ivec2(21, 266), held);
                    for (int i = 0; i < 3; ++i) {
                        texcoord = v_texcoord[i];
                        gl_Position = gl_in[i].gl_Position;
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_hand.fsh"), """
                #version 450 compatibility
                uniform sampler2D texture;
                in vec2 texcoord;
                void main() {
                    /* RENDERTARGETS: 0,4 */
                    gl_FragData[0] = texture2D(texture, texcoord);
                    gl_FragData[1] = vec4(1.0);
                }
                """);
        writeProgram(shaders, "deferred1", """
                #version 430 compatibility
                const int colortex7Format = RGBA16F;
                uniform sampler2D colortex7;
                void main() { gl_FragData[0] = texelFetch(colortex7, ivec2(21, 266), 0); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(plan.shouldAttempt("gbuffers_hand") && advanced.imagesServable("gbuffers_hand"),
                advanced.deviations().toString());
        PackAdvancedResourcePlan.ProgramBindingLayout layout = advanced.bindingLayout("gbuffers_hand");
        PackAdvancedResourcePlan.GraphicsImageBinding binding = layout.storageImages().get(0);
        assertEquals(List.of("colorimg7", "colortex7", "image2D", "rgba16f", 7, SelectorNamespace.colorTargetSlot(7)),
                List.of(binding.symbol(), binding.imageName(), binding.glslType(), binding.viewFormat(),
                        binding.target(), binding.selectorSlot()));
        PackProgramPlan hand = plan.program("gbuffers_hand");
        PackPipelines.PreparedPipeline prepared = PackPipelines.prepare(hand, advanced, UniformRegistry.Stage.HAND,
                PackFamilyPipelines.familyVertex(hand));
        PackPipelines.prepareLayout(prepared, hand.name(), advanced);
        String geometry = prepared.between().geometry();
        assertTrue(geometry.contains("layout(rgba16f, binding = " + layout.imageBase()
                + ") uniform image2D colorimg7;"), geometry);
        assertNull(PackCompileTest.compileBetween(geometry, "geometry"), geometry);
        ProgramImageBindingManifest.Entry entry = prepared.imageBindings().entries().stream()
                .filter(value -> value.sourceSymbol().equals("colorimg7")).findFirst().orElseThrow();
        assertEquals(List.of(ProgramImageBindingManifest.Kind.TARGET_IMAGE, "colortex7", 3),
                List.of(entry.kind(), entry.resourceKey(), entry.descriptorType()));
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(plan.programs(), analysis.config(), 16, 16, 8, 16384);
        assertTrue(graph.geometryReads().contains(7) && graph.target(7) != null, graph.snapshot());
    }

    /**
     * A pass that also samples the target it stores to would read one image through two layouts,
     * and a format of another texel size has no view: both fall back with the image named. So does
     * a hand that also draws to the target, which would hold it as an attachment and a storage image
     * at once, and so do final and the other gbuffers programs, where Iris gives colorimgN too and
     * Chimera binds none yet.
     */
    @Test
    void colourImagesChimeraCannotBindFallBack(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        writeProgram(shaders, "composite", """
                #version 430 compatibility
                uniform sampler2D colortex4;
                layout(rgba8) uniform image2D colorimg4;
                void main() {
                    imageStore(colorimg4, ivec2(0), texelFetch(colortex4, ivec2(1), 0));
                    gl_FragData[0] = vec4(1.0);
                }
                """);
        writeProgram(shaders, "composite1", """
                #version 430 compatibility
                layout(rgba16f) uniform writeonly image2D colorimg5;
                void main() { imageStore(colorimg5, ivec2(0), vec4(1.0)); gl_FragData[0] = vec4(1.0); }
                """);
        writeProgram(shaders, "final", """
                #version 430 compatibility
                layout(rgba8) uniform writeonly image2D colorimg2;
                void main() { imageStore(colorimg2, ivec2(0), vec4(1.0)); gl_FragColor = vec4(1.0); }
                """);
        writeProgram(shaders, "gbuffers_hand", """
                #version 430 compatibility
                layout(rgba8) uniform writeonly image2D colorimg6;
                void main() {
                    imageStore(colorimg6, ivec2(0), vec4(1.0));
                    /* RENDERTARGETS: 0,6 */
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[1] = vec4(1.0);
                }
                """);
        writeProgram(shaders, "gbuffers_terrain", """
                #version 430 compatibility
                layout(rgba8) uniform writeonly image2D colorimg3;
                void main() { imageStore(colorimg3, ivec2(0), vec4(1.0)); gl_FragData[0] = vec4(1.0); }
                """);
        PackAdvancedResourcePlan advanced = PackProbe.analyze(root).plan().advancedResources();
        List<String> deviations = advanced.deviations();
        assertTrue(deviations.contains("TARGET_IMAGE_ALSO_SAMPLED:composite:colortex4")
                && !advanced.imagesServable("composite"), deviations.toString());
        assertTrue(deviations.contains("ADVANCED_IMAGE_FORMAT_INCOMPATIBLE:colorimg5:rgba16f")
                && !advanced.imagesServable("composite1"), deviations.toString());
        assertTrue(deviations.contains("ADVANCED_IMAGE_UNSERVED:final:colorimg2"), deviations.toString());
        assertTrue(deviations.contains("TARGET_IMAGE_ALSO_DRAWN:gbuffers_hand:colortex6")
                && !advanced.imagesServable("gbuffers_hand"), deviations.toString());
        assertTrue(deviations.contains("ADVANCED_IMAGE_UNSERVED:gbuffers_terrain:colorimg3"), deviations.toString());
    }

    /**
     * Rethinking Voxels' prepare1 names colortex3 in its directive, writes no output, and only
     * stores to colorimg9. Iris flips colortex3 anyway, onto the other side it cleared too, so the
     * flip hands back what the frame began with: the target is unwritten (KNOW-859). Read as
     * written, Chimera made last frame's uncleared side current. A write the scan cannot place,
     * through a variable gl_FragData index, still reads as the first target's.
     */
    @Test
    void aPassThatOnlyDiscardsLeavesItsTargetsUnwritten(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        writeProgram(shaders, "prepare1", """
                #version 430 compatibility
                const int colortex9Format = R32UI;
                layout(r32ui) uniform uimage2D colorimg9;
                void main() {
                    imageAtomicMin(colorimg9, ivec2(gl_FragCoord.xy), 7u);
                    /*DRAWBUFFERS:3*/
                    discard;
                }
                """);
        writeProgram(shaders, "prepare2", """
                #version 120
                /* DRAWBUFFERS:3 */
                void main() { for (int i = 0; i < 1; i++) gl_FragData[i] = vec4(1.0); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(analysis.plan().programs(), analysis.config(),
                16, 16, 8, 16384);
        assertEquals(List.of(3), graph.step("prepare1").unwrittenTargets(), graph.snapshot());
        assertEquals(List.of(3), graph.step("prepare1").outputTargets(), graph.snapshot());
        assertEquals(List.of(), graph.step("prepare2").unwrittenTargets(), graph.snapshot());
    }

    private static void writeProgram(Path shaders, String program, String fragment) throws Exception {
        Files.writeString(shaders.resolve(program + ".vsh"),
                "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(shaders.resolve(program + ".fsh"), fragment);
    }
}
