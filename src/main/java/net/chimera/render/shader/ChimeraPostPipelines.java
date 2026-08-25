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

    /** Creates a terrain-format pipeline (COMPRESSED_TERRAIN vertex layout) for shadow rendering. */
    public static GraphicsPipeline createTerrainPipeline(String name, VertexFormat vertexFormat) {
        JsonObject json = ChimeraShaderLoader.loadJson(name + ".json");
        PipelineConfig config = PipelineConfig.fromJson(name, json);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, name);
        builder.applyConfig(config);

        String vertexPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER);
        String fragmentPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ChimeraShaderLoader.loadSource(vertexPath + ".vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ChimeraShaderLoader.loadSource(fragmentPath + ".fsh"));

        return builder.createGraphicsPipeline();
    }
}
