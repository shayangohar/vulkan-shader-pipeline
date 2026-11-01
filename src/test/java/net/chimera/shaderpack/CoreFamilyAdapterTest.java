package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The core family adapters. */
final class CoreFamilyAdapterTest {
    @Test
    void coreFamilyAdapters() throws Exception {
        Path fixtureRoot = TestPacks.fixtures();
        Path supported = fixtureRoot.resolve("m7_6/families");
        Path unsupported = fixtureRoot.resolve("m7_6/unsupported_families");
        Path baseline = TestPacks.baseline("m7_6");

        verifySupported(supported, baseline);
        verifyUnsupported(unsupported, baseline);
    }

    /** A pack's own gbuffers_block_translucent plans from its own file, into its own targets. */
    @Test
    void blockTranslucentOwnTargets(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Path families = TestPacks.fixture("m7_6/families/shaders");
        Files.copy(families.resolve("gbuffers_block.vsh"), shaders.resolve("gbuffers_block.vsh"));
        Files.copy(families.resolve("gbuffers_block.fsh"), shaders.resolve("gbuffers_block.fsh"));
        Files.copy(families.resolve("gbuffers_block.vsh"), shaders.resolve("gbuffers_block_translucent.vsh"));
        Files.writeString(shaders.resolve("gbuffers_block_translucent.fsh"), """
                #version 120
                uniform sampler2D texture;
                varying vec4 familyColor;
                varying vec2 familyUv;
                /* RENDERTARGETS:3 */
                void main() { gl_FragData[0] = texture2D(texture, familyUv) * familyColor; }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan block = analysis.plan().program("gbuffers_block");
        PackProgramPlan text = analysis.plan().program("gbuffers_block_translucent");
        assertTrue(text != null && text.executable(), "own block-translucent program is not executable: "
                + (text == null ? "missing" : text.deviations()));
        assertEquals(List.of(3), text.geometryOutputPlan().targetPlan().targetSlots(), "block-entity text targets");
        assertEquals(List.of(0), block.geometryOutputPlan().targetPlan().targetSlots(), "block model targets");
    }

    private static void verifySupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "supported report stability");
        for (String name : List.of("gbuffers_entities", "gbuffers_block", "gbuffers_hand")) {
            assertEntityLikePlan(first, name);
        }
        PackProgramPlan entityPlan = first.plan().program("gbuffers_entities");
        PackProgramPlan blockPlan = first.plan().program("gbuffers_block");
        for (PackProgramPlan plan : List.of(entityPlan, blockPlan)) {
            // Block entities draw entity meshes placed by the pose, as entities do.
            assertTrue(plan.convertedVertex() != null && plan.convertedVertex().contains("return vec4(Position, 1.0);"),
                    plan.name() + " bridge must read the host position as is");
        }
        PackProgramPlan blockText = first.plan().program("gbuffers_block_translucent");
        assertTrue(blockText != null && blockText.executable() && "gbuffers_block".equals(
                        first.resolution().resolution("gbuffers_block_translucent").selectedProgram()),
                "without its own file, block-entity text plans from gbuffers_block (Iris BlockTrans -> Block)");

        PackProgramPlan particles = first.plan().program("gbuffers_particles");
        assertTrue(particles != null && particles.executable(),
                "particle plan was not executable: "
                        + (particles == null ? List.of() : particles.deviations()));
        assertEquals(UniformRegistry.Stage.PARTICLE,
                particles.interfacePlan().effective(UniformRegistry.Stage.PARTICLE).stage(),
                "particle interface stage");
        assertTrue(particles.convertedVertex().contains("layout(location = 0) in vec3 Position"),
                "particle position input");
        assertTrue(particles.convertedFragment().contains("layout(location = 0) in vec4 particleColor"),
                "particle varying location");
        assertTrue(first.report().shouldAttempt("gbuffers_particles"),
                "particle report rejected");

        PackProgramPlan finalPlan = first.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "supported final pass was not executable");
        assertTrue(finalPlan.convertedFragment() != null
                        && finalPlan.convertedFragment().contains("texcoord"),
                "supported final pass was not converted with the fixed varying");

        ConformanceReport.ProgramReport sky = first.report().program("gbuffers_skybasic");
        assertTrue(sky != null, "sky family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, sky.support(),
                "fragment-only sky support status");
        assertTrue(!first.report().shouldAttempt("gbuffers_skybasic"),
                "fragment-only sky family remained eligible");
        verifyBaseline(first.report(), baseline, "families");
        if (TestPacks.printBaselines()) {
            printBaseline("families", first.report());
        }
        assertNoAbsolutePaths(first.report(), fixture);
    }

    /** An entity-like program converts onto the extended entity layout and stays eligible. */
    private static void assertEntityLikePlan(PackProbe.Analysis analysis, String name) {
        PackProgramPlan plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable(),
                "entity-like plan was not executable: " + name
                        + " deviations=" + (plan == null ? "missing" : plan.deviations())
                        + " vertex=" + (plan == null ? "missing" : plan.convertedVertex() != null)
                        + " fragment=" + (plan == null ? "missing" : plan.convertedFragment() != null)
                        + " interface=" + (plan == null ? "missing" : plan.interfacePlan().deviations())
                        + " resolution=" + (plan == null || analysis.resolution() == null
                        ? "missing" : analysis.resolution().resolution(name))
                        + " stage=" + (plan == null ? "missing" : plan.interfacePlan().effective(
                        FamilyAdapterRegistry.stageFor(name)).stage()));
        FamilyAdapterPlan.VertexContract expectedContract = FamilyAdapterPlan.VertexContract.EXTENDED_ENTITY;
        assertEquals(expectedContract, plan.familyAdapter().vertexContract(),
                "entity-like contract: " + name);
        assertTrue(plan.convertedVertex() != null && plan.convertedVertex().contains("#version 460"),
                "entity-like vertex was not converted: " + name);
        assertTrue(plan.convertedFragment() != null && plan.convertedFragment().contains("#version 460"),
                "entity-like fragment was not converted: " + name);
        if (name.equals("gbuffers_hand")) {
            assertTrue(plan.convertedVertex().contains("layout(location = 2) in vec2 UV0"),
                    "hand primary vertex must use the actual arm entity UV layout");
            assertTrue(plan.convertedVertex().contains("layout(location = 4) in ivec2 UV2"),
                    "hand primary vertex omitted the arm lightmap input");
        }
        assertTrue(analysis.report().shouldAttempt(name),
                "entity-like report rejected: " + name);
    }

    /**
     * A world program with no lane names why: Iris draws terrain_solid for solid terrain, which
     * VulkanMod draws in the cutout_mipped layer with the rest, and gbuffers_textured_lit under no
     * key of its own (ShaderKey). gbuffers_textured has its lanes: Iris draws position_tex and
     * position_tex_color world draws with it (MixinGameRenderer, TEXTURED and TEXTURED_COLOR).
     */
    @Test
    void unlanedProgramsNameTheirReason(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String fragment = "#version 120\nvoid main() {\n/* DRAWBUFFERS:0 */\n gl_FragData[0] = vec4(1.0);\n}\n";
        for (String name : List.of("gbuffers_textured", "gbuffers_textured_lit", "gbuffers_terrain_solid",
                "begin1", "setup", "gbuffers_translucent", "shadow")) {
            Files.writeString(shaders.resolve(name + ".vsh"),
                    "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
            Files.writeString(shaders.resolve(name + ".fsh"), fragment);
        }
        ConformanceReport report = PackProbe.analyze(root).report();
        // Iris's begin renderer runs begin1; it runs no setup program and reads no gbuffers_translucent.
        assertTrue(report.program("begin1").deviations().contains("PASS_PROGRAM_UNSUPPORTED:begin1")
                && report.program("setup").deviations().contains("IRIS_PROGRAM_UNUSED:setup")
                && report.program("gbuffers_translucent").deviations()
                .contains("IRIS_PROGRAM_UNUSED:gbuffers_translucent"), "pass and unread reasons");
        PackPlan plan = PackProbe.analyze(root).plan();
        for (String lane : List.of("gbuffers_textured", "textured_color", "shadow_tex", "shadow_tex_color")) {
            assertTrue(report.shouldAttempt(lane) && plan.program(lane) != null && plan.program(lane).executable(),
                    lane + ": " + report.program(lane).deviations());
        }
        String textured = plan.program("gbuffers_textured").convertedFragment();
        assertTrue(textured.contains(UniformRegistry.ENTITY_ALPHA_REFERENCE), "TEXTURED tests the lane's reference");
        assertTrue(report.program("gbuffers_terrain_solid").deviations()
                .contains("TERRAIN_LAYER_MERGED_BY_HOST:gbuffers_terrain_solid"), "terrain_solid reason");
        assertTrue(report.program("gbuffers_textured_lit").deviations().contains("IRIS_FALLBACK_SOURCE_ONLY"),
                "textured_lit reason");
    }

    /**
     * Iris draws the shadow pass's translucent terrain with shadow_water (SodiumPrograms SHADOW_TRANS)
     * and its cutout terrain with shadow_cutout (SHADOW_CUTOUT), so a pack's own programs run on the
     * shadow terrain lane. VulkanMod's cutout_mipped holds solid blocks too, which shadow_cutout
     * draws; shadow_solid has no layer of its own and says so.
     */
    @Test
    void shadowLayerProgramsRunOnTheShadowLane(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String vertex = "#version 120\nvarying vec2 uv;\n"
                + "void main() {\n gl_Position = ftransform();\n uv = gl_MultiTexCoord0.xy;\n}\n";
        String fragment = "#version 120\nuniform sampler2D tex;\nvarying vec2 uv;\n"
                + "void main() {\n gl_FragData[0] = texture2D(tex, uv);\n}\n";
        for (String name : List.of("shadow", "shadow_water", "shadow_cutout", "shadow_solid")) {
            Files.writeString(shaders.resolve(name + ".vsh"), vertex);
            Files.writeString(shaders.resolve(name + ".fsh"), fragment);
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String name : List.of("shadow_water", "shadow_cutout")) {
            PackProgramPlan layer = analysis.plan().program(name);
            assertTrue(layer.executable() && layer.convertedVertex() != null
                    && !analysis.plan().isProgramAlias(name), name + ": " + layer.deviations());
        }
        assertTrue(analysis.report().program("shadow_solid").deviations()
                .contains("TERRAIN_LAYER_MERGED_BY_HOST:shadow_solid"), "shadow_solid reason");
        // The registry, not a name, puts a program on the shadow terrain lane.
        assertTrue(FamilyAdapterRegistry.isShadowTerrain("shadow")
                        && FamilyAdapterRegistry.isShadowTerrain("shadow_water")
                        && FamilyAdapterRegistry.isShadowTerrain("shadow_cutout")
                        && !FamilyAdapterRegistry.isShadowTerrain("shadow_solid")
                        && !FamilyAdapterRegistry.isShadowTerrain("shadow_entities")
                        && FamilyAdapterRegistry.laneProgram("shadow_cutout").equals("shadow")
                        && FamilyAdapterRegistry.laneProgram("shadow_entities").equals("shadow_entities"),
                "shadow terrain lane");
    }

    private static void verifyUnsupported(Path fixture, Path baseline) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        assertTrue(analysis.report().shouldAttempt("final"),
                "unrelated final pass was rejected");
        PackProgramPlan finalPlan = analysis.plan().program("final");
        assertTrue(finalPlan != null && finalPlan.executable(),
                "unsupported fixture identity final was not executable");
        for (String name : List.of("gbuffers_skybasic", "gbuffers_entities_glowing")) {
            ConformanceReport.ProgramReport program = analysis.report().program(name);
            assertTrue(program != null, "unsupported family was not inventoried: " + name);
            assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED, program.support(),
                    "unsupported family status: " + name);
            assertTrue(!analysis.report().shouldAttempt(name),
                    "unsupported family remained eligible: " + name);
        }
        // Iris draws entities_glowing under no key of its own, and a skybasic with no vertex stage on
        // its default one (ProgramSet.readProgramSource), which the sky lane lacks: each row names why.
        assertTrue(analysis.report().program("gbuffers_entities_glowing").deviations()
                .contains("IRIS_FALLBACK_SOURCE_ONLY"), "glowing reason");
        assertTrue(analysis.report().program("gbuffers_skybasic").deviations()
                .contains("IRIS_DEFAULT_VERTEX_UNSUPPORTED"), "skybasic reason");
        verifyBaseline(analysis.report(), baseline, "unsupported_families");
        if (TestPacks.printBaselines()) {
            printBaseline("unsupported_families", analysis.report());
        }
        assertNoAbsolutePaths(analysis.report(), fixture);
    }

    private static void verifyBaseline(
            ConformanceReport report, Path baselinePath, String fixtureName) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = root.getAsJsonObject("fixtures").getAsJsonObject(fixtureName);
        assertTrue(entry != null, "baseline entry is missing: " + fixtureName);
        String expectedHash = entry.has("reportSha256")
                ? entry.get("reportSha256").getAsString() : "TO_BE_FILLED";
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "report hash: " + fixtureName);
        }
        if (entry.has("sourceHashes")) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report), "source hashes: " + fixtureName);
        }
        if (entry.has("expectedExecutablePrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "executable baseline: " + fixtureName);
        }
        if (entry.has("expectedFallbackPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "fallback baseline: " + fixtureName);
        }
        if (entry.has("expectedUnsupportedPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedUnsupportedPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.UNSUPPORTED)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "unsupported baseline: " + fixtureName);
        }
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "deviations: " + fixtureName);
        }
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
    }

    private static void printBaseline(String name, ConformanceReport report) {
        System.out.println("[chimera] M7.6 baseline " + name + " reportSha256=" + report.sha256());
        sourceHashes(report).forEach((path, hash) ->
                System.out.println("[chimera] M7.6 baseline " + name + " source " + path + "=" + hash));
        System.out.println("[chimera] M7.6 baseline " + name + " deviations=" + allDeviations(report));
    }

    private static List<String> allDeviations(ConformanceReport report) {
        List<String> result = new ArrayList<>(report.deviations());
        for (ConformanceReport.ProgramReport program : report.programs()) {
            result.addAll(program.deviations());
        }
        return result.stream().distinct().sorted().toList();
    }

    private static List<String> strings(Iterable<JsonElement> values) {
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    private static void assertNoAbsolutePaths(ConformanceReport report, Path fixture) {
        String json = report.toJson();
        assertTrue(!json.contains(fixture.toAbsolutePath().toString()),
                "report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "report contains a timestamp field");
    }
}
