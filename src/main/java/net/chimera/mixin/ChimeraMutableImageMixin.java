package net.chimera.mixin;

import net.chimera.render.TargetFormatViews;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT;

/**
 * Creates the image VulkanMod allocates while TargetFormatViews is preparing one as mutable, so
 * views in other formats of its texel size can read it.
 */
@Mixin(value = MemoryManager.class, remap = false)
public abstract class ChimeraMutableImageMixin {
    @ModifyArg(method = "createImage", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/util/vma/Vma;vmaCreateImage(JLorg/lwjgl/vulkan/VkImageCreateInfo;"
                    + "Lorg/lwjgl/util/vma/VmaAllocationCreateInfo;Ljava/nio/LongBuffer;Lorg/lwjgl/PointerBuffer;"
                    + "Lorg/lwjgl/util/vma/VmaAllocationInfo;)I"), index = 1, require = 1)
    private static VkImageCreateInfo chimera$mutableFormat(VkImageCreateInfo info) {
        if (TargetFormatViews.preparing()) info.flags(info.flags() | VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT);
        return info;
    }
}
