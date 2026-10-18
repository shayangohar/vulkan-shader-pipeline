package net.chimera.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.chimera.render.shader.PackHostDraws;
import org.spongepowered.asm.mixin.Mixin;

/** Immediate host draws run under the pack program that serves them (PackHostDraws). */
@Mixin(BufferUploader.class)
public abstract class ChimeraBufferUploaderMixin {
    @WrapMethod(method = "drawWithShader")
    private static void chimera$packProgram(MeshData mesh, Operation<Void> original) {
        PackHostDraws.draw(RenderSystem.getShader(), mesh.drawState().format(), () -> original.call(mesh), mesh::close);
    }
}
