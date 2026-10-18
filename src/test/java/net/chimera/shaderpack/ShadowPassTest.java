package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ShadowCullingFrustum;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.HostLayoutLane;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shadow pass: its casters, host-layout lanes, culling and place in the frame. */
final class ShadowPassTest {
    /**
     * shadow_entities (Iris SHADOW_ENTITIES_CUTOUT): with no authored shadow_entities, the shadow
     * program converts onto the entity layout, reads the draw's host matrices, carries entityId
     * and blockEntityId per vertex, and tests alpha per draw; the pass draws entity casters
     * before the shadowtex1 snapshot and drops what it cannot serve.
     */
    @Test
    void shadowEntityCasters(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        writeLegacyShadowProgram(shaders);
        Files.writeString(shaders.resolve("shaders.properties"), """
                shadowPlayer=true
                shadowBlockEntities=0
                shadowLightBlockEntities=maybe
                """);
        var analysis = PackProbe.analyze(root);
        assertEquals("shadow", analysis.resolution().resolution("shadow_entities").selectedProgram(),
                "Iris ProgramId.ShadowEntities falls back to shadow");
        var plan = analysis.plan().program("shadow_entities");
        assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt("shadow_entities"),
                "shadow_entities rejected: " + (plan == null ? "missing" : plan.deviations()));
        assertShadowEntitiesReadEntityLayout(plan);
        String terrain = analysis.plan().program("shadow").convertedVertex();
        assertTrue(terrain.contains("MVP") && !terrain.contains("EntityIds"),
                "the terrain shadow program keeps its own inputs: " + terrain);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "shadow_entities compilation: " + compiled.failures());

        ShadowCasterDirectives directives = analysis.plan().shadowCasters();
        assertTrue(directives.entities() && directives.player() && !directives.blockEntities()
                        && !directives.lightBlockEntities()
                        && directives.deviations().contains("SHADOW_DIRECTIVE_MALFORMED:shadowLightBlockEntities"),
                "Iris reads true/1 and false/0 and keeps its default otherwise: " + directives);
        assertEquals(new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.ADVANCED, 32.0, 0.0),
                analysis.plan().shadowCulling().entities(192),
                "Iris entity frustum: advanced, BoxCuller(shadowDistance * shadowDistanceRenderMul"
                        + " * entityShadowDistanceMul)");
        assertEquals(ShadowCasterDirectives.DEFAULTS, ShadowCasterDirectives.fromProperties(Map.of()),
                "Iris defaults: entities and block entities on, player and light block entities off");
    }

    /** The shadow program converted onto the entity layout: host matrices, per-vertex ids, per-draw alpha. */
    private static void assertShadowEntitiesReadEntityLayout(PackProgramPlan plan) {
        String vertex = plan.convertedVertex();
        for (String line : List.of("layout(location = 6) in uvec4 EntityIds;",
                "return ProjMat * ModelViewMat * chimeraEntityVertexValue();",
                "transpose(inverse(mat3(ModelViewMat))) * chimeraNormalValue()",
                "TextureMat * chimeraTexCoord0Value()",
                "chimeraEntityId = EntityIds.x;", "chimeraBlockEntityId = EntityIds.y;",
                "layout(binding = 2) uniform ChimeraShadowUniforms",
                "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);")) {
            assertTrue(vertex.contains(line), "shadow_entities vertex lost " + line + ": " + vertex);
        }
        assertTrue(!vertex.contains("MVP") && !vertex.contains("inPositionLight")
                        && !vertex.contains("chimeraShadowUniforms.entityId"),
                "shadow_entities must not read terrain inputs or a uniform entityId: " + vertex);
        String fragment = plan.convertedFragment();
        assertTrue(fragment.contains("flat in uint chimeraBlockEntityId")
                        && fragment.contains("int(chimeraBlockEntityId) / 100 == 155")
                        && fragment.contains("chimeraShadowColor1")
                        && plan.alphaTestPlan().perDrawReference(),
                "shadow_entities fragment must read blockEntityId per vertex and test alpha per draw: " + fragment);
    }

    @Test
    void shadowEntityCastersDrawBeforeSnapshot() throws IOException {
        var shadowPass = MixinBytecode.nativeClass("net/chimera/render/PackShadowPass");
        boolean casters = false;
        boolean snapshotAfterCasters = false;
        for (var instruction : MixinBytecode.instructionsThrough(shadowPass, "renderShadowMap")) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("renderShadowCasters")) casters = true;
                if (casters && call.name.equals("captureOpaqueDepth")) snapshotAfterCasters = true;
            }
        }
        assertTrue(snapshotAfterCasters, "entity casters must draw before the shadowtex1 snapshot, as in Iris");
    }

    /**
     * A shadow-pass draw tries its shadow lane first; one without a lane is dropped, never drawn with
     * its host shader.
     */
    @Test
    void shadowDrawWithoutLaneIsDropped() throws IOException {
        var draws = MixinBytecode.nativeClass("net/chimera/render/shader/PackHostDraws");
        boolean lane = false;
        boolean dropped = false;
        for (var instruction : MixinBytecode.instructionsThrough(draws, "draw")) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("hostLaneFor")) lane = true;
                if (lane && call.name.equals("dropsHostDraw")) dropped = true;
            }
        }
        assertTrue(dropped, "a shadow-pass draw tries its shadow lane first; one without an installed lane "
                + "must be dropped, not drawn with its host shader");
    }

    /**
     * The shadow pass's host-layout lanes (Iris IrisPipelines.assignToShadow): each key runs the
     * Iris program it names through the fallback chain, never a pack file of its own name, on its
     * host layout with Iris's inputs. Leads, lines, lightning, text backgrounds and the water mask
     * read entityId per draw and blockEntityId -1; glint and text read the unbound per-vertex 0.
     * Lines are widened. Every lane tests alpha per draw and compiles.
     */
    @Test
    void shadowLaneRoutes() {
        var shadowPass = HostLayoutLane.Pass.SHADOW;
        record Draw(String shader, VertexFormat format) {}
        Map<Draw, HostLayoutLane> routes = Map.of(
                new Draw("rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL), HostLayoutLane.SHADOW_LINES,
                new Draw("rendertype_leash", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP), HostLayoutLane.SHADOW_LEASH,
                new Draw("rendertype_lightning", DefaultVertexFormat.POSITION_COLOR), HostLayoutLane.SHADOW_LIGHTNING,
                new Draw("rendertype_glint", DefaultVertexFormat.POSITION_TEX), HostLayoutLane.SHADOW_GLINT,
                new Draw("rendertype_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                HostLayoutLane.SHADOW_TEXT,
                new Draw("rendertype_text_see_through", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP),
                HostLayoutLane.SHADOW_TEXT,
                new Draw("rendertype_text_background", DefaultVertexFormat.POSITION_COLOR_LIGHTMAP),
                HostLayoutLane.SHADOW_TEXT_BG,
                new Draw("rendertype_water_mask", DefaultVertexFormat.POSITION), HostLayoutLane.SHADOW_BASIC);
        for (var route : routes.entrySet()) {
            assertTrue(HostLayoutLane.forDraw(route.getKey().shader(), route.getKey().format(), shadowPass)
                            == route.getValue(),
                    route.getKey().shader() + " must route to " + route.getValue() + " in the shadow pass");
        }
        // Iris cancels beacon beams in the shadow pass: they stay out of the map.
        assertTrue(HostLayoutLane.forDraw("rendertype_beacon_beam", DefaultVertexFormat.BLOCK, shadowPass) == null,
                "beacon beams have no shadow lane");
        assertEquals(0.0001F, HostLayoutLane.SHADOW_TEXT.alphaReference(),
                "Iris SHADOW_TEXT: NON_ZERO_ALPHA");
        assertEquals(0.0001F, HostLayoutLane.SHADOW_TEXT_BG.alphaReference(),
                "Iris SHADOW_TEXT_BG: NON_ZERO_ALPHA");
        assertEquals(0.1F, HostLayoutLane.SHADOW_GLINT.alphaReference(),
                "Iris GLINT in the shadow pass: SHADOW_ENTITIES_CUTOUT, ONE_TENTH_ALPHA");
        for (var lane : List.of(HostLayoutLane.SHADOW_LINES,
                HostLayoutLane.SHADOW_LEASH,
                HostLayoutLane.SHADOW_LIGHTNING,
                HostLayoutLane.SHADOW_BASIC)) {
            assertEquals(0.0F, lane.alphaReference(), lane + ": Iris AlphaTests.OFF");
        }
    }

    @Test
    void shadowLanes(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        writeLegacyShadowProgram(shaders);
        // A pack file named like a shader key is not an Iris program and is never selected.
        Files.writeString(shaders.resolve("shadow_text.vsh"), "#version 120\nvoid main() { decoy(); }\n");
        Files.writeString(shaders.resolve("shadow_text.fsh"), "#version 120\nvoid main() { decoy(); }\n");
        var analysis = PackProbe.analyze(root);
        Map<String, List<String>> inputs = Map.of(
                "shadow_lines", List.of("#define chimeraLineWidth LineWidth", "void chimeraLineMain()",
                        "chimeraLineViewScale", "chimeraShadowUniforms.viewWidth"),
                "shadow_leash", List.of("layout(location = 2) in ivec2 UV2;", "const vec2 UV0 = vec2(0.5);"),
                "shadow_lightning", List.of("layout(location = 1) in vec4 Color;", "const ivec2 UV2 = ivec2(240);"),
                "shadow_text_bg", List.of("layout(location = 2) in ivec2 UV2;", "const vec2 UV0 = vec2(0.5);"),
                "shadow_basic", List.of("const vec4 Color = vec4(1.0);", "const ivec2 UV2 = ivec2(240);"),
                "shadow_text", List.of("layout(location = 2) in vec2 UV0;", "layout(location = 3) in ivec2 UV2;"),
                "shadow_glint", List.of("layout(location = 1) in vec2 UV0;",
                        "const vec4 Color = vec4(0.0, 0.0, 0.0, 1.0);"));
        for (var entry : inputs.entrySet()) {
            assertShadowLaneProgram(analysis, entry.getKey(), entry.getValue());
        }
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "shadow lane compilation: " + compiled.failures());

        // An authored shadow_entities serves the ShadowEntities keys; the Shadow keys keep shadow.
        Files.copy(shaders.resolve("shadow.vsh"), shaders.resolve("shadow_entities.vsh"));
        Files.copy(shaders.resolve("shadow.fsh"), shaders.resolve("shadow_entities.fsh"));
        var authored = PackProbe.analyze(root).resolution();
        for (String key : List.of("shadow_lines", "shadow_leash", "shadow_basic")) {
            assertEquals("shadow", authored.resolution(key).selectedProgram(), key + " is Iris ProgramId.Shadow");
        }
        for (String key : List.of("shadow_text", "shadow_text_bg", "shadow_glint", "shadow_lightning")) {
            assertEquals("shadow_entities", authored.resolution(key).selectedProgram(),
                    key + " is Iris ProgramId.ShadowEntities (shadow_lightning falls back to it)");
        }
    }

    /** One shadow lane runs the shadow program on its host layout with Iris's inputs, ids and alpha test. */
    private static void assertShadowLaneProgram(PackProbe.Analysis analysis, String key, List<String> inputs) {
        var plan = analysis.plan().program(key);
        assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt(key),
                key + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        assertEquals("shadow", analysis.resolution().resolution(key).selectedProgram(),
                key + " must run Iris's shadow program through its fallback chain");
        String vertex = plan.convertedVertex();
        for (String line : inputs) {
            assertTrue(vertex.contains(line), key + " lost Iris host input " + line + ": " + vertex);
        }
        for (String line : List.of("return Color * ColorModulator;",
                "return ProjMat * ModelViewMat * chimeraEntityVertexValue();",
                "layout(binding = 2) uniform ChimeraShadowUniforms",
                "gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);")) {
            assertTrue(vertex.contains(line), key + " lost the shadow bridge " + line + ": " + vertex);
        }
        assertTrue(!vertex.contains("decoy") && !vertex.contains("MVP") && !vertex.contains("inPositionLight"),
                key + " must convert the shadow program on its host layout: " + vertex);
        String fragment = plan.convertedFragment();
        assertShadowLaneIds(key, vertex, fragment);
        assertTrue(fragment.contains("chimeraShadowColor1") && plan.alphaTestPlan().perDrawReference(),
                key + " must write both shadow colours and test alpha per draw: " + fragment);
        assertTrue(plan.deviations().contains(FamilyAdapterRegistry.hostLayoutLane(key) + "_VERTEX_BRIDGE"),
                key + " must record its vertex bridge: " + plan.deviations());
    }

    /** Glint and text read the unbound per-vertex ids; every other lane reads entityId 0, blockEntityId -1. */
    private static void assertShadowLaneIds(String key, String vertex, String fragment) {
        if (FamilyAdapterRegistry.readsEntityIdsPerVertex(key)) {
            assertTrue(vertex.contains("const uvec4 EntityIds = uvec4(0);")
                            && vertex.contains("chimeraEntityId = EntityIds.x;")
                            && fragment.contains("int(chimeraBlockEntityId) / 100 == 155"),
                    key + " reads Iris's entity-patched ids, unbound on the host mesh: " + vertex);
        } else {
            assertTrue(vertex.contains("chimeraHostEntityId) == 10001")
                            && !vertex.contains("chimeraEntityId")
                            && fragment.contains("(-1) / 100 == 155"),
                    key + " must read the per-draw entityId and blockEntityId -1: " + vertex + "\n" + fragment);
        }
    }

    /** Burning-entity fire draws through the entity family, its block-sheet cutout, with Iris's entity_flame id. */
    @Test
    void burningEntityFireDrawsThroughEntityFamily() throws IOException {
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraEntityRenderDispatcherMixin");
        assertTrue(MixinBytecode.injects(mixin, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
                        "render", "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderFlame("
                                + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                                + "Lnet/minecraft/client/renderer/MultiBufferSource;"
                                + "Lnet/minecraft/world/entity/Entity;Lorg/joml/Quaternionf;)V"),
                "burning-entity fire must be wrapped where the dispatcher draws it");
        assertTrue(MixinBytecode.instructionsThrough(mixin, "chimera$flameId").stream().anyMatch(instruction ->
                        instruction instanceof MethodInsnNode call && call.name.equals("flameEntityId")),
                "burning-entity fire must draw with Iris's entity_flame id");
        assertEquals(ChimeraEntityBridge.Family.ENTITY, ChimeraEntityBridge.worldFamily("rendertype_entity_cutout",
                DefaultVertexFormat.NEW_ENTITY, ChimeraEntityBridge.Family.ENTITY), "fire's cutout is an entity draw");
    }

    /** Iris ShadowRenderer.createShadowFrustum: the culling each shadow.culling state picks, with its distances. */
    @Test
    void shadowCulling() {
        int renderDistance = 192;
        Map<String, String> unitRender = Map.of("shadowDistanceRenderMul", "1.0");
        var advanced = ShadowCullingPlan.Mode.ADVANCED;
        double none = Double.POSITIVE_INFINITY;

        ShadowCullingPlan unset = cullingPlan(Map.of(), unitRender);
        assertEquals(ShadowCullingPlan.State.DEFAULT, unset.state(), "no shadow.culling is Iris DEFAULT");
        assertEquals(new ShadowCullingPlan.Culler(advanced, 128.0, 0.0), unset.terrain(renderDistance),
                "DEFAULT without a geometry stage is advanced, BoxCuller(shadowDistance * renderMul)");
        assertEquals(unset.terrain(renderDistance), unset.entities(renderDistance),
                "entityShadowDistanceMul 1 reuses the terrain frustum");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0), unset.terrain(96),
                "a box at or past the render distance is no box");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0),
                cullingPlan(Map.of(), Map.of()).terrain(renderDistance),
                "renderMul unset (Iris -1) means the player's shadow distance, 32 chunks by default");
        assertEquals(new ShadowCullingPlan.Culler(advanced, 0.0, 0.0),
                cullingPlan(Map.of(), Map.of("shadowDistanceRenderMul", "0.0")).terrain(renderDistance),
                "a zero distance keeps Iris's BoxCuller(0)");
        assertEquals(unset.terrain(renderDistance),
                cullingPlan(Map.of("shadow.culling", "true"), unitRender).terrain(renderDistance), "true is ADVANCED");

        ShadowCullingPlan distance = cullingPlan(Map.of("shadow.culling", "false"), unitRender);
        assertEquals(new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.DISTANCE, 128.0, 0.0),
                distance.terrain(renderDistance), "false culls by distance only");
        assertEquals(ShadowCullingPlan.Culler.NONE, distance.terrain(96),
                "a distance past the render distance culls nothing");
        assertEquals(ShadowCullingPlan.Culler.NONE,
                cullingPlan(Map.of("shadow.culling", "false"), Map.of()).terrain(renderDistance),
                "a negative distance culls nothing");

        ShadowCullingPlan safeZone = cullingPlan(Map.of("shadow.culling", "reversed"), Map.of("voxelDistance", "32.0"));
        assertEquals(new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.SAFE_ZONE, 128.0, 32.0),
                safeZone.terrain(renderDistance),
                "reversed: voxelDistance box always drawn, outer box shadowDistance (renderMul -1 taken as 1)");
        assertEquals(safeZone.terrain(renderDistance),
                cullingPlan(Map.of("shadow.culling", "safe_zone"),
                        Map.of("voxelDistance", "32.0")).terrain(renderDistance),
                "safe_zone is reversed");

        assertEquals(new ShadowCullingPlan.Culler(advanced, 32.0, 0.0),
                cullingPlan(Map.of(), Map.of("shadowDistanceRenderMul", "1.0", "entityShadowDistanceMul", "0.25"))
                        .entities(renderDistance), "entities: BoxCuller(shadowDistance * renderMul * entityMul)");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0),
                cullingPlan(Map.of(), Map.of("entityShadowDistanceMul", "0.25")).entities(renderDistance),
                "a negative entity multiplier product is the player's shadow distance");
        ShadowCullingPlan malformed = cullingPlan(Map.of("shadow.culling", "maybe"), unitRender);
        assertTrue(malformed.state() == ShadowCullingPlan.State.DEFAULT
                        && malformed.deviations().contains("SHADOW_CULLING_DIRECTIVE_MALFORMED:maybe"),
                "Iris keeps the default for an unrecognized value: " + malformed);
    }

    /** The shadow culling plan for these shaders.properties entries and shadow constants. */
    private static ShadowCullingPlan cullingPlan(Map<String, String> properties, Map<String, String> constants) {
        return ShadowCullingPlan.of(properties, new PackConfig.ShadowSettings(2048, 128.0F, constants, List.of()));
    }

    /** The advanced shadow frustum's planes, box and safe zone against known casters. */
    @Test
    void shadowCullingFrustum() {
        var advanced = ShadowCullingPlan.Mode.ADVANCED;
        double none = Double.POSITIVE_INFINITY;
        ShadowCullingPlan safeZone = cullingPlan(Map.of("shadow.culling", "reversed"), Map.of("voxelDistance", "32.0"));
        // A camera at the origin looking down -Z, 70 degree vertical field, 16:9; planes only.
        Matrix4f camera = new Matrix4f()
                .perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 1000.0F);
        var open = new ShadowCullingPlan.Culler(advanced, none, 0.0);
        var overhead = new ShadowCullingFrustum(open, camera,
                new Vector3f(0.0F, 1.0F, 0.0F));
        assertTrue(cube(overhead, 0, 0, -20), "a visible caster is kept");
        assertTrue(cube(overhead, 0, 50, -20), "a caster above the view shadows what the camera sees");
        assertTrue(!cube(overhead, 0, 50, 20),
                "with the sun overhead, a caster behind the camera shadows nothing seen");
        assertTrue(!cube(overhead, 0, -100, -20), "a caster below the view shadows nothing seen");
        var side = new ShadowCullingFrustum(open, camera,
                new Vector3f(1.0F, 0.0F, 0.0F));
        assertTrue(cube(side, 200, 0, -20), "a caster on the light's side shadows into the view");
        assertTrue(!cube(side, -200, 0, -20), "a caster on the far side of the view shadows nothing seen");
        var oblique = new ShadowCullingFrustum(open, camera,
                new Vector3f(1.0F, 1.0F, 0.0F).normalize());
        assertTrue(cube(oblique, 30, 30, -20), "the light's ray from this caster crosses the view");
        assertTrue(!cube(oblique, -30, -30, -20), "the light's ray from this caster leaves the view");
        var boxed = new ShadowCullingFrustum(
                new ShadowCullingPlan.Culler(advanced, 64.0, 0.0), camera, new Vector3f(1.0F, 0.0F, 0.0F));
        assertTrue(!cube(boxed, 100, 0, -20), "the BoxCuller still bounds the advanced frustum");
        var zone = new ShadowCullingFrustum(safeZone.terrain(1024), camera,
                new Vector3f(1.0F, 0.0F, 0.0F));
        assertTrue(cube(zone, -20, 0, -20) && zone.forced(-20, 0, -20, -19, 1, -19),
                "a caster in the safe zone is drawn whatever the view");
        assertTrue(!cube(zone, -60, 0, -20) && !zone.forced(-60, 0, -20, -59, 1, -19),
                "outside the safe zone the advanced planes decide");
        assertTrue(!cube(zone, 200, 0, -20), "the outer box bounds the safe zone frustum");
        var off = new ShadowCullingFrustum(ShadowCullingPlan.Culler.NONE, camera,
                new Vector3f(1.0F, 0.0F, 0.0F));
        assertTrue(cube(off, -200, 0, -20) && !off.forced(0, 0, 0, 1, 1, 1), "no culling keeps everything");
    }

    static boolean cube(ShadowCullingFrustum frustum, float x, float y, float z) {
        return frustum.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
    }

    /** Iris renders shadows before any terrain: the shadow segment runs before the opaque layer draws. */
    @Test
    void shadowPassOrder() throws IOException {
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/WorldRendererMixin");
        assertTrue(MixinBytecode.injects(mixin, "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
                "renderSectionLayer", null), "the terrain layers must be wrapped");
        var hook = mixin.methods.stream().filter(method -> method.name.equals("chimera$terrainLayer")).findFirst()
                .orElseThrow();
        int shadow = -1;
        int layer = -1;
        int index = 0;
        for (var instruction : hook.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("renderShadowSegment") && shadow < 0) shadow = index;
                if (shadow >= 0 && layer < 0 && call.name.equals("call")
                        && call.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/Operation")) {
                    layer = index;
                }
            }
            index++;
        }
        assertTrue(shadow >= 0 && layer > shadow, "the shadow map must render before the opaque layer samples it");
    }

    /** BSL-style legacy shadow program: terrain attributes, both identities and both shadow colours. */
    static void writeLegacyShadowProgram(Path shaders) throws IOException {
        Files.writeString(shaders.resolve("shadow.vsh"), """
                    #version 120
                    attribute vec4 mc_Entity;
                    attribute vec4 mc_midTexCoord;
                    attribute vec3 at_midBlock;
                    uniform int entityId;
                    uniform mat4 shadowModelView;
                    uniform mat4 shadowModelViewInverse;
                    uniform mat4 shadowProjection;
                    uniform mat4 shadowProjectionInverse;
                    const float entityShadowDistanceMul = 0.25;
                    const float shadowDistanceRenderMul = 1.0;
                    const float shadowDistance = 128.0;
                    varying vec2 texCoord;
                    varying vec4 color;
                    varying float mat;
                    void main() {
                        texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                        color = gl_Color;
                        mat = mc_Entity.x + at_midBlock.x + mc_midTexCoord.y;
                        vec3 normal = gl_NormalMatrix * gl_Normal;
                        vec4 position = shadowModelViewInverse * shadowProjectionInverse * ftransform();
                        if (entityId == 10001) position.y += normal.y * 0.0;
                        gl_Position = shadowProjection * shadowModelView * position;
                    }
                    """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                    #version 120
                    /* DRAWBUFFERS:01 */
                    uniform sampler2D tex;
                    uniform int blockEntityId;
                    varying vec2 texCoord;
                    varying vec4 color;
                    varying float mat;
                    void main() {
                        if (blockEntityId / 100 == 155) discard;
                        gl_FragData[0] = texture2D(tex, texCoord) * color;
                        gl_FragData[1] = vec4(mat);
                    }
                    """);
    }
}
