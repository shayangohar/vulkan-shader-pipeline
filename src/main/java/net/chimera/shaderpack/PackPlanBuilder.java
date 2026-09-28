package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds the one load-time source, interface, and translation plan. */
public final class PackPlanBuilder {
    private PackPlanBuilder() {}

    public static PackPlan build(
            List<PackProgram> programs,
            PackConfig.PackConfigData config
    ) {
        return build(programs, config, PackEntityIdResolver.empty());
    }

    public static PackPlan build(
            List<PackProgram> programs,
            PackConfig.PackConfigData config,
            PackEntityIdResolver entityIds
    ) {
        return build(programs, config, entityIds, PackResolutionPlan.empty());
    }

    public static PackPlan build(
            List<PackProgram> programs,
            PackConfig.PackConfigData config,
            PackEntityIdResolver entityIds,
            PackResolutionPlan resolution
    ) {
        return build(programs, config, entityIds, resolution,
                PackAdvancedResourcePlan.empty());
    }

    public static PackPlan build(
            List<PackProgram> programs,
            PackConfig.PackConfigData config,
            PackEntityIdResolver entityIds,
            PackResolutionPlan resolution,
            PackAdvancedResourcePlan advancedResources
    ) {
        List<PackProgramPlan> plans = new ArrayList<>();
        if (programs != null) {
            for (PackProgram program : programs) {
                plans.add(build(program, config, resolution, advancedResources));
            }
        }
        return new PackPlan(config, plans, entityIds,
                config == null ? PackSettingsPlan.empty() : config.settings(), resolution,
                PackResourcePlan.empty(), advancedResources);
    }

    public static PackProgramPlan build(
            PackProgram program,
            PackConfig.PackConfigData config
    ) {
        return build(program, config, PackResolutionPlan.empty());
    }

    public static PackProgramPlan build(
            PackProgram program,
            PackConfig.PackConfigData config,
            PackResolutionPlan resolution
    ) {
        return build(program, config, resolution, PackAdvancedResourcePlan.empty());
    }

