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
import java.util.TreeMap;
import java.util.TreeSet;
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

    /** The selected programs, shader root, archive owner, and load metadata. */
    public static final class LoadResult implements AutoCloseable {
        private final Path shadersDir;
        private final Path temporaryRoot;
        private final Map<String, List<PackProgram>> variants;
        private final List<String> baseDeviations;
        private List<PackProgram> programs;
        private List<String> deviations;
        private String selectedDimension = "minecraft:overworld";
        private String selectedVariantFolder = "";
        private boolean closed;

        private LoadResult(
                Path shadersDir,
                Path temporaryRoot,
                Map<String, List<PackProgram>> variants,
                List<String> deviations
        ) {
            this.shadersDir = shadersDir;
            this.temporaryRoot = temporaryRoot;
            Map<String, List<PackProgram>> copy = new TreeMap<>();
            variants.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            this.variants = Map.copyOf(copy);
            this.baseDeviations = List.copyOf(new TreeSet<>(deviations));
            this.programs = List.of();
            this.deviations = this.baseDeviations;
            selectDimension("minecraft:overworld");
        }

        public List<PackProgram> programs() {
            return programs;
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
            String folder = dimensionFolder(requested);
            String chosen = folder;
            List<PackProgram> selected = variants.get(folder);
            List<String> nextDeviations = new ArrayList<>(baseDeviations);
            boolean hasNestedVariants = variants.keySet().stream()
                    .anyMatch(value -> !value.isBlank());

            if (selected == null && hasNestedVariants) {
                if (folder.equals("world0") && variants.containsKey("world0")) {
                    selected = variants.get("world0");
                } else if (variants.containsKey("world0") && !isStandardDimension(requested)) {
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
            if (hasNestedVariants && !isStandardDimension(requested)
                    && chosen.equals("world0") && selected != null) {
                nextDeviations.add("DIMENSION_SOURCE_FALLBACK_TO_WORLD0:" + requested);
            }
            if (hasNestedVariants && !chosen.isBlank()) {
                nextDeviations.add("DIMENSION_SOURCE_SELECTED:" + chosen);
            }
            this.selectedDimension = requested;
            this.selectedVariantFolder = chosen;
            this.programs = selected;
            this.deviations = List.copyOf(new TreeSet<>(nextDeviations));
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
            return emptyResult(Path.of("shaders"), null, deviations);
        }
        if (Files.isDirectory(packPath)) {
            return discover(packPath.resolve("shaders"), null, deviations, dimension);
        }
        if (Files.isRegularFile(packPath)) {
            return loadArchive(packPath, deviations, dimension);
        }

        deviations.add("PACK_PATH_INVALID");
        LOGGER.warn("[chimera] pack path is not a directory or ZIP file: {}", packPath);
        return emptyResult(packPath.resolve("shaders"), null, deviations);
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
            return emptyResult(failedShadersDir, null, deviations);
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
            return emptyResult(shadersDir, temporaryRoot, deviations);
        }

        Map<String, Path> variantDirs = variantDirectories(shadersDir);
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
            loadStandardPostPrograms(variant.getValue(), byName, deviations);
            variants.put(variant.getKey(), preparePrograms(byName, shadersDir, variant.getKey(), deviations));
        }
        return new LoadResult(shadersDir, temporaryRoot, variants, deviations);
    }

    private static LoadResult emptyResult(Path shadersDir, Path temporaryRoot, List<String> deviations) {
        return new LoadResult(shadersDir, temporaryRoot, Map.of("", List.of()), deviations);
    }

    private static Map<String, Path> variantDirectories(Path shadersDir) {
        Map<String, Path> result = new TreeMap<>();
        if (hasDirectStageFiles(shadersDir) || Files.isRegularFile(shadersDir.resolve("shaders.json"))) {
            result.put("", shadersDir);
        }
        for (String folder : DIMENSION_FOLDERS) {
            Path path = shadersDir.resolve(folder);
            if (Files.isDirectory(path) && hasDirectStageFiles(path)) {
                result.put(folder, path);
            }
        }
        if (result.isEmpty()) {
            result.put("", shadersDir);
        }
        return result;
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
            Map<String, PackProgram> candidates,
            Path shadersRoot,
            String variantFolder,
            List<String> deviations
    ) {
        List<PackProgram> result = new ArrayList<>();
        for (PackProgram candidate : candidates.values()) {
            ShaderSourcePreprocessor.Result fragment = ShaderSourcePreprocessor.prepare(
                    shadersRoot, candidate.fragmentPath(), candidate.fragmentSource());
            ShaderSourcePreprocessor.Result vertex = candidate.vertexSource() == null
                    ? new ShaderSourcePreprocessor.Result(null, List.of())
                    : ShaderSourcePreprocessor.prepare(
                    shadersRoot, candidate.vertexPath(), candidate.vertexSource());
            List<String> prepDeviations = new ArrayList<>();
            prepDeviations.addAll(fragment.deviations());
            prepDeviations.addAll(vertex.deviations());
            result.add(new PackProgram(candidate.name(), candidate.fragmentSource(), candidate.fragmentPath(),
                    candidate.vertexSource(), candidate.vertexPath(), shadersRoot,
                    fragment.source(), vertex.source(), prepDeviations, variantFolder));
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
        return file == null ? "~" : root.relativize(file.normalize()).toString().replace('\\', '/');
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
