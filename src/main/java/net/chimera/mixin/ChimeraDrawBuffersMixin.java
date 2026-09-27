package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.chimera.render.ShadowSectionQueue;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import org.joml.Vector3d;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records execution only after a nonempty native terrain draw command, and
 * draws every facing during the shadow pass.
 */
@Mixin(value = DrawBuffers.class, remap = false)
public abstract class ChimeraDrawBuffersMixin {
    @Redirect(method = "buildDrawBatchesDirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexed(Lorg/lwjgl/vulkan/VkCommandBuffer;IIIII)V"), require = 1)
    private void chimera$direct(VkCommandBuffer cmd, int count, int instances, int first, int vertex, int base) {
        VK10.vkCmdDrawIndexed(cmd, count, instances, first, vertex, base);
        if (count > 0 && instances > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }

    /**
     * With backface culling on, VulkanMod stores each section's quads in
     * per-facing slots and draws only the facings that point toward the
     * camera. The light sees the others, so the shadow pass enables every
     * facing. Turning the culling branch off instead would draw only the
     * UNDEFINED slot, which holds almost nothing on those builds.
     */
    @Inject(method = "getMask", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$shadowFacings(Vector3d camera, RenderSection section,
                                        CallbackInfoReturnable<Integer> mask) {
        if (ShadowSectionQueue.active()) mask.setReturnValue((1 << QuadFacing.COUNT) - 1);
    }

    @Redirect(method = "buildDrawBatchesIndirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexedIndirect(Lorg/lwjgl/vulkan/VkCommandBuffer;JJII)V"), require = 1)
    private void chimera$indirect(VkCommandBuffer cmd, long buffer, long offset, int count, int stride) {
        VK10.vkCmdDrawIndexedIndirect(cmd, buffer, offset, count, stride);
        if (count > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }
}
