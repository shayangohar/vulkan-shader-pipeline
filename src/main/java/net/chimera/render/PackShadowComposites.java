package net.chimera.render;

import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PostTargetPlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;

/**
 * The pack's shadowcomp passes, run after the shadow map is drawn as Iris's ShadowCompositeRenderer
 * runs them: a pass's computes first, then its fragment program as a fullscreen draw at the shadow
 * map's size, unblended, into the other side of each shadowcolor target it draws. The pass then flips
 * those targets, so the next pass and every later program sample what it wrote
 * (ChimeraShadowMap.shadowColor). Kappa's shadowcomp turns the shadow pass's albedo into the light
 * transmittance its lighting reads; without it, lighting took the raw albedo as a tint.
 */
final class PackShadowComposites {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private final ChimeraMainPass pass;
    /** The bound framebuffer VulkanMod sizes the viewport to: one wrapping the pass's first output. */
    private Framebuffer boundFramebuffer;
    /** Whether a failed pass was logged this pack session; later failures are quiet until the next. */
    private boolean failureLogged;

    PackShadowComposites(ChimeraMainPass pass) {
        this.pass = pass;
    }

    /**
     * A new pack session: its first failed pass is logged again, and the old session's framebuffer,
     * which wraps a freed shadow target, is dropped. The pass lives as long as the game, so a second
     * pack's failures went unlogged (audit RD4).
     */
    void resetSession() {
        this.failureLogged = false;
        this.boundFramebuffer = null;
    }

    /** True for the session's first failed pass, which is the one logged. */
    boolean firstFailure() {
        if (this.failureLogged) return false;
        this.failureLogged = true;
        return true;
    }

    /** Runs every shadowcomp pass in order over the map just drawn; computes dispatch at the screen's size. */
    void run(VkCommandBuffer commandBuffer, int screenWidth, int screenHeight) {
        Map<String, PackPipelines.PackPost> programs = new LinkedHashMap<>();
        pass.pipelines.shadowcompStages.forEach(post -> programs.put(post.name(), post));
        TreeSet<String> passes = new TreeSet<>(PostTargetPlan.programComparator());
        passes.addAll(programs.keySet());
        if (pass.packPassComputes != null) passes.addAll(pass.packPassComputes.shadowcompPasses());
        for (String name : passes) {
            if (pass.packPassComputes != null) {
                pass.packPassComputes.dispatchBefore(name, commandBuffer, pass.postChain.computeSources(commandBuffer),
                        screenWidth, screenHeight);
                if (pass.packAdvancedImageOwner != null) pass.packAdvancedImageOwner.afterImageWrites(commandBuffer);
            }
            PackPipelines.PackPost program = programs.get(name);
            if (program != null && !draw(commandBuffer, program)) return;
        }
    }

    /** Draws one pass into its targets' other sides and flips them; false, logged once, when it fails. */
    private boolean draw(VkCommandBuffer commandBuffer, PackPipelines.PackPost post) {
        ChimeraShadowMap shadowMap = pass.shadowPass.shadowMap;
        List<Integer> targets = post.targetPlan().targetSlots();
        ChimeraMainPass.PackRenderState previousState = pass.capturePackRenderState();
        ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> previous = null;
        boolean rendering = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<VulkanImage> outputs = new ArrayList<>();
            for (int target : targets) outputs.add(shadowMap.shadowcompOutput(stack, commandBuffer, target));
            ChimeraMainPass.preparePackFullscreenState();
            pass.prepareProgramImages(post.pipeline());
            previous = pass.images.bindProgramImages(post.pipeline());
            begin(stack, commandBuffer, outputs);
            rendering = true;
            pass.drawFullscreen(commandBuffer, post.pipeline());
            vkCmdEndRenderingKHR(commandBuffer);
            rendering = false;
            for (VulkanImage output : outputs) {
                output.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            targets.forEach(shadowMap::flipColor);
            return true;
        } catch (RuntimeException failure) {
            if (rendering) vkCmdEndRenderingKHR(commandBuffer);
            if (firstFailure()) {
                LOGGER.warn("[chimera] pack {}: shadowcomp pass failed; later passes this frame are skipped",
                        post.name(), failure);
            }
            return false;
        } finally {
            MrtPipelineContext.end();
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
            if (previous != null) previous.close();
            pass.restorePackRenderState(previousState);
        }
    }

    /** Begins dynamic rendering into {@code outputs}, loaded and stored, at the first one's size. */
    private void begin(MemoryStack stack, VkCommandBuffer commandBuffer, List<VulkanImage> outputs) {
        VkRenderingAttachmentInfo.Buffer attachments = VkRenderingAttachmentInfo.calloc(outputs.size(), stack);
        int[] formats = new int[outputs.size()];
        for (int index = 0; index < outputs.size(); index++) {
            VulkanImage output = outputs.get(index);
            output.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            attachments.get(index).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                    .imageView(PackPostTargets.attachmentView(output))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            formats[index] = output.format;
        }
        VulkanImage first = outputs.get(0);
        VkRect2D renderArea = VkRect2D.calloc(stack);
        renderArea.extent().set(first.width, first.height);
        VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .renderArea(renderArea)
                .layerCount(1)
                .pColorAttachments(attachments);
        // ShadowCompositeRenderer draws with blending disabled.
        MrtPipelineContext.begin(formats, ChimeraMainPass.deviceMaxColorAttachments(), null);
        if (this.boundFramebuffer == null || this.boundFramebuffer.getColorAttachment() != first) {
            this.boundFramebuffer = Framebuffer.builder(first, null).build();
        }
        Renderer.getInstance().setBoundFramebuffer(this.boundFramebuffer);
        Renderer.getInstance().setBoundRenderPass(ChimeraRenderingPass.of(formats, 0, false));
        Renderer.setViewport(0, 0, first.width, first.height, stack);
        VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
        scissor.get(0).extent().set(first.width, first.height);
        vkCmdSetScissor(commandBuffer, 0, scissor);
        vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
    }
}
