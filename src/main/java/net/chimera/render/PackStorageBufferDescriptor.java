package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.UBO;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;

/**
 * A pack-owned storage buffer descriptor carried through VulkanMod's ordered buffer descriptor
 * list. It has no fields: its {@link PackStorageBuffer} is attached as the descriptor's own buffer
 * (PackStorageBufferOwner), which VulkanMod binds whole at offset 0 instead of writing uniforms.
 */
public final class PackStorageBufferDescriptor extends UBO {
    private final int logicalIndex;

    public PackStorageBufferDescriptor(int binding, int stages, int size, int logicalIndex) {
        super(binding, stages, size, null);
        this.logicalIndex = logicalIndex;
    }

    /** The pack's index of the buffer this descriptor binds (Iris bufferObject). */
    public int logicalIndex() {
        return logicalIndex;
    }

    @Override
    public int getType() {
        return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    }
}
