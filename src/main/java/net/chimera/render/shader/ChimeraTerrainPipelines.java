package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.PipelineConfig;
import net.vulkanmod.vulkan.shader.SPIRVUtils;

/**
 * Builds and installs chimera's terrain pipelines.
 *
 * The GLSL/JSON live under /assets/chimera/shaders/ and declare the same
 * bindings and UBO fields as the host's terrain path, so the host's uniform
 * suppliers and per-instance section data keep feeding them unchanged.
 *
 * Enabling swaps the terrain shader getter to chimera's pipeline. Both
 * sides of the swap use the same 16B COMPRESSED_TERRAIN vertex format
 * and the default builder, so no chunk re-mesh is needed; reinstate one
 * only if the vertex format ever changes again (TASK-49's BlockId
 * extension). Disabling restores the host getter.
 */
public final class ChimeraTerrainPipelines {
    private static boolean initialized;
    private static GraphicsPipeline terrainPipeline;

    private ChimeraTerrainPipelines() {}

    public static void init() {
        if (initialized) {
            return;
        }

        // BISECTION 2: plain compressed format (BlockId extension paused).
        terrainPipeline = buildPipeline("chimera_terrain", CustomVertexFormat.COMPRESSED_TERRAIN);
        initialized = true;

        ChimeraMod.LOGGER.info("chimera terrain pipeline ready: stride={}B attributes={}",
                CustomVertexFormat.COMPRESSED_TERRAIN.getVertexSize(),
                CustomVertexFormat.COMPRESSED_TERRAIN.getElementAttributeNames());
    }

    public static void enable() {
        if (!initialized) {
            return;
        }

        PipelineManager.setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
        PipelineManager.setShaderGetter(renderType -> terrainPipeline);
        // No rebuildChunks() here: both sides of the swap use the same
        // 16B COMPRESSED_TERRAIN format and the default builder, so only
        // the shader getter changes. A re-mesh (allChanged) would rebuild
        // the SectionGraph on every screen cycle for zero visual gain.
        // Reinstate it only when the vertex format actually changes
        // (TASK-49's BlockId extension).
    }

    public static void disable() {
        PipelineManager.setDefaultTerrainShaderGetter();
        PipelineManager.setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
        // See enable(): no re-mesh needed while the format is unchanged.
    }

    /**
     * Simple mode (vanilla screens): full host terrain, no redirect. Called
     * instead of disable() so the chimera terrain pipeline object survives.
     */
    public static void suspendForScreens() {
        if (!initialized) {
            return;
        }

        PipelineManager.setDefaultTerrainShaderGetter();
        PipelineManager.setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
    }

    public static GraphicsPipeline getTerrainPipeline() {
        return terrainPipeline;
    }

    public static VertexFormat getTerrainVertexFormat() {
        return CustomVertexFormat.COMPRESSED_TERRAIN;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static GraphicsPipeline buildPipeline(String name, VertexFormat vertexFormat) {
        JsonObject json = ChimeraShaderLoader.loadJson(name + ".json");
        PipelineConfig config = PipelineConfig.fromJson(name, json);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, name);
        builder.applyConfig(config);

        String vertexPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER);
        String fragmentPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER, ChimeraShaderLoader.loadSource(vertexPath + ".vsh"));
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ChimeraShaderLoader.loadSource(fragmentPath + ".fsh"));

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();

        // Share the host's global UBO pool so per-instance section data flows in unchanged.
        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }
}
