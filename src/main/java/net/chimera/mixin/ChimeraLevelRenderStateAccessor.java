package net.chimera.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the frame's extracted level state; the shadow casters reuse its camera. */
@Mixin(LevelRenderer.class)
public interface ChimeraLevelRenderStateAccessor {
    @Accessor("levelRenderState")
    LevelRenderState chimera$levelRenderState();
}
