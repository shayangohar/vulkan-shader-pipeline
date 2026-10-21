package net.chimera.render;

import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.SamplerManager.SamplerKey;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL14.GL_COMPARE_R_TO_TEXTURE;

/**
 * Chimera's samplers, described as OpenGL describes them and created and cached by VulkanMod
 * (SamplerManager), which also destroys them. None filters anisotropically or biases its level.
 */
public final class ChimeraSamplers {
    private ChimeraSamplers() {}

    /** Linear or nearest filtering on one level, clamped to the edge or repeating. */
    public static long flat(boolean linear, boolean clamp) {
        return SamplerManager.getTextureSampler(flatKey(linear, clamp));
    }

    /** Linear filtering clamped to the edge, reading levels 0 to {@code maxLevel}, linearly between them. */
    public static long linearMipmapped(int maxLevel) {
        return mipmapped(maxLevel, true);
    }

    /**
     * Levels 0 to {@code maxLevel} clamped to the edge: linearly within and between levels, or the
     * nearest texel of the nearest level (Iris's GL_NEAREST_MIPMAP_NEAREST for integer targets).
     */
    public static long mipmapped(int maxLevel, boolean linear) {
        return SamplerManager.getTextureSampler(linear ? linearMipmappedKey(maxLevel) : nearestMipmappedKey(maxLevel));
    }

    /**
     * A depth comparison sampler (GLSL sampler2DShadow): linear, clamped to the edge, passing where
     * the reference depth is at most the stored one, as OpenGL's GL_LEQUAL comparison.
     */
    public static long depthCompare() {
        return depthCompare(true);
    }

    /** {@link #depthCompare()}, or its nearest variant (Iris GlSampler.NEAREST_HW for shadowtexNNearest). */
    public static long depthCompare(boolean linear) {
        return SamplerManager.getTextureSampler(linear ? depthCompareKey() : nearestDepthCompareKey());
    }

    static SamplerKey flatKey(boolean linear, boolean clamp) {
        int filter = linear ? GL_LINEAR : GL_NEAREST;
        int wrap = clamp ? GL_CLAMP_TO_EDGE : GL_REPEAT;
        return new SamplerKey(filter, filter, wrap, wrap, wrap, 0.0F, 0.0F, 0.0F, 0, 0);
    }

    static SamplerKey linearMipmappedKey(int maxLevel) {
        if (maxLevel <= 0) return flatKey(true, true);
        return new SamplerKey(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                0.0F, maxLevel, 0.0F, 0, 0);
    }

    static SamplerKey nearestMipmappedKey(int maxLevel) {
        if (maxLevel <= 0) return flatKey(false, true);
        return new SamplerKey(GL_NEAREST_MIPMAP_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                GL_CLAMP_TO_EDGE, 0.0F, maxLevel, 0.0F, 0, 0);
    }

    static SamplerKey depthCompareKey() {
        return new SamplerKey(GL_LINEAR, GL_LINEAR, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                0.0F, 0.0F, 0.0F, GL_COMPARE_R_TO_TEXTURE, GL_LEQUAL);
    }

    static SamplerKey nearestDepthCompareKey() {
        return new SamplerKey(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE, GL_CLAMP_TO_EDGE,
                0.0F, 0.0F, 0.0F, GL_COMPARE_R_TO_TEXTURE, GL_LEQUAL);
    }
}
