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
            "\\buniform\\s+([A-Za-z_]\\w*)\\s+([^;{}]+);");
    private static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s*\\{");

    private UniformRegistry() {}

    /** Shader stage of the pipeline a pack program is built onto. */
    public enum Stage {
        /** composite/final post seams (fullscreen, slot 0 = seam color). */
        POST,
        /** gbuffers_* terrain path (fixed-vertex inputs, host-set registry slots). */
        GEOMETRY,
        /** shadow gbuffers path (terrain inputs, but no shadow feedback samplers). */
        SHADOW,
        /** gbuffers_water on the host translucent terrain layer. */
        TRANSLUCENT
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
                    .filter(uniform -> !blocked.contains(uniform.name())
                            && !isDeviationForName(deviations, "UNIFORM_DECLARATION_UNUSED:",
                            uniform.name()))
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
            Map.entry("shadowtex1", 5),
            Map.entry("shadowcolor0", 3),
            Map.entry("shadowcolor1", 3),
            Map.entry("depthtex0", 6),
            Map.entry("depthtex1", 6),
            Map.entry("noisetex", 7)
    );

    /** Geometry stage: the host's registry slots the terrain draw path fills. */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5)
    );

    /** Shadow writes must not sample the resource they are currently filling. */
    public static final Map<String, Integer> SHADOW_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("lightmap", 2)
    );

    /** Translucent terrain uses the host's atlas, lightmap, and shadow slots. */
    public static final Map<String, Integer> TRANSLUCENT_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5)
    );

    /**
     * The water bridge is intentionally limited to fields already present in
     * the host terrain UBO. Lower-case names are the small accepted pack
     * aliases; their values are rewritten to the corresponding host fields.
     */
    private static final Map<String, String> TRANSLUCENT_UNIFORM_FIELDS = Map.ofEntries(
            Map.entry("fogColor", "FogColor"),
            Map.entry("fogStart", "FogRenderDistanceStart"),
            Map.entry("fogEnd", "FogRenderDistanceEnd"),
            Map.entry("textureSize", "TextureSize"),
            Map.entry("texelSize", "TexelSize"),
            Map.entry("FogColor", "FogColor"),
            Map.entry("FogEnvironmentalStart", "FogEnvironmentalStart"),
            Map.entry("FogEnvironmentalEnd", "FogEnvironmentalEnd"),
            Map.entry("FogRenderDistanceStart", "FogRenderDistanceStart"),
            Map.entry("FogRenderDistanceEnd", "FogRenderDistanceEnd"),
            Map.entry("FogSkyEnd", "FogSkyEnd"),
            Map.entry("FogCloudsEnd", "FogCloudsEnd"),
            Map.entry("AlphaCutout", "AlphaCutout"),
            Map.entry("TextureSize", "TextureSize"),
            Map.entry("TexelSize", "TexelSize"),
            Map.entry("UseRgss", "UseRgss")
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
        return planInternal(source, stage, false, false);
    }

    /**
     * Plans a post program whose colortex bindings are backed by M5.6 target
     * images rather than the older single-image seam aliases.
     */
    public static ProgramInterface planPost(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            throw new IllegalArgumentException("post target plan is required");
        }
        return planInternal(source, Stage.POST, true, false);
    }

    /** Plans a source snapshot after the pack preprocessor has removed inactive declarations. */
    public static ProgramInterface planPrepared(String source, Stage stage) {
        return planInternal(source, stage, false, true);
    }

    /** Prepared post source variant used by the real-pack loader. */
    public static ProgramInterface planPreparedPost(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            throw new IllegalArgumentException("post target plan is required");
        }
        return planInternal(source, Stage.POST, true, true);
    }

    private static ProgramInterface planInternal(
            String source, Stage stage, boolean targetedPost, boolean allowUnusedDeclarations) {
        String stripped = stripComments(source == null ? "" : source);
        Map<String, UniformDeclaration> declarations = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        Map<String, String> samplerNames = new TreeMap<>();
        Map<Integer, String> samplerResources = new TreeMap<>();
        Set<String> deviations = new TreeSet<>();

        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (Variable variable : parseVariables(matcher.group(2))) {
                if (isSamplerType(type)) {
                    samplerNames.putIfAbsent(variable.name(), type);
                    continue;
                }
                String declaredType = variable.array() ? type + "[]" : type;
                UniformDeclaration declaration = new UniformDeclaration(variable.name(), declaredType);
                UniformDeclaration previous = declarations.putIfAbsent(variable.name(), declaration);
                if (previous != null && !previous.glslType().equals(declaredType)) {
                    conflicts.add(variable.name());
                    declarations.put(variable.name(), declaration);
                }
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
            String fixedField = stage == Stage.TRANSLUCENT
                    ? TRANSLUCENT_UNIFORM_FIELDS.get(name) : name;
            UniformSpec spec = fixedField == null ? null : UNIFORM_SPECS.get(fixedField);
            if (!SUPPORTED_TYPES.contains(type) || !compatibleType(name, type, spec)) {
                if (allowUnusedDeclarations && !isReferenced(stripped, name)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + name);
                }
                continue;
            }
            if (spec == null) {
                if (allowUnusedDeclarations && !isReferenced(stripped, name)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("UNIFORM_NAME_UNSUPPORTED:" + name);
                }
                continue;
            }
            if (allowUnusedDeclarations && !isReferenced(stripped, name)) {
                deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                continue;
            }
            deviations.add("LIVE_UNIFORM_BRIDGE");
            if (stage == Stage.TRANSLUCENT) {
                deviations.add("TRANSLUCENT_FIXED_UNIFORM_BRIDGE");
            }
            if (spec.availability() == Availability.DEFAULTED) {
                deviations.add("UNIFORM_DEFAULTED:" + name);
            }
        }

        Map<String, Integer> slots = switch (stage) {
            case POST -> NAME_TO_SLOT;
            case GEOMETRY -> GEOMETRY_NAME_TO_SLOT;
            case SHADOW -> SHADOW_NAME_TO_SLOT;
            case TRANSLUCENT -> TRANSLUCENT_NAME_TO_SLOT;
        };
        List<SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            if (allowUnusedDeclarations && !isReferenced(stripped, sampler.getKey())) {
                deviations.add("SAMPLER_DECLARATION_UNUSED:" + sampler.getKey());
                continue;
            }
            Integer slot = slots.get(sampler.getKey());
            boolean mappedType = sampler.getValue().equals("sampler2D")
                    || sampler.getValue().equals("sampler2DShadow");
            if (!mappedType || slot == null) {
                if (stage == Stage.SHADOW && sampler.getKey().startsWith("shadowcolor")) {
                    deviations.add("SHADOW_COLOR_INPUT_UNSUPPORTED");
                } else if (stage == Stage.TRANSLUCENT
                        && (sampler.getKey().startsWith("depthtex")
                        || sampler.getKey().startsWith("shadowcolor"))) {
                    deviations.add("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED");
                } else if (stage == Stage.TRANSLUCENT) {
                    deviations.add("TRANSLUCENT_SAMPLER_UNSUPPORTED:" + sampler.getKey());
                } else {
                    deviations.add(stage == Stage.SHADOW
                            ? "SHADOW_SAMPLER_UNSUPPORTED:" + sampler.getKey()
                            : "SAMPLER_NOT_MAPPED:" + sampler.getKey());
                }
                continue;
            }
            bindings.add(new SamplerBinding(sampler.getKey(), slot));
            String resource = samplerResource(sampler.getKey());
            String previousResource = samplerResources.putIfAbsent(slot, resource);
            if (previousResource != null && !previousResource.equals(resource)) {
                deviations.add("SAMPLER_SLOT_CONFLICT:" + slot);
            }
            if (stage == Stage.POST && isHostAlias(sampler.getKey())) {
                deviations.add("SAMPLER_ALIAS_TO_HOST:" + sampler.getKey());
            }
            if (stage == Stage.POST && sampler.getKey().equals("noisetex")) {
                deviations.add("NOISETEX_PACK_RESOURCE");
            }
            if (stage == Stage.POST && sampler.getKey().equals("depthtex0")) {
                deviations.add("DEPTH_INPUT_FIXED_TO_HDR");
            }
            if (stage == Stage.POST && !targetedPost && sampler.getKey().matches("colortex[1-3]")) {
                deviations.add("COLORTEX_ALIAS_TO_SEAM");
            }
        }

        if ((stage == Stage.GEOMETRY || stage == Stage.SHADOW) && !declarations.isEmpty()) {
            for (String name : declarations.keySet()) {
                deviations.add("UNIFORM_NAME_UNSUPPORTED:" + name);
            }
        }

        return new ProgramInterface(stage, List.copyOf(declarations.values()), bindings, List.copyOf(deviations));
    }

    private static List<Variable> parseVariables(String body) {
        List<Variable> result = new ArrayList<>();
        for (String part : body.split(",")) {
            String value = part.trim();
            int equals = value.indexOf('=');
            if (equals >= 0) {
                value = value.substring(0, equals).trim();
            }
            Matcher name = Pattern.compile("^([A-Za-z_]\\w*)(\\s*\\[[^]]*\\])?$")
                    .matcher(value);
            if (name.matches()) {
                result.add(new Variable(name.group(1), name.group(2) != null));
            }
        }
        return result;
    }

    private static boolean compatibleType(String name, String type, UniformSpec spec) {
        if (spec == null) {
            return SUPPORTED_TYPES.contains(type);
        }
        if (spec.type().equals(type)) {
            return true;
        }
        // Iris declares fogColor as vec3 while the M5.3 fixture uses vec4.
        return name.equals("fogColor") && (type.equals("vec3") || type.equals("vec4"));
    }

    private static boolean isReferenced(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        return matcher.find() && matcher.find();
    }

    private static boolean isHostAlias(String name) {
        return name.equals("depthtex1") || name.equals("shadowtex1")
                || name.equals("shadowcolor0") || name.equals("shadowcolor1");
    }

    private record Variable(String name, boolean array) {}

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
                for (Variable variable : parseVariables(matcher.group(2))) {
                    names.add(variable.name());
                }
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
        Set<String> executable = plan.executableUniforms().stream()
                .map(UniformDeclaration::name).collect(java.util.stream.Collectors.toSet());
        Set<String> unusedSamplers = new java.util.HashSet<>();
        for (String deviation : plan.deviations()) {
            if (deviation.startsWith("UNIFORM_DECLARATION_UNUSED:")) {
                executable.add(deviation.substring("UNIFORM_DECLARATION_UNUSED:".length()));
            } else if (deviation.startsWith("SAMPLER_DECLARATION_UNUSED:")) {
                unusedSamplers.add(deviation.substring("SAMPLER_DECLARATION_UNUSED:".length()));
            }
        }
        Matcher matcher = UNIFORM_DECLARATION.matcher(result);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            if (isSamplerType(matcher.group(1))) {
                List<Variable> variables = parseVariables(matcher.group(2));
                if (!variables.isEmpty()
                        && variables.stream().allMatch(variable -> unusedSamplers.contains(variable.name()))) {
                    out.append(result, last, matcher.start());
                    last = matcher.end();
                    changed = true;
                }
                continue;
            }
            List<Variable> variables = parseVariables(matcher.group(2));
            boolean remove = !variables.isEmpty()
                    && variables.stream().allMatch(variable -> executable.contains(variable.name()));
            if (remove) {
                out.append(result, last, matcher.start());
                last = matcher.end();
                changed = true;
            }
        }
        if (!changed) {
            return result;
        }
        out.append(result, last, result.length());
        return out.toString();
    }

    private static boolean isExecutionBlocking(String deviation) {
        return deviation.startsWith("UNIFORM_TYPE_UNSUPPORTED:")
                || deviation.startsWith("UNIFORM_NAME_UNSUPPORTED:")
                || deviation.startsWith("UNIFORM_CONFLICT:")
                || deviation.startsWith("SAMPLER_SLOT_CONFLICT:")
                || deviation.startsWith("SAMPLER_NOT_MAPPED:")
                || deviation.startsWith("SHADOW_SAMPLER_UNSUPPORTED:")
                || deviation.equals("SHADOW_COLOR_INPUT_UNSUPPORTED")
                || deviation.startsWith("TRANSLUCENT_SAMPLER_UNSUPPORTED:")
                || deviation.equals("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED");
    }

    private static boolean isSamplerType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    private static String samplerResource(String name) {
        if (name.startsWith("shadowcolor")) {
            return "shadowcolor";
        }
        if (name.startsWith("shadowtex")) {
            return "shadowtex";
        }
        if (name.startsWith("depthtex")) {
            return "depthtex";
        }
        return name;
    }

    private static boolean isDeviationForName(
            Collection<String> deviations,
            String prefix,
            String name
    ) {
        return deviations.contains(prefix + name);
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
        addLive(specs, "shadowModelView", "mat4");
        addLive(specs, "shadowProjection", "mat4");
        addLive(specs, "viewWidth", "float");
        addLive(specs, "viewHeight", "float");
        addLive(specs, "aspectRatio", "float");
        addLive(specs, "near", "float");
        addLive(specs, "far", "float");
        addDefault(specs, "wetness", "float");
        addLive(specs, "sunAngle", "float");
        addLive(specs, "frameCounter", "int");
        addLive(specs, "cameraPositionInt", "ivec3");
        addLive(specs, "previousCameraPositionInt", "ivec3");
        addLive(specs, "cameraPositionFract", "vec3");
        addLive(specs, "previousCameraPositionFract", "vec3");
        addLive(specs, "previousCameraPosition", "vec3");
        addDefault(specs, "cloudHeight", "float");

        // Common standard Iris values used by real legacy post sources. The
        // provider supplies live values where Chimera has a source of truth;
        // the remaining values are explicit zero or identity defaults.
        addDefault(specs, "bedrockLevel", "int");
        addDefault(specs, "blindFactor", "float");
        addDefault(specs, "darknessFactor", "float");
        addDefault(specs, "darknessLightFactor", "float");
        addDefault(specs, "endFlashIntensity", "float");
        addDefault(specs, "endFlashPosition", "vec3");
        addLive(specs, "frameTime", "float");
        addDefault(specs, "frameTimeSmooth", "float");
        addDefault(specs, "framemod2", "float");
        addDefault(specs, "framemod4", "float");
        addDefault(specs, "framemod8", "float");
        addDefault(specs, "framemod600", "float");
        addDefault(specs, "isCold", "float");
        addDefault(specs, "isDesert", "float");
        addDefault(specs, "isJungle", "float");
        addDefault(specs, "isMesa", "float");
        addDefault(specs, "isMushroom", "float");
        addDefault(specs, "isSavanna", "float");
        addDefault(specs, "isSwamp", "float");
        addDefault(specs, "inBasaltDeltas", "float");
        addDefault(specs, "inCrimsonForest", "float");
        addDefault(specs, "inDry", "float");
        addDefault(specs, "inNetherWastes", "float");
        addDefault(specs, "inPaleGarden", "float");
        addDefault(specs, "inRainy", "float");
        addDefault(specs, "inSnowy", "float");
        addDefault(specs, "inSoulValley", "float");
        addDefault(specs, "inWarpedForest", "float");
        addDefault(specs, "isEyeInCave", "float");
        addDefault(specs, "maxBlindnessDarkness", "float");
        addDefault(specs, "nightVision", "float");
        addDefault(specs, "rainFactor", "float");
        addDefault(specs, "screenBrightness", "float");
        addDefault(specs, "shadowFade", "float");
        addDefault(specs, "starter", "float");
        addDefault(specs, "timeAngle", "float");
        addDefault(specs, "timeBrightness", "float");
        addDefault(specs, "velocity", "float");
        addDefault(specs, "worldDay", "int");
        addDefault(specs, "atlasSize", "ivec2");
        addDefault(specs, "eyeBrightness", "ivec2");
        addDefault(specs, "eyeBrightnessSmooth", "ivec2");
        addDefault(specs, "eyePosition", "vec3");
        addDefault(specs, "playerLookVector", "vec3");
        addDefault(specs, "relativeEyePosition", "vec3");
        addDefault(specs, "skyColor", "vec3");
        addDefault(specs, "entityColor", "vec4");
        addDefault(specs, "lightningBoltPosition", "vec4");
        addDefault(specs, "blockEntityId", "int");
        addDefault(specs, "currentRenderedItemId", "int");
        addDefault(specs, "entityId", "int");
        addDefault(specs, "heldBlockLightValue", "int");
        addDefault(specs, "heldBlockLightValue2", "int");
        addDefault(specs, "heldItemId", "int");
        addDefault(specs, "heldItemId2", "int");
        addDefault(specs, "gbufferModelView", "mat4");
        addDefault(specs, "gbufferModelViewInverse", "mat4");
        addDefault(specs, "gbufferPreviousModelView", "mat4");
        addDefault(specs, "gbufferPreviousProjection", "mat4");
        addDefault(specs, "gbufferProjection", "mat4");
        addDefault(specs, "gbufferProjectionInverse", "mat4");
        addDefault(specs, "shadowModelViewInverse", "mat4");
        addDefault(specs, "shadowProjectionInverse", "mat4");

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

    /** Returns the host terrain UBO field for an accepted water uniform. */
    public static String translucentUniformField(String name) {
        return TRANSLUCENT_UNIFORM_FIELDS.get(name);
    }
}
