package net.chimera.shaderpack;

import net.chimera.render.PackAdvancedImageOwner;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;

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
        PassComputePlan.PassCompute shadowcomp = supported.passComputes().compute("shadowcomp");
        assertTrue(shadowcomp != null && shadowcomp.supported(), "shadow compute rejected");
        assertTrue(supportedPlan.capabilityPossible(), "capability was not planned");
        assertTrue(PackAdvancedImageOwner.shouldInitializeImage(false, false),
                "persistent images must be seeded on first use");
        assertTrue(!PackAdvancedImageOwner.shouldInitializeImage(false, true),
                "persistent images must not be cleared every frame");
        assertTrue(PackAdvancedImageOwner.shouldInitializeImage(true, true),
                "per-frame clear policy was lost");
        assertEquals(2, shadowcomp.localSize()[0], "workgroup X");
        assertTrue(supportedPlan.graphicsImages().containsKey("shadow"),
                "graphics image producer was not discovered");
        assertTrue(supportedPlan.graphicsImages("shadow").stream()
                        .anyMatch(binding -> binding.imageName().equals("voxel_img")
                                && binding.symbol().equals("voxel_sampler")),
                "shadow imageStore binding is missing");

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        PackAdvancedResourcePlan unsupportedPlan = unsupported.plan().advancedResources();
        assertTrue(!unsupportedPlan.capabilityPossible(), "unsupported capability enabled");
        // 1024^3 R16UI texels, 2 GiB: past the byte budget, which bounds an image in place of an axis cap.
        assertTrue(unsupportedPlan.deviations().stream().anyMatch(value ->
                        value.startsWith("ADVANCED_IMAGE_BUDGET_EXCEEDED:too_large")),
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

    /**
     * A custom image takes any InternalTextureFormat Iris knows (ShaderProperties, GlImage): Chimera
     * knew only r8ui, r16ui and rgba16f, so Pegasus's R32UI and RGBA8 images were refused.
     */
    @Test
    void imageFormatsFollowIris() {
        PackAdvancedResourcePlan.ImageSpec integer = AdvancedImageDeclarations.parseImage("image.cimage1a",
                "cSampler1 RED_INTEGER R32UI UNSIGNED_INT true false 64 64 64", Map.of());
        PackAdvancedResourcePlan.ImageSpec color = AdvancedImageDeclarations.parseImage("image.light",
                "cSampler3 RGBA RGBA8 BYTE false false 32 64 32", Map.of());
        PackAdvancedResourcePlan.ImageSpec padded = AdvancedImageDeclarations.parseImage("image.padded",
                "padded RGB rgb16f HALF_FLOAT false false 8 8 8", Map.of());
        PackAdvancedResourcePlan.ImageSpec unknown = AdvancedImageDeclarations.parseImage("image.unknown",
                "unknown RGBA NOT_A_FORMAT FLOAT false false 8 8 8", Map.of());
        assertTrue(integer.supported() && color.supported() && padded.supported());
        assertEquals(64L * 64L * 64L * 4L, integer.estimatedBytes());
        assertEquals("r32ui", integer.layoutQualifier());
        assertEquals("uimage3D", AdvancedImageDeclarations.storageImageType(integer));
        assertEquals("rgba8", color.layoutQualifier());
        assertEquals("image3D", AdvancedImageDeclarations.storageImageType(color));
        assertEquals("rgba16f", padded.layoutQualifier(), "RGB16F is stored with alpha");
        assertTrue(padded.deviations().contains("ADVANCED_IMAGE_FORMAT_APPROXIMATED:padded=RGB16F->RGBA16F"),
                padded.deviations().toString());
        assertTrue(color.deviations().stream().noneMatch(value -> value.startsWith("ADVANCED_IMAGE_FORMAT_")));
        assertTrue(!unknown.supported() && unknown.deviations().contains("ADVANCED_IMAGE_UNSUPPORTED:unknown"));
    }

    /**
     * Iris's ShaderProperties reads 7, 8 or 9 values as a 1D, 2D or 3D image, and a relative image as
     * 2D at a share of the screen (GlImage.Relative). Reverie's composites read its screen-sized images.
     */
    @Test
    void imagesOfEveryIrisShape(@TempDir Path root) throws Exception {
        PackAdvancedResourcePlan.ImageSpec line = AdvancedImageDeclarations.parseImage("image.line",
                "lineSampler RED_INTEGER R32UI UNSIGNED_INT false false 64", Map.of());
        PackAdvancedResourcePlan.ImageSpec plane = AdvancedImageDeclarations.parseImage("image.plane",
                "none RGBA RGBA8 BYTE false false 32 16", Map.of());
        // ShaderProperties splits on single spaces: a tab or a double space shifts the values.
        assertTrue(!AdvancedImageDeclarations.parseImage("image.tabbed", "none\tRGBA RGBA8\tBYTE false false 32\t16",
                Map.of()).supported() && !AdvancedImageDeclarations.parseImage("image.spaced",
                "none RGBA  RGBA8 BYTE false false 32 16", Map.of()).supported());
        PackAdvancedResourcePlan.ImageSpec screen = AdvancedImageDeclarations.parseImage("image.screen",
                "screenSampler RGBA RGBA16F HALF_FLOAT true true SHARE 0.25", Map.of("SHARE", "0.5"));
        assertTrue(line.supported() && plane.supported() && screen.supported());
        // No per-axis cap below the device's: Alpha Piscium's 1056x832 image, Bloop's 2048-texel line.
        assertTrue(AdvancedImageDeclarations.parseImage("image.probe",
                        "probeSampler RGBA RGBA16F HALF_FLOAT false false 1056 832", Map.of()).supported()
                && AdvancedImageDeclarations.parseImage("image.blocks",
                        "blockSampler RED_INTEGER R32UI UNSIGNED_INT false false 2048", Map.of()).supported());
        assertEquals("uimage1D", AdvancedImageDeclarations.storageImageType(line));
        assertEquals("image2D", AdvancedImageDeclarations.storageImageType(plane));
        assertEquals("", plane.sampler(), "a sampler named none is no sampler");
        assertArrayEquals(new int[] {64, 1, 1}, line.extent(1920, 1080));
        assertArrayEquals(new int[] {32, 16, 1}, plane.extent(1920, 1080));
        assertArrayEquals(new int[] {960, 270, 1}, screen.extent(1920, 1080));
        assertEquals(2, screen.dimensions());

        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.screen = screenSampler RGBA RGBA16F HALF_FLOAT true true 0.5 0.5\n");
        writeVolumeReader(shaders, "composite", "unused");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 430 compatibility
                layout(rgba16f) uniform restrict image2D screen;
                uniform sampler2D screenSampler;
                in vec2 uv;
                void main() { gl_FragData[0] = imageLoad(screen, ivec2(0)) + texture(screenSampler, uv); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(plan.shouldAttempt("composite"), advanced.deviations().toString());
        String bound = PackDescriptorContracts.bindStorageImages(plan.program("composite").convertedFragment(),
                "composite", advanced, advanced.bindingLayout("composite"), VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(bound.matches("(?s).*layout\\(rgba16f, binding = \\d+\\) uniform image2D screen;.*")
                && bound.matches("(?s).*layout\\(binding = \\d+\\) uniform sampler2D screenSampler;.*"), bound);
    }

    /**
     * Iris adds the first 16 images in file order and drops the rest (ShaderProperties). An entry it
     * cannot read, such as a relative image without its height share, adds nothing and counts for none.
     */
    @Test
    void irisKeepsTheFirstSixteenImages(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        StringBuilder properties = new StringBuilder("image.short = none RGBA RGBA8 BYTE false true 0.5\n");
        for (int image = 17; image >= 0; image--) {
            properties.append(String.format("image.n%02d = none RGBA RGBA8 BYTE false false 4 4%n", image));
        }
        Files.writeString(shaders.resolve("shaders.properties"), properties);
        PackSettingsPlan settings = PackProbe.analyze(root).settings();
        assertTrue(settings.deviations().containsAll(List.of("ADVANCED_IMAGE_LIMIT_EXCEEDED:n01",
                "ADVANCED_IMAGE_LIMIT_EXCEEDED:n00")) && settings.propertyValues().containsKey("image.n17")
                && settings.propertyValues().containsKey("image.n02")
                && !settings.propertyValues().containsKey("image.n00"), settings.deviations().toString());
    }

    /**
     * Iris creates custom images whatever programs the pack has and runs each program on its own.
     * Chimera served them only alongside a supported shadowcomp, so Pegasus's gbuffers programs,
     * which read an image its prepare computes write, fell back.
     */
    @Test
    void imagesServeEachProgramWithoutAShadowcomp(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                image.light = lightSampler RGBA RGBA8 BYTE false false 16 16 16
                image.unknown = unknownSampler RGBA NOT_A_FORMAT BYTE false false 16 16 16
                """);
        writeVolumeReader(shaders, "composite", "lightSampler");
        writeVolumeReader(shaders, "composite1", "unknownSampler");
        PackPlan plan = PackProbe.analyze(root).plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(advanced.capabilityPossible(), advanced.deviations().toString());
        assertTrue(advanced.imagesServable("composite") && plan.shouldAttempt("composite"),
                plan.program("composite").deviations().toString());
        assertTrue(!advanced.imagesServable("composite1") && !plan.shouldAttempt("composite1")
                        && advanced.deviations().contains("ADVANCED_IMAGE_UNSERVED:composite1:unknown"),
                advanced.deviations().toString());
    }

    /**
     * A storage image nothing reads is inactive, as GL leaves it, and its declaration is dropped
     * (Pegasus's gbuffers declare cimage3_colored_light, which only voxelizing writes). So is one
     * only a helper main never calls reads: GL compiles the helper away, and Iris binds no
     * inactive image (Rethinking Voxels' composite7). The stage goes to the converters without
     * that helper, on the same lines.
     */
    @Test
    void anImageOnlyADeadHelperReadsIsDroppedAsAnUnreadOne(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                image.light = lightSampler RGBA RGBA8 BYTE false false 16 16 16
                image.spare = spareSampler RGBA RGBA8 BYTE false false 16 16 16
                """);
        writeVolumeReader(shaders, "composite", "lightSampler");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 430 compatibility
                uniform sampler3D lightSampler;
                layout (rgba8) uniform image3D light;
                layout (rgba8) uniform image3D spare;
                in vec2 uv;
                vec4 neverCalled() { return imageLoad(light, ivec3(0)); }
                void main() { gl_FragData[0] = texture(lightSampler, vec3(uv, 0.5)); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackPlan plan = analysis.plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(advanced.imagesServable("composite") && plan.shouldAttempt("composite")
                && !advanced.declaresUnplannedResource("composite"), advanced.deviations().toString());
        PackProgramPlan composite = plan.program("composite");
        String prepared = composite.stages().get("fragment").source();
        assertTrue(prepared.contains("in vec2 uv;\n\nvoid main()"), prepared);
        String fragment = PackDescriptorContracts.bindStorageImages(composite.convertedFragment(), "composite",
                advanced, advanced.bindingLayout("composite"), VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(!fragment.contains("image3D light") && !fragment.contains("image3D spare")
                && !fragment.contains("neverCalled"), fragment);
        assertTrue(!analysis.report().program("composite").deviations().contains("ADVANCED_RESOURCE_UNSUPPORTED"),
                analysis.report().program("composite").deviations().toString());
    }

    /**
     * An image Chimera does not create (one in a format Iris does not know) is inactive where a
     * stage only declares it, as GL leaves it, and its declaration is dropped. A stage that reads it
     * is refused with the image named, before preparation.
     */
    @Test
    void anImageChimeraDoesNotCreateIsDroppedUnlessRead(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.screen = screenSampler RGBA NOT_A_FORMAT HALF_FLOAT true true 1.0 1.0\n");
        String fragment = """
                #version 430 compatibility
                layout(rgba16f) uniform restrict image2D screen;
                in vec2 uv;
                void main() { gl_FragData[0] = vec4(uv, 0.0, 1.0)%s; }
                """;
        for (String program : List.of("composite", "composite1", "composite2")) {
            writeVolumeReader(shaders, program, "unused");
        }
        Files.writeString(shaders.resolve("composite.fsh"), fragment.formatted(""));
        Files.writeString(shaders.resolve("composite1.fsh"), fragment.formatted(" + imageLoad(screen, ivec2(0))"));
        writeVolumeReader(shaders, "composite2", "screenSampler");
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        assertTrue(!analysis.plan().shouldAttempt("composite2") && analysis.report().program("composite2")
                        .deviations().contains("ADVANCED_IMAGE_UNSERVED:screen"),
                "reading the sampler of an image Chimera does not create runs nothing in its place");
        PackPlan plan = analysis.plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(plan.shouldAttempt("composite"), advanced.deviations().toString());
        String declaredOnly = PackDescriptorContracts.bindStorageImages(plan.program("composite").convertedFragment(),
                "composite", advanced, advanced.bindingLayout("composite"), VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(!declaredOnly.contains("image2D screen"), declaredOnly);
        assertTrue(!plan.shouldAttempt("composite1")
                && advanced.deviations().contains("ADVANCED_IMAGE_UNSERVED:composite1:screen"),
                advanced.deviations().toString());
    }

    /**
     * Glimmer's header declares its multiple-scattering LUT's sampler in every program, and only its
     * compute passes read it, so no program Chimera runs gives it a slot. A program that only
     * declares it leaves it inactive and runs, as under GL.
     */
    @Test
    void anImageSamplerNoProgramReadsNeedsNoSlot(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.lut = lutTex RGBA RGBA16F HALF_FLOAT false false 32 32 32\n");
        writeVolumeReader(shaders, "composite", "unused");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 430 compatibility
                uniform sampler3D lutTex;
                in vec2 uv;
                void main() { gl_FragData[0] = vec4(uv, 0.0, 1.0); }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        assertTrue(!plan.settings().customSamplerSlots().containsKey("lutTex")
                && plan.shouldAttempt("composite"), plan.advancedResources().deviations().toString());
        assertTrue(plan.program("composite").deviations().contains("SAMPLER_DECLARATION_UNUSED:lutTex"),
                plan.program("composite").deviations().toString());
    }

    /**
     * Rethinking Voxels gives occupancyVolume and voxelCols the sampler none, which Iris reads as no
     * sampler. Each image still binds, so each keeps its own selector slot under its own name.
     */
    @Test
    void imagesWithoutASamplerKeepTheirOwnSlots(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                image.occupancy = none RED_INTEGER R32I INT true false 16 16 16
                image.cols = none RED_INTEGER R32I INT true false 16 32 16
                """);
        writeVolumeReader(shaders, "composite", "unused");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 430 compatibility
                layout(r32i) uniform iimage3D occupancy;
                layout(r32i) uniform iimage3D cols;
                in vec2 uv;
                void main() {
                    gl_FragData[0] = vec4(imageLoad(occupancy, ivec3(0)).r + imageLoad(cols, ivec3(0)).r);
                }
                """);
        PackPlan plan = PackProbe.analyze(root).plan();
        Map<String, Integer> slots = plan.settings().customSamplerSlots();
        assertTrue(slots.containsKey("occupancy") && slots.containsKey("cols")
                && !slots.get("occupancy").equals(slots.get("cols")) && !slots.containsKey("none"), slots.toString());
        assertTrue(plan.shouldAttempt("composite"), plan.advancedResources().deviations().toString());
    }

    /**
     * Alpha Piscium's water declares its R32F image r32i for integer atomics, which a GL image unit
     * reads as the same bits. The program binds a view of that format; one of another texel size
     * has no such reading and is refused.
     */
    @Test
    void anImageReadThroughAnotherFormatOfItsSizeBindsThatView(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "image.csr = csrSampler RED R32F FLOAT false true 1.0 1.0\n");
        writeVolumeReader(shaders, "composite", "unused");
        String fragment = """
                #version 430 compatibility
                layout(%s) uniform iimage2D csr;
                in vec2 uv;
                void main() { gl_FragData[0] = vec4(imageAtomicAdd(csr, ivec2(0), 1)); }
                """;
        Files.writeString(shaders.resolve("composite.fsh"), fragment.formatted("r32i"));
        PackPlan plan = PackProbe.analyze(root).plan();
        PackAdvancedResourcePlan advanced = plan.advancedResources();
        assertTrue(plan.shouldAttempt("composite")
                        && advanced.viewFormats("csr").equals(Set.of(IrisColorFormats.of("R32I").vkFormat())),
                advanced.deviations().toString());
        String bound = PackDescriptorContracts.bindStorageImages(plan.program("composite").convertedFragment(),
                "composite", advanced, advanced.bindingLayout("composite"), VK_SHADER_STAGE_FRAGMENT_BIT);
        assertTrue(bound.matches("(?s).*layout\\(r32i, binding = \\d+\\) uniform iimage2D csr;.*"), bound);
        assertEquals(IrisColorFormats.of("R32I").vkFormat(), advanced.bindingLayout("composite").storageImages()
                .get(0).viewVkFormat());

        Files.writeString(shaders.resolve("composite.fsh"), fragment.formatted("rgba16i"));
        PackPlan wider = PackProbe.analyze(root).plan();
        assertTrue(!wider.shouldAttempt("composite") && wider.advancedResources().deviations()
                .contains("ADVANCED_IMAGE_FORMAT_INCOMPATIBLE:csr:rgba16i"),
                wider.advancedResources().deviations().toString());

        // One descriptor binds the image for the whole program: two stages cannot read two views.
        Files.writeString(shaders.resolve("composite.fsh"), fragment.formatted("r32i"));
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 430 compatibility
                layout(r32f) uniform image2D csr;
                out vec2 uv;
                void main() { uv = imageLoad(csr, ivec2(0)).xy; gl_Position = ftransform(); }
                """);
        PackPlan split = PackProbe.analyze(root).plan();
        assertTrue(!split.shouldAttempt("composite") && split.advancedResources().deviations()
                .contains("ADVANCED_IMAGE_FORMAT_CONFLICT:composite:csr"),
                split.advancedResources().deviations().toString());
    }

    private static void writeVolumeReader(Path shaders, String program, String sampler) throws Exception {
        Files.writeString(shaders.resolve(program + ".vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve(program + ".fsh"), """
                #version 120
                uniform sampler3D %s;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture3D(%s, vec3(uv, 0.5)); }
                """.formatted(sampler, sampler));
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
        PassComputePlan.PassCompute shadowcomp = budget.passComputes().compute("shadowcomp");
        assertTrue(shadowcomp != null && shadowcomp.supported() && budgetPlan.capabilityPossible(),
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
                List.of());
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
            PassComputePlan.PassCompute shadowcomp = analysis.passComputes().compute("shadowcomp");
            System.out.println("[chimera] M8.6 " + label + " imagePlan=" + plan.images().keySet()
                    + " shadowcomp=" + (shadowcomp == null ? "none" : shadowcomp.deviations())
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
            assertTrue(shadowcomp != null && shadowcomp.supported(),
                    label + " shadowcomp is not supported under explicit settings");
            PackProgramPlan shadow = packPlan.program("shadow");
            assertTrue(shadow != null && shadow.executable() && packPlan.shouldAttempt("shadow"),
                    label + " shadow producer is not executable with advanced resources");
            verifyShadowAdapter(shadow, label);
            assertTrue(shadowcomp.bindings().stream().anyMatch(binding
                            -> binding.kind() == PassComputePlan.Kind.PACK_IMAGE_SAMPLER),
                    label + " 3D sampler dependencies were not planned");
            assertTrue(plan.dependentPrograms().stream().allMatch(plan::imagesServable),
                    label + " a program's images are not all created and bound: " + plan.deviations());
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
