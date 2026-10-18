package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Stores the shadow pass's projections unchanged, as upstream VulkanMod stored every projection.
 * Reforged maps each projection's depth from GL's [-1,1] onto Vulkan's [0,1], but the shadow
 * programs map their legacy clip depth themselves and the fixed shadow projection is already
 * [0,1]: mapped twice, every shadow depth landed about 78 blocks behind its caster. The shadow
 * draws (Chimera's, Reforged's renderSectionLayer, RenderSystem.setProjectionMatrix for casters)
 * all apply their projection here; vanilla's own copy of the matrix is untouched.
 */
@Mixin(value = VRenderSystem.class, remap = false)
public abstract class ChimeraShadowProjectionMixin {
    @Shadow
    @Final
    private static FloatBuffer projectionFloatView;

    @Inject(method = "applyProjectionMatrix", at = @At("HEAD"), cancellable = true, require = 1)
    private static void chimera$keepShadowProjection(Matrix4f projection, CallbackInfo callback) {
        if (!ChimeraRenderer.shadowPassActive()) return;
        projection.get(projectionFloatView);
        callback.cancel();
    }
}
