package net.chimera.render.shader;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.TerrainShadingDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
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
    /** Chimera's own terrain pipelines by vertex layout (bit 1: modern) and separateAo (bit 0). */
    private static final GraphicsPipeline[] fixedPipelines = new GraphicsPipeline[4];
    private static PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    private static TerrainMaterialPlan materialPlan = TerrainMaterialPlan.legacy();
    /** Pack geometry program (gbuffers_terrain) installed over the chimera terrain pipeline; null = none. */
    private static GraphicsPipeline geometryOverride;
    /** Pack water program installed only over the host translucent terrain lane; null = none. */
    private static GraphicsPipeline translucentOverride;
    /** True for one translucent draw whose pack inputs are not live this frame. */
    private static boolean translucentOverrideSuspended;
    /** True while the installed pack removes Minecraft's directional face shading; chunk builders read it. */
    private static volatile boolean directionalShadingDisabled;
    /** True while the installed pack takes block brightness in the vertex colour's alpha; chunk builders read it. */
    private static volatile boolean separateAo;

    private ChimeraTerrainPipelines() {}

    /**
     * True while the installed pack lights faces itself (Iris oldLighting off), so
     * ClientLevel.getShade answers unshaded for block and fluid meshing.
     */
    public static boolean directionalShadingDisabled() {
        return directionalShadingDisabled;
    }

    /**
     * True while Chimera's terrain meshes carry the light pipeline's brightness in the vertex
     * colour's alpha instead of multiplying it into RGB (Iris separateAo). Host-format meshes
     * never do: VulkanMod's own terrain shader reads alpha as opacity.
     */
    public static boolean separateAoActive() {
        return separateAo && extendedMode;
    }

    /**
     * Applies the installed pack's oldLighting and separateAo, or
     * {@link TerrainShadingDirectives#VANILLA} when no pack is installed. Chunk meshes bake
     * both into their vertex colours, so a change rebuilds the live level once, as Iris reloads
     * chunks when either of its WorldRenderingSettings values changes.
     */
    public static void setTerrainShading(TerrainShadingDirectives directives) {
        TerrainShadingDirectives next = directives == null ? TerrainShadingDirectives.VANILLA : directives;
        boolean shadingDisabled = next.disablesDirectionalShading();
        if (directionalShadingDisabled == shadingDisabled && separateAo == next.separateAo()) {
            return;
        }
        directionalShadingDisabled = shadingDisabled;
        separateAo = next.separateAo();
        ChimeraMod.LOGGER.info("[chimera] terrain shading: directional shading {}, ambient occlusion {}",
                shadingDisabled ? "off (pack lights faces)" : "vanilla",
                separateAo ? "in vertex alpha (separateAo)" : "in vertex RGB");
        if (initialized && extendedMode) {
            // Build the matching fixed pipeline here rather than on first draw.
            fixedPipeline();
        }
        rebuildLiveLevel("terrain shading changed");
    }

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

        fixedPipelines[0] = buildPipeline("chimera_terrain", ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN,
                "chimera_terrain", false);
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
        if (renderType == TerrainRenderType.TRANSLUCENT && translucentOverride != null
                && !translucentOverrideSuspended) {
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
            fixedPipeline();
            setTerrainMode(true);
        }
    }

    /** The pack block.properties id of a block state, or -1 when unmapped (Iris BlockMaterialMapping). */
    public static int blockStateId(BlockState state) {
        return materialResolver.resolve(state);
    }

    public static void setMaterialResolver(PackMaterialResolver resolver) {
        PackMaterialResolver next = resolver == null ? PackMaterialResolver.empty() : resolver;
        if (materialResolver == next) {
            return;
        }
        materialResolver = next;
        if (extendedMode) {
            rebuildLiveLevel("material map changed");
        }
    }

    /** Installs/clears the pack water override without affecting opaque terrain. */
    public static void setTranslucentOverride(GraphicsPipeline pipeline) {
        translucentOverride = pipeline;
        translucentOverrideSuspended = false;
        ChimeraMod.LOGGER.info("[chimera] translucent override: {}",
                pipeline != null ? "installed" : "cleared");
        if (!initialized) {
            return;
        }
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /**
     * Routes one translucent layer draw to the fixed Chimera pipeline while
     * the pack water program's sampled inputs are unavailable. The main pass
     * sets it at the layer's head and clears it at its return.
     */
    public static void suspendTranslucentOverride(boolean suspended) {
        translucentOverrideSuspended = suspended;
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
            rebuildLiveLevel("terrain format changed");
        }
    }

    private static void rebuildLiveLevel(String reason) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
            ChimeraMod.LOGGER.info("[chimera] {}: live level rebuilt (extended={})", reason, extendedMode);
        }
    }

    private static GraphicsPipeline buildPipeline(
            String name,
            VertexFormat vertexFormat,
            String shaderConfigName,
            boolean separateAo
    ) {
        JsonObject json = ChimeraShaderLoader.loadJson(shaderConfigName + ".json");
        PipelineConfig config = PipelineConfig.fromJson(name, json);

        Pipeline.Builder builder = new Pipeline.Builder(vertexFormat, name);
        builder.applyConfig(config);

        String vertexPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.VERTEX_SHADER);
        String fragmentPath = config.shaderPaths.get(SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
        String vertexSource = ChimeraShaderLoader.loadSource(vertexPath + ".vsh");
        builder.setShaderSrc(SPIRVUtils.ShaderKind.VERTEX_SHADER,
                separateAo ? withSeparateAo(vertexSource) : vertexSource);
        builder.setShaderSrc(SPIRVUtils.ShaderKind.FRAGMENT_SHADER, ChimeraShaderLoader.loadSource(fragmentPath + ".fsh"));

        GraphicsPipeline pipeline = builder.createGraphicsPipeline();

        // Share the host's global UBO pool so per-instance section data flows in unchanged.
        for (var buffer : pipeline.getBuffers()) {
            buffer.setUseGlobalBuffer(true);
        }

        return pipeline;
    }

    /**
     * Defines CHIMERA_SEPARATE_AO after the #version line. The vertex shader then multiplies
     * the brightness that separateAo meshes carry in alpha back into RGB, as the vanilla
     * terrain shader would have seen it, and keeps the block opaque.
     */
    public static String withSeparateAo(String source) {
        int lineEnd = source.indexOf('\n', source.indexOf("#version"));
        if (lineEnd < 0) {
            throw new IllegalArgumentException("chimera_terrain vertex shader has no #version line");
        }
        return source.substring(0, lineEnd + 1) + "#define CHIMERA_SEPARATE_AO\n" + source.substring(lineEnd + 1);
    }

    /** The fixed pipeline for the session's vertex layout and separateAo, built on first use. */
    private static GraphicsPipeline fixedPipeline() {
        boolean modern = materialPlan.modern();
        int variant = (modern ? 2 : 0) | (separateAo ? 1 : 0);
        GraphicsPipeline pipeline = fixedPipelines[variant];
        if (pipeline == null) {
            String name = "chimera_terrain" + (modern ? "_modern" : "") + (separateAo ? "_separate_ao" : "");
            pipeline = buildPipeline(name, ChimeraVertexFormats.terrainFormat(materialPlan), "chimera_terrain",
                    separateAo);
            fixedPipelines[variant] = pipeline;
        }
        return pipeline;
    }
}
