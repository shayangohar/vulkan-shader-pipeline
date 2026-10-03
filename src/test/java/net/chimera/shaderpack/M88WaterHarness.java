package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * M8.8 water gates (TASK-432 slices A and B).
 *
 * <p>Slice A: gbuffers_water plans on the terrain contract. Its uniforms come
 * from the live catalog, it samples the opaque depth and the pack colour
 * targets Iris gives it, it carries the pack alpha test, and it is rejected
 * when it would sample a target it also writes.</p>
 *
 * <p>Slice B: deferred passes run before translucents, where depthtex0 is the
 * opaque depth, and a world program's depth reads reach the depth graph.</p>
 *
 * <p>With the locked pack paths set, both packs' gbuffers_water must plan
 * executable, compile, and build a complete descriptor manifest.</p>
 */
public final class M88WaterHarness {
    private M88WaterHarness() {}

    private static final String WATER_FRAGMENT = """
            #version 120
            uniform sampler2D texture;
            uniform sampler2D lightmap;
            uniform sampler2D shadowtex1;
            uniform sampler2D noisetex;
            uniform sampler2D depthtex1;
            uniform sampler2D gaux2;
            uniform mat4 gbufferModelView;
            uniform vec3 cameraPosition;
            uniform vec3 fogColor;
            uniform float viewWidth;
            varying vec2 texcoord;
            void main() {
                vec2 screen = gl_FragCoord.xy / viewWidth;
                float opaque = texture2D(depthtex1, screen).r;
                float cloud = texture2D(gaux2, screen).a;
                float shadow = texture2D(shadowtex1, texcoord).r;
                float noise = texture2D(noisetex, texcoord).r;
                vec4 atlas = texture2D(texture, texcoord) * texture2D(lightmap, texcoord);
                vec3 tint = (gbufferModelView * vec4(cameraPosition, 1.0)).xyz * 0.0 + fogColor;
                /* DRAWBUFFERS:03 */
                gl_FragData[0] = vec4(atlas.rgb * tint, atlas.a * opaque * cloud * shadow * noise);
                gl_FragData[1] = vec4(1.0);
            }
            """;

    public static void main(String[] args) throws Exception {
        verifyLiveInterface();
        verifyRejectedInputs();
        verifyTargetFeedback();
        verifyDeferredSchedule();
        verifyWorldDepthGraph();
        verifyDepthSamplingContract();
        verifyBlendDirectives();
        verifyOptionFile();
        verifyTextureMcmeta();
        String complementary = System.getProperty("chimera.m88.water.complementary");
        String bsl = System.getProperty("chimera.m88.water.bsl");
        if (complementary != null && !complementary.isBlank()) {
            verifyRealPack(complementary, "complementary");
        }
        if (bsl != null && !bsl.isBlank()) {
            verifyRealPack(bsl, "bsl");
        }
        System.out.println("[chimera] m8.8 water conformance: PASS");
    }

    /** Structural check for the shared Vulkan builder; runtime captures verify its sampler. */
    private static void verifyDepthSamplingContract() throws java.io.IOException {
        String source = java.nio.file.Files.readString(Path.of(
                "src/main/java/net/chimera/render/PackDepthTargets.java"));
        assertTrue(source.contains(".setLinearFiltering(false)")
                        && !source.contains(".setLinearFiltering(true)")
                        && source.contains(".setClamp(true)"),
                "depthtex0/1/2 snapshots must use Iris nearest/clamp sampling");
    }

    /** The water stage serves the terrain uniforms and its translucent inputs. */
    private static void verifyLiveInterface() {
        UniformRegistry.ProgramInterface water = UniformRegistry.plan(
                WATER_FRAGMENT, UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(water.executable(), "water interface is not executable: " + water.deviations());
        assertTrue(water.deviations().contains("LIVE_UNIFORM_BRIDGE"),
                "water uniforms do not use the live bridge: " + water.deviations());
        assertTrue(water.deviations().stream().noneMatch(value -> value.startsWith("TRANSLUCENT_FIXED")),
                "water still reports the retired fixed uniform bridge: " + water.deviations());
        Map<String, Integer> expected = Map.of(
                "texture", 0, "lightmap", 2, "shadowtex1", SelectorNamespace.SHADOW_TEX1_SLOT, "noisetex", 7,
                "depthtex1", 12, "gaux2", 9);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            Integer slot = water.samplers().stream()
                    .filter(sampler -> sampler.name().equals(entry.getKey()))
                    .map(UniformRegistry.SamplerBinding::slot).findFirst().orElse(null);
            assertTrue(entry.getValue().equals(slot),
                    "water " + entry.getKey() + " slot " + slot + ", expected " + entry.getValue());
        }
        List<String> uniforms = water.uniforms().stream().map(UniformRegistry.UniformDeclaration::name).toList();
        assertTrue(uniforms.containsAll(List.of("gbufferModelView", "cameraPosition", "fogColor", "viewWidth")),
                "water live uniforms are missing: " + uniforms);
    }

