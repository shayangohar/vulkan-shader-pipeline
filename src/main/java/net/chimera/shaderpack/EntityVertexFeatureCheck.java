package net.chimera.shaderpack;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rejects what the entity vertex bridge cannot serve before it converts a stage: referenced custom
 * uniforms and images, unmapped built-ins, unserved attributes and pack-authored layouts. Each
 * failure names the first offender.
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

    static void rejectEntityVertexFeatures(
            String source, boolean allowStorageBuffers, boolean allowSamplers
    ) {
        rejectReferencedCustomUniforms(source);
        rejectReferencedSamplers(source, allowSamplers);
        rejectUnservedBuiltins(source);
        if (source.matches("(?s).*\\b(?:image\\w*|geometry|tessellation|compute)\\b.*")) {
            throw new IllegalArgumentException("unsupported entity vertex feature");
        }
        if (!allowStorageBuffers && source.matches("(?s).*\\bbuffer\\b.*")) {
            throw new IllegalArgumentException("storage buffer was not admitted by the program plan");
        }
        rejectUnservedAttributes(source);
        String withoutInterfaces = withoutScannedInterfaces(source);
        if (withoutInterfaces.matches("(?s).*\\battribute\\b.*")
                && !withoutInterfaces.matches("(?s).*\\battribute\\s+(?:int|float|vec2|vec3|vec4)\\s+"
                + "(?:entityId|blockEntityId|mc_midTexCoord|at_tangent|Tangent|mc_Entity|at_midBlock)\\s*;.*")) {
            throw new IllegalArgumentException("unsupported entity attribute declaration");
        }
        // Interpolation survives on scanned varyings via the shared layout. Pack-authored
        // explicit layouts would conflict with the generated locations, and interpolation
        // qualifiers anywhere else have no adapter path.
        if (withoutInterfaces.matches("(?s).*\\b(?:layout|flat|noperspective)\\b.*")) {
            throw new IllegalArgumentException("entity vertex layout is fixed by Chimera");
        }
    }

    private static void rejectReferencedCustomUniforms(String source) {
        // Served and dead uniform declarations are stripped before this
        // check, so a remaining value-type uniform names a referenced
        // custom with no adapter. Matrix and texture built-ins have
        // token mappings in the inputs table.
        Matcher leftoverUniform = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:highp|mediump|lowp\\s+)?(?:bool|int|float|vec[234]|ivec[234]|mat[234])\\s+"
                        + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;").matcher(source);
        while (leftoverUniform.find()) {
            String checkSource = withoutMatch(source, leftoverUniform);
            for (String declarator : leftoverUniform.group(1).split(",")) {
                String name = declarator.trim();
                if (GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    throw new IllegalArgumentException(
                            "unsupported entity vertex uniform: " + name);
                }
            }
        }
    }

    /** A referenced image, or a referenced sampler when the plan binds no vertex samplers, has no binding. */
    private static void rejectReferencedSamplers(String source, boolean allowSamplers) {
        Matcher vertexSampler = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:[iu]?sampler\\w*|u?i?image\\w*)\\s+"
                        + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)*)\\s*;\\s*$").matcher(source);
        while (vertexSampler.find()) {
            String checkSource = withoutMatch(source, vertexSampler);
            boolean referenced = false;
            for (String declarator : vertexSampler.group(1).split(",")) {
                if (GlslTokenRewriter.containsIdentifier(checkSource, declarator.trim())) {
                    referenced = true;
                    break;
                }
            }
            boolean image = vertexSampler.group().matches("(?s).*\\buniform\\s+u?i?image\\w*.*");
            if (referenced && (image || !allowSamplers)) {
                String first = vertexSampler.group(1).split(",")[0].trim();
                throw new IllegalArgumentException(
                        "unsupported entity vertex sampler: " + first);
            }
        }
    }

    private static void rejectUnservedBuiltins(String source) {
        Matcher builtin = Pattern.compile("\\bgl_[A-Za-z]\\w*\\b").matcher(source);
        while (builtin.find()) {
            if (!ENTITY_SERVED_BUILTINS.contains(builtin.group())) {
                throw new IllegalArgumentException(
                        "unsupported entity vertex builtin: " + builtin.group());
            }
        }
    }

    private static void rejectUnservedAttributes(String source) {
        Matcher attributes = Pattern.compile(
                "(?m)\\b(attribute|in)\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;").matcher(source);
        while (attributes.find()) {
            String type = attributes.group(2);
            String name = attributes.group(3);
            if (!SERVED_ATTRIBUTE_TYPES.getOrDefault(name, Set.of()).contains(type)) {
                throw new IllegalArgumentException("unsupported entity attribute: " + name);
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

    /** The source without one matched declaration, to ask whether the rest of the stage reads a name. */
    private static String withoutMatch(String source, Matcher match) {
        return source.substring(0, match.start()) + source.substring(match.end());
    }
}
