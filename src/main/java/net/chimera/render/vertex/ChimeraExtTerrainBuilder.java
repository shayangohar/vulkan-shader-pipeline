package net.chimera.render.vertex;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Terrain builder for chimera's extended format. The host's meshing loop
 * (BlockRenderer.renderBlock) calls setBlockAttributes per block before quad
 * emission; we translate the block into its registry id, which shaders read
 * through the BlockId vertex attribute.
 */
public class ChimeraExtTerrainBuilder extends net.vulkanmod.render.vertex.TerrainBuilder {
    private final ChimeraExtVertexBuilder extBuilder;

    public ChimeraExtTerrainBuilder(int size) {
        super(size, new ChimeraExtVertexBuilder());
        this.extBuilder = (ChimeraExtVertexBuilder) this.vertexBuilder;
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        this.extBuilder.setBlockId(BuiltInRegistries.BLOCK.getId(blockState.getBlock()));
    }
}
