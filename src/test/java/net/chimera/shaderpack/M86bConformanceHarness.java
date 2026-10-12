package net.chimera.shaderpack;

import net.chimera.render.PackStorageBufferDescriptor;
import net.chimera.render.PackStorageBufferOwner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic M8.6b bounded storage-buffer plan and descriptor checks. */
public final class M86bConformanceHarness {
    private static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC = 9;
    private static final int VK_SHADER_STAGE_VERTEX_BIT = 1;
    private static final int VK_SHADER_STAGE_FRAGMENT_BIT = 16;
    private M86bConformanceHarness() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path supportedPath = root.resolve("m8_6b/ssbo_supported");
        Path unsupportedPath = root.resolve("m8_6b/unsupported_ssbo");
        assertTrue(Files.isDirectory(supportedPath), "M8.6b supported fixture is missing");
        assertTrue(Files.isDirectory(unsupportedPath), "M8.6b unsupported fixture is missing");

        verifyExtendedSelectorRouting();
        verifyShadowRegressions();
        verifyExactPackBuilderMetadata();

        PackProbe.Analysis supported = PackProbe.analyze(supportedPath);
        PackProbe.Analysis supportedAgain = PackProbe.analyze(supportedPath);
        PackAdvancedResourcePlan plan = supported.plan().advancedResources();
        PackAdvancedResourcePlan repeatPlan = supportedAgain.plan().advancedResources();

        PackAdvancedResourcePlan.BufferSpec named = plan.buffers().get(0);
        PackAdvancedResourcePlan.BufferSpec unnamed = plan.buffers().get(1);
        PackAdvancedResourcePlan.BufferSpec upper = plan.buffers().get(12);
        assertTrue(named != null && named.supported() && named.size() == 64
                        && named.requestedName().equals("blockDataBuffer"),
                "M8.6b named absolute buffer was not parsed");
        assertTrue(unnamed != null && unnamed.supported() && unnamed.size() == 32,
                "M8.6b unnamed absolute buffer was not parsed");
        assertTrue(upper != null && upper.supported(),
                "M8.6b logical buffer index 12 was not accepted");

        PackProgramPlan shadow = supported.plan().program("shadow");
        PackProgramPlan composite = supported.plan().program("composite");
        assertTrue(shadow != null && shadow.executable() && supported.plan().shouldAttempt("shadow"),
                "M8.6b shadow program is not executable: "
                        + (shadow == null ? "missing" : shadow.deviations())
                        + " advanced=" + plan.snapshot());
        assertTrue(composite != null && composite.executable()
                        && supported.plan().shouldAttempt("composite"),
                "M8.6b composite program is not executable");
        ConformanceReport.ProgramReport supportedShadowReport = supported.report().program("shadow");
        assertTrue(supportedShadowReport != null
                        && supportedShadowReport.support()
                        == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                "M8.6b supported storage program was reported as generic fallback: "
                        + (supportedShadowReport == null ? "missing"
                        : supportedShadowReport.support() + " " + supportedShadowReport.deviations()));

        List<PackAdvancedResourcePlan.StorageBufferBinding> shadowBindings = plan.storageBuffers("shadow");
        assertTrue(shadowBindings.size() == 1, "M8.6b shadow binding count");
        PackAdvancedResourcePlan.StorageBufferBinding shadowBinding = shadowBindings.get(0);
        assertTrue(shadowBinding.supported() && shadowBinding.logicalIndex() == 0
                        && shadowBinding.originalBinding() == 0
                        && shadowBinding.stageMask() == (VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                "M8.6b shadow name/binding or stage merge is incorrect");
        int shadowBase = PackPipelines.storageBufferBindingBase(shadow);
        assertTrue(shadowBinding.rewrittenBinding() == shadowBase,
                "M8.6b shadow binding is not compact after generated descriptors");

        List<PackAdvancedResourcePlan.StorageBufferBinding> compositeBindings = plan.storageBuffers("composite");
        assertTrue(compositeBindings.size() == 1 && compositeBindings.get(0).supported()
                        && compositeBindings.get(0).logicalIndex() == 1
                        && compositeBindings.get(0).originalBinding() == 1,
                "M8.6b unnamed binding did not resolve by authored binding");
        String rewritten = plan.rewriteStorageBuffers("shadow", shadow.stageSource("fragment"));
        assertTrue(rewritten != null
                        && rewritten.contains("layout(std430, binding = "
                        + shadowBinding.rewrittenBinding() + ") buffer blockDataBuffer")
                        && rewritten.contains("uint values[]")
                        && rewritten.contains("blockDataBuffer;"),
                "M8.6b storage block rewrite lost std430 members or instance");

        PackStorageBufferDescriptor descriptor = new PackStorageBufferDescriptor(
                "test", shadowBinding.rewrittenBinding(), shadowBinding.stageMask(), 64);
        assertTrue(descriptor.getType() == VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC,
                "M8.6b descriptor is not dynamic storage-buffer type");
        assertTrue(descriptor.getSize() == 64 && !descriptor.useGlobalBuffer(),
                "M8.6b descriptor range or global-buffer policy is incorrect");

        verifyMixedBindingAgreement(supportedPath);
        verifyStorageParserAndLiveResourceUse();
        assertTrue(PackStorageBufferOwner.unsignedStorageBufferRange(-1)
                        == 4_294_967_295L,
                "M8.6b unsigned Vulkan storage range conversion is incorrect");

        PackProbe.Analysis unsupported = PackProbe.analyze(unsupportedPath);
        PackAdvancedResourcePlan unsupportedPlan = unsupported.plan().advancedResources();
        PackAdvancedResourcePlan.BufferSpec invalidZero = unsupportedPlan.buffers().get(0);
        PackAdvancedResourcePlan.BufferSpec invalidIndex = unsupportedPlan.buffers().get(13);
        assertTrue(invalidZero != null && !invalidZero.supported()
                        && invalidZero.deviations().stream()
                        .anyMatch(value -> value.startsWith("STORAGE_BUFFER_SIZE_UNSUPPORTED:")),
                "M8.6b zero-size rejection is missing");
        assertTrue(invalidIndex != null && !invalidIndex.supported()
                        && invalidIndex.deviations().contains("STORAGE_BUFFER_INDEX_UNSUPPORTED:13"),
                "M8.6b index-13 rejection is missing");
        PackProgramPlan unsupportedShadow = unsupported.plan().program("shadow");
        assertTrue(unsupportedShadow != null && !unsupportedShadow.executable()
                        && !unsupported.plan().shouldAttempt("shadow"),
                "M8.6b unmapped storage block did not scope fallback to the program");
        assertTrue(unsupported.report().program("shadow") != null
                        && unsupported.report().program("shadow").support()
                        == ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                "M8.6b unsupported storage program was not reported as identity fallback");
        assertTrue(!unsupportedPlan.storageBufferCapabilityPossible()
                        && !unsupportedPlan.storageBufferProgramSupported("shadow"),
                "M8.6b invalid storage did not remain scoped to its dependent program");
        PackProgramPlan unrelatedFinal = unsupported.plan().program("final");
        assertTrue(unrelatedFinal != null && unrelatedFinal.executable()
                        && unsupported.plan().shouldAttempt("final"),
                "M8.6b unrelated final program was disabled by storage fallback");

        assertEquals(plan.snapshot(), repeatPlan.snapshot(), "M8.6b plan is not deterministic");
        assertEquals(plan.fingerprint(), repeatPlan.fingerprint(), "M8.6b fingerprint is not deterministic");
        assertNoAbsolutePaths(plan.snapshot());

        System.out.println("[chimera] M8.6b bounded storage-buffer conformance: PASS");
        System.out.println("[chimera] M8.6b supportedFingerprint=" + plan.fingerprint());
        System.out.println("[chimera] M8.6b unsupportedFingerprint=" + unsupportedPlan.fingerprint());
    }

