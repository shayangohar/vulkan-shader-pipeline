package net.chimera.shaderpack;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects what the entity vertex bridge cannot serve before it converts a stage: referenced custom
 * uniforms, images the plan does not bind, unmapped built-ins, unserved attributes and pack-authored
 * layouts. Each failure names the first offender. Declarations and names are read from the parse tree.
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
            // Terrain inputs the entity format lacks read GL's unbound default.
            "mc_Entity", Set.of("float", "vec2", "vec3", "vec4"),
            "at_midBlock", Set.of("float", "vec2", "vec3", "vec4"));

    /** The value types the catalog could serve; a leftover one is a custom value with no adapter. */
    private static final Pattern VALUE_TYPE = Pattern.compile("bool|int|float|vec[234]|ivec[234]|mat[234]");
    private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler\\w*|u?i?image\\w*");
    /**
     * Image types and image built-ins; not identifiers that only start with "image" (Reverie's
     * image0Sampler is a sampler).
     */
    private static final Pattern IMAGE = Pattern.compile(
            "[iu]?image(?:1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)"
                    + "|image(?:Load|Store|Size|Samples|Atomic\\w+)");
    private static final Set<String> OTHER_STAGES = Set.of("geometry", "tessellation", "compute");
    private static final Set<String> ATTRIBUTE_DECLARATION_TYPES = Set.of("int", "float", "vec2", "vec3", "vec4");

    /**
     * {@code source} has its comments stripped already. {@code storageImages} names the pack images
     * the plan binds this stage (GraphicsImageScan); Iris binds images in every stage, and Pegasus
     * stores particle light from gbuffers_particles' vertex (PARTICLES_LPV).
     */
    static void rejectEntityVertexFeatures(
            String source, boolean allowStorageBuffers, boolean allowSamplers, Set<String> storageImages
    ) {
        GlslTree tree = GlslTree.parse(source);
        rejectReferencedCustomUniforms(tree);
        rejectUnboundSamplers(tree, allowSamplers, storageImages);
        String builtin = firstIdentifier(tree, name -> name.matches("gl_[A-Za-z]\\w*")
                && !ENTITY_SERVED_BUILTINS.contains(name));
        if (builtin != null) throw new IllegalArgumentException("unsupported entity vertex builtin: " + builtin);
        if (firstIdentifier(tree, name -> OTHER_STAGES.contains(name)
                || storageImages.isEmpty() && IMAGE.matcher(name).matches()) != null) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        if (!allowStorageBuffers && firstIdentifier(tree, "buffer"::equals) != null) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        rejectUnservedAttributes(tree);
        // An admitted storage block or image keeps its own layout; the plan rewrites its binding.
        String interfaces = allowStorageBuffers ? GlslStorageBufferParser.withoutBlocks(source) : source;
        interfaces = withoutImageDeclarations(interfaces, storageImages);
        GlslTree withoutInterfaces = GlslTree.parse(withoutScannedInterfaces(interfaces));
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

    /**
     * A referenced sampler when the plan binds no vertex samplers has no binding; nor has an image
     * the plan does not bind, referenced or not, since the stage keeps every declaration it is given.
     */
    private static void rejectUnboundSamplers(GlslTree tree, boolean allowSamplers, Set<String> storageImages) {
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualified("uniform") || !SAMPLER_TYPE.matcher(variable.type()).matches()) continue;
            boolean image = variable.type().matches("u?i?image\\w*");
            boolean unbound = image
                    ? variable.declaration().declarators().stream()
                            .anyMatch(name -> !storageImages.contains(tree.text(name.name())))
                    : !allowSamplers && variable.declaration().declarators().stream()
                            .anyMatch(name -> referencedOutside(tree, tree.text(name.name()), variable.declaration()));
            if (unbound) {
                String first = tree.text(variable.declaration().declarators().get(0).name());
                throw new IllegalArgumentException("unsupported entity vertex sampler: " + first);
            }
        }
    }

    /** The source without the declarations of the images the plan binds (PackPipelines.STORAGE_IMAGE_DECLARATION). */
    private static String withoutImageDeclarations(String source, Set<String> storageImages) {
        if (storageImages.isEmpty()) return source;
        return PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(source).replaceAll(declaration ->
                storageImages.contains(declaration.group(2)) ? "" : Matcher.quoteReplacement(declaration.group()));
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
