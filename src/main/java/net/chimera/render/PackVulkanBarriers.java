package net.chimera.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import net.vulkanmod.vulkan.memory.buffer.Buffer;

import java.util.Collection;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;

/**
 * Synchronization for pack-owned advanced images.
 *
 * VulkanMod does not load VK_KHR_synchronization2 on this runtime. The legacy
 * barrier command is available on the same device and expresses the same
 * dependency with the older stage and access masks. Keep the scope broad here:
 * these barriers protect shared storage-image and sampled-image aliases at the
 * shadow seam, where correctness is more important than speculative overlap.
 */
final class PackVulkanBarriers {
    private PackVulkanBarriers() {}

    static void beforeTransfer(VkCommandBuffer commandBuffer, MemoryStack stack) {
        barrier(commandBuffer, stack,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                        | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);
    }

    static void afterTransfer(VkCommandBuffer commandBuffer, MemoryStack stack) {
        barrier(commandBuffer, stack,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    static void afterStorageBufferTransfer(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            afterTransfer(commandBuffer, stack);
        }
    }

    static void beforeStorageBufferUse(VkCommandBuffer commandBuffer,
                                       Collection<Buffer> buffers) {
        if (commandBuffer == null || buffers == null || buffers.isEmpty()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferMemoryBarrier.Buffer barriers = VkBufferMemoryBarrier.calloc(buffers.size(), stack);
            int index = 0;
            for (Buffer buffer : buffers) {
                if (buffer == null) continue;
                barriers.get(index++)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT
                                | VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT)
                        .srcQueueFamilyIndex(-1)
                        .dstQueueFamilyIndex(-1)
                        .buffer(buffer.getId())
                        .offset(0L)
                        .size(buffer.getBufferSize());
            }
            if (index > 0) {
                barriers.limit(index);
                vkCmdPipelineBarrier(commandBuffer,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                        0, null, barriers, null);
            }
        }
    }

    static void beforeCompute(VkCommandBuffer commandBuffer, MemoryStack stack) {
        barrier(commandBuffer, stack,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    static void afterCompute(VkCommandBuffer commandBuffer, MemoryStack stack) {
        barrier(commandBuffer, stack,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    private static void barrier(
            VkCommandBuffer commandBuffer,
            MemoryStack stack,
            int sourceStage,
            int sourceAccess,
            int destinationStage,
            int destinationAccess
    ) {
        VkMemoryBarrier.Buffer memory = VkMemoryBarrier.calloc(1, stack)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0,
                memory, null, null);
    }
}
