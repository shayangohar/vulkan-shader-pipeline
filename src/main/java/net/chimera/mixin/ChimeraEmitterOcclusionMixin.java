package net.chimera.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.LightEmission;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.EmitterOcclusion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.light.data.LightDataAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a light-emitting block darken its neighbours' ambient occlusion in the terrain Chimera
 * meshes for a pack, as vanilla and Sodium 0.8.13 under Iris do (EmitterOcclusion). Packs are tuned
 * against that: Mellow's recess beside a glowing block came out 1.85 times brighter under
 * VulkanMod's rule. VulkanMod's own meshes, with no pack, keep its rule.
 */
@Mixin(value = LightDataAccess.class, remap = false)
public abstract class ChimeraEmitterOcclusionMixin {
    @Shadow @Final private BlockPos.MutableBlockPos pos;
    @Shadow protected BlockAndTintGetter world;

    @Inject(method = "compute", at = @At("RETURN"), cancellable = true, require = 1)
    private void chimera$emitterOcclusion(int x, int y, int z, CallbackInfoReturnable<Integer> result,
                                          @Local BlockState state) {
        // The emission VulkanMod tests (LightDataAccess's lu).
        if (LightEmission.of(state) == 0 || !ChimeraTerrainPipelines.vanillaEmitterOcclusion()) return;
        result.setReturnValue(EmitterOcclusion.occluding(result.getReturnValueI(),
                state.getShadeBrightness(this.world, this.pos)));
    }
}
