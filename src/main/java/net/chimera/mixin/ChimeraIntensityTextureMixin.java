package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.chimera.render.IntensityTextureViews;
import org.spongepowered.asm.mixin.Mixin;

/** Opens IntensityTextureViews while a RED texture is allocated (VulkanMod overwrites prepareImage). */
@Mixin(TextureUtil.class)
public abstract class ChimeraIntensityTextureMixin {
    @WrapMethod(method = "prepareImage(Lcom/mojang/blaze3d/platform/NativeImage$InternalGlFormat;IIII)V")
    private static void chimera$intensityViews(NativeImage.InternalGlFormat format, int id, int mipLevels, int width,
                                               int height, Operation<Void> original) {
        if (format != NativeImage.InternalGlFormat.RED) {
            original.call(format, id, mipLevels, width, height);
            return;
        }
        IntensityTextureViews.setPreparing(true);
        try {
            original.call(format, id, mipLevels, width, height);
        } finally {
            IntensityTextureViews.setPreparing(false);
        }
    }
}
