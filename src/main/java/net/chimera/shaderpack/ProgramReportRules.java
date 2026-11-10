package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * The per-program rules behind the conformance report: what each program's sources, plan and
 * targets deviate from, and the support status those deviations add up to.
 */
final class ProgramReportRules {
    private ProgramReportRules() {}

    static ConformanceReport.ProgramReport toProgram(
            PackProbe.Inventory inventory,
            PackConfig.PackConfigData packConfig,
            PackProgramPlan programPlan,
            PackResourcePlan resourcePlan,
            PackPlan packPlan,
            PassComputePlan passComputes
    ) {
        String name = inventory.name;
        ConformanceReport.ProgramReport compute = computeOnlyProgram(inventory, packPlan, passComputes);
        if (compute != null) return compute;
        ReportSources sources = ReportSources.of(inventory);
        boolean relaxed = sources.relaxed();
        String source = sources.source();
        String fragment = sources.fragment();
        String stripped = stripComments(source);
        boolean postName = PostTargetPlan.isPostProgramName(name);
        boolean hasFragment = inventory.stages.contains("fragment");
        boolean modern = !modernTerrainPlan(name, programPlan) && !validatedConversion(inventory, programPlan, packPlan)
                && usesUnsupportedModernGlsl(stripped, postName, FamilyAdapterRegistry.isEntityLike(name));
        List<String> samplers = UniformRegistry.scanDeclaredSamplerNames(stripped);
        // The report has historically exposed all uniform declarations,
        // including samplers. Keep that public inventory stable while the
        // shared interface plan continues to separate samplers for bindings.
        List<String> uniformNames = declaredNames(stripped, samplers);
        PostTargetPlan.ParseResult targetResult = targetResult(inventory, fragment, packConfig, programPlan, postName);
        List<Integer> targets = targetResult != null
                ? targetResult.plan().targetSlots() : scanTargets(fragment);

        TreeSet<String> deviations = new TreeSet<>(inventory.deviations);
        if (resourcePlan != null) {
            deviations.addAll(reportedResourceDeviations(resourcePlan, name));
        }
        deviations.addAll(relevantTargetDeviations(name, packConfig, targetResult));
        UniformRegistry.ProgramInterface interfacePlan =
                interfacePlan(name, fragment, programPlan, targetResult, relaxed, postName);
        if (programPlan != null) {
            deviations.addAll(programPlan.deviations());
        }
        deviations.addAll(interfacePlan.deviations());
        addTargetRouteDeviations(targetResult, postName, programPlan, deviations);
        if (programPlan == null && postName && hasFragment && !modern) {
            addUnplannedPostDeviations(inventory, fragment, relaxed, targetResult, interfacePlan,
                    deviations);
        }
        addVertexDeviations(inventory, packPlan, programPlan, fragment, postName, deviations);
        addSourceDeviations(inventory, packPlan, programPlan, sources.advancedDeclarations(), samplers, relaxed,
                modern, deviations);
        addTargetCountDeviations(name, programPlan, targetResult, targets, deviations);
        if (!hasFragment) {
            deviations.add("MISSING_FRAGMENT_SOURCE");
        }
        addUnlanedDeviation(inventory, postName || !hasFragment, deviations);

        return new ConformanceReport.ProgramReport(
                name, PackProbe.familyOf(name), modern ? "MODERN_GLSL" : "LEGACY_GLSL",
                PackProbe.sorted(inventory.stages), inventory.sourceHashes, samplers, uniformNames,
                targets, supportStatus(executableName(name, inventory, postName), hasFragment,
                        planRuns(programPlan, packPlan, name),
                        deviations),
                ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED, List.copyOf(deviations));
    }

