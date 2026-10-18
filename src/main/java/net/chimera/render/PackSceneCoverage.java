package net.chimera.render;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackGeometryContext;
import net.chimera.shaderpack.PackCoveragePlan;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.SelectorNamespace;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Scene coverage for a pack frame: which pixels pack geometry covered, the window that records it,
 * and the seed that fills uncovered pixels from the host scene before pack post runs.
 */
final class PackSceneCoverage {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private final ChimeraMainPass pass;

    /**
     * The coverage path remains load-time inventory until every host
     * family can share the same coverage contract. Keeping the gate explicit
     * prevents the deferred path from allocating live images or pipelines.
     */
    static final boolean PACK_COVERAGE_RUNTIME_ENABLED = false;

    GraphicsPipeline sceneSeedPipeline;

    /** Session-owned scene-seed coverage state. */
    final PackCoverageOwner packCoverageOwner = new PackCoverageOwner();

    final PackCoverageState packCoverageState = new PackCoverageState();

    PackCoveragePlan packCoveragePlan = PackCoveragePlan.disabled();

    PackSceneCoverage(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * Retained as the terrain-family seam. Pack terrain stays on the live HDR
     * render pass; the post graph reads that complete HDR image as colortex0.
     */
    public void beginPackCoverageWindow(TerrainRenderType renderType) {
        if (suspendTranslucentWithoutInputs(renderType)) {
            return;
        }
        pass.windows.beginPackResourceWindow(renderType);
        PackPipelines.PackTerrain terrain = switch (renderType) {
            case SOLID, CUTOUT_MIPPED, CUTOUT -> pass.pipelines.terrainRoute;
            case TRANSLUCENT -> pass.pipelines.waterRoute;
            default -> null;
        };
        if (terrain == null || !terrain.requiresDynamicAttachments()
                || !pass.packTargetResourcesReady || pass.shadowPass.shadowPassActive
                || pass.targets.hdrFramebuffer == null || pass.targets.hdrFramebuffer.getDepthAttachment() == null) {
            return;
        }
        if (PackGeometryContext.active()) {
            return;
        }
        try {
            List<Integer> outputs = terrain.outputPlan().targetSlots();
            VulkanImage hdrColor = pass.targets.hdrFramebuffer.getColorAttachment();
            Renderer.getInstance().endRenderPass();
            pass.packPostTargets.beginGeometry(Renderer.getCommandBuffer(), hdrColor, outputs,
                    terrain.outputPlan().snapshotTargets());
            PackGeometryContext.beginGeometry(
                    pass.packPostTargets.geometryAttachments(outputs),
                    pass.targets.hdrFramebuffer.getDepthAttachment());
        } catch (RuntimeException failure) {
            pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
            pass.currentFramebuffer = pass.targets.hdrFramebuffer;
            pass.rebindMainTarget();
            pass.logPackRuntimeFailure("geometry-begin:" + renderType, failure);
        }
    }

    /**
     * Hands the translucent layer to the fixed pipeline when the water program's inputs are not
     * live this frame. True when it did, so the layer opens no pack window.
     */
    private boolean suspendTranslucentWithoutInputs(TerrainRenderType renderType) {
        if (renderType != TerrainRenderType.TRANSLUCENT || pass.shadowPass.shadowPassActive
                || pass.pipelines.waterRoute == null
                || pass.images.packTranslucentInputsReady(pass.pipelines.waterRoute)) {
            return false;
        }
        // The fixed pipeline draws this layer; the pack program never
        // binds a stale or missing image.
        ChimeraTerrainPipelines.suspendTranslucentOverride(true);
        pass.packTranslucentSuspended = true;
        if (!pass.packTranslucentSuspendLogged) {
            pass.packTranslucentSuspendLogged = true;
            LOGGER.warn("[chimera] pack gbuffers_water: inputs unavailable this frame, "
                    + "fixed translucent pipeline used");
        }
        return true;
    }

    boolean packCoverageRuntimeEnabled() {
        return PACK_COVERAGE_RUNTIME_ENABLED;
    }

    /** Ends the frame's coverage state when the coverage runtime is enabled. */
    void endCoverageFrame() {
        if (packCoverageRuntimeEnabled()) {
            this.packCoverageState.endFrame();
        }
    }

    /** The direct-HDR terrain path has no separate coverage window to close. */
    public void endPackCoverageWindow(TerrainRenderType renderType) {
        if (pass.shadowPass.shadowPassActive) return;
        if (pass.packTranslucentSuspended && renderType == TerrainRenderType.TRANSLUCENT) {
            pass.packTranslucentSuspended = false;
            ChimeraTerrainPipelines.suspendTranslucentOverride(false);
            return;
        }
        boolean geometryWindow = PackGeometryContext.geometryActive();
        if (geometryWindow) {
            PackPipelines.PackTerrain terrain = renderType == TerrainRenderType.TRANSLUCENT
                    ? pass.pipelines.waterRoute : pass.pipelines.terrainRoute;
            if (terrain == null || terrain.outputPlan() == null) {
                PackGeometryContext.close();
                pass.windows.restorePackResourceWindow();
                return;
            }
            try {
                Renderer.getInstance().endRenderPass();
                PackGeometryContext.close();
                pass.packPostTargets.commitGeometry(terrain.outputPlan().targetSlots(),
                        pass.targets.hdrFramebuffer.getColorAttachment());
                pass.currentFramebuffer = pass.targets.hdrFramebuffer;
                pass.rebindMainTarget();
            } catch (RuntimeException failure) {
                pass.packPostTargets.abortGeometry(pass.targets.hdrFramebuffer.getColorAttachment());
                pass.logPackRuntimeFailure("geometry-commit", failure);
                if (PackGeometryContext.active()) PackGeometryContext.close();
                pass.rebindMainTarget();
            } finally {
                pass.windows.restorePackResourceWindow();
            }
            return;
        }
        if (renderType == TerrainRenderType.TRANSLUCENT || renderType == TerrainRenderType.TRIPWIRE) {
            pass.windows.restorePackResourceWindow();
            return;
        }
        if (!PackGeometryContext.coverageActive()) {
            pass.windows.restorePackResourceWindow();
            return;
        }
        Renderer.getInstance().endRenderPass();
        PackGeometryContext.close();
        this.packCoverageState.commitPackWrite();
        pass.currentFramebuffer = pass.targets.hdrFramebuffer;
        pass.rebindMainTarget();
        pass.windows.restorePackResourceWindow();
    }

    /** Merges host HDR pixels into the pack target without overwriting covered geometry. */
    void seedPackScene(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        VulkanImage target = pass.packPostTargets.geometryTarget0();
        VulkanImage coverage = this.packCoverageOwner.image();
        if (target == null || coverage == null || this.sceneSeedPipeline == null
                || !this.packCoverageState.canSeed()) {
            return;
        }
        VulkanImage previousHdr = VTextureSelector.getBoundTexture(0);
        VulkanImage previousCoverage = VTextureSelector.getBoundTexture(SelectorNamespace.COVERAGE_SLOT);
        boolean depthTest = VRenderSystem.depthTest;
        boolean depthMask = VRenderSystem.depthMask;
        int colorMask = VRenderSystem.getColorMask();
        boolean blendEnabled = PipelineState.blendInfo.enabled;
        int previousSrcRgb = PipelineState.blendInfo.srcRgbFactor;
        int previousDstRgb = PipelineState.blendInfo.dstRgbFactor;
        int previousSrcAlpha = PipelineState.blendInfo.srcAlphaFactor;
        int previousDstAlpha = PipelineState.blendInfo.dstAlphaFactor;
        int previousBlendOp = PipelineState.blendInfo.blendOp;
        boolean cullEnabled = VRenderSystem.cull;
        VTextureSelector.bindTexture(0, hdrColor);
        VTextureSelector.bindTexture(SelectorNamespace.COVERAGE_SLOT, coverage);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            coverage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            int deviceLimit = ChimeraMainPass.deviceMaxColorAttachments();
            MrtPipelineContext.begin(new int[] {target.format}, deviceLimit);
            VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(1, stack);
            attachments.get(0).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(PackPostTargets.attachmentView(target))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            VkRect2D area = VkRect2D.calloc(stack);
            area.offset().set(0, 0);
            area.extent().set(target.width, target.height);
            VkRenderingInfo rendering = VkRenderingInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                    .renderArea(area).layerCount(1).pColorAttachments(attachments);
            vkCmdBeginRenderingKHR(commandBuffer, rendering);
            Renderer.getInstance().setBoundRenderPass(ChimeraRenderingPass.of(new int[] {target.format}, 0, false));
            VRenderSystem.disableDepthTest();
            VRenderSystem.depthMask(false);
            VRenderSystem.enableBlend();
            VRenderSystem.blendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            VRenderSystem.disableCull();
            Renderer.setViewport(0, 0, target.width, target.height, stack);
            pass.drawFullscreen(commandBuffer, this.sceneSeedPipeline);
            vkCmdEndRenderingKHR(commandBuffer);
            Renderer.getInstance().setBoundRenderPass(null);
            target.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            pass.packPostTargets.commitSceneSeed(target);
            this.packCoverageState.commitSeed();
        } finally {
            MrtPipelineContext.end();
            VTextureSelector.bindTexture(0, previousHdr);
            VTextureSelector.bindTexture(SelectorNamespace.COVERAGE_SLOT, previousCoverage);
            VRenderSystem.depthTest = depthTest;
            VRenderSystem.depthMask = depthMask;
            VRenderSystem.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                    (colorMask & 4) != 0, (colorMask & 8) != 0);
            if (blendEnabled) {
                VRenderSystem.enableBlend();
            } else {
                VRenderSystem.disableBlend();
            }
            PipelineState.blendInfo.srcRgbFactor = previousSrcRgb;
            PipelineState.blendInfo.dstRgbFactor = previousDstRgb;
            PipelineState.blendInfo.srcAlphaFactor = previousSrcAlpha;
            PipelineState.blendInfo.dstAlphaFactor = previousDstAlpha;
            PipelineState.blendInfo.blendOp = previousBlendOp;
            VRenderSystem.cull = cullEnabled;
        }
    }
}
