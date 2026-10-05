package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.vertex.TerrainRenderType;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.PackRenderingPhase;
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
 * cullTerrain has updated the section graph and the SOLID pass has drawn.
 * The shadow phase then draws its own light-volume sections
 * (ShadowSectionQueue), never the camera's culled queues. The previous hook
 * point (right after compileSections) raced section-graph updates and
 * recorded zero draws.
 *
 * No recursion: consumeShadowPending() clears the flag before
 * renderShadowSegment runs, so the nested renderSectionLayer(SOLID) call
 * inside the shadow pass hits this handler with the flag already down.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class WorldRendererMixin {

    @WrapMethod(method = "renderSectionLayer")
    private void chimera$terrainPhase(TerrainRenderType type, double x, double y, double z,
                                     Matrix4f view, Matrix4f projection, Operation<Void> original) {
        if (!ChimeraRenderer.segmentsActive()) {
            original.call(type, x, y, z, view, projection);
            return;
        }
        var phase = switch (type) {
            case SOLID -> PackRenderingPhase.TERRAIN_SOLID;
            case CUTOUT -> PackRenderingPhase.TERRAIN_CUTOUT;
            case TRANSLUCENT -> PackRenderingPhase.TERRAIN_TRANSLUCENT;
            case TRIPWIRE -> PackRenderingPhase.TRIPWIRE;
        };
        var previous = PackUniformProvider.setRenderingPhase(phase);
        try {
            original.call(type, x, y, z, view, projection);
        } finally {
            PackUniformProvider.setRenderingPhase(previous);
        }
    }

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
    private void chimera$beginPackCoverage(TerrainRenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (!ChimeraRenderer.segmentsActive()) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass == null) return;
        // Capture depth before the pack geometry window can install its guarded
        // dynamic-rendering attachments. The depth conversion is an independent
        // fullscreen pass and must never be intercepted as terrain rendering.
        if (renderType == TerrainRenderType.TRANSLUCENT) {
            pass.captureOpaqueDepthBeforeTranslucent();
        }
        pass.beginPackCoverageWindow(renderType);
    }

    @Inject(method = "renderSectionLayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/vulkanmod/vulkan/texture/VTextureSelector;bindShaderTextures(Lnet/vulkanmod/vulkan/shader/Pipeline;)V",
                    shift = At.Shift.AFTER),
            require = 1)
    private void chimera$bindActualTerrainAtlas(TerrainRenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (!ChimeraRenderer.segmentsActive()) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null) pass.bindTerrainAtlasAfterSelector(renderType);
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void chimera$endPackCoverage(TerrainRenderType renderType, double camX, double camY,
            double camZ, Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        if (!ChimeraRenderer.segmentsActive()) return;
        ChimeraMainPass pass = ChimeraRenderer.getMainPass();
        if (pass != null) pass.endPackCoverageWindow(renderType);
    }

}
