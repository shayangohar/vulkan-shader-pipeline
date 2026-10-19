package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * The small, shared interface model for pack shader declarations.
 *
 * <p>Pack names are intentionally resolved here instead of independently in
 * the probe, converter, and pipeline builder. That keeps descriptor bindings
 * and compatibility decisions deterministic.</p>
 */
public final class UniformRegistry {
    static final Pattern UNIFORM_DECLARATION = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s+([^;{}]+);");
    static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s*\\{");

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
                ? InterfacePlanner.planInternal(fragmentSource, Stage.POST, true, prepared,
                customDescriptors, customSamplerSlots)
                : InterfacePlanner.planInternal(fragmentSource, effectiveStage, false, prepared,
                customDescriptors, customSamplerSlots);
        Map<String, ProgramInterface> stagePlans = new TreeMap<>();
        stagePlans.put("fragment", fragment);
        List<UniformDeclaration> uniforms = new ArrayList<>(fragment.uniforms());
        List<SamplerBinding> samplers = new ArrayList<>(fragment.samplers());
        List<String> deviations = new ArrayList<>(fragment.deviations());
        if (vertexSource != null) {
            ProgramInterface vertex = InterfacePlanner.planInternal(vertexSource, effectiveStage, false, prepared,
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
    static final String PACK_ATLAS_SAMPLER = "textureAtlas";

    /** Geometry stage: the host's registry slots the terrain draw path fills. */
    public static final Map<String, Integer> GEOMETRY_NAME_TO_SLOT = Map.ofEntries(
            Map.entry("texture", 0),
            Map.entry("tex", 0),
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
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
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("lightmap", 2),
            Map.entry("noisetex", 7)
    );

    /**
     * Translucent terrain is terrain on the host translucent lane: the
     * geometry slots, plus the opaque depth Iris gives gbuffers_water as
     * depthtex1. The pack colour targets it reads come from
     * {@link #gbufferColorInputSlot}.
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
            // Iris core-profile name for the albedo atlas.
            Map.entry("gtexture", 0),
            Map.entry("shadowtex0", 5),
            Map.entry("shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT),
            Map.entry("shadowcolor0", SelectorNamespace.SHADOW_COLOR0_SLOT),
            // Noise stays on the canonical fixed selector 7, matching the
            // post, geometry, and shadow tables and the resource plan. A
            // custom pack-selector allocation here leaves the ordinary
            // descriptor without a same-slot manifest resource and rejects
            // the pipeline.
            Map.entry("noisetex", 7),
            // The opaque depth snapshot, on the slot post and gbuffers_water read it from.
            Map.entry("depthtex1", 12)
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

    static final Set<String> SUPPORTED_TYPES = Set.of(
            "float", "int", "bool", "vec2", "vec3", "vec4",
            "ivec2", "ivec3", "ivec4", "mat4");

    /** The only catalog used by declaration planning and the runtime provider. */
    static final Map<String, UniformDescriptor> UNIFORM_SPECS = UniformCatalog.uniformSpecs();

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
     * Builds the immutable declaration plan for one fragment source.
     * Comments are ignored. Unsupported declarations remain visible in the
     * deviation list and make the plan ineligible for pipeline construction.
     */
    public static ProgramInterface plan(String source, Stage stage) {
        return InterfacePlanner.planInternal(source, stage, false, false);
    }

    /**
     * Plans a post program whose colortex bindings are backed by post target
     * images rather than the older single-image seam aliases.
     */
    public static ProgramInterface planPost(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            throw new IllegalArgumentException("post target plan is required");
        }
        return InterfacePlanner.planInternal(source, Stage.POST, true, false);
    }

    /** Plans a source snapshot after the pack preprocessor has removed inactive declarations. */
    public static ProgramInterface planPrepared(String source, Stage stage) {
        return InterfacePlanner.planInternal(source, stage, false, true);
    }

    /** Prepared post source variant used by the real-pack loader. */
    public static ProgramInterface planPreparedPost(String source, PostTargetPlan targetPlan) {
        if (targetPlan == null) {
            throw new IllegalArgumentException("post target plan is required");
        }
        return InterfacePlanner.planInternal(source, Stage.POST, true, true);
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
            return custom.availability() == Availability.REJECTED || !custom.accepts(glslType) ? null : custom;
        }
        return unsetDescriptor(name, glslType);
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
     * Names Iris 1.21.1 serves that Chimera does not yet: per-draw state, values that need Iris's
     * ID maps or fog-mode plumbing, and the matrices Iris's core-profile transformers inject. A
     * program reading one must not run on a zero Iris would never give it.
     */
    private static final Set<String> IRIS_UNSERVED = Set.of(
            "alphaTestRef", "blendFunc", "gtextureSize", "currentSelectedBlockId", "currentSelectedBlockPos",
            "fogDensity", "fogMode", "fogShape",
            "cloudTime", "constantMood",
            "modelViewMatrix", "modelViewMatrixInverse", "projectionMatrix", "projectionMatrixInverse",
            "normalMatrix", "textureMatrix", "chunkOffset");

    /** Whether Iris itself gives this name a value: the catalog, the unserved list, Iris internals. */
    static boolean irisServes(String name) {
        return UNIFORM_SPECS.containsKey(name) && !HOST_ONLY.contains(name)
                || IRIS_UNSERVED.contains(name) || name.startsWith("iris_") || name.startsWith("u_");
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

    /** True when {@code name} appears as a whole word at least twice: a declaration and a use. */
    private static boolean isReferenced(String source, String name) {
        return wholeWordCount(source, name, 2) >= 2;
    }

    /**
     * How often {@code name} appears as a whole word, counting no further than {@code limit}, word
     * edges judged as Java's {@code \b} judges them. Planning asks this for every catalog uniform in
     * every program, so it searches with {@code indexOf} rather than compiling a pattern each time.
     */
    private static int wholeWordCount(String source, String name, int limit) {
        if (name.isEmpty() || !wordCharacter(name.charAt(0)) || !wordCharacter(name.charAt(name.length() - 1))) {
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(source);
            int found = 0;
            while (found < limit && matcher.find()) found++;
            return found;
        }
        int found = 0;
        int at = source.indexOf(name);
        while (at >= 0 && found < limit) {
            int end = at + name.length();
            boolean whole = (at == 0 || !wordCharacter(source.charAt(at - 1)))
                    && (end == source.length() || !wordCharacter(source.charAt(end)));
            if (whole) found++;
            at = source.indexOf(name, whole ? end : at + 1);
        }
        return found;
    }

    /** Java's {@code \b} word characters since JDK 19: ASCII letters, digits and '_'. */
    private static boolean wordCharacter(char value) {
        return value == '_' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    /** Iris EntityPatcher names served per vertex from EntityIds, not from the pack UBO. */
    static boolean isEntityIdentity(String name) {
        return EntityVertexBridge.ENTITY_ID_NAMES.contains(name);
    }

    /** Accounts for stage-local legacy shadow matrix aliases during planning. */
    static boolean isReferencedForStage(String source, String name, Stage stage) {
        if (isReferenced(source, name)) {
            return true;
        }
        if ((stage == Stage.GEOMETRY || stage == Stage.TRANSLUCENT) && name.equals("gbufferModelView")) {
            return usesLegacyModelView(source);
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

    static boolean usesLegacyModelView(String source) {
        return containsIdentifier(source, "gl_ModelViewMatrix") || containsIdentifier(source, "gl_NormalMatrix")
                || containsIdentifier(source, "gl_ProjectionMatrix");
    }

    static boolean containsIdentifier(String source, String name) {
        return wholeWordCount(source, name, 1) >= 1;
    }

    static boolean isSamplerUnused(String source, String name) {
        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(source);
        return usage.successful() && !usage.liveSamplers().contains(name);
    }

    /** Whether {@code source} declares {@code name}, alone or later in a declarator list ({@code vec3 a, name}). */
    static boolean isLocallyDeclared(String source, String name) {
        return Pattern.compile("\\b(?:const\\s+)?[A-Za-z_]\\w*\\s+(?:[A-Za-z_]\\w*\\s*(?:\\[[^\\]]*\\]\\s*)?,\\s*)*"
                + Pattern.quote(name) + "\\b").matcher(source).find();
    }

    static boolean isHostAlias(String name) {
        return name.equals("shadowtex1")
                || name.equals("shadowcolor0") || name.equals("shadowcolor1");
    }

    record Variable(String name, boolean array) {}

    /** Returns all declared sampler names, including unsupported names, sorted. */
    public static List<String> scanDeclaredSamplerNames(String source) {
        String stripped = stripComments(source == null ? "" : source);
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            if (isSamplerType(matcher.group(1))) {
                for (Variable variable : InterfacePlanner.parseVariables(matcher.group(2))) {
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

    /** Removes only accepted ordinary declarations, keeping sampler declarations for binding rewrite. */
    public static String removeUniformDeclarations(String source, ProgramInterface plan) {
        String result = source == null ? "" : source;
        Set<String> executable = plan.executableUniforms().stream()
                .map(UniformDeclaration::name).collect(Collectors.toSet());
        Set<String> unusedSamplers = new HashSet<>();
        for (String deviation : plan.deviations()) {
            if (deviation.startsWith("UNIFORM_DECLARATION_UNUSED:")) {
                executable.add(deviation.substring("UNIFORM_DECLARATION_UNUSED:".length()));
            } else if (deviation.startsWith("SAMPLER_DECLARATION_UNUSED:")) {
                unusedSamplers.add(deviation.substring("SAMPLER_DECLARATION_UNUSED:".length()));
            }
        }
        Matcher matcher = UNIFORM_DECLARATION.matcher(maskComments(result));
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            // Samplers stay declared for the binding rewrite unless the pack never reads them.
            Set<String> removed = isSamplerType(matcher.group(1)) ? unusedSamplers : executable;
            List<Variable> variables = InterfacePlanner.parseVariables(matcher.group(2));
            if (!variables.isEmpty()) {
                List<Variable> retained = variables.stream()
                        .filter(variable -> !removed.contains(variable.name()))
                        .toList();
                if (retained.size() != variables.size()) {
                    out.append(result, last, matcher.start());
                    if (!retained.isEmpty()) {
                        out.append("uniform ").append(matcher.group(1)).append(' ')
                                .append(retained.stream().map(Variable::name)
                                        .collect(Collectors.joining(", ")))
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

    static boolean isSamplerType(String type) {
        return type.startsWith("sampler") || type.startsWith("isampler") || type.startsWith("usampler");
    }

    static boolean isStorageImageType(String type) {
        return "image3D".equals(type) || "iimage3D".equals(type) || "uimage3D".equals(type);
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

    /**
     * Blanks comment text but keeps every offset, so a declaration match on the
     * result can edit the original source. A comment such as "a corresponding
     * uniform in shaders.properties" must never start a declaration match.
     */
    private static String maskComments(String source) {
        StringBuilder masked = new StringBuilder(source.length());
        for (GlslLexer.Token token : GlslLexer.lex(source)) {
            String text = token.text();
            if (token.kind() == GlslLexer.Kind.TRIVIA && (text.startsWith("//") || text.startsWith("/*"))) {
                for (int index = 0; index < text.length(); index++) {
                    masked.append(text.charAt(index) == '\n' ? '\n' : ' ');
                }
            } else {
                masked.append(text);
            }
        }
        return masked.toString();
    }
}
