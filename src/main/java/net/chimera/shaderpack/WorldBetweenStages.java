package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a world program's stages between the vertex and fragment stages share, its tessellation
 * stages (TessellationStageBridge) and its geometry stage (GeometryStageBridge.convertWorld)
 * alike: the legacy built-ins and samplers they read, served as the vertex stage serves them; the
 * preamble declaring the host blocks and pack uniforms they read; and, for a hand program, the
 * values the entity vertex bridge adds to the vertex stage's outputs for the fragment stage, which
 * they pass on ({@link PassedOn}).
 */
final class WorldBetweenStages {
    private WorldBetweenStages() {}

    /**
     * A stage's legacy built-ins served once its interface is located: a {@code hand} stage reads its
     * draw's own matrices (GlslVertexPreambles.handMatrixBuiltins), a terrain stage the host's in GL's
     * clip range; then the texture matrix, the pack's samplers and its texture calls.
     */
    static String builtins(String converted, UniformRegistry.ProgramInterface plan, boolean hand) {
        String result = GlslTokenRewriter.replaceIdentifiers(converted, hand ? GlslVertexPreambles.handMatrixBuiltins()
                : WorldClipRange.GL.builtins(GlslVertexPreambles.terrainMatrixBuiltins()));
        // Iris gives the hand the draw's texture matrix (iris_TextureMat), as its vertex stage reads it.
        result = LegacyGlslConverter.replaceLightmapTextureMatrices(result)
                .replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", hand ? "TextureMat" : "mat4(1.0)");
        result = TerrainVertexBridge.rewriteModernTerrainSamplers(result, plan, false);
        return LegacyGlslConverter.convertTextureCalls(result);
    }

    /**
     * What a {@code converted} stage needs above it: its version, the render-stage defines, the host
     * blocks it reads matrices from and the pack's uniforms. A terrain stage declares the terrain
     * view block where it reads MVP, and the terrain uniform block; a {@code hand} stage the host's
     * transform and projection blocks and the entity family's block, each where it reads them.
     */
    static String preamble(String converted, UniformRegistry.ProgramInterface plan, boolean hand) {
        String host = hand ? handHostBlocks(converted)
                : GlslTokenRewriter.containsIdentifier(converted, "MVP") ? GlslVertexPreambles.TERRAIN_VIEW_BLOCK : "";
        String uniforms = !hand ? TerrainVertexBridge.modernUniformBlock(plan, false)
                : GlslVertexPreambles.entityCameraUniformBlock(converted, plan == null ? null
                : plan.executableUniforms());
        return "#version 460\n" + FragmentConverter.SHADOW_RENDER_STAGE_DEFINES + host + uniforms;
    }

    /** The host's transform and projection blocks a hand stage reads its draw's matrices from, each where read. */
    private static String handHostBlocks(String converted) {
        String blocks = GlslTokenRewriter.containsIdentifier(converted, "ModelViewMat")
                || GlslTokenRewriter.containsIdentifier(converted, "TextureMat")
                ? GlslVertexPreambles.DYNAMIC_TRANSFORMS_BLOCK : "";
        return GlslTokenRewriter.containsIdentifier(converted, "chimeraHandProjection")
                ? blocks + GlslVertexPreambles.PROJECTION_BLOCK + GlslVertexPreambles.HAND_PROJECTION : blocks;
    }

    /** An interpolation qualifier as a declaration prefix: itself and a space, or nothing. */
    static String qualified(String qualifier) {
        return qualifier == null || qualifier.isEmpty() ? "" : qualifier + " ";
    }

    /** A declaration's array suffix, {@code [n]}, or nothing. */
    static String arraySuffix(GlslInterfaceScanner.Declaration declaration) {
        return declaration.arraySize() == 0 ? "" : "[" + declaration.arraySize() + "]";
    }

    /**
     * The values the entity vertex bridge adds to the vertex stage's outputs for the fragment stage,
     * each with its type and qualifier, which a hand's tessellation or geometry stages pass on. The
     * control stage copies each per vertex. The evaluation stage writes a smooth float as the pack's
     * own values are, at the evaluated point. It writes any other value as the patch's first
     * vertex's, as Iris reads iris_entityInfo[0]; a geometry stage writes each as the primitive's
     * first vertex's.
     */
    record PassedOn(Map<String, String> types, Map<String, String> qualifiers) {
        static final PassedOn NONE = new PassedOn(Map.of(), Map.of());
        /** The suffix of a value's per-vertex copy; the evaluation stage writes the vertex stage's name. */
        static final String PATCH_SUFFIX = "TC";

        static PassedOn of(LegacyGlslConverter.TerrainVaryingLayout vertexLayout) {
            List<String> names = new ArrayList<>();
            EntityIdRewrites.ENTITY_ID_NAMES.forEach(name -> names.add(EntityIdRewrites.entityIdVarying(name)));
            EntityVertexBridge.ForwardedVarying.ALL.forEach(varying -> names.add(varying.name()));
            Map<String, String> types = new LinkedHashMap<>();
            Map<String, String> qualifiers = new LinkedHashMap<>();
            for (String name : names) {
                if (!vertexLayout.locations().containsKey(name)) continue;
                String type = vertexLayout.types().get(name);
                types.put(name, type);
                // An integer is flat, as the fragment stage takes it.
                qualifiers.put(name, type.matches("u?int|[iu]vec[234]") ? "flat" : vertexLayout.qualifier(name));
            }
            return new PassedOn(types, qualifiers);
        }

