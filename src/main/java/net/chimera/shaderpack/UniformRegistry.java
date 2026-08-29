package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small, shared interface model for pack shader declarations.
 *
 * <p>Pack names are intentionally resolved here instead of independently in
 * the probe, converter, and pipeline builder. That keeps descriptor bindings
 * and compatibility decisions deterministic.</p>
 */
public final class UniformRegistry {
    private static final Pattern UNIFORM_DECLARATION = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s+([A-Za-z_]\\w*)(\\s*\\[[^;]*\\])?\\s*;");
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s*\\{");

    private UniformRegistry() {}

    /** Shader stage of the pipeline a pack program is built onto. */
    public enum Stage {
        /** composite/final post seams (fullscreen, slot 0 = seam color). */
        POST,
        /** gbuffers_* terrain path (fixed-vertex inputs, host-set registry slots). */
        GEOMETRY
    }

    /** One ordinary GLSL uniform declaration, excluding sampler declarations. */
    public record UniformDeclaration(String name, String glslType) {
        public UniformDeclaration {
            if (name == null || name.isBlank() || glslType == null || glslType.isBlank()) {
                throw new IllegalArgumentException("uniform declaration needs a name and type");
            }
        }
    }

    /** One pack sampler and its fixed VulkanMod texture registry slot. */
    public record SamplerBinding(String name, int slot) {
        public SamplerBinding {
            if (name == null || name.isBlank() || slot < 0) {
                throw new IllegalArgumentException("sampler binding is invalid");
            }
        }
    }

    /** Immutable interface plan shared by scanning, conversion, and building. */
    public record ProgramInterface(
            Stage stage,
            List<UniformDeclaration> uniforms,
            List<SamplerBinding> samplers,
            List<String> deviations
    ) {
        public ProgramInterface {
            stage = stage == null ? Stage.POST : stage;
            uniforms = sortedUniforms(uniforms);
            samplers = sortedSamplers(samplers);
            deviations = sortedStrings(deviations);
        }

        /** Uniforms safe to place in the generated UBO. */
        public List<UniformDeclaration> executableUniforms() {
            Set<String> blocked = new TreeSet<>();
            for (String deviation : deviations) {
                int separator = deviation.indexOf(':');
                if (separator >= 0 && (deviation.startsWith("UNIFORM_TYPE_UNSUPPORTED:")
                        || deviation.startsWith("UNIFORM_NAME_UNSUPPORTED:")
                        || deviation.startsWith("UNIFORM_CONFLICT:"))) {
                    blocked.add(deviation.substring(separator + 1));
                }
            }
            return uniforms.stream()
                    .filter(uniform -> !blocked.contains(uniform.name()))
                    .toList();
        }

        public boolean hasSampler(String name) {
            return samplers.stream().anyMatch(sampler -> sampler.name().equals(name));
        }

        public int samplerIndex(String name) {
            for (int i = 0; i < samplers.size(); i++) {
                if (samplers.get(i).name().equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        /** True when the current converter and resource bridge may execute this plan. */
        public boolean executable() {
            return deviations.stream().noneMatch(UniformRegistry::isExecutionBlocking);
        }
    }

    /** OptiFine texture uniform name -> fixed VTextureSelector binding slot (post stage). */
    public static final Map<String, Integer> NAME_TO_SLOT = Map.ofEntries(
            Map.entry("colortex0", 0),
            Map.entry("colortex1", 1),
            Map.entry("colortex2", 2),
            Map.entry("colortex3", 3),
            Map.entry("shadowtex0", 5),
            Map.entry("depthtex0", 6)
    );

    /** Geometry stage: the host's registry slots the terrain draw path fills. */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5)
    );

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "float", "int", "vec2", "vec3", "vec4",
            "ivec2", "ivec3", "ivec4", "mat4");

    /** Names already supplied by VulkanMod or by the M5.3 provider. */
    private static final Map<String, UniformSpec> UNIFORM_SPECS = uniformSpecs();

    /**
     * Builds the immutable declaration plan for one fragment source.
     * Comments are ignored. Unsupported declarations remain visible in the
     * deviation list and make the plan ineligible for pipeline construction.
     */
    public static ProgramInterface plan(String source, Stage stage) {
        String stripped = stripComments(source == null ? "" : source);
        Map<String, UniformDeclaration> declarations = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        Map<String, String> samplerNames = new TreeMap<>();
        Set<String> deviations = new TreeSet<>();

        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            String array = matcher.group(3);
            if (isSamplerType(type)) {
                samplerNames.putIfAbsent(name, type);
                continue;
            }
            String declaredType = array == null ? type : type + "[]";
            UniformDeclaration declaration = new UniformDeclaration(name, declaredType);
            UniformDeclaration previous = declarations.putIfAbsent(name, declaration);
            if (previous != null && !previous.glslType().equals(declaredType)) {
                conflicts.add(name);
                declarations.put(name, declaration);
            }
        }

        Matcher blockMatcher = UNIFORM_BLOCK.matcher(stripped);
        while (blockMatcher.find()) {
            deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + blockMatcher.group(1));
        }

