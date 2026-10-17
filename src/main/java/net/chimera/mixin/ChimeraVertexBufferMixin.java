package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.PackHostDraws;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.render.VBO;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Vertex-buffer host draws (the sky, stars, void and clouds, and the pack horizon) run under the
 * pack program that serves them (PackHostDraws). VulkanMod's VertexBuffer draws through its VBO,
 * which alone knows the uploaded format.
 */
@Mixin(value = VBO.class, remap = false)
public abstract class ChimeraVertexBufferMixin {
    @Shadow
    private VertexFormat format;

    @WrapMethod(method = "drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;"
            + "Lnet/minecraft/client/renderer/ShaderInstance;)V")
    private void chimera$packProgram(Matrix4f modelView, Matrix4f projection, ShaderInstance shader,
                                     Operation<Void> original) {
        PackHostDraws.draw(shader, this.format, () -> original.call(modelView, projection, shader), () -> {});
    }
}
