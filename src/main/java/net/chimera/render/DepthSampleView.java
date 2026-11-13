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
 * host image keeps its own depth-stencil view and is never replaced. A retired
 * view goes through HandleRetirement: mid-frame it waits for the frame slot's
 * fence, since the command buffer still being recorded can already hold a
 * conversion that samples the old view, and destroying it before submission
 * loses the device (re-joining a world recreates the HDR depth image mid-frame).</p>
 */
final class DepthSampleView {
    /** Whether the view samples as R, R, R, 1, as Iris's shadow depth textures do (ImageViews.shadowDepth). */
    private final boolean shadow;
    private long view;
    /**
     * The image the view was made for, compared by object: a recreated image can reuse a freed
     * image's VkImage handle, and a view of the freed image must not be returned for it.
     */
    private VulkanImage image;

    /** A plain depth view: a read without compare gives (d, 0, 0, 1), as GL's depthtex does. */
    DepthSampleView() {
        this(false);
    }

    private DepthSampleView(boolean shadow) {
        this.shadow = shadow;
    }

    /** A view of a shadow depth image (shadowtex0 or shadowtex1), which samples as R, R, R, 1. */
    static DepthSampleView shadow() {
        return new DepthSampleView(true);
    }

    /** Returns the depth-only view of the image, creating it for a new image. */
    long ensure(VulkanImage image) {
        if (view != 0L && this.image == image) {
            return view;
        }
        // Create before retiring, so the new handle cannot reuse the old one.
        long next = shadow ? ImageViews.shadowDepth(image)
                : VulkanImage.createImageView(image.getId(), image.format, VK_IMAGE_ASPECT_DEPTH_BIT, image.mipLevels);
        destroy();
        view = next;
        this.image = view == 0L ? null : image;
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
        HandleRetirement.retire(() -> vkDestroyImageView(Vulkan.getVkDevice(), retired, null));
        view = 0L;
        image = null;
    }
}
