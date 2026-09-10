package net.chimera.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraVkRenderPassAccess;
import net.vulkanmod.render.engine.VkCommandEncoder;
import net.vulkanmod.render.engine.VkGpuBuffer;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Binds the pack entity pipeline while inheriting the host render state. */
@Mixin(value = VkCommandEncoder.class, remap = false)
public abstract class ChimeraVkCommandEncoderMixin {
    @Inject(method = "trySetup", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$bindEntityPipeline(
            VkRenderPass renderPass,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (!ChimeraEntityBridge.isDrawActive()) {
            return;
        }

        RenderPipeline hostPipeline = renderPass.getPipeline();
        if (!ChimeraEntityBridge.shouldUsePackPipeline(hostPipeline)) {
            return;
        }
        // The pack entity adapter inherits the host depth state and must have
        // the host depth attachment available.  A depthless pass can be a
        // screen, inventory, or other overlay draw that happens to reuse an
        // entity-looking pipeline.  Keep that draw on the host path instead
        // of placing its geometry in front of the world.
        if (!renderPass.hasDepthTexture()) {
            return;
        }
        var packPipeline = ChimeraEntityBridge.pipeline();
        if (hostPipeline == null || packPipeline == null) {
            callback.setReturnValue(false);
            return;
        }

        VkCommandEncoder encoder = (VkCommandEncoder) (Object) this;
        encoder.applyPipelineState(hostPipeline);
        if (renderPass.isScissorEnabled()) {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(renderPass.getScissorX(), renderPass.getScissorY(),
                    renderPass.getScissorWidth(), renderPass.getScissorHeight());
        } else {
            GlStateManager._disableScissorTest();
        }
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(packPipeline);
        ChimeraEntityBridge.notePipelineBound(hostPipeline);
        bindHostUniforms(renderPass, packPipeline);
        renderer.uploadAndBindUBOs(packPipeline);
        callback.setReturnValue(true);
    }

    private static void bindHostUniforms(
            VkRenderPass renderPass,
            net.vulkanmod.vulkan.shader.GraphicsPipeline packPipeline
    ) {
        for (var ubo : packPipeline.getBuffers()) {
            ubo.setUseGlobalBuffer(true);
        }
        if (!(renderPass instanceof ChimeraVkRenderPassAccess access)) {
            return;
        }
        copyUniform(access.chimera$uniform("DynamicTransforms"),
                packPipeline.getUBO("DynamicTransforms"));
        copyUniform(access.chimera$uniform("Projection"),
                packPipeline.getUBO("Projection"));
    }

    private static void copyUniform(GpuBufferSlice source, net.vulkanmod.vulkan.shader.descriptor.UBO target) {
        if (source == null || target == null || !(source.buffer() instanceof VkGpuBuffer buffer)) {
            return;
        }
        target.setUseGlobalBuffer(false);
        target.getBufferSlice().set(buffer.getBuffer(), source.offset(), (int) source.length());
    }
}
