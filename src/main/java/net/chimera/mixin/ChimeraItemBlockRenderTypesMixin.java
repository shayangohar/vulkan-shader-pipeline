package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the pack's block.properties {@code layer.*} lines, as Iris does on NeoForge: Complementary
 * and Bliss draw glass, panes and beacons in the translucent layer, through the water program. Chunk
 * building asks each block model for its render types, and a model without its own reads
 * {@code getRenderLayers}; a block item's fallback render type reads the same, and the older
 * {@code getChunkRenderType} serves the deprecated block and item paths. Moving blocks keep the
 * host's type, as in Iris.
 */
@Mixin(ItemBlockRenderTypes.class)
public abstract class ChimeraItemBlockRenderTypesMixin {
    @Inject(method = "getRenderLayers", at = @At("HEAD"), cancellable = true)
    private static void chimera$packRenderLayers(BlockState state, CallbackInfoReturnable<ChunkRenderTypeSet> cir) {
        RenderType renderType = ChimeraTerrainPipelines.packRenderType(state);
        if (renderType != null) {
            cir.setReturnValue(ChunkRenderTypeSet.of(renderType));
        }
    }

    @Inject(method = "getChunkRenderType", at = @At("HEAD"), cancellable = true)
    private static void chimera$packChunkRenderType(BlockState state, CallbackInfoReturnable<RenderType> cir) {
        RenderType renderType = ChimeraTerrainPipelines.packRenderType(state);
        if (renderType != null) {
            cir.setReturnValue(renderType);
        }
    }
}
