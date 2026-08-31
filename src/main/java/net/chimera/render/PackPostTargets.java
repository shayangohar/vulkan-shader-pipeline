package net.chimera.render;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PostTargetPlan;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingInfo;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_RENDERING_INFO_KHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK10.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdSetScissor;

/**
 * Owns the small post-processing target graph used by M5.6. The host
 * renderer still owns the HDR and presentation framebuffers. This class only
 * owns two banks of pack color images and the dynamic-rendering seam between
 * them.
 */
public final class PackPostTargets {
    private static final int TARGET_COUNT = PostTargetPlan.MAX_TARGET + 1;

    private final VulkanImage[][] images = new VulkanImage[2][TARGET_COUNT];
    private final boolean[] used = new boolean[TARGET_COUNT];
    private final boolean[] initialized = new boolean[TARGET_COUNT];
    private final boolean[] pendingOutputs = new boolean[TARGET_COUNT];
    private final int[] targetFormats = new int[TARGET_COUNT];
    private final VulkanImage[] sourceImages = new VulkanImage[TARGET_COUNT];
    /** The current frame's immutable identity source for logical target 0. */
    private VulkanImage hdrIdentitySource;

    private Framebuffer pipelineFramebuffer;
    private RenderPass pipelineRenderPass;
    private int activeBank;
    private int destinationBank;
    private boolean configured;
    private boolean rendering;

