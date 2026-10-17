package net.chimera.render;

import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateImageView;

/** Views of Chimera's own 1D and 3D images; VulkanMod creates 2D views only. */
final class ImageViews {
    private ImageViews() {}

    /** A view of the first level and layer of {@code image}, as {@code viewType}. */
    static long create(long image, int viewType, int format, int aspect) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(viewType)
                    .format(format);
            info.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer view = stack.mallocLong(1);
            if (vkCreateImageView(Vulkan.getVkDevice(), info, null, view) != VK_SUCCESS) {
                throw new IllegalStateException("image view creation failed");
            }
            return view.get(0);
        }
    }
}
