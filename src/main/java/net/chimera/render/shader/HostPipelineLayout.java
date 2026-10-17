package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.shaderpack.PackDescriptorLayout;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.shader.layout.AlignedStruct;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds the host renderer's descriptors from a Chimera {@link PackDescriptorLayout}: the one place
 * a pipeline layout meets VulkanMod's pipeline builder.
 */
public final class HostPipelineLayout {
    private HostPipelineLayout() {}

    /**
     * Adds the layout's uniform blocks, image descriptors and push constants to the builder. With
     * {@code semanticTransformBlocks}, the blocks at the transform bindings take the host names the
     * family bridge looks up.
     */
    public static void apply(Pipeline.Builder builder, PackDescriptorLayout layout, boolean semanticTransformBlocks) {
        builder.applyConfig(config(layout));
        if (semanticTransformBlocks) {
            builder.setUniforms(namedTransformBlocks(builder.getUBOs()), builder.getImageDescriptors());
        }
    }

    /**
     * Builds one of Chimera's own pipelines, named {@code name}, from a config in
     * assets/chimera/shaders: the config's layout and shader paths, the vertex source passed
     * through {@code vertexSource}. With {@code globalBuffers} its blocks read the host's global
     * uniform buffer, where VulkanMod's world renderer writes per-section data.
     */
    public static GraphicsPipeline create(String name, String configName, VertexFormat format,
                                          UnaryOperator<String> vertexSource, boolean globalBuffers) {
        JsonObject json = ChimeraShaderLoader.loadJson(configName + ".json");
        Pipeline.Builder builder = new Pipeline.Builder(format, name);
        apply(builder, PackDescriptorLayout.fromJson(json), false);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,
                vertexSource.apply(ChimeraShaderLoader.loadSource(json.get("vertex").getAsString() + ".vsh")));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER,
                ChimeraShaderLoader.loadSource(json.get("fragment").getAsString() + ".fsh"));
        GraphicsPipeline pipeline = builder.createGraphicsPipeline();
        if (globalBuffers) {
            for (var buffer : pipeline.getBuffers()) buffer.setUseGlobalBuffer(true);
        }
        return pipeline;
    }

    private static PipelineConfig config(PackDescriptorLayout layout) {
        PipelineConfig.Builder config = PipelineConfig.builder();
        for (var block : layout.uniformBlocks()) config.addUB(block(block));
        for (var image : layout.images()) {
            config.addImageDescriptor(image.binding(), image.type(), image.name(), image.textureSlot());
        }
        if (layout.pushConstants() != null) config.setPushConstants(block(layout.pushConstants()));
        return config.build();
    }

    private static PipelineConfig.UB block(PackDescriptorLayout.UniformBlock block) {
        var builder = PipelineConfig.UB.builder(block.binding(), block.stages());
        for (var field : block.fields()) builder.addUniform(field.type(), field.name());
        if (block.fields().isEmpty()) builder.setSize(block.size());
        return builder.build();
    }

    /**
     * Rebuilds the host transform blocks under the names the bridge looks up.
     *
     * <p>VulkanMod names config-declared blocks {@code "UBO: <binding>"}, while
     * its reflected host pipelines carry the semantic block names of their GLSL
     * and {@code Pipeline.getUBO(String)} compares names exactly. The converted
     * family preambles declare {@code DynamicTransforms} at binding 0 and
     * {@code Projection} at binding 1, so a guarded family pipeline must carry
     * those names too or the host per-draw slices can never be bound.</p>
     */
    private static List<UBO> namedTransformBlocks(List<UBO> blocks) {
        List<UBO> named = new ArrayList<>(blocks.size());
        for (UBO block : blocks) {
            String name = switch (block.binding) {
                case UniformRegistry.DYNAMIC_TRANSFORMS_BINDING -> UniformRegistry.DYNAMIC_TRANSFORMS_UBO;
                case UniformRegistry.PROJECTION_BINDING -> UniformRegistry.PROJECTION_UBO;
                default -> null;
            };
            named.add(name == null ? block : namedCopy(block, name));
        }
        return named;
    }

    /** Rebuilds one block under a new name, keeping its fields, suppliers, and buffer state. */
    private static UBO namedCopy(UBO block, String name) {
        var uniforms = AlignedStruct.builder();
        for (var uniform : block.getUniforms()) {
            uniforms.addUniform(uniform.getInfo());
        }
        UBO copy = uniforms.buildUBO(name, block.binding, block.stages);
        copy.setUseGlobalBuffer(block.useGlobalBuffer());
        return copy;
    }
}
