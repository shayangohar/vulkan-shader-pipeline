package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris MixinTheEndPortalRenderer: with a pack active, the end portal (and the end gateway, which
 * renders through it) is an entitySolid cube with a slowly scrolling portal texture, drawn by the
 * pack's block program, instead of vanilla's position-only end_portal shader.
 */
@Mixin(TheEndPortalRenderer.class)
public abstract class ChimeraEndPortalRendererMixin {
    @Unique private static final float RED = 0.075f;
    @Unique private static final float GREEN = 0.15f;
    @Unique private static final float BLUE = 0.2f;

    @Shadow protected abstract float getOffsetUp();
    @Shadow protected abstract float getOffsetDown();

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/TheEndPortalBlockEntity;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$packPortal(TheEndPortalBlockEntity portal, float partialTick, PoseStack poses,
                                    MultiBufferSource buffers, int light, int overlay, CallbackInfo callback) {
        if (!ChimeraRenderer.packProjectionActive()) return;
        callback.cancel();
        VertexConsumer consumer = buffers.getBuffer(RenderType.entitySolid(TheEndPortalRenderer.END_PORTAL_LOCATION));
        PoseStack.Pose pose = poses.last();
        // A 100-second period; the texture wraps rather than clamps.
        float progress = (PackUniformProvider.currentFrameTimeCounter() * 0.01f) % 1f;
        float top = getOffsetUp();
        float bottom = getOffsetDown();
        chimera$quad(portal, consumer, pose, Direction.UP, progress, light, overlay,
                0, top, 1, 1, top, 1, 1, top, 0, 0, top, 0);
        chimera$quad(portal, consumer, pose, Direction.DOWN, progress, light, overlay,
                0, bottom, 1, 0, bottom, 0, 1, bottom, 0, 1, bottom, 1);
        chimera$quad(portal, consumer, pose, Direction.NORTH, progress, light, overlay,
                0, top, 0, 1, top, 0, 1, bottom, 0, 0, bottom, 0);
        chimera$quad(portal, consumer, pose, Direction.WEST, progress, light, overlay,
                0, top, 1, 0, top, 0, 0, bottom, 0, 0, bottom, 1);
        chimera$quad(portal, consumer, pose, Direction.SOUTH, progress, light, overlay,
                0, top, 1, 0, bottom, 1, 1, bottom, 1, 1, top, 1);
        chimera$quad(portal, consumer, pose, Direction.EAST, progress, light, overlay,
                1, top, 1, 1, bottom, 1, 1, bottom, 0, 1, top, 0);
    }

    @Unique
    private static void chimera$quad(TheEndPortalBlockEntity portal, VertexConsumer consumer, PoseStack.Pose pose,
                                     Direction direction, float progress, int light, int overlay,
                                     float x1, float y1, float z1, float x2, float y2, float z2,
                                     float x3, float y3, float z3, float x4, float y4, float z4) {
        if (!portal.shouldRenderFace(direction)) return;
        float nx = direction.getStepX(), ny = direction.getStepY(), nz = direction.getStepZ();
        chimera$vertex(consumer, pose, x1, y1, z1, progress, progress, light, overlay, nx, ny, nz);
        chimera$vertex(consumer, pose, x2, y2, z2, progress, 0.2f + progress, light, overlay, nx, ny, nz);
        chimera$vertex(consumer, pose, x3, y3, z3, 0.2f + progress, 0.2f + progress, light, overlay, nx, ny, nz);
        chimera$vertex(consumer, pose, x4, y4, z4, 0.2f + progress, progress, light, overlay, nx, ny, nz);
    }

    @Unique
    private static void chimera$vertex(VertexConsumer consumer, PoseStack.Pose pose, float x, float y, float z,
                                       float u, float v, int light, int overlay, float nx, float ny, float nz) {
        consumer.addVertex(pose, x, y, z).setColor(RED, GREEN, BLUE, 1.0f).setUv(u, v)
                .setOverlay(overlay).setLight(light).setNormal(pose, nx, ny, nz);
    }
}
