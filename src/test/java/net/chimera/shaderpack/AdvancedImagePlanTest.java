package net.chimera.shaderpack;

import net.chimera.render.PackAdvancedImageOwner;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Advanced images and the shadow-compute plan. */
final class AdvancedImagePlanTest {
    @Test
    void advancedImages() throws Exception {
        Path root = TestPacks.fixtures();
        Path supportedPath = root.resolve("m8_6/advanced_images");
        Path unsupportedPath = root.resolve("m8_6/unsupported_advanced");
        assertTrue(Files.isDirectory(supportedPath), "supported fixture is missing");
        assertTrue(Files.isRegularFile(root.resolve("baselines/m8_6.json")),
                "baseline is missing");

        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        PackAdvancedResourcePlan supportedPlan = supported.plan().advancedResources();
        ConformanceReport.ProgramReport computeReport = supported.report().program("shadowcomp");
        assertTrue(computeReport != null && computeReport.stages().equals(List.of("compute")),
                "compute report is not first-class");
        assertTrue(!computeReport.deviations().contains("MISSING_FRAGMENT_SOURCE")
                        && !computeReport.deviations().contains("MODERN_GLSL_UNSUPPORTED"),
                "compute report contains graphics-only rejection reasons");
        assertTrue(computeReport.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                "supported compute report is not supported");
        assertEquals(supportedPlan.snapshot(), supportedAgain.plan().advancedResources().snapshot(),
                "supported plan is not deterministic");
        assertTrue(supportedPlan.images().size() == 2, "image declaration count");
        assertTrue(supportedPlan.hasSupportedImages(), "supported images rejected");
        assertTrue(supportedPlan.hasSupportedCompute(), "shadow compute rejected");
        assertTrue(supportedPlan.capabilityPossible(), "capability was not planned");
        assertTrue(PackAdvancedImageOwner.shouldInitializeImage(false, false),
                "persistent images must be seeded on first use");
        assertTrue(!PackAdvancedImageOwner.shouldInitializeImage(false, true),
                "persistent images must not be cleared every frame");
        assertTrue(PackAdvancedImageOwner.shouldInitializeImage(true, true),
                "per-frame clear policy was lost");
        assertEquals(2, supportedPlan.computeStages().get("shadowcomp").localSizeX(),
                "workgroup X");
        assertTrue(supportedPlan.graphicsImages().containsKey("shadow"),
                "graphics image producer was not discovered");
        assertTrue(supportedPlan.graphicsImages("shadow").stream()
                        .anyMatch(binding -> binding.imageName().equals("voxel_img")
                                && binding.symbol().equals("voxel_sampler")),
                "shadow imageStore binding is missing");

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        PackAdvancedResourcePlan unsupportedPlan = unsupported.plan().advancedResources();
        assertTrue(!unsupportedPlan.capabilityPossible(), "unsupported capability enabled");
        assertTrue(unsupportedPlan.deviations().stream().anyMatch(value ->
                        value.startsWith("ADVANCED_IMAGE_UNSUPPORTED:too_large")),
                "oversized image deviation missing");
        assertTrue(unsupportedPlan.deviations().contains("STORAGE_BUFFER_DEFERRED:bufferObject.0"),
                "storage-buffer deferral missing");

        PackAdvancedResourcePlan budgetPlan = budgetIsolationPlan(root.resolve("m8_6/budget_isolation"));
        assertNoAbsolutePaths(supportedPlan.snapshot());
        assertNoAbsolutePaths(budgetPlan.snapshot());

        String baseline = Files.readString(root.resolve("baselines/m8_6.json"));
        assertTrue(baseline.contains(supportedPlan.fingerprint()),
                "supported fingerprint baseline mismatch");
        assertTrue(baseline.contains(unsupportedPlan.fingerprint()),
                "unsupported fingerprint baseline mismatch");
        assertTrue(baseline.contains(budgetPlan.fingerprint()),
                "budget-isolation fingerprint baseline mismatch");
    }

