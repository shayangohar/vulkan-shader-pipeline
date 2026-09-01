package net.chimera.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraVkRenderPassAccess;
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
        if (!ChimeraEntityBridge.isDrawActive()) {
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
        if (!ChimeraEntityBridge.isDrawActive() || view == null || sampler == null) {
            return;
        }
        int slot = chimera$textureSlot(name);
        if (slot < 0 || !(view.texture() instanceof VkGpuTexture texture)
                || !(sampler instanceof VkSampler vkSampler)) {
            return;
        }
        texture.getVulkanImage().setSampler(vkSampler.getId());
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
