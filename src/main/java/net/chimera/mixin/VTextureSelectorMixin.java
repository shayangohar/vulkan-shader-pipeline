package net.chimera.mixin;

import net.chimera.shaderpack.SelectorNamespace;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds only the unused selector names required by Chimera's bounded targets. */
@Mixin(VTextureSelector.class)
public abstract class VTextureSelectorMixin {
    /** Canonical image identity for every Chimera-owned extended selector. */
    @Unique
    private static final VulkanImage[] chimera$extendedTextures =
            new VulkanImage[SelectorNamespace.EXTENDED_CAPACITY];

    @Inject(method = "getTextureIdx", at = @At("HEAD"), cancellable = true)
    private static void chimera$extendedSampler(String name, CallbackInfoReturnable<Integer> cir) {
        if (name != null && name.startsWith("Sampler")) {
            try {
                int index = Integer.parseInt(name.substring("Sampler".length()));
                if (SelectorNamespace.extendedIndex(index) >= 0) {
                    cir.setReturnValue(index);
                }
            } catch (NumberFormatException ignored) {
                // VulkanMod handles malformed names as before.
            }
        }
    }

    @Inject(method = "bindTexture(ILnet/vulkanmod/vulkan/texture/VulkanImage;)V",
            at = @At("HEAD"), cancellable = true)
    private static void chimera$bindExtendedTexture(int index, VulkanImage texture,
            CallbackInfo ci) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            chimera$extendedTextures[extended] = texture;
            ci.cancel();
        }
    }

    @Inject(method = "getImage", at = @At("HEAD"), cancellable = true)
    private static void chimera$getExtendedImage(int index, CallbackInfoReturnable<VulkanImage> cir) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }

    @Inject(method = "getBoundTexture(I)Lnet/vulkanmod/vulkan/texture/VulkanImage;",
            at = @At("HEAD"), cancellable = true)
    private static void chimera$getExtendedBoundTexture(int index,
            CallbackInfoReturnable<VulkanImage> cir) {
        int extended = SelectorNamespace.extendedIndex(index);
        if (extended >= 0) {
            cir.setReturnValue(chimera$extendedTextures[extended]);
        }
    }
}
