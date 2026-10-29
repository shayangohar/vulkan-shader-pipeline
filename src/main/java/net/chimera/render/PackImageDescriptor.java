package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;

/**
 * A pack program's image at a selector slot Chimera binds: a target, depth, shadow map, material
 * map, pack image or the entity overlay. Every draw binds it before descriptor upload (the terrain,
 * post and bindDescriptorImages seams), so VulkanMod skips the RenderSystem unit of the same number.
 * That unit holds no texture for it (the overlay unit only under an overlay render type), and
 * VulkanMod would first bind and log its missing texture, once per session, hiding later misses.
 */
public class PackImageDescriptor extends ImageDescriptor {
    public PackImageDescriptor(int binding, String type, String name, int textureSlot, boolean storage) {
        super(binding, type, name, textureSlot, storage);
    }

    @Override
    public boolean usesVanillaTextureBinding() {
        return false;
    }
}
