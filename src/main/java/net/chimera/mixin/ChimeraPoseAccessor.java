package net.chimera.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Whether a pose's normal matrix keeps unit lengths (PoseStack.scale and mulPose clear it). */
@Mixin(PoseStack.Pose.class)
public interface ChimeraPoseAccessor {
    @Accessor("trustedNormals")
    boolean chimera$trustedNormals();
}
