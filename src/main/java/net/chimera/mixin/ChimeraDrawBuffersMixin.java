package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Records execution only after a nonempty native terrain draw command. */
@Mixin(value = DrawBuffers.class, remap = false)
public abstract class ChimeraDrawBuffersMixin {
    @Redirect(method = "buildDrawBatchesDirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexed(Lorg/lwjgl/vulkan/VkCommandBuffer;IIIII)V"), require = 1)
    private void chimera$direct(VkCommandBuffer cmd, int count, int instances, int first, int vertex, int base) {
        VK10.vkCmdDrawIndexed(cmd, count, instances, first, vertex, base);
        if (count > 0 && instances > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }

    @Redirect(method = "buildDrawBatchesIndirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexedIndirect(Lorg/lwjgl/vulkan/VkCommandBuffer;JJII)V"), require = 1)
    private void chimera$indirect(VkCommandBuffer cmd, long buffer, long offset, int count, int stride) {
        VK10.vkCmdDrawIndexedIndirect(cmd, buffer, offset, count, stride);
        if (count > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }
}
