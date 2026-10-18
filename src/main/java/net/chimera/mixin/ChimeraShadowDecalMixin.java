package net.chimera.mixin;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lifts the vanilla entity ground-shadow decal off its receiver.
 *
 * <p>EntityRenderDispatcher.renderBlockShadow builds the shadow.png quad at
 * exactly the receiving voxel shape's minimum Y, with no lift. The entity
 * shadow render type (translucent, no depth write, no depth bias) therefore
 * depth-fights the receiver as the camera angle changes. This adds
 * separation to the decal Y only; X, Z, UVs, alpha, and depth state are
 * untouched, and the oval itself is preserved as the fallback until entity
 * casters exist.</p>
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class ChimeraShadowDecalMixin {
    /**
     * 1/64 block of world-space separation. Large enough to beat D24
     * depth quantization at gameplay distances, small enough to stay
     * invisible and far below slab/stair geometry so the decal cannot
     * bleed through adjacent receivers. Exactly representable in
     * float32, so integer receiver heights stay exact after the lift.
     */
    private static final float GROUND_SHADOW_LIFT = 1.0F / 64.0F;

    // The quad has exactly four shadowVertex call sites; require = 4
    // fails loudly if Mojang changes the vertex count or signature.
    @ModifyArg(
            method = "renderBlockShadow",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;"
                            + "shadowVertex(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFFF)V"),
            index = 4,
            require = 4)
    private static float chimera$liftShadowDecal(float y) {
        return y + GROUND_SHADOW_LIFT;
    }
}
