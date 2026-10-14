package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.ChimeraTextureBindingState;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives Chimera's world/output split inside LevelRenderer.renderLevel:
 *
 * - HEAD opens the internal RGBA16F world target.
 * - The opaque layer's head records the shadow map and resumes that target.
 * - RETURN resolves the finished HDR world into the stable output target used
 *   by hand, GUI, and VulkanMod's post chain.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {
    @Shadow
    @Final
    private GpuSampler chunkLayerSampler;

    @Unique
    private boolean chimera$particleDrawActive;

    @Inject(method = "method_62214", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V", ordinal = 1),
                    require = 1)
    private void chimera$solidHandBeforeDeferred(CallbackInfo callback) {
        ChimeraHandRenderer.drawSolid();
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;"
                            + "DDD)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"),
            require = 1)
    private void chimera$captureChunkSampler(
            GpuBufferSlice gpuBufferSlice,
            LevelRenderState levelRenderState,
            ProfilerFiller profilerFiller,
            Matrix4f matrix4f,
            ResourceHandle<?> resourceHandle,
            ResourceHandle<?> resourceHandle2,
            boolean bl,
            ResourceHandle<?> resourceHandle3,
            ResourceHandle<?> resourceHandle4,
            CallbackInfo ci
    ) {
        ChimeraTextureBindingState.captureChunkSampler(this.chunkLayerSampler);
    }

    /**
     * The main pass's world clear (fog colour, depth 1). While a pack owns colortex0 only the
     * depth clears: Iris's colortex0 clear is the pack's own (ChimeraMainPass.clearWorldTarget).
     */
    @WrapOperation(method = "method_62218", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearColorAndDepthTextures("
                    + "Lcom/mojang/blaze3d/textures/GpuTexture;ILcom/mojang/blaze3d/textures/GpuTexture;D)V"),
            require = 1)
    private void chimera$keepPackColortex0(CommandEncoder encoder, GpuTexture color, int clearColor,
                                           GpuTexture depth, double clearDepth, Operation<Void> original) {
        ChimeraMainPass pass = ChimeraRenderer.segmentsActive() ? ChimeraRenderer.getMainPass() : null;
        if (pass == null || !pass.packOwnsColortex0()) {
            original.call(encoder, color, clearColor, depth, clearDepth);
            return;
        }
        // VkCommandEncoder's main-target path, depth only.
        Renderer.getInstance().getMainPass().rebindMainTarget();
        VRenderSystem.clearDepth(clearDepth);
        Renderer.clearAttachments(GL11.GL_DEPTH_BUFFER_BIT);
    }

    @Inject(method = "method_62213", at = @At("HEAD"), require = 1)
    private void chimera$beginParticlePass(
            GpuBufferSlice fog,
            ResourceHandle<?> particles,
            ResourceHandle<?> main,
            CallbackInfo callback
    ) {
        if (ChimeraRenderer.segmentsActive()) {
            chimera$particleDrawActive = ChimeraEntityBridge.beginParticleDraw();
        }
    }

    @Inject(method = "method_62213", at = @At("RETURN"), require = 1)
    private void chimera$endParticlePass(
            GpuBufferSlice fog,
            ResourceHandle<?> particles,
            ResourceHandle<?> main,
            CallbackInfo callback
    ) {
        if (chimera$particleDrawActive && ChimeraEntityBridge.isParticleDrawActive()) {
            ChimeraEntityBridge.endDraw();
        }
        chimera$particleDrawActive = false;
    }

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
        ChimeraHandRenderer.beginFrame();
        if (ChimeraRenderer.beginLevelSegments()) {
            ChimeraEntityBridge.beginWorldSubmissionWindow();
            PackUniformProvider.beginFrame(camera,
                    deltaTracker.getGameTimeDeltaPartialTick(false), modelView, projection);
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                var position = camera.position();
                pass.openLevelSegment(position.x, position.y, position.z,
                        new float[] {vector4f.x(), vector4f.y(), vector4f.z()});
            }
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void chimera$finishHdrSegment(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                ChimeraHandRenderer.drawTranslucent();
                pass.finishLevelSegment();
            }
            ChimeraEntityBridge.endWorldSubmissionWindow();
        }
    }
}
