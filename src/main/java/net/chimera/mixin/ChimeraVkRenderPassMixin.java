package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraVkRenderPassAccess;
import net.chimera.render.ChimeraTextureBindingState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.render.engine.VkSampler;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;

/** Copies only host entity texture bindings into the fixed Chimera slots. */
@Mixin(value = VkRenderPass.class, remap = false)
public abstract class ChimeraVkRenderPassMixin implements ChimeraVkRenderPassAccess {
    @Shadow
    protected RenderPipeline pipeline;

    @Shadow
    @Final
    protected HashMap<String, GpuBufferSlice> uniforms;

    @Shadow
    @Final
    protected java.util.Set<String> dirtyUniforms;

    /** Sky has both indexed celestial/star draws and non-indexed sky fans. */
    @WrapMethod(method = "draw")
    private void chimera$skyAttachments(int firstVertex, int vertexCount, Operation<Void> original) {
        var pass = net.chimera.render.ChimeraRenderer.getMainPass();
        if (pass == null || !ChimeraSkyBridge.shouldUsePackPipeline(this.pipeline)) {
            original.call(firstVertex, vertexCount);
            return;
        }
        pass.prepareProgramImages(ChimeraSkyBridge.pipeline());
        var outputs = ChimeraSkyBridge.outputPlan();
        boolean window = false;
        try {
            if (outputs != null && outputs.requiresDynamicAttachments()) {
                window = pass.beginPackFamilyWindow(outputs);
                if (!window) return;
                pass.rebindMainTarget();
            }
            original.call(firstVertex, vertexCount);
        } finally {
            if (window) pass.endPackFamilyWindow(outputs);
        }
    }

    /** Native indexed sky/particle passes may switch authored outputs between draws. */
    @WrapMethod(method = "drawIndexed")
    private void chimera$familyAttachments(int vertexOffset, int firstIndex, int vertexCount,
                                            int instanceCount, Operation<Void> original) {
        var pass = net.chimera.render.ChimeraRenderer.getMainPass();
        boolean sky = pass != null && ChimeraSkyBridge.shouldUsePackPipeline(this.pipeline);
        if (pass == null || (!sky && (!ChimeraEntityBridge.isParticleDrawActive()
                || !ChimeraEntityBridge.shouldUsePackPipeline(this.pipeline)))) {
            original.call(vertexOffset, firstIndex, vertexCount, instanceCount);
            return;
        }
        var family = sky ? null : pass.particlePipeline(ChimeraEntityBridge.activeFamily());
        if (!sky && family == null) {
            original.call(vertexOffset, firstIndex, vertexCount, instanceCount);
            return;
        }
        pass.prepareProgramImages(sky ? ChimeraSkyBridge.pipeline() : ChimeraEntityBridge.pipeline());
        var outputs = sky ? ChimeraSkyBridge.outputPlan() : family.outputPlan();
        boolean window = false;
        try {
            if (outputs != null && outputs.requiresDynamicAttachments()) {
                window = pass.beginPackFamilyWindow(outputs);
                if (!window) return;
                // This VkRenderPass already exists; no createRenderPass call
                // will reopen Vulkan rendering after the attachment switch.
                pass.rebindMainTarget();
            }
            original.call(vertexOffset, firstIndex, vertexCount, instanceCount);
        } finally {
            if (window) pass.endPackFamilyWindow(outputs);
        }
    }

    /**
     * VkRenderPass normally compiles the host pipeline from the format returned
     * by RenderPipeline.getVertexFormat(). During the guarded entity batch that
     * getter intentionally reports EXTENDED_ENTITY for the upload path. Letting
     * the host compiler see that temporary format would cache a host pipeline
     * with the wrong stride for later 36-byte entity draws. The command encoder
     * bridge binds Chimera's already-built pipeline, so this seam only records
     * the host pipeline for state and uniform lookup.
     */
    @Inject(method = "setPipeline", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$recordEntityHostPipeline(
            RenderPipeline renderPipeline,
            CallbackInfo callback
    ) {
        if (!ChimeraEntityBridge.shouldUsePackPipeline(renderPipeline)
                && !ChimeraSkyBridge.shouldUsePackPipeline(renderPipeline)) {
            return;
        }
        if (this.pipeline == null || this.pipeline != renderPipeline) {
            this.dirtyUniforms.addAll(this.uniforms.keySet());
        }
        this.pipeline = renderPipeline;
        callback.cancel();
    }

    @Inject(method = "bindTexture", at = @At("HEAD"), require = 1)
    private void chimera$bindEntityTexture(
            String name,
            GpuTextureView view,
            com.mojang.blaze3d.textures.GpuSampler sampler,
            CallbackInfo callback
    ) {
        if (view == null || sampler == null
                || !(view.texture() instanceof VkGpuTexture texture)
                || !(sampler instanceof VkSampler vkSampler)) {
            return;
        }
        int slot = chimera$textureSlot(name);
        if (slot < 0 || ((!ChimeraEntityBridge.isDrawActive()
                || !ChimeraEntityBridge.shouldUsePackPipeline(this.pipeline))
                && (!ChimeraSkyBridge.isDrawActive()
                || !ChimeraSkyBridge.shouldUsePackPipeline(this.pipeline)))) {
            return;
        }
        ChimeraTextureBindingState.markPackBinding(
                slot, texture.getVulkanImage(), sampler);
        VTextureSelector.bindTexture(slot, texture.getVulkanImage());
    }

    /** Resolves the host names used by entity RenderSetup into Chimera slots. */
    private static int chimera$textureSlot(String name) {
        int slot = VTextureSelector.getTextureIdx(name);
        if (slot >= 0) {
            return slot;
        }
        return switch (name) {
            case "LightTexture", "Lightmap" -> 2;
            case "Overlay", "OverlayTexture" -> 1;
            default -> -1;
        };
    }

    @Override
    public GpuBufferSlice chimera$uniform(String name) {
        return uniforms.get(name);
    }
}
