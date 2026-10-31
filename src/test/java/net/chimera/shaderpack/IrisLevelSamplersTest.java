package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.HostLayoutLane;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris registers its white pixel under lightmap where a draw's ShaderKey has no light input: a
 * format without UV2, or a FULLBRIGHT key (IrisSamplers.addLevelSamplers, ShaderAttributeInputs).
 * Miniature lights its lightning bolt by lightmap; on the real night lightmap the bolt drew purple.
 */
final class IrisLevelSamplersTest {
    @Test
    void lanesReadTheLightmapExactlyWithLightCoordinates() {
        for (var lane : List.of(HostLayoutLane.LIGHTNING, HostLayoutLane.LINE, HostLayoutLane.GLINT,
                HostLayoutLane.HAND_GLINT, HostLayoutLane.TEXTURED, HostLayoutLane.TEXTURED_COLOR,
                HostLayoutLane.SHADOW_LIGHTNING, HostLayoutLane.SHADOW_BASIC)) {
            assertTrue(lane.whiteLightmap(), lane.name());
        }
        for (var lane : List.of(HostLayoutLane.WEATHER, HostLayoutLane.LEASH, HostLayoutLane.TEXT_BG,
                HostLayoutLane.SHADOW_LEASH, HostLayoutLane.SHADOW_TEXT, HostLayoutLane.SHADOW_TEXT_BG)) {
            assertFalse(lane.whiteLightmap(), lane.name());
        }
    }

    /** The FULLBRIGHT keys Iris 1.21.1 picks: ENTITIES_EYES and _TRANS, BEACON and CRUMBLING. */
    @Test
    void fullbrightFamiliesReadWhite() {
        for (Family family : List.of(Family.SPIDER_EYES, Family.BEACON_BEAM, Family.DAMAGED_BLOCK, Family.LIGHTNING)) {
            assertTrue(ChimeraEntityBridge.whiteLightmap(family), family.name());
        }
        for (Family family : List.of(Family.ENTITY, Family.BLOCK, Family.HAND, Family.PARTICLE,
                Family.SHADOW_ENTITIES)) {
            assertFalse(ChimeraEntityBridge.whiteLightmap(family), family.name());
        }
    }

    @Test
    void skyAndCloudKeysHaveNoLight() {
        for (VertexFormat format : List.of(DefaultVertexFormat.POSITION, DefaultVertexFormat.POSITION_COLOR,
                DefaultVertexFormat.POSITION_TEX, DefaultVertexFormat.POSITION_TEX_COLOR,
                DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL)) {
            assertTrue(ChimeraSkyBridge.whiteLightmap(format), format.toString());
        }
        assertTrue(ChimeraSkyBridge.whiteAlbedo(DefaultVertexFormat.POSITION)
                && ChimeraSkyBridge.whiteAlbedo(DefaultVertexFormat.POSITION_COLOR)
                && !ChimeraSkyBridge.whiteAlbedo(DefaultVertexFormat.POSITION_TEX)
                && !ChimeraSkyBridge.whiteAlbedo(DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL),
                "SKY_BASIC reads a white albedo; SKY_TEXTURED and CLOUDS their texture");
    }
}
