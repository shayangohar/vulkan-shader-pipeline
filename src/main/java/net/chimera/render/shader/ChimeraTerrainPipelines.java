package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.chimera.shaderpack.TerrainShadingDirectives;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.thread.ThreadBuilderPack;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Builds and installs chimera's terrain pipelines.
 *
 * The GLSL/JSON live under /assets/chimera/shaders/ and read the host terrain
 * path's inputs: its uniform suppliers, the ChunkOffset push constant
 * DrawBuffers writes per area and the section offset in the draw's base instance.
 *
 * Enabling swaps the terrain shader getter to chimera's pipeline and installs
 * the matching extended builder. Disabling restores the host getter, format,
 * and builder. A live level is rebuilt only when that format mode changes.
 */
public final class ChimeraTerrainPipelines {
    private static boolean initialized;
    /** True while Chimera's terrain format and pipelines are installed for a pack; chunk builders read it. */
    private static volatile boolean extendedMode;
    /** Chimera's own terrain pipelines by vertex layout (bit 1: modern) and separateAo (bit 0). */
    private static final GraphicsPipeline[] fixedPipelines = new GraphicsPipeline[4];
    /** Read by the chunk-build threads, through the render-layer overrides; replaced on the render thread. */
    private static volatile PackMaterialResolver materialResolver = PackMaterialResolver.empty();
    private static TerrainMaterialPlan materialPlan = TerrainMaterialPlan.legacy();
    /** Pack geometry program (gbuffers_terrain) installed over the chimera terrain pipeline; null = none. */
    private static GraphicsPipeline geometryOverride;
    /** Pack water program installed only over the host translucent terrain lane; null = none. */
    private static GraphicsPipeline translucentOverride;
    /** True for one translucent draw whose pack inputs are not live this frame. */
    private static boolean translucentOverrideSuspended;
    /**
     * The installed pack's terrain shading, which chunk builders read: its directional shading,
     * separateAo, ambient occlusion level and light-block voxels, replaced as one value.
     */
    private static volatile TerrainShadingDirectives shading = TerrainShadingDirectives.VANILLA;
    /** The rebuilds a handoff asked for (rebuildingOnce), by reason; null outside one. Render thread only. */
    private static Set<String> heldRebuilds;

    private ChimeraTerrainPipelines() {}

    /** A block's shade brightness under the installed pack's ambientOcclusionLevel (Iris's getShadeBrightness). */
    public static float shadeBrightness(float original) {
        return shading.shadeBrightness(original);
    }

    /**
     * True while the installed pack lights faces itself (Iris oldLighting off), so
     * ClientLevel.getShade answers unshaded for block and fluid meshing.
     */
    public static boolean directionalShadingDisabled() {
        return shading.disablesDirectionalShading();
    }

    /**
     * True while Chimera's terrain meshes carry the light pipeline's brightness in the vertex
     * colour's alpha instead of multiplying it into RGB (Iris separateAo). Host-format meshes
     * never do: VulkanMod's own terrain shader reads alpha as opacity.
     */
    public static boolean separateAoActive() {
        return shading.separateAo() && extendedMode;
    }

    /**
     * True while Chimera meshes terrain for a pack: sections then compile as NeoForge's
     * SectionCompiler and Sodium do where VulkanMod departs. A light-emitting block darkens its
     * neighbours' ambient occlusion (ChimeraEmitterOcclusionMixin), and a block entity that renders
     * off-screen joins only the level's global set (ChimeraGlobalBlockEntityMixin). The level
     * rebuilds when it changes, with the terrain format.
     */
    public static boolean vanillaSectionCompile() {
        return extendedMode;
    }

    /**
     * True while the installed pack meshes each light block as a voxel quad (Iris
     * voxelizeLightBlocks); chunk builders read it.
     */
    public static boolean voxelizeLightBlocks() {
        return shading.voxelizeLightBlocks();
    }

