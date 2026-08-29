package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only inventory of the part of an OptiFine/Iris pack that Chimera can
 * currently see. It never compiles or changes source files.
 */
public final class PackProbe {
    private static final Set<String> STAGE_EXTENSIONS = Set.of(
            ".vsh", ".fsh", ".gsh", ".tcs", ".tes", ".csh");
    private static final Pattern SAMPLER_DECL = Pattern.compile(
            "\\buniform\\s+(?:sampler\\w*|isampler\\w*|usampler\\w*)\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern UNIFORM_DECL = Pattern.compile(
            "\\buniform\\s+[A-Za-z_]\\w*\\s+([A-Za-z_]\\w*)\\s*;");
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^\\s*#version\\s+(\\d+)");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile(
            "#define\\s+DRAWBUFFERS(\\d+)");
    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?i)(?:DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,\\s]+)");
    private static final Pattern FRAG_DATA = Pattern.compile(
            "gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^\\s#]+)");
    private static final Pattern COLORTEX_FORMAT = Pattern.compile(
            "colortex\\d+Format");
    private static final Pattern SHADOW_SETTING = Pattern.compile(
            "shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov");

    private PackProbe() {}

    public static ConformanceReport probe(Path packDir) {
        String packName = packDir.getFileName() == null
                ? "pack"
                : packDir.getFileName().toString();
        Path shadersDir = packDir.resolve("shaders");
        Map<String, String> metadataHashes = new TreeMap<>();
        List<String> globalDeviations = new ArrayList<>();
        List<String> settings = new ArrayList<>();
        List<String> passInventory = new ArrayList<>();
        boolean passListPresent = false;

        Path passList = shadersDir.resolve("shaders.json");
        if (Files.isRegularFile(passList)) {
            passListPresent = true;
            readMetadata(passList, "shaders.json", metadataHashes, globalDeviations);
            readPassList(passList, passInventory, globalDeviations);
        }

        Path properties = shadersDir.resolve("shaders.properties");
        if (Files.isRegularFile(properties)) {
            readMetadata(properties, "shaders.properties", metadataHashes, globalDeviations);
            readProperties(properties, settings, globalDeviations);
        }

        if (!Files.isDirectory(shadersDir)) {
            globalDeviations.add("NO_SHADERS_DIRECTORY");
            return new ConformanceReport(packName, passListPresent, passInventory,
                    metadataHashes, settings, globalDeviations);
        }

        Map<String, Inventory> inventories = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(shadersDir)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(PackProbe::isStageFile)
                    .sorted(Comparator.comparing(path -> relativePath(shadersDir, path)))
                    .toList();
            for (Path file : files) {
                String relative = relativePath(shadersDir, file);
                String fileName = file.getFileName().toString();
                String base = fileName.substring(0, fileName.lastIndexOf('.'));
                Inventory inventory = inventories.computeIfAbsent(base, ignored -> new Inventory(base));
                String source;
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(file);
                    source = new String(bytes, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    inventory.deviations.add("SOURCE_READ_FAILED");
                    continue;
                }
                String extension = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
                inventory.stages.add(stageName(extension));
                inventory.sourceHashes.put(relative, ConformanceReport.sha256(bytes));
                inventory.sources.put(stageName(extension), source);
                if (relative.indexOf('/') >= 0) {
                    inventory.deviations.add("NESTED_SOURCE_NOT_LOADED");
                }
            }
        } catch (IOException e) {
            globalDeviations.add("SOURCE_SCAN_FAILED");
        }

        if (!passListPresent) {
            passInventory.addAll(inventories.keySet());
        }
        for (String pass : passInventory) {
            inventories.computeIfAbsent(pass, Inventory::new);
        }

        Path blockProperties = shadersDir.resolve("block.properties");
        if (Files.isRegularFile(blockProperties)) {
            readMetadata(blockProperties, "block.properties", metadataHashes, globalDeviations);
            globalDeviations.addAll(PackMaterialResolver.parse(shadersDir).deviations());
        } else {
            Inventory terrain = inventories.get("gbuffers_terrain");
            if (terrain != null && terrain.stages.contains("vertex")) {
                globalDeviations.add("BLOCK_PROPERTIES_MISSING");
            }
        }

        ConformanceReport report = new ConformanceReport(packName, passListPresent,
                passInventory, metadataHashes, settings, globalDeviations);
        for (Inventory inventory : inventories.values()) {
            report.addProgram(toProgram(inventory));
        }
        return report;
    }

    private static ConformanceReport.ProgramReport toProgram(Inventory inventory) {
        StringBuilder combinedSource = new StringBuilder();
        inventory.sources.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> combinedSource.append(entry.getValue()).append('\n'));
        String source = combinedSource.toString();
        String fragment = inventory.sources.getOrDefault("fragment", source);
        String stripped = stripComments(source);
        boolean modern = usesModernGlsl(stripped);
        List<String> samplers = scanSamplers(stripped);
        List<String> uniforms = scanUniforms(stripped);
        List<Integer> targets = scanTargets(fragment);
        TreeSet<String> deviations = new TreeSet<>(inventory.deviations);

        String name = inventory.name;
        String family = familyOf(name);
        boolean executableName = name.equals("gbuffers_terrain")
                || name.equals("composite")
                || name.equals("final");
        boolean hasFragment = inventory.stages.contains("fragment");

        if (inventory.stages.contains("vertex") && name.equals("gbuffers_terrain")) {
            String vertex = inventory.sources.get("vertex");
            if (LegacyGlslConverter.supportsTerrainVertex(vertex, fragment)) {
                deviations.add("LEGACY_TERRAIN_VERTEX_BRIDGE");
            } else {
                deviations.add("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED");
            }
        } else if (inventory.stages.contains("vertex")) {
            deviations.add("FIXED_VERTEX_SUBSTITUTION");
        }
        if (inventory.stages.stream().anyMatch(stage ->
                stage.equals("geometry")
                        || stage.equals("tess_control")
                        || stage.equals("tess_evaluation")
                        || stage.equals("compute"))) {
            deviations.add("UNSUPPORTED_PACK_STAGE");
        }
        if (modern) {
            deviations.add("MODERN_GLSL_UNSUPPORTED");
        }
        if (stripped.matches("(?s).*\\b(?:image\\w*|buffer)\\b.*")) {
            deviations.add("ADVANCED_RESOURCE_UNSUPPORTED");
        }

        Map<String, Integer> knownSamplers = name.equals("gbuffers_terrain")
                ? UniformRegistry.GEOMETRY_NAME_TO_SLOT
                : UniformRegistry.NAME_TO_SLOT;
        for (String sampler : samplers) {
            if (!knownSamplers.containsKey(sampler)) {
                deviations.add("SAMPLER_NOT_MAPPED:" + sampler);
            }
        }
        if (uniforms.stream().anyMatch(uniform -> !samplers.contains(uniform))) {
            deviations.add("UNIFORM_NOT_DYNAMIC");
        }
        if (targets.size() > 1) {
            deviations.add("MRT_NOT_SUPPORTED");
        } else if (!targets.isEmpty() && targets.get(0) != 0) {
            deviations.add("TARGET_ROUTING_FIXED_TO_COLORTEX0");
        }
        if (!hasFragment) {
            deviations.add("MISSING_FRAGMENT_SOURCE");
        }

        ConformanceReport.SupportStatus support;
        if (!executableName) {
            support = ConformanceReport.SupportStatus.UNSUPPORTED;
        } else if (!hasFragment || hasSevereDeviation(deviations)) {
            support = ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        } else if (!deviations.isEmpty()) {
            support = ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
        } else {
            support = ConformanceReport.SupportStatus.SUPPORTED;
        }

        return new ConformanceReport.ProgramReport(
                name,
                family,
                modern ? "MODERN_GLSL" : "LEGACY_GLSL",
                sorted(inventory.stages),
                inventory.sourceHashes,
                samplers,
                uniforms,
                targets,
                support,
                ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                List.copyOf(deviations)
        );
    }

    private static boolean hasSevereDeviation(Collection<String> deviations) {
        for (String deviation : deviations) {
            if (deviation.equals("UNSUPPORTED_PACK_STAGE")
                    || deviation.equals("MODERN_GLSL_UNSUPPORTED")
                    || deviation.equals("ADVANCED_RESOURCE_UNSUPPORTED")
                    || deviation.equals("MRT_NOT_SUPPORTED")
                    || deviation.equals("TARGET_ROUTING_FIXED_TO_COLORTEX0")
                    || deviation.equals("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("MISSING_FRAGMENT_SOURCE")
                    || deviation.equals("NESTED_SOURCE_NOT_LOADED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean usesModernGlsl(String source) {
        Matcher version = VERSION.matcher(source);
        if (version.find()) {
            try {
                if (Integer.parseInt(version.group(1)) >= 330) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        return source.matches("(?s).*\\b(?:layout|in|out|flat|noperspective)\\b.*");
    }

    private static List<String> scanSamplers(String source) {
        TreeSet<String> result = new TreeSet<>();
        Matcher matcher = SAMPLER_DECL.matcher(source);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return List.copyOf(result);
    }

    private static List<String> scanUniforms(String source) {
        TreeSet<String> result = new TreeSet<>();
        Matcher matcher = UNIFORM_DECL.matcher(source);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return List.copyOf(result);
    }

    private static List<Integer> scanTargets(String source) {
        TreeSet<Integer> result = new TreeSet<>();
        Matcher define = DRAWBUFFERS_DEFINE.matcher(source);
        if (define.find()) {
            addSequentialTargets(result, define.group(1));
        }
        Matcher comment = TARGET_COMMENT.matcher(source);
        while (comment.find()) {
            for (String token : comment.group(1).split(",")) {
                addTarget(result, token.trim());
            }
        }
        Matcher fragData = FRAG_DATA.matcher(source);
        while (fragData.find()) {
            addTarget(result, fragData.group(1));
        }
        if (source.contains("gl_FragColor")) {
            result.add(0);
        }
        return List.copyOf(result);
    }

    private static void addSequentialTargets(Set<Integer> result, String countText) {
        try {
            int count = Integer.parseInt(countText);
            for (int index = 0; index < count; index++) {
                result.add(index);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static void addTarget(Set<Integer> result, String targetText) {
        try {
            result.add(Integer.parseInt(targetText));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void readPassList(Path passList, List<String> passes, List<String> deviations) {
        try {
            JsonObject object = JsonParser.parseString(
                    Files.readString(passList, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement programs = object.get("programs");
            if (programs == null || !programs.isJsonArray()) {
                deviations.add("SHADERS_JSON_MISSING_PROGRAMS");
                return;
            }
            for (JsonElement element : programs.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonElement name = element.getAsJsonObject().get("name");
                if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()) {
                    passes.add(name.getAsString());
                }
            }
        } catch (Exception e) {
            deviations.add("SHADERS_JSON_INVALID");
        }
    }

    private static void readProperties(
            Path properties,
            List<String> settings,
            List<String> deviations
    ) {
        try {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                Matcher matcher = PROPERTY.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                String key = matcher.group(1);
                settings.add(key);
                boolean colortexFormat = COLORTEX_FORMAT.matcher(key).matches();
                boolean shadowSetting = SHADOW_SETTING.matcher(key).matches();
                if (shadowSetting) {
                    deviations.add("SHADOW_SETTING_LOGGED_ONLY:" + key);
                } else if (!colortexFormat) {
                    deviations.add("SETTING_NOT_APPLIED:" + key);
                }
            }
        } catch (IOException e) {
            deviations.add("SHADERS_PROPERTIES_READ_FAILED");
        }
    }

    private static void readMetadata(
            Path file,
            String name,
            Map<String, String> hashes,
            List<String> deviations
    ) {
        try {
            hashes.put(name, ConformanceReport.sha256(Files.readAllBytes(file)));
        } catch (IOException e) {
            deviations.add("METADATA_READ_FAILED:" + name);
        }
    }

    private static boolean isStageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && STAGE_EXTENSIONS.contains(name.substring(dot));
    }

    private static String stageName(String extension) {
        return switch (extension) {
            case ".vsh" -> "vertex";
            case ".fsh" -> "fragment";
            case ".gsh" -> "geometry";
            case ".tcs" -> "tess_control";
            case ".tes" -> "tess_evaluation";
            case ".csh" -> "compute";
            default -> throw new IllegalArgumentException("Unknown shader stage: " + extension);
        };
    }

    private static String familyOf(String name) {
        if (name.startsWith("gbuffers_")) {
            return "gbuffers";
        }
        if (name.startsWith("composite")) {
            return "composite";
        }
        if (name.equals("final")) {
            return "final";
        }
        if (name.startsWith("shadow")) {
            return "shadow";
        }
        if (name.startsWith("deferred")) {
            return "deferred";
        }
        if (name.startsWith("prepare")) {
            return "prepare";
        }
        if (name.startsWith("begin") || name.startsWith("end")) {
            return "setup";
        }
        return "other";
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static List<String> sorted(Collection<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static final class Inventory {
        private final String name;
        private final Set<String> stages = new TreeSet<>();
        private final Map<String, String> sourceHashes = new TreeMap<>();
        private final Map<String, String> sources = new HashMap<>();
        private final Set<String> deviations = new HashSet<>();

        private Inventory(String name) {
            this.name = name;
        }
    }
}
