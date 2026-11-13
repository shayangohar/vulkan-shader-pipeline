package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the one load-time source, interface, and translation plan. */
public final class PackPlanBuilder {
    private static final Logger LOGGER = LoggerFactory.getLogger("chimera");
    /** The deviation of a program whose planning threw; its argument is the failure (failureReason). */
    public static final String PLANNING_FAILED = "PROGRAM_PLANNING_FAILED";

    private PackPlanBuilder() {}

    public static PackPlan build(List<PackProgram> programs, PackConfig.PackConfigData config) {
        return build(programs, config, PackEntityIdResolver.empty());
    }

    public static PackPlan build(List<PackProgram> programs, PackConfig.PackConfigData config,
                                 PackEntityIdResolver entityIds) {
        return build(programs, config, entityIds, PackResolutionPlan.empty());
    }

    public static PackPlan build(List<PackProgram> programs, PackConfig.PackConfigData config,
                                 PackEntityIdResolver entityIds, PackResolutionPlan resolution) {
        return build(programs, config, entityIds, resolution, PackAdvancedResourcePlan.empty());
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
            // Each program plans from the shared, unchanging inputs alone, so the workers share them.
            plans.addAll(PackWorkers.map((resolution == null ? PackResolutionPlan.empty() : resolution)
                    .executablePrograms(programs), program -> build(program, config, resolution, advancedResources)));
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
                    InterfacePlanner.planProgram("", null, UniformRegistry.Stage.POST, null, true),
                    Map.of(), Map.of(), null, null, null, null,
                    List.of("PROGRAM_SOURCE_MISSING"), false);
        }
        try {
            return new ProgramBuild(program, config, resolution, advancedResources).plan();
        } catch (RuntimeException | StackOverflowError failure) {
            return planningFailed(program, config, failure);
        }
    }

    /**
     * The plan of a program whose planning threw: it does not run, and the rest of the pack plans
     * on (KNOW-142). The throw is a Chimera defect, so the corpus gate fails on this deviation
     * (PackCompileTest). A stack overflow, from a source that recursed too deep, unwinds clean and
     * falls back the same way. The plan keeps the targets its fragment declares where they still
     * parse, as a program refused for any other reason does.
     */
    static PackProgramPlan planningFailed(PackProgram program, PackConfig.PackConfigData config,
                                          Throwable failure) {
        String name = program.name();
        LOGGER.warn("[chimera] {} skipped: planning failed ({})", name, failure.toString());
        LOGGER.debug("[chimera] {} planning failure", name, failure);
        PostTargetPlan targets;
        try {
            targets = PostTargetPlan.forFullscreenProgram(name, program.executableFragmentSource(), config);
        } catch (RuntimeException unparsed) {
            targets = null;
        }
        return new PackProgramPlan(program, Map.of(),
                InterfacePlanner.planProgram("", null, FamilyAdapterRegistry.stageFor(name), null, true),
                Map.of(), Map.of(), targets, null, null, null,
                List.of(PLANNING_FAILED + ":" + failureReason(failure)), false);
    }

    /** A thrown failure as a deviation argument: its type, then its message's word characters. */
    static String failureReason(Throwable failure) {
        String message = failure.getMessage();
        String reason = failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : "_" + message.replaceAll("[^A-Za-z0-9_.-]+", "_"));
        return reason.length() <= 160 ? reason : reason.substring(0, 160);
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
        private GeometryOutputPlan geometryOutputPlan;
        private final String directiveProgram;
        /** The deviation label of a host-layout lane (LINE, GLINT, SHADOW_LINES, ...), or null. */
        private final String hostLayoutLane;
        private final PackAlphaTestPlan alphaTestPlan;
        private final boolean allowStorageBuffers;
        /** The geometry and tessellation stages Iris would run between this program's stages. */
        private final List<String> geometryStages;
        private final List<String> deviations = new ArrayList<>();

        private FamilyAdapterPlan familyAdapter;
        private String fragment;
        private String vertex;
        /** The prepared geometry stage, run between the vertex and fragment stages; null without one. */
        private String geometry;
        /** The prepared tessellation stages, run between the vertex and fragment stages; null without them. */
        private String tessControl;
        private String tessEvaluation;
        private String hostLayoutFailure;
        private UniformRegistry.ProgramInterfacePlan interfacePlan;
        private List<String> unboundShadowSamplers = List.of();
        private final Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces = new TreeMap<>();
        private boolean pairedPostVertex;
        private GlslInterfaceScanner.ProgramMatch stageMatch;
        private boolean executable;
        private String convertedFragment;
        private String convertedVertex;
        private String convertedGeometry;
        private TessellationStageBridge.Conversion convertedTessellation;
        private LegacyGlslConverter.TerrainVaryingLayout vertexLayout;
        /** The geometry stage's output locations, which the fragment stage reads from; null without one. */
        private LegacyGlslConverter.TerrainVaryingLayout geometryLayout;

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
            this.stages = prepareStages(program, name,
                    FamilyAdapterRegistry.isTerrainMaterialFamily(name), settings().readsTessellation());
            this.fragment = stageSource(stages, "fragment", program.executableFragmentSource());
            this.vertex = stageSource(stages, "vertex", program.executableVertexSource());
            this.geometry = stageSource(stages, "geometry", null);
            this.tessControl = stageSource(stages, "tess_control", null);
            this.tessEvaluation = stageSource(stages, "tess_evaluation", null);
            this.familyAdapter = FamilyAdapterRegistry.forProgram(name);
            this.terrainMaterial = TerrainMaterialPlan.forProgram(name, vertex, fragment);
            this.preparedSnapshot = !program.preparedSources().isEmpty();
            this.stage = FamilyAdapterRegistry.stageFor(name);
            Map<Integer, Integer> formats = config == null ? Map.of() : config.colortexFormats();
            this.targetPlan = PostTargetPlan.forFullscreenProgram(name, fragment, config);
            this.geometryOutputPlan = FamilyAdapterRegistry.isGeometryOutputFamily(name)
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
            this.geometryStages = program.geometryStages(settings().readsTessellation());
        }

        PackProgramPlan plan() {
            prepareIrisCoreProfile();
            prepareHostLayout();
            renameHostNameCollisions();
            repairStageInterfaces();
            injectAlphaTest();
            planInterface();
            collectPlanningDeviations();
            executable = admitted();
            try {
                convertVertex();
                if (executable && FamilyAdapterRegistry.isEntityLike(name) && vertex != null) {
                    convertEntityVertex();
                }
                convertTessellation();
                convertGeometry();
                convertFragment();
            } catch (RuntimeException e) {
                deviations.add("TRANSLATION_UNSUPPORTED:" + VertexBridgeDeviations.safeReason(e));
                executable = false;
            }
            rewriteStorageBuffers();
            applyResolution();
            addAlphaTestDeviation();
            PackBlendPlan blendPlan = blendPlan();
            return new PackProgramPlan(program, stages, interfacePlan, stageInterfaces,
                    varyingLocations(), targetPlan, convertedFragment, convertedVertex,
                    vertexLayout, deviations, executable,
                    familyAdapter, terrainMaterial, geometryOutputPlan, alphaTestPlan, blendPlan, shadowAttachments(),
                    new BetweenStages(convertedTessellation == null ? null : convertedTessellation.control(),
                            convertedTessellation == null ? null : convertedTessellation.evaluation(),
                            convertedGeometry));
        }

        /** The shadowcolor targets the shadow pass draws: Iris's two, or up to eight with HIGHER_SHADOWCOLOR. */
        private int shadowTargetCount() {
            return shadowAttachments().isEmpty() ? ShadowTargetDirectives.LEGACY_COLOR_TARGETS
                    : shadowAttachments().size();
        }

        /** The shadow pass's colour attachment formats, which every shadow-stage pipeline declares. */
        private List<Integer> shadowAttachments() {
            return stage.shadowPass() && config != null ? config.shadowSettings().colorAttachmentFormats() : List.of();
        }

        private PackSettingsPlan settings() {
            return config == null ? PackSettingsPlan.empty() : config.settings();
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
         * Iris core-profile names (vaPosition, modelViewMatrix) become the compatibility built-ins every converter
         * serves. A pack's own gbuffers_line vertex keeps Iris's unbound UVs (prepareLineVertex). A fullscreen pass
         * reads centerDepthSmooth from Iris's smoothed texture (CenterDepthSmooth). An unknown sampler2D reads unit 0:
         * colortex0 in a fullscreen pass, the albedo texture in a world program (UnitZeroSampler).
         */
        private void prepareIrisCoreProfile() {
            String overlay = stage == UniformRegistry.Stage.POST
                    || FamilyAdapterRegistry.isTerrainMaterialFamily(name) ? IrisCoreProfile.NO_OVERLAY : "UV1";
            IrisCoreProfile.Patch patch = irisPatch();
            // Iris's core transformers serve the core names in every stage (IrisCoreProfile.servedUniforms).
            eachStage((programStage, source) -> {
                boolean vertexStage = programStage == GlslInterfaceScanner.Stage.VERTEX;
                if (vertexStage && patch == IrisCoreProfile.Patch.LINES) return source;
                return IrisCoreProfile.prepareIrisCoreProfile(source, stageFile(programStage), vertexStage, overlay,
                        IrisCoreProfile.coreMode(sourceAsWritten(programStage)), patch);
            });
            if (patch == IrisCoreProfile.Patch.SODIUM) vertex = IrisCoreProfile.withChunkFade(vertex);
            if (stage.shadowPass()) {
                fragment = PostTargetPlan.withShadowTargetsWithin(fragment, shadowTargetCount(), deviations);
            }
            eachStage((programStage, source) ->
                    UnitZeroSampler.apply(CenterDepthSmooth.apply(source, name), config, deviations, name));
        }

        /**
         * Runs one preparation over every stage the program has, in the order GL links them. A
         * preparation written stage by stage missed the stages added after it (KNOW-847).
         */
        private void eachStage(BiFunction<GlslInterfaceScanner.Stage, String, String> preparation) {
            for (GlslInterfaceScanner.Stage programStage : GlslInterfaceScanner.Stage.values()) {
                String source = source(programStage);
                if (source != null) setSource(programStage, preparation.apply(programStage, source));
            }
        }

        /** A stage's source as prepared so far; null when the program has no such stage. */
        private String source(GlslInterfaceScanner.Stage programStage) {
            return switch (programStage) {
                case VERTEX -> vertex;
                case TESS_CONTROL -> tessControl;
                case TESS_EVALUATION -> tessEvaluation;
                case GEOMETRY -> geometry;
                case FRAGMENT -> fragment;
            };
        }

        private void setSource(GlslInterfaceScanner.Stage programStage, String source) {
            switch (programStage) {
                case VERTEX -> vertex = source;
                case TESS_CONTROL -> tessControl = source;
                case TESS_EVALUATION -> tessEvaluation = source;
                case GEOMETRY -> geometry = source;
                case FRAGMENT -> fragment = source;
            }
        }

        /**
         * A stage's source as the pack wrote it, where Iris reads whether the stage compiles in core
         * mode: before the modern post and terrain normalizations drop its version line.
         */
        private String sourceAsWritten(GlslInterfaceScanner.Stage programStage) {
            return switch (programStage) {
                case VERTEX -> program.executableVertexSource();
                case FRAGMENT -> program.executableFragmentSource();
                default -> {
                    PreparedShaderSource prepared = program.preparedSources().get(programStage.programStage());
                    yield prepared == null ? source(programStage) : prepared.source();
                }
            };
        }

        /**
         * The file a stage's includes resolve against. The stages between vertex and fragment are
         * prepared with their includes already expanded, as is every stage of a prepared snapshot.
         */
        private Path stageFile(GlslInterfaceScanner.Stage programStage) {
            return switch (programStage) {
                case VERTEX -> vertexFile();
                case FRAGMENT -> fragmentFile();
                default -> null;
            };
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
                fragment = IrisCoreProfile.prepareHostLayoutSource(fragment, fragmentFile());
                if (!FamilyAdapterRegistry.readsEntityIdsPerVertex(name)) {
                    eachStage((programStage, source) -> IrisCoreProfile.prepareHostLayoutIdentity(source));
                }
            } catch (RuntimeException failure) {
                hostLayoutFailure = VertexBridgeDeviations.safeReason(failure);
            }
        }

        /**
         * The entity-style vertex preambles declare the host's inputs under vanilla's core names
         * (GlslVertexPreambles.hostNameCollisions). A pack identifier of such a name is renamed in
         * every stage before their interfaces are matched, so a varying keeps its pair: Mellow's
         * entities and Reverie's shadow lanes write their own Normal, and a tessellated hand's
         * control stage reads it.
         */
        private void renameHostNameCollisions() {
            if (vertex == null || !usesEntityPreamble()) return;
            Map<String, String> renames = GlslVertexPreambles.hostNameCollisions(vertex);
            if (renames.isEmpty()) return;
            eachStage((programStage, source) -> GlslTokenRewriter.replaceIdentifiers(source, renames));
        }

        /** The lanes whose vertex preamble declares the host's inputs under vanilla's core names. */
        private boolean usesEntityPreamble() {
            return FamilyAdapterRegistry.isEntityLike(name) || FamilyAdapterRegistry.isParticleLike(name)
                    || FamilyAdapterRegistry.isWeatherFamily(name) || FamilyAdapterRegistry.isGlintFamily(name)
                    || FamilyAdapterRegistry.isSkyFamily(name) || FamilyAdapterRegistry.isCloudFamily(name)
                    || hostLayoutLane != null || FamilyAdapterRegistry.isShadowLaneFamily(name)
                    || FamilyAdapterRegistry.isShadowEntityFamily(name);
        }

        /**
         * Iris repairs every pair of adjacent stages, in the order GL links them, before anything
         * matches their interfaces (CompatibilityTransformer.transformGrouped): vertex and fragment,
         * or each pair through a geometry or tessellation stage between them. Chimera repaired only
         * a vertex/fragment pair, and refused a geometry program with an unwritten input Iris runs.
         */
        private void repairStageInterfaces() {
            GlslInterfaceScanner.Stage earlier = null;
            for (GlslInterfaceScanner.Stage later : GlslInterfaceScanner.Stage.values()) {
                if (source(later) == null) continue;
                if (earlier != null) {
                    CrossStageRepair.Result repaired = CrossStageRepair.repair(source(earlier), earlier,
                            source(later), later);
                    setSource(earlier, repaired.earlier());
                    deviations.addAll(repaired.deviations());
                }
                earlier = later;
            }
        }

        /**
         * Serves the stage values Iris fixes per program (servedStageValues). Only a program that
         * can execute (entity families need a vertex stage) receives the host alpha test.
         */
        private void injectAlphaTest() {
            // Every stage reads Iris's values alike.
            eachStage((programStage, source) -> servedStageValues(source, stageFile(programStage)));
            if (vertex == null) {
                return;
            }
            boolean irisTests = irisAppendsAlphaTest();
            if (FamilyAdapterRegistry.isEntityLike(name) || FamilyAdapterRegistry.isParticleLike(name)
                    || FamilyAdapterRegistry.isWeatherFamily(name) || FamilyAdapterRegistry.isGlintFamily(name)) {
                // Iris draws ENTITIES_ALPHA (the dying dragon) with gbuffers_entities alone.
                if (irisTests) fragment = alphaTestPlan.injectDrawTest(fragment, name.equals("gbuffers_entities"));
                if (FamilyAdapterRegistry.isEntityLike(name) && !name.equals("gbuffers_damagedblock")) {
                    fragment = EntityOverlayColor.inject(fragment);
                    vertex = EntityOverlayColor.withoutUnreadVertexDeclaration(vertex);
                }
            } else if (irisTests && (!FamilyAdapterRegistry.isCloudFamily(name)
                    || !AuthoredOutput.producesNothing(vertex, fragment))) {
                // Any other program tests as its plan says (Iris ShaderCreator: the pack's
                // alphaTest.<program>, else its key's), and an untested one is left alone. Terrain
                // and shadow terrain test by layer and entity casters at 0.1, set per layer by the
                // main and shadow passes (WorldRendererMixin, PackShadowPass); the sky by format
                // (ChimeraSkyBridge); text backgrounds by their lane (TEXT_BG); clouds at a fixed
                // reference. A cloud program that draws nothing keeps its discarding main, which is
                // how the plan proves it (AuthoredOutput).
                fragment = alphaTestPlan.injectDrawTest(fragment);
            }
            if (!FamilyAdapterRegistry.isShadowEntityFamily(name)) return;
            var served = EntityOverlayColor.injectShadow(vertex, geometry, fragment, geometryStageRuns());
            vertex = served.vertex();
            geometry = served.geometry();
            fragment = served.fragment();
        }

        /**
         * Whether Iris appends the alpha test to this program's fragment: only in compatibility
         * mode (CommonTransformer.transform's {@code !core}; the Sodium and composite core
         * transformers never call it). A core-profile fragment, and every stage of a pack's own
         * gbuffers_line, tests itself against alphaTestRef.
         */
        private boolean irisAppendsAlphaTest() {
            return fragment != null && !IrisCoreProfile.coreMode(program.executableFragmentSource())
                    && irisPatch() != IrisCoreProfile.Patch.LINES;
        }

        /** The Iris patch that compiles this program (IrisCoreProfile.patchFor). */
        private IrisCoreProfile.Patch irisPatch() {
            return IrisCoreProfile.patchFor(name, stage, lineLayout() && directiveProgram.equals("gbuffers_line"));
        }

        /**
         * A stage with the values Iris fixes for its program served into it, its includes expanded first. A world
         * program's alphaTestRef reads its alpha-test reference; a post program reads the constants of a fullscreen
         * pass (ServedUniforms.servePostValues) and keeps refusing alphaTestRef, which Iris leaves the last world
         * draw's (UniformRegistry.IRIS_UNSERVED). A uniform Iris never sets reads its initializer
         * (ServedUniforms.readInitializers). A stage whose includes do not resolve is left for the converters to
         * refuse.
         */
        private String servedStageValues(String source, Path file) {
            if (source == null) return null;
            boolean post = stage == UniformRegistry.Stage.POST;
            List<String> names = post ? ServedUniforms.POST_VALUES : List.of(PackAlphaTestPlan.IRIS_REFERENCE);
            try {
                ServedUniforms.Initialized initialized = ServedUniforms.readInitializers(
                        IrisCoreProfile.expandIncludes(source, file),
                        value -> UniformRegistry.irisSets(value, settings().runtimeSettings().customDescriptors()));
                initialized.names().forEach(value -> deviations.add("UNIFORM_UNSET_INITIALIZER:" + value));
                String expanded = initialized.source();
                if (names.stream().noneMatch(served -> GlslTokenRewriter.containsIdentifier(expanded, served))) {
                    return initialized.names().isEmpty() ? source : expanded;
                }
                // FinalPassRenderer and ShadowCompositeRenderer apply no blend directive.
                boolean blends = !directiveProgram.equals("final") && !directiveProgram.startsWith("shadowcomp");
                return post ? ServedUniforms.servePostValues(expanded,
                        blends ? PackBlendPlan.forProgram(directiveProgram, settings()).program() : null)
                        : alphaTestPlan.serveReference(expanded);
            } catch (IllegalArgumentException unresolved) {
                return source;
            }
        }

        private void planInterface() {
            PackSettingsPlan settings = config == null ? null : config.settings();
            Map<String, String> between = runningBetweenStages();
            interfacePlan = InterfacePlanner.planProgram(
                    fragment, vertex, between, stage, targetPlan, !program.variantFolder().isBlank(),
                    settings == null ? Map.of() : settings.runtimeSettings().customDescriptors(),
                    settings == null ? Map.of() : settings.customSamplerSlots());
            unboundShadowSamplers = ShadowTargetDirectives.unboundShadowSamplers(interfacePlan.samplers(), config,
                    this::readsSampler);
            List<GlslInterfaceScanner.StageInterface> chain = new ArrayList<>();
            if (vertex != null) {
                stageInterfaces.put("vertex", GlslInterfaceScanner.scan(vertex, true));
                chain.add(stageInterfaces.get("vertex"));
            }
            between.forEach((name, source) -> {
                stageInterfaces.put(name, GlslInterfaceScanner.scan(source, GlslInterfaceScanner.Stage.of(name)));
                chain.add(stageInterfaces.get(name));
            });
            if (fragment != null) {
                stageInterfaces.put("fragment", GlslInterfaceScanner.scan(fragment, false));
            }
            chain.add(stageInterfaces.get("fragment"));
            pairedPostVertex = stage == UniformRegistry.Stage.POST && vertex != null;
            boolean reconcileStages = vertex != null && (stage != UniformRegistry.Stage.POST || pairedPostVertex);
            stageMatch = !reconcileStages
                    ? new GlslInterfaceScanner.ProgramMatch(Map.of(), List.of())
                    : GlslInterfaceScanner.match(chain);
        }

        /** The stages that run between the vertex and fragment stages, by stage name, in the order they run. */
        private Map<String, String> runningBetweenStages() {
            Map<String, String> between = new LinkedHashMap<>();
            if (tessellationRuns()) {
                between.put("tess_control", tessControl);
                between.put("tess_evaluation", tessEvaluation);
            }
            if (geometryStageRuns()) between.put("geometry", geometry);
            return between;
        }

        /** Whether any stage reads {@code sampler}; GL leaves one nothing reads inactive, bound or not. */
        private boolean readsSampler(String sampler) {
            return fragment != null && !InterfacePlanner.isSamplerUnused(fragment, sampler)
                    || vertex != null && !InterfacePlanner.isSamplerUnused(vertex, sampler)
                    || runningBetweenStages().values().stream()
                            .anyMatch(source -> !InterfacePlanner.isSamplerUnused(source, sampler));
        }

        private Map<String, Integer> varyingLocations() {
            GlslInterfaceScanner.StageInterface fragmentInterface = stageInterfaces.get("fragment");
            return stage != UniformRegistry.Stage.POST || pairedPostVertex ? stageMatch.locations()
                    : fragmentInterface == null ? Map.of() : fragmentInterface.readInputLocations();
        }

        private void collectPlanningDeviations() {
            deviations.addAll(program.preparationDeviations());
            stages.values().forEach(value -> deviations.addAll(value.deviations()));
            deviations.addAll(interfacePlan.deviations());
            deviations.addAll(unboundShadowSamplers);
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
            // Every check runs, so the report names every reason a program is refused.
            boolean admitted = geometryStages.isEmpty() || geometryStageRuns() || tessellationRuns();
            if (!admitted) {
                // A lane resolved to such a program (shadow_lines to Spring's shadow) too.
                deviations.add("UNSUPPORTED_PACK_STAGE");
            }
            admitted &= interfaceAdmitted();
            admitted &= targetsAdmitted();
            admitted &= storageAdmitted();
            admitted &= vertexAdmitted();
            return admitted;
        }

        /**
         * Whether the program's stages between vertex and fragment run: a geometry stage alone, on
         * every shadow lane, on the hand lanes, and on the terrain or water lane after a modern
         * vertex stage (Spring's gbuffers_terrain and gbuffers_hand).
         */
        private boolean geometryStageRuns() {
            return geometry != null && geometryStages.equals(List.of("geometry"))
                    && (FamilyAdapterRegistry.isShadowFamily(name) || FamilyAdapterRegistry.isHandFamily(name)
                    || modernWorldTerrain());
        }

        /**
         * Whether the program's tessellation stages run: both alone, on the hand lanes, or on the terrain or water lane
         * after a modern vertex stage (TessellationStageBridge). The shadow lanes stay refused.
         */
        private boolean tessellationRuns() {
            return tessControl != null && tessEvaluation != null && geometryStages.equals(List.of("tess_control",
                    "tess_evaluation")) && (FamilyAdapterRegistry.isHandFamily(name) || modernWorldTerrain());
        }

        /** The terrain or water lane, drawn after a modern vertex stage. */
        private boolean modernWorldTerrain() {
            return terrainMaterial.modern() && (name.equals("gbuffers_terrain") || name.equals("gbuffers_water"));
        }

        /**
         * What the vertex stage feeds: a running geometry or tessellation control stage, after which
         * a later stage converts the clip depth, or the fragment.
         */
        private String vertexConsumer() {
            if (tessellationRuns()) return TessellationStageBridge.consumerView(tessControl);
            return geometryStageRuns() ? GeometryStageBridge.consumerView(geometry, fragment) : fragment;
        }

        /** Converts the tessellation stages that run between the converted vertex stage and the fragment stage. */
        private void convertTessellation() {
            if (!executable || !tessellationRuns()) return;
            try {
                convertedTessellation = TessellationStageBridge.convert(FamilyAdapterRegistry.isHandFamily(name),
                        tessControl, tessEvaluation, vertexLayout, fragment,
                        interfacePlan.project("tess_control", stage), interfacePlan.project("tess_evaluation", stage),
                        allowStorageBuffers);
            } catch (RuntimeException failure) {
                deviations.add(VertexBridgeDeviations.conversionFailure("TESSELLATION_STAGE_BRIDGE_UNSUPPORTED",
                        failure));
                deviations.add("TESSELLATION_STAGE_BRIDGE_UNSUPPORTED");
                executable = false;
            }
        }

        /** Converts the geometry stage that runs between the converted vertex stage and the fragment stage. */
        private void convertGeometry() {
            if (!executable || !geometryStageRuns()) return;
            try {
                UniformRegistry.ProgramInterface geometryPlan = interfacePlan.project("geometry", stage);
                GeometryStageBridge.Conversion conversion = FamilyAdapterRegistry.isShadowFamily(name)
                        ? GeometryStageBridge.convertShadow(geometry, vertexLayout, fragment, geometryPlan,
                                allowStorageBuffers, !FamilyAdapterRegistry.isShadowTerrain(name))
                        : GeometryStageBridge.convertWorld(geometry, vertexLayout, fragment, geometryPlan,
                                allowStorageBuffers, FamilyAdapterRegistry.isHandFamily(name));
                convertedGeometry = conversion.source();
                geometryLayout = conversion.outputLayout();
            } catch (RuntimeException failure) {
                deviations.add(VertexBridgeDeviations.conversionFailure("GEOMETRY_STAGE_BRIDGE_UNSUPPORTED", failure));
                deviations.add("GEOMETRY_STAGE_BRIDGE_UNSUPPORTED");
                executable = false;
            }
        }

        /** The family, stages, interface, targets and alpha test each allow the program. */
        private boolean interfaceAdmitted() {
            return FamilyAdapterRegistry.isExecutableFamily(name)
                    && fragment != null
                    && (!needsVertex() || vertex != null)
                    && stages.values().stream().allMatch(PackPlanBuilder::preparedSuccessfully)
                    && interfacePlan.executable()
                    && unboundShadowSamplers.isEmpty()
                    && stageInterfaces.values().stream().allMatch(value -> value.deviations().isEmpty())
                    && stageMatch.executable()
                    && (targetPlan == null || targetPlan.executable())
                    && (geometryOutputPlan == null || geometryOutputPlan.executable());
        }

        /** The families that draw only through their own vertex stage. */
        private boolean needsVertex() {
            return FamilyAdapterRegistry.isEntityLike(name)
                    || FamilyAdapterRegistry.isShadowEntityFamily(name)
                    || FamilyAdapterRegistry.isParticleLike(name)
                    || FamilyAdapterRegistry.isWeatherFamily(name)
                    || hostLayoutLane != null
                    || FamilyAdapterRegistry.isSkyFamily(name)
                    || FamilyAdapterRegistry.isCloudFamily(name);
        }

        /** A world draw samples its own outputs through snapshots; shadow outputs are the pack shadowcolor targets. */
        private boolean targetsAdmitted() {
            boolean admitted = true;
            if (geometryOutputPlan != null) {
                // A world draw samples pack targets as shader-read images while its own outputs
                // are colour attachments; one image cannot be both inside a render pass. An
                // output it also samples (BSL's MCBL_SS water reads colortex8) reads a copy.
                // colortex0 is the live HDR image, which has no target-shaped copy.
                List<Integer> snapshots = new ArrayList<>();
                for (UniformRegistry.SamplerBinding sampler : interfacePlan.effective(stage).samplers()) {
                    Integer target = PackResourcePlan.targetIndex(sampler.name());
                    if (target == null || !geometryOutputPlan.targetSlots().contains(target)
                            || snapshots.contains(target)) continue;
                    if (target == 0) {
                        deviations.add("GBUFFER_TARGET_FEEDBACK:colortex0");
                        admitted = false;
                    } else {
                        deviations.add("GBUFFER_TARGET_SNAPSHOT:colortex" + target);
                        snapshots.add(target);
                    }
                }
                geometryOutputPlan = geometryOutputPlan.withSnapshotTargets(snapshots);
            }
            if (stage.shadowPass()) {
                PostTargetPlan shadowOutputs = PostTargetPlan.parseShadow("shadow", fragment, Map.of()).plan();
                for (String deviation : shadowOutputs.deviations()) {
                    deviations.add(deviation.replaceFirst("^POST_", "SHADOW_"));
                }
                admitted &= shadowOutputs.executable();
            }
            return admitted;
        }

        /** A storage block needs a planned buffer, or a supported catalog still to bind it. */
        private boolean storageAdmitted() {
            boolean hasStorageBlock = stages.values().stream()
                    .filter(value -> value != null)
                    .anyMatch(value -> PackAdvancedResourcePlan.containsStorageBlock(value.source()));
            if (hasStorageBlock && (advancedResources == null || advancedResources.storageBuffers(name).isEmpty())) {
                boolean pendingCatalog = advancedResources != null
                        && advancedResources.buffers().values().stream()
                        .anyMatch(PackAdvancedResourcePlan.BufferSpec::supported);
                if (!pendingCatalog) {
                    deviations.add("STORAGE_BUFFER_UNPLANNED:" + name);
                    return false;
                }
            }
            return true;
        }

        /** The vertex stage each family needs: MRT pairs, family bridges, and host layout lanes. */
        private boolean vertexAdmitted() {
            boolean admitted = mrtPairAdmitted();
            if (vertex == null) {
                if (FamilyAdapterRegistry.isEntityLike(name) || FamilyAdapterRegistry.isShadowEntityFamily(name)) {
                    deviations.add(VertexBridgeDeviations.unsupported(name));
                }
                if (FamilyAdapterRegistry.isParticleLike(name)) {
                    deviations.add("PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
                }
                if (FamilyAdapterRegistry.isWeatherFamily(name)) {
                    deviations.add("WEATHER_VERTEX_BRIDGE_UNSUPPORTED");
                }
            }
            admitted &= hostLayoutVertexAdmitted();
            return admitted;
        }

        /** MRT geometry outside the entity, particle and weather families needs its vertex stage. */
        private boolean mrtPairAdmitted() {
            if (geometryOutputPlan != null && geometryOutputPlan.requiresMrt() && vertex == null
                    && !FamilyAdapterRegistry.isEntityLike(name)
                    && !FamilyAdapterRegistry.isParticleLike(name)
                    && !FamilyAdapterRegistry.isWeatherFamily(name)) {
                // MRT geometry requires a complete vertex/fragment pair; the fragment-only
                // single-target adapter stays as it was.
                deviations.add("MRT_NOT_SUPPORTED");
                return false;
            }
            return true;
        }

        /** A host layout lane needs a vertex stage that its preparation did not fail on. */
        private boolean hostLayoutVertexAdmitted() {
            if (hostLayoutLane != null && (vertex == null || hostLayoutFailure != null)) {
                deviations.add(hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
                if (hostLayoutFailure != null) {
                    deviations.add(hostLayoutLane + "_VERTEX_CONVERSION_FAILED:" + hostLayoutFailure);
                }
                return false;
            }
            return true;
        }

        /**
         * Records a vertex conversion, or the deviation that keeps the program from executing with
         * the failure that names why.
         */
        private void acceptVertex(Supplier<LegacyGlslConverter.TerrainVertexConversion> converter,
                                  String bridged, String unsupported) {
            LegacyGlslConverter.TerrainVertexConversion conversion = null;
            try {
                conversion = converter.get();
            } catch (RuntimeException failure) {
                deviations.add(VertexBridgeDeviations.conversionFailure(unsupported, failure));
            }
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
            List<UniformRegistry.UniformDeclaration> fragmentUniforms =
                    interfacePlan.project("fragment", stage).executableUniforms();
            if (FamilyAdapterRegistry.isSkyFamily(name)) {
                FamilyAdapterPlan.VertexContract skyContract =
                        HostLayoutVertexBridge.skyVertexContract(vertex, name.equals("gbuffers_skytextured"));
                familyAdapter = familyAdapter.withVertexContract(skyContract);
                acceptVertex(() -> HostLayoutVertexBridge.convertSkyVertexChecked(vertex, vertexFile(), fragment,
                                skyContract, stageMatch.locations(), allowStorageBuffers, fragmentUniforms,
                                vertexSamplerPlan(interfacePlan, stage, vertex)),
                        "SKY_VERTEX_BRIDGE", "SKY_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isCloudFamily(name) && AuthoredOutput.producesNothing(vertex, fragment)) {
                // The selected branch draws no cloud at all. There is no pipeline to build; the
                // runtime skips the host cloud draw.
                deviations.add(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
                executable = false;
            } else if (FamilyAdapterRegistry.isCloudFamily(name)) {
                familyAdapter = familyAdapter.withVertexContract(
                        FamilyAdapterPlan.VertexContract.CLOUD_POSITION_UV_COLOR_NORMAL);
                acceptVertex(() -> HostLayoutVertexBridge.convertCloudVertexChecked(vertex, vertexFile(), fragment,
                                stageMatch.locations(), allowStorageBuffers, fragmentUniforms,
                                vertexSamplerPlan(interfacePlan, stage, vertex)),
                        "CLOUD_VERTEX_BRIDGE", "CLOUD_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isShadowLaneFamily(name) && hostLayoutFailure == null) {
                // The host-layout preparation already expanded the includes.
                acceptVertex(() -> HostLayoutVertexBridge.convertShadowLaneVertexChecked(
                                familyAdapter.vertexContract(), vertex, vertexConsumer(),
                                interfacePlan.project("vertex", stage), allowStorageBuffers, geometryStageRuns()),
                        hostLayoutLane + "_VERTEX_BRIDGE", hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isShadowEntityFamily(name)) {
                acceptVertex(() -> EntityVertexBridge.convertShadowEntityVertexChecked(
                                vertex, vertexFile(), vertexConsumer(), interfacePlan.project("vertex", stage),
                                allowStorageBuffers, geometryStageRuns()),
                        "SHADOW_ENTITY_VERTEX_BRIDGE", VertexBridgeDeviations.unsupported(name));
            } else if (FamilyAdapterRegistry.isTerrainLike(name)) {
                acceptVertex(this::terrainVertexOrThrow, null, VertexBridgeDeviations.unsupported(name));
            }
        }

        /** The shadow, modern terrain or legacy terrain vertex bridge, throwing its first concrete failure. */
        private LegacyGlslConverter.TerrainVertexConversion terrainVertexOrThrow() {
            UniformRegistry.ProgramInterface vertexInterface = interfacePlan.project("vertex", stage);
            if (FamilyAdapterRegistry.isShadowTerrain(name)) {
                return TerrainVertexBridge.convertModernTerrainVertexOrThrow(vertex, vertexFile(), vertexConsumer(),
                        GlslVertexPreambles.MODERN_SHADOW_VERTEX_PREAMBLE, vertexInterface, true, false,
                        allowStorageBuffers, geometryStageRuns());
            }
            if (terrainMaterial.modern()) {
                return TerrainVertexBridge.convertModernTerrainVertexOrThrow(vertex, null, vertexConsumer(),
                        GlslVertexPreambles.MODERN_TERRAIN_VERTEX_PREAMBLE, vertexInterface, false, false,
                        allowStorageBuffers, tessellationRuns() || geometryStageRuns());
            }
            return TerrainVertexBridge.convertLegacyVertexOrThrow(vertex, vertexFile(), fragment,
                    GlslVertexPreambles.TERRAIN_VERTEX_PREAMBLE, allowStorageBuffers);
        }

        private void convertEntityVertex() {
            LegacyGlslConverter.TerrainVertexConversion conversion = null;
            try {
                List<UniformRegistry.UniformDeclaration> fragmentUniforms =
                        interfacePlan.project("fragment", stage).executableUniforms();
                EntityVertexBridge.VertexSamplerPlan vertexSamplers = vertexSamplerPlan(interfacePlan, stage, vertex);
                conversion = FamilyAdapterRegistry.isBlockFamily(name)
                        ? EntityVertexBridge.convertBlockVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers)
                        : FamilyAdapterRegistry.isHandFamily(name)
                        ? EntityVertexBridge.convertHandVertexChecked(vertex, vertexFile(),
                        new EntityVertexBridge.Consumer(vertexConsumer(), tessellationRuns() || geometryStageRuns()),
                        fragment, stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers)
                        : EntityVertexBridge.convertEntityVertexChecked(vertex, vertexFile(), fragment,
                        stageMatch.locations(), allowStorageBuffers, fragmentUniforms, vertexSamplers);
            } catch (IllegalArgumentException failure) {
                deviations.add(VertexBridgeDeviations.entity(name, true));
                deviations.add(VertexBridgeDeviations.entityConversionFailure(name, failure));
            }
            if (conversion == null) {
                executable = false;
            } else {
                convertedVertex = conversion.source();
                vertexLayout = conversion.layout();
                deviations.add(VertexBridgeDeviations.entity(name, false));
            }
        }

        /** Converts the fragment stage, and the vertex stage of the families converted as a pair. */
        private void convertFragment() {
            if (!executable) {
                return;
            }
            if (PostTargetPlan.isFullscreenProgramName(name)) {
                convertPost();
            } else if (name.equals("gbuffers_terrain") || name.equals("gbuffers_water")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                acceptFragment(FragmentConverter.FragmentConversionRequest
                        .of(fragment, fragmentFile(), true, slots)
                        .withTerrainLayout(fragmentInputLayout())
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .withGeometryOutputPlan(geometryOutputPlan)
                        .withAtlasSamplers(PackResourcePlan.terrainAtlasSamplers(name,
                                interfacePlan.project("fragment", stage), config == null ? null : config.settings()))
                        .convert(), "POST_CONVERTER_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isShadowFamily(name)) {
                int[] slots = PackPipelines.shadowSamplerSlots(interfaceSlots(interfacePlan, stage));
                acceptFragment(FragmentConverter.FragmentConversionRequest
                        .of(fragment, fragmentFile(), true, slots)
                        .withTerrainLayout(fragmentInputLayout())
                        .withInterfacePlan(interfacePlan.project("fragment", stage))
                        .convert(), "POST_CONVERTER_UNSUPPORTED");
            } else if (FamilyAdapterRegistry.isEntityLike(name)) {
                acceptFragment(FragmentConverter.convertEntityFragment(fragment, fragmentFile(), entitySlots(),
                        fragmentInputLayout(), interfacePlan.project("fragment", stage), geometryOutputPlan),
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

        /** The output locations of the stage that feeds the fragment: tessellation, geometry, or the vertex stage. */
        private LegacyGlslConverter.TerrainVaryingLayout fragmentInputLayout() {
            if (convertedTessellation != null) return convertedTessellation.outputLayout();
            return geometryLayout != null ? geometryLayout : vertexLayout;
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
                        interfacePlan.project("vertex", UniformRegistry.Stage.POST));
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
                        stageMatch.locations(), stageInterfaces.get("fragment").readInputTypes());
                convertedFragment = FragmentConverter.convertPostFragment(
                        fragment, fragmentFile(), interfacePlan.project("fragment", UniformRegistry.Stage.POST),
                        targetPlan,
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
            // The line and glint preparation already expanded the includes.
            acceptVertex(() -> HostLayoutVertexBridge.convertHostLayoutVertexChecked(
                            familyAdapter.vertexContract(), vertex, fragment, stageMatch.locations(),
                            allowStorageBuffers, interfacePlan.effective(stage).executableUniforms(),
                            vertexSamplerPlan(interfacePlan, stage, vertex)),
                    hostLayoutLane + "_VERTEX_BRIDGE", hostLayoutLane + "_VERTEX_BRIDGE_UNSUPPORTED");
            convertParticleFragment(hostLayoutLane);
        }

        private void convertParticle() {
            String label = FamilyAdapterRegistry.isWeatherFamily(name) ? "WEATHER" : "PARTICLE";
            acceptVertex(() -> EntityVertexBridge.convertParticleVertexChecked(
                            vertex, vertexFile(), fragment, stageMatch.locations(), allowStorageBuffers,
                            interfacePlan.effective(stage).executableUniforms(),
                            vertexSamplerPlan(interfacePlan, stage, vertex)),
                    null, label + "_VERTEX_BRIDGE_UNSUPPORTED");
            convertParticleFragment(label);
        }

        /** The particle-layout fragment stage of a program whose vertex stage converted. */
        private void convertParticleFragment(String label) {
            if (!executable) return;
            acceptFragment(FragmentConverter.convertParticleFragment(fragment, fragmentFile(), entitySlots(),
                            vertexLayout, interfacePlan.project("fragment", stage), geometryOutputPlan),
                    label + "_FRAGMENT_BRIDGE_UNSUPPORTED");
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
            boolean geometryRewritten = true;
            if (convertedGeometry != null) {
                convertedGeometry = advancedResources.rewriteStorageBuffers(name, convertedGeometry);
                geometryRewritten = convertedGeometry != null;
            }
            if (convertedTessellation != null) {
                String control = advancedResources.rewriteStorageBuffers(name, convertedTessellation.control());
                String evaluation = advancedResources.rewriteStorageBuffers(name, convertedTessellation.evaluation());
                geometryRewritten &= control != null && evaluation != null;
                convertedTessellation = TessellationStageBridge.paired(control, evaluation,
                        convertedTessellation.outputLayout());
            }
            if (convertedFragment == null || (program.vertexSource() != null && convertedVertex == null)
                    || !geometryRewritten) {
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
                    && alphaTestPlan.configured() && alphaTestPlan.active()) {
                deviations.add("ALPHA_TEST_PLANNED_NOT_INSTALLED:" + name);
            }
        }

        /**
         * The program's blend directives as Iris applies them: a world program blends each buffer
         * through its MRT output route, a shadow program each shadow attachment, and a fullscreen
         * pass as PackBlendPlan.forFullscreenPass says. A world program with no route keeps the
         * directive as a named gap.
         */
        private PackBlendPlan blendPlan() {
            PackBlendPlan blendPlan = PackBlendPlan.forProgram(directiveProgram, settings())
                    .withIrisDefault(directiveProgram);
            if (stage == UniformRegistry.Stage.POST) {
                blendPlan = blendPlan.forFullscreenPass(name);
            } else if (blendPlan.overridesAnything() && geometryOutputPlan == null && !stage.shadowPass()) {
                deviations.add("BLEND_DIRECTIVE_UNSUPPORTED:" + name);
                return PackBlendPlan.empty();
            }
            deviations.addAll(blendPlan.deviations());
            return blendPlan;
        }

        /** The program's samplers as its vertex stage declares them, at the fragment's bindings, and its images. */
        private EntityVertexBridge.VertexSamplerPlan vertexSamplerPlan(
                UniformRegistry.ProgramInterfacePlan plan, UniformRegistry.Stage stage, String vertex) {
            if (vertex == null) return null;
            return new EntityVertexBridge.VertexSamplerPlan(plan.project("vertex", stage),
                    PackPipelines.entitySamplerSlots(interfaceSlots(plan, stage)),
                    advancedResources == null ? Set.of() : advancedResources.storedImages(name, "vertex"));
        }
    }

    // Stage preparation, ProgramBuild's first step: a program's stage sources as planning reads them, its
    // prepared snapshots or its executable sources, with modern post and extended-terrain stages normalized.

    /**
     * The stages of a program planned under {@code name}, without a tessellation stage the pack does
     * not enable ({@code tessellation}, PackProgram.runsBetweenStages); post and terrain-material
     * stages normalized.
     */
    private static Map<String, PreparedShaderSource> prepareStages(PackProgram program, String name,
                                                                   boolean terrainMaterial, boolean tessellation) {
        Map<String, PreparedShaderSource> stages = preparedStages(program);
        stages.keySet().removeIf(stage -> !stage.equals("vertex") && !stage.equals("fragment")
                && !PackProgram.runsBetweenStages(stage, tessellation));
        Map<String, PreparedShaderSource> prepared = withoutInactiveImages(flattenInterfaceBlocks(stages));
        if (FamilyAdapterRegistry.stageFor(name) == UniformRegistry.Stage.POST) {
            return normalizeModernPostStages(prepared);
        }
        return terrainMaterial ? normalizeModernTerrainStages(prepared) : prepared;
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

    /** The stages GL links, in the order each feeds the next. */
    private static final List<String> LINK_ORDER = List.of("vertex", "tess_control", "tess_evaluation", "geometry",
            "fragment");
    /** The suffix of the varyings each stage writes, which the stage after it reads (InterfaceBlockFlattener). */
    private static final Map<String, String> WRITER_SUFFIXES = Map.of("vertex", "", "tess_control", "_tc",
            "tess_evaluation", "_te", "geometry", "_gs", "fragment", "");

    /**
     * Every stage with its interface blocks as plain varyings (InterfaceBlockFlattener). Each
     * stage's inputs carry the suffix of the stage before it and its outputs its own, so a stage
     * between the vertex and fragment stages can read and write blocks of one name.
     */
    private static Map<String, PreparedShaderSource> flattenInterfaceBlocks(Map<String, PreparedShaderSource> stages) {
        if (!Set.copyOf(LINK_ORDER).containsAll(stages.keySet())) return stages;
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        String inputSuffix = "";
        for (String stage : LINK_ORDER) {
            PreparedShaderSource prepared = stages.get(stage);
            if (prepared == null) continue;
            String outputSuffix = WRITER_SUFFIXES.get(stage);
            InterfaceBlockFlattener.Result flattened = InterfaceBlockFlattener.flatten(prepared.source(),
                    inputSuffix, outputSuffix);
            inputSuffix = outputSuffix;
            if (flattened.blocks().isEmpty()) continue;
            List<String> deviations = new ArrayList<>(prepared.deviations());
            flattened.blocks().forEach(block -> deviations.add("INTERFACE_BLOCK_FLATTENED:" + block));
            result.put(stage, new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    flattened.source(), prepared.dependencies(), deviations));
        }
        return result;
    }

    /**
     * Each stage without the image declarations nothing in it reads. GL leaves such a uniform
     * inactive, and Vulkan GLSL would need a binding for it: Reverie's programs all declare its
     * screen-sized images (image0-2, water_depth_min, atm_image) and most read none of them. A read
     * in a function main never reaches is none: GL compiles it away, and so do the converters
     * (Rethinking Voxels' composite7 comments out the call that reaches its voxel images). Such a
     * stage goes on without its dead functions, so no converter meets a read of an image that is
     * no longer declared. Line breaks stay, so line numbers do not move.
     */
    private static Map<String, PreparedShaderSource> withoutInactiveImages(Map<String, PreparedShaderSource> stages) {
        Map<String, PreparedShaderSource> result = new TreeMap<>(stages);
        for (Map.Entry<String, PreparedShaderSource> entry : stages.entrySet()) {
            PreparedShaderSource prepared = entry.getValue();
            String source = prepared.source();
            if (source == null || !source.contains("image")) continue;
            String reachable = LegacyGlslConverter.pruneUnreachableFunctions(source);
            String rewritten = withoutUnread(readOnlyInDeadCode(source, reachable) ? reachable : source, reachable);
            if (rewritten.equals(source)) continue;
            result.put(entry.getKey(), new PreparedShaderSource(prepared.stage(), prepared.relativePath(),
                    rewritten, prepared.dependencies(), prepared.deviations()));
        }
        return result;
    }

    /** {@code text} with each image declaration {@code reachable} does not read blanked. */
    private static String withoutUnread(String text, String reachable) {
        Matcher declaration = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(text);
        StringBuilder active = new StringBuilder();
        while (declaration.find()) {
            String kept = GlslTokenRewriter.identifierCount(reachable, declaration.group(2)) > 1
                    ? declaration.group() : declaration.group().replaceAll("[^\\r\\n]", "");
            declaration.appendReplacement(active, Matcher.quoteReplacement(kept));
        }
        return declaration.appendTail(active).toString();
    }

    /** Whether an image declaration is read only in functions main never reaches. */
    private static boolean readOnlyInDeadCode(String source, String reachable) {
        Matcher declaration = PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(reachable);
        while (declaration.find()) {
            String name = declaration.group(2);
            if (GlslTokenRewriter.identifierCount(reachable, name) <= 1
                    && GlslTokenRewriter.identifierCount(source, name) > 1) return true;
        }
        return false;
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
                normalized = entry.getKey().equals("fragment")
                        ? TerrainVertexBridge.normalizeModernTerrainFragment(prepared.source())
                        : TerrainVertexBridge.normalizeModernTerrain(prepared.source());
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

    /** Post stages in modern syntax: GLSL 140 and later, where in/out and layouts appear. */
    private static boolean hasModernVersion(String source) {
        GlslVersion version = GlslVersion.first(source);
        return version != null && version.supported() && version.number() >= 140;
    }

    private static String stageSource(
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

    private static boolean preparedSuccessfully(PreparedShaderSource source) {
        if (source.source() == null) {
            return false;
        }
        return source.deviations().stream().noneMatch(value ->
                ((value.startsWith("SOURCE_") || value.startsWith("PREPROCESSOR_"))
                        && !ShaderSourcePreprocessor.isWarning(value))
                        || value.startsWith("LEGACY_FOG_FIELD_UNSUPPORTED:"));
    }

    private static int[] interfaceSlots(
            UniformRegistry.ProgramInterfacePlan plan,
            UniformRegistry.Stage stage
    ) {
        return plan.effective(stage).samplers().stream()
                .mapToInt(UniformRegistry.SamplerBinding::slot)
                .toArray();
    }
}
