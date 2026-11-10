package net.chimera.render;

import net.chimera.shaderpack.PassComputePlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device-free checks of pass computes: when one skips a frame rather than dispatching, and a round of
 * setup computes: order, held frames, the bound, and the next round.
 */
final class SetupRoundTest {
    private static final List<String> NAMES = List.of("setup", "setup1", "setup2");
    private static final List<PassComputePlan.Binding> BINDINGS = List.of(
            new PassComputePlan.Binding("colorimg3", 0, PassComputePlan.Kind.TARGET_IMAGE, 3, "rgba16f", null),
            new PassComputePlan.Binding("counts", 1, PassComputePlan.Kind.STORAGE_BUFFER, 2, null, null));

    /**
     * Iris runs setup computes in order, in one batch: one that skips a frame holds the ones after
     * it, so setup2 never runs before setup1.
     */
    @Test
    void aSkippedComputeHoldsTheRestOfTheRound() {
        SetupRound round = new SetupRound();
        round.start(true);
        List<String> ran = new ArrayList<>();
        assertEquals(List.of(), round.advance(NAMES, name -> name.equals("setup") && ran.add(name)));
        assertTrue(round.due());
        assertEquals(List.of(), round.advance(NAMES, name -> ran.add(name)));
        assertEquals(List.of("setup", "setup1", "setup2"), ran);
        assertTrue(!round.due());
    }

    /** After TRIES held frames the round goes on without the compute, and the next round tries it again. */
    @Test
    void aComputeThatKeepsSkippingWaitsForTheNextRound() {
        SetupRound round = new SetupRound();
        round.start(true);
        List<String> abandoned = List.of();
        for (int frame = 0; frame < SetupRound.TRIES && round.due(); frame++) {
            abandoned = round.advance(NAMES, name -> !name.equals("setup1"));
        }
        assertEquals(List.of("setup1"), abandoned);
        assertTrue(!round.due());
        round.start(true);
        List<String> ran = new ArrayList<>();
        assertEquals(List.of(), round.advance(NAMES, name -> ran.add(name)));
        assertEquals(NAMES, ran);
    }

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
        assertNull(PackPassComputes.frameGap(compute, binding -> true, 20L + PassComputePlan.INDIRECT_COUNTS_BYTES));
    }

    /**
     * Audit FD6: work groups GL refuses (INVALID_VALUE) dispatch nothing, and the compute counts as
     * run. A negative workGroupsRender share gives a negative count, which Vulkan would read as about
     * four billion.
     */
    @Test
    void workGroupsGlRefusesDispatchNothing() {
        int[] limits = {65535, 65535, 65535};
        int[] size = {8, 8, 1};
        assertEquals("work groups -120x135x1 lie outside the device's maxComputeWorkGroupCount 65535x65535x65535",
                PackPassComputes.dispatchGap(compute(null, new float[] {-0.5F, 1.0F}), size, 1920, 1080, limits));
        assertNull(PackPassComputes.dispatchGap(compute(null, new float[] {0.5F, 1.0F}), size, 1920, 1080, limits));
        assertEquals("work groups 65536x1x1 lie outside the device's maxComputeWorkGroupCount 65535x65535x65535",
                PackPassComputes.dispatchGap(compute(new int[] {65536, 1, 1}, null), size, 1920, 1080, limits));
        assertNull(PackPassComputes.dispatchGap(compute(new int[] {65535, 1, 1}, null), size, 1920, 1080, limits));
        // An unsigned limit past Integer.MAX_VALUE holds every non-negative count.
        assertNull(PackPassComputes.dispatchGap(compute(null, null), size, 1920, 1080, new int[] {-1, -1, -1}));
    }

    /**
     * A screen-sized count over a work group size of 0, as a layout sized by a constant expression
     * reads when the module gives none, would divide by zero: Integer.MAX_VALUE groups.
     */
    @Test
    void aScreenSizedCountNeedsAWorkGroupSize() {
        int[] limits = {Integer.MAX_VALUE, 65535, 65535};
        assertEquals("its work group size is unknown",
                PackPassComputes.dispatchGap(compute(null, null), new int[] {0, 8, 1}, 1920, 1080, limits));
        assertNull(PackPassComputes.dispatchGap(compute(new int[] {4, 1, 1}, null), new int[] {0, 8, 1}, 1920, 1080,
                limits));
        assertNull(PackPassComputes.dispatchGap(compute(new PassComputePlan.Indirect(2, 0L)), new int[] {0, 0, 0},
                1920, 1080, limits));
    }

    private static PassComputePlan.PassCompute compute(PassComputePlan.Indirect indirect) {
        return new PassComputePlan.PassCompute("composite_a", "composite", "void main() {}", new int[] {8, 8, 1},
                null, null, indirect, BINDINGS, List.of(), List.of(), List.of());
    }

    private static PassComputePlan.PassCompute compute(int[] workGroups, float[] workGroupsRender) {
        return new PassComputePlan.PassCompute("composite_a", "composite", "void main() {}", new int[] {8, 8, 1},
                workGroups, workGroupsRender, null, BINDINGS, List.of(), List.of(), List.of());
    }
}
