package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.engine.VkTextureView;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** One pack-owned noisetex image for the narrow real-pack post path. */
public final class PackNoiseTexture implements AutoCloseable {
    private final DynamicTexture texture;
    private final VulkanImage image;

    private PackNoiseTexture(
            DynamicTexture texture,
            VulkanImage image
    ) {
        this.texture = texture;
        this.image = image;
    }

    public static PackNoiseTexture load(Path path) throws IOException {
        NativeImage pixels;
        try (InputStream input = Files.newInputStream(path)) {
            pixels = NativeImage.read(input);
        }
        if (pixels.getWidth() < 1 || pixels.getHeight() < 1) {
            pixels.close();
            throw new IOException("noise image has no pixels");
        }

        DynamicTexture texture = new DynamicTexture(() -> "chimera_pack_noise", pixels);
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
