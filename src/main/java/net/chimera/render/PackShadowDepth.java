package net.chimera.render;

import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Owns the comparison sampler that pack shadow lookups need.
 *
 * <p>Pack programs sample the engine shadow depth directly: `sampler2DShadow`
 * lookups compare against the stored depth and `sampler2D` lookups read it, so
 * the pack-facing shadow texture is the shadow attachment and not a converted
 * copy. The shadow vertex stage already maps the authored clip output into the
 * OpenGL window range the receivers expect, so a comparison against the stored
 * depth is the documented OptiFine behaviour.</p>
 *
 * <p>A copy of the depth into a colour image was tried first. Comparison
 * sampling against a colour image is the one configuration that faulted the
 * driver (device lost under BSL and Complementary), while comparison sampling
 * against the depth image itself ran stably for both packs, so the copy is
 * gone and the descriptor sampler carries the contract instead.</p>
 */
public final class PackShadowDepth {
    private long compareSampler;

    /**
     * Takes the shared comparison sampler: the engine shadow sampler's linear filter and edge
     * clamp, with the OpenGL shadow comparison the packs author against. Safe to call for every
     * pack load.
     */
    public void install() {
        this.compareSampler = ChimeraSamplers.depthCompare();
    }

    /** Compare-enabled sampler, or 0 before {@link #install()}. */
    public long compareSampler() {
        return this.compareSampler;
    }

    /**
     * True when a declared sampler type reads the shadow depth through a depth
     * comparison. The converted GLSL decides this: {@code sampler2DShadow}
     * lookups compare, while a program that declares the same texture as
     * {@code sampler2D} reads and compares it by hand.
     */
    public static boolean requiresCompareSampler(String declaredType) {
        return declaredType != null && declaredType.contains("Shadow");
    }

    /**
     * Sampler one shadow descriptor binds: the compare-enabled sampler when the
     * program's lookup needs a comparison, otherwise the depth image's own
     * sampler, so a program that reads the same image without a shadow sampler
     * keeps the non-compare path its shader was written for.
     */
    public long samplerFor(VulkanImage boundImage, boolean compareRequired) {
        if (!compareRequired || this.compareSampler == 0L) {
            return boundImage.getSampler();
        }
        return this.compareSampler;
    }

    public void cleanUp() {
        this.compareSampler = 0L;
    }
}
