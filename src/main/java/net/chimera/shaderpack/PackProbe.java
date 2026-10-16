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
    static final Pattern DRAWBUFFERS_DEFINE = Pattern.compile(
            "#define\\s+DRAWBUFFERS(\\d+)");
    static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?i)(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([0-9,\\s]+)");
    static final Pattern FRAG_DATA = Pattern.compile(
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
        return new Probe(packPath, loaded).analyze();
    }

    /** One probe of a loaded pack: the report inputs it reads and the plans it builds from them. */
    private static final class Probe {
        private final String packName;
        private final PackSource.LoadResult loaded;
        private final Path shadersDir;
        private final Map<String, String> metadataHashes = new TreeMap<>();
        private final List<String> globalDeviations = new ArrayList<>();
        private final List<String> settings = new ArrayList<>();
        private final List<String> passInventory = new ArrayList<>();
        private final Set<String> shadowPropertySettings = new TreeSet<>();
        private final Set<String> alphaTestPropertySettings = new TreeSet<>();
        private final Map<String, Inventory> inventories = new TreeMap<>();
        private boolean passListPresent;
        private PackSettingsPlan settingsPlan;
        private PackEntityIdResolver.ParseResult entityIds;
        private PackConfig.PackConfigData packConfig;
        private PackResolutionPlan resolution;
        private PackPlan initialPlan;
        private PackResourcePlan resourcePlan;
        private PackAdvancedResourcePlan advancedPlan;

        Probe(Path packPath, PackSource.LoadResult loaded) {
            this.packName = logicalPackName(packPath);
            this.loaded = loaded;
            this.shadersDir = loaded.shadersDir();
            this.settingsPlan = PackSettingsPlan.parse(loaded.rawProgramsAllVariants(), shadersDir,
                    PackOptionSources.overrides(packPath));
        }

        Analysis analyze() {
            loaded.prepare(
                    PackEngineDefines.forPack(settingsPlan.preprocessorDefines()),
                    PackEngineDefines.lockedNames(settingsPlan.overriddenNames(), false, false));
            settingsPlan = resolveLiveSamplerSlots(settingsPlan, loaded.programs(), shadersDir,
                    loaded.selectedVariantFolder());
            globalDeviations.addAll(loaded.deviations());
            readPackFiles();
            if (!Files.isDirectory(shadersDir)) {
                globalDeviations.add("NO_SHADERS_DIRECTORY");
                return new Analysis(conformanceReport(), new PackPlan(null, List.of()), null,
                        settingsPlan, PackResolutionPlan.empty());
            }
            inventorySources();
            readBlockProperties();
            readEntityIds();
            buildPlans();
            PackPlan packPlan = new PackPlan(packConfig, initialPlan.programs(), entityIds.resolver(),
                    settingsPlan, resolution, resourcePlan, advancedPlan);
            addPackDeviations(packPlan);
            ConformanceReport report = conformanceReport();
            inventoryAliases(packPlan);
            for (Inventory inventory : inventories.values()) {
                report.addProgram(ProgramReportRules.toProgram(inventory, packConfig,
                        packPlan.program(inventory.name), resourcePlan, packPlan));
            }
            return new Analysis(report, packPlan, packConfig, settingsPlan, resolution);
        }

        private ConformanceReport conformanceReport() {
            return report(packName, passListPresent, passInventory, metadataHashes, settings, globalDeviations,
                    loaded);
        }

        /** Hashes shaders.json and shaders.properties, and reads the passes and settings they declare. */
        private void readPackFiles() {
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
        }

        /** Inventories each program's sources, the stage files on disk, and every pass the pack names. */
        private void inventorySources() {
            addInventories(loaded.programs(), inventories, shadersDir);
            scanSelectedStageFiles(loaded, inventories);

            if (!passListPresent) {
                passInventory.addAll(inventories.keySet());
            }
            for (String pass : passInventory) {
                inventories.computeIfAbsent(pass, Inventory::new);
            }
        }

        private void readBlockProperties() {
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
        }

        /** Parses entity.properties, and records whether world entities draw with its ids or defaults. */
        private void readEntityIds() {
            Path entityProperties = shadersDir.resolve("entity.properties");
            entityIds = PackEntityIdResolver.parse(shadersDir);
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
        }

        private void buildPlans() {
            planPreparedSources();
            // Some real packs intentionally hide their writable-image producers
            // behind the Iris custom-image capability. The first plan tells us
            // whether the pack declares a supported image set and a compute stage;
            // only then do we negotiate that capability and rebuild every plan
            // from the same prepared source snapshot. Packs without that contract
            // retain their original preparation and fallback behavior.
            if (advancedPlan.hasSupportedImages()
                    && advancedPlan.computeStages().containsKey("shadowcomp")) {
                prepareWithCustomImages();
                planPreparedSources();
            }
            // The first pass admits only property-declared blocks so the full
            // source scan can build exact stage masks and bindings. Rebuild once
            // from that immutable result so probing and pipeline construction use
            // the same storage-buffer eligibility and rewritten GLSL.
            planPrograms(advancedPlan);
            advancedPlan = PackAdvancedResourcePlan.build(initialPlan, shadersDir);
            planPrograms(advancedPlan);
        }

        /** Parses the config and plans every resource from the current prepared sources and settings. */
        private void planPreparedSources() {
            packConfig = PackConfig.parse(loaded.programs(), shadersDir, settingsPlan);
            resolution = PackResolutionPlan.build(
                    loaded.selectedDimension(), loaded.selectedVariantFolder(),
                    loaded.programs(), settingsPlan);
            planPrograms(PackAdvancedResourcePlan.catalog(settingsPlan));
            advancedPlan = PackAdvancedResourcePlan.build(initialPlan, shadersDir);
        }

        /** Plans every program against these advanced resources, and the sampled resources it binds. */
        private void planPrograms(PackAdvancedResourcePlan advancedResources) {
            initialPlan = PackPlanBuilder.build(
                    loaded.programs(), packConfig, entityIds.resolver(), resolution, advancedResources);
            resourcePlan = PackResourcePlan.build(initialPlan, shadersDir);
        }

        /** Prepares the sources again with custom images negotiated, and inventories them afresh. */
        private void prepareWithCustomImages() {
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
        }

        /** The pack-wide deviations of the config and settings, and how the shadow and alpha settings apply. */
        private void addPackDeviations(PackPlan packPlan) {
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
        }

        private void inventoryAliases(PackPlan packPlan) {
            // Aliases are real requested-family plans, not synthetic host fallbacks.
            // Inventory the selected source too so probe and runtime share admission.
            for (PackProgramPlan planned : packPlan.programs()) {
                PackProgramResolution resolved = resolution.resolution(planned.name());
                if (resolved == null || !resolved.executable()
                        || resolved.requestedProgram().equals(resolved.selectedProgram())) continue;
                PackProgram program = planned.program();
                Inventory inventory = new Inventory(program.name());
                addProgramSources(inventory, program, shadersDir);
                inventories.put(program.name(), inventory);
            }
        }
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
            addProgramSources(inventories.computeIfAbsent(program.name(), Inventory::new), program, shadersDir);
        }
    }

    private static void addProgramSources(Inventory inventory, PackProgram program, Path shadersDir) {
        inventory.variantFolder = program.variantFolder();
        addSource(inventory, "fragment", program.fragmentPath(), program.fragmentSource(),
                program.preparedFragmentSource(), shadersDir);
        if (program.vertexSource() != null) {
            addSource(inventory, "vertex", program.vertexPath(), program.vertexSource(),
                    program.preparedVertexSource(), shadersDir);
        }
        inventory.deviations.addAll(program.preparationDeviations());
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

    static Integer targetIndex(String deviation) {
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
                    || FamilyAdapterRegistry.isCloudFamily(programName)
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

    static String familyOf(String name) {
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

    static List<String> sorted(Collection<String> values) {
        return List.copyOf(new TreeSet<>(values));
    }

    static final class Inventory {
        final String name;
        final Set<String> stages = new TreeSet<>();
        final Map<String, String> sourceHashes = new TreeMap<>();
        final Map<String, String> sources = new HashMap<>();
        final Map<String, String> preparedSources = new HashMap<>();
        final Map<String, Path> sourcePaths = new HashMap<>();
        final Set<String> deviations = new HashSet<>();
        String variantFolder = "";

        private Inventory(String name) {
            this.name = name;
        }
    }
}
