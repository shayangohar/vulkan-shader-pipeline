package net.chimera.shaderpack;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Immutable load-time plan for one complete pack program. Probe and runtime
 * pipeline construction consume the same instance, so eligibility cannot
 * drift between reporting and execution. {@code shadowAttachmentFormats} is the shadow pass's
 * colour attachment contract, shadowcolor0 onward, that a shadow-stage pipeline declares; it is
 * empty for every other stage. {@code convertedGeometry} and the two converted tessellation stages
 * run between the program's vertex and fragment stages; each is null when it has none.
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
        PackAlphaTestPlan alphaTestPlan,
        PackBlendPlan blendPlan,
        List<Integer> shadowAttachmentFormats,
        String convertedGeometry,
        String convertedTessControl,
        String convertedTessEvaluation
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
        alphaTestPlan = alphaTestPlan == null
                ? PackAlphaTestPlan.forProgram(program == null ? "" : program.name(), PackSettingsPlan.empty())
                : alphaTestPlan;
        blendPlan = blendPlan == null ? PackBlendPlan.empty() : blendPlan;
        shadowAttachmentFormats = shadowAttachmentFormats == null ? List.of() : List.copyOf(shadowAttachmentFormats);
    }

    /** A plan with the default family adapter, terrain material, alpha test and blend for its program. */
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
                convertedFragment, convertedVertex, vertexLayout, deviations, executable, null, null, null, null, null,
                null, null, null, null);
    }

    /** The converted stages the program runs between its vertex and fragment stages. */
    public BetweenStages betweenStages() {
        return new BetweenStages(convertedTessControl, convertedTessEvaluation, convertedGeometry);
    }

    /** The authored cloud program provably draws nothing (see AuthoredOutput). */
    public static final String CLOUD_AUTHORED_NO_OUTPUT = "CLOUD_AUTHORED_NO_OUTPUT";

    public String name() {
        return program == null ? "" : program.name();
    }

    /** True when executing the authored cloud program would draw nothing. */
    public boolean cloudDrawsNothing() {
        return deviations.contains(CLOUD_AUTHORED_NO_OUTPUT);
    }

    public String stageSource(String stage) {
        PreparedShaderSource prepared = stages.get(stage);
        return prepared == null ? null : prepared.source();
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
