package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Converts programs drawn on the host's own vertex layouts, as Iris draws them natively: lines,
 * glint, lightning, beacon beams, leads, text and the water mask in both passes, and the sky and
 * cloud layouts.
 */
final class HostLayoutVertexBridge {
    private HostLayoutVertexBridge() {}

    /**
     * Converts the shadow program for a shadow-pass key Iris draws on the host's own layout
     * (IrisPipelines.assignToShadow: SHADOW_LINES, SHADOW_LEASH, SHADOW_LIGHTNING, SHADOW_TEXT,
     * SHADOW_TEXT_BG and SHADOW_BASIC, and GLINT's SHADOW_ENTITIES_CUTOUT). It is the
     * shadow_entities bridge on that layout's inputs: the legacy matrix built-ins name the draw's
     * host blocks, which the pass loads with the light view and projection, and an input the
     * layout lacks reads Iris's value for it. The host-layout preparation has already expanded
     * the includes. Reports the first concrete failure.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertShadowLaneVertexChecked(
            FamilyAdapterPlan.VertexContract contract,
            String source,
            String fragmentSource,
            UniformRegistry.ProgramInterface interfacePlan,
            boolean allowStorageBuffers
    ) {
        return TerrainVertexBridge.convertModernTerrainVertexOrThrow(source, null, fragmentSource,
                GlslVertexPreambles.shadowLanePreamble(contract), interfacePlan, true, true, allowStorageBuffers);
    }

    /** Selects the one host sky format that can provide the authored legacy inputs. */
    public static FamilyAdapterPlan.VertexContract skyVertexContract(
            String source, boolean textured
    ) {
        String stripped = stripComments(source == null ? "" : source);
        boolean color = GlslTokenRewriter.containsIdentifier(stripped, "gl_Color");
        if (textured) {
            return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV
                    : FamilyAdapterPlan.VertexContract.SKY_POSITION_UV;
        }
        return color ? FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR
                : FamilyAdapterPlan.VertexContract.SKY_POSITION;
    }

