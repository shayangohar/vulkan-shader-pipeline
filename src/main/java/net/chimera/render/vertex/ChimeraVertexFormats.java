package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

/** Vertex formats owned by Chimera rather than by the host renderer. */
public final class ChimeraVertexFormats {
    public static final VertexFormatElement MATERIAL_ID =
            new VertexFormatElement(3, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormatElement RENDER_TYPE =
            new VertexFormatElement(4, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);

    /**
     * Scalar metadata accepted by VulkanMod's generic format translator.
     * ChimeraEntityVertexInputMixin replaces the Vulkan description with the
     * packed four-component unsigned-short input at the same byte offset.
     */
    public static final VertexFormatElement ENTITY_IDS =
            VertexFormatElement.register(16, 0, VertexFormatElement.Type.SHORT,
                    VertexFormatElement.Usage.GENERIC, 1);
    /** Polygon center UV written by the entity buffer bridge. */
    public static final VertexFormatElement MID_TEX_COORD =
            VertexFormatElement.register(17, 0, VertexFormatElement.Type.FLOAT,
                    VertexFormatElement.Usage.GENERIC, 1);
    /** Packed normalized tangent frame written by the entity buffer bridge. */
    public static final VertexFormatElement TANGENT =
            VertexFormatElement.register(18, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);

    public static final VertexFormat EXTENDED_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_POSITION_INT16)
                    .add("UV0", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_UV0_UINT16)
                    .add("Color", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_COLOR_UINT)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .build();

    /** Host NEW_ENTITY followed by the three pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_ENTITY = extendedEntityFormat();

    /** Host PARTICLE followed by the same pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_PARTICLE = extendedParticleFormat();

    private static VertexFormat extendedEntityFormat() {
        VertexFormat.Builder builder = VertexFormat.builder();
        java.util.List<VertexFormatElement> elements = DefaultVertexFormat.NEW_ENTITY.getElements();
        java.util.List<String> names = DefaultVertexFormat.NEW_ENTITY.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (DefaultVertexFormat.NEW_ENTITY.getVertexSize() > hostElementBytes) {
            builder.padding(DefaultVertexFormat.NEW_ENTITY.getVertexSize() - hostElementBytes);
        }
        builder.add("EntityIds", ENTITY_IDS);
        builder.padding(6);
        builder.add("MidTexCoord", MID_TEX_COORD);
        builder.padding(4);
        builder.add("Tangent", TANGENT);
        return builder.build();
    }

    private static VertexFormat extendedParticleFormat() {
        VertexFormat.Builder builder = VertexFormat.builder();
        java.util.List<VertexFormatElement> elements = DefaultVertexFormat.PARTICLE.getElements();
        java.util.List<String> names = DefaultVertexFormat.PARTICLE.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (DefaultVertexFormat.PARTICLE.getVertexSize() > hostElementBytes) {
            builder.padding(DefaultVertexFormat.PARTICLE.getVertexSize() - hostElementBytes);
        }
        builder.add("EntityIds", ENTITY_IDS);
        builder.padding(6);
        builder.add("MidTexCoord", MID_TEX_COORD);
        builder.padding(4);
        builder.add("Tangent", TANGENT);
        return builder.build();
    }

    private ChimeraVertexFormats() {}
}
