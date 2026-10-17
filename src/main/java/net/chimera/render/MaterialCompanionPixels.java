package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;

/**
 * Builds a material companion's pixels: decodes each sprite's normal or specular map, places it in
 * the albedo atlas layout, builds its mip chain by the map's own reduction, and makes the GPU images.
 */
final class MaterialCompanionPixels {
    private MaterialCompanionPixels() {}

    static boolean detectLabPbr(MaterialMapOwner.ResourceLookup lookup) {
        try (InputStream in = lookup.open("minecraft", "optifine/texture.properties")
                .orElse(null)) {
            if (in == null) return false;
            return MaterialMapPixels.detectLabPbr(in);
        } catch (IOException | RuntimeException failure) {
            MaterialMapOwner.LOGGER.warn("[chimera] material maps: texture.properties unreadable, "
                    + "assuming linear mips", failure);
            return false;
        }
    }

    /**
     * One sprite whose material file decoded cleanly: its logical mip chain
     * as the slot shows it at build time, plus its animation when the map
     * carries its own cycling schedule.
     */
    record PlacedSprite(MaterialMapOwner.AtlasSprite sprite, int[][] chain, MaterialAnimation animation) {}

    static List<PlacedSprite> decodeKind(List<MaterialMapOwner.AtlasSprite> sprites,
            MaterialMapOwner.MaterialMapKind kind,
            MaterialMapOwner.ResourceLookup lookup, MaterialMapOwner.MapImageDecoder decoder, int mipLevels,
                    boolean labPbr) {
        List<PlacedSprite> placed = new ArrayList<>();
        for (MaterialMapOwner.AtlasSprite sprite : sprites) {
            if (sprite.padX() != sprite.padY()) {
                MaterialMapOwner.LOGGER.warn("[chimera] material maps: skipping {} with asymmetric padding {}x{}",
                        sprite.id(), sprite.padX(), sprite.padY());
                continue;
            }
            if (sprite.padX() < 0) {
                MaterialMapOwner.LOGGER.warn("[chimera] material maps: skipping {} with negative padding {}",
                        sprite.id(), sprite.padX());
                continue;
            }
            if (sprite.width() <= 0 || sprite.height() <= 0) {
                MaterialMapOwner.LOGGER.warn("[chimera] material maps: skipping {} with empty slot {}x{}",
                        sprite.id(), sprite.width(), sprite.height());
                continue;
            }
            String resource = MaterialMapPixels.siblingResource(
                    sprite.namespace(), sprite.path(), kind.suffix());
            int separator = resource.indexOf(':');
            String namespace = resource.substring(0, separator);
            String path = resource.substring(separator + 1);
            try {
                MaterialMapOwner.DecodedImage decoded;
                try (InputStream in = lookup.open(namespace, path).orElse(null)) {
                    if (in == null) continue;
                    decoded = decoder.decode(in);
                }
                MaterialMapPixels.checkSize(decoded.width(), decoded.height(), resource);
                MaterialAnimationSchedule schedule = readSchedule(lookup, namespace, path,
                        decoded.width(), decoded.height());
                placed.add(place(sprite, kind, decoded, schedule, mipLevels, labPbr));
            } catch (IOException | IllegalArgumentException failure) {
                MaterialMapOwner.LOGGER.warn("[chimera] material maps: skipping {} for {}: {}",
                        resource, sprite.id(), failure.toString());
            }
        }
        return placed;
    }

