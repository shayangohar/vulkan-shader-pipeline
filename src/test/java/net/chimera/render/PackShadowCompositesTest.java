package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit RD4: the shadowcomp passes live as long as the game, and their one failure log was never
 * re-armed, so a second pack whose shadowcomp failed skipped its passes with no log line.
 */
final class PackShadowCompositesTest {
    @Test
    void eachPackSessionLogsItsFirstFailedPass() {
        PackShadowComposites composites = new PackShadowComposites(null);
        assertTrue(composites.firstFailure());
        assertFalse(composites.firstFailure(), "a pass failing every frame logs once");
        composites.resetSession();
        assertTrue(composites.firstFailure(), "the next pack's first failure is logged");
    }
}
