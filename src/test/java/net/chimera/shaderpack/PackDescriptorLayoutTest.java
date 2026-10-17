package net.chimera.shaderpack;

import com.google.gson.JsonParser;
import net.chimera.render.shader.ChimeraShaderLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Chimera's pipeline configs parse into the layouts VulkanMod's PipelineConfig.fromJson read from them. */
final class PackDescriptorLayoutTest {
    private static final int VERTEX = 1;
    private static final int FRAGMENT = 16;
    private static final int ALL_GRAPHICS = 0x1F;

    @Test
    void terrainConfig() {
        var layout = PackDescriptorLayout.fromJson(ChimeraShaderLoader.loadJson("chimera_terrain.json"));
        var blocks = layout.uniformBlocks();
        assertEquals(List.of(0, 1, 2), blocks.stream().map(PackDescriptorLayout.UniformBlock::binding).toList());
        assertEquals(List.of(VERTEX, ALL_GRAPHICS, VERTEX),
                blocks.stream().map(PackDescriptorLayout.UniformBlock::stages).toList());
        assertEquals(List.of(new PackDescriptorLayout.Field("mat4", "MVP"),
                new PackDescriptorLayout.Field("mat4", "LightMVP")), blocks.get(0).fields());
        assertEquals(List.of("vec4", "float", "float", "float", "float", "float", "float", "float", "ivec2", "vec2",
                "int"), blocks.get(1).fields().stream().map(PackDescriptorLayout.Field::type).toList());
        assertEquals(4096, blocks.get(2).size(), "a block without fields keeps its byte size");
        assertEquals(List.of(), blocks.get(2).fields());
        assertEquals(List.of(new PackDescriptorLayout.ImageSlot(3, "sampler2D", "Sampler0", 0),
                new PackDescriptorLayout.ImageSlot(4, "sampler2D", "Sampler2", 2),
                new PackDescriptorLayout.ImageSlot(5, "sampler2D", "Sampler3", 3)), layout.images(),
                "samplers take the bindings after the last block");
        assertEquals(new PackDescriptorLayout.UniformBlock(0, VERTEX, 0,
                List.of(new PackDescriptorLayout.Field("vec3", "ModelOffset"))), layout.pushConstants());
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
}
