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
            for (PackProgram program : (resolution == null ? PackResolutionPlan.empty() : resolution)
                    .executablePrograms(programs)) {
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
        return new ProgramBuild(program, config, resolution, advancedResources).plan();
    }

    /**
     * One program's plan, built in order: prepare the sources for their family, plan the shared
     * interface, decide whether the program can execute, convert the vertex and fragment stages,
     * then apply the pack-wide storage, resolution, alpha-test and blend contracts.
     */
    private static final class ProgramBuild {
        private final PackProgram program;
        private final String name;
        private final PackConfig.PackConfigData config;
        private final PackResolutionPlan resolution;
        private final PackAdvancedResourcePlan advancedResources;
        private final Map<String, PreparedShaderSource> stages;
        private final boolean preparedSnapshot;
        private final UniformRegistry.Stage stage;
        private final TerrainMaterialPlan terrainMaterial;
        private final PostTargetPlan targetPlan;
        private final GeometryOutputPlan geometryOutputPlan;
        private final String directiveProgram;
        /** The deviation label of a host-layout lane (LINE, GLINT, SHADOW_LINES, ...), or null. */
        private final String hostLayoutLane;
        private final PackAlphaTestPlan alphaTestPlan;
        private final boolean allowStorageBuffers;
        private final List<String> deviations = new ArrayList<>();

        private FamilyAdapterPlan familyAdapter;
        private String fragment;
        private String vertex;
        private String hostLayoutFailure;
        private UniformRegistry.ProgramInterfacePlan interfacePlan;
        private final Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces = new TreeMap<>();
        private boolean pairedPostVertex;
        private GlslInterfaceScanner.ProgramMatch stageMatch;
        private boolean executable;
        private String convertedFragment;
        private String convertedVertex;
        private LegacyGlslConverter.TerrainVaryingLayout vertexLayout;

        private ProgramBuild(
                PackProgram program,
                PackConfig.PackConfigData config,
                PackResolutionPlan resolution,
                PackAdvancedResourcePlan advancedResources
        ) {
            this.program = program;
            this.name = program.name();
            this.config = config;
            this.resolution = resolution;
            this.advancedResources = advancedResources;
            Map<String, PreparedShaderSource> prepared = preparedStages(program);
            if (FamilyAdapterRegistry.stageFor(name) == UniformRegistry.Stage.POST) {
                prepared = normalizeModernPostStages(prepared);
            } else if (isTerrainMaterialFamily(name)) {
                prepared = normalizeModernTerrainStages(prepared);
            }
            this.stages = prepared;
            this.fragment = source(stages, "fragment", program.executableFragmentSource());
            this.vertex = source(stages, "vertex", program.executableVertexSource());
            this.familyAdapter = FamilyAdapterRegistry.forProgram(name);
            this.terrainMaterial = TerrainMaterialPlan.forProgram(name, vertex, fragment);
            this.preparedSnapshot = !program.preparedSources().isEmpty();
            this.stage = FamilyAdapterRegistry.stageFor(name);
            Map<Integer, Integer> formats = config == null ? Map.of() : config.colortexFormats();
            this.targetPlan = PostTargetPlan.isPostProgramName(name)
                    ? PostTargetPlan.parse(name, fragment, formats).plan() : null;
            this.geometryOutputPlan = isGeometryOutputFamily(name)
                    ? GeometryOutputPlan.parse(name, fragment, formats) : null;
            PackProgramResolution sourceResolution = resolution == null ? null : resolution.resolution(name);
            this.directiveProgram = sourceResolution == null || sourceResolution.selectedProgram().isBlank()
                    ? name : sourceResolution.selectedProgram();
            this.hostLayoutLane = FamilyAdapterRegistry.hostLayoutLane(name);
            this.alphaTestPlan = PackAlphaTestPlan.forProgram(name, directiveProgram, settings());
            boolean storageBuffers = advancedResources != null
                    && !advancedResources.storageBuffers(name).isEmpty()
                    && advancedResources.storageBufferProgramSupported(name);
            if (!storageBuffers && advancedResources != null) {
                storageBuffers = advancedResources.buffers().values().stream()
                        .anyMatch(PackAdvancedResourcePlan.BufferSpec::supported);
            }
            this.allowStorageBuffers = storageBuffers;
        }

        PackProgramPlan plan() {
            prepareIrisCoreProfile();
            prepareHostLayout();
            injectAlphaTest();
            planInterface();
            collectPlanningDeviations();
            executable = admitted();
            try {
                convertVertex();
                if (executable && FamilyAdapterRegistry.isEntityLike(name) && vertex != null) {
                    convertEntityVertex();
                }
                convertFragment();
            } catch (RuntimeException e) {
                deviations.add("TRANSLATION_UNSUPPORTED:" + safeReason(e));
                executable = false;
            }
            rewriteStorageBuffers();
            applyResolution();
            addAlphaTestDeviation();
            PackBlendPlan blendPlan = blendPlan();
            return new PackProgramPlan(program, stages, interfacePlan, stageInterfaces,
                    varyingLocations(), targetPlan, convertedFragment, convertedVertex,
                    vertexLayout, deviations, executable,
                    familyAdapter, terrainMaterial, geometryOutputPlan, alphaTestPlan, blendPlan);
        }

        private PackSettingsPlan settings() {
            return config == null ? PackSettingsPlan.empty() : config.settings();
        }

        private Map<String, String> packConstants() {
            return config == null ? Map.of() : config.shaderConstants();
        }

        private Path vertexFile() {
            return preparedSnapshot ? null : program.vertexPath();
        }

        private Path fragmentFile() {
            return preparedSnapshot ? null : program.fragmentPath();
        }

        private boolean lineLayout() {
            return familyAdapter.vertexContract() == FamilyAdapterPlan.VertexContract.HOST_LINE;
        }

        /**
         * Iris core-profile names (vaPosition, modelViewMatrix) become the compatibility built-ins
         * every converter serves. A pack's own gbuffers_line vertex keeps Iris's unbound UVs
         * (prepareLineVertex).
         */
        private void prepareIrisCoreProfile() {
            String overlay = stage == UniformRegistry.Stage.POST || isTerrainMaterialFamily(name)
                    ? IrisCoreProfile.NO_OVERLAY : "UV1";
            if (vertex != null && !(lineLayout() && directiveProgram.equals("gbuffers_line"))) {
                vertex = IrisCoreProfile.prepareIrisCoreProfile(vertex, vertexFile(), true, overlay,
                        irisCoreMode(vertex));
            }
            if (fragment != null) {
                fragment = IrisCoreProfile.prepareIrisCoreProfile(fragment, fragmentFile(), false, overlay,
                        irisCoreMode(fragment));
            }
        }

        /**
         * Iris's handling of a host-layout lane rewrites the authored sources before anything
         * reads their interface: a widened line declares and reads the screen size, glint reads
         * the glint strength, and a lane whose format Iris does not entity-patch reads entityId as
         * a per-draw uniform.
         */
        private void prepareHostLayout() {
            if (hostLayoutLane == null || vertex == null) {
                return;
            }
            try {
                // Iris widens every program drawn on the line layout except a pack's own
                // gbuffers_line (ShaderAttributeInputs.newLines from the LineWidth element).
                vertex = FamilyAdapterRegistry.isGlintFamily(name)
                        ? IrisCoreProfile.prepareGlintVertex(vertex, vertexFile())
                        : lineLayout()
                        ? IrisCoreProfile.prepareLineVertex(vertex, vertexFile(),
                        directiveProgram.equals("gbuffers_line"))
                        : IrisCoreProfile.prepareHostLayoutSource(vertex, vertexFile());
                boolean perVertexIds = FamilyAdapterRegistry.readsEntityIdsPerVertex(name);
                if (!perVertexIds) vertex = IrisCoreProfile.prepareHostLayoutIdentity(vertex);
                if (fragment != null) {
                    fragment = IrisCoreProfile.prepareHostLayoutSource(fragment, fragmentFile());
                    if (!perVertexIds) fragment = IrisCoreProfile.prepareHostLayoutIdentity(fragment);
                }
            } catch (RuntimeException failure) {
                hostLayoutFailure = safeReason(failure);
            }
        }

        /** Only a program that can execute (entity families need a vertex stage) receives the host alpha test. */
        private void injectAlphaTest() {
            if (vertex == null) {
                return;
            }
            if (FamilyAdapterRegistry.isEntityLike(name) || FamilyAdapterRegistry.isParticleLike(name)
                    || FamilyAdapterRegistry.isWeatherFamily(name) || FamilyAdapterRegistry.isGlintFamily(name)) {
                fragment = alphaTestPlan.injectDrawTest(fragment);
                if (FamilyAdapterRegistry.isEntityLike(name) && !name.equals("gbuffers_damagedblock")) {
                    fragment = EntityOverlayColor.inject(fragment);
                }
            } else if (FamilyAdapterRegistry.isShadowFamily(name)) {
                // Iris alpha-tests shadow terrain by layer and entity casters at 0.1; the
                // shadow pass sets the reference per draw (ChimeraMainPass.renderShadowMap).
                fragment = alphaTestPlan.injectDrawTest(fragment);
            }
        }

        private void planInterface() {
            PackSettingsPlan settings = config == null ? null : config.settings();
            interfacePlan = UniformRegistry.planProgram(
                    fragment, vertex, stage, targetPlan, !program.variantFolder().isBlank(),
                    settings == null ? Map.of() : settings.runtimeSettings().customDescriptors(),
                    settings == null ? Map.of() : settings.customSamplerSlots());
            if (fragment != null) {
                stageInterfaces.put("fragment", GlslInterfaceScanner.scan(fragment, false));
            }
            if (vertex != null) {
                stageInterfaces.put("vertex", GlslInterfaceScanner.scan(vertex, true));
            }
            pairedPostVertex = stage == UniformRegistry.Stage.POST && vertex != null;
            boolean reconcileStages = vertex != null && (stage != UniformRegistry.Stage.POST || pairedPostVertex);
            stageMatch = !reconcileStages
                    ? new GlslInterfaceScanner.ProgramMatch(Map.of(), List.of())
                    : GlslInterfaceScanner.match(stageInterfaces.get("vertex"), stageInterfaces.get("fragment"));
        }

        private Map<String, Integer> varyingLocations() {
            return stage == UniformRegistry.Stage.POST && !pairedPostVertex
                    ? postVaryingLocations(stageInterfaces.get("fragment"))
                    : stageMatch.locations();
        }

        private void collectPlanningDeviations() {
            deviations.addAll(program.preparationDeviations());
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
        }

        /** Whether the planned sources may execute, recording each contract they break. */
        private boolean admitted() {
            boolean needsVertex = FamilyAdapterRegistry.isEntityLike(name)
                    || FamilyAdapterRegistry.isShadowEntityFamily(name)
                    || FamilyAdapterRegistry.isParticleLike(name)
                    || FamilyAdapterRegistry.isWeatherFamily(name)
                    || hostLayoutLane != null
                    || FamilyAdapterRegistry.isSkyFamily(name)
                    || FamilyAdapterRegistry.isCloudFamily(name);
            boolean admitted = FamilyAdapterRegistry.isExecutableFamily(name)
                    && fragment != null
                    && (!needsVertex || vertex != null)
                    && stages.values().stream().allMatch(PackPlanBuilder::preparedSuccessfully)
                    && interfacePlan.executable()
                    && stageInterfaces.values().stream().allMatch(value -> value.deviations().isEmpty())
                    && stageMatch.executable()
                    && (targetPlan == null || targetPlan.executable())
                    && (geometryOutputPlan == null || geometryOutputPlan.executable())
                    && alphaTestPlan.valid();
            if (geometryOutputPlan != null) {
                // A world draw samples pack targets as shader-read images while its own outputs
                // are colour attachments; one image cannot be both inside a render pass.
                for (UniformRegistry.SamplerBinding sampler : interfacePlan.effective(stage).samplers()) {
                    Integer target = PackResourcePlan.targetIndex(sampler.name());
                    if (target != null && geometryOutputPlan.targetSlots().contains(target)) {
                        deviations.add("GBUFFER_TARGET_FEEDBACK:colortex" + target);
                        admitted = false;
                    }
                }
            }
            if (stage.shadowPass()) {
                PostTargetPlan shadowOutputs = PostTargetPlan.parse("shadow", fragment).plan();
                for (String deviation : shadowOutputs.deviations()) {
                    deviations.add(deviation.replaceFirst("^POST_", "SHADOW_"));
                }
                for (int target : shadowOutputs.targetSlots()) {
                    if (target < 0 || target > 1) {
                        deviations.add("SHADOW_OUTPUT_TARGET_UNSUPPORTED:" + target);
                        admitted = false;
                    }
                }
                admitted &= shadowOutputs.executable();
            }
            boolean hasStorageBlock = stages.values().stream()
                    .filter(value -> value != null)
                    .anyMatch(value -> PackAdvancedResourcePlan.containsStorageBlock(value.source()));
            if (hasStorageBlock && (advancedResources == null || advancedResources.storageBuffers(name).isEmpty())) {
                boolean pendingCatalog = advancedResources != null
                        && advancedResources.buffers().values().stream()
                        .anyMatch(PackAdvancedResourcePlan.BufferSpec::supported);
                if (!pendingCatalog) {
                    deviations.add("STORAGE_BUFFER_UNPLANNED:" + name);
                    admitted = false;
                }
            }
            if (geometryOutputPlan != null && geometryOutputPlan.requiresMrt() && vertex == null
                    && !FamilyAdapterRegistry.isEntityLike(name)
                    && !FamilyAdapterRegistry.isParticleLike(name)
                    && !FamilyAdapterRegistry.isWeatherFamily(name)) {
                // MRT geometry requires a complete vertex/fragment pair; the fragment-only
                // single-target adapter stays as it was.
                deviations.add("MRT_NOT_SUPPORTED");
                admitted = false;
            }
            if (vertex == null) {
                if (FamilyAdapterRegistry.isEntityLike(name) || FamilyAdapterRegistry.isShadowEntityFamily(name)) {
                    deviations.add(vertexBridgeDeviation(name));
                }
                if (FamilyAdapterRegistry.isParticleLike(name)) {
                    deviations.add("PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
                }
                if (FamilyAdapterRegistry.isWeatherFamily(name)) {
                    deviations.add("WEATHER_VERTEX_BRIDGE_UNSUPPORTED");
                }
            }
            if (hostLayoutLane != null && (vertex == null || hostLayoutFailure != null)) {
                deviations.add(hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
                if (hostLayoutFailure != null) {
                    deviations.add(hostLayoutLane + "_VERTEX_CONVERSION_FAILED:" + hostLayoutFailure);
                }
                admitted = false;
            }
            return admitted;
        }

        /** Records a vertex conversion, or the deviation that keeps the program from executing. */
        private void acceptVertex(LegacyGlslConverter.TerrainVertexConversion conversion,
                                  String bridged, String unsupported) {
            if (conversion == null) {
                deviations.add(unsupported);
                executable = false;
                return;
            }
            convertedVertex = conversion.source();
            vertexLayout = conversion.layout();
            if (bridged != null) {
                deviations.add(bridged);
            }
        }

        /** Converts the vertex stage of the families with their own bridge, before admission is final. */
        private void convertVertex() {
            if (vertex == null) {
                return;
            }
            if (FamilyAdapterRegistry.isSkyFamily(name)) {
                FamilyAdapterPlan.VertexContract skyContract =
                        HostLayoutVertexBridge.skyVertexContract(vertex, name.equals("gbuffers_skytextured"));
                familyAdapter = familyAdapter.withVertexContract(skyContract);
                acceptVertex(HostLayoutVertexBridge.convertSkyVertex(vertex, vertexFile(), fragment,
                                skyContract, stageMatch.locations(), allowStorageBuffers,
                                interfacePlan.project("fragment", stage).executableUniforms()),
                        "SKY_VERTEX_BRIDGE", "SKY_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isCloudFamily(name) && AuthoredOutput.producesNothing(vertex, fragment)) {
                // The selected branch draws no cloud at all. There is no pipeline to build; the
                // runtime skips the host cloud draw.
                deviations.add(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
                executable = false;
            } else if (FamilyAdapterRegistry.isCloudFamily(name)) {
                familyAdapter = familyAdapter.withVertexContract(FamilyAdapterPlan.VertexContract.CLOUD_POSITION_COLOR);
                acceptVertex(HostLayoutVertexBridge.convertCloudVertex(vertex, vertexFile(), fragment),
                        "CLOUD_VERTEX_BRIDGE", "CLOUD_VERTEX_BRIDGE_UNSUPPORTED");
                if (convertedVertex != null) {
                    deviations.add("CLOUD_STATE_FIXED_TO_HOST");
                }
            } else if (FamilyAdapterRegistry.isShadowLaneFamily(name) && hostLayoutFailure == null) {
                LegacyGlslConverter.TerrainVertexConversion conversion = null;
                try {
                    // The host-layout preparation already expanded the includes.
                    conversion = HostLayoutVertexBridge.convertShadowLaneVertexChecked(
                            familyAdapter.vertexContract(), vertex, fragment,
                            interfacePlan.project("vertex", stage), allowStorageBuffers);
                } catch (RuntimeException failure) {
                    deviations.add(hostLayoutLane + "_VERTEX_CONVERSION_FAILED:" + safeReason(failure));
                }
                acceptVertex(conversion, hostLayoutLane + "_VERTEX_BRIDGE",
                        hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isShadowEntityFamily(name)) {
                LegacyGlslConverter.TerrainVertexConversion conversion = null;
                try {
                    conversion = EntityVertexBridge.convertShadowEntityVertexChecked(
                            vertex, vertexFile(), fragment, interfacePlan.project("vertex", stage),
                            allowStorageBuffers);
                } catch (RuntimeException failure) {
                    deviations.add("SHADOW_ENTITY_VERTEX_CONVERSION_FAILED:" + safeReason(failure));
                }
                acceptVertex(conversion, "SHADOW_ENTITY_VERTEX_BRIDGE", vertexBridgeDeviation(name));
            } else if (isTerrainLike(name)) {
                LegacyGlslConverter.TerrainVertexConversion conversion;
                if (name.equals("shadow")) {
                    conversion = TerrainVertexBridge.convertShadowVertex(vertex, vertexFile(), fragment,
                            interfacePlan.project("vertex", stage), allowStorageBuffers);
                } else if (terrainMaterial.modern()) {
                    conversion = TerrainVertexBridge.convertModernTerrainVertex(vertex, null, fragment,
                            interfacePlan.project("vertex", stage), allowStorageBuffers);
                } else {
                    conversion = TerrainVertexBridge.convertTerrainVertex(vertex, vertexFile(), fragment,
                            allowStorageBuffers);
                }
                acceptVertex(conversion, null, vertexBridgeDeviation(name));
            }
        }

        private void convertEntityVertex() {
            LegacyGlslConverter.TerrainVertexConversion conversion = null;
            try {
                List<UniformRegistry.UniformDeclaration> fragmentUniforms =
                        interfacePlan.project("fragment", stage).executableUniforms();
                EntityVertexBridge.VertexSamplerPlan vertexSamplers = vertexSamplerPlan(interfacePlan, stage, vertex);
                conversion = name.equals("gbuffers_damagedblock")
                        ? EntityVertexBridge.convertCrumblingVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers)
                        : FamilyAdapterRegistry.isBlockFamily(name)
                        ? EntityVertexBridge.convertBlockVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers)
                        : FamilyAdapterRegistry.isHandFamily(name)
                        ? EntityVertexBridge.convertHandVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers)
                        : EntityVertexBridge.convertEntityVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers);
            } catch (IllegalArgumentException failure) {
                deviations.add(entityBridgeDeviation(name, true));
                deviations.add(entityConversionDeviation(name, failure));
            }
            if (conversion == null) {
                executable = false;
            } else {
                convertedVertex = conversion.source();
                vertexLayout = conversion.layout();
                deviations.add(entityBridgeDeviation(name, false));
            }
        }

        /** Converts the fragment stage, and the vertex stage of the families converted as a pair. */
        private void convertFragment() {
            if (!executable) {
                return;
            }
            if (PostTargetPlan.isPostProgramName(name)) {
                convertPost();
            } else if (name.equals("gbuffers_terrain") || name.equals("gbuffers_water")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                acceptFragment(FragmentConverter.FragmentConversionRequest
                        .of(fragment, fragmentFile(), true, slots)
                        .withTerrainLayout(vertexLayout)
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withGeometryOutputPlan(geometryOutputPlan)
                        .withPackConstants(packConstants())
                        .withAtlasSamplers(PackResourcePlan.terrainAtlasSamplers(name,
                                interfacePlan.project("fragment", stage), config == null ? null : config.settings()))
                        .withAlphaTestPlan(vertex != null ? alphaTestPlan : PackAlphaTestPlan.disabled())
                        .convert(), "POST_CONVERTER_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isShadowFamily(name)) {
                int[] slots = PackPipelines.shadowSamplerSlots(interfaceSlots(interfacePlan, stage));
                acceptFragment(FragmentConverter.FragmentConversionRequest
                        .of(fragment, fragmentFile(), true, slots)
                        .withTerrainLayout(vertexLayout)
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withPackConstants(packConstants())
                        .convert(), "POST_CONVERTER_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isEntityLike(name)) {
                acceptFragment(FragmentConverter.convertEntityFragment(fragment, fragmentFile(),
                        entitySlots(), vertexLayout, interfacePlan.project("fragment", stage), geometryOutputPlan),
                        "ENTITY_FRAGMENT_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isSkyFamily(name) || FamilyAdapterRegistry.isCloudFamily(name)) {
                acceptFragment(FragmentConverter.convertSkyFragment(fragment, fragmentFile(), entitySlots(),
                                vertexLayout, interfacePlan.project("fragment", stage), geometryOutputPlan),
                        FamilyAdapterRegistry.isCloudFamily(name)
                                ? "CLOUD_FRAGMENT_BRIDGE_UNSUPPORTED" : "SKY_FRAGMENT_BRIDGE_UNSUPPORTED");
            } else if (hostLayoutLane != null) {
                convertHostLayout();
            } else if (FamilyAdapterRegistry.isParticleLike(name) || FamilyAdapterRegistry.isWeatherFamily(name)) {
                convertParticle();
            } else {
                executable = false;
            }
        }

        private int[] entitySlots() {
            return PackPipelines.entitySamplerSlots(interfaceSlots(interfacePlan, stage));
        }

        private void acceptFragment(String converted, String unsupported) {
            convertedFragment = converted;
            if (converted == null) {
                deviations.add(unsupported);
                executable = false;
            }
        }

        private void convertPost() {
            if (pairedPostVertex) {
                PostVertexBridge.PostVertexConversion postVertex = PostVertexBridge.convertPostVertex(
                        vertex, stageInterfaces.get("vertex"), stageInterfaces.get("fragment"), stageMatch,
                        interfacePlan.project("vertex", UniformRegistry.Stage.POST), packConstants());
                deviations.addAll(postVertex.deviations());
                if (postVertex.source() == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                } else {
                    convertedVertex = postVertex.source();
                }
            }
            if (executable) {
                PostVertexBridge.PostVaryingLayout postLayout = !pairedPostVertex ? null
                        : new PostVertexBridge.PostVaryingLayout(
                        stageMatch.locations(), postVaryingTypes(stageInterfaces.get("fragment")));
                convertedFragment = FragmentConverter.convertPostFragment(
                        fragment, fragmentFile(), interfacePlan.project("fragment", UniformRegistry.Stage.POST),
                        targetPlan, packConstants(),
                        postLayout == null ? null : postLayout.locations(),
                        postLayout == null ? null : postLayout.types());
            }
            if (convertedFragment == null) {
                if (!pairedPostVertex) deviations.addAll(PostVertexBridge.postVaryingDeviations(fragment));
                deviations.add("POST_CONVERTER_UNSUPPORTED");
                executable = false;
            }
        }

        private void convertHostLayout() {
            LegacyGlslConverter.TerrainVertexConversion conversion = null;
            try {
                // The line and glint preparation already expanded the includes.
                conversion = HostLayoutVertexBridge.convertHostLayoutVertexChecked(
                        familyAdapter.vertexContract(), vertex, fragment, stageMatch.locations(),
                        allowStorageBuffers, interfacePlan.effective(stage).executableUniforms(),
                        vertexSamplerPlan(interfacePlan, stage, vertex));
            } catch (IllegalArgumentException failure) {
                deviations.add(hostLayoutLane + "_VERTEX_CONVERSION_FAILED:" + safeReason(failure));
            }
            acceptVertex(conversion, hostLayoutLane + "_VERTEX_BRIDGE", hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
            if (conversion != null) {
                acceptFragment(FragmentConverter.convertParticleFragment(fragment, fragmentFile(), entitySlots(),
                                vertexLayout, interfacePlan.project("fragment", stage), geometryOutputPlan),
                        hostLayoutLane + "_FRAGMENT_BRIDGE_UNSUPPORTED");
            }
        }

        private void convertParticle() {
            String label = FamilyAdapterRegistry.isWeatherFamily(name) ? "WEATHER" : "PARTICLE";
            LegacyGlslConverter.TerrainVertexConversion conversion = EntityVertexBridge.convertParticleVertexChecked(
                    vertex, vertexFile(), fragment, stageMatch.locations(), allowStorageBuffers,
                    interfacePlan.effective(stage).executableUniforms(),
                            vertexSamplerPlan(interfacePlan, stage, vertex));
            acceptVertex(conversion, null, label + "_VERTEX_BRIDGE_UNSUPPORTED");
            if (conversion != null) {
                acceptFragment(FragmentConverter.convertParticleFragment(fragment, fragmentFile(), entitySlots(),
                                vertexLayout, interfacePlan.project("fragment", stage), geometryOutputPlan),
                        label + "_FRAGMENT_BRIDGE_UNSUPPORTED");
            }
        }

        private void rewriteStorageBuffers() {
            if (!executable || advancedResources == null || advancedResources.storageBuffers(name).isEmpty()) {
                return;
            }
            if (!advancedResources.storageBufferProgramSupported(name)) {
                deviations.addAll(advancedResources.storageBuffers(name).stream()
                        .flatMap(value -> value.deviations().stream()).toList());
                executable = false;
                return;
            }
            convertedFragment = advancedResources.rewriteStorageBuffers(name, convertedFragment);
            if (convertedVertex != null) {
                convertedVertex = advancedResources.rewriteStorageBuffers(name, convertedVertex);
            }
            if (convertedFragment == null || (program.vertexSource() != null && convertedVertex == null)) {
                deviations.add("STORAGE_BUFFER_REWRITE_FAILED:" + name);
                executable = false;
            }
        }

        private void applyResolution() {
            PackProgramResolution resolved = resolution == null ? null : resolution.resolution(name);
            if (resolved != null) {
                deviations.addAll(resolved.deviations());
                if (!resolved.enabled() || !resolved.executable()) {
                    executable = false;
                }
            }
        }

        private void addAlphaTestDeviation() {
            if (!name.equals("gbuffers_terrain") && !name.equals("gbuffers_water")) {
                return;
            }
            if (executable && vertex != null) {
                String applied = alphaTestPlan.appliedDeviation(name);
                if (!applied.isBlank()) deviations.add(applied);
            } else if ((vertex == null || name.equals("gbuffers_water"))
                    && alphaTestPlan.configured()
                    && (alphaTestPlan.active() || !alphaTestPlan.valid())) {
                deviations.add("ALPHA_TEST_PLANNED_NOT_INSTALLED:" + name);
            }
        }

        /**
         * Blend directives reach the per-attachment state of the world MRT pipelines; other
         * programs keep the directive as a named gap.
         */
        private PackBlendPlan blendPlan() {
            PackBlendPlan blendPlan = PackBlendPlan.forProgram(directiveProgram, settings()).withIrisDefault(name);
            if (blendPlan.overridesAnything() && geometryOutputPlan == null) {
                deviations.add("BLEND_DIRECTIVE_UNSUPPORTED:" + name);
                return PackBlendPlan.empty();
            }
            deviations.addAll(blendPlan.deviations());
            return blendPlan;
        }
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
                result.put(declaration.name(), declaration.typeWithArray());
            }
        }
        return result;
    }

    private static boolean isTerrainLike(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || name.equals("shadow");
    }

    /** Programs whose modern sources are normalized like terrain: terrain, water and both shadow lanes. */
    private static boolean isTerrainMaterialFamily(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || FamilyAdapterRegistry.isShadowFamily(name);
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
                || FamilyAdapterRegistry.isHostLayoutFamily(name)
                || FamilyAdapterRegistry.isSkyFamily(name)
                || name.equals("gbuffers_terrain")
                || name.equals("gbuffers_water");
    }

    private static String vertexBridgeDeviation(String name) {
        return switch (name) {
            case "shadow" -> "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
            case "shadow_entities" -> "SHADOW_ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_water" -> "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_particles", "gbuffers_particles_translucent"
                    -> "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_block", "gbuffers_damagedblock"
                    -> "BLOCK_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_hand", "gbuffers_hand_water"
                    -> "HAND_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_entities", "gbuffers_entities_translucent",
                    "gbuffers_spidereyes" -> "ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
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
            String normalized = PostVertexBridge.normalizeModernPost(prepared.source());
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
                value.source() != null && TerrainVertexBridge.requiresExtendedTerrain(value.source()));
        if (!extended) {
            return result;
        }
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            if (prepared.source() == null || !TerrainVertexBridge.requiresExtendedTerrain(prepared.source())) {
                continue;
            }
            String normalized;
            try {
                normalized = TerrainVertexBridge.normalizeModernTerrain(prepared.source());
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

    /**
     * Iris TransformPatcher compiles a stage in core-profile mode for a core profile and for 150
     * and above with no profile, serving the core names whether or not the stage declares them.
     * Iris also uses that mode for any program on the line layout; there a compatibility stage
     * only gets the core names it declares, so its own variables of those names stay its own.
     */
    private static boolean irisCoreMode(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.irisCore();
    }

    /** Post stages in modern syntax: GLSL 140 and later, where in/out and layouts appear. */
    private static boolean hasModernVersion(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.supported() && version.number() >= 140;
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

    private static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "source";
        }
        return message.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    /** The program's samplers as its vertex stage declares them, at the fragment stage's bindings. */
    private static EntityVertexBridge.VertexSamplerPlan vertexSamplerPlan(
            UniformRegistry.ProgramInterfacePlan plan,
            UniformRegistry.Stage stage,
            String vertex
    ) {
        if (vertex == null) return null;
        return new EntityVertexBridge.VertexSamplerPlan(plan.project("vertex", stage),
                PackPipelines.entitySamplerSlots(interfaceSlots(plan, stage)));
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
