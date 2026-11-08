package net.chimera.render;

import net.chimera.shaderpack.ComputeValueBlock;
import net.chimera.shaderpack.PackAdvancedResourcePlan;
import net.chimera.shaderpack.PackPlan;
import net.chimera.shaderpack.PackResourceBinding;
import net.chimera.shaderpack.PackResourceKind;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.vkCmdDispatch;

/**
 * Iris's CenterDepthSampler: the 1x1 R32F texture fullscreen passes read as iris_centerDepthSmooth.
 * Each frame at beginHand, before the hand and translucents, it moves toward the world depth at the
 * screen's centre by {@code 1 - exp(-decay * frameTime)}, with {@code decay = ln 2 / (halfLife * 0.1)}
 * from the pack's centerDepthHalflife; a NaN value takes the new depth whole. Iris reads the main
 * depth with its own nearest sampler. The image starts at zero, as Iris's unfilled texture reads.
 */
final class PackCenterDepth implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final List<ComputeValueBlock.Value> VALUES =
            ComputeValueBlock.layout(Map.of("frameTime", "float"));
    private static final String SOURCE = """
            #version 450
            layout(local_size_x = 1) in;
            layout(binding = 0) uniform sampler2D depth;
            layout(binding = 1, r32f) uniform image2D smoothed;
            %s
            const float DECAY = %s;
            void main() {
                float currentDepth = textureLod(depth, vec2(0.5), 0.0).r;
                float decay2 = 1.0 - exp(-DECAY * frameTime);
                float oldDepth = imageLoad(smoothed, ivec2(0)).r;
                if (isnan(oldDepth)) oldDepth = currentDepth;
                imageStore(smoothed, ivec2(0), vec4(mix(oldDepth, currentDepth, decay2)));
            }
            """;

    private final PackStorageImage image;
    private final ComputeKernel kernel;
    private final DepthSampleView depthView = new DepthSampleView();
    private final long sampler = ChimeraSamplers.flat(false, true);

    private PackCenterDepth(PackStorageImage image, ComputeKernel kernel) {
        this.image = image;
        this.kernel = kernel;
    }

    /**
     * The smoothed centre depth when a program of {@code plan} reads it, else null. Without its
     * kernel the image stays at zero, which every reader then gets.
     */
    static PackCenterDepth create(PackPlan plan) {
        if (plan == null || plan.resources().bindings().values().stream().flatMap(Collection::stream)
                .map(PackResourceBinding::kind).noneMatch(PackResourceKind.CENTER_DEPTH::equals)) {
            return null;
        }
        var spec = new PackAdvancedResourcePlan.ImageSpec("iris_centerDepthSmooth", "iris_centerDepthSmooth",
                "RED", "R32F", "FLOAT", false, false, 1, 1, 0, null, null, null, 4L,
                PackAdvancedResourcePlan.Status.SUPPORTED, List.of());
        PackStorageImage image = PackStorageImage.create(spec, new int[] {1, 1, 1}, Set.of());
        float halfLife = plan.config() == null ? 1.0F : plan.config().directives().centerDepthHalfLife();
        ComputeKernel kernel = ComputeKernel.create("chimera_center_depth.csh", source(halfLife),
                new int[] {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE},
                ComputeValueBlock.bufferSize(VALUES));
        LOGGER.info("[chimera] centerDepthSmooth: halfLife={}, kernel={}", halfLife, kernel != null ? "ok" : "failed");
        return new PackCenterDepth(image, kernel);
    }

    /** The kernel for the pack's half-life: the depth sampler, the image, then frameTime's block. */
    static String source(float halfLife) {
        return String.format(Locale.ROOT, SOURCE,
                ComputeValueBlock.declaration("ChimeraCenterDepthValues", VALUES, 2), decayLiteral(halfLife));
    }

    /**
     * Iris's decay, {@code 1 / ((halfLife * 0.1) / ln 2)}, as a GLSL literal. A half-life of zero
     * makes it infinite there; the largest float stands in, which takes the new depth whole.
     */
    static String decayLiteral(float halfLife) {
        float decay = (float) (1.0 / ((halfLife * 0.1) / Math.log(2)));
        return String.format(Locale.ROOT, "%.9e", Float.isFinite(decay) ? decay : Float.MAX_VALUE);
    }

    /** Clears the image to zero before anything first reads it; call outside any render pass. */
    void beginFrame(VkCommandBuffer commandBuffer) {
        if (image.initialized()) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.clearToZero(stack, commandBuffer);
        }
        image.markInitialized();
    }

    /** Moves the value toward the centre of {@code worldDepth}; call once a frame, outside any render pass. */
    void sample(VkCommandBuffer commandBuffer, VulkanImage worldDepth) {
        if (kernel == null || worldDepth == null || !image.initialized()) return;
        long view = depthView.ensure(worldDepth);
        if (view == 0L) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            image.transitionToGeneral(stack, commandBuffer);
            worldDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            ComputeKernel.FrameSlot frame = kernel.currentFrame();
            kernel.writeImage(frame, 0, view, sampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            kernel.writeImage(frame, 1, image.getImageView(), 0L, VK_IMAGE_LAYOUT_GENERAL);
            PackVulkanBarriers.beforeCompute(commandBuffer, stack);
            kernel.bind(commandBuffer, stack, frame, VALUES);
            vkCmdDispatch(commandBuffer, 1, 1, 1);
            PackVulkanBarriers.afterCompute(commandBuffer, stack);
            worldDepth.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
    }

    /** The image fullscreen passes sample, which stays in GENERAL. */
    VulkanImage image() {
        return image;
    }

    @Override
    public void close() {
        if (kernel != null) kernel.close();
        depthView.destroy();
        image.free();
    }
}
