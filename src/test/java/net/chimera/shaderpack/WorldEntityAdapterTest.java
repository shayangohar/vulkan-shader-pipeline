package net.chimera.shaderpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraEntityBridge.Family;
import net.chimera.render.vertex.ChimeraEntityVertexData;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The isolated world-entity adapter. */
final class WorldEntityAdapterTest {
    @Test
    void worldEntityAdapter() throws Exception {
        Path fixtureRoot = TestPacks.fixtures();
        Path fixture = fixtureRoot.resolve("m6_3/entities");
        Path unsupported = fixtureRoot.resolve("m6_3/unsupported_entities");
        Path baseline = TestPacks.baseline("m6_3");

        verifyEntityIdResolver(fixture.resolve("shaders"));
        verifySupportedFixture(fixture, baseline, "entities");
        verifyUnsupportedFixture(unsupported, baseline);
    }

    @Test
    void extendedFormat() {
        var host = DefaultVertexFormat.NEW_ENTITY;
        var extended = ChimeraVertexFormats.EXTENDED_ENTITY;
        assertEquals(36, host.getVertexSize(), "host entity stride");
        assertEquals(56, extended.getVertexSize(), "extended entity stride");
        assertEquals(9, extended.getElements().size(), "extended entity attribute count");

        List<String> expectedNames = List.of(
                "Position", "Color", "UV0", "UV1", "UV2", "Normal",
                "EntityIds", "MidTexCoord", "Tangent");
        assertEquals(expectedNames, extended.getElementAttributeNames(),
                "entity attribute names");
        int[] expectedOffsets = {0, 12, 16, 24, 28, 32, 36, 44, 52};
        for (int index = 0; index < expectedOffsets.length; index++) {
            assertEquals(expectedOffsets[index], extended.getOffset(extended.getElements().get(index)),
                    "entity attribute offset " + expectedNames.get(index));
        }
        for (int index = 0; index < host.getElements().size(); index++) {
            assertTrue(extended.getElements().get(index) == host.getElements().get(index),
                    "host entity element was copied instead of preserved at " + index);
            assertEquals(host.getOffset(host.getElements().get(index)),
                    extended.getOffset(extended.getElements().get(index)),
                    "host entity offset changed at " + index);
        }
        assertEquals("SHORT", ChimeraVertexFormats.ENTITY_IDS.type().name(),
                "entity IDs translator-safe type");
        assertEquals(1, ChimeraVertexFormats.ENTITY_IDS.count(),
                "entity IDs translator-safe count");
        assertEquals("FLOAT", ChimeraVertexFormats.MID_TEX_COORD.type().name(),
                "mid UV translator-safe type");
        assertEquals(1, ChimeraVertexFormats.MID_TEX_COORD.count(),
                "mid UV translator-safe count");
        assertEquals("INT", ChimeraVertexFormats.TANGENT.type().name(),
                "tangent translator-safe type");
        assertEquals(1, ChimeraVertexFormats.TANGENT.count(),
                "tangent translator-safe count");
    }

    /** One shader in Iris 1.21.1's MixinGameRenderer table: the family of its draws in each context. */
    private record Route(String shader, Family entity, Family blockEntity, Family shadow, Family blockEntityShadow) {}

