package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.vulkanmod.render.vertex.TerrainBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Sets a Chimera terrain builder's vertex format. VulkanMod's constructor accepts only its own
 * formats; the builder sizes, sorts and hands over its vertices by this one (ChimeraExtTerrainBuilder).
 */
@Mixin(value = TerrainBufferBuilder.class, remap = false)
public interface ChimeraTerrainBufferBuilderAccessor {
    @Mutable
    @Accessor("format")
    void chimera$setFormat(VertexFormat format);
}