    /** The map's own {@code .mcmeta} animation table, or null when it has none. */
    static MaterialAnimationSchedule readSchedule(MaterialMapOwner.ResourceLookup lookup, String namespace,
            String path, int width, int height) throws IOException {
        try (InputStream in = lookup.open(namespace, path + ".mcmeta").orElse(null)) {
            if (in == null) return null;
            return MaterialAnimationSchedule.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), width, height);
        }
    }

    /**
     * Places one decoded map in its slot. Without a schedule the whole image
     * is the map, as for a static sprite. A single-entry schedule shows the
     * strip's first frame. A cycling schedule keeps every frame it names as
     * its own scaled mip chain and starts on its first entry.
     */
    static PlacedSprite place(MaterialMapOwner.AtlasSprite sprite, MaterialMapOwner.MaterialMapKind kind,
            MaterialMapOwner.DecodedImage decoded,
            MaterialAnimationSchedule schedule, int mipLevels, boolean labPbr) {
        if (schedule == null) {
            return new PlacedSprite(sprite, chain(kind, labPbr, decoded.pixels(), decoded.width(),
                    decoded.height(), sprite, mipLevels), null);
        }
        if (!schedule.animated()) {
            int[] first = schedule.crop(decoded.pixels(), decoded.width(), 0);
            return new PlacedSprite(sprite, chain(kind, labPbr, first, schedule.frameWidth(),
                    schedule.frameHeight(), sprite, mipLevels), null);
        }
        int[][][] frameLevels = new int[schedule.frameCount(decoded.height())][][];
        for (MaterialAnimation.Frame frame : schedule.frames()) {
            if (frameLevels[frame.index()] != null) continue;
            int[] pixels = schedule.crop(decoded.pixels(), decoded.width(), frame.index());
            frameLevels[frame.index()] = chain(kind, labPbr, pixels, schedule.frameWidth(),
                    schedule.frameHeight(), sprite, mipLevels);
        }
        MaterialAnimation animation = new MaterialAnimation(schedule.frames(), schedule.interpolate(),
                frameLevels, sprite.width(), sprite.height());
        int[][] initial = new int[mipLevels][];
        for (int level = 0; level < mipLevels; level++) {
            initial[level] = animation.level(level);
        }
        return new PlacedSprite(sprite, initial, animation);
    }

    /**
     * One frame's logical mip chain: scaled to the slot's logical size, then
     * reduced sprite-locally level by level with the kind's mip policy.
     */
    static int[][] chain(MaterialMapOwner.MaterialMapKind kind, boolean labPbr, int[] pixels, int width,
            int height, MaterialMapOwner.AtlasSprite sprite, int mipLevels) {
        int[][] chain = new int[mipLevels][];
        chain[0] = MaterialMapPixels.scale(pixels, width, height, sprite.width(), sprite.height());
        for (int level = 1; level < mipLevels; level++) {
            chain[level] = reduceFor(kind, labPbr, chain[level - 1],
                    MaterialMapPixels.levelSize(sprite.width(), level - 1),
                    MaterialMapPixels.levelSize(sprite.height(), level - 1),
                    MaterialMapPixels.levelSize(sprite.width(), level),
                    MaterialMapPixels.levelSize(sprite.height(), level));
        }
        return chain;
    }

    /**
     * Builds one exact-layout mip chain per kind. Each sprite contributes
     * its own padded image: its logical level grows a replicated border of
     * {@code pad >> level}, and the complete padded image lands at the slot
     * origin shifted by level. The canvas starts at fallback, so missing
     * sprites leave their whole padded slot untouched. Sprites never
     * negotiate shared pixels, so no occupancy map or ring algorithm exists.
     */
    static List<MaterialMapOwner.LevelPixels> buildLevels(MaterialMapOwner.AtlasUpload upload,
            MaterialMapOwner.MaterialMapKind kind,
            List<PlacedSprite> placed) {
        List<MaterialMapOwner.LevelPixels> levels = new ArrayList<>(upload.mipLevels());
        for (int level = 0; level < upload.mipLevels(); level++) {
            int canvasWidth = MaterialMapPixels.levelSize(upload.width(), level);
            int canvasHeight = MaterialMapPixels.levelSize(upload.height(), level);
            int[] canvas = new int[canvasWidth * canvasHeight];
            Arrays.fill(canvas, kind.fallbackAbgr());
            for (PlacedSprite sprite : placed) {
                int pad = sprite.sprite().padX() >> level;
                int logicalWidth = MaterialMapPixels.levelSize(sprite.sprite().width(), level);
                int logicalHeight = MaterialMapPixels.levelSize(sprite.sprite().height(), level);
                int[] padded = MaterialMapPixels.padReplicate(
                        sprite.chain()[level], logicalWidth, logicalHeight, pad);
                MaterialMapPixels.placeRect(canvas, canvasWidth, canvasHeight,
                        sprite.sprite().x() >> level, sprite.sprite().y() >> level,
                        padded, logicalWidth + pad * 2, logicalHeight + pad * 2);
            }
            levels.add(new MaterialMapOwner.LevelPixels(canvas, canvasWidth, canvasHeight));
        }
        return levels;
    }

    static int[] reduceFor(MaterialMapOwner.MaterialMapKind kind, boolean labPbr, int[] src,
            int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        if (kind == MaterialMapOwner.MaterialMapKind.SPECULAR && labPbr) {
            return MaterialMapPixels.reduceLabPbr(src, srcWidth, srcHeight, dstWidth, dstHeight);
        }
        return MaterialMapPixels.reduceLinear(src, srcWidth, srcHeight, dstWidth, dstHeight);
    }

    /** Production decoder over {@code NativeImage}; the caller closes the stream. */
    public static MaterialMapOwner.DecodedImage decodeNative(InputStream in) throws IOException {
        NativeImage image = NativeImage.read(in);
        try {
            MaterialMapPixels.checkSize(image.getWidth(), image.getHeight(), "decoded");
            return new MaterialMapOwner.DecodedImage(image.getPixelsRGBA(), image.getWidth(), image.getHeight());
        } finally {
            image.close();
        }
    }

    /** One RGBA8 image with a level per mip, each uploaded from its ABGR texels. */
    static MaterialMapOwner.CompanionSet buildGpuCompanions(String location, MaterialMapOwner.MaterialMapKind kind,
            int width, int height, List<MaterialMapOwner.LevelPixels> levels) {
        VulkanImage image = VulkanImage.builder(width, height)
                .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                .setMipLevels(levels.size())
                .createVulkanImage();
        boolean installed = false;
        try {
            for (int level = 0; level < levels.size(); level++) {
                MaterialMapOwner.LevelPixels pixels = levels.get(level);
                upload(image, level, 0, 0, pixels.width(), pixels.height(), pixels.pixels());
            }
            installed = true;
            return new GpuCompanionSet(image);
        } finally {
            if (!installed) image.free();
        }
    }

    record GpuCompanionSet(VulkanImage image) implements MaterialMapOwner.CompanionSet {
        @Override
        public void writeRegion(int level, int x, int y, int width, int height, int[] pixels) {
            upload(image, level, x, y, width, height, pixels);
        }

        @Override
        public void close() {
            image.free();
        }
    }

    /**
     * Writes ABGR texels (RGBA bytes in memory) into one level region. VulkanMod copies them into
     * its staging buffer during the call, so the buffer is freed as soon as it returns.
     */
    private static void upload(VulkanImage image, int level, int x, int y, int width, int height, int[] pixels) {
        ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length * Integer.BYTES);
        try {
            buffer.asIntBuffer().put(pixels);
            image.uploadSubTextureAsync(level, width, height, x, y, 0, 0, width, buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }
}
