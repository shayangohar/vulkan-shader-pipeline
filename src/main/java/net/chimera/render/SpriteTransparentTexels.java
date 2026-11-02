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
    private static final float[] LINEAR = new float[256];

    static {
        for (int value = 0; value < LINEAR.length; value++) {
            float srgb = value / 255.0F;
            LINEAR[value] = srgb <= 0.04045F ? srgb / 12.92F : (float) Math.pow((srgb + 0.055F) / 1.055F, 2.4);
        }
    }

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
            red += LINEAR[texel & 0xFF] * alpha;
            green += LINEAR[texel >>> 8 & 0xFF] * alpha;
            blue += LINEAR[texel >>> 16 & 0xFF] * alpha;
            weight += alpha;
        }
        if (!transparent || weight == 0) return false;
        int mean = srgb(red / weight) | srgb(green / weight) << 8 | srgb(blue / weight) << 16;
        for (int index = 0; index < abgr.length; index++) {
            if (abgr[index] >>> 24 == 0) abgr[index] = mean;
        }
        return true;
    }

    private static int srgb(double linear) {
        double encoded = linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.min(1, Math.max(0, encoded)) * 255);
    }
}
