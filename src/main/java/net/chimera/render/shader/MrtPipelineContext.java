package net.chimera.render.shader;

import java.util.Arrays;

/**
 * Render-thread-only context used by the narrow M5.6 GraphicsPipeline mixin.
 * VulkanMod creates a graphics pipeline lazily while a post pass is bound, so
 * the target formats must be available at that exact call site.
 */
public final class MrtPipelineContext {
    private static final ThreadLocal<int[]> COLOR_FORMATS = new ThreadLocal<>();

    private MrtPipelineContext() {}

    public static void begin(int[] formats) {
        if (formats == null || formats.length < 1 || formats.length > 4) {
            throw new IllegalArgumentException("M5.6 requires one to four color formats");
        }
        COLOR_FORMATS.set(formats);
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
