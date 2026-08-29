package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Writes fluid material identity before the host fluid renderer emits vertices. */
@Mixin(value = FluidRenderer.class, remap = false)
public abstract class FluidRendererMixin {
    @Shadow
    private BuilderResources resources;

    @Inject(method = "renderLiquid", at = @At("HEAD"))
    private void chimera$setFluidMaterial(BlockState blockState, FluidState fluidState,
                                           BlockPos blockPos, CallbackInfo ci) {
        if (!ChimeraTerrainPipelines.isExtendedMode()) {
            return;
        }
        TerrainRenderType renderType = TerrainRenderType.get(ItemBlockRenderTypes.getRenderLayer(fluidState));
        renderType = TerrainRenderType.getRemapped(renderType);
        TerrainBuilder builder = resources.builderPack.builder(renderType);
        if (builder instanceof ChimeraExtTerrainBuilder extended) {
            extended.setFluidBlockAttributes(blockState, fluidState);
        }
    }
}
