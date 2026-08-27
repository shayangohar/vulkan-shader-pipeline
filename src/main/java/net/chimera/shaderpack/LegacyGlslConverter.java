package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a legacy OptiFine-style fragment shader (#version 120/120e,
 * varying, gl_FragColor, texture2D, OptiFine texture-name uniforms) into
 * modern Vulkan GLSL: #version 460, explicit varying locations, samplers
 * declared on layout(binding = N) matching the generated pipeline config,
 * #include inlined relative to the including file. Deterministic, no external
 * state; returns null (never throws) so the caller falls back to the identity
 * pipeline.
 */
public final class LegacyGlslConverter {
    private static final int MAX_INCLUDE_DEPTH = 8;

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\S+.*$");
    private static final Pattern VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    private static final Pattern INCLUDE_LINE = Pattern.compile("(?m)^\\s*#include\\s+[<\"]([^>\"]+)[>\"]\\s*$");

    private LegacyGlslConverter() {}

    public static String convertFragment(String source, Path sourceFile) {
        try {
            String src = source;
            boolean modern = src.contains("#version 460") || src.contains("#version 450");
            src = inlineIncludes(src, sourceFile, 0);

            if (!modern) {
                src = VERSION_LINE.matcher(src).replaceFirst("");
            }

            src = convertVaryings(src);
            src = convertTextureCalls(src);

            // Samplers in ascending slot order -> bindings 0,1,... in config order.
            List<String> samplers = UniformRegistry.scanSamplerNames(src);
            for (int i = 0; i < samplers.size(); i++) {
                String name = samplers.get(i);
                src = src.replaceFirst(
                        "uniform\\s+sampler2D\\s+" + name + "\\s*;",
                        "layout(binding = " + i + ") uniform sampler2D " + name + ";");
            }

            if (src.contains("gl_FragColor")) {
                src = src.replaceAll("\\bgl_FragColor\\b", "fragColor");
                src = src + "\nlayout(location = 0) out vec4 fragColor;\n";
            }

            if (!modern) {
                src = "#version 460\n" + src;
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    private static String inlineIncludes(String src, Path sourceFile, int depth) throws IOException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IOException("include depth exceeds " + MAX_INCLUDE_DEPTH);
        }
        Matcher matcher = INCLUDE_LINE.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(src, last, matcher.start());
            Path include = resolveInclude(sourceFile, matcher.group(1));
            String included = Files.readString(include, StandardCharsets.UTF_8);
            out.append(inlineIncludes(included, include, depth + 1));
            last = matcher.end();
        }
        out.append(src, last, src.length());
        return out.toString();
    }

    private static Path resolveInclude(Path sourceFile, String relative) throws IOException {
        Path base = sourceFile != null && sourceFile.getParent() != null
                ? sourceFile.getParent()
                : Path.of(".");
        Path include = base.resolve(relative).normalize();
        if (!Files.isRegularFile(include)) {
            throw new IOException("missing include: " + include);
        }
        return include;
    }

    private static String convertVaryings(String src) {
        Matcher matcher = VARYING_DECL.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            out.append(src, last, matcher.start());
            out.append("layout(location = 0) in ").append(matcher.group(1)).append(' ').append(matcher.group(2)).append(';');
            last = matcher.end();
        }
        out.append(src, last, src.length());
        return out.toString();
    }

    private static String convertTextureCalls(String src) {
        String converted = src;
        // Order matters: the Lod variants first, so plain texture2D/texture3D
        // rewrites never eat their suffixes.
        converted = converted.replace("texture2DLod(", "textureLod(");
        converted = converted.replace("texture2D(", "texture(");
        converted = converted.replace("texture3DLod(", "textureLod(");
        converted = converted.replace("texture3D(", "texture(");
        return converted;
    }
}