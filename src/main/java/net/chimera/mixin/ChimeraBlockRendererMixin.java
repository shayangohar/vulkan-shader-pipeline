package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.vertex.ChimeraExtTerrainBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.renderer.BlockRenderer;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the terrain builders the position of the block about to be rendered, as Iris's
 * block context does, so at_midBlock points from each vertex to its own block's centre.
 * The block's quads can go to any layer's builder, so every extended builder learns it.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class ChimeraBlockRendererMixin {
    @Unique
    private static final TerrainRenderType[] CHIMERA$RENDER_TYPES = TerrainRenderType.values();

    @Shadow
    private BuilderResources resources;

    @Inject(method = "renderBlock", at = @At("HEAD"))
    private void chimera$beginBlock(BlockState blockState, BlockPos blockPos, Vector3f pos, CallbackInfo ci) {
        if (!ChimeraTerrainPipelines.isExtendedMode()) {
            return;
        }
        // VulkanMod writes terrain vertices relative to the section, as Iris's local positions.
        int localX = blockPos.getX() & 15;
        int localY = blockPos.getY() & 15;
        int localZ = blockPos.getZ() & 15;
        for (TerrainRenderType renderType : CHIMERA$RENDER_TYPES) {
            if (resources.builderPack.builder(renderType) instanceof ChimeraExtTerrainBuilder extended) {
                extended.beginBlock(localX, localY, localZ);
            }
        }
    }
}
