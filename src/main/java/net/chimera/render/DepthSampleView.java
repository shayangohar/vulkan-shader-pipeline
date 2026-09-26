package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * Owns one depth-aspect view of a raw depth image, shared by every pack depth
 * conversion.
 *
 * <p>Sampling a combined depth-stencil attachment needs a depth-only view; the
 * host image keeps its own depth-stencil view and is never replaced. A view
 * owned by Chimera cannot use VulkanMod's deferred free queue, so destroying it
 * waits for GPU idle first: a resize or pack replacement must not race an
 * in-flight conversion that still reads it.</p>
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
        if (view != 0L) {
            // The old view can belong to a depth image retired by a resize or
            // dimension change; the GPU may still be reading it.
            Vulkan.waitIdle();
        }
        destroy();
        view = VulkanImage.createImageView(nextImageId, image.format, VK_IMAGE_ASPECT_DEPTH_BIT,
                image.arrayLayers, image.mipLevels);
        imageId = view == 0L ? 0L : nextImageId;
        return view;
    }

    boolean valid() {
        return view != 0L;
    }

    /** Destroys the view after the GPU has finished using it. */
    void destroy() {
        if (view == 0L) {
            return;
        }
        Vulkan.waitIdle();
        vkDestroyImageView(Vulkan.getVkDevice(), view, null);
        view = 0L;
        imageId = 0L;
    }
}
