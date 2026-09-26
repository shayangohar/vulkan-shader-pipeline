package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Real-pack entity and block qualification: the locked Complementary and
 * BSL programs must plan executable entity/block pipelines with exact
 * active target routes, build complete descriptor manifests, and compile
 * converted sources through shaderc. Requires the locked pack paths plus
 * the complete CHIMERA instance option set (multicolored-blocklight,
 * reflection, material-format, RP mode, advanced materials, reflect
 * quality): the manifest gate must see the same branch conditions as
 * runtime, or samplers such as MCBL lighttex and noisetex silently drop.
 */
public final class M87EntityRealPackHarness {
    private M87EntityRealPackHarness() {}

    private record Expectation(String pack, String program, List<Integer> targets,
            UniformRegistry.Stage stage) {}

    public static void main(String[] args) throws Exception {
        String complementary = System.getProperty("chimera.m87.entity.complementary");
        String bsl = System.getProperty("chimera.m87.entity.bsl");
        assertTrue(complementary != null && !complementary.isBlank(),
                "chimera.m87.entity.complementary pack path not provided");
        assertTrue(bsl != null && !bsl.isBlank(), "chimera.m87.entity.bsl pack path not provided");
        verifyPack(complementary, List.of(
                new Expectation("complementary", "gbuffers_entities", List.of(0, 3, 6, 4),
                        UniformRegistry.Stage.ENTITY),
                new Expectation("complementary", "gbuffers_block", List.of(0, 3, 6, 4),
                        UniformRegistry.Stage.BLOCK)));
        verifyPack(bsl, List.of(
                new Expectation("bsl", "gbuffers_entities", List.of(0, 1, 3, 6, 7),
                        UniformRegistry.Stage.ENTITY),
                new Expectation("bsl", "gbuffers_block", List.of(0, 3, 6, 7),
                        UniformRegistry.Stage.BLOCK)));
        System.out.println("[chimera] m8.7 entity real-pack conformance: PASS");
    }

    private static void verifyPack(String pack, List<Expectation> expectations) throws Exception {
        PackProbe.Analysis analysis = PackProbe.analyze(Path.of(pack));
        verifyShadowSamplerTypes(analysis, expectations.get(0).pack());
        verifyShadowClipRange(analysis, expectations.get(0).pack(), expectations);
        if (expectations.stream().anyMatch(value -> value.pack().equals("bsl"))) {
            verifyBslTemporalTargetPlan(analysis);
        }
        verifyAuthoredEmptyClouds(analysis, expectations.get(0).pack());
        verifyStageTextures(analysis, expectations.get(0).pack());
        for (Expectation expectation : expectations) {
            PackProgramPlan plan = analysis.plan().program(expectation.program());
            assertTrue(plan != null, expectation.pack + " " + expectation.program() + " has no plan");
            assertTrue(plan.executable(), expectation.pack + " " + expectation.program()
                    + " not executable: " + plan.deviations());
            for (String deviation : plan.deviations()) {
                assertTrue(!deviation.startsWith("SAMPLER_SLOT_CONFLICT:")
                                && !deviation.contains("VERTEX_BRIDGE_UNSUPPORTED")
                                && !deviation.contains("VERTEX_CONVERSION_FAILED:"),
                        expectation.pack + " " + expectation.program() + " blocked: " + deviation);
            }
            assertTrue(plan.geometryOutputPlan() != null
                            && plan.geometryOutputPlan().targetSlots().equals(expectation.targets()),
                    expectation.pack + " " + expectation.program() + " targets "
                            + (plan.geometryOutputPlan() == null ? "null"
                            : plan.geometryOutputPlan().targetSlots())
                            + ", expected " + expectation.targets());
            assertTrue(plan.convertedVertex() != null && plan.convertedFragment() != null,
                    expectation.pack + " " + expectation.program() + " missing converted sources");
            System.out.println("[chimera] m8.7 real-pack converted " + expectation.pack + " "
                    + expectation.program() + ": vertex=" + plan.convertedVertex().length()
                    + " chars, fragment=" + plan.convertedFragment().length() + " chars");
            compileStage(plan.convertedVertex(), true,
                    expectation.pack + " " + expectation.program() + " vertex");
            compileStage(plan.convertedFragment(), false,
                    expectation.pack + " " + expectation.program() + " fragment");
            verifyManifest(analysis.plan().advancedResources(), expectation, plan);        }
    }

