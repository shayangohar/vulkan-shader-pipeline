package net.chimera.mixin;

import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Exposes the descriptor list while a Chimera pipeline is being assembled.
 *
 * <p>The VulkanMod builder defaults every combined image sampler to
 * SHADER_READ_ONLY_OPTIMAL. A pack storage image can also be sampled by the
 * same stage, so that one descriptor must use GENERAL. This accessor keeps
 * that adjustment local to pack pipeline construction.</p>
 */
@Mixin(Pipeline.Builder.class)
public interface ChimeraPipelineBuilderAccessor {
    @Accessor("imageDescriptors")
    List<ImageDescriptor> chimera$imageDescriptors();
}
