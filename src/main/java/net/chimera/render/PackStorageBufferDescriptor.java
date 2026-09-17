package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.UBO;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;

/**
 * A pack-owned storage buffer descriptor carried through VulkanMod's existing
 * ordered UBO list. The list is used only as a descriptor transport here; the
 * shader type and exact range are storage-buffer semantics.
 */
public final class PackStorageBufferDescriptor extends UBO {
    public PackStorageBufferDescriptor(String name, int binding, int stages, int size) {
        super(name, binding, stages, size, null);
        setUseGlobalBuffer(false);
    }

    @Override
    public int getType() {
        return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
    }
}
