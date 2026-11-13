package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.FamilyRenderType;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.PackPipelines;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
     * Draws held batches in order inside the pack windows their family lanes write, one window per
     * run of consecutive batches with the same outputs and the same stored targets. A run holds
     * the targets its programs store to as colorimgN in the general layout, and a program that
     * samples one (a world program reading colortexN) would fail to bind it, so the two never share
     * a run; a program never samples a target it stores to itself (TARGET_IMAGE_ALSO_SAMPLED). A
     * host type's batch opens no window here: its host-layout lane opens its own when it draws. A
     * run whose window cannot open is discarded: its widened meshes have no host program to fall
     * back to. Every batch of one flush reads the depthtex0 its first reader captured, the world
     * drawn before the flush.
     */
    void drawFamilyBatches(List<RenderType> batches, Consumer<RenderType> draw, Consumer<RenderType> discard) {
        try {
            List<RunKey> keys = new ArrayList<>(batches.size());
            for (RenderType batch : batches) keys.add(runKey(batch));
            for (int[] run : runs(keys)) {
                drawRun(batches.subList(run[0], run[1]), keys.get(run[0]).outputs(), draw, discard);
            }
        } finally {
            pass.depth.markWorldDrawn();
        }
    }

    /** What one run's batches share: the outputs their window binds and the targets they store to. */
    record RunKey(GeometryOutputPlan outputs, List<Integer> stored) {}

    /** A held batch's run key; no outputs and nothing stored for a host type's. */
    private RunKey runKey(RenderType batch) {
        if (!(batch instanceof FamilyRenderType family)) return new RunKey(null, List.of());
        return new RunKey(pass.laneWindowOutputs(family.family()),
                pass.worldStorageTargets(ChimeraEntityBridge.lanePipeline(family.family(), family.format())));
    }

    /** The runs of consecutive equal {@code keys}, in order, each as its start and end index. */
    static List<int[]> runs(List<?> keys) {
        List<int[]> runs = new ArrayList<>();
        int start = 0;
        while (start < keys.size()) {
            int end = start + 1;
            while (end < keys.size() && Objects.equals(keys.get(end), keys.get(start))) end++;
            runs.add(new int[] {start, end});
            start = end;
        }
        return runs;
    }

    /**
     * Draws one run inside its window. The targets a hand program stores to as colorimgN, which
     * preparing its images held in the general layout, go back to the sampled layout once the run
     * ends, drawn or discarded.
     */
    private void drawRun(List<RenderType> run, GeometryOutputPlan outputs, Consumer<RenderType> draw,
                         Consumer<RenderType> discard) {
        try {
            for (RenderType batch : run) {
                if (batch instanceof FamilyRenderType family) {
                    pass.prepareProgramImages(ChimeraEntityBridge.lanePipeline(family.family(), family.format()));
                }
            }
            if (outputs != null && !beginPackFamilyWindow(outputs)) {
                run.forEach(discard);
                return;
            }
            // Rendering reopens inside the window before the first batch draws.
            if (outputs != null) pass.rebindMainTarget();
            try {
                run.forEach(draw);
            } finally {
                if (outputs != null) endPackFamilyWindow(outputs);
            }
        } finally {
            pass.images.releaseWorldStorage();
        }
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
                || !pass.session.packTargetResourcesReady || pass.shadowPass.shadowPassActive
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
    public void bindTerrainImagesAfterSelector(TerrainRenderType renderType) {
        if (pass.shadowPass.shadowPassActive) return;
        boolean translucent = renderType == TerrainRenderType.TRANSLUCENT;
        PackPipelines.PackTerrain terrain = translucent ? pass.pipelines.waterRoute : pass.pipelines.terrainRoute;
        if (terrain == null || translucent && pass.packTranslucentSuspended) {
            // The fixed pipeline draws this layer. Reforged has just rebound its shadow map slot
            // (unit 3) from RenderSystem, which holds no texture there, to the missing texture.
            pass.shadowPass.shadowMap.bindShadowTexture();
            return;
        }
        restorePackResourceWindow();
        // Capture the draw context before the transaction: the albedo image
        // straight from the synchronized selector plus the authoritative
        // chunk sampler profile. The resolver below binds from this
        // immutable context instead of re-querying slot state mid-draw.
        DrawMaterialContext drawContext =
                DrawMaterialContext.forTerrain(VTextureSelector.getImage(0));
        pass.packGeometryBindings = pass.images.bindProgramImages(terrain.pipeline(),
                pass.packPostTargets.worldImages(), pass.targets.hdrFramebuffer.getColorAttachment(),
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
