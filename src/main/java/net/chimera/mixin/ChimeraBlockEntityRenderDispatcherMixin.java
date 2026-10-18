package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block entity's context for the family lanes while it renders in the world. Like Iris
 * MixinBlockEntityRenderDispatcher, its block state's block.properties id is blockEntityId for
 * everything it draws.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ChimeraBlockEntityRenderDispatcherMixin {
    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private <E extends BlockEntity> void chimera$beginBlockEntity(E blockEntity, float partialTick, PoseStack poses,
                                                                  MultiBufferSource buffers, CallbackInfo ci) {
        ChimeraEntityBridge.beginBlockEntity(ChimeraTerrainPipelines.blockStateId(blockEntity.getBlockState()));
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private <E extends BlockEntity> void chimera$endBlockEntity(E blockEntity, float partialTick, PoseStack poses,
                                                                MultiBufferSource buffers, CallbackInfo ci) {
        ChimeraEntityBridge.endContext();
    }
}
