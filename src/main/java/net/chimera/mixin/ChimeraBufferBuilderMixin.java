package net.chimera.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.vertex.ChimeraEntityVertexData;
import net.chimera.render.vertex.ChimeraVertexFormats;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Initializes and finalizes the three appended entity vertex elements. */
@Mixin(BufferBuilder.class)
public abstract class ChimeraBufferBuilderMixin {
    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private ByteBufferBuilder buffer;

    @Shadow
    private int elementsToFill;

    @Unique private int chimera$ids = -1;
    @Unique private int chimera$mid = -1;
    @Unique private int chimera$tangent = -1;
    @Unique private int chimera$position = -1;
    @Unique private int chimera$uv = -1;
    @Unique private int chimera$normal = -1;
    @Unique private boolean chimera$particleLayout;
    @Unique private int chimera$extraMask;
    @Unique private int chimera$corners;
    @Unique private long[] chimera$cornerOffsets;
    @Unique private int chimera$written;
    @Unique private float[] chimera$values;
    @Unique private float[] chimera$face;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void chimera$locateElements(
            ByteBufferBuilder buffer,
            VertexFormat.Mode mode,
            VertexFormat format,
            CallbackInfo callback
    ) {
        if (format != ChimeraVertexFormats.EXTENDED_ENTITY
                && !ChimeraVertexFormats.handFormats().containsValue(format)) {
            return;
        }
        chimera$particleLayout = !format.getElementAttributeNames().contains("Normal");
        chimera$ids = offsetOf(format, "EntityIds");
        chimera$mid = offsetOf(format, "MidTexCoord");
        chimera$tangent = offsetOf(format, "Tangent");
        chimera$position = offsetOf(format, "Position");
        chimera$uv = offsetOf(format, "UV0");
        chimera$normal = offsetOf(format, "Normal");
        chimera$extraMask = maskOf(format, "EntityIds")
                | maskOf(format, "MidTexCoord")
                | maskOf(format, "Tangent");
        chimera$corners = mode == VertexFormat.Mode.QUADS ? 4
                : mode == VertexFormat.Mode.TRIANGLES ? 3 : 0;
        if (chimera$ids < 0 || chimera$mid < 0 || chimera$tangent < 0
                || chimera$position < 0 || chimera$uv < 0
                || (!chimera$particleLayout && chimera$normal < 0)) {
            chimera$ids = -1;
            return;
        }
        chimera$cornerOffsets = new long[4];
        chimera$values = new float[20];
        chimera$face = new float[3];
    }

    @Inject(method = "beginVertex", at = @At("RETURN"), require = 1)
    private void chimera$writeDefaults(CallbackInfoReturnable<Long> callback) {
        if (chimera$ids < 0) {
            return;
        }
        chimera$fillPolygon();
        long pointer = callback.getReturnValueJ();
        MemoryUtil.memPutShort(pointer + chimera$ids,
                (short) ChimeraEntityBridge.currentEntityId());
        MemoryUtil.memPutShort(pointer + chimera$ids + 2L, (short) 0);
        MemoryUtil.memPutShort(pointer + chimera$ids + 4L, (short) 0);
        MemoryUtil.memPutShort(pointer + chimera$ids + 6L, (short) 0);
        MemoryUtil.memPutFloat(pointer + chimera$mid, 0.0f);
        MemoryUtil.memPutFloat(pointer + chimera$mid + 4L, 0.0f);
        MemoryUtil.memPutInt(pointer + chimera$tangent, ChimeraEntityVertexData.FLAT_TANGENT);
        if (chimera$cornerOffsets != null && chimera$corners > 0) {
            chimera$cornerOffsets[chimera$written++] = pointer
                    - ((ChimeraByteBufferBuilderAccessor) (Object) buffer).chimera$pointer();
        }
    }

    @Inject(method = "build", at = @At("HEAD"), require = 1)
    private void chimera$finishPolygon(CallbackInfoReturnable<MeshData> callback) {
        if (chimera$ids >= 0) {
            chimera$fillPolygon();
        }
    }

    /**
     * The vanilla builder validates its element mask when the next vertex
     * starts. The appended attributes are written directly because they are
     * Chimera-owned data, so mark only those elements complete before that
     * validation runs. Host attributes keep their normal validation.
     */
    @Inject(method = "endLastVertex", at = @At("HEAD"), require = 1)
    private void chimera$completeAppendedElements(CallbackInfo callback) {
        if (chimera$ids < 0) {
            return;
        }
        chimera$fillPolygon();
        elementsToFill &= ~chimera$extraMask;
    }

