package net.chimera.render;

import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * OpenGL texture names for Chimera-owned images. VulkanMod binds a draw's sampled images from
 * RenderSystem's texture units, which hold OpenGL names, so an image a draw samples through a
 * unit needs one, as VulkanMod names its own main images.
 */
final class GlImageAlias {
    private GlImageAlias() {}

    /** A new OpenGL texture name for {@code image}. */
    static int create(VulkanImage image) {
        int id = GlTexture.genTextureId();
        GlTexture.bindIdToImage(id, image);
        return id;
    }

    /** Deletes an alias name without freeing its image, which its owner frees. */
    static void retire(int id) {
        if (id == 0 || GlTexture.getTexture(id) == null) return;
        GlTexture.setVulkanImage(id, null);
        GlTexture.glDeleteTextures(id);
    }
}
