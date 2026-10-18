package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.vulkanmod.render.culling.EntityCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shadow casters are tested against the light's volume alone, as Iris skips Sodium's entity
 * culling while it renders shadows. VulkanMod's entity culling also requires an entity's section
 * to be visible from the camera this frame and the entity to be near the camera, so mobs behind
 * the player, around corners or far away would cast no shadow.
 */
@Mixin(value = EntityCulling.class, remap = false)
public abstract class ChimeraEntityCullingMixin {
    @Inject(method = "isVisible(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;"
            + "Lnet/minecraft/world/phys/AABB;DDD)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$shadowCasters(Entity entity, Frustum frustum, AABB box, double cameraX,
                                              double cameraY, double cameraZ,
                                              CallbackInfoReturnable<Boolean> callback) {
        if (ChimeraEntityBridge.isShadowCasterPass()) callback.setReturnValue(frustum.isVisible(box));
    }
}
