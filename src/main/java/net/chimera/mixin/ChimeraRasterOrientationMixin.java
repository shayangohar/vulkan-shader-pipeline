package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.chimera.render.ChimeraRasterOrientation;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps GL row order in Chimera's GL-oriented passes by unflipping the rectangle VulkanMod hands
 * Vulkan. Reforged still records the viewport and scissor it was asked for: its render-state
 * snapshots restore them, and a world model drawn under an open screen takes such a snapshot.
 */
@Mixin(value = Renderer.class, remap = false)
public abstract class ChimeraRasterOrientationMixin {

    @WrapOperation(method = {"setViewport(IIIILorg/lwjgl/system/MemoryStack;)V", "resetViewport"},
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK10;vkCmdSetViewport("
                    + "Lorg/lwjgl/vulkan/VkCommandBuffer;ILorg/lwjgl/vulkan/VkViewport$Buffer;)V"), require = 2)
    private static void chimera$glViewport(VkCommandBuffer commandBuffer, int first, VkViewport.Buffer viewports,
                                           Operation<Void> original) {
        if (ChimeraRasterOrientation.boundPassGlOriented()) {
            VkViewport viewport = viewports.get(0);
            viewport.y(viewport.y() + viewport.height()).height(-viewport.height());
        }
        original.call(commandBuffer, first, viewports);
    }

    @WrapOperation(method = "setScissor(IIII)V", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VK10;"
            + "vkCmdSetScissor(Lorg/lwjgl/vulkan/VkCommandBuffer;ILorg/lwjgl/vulkan/VkRect2D$Buffer;)V"), require = 1)
    private static void chimera$glScissor(VkCommandBuffer commandBuffer, int first, VkRect2D.Buffer scissors,
                                          Operation<Void> original) {
        if (ChimeraRasterOrientation.boundPassGlOriented()) {
            VkRect2D scissor = scissors.get(0);
            int height = ((ChimeraRendererAccessor) Renderer.getInstance()).chimera$boundFramebuffer().getHeight();
            scissor.offset().y(height - scissor.offset().y() - scissor.extent().height());
        }
        original.call(commandBuffer, first, scissors);
    }
}
