package net.chimera.shaderpack;

import net.chimera.render.ChimeraTextureBindingStateHarness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic M8.6a advanced image and shadow-compute plan checks. */
public final class M86ConformanceHarness {
    private M86ConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supportedPath = root.resolve("m8_6/advanced_images");
        Path unsupportedPath = root.resolve("m8_6/unsupported_advanced");
        assertTrue(Files.isDirectory(supportedPath), "M8.6 supported fixture is missing");
        assertTrue(Files.isRegularFile(root.resolve("baselines/m8_6.json")),
                "M8.6 baseline is missing");
        verifyPackFinalHandSchedule();
        verifyEngineDefines();
        verifyLegacyLavaFog();
        ChimeraTextureBindingStateHarness.verify();

        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        PackAdvancedResourcePlan supportedPlan = supported.plan().advancedResources();
        ConformanceReport.ProgramReport computeReport = supported.report().program("shadowcomp");
        assertTrue(computeReport != null && computeReport.stages().equals(List.of("compute")),
                "M8.6 compute report is not first-class");
        assertTrue(!computeReport.deviations().contains("MISSING_FRAGMENT_SOURCE")
                        && !computeReport.deviations().contains("MODERN_GLSL_UNSUPPORTED"),
                "M8.6 compute report contains graphics-only rejection reasons");
        assertTrue(computeReport.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                "M8.6 supported compute report is not supported");
        assertEquals(supportedPlan.snapshot(), supportedAgain.plan().advancedResources().snapshot(),
                "M8.6 supported plan is not deterministic");
        assertTrue(supportedPlan.images().size() == 2, "M8.6 image declaration count");
        assertTrue(supportedPlan.hasSupportedImages(), "M8.6 supported images rejected");
        assertTrue(supportedPlan.hasSupportedCompute(), "M8.6 shadow compute rejected");
        assertTrue(supportedPlan.capabilityPossible(), "M8.6 capability was not planned");
        assertTrue(net.chimera.render.PackAdvancedImageOwner.shouldInitializeImage(false, false),
                "M8.6 persistent images must be seeded on first use");
        assertTrue(!net.chimera.render.PackAdvancedImageOwner.shouldInitializeImage(false, true),
                "M8.6 persistent images must not be cleared every frame");
        assertTrue(net.chimera.render.PackAdvancedImageOwner.shouldInitializeImage(true, true),
                "M8.6 per-frame clear policy was lost");
        assertEquals(2, supportedPlan.computeStages().get("shadowcomp").localSizeX(),
                "M8.6 workgroup X");
        assertTrue(supportedPlan.graphicsImages().containsKey("shadow"),
                "M8.6 graphics image producer was not discovered");
        assertTrue(supportedPlan.graphicsImages("shadow").stream()
                        .anyMatch(binding -> binding.imageName().equals("voxel_img")
                                && binding.symbol().equals("voxel_sampler")),
                "M8.6 shadow imageStore binding is missing");
        String boundedFetch = GlslTokenRewriter.rewriteTexelFetchBounds(
                "vec4 value = texelFetch(lighttex, pos + ivec3(1, 0, 0), 0);",
                Set.of("lighttex"));
        assertTrue(boundedFetch.contains("clamp(pos + ivec3(1, 0, 0), ivec3(0)"),
                "M8.6 3D texelFetch was not bounded");
        String boundedHelperFetch = GlslTokenRewriter.rewriteTexelFetchBounds(
                "vec4 sample(sampler3D lighttex, ivec3 pos) {"
                        + " return texelFetch(lighttex, pos, 0); }",
                Set.of("lighttex"));
        assertTrue(boundedHelperFetch.contains("textureSize(lighttex, 0) - ivec3(1)"),
                "M8.6 helper-function 3D texelFetch was not bounded");
        UniformRegistry.ProgramInterface shadowedSampler = UniformRegistry.planPrepared(
                "uniform sampler2D specular;"
                        + "float readValue() { float specular = 1.0; return specular; }",
                UniformRegistry.Stage.GEOMETRY);
        assertTrue(shadowedSampler.deviations().stream().noneMatch(value ->
                        value.equals("SAMPLER_NOT_MAPPED:specular")),
                "M8.6 local sampler shadowing was treated as a resource use");
        UniformRegistry.ProgramInterface helperSampler = UniformRegistry.planPrepared(
                "uniform sampler2D customSampler;"
                        + "vec4 sampleIt(sampler2D value, vec2 uv) { return texture2D(value, uv); }"
                        + "vec4 readValue(vec2 uv) { return sampleIt(customSampler, uv); }",
                UniformRegistry.Stage.GEOMETRY);
        assertTrue(helperSampler.deviations().contains("SAMPLER_NOT_MAPPED:customSampler"),
                "M8.6 helper sampler dependency was not detected");

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        PackAdvancedResourcePlan unsupportedPlan = unsupported.plan().advancedResources();
        assertTrue(!unsupportedPlan.capabilityPossible(), "M8.6 unsupported capability enabled");
        assertTrue(unsupportedPlan.deviations().stream().anyMatch(value ->
                        value.startsWith("ADVANCED_IMAGE_UNSUPPORTED:too_large")),
                "M8.6 oversized image deviation missing");
        assertTrue(unsupportedPlan.deviations().contains("STORAGE_BUFFER_DEFERRED:bufferObject.0"),
                "M8.6 storage-buffer deferral missing");

