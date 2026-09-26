package net.chimera.render;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.chimera.mixin.ChimeraSamplerAccessor;
import net.vulkanmod.render.engine.VkSampler;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Immutable sampler description carried beside selector bindings.
 *
 * <p>A profile names the Vulkan sampler ID plus the fields that affect
 * sampling: address modes, min/mag filters, anisotropy, and max LOD. Null
 * enum fields mean unknown: the ID alone is known (an id-only profile from
 * a binding seam that records only the long). Unknown fields never drive
 * derived-sampler creation; the resolver falls back to the base image ID
 * instead of guessing.</p>
 */
public record SamplerProfile(
        long id,
        AddressMode addressU,
        AddressMode addressV,
        FilterMode minFilter,
        FilterMode magFilter,
        int anisotropy,
        float maxLod) {

    /** ID-only profile: the sampler exists, its fields were never observed. */
    public static SamplerProfile ofId(long id) {
        return new SamplerProfile(id, null, null, null, null, 0, Float.MAX_VALUE);
    }

    /** Full profile from a live sampler object. Never guesses max LOD. */
    public static SamplerProfile of(GpuSampler sampler, long id) {
        Objects.requireNonNull(sampler, "sampler");
        float maxLod = maxLodOf(sampler);
        return new SamplerProfile(id,
                sampler.getAddressModeU(), sampler.getAddressModeV(),
                sampler.getMinFilter(), sampler.getMagFilter(),
                sampler.getMaxAnisotropy(), maxLod);
    }

    private static float maxLodOf(GpuSampler sampler) {
        if (sampler instanceof ChimeraSamplerAccessor accessor) {
            return accessor.chimera$maxLod();
        }
        OptionalDouble declared = sampler.getMaxLod();
        return declared.isPresent() ? (float) declared.getAsDouble() : Float.MAX_VALUE;
    }

    /** True when every field needed for derived-sampler creation is known. */
    public boolean complete() {
        return addressU != null && addressV != null
                && minFilter != null && magFilter != null;
    }
}
