package net.chimera.mixin;

import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the shaderc result handle so a transformed module keeps its owner. */
@Mixin(value = SPIRVUtils.SPIRV.class, remap = false)
public interface ChimeraSpirvAccessor {
    @Accessor("handle")
    long chimera$handle();
}