    /** The budget fixture's plan, which rejects only its oversized image and keeps compute. */
    private static PackAdvancedResourcePlan budgetIsolationPlan(Path budgetPath) {
        assertTrue(Files.isDirectory(budgetPath), "budget fixture is missing");
        PackProbe.Analysis budget = PackProbe.analyze(budgetPath);
        PackAdvancedResourcePlan budgetPlan = budget.plan().advancedResources();
        PackAdvancedResourcePlan.ImageSpec small = budgetPlan.images().get("small_image");
        PackAdvancedResourcePlan.ImageSpec large = budgetPlan.images().get("large_image");
        assertTrue(small != null && small.supported(),
                "budget fixture rejected the valid image");
        assertTrue(large != null && !large.supported(),
                "budget fixture admitted the oversized image");
        assertTrue(budgetPlan.hasSupportedCompute() && budgetPlan.capabilityPossible(),
                "budget fixture disabled compute because of an unrelated image");
        assertTrue(budgetPlan.deviations().contains("ADVANCED_IMAGE_BUDGET_EXCEEDED:large_image"),
                "per-resource budget deviation missing");
        assertEquals(budgetPlan.snapshot(),
                PackProbe.analyze(budgetPath).plan().advancedResources().snapshot(),
                "budget plan is not deterministic");
        return budgetPlan;
    }

