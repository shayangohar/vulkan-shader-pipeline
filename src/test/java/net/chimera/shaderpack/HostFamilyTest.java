package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.HostAlphaTest;
import net.chimera.render.shader.HostLayoutLane;
import net.chimera.render.vertex.ChimeraVertexFormats;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Host program families: requested-family fallback, particles, lines, glint, host-layout lanes and hands. */
final class HostFamilyTest {
    /** The line lane's fragment stage, shared by the gbuffers_basic fallback and an authored gbuffers_line. */
    private static final String LINE_FRAGMENT = """
            #version 120
            /* DRAWBUFFERS:0 */
            varying vec4 tint;
            varying vec3 normal;
            void main() {
                gl_FragData[0] = tint + vec4(normal * 0.001, 0.0);
            }
            """;

    /** Hand variants are named by position (pack_gbuffers_hand_N), so the order must not change per run. */
    @Test
    void handFormatOrderIsFixed() {
        assertEquals(List.of(DefaultVertexFormat.NEW_ENTITY, DefaultVertexFormat.PARTICLE,
                        DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, DefaultVertexFormat.POSITION_TEX_COLOR),
                List.copyOf(ChimeraVertexFormats.handFormats().keySet()),
                "hand formats keep declaration order: native model, item quad, map text, map background");
    }

    /** Only a layout with UV1 binds an overlay; Iris serves the others entityColor 0 (name tags read slot 1). */
    @Test
    void overlayFetchOnlyWhereTheLayoutHasUv1() {
        String fragment = EntityOverlayColor.inject(
                "#version 120\nuniform vec4 entityColor;\nvoid main() { gl_FragColor = entityColor; }\n");
        for (var entry : ChimeraVertexFormats.handFormats().entrySet()) {
            boolean entity = entry.getKey() == DefaultVertexFormat.NEW_ENTITY;
            for (var format : List.of(entry.getKey(), entry.getValue())) {
                assertEquals(entity, EntityVertexBridge.fragmentForFormat(fragment, format)
                        .contains("texelFetch(" + PackResourcePlan.OVERLAY_SAMPLER), "overlay fetch on " + format);
            }
        }
    }

    /**
     * Iris serves gl_NormalMatrix as the model view's inverse transpose on every lane. Nausea and
     * the portal warp stretch the model view, and mat3 of it then tilts the normals packs read.
     */
    @Test
    void normalMatrixIsTheInverseTranspose() {
        assertEquals("transpose(inverse(mat3(gbufferModelView)))",
                TerrainVertexBridge.modernTerrainInputReplacements("").get("gl_NormalMatrix"), "terrain and water");
        assertEquals("transpose(inverse(mat3(ModelViewMat)))",
                EntityVertexBridge.entityShadowMatrixBuiltinReplacements().get("gl_NormalMatrix"), "shadow entities");
        String vertex = "#version 120\nvarying vec3 normal;\n"
                + "void main() { normal = gl_NormalMatrix * gl_Normal; gl_Position = ftransform(); }\n";
        String fragment = "#version 120\nvarying vec3 normal;\nvoid main() { gl_FragColor = vec4(normal, 1); }\n";
        var entity = EntityVertexBridge.convertEntityVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        assertTrue(entity.source().contains("normal = transpose(inverse(mat3(ModelViewMat))) * Normal.xyz;"),
                "entities: " + entity.source());
    }

