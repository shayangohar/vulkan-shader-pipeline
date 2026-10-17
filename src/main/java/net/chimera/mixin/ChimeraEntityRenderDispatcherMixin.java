package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An entity's context for the family lanes while EntityRenderDispatcher.render draws it, as Iris
 * MixinEntityRenderDispatcher captures it: everything it writes, its held items, fire and name
 * tag included, batches in the entity lanes with its entity.properties id.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ChimeraEntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private <E extends Entity> void chimera$beginEntity(E entity, double x, double y, double z, float yaw,
                                                        float partialTick, PoseStack poses,
                                                        MultiBufferSource buffers, int light, CallbackInfo ci) {
        ChimeraEntityBridge.beginEntity(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private <E extends Entity> void chimera$endEntity(E entity, double x, double y, double z, float yaw,
                                                      float partialTick, PoseStack poses,
                                                      MultiBufferSource buffers, int light, CallbackInfo ci) {
        ChimeraEntityBridge.endContext();
    }

    /** Iris gives burning-entity fire the pack's entity_flame id. */
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderFlame("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/world/entity/Entity;Lorg/joml/Quaternionf;)V"), require = 1)
    private void chimera$flameId(EntityRenderDispatcher dispatcher, PoseStack poses, MultiBufferSource buffers,
                                 Entity entity, Quaternionf rotation, Operation<Void> original) {
        int previous = ChimeraEntityBridge.replaceEntityId(ChimeraEntityBridge.flameEntityId());
        try {
            original.call(dispatcher, poses, buffers, entity, rotation);
        } finally {
            ChimeraEntityBridge.replaceEntityId(previous);
        }
    }

    /**
     * Iris MixinEntityRenderDispatcher: a pack that renders its own shadow map replaces the
     * vanilla ground-shadow decal, so the decal is not drawn. Without a pack shadow pass it stays.
     */
    @WrapWithCondition(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow("
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V"),
            require = 1)
    private boolean chimera$vanillaEntityShadow(PoseStack poses, MultiBufferSource buffers, Entity entity,
                                                float weight, float partialTick, LevelReader level, float size) {
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        return !(ChimeraRenderer.segmentsActive() && pass != null && pass.packShadowPassActive());
    }
}
