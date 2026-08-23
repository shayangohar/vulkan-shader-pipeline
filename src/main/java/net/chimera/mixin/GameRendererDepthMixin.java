package net.chimera.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla clears depth before first-person hand rendering through
 * CommandEncoder.clearDepthTexture(GpuTexture, double). That targets a texture
 * handle, which cannot be cleared while it is the depth attachment of the
 * currently recording render pass - exactly chimera's state at that point in
 * the frame. Redirect to an in-pass attachment clear on the bound framebuffer
 * instead (GL_DEPTH_BUFFER_BIT = 256), mirroring the proven host-compatible
 * approach. When chimera is not installed, vanilla's path works unchanged.
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
        if (ChimeraRenderer.isInstalled()) {
            Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT);
        } else {
            instance.clearDepthTexture(depthTexture, clearDepth);
        }
    }
}
