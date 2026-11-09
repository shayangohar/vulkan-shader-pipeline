package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/**
 * Tells the particle lanes which program a ParticleEngine.render call draws its sheets with. Iris's
 * NeoForge MixinParticleEngine sets one shader per call, from the sheets the call's filter accepts
 * (ChimeraEntityBridge.beginParticleCall); the filter is read here, as the call receives it, so
 * the Fabulous call and other mods' calls follow the same rule.
 */
@Mixin(ParticleEngine.class)
public abstract class ChimeraParticleEngineMixin {
    @Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("HEAD"), require = 1)
    private void chimera$particleCall(LightTexture lightTexture, Camera camera, float partialTick, Frustum frustum,
                                      Predicate<ParticleRenderType> sheets, CallbackInfo callback) {
        ChimeraEntityBridge.beginParticleCall(sheets);
    }
}
