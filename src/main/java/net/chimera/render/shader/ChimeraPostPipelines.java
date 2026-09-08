package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

/**
 * Builds chimera's fullscreen post-processing pipelines (composite, present)
 * from assets/chimera/shaders/. These use CustomVertexFormat.NONE: a
 * three-vertex triangle generated in the vertex stage, no vertex buffers.
 */
public final class ChimeraPostPipelines {
    private ChimeraPostPipelines() {}

    public static GraphicsPipeline create(String name) {
        JsonObject json = ChimeraShaderLoader.loadJson(name + ".json");
        PipelineConfig config = PipelineConfig.fromJson(name, json);

        Pipeline.Builder builder = new Pipeline.Builder((VertexFormat) CustomVertexFormat.NONE, name);
        builder.applyConfig(config);

        String vertexPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER);
        String fragmentPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ChimeraShaderLoader.loadSource(vertexPath + ".vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ChimeraShaderLoader.loadSource(fragmentPath + ".fsh"));

        return builder.createGraphicsPipeline();
    }

    /** Creates the one internal reversed-Z depth conversion pipeline. */
    public static GraphicsPipeline createDepthPipeline() {
        return create("chimera_depth");
    }

    /** Creates the M8.2 host-to-pack scene seed pipeline. */
    public static GraphicsPipeline createSceneSeedPipeline() {
        return create("chimera_scene_seed");
    }

    /** Creates an extended terrain-format pipeline for shadow rendering. */
    public static GraphicsPipeline createTerrainPipeline(String name, VertexFormat vertexFormat) {
        JsonObject json = ChimeraShaderLoader.loadJson(name + ".json");
        PipelineConfig config = PipelineConfig.fromJson(name, json);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, name);
        builder.applyConfig(config);

        String vertexPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER);
        String fragmentPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ChimeraShaderLoader.loadSource(vertexPath + ".vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ChimeraShaderLoader.loadSource(fragmentPath + ".fsh"));

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();

        // Section-offset UBO (binding 2) must read from the global buffer
        // that VulkanMod's WorldRenderer writes per chunk area.
        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }
}
