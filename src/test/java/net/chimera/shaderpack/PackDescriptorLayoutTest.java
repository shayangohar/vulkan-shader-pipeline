package net.chimera.shaderpack;

import com.google.gson.JsonParser;
import net.chimera.render.shader.ChimeraShaderLoader;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

/**
 * Chimera's pipeline configs parse into the layouts VulkanMod's PipelineConfig.fromJson read from them,
 * and a pack program's samplers take its layout's bindings.
 */
final class PackDescriptorLayoutTest {
    private static final int VERTEX = 1;
    private static final int FRAGMENT = 16;
    private static final int ALL_GRAPHICS = 0x1F;

    @Test
    void terrainConfig() {
        var layout = PackDescriptorLayout.fromJson(ChimeraShaderLoader.loadJson("chimera_terrain.json"));
        var blocks = layout.uniformBlocks();
        assertEquals(List.of(0, 1), blocks.stream().map(PackDescriptorLayout.UniformBlock::binding).toList());
        assertEquals(List.of(VERTEX, ALL_GRAPHICS),
                blocks.stream().map(PackDescriptorLayout.UniformBlock::stages).toList());
        assertEquals(List.of(new PackDescriptorLayout.Field("mat4", "MVP"),
                new PackDescriptorLayout.Field("mat4", "LightMVP"),
                new PackDescriptorLayout.Field("vec4", "ChimeraCameraFraction")), blocks.get(0).fields());
        assertEquals(List.of(new PackDescriptorLayout.Field("vec4", "FogColor"),
                new PackDescriptorLayout.Field("float", "FogStart"), new PackDescriptorLayout.Field("float", "FogEnd"),
                new PackDescriptorLayout.Field("float", "AlphaCutout")), blocks.get(1).fields());
        assertEquals(List.of(new PackDescriptorLayout.ImageSlot(2, "sampler2D", "Sampler0", 0),
                new PackDescriptorLayout.ImageSlot(3, "sampler2D", "Sampler2", 2),
                new PackDescriptorLayout.ImageSlot(4, "sampler2D", "Sampler3", 3)), layout.images(),
                "samplers take the bindings after the last block");
        assertEquals(new PackDescriptorLayout.UniformBlock(0, VERTEX, 0,
                List.of(new PackDescriptorLayout.Field("vec3", "ChunkOffset"))), layout.pushConstants());
    }

    @Test
    void pushConstantsAndManualBlocks() {
        var none = PackDescriptorLayout.fromJson(JsonParser.parseString("{\"samplers\":[{\"name\":\"Sampler14\"}]}")
                .getAsJsonObject());
        assertNull(none.pushConstants(), "a config without PushConstants pushes nothing");
        assertEquals(List.of(new PackDescriptorLayout.ImageSlot(0, "sampler2D", "Sampler14", 14)), none.images());
        var empty = PackDescriptorLayout.fromJson(JsonParser.parseString(
                "{\"PushConstants\":[],\"ManualUBOs\":[{\"binding\":4,\"type\":\"fragment\",\"size\":64}]}")
                .getAsJsonObject());
        assertEquals(PackDescriptorLayout.EMPTY_PUSH_CONSTANTS, empty.pushConstants(),
                "an empty PushConstants array still declares a block");
        assertEquals(List.of(new PackDescriptorLayout.UniformBlock(4, FRAGMENT, 64, List.of())), empty.uniformBlocks());
        assertThrows(IllegalArgumentException.class, () -> PackDescriptorLayout.fromJson(JsonParser.parseString(
                "{\"samplers\":[{\"name\":\"DiffuseSampler\"}]}").getAsJsonObject()));
    }

    /**
     * A program's samplers take the bindings of its descriptor layout, whatever their type. Alpha
     * Piscium's shadow fragment reads its PBR tables through usampler1D custom textures; left at the
     * converter's provisional binding, usam_pbrLUT1 shared binding 5 with the GlobalData storage
     * buffer, and the layout check stopped the game (DESCRIPTOR_LAYOUT_MISMATCH:usam_pbrLUT1).
     */
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
