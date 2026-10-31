package net.chimera.render;

/**
 * A VulkanImage that keeps clamping to its edge once vanilla has asked for it
 * (ChimeraNativeImageClampMixin), as an OpenGL texture keeps its wrap mode. VulkanMod rebuilds a
 * texture's sampler from flags, and its AbstractTexture.setFilter hook passes clamp=false on every
 * draw; ChimeraVulkanImageClampMixin adds the clamp back to each sampler built for this image.
 */
public interface EdgeClampedImage {
    /** Clamps this image's sampler to its edge, now and whenever VulkanMod rebuilds it. */
    void chimera$clampToEdge();
}
