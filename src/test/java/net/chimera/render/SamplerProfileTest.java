package net.chimera.render;

import net.vulkanmod.vulkan.texture.SamplerManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;

/**
 * Pack programs sample at OpenGL's mip bias: a mipmapped sampler VulkanMod built from texture
 * flags, biased by its MIP_BIAS, maps to the same sampler at bias 0; every other sampler keeps its own.
 */
final class SamplerProfileTest {
    /** The block atlas as VulkanMod samples it: trilinear, repeating, four levels below the base. */
    @Test
    void aMipmappedFlagSamplerLosesOnlyVulkanModsBias() {
        int atlas = 4 << 8 | SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.USE_MIPMAPS_BIT
                | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT;
        assertEquals(SamplerProfile.HOST_MIP_BIAS, SamplerProfile.legacyKey((short) atlas).lodBias());
        assertEquals(new SamplerManager.SamplerKey(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT,
                GL_REPEAT, 0.0F, 4.0F, 0.0F, GL_NONE, GL_LEQUAL), SamplerProfile.unbiasedKey(atlas));
    }

    @Test
    void anUnmippedOrUnknownSamplerKeepsItsBias() {
        assertNull(SamplerProfile.unbiasedKey(SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.CLAMP_BIT));
        assertNull(SamplerProfile.unbiasedKey(-1), "not a flag sampler");
        assertNull(SamplerProfile.unbiasedKey(SamplerManager.REDUCTION_MIN_BIT | SamplerManager.USE_MIPMAPS_BIT),
                "a min/max reduction sampler has no OpenGL form");
    }
}
