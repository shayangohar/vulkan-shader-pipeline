package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Tells the particle lanes which render type ParticleEngine draws next: Iris splits particles by translucency. */
@Mixin(ParticleEngine.class)
public abstract class ChimeraParticleEngineMixin {
    @WrapOperation(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F"
            + "Lnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleRenderType;"
                    + "begin(Lcom/mojang/blaze3d/vertex/Tesselator;Lnet/minecraft/client/renderer/texture/TextureManager;)"
                    + "Lcom/mojang/blaze3d/vertex/BufferBuilder;"), require = 1)
    private BufferBuilder chimera$particleType(ParticleRenderType type, Tesselator tesselator, TextureManager textures,
                                               Operation<BufferBuilder> original) {
        ChimeraEntityBridge.setParticleTranslucent(type.isTranslucent());
        return original.call(type, tesselator, textures);
    }
}
