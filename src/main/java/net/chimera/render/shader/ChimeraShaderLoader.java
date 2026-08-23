package net.chimera.render.shader;

import com.google.gson.JsonObject;
import net.chimera.ChimeraMod;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads chimera's own shader assets (GLSL sources and pipeline JSON configs)
 * from /assets/chimera/shaders/. Mirrors the access pattern of the host's
 * ShaderLoadUtil but scoped to our namespace so we never touch its files.
 */
public abstract class ChimeraShaderLoader {
    public static final String SHADERS_ROOT = "/assets/chimera/shaders/";

    private ChimeraShaderLoader() {}

    public static JsonObject loadJson(String relativePath) {
        try (InputStream stream = open(relativePath)) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load chimera shader config: " + relativePath, e);
        }
    }

    public static String loadSource(String relativePath) {
        try (InputStream stream = open(relativePath)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load chimera shader source: " + relativePath, e);
        }
    }

    private static InputStream open(String relativePath) {
        InputStream stream = ChimeraShaderLoader.class.getResourceAsStream(SHADERS_ROOT + relativePath);
        if (stream == null) {
            throw new IllegalStateException("Missing chimera shader resource: " + SHADERS_ROOT + relativePath);
        }
        return stream;
    }
}