    /**
     * Both locked packs draw their own clouds at the configured defaults
     * (BSL CLOUDS=2, Complementary CLOUD_STYLE_DEFINE != 50); their
     * gbuffers_clouds fragment discards every vanilla cloud fragment. The
     * plan must record that fact so the runtime skips the host clouds
     * instead of drawing them over the pack's own.
     */
    private static void verifyAuthoredEmptyClouds(PackProbe.Analysis analysis, String pack) {
        PackProgramPlan clouds = analysis.plan().program("gbuffers_clouds");
        assertTrue(clouds != null, pack + " gbuffers_clouds has no plan");
        assertTrue(clouds.cloudDrawsNothing() && !clouds.executable(),
                pack + " gbuffers_clouds is not planned as authored no-output: " + clouds.deviations());
        assertTrue(!clouds.deviations().contains("CLOUD_VERTEX_BRIDGE_UNSUPPORTED"),
                pack + " gbuffers_clouds still reports a vertex bridge failure");
    }

    /**
     * Iris texture stages cover numbered programs. Complementary's cloud
     * noise (texture.deferred.colortex3) must reach deferred1, which draws
     * its clouds; BSL's lens dirt (texture.composite.colortex7) must reach
     * every compositeN that samples colortex7 or its gaux4 alias.
     */
    private static void verifyStageTextures(PackProbe.Analysis analysis, String pack) {
        PackResourcePlan resources = analysis.plan().resources();
        String sampler = pack.equals("complementary") ? "colortex3" : "colortex7";
        String source = pack.equals("complementary") ? "lib/textures/cloud-water.png" : "tex/dirt.png";
        int checked = 0;
        for (var entry : resources.bindings().entrySet()) {
            String stage = pack.equals("complementary") ? "deferred" : "composite";
            if (!stage.equals(PackResourcePlan.textureStage(entry.getKey()))) continue;
            for (PackResourceBinding binding : entry.getValue()) {
                if (!PackResourcePlan.canonicalResource(binding.sampler()).equals(sampler)) continue;
                assertTrue(binding.status() == PackResourceStatus.PACK_FILE && binding.source().equals(source),
                        pack + " " + entry.getKey() + " " + sampler + " did not take the stage texture: "
                                + binding.status() + " " + binding.source());
                checked++;
            }
        }
        // BSL samples its dirt only when lens dirt is enabled (off in the locked
        // options); Complementary deferred1 always draws the clouds.
        assertTrue(!pack.equals("complementary") || checked > 0,
                "complementary deferred1 no longer samples colortex3");
    }

