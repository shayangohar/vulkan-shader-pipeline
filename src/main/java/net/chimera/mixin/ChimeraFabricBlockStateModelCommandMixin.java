package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.fabricmc.fabric.impl.renderer.ExtendedBlockStateModelCommand;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/**
 * Stores the world-submission identity on Fabric API's block-state-model command, as
 * vanilla submissions do. Fabric's renderer redirects item frames, mooshroom mushrooms
 * and the snow golem's head from SubmitNodeCollector.submitBlockModel to
 * submitBlockStateModel, so those draws never become a vanilla BlockModelSubmit.
 */
@Mixin(ExtendedBlockStateModelCommand.class)
public abstract class ChimeraFabricBlockStateModelCommandMixin implements ChimeraEntitySubmission {
    @Unique
    private boolean chimera$worldEntity;
    @Unique
    private int chimera$entityId;
    @Unique
    private int chimera$blockEntityId;
    @Unique
    private int chimera$family;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$capture(
            PoseStack.Pose matricesEntry,
            Function<ChunkSectionLayer, RenderType> renderLayerFunction,
            BlockStateModel model,
            float r,
            float g,
            float b,
            int lightCoords,
            int overlayCoords,
            int outlineColor,
            BlockAndTintGetter blockView,
            BlockPos pos,
            BlockState state,
            CallbackInfo callback
    ) {
        this.chimera$worldEntity = ChimeraEntityBridge.isSubmittingEntity();
        this.chimera$entityId = ChimeraEntityBridge.currentEntityId();
        this.chimera$blockEntityId = ChimeraEntityBridge.currentBlockEntityId();
        this.chimera$family = ChimeraEntityBridge.currentSubmissionFamily();
    }

    @Override
    public boolean chimera$isWorldEntity() {
        return chimera$worldEntity;
    }

    @Override
    public int chimera$blockEntityId() {
        return chimera$blockEntityId;
    }

    @Override
    public int chimera$entityId() {
        return chimera$entityId;
    }

    @Override
    public int chimera$family() {
        return chimera$family;
    }
}
