package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * A program's stage sources as planning reads them: its prepared snapshots, or its executable
 * sources, with modern post and extended-terrain stages normalized.
 */
final class ProgramStageSources {
    private ProgramStageSources() {}

    /**
     * The stages of a program planned under {@code name}, without a tessellation stage the pack does
     * not enable ({@code tessellation}, PackProgram.runsBetweenStages); post and terrain-material
     * stages normalized.
     */
    static Map<String, PreparedShaderSource> prepare(PackProgram program, String name, boolean terrainMaterial,
                                                     boolean tessellation) {
        Map<String, PreparedShaderSource> stages = preparedStages(program);
        stages.keySet().removeIf(stage -> !stage.equals("vertex") && !stage.equals("fragment")
                && !PackProgram.runsBetweenStages(stage, tessellation));
        Map<String, PreparedShaderSource> prepared = withoutInactiveImages(flattenInterfaceBlocks(stages));
        if (FamilyAdapterRegistry.stageFor(name) == UniformRegistry.Stage.POST) {
            return normalizeModernPostStages(prepared);
        }
        return terrainMaterial ? normalizeModernTerrainStages(prepared) : prepared;
    }

    private static Map<String, PreparedShaderSource> preparedStages(PackProgram program) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(program.preparedSources());
        if (result.isEmpty()) {
            if (program.fragmentSource() != null) {
                result.put("fragment", new PreparedShaderSource("fragment",
                        relative(program.sourceRoot(), program.fragmentPath()),
                        program.executableFragmentSource(), List.of(), List.of()));
            }
            if (program.vertexSource() != null) {
                result.put("vertex", new PreparedShaderSource("vertex",
                        relative(program.sourceRoot(), program.vertexPath()),
                        program.executableVertexSource(), List.of(), List.of()));
            }
        }
        return result;
    }

    /** The stages GL links, in the order each feeds the next. */
    private static final List<String> LINK_ORDER = List.of("vertex", "tess_control", "tess_evaluation", "geometry",
            "fragment");
    /** The suffix of the varyings each stage writes, which the stage after it reads (InterfaceBlockFlattener). */
    private static final Map<String, String> WRITER_SUFFIXES = Map.of("vertex", "", "tess_control", "_tc",
            "tess_evaluation", "_te", "geometry", "_gs", "fragment", "");

    /**
     * Every stage with its interface blocks as plain varyings (InterfaceBlockFlattener). Each
     * stage's inputs carry the suffix of the stage before it and its outputs its own, so a stage
     * between the vertex and fragment stages can read and write blocks of one name.
     */
    private static Map<String, PreparedShaderSource> flattenInterfaceBlocks(Map<String, PreparedShaderSource> stages) {
        if (!Set.copyOf(LINK_ORDER).containsAll(stages.keySet())) return stages;
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        String inputSuffix = "";
        for (String stage : LINK_ORDER) {
            PreparedShaderSource prepared = stages.get(stage);
            if (prepared == null) continue;
            String outputSuffix = WRITER_SUFFIXES.get(stage);
            InterfaceBlockFlattener.Result flattened = InterfaceBlockFlattener.flatten(prepared.source(),
                    inputSuffix, outputSuffix);
            inputSuffix = outputSuffix;
            if (flattened.blocks().isEmpty()) continue;
            List<String> deviations = new ArrayList<>(prepared.deviations());
            flattened.blocks().forEach(block -> deviations.add("INTERFACE_BLOCK_FLATTENED:" + block));
            result.put(stage, new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    flattened.source(), prepared.dependencies(), deviations));
        }
        return result;
    }

    /**
     * Each stage without the image declarations nothing in it reads. GL leaves such a uniform
     * inactive, and Vulkan GLSL would need a binding for it: Reverie's programs all declare its
     * screen-sized images (image0-2, water_depth_min, atm_image) and most read none of them. A read
     * in a function main never reaches is none: GL compiles it away, and so do the converters
     * (Rethinking Voxels' composite7 comments out the call that reaches its voxel images). Such a
     * stage goes on without its dead functions, so no converter meets a read of an image that is
     * no longer declared. Line breaks stay, so line numbers do not move.
     */
    private static Map<String, PreparedShaderSource> withoutInactiveImages(Map<String, PreparedShaderSource> stages) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            String source = prepared.source();
            if (source == null || !source.contains("image")) continue;
            String reachable = LegacyGlslConverter.pruneUnreachableFunctions(source);
            String rewritten = withoutUnread(readOnlyInDeadCode(source, reachable) ? reachable : source, reachable);
            if (rewritten.equals(source)) continue;
            result.put(entry.getKey(), new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    rewritten, prepared.dependencies(), prepared.deviations()));
        }
        return result;
    }

    /** {@code text} with each image declaration {@code reachable} does not read blanked. */
    private static String withoutUnread(String text, String reachable) {
        Matcher declaration = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(text);
        StringBuilder active = new StringBuilder();
        while (declaration.find()) {
            String kept = GlslTokenRewriter.identifierCount(reachable, declaration.group(2)) > 1
                    ? declaration.group() : declaration.group().replaceAll("[^\\r\\n]", "");
            declaration.appendReplacement(active, Matcher.quoteReplacement(kept));
        }
        return declaration.appendTail(active).toString();
    }

    /** Whether an image declaration is read only in functions main never reaches. */
    private static boolean readOnlyInDeadCode(String source, String reachable) {
        Matcher declaration = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(reachable);
        while (declaration.find()) {
            String name = declaration.group(2);
            if (GlslTokenRewriter.identifierCount(reachable, name) <= 1
                    && GlslTokenRewriter.identifierCount(source, name) > 1) return true;
        }
        return false;
    }

    private static Map<String, PreparedShaderSource> normalizeModernPostStages(
            Map<String, PreparedShaderSource> stages
    ) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            if (prepared.source() == null || !hasModernVersion(prepared.source())) {
                continue;
            }
            String normalized = PostVertexBridge.normalizeModernPost(prepared.source());
            List<String> deviations = new ArrayList<>(prepared.deviations());
            deviations.add("MODERN_GLSL_TRANSLATED");
            result.put(entry.getKey(), new PreparedShaderSource(
                    prepared.stage(), prepared.relativePath(), normalized,
                    prepared.dependencies(), deviations));
        }
        return result;
    }

    private static Map<String, PreparedShaderSource> normalizeModernTerrainStages(
            Map<String, PreparedShaderSource> stages
    ) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        boolean extended = stages.values().stream().anyMatch(value ->
                value.source() != null && TerrainVertexBridge.requiresExtendedTerrain(value.source()));
        if (!extended) {
            return result;
        }
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            if (prepared.source() == null || !TerrainVertexBridge.requiresExtendedTerrain(prepared.source())) {
                continue;
            }
            String normalized;
            try {
                normalized = entry.getKey().equals("fragment")
                        ? TerrainVertexBridge.normalizeModernTerrainFragment(prepared.source())
                        : TerrainVertexBridge.normalizeModernTerrain(prepared.source());
            } catch (IllegalArgumentException unsupportedVersion) {
                // Leave unsupported versions authored so the program plan can
                // record a per-program fallback instead of rejecting the pack.
                continue;
            }
            List<String> deviations = new ArrayList<>(prepared.deviations());
            deviations.add("MODERN_GLSL_TRANSLATED");
            result.put(entry.getKey(), new PreparedShaderSource(
                    prepared.stage(), prepared.relativePath(), normalized,
                    prepared.dependencies(), deviations));
        }
        return result;
    }

    /** Post stages in modern syntax: GLSL 140 and later, where in/out and layouts appear. */
    private static boolean hasModernVersion(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.supported() && version.number() >= 140;
    }

    static String source(
            Map<String, PreparedShaderSource> stages,
            String stage,
            String fallback
    ) {
        PreparedShaderSource prepared = stages.get(stage);
        return prepared == null ? fallback : prepared.source();
    }

    private static String relative(Path root, Path path) {
        if (root == null || path == null) {
            return "";
        }
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    static boolean preparedSuccessfully(PreparedShaderSource source) {
        if (source.source() == null) {
            return false;
        }
        return source.deviations().stream().noneMatch(value ->
                ((value.startsWith("SOURCE_") || value.startsWith("PREPROCESSOR_"))
                        && !ShaderSourcePreprocessor.isWarning(value))
                        || value.startsWith("LEGACY_FOG_FIELD_UNSUPPORTED:"));
    }
}
