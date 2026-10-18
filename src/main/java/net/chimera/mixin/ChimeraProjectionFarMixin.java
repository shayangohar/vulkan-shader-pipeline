package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Restores vanilla's finite far plane for the world projection while a shader
 * pack is loaded. VulkanMod makes GameRenderer.getDepthFar infinite; packs
 * then receive an infinite gbufferProjection, and every sky pixel (depth 1)
 * reconstructs a ray behind the camera, which removes pack clouds and skews
 * sky and fog. Iris gives packs vanilla's projection, and the depth buffer is
 * written with the same matrix, so raster and published uniforms stay equal.
 */
@Mixin(GameRenderer.class)
public abstract class ChimeraProjectionFarMixin {
    @Shadow private float renderDistance;

    /** Vanilla 1.21.1's getDepthFar: four times the render distance in blocks. */
    @ModifyArg(method = "getProjectionMatrix", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;", remap = false),
            index = 3, require = 1)
    private float chimera$vanillaDepthFar(float hostFar) {
        return ChimeraRenderer.packProjectionActive() ? this.renderDistance * 4.0F : hostFar;
    }
}
