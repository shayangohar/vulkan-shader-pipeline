package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

/** Iris's colorimgN in the post passes: the colour target as a storage image (TargetImages). */
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
     * A pass that also samples the target it stores to would read one image through two layouts,
     * and a format of another texel size has no view: both fall back with the image named. So do
     * final and gbuffers programs, where Iris gives colorimgN too and Chimera binds none yet.
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
        PackAdvancedResourcePlan advanced = PackProbe.analyze(root).plan().advancedResources();
        List<String> deviations = advanced.deviations();
        assertTrue(deviations.contains("TARGET_IMAGE_ALSO_SAMPLED:composite:colortex4")
                && !advanced.imagesServable("composite"), deviations.toString());
        assertTrue(deviations.contains("ADVANCED_IMAGE_FORMAT_INCOMPATIBLE:colorimg5:rgba16f")
                && !advanced.imagesServable("composite1"), deviations.toString());
        assertTrue(deviations.contains("ADVANCED_IMAGE_UNSERVED:final:colorimg2"), deviations.toString());
    }

    private static void writeProgram(Path shaders, String program, String fragment) throws Exception {
        Files.writeString(shaders.resolve(program + ".vsh"),
                "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
        Files.writeString(shaders.resolve(program + ".fsh"), fragment);
    }
}
