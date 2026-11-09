package net.chimera.render.shader;

import net.chimera.shaderpack.ParticleDirectives;
import net.minecraft.client.particle.ParticleRenderType;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's NeoForge MixinParticleEngine sets one particle shader per ParticleEngine.render call: the
 * translucent program only when the call leaves the opaque sheet out. Chimera picked it per sheet,
 * and NeoForge makes the terrain and custom sheets translucent too, so under BEFORE and AFTER
 * Spring, Sildur's Nether and End and Bloop drew those with gbuffers_particles_translucent.
 */
final class ParticleProgramTest {
    @Test
    void neoForgesOwnCallsUseTheTranslucentProgramOnlyInTheLateOne() {
        assertTrue(ParticleRenderType.TERRAIN_SHEET.isTranslucent(), "NeoForge's terrain sheet reads as translucent");
        assertFalse(ChimeraEntityBridge.translucentParticleCall(type -> true), "the Fabulous call");
        assertFalse(ChimeraEntityBridge.translucentParticleCall(type -> !type.isTranslucent()), "the early call");
        assertTrue(ChimeraEntityBridge.translucentParticleCall(ParticleRenderType::isTranslucent), "the late call");
    }

    /** The calls a pack's ordering moves sheets into (ChimeraLevelRendererMixin), each with Iris's program. */
    @Test
    void onlyMixedsLateCallUsesTheTranslucentProgram() {
        int calls = 0;
        for (ParticleDirectives.Ordering ordering : ParticleDirectives.Ordering.values()) {
            for (boolean early : new boolean[] {true, false}) {
                if (!ordering.drawsIn(early)) continue;
                calls++;
                Predicate<ParticleRenderType> sheets = type -> ordering.drawsSheet(early, type.isTranslucent());
                boolean mixed = ordering == ParticleDirectives.Ordering.MIXED;
                assertEquals(mixed && !early, ChimeraEntityBridge.translucentParticleCall(sheets),
                        ordering + (early ? " early" : " late"));
                assertTrue(mixed || sheets.test(ParticleRenderType.TERRAIN_SHEET),
                        ordering + " draws the terrain sheet in its one call");
            }
        }
        assertEquals(4, calls, "BEFORE and AFTER draw in one call each, MIXED in both");
    }
}
