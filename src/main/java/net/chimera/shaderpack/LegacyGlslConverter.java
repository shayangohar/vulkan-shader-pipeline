package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts a legacy OptiFine-style fragment shader (#version 120/120e,
 * varying, gl_FragColor/gl_FragData, texture2D, OptiFine texture-name
 * uniforms) into modern Vulkan GLSL: #version 460, explicit varying
 * locations, samplers declared on layout(binding = N) matching the generated
 * pipeline config, #include inlined relative to the including file.
 * Deterministic, no external state; returns null (never throws) so the caller
 * falls back to the identity pipeline.
 *
 * <p>Two stages:
 * <ul>
 * <li>POST (geometryStage=false): fullscreen seams; varying -> location 0,
 *     samplers at bindings 0,1,... (no UBOs precede them in post configs).
 * <li>GEOMETRY (geometryStage=true): the fixed chimera terrain vertex's
 *     outputs consumed by name (color->0, texcoord->1, lightSpacePos->5);
 *     samplers at bindings 3+i (three UBO blocks precede them in the terrain
 *     config); gl_FragData[N] single-target output (target 0 kept, higher
 *     targets stripped — M4 passes have one color attachment).
 * </ul>
 */
public final class LegacyGlslConverter {
    private static final int MAX_INCLUDE_DEPTH = 8;

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\S+.*$");
    private static final Pattern VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    private static final Pattern INCLUDE_LINE = Pattern.compile("(?m)^\\s*#include\\s+[<\"]([^>\"]+)[>\"]\\s*$");
    /** Pack const declarations consumed by PackConfig; stripped so the preprocessor-less GLSL compiles. */
    private static final Pattern CONSUMED_CONSTS =
            Pattern.compile("(?m)^\\s*const\\s+int\\s+(?:colortex\\d+Format|shadowMapResolution|shadowMapDistance|shadowMapSize|shadowMapFov)\\s*=\\s*\\w+\\s*;\\s*$");

    /** Geometry fragments receive the fixed chimera terrain vertex's outputs by name. */
    private static final Map<String, Integer> GEOMETRY_VARYING_LOCATIONS = Map.of(
            "color", 0,
            "texcoord", 1,
            "lightSpacePos", 5
    );
    /** Three UBO blocks precede samplers in the terrain pipeline config. */
    private static final int GEOMETRY_SAMPLER_BINDING_BASE = 3;

    private LegacyGlslConverter() {}

    public static String convertFragment(String source, Path sourceFile, boolean geometryStage) {
        try {
            String src = source;
            boolean modern = src.contains("#version 460") || src.contains("#version 450");
            src = inlineIncludes(src, sourceFile, 0);

            if (!modern) {
                src = VERSION_LINE.matcher(src).replaceFirst("");
            }

            src = CONSUMED_CONSTS.matcher(src).replaceAll("");
            src = convertVaryings(src, geometryStage);

            // Samplers in ascending slot order -> bindings base,base+1,... in config order.
            // The scan runs before the rename below so the declaration is still
            // recognizable by its OptiFine name.
            List<String> samplers = UniformRegistry.scanSamplerNames(src, stageOf(geometryStage));
            int bindingBase = geometryStage ? GEOMETRY_SAMPLER_BINDING_BASE : 0;
            if (geometryStage) {
                // GLSL 460: 'texture' is the sampling function name, so a pack
                // sampler declared as 'uniform sampler2D texture;' (the OptiFine
                // atlas convention) is renamed uniformly. Runs BEFORE
                // convertTextureCalls so the function-name rewrite below
                // creates texture(chimeraTexture, ...) instead of renaming
                // the function into a variable call.
                src = src.replaceAll("\\btexture\\b", "chimeraTexture");
            }
            src = convertTextureCalls(src);
            for (int i = 0; i < samplers.size(); i++) {
                String name = samplers.get(i);
                String srcName = geometryStage && name.equals("texture") ? "chimeraTexture" : name;
                src = src.replaceFirst(
                        "uniform\\s+sampler2D\\s+" + srcName + "\\s*;",
                        "layout(binding = " + (bindingBase + i) + ") uniform sampler2D " + srcName + ";");
            }

            String outDecl = null;
            if (src.contains("gl_FragColor") || src.contains("gl_FragData")) {
                src = src.replaceAll("\\bgl_FragColor\\b", "fragColor");
                src = src.replaceAll("gl_FragData\\s*\\[\\s*0\\s*\\]", "fragColor");
                // Targets beyond 0 are consumed by nothing in M4's single-attachment passes.
                src = src.replaceAll("gl_FragData\\s*\\[\\s*[1-9][0-9]*\\s*\\]\\s*=\\s*[^;]+;\\s*", "");
                outDecl = "layout(location = 0) out vec4 fragColor;";
            }

            // GLSL requires global declarations to precede their first use;
            // an output declared at the end of the file is a forward reference
            // and glslang rejects it ("'fragColor' : undeclared identifier").
            // Emit the declaration directly after the version line.
            if (!modern) {
                src = "#version 460\n" + (outDecl != null ? outDecl + "\n" : "") + src;
            } else if (outDecl != null) {
                src = insertAfterFirstLine(src, outDecl);
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    private static UniformRegistry.Stage stageOf(boolean geometryStage) {
        return geometryStage ? UniformRegistry.Stage.GEOMETRY : UniformRegistry.Stage.POST;
    }

    private static String insertAfterFirstLine(String src, String line) {
        int newline = src.indexOf('\n');
        if (newline < 0) {
            return line + "\n" + src;
        }
        return src.substring(0, newline + 1) + line + src.substring(newline + 1);
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

    private static String convertVaryings(String src, boolean geometryStage) {
        Matcher matcher = VARYING_DECL.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            int location;
            if (geometryStage) {
                Integer slot = GEOMETRY_VARYING_LOCATIONS.get(name);
                if (slot == null) {
                    throw new IllegalArgumentException(
                            "geometry varying '" + name + "' has no fixed-vertex slot (expected one of "
                                    + GEOMETRY_VARYING_LOCATIONS.keySet() + ")");
                }
                location = slot;
            } else {
                location = 0;
            }
            out.append(src, last, matcher.start());
            out.append("layout(location = ").append(location).append(") in ").append(type).append(' ').append(name).append(';');
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