        /**
         * The fragment stage's inputs located from the last stage's {@code producer} outputs, then
         * each value after the producer's own slots, under the vertex stage's name.
         */
        LegacyGlslConverter.TerrainVaryingLayout outputLayout(String producer, String fragment) {
            String reads = LegacyGlslConverter.expandModernVaryingLists(
                    TerrainVertexBridge.normalizedFragment(fragment, false));
            LegacyGlslConverter.TerrainVaryingLayout own = TerrainVertexBridge.modernVaryingLayout(producer, reads,
                    Map.of());
            if (types.isEmpty()) return own;
            return appendedTo(own, EntityVertexBridge.parseEntityVaryingDeclarations(producer, true).slots().values()
                    .stream().mapToInt(Integer::intValue).sum(), "");
        }

        /** {@code layout} and each value after it from {@code location}, its name with {@code suffix}. */
        LegacyGlslConverter.TerrainVaryingLayout appendedTo(LegacyGlslConverter.TerrainVaryingLayout layout,
                                                            int location, String suffix) {
            if (types.isEmpty()) return layout;
            Map<String, String> allTypes = new TreeMap<>(layout.types());
            Map<String, Integer> locations = new TreeMap<>(layout.locations());
            Map<String, String> allQualifiers = new TreeMap<>(layout.qualifiers());
            int next = location;
            for (String name : types.keySet()) {
                allTypes.put(name + suffix, types.get(name));
                locations.put(name + suffix, next++);
                if (!qualifiers.get(name).isEmpty()) allQualifiers.put(name + suffix, qualifiers.get(name));
            }
            return new LegacyGlslConverter.TerrainVaryingLayout(allTypes, locations, allQualifiers);
        }

        /** The control stage reading each value from the vertex stage and copying it per vertex. */
        String control(String converted, LegacyGlslConverter.TerrainVaryingLayout inputs,
                       LegacyGlslConverter.TerrainVaryingLayout patch) {
            String result = converted;
            StringBuilder declarations = new StringBuilder();
            for (String name : types.keySet()) {
                declarations.append(declaration(inputs, name, "in", "[]"))
                        .append(declaration(patch, name + PATCH_SUFFIX, "out", "[]"));
                result = LegacyGlslConverter.injectMainPrologue(result,
                        name + PATCH_SUFFIX + "[gl_InvocationID] = " + name + "[gl_InvocationID];");
            }
            return beforeMain(result, declarations.toString());
        }

        /** The evaluation stage reading each value's copies and writing the value the fragment stage reads. */
        String evaluation(String converted, LegacyGlslConverter.TerrainVaryingLayout patch,
                          LegacyGlslConverter.TerrainVaryingLayout outputs) {
            String result = converted;
            StringBuilder declarations = new StringBuilder();
            for (String name : types.keySet()) {
                String copies = name + PATCH_SUFFIX;
                declarations.append(declaration(patch, copies, "in", "[]"))
                        .append(declaration(outputs, name, "out", ""));
                boolean smooth = qualifiers.get(name).isEmpty() && types.get(name).matches("float|vec[234]");
                String value = !smooth ? copies + "[0]" : "gl_TessCoord.x * " + copies + "[0] + gl_TessCoord.y * "
                        + copies + "[1] + gl_TessCoord.z * " + copies + "[2]";
                result = LegacyGlslConverter.injectMainPrologue(result, name + " = " + value + ";");
            }
            return beforeMain(result, declarations.toString());
        }

        /**
         * A geometry stage's declarations: each value in per vertex at the vertex stage's location,
         * and out with {@code suffix} at the fragment stage's.
         */
        String geometryDeclarations(LegacyGlslConverter.TerrainVaryingLayout inputs,
                                    LegacyGlslConverter.TerrainVaryingLayout outputs, String suffix) {
            StringBuilder declarations = new StringBuilder();
            for (String name : types.keySet()) {
                String qualifier = qualified(qualifiers.get(name));
                declarations.append("layout(location = ").append(inputs.location(name)).append(") ")
                        .append(qualifier).append("in ").append(types.get(name)).append(' ').append(name)
                        .append("[];\n")
                        .append("layout(location = ").append(outputs.location(name)).append(") ")
                        .append(qualifier).append("out ").append(types.get(name)).append(' ').append(name)
                        .append(suffix).append(";\n");
            }
            return declarations.toString();
        }

        /** What a geometry stage writes before each emit: each value's output, from the primitive's first vertex. */
        List<String> geometryStatements(String suffix) {
            return types.keySet().stream().map(name -> name + suffix + " = " + name + "[0];").toList();
        }

        private static String declaration(LegacyGlslConverter.TerrainVaryingLayout layout, String name,
                                          String direction, String array) {
            return "layout(location = " + layout.location(name) + ") " + qualified(layout.qualifier(name))
                    + direction + " " + layout.types().get(name) + " " + name + array + ";\n";
        }

        private static String beforeMain(String source, String declarations) {
            if (declarations.isEmpty()) return source;
            GlslTree tree = GlslTree.parse(source);
            int main = tree.offset(GlslDeclarations.mainDefinition(tree).range().start());
            return source.substring(0, main) + declarations + source.substring(main);
        }
    }
}
