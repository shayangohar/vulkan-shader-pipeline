package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_IDENTITY;
import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_ONE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;
import static org.lwjgl.vulkan.VK10.vkDestroyImageView;

/** Views VulkanMod does not create: of Chimera's own 1D and 3D images, and with alpha read as one. */
final class ImageViews {
    private ImageViews() {}

    /** A view of the first level and layer of {@code image}, as {@code viewType}. */
    static long create(long image, int viewType, int format, int aspect) {
        return create(image, viewType, format, aspect, 1, VK_COMPONENT_SWIZZLE_IDENTITY);
    }

    /**
     * Makes a freshly created colour image sample alpha as 1, as GL samples a format without alpha
     * that Chimera stores in an RGBA image. Attachments and storage keep the identity level views.
     */
    static void sampleAlphaAsOne(VulkanImage image) {
        long view = create(image.getId(), VK_IMAGE_VIEW_TYPE_2D, image.format, VK_IMAGE_ASPECT_COLOR_BIT,
                image.mipLevels, VK_COMPONENT_SWIZZLE_ONE);
        // No command has recorded the replaced view yet.
        vkDestroyImageView(Vulkan.getVkDevice(), image.getImageView(), null);
        ((ChimeraVulkanImageAccessor) image).chimera$setMainImageView(view);
    }

    private static long create(long image, int viewType, int format, int aspect, int levelCount, int alpha) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(viewType)
                    .format(format);
            info.components().a(alpha);
            info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(levelCount)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer view = stack.mallocLong(1);
            if (vkCreateImageView(Vulkan.getVkDevice(), info, null, view) != VK_SUCCESS) {
                throw new IllegalStateException("image view creation failed");
            }
            return view.get(0);
        }
    }
}