    /** Inputs a translucent draw cannot read stay fail-closed with named reasons. */
    private static void verifyRejectedInputs() {
        UniformRegistry.ProgramInterface current = UniformRegistry.plan(
                "uniform sampler2D colortex0; void main() { gl_FragColor = texture2D(colortex0, vec2(0.0)); }",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!current.executable()
                        && current.deviations().contains("TRANSLUCENT_SAMPLER_UNSUPPORTED:colortex0"),
                "water accepted colortex0, which aliases a host selector: " + current.deviations());
        UniformRegistry.ProgramInterface depth = UniformRegistry.plan(
                "uniform sampler2D depthtex0; void main() { gl_FragColor = texture2D(depthtex0, vec2(0.0)); }",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!depth.executable() && depth.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "water accepted depthtex0, the depth it is writing: " + depth.deviations());
    }

    /** A water program that samples a target it also writes must not execute. */
    private static void verifyTargetFeedback() {
        String fragment = """
                #version 120
                uniform sampler2D texture;
                uniform sampler2D gaux2;
                varying vec2 texcoord;
                void main() {
                    /* DRAWBUFFERS:05 */
                    gl_FragData[0] = texture2D(texture, texcoord);
                    gl_FragData[1] = texture2D(gaux2, texcoord);
                }
                """;
        PackProgramPlan plan = PackPlanBuilder.build(new PackProgram("gbuffers_water", fragment, null,
                terrainVertex(), null), null);
        assertTrue(!plan.executable() && plan.deviations().contains("TRANSLUCENT_TARGET_FEEDBACK:colortex5"),
                "water sampling its own output target was admitted: " + plan.deviations());
        PackProgramPlan ok = PackPlanBuilder.build(new PackProgram("gbuffers_water", WATER_FRAGMENT, null,
                terrainVertex(), null), null);
        assertTrue(ok.executable(), "reference water program is not executable: " + ok.deviations());
        assertTrue(ok.convertedVertex() != null && ok.convertedFragment() != null,
                "reference water program has no converted sources");
        assertTrue(ok.convertedFragment().contains("uniform ChimeraTerrainPackUniforms"),
                "water fragment does not use the terrain pack uniform block");
    }

    /**
     * Iris runs deferred passes between opaque and translucent geometry. A
     * deferred pass that reads depthtex0 still belongs there; only the
     * pre-hand depth has no seam before translucents.
     */
    private static void verifyDeferredSchedule() {
        PackProgramPlan opaqueDepth = post("deferred1", "depthtex0", 0);
        PackProgramPlan preHand = post("deferred2", "depthtex2", 0);
        PackProgramPlan composite = post("composite", "depthtex0", 0);
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(
                List.of(opaqueDepth, preHand, composite), null);
        assertTrue(schedule.postStage("deferred1").window() == PackFrameSchedulePlan.PostWindow.EARLY,
                "deferred1 reading depthtex0 is not early: " + schedule.snapshot());
        assertTrue(schedule.earlyReadsDepthtex0(), "schedule does not request the early depthtex0 capture");
        assertTrue(schedule.deviations().contains("SCHEDULE_DEPTH_TEX0_OPAQUE_AT_DEFERRED:deferred1"),
                "early depthtex0 is not reported: " + schedule.deviations());
        assertTrue(schedule.postStage("deferred2").window() == PackFrameSchedulePlan.PostWindow.LATE
                        && schedule.deviations().contains("SCHEDULE_POST_AFTER_DEPTH:deferred2"),
                "deferred2 reading depthtex2 must wait for the pre-hand seam: " + schedule.snapshot());
        assertTrue(schedule.postStage("composite").window() == PackFrameSchedulePlan.PostWindow.LATE,
                "composite moved out of the late window: " + schedule.snapshot());
        PackFrameSchedulePlan noDepth = PackFrameSchedulePlan.build(
                List.of(post("deferred1", "colortex4", 0)), null);
        assertTrue(!noDepth.earlyReadsDepthtex0(), "an early pass without depthtex0 requested a capture");
    }

