package net.chimera.render.vertex;

import net.chimera.render.shader.TerrainVertexUniforms;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terrain texture coordinates as Iris's terrain vertex holds them: the word ChimeraExtVertexBuilder
 * writes, decoded as the terrain preamble's chimeraTexCoord0Value does, puts a sprite's edge one
 * sub-texel inside the sprite. The measured case (noble cave white dots.rdc, a 2048x1024 block atlas):
 * a quad's low v edge at row 432 carried vanilla's inward shrink of half a 1/32768 step, VulkanMod's
 * truncating word dropped it, and the edge sat on the boundary under a transparent sprite.
 */
final class TerrainTexCoordTest {
    private static final double STEP = 1.0 / 32768.0;
    private static final int ATLAS_HEIGHT = 1024;
    private static final int SUB_TEXEL_BITS = 8;
    private static final double SUB_TEXEL = 1.0 / ATLAS_HEIGHT / (1 << SUB_TEXEL_BITS);

    @Test
    void spriteEdgesDecodeInsideTheirSprite() {
        double top = 432.0 / ATLAS_HEIGHT;
        double bottom = 448.0 / ATLAS_HEIGHT;
        float low = (float) (top + STEP / 2);
        float high = (float) (bottom - STEP / 2);
        float centroid = (low + high) / 2;
        assertEquals(top, (short) (low * 32768.0F) * STEP, "VulkanMod's truncated word sits on the boundary");
        assertTrue(decode(centroid, low) > top, "the low edge decodes inside its sprite");
        assertTrue(decode(centroid, high) < bottom, "the high edge decodes inside its sprite");
        assertEquals(low, decode(centroid, low), STEP, "within a step of the coordinate");
    }

    @Test
    void anEdgeOnTheBoundaryLandsOneSubTexelInside() {
        float top = 432.0F / ATLAS_HEIGHT;
        float bottom = 448.0F / ATLAS_HEIGHT;
        float centroid = (top + bottom) / 2;
        assertEquals(top + SUB_TEXEL, decode(centroid, top), 1e-9);
        assertEquals(bottom - SUB_TEXEL, decode(centroid, bottom), 1e-9);
    }

    /** chimeraTexCoord0Value: the 15-bit word less the step that bit 15 records, taken back less one sub-texel. */
    private static double decode(float centroid, float coordinate) {
        int word = ChimeraExtVertexBuilder.encodeTexture(centroid, coordinate) & 0xFFFF;
        double stepUp = word > 0x7FFF ? 1.0 : -1.0;
        return (word & 0x7FFF) * STEP - stepUp * TerrainVertexUniforms.texcoordShrink(ATLAS_HEIGHT, SUB_TEXEL_BITS);
    }
}
