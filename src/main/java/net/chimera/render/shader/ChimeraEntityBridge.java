package net.chimera.render.shader;

import net.chimera.ChimeraMod;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Set;

/** Render-thread bridge for the guarded world gbuffers_entities lane. */
public final class ChimeraEntityBridge {
    private static PackEntityIdResolver resolver = PackEntityIdResolver.empty();
    private static GraphicsPipeline pipeline;
    private static boolean enabled;
    private static int submittingDepth;
    private static final int[] previousEntityIds = new int[8];
    private static boolean drawActive;
    private static int currentEntityId;
    private static boolean worldSubmissionWindow;
    private static boolean textureSnapshot;
    private static boolean entityBufferUnavailable;
    private static boolean submissionTraceLogged;
    private static boolean batchTraceLogged;
    private static boolean drawTraceLogged;
    private static boolean unsupportedPipelineTraceLogged;
    private static boolean modelTraceLogged;
    private static boolean flushTraceLogged;
    private static boolean pipelineTraceLogged;
    private static boolean meshTraceLogged;
    private static ByteBufferBuilder entityBuffer;
    private static MultiBufferSource.BufferSource entityBufferSource;
    private static final VulkanImage[] previousTextures =
            new VulkanImage[VTextureSelector.SIZE];
    private static final Set<RenderPipeline> SUPPORTED_WORLD_PIPELINES = Set.of(
            RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
            RenderPipelines.ENTITY_CUTOUT,
            RenderPipelines.ENTITY_CUTOUT_NO_CULL,
            RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET,
            RenderPipelines.ENTITY_SMOOTH_CUTOUT,
            RenderPipelines.ENTITY_NO_OUTLINE,
            // Living entities, including the vanilla cow and zombie renderers,
            // use this host translucent pipeline. It still inherits the host
            // blend and depth state through the guarded Vulkan seam below.
            RenderPipelines.ENTITY_TRANSLUCENT
    );

    private ChimeraEntityBridge() {}

    public static void install(PackPipelines.PackEntity entity,
                               PackEntityIdResolver nextResolver) {
        releaseEntityBuffer();
        pipeline = entity == null ? null : entity.pipeline();
        resolver = nextResolver == null ? PackEntityIdResolver.empty() : nextResolver;
        currentEntityId = 0;
        drawActive = false;
        submittingDepth = 0;
        worldSubmissionWindow = false;
        textureSnapshot = false;
        entityBufferUnavailable = false;
        submissionTraceLogged = false;
        batchTraceLogged = false;
        drawTraceLogged = false;
        unsupportedPipelineTraceLogged = false;
        modelTraceLogged = false;
        flushTraceLogged = false;
        pipelineTraceLogged = false;
        meshTraceLogged = false;
        ChimeraMod.LOGGER.info("[chimera] entity bridge: {}",
                pipeline == null ? "fallback=IDENTITY" : "installed");
    }

    public static void setEnabled(boolean value) {
        enabled = value && pipeline != null;
        if (!enabled) {
            restoreTextures();
            drawActive = false;
            currentEntityId = 0;
            submittingDepth = 0;
            worldSubmissionWindow = false;
        }
    }

    public static void disable() {
        releaseEntityBuffer();
        restoreTextures();
        enabled = false;
        drawActive = false;
        submittingDepth = 0;
        currentEntityId = 0;
        worldSubmissionWindow = false;
        pipeline = null;
        resolver = PackEntityIdResolver.empty();
    }

    public static boolean isInstalled() {
        return pipeline != null;
    }

    public static boolean isDrawActive() {
        return enabled && drawActive && pipeline != null;
    }

    public static GraphicsPipeline pipeline() {
        return pipeline;
    }

