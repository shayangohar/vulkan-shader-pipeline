package net.chimera.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The shader, transparency and depth-test shards of a render type's composite state. */
@Mixin(RenderType.CompositeState.class)
public interface ChimeraCompositeStateAccessor {
    @Accessor("shaderState")
    RenderStateShard.ShaderStateShard chimera$shaderState();

    @Accessor("transparencyState")
    RenderStateShard.TransparencyStateShard chimera$transparencyState();

    @Accessor("depthTestState")
    RenderStateShard.DepthTestStateShard chimera$depthTestState();
}