        Path budgetPath = root.resolve("m8_6/budget_isolation");
        assertTrue(Files.isDirectory(budgetPath), "M8.6 budget fixture is missing");
        PackProbe.Analysis budget = PackProbe.analyze(budgetPath);
        PackAdvancedResourcePlan budgetPlan = budget.plan().advancedResources();
        PackAdvancedResourcePlan.ImageSpec small = budgetPlan.images().get("small_image");
        PackAdvancedResourcePlan.ImageSpec large = budgetPlan.images().get("large_image");
        assertTrue(small != null && small.supported(),
                "M8.6 budget fixture rejected the valid image");
        assertTrue(large != null && !large.supported(),
                "M8.6 budget fixture admitted the oversized image");
        assertTrue(budgetPlan.hasSupportedCompute() && budgetPlan.capabilityPossible(),
                "M8.6 budget fixture disabled compute because of an unrelated image");
        assertTrue(budgetPlan.deviations().contains("ADVANCED_IMAGE_BUDGET_EXCEEDED:large_image"),
                "M8.6 per-resource budget deviation missing");
        assertEquals(budgetPlan.snapshot(),
                PackProbe.analyze(budgetPath).plan().advancedResources().snapshot(),
                "M8.6 budget plan is not deterministic");
        assertNoAbsolutePaths(supportedPlan.snapshot());
        assertNoAbsolutePaths(budgetPlan.snapshot());

        Path overridePath = root.resolve("m8_6/option_override");
        assertTrue(Files.isDirectory(overridePath), "M8.6 option override fixture is missing");
        PackAdvancedResourcePlan defaultOverridePlan = PackProbe.analyze(overridePath)
                .plan().advancedResources();
        assertTrue(!defaultOverridePlan.capabilityPossible(),
                "M8.6 option fixture enabled advanced images without an override");
        String previousOverride = System.getProperty("chimera.option.ENABLE_ADVANCED");
        System.setProperty("chimera.option.ENABLE_ADVANCED", "1");
        PackProbe.Analysis overridden;
        try {
            overridden = PackProbe.analyze(overridePath);
        } finally {
            if (previousOverride == null) {
                System.clearProperty("chimera.option.ENABLE_ADVANCED");
            } else {
                System.setProperty("chimera.option.ENABLE_ADVANCED", previousOverride);
            }
        }
        PackAdvancedResourcePlan overridePlan = overridden.plan().advancedResources();
        assertTrue(overridePlan.images().get("test_image") != null
                        && overridePlan.images().get("test_image").supported(),
                "M8.6 explicit option did not activate the image declaration");
        assertTrue(overridePlan.capabilityPossible(),
                "M8.6 explicit option did not activate shadow compute");
        assertTrue(overridden.settings().deviations().contains("PACK_OPTION_OVERRIDE:ENABLE_ADVANCED"),
                "M8.6 explicit option deviation missing");

