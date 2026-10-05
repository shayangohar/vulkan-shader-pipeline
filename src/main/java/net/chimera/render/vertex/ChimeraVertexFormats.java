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

    /** Modern terrain mid-UV, stored as two unsigned normalized atlas words. */
    public static final VertexFormatElement TERRAIN_MID_TEX_COORD =
            new VertexFormatElement(5, 0, VertexFormatElement.Type.USHORT,
                    VertexFormatElement.Usage.UV, 2);
    /** Signed 1/64-block local midpoint offset used by the modern material bridge. */
    public static final VertexFormatElement TERRAIN_MID_BLOCK =
            new VertexFormatElement(6, 0, VertexFormatElement.Type.INT,
                    VertexFormatElement.Usage.GENERIC, 1);
    /** Normalized normal word used to derive a deterministic tangent fallback. */
    public static final VertexFormatElement TERRAIN_TANGENT_FRAME =
            new VertexFormatElement(7, 0, VertexFormatElement.Type.BYTE,
                    VertexFormatElement.Usage.NORMAL, 4);
    /** Optional separate ambient-occlusion color word. */
    public static final VertexFormatElement TERRAIN_SEPARATE_AO =
            new VertexFormatElement(8, 0, VertexFormatElement.Type.UBYTE,
                    VertexFormatElement.Usage.COLOR, 4);

    public static final VertexFormat EXTENDED_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_POSITION_INT16)
                    .add("UV0", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_UV0_UINT16)
                    .add("Color", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_COLOR_UINT)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .build();

    /** Modern terrain/water layout without a separate AO word: 36 bytes. */
    public static final VertexFormat MODERN_COMPRESSED_TERRAIN =
            modernTerrainFormat(false);

    /** Modern terrain/water layout with a separate AO word: 40 bytes. */
    public static final VertexFormat MODERN_COMPRESSED_TERRAIN_AO =
            modernTerrainFormat(true);

    /** Host NEW_ENTITY followed by the three pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_ENTITY = extendedEntityFormat();

    /** Host PARTICLE followed by the same pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_PARTICLE = extendedParticleFormat();

    public static VertexFormat terrainFormat(net.chimera.shaderpack.TerrainMaterialPlan plan) {
        return plan != null && plan.modern()
                ? (plan.separateAo() ? MODERN_COMPRESSED_TERRAIN_AO : MODERN_COMPRESSED_TERRAIN)
                : EXTENDED_COMPRESSED_TERRAIN;
    }

    public static boolean isTerrainFormat(VertexFormat format) {
        return format == EXTENDED_COMPRESSED_TERRAIN
                || format == MODERN_COMPRESSED_TERRAIN
                || format == MODERN_COMPRESSED_TERRAIN_AO;
    }

    private static VertexFormat modernTerrainFormat(boolean separateAo) {
        VertexFormat.Builder builder = VertexFormat.builder()
                .add("Position", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_POSITION_INT16)
                .add("UV0", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_UV0_UINT16)
                .add("Color", net.vulkanmod.render.vertex.CustomVertexFormat.ELEMENT_COLOR_UINT)
                .add("MaterialId", MATERIAL_ID)
                .add("RenderType", RENDER_TYPE)
                .add("MidTexCoord", TERRAIN_MID_TEX_COORD)
                .add("MidBlock", TERRAIN_MID_BLOCK)
                .add("Tangent", TERRAIN_TANGENT_FRAME);
        if (separateAo) {
            builder.add("SeparateAo", TERRAIN_SEPARATE_AO);
        }
        return builder.build();
    }

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
        return appendMaterialInputs(DefaultVertexFormat.PARTICLE);
    }

    private static final VertexFormat EXTENDED_HAND_MAP = appendMaterialInputs(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
    private static final VertexFormat EXTENDED_HAND_MAP_BACKGROUND = appendMaterialInputs(DefaultVertexFormat.POSITION_TEX_COLOR);
    private static final java.util.Map<VertexFormat, VertexFormat> HAND_FORMATS = java.util.Map.of(
            DefaultVertexFormat.NEW_ENTITY, EXTENDED_ENTITY,
            DefaultVertexFormat.PARTICLE, EXTENDED_PARTICLE,
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, EXTENDED_HAND_MAP,
            DefaultVertexFormat.POSITION_TEX_COLOR, EXTENDED_HAND_MAP_BACKGROUND);

    /** Native hand models, item quads, map text and map background keep distinct prefixes. */
    public static java.util.Map<VertexFormat, VertexFormat> handFormats() {
        return HAND_FORMATS;
    }

    public static VertexFormat handHostFormat(VertexFormat format) {
        for (var entry : handFormats().entrySet()) {
            if (format == entry.getKey() || format == entry.getValue()) return entry.getKey();
        }
        return null;
    }

    private static VertexFormat appendMaterialInputs(VertexFormat host) {
        VertexFormat.Builder builder = VertexFormat.builder();
        java.util.List<VertexFormatElement> elements = host.getElements();
        java.util.List<String> names = host.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (host.getVertexSize() > hostElementBytes) {
            builder.padding(host.getVertexSize() - hostElementBytes);
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
