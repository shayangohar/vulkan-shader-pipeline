package net.chimera.render;

import net.chimera.shaderpack.PassComputePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Device-free checks of when a pass compute skips a frame rather than dispatching. */
final class PackPassComputesTest {
    private static final List<PassComputePlan.Binding> BINDINGS = List.of(
            new PassComputePlan.Binding("colorimg3", 0, PassComputePlan.Kind.TARGET_IMAGE, 3, "rgba16f", null),
            new PassComputePlan.Binding("counts", 1, PassComputePlan.Kind.STORAGE_BUFFER, 2, null, null));

    /** An image missing this frame skips the frame; a storage buffer has no image to miss. */
    @Test
    void aMissingImageSkipsTheFrame() {
        PassComputePlan.PassCompute compute = compute(null);
        assertEquals("colorimg3 has no image this frame",
                PackPassComputes.frameGap(compute, binding -> binding != 0, 0L));
        assertNull(PackPassComputes.frameGap(compute, binding -> binding == 0, 0L));
    }

    /**
     * Counts past the buffer's end skip the frame, as glDispatchComputeIndirect dispatches nothing
     * then; a relative buffer can be that small at one screen size and not another.
     */
    @Test
    void indirectCountsPastTheBufferSkipTheFrame() {
        PassComputePlan.PassCompute compute = compute(new PassComputePlan.Indirect(2, 20L));
        assertEquals("indirect counts at 20 lie past bufferObject.2",
                PackPassComputes.frameGap(compute, binding -> true, 31L));
        assertNull(PackPassComputes.frameGap(compute, binding -> true, 20L + PackPassComputes.INDIRECT_COUNTS_BYTES));
    }

    private static PassComputePlan.PassCompute compute(PassComputePlan.Indirect indirect) {
        return new PassComputePlan.PassCompute("composite_a", "composite", "void main() {}", new int[] {8, 8, 1},
                null, null, indirect, BINDINGS, List.of(), List.of(), List.of());
    }
}
