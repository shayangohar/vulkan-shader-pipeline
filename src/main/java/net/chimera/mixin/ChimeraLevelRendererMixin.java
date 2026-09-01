package net.chimera.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Chimera's world/output split inside LevelRenderer.renderLevel:
 *
 * - HEAD opens the internal RGBA16F world target.
 * - The SOLID layer tail records the shadow map and resumes that target.
 * - RETURN resolves the finished HDR world into the stable output target used
 *   by hand, GUI, and VulkanMod's post chain.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$openHdrSegment(GraphicsResourceAllocator graphicsResourceAllocator,
                                        DeltaTracker deltaTracker,
                                        boolean bl,
                                        Camera camera,
                                        Matrix4f modelView,
                                        Matrix4f projection,
                                        Matrix4f matrix4f,
                                        GpuBufferSlice gpuBufferSlice,
                                        Vector4f vector4f,
                                        boolean bl2,
                                        CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraEntityBridge.beginWorldSubmissionWindow();
            PackUniformProvider.beginFrame(camera,
                    deltaTracker.getGameTimeDeltaPartialTick(false), modelView, projection);
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.openLevelSegment();
            }
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void chimera$finishHdrSegment(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.finishLevelSegment();
            }
            ChimeraEntityBridge.endWorldSubmissionWindow();
        }
    }
}
