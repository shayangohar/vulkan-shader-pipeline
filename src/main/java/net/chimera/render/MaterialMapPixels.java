package net.chimera.render;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Pure CPU pixel and layout math for resource-pack material companions.
 *
 * <p>Every method here works on plain ABGR {@code int[]} buffers, so the
 * whole policy is unit-testable without Minecraft natives or a GPU. The
 * live owner converts to and from {@code NativeImage} at its boundary.
 * ABGR packing matches {@code NativeImage}: red in bits 0-7, green 8-15,
 * blue 16-23, alpha 24-31.</p>
 */
public final class MaterialMapPixels {
    private MaterialMapPixels() {}

    /** labPBR specular channel classes: green below 230 is smoothness-like, at or above is metal-like. */
    public static final int LAB_GREEN_SPLIT = 230;
    /** labPBR specular channel classes: blue below 65 is one class, at or above is another. */
    public static final int LAB_BLUE_SPLIT = 65;
    /** labPBR specular alpha classes: anything below 255 is translucent, 255 is opaque. */
    public static final int LAB_ALPHA_OPAQUE = 255;
    /** Largest image the companion builder accepts: 16M texels (64MB RGBA). */
    public static final int MAX_IMAGE_PIXELS = 1 << 24;

    public static int red(int abgr) { return abgr & 0xFF; }
    public static int green(int abgr) { return (abgr >> 8) & 0xFF; }
    public static int blue(int abgr) { return (abgr >> 16) & 0xFF; }
    public static int alpha(int abgr) { return (abgr >>> 24) & 0xFF; }

    public static int pack(int alpha, int blue, int green, int red) {
        return (alpha << 24) | (blue << 16) | (green << 8) | red;
    }

