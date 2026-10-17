package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * One pipeline's descriptor layout: its uniform blocks, image descriptors and push constants.
 * Chimera owns it, so pack plans and Chimera's own pipeline configs never name a host renderer
 * type; the mod builds the host's descriptors from it ({@code HostPipelineLayout}).
 *
 * @param pushConstants the push-constant block, or null when the pipeline declares none. A config
 *                      that lists an empty "PushConstants" array still has a (zero-field) block.
 */
public record PackDescriptorLayout(List<UniformBlock> uniformBlocks, List<ImageSlot> images,
                                   UniformBlock pushConstants) {
    public static final PackDescriptorLayout EMPTY = new PackDescriptorLayout(List.of(), List.of(), null);
    /** What an empty "PushConstants" array declares: a vertex-stage block with no fields. */
    public static final UniformBlock EMPTY_PUSH_CONSTANTS =
            new UniformBlock(0, VK_SHADER_STAGE_VERTEX_BIT, 0, List.of());

    public PackDescriptorLayout {
        uniformBlocks = List.copyOf(uniformBlocks);
        images = List.copyOf(images);
    }

    /** A uniform block: its fields in declaration order, or only a byte size when it declares none. */
    public record UniformBlock(int binding, int stages, int size, List<Field> fields) {
        public UniformBlock {
            fields = List.copyOf(fields);
        }
    }

    /** One block field, typed in the host's vocabulary: float, vec2-4, int, ivec2-4 or mat4. */
    public record Field(String type, String name) {}

    /** An image descriptor: its binding, GLSL type (sampler2D or image2D), name and texture slot. */
    public record ImageSlot(int binding, String type, String name, int textureSlot) {}

    /** The same blocks and push constants with these image descriptors. */
    public PackDescriptorLayout withImages(List<ImageSlot> replacement) {
        return new PackDescriptorLayout(uniformBlocks, replacement, pushConstants);
    }

    /** The binding after the highest uniform block. */
    public int bindingAfterBlocks() {
        return uniformBlocks.stream().mapToInt(block -> block.binding() + 1).max().orElse(0);
    }

    /**
     * Parses a pipeline config the way VulkanMod's PipelineConfig.fromJson does: "UBOs" in order,
     * then the first "ManualUBOs" entry, then "samplers" on the bindings after the last UBO, then
     * "PushConstants" as one vertex-stage block. A sampler is named Sampler&lt;slot&gt;.
     */
    public static PackDescriptorLayout fromJson(JsonObject config) {
        List<UniformBlock> blocks = new ArrayList<>();
        int nextBinding = 0;
        for (JsonElement element : array(config, "UBOs")) {
            UniformBlock block = uniformBlock(element.getAsJsonObject());
            blocks.add(block);
            nextBinding = block.binding() + 1;
        }
        JsonArray manual = array(config, "ManualUBOs");
        if (!manual.isEmpty()) {
            JsonObject block = manual.get(0).getAsJsonObject();
            blocks.add(new UniformBlock(block.get("binding").getAsInt(), stages(block.get("type").getAsString()),
                    block.get("size").getAsInt(), List.of()));
        }
        List<ImageSlot> images = new ArrayList<>();
        for (JsonElement element : array(config, "samplers")) {
            String name = element.getAsJsonObject().get("name").getAsString();
            images.add(new ImageSlot(nextBinding++, "sampler2D", name, samplerSlot(name)));
        }
        UniformBlock pushConstants = config.has("PushConstants")
                ? new UniformBlock(0, VK_SHADER_STAGE_VERTEX_BIT, 0, fields(config.getAsJsonArray("PushConstants")))
                : null;
        return new PackDescriptorLayout(blocks, images, pushConstants);
    }

    /** The texture slot a config sampler named Sampler&lt;slot&gt; reads. */
    static int samplerSlot(String name) {
        if (name.startsWith("Sampler")) {
            try {
                return Integer.parseInt(name.substring("Sampler".length()));
            } catch (NumberFormatException ignored) {
                // Falls through to the rejection below.
            }
        }
        throw new IllegalArgumentException("pipeline config sampler is not Sampler<slot>: " + name);
    }

    private static UniformBlock uniformBlock(JsonObject block) {
        int binding = block.get("binding").getAsInt();
        int stages = stages(block.get("type").getAsString());
        return block.has("fields")
                ? new UniformBlock(binding, stages, 0, fields(block.getAsJsonArray("fields")))
                : new UniformBlock(binding, stages, block.get("size").getAsInt(), List.of());
    }

    private static List<Field> fields(JsonArray fields) {
        List<Field> result = new ArrayList<>();
        for (JsonElement element : fields) {
            JsonObject field = element.getAsJsonObject();
            result.add(new Field(fieldType(field.get("type").getAsString(), field.get("count").getAsInt()),
                    field.get("name").getAsString()));
        }
        return result;
    }

    /** A config field's JSON type and component count as the host type name. */
    static String fieldType(String type, int count) {
        return switch (type) {
            case "matrix4x4" -> "mat4";
            case "float" -> count == 1 ? "float" : vector("vec", count);
            case "int" -> count == 1 ? "int" : vector("ivec", count);
            default -> throw new IllegalArgumentException("unsupported pipeline config field type: " + type);
        };
    }

    private static String vector(String prefix, int count) {
        if (count < 2 || count > 4) throw new IllegalArgumentException("unsupported field component count: " + count);
        return prefix + count;
    }

    static int stages(String type) {
        return switch (type) {
            case "vertex" -> VK_SHADER_STAGE_VERTEX_BIT;
            case "fragment" -> VK_SHADER_STAGE_FRAGMENT_BIT;
            case "all" -> VK_SHADER_STAGE_ALL_GRAPHICS;
            case "compute" -> VK_SHADER_STAGE_COMPUTE_BIT;
            default -> throw new IllegalArgumentException("unsupported pipeline config stage: " + type);
        };
    }

    private static JsonArray array(JsonObject config, String member) {
        return config.has(member) ? config.getAsJsonArray(member) : new JsonArray();
    }
}
