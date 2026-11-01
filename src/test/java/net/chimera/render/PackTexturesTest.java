package net.chimera.render;

import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PackResourceStatus;
import net.chimera.shaderpack.RawTextureSpec;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;

/** Pack textures: the device's image limits, raw texture data, and game texture locations. */
final class PackTexturesTest {
    /**
     * Alpha Piscium's 4096x2048 star map fits a 16384 limit: only the device's image limit refuses
     * a pack texture, not its encoded size or pixel count.
     */
    @Test
    void anImageWithinTheDeviceLimitIsMade() {
        assertNull(PackImageLimits.failure("texture", 2, new int[] {4096, 2048}, 16384));
        assertNull(PackImageLimits.failure("texture", 2, new int[] {16384, 16384}, 16384), "the limit itself fits");
    }

    /** Each axis is checked against the limit for the image's dimensions; only those axes count. */
    @Test
    void anAxisPastTheLimitNamesTheImageAndTheLimit() {
        assertEquals("texture at 16385x8 exceeds the device's 2D limit 16384",
                PackImageLimits.failure("texture", 2, new int[] {16385, 8}, 16384));
        assertEquals("raw texture at 64x64x4096 exceeds the device's 3D limit 2048",
                PackImageLimits.failure("raw texture", 3, new int[] {64, 64, 4096}, 2048));
        assertNull(PackImageLimits.failure("raw texture", 1, new int[] {256, 99999, 99999}, 16384),
                "a 1D image's unused axes are not its extent");
    }

    /** Rows lose GL's 4-byte padding, and RGB gains an alpha of 1 in its own storage. */
    @Test
    void texelsDropRowPaddingAndWidenRgb() {
        RawTextureSpec bytes = RawTextureSpec.parse("lut.dat TEXTURE_2D RGB8 1 2 RGB UNSIGNED_BYTE");
        assertArrayEquals(new byte[] {1, 2, 3, (byte) 0xFF, 4, 5, 6, (byte) 0xFF},
                texels(new byte[] {1, 2, 3, 0, 4, 5, 6}, bytes));
        RawTextureSpec halves = RawTextureSpec.parse("lut.dat TEXTURE_1D RGB16F 1 RGB HALF_FLOAT");
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 0x00, 0x3C},
                texels(new byte[] {1, 2, 3, 4, 5, 6}, halves), "half-float 1.0 is 0x3C00");
    }

    /** Photon's lookup tables: the scattering volume widens to RGBA16F; worley noise stays R8. */
    @Test
    void photonFormats() {
        assertEquals(VK_FORMAT_R16G16B16A16_SFLOAT, PackRawTexture.vulkanFormat(RawTextureSpec.parse(
                "scattering.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT").storage()));
        assertEquals(VK_FORMAT_R8_UNORM, PackRawTexture.vulkanFormat(RawTextureSpec.parse(
                "worley.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE").storage()));
    }

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

    private static byte[] texels(byte[] file, RawTextureSpec spec) {
        ByteBuffer texels = PackRawTexture.texels(file, spec, spec.storage());
        try {
            byte[] out = new byte[texels.remaining()];
            texels.get(out);
            return out;
        } finally {
            MemoryUtil.memFree(texels);
        }
    }
}
