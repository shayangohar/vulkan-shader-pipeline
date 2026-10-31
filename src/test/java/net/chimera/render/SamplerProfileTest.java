package net.chimera.render;

import net.vulkanmod.vulkan.texture.SamplerManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * Pack programs sample game textures as OpenGL does for Iris: a sampler VulkanMod built from
 * texture flags loses its MIP_BIAS and clamps to the edge where vanilla's upload asked; every
 * other sampler keeps its own.
 */
final class SamplerProfileTest {
    /** The block atlas as VulkanMod samples it: trilinear, repeating, four levels below the base. */
    @Test
    void aMipmappedFlagSamplerLosesOnlyVulkanModsBias() {
        int atlas = 4 << 8 | SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.USE_MIPMAPS_BIT
                | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT;
        assertEquals(SamplerProfile.HOST_MIP_BIAS, SamplerProfile.legacyKey((short) atlas).lodBias());
        assertEquals(new SamplerManager.SamplerKey(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT,
                GL_REPEAT, 0.0F, 4.0F, 0.0F, GL_NONE, GL_LEQUAL), SamplerProfile.glKey(atlas, false));
    }

    /** misc/shadow.png as VulkanMod samples it, nearest and repeating, after vanilla asked to clamp it. */
    @Test
    void aTextureVanillaClampedClampsToItsEdge() {
        assertEquals(new SamplerManager.SamplerKey(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                GL_CLAMP_TO_EDGE, 0.0F, 0.0F, 0.0F, GL_NONE, GL_LEQUAL), SamplerProfile.glKey(1 << 8, true));
        assertNull(SamplerProfile.glKey(1 << 8 | SamplerManager.CLAMP_BIT, true), "already clamped");
    }

    @Test
    void anyOtherSamplerIsKept() {
        assertNull(SamplerProfile.glKey(SamplerManager.LINEAR_FILTERING_BIT, false), "unmipped, repeating");
        assertNull(SamplerProfile.glKey(-1, true), "not a flag sampler");
        assertNull(SamplerProfile.glKey(SamplerManager.REDUCTION_MIN_BIT | SamplerManager.USE_MIPMAPS_BIT, false),
                "a min/max reduction sampler has no OpenGL form");
    }
}
