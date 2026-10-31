package net.chimera.mixin;

import net.chimera.render.EdgeClampedImage;
import net.chimera.render.SamplerProfile;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps an image vanilla clamped to its edge clamped (EdgeClampedImage). Every sampler VulkanMod
 * builds from flags for an image, its setFilter hook's included, goes through
 * updateTextureSampler(int, byte).
 */
@Mixin(value = VulkanImage.class, remap = false)
public abstract class ChimeraVulkanImageClampMixin implements EdgeClampedImage {
    @Shadow private long sampler;
    @Unique private boolean chimera$edgeClamp;

    @Shadow public abstract void updateTextureSampler(int maxLod, byte flags);

    @Override
    public void chimera$clampToEdge() {
        this.chimera$edgeClamp = true;
        int key = SamplerProfile.flagKey(this.sampler);
        if (key >= 0) updateTextureSampler(key >> 8, (byte) key);
    }

    @ModifyVariable(method = "updateTextureSampler(IB)V", at = @At("HEAD"), argsOnly = true, require = 1)
    private byte chimera$keepEdgeClamp(byte flags) {
        return this.chimera$edgeClamp ? (byte) (flags | SamplerManager.CLAMP_BIT) : flags;
    }
}
