package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render-thread-only context used by the narrow Chimera GraphicsPipeline mixin.
 * VulkanMod creates a graphics pipeline lazily while a post pass is bound, so
 * the target formats, any pack blend directives per attachment, and which
 * attachments the program writes must be available at that exact call site.
 */
public final class MrtPipelineContext {
    public static final int LOGICAL_LIMIT = 8;

    /**
     * Attachment formats plus per-attachment pack blend modes, where a null mode keeps the host
     * blend, and the attachments the program writes, where null means all of them.
     */
    private record Contract(int[] formats, PackBlendPlan.Mode[] blends, boolean[] writes) {}

    private static final ThreadLocal<Contract> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Contract> PREVIOUS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PIPELINE_SCOPE = new ThreadLocal<>();
    private static final Map<GraphicsPipeline, Contract> REGISTERED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MrtPipelineContext() {}

    public static void begin(int[] formats) {
        begin(formats, LOGICAL_LIMIT);
    }

    public static void begin(int[] formats, int deviceMaxColorAttachments) {
        begin(formats, deviceMaxColorAttachments, null, null);
    }

    /**
     * Begins an MRT contract whose attachments may carry pack blend directives, and of which the
     * program may write only some.
     */
    public static void begin(int[] formats, int deviceMaxColorAttachments, PackBlendPlan.Mode[] blends,
                             boolean[] writes) {
        int limit = Math.min(LOGICAL_LIMIT, Math.max(0, deviceMaxColorAttachments));
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("post MRT exceeds the safe attachment limit " + limit);
        }
        CURRENT.set(contract(formats, blends, writes));
    }

    /** Enables the two-output geometry contract: pack color plus coverage depth. */
    public static void beginGeometry(int targetFormat, int coverageFormat, int deviceMaxColorAttachments) {
        begin(new int[] {targetFormat, coverageFormat}, deviceMaxColorAttachments);
    }

    public static void end() {
        CURRENT.remove();
        PREVIOUS.remove();
        PIPELINE_SCOPE.remove();
    }

    /**
     * Retains a dynamic-rendering format contract for a pipeline whose Vulkan
     * handle will be created lazily by VulkanMod.
     */
    public static void register(GraphicsPipeline pipeline, int[] formats) {
        register(pipeline, formats, null, null);
    }

    /** Retains formats, the pack's per-attachment blend modes and the written attachments for lazy variants. */
    public static void register(GraphicsPipeline pipeline, int[] formats, PackBlendPlan.Mode[] blends,
                                boolean[] writes) {
        if (pipeline == null) throw new IllegalArgumentException("pipeline is required");
        validate(formats, LOGICAL_LIMIT);
        REGISTERED.put(pipeline, contract(formats, blends, writes));
    }

    public static void unregister(GraphicsPipeline pipeline) {
        if (pipeline != null) REGISTERED.remove(pipeline);
    }

    /** Activates a registered contract at GraphicsPipeline.createGraphicsPipeline. */
    public static void beginPipeline(GraphicsPipeline pipeline) {
        Contract contract = REGISTERED.get(pipeline);
        if (contract == null) {
            PIPELINE_SCOPE.set(Boolean.FALSE);
            return;
        }
        PREVIOUS.set(CURRENT.get());
        CURRENT.set(contract);
        PIPELINE_SCOPE.set(Boolean.TRUE);
    }

    /** Restores any outer render-pass contract after lazy pipeline creation. */
    public static void endPipeline() {
        if (!Boolean.TRUE.equals(PIPELINE_SCOPE.get())) {
            PIPELINE_SCOPE.remove();
            return;
        }
        Contract previous = PREVIOUS.get();
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
        PREVIOUS.remove();
        PIPELINE_SCOPE.remove();
    }

    public static int[] colorFormats() {
        Contract contract = CURRENT.get();
        return contract == null ? null : contract.formats().clone();
    }

    /** Per-attachment pack blend modes, or null when every attachment keeps the host blend. */
    public static PackBlendPlan.Mode[] attachmentBlends() {
        Contract contract = CURRENT.get();
        return contract == null || contract.blends() == null ? null : contract.blends().clone();
    }

    /** Which attachments the program writes, or null when it writes every one. */
    public static boolean[] attachmentWrites() {
        Contract contract = CURRENT.get();
        return contract == null || contract.writes() == null ? null : contract.writes().clone();
    }

    public static int attachmentCount(int fallback) {
        Contract contract = CURRENT.get();
        return contract == null ? fallback : contract.formats().length;
    }

    public static String describe() {
        int[] formats = colorFormats();
        return formats == null ? "inactive" : Arrays.toString(formats);
    }

    private static Contract contract(int[] formats, PackBlendPlan.Mode[] blends, boolean[] writes) {
        if (blends != null && blends.length != formats.length) {
            throw new IllegalArgumentException("blend modes do not match the " + formats.length + " attachments");
        }
        if (writes != null && writes.length != formats.length) {
            throw new IllegalArgumentException("write flags do not match the " + formats.length + " attachments");
        }
        return new Contract(formats.clone(), blends == null ? null : blends.clone(),
                writes == null ? null : writes.clone());
    }

    private static void validate(int[] formats, int limit) {
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("MRT exceeds the safe attachment limit " + limit);
        }
    }
}
