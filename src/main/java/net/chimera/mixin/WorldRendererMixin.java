package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Positions chimera's shadow segment deterministically inside the frame:
 * it renders at the RETURN of VulkanMod's effective opaque section layer
 * (TerrainRenderType.getRemapped(SOLID) = CUTOUT under uniqueOpaqueLayer,
 * else SOLID; renderSectionLayer is pinned decompile line 282 - void,
 * single exit).
 *
 * Ordering contract: openLevelSegment (HEAD of LevelRenderer.renderLevel)
 * opens HDR and arms shadowPending; by the time the SOLID layer returns,
 * cullTerrain has filled the section draw queues and the SOLID pass itself
 * has just drawn 1:1 from them, so the shadow phase sees exactly the state
 * the solid pass saw. The previous hook point (right after
 * compileSections) raced section-graph updates against queue filling and
 * recorded zero draws.
 *
 * No recursion: consumeShadowPending() clears the flag before
 * renderShadowSegment runs, so the nested renderSectionLayer(SOLID) call
 * inside the shadow pass hits this handler with the flag already down.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void chimera$renderShadowAfterSolid(TerrainRenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (renderType != TerrainRenderType.getRemapped(TerrainRenderType.SOLID)) return;
        if (!ChimeraRenderer.segmentsActive()) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null && pass.consumeShadowPending()) {
            pass.renderShadowSegment(camX, camY, camZ);
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void chimera$captureOpaqueDepthBeforeTranslucent(TerrainRenderType renderType,
            double camX, double camY, double camZ, Matrix4f modelView, Matrix4f projection,
            CallbackInfo ci) {
        if (renderType != TerrainRenderType.TRANSLUCENT || !ChimeraRenderer.segmentsActive()) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null) pass.captureOpaqueDepthBeforeTranslucent();
    }
}
