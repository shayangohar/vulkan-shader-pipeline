package net.chimera.render;

import net.chimera.shaderpack.PackRenderingPhase;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TerrainLayersTest {
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
