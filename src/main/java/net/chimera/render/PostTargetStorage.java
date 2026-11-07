package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;

/**
 * The colour targets a post pass or a hand program stores to as colorimgN (TargetImages), held in
 * the general layout for its draws: for a pass, the images the chain reads now
 * (PackPostTargets.storageSource), as a pass compute binds them; for the hand, the images world
 * programs read (PackPostTargets.worldStorageImage). Iris binds the target's texture to an image
 * unit, which GL needs no layout for; VulkanMod moves only sampled images, and rejects one it finds
 * general, so each goes back to the sampled layout after the draws. PackPostTargets holds a pass's
 * once its output copies are recorded, just before the draw opens, and releases them as it closes:
 * holding them earlier made Rethinking Voxels' prepare2 copy its colortex3 out of GENERAL
 * ("Unexpected value:1"), and the runtime fallback turned the whole post chain off. The hand's are
 * held before its lane run opens and released when the run ends (PackFamilyWindows.drawRun).
 */
final class PostTargetStorage {
    private final List<VulkanImage> held = new ArrayList<>();

    /** The stages that store to a held target: the hand's geometry stage too, where the device runs one. */
    private static int shaderStages() {
        return VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                | (ChimeraDevice.geometryShader() ? VK_PIPELINE_STAGE_GEOMETRY_SHADER_BIT : 0);
    }

    /** Whether any target is held in the general layout. */
    boolean holding() {
        return !this.held.isEmpty();
    }

    /** Whether {@code image} is held in the general layout. */
    boolean holds(VulkanImage image) {
        return this.held.contains(image);
    }

    /**
     * Moves each stored target's image, as {@code images} names it, into the general layout, after
     * every earlier use. A target already held stays as it is. Recorded outside a render pass.
     */
    void begin(VkCommandBuffer commandBuffer, IntFunction<VulkanImage> images, List<Integer> stored) {
        if (stored.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target : stored) {
                VulkanImage image = images.apply(target);
                if (image == null || this.held.contains(image)) continue;
                VulkanImage.transitionLayout(stack, commandBuffer, image, image.getCurrentLayout(),
                        VK_IMAGE_LAYOUT_GENERAL, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                                | VK_ACCESS_TRANSFER_WRITE_BIT,
                        shaderStages(), VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                this.held.add(image);
            }
        }
    }

    /** Returns the images to the sampled layout once the draws have written them. Recorded outside a render pass. */
    void end(VkCommandBuffer commandBuffer) {
        if (this.held.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (VulkanImage image : this.held) {
                VulkanImage.transitionLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_GENERAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, shaderStages(), VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_SHADER_READ_BIT);
            }
        } finally {
            this.held.clear();
        }
    }
}
