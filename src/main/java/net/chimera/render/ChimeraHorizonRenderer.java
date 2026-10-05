package net.chimera.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.vulkan.VRenderSystem;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Iris's camera-centred inverted cone fills the band vanilla's sky disc leaves clear. */
public final class ChimeraHorizonRenderer implements AutoCloseable {
    private GpuBuffer buffer;
    private int radius = -1;
    private int indexCount;

    /** Same radius, winding and fan topology as Iris HorizonRenderer. */
    public static float[] vertices(int renderDistance) {
        int radius = Math.min(renderDistance * 16, 256);
        float[] vertices = new float[30];
        vertices[1] = -16;
        for (int i = 0; i <= 8; i++) {
            double angle = -i * Math.PI / 4;
            int offset = (i + 1) * 3;
            vertices[offset] = (float) (radius * Math.cos(angle));
            vertices[offset + 1] = 16;
            vertices[offset + 2] = (float) (radius * Math.sin(angle));
        }
        return vertices;
    }

    public void draw() {
        var minecraft = Minecraft.getInstance();
        int distance = minecraft.options.getEffectiveRenderDistance();
        int nextRadius = Math.min(distance * 16, 256);
        if (buffer == null || radius != nextRadius) {
            close();
            var builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
            float[] vertices = vertices(distance);
            for (int i = 0; i < vertices.length; i += 3) builder.addVertex(vertices[i], vertices[i + 1], vertices[i + 2]);
            try (var mesh = builder.buildOrThrow()) {
                buffer = RenderSystem.getDevice().createBuffer(() -> "Chimera horizon", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, mesh.vertexBuffer());
                indexCount = mesh.drawState().indexCount();
            }
            Tesselator.getInstance().clear();
            radius = nextRadius;
        }
        var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.TRIANGLE_FAN);
        var indexBuffer = indices.getBuffer(indexCount);
        var fog = VRenderSystem.getShaderFogColor();
        // Alpha one is part of Iris's contract; zero breaks sky reflections in some packs.
        var transform = RenderSystem.getDynamicUniforms().writeTransform(PackUniformProvider.currentSkyModelView(),
                new Vector4f(fog.getFloat(0), fog.getFloat(4), fog.getFloat(8), 1), new Vector3f(), new Matrix4f());
        var target = minecraft.getMainRenderTarget();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Chimera horizon",
                target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transform);
            pass.setPipeline(RenderPipelines.SKY);
            pass.setVertexBuffer(0, buffer);
            pass.setIndexBuffer(indexBuffer, indices.type());
            // Existing sky draw hook owns authored targets, formats, blending and commit.
            pass.drawIndexed(0, 0, indexCount, 1);
        }
    }

    @Override public void close() {
        if (buffer != null) { buffer.close(); buffer = null; }
        radius = -1;
    }
}
