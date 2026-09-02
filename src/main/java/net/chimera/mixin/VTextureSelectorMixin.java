package net.chimera.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.vulkanmod.vulkan.texture.VTextureSelector;

/** Adds only the unused selector names required by Chimera's bounded targets. */
@Mixin(VTextureSelector.class)
public abstract class VTextureSelectorMixin {
    @Inject(method = "getTextureIdx", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
    private static void chimera$extendedSampler(String name, CallbackInfoReturnable<Integer> cir) {
        if (name != null && name.startsWith("Sampler")) {
            try {
                int index = Integer.parseInt(name.substring("Sampler".length()));
                if (index >= 8 && index <= 11) {
                    cir.setReturnValue(index);
                }
            } catch (NumberFormatException ignored) {
                // VulkanMod handles malformed names as before.
            }
        }
    }
}
