package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.chimera.ChimeraMod;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.shaderpack.UniformRegistry;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * One-time diagnostics for the guarded family lanes: the first batch, model draw, pipeline bind and
 * fallback of each family is logged once per session, so a log shows which path each family took.
 */
public final class FamilyDrawTrace {
    static final EnumSet<Family> drawTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> separatedTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> hostFallbackTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> modelTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> pipelineTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> meshTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> hostTransformBindingTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> hostTransformAbandonTraceFamilies = EnumSet.noneOf(Family.class);
    static final EnumSet<Family> transformContractTraceFamilies = EnumSet.noneOf(Family.class);
    static boolean separatedBatchTraceLogged;
    static boolean unsupportedPipelineTraceLogged;
    static final Set<String> submissionHostTraces = new HashSet<>();
    static boolean flushTraceLogged;

    private FamilyDrawTrace() {}

    /** Forgets what was logged, so a newly installed pack logs each family's path again. */
    static void reset() {
        drawTraceFamilies.clear();
        separatedTraceFamilies.clear();
        hostFallbackTraceFamilies.clear();
        modelTraceFamilies.clear();
        pipelineTraceFamilies.clear();
        meshTraceFamilies.clear();
        hostTransformBindingTraceFamilies.clear();
        hostTransformAbandonTraceFamilies.clear();
        transformContractTraceFamilies.clear();
        separatedBatchTraceLogged = false;
        unsupportedPipelineTraceLogged = false;
        submissionHostTraces.clear();
        flushTraceLogged = false;
    }

    /** Records which separated family is about to emit a model batch. */
    public static void noteFamilyBatch(Family family, int renderTypeCount) {
        if (family == null || !separatedTraceFamilies.add(family)) {
            return;
        }
        ChimeraMod.LOGGER.info("[chimera] entity bridge: family={} separated render types={}",
                family.name().toLowerCase(), renderTypeCount);
    }

    /** Emits one diagnostic for the first separated pack entity batch. */
    public static void noteSeparatedBatch(RenderType renderType, int count) {
        if (!separatedBatchTraceLogged) {
            separatedBatchTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: separated world batch type={} count={}",
                    renderType == null ? "unknown" : renderType, count);
        }
    }

    /** Emits one diagnostic when a world entity uses a lane outside the contract. */
    /** Emits one diagnostic per reason a world block or block-model submission keeps its host draw. */
    public static void noteSubmissionKeptOnHost(String reason, RenderType renderType) {
        if (submissionHostTraces.add(reason)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: block submission kept on host reason={} type={}",
                    reason, renderType == null ? "unknown" : renderType);
        }
    }

    public static void noteUnsupportedWorldPipeline(RenderType renderType) {
        if (!unsupportedPipelineTraceLogged) {
            unsupportedPipelineTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: world entity lane kept on host type={} pipeline={}",
                    renderType == null ? "unknown" : renderType,
                    renderType == null ? "unknown" : renderType.pipeline());
        }
    }

    /**
     * Records a family batch that stayed on the host path because admission
     * failed before the mesh was built, once per family. The mesh therefore
     * keeps the host vertex format and the host pipeline draws it.
     */
    public static void noteFamilyHostFallback(Family family, RenderType renderType) {
        if (hostFallbackTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: family={} kept on the host path type={}",
                    family.name().toLowerCase(), renderType == null ? "unknown" : renderType);
        }
    }

    /** Emits one diagnostic when a delayed world entity reaches model emission. */
    public static void noteModelDraw(RenderType renderType) {
        Family family = ChimeraEntityBridge.activeFamily == null ? Family.HOST_FALLBACK
                : ChimeraEntityBridge.activeFamily;
        if (modelTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: model emission reached type={}",
                    renderType == null ? "unknown" : renderType);
        }
    }

    /** Emits one diagnostic after the Vulkan seam binds the pack entity pipeline. */
    public static void notePipelineBound(RenderPipeline hostPipeline) {
        Family family = ChimeraEntityBridge.activeFamily == null ? Family.HOST_FALLBACK
                : ChimeraEntityBridge.activeFamily;
        if (pipelineTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: pack pipeline bound family={} hostPipeline={}",
                    ChimeraEntityBridge.activeFamily == null ? "unknown" : ChimeraEntityBridge.activeFamily.name()
                            .toLowerCase(),
                    hostPipeline == null ? "unknown" : hostPipeline);
        }
    }

    /** Records the per-draw host transform routes for one family. */
    public static void noteHostTransformRoutes(String detail) {
        Family family = ChimeraEntityBridge.activeFamily == null ? Family.HOST_FALLBACK
                : ChimeraEntityBridge.activeFamily;
        if (hostTransformBindingTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: host raster UBOs family={} {}",
                    family.name().toLowerCase(), detail);
        }
    }

    /**
     * Records an abandoned guarded draw for one family.
     *
     * <p>A widened batch keeps its append-only mesh, so an unusable or missing
     * depth contract drops the draw. Routing it back to the host pipeline would
     * make that pipeline read the widened layout.</p>
     */
    public static void noteDrawAbandoned(String detail) {
        Family family = ChimeraEntityBridge.activeFamily == null ? Family.HOST_FALLBACK
                : ChimeraEntityBridge.activeFamily;
        if (hostTransformAbandonTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] entity bridge: abandoned guarded draw family={} {}",
                    family.name().toLowerCase(), detail);
        }
    }

    /** Records a family whose pack pipeline cannot serve the host transform blocks. */
    public static void noteTransformContractMissing(Family family, GraphicsPipeline pipeline) {
        if (transformContractTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] entity bridge: pack pipeline cannot serve host "
                            + "transforms family={} pipeline={} DynamicTransforms={} Projection={}; "
                            + "batch kept on the host format",
                    family.name().toLowerCase(), pipeline,
                    pipeline.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO) != null,
                    pipeline.getUBO(UniformRegistry.PROJECTION_UBO) != null);
        }
    }

    /** Emits one diagnostic for the mesh handed to the guarded Vulkan draw. */
    public static void noteMeshDraw(int vertexCount, int indexCount, int stride) {
        Family family = ChimeraEntityBridge.activeFamily == null ? Family.HOST_FALLBACK
                : ChimeraEntityBridge.activeFamily;
        if (meshTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: mesh ready family={} vertices={} indices={} stride={}B",
                    ChimeraEntityBridge.activeFamily == null ? "unknown" : ChimeraEntityBridge.activeFamily.name()
                            .toLowerCase(),
                    vertexCount, indexCount, stride);
        }
    }
}
