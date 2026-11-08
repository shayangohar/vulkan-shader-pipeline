package net.chimera.render;

import net.chimera.shaderpack.PackRenderingPhase;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pack shadow pass draws each terrain layer with the program Iris's SodiumPrograms pick:
 * shadow_cutout for the cutout layers (SHADOW_CUTOUT) and shadow_water for the translucent layer
 * (SHADOW_TRANS) where the pack has them, shadow otherwise. Each layer also reads its render stage.
 */
final class ShadowTerrainProgramTest {
    @Test
    void translucentTerrainDrawsWithShadowWater() {
        assertEquals("shadow_water", PackShadowPass.shadowTerrainProgram(TerrainRenderType.TRANSLUCENT,
                "shadow", "shadow_cutout", "shadow_water"));
        assertEquals("shadow", PackShadowPass.shadowTerrainProgram(TerrainRenderType.TRANSLUCENT,
                "shadow", "shadow_cutout", null), "without shadow_water the translucent layer draws with shadow");
    }

    /**
     * Alpha Piscium's shadow_cutout is its shadow with the alpha test; drawn with shadow, leaves,
     * grass and flowers cast their whole quads. VulkanMod's cutout_mipped holds solid blocks too.
     */
    @Test
    void cutoutTerrainDrawsWithShadowCutout() {
        for (TerrainRenderType layer : new TerrainRenderType[] {TerrainRenderType.CUTOUT,
                TerrainRenderType.CUTOUT_MIPPED}) {
            assertEquals("shadow_cutout", PackShadowPass.shadowTerrainProgram(layer, "shadow", "shadow_cutout",
                    "shadow_water"), layer.name());
            assertEquals("shadow", PackShadowPass.shadowTerrainProgram(layer, "shadow", null, "shadow_water"),
                    layer.name() + " without shadow_cutout");
        }
        for (TerrainRenderType layer : new TerrainRenderType[] {TerrainRenderType.SOLID, TerrainRenderType.TRIPWIRE}) {
            assertEquals("shadow", PackShadowPass.shadowTerrainProgram(layer, "shadow", "shadow_cutout",
                    "shadow_water"), layer.name());
        }
    }

    @Test
    void opaqueLayersReadTheSolidStage() {
        // Sodium draws Iris's cutout pass in the solid layer call; VulkanMod merges solid blocks into cutout_mipped.
        for (TerrainRenderType type : List.of(TerrainRenderType.SOLID, TerrainRenderType.CUTOUT_MIPPED,
                TerrainRenderType.CUTOUT)) {
            assertEquals(PackRenderingPhase.TERRAIN_SOLID, TerrainLayers.phase(type), type.name());
        }
        assertEquals(PackRenderingPhase.TERRAIN_TRANSLUCENT, TerrainLayers.phase(TerrainRenderType.TRANSLUCENT));
    }
}
