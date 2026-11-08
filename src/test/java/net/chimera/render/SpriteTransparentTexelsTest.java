package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sprite's fully transparent texels take its alpha-weighted mean colour, averaged in linear
 * light, as Sodium loads them under Iris; every other texel keeps its own (texels are ABGR).
 */
final class SpriteTransparentTexelsTest {
    /** Opaque white and a third-opaque black average to linear 0.75: sRGB 225, not the sRGB mean 191. */
    @Test
    void transparentTexelsTakeTheAlphaWeightedLinearMean() {
        int[] sprite = {0xFFFFFFFF, 0x55000000, 0x00000000, 0x00FFFFFF};
        assertTrue(SpriteTransparentTexels.fill(sprite));
        assertArrayEquals(new int[] {0xFFFFFFFF, 0x55000000, 0x00E1E1E1, 0x00E1E1E1}, sprite);
    }

    /** Pegasus's particle light reads the transparent corner of a sprite with one visible texel. */
    @Test
    void aSingleVisibleTexelColoursTheRest() {
        int[] sprite = {0x00000000, 0x00000000, 0xFF2040C0, 0x00FFFFFF};
        assertTrue(SpriteTransparentTexels.fill(sprite));
        assertArrayEquals(new int[] {0x002040C0, 0x002040C0, 0xFF2040C0, 0x002040C0}, sprite);
    }

    @Test
    void aSpriteWithNoTransparentTexelOrNothingVisibleKeepsItsOwn() {
        int[] opaque = {0xFF102030, 0x01FFFFFF};
        int[] invisible = {0x00102030, 0x00FFFFFF};
        assertFalse(SpriteTransparentTexels.fill(opaque));
        assertFalse(SpriteTransparentTexels.fill(invisible));
        assertArrayEquals(new int[] {0xFF102030, 0x01FFFFFF}, opaque);
        assertArrayEquals(new int[] {0x00102030, 0x00FFFFFF}, invisible);
    }
}