    @Test
    void optionOverrideActivatesAdvancedImages() throws Exception {
        Path overridePath = TestPacks.fixture("m8_6/option_override");
        assertTrue(Files.isDirectory(overridePath), "option override fixture is missing");
        PackAdvancedResourcePlan defaultOverridePlan = PackProbe.analyze(overridePath)
                .plan().advancedResources();
        assertTrue(!defaultOverridePlan.capabilityPossible(),
                "option fixture enabled advanced images without an override");
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
                "explicit option did not activate the image declaration");
        assertTrue(overridePlan.capabilityPossible(),
                "explicit option did not activate shadow compute");
        assertTrue(overridden.settings().deviations().contains("PACK_OPTION_OVERRIDE:ENABLE_ADVANCED"),
                "explicit option deviation missing");
    }

    @Test
    void advancedSamplerSourceRules() {
        String boundedFetch = GlslTokenRewriter.rewriteTexelFetchBounds(
                "vec4 value = texelFetch(lighttex, pos + ivec3(1, 0, 0), 0);",
                Set.of("lighttex"));
        assertTrue(boundedFetch.contains("clamp(pos + ivec3(1, 0, 0), ivec3(0)"),
                "3D texelFetch was not bounded");
        String boundedHelperFetch = GlslTokenRewriter.rewriteTexelFetchBounds(
                "vec4 sample(sampler3D lighttex, ivec3 pos) {"
                        + " return texelFetch(lighttex, pos, 0); }",
                Set.of("lighttex"));
        assertTrue(boundedHelperFetch.contains("textureSize(lighttex, 0) - ivec3(1)"),
                "helper-function 3D texelFetch was not bounded");
        UniformRegistry.ProgramInterface shadowedSampler = UniformRegistry.planPrepared(
                "uniform sampler2D specular;"
                        + "float readValue() { float specular = 1.0; return specular; }",
                UniformRegistry.Stage.GEOMETRY);
        assertTrue(shadowedSampler.deviations().stream().noneMatch(value ->
                        value.equals("SAMPLER_NOT_MAPPED:specular")),
                "local sampler shadowing was treated as a resource use");
        UniformRegistry.ProgramInterface helperSampler = UniformRegistry.planPrepared(
                "uniform sampler2D customSampler;"
                        + "vec4 sampleIt(sampler2D value, vec2 uv) { return texture2D(value, uv); }"
                        + "vec4 readValue(vec2 uv) { return sampleIt(customSampler, uv); }",
                UniformRegistry.Stage.GEOMETRY);
        assertTrue(helperSampler.deviations().contains("SAMPLER_NOT_MAPPED:customSampler"),
                "helper sampler dependency was not detected");
    }

    @Test
    @Tag("packs")
    void complementary() throws Exception {
        verifyRealPack(TestPacks.complementary(),
                Map.of("COLORED_LIGHTING", "128", "WORLD_SPACE_REFLECTIONS", "1"), "Complementary");
    }

    @Test
    @Tag("packs")
    void bsl() throws Exception {
        verifyRealPack(TestPacks.bsl(),
                Map.of("MULTICOLORED_BLOCKLIGHT", "1", "MCBL_DISTANCE", "128", "MCBL_HALF_HEIGHT", "1"), "BSL");
    }

    @Test
    void packFinalHandSchedule() throws Exception {
        Path mainSource = sourcePath("src/main/java/net/chimera/render/ChimeraMainPass.java");
        Path handSource = sourcePath("src/main/java/net/chimera/mixin/ChimeraItemInHandRendererMixin.java");
        Path depthSource = sourcePath("src/main/java/net/chimera/mixin/GameRendererDepthMixin.java");
        String main = Files.readString(mainSource);
        String hand = Files.readString(handSource);
        String depth = Files.readString(depthSource);

        assertTrue(!main.contains("finishPackFinalAfterHand"),
                "final still has a late hand-return hook");
        assertTrue(!main.contains("seedFinalPackInput")
                        && !main.contains("nextFinalInputTarget")
                        && !main.contains("commitFinalInput")
                        && !main.contains("finalSeedPipeline"),
                "obsolete host-composition seed path remains");
        assertTrue(!hand.contains("finishPackFinalAfterHand"),
                "hand mixin still owns pack final");
        assertTrue(!depth.contains("finishPackFinalAfterHand"),
                "depth mixin still owns pack final");
        String schedule = Files.readString(sourcePath("src/main/java/net/chimera/render/ChimeraHandRenderer.java"));
        String level = Files.readString(sourcePath("src/main/java/net/chimera/mixin/ChimeraLevelRendererMixin.java"));
        assertTrue(schedule.contains("!pass.hasPackHandSchedule()")
                        && depth.contains("if (!ChimeraHandRenderer.diverted()) renderer.renderHandsWithItems"),
                "hand fallback must remain vanilla unless both pack phases are installed");
        assertTrue(level.contains("stringValue=translucent") && level.contains("ChimeraHandRenderer.drawSolid();"),
                "opaque pack hand must run before translucent terrain");
        assertTrue(level.indexOf("ChimeraHandRenderer.drawTranslucent();")
                        < level.indexOf("pass.finishLevelSegment();"),
                "translucent pack hand must run before pack final");
        // Hand models, items and maps flush in their family lanes, then glint in its own, all
        // inside the hand scope that selects the hand lanes.
        assertTrue(schedule.indexOf("renderHandsWithItems(") < schedule.indexOf("buffers.endFamilyBatches();")
                        && schedule.indexOf("buffers.endFamilyBatches();") < schedule.indexOf("buffers.endBatch();")
                        && schedule.indexOf("buffers.endBatch();") < schedule.indexOf("translucentPhase = null;"),
                "hand submission and both flushes must share the hand scope");

        int resolve = main.indexOf("resolvePackWorldToOutput(commandBuffer, hdrColor);");
        int finalCall = main.indexOf("finishPackFinalBeforeHand();", resolve);
        int returnIndex = main.indexOf("return;", finalCall);
        assertTrue(resolve >= 0 && finalCall > resolve && returnIndex > finalCall,
                "pack final is not ordered after world resolve and before hand");
        assertEquals(1, count(main, "finishPackFinalBeforeHand();"),
                "pack final must have one authoritative call site");
    }

    @Test
    void legacyLavaFog() {
        String source = "#version 120\nuniform vec3 fogColor;\n"
                + "void DoLavaFog(inout vec4 color, float lViewPos) {\n"
                + " float fog = (lViewPos * 3.0 - gl_Fog.start) * gl_Fog.scale;\n"
                + " fog = clamp(1.0 - exp(-fog), 0.0, 1.0);\n"
                + " color = mix(color, vec4(fogColor * 5.0, 0.0), fog);\n}\n"
                + "void main() { vec4 color = vec4(1.0);"
                + " DoLavaFog(color, 4.0); gl_FragColor = color; }\n";
        LegacyShaderNormalizer.Result normalized = LegacyShaderNormalizer.normalize(source, false);
        assertTrue(normalized.successful(), "DoLavaFog normalization failed");
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(
                normalized.source(), PostTargetPlan.parse("composite", source).plan());
        assertTrue(plan.executable()
                        && plan.uniforms().contains(new UniformRegistry.UniformDeclaration("fogStart", "float"))
                        && plan.uniforms().contains(new UniformRegistry.UniformDeclaration("fogEnd", "float")),
                "DoLavaFog must retain both live environmental fog uniforms");

        // Division exposes a missing outer pair of parentheses; multiplication alone does not.
        LegacyShaderNormalizer.Result division = LegacyShaderNormalizer.normalize(
                "float probe = 6.0 / gl_Fog.scale;", false);
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

    @Test
    void engineDefines() {
        String predicate = "#version 430 compatibility\n"
                + "#if COLORED_LIGHTING > 0 && (!defined IS_IRIS || !defined IRIS_FEATURE_CUSTOM_IMAGES)\n"
                + "Text beginTextM printChar\n"
                + "#endif\n"
                + "void main() {}\n";
        ShaderSourcePreprocessor.Result enabled = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, predicate,
                PackEngineDefines.forCustomImages(Map.of("COLORED_LIGHTING", "128")),
                PackEngineDefines.lockedNames(Set.of("COLORED_LIGHTING"), true, false));
        assertTrue(enabled.successful(), "engine define preparation failed");
        assertTrue(!enabled.source().contains("Text")
                        && !enabled.source().contains("beginTextM")
                        && !enabled.source().contains("printChar"),
                "IS_IRIS predicate did not remove the compatibility error branch");

        ShaderSourcePreprocessor.Result noEngine = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, predicate,
                Map.of("COLORED_LIGHTING", "128"), Set.of());
        assertTrue(noEngine.successful() && noEngine.source().contains("beginTextM"),
                "negative engine-define predicate case is invalid");
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
                "snapshot contains an absolute path");
    }

    private static void verifyRealPack(Path path, Map<String, String> overrides, String label) throws Exception {
        Map<String, String> previous = new TreeMap<>();
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
                        label + " advanced path was rejected without a named reason");
                throw new AssertionError(label
                        + " supplied real pack did not reach the required advanced path: "
                        + plan.deviations());
            }
            assertTrue(plan.capabilityPossible(),
                    label + " advanced path is not eligible under explicit settings");
            assertTrue(plan.computeStages().get("shadowcomp") != null
                            && plan.computeStages().get("shadowcomp").supported(),
                    label + " shadowcomp is not supported under explicit settings");
            PackProgramPlan shadow = packPlan.program("shadow");
            assertTrue(shadow != null && shadow.executable() && packPlan.shouldAttempt("shadow"),
                    label + " shadow producer is not executable with advanced resources");
            verifyShadowAdapter(shadow, label);
            assertTrue(!plan.computeStages().get("shadowcomp").samplerNames().isEmpty(),
                    label + " 3D sampler dependencies were not planned");
            if (plan.graphicsImages().containsKey("shadow")) {
                assertTrue(plan.graphicsImageNames(Set.of("shadow"))
                                .containsAll(plan.requiredGraphicsImageNames()),
                        label
                                + " optional graphics producers incorrectly block image coverage");
            }
            assertTrue(plan.deviations().stream().noneMatch(value ->
                            value.startsWith("PACK_RESOURCE_SLOT_LIMIT:")),
                    label + " live advanced sampler was starved by a slot limit: "
                            + plan.deviations());
            if (label.equals("Complementary")) {
                verifyComplementaryResources(analysis, plan);
            } else {
                verifyBslResources(analysis);
            }
            verifyAdvancedTerrain(packPlan, label);
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                String property = "chimera.option." + entry.getKey();
                if (entry.getValue() == null) System.clearProperty(property);
                else System.setProperty(property, entry.getValue());
            }
        }
    }

    /** The converted shadow vertex keeps the canonical matrix UBO and drops gl_VertexID. */
    private static void verifyShadowAdapter(PackProgramPlan shadow, String label) {
        String vertex = shadow.executableVertexSource();
        // Camera gbuffer uniforms stay distinct from the legacy
        // shadow-stage built-ins. Do not require a camera matrix to
        // survive conversion when only the shadow draw pair is used.
        assertTrue(vertex.contains("ChimeraShadowUniforms")
                        && vertex.contains("shadowModelView")
                        && vertex.contains("shadowProjection"),
                label + " shadow adapter lost its canonical matrix UBO");
        assertTrue(vertex.contains("chimeraShadowUniforms.shadowModelView")
                        && vertex.contains("chimeraShadowUniforms.shadowProjection"),
                label + " shadow adapter did not qualify its draw matrix UBO access");
        assertTrue(!vertex.contains("gl_VertexID"),
                label + " shadow adapter left Vulkan-incompatible gl_VertexID");
    }

    /** Complementary's WSR image and buffer, advanced sampler slots, storage readers and composite. */
    private static void verifyComplementaryResources(PackProbe.Analysis analysis, PackAdvancedResourcePlan plan) {
        PackAdvancedResourcePlan.ImageSpec wsr = plan.images().get("wsr_img");
        assertTrue(wsr != null && wsr.supported()
                        && wsr.width() == 128 && wsr.height() == 64 && wsr.depth() == 128
                        && wsr.widthToken().equals("COLORED_LIGHTING")
                        && wsr.heightToken().equals("64")
                        && wsr.depthToken().equals("COLORED_LIGHTING"),
                "Complementary WSR dimensions were not resolved from the active option");
        PackAdvancedResourcePlan.BufferSpec wsrBuffer = plan.buffers().get(0);
        assertTrue(wsrBuffer != null && wsrBuffer.supported()
                        && wsrBuffer.size() == 50_855_936L,
                "Complementary WSR bufferObject.0 was not admitted at its authored size");
        verifyComplementaryShadowResources(analysis, plan);
        verifyComplementaryComposite(analysis, plan);
        verifyComplementaryBlockAtlas(analysis.plan());
    }

    /** Complementary's advanced sampler slots and its shadow image producer, readers and storage writer. */
    private static void verifyComplementaryShadowResources(PackProbe.Analysis analysis,
                                                           PackAdvancedResourcePlan plan) {
        Map<String, Integer> slots = analysis.settings().customSamplerSlots();
        assertTrue(slots.containsKey("voxel_sampler")
                        && slots.containsKey("wsr_sampler")
                        && slots.containsKey("wsr_lod_sampler"),
                "Complementary advanced sampler slots are incomplete: " + slots);
        assertTrue(plan.graphicsImages("shadow").stream().anyMatch(value ->
                        value.imageName().equals("wsr_img")),
                "Complementary WSR image producer was not admitted");
        Set<String> shadowReaders = plan.graphicsImageReaders("shadow").stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .collect(Collectors.toSet());
        assertTrue(shadowReaders.containsAll(Set.of(
                        "voxel_sampler", "floodfill_sampler", "floodfill_sampler_copy")),
                "Complementary shadow helper samplers were not declared: "
                        + shadowReaders);
        List<PackAdvancedResourcePlan.StorageBufferBinding> writer =
                plan.storageBuffers("shadow");
        assertTrue(writer.stream().anyMatch(value ->
                        value.blockName().equals("blockDataBuffer")
                                && value.logicalIndex() == 0
                                && value.originalBinding() == 0
                                && (value.stageMask() & 1) != 0),
                "Complementary shadow storage writer was not planned: " + writer);
        assertTrue(plan.storageBuffers().entrySet().stream()
                        .filter(entry -> !entry.getKey().equals("shadow"))
                        .flatMap(entry -> entry.getValue().stream())
                        .anyMatch(value -> value.blockName().equals("blockDataBuffer")
                                && value.logicalIndex() == 0
                                && (value.stageMask() & 16) != 0),
                "Complementary storage reader was not planned");
    }

    /** Complementary's composite reads storage and normalized fog, without a generated or source-only sampler. */
    private static void verifyComplementaryComposite(PackProbe.Analysis analysis, PackAdvancedResourcePlan plan) {
        PackPlan packPlan = analysis.plan();
        PackProgramPlan composite = packPlan.program("composite");
        assertTrue(composite != null && composite.executable()
                        && packPlan.shouldAttempt("composite"),
                "Complementary composite reader is not executable: "
                        + (composite == null ? "missing" : composite.deviations()));
        assertTrue(composite.convertedFragment() != null
                        && composite.stageSource("fragment") != null
                        && !composite.stageSource("fragment").contains("gl_Fog.")
                        && composite.stageSource("fragment").contains("fogStart")
                        && composite.stageSource("fragment").contains("fogEnd"),
                "Complementary legacy fog normalization did not reach the plan");
        assertTrue(composite.interfacePlan().deviations().stream().noneMatch(value ->
                        value.equals("UNIFORM_IMPLICIT_DECLARATION:textureSize")),
                "Complementary textureSize call became a generated uniform");
        assertTrue(composite.interfacePlan().deviations().stream().noneMatch(value ->
                        value.equals("SAMPLER_NOT_MAPPED:tex")),
                "Complementary source-only tex remains a blocking sampler: "
                        + composite.interfacePlan().deviations());
        assertTrue(plan.storageBuffers("composite").stream().anyMatch(value ->
                        value.blockName().equals("blockDataBuffer")
                                && value.logicalIndex() == 0
                                && (value.stageMask() & 16) != 0),
                "Complementary composite fragment storage reader is missing");
        assertTrue(!analysis.settings().customSamplerSlots().containsKey("tex"),
                "Complementary source-only tex consumed a custom sampler slot");
    }

    /** Complementary's generated block atlas resolves as a host alias for an allowed program. */
    private static void verifyComplementaryBlockAtlas(PackPlan packPlan) {
        PackResourceBinding blockAtlas = packPlan.resources().bindings().values().stream()
                .flatMap(Collection::stream)
                .filter(value -> value.source().equals("minecraft:textures/atlas/blocks.png"))
                .findFirst().orElse(null);
        assertTrue(blockAtlas != null
                        && blockAtlas.status() == PackResourceStatus.HOST_ALIAS
                        && blockAtlas.kind() == PackResourceKind.TARGET
                        && blockAtlas.resourceKey().equals("texture")
                        && packPlan.resources().programAllowed(blockAtlas.program()),
                "Complementary generated block atlas was not a truthful host alias: "
                        + blockAtlas);
    }

    /** BSL's 3D light volume slots and its composite4 plan, whose Bayer4/Bayer8 macros arrive expanded. */
    private static void verifyBslResources(PackProbe.Analysis analysis) {
        PackPlan packPlan = analysis.plan();
        Map<String, Integer> slots = analysis.settings().customSamplerSlots();
        assertTrue(slots.containsKey("voxeltex") && slots.containsKey("lighttex0")
                        && slots.containsKey("lighttex1"),
                "BSL advanced sampler slots are incomplete: " + slots);
        PackProgramPlan composite4 = packPlan.program("composite4");
        assertTrue(composite4 != null && composite4.executable() && composite4.convertedFragment() != null,
                "BSL composite4 macro/function plan is not executable: "
                        + (composite4 == null ? "missing" : composite4.deviations()));
        String fragment = composite4.stageSource("fragment");
        assertTrue(fragment != null && !GlslLexer.stripComments(fragment).contains("#define"),
                "BSL composite4 kept a macro: " + fragment);
    }

    /** Terrain stays executable with the advanced resources, and BSL's terrain binds its light volumes. */
    private static void verifyAdvancedTerrain(PackPlan packPlan, String label) {
        PackProgramPlan terrain = packPlan.program("gbuffers_terrain");
        assertTrue(terrain != null && terrain.executable()
                        && packPlan.shouldAttempt("gbuffers_terrain"),
                label + " terrain plan is not executable with advanced resources");
        var samplers = terrain.interfacePlan()
                .effective(UniformRegistry.Stage.GEOMETRY).samplers().stream()
                .map(UniformRegistry.SamplerBinding::name).toList();
        if (label.equals("BSL")) {
            assertTrue(samplers.contains("lighttex0") && samplers.contains("lighttex1"),
                    "BSL terrain 3D sampler bindings are missing");
            assertTrue(packPlan.resources().binding("gbuffers_terrain", "lighttex0") != null
                            && packPlan.resources().binding("gbuffers_terrain", "lighttex0").kind()
                            == PackResourceKind.ADVANCED_IMAGE,
                    "BSL lighttex0 resource identity is missing");
        }
    }

    private static void verifyAdvancedPostDescriptorPartition(
            PackPlan packPlan, PackAdvancedResourcePlan resources, String label
    ) {
        PackProgramPlan composite = packPlan.program("composite");
        assertTrue(composite != null && composite.executable(),
                label + " composite plan is missing or ineligible");
        PackAdvancedResourcePlan.ProgramBindingLayout layout = resources.bindingLayout("composite");
        Set<String> advanced = layout.advancedSamplers().stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .collect(Collectors.toSet());
        assertTrue(!advanced.isEmpty(),
                label + " composite has no planned advanced sampled resources");
        Set<String> ordinaryIdentities = layout.ordinaryDescriptors().stream()
                .map(PackAdvancedResourcePlan.DescriptorBinding::identity)
                .collect(Collectors.toSet());
        Set<String> descriptorIdentities = new HashSet<>();
        for (PackAdvancedResourcePlan.DescriptorBinding descriptor : layout.descriptors()) {
            if (!descriptorIdentities.add(descriptor.identity())) {
                throw new AssertionError(label
                        + " composite descriptor identity is duplicated in the immutable plan: "
                        + layout.descriptors().stream()
                        .filter(value -> value.identity().equals(descriptor.identity()))
                        .toList() + " advanced=" + layout.advancedSamplers());
            }
        }
        for (PackAdvancedResourcePlan.GraphicsImageBinding sampler : layout.advancedSamplers()) {
            String identity = "resource:" + sampler.selectorSlot() + ":1";
            assertTrue(!ordinaryIdentities.contains(identity),
                    label + " advanced sampler remained in ordinary descriptors: "
                            + sampler.sampler());
            long planned = layout.descriptors().stream().filter(value ->
                    value.type() == 1 && value.identity().equals(identity)).count();
            assertTrue(planned == 1,
                    label + " advanced sampler descriptor count is not one: "
                            + sampler.sampler());
            assertTrue((sampler.stageMask() & 16) != 0,
                    label + " post advanced sampler lacks fragment ownership: "
                            + sampler.sampler());
        }
        if (label.equals("BSL")) {
            assertTrue(advanced.containsAll(Set.of("lighttex0", "lighttex1")),
                    "BSL composite advanced reader set is incomplete: " + advanced);
        }
        if (label.equals("Complementary")) {
            assertTrue(advanced.containsAll(Set.of("floodfill_sampler", "floodfill_sampler_copy",
                            "voxel_sampler", "wsr_sampler", "wsr_lod_sampler")),
                    "Complementary composite advanced reader set is incomplete: " + advanced);
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
        // Iris order: BSL's deferred reads depthtex2 and still runs before translucents.
        if (label.equals("BSL") && deferred != null) {
            assertTrue(deferred.window() == PackFrameSchedulePlan.PostWindow.EARLY
                            && !schedule.deviations().contains("SCHEDULE_POST_AFTER_DEPTH:deferred"),
                    "BSL deferred left the Iris deferred seam: " + schedule.snapshot());
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
                    .collect(Collectors.toSet());
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
                        label + " post ordinary UBO is missing: " + program.name());
                assertTrue((matching.stages() & 1) != 0,
                        label + " post ordinary descriptor lost vertex ownership: "
                                + program.name() + " " + vertexUbo.identity());
            }
            Set<String> vertexSamplerNames = vertexBindings.stream()
                    .filter(value -> value.type() == 1)
                    .map(PackAdvancedResourcePlan.DescriptorBinding::symbol)
                    .collect(Collectors.toSet());
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
                        label + " post ordinary sampler is missing: "
                                + program.name() + " " + sampler.name());
                assertTrue((matching.stages() & 1) != 0,
                        label + " post ordinary sampler lost vertex ownership: "
                                + program.name() + " " + sampler.name());
            }
        }
    }
}
