package net.chimera.shaderpack;

import net.chimera.shaderpack.UniformRegistry.Availability;
import net.chimera.shaderpack.UniformRegistry.ProgramInterface;
import net.chimera.shaderpack.UniformRegistry.ProgramInterfacePlan;
import net.chimera.shaderpack.UniformRegistry.SamplerBinding;
import net.chimera.shaderpack.UniformRegistry.Stage;
import net.chimera.shaderpack.UniformRegistry.UniformDeclaration;
import net.chimera.shaderpack.UniformRegistry.UniformDescriptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * Plans a program's interface from its sources, one stage or the whole program: scans its uniform
 * and sampler declarations, resolves each uniform against the catalog (UniformRegistry) and the
 * pack's own values, binds each sampler to its selector slot, and removes the declarations the
 * generated uniform block replaces.
 */
public final class InterfacePlanner {
    /** Iris binds integer targets (IrisColorFormats) to isampler2D and usampler2D declarations. */
    private static final Set<String> PLANAR_SAMPLER_TYPES = Set.of("sampler2D", "sampler2DShadow",
            "isampler2D", "usampler2D");
    /** Samplers of 1D and 3D textures, which only the pack's own textures and images serve. */
    private static final Set<String> PACK_ONLY_SAMPLER_TYPES = Set.of("sampler3D", "isampler3D", "usampler3D",
            "sampler1D", "isampler1D", "usampler1D");
    static final Pattern UNIFORM_DECLARATION = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s+([^;{}]+);");
    static final Pattern UNIFORM_BLOCK = Pattern.compile(
            "\\buniform\\s+([A-Za-z_]\\w*)\\s*\\{");

    private InterfacePlanner() {}

    /** Builds one program-level union from its prepared stage sources. */
    public static ProgramInterfacePlan planProgram(
            String fragmentSource,
            String vertexSource,
            Stage stage,
            PostTargetPlan targetPlan,
            boolean prepared
    ) {
        return planProgram(fragmentSource, vertexSource, null, stage, targetPlan, prepared, Map.of(), Map.of());
    }

    /**
     * A program interface over every stage the program runs, with pack-owned sampler slots: the
     * uniforms and samplers of each stage it runs between its vertex and fragment stages
     * ({@code betweenStages}, by stage name; null for none) join the program's, and project
     * answers for each of them.
     */
    public static ProgramInterfacePlan planProgram(
            String fragmentSource,
            String vertexSource,
            Map<String, String> betweenStages,
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
        Map<String, String> otherStages = new LinkedHashMap<>();
        if (vertexSource != null) otherStages.put("vertex", vertexSource);
        if (betweenStages != null) otherStages.putAll(betweenStages);
        otherStages.forEach((name, source) -> {
            ProgramInterface plan = planInternal(source, effectiveStage, false, prepared,
                    customDescriptors, customSamplerSlots);
            stagePlans.put(name, plan);
            uniforms.addAll(plan.uniforms());
            samplers.addAll(plan.samplers());
            deviations.addAll(plan.deviations());
        });
        return new ProgramInterfacePlan(stagePlans, uniforms, samplers, deviations);
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
     * Plans a post program whose colortex bindings are backed by post target
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

    static ProgramInterface planInternal(
            String source, Stage stage, boolean targetedPost, boolean allowUnusedDeclarations) {
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations, Map.of());
    }

