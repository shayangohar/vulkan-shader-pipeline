package net.chimera.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryBarrier;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_BARRIER;

/** Device-free checks for the structure passed to vkCmdPipelineBarrier. */
public final class PackVulkanBarriersHarness {
    private PackVulkanBarriersHarness() {}

    public static void main(String[] args) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = PackVulkanBarriers.createMemoryBarrier(
                    stack, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
            if (barrier.sType() != VK_STRUCTURE_TYPE_MEMORY_BARRIER) {
                throw new AssertionError("VkMemoryBarrier has invalid sType: " + barrier.sType());
            }
            if (barrier.srcAccessMask() != VK_ACCESS_TRANSFER_WRITE_BIT
                    || barrier.dstAccessMask() != VK_ACCESS_SHADER_READ_BIT) {
                throw new AssertionError("VkMemoryBarrier access masks were not preserved");
            }
        }
        System.out.println("[chimera] Vulkan memory barrier structure: PASS");
    }
}
