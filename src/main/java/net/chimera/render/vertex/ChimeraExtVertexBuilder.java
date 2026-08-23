package net.chimera.render.vertex;

import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

/**
 * Writes chimera's extended terrain vertices: the host's compressed 16-byte
 * layout followed by a 4-byte block id. The id is set per quad batch by
 * {@link ChimeraExtTerrainBuilder#setBlockAttributes} before emission.
 */
public class ChimeraExtVertexBuilder implements VertexBuilder {
    public static final int VERTEX_SIZE = 20;

    private static final float POS_CONV_MUL = 2048.0F;
    private static final float POS_OFFSET = -8192.0F;
    private static final float UV_CONV_MUL = 32768.0F;

    private int blockId = -1;

    public void setBlockId(int blockId) {
        this.blockId = blockId;
    }

    @Override
    public void vertex(long ptr, float x, float y, float z, int color, float u, float v, int light, int packedNormal) {
        writeCompressedPrefix(ptr, x, y, z, color, u, v, light);
        MemoryUtil.memPutInt(ptr + 16L, this.blockId);
    }

    private void writeCompressedPrefix(long ptr, float x, float y, float z, int color, float u, float v, int light) {
        short sX = (short) ((int) (x * POS_CONV_MUL + POS_OFFSET));
        short sY = (short) ((int) (y * POS_CONV_MUL + POS_OFFSET));
        short sZ = (short) ((int) (z * POS_CONV_MUL + POS_OFFSET));
        MemoryUtil.memPutShort(ptr + 0L, sX);
        MemoryUtil.memPutShort(ptr + 2L, sY);
        MemoryUtil.memPutShort(ptr + 4L, sZ);

        short l = (short) (light >>> 8 & 0xFF00 | light & 0xFF);
        MemoryUtil.memPutShort(ptr + 6L, l);

        MemoryUtil.memPutShort(ptr + 8L, (short) ((int) (u * UV_CONV_MUL)));
        MemoryUtil.memPutShort(ptr + 10L, (short) ((int) (v * UV_CONV_MUL)));

        MemoryUtil.memPutInt(ptr + 12L, color);
    }

    @Override
    public void position(long ptr, float x, float y, float z) {
        short sX = (short) ((int) (x * POS_CONV_MUL + POS_OFFSET));
        short sY = (short) ((int) (y * POS_CONV_MUL + POS_OFFSET));
        short sZ = (short) ((int) (z * POS_CONV_MUL + POS_OFFSET));
        MemoryUtil.memPutShort(ptr + 0L, sX);
        MemoryUtil.memPutShort(ptr + 2L, sY);
        MemoryUtil.memPutShort(ptr + 4L, sZ);
    }

    @Override
    public void color(long ptr, int color) {
        MemoryUtil.memPutInt(ptr + 12L, color);
    }

    @Override
    public void uv(long ptr, float u, float v) {
        MemoryUtil.memPutShort(ptr + 8L, (short) ((int) (u * UV_CONV_MUL)));
        MemoryUtil.memPutShort(ptr + 10L, (short) ((int) (v * UV_CONV_MUL)));
    }

    @Override
    public void light(long ptr, int light) {
        short l = (short) (light >>> 8 & 0xFF00 | light & 0xFF);
        MemoryUtil.memPutShort(ptr + 6L, l);
    }

    @Override
    public void normal(long ptr, int normal) {
        // Normals are not part of the compressed layout.
    }

    @Override
    public int getStride() {
        return VERTEX_SIZE;
    }
}
