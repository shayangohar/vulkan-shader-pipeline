package net.chimera.mixin;

import net.chimera.render.ChimeraTextureBindingState;
import net.vulkanmod.vulkan.shader.DescriptorSets;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Applies pack sampler pairs only at VulkanMod descriptor writes and checks. */
@Mixin(value = DescriptorSets.class, remap = false)
public abstract class ChimeraDescriptorSetsMixin {
    @Redirect(method = "needsUpdate",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/texture/VulkanImage;getSampler()J"),
            require = 1)
    private long chimera$needsUpdateSampler(VulkanImage image) {
        return ChimeraTextureBindingState.samplerForDescriptor(image, image.getSampler());
    }

    @Redirect(method = "updateDescriptorSet",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/texture/VulkanImage;getSampler()J"),
            require = 1)
    private long chimera$updateDescriptorSampler(VulkanImage image) {
        return ChimeraTextureBindingState.samplerForDescriptor(image, image.getSampler());
    }
}