    /** The depth graph serves a world program's depth reads, not only post reads. */
    private static void verifyWorldDepthGraph() {
        PackProgramPlan water = PackPlanBuilder.build(new PackProgram("gbuffers_water", WATER_FRAGMENT, null,
                terrainVertex(), null), null);
        PackProgramPlan composite = post("composite", "colortex0", 0);
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(List.of(water, composite), null,
                PackResourcePlan.empty(), 1920, 1080, 8, 16384);
        assertTrue(graph.depth().depthtex1(), "water depthtex1 read did not reach the depth graph");
        assertTrue(graph.target(5) != null, "water gaux2 read did not allocate colortex5");
        assertTrue(graph.target(3) != null, "water output colortex3 was not allocated");
    }

    private static void verifyRealPack(String pack, String name) throws Exception {
        PackProbe.Analysis analysis = PackProbe.analyze(Path.of(pack));
        PackProgramPlan water = analysis.plan().program("gbuffers_water");
        assertTrue(water != null, name + " gbuffers_water has no plan");
        assertTrue(water.executable(), name + " gbuffers_water is not executable: " + water.deviations());
        for (String deviation : water.deviations()) {
            assertTrue(!deviation.startsWith("UNIFORM_NAME_UNSUPPORTED:")
                            && !deviation.startsWith("UNIFORM_TYPE_UNSUPPORTED:")
                            && !deviation.startsWith("TRANSLUCENT_SAMPLER_UNSUPPORTED:")
                            && !deviation.contains("VERTEX_BRIDGE_UNSUPPORTED")
                            && !deviation.startsWith("ALPHA_TEST_PLANNED_NOT_INSTALLED:"),
                    name + " gbuffers_water blocked: " + deviation);
        }
        assertTrue(water.deviations().stream().anyMatch(value -> value.startsWith("ALPHA_TEST_APPLIED:gbuffers_water:")),
                name + " gbuffers_water does not carry the pack alpha test: " + water.deviations());
        M87EntityRealPackHarness.compileStage(water.convertedVertex(), true, name + " gbuffers_water vertex");
        M87EntityRealPackHarness.compileStage(water.convertedFragment(), false, name + " gbuffers_water fragment");
        PackPipelines.PreparedPipeline prepared;
        try {
            prepared = PackPipelines.prepare(water, analysis.plan().advancedResources(),
                    UniformRegistry.Stage.TRANSLUCENT, water.convertedVertex());
        } catch (PackPipelines.PreparationFailure failure) {
            throw new AssertionError(name + " gbuffers_water descriptor preparation failed: "
                    + failure.phase + ":" + failure.reason, failure);
        }
        List<ProgramImageBindingManifest.Entry> entries = prepared.imageBindings().entries();
        assertTrue(entries.stream().anyMatch(entry -> entry.kind() == ProgramImageBindingManifest.Kind.DEPTH_TARGET
                        && entry.resourceKey().equals("depthtex1")),
                name + " gbuffers_water manifest has no opaque depth: " + entries);
        assertTrue(entries.stream().anyMatch(entry -> entry.kind() == ProgramImageBindingManifest.Kind.COLOR_TARGET),
                name + " gbuffers_water manifest samples no pack colour target: " + entries);
        verifyShadowDepthSplit(analysis, name);
        verifyRealPackBlend(water, name);
        verifyWaterMaterial(pack, analysis, name);
        PackProgramPlan shadow = analysis.plan().program("shadow");
        assertTrue(shadow != null && shadow.executable() && shadow.convertedFragment()
                        .contains(UniformRegistry.ENTITY_ALPHA_REFERENCE),
                name + " shadow program lost its per-layer alpha test");
        PackTargetGraphPlan graph = PackTargetGraphPlan.build(analysis.plan().programs(), analysis.config(),
                analysis.plan().resources(), 2560, 1440, 8, 16384);
        assertTrue(graph.depth().depthtex1(), name + " depth graph omits the water depthtex1 read");
        PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(analysis.plan().programs(), graph);
        for (PackFrameSchedulePlan.PostStage stage : schedule.postStages()) {
            if (!stage.name().startsWith("deferred")) continue;
            assertTrue(stage.window() == PackFrameSchedulePlan.PostWindow.EARLY,
                    name + " " + stage.name() + " does not run before translucents: " + schedule.snapshot());
        }
        System.out.println("[chimera] m8.8 " + name + " gbuffers_water: targets="
                + water.geometryOutputPlan().targetSlots() + " manifest=" + entries.size()
                + " early=" + schedule.stages(PackFrameSchedulePlan.PostWindow.EARLY).stream()
                .map(PackFrameSchedulePlan.PostStage::name).toList());
    }