    public static void beginEntity(String name) {
        if (!enabled) {
            return;
        }
        if (submittingDepth < previousEntityIds.length) {
            previousEntityIds[submittingDepth] = currentEntityId;
        }
        submittingDepth++;
        currentEntityId = resolver.resolveName(name);
        if (!submissionTraceLogged) {
            submissionTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: captured world entity type={} id={}",
                    name == null ? "unknown" : name, currentEntityId);
        }
    }

    public static void endEntity() {
        if (enabled && submittingDepth > 0) {
            submittingDepth--;
            currentEntityId = submittingDepth == 0
                    ? 0
                    : submittingDepth < previousEntityIds.length
                    ? previousEntityIds[submittingDepth] : 0;
        }
    }

    public static boolean isSubmittingEntity() {
        return enabled && submittingDepth > 0;
    }

    public static boolean beginDraw() {
        if (enabled && pipeline != null && ensureEntityBuffer()) {
            if (!drawActive) {
                for (int index = 0; index < VTextureSelector.SIZE; index++) {
                    previousTextures[index] = VTextureSelector.getBoundTexture(index);
                }
                textureSnapshot = true;
            }
            drawActive = true;
            if (!drawTraceLogged) {
                drawTraceLogged = true;
                ChimeraMod.LOGGER.info("[chimera] entity bridge: rendering separated world batch");
            }
            return true;
        }
        return false;
    }

    public static void endDraw() {
        restoreTextures();
        drawActive = false;
        currentEntityId = 0;
    }

    /**
     * Returns the session-stable buffer source reserved for world entity batches.
     *
     * The source is deliberately separate from the host immediate source. It
     * lets the guarded format mixins change only the upload path for this
     * batch, so host screen, inventory, and special-entity buffers keep their
     * normal 36-byte format and cache lifetime.
     */
    public static MultiBufferSource.BufferSource entityBufferSource() {
        return ensureEntityBuffer() ? entityBufferSource : null;
    }

    /** Flushes the dedicated source while the extended-format draw window is active. */
    public static void endEntityBatch() {
        if (entityBufferSource == null || !drawActive) {
            return;
        }
        if (!flushTraceLogged) {
            flushTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: flushing separated world batch");
        }
        entityBufferSource.endBatch();
    }

    public static int currentEntityId() {
        return enabled ? currentEntityId : 0;
    }

    public static void setCurrentEntityId(int value) {
        if (enabled) {
            currentEntityId = value;
        }
    }

    /** Marks the LevelRenderer interval in which world entities are submitted. */
    public static void beginWorldSubmissionWindow() {
        // Reserve the session-stable source before ModelFeatureRenderer moves
        // submissions into the separate batch. If allocation fails, the host
        // batch remains untouched and can render the entities normally.
        worldSubmissionWindow = enabled && ensureEntityBuffer();
    }

    /** Ends the world submission interval; delayed model data keeps its mark. */
    public static void endWorldSubmissionWindow() {
        worldSubmissionWindow = false;
        currentEntityId = 0;
        submittingDepth = 0;
    }

    public static boolean isWorldSubmissionWindow() {
        return enabled && worldSubmissionWindow;
    }

    /** True only when the separate entity batch can be flushed safely. */
    public static boolean isEntityBufferReady() {
        return enabled && pipeline != null && entityBufferSource != null;
    }

    /** Keeps emissive, hand, and special entity lanes on the host path. */
    public static boolean supportsWorldRenderType(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        return supportsWorldPipeline(renderType.pipeline());
    }

    /** Returns whether the guarded pack batch may handle this world entity pipeline. */
    public static boolean supportsWorldPipeline(RenderPipeline pipeline) {
        return pipeline != null && SUPPORTED_WORLD_PIPELINES.contains(pipeline);
    }

    /** Emits one diagnostic for the first separated pack entity batch. */
    public static void noteSeparatedBatch(RenderType renderType, int count) {
        if (!batchTraceLogged) {
            batchTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: separated world batch type={} count={}",
                    renderType == null ? "unknown" : renderType, count);
        }
    }

    /** Emits one diagnostic when a world entity uses a lane outside the contract. */
    public static void noteUnsupportedWorldPipeline(RenderType renderType) {
        if (!unsupportedPipelineTraceLogged) {
            unsupportedPipelineTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: world entity lane kept on host type={} pipeline={}",
                    renderType == null ? "unknown" : renderType,
                    renderType == null ? "unknown" : renderType.pipeline());
        }
    }

    /** Emits one diagnostic when a delayed world entity reaches model emission. */
    public static void noteModelDraw(RenderType renderType) {
        if (!modelTraceLogged) {
            modelTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: model emission reached type={}",
                    renderType == null ? "unknown" : renderType);
        }
    }

    /** Emits one diagnostic after the Vulkan seam binds the pack entity pipeline. */
    public static void notePipelineBound(RenderPipeline hostPipeline) {
        if (!pipelineTraceLogged) {
            pipelineTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: pack pipeline bound hostPipeline={}",
                    hostPipeline == null ? "unknown" : hostPipeline);
        }
    }

    /** Emits one diagnostic for the mesh handed to the guarded Vulkan draw. */
    public static void noteMeshDraw(int vertexCount, int indexCount, int stride) {
        if (!meshTraceLogged) {
            meshTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: mesh ready vertices={} indices={} stride={}B",
                    vertexCount, indexCount, stride);
        }
    }

    private static void restoreTextures() {
        if (!textureSnapshot) {
            return;
        }
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            VTextureSelector.bindTexture(index, previousTextures[index]);
            previousTextures[index] = null;
        }
        textureSnapshot = false;
    }

    private static boolean ensureEntityBuffer() {
        if (entityBufferSource != null) {
            return true;
        }
        if (entityBufferUnavailable || pipeline == null) {
            return false;
        }
        try {
            entityBuffer = new ByteBufferBuilder(256 * 1024);
            entityBufferSource = MultiBufferSource.immediate(entityBuffer);
            return true;
        } catch (RuntimeException exception) {
            releaseEntityBuffer();
            entityBufferUnavailable = true;
            ChimeraMod.LOGGER.warn("[chimera] entity buffer source unavailable; using host entity path", exception);
            return false;
        }
    }

    private static void releaseEntityBuffer() {
        if (entityBufferSource != null) {
            if (drawActive) {
                try {
                    entityBufferSource.endBatch();
                } catch (RuntimeException exception) {
                    ChimeraMod.LOGGER.warn("[chimera] entity buffer source cleanup failed", exception);
                }
            }
            entityBufferSource = null;
        }
        if (entityBuffer != null) {
            entityBuffer.close();
            entityBuffer = null;
        }
    }
}