    /**
     * Descriptor-contract gate (DOC-348 repair 2): builds source
     * projection, ordinary descriptors, advanced descriptors, and the
     * image manifest through the runtime preparation seam, then proves
     * the bijection against the native layout inventory. Fails on
     * IMAGE_RESOLVER_MISSING, IMAGE_MANIFEST_MISMATCH, selector/resource
     * drift, or an unservable manifest kind. Compilation and routes alone
     * cannot prove pipeline eligibility.
     */
    private static void verifyManifest(PackAdvancedResourcePlan advanced, Expectation expectation,
            PackProgramPlan plan) {
        String what = expectation.pack + " " + expectation.program();
        PackPipelines.PreparedPipeline prepared;
        try {
            prepared = PackPipelines.prepare(plan, advanced, expectation.stage(),
                    plan.convertedVertex());
        } catch (PackPipelines.PreparationFailure failure) {
            throw new AssertionError(what + " descriptor preparation failed: "
                    + failure.phase + ":" + failure.reason, failure);
        }
        ProgramImageBindingManifest manifest = prepared.imageBindings();
        assertTrue(manifest != null && !manifest.entries().isEmpty(),
                what + " has no manifest entries");
        // Builder inventory, not the layout proxy: this is the exact
        // descriptor-contract layer createNative verifies at runtime
        // (layout mismatch plus manifest bijection). Uniform suppliers
        // never run headless; the dummy is never invoked.
        try {
            net.vulkanmod.vulkan.shader.Pipeline.Builder builder =
                    PackPipelines.prepareBuilder(prepared, plan.name(), advanced,
                            net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY,
                            info -> () -> null);
            assertTransformBlocks(what, builder);
        } catch (PackPipelines.PreparationFailure failure) {
            throw new AssertionError(what + " builder descriptor mismatch: "
                    + failure.phase + ":" + failure.reason + "\nmanifest=" + manifest.entries()
                    + "\ninventory=" + prepared.layout().descriptors(), failure);
        }
        for (PackAdvancedResourcePlan.DescriptorBinding descriptor
                : prepared.ordinary().descriptors()) {
            if (descriptor.type() != 1 && descriptor.type() != 3) continue;
            int slot = selectorSlot(descriptor.identity(), what, descriptor.symbol());
            List<ProgramImageBindingManifest.Entry> matches = manifest.entries().stream()
                    .filter(entry -> entry.set() == descriptor.set()
                            && entry.binding() == descriptor.binding())
                    .toList();
            assertTrue(matches.size() == 1, what + " ordinary descriptor "
                    + descriptor.symbol() + " has " + matches.size() + " manifest resources");
            assertTrue(matches.get(0).slot() == slot, what + " ordinary descriptor "
                    + descriptor.symbol() + " manifest slot " + matches.get(0).slot()
                    + ", expected " + slot);
        }
        assertCanonicalSamplers(what, plan, expectation, manifest);
    }

    /**
     * DOC-375 item 1 on the real packs: the shadow vertex carries the one-time
     * clip-range wrapper and still compiles, while the world-family vertices
     * keep the authored clip space because the host world raster owns their
     * depth convention.
     */
    private static void verifyShadowClipRange(PackProbe.Analysis analysis, String pack,
            List<Expectation> expectations) {
        PackProgramPlan shadow = analysis.plan().program("shadow");
        if (shadow != null && shadow.convertedVertex() != null) {
            PackPipelines.PreparedPipeline prepared;
            try {
                prepared = PackPipelines.prepare(shadow, analysis.plan().advancedResources(),
                        UniformRegistry.Stage.SHADOW, shadow.convertedVertex());
            } catch (PackPipelines.PreparationFailure failure) {
                throw new AssertionError(pack + " shadow preparation failed: "
                        + failure.phase + ":" + failure.reason, failure);
            }
            String converted = prepared.vertex();
            assertTrue(converted.contains("chimeraAuthoredShadowMain();"),
                    pack + " shadow vertex has no clip-range wrapper");
            assertTrue(converted.contains(
                            "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);"),
                    pack + " shadow vertex has no clip-range remap");
            assertTrue(!converted.contains("0.2 * gl_Position.z"),
                    pack + " converter duplicated the pack-authored shadow depth compression");
            assertTrue(converted.indexOf("chimeraAuthoredShadowMain();")
                            < converted.indexOf("gl_Position.z = 0.5 *"),
                    pack + " clip-range remap does not follow the authored entry point");
            compileStage(converted, true, pack + " shadow vertex");
            System.out.println("[chimera] m8.7 " + pack + " shadow clip-range wrapper verified");
        }
        for (Expectation expectation : expectations) {
            PackProgramPlan plan = analysis.plan().program(expectation.program());
            if (plan == null || plan.convertedVertex() == null) continue;
            assertTrue(!plan.convertedVertex().contains("chimeraAuthoredShadowMain"),
                    pack + " " + expectation.program() + " world vertex picked up the shadow wrapper");
        }
    }

