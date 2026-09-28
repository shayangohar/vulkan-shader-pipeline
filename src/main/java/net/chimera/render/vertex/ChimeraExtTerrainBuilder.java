package net.chimera.render.vertex;

import net.chimera.shaderpack.PackMaterialResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.vertex.TerrainBuilder;

/** Terrain builder that writes the current block and fluid material identity. */
public final class ChimeraExtTerrainBuilder extends TerrainBuilder {
    private final PackMaterialResolver materialResolver;
    private final ChimeraExtVertexBuilder extendedVertexBuilder;

    public ChimeraExtTerrainBuilder(int size, PackMaterialResolver materialResolver) {
        this(size, materialResolver, net.chimera.shaderpack.TerrainMaterialPlan.legacy());
    }

    public ChimeraExtTerrainBuilder(
            int size,
            PackMaterialResolver materialResolver,
            net.chimera.shaderpack.TerrainMaterialPlan materialPlan
    ) {
        super(size, new ChimeraExtVertexBuilder(materialPlan));
        this.materialResolver = materialResolver == null ? PackMaterialResolver.empty() : materialResolver;
        this.extendedVertexBuilder = (ChimeraExtVertexBuilder) this.vertexBuilder;
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        extendedVertexBuilder.setMaterialData(materialResolver.resolve(blockState), -1);
    }

    /**
     * A fluid's material is its own block, as in Iris: water inside seagrass
     * or a waterlogged stair is water, not the block that holds it.
     */
    public void setFluidBlockAttributes(BlockState blockState, FluidState fluidState) {
        BlockState fluidBlock = fluidState == null ? blockState : fluidState.createLegacyBlock();
        extendedVertexBuilder.setMaterialData(materialResolver.resolve(fluidBlock), 1);
    }
}
