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
 * The horizon of Iris 1.8.14, the Iris for 1.21.1: an eight-sided wall around the camera between
 * the sky planes at y -16 and 16, then both planes as 64-block grids out to 384 blocks, so the
 * pack's sky surrounds the camera on every side. It draws in the fog colour with the position
 * shader, which the sky window gives the pack's gbuffers_skybasic program. Later Iris versions
 * draw a cone instead.
 */
public final class ChimeraHorizonRenderer implements AutoCloseable {
    private static final float TOP = 16;
    private static final float BOTTOM = -16;
    private static final int SIDES = 8;
    private static final int MAX_RADIUS = 256;
    private static final int CELL = 64;
    /** The planes' first cell starts here and their last cell starts at its negation. */
    private static final int PLANE_START = -384;
    private static final int CELLS_PER_ROW = -2 * PLANE_START / CELL + 1;
    /** A plane cell's corners, counterclockwise seen from below. */
    private static final int[] CELL_X = {CELL, CELL, 0, 0};
    private static final int[] CELL_Z = {0, CELL, CELL, 0};

    /** The walls, then the top plane, then the bottom plane, each a run of quads. */
    public static final int VERTEX_COUNT = 4 * (SIDES + 2 * CELLS_PER_ROW * CELLS_PER_ROW);

    private VertexBuffer buffer;
    private int radius = -1;

    private static int radius(int renderDistance) {
        return Math.min(renderDistance * 16, MAX_RADIUS);
    }

    /** Iris's quads, in its order, each wound to face the camera inside. */
    public static float[] vertices(int renderDistance) {
        float[] vertices = new float[VERTEX_COUNT * 3];
        int next = walls(vertices, radius(renderDistance));
        next = plane(vertices, next, TOP);
        plane(vertices, next, BOTTOM);
        return vertices;
    }

    /**
     * Corner k sits at 45k - 22.5 degrees, so the walls face the axes and the diagonals. Wall k
     * runs from corner k back to corner k - 1.
     */
    private static int walls(float[] vertices, int radius) {
        float[] x = new float[SIDES];
        float[] z = new float[SIDES];
        for (int k = 0; k < SIDES; k++) {
            double angle = Math.toRadians(45 * k - 22.5);
            x[k] = (float) (radius * Math.cos(angle));
            z[k] = (float) (radius * Math.sin(angle));
        }
        int next = 0;
        for (int k = 0; k < SIDES; k++) {
            int previous = (k + SIDES - 1) % SIDES;
            next = put(vertices, next, x[k], BOTTOM, z[k]);
            next = put(vertices, next, x[k], TOP, z[k]);
            next = put(vertices, next, x[previous], TOP, z[previous]);
            next = put(vertices, next, x[previous], BOTTOM, z[previous]);
        }
        return next;
    }

    /** The top plane walks each cell's corners in order; the bottom walks them backwards. */
    private static int plane(float[] vertices, int next, float y) {
        for (int x = PLANE_START; x <= -PLANE_START; x += CELL) {
            for (int z = PLANE_START; z <= -PLANE_START; z += CELL) {
                for (int i = 0; i < 4; i++) {
                    int corner = y > 0 ? i : (4 - i) % 4;
                    next = put(vertices, next, x + CELL_X[corner], y, z + CELL_Z[corner]);
                }
            }
        }
        return next;
    }

    private static int put(float[] vertices, int next, float x, float y, float z) {
        vertices[next] = x;
        vertices[next + 1] = y;
        vertices[next + 2] = z;
        return next + 3;
    }

    /** Draws the horizon under the sky's model-view and projection. */
    public void draw(Matrix4f modelView, Matrix4f projection) {
        int distance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        if (buffer == null || radius != radius(distance)) {
            close();
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,
                    DefaultVertexFormat.POSITION);
            float[] vertices = vertices(distance);
            for (int i = 0; i < vertices.length; i += 3) builder.addVertex(vertices[i], vertices[i + 1],
                    vertices[i + 2]);
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(builder.buildOrThrow());
            radius = radius(distance);
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
