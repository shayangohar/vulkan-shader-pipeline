package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

/**
 * The level renderer's entity drawing and global block entities, for the shadow casters, its tick
 * count, which Iris's cloudTime reads, its culling frustums, which the section setup before the
 * sky takes, and its blindness and darkness check.
 */
@Mixin(LevelRenderer.class)
public interface ChimeraLevelRendererAccessor {
    @Accessor("ticks")
    int chimera$ticks();

    /** The frustum the debug key froze, or null. */
    @Accessor("capturedFrustum")
    Frustum chimera$capturedFrustum();

    @Accessor("cullingFrustum")
    Frustum chimera$cullingFrustum();

    /** Guard reads with the set's own monitor, as LevelRenderer does. */
    @Accessor("globalBlockEntities")
    Set<BlockEntity> chimera$globalBlockEntities();

    @Invoker("renderEntity")
    void chimera$renderEntity(Entity entity, double cameraX, double cameraY, double cameraZ, float partialTick,
                              PoseStack poses, MultiBufferSource buffers);

    /** Whether the camera's entity has blindness or darkness, which hide the sky (ChimeraSunriseColorMixin). */
    @Invoker("doesMobEffectBlockSky")
    boolean chimera$doesMobEffectBlockSky(Camera camera);
}
