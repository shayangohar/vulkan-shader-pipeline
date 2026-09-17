package net.chimera.shaderpack;

import java.util.Collections;
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
            PackEntityIdResolver entityIds
    ) {
        this(config, programs, entityIds, PackSettingsPlan.empty(),
                PackResolutionPlan.empty(), PackResourcePlan.empty(), null,
                PackAdvancedResourcePlan.empty());
    }

    /** Compatibility constructor for the pre-M8.1 resource-plan shape. */
    public PackPlan(
            PackConfig.PackConfigData config,
            List<PackProgramPlan> programs,
            PackEntityIdResolver entityIds,
            PackSettingsPlan settings,
            PackResolutionPlan resolution,
            PackResourcePlan resources
    ) {
        this(config, programs, entityIds, settings, resolution, resources, null,
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
                .sorted(java.util.Comparator.comparing(PackProgramPlan::name))
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
                && (!advancedResources.dependentPrograms().contains(name)
                || advancedResources.capabilityPossible())
                && (!advancedResources.bufferDependentPrograms().contains(name)
                || advancedResources.storageBufferProgramSupported(name))
                && (!advancedResources.customImageFeaturePrograms().contains(name)
                || advancedResources.capabilityPossible());
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

    /** Explicit option names whose values must survive source defaults. */
    public Set<String> settingsOverriddenNames() {
        return settings.overriddenNames();
    }

    public boolean isProgramDisabled(String name) {
        PackProgramResolution value = resolution.resolution(name);
        return value != null && !value.enabled();
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
