package net.chimera.mixin;

import net.chimera.render.ChimeraRenderer;
import net.chimera.render.MaterialMapOwner;
import net.chimera.render.MaterialMapPixels;
import net.chimera.render.shader.AtlasSizes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.data.AtlasIds;
import net.vulkanmod.render.engine.VkTextureView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports stitched atlas layouts to the material owner, and each atlas's
 * size to {@link AtlasSizes} for the {@code atlasSize} uniform.
 *
 * <p>At {@code TextureAtlas.upload} return the atlas texture is fully
 * uploaded, so the observation copies only immutable metadata (atlas
 * location, base-image identity, sprite coordinates plus recovered
 * padding) into the owner's pending queue and returns. Sprite origins are
 * padded slot origins while content dimensions are logical, so the padding
 * is recovered from the UV edge scaled to atlas texels. Sprites whose X
 * and Y padding disagree are isolated here with a named warning because
 * only square borders are supported. Decoding and GPU companion allocation
 * happen later at the safe pump seam, never inside this call. The
 * injection never throws out: a failure warns and leaves vanilla rendering
 * untouched.</p>
 */
@Mixin(TextureAtlas.class)
public abstract class ChimeraAtlasMaterialMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChimeraAtlasMaterialMixin.class);

    @Inject(method = "upload", at = @At("RETURN"), require = 1)
    private void chimera$noteAtlasUpload(SpriteLoader.Preparations preparations, CallbackInfo info) {
        try {
            TextureAtlas atlas = (TextureAtlas) (Object) this;
            var view = atlas.getTextureView();
            if (!(view instanceof VkTextureView vulkanView)
                    || vulkanView.texture().getVulkanImage() == null) {
                LOGGER.warn("[chimera] material maps: atlas {} has no Vulkan image, skipping companions",
                        atlas.location());
                return;
            }
            AtlasSizes.note(vulkanView.texture().getVulkanImage(), preparations.width(),
                    preparations.height(), chimera$isBlockAtlas(atlas));
            var pass = ChimeraRenderer.getMainPass();
            if (pass == null) return;
            MaterialMapOwner owner = pass.materialMaps();
            if (owner == null) return;
            List<MaterialMapOwner.AtlasSprite> sprites =
                    new ArrayList<>(preparations.regions().size());
            for (var entry : preparations.regions().entrySet()) {
                var id = entry.getKey();
                var sprite = entry.getValue();
                int padX = MaterialMapPixels.recoverPad(
                        sprite.getU0(), preparations.width(), sprite.getX());
                int padY = MaterialMapPixels.recoverPad(
                        sprite.getV0(), preparations.height(), sprite.getY());
                if (padX != padY) {
                    LOGGER.warn("[chimera] material maps: skipping {} with asymmetric padding {}x{}",
                            id, padX, padY);
                    continue;
                }
                sprites.add(new MaterialMapOwner.AtlasSprite(id.toString(), id.getNamespace(),
                        id.getPath(), sprite.getX(), sprite.getY(),
                        sprite.contents().width(), sprite.contents().height(), padX, padY));
            }
            owner.noteAtlasUpload(new MaterialMapOwner.AtlasUpload(atlas.location().toString(),
                    preparations.width(), preparations.height(), preparations.mipLevel() + 1,
                    List.copyOf(sprites)), vulkanView.texture().getVulkanImage());
        } catch (RuntimeException failure) {
            LOGGER.warn("[chimera] material maps: atlas observation failed", failure);
        }
    }

    private static boolean chimera$isBlockAtlas(TextureAtlas atlas) {
        try {
            return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS) == atlas;
        } catch (RuntimeException notRegistered) {
            return false;
        }
    }
}
