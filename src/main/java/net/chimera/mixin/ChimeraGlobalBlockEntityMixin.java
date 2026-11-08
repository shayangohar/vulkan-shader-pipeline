package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.vulkanmod.render.chunk.build.task.BuildTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

import java.util.List;

/**
 * Keeps a block entity that renders off-screen (a beacon, an end gateway, a structure block) out of
 * its section's list in the terrain Chimera meshes for a pack, as NeoForge's SectionCompiler and
 * Sodium do: only the level's global set renders it. VulkanMod lists it in both (vanilla's
 * MC-112730), so its section loop and the global loop each drew it, as did the shadow casters, and
 * a beacon's blended glow came out twice as opaque as under Iris. VulkanMod's own meshes, with no
 * pack, keep its lists.
 */
@Mixin(value = BuildTask.class, remap = false)
public abstract class ChimeraGlobalBlockEntityMixin {
    /**
     * handleBlockEntity's add to the section's list: the first add after it reads that list, so the
     * global list's add is never taken for it, in whichever order VulkanMod makes the two.
     */
    @WrapOperation(method = "handleBlockEntity",
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lnet/vulkanmod/render/chunk/build/task/CompileResult;blockEntities:Ljava/util/List;")))
    private boolean chimera$sectionBlockEntity(List<BlockEntity> sectionList, Object blockEntity,
                                               Operation<Boolean> original,
                                               @Local BlockEntityRenderer<BlockEntity> renderer) {
        if (ChimeraTerrainPipelines.vanillaSectionCompile()
                && renderer.shouldRenderOffScreen((BlockEntity) blockEntity)) return false;
        return original.call(sectionList, blockEntity);
    }
}
