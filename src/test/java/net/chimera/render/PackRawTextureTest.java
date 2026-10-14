package net.chimera.render;

import net.chimera.shaderpack.RawTextureSpec;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8_UNORM;

class PackRawTextureTest {
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
