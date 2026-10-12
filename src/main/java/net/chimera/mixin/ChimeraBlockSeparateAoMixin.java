package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.SeparateAoColor;
import net.vulkanmod.render.chunk.build.frapi.helper.ColorHelper;
import net.vulkanmod.render.chunk.build.frapi.render.AbstractBlockRenderContext;
import net.vulkanmod.render.chunk.build.renderer.BlockRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies Iris's separateAo where VulkanMod multiplies the light pipeline's brightness
 * (ambient occlusion times face shade) into a block quad's colour. Chunk meshes
 * (BlockRenderer) keep the tint in RGB and carry the brightness in alpha, as Iris writes
 * Sodium's terrain vertices. Blocks drawn outside chunk meshes (BlockRenderContext: falling
 * blocks, moving pistons) drop it, as Iris's MixinBufferBuilder_SeparateAo passes 1.0 for
 * every vanilla block quad.
 */
@Mixin(value = AbstractBlockRenderContext.class, remap = false)
public abstract class ChimeraBlockSeparateAoMixin {
    @Redirect(method = "shadeQuad", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/render/chunk/build/frapi/helper/ColorHelper;multiplyRGB(IF)I"))
    private int chimera$separateAo(int color, float brightness) {
        if (!ChimeraTerrainPipelines.separateAoActive()) {
            return ColorHelper.multiplyRGB(color, brightness);
        }
        return (Object) this instanceof BlockRenderer ? SeparateAoColor.argb(color, brightness) : color;
    }
}
