package net.chimera.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

/** Adds only the unused selector names required by Chimera's bounded targets. */
@Mixin(VTextureSelector.class)
public abstract class VTextureSelectorMixin {
    /** M7.4 depth slots and M7.5 pack-owned sampled resource slots. */
    @Unique
    private static final VulkanImage[] chimera$extendedTextures = new VulkanImage[14];

    @Inject(method = "getTextureIdx", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void chimera$extendedSampler(String name, CallbackInfoReturnable<Integer> cir) {
        if (name != null && name.startsWith("Sampler")) {
            try {
                int index = Integer.parseInt(name.substring("Sampler".length()));
                if (index >= 8 && index <= 21) {
                    cir.setReturnValue(index);
                }
            } catch (NumberFormatException ignored) {
                // VulkanMod handles malformed names as before.
            }
        }
    }

    @Inject(method = "bindTexture(ILnet/vulkanmod/vulkan/texture/VulkanImage;)V",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void chimera$bindExtendedTexture(int index, VulkanImage texture,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        int extended = chimera$extendedIndex(index);
        if (extended >= 0) {
            chimera$extendedTextures[extended] = texture;
            ci.cancel();
        }
    }

    @Inject(method = "getImage", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void chimera$getExtendedImage(int index, CallbackInfoReturnable<VulkanImage> cir) {
        int extended = chimera$extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }

    @Inject(method = "getBoundTexture(I)Lnet/vulkanmod/vulkan/texture/VulkanImage;",
            at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void chimera$getExtendedBoundTexture(int index,
            CallbackInfoReturnable<VulkanImage> cir) {
        int extended = chimera$extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }

    @Unique
    private static int chimera$extendedIndex(int index) {
        return index >= 8 && index <= 21 ? index - 8 : -1;
    }
}
