package net.chimera.render.vertex;

import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/** Compressed host vertex writer with two scalar material attributes. */
public final class ChimeraExtVertexBuilder extends VertexBuilder.CompressedVertexBuilder {
    private final net.chimera.shaderpack.TerrainMaterialPlan materialPlan;
    private final int vertexSize;
    private int materialId = -1;
    private int renderType = -1;
    private final long[] polygonPointers = new long[4];
    private final float[] polygonValues = new float[20];
    private final int[] polygonNormals = new int[4];
    private final float[] faceNormal = new float[3];
    private int polygonVertices;
    private long lastPointer;

    public ChimeraExtVertexBuilder() {
        this(net.chimera.shaderpack.TerrainMaterialPlan.legacy());
    }

    public ChimeraExtVertexBuilder(net.chimera.shaderpack.TerrainMaterialPlan materialPlan) {
        this.materialPlan = materialPlan == null
                ? net.chimera.shaderpack.TerrainMaterialPlan.legacy() : materialPlan;
        this.vertexSize = this.materialPlan.stride();
    }

    public void setMaterialData(int materialId, int renderType) {
        this.materialId = materialId;
        this.renderType = renderType;
    }

    @Override
    public void vertex(long ptr, float x, float y, float z, int color,
                       float u, float v, int light, int packedNormal) {
        // TerrainBuilder shares one VertexBuilder instance across its facing
        // buffers. A discontinuous pointer means a different buffer (or a
        // cleared buffer), so never combine its vertices into one polygon.
        if (lastPointer != 0L && ptr != lastPointer + vertexSize) {
            polygonVertices = 0;
            Arrays.fill(polygonPointers, 0L);
        }
        lastPointer = ptr;
        super.vertex(ptr, x, y, z, color, u, v, light, packedNormal);
        MemoryUtil.memPutInt(ptr + 16, materialId);
        MemoryUtil.memPutInt(ptr + 20, renderType);
        if (!materialPlan.modern()) {
            return;
        }

        // Every modern vertex is initialized, even when a pack does not read
        // one of the optional fields. This prevents stale data after a chunk
        // builder is reused.
        MemoryUtil.memPutShort(ptr + 24, (short) 0);
        MemoryUtil.memPutShort(ptr + 26, (short) 0);
        MemoryUtil.memPutInt(ptr + 28, encodeMidBlock(x, y, z));
        // The fourth byte carries the quad tangent once the polygon closes.
        MemoryUtil.memPutInt(ptr + 32, packedNormal & 0x00FFFFFF);
        if (materialPlan.separateAo()) {
            MemoryUtil.memPutInt(ptr + 36, 0xFFFFFFFF);
        }

        int index = polygonVertices++;
        if (index < 4) {
            polygonPointers[index] = ptr;
            int into = index * 5;
            polygonValues[into] = x;
            polygonValues[into + 1] = y;
            polygonValues[into + 2] = z;
            polygonValues[into + 3] = u;
            polygonValues[into + 4] = v;
            polygonNormals[index] = packedNormal;
        }
        if (polygonVertices == 4) {
            finishPolygon();
        }
    }

    @Override
    public int getStride() {
        return vertexSize;
    }

    private void finishPolygon() {
        float midU = 0.0F;
        float midV = 0.0F;
        for (int index = 0; index < 4; index++) {
            int into = index * 5;
            midU += polygonValues[into + 3];
            midV += polygonValues[into + 4];
        }
        midU = clamp01(midU * 0.25F);
        midV = clamp01(midV * 0.25F);
        // VulkanMod's fluid renderer emits packed normal 0. Iris derives the
        // face normal from the quad's diagonals, so do the same whenever the
        // host supplied none; otherwise the pack normalizes a zero vector.
        if ((polygonNormals[0] & 0x00FFFFFF) == 0 && ChimeraEntityVertexData.faceNormal(faceNormal,
                polygonValues[0], polygonValues[1], polygonValues[2],
                polygonValues[5], polygonValues[6], polygonValues[7],
                polygonValues[10], polygonValues[11], polygonValues[12],
                polygonValues[15], polygonValues[16], polygonValues[17])) {
            int packed = ChimeraEntityVertexData.pack(faceNormal[0], faceNormal[1], faceNormal[2], 0.0F);
            for (int index = 0; index < 4; index++) {
                if ((polygonNormals[index] & 0x00FFFFFF) == 0) {
                    polygonNormals[index] = packed;
                }
            }
        }
        // Iris's at_tangent: the texture U direction of the quad's first
        // triangle, with the V handedness in w.
        int firstNormal = polygonNormals[0];
        int tangent = ChimeraEntityVertexData.tangent(
                ChimeraEntityVertexData.unpack(firstNormal, 0),
                ChimeraEntityVertexData.unpack(firstNormal, 1),
                ChimeraEntityVertexData.unpack(firstNormal, 2), false,
                polygonValues[0], polygonValues[1], polygonValues[2], polygonValues[3], polygonValues[4],
                polygonValues[5], polygonValues[6], polygonValues[7], polygonValues[8], polygonValues[9],
                polygonValues[10], polygonValues[11], polygonValues[12], polygonValues[13], polygonValues[14]);
        for (int index = 0; index < 4; index++) {
            long ptr = polygonPointers[index];
            MemoryUtil.memPutShort(ptr + 24, (short) Math.round(midU * 32768.0F));
            MemoryUtil.memPutShort(ptr + 26, (short) Math.round(midV * 32768.0F));
            int into = index * 5;
            MemoryUtil.memPutInt(ptr + 28, encodeMidBlock(
                    polygonValues[into], polygonValues[into + 1], polygonValues[into + 2]));
            // VulkanMod's packed face normal, with the quad tangent frame in
            // its spare byte (TerrainTangentCodec).
            MemoryUtil.memPutInt(ptr + 32, TerrainTangentCodec.encode(polygonNormals[index], tangent));
            if (materialPlan.separateAo()) {
                MemoryUtil.memPutInt(ptr + 36, 0xFFFFFFFF);
            }
        }
        polygonVertices = 0;
        Arrays.fill(polygonPointers, 0L);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    private static int encodeMidBlock(float x, float y, float z) {
        int ix = signedMidBlockOffset(x);
        int iy = signedMidBlockOffset(y);
        int iz = signedMidBlockOffset(z);
        return (ix & 0xFF) | ((iy & 0xFF) << 8) | ((iz & 0xFF) << 16);
    }

    private static int signedMidBlockOffset(float coordinate) {
        float blockCenter = (float) Math.floor(coordinate) + 0.5F;
        return Math.max(-128, Math.min(127,
                (int) Math.floor((blockCenter - coordinate) * 64.0F)));
    }
}
