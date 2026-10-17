package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/** The level renderer's entity drawing and global block entities, for the shadow casters. */
@Mixin(LevelRenderer.class)
public interface ChimeraLevelRendererAccessor {
    /** Guard reads with the set's own monitor, as LevelRenderer does. */
    @Accessor("globalBlockEntities")
    Set<BlockEntity> chimera$globalBlockEntities();

    @Invoker("renderEntity")
    void chimera$renderEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float partialTick,
                              PoseStack poses, MultiBufferSource buffers);
}
