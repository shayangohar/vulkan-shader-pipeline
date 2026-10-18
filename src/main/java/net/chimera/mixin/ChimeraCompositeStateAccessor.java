package net.chimera.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The shader shard of a render type's composite state. */
@Mixin(RenderType.CompositeState.class)
public interface ChimeraCompositeStateAccessor {
    @Accessor("shaderState")
    RenderStateShard.ShaderStateShard chimera$shaderState();
}
