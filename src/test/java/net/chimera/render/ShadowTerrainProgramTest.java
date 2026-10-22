package net.chimera.render;

import net.vulkanmod.render.vertex.TerrainRenderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pack shadow pass draws each terrain layer with the program Iris's SodiumPrograms pick:
 * shadow_water for the translucent layer (SHADOW_TRANS) where the pack has it, shadow otherwise.
 */
final class ShadowTerrainProgramTest {
    @Test
    void translucentTerrainDrawsWithShadowWater() {
        assertEquals("shadow_water", PackShadowPass.shadowTerrainProgram(TerrainRenderType.TRANSLUCENT,
                "shadow", "shadow_water"));
        assertEquals("shadow", PackShadowPass.shadowTerrainProgram(TerrainRenderType.TRANSLUCENT,
                "shadow", null), "without shadow_water the translucent layer draws with shadow");
        for (TerrainRenderType layer : new TerrainRenderType[] {TerrainRenderType.SOLID,
                TerrainRenderType.CUTOUT, TerrainRenderType.CUTOUT_MIPPED, TerrainRenderType.TRIPWIRE}) {
            assertEquals("shadow", PackShadowPass.shadowTerrainProgram(layer, "shadow", "shadow_water"),
                    layer.name());
        }
    }
}
