package net.chimera.render;

/**
 * Pure eligibility checks for the guarded family host-transform bridge.
 *
 * <p>A guarded family batch uploads an append-only vertex format, so it can
 * never fall back to the host pipeline once its mesh exists. These checks
 * therefore decide the route before any vertex is written (admission) and, at
 * the draw, whether the pack pipeline can serve the host's per-draw transform
 * slices. An absent slice is not a failure: VulkanMod's own encoder keeps the
 * global buffer when a render pass carries no slice, so the pack draw stays
 * host-equivalent. Only a slice that is present but unusable abandons the
 * draw.</p>
 */
public final class EntityTransformBinding {
    private EntityTransformBinding() {}

    /** How one host transform slice reaches the pack pipeline at this draw. */
    public enum HostTransformRoute {
        /** Bind the host render-pass slice straight onto the pack block. */
        BIND_SLICE,
        /** No slice for this draw: keep VulkanMod's global buffer, as the host pipeline does. */
        USE_GLOBAL,
        /** A slice exists but cannot be bound; the draw must not fall back to the host pipeline. */
        UNAVAILABLE
    }

    /**
     * Route for one host transform source at the actual draw.
     *
     * <p>A guarded family batch widened its vertex format and must run the pack
     * pipeline, so an absent or unusable source abandons the draw: VulkanMod's
     * global buffer is filled from this shader's own uniform suppliers, and
     * substituting those frame-global matrices would move the object. A lane
     * that keeps the host format behaves like the host pipeline: bind the slice
     * when it exists and can serve the block, otherwise leave the global buffer
     * bound.</p>
     */
    public static HostTransformRoute classifyHostTransform(boolean perDrawTransform, boolean present,
                                                           boolean usable) {
        if (present && usable) {
            return HostTransformRoute.BIND_SLICE;
        }
        return perDrawTransform ? HostTransformRoute.UNAVAILABLE : HostTransformRoute.USE_GLOBAL;
    }

    /**
     * Admission gate for a batch whose upload widens the host vertex format.
     * The pack pipeline must declare both host transform blocks, or the batch
     * must keep the host format and the host pipeline from the outset.
     */
    public static boolean canServeHostTransforms(boolean widensVertexFormat,
                                                 boolean dynamicTransformsDeclared,
                                                 boolean projectionDeclared) {
        return !widensVertexFormat || (dynamicTransformsDeclared && projectionDeclared);
    }

    public static boolean validUniformRange(long offset, long sliceLength, long bufferCapacity, int requiredSize) {
        return requiredSize > 0 && offset >= 0 && sliceLength >= requiredSize
                && bufferCapacity >= requiredSize && offset <= bufferCapacity
                && sliceLength <= bufferCapacity - offset;
    }
}
