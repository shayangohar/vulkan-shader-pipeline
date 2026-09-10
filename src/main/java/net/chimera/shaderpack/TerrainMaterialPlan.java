package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;

/** Immutable pack-wide material contract for terrain and water vertices. */
public record TerrainMaterialPlan(
        boolean modern,
        boolean midTexCoord,
        boolean midBlock,
        boolean tangent,
        boolean separateAo,
        List<String> deviations
) {
    public TerrainMaterialPlan {
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
    }

    public static TerrainMaterialPlan legacy() {
        return new TerrainMaterialPlan(false, false, false, false, false, List.of());
    }

    /** The common modern contract is twelve bytes, plus an optional AO word. */
    public int stride() {
        return modern ? 24 + 12 + (separateAo ? 4 : 0) : 24;
    }

    public static TerrainMaterialPlan forProgram(
            String name,
            String vertexSource,
            String fragmentSource,
            PackConfig.PackConfigData config
    ) {
        if (!name.equals("gbuffers_terrain") && !name.equals("gbuffers_water")
                && !name.equals("shadow")) {
            return legacy();
        }
        boolean supportedModern = name.equals("shadow")
                ? LegacyGlslConverter.supportsModernShadow(vertexSource, fragmentSource)
                : LegacyGlslConverter.requiresExtendedTerrain(vertexSource)
                || LegacyGlslConverter.requiresExtendedTerrain(fragmentSource);
        if (!supportedModern) {
            return legacy();
        }
        List<String> deviations = new ArrayList<>();
        deviations.add(name.equals("gbuffers_water")
                ? "MODERN_WATER_VERTEX_BRIDGE"
                : name.equals("shadow") ? "MODERN_SHADOW_VERTEX_BRIDGE"
                : "MODERN_TERRAIN_VERTEX_BRIDGE");
        deviations.add("TERRAIN_NORMAL_AUTHORITATIVE");
        deviations.add("TERRAIN_TANGENT_DERIVED_FALLBACK");
        boolean separateAo = config != null
                && config.settings() != null
                && "true".equalsIgnoreCase(config.settings().propertyValues().get("separateAo"));
        if (separateAo) {
            deviations.add("SEPARATE_AO_ATTRIBUTE");
            deviations.add("SEPARATE_AO_HOST_COLOR_FALLBACK");
        }
        return new TerrainMaterialPlan(true, true, true, true, separateAo, deviations);
    }

    public TerrainMaterialPlan merge(TerrainMaterialPlan other) {
        if (other == null || !other.modern()) {
            return this;
        }
        List<String> merged = new ArrayList<>(deviations);
        merged.addAll(other.deviations());
        return new TerrainMaterialPlan(true,
                midTexCoord || other.midTexCoord(),
                midBlock || other.midBlock(),
                tangent || other.tangent(),
                separateAo || other.separateAo(),
                merged);
    }
}
