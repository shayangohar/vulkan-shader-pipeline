package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.chimera.render.ChimeraTextureBindingState;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pack descriptors in VulkanMod's per-pipeline descriptor sets: the descriptor writes and checks
 * bind a pack's sampler override for a selector's image (ChimeraTextureBindingState) instead of the
 * host's sampler. Each pool entry takes its descriptor's own type in Reforged 0.3.5 itself, so a set
 * holding a pack storage buffer or storage image fits its pool (HostFixesTest).
 */
@Mixin(targets = "net.vulkanmod.vulkan.shader.Pipeline$DescriptorSets", remap = false)
public abstract class ChimeraDescriptorSetsMixin {
    @WrapOperation(method = {"needsUpdate", "updateDescriptorSet"},
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/shader/descriptor/ImageDescriptor;"
                    + "getSampler(Lnet/vulkanmod/vulkan/texture/VulkanImage;)J"),
            require = 2)
    private long chimera$packSampler(ImageDescriptor descriptor, VulkanImage image, Operation<Long> original) {
        return ChimeraTextureBindingState.samplerForDescriptor(descriptor.getTextureUnit(), image,
                original.call(descriptor, image));
    }
}