    /**
     * Applies the installed pack's oldLighting, separateAo, ambientOcclusionLevel and
     * voxelizeLightBlocks, or {@link TerrainShadingDirectives#VANILLA} when no pack is installed.
     * Chunk meshes bake them in, so a change rebuilds the live level once, as Iris reloads
     * chunks when one of its WorldRenderingSettings values changes.
     */
    public static void setTerrainShading(TerrainShadingDirectives directives) {
        TerrainShadingDirectives next = directives == null ? TerrainShadingDirectives.VANILLA : directives;
        TerrainShadingDirectives current = shading;
        if (current.disablesDirectionalShading() == next.disablesDirectionalShading()
                && current.separateAo() == next.separateAo()
                && current.ambientOcclusionLevel() == next.ambientOcclusionLevel()
                && current.voxelizeLightBlocks() == next.voxelizeLightBlocks()) {
            return;
        }
        shading = next;
        ChimeraMod.LOGGER.debug("[chimera] terrain shading: directional={}, ao={} at {}, lightVoxels={}",
                next.disablesDirectionalShading() ? "off" : "vanilla",
                next.separateAo() ? "alpha" : "rgb", next.ambientOcclusionLevel(),
                next.voxelizeLightBlocks());
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
        // Logged when it changes: the no-pack load clears it on every window resize.
        if (pipeline != geometryOverride) {
            ChimeraMod.LOGGER.debug("[chimera] terrain override: {}", pipeline != null ? "installed" : "cleared");
        }
        geometryOverride = pipeline;
        if (!initialized) {
            return;
        }
        // Re-register only while Chimera owns terrain. Host/screen mode must
        // keep the host getter even if an override is cleared or replaced.
        if (extendedMode) {
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        }
    }

    /**
     * Destroys Chimera's own terrain pipelines at the renderer's final teardown
     * (ChimeraMainPass.cleanUp): VulkanMod's PipelineManager.destroyPipelines destroys only its
     * own, so these outlived the device. The pack's overrides are its pipelines, destroyed with them.
     */
    public static void cleanUp() {
        for (int variant = 0; variant < fixedPipelines.length; variant++) {
            if (fixedPipelines[variant] != null) fixedPipelines[variant].cleanUp();
            fixedPipelines[variant] = null;
        }
        initialized = false;
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

    /**
     * The chunk render type the pack's {@code layer.*} lines give a block (Iris BlockRenderType), or
     * null to keep the host's. A new resolver rebuilds the live level, so every mesh takes its layers.
     */
    public static RenderType packRenderType(BlockState state) {
        PackMaterialResolver.Layer layer = materialResolver.layer(state);
        if (layer == null) return null;
        return switch (layer) {
            case SOLID -> RenderType.solid();
            case CUTOUT -> RenderType.cutout();
            case CUTOUT_MIPPED -> RenderType.cutoutMipped();
            case TRANSLUCENT -> RenderType.translucent();
        };
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
        if (pipeline != translucentOverride) {
            ChimeraMod.LOGGER.debug("[chimera] translucent override: {}", pipeline != null ? "installed" : "cleared");
        }
        translucentOverride = pipeline;
        translucentOverrideSuspended = false;
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
                || PipelineManager.TERRAIN_VERTEX_FORMAT != desiredFormat;

        PipelineManager.setTerrainVertexFormat(desiredFormat);
        if (chimeraMode) {
            ThreadBuilderPack.setTerrainBuilderConstructor(renderType -> {
                return new ChimeraExtTerrainBuilder(TerrainRenderType.getRenderType(renderType).bufferSize(),
                        materialResolver, materialPlan);
            });
            PipelineManager.setShaderGetter(ChimeraTerrainPipelines::getTerrainPipeline);
        } else {
            ThreadBuilderPack.defaultTerrainBuilderConstructor();
            PipelineManager.setDefaultShader();
        }
        extendedMode = chimeraMode;

        if (changed) {
            rebuildLiveLevel("terrain format changed");
        }
    }

    /**
     * Runs {@code handoff}, rebuilding the live level at most once, at its end. A frame boundary
     * that hands terrain to the host, applies a pack or dimension variant and reinstalls it changes
     * the terrain format and shading up to four times, and no frame draws in between: each change
     * rebuilt the whole level, and the chunk builders meshed sections only to throw them away.
     */
    public static void rebuildingOnce(Runnable handoff) {
        if (heldRebuilds != null) {
            handoff.run();
            return;
        }
        heldRebuilds = new LinkedHashSet<>();
        try {
            handoff.run();
        } finally {
            Set<String> reasons = heldRebuilds;
            heldRebuilds = null;
            if (!reasons.isEmpty()) rebuildLiveLevel(String.join(", ", reasons));
        }
    }

    private static void rebuildLiveLevel(String reason) {
        if (heldRebuilds != null) {
            heldRebuilds.add(reason);
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.levelRenderer != null) {
            minecraft.levelRenderer.allChanged();
            ChimeraMod.LOGGER.debug("[chimera] {}: live level rebuilt (extended={})", reason, extendedMode);
        }
    }

    private static GraphicsPipeline buildPipeline(
            String name,
            VertexFormat vertexFormat,
            String shaderConfigName,
            boolean separateAo
    ) {
        return HostPipelineLayout.create(name, shaderConfigName, vertexFormat,
                separateAo ? ChimeraTerrainPipelines::withSeparateAo : UnaryOperator.identity());
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
        boolean separateAo = shading.separateAo();
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
