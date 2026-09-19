package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.vulkanmod.render.engine.VkTextureView;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns resource-pack material images for one renderer lifetime.
 *
 * <p>Fallback core first: two permanent 1x1 images with the contractual
 * flat texels, so material-capable programs always bind something valid.
 * Atlas companions, simple-texture companions, reload generations, and
 * animation uploads join this owner in later slices; the resolver contract
 * below already assumes them, so those slices add state without changing
 * how programs bind.</p>
 */
public final class MaterialMapOwner implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialMapOwner.class);

    /** Material sampler name, resource-pack suffix, and canonical flat texel. */
    public enum MaterialMapKind {
        NORMALS("normals", "_n", 0xFFFF7F7F),
        SPECULAR("specular", "_s", 0x00000000);

        private final String sampler;
        private final String suffix;
        private final int fallbackAbgr;

        MaterialMapKind(String sampler, String suffix, int fallbackAbgr) {
            this.sampler = sampler;
            this.suffix = suffix;
            this.fallbackAbgr = fallbackAbgr;
        }

        public String sampler() { return sampler; }
        public String suffix() { return suffix; }
        public int fallbackAbgr() { return fallbackAbgr; }

        public static MaterialMapKind forSampler(String sampler) {
            for (MaterialMapKind kind : values()) {
                if (kind.sampler.equals(sampler)) return kind;
            }
            return null;
        }
    }

    private final DynamicTexture normalsTexture;
    private final DynamicTexture specularTexture;
    private final VulkanImage normalsImage;
    private final VulkanImage specularImage;
    private boolean closed;

    private MaterialMapOwner(DynamicTexture normalsTexture, VulkanImage normalsImage,
            DynamicTexture specularTexture, VulkanImage specularImage) {
        this.normalsTexture = normalsTexture;
        this.normalsImage = normalsImage;
        this.specularTexture = specularTexture;
        this.specularImage = specularImage;
    }

    /** Test and forward seam: an owner over prebuilt images without GPU allocation. */
    public static MaterialMapOwner withImages(VulkanImage normalsImage, VulkanImage specularImage) {
        return new MaterialMapOwner(null, normalsImage, null, specularImage);
    }

    /**
     * Allocates the two permanent flat fallbacks on the calling thread.
     * Call only where GPU uploads are legal (pack install on the render
     * thread); never during a draw.
     */
    public static MaterialMapOwner create() {
        FlatImage normals = flat(MaterialMapKind.NORMALS);
        FlatImage specular;
        try {
            specular = flat(MaterialMapKind.SPECULAR);
        } catch (RuntimeException | Error failure) {
            normals.texture().close();
            throw failure;
        }
        LOGGER.info("[chimera] material maps: flat fallbacks ready normals={} specular={}",
                normals.image().getId(), specular.image().getId());
        return new MaterialMapOwner(normals.texture(), normals.image(),
                specular.texture(), specular.image());
    }

    private record FlatImage(DynamicTexture texture, VulkanImage image) {}

    private static FlatImage flat(MaterialMapKind kind) {
        NativeImage pixels = new NativeImage(1, 1, false);
        boolean owned = true;
        try {
            pixels.setPixelABGR(0, 0, kind.fallbackAbgr());
            DynamicTexture texture = new DynamicTexture(
                    () -> "chimera_material_flat_" + kind.sampler(), pixels);
            owned = false;
            texture.upload();
            GpuTextureView view = texture.getTextureView();
            if (!(view instanceof VkTextureView vulkanView)) {
                texture.close();
                throw new IllegalStateException("material fallback has no Vulkan view: " + kind.sampler());
            }
            VulkanImage image = vulkanView.texture().getVulkanImage();
            if (image == null) {
                texture.close();
                throw new IllegalStateException("material fallback has no Vulkan image: " + kind.sampler());
            }
            return new FlatImage(texture, image);
        } finally {
            if (owned) pixels.close();
        }
    }

    /**
     * Resolves one material sampler to its current image. Until atlas and
     * simple-texture companions land, every kind answers with its flat
     * fallback. Unknown names fail loudly so a bad manifest entry can
     * never silently bind the wrong map.
     */
    public VulkanImage image(String sampler) {
        return snapshot(sampler).image();
    }

    /** Full binding answer including the exact sampler override. */
    public ChimeraTextureBindingState.Snapshot snapshot(String sampler) {
        MaterialMapKind kind = MaterialMapKind.forSampler(sampler);
        if (kind == null) {
            throw new IllegalArgumentException("MATERIAL_MAP_UNKNOWN:" + sampler);
        }
        VulkanImage image = kind == MaterialMapKind.NORMALS ? normalsImage : specularImage;
        return new ChimeraTextureBindingState.Snapshot(image,
                image == null ? null : image.getSampler());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (normalsTexture != null) normalsTexture.close();
        if (specularTexture != null && specularTexture != normalsTexture) specularTexture.close();
    }
}
