package net.chimera.render.shader;

import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render-thread-only context used by the narrow Chimera GraphicsPipeline mixin.
 * VulkanMod creates a graphics pipeline lazily while a post pass is bound, so
 * the target formats must be available at that exact call site.
 */
public final class MrtPipelineContext {
    public static final int LOGICAL_LIMIT = 8;
    private static final ThreadLocal<int[]> COLOR_FORMATS = new ThreadLocal<>();
    private static final ThreadLocal<int[]> PREVIOUS_FORMATS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PIPELINE_SCOPE = new ThreadLocal<>();
    private static final Map<GraphicsPipeline, int[]> REGISTERED_FORMATS =
            Collections.synchronizedMap(new WeakHashMap<>());

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

    /** Enables the two-output geometry contract: pack color plus coverage depth. */
    public static void beginGeometry(int targetFormat, int coverageFormat, int deviceMaxColorAttachments) {
        begin(new int[] {targetFormat, coverageFormat}, deviceMaxColorAttachments);
    }

    public static void end() {
        COLOR_FORMATS.remove();
        PREVIOUS_FORMATS.remove();
        PIPELINE_SCOPE.remove();
    }

    /**
     * Retains a dynamic-rendering format contract for a pipeline whose Vulkan
     * handle will be created lazily by VulkanMod.
     */
    public static void register(GraphicsPipeline pipeline, int[] formats) {
        if (pipeline == null) throw new IllegalArgumentException("pipeline is required");
        validate(formats, LOGICAL_LIMIT);
        REGISTERED_FORMATS.put(pipeline, formats.clone());
    }

    public static void unregister(GraphicsPipeline pipeline) {
        if (pipeline != null) REGISTERED_FORMATS.remove(pipeline);
    }

    /** Activates a registered contract at GraphicsPipeline.createGraphicsPipeline. */
    public static void beginPipeline(GraphicsPipeline pipeline) {
        int[] formats = REGISTERED_FORMATS.get(pipeline);
        if (formats == null) {
            PIPELINE_SCOPE.set(Boolean.FALSE);
            return;
        }
        PREVIOUS_FORMATS.set(COLOR_FORMATS.get());
        COLOR_FORMATS.set(formats.clone());
        PIPELINE_SCOPE.set(Boolean.TRUE);
    }

    /** Restores any outer render-pass contract after lazy pipeline creation. */
    public static void endPipeline() {
        if (!Boolean.TRUE.equals(PIPELINE_SCOPE.get())) {
            PIPELINE_SCOPE.remove();
            return;
        }
        int[] previous = PREVIOUS_FORMATS.get();
        if (previous == null) COLOR_FORMATS.remove();
        else COLOR_FORMATS.set(previous);
        PREVIOUS_FORMATS.remove();
        PIPELINE_SCOPE.remove();
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

    private static void validate(int[] formats, int limit) {
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("MRT exceeds the safe attachment limit " + limit);
        }
    }
}
