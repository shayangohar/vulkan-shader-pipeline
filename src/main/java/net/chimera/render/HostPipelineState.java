package net.chimera.render;

import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.shader.PipelineState;

/**
 * VulkanMod picks a pipeline variant from its global render state when a
 * pipeline is bound, so a Chimera-owned fullscreen draw inherits whatever
 * blend, mask, depth and cull state the last host draw left behind. A glint
 * draw's SRC_COLOR/ONE blend once squared every depth copy. Owners snapshot
 * this state, bind under the fixed fullscreen state, and restore it.
 */
record HostPipelineState(
        boolean depthTest,
        boolean depthMask,
        int colorMask,
        boolean blendEnabled,
        int srcRgbFactor,
        int dstRgbFactor,
        int srcAlphaFactor,
        int dstAlphaFactor,
        int blendOp,
        boolean cullEnabled
) {
    static HostPipelineState capture() {
        return new HostPipelineState(
                VRenderSystem.depthTest,
                VRenderSystem.depthMask,
                VRenderSystem.getColorMask(),
                PipelineState.blendInfo.enabled,
                PipelineState.blendInfo.srcRgbFactor,
                PipelineState.blendInfo.dstRgbFactor,
                PipelineState.blendInfo.srcAlphaFactor,
                PipelineState.blendInfo.dstAlphaFactor,
                PipelineState.blendInfo.blendOp,
                VRenderSystem.cull);
    }

    /** Opaque overwrite of every channel, no depth, no culling. */
    static void prepareFullscreen() {
        VRenderSystem.disableDepthTest();
        VRenderSystem.depthMask(false);
        VRenderSystem.colorMask(true, true, true, true);
        VRenderSystem.disableBlend();
        VRenderSystem.disableCull();
    }

    void restore() {
        VRenderSystem.depthTest = depthTest;
        VRenderSystem.depthMask = depthMask;
        VRenderSystem.colorMask((colorMask & 1) != 0, (colorMask & 2) != 0,
                (colorMask & 4) != 0, (colorMask & 8) != 0);
        if (blendEnabled) {
            VRenderSystem.enableBlend();
        } else {
            VRenderSystem.disableBlend();
        }
        PipelineState.blendInfo.srcRgbFactor = srcRgbFactor;
        PipelineState.blendInfo.dstRgbFactor = dstRgbFactor;
        PipelineState.blendInfo.srcAlphaFactor = srcAlphaFactor;
        PipelineState.blendInfo.dstAlphaFactor = dstAlphaFactor;
        PipelineState.blendInfo.blendOp = blendOp;
        VRenderSystem.cull = cullEnabled;
    }
}
