package net.chimera.mixin;

import net.chimera.shaderpack.SpirvLocalInitializer;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * Pack modules compiled by VulkanMod get zero initializers on their
 * uninitialized locals, matching the GL drivers the packs target
 * (SpirvLocalInitializer). Host shaders compile outside the pack scope and
 * keep their bytes. The returned SPIRV keeps shaderc's handle; its
 * transformed bytes are a GC-owned direct buffer, read once at module
 * creation.
 */
@Mixin(value = SPIRVUtils.class, remap = false)
public abstract class ChimeraSpirvLocalInitMixin {
    @Inject(method = "compileShader", at = @At("RETURN"), cancellable = true, require = 1)
    private static void chimera$initializePackLocals(String filename, String source,
            SPIRVUtils.ShaderKind kind, CallbackInfoReturnable<SPIRVUtils.SPIRV> callback) {
        if (!SpirvLocalInitializer.packCompileActive()) return;
        SPIRVUtils.SPIRV compiled = callback.getReturnValue();
        ByteBuffer initialized = SpirvLocalInitializer.apply(compiled.bytecode());
        if (initialized != compiled.bytecode()) {
            callback.setReturnValue(new SPIRVUtils.SPIRV(((ChimeraSpirvAccessor) (Object) compiled).chimera$handle(),
                    initialized));
        }
    }
}
