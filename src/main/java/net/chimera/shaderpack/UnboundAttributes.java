package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Vertex inputs with no vertex data behind them. GL then reads the current generic attribute,
 * (0, 0, 0, 1) unless set, cut to the declared type. Each such input becomes a constant of that
 * value, so the stage keeps its GLSL scoping and no bridge has to serve or refuse it.
 */
final class UnboundAttributes {
    private UnboundAttributes() {}

    /**
     * The attribute names Iris binds vertex data to (Iris ProgramCreator and IrisVertexFormats):
     * vanilla's elements, its extended formats' inputs, and the core-profile names its
     * transformers map onto those elements. Each lane serves these or names why it cannot.
     */
    static final Set<String> IRIS_BOUND = Set.of(
            "Position", "Color", "UV0", "UV1", "UV2", "Normal",
            "iris_Entity", "mc_Entity", "mc_midTexCoord", "at_tangent", "at_midBlock",
            "vaPosition", "vaColor", "vaUV0", "vaUV1", "vaUV2", "vaNormal");

    /** Names Chimera's lanes serve themselves: the per-vertex identities and the capital-T tangent. */
    private static final Set<String> CHIMERA_SERVED = Set.of(
            "entityId", "blockEntityId", "currentRenderedItemId", "Tangent");

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("float", "0.0"), Map.entry("vec2", "vec2(0.0)"), Map.entry("vec3", "vec3(0.0)"),
            Map.entry("vec4", "vec4(0.0, 0.0, 0.0, 1.0)"),
            Map.entry("int", "0"), Map.entry("ivec2", "ivec2(0)"), Map.entry("ivec3", "ivec3(0)"),
            Map.entry("ivec4", "ivec4(0, 0, 0, 1)"),
            Map.entry("uint", "0u"), Map.entry("uvec2", "uvec2(0u)"), Map.entry("uvec3", "uvec3(0u)"),
            Map.entry("uvec4", "uvec4(0u, 0u, 0u, 1u)"));

    private static final Set<List<String>> INPUT_QUALIFIERS = Set.of(List.of("attribute"), List.of("in"));

    /** Whether Iris leaves {@code name} without vertex data in every vertex format. */
    static boolean irisUnbound(String name) {
        return !IRIS_BOUND.contains(name) && !CHIMERA_SERVED.contains(name);
    }

    /** GL's default generic attribute value cut to {@code type}; null for a type an attribute cannot have. */
    static String defaultValue(String type) {
        return DEFAULTS.get(type);
    }

    /**
     * Each plain {@code attribute} or {@code in} declaration whose name {@code unbound} accepts
     * becomes {@code const type name = default;}. An input with a layout keeps its declaration:
     * its explicit location decides what it reads.
     */
    static String substitute(String source, Predicate<String> unbound) {
        if (source == null || source.isEmpty()) return source;
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            String value = defaultValue(variable.type());
            if (value == null || !variable.plain() || !INPUT_QUALIFIERS.contains(variable.qualifiers())
                    || !unbound.test(variable.name())) {
                continue;
            }
            edits.add(new GlslDeclarations.TextEdit(tree.offset(variable.declaration().range().start()),
                    tree.offset(variable.declaration().range().end()),
                    "const " + variable.type() + " " + variable.name() + " = " + value + ";"));
        }
        return GlslDeclarations.apply(source, edits);
    }
}
