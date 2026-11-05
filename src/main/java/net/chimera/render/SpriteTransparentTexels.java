package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * The colour Iris setups load an atlas sprite's fully transparent texels with. Sodium, which Iris
 * requires, recolours each to the sprite's mean colour as the sprite loads: the alpha-weighted mean
 * of its texels decoded from sRGB, encoded back. Vanilla and VulkanMod keep the image's own colour.
 * No draw shows it without a pack, since those texels are discarded or blend to nothing, and on a
 * mipmapped atlas VulkanMod's mip generator recolours them again. A pack can read it: Pegasus
 * colours particle light (PARTICLES_LPV) from the particle atlas's first texel, a transparent one,
 * and its particles lit nothing in black.
 */
public final class SpriteTransparentTexels {
    private SpriteTransparentTexels() {}

    /** Recolours {@code image}'s alpha-0 texels in place, as {@link #fill(int[])} does. */
    public static void fill(NativeImage image) {
        if (image.format() != NativeImage.Format.RGBA) return;
        int width = image.getWidth();
        int[] abgr = new int[width * image.getHeight()];
        for (int index = 0; index < abgr.length; index++) {
            abgr[index] = image.getPixelRGBA(index % width, index / width);
        }
        if (!fill(abgr)) return;
        for (int index = 0; index < abgr.length; index++) {
            if (abgr[index] >>> 24 == 0) image.setPixelRGBA(index % width, index / width, abgr[index]);
        }
    }

    /**
     * Sets each alpha-0 texel of {@code abgr} to the mean colour, alpha kept 0. A sprite with no
     * such texel, or with nothing visible to average, keeps its own; returns whether it changed.
     */
    static boolean fill(int[] abgr) {
        double red = 0, green = 0, blue = 0, weight = 0;
        boolean transparent = false;
        for (int texel : abgr) {
            int alpha = texel >>> 24;
            if (alpha == 0) {
                transparent = true;
                continue;
            }
            red += SrgbChannels.linear(texel & 0xFF) * alpha;
            green += SrgbChannels.linear(texel >>> 8 & 0xFF) * alpha;
            blue += SrgbChannels.linear(texel >>> 16 & 0xFF) * alpha;
            weight += alpha;
        }
        if (!transparent || weight == 0) return false;
        int mean = SrgbChannels.encode(red / weight) | SrgbChannels.encode(green / weight) << 8
                | SrgbChannels.encode(blue / weight) << 16;
        for (int index = 0; index < abgr.length; index++) {
            if (abgr[index] >>> 24 == 0) abgr[index] = mean;
        }
        return true;
    }
}
