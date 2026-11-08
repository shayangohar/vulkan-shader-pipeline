package net.chimera.render;

/**
 * 8-bit sRGB channels to linear light and back, as Sodium blends atlas texels under Iris. Decoding
 * applies the sRGB transfer curve through a float table. Encoding rounds the curve to the nearest
 * 8-bit value. It finds the result among the linear values where that rounding steps up, which
 * gives the same answer without a power per channel: an atlas rewrite encodes millions of them.
 */
final class SrgbChannels {
    private static final float[] LINEAR = new float[256];
    /** STEP[k]: the least linear value that encodes above k. */
    private static final double[] STEP = new double[255];

    static {
        for (int value = 0; value < LINEAR.length; value++) {
            float srgb = value / 255.0F;
            LINEAR[value] = srgb <= 0.04045F ? srgb / 12.92F : (float) Math.pow((srgb + 0.055F) / 1.055F, 2.4);
        }
        for (int value = 0; value < STEP.length; value++) {
            STEP[value] = decode((value + 0.5) / 255.0);
        }
    }

    private SrgbChannels() {}

    /** The linear light of an 8-bit sRGB channel value. */
    static float linear(int value) {
        return LINEAR[value];
    }

    /** The 8-bit sRGB value nearest {@code linear}, clamped to 0..255. */
    static int encode(double linear) {
        int low = 0;
        int high = STEP.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (STEP[middle] <= linear) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static double decode(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
}
