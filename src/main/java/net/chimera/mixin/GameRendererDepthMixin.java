package net.chimera.mixin;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla clears depth before first-person hand rendering through
 * CommandEncoder.clearDepthTexture(GpuTexture, double). Two cases:
 * - A chimera pass is recording: the texture is the depth attachment of that
 *   pass, so clear it in-pass via vkCmdClearAttachments on the bound
 *   framebuffer (GL_DEPTH_BUFFER_BIT = 256).
 * - No pass is recording (chimera closed the HDR pass at end of level
 *   render): fall through to the host implementation, which records the clear
 *   value; the aux pass' depth CLEAR load-op applies it on reopen.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererDepthMixin {

    @Redirect(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"
            )
    )
    private void chimera$clearDepthInPass(CommandEncoder instance, GpuTexture depthTexture, double clearDepth) {
        if (!ChimeraRenderer.isInstalled()) {
            instance.clearDepthTexture(depthTexture, clearDepth);
            return;
        }

        ChimeraRenderer.debugDepthCallSite();

        if (Renderer.getInstance().getBoundRenderPass() != null) {
            ChimeraRenderer.debugDepthRedirect();
            // Mirror the host's clear preamble exactly: vkCmdClearAttachments
            // respects the current scissor, and a stale depth mask from world
            // rendering can suppress the clear.
            GlStateManager._disableScissorTest();
            GlStateManager._depthMask(true);
            GlStateManager._colorMask(true, true, true, true);
            Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT);
        } else {
            // No pass recording: force-reopen the HDR pass with its
            // depth-CLEAR variant right now. The hand's own render pass will
            // then alias into the freshly cleared buffer.
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            pass.requestPendingDepthClear();
            pass.reopenWithPendingClear();
        }
    }
}