    /** Rejects non-positive or absurd dimensions before any allocation. */
    public static void checkSize(int width, int height, String what) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("MATERIAL_MAP_SIZE:" + what + "=" + width + "x" + height);
        }
        if ((long) width * (long) height > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("MATERIAL_MAP_TOO_LARGE:" + what + "=" + width + "x" + height);
        }
    }

    /**
     * Scales a decoded material frame to its atlas slot size. Identical
     * sizes copy; whole-number enlargement point-samples; every other
     * ratio, including all reductions, takes a channel-wise box average.
     * Reductions never point-sample: selecting one source texel per output
     * texel would keep only the top-left of each footprint.
     */
    public static int[] scale(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "scale-src");
        checkSize(dstWidth, dstHeight, "scale-dst");
        if (srcWidth == dstWidth && srcHeight == dstHeight) {
            return src.clone();
        }
        if (dstWidth % srcWidth == 0 && dstHeight % srcHeight == 0) {
            return scalePoint(src, srcWidth, srcHeight, dstWidth, dstHeight);
        }
        return boxAverage(src, srcWidth, srcHeight, dstWidth, dstHeight);
    }

    private static int[] scalePoint(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        int xStep = dstWidth / srcWidth;
        int yStep = dstHeight / srcHeight;
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int srcRow = (y / yStep) * srcWidth;
            int dstRow = y * dstWidth;
            for (int x = 0; x < dstWidth; x++) {
                dst[dstRow + x] = src[srcRow + x / xStep];
            }
        }
        return dst;
    }

    /** Plain arithmetic mean per channel: normal maps and non-labPBR specular maps. */
    public static int[] reduceLinear(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "reduce-src");
        checkSize(dstWidth, dstHeight, "reduce-dst");
        return boxAverage(src, srcWidth, srcHeight, dstWidth, dstHeight);
    }

    /**
     * labPBR class-aware reduction. Red stays an arithmetic mean. Green
     * separates dielectric texels (below 230, averaged together) from metal
     * texels, where each distinct encoded metal index is its own discrete
     * class: merging two metal IDs would invent a third material. Blue and
     * alpha keep their threshold class policies. Each channel picks the
     * majority class in the footprint and averages only texels in that
     * class; a tied footprint keeps the class of its earliest texel in
     * row-major order.
     */
    public static int[] reduceLabPbr(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        checkSize(srcWidth, srcHeight, "labpbr-src");
        checkSize(dstWidth, dstHeight, "labpbr-dst");
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int y0 = y * srcHeight / dstHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * srcHeight / dstHeight);
            for (int x = 0; x < dstWidth; x++) {
                int x0 = x * srcWidth / dstWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * srcWidth / dstWidth);
                dst[y * dstWidth + x] = reduceLabFootprint(src, srcWidth, x0, y0, x1, y1);
            }
        }
        return dst;
    }

    private static int reduceLabFootprint(int[] src, int srcWidth, int x0, int y0, int x1, int y1) {
        int redSum = 0;
        int count = 0;
        java.util.LinkedHashMap<Integer, int[]> greenClasses = new java.util.LinkedHashMap<>();
        int blueLow = 0;
        int alphaLow = 0;
        int first = src[y0 * srcWidth + x0];
        for (int y = y0; y < y1; y++) {
            int row = y * srcWidth;
            for (int x = x0; x < x1; x++) {
                int texel = src[row + x];
                redSum += red(texel);
                count++;
                int greenClass = green(texel) < LAB_GREEN_SPLIT ? -1 : green(texel);
                int[] tally = greenClasses.get(greenClass);
                if (tally == null) {
                    greenClasses.put(greenClass, new int[]{1, green(texel)});
                } else {
                    tally[0]++;
                    tally[1] += green(texel);
                }
                if (blue(texel) < LAB_BLUE_SPLIT) blueLow++;
                if (alpha(texel) < LAB_ALPHA_OPAQUE) alphaLow++;
            }
        }
        int chosenGreen = -1;
        int chosenCount = -1;
        for (var entry : greenClasses.entrySet()) {
            if (entry.getValue()[0] > chosenCount) {
                chosenCount = entry.getValue()[0];
                chosenGreen = entry.getKey();
            }
        }
        int greenSum = greenClasses.get(chosenGreen)[1];
        int greenCount = chosenCount;
        boolean blueIsLow = blueLow * 2 > count
                || (blueLow * 2 == count && blue(first) < LAB_BLUE_SPLIT);
        boolean alphaIsLow = alphaLow * 2 > count
                || (alphaLow * 2 == count && alpha(first) < LAB_ALPHA_OPAQUE);
        int blueSum = 0;
        int blueCount = 0;
        int alphaSum = 0;
        int alphaCount = 0;
        for (int y = y0; y < y1; y++) {
            int row = y * srcWidth;
            for (int x = x0; x < x1; x++) {
                int texel = src[row + x];
                if ((blue(texel) < LAB_BLUE_SPLIT) == blueIsLow) {
                    blueSum += blue(texel);
                    blueCount++;
                }
                if ((alpha(texel) < LAB_ALPHA_OPAQUE) == alphaIsLow) {
                    alphaSum += alpha(texel);
                    alphaCount++;
                }
            }
        }
        return pack(alphaSum / alphaCount, blueSum / blueCount, greenSum / greenCount, redSum / count);
    }

    private static int[] boxAverage(int[] src, int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        int[] dst = new int[dstWidth * dstHeight];
        for (int y = 0; y < dstHeight; y++) {
            int y0 = y * srcHeight / dstHeight;
            int y1 = Math.max(y0 + 1, (y + 1) * srcHeight / dstHeight);
            for (int x = 0; x < dstWidth; x++) {
                int x0 = x * srcWidth / dstWidth;
                int x1 = Math.max(x0 + 1, (x + 1) * srcWidth / dstWidth);
                long a = 0;
                long b = 0;
                long g = 0;
                long r = 0;
                int count = 0;
                for (int sy = y0; sy < y1; sy++) {
                    int row = sy * srcWidth;
                    for (int sx = x0; sx < x1; sx++) {
                        int texel = src[row + sx];
                        a += alpha(texel);
                        b += blue(texel);
                        g += green(texel);
                        r += red(texel);
                        count++;
                    }
                }
                dst[y * dstWidth + x] = pack((int) (a / count), (int) (b / count),
                        (int) (g / count), (int) (r / count));
            }
        }
        return dst;
    }

    /** Copies a sprite rectangle into a level canvas, clipping defensively. */
    public static void placeRect(int[] canvas, int canvasWidth, int canvasHeight,
            int x, int y, int[] rect, int rectWidth, int rectHeight) {
        for (int ry = 0; ry < rectHeight; ry++) {
            int cy = y + ry;
            if (cy < 0 || cy >= canvasHeight) continue;
            int canvasRow = cy * canvasWidth;
            int rectRow = ry * rectWidth;
            for (int rx = 0; rx < rectWidth; rx++) {
                int cx = x + rx;
                if (cx < 0 || cx >= canvasWidth) continue;
                canvas[canvasRow + cx] = rect[rectRow + rx];
            }
        }
    }

    /**
     * Pads one logical mip with its own replicated edge on all four sides.
     * Returns a {@code (width + 2*pad) x (height + 2*pad)} image whose
     * center is the logical content. A zero pad copies. This mirrors the
     * stitched slot: content at the padded origin, never a ring negotiated
     * against neighboring sprites.
     */
    public static int[] padReplicate(int[] logical, int width, int height, int pad) {
        checkSize(width, height, "pad-src");
        if (pad < 0) {
            throw new IllegalArgumentException("MATERIAL_MAP_PAD:" + pad);
        }
        if (pad == 0) {
            return logical.clone();
        }
        int paddedWidth = width + pad * 2;
        int[] padded = new int[paddedWidth * (height + pad * 2)];
        for (int y = 0; y < height + pad * 2; y++) {
            int srcY = Math.max(0, Math.min(height - 1, y - pad));
            int paddedRow = y * paddedWidth;
            int srcRow = srcY * width;
            for (int x = 0; x < paddedWidth; x++) {
                int srcX = Math.max(0, Math.min(width - 1, x - pad));
                padded[paddedRow + x] = logical[srcRow + srcX];
            }
        }
        return padded;
    }

    /**
     * Recovers one axis of stitched padding from observable sprite state:
     * the UV edge scaled to atlas texels minus the placed origin. Callers
     * require X and Y to agree and isolate sprites that do not.
     */
    public static int recoverPad(float uvEdge, int atlasDim, int origin) {
        return Math.max(0, Math.round(uvEdge * atlasDim) - origin);
    }

    /** Atlas level dimensions: halve per level, never below one texel. */
    public static int levelSize(int base, int level) {
        return Math.max(1, base >> level);
    }

    /**
     * Sibling material resource for a sprite identifier. Ordinary sprites
     * resolve under {@code textures/}; paths already under
     * {@code optifine/cit/} keep their directory (the OptiFine CIT
     * exception). Returns the {@code namespace:path} string; the caller
     * converts it to an {@code Identifier}.
     */
    public static String siblingResource(String namespace, String spritePath, String suffix) {
        String base = spritePath.startsWith("optifine/cit/") ? spritePath : "textures/" + spritePath;
        return namespace + ":" + base + suffix + ".png";
    }

    /**
     * Reads the labPBR opt-out/opt-in flag from an
     * {@code optifine/texture.properties} stream. Accepts
     * {@code format=lab-pbr} with an optional {@code /version} suffix.
     */
    public static boolean detectLabPbr(InputStream properties) throws IOException {
        Properties parsed = new Properties();
        parsed.load(properties);
        String format = parsed.getProperty("format", "").trim();
        return format.equals("lab-pbr") || format.startsWith("lab-pbr/");
    }
}
