package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** One pack-owned sampled image: a pack PNG or game texture, or the noise Iris generates. */
public final class PackNoiseTexture implements PackResourceOwner.LoadedTexture {
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

        return upload(pixels, name, filter, wrap);
    }

    /**
     * The noise Iris generates for a pack without texture.noise: {@code size} squared opaque
     * texels whose red, green and blue are the low three bytes of successive {@code java.util.Random}
     * ints from seed 0, drawn column by column, sampled linear and repeating.
     */
    public static PackNoiseTexture generated(int size, String name) throws IOException {
        NativeImage pixels = new NativeImage(NativeImage.Format.RGBA, size, size, false);
        Random random = new Random(0);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                // NativeImage packs a texel as ABGR: red is the low byte.
                pixels.setPixelRGBA(x, y, random.nextInt() | 0xFF000000);
            }
        }
        return upload(pixels, name, "linear", "repeat");
    }

    private static PackNoiseTexture upload(NativeImage pixels, String name, String filter, String wrap)
            throws IOException {
        DynamicTexture texture = new DynamicTexture(pixels);
        try {
            VulkanImage image = ChimeraTextures.image(texture);
            if (image == null) {
                throw new IOException("noise texture has no Vulkan image: " + name);
            }
            // DynamicTexture samples nearest/repeat. A pack's water normals
            // difference neighbouring noise texels, so nearest made them blocky.
            ((ChimeraVulkanImageAccessor) image).chimera$setSampler(
                    ChimeraSamplers.flat(!"nearest".equals(filter), "clamp".equals(wrap)));
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
