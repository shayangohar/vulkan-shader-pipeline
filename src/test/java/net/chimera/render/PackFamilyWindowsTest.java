package net.chimera.render;

import net.chimera.shaderpack.GeometryOutputPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PackFamilyWindowsTest {
    /**
     * A run holds the targets its programs store to as colorimgN in the general layout until it
     * ends, so a batch that samples one as colortexN must not share it: batches split on their
     * stored targets as well as on their outputs. Equal outputs alone kept a storing hand batch and
     * a sampling one in one run, where binding the held target throws "Unexpected value:1".
     */
    @Test
    void aStoringBatchNeverSharesARunWithOneThatDoesNot() {
        GeometryOutputPlan hand = GeometryOutputPlan.parse("gbuffers_hand", "/* DRAWBUFFERS:04 */\n", Map.of());
        List<PackFamilyWindows.RunKey> batches = List.of(
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(hand, List.of(7)),
                new PackFamilyWindows.RunKey(hand, List.of(7)),
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(null, List.of()));
        List<List<PackFamilyWindows.RunKey>> runs = PackFamilyWindows.runs(batches, batch -> batch);
        assertEquals(List.of(2, 2, 1, 1), runs.stream().map(List::size).toList());
        assertEquals(List.of(7), runs.get(1).get(0).stored(), "the storing batches run together");
        assertEquals(List.of(), PackFamilyWindows.runs(List.<String>of(), value -> value), "no batches, no runs");
    }
}