    /**
     * Every entity-format shader Iris 1.21.1 overrides, in an entity, in a block entity and in the
     * shadow pass; then the chunk shaders Iris draws as MOVING_BLOCK. Routes key on the shader, as
     * Iris's do, so NeoForge's item types and the armor-trim decal follow their shaders' rows.
     */
    @Test
    void worldPipelineContract() {
        List<Route> routes = new ArrayList<>();
        for (String model : List.of("entity_solid", "entity_cutout", "entity_cutout_no_cull",
                "entity_cutout_no_cull_z_offset", "entity_decal", "entity_smooth_cutout", "armor_cutout_no_cull")) {
            routes.add(new Route(model, Family.ENTITY, Family.BLOCK, Family.SHADOW_ENTITIES, Family.SHADOW_BLOCK));
        }
        for (String overlay : List.of("energy_swirl", "entity_shadow")) {
            routes.add(new Route(overlay, Family.ENTITY, Family.BLOCK, Family.SHADOW_ENTITIES,
                    Family.SHADOW_ENTITIES));
        }
        for (String translucent : List.of("entity_translucent", "entity_translucent_cull",
                "item_entity_translucent_cull", "breeze_wind", "entity_no_outline")) {
            routes.add(new Route(translucent, Family.ENTITY_TRANSLUCENT, Family.BLOCK_TRANSLUCENT,
                    Family.SHADOW_ENTITIES, Family.SHADOW_BLOCK));
        }
        routes.add(new Route("eyes", Family.SPIDER_EYES, Family.BLOCK, Family.SHADOW_ENTITIES,
                Family.SHADOW_ENTITIES));
        routes.add(new Route("entity_translucent_emissive", Family.SPIDER_EYES, Family.BLOCK,
                Family.SHADOW_ENTITIES, Family.SHADOW_BLOCK));
        // Iris ENTITIES_ALPHA (the dying dragon) in either context, with no shadow key of its own.
        routes.add(new Route("entity_alpha", Family.ENTITY, Family.ENTITY, Family.SHADOW_ENTITIES,
                Family.SHADOW_ENTITIES));
        var entityFormat = DefaultVertexFormat.NEW_ENTITY;
        for (Route route : routes) {
            String shader = "rendertype_" + route.shader();
            assertEquals(route.entity(), ChimeraEntityBridge.worldFamily(shader, entityFormat, Family.ENTITY), shader);
            assertEquals(route.blockEntity(), ChimeraEntityBridge.worldFamily(shader, entityFormat, Family.BLOCK),
                    shader + " in a block entity");
            assertEquals(route.shadow(), ChimeraEntityBridge.shadowFamily(shader, entityFormat, Family.ENTITY),
                    shader + " casting a shadow");
            assertEquals(route.blockEntityShadow(),
                    ChimeraEntityBridge.shadowFamily(shader, entityFormat, Family.BLOCK),
                    shader + " casting a block entity's shadow");
        }
        assertEquals(null, ChimeraEntityBridge.worldFamily(null, entityFormat, Family.ENTITY),
                "a render type without a shader keeps its host draw");
        var block = DefaultVertexFormat.BLOCK;
        for (String type : List.of("solid", "cutout_mipped", "cutout", "translucent", "translucent_moving_block",
                "tripwire")) {
            String shader = "rendertype_" + type;
            for (Family context : List.of(Family.ENTITY, Family.BLOCK)) {
                assertEquals(Family.MOVING_BLOCK, ChimeraEntityBridge.worldFamily(shader, block, context), shader);
                assertEquals(Family.SHADOW_ENTITIES, ChimeraEntityBridge.shadowFamily(shader, block, context), shader);
            }
            assertEquals(null, ChimeraEntityBridge.shadowFamily(shader, block, null), "terrain casts no entity shadow");
        }
        assertEquals(null, ChimeraEntityBridge.worldFamily("rendertype_crumbling", block, Family.BLOCK), "crumbling");
        assertEquals(null, ChimeraEntityBridge.worldFamily("rendertype_beacon_beam", block, Family.BLOCK), "beams");
    }

