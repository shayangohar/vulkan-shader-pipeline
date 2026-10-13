package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * Plans one program's interface: scans its uniform and sampler declarations, resolves each uniform
 * against the catalog and the pack's own values, and binds each sampler to its selector slot.
 */
final class InterfacePlanner {
    private InterfacePlanner() {}

    static UniformRegistry.ProgramInterface planInternal(
            String source, UniformRegistry.Stage stage, boolean targetedPost, boolean allowUnusedDeclarations) {
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations, Map.of());
    }

    static UniformRegistry.ProgramInterface planInternal(
            String source,
            UniformRegistry.Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformRegistry.UniformDescriptor> customDescriptors
    ) {
        return planInternal(source, stage, targetedPost, allowUnusedDeclarations,
                customDescriptors, Map.of());
    }

    static UniformRegistry.ProgramInterface planInternal(
            String source,
            UniformRegistry.Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, UniformRegistry.UniformDescriptor> customDescriptors,
            Map<String, Integer> customSamplerSlots
    ) {
        String stripped = stripComments(source == null ? "" : source);
        Set<String> deviations = new TreeSet<>();
        Declarations declared = scanDeclarations(stripped, stage, allowUnusedDeclarations, deviations);
        for (UniformRegistry.UniformDeclaration declaration : declared.uniforms().values()) {
            planUniform(declaration, declared, stripped, stage, allowUnusedDeclarations, customDescriptors,
                    deviations);
        }
        List<UniformRegistry.SamplerBinding> bindings = bindSamplers(stripped, stage, targetedPost,
                allowUnusedDeclarations,
                declared.samplers(), customSamplerSlots, deviations);
        return new UniformRegistry.ProgramInterface(stage, List.copyOf(declared.uniforms().values()), bindings,
                List.copyOf(deviations));
    }

    /** A program's declared values and samplers, before either is matched against the catalog. */
    record Declarations(
            Map<String, UniformRegistry.UniformDeclaration> uniforms,
            Set<String> implicit,
            Set<String> conflicts,
            Map<String, String> samplers
    ) {}

    static Declarations scanDeclarations(
            String stripped, UniformRegistry.Stage stage, boolean allowUnusedDeclarations, Set<String> deviations) {
        Map<String, UniformRegistry.UniformDeclaration> declarations = new TreeMap<>();
        Set<String> implicitDeclarations = new TreeSet<>();
        Set<String> conflicts = new TreeSet<>();
        Map<String, String> samplerNames = new TreeMap<>();
        Matcher matcher = UniformRegistry.UNIFORM_DECLARATION.matcher(stripped);
        while (matcher.find()) {
            String type = matcher.group(1);
            for (UniformRegistry.Variable variable : parseVariables(matcher.group(2))) {
                if (UniformRegistry.isSamplerType(type)) {
                    samplerNames.putIfAbsent(variable.name(), type);
                    continue;
                }
                // Writable 3D images belong to the advanced-resource plan, not to the ordinary
                // generated UBO. Keeping them out of this catalog lets the graphics image bridge
                // assign one explicit storage descriptor later.
                if (UniformRegistry.isStorageImageType(type)) {
                    deviations.add("ADVANCED_IMAGE_DECLARATION:" + variable.name());
                    continue;
                }
                // Pack authors write bool for Iris's isElytraFlying; the catalog field is int, so a
                // bool declaration of that name plans as the live int field rather than failing.
                String declaredType = variable.name().equals("isElytraFlying") && type.equals("bool")
                        ? "int" : type;
                String effectiveType = variable.array() ? declaredType + "[]" : declaredType;
                UniformRegistry.UniformDeclaration declaration = new UniformRegistry.UniformDeclaration(variable.name(),
                        effectiveType);
                UniformRegistry.UniformDeclaration previous = declarations.putIfAbsent(variable.name(), declaration);
                if (previous != null && !previous.glslType().equals(declaredType)) {
                    conflicts.add(variable.name());
                    declarations.put(variable.name(), declaration);
                }
            }
        }
        Matcher blockMatcher = UniformRegistry.UNIFORM_BLOCK.matcher(stripped);
        while (blockMatcher.find()) {
            deviations.add("UNIFORM_TYPE_UNSUPPORTED:" + blockMatcher.group(1));
        }
        // Real packs sometimes expose standard Iris uniforms through a conditional include that is
        // absent from the prepared snapshot. If the name is in the canonical catalog and has exactly
        // one accepted type, synthesize the declaration from that catalog entry. This is
        // deliberately table-driven and does not infer arbitrary GLSL types.
        if (allowUnusedDeclarations) {
            Set<String> referencedValues = referencedValues(stripped, stage);
            for (UniformRegistry.UniformDescriptor descriptor : UniformRegistry.UNIFORM_SPECS.values()) {
                if (descriptor.acceptedTypes().size() != 1
                        || declarations.containsKey(descriptor.name())
                        || !referencedValues.contains(descriptor.name())
                        || UniformRegistry.isLocallyDeclared(stripped, descriptor.name())) {
                    continue;
                }
                declarations.put(descriptor.name(), new UniformRegistry.UniformDeclaration(
                        descriptor.name(), descriptor.acceptedTypes().get(0)));
                implicitDeclarations.add(descriptor.name());
                deviations.add("UNIFORM_IMPLICIT_DECLARATION:" + descriptor.name());
            }
        }
        // The terrain bridge serves the legacy matrix built-ins from gbufferModelView
        // (LegacyGlslConverter.modernTerrainInputReplacements); a pack that only writes
        // gl_ModelViewMatrix never declares it.
        if ((stage == UniformRegistry.Stage.GEOMETRY || stage == UniformRegistry.Stage.TRANSLUCENT)
                && UniformRegistry.usesLegacyModelView(stripped)
                && !declarations.containsKey("gbufferModelView")
                && !UniformRegistry.isLocallyDeclared(stripped, "gbufferModelView")) {
            declarations.put("gbufferModelView", new UniformRegistry.UniformDeclaration("gbufferModelView", "mat4"));
            implicitDeclarations.add("gbufferModelView");
            deviations.add("UNIFORM_IMPLICIT_DECLARATION:gbufferModelView");
        }
        return new Declarations(declarations, implicitDeclarations, conflicts, samplerNames);
    }

    /** The catalog values a prepared source reads, with the shadow pass's legacy matrix names. */
    static Set<String> referencedValues(String stripped, UniformRegistry.Stage stage) {
        Set<String> referenced = new TreeSet<>(
                GlslResourceUsage.referencedValueIdentifiers(stripped, UniformRegistry.UNIFORM_SPECS.keySet()));
        if (stage == UniformRegistry.Stage.SHADOW) {
            if (UniformRegistry.containsIdentifier(stripped, "gl_ModelViewMatrix")) {
                referenced.add("shadowModelView");
            }
            if (UniformRegistry.containsIdentifier(stripped, "gl_NormalMatrix")) {
                referenced.add("shadowModelViewInverse");
            }
            if (UniformRegistry.containsIdentifier(stripped, "gl_ProjectionMatrix")) {
                referenced.add("shadowProjection");
            }
        }
        return referenced;
    }

    /** The descriptor a declared name plans against: a pack value of a VulkanMod host name is the pack's. */
    static UniformRegistry.UniformDescriptor descriptorFor(String name, Map<String,
            UniformRegistry.UniformDescriptor> customDescriptors) {
        UniformRegistry.UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
        UniformRegistry.UniformDescriptor spec = UniformRegistry.HOST_ONLY.contains(name) && custom != null ? custom
                : UniformRegistry.UNIFORM_SPECS.get(name);
        return spec == null ? custom : spec;
    }

    /**
     * Records how one declared value plans: LIVE_UNIFORM_BRIDGE when the host serves it, otherwise
     * the deviation naming why not. A declaration a prepared source never reads is only unused.
     */
    static void planUniform(
            UniformRegistry.UniformDeclaration declaration,
            Declarations declared,
            String stripped,
            UniformRegistry.Stage stage,
            boolean allowUnusedDeclarations,
            Map<String, UniformRegistry.UniformDescriptor> customDescriptors,
            Set<String> deviations
    ) {
        String name = declaration.name();
        String type = declaration.glslType();
        if (declared.conflicts().contains(name)) {
            deviations.add("UNIFORM_CONFLICT:" + name);
        }
        boolean unused = allowUnusedDeclarations && !UniformRegistry.isReferencedForStage(stripped, name, stage);
        if (stage.perVertexEntityIdentity() && UniformRegistry.isEntityIdentity(name)) {
            deviations.add(!type.equals("int") && !type.equals("float") ? "ENTITY_ID_UNSUPPORTED:" + type
                    : unused ? "UNIFORM_DECLARATION_UNUSED:" + name : "ENTITY_ID_VERTEX_DATA");
            return;
        }
        UniformRegistry.UniformDescriptor custom = customDescriptors == null ? null : customDescriptors.get(name);
        UniformRegistry.UniformDescriptor spec = descriptorFor(name, customDescriptors);
        if (custom != null && custom.availability() == UniformRegistry.Availability.REJECTED) {
            // The pack wrote this declaration and Chimera could not honour it. A program that reads
            // the name must not run with a substitute value, so it fails closed here.
            deviations.add(unused ? "UNIFORM_DECLARATION_UNUSED:" + name : "CUSTOM_VALUE_REJECTED:" + name);
            return;
        }
        if (!UniformRegistry.SUPPORTED_TYPES.contains(type) || !compatibleType(name, type, spec)) {
            deviations.add(unused ? "UNIFORM_DECLARATION_UNUSED:" + name : "UNIFORM_TYPE_UNSUPPORTED:" + name);
            return;
        }
        if (spec == null) {
            spec = UniformRegistry.unsetDescriptor(name, type);
            if (spec != null) {
                deviations.add("UNIFORM_UNSET_ZERO:" + name);
            }
        }
        if (spec == null) {
            deviations.add(unused ? "UNIFORM_DECLARATION_UNUSED:" + name : "UNIFORM_NAME_UNSUPPORTED:" + name);
            return;
        }
        if (unused && !declared.implicit().contains(name)) {
            deviations.add("UNIFORM_DECLARATION_UNUSED:" + name);
            return;
        }
        deviations.add("LIVE_UNIFORM_BRIDGE");
        if (customDescriptors != null && customDescriptors.containsKey(name)) {
            deviations.add("CUSTOM_UNIFORM_BRIDGE:" + name);
        }
        if (spec.availability() == UniformRegistry.Availability.DEFAULTED) {
            deviations.add("UNIFORM_DEFAULTED:" + name);
        }
    }

    static List<UniformRegistry.SamplerBinding> bindSamplers(
            String stripped,
            UniformRegistry.Stage stage,
            boolean targetedPost,
            boolean allowUnusedDeclarations,
            Map<String, String> samplerNames,
            Map<String, Integer> customSamplerSlots,
            Set<String> deviations
    ) {
        boolean waterShadow = samplerNames.containsKey("watershadow");
        Map<Integer, String> samplerResources = new TreeMap<>();
        List<UniformRegistry.SamplerBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, String> sampler : samplerNames.entrySet()) {
            String name = sampler.getKey();
            String type = sampler.getValue();
            if (allowUnusedDeclarations && UniformRegistry.isSamplerUnused(stripped, name)) {
                deviations.add("SAMPLER_DECLARATION_UNUSED:" + name);
                continue;
            }
            // Iris gives the legacy shadow names a per-program meaning; look them up by it.
            String lookup = PackResourcePlan.isProgramShadowAlias(name)
                    ? PackResourcePlan.programResource(name, waterShadow) : name;
            Integer slot = samplerSlot(name, lookup, stage, customSamplerSlots, deviations);
            boolean mappedType = type.equals("sampler2D") || type.equals("sampler2DShadow")
                    || ((type.equals("sampler3D") || type.equals("isampler3D") || type.equals("usampler3D"))
                    && customSamplerSlots != null && customSamplerSlots.containsKey(name));
            if (!mappedType || slot == null) {
                deviations.add(unmappedSamplerDeviation(name, stage));
                continue;
            }
            bindings.add(new UniformRegistry.SamplerBinding(name, slot, type));
            String resource = stage == UniformRegistry.Stage.POST && name.equals("tex") && customSamplerSlots != null
                    && customSamplerSlots.containsKey(UniformRegistry.PACK_ATLAS_SAMPLER)
                    ? UniformRegistry.PACK_ATLAS_SAMPLER : UniformRegistry.samplerResource(lookup);
            String previousResource = samplerResources.putIfAbsent(slot, resource);
            if (previousResource != null && !previousResource.equals(resource)) {
                deviations.add("SAMPLER_SLOT_CONFLICT:" + slot);
            }
            if (stage == UniformRegistry.Stage.POST) {
                addPostSamplerDeviations(name, targetedPost, deviations);
            }
        }
        return bindings;
    }

    static Map<String, Integer> stageSlots(UniformRegistry.Stage stage) {
        return switch (stage) {
            case POST -> UniformRegistry.NAME_TO_SLOT;
            case GEOMETRY, SKY, CLOUD -> UniformRegistry.GEOMETRY_NAME_TO_SLOT;
            case SHADOW, SHADOW_ENTITY -> UniformRegistry.SHADOW_NAME_TO_SLOT;
            case TRANSLUCENT -> UniformRegistry.TRANSLUCENT_NAME_TO_SLOT;
            case ENTITY, BLOCK, HAND, PARTICLE -> UniformRegistry.ENTITY_NAME_TO_SLOT;
        };
    }

    /** The slot a declared sampler binds to: the stage's map, then the pack's own textures. */
    static Integer samplerSlot(
            String name, String lookup, UniformRegistry.Stage stage, Map<String, Integer> customSamplerSlots,
                    Set<String> deviations) {
        Integer slot = stageSlots(stage).get(lookup);
        if (stage == UniformRegistry.Stage.POST && slot == null) {
            slot = UniformRegistry.extendedPostColorSlot(name);
        }
        if (stage != UniformRegistry.Stage.POST && !stage.shadowPass() && slot == null) {
            slot = UniformRegistry.gbufferColorInputSlot(name);
        }
        if (stage == UniformRegistry.Stage.POST && slot == null && name.equals("tex") && customSamplerSlots != null) {
            slot = customSamplerSlots.get(UniformRegistry.PACK_ATLAS_SAMPLER);
            if (slot != null) {
                deviations.add("SAMPLER_ALIAS_TO_PACK_RESOURCE:tex:textureAtlas");
            }
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

    static String unmappedSamplerDeviation(String name, UniformRegistry.Stage stage) {
        if (stage.shadowPass() && name.startsWith("shadowcolor")) {
            return "SHADOW_COLOR_INPUT_UNSUPPORTED";
        }
        if (stage == UniformRegistry.Stage.TRANSLUCENT
                && (name.startsWith("depthtex") || name.startsWith("shadowcolor"))) {
            return "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED";
        }
        if (stage == UniformRegistry.Stage.ENTITY || stage == UniformRegistry.Stage.BLOCK
                || stage == UniformRegistry.Stage.HAND || stage == UniformRegistry.Stage.PARTICLE) {
            return "ENTITY_SAMPLER_UNSUPPORTED:" + name;
        }
        if (stage == UniformRegistry.Stage.TRANSLUCENT) {
            return "TRANSLUCENT_SAMPLER_UNSUPPORTED:" + name;
        }
        return stage.shadowPass() ? "SHADOW_SAMPLER_UNSUPPORTED:" + name : "SAMPLER_NOT_MAPPED:" + name;
    }

    static void addPostSamplerDeviations(String name, boolean targetedPost, Set<String> deviations) {
        if (UniformRegistry.isHostAlias(name)) {
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

    static List<UniformRegistry.Variable> parseVariables(String body) {
        List<UniformRegistry.Variable> result = new ArrayList<>();
        for (String part : body.split(",")) {
            String value = part.trim();
            int equals = value.indexOf('=');
            if (equals >= 0) {
                value = value.substring(0, equals).trim();
            }
            Matcher name = Pattern.compile("^([A-Za-z_]\\w*)(\\s*\\[[^]]*\\])?$")
                    .matcher(value);
            if (name.matches()) {
                result.add(new UniformRegistry.Variable(name.group(1), name.group(2) != null));
            }
        }
        return result;
    }

    static boolean compatibleType(String name, String type, UniformRegistry.UniformDescriptor spec) {
        if (spec == null) {
            return UniformRegistry.SUPPORTED_TYPES.contains(type);
        }
        return spec.accepts(type);
    }
}
