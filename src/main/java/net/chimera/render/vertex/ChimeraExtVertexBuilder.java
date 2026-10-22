package net.chimera.render.vertex;

import net.chimera.shaderpack.TerrainMaterialPlan;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/** Compressed host vertex writer with two scalar material attributes. */
public final class ChimeraExtVertexBuilder extends VertexBuilder.CompressedVertexBuilder {
    /** mc_Entity.y of a block model's quads, as Iris's MixinChunkMeshBuildTask passes to beginBlock. */
    public static final int BLOCK_RENDER_TYPE = 0;
    /** mc_Entity.y of a fluid's quads. */
    public static final int FLUID_RENDER_TYPE = 1;
    /** How far into its block the light-block voxel quad sits on each axis. */
    static final float LIGHT_BLOCK_INSET = 0.25F;
    /**
     * The voxel quad's normal: Iris normalizes its zero-area face normal to NaN, which
     * XHFPTerrainVertex's octahedral encoding stores as (0, 0) and the shader decodes as +Z.
     */
    static final int LIGHT_BLOCK_NORMAL = ChimeraEntityVertexData.pack(0.0F, 0.0F, 1.0F, 0.0F);

    private final TerrainMaterialPlan materialPlan;
    private final int vertexSize;
    private int materialId = -1;
    private int renderType = BLOCK_RENDER_TYPE;
    private int blockEmission;
    private int blockX;
    private int blockY;
    private int blockZ;
    /** True while the light-block voxel quad is written: its at_midBlock keeps only the emission. */
    private boolean ignoreMidBlock;
    private final long[] polygonPointers = new long[4];
    private final float[] polygonValues = new float[20];
    private final int[] polygonNormals = new int[4];
    private final float[] faceNormal = new float[3];
    private int polygonVertices;
    private long lastPointer;

    public ChimeraExtVertexBuilder() {
        this(TerrainMaterialPlan.legacy());
    }

    public ChimeraExtVertexBuilder(TerrainMaterialPlan materialPlan) {
        this.materialPlan = materialPlan == null
                ? TerrainMaterialPlan.legacy() : materialPlan;
        this.vertexSize = this.materialPlan.stride();
    }

    /**
     * The block whose quads follow, as Iris's BlockContextHolder.setBlockData: its material,
     * render type and light emission.
     */
    public void setBlockData(int materialId, int renderType, int emission) {
        this.materialId = materialId;
        this.renderType = renderType;
        this.blockEmission = emission;
    }

    /** The position within the section of that block; at_midBlock points from each vertex to its centre. */
    public void setBlockPosition(int localX, int localY, int localZ) {
        this.blockX = localX;
        this.blockY = localY;
        this.blockZ = localZ;
    }

    /**
     * Writes Iris's voxelizeLightBlocks quad for a light block, which has no model, from ptr
     * (MixinChunkMeshBuildTask.iris$setLightBlock): four vertices at one point a quarter block
     * into it, so it covers no pixel, with its id and render type 0. Colour and texture
     * coordinates are 0, both lightmap coordinates are the emission, and at_midBlock keeps only
     * the emission, as Iris ignores the mid-block offset for it.
     */
    public void lightBlockQuad(long ptr, int materialId, int emission, int localX, int localY, int localZ) {
        setBlockData(materialId, BLOCK_RENDER_TYPE, emission);
        setBlockPosition(localX, localY, localZ);
        ignoreMidBlock = true;
        int light = emission << 4 | emission << 20;
        for (int corner = 0; corner < 4; corner++) {
            vertex(ptr + (long) corner * vertexSize, localX + LIGHT_BLOCK_INSET, localY + LIGHT_BLOCK_INSET,
                    localZ + LIGHT_BLOCK_INSET, 0, 0.0F, 0.0F, light, LIGHT_BLOCK_NORMAL);
        }
        ignoreMidBlock = false;
    }

    @Override
    public void vertex(long ptr, float x, float y, float z, int color,
                       float u, float v, int light, int packedNormal) {
        // A discontinuous pointer means the builder restarted its buffer
        // (cleared, or grown into a new allocation), so never combine
        // vertices from either side into one polygon.
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
        // Iris derives a terrain quad's normal from its diagonals. VulkanMod
        // passes the quad's facing axis instead (unit bytes, not scaled to
        // 127), and none at all for fluids, so derive it here. A degenerate
        // quad keeps the host's word.
        if (ChimeraEntityVertexData.faceNormal(faceNormal,
                polygonValues[0], polygonValues[1], polygonValues[2],
                polygonValues[5], polygonValues[6], polygonValues[7],
                polygonValues[10], polygonValues[11], polygonValues[12],
                polygonValues[15], polygonValues[16], polygonValues[17])) {
            Arrays.fill(polygonNormals,
                    ChimeraEntityVertexData.pack(faceNormal[0], faceNormal[1], faceNormal[2], 0.0F));
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
            // The packed face normal, with the quad tangent frame in
            // its spare byte (TerrainTangentCodec).
            MemoryUtil.memPutInt(ptr + 32, TerrainTangentCodec.encode(polygonNormals[index], tangent));
        }
        polygonVertices = 0;
        Arrays.fill(polygonPointers, 0L);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    /**
     * Iris's ExtendedDataHelper.computeMidBlock from the current block's position, with its
     * light emission in the fourth byte. A centre taken from the vertex instead names the
     * neighbouring block for every vertex on a +X, +Y or +Z face; Solas's voxelization then
     * raced neighbouring blocks into one voxel, and its light sources flickered.
     */
    private int encodeMidBlock(float x, float y, float z) {
        int emission = (blockEmission & 0xFF) << 24;
        if (ignoreMidBlock) return emission;
        return midBlockOffset(blockX + 0.5F - x)
                | (midBlockOffset(blockY + 0.5F - y) << 8)
                | (midBlockOffset(blockZ + 0.5F - z) << 16)
                | emission;
    }

    private static int midBlockOffset(float offset) {
        return (int) (offset * 64.0F) & 0xFF;
    }
}
