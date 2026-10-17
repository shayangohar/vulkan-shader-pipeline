package net.chimera.render.vertex;

import net.chimera.mixin.ChimeraTerrainBufferBuilderAccessor;
import net.chimera.shaderpack.PackMaterialResolver;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.vertex.CustomVertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;

/** Terrain builder that writes the current block and fluid material identity. */
public final class ChimeraExtTerrainBuilder extends TerrainBufferBuilder {
    private final PackMaterialResolver materialResolver;
    private final ChimeraExtVertexBuilder extendedVertexBuilder;

    /**
     * The host builder accepts only its own formats, so it starts on COMPRESSED_TERRAIN, whose
     * prefix the extended formats share, and then takes the plan's format and vertex writer.
     */
    public ChimeraExtTerrainBuilder(int size, PackMaterialResolver materialResolver,
                                    TerrainMaterialPlan materialPlan) {
        super(size, CustomVertexFormat.COMPRESSED_TERRAIN);
        TerrainMaterialPlan plan = materialPlan == null ? TerrainMaterialPlan.legacy() : materialPlan;
        ((ChimeraTerrainBufferBuilderAccessor) (Object) this).chimera$setFormat(ChimeraVertexFormats.terrainFormat(plan));
        this.materialResolver = materialResolver == null ? PackMaterialResolver.empty() : materialResolver;
        this.extendedVertexBuilder = new ChimeraExtVertexBuilder(plan);
        this.vertexBuilder = this.extendedVertexBuilder;
    }

    /**
     * The position within the section of the block the host renders next, as Iris's
     * BlockContextHolder records it. ChimeraBlockRendererMixin calls this after the host's
     * setBlockAttributes, which passes the state only.
     */
    public void beginBlock(int localX, int localY, int localZ) {
        extendedVertexBuilder.setBlockPosition(localX, localY, localZ);
    }

    @Override
    public void setBlockAttributes(BlockState blockState) {
        extendedVertexBuilder.setBlockData(materialResolver.resolve(blockState), -1,
                blockState.getLightEmission());
    }

    /**
     * A fluid's material is its own block, as in Iris: water inside seagrass
     * or a waterlogged stair is water, not the block that holds it. The light
     * emission is the holding block's, as Iris passes it.
     */
    public void setFluidBlockAttributes(BlockState blockState, FluidState fluidState,
                                        int localX, int localY, int localZ) {
        extendedVertexBuilder.setBlockData(materialResolver.resolve(fluidState.createLegacyBlock()), 1,
                blockState.getLightEmission());
        extendedVertexBuilder.setBlockPosition(localX, localY, localZ);
    }
}
