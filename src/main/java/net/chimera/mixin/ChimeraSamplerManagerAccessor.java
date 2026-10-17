package net.chimera.mixin;

import it.unimi.dsi.fastutil.shorts.Short2LongMap;
import net.vulkanmod.vulkan.texture.SamplerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * VulkanMod's sampler caches, read to recover how an image's sampler samples (SamplerProfile): a
 * VulkanImage keeps only the Vulkan sampler handle its texture parameters chose.
 */
@Mixin(value = SamplerManager.class, remap = false)
public interface ChimeraSamplerManagerAccessor {
    /** Texture samplers keyed by their flags (low byte) and mip ceiling (high byte). */
    @Accessor("SAMPLERS")
    static Short2LongMap chimera$samplers() {
        throw new AssertionError();
    }

    /** Samplers built from OpenGL sampler objects and parameters. */
    @Accessor("GL_SAMPLERS")
    static Map<SamplerManager.SamplerKey, Long> chimera$glSamplers() {
        throw new AssertionError();
    }
}
