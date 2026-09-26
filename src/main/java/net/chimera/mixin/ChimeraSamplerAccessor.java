package net.chimera.mixin;

import net.vulkanmod.render.engine.VkSampler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the pinned VulkanMod sampler ceiling.
 * {@code VkSampler} stores max LOD privately while its public getter
 * returns null, so profiles would otherwise have to guess or drop it.
 */
@Mixin(value = VkSampler.class, remap = false)
public interface ChimeraSamplerAccessor {
    @Accessor("maxLod")
    float chimera$maxLod();
}
