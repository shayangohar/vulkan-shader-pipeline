package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Render-thread-only context used by the narrow Chimera GraphicsPipeline mixin.
 * VulkanMod creates a graphics pipeline lazily while a post pass is bound, so
 * the target formats, any pack blend directives per attachment, and the output
 * locations the pipeline's fragment stage writes must be available at that
 * exact call site.
 */
public final class MrtPipelineContext {
    public static final int LOGICAL_LIMIT = 8;

    /** Attachment formats plus per-attachment pack blend modes; a null mode keeps the host blend. */
    private record Contract(int[] formats, PackBlendPlan.Mode[] blends) {}

    private static final ThreadLocal<Contract> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Contract> PREVIOUS = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PIPELINE_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_WRITES = new ThreadLocal<>();
    private static final Map<GraphicsPipeline, Contract> REGISTERED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<GraphicsPipeline, Integer> WRITTEN_OUTPUTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MrtPipelineContext() {}

    public static void begin(int[] formats) {
        begin(formats, LOGICAL_LIMIT);
    }

    public static void begin(int[] formats, int deviceMaxColorAttachments) {
        begin(formats, deviceMaxColorAttachments, null);
    }

    /** Begins an MRT contract whose attachments may carry pack blend directives. */
    public static void begin(int[] formats, int deviceMaxColorAttachments, PackBlendPlan.Mode[] blends) {
        int limit = Math.min(LOGICAL_LIMIT, Math.max(0, deviceMaxColorAttachments));
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("post MRT exceeds the safe attachment limit " + limit);
        }
        CURRENT.set(contract(formats, blends));
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
        register(pipeline, formats, null);
    }

    /** Retains formats plus the pack's per-attachment blend modes for lazy variants. */
    public static void register(GraphicsPipeline pipeline, int[] formats, PackBlendPlan.Mode[] blends) {
        if (pipeline == null) throw new IllegalArgumentException("pipeline is required");
        validate(formats, LOGICAL_LIMIT);
        REGISTERED.put(pipeline, contract(formats, blends));
    }

    /** Records the output locations {@code pipeline}'s fragment stage writes (SpirvOutputWrites). */
    public static void recordWrittenOutputs(GraphicsPipeline pipeline, int locations) {
        if (pipeline != null) WRITTEN_OUTPUTS.put(pipeline, locations);
    }

    public static void unregister(GraphicsPipeline pipeline) {
        if (pipeline == null) return;
        REGISTERED.remove(pipeline);
        WRITTEN_OUTPUTS.remove(pipeline);
    }

    /** Activates a registered contract at GraphicsPipeline.createGraphicsPipeline. */
    public static void beginPipeline(GraphicsPipeline pipeline) {
        CURRENT_WRITES.set(WRITTEN_OUTPUTS.get(pipeline));
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
        CURRENT_WRITES.remove();
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

    /**
     * A bit per output location the pipeline being created writes, or SpirvOutputWrites.ALL when
     * that is not recorded (Chimera's own pipelines, VulkanMod's).
     */
    public static int writtenOutputs() {
        Integer locations = CURRENT_WRITES.get();
        return locations == null ? SpirvOutputWrites.ALL : locations;
    }

    public static int attachmentCount(int fallback) {
        Contract contract = CURRENT.get();
        return contract == null ? fallback : contract.formats().length;
    }

    public static String describe() {
        int[] formats = colorFormats();
        return formats == null ? "inactive" : Arrays.toString(formats);
    }

    private static Contract contract(int[] formats, PackBlendPlan.Mode[] blends) {
        if (blends != null && blends.length != formats.length) {
            throw new IllegalArgumentException("blend modes do not match the " + formats.length + " attachments");
        }
        return new Contract(formats.clone(), blends == null ? null : blends.clone());
    }

    private static void validate(int[] formats, int limit) {
        if (formats == null || formats.length < 1 || formats.length > limit) {
            throw new IllegalArgumentException("MRT exceeds the safe attachment limit " + limit);
        }
    }
}