    @Unique
    private void chimera$fillPolygon() {
        if (chimera$corners == 0 || chimera$written < chimera$corners) {
            return;
        }
        long base = ((ChimeraByteBufferBuilderAccessor) (Object) buffer).chimera$pointer();
        float midU = 0.0f;
        float midV = 0.0f;
        for (int index = 0; index < chimera$corners; index++) {
            long vertex = base + chimera$cornerOffsets[index];
            int into = index * 5;
            chimera$values[into] = MemoryUtil.memGetFloat(vertex + chimera$position);
            chimera$values[into + 1] = MemoryUtil.memGetFloat(vertex + chimera$position + 4L);
            chimera$values[into + 2] = MemoryUtil.memGetFloat(vertex + chimera$position + 8L);
            chimera$values[into + 3] = MemoryUtil.memGetFloat(vertex + chimera$uv);
            chimera$values[into + 4] = MemoryUtil.memGetFloat(vertex + chimera$uv + 4L);
            midU += chimera$values[into + 3];
            midV += chimera$values[into + 4];
        }
        midU /= chimera$corners;
        midV /= chimera$corners;
        if (chimera$corners == 4
                && ChimeraEntityVertexData.faceNormal(chimera$face,
                chimera$values[0], chimera$values[1], chimera$values[2],
                chimera$values[5], chimera$values[6], chimera$values[7],
                chimera$values[10], chimera$values[11], chimera$values[12],
                chimera$values[15], chimera$values[16], chimera$values[17])) {
            int tangent = ChimeraEntityVertexData.tangent(chimera$face[0], chimera$face[1],
                    chimera$face[2], false, chimera$values[0], chimera$values[1], chimera$values[2],
                    chimera$values[3], chimera$values[4], chimera$values[5], chimera$values[6],
                    chimera$values[7], chimera$values[8], chimera$values[9], chimera$values[10],
                    chimera$values[11], chimera$values[12], chimera$values[13], chimera$values[14]);
            for (int index = 0; index < 4; index++) {
                chimera$write(base + chimera$cornerOffsets[index], midU, midV, tangent);
            }
        } else if (chimera$normal < 0) {
            for (int index = 0; index < chimera$corners; index++) {
                long vertex = base + chimera$cornerOffsets[index];
                chimera$write(vertex, midU, midV, ChimeraEntityVertexData.FLAT_TANGENT);
            }
        } else {
            for (int index = 0; index < chimera$corners; index++) {
                long vertex = base + chimera$cornerOffsets[index];
                int normal = MemoryUtil.memGetInt(vertex + chimera$normal);
                int tangent = ChimeraEntityVertexData.tangent(
                        ChimeraEntityVertexData.unpack(normal, 0),
                        ChimeraEntityVertexData.unpack(normal, 1),
                        ChimeraEntityVertexData.unpack(normal, 2), true,
                        chimera$values[0], chimera$values[1], chimera$values[2], chimera$values[3],
                        chimera$values[4], chimera$values[5], chimera$values[6], chimera$values[7],
                        chimera$values[8], chimera$values[9], chimera$values[10], chimera$values[11],
                        chimera$values[12], chimera$values[13], chimera$values[14]);
                chimera$write(vertex, midU, midV, tangent);
            }
        }
        chimera$written = 0;
    }

    @Unique
    private void chimera$write(long vertex, float u, float v, int tangent) {
        MemoryUtil.memPutFloat(vertex + chimera$mid, u);
        MemoryUtil.memPutFloat(vertex + chimera$mid + 4L, v);
        MemoryUtil.memPutInt(vertex + chimera$tangent, tangent);
    }

    @Unique
    private static int offsetOf(VertexFormat value, String name) {
        java.util.List<String> names = value.getElementAttributeNames();
        java.util.List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = value.getElements();
        for (int index = 0; index < names.size(); index++) {
            if (names.get(index).equals(name)) {
                return value.getOffset(elements.get(index));
            }
        }
        return -1;
    }

    @Unique
    private static int maskOf(VertexFormat value, String name) {
        java.util.List<String> names = value.getElementAttributeNames();
        java.util.List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = value.getElements();
        for (int index = 0; index < names.size(); index++) {
            if (names.get(index).equals(name)) {
                return elements.get(index).mask();
            }
        }
        return 0;
    }
}
