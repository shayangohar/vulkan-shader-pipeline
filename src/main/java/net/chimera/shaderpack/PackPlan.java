package net.chimera.shaderpack;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable plan for the active pack variant. */
public record PackPlan(
        PackConfig.PackConfigData config,
        List<PackProgramPlan> programs,
        PackEntityIdResolver entityIds,
        PackSettingsPlan settings,
        PackResolutionPlan resolution,
        PackResourcePlan resources,
        TerrainMaterialPlan terrainMaterial,
        PackAdvancedResourcePlan advancedResources
) {
    public PackPlan(PackConfig.PackConfigData config, List<PackProgramPlan> programs) {
        this(config, programs, PackEntityIdResolver.empty(),
                PackSettingsPlan.empty(), PackResolutionPlan.empty(), PackResourcePlan.empty(), null,
                PackAdvancedResourcePlan.empty());
    }

    public PackPlan(
            PackConfig.PackConfigData config,
            List<PackProgramPlan> programs,
            PackEntityIdResolver entityIds,
            PackSettingsPlan settings,
            PackResolutionPlan resolution,
            PackResourcePlan resources,
            PackAdvancedResourcePlan advancedResources
    ) {
        this(config, programs, entityIds, settings, resolution, resources, null, advancedResources);
    }

    public PackPlan {
        programs = programs == null ? List.of() : programs.stream()
                .filter(value -> value != null)
                .sorted(Comparator.comparing(PackProgramPlan::name))
                .toList();
        entityIds = entityIds == null ? PackEntityIdResolver.empty() : entityIds;
        settings = settings == null ? PackSettingsPlan.empty() : settings;
        resolution = resolution == null ? PackResolutionPlan.empty() : resolution;
        resources = resources == null ? PackResourcePlan.empty() : resources;
        terrainMaterial = terrainMaterial == null ? deriveTerrainMaterial(programs) : terrainMaterial;
        advancedResources = advancedResources == null
                ? PackAdvancedResourcePlan.empty() : advancedResources;
    }

    public Map<String, PackProgramPlan> byName() {
        Map<String, PackProgramPlan> result = new TreeMap<>();
        for (PackProgramPlan program : programs) {
            result.putIfAbsent(program.name(), program);
        }
        return Collections.unmodifiableMap(result);
    }

    public PackProgramPlan program(String name) {
        for (PackProgramPlan program : programs) {
            if (program.name().equals(name)) {
                return program;
            }
        }
        return null;
    }

    /** One eligibility result shared by probe and runtime pipeline construction. */
    public boolean shouldAttempt(String name) {
        PackProgramPlan program = program(name);
        return program != null && program.executable() && resolution.shouldAttempt(name)
                && resources.programAllowed(name)
                && !writesUnsupportedFormat(program)
                && !advancedResources.declaresUnplannedResource(name)
                && (!advancedResources.dependentPrograms().contains(name)
                || advancedResources.imageProgramsServable())
                && (!advancedResources.bufferDependentPrograms().contains(name)
                || advancedResources.storageBufferProgramSupported(name))
                && (!advancedResources.customImageFeaturePrograms().contains(name)
                || advancedResources.capabilityPossible());
    }

    /**
     * Whether the program writes a target whose declared format has no Vulkan image here, as the
     * report reads it (Noble's RGBA32UI colortex1). The target keeps the default format, which
     * the program's output does not fit.
     */
    private boolean writesUnsupportedFormat(PackProgramPlan program) {
        PostTargetPlan targets = program.targetPlan() != null ? program.targetPlan()
                : program.geometryOutputPlan() != null ? program.geometryOutputPlan().targetPlan() : null;
        if (targets == null || config == null) return false;
        return config.deviations().stream()
                .filter(deviation -> deviation.startsWith("POST_TARGET_FORMAT_UNSUPPORTED:"))
                .map(PackProbe::targetIndex)
                .anyMatch(target -> target != null && targets.targetSlots().contains(target));
    }

    public String selectedDimension() {
        return resolution.selectedDimension();
    }

    public String selectedSourceFolder() {
        return resolution.selectedSourceFolder();
    }

    public int aliasCount() {
        return resolution.aliases().size();
    }

    public int disabledProgramCount() {
        return resolution.disabledPrograms().size();
    }

    public int missingProgramCount() {
        return resolution.missingPrograms().size();
    }

    public String settingsFingerprint() {
        return settings.fingerprint();
    }

    public String resolutionFingerprint() {
        return resolution.fingerprint();
    }

    /** Immutable runtime smoothing and custom scalar settings for the pack session. */
    public PackRuntimeSettings runtimeSettings() {
        return settings.runtimeSettings();
    }

    /** Shared load-time macros used by runtime-only stages such as shadowcomp. */
    public Map<String, String> settingsPreprocessorDefines() {
        return settings.preprocessorDefines();
    }

    /** The {@code iris.features.required} flags Chimera cannot provide; Iris refuses such a pack. */
    public Set<String> unsupportedRequiredFeatures() {
        return settings.unsupportedRequiredFeatures();
    }

    /** Explicit option names whose values must survive source defaults. */
    public Set<String> settingsOverriddenNames() {
        return settings.overriddenNames();
    }

    public boolean isProgramDisabled(String name) {
        PackProgramResolution value = resolution.resolution(name);
        return value != null && !value.enabled();
    }

    /**
     * Iris's {@code clouds} directive: "off", "fast" or "fancy", or null when
     * the pack leaves the player's setting alone (Iris logs and ignores other values).
     */
    public String cloudSetting() {
        String value = settings.propertyValues().get("clouds");
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.equals("off") || trimmed.equals("fast") || trimmed.equals("fancy") ? trimmed : null;
    }

    /** Which parts of Minecraft's sky the pack keeps (Iris sun, moon, stars and sky, all default on). */
    public SkyDirectives skyDirectives() {
        return SkyDirectives.fromProperties(settings.propertyValues());
    }

    /** Which entities and block entities the pack lets cast shadows (Iris shadowEntities and friends). */
    public ShadowCasterDirectives shadowCasters() {
        return ShadowCasterDirectives.fromProperties(settings.propertyValues());
    }

    /** How terrain vertex colours carry block lighting (Iris oldLighting and separateAo, both default off). */
    public TerrainShadingDirectives terrainShading() {
        return TerrainShadingDirectives.fromProperties(settings.propertyValues());
    }

    /** Whether heldBlockLightValue takes the brighter hand (Iris oldHandLight, default on). */
    public HandLightDirectives handLight() {
        return HandLightDirectives.fromProperties(settings.propertyValues());
    }

    /** Which of Minecraft's screen overlays the pack keeps (Iris vignette and underwaterOverlay, default off). */
    public ScreenOverlayDirectives screenOverlays() {
        return ScreenOverlayDirectives.fromProperties(settings.propertyValues());
    }

    /** Where particles draw against the deferred passes (Iris particles.ordering and its older keys). */
    public ParticleDirectives particleDirectives() {
        return settings.particleDirectives();
    }

    /** How the shadow pass culls its casters (Iris shadow.culling and the shadow distances). */
    public ShadowCullingPlan shadowCulling() {
        PackConfig.ShadowSettings shadow = config != null ? config.shadowSettings()
                : new PackConfig.ShadowSettings(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION,
                        PackConfig.DEFAULT_SHADOW_DISTANCE, Map.of(), List.of());
        return ShadowCullingPlan.of(settings.propertyValues(), shadow);
    }

    /**
     * The programs that take part in the frame. Iris removes a program the pack switches off
     * ({@code program.<name>.enabled}) from the pipeline entirely, so it neither runs nor counts
     * as a producer; the schedule and target graph are built from these, never from a gap.
     */
    public List<PackProgramPlan> activePrograms() {
        return programs().stream().filter(program -> !isProgramDisabled(program.name())).toList();
    }

    public boolean isProgramAlias(String name) {
        PackProgramResolution value = resolution.resolution(name);
        return value != null && !value.requestedProgram().equals(value.selectedProgram());
    }

    public PackResourcePlan resources() {
        return resources;
    }

    public PackAdvancedResourcePlan advancedResources() {
        return advancedResources;
    }

    /** The one terrain layout selected for every chunk buffer in this session. */
    public TerrainMaterialPlan terrainMaterial() {
        return terrainMaterial;
    }

    private static TerrainMaterialPlan deriveTerrainMaterial(List<PackProgramPlan> values) {
        TerrainMaterialPlan result = TerrainMaterialPlan.legacy();
        if (values != null) {
            for (PackProgramPlan value : values) {
                if (value != null) {
                    result = result.merge(value.terrainMaterial());
                }
            }
        }
        return result;
    }
}