    public static PackProgramPlan build(
            PackProgram program,
            PackConfig.PackConfigData config,
            PackResolutionPlan resolution,
            PackAdvancedResourcePlan advancedResources
    ) {
        if (program == null) {
            return new PackProgramPlan(null, Map.of(),
                    UniformRegistry.planProgram("", null, UniformRegistry.Stage.POST, null, true),
                    Map.of(), Map.of(), null, null, null, null,
                    List.of("PROGRAM_SOURCE_MISSING"), false);
        }

        Map<String, PreparedShaderSource> stages = preparedStages(program);
        if (stageFor(program.name()) == UniformRegistry.Stage.POST) {
            stages = normalizeModernPostStages(stages);
        } else if (isTerrainMaterialFamily(program.name())) {
            stages = normalizeModernTerrainStages(stages);
        }
        String fragment = source(stages, "fragment", program.executableFragmentSource());
        String vertex = source(stages, "vertex", program.executableVertexSource());
        FamilyAdapterPlan familyAdapter = FamilyAdapterRegistry.forProgram(program.name());
        TerrainMaterialPlan terrainMaterial = TerrainMaterialPlan.forProgram(
                program.name(), vertex, fragment, config);
        // Preserve the M5 report contract: root-level packs are inventoried
        // from their authored declarations, while nested dimension variants
        // use the active preprocessed snapshot.
        boolean prepared = !program.variantFolder().isBlank();
        boolean preparedSnapshot = !program.preparedSources().isEmpty();
        boolean allowUnusedDeclarations = prepared;
        UniformRegistry.Stage stage = stageFor(program.name());
        PostTargetPlan targetPlan = PostTargetPlan.isPostProgramName(program.name())
                ? PostTargetPlan.parse(program.name(), fragment,
                config == null ? Map.of() : config.colortexFormats()).plan()
                : null;
        GeometryOutputPlan geometryOutputPlan = isGeometryOutputFamily(program.name())
                ? GeometryOutputPlan.parse(program.name(), fragment,
                config == null ? Map.of() : config.colortexFormats())
                : null;
        PackAlphaTestPlan alphaTestPlan = PackAlphaTestPlan.forProgram(
                program.name(), config == null ? PackSettingsPlan.empty() : config.settings());
        // Only a program that can execute (entity families need a vertex
        // stage) receives the host alpha test.
        if (FamilyAdapterRegistry.isEntityLike(program.name()) && vertex != null) {
            fragment = alphaTestPlan.injectEntityTest(fragment);
            fragment = EntityOverlayColor.inject(fragment);
        }
        UniformRegistry.ProgramInterfacePlan interfacePlan = UniformRegistry.planProgram(
                fragment, vertex, stage, targetPlan, allowUnusedDeclarations,
                config == null || config.settings() == null
                        ? Map.of() : config.settings().runtimeSettings().customDescriptors(),
                config == null || config.settings() == null
                        ? Map.of() : config.settings().customSamplerSlots());
        Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces = new TreeMap<>();
        if (fragment != null) {
            stageInterfaces.put("fragment", GlslInterfaceScanner.scan(fragment, false));
        }
        if (vertex != null) {
            stageInterfaces.put("vertex", GlslInterfaceScanner.scan(vertex, true));
        }
        boolean pairedPostVertex = stage == UniformRegistry.Stage.POST && vertex != null;
        boolean reconcileStages = vertex != null
                && (stage != UniformRegistry.Stage.POST || pairedPostVertex);
        GlslInterfaceScanner.ProgramMatch stageMatch = !reconcileStages
                ? new GlslInterfaceScanner.ProgramMatch(Map.of(), List.of())
                : GlslInterfaceScanner.match(stageInterfaces.get("vertex"), stageInterfaces.get("fragment"));
        Map<String, Integer> varyingLocations = stage == UniformRegistry.Stage.POST && !pairedPostVertex
                ? postVaryingLocations(stageInterfaces.get("fragment"))
                : stageMatch.locations();

        List<String> deviations = new ArrayList<>(program.preparationDeviations());
        stages.values().forEach(value -> deviations.addAll(value.deviations()));
        deviations.addAll(interfacePlan.deviations());
        stageInterfaces.values().forEach(value -> deviations.addAll(value.deviations()));
        deviations.addAll(stageMatch.deviations());
        deviations.addAll(terrainMaterial.deviations());
        if (targetPlan != null) {
            deviations.addAll(targetPlan.deviations());
        }
        if (geometryOutputPlan != null) {
            deviations.addAll(geometryOutputPlan.deviations());
        }
        deviations.addAll(alphaTestPlan.deviations());

        String convertedFragment = null;
        String convertedVertex = null;
        LegacyGlslConverter.TerrainVaryingLayout vertexLayout = null;
        boolean allowStorageBuffers = advancedResources != null
                && !advancedResources.storageBuffers(program.name()).isEmpty()
                && advancedResources.storageBufferProgramSupported(program.name());
        if (!allowStorageBuffers && advancedResources != null) {
            allowStorageBuffers = advancedResources.buffers().values().stream()
                    .anyMatch(PackAdvancedResourcePlan.BufferSpec::supported);
        }
        boolean executable = isExecutableFamily(program.name())
                && fragment != null
                && (!FamilyAdapterRegistry.isEntityLike(program.name())
                && !FamilyAdapterRegistry.isParticleLike(program.name())
                && !FamilyAdapterRegistry.isWeatherFamily(program.name())
                && !FamilyAdapterRegistry.isSkyFamily(program.name())
                && !FamilyAdapterRegistry.isCloudFamily(program.name()) || vertex != null)
                && stages.values().stream().allMatch(PackPlanBuilder::preparedSuccessfully)
                && interfacePlan.executable()
                && stageInterfaces.values().stream().allMatch(value -> value.deviations().isEmpty())
                && stageMatch.executable()
                && (targetPlan == null || targetPlan.executable())
                && (geometryOutputPlan == null || geometryOutputPlan.executable())
                && alphaTestPlan.valid();
        if (stage == UniformRegistry.Stage.TRANSLUCENT && geometryOutputPlan != null) {
            // A translucent draw samples pack targets as shader-read images
            // while its own outputs are colour attachments; one image cannot
            // be both inside a render pass.
            for (UniformRegistry.SamplerBinding sampler : interfacePlan.effective(stage).samplers()) {
                Integer target = PackResourcePlan.targetIndex(sampler.name());
                if (target != null && geometryOutputPlan.targetSlots().contains(target)) {
                    deviations.add("TRANSLUCENT_TARGET_FEEDBACK:colortex" + target);
                    executable = false;
                }
            }
        }
        if (stage == UniformRegistry.Stage.SHADOW) {
            PostTargetPlan shadowOutputs = PostTargetPlan.parse("shadow", fragment).plan();
            for (String deviation : shadowOutputs.deviations()) {
                deviations.add(deviation.replaceFirst("^POST_", "SHADOW_"));
            }
            for (int target : shadowOutputs.targetSlots()) {
                if (target < 0 || target > 1) {
                    deviations.add("SHADOW_OUTPUT_TARGET_UNSUPPORTED:" + target);
                    executable = false;
                }
            }
            executable &= shadowOutputs.executable();
        }

        boolean hasStorageBlock = stages.values().stream()
                .filter(value -> value != null)
                .anyMatch(value -> PackAdvancedResourcePlan.containsStorageBlock(value.source()));
        if (hasStorageBlock && (advancedResources == null
                || advancedResources.storageBuffers(program.name()).isEmpty())) {
            boolean pendingCatalog = advancedResources != null
                    && advancedResources.buffers().values().stream()
                    .anyMatch(PackAdvancedResourcePlan.BufferSpec::supported);
            if (!pendingCatalog) {
                deviations.add("STORAGE_BUFFER_UNPLANNED:" + program.name());
                executable = false;
            }
        }

        if (geometryOutputPlan != null && geometryOutputPlan.requiresMrt()
                && vertex == null
                && !FamilyAdapterRegistry.isEntityLike(program.name())
                && !FamilyAdapterRegistry.isParticleLike(program.name())
                && !FamilyAdapterRegistry.isWeatherFamily(program.name())) {
            // M8.5 MRT geometry requires a complete vertex/fragment pair. Keep
            // the older fragment-only single-target adapter unchanged.
            deviations.add("MRT_NOT_SUPPORTED");
            executable = false;
        }

        if (FamilyAdapterRegistry.isEntityLike(program.name()) && vertex == null) {
            // Preserve the pre-M8.4 inventory contract for the historical
            // fragment-only glowing fixture. The new glowing adapter requires
            // a paired vertex stage; the missing pair is already represented
            // by the unsupported eligibility result.
            if (!program.name().equals("gbuffers_entities_glowing")) {
                deviations.add(vertexBridgeDeviation(program.name()));
            }
        }
        if (FamilyAdapterRegistry.isParticleLike(program.name()) && vertex == null) {
            deviations.add("PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
        }
        if (FamilyAdapterRegistry.isWeatherFamily(program.name()) && vertex == null) {
            deviations.add("WEATHER_VERTEX_BRIDGE_UNSUPPORTED");
        }

        try {
            if (FamilyAdapterRegistry.isSkyFamily(program.name()) && vertex != null) {
                FamilyAdapterPlan.VertexContract skyContract =
                        LegacyGlslConverter.skyVertexContract(
                                vertex, program.name().equals("gbuffers_skytextured"));
                familyAdapter = familyAdapter.withVertexContract(skyContract);
                LegacyGlslConverter.TerrainVertexConversion conversion =
                        LegacyGlslConverter.convertSkyVertex(vertex,
                                preparedSnapshot ? null : program.vertexPath(), fragment,
                                skyContract);
                if (conversion == null) {
                    deviations.add("SKY_VERTEX_BRIDGE_UNSUPPORTED");
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                    deviations.add("SKY_VERTEX_BRIDGE");
                }
            } else if (FamilyAdapterRegistry.isCloudFamily(program.name()) && vertex != null
                    && AuthoredOutput.producesNothing(vertex, fragment)) {
                // The selected branch draws no cloud at all. There is no
                // pipeline to build; the runtime skips the host cloud draw.
                deviations.add(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
                executable = false;
            } else if (FamilyAdapterRegistry.isCloudFamily(program.name()) && vertex != null) {
                familyAdapter = familyAdapter.withVertexContract(
                        FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR);
                LegacyGlslConverter.TerrainVertexConversion conversion =
                        LegacyGlslConverter.convertCloudVertex(vertex,
                                preparedSnapshot ? null : program.vertexPath(), fragment);
                if (conversion == null) {
                    deviations.add("CLOUD_VERTEX_BRIDGE_UNSUPPORTED");
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                    deviations.add("CLOUD_VERTEX_BRIDGE");
                    deviations.add("CLOUD_STATE_FIXED_TO_HOST");
                }
            } else if (isTerrainLike(program.name()) && vertex != null) {
                boolean modernTerrain = terrainMaterial.modern();
                LegacyGlslConverter.TerrainVertexConversion conversion;
                if (program.name().equals("shadow")) {
                    conversion = LegacyGlslConverter.convertShadowVertex(
                            vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                            interfacePlan.project("vertex", stage), allowStorageBuffers);
                } else {
                    conversion = modernTerrain
                            ? LegacyGlslConverter.convertModernTerrainVertex(vertex, null, fragment,
                            interfacePlan.project("vertex", stage), allowStorageBuffers)
                            : LegacyGlslConverter.convertTerrainVertex(
                            vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                            allowStorageBuffers);
                }
                if (conversion == null) {
                    deviations.add(vertexBridgeDeviation(program.name()));
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                }
            }

            if (executable && FamilyAdapterRegistry.isEntityLike(program.name()) && vertex != null) {
                LegacyGlslConverter.TerrainVertexConversion conversion = null;
                try {
                    java.util.List<UniformRegistry.UniformDeclaration> fragmentUniforms =
                            interfacePlan.project("fragment", stage).executableUniforms();
                    conversion = FamilyAdapterRegistry.isBlockFamily(program.name())
                            ? LegacyGlslConverter.convertBlockVertexChecked(
                            vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                            stageMatch.locations(), allowStorageBuffers, fragmentUniforms)
                            : FamilyAdapterRegistry.isHandFamily(program.name())
                            ? LegacyGlslConverter.convertHandVertexChecked(
                            vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                            stageMatch.locations(), allowStorageBuffers, fragmentUniforms)
                            : LegacyGlslConverter.convertEntityVertexChecked(
                            vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                            stageMatch.locations(), allowStorageBuffers, fragmentUniforms);
                } catch (IllegalArgumentException failure) {
                    deviations.add(entityBridgeDeviation(program.name(), true));
                    deviations.add(entityConversionDeviation(program.name(), failure));
                }
                if (conversion == null) {
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                    deviations.add(entityBridgeDeviation(program.name(), false));
                }
            }

            if (executable && PostTargetPlan.isPostProgramName(program.name())) {
                if (pairedPostVertex) {
                    LegacyGlslConverter.PostVertexConversion postVertex =
                            LegacyGlslConverter.convertPostVertex(
                                    vertex,
                                    stageInterfaces.get("vertex"),
                                    stageInterfaces.get("fragment"),
                                    stageMatch, interfacePlan.project("vertex", UniformRegistry.Stage.POST),
                                    config == null ? Map.of() : config.shaderConstants());
                    if (postVertex.source() == null) {
                        deviations.addAll(postVertex.deviations());
                        deviations.add("POST_CONVERTER_UNSUPPORTED");
                        executable = false;
                    } else {
                        convertedVertex = postVertex.source();
                        deviations.addAll(postVertex.deviations());
                    }
                }
                if (executable) {
                    LegacyGlslConverter.PostVaryingLayout postLayout = !pairedPostVertex ? null
                            : new LegacyGlslConverter.PostVaryingLayout(
                            stageMatch.locations(), postVaryingTypes(stageInterfaces.get("fragment")));
                    convertedFragment = LegacyGlslConverter.convertPostFragment(
                            fragment,
                            preparedSnapshot ? null : program.fragmentPath(),
                            interfacePlan.project("fragment", UniformRegistry.Stage.POST),
                            targetPlan,
                            config == null ? Map.of() : config.shaderConstants(),
                            postLayout == null ? null : postLayout.locations(),
                            postLayout == null ? null : postLayout.types());
                }
                if (convertedFragment == null) {
                    if (!pairedPostVertex) deviations.addAll(LegacyGlslConverter.postVaryingDeviations(fragment));
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("gbuffers_terrain")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.FragmentConversionRequest
                        .of(fragment, preparedSnapshot ? null : program.fragmentPath(), true, slots)
                        .withTerrainLayout(vertexLayout)
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withGeometryOutputPlan(geometryOutputPlan)
                        .withPackConstants(config == null ? Map.of() : config.shaderConstants())
                        .withAtlasSamplers(PackResourcePlan.terrainAtlasSamplers(program.name(),
                                interfacePlan.project("fragment", stage), config == null ? null : config.settings()))
                        .withAlphaTestPlan(vertex != null ? alphaTestPlan : PackAlphaTestPlan.disabled())
                        .convert();
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("gbuffers_water")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.FragmentConversionRequest
                        .of(fragment, preparedSnapshot ? null : program.fragmentPath(), true, slots)
                        .withTerrainLayout(vertexLayout)
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withGeometryOutputPlan(geometryOutputPlan)
                        .withPackConstants(config == null ? Map.of() : config.shaderConstants())
                        .withAtlasSamplers(PackResourcePlan.terrainAtlasSamplers(program.name(),
                                interfacePlan.project("fragment", stage), config == null ? null : config.settings()))
                        .withAlphaTestPlan(vertex != null ? alphaTestPlan : PackAlphaTestPlan.disabled())
                        .convert();
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("shadow")) {
                int[] slots = PackPipelines.shadowSamplerSlots(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.FragmentConversionRequest
                        .of(fragment, preparedSnapshot ? null : program.fragmentPath(), true, slots)
                        .withTerrainLayout(vertexLayout)
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withPackConstants(config == null ? Map.of() : config.shaderConstants())
                        .convert();
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && FamilyAdapterRegistry.isEntityLike(program.name())) {
                int[] slots = PackPipelines.entitySamplerSlots(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.convertEntityFragment(
                        fragment, preparedSnapshot ? null : program.fragmentPath(),
                        slots, vertexLayout, interfacePlan.project("fragment", stage),
                        geometryOutputPlan);
            } else if (executable && (FamilyAdapterRegistry.isSkyFamily(program.name())
                    || FamilyAdapterRegistry.isCloudFamily(program.name()))) {
                int[] slots = PackPipelines.entitySamplerSlots(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.convertSkyFragment(
                        fragment, preparedSnapshot ? null : program.fragmentPath(), slots,
                        vertexLayout, interfacePlan.project("fragment", stage));
                if (convertedFragment == null) {
                    deviations.add(FamilyAdapterRegistry.isCloudFamily(program.name())
                            ? "CLOUD_FRAGMENT_BRIDGE_UNSUPPORTED"
                            : "SKY_FRAGMENT_BRIDGE_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && (FamilyAdapterRegistry.isParticleLike(program.name())
                    || FamilyAdapterRegistry.isWeatherFamily(program.name()))) {
                int[] slots = PackPipelines.entitySamplerSlots(interfaceSlots(interfacePlan, stage));
                LegacyGlslConverter.TerrainVertexConversion conversion =
                        LegacyGlslConverter.convertParticleVertex(
                                vertex, preparedSnapshot ? null : program.vertexPath(), fragment,
                                stageMatch.locations(), allowStorageBuffers);
                if (conversion == null) {
                    deviations.add(FamilyAdapterRegistry.isWeatherFamily(program.name())
                            ? "WEATHER_VERTEX_BRIDGE_UNSUPPORTED"
                            : "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                    convertedFragment = LegacyGlslConverter.convertParticleFragment(
                            fragment, preparedSnapshot ? null : program.fragmentPath(), slots,
                            vertexLayout, interfacePlan.project("fragment", stage),
                            geometryOutputPlan);
                    if (convertedFragment == null) {
                        deviations.add(FamilyAdapterRegistry.isWeatherFamily(program.name())
                                ? "WEATHER_FRAGMENT_BRIDGE_UNSUPPORTED"
                                : "PARTICLE_FRAGMENT_BRIDGE_UNSUPPORTED");
                        executable = false;
                    }
                }
            } else {
                executable = false;
            }
        } catch (RuntimeException e) {
            deviations.add("TRANSLATION_UNSUPPORTED:" + safeReason(e));
            executable = false;
        }

        if (executable && advancedResources != null
                && !advancedResources.storageBuffers(program.name()).isEmpty()) {
            if (!advancedResources.storageBufferProgramSupported(program.name())) {
                deviations.addAll(advancedResources.storageBuffers(program.name()).stream()
                        .flatMap(value -> value.deviations().stream()).toList());
                executable = false;
            } else {
                convertedFragment = advancedResources.rewriteStorageBuffers(
                        program.name(), convertedFragment);
                if (convertedVertex != null) {
                    convertedVertex = advancedResources.rewriteStorageBuffers(
                            program.name(), convertedVertex);
                }
                if (convertedFragment == null || (program.vertexSource() != null && convertedVertex == null)) {
                    deviations.add("STORAGE_BUFFER_REWRITE_FAILED:" + program.name());
                    executable = false;
                }
            }
        }

        PackProgramResolution resolved = resolution == null ? null : resolution.resolution(program.name());
        if (resolved != null) {
            deviations.addAll(resolved.deviations());
            if (!resolved.enabled() || !resolved.executable()) {
                executable = false;
            }
        }

        boolean terrainFamily = program.name().equals("gbuffers_terrain")
                || program.name().equals("gbuffers_water");
        if (terrainFamily && executable && vertex != null) {
            String applied = alphaTestPlan.appliedDeviation(program.name());
            if (!applied.isBlank()) deviations.add(applied);
        } else if (terrainFamily
                && (vertex == null || program.name().equals("gbuffers_water"))
                && alphaTestPlan.configured()
                && (alphaTestPlan.active() || !alphaTestPlan.valid())) {
            deviations.add("ALPHA_TEST_PLANNED_NOT_INSTALLED:" + program.name());
        }

        // Blend directives reach the per-attachment state of the world MRT
        // pipelines; other programs keep the directive as a named gap.
        PackBlendPlan blendPlan = PackBlendPlan.forProgram(program.name(),
                config == null ? PackSettingsPlan.empty() : config.settings());
        if (blendPlan.overridesAnything() && geometryOutputPlan == null) {
            deviations.add("BLEND_DIRECTIVE_UNSUPPORTED:" + program.name());
            blendPlan = PackBlendPlan.empty();
        } else {
            deviations.addAll(blendPlan.deviations());
        }

        return new PackProgramPlan(program, stages, interfacePlan, stageInterfaces,
                varyingLocations, targetPlan, convertedFragment, convertedVertex,
                vertexLayout, deviations, executable,
                familyAdapter, terrainMaterial, geometryOutputPlan, alphaTestPlan, blendPlan);
    }

    private static Map<String, Integer> postVaryingLocations(
            GlslInterfaceScanner.StageInterface fragment
    ) {
        if (fragment == null) {
            return Map.of();
        }
        Map<String, Integer> result = new TreeMap<>();
        for (GlslInterfaceScanner.Declaration declaration : fragment.inputs()) {
            if (declaration.referenced()) {
                result.put(declaration.name(), 0);
            }
        }
        return result;
    }

    private static Map<String, String> postVaryingTypes(
            GlslInterfaceScanner.StageInterface fragment
    ) {
        if (fragment == null) {
            return Map.of();
        }
        Map<String, String> result = new TreeMap<>();
        for (GlslInterfaceScanner.Declaration declaration : fragment.inputs()) {
            if (declaration.referenced()) {
                result.put(declaration.name(), declaration.type());
            }
        }
        return result;
    }

    private static boolean isTerrainLike(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || name.equals("shadow");
    }

    private static boolean isTerrainMaterialFamily(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || name.equals("shadow");
    }

    /**
     * Families whose active sources can write multiple outputs through
     * DRAWBUFFERS/RENDERTARGETS. Each gets an immutable geometry output
     * plan; single-output plans stay executable and unused directives
     * never block the family.
     */
    private static boolean isGeometryOutputFamily(String name) {
        return FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name)
                || FamilyAdapterRegistry.isWeatherFamily(name)
                || name.equals("gbuffers_terrain")
                || name.equals("gbuffers_water");
    }

    private static String vertexBridgeDeviation(String name) {
        return switch (name) {
            case "shadow" -> "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_water" -> "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_particles", "gbuffers_particles_translucent"
                    -> "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_block", "gbuffers_damagedblock"
                    -> "BLOCK_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_hand", "gbuffers_hand_water"
                    -> "HAND_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_entities", "gbuffers_entities_translucent",
                    "gbuffers_entities_glowing" -> "ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
            default -> "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
        };
    }

    private static String entityBridgeDeviation(String name, boolean unsupported) {
        return switch (name) {
            case "gbuffers_block", "gbuffers_damagedblock" -> unsupported
                    ? "BLOCK_VERTEX_BRIDGE_UNSUPPORTED" : "BLOCK_VERTEX_BRIDGE";
            case "gbuffers_hand", "gbuffers_hand_water" -> unsupported
                    ? "HAND_VERTEX_BRIDGE_UNSUPPORTED" : "HAND_VERTEX_BRIDGE";
            default -> unsupported
                    ? "ENTITY_VERTEX_BRIDGE_UNSUPPORTED" : "ENTITY_VERTEX_BRIDGE";
        };
    }

    /**
     * Preserves the first concrete vertex conversion failure next to the
     * generic bridge code so diagnostics name the actual blocker. The
     * generic code keeps driving severity routing; this code is
     * diagnostic-only and never matches a severe prefix.
     */
    private static String entityConversionDeviation(String name, IllegalArgumentException failure) {
        String reason = failure.getMessage() == null ? "unknown" : failure.getMessage();
        int cut = Math.min(reason.length(), 120);
        return switch (name) {
            case "gbuffers_block", "gbuffers_damagedblock" ->
                    "BLOCK_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
            case "gbuffers_hand", "gbuffers_hand_water" ->
                    "HAND_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
            default -> "ENTITY_VERTEX_CONVERSION_FAILED:" + reason.substring(0, cut);
        };
    }

    private static Map<String, PreparedShaderSource> preparedStages(PackProgram program) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(program.preparedSources());
        if (result.isEmpty()) {
            if (program.fragmentSource() != null) {
                result.put("fragment", new PreparedShaderSource("fragment",
                        relative(program.sourceRoot(), program.fragmentPath()),
                        program.executableFragmentSource(), List.of(), List.of()));
            }
            if (program.vertexSource() != null) {
                result.put("vertex", new PreparedShaderSource("vertex",
                        relative(program.sourceRoot(), program.vertexPath()),
                        program.executableVertexSource(), List.of(), List.of()));
            }
        }
        return result;
    }

    private static Map<String, PreparedShaderSource> normalizeModernPostStages(
            Map<String, PreparedShaderSource> stages
    ) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            if (prepared.source() == null || !hasModernVersion(prepared.source())) {
                continue;
            }
            String normalized = LegacyGlslConverter.normalizeModernPost(prepared.source());
            List<String> deviations = new ArrayList<>(prepared.deviations());
            deviations.add("MODERN_GLSL_TRANSLATED");
            result.put(entry.getKey(), new PreparedShaderSource(
                    prepared.stage(), prepared.relativePath(), normalized,
                    prepared.dependencies(), deviations));
        }
        return result;
    }

    private static Map<String, PreparedShaderSource> normalizeModernTerrainStages(
            Map<String, PreparedShaderSource> stages
    ) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        boolean extended = stages.values().stream().anyMatch(value ->
                value.source() != null && LegacyGlslConverter.requiresExtendedTerrain(value.source()));
        if (!extended) {
            return result;
        }
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            if (prepared.source() == null || !LegacyGlslConverter.requiresExtendedTerrain(prepared.source())) {
                continue;
            }
            String normalized;
            try {
                normalized = LegacyGlslConverter.normalizeModernTerrain(prepared.source());
            } catch (IllegalArgumentException unsupportedVersion) {
                // Leave unsupported versions authored so the program plan can
                // record a per-program fallback instead of rejecting the pack.
                continue;
            }
            List<String> deviations = new ArrayList<>(prepared.deviations());
            deviations.add("MODERN_GLSL_TRANSLATED");
            result.put(entry.getKey(), new PreparedShaderSource(
                    prepared.stage(), prepared.relativePath(), normalized,
                    prepared.dependencies(), deviations));
        }
        return result;
    }

    private static boolean hasModernVersion(String source) {
        return source.matches("(?s).*#version\\s+(?:330|400)(?:\\s+.*)?(?:\\r?\\n|$).*");
    }

    private static String source(
            Map<String, PreparedShaderSource> stages,
            String stage,
            String fallback
    ) {
        PreparedShaderSource prepared = stages.get(stage);
        return prepared == null ? fallback : prepared.source();
    }

    private static String relative(Path root, Path path) {
        if (root == null || path == null) {
            return "";
        }
        try {
            return root.toAbsolutePath().normalize()
                    .relativize(path.toAbsolutePath().normalize())
                    .toString().replace('\\', '/');
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static UniformRegistry.Stage stageFor(String name) {
        return FamilyAdapterRegistry.stageFor(name);
    }

    private static boolean isExecutableFamily(String name) {
        return FamilyAdapterRegistry.isExecutableFamily(name);
    }

    private static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "source";
        }
        return message.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static int[] interfaceSlots(
            UniformRegistry.ProgramInterfacePlan plan,
            UniformRegistry.Stage stage
    ) {
        return plan.effective(stage).samplers().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot)
                .toArray();
    }

    private static boolean preparedSuccessfully(PreparedShaderSource source) {
        if (source.source() == null) {
            return false;
        }
        return source.deviations().stream().noneMatch(value ->
                ((value.startsWith("SOURCE_") || value.startsWith("PREPROCESSOR_"))
                        && !value.startsWith("PREPROCESSOR_MACRO_REDEFINED:"))
                        || value.startsWith("LEGACY_FOG_FIELD_UNSUPPORTED:"));
    }
}
