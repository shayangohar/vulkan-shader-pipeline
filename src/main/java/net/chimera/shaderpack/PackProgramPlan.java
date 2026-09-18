package net.chimera.shaderpack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable load-time plan for one complete pack program. Probe and runtime
 * pipeline construction consume the same instance, so eligibility cannot
 * drift between reporting and execution.
 */
public record PackProgramPlan(
        PackProgram program,
        Map<String, PreparedShaderSource> stages,
        UniformRegistry.ProgramInterfacePlan interfacePlan,
        Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces,
        Map<String, Integer> varyingLocations,
        PostTargetPlan targetPlan,
        String convertedFragment,
        String convertedVertex,
        LegacyGlslConverter.TerrainVaryingLayout vertexLayout,
        List<String> deviations,
        boolean executable,
        FamilyAdapterPlan familyAdapter,
        TerrainMaterialPlan terrainMaterial,
        GeometryOutputPlan geometryOutputPlan,
        PackAlphaTestPlan alphaTestPlan
) {
    public PackProgramPlan {
        stages = immutableStages(stages);
        stageInterfaces = stageInterfaces == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(stageInterfaces));
        varyingLocations = varyingLocations == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(varyingLocations));
        deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        familyAdapter = familyAdapter == null
                ? FamilyAdapterRegistry.forProgram(program == null ? "" : program.name())
                : familyAdapter;
        terrainMaterial = terrainMaterial == null ? TerrainMaterialPlan.legacy() : terrainMaterial;
        geometryOutputPlan = geometryOutputPlan == null ? null : geometryOutputPlan;
        alphaTestPlan = alphaTestPlan == null
                ? PackAlphaTestPlan.forProgram(program == null ? "" : program.name(), PackSettingsPlan.empty())
                : alphaTestPlan;
    }

    /** Compatibility constructor for callers using the pre-M6.1 plan shape. */
    public PackProgramPlan(
            PackProgram program,
            Map<String, PreparedShaderSource> stages,
            UniformRegistry.ProgramInterfacePlan interfacePlan,
            Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces,
            PostTargetPlan targetPlan,
            String convertedFragment,
            String convertedVertex,
            LegacyGlslConverter.TerrainVaryingLayout vertexLayout,
            List<String> deviations,
            boolean executable
    ) {
        this(program, stages, interfacePlan, stageInterfaces, Map.of(), targetPlan,
                convertedFragment, convertedVertex, vertexLayout, deviations, executable,
                FamilyAdapterRegistry.forProgram(program == null ? "" : program.name()),
                TerrainMaterialPlan.legacy(), null,
                PackAlphaTestPlan.forProgram(program == null ? "" : program.name(), PackSettingsPlan.empty()));
    }

    /** Compatibility constructor for pre-M7.6 callers. */
    public PackProgramPlan(
            PackProgram program,
            Map<String, PreparedShaderSource> stages,
            UniformRegistry.ProgramInterfacePlan interfacePlan,
            Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces,
            Map<String, Integer> varyingLocations,
            PostTargetPlan targetPlan,
            String convertedFragment,
            String convertedVertex,
            LegacyGlslConverter.TerrainVaryingLayout vertexLayout,
            List<String> deviations,
            boolean executable
    ) {
        this(program, stages, interfacePlan, stageInterfaces, varyingLocations, targetPlan,
                convertedFragment, convertedVertex, vertexLayout, deviations, executable,
                FamilyAdapterRegistry.forProgram(program == null ? "" : program.name()),
                TerrainMaterialPlan.legacy(), null,
                PackAlphaTestPlan.forProgram(program == null ? "" : program.name(), PackSettingsPlan.empty()));
    }

    public String name() {
        return program == null ? "" : program.name();
    }

    public String stageSource(String stage) {
        PreparedShaderSource prepared = stages.get(stage);
        return prepared == null ? null : prepared.source();
    }

    public boolean hasStage(String stage) {
        return stages.containsKey(stage);
    }

    public String executableFragmentSource() {
        return convertedFragment != null
                ? convertedFragment
                : stageSource("fragment");
    }

    public String executableVertexSource() {
        return convertedVertex != null
                ? convertedVertex
                : stageSource("vertex");
    }

    private static Map<String, PreparedShaderSource> immutableStages(
            Map<String, PreparedShaderSource> values
    ) {
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(values));
    }
}
