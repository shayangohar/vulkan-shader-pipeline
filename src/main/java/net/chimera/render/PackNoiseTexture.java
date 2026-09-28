package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.engine.VkTextureView;
import net.vulkanmod.vulkan.texture.SamplerInfo;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** One pack-owned noisetex image for the narrow real-pack post path. */
public final class PackNoiseTexture implements AutoCloseable {
    private static final int MAX_ENCODED_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 16L * 1024L * 1024L;

    private final DynamicTexture texture;
    private final VulkanImage image;

    private PackNoiseTexture(
            DynamicTexture texture,
            VulkanImage image
    ) {
        this.texture = texture;
        this.image = image;
    }

    /** Loads with the binding's planned sampling ("linear"/"nearest", "repeat"/"clamp"). */
    public static PackNoiseTexture load(Path path, String name, String filter, String wrap)
            throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return load(input, name, filter, wrap);
        }
    }

    public static PackNoiseTexture load(InputStream input, String name, String filter, String wrap)
            throws IOException {
        if (input == null) {
            throw new IOException("texture input is missing");
        }
        byte[] encoded = input.readNBytes(MAX_ENCODED_BYTES + 1);
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IOException("texture exceeds encoded size limit");
        }
        NativeImage pixels = NativeImage.read(new ByteArrayInputStream(encoded));
        if (pixels.getWidth() < 1 || pixels.getHeight() < 1) {
            pixels.close();
            throw new IOException("noise image has no pixels");
        }
        if (pixels.getWidth() > MAX_DIMENSION || pixels.getHeight() > MAX_DIMENSION
                || (long) pixels.getWidth() * pixels.getHeight() > MAX_PIXELS) {
            pixels.close();
            throw new IOException("texture dimensions exceed limit");
        }

        DynamicTexture texture = new DynamicTexture(() -> name == null ? "chimera_pack_texture" : name, pixels);
        try {
            texture.upload();
            GpuTextureView view = texture.getTextureView();
            if (!(view instanceof VkTextureView vulkanView)) {
                throw new IOException("noise texture did not create a Vulkan view");
            }
            VkGpuTexture gpuTexture = vulkanView.texture();
            VulkanImage image = gpuTexture.getVulkanImage();
            if (image == null) {
                throw new IOException("noise texture has no Vulkan image");
            }
            // DynamicTexture samples nearest/repeat. A pack's water normals
            // difference neighbouring noise texels, so nearest made them blocky.
            boolean linear = !"nearest".equals(filter);
            int vkFilter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            image.setSampler(SamplerManager.getSampler(SamplerInfo.builder()
                    .setAddressMode("clamp".equals(wrap)
                            ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .setFiltering(vkFilter, vkFilter, VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .createSamplerInfo()));
            return new PackNoiseTexture(texture, image);
        } catch (RuntimeException | IOException e) {
            texture.close();
            throw e;
        }
    }

    public VulkanImage image() {
        return image;
    }

    @Override
    public void close() {
        texture.close();
    }
}
