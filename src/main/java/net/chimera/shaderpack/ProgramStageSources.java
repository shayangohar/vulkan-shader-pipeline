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

    /** The stages of a program planned under {@code name}; post and terrain-material stages normalized. */
    static Map<String, PreparedShaderSource> prepare(PackProgram program, String name, boolean terrainMaterial) {
        Map<String, PreparedShaderSource> prepared =
                withoutInactiveImages(flattenInterfaceBlocks(preparedStages(program)));
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

    /**
     * The vertex and fragment stages with their interface blocks as plain varyings
     * (InterfaceBlockFlattener). A program with another stage keeps its blocks: a geometry
     * stage's arrayed input block cannot be flattened to match.
     */
    private static Map<String, PreparedShaderSource> flattenInterfaceBlocks(Map<String, PreparedShaderSource> stages) {
        if (!Set.of("vertex", "fragment").containsAll(stages.keySet())) return stages;
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            InterfaceBlockFlattener.Result flattened = InterfaceBlockFlattener.flatten(prepared.source());
            if (flattened.blocks().isEmpty()) continue;
            List<String> deviations = new ArrayList<>(prepared.deviations());
            flattened.blocks().forEach(block -> deviations.add("INTERFACE_BLOCK_FLATTENED:" + block));
            result.put(entry.getKey(), new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    flattened.source(), prepared.dependencies(), deviations));
        }
        return result;
    }

    /**
     * Each stage without the image declarations nothing in it reads. GL leaves such a uniform
     * inactive, and Vulkan GLSL would need a binding for it: Reverie's programs all declare its
     * screen-sized images (image0-2, water_depth_min, atm_image) and most read none of them. Line
     * breaks stay, so line numbers do not move.
     */
    private static Map<String, PreparedShaderSource> withoutInactiveImages(Map<String, PreparedShaderSource> stages) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            String source = prepared.source();
            if (source == null || !source.contains("image")) continue;
            Matcher declaration = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(source);
            StringBuilder active = new StringBuilder();
            while (declaration.find()) {
                String kept = GlslTokenRewriter.identifierCount(source, declaration.group(2)) > 1
                        ? declaration.group() : declaration.group().replaceAll("[^\\r\\n]", "");
                declaration.appendReplacement(active, Matcher.quoteReplacement(kept));
            }
            String rewritten = declaration.appendTail(active).toString();
            if (rewritten.equals(source)) continue;
            result.put(entry.getKey(), new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    rewritten, prepared.dependencies(), prepared.deviations()));
        }
        return result;
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
                normalized = TerrainVertexBridge.normalizeModernTerrain(prepared.source());
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
