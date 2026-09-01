package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.vertex.ChimeraEntityVertexData;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.minecraft.client.renderer.RenderPipelines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic M6.3 checks for the isolated world-entity adapter. */
public final class M63ConformanceHarness {
    private M63ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        Path fixture = fixtureRoot.resolve("m6_3/entities");
        Path unsupported = fixtureRoot.resolve("m6_3/unsupported_entities");
        Path baseline = Path.of(System.getProperty(
                "chimera.m63.baseline", fixtureRoot.resolve("baselines/m6_3.json").toString()));

        verifyExtendedFormat();
        verifyWorldPipelineContract();
        verifyEntityMath();
        verifyEntityIdUniformBridge();
        verifyEntityIdResolver(fixture.resolve("shaders"));
        verifySupportedFixture(fixture, baseline, "entities");
        verifyUnsupportedFixture(unsupported, baseline);
        System.out.println("[chimera] M6.3 entity geometry conformance: PASS");
    }

    private static void verifyExtendedFormat() {
        var host = com.mojang.blaze3d.vertex.DefaultVertexFormat.NEW_ENTITY;
        var extended = ChimeraVertexFormats.EXTENDED_ENTITY;
        assertEquals(36, host.getVertexSize(), "M6.3 host entity stride");
        assertEquals(56, extended.getVertexSize(), "M6.3 extended entity stride");
        assertEquals(9, extended.getElements().size(), "M6.3 extended entity attribute count");

        List<String> expectedNames = List.of(
                "Position", "Color", "UV0", "UV1", "UV2", "Normal",
                "EntityIds", "MidTexCoord", "Tangent");
        assertEquals(expectedNames, extended.getElementAttributeNames(),
                "M6.3 entity attribute names");
        int[] expectedOffsets = {0, 12, 16, 24, 28, 32, 36, 44, 52};
        for (int index = 0; index < expectedOffsets.length; index++) {
            assertEquals(expectedOffsets[index], extended.getOffset(extended.getElements().get(index)),
                    "M6.3 entity attribute offset " + expectedNames.get(index));
        }
        for (int index = 0; index < host.getElements().size(); index++) {
            assertTrue(extended.getElements().get(index) == host.getElements().get(index),
                    "M6.3 host entity element was copied instead of preserved at " + index);
            assertEquals(host.getOffset(host.getElements().get(index)),
                    extended.getOffset(extended.getElements().get(index)),
                    "M6.3 host entity offset changed at " + index);
        }
        assertEquals("SHORT", ChimeraVertexFormats.ENTITY_IDS.type().name(),
                "M6.3 entity IDs translator-safe type");
        assertEquals(1, ChimeraVertexFormats.ENTITY_IDS.count(),
                "M6.3 entity IDs translator-safe count");
        assertEquals("FLOAT", ChimeraVertexFormats.MID_TEX_COORD.type().name(),
                "M6.3 mid UV translator-safe type");
        assertEquals(1, ChimeraVertexFormats.MID_TEX_COORD.count(),
                "M6.3 mid UV translator-safe count");
        assertEquals("INT", ChimeraVertexFormats.TANGENT.type().name(),
                "M6.3 tangent translator-safe type");
        assertEquals(1, ChimeraVertexFormats.TANGENT.count(),
                "M6.3 tangent translator-safe count");
    }

    private static void verifyWorldPipelineContract() {
        assertTrue(ChimeraEntityBridge.supportsWorldPipeline(RenderPipelines.ENTITY_SOLID),
                "M6.3 solid world entity lane was not supported");
        assertTrue(ChimeraEntityBridge.supportsWorldPipeline(RenderPipelines.ENTITY_TRANSLUCENT),
                "M6.3 living translucent world entity lane was not supported");
    }

    private static void verifyEntityMath() {
        float[] normal = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(normal,
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        1.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f),
                "M6.3 face normal rejected a valid quad");
        assertNear(0.0f, normal[0], "M6.3 face normal x");
        assertNear(0.0f, normal[1], "M6.3 face normal y");
        assertNear(1.0f, normal[2], "M6.3 face normal z");

        int tangent = ChimeraEntityVertexData.tangent(0.0f, 0.0f, 1.0f, false,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                0.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        assertTrue(tangent != ChimeraEntityVertexData.FLAT_TANGENT,
                "M6.3 tangent math used the flat fallback for a valid triangle");
        assertEquals(ChimeraEntityVertexData.FLAT_TANGENT,
                ChimeraEntityVertexData.tangent(0.0f, 0.0f, 1.0f, false,
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f, 0.0f),
                "M6.3 zero-UV triangle fallback");
    }

    private static void verifyEntityIdUniformBridge() {
        String vertex = """
                #version 120
                uniform int entityId;
                varying vec2 entityUv;
                void main() {
                    entityUv = vec2(float(entityId));
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 120
                uniform int frameCounter, entityId;
                varying vec2 entityUv;
                void main() {
                    gl_FragColor = vec4(float(entityId) + float(frameCounter) + entityUv.x);
                }
                """;
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                fragment, vertex, UniformRegistry.Stage.ENTITY, null, false);
        LegacyGlslConverter.TerrainVertexConversion convertedVertex =
                LegacyGlslConverter.convertEntityVertex(vertex, null, fragment,
                        Map.of("entityUv", 0));
        assertTrue(plan.executable(), "M6.3 entityId uniform was not treated as vertex data");
        assertTrue(convertedVertex != null, "M6.3 entityId uniform vertex bridge rejected the source");
        String convertedFragment = LegacyGlslConverter.convertEntityFragment(
                fragment, null, new int[0], convertedVertex.layout(),
                plan.effective(UniformRegistry.Stage.ENTITY));
        assertTrue(convertedFragment != null, "M6.3 entityId uniform fragment bridge rejected the source");
        assertTrue(!convertedVertex.source().contains("uniform int entityId"),
                "M6.3 entityId uniform remained in the vertex source");
        assertTrue(!convertedFragment.contains("uniform int entityId"),
                "M6.3 entityId uniform remained in the fragment source");
        assertTrue(convertedVertex.source().contains("chimeraEntityId = EntityIds.x"),
                "M6.3 entityId vertex data was not initialized");
        assertTrue(convertedFragment.contains("flat in uint chimeraEntityId"),
                "M6.3 entityId vertex data was not bridged to the fragment");
    }

    private static void verifyEntityIdResolver(Path shaders) throws IOException {
        PackEntityIdResolver.ParseResult parsed = PackEntityIdResolver.parse(shaders);
        assertTrue(parsed.present(), "M6.3 entity.properties was not detected");
        assertEquals(Map.of("minecraft:cow", 2, "minecraft:zombie", 1),
                parsed.resolver().mappings(), "M6.3 entity map");
        assertEquals(1, parsed.resolver().resolveName("zombie"),
                "M6.3 unqualified entity name");
        assertEquals(2, parsed.resolver().resolveName("minecraft:cow"),
                "M6.3 namespaced entity name");
        assertEquals(0xFFFF, parsed.resolver().resolveName("pig"),
                "M6.3 unknown mapped entity sentinel");
        assertEquals(0, PackEntityIdResolver.empty().resolveName("zombie"),
                "M6.3 missing map default");

        Path temporary = Files.createTempDirectory("chimera-m63-entity-");
        try {
            Files.writeString(temporary.resolve("entity.properties"), "# no usable mappings\n",
                    StandardCharsets.UTF_8);
            PackEntityIdResolver.ParseResult empty = PackEntityIdResolver.parse(temporary);
            assertTrue(!empty.present(), "M6.3 empty entity map was treated as usable");
            assertEquals(0, empty.resolver().resolveName("zombie"),
                    "M6.3 empty entity map default");

            Files.writeString(temporary.resolve("entity.properties"),
                    "entity.1=zombie\nentity.2=zombie\nentity.70000=pig\nentity.bad=cat\n",
                    StandardCharsets.UTF_8);
            PackEntityIdResolver.ParseResult malformed = PackEntityIdResolver.parse(temporary);
            assertTrue(malformed.deviations().stream()
                            .anyMatch(value -> value.startsWith("ENTITY_ID_UNSUPPORTED:")),
                    "M6.3 malformed entity map was accepted silently");
            assertTrue(!malformed.resolver().mappings().containsKey("minecraft:zombie"),
                    "M6.3 conflicting entity map entry remained active");
        } finally {
            try (var paths = Files.walk(temporary)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                });
            }
        }
    }

    private static void verifySupportedFixture(
            Path fixture, Path baselinePath, String baselineName) throws IOException {
        PackProbe.Analysis first = PackProbe.analyze(fixture);
        PackProbe.Analysis second = PackProbe.analyze(fixture);
        assertEquals(first.report().toJson(), second.report().toJson(),
                "M6.3 supported report stability");
        assertEquals(first.plan().program("gbuffers_entities").convertedVertex(),
                second.plan().program("gbuffers_entities").convertedVertex(),
                "M6.3 supported converted vertex stability");

        ConformanceReport report = first.report();
        ConformanceReport.ProgramReport entity = report.program("gbuffers_entities");
        assertTrue(entity != null, "M6.3 entity program was not discovered");
        assertEquals(List.of("fragment", "vertex"), entity.stages(),
                "M6.3 entity stages");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                entity.support(), "M6.3 entity support status");
        assertTrue(report.shouldAttempt("gbuffers_entities"),
                "M6.3 entity report rejected an executable program");
        assertTrue(report.deviations().contains("ENTITY_ID_MAP_APPLIED"),
                "M6.3 entity map application was not reported");
        assertContains(entity.deviations(), "ENTITY_BATCH_ORIGIN_SPLIT",
                "M6.3 entity batch split deviation");
        assertContains(entity.deviations(), "ENTITY_STATE_FIXED_TO_HOST",
                "M6.3 host state deviation");
        assertContains(entity.deviations(), "ENTITY_VERTEX_FORMAT_EXTENDED",
                "M6.3 extended format deviation");
        assertContains(entity.deviations(), "ENTITY_VERTEX_BRIDGE",
                "M6.3 vertex bridge deviation");
        assertEquals(List.of("lightmap", "texture"), entity.samplers(),
                "M6.3 entity sampler inventory");

        PackProgramPlan plan = first.plan().program("gbuffers_entities");
        assertTrue(plan != null && plan.executable(), "M6.3 entity runtime plan rejected");
        assertEquals(UniformRegistry.Stage.ENTITY,
                plan.interfacePlan().effective(UniformRegistry.Stage.ENTITY).stage(),
                "M6.3 entity interface stage");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2)),
                plan.interfacePlan().samplers(), "M6.3 entity sampler slots");
        assertEquals(Map.of("entityColor", 0, "entityMarker", 1, "entityUv", 2),
                plan.varyingLocations(), "M6.3 shared entity varying locations");
        assertTrue(plan.convertedVertex().contains("#version 460"),
                "M6.3 entity vertex version");
        assertTrue(plan.convertedVertex().contains("layout(location = 0) out vec4 entityColor"),
                "M6.3 entity vertex varying output");
        assertTrue(plan.convertedVertex().contains("EntityIds"),
                "M6.3 entity ID input");
        assertTrue(plan.convertedVertex().contains("MidTexCoord"),
                "M6.3 mid UV input");
        assertTrue(plan.convertedVertex().contains("Tangent"),
                "M6.3 tangent input");
        assertTrue(plan.convertedVertex().contains("entityMarker"),
                "M6.3 entity marker varying");
        assertTrue(!plan.convertedVertex().contains("attribute float entityId"),
                "M6.3 legacy entity attribute declaration remained");
        assertTrue(plan.convertedFragment().contains("layout(binding = 2) uniform sampler2D chimeraTexture"),
                "M6.3 entity atlas binding");
        assertTrue(plan.convertedFragment().contains("layout(binding = 3) uniform sampler2D lightmap"),
                "M6.3 entity lightmap binding");
        assertTrue(plan.convertedFragment().contains("layout(location = 0) in vec4 entityColor"),
                "M6.3 entity fragment varying input");
        assertEquals(new int[] {0, 2}, PackPipelines.entitySamplerSlots(new int[] {2, 0, 2}),
                "M6.3 entity sampler slot ordering");

        verifyBaseline(report, baselinePath, fixture, baselineName);
        assertNoAbsolutePaths(report, fixture);
    }

    private static void verifyUnsupportedFixture(Path fixture, Path baselinePath) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        ConformanceReport.ProgramReport entity = analysis.report().program("gbuffers_entities");
        assertTrue(entity != null, "M6.3 unsupported entity program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                entity.support(), "M6.3 unsupported entity support");
        assertTrue(entity.deviations().contains("ENTITY_SAMPLER_UNSUPPORTED:unknownEntityTexture"),
                "M6.3 unsupported sampler deviation");
        assertTrue(!analysis.report().shouldAttempt("gbuffers_entities"),
                "M6.3 unsupported entity program remained eligible");
        assertTrue(!analysis.plan().program("gbuffers_entities").executable(),
                "M6.3 unsupported entity plan remained executable");
        assertTrue(analysis.report().shouldAttempt("final"),
                "M6.3 unsupported entity rejected unrelated final program");
        verifyBaseline(analysis.report(), baselinePath, fixture, "unsupported_entities");
    }

    private static void verifyBaseline(
            ConformanceReport report,
            Path baselinePath,
            Path fixture,
            String baselineName
    ) throws IOException {
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject entry = root.has("fixtures")
                ? root.getAsJsonObject("fixtures").getAsJsonObject(baselineName) : root;
        assertTrue(entry != null, "M6.3 entity baseline entry is missing");
        String expectedHash = entry.get("reportSha256").getAsString();
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "M6.3 entity report hash");
        }
        if (entry.has("sourceHashes")) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report), "M6.3 entity source hashes");
        }
        if (entry.has("expectedExecutablePrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M6.3 executable program baseline");
        }
        if (entry.has("expectedFallbackPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "M6.3 fallback program baseline");
        }
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "M6.3 deviation baseline");
        }
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> result = new TreeMap<>();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            program.sourceHashes().forEach(result::putIfAbsent);
        }
        return result;
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
                "M6.3 report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "M6.3 report contains a timestamp field");
    }

    private static void assertContains(List<String> values, String expected, String message) {
        assertTrue(values.contains(expected), message + ": " + values);
    }

    private static void assertNear(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.deepEquals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
