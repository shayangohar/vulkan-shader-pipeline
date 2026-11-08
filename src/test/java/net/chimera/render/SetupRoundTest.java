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

    private static PassComputePlan.PassCompute compute(PassComputePlan.Indirect indirect) {
        return new PassComputePlan.PassCompute("composite_a", "composite", "void main() {}", new int[] {8, 8, 1},
                null, null, indirect, BINDINGS, List.of(), List.of(), List.of());
    }
}
