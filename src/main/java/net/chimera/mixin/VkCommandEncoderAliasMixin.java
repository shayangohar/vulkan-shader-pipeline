package net.chimera.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.engine.VkCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes entity/outline loss after screen cycles.
 *
 * VulkanMod's VkCommandEncoder.beginRenderPass routes a pass into the main
 * buffer only when the pass's color view matches the live main-target color
 * texture. Vanilla's pooled render passes capture that view once and reuse
 * it - and after any main-target identity change (our phase flip, or a screen
 * mode cycle where the host pass exposed swapchain-adjacent views), the
 * captured view no longer compares equal. Those passes then fall into the
 * emulated-FBO path and render into a surface our composite never reads:
 * entities and the block outline vanish while terrain (recorded directly)
 * stays visible - exactly the observed state B.
 *
 * Redirect the view unwrap: when the view is any main-target-family view
 * (ours in either phase, or the host's), return the live phase texture so the
 * equality holds and the pass re-enters through rebindMainTarget. Genuine
 * non-main views (post-chain temps, FBOs) keep original behavior.
 */
@Mixin(value = VkCommandEncoder.class, remap = false)
public abstract class VkCommandEncoderAliasMixin {

    @Redirect(
            method = "createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/textures/GpuTextureView;texture()Lcom/mojang/blaze3d/textures/GpuTexture;"
            )
    )
    private GpuTexture chimera$mainFamilyAlias(GpuTextureView view) {
        if (ChimeraRenderer.mainTargetInteropActive() && ChimeraRenderer.isMainFamilyView(view)) {
            return ChimeraRenderer.getCurrentMainColorTexture();
        }
        return view.texture();
    }
}
