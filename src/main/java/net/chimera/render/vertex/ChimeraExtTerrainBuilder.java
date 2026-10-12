package net.chimera.render.vertex;

import net.chimera.shaderpack.PackMaterialResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.vertex.TerrainBuilder;

/** Terrain builder that writes the current block and fluid material identity. */
public final class ChimeraExtTerrainBuilder extends TerrainBuilder {
    private final PackMaterialResolver materialResolver;
    private final ChimeraExtVertexBuilder extendedVertexBuilder;
    private int blockX;
    private int blockY;
    private int blockZ;

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

    /**
     * The position within the section of the block the host renders next, as Iris's
     * BlockContextHolder records it. ChimeraBlockRendererMixin calls this before the host's
     * setBlockAttributes, which passes the state only.
     */
    public void beginBlock(int localX, int localY, int localZ) {
        this.blockX = localX;
        this.blockY = localY;
        this.blockZ = localZ;
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        extendedVertexBuilder.setBlockData(materialResolver.resolve(blockState), -1,
                blockState.getLightEmission(), blockX, blockY, blockZ);
    }

    /**
     * A fluid's material is its own block, as in Iris: water inside seagrass
     * or a waterlogged stair is water, not the block that holds it. The light
     * emission is the holding block's, as Iris passes it.
     */
    public void setFluidBlockAttributes(BlockState blockState, FluidState fluidState,
                                        int localX, int localY, int localZ) {
        BlockState fluidBlock = fluidState == null ? blockState : fluidState.createLegacyBlock();
        extendedVertexBuilder.setBlockData(materialResolver.resolve(fluidBlock), 1,
                blockState.getLightEmission(), localX, localY, localZ);
    }
}
