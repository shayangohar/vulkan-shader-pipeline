package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

/**
 * A program's samplers take the bindings of its descriptor layout, whatever their type. Alpha
 * Piscium's shadow fragment reads its PBR tables through usampler1D custom textures; left at the
 * converter's provisional binding, usam_pbrLUT1 shared binding 5 with the GlobalData storage
 * buffer, and the layout check stopped the game (DESCRIPTOR_LAYOUT_MISMATCH:usam_pbrLUT1).
 */
final class PackSamplerBindingTest {
    @Test
    void aOneDimensionalIntegerSamplerTakesItsLayoutBinding() {
        String fragment = """
                #version 460
                layout(binding = 5) uniform usampler1D usam_lut;
                layout(binding = 6) uniform isampler2D usam_ids;
                layout(location = 0) out vec4 color;
                void main() {
                    color = vec4(texelFetch(usam_lut, 0, 0).r, texelFetch(usam_ids, ivec2(0), 0).r, 0.0, 1.0);
                }
                """;
        UniformRegistry.ProgramInterface plan = UniformRegistry.planProgram(fragment, null, null,
                UniformRegistry.Stage.SHADOW, null, true, Map.of(), Map.of("usam_lut", 16, "usam_ids", 17))
                .project("fragment", UniformRegistry.Stage.SHADOW);
        List<PackAdvancedResourcePlan.DescriptorBinding> samplers = List.of(
                new PackAdvancedResourcePlan.DescriptorBinding("Sampler16", 0, 3, 1, 63, "resource:16:1"),
                new PackAdvancedResourcePlan.DescriptorBinding("Sampler17", 0, 4, 1, 63, "resource:17:1"));
        PackAdvancedResourcePlan.ProgramBindingLayout layout = new PackAdvancedResourcePlan.ProgramBindingLayout(
                1, 1, 1, List.of(), List.of(), List.of(), samplers, samplers);
        String bound = PackDescriptorContracts.bindPackShader(fragment, "shadow", PackAdvancedResourcePlan.empty(),
                layout, plan, UniformRegistry.Stage.SHADOW, VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(bound.contains("layout(binding = 3) uniform usampler1D usam_lut;")
                && bound.contains("layout(binding = 4) uniform isampler2D usam_ids;"), bound);
    }
}
