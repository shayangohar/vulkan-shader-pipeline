package net.chimera.render;

/**
 * The mip levels Iris setups give atlas sprites. Sodium, which Iris requires, builds each level from
 * the one above it. Every 2x2 footprint blends its two horizontal pairs, then blends the two results;
 * each pair's result is stored as 8-bit sRGB. A pair of equal alpha averages its colour in linear
 * light. A fully transparent texel gives way to the other one, whose alpha is quartered. Otherwise
 * the colours weigh by alpha in linear light and the alphas average. A cutout texel beside
 * transparent ones therefore fades below the 0.1 alpha test with distance. VulkanMod Reforged
 * averages all four in gamma 2.2 with no cutoff, so distant foliage stays opaque and fuller
 * (KNOW-996). Texels are ABGR, as NativeImage.getPixelRGBA returns them.
 */
final class AtlasMipBlend {
    private AtlasMipBlend() {}

    /**
     * The level below {@code src}: each texel blends the 2x2 footprint above it, clamped to the
     * source's edge where an odd size leaves no partner.
     */
    static int[] reduce(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int top = Math.min(2 * y, srcHeight - 1) * srcWidth;
            int bottom = Math.min(2 * y + 1, srcHeight - 1) * srcWidth;
            for (int x = 0; x < dstWidth; x++) {
                int left = Math.min(2 * x, srcWidth - 1);
                int right = Math.min(2 * x + 1, srcWidth - 1);
                dst[y * dstWidth + x] = blend(blend(src[top + left], src[top + right]),
                        blend(src[bottom + left], src[bottom + right]));
            }
        }
        return dst;
    }

    /** One pair of texels blended. */
    static int blend(int one, int two) {
        int alphaOne = one >>> 24;
        int alphaTwo = two >>> 24;
        if (alphaOne == alphaTwo) return mix(one, two, 1, 1) | alphaOne << 24;
        if (alphaOne == 0) return two & 0xFFFFFF | (alphaTwo >> 2) << 24;
        if (alphaTwo == 0) return one & 0xFFFFFF | (alphaOne >> 2) << 24;
        return mix(one, two, alphaOne, alphaTwo) | (alphaOne + alphaTwo) >> 1 << 24;
    }

    /** The two colours weighed in linear light, as 8-bit sRGB in the low 24 bits. */
    private static int mix(int one, int two, int weightOne, int weightTwo) {
        double total = weightOne + weightTwo;
        int rgb = 0;
        for (int shift = 0; shift < 24; shift += 8) {
            double linear = (SrgbChannels.linear(one >>> shift & 0xFF) * weightOne
                    + SrgbChannels.linear(two >>> shift & 0xFF) * weightTwo) / total;
            rgb |= SrgbChannels.encode(linear) << shift;
        }
        return rgb;
    }
}
