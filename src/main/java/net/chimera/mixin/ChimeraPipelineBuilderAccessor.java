package net.chimera.mixin;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.layout.PushConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets a Chimera pipeline's push constants. VulkanMod's builder takes them only from a JSON
 * config; Chimera builds its layouts itself (HostPipelineLayout).
 */
@Mixin(value = Pipeline.Builder.class, remap = false)
public interface ChimeraPipelineBuilderAccessor {
    @Accessor("pushConstants")
    void chimera$setPushConstants(PushConstants pushConstants);
}
