package net.chimera.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;
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
 *     an optional generated pack UBO at binding 0, then samplers at binding
 *     1,2,... . No-uniform programs keep sampler binding 0,1,... .
 * <li>GEOMETRY (geometryStage=true): the fixed chimera terrain vertex's
 *     outputs consumed by name (color->0, texcoord->1, lightSpacePos->5);
 *     samplers at bindings 3+i (three UBO blocks precede them in the terrain
 *     config); gl_FragData[N] single-target output (target 0 kept, higher
 *     targets stripped - M4 passes have one color attachment).
 * </ul>
 */
public final class LegacyGlslConverter {
    private static final int MAX_INCLUDE_DEPTH = 8;

    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\S+.*$");
    private static final Pattern VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    private static final Pattern TERRAIN_VARYING_DECL =
            Pattern.compile("(?m)\\bvarying\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern TERRAIN_ATTRIBUTE_DECL =
            Pattern.compile("(?m)\\battribute\\s+([A-Za-z_]\\w*)\\s+(\\w+)\\s*;");
    private static final Pattern TERRAIN_VERSION =
            Pattern.compile("(?m)^\\s*#version\\s+120(?:e)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INCLUDE_LINE = Pattern.compile("(?m)^\\s*#include\\s+[<\"]([^>\"]+)[>\"]\\s*$");
    /** Pack const declarations consumed by PackConfig; stripped so the preprocessor-less GLSL compiles. */
    private static final Pattern CONSUMED_CONSTS =
            Pattern.compile("(?m)^\\s*const\\s+(?:int|float)\\s+(?:colortex\\d+Format|shadowMapResolution|shadowDistance|shadowMapDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul)\\s*=\\s*[A-Za-z0-9+_.-]+\\s*;\\s*$");

    /** Geometry fragments receive the fixed chimera terrain vertex's outputs by name. */
    private static final Map<String, Integer> GEOMETRY_VARYING_LOCATIONS = Map.of(
            "color", 0,
            "texcoord", 1,
            "lightSpacePos", 5
    );
    /** Three UBO blocks precede samplers in the terrain pipeline config. */
    private static final int GEOMETRY_SAMPLER_BINDING_BASE = 3;

    private LegacyGlslConverter() {}

    /** Deterministic interface shared by the narrow terrain vertex and fragment bridge. */
    public record TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations) {
        public TerrainVaryingLayout {
            types = Collections.unmodifiableMap(new TreeMap<>(types));
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
        }

        public int location(String name) {
            Integer location = locations.get(name);
            if (location == null) {
                throw new IllegalArgumentException("terrain varying is not produced by the vertex stage: " + name);
            }
            return location;
        }
    }

    /** Converted terrain vertex source plus the interface used by its fragment stage. */
    public record TerrainVertexConversion(String source, TerrainVaryingLayout layout) {}

    public static String convertFragment(String source, Path sourceFile, boolean geometryStage, int[] geometrySamplerSlots) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots, null,
                UniformRegistry.plan(source, stageOf(geometryStage)));
    }

    public static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout
    ) {
        return convertFragment(source, sourceFile, geometryStage, geometrySamplerSlots, terrainLayout,
                UniformRegistry.plan(source, stageOf(geometryStage)));
    }

    /** Converts a fragment with the interface plan already used by the caller. */
    public static String convertFragment(
            String source,
            Path sourceFile,
            boolean geometryStage,
            int[] geometrySamplerSlots,
            TerrainVaryingLayout terrainLayout,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        try {
            boolean geometryInterface = interfacePlan != null
                    && (interfacePlan.stage() == UniformRegistry.Stage.GEOMETRY
                    || interfacePlan.stage() == UniformRegistry.Stage.SHADOW);
            if (interfacePlan == null || !interfacePlan.executable()
                    || geometryInterface != geometryStage) {
                throw new IllegalArgumentException("pack interface is outside the executable contract");
            }
            String src = source;
            boolean modern = src.contains("#version 460") || src.contains("#version 450");
            src = inlineIncludes(src, sourceFile, 0);

            if (!modern) {
                src = VERSION_LINE.matcher(src).replaceFirst("");
            }

            src = CONSUMED_CONSTS.matcher(src).replaceAll("");
            src = UniformRegistry.removeUniformDeclarations(src, interfacePlan);
            src = convertVaryings(src, geometryStage, terrainLayout);

            // Samplers in ascending slot order -> bindings base,base+1,... in config order.
            // The interface plan is also the source of the generated config,
            // so conversion cannot drift from descriptor order.
            List<UniformRegistry.SamplerBinding> samplers = interfacePlan.samplers();
            int bindingBase = geometryStage
                    ? GEOMETRY_SAMPLER_BINDING_BASE
                    : (interfacePlan.executableUniforms().isEmpty() ? 0 : 1);
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
                String name = samplers.get(i).name();
                String srcName = geometryStage && name.equals("texture") ? "chimeraTexture" : name;
                int binding = geometryStage
                        ? bindingBase + configIndexOf(name, geometrySamplerSlots)
                        : bindingBase + i;
                if (binding < bindingBase) {
                    throw new IllegalArgumentException("sampler is missing from the generated config: " + name);
                }
                src = src.replaceFirst(
                        "uniform\\s+sampler2D\\s+" + srcName + "\\s*;",
                        "layout(binding = " + binding + ") uniform sampler2D " + srcName + ";");
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
            String uniformBlock = !geometryStage && !interfacePlan.executableUniforms().isEmpty()
                    ? generatedUniformBlock(interfacePlan.executableUniforms())
                    : "";
            String declarations = (outDecl != null ? outDecl + "\n" : "") + uniformBlock;
            if (!modern) {
                src = "#version 460\n" + declarations + src;
            } else if (!declarations.isEmpty()) {
                src = insertAfterFirstLine(src, declarations);
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Converts the strict M5.2 terrain vertex subset. A null result means that
     * the source must use the identity terrain pipeline.
     */
    public static TerrainVertexConversion convertTerrainVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, TERRAIN_VERTEX_PREAMBLE);
    }

    /** Converts the same strict bridge for the shadow pipeline's one-matrix UBO. */
    public static TerrainVertexConversion convertShadowVertex(
            String source,
            Path sourceFile,
            String fragmentSource
    ) {
        return convertLegacyVertex(source, sourceFile, fragmentSource, SHADOW_VERTEX_PREAMBLE);
    }

    private static TerrainVertexConversion convertLegacyVertex(
            String source,
            Path sourceFile,
            String fragmentSource,
            String vertexPreamble
    ) {
        try {
            String src = inlineIncludes(source, sourceFile, 0);
            String stripped = stripComments(src);
            if (!TERRAIN_VERSION.matcher(src).find()
                    || stripped.matches("(?s).*#version\\s+(?!120(?:e)?\\b)\\d+.*")) {
                throw new IllegalArgumentException("legacy vertex requires #version 120 or #version 120e");
            }
            if (!stripped.matches("(?s).*\\bvoid\\s+main\\s*\\(.*")
                    || !stripped.matches("(?s).*\\bgl_Position\\b.*")) {
                throw new IllegalArgumentException("legacy vertex requires main and gl_Position");
            }
            rejectTerrainVertexFeatures(stripped);

            Map<String, String> vertexTypes = parseTerrainVaryings(stripped);
            Map<String, String> fragmentTypes = parseTerrainVaryings(stripComments(fragmentSource));
            for (Map.Entry<String, String> entry : fragmentTypes.entrySet()) {
                String vertexType = vertexTypes.get(entry.getKey());
                if (!entry.getValue().equals(vertexType)) {
                    throw new IllegalArgumentException("legacy varying mismatch: " + entry.getKey());
                }
            }

            Map<String, Integer> locations = new TreeMap<>();
            int location = 0;
            for (String name : vertexTypes.keySet().stream().sorted().toList()) {
                locations.put(name, location++);
            }
            TerrainVaryingLayout layout = new TerrainVaryingLayout(vertexTypes, locations);

            String converted = VERSION_LINE.matcher(src).replaceFirst("");
            converted = converted.replaceAll(
                    "(?m)^\\s*attribute\\s+(?:float|vec2)\\s+mc_Entity\\s*;\\s*", "");
            converted = replaceTerrainVaryings(converted, layout, "out");
            converted = converted.replaceAll("\\bgl_Vertex\\b", "chimeraVertexValue()");
            converted = converted.replaceAll("\\bgl_Color\\b", "chimeraColorValue()");
            converted = converted.replaceAll("\\bgl_MultiTexCoord0\\b", "chimeraTexCoord0Value()");
            converted = converted.replaceAll("\\bgl_MultiTexCoord1\\b", "chimeraTexCoord1Value()");
            converted = converted.replaceAll("\\bftransform\\s*\\(\\s*\\)", "chimeraFtransform()");
            String entityType = terrainEntityType(stripped);
            if (entityType != null) {
                converted = converted.replaceAll("\\bmc_Entity\\b",
                        entityType.equals("vec2") ? "chimeraMcEntityValue()" : "chimeraMcEntityValue().x");
            }
            if (converted.matches("(?s).*\\bmc_Entity\\b.*")) {
                throw new IllegalArgumentException("mc_Entity is used without a supported declaration");
            }
            return new TerrainVertexConversion(
                    "#version 460\n" + vertexPreamble + converted,
                    layout);
        } catch (Exception e) {
            return null;
        }
    }

    /** Static probe helper used by PackProbe without compiling a shader. */
    public static boolean supportsTerrainVertex(String source, String fragmentSource) {
        return convertTerrainVertex(source, null, fragmentSource) != null;
    }

    /** Static probe helper for the strict shadow vertex bridge. */
    public static boolean supportsShadowVertex(String source, String fragmentSource) {
        return convertShadowVertex(source, null, fragmentSource) != null;
    }

    private static UniformRegistry.Stage stageOf(boolean geometryStage) {
        return geometryStage ? UniformRegistry.Stage.GEOMETRY : UniformRegistry.Stage.POST;
    }
    /** Position of the sampler's registry slot in the emitted geometry config array. */
    private static int configIndexOf(String name, int[] slots) {
        int slot = UniformRegistry.GEOMETRY_NAME_TO_SLOT.get(name);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    private static String insertAfterFirstLine(String src, String line) {
        int newline = src.indexOf('\n');
        if (newline < 0) {
            return line + "\n" + src;
        }
        return src.substring(0, newline + 1) + line + src.substring(newline + 1);
    }

    private static String generatedUniformBlock(List<UniformRegistry.UniformDeclaration> uniforms) {
        StringBuilder block = new StringBuilder("layout(binding = 0) uniform ChimeraPackUniforms {\n");
        for (UniformRegistry.UniformDeclaration uniform : uniforms) {
            block.append("    ").append(uniform.glslType()).append(' ')
                    .append(uniform.name()).append(";\n");
        }
        return block.append("};\n").toString();
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

    private static String convertVaryings(
            String src,
            boolean geometryStage,
            TerrainVaryingLayout terrainLayout
    ) {
        Matcher matcher = VARYING_DECL.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            int location;
            if (geometryStage && terrainLayout != null) {
                location = terrainLayout.location(name);
            } else if (geometryStage) {
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

    private static String replaceTerrainVaryings(
            String source,
            TerrainVaryingLayout layout,
            String direction
    ) {
        Matcher matcher = TERRAIN_VARYING_DECL.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!layout.types().containsKey(name)
                    || !layout.types().get(name).equals(type)
                    || !(type.equals("float") || type.equals("vec2")
                    || type.equals("vec3") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported terrain varying: " + name);
            }
            out.append(source, last, matcher.start());
            out.append("layout(location = ").append(layout.location(name)).append(") ")
                    .append(direction).append(' ').append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    private static Map<String, String> parseTerrainVaryings(String source) {
        Map<String, String> result = new TreeMap<>();
        Matcher matcher = TERRAIN_VARYING_DECL.matcher(source);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!(type.equals("float") || type.equals("vec2")
                    || type.equals("vec3") || type.equals("vec4"))) {
                throw new IllegalArgumentException("unsupported terrain varying type: " + type);
            }
            String previous = result.putIfAbsent(name, type);
            if (previous != null && !previous.equals(type)) {
                throw new IllegalArgumentException("terrain varying declared with two types: " + name);
            }
        }
        return result;
    }

    private static String terrainEntityType(String source) {
        Matcher matcher = TERRAIN_ATTRIBUTE_DECL.matcher(source);
        String entityType = null;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (!name.equals("mc_Entity")) {
                throw new IllegalArgumentException("unsupported terrain attribute: " + name);
            }
            if (!type.equals("float") && !type.equals("vec2")) {
                throw new IllegalArgumentException("unsupported mc_Entity type: " + type);
            }
            if (entityType != null && !entityType.equals(type)) {
                throw new IllegalArgumentException("mc_Entity declared with two types");
            }
            entityType = type;
        }
        return entityType;
    }

    private static void rejectTerrainVertexFeatures(String source) {
        if (source.matches("(?s).*\\b(?:uniform|gl_Normal|gl_NormalMatrix|gl_ModelViewMatrix|"
                + "gl_ProjectionMatrix|gl_ModelViewProjectionMatrix|gl_TextureMatrix|"
                + "mc_midTexCoord|at_tangent|tangent|image\\w*|buffer)\\b.*")) {
            throw new IllegalArgumentException("unsupported terrain vertex feature");
        }
        if (source.matches("(?s).*\\b(?:layout|in|out|flat|noperspective)\\b.*")) {
            throw new IllegalArgumentException("modern GLSL is not supported in terrain vertex");
        }
        Matcher attributes = TERRAIN_ATTRIBUTE_DECL.matcher(source);
        if (attributes.find()) {
            attributes.reset();
            while (attributes.find()) {
                if (!attributes.group(2).equals("mc_Entity")) {
                    throw new IllegalArgumentException("unsupported terrain attribute");
                }
            }
        }
        if (source.matches("(?s).*\\battribute\\b.*")
                && !source.matches("(?s).*\\battribute\\s+(?:float|vec2)\\s+mc_Entity\\s*;.*")) {
            throw new IllegalArgumentException("unsupported terrain attribute declaration");
        }
    }

    private static String stripComments(String source) {
        return source == null ? "" : source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }

    private static final String TERRAIN_VERTEX_PREAMBLE = """
            layout(binding = 0) uniform ViewUBO {
                mat4 MVP;
                mat4 LightMVP;
            };

            layout(binding = 2) uniform SectionData {
                ivec4 SectionOffsets[128];
                vec4 SectionFadeFactors[128];
            };

            layout(push_constant) uniform Push {
                vec3 ModelOffset;
            };

            layout(location = 0) in ivec4 inPositionLight;
            layout(location = 1) in uvec2 inUV;
            layout(location = 2) in uint inPackedColor;
            layout(location = 3) in int inMaterialId;
            layout(location = 4) in int inRenderType;

            const float CHIMERA_UV_SCALE = 1.0 / 32768.0;
            const vec3 CHIMERA_POSITION_SCALE = vec3(1.0 / 2048.0);
            const vec3 CHIMERA_POSITION_BIAS = vec3(4.0);

            vec3 chimeraSectionOffset(int encoded) {
                return vec3(
                    float(bitfieldExtract(encoded, 0, 8)),
                    float(bitfieldExtract(encoded, 16, 8)),
                    float(bitfieldExtract(encoded, 8, 8)));
            }

            vec3 chimeraWorldPosition() {
                int encoded = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
                return fma(vec3(inPositionLight.xyz), CHIMERA_POSITION_SCALE,
                        ModelOffset + chimeraSectionOffset(encoded));
            }

            vec4 chimeraVertexValue() {
                return vec4(chimeraWorldPosition(), 1.0);
            }

            vec4 chimeraColorValue() {
                return unpackUnorm4x8(inPackedColor);
            }

            vec4 chimeraTexCoord0Value() {
                return vec4(vec2(inUV) * CHIMERA_UV_SCALE, 0.0, 1.0);
            }

            vec4 chimeraTexCoord1Value() {
                return vec4(
                        (vec2(float((uint(inPositionLight.w) >> 4u) & 0xFu),
                              float((uint(inPositionLight.w) >> 12u) & 0xFu)) + 0.5) / 16.0,
                        0.0, 1.0);
            }

            vec2 chimeraMcEntityValue() {
                return vec2(float(inMaterialId), float(inRenderType));
            }

            vec4 chimeraFtransform() {
                return MVP * chimeraVertexValue();
            }

            """;

    /** The shadow config exposes only the single host MVP matrix at binding 0. */
    private static final String SHADOW_VERTEX_PREAMBLE = TERRAIN_VERTEX_PREAMBLE
            .replace("mat4 LightMVP;\n", "");

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
