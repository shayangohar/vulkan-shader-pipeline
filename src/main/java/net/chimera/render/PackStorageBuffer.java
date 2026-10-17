package net.chimera.render;

import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.UniformBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

/**
 * Device memory for one pack storage buffer. VulkanMod binds a pipeline's buffer descriptors from
 * uniform buffers, so this is one, created with storage usage instead. Its contents belong to the
 * pack, so it never grows: VulkanMod's capacity check would replace it with an empty buffer.
 */
public final class PackStorageBuffer extends UniformBuffer {
    public PackStorageBuffer(int size) {
        super(size, MemoryTypes.GPU_MEM);
    }

    /** Runs from the superclass constructor, before any field of this class is set. */
    @Override
    protected void createBuffer(int size) {
        this.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        super.createBuffer(size);
    }

    @Override
    public void checkCapacity(int size) {}
}