    /**
     * DOC-375 sampler identity: the descriptor sampler follows the declared
     * sampler type, because a {@code sampler2DShadow} lookup is a depth
     * comparison and a plain {@code sampler2D} lookup reads the value. Report
     * how each admitted program reads the shadow textures and reject any type
     * the shadow owner cannot serve.
     */
    private static void verifyShadowSamplerTypes(PackProbe.Analysis analysis, String pack) {
        for (PackProgramPlan plan : analysis.plan().programs()) {
            if (!plan.executable() || plan.interfacePlan() == null) continue;
            List<String> declared = new java.util.ArrayList<>();
            for (var sampler : plan.interfacePlan().samplers()) {
                if (!sampler.name().startsWith("shadowtex")) continue;
                declared.add(sampler.name() + "=" + sampler.glslType());
                assertTrue(sampler.glslType().equals("sampler2D")
                                || sampler.glslType().equals("sampler2DShadow"),
                        pack + " " + plan.name() + " " + sampler.name()
                                + " declares unsupported type " + sampler.glslType());
            }
            if (!declared.isEmpty()) {
                System.out.println("[chimera] m8.7 " + pack + " shadow samplers "
                        + plan.name() + ": " + String.join(", ", declared));
            }
        }
    }

    /**
     * DOC-365 descriptor identity on the real pack program: the built contract's
     * transform blocks must answer to the semantic names the bridge looks up.
     * VulkanMod names config-declared blocks {@code "UBO: <binding>"} and
     * {@code Pipeline.getUBO(String)} compares names exactly, so a numeric block
     * would make every host per-draw transform binding miss.
     */
    private static void assertTransformBlocks(String what,
            net.vulkanmod.vulkan.shader.Pipeline.Builder builder) {
        for (int binding : List.of(UniformRegistry.DYNAMIC_TRANSFORMS_BINDING,
                UniformRegistry.PROJECTION_BINDING)) {
            String expected = binding == UniformRegistry.DYNAMIC_TRANSFORMS_BINDING
                    ? UniformRegistry.DYNAMIC_TRANSFORMS_UBO : UniformRegistry.PROJECTION_UBO;
            boolean present = builder.getUBOs().stream()
                    .anyMatch(block -> block.binding == binding && block.name.equals(expected));
            assertTrue(present, what + " has no " + expected + " block at binding " + binding
                    + ": " + builder.getUBOs().stream()
                    .map(block -> block.name + "@" + block.binding).toList());
        }
    }

    /**
     * Canonical selector assertions (DOC-348 repair 1): a declared
     * noisetex must ride fixed selector 7 as a pack texture, and declared
     * MCBL light volumes must survive as advanced sampled images.
     */
    private static void assertCanonicalSamplers(String what, PackProgramPlan plan,
            Expectation expectation, ProgramImageBindingManifest manifest) {
        var samplers = plan.interfacePlan().effective(expectation.stage()).samplers();
        boolean declaresNoise = samplers.stream().anyMatch(sampler -> sampler.name().equals("noisetex"));
        // DOC-348 names BSL block explicitly: its manifest must carry the
        // canonical slot-7 pack-texture noise entry, not a bare declaration.
        boolean requireNoise = declaresNoise
                || (expectation.pack().equals("bsl") && expectation.program().equals("gbuffers_block"));
        if (requireNoise) {
            boolean noiseOk = manifest.entries().stream().anyMatch(entry -> entry.slot() == 7
                    && entry.kind() == ProgramImageBindingManifest.Kind.PACK_TEXTURE
                    && entry.resourceKey().equals("noisetex"));
            assertTrue(noiseOk, what + " declares noisetex but the manifest has no "
                    + "slot-7 PACK_TEXTURE noisetex entry: " + manifest.entries());
        }
        for (String volume : List.of("lighttex0", "lighttex1")) {
            boolean declaresVolume = samplers.stream()
                    .anyMatch(sampler -> sampler.name().equals(volume));
            if (declaresVolume) {
                boolean volumeOk = manifest.entries().stream().anyMatch(entry -> entry.kind()
                                == ProgramImageBindingManifest.Kind.ADVANCED_IMAGE
                        && entry.sourceSymbol().equals(volume));
                assertTrue(volumeOk, what + " declares " + volume + " but the manifest has no "
                        + "ADVANCED_IMAGE entry for it: " + manifest.entries());
            }
        }
    }

