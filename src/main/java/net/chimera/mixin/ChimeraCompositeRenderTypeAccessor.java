package net.chimera.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A composite render type's state, whose shader the family lanes route by (ChimeraEntityBridge). */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface ChimeraCompositeRenderTypeAccessor {
    @Accessor("state")
    RenderType.CompositeState chimera$state();
}
