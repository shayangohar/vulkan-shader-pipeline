package net.chimera.mixin;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.vulkanmod.render.vertex.QuadSorter;
import net.vulkanmod.render.vertex.VertexBuilder;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps VulkanMod's transparency sorter correct for the extended stride. */
@Mixin(value = QuadSorter.class, remap = false)
public abstract class QuadSorterMixin {
    @Shadow
    private int vertexCount;
    @Shadow
    private Vector3f[] sortingPoints;
    @Shadow
    private float[] distances;
    @Shadow
    private int[] sortingPointsIndices;

    @Inject(method = "setupQuadSortingPoints", at = @At("HEAD"), cancellable = true)
    private void chimera$setupExtendedPoints(long bufferPtr, int vertexCount,
                                              VertexFormat format, CallbackInfo ci) {
        if (format != ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN) {
            return;
        }

        this.vertexCount = vertexCount;
        int pointCount = vertexCount / 4;
        Vector3f[] points = new Vector3f[pointCount];
        int vertexSize = format.getVertexSize();
        int quadStride = vertexSize * 4;
        int offset = vertexSize * 2;
        float invConv = 1.0f / VertexBuilder.CompressedVertexBuilder.POS_CONV_MUL;
        float convOffset = -VertexBuilder.CompressedVertexBuilder.POS_OFFSET;

        for (int i = 0; i < pointCount; i++) {
            long ptr = bufferPtr + (long) i * quadStride;
            short x0 = MemoryUtil.memGetShort(ptr);
            short y0 = MemoryUtil.memGetShort(ptr + 2);
            short z0 = MemoryUtil.memGetShort(ptr + 4);
            short x2 = MemoryUtil.memGetShort(ptr + offset);
            short y2 = MemoryUtil.memGetShort(ptr + offset + 2);
            short z2 = MemoryUtil.memGetShort(ptr + offset + 4);
            points[i] = new Vector3f(
                    (x0 + x2) * invConv * 0.5f + convOffset,
                    (y0 + y2) * invConv * 0.5f + convOffset,
                    (z0 + z2) * invConv * 0.5f + convOffset);
        }

        this.sortingPoints = points;
        this.distances = new float[pointCount];
        this.sortingPointsIndices = new int[pointCount];
        ci.cancel();
    }
}
