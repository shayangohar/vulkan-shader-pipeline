package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the level's block entities in vanilla's order: the visible sections' first, then the
 * global ones, which render off-screen (beacons, end gateways). Sodium under Iris keeps that order.
 * VulkanMod Reforged renders the sections' block entities itself, just before the block-entity
 * stage's closing checkPoseStack, so after vanilla's global loop; this holds each global render and
 * runs it after that checkPoseStack.
 *
 * <p>The order the level's batches are written in orders their draws: Iris draws a kind's unrelated
 * batches in reverse first-use order (IrisBatchOrder). With the beacon written first, its beam drew
 * after the chest behind it, and a pack that keeps block entities in a target of their own
 * (Escheridia's colortex15, layered over colortex0 in composite2) showed the chest over the beam.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraBlockEntityOrderMixin {
    private static final String RENDER_BLOCK_ENTITY = "Lnet/minecraft/client/renderer/blockentity/"
            + "BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V";
    private static final String CHECK_POSE_STACK =
            "Lnet/minecraft/client/renderer/LevelRenderer;checkPoseStack(Lcom/mojang/blaze3d/vertex/PoseStack;)V";

    /** This frame's global block-entity renders, held until the sections' block entities have rendered. */
    @Unique private final List<Runnable> chimera$globalRenders = new ArrayList<>();

    /** Vanilla's global loop, the second block-entity render in renderLevel: holds the render with its pose. */
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = RENDER_BLOCK_ENTITY, ordinal = 1),
            require = 1)
    private void chimera$holdGlobalRender(BlockEntityRenderDispatcher dispatcher, BlockEntity blockEntity,
                                          float partialTick, PoseStack poses, MultiBufferSource buffers,
                                          Operation<Void> original) {
        PoseStack held = new PoseStack();
        held.last().pose().set(poses.last().pose());
        held.last().normal().set(poses.last().normal());
        chimera$globalRenders.add(() -> original.call(dispatcher, blockEntity, partialTick, held, buffers));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = CHECK_POSE_STACK, ordinal = 1,
            shift = At.Shift.AFTER), require = 1)
    private void chimera$renderGlobalBlockEntities(CallbackInfo callback) {
        List<Runnable> held = List.copyOf(chimera$globalRenders);
        chimera$globalRenders.clear();
        held.forEach(Runnable::run);
    }
}
