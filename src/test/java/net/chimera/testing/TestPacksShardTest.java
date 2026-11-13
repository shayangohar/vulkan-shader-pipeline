package net.chimera.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The corpus gate's shards (CorpusChecks) cover every pack exactly once and share the bytes evenly. */
final class TestPacksShardTest {
    @Test
    void everyPackLandsInExactlyOneShardAndTheShardsAreEven(@TempDir Path root) throws IOException {
        List<Path> packs = new ArrayList<>();
        int[] sizes = {900, 700, 600, 400, 300, 300, 200, 100, 100, 50};
        for (int index = 0; index < sizes.length; index++) {
            packs.add(Files.write(root.resolve("pack" + index + ".zip"), new byte[sizes[index]]));
        }
        List<Path> dealt = new ArrayList<>();
        long largest = 0;
        long smallest = Long.MAX_VALUE;
        for (int shard = 0; shard < 3; shard++) {
            List<Path> mine = TestPacks.shard(packs, shard, 3);
            assertEquals(mine, TestPacks.shard(packs, shard, 3), "the same packs land in the same shard");
            dealt.addAll(mine);
            long bytes = mine.stream().mapToLong(TestPacksShardTest::size).sum();
            largest = Math.max(largest, bytes);
            smallest = Math.min(smallest, bytes);
        }
        assertEquals(packs.size(), dealt.size());
        assertTrue(dealt.containsAll(packs));
        assertTrue(largest - smallest <= 300, "largest first to the lightest shard: " + largest + " vs " + smallest);
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
