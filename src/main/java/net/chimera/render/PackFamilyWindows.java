package net.chimera.render;

import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Opens and closes the render-pass windows pack geometry draws in: a family window retargets the
 * pass to the family's authored outputs, and a resource window binds a program's images and
 * terrain atlas for the draws inside it.
 */
final class PackFamilyWindows {
    private final ChimeraMainPass pass;

    PackFamilyWindows(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Opens the authored attachment window around one host geometry family
     * draw or batch. The bridge only selects the pipeline; this pass owns the target
     * transitions: it closes the host HDR pass, binds the declared targets
     * in output-location order with shared depth (target 0 uses live HDR), and
     * enters the guarded geometry context whose dynamic-rendering bridge
     * supplies the attachments. Returns true only when this call opened the
     * window; the caller closes exactly what it opened.
     */
    public boolean beginPackFamilyWindow(PackPipelines.PackEntity family) {
        return family != null && beginPackFamilyWindow(family.outputPlan());
    }

    public boolean beginPackFamilyWindow(GeometryOutputPlan outputs) {
        if (outputs == null || !outputs.requiresDynamicAttachments()
                || !pass.packTargetResourcesReady || pass.shadowPass.shadowPassActive
                || pass.targets.hdrFramebuffer == null
                || pass.targets.hdrFramebuffer.getDepthAttachment() == null
                || !pass.packPostTargets.isConfigured()) {
            return false;
        }
        if (PackGeometryContext.active()) {
            return false;
        }
        try {
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            pass.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs.targetSlots(),
                    outputs.snapshotTargets());
            PackGeometryContext.beginGeometry(
                    pass.packPostTargets.geometryAttachments(outputs.targetSlots()),
                    pass.targets.hdrFramebuffer.getDepthAttachment());
            return true;
        } catch (RuntimeException failure) {
            pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
            pass.currentFramebuffer = pass.targets.hdrFramebuffer;
            pass.rebindMainTarget();
            pass.logPackRuntimeFailure("family-begin", failure);
            return false;
        }
    }

    /** Commits the family window's auxiliary outputs and reopens the HDR pass. */
    public void endPackFamilyWindow(PackPipelines.PackEntity family) {
        if (family != null) endPackFamilyWindow(family.outputPlan());
    }

    public void endPackFamilyWindow(GeometryOutputPlan outputs) {
        if (outputs == null || !outputs.requiresDynamicAttachments()
                || !PackGeometryContext.geometryActive() || pass.shadowPass.shadowPassActive) {
            return;
        }
        try {
            Renderer.getInstance().endRenderPass();
            PackGeometryContext.close();
            pass.packPostTargets.commitGeometry(outputs.targetSlots(),
                    pass.targets.hdrFramebuffer.getColorAttachment());
            pass.currentFramebuffer = pass.targets.hdrFramebuffer;
            pass.rebindMainTarget();
        } catch (RuntimeException failure) {
            pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
            pass.logPackRuntimeFailure("family-commit", failure);
            if (PackGeometryContext.active()) PackGeometryContext.close();
            pass.rebindMainTarget();
        }
    }

    /** Binds pack-owned sampled images for the guarded terrain draw only. */
    void beginPackResourceWindow(TerrainRenderType renderType) {
        if (pass.shadowPass.shadowPassActive || !ChimeraRenderer.segmentsActive()) return;
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT_MIPPED, CUTOUT -> pass.pipelines.terrainRoute;
            case TRANSLUCENT -> pass.pipelines.waterRoute;
            default -> null;
        };
        if (terrain == null) return;
        pass.images.prepareProgramImages(terrain.pipeline());
    }

    /**
     * Pairs the real selector atlas with LevelRenderer.chunkLayerSampler.
     * WorldRenderer calls this only after VulkanMod has synchronized slot 0.
     */
    public void bindTerrainAtlasAfterSelector(TerrainRenderType renderType) {
        if (pass.shadowPass.shadowPassActive) return;
        if (pass.packTranslucentSuspended && renderType == TerrainRenderType.TRANSLUCENT) return;
        PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                ? pass.pipelines.waterRoute : pass.pipelines.terrainRoute;
        if (terrain == null) return;
        restorePackResourceWindow();
        // Capture the draw context before the transaction: the albedo image
        // straight from the synchronized selector plus the authoritative
        // chunk sampler profile. The resolver below binds from this
        // immutable context instead of re-querying slot state mid-draw.
        DrawMaterialContext drawContext =
                DrawMaterialContext.forTerrain(VTextureSelector.getImage(0));
        pass.packGeometryBindings = pass.images.bindProgramImages(terrain.pipeline(),
                pass.packPostTargets.sourceImages(), pass.targets.hdrFramebuffer.getColorAttachment(),
                drawContext);
        if (pass.packGeometryBindings.containsSlot(0)) {
            ChimeraTextureBindingState.bindTerrainAtlas(VTextureSelector.getImage(0));
        }
    }

    /** Restores host selector values after a guarded terrain draw. */
    void restorePackResourceWindow() {
        if (pass.packGeometryBindings != null) {
            pass.packGeometryBindings.close();
            pass.packGeometryBindings = null;
        }
    }
}
