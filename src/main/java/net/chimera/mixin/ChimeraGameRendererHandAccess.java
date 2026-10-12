package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GameRenderer.class)
public interface ChimeraGameRendererHandAccess {
    @Invoker("getFov") float chimera$getFov(Camera camera, float tick, boolean changingFov);
    @Invoker("bobHurt") void chimera$bobHurt(PoseStack poses, float tick);
    @Invoker("bobView") void chimera$bobView(PoseStack poses, float tick);
}
