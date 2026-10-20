package net.chimera.shaderpack;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Rejects what the entity vertex bridge cannot serve before it converts a stage: referenced custom
 * uniforms and images, unmapped built-ins, unserved attributes and pack-authored layouts. Each
 * failure names the first offender. Declarations and names are read from the parse tree.
 */
final class EntityVertexFeatureCheck {
    private EntityVertexFeatureCheck() {}

    /**
     * Legacy built-ins with token mappings in the entity vertex inputs
     * table. Every other gl_ builtin fails closed with its own name.
     */
    static final Set<String> ENTITY_SERVED_BUILTINS = Set.of(
            "gl_Vertex", "gl_Color", "gl_MultiTexCoord0", "gl_MultiTexCoord1",
            "gl_MultiTexCoord2", "gl_Normal", "gl_NormalMatrix", "gl_ModelViewMatrix",
            "gl_ModelViewProjectionMatrix", "gl_ProjectionMatrix", "gl_TextureMatrix",
            "gl_Position", "gl_VertexID");

    /** Host inputs the entity vertex bridge serves, with the types a stage may declare each as. */
    private static final Map<String, Set<String>> SERVED_ATTRIBUTE_TYPES = Map.of(
            "entityId", Set.of("float", "int"),
            "blockEntityId", Set.of("float", "int"),
            "mc_midTexCoord", Set.of("vec2", "vec4"),
            "at_tangent", Set.of("vec4"),
            // Iris capital-T tangent names the same host input.
            "Tangent", Set.of("vec4"),
            // Terrain inputs the entity format lacks read GL's unbound default.
            "mc_Entity", Set.of("float", "vec2", "vec3", "vec4"),
            "at_midBlock", Set.of("float", "vec2", "vec3", "vec4"));

    /** The value types the catalog could serve; a leftover one is a custom value with no adapter. */
    private static final Pattern VALUE_TYPE = Pattern.compile("bool|int|float|vec[234]|ivec[234]|mat[234]");
    private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler\\w*|u?i?image\\w*");
    private static final Pattern IMAGE_OR_STAGE = Pattern.compile("image\\w*|geometry|tessellation|compute");
    private static final Set<String> ATTRIBUTE_DECLARATION_TYPES = Set.of("int", "float", "vec2", "vec3", "vec4");

    /** {@code source} has its comments stripped already. */
    static void rejectEntityVertexFeatures(
            String source, boolean allowStorageBuffers, boolean allowSamplers
    ) {
        GlslTree tree = GlslTree.parse(source);
        rejectReferencedCustomUniforms(tree);
        rejectReferencedSamplers(tree, allowSamplers);
        String builtin = firstIdentifier(tree, name -> name.matches("gl_[A-Za-z]\\w*")
                && !ENTITY_SERVED_BUILTINS.contains(name));
        if (builtin != null) throw new IllegalArgumentException("unsupported entity vertex builtin: " + builtin);
        if (firstIdentifier(tree, name -> IMAGE_OR_STAGE.matcher(name).matches()) != null) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        if (!allowStorageBuffers && firstIdentifier(tree, "buffer"::equals) != null) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        rejectUnservedAttributes(tree);
        GlslTree withoutInterfaces = GlslTree.parse(withoutScannedInterfaces(source));
        rejectUnservedAttributeDeclarations(withoutInterfaces);
        // Interpolation survives on scanned varyings via the shared layout. Pack-authored
        // explicit layouts would conflict with the generated locations, and interpolation
        // qualifiers anywhere else have no adapter path.
        if (firstIdentifier(withoutInterfaces, Set.of("layout", "flat", "noperspective")::contains) != null) {
            throw new IllegalArgumentException("entity vertex layout is fixed by Chimera");
        }
    }

    /**
     * Served and dead uniform declarations are stripped before this check, so a remaining
     * value-type uniform names a referenced custom with no adapter. Matrix and texture built-ins
     * have token mappings in the inputs table.
     */
    private static void rejectReferencedCustomUniforms(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualified("uniform") && VALUE_TYPE.matcher(variable.type()).matches()
                    && referencedOutside(tree, variable.name(), variable.declaration())) {
                throw new IllegalArgumentException("unsupported entity vertex uniform: " + variable.name());
            }
        }
    }

    /** A referenced image, or a referenced sampler when the plan binds no vertex samplers, has no binding. */
    private static void rejectReferencedSamplers(GlslTree tree, boolean allowSamplers) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualified("uniform") || !SAMPLER_TYPE.matcher(variable.type()).matches()) continue;
            boolean image = variable.type().matches("u?i?image\\w*");
            if ((image || !allowSamplers) && variable.declaration().declarators().stream()
                    .anyMatch(name -> referencedOutside(tree, tree.text(name.name()), variable.declaration()))) {
                String first = tree.text(variable.declaration().declarators().get(0).name());
                throw new IllegalArgumentException("unsupported entity vertex sampler: " + first);
            }
        }
    }

    /** Each input names a host input the bridge serves, declared as a type it serves that input as. */
    private static void rejectUnservedAttributes(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if ((variable.qualified("attribute") || variable.qualified("in"))
                    && !SERVED_ATTRIBUTE_TYPES.getOrDefault(variable.name(), Set.of()).contains(variable.type())) {
                throw new IllegalArgumentException("unsupported entity attribute: " + variable.name());
            }
        }
    }

    /** An attribute the interface scanner left behind is one it could not read as a served input. */
    private static void rejectUnservedAttributeDeclarations(GlslTree tree) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualified("attribute") && !(variable.plain()
                    && ATTRIBUTE_DECLARATION_TYPES.contains(variable.type())
                    && SERVED_ATTRIBUTE_TYPES.containsKey(variable.name()))) {
                throw new IllegalArgumentException("unsupported entity attribute declaration");
            }
        }
    }

    /** The source without its file-scope interface declarations, once every input is a served one. */
    private static String withoutScannedInterfaces(String source) {
        // File-scope interfaces come from the shared scanner, so parameter qualifiers
        // (`const in float depth`) and integral or matrix varyings are not misread.
        try {
            for (GlslInterfaceScanner.Declaration input : GlslInterfaceScanner.scan(source, true).inputs()) {
                if (!SERVED_ATTRIBUTE_TYPES.containsKey(input.name())) {
                    throw new IllegalArgumentException("unsupported entity attribute: " + input.name());
                }
            }
            return GlslInterfaceScanner.rewrite(source, true, declaration -> "");
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("entity vertex interface: " + failure.getMessage());
        }
    }

    /** Whether any identifier outside {@code declaration} is {@code name}: the rest of the stage reads it. */
    private static boolean referencedOutside(GlslTree tree, String name, GlslTree.Declaration declaration) {
        for (int index = 0; index < tree.tokens().size(); index++) {
            if (!declaration.range().contains(index) && tree.tokens().get(index).identifier(name)) return true;
        }
        return false;
    }

    private static String firstIdentifier(GlslTree tree, Predicate<String> test) {
        for (GlslLexer.Token token : tree.tokens()) {
            if (token.kind() == GlslLexer.Kind.IDENTIFIER && test.test(token.text())) return token.text();
        }
        return null;
    }
}
