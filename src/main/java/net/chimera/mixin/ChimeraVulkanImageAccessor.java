package net.chimera.mixin;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets the sampler and view a Chimera-owned image is read with. VulkanMod derives an image's
 * sampler from its creation flags, which cannot express the samplers pack targets, shadow maps and
 * noise textures need (ChimeraSamplers), and gives every view an identity swizzle.
 */
@Mixin(value = VulkanImage.class, remap = false)
public interface ChimeraVulkanImageAccessor {
    @Accessor("sampler")
    void chimera$setSampler(long sampler);

    /** The view descriptors sample the image through; VulkanImage.free destroys it. */
    @Accessor("mainImageView")
    void chimera$setMainImageView(long view);

    /** The Vulkan usage flags the image was created with. */
    @Accessor("usage")
    int chimera$usage();
}
