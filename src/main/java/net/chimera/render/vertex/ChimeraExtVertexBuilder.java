package net.chimera.render.vertex;

import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

/** Compressed host vertex writer with two scalar material attributes. */
public final class ChimeraExtVertexBuilder extends VertexBuilder.CompressedVertexBuilder {
    private static final int VERTEX_SIZE = 24;

    private int materialId = -1;
    private int renderType = -1;

    public void setMaterialData(int materialId, int renderType) {
        this.materialId = materialId;
        this.renderType = renderType;
    }

    @Override
    public void vertex(long ptr, float x, float y, float z, int color,
                       float u, float v, int light, int packedNormal) {
        super.vertex(ptr, x, y, z, color, u, v, light, packedNormal);
        MemoryUtil.memPutInt(ptr + 16, materialId);
        MemoryUtil.memPutInt(ptr + 20, renderType);
    }

    @Override
    public int getStride() {
        return VERTEX_SIZE;
    }
}
