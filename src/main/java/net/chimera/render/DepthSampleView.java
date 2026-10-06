package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * Owns one depth-aspect view of a raw depth image, shared by every pack depth
 * conversion.
 *
 * <p>Sampling a combined depth-stencil attachment needs a depth-only view; the
 * host image keeps its own depth-stencil view and is never replaced. A retired
 * view is destroyed through VulkanMod's frame ops, once the current frame's
 * fence has signalled. Waiting for GPU idle is not enough: the command buffer
 * still being recorded can already hold a conversion that samples the old
 * view, and destroying it before submission loses the device (re-joining a
 * world recreates the HDR depth image mid-frame).</p>
 */
final class DepthSampleView {
    private long view;
    private long imageId;

    /** Returns the depth-only view of the image, creating it for a new image identity. */
    long ensure(VulkanImage image) {
        long nextImageId = image.getId();
        if (view != 0L && imageId == nextImageId) {
            return view;
        }
        // Create before retiring, so the new handle cannot reuse the old one.
        long next = VulkanImage.createImageView(nextImageId, image.format, VK_IMAGE_ASPECT_DEPTH_BIT,
                image.arrayLayers, image.mipLevels);
        destroy();
        view = next;
        imageId = view == 0L ? 0L : nextImageId;
        return view;
    }

    boolean valid() {
        return view != 0L;
    }

    /** Retires the view; it is destroyed after every recorded use has finished on the GPU. */
    void destroy() {
        if (view == 0L) {
            return;
        }
        long retired = view;
        MemoryManager.getInstance().addFrameOp(
                () -> vkDestroyImageView(Vulkan.getVkDevice(), retired, null));
        view = 0L;
        imageId = 0L;
    }
}
