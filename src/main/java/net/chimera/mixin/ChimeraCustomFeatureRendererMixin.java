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
import java.util.Map;

/**
 * Draws the held custom geometry: entity and block-entity geometry through their pack
 * families, and host-layout lane geometry with its submitter's entity id.
 */
@Mixin(CustomFeatureRenderer.class)
public abstract class ChimeraCustomFeatureRendererMixin {
    @Inject(method = "render", at = @At("RETURN"), require = 1)
    private void chimera$renderHeldGeometry(SubmitNodeCollection collection,
                                            MultiBufferSource.BufferSource bufferSource, CallbackInfo callback) {
        if (!(collection.getCustomGeometrySubmits() instanceof ChimeraCustomGeometryStorage storage)) {
            return;
        }
        try {
            chimera$renderFamily(ChimeraEntityBridge.Family.ENTITY, storage.chimera$entitySubmits(), bufferSource);
            chimera$renderFamily(ChimeraEntityBridge.Family.BLOCK, storage.chimera$blockSubmits(), bufferSource);
            chimera$renderLanes(storage.chimera$laneSubmits(), bufferSource);
        } finally {
            storage.chimera$entitySubmits().clear();
            storage.chimera$blockSubmits().clear();
            storage.chimera$laneSubmits().clear();
        }
    }

    /**
     * Draws host-layout lane geometry on the host source, one batch per run of submitters
     * sharing an entity id, each flushed while that id is the draw identity. Iris's fallback
     * entityId uniform carries the same value only when the batch happens to flush before
     * its renderer returns; every pack tested identifies the lightning bolt by entityId.
     */
    @Unique
    private static void chimera$renderLanes(Map<RenderType, List<ChimeraCustomGeometryStorage.HeldSubmit>> held,
                                            MultiBufferSource.BufferSource hostSource) {
        for (var entry : held.entrySet()) {
            RenderType type = entry.getKey();
            List<ChimeraCustomGeometryStorage.HeldSubmit> submits = entry.getValue();
            for (int start = 0, end; start < submits.size(); start = end) {
                int entityId = submits.get(start).entityId();
                end = start;
                // Requesting the buffer first flushes any pending host batch outside this identity.
                VertexConsumer consumer = hostSource.getBuffer(type);
                for (; end < submits.size() && submits.get(end).entityId() == entityId; end++) {
                    submits.get(end).renderer().render(submits.get(end).pose(), consumer);
                }
                ChimeraEntityBridge.beginDrawIdentity(entityId);
                try {
                    hostSource.endBatch(type);
                } finally {
                    ChimeraEntityBridge.endDrawIdentity();
                }
            }
        }
    }

    @Unique
    private static void chimera$renderFamily(ChimeraEntityBridge.Family family,
                                             Map<RenderType, List<ChimeraCustomGeometryStorage.HeldSubmit>> held,
                                             MultiBufferSource.BufferSource hostSource) {
        for (var entry : held.entrySet()) {
            RenderType type = entry.getKey();
            // A batch the family cannot admit is drawn by the host, never dropped.
            if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()
                    || !ChimeraFamilyDraw.emit(family, type,
                    source -> chimera$emit(source.getBuffer(type), entry.getValue(), true))) {
                chimera$emit(hostSource.getBuffer(type), entry.getValue(), false);
            }
        }
    }

    @Unique
    private static void chimera$emit(VertexConsumer consumer, List<ChimeraCustomGeometryStorage.HeldSubmit> submits,
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
