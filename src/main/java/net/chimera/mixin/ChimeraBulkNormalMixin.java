package net.chimera.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import net.chimera.render.ChimeraRenderer;
import net.vulkanmod.render.vertex.VertexUtil;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla's putBulkData normalizes a quad's normal when the pose's normals are untrusted
 * (PoseStack.Pose.transformNormal), as after a non-uniform scale: the hotbar's pickup bounce in
 * Gui.renderSlot, or a stretched display entity. VulkanMod Reforged's overwrite (VertexConsumerM)
 * does not, and its packNormal wraps a component past 1 to a negative one, so a popping block's lit
 * and shaded faces swapped. Every other vanilla caller passes a unit normal, so while a pack renders
 * the world, a normal that is not unit length is normalized where Reforged's BufferBuilder.addVertex
 * packs it. With no pack, Reforged's draws are left as they are.
 *
 * <p>Mixin 0.8.7 injects into no interface method (VertexConsumer.putBulkData), and into a body
 * another mixin merged (Reforged's BufferBuilderM overwrite) only from a later priority.</p>
 */
@Mixin(value = BufferBuilder.class, priority = 1100)
public abstract class ChimeraBulkNormalMixin {
    @Redirect(method = "addVertex(FFFIFFIIFFF)V", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/render/vertex/VertexUtil;packNormal(FFF)I", remap = false))
    private int chimera$unitNormal(float x, float y, float z) {
        float lengthSquared = x * x + y * y + z * z;
        if (ChimeraRenderer.packProjectionActive() && lengthSquared > 0.0f
                && Math.abs(lengthSquared - 1.0f) > 1.0e-4f) {
            Vector3f normal = new Vector3f(x, y, z).normalize();
            return VertexUtil.packNormal(normal.x, normal.y, normal.z);
        }
        return VertexUtil.packNormal(x, y, z);
    }
}