    /** Iris reports BLOCK_ENTITIES for whatever a block entity draws, in either pass, and ENTITIES for an entity's. */
    @Test
    void familyBatchesReportTheirContextPhase() {
        ChimeraEntityBridge.beginBlockEntity(0);
        try {
            assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.writePhase(Family.MOVING_BLOCK),
                    "a moving piston");
            assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.writePhase(Family.SHADOW_ENTITIES),
                    "a block entity's eyes in the shadow pass");
            ChimeraEntityBridge.beginEntity("minecraft:zombie");
            try {
                assertEquals(PackRenderingPhase.BLOCK_ENTITIES, ChimeraEntityBridge.writePhase(Family.ENTITY),
                        "a spawner's mob draws as the block entity's, as Iris's wrapper order gives");
            } finally {
                ChimeraEntityBridge.endContext();
            }
        } finally {
            ChimeraEntityBridge.endContext();
        }
        ChimeraEntityBridge.beginEntity("minecraft:falling_block");
        try {
            assertEquals(PackRenderingPhase.ENTITIES, ChimeraEntityBridge.writePhase(Family.MOVING_BLOCK),
                    "a falling block");
        } finally {
            ChimeraEntityBridge.endContext();
        }
    }

    @Test
    void entityMath() {
        float[] normal = new float[3];
        assertTrue(ChimeraEntityVertexData.faceNormal(normal,
                        0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f,
                        1.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f),
                "face normal rejected a valid quad");
        assertNear(0.0f, normal[0], "face normal x");
        assertNear(0.0f, normal[1], "face normal y");
        assertNear(1.0f, normal[2], "face normal z");

        int tangent = ChimeraEntityVertexData.tangent(0.0f, 0.0f, 1.0f, false,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f, 1.0f,
                0.0f, 1.0f, 1.0f, 1.0f, 1.0f);
        assertTrue(tangent != ChimeraEntityVertexData.FLAT_TANGENT,
                "tangent math used the flat fallback for a valid triangle");
        int zeroUvZ = ChimeraEntityVertexData.tangent(0.0f, 0.0f, 1.0f, false,
                        0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                        0.0f, 1.0f, 0.0f, 0.0f, 0.0f);
        assertTangentFrame(zeroUvZ, 0.0f, 0.0f, 1.0f,
                "zero-UV Z-face fallback");
        assertTangentFrame(ChimeraEntityVertexData.fallbackTangent(1.0f, 0.0f, 0.0f),
                1.0f, 0.0f, 0.0f, "X-face fallback");
        assertTangentFrame(ChimeraEntityVertexData.fallbackTangent(0.0f, 1.0f, 0.0f),
                0.0f, 1.0f, 0.0f, "Y-face fallback");
    }

    @Test
    void entityIdUniformBridge() {
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
                EntityVertexBridge.convertEntityVertex(vertex, null, fragment,
                        Map.of("entityUv", 0));
        assertTrue(plan.executable(), "entityId uniform was not treated as vertex data");
        assertTrue(convertedVertex != null, "entityId uniform vertex bridge rejected the source");
        String convertedFragment = FragmentConverter.convertEntityFragment(
                fragment, null, new int[0], convertedVertex.layout(),
                plan.effective(UniformRegistry.Stage.ENTITY));
        assertTrue(convertedFragment != null, "entityId uniform fragment bridge rejected the source");
        assertTrue(!convertedVertex.source().contains("uniform int entityId"),
                "entityId uniform remained in the vertex source");
        assertTrue(!convertedFragment.contains("uniform int entityId"),
                "entityId uniform remained in the fragment source");
        assertTrue(convertedVertex.source().contains("chimeraEntityId = EntityIds.x"),
                "entityId vertex data was not initialized");
        assertTrue(convertedFragment.contains("flat in uint chimeraEntityId"),
                "entityId vertex data was not bridged to the fragment");
    }

    /**
     * Iris EntityPatcher: blockEntityId reads iris_Entity.y whether declared as an
     * attribute (MakeUp 9.5g solid_blocks_vertex) or a uniform, beside entityId on .x.
     */
    @Test
    void blockEntityIdBridge() {
        String vertex = """
                #version 120
                #define ENTITY_PORTAL 10091.0
                attribute int blockEntityId;
                uniform int entityId;
                varying float portal;
                void main() {
                    portal = (blockEntityId == ENTITY_PORTAL || entityId == 7) ? 1.0 : 0.0;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 120
                uniform int blockEntityId;
                varying float portal;
                void main() {
                    gl_FragColor = vec4(portal, float(blockEntityId), 0.0, 1.0);
                }
                """;
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                fragment, vertex, UniformRegistry.Stage.BLOCK, null, false);
        assertTrue(plan.executable(), "blockEntityId was not treated as vertex data");
        LegacyGlslConverter.TerrainVertexConversion convertedVertex =
                EntityVertexBridge.convertEntityVertex(vertex, null, fragment, Map.of("portal", 0));
        assertTrue(convertedVertex != null, "blockEntityId vertex bridge rejected the source");
        String convertedFragment = FragmentConverter.convertEntityFragment(
                fragment, null, new int[0], convertedVertex.layout(),
                plan.effective(UniformRegistry.Stage.BLOCK));
        assertTrue(convertedFragment != null, "blockEntityId fragment bridge rejected the source");
        String converted = convertedVertex.source();
        assertTrue(!converted.contains("attribute int blockEntityId") && !converted.contains("uniform int entityId")
                        && !convertedFragment.contains("uniform int blockEntityId"),
                "entity identity declarations remained");
        assertTrue(converted.contains("chimeraBlockEntityId = EntityIds.y")
                        && converted.contains("chimeraEntityId = EntityIds.x"),
                "block entity and entity ids must read EntityIds.y and .x");
        assertTrue(convertedFragment.contains("flat in uint chimeraBlockEntityId")
                        && convertedFragment.contains("int(chimeraBlockEntityId)"),
                "blockEntityId was not bridged to the fragment");
        assertTrue(!convertedFragment.contains("chimeraEntityId)"),
                "fragment must not reference an id it never declared");

        // Terrain has no iris_Entity: the same shared vertex source reads the unbound 0.
        String terrain = """
                #version 120
                attribute vec2 mc_Entity;
                attribute int blockEntityId;
                varying float portal;
                void main() {
                    portal = (blockEntityId == 10091 || mc_Entity.x == 10091.0) ? 1.0 : 0.0;
                    gl_Position = ftransform();
                }
                """;
        String terrainFragment = """
                #version 120
                varying float portal;
                void main() { gl_FragColor = vec4(portal); }
                """;
        LegacyGlslConverter.TerrainVertexConversion convertedTerrain =
                TerrainVertexBridge.convertTerrainVertex(terrain, null, terrainFragment);
        assertTrue(convertedTerrain != null, "terrain rejected an unbound blockEntityId attribute");
        assertTrue(convertedTerrain.source().contains("const int blockEntityId = 0;")
                        && !convertedTerrain.source().contains("EntityIds"),
                "terrain blockEntityId must read GL's unbound default, not entity data");
    }

    /**
     * Iris EntityPatcher: currentRenderedItemId reads iris_Entity.z on the entity layout (Photon's
     * get_material_mask takes a dropped item's or a held item's id from it), and stays Iris's
     * CommonUniforms value, 0 by the time the batch draws, on a host layout.
     */
    @Test
    void renderedItemIdBridge(@TempDir Path shaders) throws IOException {
        String vertex = """
                #version 120
                uniform int entityId;
                uniform int currentRenderedItemId;
                flat varying int material;
                void main() {
                    int id = max(entityId - 10000, 0);
                    material = id == 100 ? max(currentRenderedItemId - 10000, 0) : id;
                    gl_Position = ftransform();
                }
                """;
        String fragment = """
                #version 120
                flat varying int material;
                void main() { gl_FragColor = vec4(float(material)); }
                """;
        UniformRegistry.ProgramInterfacePlan plan = UniformRegistry.planProgram(
                fragment, vertex, UniformRegistry.Stage.ENTITY, null, false);
        assertTrue(plan.executable(), "currentRenderedItemId was not treated as vertex data: "
                + plan.effective(UniformRegistry.Stage.ENTITY).deviations());
        LegacyGlslConverter.TerrainVertexConversion converted =
                EntityVertexBridge.convertEntityVertex(vertex, null, fragment, Map.of("material", 0));
        assertTrue(converted != null, "entity vertex bridge rejected currentRenderedItemId");
        assertTrue(converted.source().contains("chimeraRenderedItemId = EntityIds.z")
                        && converted.source().contains("int(chimeraRenderedItemId)")
                        && !converted.source().contains("uniform int currentRenderedItemId"),
                "currentRenderedItemId must read EntityIds.z: " + converted.source());

        String hostLayout = IrisCoreProfile.prepareHostLayoutIdentity(vertex);
        assertTrue(hostLayout.contains("max((0) - 10000, 0)")
                        && !hostLayout.contains("uniform int currentRenderedItemId"),
                "a host layout reads the item as 0: " + hostLayout);
        // Iris's fallback entityId uniform: per draw, the bolt's id while a lightning bolt draws.
        String lane = IrisCoreProfile.prepareHostLayoutIdentity("#version 120\nuniform int entityId;\n"
                + "uniform float blockEntityId;\nvoid main() { int id = entityId; float b = blockEntityId; }\n");
        assertTrue(lane.contains("uniform int " + UniformRegistry.HOST_ENTITY_ID + ";")
                        && lane.contains("int id = (" + UniformRegistry.HOST_ENTITY_ID + ");")
                        && lane.contains("float b = (-1.0);") && !lane.contains("uniform int entityId"),
                "a host-layout lane reads entityId per draw and blockEntityId as -1: " + lane);

        assertEquals(0, PackItemIdResolver.empty().renderedItemId("minecraft:stick"),
                "no item.properties: Iris's empty map reads 0");
        Files.writeString(shaders.resolve("item.properties"), "item.10036 = torch\n");
        PackItemIdResolver items = PackItemIdResolver.parse(shaders, Map.of()).resolver();
        assertEquals(10036, items.renderedItemId("minecraft:torch"), "listed item");
        assertEquals(PackItemIdResolver.UNMAPPED, items.renderedItemId("minecraft:stick"), "unlisted item");
    }

    private static void verifyEntityIdResolver(Path shaders) throws IOException {
        PackEntityIdResolver.ParseResult parsed = PackEntityIdResolver.parse(shaders);
        assertTrue(parsed.present(), "entity.properties was not detected");
        assertEquals(Map.of("minecraft:cow", 2, "minecraft:zombie", 1),
                parsed.resolver().mappings(), "entity map");
        assertEquals(1, parsed.resolver().resolveName("zombie"),
                "unqualified entity name");
        assertEquals(2, parsed.resolver().resolveName("minecraft:cow"),
                "namespaced entity name");
        assertEquals(0xFFFF, parsed.resolver().resolveName("pig"),
                "unknown mapped entity sentinel");
        assertEquals(-1, parsed.resolver().uniformId("pig"), "Iris's entityId uniform reads the map's -1");
        assertEquals(1, parsed.resolver().uniformId("zombie"), "a mapped entity's uniform id");
        assertEquals(0, PackEntityIdResolver.empty().uniformId("minecraft:lightning_bolt"),
                "without entity.properties Iris's empty map reads 0");
        assertEquals(0, PackEntityIdResolver.empty().resolveName("zombie"),
                "missing map default");

        Path temporary = Files.createTempDirectory("chimera-m63-entity-");
        try {
            Files.writeString(temporary.resolve("entity.properties"), "# no usable mappings\n",
                    StandardCharsets.UTF_8);
            PackEntityIdResolver.ParseResult empty = PackEntityIdResolver.parse(temporary);
            assertTrue(!empty.present(), "empty entity map was treated as usable");
            assertEquals(0, empty.resolver().resolveName("zombie"),
                    "empty entity map default");

            Files.writeString(temporary.resolve("entity.properties"),
                    "entity.1=zombie\nentity.2=zombie\nentity.70000=pig\nentity.bad=cat\n",
                    StandardCharsets.UTF_8);
            PackEntityIdResolver.ParseResult malformed = PackEntityIdResolver.parse(temporary);
            assertTrue(malformed.deviations().stream()
                            .anyMatch(value -> value.startsWith("ENTITY_ID_UNSUPPORTED:")),
                    "malformed entity map was accepted silently");
            assertTrue(!malformed.resolver().mappings().containsKey("minecraft:zombie"),
                    "conflicting entity map entry remained active");
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
                "supported report stability");
        assertEquals(first.plan().program("gbuffers_entities").convertedVertex(),
                second.plan().program("gbuffers_entities").convertedVertex(),
                "supported converted vertex stability");

        ConformanceReport report = first.report();
        ConformanceReport.ProgramReport entity = report.program("gbuffers_entities");
        assertTrue(entity != null, "entity program was not discovered");
        assertEquals(List.of("fragment", "vertex"), entity.stages(),
                "entity stages");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                entity.support(), "entity support status");
        assertTrue(report.shouldAttempt("gbuffers_entities"),
                "entity report rejected an executable program");
        assertTrue(report.deviations().contains("ENTITY_ID_MAP_APPLIED"),
                "entity map application was not reported");
        assertContains(entity.deviations(), "ENTITY_BATCH_ORIGIN_SPLIT",
                "entity batch split deviation");
        assertContains(entity.deviations(), "ENTITY_STATE_FIXED_TO_HOST",
                "host state deviation");
        assertContains(entity.deviations(), "ENTITY_VERTEX_FORMAT_EXTENDED",
                "extended format deviation");
        assertContains(entity.deviations(), "ENTITY_VERTEX_BRIDGE",
                "vertex bridge deviation");
        assertEquals(List.of("lightmap", "texture"), entity.samplers(),
                "entity sampler inventory");

        PackProgramPlan plan = first.plan().program("gbuffers_entities");
        assertTrue(plan != null && plan.executable(), "entity runtime plan rejected");
        assertEquals(UniformRegistry.Stage.ENTITY,
                plan.interfacePlan().effective(UniformRegistry.Stage.ENTITY).stage(),
                "entity interface stage");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2)),
                plan.interfacePlan().samplers(), "entity sampler slots");
        assertEquals(Map.of("entityColor", 0, "entityMarker", 1, "entityUv", 2),
                plan.varyingLocations(), "shared entity varying locations");
        assertTrue(plan.convertedVertex().contains("#version 460"),
                "entity vertex version");
        assertTrue(plan.convertedVertex().contains("layout(location = 0) out vec4 entityColor"),
                "entity vertex varying output");
        assertTrue(plan.convertedVertex().contains("EntityIds"),
                "entity ID input");
        assertTrue(plan.convertedVertex().contains("MidTexCoord"),
                "mid UV input");
        assertTrue(plan.convertedVertex().contains("Tangent"),
                "tangent input");
        assertTrue(plan.convertedVertex().contains("entityMarker"),
                "entity marker varying");
        assertTrue(!plan.convertedVertex().contains("attribute float entityId"),
                "legacy entity attribute declaration remained");
        // The host alpha-test reference gives the fragment a uniform block at
        // binding 2, so the samplers follow it, as in any entity program with uniforms.
        assertTrue(plan.convertedFragment().contains("layout(binding = 3) uniform sampler2D chimeraTexture"),
                "entity atlas binding");
        assertTrue(plan.convertedFragment().contains("layout(binding = 4) uniform sampler2D lightmap"),
                "entity lightmap binding");
        assertTrue(plan.convertedFragment().contains(UniformRegistry.ENTITY_ALPHA_REFERENCE),
                "entity program lacks the host alpha test");
        assertTrue(plan.convertedFragment().contains("layout(location = 0) in vec4 entityColor"),
                "entity fragment varying input");
        assertArrayEquals(new int[] {0, 2}, PackPipelines.entitySamplerSlots(new int[] {2, 0, 2}),
                "entity sampler slot ordering");

        verifyBaseline(report, baselinePath, fixture, baselineName);
        assertNoAbsolutePaths(report, fixture);
    }

    private static void verifyUnsupportedFixture(Path fixture, Path baselinePath) throws IOException {
        PackProbe.Analysis analysis = PackProbe.analyze(fixture);
        ConformanceReport.ProgramReport entity = analysis.report().program("gbuffers_entities");
        assertTrue(entity != null, "unsupported entity program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                entity.support(), "unsupported entity support");
        assertTrue(entity.deviations().contains("ENTITY_SAMPLER_UNSUPPORTED:unknownEntityTexture"),
                "unsupported sampler deviation");
        assertTrue(!analysis.report().shouldAttempt("gbuffers_entities"),
                "unsupported entity program remained eligible");
        assertTrue(!analysis.plan().program("gbuffers_entities").executable(),
                "unsupported entity plan remained executable");
        assertTrue(analysis.report().shouldAttempt("final"),
                "unsupported entity rejected unrelated final program");
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
        assertTrue(entry != null, "entity baseline entry is missing");
        String expectedHash = entry.get("reportSha256").getAsString();
        if (!"TO_BE_FILLED".equals(expectedHash)) {
            assertEquals(expectedHash, report.sha256(), "entity report hash");
        }
        if (entry.has("sourceHashes")) {
            Map<String, String> expected = new TreeMap<>();
            for (Map.Entry<String, JsonElement> value
                    : entry.getAsJsonObject("sourceHashes").entrySet()) {
                expected.put(value.getKey(), value.getValue().getAsString());
            }
            assertEquals(expected, sourceHashes(report), "entity source hashes");
        }
        if (entry.has("expectedExecutablePrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedExecutablePrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.SUPPORTED
                                    || value.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "executable program baseline");
        }
        if (entry.has("expectedFallbackPrograms")) {
            assertEquals(strings(entry.getAsJsonArray("expectedFallbackPrograms")),
                    report.programs().stream()
                            .filter(value -> value.support() == ConformanceReport.SupportStatus.IDENTITY_FALLBACK)
                            .map(ConformanceReport.ProgramReport::name).sorted().toList(),
                    "fallback program baseline");
        }
        if (entry.has("expectedDeviations")) {
            assertEquals(strings(entry.getAsJsonArray("expectedDeviations")),
                    allDeviations(report), "deviation baseline");
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
                "report contains an absolute fixture path");
        assertTrue(!json.matches("(?s).*\\b(?:timestamp|generatedAt)\\b.*"),
                "report contains a timestamp field");
    }

    private static void assertContains(List<String> values, String expected, String message) {
        assertTrue(values.contains(expected), message + ": " + values);
    }

    private static void assertNear(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTangentFrame(
            int tangent, float nx, float ny, float nz, String message
    ) {
        float tx = ChimeraEntityVertexData.unpack(tangent, 0);
        float ty = ChimeraEntityVertexData.unpack(tangent, 1);
        float tz = ChimeraEntityVertexData.unpack(tangent, 2);
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        float dot = tx * nx + ty * ny + tz * nz;
        assertTrue(length > 0.98f && length <= 1.01f,
                message + " tangent is not unit length: " + length);
        assertTrue(Math.abs(dot) < 0.02f,
                message + " tangent is not perpendicular: " + dot);
    }
}
