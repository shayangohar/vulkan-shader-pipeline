package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.ChimeraShadowCasters;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first-person hand around Chimera's frame:
 * - A hand the pack's schedule already drew (ChimeraHandRenderer) is not drawn again.
 * - Vanilla clears depth before the hand with RenderSystem.clear, which VulkanMod records into
 *   the bound framebuffer. When Chimera's world resolve left none bound, the clear would be
 *   lost; the output reopens with a depth-clearing pass instead.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDepthMixin {

    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;"
                    + "renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;"
                    + "Lnet/minecraft/client/player/LocalPlayer;I)V"), require = 1)
    private void chimera$skipLateHand(ItemInHandRenderer renderer, float tick, PoseStack poses,
                                      MultiBufferSource.BufferSource buffers, LocalPlayer player, int light) {
        if (!ChimeraHandRenderer.diverted()) renderer.renderHandsWithItems(tick, poses, buffers, player, light);
    }

    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void chimera$destroyHand(CallbackInfo callback) { ChimeraHandRenderer.destroy(); }

    @Inject(method = "close", at = @At("HEAD"), require = 1)
    private void chimera$destroyShadowCasters(CallbackInfo callback) { ChimeraShadowCasters.destroy(); }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V"), require = 1)
    private void chimera$clearHandDepth(int mask, boolean checkError, Operation<Void> original) {
        if (ChimeraRenderer.segmentsActive() && Renderer.getInstance().getBoundRenderPass() == null) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            pass.requestPendingDepthClear();
            pass.reopenWithPendingClear();
            return;
        }
        original.call(mask, checkError);
    }
}
