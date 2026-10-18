package net.chimera.render;

import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;


/**
 * Immutable draw context for one material program transaction.
 *
 * <p>Captured once per draw from real binding state, then threaded through
 * the whole transaction as an explicit resolver input. The resolver never
 * re-queries mutable global slot state from a context: the albedo image
 * and its sampler profile are fixed before the first capture, so an entity
 * batch can no longer leak its sampler into the next terrain draw.</p>
 */
public record DrawMaterialContext(VulkanImage albedo, SamplerProfile profile) {
    public DrawMaterialContext {
        if (albedo != null && profile == null) {
            throw new IllegalArgumentException("MATERIAL_CONTEXT_PROFILE_MISSING");
        }
    }

    /** Terrain path: the atlas and the sampler its texture parameters chose. */
    public static DrawMaterialContext forTerrain(VulkanImage albedo) {
        return albedo == null ? new DrawMaterialContext(null, null)
                : new DrawMaterialContext(albedo, SamplerProfile.of(albedo));
    }

    /** Legacy query for callers with no captured seam: live slot 0 plus its recorded profile. */
    public static DrawMaterialContext captureLive() {
        VulkanImage albedo = VTextureSelector.getImage(0);
        if (albedo == null) {
            return new DrawMaterialContext(null, null);
        }
        SamplerProfile recorded = ChimeraTextureBindingState.samplerProfile(0);
        SamplerProfile profile = recorded != null
                ? recorded
                : SamplerProfile.of(albedo);
        return new DrawMaterialContext(albedo, profile);
    }
}
