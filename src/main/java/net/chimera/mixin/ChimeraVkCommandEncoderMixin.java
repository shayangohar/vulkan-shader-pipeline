package net.chimera.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.chimera.render.EntityTransformBinding;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraVkRenderPassAccess;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.render.engine.VkCommandEncoder;
import net.vulkanmod.render.engine.VkGpuBuffer;
import net.vulkanmod.render.engine.VkRenderPass;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Binds the pack entity pipeline while inheriting the host render state. */
@Mixin(value = VkCommandEncoder.class, remap = false)
public abstract class ChimeraVkCommandEncoderMixin {
    @Inject(method = "trySetup", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$bindEntityPipeline(
            VkRenderPass renderPass,
            CallbackInfoReturnable<Boolean> callback
    ) {
        RenderPipeline hostPipeline = renderPass.getPipeline();
        if (ChimeraSkyBridge.isDrawActive()
                && ChimeraSkyBridge.shouldUsePackPipeline(hostPipeline)) {
            var packPipeline = ChimeraSkyBridge.pipeline();
            if (packPipeline == null) {
                callback.setReturnValue(false);
                return;
            }
            VkCommandEncoder encoder = (VkCommandEncoder) (Object) this;
            encoder.applyPipelineState(hostPipeline);
            Renderer renderer = Renderer.getInstance();
            renderer.bindGraphicsPipeline(packPipeline);
            bindHostUniforms(renderPass, packPipeline);
            PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
            renderer.uploadAndBindUBOs(packPipeline);
            PackUniformProvider.restoreFrameAtlasSize();
            callback.setReturnValue(true);
            return;
        }
        if (!ChimeraEntityBridge.isDrawActive()) {
            return;
        }
        if (!ChimeraEntityBridge.shouldUsePackPipeline(hostPipeline)) {
            return;
        }
        boolean perDrawTransforms = ChimeraEntityBridge.requiresExtendedVertexFormat();
        if (!renderPass.hasDepthTexture()) {
            // A guarded family inherits the host depth state, and a depthless
            // pass can be a screen, inventory, or other overlay draw that
            // happens to reuse an entity-looking pipeline. A widened batch
            // keeps its append-only mesh, so it can never run that pass: the
            // host pipeline would read the shorter host layout. A batch that
            // still uses the host format keeps the ordinary host setup.
            if (perDrawTransforms) {
                ChimeraEntityBridge.noteDrawAbandoned("reason=depthless-pass");
                callback.setReturnValue(false);
            }
            return;
        }
        var packPipeline = ChimeraEntityBridge.pipeline();
        if (hostPipeline == null || packPipeline == null) {
            callback.setReturnValue(false);
            return;
        }

        GpuBufferSlice dynamicTransforms = null;
        GpuBufferSlice projection = null;
        UBO dynamicTransformsUbo = null;
        UBO projectionUbo = null;
        var dynamicRoute = EntityTransformBinding.HostTransformRoute.USE_GLOBAL;
        var projectionRoute = EntityTransformBinding.HostTransformRoute.USE_GLOBAL;
        if (renderPass instanceof ChimeraVkRenderPassAccess access) {
            dynamicTransforms = access.chimera$uniform(UniformRegistry.DYNAMIC_TRANSFORMS_UBO);
            projection = access.chimera$uniform(UniformRegistry.PROJECTION_UBO);
            dynamicTransformsUbo = packPipeline.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO);
            projectionUbo = packPipeline.getUBO(UniformRegistry.PROJECTION_UBO);
            dynamicRoute = routeTransform(dynamicTransforms, dynamicTransformsUbo, perDrawTransforms);
            projectionRoute = routeTransform(projection, projectionUbo, perDrawTransforms);
            if (dynamicRoute == EntityTransformBinding.HostTransformRoute.UNAVAILABLE
                    || projectionRoute == EntityTransformBinding.HostTransformRoute.UNAVAILABLE) {
                ChimeraEntityBridge.noteDrawAbandoned(describeTransformSources(
                        dynamicTransforms, dynamicTransformsUbo, projection, projectionUbo));
                callback.setReturnValue(false);
                return;
            }
        } else if (perDrawTransforms) {
            ChimeraEntityBridge.noteDrawAbandoned("reason=render-pass-access");
            callback.setReturnValue(false);
            return;
        }

        VkCommandEncoder encoder = (VkCommandEncoder) (Object) this;
        encoder.applyPipelineState(hostPipeline);
        if (renderPass.isScissorEnabled()) {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(renderPass.getScissorX(), renderPass.getScissorY(),
                    renderPass.getScissorWidth(), renderPass.getScissorHeight());
        } else {
            GlStateManager._disableScissorTest();
        }
        Renderer renderer = Renderer.getInstance();
        renderer.bindGraphicsPipeline(packPipeline);
        ChimeraEntityBridge.notePipelineBound(hostPipeline);
        for (var ubo : packPipeline.getBuffers()) {
            ubo.setUseGlobalBuffer(true);
        }
        // A routed slice is bound verbatim from the host render pass. The
        // global buffer stays bound only where the host draw itself would use
        // it, never to replace a guarded family's per-object transform.
        applyTransformRoute(dynamicRoute, dynamicTransforms, dynamicTransformsUbo);
        applyTransformRoute(projectionRoute, projection, projectionUbo);
        ChimeraEntityBridge.noteHostTransformRoutes(describeTransformSources(
                dynamicTransforms, dynamicTransformsUbo, projection, projectionUbo));
        PackUniformProvider.updateEntityAlphaReference(ChimeraEntityBridge.alphaReference(hostPipeline));
        PackUniformProvider.updateDrawAtlasSize(VTextureSelector.getBoundTexture(0));
        renderer.uploadAndBindUBOs(packPipeline);
        PackUniformProvider.restoreFrameAtlasSize();
        callback.setReturnValue(true);
    }