    @Test
    void handInputsAndProjection() {
        String vertex = """
                #version 120
                attribute vec4 at_tangent;
                attribute vec4 mc_midTexCoord;
                varying vec2 uv;
                varying vec3 normal;
                void main() {
                    mat4 mvp = gl_ModelViewProjectionMatrix;
                    uv = (gl_TextureMatrix[0] * mc_midTexCoord).xy;
                    normal = gl_NormalMatrix * gl_Normal + at_tangent.xyz * 0.001;
                    gl_Position = mvp * gl_Vertex;
                    if (gl_Color.a == 0.0) return;
                }
                """;
        String fragment = "#version 120\nvarying vec2 uv;\nvarying vec3 normal;\n"
                + "void main() { gl_FragColor = vec4(uv, normal.z, 1); }\n";
        var hand = EntityVertexBridge.convertHandVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        for (var entry : ChimeraVertexFormats.handFormats().entrySet()) {
            var host = entry.getKey();
            var extended = entry.getValue();
            assertEquals(host, ChimeraVertexFormats.handHostFormat(extended), "hand variant host lookup");
            boolean computedNormal = ChimeraVertexFormats.computesNormal(extended);
            assertEquals(host.getVertexSize() + (computedNormal ? 24 : 20), extended.getVertexSize(),
                    "hand prefix plus 20-byte material inputs (and a 4-byte GLYPH normal)");
            for (int i = 0; i < host.getElements().size(); i++) {
                assertEquals(host.getOffset(host.getElements().get(i)), extended.getOffset(host.getElements().get(i)),
                        "hand native attribute offset");
            }
            String source = EntityVertexBridge.handVertexForFormat(hand.source(), host);
            assertTrue(PackCompileTest.compile(source, true) == null, "hand layout fails compilation: " + host);
            assertTrue(source.contains("void chimeraHandMain()")
                            && source.contains("void main() { chimeraHandMain();"
                                    + " gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }"),
                    "authored hand returns must still reach exactly one Vulkan clip conversion");
            if (computedNormal) {
                // Iris GLYPH: text has a per-quad normal, ahead of the material inputs.
                assertTrue(source.contains("layout(location = 4) in vec3 Normal;")
                                && source.contains("layout(location = 5) in uvec4 EntityIds;")
                                && source.contains("const ivec2 UV1 = ivec2(0);"), "GLYPH text inputs");
            } else if (host != DefaultVertexFormat.NEW_ENTITY) {
                assertTrue(source.contains("const vec3 Normal = vec3(0.0, 0.0, 1.0);")
                                && source.contains("const ivec2 UV1 = ivec2(0);"),
                                        "Iris missing normal/overlay defaults");
            }
        }
        // World and sign text: the entity and block programs take the same GLYPH layout.
        var entity = EntityVertexBridge.convertEntityVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        var block = EntityVertexBridge.convertBlockVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        for (var program : List.of(entity, block)) {
            String glyph = EntityVertexBridge.layoutVertexForFormat(program.source(),
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            assertTrue(PackCompileTest.compile(glyph, true) == null, "entity-family GLYPH layout fails compilation");
            assertTrue(glyph.contains("layout(location = 4) in vec3 Normal;")
                    && glyph.contains("layout(location = 7) in vec4 ChimeraPackedTangent;"),
                            "entity-family GLYPH inputs");
        }
        var raster = new Matrix4f().perspective(1.1F, 1.6F, 0.05F, 128F, true);
        var snapshot = new Matrix4f(raster);
        var drawn = ChimeraHandRenderer.projection(raster);
        var iris = new Matrix4f().scale(1, 1, ChimeraHandRenderer.DEPTH)
                .mul(new Matrix4f().perspective(1.1F, 1.6F, 0.05F, 128F, false));
        for (float z : new float[]{-0.05F, -0.5F, -1.0F, -128F}) {
            var point = new Vector4f(0.02F, -0.03F, z, 1);
            var actual = drawn.transform(new Vector4f(point));
            var expected = iris.transform(new Vector4f(point));
            float depth = actual.z / actual.w;
            assertTrue(Math.abs(depth - (0.5F * (expected.z / expected.w + 1))) < 1.0e-6F,
                    "hand Vulkan depth must equal Iris window depth at z=" + z);
            assertTrue(depth >= 0.43749F && depth <= 0.56251F, "hand depth squeeze range");
            assertTrue(Math.abs(actual.x - expected.x) < 1.0e-6F && Math.abs(actual.y - expected.y) < 1.0e-6F,
                    "hand projection must not change screen placement");
        }
        assertEquals(snapshot, raster, "hand projection must not mutate world matrices");
    }

    /**
     * Falling blocks and moving pistons: gbuffers_block on the block layout, as Iris draws MOVING_BLOCK
     * on IrisVertexFormats.TERRAIN with mc_Entity (-1, -1) and no overlay.
     */
    @Test
    void movingBlockLayout() {
        String vertex = """
                #version 120
                attribute vec4 mc_Entity;
                attribute vec4 at_tangent;
                varying vec4 tint;
                void main() {
                    tint = vec4(mc_Entity.xy, at_tangent.w, 1.0) * gl_Color;
                    gl_Position = ftransform();
                }
                """;
        String fragment = "#version 120\nvarying vec4 tint;\nvoid main() { gl_FragColor = tint; }\n";
        var block = EntityVertexBridge.convertBlockVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        String moving = EntityVertexBridge.layoutVertexForFormat(block.source(), DefaultVertexFormat.BLOCK);
        assertTrue(PackCompileTest.compile(moving, true) == null, "moving-block layout fails compilation: " + moving);
        assertTrue(moving.contains("layout(location = 3) in ivec2 UV2;")
                        && moving.contains("layout(location = 4) in vec3 Normal;")
                        && moving.contains("layout(location = 5) in uvec4 EntityIds;")
                        && moving.contains("const ivec2 UV1 = ivec2(0);"), "moving-block inputs: " + moving);
        assertTrue(moving.contains("const vec4 mc_Entity = vec4(-1.0, -1.0, 0.0, 1.0);")
                        && block.source().contains("const vec4 mc_Entity = vec4(0.0, 0.0, 0.0, 1.0);"),
                "mc_Entity reads (-1, -1) on the block layout and the unbound default on the entity layout");
        // A shadow program reads mc_Entity through its preamble's helper.
        assertTrue(EntityVertexBridge.movingBlockEntity(GlslVertexPreambles.ENTITY_SHADOW_VERTEX_PREAMBLE)
                        .matches("(?s).*vec2 chimeraMcEntityValue\\(\\) \\{\\s*return vec2\\(-1\\.0\\);.*"),
                "shadow casters read mc_Entity (-1, -1) on the block layout");
        var extended = ChimeraVertexFormats.EXTENDED_BLOCK;
        assertEquals(DefaultVertexFormat.BLOCK.getVertexSize() + 20, extended.getVertexSize(),
                "block prefix plus 20-byte material inputs");
        assertEquals(extended, ChimeraVertexFormats.extendedLayout(DefaultVertexFormat.BLOCK));
        assertTrue(ChimeraVertexFormats.isFamilyFormat(extended), "only a family lane writes the block layout");
    }

    /** Iris 1.21.1's AlphaTests per ShaderKey, for the entity-format and moving-block keys. */
    @Test
    void entityAlphaReferences() {
        var eyes = "rendertype_eyes";
        var solid = "rendertype_entity_solid";
        assertEquals(0.0001F, HostAlphaTest.reference(eyes, ChimeraEntityBridge.Family.SPIDER_EYES, false),
                "ENTITIES_EYES tests NON_ZERO_ALPHA");
        assertEquals(0.1F, HostAlphaTest.reference("rendertype_entity_translucent_emissive",
                ChimeraEntityBridge.Family.SPIDER_EYES, false), "ENTITIES_EYES_TRANS tests ONE_TENTH_ALPHA");
        assertEquals(0.1F, HostAlphaTest.reference(eyes, ChimeraEntityBridge.Family.BLOCK, false),
                "a block entity's eyes are BLOCK_ENTITY, ONE_TENTH_ALPHA");
        assertEquals(0.0F, HostAlphaTest.reference(solid, ChimeraEntityBridge.Family.ENTITY, false),
                "ENTITIES_SOLID_DIFFUSE draws untested");
        assertEquals(0.1F, HostAlphaTest.reference(solid, ChimeraEntityBridge.Family.BLOCK, false),
                "BLOCK_ENTITY_DIFFUSE tests ONE_TENTH_ALPHA");
        assertEquals(-1.0F, HostAlphaTest.reference("rendertype_entity_alpha", ChimeraEntityBridge.Family.ENTITY,
                false), "ENTITIES_ALPHA tests the vertex alpha (AlphaTests.VERTEX_ALPHA)");
        assertEquals(0.1F, HostAlphaTest.reference("rendertype_energy_swirl", ChimeraEntityBridge.Family.ENTITY, false),
                "ENTITIES_CUTOUT tests ONE_TENTH_ALPHA");
        assertEquals(0.1F, HostAlphaTest.reference("rendertype_solid", ChimeraEntityBridge.Family.MOVING_BLOCK, false),
                "MOVING_BLOCK tests ONE_TENTH_ALPHA");
        assertEquals(0.1F, HostAlphaTest.reference(solid, ChimeraEntityBridge.Family.SHADOW_BLOCK, true),
                "SHADOW_BLOCK tests ONE_TENTH_ALPHA");
    }

    /**
     * The opaque hand draws where renderLevel enters its "translucent" section, before translucent
     * terrain in both of its branches (Iris draws it there too).
     */
    @Test
    void opaqueHandSeamPrecedesTranslucency() throws IOException {
        var level = MixinBytecode.nativeClass("net/minecraft/client/renderer/LevelRenderer").methods.stream()
                .filter(method -> method.name.equals("renderLevel")).findFirst().orElseThrow();
        int seams = 0;
        boolean open = false;
        for (var instruction : level.instructions) {
            if (instruction instanceof LdcInsnNode constant && "translucent".equals(constant.cst)) {
                open = true;
            } else if (open && instruction instanceof MethodInsnNode call && call.name.equals("translucent")
                    && call.owner.equals("net/minecraft/client/renderer/RenderType")) {
                seams++;
                open = false;
            }
        }
        assertEquals(2, seams, "each translucent section must open before its translucent terrain layer");
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraLevelRendererMixin");
        boolean hooked = mixin.methods.stream()
                .flatMap(method -> MixinBytecode.annotations(method.visibleAnnotations, method.invisibleAnnotations)
                        .stream())
                .filter(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;"))
                .flatMap(annotation -> ((List<?>) MixinBytecode.annotationValue(annotation, "at")).stream())
                .anyMatch(at -> at instanceof AnnotationNode node
                        && "CONSTANT".equals(MixinBytecode.annotationValue(node, "value"))
                        && MixinBytecode.annotationValue(node, "args") instanceof List<?> args
                        && args.contains("stringValue=translucent"));
        assertTrue(hooked, "the opaque hand must draw at the translucent section");
    }

    /**
     * Every pack draw opens its program's attachment window around the host's own draw call: host
     * meshes from the immediate and vertex-buffer paths (PackHostDraws) and buffered family batches.
     */
    @Test
    void packDrawsOwnTheirAttachments() throws IOException {
        for (String mixin : List.of("ChimeraBufferUploaderMixin", "ChimeraVertexBufferMixin")) {
            var node = MixinBytecode.nativeClass("net/chimera/mixin/" + mixin);
            assertTrue(MixinBytecode.instructionsThrough(node, "chimera$packProgram").stream().anyMatch(instruction ->
                            instruction instanceof MethodInsnNode call && call.name.equals("draw")
                                    && call.owner.equals("net/chimera/render/shader/PackHostDraws")),
                    mixin + " must draw through PackHostDraws");
        }
        assertOwnsWindow("net/chimera/render/shader/PackHostDraws", "draw");
        assertOwnsWindow("net/chimera/render/PackFamilyWindows", "drawFamilyBatches");
    }

    private static void assertOwnsWindow(String owner, String method) throws IOException {
        boolean begin = false;
        boolean end = false;
        for (var instruction : MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(owner), method)) {
            if (instruction instanceof MethodInsnNode call) {
                begin |= call.name.equals("beginPackFamilyWindow");
                end |= call.name.equals("endPackFamilyWindow");
            }
        }
        assertTrue(begin && end, owner + "." + method + " must open and close its pack window");
    }

    @Test
    void requestedFamilyFallbackAndCrumbling(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        List<String> names = List.of("gbuffers_entities", "gbuffers_entities_translucent",
                "gbuffers_textured_lit", "gbuffers_damagedblock", "final");
        String vertex = "#version 120\nvarying vec2 uv;\nvoid main() {"
                + " uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;"
                + " gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }\n";
        String fragment = "#version 120\n/* DRAWBUFFERS:1 */\nvarying vec2 uv;"
                + " uniform bool hasSkylight; uniform sampler2D tex; void main() {"
                + " gl_FragColor = texture2D(tex, uv) * (hasSkylight ? 1.0 : 0.5); }\n";
        Path properties = shaders.resolve("shaders.properties");
        try {
            for (String name : names) {
                Files.writeString(shaders.resolve(name + ".vsh"), vertex);
                Files.writeString(shaders.resolve(name + ".fsh"), fragment);
            }
            String directives = "program.gbuffers_entities_translucent.enabled=false\n"
                    + "program.final.enabled=false\nblend.gbuffers_entities.colortex1=off\n"
                    + "alphaTest.gbuffers_entities=GREATER 0.2\n";
            Files.writeString(properties, directives);
            PackProbe.Analysis analysis = PackProbe.analyze(root);
            assertHandAliasesKeepHandFamily(analysis);
            assertTranslucentAliasKeepsRequestedFamily(analysis);
            assertCrumblingKeepsHostBlockLayout(analysis);
            Files.writeString(properties, directives + "program.gbuffers_entities.enabled=false\n");
            analysis = PackProbe.analyze(root);
            assertEquals("gbuffers_textured_lit", analysis.resolution()
                    .resolution("gbuffers_entities_translucent").selectedProgram(),
                    "fallback stopped at disabled parent");
            assertTrue(analysis.plan().shouldAttempt("gbuffers_entities_translucent"),
                    "second ancestor did not execute through requested family");
            Files.delete(shaders.resolve("gbuffers_entities_translucent.fsh"));
            Files.delete(shaders.resolve("gbuffers_entities_translucent.vsh"));
            Files.writeString(properties, "");
            analysis = PackProbe.analyze(root);
            assertTrue(analysis.report().shouldAttempt("gbuffers_entities_translucent"),
                    "missing child did not materialize its parent source");
        } finally {
            Files.deleteIfExists(properties);
            for (String name : names) {
                Files.deleteIfExists(shaders.resolve(name + ".fsh"));
                Files.deleteIfExists(shaders.resolve(name + ".vsh"));
            }
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }

    /** Both hand phases inherit gbuffers_textured_lit yet keep the hand family and its bool uniform. */
    private static void assertHandAliasesKeepHandFamily(PackProbe.Analysis analysis) {
        PackProgramPlan handWater = analysis.plan().program("gbuffers_hand_water");
        assertEquals("gbuffers_textured_lit", analysis.resolution()
                .resolution("gbuffers_hand_water").selectedProgram(), "missing hand inherited source");
        assertTrue(analysis.plan().shouldAttempt("gbuffers_hand")
                        && analysis.plan().shouldAttempt("gbuffers_hand_water"),
                "inherited hand phases must be executable");
        assertEquals(FamilyAdapterPlan.Family.HAND_WATER, handWater.familyAdapter().family(),
                "hand alias must retain the requested translucent hand adapter");
        assertEquals(List.of(1), handWater.geometryOutputPlan().targetSlots(), "hand alias lost source outputs");
        for (String name : List.of("gbuffers_hand", "gbuffers_hand_water")) {
            var handPlan = analysis.plan().program(name);
            assertTrue(handPlan.convertedFragment().contains("bool hasSkylight;"), "GLSL bool semantics changed");
            var ordinary = PackDescriptorContracts.ordinaryDescriptorContract(handPlan, Set.of());
            var boolField = ordinary.layout().uniformBlocks().stream().flatMap(block -> block.fields().stream())
                    .filter(field -> field.name().equals("hasSkylight")).findFirst().orElseThrow();
            // Regression: the host builder rejects "not admitted type: bool"; std140 stores it as int.
            assertEquals("int", boolField.type(), "std140 bool storage");
        }
    }

    /** A disabled translucent child runs its parent's source under its own requested family. */
    private static void assertTranslucentAliasKeepsRequestedFamily(PackProbe.Analysis analysis) {
        PackProgramPlan trans = analysis.plan().program("gbuffers_entities_translucent");
        assertEquals("gbuffers_entities", analysis.resolution()
                .resolution(trans.name()).selectedProgram(), "disabled child must use enabled parent");
        assertTrue(analysis.report().shouldAttempt(trans.name()) && analysis.plan().shouldAttempt(trans.name()),
                "requested translucent adapter was not admitted: " + trans.deviations()
                        + "/" + analysis.report().program(trans.name()).deviations());
        assertEquals(FamilyAdapterPlan.Family.ENTITY_TRANSLUCENT, trans.familyAdapter().family(),
                "alias borrowed the opaque family");
        assertEquals(List.of(1), trans.geometryOutputPlan().targetSlots(), "alias lost source outputs");
        assertEquals(PackBlendPlan.Mode.OFF, trans.blendPlan().buffers().get(1), "alias lost source blend");
        assertTrue(trans.alphaTestPlan().reference() == 0.2F, "alias lost source alpha directive");
        assertTrue(!analysis.plan().activePrograms().stream().anyMatch(p -> p.name().equals("final")),
                "disabled post program became a fallback producer");
    }

    private static void assertCrumblingKeepsHostBlockLayout(PackProbe.Analysis analysis) {
        PackProgramPlan damage = analysis.plan().program("gbuffers_damagedblock");
        assertTrue(analysis.report().shouldAttempt(damage.name()) && damage.executable(),
                "host BLOCK crumbling adapter not admitted: " + damage.deviations());
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_BLOCK, damage.familyAdapter().vertexContract(),
                "crumbling reused entity format");
        assertTrue(damage.convertedVertex().contains("layout(location = 3) in ivec2 UV2;")
                        && damage.convertedVertex().contains("layout(location = 4) in vec3 Normal;")
                        && damage.convertedVertex().contains("TextureMat * vec4(UV0")
                        && !damage.convertedVertex().contains("in uvec4 EntityIds"),
                "crumbling host attribute/texture-matrix contract changed");
    }

    /** The named program's plan, asserted present, executable and attempted. */
    private static PackProgramPlan executablePlan(PackProbe.Analysis analysis, String name, String subject) {
        var plan = analysis.plan().program(name);
        assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt(name),
                subject + " rejected: " + (plan == null ? "missing" : plan.deviations()));
        return plan;
    }

