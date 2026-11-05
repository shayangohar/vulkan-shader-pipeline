package net.chimera.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Atlas mip levels follow Sodium's alpha-weighted blend, as Iris setups build them (texels are ABGR).
 * The short-grass chain is the one Iris 1.8.14 with Sodium 0.8.13 uploaded for SPBR's short_grass.png,
 * read from the Reverie night capture's block atlas (2026-11-04).
 */
final class AtlasMipBlendTest {
    /** SPBR short_grass.png's opaque texels, row by row; every other texel is fully transparent. */
    private static final String[] SHORT_GRASS = {
            "................", "................", "....#..#..#..#..", ".....#.#..#.#...",
            "...#.#.#.##.#...", ".#..##.#.#.#..#.", "..#.##.###.#.#..", "..#..##.##.#.#..",
            "..##.#######.#..", "#.##.#.##.###.#.", "#.##.##.###.#.##", "##.####.###.##.#",
            "#.##############", "###############.", ".###############", "################"};

    @Test
    void shortGrassFadesAsIrisBuildsIt() {
        int[] level = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                level[y * 16 + x] = SHORT_GRASS[y].charAt(x) == '#' ? 0xFF3C8A2E : 0x003C8A2E;
            }
        }
        int[][] iris = {
                {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 63, 63, 0, 63, 63, 0, 15, 15, 159, 63, 63, 63, 15, 15,
                        0, 63, 159, 63, 255, 63, 63, 0, 15, 255, 63, 159, 159, 255, 63, 15,
                        159, 159, 159, 63, 255, 63, 159, 159, 159, 255, 255, 255, 255, 255, 255, 159,
                        159, 255, 255, 255, 255, 255, 255, 255},
                {0, 15, 3, 3, 15, 111, 111, 15, 147, 111, 183, 99, 207, 255, 255, 231},
                {33, 33, 180, 192},
                {109}};
        int size = 16;
        for (int[] expected : iris) {
            level = AtlasMipBlend.reduce(level, size, size, size / 2, size / 2);
            size /= 2;
            int[] alpha = new int[level.length];
            for (int index = 0; index < level.length; index++) alpha[index] = level[index] >>> 24;
            assertArrayEquals(expected, alpha, "alpha at the " + size + "x" + size + " level");
        }
    }

    /** Equal alphas average in linear light: white and black meet at linear 0.5, sRGB 188. */
    @Test
    void equalAlphasAverageInLinearLight() {
        assertEquals(0xFFBCBCBC, AtlasMipBlend.blend(0xFFFFFFFF, 0xFF000000));
        assertEquals(0xFF00BCBC, AtlasMipBlend.blend(0xFF0000FF, 0xFF00FF00));
    }

    /** A fully transparent texel gives way: the other keeps its colour, its alpha quartered. */
    @Test
    void aTransparentTexelGivesWayAndQuartersTheAlpha() {
        assertEquals(0x3FFFFFFF, AtlasMipBlend.blend(0xFFFFFFFF, 0x00000000));
        assertEquals(0x3F2040C0, AtlasMipBlend.blend(0x00FFFFFF, 0xFF2040C0));
    }

    /** Unequal alphas weigh the colours by alpha (255:85 is linear 0.75, sRGB 225) and average the alphas. */
    @Test
    void unequalAlphasWeighTheColour() {
        assertEquals(0xAAE1E1E1, AtlasMipBlend.blend(0xFFFFFFFF, 0x55000000));
    }

    /** An odd size clamps the footprint at the edge instead of reading past it. */
    @Test
    void anOddSizeClampsTheFootprint() {
        int[] src = {0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFF000000, 0xFFFFFFFF,
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};
        assertArrayEquals(new int[] {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF},
                AtlasMipBlend.reduce(src, 3, 3, 2, 2));
    }
}
