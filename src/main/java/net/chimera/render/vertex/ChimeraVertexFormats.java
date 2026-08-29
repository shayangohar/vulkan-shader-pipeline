package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** Vertex formats owned by Chimera rather than by the host renderer. */
public final class ChimeraVertexFormats {
    public static final VertexFormatElement MATERIAL_ID =
            new VertexFormatElement(3, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormatElement RENDER_TYPE =
            new VertexFormatElement(4, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);

    public static final VertexFormat EXTENDED_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_POSITION_INT16)
                    .add("UV0", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_UV0_UINT16)
                    .add("Color", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_COLOR_UINT)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .build();

    private ChimeraVertexFormats() {}
}