    /**
     * Iris ShaderKey.LINES: a pack's own gbuffers_line runs as authored on the core inputs
     * and widens its own line; a gbuffers_basic fallback is widened by Iris (main runs at
     * both segment ends, VIEW_SHRINK, +Z normal). Both compile on the host line layout.
     */
    @Test
    void lineLane() {
        assertTrue(HostLayoutLane.forDraw("rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                        HostLayoutLane.Pass.WORLD) == HostLayoutLane.LINE,
                "lines and line strips (rendertype_lines) must route to gbuffers_line");
        assertEquals(0.0F, HostLayoutLane.LINE.alphaReference(), "Iris LINES uses AlphaTests.OFF");
    }

    @Test
    void basicFallbackLine(@TempDir Path fallback) throws IOException {
        Path shaders = Files.createDirectory(fallback.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 120
                varying vec4 tint;
                varying vec3 normal;
                void main() {
                    tint = gl_Color;
                    normal = gl_NormalMatrix * gl_Normal;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.fsh"), LINE_FRAGMENT);
        var analysis = PackProbe.analyze(fallback);
        var plan = executablePlan(analysis, "gbuffers_line", "basic fallback line");
        assertEquals("gbuffers_basic", analysis.resolution().resolution("gbuffers_line").selectedProgram(),
                "line fallback source");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_LINE, plan.familyAdapter().vertexContract(),
                "line keeps the host layout");
        String converted = plan.convertedVertex();
        assertTrue(converted.contains("#define chimeraLineWidth LineWidth")
                        && converted.contains("void chimeraLineMain()")
                        && converted.contains("chimeraLineOffset = chimeraLineDirection;")
                        && converted.contains("chimeraLineViewScale")
                        && converted.contains("vec3(0.0, 0.0, 1.0)")
                        && converted.contains("tint = (Color * ColorModulator);"),
                "fallback line lost Iris widening: " + converted);
        var compiled = PackCompileTest.run(fallback, analysis);
        assertTrue(compiled.failures().isEmpty(), "fallback line compilation: " + compiled.failures());
    }

    @Test
    void authoredLine(@TempDir Path packLine) throws IOException {
        Path shaders = Files.createDirectory(packLine.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_line.vsh"), """
                #version 130
                uniform float viewWidth;
                uniform float viewHeight;
                out vec4 tint;
                out vec3 normal;
                void main() {
                    vec4 start = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                    vec4 end = projectionMatrix * modelViewMatrix * vec4(vaPosition + vaNormal, 1.0);
                    vec2 direction = normalize((end.xy / end.w - start.xy / start.w) * vec2(viewWidth, viewHeight));
                    vec2 offset = vec2(-direction.y, direction.x) * 2.0 / vec2(viewWidth, viewHeight);
                    gl_Position = start + vec4((gl_VertexID % 2 == 0 ? offset : -offset) * start.w, 0.0, 0.0);
                    tint = gl_Color;
                    normal = gl_NormalMatrix * gl_Normal;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_line.fsh"), LINE_FRAGMENT.replace("varying", "in"));
        var analysis = PackProbe.analyze(packLine);
        var plan = executablePlan(analysis, "gbuffers_line", "authored line");
        String converted = plan.convertedVertex();
        assertTrue(!converted.contains("chimeraLineMain")
                        && converted.contains("chimeraLineVertexIndex % 2")
                        && converted.contains("ProjMat * ModelViewMat")
                        && converted.contains("Normal.xyz"),
                "authored line lost Iris core inputs: " + converted);
        var compiled = PackCompileTest.run(packLine, analysis);
        assertTrue(compiled.failures().isEmpty(), "authored line compilation: " + compiled.failures());
    }

    /**
     * Iris ShaderKey.GLINT: gbuffers_armor_glint on the host POSITION_TEX layers, with the
     * glint strength in gl_Color's alpha, NON_ZERO_ALPHA, and unbound material attributes.
     */
    @Test
    void glintLane(@TempDir Path root) throws IOException {
        assertEquals(0.0001F, HostLayoutLane.GLINT.alphaReference(), "Iris GLINT uses AlphaTests.NON_ZERO_ALPHA");
        // A held foil item flushes its glint inside the hand scope; the hand lane has no
        // POSITION_TEX variant, so the lookup must answer host fallback, not throw.
        assertTrue(new PackPipelines.PackHand(Map.of()).forFormat(DefaultVertexFormat.POSITION_TEX) == null,
                "hand glint must fall back to its host draw");
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_armor_glint.vsh"), """
                #version 120
                attribute vec4 at_tangent;
                varying vec2 texCoord;
                varying vec4 color;
                varying vec4 tangent;
                void main() {
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    color = gl_Color;
                    tangent = at_tangent;
                    gl_Position = ftransform();
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_armor_glint.fsh"), """
                #version 120
                /* DRAWBUFFERS:0 */
                uniform sampler2D texture;
                varying vec2 texCoord;
                varying vec4 color;
                varying vec4 tangent;
                void main() {
                    gl_FragData[0] = texture2D(texture, texCoord) * color + tangent * 0.001;
                }
                """);
        var analysis = PackProbe.analyze(root);
        var plan = executablePlan(analysis, "gbuffers_armor_glint", "glint");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_GLINT, plan.familyAdapter().vertexContract(),
                "glint keeps the host layout");
        String converted = plan.convertedVertex();
        assertTrue(converted.contains("layout(location = 1) in vec2 UV0;")
                        && converted.contains("const vec4 Color = vec4(1.0);")
                        && converted.contains(UniformRegistry.GLINT_ALPHA)
                        && converted.contains("TextureMat")
                        && converted.contains("tangent = vec4(0.0, 0.0, 0.0, 1.0);"),
                "glint lost Iris input semantics: " + converted);
        assertTrue(plan.alphaTestPlan().perDrawReference() && plan.convertedFragment().contains("discard;"),
                "glint lost its per-draw alpha test");
        String hand = EntityVertexBridge.handGlintVertex(converted);
        assertTrue(hand.contains("void chimeraHandMain()")
                        && hand.contains("gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w)")
                        && hand.contains("chimeraHandProjection() * ModelViewMat")
                        && !hand.contains("\nvoid main() {\n"),
                "hand glint must share the hand family's projection and clip-depth remap: " + hand);
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "glint compilation: " + compiled.failures());
    }

    /** The HostLayoutLane table against Iris IrisPipelines and the pack-side contracts. */
    @Test
    void hostLayoutLanes() {
        var lanes = HostLayoutLane.values();
        Set<String> servedPrograms = new HashSet<>();
        for (var lane : lanes) {
            assertTrue(HostLayoutLane.of(lane.family()) == lane,
                    lane + " family must name its lane");
            for (String shader : lane.shaders()) {
                assertTrue(HostLayoutLane.forDraw(shader, lane.format(), lane.pass()) == lane,
                        shader + " must route to " + lane);
            }
            FamilyAdapterPlan adapter = FamilyAdapterRegistry.forProgram(lane.program());
            if (lane == HostLayoutLane.WEATHER) {
                assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE, adapter.vertexContract(),
                        "weather keeps the particle conversion");
            } else {
                assertTrue(adapter.vertexContract().hostLayout(), lane.program() + " must be a host-layout program");
                assertEquals(lane.format(), PackFamilyPipelines.hostLayoutFormat(adapter.vertexContract()),
                        lane + " pack layout and host layout disagree");
                servedPrograms.add(lane.program());
            }
        }
        for (String program : List.of("gbuffers_line", "gbuffers_armor_glint", "gbuffers_lightning",
                "gbuffers_beaconbeam", "gbuffers_basic", "shadow_lines", "shadow_leash", "shadow_lightning",
                "shadow_glint", "shadow_text", "shadow_text_bg", "shadow_basic", "text_bg")) {
            assertTrue(servedPrograms.contains(program), program + " has no host-layout lane");
        }
        assertEquals(FamilyAdapterRegistry.hostLayoutLanes().size(), servedPrograms.size(),
                "every host-layout program needs a lane");
    }

    @Test
    void hostLayoutLaneDrawState() {
        var table = HostLayoutLane.class;
        assertTrue(HostLayoutLane.LINE.whiteAlbedo()
                        && HostLayoutLane.LIGHTNING.whiteAlbedo()
                        && HostLayoutLane.LEASH.whiteAlbedo()
                        && HostLayoutLane.TEXT_BG.whiteAlbedo()
                        && HostLayoutLane.SHADOW_TEXT_BG.whiteAlbedo()
                        && HostLayoutLane.SHADOW_BASIC.whiteAlbedo()
                        && !HostLayoutLane.GLINT.whiteAlbedo()
                        && !HostLayoutLane.SHADOW_TEXT.whiteAlbedo()
                        && !HostLayoutLane.BEACON_BEAM.whiteAlbedo(),
                "Iris binds white to unit 0 exactly for host formats without UV0: " + table.getSimpleName());
        assertTrue(HostLayoutLane.forDraw("rendertype_lines", DefaultVertexFormat.POSITION_COLOR_NORMAL,
                        HostLayoutLane.Pass.HAND) == null,
                "a world-only lane must not take a first-person draw");
        for (var lane : List.of(HostLayoutLane.LIGHTNING, HostLayoutLane.BEACON_BEAM, HostLayoutLane.LEASH)) {
            assertEquals(0.0F, lane.alphaReference(), "Iris LIGHTNING, BEACON and LEASH use AlphaTests.OFF");
        }
    }

    /**
     * LIGHTNING (through the entity fallback), BEACON and LEASH converted and compiled with
     * Iris's host-layout inputs and identity: entityId per draw, blockEntityId -1.
     */
    @Test
    void hostLayoutLanePrograms(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        // No gbuffers_lightning: lightning falls back to gbuffers_entities, as in BSL and MakeUp.
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 120
                uniform int entityId;
                attribute int blockEntityId;
                varying vec2 texCoord;
                varying vec2 light;
                varying vec4 color;
                varying vec3 normal;
                varying float block;
                void main() {
                    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    light = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                    color = gl_Color;
                    normal = gl_NormalMatrix * gl_Normal;
                    block = float(blockEntityId);
                    gl_Position = ftransform();
                    if (entityId == 10101) gl_Position.z -= 0.0001;
                }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 120
                /* DRAWBUFFERS:0 */
                uniform sampler2D texture;
                uniform int entityId;
                varying vec2 texCoord;
                varying vec2 light;
                varying vec4 color;
                varying vec3 normal;
                varying float block;
                void main() {
                    vec4 albedo = texture2D(texture, texCoord) * color;
                    if (entityId == 10101) albedo = vec4(1.0);
                    gl_FragData[0] = albedo + vec4(light, normal.z, block) * 0.001;
                }
                """);
        for (String program : List.of("gbuffers_beaconbeam", "gbuffers_basic")) {
            Files.copy(shaders.resolve("gbuffers_entities.vsh"), shaders.resolve(program + ".vsh"));
            Files.copy(shaders.resolve("gbuffers_entities.fsh"), shaders.resolve(program + ".fsh"));
        }
        var analysis = PackProbe.analyze(root);
        assertEquals("gbuffers_entities", analysis.resolution().resolution("gbuffers_lightning").selectedProgram(),
                "Iris ProgramId.Lightning falls back to gbuffers_entities");
        Map<String, List<String>> expected = Map.of(
                "gbuffers_lightning", List.of("layout(location = 1) in vec4 Color;",
                        "const vec2 UV0 = vec2(0.5);", "const ivec2 UV2 = ivec2(240);",
                        "const vec3 Normal = vec3(0.0, 0.0, 1.0);"),
                "gbuffers_beaconbeam", List.of("layout(location = 2) in vec2 UV0;",
                        "layout(location = 3) in ivec2 UV2;", "layout(location = 4) in vec3 Normal;",
                        "return vec4(Position, 1.0);"),
                "gbuffers_basic", List.of("layout(location = 1) in vec4 Color;",
                        "const vec2 UV0 = vec2(0.5);", "layout(location = 2) in ivec2 UV2;",
                        "const vec3 Normal = vec3(0.0, 0.0, 1.0);"));
        for (var entry : expected.entrySet()) {
            assertHostLayoutProgram(analysis, entry.getKey(), entry.getValue());
        }
        // No gbuffers_entities_translucent: Iris TEXT_BG runs EntitiesTrans's chain, here gbuffers_entities.
        assertEquals("gbuffers_entities", analysis.resolution().resolution("text_bg").selectedProgram(),
                "text_bg resolves through gbuffers_entities_translucent's chain");
        var textBackground = executablePlan(analysis, "text_bg", "text_bg");
        for (String line : List.of("layout(location = 1) in vec4 Color;", "const vec2 UV0 = vec2(0.5);",
                "layout(location = 2) in ivec2 UV2;", "const vec3 Normal = vec3(0.0, 0.0, 1.0);")) {
            assertTrue(textBackground.convertedVertex().contains(line),
                    "text_bg lost Iris host input " + line + ": " + textBackground.convertedVertex());
        }
        assertTrue(textBackground.alphaTestPlan().perDrawReference(),
                "text_bg tests the lane's ONE_TENTH_ALPHA per draw");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "host-layout lane compilation: " + compiled.failures());
    }

    /** One host-layout program reads Iris's host inputs, entityId 0 and blockEntityId -1, with no alpha test. */
    private static void assertHostLayoutProgram(PackProbe.Analysis analysis, String program, List<String> inputs) {
        var plan = executablePlan(analysis, program, program);
        String vertex = plan.convertedVertex();
        for (String line : inputs) {
            assertTrue(vertex.contains(line), program + " lost Iris host input " + line + ": " + vertex);
        }
        // Iris reads its fallback entityId per draw: 0 by the time these batches draw.
        assertTrue(vertex.contains("(Color * ColorModulator)")
                        && vertex.contains("(chimeraHostEntityId) == 10101")
                        && vertex.contains("float((-1))")
                        && !vertex.contains("chimeraEntityId")
                        && plan.convertedFragment().contains("(chimeraHostEntityId) == 10101"),
                program + " must read the per-draw entityId and blockEntityId -1: " + vertex);
        assertTrue(!plan.alphaTestPlan().perDrawReference(),
                program + " has no Iris alpha test (AlphaTests.OFF)");
    }

    /**
     * Vanilla's world clear (fog colour, depth 1) would land on the pack's colortex0; a hook keeps
     * it to depth while a pack owns that image, as Iris's ClearPass is colortex0's only clear.
     */
    @Test
    void worldClearHook() throws IOException {
        assertWrapsCalls("ChimeraLevelRendererMixin", "net/minecraft/client/renderer/LevelRenderer", "renderLevel",
                "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", 1);
    }

    private static void assertWrapsCalls(String mixinName, String owner, String method, String target, int calls)
            throws IOException {
        MixinBytecode.assertMixinRegistered(mixinName);
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/" + mixinName);
        assertTrue(MixinBytecode.mixinTargets(mixin, owner)
                        && MixinBytecode.injects(mixin,
                        "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", method, target),
                mixinName + " wraps " + target);
        long found = MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(owner), method).stream()
                .filter(instruction -> instruction instanceof MethodInsnNode call
                        && ("L" + call.owner + ";" + call.name + call.desc).equals(target))
                .count();
        assertEquals(calls, found, owner + "." + method + " calls " + target);
    }

    @Test
    void particleAndTextAlphaReferences() {
        for (var family : List.of(ChimeraEntityBridge.Family.PARTICLE,
                ChimeraEntityBridge.Family.PARTICLE_TRANSLUCENT)) {
            assertEquals(0.1F, HostAlphaTest.reference("particle", family, false),
                    "particle fixed core cutout must not depend on an absent ALPHA_CUTOUT define");
        }
        assertEquals(0.1F, HostLayoutLane.WEATHER.alphaReference(), "Iris WEATHER tests ONE_TENTH_ALPHA");
        for (String shader : List.of("rendertype_text", "rendertype_text_see_through")) {
            assertEquals(0.1F, HostAlphaTest.reference(shader, ChimeraEntityBridge.Family.ENTITY_TRANSLUCENT, false),
                    "world text tests ONE_TENTH_ALPHA as Iris TEXT/TEXT_BE; untested glyph quads fill solid");
            assertEquals(0.0001F, HostAlphaTest.reference(shader, ChimeraEntityBridge.Family.HAND, false),
                    "first-person text tests NON_ZERO_ALPHA as Iris HAND_TEXT");
        }
        assertEquals(0.1F, HostLayoutLane.TEXT_BG.alphaReference(), "Iris TEXT_BG tests ONE_TENTH_ALPHA");
        var glyphs = DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
        // TrueType glyphs (Iris TEXT_INTENSITY and TEXT_INTENSITY_BE) read their RRRR page alike.
        for (String text : List.of("rendertype_text", "rendertype_text_see_through", "position_color_tex_lightmap",
                "rendertype_text_intensity", "rendertype_text_intensity_see_through")) {
            assertTrue(ChimeraEntityBridge.worldFamily(text, glyphs, ChimeraEntityBridge.Family.ENTITY)
                            == ChimeraEntityBridge.Family.ENTITY_TRANSLUCENT
                            && ChimeraEntityBridge.worldFamily(text, glyphs, ChimeraEntityBridge.Family.BLOCK)
                            == ChimeraEntityBridge.Family.BLOCK_TRANSLUCENT,
                    text + " must draw as Iris TEXT and TEXT_BE");
        }
        assertEquals(0.0001F, HostAlphaTest.reference("rendertype_text_intensity", ChimeraEntityBridge.Family.HAND,
                false), "first-person TrueType text tests NON_ZERO_ALPHA as Iris HAND_TEXT_INTENSITY");
        assertEquals(HostLayoutLane.SHADOW_TEXT, HostLayoutLane.forDraw("rendertype_text_intensity", glyphs,
                HostLayoutLane.Pass.SHADOW), "TrueType text casts with shadow_text (Iris SHADOW_TEXT_INTENSITY)");
    }

    @Test
    void particleFallbackAndTargets(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Path vertex = shaders.resolve("gbuffers_textured.vsh");
        Path fragment = shaders.resolve("gbuffers_textured.fsh");
        Path properties = shaders.resolve("shaders.properties");
        String source = """
                #version 120
                attribute vec4 mc_Entity;
                uniform float frameTimeCounter;
                varying vec2 uv;
                varying vec4 tint;
                varying vec2 light;
                varying vec3 normal;
                varying float time;
                void main() {
                    mat4 mvp = gl_ModelViewProjectionMatrix;
                    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    light = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
                    normal = gl_NormalMatrix * gl_Normal;
                    tint = gl_Color;
                    time = frameTimeCounter + mc_Entity.x;
                    gl_Position = mvp * gl_Vertex;
                }
                """;
        try {
            Files.writeString(vertex, source);
            Files.writeString(fragment, """
                    #version 120
                    /* DRAWBUFFERS:1 */
                    uniform sampler2D tex;
                    uniform float frameTimeCounter;
                    varying vec2 uv;
                    varying vec4 tint;
                    varying vec2 light;
                    varying vec3 normal;
                    varying float time;
                    void main() {
                        gl_FragData[0] = texture2D(tex, uv) * tint
                            + vec4(light, normal.z + time - frameTimeCounter, 0.0) * 0.001;
                    }
                    """);
            Files.writeString(properties, "blend.gbuffers_textured.colortex1=off\n");
            var analysis = PackProbe.analyze(root);
            for (String name : List.of("gbuffers_particles", "gbuffers_particles_translucent")) {
                assertParentSourceParticle(analysis, name);
            }
            var compiled = PackCompileTest.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "particle alias compilation: " + compiled.failures());
            assertParticleAdmissionStaysStrict(analysis, source, fragment);
        } finally {
            Files.deleteIfExists(properties);
            Files.deleteIfExists(fragment);
            Files.deleteIfExists(vertex);
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }

    /** A particle phase runs gbuffers_textured on the native particle layout, keeping its target and blend. */
    private static void assertParentSourceParticle(PackProbe.Analysis analysis, String name) {
        var plan = executablePlan(analysis, name, name + " parent-source particle adapter");
        assertEquals("gbuffers_textured", analysis.resolution().resolution(name).selectedProgram(),
                name + " selected source");
        assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE, plan.familyAdapter().vertexContract(),
                name + " must retain native particle format");
        var output = plan.geometryOutputPlan();
        assertEquals(List.of(1), output.targetSlots(), name + " authored target");
        var runtime = new PackPipelines.PackParticle(null, new int[0], plan.convertedVertex(),
                plan.convertedFragment(), output, new ProgramImageBindingManifest(List.of()));
        assertTrue(runtime.requiresDynamicAttachments(), name + " singleton target1 lost its draw window");
        assertEquals(PackBlendPlan.Mode.OFF, plan.blendPlan().buffers().get(1),
                name + " lost selected-source blend directive");
        String converted = plan.convertedVertex();
        assertTrue(converted.contains("layout(location = 1) in vec2 UV0;")
                        && converted.contains("layout(location = 2) in vec4 Color;")
                        && converted.contains("layout(location = 3) in ivec2 UV2;")
                        && !converted.contains("in uvec4 EntityIds")
                        && !converted.contains("in vec3 Normal")
                        && converted.contains("const vec3 Normal = vec3(0.0, 0.0, 1.0);")
                        && converted.contains("tint = (Color * ColorModulator);")
                        && converted.contains("transpose(inverse(mat3(ModelViewMat)))"),
                name + " Iris host-particle input semantics");
        assertTrue(plan.alphaTestPlan().perDrawReference()
                        && plan.convertedFragment().contains("discard;"),
                name + " lost native/pack alpha cutout");
    }

    /** The particle repair fabricates no material attributes, admits no unknown uniform and leaves terrain alone. */
    private static void assertParticleAdmissionStaysStrict(PackProbe.Analysis analysis, String source,
                                                           Path fragment) throws IOException {
        var uniforms = analysis.plan().program("gbuffers_particles").interfacePlan()
                .effective(UniformRegistry.Stage.PARTICLE).executableUniforms();
        assertTrue(EntityVertexBridge.convertParticleVertex(source.replace("void main() {",
                        "attribute vec2 mc_midTexCoord;\nvoid main() {\nuv = mc_midTexCoord;"),
                        null, "", Map.of()) == null,
                "particle material attributes must not be fabricated");
        try {
            EntityVertexBridge.convertParticleVertexChecked(source.replace("frameTimeCounter", "unknownTime"),
                    null, Files.readString(fragment), Map.of(), false, uniforms);
            throw new AssertionError("referenced unknown particle uniform was admitted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unknownTime"), "particle rejection lost concrete symbol");
        }
        assertTrue(TerrainVertexBridge.convertTerrainVertex(source, null, "") == null,
                "particle repair weakened terrain admission");
    }
}
