package net.chimera.render;

import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.chimera.shaderpack.IrisColorFormats;
import net.chimera.shaderpack.TargetSpec;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.util.Collection;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_BLIT_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_QUEUE_FAMILY_IGNORED;
import static org.lwjgl.vulkan.VK10.vkCmdBlitImage;
import static org.lwjgl.vulkan.VK10.vkCmdClearColorImage;
import static org.lwjgl.vulkan.VK10.vkCmdCopyImage;
import static org.lwjgl.vulkan.VK10.vkCmdPipelineBarrier;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFormatProperties;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK11.VK_FORMAT_FEATURE_TRANSFER_SRC_BIT;

/**
 * Pack target images: allocation, the device checks their formats need, clears, copies, mip
 * chains and samplers. An integer target (IrisColorFormats) samples nearest, as Iris's do (Iris
 * RenderTarget, CompositeRenderer); its clears and mip chains are Chimera's defined stand-ins for
 * what GL leaves undefined or refuses.
 */
final class PackTargetImages {
    private PackTargetImages() {}

    /** R16, RG16 and RGBA16 in their exact normalized formats, whose target features are optional. */
    private static final Set<Integer> NORMALIZED_16_FORMATS = Set.of(
            VK_FORMAT_R16_UNORM, VK_FORMAT_R16G16_UNORM, VK_FORMAT_R16G16B16A16_UNORM);

    /** Checks the device features the targets' formats rely on before any image is allocated. */
    static void verifyFormats(Collection<TargetSpec> targets) {
        // The 16-bit normalized formats' sampled/attachment features are not mandatory on every
        // device. Reject unsupported storage rather than silently substituting floating point.
        targets.stream().map(TargetSpec::format).filter(NORMALIZED_16_FORMATS::contains)
                .distinct().forEach(PackTargetImages::verifyNormalized16Support);
        targets.stream().filter(TargetSpec::mipmapped).map(TargetSpec::format)
                .distinct().forEach(PackTargetImages::verifyMipmapSupport);
    }

    /** A target image; one a pass compute writes through {@code colorimgN} also allows storage. */
    static VulkanImage createTargetImage(TargetSpec target, boolean storage) {
        return createTargetImage(target, storage ? Set.of(target.format()) : Set.of());
    }

