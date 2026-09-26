package net.chimera.mixin;

import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Lifts the vanilla entity ground-shadow decal off its receiver.
 *
 * <p>ShadowFeatureRenderer builds the shadow.png quad at exactly the
 * receiving voxel shape's minimum Y: 1.21.11 bytecode computes
 * {@code relativeY + bounds.minY} with no epsilon, matching 1.21.1
 * EntityRenderDispatcher.renderShadowPart, which likewise emits the
 * shape minimum with no lift. The ENTITY_SHADOW pipeline (translucent,
 * no depth write, no depth bias) therefore depth-fights the receiver as
 * the camera angle changes. This adds separation to the decal Y only;
 * X, Z, UVs, alpha, and depth state are untouched, and the oval itself
 * is preserved as the fallback until entity casters exist.</p>
 */
@Mixin(ShadowFeatureRenderer.class)
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
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/feature/ShadowFeatureRenderer;shadowVertex(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexConsumer;IFFFFF)V"),
            index = 4,
            require = 4)
    private float chimera$liftShadowDecal(float y) {
        return y + GROUND_SHADOW_LIFT;
    }
}
