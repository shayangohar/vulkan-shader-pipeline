package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.TerrainLayers;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chimera's frame around VulkanMod's section layers.
 *
 * <p>Every layer call runs in its Iris rendering phase. Only the layers VulkanMod draws
 * ({@link TerrainLayers}) open Chimera's windows:</p>
 * <ul>
 *   <li>The shadow segment renders at the head of the first drawn layer, before any terrain,
 *   as Iris renders shadows. openLevelSegment (at the world clear) armed it, and cullTerrain has
 *   updated the section graph by then. The shadow phase draws its own light-volume sections
 *   (ShadowSectionQueue), never the camera's culled queues. consumeShadowPending() clears the
 *   flag first, so the layer calls the shadow pass makes do not recurse.</li>
 *   <li>Depth is captured before translucent terrain, ahead of the pack window, whose guarded
 *   attachments must not intercept that fullscreen pass.</li>
 *   <li>The pack coverage window spans the layer's draws.</li>
 *   <li>Every layer opens on the main target (ChimeraMainPass.rebindMainTarget), which opens the
 *   shadow pass or a pending pack geometry window. Upstream VulkanMod's renderSectionLayer did this
 *   itself; Reforged's draws into whatever is bound.</li>
 * </ul>
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

    @WrapMethod(method = "renderSectionLayer")
    private void chimera$terrainLayer(RenderType renderType, double camX, double camY, double camZ,
                                      Matrix4f view, Matrix4f projection, Operation<Void> original) {
        TerrainRenderType type = ChimeraRenderer.segmentsActive() ? TerrainRenderType.get(renderType) : null;
        if (type == null) {
            original.call(renderType, camX, camY, camZ, view, projection);
            return;
        }
        var previous = PackUniformProvider.setRenderingPhase(TerrainLayers.phase(type));
        try {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass == null || !TerrainLayers.drawn(type)) {
                // Reforged binds the pipeline of a layer it merged away too.
                if (pass != null) pass.rebindMainTarget();
                original.call(renderType, camX, camY, camZ, view, projection);
                return;
            }
            if (type == TerrainLayers.OPAQUE && pass.consumeShadowPending()) {
                pass.renderShadowSegment(camX, camY, camZ);
            }
            if (type == TerrainRenderType.TRANSLUCENT) {
                pass.captureOpaqueDepthBeforeTranslucent();
            }
            pass.beginPackCoverageWindow(type);
            try {
                pass.rebindMainTarget();
                original.call(renderType, camX, camY, camZ, view, projection);
            } finally {
                pass.endPackCoverageWindow(type);
            }
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

    /**
     * Keeps back faces culled, as upstream VulkanMod and vanilla do for every layer. Reforged
     * draws translucent terrain unculled, so a water surface and its reversed copy both passed the
     * depth test and the reversed one, lit from below, overwrote it; every far face of glass drew.
     */
    @WrapWithCondition(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;disableCull()V"), require = 1)
    private boolean chimera$keepBackFacesCulled() {
        return !ChimeraRenderer.segmentsActive();
    }

    @Inject(method = "renderSectionLayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/texture/VTextureSelector;"
                            + "bindShaderTextures(Lnet/vulkanmod/vulkan/shader/Pipeline;)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void chimera$bindTerrainImages(RenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (!ChimeraRenderer.segmentsActive()) return;
        TerrainRenderType type = TerrainRenderType.get(renderType);
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null && type != null && TerrainLayers.drawn(type)) pass.bindTerrainImagesAfterSelector(type);
    }
}