    /**
     * Pack textures sample as their .mcmeta says (BSL's noise.png: blur=true).
     * BSL differences neighbouring noise texels for water normals, so nearest
     * sampling made the surface blocky.
     */
    private static void verifyTextureMcmeta() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("m88-mcmeta");
        Path noise = dir.resolve("noise.png");
        java.nio.file.Files.writeString(noise, "");
        assertTrue(PackResourcePlan.mcmetaFilter(noise, "nearest").equals("nearest")
                        && PackResourcePlan.mcmetaWrap(noise, "repeat").equals("repeat"),
                "a texture without .mcmeta lost its default sampling");
        java.nio.file.Files.writeString(dir.resolve("noise.png.mcmeta"),
                "{\"texture\": {\"blur\": true, \"clamp\": true}}");
        assertTrue(PackResourcePlan.mcmetaFilter(noise, "nearest").equals("linear")
                        && PackResourcePlan.mcmetaWrap(noise, "repeat").equals("clamp"),
                ".mcmeta blur/clamp were not applied");
    }

    /**
     * Water shading keys off the block id. BSL writes each id's block list on a
     * backslash continuation line inside {@code #if MC_VERSION} tables, so the
     * reader must join lines and evaluate the branches with the standard
     * macros, or water resolves to -1 and renders as a plain translucent.
     */
    private static void verifyWaterMaterial(String pack, PackProbe.Analysis analysis, String name) {
        int expected = name.equals("bsl") ? 20000 : 32000;
        try (PackSource.LoadResult loaded = PackSource.loadResult(Path.of(pack))) {
            PackMaterialResolver resolver = PackMaterialResolver.parse(loaded.shadersDir(),
                    PackEngineDefines.forPack(analysis.settings().preprocessorDefines())).resolver();
            assertTrue(resolver.resolveName("minecraft:water") == expected
                            && resolver.resolveName("minecraft:flowing_water") == expected,
                    name + " water resolves to " + resolver.resolveName("minecraft:water")
                            + ", expected " + expected);
            // Double plants map by half through state selectors; without them
            // tall grass had no id and did not wave.
            int upper = name.equals("bsl") ? 10300 : 10021;
            int tallGrass = resolver.resolveState("minecraft:tall_grass", Map.of("half", "upper")::get);
            assertTrue(tallGrass == upper,
                    name + " upper tall grass resolves to " + tallGrass + ", expected " + upper);
        }
    }

    /**
     * Iris keeps a pack's options in <pack>.txt beside the zip; Chimera reads
     * the same file so both renderers run one option set. JVM
     * chimera.option.* properties win over the file.
     */
    private static void verifyOptionFile() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("m88-options");
        Path pack = dir.resolve("Example_r1.zip");
        String previous = System.getProperty("chimera.option.WATER_STYLE");
        try {
            java.nio.file.Files.writeString(pack, "");
            assertTrue(PackOptionSources.optionFile(pack) == null
                            && PackOptionSources.fileValues(pack).isEmpty(),
                    "a pack without an option file produced options");
            java.nio.file.Files.writeString(dir.resolve("Example_r1.zip.txt"),
                    "#Iris options\nWATER_STYLE=2\nRP_MODE=3\nSHADOW_QUALITY=1\n");
            assertTrue(PackOptionSources.fileValues(pack).equals(
                            Map.of("WATER_STYLE", "2", "RP_MODE", "3", "SHADOW_QUALITY", "1")),
                    "option file values: " + PackOptionSources.fileValues(pack));
            System.setProperty("chimera.option.WATER_STYLE", "3");
            Map<String, String> merged = PackOptionSources.overrides(pack);
            assertTrue("3".equals(merged.get("WATER_STYLE")) && "3".equals(merged.get("RP_MODE")),
                    "JVM options must override the option file: " + merged);
            verifyToggleOptions();
        } finally {
            if (previous == null) System.clearProperty("chimera.option.WATER_STYLE");
            else System.setProperty("chimera.option.WATER_STYLE", previous);
            try (var paths = java.nio.file.Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /**
     * Iris writes toggle options as true/false. false must leave the macro
     * undefined even though the pack source defines it; true must define a
     * toggle the source leaves commented out.
     */
    private static void verifyToggleOptions() {
        String source = """
                #version 120
                #define SOURCE_ON
                //#define SOURCE_OFF
                #ifdef SOURCE_ON
                int sourceOn;
                #endif
                #ifdef SOURCE_OFF
                int sourceOff;
                #endif
                void main() {}
                """;
        PackProgram program = new PackProgram("composite", source, null);
        PackSettingsPlan settings = PackSettingsPlan.parse(List.of(program), null,
                Map.of("SOURCE_ON", "false", "SOURCE_OFF", "true"));
        assertTrue(!settings.preprocessorDefines().containsKey("SOURCE_ON")
                        && "1".equals(settings.preprocessorDefines().get("SOURCE_OFF")),
                "toggle defines: " + settings.preprocessorDefines());
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(null, null, source,
                PackEngineDefines.forPack(settings.preprocessorDefines()),
                PackEngineDefines.lockedNames(settings.overriddenNames(), false, false));
        assertTrue(prepared.source() != null && !prepared.source().contains("sourceOn")
                        && prepared.source().contains("sourceOff"),
                "toggle options did not follow Iris true/false: " + prepared.source());
    }

    /**
     * Iris blend directives: blend.<program> sets every buffer, and
     * blend.<program>.<buffer> (colortexN or its alias) sets one. Buffers
     * without a directive keep the host draw's blend (a null mode).
     */
    private static void verifyBlendDirectives() {
        PackBlendPlan.Mode alpha = PackBlendPlan.parse("SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA");
        assertTrue(alpha != null && alpha.enabled() && alpha.srcColor() == 6 && alpha.dstColor() == 7
                        && alpha.srcAlpha() == 1 && alpha.dstAlpha() == 7,
                "blend factors did not map to Vulkan factors: " + alpha);
        assertTrue(PackBlendPlan.parse("GL_ONE GL_ZERO GL_ONE GL_ZERO") != null, "GL_ prefix rejected");
        assertTrue(PackBlendPlan.parse("off") == PackBlendPlan.Mode.OFF, "off did not disable blending");
        assertTrue(PackBlendPlan.parse("SRC_ALPHA ONE") == null, "two-factor directive accepted");
        assertTrue(PackBlendPlan.parse("SRC_ALPHA ONE ONE BOGUS") == null, "unknown factor accepted");

        PackSettingsPlan settings = properties(Map.of(
                "blend.gbuffers_water", "SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ONE_MINUS_SRC_ALPHA",
                "blend.gbuffers_water.colortex4", "off",
                "blend.gbuffers_water.gaux4", "off",
                "blend.gbuffers_water.colortex99x", "off",
                "blend.gbuffers_waterfall", "off"));
        PackBlendPlan plan = PackBlendPlan.forProgram("gbuffers_water", settings);
        PackBlendPlan.Mode[] modes = plan.attachments(List.of(0, 3, 4, 7));
        assertTrue(modes[0].equals(alpha) && modes[1].equals(alpha)
                        && modes[2] == PackBlendPlan.Mode.OFF && modes[3] == PackBlendPlan.Mode.OFF,
                "water attachment blends in output order: " + java.util.Arrays.toString(modes));
        assertTrue(plan.deviations().contains("BLEND_DIRECTIVE_MALFORMED:blend.gbuffers_water.colortex99x")
                        && plan.deviations().contains("BLEND_DIRECTIVE_APPLIED:gbuffers_water"),
                "blend deviations: " + plan.deviations());
        PackBlendPlan none = PackBlendPlan.forProgram("gbuffers_terrain", settings);
        assertTrue(!none.overridesAnything() && none.attachments(List.of(0, 1)) == null,
                "a program without directives changed the host blend");
        PackBlendPlan bufferOnly = PackBlendPlan.forProgram("gbuffers_water",
                properties(Map.of("blend.gbuffers_water.colortex8", "off")));
        PackBlendPlan.Mode[] bufferModes = bufferOnly.attachments(List.of(0, 8));
        assertTrue(bufferModes[0] == null && bufferModes[1] == PackBlendPlan.Mode.OFF,
                "a buffer-only directive must leave the other buffers on the host blend");
    }

    /**
     * Complementary writes normals (colortex4) and reflection data
     * (colortex8) from water with blending off; they must not be mixed with
     * the opaque values underneath. Its program-wide water blend sits under
     * DISTANT_HORIZONS, so the other buffers keep the host translucent blend.
     * BSL declares no water blend.
     */
    private static void verifyRealPackBlend(PackProgramPlan water, String name) {
        List<Integer> targets = water.geometryOutputPlan().targetSlots();
        PackBlendPlan.Mode[] modes = water.blendPlan().attachments(targets);
        if (name.equals("bsl")) {
            assertTrue(modes == null, "bsl gbuffers_water gained a blend override");
            return;
        }
        assertTrue(modes != null, name + " gbuffers_water lost its blend directives");
        for (int i = 0; i < targets.size(); i++) {
            int target = targets.get(i);
            boolean off = target == 4 || target == 8;
            assertTrue(off ? modes[i] == PackBlendPlan.Mode.OFF : modes[i] == null,
                    name + " gbuffers_water colortex" + target + " blend " + modes[i]);
        }
        System.out.println("[chimera] m8.8 " + name + " gbuffers_water blends " + targets + " "
                + java.util.Arrays.toString(modes));
    }

    /**
     * Iris shadowtex1 holds only the casters drawn before translucent
     * terrain, so every program that reads it must bind its own image on its
     * own selector, never shadowtex0's. Packs tint light through
     * shadowcolor exactly where the two maps differ.
     */
    private static void verifyShadowDepthSplit(PackProbe.Analysis analysis, String name) {
        int checked = 0;
        for (PackProgramPlan plan : analysis.plan().programs()) {
            if (!plan.executable()) continue;
            for (PackResourceBinding binding : analysis.plan().resources().bindings(plan.name())) {
                if (!PackResourcePlan.canonicalResource(binding.sampler()).equals("shadowtex1")) continue;
                assertTrue(binding.resourceKey().equals("shadowtex1")
                                && binding.kind() == PackResourceKind.SHADOW_DEPTH
                                && binding.slot() == SelectorNamespace.SHADOW_TEX1_SLOT,
                        name + " " + plan.name() + " shadowtex1 resolves to " + binding.resourceKey()
                                + " at slot " + binding.slot());
                checked++;
            }
        }
        assertTrue(checked > 0, name + " no executable program reads shadowtex1");
        PackProgramPlan water = analysis.plan().program("gbuffers_water");
        PackPipelines.PreparedPipeline prepared;
        try {
            prepared = PackPipelines.prepare(water, analysis.plan().advancedResources(),
                    UniformRegistry.Stage.TRANSLUCENT, water.convertedVertex());
        } catch (PackPipelines.PreparationFailure failure) {
            throw new AssertionError(name + " gbuffers_water preparation failed", failure);
        }
        List<String> shadowKeys = prepared.imageBindings().entries().stream()
                .filter(entry -> entry.kind() == ProgramImageBindingManifest.Kind.SHADOW_DEPTH)
                .map(ProgramImageBindingManifest.Entry::resourceKey).sorted().toList();
        assertTrue(shadowKeys.contains("shadowtex1"),
                name + " gbuffers_water manifest has no separate shadowtex1: " + shadowKeys);
        System.out.println("[chimera] m8.8 " + name + " shadowtex1 split: " + checked
                + " program bindings, water shadow depths " + shadowKeys);
    }

    private static PackProgramPlan post(String name, String sampler, int target) {
        String fragment = """
                #version 120
                uniform sampler2D %s;
                varying vec2 texcoord;
                void main() {
                    /* DRAWBUFFERS:%d */
                    gl_FragData[0] = texture2D(%s, texcoord);
                }
                """.formatted(sampler, target, sampler);
        PackProgramPlan plan = PackPlanBuilder.build(new PackProgram(name, fragment, null), null);
        assertTrue(plan.executable(), name + " fixture is not executable: " + plan.deviations());
        return plan;
    }

    private static PackSettingsPlan properties(Map<String, String> values) {
        return new PackSettingsPlan(Map.of(), Map.of(), Map.of(), values, Map.of(),
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), List.of(), "default");
    }

    private static String terrainVertex() {
        return """
                #version 120
                varying vec2 texcoord;
                void main() {
                    gl_Position = ftransform();
                    texcoord = gl_MultiTexCoord0.xy;
                }
                """;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
