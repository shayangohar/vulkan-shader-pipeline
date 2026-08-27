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
 * by pairing top-level *.fsh with same-basename *.vsh files. Never throws:
 * any failure drops the affected program and is logged as a warning, so a bad
 * pack can never break the frame.
 */
public final class PackSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");

    private PackSource() {}

    public static List<PackProgram> load(Path packDir) {
        Path shadersDir = packDir.resolve("shaders");
        List<PackProgram> programs = new ArrayList<>();
        if (!Files.isDirectory(shadersDir)) {
            LOGGER.warn("[chimera] pack: no 'shaders' directory under {}", packDir);
            return programs;
        }

        Path passList = shadersDir.resolve("shaders.json");
        if (Files.isRegularFile(passList)) {
            loadFromPassList(passList, shadersDir, programs);
        } else {
            scanPairs(shadersDir, programs);
        }
        return programs;
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
            String vertexRel = stringField(program, "vertex");
            Path fragmentPath = shadersDir.resolve(fragmentRel);
            String fragment = readSource(fragmentPath);
            if (fragment == null) {
                continue;
            }
            String vertex = vertexRel != null ? readSource(shadersDir.resolve(vertexRel)) : null;
            out.add(new PackProgram(name, vertex, fragment, fragmentPath));
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
            Path vertex = fragment.resolveSibling(base + ".vsh");
            String vertexSrc = Files.isRegularFile(vertex) ? readSource(vertex) : null;
            out.add(new PackProgram(base, vertexSrc, fragmentSrc, fragment));
        }
    }

    private static String readSource(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[chimera] pack: cannot read {}: {}", path, e.getMessage());
            return null;
        }
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