    /** Pins BSL's first-use temporal target contract against the real source pack. */
    private static void verifyBslTemporalTargetPlan(PackProbe.Analysis analysis) {
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), analysis.plan().resources(),
                2560, 1440, 8, 16384);
        TargetStep producer = graph.steps().stream()
                .filter(step -> step.reads(2) || step.writes(2))
                .findFirst().orElseThrow(() -> new AssertionError("BSL colortex2 has no graph access"));
        assertTrue(producer.programName().equals("composite3")
                        && producer.reads(2) && producer.writes(2),
                "BSL colortex2 first access must be composite3 feedback: " + producer);
        assertTrue(graph.target(2) != null && graph.target(2).persistent()
                        && graph.target(2).requiresHistory(),
                "BSL colortex2 must retain persistent ping-pong history");
        assertTrue(graph.requiresInitialSeed(2),
                "BSL colortex2 must be initialized before composite3's first feedback read");
        System.out.println("[chimera] BSL temporal graph: firstAccess=" + producer.programName()
                + ", reads=" + producer.readTargets() + ", writes=" + producer.outputTargets()
                + ", initialSeed=" + graph.requiresInitialSeed(2)
                + ", fingerprint=" + graph.fingerprint());
    }

    private static int selectorSlot(String identity, String what, String symbol) {
        String prefix = "resource:";
        int end = identity.indexOf(':', prefix.length());
        assertTrue(identity.startsWith(prefix) && end > prefix.length(),
                what + " descriptor " + symbol + " has no selector identity: " + identity);
        try {
            return Integer.parseInt(identity.substring(prefix.length(), end));
        } catch (NumberFormatException failure) {
            throw new AssertionError(what + " descriptor " + symbol
                    + " has an unparsable selector identity: " + identity, failure);
        }
    }

    private static void compileStage(String source, boolean vertex, String what) {
        long compiler = org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize();
        // Heap buffers plus the native entry point directly. The CharSequence
        // overload stack-allocates UTF-8 and overflows LWJGL's thread-local
        // MemoryStack on large MCBL sources (DOC-348 gate), while the
        // ByteBuffer overload sizes by remaining() and feeds shaderc the
        // terminator as trailing garbage. Explicit sizes excluding the
        // terminator reproduce the CharSequence semantics without the stack.
        java.nio.ByteBuffer text = org.lwjgl.system.MemoryUtil.memUTF8(source);
        java.nio.ByteBuffer name = org.lwjgl.system.MemoryUtil.memUTF8(
                vertex ? "entity.vsh" : "entity.fsh");
        java.nio.ByteBuffer entry = org.lwjgl.system.MemoryUtil.memASCII("main");
        long result = 0;
        try {
            result = org.lwjgl.util.shaderc.Shaderc.nshaderc_compile_into_spv(compiler,
                    org.lwjgl.system.MemoryUtil.memAddress(text), text.remaining() - 1,
                    vertex ? org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader
                            : org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader,
                    org.lwjgl.system.MemoryUtil.memAddress(name),
                    org.lwjgl.system.MemoryUtil.memAddress(entry), 0);
            assertTrue(result != 0
                            && org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status(result)
                            == org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success,
                    what + " did not compile: " + (result == 0 ? "no result"
                            : org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message(result))
                            + " [source dumped to " + dumpSource(source, what) + "]");
        } finally {
            if (result != 0) org.lwjgl.util.shaderc.Shaderc.shaderc_result_release(result);
            org.lwjgl.system.MemoryUtil.memFree(entry);
            org.lwjgl.system.MemoryUtil.memFree(name);
            org.lwjgl.system.MemoryUtil.memFree(text);
            org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static String dumpSource(String source, String what) {
        try {
            Path dir = Path.of("build", "m87-fail");
            java.nio.file.Files.createDirectories(dir);
            String name = what.replaceAll("[^a-zA-Z0-9]+", "_") + ".glsl";
            Path file = dir.resolve(name);
            java.nio.file.Files.writeString(file, source);
            return file.toString();
        } catch (Exception failure) {
            return "dump failed: " + failure.getMessage();
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
