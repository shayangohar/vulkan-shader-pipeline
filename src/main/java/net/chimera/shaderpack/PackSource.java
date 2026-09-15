package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovers programs from an OptiFine or Iris shader pack. Directories and
 * ZIP archives are normalized to one extracted shader tree. Standard
 * dimension variants are selected without adding pack-specific branches.
 */
public final class PackSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    private static final List<String> DIMENSION_FOLDERS = List.of("world0", "world-1", "world1");
    private static final Pattern PROPERTY_DIRECTIVE = Pattern.compile(
            "^\\s*#\\s*(if|ifdef|ifndef|elif|else|endif|define)\\b(.*)$");
    private static final Pattern PROPERTY_DEFINE = Pattern.compile(
            "^([A-Za-z_]\\w*)(?:\\s+(.*))?$");

    /** The selected programs, shader root, archive owner, and load metadata. */
    public static final class LoadResult implements AutoCloseable {
        private final Path shadersDir;
        private final Path temporaryRoot;
        private final Map<String, List<PackProgram>> rawVariants;
        private final Map<String, String> explicitDimensionFolders;
        private final boolean hasDimensionProperties;
        private final List<String> baseDeviations;
        private Map<String, List<PackProgram>> variants;
        private List<PackProgram> programs;
        private List<String> deviations;
        private String selectedDimension = "minecraft:overworld";
        private String selectedVariantFolder = "";
        private boolean closed;

        private LoadResult(
                Path shadersDir,
                Path temporaryRoot,
                Map<String, List<PackProgram>> variants,
                Map<String, String> explicitDimensionFolders,
                boolean hasDimensionProperties,
                List<String> deviations,
                String initialDimension
        ) {
            this.shadersDir = shadersDir;
            this.temporaryRoot = temporaryRoot;
            Map<String, List<PackProgram>> copy = new TreeMap<>();
            variants.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            this.rawVariants = Map.copyOf(copy);
            this.explicitDimensionFolders = Map.copyOf(explicitDimensionFolders);
            this.hasDimensionProperties = hasDimensionProperties;
            this.baseDeviations = List.copyOf(new TreeSet<>(deviations));
            this.variants = Map.of();
            this.programs = List.of();
            this.deviations = this.baseDeviations;
            prepare(PackEngineDefines.forPack(Map.of()),
                    PackEngineDefines.lockedNames(Set.of(), false, false));
            selectDimension(initialDimension);
        }

        public List<PackProgram> programs() {
            return programs;
        }

        /** Raw selected sources used to build the settings plan before preparation. */
        public List<PackProgram> rawPrograms() {
            return rawVariants.getOrDefault(selectedVariantFolder, List.of());
        }

        /** Raw executable entry files from every discovered dimension variant. */
        public List<PackProgram> rawProgramsAllVariants() {
            return rawVariants.values().stream()
                    .flatMap(List::stream)
                    .sorted(Comparator.comparing(PackProgram::name)
                            .thenComparing(value -> relativePath(shadersDir, value.fragmentPath())))
                    .toList();
        }

        /** Re-prepares every variant with the active load-time option defaults. */
        public void prepare(Map<String, String> initialMacros) {
            prepare(initialMacros, Set.of());
        }

        /** Re-prepares variants while preserving explicit option overrides. */
        public void prepare(Map<String, String> initialMacros, Set<String> lockedMacros) {
            Map<String, List<PackProgram>> prepared = new TreeMap<>();
            rawVariants.forEach((folder, values) -> prepared.put(folder, preparePrograms(
                    values, shadersDir, folder, variantMacros(folder, initialMacros), lockedMacros)));
            this.variants = Map.copyOf(prepared);
            selectDimension(this.selectedDimension);
        }

        private static Map<String, String> variantMacros(
                String folder, Map<String, String> initialMacros) {
            Map<String, String> result = new TreeMap<>();
            if (initialMacros != null) {
                result.putAll(initialMacros);
            }
            if (folder.equals("world0")) {
                result.put("OVERWORLD", "1");
            } else if (folder.equals("world-1")) {
                result.put("NETHER", "1");
            } else if (folder.equals("world1")) {
                result.put("END", "1");
            }
            return Map.copyOf(result);
        }

        public Path shadersDir() {
            return shadersDir;
        }

        public List<String> deviations() {
            return deviations;
        }

        /** The exact dimension requested for the selected source variant. */
        public String selectedDimension() {
            return selectedDimension;
        }

        /** The selected standard source folder, or empty for root-level packs. */
        public String selectedVariantFolder() {
            return selectedVariantFolder;
        }

        /** The directory from which selected stage files were discovered. */
        public Path selectedSourceDir() {
            return selectedVariantFolder.isBlank()
                    ? shadersDir
                    : shadersDir.resolve(selectedVariantFolder);
        }

        /** Returns the first standard noisetex image in deterministic order. */
        public Path findNoiseTexture() {
            for (String relative : List.of("tex/noise.png", "lib/textures/noise.png")) {
                Path candidate = shadersDir.resolve(relative).normalize();
                if (candidate.startsWith(shadersDir.normalize()) && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        /**
         * Selects another dimension without extracting the archive again.
         * This is called at a level boundary on the render thread.
         */
        public void selectDimension(String dimension) {
            String requested = dimension == null || dimension.isBlank()
                    ? "minecraft:overworld" : dimension;
            String folder = selectedFolder(requested);
            String chosen = folder;
            List<PackProgram> selected = variants.get(folder);
            List<String> nextDeviations = new ArrayList<>(baseDeviations);
            boolean hasNestedVariants = variants.keySet().stream()
                    .anyMatch(value -> !value.isBlank());

            if (hasNestedVariants && !hasDimensionProperties
                    && !isStandardDimension(requested)
                    && folder.equals("world0") && variants.containsKey("world0")) {
                nextDeviations.add("DIMENSION_SOURCE_FALLBACK_TO_WORLD0:" + requested);
            }

            if (selected == null && hasNestedVariants) {
                if (!hasDimensionProperties && !isStandardDimension(requested)
                        && variants.containsKey("world0")) {
                    chosen = "world0";
                    selected = variants.get("world0");
                    nextDeviations.add("DIMENSION_SOURCE_FALLBACK_TO_WORLD0:" + requested);
                } else {
                    nextDeviations.add("DIMENSION_SOURCE_UNAVAILABLE:" + requested);
                }
            }
            if (selected == null) {
                selected = variants.getOrDefault("", List.of());
                chosen = "";
            }
            if (hasNestedVariants && !chosen.isBlank()) {
                nextDeviations.add("DIMENSION_SOURCE_SELECTED:" + chosen);
            }
            this.selectedDimension = requested;
            this.selectedVariantFolder = chosen;
            this.programs = selected;
            this.deviations = List.copyOf(new TreeSet<>(nextDeviations));
        }

        private String selectedFolder(String dimension) {
            String normalized = normalizeDimension(dimension);
            if (hasDimensionProperties) {
                return explicitDimensionFolders.getOrDefault(normalized,
                        explicitDimensionFolders.getOrDefault("*", ""));
            }
            return dimensionFolder(normalized);
        }

        @Override
        public void close() {
            if (closed || temporaryRoot == null) {
                return;
            }
            closed = true;
            deleteTree(temporaryRoot);
        }
    }

    private PackSource() {}

    public static LoadResult loadResult(Path packPath) {
        return loadResult(packPath, "minecraft:overworld");
    }

    public static LoadResult loadResult(Path packPath, String dimension) {
        List<String> deviations = new ArrayList<>();
        if (packPath == null) {
            deviations.add("PACK_PATH_MISSING");
            return emptyResult(Path.of("shaders"), null, deviations, dimension);
        }
        if (Files.isDirectory(packPath)) {
            return discover(packPath.resolve("shaders"), null, deviations, dimension);
        }
        if (Files.isRegularFile(packPath)) {
            return loadArchive(packPath, deviations, dimension);
        }

        deviations.add("PACK_PATH_INVALID");
        LOGGER.warn("[chimera] pack path is not a directory or ZIP file: {}", packPath);
        return emptyResult(packPath.resolve("shaders"), null, deviations, dimension);
    }

    private static LoadResult loadArchive(Path archive, List<String> deviations, String dimension) {
        Path temporaryRoot = null;
        try {
            temporaryRoot = Files.createTempDirectory("chimera-pack-");
            List<String> names = new ArrayList<>();
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                TreeSet<String> seen = new TreeSet<>();
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = normalizeZipName(entry.getName());
                    if (unsafeZipName(name)) {
                        deviations.add("PACK_ARCHIVE_UNSAFE_PATH");
                        throw new IOException("unsafe ZIP entry path");
                    }
                    if (!seen.add(name)) {
                        deviations.add("PACK_ARCHIVE_DUPLICATE_ENTRY:" + name);
                        throw new IOException("duplicate ZIP entry");
                    }
                    names.add(name);
                }

                String prefix = findShadersPrefix(names);
                if (prefix == null) {
                    deviations.add("PACK_SHADERS_NOT_FOUND");
                    throw new IOException("ZIP contains no unambiguous shaders directory");
                }
                String shaderPrefix = prefix + "shaders/";
                Files.createDirectories(temporaryRoot.resolve("shaders"));
                for (String name : names) {
                    if (!name.startsWith(shaderPrefix) || name.endsWith("/")) {
                        continue;
                    }
                    String relative = name.substring(shaderPrefix.length());
                    if (relative.isBlank()) {
                        continue;
                    }
                    Path target = temporaryRoot.resolve("shaders").resolve(relative).normalize();
                    if (!target.startsWith(temporaryRoot)) {
                        deviations.add("PACK_ARCHIVE_UNSAFE_PATH");
                        throw new IOException("ZIP entry escaped extraction root");
                    }
                    Files.createDirectories(target.getParent());
                    ZipEntry entry = zip.getEntry(name);
                    if (entry == null) {
                        throw new IOException("ZIP entry disappeared during extraction");
                    }
                    try (var input = zip.getInputStream(entry)) {
                        Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return discover(temporaryRoot.resolve("shaders"), temporaryRoot, deviations, dimension);
        } catch (Exception e) {
            if (!deviations.contains("PACK_ARCHIVE_UNSAFE_PATH")
                    && !deviations.contains("PACK_SHADERS_NOT_FOUND")
                    && deviations.stream().noneMatch(value -> value.startsWith("PACK_ARCHIVE_DUPLICATE_ENTRY:"))) {
                deviations.add("PACK_ARCHIVE_INVALID");
            }
            LOGGER.warn("[chimera] pack archive cannot be loaded from {}: {}", archive, e.getMessage());
            Path failedShadersDir = temporaryRoot == null
                    ? archive.resolveSibling(archive.getFileName() + ".invalid-shaders")
                    : temporaryRoot.resolve("shaders");
            if (temporaryRoot != null) {
                deleteTree(temporaryRoot);
            }
            return emptyResult(failedShadersDir, null, deviations, dimension);
        }
    }

    private static LoadResult discover(
            Path shadersDir,
            Path temporaryRoot,
            List<String> deviations,
            String dimension
    ) {
        if (!Files.isDirectory(shadersDir)) {
            deviations.add("NO_SHADERS_DIRECTORY");
            LOGGER.warn("[chimera] pack: no 'shaders' directory under {}", shadersDir);
            return emptyResult(shadersDir, temporaryRoot, deviations, dimension);
        }

        Map<String, String> dimensionFolders = readDimensionFolders(shadersDir, deviations);
        Map<String, Path> variantDirs = variantDirectories(shadersDir, dimensionFolders);
        boolean dimensionProperties = Files.isRegularFile(shadersDir.resolve("dimension.properties"));
        Map<String, List<PackProgram>> variants = new TreeMap<>();
        for (Map.Entry<String, Path> variant : variantDirs.entrySet()) {
            Map<String, PackProgram> byName = new TreeMap<>();
            Path passList = shadersDir.resolve("shaders.json");
            if (Files.isRegularFile(passList)) {
                loadFromPassList(passList, variant.getValue(), byName, deviations);
            } else {
                scanPairs(variant.getValue(), byName, deviations);
            }
            loadStandardPair(variant.getValue(), byName, deviations, "shadow");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_water");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_entities");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_entities_translucent");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_entities_glowing");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_block");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_damagedblock");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_hand");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_hand_water");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_particles");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_particles_translucent");
            loadStandardPair(variant.getValue(), byName, deviations, "gbuffers_weather");
            loadStandardPostPrograms(variant.getValue(), byName, deviations);
            variants.put(variant.getKey(), List.copyOf(byName.values()));
        }
        return new LoadResult(shadersDir, temporaryRoot, variants, dimensionFolders,
                dimensionProperties, deviations, dimension);
    }

    private static LoadResult emptyResult(
            Path shadersDir,
            Path temporaryRoot,
            List<String> deviations,
            String dimension
    ) {
        return new LoadResult(shadersDir, temporaryRoot, Map.of("", List.of()), Map.of(),
                false, deviations, dimension);
    }

    private static Map<String, Path> variantDirectories(
            Path shadersDir,
            Map<String, String> dimensionFolders
    ) {
        Map<String, Path> result = new TreeMap<>();
        if (hasDirectStageFiles(shadersDir) || Files.isRegularFile(shadersDir.resolve("shaders.json"))) {
            result.put("", shadersDir);
        }
        for (String folder : DIMENSION_FOLDERS) {
            Path path = shadersDir.resolve(folder);
            if (Files.isDirectory(path)
                    && (hasDirectStageFiles(path)
                    || Files.isRegularFile(path.resolve("shaders.json")))) {
                result.put(folder, path);
            }
        }
        for (String folder : dimensionFolders.values()) {
            Path path = shadersDir.resolve(folder);
            if (Files.isDirectory(path)
                    && (hasDirectStageFiles(path)
                    || Files.isRegularFile(path.resolve("shaders.json")))) {
                result.put(folder, path);
            }
        }
        if (result.isEmpty()) {
            result.put("", shadersDir);
        }
        return result;
    }

    private static Map<String, String> readDimensionFolders(
            Path shadersDir,
            List<String> deviations
    ) {
        Path properties = shadersDir.resolve("dimension.properties");
        if (!Files.isRegularFile(properties)) {
            return Map.of();
        }
        Map<String, String> result = new TreeMap<>();
        PackConditionals.State conditions = new PackConditionals.State(Map.of());
        boolean invalidCondition = false;
        try {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                Matcher directive = PROPERTY_DIRECTIVE.matcher(line);
                if (directive.matches()) {
                    if (!invalidCondition) {
                        String name = directive.group(1).toLowerCase();
                        String argument = stripLineComment(directive.group(2)).trim();
                        try {
                            if (name.equals("define")) {
                                if (conditions.active()) {
                                    Matcher define = PROPERTY_DEFINE.matcher(argument);
                                    if (!define.matches()) {
                                        throw new IllegalArgumentException("invalid define");
                                    }
                                    conditions.define(define.group(1), define.group(2));
                                }
                            } else {
                                conditions.apply(name, argument);
                            }
                        } catch (RuntimeException failure) {
                            deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                            conditions = new PackConditionals.State(Map.of());
                            invalidCondition = true;
                        }
                    }
                    continue;
                }
                if (invalidCondition || !conditions.active()) {
                    continue;
                }
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int equals = value.indexOf('=');
                if (equals <= "dimension.".length()) {
                    continue;
                }
                String key = value.substring(0, equals).trim();
                if (!key.startsWith("dimension.")) {
                    continue;
                }
                String folder = key.substring("dimension.".length()).trim();
                if (!folder.matches("[A-Za-z0-9_-]+")) {
                    continue;
                }
                for (String world : value.substring(equals + 1).trim().split("\\s+")) {
                    if (!world.isBlank()) {
                        result.put(world.equals("*") ? "*" : normalizeDimension(world), folder);
                    }
                }
            }
            if (!invalidCondition) {
                try {
                    conditions.finish();
                } catch (RuntimeException failure) {
                    deviations.add("PREPROCESSOR_CONDITION_UNSUPPORTED");
                }
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return Map.copyOf(result);
    }

    private static String stripLineComment(String value) {
        if (value == null) {
            return "";
        }
        int comment = value.indexOf("//");
        return comment < 0 ? value : value.substring(0, comment);
    }

    private static boolean hasDirectStageFiles(Path directory) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && isStageFile(path)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    /** Standard post programs may be omitted from shaders.json. */
    private static void loadStandardPostPrograms(
            Path shadersDir,
            Map<String, PackProgram> out,
            List<String> deviations
    ) {
        List<Path> fragments = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shadersDir, "*.fsh")) {
            for (Path path : stream) {
                String name = fileNameWithoutExtension(path.getFileName().toString());
                if (PostTargetPlan.isPostProgramName(name)) {
                    fragments.add(path);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[chimera] pack: cannot list post programs in {}: {}", shadersDir, e.getMessage());
            return;
        }
        fragments.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path fragmentPath : fragments) {
            loadStandardPair(shadersDir, out, deviations,
                    fileNameWithoutExtension(fragmentPath.getFileName().toString()));
        }
    }

    /** Standard Iris families may be omitted from shaders.json. */
    private static void loadStandardPair(
            Path shadersDir,
            Map<String, PackProgram> out,
            List<String> deviations,
            String name
    ) {
        Path vertexPath = shadersDir.resolve(name + ".vsh");
        PackProgram existing = out.get(name);
        Path fragmentPath = shadersDir.resolve(name + ".fsh");
        if (existing != null && (!Files.isRegularFile(fragmentPath)
                || samePath(shadersDir, existing.fragmentPath(), fragmentPath))) {
            PackProgram merged = mergeVertex(existing, vertexPath);
            if (merged != existing) {
                out.put(name, merged);
            }
            return;
        }
        if (!Files.isRegularFile(fragmentPath)) {
            if (existing != null) {
                PackProgram merged = mergeVertex(existing, vertexPath);
                if (merged != existing) {
                    out.put(name, merged);
                }
            }
            return;
        }
        String fragment = readSource(fragmentPath);
        if (fragment != null) {
            addProgram(out, new PackProgram(name, fragment, fragmentPath,
                    readOptionalSource(vertexPath), vertexPath), shadersDir, deviations);
        }
    }

    private static PackProgram mergeVertex(PackProgram existing, Path vertexPath) {
        String vertex = readOptionalSource(vertexPath);
        if (vertex == null || existing.vertexSource() != null) {
            return existing;
        }
        return new PackProgram(existing.name(), existing.fragmentSource(),
                existing.fragmentPath(), vertex, vertexPath);
    }

    private static List<PackProgram> preparePrograms(
            List<PackProgram> candidates,
            Path shadersRoot,
            String variantFolder,
            Map<String, String> initialMacros,
            Set<String> lockedMacros
    ) {
        List<PackProgram> result = new ArrayList<>();
        for (PackProgram candidate : candidates) {
            ShaderSourcePreprocessor.Result fragment = ShaderSourcePreprocessor.prepare(
                    shadersRoot, candidate.fragmentPath(), candidate.fragmentSource(), initialMacros,
                    lockedMacros);
            ShaderSourcePreprocessor.Result vertex = candidate.vertexSource() == null
                    ? new ShaderSourcePreprocessor.Result(null, List.of())
                    : ShaderSourcePreprocessor.prepare(
                    shadersRoot, candidate.vertexPath(), candidate.vertexSource(), initialMacros,
                    lockedMacros);
            List<String> prepDeviations = new ArrayList<>();
            prepDeviations.addAll(fragment.deviations());
            prepDeviations.addAll(vertex.deviations());
            Map<String, PreparedShaderSource> preparedSources = new TreeMap<>();
            if (candidate.fragmentSource() != null) {
                preparedSources.put("fragment", new PreparedShaderSource(
                        "fragment", relativePath(shadersRoot, candidate.fragmentPath()),
                        fragment.source(), fragment.dependencies(), fragment.deviations()));
            }
            if (candidate.vertexSource() != null) {
                preparedSources.put("vertex", new PreparedShaderSource(
                        "vertex", relativePath(shadersRoot, candidate.vertexPath()),
                        vertex.source(), vertex.dependencies(), vertex.deviations()));
            }
            result.add(new PackProgram(candidate.name(), candidate.fragmentSource(), candidate.fragmentPath(),
                    candidate.vertexSource(), candidate.vertexPath(), shadersRoot,
                    fragment.source(), vertex.source(), prepDeviations, variantFolder, preparedSources));
        }
        return List.copyOf(result);
    }

    private static void loadFromPassList(
            Path passList,
            Path shadersDir,
            Map<String, PackProgram> out,
            List<String> deviations
    ) {
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(passList, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            deviations.add("SHADERS_JSON_INVALID");
            LOGGER.warn("[chimera] pack: cannot parse {}: {}", passList, e.getMessage());
            scanPairs(shadersDir, out, deviations);
            return;
        }

        JsonElement programsEl = json.get("programs");
        if (programsEl == null || !programsEl.isJsonArray()) {
            deviations.add("SHADERS_JSON_MISSING_PROGRAMS");
            LOGGER.warn("[chimera] pack: {} has no \"programs\" array; falling back to file scan", passList);
            scanPairs(shadersDir, out, deviations);
            return;
        }

        JsonArray programs = programsEl.getAsJsonArray();
        for (JsonElement element : programs) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject program = element.getAsJsonObject();
            String name = stringField(program, "name");
            if (name == null || name.isBlank()) {
                continue;
            }
            String fragmentRel = stringField(program, "fragment");
            if (fragmentRel == null) {
                LOGGER.warn("[chimera] pack: program '{}' has no fragment shader; dropping", name);
                continue;
            }
            Path fragmentPath = resolveStagePath(shadersDir, fragmentRel, ".fsh");
            if (fragmentPath == null) {
                deviations.add("PACK_SOURCE_PATH_UNSAFE");
                continue;
            }
            if (!Files.isRegularFile(fragmentPath)) {
                continue;
            }
            String fragment = readSource(fragmentPath);
            if (fragment == null) {
                continue;
            }
            String vertexRel = stringField(program, "vertex");
            Path vertexPath = resolveStagePath(shadersDir, vertexRel, ".vsh");
            if (vertexRel != null && !vertexRel.isBlank() && vertexPath == null) {
                deviations.add("PACK_SOURCE_PATH_UNSAFE");
                vertexPath = null;
            }
            addProgram(out, new PackProgram(name, fragment, fragmentPath,
                    readOptionalSource(vertexPath), vertexPath), shadersDir, deviations);
        }
    }

    /** Packs without shaders.json: every direct *.fsh is a program. */
    private static void scanPairs(
            Path shadersDir,
            Map<String, PackProgram> out,
            List<String> deviations
    ) {
        List<Path> fragments = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(shadersDir, "*.fsh")) {
            for (Path path : stream) {
                fragments.add(path);
            }
        } catch (IOException e) {
            LOGGER.warn("[chimera] pack: cannot list {}: {}", shadersDir, e.getMessage());
            return;
        }
        fragments.sort(Comparator.comparing(p -> p.getFileName().toString()));

        for (Path fragment : fragments) {
            String base = fileNameWithoutExtension(fragment.getFileName().toString());
            String fragmentSrc = readSource(fragment);
            if (fragmentSrc == null) {
                continue;
            }
            Path vertex = shadersDir.resolve(base + ".vsh");
            addProgram(out, new PackProgram(base, fragmentSrc, fragment,
                    readOptionalSource(vertex), vertex), shadersDir, deviations);
        }
    }

    private static void addProgram(
            Map<String, PackProgram> out,
            PackProgram candidate,
            Path shadersDir,
            List<String> deviations
    ) {
        PackProgram existing = out.get(candidate.name());
        if (existing == null) {
            out.put(candidate.name(), candidate);
            return;
        }
        deviations.add("DUPLICATE_PROGRAM:" + candidate.name());
        if (compareProgram(candidate, existing, shadersDir) < 0) {
            out.put(candidate.name(), candidate);
        }
    }

    private static int compareProgram(PackProgram left, PackProgram right, Path shadersDir) {
        int fragment = relativePath(shadersDir, left.fragmentPath())
                .compareTo(relativePath(shadersDir, right.fragmentPath()));
        if (fragment != 0) {
            return fragment;
        }
        return relativePath(shadersDir, left.vertexPath())
                .compareTo(relativePath(shadersDir, right.vertexPath()));
    }

    private static String readSource(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[chimera] pack: cannot read {}: {}", path, e.getMessage());
            return null;
        }
    }

    private static String readOptionalSource(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        return readSource(path);
    }

    private static Path resolveStagePath(Path shadersDir, String relative, String extension) {
        if (relative == null || relative.isBlank()) {
            return null;
        }
        String safeRelative = relative.startsWith("/") ? relative.substring(1) : relative;
        Path path = shadersDir.resolve(safeRelative);
        String fileName = path.getFileName().toString();
        if (!fileName.contains(".")) {
            path = path.resolveSibling(fileName + extension);
        }
        path = path.normalize();
        return path.startsWith(shadersDir.normalize()) ? path : null;
    }

    private static String stringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String fileNameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean samePath(Path root, Path left, Path right) {
        return left != null && right != null
                && relativePath(root, left).equals(relativePath(root, right));
    }

    private static String relativePath(Path root, Path file) {
        if (file == null) {
            return "~";
        }
        if (root == null) {
            return file.getFileName() == null ? "" : file.getFileName().toString().replace('\\', '/');
        }
        return root.relativize(file.normalize()).toString().replace('\\', '/');
    }

    private static String normalizeZipName(String name) {
        return name.replace('\\', '/');
    }

    private static boolean unsafeZipName(String name) {
        if (name.isBlank() || name.startsWith("/")
                || (name.length() >= 3 && Character.isLetter(name.charAt(0))
                && name.charAt(1) == ':' && name.charAt(2) == '/')) {
            return true;
        }
        for (String component : name.split("/", -1)) {
            if (component.equals("..") || component.indexOf('\0') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String findShadersPrefix(List<String> names) {
        TreeSet<String> candidates = new TreeSet<>();
        for (String name : names) {
            if (name.equals("shaders") || name.startsWith("shaders/")) {
                candidates.add("");
            }
            int slash = name.indexOf('/');
            if (slash > 0) {
                String top = name.substring(0, slash);
                String nested = top + "/shaders";
                if (name.equals(nested) || name.startsWith(nested + "/")) {
                    candidates.add(top + "/");
                }
            }
        }
        return candidates.size() == 1 ? candidates.first() : null;
    }

    private static boolean isStageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && switch (name.substring(dot)) {
            case ".vsh", ".fsh", ".gsh", ".tcs", ".tes", ".csh" -> true;
            default -> false;
        };
    }

    private static String dimensionFolder(String dimension) {
        return switch (dimension) {
            case "minecraft:the_nether", "the_nether" -> "world-1";
            case "minecraft:the_end", "the_end" -> "world1";
            case "minecraft:overworld", "overworld" -> "world0";
            default -> "world0";
        };
    }

    private static String normalizeDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "minecraft:overworld";
        }
        return dimension.indexOf(':') < 0 ? "minecraft:" + dimension : dimension;
    }

    private static boolean isStandardDimension(String dimension) {
        return dimension.equals("minecraft:overworld") || dimension.equals("overworld")
                || dimension.equals("minecraft:the_nether") || dimension.equals("the_nether")
                || dimension.equals("minecraft:the_end") || dimension.equals("the_end");
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOGGER.warn("[chimera] pack: cannot remove temporary path {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[chimera] pack: cannot clean temporary root {}: {}", root, e.getMessage());
        }
    }
}
