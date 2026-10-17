package net.chimera.render.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.shaderpack.TerrainMaterialPlan;
import net.vulkanmod.render.vertex.CustomVertexFormat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * VulkanMod's COMPRESSED_TERRAIN prefix (position and light, colour, UV), which its
     * CompressedVertexBuilder writes, followed by the material attributes: 24 bytes.
     */
    public static final VertexFormat EXTENDED_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", CustomVertexFormat.ELEMENT_POSITION)
                    .add("Color", CustomVertexFormat.ELEMENT_COLOR)
                    .add("UV0", CustomVertexFormat.ELEMENT_UV0)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .build();

    /**
     * Modern terrain/water layout: 36 bytes. Iris's separateAo needs no word of its own; the
     * light pipeline's brightness rides in the colour's alpha.
     */
    public static final VertexFormat MODERN_COMPRESSED_TERRAIN =
            VertexFormat.builder()
                    .add("Position", CustomVertexFormat.ELEMENT_POSITION)
                    .add("Color", CustomVertexFormat.ELEMENT_COLOR)
                    .add("UV0", CustomVertexFormat.ELEMENT_UV0)
                    .add("MaterialId", MATERIAL_ID)
                    .add("RenderType", RENDER_TYPE)
                    .add("MidTexCoord", TERRAIN_MID_TEX_COORD)
                    .add("MidBlock", TERRAIN_MID_BLOCK)
                    .add("Tangent", TERRAIN_TANGENT_FRAME)
                    .build();

    /** Host NEW_ENTITY followed by the three pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_ENTITY = extendedEntityFormat();

    /** Host PARTICLE followed by the same pack-visible Iris attributes. */
    public static final VertexFormat EXTENDED_PARTICLE = extendedParticleFormat();

    public static VertexFormat terrainFormat(TerrainMaterialPlan plan) {
        return plan != null && plan.modern() ? MODERN_COMPRESSED_TERRAIN : EXTENDED_COMPRESSED_TERRAIN;
    }

    public static boolean isTerrainFormat(VertexFormat format) {
        return format == EXTENDED_COMPRESSED_TERRAIN || format == MODERN_COMPRESSED_TERRAIN;
    }

    private static VertexFormat extendedEntityFormat() {
        VertexFormat.Builder builder = VertexFormat.builder();
        List<VertexFormatElement> elements = DefaultVertexFormat.NEW_ENTITY.getElements();
        List<String> names = DefaultVertexFormat.NEW_ENTITY.getElementAttributeNames();
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

    /**
     * Iris IrisVertexFormats.GLYPH: text keeps POSITION_COLOR_TEX_LIGHTMAP and gains a
     * normal, computed per glyph quad, ahead of the pack-visible attributes. World text,
     * sign text and text drawn in the hand (maps) all use it.
     */
    public static final VertexFormat EXTENDED_GLYPH = appendMaterialInputs(
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, true);
    /**
     * Host BLOCK followed by the pack-visible attributes: the chunk render types of falling blocks
     * and moving pistons, which Iris extends to IrisVertexFormats.TERRAIN outside chunk meshes. Its
     * elements match EXTENDED_GLYPH's, so VertexFormat.equals cannot tell the two apart; Chimera
     * compares formats by identity, and their identical vertex inputs are harmless where a map does
     * merge them.
     */
    public static final VertexFormat EXTENDED_BLOCK = appendMaterialInputs(DefaultVertexFormat.BLOCK);
    private static final VertexFormat EXTENDED_HAND_MAP_BACKGROUND = appendMaterialInputs(
            DefaultVertexFormat.POSITION_TEX_COLOR);
    private static final Map<VertexFormat, VertexFormat> HAND_FORMATS = handFormatsInOrder();

    /**
     * Native hand models, item quads, map text and map background keep distinct prefixes. The
     * order is fixed because each hand variant is named by its position, as pack_gbuffers_hand_N.
     */
    public static Map<VertexFormat, VertexFormat> handFormats() {
        return HAND_FORMATS;
    }

    /** Map.of iterates in an order randomized per JVM start, which renamed the variants per session. */
    private static Map<VertexFormat, VertexFormat> handFormatsInOrder() {
        Map<VertexFormat, VertexFormat> formats = new LinkedHashMap<>();
        formats.put(DefaultVertexFormat.NEW_ENTITY, EXTENDED_ENTITY);
        formats.put(DefaultVertexFormat.PARTICLE, EXTENDED_PARTICLE);
        formats.put(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, EXTENDED_GLYPH);
        formats.put(DefaultVertexFormat.POSITION_TEX_COLOR, EXTENDED_HAND_MAP_BACKGROUND);
        return Collections.unmodifiableMap(formats);
    }

    /** The widened format a family lane writes for a host layout, or null when none widens it. */
    public static VertexFormat extendedLayout(VertexFormat host) {
        return host == DefaultVertexFormat.BLOCK ? EXTENDED_BLOCK : HAND_FORMATS.get(host);
    }

    /** True for a widened format only a family lane writes: no host program reads its layout. */
    public static boolean isFamilyFormat(VertexFormat format) {
        return format == EXTENDED_BLOCK || HAND_FORMATS.containsValue(format);
    }

    /** True for a format whose normal Chimera computes per quad because the host writes none. */
    public static boolean computesNormal(VertexFormat format) {
        return format == EXTENDED_GLYPH;
    }

    public static VertexFormat handHostFormat(VertexFormat format) {
        for (var entry : handFormats().entrySet()) {
            if (format == entry.getKey() || format == entry.getValue()) return entry.getKey();
        }
        return null;
    }

    private static VertexFormat appendMaterialInputs(VertexFormat host) {
        return appendMaterialInputs(host, false);
    }

    private static VertexFormat appendMaterialInputs(VertexFormat host, boolean computedNormal) {
        VertexFormat.Builder builder = VertexFormat.builder();
        List<VertexFormatElement> elements = host.getElements();
        List<String> names = host.getElementAttributeNames();
        int hostElementBytes = 0;
        for (int index = 0; index < elements.size(); index++) {
            builder.add(names.get(index), elements.get(index));
            hostElementBytes += elements.get(index).byteSize();
        }
        if (host.getVertexSize() > hostElementBytes) {
            builder.padding(host.getVertexSize() - hostElementBytes);
        }
        if (computedNormal) {
            builder.add("Normal", VertexFormatElement.NORMAL);
            builder.padding(1);
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