    /** Converts the narrow host sky vertex contract. */
    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource, boolean textured
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource,
                skyVertexContract(source, textured));
    }

    /** Converts a sky vertex against one validated host vertex format. */
    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract
    ) {
        return convertSkyVertex(source, sourceFile, fragmentSource, contract, Map.of(), false, List.of());
    }

    public static LegacyGlslConverter.TerrainVertexConversion convertSkyVertex(
            String source, Path sourceFile, String fragmentSource,
            FamilyAdapterPlan.VertexContract contract, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        try {
            String live = stripComments(
                    EntityDeclarations.removeEntityAttributes(LegacyGlslConverter.pruneUnreachableVertexFunctions(
                    LegacyGlslConverter.prepareSource(source, sourceFile, true))));
            if (LegacyGlslConverter.containsIdentifier(live, "mc_midTexCoord")
                    || LegacyGlslConverter.containsIdentifier(live, "at_tangent")
                    || LegacyGlslConverter.containsIdentifier(live, "Tangent")) {
                throw new IllegalArgumentException("sky format has no material mid-coordinate or tangent attribute");
            }
            return EntityVertexBridge.convertEntityVertexOrThrow(source, sourceFile, fragmentSource, locations,
                    GlslVertexPreambles.skyPreamble(contract), allowStorageBuffers, uniforms, null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /** One authored sky program runs against each real native sky mesh layout. */
    public static String skyVertexForContract(String converted, FamilyAdapterPlan.VertexContract contract) {
        for (var source : SKY_CONTRACTS) {
            String preamble = GlslVertexPreambles.skyPreamble(source);
            if (converted != null && converted.contains(preamble)) {
                return converted.replace(preamble, GlslVertexPreambles.skyPreamble(contract));
            }
        }
        throw new IllegalArgumentException("sky program lacks its native input contract");
    }

    /** Converts a cloud vertex onto the decoded cloud faces; null when it cannot run there. */
    public static LegacyGlslConverter.TerrainVertexConversion convertCloudVertex(
            String source, Path sourceFile, String fragmentSource
    ) {
        return convertCloudVertex(source, sourceFile, fragmentSource, Map.of(), false, List.of());
    }

    /**
     * Converts a cloud vertex onto vanilla's cloud mesh, with Iris's cloud inputs
     * (GlslVertexPreambles.CLOUD_VERTEX_PREAMBLE). The mesh carries no mid-coordinates or tangents,
     * so those attributes read GL's unbound default, as under Iris.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertCloudVertex(
            String source, Path sourceFile, String fragmentSource, Map<String, Integer> locations,
            boolean allowStorageBuffers, List<UniformRegistry.UniformDeclaration> uniforms
    ) {
        try {
            return EntityVertexBridge.convertEntityVertexOrThrow(unboundMaterialAttributes(source), sourceFile,
                    fragmentSource, locations, GlslVertexPreambles.CLOUD_VERTEX_PREAMBLE, allowStorageBuffers,
                    uniforms, null);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * Converts a host-layout vertex (after {@link #prepareLineVertex}, {@link #prepareGlintVertex}
     * or {@link #prepareHostLayoutSource}, and {@link #prepareHostLayoutIdentity}) onto its layout.
     */
    public static LegacyGlslConverter.TerrainVertexConversion convertHostLayoutVertexChecked(
            FamilyAdapterPlan.VertexContract contract, String source, String fragmentSource,
            Map<String, Integer> sharedLocations, boolean allowStorageBuffers,
            List<UniformRegistry.UniformDeclaration> uniforms,
            EntityVertexBridge.VertexSamplerPlan samplerPlan
    ) {
        String preamble = switch (contract) {
            case HOST_LINE -> GlslVertexPreambles.LINE_VERTEX_PREAMBLE;
            case HOST_GLINT -> GlslVertexPreambles.GLINT_VERTEX_PREAMBLE;
            case HOST_LIGHTNING -> GlslVertexPreambles.LIGHTNING_VERTEX_PREAMBLE;
            case HOST_BEACON -> GlslVertexPreambles.BEACON_VERTEX_PREAMBLE;
            case HOST_LEASH, HOST_TEXT_BACKGROUND -> GlslVertexPreambles.LEASH_VERTEX_PREAMBLE;
            default -> throw new IllegalArgumentException("not a host-layout contract: " + contract);
        };
        return EntityVertexBridge.convertEntityVertexOrThrow(unboundMaterialAttributes(source), null, fragmentSource,
                sharedLocations, preamble, allowStorageBuffers, uniforms, samplerPlan);
    }

    private static final Set<String> MATERIAL_ATTRIBUTES = Set.of("mc_midTexCoord", "at_tangent");

    /** GL's unbound attribute value (0, 0, 0, 1), cut to the declared type. */
    private static final Map<String, String> UNBOUND_VALUES = Map.of("float", "0.0", "vec2", "vec2(0.0)",
            "vec3", "vec3(0.0)", "vec4", "vec4(0.0, 0.0, 0.0, 1.0)");

    /**
     * A host layout without mid-coordinates or tangents leaves those attributes unbound in
     * Iris, so GL reads its default (0, 0, 0, 1) cut to the declared type.
     */
    static String unboundMaterialAttributes(String source) {
        GlslTree tree = GlslTree.parse(source);
        Map<String, String> defaults = new HashMap<>();
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            String unbound = UNBOUND_VALUES.get(variable.type());
            if (!MATERIAL_ATTRIBUTES.contains(variable.name()) || unbound == null || !variable.plain()
                    || !variable.qualified("attribute") && !variable.qualified("in")) {
                continue;
            }
            defaults.put(variable.name(), unbound);
            removals.add(GlslDeclarations.remove(tree, variable.declaration()));
        }
        if (defaults.isEmpty()) return source;
        return GlslTokenRewriter.replaceIdentifiers(GlslDeclarations.apply(source, removals), defaults);
    }

    public static final List<FamilyAdapterPlan.VertexContract> SKY_CONTRACTS = List.of(
            FamilyAdapterPlan.VertexContract.SKY_POSITION,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_UV,
            FamilyAdapterPlan.VertexContract.SKY_POSITION_COLOR_UV);
}
