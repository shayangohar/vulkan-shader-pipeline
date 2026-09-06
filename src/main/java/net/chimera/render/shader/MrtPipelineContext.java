package net.chimera.render.shader;

import java.util.Arrays;

/**
 * Render-thread-only context used by the narrow M5.6 GraphicsPipeline mixin.
 * VulkanMod creates a graphics pipeline lazily while a post pass is bound, so
 * the target formats must be available at that exact call site.
 */
public final class MrtPipelineContext {
    public static final int LOGICAL_LIMIT = 8;
    private static final ThreadLocal<int[]> COLOR_FORMATS = new ThreadLocal<>();

    private MrtPipelineContext() {}

    public static void begin(int[] formats) {
        begin(formats, LOGICAL_LIMIT);
    }

    public static void begin(int[] formats, int deviceMaxColorAttachments) {
        int limit = Math.min(LOGICAL_LIMIT, Math.max(0, deviceMaxColorAttachments));
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("M7.4 post MRT exceeds the safe attachment limit " + limit);
        }
        COLOR_FORMATS.set(formats.clone());
    }

    public static void end() {
        COLOR_FORMATS.remove();
    }

    public static int[] colorFormats() {
        return COLOR_FORMATS.get();
    }

    public static int attachmentCount(int fallback) {
        int[] formats = colorFormats();
        return formats == null ? fallback : formats.length;
    }

    public static String describe() {
        int[] formats = colorFormats();
        return formats == null ? "inactive" : Arrays.toString(formats);
    }
}
