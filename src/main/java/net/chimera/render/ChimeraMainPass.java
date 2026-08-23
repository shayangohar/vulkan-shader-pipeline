package net.chimera.render;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.pass.DefaultMainPass;
import net.vulkanmod.vulkan.pass.MainPass;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * M1 main pass: forwards every MainPass operation to a delegate instance.
 * Exists as chimera's extension point - later milestones replace the delegate
 * internals (HDR framebuffer target, shadow pre-pass, composite chain) while
 * keeping this class as the object VulkanMod's Renderer holds.
 */
public class ChimeraMainPass implements MainPass {
    private final MainPass delegate;

    public ChimeraMainPass(MainPass delegate) {
        this.delegate = delegate;
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        this.delegate.begin(commandBuffer, stack);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        this.delegate.end(commandBuffer);
    }

    @Override
    public void cleanUp() {
        this.delegate.cleanUp();
    }

    @Override
    public void onResize() {
        this.delegate.onResize();
    }

    @Override
    public void rebindMainTarget() {
        this.delegate.rebindMainTarget();
    }

    @Override
    public void bindAsTexture() {
        this.delegate.bindAsTexture();
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return this.delegate.getMainFramebuffer();
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.delegate.getColorAttachment();
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.delegate.getColorAttachmentView();
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.delegate.getDepthAttachment();
    }
}
