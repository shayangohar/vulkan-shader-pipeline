package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Iris HorizonRenderer: a camera-centred inverted cone that fills the band vanilla's sky disc
 * leaves clear. It draws in the fog colour with the position shader, which the sky window gives
 * the pack's gbuffers_skybasic program.
 */
public final class ChimeraHorizonRenderer implements AutoCloseable {
    private VertexBuffer buffer;
    private int radius = -1;

    /** Apex plus a closed eight-segment rim, drawn as one fan. */
    public static final int VERTEX_COUNT = 10;

    /** Same radius, winding and fan topology as Iris HorizonRenderer. */
    public static float[] vertices(int renderDistance) {
        int radius = Math.min(renderDistance * 16, 256);
        float[] vertices = new float[VERTEX_COUNT * 3];
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

    /** Draws the horizon under the sky's model-view and projection. */
    public void draw(Matrix4f modelView, Matrix4f projection) {
        int distance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int nextRadius = Math.min(distance * 16, 256);
        if (buffer == null || radius != nextRadius) {
            close();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN,
                    DefaultVertexFormat.POSITION);
            float[] vertices = vertices(distance);
            for (int i = 0; i < vertices.length; i += 3) builder.addVertex(vertices[i], vertices[i + 1],
                    vertices[i + 2]);
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            radius = nextRadius;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        RenderSystem.depthMask(false);
        // Alpha one is part of Iris's contract; zero breaks sky reflections in some packs.
        RenderSystem.setShaderColor(fog[0], fog[1], fog[2], 1.0F);
        buffer.bind();
        buffer.drawWithShader(modelView, projection, GameRenderer.getPositionShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.depthMask(true);
    }

    @Override public void close() {
        if (buffer != null) { buffer.close(); buffer = null; }
        radius = -1;
    }
}
