package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraFamilyDraw;
import net.chimera.render.ChimeraRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
import net.fabricmc.fabric.api.renderer.v1.render.BlockVertexConsumerProvider;
import net.fabricmc.fabric.api.renderer.v1.render.FabricBlockModelRenderer;
import net.fabricmc.fabric.impl.renderer.BatchingRenderCommandQueueExtension;
import net.fabricmc.fabric.impl.renderer.ExtendedBlockStateModelCommand;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.feature.BlockFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws block models and blocks that entities submit (item frames, falling blocks,
 * TNT, minecart contents, mooshroom mushrooms, the snow golem's head) with the pack
 * family of their submitter, as Iris does: their render types use the entity
 * pipelines, which Iris maps to gbuffers_entities or the block program. Drawn by the
 * host they wrote only colour into a buffer deferred packs never show, so item frames
 * vanished. Outline passes and moving (piston) blocks keep their host paths.
 *
 * <p>Fabric API's renderer redirects some of these submissions (item frames among
 * them) to its own block-state-model commands, which it draws when this method
 * returns. Those commands are taken over here before Fabric's handler runs: a world
 * entity's command is drawn through its family, and removed from Fabric's list so it
 * is not drawn twice. This reads Fabric's internal command list
 * (BatchingRenderCommandQueueExtension), so a Fabric API update that changes it
 * fails the build rather than silently dropping frames.</p>
 */
@Mixin(BlockFeatureRenderer.class)
public abstract class ChimeraBlockFeatureRendererMixin {
    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;"
                    + "renderModel(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                    + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"
                    + "Lnet/minecraft/client/renderer/block/model/BlockStateModel;FFFII)V",
            ordinal = 0), require = 1)
    private void chimera$packBlockModel(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            BlockStateModel model,
            float r,
            float g,
            float b,
            int lightCoords,
            int overlayCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.BlockModelSubmit submit
    ) {
        RenderType renderType = submit.renderType();
        if (!chimera$packed((Object) submit, renderType, source -> original.call(pose,
                source.getBuffer(renderType), model, r, g, b, lightCoords, overlayCoords))) {
            original.call(pose, consumer, model, r, g, b, lightCoords, overlayCoords);
        }
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;"
                    + "renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            ordinal = 0), require = 1)
    private void chimera$packBlock(
            BlockRenderDispatcher dispatcher,
            BlockState state,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int lightCoords,
            int overlayCoords,
            Operation<Void> original,
            @Local SubmitNodeStorage.BlockSubmit submit
    ) {
        // renderSingleBlock draws one model with this one render type.
        RenderType renderType = ItemBlockRenderTypes.getRenderType(state);
        if (!chimera$packed((Object) submit, renderType, source -> original.call(dispatcher, state,
                poseStack, source, lightCoords, overlayCoords))) {
            original.call(dispatcher, state, poseStack, buffers, lightCoords, overlayCoords);
        }
    }

    @Inject(method = "render", at = @At("HEAD"), require = 1)
    private void chimera$packFabricBlockStateModels(
            SubmitNodeCollection collection,
            MultiBufferSource.BufferSource bufferSource,
            BlockRenderDispatcher dispatcher,
            OutlineBufferSource outlineBufferSource,
            CallbackInfo callback
    ) {
        if (!((Object) collection instanceof BatchingRenderCommandQueueExtension fabric)
                || !ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()) {
            return;
        }
        Iterator<ExtendedBlockStateModelCommand> commands =
                fabric.fabric_getExtendedBlockStateModelCommands().iterator();
        while (commands.hasNext()) {
            ExtendedBlockStateModelCommand command = commands.next();
            // Fabric draws an outlined command into the outline source as well; it keeps it.
            if (command.outlineColor() == 0 && chimera$drawFabricCommand(command, bufferSource)) {
                commands.remove();
            }
        }
    }

    /**
     * Draws one Fabric command, render type by render type, through the family of its
     * submitter. Returns false, having drawn nothing, when any of its render types must
     * stay on the host, so Fabric draws the whole command as before.
     */
    private static boolean chimera$drawFabricCommand(
            ExtendedBlockStateModelCommand command,
            MultiBufferSource.BufferSource hostSource
    ) {
        Set<RenderType> types = new LinkedHashSet<>();
        chimera$renderFabric(command, layer -> {
            types.add(command.renderLayerFunction().apply(layer));
            return ChimeraFamilyDraw.DISCARD;
        });
        for (RenderType type : types) {
            String rejected = chimera$rejection(command, type);
            if (rejected != null) {
                ChimeraEntityBridge.noteSubmissionKeptOnHost(rejected, type);
                return false;
            }
        }
        for (RenderType type : types) {
            if (!chimera$packed(command, type, source -> chimera$renderFabric(command,
                    layer -> command.renderLayerFunction().apply(layer) == type ? source.getBuffer(type)
                            : ChimeraFamilyDraw.DISCARD))) {
                // The family refused this batch; draw this render type as Fabric would.
                chimera$renderFabric(command, layer -> command.renderLayerFunction().apply(layer) == type
                        ? hostSource.getBuffer(type) : ChimeraFamilyDraw.DISCARD);
            }
        }
        return true;
    }

    private static void chimera$renderFabric(
            ExtendedBlockStateModelCommand command,
            BlockVertexConsumerProvider provider
    ) {
        FabricBlockModelRenderer.render(command.matricesEntry(), provider, command.model(), command.r(),
                command.g(), command.b(), command.lightCoords(), command.overlayCoords(), command.blockView(),
                command.pos(), command.state());
    }

    /** The first check that keeps a submission on the host, or null when its family can draw it. */
    private static String chimera$rejection(Object submit, RenderType renderType) {
        if (!(submit instanceof ChimeraEntitySubmission)) return "no-identity";
        SubmissionIdentity identity = SubmissionIdentity.ofWorldNode(submit);
        if (identity == null) return "not-world-entity";
        if (ChimeraEntityBridge.isDrawActive()) return "draw-active";
        if (!ChimeraEntityBridge.supportsSubmissionFamily(identity.family())) return "family-unsupported";
        if (!ChimeraEntityBridge.supportsWorldPipeline(renderType.pipeline())) return "pipeline-unsupported";
        if (!ChimeraEntityBridge.supportsWorldRenderType(renderType)) return "format-unsupported";
        return null;
    }

    /** Emits the submission through its family; false when the host must draw it. */
    private static boolean chimera$packed(
            Object submit,
            RenderType renderType,
            Consumer<MultiBufferSource.BufferSource> body
    ) {
        if (!ChimeraRenderer.segmentsActive() || !ChimeraEntityBridge.isInstalled()) {
            return false;
        }
        String rejected = chimera$rejection(submit, renderType);
        if (rejected != null) {
            ChimeraEntityBridge.noteSubmissionKeptOnHost(rejected, renderType);
            return false;
        }
        return ChimeraFamilyDraw.emitSubmission(SubmissionIdentity.ofWorldNode(submit), renderType, body);
    }
}
