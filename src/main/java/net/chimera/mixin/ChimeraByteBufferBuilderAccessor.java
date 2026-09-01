package net.chimera.mixin;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the moving base of a BufferBuilder arena when polygon data is finalized. */
@Mixin(ByteBufferBuilder.class)
public interface ChimeraByteBufferBuilderAccessor {
    @Accessor("pointer")
    long chimera$pointer();
}
