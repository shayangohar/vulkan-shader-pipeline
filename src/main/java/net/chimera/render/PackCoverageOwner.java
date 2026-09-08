package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;

/** Owns the window-sized coverage image for one pack session. */
public final class PackCoverageOwner {
    public static final float EMPTY_SENTINEL = -1.0f;
    private VulkanImage image;
    private int width;
    private int height;

    public void ensure(int width, int height) {
        if (image != null && this.width == width && this.height == height) return;
        close();
        this.width = width;
        this.height = height;
        image = VulkanImage.builder(width, height)
                .setName("chimeraSceneCoverage")
                .setFormat(VK_FORMAT_R32_SFLOAT)
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .setLinearFiltering(false)
                .setClamp(true)
                .createVulkanImage();
    }

    public VulkanImage image() { return image; }
    public boolean isLive() { return image != null && image.getId() != 0L; }

    public void beginFrame(VkCommandBuffer commandBuffer) {
        if (!isLive()) throw new IllegalStateException("coverage image is not live");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(stack.floats(EMPTY_SENTINEL, EMPTY_SENTINEL,
                    EMPTY_SENTINEL, EMPTY_SENTINEL));
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            vkCmdClearColorImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clear, range);
            image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    public void close() {
        if (image != null) image.free();
        image = null;
        width = 0;
        height = 0;
    }
}
