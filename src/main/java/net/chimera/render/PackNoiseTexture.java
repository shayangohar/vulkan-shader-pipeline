package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.chimera.mixin.ChimeraVulkanImageAccessor;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** One pack-owned sampled image: a pack PNG or game texture, or a texture Iris generates or leaves unbound. */
public final class PackNoiseTexture implements PackResourceOwner.LoadedTexture {
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

    /**
     * Reads the whole PNG, as Iris's CustomTextureManager does, and refuses it only past the device's
     * 2D image limit (PackImageLimits).
     */
    public static PackNoiseTexture load(InputStream input, String name, String filter, String wrap)
            throws IOException {
        if (input == null) {
            throw new IOException("texture input is missing");
        }
        NativeImage pixels = NativeImage.read(input);
        if (pixels.getWidth() < 1 || pixels.getHeight() < 1) {
            pixels.close();
            throw new IOException("noise image has no pixels");
        }
        String limit = PackImageLimits.deviceFailure("texture", 2, pixels.getWidth(), pixels.getHeight());
        if (limit != null) {
            pixels.close();
            throw new IOException(limit);
        }

        return upload(pixels, name, filter, wrap);
    }

    /**
     * The noise Iris generates for a pack without texture.noise: {@code size} squared opaque
     * texels whose red, green and blue are the low three bytes of successive {@code java.util.Random}
     * ints from seed 0, drawn column by column, sampled linear and repeating. Refused, as a pack PNG
     * is, past the device's 2D image limit (PackImageLimits), before any texel is made: a side of
     * 20000 would first take 1.6 GB and seconds of hashing.
     */
    public static PackNoiseTexture generated(int size, String name) throws IOException {
        String limit = PackImageLimits.deviceFailure("generated noise", 2, size, size);
        if (limit != null) throw new IOException(limit);
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

    /**
     * One texel of what GL samples from an unbound texture, (0, 0, 0, 1): Iris binds texture 0 where
     * Distant Horizons would supply its depth textures (PackResourcePlan.GENERATED_UNBOUND).
     */
    public static PackNoiseTexture unbound(String name) throws IOException {
        NativeImage pixels = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
        pixels.setPixelRGBA(0, 0, 0xFF000000);
        return upload(pixels, name, "nearest", "clamp");
    }

    private static PackNoiseTexture upload(NativeImage pixels, String name, String filter, String wrap)
            throws IOException {
        DynamicTexture texture;
        try {
            texture = new DynamicTexture(pixels);
        } catch (RuntimeException failure) {
            // No texture holds the pixels to free them with (audit review D, image factory); closing
            // a NativeImage twice frees it once.
            pixels.close();
            throw failure;
        }
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