        verifyOptionalRealPack(
                System.getProperty("chimera.m86.complementary"),
                Map.of("COLORED_LIGHTING", "128", "WORLD_SPACE_REFLECTIONS", "1"),
                "Complementary");
        verifyOptionalRealPack(
                System.getProperty("chimera.m86.bsl"),
                Map.of("MULTICOLORED_BLOCKLIGHT", "1", "MCBL_DISTANCE", "128",
                        "MCBL_HALF_HEIGHT", "1"),
                "BSL");

        String baseline = Files.readString(root.resolve("baselines/m8_6.json"));
        assertTrue(baseline.contains(supportedPlan.fingerprint()),
                "M8.6 supported fingerprint baseline mismatch");
        assertTrue(baseline.contains(unsupportedPlan.fingerprint()),
                "M8.6 unsupported fingerprint baseline mismatch");
        assertTrue(baseline.contains(budgetPlan.fingerprint()),
                "M8.6 budget-isolation fingerprint baseline mismatch");
        System.out.println("[chimera] M8.6a advanced image and compute conformance: PASS");
        System.out.println("[chimera] M8.6a supportedFingerprint=" + supportedPlan.fingerprint());
        System.out.println("[chimera] M8.6a unsupportedFingerprint=" + unsupportedPlan.fingerprint());
        System.out.println("[chimera] M8.6a budgetIsolationFingerprint=" + budgetPlan.fingerprint());
    }

    private static void verifyPackFinalHandSchedule() throws Exception {
        Path mainSource = sourcePath("src/main/java/net/chimera/render/ChimeraMainPass.java");
        Path handSource = sourcePath("src/main/java/net/chimera/mixin/ChimeraItemInHandRendererMixin.java");
        Path depthSource = sourcePath("src/main/java/net/chimera/mixin/GameRendererDepthMixin.java");
        String main = Files.readString(mainSource);
        String hand = Files.readString(handSource);
        String depth = Files.readString(depthSource);

        assertTrue(!main.contains("finishPackFinalAfterHand"),
                "M8.6 final still has a late hand-return hook");
        assertTrue(!main.contains("seedFinalPackInput")
                        && !main.contains("nextFinalInputTarget")
                        && !main.contains("commitFinalInput")
                        && !main.contains("finalSeedPipeline"),
                "M8.6 obsolete host-composition seed path remains");
        assertTrue(!hand.contains("finishPackFinalAfterHand"),
                "M8.6 hand mixin still owns pack final");
        assertTrue(!depth.contains("finishPackFinalAfterHand"),
                "M8.6 depth mixin still owns pack final");
        assertTrue(main.contains("HAND_SCHEDULE_FALLBACK"),
                "M8.6 hand fallback is not explicit");
        assertTrue(!main.contains("this.packHandPipeline = hand;")
                        && !main.contains("this.packHandWaterPipeline = hand;"),
                "M8.6 late hand adapter is still installed");

        int resolve = main.indexOf("resolvePackWorldToOutput(commandBuffer, hdrColor);");
        int finalCall = main.indexOf("finishPackFinalBeforeHand();", resolve);
        int returnIndex = main.indexOf("return;", finalCall);
        assertTrue(resolve >= 0 && finalCall > resolve && returnIndex > finalCall,
                "M8.6 pack final is not ordered after world resolve and before hand");
        assertEquals(1, count(main, "finishPackFinalBeforeHand();"),
                "M8.6 pack final must have one authoritative call site");

        System.out.println("[chimera] M8.6a pack-final-before-hand schedule: PASS");
    }

    private static void verifyLegacyLavaFog() {
        String source = "#version 120\nuniform vec3 fogColor;\n"
                + "void DoLavaFog(inout vec4 color, float lViewPos) {\n"
                + " float fog = (lViewPos * 3.0 - gl_Fog.start) * gl_Fog.scale;\n"
                + " fog = clamp(1.0 - exp(-fog), 0.0, 1.0);\n"
                + " color = mix(color, vec4(fogColor * 5.0, 0.0), fog);\n}\n"
                + "void main() { vec4 color = vec4(1.0);"
                + " DoLavaFog(color, 4.0); gl_FragColor = color; }\n";
        LegacyShaderNormalizer.Result normalized = LegacyShaderNormalizer.normalize(source);
        assertTrue(normalized.successful(), "DoLavaFog normalization failed");
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(
                normalized.source(), PostTargetPlan.parse("composite", source).plan());
        assertTrue(plan.executable()
                        && plan.uniforms().contains(new UniformRegistry.UniformDeclaration("fogStart", "float"))
                        && plan.uniforms().contains(new UniformRegistry.UniformDeclaration("fogEnd", "float")),
                "DoLavaFog must retain both live environmental fog uniforms");

        // Division exposes a missing outer pair of parentheses; multiplication alone does not.
        LegacyShaderNormalizer.Result division = LegacyShaderNormalizer.normalize(
                "float probe = 6.0 / gl_Fog.scale;");
        assertTrue(division.successful(), "Fog scale division normalization failed");
        String expression = division.source().substring(division.source().indexOf("=") + 1,
                division.source().lastIndexOf(';')).trim();
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "float", "fogScaleProbe", expression)),
                Map.of(), List.of());
        assertTrue(settings.indexOf("fogScaleProbe") >= 0,
                "Normalized fog scale expression is not evaluable");
        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> switch (name) {
            case "fogStart" -> 2.0;
            case "fogEnd" -> 8.0;
            default -> throw new AssertionError("Unexpected fog dependency: " + name);
        }, session, 0.05f);
        assertEquals(36.0f, session.values()[settings.indexOf("fogScaleProbe")],
                "gl_Fog.scale reciprocal lost expression precedence");
    }

    private static void verifyEngineDefines() {
        String predicate = "#version 430 compatibility\n"
                + "#if COLORED_LIGHTING > 0 && (!defined IS_IRIS || !defined IRIS_FEATURE_CUSTOM_IMAGES)\n"
                + "Text beginTextM printChar\n"
                + "#endif\n"
                + "void main() {}\n";
        ShaderSourcePreprocessor.Result enabled = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, predicate,
                PackEngineDefines.forCustomImages(Map.of("COLORED_LIGHTING", "128")),
                PackEngineDefines.lockedNames(Set.of("COLORED_LIGHTING"), true, false));
        assertTrue(enabled.successful(), "M8.6 engine define preparation failed");
        assertTrue(!enabled.source().contains("Text")
                        && !enabled.source().contains("beginTextM")
                        && !enabled.source().contains("printChar"),
                "M8.6 IS_IRIS predicate did not remove the compatibility error branch");

        ShaderSourcePreprocessor.Result noEngine = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, predicate,
                Map.of("COLORED_LIGHTING", "128"), Set.of());
        assertTrue(noEngine.successful() && noEngine.source().contains("beginTextM"),
                "M8.6 negative engine-define predicate case is invalid");
    }

    private static Path sourcePath(String relative) {
        Path direct = Path.of(relative);
        if (Files.isRegularFile(direct)) return direct;
        return Path.of("vulkan-shader-pipeline").resolve(relative);
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static void assertNoAbsolutePaths(String value) {
        assertTrue(!value.contains("C:\\") && !value.contains("/Users/"),
                "M8.6 snapshot contains an absolute path");
    }

    private static void verifyOptionalRealPack(
            String rawPath,
            Map<String, String> overrides,
            String label
    ) throws Exception {
        if (rawPath == null || rawPath.isBlank()) return;
        Path path = Path.of(rawPath);
        assertTrue(Files.isDirectory(path) || Files.isRegularFile(path),
                "M8.6 " + label + " path is missing");
        Map<String, String> previous = new java.util.TreeMap<>();
        for (String name : overrides.keySet()) {
            String property = "chimera.option." + name;
            previous.put(name, System.getProperty(property));
            System.setProperty(property, overrides.get(name));
        }
        try {
            PackProbe.Analysis analysis = PackProbe.analyze(path);
            PackPlan packPlan = analysis.plan();
            PackAdvancedResourcePlan plan = packPlan.advancedResources();
            verifyAdvancedPostDescriptorPartition(packPlan, plan, label);
            verifyPostDescriptorStageOwnership(packPlan, plan, label);
            verifyDepthDependentPostSchedule(analysis, label);
            System.out.println("[chimera] M8.6 " + label + " imagePlan=" + plan.images().keySet()
                    + " compute=" + plan.computeStages().values().stream()
                    .map(value -> value.program() + ":" + value.status() + ":" + value.deviations())
                    .toList()
                    + " dependent=" + plan.dependentPrograms()
                    + " graphics=" + plan.graphicsImages()
                    + " deviations=" + plan.deviations());
            if (!plan.capabilityPossible()) {
                boolean explicitGate = plan.deviations().stream().anyMatch(value ->
                        value.startsWith("IRIS_FEATURE_CUSTOM_IMAGES_DISABLED")
                                || value.startsWith("COMPUTE_")
                                || value.startsWith("ADVANCED_IMAGE_"));
                assertTrue(explicitGate,
                        "M8.6 " + label + " advanced path was rejected without a named reason");
                throw new AssertionError("M8.6 " + label
                        + " supplied real pack did not reach the required advanced path: "
                        + plan.deviations());
            }
            assertTrue(plan.capabilityPossible(),
                    "M8.6 " + label + " advanced path is not eligible under explicit settings");
            assertTrue(plan.computeStages().get("shadowcomp") != null
                            && plan.computeStages().get("shadowcomp").supported(),
                    "M8.6 " + label + " shadowcomp is not supported under explicit settings");
            PackProgramPlan shadow = packPlan.program("shadow");
            assertTrue(shadow != null && shadow.executable() && packPlan.shouldAttempt("shadow"),
                    "M8.6 " + label + " shadow producer is not executable with advanced resources");
            if (label.equals("BSL") || label.equals("Complementary")) {
                String vertex = shadow.executableVertexSource();
                // M8.7 keeps camera gbuffer uniforms distinct from the legacy
                // shadow-stage built-ins. Do not require a camera matrix to
                // survive conversion when only the shadow draw pair is used.
                assertTrue(vertex.contains("ChimeraShadowUniforms")
                                && vertex.contains("shadowModelView")
                                && vertex.contains("shadowProjection"),
                        "M8.6 " + label + " shadow adapter lost its canonical matrix UBO");
                assertTrue(vertex.contains("chimeraShadowUniforms.shadowModelView")
                                && vertex.contains("chimeraShadowUniforms.shadowProjection"),
                        "M8.6 " + label + " shadow adapter did not qualify its draw matrix UBO access");
                assertTrue(!vertex.contains("gl_VertexID"),
                        "M8.6 " + label + " shadow adapter left Vulkan-incompatible gl_VertexID");
            }
            assertTrue(!plan.computeStages().get("shadowcomp").samplerNames().isEmpty(),
                    "M8.6 " + label + " 3D sampler dependencies were not planned");
            if (plan.graphicsImages().containsKey("shadow")) {
                assertTrue(plan.graphicsImageNames(java.util.Set.of("shadow"))
                                .containsAll(plan.requiredGraphicsImageNames()),
                        "M8.6 " + label
                                + " optional graphics producers incorrectly block image coverage");
            }
            assertTrue(plan.deviations().stream().noneMatch(value ->
                            value.startsWith("PACK_RESOURCE_SLOT_LIMIT:")),
                    "M8.6 " + label + " live advanced sampler was starved by a slot limit: "
                            + plan.deviations());
            if (label.equals("Complementary")) {
                PackAdvancedResourcePlan.ImageSpec wsr = plan.images().get("wsr_img");
                assertTrue(wsr != null && wsr.supported()
                                && wsr.width() == 128 && wsr.height() == 64 && wsr.depth() == 128
                                && wsr.widthToken().equals("COLORED_LIGHTING")
                                && wsr.heightToken().equals("64")
                                && wsr.depthToken().equals("COLORED_LIGHTING"),
                        "M8.6 Complementary WSR dimensions were not resolved from the active option");
                PackAdvancedResourcePlan.BufferSpec wsrBuffer = plan.buffers().get(0);
                assertTrue(wsrBuffer != null && wsrBuffer.supported()
                                && wsrBuffer.size() == 50_855_936L,
                        "M8.6 Complementary WSR bufferObject.0 was not admitted at its authored size");
                Map<String, Integer> slots = analysis.settings().customSamplerSlots();
                assertTrue(slots.containsKey("voxel_sampler")
                                && slots.containsKey("wsr_sampler")
                                && slots.containsKey("wsr_lod_sampler"),
                        "M8.6 Complementary advanced sampler slots are incomplete: " + slots);
                assertTrue(plan.graphicsImages("shadow").stream().anyMatch(value ->
                                value.imageName().equals("wsr_img")),
                        "M8.6 Complementary WSR image producer was not admitted");
                Set<String> shadowReaders = plan.graphicsImageReaders("shadow").stream()
                        .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                        .collect(java.util.stream.Collectors.toSet());
                assertTrue(shadowReaders.containsAll(Set.of(
                                "voxel_sampler", "floodfill_sampler", "floodfill_sampler_copy")),
                        "M8.6 Complementary shadow helper samplers were not declared: "
                                + shadowReaders);
                List<PackAdvancedResourcePlan.StorageBufferBinding> writer =
                        plan.storageBuffers("shadow");
                assertTrue(writer.stream().anyMatch(value ->
                                value.blockName().equals("blockDataBuffer")
                                        && value.logicalIndex() == 0
                                        && value.originalBinding() == 0
                                        && (value.stageMask() & 1) != 0),
                        "M8.6 Complementary shadow storage writer was not planned: " + writer);
                assertTrue(plan.storageBuffers().entrySet().stream()
                                .filter(entry -> !entry.getKey().equals("shadow"))
                                .flatMap(entry -> entry.getValue().stream())
                                .anyMatch(value -> value.blockName().equals("blockDataBuffer")
                                        && value.logicalIndex() == 0
                                        && (value.stageMask() & 16) != 0),
                        "M8.6 Complementary storage reader was not planned");
                PackProgramPlan composite = packPlan.program("composite");
                assertTrue(composite != null && composite.executable()
                                && packPlan.shouldAttempt("composite"),
                        "M8.6 Complementary composite reader is not executable: "
                                + (composite == null ? "missing" : composite.deviations()));
                assertTrue(composite.convertedFragment() != null
                                && composite.stageSource("fragment") != null
                                && !composite.stageSource("fragment").contains("gl_Fog.")
                                && composite.stageSource("fragment").contains("fogStart")
                                && composite.stageSource("fragment").contains("fogEnd"),
                        "M8.6 Complementary legacy fog normalization did not reach the plan");
                assertTrue(composite.interfacePlan().deviations().stream().noneMatch(value ->
                                value.equals("UNIFORM_IMPLICIT_DECLARATION:textureSize")),
                        "M8.6 Complementary textureSize call became a generated uniform");
                assertTrue(composite.interfacePlan().deviations().stream().noneMatch(value ->
                                value.equals("SAMPLER_NOT_MAPPED:tex")),
                        "M8.6 Complementary source-only tex remains a blocking sampler: "
                                + composite.interfacePlan().deviations());
                assertTrue(plan.storageBuffers("composite").stream().anyMatch(value ->
                                value.blockName().equals("blockDataBuffer")
                                        && value.logicalIndex() == 0
                                        && (value.stageMask() & 16) != 0),
                        "M8.6 Complementary composite fragment storage reader is missing");
                assertTrue(!analysis.settings().customSamplerSlots().containsKey("tex"),
                        "M8.6 Complementary source-only tex consumed a custom sampler slot");
                PackResourceBinding blockAtlas = packPlan.resources().bindings().values().stream()
                        .flatMap(java.util.Collection::stream)
                        .filter(value -> value.source().equals("minecraft:textures/atlas/blocks.png"))
                        .findFirst().orElse(null);
                assertTrue(blockAtlas != null
                                && blockAtlas.status() == PackResourceStatus.HOST_ALIAS
                                && blockAtlas.kind() == PackResourceKind.TARGET
                                && blockAtlas.resourceKey().equals("texture")
                                && packPlan.resources().programAllowed(blockAtlas.program()),
                        "M8.6 Complementary generated block atlas was not a truthful host alias: "
                                + blockAtlas);
            }
            if (label.equals("BSL")) {
                Map<String, Integer> slots = analysis.settings().customSamplerSlots();
                assertTrue(slots.containsKey("voxeltex") && slots.containsKey("lighttex0")
                                && slots.containsKey("lighttex1"),
                        "M8.6 BSL advanced sampler slots are incomplete: " + slots);
                PackProgramPlan composite4 = packPlan.program("composite4");
                assertTrue(composite4 != null && composite4.executable()
                                && composite4.convertedFragment() != null
                                && composite4.stageSource("fragment") != null
                                && composite4.stageSource("fragment").contains("#define Bayer4")
                                && composite4.stageSource("fragment").contains("#define Bayer8"),
                        "M8.6 BSL composite4 macro/function plan is not executable: "
                                + (composite4 == null ? "missing" : composite4.deviations()));
            }
            if (label.equals("BSL") || label.equals("Complementary")) {
                PackProgramPlan terrain = packPlan.program("gbuffers_terrain");
                assertTrue(terrain != null && terrain.executable()
                                && packPlan.shouldAttempt("gbuffers_terrain"),
                        "M8.6 " + label + " terrain plan is not executable with advanced resources");
                var samplers = terrain.interfacePlan()
                        .effective(UniformRegistry.Stage.GEOMETRY).samplers().stream()
                        .map(UniformRegistry.SamplerBinding::name).toList();
                if (label.equals("BSL")) {
                    assertTrue(samplers.contains("lighttex0") && samplers.contains("lighttex1"),
                            "M8.6 BSL terrain 3D sampler bindings are missing");
                    assertTrue(packPlan.resources().binding("gbuffers_terrain", "lighttex0") != null
                                    && packPlan.resources().binding("gbuffers_terrain", "lighttex0").kind()
                                    == PackResourceKind.ADVANCED_IMAGE,
                            "M8.6 BSL lighttex0 resource identity is missing");
                }
            }
            System.out.println("[chimera] M8.6a " + label
                    + " explicit advanced settings: PASS");
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                String property = "chimera.option." + entry.getKey();
                if (entry.getValue() == null) System.clearProperty(property);
                else System.setProperty(property, entry.getValue());
            }
        }
    }

    private static void verifyAdvancedPostDescriptorPartition(
            PackPlan packPlan, PackAdvancedResourcePlan resources, String label
    ) {
        PackProgramPlan composite = packPlan.program("composite");
        assertTrue(composite != null && composite.executable(),
                "M8.6 " + label + " composite plan is missing or ineligible");
        PackAdvancedResourcePlan.ProgramBindingLayout layout = resources.bindingLayout("composite");
        Set<String> advanced = layout.advancedSamplers().stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(!advanced.isEmpty(),
                "M8.6 " + label + " composite has no planned advanced sampled resources");
        Set<String> ordinaryIdentities = layout.ordinaryDescriptors().stream()
                .map(PackAdvancedResourcePlan.DescriptorBinding::identity)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> descriptorIdentities = new java.util.HashSet<>();
        for (PackAdvancedResourcePlan.DescriptorBinding descriptor : layout.descriptors()) {
            if (!descriptorIdentities.add(descriptor.identity())) {
                throw new AssertionError("M8.6 " + label
                        + " composite descriptor identity is duplicated in the immutable plan: "
                        + layout.descriptors().stream()
                        .filter(value -> value.identity().equals(descriptor.identity()))
                        .toList() + " advanced=" + layout.advancedSamplers());
            }
        }
        for (PackAdvancedResourcePlan.GraphicsImageBinding sampler : layout.advancedSamplers()) {
            String identity = "resource:" + sampler.selectorSlot() + ":1";
            assertTrue(!ordinaryIdentities.contains(identity),
                    "M8.6 " + label + " advanced sampler remained in ordinary descriptors: "
                            + sampler.sampler());
            long planned = layout.descriptors().stream().filter(value ->
                    value.type() == 1 && value.identity().equals(identity)).count();
            assertTrue(planned == 1,
                    "M8.6 " + label + " advanced sampler descriptor count is not one: "
                            + sampler.sampler());
            assertTrue((sampler.stageMask() & 16) != 0,
                    "M8.6 " + label + " post advanced sampler lacks fragment ownership: "
                            + sampler.sampler());
        }
        if (label.equals("BSL")) {
            assertTrue(advanced.containsAll(Set.of("lighttex0", "lighttex1")),
                    "M8.6 BSL composite advanced reader set is incomplete: " + advanced);
        }
        if (label.equals("Complementary")) {
            assertTrue(advanced.containsAll(Set.of("floodfill_sampler", "floodfill_sampler_copy",
                            "voxel_sampler", "wsr_sampler", "wsr_lod_sampler")),
                    "M8.6 Complementary composite advanced reader set is incomplete: " + advanced);
        }
    }

    private static void verifyDepthDependentPostSchedule(
            PackProbe.Analysis analysis,
            String label
    ) {
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                2560, 1440, 8, 16384);
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(
                analysis.plan().programs(), graph);
        PackFrameSchedulePlan.PostStage deferred = schedule.postStage("deferred");
        if (label.equals("BSL") && deferred != null
                && deferred.window() == PackFrameSchedulePlan.PostWindow.LATE) {
            assertTrue(schedule.deviations().contains("SCHEDULE_POST_AFTER_DEPTH:deferred"),
                    "M8.6 BSL depth-dependent deferred stage lacks its schedule deviation");
        }
    }

    private static void verifyPostDescriptorStageOwnership(
            PackPlan packPlan, PackAdvancedResourcePlan resources, String label
    ) {
        for (PackProgramPlan program : packPlan.programs()) {
            if (!PostTargetPlan.isPostProgramName(program.name())
                    || program.convertedVertex() == null
                    || program.interfacePlan() == null) {
                continue;
            }
            List<PackAdvancedResourcePlan.DescriptorBinding> vertexBindings =
                    PackAdvancedResourcePlan.shaderBindings(
                            program.convertedVertex(), 1);
            if (vertexBindings.isEmpty()) continue;
            Set<String> advancedSamplerNames = resources.bindingLayout(program.name()).advancedSamplers().stream()
                    .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                    .collect(java.util.stream.Collectors.toSet());
            List<PackAdvancedResourcePlan.DescriptorBinding> ordinary =
                    resources.bindingLayout(program.name()).ordinaryDescriptors();
            PackAdvancedResourcePlan.DescriptorBinding vertexUbo = vertexBindings.stream()
                    .filter(value -> value.type() == 8)
                    .findFirst().orElse(null);
            if (vertexUbo != null) {
                PackAdvancedResourcePlan.DescriptorBinding matching = ordinary.stream()
                        .filter(value -> value.identity().equals("ubo:" + vertexUbo.binding()))
                        .findFirst().orElse(null);
                assertTrue(matching != null,
                        "M8.6 " + label + " post ordinary UBO is missing: " + program.name());
                assertTrue((matching.stages() & 1) != 0,
                        "M8.6 " + label + " post ordinary descriptor lost vertex ownership: "
                                + program.name() + " " + vertexUbo.identity());
            }
            Set<String> vertexSamplerNames = vertexBindings.stream()
                    .filter(value -> value.type() == 1)
                    .map(PackAdvancedResourcePlan.DescriptorBinding::symbol)
                    .collect(java.util.stream.Collectors.toSet());
            for (UniformRegistry.SamplerBinding sampler : program.interfacePlan().samplers()) {
                if (!vertexSamplerNames.contains(sampler.name())
                        || advancedSamplerNames.contains(sampler.name())) {
                    continue;
                }
                PackAdvancedResourcePlan.DescriptorBinding matching = ordinary.stream()
                        .filter(value -> value.identity().equals(
                                "resource:" + sampler.slot() + ":1"))
                        .findFirst().orElse(null);
                assertTrue(matching != null,
                        "M8.6 " + label + " post ordinary sampler is missing: "
                                + program.name() + " " + sampler.name());
                assertTrue((matching.stages() & 1) != 0,
                        "M8.6 " + label + " post ordinary sampler lost vertex ownership: "
                                + program.name() + " " + sampler.name());
            }
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