    static ProgramInterface planInternal(
            String source,
            Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformDescriptor> customDescriptors
    ) {
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations,
                customDescriptors, Map.of());
    }

    static ProgramInterface planInternal(
            String source,
            Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformDescriptor> customDescriptors,
            Map<String, Integer> customSamplerSlots
    ) {
        String stripped = stripComments(source == null ? "" : source);
        Set<String> deviations = new TreeSet<>();
        Declarations declared = scanDeclarations(stripped, stage, allowUnusedDeclarations, deviations);
        for (UniformDeclaration declaration : declared.uniforms().values()) {
            planUniform(declaration, declared, stripped, stage, allowUnusedDeclarations, customDescriptors,
                    deviations);
        }
        List<SamplerBinding> bindings = bindSamplers(stripped, stage, targetedPost,
                allowUnusedDeclarations,
                declared.samplers(), customSamplerSlots, deviations);
        return new ProgramInterface(stage, List.copyOf(declared.uniforms().values()), bindings,
                List.copyOf(deviations));
    }

    /** A program's declared values and samplers, before either is matched against the catalog. */
    record Declarations(
            Map<String, UniformDeclaration> uniforms,
            Set<String> implicit,
            Set<String> conflicts,
            Map<String, String> samplers
    ) {}

    static Declarations scanDeclarations(
            String stripped, Stage stage, boolean allowUnusedDeclarations, Set<String> deviations) {
        Declarations declared = new Declarations(new TreeMap<>(), new TreeSet<>(), new TreeSet<>(), new TreeMap<>());
        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (Variable variable : parseVariables(matcher.group(2))) {
                declare(type, variable, declared, deviations);
            }
        }
        Matcher blockMatcher = UNIFORM_BLOCK.matcher(stripped);
        while (blockMatcher.find()) {
            deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + blockMatcher.group(1));
        }
        // Real packs sometimes expose standard Iris uniforms through a conditional include that is
        // absent from the prepared snapshot. If the name is in the canonical catalog and has exactly
        // one accepted type, synthesize the declaration from that catalog entry. This is
        // deliberately table-driven and does not infer arbitrary GLSL types.
        if (allowUnusedDeclarations) {
            declareReferencedCatalogValues(stripped, stage, declared, deviations);
        }
        // The terrain bridge serves the legacy matrix built-ins from gbufferModelView
        // (LegacyGlslConverter.modernTerrainInputReplacements); a pack that only writes
        // gl_ModelViewMatrix never declares it.
        if ((stage == Stage.GEOMETRY || stage == Stage.TRANSLUCENT)
                && usesLegacyModelView(stripped)
                && !declared.uniforms().containsKey("gbufferModelView")
                && !isLocallyDeclared(stripped, "gbufferModelView")) {
            declareImplicit("gbufferModelView", "mat4", declared, deviations);
        }
        // The shadow bridge serves them from the shadow matrices
        // (ShadowStageBuiltins.matrixReplacements): RenderPearl's shadow vertex
        // reads modelViewMatrix, which Iris's core profile makes gl_ModelViewMatrix.
        for (String name : shadowMatrixValues(stripped, stage)) {
            if (declared.uniforms().containsKey(name) || isLocallyDeclared(stripped, name)) continue;
            declareImplicit(name, "mat4", declared, deviations);
        }
        return declared;
    }

    /** Records one declared variable as a sampler, an advanced image, or a value whose type may conflict. */
    private static void declare(
            String type, Variable variable, Declarations declared, Set<String> deviations) {
        if (UniformRegistry.isSamplerType(type)) {
            declared.samplers().putIfAbsent(variable.name(), type);
            return;
        }
        // Writable 3D images belong to the advanced-resource plan, not to the ordinary
        // generated UBO. Keeping them out of this catalog lets the graphics image bridge
        // assign one explicit storage descriptor later.
        if (UniformRegistry.isStorageImageType(type)) {
            deviations.add("ADVANCED_IMAGE_DECLARATION:" + variable.name());
            return;
        }
        String effectiveType = variable.array() ? type + "[]" : type;
        UniformDeclaration declaration = new UniformDeclaration(variable.name(),
                effectiveType);
        UniformDeclaration previous = declared.uniforms().putIfAbsent(variable.name(), declaration);
        if (previous != null && !previous.glslType().equals(type)) {
            declared.conflicts().add(variable.name());
            declared.uniforms().put(variable.name(), declaration);
        }
    }

    /** Declares each single-typed catalog value the source reads without declaring it. */
    private static void declareReferencedCatalogValues(
            String stripped, Stage stage, Declarations declared, Set<String> deviations) {
        Set<String> referencedValues = referencedValues(stripped, stage);
        for (UniformDescriptor descriptor : UniformRegistry.UNIFORM_SPECS.values()) {
            if (descriptor.acceptedTypes().size() != 1
                    || declared.uniforms().containsKey(descriptor.name())
                    || !referencedValues.contains(descriptor.name())
                    || isLocallyDeclared(stripped, descriptor.name())) {
                continue;
            }
            declareImplicit(descriptor.name(), descriptor.acceptedTypes().get(0), declared, deviations);
        }
    }

    private static void declareImplicit(String name, String type, Declarations declared, Set<String> deviations) {
        declared.uniforms().put(name, new UniformDeclaration(name, type));
        declared.implicit().add(name);
        deviations.add("UNIFORM_IMPLICIT_DECLARATION:" + name);
    }

    /** The catalog values a prepared source reads, with the shadow pass's legacy matrix names. */
    static Set<String> referencedValues(String stripped, Stage stage) {
        Set<String> referenced = new TreeSet<>(
                GlslResourceUsage.referencedValueIdentifiers(stripped, UniformRegistry.UNIFORM_SPECS.keySet()));
        referenced.addAll(shadowMatrixValues(stripped, stage));
        return referenced;
    }

    /** The shadow matrices that stand for the legacy matrix built-ins a shadow-pass source reads. */
    private static Set<String> shadowMatrixValues(String stripped, Stage stage) {
        Set<String> values = new TreeSet<>();
        if (stage != Stage.SHADOW) return values;
        if (containsIdentifier(stripped, "gl_ModelViewMatrix")) {
            values.add("shadowModelView");
        }
        if (containsIdentifier(stripped, "gl_NormalMatrix")) {
            values.add("shadowModelViewInverse");
        }
        if (containsIdentifier(stripped, "gl_ProjectionMatrix")) {
            values.add("shadowProjection");
        }
        return values;
    }

    /** The descriptor a declared name plans against: a pack value of a VulkanMod host name is the pack's. */
    static UniformDescriptor descriptorFor(String name, Map<String,
            UniformDescriptor> customDescriptors) {
        UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
        UniformDescriptor spec = UniformRegistry.HOST_ONLY.contains(name) && custom != null ? custom
                : UniformRegistry.UNIFORM_SPECS.get(name);
        return spec == null ? custom : spec;
    }

    /**
     * Records how one declared value plans: LIVE_UNIFORM_BRIDGE when the host serves it, otherwise
     * the deviation naming why not. A declaration a prepared source never reads is only unused.
     */
    static void planUniform(
            UniformDeclaration declaration,
            Declarations declared,
            String stripped,
            Stage stage,
            boolean allowUnusedDeclarations,
            Map<String, UniformDescriptor> customDescriptors,
            Set<String> deviations
    ) {
        String name = declaration.name();
        String type = declaration.glslType();
        if (declared.conflicts().contains(name)) {
            deviations.add("UNIFORM_CONFLICT:" + name);
        }
        boolean unused = allowUnusedDeclarations && !isReferencedForStage(stripped, name, stage);
        if (stage.perVertexEntityIdentity() && UniformRegistry.isEntityIdentity(name)) {
            deviations.add(entityIdentityDeviation(name, type, unused));
            return;
        }
        UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
        UniformDescriptor spec = descriptorFor(name, customDescriptors);
        String refusal = refusal(name, type, custom, stage);
        if (refusal != null) {
            deviations.add(unusedOr(name, unused, refusal));
            return;
        }
        spec = typed(name, type, spec, unused, deviations);
        if (spec == null) {
            spec = UniformRegistry.unsetDescriptor(name, type);
            if (spec != null) {
                deviations.add("UNIFORM_UNSET_ZERO:" + name);
            }
        }
        if (spec == null) {
            deviations.add(unusedOr(name, unused, "UNIFORM_NAME_UNSUPPORTED:" + name));
            return;
        }
        if (unused && !declared.implicit().contains(name)) {
            deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
            return;
        }
        addLiveBridge(name, spec, customDescriptors, deviations);
    }

    /**
     * Why a declaration keeps its program from running, or null. A custom value the pack wrote and
     * Chimera could not honour must not run with a substitute, so it fails closed; so do a type the
     * uniform block cannot carry and a name this stage cannot read (UniformRegistry.unservedIn).
     */
    private static String refusal(String name, String type, UniformDescriptor custom,
                                  Stage stage) {
        if (custom != null && custom.availability() == Availability.REJECTED) {
            return "CUSTOM_VALUE_REJECTED:" + name;
        }
        if (!UniformRegistry.SUPPORTED_TYPES.contains(type)) return "UNIFORM_TYPE_UNSUPPORTED:" + name;
        return custom == null && UniformRegistry.unservedIn(name, stage) ? "UNIFORM_NAME_UNSUPPORTED:" + name : null;
    }

    /** Iris disables a value the program declares with another type, and it reads zero (KNOW-732). */
    private static UniformDescriptor typed(String name, String type,
            UniformDescriptor spec, boolean unused, Set<String> deviations) {
        if (compatibleType(name, type, spec)) return spec;
        if (!unused) deviations.add("UNIFORM_TYPE_MISMATCH_ZERO:" + name);
        return UniformRegistry.disabledDescriptor(name, type);
    }

    /** A per-vertex entity id plans as vertex data when it is an int or a float. */
    private static String entityIdentityDeviation(String name, String type, boolean unused) {
        if (!type.equals("int") && !type.equals("float")) {
            return "ENTITY_ID_UNSUPPORTED:" + type;
        }
        return unusedOr(name, unused, "ENTITY_ID_VERTEX_DATA");
    }

    /** The deviation for a declaration: only unused when the prepared source never reads it. */
    private static String unusedOr(String name, boolean unused, String deviation) {
        return unused ? "UNIFORM_DECLARATION_UNUSED:" + name : deviation;
    }

    /** Records a value the host serves live, and whether the pack defines it or it is defaulted. */
    private static void addLiveBridge(String name, UniformDescriptor spec,
                                      Map<String, UniformDescriptor> customDescriptors,
                                      Set<String> deviations) {
        deviations.add("LIVE_UNIFORM_BRIDGE");
        if (customDescriptors != null && customDescriptors.containsKey(name)) {
            deviations.add("CUSTOM_UNIFORM_BRIDGE:" + name);
        }
        if (spec.availability() == Availability.DEFAULTED) {
            deviations.add("UNIFORM_DEFAULTED:" + name);
        }
    }

    static List<SamplerBinding> bindSamplers(
            String stripped,
            Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, String> samplerNames,
            Map<String, Integer> customSamplerSlots,
            Set<String> deviations
    ) {
        boolean waterShadow = samplerNames.containsKey("watershadow");
        Map<Integer, String> samplerResources = new TreeMap<>();
        List<SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            String name = sampler.getKey();
            String type = sampler.getValue();
            // A shadow program's unread shadowcolor stays inactive, so the pass copies no target for it.
            boolean shadowColor = stage.shadowPass() && PackResourcePlan.shadowColorIndex(name) >= 0;
            if ((allowUnusedDeclarations || shadowColor) && isSamplerUnused(stripped, name)) {
                deviations.add("SAMPLER_DECLARATION_UNUSED:" + name);
                continue;
            }
            // Iris gives the legacy shadow names a per-program meaning; look them up by it.
            String lookup = PackResourcePlan.isProgramShadowAlias(name)
                    ? PackResourcePlan.programResource(name, waterShadow) : name;
            Integer slot = samplerSlot(name, lookup, stage, customSamplerSlots);
            if (!mappedType(name, type, customSamplerSlots) || slot == null) {
                // A sampler nothing reads is inactive, as GL leaves it: Escheridia's composites
                // declare clouds, normals and shadowtex0HW in a shared header and read none of them.
                deviations.add(isSamplerUnused(stripped, name)
                        ? "SAMPLER_DECLARATION_UNUSED:" + name : unmappedSamplerDeviation(name, stage));
                continue;
            }
            bindings.add(new SamplerBinding(name, slot, type));
            String resource = UniformRegistry.samplerResource(lookup);
            String previousResource = samplerResources.putIfAbsent(slot, resource);
            if (previousResource != null && !previousResource.equals(resource)) {
                deviations.add("SAMPLER_SLOT_CONFLICT:" + slot);
            }
            if (stage == Stage.POST) {
                addPostSamplerDeviations(name, targetedPost, deviations);
            }
        }
        return bindings;
    }

    /** 2D and shadow samplers always bind; 3D samplers bind only to the pack's own textures. */
    private static boolean mappedType(String name, String type, Map<String, Integer> customSamplerSlots) {
        return PLANAR_SAMPLER_TYPES.contains(type)
                || PACK_ONLY_SAMPLER_TYPES.contains(type) && hasCustomSlot(customSamplerSlots, name);
    }

    private static boolean hasCustomSlot(Map<String, Integer> customSamplerSlots, String name) {
        return customSamplerSlots != null && customSamplerSlots.containsKey(name);
    }

    static Map<String, Integer> stageSlots(Stage stage) {
        return switch (stage) {
            case POST -> UniformRegistry.NAME_TO_SLOT;
            case GEOMETRY, SKY, CLOUD -> UniformRegistry.GEOMETRY_NAME_TO_SLOT;
            case SHADOW, SHADOW_ENTITY -> UniformRegistry.SHADOW_NAME_TO_SLOT;
            case TRANSLUCENT -> UniformRegistry.TRANSLUCENT_NAME_TO_SLOT;
            case ENTITY, BLOCK, HAND, PARTICLE -> UniformRegistry.ENTITY_NAME_TO_SLOT;
        };
    }

    /** The slot a declared sampler binds to: the stage's map, the targets' samplers, then the pack's own textures. */
    static Integer samplerSlot(
            String name, String lookup, Stage stage, Map<String, Integer> customSamplerSlots) {
        Integer slot = stageSlots(stage).get(lookup);
        if (slot == null) {
            slot = targetSamplerSlot(name, lookup, stage);
        }
        if (slot == null && customSamplerSlots != null) {
            slot = customSamplerSlots.get(name);
        }
        if (slot == null && UniformRegistry.MATERIAL_NAME_TO_SLOT.containsKey(name)
                && UniformRegistry.MATERIAL_STAGES.contains(stage)) {
            slot = UniformRegistry.MATERIAL_NAME_TO_SLOT.get(name);
        }
        return slot;
    }

    /**
     * The slot of a target sampler the stage's map lacks: the DH depth textures, which every program
     * that has the render targets has with them; a fullscreen pass's smoothed centre depth; and
     * outside the shadow pass the shadow samplers and the further colour targets.
     */
    private static Integer targetSamplerSlot(String name, String lookup, Stage stage) {
        if (PackResourcePlan.isDhDepth(lookup)) return SelectorNamespace.dhDepthSlot(lookup);
        if (stage == Stage.POST && CenterDepthSmooth.SAMPLER.equals(lookup)) {
            return SelectorNamespace.CENTER_DEPTH_SLOT;
        }
        // Iris gives every program each shadowcolor target and the hardware shadow samplers
        // (addShadowSamplers), shadow programs too. A shadow program reads a shadowcolor target as
        // the pass opened (ChimeraShadowMap.snapshotPassReads); the hardware samplers bind outside
        // the shadow pass. PackPlanBuilder refuses those the pack lacks.
        if (stage.shadowPass()) return shadowPassColorSlot(lookup);
        int shadow = SelectorNamespace.shadowSamplerSlot(lookup);
        if (shadow >= 0) return shadow;
        return stage == Stage.POST
                ? UniformRegistry.extendedPostColorSlot(name) : UniformRegistry.gbufferColorInputSlot(name);
    }

    /** A shadowcolorN sampler's slot in the shadow pass; null for any other name. */
    private static Integer shadowPassColorSlot(String lookup) {
        int slot = SelectorNamespace.shadowColorSlot(PackResourcePlan.shadowColorIndex(lookup));
        return slot >= 0 ? slot : null;
    }

    static String unmappedSamplerDeviation(String name, Stage stage) {
        if (stage.shadowPass() && name.startsWith("shadowcolor")) {
            return "SHADOW_COLOR_INPUT_UNSUPPORTED";
        }
        if (stage == Stage.TRANSLUCENT
                && (name.startsWith("depthtex") || name.startsWith("shadowcolor"))) {
            return "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED";
        }
        if (stage == Stage.ENTITY || stage == Stage.BLOCK
                || stage == Stage.HAND || stage == Stage.PARTICLE) {
            return "ENTITY_SAMPLER_UNSUPPORTED:" + name;
        }
        if (stage == Stage.TRANSLUCENT) {
            return "TRANSLUCENT_SAMPLER_UNSUPPORTED:" + name;
        }
        return stage.shadowPass() ? "SHADOW_SAMPLER_UNSUPPORTED:" + name : "SAMPLER_NOT_MAPPED:" + name;
    }

    static void addPostSamplerDeviations(String name, boolean targetedPost, Set<String> deviations) {
        if (isHostAlias(name)) {
            deviations.add("SAMPLER_ALIAS_TO_HOST:" + name);
        }
        if (name.equals("noisetex")) {
            deviations.add("NOISETEX_PACK_RESOURCE");
        }
        // The single-depth report keeps depthtex0 fixed to HDR; the target graph snapshots carry
        // the semantic correction.
        if (name.equals("depthtex0")) {
            deviations.add("DEPTH_INPUT_FIXED_TO_HDR");
        } else if (name.startsWith("depthtex")) {
            deviations.add("DEPTH_INPUT_GRAPH:" + name);
        }
        if (!targetedPost && name.matches("colortex[1-3]")) {
            deviations.add("COLORTEX_ALIAS_TO_SEAM");
        }
    }

    static List<Variable> parseVariables(String body) {
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

    static boolean compatibleType(String name, String type, UniformDescriptor spec) {
        if (spec == null) {
            return UniformRegistry.SUPPORTED_TYPES.contains(type);
        }
        return spec.accepts(type);
    }

    // Source scanning: the declarations a stage makes and reads, judged as GLSL words.

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
                || PackResourcePlan.shadowColorIndex(name) >= 0;
    }

    record Variable(String name, boolean array) {}

    /** Returns all declared sampler names, including unsupported names, sorted. */
    public static List<String> scanDeclaredSamplerNames(String source) {
        String stripped = stripComments(source == null ? "" : source);
        TreeSet<String> names = new TreeSet<>();
        Matcher matcher = UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            if (UniformRegistry.isSamplerType(matcher.group(1))) {
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
        String masked = GlslLexer.maskComments(result);
        Matcher matcher = UNIFORM_DECLARATION.matcher(masked);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            // Samplers stay declared for the binding rewrite unless the pack never reads them.
            Set<String> removed = UniformRegistry.isSamplerType(matcher.group(1)) ? unusedSamplers : executable;
            List<Variable> variables = parseVariables(matcher.group(2));
            if (!variables.isEmpty()) {
                List<Variable> retained = variables.stream()
                        .filter(variable -> !removed.contains(variable.name()))
                        .toList();
                if (retained.size() != variables.size()) {
                    // A declaration removed whole takes its layout qualifier (layout(rgba8) uniform image2D).
                    out.append(result, last, retained.isEmpty()
                            ? Math.max(last, GlslDeclarations.layoutStart(masked, matcher.start())) : matcher.start());
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
}
