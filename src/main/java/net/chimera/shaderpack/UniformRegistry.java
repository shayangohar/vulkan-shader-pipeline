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
        TRANSLUCENT,
        /** gbuffers_entities on the guarded world entity lane. */
        ENTITY
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

    /** Whether Chimera reads a value from the current frame or uses a declared default. */
    public enum Availability {
        LIVE,
        DEFAULTED
    }

    /** One canonical source and default policy for a standard pack uniform. */
    public enum DefaultPolicy {
        ZERO,
        ONE,
        IDENTITY_MATRIX,
        CLOUD_HEIGHT,
        NEAR_CLIP,
        FAR_CLIP,
        SMOOTH_WETNESS,
        SMOOTH_EYE_BRIGHTNESS
    }

    /** Immutable catalog entry shared by probing and runtime buffer creation. */
    public record UniformDescriptor(
            String name,
            List<String> acceptedTypes,
            Availability availability,
            String sourceKey,
            DefaultPolicy defaultPolicy
    ) {
        public UniformDescriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("uniform descriptor needs a name");
            }
            if (acceptedTypes == null || acceptedTypes.isEmpty()
                    || acceptedTypes.stream().anyMatch(type -> type == null || type.isBlank())) {
                throw new IllegalArgumentException("uniform descriptor needs accepted types");
            }
            acceptedTypes = acceptedTypes.stream().distinct().sorted().toList();
            availability = availability == null ? Availability.LIVE : availability;
            sourceKey = sourceKey == null || sourceKey.isBlank() ? name : sourceKey;
            defaultPolicy = defaultPolicy == null ? DefaultPolicy.ZERO : defaultPolicy;
        }

        public boolean accepts(String glslType) {
            return acceptedTypes.contains(glslType);
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
                            && !(stage == Stage.ENTITY && uniform.name().equals("entityId"))
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

    /**
     * Canonical interface for all stages in one pack program. The existing
     * ProgramInterface remains the stage-compatible facade used by the
     * VulkanMod pipeline builders.
     */
    public record ProgramInterfacePlan(
            Map<String, ProgramInterface> stages,
            List<UniformDeclaration> uniforms,
            List<SamplerBinding> samplers,
            List<String> deviations
    ) {
        public ProgramInterfacePlan {
            stages = stages == null
                    ? Map.of()
                    : java.util.Collections.unmodifiableMap(new TreeMap<>(stages));
            uniforms = sortedUniforms(uniforms);
            samplers = sortedSamplers(samplers);
            deviations = sortedStrings(reconcileStageUnused(stages, deviations));
        }

        /** Returns the legacy facade with the canonical union fields. */
        public ProgramInterface effective(Stage stage) {
            Stage effectiveStage = stage == null
                    ? stages.values().stream().findFirst().map(ProgramInterface::stage).orElse(Stage.POST)
                    : stage;
            return new ProgramInterface(effectiveStage, uniforms, samplers, deviations);
        }

        public boolean executable() {
            return effective(null).executable();
        }

        public List<UniformDeclaration> executableUniforms() {
            return effective(null).executableUniforms();
        }
    }

    /** Builds one program-level union from its prepared stage sources. */
    public static ProgramInterfacePlan planProgram(
            String fragmentSource,
            String vertexSource,
            Stage stage,
            PostTargetPlan targetPlan,
            boolean prepared
    ) {
        Stage effectiveStage = stage == null ? Stage.POST : stage;
        ProgramInterface fragment = effectiveStage == Stage.POST && targetPlan != null
                ? (prepared ? planPreparedPost(fragmentSource, targetPlan)
                : planPost(fragmentSource, targetPlan))
                : (prepared ? planPrepared(fragmentSource, effectiveStage)
                : plan(fragmentSource, effectiveStage));
        Map<String, ProgramInterface> stagePlans = new TreeMap<>();
        stagePlans.put("fragment", fragment);
        List<UniformDeclaration> uniforms = new ArrayList<>(fragment.uniforms());
        List<SamplerBinding> samplers = new ArrayList<>(fragment.samplers());
        List<String> deviations = new ArrayList<>(fragment.deviations());
        if (vertexSource != null) {
            ProgramInterface vertex = prepared
                    ? planPrepared(vertexSource, effectiveStage)
                    : plan(vertexSource, effectiveStage);
            stagePlans.put("vertex", vertex);
            uniforms.addAll(vertex.uniforms());
            samplers.addAll(vertex.samplers());
            deviations.addAll(vertex.deviations());
        }
        return new ProgramInterfacePlan(stagePlans, uniforms, samplers, deviations);
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
            Map.entry("depthtex2", 6),
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

    /** World entities use the host atlas, lightmap, and optional shadow map. */
    public static final Map<String, Integer> ENTITY_NAME_TO_SLOT = Map.ofEntries(
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

    /** The only catalog used by declaration planning and the runtime provider. */
    private static final Map<String, UniformDescriptor> UNIFORM_SPECS = uniformSpecs();

    /** Returns the canonical descriptor for a name, or null for an unknown name. */
    public static UniformDescriptor descriptor(String name) {
        return UNIFORM_SPECS.get(name);
    }

    /** Returns a descriptor only when the declaration uses one of its accepted types. */
    public static UniformDescriptor descriptor(String name, String glslType) {
        UniformDescriptor descriptor = descriptor(name);
        return descriptor != null && descriptor.accepts(glslType) ? descriptor : null;
    }

    /** Returns the complete deterministic catalog for diagnostics and tests. */
    public static List<UniformDescriptor> catalog() {
        return UNIFORM_SPECS.values().stream()
                .sorted(java.util.Comparator.comparing(UniformDescriptor::name))
                .toList();
    }

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
            if (stage == Stage.ENTITY && name.equals("entityId")) {
                if (!type.equals("int") && !type.equals("float")) {
                    deviations.add("ENTITY_ID_UNSUPPORTED:" + type);
                } else if (allowUnusedDeclarations && !isReferenced(stripped, name)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("ENTITY_ID_VERTEX_DATA");
                }
                continue;
            }
            String fixedField = stage == Stage.TRANSLUCENT
                    ? TRANSLUCENT_UNIFORM_FIELDS.get(name) : name;
            UniformDescriptor spec = fixedField == null ? null : UNIFORM_SPECS.get(fixedField);
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
            case ENTITY -> ENTITY_NAME_TO_SLOT;
        };
        List<SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            if (allowUnusedDeclarations && !isReferenced(stripped, sampler.getKey())) {
                deviations.add("SAMPLER_DECLARATION_UNUSED:" + sampler.getKey());
                continue;
            }
            Integer slot = slots.get(sampler.getKey());
            if (stage == Stage.POST && slot == null) {
                slot = extendedPostColorSlot(sampler.getKey());
            }
            boolean mappedType = sampler.getValue().equals("sampler2D")
                    || sampler.getValue().equals("sampler2DShadow");
            if (!mappedType || slot == null) {
                if (stage == Stage.SHADOW && sampler.getKey().startsWith("shadowcolor")) {
                    deviations.add("SHADOW_COLOR_INPUT_UNSUPPORTED");
                } else if (stage == Stage.TRANSLUCENT
                        && (sampler.getKey().startsWith("depthtex")
                        || sampler.getKey().startsWith("shadowcolor"))) {
                    deviations.add("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED");
                } else if (stage == Stage.ENTITY) {
                    deviations.add("ENTITY_SAMPLER_UNSUPPORTED:" + sampler.getKey());
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

    private static boolean compatibleType(String name, String type, UniformDescriptor spec) {
        if (spec == null) {
            return SUPPORTED_TYPES.contains(type);
        }
        return spec.accepts(type);
    }

    private static boolean isReferenced(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        return matcher.find() && matcher.find();
    }

    private static boolean isHostAlias(String name) {
        return name.equals("depthtex1") || name.equals("depthtex2") || name.equals("shadowtex1")
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
                || deviation.equals("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED")
                || deviation.startsWith("ENTITY_SAMPLER_UNSUPPORTED:")
                || deviation.startsWith("ENTITY_ID_UNSUPPORTED:");
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

    /**
     * Keep VulkanMod's reserved slots intact while exposing four additional
     * logical post targets through selector slots 8 through 11.
     */
    private static Integer extendedPostColorSlot(String name) {
        Matcher matcher = Pattern.compile("colortex(\\d+)").matcher(name);
        if (!matcher.matches()) {
            return null;
        }
        int target;
        try {
            target = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        return target >= 4 && target <= PostTargetPlan.MAX_TARGET ? target + 4 : null;
    }

    private static boolean isDeviationForName(
            Collection<String> deviations,
            String prefix,
            String name
    ) {
        return deviations.contains(prefix + name);
    }

    private static Map<String, UniformDescriptor> uniformSpecs() {
        Map<String, UniformDescriptor> specs = new LinkedHashMap<>();
        addLive(specs, "cameraPosition", "vec3");
        addLive(specs, "worldTime", "int");
        addLive(specs, "frameTimeCounter", "float");
        addLive(specs, "rainStrength", "float");
        addLive(specs, "thunderStrength", "float");
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
        addDefault(specs, "wetness", "float", DefaultPolicy.SMOOTH_WETNESS);
        addLive(specs, "sunAngle", "float");
        addLive(specs, "frameCounter", "int");
        addLive(specs, "cameraPositionInt", "ivec3");
        addLive(specs, "previousCameraPositionInt", "ivec3");
        addLive(specs, "cameraPositionFract", "vec3");
        addLive(specs, "previousCameraPositionFract", "vec3");
        addLive(specs, "previousCameraPosition", "vec3");
        addDefault(specs, "cloudHeight", "float", DefaultPolicy.CLOUD_HEIGHT);

        // Common standard Iris values used by real legacy post sources. The
        // provider supplies live values where Chimera has a source of truth;
        // the remaining values are explicit zero or identity defaults.
        addLive(specs, "bedrockLevel", "int");
        addLive(specs, "blindFactor", "float");
        addLive(specs, "darknessFactor", "float");
        addLive(specs, "darknessLightFactor", "float");
        addDefault(specs, "endFlashIntensity", "float");
        addDefault(specs, "endFlashPosition", "vec3");
        addLive(specs, "frameTime", "float");
        addDefault(specs, "frameTimeSmooth", "float");
        addLive(specs, "framemod2", "float");
        addLive(specs, "framemod4", "float");
        addLive(specs, "framemod8", "float");
        addLive(specs, "framemod600", "float");
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
        addLive(specs, "nightVision", "float");
        addLive(specs, "rainFactor", "float", "rainStrength");
        addLive(specs, "screenBrightness", "float");
        addDefault(specs, "shadowFade", "float", DefaultPolicy.ONE);
        addDefault(specs, "starter", "float");
        addLive(specs, "timeAngle", "float", "sunAngle");
        addDefault(specs, "timeBrightness", "float", DefaultPolicy.ONE);
        addDefault(specs, "velocity", "float");
        addLive(specs, "worldDay", "int");
        addDefault(specs, "atlasSize", "ivec2");
        addLive(specs, "eyeBrightness", "ivec2");
        addDefault(specs, "eyeBrightnessSmooth", "ivec2", DefaultPolicy.SMOOTH_EYE_BRIGHTNESS);
        addLive(specs, "eyeBrightnessM", "float");
        addLive(specs, "eyePosition", "vec3");
        addLive(specs, "playerLookVector", "vec3");
        addLive(specs, "relativeEyePosition", "vec3");
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
        addLive(specs, "gbufferModelView", "mat4");
        addLive(specs, "gbufferModelViewInverse", "mat4");
        addLive(specs, "gbufferPreviousModelView", "mat4");
        addLive(specs, "gbufferPreviousProjection", "mat4");
        addLive(specs, "gbufferProjection", "mat4");
        addLive(specs, "gbufferProjectionInverse", "mat4");
        addLive(specs, "shadowModelViewInverse", "mat4");
        addLive(specs, "shadowProjectionInverse", "mat4");

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

        addLive(specs, "fogColor", List.of("vec3", "vec4"), "fogColor");
        addLive(specs, "fogStart", "float");
        addLive(specs, "fogEnd", "float");
        addLive(specs, "screenSize", "vec2");
        addLive(specs, "textureSize", "ivec2");
        addLive(specs, "texelSize", "vec2");
        return Map.copyOf(specs);
    }

    private static void addLive(Map<String, UniformDescriptor> specs, String name, String type) {
        addLive(specs, name, List.of(type), name);
    }

    private static void addLive(Map<String, UniformDescriptor> specs, String name,
                                String type, String sourceKey) {
        addLive(specs, name, List.of(type), sourceKey);
    }

    private static void addLive(Map<String, UniformDescriptor> specs, String name,
                                List<String> types, String sourceKey) {
        specs.put(name, new UniformDescriptor(name, types, Availability.LIVE,
                sourceKey, DefaultPolicy.ZERO));
    }

    private static void addDefault(Map<String, UniformDescriptor> specs, String name, String type) {
        addDefault(specs, name, type, DefaultPolicy.ZERO);
    }

    private static void addDefault(Map<String, UniformDescriptor> specs, String name,
                                   String type, DefaultPolicy policy) {
        specs.put(name, new UniformDescriptor(name, List.of(type), Availability.DEFAULTED,
                name, policy));
    }

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

    private static List<String> reconcileStageUnused(
            Map<String, ProgramInterface> stages,
            Collection<String> values
    ) {
        Set<String> usedUniforms = new TreeSet<>();
        Set<String> usedSamplers = new TreeSet<>();
        for (ProgramInterface stage : stages.values()) {
            for (UniformDeclaration uniform : stage.uniforms()) {
                if (!stage.deviations().contains("UNIFORM_DECLARATION_UNUSED:" + uniform.name())) {
                    usedUniforms.add(uniform.name());
                }
            }
            for (SamplerBinding sampler : stage.samplers()) {
                if (!stage.deviations().contains("SAMPLER_DECLARATION_UNUSED:" + sampler.name())) {
                    usedSamplers.add(sampler.name());
                }
            }
        }
        return values.stream().filter(value -> {
            if (value.startsWith("UNIFORM_DECLARATION_UNUSED:")) {
                return !usedUniforms.contains(value.substring("UNIFORM_DECLARATION_UNUSED:".length()));
            }
            if (value.startsWith("SAMPLER_DECLARATION_UNUSED:")) {
                return !usedSamplers.contains(value.substring("SAMPLER_DECLARATION_UNUSED:".length()));
            }
            return true;
        }).toList();
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
