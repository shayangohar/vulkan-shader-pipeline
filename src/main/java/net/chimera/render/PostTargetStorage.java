package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;

/**
 * The colour targets a post pass stores to as colorimgN (TargetImages), held in the general
 * layout for its draw: the images the chain reads now (PackPostTargets.storageSource), as a pass
 * compute binds them. Iris binds the target's texture to an image unit, which GL needs no layout
 * for; VulkanMod moves only sampled images, and rejects one it finds general, so each goes back to
 * the sampled layout after the draw.
 */
final class PostTargetStorage {
    private static final int SHADER_STAGES =
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    private final List<VulkanImage> held = new ArrayList<>();

    /** Moves each target's image into the general layout, after every earlier use. Recorded outside a render pass. */
    void begin(VkCommandBuffer commandBuffer, PackPostTargets targets, List<Integer> stored) {
        if (stored.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : stored) {
                VulkanImage image = targets.storageSource(commandBuffer, target);
                if (image == null || this.held.contains(image)) continue;
                VulkanImage.transitionLayout(stack, commandBuffer, image, image.getCurrentLayout(),
                        VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                                | VK_ACCESS_TRANSFER_WRITE_BIT,
                        SHADER_STAGES, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                this.held.add(image);
            }
        }
    }

    /** Returns the images to the sampled layout once the draw has written them. Recorded outside a render pass. */
    void end(VkCommandBuffer commandBuffer) {
        if (this.held.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (VulkanImage image : this.held) {
                VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, SHADER_STAGES, VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
        } finally {
            this.held.clear();
        }
    }
}
