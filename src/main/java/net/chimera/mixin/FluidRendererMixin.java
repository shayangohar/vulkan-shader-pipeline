package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.chimera.render.vertex.SeparateAoColor;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.renderer.FluidRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainBuilder;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Writes fluid material identity before the host fluid renderer emits vertices, and applies
 * Iris's separateAo to the fluid's vertex colours.
 */
@Mixin(value = FluidRenderer.class, remap = false)
public abstract class FluidRendererMixin {
    @Shadow
    private BuilderResources resources;

    @Shadow
    @Final
    private int[] quadColors;

    /**
     * Iris separateAo: the fluid's tint stays in RGB and the light pipeline's brightness times
     * the face shade moves to alpha, as Sodium's fluid renderer hands its vertices to Iris.
     * VulkanMod multiplied both into RGB.
     */
    @Inject(method = "updateColor", at = @At("TAIL"))
    private void chimera$separateAo(float red, float green, float blue, float brightness, CallbackInfo ci) {
        if (!ChimeraTerrainPipelines.separateAoActive()) {
            return;
        }
        float[] lightBrightness = resources.quadLightData.br;
        for (int index = 0; index < 4; index++) {
            quadColors[index] = SeparateAoColor.rgba(red, green, blue, lightBrightness[index] * brightness);
        }
    }

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
            // Fluid vertices are section-local, as VulkanMod's fluid renderer places them.
            extended.setFluidBlockAttributes(blockState, fluidState,
                    blockPos.getX() & 15, blockPos.getY() & 15, blockPos.getZ() & 15);
        }
    }
}
