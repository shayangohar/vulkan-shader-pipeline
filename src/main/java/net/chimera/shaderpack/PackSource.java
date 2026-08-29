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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Discovers the programs of an OptiFine/Iris-format shader pack on disk
 * (packDir/shaders/), either via its shaders.json pass list or, when absent,
 * every top-level *.fsh becomes a program. Fragment sources and optional
 * vertex sources are retained for the narrow M5.2 terrain bridge. Never throws: any failure drops the
 * affected program and is logged as a warning, so a bad pack can never break
 * the frame.
 */
public final class PackSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    /** The pack's programs plus its shaders/ directory (for properties). */
    public record LoadResult(List<PackProgram> programs, Path shadersDir) {}

    public static LoadResult loadResult(Path packDir) {
        Path shadersDir = packDir.resolve("shaders");
        List<PackProgram> programs = new ArrayList<>();
        if (!Files.isDirectory(shadersDir)) {
            LOGGER.warn("[chimera] pack: no 'shaders' directory under {}", packDir);
            return new LoadResult(programs, shadersDir);
        }

        Path passList = shadersDir.resolve("shaders.json");
        if (Files.isRegularFile(passList)) {
            loadFromPassList(passList, shadersDir, programs);
        } else {
            scanPairs(shadersDir, programs);
        }
        loadStandardPair(shadersDir, programs, "shadow");
        loadStandardPair(shadersDir, programs, "gbuffers_water");
        return new LoadResult(programs, shadersDir);
    }

    /** Standard Iris families may be omitted from shaders.json. */
    private static void loadStandardPair(Path shadersDir, List<PackProgram> out, String name) {
        Path vertexPath = shadersDir.resolve(name + ".vsh");
        for (int i = 0; i < out.size(); i++) {
            PackProgram existing = out.get(i);
            if (existing.name().equals(name)) {
                if (existing.vertexSource() == null && Files.isRegularFile(vertexPath)) {
                    out.set(i, new PackProgram(existing.name(), existing.fragmentSource(),
                            existing.fragmentPath(), readOptionalSource(vertexPath), vertexPath));
                }
                return;
            }
        }
        Path fragmentPath = shadersDir.resolve(name + ".fsh");
        if (!Files.isRegularFile(fragmentPath)) {
            return;
        }
        String fragment = readSource(fragmentPath);
        if (fragment != null) {
            out.add(new PackProgram(name, fragment, fragmentPath,
                    readOptionalSource(vertexPath), vertexPath));
        }
    }

    private static void loadFromPassList(Path passList, Path shadersDir, List<PackProgram> out) {
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(passList, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.warn("[chimera] pack: cannot parse {}: {}", passList, e.getMessage());
            return;
        }

        JsonElement programsEl = json.get("programs");
        if (programsEl == null || !programsEl.isJsonArray()) {
            LOGGER.warn("[chimera] pack: {} has no \"programs\" array; falling back to file scan", passList);
            scanPairs(shadersDir, out);
            return;
        }

        for (JsonElement element : programsEl.getAsJsonArray()) {
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
            String fragment = readSource(fragmentPath);
            if (fragment == null) {
                continue;
            }
            String vertexRel = stringField(program, "vertex");
            Path vertexPath = resolveStagePath(shadersDir, vertexRel, ".vsh");
            String vertex = readOptionalSource(vertexPath);
            out.add(new PackProgram(name, fragment, fragmentPath, vertex, vertexPath));
        }
    }

    /** Packs without shaders.json: every top-level *.fsh is a program. */
    private static void scanPairs(Path shadersDir, List<PackProgram> out) {
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
            out.add(new PackProgram(base, fragmentSrc, fragment,
                    readOptionalSource(vertex), vertex));
        }
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
        Path path = shadersDir.resolve(relative);
        String fileName = path.getFileName().toString();
        if (!fileName.contains(".")) {
            path = path.resolveSibling(fileName + extension);
        }
        return path;
    }

    private static String stringField(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static String fileNameWithoutExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
