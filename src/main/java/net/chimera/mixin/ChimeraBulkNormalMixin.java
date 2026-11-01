package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.chimera.render.ChimeraRenderer;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla's putBulkData normalizes a quad's normal when the pose's normals are untrusted
 * (PoseStack.Pose.transformNormal), as after a non-uniform scale: the hotbar's pickup bounce in
 * Gui.renderSlot, or a stretched display entity. VulkanMod Reforged's overwrite (VertexConsumerM)
 * does not, and its normal packer wraps a component past 1 to a negative one, so a popping block's
 * lit and shaded faces swapped. While a pack renders the world the normal is normalized as
 * vanilla's is; with no pack, Reforged's draws are left as they are.
 */
@Mixin(VertexConsumer.class)
public interface ChimeraBulkNormalMixin {
    @WrapOperation(method = "putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
            + "Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFFF[IIZ)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"))
    private void chimera$unitNormal(VertexConsumer consumer, float x, float y, float z, int color, float u, float v,
                                    int overlay, int light, float normalX, float normalY, float normalZ,
                                    Operation<Void> original, @Local(argsOnly = true) PoseStack.Pose pose) {
        if (ChimeraRenderer.packProjectionActive()
                && !((ChimeraPoseAccessor) (Object) pose).chimera$trustedNormals()) {
            Vector3f normal = new Vector3f(normalX, normalY, normalZ).normalize();
            original.call(consumer, x, y, z, color, u, v, overlay, light, normal.x, normal.y, normal.z);
            return;
        }
        original.call(consumer, x, y, z, color, u, v, overlay, light, normalX, normalY, normalZ);
    }
}
