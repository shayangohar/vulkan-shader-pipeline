package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.chimera.render.shader.ChimeraEntitySubmission;
import net.chimera.render.shader.SubmissionIdentity;
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
    private SubmissionIdentity chimera$identity;

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
        this.chimera$identity = SubmissionIdentity.current();
    }

    @Override
    public SubmissionIdentity chimera$identity() {
        return chimera$identity;
    }
}
