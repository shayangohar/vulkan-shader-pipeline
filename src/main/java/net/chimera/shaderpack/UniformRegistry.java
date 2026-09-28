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

    /**
     * Host ordinary-transform block names for the guarded family lanes.
     *
     * <p>They must match the converted GLSL preambles in
     * {@link LegacyGlslConverter} and the name-based
     * {@code Pipeline.getUBO(String)} lookups, which compare names exactly.
     * VulkanMod's own reflected pipelines carry these same semantic names, and
     * a host render pass stores its per-draw transform slices under them.</p>
     */
    public static final String DYNAMIC_TRANSFORMS_UBO = "DynamicTransforms";
    /** Per-draw entity alpha-test reference, Chimera's counterpart of iris_currentAlphaTest. */
    public static final String ENTITY_ALPHA_REFERENCE = "chimeraAlphaTestRef";
    public static final String PROJECTION_UBO = "Projection";

    /** Descriptor bindings of the host ordinary-transform blocks. */
    public static final int DYNAMIC_TRANSFORMS_BINDING = 0;
    public static final int PROJECTION_BINDING = 1;

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
        ENTITY,
        /** gbuffers_block on the guarded block-entity lane. */
        BLOCK,
        /** gbuffers_hand on the guarded first-person item lane. */
        HAND,
        /** gbuffers_particles on the host particle lane. */
        PARTICLE,
        /** gbuffers_skybasic and gbuffers_skytextured host sky lanes. */
        SKY,
        /** gbuffers_clouds host cloud lane. */
        CLOUD
    }

    /** One ordinary GLSL uniform declaration, excluding sampler declarations. */
    public record UniformDeclaration(String name, String glslType) {
        public UniformDeclaration {
            if (name == null || name.isBlank() || glslType == null || glslType.isBlank()) {
                throw new IllegalArgumentException("uniform declaration needs a name and type");
            }
        }
    }

    /** One pack sampler, its GLSL type, and its fixed VulkanMod texture slot. */
    public record SamplerBinding(String name, int slot, String glslType) {
        public SamplerBinding {
            if (name == null || name.isBlank() || slot < 0) {
                throw new IllegalArgumentException("sampler binding is invalid");
            }
            if (glslType == null || glslType.isBlank()) {
                throw new IllegalArgumentException("sampler binding needs a GLSL type");
            }
        }

        /** Compatibility constructor for callers that use the ordinary sampler type. */
        public SamplerBinding(String name, int slot) {
            this(name, slot, "sampler2D");
        }
    }

    /** Whether Chimera reads a value from the current frame or uses a declared default. */
    public enum Availability {
        LIVE,
        DEFAULTED,
        /** Declared by the pack, refused by Chimera: never a value, always a diagnostic. */
        REJECTED
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
            List<String> deviations,
            List<SamplerBinding> samplerLayout
    ) {
        public ProgramInterface {
            stage = stage == null ? Stage.POST : stage;
            uniforms = sortedUniforms(uniforms);
            samplers = sortedSamplers(samplers);
            deviations = sortedStrings(deviations);
            samplerLayout = sortedSamplers(samplerLayout);
        }

        /** A standalone stage uses its own sampler order as the descriptor layout. */
        public ProgramInterface(
                Stage stage,
                List<UniformDeclaration> uniforms,
                List<SamplerBinding> samplers,
                List<String> deviations
        ) {
            this(stage, uniforms, samplers, deviations, samplers);
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
                            && !((stage == Stage.ENTITY || stage == Stage.BLOCK || stage == Stage.HAND)
                            && uniform.name().equals("entityId"))
                            && !isDeviationForName(deviations, "UNIFORM_DECLARATION_UNUSED:",
                            uniform.name()))
                    .toList();
        }

        public boolean hasSampler(String name) {
            return samplers.stream().anyMatch(sampler -> sampler.name().equals(name));
        }

        public int samplerIndex(String name) {
            for (int i = 0; i < samplerLayout.size(); i++) {
                if (samplerLayout.get(i).name().equals(name)) {
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

        /** Keeps shared uniform fields and binding order, but emits only this stage's samplers. */
        public ProgramInterface project(String sourceStage, Stage familyStage) {
            ProgramInterface source = stages.get(sourceStage);
            if (source == null) {
                throw new IllegalArgumentException("program source stage is missing: " + sourceStage);
            }
            List<String> projectedDeviations = new ArrayList<>(deviations);
            for (String deviation : source.deviations()) {
                if (deviation.startsWith("SAMPLER_DECLARATION_UNUSED:")) {
                    projectedDeviations.add(deviation);
                }
            }
            return new ProgramInterface(familyStage == null ? source.stage() : familyStage,
                    uniforms, source.samplers(), projectedDeviations, samplers);
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
        return planProgram(fragmentSource, vertexSource, stage, targetPlan, prepared,
                Map.of(), Map.of());
    }

    /** Builds a program interface with the valid custom scalar descriptors for one pack session. */
    public static ProgramInterfacePlan planProgram(
            String fragmentSource,
            String vertexSource,
            Stage stage,
            PostTargetPlan targetPlan,
            boolean prepared,
            Map<String, UniformDescriptor> customDescriptors
    ) {
        return planProgram(fragmentSource, vertexSource, stage, targetPlan, prepared,
                customDescriptors, Map.of());
    }

    /** Builds a program interface with pack-owned sampler slots. */
    public static ProgramInterfacePlan planProgram(
            String fragmentSource,
            String vertexSource,
            Stage stage,
            PostTargetPlan targetPlan,
            boolean prepared,
            Map<String, UniformDescriptor> customDescriptors,
            Map<String, Integer> customSamplerSlots
    ) {
        Stage effectiveStage = stage == null ? Stage.POST : stage;
        ProgramInterface fragment = effectiveStage == Stage.POST && targetPlan != null
                ? planInternal(fragmentSource, Stage.POST, true, prepared,
                customDescriptors, customSamplerSlots)
                : planInternal(fragmentSource, effectiveStage, false, prepared,
                customDescriptors, customSamplerSlots);
        Map<String, ProgramInterface> stagePlans = new TreeMap<>();
        stagePlans.put("fragment", fragment);
        List<UniformDeclaration> uniforms = new ArrayList<>(fragment.uniforms());
        List<SamplerBinding> samplers = new ArrayList<>(fragment.samplers());
        List<String> deviations = new ArrayList<>(fragment.deviations());
        if (vertexSource != null) {
            ProgramInterface vertex = planInternal(vertexSource, effectiveStage, false, prepared,
                    customDescriptors, customSamplerSlots);
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
            Map.entry("colortex8", SelectorNamespace.COLORTEX8_SLOT),
            Map.entry("shadowtex0", 5),
            // shadowtex1 is its own image, the casters before translucent
            // terrain, so every stage reads it on its own selector.
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", SelectorNamespace.SHADOW_COLOR0_SLOT),
            Map.entry("shadowcolor1", SelectorNamespace.SHADOW_COLOR1_SLOT),
            Map.entry("depthtex0", 6),
            Map.entry("depthtex1", 12),
            Map.entry("depthtex2", 13),
            Map.entry("noisetex", 7)
    );

    /**
     * A pack may expose its block atlas through a custom texture named
     * textureAtlas.  Complementary also uses the legacy name tex in the
     * material helper that reads that same declared resource.  The alias is
     * resolved only when the pack has already allocated textureAtlas; it is
     * not a host-texture fallback and does not consume a second selector slot.
     */
    private static final String PACK_ATLAS_SAMPLER = "textureAtlas";

    /** Geometry stage: the host's registry slots the terrain draw path fills. */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", 3),
            Map.entry("noisetex", 7)
    );

    /** Shadow writes must not sample the resource they are currently filling. */
    public static final Map<String, Integer> SHADOW_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            Map.entry("lightmap", 2),
            Map.entry("noisetex", 7)
    );

    /**
     * Translucent terrain is terrain on the host translucent lane: the
     * geometry slots, plus the opaque depth Iris gives gbuffers_water as
     * depthtex1. The pack colour targets it reads come from
     * {@link #translucentColorInputSlot}.
     */
    public static final Map<String, Integer> TRANSLUCENT_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", 3),
            Map.entry("noisetex", 7),
            Map.entry("depthtex1", 12)
    );

    /**
     * World entities use the host atlas, overlay, lightmap, noise, and
     * optional shadow map. The overlay is declared by EntityOverlayColor.
     */
    public static final Map<String, Integer> ENTITY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry(PackResourcePlan.OVERLAY_SAMPLER, 1),
            Map.entry("lightmap", 2),
            Map.entry("tex", 0),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", SelectorNamespace.SHADOW_COLOR0_SLOT),
            // Noise stays on the canonical fixed selector 7, matching the
            // post, geometry, and shadow tables and the resource plan. A
            // custom pack-selector allocation here leaves the ordinary
            // descriptor without a same-slot manifest resource and rejects
            // the pipeline (DOC-348 repair 1).
            Map.entry("noisetex", 7)
    );
    /**
     * Resource-pack material maps ride fixed extended selectors, but only in
     * stages whose draws bind a real albedo image. Sky and cloud reuse the
     * geometry slot table without material draws, and post/shadow never
     * serve material names, so the overlay is keyed on stage, not on table.
     */
    public static final Map<String, Integer> MATERIAL_NAME_TO_SLOT = Map.of(
            "normals", SelectorNamespace.NORMALS_SLOT,
            "specular", SelectorNamespace.SPECULAR_SLOT
    );

    /** Stages whose draws carry an albedo image that material maps can follow. */
    public static final Set<Stage> MATERIAL_STAGES = Set.of(
            Stage.GEOMETRY, Stage.TRANSLUCENT, Stage.ENTITY,
            Stage.BLOCK, Stage.HAND, Stage.PARTICLE
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
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations, Map.of());
    }

    private static ProgramInterface planInternal(
            String source,
            Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformDescriptor> customDescriptors
    ) {
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations,
                customDescriptors, Map.of());
    }

    private static ProgramInterface planInternal(
            String source,
            Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformDescriptor> customDescriptors,
            Map<String, Integer> customSamplerSlots
    ) {
        String stripped = stripComments(source == null ? "" : source);
        Map<String, UniformDeclaration> declarations = new TreeMap<>();
        Set<String> implicitDeclarations = new TreeSet<>();
        Set<String> conflicts = new TreeSet<>();
        Map<String, String> samplerNames = new TreeMap<>();
        Map<Integer, String> samplerResources = new TreeMap<>();
        Set<String> deviations = new TreeSet<>();
        Set<String> referencedValues = new TreeSet<>(allowUnusedDeclarations
                ? GlslResourceUsage.referencedValueIdentifiers(stripped, UNIFORM_SPECS.keySet())
                : Set.of());
        if (allowUnusedDeclarations && stage == Stage.SHADOW) {
            if (containsIdentifier(stripped, "gl_ModelViewMatrix")) {
                referencedValues.add("shadowModelView");
            }
            if (containsIdentifier(stripped, "gl_NormalMatrix")) {
                referencedValues.add("shadowModelViewInverse");
            }
            if (containsIdentifier(stripped, "gl_ProjectionMatrix")) {
                referencedValues.add("shadowProjection");
            }
        }

        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (Variable variable : parseVariables(matcher.group(2))) {
                if (isSamplerType(type)) {
                    samplerNames.putIfAbsent(variable.name(), type);
                    continue;
                }
                // Writable 3D images belong to the M8.6 advanced-resource
                // plan, not to the ordinary generated UBO. Keeping them out
                // of this catalog lets the graphics image bridge assign one
                // explicit storage descriptor later.
                if (isStorageImageType(type)) {
                    deviations.add("ADVANCED_IMAGE_DECLARATION:" + variable.name());
                    continue;
                }
                // Pack authors write bool for Iris's isElytraFlying; the
                // catalog field is int, so a bool declaration of that name
                // plans as the live int field rather than failing type
                // support.
                String declaredType = type;
                if (variable.name().equals("isElytraFlying") && type.equals("bool")) {
                    declaredType = "int";
                }
                String effectiveType = variable.array() ? declaredType + "[]" : declaredType;
                UniformDeclaration declaration = new UniformDeclaration(
                        variable.name(), effectiveType);
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

        // Real packs sometimes expose standard Iris uniforms through a
        // conditional include that is absent from the prepared snapshot. If
        // the name is in the canonical catalog and has exactly one accepted
        // type, synthesize the declaration from that catalog entry. This is
        // deliberately table-driven and does not infer arbitrary GLSL types.
        if (allowUnusedDeclarations) {
            for (UniformDescriptor descriptor : UNIFORM_SPECS.values()) {
                if (descriptor.acceptedTypes().size() != 1
                        || declarations.containsKey(descriptor.name())
                        || !referencedValues.contains(descriptor.name())
                        || isLocallyDeclared(stripped, descriptor.name())) {
                    continue;
                }
                declarations.put(descriptor.name(), new UniformDeclaration(
                        descriptor.name(), descriptor.acceptedTypes().get(0)));
                implicitDeclarations.add(descriptor.name());
                deviations.add("UNIFORM_IMPLICIT_DECLARATION:" + descriptor.name());
            }
        }

        for (UniformDeclaration declaration : declarations.values()) {
            String name = declaration.name();
            String type = declaration.glslType();
            if (conflicts.contains(name)) {
                deviations.add("UNIFORM_CONFLICT:" + name);
            }
            if ((stage == Stage.ENTITY || stage == Stage.BLOCK || stage == Stage.HAND)
                    && name.equals("entityId")) {
                if (!type.equals("int") && !type.equals("float")) {
                    deviations.add("ENTITY_ID_UNSUPPORTED:" + type);
                } else if (allowUnusedDeclarations && !isReferencedForStage(stripped, name, stage)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("ENTITY_ID_VERTEX_DATA");
                }
                continue;
            }
            UniformDescriptor spec = UNIFORM_SPECS.get(name);
            if (spec == null && customDescriptors != null) {
                spec = customDescriptors.get(name);
            }
            UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
            if (custom != null && custom.availability() == Availability.REJECTED) {
                // The pack wrote this declaration and Chimera could not honour it. A program that
                // reads the name must not run with a substitute value, so it fails closed here.
                if (allowUnusedDeclarations && !isReferencedForStage(stripped, name, stage)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("CUSTOM_VALUE_REJECTED:" + name);
                }
                continue;
            }
            if (!SUPPORTED_TYPES.contains(type) || !compatibleType(name, type, spec)) {
                if (allowUnusedDeclarations && !isReferencedForStage(stripped, name, stage)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + name);
                }
                continue;
            }
            if (spec == null) {
                if (allowUnusedDeclarations && !isReferencedForStage(stripped, name, stage)) {
                    deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                } else {
                    deviations.add("UNIFORM_NAME_UNSUPPORTED:" + name);
                }
                continue;
            }
            if (allowUnusedDeclarations && !implicitDeclarations.contains(name)
                    && !isReferencedForStage(stripped, name, stage)) {
                deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
                continue;
            }
            deviations.add("LIVE_UNIFORM_BRIDGE");
            if (customDescriptors != null && customDescriptors.containsKey(name)) {
                deviations.add("CUSTOM_UNIFORM_BRIDGE:" + name);
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
            case ENTITY, BLOCK, HAND -> ENTITY_NAME_TO_SLOT;
            case PARTICLE -> ENTITY_NAME_TO_SLOT;
            case SKY, CLOUD -> GEOMETRY_NAME_TO_SLOT;
        };
        List<SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            if (allowUnusedDeclarations && isSamplerUnused(stripped, sampler.getKey())) {
                deviations.add("SAMPLER_DECLARATION_UNUSED:" + sampler.getKey());
                continue;
            }
            Integer slot = slots.get(sampler.getKey());
            if (stage == Stage.POST && slot == null) {
                slot = extendedPostColorSlot(sampler.getKey());
            }
            if (stage == Stage.TRANSLUCENT && slot == null) {
                slot = translucentColorInputSlot(sampler.getKey());
            }
            if (stage == Stage.POST && slot == null && sampler.getKey().equals("tex")
                    && customSamplerSlots != null) {
                slot = customSamplerSlots.get(PACK_ATLAS_SAMPLER);
                if (slot != null) {
                    deviations.add("SAMPLER_ALIAS_TO_PACK_RESOURCE:tex:textureAtlas");
                }
            }
            if (slot == null && customSamplerSlots != null) {
                slot = customSamplerSlots.get(sampler.getKey());
            }
            if (slot == null && MATERIAL_NAME_TO_SLOT.containsKey(sampler.getKey())
                    && MATERIAL_STAGES.contains(stage)) {
                slot = MATERIAL_NAME_TO_SLOT.get(sampler.getKey());
            }
            boolean mappedType = sampler.getValue().equals("sampler2D")
                    || sampler.getValue().equals("sampler2DShadow")
                    || ((sampler.getValue().equals("sampler3D")
                    || sampler.getValue().equals("isampler3D")
                    || sampler.getValue().equals("usampler3D"))
                    && customSamplerSlots != null
                    && customSamplerSlots.containsKey(sampler.getKey()));
            if (!mappedType || slot == null) {
                if (stage == Stage.SHADOW && sampler.getKey().startsWith("shadowcolor")) {
                    deviations.add("SHADOW_COLOR_INPUT_UNSUPPORTED");
                } else if (stage == Stage.TRANSLUCENT
                        && (sampler.getKey().startsWith("depthtex")
                        || sampler.getKey().startsWith("shadowcolor"))) {
                    deviations.add("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED");
                } else if (stage == Stage.ENTITY || stage == Stage.BLOCK || stage == Stage.HAND
                        || stage == Stage.PARTICLE) {
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
            bindings.add(new SamplerBinding(sampler.getKey(), slot, sampler.getValue()));
            String resource = stage == Stage.POST && sampler.getKey().equals("tex")
                    && customSamplerSlots != null
                    && customSamplerSlots.containsKey(PACK_ATLAS_SAMPLER)
                    ? PACK_ATLAS_SAMPLER : samplerResource(sampler.getKey());
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
            if (stage == Stage.POST && sampler.getKey().startsWith("depthtex")) {
                // Keep the M5.3 single-depth report stable while the new
                // graph is introduced. M7.4 graph snapshots carry the
                // authoritative semantic correction.
                if (sampler.getKey().equals("depthtex0")) {
                    deviations.add("DEPTH_INPUT_FIXED_TO_HDR");
                } else {
                    deviations.add("DEPTH_INPUT_GRAPH:" + sampler.getKey());
                }
            }
            if (stage == Stage.POST && !targetedPost && sampler.getKey().matches("colortex[1-3]")) {
                deviations.add("COLORTEX_ALIAS_TO_SEAM");
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

    /**
     * The catalog entry a program's declaration resolves to, the pack's own authored values
     * included: a refused declaration resolves to nothing, so nothing serves it a substitute.
     */
    public static UniformDescriptor resolve(
            String name,
            String glslType,
            Map<String, UniformDescriptor> customDescriptors
    ) {
        UniformDescriptor spec = descriptor(name, glslType);
        if (spec != null) {
            return spec;
        }
        spec = customDescriptors == null ? null : customDescriptors.get(name);
        if (spec == null) {
            return null;
        }
        return spec.availability() == Availability.REJECTED || !spec.accepts(glslType) ? null : spec;
    }

    /**
     * The names the host answers for. A pack may not author a declaration under one of these: it
     * would replace a value the host is responsible for. Every other name is the pack's own.
     */
    public static boolean isEngineInput(String name) {
        return name != null && UNIFORM_SPECS.containsKey(name);
    }

    /**
     * The engine inputs with a per-component source, so {@code eyeBrightness.y} resolves to
     * something the host actually reads. A component of any other name is a declaration error
     * rather than a zero.
     */
    private static final Set<String> COMPONENT_INPUTS = Set.of(
            "eyeBrightness", "eyeBrightnessSmooth",
            "cameraPosition", "previousCameraPosition",
            "cameraPositionFract", "previousCameraPositionFract",
            "cameraPositionInt", "previousCameraPositionInt",
            "eyePosition", "relativeEyePosition", "playerLookVector",
            "sunPosition", "moonPosition", "shadowLightPosition", "upPosition", "skyColor");

    /** Whether a component of the uniform's own vector type exists, for {@code name.x}. */
    public static boolean supportsComponent(UniformDescriptor descriptor, int component) {
        if (descriptor == null || component < 0) {
            return true;
        }
        if (!COMPONENT_INPUTS.contains(descriptor.name())) {
            return false;
        }
        for (String type : descriptor.acceptedTypes()) {
            if (componentCount(type) > component) {
                return true;
            }
        }
        return false;
    }

    private static int componentCount(String glslType) {
        return switch (glslType) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> 0;
        };
    }

    private static boolean isReferenced(String source, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
        return matcher.find() && matcher.find();
    }

    /** Accounts for stage-local legacy shadow matrix aliases during planning. */
    private static boolean isReferencedForStage(String source, String name, Stage stage) {
        if (isReferenced(source, name)) {
            return true;
        }
        if (stage != Stage.SHADOW) {
            return false;
        }
        return switch (name) {
            case "shadowModelView" -> containsIdentifier(source, "gl_ModelViewMatrix");
            case "shadowModelViewInverse" -> containsIdentifier(source, "gl_NormalMatrix");
            case "shadowProjection" -> containsIdentifier(source, "gl_ProjectionMatrix");
            default -> false;
        };
    }

    private static boolean containsIdentifier(String source, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source).find();
    }

    private static boolean isSamplerUnused(String source, String name) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source);
        return usage.successful() && !usage.liveSamplers().contains(name);
    }

    private static String removeUniformDeclaration(String source, String name) {
        Matcher matcher = UNIFORM_DECLARATION.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            boolean contains = parseVariables(matcher.group(2)).stream()
                    .anyMatch(value -> value.name().equals(name));
            if (contains) {
                result.append(source, last, matcher.start());
                last = matcher.end();
            }
        }
        if (last == 0) return source;
        result.append(source, last, source.length());
        return result.toString();
    }

    private static boolean isLocallyDeclared(String source, String name) {
        return Pattern.compile("\\b(?:const\\s+)?[A-Za-z_]\\w*\\s+"
                + Pattern.quote(name) + "\\b").matcher(source).find();
    }

    private static boolean isHostAlias(String name) {
        return name.equals("shadowtex1")
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
                if (!variables.isEmpty()) {
                    List<Variable> retained = variables.stream()
                            .filter(variable -> !unusedSamplers.contains(variable.name()))
                            .toList();
                    if (retained.size() != variables.size()) {
                        out.append(result, last, matcher.start());
                        if (!retained.isEmpty()) {
                            out.append("uniform ").append(matcher.group(1)).append(' ')
                                    .append(retained.stream().map(Variable::name)
                                            .collect(java.util.stream.Collectors.joining(", ")))
                                    .append(';');
                        }
                        last = matcher.end();
                        changed = true;
                    }
                }
                continue;
            }
            List<Variable> variables = parseVariables(matcher.group(2));
            if (!variables.isEmpty()) {
                List<Variable> retained = variables.stream()
                        .filter(variable -> !executable.contains(variable.name()))
                        .toList();
                if (retained.size() != variables.size()) {
                    out.append(result, last, matcher.start());
                    if (!retained.isEmpty()) {
                        out.append("uniform ").append(matcher.group(1)).append(' ')
                                .append(retained.stream().map(Variable::name)
                                        .collect(java.util.stream.Collectors.joining(", ")))
                                .append(';');
                    }
                    last = matcher.end();
                    changed = true;
                }
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
                || deviation.startsWith("CUSTOM_VALUE_REJECTED:")
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

    private static boolean isStorageImageType(String type) {
        return "image3D".equals(type) || "iimage3D".equals(type) || "uimage3D".equals(type);
    }

    private static String samplerResource(String name) {
        return PackResourcePlan.canonicalResource(name);
    }

    /**
     * Keep VulkanMod's reserved slots intact while exposing four additional
     * logical post targets through selector slots 8 through 11.
     */
    private static Integer extendedPostColorSlot(String name) {
        Integer target = PackResourcePlan.targetIndex(name);
        if (target == null) {
            return null;
        }
        // The first four aliases use the host selector slots. The extended
        // logical targets continue at slots 8 through 11. This keeps aliases
        // such as gcolor and gaux1 equivalent to their colortex names while
        // preserving the existing M5.3 slots for colortex0..3.
        if (target >= 0 && target <= 3) {
            return target;
        }
        return target <= PostTargetPlan.MAX_TARGET ? target + 4 : null;
    }

    /**
     * Pack colour targets a translucent program may sample. They share the
     * post selectors (colortex4..7 at 8..11, colortex8 at its own slot), so
     * one target keeps one selector across the pack and PackSettingsPlan
     * already reserves them. colortex0..3 would land on the host atlas,
     * overlay, lightmap and shadowcolor0 selectors, so they stay unmapped.
     */
    private static Integer translucentColorInputSlot(String name) {
        Integer target = PackResourcePlan.targetIndex(name);
        if (target == null || target < 4 || target > 8) {
            return null;
        }
        return target == 8 ? SelectorNamespace.COLORTEX8_SLOT : target + 4;
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
        addLive(specs, "wetness", "float");
        addLive(specs, "sunAngle", "float");
        addLive(specs, "shadowAngle", "float");
        addLive(specs, "upPosition", "vec3");
        addLive(specs, "skyColor", "vec3");
        addLive(specs, "frameCounter", "int");
        addLive(specs, "cameraPositionInt", "ivec3");
        addLive(specs, "previousCameraPositionInt", "ivec3");
        addLive(specs, "cameraPositionFract", "vec3");
        addLive(specs, "previousCameraPositionFract", "vec3");
        addLive(specs, "previousCameraPosition", "vec3");
        addLive(specs, "cloudHeight", "float");

        // Common standard Iris values used by real legacy post sources. The
        // provider supplies live values where Chimera has a source of truth;
        // the remaining values are explicit zero or identity defaults.
        addLive(specs, "bedrockLevel", "int");
        addLive(specs, "blindness", "float", "blindness");
        addLive(specs, "darknessFactor", "float");
        addLive(specs, "darknessLightFactor", "float");
        addLive(specs, "isElytraFlying", "int");
        addDefault(specs, "endFlashIntensity", "float");
        addDefault(specs, "endFlashPosition", "vec3");
        addLive(specs, "frameTime", "float");
        addLive(specs, "nightVision", "float");
        addLive(specs, "screenBrightness", "float");
        addDefault(specs, "velocity", "float");
        addLive(specs, "worldDay", "int");
        addLive(specs, "atlasSize", "ivec2");
        addLive(specs, "eyeBrightness", "ivec2");
        addLive(specs, "eyeBrightnessSmooth", "ivec2");
        addLive(specs, "eyeAltitude", "float");
        addLive(specs, "eyePosition", "vec3");
        addLive(specs, "playerLookVector", "vec3");
        addLive(specs, "relativeEyePosition", "vec3");
        addLive(specs, "biome", "int");
        addLive(specs, "biome_precipitation", "int");
        addLive(specs, "dimension", "int");
        addLive(specs, "heightLimit", "int");
        addLive(specs, "logicalHeightLimit", "int");
        addLive(specs, "seaLevel", "int");
        addLive(specs, "hasCeiling", "int");
        addLive(specs, "hasSkylight", "int");
        addLive(specs, "ambientLight", "float");
        addLive(specs, "temperature", "float");
        addLive(specs, "rainfall", "float");
        addDefault(specs, "centerDepthSmooth", "float");
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
        // The shadow adapter renders the terrain caster lane as one fixed
        // stage. Iris normally supplies this value from its render scheduler;
        // Chimera uses the solid-terrain value as an explicit adapter default.
        addDefault(specs, "renderStage", "int");

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
        addLive(specs, ENTITY_ALPHA_REFERENCE, "float");
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
            // Mapping failure is not evidence that a sampler is dead. A live
            // unsupported declaration may also be unused in the paired stage.
            for (String deviation : stage.deviations()) {
                for (String prefix : List.of("SAMPLER_NOT_MAPPED:",
                        "SHADOW_SAMPLER_UNSUPPORTED:", "TRANSLUCENT_SAMPLER_UNSUPPORTED:",
                        "ENTITY_SAMPLER_UNSUPPORTED:")) {
                    if (deviation.startsWith(prefix)) {
                        usedSamplers.add(deviation.substring(prefix.length()));
                    }
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
}
