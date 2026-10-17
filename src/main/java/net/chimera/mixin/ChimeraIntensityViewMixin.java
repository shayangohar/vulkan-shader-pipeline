package net.chimera.mixin;

import net.chimera.render.IntensityTextureViews;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import static org.lwjgl.vulkan.VK10.VK_COMPONENT_SWIZZLE_R;

/** Gives the views of a RED texture being allocated an RRRR swizzle (IntensityTextureViews). */
@Mixin(value = VulkanImage.class, remap = false)
public abstract class ChimeraIntensityViewMixin {
    @ModifyArg(method = "createImageView(JIIII)J", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCreateImageView(Lorg/lwjgl/vulkan/VkDevice;"
                    + "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;"
                    + "Ljava/nio/LongBuffer;)I"), index = 1, require = 1)
    private static VkImageViewCreateInfo chimera$intensitySwizzle(VkImageViewCreateInfo info) {
        if (IntensityTextureViews.preparing()) {
            info.components().set(VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_R,
                    VK_COMPONENT_SWIZZLE_R);
        }
        return info;
    }
}
