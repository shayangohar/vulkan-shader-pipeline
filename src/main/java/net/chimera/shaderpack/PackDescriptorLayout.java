package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * One pipeline's whole descriptor set: its uniform blocks, storage buffers, image descriptors and
 * push constants. Chimera owns it, so pack plans and Chimera's own pipeline configs never name a
 * host renderer type; the mod builds the host's descriptors from it ({@code HostPipelineLayout}),
 * one host descriptor per entry, and checks the shaders against {@link #descriptorBindings()}.
 *
 * @param pushConstants the push-constant block, or null when the pipeline declares none. A config
 *                      that lists an empty "PushConstants" array still has a (zero-field) block.
 */
public record PackDescriptorLayout(List<UniformBlock> uniformBlocks, List<StorageBuffer> storageBuffers,
                                   List<ImageSlot> images, UniformBlock pushConstants) {
    public static final PackDescriptorLayout EMPTY = new PackDescriptorLayout(List.of(), List.of(), null);
    private static final Pattern SAMPLER_NAME = Pattern.compile("Sampler(\\d+)");
    /** The host binds images to every graphics and compute stage. */
    private static final int IMAGE_STAGES = VK_SHADER_STAGE_ALL_GRAPHICS | VK_SHADER_STAGE_COMPUTE_BIT;
    /** What an empty "PushConstants" array declares: a vertex-stage block with no fields. */
    public static final UniformBlock EMPTY_PUSH_CONSTANTS =
            new UniformBlock(0, VK_SHADER_STAGE_VERTEX_BIT, 0, List.of());

    public PackDescriptorLayout {
        uniformBlocks = List.copyOf(uniformBlocks);
        storageBuffers = List.copyOf(storageBuffers);
        images = List.copyOf(images);
    }

    /** A layout without storage buffers. */
    public PackDescriptorLayout(List<UniformBlock> uniformBlocks, List<ImageSlot> images, UniformBlock pushConstants) {
        this(uniformBlocks, List.of(), images, pushConstants);
    }

    /** A uniform block: its fields in declaration order, or only a byte size when it declares none. */
    public record UniformBlock(int binding, int stages, int size, List<Field> fields) {
        public UniformBlock {
            fields = List.copyOf(fields);
        }
    }

    /** One block field, typed in the host's vocabulary: float, vec2-4, int, ivec2-4 or mat4. */
    public record Field(String type, String name) {}

    /** A pack storage buffer (Iris SSBO): its binding, stages, byte range and the pack's buffer index. */
    public record StorageBuffer(int binding, int stages, int size, int logicalIndex) {}

    /** How a shader reaches an image. */
    public enum ImageAccess {
        /** A combined image sampler over an image in the shader-read layout. */
        SAMPLED,
        /** A combined image sampler over an image kept in the general layout, as pack storage images are. */
        SAMPLED_GENERAL,
        /** A storage image (imageLoad and imageStore), in the general layout. */
        STORAGE
    }

    /** An image descriptor: its binding, GLSL type (sampler2D, image3D...), name, texture slot and access. */
    public record ImageSlot(int binding, String type, String name, int textureSlot, ImageAccess access) {
        /** A sampled image in the shader-read layout. */
        public ImageSlot(int binding, String type, String name, int textureSlot) {
            this(binding, type, name, textureSlot, ImageAccess.SAMPLED);
        }
    }

    /** The same blocks, storage buffers and push constants with these image descriptors. */
    public PackDescriptorLayout withImages(List<ImageSlot> replacement) {
        return new PackDescriptorLayout(uniformBlocks, storageBuffers, replacement, pushConstants);
    }

    /** This layout plus a program's storage buffers and advanced images. */
    public PackDescriptorLayout withAdvanced(List<StorageBuffer> buffers, List<ImageSlot> advancedImages) {
        List<StorageBuffer> allBuffers = new ArrayList<>(storageBuffers);
        allBuffers.addAll(buffers);
        List<ImageSlot> allImages = new ArrayList<>(images);
        allImages.addAll(advancedImages);
        return new PackDescriptorLayout(uniformBlocks, allBuffers, allImages, pushConstants);
    }

    /**
     * Every descriptor as the shader and binding checks see it: its Vulkan type, stages and the
     * identity of the resource it carries (a uniform block's binding, a storage buffer's index, or
     * the texture slot an image named Sampler&lt;slot&gt; reads).
     */
    public List<PackAdvancedResourcePlan.DescriptorBinding> descriptorBindings() {
        List<PackAdvancedResourcePlan.DescriptorBinding> bindings = new ArrayList<>();
        for (UniformBlock block : uniformBlocks) {
            bindings.add(new PackAdvancedResourcePlan.DescriptorBinding("UBO" + block.binding(), 0,
                    block.binding(), VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, block.stages(),
                    "ubo:" + block.binding()));
        }
        for (StorageBuffer buffer : storageBuffers) {
            bindings.add(new PackAdvancedResourcePlan.DescriptorBinding("PackStorageBuffer" + buffer.logicalIndex(),
                    0, buffer.binding(), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC, buffer.stages(),
                    "storage:" + buffer.logicalIndex()));
        }
        for (ImageSlot image : images) {
            int type = descriptorType(image.access());
            bindings.add(new PackAdvancedResourcePlan.DescriptorBinding(image.name(), 0, image.binding(), type,
                    IMAGE_STAGES, imageIdentity(image.name(), type)));
        }
        return List.copyOf(bindings);
    }

    /** The Vulkan descriptor type an image access binds as. */
    public static int descriptorType(ImageAccess access) {
        return access == ImageAccess.STORAGE ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }

    /** An image named Sampler&lt;slot&gt; carries texture slot {@code slot}; any other name is its own identity. */
    private static String imageIdentity(String name, int type) {
        Matcher sampler = SAMPLER_NAME.matcher(name);
        return sampler.matches() ? "resource:" + sampler.group(1) + ":" + type : name;
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
        Matcher sampler = SAMPLER_NAME.matcher(name);
        if (!sampler.matches()) {
            throw new IllegalArgumentException("pipeline config sampler is not Sampler<slot>: " + name);
        }
        return Integer.parseInt(sampler.group(1));
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
