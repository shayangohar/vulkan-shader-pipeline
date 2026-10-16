package net.chimera.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to vanilla's parsed cloud cells. VulkanMod replaces vanilla's cloud pass, but
 * vanilla's CloudRenderer is still a reload listener, so its cells follow resource reloads.
 */
@Mixin(CloudRenderer.class)
public interface ChimeraVanillaCloudsAccessor {
    @Accessor("texture")
    CloudRenderer.TextureData chimera$texture();
}
