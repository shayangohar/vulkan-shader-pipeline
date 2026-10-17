package net.chimera.mixin;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets the sampler a Chimera-owned image is read with. VulkanMod derives an image's sampler from
 * its creation flags, which cannot express the samplers pack targets, shadow maps and noise
 * textures need (ChimeraSamplers).
 */
@Mixin(value = VulkanImage.class, remap = false)
public interface ChimeraVulkanImageAccessor {
    @Accessor("sampler")
    void chimera$setSampler(long sampler);
}
