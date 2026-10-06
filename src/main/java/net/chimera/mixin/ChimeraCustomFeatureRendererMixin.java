package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraCustomGeometryStorage;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Draws separated block-entity custom geometry through the pack block family. */
@Mixin(CustomFeatureRenderer.class)
public abstract class ChimeraCustomFeatureRendererMixin {
    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void chimera$renderBlockGeometry(SubmitNodeCollection collection,
                                             MultiBufferSource.BufferSource bufferSource, CallbackInfo callback) {
        if (!(collection.getCustomGeometrySubmits() instanceof ChimeraCustomGeometryStorage storage)
                || storage.chimera$blockSubmits().isEmpty()) {
            return;
        }
        try {
            for (var entry : storage.chimera$blockSubmits().entrySet()) {
                RenderType type = entry.getKey();
                // A batch the family cannot admit is drawn by the host, never dropped.
                if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()
                        || !ChimeraFamilyDraw.emit(ChimeraEntityBridge.Family.BLOCK, type,
                        source -> chimera$emit(source.getBuffer(type), entry.getValue(), true))) {
                    chimera$emit(bufferSource.getBuffer(type), entry.getValue(), false);
                }
            }
        } finally {
            storage.chimera$blockSubmits().clear();
        }
    }

    @Unique
    private static void chimera$emit(VertexConsumer consumer, List<ChimeraCustomGeometryStorage.BlockSubmit> submits,
                                     boolean pack) {
        for (var submit : submits) {
            if (pack) ChimeraEntityBridge.beginModelEntity(submit.entityId(), submit.blockEntityId());
            try {
                submit.renderer().render(submit.pose(), consumer);
            } finally {
                if (pack) ChimeraEntityBridge.endModelEntity();
            }
        }
    }
}
