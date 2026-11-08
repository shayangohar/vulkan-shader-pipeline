package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.FamilyBufferSource;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One banner's patterns are one of Iris's batch groups (batchedentityrendering
 * MixinBannerRenderer), unless an entity's render already holds one: the base draws before the
 * patterns layered on it, which the level's batch order would otherwise put first (IrisBatchOrder).
 */
@Mixin(BannerRenderer.class)
public abstract class ChimeraBannerRendererMixin {
    private static final String RENDER_PATTERNS = "renderPatterns(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/model/geom/ModelPart;"
            + "Lnet/minecraft/client/resources/model/Material;ZLnet/minecraft/world/item/DyeColor;"
            + "Lnet/minecraft/world/level/block/entity/BannerPatternLayers;Z)V";

    /** Whether this banner's render opened the group it closes. */
    @Unique private static boolean chimera$groupOpened;

    @Inject(method = RENDER_PATTERNS, at = @At("HEAD"), require = 1)
    private static void chimera$openGroup(PoseStack poses, MultiBufferSource buffers, int light, int overlay,
                                          ModelPart flag, Material base, boolean banner, DyeColor color,
                                          BannerPatternLayers patterns, boolean glint, CallbackInfo callback) {
        chimera$groupOpened = FamilyBufferSource.startLevelGroup();
    }

    @Inject(method = RENDER_PATTERNS, at = @At("RETURN"), require = 1)
    private static void chimera$closeGroup(PoseStack poses, MultiBufferSource buffers, int light, int overlay,
                                           ModelPart flag, Material base, boolean banner, DyeColor color,
                                           BannerPatternLayers patterns, boolean glint, CallbackInfo callback) {
        if (chimera$groupOpened) FamilyBufferSource.endLevelGroup();
        chimera$groupOpened = false;
    }
}
