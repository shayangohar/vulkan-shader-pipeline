package net.chimera.mixin;

import net.chimera.render.ChimeraDepthViewOverride;
import net.chimera.render.ChimeraTextureBindingState;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies Chimera's depth-only view only while a pack depth conversion binds it. */
@Mixin(value = ImageDescriptor.class, remap = false)
public abstract class ChimeraImageDescriptorMixin {
    @org.spongepowered.asm.mixin.Shadow
    @org.spongepowered.asm.mixin.Final
    public int imageIdx;

    @Inject(method = "getImage", at = @At("RETURN"), require = 1)
    private void chimera$recordDescriptorImage(
            CallbackInfoReturnable<VulkanImage> callback
    ) {
        ChimeraTextureBindingState.beginDescriptor(this.imageIdx, callback.getReturnValue());
    }

    @Inject(method = "getImageView(Lnet/vulkanmod/vulkan/texture/VulkanImage;)J",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$depthOnlyView(
            VulkanImage image,
            CallbackInfoReturnable<Long> callback
    ) {
        long view = ChimeraDepthViewOverride.viewFor(image);
        if (view != 0L) {
            callback.setReturnValue(view);
        }
    }
}
