package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.FlameFeatureRenderer;
import net.minecraft.client.resources.model.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Draws burning-entity fire with the entity family, as Iris does: its render type,
 * Sheets.cutoutBlockSheet(), is ENTITY_CUTOUT on the block atlas, which IrisPipelines draws
 * with gbuffers_entities and in the shadow pass with shadow_entities. FlameFeatureRenderer
 * writes straight into the dispatcher's buffer source, so the model storage never separates
 * it: drawn by the host, the fire wrote only colour into an image deferred packs never show,
 * and the shadow pass left it out. Iris tags the fire with the pack's entity.properties id for
 * minecraft:entity_flame (MixinFlameFeatureRenderer).
 */
@Mixin(FlameFeatureRenderer.class)
public abstract class ChimeraFlameFeatureRendererMixin {
    @WrapMethod(method = "render")
    private void chimera$packFlames(SubmitNodeCollection collection, MultiBufferSource.BufferSource buffers,
                                    AtlasManager atlases, Operation<Void> original) {
        if (collection.getFlameSubmits().isEmpty() || !ChimeraRenderer.segmentsActive()
                || !ChimeraEntityBridge.isInstalled() || !ChimeraEntityBridge.isWorldSubmissionWindow()
                || ChimeraEntityBridge.isDrawActive()) {
            original.call(collection, buffers, atlases);
            return;
        }
        int flameId = ChimeraEntityBridge.flameEntityId();
        boolean packed = ChimeraFamilyDraw.emit(ChimeraEntityBridge.Family.ENTITY, Sheets.cutoutBlockSheet(),
                source -> {
                    ChimeraEntityBridge.beginModelEntity(flameId, 0);
                    try {
                        original.call(collection, source, atlases);
                    } finally {
                        ChimeraEntityBridge.endModelEntity();
                    }
                });
        // A batch the family cannot admit is drawn by the host, never dropped.
        if (!packed) original.call(collection, buffers, atlases);
    }
}