    /**
     * Routes the host transform slices a non-guarded lane carries.
     *
     * <p>A lane that keeps the host vertex format needs no per-draw matrix: it
     * binds a slice when one exists and can serve the block, and otherwise
     * leaves VulkanMod's global buffer bound, exactly like the host pipeline.</p>
     */
    private static void bindHostUniforms(
            VkRenderPass renderPass,
            GraphicsPipeline packPipeline
    ) {
        for (var ubo : packPipeline.getBuffers()) {
            ubo.setUseGlobalBuffer(true);
        }
        if (!(renderPass instanceof ChimeraVkRenderPassAccess access)) {
            return;
        }
        GpuBufferSlice dynamicTransforms = access.chimera$uniform(UniformRegistry.DYNAMIC_TRANSFORMS_UBO);
        UBO dynamicTransformsUbo = packPipeline.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO);
        GpuBufferSlice projection = access.chimera$uniform(UniformRegistry.PROJECTION_UBO);
        UBO projectionUbo = packPipeline.getUBO(UniformRegistry.PROJECTION_UBO);
        applyTransformRoute(routeTransform(dynamicTransforms, dynamicTransformsUbo, false),
                dynamicTransforms, dynamicTransformsUbo);
        applyTransformRoute(routeTransform(projection, projectionUbo, false),
                projection, projectionUbo);
    }

    private static EntityTransformBinding.HostTransformRoute routeTransform(
            GpuBufferSlice source, UBO target, boolean perDrawTransform) {
        return EntityTransformBinding.classifyHostTransform(perDrawTransform, source != null,
                isUsableHostSlice(source, target));
    }

    /** Binds a routed slice; any other route keeps VulkanMod's global buffer bound. */
    private static void applyTransformRoute(EntityTransformBinding.HostTransformRoute route,
                                            GpuBufferSlice source, UBO target) {
        if (route == EntityTransformBinding.HostTransformRoute.BIND_SLICE) {
            bindHostSlice(source, target);
        }
    }

    private static void bindHostSlice(GpuBufferSlice source, UBO target) {
        VkGpuBuffer buffer = (VkGpuBuffer) source.buffer();
        target.setUseGlobalBuffer(false);
        target.getBufferSlice().set(buffer.getBuffer(), source.offset(), target.getSize());
    }

    private static boolean isUsableHostSlice(GpuBufferSlice source, UBO target) {
        if (source == null || target == null || target.getSize() <= 0) {
            return false;
        }
        try {
            if (!(source.buffer() instanceof VkGpuBuffer buffer) || buffer.isClosed()) {
                return false;
            }
            Buffer backing = buffer.getBuffer();
            return backing != null && EntityTransformBinding.validUniformRange(source.offset(), source.length(),
                    backing.getBufferSize(), target.getSize());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Describes both transform sources separately: which side is present, the
     * pack block it targets, and the exact range. A single combined flag cannot
     * tell a missing host slice from a missing pack block.
     */
    private static String describeTransformSources(GpuBufferSlice dynamicTransforms, UBO dynamicTransformsUbo,
                                                   GpuBufferSlice projection, UBO projectionUbo) {
        return describeTransformSource(UniformRegistry.DYNAMIC_TRANSFORMS_UBO, dynamicTransforms,
                dynamicTransformsUbo)
                + " " + describeTransformSource(UniformRegistry.PROJECTION_UBO, projection, projectionUbo);
    }

    private static String describeTransformSource(String name, GpuBufferSlice source, UBO target) {
        String targetState = target == null
                ? "target=missing"
                : "target=present,name=" + target.name + ",binding=" + target.binding
                + ",required=" + target.getSize() + "B";
        if (source == null) {
            return name + "[source=absent," + targetState + "]";
        }
        return name + "[source=present,offset=" + source.offset() + ",length=" + source.length()
                + "," + targetState + ",capacity=" + sourceCapacity(source) + "B]";
    }

    private static long sourceCapacity(GpuBufferSlice source) {
        if (!(source.buffer() instanceof VkGpuBuffer buffer) || buffer.isClosed()) {
            return -1;
        }
        Buffer backing = buffer.getBuffer();
        return backing == null ? -1 : backing.getBufferSize();
    }
}
