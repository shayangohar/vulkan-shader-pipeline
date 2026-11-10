package net.chimera.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit W7: the atlas level swap kept a sprite's VulkanMod levels only after rewriting them, and
 * recorded a swap as done before it ran, so one that stopped partway lost levels for good.
 */
final class AtlasLevelSwapTest {
    /** A rewrite that stops partway is undone whole by the restore that follows, and is not retried. */
    @Test
    void aRewriteThatStopsPartwayIsRestoredWhole() {
        Map<String, Sprite> sprites = Map.of("a", new Sprite(1), "b", new Sprite(2));
        int[][] hostA = sprites.get("a").copy();
        int[][] hostB = sprites.get("b").copy();
        sprites.get("b").failAt = 2;
        AtlasLevelSwap<String> swap = new AtlasLevelSwap<>();
        assertThrows(IllegalStateException.class, () -> swap.swap(true, List.of("a", "b"), sprites::get, () -> {}));
        assertFalse(Arrays.equals(hostB[1], sprites.get("b").levels[1]), "b's level 1 changed before level 2 failed");
        assertFalse(swap.due(true), "a failed rewrite is not tried again each frame");
        assertTrue(swap.due(false));
        assertEquals(2, swap.swap(false, List.of("a", "b"), sprites::get, () -> {}));
        assertLevels(hostA, sprites.get("a"));
        assertLevels(hostB, sprites.get("b"));
        assertFalse(swap.due(false));
        assertTrue(swap.due(true), "the next pack install tries the rewrite again");
    }

    /**
     * A restore that stops partway keeps what it has not put back; a rewrite then keeps those
     * levels rather than the rewritten ones it finds, so the next restore still has VulkanMod's.
     */
    @Test
    void aRestoreThatStopsPartwayKeepsTheLevelsItHasNotPutBack() {
        Sprite sprite = new Sprite(2);
        int[][] host = sprite.copy();
        AtlasLevelSwap<String> swap = new AtlasLevelSwap<>();
        swap.swap(true, List.of("b"), name -> sprite, () -> {});
        sprite.failAt = 2;
        assertThrows(IllegalStateException.class, () -> swap.swap(false, List.of("b"), name -> sprite, () -> {}));
        assertArrayEquals(host[1], sprite.levels[1], "level 1 was put back before level 2 failed");
        assertTrue(swap.due(true));
        swap.swap(true, List.of("b"), name -> sprite, () -> {});
        swap.swap(false, List.of("b"), name -> sprite, () -> {});
        assertLevels(host, sprite);
    }

    /** A failed upload leaves the levels mixed; a retry, as at the next pack load, may run it again. */
    @Test
    void aFailedUploadIsTriedAgainOnlyAfterARetry() {
        Map<String, Sprite> sprites = Map.of("a", new Sprite(1));
        AtlasLevelSwap<String> swap = new AtlasLevelSwap<>();
        assertThrows(IllegalStateException.class, () -> swap.swap(true, List.of("a"), sprites::get,
                () -> { throw new IllegalStateException("upload failed"); }));
        assertFalse(swap.due(true));
        swap.retry();
        assertTrue(swap.due(true));
        assertEquals(1, swap.swap(true, List.of("a"), sprites::get, () -> {}));
        assertFalse(swap.due(true));
    }

    private static void assertLevels(int[][] expected, Sprite sprite) {
        for (int level = 0; level < expected.length; level++) {
            assertArrayEquals(expected[level], sprite.levels[level], "level " + level);
        }
    }

    /** Levels of 4x4, 2x2 and 1x1 texels; {@code failAt} fails one write at that level. */
    private static final class Sprite implements AtlasLevelSwap.Levels {
        final int[][] levels = new int[3][];
        int failAt = -1;

        Sprite(int seed) {
            for (int level = 0; level < levels.length; level++) {
                levels[level] = new int[width(level) * height(level)];
                for (int texel = 0; texel < levels[level].length; texel++) {
                    levels[level][texel] = 0xFF000000 | seed * 0x10101 * (level + 1) + texel;
                }
            }
        }

        int[][] copy() {
            int[][] copy = new int[levels.length][];
            for (int level = 0; level < levels.length; level++) copy[level] = levels[level].clone();
            return copy;
        }

        @Override
        public int count() {
            return levels.length;
        }

        @Override
        public int width(int level) {
            return 4 >> level;
        }

        @Override
        public int height(int level) {
            return 4 >> level;
        }

        @Override
        public int[] read(int level) {
            return levels[level].clone();
        }

        @Override
        public void write(int level, int[] abgr) {
            if (level == failAt) {
                failAt = -1;
                throw new IllegalStateException("write failed");
            }
            levels[level] = abgr.clone();
        }
    }
}
