package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * A pack storage image read through a view of another format of the same texel size, as a GL
 * image unit reinterprets an image whose format qualifier differs from its own (Alpha Piscium
 * declares its R32F uimg_csr32f r32i for integer atomics). VulkanMod asks the descriptor for the
 * view it binds, so this one answers with the image's view in its format.
 */
public final class PackStorageViewDescriptor extends ImageDescriptor {
    private final int viewFormat;

    public PackStorageViewDescriptor(int binding, String type, String name, int textureSlot, int viewFormat) {
        super(binding, type, name, textureSlot, true);
        this.viewFormat = viewFormat;
    }

    @Override
    public long getImageView(VulkanImage image) {
        return image instanceof PackStorageImage storage ? storage.view(viewFormat) : super.getImageView(image);
    }
}
