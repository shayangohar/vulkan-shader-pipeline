package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris MixinTheEndPortalRenderer: with a pack active, the end portal is an
 * entitySolid cube with a slowly scrolling portal texture, drawn by the pack's
 * block program, instead of vanilla's position-only end_portal shader.
 */
@Mixin(AbstractEndPortalRenderer.class)
public abstract class ChimeraEndPortalRendererMixin {
    @Unique private static final float RED = 0.075f;
    @Unique private static final float GREEN = 0.15f;
    @Unique private static final float BLUE = 0.2f;

    @Shadow protected abstract float getOffsetUp();
    @Shadow protected abstract float getOffsetDown();

    @Inject(method = "renderType", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$renderType(CallbackInfoReturnable<RenderType> callback) {
        if (ChimeraRenderer.packProjectionActive()) {
            callback.setReturnValue(RenderTypes.entitySolid(AbstractEndPortalRenderer.END_PORTAL_LOCATION));
        }
    }

    @Inject(method = "method_73539", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$cube(EndPortalRenderState state, PoseStack.Pose pose, VertexConsumer consumer,
                              CallbackInfo callback) {
        if (!ChimeraRenderer.packProjectionActive()) return;
        callback.cancel();
        // A 100-second period; the texture wraps rather than clamps.
        float progress = (PackUniformProvider.currentFrameTimeCounter() * 0.01f) % 1f;
        float top = getOffsetUp();
        float bottom = getOffsetDown();
        chimera$quad(state, consumer, pose, Direction.UP, progress,
                0, top, 1, 1, top, 1, 1, top, 0, 0, top, 0);
        chimera$quad(state, consumer, pose, Direction.DOWN, progress,
                0, bottom, 1, 0, bottom, 0, 1, bottom, 0, 1, bottom, 1);
        chimera$quad(state, consumer, pose, Direction.NORTH, progress,
                0, top, 0, 1, top, 0, 1, bottom, 0, 0, bottom, 0);
        chimera$quad(state, consumer, pose, Direction.WEST, progress,
                0, top, 1, 0, top, 0, 0, bottom, 0, 0, bottom, 1);
        chimera$quad(state, consumer, pose, Direction.SOUTH, progress,
                0, top, 1, 0, bottom, 1, 1, bottom, 1, 1, top, 1);
        chimera$quad(state, consumer, pose, Direction.EAST, progress,
                1, top, 1, 1, bottom, 1, 1, bottom, 0, 1, top, 0);
    }

    @Unique
    private static void chimera$quad(EndPortalRenderState state, VertexConsumer consumer, PoseStack.Pose pose,
                                     Direction direction, float progress,
                                     float x1, float y1, float z1, float x2, float y2, float z2,
                                     float x3, float y3, float z3, float x4, float y4, float z4) {
        if (!state.facesToShow.contains(direction)) return;
        float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
        chimera$vertex(consumer, pose, x1, y1, z1, progress, progress, nx, ny, nz);
        chimera$vertex(consumer, pose, x2, y2, z2, progress, 0.2f + progress, nx, ny, nz);
        chimera$vertex(consumer, pose, x3, y3, z3, 0.2f + progress, 0.2f + progress, nx, ny, nz);
        chimera$vertex(consumer, pose, x4, y4, z4, 0.2f + progress, progress, nx, ny, nz);
    }

    @Unique
    private static void chimera$vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                                       float u, float v, float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z).setColor(RED, GREEN, BLUE, 1.0f).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT)
                .setNormal(pose, nx, ny, nz);
    }
}