    public boolean configure(
            List<PackPipelines.PackPost> posts,
            Map<Integer, Integer> declaredFormats,
            int width,
            int height
    ) {
        cleanUp();
        if (!needsTargetChain(posts)) {
            return false;
        }

        Arrays.fill(this.used, false);
        Arrays.fill(this.targetFormats, PostTargetPlan.DEFAULT_FORMAT);
        this.used[0] = true;
        for (PackPipelines.PackPost post : posts) {
            PostTargetPlan plan = post.targetPlan();
            if (plan == null) {
                continue;
            }
            List<Integer> targets = plan.targetSlots();
            List<Integer> formats = plan.outputFormats();
            for (int i = 0; i < targets.size(); i++) {
                int target = targets.get(i);
                if (target >= 0 && target < TARGET_COUNT) {
                    this.used[target] = true;
                    this.targetFormats[target] = formats.get(i);
                }
            }
            for (String sampler : post.samplerNames()) {
                if (!sampler.startsWith("colortex")) {
                    continue;
                }
                try {
                    int target = Integer.parseInt(sampler.substring("colortex".length()));
                    if (target >= 0 && target < TARGET_COUNT) {
                        this.used[target] = true;
                    }
                } catch (NumberFormatException ignored) {
                    // The interface plan rejects malformed sampler names.
                }
            }
        }
        if (declaredFormats != null) {
            for (Map.Entry<Integer, Integer> entry : declaredFormats.entrySet()) {
                int target = entry.getKey();
                if (target >= 0 && target < TARGET_COUNT && this.used[target]) {
                    this.targetFormats[target] = entry.getValue();
                }
            }
        }

        int safeWidth = Math.max(width, 1);
        int safeHeight = Math.max(height, 1);
        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                | VK_IMAGE_USAGE_SAMPLED_BIT
                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        for (int bank = 0; bank < images.length; bank++) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (!this.used[target]) {
                    continue;
                }
                this.images[bank][target] = VulkanImage.builder(safeWidth, safeHeight)
                        .setName("chimeraPackColortex" + target + "Bank" + bank)
                        .setFormat(this.targetFormats[target])
                        .setUsage(usage)
                        .setLinearFiltering(true)
                        .setClamp(true)
                        .createVulkanImage();
            }
        }

        // VulkanMod uses this one-color framebuffer only as the pipeline
        // state anchor. The actual draw attachments are supplied below by
        // dynamic rendering, so no host framebuffer is widened.
        this.pipelineFramebuffer = Framebuffer.builder(this.images[0][0], null).build();
        this.pipelineRenderPass = RenderPass.builder(this.pipelineFramebuffer).build();
        this.configured = true;
        return true;
    }

    public void beginFrame(VkCommandBuffer commandBuffer, VulkanImage hdrColor) {
        if (!this.configured) {
            throw new IllegalStateException("pack post targets are not configured");
        }
        this.activeBank = 0;
        this.destinationBank = 1;
        Arrays.fill(this.initialized, false);
        Arrays.fill(this.pendingOutputs, false);
        this.hdrIdentitySource = hdrColor;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            range.get(0).baseMipLevel(0);
            range.get(0).levelCount(1);
            range.get(0).baseArrayLayer(0);
            range.get(0).layerCount(1);
            for (int bank = 0; bank < images.length; bank++) {
                for (int target = 0; target < TARGET_COUNT; target++) {
                    VulkanImage image = this.images[bank][target];
                    if (image == null) {
                        continue;
                    }
                    image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                    vkCmdClearColorImage(commandBuffer, image.getId(),
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
                    image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
        }
        Arrays.fill(this.sourceImages, null);
        this.sourceImages[0] = hdrColor;
    }

    /**
     * Invalidates logical pack ownership for the supplied outputs. Physical
     * images are deliberately left untouched so bank preservation and cleanup
     * remain unchanged. Target 0 immediately returns to the HDR identity
     * source; auxiliary targets remain unavailable until a later successful
     * finish writes them.
     */
    public void invalidateOutputs(List<Integer> targets) {
        if (targets == null) {
            return;
        }
        for (int target : targets) {
            if (target < 0 || target >= TARGET_COUNT) {
                continue;
            }
            this.pendingOutputs[target] = false;
            this.initialized[target] = false;
            this.sourceImages[target] = target == 0 ? this.hdrIdentitySource : null;
        }
    }

    public void prepare(PackPipelines.PackPost post, VkCommandBuffer commandBuffer) {
        if (!this.configured || this.rendering) {
            throw new IllegalStateException("pack post target state is not ready");
        }
        PostTargetPlan plan = post.targetPlan();
        if (plan == null || plan.isFinal()) {
            throw new IllegalArgumentException("final is not an intermediate pack post pass");
        }

        this.destinationBank = 1 - this.activeBank;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (!this.used[target]) {
                    continue;
                }
                VulkanImage source = this.sourceImages[target];
                VulkanImage destination = this.images[this.destinationBank][target];
                if (destination == null) {
                    throw new IllegalStateException("missing pack post target " + target);
                }
                if (source != null) {
                    copyImage(stack, commandBuffer, source, destination);
                }
            }
            for (int target = 0; target < TARGET_COUNT; target++) {
                VulkanImage destination = this.images[this.destinationBank][target];
                if (destination != null) {
                    destination.transitionImageLayout(stack, commandBuffer,
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                }
            }

            List<Integer> targets = plan.targetSlots();
            Arrays.fill(this.pendingOutputs, false);
            for (int target : targets) {
                this.pendingOutputs[target] = true;
            }
            VkRenderingAttachmentInfo.Buffer attachments =
                    VkRenderingAttachmentInfo.calloc(targets.size(), stack);
            for (int i = 0; i < targets.size(); i++) {
                VulkanImage destination = this.images[this.destinationBank][targets.get(i)];
                VkRenderingAttachmentInfo attachment = attachments.get(i);
                attachment.sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR);
                attachment.imageView(destination.getImageView());
                attachment.imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                attachment.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD);
                attachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            }

            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset().set(0, 0);
            renderArea.extent().set(this.images[this.destinationBank][0].width,
                    this.images[this.destinationBank][0].height);
            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR);
            renderingInfo.renderArea(renderArea);
            renderingInfo.layerCount(1);
            renderingInfo.pColorAttachments(attachments);

            MrtPipelineContext.begin(plan.outputFormatsArray());
            Renderer.getInstance().setBoundFramebuffer(this.pipelineFramebuffer);
            Renderer.getInstance().setBoundRenderPass(this.pipelineRenderPass);
            Renderer.setViewport(0, 0, renderArea.extent().width(), renderArea.extent().height(), stack);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).offset().set(0, 0);
            scissor.get(0).extent().set(renderArea.extent().width(), renderArea.extent().height());
            vkCmdSetScissor(commandBuffer, 0, scissor);
            try {
                vkCmdBeginRenderingKHR(commandBuffer, renderingInfo);
                this.rendering = true;
            } catch (RuntimeException e) {
                MrtPipelineContext.end();
                Renderer.getInstance().setBoundRenderPass(null);
                Renderer.getInstance().setBoundFramebuffer(null);
                throw e;
            }
        }
    }

    public void finish(VkCommandBuffer commandBuffer) {
        if (!this.rendering) {
            throw new IllegalStateException("pack post rendering is not active");
        }
        boolean committed = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdEndRenderingKHR(commandBuffer);
            for (int target = 0; target < TARGET_COUNT; target++) {
                VulkanImage destination = this.images[this.destinationBank][target];
                if (destination != null) {
                    destination.transitionImageLayout(stack, commandBuffer,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
            committed = true;
        } finally {
            this.activeBank = bankAfterFinish(this.activeBank, this.destinationBank, committed);
            if (committed) {
                for (int target = 0; target < TARGET_COUNT; target++) {
                    if (this.pendingOutputs[target]) {
                        this.initialized[target] = true;
                        this.sourceImages[target] = this.images[this.activeBank][target];
                    } else if (this.initialized[target]) {
                        this.sourceImages[target] = this.images[this.activeBank][target];
                    }
                }
            }
            Arrays.fill(this.pendingOutputs, false);
            this.rendering = false;
            MrtPipelineContext.end();
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
        }
    }

    public void abort(VkCommandBuffer commandBuffer) {
        if (this.rendering) {
            vkCmdEndRenderingKHR(commandBuffer);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                VulkanImage destination = this.images[this.destinationBank][target];
                if (destination != null) {
                    destination.transitionImageLayout(stack, commandBuffer,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                }
            }
        } finally {
            this.rendering = false;
            Arrays.fill(this.pendingOutputs, false);
            MrtPipelineContext.end();
            Renderer.getInstance().setBoundRenderPass(null);
            Renderer.getInstance().setBoundFramebuffer(null);
        }
    }

    public VulkanImage[] sourceImages() {
        return this.sourceImages;
    }

    /** True when every declared logical color input has a valid current image. */
    public boolean areInputsAvailable(List<Integer> requiredTargets) {
        if (requiredTargets == null) {
            return true;
        }
        for (int target : requiredTargets) {
            if (!isTargetAvailable(target)) {
                return false;
            }
        }
        return true;
    }

    public VulkanImage activeTarget(int target) {
        if (target < 0 || target >= TARGET_COUNT) {
            return null;
        }
        if (target == 0 && !this.initialized[0]) {
            return this.sourceImages[0];
        }
        return this.initialized[target] ? this.images[this.activeBank][target] : null;
    }

    /** Pure target availability rule used by the deterministic harness. */
    static boolean isTargetAvailable(int target, boolean hdrIdentity, boolean[] written) {
        return written != null && target >= 0 && target < TARGET_COUNT && target < written.length
                && (target == 0 ? hdrIdentity || written[target] : written[target]);
    }

    static boolean areTargetsAvailable(List<Integer> targets, boolean hdrIdentity, boolean[] written) {
        if (targets == null) {
            return true;
        }
        for (int target : targets) {
            if (!isTargetAvailable(target, hdrIdentity, written)) {
                return false;
            }
        }
        return true;
    }

    /** Pure logical invalidation rule used by the deterministic harness. */
    static void invalidateWrittenTargets(boolean[] written, List<Integer> targets) {
        if (written == null || targets == null) {
            return;
        }
        for (int target : targets) {
            if (target >= 0 && target < TARGET_COUNT && target < written.length) {
                written[target] = false;
            }
        }
    }

    /** Pure commit rule used to prove that failed stages cannot swap banks. */
    static int bankAfterFinish(int activeBank, int destinationBank, boolean committed) {
        return committed ? destinationBank : activeBank;
    }

    public boolean isRendering() {
        return this.rendering;
    }

    public boolean isConfigured() {
        return this.configured;
    }

    public void cleanUp() {
        if (this.rendering) {
            throw new IllegalStateException("cannot clean up active pack post rendering");
        }
        if (this.pipelineRenderPass != null) {
            this.pipelineRenderPass.cleanUp();
        }
        if (this.pipelineFramebuffer != null) {
            this.pipelineFramebuffer.cleanUp(false);
        }
        this.pipelineRenderPass = null;
        this.pipelineFramebuffer = null;
        for (int bank = 0; bank < images.length; bank++) {
            for (int target = 0; target < TARGET_COUNT; target++) {
                if (this.images[bank][target] != null) {
                    this.images[bank][target].free();
                    this.images[bank][target] = null;
                }
            }
        }
        Arrays.fill(this.sourceImages, null);
        this.hdrIdentitySource = null;
        Arrays.fill(this.used, false);
        Arrays.fill(this.initialized, false);
        Arrays.fill(this.pendingOutputs, false);
        this.configured = false;
        this.activeBank = 0;
        this.destinationBank = 1;
        MrtPipelineContext.end();
    }

    private static boolean needsTargetChain(List<PackPipelines.PackPost> posts) {
        int intermediateCount = 0;
        for (PackPipelines.PackPost post : posts) {
            PostTargetPlan plan = post.targetPlan();
            if (plan == null) {
                continue;
            }
            if (plan.isFinal()) {
                return true;
            }
            intermediateCount++;
            if (plan.requiresMrt() || !post.name().equals("composite")) {
                return true;
            }
        }
        return intermediateCount > 1;
    }

    private boolean isTargetAvailable(int target) {
        if (target < 0 || target >= TARGET_COUNT) {
            return false;
        }
        return target == 0
                ? this.sourceImages[0] != null || this.initialized[0]
                : this.initialized[target] && this.sourceImages[target] != null;
    }

    private static void copyImage(
            MemoryStack stack,
            VkCommandBuffer commandBuffer,
            VulkanImage source,
            VulkanImage destination
    ) {
        if (source.format != destination.format
                || source.width != destination.width
                || source.height != destination.height) {
            throw new IllegalStateException("pack post target copy dimensions or formats differ");
        }
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        VkImageSubresourceLayers sourceSubresource = copy.srcSubresource();
        sourceSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        sourceSubresource.mipLevel(0);
        sourceSubresource.baseArrayLayer(0);
        sourceSubresource.layerCount(1);
        VkImageSubresourceLayers destinationSubresource = copy.dstSubresource();
        destinationSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        destinationSubresource.mipLevel(0);
        destinationSubresource.baseArrayLayer(0);
        destinationSubresource.layerCount(1);
        copy.extent().set(source.width, source.height, 1);
        vkCmdCopyImage(commandBuffer, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }
}
