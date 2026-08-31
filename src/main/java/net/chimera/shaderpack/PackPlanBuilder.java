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
        List<PackProgramPlan> plans = new ArrayList<>();
        if (programs != null) {
            for (PackProgram program : programs) {
                plans.add(build(program, config));
            }
        }
        return new PackPlan(config, plans);
    }

    public static PackProgramPlan build(
            PackProgram program,
            PackConfig.PackConfigData config
    ) {
        if (program == null) {
            return new PackProgramPlan(null, Map.of(),
                    UniformRegistry.planProgram("", null, UniformRegistry.Stage.POST, null, true),
                    Map.of(), Map.of(), null, null, null, null,
                    List.of("PROGRAM_SOURCE_MISSING"), false);
        }

        Map<String, PreparedShaderSource> stages = preparedStages(program);
        String fragment = source(stages, "fragment", program.executableFragmentSource());
        String vertex = source(stages, "vertex", program.executableVertexSource());
        // Preserve the M5 report contract: root-level packs are inventoried
        // from their authored declarations, while nested dimension variants
        // use the active preprocessed snapshot.
        boolean prepared = !program.variantFolder().isBlank();
        UniformRegistry.Stage stage = stageFor(program.name());
        PostTargetPlan targetPlan = PostTargetPlan.isPostProgramName(program.name())
                ? PostTargetPlan.parse(program.name(), fragment,
                config == null ? Map.of() : config.colortexFormats()).plan()
                : null;
        UniformRegistry.ProgramInterfacePlan interfacePlan = UniformRegistry.planProgram(
                fragment, vertex, stage, targetPlan, prepared);
        Map<String, GlslInterfaceScanner.StageInterface> stageInterfaces = new TreeMap<>();
        if (fragment != null) {
            stageInterfaces.put("fragment", GlslInterfaceScanner.scan(fragment, false));
        }
        if (vertex != null) {
            stageInterfaces.put("vertex", GlslInterfaceScanner.scan(vertex, true));
        }
        boolean pairedPostVertex = stage == UniformRegistry.Stage.POST && vertex != null && prepared;
        boolean reconcileStages = vertex != null
                && (stage != UniformRegistry.Stage.POST || pairedPostVertex);
        GlslInterfaceScanner.ProgramMatch stageMatch = !reconcileStages
                ? new GlslInterfaceScanner.ProgramMatch(Map.of(), List.of())
                : GlslInterfaceScanner.match(stageInterfaces.get("vertex"), stageInterfaces.get("fragment"));
        Map<String, Integer> varyingLocations = stage == UniformRegistry.Stage.POST && !pairedPostVertex
                ? postVaryingLocations(stageInterfaces.get("fragment"))
                : stageMatch.locations();

        List<String> deviations = new ArrayList<>(program.preparationDeviations());
        stages.values().forEach(value -> deviations.addAll(value.deviations()));
        deviations.addAll(interfacePlan.deviations());
        stageInterfaces.values().forEach(value -> deviations.addAll(value.deviations()));
        deviations.addAll(stageMatch.deviations());
        if (targetPlan != null) {
            deviations.addAll(targetPlan.deviations());
        }

        String convertedFragment = null;
        String convertedVertex = null;
        LegacyGlslConverter.TerrainVaryingLayout vertexLayout = null;
        boolean executable = isExecutableFamily(program.name())
                && fragment != null
                && stages.values().stream().allMatch(PackPlanBuilder::preparedSuccessfully)
                && interfacePlan.executable()
                && stageInterfaces.values().stream().allMatch(value -> value.deviations().isEmpty())
                && stageMatch.executable()
                && (targetPlan == null || targetPlan.executable());

        try {
            if (isTerrainLike(program.name()) && vertex != null) {
                LegacyGlslConverter.TerrainVertexConversion conversion = switch (program.name()) {
                    case "shadow" -> LegacyGlslConverter.convertShadowVertex(
                            vertex, prepared ? null : program.vertexPath(), fragment);
                    default -> LegacyGlslConverter.convertTerrainVertex(
                            vertex, prepared ? null : program.vertexPath(), fragment);
                };
                if (conversion == null) {
                    deviations.add(vertexBridgeDeviation(program.name()));
                    executable = false;
                } else {
                    convertedVertex = conversion.source();
                    vertexLayout = conversion.layout();
                }
            }

            if (executable && PostTargetPlan.isPostProgramName(program.name())) {
                if (pairedPostVertex) {
                    LegacyGlslConverter.PostVertexConversion postVertex =
                            LegacyGlslConverter.convertPostVertex(
                                    vertex,
                                    stageInterfaces.get("vertex"),
                                    stageInterfaces.get("fragment"),
                                    stageMatch);
                    if (postVertex == null) {
                        deviations.addAll(LegacyGlslConverter.postVaryingDeviations(fragment));
                        deviations.add("POST_CONVERTER_UNSUPPORTED");
                        executable = false;
                    } else {
                        convertedVertex = postVertex.source();
                        deviations.addAll(postVertex.deviations());
                    }
                }
                if (executable) {
                    LegacyGlslConverter.PostVaryingLayout postLayout = !pairedPostVertex ? null
                            : new LegacyGlslConverter.PostVaryingLayout(
                            stageMatch.locations(), postVaryingTypes(stageInterfaces.get("fragment")));
                    convertedFragment = LegacyGlslConverter.convertPostFragment(
                            fragment,
                            prepared ? null : program.fragmentPath(),
                            interfacePlan.effective(UniformRegistry.Stage.POST),
                            targetPlan,
                            config == null ? Map.of() : config.shaderConstants(),
                            postLayout == null ? null : postLayout.locations(),
                            postLayout == null ? null : postLayout.types());
                }
                if (convertedFragment == null) {
                    deviations.addAll(LegacyGlslConverter.postVaryingDeviations(fragment));
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("gbuffers_terrain")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.convertFragment(
                        fragment, prepared ? null : program.fragmentPath(), true, slots,
                        vertexLayout, interfacePlan.effective(stage));
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("gbuffers_water")) {
                int[] slots = PackPipelines.interleaveLightmap(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.convertFragment(
                        fragment, prepared ? null : program.fragmentPath(), true, slots,
                        vertexLayout, interfacePlan.effective(stage));
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else if (executable && program.name().equals("shadow")) {
                int[] slots = PackPipelines.shadowSamplerSlots(interfaceSlots(interfacePlan, stage));
                convertedFragment = LegacyGlslConverter.convertFragment(
                        fragment, prepared ? null : program.fragmentPath(), true, slots,
                        vertexLayout, interfacePlan.effective(stage));
                if (convertedFragment == null) {
                    deviations.add("POST_CONVERTER_UNSUPPORTED");
                    executable = false;
                }
            } else {
                executable = false;
            }
        } catch (RuntimeException e) {
            deviations.add("TRANSLATION_UNSUPPORTED:" + safeReason(e));
            executable = false;
        }

        return new PackProgramPlan(program, stages, interfacePlan, stageInterfaces,
                varyingLocations, targetPlan, convertedFragment, convertedVertex,
                vertexLayout, deviations, executable);
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
                result.put(declaration.name(), declaration.type());
            }
        }
        return result;
    }

    private static boolean isTerrainLike(String name) {
        return name.equals("gbuffers_terrain") || name.equals("gbuffers_water")
                || name.equals("shadow");
    }

    private static String vertexBridgeDeviation(String name) {
        return switch (name) {
            case "shadow" -> "SHADOW_VERTEX_BRIDGE_UNSUPPORTED";
            case "gbuffers_water" -> "TRANSLUCENT_VERTEX_BRIDGE_UNSUPPORTED";
            default -> "TERRAIN_VERTEX_BRIDGE_UNSUPPORTED";
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

    private static UniformRegistry.Stage stageFor(String name) {
        if ("gbuffers_terrain".equals(name)) {
            return UniformRegistry.Stage.GEOMETRY;
        }
        if ("shadow".equals(name)) {
            return UniformRegistry.Stage.SHADOW;
        }
        if ("gbuffers_water".equals(name)) {
            return UniformRegistry.Stage.TRANSLUCENT;
        }
        return UniformRegistry.Stage.POST;
    }

    private static boolean isExecutableFamily(String name) {
        return "gbuffers_terrain".equals(name)
                || "gbuffers_water".equals(name)
                || "shadow".equals(name)
                || PostTargetPlan.isPostProgramName(name);
    }

    private static String safeReason(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return "source";
        }
        return message.replaceAll("[^A-Za-z0-9_.-]+", "_");
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
                (value.startsWith("SOURCE_") || value.startsWith("PREPROCESSOR_"))
                        && !value.startsWith("PREPROCESSOR_MACRO_REDEFINED:"));
    }
}
