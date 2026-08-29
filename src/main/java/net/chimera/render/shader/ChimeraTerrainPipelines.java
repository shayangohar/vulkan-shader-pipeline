package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackMaterialResolver;
import net.minecraft.client.Minecraft;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.shader.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
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
 * Enabling swaps the terrain shader getter to chimera's pipeline and installs
 * the matching extended builder. Disabling restores the host getter, format,
 * and builder. A live level is rebuilt only when that format mode changes.
 */
public final class ChimeraTerrainPipelines {
    private static boolean initialized;
    private static boolean extendedMode;
    private static GraphicsPipeline terrainPipeline;
    private static PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    /** Pack geometry program (gbuffers_terrain) installed over the chimera terrain pipeline; null = none. */
    private static GraphicsPipeline geometryOverride;

    private ChimeraTerrainPipelines() {}

    /**
     * Installs/clears the pack geometry override. All terrain getter sites
     * route through getTerrainPipeline(), so the override composes with the
     * shadow-segment swap and its restore without touching ChimeraMainPass.
     */
    public static void setGeometryOverride(GraphicsPipeline pipeline) {
        geometryOverride = pipeline;
        ChimeraMod.LOGGER.info("[chimera] terrain override: {}", pipeline != null ? "installed" : "cleared");
        if (!initialized) {
            return;
        }
        // Re-register only while Chimera owns terrain. Host/screen mode must
        // keep the host getter even if an override is cleared or replaced.
        if (extendedMode) {
            PipelineManager.setShaderGetter(renderType -> getTerrainPipeline());
        }
    }

    public static void init() {
        if (initialized) {
            return;
        }

        terrainPipeline = buildPipeline("chimera_terrain", ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN);
        initialized = true;

        ChimeraMod.LOGGER.info("chimera terrain pipeline ready: stride={}B attributes={}",
                ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN.getVertexSize(),
                ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN.getElementAttributeNames());
    }

    public static void enable() {
        if (!initialized) {
            return;
        }

        setTerrainMode(true);
    }

    public static void disable() {
        setTerrainMode(false);
    }

    /**
     * Simple mode (vanilla screens): full host terrain, no redirect. Called
     * instead of disable() so the chimera terrain pipeline object survives.
     */
    public static void suspendForScreens() {
        if (!initialized) {
            return;
        }

        setTerrainMode(false);
    }

    public static GraphicsPipeline getTerrainPipeline() {
        return geometryOverride != null ? geometryOverride : terrainPipeline;
    }

    public static VertexFormat getTerrainVertexFormat() {
        return ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN;
    }

    public static void setMaterialResolver(PackMaterialResolver resolver) {
        PackMaterialResolver next = resolver == null ? PackMaterialResolver.empty() : resolver;
        if (materialResolver == next) {
            return;
        }
        materialResolver = next;
        if (extendedMode) {
            rebuildLiveLevel();
        }
    }

    public static boolean isExtendedMode() {
        return extendedMode;
    }

    private static void setTerrainMode(boolean chimeraMode) {
        VertexFormat desiredFormat = chimeraMode
                ? ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN
                : CustomVertexFormat.COMPRESSED_TERRAIN;
        boolean changed = extendedMode != chimeraMode
                || PipelineManager.terrainVertexFormat != desiredFormat;

        PipelineManager.setTerrainVertexFormat(desiredFormat);
        if (chimeraMode) {
            ThreadBuilderPack.setTerrainBuilderConstructor(renderType -> {
                int size = TerrainRenderType.getLayer(renderType).bufferSize()
                        / DefaultVertexFormat.BLOCK.getVertexSize();
                return new ChimeraExtTerrainBuilder(size, materialResolver);
            });
            PipelineManager.setShaderGetter(renderType -> getTerrainPipeline());
        } else {
            ThreadBuilderPack.defaultTerrainBuilderConstructor();
            PipelineManager.setDefaultTerrainShaderGetter();
        }
        extendedMode = chimeraMode;

        if (changed) {
            rebuildLiveLevel();
        }
    }

    private static void rebuildLiveLevel() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
            ChimeraMod.LOGGER.info("[chimera] terrain format changed: live level rebuilt (extended={})", extendedMode);
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
