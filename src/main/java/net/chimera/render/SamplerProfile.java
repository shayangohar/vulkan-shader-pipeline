package net.chimera.render;

import net.chimera.mixin.ChimeraSamplerManagerAccessor;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_LEQUAL;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * The sampler a draw samples an image with, and how: VulkanMod's OpenGL-style sampler key
 * (filters with their mip mode, wrap modes, LOD range and bias). A null key means only the Vulkan
 * sampler is known; anything derived from the profile then falls back to that sampler rather than
 * guess its fields.
 */
public record SamplerProfile(long id, SamplerManager.SamplerKey key) {

    /** ID-only profile: the sampler exists, its fields were never observed. */
    public static SamplerProfile ofId(long id) {
        return new SamplerProfile(id, null);
    }

    /** The profile of an image's own sampler, read back from VulkanMod's sampler caches. */
    public static SamplerProfile of(VulkanImage image) {
        long id = image.getSampler();
        return new SamplerProfile(id, keyOf(id));
    }

    /** True when every field needed to derive a sampler is known. */
    public boolean complete() {
        return key != null;
    }

    /**
     * This profile's sampling with nearest texel filtering inside the same mip mode, and clamped
     * to the base level when {@code baseLevelOnly}; null when the profile is incomplete.
     */
    public SamplerManager.SamplerKey derive(boolean nearest, boolean baseLevelOnly) {
        if (key == null) return null;
        int min = nearest ? nearestOf(key.minFilter()) : key.minFilter();
        int mag = nearest ? GL_NEAREST : key.magFilter();
        float maxLod = baseLevelOnly ? 0.0F : key.maxLod();
        return new SamplerManager.SamplerKey(min, mag, key.wrapS(), key.wrapT(), key.wrapR(),
                Math.min(key.minLod(), maxLod), maxLod, key.lodBias(), key.compareMode(), key.compareFunc());
    }

    private static int nearestOf(int minFilter) {
        return switch (minFilter) {
            case GL_LINEAR -> GL_NEAREST;
            case GL_LINEAR_MIPMAP_NEAREST -> GL_NEAREST_MIPMAP_NEAREST;
            case GL_LINEAR_MIPMAP_LINEAR -> GL_NEAREST_MIPMAP_LINEAR;
            default -> minFilter;
        };
    }

    /** The key VulkanMod created {@code sampler} from, or null when it is not one of its cached samplers. */
    static SamplerManager.SamplerKey keyOf(long sampler) {
        if (sampler == 0L) return null;
        for (Map.Entry<SamplerManager.SamplerKey, Long> entry
                : ChimeraSamplerManagerAccessor.chimera$glSamplers().entrySet()) {
            if (entry.getValue() == sampler) return entry.getKey();
        }
        for (var entry : ChimeraSamplerManagerAccessor.chimera$samplers().short2LongEntrySet()) {
            if (entry.getLongValue() == sampler) return legacyKey(entry.getShortKey());
        }
        return null;
    }

    /**
     * The OpenGL-style key of a texture sampler VulkanMod built from its flags (texture parameters):
     * the flags in the low byte and the mip ceiling in the high byte, mipmapped levels biased by
     * {@code -0.5}. A min/max reduction sampler has no OpenGL form, so it stays unknown.
     */
    static SamplerManager.SamplerKey legacyKey(short packed) {
        int flags = packed & 0xFF;
        if ((flags & ~(SamplerManager.LINEAR_FILTERING_BIT | SamplerManager.CLAMP_BIT
                | SamplerManager.USE_MIPMAPS_BIT | SamplerManager.MIPMAP_LINEAR_FILTERING_BIT)) != 0) return null;
        boolean linear = (flags & SamplerManager.LINEAR_FILTERING_BIT) != 0;
        boolean mipmaps = (flags & SamplerManager.USE_MIPMAPS_BIT) != 0;
        boolean mipLinear = (flags & SamplerManager.MIPMAP_LINEAR_FILTERING_BIT) != 0;
        int mag = linear ? GL_LINEAR : GL_NEAREST;
        int min = !mipmaps ? mag
                : linear ? (mipLinear ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR_MIPMAP_NEAREST)
                : (mipLinear ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_NEAREST);
        int wrap = (flags & SamplerManager.CLAMP_BIT) != 0 ? GL_CLAMP_TO_EDGE : GL_REPEAT;
        float maxLod = mipmaps ? (packed >> 8) & 0xFF : 0.0F;
        return new SamplerManager.SamplerKey(min, mag, wrap, wrap, wrap, 0.0F, maxLod,
                mipmaps ? -0.5F : 0.0F, GL_NONE, GL_LEQUAL);
    }
}