    /**
     * A target image that storage bindings write in {@code storageFormats} (none: not a storage
     * image), with a view in each one other than its own (TargetFormatViews).
     */
    static VulkanImage createTargetImage(TargetSpec target, Set<Integer> storageFormats) {
        boolean storage = !storageFormats.isEmpty() && PackPassComputes.storageSupported(target.format());
        VulkanImage image = TargetFormatViews.create(() -> VulkanImage.builder(target.width(), target.height())
                .setFormat(target.format())
                .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | (storage ? VK_IMAGE_USAGE_STORAGE_BIT : 0))
                .setMipLevels(target.mipLevels())
                // Reforged makes per-level views only on request. A mipmapped target renders to level 0
                // through one, as does a padded-alpha target, whose main view reads alpha as 1.
                .setLevelViews(target.mipLevels() > 1 || target.paddedAlpha())
                .setLinearFiltering(!IrisColorFormats.integer(target.format()))
                .setClamp(true)
                .createVulkanImage(), target.format(), storage ? storageFormats : Set.of());
        if (target.paddedAlpha()) ImageViews.sampleAlphaAsOne(image);
        // Iris samples the base level until a program's pass generates the chain.
        if (image.mipLevels > 1) sampleWith(image, baseLevelSampler(image));
        return image;
    }

    /** The mip chain is built by blits, linear unless integer; reject a format that cannot be blitted so. */
    private static void verifyMipmapSupport(int format) {
        int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                | (IrisColorFormats.integer(format) ? 0 : VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        requireFeatures(format, required, "mipmapped target format", "blit");
    }

    /** Check an optional exact normalized format before allocating any target images. */
    private static void verifyNormalized16Support(int format) {
        requireFeatures(format, VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT
                | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT
                | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT
                | VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT
                | VK_FORMAT_FEATURE_TRANSFER_SRC_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT,
                "16-bit normalized target format", "sampled/linear/attachment/blend/transfer");
    }

    private static void requireFeatures(int format, int required, String what, String support) {
        if (DeviceManager.physicalDevice == null) {
            throw new IllegalStateException("Vulkan physical device is unavailable for " + what);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties properties = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);
            if ((properties.optimalTilingFeatures() & required) != required) {
                throw new IllegalArgumentException(what + " " + format + " lacks " + support
                        + " support (required=0x" + Integer.toHexString(required)
                        + ", supported=0x" + Integer.toHexString(properties.optimalTilingFeatures()) + ")");
            }
        }
    }

    /**
     * Clears a target's base level to its clear colour. An integer target takes the colour's
     * integer parts. GL leaves a float clear of an integer buffer undefined (Iris ClearPass clears
     * with a float colour); Chimera writes the integer value the colour names.
     */
    static void clearImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, float[] color) {
        if (image == null) throw new IllegalStateException("cannot clear missing target image");
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        clearValue(stack, clear, image.format, color);
        VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
        range.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        vkCmdClearColorImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, range);
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /** A clear colour in the image format's kind: floats, or the colour's integer parts. */
    static void clearValue(MemoryStack stack, VkClearColorValue clear, int vkFormat, float[] color) {
        IrisColorFormats.Format format = IrisColorFormats.stored(vkFormat);
        if (format != null && format.kind() == IrisColorFormats.Kind.UINT) {
            clear.uint32(stack.ints(unsigned(color[0]), unsigned(color[1]), unsigned(color[2]), unsigned(color[3])));
        } else if (format != null && format.kind() == IrisColorFormats.Kind.SINT) {
            clear.int32(stack.ints((int) color[0], (int) color[1], (int) color[2], (int) color[3]));
        } else {
            clear.float32(stack.floats(color[0], color[1], color[2], color[3]));
        }
    }

    private static int unsigned(float value) {
        return value <= 0.0f ? 0 : (int) Math.min(value, 4294967295.0f);
    }

    /** Copies level 0 between two targets of one format and size. */
    static void copyImage(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage source,
                          VulkanImage destination) {
        if (source.format != destination.format || source.width != destination.width
                || source.height != destination.height) {
            throw new IllegalStateException("POST_TARGET_SEED_CONVERSION");
        }
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        VkImageSubresourceLayers sourceSubresource = copy.srcSubresource();
        sourceSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        VkImageSubresourceLayers destinationSubresource = copy.dstSubresource();
        destinationSubresource.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        copy.extent().set(source.width, source.height, 1);
        vkCmdCopyImage(commandBuffer, source.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                destination.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        source.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        destination.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /** Generates the image's mip chain and samples it through every level, as Iris does after a pass. */
    static void buildMipChain(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image) {
        // One tracked layout covers the chain; TRANSFER_DST keeps level 0's contents.
        image.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        boolean integer = IrisColorFormats.integer(image.format);
        recordMipChain(stack, commandBuffer, image.getId(), image.width, image.height, image.mipLevels,
                integer ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        sampleWith(image, ChimeraSamplers.mipmapped(image.mipLevels - 1, !integer));
    }

    /** glGenerateMipmap with linear downsampling: the float targets' chain. */
    static void recordMipChain(MemoryStack stack, VkCommandBuffer commandBuffer,
                               long image, int width, int height, int levels) {
        recordMipChain(stack, commandBuffer, image, width, height, levels, VK_FILTER_LINEAR);
    }

    /**
     * glGenerateMipmap: each level is a downsample of the one above. An integer target downsamples
     * nearest, Chimera's choice: GL refuses glGenerateMipmap on a format it cannot filter, so Iris's
     * integer chains stay at their base level. Every level enters in TRANSFER_DST and leaves in
     * SHADER_READ_ONLY.
     */
    static void recordMipChain(MemoryStack stack, VkCommandBuffer commandBuffer,
                               long image, int width, int height, int levels, int filter) {
        for (int level = 1; level < levels; level++) {
            levelBarrier(stack, commandBuffer, image, level - 1, 1,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            int nextWidth = Math.max(1, width / 2);
            int nextHeight = Math.max(1, height / 2);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level - 1).layerCount(1);
            blit.srcOffsets(1).set(width, height, 1);
            blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(level).layerCount(1);
            blit.dstOffsets(1).set(nextWidth, nextHeight, 1);
            vkCmdBlitImage(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filter);
            width = nextWidth;
            height = nextHeight;
        }
        int last = levels - 1;
        if (last > 0) {
            levelBarrier(stack, commandBuffer, image, 0, last,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
        levelBarrier(stack, commandBuffer, image, last, 1,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
    }

    private static void levelBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, long image,
                                     int baseLevel, int levelCount, int oldLayout, int newLayout,
                                     int sourceAccess, int destinationAccess, int destinationStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                .sType$Default().oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image).srcAccessMask(sourceAccess).dstAccessMask(destinationAccess);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(baseLevel).levelCount(levelCount).layerCount(1);
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, destinationStage,
                0, null, null, barrier);
    }

    /** Iris samples a target's base level clamped to the edge: linearly, or nearest for integers. */
    static long baseLevelSampler(VulkanImage image) {
        return ChimeraSamplers.flat(!IrisColorFormats.integer(image.format), true);
    }

    static void sampleWith(VulkanImage image, long sampler) {
        ((ChimeraVulkanImageAccessor) image).chimera$setSampler(sampler);
    }
}
