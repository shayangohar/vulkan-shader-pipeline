package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Device-free checks of a round of setup computes: order, held frames, the bound, and the next round. */
final class SetupRoundTest {
    private static final List<String> NAMES = List.of("setup", "setup1", "setup2");

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
}
