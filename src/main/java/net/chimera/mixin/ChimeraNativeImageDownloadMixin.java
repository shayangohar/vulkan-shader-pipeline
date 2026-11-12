package net.chimera.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.chimera.render.TextureLevelReadback;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NativeImage.downloadTexture reads the level asked for, only as much of it as the image holds.
 * Reforged 0.3.0's overwrite (MNativeImage) ignored the level and copied the bound texture's whole
 * first level into the image: F3 + S dumps each texture atlas one mip level at a time
 * (TextureUtil.writeAsPNG), into an image of that level's size, so level 1 wrote four times past its
 * end and the JVM crashed in MemoryUtil.memCopy. Reforged 0.3.5 reads the level asked for, but
 * copies all of it whatever the image holds, and throws for a level the texture lacks. Any level
 * but 0, or an image of another size than the texture, therefore reads here; VulkanMod keeps the
 * download it serves right.
 */
@Mixin(NativeImage.class)
public abstract class ChimeraNativeImageDownloadMixin {
    @Shadow private long pixels;
    @Shadow @Final private NativeImage.Format format;

    @Shadow public abstract int getWidth();

    @Shadow public abstract int getHeight();

    @Shadow public abstract int getPixelRGBA(int x, int y);

    @Shadow public abstract void setPixelRGBA(int x, int y, int color);

    @Shadow protected abstract void checkAllocated();

    @Inject(method = "downloadTexture", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$downloadLevel(int level, boolean opaque, CallbackInfo callback) {
        VulkanImage texture = VTextureSelector.getBoundTexture(0);
        if (texture == null || level == 0 && texture.width == getWidth() && texture.height == getHeight()) return;
        checkAllocated();
        TextureLevelReadback.read(texture, level, pixels, getWidth(), getHeight(), format.components());
        if (opaque && format.hasAlpha()) {
            int alpha = 255 << format.alphaOffset();
            for (int y = 0; y < getHeight(); y++) {
                for (int x = 0; x < getWidth(); x++) setPixelRGBA(x, y, getPixelRGBA(x, y) | alpha);
            }
        }
        callback.cancel();
    }
}
