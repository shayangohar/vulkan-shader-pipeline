package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
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
    private static GraphicsPipeline modernTerrainPipeline;
    private static GraphicsPipeline modernTerrainAoPipeline;
    private static PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    private static TerrainMaterialPlan materialPlan = TerrainMaterialPlan.legacy();
    /** Pack geometry program (gbuffers_terrain) installed over the chimera terrain pipeline; null = none. */
    private static GraphicsPipeline geometryOverride;
    /** Pack water program installed only over the host translucent terrain lane; null = none. */
    private static GraphicsPipeline translucentOverride;

    private ChimeraTerrainPipelines() {}

    /**
     * Installs/clears the pack geometry override. All terrain getter sites
     * route through getTerrainPipeline(renderType), so the override composes with the
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
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
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
        return geometryOverride != null ? geometryOverride : fixedPipeline();
    }

    /** Selects the family override while preserving the fixed Chimera fallback. */
    public static GraphicsPipeline getTerrainPipeline(TerrainRenderType renderType) {
        if (renderType == TerrainRenderType.TRANSLUCENT && translucentOverride != null) {
            return translucentOverride;
        }
        return renderType == TerrainRenderType.TRANSLUCENT ? fixedPipeline() : getTerrainPipeline();
    }

    public static VertexFormat getTerrainVertexFormat() {
        return ChimeraVertexFormats.terrainFormat(materialPlan);
    }

    /** Selects the one pack-wide append-only terrain layout before chunk rebuild. */
    public static void setMaterialPlan(TerrainMaterialPlan plan) {
        TerrainMaterialPlan next = plan == null ? TerrainMaterialPlan.legacy() : plan;
        if (materialPlan.equals(next)) {
            return;
        }
        materialPlan = next;
        if (initialized && extendedMode) {
            ensureFixedPipeline();
            setTerrainMode(true);
        }
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

    /** Installs/clears the pack water override without affecting opaque terrain. */
    public static void setTranslucentOverride(GraphicsPipeline pipeline) {
        translucentOverride = pipeline;
        ChimeraMod.LOGGER.info("[chimera] translucent override: {}",
                pipeline != null ? "installed" : "cleared");
        if (!initialized) {
            return;
        }
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    public static boolean isExtendedMode() {
        return extendedMode;
    }

    private static void setTerrainMode(boolean chimeraMode) {
        VertexFormat desiredFormat = chimeraMode
                ? ChimeraVertexFormats.terrainFormat(materialPlan)
                : CustomVertexFormat.COMPRESSED_TERRAIN;
        boolean changed = extendedMode != chimeraMode
                || PipelineManager.terrainVertexFormat != desiredFormat;

        PipelineManager.setTerrainVertexFormat(desiredFormat);
        if (chimeraMode) {
            ThreadBuilderPack.setTerrainBuilderConstructor(renderType -> {
                int size = TerrainRenderType.getLayer(renderType).bufferSize()
                        / DefaultVertexFormat.BLOCK.getVertexSize();
                return new ChimeraExtTerrainBuilder(size, materialResolver, materialPlan);
            });
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
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
        return buildPipeline(name, vertexFormat, name);
    }

    private static GraphicsPipeline buildPipeline(
            String name,
            VertexFormat vertexFormat,
            String shaderConfigName
    ) {
        JsonObject json = ChimeraShaderLoader.loadJson(shaderConfigName + ".json");
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

    private static void ensureFixedPipeline() {
        if (!initialized || !materialPlan.modern()) {
            return;
        }
        if (materialPlan.separateAo()) {
            if (modernTerrainAoPipeline == null) {
                modernTerrainAoPipeline = buildPipeline("chimera_terrain_modern_ao",
                        ChimeraVertexFormats.MODERN_COMPRESSED_TERRAIN_AO, "chimera_terrain");
            }
        } else if (modernTerrainPipeline == null) {
            modernTerrainPipeline = buildPipeline("chimera_terrain_modern",
                    ChimeraVertexFormats.MODERN_COMPRESSED_TERRAIN, "chimera_terrain");
        }
    }

    private static GraphicsPipeline fixedPipeline() {
        ensureFixedPipeline();
        if (materialPlan.modern()) {
            return materialPlan.separateAo() ? modernTerrainAoPipeline : modernTerrainPipeline;
        }
        return terrainPipeline;
    }
}
