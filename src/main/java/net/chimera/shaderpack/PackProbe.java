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

/** Read-only, deterministic inventory of the Chimera pack boundary. */
public final class PackProbe {
    private static final Set<String> STAGE_EXTENSIONS = Set.of(
            ".vsh", ".fsh", ".gsh", ".tcs", ".tes", ".csh");
    private static final Pattern VERSION = Pattern.compile(
            "(?m)^\\s*#version\\s+(\\d+)");
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
        for (PackProgram program : loaded.programs()) {
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
            for (PackProgram program : loaded.programs()) {
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
        boolean relaxed = !inventory.variantFolder.isBlank();
        StringBuilder combinedSource = new StringBuilder();
        Map<String, String> sourceView = relaxed && !inventory.preparedSources.isEmpty()
                ? inventory.preparedSources : inventory.sources;
        sourceView.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> combinedSource.append(entry.getValue()).append('\n'));
        String source = combinedSource.toString();
        String fragment = sourceView.getOrDefault("fragment", source);
        String vertex = sourceView.get("vertex");
        String name = inventory.name;
        PackAdvancedResourcePlan.ComputeSpec computeSpec = packPlan == null
                ? null : packPlan.advancedResources().computeStages().get(name);
        if (computeSpec != null && inventory.stages.contains("compute")) {
            return computeProgram(inventory, computeSpec, packPlan);
        }
        String stripped = stripComments(source);
        boolean executablePostName = PostTargetPlan.isPostProgramName(name);
        boolean modernTerrain = (name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || name.equals("shadow"))
                && programPlan != null
                && programPlan.terrainMaterial().modern();
        boolean modern = usesUnsupportedModernGlsl(stripped,
                executablePostName, FamilyAdapterRegistry.isEntityLike(name));
        // A validated conversion is authoritative for every family, not just
        // post/particles. The old lexical veto also rejected executable sky
        // and modern shadow shaders that already passed their converter guards.
        if (programPlan != null && programPlan.executable()
                && programPlan.convertedFragment() != null
                && (!inventory.stages.contains("vertex") || programPlan.convertedVertex() != null)) {
            modern = false;
        }
        if (modernTerrain) {
            modern = false;
        }
        List<String> samplers = UniformRegistry.scanDeclaredSamplerNames(stripped);
        // The report has historically exposed all uniform declarations,
        // including samplers. Keep that public inventory stable while the
        // shared interface plan continues to separate samplers for bindings.
        TreeSet<String> uniformNames = new TreeSet<>(samplers);
        uniformNames.addAll(UniformRegistry.scanUniformDeclarations(stripped).stream()
                .map(UniformRegistry.UniformDeclaration::name).toList());
        List<String> uniforms = List.copyOf(uniformNames);
        PostTargetPlan.ParseResult targetResult = null;
        if (programPlan != null && programPlan.targetPlan() != null) {
            targetResult = new PostTargetPlan.ParseResult(
                    programPlan.targetPlan(), programPlan.targetPlan().deviations());
        } else if (programPlan != null && programPlan.geometryOutputPlan() != null) {
            // The plan routes the prepared source, whose #if already chose one
            // DRAWBUFFERS directive; an authored scan would count every branch.
            targetResult = new PostTargetPlan.ParseResult(
                    programPlan.geometryOutputPlan().targetPlan(),
                    programPlan.geometryOutputPlan().deviations());
        } else if (executablePostName) {
            targetResult = PostTargetPlan.parse(inventory.name, fragment, packConfig.colortexFormats());
        }
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

        String family = familyOf(name);
        boolean hasFragment = inventory.stages.contains("fragment");
        UniformRegistry.Stage interfaceStage = FamilyAdapterRegistry.stageFor(name);
        UniformRegistry.ProgramInterface interfacePlan;
        if (programPlan != null) {
            interfacePlan = programPlan.interfacePlan().effective(interfaceStage);
            deviations.addAll(programPlan.deviations());
        } else {
            interfacePlan = executablePostName
                    ? (relaxed ? UniformRegistry.planPreparedPost(fragment, targetResult.plan())
                    : UniformRegistry.planPost(fragment, targetResult.plan()))
                    : (relaxed ? UniformRegistry.planPrepared(fragment, interfaceStage)
                    : UniformRegistry.plan(fragment, interfaceStage));
        }
        deviations.addAll(interfacePlan.deviations());
        if (targetResult != null) {
            deviations.addAll(targetResult.deviations());
            if (targetResult.executable()
                    && !targetResult.plan().targetSlots().equals(List.of(0))
                    && (executablePostName || targetResult.plan().requiresMrt())) {
                deviations.add(executablePostName
                        ? "POST_TARGET_ROUTE_APPLIED" : "GEOMETRY_TARGET_ROUTE_APPLIED");
            }
            if (targetResult.executable() && targetResult.plan().requiresMrt()
                    && (executablePostName || programPlan == null || programPlan.executable())) {
                deviations.add(executablePostName ? "MRT_POST_BRIDGE" : "MRT_GEOMETRY_BRIDGE");
            }
        }

        if (programPlan == null && executablePostName && hasFragment && !modern && targetResult != null
                && targetResult.executable() && interfacePlan.executable()) {
            Path sourcePath = relaxed && !inventory.preparedSources.isEmpty()
                    ? null : inventory.sourcePaths.get("fragment");
            String converted = LegacyGlslConverter.convertPostFragment(
                    fragment, sourcePath, interfacePlan, targetResult.plan(),
                    packConfig.shaderConstants());
            if (converted == null) {
                deviations.addAll(LegacyGlslConverter.postVaryingDeviations(fragment));
                deviations.add("POST_CONVERTER_UNSUPPORTED");
            }
        }

        if (executablePostName) {
            addPackConstantDeviations(fragment, packConfig, deviations);
        }

        boolean executableName = FamilyAdapterRegistry.isExecutableFamily(name)
                && (executablePostName || inventory.stages.contains("fragment"))
                && (!FamilyAdapterRegistry.isEntityLike(name)
                && !FamilyAdapterRegistry.isParticleLike(name)
                && !FamilyAdapterRegistry.isWeatherFamily(name)
                && !FamilyAdapterRegistry.isSkyFamily(name)
                && !FamilyAdapterRegistry.isCloudFamily(name)
                || inventory.stages.contains("vertex"));
        vertex = inventory.preparedSources.getOrDefault("vertex", inventory.sources.get("vertex"));
        if (programPlan != null) {
            if (inventory.stages.contains("vertex") && name.equals("gbuffers_terrain")) {
                deviations.add(programPlan.terrainMaterial().modern()
                        ? (programPlan.convertedVertex() != null
                        ? "MODERN_TERRAIN_VERTEX_BRIDGE" : "MODERN_TERRAIN_VERTEX_BRIDGE_UNSUPPORTED")
                        : (programPlan.convertedVertex() != null
                        ? "LEGACY_TERRAIN_VERTEX_BRIDGE" : "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED"));
            } else if (inventory.stages.contains("vertex") && name.equals("shadow")) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "SHADOW_VERTEX_BRIDGE" : "SHADOW_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_water")) {
                deviations.add(programPlan.terrainMaterial().modern()
                        ? (programPlan.convertedVertex() != null
                        ? "MODERN_WATER_VERTEX_BRIDGE" : "MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED")
                        : (programPlan.convertedVertex() != null
                        ? "TRANSLUCENT_VERTEX_BRIDGE" : "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED"));
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_entities")) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "ENTITY_VERTEX_BRIDGE" : "ENTITY_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_block")) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "BLOCK_VERTEX_BRIDGE" : "BLOCK_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_hand")) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "HAND_VERTEX_BRIDGE" : "HAND_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isParticleLike(name)) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "PARTICLE_VERTEX_BRIDGE" : "PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isWeatherFamily(name)) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "WEATHER_VERTEX_BRIDGE" : "WEATHER_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isSkyFamily(name)) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "SKY_VERTEX_BRIDGE" : "SKY_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isCloudFamily(name)
                    && programPlan.cloudDrawsNothing()) {
                deviations.add(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isCloudFamily(name)) {
                deviations.add(programPlan.convertedVertex() != null
                        ? "CLOUD_VERTEX_BRIDGE" : "CLOUD_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (name.equals("gbuffers_water")) {
                deviations.add("FIXED_VERTEX_SUBSTITUTION");
            } else if (name.equals("shadow")) {
                deviations.add("SHADOW_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex")) {
                if (programPlan.convertedVertex() != null && executablePostName) {
                    deviations.add(programPlan.deviations().contains("POST_VERTEX_AUTHORED_TRANSLATED")
                            ? "POST_VERTEX_AUTHORED_TRANSLATED" : "POST_VERTEX_ADAPTER");
                } else {
                    deviations.add("FIXED_VERTEX_SUBSTITUTION");
                }
            }
        } else {
            if (inventory.stages.contains("vertex") && name.equals("gbuffers_terrain")) {
                if (LegacyGlslConverter.supportsTerrainVertex(vertex, fragment)) {
                    deviations.add("LEGACY_TERRAIN_VERTEX_BRIDGE");
                } else {
                    deviations.add("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && name.equals("shadow")) {
                if (LegacyGlslConverter.supportsModernShadow(vertex, fragment)
                        || LegacyGlslConverter.supportsShadowVertex(vertex, fragment)) {
                    deviations.add("SHADOW_VERTEX_BRIDGE");
                } else {
                    deviations.add("SHADOW_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_water")) {
                if (LegacyGlslConverter.supportsModernTerrain(vertex, fragment)) {
                    deviations.add("MODERN_WATER_VERTEX_BRIDGE");
                } else if (LegacyGlslConverter.supportsTerrainVertex(vertex, fragment)) {
                    deviations.add("TRANSLUCENT_VERTEX_BRIDGE");
                } else {
                    deviations.add("MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_entities")) {
                if (LegacyGlslConverter.supportsEntityVertex(vertex, fragment)) {
                    deviations.add("ENTITY_VERTEX_BRIDGE");
                } else {
                    deviations.add("ENTITY_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_block")) {
                if (LegacyGlslConverter.supportsEntityVertex(vertex, fragment)) {
                    deviations.add("BLOCK_VERTEX_BRIDGE");
                } else {
                    deviations.add("BLOCK_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && name.equals("gbuffers_hand")) {
                if (LegacyGlslConverter.supportsHandVertex(vertex, fragment)) {
                    deviations.add("HAND_VERTEX_BRIDGE");
                } else {
                    deviations.add("HAND_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isParticleLike(name)) {
                if (LegacyGlslConverter.convertParticleVertex(vertex, null, fragment, Map.of()) != null) {
                    deviations.add("PARTICLE_VERTEX_BRIDGE");
                } else {
                    deviations.add("PARTICLE_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isWeatherFamily(name)) {
                if (LegacyGlslConverter.convertParticleVertex(vertex, null, fragment, Map.of()) != null) {
                    deviations.add("WEATHER_VERTEX_BRIDGE");
                } else {
                    deviations.add("WEATHER_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isSkyFamily(name)) {
                if (LegacyGlslConverter.convertSkyVertex(vertex, null, fragment,
                        name.equals("gbuffers_skytextured")) != null) {
                    deviations.add("SKY_VERTEX_BRIDGE");
                } else {
                    deviations.add("SKY_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isCloudFamily(name)
                    && AuthoredOutput.producesNothing(vertex, fragment)) {
                deviations.add(PackProgramPlan.CLOUD_AUTHORED_NO_OUTPUT);
            } else if (inventory.stages.contains("vertex") && FamilyAdapterRegistry.isCloudFamily(name)) {
                if (LegacyGlslConverter.convertCloudVertex(vertex, null, fragment) != null) {
                    deviations.add("CLOUD_VERTEX_BRIDGE");
                } else {
                    deviations.add("CLOUD_VERTEX_BRIDGE_UNSUPPORTED");
                }
            } else if (name.equals("gbuffers_water")) {
                deviations.add("FIXED_VERTEX_SUBSTITUTION");
            } else if (name.equals("shadow")) {
                deviations.add("SHADOW_VERTEX_BRIDGE_UNSUPPORTED");
            } else if (inventory.stages.contains("vertex")) {
                deviations.add("FIXED_VERTEX_SUBSTITUTION");
            }
        }
        if (name.equals("gbuffers_water")) {
            deviations.add("TRANSLUCENT_STATE_FIXED_TO_HOST");
        }
        if (name.equals("gbuffers_entities")
                && programPlan != null && programPlan.executable()) {
            deviations.add("ENTITY_VERTEX_FORMAT_EXTENDED");
            deviations.add("ENTITY_STATE_FIXED_TO_HOST");
            deviations.add("ENTITY_BATCH_ORIGIN_SPLIT");
            deviations.add("ENTITY_SCREEN_DRAW_FALLBACK");
        }
        if (name.equals("gbuffers_block") && programPlan != null && programPlan.executable()) {
            deviations.add("FAMILY_ADAPTER_INSTALLED");
            deviations.add("ENTITY_VERTEX_FORMAT_EXTENDED");
            deviations.add("BLOCK_ENTITY_ID_DEFAULTED");
            deviations.add("ENTITY_STATE_FIXED_TO_HOST");
        }
        if (name.equals("gbuffers_hand") && programPlan != null && programPlan.executable()) {
            deviations.add("FAMILY_ADAPTER_INSTALLED");
            deviations.add("ENTITY_VERTEX_FORMAT_EXTENDED");
            deviations.add("HAND_ITEM_ID_DEFAULTED");
            deviations.add("HAND_STATE_FIXED_TO_HOST");
        }
        if (name.equals("gbuffers_particles") && programPlan != null && programPlan.executable()) {
            deviations.add("FAMILY_ADAPTER_INSTALLED");
            deviations.add("PARTICLE_STATE_FIXED_TO_HOST");
        }
        if (FamilyAdapterRegistry.isWorldEntityFamily(name)
                && programPlan != null && programPlan.executable()) {
            if (!name.equals("gbuffers_entities")) {
                deviations.add("FAMILY_ADAPTER_INSTALLED");
                deviations.add("ENTITY_STATE_FIXED_TO_HOST");
            }
        }
        if (FamilyAdapterRegistry.isBlockFamily(name)
                && programPlan != null && programPlan.executable()) {
            if (name.equals("gbuffers_damagedblock")) {
                deviations.add("DAMAGED_BLOCK_HOST_FORMAT_INSTALLED");
            }
        }
        if (FamilyAdapterRegistry.isHandFamily(name)
                && programPlan != null && programPlan.executable()) {
            if (name.equals("gbuffers_hand_water")) {
                deviations.add("FAMILY_ADAPTER_INSTALLED");
                deviations.add("HAND_TRANSLUCENT_ITEM_PHASE");
            }
        }
        if (FamilyAdapterRegistry.isParticleLike(name)
                && programPlan != null && programPlan.executable()) {
            if (!name.equals("gbuffers_particles")) {
                deviations.add("FAMILY_ADAPTER_INSTALLED");
                deviations.add("PARTICLE_TRANSLUCENT_INSTALLED");
            }
        }
        if (FamilyAdapterRegistry.isWeatherFamily(name)
                && programPlan != null && programPlan.executable()) {
            deviations.add("FAMILY_ADAPTER_INSTALLED");
            deviations.add("WEATHER_STATE_FIXED_TO_HOST");
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

        Map<String, Integer> knownSamplers = name.equals("gbuffers_terrain")
                ? UniformRegistry.GEOMETRY_NAME_TO_SLOT
                : name.equals("shadow") ? UniformRegistry.SHADOW_NAME_TO_SLOT
                : name.equals("gbuffers_water") ? UniformRegistry.TRANSLUCENT_NAME_TO_SLOT
                : (FamilyAdapterRegistry.isEntityLike(name)
                || FamilyAdapterRegistry.isParticleLike(name)
                || FamilyAdapterRegistry.isWeatherFamily(name)
                || FamilyAdapterRegistry.isSkyFamily(name)
                || FamilyAdapterRegistry.isCloudFamily(name))
                ? UniformRegistry.ENTITY_NAME_TO_SLOT
                : UniformRegistry.NAME_TO_SLOT;
        // PackPlanBuilder already resolved standard aliases and pack-owned
        // sampler slots through the shared interface plan. Keep the legacy
        // scan only for compatibility callers that do not have a plan.
        if (!relaxed && programPlan == null) {
            for (String sampler : samplers) {
                if (!knownSamplers.containsKey(sampler)) {
                    deviations.add(name.equals("gbuffers_water")
                            ? (sampler.startsWith("depthtex") || sampler.startsWith("shadowcolor")
                            ? "TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"
                            : "TRANSLUCENT_SAMPLER_UNSUPPORTED:" + sampler)
                            : name.equals("shadow")
                            ? "SHADOW_SAMPLER_UNSUPPORTED:" + sampler
                            : FamilyAdapterRegistry.isEntityLike(name)
                            || FamilyAdapterRegistry.isParticleLike(name)
                            || FamilyAdapterRegistry.isWeatherFamily(name)
                            ? "ENTITY_SAMPLER_UNSUPPORTED:" + sampler
                            : "SAMPLER_NOT_MAPPED:" + sampler);
                }
            }
        }
        if (samplers.contains("noisetex")) {
            // Runtime performs the exact file check. The report records the
            // standard resource requirement without embedding a path.
            deviations.add("NOISETEX_PACK_RESOURCE");
        }
        boolean authoredShadowOutputs = name.equals("shadow") && programPlan != null
                && programPlan.convertedFragment() != null && programPlan.executable();
        if (!authoredShadowOutputs && targetResult == null && targets.size() > 1) {
            if (name.equals("shadow")) {
                deviations.add("SHADOW_COLOR_TARGET_UNSUPPORTED");
            } else {
                deviations.add("MRT_NOT_SUPPORTED");
            }
        } else if (!authoredShadowOutputs && targetResult == null && !targets.isEmpty() && targets.get(0) != 0) {
            deviations.add("TARGET_ROUTING_FIXED_TO_COLORTEX0");
        }
        if (!hasFragment) {
            deviations.add("MISSING_FRAGMENT_SOURCE");
        }

        ConformanceReport.SupportStatus support;
        if (!executableName) {
            support = ConformanceReport.SupportStatus.UNSUPPORTED;
        } else if (!hasFragment || hasSevereDeviation(deviations)) {
            support = ConformanceReport.SupportStatus.IDENTITY_FALLBACK;
        } else if (!deviations.isEmpty()) {
            support = ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION;
        } else {
            support = ConformanceReport.SupportStatus.SUPPORTED;
        }

        return new ConformanceReport.ProgramReport(
                name, family, modern ? "MODERN_GLSL" : "LEGACY_GLSL",
                sorted(inventory.stages), inventory.sourceHashes, samplers, uniforms,
                targets, support, ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                List.copyOf(deviations));
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
     * M8.6 advanced image declarations are not an old generic-resource
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

    private static boolean hasSevereDeviation(Collection<String> deviations) {
        for (String deviation : deviations) {
            if (deviation.equals("UNSUPPORTED_PACK_STAGE")
                    || deviation.equals("MODERN_GLSL_UNSUPPORTED")
                    || deviation.equals("ADVANCED_RESOURCE_UNSUPPORTED")
                    || deviation.equals("MRT_NOT_SUPPORTED")
                    || deviation.equals("TARGET_ROUTING_FIXED_TO_COLORTEX0")
                    || deviation.equals("POST_TARGET_DIRECTIVE_MALFORMED")
                    || deviation.equals("POST_TARGET_DIRECTIVE_CONFLICT")
                    || deviation.startsWith("POST_TARGET_DIRECTIVE_DUPLICATE:")
                    || deviation.startsWith("POST_TARGET_INDEX_UNSUPPORTED:")
                    || deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:")
                    || deviation.startsWith("POST_OUTPUT_INDEX_UNMAPPED:")
                    || deviation.equals("FINAL_MRT_UNSUPPORTED")
                    || deviation.equals("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("MODERN_TERRAIN_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("MODERN_WATER_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("SHADOW_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED")
                    || deviation.equals("ENTITY_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("BLOCK_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("HAND_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("PARTICLE_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("WEATHER_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("SKY_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("CLOUD_VERTEX_BRIDGE_UNSUPPORTED")
                    || deviation.equals("DAMAGED_BLOCK_HOST_FORMAT_UNSUPPORTED")
                    || deviation.startsWith("ENTITY_SAMPLER_UNSUPPORTED:")
                    || deviation.startsWith("ENTITY_ID_UNSUPPORTED:")
                    || deviation.startsWith("UNIFORM_TYPE_UNSUPPORTED:")
                    || deviation.startsWith("UNIFORM_NAME_UNSUPPORTED:")
                    || deviation.startsWith("UNIFORM_CONFLICT:")
                    || deviation.startsWith("SAMPLER_NOT_MAPPED:")
                    || deviation.startsWith("SAMPLER_SLOT_CONFLICT:")
                    || deviation.startsWith("PACK_TEXTURE_MISSING:")
                    || deviation.startsWith("PACK_RAW_TEXTURE_UNSUPPORTED:")
                    || deviation.startsWith("PACK_TEXTURE_PATH_UNSAFE:")
                    || deviation.startsWith("PACK_TEXTURE_LOAD_FAILED:")
                    || deviation.startsWith("PACK_RESOURCE_SLOT_LIMIT:")
                    || deviation.startsWith("STANDARD_RESOURCE_UNAVAILABLE:")
                    || deviation.startsWith("SHADOW_SAMPLER_UNSUPPORTED:")
                    || deviation.startsWith("TRANSLUCENT_SAMPLER_UNSUPPORTED:")
                    || deviation.startsWith("GBUFFER_TARGET_FEEDBACK:")
                    || deviation.equals("SHADOW_COLOR_INPUT_UNSUPPORTED")
                    || deviation.equals("MISSING_FRAGMENT_SOURCE")
                    || (deviation.startsWith("PROGRAM_")
                    && !deviation.startsWith("PROGRAM_ALIAS_RECORDED:"))
                    || deviation.startsWith("SOURCE_INCLUDE_")
                    || deviation.equals("POST_CONVERTER_UNSUPPORTED")
                    || deviation.startsWith("TRANSLATION_UNSUPPORTED:")
                    || deviation.startsWith("STORAGE_BUFFER_")
                    || deviation.startsWith("PROGRAM_INTERFACE_")
                    || deviation.startsWith("POST_VARYING_UNSUPPORTED:")
                    || deviation.startsWith("ALPHA_TEST_MALFORMED:")
                    || deviation.startsWith("LEGACY_FOG_FIELD_UNSUPPORTED:")
                    || deviation.startsWith("SOURCE_NORMALIZATION_FAILED:")
                    || isBlockingPreprocessorDeviation(deviation)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockingPreprocessorDeviation(String deviation) {
        return deviation.startsWith("PREPROCESSOR_")
                && !deviation.startsWith("PREPROCESSOR_MACRO_REDEFINED:");
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

    private static boolean usesUnsupportedModernGlsl(String source, boolean post, boolean entity) {
        Matcher version = VERSION.matcher(source);
        int number = 120;
        if (version.find()) {
            try {
                number = Integer.parseInt(version.group(1));
            } catch (NumberFormatException ignored) {
                return true;
            }
        }
        if (number >= 330 || number < 120) {
            return true;
        }
        if (entity && number != 120 && number != 130) {
            return true;
        }
        if (post) {
            return source.matches("(?s).*\\b(?:layout|buffer|shared|subroutine)\\b.*");
        }
        if (entity) {
            // The entity bridge accepts simple GLSL 130 stage declarations. The
            // converter still performs the authoritative feature check below.
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
                boolean customValue = key.matches("(?:uniform|variable)\\.(?:float|int|bool|vec2|vec3|vec4)\\.[A-Za-z_]\\w*");
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
            if (key.equals("shadowMapResolution") || key.equals("shadowDistance")) {
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

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ");
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
