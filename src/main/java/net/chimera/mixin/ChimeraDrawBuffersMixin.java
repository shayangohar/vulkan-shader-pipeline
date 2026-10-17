package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps VulkanMod's section buffers on the live terrain format, and records execution only
 * after a nonempty terrain draw command.
 *
 * <p>DrawBuffers reads its vertex stride and position bias from the terrain format current
 * when the class loaded. Chimera changes the format when a pack installs or leaves, then
 * rebuilds every section (WorldRenderer.allChanged), so the buffers created after that must
 * follow the new format.</p>
 */
@Mixin(value = DrawBuffers.class, remap = false)
public abstract class ChimeraDrawBuffersMixin {
    @ModifyExpressionValue(method = {"stageUpload", "initialVertexBufferElementCount",
            "lambda$getAreaBufferOrAlloc$0"}, at = @At(value = "FIELD",
            target = "Lnet/vulkanmod/render/chunk/buffer/DrawBuffers;VERTEX_SIZE:I"), require = 4)
    private static int chimera$liveStride(int loaded) {
        return PipelineManager.TERRAIN_VERTEX_FORMAT.getVertexSize();
    }

    /** VulkanMod biases every compressed position, Chimera's formats included; BLOCK positions are plain. */
    @ModifyExpressionValue(method = "updateChunkAreaOrigin", at = @At(value = "FIELD",
            target = "Lnet/vulkanmod/render/chunk/buffer/DrawBuffers;POS_OFFSET:F"), require = 3)
    private static float chimera$livePositionBias(float loaded) {
        return PipelineManager.TERRAIN_VERTEX_FORMAT == DefaultVertexFormat.BLOCK
                ? 0.0F : -VertexBuilder.CompressedVertexBuilder.POS_OFFSET;
    }

    @Redirect(method = "buildDrawBatchesDirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexed(Lorg/lwjgl/vulkan/VkCommandBuffer;IIIII)V"), require = 1)
    private void chimera$direct(VkCommandBuffer cmd, int count, int instances, int first, int vertex, int base) {
        VK10.vkCmdDrawIndexed(cmd, count, instances, first, vertex, base);
        if (count > 0 && instances > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }

    @Redirect(method = "buildDrawBatchesIndirect", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK10;vkCmdDrawIndexedIndirect(Lorg/lwjgl/vulkan/VkCommandBuffer;JJII)V"),
                    require = 1)
    private void chimera$indirect(VkCommandBuffer cmd, long buffer, long offset, int count, int stride) {
        VK10.vkCmdDrawIndexedIndirect(cmd, buffer, offset, count, stride);
        if (count > 0 && ChimeraRenderer.getMainPass() != null)
            ChimeraRenderer.getMainPass().recordShadowDraw();
    }
}