    /** Run independently so one broken shadow contract cannot hide the other. */
    private static void verifyShadowRegressions() throws Exception {
        List<String> failures = new java.util.ArrayList<>();
        for (boolean advanced : new boolean[] {true, false}) {
            String regression = advanced ? "SHADOW_STAGE_LOCAL_RESOURCE" : "SHADOW_NATIVE_UBO_STAGES";
            Path fixture = Files.createTempDirectory("chimera-shadow-regression-");
            try {
                Path shaders = Files.createDirectories(fixture.resolve("shaders"));
                Files.writeString(shaders.resolve("shaders.properties"), advanced
                        ? "image.volume_img=volume_sampler rgba rgba16f half_float false false 8 4 8\n"
                        : "");
                Files.writeString(shaders.resolve("shadow.vsh"), "#version 120\n"
                        + "uniform float frameTimeCounter;\n"
                        + (advanced ? "uniform image3D volume_img;\nuniform sampler3D volume_sampler;\n" : "")
                        + "void main() { gl_Position=ftransform(); gl_Position.x += frameTimeCounter;"
                        + (advanced ? "imageStore(volume_img, ivec3(0), texture(volume_sampler, vec3(0)));" : "")
                        + " }\n");
                Files.writeString(shaders.resolve("shadow.fsh"),
                        "#version 120\nvoid main() { gl_FragColor=vec4(1.0); }\n");
                PackPlan pack = PackProbe.analyze(fixture).plan();
                PackProgramPlan shadow = pack.program("shadow");
                assertTrue(shadow != null && shadow.executable(), regression + " fixture rejected: "
                        + (shadow == null ? "missing" : shadow.deviations()));
                var resources = pack.advancedResources();
                var layout = resources.bindingLayout("shadow");
                List<PackAdvancedResourcePlan.DescriptorBinding> nativeDescriptors =
                        baselineShadowBuilderDescriptors(shadow, resources);
                if (advanced) {
                    var sampler = layout.advancedSamplers().stream()
                            .filter(value -> value.sampler().equals("volume_sampler"))
                            .findFirst().orElseThrow();
                    assertEquals(VK_SHADER_STAGE_VERTEX_BIT, sampler.stageMask(),
                            "Vertex-only resource was widened in the plan");
                    String vertex = PackPipelines.bindStorageImages(shadow.convertedVertex(), "shadow",
                            resources, layout, VK_SHADER_STAGE_VERTEX_BIT);
                    String fragment = PackPipelines.bindStorageImages(shadow.convertedFragment(), "shadow",
                            resources, layout, VK_SHADER_STAGE_FRAGMENT_BIT);
                    assertTrue(!fragment.contains("uniform sampler3D volume_sampler"),
                            "Vertex-only advanced sampler leaked into final shadow fragment; rewrite=" + fragment);
                    assertTrue(vertex != null && vertex.contains("uniform sampler3D volume_sampler"),
                            "Vertex lost its live advanced sampler");
                    assertEquals(null, PackAdvancedResourcePlan.layoutMismatch(layout, vertex, fragment,
                            nativeDescriptors), "Shadow resource layout disagrees with actual builder metadata");
                } else {
                    for (int binding = 0; binding < 4; binding++) {
                        final int target = binding;
                        var actual = nativeDescriptors.stream().filter(value -> value.type() == 8
                                && value.binding() == target).findFirst().orElseThrow();
                        int stages = binding < 3 ? VK_SHADER_STAGE_VERTEX_BIT
                                : VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                        assertEquals(stages, actual.stages() & 17, "Actual shadow UBO stage contract at " + binding);
                    }
                    assertEquals(null, PackAdvancedResourcePlan.layoutMismatch(layout,
                            shadow.convertedVertex(), shadow.convertedFragment(), nativeDescriptors),
                            "Shadow UBO plan disagrees with actual Pipeline.Builder.applyConfig metadata");
                }
                System.out.println("[chimera] " + regression + ": PASS");
            } catch (AssertionError | Exception failure) {
                String reason = regression + ": " + failure.getClass().getName() + ": " + failure.getMessage();
                failures.add(reason);
                System.err.println("[chimera] " + reason);
            } finally {
                try (var paths = Files.walk(fixture)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "Shadow regressions failed: " + String.join("; ", failures));
    }

    /** Baseline mirrors the current runtime JSON path, never the predicted descriptor list. */
    private static List<PackAdvancedResourcePlan.DescriptorBinding> baselineShadowBuilderDescriptors(
            PackProgramPlan shadow, PackAdvancedResourcePlan resources
    ) {
        var iface = shadow.interfacePlan().effective(UniformRegistry.Stage.SHADOW);
        var layout = resources.bindingLayout("shadow");
        Set<String> advancedNames = layout.advancedSamplers().stream()
                .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                .collect(java.util.stream.Collectors.toSet());
        var json = PackPipelines.shadowPipelineJson();
        var samplers = new com.google.gson.JsonArray();
        int[] slots = PackPipelines.shadowSamplerSlots(iface.samplers().stream()
                .filter(value -> !advancedNames.contains(value.name()))
                .mapToInt(UniformRegistry.SamplerBinding::slot).distinct().toArray());
        for (int slot : slots) {
            var sampler = new com.google.gson.JsonObject();
            sampler.addProperty("name", "Sampler" + slot);
            samplers.add(sampler);
        }
        json.add("samplers", samplers);
        var generated = new com.google.gson.JsonObject();
        generated.addProperty("type", "all");
        generated.addProperty("binding", 3);
        var fields = new com.google.gson.JsonArray();
        for (var uniform : iface.executableUniforms()) {
            var field = new com.google.gson.JsonObject();
            field.addProperty("name", uniform.name());
            field.addProperty("type", UniformRegistry.pipelineType(uniform.glslType()));
            field.addProperty("count", UniformRegistry.pipelineCount(uniform.glslType()));
            fields.add(field);
        }
        generated.add("fields", fields);
        assertTrue(!fields.isEmpty(), "Synthetic shadow must include a generated UBO");
        json.getAsJsonArray("UBOs").add(generated);
        var builder = new net.vulkanmod.vulkan.shader.Pipeline.Builder("shadow_regression");
        List<net.vulkanmod.vulkan.shader.descriptor.UBO> buffers = new java.util.ArrayList<>();
        List<net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor> images = new java.util.ArrayList<>();
        builder.setUniforms(buffers, images);
        // Descriptor preparation installs suppliers but does not execute them or access Vulkan.
        builder.setUniformSupplierGetter(info -> () -> null);
        builder.applyConfig(net.vulkanmod.vulkan.shader.PipelineConfig.fromJson("shadow_regression", json));
        int index = 0;
        for (var binding : layout.storageImages()) {
            builder.addImageDescriptor(new net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor(
                    layout.imageBase() + index++, "image3D", "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), 3));
        }
        index = 0;
        for (var binding : layout.advancedSamplers()) {
            builder.addImageDescriptor(new net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor(
                    layout.samplerBase() + index++, "sampler3D", "Sampler" + binding.selectorSlot(),
                    binding.selectorSlot(), 1));
        }
        List<PackAdvancedResourcePlan.DescriptorBinding> actual = new java.util.ArrayList<>();
        for (var buffer : builder.getUBOs()) {
            actual.add(new PackAdvancedResourcePlan.DescriptorBinding(buffer.name, 0, buffer.getBinding(),
                    buffer.getType(), buffer.getStages(), "ubo:" + buffer.getBinding()));
        }
        for (var image : images) {
            actual.add(new PackAdvancedResourcePlan.DescriptorBinding(image.name, 0, image.getBinding(),
                    image.getType(), image.getStages(), "resource:" + image.name.substring(7) + ":" + image.getType()));
        }
        return List.copyOf(actual);
    }

    private static void verifyExtendedSelectorRouting() throws Exception {
        Class<?> mixin = Class.forName("net.chimera.mixin.VTextureSelectorMixin");
        Class<?> callbackType = org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable.class;
        var read = mixin.getDeclaredMethod("chimera$getExtendedImage", int.class, callbackType);
        var bound = mixin.getDeclaredMethod("chimera$getExtendedBoundTexture", int.class, callbackType);
        var name = mixin.getDeclaredMethod("chimera$extendedSampler", String.class, callbackType);
        var bind = mixin.getDeclaredMethod("chimera$bindExtendedTexture", int.class,
                net.vulkanmod.vulkan.texture.VulkanImage.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        for (var method : List.of(read, bound, name, bind)) method.setAccessible(true);
        var backing = mixin.getDeclaredField("chimera$extendedTextures");
        backing.setAccessible(true);
        assertEquals(SelectorNamespace.EXTENDED_CAPACITY, java.lang.reflect.Array.getLength(backing.get(null)), "Backing must cover 8..26");
        // CPU-only image metadata constructor: no image allocation, views, or Vulkan device.
        var constructor = net.vulkanmod.vulkan.texture.VulkanImage.class.getDeclaredConstructor(
                net.vulkanmod.vulkan.texture.VulkanImage.Builder.class);
        constructor.setAccessible(true);
        var original = constructor.newInstance(net.vulkanmod.vulkan.texture.VulkanImage.builder(1, 1));
        var replacement = constructor.newInstance(net.vulkanmod.vulkan.texture.VulkanImage.builder(1, 1));
        Object[] host = new Object[12];
        for (int slot = 8; slot <= SelectorNamespace.LAST_RESERVED; slot++) {
            assertTrue(SelectorNamespace.isAddressable(slot), "Reserved selector rejected: " + slot);
            var named = new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer>("name", true);
            name.invoke(null, "Sampler" + slot, named);
            assertTrue(named.isCancelled() && named.getReturnValue() == slot, "Sampler name escaped: " + slot);
            selectorBind(bind, slot, original, host);
            Object captured = selectorRead(read, slot, host);
            selectorBind(bind, slot, replacement, host);
            assertTrue(selectorRead(bound, slot, host) == replacement, "Rebind lost image: " + slot);
            selectorBind(bind, slot, captured, host);
            assertTrue(selectorRead(read, slot, host) == original, "Restore lost original: " + slot);
        }
        java.util.BitSet cleared = new java.util.BitSet();
        SelectorNamespace.clearOwned(slot -> {
            try { selectorBind(bind, slot, null, host); cleared.set(slot); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        assertEquals(SelectorNamespace.LAST_RESERVED + 1, cleared.cardinality(), "Teardown did not clear full owned range");
        for (int slot = 0; slot <= SelectorNamespace.LAST_RESERVED; slot++) {
            assertTrue(selectorRead(read, slot, host) == null, "Teardown retained image: " + slot);
        }
        int unsupported = SelectorNamespace.LAST_RESERVED + 1;
        var descriptor = new PackAdvancedResourcePlan.DescriptorBinding("future", 0, 0, 1, 16,
                "resource:" + unsupported + ":1");
        var layout = new PackAdvancedResourcePlan.ProgramBindingLayout(1, 1, 1,
                List.of(), List.of(), List.of(), List.of(descriptor), List.of(descriptor));
        try {
            PackPipelines.prepareBuilder(new PackPipelines.PreparedPipeline(null, "", "", layout, new ProgramImageBindingManifest(List.of())),
                    "future", PackAdvancedResourcePlan.empty(), null, info -> () -> null);
            throw new AssertionError("Future selector reached builder creation");
        } catch (PackPipelines.PreparationFailure failure) {
            assertEquals("descriptor-contract", failure.phase, "Wrong selector failure phase");
            assertEquals("SELECTOR_SLOT_UNSUPPORTED:" + unsupported, failure.reason, "Wrong selector reason");
        }
        System.out.println("[chimera] extended selector routing/lifecycle/preparation: PASS");
    }

    private static Object selectorRead(java.lang.reflect.Method method, int slot, Object[] host) throws Exception {
        var callback = new org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Object>("read", true);
        method.invoke(null, slot, callback);
        return callback.isCancelled() ? callback.getReturnValue() : host[slot];
    }

    private static void selectorBind(java.lang.reflect.Method method, int slot, Object image, Object[] host)
            throws Exception {
        var callback = new org.spongepowered.asm.mixin.injection.callback.CallbackInfo("bind", true);
        method.invoke(null, slot, image, callback);
        if (!callback.isCancelled()) host[slot] = image;
    }

    /**
     * R4 exact gate: real pack planning, real shared preparation, real
     * applyConfig metadata, real layoutMismatch. No predicted layout echo.
     */
    private static void verifyExactPackBuilderMetadata() throws Exception {
        List<String> failures = new java.util.ArrayList<>();
        long skyColourDeclarations = 0L;
        for (String[] configured : new String[][] {
                {"Complementary", "chimera.m86.complementary"},
                {"BSL", "chimera.m86.bsl"}}) {
            String label = "exact " + configured[0] + " shadow/composite builder parity";
            Map<String, String> overrides = configured[0].equals("Complementary")
                    ? Map.of("COLORED_LIGHTING", "128", "WORLD_SPACE_REFLECTIONS", "1")
                    : Map.of("MULTICOLORED_BLOCKLIGHT", "1", "MCBL_DISTANCE", "128", "MCBL_HALF_HEIGHT", "1");
            Map<String, String> previous = new java.util.HashMap<>();
            overrides.forEach((key, setting) -> previous.put(key,
                    System.setProperty("chimera.option." + key, setting)));
            try {
                String value = System.getProperty(configured[1],
                        System.getenv(configured[1].replace('.', '_').toUpperCase(java.util.Locale.ROOT)));
                assertTrue(value != null && !value.isBlank(), configured[1] + " pack path not provided");
                Path packPath = Path.of(value);
                assertTrue(Files.isRegularFile(packPath) || Files.isDirectory(packPath),
                        label + ": missing pack " + packPath);
                PackProbe.Analysis probe = PackProbe.analyze(packPath);
                // The pinned packs colour their atmosphere from the pack-facing sky state, and a
                // program handed the zero default cannot do it. These manifest rows are per
                // program, so scan them rather than the aggregate, and count the declarations so
                // the check cannot pass by finding nothing to look at.
                List<String> skyColourDefaulted = probe.report().programs().stream()
                        .filter(program -> program.deviations().contains("UNIFORM_DEFAULTED:skyColor"))
                        .map(program -> program.name()).toList();
                assertTrue(skyColourDefaulted.isEmpty(),
                        label + ": sky colour is still a default in " + skyColourDefaulted);
                skyColourDeclarations += probe.report().programs().stream()
                        .filter(program -> program.uniforms().contains("skyColor")).count();
                PackPlan pack = probe.plan();
                PackProgramPlan shadow = pack.program("shadow");
                PackProgramPlan composite = pack.program("composite");
                PackAdvancedResourcePlan resources = pack.advancedResources();
                assertTrue(shadow != null && shadow.executable(), label + ": shadow not executable");
                assertTrue(composite != null && composite.executable(), label + ": composite not executable");
                for (var sampler : composite.interfacePlan().samplers()) {
                    assertTrue(SelectorNamespace.isAddressable(sampler.slot()),
                            label + ": unaddressable composite sampler " + sampler);
                }
                if (shadow != null && shadow.executable()) {
                    PackPipelines.PreparedPipeline prepared = PackPipelines.prepareShadow(shadow, resources);
                    var builder = PackPipelines.prepareBuilder(prepared, shadow.name(), resources, null,
                            info -> () -> null);
                    var actual = PackPipelines.builderDescriptors(builder);
                    assertEquals(null, PackAdvancedResourcePlan.layoutMismatch(prepared.layout(),
                            prepared.vertex(), prepared.fragment(), actual), label);
                    prepared.imageBindings().verify(actual);
                    assertTrue(actual.stream().filter(d -> d.type() == 8).count() >= 3,
                            label + ": shadow UBO contract lost base blocks");
                    for (var sampler : resources.bindingLayout("shadow").advancedSamplers()) {
                        var descriptor = actual.stream()
                                .filter(d -> d.identity().equals("resource:" + sampler.selectorSlot() + ":1"))
                                .findFirst().orElseThrow();
                        assertEquals(sampler.stageMask(), descriptor.stages() & sampler.stageMask(),
                                label + ": advanced stage mask for " + sampler.sampler());
                    }
                }
                for (PackProgramPlan post : pack.programs()) {
                    if (!post.executable() || !PostTargetPlan.isPostProgramName(post.name())) continue;
                    PackPipelines.PreparedPipeline prepared = PackPipelines.preparePost(post, "", resources);
                    var builder = PackPipelines.prepareBuilder(prepared, post.name(), resources, null,
                            info -> () -> null);
                    var actual = PackPipelines.builderDescriptors(builder);
                    assertEquals(null, PackAdvancedResourcePlan.layoutMismatch(prepared.layout(),
                            prepared.vertex(), prepared.fragment(), actual), label + " " + post.name());
                    prepared.imageBindings().verify(actual);
                    assertEquals(actual.stream().filter(d -> d.type() == 1 || d.type() == 3).count(),
                            (long) prepared.imageBindings().entries().size(), label + " image bijection " + post.name());
                    if (configured[0].equals("Complementary") && post.name().equals("composite")) {
                        var descriptor = actual.stream().filter(d -> d.symbol().equals("Sampler18") && d.type() == 1)
                                .findFirst().orElseThrow();
                        var entry = prepared.imageBindings().entries().stream()
                                .filter(e -> e.slot() == 18 && e.descriptorType() == 1).findFirst().orElseThrow();
                        assertEquals(descriptor.binding(), entry.binding(), "voxel descriptor binding");
                        assertEquals("voxel_sampler", entry.sourceSymbol(), "voxel source sampler");
                        assertEquals("voxel_img", entry.resourceKey(), "voxel allocated image resolver");
                        assertEquals(ProgramImageBindingManifest.Kind.ADVANCED_IMAGE, entry.kind(), "voxel resolver kind");
                        verifyImageTransaction(prepared.imageBindings());
                    }
                    int extraBinding = actual.stream().mapToInt(PackAdvancedResourcePlan.DescriptorBinding::binding)
                            .max().orElse(0) + 1;
                    builder.addImageDescriptor(new net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor(
                            extraBinding, "sampler2D", "Sampler24", 24, 1));
                    try {
                        PackPipelines.createNative(builder, prepared.imageBindings());
                        throw new AssertionError("Unresolved actual builder descriptor reached native creation");
                    } catch (PackPipelines.PreparationFailure failure) {
                        assertEquals("descriptor-contract", failure.phase, "unresolved descriptor phase");
                        assertEquals("IMAGE_RESOLVER_MISSING:Sampler24", failure.reason, "unresolved descriptor reason");
                    }
                }
                System.out.println("[chimera] M8.6b " + label + ": PASS");
            } catch (AssertionError | Exception failure) {
                failures.add(label + ": " + failure + (failure.getCause() == null ? ""
                        : " caused by " + failure.getCause()));
            } finally {
                previous.forEach((key, setting) -> {
                    if (setting == null) System.clearProperty("chimera.option." + key);
                    else System.setProperty("chimera.option." + key, setting);
                });
            }
        }
        assertTrue(skyColourDeclarations > 0L,
                "no exact pack program declares skyColor, so the manifest check proved nothing");
        assertTrue(failures.isEmpty(), "Exact pack builder parity failed: " + String.join("; ", failures));
    }

    private static void verifyImageTransaction(ProgramImageBindingManifest exact) {
        record Pair(Object image, Object textureBinding) {}
        Pair[] state = new Pair[SelectorNamespace.LAST_RESERVED + 1];
        int[] captures = new int[state.length];
        var owner = new java.util.HashMap<String, Pair>();
        for (var entry : exact.entries()) owner.putIfAbsent(entry.resourceKey(), new Pair(new Object(), new Object()));
        var store = new net.chimera.render.ProgramImageBindingTransaction.Store<Pair>() {
            public Pair capture(int slot) { captures[slot]++; return state[slot]; }
            public void bind(int slot, Pair pair) { state[slot] = pair; }
            public void restore(int slot, Pair pair) { state[slot] = pair; }
            public boolean available(Pair pair) { return pair != null && pair.image() != null; }
        };
        try (var ignored = net.chimera.render.ProgramImageBindingTransaction.bind("composite", exact,
                store, entry -> owner.get(entry.resourceKey()))) {
            assertTrue(state[18] == owner.get("voxel_img"), "selector 18 did not bind allocated voxel image");
            for (var entry : exact.entries()) assertTrue(state[entry.slot()] == owner.get(entry.resourceKey()),
                    "descriptor did not bind its resource " + entry.symbol());
        }
        for (var entry : exact.entries()) {
            assertEquals(null, state[entry.slot()], "transaction did not restore prior null");
            assertEquals(1, captures[entry.slot()], "transaction captured shared slot more than once");
        }
        List<ProgramImageBindingManifest.Entry> entries = new java.util.ArrayList<>();
        for (int slot = 22; slot <= 24; slot++) {
            entries.add(new ProgramImageBindingManifest.Entry("Sampler" + slot, slot, 1, 63,
                    ProgramImageBindingManifest.Kind.ADVANCED_IMAGE, "image" + slot, 0, slot, "read" + slot));
            entries.add(new ProgramImageBindingManifest.Entry("Sampler" + slot, slot, 3, 63,
                    ProgramImageBindingManifest.Kind.ADVANCED_IMAGE, "image" + slot, 0, slot + 25, "write" + slot));
        }
        var duplicate = new ProgramImageBindingManifest(entries);
        Pair prior = new Pair(new Object(), new Object());
        Pair bound = new Pair(new Object(), new Object());
        java.util.Arrays.fill(captures, 0);
        state[22] = prior;
        try (var ignored = net.chimera.render.ProgramImageBindingTransaction.bind("duplicate", duplicate,
                store, entry -> bound)) {
            for (int slot = 22; slot <= 24; slot++) assertTrue(state[slot] == bound, "extended selector not bound");
        }
        assertTrue(state[22] == prior, "paired previous image and texture binding not restored");
        for (int slot = 22; slot <= 24; slot++) assertEquals(1, captures[slot], "duplicate slot capture");
        assertEquals(null, state[23], "shared slot prior null not restored");
        assertEquals(null, state[24], "last extended slot prior null not restored");
        try {
            net.chimera.render.ProgramImageBindingTransaction.bind("missing", duplicate, store,
                    entry -> entry.slot() == 24 ? null : bound);
            throw new AssertionError("Missing required image was accepted");
        } catch (IllegalStateException failure) {
            assertTrue(failure.getMessage().startsWith("RESOURCE_BINDING_UNAVAILABLE:missing:"),
                    "missing resource lost named reason: " + failure.getMessage());
        }
        assertTrue(state[22] == prior && state[23] == null && state[24] == null,
                "failed transaction mutated selector or texture state");
        var failingStore = new net.chimera.render.ProgramImageBindingTransaction.Store<Pair>() {
            public Pair capture(int slot) { return store.capture(slot); }
            public void bind(int slot, Pair pair) {
                store.bind(slot, pair);
                if (slot == 23) throw new IllegalStateException("injected bind failure");
            }
            public void restore(int slot, Pair pair) { store.restore(slot, pair); }
            public boolean available(Pair pair) { return store.available(pair); }
        };
        try {
            net.chimera.render.ProgramImageBindingTransaction.bind("partial", duplicate, failingStore, entry -> bound);
            throw new AssertionError("Injected selector failure did not abort binding");
        } catch (IllegalStateException failure) {
            assertEquals("injected bind failure", failure.getMessage(), "bind failure identity");
        }
        assertTrue(state[22] == prior && state[23] == null && state[24] == null,
                "partial bind did not roll back paired image and texture state");
    }

    private static void verifyMixedBindingAgreement(Path supportedPath) throws Exception {
        Path fixture = Files.createTempDirectory("chimera-mixed-bindings-");
        try {
            try (var paths = Files.walk(supportedPath)) {
                for (Path source : paths.toList()) {
                    Path destination = fixture.resolve(supportedPath.relativize(source));
                    if (Files.isDirectory(source)) Files.createDirectories(destination);
                    else Files.copy(source, destination);
                }
            }
            Path shaders = fixture.resolve("shaders");
            Files.writeString(shaders.resolve("shaders.properties"),
                    "bufferObject.0=64 blockDataBuffer\n"
                    + "image.voxel_img=voxel_sampler red_integer r8ui unsigned_int true false 8 4 8\n"
                    + "image.light_img=light_sampler rgba rgba16f half_float false false 8 4 8\n");
            Files.writeString(shaders.resolve("shadow.vsh"),
                    "#version 120\nlayout(std430, binding=0) buffer blockDataBuffer { uint values[]; } blockDataBuffer;\n"
                    + "uniform uimage3D voxel_img;\nuniform image3D light_img;\nuniform usampler3D voxel_sampler;\n"
                    + "uniform sampler3D light_sampler;\n"
                    + "vec4 GetComplexLightVolume(vec3 pos, sampler3D ff_sampler);\n"
                    + "vec4 GetComplexLightVolume(vec3 pos, sampler3D ff_sampler) { return texture(ff_sampler, pos); }\n"
                    + "void main() { imageStore(voxel_img, ivec3(0), uvec4(blockDataBuffer.values[0]));"
                    + "imageStore(light_img, ivec3(0), GetComplexLightVolume(vec3(0), light_sampler)"
                    + " + vec4(texture(voxel_sampler, vec3(0)))); gl_Position=ftransform(); }\n");
            PackPlan pack = PackProbe.analyze(fixture).plan();
            PackAdvancedResourcePlan resources = pack.advancedResources();
            var layout = resources.bindingLayout("shadow");
            PackProgramPlan shadow = pack.program("shadow");
            assertTrue(shadow != null && shadow.convertedVertex() != null && shadow.convertedFragment() != null,
                    "Mixed resource fixture did not translate");
            Set<String> mixedAdvancedNames = layout.advancedSamplers().stream()
                    .map(PackAdvancedResourcePlan.GraphicsImageBinding::sampler)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(mixedAdvancedNames.contains("light_sampler"),
                    "Mixed resource fixture did not plan its advanced sampled image");
            assertTrue(layout.ordinaryDescriptors().stream().noneMatch(value ->
                            value.identity().equals("resource:"
                                    + layout.advancedSamplers().stream()
                                    .filter(binding -> binding.sampler().equals("light_sampler"))
                                    .findFirst().orElseThrow().selectorSlot() + ":1")),
                    "Advanced sampler remained in the ordinary descriptor partition");
            String vertex;
            String fragment;
            try {
                vertex = PackPipelines.bindStorageImages(shadow.convertedVertex(), "shadow", resources, layout,
                        VK_SHADER_STAGE_VERTEX_BIT);
                fragment = PackPipelines.bindStorageImages(shadow.convertedFragment(), "shadow", resources, layout,
                        VK_SHADER_STAGE_FRAGMENT_BIT);
            } catch (PackPipelines.PreparationFailure failure) {
                throw new AssertionError("Mixed resource rewrite rejected live resources: " + failure.reason);
            }
            assertTrue(!vertex.isBlank() && !fragment.isBlank(),
                    "Mixed resource rewrite lost a stage: " + resources.deviations());
            List<PackAdvancedResourcePlan.DescriptorBinding> descriptors =
                    new java.util.ArrayList<>(layout.descriptors());
            try (PackStorageBufferOwner owner = new PackStorageBufferOwner(resources)) {
                assertEquals(null, owner.verifyLayout("shadow", vertex, fragment, descriptors),
                        "Mixed rewritten shader disagrees with descriptor layout: planned=" + layout.descriptors()
                                + " vertex=" + PackAdvancedResourcePlan.shaderBindings(vertex, 1)
                                + " fragment=" + PackAdvancedResourcePlan.shaderBindings(fragment, 16)
                                + " native=" + descriptors);
                assertTrue(owner.layoutVerified("shadow"), "Matching layout was not admitted");
                var light = layout.descriptors().stream().filter(value -> value.symbol().equals("light_sampler"))
                        .findFirst().orElseThrow();
                String samplerCollision = vertex.replace("binding = " + light.binding() + ") uniform sampler3D light_sampler",
                        "binding = " + layout.storageBase() + ") uniform sampler3D light_sampler");
                assertEquals("DESCRIPTOR_LAYOUT_MISMATCH:light_sampler",
                        owner.verifyLayout("shadow", samplerCollision, fragment, descriptors),
                        "Real sampler/SSBO collision was not rejected by sampler name");
                assertEquals("DESCRIPTOR_LAYOUT_MISMATCH:ff_sampler",
                        owner.verifyLayout("shadow", vertex + "\nuniform sampler3D ff_sampler;\n", fragment, descriptors),
                        "Unplanned global sampler sharing a helper parameter name escaped verification");
                var wrongSamplerType = new java.util.ArrayList<>(descriptors);
                wrongSamplerType.replaceAll(value -> value.binding() == light.binding()
                        ? new PackAdvancedResourcePlan.DescriptorBinding(value.symbol(), value.set(),
                                value.binding(), 3, value.stages()) : value);
                assertEquals("DESCRIPTOR_LAYOUT_MISMATCH:light_sampler",
                        owner.verifyLayout("shadow", vertex, fragment, wrongSamplerType),
                        "Storage image descriptor was accepted for a combined sampler");
                var wrongSamplerStages = new java.util.ArrayList<>(descriptors);
                wrongSamplerStages.replaceAll(value -> value.binding() == light.binding()
                        ? new PackAdvancedResourcePlan.DescriptorBinding(value.symbol(), value.set(),
                                value.binding(), value.type(), VK_SHADER_STAGE_FRAGMENT_BIT) : value);
                assertEquals("DESCRIPTOR_LAYOUT_MISMATCH:light_sampler",
                        owner.verifyLayout("shadow", vertex, fragment, wrongSamplerStages),
                        "Fragment-only sampler descriptor was accepted for a vertex sampler");
                var voxel = layout.descriptors().stream().filter(value -> value.symbol().equals("voxel_img"))
                        .findFirst().orElseThrow();
                String broken = vertex.replace("binding = " + voxel.binding() + ") uniform uimage3D voxel_img",
                        "binding = " + layout.storageBase() + ") uniform uimage3D voxel_img");
                assertEquals("DESCRIPTOR_LAYOUT_MISMATCH:voxel_img",
                        owner.verifyLayout("shadow", broken, fragment, descriptors),
                        "SSBO/image collision was not rejected by shader symbol");
                assertTrue(!owner.layoutVerified("shadow") && !owner.storagePathReady("shadow", "shadow")
                                && owner.storagePathStatus("shadow", "shadow").contains("DESCRIPTOR_LAYOUT_MISMATCH:voxel_img"),
                        "Rejected layout remained ready or lost its named reason");
                assertEquals(null, owner.verifyLayout("shadow", vertex, fragment, descriptors),
                        "Corrected layout failed to recover");
                assertTrue(owner.layoutVerified("shadow"), "Corrected layout remains rejected");
            }
        } finally {
            try (var paths = Files.walk(fixture)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void assertNoAbsolutePaths(String value) {
        assertTrue(!value.contains("C:\\") && !value.contains("/Users/"),
                "M8.6b snapshot contains an absolute path");
    }

    private static void verifyStorageParserAndLiveResourceUse() {
        String source = "#define SSBO_QUALIFIER\n"
                + "layout(std430, binding = 0) SSBO_QUALIFIER buffer blockDataBuffer {\n"
                + " uint values[];\n} blockDataBuffer;\n"
                + "#undef SSBO_QUALIFIER\n"
                + "#define SSBO_QUALIFIER readonly\n"
                + "layout(std430, binding = 1) SSBO_QUALIFIER buffer reader {\n"
                + " uint values[];\n};\n";
        List<GlslStorageBufferParser.Block> blocks = GlslStorageBufferParser.scan(source);
        assertTrue(blocks.size() == 2 && blocks.get(0).std430()
                        && blocks.get(0).originalBinding() == 0
                        && blocks.get(0).qualifierMacro().equals("SSBO_QUALIFIER")
                        && blocks.get(1).std430()
                        && blocks.get(1).originalBinding() == 1,
                "M8.6b qualifier-macro storage parsing is not line ordered");
        String rewritten = GlslStorageBufferParser.rewrite(source,
                Map.of(GlslStorageBufferParser.key(blocks.get(0)), 7));
        assertTrue(rewritten.contains("binding = 7")
                        && rewritten.contains("#define SSBO_QUALIFIER\n"),
                "M8.6b token storage rewrite changed the authored declaration");

        GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(
                "uniform sampler3D liveSampler; uniform sampler3D unusedSampler;\n"
                        + "vec4 readValue() { return texture(liveSampler, vec3(0.0)); }\n"
                        + "void main() { vec4 value = readValue(); }\n");
        assertTrue(usage.successful() && usage.liveSamplers().contains("liveSampler")
                        && !usage.liveSamplers().contains("unusedSampler"),
                "M8.6b live resource analysis did not follow the reachable helper");
        GlslResourceUsage.Analysis helperUsage = GlslResourceUsage.analyze(
                "uniform sampler2D helperSampler;\n"
                        + "vec4 unreachable() { return texture2D(helperSampler, vec2(0.0)); }\n"
                        + "void main() { gl_FragColor = vec4(1.0); }\n");
        assertTrue(helperUsage.referencedSamplers().contains("helperSampler")
                        && !helperUsage.liveSamplers().contains("helperSampler"),
                "M8.6b sampler references in unreachable helpers were lost");
        PostTargetPlan.ParseResult targetResult = PostTargetPlan.parse("composite",
                "#version 120\n/* DRAWBUFFERS:0 */\n"
                        + "uniform sampler2D helperSampler;\n"
                        + "vec4 unreachable() { return texture2D(helperSampler, vec2(0.0)); }\n"
                        + "void main() { gl_FragColor = vec4(1.0); }\n");
        UniformRegistry.ProgramInterface deadPlan = UniformRegistry.planPreparedPost(
                "#version 120\n/* DRAWBUFFERS:0 */\n"
                        + "uniform sampler2D helperSampler;\n"
                        + "vec4 unreachable() { return texture2D(helperSampler, vec2(0.0)); }\n"
                        + "void main() { gl_FragColor = vec4(1.0); }\n",
                targetResult.plan());
        String deadConverted = LegacyGlslConverter.convertPostFragment(
                "#version 120\n/* DRAWBUFFERS:0 */\n"
                        + "uniform sampler2D helperSampler;\n"
                        + "vec4 unreachable() { return texture2D(helperSampler, vec2(0.0)); }\n"
                        + "void main() { gl_FragColor = vec4(1.0); }\n",
                null, deadPlan, targetResult.plan(), Map.of());
        assertTrue(deadConverted != null && !deadConverted.contains("unreachable")
                        && !deadConverted.contains("helperSampler")
                        && deadPlan.samplers().isEmpty()
                        && deadPlan.deviations().contains("SAMPLER_DECLARATION_UNUSED:helperSampler")
                        && !deadPlan.deviations().contains("SAMPLER_NOT_MAPPED:helperSampler"),
                "M8.6b dead helper sampler was not removed before conversion");

        String liveHelperSource = "#version 120\n/* DRAWBUFFERS:0 */\n"
                + "uniform sampler2D helperSampler;\n"
                + "vec4 reachable() { return texture2D(helperSampler, vec2(0.0)); }\n"
                + "void main() { gl_FragColor = reachable(); }\n";
        UniformRegistry.ProgramInterface livePlan = UniformRegistry.planPreparedPost(
                liveHelperSource, PostTargetPlan.parse("composite", liveHelperSource).plan());
        assertTrue(livePlan.deviations().contains("SAMPLER_NOT_MAPPED:helperSampler"),
                "M8.6b reachable unmapped sampler did not fail closed");

        String overloadSource = "uniform sampler2D overloadedSampler;\n"
                + "vec4 read(int value) { return texture2D(overloadedSampler, vec2(0.0)); }\n"
                + "vec4 read(float value) { return read(1); }\n"
                + "void main() { gl_FragColor = read(1.0); }\n";
        GlslResourceUsage.Analysis overloadUsage = GlslResourceUsage.analyze(overloadSource);
        assertTrue(overloadUsage.liveSamplers().contains("overloadedSampler")
                        && overloadUsage.unreachableFunctions().isEmpty(),
                "M8.6b overloaded helper reachability was not conservative");

        String recursiveSource = "uniform sampler2D recursiveSampler;\n"
                + "vec4 recur(int value) { if (value == 0) return texture2D(recursiveSampler, vec2(0.0)); return recur(value - 1); }\n"
                + "void main() { gl_FragColor = recur(1); }\n";
        assertTrue(GlslResourceUsage.analyze(recursiveSource).liveSamplers()
                        .contains("recursiveSampler"),
                "M8.6b recursive helper reachability was not retained");

        String macroSource = "float Bayer2(vec2 value) { return value.x; }\n"
                + "#define Bayer4(value) (Bayer2(value * 0.5))\n"
                + "#define Bayer8(value) (Bayer4(value * 0.5))\n"
                + "float BayerCloud2(vec2 value) { return Bayer2(value); }\n"
                + "void main() { float value = Bayer8(vec2(0.0)); }\n";
        GlslResourceUsage.Analysis macroUsage = GlslResourceUsage.analyze(macroSource);
        String macroConverted = GlslTokenRewriter.removeUnreachableFunctions(
                macroSource, macroUsage);
        assertTrue(macroUsage.reachableFunctions().stream()
                        .anyMatch(value -> value.name().equals("Bayer2"))
                        && macroUsage.unreachableFunctions().stream()
                        .anyMatch(value -> value.name().equals("BayerCloud2"))
                        && macroConverted.contains("float Bayer2")
                        && macroConverted.contains("#define Bayer4")
                        && macroConverted.contains("#define Bayer8")
                        && !macroConverted.contains("BayerCloud2"),
                "M8.6b function-like macro dependencies were pruned");

        Set<String> callableUniforms = GlslResourceUsage.referencedValueIdentifiers(
                "uniform sampler2D textureAtlas; void main() {"
                        + "ivec2 size = textureSize(textureAtlas, 0); }",
                Set.of("textureSize", "textureAtlas"));
        assertTrue(!callableUniforms.contains("textureSize")
                        && callableUniforms.contains("textureAtlas"),
                "M8.6b callable builtin was inferred as a uniform value");
        assertTrue(GlslResourceUsage.referencedValueIdentifiers(
                        "#define SIZE_OF \\" + "\n"
                                + "textureSize(textureAtlas, 0)\nvoid main() {}",
                        Set.of("textureSize", "textureAtlas")).isEmpty(),
                "M8.6b continued preprocessor text was treated as executable uniform use");
        UniformRegistry.ProgramInterface callablePlan = UniformRegistry.planPreparedPost(
                "#version 120\nuniform sampler2D textureAtlas;\n"
                        + "void main() { ivec2 size = textureSize(textureAtlas, 0); "
                        + "gl_FragColor = vec4(float(size.x)); }",
                PostTargetPlan.parse("composite", "#version 120\nDRAWBUFFERS:0").plan());
        assertTrue(callablePlan.deviations().stream().noneMatch(value ->
                        value.equals("UNIFORM_IMPLICIT_DECLARATION:textureSize")),
                "M8.6b textureSize was added to the generated UBO");

        String fogSource = "#version 120\n"
                + "void main() { float value = (gl_Fog.start + gl_Fog.end) * gl_Fog.scale;"
                + " gl_FragColor = vec4(value); }\n";
        LegacyShaderNormalizer.Result normalizedFog = LegacyShaderNormalizer.normalize(fogSource, false);
        assertTrue(normalizedFog.successful()
                        && normalizedFog.source().contains("fogStart")
                        && normalizedFog.source().contains("fogEnd")
                        && normalizedFog.source().contains("1.0 / (fogEnd - fogStart)")
                        && normalizedFog.source().contains("uniform float fogStart;")
                        && normalizedFog.source().contains("uniform float fogEnd;"),
                "M8.6b legacy gl_Fog fields were not normalized");
        UniformRegistry.ProgramInterface fogPlan = UniformRegistry.planPreparedPost(
                normalizedFog.source(),
                PostTargetPlan.parse("composite", fogSource).plan());
        assertTrue(fogPlan.uniforms().stream().anyMatch(value -> value.name().equals("fogStart"))
                        && fogPlan.uniforms().stream().anyMatch(value -> value.name().equals("fogEnd"))
                        && fogPlan.executable(),
                "M8.6b normalized fog values did not enter the live interface");
        LegacyShaderNormalizer.Result unsupportedFog = LegacyShaderNormalizer.normalize(
                "void main() { gl_FragColor = vec4(gl_Fog.density); }", false);
        assertTrue(!unsupportedFog.successful()
                        && unsupportedFog.deviations().contains(
                        "LEGACY_FOG_FIELD_UNSUPPORTED:density"),
                "M8.6b unsupported gl_Fog field did not fail closed");

        String shadowedSource = "uniform sampler2D shadowedSampler;\n"
                + "vec4 local() { float shadowedSampler = 1.0; return vec4(shadowedSampler); }\n"
                + "void main() { gl_FragColor = local(); }\n";
        assertTrue(!GlslResourceUsage.analyze(shadowedSource).liveSamplers()
                        .contains("shadowedSampler"),
                "M8.6b local resource shadowing was misclassified");

        String snippetSource = "uniform sampler2D snippetSampler;\n"
                + "vec4 snippet() { return texture2D(snippetSampler, vec2(0.0)); }\n";
        GlslResourceUsage.Analysis snippetUsage = GlslResourceUsage.analyze(snippetSource);
        assertTrue(snippetUsage.liveSamplers().contains("snippetSampler")
                        && snippetUsage.unreachableFunctions().isEmpty(),
                "M8.6b no-main source was pruned instead of retained conservatively");
        UniformRegistry.UniformDescriptor blindness =
                UniformRegistry.descriptor("blindness", "float");
        assertTrue(blindness != null && blindness.sourceKey().equals("blindness"),
                "M8.6b blindness uniform alias is not in the live catalog");
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
