package net.chimera.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import net.vulkanmod.render.texture.SpriteUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Gives the block atlas the mip levels Iris setups build (AtlasMipBlend) while a shader pack's
 * terrain directives are installed, and VulkanMod's own levels when none are (TASK-1000). Each
 * sprite keeps its levels in SpriteContents.byMipLevel, which its animation uploads from, so the
 * levels are rewritten there and the atlas uploaded again. VulkanMod's levels are kept to put back.
 * A resource reload stitches new sprites with VulkanMod's levels. Pack lifecycle points only record
 * the wanted state, and the frame boundary reconciles it, so an option change uploads the atlas at
 * most once. Render thread only.
 */
public final class PackAtlasMipmaps {
    private static final Logger LOGGER = LoggerFactory.getLogger(PackAtlasMipmaps.class);
    /** VulkanMod's levels 1.. of each rewritten sprite, by level; empty while VulkanMod's are in place. */
    private static final Map<SpriteContents, int[][]> HOST_LEVELS = new IdentityHashMap<>();
    private static TextureAtlas atlas;
    private static boolean wanted;
    private static boolean applied;

    private PackAtlasMipmaps() {}

    /** Whether an installed pack's terrain directives are in force. */
    public static void setPackInstalled(boolean installed) {
        wanted = installed;
    }

    /** A stitched atlas finished uploading; the block atlas's sprites now carry VulkanMod's levels. */
    public static void noteAtlasUpload(TextureAtlas uploaded) {
        if (!InventoryMenu.BLOCK_ATLAS.equals(uploaded.location())) return;
        atlas = uploaded;
        HOST_LEVELS.clear();
        applied = false;
    }

    /** At the frame boundary, gives the block atlas the levels the pack state wants. Never throws. */
    public static void reconcile() {
        if (atlas == null || wanted == applied) return;
        applied = wanted;
        long start = System.nanoTime();
        try {
            int sprites = applied ? rewrite() : restore();
            upload();
            LOGGER.info("[chimera] pack atlas mipmaps: {} for {} sprites ({} ms)",
                    applied ? "alpha-weighted levels, as Iris setups build" : "VulkanMod's levels restored",
                    sprites, (System.nanoTime() - start) / 1_000_000);
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] pack atlas mipmaps: {} failed", applied ? "rewrite" : "restore", failure);
        }
    }

    /** Rebuilds every mipmapped sprite's levels from its level 0, keeping VulkanMod's to restore. */
    private static int rewrite() {
        int sprites = 0;
        for (TextureAtlasSprite sprite : atlas.getTextures().values()) {
            NativeImage[] levels = sprite.contents().byMipLevel;
            if (levels.length < 2 || levels[0].format() != NativeImage.Format.RGBA) continue;
            int[][] host = new int[levels.length][];
            int[] above = pixels(levels[0]);
            for (int level = 1; level < levels.length; level++) {
                host[level] = pixels(levels[level]);
                int[] below = AtlasMipBlend.reduce(above, levels[level - 1].getWidth(),
                        levels[level - 1].getHeight(), levels[level].getWidth(), levels[level].getHeight());
                store(levels[level], below);
                above = below;
            }
            HOST_LEVELS.put(sprite.contents(), host);
            sprites++;
        }
        return sprites;
    }

    /** Puts VulkanMod's levels back into every rewritten sprite. */
    private static int restore() {
        int sprites = HOST_LEVELS.size();
        for (Map.Entry<SpriteContents, int[][]> entry : HOST_LEVELS.entrySet()) {
            NativeImage[] levels = entry.getKey().byMipLevel;
            int[][] host = entry.getValue();
            for (int level = 1; level < levels.length; level++) store(levels[level], host[level]);
        }
        HOST_LEVELS.clear();
        return sprites;
    }

    /**
     * Uploads every sprite's first frame at each level in one recording (TextureUploadBatch), as
     * TextureAtlas.upload does. VulkanMod uploads sprites only on a frame's last client tick
     * (MSpriteContents, SpriteUtil.shouldUpload), so the gate opens for this upload alone.
     */
    private static void upload() {
        VulkanImage image = ChimeraTextures.image(atlas);
        if (image == null) return;
        boolean gate = SpriteUtil.shouldUpload();
        SpriteUtil.setDoUpload(true);
        TextureUploadBatch<VulkanImage> batch = new TextureUploadBatch<>(TextureUploadBatch.GRAPHICS_QUEUE);
        batch.open();
        try {
            atlas.bind();
            batch.beforeUpload(image);
            for (TextureAtlasSprite sprite : atlas.getTextures().values()) sprite.uploadFirstFrame();
        } finally {
            batch.close();
            SpriteUtil.setDoUpload(gate);
        }
    }

    private static int[] pixels(NativeImage image) {
        int width = image.getWidth();
        int[] abgr = new int[width * image.getHeight()];
        for (int index = 0; index < abgr.length; index++) {
            abgr[index] = image.getPixelRGBA(index % width, index / width);
        }
        return abgr;
    }

    private static void store(NativeImage image, int[] abgr) {
        int width = image.getWidth();
        for (int index = 0; index < abgr.length; index++) {
            image.setPixelRGBA(index % width, index / width, abgr[index]);
        }
    }
}
