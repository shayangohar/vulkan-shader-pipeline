package net.chimera.render;

import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;

/**
 * A pack program's image at a selector slot Chimera binds: a target, depth, shadow map, material
 * map or pack image. Every draw binds it before descriptor upload (the terrain, post and
 * bindDescriptorImages seams), so VulkanMod skips the RenderSystem unit of the same number: no game
 * texture lives there, and VulkanMod would first bind its missing texture and log it (once per
 * session, which hid every later missing texture).
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
