package net.chimera.mixin;

import net.chimera.shaderpack.PackConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * VulkanMod's image builder rejects any format outside its own small table.
 * Pack colour targets use the exact formats in PackConfig.FMT_TO_VK, so the
 * builder takes their texel size from there.
 */
@Mixin(targets = "net.vulkanmod.vulkan.texture.VulkanImage$Builder", remap = false)
public abstract class ChimeraImageFormatSizeMixin {
    @Inject(method = "formatSize(I)I", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$packFormatSize(
            int format,
            CallbackInfoReturnable<Integer> callback
    ) {
        int bytes = PackConfig.formatBytes(format);
        if (bytes != 0) {
            callback.setReturnValue(bytes);
        }
    }
}
