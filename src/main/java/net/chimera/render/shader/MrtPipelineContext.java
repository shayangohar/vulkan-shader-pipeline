package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
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
    record Contract(int[] formats, PackBlendPlan.Mode[] blends) {}

    /**
     * This thread's contracts: the pass's ({@link #begin}), and, while VulkanMod creates one of a
     * pipeline's variants ({@link #beginPipeline}), that pipeline's registered contract, the output
     * locations its fragment stage writes and its blends for VulkanMod's own render passes. The
     * pipeline's contract stands in for the pass's during its creation.
     */
    private record Scope(Contract pass, Contract pipeline, Integer writes, PackBlendPlan.Mode[] hostPassBlends) {
        static final Scope EMPTY = new Scope(null, null, null, null);

        Contract contract() {
            return this.pipeline != null ? this.pipeline : this.pass;
        }
    }

    private static final ThreadLocal<Scope> SCOPE = ThreadLocal.withInitial(() -> Scope.EMPTY);
    private static final Map<GraphicsPipeline, Contract> REGISTERED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<GraphicsPipeline, Integer> WRITTEN_OUTPUTS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<GraphicsPipeline, PackBlendPlan.Mode[]> HOST_PASS_BLENDS =
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
        Scope scope = SCOPE.get();
        SCOPE.set(new Scope(contract(formats, blends), scope.pipeline(), scope.writes(), scope.hostPassBlends()));
    }

    /** Enables the two-output geometry contract: pack color plus coverage depth. */
    public static void beginGeometry(int targetFormat, int coverageFormat, int deviceMaxColorAttachments) {
        begin(new int[] {targetFormat, coverageFormat}, deviceMaxColorAttachments);
    }

    /**
     * Ends the contract and any pipeline-creation scope with it, so nothing outlives the pass.
     * GraphicsPipelineMrtMixin closes each creation's scope in a finally, so a creation that throws
     * restores the pass's contract before its next variant.
     */
    public static void end() {
        SCOPE.remove();
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

    /**
     * Retains the pack blend modes for {@code pipeline}'s variants in VulkanMod's own render passes,
     * one per attachment there; a null mode keeps the host blend, and all-null modes register nothing.
     */
    public static void registerHostPassBlends(GraphicsPipeline pipeline, PackBlendPlan.Mode[] blends) {
        if (pipeline == null || blends == null || Arrays.stream(blends).allMatch(Objects::isNull)) return;
        HOST_PASS_BLENDS.put(pipeline, blends.clone());
    }

    public static void unregister(GraphicsPipeline pipeline) {
        if (pipeline == null) return;
        REGISTERED.remove(pipeline);
        WRITTEN_OUTPUTS.remove(pipeline);
        HOST_PASS_BLENDS.remove(pipeline);
    }

    /**
     * Opens the creation scope of one of {@code pipeline}'s variants at
     * GraphicsPipeline.createGraphicsPipeline. The caller ends it with {@link #endPipeline} however
     * the creation ends.
     */
    public static void beginPipeline(GraphicsPipeline pipeline) {
        beginPipeline(REGISTERED.get(pipeline), WRITTEN_OUTPUTS.get(pipeline), HOST_PASS_BLENDS.get(pipeline));
    }

    /** Opens a creation scope with the creating pipeline's registered state; null for none. */
    static void beginPipeline(Contract contract, Integer writes, PackBlendPlan.Mode[] hostPassBlends) {
        SCOPE.set(new Scope(SCOPE.get().pass(), contract, writes, hostPassBlends));
    }

    /** Closes the creation scope: the pass's contract applies again. */
    public static void endPipeline() {
        Contract pass = SCOPE.get().pass();
        if (pass == null) SCOPE.remove();
        else SCOPE.set(new Scope(pass, null, null, null));
    }

    public static int[] colorFormats() {
        Contract contract = SCOPE.get().contract();
        return contract == null ? null : contract.formats().clone();
    }

    /** Per-attachment pack blend modes, or null when every attachment keeps the host blend. */
    public static PackBlendPlan.Mode[] attachmentBlends() {
        Contract contract = SCOPE.get().contract();
        return contract == null || contract.blends() == null ? null : contract.blends().clone();
    }

    /** The pack blend modes for the pipeline being created in a VulkanMod render pass, or null. */
    public static PackBlendPlan.Mode[] hostPassBlends() {
        PackBlendPlan.Mode[] blends = SCOPE.get().hostPassBlends();
        return blends == null ? null : blends.clone();
    }

    /**
     * A bit per output location the pipeline being created writes, or SpirvOutputWrites.ALL when
     * that is not recorded (Chimera's own pipelines, VulkanMod's).
     */
    public static int writtenOutputs() {
        Integer locations = SCOPE.get().writes();
        return locations == null ? SpirvOutputWrites.ALL : locations;
    }

    static Contract contract(int[] formats, PackBlendPlan.Mode[] blends) {
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
