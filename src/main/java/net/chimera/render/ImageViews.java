package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkImageViewUsageCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_ONE;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_R;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/**
 * Views VulkanMod does not create: of Chimera's own 1D and 3D images, with alpha read as one, and
 * of a shadow depth image read as R, R, R, 1.
 */
final class ImageViews {
    private ImageViews() {}

    /** A view of the first level and layer of {@code image}, as {@code viewType}. */
    static long create(long image, int viewType, int format, int aspect) {
        return create(image, viewType, format, aspect, 1, VK_COMPONENT_SWIZZLE_IDENTITY, 0);
    }

    /**
     * A storage-only colour view of a mutable-format image in another format of its size. Limiting
     * its usage keeps the view from needing the format's sampled-image support.
     */
    static long createStorage(long image, int viewType, int format) {
        return create(image, viewType, format, VK_IMAGE_ASPECT_COLOR_BIT, 1, VK_COMPONENT_SWIZZLE_IDENTITY,
                VK_IMAGE_USAGE_STORAGE_BIT);
    }

    /**
     * Makes a freshly created colour image sample alpha as 1, as GL samples a format without alpha
     * that Chimera stores in an RGBA image. Attachments and storage keep the identity level views.
     */
    static void sampleAlphaAsOne(VulkanImage image) {
        long view = create(image.getId(), VK_IMAGE_VIEW_TYPE_2D, image.format, VK_IMAGE_ASPECT_COLOR_BIT,
                image.mipLevels, VK_COMPONENT_SWIZZLE_ONE, 0);
        // No command has recorded the replaced view yet.
        vkDestroyImageView(Vulkan.getVkDevice(), image.getImageView(), null);
        ((ChimeraVulkanImageAccessor) image).chimera$setMainImageView(view);
    }

    /** The component mapping of Iris's shadow depth swizzle, GL's {RED, RED, RED, ONE}. */
    private static final int[] SHADOW_DEPTH_COMPONENTS = {VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R,
            VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_ONE};

    /** The shadow depth swizzle's r, g, b and a mapping, as a copy. */
    static int[] shadowDepthComponents() {
        return SHADOW_DEPTH_COMPONENTS.clone();
    }

    /**
     * A depth-only view of a shadow depth image that samples as R, R, R, 1, as Iris swizzles its
     * shadowtex textures (ShadowRenderer.configureDepthSampler, for packs that read the depth from z):
     * a read without depth compare finds the depth in every colour channel (audit T-S3). A depth
     * compare happens before the swizzle, so its result still comes back in R.
     */
    static long shadowDepth(VulkanImage image) {
        return create(Vulkan.getVkDevice(), image.getId(), VK_IMAGE_VIEW_TYPE_2D, image.format,
                VK_IMAGE_ASPECT_DEPTH_BIT, image.mipLevels, SHADOW_DEPTH_COMPONENTS, 0);
    }

    private static long create(long image, int viewType, int format, int aspect, int levelCount, int alpha,
                               int usage) {
        return create(Vulkan.getVkDevice(), image, viewType, format, aspect, levelCount, new int[] {
                VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, alpha},
                usage);
    }

    /**
     * A view on {@code device}, with {@code components} as its r, g, b and a mapping. {@code usage}
     * 0 leaves the view the image's usage; otherwise the view takes only it.
     */
    static long create(VkDevice device, long image, int viewType, int format, int aspect, int levelCount,
                       int[] components, int usage) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(viewType)
                    .format(format);
            if (usage != 0) {
                info.pNext(VkImageViewUsageCreateInfo.calloc(stack).sType$Default().usage(usage).address());
            }
            info.components().set(components[0], components[1], components[2], components[3]);
            info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(levelCount)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer view = stack.mallocLong(1);
            if (vkCreateImageView(device, info, null, view) != VK_SUCCESS) {
                throw new IllegalStateException("image view creation failed");
            }
            return view.get(0);
        }
    }
}
