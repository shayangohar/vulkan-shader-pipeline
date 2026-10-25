package net.chimera.render;

import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackGameTextureTest {
    /** Alpha Piscium's usam_blocksNormal: the block atlas's normal map, as Iris's PBRType reads the name. */
    @Test
    void aPbrSuffixNamesAMapOfTheTextureWithoutIt() {
        assertEquals(new PackGameTexture.Source("minecraft", "textures/atlas/blocks.png",
                        MaterialMapOwner.MaterialMapKind.NORMALS),
                PackGameTexture.Source.parse("minecraft:textures/atlas/blocks_n.png"));
        assertEquals(new PackGameTexture.Source("minecraft", "textures/block/stone.png",
                        MaterialMapOwner.MaterialMapKind.SPECULAR),
                PackGameTexture.Source.parse("minecraft:textures/block/stone_s.png"));
    }

    /** Without a PBR suffix the location is the texture itself (Shrimple's vanilla clouds). */
    @Test
    void aPlainLocationIsTheTexture() {
        assertEquals(new PackGameTexture.Source("minecraft", "textures/environment/clouds.png", null),
                PackGameTexture.Source.parse(" minecraft:textures/environment/clouds.png "));
        assertEquals(new PackGameTexture.Source("minecraft", "textures/my_n.dir/clouds.png", null),
                PackGameTexture.Source.parse("minecraft:textures/my_n.dir/clouds.png"),
                "the suffix is read from the file name");
    }

    /**
     * A saved pack loads with the renderer, before Minecraft has a TextureManager. A compute reading a
     * game texture is still admitted, and the lookup waits for the bind, as Iris's TextureWrapper does.
     */
    @Test
    void aGameTextureCountsBeforeTheGameHasTextures() {
        PackResourceBinding normals = new PackResourceBinding(null, "usam_blocksNormal",
                "customTexture.usam_blocksNormal", PackResourceKind.PACK_TEXTURE,
                "minecraft:textures/atlas/blocks_n.png", 0, "nearest", "repeat",
                PackResourceStatus.GAME_RESOURCE, List.of());
        try (PackResourceOwner owner = PackResourceOwner.load(PackResourcePlan.empty(), null, () -> null)) {
            assertTrue(owner.available(normals));
            assertNull(owner.image(normals), "no image until the game has textures; the compute skips the frame");
        }
    }

    /** Iris splits on ':' and keeps the first two parts; a path with no namespace is not a game texture. */
    @Test
    void onlyANamespacedLocationIsAGameTexture() {
        assertNull(PackGameTexture.Source.parse("textures/noise.png"));
        assertNull(PackGameTexture.Source.parse(null));
        assertEquals(new PackGameTexture.Source("minecraft", "textures/my.dir/blocks",
                        MaterialMapOwner.MaterialMapKind.NORMALS),
                PackGameTexture.Source.parse("minecraft:textures/my.dir/blocks_n"),
                "a dot in a directory is no extension");
    }
}
