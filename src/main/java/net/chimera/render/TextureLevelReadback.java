package net.chimera.render;

import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK10.vkWaitForFences;

/**
 * Reads one mip level of a texture into client memory, as glGetTexImage does: the level at its own
 * size, in rows of the destination's width. VulkanMod's ImageUtil.downloadTexture reads level 0
 * only, at the texture's size, whatever the destination holds.
 */
public final class TextureLevelReadback {
    private TextureLevelReadback() {}

    /**
     * Copies {@code level} of {@code image} into {@code destination}, an image {@code width} by
     * {@code height} of {@code texelBytes} a texel. Only the part both cover is copied. A level the
     * image lacks, or texels of another size, leave the destination as it was.
     */
    public static void read(VulkanImage image, int level, long destination, int width, int height, int texelBytes) {
        int copyWidth = Math.min(width, Math.max(1, image.width >> level));
        int copyHeight = Math.min(height, Math.max(1, image.height >> level));
        if (level < 0 || level >= image.mipLevels || image.formatSize != texelBytes
                || copyWidth <= 0 || copyHeight <= 0) {
            return;
        }
        long rowBytes = (long) copyWidth * texelBytes;
        try (MemoryStack stack = stackPush()) {
            LongBuffer buffer = stack.mallocLong(1);
            PointerBuffer allocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(rowBytes * copyHeight, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, buffer, allocation);
            copyLevel(stack, image, level, buffer.get(0), copyWidth, copyHeight);
            MemoryManager.MapAndCopy(allocation.get(0), data -> {
                for (int row = 0; row < copyHeight; row++) {
                    MemoryUtil.memCopy(data.get(0) + row * rowBytes,
                            destination + (long) row * width * texelBytes, rowBytes);
                }
            });
            MemoryManager.freeBuffer(buffer.get(0), allocation.get(0));
        }
    }

    /** Records and waits for the copy of the level's corner into {@code buffer}, then restores the image's layout. */
    private static void copyLevel(MemoryStack stack, VulkanImage image, int level, long buffer, int width, int height) {
        int previous = image.getCurrentLayout();
        CommandPool.CommandBuffer commands = DeviceManager.getGraphicsQueue().beginCommands();
        image.transitionImageLayout(stack, commands.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        ImageUtil.copyImageToBuffer(commands.getHandle(), buffer, image.getId(), level, width, height,
                0, 0, 0, 0, 0, image.aspect);
        // As ImageUtil.downloadTexture leaves it: a texture that was never laid out becomes readable.
        image.transitionImageLayout(stack, commands.getHandle(),
                previous == VK_IMAGE_LAYOUT_UNDEFINED ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : previous);
        long fence = DeviceManager.getGraphicsQueue().submitCommands(commands);
        vkWaitForFences(DeviceManager.vkDevice, fence, true, -1L);
    }
}
