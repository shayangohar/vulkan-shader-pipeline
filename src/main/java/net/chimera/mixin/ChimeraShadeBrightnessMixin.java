package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the pack's ambientOcclusionLevel where Iris does: every block's shade brightness, which
 * vanilla's and VulkanMod's ambient occlusion (LightDataAccess) both read. At level 1, the value
 * without a pack, the brightness is unchanged.
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class, priority = 990)
public abstract class ChimeraShadeBrightnessMixin {
    @Inject(method = "getShadeBrightness", at = @At("RETURN"), cancellable = true)
    private void chimera$ambientOcclusionLevel(BlockGetter level, BlockPos position,
                                              CallbackInfoReturnable<Float> result) {
        result.setReturnValue(ChimeraTerrainPipelines.shadeBrightness(result.getReturnValueF()));
    }
}
