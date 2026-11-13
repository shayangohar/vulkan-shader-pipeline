package net.chimera.shaderpack;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Iris's per-vertex entity identities (EntityPatcher): entityId, blockEntityId and
 * currentRenderedItemId, each one component of the EntityIds input. A converted vertex stage copies
 * each identity it or a later stage reads into a flat varying, and the later stages read the
 * identity back through it. The vertex, geometry, between-stage and fragment converters, the core
 * profile rewrite, the entity declarations and the uniform planning share these names and rewrites.
 */
final class EntityIdRewrites {
    /**
     * Iris EntityPatcher identities: each name reads one component of the
     * per-vertex EntityIds input (Iris iris_Entity), passed flat to fragments.
     */
    static final List<String> ENTITY_ID_NAMES = List.of("entityId", "blockEntityId", "currentRenderedItemId");

    static final String ENTITY_ID_VARYING = "chimeraEntityId";

    private EntityIdRewrites() {}

    static String entityIdVarying(String name) {
        return switch (name) {
            case "entityId" -> ENTITY_ID_VARYING;
            case "blockEntityId" -> "chimeraBlockEntityId";
            default -> "chimeraRenderedItemId";
        };
    }

    static String entityIdComponent(String name) {
        return switch (name) {
            case "entityId" -> "x";
            case "blockEntityId" -> "y";
            default -> "z";
        };
    }

    /** The entity identity types either stage declares, in {@code ENTITY_ID_NAMES} order. */
    static Map<String, String> entityIdTypes(String strippedVertex, String fragmentSource) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            String type = entityIdentifierType(name, strippedVertex, fragmentSource);
            if (type != null) types.put(name, type);
        }
        return types;
    }

    /** Copies each named identity from the EntityIds input into its flat varying at the start of main. */
    static String injectEntityIdPrologues(String source, Collection<String> names) {
        String result = source;
        for (String name : names) {
            result = LegacyGlslConverter.injectMainPrologue(result,
                    entityIdVarying(name) + " = EntityIds." + entityIdComponent(name) + ";");
        }
        return result;
    }

    /** The flat output declaration of each named identity's varying, at its layout location. */
    static String entityIdOutputDeclarations(Collection<String> names,
                                             LegacyGlslConverter.TerrainVaryingLayout layout) {
        StringBuilder declarations = new StringBuilder();
        for (String name : names) {
            declarations.append("layout(location = " + layout.location(entityIdVarying(name))
                    + ") flat out uint " + entityIdVarying(name) + ";\n");
        }
        return declarations.toString();
    }

    /**
     * {@code source} with a vertex stage's input {@code replacements}: a built-in everywhere, an entity
     * identity only where it names the global (GlslScopedReferences). A pack may give a parameter or
     * local an identity's name, as Shrimple's GetSceneEntityLightColor(const in int entityId) does.
     */
    static String replaceInputs(String source, Map<String, String> replacements) {
        Map<String, String> others = new HashMap<>(replacements);
        Map<String, String> identities = new HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            String value = others.remove(name);
            if (value != null && GlslTokenRewriter.containsIdentifier(source, name)) identities.put(name, value);
        }
        return GlslScopedReferences.replace(GlslTokenRewriter.replaceIdentifiers(source, others), identities);
    }

    /** Each named identity read through its flat varying. */
    static Map<String, String> entityIdReplacements(Collection<String> names,
                                                    LegacyGlslConverter.TerrainVaryingLayout layout) {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (String name : names) {
            replacements.put(name, entityIdExpression(layout, name));
        }
        return replacements;
    }

    static String entityIdentifierType(String name, String vertexSource, String fragmentSource) {
        String vertex = vertexSource == null ? "" : vertexSource;
        String fragment = fragmentSource == null ? "" : stripComments(fragmentSource);
        String declared = EntityDeclarations.declaredEntityIdType(vertex, name);
        if (declared == null) declared = EntityDeclarations.declaredEntityIdType(fragment, name);
        if (declared != null) return declared;
        if (LegacyGlslConverter.containsIdentifier(vertex, name)
                || LegacyGlslConverter.containsIdentifier(fragment, name)) {
            return "int";
        }
        return null;
    }

    static String entityIdExpression(LegacyGlslConverter.TerrainVaryingLayout layout, String name) {
        return entityIdExpression(layout, name, "");
    }

    /** The identity read through its varying's {@code element}: "[0]" for a geometry stage's first vertex. */
    static String entityIdExpression(LegacyGlslConverter.TerrainVaryingLayout layout, String name, String element) {
        String varying = entityIdVarying(name);
        if (layout != null && layout.locations().containsKey(varying)) {
            return "float".equals(layout.types().get(varying))
                    ? "float(" + varying + element + ")"
                    : "int(" + varying + element + ")";
        }
        return "float(EntityIds." + entityIdComponent(name) + ")";
    }

    static String replaceEntityIdReferences(
            String source,
            LegacyGlslConverter.TerrainVaryingLayout layout
    ) {
        if (layout == null) return source;
        Map<String, String> replacements = new HashMap<>();
        for (String name : ENTITY_ID_NAMES) {
            if (layout.locations().containsKey(entityIdVarying(name))) {
                replacements.put(name, entityIdExpression(layout, name));
            }
        }
        // Only the uniform: Shrimple's GetSceneEntityLightColor(const in int entityId) names its own.
        return replacements.isEmpty() ? source : GlslScopedReferences.replace(source, replacements);
    }
}
