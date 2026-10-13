package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
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

/** Read-only, deterministic inventory of the Chimera pack boundary. */
public final class PackProbe {
    private static final Set<String> STAGE_EXTENSIONS = Set.of(
            ".vsh", ".fsh", ".gsh", ".tcs", ".tes", ".csh");
    private static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile(
            "#define\\s+DRAWBUFFERS(\\d+)");
    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?i)(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,\\s]+)");
    private static final Pattern FRAG_DATA = Pattern.compile(
            "gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern PROPERTY = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*([^\\s#]+)");
    private static final Pattern COLORTEX_FORMAT = Pattern.compile(
            "colortex\\d+Format");
    private static final Pattern SHADOW_SETTING = Pattern.compile(
            "shadowMapResolution|shadowDistance|shadowMapSize|shadowMapFov|shadowDistanceRenderMul");

    private PackProbe() {}

    /** Static analysis result shared by reporting and runtime pipeline loading. */
    public record Analysis(
            ConformanceReport report,
            PackPlan plan,
            PackConfig.PackConfigData config,
            PackSettingsPlan settings,
            PackResolutionPlan resolution
    ) {
        public Analysis(
                ConformanceReport report,
                PackPlan plan,
                PackConfig.PackConfigData config
        ) {
            this(report, plan, config, PackSettingsPlan.empty(), PackResolutionPlan.empty());
        }
    }

    public static ConformanceReport probe(Path packPath) {
        return analyze(packPath).report();
    }

    public static Analysis analyze(Path packPath) {
        try (PackSource.LoadResult loaded = PackSource.loadResult(packPath)) {
            return analyze(packPath, loaded);
        }
    }

    /** Probes an already loaded source so runtime and static reports share one extraction. */
    public static ConformanceReport probe(Path packPath, PackSource.LoadResult loaded) {
        return analyze(packPath, loaded).report();
    }

    /** Builds the report and the immutable runtime plan from one loaded source tree. */
    public static Analysis analyze(Path packPath, PackSource.LoadResult loaded) {
        String packName = logicalPackName(packPath);
        Path shadersDir = loaded.shadersDir();
        PackSettingsPlan settingsPlan = PackSettingsPlan.parse(loaded.rawProgramsAllVariants(), shadersDir,
                PackOptionSources.overrides(packPath));
        loaded.prepare(
                PackEngineDefines.forPack(settingsPlan.preprocessorDefines()),
                PackEngineDefines.lockedNames(settingsPlan.overriddenNames(), false, false));
        settingsPlan = resolveLiveSamplerSlots(settingsPlan, loaded.programs(), shadersDir,
                loaded.selectedVariantFolder());
        Map<String, String> metadataHashes = new TreeMap<>();
        List<String> globalDeviations = new ArrayList<>(loaded.deviations());
        List<String> settings = new ArrayList<>();
        List<String> passInventory = new ArrayList<>();
        Set<String> shadowPropertySettings = new TreeSet<>();
        Set<String> alphaTestPropertySettings = new TreeSet<>();
        boolean passListPresent = false;

        Path passList = shadersDir.resolve("shaders.json");
        if (Files.isRegularFile(passList)) {
            passListPresent = true;
            readMetadata(passList, "shaders.json", metadataHashes, globalDeviations);
            readPassList(passList, passInventory, globalDeviations);
        }

        Path properties = shadersDir.resolve("shaders.properties");
        if (Files.isRegularFile(properties)) {
            readMetadata(properties, "shaders.properties", metadataHashes, globalDeviations);
            readProperties(properties, settings, globalDeviations, shadowPropertySettings,
                    alphaTestPropertySettings);
        }

        if (!Files.isDirectory(shadersDir)) {
            globalDeviations.add("NO_SHADERS_DIRECTORY");
            return new Analysis(report(packName, passListPresent, passInventory, metadataHashes,
                    settings, globalDeviations, loaded), new PackPlan(null, List.of()), null,
                    settingsPlan, PackResolutionPlan.empty());
        }

        Map<String, Inventory> inventories = new TreeMap<>();
        addInventories(loaded.programs(), inventories, shadersDir);
        scanSelectedStageFiles(loaded, inventories);

        if (!passListPresent) {
            passInventory.addAll(inventories.keySet());
        }
        for (String pass : passInventory) {
            inventories.computeIfAbsent(pass, Inventory::new);
        }

        Path blockProperties = shadersDir.resolve("block.properties");
        if (Files.isRegularFile(blockProperties)) {
            readMetadata(blockProperties, "block.properties", metadataHashes, globalDeviations);
            globalDeviations.addAll(PackMaterialResolver.parse(shadersDir,
                    PackEngineDefines.forPack(settingsPlan.preprocessorDefines())).deviations());
        } else {
            Inventory terrain = inventories.get("gbuffers_terrain");
            if (terrain != null && terrain.stages.contains("vertex")) {
                globalDeviations.add("BLOCK_PROPERTIES_MISSING");
            }
        }

        Path entityProperties = shadersDir.resolve("entity.properties");
        PackEntityIdResolver.ParseResult entityIds = PackEntityIdResolver.parse(shadersDir);
        if (Files.isRegularFile(entityProperties)) {
            readMetadata(entityProperties, "entity.properties", metadataHashes, globalDeviations);
            globalDeviations.addAll(entityIds.deviations());
        }
        if (loaded.programs().stream().anyMatch(program ->
                FamilyAdapterRegistry.isWorldEntityFamily(program.name())
                        && program.vertexSource() != null
                        && program.fragmentSource() != null)) {
            globalDeviations.add(entityIds.present()
                    ? "ENTITY_ID_MAP_APPLIED" : "ENTITY_ID_DEFAULTED");
        }

        PackConfig.PackConfigData packConfig = PackConfig.parse(loaded.programs(), shadersDir, settingsPlan);
        PackResolutionPlan resolution = PackResolutionPlan.build(
                loaded.selectedDimension(), loaded.selectedVariantFolder(),
                loaded.programs(), settingsPlan);
        PackAdvancedResourcePlan bufferCatalog = PackAdvancedResourcePlan.catalog(settingsPlan);
        PackPlan initialPlan = PackPlanBuilder.build(
                loaded.programs(), packConfig, entityIds.resolver(), resolution, bufferCatalog);
        PackResourcePlan resourcePlan = PackResourcePlan.build(initialPlan, shadersDir);
        PackAdvancedResourcePlan advancedPlan = PackAdvancedResourcePlan.build(initialPlan, shadersDir);

        // Some real packs intentionally hide their writable-image producers
        // behind the Iris custom-image capability. The first plan tells us
        // whether the pack declares a supported image set and a compute stage;
        // only then do we negotiate that capability and rebuild every plan
        // from the same prepared source snapshot. Packs without that contract
        // retain their original preparation and fallback behavior.
        if (advancedPlan.hasSupportedImages()
                && advancedPlan.computeStages().containsKey("shadowcomp")) {
            loaded.prepare(
                    PackEngineDefines.forCustomImages(settingsPlan.preprocessorDefines()),
                    PackEngineDefines.lockedNames(settingsPlan.overriddenNames(), true, false));
            settingsPlan = resolveLiveSamplerSlots(settingsPlan, loaded.programs(), shadersDir,
                    loaded.selectedVariantFolder());
            globalDeviations.addAll(loaded.deviations());
            inventories.values().forEach(inventory -> {
                inventory.preparedSources.clear();
                inventory.deviations.clear();
            });
            addInventories(loaded.programs(), inventories, shadersDir);
            packConfig = PackConfig.parse(loaded.programs(), shadersDir, settingsPlan);
            resolution = PackResolutionPlan.build(
                    loaded.selectedDimension(), loaded.selectedVariantFolder(),
                    loaded.programs(), settingsPlan);
            bufferCatalog = PackAdvancedResourcePlan.catalog(settingsPlan);
            initialPlan = PackPlanBuilder.build(
                    loaded.programs(), packConfig, entityIds.resolver(), resolution, bufferCatalog);
            resourcePlan = PackResourcePlan.build(initialPlan, shadersDir);
            advancedPlan = PackAdvancedResourcePlan.build(initialPlan, shadersDir);
        }
        // The first pass admits only property-declared blocks so the full
        // source scan can build exact stage masks and bindings. Rebuild once
        // from that immutable result so probing and pipeline construction use
        // the same storage-buffer eligibility and rewritten GLSL.
        initialPlan = PackPlanBuilder.build(
                loaded.programs(), packConfig, entityIds.resolver(), resolution, advancedPlan);
        resourcePlan = PackResourcePlan.build(initialPlan, shadersDir);
        advancedPlan = PackAdvancedResourcePlan.build(initialPlan, shadersDir);
        initialPlan = PackPlanBuilder.build(
                loaded.programs(), packConfig, entityIds.resolver(), resolution, advancedPlan);
        resourcePlan = PackResourcePlan.build(initialPlan, shadersDir);
        PackPlan packPlan = new PackPlan(packConfig, initialPlan.programs(), entityIds.resolver(),
                settingsPlan, resolution, resourcePlan, advancedPlan);
        globalDeviations.addAll(packConfig.deviations());
        // Keep standard sampler aliases program-scoped. Adding every binding
        // deviation to the pack-wide list changes old fixture support status
        // even when the alias is already part of that family's contract.
        globalDeviations.addAll(settingsPlan.resourceDeviations());
        settingsPlan.deviations().stream()
                .filter(value -> value.startsWith("CUSTOM_IMAGE_UNSUPPORTED:")
                        || value.startsWith("STORAGE_RESOURCE_UNSUPPORTED:"))
                .forEach(globalDeviations::add);
        applyShadowPropertyReporting(inventories, shadowPropertySettings,
                packConfig.shadowSettings(), globalDeviations);
        applyAlphaTestPropertyReporting(packPlan, alphaTestPropertySettings, globalDeviations);

        ConformanceReport report = report(packName, passListPresent, passInventory, metadataHashes,
                settings, globalDeviations, loaded);
        // Aliases are real requested-family plans, not synthetic host fallbacks.
        // Inventory the selected source too so probe and runtime share admission.
        for (PackProgramPlan planned : packPlan.programs()) {
            PackProgramResolution resolved = resolution.resolution(planned.name());
            if (resolved == null || !resolved.executable()
                    || resolved.requestedProgram().equals(resolved.selectedProgram())) continue;
            PackProgram program = planned.program();
            Inventory inventory = new Inventory(program.name());
            inventory.variantFolder = program.variantFolder();
            addSource(inventory, "fragment", program.fragmentPath(), program.fragmentSource(),
                    program.preparedFragmentSource(), shadersDir);
            if (program.vertexSource() != null) addSource(inventory, "vertex", program.vertexPath(),
                    program.vertexSource(), program.preparedVertexSource(), shadersDir);
            inventory.deviations.addAll(program.preparationDeviations());
            inventories.put(program.name(), inventory);
        }
        for (Inventory inventory : inventories.values()) {
            report.addProgram(toProgram(inventory, packConfig, packPlan.program(inventory.name),
                    resourcePlan, packPlan));
        }
        return new Analysis(report, packPlan, packConfig, settingsPlan, resolution);
    }

    private static ConformanceReport report(
            String packName,
            boolean passListPresent,
            Collection<String> passInventory,
            Map<String, String> metadataHashes,
            Collection<String> settings,
            Collection<String> deviations,
            PackSource.LoadResult loaded
    ) {
        if (loaded.selectedVariantFolder().isBlank()) {
            return new ConformanceReport(packName, passListPresent, passInventory,
                    metadataHashes, settings, deviations);
        }
        return new ConformanceReport(packName, passListPresent, passInventory,
                metadataHashes, settings, deviations,
                loaded.selectedDimension(), loaded.selectedVariantFolder());
    }

    private static void scanSelectedStageFiles(
            PackSource.LoadResult loaded,
            Map<String, Inventory> inventories
    ) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(loaded.selectedSourceDir())) {
            List<Path> files = new ArrayList<>();
            for (Path path : stream) {
                if (Files.isRegularFile(path) && isStageFile(path)) {
                    files.add(path);
                }
            }
            files.sort(Comparator.comparing(path -> relativePath(loaded.shadersDir(), path)));
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                String base = fileName.substring(0, fileName.lastIndexOf('.'));
                Inventory inventory = inventories.computeIfAbsent(base, Inventory::new);
                String extension = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
                String stage = stageName(extension);
                if (inventory.sourcePaths.containsKey(stage)) {
                    continue;
                }
                try {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    addSource(inventory, stage, file, source, null, loaded.shadersDir());
                } catch (IOException e) {
                    inventory.deviations.add("SOURCE_READ_FAILED");
                }
            }
        } catch (IOException ignored) {
            // The load result already carries the path failure. The report remains deterministic.
        }
    }

    /** Records each program's fragment and vertex sources, and its preparation deviations. */
    private static void addInventories(List<PackProgram> programs, Map<String, Inventory> inventories,
                                       Path shadersDir) {
        for (PackProgram program : programs) {
            Inventory inventory = inventories.computeIfAbsent(program.name(), Inventory::new);
            inventory.variantFolder = program.variantFolder();
            addSource(inventory, "fragment", program.fragmentPath(), program.fragmentSource(),
                    program.preparedFragmentSource(), shadersDir);
            if (program.vertexSource() != null) {
                addSource(inventory, "vertex", program.vertexPath(), program.vertexSource(),
                        program.preparedVertexSource(), shadersDir);
            }
            inventory.deviations.addAll(program.preparationDeviations());
        }
    }

    private static void addSource(
            Inventory inventory,
            String stage,
            Path path,
            String source,
            String prepared,
            Path root
    ) {
        if (path == null || source == null) {
            return;
        }
        String relative = relativePath(root, path);
        inventory.stages.add(stage);
        inventory.sourceHashes.put(relative, ConformanceReport.textSha256(source));
        inventory.sources.putIfAbsent(stage, source);
        if (prepared != null) {
            inventory.preparedSources.putIfAbsent(stage, prepared);
        }
        inventory.sourcePaths.putIfAbsent(stage, path);
    }

    public static String logicalPackName(Path packPath) {
        if (packPath == null || packPath.getFileName() == null) {
            return "pack";
        }
        String name = packPath.getFileName().toString();
        return name.toLowerCase().endsWith(".zip")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    private static PackSettingsPlan resolveLiveSamplerSlots(
            PackSettingsPlan settings,
            List<PackProgram> programs,
            Path shadersDir,
            String selectedFolder
    ) {
        if (settings == null) return PackSettingsPlan.empty();
        Set<String> live = new TreeSet<>();
        if (programs != null) {
            for (PackProgram program : programs) {
                if (program == null) continue;
                if (program.preparedFragmentSource() != null) {
                    GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(
                            program.preparedFragmentSource());
                    live.addAll(usage.liveSamplers());
                    live.addAll(usage.liveImages());
                }
                if (program.preparedVertexSource() != null) {
                    GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(
                            program.preparedVertexSource());
                    live.addAll(usage.liveSamplers());
                    live.addAll(usage.liveImages());
                }
            }
        }
        boolean customImages = settings.propertyValues().keySet().stream()
                .anyMatch(name -> name.startsWith("image."));
        live.addAll(PackAdvancedResourcePlan.liveComputeResourceNames(
                shadersDir, selectedFolder, settings, customImages));
        return settings.withSamplerSlots(settings.customSamplerSlots(live));
    }

    private static ConformanceReport.ProgramReport toProgram(
            Inventory inventory,
            PackConfig.PackConfigData packConfig,
            PackProgramPlan programPlan,
            PackResourcePlan resourcePlan,
            PackPlan packPlan
    ) {
        String name = inventory.name;
        PackAdvancedResourcePlan.ComputeSpec computeSpec = packPlan == null
                ? null : packPlan.advancedResources().computeStages().get(name);
        if (computeSpec != null && inventory.stages.contains("compute")) {
            return computeProgram(inventory, computeSpec, packPlan);
        }
        boolean relaxed = !inventory.variantFolder.isBlank();
        Map<String, String> sourceView = relaxed && !inventory.preparedSources.isEmpty()
                ? inventory.preparedSources : inventory.sources;
        StringBuilder combinedSource = new StringBuilder();
        sourceView.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> combinedSource.append(entry.getValue()).append('\n'));
        String source = combinedSource.toString();
        String fragment = sourceView.getOrDefault("fragment", source);
        String stripped = stripComments(source);
        boolean postName = PostTargetPlan.isPostProgramName(name);
        boolean hasFragment = inventory.stages.contains("fragment");
        boolean modern = !modernTerrainPlan(name, programPlan) && !validatedConversion(inventory, programPlan)
                && usesUnsupportedModernGlsl(stripped, postName, FamilyAdapterRegistry.isEntityLike(name));
        List<String> samplers = UniformRegistry.scanDeclaredSamplerNames(stripped);
        // The report has historically exposed all uniform declarations,
        // including samplers. Keep that public inventory stable while the
        // shared interface plan continues to separate samplers for bindings.
        TreeSet<String> uniformNames = new TreeSet<>(samplers);
        uniformNames.addAll(UniformRegistry.scanUniformDeclarations(stripped).stream()
                .map(UniformRegistry.UniformDeclaration::name).toList());
        PostTargetPlan.ParseResult targetResult = targetResult(inventory, fragment, packConfig, programPlan, postName);
        List<Integer> targets = targetResult != null
                ? targetResult.plan().targetSlots() : scanTargets(fragment);

        TreeSet<String> deviations = new TreeSet<>(inventory.deviations);
        if (resourcePlan != null) {
            resourcePlan.deviationsForProgram(name).stream()
                    .filter(value -> !value.startsWith("STANDARD_RESOURCE_ALIAS:")
                            && !value.startsWith("SHADOW_RESOURCE_ALIAS:"))
                    .forEach(deviations::add);
        }
        deviations.addAll(relevantTargetDeviations(packConfig.deviations(), targetResult));
        UniformRegistry.ProgramInterface interfacePlan =
                interfacePlan(name, fragment, programPlan, targetResult, relaxed, postName);
        if (programPlan != null) {
            deviations.addAll(programPlan.deviations());
        }
        deviations.addAll(interfacePlan.deviations());
        addTargetRouteDeviations(targetResult, postName, programPlan, deviations);
        if (programPlan == null && postName && hasFragment && !modern && targetResult != null
                && targetResult.executable() && interfacePlan.executable()) {
            Path sourcePath = relaxed && !inventory.preparedSources.isEmpty()
                    ? null : inventory.sourcePaths.get("fragment");
            String converted = FragmentConverter.convertPostFragment(
                    fragment, sourcePath, interfacePlan, targetResult.plan(),
                    packConfig.shaderConstants());
            if (converted == null) {
                deviations.addAll(PostVertexBridge.postVaryingDeviations(fragment));
                deviations.add("POST_CONVERTER_UNSUPPORTED");
            }
        }
        if (postName) {
            addPackConstantDeviations(fragment, packConfig, deviations);
        }

        boolean hasVertex = inventory.stages.contains("vertex");
        String vertex = inventory.preparedSources.getOrDefault("vertex", inventory.sources.get("vertex"));
        String vertexBridge = programPlan != null
                ? plannedVertexBridge(name, hasVertex, programPlan, postName)
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
        if (inventory.stages.stream().anyMatch(stage ->
                stage.equals("geometry") || stage.equals("tess_control")
                        || stage.equals("tess_evaluation") || stage.equals("compute"))) {
            deviations.add("UNSUPPORTED_PACK_STAGE");
        }
        if (modern) {
            deviations.add("MODERN_GLSL_UNSUPPORTED");
        }
        if (PackAdvancedResourcePlan.containsAdvancedDeclarations(source)
                && !advancedResourcesArePlanned(name, packPlan)) {
            deviations.add("ADVANCED_RESOURCE_UNSUPPORTED");
        }
        // PackPlanBuilder already resolved standard aliases and pack-owned
        // sampler slots through the shared interface plan. Keep the legacy
        // scan only for compatibility callers that do not have a plan.
        if (!relaxed && programPlan == null) {
            addUnmappedSamplerDeviations(name, samplers, deviations);
        }
        if (samplers.contains("noisetex")) {
            // Runtime performs the exact file check. The report records the
            // standard resource requirement without embedding a path.
            deviations.add("NOISETEX_PACK_RESOURCE");
        }
        boolean authoredShadowOutputs = FamilyAdapterRegistry.isShadowFamily(name) && programPlan != null
                && programPlan.convertedFragment() != null && programPlan.executable();
        if (!authoredShadowOutputs && targetResult == null && targets.size() > 1) {
            deviations.add(FamilyAdapterRegistry.isShadowFamily(name)
                    ? "SHADOW_COLOR_TARGET_UNSUPPORTED" : "MRT_NOT_SUPPORTED");
        } else if (!authoredShadowOutputs && targetResult == null && !targets.isEmpty() && targets.get(0) != 0) {
            deviations.add("TARGET_ROUTING_FIXED_TO_COLORTEX0");
        }
        if (!hasFragment) {
            deviations.add("MISSING_FRAGMENT_SOURCE");
        }

        return new ConformanceReport.ProgramReport(
                name, familyOf(name), modern ? "MODERN_GLSL" : "LEGACY_GLSL",
                sorted(inventory.stages), inventory.sourceHashes, samplers, List.copyOf(uniformNames),
                targets, supportStatus(executableName(name, inventory, postName), hasFragment, deviations),
                ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED, List.copyOf(deviations));
    }

    /** Modern terrain, water and shadow sources run through their own planned bridge. */
    private static boolean modernTerrainPlan(String name, PackProgramPlan programPlan) {
        return (name.equals("gbuffers_terrain") || name.equals("gbuffers_water") || name.equals("shadow"))
                && programPlan != null && programPlan.terrainMaterial().modern();
    }

    /**
     * A validated conversion is authoritative for every family, not just post and particles: the
     * lexical modern-GLSL veto must not reject a program whose converters already accepted it.
     */
    private static boolean validatedConversion(Inventory inventory, PackProgramPlan programPlan) {
        return programPlan != null && programPlan.executable() && programPlan.convertedFragment() != null
                && (!inventory.stages.contains("vertex") || programPlan.convertedVertex() != null);
    }

    private static PostTargetPlan.ParseResult targetResult(
            Inventory inventory,
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

    private static UniformRegistry.ProgramInterface interfacePlan(
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

    private static void addTargetRouteDeviations(
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
    private static boolean executableName(String name, Inventory inventory, boolean postName) {
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

    private static ConformanceReport.SupportStatus supportStatus(
            boolean executableName, boolean hasFragment, Collection<String> deviations) {
        if (!executableName) {
            return ConformanceReport.SupportStatus.UNSUPPORTED;
        }
        if (!hasFragment || hasSevereDeviation(deviations)) {
            return ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        }
        return deviations.isEmpty() ? ConformanceReport.SupportStatus.SUPPORTED
                : ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
    }

    /** How a planned program's vertex stage is bridged, as its report names it; null when it has none. */
    private static String plannedVertexBridge(
            String name, boolean hasVertex, PackProgramPlan plan, boolean postName) {
        boolean converted = plan.convertedVertex() != null;
        if (hasVertex) {
            if (FamilyAdapterRegistry.isCloudFamily(name) && plan.cloudDrawsNothing()) {
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
                return bridge;
            }
            if (converted && postName) {
                return plan.deviations().contains("POST_VERTEX_AUTHORED_TRANSLATED")
                        ? "POST_VERTEX_AUTHORED_TRANSLATED" : "POST_VERTEX_ADAPTER";
            }
        }
        return unbridgedVertex(name, hasVertex);
    }

    /** The bridge a program without a plan would get, judged from its authored sources alone. */
    private static String unplannedVertexBridge(String name, boolean hasVertex, String vertex, String fragment) {
        if (hasVertex) {
            if (FamilyAdapterRegistry.isCloudFamily(name) && AuthoredOutput.producesNothing(vertex, fragment)) {
                return PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT;
            }
            String bridge = switch (name) {
                case "gbuffers_terrain" -> TerrainVertexBridge.supportsTerrainVertex(vertex, fragment)
                        ? "LEGACY_TERRAIN_VERTEX_BRIDGE" : "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
                case "gbuffers_water" -> TerrainVertexBridge.supportsModernTerrain(vertex, fragment)
                        ? "MODERN_WATER_VERTEX_BRIDGE"
                        : TerrainVertexBridge.supportsTerrainVertex(vertex, fragment)
                        ? "TRANSLUCENT_VERTEX_BRIDGE" : "MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED";
                case "shadow" -> bridge("SHADOW", TerrainVertexBridge.supportsModernShadow(vertex, fragment)
                        || TerrainVertexBridge.supportsShadowVertex(vertex, fragment));
                case "gbuffers_entities" -> bridge("ENTITY", EntityVertexBridge.supportsEntityVertex(vertex, fragment));
                case "gbuffers_block" -> bridge("BLOCK", EntityVertexBridge.supportsEntityVertex(vertex, fragment));
                case "gbuffers_hand" -> bridge("HAND", EntityVertexBridge.supportsHandVertex(vertex, fragment));
                default -> FamilyAdapterRegistry.isParticleLike(name) || FamilyAdapterRegistry.isWeatherFamily(name)
                        ? bridge(vertexBridgeLabel(name),
                        EntityVertexBridge.convertParticleVertex(vertex, null, fragment, Map.of()) != null)
                        : FamilyAdapterRegistry.isSkyFamily(name)
                        ? bridge("SKY", HostLayoutVertexBridge.convertSkyVertex(vertex, null, fragment,
                        name.equals("gbuffers_skytextured")) != null)
                        : FamilyAdapterRegistry.isCloudFamily(name)
                        ? bridge("CLOUD", HostLayoutVertexBridge.convertCloudVertex(vertex, null, fragment) != null)
                        : null;
            };
            if (bridge != null) {
                return bridge;
            }
        }
        return unbridgedVertex(name, hasVertex);
    }

    /**
     * The label of a family's authored vertex bridge, or null for one without: terrain and water
     * name theirs by the material contract.
     */
    private static String vertexBridgeLabel(String name) {
        if (name.equals("shadow")) return "SHADOW";
        if (name.equals("gbuffers_entities")) return "ENTITY";
        if (name.equals("gbuffers_block")) return "BLOCK";
        if (name.equals("gbuffers_hand")) return "HAND";
        if (FamilyAdapterRegistry.isShadowEntityFamily(name)) return "SHADOW_ENTITY";
        if (FamilyAdapterRegistry.isParticleLike(name)) return "PARTICLE";
        if (FamilyAdapterRegistry.isWeatherFamily(name)) return "WEATHER";
        if (FamilyAdapterRegistry.isSkyFamily(name)) return "SKY";
        if (FamilyAdapterRegistry.isCloudFamily(name)) return "CLOUD";
        return FamilyAdapterRegistry.hostLayoutLane(name);
    }

    private static String bridge(String label, boolean converted) {
        return label + (converted ? "_VERTEX_BRIDGE" : "_VERTEX_BRIDGE_UNSUPPORTED");
    }

    /** A vertex stage no bridge serves: water draws on the fixed host vertex, shadow programs fail. */
    private static String unbridgedVertex(String name, boolean hasVertex) {
        if (name.equals("gbuffers_water")) return "FIXED_VERTEX_SUBSTITUTION";
        if (name.equals("shadow")) return "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
        if (FamilyAdapterRegistry.isShadowEntityFamily(name)) return "SHADOW_ENTITY_VERTEX_BRIDGE_UNSUPPORTED";
        return hasVertex ? "FIXED_VERTEX_SUBSTITUTION" : null;
    }

    /** What an executable plan of each family records about the host state its draws keep. */
    private static List<String> installedFamilyDeviations(String name) {
        FamilyAdapterPlan family = FamilyAdapterRegistry.forProgram(name);
        return switch (family.family()) {
            case ENTITY -> List.of("ENTITY_VERTEX_FORMAT_EXTENDED", "ENTITY_STATE_FIXED_TO_HOST",
                    "ENTITY_BATCH_ORIGIN_SPLIT", "ENTITY_SCREEN_DRAW_FALLBACK");
            case ENTITY_TRANSLUCENT, SPIDER_EYES -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_STATE_FIXED_TO_HOST");
            case BLOCK -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "BLOCK_ENTITY_ID_DEFAULTED", "ENTITY_STATE_FIXED_TO_HOST");
            case DAMAGED_BLOCK -> List.of("DAMAGED_BLOCK_HOST_FORMAT_INSTALLED");
            case HAND -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "HAND_ITEM_ID_DEFAULTED", "HAND_STATE_FIXED_TO_HOST");
            case HAND_WATER -> List.of("FAMILY_ADAPTER_INSTALLED", "HAND_TRANSLUCENT_ITEM_PHASE");
            case PARTICLE -> List.of("FAMILY_ADAPTER_INSTALLED", "PARTICLE_STATE_FIXED_TO_HOST");
            case PARTICLE_TRANSLUCENT -> List.of("FAMILY_ADAPTER_INSTALLED", "PARTICLE_TRANSLUCENT_INSTALLED");
            case WEATHER -> List.of("FAMILY_ADAPTER_INSTALLED", "WEATHER_STATE_FIXED_TO_HOST");
            // Depth and cull follow each caster's host pipeline; blending is off, as in Iris.
            case SHADOW_ENTITIES -> List.of("FAMILY_ADAPTER_INSTALLED", "ENTITY_VERTEX_FORMAT_EXTENDED",
                    "SHADOW_ENTITY_STATE_FIXED_TO_HOST");
            default -> family.vertexContract().hostLayout()
                    ? List.of("FAMILY_ADAPTER_INSTALLED", family.family().name() + "_STATE_FIXED_TO_HOST")
                    : List.of();
        };
    }

    /** Declared samplers a program without a plan could not bind, by the slot map of its stage. */
    private static void addUnmappedSamplerDeviations(String name, List<String> samplers,
                                                     Collection<String> deviations) {
        boolean entityStage = FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name)
                || FamilyAdapterRegistry.isWeatherFamily(name)
                || FamilyAdapterRegistry.isHostLayoutFamily(name);
        Map<String, Integer> knownSamplers = name.equals("gbuffers_terrain")
                ? UniformRegistry.GEOMETRY_NAME_TO_SLOT
                : FamilyAdapterRegistry.isShadowFamily(name) ? UniformRegistry.SHADOW_NAME_TO_SLOT
                : name.equals("gbuffers_water") ? UniformRegistry.TRANSLUCENT_NAME_TO_SLOT
                : entityStage || FamilyAdapterRegistry.isSkyFamily(name) || FamilyAdapterRegistry.isCloudFamily(name)
                ? UniformRegistry.ENTITY_NAME_TO_SLOT
                : UniformRegistry.NAME_TO_SLOT;
        for (String sampler : samplers) {
            if (knownSamplers.containsKey(sampler)) {
                continue;
            }
            deviations.add(name.equals("gbuffers_water")
                    ? (sampler.startsWith("depthtex") || sampler.startsWith("shadowcolor")
                    ? "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"
                    : "TRANSLUCENT_SAMPLER_UNSUPPORTED:" + sampler)
                    : FamilyAdapterRegistry.isShadowFamily(name) ? "SHADOW_SAMPLER_UNSUPPORTED:" + sampler
                    : entityStage ? "ENTITY_SAMPLER_UNSUPPORTED:" + sampler
                    : "SAMPLER_NOT_MAPPED:" + sampler);
        }
    }

    /** Builds a truthful report for a native compute stage without applying graphics rules. */
    private static ConformanceReport.ProgramReport computeProgram(
            Inventory inventory,
            PackAdvancedResourcePlan.ComputeSpec computeSpec,
            PackPlan packPlan
    ) {
        TreeSet<String> deviations = new TreeSet<>(inventory.deviations);
        deviations.addAll(computeSpec.deviations());
        boolean capability = packPlan != null
                && packPlan.advancedResources().capabilityPossible();
        boolean executable = computeSpec.supported() && capability;
        if (executable) {
            deviations.add("COMPUTE_NATIVE_BRIDGE");
        } else if (!computeSpec.supported()) {
            deviations.add("COMPUTE_STAGE_UNSUPPORTED:" + computeSpec.program());
        } else {
            deviations.add("COMPUTE_CAPABILITY_UNAVAILABLE:" + computeSpec.program());
        }
        ConformanceReport.SupportStatus support = executable
                ? ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION
                : ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        ConformanceReport.RuntimeDisposition runtime = executable
                ? ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED
                : ConformanceReport.RuntimeDisposition.IDENTITY_FALLBACK;
        String source = inventory.sources.getOrDefault("compute", "");
        String stripped = stripComments(source);
        List<String> samplers = UniformRegistry.scanDeclaredSamplerNames(stripped);
        TreeSet<String> uniforms = new TreeSet<>(samplers);
        uniforms.addAll(UniformRegistry.scanUniformDeclarations(stripped).stream()
                .map(UniformRegistry.UniformDeclaration::name).toList());
        return new ConformanceReport.ProgramReport(
                inventory.name,
                "compute",
                "COMPUTE_GLSL",
                List.of("compute"),
                inventory.sourceHashes,
                samplers,
                List.copyOf(uniforms),
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
    private static boolean advancedResourcesArePlanned(String program, PackPlan packPlan) {
        if (packPlan == null || packPlan.advancedResources() == null) {
            return false;
        }
        PackAdvancedResourcePlan advanced = packPlan.advancedResources();
        boolean usesImages = advanced.dependentPrograms().contains(program);
        boolean usesStorage = advanced.bufferDependentPrograms().contains(program);
        if (!usesImages && !usesStorage) {
            return false;
        }
        boolean imagesReady = !usesImages || advanced.capabilityPossible();
        boolean storageReady = !usesStorage || advanced.storageBufferProgramSupported(program);
        return imagesReady && storageReady;
    }

    /** A host-layout lane (LINE, GLINT, LIGHTNING, ...) whose vertex could not be bridged. */
    private static final Set<String> HOST_LAYOUT_BRIDGE_FAILURES = FamilyAdapterRegistry.hostLayoutLanes().stream()
            .map(lane -> lane + "_VERTEX_BRIDGE_UNSUPPORTED")
            .collect(Collectors.toUnmodifiableSet());

    /** Deviations that leave a program on the identity fallback. */
    private static final Set<String> SEVERE_DEVIATIONS = Set.of(
            "UNSUPPORTED_PACK_STAGE", "MODERN_GLSL_UNSUPPORTED", "ADVANCED_RESOURCE_UNSUPPORTED",
            "MRT_NOT_SUPPORTED", "TARGET_ROUTING_FIXED_TO_COLORTEX0",
            "POST_TARGET_DIRECTIVE_MALFORMED", "POST_TARGET_DIRECTIVE_CONFLICT", "FINAL_MRT_UNSUPPORTED",
            "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED", "MODERN_TERRAIN_VERTEX_BRIDGE_UNSUPPORTED",
            "MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED", "SHADOW_VERTEX_BRIDGE_UNSUPPORTED",
            "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED", "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED",
            "ENTITY_VERTEX_BRIDGE_UNSUPPORTED", "BLOCK_VERTEX_BRIDGE_UNSUPPORTED",
            "HAND_VERTEX_BRIDGE_UNSUPPORTED", "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED",
            "WEATHER_VERTEX_BRIDGE_UNSUPPORTED", "SKY_VERTEX_BRIDGE_UNSUPPORTED",
            "CLOUD_VERTEX_BRIDGE_UNSUPPORTED", "DAMAGED_BLOCK_HOST_FORMAT_UNSUPPORTED",
            "SHADOW_COLOR_INPUT_UNSUPPORTED", "MISSING_FRAGMENT_SOURCE", "POST_CONVERTER_UNSUPPORTED");

    /** Deviation families that do the same, matched by prefix. */
    private static final List<String> SEVERE_PREFIXES = List.of(
            "POST_TARGET_DIRECTIVE_DUPLICATE:", "POST_TARGET_INDEX_UNSUPPORTED:",
            "POST_TARGET_FORMAT_UNSUPPORTED:", "ENTITY_SAMPLER_UNSUPPORTED:", "ENTITY_ID_UNSUPPORTED:",
            "UNIFORM_TYPE_UNSUPPORTED:", "UNIFORM_NAME_UNSUPPORTED:", "UNIFORM_CONFLICT:",
            "SAMPLER_NOT_MAPPED:", "SAMPLER_SLOT_CONFLICT:", "PACK_TEXTURE_MISSING:",
            "PACK_RAW_TEXTURE_UNSUPPORTED:", "PACK_TEXTURE_PATH_UNSAFE:", "PACK_TEXTURE_LOAD_FAILED:",
            "PACK_RESOURCE_SLOT_LIMIT:", "STANDARD_RESOURCE_UNAVAILABLE:", "SHADOW_SAMPLER_UNSUPPORTED:",
            "TRANSLUCENT_SAMPLER_UNSUPPORTED:", "GBUFFER_TARGET_FEEDBACK:", "PROGRAM_", "SOURCE_INCLUDE_",
            "TRANSLATION_UNSUPPORTED:", "STORAGE_BUFFER_", "POST_VARYING_UNSUPPORTED:",
            "ALPHA_TEST_MALFORMED:", "LEGACY_FOG_FIELD_UNSUPPORTED:", "SOURCE_NORMALIZATION_FAILED:",
            "PREPROCESSOR_");

    /** Members of a severe family that only record a fact. */
    private static final List<String> BENIGN_PREFIXES = List.of(
            "PROGRAM_ALIAS_RECORDED:", "PREPROCESSOR_MACRO_REDEFINED:");

    private static boolean hasSevereDeviation(Collection<String> deviations) {
        return deviations.stream().anyMatch(PackProbe::isSevere);
    }

    private static boolean isSevere(String deviation) {
        if (SEVERE_DEVIATIONS.contains(deviation) || HOST_LAYOUT_BRIDGE_FAILURES.contains(deviation)) return true;
        return BENIGN_PREFIXES.stream().noneMatch(deviation::startsWith)
                && SEVERE_PREFIXES.stream().anyMatch(deviation::startsWith);
    }

    private static void addPackConstantDeviations(
            String source,
            PackConfig.PackConfigData packConfig,
            Collection<String> deviations
    ) {
        String stripped = stripComments(source);
        for (String name : packConfig.shaderConstants().keySet()) {
            if (!Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(stripped).find()) {
                continue;
            }
            if (packConfig.shadowSettings().deviations().contains(
                    "SHADOW_SETTING_DEFAULTED:" + name)) {
                deviations.add("PACK_CONSTANT_DEFAULTED:" + name);
            } else {
                deviations.add("PACK_CONSTANT_INJECTED:" + name);
            }
        }
    }

    /**
     * Labels a program whose conversion failed: a version outside the one rule
     * (GlslVersion), or syntax beyond the legacy subset. A validated conversion
     * clears it, so the version number alone never rejects a program.
     */
    private static boolean usesUnsupportedModernGlsl(String source, boolean post, boolean entity) {
        if (!GlslVersion.supportedIfDeclared(source)) {
            return true;
        }
        if (post || entity) {
            // Stage declarations convert in these families; the converter still
            // performs the authoritative feature check.
            return source.matches("(?s).*\\b(?:layout|buffer|shared|subroutine)\\b.*");
        }
        return source.matches("(?s).*\\b(?:layout|in|out|flat|noperspective|buffer)\\b.*");
    }

    private static List<String> relevantTargetDeviations(
            Collection<String> configDeviations,
            PostTargetPlan.ParseResult targetResult
    ) {
        if (targetResult == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String deviation : configDeviations) {
            if (deviation.startsWith("POST_TARGET_INDEX_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FORMAT_APPROXIMATED:")) {
                Integer target = targetIndex(deviation);
                if (target != null && targetResult.plan().targetSlots().contains(target)) {
                    result.add(deviation);
                }
            }
        }
        return result;
    }

    private static Integer targetIndex(String deviation) {
        Matcher index = Pattern.compile("POST_TARGET_INDEX_UNSUPPORTED:(\\d+)").matcher(deviation);
        if (index.matches()) {
            return Integer.parseInt(index.group(1));
        }
        Matcher format = Pattern.compile("POST_TARGET_FORMAT_(?:UNSUPPORTED|APPROXIMATED):colortex(\\d+)")
                .matcher(deviation);
        if (format.find()) {
            return Integer.parseInt(format.group(1));
        }
        return null;
    }

    private static List<Integer> scanTargets(String source) {
        TreeSet<Integer> result = new TreeSet<>();
        Matcher define = DRAWBUFFERS_DEFINE.matcher(source);
        if (define.find()) {
            addSequentialTargets(result, define.group(1));
        }
        Matcher comment = TARGET_COMMENT.matcher(source);
        while (comment.find()) {
            result.addAll(PostTargetPlan.parseCommentTargets(
                    comment.group(1), comment.group(2), new ArrayList<>()));
        }
        Matcher fragData = FRAG_DATA.matcher(source);
        while (fragData.find()) {
            addTarget(result, fragData.group(1));
        }
        if (source.contains("gl_FragColor")) {
            result.add(0);
        }
        return List.copyOf(result);
    }

    private static void addSequentialTargets(Set<Integer> result, String targetText) {
        try {
            int count = Integer.parseInt(targetText);
            for (int index = 0; index < count; index++) {
                result.add(index);
            }
        } catch (NumberFormatException ignored) {
        }
    }

    private static void addTarget(Set<Integer> result, String targetText) {
        try {
            result.add(Integer.parseInt(targetText));
        } catch (NumberFormatException ignored) {
        }
    }

    private static void readPassList(Path passList, List<String> passes, List<String> deviations) {
        try {
            JsonObject object = JsonParser.parseString(
                    Files.readString(passList, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement programs = object.get("programs");
            if (programs == null || !programs.isJsonArray()) {
                deviations.add("SHADERS_JSON_MISSING_PROGRAMS");
                return;
            }
            for (JsonElement element : programs.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonElement name = element.getAsJsonObject().get("name");
                if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()) {
                    passes.add(name.getAsString());
                }
            }
        } catch (Exception e) {
            deviations.add("SHADERS_JSON_INVALID");
        }
    }

    private static void readProperties(
            Path properties,
            List<String> settings,
            List<String> deviations,
            Set<String> shadowPropertySettings,
            Set<String> alphaTestPropertySettings
    ) {
        try {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                Matcher matcher = PROPERTY.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                String key = matcher.group(1);
                settings.add(key);
                boolean colortexFormat = COLORTEX_FORMAT.matcher(key).matches();
                boolean shadowSetting = SHADOW_SETTING.matcher(key).matches();
                boolean customValue = key.matches(
                        "(?:uniform|variable)\\.(?:float|int|bool|vec2|vec3|vec4)\\.[A-Za-z_]\\w*");
                boolean alphaTest = key.equals("alphaTest.gbuffers_terrain")
                        || key.equals("alphaTest.gbuffers_water");
                if (shadowSetting) {
                    shadowPropertySettings.add(key);
                } else if (alphaTest) {
                    alphaTestPropertySettings.add(key);
                } else if (!colortexFormat && !customValue && !alphaTest) {
                    deviations.add("SETTING_NOT_APPLIED:" + key);
                }
            }
        } catch (IOException e) {
            deviations.add("SHADERS_PROPERTIES_READ_FAILED");
        }
    }

    private static void applyAlphaTestPropertyReporting(
            PackPlan packPlan,
            Set<String> alphaTestPropertySettings,
            List<String> deviations
    ) {
        for (String key : alphaTestPropertySettings) {
            String programName = key.substring("alphaTest.".length());
            PackProgramPlan programPlan = packPlan == null ? null : packPlan.program(programName);
            boolean consumed = programPlan != null
                    && programPlan.executable()
                    && programPlan.alphaTestPlan().valid()
                    && programPlan.alphaTestPlan().configured()
                    && (programName.equals("gbuffers_terrain")
                    || FamilyAdapterRegistry.isEntityLike(programName)
                    ? programPlan.convertedVertex() != null
                    : false);
            if (!consumed) {
                deviations.add("SETTING_NOT_APPLIED:" + key);
            }
        }
    }

    private static void applyShadowPropertyReporting(
            Map<String, Inventory> inventories,
            Set<String> shadowPropertySettings,
            PackConfig.ShadowSettings shadowSettings,
            List<String> deviations
    ) {
        Inventory shadow = inventories.get("shadow");
        boolean executablePair = shadow != null
                && shadow.stages.contains("vertex") && shadow.stages.contains("fragment");
        if (!executablePair) {
            for (String key : shadowPropertySettings) {
                deviations.add("SHADOW_SETTING_LOGGED_ONLY:" + key);
            }
            return;
        }
        for (String key : shadowSettings.rawValues().keySet()) {
            if (key.equals("shadowMapResolution") || key.equals("shadowDistance")
                    || key.equals("entityShadowDistanceMul")) {
                if (shadowSettings.deviations().contains("SHADOW_SETTING_DEFAULTED:" + key)) {
                    deviations.add("SHADOW_SETTING_DEFAULTED:" + key);
                } else {
                    deviations.add("SHADOW_SETTING_APPLIED:" + key);
                }
            } else {
                deviations.add("SHADOW_SETTING_UNSUPPORTED:" + key);
            }
        }
    }

    private static void readMetadata(
            Path file, String name, Map<String, String> hashes, List<String> deviations) {
        try {
            hashes.put(name, ConformanceReport.textSha256(Files.readAllBytes(file)));
        } catch (IOException e) {
            deviations.add("METADATA_READ_FAILED:" + name);
        }
    }

    private static boolean isStageFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && STAGE_EXTENSIONS.contains(name.substring(dot));
    }

    private static String stageName(String extension) {
        return switch (extension) {
            case ".vsh" -> "vertex";
            case ".fsh" -> "fragment";
            case ".gsh" -> "geometry";
            case ".tcs" -> "tess_control";
            case ".tes" -> "tess_evaluation";
            case ".csh" -> "compute";
            default -> throw new IllegalArgumentException("Unknown shader stage: " + extension);
        };
    }

    private static String familyOf(String name) {
        if (name.equals("gbuffers_water")) return "gbuffers_water";
        if (name.startsWith("gbuffers_")) return "gbuffers";
        if (name.startsWith("composite")) return "composite";
        if (name.equals("final")) return "final";
        if (name.startsWith("shadow")) return "shadow";
        if (name.startsWith("deferred")) return "deferred";
        if (name.startsWith("prepare")) return "prepare";
        if (name.startsWith("begin") || name.startsWith("end")) return "setup";
        return "other";
    }

    private static String relativePath(Path root, Path file) {
        return root.relativize(file.normalize()).toString().replace('\\', '/');
    }

    private static List<String> sorted(Collection<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    private static final class Inventory {
        private final String name;
        private final Set<String> stages = new TreeSet<>();
        private final Map<String, String> sourceHashes = new TreeMap<>();
        private final Map<String, String> sources = new HashMap<>();
        private final Map<String, String> preparedSources = new HashMap<>();
        private final Map<String, Path> sourcePaths = new HashMap<>();
        private final Set<String> deviations = new HashSet<>();
        private String variantFolder = "";

        private Inventory(String name) {
            this.name = name;
        }
    }
}
