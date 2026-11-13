package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a pack uniform is and which ones Chimera serves: the shared interface model for pack shader
 * declarations (stages, declarations, sampler bindings, descriptors, a program's interface), the
 * uniform catalog and its lookup, and the selector slot tables. InterfacePlanner plans a program's
 * interface against it.
 *
 * <p>Pack names are intentionally resolved here instead of independently in
 * the probe, converter, and pipeline builder. That keeps descriptor bindings
 * and compatibility decisions deterministic.</p>
 */
public final class UniformRegistry {
    /** Per-draw entity alpha-test reference, Chimera's counterpart of iris_currentAlphaTest. */
    public static final String ENTITY_ALPHA_REFERENCE = "chimeraAlphaTestRef";
    /** Vanilla Globals.GlintAlpha (glint strength), declared into glint programs before planning. */
    public static final String GLINT_ALPHA = "chimeraGlintAlpha";
    /**
     * Per-draw entityId of a host-layout lane, Chimera's counterpart of Iris's fallback entityId
     * uniform: declared into host-layout programs in place of entityId before planning.
     */
    public static final String HOST_ENTITY_ID = "chimeraHostEntityId";

    /**
     * Descriptor bindings of the transform blocks (DynamicTransforms, Projection) the converted
     * preambles declare; the host fills their fields by name (HostPipelineLayout).
     */
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
        /**
         * shadow_entities on the shadow pass's entity lane: the shadow interface on the
         * extended entity inputs, with entityId and blockEntityId per vertex (Iris
         * SHADOW_ENTITIES_CUTOUT, EntityPatcher).
         */
        SHADOW_ENTITY,
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
        CLOUD;

        /** Programs that draw into the shadow map: its samplers, outputs and uniforms. */
        public boolean shadowPass() {
            return this == SHADOW || this == SHADOW_ENTITY;
        }

        /** Stages whose entity identities (entityId, blockEntityId, currentRenderedItemId) come from EntityIds. */
        public boolean perVertexEntityIdentity() {
            return this == ENTITY || this == BLOCK || this == HAND || this == SHADOW_ENTITY;
        }
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

        /** Iris sets a bool declaration as an int one (ProgramUniforms expects INT for GL_BOOL). */
        public boolean accepts(String glslType) {
            return acceptedTypes.contains(glslType) || INTEGRAL.contains(glslType)
                    && acceptedTypes.stream().anyMatch(INTEGRAL::contains);
        }

        private static final Set<String> INTEGRAL = Set.of("int", "bool");
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
                            && !(stage.perVertexEntityIdentity() && isEntityIdentity(uniform.name()))
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
                    : Collections.unmodifiableMap(new TreeMap<>(stages));
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
     * Terrain, sky and clouds: the host slots and Iris's addWorldDepthSamplers depths on post's selectors
     * (depthtex0 the world drawn before the group, KNOW-760; depthtex1 and depthtex2 their last captures).
     */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", 3),
            Map.entry("noisetex", 7),
            Map.entry("depthtex0", 6),
            Map.entry("depthtex1", 12),
            Map.entry("depthtex2", 13)
    );

    /**
     * Shadow writes must not sample the resource they are filling. shadowtex1 is the opaque copy,
     * last frame's while casters draw and this frame's for the translucent layer, as Iris copies it.
     */
    public static final Map<String, Integer> SHADOW_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("lightmap", 2),
            Map.entry("noisetex", 7),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            // The entity casters' overlay, which only their injected entityColor reads (EntityOverlayColor).
            Map.entry(PackResourcePlan.OVERLAY_SAMPLER, 1)
    );

    /**
     * Translucent terrain: the geometry slots with this frame's depths (depthtex0 and depthtex1 the
     * opaque depth, depthtex2 the pre-hand depth Iris copies just before translucents). Its colour
     * targets come from {@link #gbufferColorInputSlot}.
     */
    public static final Map<String, Integer> TRANSLUCENT_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("lightmap", 2),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", 3),
            Map.entry("noisetex", 7),
            Map.entry("depthtex0", 6),
            Map.entry("depthtex1", 12),
            Map.entry("depthtex2", 13)
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
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", SelectorNamespace.SHADOW_COLOR0_SLOT),
            // Noise stays on selector 7, as in every table and the resource plan: a pack selector here
            // leaves the descriptor without a same-slot manifest resource.
            Map.entry("noisetex", 7),
            // The three depth textures as in GEOMETRY_NAME_TO_SLOT; depthtex0 is captured per flush.
            Map.entry("depthtex0", 6),
            Map.entry("depthtex1", 12),
            Map.entry("depthtex2", 13)
    );
    /** Resource-pack material maps on fixed extended selectors, in MATERIAL_STAGES; keyed on stage, not table. */
    public static final Map<String, Integer> MATERIAL_NAME_TO_SLOT = Map.of(
            "normals", SelectorNamespace.NORMALS_SLOT,
            "specular", SelectorNamespace.SPECULAR_SLOT
    );

    /**
     * Every gbuffers and shadow stage: addLevelSamplers registers normals and specular in each, which
     * follow the draw's albedo; a texture without maps, such as clouds.png, reads the flat ones.
     */
    public static final Set<Stage> MATERIAL_STAGES = Set.of(
            Stage.GEOMETRY, Stage.TRANSLUCENT, Stage.ENTITY, Stage.BLOCK, Stage.HAND, Stage.PARTICLE,
            Stage.SKY, Stage.CLOUD, Stage.SHADOW, Stage.SHADOW_ENTITY
    );

    static final Set<String> SUPPORTED_TYPES = Set.of(
            "float", "int", "bool", "vec2", "vec3", "vec4",
            "ivec2", "ivec3", "ivec4", "mat4");

    /** The only catalog used by declaration planning and the runtime provider. */
    static final Map<String, UniformDescriptor> UNIFORM_SPECS = uniformSpecs();

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
                .sorted(Comparator.comparing(UniformDescriptor::name))
                .toList();
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
        UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
        UniformDescriptor spec = HOST_ONLY.contains(name) && custom != null ? null : descriptor(name, glslType);
        if (spec != null) {
            return spec;
        }
        if (custom != null) {
            if (custom.availability() == Availability.REJECTED) return null;
            return custom.accepts(glslType) ? custom : disabledDescriptor(name, glslType);
        }
        UniformDescriptor unset = unsetDescriptor(name, glslType);
        return unset != null || descriptor(name) == null ? unset : disabledDescriptor(name, glslType);
    }

    /**
     * The names the host answers for. A pack may not author a declaration under one of these: it
     * would replace a value the host is responsible for. Every other name is the pack's own,
     * including VulkanMod's host names, which Iris does not have.
     */
    public static boolean isEngineInput(String name) {
        return name != null && UNIFORM_SPECS.containsKey(name) && !HOST_ONLY.contains(name);
    }

    /**
     * VulkanMod's own uniform names, which the catalog carries for Chimera's host-shaped programs.
     * Iris has none of them, so a pack that authors one (Bliss's {@code texelSize}) gets its own
     * value, as it would under Iris.
     */
    static final Set<String> HOST_ONLY = Set.of(
            "MVP", "ModelViewMat", "ProjMat", "TextureMat", "FogColor", "FogStart", "FogEnd", "AlphaCutout",
            ENTITY_ALPHA_REFERENCE, GLINT_ALPHA, HOST_ENTITY_ID, "ScreenSize", "Light0_Direction", "Light1_Direction",
            "ColorModulator", "LineWidth", "ChunkOffset", "EndPortalLayers", "screenSize");

    /**
     * Names Iris 1.21.1 serves that Chimera does not yet. A program reading one must not run on a
     * zero Iris would never give it. A world program's alphaTestRef becomes its alpha-test
     * reference before planning (PackAlphaTestPlan); a post program reads whatever the last world
     * draw left in CapturedRenderingState, which has no equivalent here. The core-profile
     * matrices and chunkOffset are not here: IrisCoreProfile serves them in the stages whose Iris
     * transformer names them, and anywhere else Iris never sets them.
     */
    private static final Set<String> IRIS_UNSERVED = Set.of("alphaTestRef");

    /**
     * Live values a fullscreen pass or compute dispatch cannot read. A pass's gtextureSize is the
     * size of whatever texture the previous pass or world draw left on unit 0 when Program.use
     * updates the uniforms, before the pass binds its own samplers; nothing names that texture.
     * fogMode, fogShape and blendFunc are served into a pass as constants
     * (ServedUniforms.servePostValues, serveFogOff) and refuse only where that could not run.
     */
    private static final Set<String> POST_UNSERVED = Set.of("gtextureSize", "blendFunc", "fogMode", "fogShape");

    /** Whether a stage cannot read a name Iris serves elsewhere (POST_UNSERVED). */
    static boolean unservedIn(String name, Stage stage) {
        return stage == Stage.POST && POST_UNSERVED.contains(name);
    }

    /** {@link #resolve} for a compute dispatch, which reads what any pass left on the GL state. */
    static UniformDescriptor resolveForCompute(String name, String glslType,
                                               Map<String, UniformDescriptor> customDescriptors) {
        return unservedIn(name, Stage.POST) ? null : resolve(name, glslType, customDescriptors);
    }

    /** Whether Iris itself gives this name a value: the catalog, the unserved list, Iris internals. */
    public static boolean irisServes(String name) {
        return UNIFORM_SPECS.containsKey(name) && !HOST_ONLY.contains(name)
                || IRIS_UNSERVED.contains(name) || name.startsWith("iris_") || name.startsWith("u_");
    }

    /** Whether Iris sets a declared uniform: a name it serves, or one of the pack's custom uniforms. */
    static boolean irisSets(String name, Map<String, UniformDescriptor> customDescriptors) {
        return irisServes(name) || customDescriptors != null && customDescriptors.containsKey(name);
    }

    /**
     * A declared uniform Iris does not provide is left unset, so GL reads it as zero, and the
     * program still runs (Solas's {@code vxRenderDistance}, a Voxy value). Chimera serves the
     * same zero. Iris names Chimera lacks are not covered: those stay unsupported.
     */
    static UniformDescriptor unsetDescriptor(String name, String glslType) {
        if (name == null || irisServes(name) || !SUPPORTED_TYPES.contains(glslType)) {
            return null;
        }
        return new UniformDescriptor(name, List.of(glslType), Availability.DEFAULTED, name, DefaultPolicy.ZERO);
    }

    /**
     * A value Iris serves, declared with another type. Iris's {@code ProgramUniforms} logs "Wrong uniform
     * type" and disables it, so the program runs and reads zero (Insanity's {@code uniform float
     * worldTime}). The source key names no value, so the zero default is what is written.
     */
    static UniformDescriptor disabledDescriptor(String name, String glslType) {
        if (name == null || !SUPPORTED_TYPES.contains(glslType)) return null;
        return new UniformDescriptor(name, List.of(glslType), Availability.DEFAULTED, "disabled:" + name,
                DefaultPolicy.ZERO);
    }

    /**
     * The engine vectors and matrices the host serves to custom-value expressions component by
     * component, so {@code eyeBrightness.y} or {@code gbufferModelViewInverse.2.1} resolves to
     * something the host actually reads. A component of any other name is a declaration error
     * rather than a zero.
     */
    private static final Set<String> COMPONENT_INPUTS = Set.of(
            "eyeBrightness", "eyeBrightnessSmooth", "atlasSize",
            "cameraPosition", "previousCameraPosition",
            "cameraPositionFract", "previousCameraPositionFract",
            "cameraPositionInt", "previousCameraPositionInt",
            "eyePosition", "relativeEyePosition", "playerLookVector",
            "sunPosition", "moonPosition", "shadowLightPosition", "upPosition", "skyColor",
            "lightningBoltPosition",
            "gbufferModelView", "gbufferModelViewInverse", "gbufferPreviousModelView",
            "gbufferProjection", "gbufferProjectionInverse", "gbufferPreviousProjection",
            "shadowModelView", "shadowModelViewInverse", "shadowProjection", "shadowProjectionInverse",
            "dhProjection", "dhProjectionInverse", "dhPreviousProjection",
            "playerBodyVector",
            "currentDate", "currentTime", "currentYearTime");

    /**
     * How a custom-value expression types an engine input: 1 for a scalar, 2 to 4 for a vector,
     * {@link PackExpression#MATRIX_WIDTH} for a {@code mat4}, 0 when the name is not an input.
     */
    public static int expressionWidth(String name) {
        UniformDescriptor descriptor = descriptor(name);
        if (descriptor == null || descriptor.acceptedTypes().isEmpty()) {
            return 0;
        }
        return switch (descriptor.acceptedTypes().get(0)) {
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            case "mat4" -> PackExpression.MATRIX_WIDTH;
            case "float", "int", "bool" -> 1;
            default -> 0;
        };
    }

    /** Whether the host answers each component of a vector or matrix input to expressions. */
    public static boolean expressionComponentsServed(String name) {
        return COMPONENT_INPUTS.contains(name);
    }

    /** Iris EntityPatcher names served per vertex from EntityIds, not from the pack UBO. */
    static boolean isEntityIdentity(String name) {
        return EntityIdRewrites.ENTITY_ID_NAMES.contains(name);
    }

    /** PipelineConfig's JSON type/count representation for one accepted type. */
    public static String pipelineType(String glslType) {
        return switch (glslType) {
            case "mat4" -> "matrix4x4";
            case "float", "vec2", "vec3", "vec4" -> "float";
            case "int", "bool", "ivec2", "ivec3", "ivec4" -> "int";
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + glslType);
        };
    }

    /** PipelineConfig's JSON component count for one accepted type. */
    public static int pipelineCount(String glslType) {
        return switch (glslType) {
            case "mat4" -> 16;
            case "float", "int", "bool" -> 1;
            case "vec2", "ivec2" -> 2;
            case "vec3", "ivec3" -> 3;
            case "vec4", "ivec4" -> 4;
            default -> throw new IllegalArgumentException("unsupported pack uniform type: " + glslType);
        };
    }

    /** Whether a deviation keeps the interface, and so the program, from executing. */
    static boolean isExecutionBlocking(String deviation) {
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

    static boolean isSamplerType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    static boolean isStorageImageType(String type) {
        return type != null && type.matches("[iu]?image(?:1D|2D|3D)");
    }

    static String samplerResource(String name) {
        return PackResourcePlan.canonicalResource(name);
    }

    /**
     * Post passes sample every pack colour target on its one selector
     * ({@link SelectorNamespace#colorTargetSlot}), so aliases such as gcolor
     * and gaux1 resolve to the same slot as their colortex names.
     */
    static Integer extendedPostColorSlot(String name) {
        Integer target = PackResourcePlan.targetIndex(name);
        if (target == null || target > PostTargetPlan.MAX_TARGET) {
            return null;
        }
        int slot = SelectorNamespace.colorTargetSlot(target);
        return slot < 0 ? null : slot;
    }

    /**
     * Pack colour targets a world program may sample (Iris binds them in every
     * gbuffers stage), on the same selectors post uses, so one target keeps
     * one selector across the pack and PackSettingsPlan already reserves
     * them. colortex0..3 would land on
     * the host atlas, overlay, lightmap and shadowcolor0 selectors, so they
     * stay unmapped.
     */
    static Integer gbufferColorInputSlot(String name) {
        Integer target = PackResourcePlan.targetIndex(name);
        if (target == null || target < 4 || target > PostTargetPlan.MAX_TARGET) {
            return null;
        }
        return SelectorNamespace.colorTargetSlot(target);
    }

    private static boolean isDeviationForName(
            Collection<String> deviations,
            String prefix,
            String name
    ) {
        return deviations.contains(prefix + name);
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

    // The canonical uniform catalog: every name Chimera serves, its types, source and default.

    private static Map<String, UniformDescriptor> uniformSpecs() {
        Map<String, UniformDescriptor> specs = new LinkedHashMap<>();
        addWorldAndCameraUniforms(specs);
        addIrisStandardUniforms(specs);
        addIrisPlayerState(specs);
        addHostShaderUniforms(specs);
        return Map.copyOf(specs);
    }

    /** The OptiFine world, weather, camera, sky, shadow and frame uniforms. */
    private static void addWorldAndCameraUniforms(Map<String, UniformDescriptor> specs) {
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
    }

    /**
     * Common standard Iris values used by real legacy post sources. The
     * provider supplies live values where Chimera has a source of truth;
     * the remaining values are explicit zero or identity defaults.
     */
    private static void addIrisStandardUniforms(Map<String, UniformDescriptor> specs) {
        addLive(specs, "bedrockLevel", "int");
        addLive(specs, "blindness", "float", "blindness");
        addLive(specs, "darknessFactor", "float");
        addLive(specs, "darknessLightFactor", "float");
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
        addLive(specs, "biome_category", "int");
        addLive(specs, "biome_precipitation", "int");
        addLive(specs, "dimension", "int");
        addLive(specs, "heightLimit", "int");
        addLive(specs, "logicalHeightLimit", "int");
        // Iris serves these through uniform1b; packs declare them bool or int.
        addLive(specs, "hasCeiling", List.of("int", "bool"), "hasCeiling");
        addLive(specs, "hasSkylight", List.of("int", "bool"), "hasSkylight");
        addLive(specs, "ambientLight", "float");
        addLive(specs, "temperature", "float");
        addLive(specs, "rainfall", "float");
        addDefault(specs, "centerDepthSmooth", "float");
        addDefault(specs, "entityColor", "vec4");
        addLive(specs, "lightningBoltPosition", "vec4");
        // IdMapUniforms: each hand's item.properties id, its light and that light's colour.
        addLive(specs, "heldBlockLightValue", "int");
        addLive(specs, "heldBlockLightValue2", "int");
        addLive(specs, "heldItemId", "int");
        addLive(specs, "heldItemId2", "int");
        addLive(specs, "heldBlockLightColor", "vec3");
        addLive(specs, "heldBlockLightColor2", "vec3");
        addLive(specs, "gbufferModelView", "mat4");
        addLive(specs, "gbufferModelViewInverse", "mat4");
        addLive(specs, "gbufferPreviousModelView", "mat4");
        addLive(specs, "gbufferPreviousProjection", "mat4");
        addLive(specs, "gbufferProjection", "mat4");
        addLive(specs, "gbufferProjectionInverse", "mat4");
        addLive(specs, "shadowModelViewInverse", "mat4");
        addLive(specs, "shadowProjectionInverse", "mat4");
        addLive(specs, "renderStage", "int");
    }

    /**
     * The host's core-shader uniforms (MVP, the Fog block, ScreenSize and the rest), then Iris's fog
     * uniforms and their iris_ forms, cloudTime, gtextureSize, blendFunc, the selected block's id and
     * position, and screenSize.
     */
    private static void addHostShaderUniforms(Map<String, UniformDescriptor> specs) {
        addLive(specs, "MVP", "mat4");
        addLive(specs, "ModelViewMat", "mat4");
        addLive(specs, "ProjMat", "mat4");
        addLive(specs, "TextureMat", "mat4");
        addLive(specs, "FogColor", "vec4");
        addLive(specs, "FogStart", "float");
        addLive(specs, "FogEnd", "float");
        addLive(specs, "AlphaCutout", "float");
        addLive(specs, UniformRegistry.ENTITY_ALPHA_REFERENCE, "float");
        addLive(specs, UniformRegistry.GLINT_ALPHA, "float");
        addLive(specs, UniformRegistry.HOST_ENTITY_ID, "int");
        addLive(specs, "ScreenSize", "vec2");
        addLive(specs, "Light0_Direction", "vec3");
        addLive(specs, "Light1_Direction", "vec3");
        addLive(specs, "ColorModulator", "vec4");
        addLive(specs, "LineWidth", "float");
        addLive(specs, "ChunkOffset", "vec3");
        addLive(specs, "EndPortalLayers", "int");

        addLive(specs, "fogColor", List.of("vec3", "vec4"), "fogColor");
        addLive(specs, "fogStart", "float");
        addLive(specs, "fogEnd", "float");
        // FogUniforms in a program with fog; a post program reads FogMode.OFF constants (PackPlanBuilder).
        addLive(specs, "fogMode", "int");
        addLive(specs, "fogShape", "int");
        addLive(specs, "fogDensity", "float");
        // IrisInternalUniforms, which a legacy program's gl_Fog reads (LegacyShaderNormalizer): the
        // range is latched per program and frame, the colour and density are fogColor's and fogDensity's.
        addLive(specs, "iris_FogStart", "float");
        addLive(specs, "iris_FogEnd", "float");
        addLive(specs, "iris_FogColor", "vec4", "fogColor");
        addLive(specs, "iris_FogDensity", "float", "fogDensity");
        addLive(specs, "cloudTime", "float");
        // CommonUniforms' per-draw unit-0 texture size and blend; a post program reads its
        // blendFunc as a constant (ServedUniforms.servePostValues). IrisExclusiveUniforms' selection.
        addLive(specs, "gtextureSize", "ivec2");
        addLive(specs, "blendFunc", "ivec4");
        addLive(specs, "currentSelectedBlockId", "int");
        addLive(specs, "currentSelectedBlockPos", "vec3");
        addLive(specs, "screenSize", "vec2");
    }

    /**
     * Iris's player, HUD, clock and Distant Horizons uniforms (see IrisPlayerState).
     * Booleans take bool or int, as Iris's uniform1b serves either.
     */
    private static void addIrisPlayerState(Map<String, UniformDescriptor> specs) {
        for (String flag : List.of("hideGUI", "isRightHanded", "firstPersonCamera", "isSpectator",
                "is_burning", "is_hurt", "is_invisible", "is_on_ground", "is_sneaking", "is_sprinting", "is_alive")) {
            addLive(specs, flag, List.of("bool", "int"), flag);
        }
        for (String value : List.of("currentPlayerHealth", "maxPlayerHealth", "currentPlayerHunger",
                "maxPlayerHunger", "currentPlayerArmor", "maxPlayerArmor", "currentPlayerAir",
                "maxPlayerAir", "playerMood", "constantMood", "pi", "lastFrameTime", "dhNearPlane", "dhFarPlane")) {
            addLive(specs, value, "float");
        }
        for (String value : List.of("currentColorSpace", "dhRenderDistance", "blockEntityId", "currentRenderedItemId",
                "entityId")) {
            addLive(specs, value, "int");
        }
        addLive(specs, "playerBodyVector", "vec3");
        addLive(specs, "currentDate", "ivec3");
        addLive(specs, "currentTime", "ivec3");
        addLive(specs, "currentYearTime", "ivec2");
        addLive(specs, "dhProjection", "mat4");
        addLive(specs, "dhProjectionInverse", "mat4");
        addLive(specs, "dhPreviousProjection", "mat4");
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
        specs.put(name,
                new UniformDescriptor(name, List.of(type), Availability.DEFAULTED,
                name, policy));
    }
}
