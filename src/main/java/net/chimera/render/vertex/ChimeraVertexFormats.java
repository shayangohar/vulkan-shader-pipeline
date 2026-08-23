package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.vulkanmod.render.vertex.CustomVertexFormat;

/**
 * Chimera's terrain vertex layout: VulkanMod's COMPRESSED_TERRAIN trio plus a
 * trailing per-vertex block id (mc_Entity equivalent) so shaders can resolve
 * materials. Layout: Position i16x4 @loc0, UV0 u16x2 @loc1, Color u32 @loc2,
 * BlockId s32 @loc3 - 20 bytes per vertex.
 */
public final class ChimeraVertexFormats {
    public static final VertexFormatElement ELEMENT_POSITION_INT16 = CustomVertexFormat.ELEMENT_POSITION_INT16;
    public static final VertexFormatElement ELEMENT_UV0_UINT16 = CustomVertexFormat.ELEMENT_UV0_UINT16;
    public static final VertexFormatElement ELEMENT_COLOR_UINT = CustomVertexFormat.ELEMENT_COLOR_UINT;
    public static final VertexFormatElement ELEMENT_BLOCK_ID = new VertexFormatElement(3, 0, VertexFormatElement.Type.INT, VertexFormatElement.Usage.GENERIC, 1);

    public static final VertexFormat EXTENDED_TERRAIN = VertexFormat.builder()
            .add("Position", ELEMENT_POSITION_INT16)
            .add("UV0", ELEMENT_UV0_UINT16)
            .add("Color", ELEMENT_COLOR_UINT)
            .add("BlockId", ELEMENT_BLOCK_ID)
            .build();

    private ChimeraVertexFormats() {}
}
