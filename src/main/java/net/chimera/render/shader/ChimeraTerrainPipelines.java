package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.minecraft.client.Minecraft;
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
 * Enabling also swaps in chimera's extended terrain vertex format (adds a
 * per-vertex BlockId attribute) and builder constructor, then forces a chunk
 * rebuild so live sections re-mesh against the new layout. Disabling restores
 * every piece of host state.
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
        rebuildChunks();
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

    public static void resumeFromScreens() {
        enable();
    }

    public static void disable() {
        PipelineManager.setDefaultTerrainShaderGetter();
        PipelineManager.setTerrainVertexFormat(CustomVertexFormat.COMPRESSED_TERRAIN);
        ThreadBuilderPack.defaultTerrainBuilderConstructor();
        rebuildChunks();
    }

    private static void rebuildChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
        }
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