    /**
     * The sources a program's report reads, joined by stage name: a variant's prepared sources when
     * it has them, else the authored ones. Advanced declarations are judged per stage, as each stage
     * compiles alone and as the plan judges them (PackAdvancedResourcePlan.addUnplannedDeclarations):
     * an image both stages only declare is no use of it.
     */
    private record ReportSources(boolean relaxed, String source, String fragment, boolean advancedDeclarations) {
        static ReportSources of(PackProbe.Inventory inventory) {
            boolean relaxed = !inventory.variantFolder.isBlank();
            Map<String, String> sourceView = relaxed && !inventory.preparedSources.isEmpty()
                    ? inventory.preparedSources : inventory.sources;
            StringBuilder combinedSource = new StringBuilder();
            sourceView.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> combinedSource.append(entry.getValue()).append('\n'));
            String source = combinedSource.toString();
            boolean advanced = sourceView.values().stream()
                    .anyMatch(PackAdvancedResourcePlan::containsAdvancedDeclarations);
            return new ReportSources(relaxed, source, sourceView.getOrDefault("fragment", source), advanced);
        }
    }

    /** Every declared uniform and sampler name, sorted. */
    static List<String> declaredNames(String stripped, List<String> samplers) {
        TreeSet<String> names = new TreeSet<>(samplers);
        names.addAll(UniformRegistry.scanUniformDeclarations(stripped).stream()
                .map(UniformRegistry.UniformDeclaration::name).toList());
        return List.copyOf(names);
    }

    /** The resource plan's deviations for a program, without the alias facts its report leaves out. */
    static List<String> reportedResourceDeviations(PackResourcePlan resourcePlan, String program) {
        return resourcePlan.deviationsForProgram(program).stream()
                .filter(value -> !value.startsWith("STANDARD_RESOURCE_ALIAS:")
                        && !value.startsWith("SHADOW_RESOURCE_ALIAS:"))
                .toList();
    }

    /** A post program without a plan must still convert; say why it cannot. */
    static void addUnplannedPostDeviations(PackProbe.Inventory inventory, String fragment, boolean relaxed,
                                                   PostTargetPlan.ParseResult targetResult,
                                                   UniformRegistry.ProgramInterface interfacePlan,
                                                   Set<String> deviations) {
        if (targetResult == null || !targetResult.executable() || !interfacePlan.executable()) return;
        Path sourcePath = relaxed && !inventory.preparedSources.isEmpty()
                ? null : inventory.sourcePaths.get("fragment");
        String converted = FragmentConverter.convertPostFragment(
                fragment, sourcePath, interfacePlan, targetResult.plan());
        if (converted == null) {
            deviations.addAll(PostVertexBridge.postVaryingDeviations(fragment));
            deviations.add("POST_CONVERTER_UNSUPPORTED");
        }
    }

    /** The vertex bridge the program takes, and what its installed family fixes to the host. */
    static void addVertexDeviations(PackProbe.Inventory inventory, PackPlan packPlan, PackProgramPlan programPlan,
                                    String fragment, boolean postName, Set<String> deviations) {
        String name = inventory.name;
        boolean hasVertex = inventory.stages.contains("vertex");
        String vertex = inventory.preparedSources.getOrDefault("vertex", inventory.sources.get("vertex"));
        String vertexBridge = programPlan != null
                ? plannedVertexBridge(name, hasVertex, programPlan, packPlan, postName)
                : unplannedVertexBridge(name, hasVertex, vertex, fragment);
        if (vertexBridge != null) {
            deviations.add(vertexBridge);
        }
        if (name.equals("gbuffers_water")) {
            deviations.add("TRANSLUCENT_STATE_FIXED_TO_HOST");
        }
        if (programPlan != null && programPlan.executable()) {
            deviations.addAll(installedFamilyDeviations(name));
        }
    }

    /**
     * Whether Iris would run a stage of the program that Chimera does not: a geometry stage, or a
     * tessellation stage the pack enables, that the plan did not convert (TASK-648), or a compute
     * stage that is not a pass compute (Noble's composite99.csh is one, planned and run apart from
     * composite99's draw).
     */
    private static boolean runsUnsupportedStage(PackProbe.Inventory inventory, PackPlan packPlan,
                                                PackProgramPlan programPlan) {
        boolean passCompute = PassComputePlan.isPassComputeProgram(inventory.name);
        boolean tessellation = packPlan != null && packPlan.settings() != null
                && packPlan.settings().readsTessellation();
        Set<String> converted = programPlan == null ? Set.of() : programPlan.betweenStages().present().stream()
                .map(BetweenStages.Stage::name).collect(Collectors.toSet());
        return inventory.stages.stream().anyMatch(stage -> PackProgram.runsBetweenStages(stage, tessellation)
                && !converted.contains(stage)
                || stage.equals("compute") && !passCompute);
    }

    /** Stages, GLSL dialect, advanced resources and samplers the program's sources use. */
    static void addSourceDeviations(PackProbe.Inventory inventory, PackPlan packPlan, PackProgramPlan programPlan,
                                            boolean advancedDeclarations, List<String> samplers, boolean relaxed,
                                            boolean modern, Set<String> deviations) {
        if (runsUnsupportedStage(inventory, packPlan, programPlan)) {
            deviations.add("UNSUPPORTED_PACK_STAGE");
        }
        if (modern) {
            deviations.add("MODERN_GLSL_UNSUPPORTED");
        }
        if (advancedDeclarations && !advancedResourcesArePlanned(inventory.name, packPlan)) {
            deviations.add("ADVANCED_RESOURCE_UNSUPPORTED");
        }
        if (packPlan != null && packPlan.advancedResources() != null) {
            packPlan.advancedResources().unservedImages(inventory.name)
                    .forEach(image -> deviations.add("ADVANCED_IMAGE_UNSERVED:" + image));
        }
        // PackPlanBuilder already resolved standard aliases and pack-owned
        // sampler slots through the shared interface plan. Keep the legacy
        // scan only for compatibility callers that do not have a plan.
        if (!relaxed && programPlan == null) {
            addUnmappedSamplerDeviations(inventory.name, samplers, deviations);
        }
        if (samplers.contains("noisetex")) {
            // Runtime performs the exact file check. The report records the
            // standard resource requirement without embedding a path.
            deviations.add("NOISETEX_PACK_RESOURCE");
        }
    }

    /** Without a target plan, only one output to colortex0 runs as authored. */
    static void addTargetCountDeviations(String name, PackProgramPlan programPlan,
                                                 PostTargetPlan.ParseResult targetResult, List<Integer> targets,
                                                 Set<String> deviations) {
        boolean authoredShadowOutputs = FamilyAdapterRegistry.isShadowFamily(name) && programPlan != null
                && programPlan.convertedFragment() != null && programPlan.executable();
        if (authoredShadowOutputs || targetResult != null) return;
        if (targets.size() > 1) {
            deviations.add(FamilyAdapterRegistry.isShadowFamily(name)
                    ? "SHADOW_COLOR_TARGET_UNSUPPORTED" : "MRT_NOT_SUPPORTED");
        } else if (!targets.isEmpty() && targets.get(0) != 0) {
            deviations.add("TARGET_ROUTING_FIXED_TO_COLORTEX0");
        }
    }

    /** Modern terrain, water and shadow sources run through their own planned bridge. */
    static boolean modernTerrainPlan(String name, PackProgramPlan programPlan) {
        String lane = FamilyAdapterRegistry.laneProgram(name);
        return (lane.equals("gbuffers_terrain") || lane.equals("gbuffers_water") || lane.equals("shadow"))
                && programPlan != null && programPlan.terrainMaterial().modern();
    }

    /**
     * A validated conversion is authoritative for every family, not just post and particles: the
     * lexical modern-GLSL veto must not reject a program whose converters already accepted it. A
     * cloud program that runs empty (PackPlan.cloudRunsEmpty) has nothing left to convert.
     */
    static boolean validatedConversion(PackProbe.Inventory inventory, PackProgramPlan programPlan, PackPlan packPlan) {
        if (packPlan != null && packPlan.cloudRunsEmpty(inventory.name)) return true;
        return programPlan != null && programPlan.executable() && programPlan.convertedFragment() != null
                && (!inventory.stages.contains("vertex") || programPlan.convertedVertex() != null)
                && (!inventory.stages.contains("geometry") || programPlan.convertedGeometry() != null);
    }

    static PostTargetPlan.ParseResult targetResult(
            PackProbe.Inventory inventory,
            String fragment,
            PackConfig.PackConfigData packConfig,
            PackProgramPlan programPlan,
            boolean postName
    ) {
        if (programPlan != null && programPlan.targetPlan() != null) {
            return new PostTargetPlan.ParseResult(programPlan.targetPlan(), programPlan.targetPlan().deviations());
        }
        if (programPlan != null && programPlan.geometryOutputPlan() != null) {
            // The plan routes the prepared source, whose #if already chose one
            // DRAWBUFFERS directive; an authored scan would count every branch.
            return new PostTargetPlan.ParseResult(programPlan.geometryOutputPlan().targetPlan(),
                    programPlan.geometryOutputPlan().deviations());
        }
        return postName ? PostTargetPlan.parse(inventory.name, fragment, packConfig.colortexFormats()) : null;
    }

    static UniformRegistry.ProgramInterface interfacePlan(
            String name,
            String fragment,
            PackProgramPlan programPlan,
            PostTargetPlan.ParseResult targetResult,
            boolean relaxed,
            boolean postName
    ) {
        UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(name);
        if (programPlan != null) {
            return programPlan.interfacePlan().effective(stage);
        }
        if (postName) {
            return relaxed ? UniformRegistry.planPreparedPost(fragment, targetResult.plan())
                    : UniformRegistry.planPost(fragment, targetResult.plan());
        }
        return relaxed ? UniformRegistry.planPrepared(fragment, stage) : UniformRegistry.plan(fragment, stage);
    }

    static void addTargetRouteDeviations(
            PostTargetPlan.ParseResult targetResult,
            boolean postName,
            PackProgramPlan programPlan,
            Collection<String> deviations
    ) {
        if (targetResult == null) {
            return;
        }
        deviations.addAll(targetResult.deviations());
        if (!targetResult.executable()) {
            return;
        }
        PostTargetPlan plan = targetResult.plan();
        if (!plan.targetSlots().equals(List.of(0)) && (postName || plan.requiresMrt())) {
            deviations.add(postName ? "POST_TARGET_ROUTE_APPLIED" : "GEOMETRY_TARGET_ROUTE_APPLIED");
        }
        if (plan.requiresMrt() && (postName || programPlan == null || programPlan.executable())) {
            deviations.add(postName ? "MRT_POST_BRIDGE" : "MRT_GEOMETRY_BRIDGE");
        }
    }

    /** Whether a program of this name and these stages is one Chimera could execute at all. */
    static boolean executableName(String name, PackProbe.Inventory inventory, boolean postName) {
        boolean needsVertex = FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name)
                || FamilyAdapterRegistry.isWeatherFamily(name)
                || FamilyAdapterRegistry.isHostLayoutFamily(name)
                || FamilyAdapterRegistry.isSkyFamily(name)
                || FamilyAdapterRegistry.isCloudFamily(name);
        return FamilyAdapterRegistry.isExecutableFamily(name)
                && (postName || inventory.stages.contains("fragment"))
                && (!needsVertex || inventory.stages.contains("vertex"));
    }

    /**
     * Whether the plan lets the program run: no plan judges it, the plan admits it, or it is an
     * authored-empty cloud program, which the runtime installs as drawing nothing (PackPlan.cloudRunsEmpty).
     */
    static boolean planRuns(PackProgramPlan plan, PackPlan packPlan, String name) {
        return plan == null || plan.executable() || packPlan != null && packPlan.cloudRunsEmpty(name);
    }

    /** A program's status. A plan that refused the program leaves it on the fallback whatever it recorded. */
    static ConformanceReport.SupportStatus supportStatus(
            boolean executableName, boolean hasFragment, boolean planExecutable, Collection<String> deviations) {
        if (!executableName) {
            return ConformanceReport.SupportStatus.UNSUPPORTED;
        }
        if (!hasFragment || !planExecutable || hasSevereDeviation(deviations)) {
            return ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        }
        return deviations.isEmpty() ? ConformanceReport.SupportStatus.SUPPORTED
                : ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
    }

    /**
     * How a planned program's vertex stage is bridged, as its report names it; null when it has none.
     * A cloud program that runs empty (PackPlan.cloudRunsEmpty) has no vertex bridge to name.
     */
    static String plannedVertexBridge(
            String name, boolean hasVertex, PackProgramPlan plan, PackPlan packPlan, boolean postName) {
        boolean converted = plan.convertedVertex() != null;
        if (hasVertex) {
            if (packPlan != null && packPlan.cloudRunsEmpty(name)) {
                return PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT;
            }
            boolean modern = plan.terrainMaterial().modern();
            String bridge = switch (name) {
                case "gbuffers_terrain" -> modern ? bridge("MODERN_TERRAIN", converted)
                        : converted ? "LEGACY_TERRAIN_VERTEX_BRIDGE" : "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
                case "gbuffers_water" -> bridge(modern ? "MODERN_WATER" : "TRANSLUCENT", converted);
                default -> {
                    String label = vertexBridgeLabel(name);
                    yield label == null ? null : bridge(label, converted);
                }
            };
            if (bridge != null) {
                return reached(bridge, converted, plan);
            }
            if (converted && postName) {
                return plan.deviations().contains("POST_VERTEX_AUTHORED_TRANSLATED")
                        ? "POST_VERTEX_AUTHORED_TRANSLATED" : "POST_VERTEX_ADAPTER";
            }
        }
        return unbridgedVertex(name, hasVertex);
    }

    /**
     * The bridge as reported. A plan its admission refused first never runs the entity,
     * host-layout or particle bridges, so an unconverted vertex with no failed bridge recorded
     * reports the bridge as not reached ({@code <LANE>_VERTEX_BRIDGE_NOT_REACHED}).
     */
    private static String reached(String bridge, boolean converted, PackProgramPlan plan) {
        boolean attempted = converted || plan.deviations().stream().anyMatch(deviation ->
                deviation.endsWith("_VERTEX_BRIDGE_UNSUPPORTED") || deviation.contains("_VERTEX_CONVERSION_FAILED:"));
        return attempted ? bridge : bridge.replace("_UNSUPPORTED", "_NOT_REACHED");
    }

    /** The bridge a program without a plan would get, judged from its authored sources alone. */
    static String unplannedVertexBridge(String name, boolean hasVertex, String vertex, String fragment) {
        if (hasVertex) {
            if (FamilyAdapterRegistry.isCloudFamily(name) && AuthoredOutput.producesNothing(vertex, fragment)) {
                return PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT;
            }
            String bridge = switch (FamilyAdapterRegistry.laneProgram(name)) {
                case "gbuffers_terrain" -> TerrainVertexBridge.supportsTerrainVertex(vertex, fragment)
                        ? "LEGACY_TERRAIN_VERTEX_BRIDGE" : "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
                case "gbuffers_water" -> TerrainVertexBridge.supportsModernTerrain(vertex, fragment)
                        ? "MODERN_WATER_VERTEX_BRIDGE"
                        : TerrainVertexBridge.supportsTerrainVertex(vertex, fragment)
                        ? "TRANSLUCENT_VERTEX_BRIDGE" : "MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED";
                case "shadow" -> bridge("SHADOW", TerrainVertexBridge.supportsModernShadow(vertex, fragment)
                        || TerrainVertexBridge.supportsShadowVertex(vertex, fragment));
                case "gbuffers_entities" -> bridge("ENTITY", EntityVertexBridge.supportsEntityVertex(vertex, fragment));
                case "gbuffers_block", "gbuffers_block_translucent" ->
                        bridge("BLOCK", EntityVertexBridge.supportsEntityVertex(vertex, fragment));
                case "gbuffers_hand" -> bridge("HAND", EntityVertexBridge.supportsHandVertex(vertex, fragment));
                default -> unplannedLaneBridge(name, vertex, fragment);
            };
            if (bridge != null) {
                return bridge;
            }
        }
        return unbridgedVertex(name, hasVertex);
    }

    /** The authored bridge of a particle, weather, sky or cloud program without a plan; null for others. */
    static String unplannedLaneBridge(String name, String vertex, String fragment) {
        if (FamilyAdapterRegistry.isParticleLike(name) || FamilyAdapterRegistry.isWeatherFamily(name)) {
            return bridge(vertexBridgeLabel(name),
                    EntityVertexBridge.convertParticleVertex(vertex, null, fragment, Map.of()) != null);
        }
        if (FamilyAdapterRegistry.isSkyFamily(name)) {
            return bridge("SKY", HostLayoutVertexBridge.convertSkyVertex(vertex, null, fragment,
                    name.equals("gbuffers_skytextured")) != null);
        }
        if (FamilyAdapterRegistry.isCloudFamily(name)) {
            return bridge("CLOUD", HostLayoutVertexBridge.convertCloudVertex(vertex, null, fragment) != null);
        }
        return null;
    }

    /**
     * The label of a family's authored vertex bridge, or null for one without: terrain and water
     * name theirs by the material contract.
     */
    static String vertexBridgeLabel(String name) {
        if (FamilyAdapterRegistry.isShadowTerrain(name)) return "SHADOW";
        // Every entity-like family converts on the entity bridge (PackPlanBuilder.convertEntityVertex).
        if (FamilyAdapterRegistry.isEntityLike(name)) {
            return VertexBridgeDeviations.entity(name, false).replace("_VERTEX_BRIDGE", "");
        }
        if (FamilyAdapterRegistry.isShadowEntityFamily(name)) return "SHADOW_ENTITY";
        if (FamilyAdapterRegistry.isParticleLike(name)) return "PARTICLE";
        if (FamilyAdapterRegistry.isWeatherFamily(name)) return "WEATHER";
        if (FamilyAdapterRegistry.isSkyFamily(name)) return "SKY";
        if (FamilyAdapterRegistry.isCloudFamily(name)) return "CLOUD";
        return FamilyAdapterRegistry.hostLayoutLane(name);
    }

    static String bridge(String label, boolean converted) {
        return label + (converted ? "_VERTEX_BRIDGE" : "_VERTEX_BRIDGE_UNSUPPORTED");
    }

    /** A vertex stage no bridge serves: water draws on the fixed host vertex, shadow programs fail. */
    static String unbridgedVertex(String name, boolean hasVertex) {
        if (name.equals("gbuffers_water")) return "FIXED_VERTEX_SUBSTITUTION";
        if (FamilyAdapterRegistry.isShadowTerrain(name)) return "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
        if (FamilyAdapterRegistry.isShadowEntityFamily(name)) return "SHADOW_ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
        return hasVertex ? "FIXED_VERTEX_SUBSTITUTION" : null;
    }

    /** What an executable plan of each family records about the host state its draws keep. */
    static List<String> installedFamilyDeviations(String name) {
        FamilyAdapterPlan family = FamilyAdapterRegistry.forProgram(name);
        return switch (family.family()) {
            case ENTITY -> List.of("ENTITY_VERTEX_FORMAT_EXTENDED", "ENTITY_STATE_FIXED_TO_HOST",
                    "ENTITY_BATCH_ORIGIN_SPLIT", "ENTITY_SCREEN_DRAW_FALLBACK");
            case ENTITY_TRANSLUCENT, SPIDER_EYES -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_STATE_FIXED_TO_HOST");
            case BLOCK -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "BLOCK_ENTITY_ID_DEFAULTED", "ENTITY_STATE_FIXED_TO_HOST");
            case BLOCK_TRANSLUCENT -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "BLOCK_TRANSLUCENT_INSTALLED", "ENTITY_STATE_FIXED_TO_HOST");
            case DAMAGED_BLOCK -> List.of("DAMAGED_BLOCK_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED");
            case HAND -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "HAND_STATE_FIXED_TO_HOST");
            case HAND_WATER -> List.of("FAMILY_ADAPTER_INSTALLED", "HAND_TRANSLUCENT_ITEM_PHASE");
            case PARTICLE -> List.of("FAMILY_ADAPTER_INSTALLED", "PARTICLE_STATE_FIXED_TO_HOST");
            case PARTICLE_TRANSLUCENT -> List.of("FAMILY_ADAPTER_INSTALLED", "PARTICLE_TRANSLUCENT_INSTALLED");
            case WEATHER -> List.of("FAMILY_ADAPTER_INSTALLED", "WEATHER_STATE_FIXED_TO_HOST");
            // Depth and cull follow each caster's host pipeline; blending is off, as in Iris.
            case SHADOW_ENTITIES, SHADOW_BLOCK -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "SHADOW_ENTITY_STATE_FIXED_TO_HOST");
            default -> family.vertexContract().hostLayout()
                    ? List.of("FAMILY_ADAPTER_INSTALLED", family.family().name() + "_STATE_FIXED_TO_HOST")
                    : List.of();
        };
    }

    /** Declared samplers a program without a plan could not bind, by the slot map of its stage. */
    static void addUnmappedSamplerDeviations(String name, List<String> samplers,
                                                     Collection<String> deviations) {
        Map<String, Integer> knownSamplers = unplannedSamplerSlots(name);
        for (String sampler : samplers) {
            if (knownSamplers.containsKey(sampler)) {
                continue;
            }
            deviations.add(unplannedSamplerDeviation(name, sampler));
        }
    }

    /** The slot map a program without a plan binds its samplers by, chosen by its family. */
    static Map<String, Integer> unplannedSamplerSlots(String name) {
        if (name.equals("gbuffers_terrain")) return UniformRegistry.GEOMETRY_NAME_TO_SLOT;
        if (FamilyAdapterRegistry.isShadowFamily(name)) return UniformRegistry.SHADOW_NAME_TO_SLOT;
        if (name.equals("gbuffers_water")) return UniformRegistry.TRANSLUCENT_NAME_TO_SLOT;
        if (entityStage(name) || FamilyAdapterRegistry.isSkyFamily(name) || FamilyAdapterRegistry.isCloudFamily(name)) {
            return UniformRegistry.ENTITY_NAME_TO_SLOT;
        }
        return UniformRegistry.NAME_TO_SLOT;
    }

    /** Why a program without a plan cannot bind a sampler its family's slot map lacks. */
    static String unplannedSamplerDeviation(String name, String sampler) {
        if (name.equals("gbuffers_water")) {
            return sampler.startsWith("depthtex") || sampler.startsWith("shadowcolor")
                    ? "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"
                    : "TRANSLUCENT_SAMPLER_UNSUPPORTED:" + sampler;
        }
        if (FamilyAdapterRegistry.isShadowFamily(name)) return "SHADOW_SAMPLER_UNSUPPORTED:" + sampler;
        return entityStage(name) ? "ENTITY_SAMPLER_UNSUPPORTED:" + sampler : "SAMPLER_NOT_MAPPED:" + sampler;
    }

    /** The entity-like, particle, weather and host-layout families, whose samplers report as entity ones. */
    static boolean entityStage(String name) {
        return FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name)
                || FamilyAdapterRegistry.isWeatherFamily(name)
                || FamilyAdapterRegistry.isHostLayoutFamily(name);
    }

    /** The report of a program that is only a compute: a pass compute; else null. */
    private static ConformanceReport.ProgramReport computeOnlyProgram(PackProbe.Inventory inventory, PackPlan packPlan,
                                                                      PassComputePlan passComputes) {
        PassComputePlan.PassCompute passCompute = passComputes == null ? null : passComputes.compute(inventory.name);
        // <pass>_x.csh is its own row; <pass>.csh shares the pass's, which is the compute's when it runs alone.
        if (passCompute != null && (!passCompute.name().equals(passCompute.pass())
                || PassComputePlan.runsAlone(packPlan, passCompute.pass()))) {
            return passComputeProgram(inventory, passCompute);
        }
        return null;
    }

    /**
     * The report of {@code <pass>[_x].csh} in a pass with no fragment stage: its own translation.
     * Iris runs it before the pass, or alone as a ComputeOnlyPass where the pass has no program.
     */
    static ConformanceReport.ProgramReport passComputeProgram(PackProbe.Inventory inventory,
                                                             PassComputePlan.PassCompute compute) {
        TreeSet<String> deviations = new TreeSet<>(inventory.deviations);
        deviations.addAll(compute.deviations());
        deviations.add(compute.supported() ? "PASS_COMPUTE_BRIDGE:" + compute.pass()
                : "PASS_COMPUTE_UNSUPPORTED:" + compute.name());
        return computeReport(inventory, compute.supported(), deviations);
    }

    private static ConformanceReport.ProgramReport computeReport(PackProbe.Inventory inventory, boolean executable,
                                                                 TreeSet<String> deviations) {
        ConformanceReport.SupportStatus support = executable
                ? ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION
                : ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        ConformanceReport.RuntimeDisposition runtime = executable
                ? ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED
                : ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK;
        String source = inventory.sources.getOrDefault("compute", "");
        String stripped = stripComments(source);
        List<String> samplers = UniformRegistry.scanDeclaredSamplerNames(stripped);
        return new ConformanceReport.ProgramReport(
                inventory.name,
                "compute",
                "COMPUTE_GLSL",
                List.of("compute"),
                inventory.sourceHashes,
                samplers,
                declaredNames(stripped, samplers),
                List.of(),
                support,
                runtime,
                List.copyOf(deviations));
    }

    /**
     * Advanced image declarations are not an old generic-resource
     * failure when the shared plan has admitted the program. The runtime
     * still performs the final pipeline/resource checks, so an individual
     * build failure remains an explicit identity fallback.
     */
    static boolean advancedResourcesArePlanned(String program, PackPlan packPlan) {
        if (packPlan == null || packPlan.advancedResources() == null) {
            return false;
        }
        PackAdvancedResourcePlan advanced = packPlan.advancedResources();
        boolean usesImages = advanced.dependentPrograms().contains(program);
        boolean usesStorage = advanced.bufferDependentPrograms().contains(program);
        if (!usesImages && !usesStorage) {
            return false;
        }
        boolean imagesReady = !usesImages || advanced.imagesServable(program);
        boolean storageReady = !usesStorage || advanced.storageBufferProgramSupported(program);
        return imagesReady && storageReady;
    }

    /**
     * Programs Iris 1.21.1 reads only as other programs' fallback sources: no shader key draws them
     * (ShaderKey, SodiumPrograms), so neither does Chimera; the programs they stand in for do.
     */
    static final Set<String> IRIS_FALLBACK_SOURCES = Set.of(
            "gbuffers_textured_lit", "gbuffers_item", "gbuffers_entities_glowing");

    /** Names why a world program runs in no lane (unlanedProgram); {@code skip} a post or fragmentless one. */
    static void addUnlanedDeviation(PackProbe.Inventory inventory, boolean skip, Set<String> deviations) {
        String unlaned = skip ? null : unlanedProgram(inventory.name, inventory);
        if (unlaned != null) deviations.add(unlaned);
    }

    /**
     * Why a world program with a fragment stage runs in no lane, or null when it has one
     * (laneReason); or, in a lane that needs an authored vertex stage, that it has none, where
     * Iris draws it with a default one (ProgramSet.readProgramSource).
     */
    static String unlanedProgram(String name, PackProbe.Inventory inventory) {
        if (!FamilyAdapterRegistry.isExecutableFamily(name)) return laneReason(name);
        return executableName(name, inventory, false) ? null : "IRIS_DEFAULT_VERTEX_UNSUPPORTED";
    }

    /**
     * Why Chimera runs a world program in no lane: Iris draws it under no key of its own; it is a
     * begin program, which Iris's composite renderer runs and Chimera does not; Iris
     * draws it and Chimera routes none of those draws to it (the per-layer terrain and shadow
     * programs, dh_*); or Iris never runs a program
     * of that name at all (setup.fsh, names that are no ProgramId).
     */
    private static String laneReason(String name) {
        if (IRIS_FALLBACK_SOURCES.contains(name)) return "IRIS_FALLBACK_SOURCE_ONLY";
        if (name.matches("begin\\d*")) return "PASS_PROGRAM_UNSUPPORTED:" + name;
        if (FamilyAdapterRegistry.isMergedLayerProgram(name)) return "TERRAIN_LAYER_MERGED_BY_HOST:" + name;
        return PackResolutionPlan.isIrisProgram(name) ? "FAMILY_LANE_UNSUPPORTED:" + name
                : "IRIS_PROGRAM_UNUSED:" + name;
    }

    /** A host-layout lane (LINE, GLINT, LIGHTNING, ...) whose vertex could not be bridged. */
    static final Set<String> HOST_LAYOUT_BRIDGE_FAILURES = FamilyAdapterRegistry.hostLayoutLanes().stream()
            .map(lane -> lane + "_VERTEX_BRIDGE_UNSUPPORTED")
            .collect(Collectors.toUnmodifiableSet());

    /** Deviations that leave a program on the identity fallback. */
    static final Set<String> SEVERE_DEVIATIONS = Set.of(
            "UNSUPPORTED_PACK_STAGE", "MODERN_GLSL_UNSUPPORTED", "ADVANCED_RESOURCE_UNSUPPORTED",
            "MRT_NOT_SUPPORTED", "TARGET_ROUTING_FIXED_TO_COLORTEX0",
            "POST_TARGET_DIRECTIVE_MALFORMED", "POST_TARGET_DIRECTIVE_CONFLICT", "FINAL_MRT_UNSUPPORTED",
            "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED", "MODERN_TERRAIN_VERTEX_BRIDGE_UNSUPPORTED",
            "MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED", "SHADOW_VERTEX_BRIDGE_UNSUPPORTED",
            "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED",
            "ENTITY_VERTEX_BRIDGE_UNSUPPORTED", "BLOCK_VERTEX_BRIDGE_UNSUPPORTED",
            "HAND_VERTEX_BRIDGE_UNSUPPORTED", "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED",
            "WEATHER_VERTEX_BRIDGE_UNSUPPORTED", "SKY_VERTEX_BRIDGE_UNSUPPORTED",
            "CLOUD_VERTEX_BRIDGE_UNSUPPORTED",
            "MISSING_FRAGMENT_SOURCE", "POST_CONVERTER_UNSUPPORTED");

    /**
     * Deviation families that do the same, matched by prefix. The interface plan's own blocking
     * deviations (UniformRegistry.isExecutionBlocking) are severe too.
     */
    static final List<String> SEVERE_PREFIXES = List.of(
            "POST_TARGET_DIRECTIVE_DUPLICATE:", "POST_TARGET_INDEX_UNSUPPORTED:",
            "POST_TARGET_FORMAT_UNSUPPORTED:", "PACK_TEXTURE_MISSING:",
            "PACK_RAW_TEXTURE_UNSUPPORTED:", "PACK_RAW_TEXTURE_SIZE_MISMATCH:", "PACK_TEXTURE_PATH_UNSAFE:",
            "PACK_TEXTURE_LOAD_FAILED:", "PACK_RESOURCE_SLOT_LIMIT:", "STANDARD_RESOURCE_UNAVAILABLE:",
            "GBUFFER_TARGET_FEEDBACK:", "PROGRAM_", "SOURCE_INCLUDE_",
            "TRANSLATION_UNSUPPORTED:", "STORAGE_BUFFER_", "POST_VARYING_UNSUPPORTED:",
            "LEGACY_FOG_FIELD_UNSUPPORTED:", "SOURCE_NORMALIZATION_FAILED:",
            "PREPROCESSOR_", "ADVANCED_IMAGE_UNSERVED:");

    /** Members of a severe family that only record a fact. */
    static final List<String> BENIGN_PREFIXES = List.of(
            "PROGRAM_ALIAS_RECORDED:", "PREPROCESSOR_MACRO_REDEFINED:", "PREPROCESSOR_UNMATCHED_CONDITIONAL:",
            "PREPROCESSOR_DIRECTIVE_REPORTED:");

    static boolean hasSevereDeviation(Collection<String> deviations) {
        return deviations.stream().anyMatch(ProgramReportRules::isSevere);
    }

    static boolean isSevere(String deviation) {
        if (SEVERE_DEVIATIONS.contains(deviation) || HOST_LAYOUT_BRIDGE_FAILURES.contains(deviation)
                || deviation.endsWith("_VERTEX_BRIDGE_NOT_REACHED") || UniformRegistry.isExecutionBlocking(deviation)) {
            return true;
        }
        return BENIGN_PREFIXES.stream().noneMatch(deviation::startsWith)
                && SEVERE_PREFIXES.stream().anyMatch(deviation::startsWith);
    }

    /**
     * Labels a program whose conversion failed: a version outside the one rule
     * (GlslVersion), or syntax beyond the legacy subset. A validated conversion
     * clears it, so the version number alone never rejects a program.
     */
    /** Syntax the post and entity converters cannot take; their stage declarations convert. */
    private static final Set<String> CONVERTED_STAGE_SYNTAX = Set.of("layout", "buffer", "shared", "subroutine");
    /** Syntax beyond the legacy subset in the other families. */
    private static final Set<String> MODERN_STAGE_SYNTAX = Set.of("layout", "in", "out", "flat", "noperspective",
            "buffer");

    static boolean usesUnsupportedModernGlsl(String source, boolean post, boolean entity) {
        if (!GlslVersion.supportedIfDeclared(source)) {
            return true;
        }
        if (post || entity) {
            // Stage declarations convert in these families; the converter still
            // performs the authoritative feature check.
            return GlslTokenRewriter.containsIdentifier(source, CONVERTED_STAGE_SYNTAX::contains);
        }
        return GlslTokenRewriter.containsIdentifier(source, MODERN_STAGE_SYNTAX::contains);
    }

    /**
     * The pack's target deviations that concern a target the program writes, and the integer targets
     * whose mip chain it asks for. The GL spec refuses glGenerateMipmap on a format it cannot filter,
     * so Iris's chain depends on the driver (incomplete, reading zero, or built); Chimera downsamples
     * it nearest (PackTargetImages). A shadow-pass program or a shadowcomp pass writes shadowcolor
     * targets, which colortex deviations never concern (audit FD3).
     */
    static List<String> relevantTargetDeviations(
            String program,
            PackConfig.PackConfigData config,
            PostTargetPlan.ParseResult targetResult
    ) {
        if (targetResult == null || PostTargetPlan.drawsShadowTargets(program)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String deviation : config.deviations()) {
            if (deviation.startsWith("POST_TARGET_INDEX_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FORMAT_APPROXIMATED:")
                    || deviation.startsWith("POST_TARGET_INTEGER_CLEAR_CHIMERA:")) {
                Integer target = PackProbe.targetIndex(deviation);
                if (target != null && targetResult.plan().targetSlots().contains(target)) {
                    result.add(deviation);
                }
            }
        }
        for (int target : targetResult.plan().mipmappedTargets()) {
            Integer format = config.colortexFormats().get(target);
            if (format != null && IrisColorFormats.integer(format)) {
                result.add("POST_TARGET_INTEGER_MIPMAP_CHIMERA:colortex" + target);
            }
        }
        return result;
    }

    static List<Integer> scanTargets(String source) {
        TreeSet<Integer> result = new TreeSet<>();
        Matcher comment = PackProbe.TARGET_COMMENT.matcher(source);
        while (comment.find()) {
            result.addAll(PostTargetPlan.parseCommentTargets(
                    comment.group(1), comment.group(2), new ArrayList<>()));
        }
        Matcher fragData = PackProbe.FRAG_DATA.matcher(source);
        while (fragData.find()) {
            addTarget(result, fragData.group(1));
        }
        if (source.contains("gl_FragColor")) {
            result.add(0);
        }
        return List.copyOf(result);
    }

    static void addTarget(Set<Integer> result, String targetText) {
        try {
            result.add(Integer.parseInt(targetText));
        } catch (NumberFormatException ignored) {
        }
    }
}
