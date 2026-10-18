package net.chimera.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.vulkanmod.gl.GlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;

/** The Vulkan images behind Minecraft's textures, which VulkanMod keeps under their OpenGL names. */
public final class ChimeraTextures {
    private ChimeraTextures() {}

    /** The image a texture uploaded to, or null when it has none (not uploaded yet, or closed). */
    public static VulkanImage image(AbstractTexture texture) {
        GlTexture gl = texture == null ? null : GlTexture.getTexture(texture.getId());
        return gl == null ? null : gl.getVulkanImage();
    }
}
