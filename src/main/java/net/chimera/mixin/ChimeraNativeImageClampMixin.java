package net.chimera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.chimera.render.EdgeClampedImage;
import net.vulkanmod.gl.GlTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NativeImage._upload clamps the bound texture to its edge when its caller asks: a texture's
 * "clamp" metadata (misc/shadow.png, the entity ground shadow). VulkanMod Reforged's overwrite
 * (MNativeImage) drops it, so a pack program that samples such a texture with no clamp of its own,
 * as Iris lets it, repeated it: the ground shadow drew a disc on every block under a mob. Vanilla's
 * entity-shadow shader clamps its coordinate, so the fixed pipeline never showed it.
 */
@Mixin(NativeImage.class)
public abstract class ChimeraNativeImageClampMixin {
    @Inject(method = "_upload", at = @At("HEAD"), require = 1)
    private void chimera$clampToEdge(int level, int xOffset, int yOffset, int skipPixels, int skipRows, int width,
                                     int height, boolean blur, boolean clamp, boolean mipmap, boolean autoClose,
                                     CallbackInfo callback) {
        GlTexture bound = GlTexture.getBoundTexture();
        if (clamp && bound != null && bound.getVulkanImage() != null) {
            ((EdgeClampedImage) bound.getVulkanImage()).chimera$clampToEdge();
        }
    }
}
