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
        super(size, new ChimeraExtVertexBuilder());
        this.materialResolver = materialResolver == null ? PackMaterialResolver.empty() : materialResolver;
        this.extendedVertexBuilder = (ChimeraExtVertexBuilder) this.vertexBuilder;
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        extendedVertexBuilder.setMaterialData(materialResolver.resolve(blockState), -1);
    }

    public void setFluidBlockAttributes(BlockState blockState, FluidState fluidState) {
        extendedVertexBuilder.setMaterialData(materialResolver.resolve(blockState), 1);
    }
}