        for (UniformDeclaration declaration : declarations.values()) {
            String name = declaration.name();
            String type = declaration.glslType();
            if (conflicts.contains(name)) {
                deviations.add("UNIFORM_CONFLICT:" + name);
            }
            UniformSpec spec = UNIFORM_SPECS.get(name);
            if (!SUPPORTED_TYPES.contains(type) || (spec != null && !spec.type().equals(type))) {
                deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + name);
                continue;
            }
            if (spec == null) {
                deviations.add("UNIFORM_NAME_UNSUPPORTED:" + name);
                continue;
            }
            deviations.add("LIVE_UNIFORM_BRIDGE");
            if (spec.availability() == Availability.DEFAULTED) {
                deviations.add("UNIFORM_DEFAULTED:" + name);
            }
        }

        Map<String, Integer> slots = stage == Stage.GEOMETRY
                ? GEOMETRY_NAME_TO_SLOT
                : NAME_TO_SLOT;
        List<SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            Integer slot = slots.get(sampler.getKey());
            if (!"sampler2D".equals(sampler.getValue()) || slot == null) {
                deviations.add("SAMPLER_NOT_MAPPED:" + sampler.getKey());
                continue;
            }
            bindings.add(new SamplerBinding(sampler.getKey(), slot));
            if (stage == Stage.POST && sampler.getKey().equals("depthtex0")) {
                deviations.add("DEPTH_INPUT_FIXED_TO_HDR");
            }
            if (stage == Stage.POST && sampler.getKey().matches("colortex[1-3]")) {
                deviations.add("COLORTEX_ALIAS_TO_SEAM");
            }
        }

        if (stage == Stage.GEOMETRY && !declarations.isEmpty()) {
            for (String name : declarations.keySet()) {
                deviations.add("UNIFORM_NAME_UNSUPPORTED:" + name);
            }
        }

        return new ProgramInterface(stage, List.copyOf(declarations.values()), bindings, List.copyOf(deviations));
    }

    /**
     * The OptiFine sampler names a fragment program declares, in ascending
     * slot order, resolved against the stage's map. Unknown names are omitted
     * from this compatibility list but remain a deviation in {@link #plan}.
     */
    public static List<String> scanSamplerNames(String fragmentSource, Stage stage) {
        return plan(fragmentSource, stage).samplers().stream().map(SamplerBinding::name).toList();
    }

    /** Returns all declared sampler names, including unsupported names, sorted. */
    public static List<String> scanDeclaredSamplerNames(String source) {
        String stripped = stripComments(source == null ? "" : source);
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            if (isSamplerType(matcher.group(1))) {
                names.add(matcher.group(2));
            }
        }
        return List.copyOf(names);
    }

    /** Returns all ordinary uniform declarations, including unsupported ones. */
    public static List<UniformDeclaration> scanUniformDeclarations(String source) {
        return plan(source, Stage.POST).uniforms();
    }

    /** PipelineConfig's JSON type/count representation for one accepted type. */
    public static String pipelineType(String glslType) {
        return switch (glslType) {
            case "mat4" -> "matrix4x4";
            case "float", "vec2", "vec3", "vec4" -> "float";
            case "int", "ivec2", "ivec3", "ivec4" -> "int";
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + glslType);
        };
    }

    /** PipelineConfig's JSON component count for one accepted type. */
    public static int pipelineCount(String glslType) {
        return switch (glslType) {
            case "mat4" -> 16;
            case "float", "int" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + glslType);
        };
    }

    /** Removes only accepted ordinary declarations, keeping sampler declarations for binding rewrite. */
    public static String removeUniformDeclarations(String source, ProgramInterface plan) {
        String result = source == null ? "" : source;
        for (UniformDeclaration declaration : plan.executableUniforms()) {
            String type = Pattern.quote(declaration.glslType());
            result = result.replaceAll(
                    "(?m)\\buniform\\s+" + type + "\\s+" + Pattern.quote(declaration.name())
                            + "\\s*;\\s*", "");
        }
        return result;
    }

    private static boolean isExecutionBlocking(String deviation) {
        return deviation.startsWith("UNIFORM_TYPE_UNSUPPORTED:")
                || deviation.startsWith("UNIFORM_NAME_UNSUPPORTED:")
                || deviation.startsWith("UNIFORM_CONFLICT:")
                || deviation.startsWith("SAMPLER_NOT_MAPPED:");
    }

    private static boolean isSamplerType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    private static Map<String, UniformSpec> uniformSpecs() {
        Map<String, UniformSpec> specs = new LinkedHashMap<>();
        addLive(specs, "cameraPosition", "vec3");
        addLive(specs, "worldTime", "int");
        addLive(specs, "frameTimeCounter", "float");
        addLive(specs, "rainStrength", "float");
        addLive(specs, "isEyeInWater", "int");
        addLive(specs, "moonPhase", "int");
        addLive(specs, "sunPosition", "vec3");
        addLive(specs, "moonPosition", "vec3");
        addLive(specs, "shadowLightPosition", "vec3");
        addLive(specs, "viewWidth", "float");
        addLive(specs, "viewHeight", "float");
        addLive(specs, "aspectRatio", "float");
        addLive(specs, "near", "float");
        addLive(specs, "far", "float");
        addDefault(specs, "wetness", "float");

        addLive(specs, "MVP", "mat4");
        addLive(specs, "ModelViewMat", "mat4");
        addLive(specs, "ProjMat", "mat4");
        addLive(specs, "TextureMat", "mat4");
        addLive(specs, "FogColor", "vec4");
        addLive(specs, "FogStart", "float");
        addLive(specs, "FogEnd", "float");
        addLive(specs, "FogEnvironmentalStart", "float");
        addLive(specs, "FogEnvironmentalEnd", "float");
        addLive(specs, "FogRenderDistanceStart", "float");
        addLive(specs, "FogRenderDistanceEnd", "float");
        addLive(specs, "FogSkyEnd", "float");
        addLive(specs, "FogCloudsEnd", "float");
        addLive(specs, "AlphaCutout", "float");
        addLive(specs, "ScreenSize", "vec2");
        addLive(specs, "TextureSize", "ivec2");
        addLive(specs, "TexelSize", "vec2");
        addLive(specs, "Light0_Direction", "vec3");
        addLive(specs, "Light1_Direction", "vec3");
        addLive(specs, "ColorModulator", "vec4");
        addLive(specs, "ModelOffset", "vec3");
        addLive(specs, "ChunkOffset", "vec3");
        addLive(specs, "UseRgss", "int");
        addLive(specs, "CurrentTime", "int");
        addLive(specs, "EndPortalLayers", "int");

        addLive(specs, "fogColor", "vec4");
        addLive(specs, "fogStart", "float");
        addLive(specs, "fogEnd", "float");
        addLive(specs, "screenSize", "vec2");
        addLive(specs, "textureSize", "ivec2");
        addLive(specs, "texelSize", "vec2");
        return Map.copyOf(specs);
    }

    private static void addLive(Map<String, UniformSpec> specs, String name, String type) {
        specs.put(name, new UniformSpec(type, Availability.LIVE));
    }

    private static void addDefault(Map<String, UniformSpec> specs, String name, String type) {
        specs.put(name, new UniformSpec(type, Availability.DEFAULTED));
    }

    private enum Availability {
        LIVE,
        DEFAULTED
    }

    private record UniformSpec(String type, Availability availability) {}

    private static List<UniformDeclaration> sortedUniforms(Collection<UniformDeclaration> values) {
        return values.stream()
                .distinct()
                .sorted(Comparator.comparing(UniformDeclaration::name)
                        .thenComparing(UniformDeclaration::glslType))
                .toList();
    }

    private static List<SamplerBinding> sortedSamplers(Collection<SamplerBinding> values) {
        Map<String, SamplerBinding> unique = new TreeMap<>();
        for (SamplerBinding value : values) {
            unique.putIfAbsent(value.name(), value);
        }
        return unique.values().stream()
                .sorted(Comparator.comparingInt(SamplerBinding::slot)
                        .thenComparing(SamplerBinding::name))
                .toList();
    }

    private static List<String> sortedStrings(Collection<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static String stripComments(String source) {
        return source
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
    }
}
