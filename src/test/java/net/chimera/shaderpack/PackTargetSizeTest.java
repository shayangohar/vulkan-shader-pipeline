package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** size.buffer target sizes, as Iris's TextureScaleOverride makes them. */
final class PackTargetSizeTest {
    /**
     * Iris truncates window * factor in float. Bloop's colortex6 and Mellow's gaux2 are 0.25 of
     * the window: 213 wide at 854, where rounding made 214.
     */
    @Test
    void aRelativeSizeTruncatesAsIrisDoes() {
        assertEquals(213, PackTargetGraphPlan.parseDimension("0.25", 854));
        assertEquals(633, PackTargetGraphPlan.parseDimension("0.33", 1920));
        assertEquals(683, PackTargetGraphPlan.parseDimension("0.5", 1366));
        assertEquals(1, PackTargetGraphPlan.parseDimension("0.1", 1), "never below one texel");
        assertEquals(128, PackTargetGraphPlan.parseDimension("128", 854), "an absolute size stays");
        assertThrows(NumberFormatException.class, () -> PackTargetGraphPlan.parseDimension("0.0", 854));
    }
}
