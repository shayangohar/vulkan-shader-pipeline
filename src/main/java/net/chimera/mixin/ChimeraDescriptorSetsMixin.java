package net.chimera.mixin;

import net.chimera.render.ChimeraTextureBindingState;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Pack descriptors in VulkanMod's per-pipeline descriptor sets:
 * <ul>
 *   <li>Each descriptor pool entry takes its descriptor's own type. VulkanMod sizes every buffer
 *       entry as a dynamic uniform buffer and every image entry as a combined image sampler, so
 *       a set holding a pack storage buffer or storage image would not fit its pool.</li>
 *   <li>The descriptor writes and checks bind a pack's sampler override for a selector's image
 *       (ChimeraTextureBindingState) instead of the host's sampler.</li>
 * </ul>
 */
@Mixin(targets = "net.vulkanmod.vulkan.shader.Pipeline$DescriptorSets", remap = false)
public abstract class ChimeraDescriptorSetsMixin {
    @Shadow
    @Final
    private Pipeline pipeline;

    @ModifyArg(method = "createDescriptorPool",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkDescriptorPoolCreateInfo;"
                    + "pPoolSizes(Lorg/lwjgl/vulkan/VkDescriptorPoolSize$Buffer;)"
                    + "Lorg/lwjgl/vulkan/VkDescriptorPoolCreateInfo;"))
    private VkDescriptorPoolSize.Buffer chimera$poolSizesByType(VkDescriptorPoolSize.Buffer sizes) {
        List<UBO> buffers = this.pipeline.getBuffers();
        List<ImageDescriptor> images = this.pipeline.getImageDescriptors();
        for (int index = 0; index < buffers.size(); index++) {
            sizes.get(index).type(buffers.get(index).getType());
        }
        for (int index = 0; index < images.size(); index++) {
            sizes.get(buffers.size() + index).type(images.get(index).getType());
        }
        return sizes;
    }

    @Redirect(method = {"needsUpdate", "updateDescriptorSet"},
            at = @At(value = "INVOKE", target = "Lnet/vulkanmod/vulkan/shader/descriptor/ImageDescriptor;"
                    + "getSampler(Lnet/vulkanmod/vulkan/texture/VulkanImage;)J"),
            require = 2)
    private long chimera$packSampler(ImageDescriptor descriptor, VulkanImage image) {
        return ChimeraTextureBindingState.samplerForDescriptor(descriptor.getTextureUnit(), image,
                descriptor.getSampler(image));
    }
}
