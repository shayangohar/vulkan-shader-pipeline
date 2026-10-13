package net.chimera.shaderpack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraHorizonRenderer;
import net.chimera.render.PackPostTargetsHarness;
import net.chimera.render.PackShadowCompute;
import net.chimera.render.ShadowCullingFrustum;
import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.HostLayoutLane;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.shader.PackUniformProvider;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.render.vertex.SeparateAoColor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.util.shaderc.Shaderc;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Small dependency-free conformance check for the locked M5.1 through M5.7 fixtures
 * and the M5.7 post-target availability boundary.
 * Gradle runs this class before a normal build.
 */
public final class ConformanceHarness {
    private static final List<String> REQUIRED_PROGRAMS = List.of(
            "gbuffers_terrain", "composite", "final");

    private ConformanceHarness() {}

    public static void main(String[] args) throws IOException {
        verifyLegacyHostVertexSemantics();
        verifyHandInputsAndProjection();
        verifyHandNativeHooks();
        verifyWeatherAndSky();
        verifyRainContracts();
        verifyRequestedFamilyFallbackAndCrumbling();
        verifyParticleFallbackAndTargets();
        verifyLineLane();
        verifyGlintLane();
        verifyHostLayoutLanes();
        verifyShadowEntityCasters();
        verifyShadowLanes();
        verifyShadowCulling();
        verifyShadowPassOrder();
        verifyGlslBreadth();
        verifyComputeDeclarationLists();
        verifyComputeFrameSlots();
        verifyComputeUniformOffsets();
        verifyMidBlockInputs();
        verifyTerrainShading();
        verifyHeldItems();
        verifyScreenOverlays();
        verifyIndentedProperties();
        PackPostTargetsHarness.run();
        assertEquals(List.of(0, 2, 3), PackPipelines.colorInputTargets(
                        List.of("colortex3", "depthtex0", "colortex0", "colortex2")),
                "post color input ordering");
        Path fixtureRoot = Path.of(System.getProperty("chimera.fixtureRoot", "testpacks"));
        verifySimplex(fixtureRoot.resolve("simplex"), fixtureRoot.resolve("baselines/simplex.json"));
        verifySupported(fixtureRoot.resolve("m5_1/optifine"), "optifine");
        verifySupported(fixtureRoot.resolve("m5_1/iris"), "iris");
        verifyUnsupported(fixtureRoot.resolve("m5_1/unsupported"));
        verifyM52Material(fixtureRoot.resolve("m5_2/material"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM52Unsupported(fixtureRoot.resolve("m5_2/unsupported_vertex"), fixtureRoot.resolve("baselines/m5_2.json"));
        verifyM53LiveUniforms(fixtureRoot.resolve("m5_3/live_uniforms"), fixtureRoot.resolve("baselines/m5_3.json"));
        verifyM53Unsupported(fixtureRoot.resolve("m5_3/unsupported_uniform"), fixtureRoot.resolve("baselines/m5_3.json"));
        verifyM54Shadow(fixtureRoot.resolve("m5_4/shadow"), fixtureRoot.resolve("baselines/m5_4.json"));
        verifyM54Unsupported(fixtureRoot.resolve("m5_4/unsupported_shadow"), fixtureRoot.resolve("baselines/m5_4.json"));
        verifyM55Water(fixtureRoot.resolve("m5_5/water"), fixtureRoot.resolve("baselines/m5_5.json"));
        verifyM55Unsupported(fixtureRoot.resolve("m5_5/unsupported_water"), fixtureRoot.resolve("baselines/m5_5.json"));
        verifyM56PostChain(fixtureRoot.resolve("m5_6/post_chain"), fixtureRoot.resolve("baselines/m5_6.json"));
        verifyM56Unsupported(fixtureRoot.resolve("m5_6/unsupported_targets"),
                fixtureRoot.resolve("baselines/m5_6.json"));
        verifyAdvancedResourceInventory();
        verifyNonzeroGeometryAndPrepare();
        System.out.println("[chimera] M5.1 through M5.7 conformance harness: PASS");
    }

    private static void verifyRainContracts() throws IOException {
        for (int target : List.of(10, 12, 15)) {
            String source = "/* RENDERTARGETS:" + target + " */\nvoid main(){gl_FragData[0]=vec4(1);}";
            var post = PostTargetPlan.parse("composite", source).plan();
            var geometry = GeometryOutputPlan.parse("gbuffers_weather", source, Map.of());
            assertTrue(post.executable() && geometry.executable(), "two-digit target rejected");
            assertEquals(List.of(target), post.targetSlots(), "integer target split into digits");
            assertEquals(post.targetSlots(), geometry.targetSlots(), "geometry target grammar drift");
            assertEquals(List.of(0), geometry.outputLocations(), "rain must have one output");
            String canonical = "/* RENDERTARGETS:" + target + ",1 */\n" + source;
            assertEquals(List.of(target), GeometryOutputPlan.parse("gbuffers_weather", canonical, Map.of()).targetSlots(),
                    "geometry normalization lost singleton target");
        }
        assertEquals(List.of(1, 2), PostTargetPlan.parse("composite", "/* DRAWBUFFERS:12 */").plan().targetSlots(),
                "legacy digit grammar changed");
        assertEquals(List.of(1, 2), PostTargetPlan.parse("composite", "/* RENDERTARGETS:1,2 */").plan().targetSlots(),
                "integer list grammar changed");
        for (String malformed : List.of("12,", "12,12", "12,nope", "2147483648")) {
            String source = "/* RENDERTARGETS:12,1 */\n/* RENDERTARGETS:" + malformed + " */";
            assertTrue(!GeometryOutputPlan.parse("gbuffers_weather", source, Map.of()).executable(),
                    "normalization concealed malformed target: " + malformed);
        }

        float[] cone = ChimeraHorizonRenderer.vertices(32);
        assertEquals(30, cone.length, "Iris fan must have ten POSITION vertices");
        assertTrue(cone[0] == 0 && cone[1] == -16 && cone[2] == 0, "horizon apex must be below camera");
        for (int i = 1; i < 10; i++) {
            float x = cone[i * 3], z = cone[i * 3 + 2];
            assertTrue(cone[i * 3 + 1] == 16 && Math.abs(Math.hypot(x, z) - 256) < 0.001,
                    "Iris horizon radius/height changed");
        }
        assertTrue(cone[8] < 0, "horizon must use inward Iris winding");
        assertTrue(Math.abs(cone[3] - cone[27]) < 0.001 && Math.abs(cone[5] - cone[29]) < 0.001,
                "horizon rim must close");
        assertTrue(ChimeraHorizonRenderer.vertices(8)[3] == 128, "horizon must track render distance");
        verifyHorizonSubmission();
        verifyEndPortalHooks();

        Path root = Files.createTempDirectory("chimera-rain-contract-");
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        try {
            String vertex = "#version 130\nflat out vec4 tint;\nout vec2 uv;\n"
                    + "void main(){ tint=gl_Color; uv=gl_MultiTexCoord0.xy; gl_Position=ftransform(); }";
            String fragment = "#version 130\nflat in vec4 tint;\nin vec2 uv;\n"
                    + "void main(){gl_FragData[0]=tint+vec4(uv,0,0);}";
            for (String name : List.of("gbuffers_skybasic", "gbuffers_skytextured")) {
                Files.writeString(shaders.resolve(name + ".vsh"), vertex);
                Files.writeString(shaders.resolve(name + ".fsh"), fragment);
            }
            Files.writeString(shaders.resolve("gbuffers_weather.vsh"), vertex);
            Files.writeString(shaders.resolve("gbuffers_weather.fsh"), "/* RENDERTARGETS:12 */\n" + fragment);
            Files.writeString(shaders.resolve("shaders.properties"), "blend.gbuffers_weather.colortex12=off\n");
            var analysis = PackProbe.analyze(root);
            for (String name : List.of("gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_weather")) {
                var plan = analysis.plan().program(name);
                assertTrue(analysis.plan().shouldAttempt(name), name + " plan rejected: " + plan.deviations());
                assertTrue(analysis.report().shouldAttempt(name), name + " report rejected: " + analysis.report().program(name).deviations());
                assertTrue(PackCompileCheck.compile(plan.convertedVertex(), true) == null, name + " vertex compile");
                assertTrue(PackCompileCheck.compile(plan.convertedFragment(), false) == null, name + " fragment compile");
                assertTrue(plan.convertedFragment().matches("(?s).*\\b(?:flat\\s+in|in\\s+flat)\\s+vec4\\b.*"),
                        name + " flat interpolation lost");
            }
            var weather = analysis.plan().program("gbuffers_weather");
            assertEquals(List.of(12), weather.geometryOutputPlan().targetSlots(), "rain data route");
            assertEquals(PackBlendPlan.Mode.OFF, weather.blendPlan().attachments(List.of(12))[0], "rain data blend override");
            Files.writeString(shaders.resolve("gbuffers_skybasic.fsh"), "#version 130\n"
                    + "uniform samplerCube unsupportedCube; void main(){gl_FragColor=texture(unsupportedCube,vec3(1));}");
            assertTrue(!PackProbe.analyze(root).plan().shouldAttempt("gbuffers_skybasic"), "sky admission bypassed resource validation");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("[chimera] rain target grammar, sky admission and Iris horizon geometry: PASS");
    }

    private static void verifyLegacyHostVertexSemantics() {
        String vertex = """
                #version 120
                varying vec2 uv;
                varying vec2 light1;
                varying vec2 light2;
                void main() {
                    mat4 mvp = gl_ModelViewProjectionMatrix;
                    mat4 textureMatrix = gl_TextureMatrix[0];
                    mat4 lightMatrix = gl_TextureMatrix[1];
                    mat4 lightAliasMatrix = gl_TextureMatrix[2];
                    uv = (textureMatrix * gl_MultiTexCoord0).xy;
                    light1 = (lightMatrix * gl_MultiTexCoord1).xy;
                    light2 = (lightAliasMatrix * gl_MultiTexCoord2).xy;
                    vec4 direct = gl_ModelViewProjectionMatrix * gl_Vertex;
                    vec4 typed = mvp * gl_Vertex;
                    vec4 helper = ftransform();
                    gl_Position = direct + (typed - direct) + (helper - direct);
                }
                """;
        String fragment = "#version 120\nvarying vec2 uv;\nvarying vec2 light1;\n"
                + "varying vec2 light2;\nvoid main() { gl_FragColor = vec4(uv + light1 + light2, 0, 1); }\n";
        for (String family : List.of("entity", "block", "crumbling", "hand", "particle")) {
            var conversion = switch (family) {
                case "block" -> EntityVertexBridge.convertBlockVertexChecked(
                        vertex, null, fragment, Map.of(), false, List.of());
                case "crumbling" -> EntityVertexBridge.convertCrumblingVertexChecked(
                        vertex, null, fragment, Map.of(), false, List.of());
                case "hand" -> EntityVertexBridge.convertHandVertexChecked(
                        vertex, null, fragment, Map.of(), false, List.of());
                case "particle" -> EntityVertexBridge.convertParticleVertex(
                        vertex, null, fragment, Map.of(), false);
                default -> EntityVertexBridge.convertEntityVertexChecked(
                        vertex, null, fragment, Map.of(), false, List.of());
            };
            assertTrue(conversion != null, family + " typed legacy matrices rejected");
            String source = conversion.source();
            String compileError = PackCompileCheck.compile(source, true);
            assertTrue(compileError == null, family + " typed legacy matrices: " + compileError);
            String projectionName = family.equals("hand") ? "chimeraHandProjection()" : "ProjMat";
            assertTrue(source.contains("mat4 mvp = (" + projectionName + " * ModelViewMat);")
                            && source.contains("vec4 direct = (" + projectionName + " * ModelViewMat) * chimeraEntityVertexValue();")
                            && source.contains("vec4 typed = mvp * chimeraEntityVertexValue();")
                            && source.contains("vec4 helper = chimeraEntityFtransform();")
                            && source.contains("return " + projectionName + " * ModelViewMat * chimeraEntityVertexValue();"),
                    family + " explicit MVP and ftransform disagree");
            boolean offset = family.equals("block") || family.equals("crumbling");
            assertTrue(source.contains(offset ? "return vec4(Position + ModelOffset, 1.0);"
                            : "return vec4(Position, 1.0);"), family + " host position offset");
            assertTrue(source.contains("mat4 textureMatrix = TextureMat;")
                            && source.contains("light1 = (lightMatrix * vec4(vec2(UV2), 0.0, 1.0)).xy;")
                            && source.contains("light2 = (lightAliasMatrix * vec4(vec2(UV2), 0.0, 1.0)).xy;")
                            && !source.contains("vec2(UV2) / 256.0"),
                    family + " texture/lightmap contract");
            var matrix = Pattern.compile("mat4 lightMatrix = mat4\\(([^)]+)\\);")
                    .matcher(source);
            assertTrue(matrix.find(), family + " missing Iris light matrix");
            String[] coefficients = matrix.group(1).split(",");
            float[] values = new float[coefficients.length];
            for (int i = 0; i < values.length; i++) values[i] = Float.parseFloat(coefficients[i].trim());
            assertEquals(16, values.length, family + " light matrix dimensions");
            var actual = new Matrix4f().set(values)
                    .transform(new Vector4f(80, 240, 0, 1));
            assertTrue(Math.abs(actual.x - 0.34375F) < 1.0e-7F
                            && Math.abs(actual.y - 0.96875F) < 1.0e-7F
                            && actual.z == 0.03125F && actual.w == 1.0F,
                    family + " raw UV2=(80,240) must receive scale and half-texel offset once: " + actual);
            assertTrue(source.contains("mat4 lightAliasMatrix = mat4(" + matrix.group(1) + ");"),
                    family + " light texture matrix aliases differ");
        }
        String overlayFragment = EntityOverlayColor.inject("#version 120\n"
                + "uniform vec4 entityColor;\nvoid main() { gl_FragColor = entityColor; }\n");
        String overlayVertex = EntityVertexBridge.convertEntityVertexChecked(vertex, null,
                overlayFragment, Map.of(), false, List.of()).source();
        assertTrue(overlayVertex.contains(EntityOverlayColor.UV_VARYING + " = UV1;"),
                "lightmap repair must not replace the entity overlay attribute");
        assertTrue(EntityVertexBridge.convertHandVertex(vertex.replace("void main() {",
                        "void main() { vec3 available = gl_Normal;"), null, fragment, Map.of()) != null,
                "hand arm must serve its actual NEW_ENTITY normal");
        // Nontrivial position and transforms distinguish a matrix product from the old vec4 product.
        var position = new Vector4f(-3.5F, -1.5F, 0.7F, 1);
        var modelView = new Matrix4f().translate(1, 2, -6).rotateY(0.4F);
        var projection = new Matrix4f().perspective(1.1F, 1.6F, 0.05F, 128);
        var sequential = projection.transform(modelView.transform(new Vector4f(position)));
        var matrixProduct = new Matrix4f(projection).mul(modelView)
                .transform(new Vector4f(position));
        assertTrue(sequential.distance(matrixProduct) < 1.0e-5F
                        && new Vector4f(sequential).mul(position).distance(matrixProduct) > 1,
                "projection fixture must expose component-wise vertex multiplication");
        System.out.println("[chimera] legacy host vertex semantics: PASS");
    }

    private static void verifyHandInputsAndProjection() {
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
                assertEquals(host.getOffset(host.getElements().get(i)), extended.getOffset(host.getElements().get(i)), "hand native attribute offset");
            }
            String source = EntityVertexBridge.handVertexForFormat(hand.source(), host);
            assertTrue(PackCompileCheck.compile(source, true) == null, "hand layout fails compilation: " + host);
            assertTrue(source.contains("void chimeraHandMain()")
                            && source.contains("void main() { chimeraHandMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }"),
                    "authored hand returns must still reach exactly one Vulkan clip conversion");
            if (computedNormal) {
                // Iris GLYPH: text has a per-quad normal, ahead of the material inputs.
                assertTrue(source.contains("layout(location = 4) in vec3 Normal;")
                                && source.contains("layout(location = 5) in uvec4 EntityIds;")
                                && source.contains("const ivec2 UV1 = ivec2(0);"), "GLYPH text inputs");
            } else if (host != DefaultVertexFormat.NEW_ENTITY) {
                assertTrue(source.contains("const vec3 Normal = vec3(0.0, 0.0, 1.0);")
                                && source.contains("const ivec2 UV1 = ivec2(0);"), "Iris missing normal/overlay defaults");
            }
        }
        // World and sign text: the entity and block programs take the same GLYPH layout.
        var entity = EntityVertexBridge.convertEntityVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        var block = EntityVertexBridge.convertBlockVertexChecked(vertex, null, fragment, Map.of(), false, List.of());
        for (var program : List.of(entity, block)) {
            String glyph = EntityVertexBridge.layoutVertexForFormat(program.source(),
                    DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
            assertTrue(PackCompileCheck.compile(glyph, true) == null, "entity-family GLYPH layout fails compilation");
            assertTrue(glyph.contains("layout(location = 4) in vec3 Normal;")
                    && glyph.contains("layout(location = 7) in vec4 ChimeraPackedTangent;"), "entity-family GLYPH inputs");
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
            assertTrue(Math.abs(actual.x - expected.x) < 1.0e-6F && Math.abs(actual.y - expected.y) < 1.0e-6F, "hand projection must not change screen placement");
        }
        assertEquals(snapshot, raster, "hand projection must not mutate world matrices");
        System.out.println("[chimera] hand input variants and Iris clip depth: PASS");
    }

    private static void verifyHandNativeHooks() throws IOException {
        var level = nativeClass("net/minecraft/client/renderer/LevelRenderer");
        var opaque = level.methods.stream().filter(method -> method.name.equals("method_62214")).findFirst().orElseThrow();
        int flushes = 0;
        boolean translucentAfterSolidHand = false;
        for (var instruction : opaque.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/minecraft/client/renderer/MultiBufferSource$BufferSource")
                        && call.name.equals("endBatch") && call.desc.equals("()V")) flushes++;
                if (call.name.equals("renderGroup") && flushes >= 2) translucentAfterSolidHand = true;
            }
        }
        assertTrue(flushes >= 2 && translucentAfterSolidHand, "opaque hand seam must precede terrain translucency");
        var game = nativeClass("net/minecraft/client/renderer/GameRenderer");
        var late = game.methods.stream().filter(method -> method.name.equals("renderItemInHand")).findFirst().orElseThrow();
        int calls = 0;
        for (var instruction : late.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("renderHandsWithItems")
                    && call.desc.equals("(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/player/LocalPlayer;I)V")) calls++;
        }
        assertEquals(1, calls, "native late-hand redirect must have exactly one target");
        assertTrue(nativeClass("net/minecraft/client/renderer/ItemInHandRenderer").methods.stream().anyMatch(method ->
                        method.name.equals("renderArmWithItem")
                                && method.desc.equals("(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V")),
                "hand partition hook must match the native arm submission API");
        // Check the installed renderer's overwrite, not just vanilla bytecode.
        var immediate = nativeClass("net/vulkanmod/mixin/render/RenderTypeM").methods.stream()
                .filter(method -> method.name.equals("draw")).findFirst().orElseThrow();
        boolean directDrawer = false;
        for (var instruction : immediate.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertTrue(!call.name.equals("drawIndexed"), "re-audit native immediate attachment ownership");
                if (call.owner.equals("net/vulkanmod/vulkan/Drawer") && call.name.equals("draw")) directDrawer = true;
            }
        }
        assertTrue(directDrawer, "expected VulkanMod immediate Drawer.draw path");
        var owner = nativeClass("net/chimera/mixin/ChimeraRenderTypeMixin").methods.stream()
                .filter(method -> method.name.equals("chimera$immediateFamilyWindow")).findFirst().orElseThrow();
        boolean handScope = false;
        boolean attachments = false;
        for (var instruction : owner.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("net/chimera/render/ChimeraHandRenderer") && call.name.equals("active")) handScope = true;
                if (call.name.equals("beginPackFamilyWindow")) attachments = true;
            }
        }
        assertTrue(handScope && attachments, "immediate hand draw must own the authored attachment window");
    }

    /**
     * VulkanMod draws fans as triangle lists. An explicit index buffer is passed through unchanged,
     * so the 10-vertex horizon must be submitted non-indexed to get the native 24-index expansion.
     */
    private static void verifyHorizonSubmission() throws IOException {
        int vertices = ChimeraHorizonRenderer.VERTEX_COUNT;
        assertEquals(VertexFormat.Mode.TRIANGLE_FAN,
                RenderPipelines.SKY.getVertexFormatMode(),
                "horizon relies on the SKY pipeline's fan auto-index path");
        assertEquals(24, AutoIndexBuffer.getIndexCount(
                        AutoIndexBuffer.DrawType.TRIANGLE_FAN, vertices),
                "horizon fan must expand to eight triangles");
        var draw = nativeClass("net/chimera/render/ChimeraHorizonRenderer").methods.stream()
                .filter(method -> method.name.equals("draw")).findFirst().orElseThrow();
        int nonIndexed = 0;
        for (var instruction : draw.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/systems/RenderPass")) {
                assertTrue(!call.name.equals("drawIndexed") && !call.name.equals("setIndexBuffer"),
                        "indexed horizon submission bypasses VulkanMod's fan expansion");
                if (call.name.equals("draw")) nonIndexed++;
            }
        }
        assertEquals(1, nonIndexed, "horizon must submit one non-indexed fan");
    }

    /**
     * The end portal follows Iris: an entitySolid cube emitted from the native submit lambda,
     * routed as block-entity custom geometry. Pin the native seams those hooks rely on.
     */
    private static void verifyEndPortalHooks() throws IOException {
        var portal = nativeClass("net/minecraft/client/renderer/blockentity/AbstractEndPortalRenderer");
        assertTrue(portal.methods.stream().anyMatch(method -> method.name.equals("method_73539")
                        && method.desc.equals("(Lnet/minecraft/client/renderer/blockentity/state/EndPortalRenderState;"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V")),
                "end portal geometry lambda changed");
        var submit = portal.methods.stream().filter(method -> method.name.equals("submit")
                && method.desc.startsWith("(Lnet/minecraft/client/renderer/blockentity/state/EndPortalRenderState;"))
                .findFirst().orElseThrow();
        boolean custom = false;
        for (var instruction : submit.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("submitCustomGeometry")) custom = true;
        }
        assertTrue(custom, "end portal must submit custom geometry for the block-entity custom batch");
        for (String renderer : List.of("net/minecraft/client/renderer/blockentity/AbstractEndPortalRenderer",
                "net/minecraft/client/renderer/blockentity/TheEndGatewayRenderer")) {
            assertTrue(nativeClass(renderer).methods.stream().anyMatch(method -> method.name.equals("renderType")
                            && method.desc.equals("()Lnet/minecraft/client/renderer/rendertype/RenderType;")),
                    renderer + " renderType seam changed");
        }
        assertTrue(nativeClass("net/minecraft/client/renderer/feature/CustomFeatureRenderer$Storage").methods.stream()
                        .anyMatch(method -> method.name.equals("add") && method.desc.equals(
                                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;"
                                        + "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V")),
                "custom geometry storage seam changed");
    }

    private static ClassNode nativeClass(String name) throws IOException {
        try (var input = ConformanceHarness.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) throw new IOException("missing native class " + name);
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }

    private static void verifyWeatherAndSky() throws IOException {
        String vertex = """
                #version 130
                varying vec4 skyColor;
                varying vec2 uv;
                uniform mat4 gbufferModelViewInverse;
                uniform float frameTimeCounter;
                uniform bool hasSkylight;
                uniform int renderStage;
                void main() {
                    uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
                    skyColor = gl_Color * (hasSkylight && renderStage != MC_RENDER_STAGE_STARS ? 1.0 : 0.5);
                    mat4 mvp = gl_ModelViewProjectionMatrix;
                    vec4 pos = mvp * gl_Vertex;
                    pos.xy += gbufferModelViewInverse[0].xy * frameTimeCounter * 0.001;
                    gl_Position = pos;
                    if (!hasSkylight) return;
                }
                """;
        String fragment = "#version 130\n/* DRAWBUFFERS:14 */\nvarying vec4 skyColor;\nvarying vec2 uv;\n"
                + "void main() { gl_FragData[0] = skyColor; gl_FragData[1] = vec4(uv, 0, 1); }\n";
        vertex = ShaderSourcePreprocessor.prepare(null, null, vertex,
                PackEngineDefines.standard(), PackEngineDefines.standard().keySet()).source();
        var plan = PackPlanBuilder.build(new PackProgram("gbuffers_skytextured", fragment, null, vertex, null), null);
        assertTrue(plan.executable(), "live sky uniforms/legacy matrices rejected: " + plan.deviations());
        assertEquals(List.of(1, 4), plan.geometryOutputPlan().targetSlots(), "sky authored outputs lost");
        var variants = new LinkedHashMap<VertexFormat, PackPipelines.PackSky>();
        for (var contract : HostLayoutVertexBridge.SKY_CONTRACTS) {
            String source = HostLayoutVertexBridge.skyVertexForContract(plan.convertedVertex(), contract);
            assertTrue(PackCompileCheck.compile(source, true) == null, "sky native variant failed: " + contract);
            assertTrue(source.contains("mat4 mvp = (chimeraSkyProjection() * ModelViewMat);")
                            && source.contains("(Color * ColorModulator)")
                            && source.contains("gbufferModelViewInverse[0]")
                            && source.contains("mat4 gbufferModelViewInverse;")
                            && source.contains("bool hasSkylight;")
                            && !source.contains("in uvec4 EntityIds")
                            && source.contains("void main() { chimeraSkyMain(); gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }"),
                    "sky live inputs, world matrices or early-return clip conversion changed");
            var format = PackPipelines.skyVertexFormat(contract);
            var nativeBuilder = new Pipeline.Builder(format, "test_sky");
            nativeBuilder.setUniformSupplierGetter(info -> () -> null);
            PackPipelines.ordinaryDescriptorContract(plan, Set.of()).apply(nativeBuilder);
            var stageInfo = nativeBuilder.getUBOs().stream().flatMap(block -> block.getUniforms().stream())
                    .filter(field -> field.getName().equals("renderStage")).findFirst().orElseThrow().getInfo();
            var supplier = PackUniformProvider.shared().supplier(stageInfo);
            var previousPhase = PackUniformProvider.setRenderingPhase(PackRenderingPhase.SUN);
            assertEquals(4, supplier.get().getInt(0), "sun stage UBO value");
            var enclosing = PackUniformProvider.setRenderingPhase(PackRenderingPhase.MOON);
            assertEquals(5, supplier.get().getInt(0), "moon stage UBO value");
            PackUniformProvider.setRenderingPhase(enclosing);
            assertEquals(4, supplier.get().getInt(0), "nested stage restore");
            PackUniformProvider.setRenderingPhase(previousPhase);
            variants.put(format, new PackPipelines.PackSky(null, new int[0], source, plan.convertedFragment(),
                    format, plan.geometryOutputPlan(), new ProgramImageBindingManifest(List.of())));
        }
        String fragmentError = PackCompileCheck.compile(plan.convertedFragment(), false);
        assertTrue(fragmentError == null, "sky MRT fragment failed: " + fragmentError);
        var basic = new PackPipelines.PackSkyFamily(variants);
        var textured = new PackPipelines.PackSkyFamily(variants.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> new PackPipelines.PackSky(null, new int[0], "", "", entry.getKey(),
                        GeometryOutputPlan.empty("textured"), new ProgramImageBindingManifest(List.of())))));
        ChimeraSkyBridge.install(basic, textured, null, true, "off");
        ChimeraSkyBridge.setEnabled(true);
        try {
            for (var host : List.of(RenderPipelines.SKY,
                    RenderPipelines.SUNRISE_SUNSET,
                    RenderPipelines.STARS)) {
                assertTrue(ChimeraSkyBridge.shouldUsePackPipeline(host), "missing basic sky shape");
                assertEquals(plan.geometryOutputPlan(), ChimeraSkyBridge.outputPlan(), "wrong sky family");
            }
            for (var host : List.of(RenderPipelines.CELESTIAL,
                    RenderPipelines.END_SKY)) {
                assertTrue(ChimeraSkyBridge.shouldUsePackPipeline(host), "missing textured sky shape");
                assertEquals("textured", ChimeraSkyBridge.outputPlan().programName(), "wrong textured family");
            }
            assertTrue(ChimeraSkyBridge.skipHostClouds(), "authored empty clouds changed");
            assertEquals(CloudStatus.OFF, ChimeraSkyBridge.packCloudStatus(),
                    "pack clouds directive not applied");
            assertTrue(!ChimeraSkyBridge.shouldUsePackPipeline(RenderPipelines.GUI)
                            && !ChimeraSkyBridge.isDrawActive(), "sky selection leaked to GUI");
        } finally {
            ChimeraSkyBridge.disable();
        }
        var level = nativeClass("net/minecraft/client/renderer/LevelRenderer").methods.stream()
                .filter(method -> method.name.equals("method_62216")).findFirst().orElseThrow();
        boolean submitted = false;
        boolean delayedFlush = false;
        for (var instruction : level.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.endsWith("/WeatherEffectRenderer") && call.name.equals("render")) submitted = true;
                if (submitted && call.name.equals("endBatch")) delayedFlush = true;
            }
        }
        assertTrue(delayedFlush, "re-audit native weather flush timing");
        assertEquals("6", PackEngineDefines.standard().get("MC_RENDER_STAGE_STARS"), "Iris star macro ABI");
        assertEquals("21", PackEngineDefines.standard().get("MC_RENDER_STAGE_RAIN_SNOW"), "Iris weather macro ABI");
        assertEquals("8", PackEngineDefines.standard().get("MC_RENDER_STAGE_TERRAIN_SOLID"), "Iris terrain macro ABI");
        var skyClass = nativeClass("net/minecraft/client/renderer/SkyRenderer");
        for (String signature : List.of("renderSkyDisc(I)V", "renderDarkDisc()V",
                "renderSun(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                "renderMoon(Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                "renderStars(FLcom/mojang/blaze3d/vertex/PoseStack;)V",
                "renderSunriseAndSunset(Lcom/mojang/blaze3d/vertex/PoseStack;FI)V", "renderEndSky()V",
                "renderEndFlash(Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V")) {
            assertTrue(skyClass.methods.stream().anyMatch(method -> (method.name + method.desc).equals(signature)),
                    "sky phase hook no longer matches native method: " + signature);
        }
        var celestial = skyClass.methods.stream().filter(method -> method.name.equals("renderSunMoonAndStars")
                && method.desc.equals("(Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V"))
                .findFirst().orElseThrow();
        boolean rotationSeam = false;
        for (var instruction : celestial.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/vertex/PoseStack")
                    && call.name.equals("mulPose") && call.desc.equals("(Lorg/joml/Quaternionfc;)V")) {
                rotationSeam = true;
                break;
            }
        }
        assertTrue(rotationSeam, "re-audit the Iris celestial sun-path rotation seam");
        float previousRotation = PackUniformProvider.currentSunPathRotation();
        try {
            PackUniformProvider.installSunPath(17.5F, 0.0F);
            assertTrue(PackUniformProvider.currentSunPathRotation() == 17.5F,
                    "native celestial geometry must use the pack sun-path rotation");
        } finally {
            PackUniformProvider.installSunPath(previousRotation, 0.0F);
        }
        var immediate = nativeClass("net/chimera/mixin/ChimeraRenderTypeMixin").methods.stream()
                .filter(method -> method.name.equals("chimera$immediateFamilyWindow")).findFirst().orElseThrow();
        boolean weather = false;
        for (var instruction : immediate.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("beginHostLayoutDraw")) weather = true;
        }
        assertTrue(weather, "weather must own its actual immediate draw");
        var indexed = nativeClass("net/chimera/mixin/ChimeraVkRenderPassMixin");
        for (String name : List.of("chimera$familyAttachments", "chimera$skyAttachments")) {
            var method = indexed.methods.stream().filter(candidate -> candidate.name.equals(name)).findFirst().orElseThrow();
            boolean targets = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("beginPackFamilyWindow")) targets = true;
            }
            assertTrue(targets, "both native sky draw APIs must own attachments");
        }
        System.out.println("[chimera] weather draw ownership and sky native variants: PASS");
    }

    private static void verifyRequestedFamilyFallbackAndCrumbling() throws IOException {
        var host = RenderPipelines.CRUMBLING;
        assertEquals(DefaultVertexFormat.BLOCK, host.getVertexFormat(),
                "crumbling host format changed");
        assertTrue(!host.isWriteDepth() && host.getBlendFunction().isPresent()
                        && host.getDepthBiasConstant() == -10.0F && host.getDepthBiasScaleFactor() == -1.0F,
                "crumbling host overlay state changed: depthWrite=" + host.isWriteDepth()
                        + ", blend=" + host.getBlendFunction() + ", constant=" + host.getDepthBiasConstant()
                        + ", slope=" + host.getDepthBiasScaleFactor());
        Path root = Files.createTempDirectory("chimera-family-fallback-");
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
                var ordinary = PackPipelines.ordinaryDescriptorContract(handPlan, Set.of());
                var nativeBuilder = new Pipeline.Builder(
                        ChimeraVertexFormats.EXTENDED_ENTITY, "test_" + name);
                nativeBuilder.setUniformSupplierGetter(info -> () -> null);
                ordinary.apply(nativeBuilder); // Regression used to throw "not admitted type: bool".
                var boolField = nativeBuilder.getUBOs().stream().flatMap(block -> block.getUniforms().stream())
                        .filter(field -> field.getName().equals("hasSkylight")).findFirst().orElseThrow();
                assertEquals("int", boolField.getInfo().type, "native std140 bool storage");
                assertEquals(1, boolField.getSize(), "native std140 bool word count");
            }
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

    /**
     * Iris ShaderKey.LINES: a pack's own gbuffers_line runs as authored on the core inputs
     * and widens its own line; a gbuffers_basic fallback is widened by Iris (main runs at
     * both segment ends, VIEW_SHRINK, +Z normal). Both compile on the host line layout.
     */
    private static void verifyLineLane() throws IOException {
        for (var host : List.of(RenderPipelines.LINES,
                RenderPipelines.LINES_TRANSLUCENT,
                RenderPipelines.SECONDARY_BLOCK_OUTLINE)) {
            assertTrue(HostLayoutLane.forDraw(host, host.getVertexFormat(),
                            HostLayoutLane.Pass.WORLD)
                            == HostLayoutLane.LINE,
                    host.getLocation() + " must route to gbuffers_line");
            assertEquals(0.0F, ChimeraEntityBridge.alphaReference(host),
                    "Iris LINES uses AlphaTests.OFF");
        }
        String fragment = """
                #version 120
                /* DRAWBUFFERS:0 */
                varying vec4 tint;
                varying vec3 normal;
                void main() {
                    gl_FragData[0] = tint + vec4(normal * 0.001, 0.0);
                }
                """;
        Path fallback = Files.createTempDirectory("chimera-line-fallback-");
        Path packLine = Files.createTempDirectory("chimera-line-authored-");
        try {
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
            Files.writeString(shaders.resolve("gbuffers_basic.fsh"), fragment);
            var analysis = PackProbe.analyze(fallback);
            var plan = analysis.plan().program("gbuffers_line");
            assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt("gbuffers_line"),
                    "basic fallback line rejected: " + (plan == null ? "missing" : plan.deviations()));
            assertEquals("gbuffers_basic", analysis.resolution().resolution("gbuffers_line").selectedProgram(),
                    "line fallback source");
            assertEquals(FamilyAdapterPlan.VertexContract.HOST_LINE, plan.familyAdapter().vertexContract(),
                    "line keeps the host layout");
            String converted = plan.convertedVertex();
            assertTrue(converted.contains("layout(location = 3) in float LineWidth;")
                            && converted.contains("void chimeraLineMain()")
                            && converted.contains("chimeraLineOffset = chimeraLineDirection;")
                            && converted.contains("chimeraLineViewScale")
                            && converted.contains("vec3(0.0, 0.0, 1.0)")
                            && converted.contains("tint = (Color * ColorModulator);"),
                    "fallback line lost Iris widening: " + converted);
            var compiled = PackCompileCheck.run(fallback, analysis);
            assertTrue(compiled.failures().isEmpty(), "fallback line compilation: " + compiled.failures());

            shaders = Files.createDirectory(packLine.resolve("shaders"));
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
            Files.writeString(shaders.resolve("gbuffers_line.fsh"), fragment.replace("varying", "in"));
            analysis = PackProbe.analyze(packLine);
            plan = analysis.plan().program("gbuffers_line");
            assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt("gbuffers_line"),
                    "authored line rejected: " + (plan == null ? "missing" : plan.deviations()));
            converted = plan.convertedVertex();
            assertTrue(!converted.contains("chimeraLineMain")
                            && converted.contains("chimeraLineVertexIndex % 2")
                            && converted.contains("ProjMat * ModelViewMat")
                            && converted.contains("Normal.xyz"),
                    "authored line lost Iris core inputs: " + converted);
            compiled = PackCompileCheck.run(packLine, analysis);
            assertTrue(compiled.failures().isEmpty(), "authored line compilation: " + compiled.failures());
        } finally {
            deleteTree(fallback);
            deleteTree(packLine);
        }
        System.out.println("[chimera] line lane, authored and widened: PASS");
    }

    /**
     * Iris ShaderKey.GLINT: gbuffers_armor_glint on the host POSITION_TEX layers, with the
     * glint strength in gl_Color's alpha, NON_ZERO_ALPHA, and unbound material attributes.
     */
    private static void verifyGlintLane() throws IOException {
        var host = RenderPipelines.GLINT;
        assertEquals(DefaultVertexFormat.POSITION_TEX, host.getVertexFormat(),
                "glint host format changed");
        assertEquals(0.0001F, ChimeraEntityBridge.alphaReference(host),
                "Iris GLINT uses AlphaTests.NON_ZERO_ALPHA");
        // A held foil item flushes its glint inside the hand scope; the hand lane has no
        // POSITION_TEX variant, so the lookup must answer host fallback, not throw.
        assertTrue(new PackPipelines.PackHand(Map.of()).forFormat(host.getVertexFormat()) == null,
                "hand glint must fall back to its host draw");
        Path root = Files.createTempDirectory("chimera-glint-");
        try {
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
            var plan = analysis.plan().program("gbuffers_armor_glint");
            assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt("gbuffers_armor_glint"),
                    "glint rejected: " + (plan == null ? "missing" : plan.deviations()));
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
            var compiled = PackCompileCheck.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "glint compilation: " + compiled.failures());
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] glint lane on the host layout: PASS");
    }

    /**
     * The HostLayoutLane table against Iris IrisPipelines and the pack-side contracts, then
     * LIGHTNING (through the entity fallback), BEACON and LEASH converted and compiled with
     * Iris's host-layout inputs and identity: entityId per draw, blockEntityId -1.
     */
    private static void verifyHostLayoutLanes() throws IOException {
        var lanes = HostLayoutLane.values();
        Set<String> servedPrograms = new HashSet<>();
        for (var lane : lanes) {
            assertTrue(HostLayoutLane.of(lane.family()) == lane,
                    lane + " family must name its lane");
            for (var host : lane.pipelines()) {
                assertEquals(lane.format(), host.getVertexFormat(), host.getLocation() + " host format changed");
                assertTrue(HostLayoutLane.forDraw(host, lane.format(), lane.pass()) == lane
                        && lane.serves(host), host.getLocation() + " must route to " + lane);
            }
            FamilyAdapterPlan adapter = FamilyAdapterRegistry.forProgram(lane.program());
            if (lane == HostLayoutLane.WEATHER) {
                assertEquals(FamilyAdapterPlan.VertexContract.HOST_PARTICLE, adapter.vertexContract(),
                        "weather keeps the particle conversion");
            } else {
                assertTrue(adapter.vertexContract().hostLayout(), lane.program() + " must be a host-layout program");
                assertEquals(lane.format(), PackPipelines.hostLayoutFormat(adapter.vertexContract()),
                        lane + " pack layout and host layout disagree");
                servedPrograms.add(lane.program());
            }
        }
        for (String program : List.of("gbuffers_line", "gbuffers_armor_glint", "gbuffers_lightning",
                "gbuffers_beaconbeam", "gbuffers_basic", "shadow_lines", "shadow_leash", "shadow_lightning",
                "shadow_glint", "shadow_text", "shadow_text_bg", "shadow_basic")) {
            assertTrue(servedPrograms.contains(program), program + " has no host-layout lane");
        }
        assertEquals(FamilyAdapterRegistry.hostLayoutLanes().size(), servedPrograms.size(),
                "every host-layout program needs a lane");
        var table = HostLayoutLane.class;
        assertTrue(HostLayoutLane.LINE.whiteAlbedo()
                        && HostLayoutLane.LIGHTNING.whiteAlbedo()
                        && HostLayoutLane.LEASH.whiteAlbedo()
                        && HostLayoutLane.SHADOW_TEXT_BG.whiteAlbedo()
                        && HostLayoutLane.SHADOW_BASIC.whiteAlbedo()
                        && !HostLayoutLane.GLINT.whiteAlbedo()
                        && !HostLayoutLane.SHADOW_TEXT.whiteAlbedo()
                        && !HostLayoutLane.BEACON_BEAM.whiteAlbedo(),
                "Iris binds white to unit 0 exactly for host formats without UV0: " + table.getSimpleName());
        assertTrue(HostLayoutLane.forDraw(
                        RenderPipelines.LINES,
                        RenderPipelines.LINES.getVertexFormat(),
                        HostLayoutLane.Pass.HAND) == null,
                "a world-only lane must not take a first-person draw");
        for (var host : List.of(RenderPipelines.LIGHTNING,
                RenderPipelines.DRAGON_RAYS,
                RenderPipelines.BEACON_BEAM_OPAQUE,
                RenderPipelines.BEACON_BEAM_TRANSLUCENT,
                RenderPipelines.LEASH)) {
            assertEquals(0.0F, ChimeraEntityBridge.alphaReference(host),
                    "Iris LIGHTNING, BEACON and LEASH use AlphaTests.OFF");
        }

        Path root = Files.createTempDirectory("chimera-host-layout-");
        try {
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
                            "return vec4(Position + ModelOffset, 1.0);"),
                    "gbuffers_basic", List.of("layout(location = 1) in vec4 Color;",
                            "const vec2 UV0 = vec2(0.5);", "layout(location = 2) in ivec2 UV2;",
                            "const vec3 Normal = vec3(0.0, 0.0, 1.0);"));
            for (var entry : expected.entrySet()) {
                var plan = analysis.plan().program(entry.getKey());
                assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt(entry.getKey()),
                        entry.getKey() + " rejected: " + (plan == null ? "missing" : plan.deviations()));
                String vertex = plan.convertedVertex();
                for (String line : entry.getValue()) {
                    assertTrue(vertex.contains(line), entry.getKey() + " lost Iris host input " + line + ": " + vertex);
                }
                // Iris draws these batches with its fallback entityId already reset to 0.
                assertTrue(vertex.contains("(Color * ColorModulator)")
                                && vertex.contains("(0) == 10101")
                                && vertex.contains("float((-1))")
                                && !vertex.contains("chimeraEntityId")
                                && plan.convertedFragment().contains("(0) == 10101"),
                        entry.getKey() + " must read entityId 0 and blockEntityId -1: " + vertex);
                assertTrue(!plan.alphaTestPlan().perDrawReference(),
                        entry.getKey() + " has no Iris alpha test (AlphaTests.OFF)");
            }
            var compiled = PackCompileCheck.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "host-layout lane compilation: " + compiled.failures());
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] host-layout lanes (lightning, beacon, lead) and Iris's draw-time entityId: PASS");
    }

    /**
     * shadow_entities (Iris SHADOW_ENTITIES_CUTOUT): with no authored shadow_entities, the shadow
     * program converts onto the entity layout, reads the draw's host matrices, carries entityId
     * and blockEntityId per vertex, and tests alpha per draw; the pass draws entity casters
     * before the shadowtex1 snapshot and drops what it cannot serve.
     */
    private static void verifyShadowEntityCasters() throws IOException {
        Path root = Files.createTempDirectory("chimera-shadow-entities-");
        try {
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
            String vertex = plan.convertedVertex();
            for (String line : List.of("layout(location = 6) in uvec4 EntityIds;",
                    "return ProjMat * ModelViewMat * chimeraEntityVertexValue();",
                    "mat3(ModelViewMat) * chimeraNormalValue()", "TextureMat * chimeraTexCoord0Value()",
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
            String terrain = analysis.plan().program("shadow").convertedVertex();
            assertTrue(terrain.contains("MVP") && !terrain.contains("EntityIds"),
                    "the terrain shadow program keeps its own inputs: " + terrain);
            var compiled = PackCompileCheck.run(root, analysis);
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
        } finally {
            deleteTree(root);
        }
        assertEquals(ShadowCasterDirectives.DEFAULTS, ShadowCasterDirectives.fromProperties(Map.of()),
                "Iris defaults: entities and block entities on, player and light block entities off");

        var shadowMap = nativeClass("net/chimera/render/PackShadowPass").methods.stream()
                .filter(method -> method.name.equals("renderShadowMap")).findFirst().orElseThrow();
        boolean casters = false;
        boolean snapshotAfterCasters = false;
        for (var instruction : shadowMap.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("renderShadowCasters")) casters = true;
                if (casters && call.name.equals("captureOpaqueDepth")) snapshotAfterCasters = true;
            }
        }
        assertTrue(snapshotAfterCasters, "entity casters must draw before the shadowtex1 snapshot, as in Iris");
        var draw = nativeClass("net/chimera/mixin/ChimeraRenderTypeMixin").methods.stream()
                .filter(method -> method.name.equals("chimera$immediateFamilyWindow")).findFirst().orElseThrow();
        boolean lane = false;
        boolean guard = false;
        boolean dropped = false;
        for (var instruction : draw.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("beginHostLayoutDraw")) lane = true;
                if (lane && call.name.equals("isShadowCasterPass")) guard = true;
                if (guard && call.name.equals("close") && call.owner.equals("com/mojang/blaze3d/vertex/MeshData")) {
                    dropped = true;
                }
            }
        }
        assertTrue(dropped, "a shadow-pass draw tries its shadow lane first; one without an installed lane "
                + "must be dropped, not drawn with its host shader");
        System.out.println("[chimera] shadow_entities conversion, directives and caster order: PASS");
    }

    /**
     * The shadow pass's host-layout lanes (Iris IrisPipelines.assignToShadow): each key runs the
     * Iris program it names through the fallback chain, never a pack file of its own name, on its
     * host layout with Iris's inputs. Leads, lines, lightning, text backgrounds and the water mask
     * read entityId per draw and blockEntityId -1; glint and text read the unbound per-vertex 0.
     * Lines are widened. Every lane tests alpha per draw and compiles.
     */
    private static void verifyShadowLanes() throws IOException {
        var shadowPass = HostLayoutLane.Pass.SHADOW;
        Map<RenderPipeline, HostLayoutLane> routes = Map.of(
                RenderPipelines.LINES, HostLayoutLane.SHADOW_LINES,
                RenderPipelines.SECONDARY_BLOCK_OUTLINE,
                HostLayoutLane.SHADOW_LINES,
                RenderPipelines.LEASH, HostLayoutLane.SHADOW_LEASH,
                RenderPipelines.LIGHTNING,
                HostLayoutLane.SHADOW_LIGHTNING,
                RenderPipelines.GLINT, HostLayoutLane.SHADOW_GLINT,
                RenderPipelines.TEXT, HostLayoutLane.SHADOW_TEXT,
                RenderPipelines.TEXT_SEE_THROUGH,
                HostLayoutLane.SHADOW_TEXT,
                RenderPipelines.TEXT_BACKGROUND,
                HostLayoutLane.SHADOW_TEXT_BG,
                RenderPipelines.WATER_MASK,
                HostLayoutLane.SHADOW_BASIC);
        for (var route : routes.entrySet()) {
            assertTrue(HostLayoutLane.forDraw(route.getKey(),
                            route.getKey().getVertexFormat(), shadowPass) == route.getValue(),
                    route.getKey().getLocation() + " must route to " + route.getValue() + " in the shadow pass");
        }
        // Iris assigns no shadow key to these (or cancels them): they stay out of the map.
        for (var host : List.of(RenderPipelines.LINES_TRANSLUCENT,
                RenderPipelines.DRAGON_RAYS,
                RenderPipelines.TEXT_INTENSITY,
                RenderPipelines.BEACON_BEAM_OPAQUE)) {
            assertTrue(HostLayoutLane.forDraw(host, host.getVertexFormat(), shadowPass) == null,
                    host.getLocation() + " has no Iris shadow key");
        }
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

        Path root = Files.createTempDirectory("chimera-shadow-lanes-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            writeLegacyShadowProgram(shaders);
            // A pack file named like a shader key is not an Iris program and is never selected.
            Files.writeString(shaders.resolve("shadow_text.vsh"), "#version 120\nvoid main() { decoy(); }\n");
            Files.writeString(shaders.resolve("shadow_text.fsh"), "#version 120\nvoid main() { decoy(); }\n");
            var analysis = PackProbe.analyze(root);
            Map<String, List<String>> inputs = Map.of(
                    "shadow_lines", List.of("layout(location = 3) in float LineWidth;", "void chimeraLineMain()",
                            "chimeraLineViewScale", "chimeraShadowUniforms.viewWidth"),
                    "shadow_leash", List.of("layout(location = 2) in ivec2 UV2;", "const vec2 UV0 = vec2(0.5);"),
                    "shadow_lightning", List.of("layout(location = 1) in vec4 Color;", "const ivec2 UV2 = ivec2(240);"),
                    "shadow_text_bg", List.of("layout(location = 2) in ivec2 UV2;", "const vec2 UV0 = vec2(0.5);"),
                    "shadow_basic", List.of("const vec4 Color = vec4(1.0);", "const ivec2 UV2 = ivec2(240);"),
                    "shadow_text", List.of("layout(location = 2) in vec2 UV0;", "layout(location = 3) in ivec2 UV2;"),
                    "shadow_glint", List.of("layout(location = 1) in vec2 UV0;",
                            "const vec4 Color = vec4(0.0, 0.0, 0.0, 1.0);"));
            for (var entry : inputs.entrySet()) {
                String key = entry.getKey();
                var plan = analysis.plan().program(key);
                assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt(key),
                        key + " rejected: " + (plan == null ? "missing" : plan.deviations()));
                assertEquals("shadow", analysis.resolution().resolution(key).selectedProgram(),
                        key + " must run Iris's shadow program through its fallback chain");
                String vertex = plan.convertedVertex();
                for (String line : entry.getValue()) {
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
                if (FamilyAdapterRegistry.readsEntityIdsPerVertex(key)) {
                    assertTrue(vertex.contains("const uvec4 EntityIds = uvec4(0);")
                                    && vertex.contains("chimeraEntityId = EntityIds.x;")
                                    && fragment.contains("int(chimeraBlockEntityId) / 100 == 155"),
                            key + " reads Iris's entity-patched ids, unbound on the host mesh: " + vertex);
                } else {
                    assertTrue(vertex.contains("(0) == 10001")
                                    && !vertex.contains("chimeraEntityId")
                                    && fragment.contains("(-1) / 100 == 155"),
                            key + " must read entityId 0 and blockEntityId -1: " + vertex + "\n" + fragment);
                }
                assertTrue(fragment.contains("chimeraShadowColor1") && plan.alphaTestPlan().perDrawReference(),
                        key + " must write both shadow colours and test alpha per draw: " + fragment);
                assertTrue(plan.deviations().contains(FamilyAdapterRegistry.hostLayoutLane(key) + "_VERTEX_BRIDGE"),
                        key + " must record its vertex bridge: " + plan.deviations());
            }
            var compiled = PackCompileCheck.run(root, analysis);
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
        } finally {
            deleteTree(root);
        }

        var flames = nativeClass("net/chimera/mixin/ChimeraFlameFeatureRendererMixin").methods.stream()
                .filter(method -> method.name.equals("chimera$packFlames")).findFirst().orElseThrow();
        boolean flameId = false;
        boolean family = false;
        for (var instruction : flames.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("flameEntityId")) flameId = true;
                if (call.name.equals("emit") && call.owner.equals("net/chimera/render/ChimeraFamilyDraw")) family = true;
            }
        }
        assertTrue(flameId && family, "burning-entity fire must draw through the entity family with Iris's entity_flame id");
        System.out.println("[chimera] shadow lanes (lines, leads, lightning, glint, text, water mask) and flames: PASS");
    }

    /**
     * Iris ShadowRenderer.createShadowFrustum: the culling each shadow.culling state picks, with
     * its distances, and the advanced frustum's planes against known casters.
     */
    private static void verifyShadowCulling() {
        int renderDistance = 192;
        BiFunction<Map<String, String>, Map<String, String>, ShadowCullingPlan> plan =
                (properties, constants) -> ShadowCullingPlan.of(properties,
                        new PackConfig.ShadowSettings(2048, 128.0F, constants, List.of()));
        Map<String, String> unitRender = Map.of("shadowDistanceRenderMul", "1.0");
        var advanced = ShadowCullingPlan.Mode.ADVANCED;
        double none = Double.POSITIVE_INFINITY;

        ShadowCullingPlan unset = plan.apply(Map.of(), unitRender);
        assertEquals(ShadowCullingPlan.State.DEFAULT, unset.state(), "no shadow.culling is Iris DEFAULT");
        assertEquals(new ShadowCullingPlan.Culler(advanced, 128.0, 0.0), unset.terrain(renderDistance),
                "DEFAULT without a geometry stage is advanced, BoxCuller(shadowDistance * renderMul)");
        assertEquals(unset.terrain(renderDistance), unset.entities(renderDistance),
                "entityShadowDistanceMul 1 reuses the terrain frustum");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0), unset.terrain(96),
                "a box at or past the render distance is no box");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0),
                plan.apply(Map.of(), Map.of()).terrain(renderDistance),
                "renderMul unset (Iris -1) means the player's shadow distance, 32 chunks by default");
        assertEquals(new ShadowCullingPlan.Culler(advanced, 0.0, 0.0),
                plan.apply(Map.of(), Map.of("shadowDistanceRenderMul", "0.0")).terrain(renderDistance),
                "a zero distance keeps Iris's BoxCuller(0)");
        assertEquals(unset.terrain(renderDistance),
                plan.apply(Map.of("shadow.culling", "true"), unitRender).terrain(renderDistance), "true is ADVANCED");

        ShadowCullingPlan distance = plan.apply(Map.of("shadow.culling", "false"), unitRender);
        assertEquals(new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.DISTANCE, 128.0, 0.0),
                distance.terrain(renderDistance), "false culls by distance only");
        assertEquals(ShadowCullingPlan.Culler.NONE, distance.terrain(96), "a distance past the render distance culls nothing");
        assertEquals(ShadowCullingPlan.Culler.NONE,
                plan.apply(Map.of("shadow.culling", "false"), Map.of()).terrain(renderDistance),
                "a negative distance culls nothing");

        ShadowCullingPlan safeZone = plan.apply(Map.of("shadow.culling", "reversed"), Map.of("voxelDistance", "32.0"));
        assertEquals(new ShadowCullingPlan.Culler(ShadowCullingPlan.Mode.SAFE_ZONE, 128.0, 32.0),
                safeZone.terrain(renderDistance),
                "reversed: voxelDistance box always drawn, outer box shadowDistance (renderMul -1 taken as 1)");
        assertEquals(safeZone.terrain(renderDistance),
                plan.apply(Map.of("shadow.culling", "safe_zone"), Map.of("voxelDistance", "32.0")).terrain(renderDistance),
                "safe_zone is reversed");

        assertEquals(new ShadowCullingPlan.Culler(advanced, 32.0, 0.0),
                plan.apply(Map.of(), Map.of("shadowDistanceRenderMul", "1.0", "entityShadowDistanceMul", "0.25"))
                        .entities(renderDistance), "entities: BoxCuller(shadowDistance * renderMul * entityMul)");
        assertEquals(new ShadowCullingPlan.Culler(advanced, none, 0.0),
                plan.apply(Map.of(), Map.of("entityShadowDistanceMul", "0.25")).entities(renderDistance),
                "a negative entity multiplier product is the player's shadow distance");
        ShadowCullingPlan malformed = plan.apply(Map.of("shadow.culling", "maybe"), unitRender);
        assertTrue(malformed.state() == ShadowCullingPlan.State.DEFAULT
                        && malformed.deviations().contains("SHADOW_CULLING_DIRECTIVE_MALFORMED:maybe"),
                "Iris keeps the default for an unrecognized value: " + malformed);

        // A camera at the origin looking down -Z, 70 degree vertical field, 16:9; planes only.
        Matrix4f camera = new Matrix4f()
                .perspective((float) Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 1000.0F);
        var open = new ShadowCullingPlan.Culler(advanced, none, 0.0);
        var overhead = new ShadowCullingFrustum(open, camera,
                new Vector3f(0.0F, 1.0F, 0.0F));
        assertTrue(cube(overhead, 0, 0, -20), "a visible caster is kept");
        assertTrue(cube(overhead, 0, 50, -20), "a caster above the view shadows what the camera sees");
        assertTrue(!cube(overhead, 0, 50, 20), "with the sun overhead, a caster behind the camera shadows nothing seen");
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
        System.out.println("[chimera] Iris shadow culling (advanced, safe zone, distance): PASS");
    }

    private static boolean cube(ShadowCullingFrustum frustum, float x, float y, float z) {
        return frustum.visible(x, y, z, x + 1.0F, y + 1.0F, z + 1.0F);
    }

    /**
     * One version rule (any desktop 110-460, compatibility or core), modern declarations in the
     * entity-style converters, Iris core-profile names, and GL's discard of an output past the
     * route, on a Photon-shaped pack.
     */
    private static void verifyGlslBreadth() throws IOException {
        for (String accepted : List.of("#version 110", "#version 120e", "#version 150", "#version 330 core",
                "#version 400 compatibility", "#version 430 compatibility", "#version 460")) {
            assertTrue(GlslVersion.declaredAndSupported(accepted + "\nvoid main() {}"), accepted + " is a desktop version");
        }
        for (String refused : List.of("#version 300 es", "#version 100", "#version 470", "#version 200")) {
            assertTrue(!GlslVersion.declaredAndSupported(refused + "\nvoid main() {}"), refused + " is not");
        }
        assertTrue(GlslVersion.first("#version 400").irisCore() && GlslVersion.first("#version 140 core").irisCore()
                        && !GlslVersion.first("#version 400 compatibility").irisCore()
                        && !GlslVersion.first("#version 130").irisCore(),
                "Iris core mode: the core profile, or 150 and above with no profile");
        assertEquals("writeonly uniform image3D a;\nwriteonly uniform image3D b;",
                GlslTokenRewriter.splitUniformDeclarators("writeonly uniform image3D a, b;"),
                "each image of a list takes its own binding");
        assertEquals("layout(rgba8) uniform writeonly image3D a;\nlayout(rgba8) uniform writeonly image3D b;",
                GlslTokenRewriter.splitUniformDeclarators("layout(rgba8) uniform writeonly image3D a, b;"),
                "qualifiers on either side of uniform stay on every declarator");
        assertEquals("#define W writeonly\nuniform sampler3D a;\nuniform sampler3D b;",
                GlslTokenRewriter.splitUniformDeclarators("#define W writeonly\nuniform sampler3D a, b;"),
                "a directive before the declaration is not a qualifier");
        assertEquals("/* a comment\nover two lines */\n\nin vec3 mc_Entity;",
                GlslTokenRewriter.expandStorageQualifierAliases(
                        "/* a comment\nover two lines */\n#define attribute in\nattribute vec3 mc_Entity;"),
                "a storage-qualifier alias applies after its definition, lexed as one stream");

        Path root = Files.createTempDirectory("chimera-glsl-breadth-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                    #version 400 compatibility
                    #define attribute in
                    out vec2 uv;
                    flat out uint material_mask;
                    flat out mat3 tbn;
                    attribute vec4 at_tangent;
                    attribute vec3 mc_Entity;
                    attribute vec2 mc_midTexCoord;
                    void main() {
                        uv = gl_MultiTexCoord0.xy + mc_midTexCoord * 0.0;
                        material_mask = uint(gl_VertexID & 1) + uint(mc_Entity.x);
                        tbn = mat3(at_tangent.xyz, gl_Normal, cross(at_tangent.xyz, gl_Normal));
                        gl_Position = gl_ProjectionMatrix * (gl_ModelViewMatrix * gl_Vertex);
                    }
                    """);
            Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                    #version 400 compatibility
                    uniform sampler2D gtexture;
                    in vec2 uv;
                    flat in uint material_mask;
                    flat in mat3 tbn;
                    layout (location = 0) out vec4 gbuffer_data_0;
                    layout (location = 1) out vec4 gbuffer_data_1;
                    /* RENDERTARGETS: 1 */
                    float[2] unused_pair() { return float[2](0.0, 1.0); }
                    void main() {
                        gbuffer_data_0 = texture(gtexture, uv) * float(material_mask + 1u) * tbn[0].x;
                        gbuffer_data_1 = vec4(tbn[2], 1.0);
                    }
                    """);
            Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                    #version 400
                    in vec3 vaPosition;
                    in vec4 vaColor;
                    in ivec2 vaUV2;
                    uniform mat4 modelViewMatrix;
                    uniform mat4 projectionMatrix;
                    out vec4 tint;
                    out vec2 light_levels;
                    void main() {
                        tint = vaColor;
                        light_levels = vec2(vaUV2) / 240.0;
                        gl_Position = projectionMatrix * modelViewMatrix * vec4(vaPosition, 1.0);
                    }
                    """);
            Files.writeString(shaders.resolve("gbuffers_basic.fsh"), """
                    #version 400
                    in vec4 tint;
                    in vec2 light_levels;
                    layout (location = 0) out vec4 color;
                    /* RENDERTARGETS: 0 */
                    void main() {
                        color = tint * light_levels.x;
                    }
                    """);
            Files.writeString(shaders.resolve("shadow.vsh"), """
                    #version 430 compatibility
                    out vec2 uv;
                    attribute vec3 mc_Entity;
                    void main() {
                        uv = gl_MultiTexCoord0.xy + mc_Entity.xy * 0.0;
                        gl_Position = ftransform();
                    }
                    """);
            Files.writeString(shaders.resolve("shadow.fsh"), """
                    #version 430 compatibility
                    uniform sampler2D gtexture;
                    in vec2 uv;
                    layout (location = 0) out vec3 shadowcolor0_out;
                    /* RENDERTARGETS: 0 */
                    void main() {
                        shadowcolor0_out = texture(gtexture, uv).rgb;
                    }
                    """);
            var analysis = PackProbe.analyze(root);
            for (String name : List.of("gbuffers_entities", "gbuffers_basic", "shadow")) {
                var plan = analysis.plan().program(name);
                assertTrue(plan != null && plan.executable(),
                        name + " must convert: " + (plan == null ? "missing" : plan.deviations()));
            }
            var entities = analysis.plan().program("gbuffers_entities");
            assertTrue(entities.convertedVertex().startsWith("#version 460")
                            && entities.convertedVertex().contains("gl_VertexIndex")
                            && !entities.convertedVertex().contains("attribute"),
                    "a 400 compatibility entity vertex converts with its modern declarations: "
                            + entities.convertedVertex());
            assertTrue(entities.deviations().contains("FRAGMENT_OUTPUT_DISCARDED:1")
                            && entities.convertedFragment().contains("vec4 gbuffer_data_1;")
                            && !entities.convertedFragment().contains("unused_pair"),
                    "an output past the route is written to nothing, and an unread array function goes: "
                            + entities.deviations() + "\n" + entities.convertedFragment());
            String basic = analysis.plan().program("gbuffers_basic").convertedVertex();
            assertTrue(!basic.contains("vaPosition") && !basic.contains("modelViewMatrix")
                            && basic.contains("ivec2(vec4(vec2(UV2), 0.0, 1.0).xy)"),
                    "Iris core names become the host inputs: " + basic);
            assertTrue(analysis.plan().program("shadow").convertedFragment()
                            .contains("layout(location = 0) out vec3 chimeraShadowColor0;"),
                    "a shadow colour keeps the type the pack writes");
            var compiled = PackCompileCheck.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "GLSL breadth compilation: " + compiled.failures());
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] GLSL breadth (versions, modern entity declarations, core names, discarded outputs): PASS");
    }

    /**
     * Solas declares its two floodfill images, and their samplers, each in one statement. The
     * plan and the runtime translator must see both names: Solas fell to vanilla in game with
     * "unsupported compute image: floodfill_img_copy".
     */
    private static void verifyComputeDeclarationLists() throws IOException {
        Path root = Files.createTempDirectory("chimera-compute-lists-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"), """
                    iris.features.optional=CUSTOM_IMAGES
                    image.floodfill_img = floodfillSampler rgba rgba16f half_float false false 8 4 8
                    image.floodfill_img_copy = floodfillSamplerCopy rgba rgba16f half_float false false 8 4 8
                    """);
            Files.writeString(shaders.resolve("shadowcomp.csh"), """
                    #version 430 compatibility
                    layout (local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
                    uniform int frameCounter;
                    writeonly uniform image3D floodfill_img, floodfill_img_copy;
                    uniform sampler3D floodfillSampler, floodfillSamplerCopy;
                    vec3 gather(sampler3D image, ivec3 pos) {
                        return texelFetch(image, pos + ivec3(1, 0, 0), 0).rgb;
                    }
                    void main() {
                        ivec3 pos = ivec3(gl_GlobalInvocationID);
                        if ((frameCounter & 1) == 0) {
                            imageStore(floodfill_img_copy, pos, vec4(gather(floodfillSampler, pos), 1.0));
                        } else {
                            imageStore(floodfill_img, pos, vec4(gather(floodfillSamplerCopy, pos), 1.0));
                        }
                    }
                    """);
            Files.writeString(shaders.resolve("final.fsh"), """
                    #version 120
                    uniform sampler2D colortex0;
                    void main() {
                        gl_FragColor = texture2D(colortex0, vec2(0.5));
                    }
                    """);
            PackPlan plan = PackProbe.analyze(root).plan();
            PackAdvancedResourcePlan.ComputeSpec compute = plan.advancedResources().computeStages().get("shadowcomp");
            assertTrue(compute != null && compute.supported(), "the list-declared shadowcomp is supported: "
                    + (compute == null ? "missing" : compute.deviations()));
            assertEquals(List.of("floodfill_img", "floodfill_img_copy"), compute.imageNames(),
                    "the plan records every image of a declaration list");
            assertEquals(List.of("floodfillSampler", "floodfillSamplerCopy"), compute.samplerNames(),
                    "the plan records every sampler of a declaration list");
            String translated = PackShadowCompute.translatedModule(plan.advancedResources(),
                    shaders, plan.settingsPreprocessorDefines(), plan.settingsOverriddenNames()).source();
            assertTrue(translated.contains("layout(rgba16f, binding = 1) uniform image3D floodfill_img_copy;")
                            && translated.contains("layout(binding = 3) uniform sampler3D floodfillSamplerCopy;"),
                    "each listed resource takes its own binding: " + translated);
            String error = PackCompileCheck.compileShadowCompute(root, plan);
            assertTrue(error == null, "the translated shadowcomp compiles: " + error);
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] compute declaration lists (plan, translation, compile): PASS");
    }

    /**
     * The shadowcomp uniform block is written while a frame is recorded, so each frame in flight
     * owns one: a single block could be rewritten while the GPU still ran the previous frame's
     * dispatch.
     */
    private static void verifyComputeFrameSlots() throws IOException {
        var compute = nativeClass("net/chimera/render/PackShadowCompute");
        var dispatch = compute.methods.stream().filter(method -> method.name.equals("dispatch"))
                .findFirst().orElseThrow(() -> new AssertionError("PackShadowCompute.dispatch is missing"));
        boolean frameSlot = false;
        for (var instruction : dispatch.instructions) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/vulkanmod/vulkan/Renderer") && call.name.equals("getCurrentFrame")) {
                frameSlot = true;
            }
        }
        assertTrue(frameSlot, "shadowcomp must bind the current frame slot's uniform block");
        assertTrue(compute.fields.stream().noneMatch(field -> field.name.equals("uniformData")
                        || field.name.equals("descriptorSet")),
                "shadowcomp keeps no uniform block or descriptor set shared across frames in flight");
        System.out.println("[chimera] shadowcomp uniforms per frame in flight: PASS");
    }

    /**
     * Solas declares cameraPosition (vec3) before frameCounter. Dispatch wrote frameCounter after
     * the vec3's padding word, implicit std140 packed it into that word, so the compute read 0,
     * wrote only floodfill_img_copy, and every light flickered as the gbuffers alternated images.
     */
    private static void verifyComputeUniformOffsets() throws IOException {
        Path root = Files.createTempDirectory("chimera-compute-offsets-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"), """
                    iris.features.optional=CUSTOM_IMAGES
                    image.floodfill_img = floodfillSampler rgba rgba16f half_float false false 8 4 8
                    image.floodfill_img_copy = floodfillSamplerCopy rgba rgba16f half_float false false 8 4 8
                    """);
            Files.writeString(shaders.resolve("shadowcomp.csh"), """
                    #version 430 compatibility
                    layout (local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
                    uniform vec3 cameraPosition;
                    uniform int frameCounter;
                    uniform float frameTimeCounter;
                    uniform vec3 previousCameraPosition;
                    uniform float wetness;
                    writeonly uniform image3D floodfill_img, floodfill_img_copy;
                    uniform sampler3D floodfillSampler, floodfillSamplerCopy;
                    void main() {
                        ivec3 pos = ivec3(gl_GlobalInvocationID);
                        vec3 shift = cameraPosition - previousCameraPosition;
                        vec4 value = vec4(shift, frameTimeCounter + wetness);
                        if ((frameCounter & 1) == 0) {
                            imageStore(floodfill_img_copy, pos, value + texelFetch(floodfillSampler, pos, 0));
                        } else {
                            imageStore(floodfill_img, pos, value + texelFetch(floodfillSamplerCopy, pos, 0));
                        }
                    }
                    """);
            Files.writeString(shaders.resolve("final.fsh"), """
                    #version 120
                    uniform sampler2D colortex0;
                    void main() {
                        gl_FragColor = texture2D(colortex0, vec2(0.5));
                    }
                    """);
            PackPlan plan = PackProbe.analyze(root).plan();
            var module = PackShadowCompute.translatedModule(plan.advancedResources(),
                    shaders, plan.settingsPreprocessorDefines(), plan.settingsOverriddenNames());
            assertEquals(List.of("cameraPosition", "frameCounter", "frameTimeCounter", "previousCameraPosition",
                            "wetness"), module.uniforms().stream().map(uniform -> uniform.name()).toList(),
                    "every live value joins the uniform block");
            String error = PackCompileCheck.compileShadowCompute(root, plan);
            assertTrue(error == null, "the compiled block reads each value where dispatch writes it: " + error);
            // The block as Chimera declared it before, with implicit offsets, against the offsets
            // dispatch wrote then: frameCounter after the vec3's padding word, at 16.
            String implicit = """
                    #version 450
                    layout(local_size_x = 1) in;
                    layout(std140, binding = 0) uniform ChimeraShadowComputeValues {
                        vec3 cameraPosition;
                        int frameCounter;
                    };
                    layout(rgba16f, binding = 1) uniform image3D target;
                    void main() {
                        imageStore(target, ivec3(0), vec4(cameraPosition, float(frameCounter)));
                    }
                    """;
            String mismatch = PackCompileCheck.compileComputeValues(implicit,
                    Map.of("cameraPosition", 0, "frameCounter", 16));
            assertTrue(mismatch != null && mismatch.contains("frameCounter"),
                    "the offset check reports the implicit layout's frameCounter: " + mismatch);
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] shadowcomp uniform offsets (declared offsets, compiled block): PASS");
    }

    /**
     * Properties.load skips a line's leading whitespace. Photon indents its composite17 switch
     * inside {@code #ifdef MC_GL_RENDERER_INTEL}; read with the indent, the switch never applied.
     */
    private static void verifyIndentedProperties() throws IOException {
        Path root = Files.createTempDirectory("chimera-indented-properties-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"),
                    "#ifdef MC_GL_RENDERER_INTEL\n\tprogram.composite1.enabled = true\n#else\n"
                            + "\tprogram.composite1.enabled = false\n#endif\n");
            String post = "#version 120\nuniform sampler2D colortex0;\n"
                    + "void main() { gl_FragColor = texture2D(colortex0, vec2(0.5)); }\n";
            Files.writeString(shaders.resolve("composite1.fsh"), post);
            Files.writeString(shaders.resolve("final.fsh"), post);
            var analysis = PackProbe.analyze(root);
            assertEquals(Boolean.FALSE, analysis.settings().programEnabled().get("composite1"),
                    "an indented program switch applies");
            assertEquals(1, analysis.plan().disabledProgramCount(), "the switched-off program is disabled");
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] indented shaders.properties lines: PASS");
    }

    /** Iris renders shadows before any terrain: the shadow segment runs at the opaque layer's HEAD. */
    private static void verifyShadowPassOrder() throws IOException {
        var hook = nativeClass("net/chimera/mixin/WorldRendererMixin").methods.stream()
                .filter(method -> {
                    for (var instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call
                                && call.name.equals("renderShadowSegment")) {
                            return true;
                        }
                    }
                    return false;
                }).findFirst().orElseThrow(() -> new AssertionError("no mixin method starts the shadow segment"));
        String point = null;
        for (var annotation : hook.visibleAnnotations == null
                ? List.<AnnotationNode>of() : hook.visibleAnnotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (!annotation.values.get(i).equals("at")) continue;
                var at = (AnnotationNode) ((List<?>) annotation.values.get(i + 1)).get(0);
                for (int j = 0; j < at.values.size(); j += 2) {
                    if (at.values.get(j).equals("value")) point = (String) at.values.get(j + 1);
                }
            }
        }
        assertEquals("HEAD", point, "the shadow map must render before the opaque layer samples it");
        System.out.println("[chimera] shadow pass before opaque terrain: PASS");
    }

    /**
     * Iris hands packs at_midBlock as four unnormalized signed bytes: the offset to the block's
     * centre in 1/64 blocks, then its light emission. Solas voxelizes each block at
     * gl_Vertex + at_midBlock / 64.0; Chimera's helper had already divided by 64, so blocks landed
     * on voxel boundaries, neighbours raced into one voxel, and Solas's lights flickered.
     */
    private static void verifyMidBlockInputs() throws IOException {
        Path root = Files.createTempDirectory("chimera-mid-block-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            writeLegacyShadowProgram(shaders);
            Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                    #version 330 compatibility
                    in vec3 vaPosition;
                    in vec2 vaUV0;
                    in vec4 at_midBlock;
                    out vec2 terrainUv;
                    out vec4 blockCentre;
                    void main() {
                        gl_Position = vec4(vaPosition, 1.0);
                        terrainUv = vaUV0;
                        blockCentre = vec4(vaPosition + at_midBlock.xyz / 64.0, at_midBlock.w / 15.0);
                    }
                    """);
            Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                    #version 330 compatibility
                    uniform sampler2D texture;
                    in vec2 terrainUv;
                    in vec4 blockCentre;
                    out vec4 fragColor;
                    void main() {
                        fragColor = texture2D(texture, terrainUv) * blockCentre;
                    }
                    """);
            var analysis = PackProbe.analyze(root);
            String shadow = analysis.plan().program("shadow").convertedVertex();
            assertTrue(shadow.contains("chimeraMidBlockValue().xyz")
                            && shadow.contains("return vec4(chimeraMidBlockRawValue());"),
                    "a vec3 at_midBlock reads Iris's raw 1/64-block offset: " + shadow);
            String terrain = analysis.plan().program("gbuffers_terrain").convertedVertex();
            assertTrue(terrain.contains("chimeraMidBlockValue().xyz / 64.0")
                            && terrain.contains("chimeraMidBlockValue().w / 15.0")
                            && terrain.contains("return vec4(chimeraMidBlockRawValue());"),
                    "a vec4 at_midBlock reads the raw offset and the emission byte: " + terrain);
            var compiled = PackCompileCheck.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "at_midBlock programs compile: " + compiled.failures());
        } finally {
            deleteTree(root);
        }
        System.out.println("[chimera] terrain at_midBlock inputs (raw offset, emission): PASS");
    }

    /**
     * Iris reads oldLighting and separateAo (both default off). Without oldLighting it meshes
     * terrain and fluids unshaded: ClientLevel.getShade sees shaded = false; Chimera kept
     * Minecraft's face shading, so Solas's walls and ceiling rendered at 0.5-0.8 of Iris's
     * brightness. With separateAo it keeps the tint in RGB and carries the light pipeline's
     * brightness in alpha; Chimera multiplied it into RGB and gave packs a constant AO word
     * nobody read, so BSL, Complementary, Bliss, Photon and Solas darkened corners twice.
     */
    private static void verifyTerrainShading() throws IOException {
        assertEquals(new TerrainShadingDirectives(false, false, List.of()), TerrainShadingDirectives.fromProperties(Map.of()),
                "Iris's oldLighting and separateAo default to off");
        assertTrue(TerrainShadingDirectives.DEFAULTS.disablesDirectionalShading()
                        && !TerrainShadingDirectives.VANILLA.disablesDirectionalShading()
                        && !TerrainShadingDirectives.VANILLA.separateAo(),
                "a pack without directives lights faces itself; vanilla keeps shading and AO in RGB");
        for (String key : List.of("oldLighting", "separateAo")) {
            for (String value : List.of("true", "1", " true ")) {
                var directives = TerrainShadingDirectives.fromProperties(Map.of(key, value));
                assertTrue(terrainShading(directives, key) && directives.deviations().isEmpty(), key + "=" + value + " switches on");
            }
            for (String value : List.of("false", "0")) {
                var directives = TerrainShadingDirectives.fromProperties(Map.of(key, value));
                assertTrue(!terrainShading(directives, key) && directives.deviations().isEmpty(), key + "=" + value + " switches off");
            }
        }
        var malformed = TerrainShadingDirectives.fromProperties(Map.of("oldLighting", "yes", "separateAo", "maybe"));
        assertTrue(malformed.disablesDirectionalShading() && !malformed.separateAo()
                        && malformed.deviations().containsAll(List.of(
                                "OLD_LIGHTING_DIRECTIVE_MALFORMED", "SEPARATE_AO_DIRECTIVE_MALFORMED")),
                "unexpected values keep Iris's defaults and are reported: " + malformed);

        Path root = Files.createTempDirectory("chimera-terrain-shading-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"), "oldLighting=true\nseparateAo=true\n");
            Files.writeString(shaders.resolve("final.fsh"), """
                    #version 120
                    uniform sampler2D colortex0;
                    void main() {
                        gl_FragColor = texture2D(colortex0, vec2(0.5));
                    }
                    """);
            var shading = PackProbe.analyze(root).plan().terrainShading();
            assertTrue(shading.oldLighting() && shading.separateAo(),
                    "the pack plan reads shaders.properties oldLighting and separateAo: " + shading);
        } finally {
            deleteTree(root);
        }

        // Iris XHFPTerrainVertex: ColorABGR.withAlpha(tint, brightness) instead of mulRGB.
        assertEquals(0x7F336699, SeparateAoColor.argb(0xFF336699, 0.5F),
                "a block quad keeps its tint and carries the brightness in alpha");
        assertEquals(0xFF336699, SeparateAoColor.argb(0x00336699, 1.0004F),
                "a brightness rounding just above 1 stays opaque instead of wrapping to 0");
        assertEquals(0xCC3F7FFF, SeparateAoColor.rgba(1.0F, 0.5F, 0.25F, 0.8F),
                "a fluid vertex keeps its tint and carries brightness times shade in alpha");
        String fixedVertex = resourceText("assets/chimera/shaders/chimera_terrain/chimera_terrain.vsh");
        String separateAoVertex = ChimeraTerrainPipelines.withSeparateAo(fixedVertex);
        assertTrue(fixedVertex.contains("#ifdef CHIMERA_SEPARATE_AO")
                        && separateAoVertex.replace("\r\n", "\n").startsWith("#version 460\n#define CHIMERA_SEPARATE_AO\n"),
                "Chimera's own terrain pipeline folds the alpha brightness back into RGB under separateAo");
        compileVertex(separateAoVertex, "chimera_terrain_separate_ao.vsh");

        var shadeMixin = nativeClass("net/chimera/mixin/ChimeraClientLevelShadeMixin");
        boolean shadeArgument = false;
        for (var method : shadeMixin.methods) {
            for (var annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")) continue;
                shadeArgument = annotationValue(annotation, "method") instanceof List<?> names && names.contains("getShade")
                        && Boolean.TRUE.equals(annotationValue(annotation, "argsOnly"))
                        && method.desc.equals("(Z)Z");
            }
        }
        assertTrue(mixinTargets(shadeMixin, "net/minecraft/client/multiplayer/ClientLevel") && shadeArgument,
                "ClientLevel.getShade's shaded argument follows the pack's oldLighting");
        var blockMixin = nativeClass("net/chimera/mixin/ChimeraBlockSeparateAoMixin");
        assertTrue(mixinTargets(blockMixin, "net/vulkanmod/render/chunk/build/frapi/render/AbstractBlockRenderContext")
                        && injects(blockMixin, "Lorg/spongepowered/asm/mixin/injection/Redirect;", "shadeQuad",
                        "Lnet/vulkanmod/render/chunk/build/frapi/helper/ColorHelper;multiplyRGB(IF)I"),
                "block quads route VulkanMod's brightness multiply through separateAo");
        var fluidMixin = nativeClass("net/chimera/mixin/FluidRendererMixin");
        assertTrue(mixinTargets(fluidMixin, "net/vulkanmod/render/chunk/build/renderer/FluidRenderer")
                        && injects(fluidMixin, "Lorg/spongepowered/asm/mixin/injection/Inject;", "updateColor", null),
                "fluid colours apply separateAo after VulkanMod computes them");
        for (String name : List.of("ChimeraClientLevelShadeMixin", "ChimeraBlockSeparateAoMixin", "FluidRendererMixin")) {
            assertMixinRegistered(name);
        }

        var pass = nativeClass("net/chimera/render/PackSessionLifecycle");
        for (String name : List.of("loadPackConfig", "cleanUpPackVariant")) {
            var method = pass.methods.stream().filter(value -> value.name.equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("PackSessionLifecycle." + name + " is missing"));
            boolean applies = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/render/shader/ChimeraTerrainPipelines")
                        && call.name.equals("setTerrainShading")) {
                    applies = true;
                }
            }
            assertTrue(applies, "PackSessionLifecycle." + name + " applies the terrain shading state");
        }
        System.out.println("[chimera] terrain shading oldLighting/separateAo (directives, encoding, mixins, install): PASS");
    }

    /**
     * Iris serves heldItemId, heldBlockLightValue and heldBlockLightColor (and their 2 variants)
     * from the pack's item.properties and the held stacks (IdMapUniforms). Chimera served 0, so
     * a held torch never lit anything: Solas's hand light only runs for heldItemId 1..83, and
     * BSL and Complementary read heldBlockLightValue.
     */
    private static void verifyHeldItems() throws IOException {
        Path root = Files.createTempDirectory("chimera-held-items-");
        try {
            Path shaders = Files.createDirectories(root.resolve("shaders"));
            Files.writeString(shaders.resolve("item.properties"), """
                    # Item ids
                    item.5=torchflower torch \\
                        minecraft:lantern
                    item.6=soul_torch leaves:waterlogged=false
                    #if MC_VERSION >= 12100
                    item.44028=soul_lantern
                    #else
                    item.9=soul_lantern
                    #endif
                    item.7=end_rod
                    item.7=glowstone
                    item.40008=air
                    """);
            var parsed = PackItemIdResolver.parse(shaders, PackEngineDefines.standard());
            var items = parsed.resolver();
            assertTrue(parsed.present() && parsed.deviations().isEmpty(), "item.properties parses: " + parsed.deviations());
            assertEquals(5, items.resolve("minecraft:torch"), "a bare name is in minecraft");
            assertEquals(5, items.resolve("minecraft:lantern"), "a continuation line joins its key's list");
            assertEquals(6, items.resolve("soul_torch"), "a lookup without a namespace means minecraft");
            assertEquals(44028, items.resolve("minecraft:soul_lantern"), "#if branches follow the engine macros");
            assertEquals(7, items.resolve("minecraft:glowstone"), "a later line for a key replaces it");
            assertEquals(PackItemIdResolver.UNMAPPED, items.resolve("minecraft:end_rod"),
                    "the replaced line's names are gone, as java.util.Properties keeps one value per key");
            assertEquals(40008, items.resolve("minecraft:air"), "an empty hand looks up minecraft:air");
            assertEquals(PackItemIdResolver.UNMAPPED, items.resolve("minecraft:stone"), "an unlisted item is -1");
            assertTrue(items.mappings().keySet().stream().noneMatch(name -> name.contains("=")),
                    "state selectors are skipped, as Iris does for items");
        } finally {
            deleteTree(root);
        }
        assertTrue(!PackItemIdResolver.parse(root, PackEngineDefines.standard()).present()
                        && PackItemIdResolver.empty().resolve("minecraft:torch") == PackItemIdResolver.UNMAPPED,
                "without item.properties every held item is -1");

        assertTrue(HandLightDirectives.fromProperties(Map.of()).oldHandLight(), "Iris's oldHandLight defaults to on");
        assertTrue(!HandLightDirectives.fromProperties(Map.of("oldHandLight", "false")).oldHandLight(),
                "oldHandLight=false lights each hand separately");
        assertTrue(HandLightDirectives.fromProperties(Map.of("oldHandLight", "nope")).deviations()
                        .contains("OLD_HAND_LIGHT_DIRECTIVE_MALFORMED"), "an unexpected oldHandLight is reported");

        for (String name : List.of("heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2")) {
            var descriptor = UniformRegistry.descriptor(name, "int");
            assertTrue(descriptor != null && descriptor.availability() == UniformRegistry.Availability.LIVE,
                    name + " is served live");
        }
        for (String name : List.of("heldBlockLightColor", "heldBlockLightColor2")) {
            var descriptor = UniformRegistry.descriptor(name, "vec3");
            assertTrue(descriptor != null && descriptor.availability() == UniformRegistry.Availability.LIVE,
                    name + " is served live");
        }

        var pass = nativeClass("net/chimera/render/PackSessionLifecycle");
        for (String name : List.of("loadPackConfig", "cleanUpPackVariant")) {
            var method = pass.methods.stream().filter(value -> value.name.equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("PackSessionLifecycle." + name + " is missing"));
            boolean installs = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/render/shader/PackUniformProvider")
                        && call.name.equals("installHeldItems")) {
                    installs = true;
                }
            }
            assertTrue(installs, "PackSessionLifecycle." + name + " installs the held-item map");
        }
        System.out.println("[chimera] held-item uniforms (item.properties, oldHandLight, live catalog, install): PASS");
    }

    private static boolean terrainShading(TerrainShadingDirectives directives, String key) {
        return key.equals("oldLighting") ? directives.oldLighting() : directives.separateAo();
    }

    private static boolean mixinTargets(ClassNode mixin, String target) {
        for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")
                    && annotationValue(annotation, "value") instanceof List<?> list && list.stream().anyMatch(type ->
                    ((Type) type).getInternalName().equals(target))) {
                return true;
            }
        }
        return false;
    }

    /** True when an injector of {@code desc} names {@code method} and, if given, an @At {@code target}. */
    private static boolean injects(ClassNode mixin, String desc, String method, String target) {
        for (var handler : mixin.methods) {
            for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                if (!annotation.desc.equals(desc)
                        || !(annotationValue(annotation, "method") instanceof List<?> names && names.contains(method))) {
                    continue;
                }
                Object at = annotationValue(annotation, "at");
                List<?> ats = at instanceof List<?> list ? list : at == null ? List.of() : List.of(at);
                if (target == null || ats.stream().anyMatch(value ->
                        value instanceof AnnotationNode node && target.equals(annotationValue(node, "target")))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String resourceText(String path) throws IOException {
        try (var input = ConformanceHarness.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IOException("missing resource " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void compileVertex(String source, String name) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_vertex_shader, name, "main", 0L);
        try {
            assertTrue(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                            == Shaderc.shaderc_compilation_status_success,
                    name + " compiles: " + (result == 0 ? "no shader result"
                            : Shaderc.shaderc_result_get_error_message(result)));
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * Iris skips Minecraft's HUD vignette and underwater overlay while a pack is active unless
     * shaders.properties keeps them (vignette, underwaterOverlay; both default off). Chimera drew
     * the vignette over Solas's finished image, so a dark room's walls ended about a fifth darker.
     */
    private static void verifyScreenOverlays() throws IOException {
        assertEquals(ScreenOverlayDirectives.DEFAULTS, ScreenOverlayDirectives.fromProperties(Map.of()),
                "Iris's vignette and underwaterOverlay default to off");
        for (String key : List.of("vignette", "underwaterOverlay")) {
            for (String value : List.of("true", "1", " true ")) {
                var directives = ScreenOverlayDirectives.fromProperties(Map.of(key, value));
                assertTrue(screenOverlay(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " keeps the overlay");
            }
            for (String value : List.of("false", "0")) {
                var directives = ScreenOverlayDirectives.fromProperties(Map.of(key, value));
                assertTrue(!screenOverlay(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " skips the overlay");
            }
            var malformed = ScreenOverlayDirectives.fromProperties(Map.of(key, "yes"));
            assertTrue(!screenOverlay(malformed, key)
                            && malformed.deviations().contains("SCREEN_OVERLAY_DIRECTIVE_MALFORMED:" + key),
                    "an unexpected " + key + " keeps Iris's default and is reported: " + malformed);
        }

        Path root = Files.createTempDirectory("chimera-screen-overlays-");
        try {
            Path shaders = Files.createDirectory(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"), "vignette=true\nunderwaterOverlay=1\n");
            Files.writeString(shaders.resolve("final.fsh"), """
                    #version 120
                    uniform sampler2D colortex0;
                    void main() {
                        gl_FragColor = texture2D(colortex0, vec2(0.5));
                    }
                    """);
            var overlays = PackProbe.analyze(root).plan().screenOverlays();
            assertTrue(overlays.vignette() && overlays.underwaterOverlay(),
                    "the pack plan reads shaders.properties vignette and underwaterOverlay: " + overlays);
        } finally {
            deleteTree(root);
        }

        assertCancellingHeadInject("net/chimera/mixin/ChimeraGuiVignetteMixin",
                "net/minecraft/client/gui/Gui", "renderVignette", false);
        assertCancellingHeadInject("net/chimera/mixin/ChimeraScreenEffectWaterMixin",
                "net/minecraft/client/renderer/ScreenEffectRenderer", "renderWater", true);
        assertMixinRegistered("ChimeraGuiVignetteMixin");
        assertMixinRegistered("ChimeraScreenEffectWaterMixin");

        var pass = nativeClass("net/chimera/render/PackSessionLifecycle");
        boolean loads = false;
        boolean clears = false;
        for (var method : pass.methods) {
            for (var instruction : method.instructions) {
                if (method.name.equals("loadPackConfig") && instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/shaderpack/PackPlan") && call.name.equals("screenOverlays")) {
                    loads = true;
                }
                if (method.name.equals("cleanUpPackVariant") && instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD && field.name.equals("screenOverlays")) {
                    clears = true;
                }
            }
        }
        assertTrue(loads && clears, "the main pass reads the overlay switches per pack session and drops them with it");
        System.out.println("[chimera] screen overlays vignette/underwaterOverlay (directive, mixins, install): PASS");
    }

    private static boolean screenOverlay(ScreenOverlayDirectives directives, String key) {
        return key.equals("vignette") ? directives.vignette() : directives.underwaterOverlay();
    }

    /** The mixin targets {@code target} and cancels {@code method} at its head. */
    private static void assertCancellingHeadInject(String mixinName, String target, String method, boolean isStatic)
            throws IOException {
        var mixin = nativeClass(mixinName);
        boolean targets = false;
        for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            targets = annotationValue(annotation, "value") instanceof List<?> list && list.stream().anyMatch(type ->
                    ((Type) type).getInternalName().equals(target));
        }
        boolean cancels = false;
        for (var handler : mixin.methods) {
            for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                boolean head = annotationValue(annotation, "at") instanceof List<?> ats && ats.stream().anyMatch(at ->
                        at instanceof AnnotationNode node && "HEAD".equals(annotationValue(node, "value")));
                cancels |= annotationValue(annotation, "method") instanceof List<?> names && names.contains(method)
                        && Boolean.TRUE.equals(annotationValue(annotation, "cancellable")) && head
                        && ((handler.access & Opcodes.ACC_STATIC) != 0) == isStatic;
            }
        }
        assertTrue(targets && cancels, mixinName + " cancels " + target + "." + method + " at its head");
    }

    private static void assertMixinRegistered(String mixinName) throws IOException {
        try (var config = ConformanceHarness.class.getClassLoader().getResourceAsStream("chimera.mixins.json")) {
            assertTrue(config != null && new String(config.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("\"" + mixinName + "\""), mixinName + " is registered");
        }
    }

    private static List<AnnotationNode> annotations(
            List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) return annotation.values.get(index + 1);
        }
        return null;
    }

    /** BSL-style legacy shadow program: terrain attributes, both identities and both shadow colours. */
    private static void writeLegacyShadowProgram(Path shaders) throws IOException {
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void verifyParticleFallbackAndTargets() throws IOException {
        for (var host : List.of(RenderPipelines.OPAQUE_PARTICLE,
                RenderPipelines.TRANSLUCENT_PARTICLE,
                RenderPipelines.WEATHER_DEPTH_WRITE,
                RenderPipelines.WEATHER_NO_DEPTH_WRITE)) {
            assertEquals(0.1F, ChimeraEntityBridge.alphaReference(host),
                    "particle/weather fixed core cutout must not depend on an absent ALPHA_CUTOUT define");
        }
        for (var host : List.of(RenderPipelines.TEXT,
                RenderPipelines.TEXT_POLYGON_OFFSET)) {
            assertEquals(0.1F, ChimeraEntityBridge.alphaReference(host),
                    "world text tests ONE_TENTH_ALPHA as Iris TEXT/TEXT_BE; untested glyph quads fill solid");
        }
        Path root = Files.createTempDirectory("chimera-particle-contract-");
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
                var plan = analysis.plan().program(name);
                assertTrue(plan != null && plan.executable() && analysis.report().shouldAttempt(name),
                        name + " parent-source particle adapter rejected: "
                                + (plan == null ? "missing" : plan.deviations()));
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
            var compiled = PackCompileCheck.run(root, analysis);
            assertTrue(compiled.failures().isEmpty(), "particle alias compilation: " + compiled.failures());
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
        } finally {
            Files.deleteIfExists(properties);
            Files.deleteIfExists(fragment);
            Files.deleteIfExists(vertex);
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }

    private static void verifyAdvancedResourceInventory() throws IOException {
        String harmless = "// uniform image2D ignored; buffer Ignored { float x; };\n"
                + "uniform sampler2D colortex0;\n"
                + "vec4 sampleImage(sampler2D image, vec2 coords) {\n"
                + " float imageFoo = 0.0, imageSize = 0.0, bufferColor = 0.0;\n"
                + " return texture2D(image, coords) + imageFoo + imageSize + bufferColor; }\n"
                + "void main() { gl_FragColor = sampleImage(colortex0, vec2(0.5)); }\n";
        assertTrue(!PackAdvancedResourcePlan.containsAdvancedDeclarations(harmless),
                "sampler parameter named image or commented declarations became advanced resources");
        for (String prefix : List.of("", "i", "u")) {
            for (String shape : List.of("1D", "2D", "3D", "Cube", "2DRect", "1DArray",
                    "2DArray", "CubeArray", "Buffer", "2DMS", "2DMSArray")) {
                assertTrue(PackAdvancedResourcePlan.containsAdvancedDeclarations(
                                "uniform " + prefix + "image" + shape + " resource;"),
                        "real image type missed: " + prefix + "image" + shape);
            }
        }
        String storage = "layout(std430, binding=0) readonly buffer Data { float x; };\n";
        assertTrue(PackAdvancedResourcePlan.containsAdvancedDeclarations(storage),
                "storage buffer declaration missed");
        Path root = Files.createTempDirectory("chimera-resource-inventory-");
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Path vertex = shaders.resolve("composite1.vsh");
        Path fragment = shaders.resolve("composite1.fsh");
        try {
            Files.writeString(vertex, "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
            Files.writeString(fragment, "#version 120\n" + harmless);
            ConformanceReport report = probe(root);
            assertTrue(report.shouldAttempt("composite1"),
                    "MakeUp-style sampler helper must remain executable: "
                            + report.program("composite1").deviations());
            for (String declaration : List.of("uniform image2D resource;\n", storage)) {
                Files.writeString(fragment, "#version 430\n" + declaration
                        + "void main() { gl_FragColor = vec4(1.0); }\n");
                report = probe(root);
                assertTrue(report.program("composite1").deviations()
                                .contains("ADVANCED_RESOURCE_UNSUPPORTED"),
                        "unplanned real advanced resource was not flagged: " + declaration);
                assertTrue(!report.shouldAttempt("composite1"),
                        "unplanned real advanced resource was admitted");
            }
        } finally {
            Files.deleteIfExists(fragment);
            Files.deleteIfExists(vertex);
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }

    private static void verifyNonzeroGeometryAndPrepare() throws IOException {
        ProgramImageBindingManifest manifest = new ProgramImageBindingManifest(List.of());
        for (String targets : List.of("0", "1", "01", "14")) {
            int count = targets.length();
            String source = "/* DRAWBUFFERS:" + targets + " */\nvoid main() {"
                    + " gl_FragData[0] = vec4(1.0);"
                    + (count > 1 ? " gl_FragData[1] = vec4(1.0);" : "") + " }";
            GeometryOutputPlan route = GeometryOutputPlan.parse("gbuffers_terrain", source,
                    Map.of(0, 97, 1, 9, 4, 76));
            boolean custom = !targets.equals("0");
            assertTrue(route.executable() && route.requiresDynamicAttachments() == custom,
                    "geometry attachment route " + targets);
            assertTrue(route.requiresMrt() == (count > 1), "MRT count changed for " + targets);
            assertTrue(new PackPipelines.PackTerrain(null, new int[0], "", route, manifest)
                            .requiresDynamicAttachments() == custom,
                    "terrain route disagrees with geometry plan " + targets);
            assertTrue(new PackPipelines.PackEntity(null, new int[0], "", "", route, manifest)
                            .requiresDynamicAttachments() == custom,
                    "entity route disagrees with geometry plan " + targets);
            PackBlendPlan blends = new PackBlendPlan(null, Map.of(1, PackBlendPlan.Mode.OFF), List.of());
            MrtPipelineContext.begin(route.outputFormatsArray(), 8,
                    blends.attachments(route.targetSlots()));
            try {
                assertEquals(count, MrtPipelineContext.attachmentCount(0),
                        "native attachment count " + targets);
                for (int location = 0; location < count; location++) {
                    int target = targets.charAt(location) - '0';
                    assertEquals(target, route.targetForOutput(location), "logical output mapping");
                    assertEquals(Map.of(0, 97, 1, 9, 4, 76).get(target),
                            MrtPipelineContext.colorFormats()[location],
                            "native format mapping");
                    assertTrue(Objects.equals(target == 1 ? PackBlendPlan.Mode.OFF : null,
                            MrtPipelineContext.attachmentBlends()[location]),
                            "logical blend mapping");
                }
            } finally {
                MrtPipelineContext.end();
            }
        }

        Path root = Files.createTempDirectory("chimera-prepare-routing-");
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        List<String> names = List.of("prepare", "prepare2", "prepare10", "gbuffers_terrain", "deferred", "final");
        try {
            for (String name : names) {
                Files.writeString(shaders.resolve(name + ".vsh"),
                        "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
                String fragment = switch (name) {
                    case "prepare" -> "/* DRAWBUFFERS:17 */\nvoid main() {"
                            + " gl_FragData[0] = vec4(0.25); gl_FragData[1] = vec4(0.5); }";
                    case "prepare2", "prepare10" -> "/* DRAWBUFFERS:7 */\nuniform sampler2D colortex7;"
                            + " void main() { gl_FragColor = texture2D(colortex7, vec2(0.5)); }";
                    case "gbuffers_terrain" -> "/* DRAWBUFFERS:1 */\nuniform sampler2D gaux4;"
                            + " void main() { gl_FragColor = texture2D(gaux4, vec2(0.5)); }";
                    case "deferred" -> "/* DRAWBUFFERS:1 */\nuniform sampler2D colortex1;"
                            + " void main() { gl_FragColor = texture2D(colortex1, vec2(0.5)); }";
                    default -> "const bool colortex1Clear = false;\nconst bool colortex7Clear = false;\n"
                            + " uniform sampler2D colortex1;"
                            + " void main() { gl_FragColor = texture2D(colortex1, vec2(0.5)); }";
                };
                Files.writeString(shaders.resolve(name + ".fsh"), "#version 120\n" + fragment);
            }
            PackProbe.Analysis analysis = PackProbe.analyze(root);
            for (String name : names) {
                assertTrue(analysis.plan().shouldAttempt(name) && analysis.report().shouldAttempt(name),
                        "prepare fixture runtime eligibility " + name + ": "
                                + analysis.plan().program(name).deviations() + "/"
                                + analysis.report().program(name).deviations());
            }
            PackTargetGraphPlan graph = PackTargetGraphPlan.build(analysis.plan().activePrograms(),
                    analysis.config(), analysis.plan().resources(), 32, 32, 8, 16384);
            PackFrameSchedulePlan schedule = PackFrameSchedulePlan.build(analysis.plan().activePrograms(), graph);
            assertEquals(List.of("prepare", "prepare2", "prepare10"),
                    schedule.stages(PackFrameSchedulePlan.PostWindow.PREPARE).stream()
                            .map(PackFrameSchedulePlan.PostStage::name).toList(), "prepare numeric order");
            assertTrue(schedule.phaseIndex(PackFrameSchedulePlan.Phase.SHADOW)
                            < schedule.phaseIndex(PackFrameSchedulePlan.Phase.PREPARE)
                            && schedule.phaseIndex(PackFrameSchedulePlan.Phase.PREPARE)
                            < schedule.phaseIndex(PackFrameSchedulePlan.Phase.OPAQUE), "prepare world seam");
            assertEquals(List.of("deferred"), schedule.stages(PackFrameSchedulePlan.PostWindow.EARLY)
                    .stream().map(PackFrameSchedulePlan.PostStage::name).toList(), "prepare leaked into deferred");
            assertEquals(List.of(1, 7), graph.step("prepare").outputTargets(), "prepare target allocation");
            assertTrue(graph.target(1).persistent() && graph.target(7).persistent()
                            && !graph.target(1).clear() && !graph.target(7).clear(),
                    "prepare changed authored clear policy");
            assertTrue(graph.geometryReads().contains(7), "terrain did not consume prepare fog target");
            Files.writeString(shaders.resolve("shaders.properties"), "program.prepare2.enabled=false\n");
            analysis = PackProbe.analyze(root);
            graph = PackTargetGraphPlan.build(analysis.plan().activePrograms(), analysis.config(),
                    analysis.plan().resources(), 32, 32, 8, 16384);
            schedule = PackFrameSchedulePlan.build(analysis.plan().activePrograms(), graph);
            assertTrue(graph.step("prepare2") == null && schedule.postStage("prepare2") == null,
                    "pack-disabled prepare remained a producer");
        } finally {
            Files.deleteIfExists(shaders.resolve("shaders.properties"));
            for (String name : names) {
                Files.deleteIfExists(shaders.resolve(name + ".vsh"));
                Files.deleteIfExists(shaders.resolve(name + ".fsh"));
            }
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        }
    }

    private static void verifySimplex(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "simplex");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("gbuffers_terrain").support(), "simplex terrain support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("composite").support(), "simplex composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("final").support(), "simplex final support");
        for (String name : List.of("composite", "final")) {
            assertTrue(report.shouldAttempt(name), "simplex authored post pair was rejected: " + name);
            assertEquals(List.of(0), report.program(name).targets(), "simplex post target route: " + name);
            assertTrue(!report.program(name).deviations().contains("FIXED_VERTEX_SUBSTITUTION"),
                    "simplex authored post vertex was replaced: " + name);
        }
        verifyBaseline(report, baselinePath);
    }

    private static void verifySupported(Path pack, String label) {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, label);
        for (String name : REQUIRED_PROGRAMS) {
            ConformanceReport.ProgramReport program = report.program(name);
            assertTrue(program.dialect().equals("LEGACY_GLSL"),
                    label + " must remain in the legacy converter dialect");
            assertTrue(program.support() == ConformanceReport.SupportStatus.SUPPORTED
                            || program.support() == ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                    label + " program must be executable: " + name);
            assertTrue(report.shouldAttempt(name), label + " program was rejected: " + name);
            assertTrue(program.targets().equals(List.of(0)),
                    label + " must use the single colortex0 target: " + name);
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), label + " static probe runtime state: " + name);
        }
        assertEquals(List.of("SHADOW_SETTING_LOGGED_ONLY:shadowMapResolution"),
                report.deviations(), label + " deviations");
        assertEquals(List.of(), report.program("gbuffers_terrain").deviations(),
                label + " terrain deviations");
        assertStable(report, label);
    }

    private static void verifyUnsupported(Path pack) {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        ConformanceReport.ProgramReport numbered = report.program("composite1");
        ConformanceReport.ProgramReport compute = report.program("setup");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                terrain.support(), "unsupported MRT terrain support");
        assertTrue(terrain.deviations().contains("MRT_NOT_SUPPORTED"),
                "unsupported MRT deviation");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                numbered.support(), "numbered post pass support");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                compute.support(), "compute stage support");
        assertTrue(compute.deviations().contains("UNSUPPORTED_PACK_STAGE"),
                "compute stage deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:customImage0"),
                "custom image deviation");
        assertTrue(report.deviations().contains("SETTING_NOT_APPLIED:iris.features.required"),
                "feature flag deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"), "MRT terrain must not execute");
        assertTrue(report.shouldAttempt("composite1"), "numbered post pass must execute");
        assertEquals(List.of(
                        "SETTING_NOT_APPLIED:customImage0",
                        "SETTING_NOT_APPLIED:iris.features.required"),
                report.deviations(), "unsupported deviations");
        assertStable(report, "unsupported");
    }

    private static void verifyM52Material(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "m5.2 material");
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                terrain.support(), "m5.2 material terrain support");
        assertEquals(List.of("fragment", "vertex"), terrain.stages(), "m5.2 material terrain stages");
        assertEquals(List.of("ALPHA_TEST_DYNAMIC:gbuffers_terrain",
                        "LEGACY_TERRAIN_VERTEX_BRIDGE"), terrain.deviations(),
                "m5.2 material terrain deviations");
        assertTrue(report.shouldAttempt("gbuffers_terrain"), "m5.2 material terrain was rejected");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "m5.2 material block.properties hash is missing");

        PackMaterialResolver.ParseResult material = PackMaterialResolver.parse(pack.resolve("shaders"));
        assertTrue(material.present(), "m5.2 material properties are missing");
        assertEquals(1, material.resolver().resolveName("minecraft:stone"), "stone material id");
        assertEquals(2, material.resolver().resolveName("dirt"), "unqualified dirt material id");
        assertEquals(2, material.resolver().resolveName("minecraft:grass_block"), "grass material id");
        assertEquals(-1, material.resolver().resolveName("minecraft:diamond_block"), "unmapped material id");
        assertEquals(List.of(), material.deviations(), "m5.2 material parser deviations");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram terrainProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_terrain"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.2 material terrain source is missing"));
        assertTrue(terrainProgram.vertexSource() != null, "m5.2 material vertex source was not retained");
        LegacyGlslConverter.TerrainVertexConversion converted = TerrainVertexBridge.convertTerrainVertex(
                terrainProgram.vertexSource(), terrainProgram.vertexPath(), terrainProgram.fragmentSource());
        assertTrue(converted != null, "m5.2 material vertex conversion failed");
        assertTrue(converted.source().contains("layout(location = 0) out"),
                "m5.2 material varying layout was not emitted");
        assertTrue(converted.source().contains("inMaterialId"),
                "m5.2 material input attribute was not emitted");
        String convertedFragment = FragmentConverter.FragmentConversionRequest.withAutoPlan(
                terrainProgram.fragmentSource(), terrainProgram.fragmentPath(), true,
                new int[] {0, 2}, converted.layout()).convert();
        assertTrue(convertedFragment != null && convertedFragment.contains("layout(location = 0) in"),
                "m5.2 material fragment varying bridge failed");

        assertExtendedFormat();
        verifyMaterialParserEdgeCases();
        // The version number no longer gates (GlslVersion); the legacy converter still refuses
        // modern syntax, which the modern terrain path converts, and GLSL ES.
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 330\nin vec3 Position;\nvoid main() { gl_Position = vec4(Position, 1.0); }", null, "") == null,
                "modern terrain vertex syntax was accepted by the legacy converter");
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 300 es\nvoid main() { gl_Position = vec4(0.0); }", null, "") == null,
                "GLSL ES terrain vertex source was accepted");
        assertTrue(TerrainVertexBridge.convertTerrainVertex(
                "#version 120\nvoid main() { gl_Position = ftransform(); float n = gl_Normal.x; }",
                null, "") == null,
                "unsupported terrain normal was accepted");
        verifyM52Baseline(report, baselinePath, "material");
    }

    private static void verifyM52Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport terrain = report.program("gbuffers_terrain");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                terrain.support(), "m5.2 unsupported vertex support");
        assertTrue(terrain.deviations().contains("TERRAIN_VERTEX_BRIDGE_UNSUPPORTED"),
                "m5.2 unsupported vertex deviation");
        assertTrue(!report.shouldAttempt("gbuffers_terrain"),
                "m5.2 unsupported vertex must not execute");
        assertTrue(metadataHashes(report).containsKey("block.properties"),
                "m5.2 unsupported block.properties hash is missing");
        verifyM52Baseline(report, baselinePath, "unsupported_vertex");
    }

    private static void verifyM53LiveUniforms(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        verifyRequiredPrograms(report, "m5.3 live uniforms");
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                composite.support(), "m5.3 live composite support");
        assertTrue(report.shouldAttempt("composite"), "m5.3 live composite was rejected");
        assertEquals(List.of("colortex0", "depthtex0"), composite.samplers(),
                "m5.3 live composite samplers");
        assertEquals(List.of(
                        "cameraPosition", "colortex0", "depthtex0", "fogColor",
                        "frameTimeCounter", "sunPosition", "viewHeight", "viewWidth",
                        "wetness", "worldTime"), composite.uniforms(),
                "m5.3 live composite declarations");
        assertEquals(List.of(
                        "DEPTH_INPUT_FIXED_TO_HDR",
                        "LIVE_UNIFORM_BRIDGE"), composite.deviations(),
                "m5.3 live composite deviations");
        assertEquals(List.of(), report.deviations(), "m5.3 live global deviations");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.POST);
        assertEquals(List.of(
                        new UniformRegistry.UniformDeclaration("cameraPosition", "vec3"),
                        new UniformRegistry.UniformDeclaration("fogColor", "vec4"),
                        new UniformRegistry.UniformDeclaration("frameTimeCounter", "float"),
                        new UniformRegistry.UniformDeclaration("sunPosition", "vec3"),
                        new UniformRegistry.UniformDeclaration("viewHeight", "float"),
                        new UniformRegistry.UniformDeclaration("viewWidth", "float"),
                        new UniformRegistry.UniformDeclaration("wetness", "float"),
                        new UniformRegistry.UniformDeclaration("worldTime", "int")),
                interfacePlan.uniforms(), "m5.3 uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("colortex0", 0),
                        new UniformRegistry.SamplerBinding("depthtex0", 6)),
                interfacePlan.samplers(), "m5.3 sampler ordering");
        assertTrue(interfacePlan.executable(), "m5.3 live interface is not executable");
        assertEquals("float", UniformRegistry.pipelineType("vec3"), "m5.3 vec3 pipeline type");
        assertEquals(3, UniformRegistry.pipelineCount("vec3"), "m5.3 vec3 pipeline count");
        assertEquals("int", UniformRegistry.pipelineType("int"), "m5.3 int pipeline type");
        assertEquals(16, UniformRegistry.pipelineCount("mat4"), "m5.3 mat4 pipeline count");
        verifyM53InterfaceEdgeCases();

        PackProgram compositeProgram = PackSource.loadResult(pack).programs().stream()
                .filter(program -> program.name().equals("composite"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.3 live composite source is missing"));
        String converted = FragmentConverter.FragmentConversionRequest
                .of(compositeProgram.fragmentSource(), compositeProgram.fragmentPath(), false, null)
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(converted != null, "m5.3 live composite conversion failed");
        assertTrue(converted.contains("layout(binding = 0) uniform ChimeraPackUniforms"),
                "m5.3 generated UBO is missing");
        assertTrue(converted.contains("layout(binding = 1) uniform sampler2D colortex0"),
                "m5.3 colortex0 binding is not after the UBO");
        assertTrue(converted.contains("layout(binding = 2) uniform sampler2D depthtex0"),
                "m5.3 depthtex0 binding is not after the UBO");
        assertTrue(!converted.contains("uniform vec3 cameraPosition"),
                "m5.3 source uniform was not removed");
        verifyM53Baseline(report, baselinePath, "live_uniforms");
    }

    private static void verifyM53InterfaceEdgeCases() {
        UniformRegistry.ProgramInterface array = UniformRegistry.plan(
                "uniform float wetness[2];", UniformRegistry.Stage.POST);
        assertTrue(!array.executable(), "m5.3 uniform arrays were accepted");
        assertTrue(array.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:wetness"),
                "m5.3 array deviation is missing");

        UniformRegistry.ProgramInterface struct = UniformRegistry.plan(
                "uniform PackValues { float wetness; };", UniformRegistry.Stage.POST);
        assertTrue(!struct.executable(), "m5.3 uniform blocks were accepted");
        assertTrue(struct.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:PackValues"),
                "m5.3 uniform block deviation is missing");

        // Iris leaves a uniform it does not provide unset, so GL reads zero and the program runs.
        UniformRegistry.ProgramInterface unknown = UniformRegistry.plan(
                "uniform float arbitraryValue; void main() { gl_FragColor = vec4(arbitraryValue); }",
                UniformRegistry.Stage.POST);
        assertTrue(unknown.executable(), "m5.3 a name Iris does not serve must run on zero");
        assertTrue(unknown.deviations().contains("UNIFORM_UNSET_ZERO:arbitraryValue"),
                "m5.3 unset uniform deviation is missing");
        // An Iris name Chimera does not serve yet must not run on a zero Iris would never give.
        UniformRegistry.ProgramInterface unserved = UniformRegistry.plan(
                "uniform float fogDensity; void main() { gl_FragColor = vec4(fogDensity); }",
                UniformRegistry.Stage.POST);
        assertTrue(!unserved.executable(), "m5.3 unserved Iris uniform was accepted");
        assertTrue(unserved.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "m5.3 unserved Iris uniform deviation is missing");

        UniformRegistry.ProgramInterface conflict = UniformRegistry.plan(
                "uniform float wetness; uniform int wetness;", UniformRegistry.Stage.POST);
        assertTrue(!conflict.executable(), "m5.3 conflicting uniform declarations were accepted");
        assertTrue(conflict.deviations().contains("UNIFORM_CONFLICT:wetness"),
                "m5.3 conflicting uniform deviation is missing");

        UniformRegistry.ProgramInterface alias = UniformRegistry.plan(
                "uniform sampler2D colortex1;", UniformRegistry.Stage.POST);
        assertTrue(alias.hasSampler("colortex1"), "m5.3 colortex1 alias was not mapped");
        assertTrue(alias.deviations().contains("COLORTEX_ALIAS_TO_SEAM"),
                "m5.3 colortex alias deviation is missing");

        UniformRegistry.ProgramInterface distinctResources = UniformRegistry.plan(
                "uniform sampler2D colortex3; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.POST);
        assertTrue(distinctResources.executable(), "Independent color and shadow resources were rejected");
        assertEquals(2L, distinctResources.samplers().stream()
                .map(UniformRegistry.SamplerBinding::slot).distinct().count(),
                "Color and shadow resources share a selector");
    }

    private static void verifyM53Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "m5.3 unsupported composite support");
        assertTrue(composite.deviations().contains("SAMPLER_NOT_MAPPED:unknownTexture"),
                "m5.3 unknown sampler deviation is missing");
        assertTrue(composite.deviations().contains("UNIFORM_TYPE_UNSUPPORTED:unsupportedToggle"),
                "m5.3 unsupported uniform type deviation is missing");
        assertTrue(!report.shouldAttempt("composite"), "m5.3 unsupported composite must not execute");
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.POST);
        assertTrue(!interfacePlan.executable(), "m5.3 unsupported interface was accepted");
        assertTrue(FragmentConverter.FragmentConversionRequest.withAutoPlan(
                Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/composite.fsh"), false, null, null).convert() == null,
                "m5.3 unsupported source converted");
        verifyM53Baseline(report, baselinePath, "unsupported_uniform");
    }

    private static void verifyM56PostChain(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "m5.6 post pass inventory must preserve shaders.json order");
        for (String name : List.of("deferred", "composite", "composite1", "final")) {
            ConformanceReport.ProgramReport program = report.program(name);
            assertTrue(program != null, "m5.6 post program was not discovered: " + name);
            assertEquals(List.of("fragment"), program.stages(),
                    "m5.6 post stages: " + name);
            assertEquals(ConformanceReport.RuntimeDisposition.NOT_ATTEMPTED,
                    program.runtime(), "m5.6 static post runtime: " + name);
            assertTrue(report.shouldAttempt(name), "m5.6 post program was rejected: " + name);
        }
        assertEquals(List.of(0, 1), report.program("deferred").targets(),
                "m5.6 deferred targets");
        assertEquals(List.of(0, 1), report.program("composite").targets(),
                "m5.6 composite targets");
        assertEquals(List.of(0), report.program("composite1").targets(),
                "m5.6 numbered composite targets");
        assertEquals(List.of(0), report.program("final").targets(),
                "m5.6 final target");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("deferred").deviations(), "m5.6 deferred deviations");
        assertEquals(List.of("MRT_POST_BRIDGE", "POST_TARGET_ROUTE_APPLIED"),
                report.program("composite").deviations(), "m5.6 composite deviations");
        assertEquals(List.of(),
                report.program("composite1").deviations(), "m5.6 numbered composite deviations");
        assertEquals(List.of(), report.program("final").deviations(), "m5.6 final deviations");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("deferred").support(), "m5.6 deferred support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                report.program("composite").support(), "m5.6 composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("composite1").support(), "m5.6 numbered composite support");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "m5.6 final support");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        List<String> loadedNames = loaded.programs().stream().map(PackProgram::name)
                .distinct().sorted(PostTargetPlan.programComparator()).toList();
        assertEquals(List.of("deferred", "composite", "composite1", "final"), loadedNames,
                "m5.6 discovered post order");
        assertEquals(4L, loaded.programs().stream()
                .filter(program -> PostTargetPlan.isPostProgramName(program.name())).count(),
                "m5.6 duplicate post programs");

        String deferred = Files.readString(pack.resolve("shaders/deferred.fsh"), StandardCharsets.UTF_8);
        String composite = Files.readString(pack.resolve("shaders/composite.fsh"), StandardCharsets.UTF_8);
        PostTargetPlan deferredPlan = PostTargetPlan.parse("deferred", deferred,
                Map.of(0, 97, 1, 37)).plan();
        PostTargetPlan compositePlan = PostTargetPlan.parse("composite", composite,
                Map.of(0, 97, 1, 37)).plan();
        assertEquals(List.of(0, 1), deferredPlan.targetSlots(), "m5.6 deferred route");
        assertEquals(List.of(0, 1), compositePlan.targetSlots(), "m5.6 composite route");
        assertEquals(List.of(97, 37), compositePlan.outputFormats(), "m5.6 target formats");
        assertTrue(compositePlan.requiresMrt(), "m5.6 composite must require MRT");
        String converted = FragmentConverter.convertPostFragment(
                composite, pack.resolve("shaders/composite.fsh"),
                UniformRegistry.planPost(composite, compositePlan), compositePlan);
        assertTrue(converted != null, "m5.6 multi-target conversion failed");
        assertTrue(converted.contains("layout(location = 0) out vec4 chimeraFragColor0"),
                "m5.6 output location 0 is missing");
        assertTrue(converted.contains("layout(location = 1) out vec4 chimeraFragColor1"),
                "m5.6 output location 1 is missing");
        assertTrue(!converted.contains("DRAWBUFFERS01"),
                "m5.6 DRAWBUFFERS directive was not removed");
        verifyM56TargetParserEdgeCases();
        verifyM56Baseline(report, baselinePath, "post_chain");
    }

    private static void verifyM56TargetParserEdgeCases() {
        PostTargetPlan drawBuffers = PostTargetPlan.parse(
                "composite", "#version 120\n#define DRAWBUFFERS0123\n").plan();
        assertEquals(List.of(0, 1, 2, 3), drawBuffers.targetSlots(),
                "m5.6 DRAWBUFFERS digits are target slots");

        PostTargetPlan renderTargets = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, 2, 3 */").plan();
        assertEquals(List.of(0, 2, 3), renderTargets.targetSlots(),
                "m5.6 RENDERTARGETS route");

        PostTargetPlan malformed = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0, nope */").plan();
        assertTrue(!malformed.executable(), "m5.6 malformed target directive was accepted");
        assertTrue(malformed.deviations().contains("POST_TARGET_DIRECTIVE_MALFORMED"),
                "m5.6 malformed target deviation is missing");

        PostTargetPlan conflict = PostTargetPlan.parse(
                "composite", "#define DRAWBUFFERS01\n/* RENDERTARGETS: 0,2 */").plan();
        assertTrue(!conflict.executable(), "m5.6 conflicting target directives were accepted");
        assertTrue(conflict.deviations().contains("POST_TARGET_DIRECTIVE_CONFLICT"),
                "m5.6 target conflict deviation is missing");

        PostTargetPlan unsupported = PostTargetPlan.parse(
                "composite", "/* RENDERTARGETS: 0,16 */").plan();
        assertTrue(!unsupported.executable(), "m5.6 unsupported target was accepted");
        assertTrue(unsupported.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                "m5.6 unsupported target deviation is missing");

        PostTargetPlan finalMrt = PostTargetPlan.parse(
                "final", "/* RENDERTARGETS: 0,1 */ gl_FragData[0] = vec4(1.0);").plan();
        assertTrue(!finalMrt.executable(), "m5.6 final MRT was accepted");
        assertTrue(finalMrt.deviations().contains("FINAL_MRT_UNSUPPORTED"),
                "m5.6 final MRT deviation is missing");
    }

    private static void verifyM56Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport composite = report.program("composite");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                composite.support(), "m5.6 unsupported target support");
        assertTrue(composite.deviations().contains("POST_TARGET_INDEX_UNSUPPORTED:16"),
                "m5.6 unsupported target deviation is missing");
        assertTrue(!report.shouldAttempt("composite"),
                "m5.6 unsupported target must not execute");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED,
                report.program("final").support(), "m5.6 unsupported fixture final probe");
        verifyM56Baseline(report, baselinePath, "unsupported_targets");
    }

    private static void verifyM55Water(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        assertTrue(report.program("composite") != null, "m5.5 water is missing program composite");
        assertTrue(report.program("final") != null, "m5.5 water is missing program final");
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "m5.5 water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                water.support(), "m5.5 water support");
        assertEquals(List.of("fragment", "vertex"), water.stages(), "m5.5 water stages");
        assertTrue(water.deviations().contains("TRANSLUCENT_VERTEX_BRIDGE"),
                "m5.5 water vertex bridge deviation is missing");
        assertTrue(water.deviations().contains("TRANSLUCENT_STATE_FIXED_TO_HOST"),
                "m5.5 host translucent state deviation is missing");
        assertEquals(List.of("lightmap", "shadowtex0", "texture"), water.samplers(),
                "m5.5 water sampler inventory");
        assertEquals(List.of("composite", "final"), report.passInventory(),
                "m5.5 water must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("gbuffers_water")).count() == 1,
                "m5.5 water program was duplicated");
        assertPostPassThrough(pack, "m5.5 water");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram waterProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("gbuffers_water"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.5 water source is missing"));
        assertTrue(waterProgram.vertexSource() != null, "m5.5 water vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                waterProgram.fragmentSource(), UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(interfacePlan.executable(), "m5.5 water interface is not executable");
        // M8.8: water plans on the terrain contract, so its uniforms are the
        // live Iris catalog (atlasSize, fogStart), not host UBO field aliases.
        assertEquals(List.of(
                        new UniformRegistry.UniformDeclaration("atlasSize", "ivec2"),
                        new UniformRegistry.UniformDeclaration("fogColor", "vec4"),
                        new UniformRegistry.UniformDeclaration("fogEnd", "float"),
                        new UniformRegistry.UniformDeclaration("fogStart", "float")),
                interfacePlan.uniforms(), "m5.5 water uniform ordering");
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2),
                        new UniformRegistry.SamplerBinding("shadowtex0", 5)),
                interfacePlan.samplers(), "m5.5 water sampler bindings");
        assertTrue(interfacePlan.deviations().contains("LIVE_UNIFORM_BRIDGE"),
                "m5.5 water live uniform bridge deviation is missing");

        LegacyGlslConverter.TerrainVertexConversion vertex = TerrainVertexBridge.convertTerrainVertex(
                waterProgram.vertexSource(), waterProgram.vertexPath(), waterProgram.fragmentSource());
        assertTrue(vertex != null, "m5.5 water vertex conversion failed");
        String convertedFragment = FragmentConverter.FragmentConversionRequest
                .of(waterProgram.fragmentSource(), waterProgram.fragmentPath(), true,
                        new int[] {0, 2, 5})
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(convertedFragment != null, "m5.5 water fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform ChimeraTerrainPackUniforms"),
                "m5.5 terrain pack uniform block is missing");
        assertTrue(!convertedFragment.contains("uniform float fogStart"),
                "m5.5 fogStart declaration was not removed");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D chimeraTexture"),
                "m5.5 water atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 5) uniform sampler2D lightmap"),
                "m5.5 water lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 6) uniform sampler2D shadowtex0"),
                "m5.5 water shadow binding is missing");
        assertTrue(convertedFragment.contains("layout(location = " + vertex.layout().location("renderType")
                        + ") in float renderType"),
                "m5.5 water varying location was not shared");

        assertExtendedFormat();
        verifyM55InterfaceEdgeCases();
        verifyM55Baseline(report, baselinePath, "water");
    }

    private static void verifyM55InterfaceEdgeCases() {
        UniformRegistry.ProgramInterface depth = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D depthtex0;",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!depth.executable(), "m5.5 depth input was accepted");
        assertTrue(depth.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "m5.5 depth input deviation is missing");

        UniformRegistry.ProgramInterface unknownSampler = UniformRegistry.plan(
                "uniform sampler2D noise;", UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownSampler.executable(), "m5.5 unknown water sampler was accepted");
        assertTrue(unknownSampler.deviations().contains("TRANSLUCENT_SAMPLER_UNSUPPORTED:noise"),
                "m5.5 unknown water sampler deviation is missing");

        UniformRegistry.ProgramInterface unknownUniform = UniformRegistry.plan(
                "uniform float fogDensity; void main() { gl_FragColor = vec4(fogDensity); }",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!unknownUniform.executable(), "m5.5 unserved Iris water uniform was accepted");
        assertTrue(unknownUniform.deviations().contains("UNIFORM_NAME_UNSUPPORTED:fogDensity"),
                "m5.5 unknown water uniform deviation is missing");
    }

    private static void verifyM55Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport water = report.program("gbuffers_water");
        assertTrue(water != null, "m5.5 unsupported water program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                water.support(), "m5.5 unsupported water support");
        assertTrue(water.deviations().contains("TRANSLUCENT_DEPTH_INPUT_UNSUPPORTED"),
                "m5.5 unsupported water depth deviation is missing");
        assertTrue(!report.shouldAttempt("gbuffers_water"),
                "m5.5 unsupported water must not execute");

        ConformanceReport.ProgramReport entities = report.program("gbuffers_entities");
        assertTrue(entities != null, "m5.5 unsupported entity family was not inventoried");
        assertEquals(ConformanceReport.SupportStatus.UNSUPPORTED,
                entities.support(), "m5.5 entity family support");
        assertTrue(!report.shouldAttempt("gbuffers_entities"),
                "m5.5 entity family must not execute");
        assertPostPassThrough(pack, "m5.5 unsupported water");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(!interfacePlan.executable(), "m5.5 unsupported water interface was accepted");
        assertTrue(FragmentConverter.FragmentConversionRequest
                .of(Files.readString(pack.resolve("shaders/gbuffers_water.fsh"), StandardCharsets.UTF_8),
                        pack.resolve("shaders/gbuffers_water.fsh"), true, new int[] {0, 2})
                .withInterfacePlan(interfacePlan)
                .convert() == null,
                "m5.5 unsupported water source converted");
        verifyM55Baseline(report, baselinePath, "unsupported_water");
    }

    private static void assertPostPassThrough(Path pack, String label) throws IOException {
        for (String name : List.of("composite", "final")) {
            String source = Files.readString(pack.resolve("shaders/" + name + ".fsh"), StandardCharsets.UTF_8);
            assertTrue(source.contains("uniform sampler2D colortex0;"),
                    label + " " + name + " must declare colortex0");
            assertTrue(source.contains("texture2D(colortex0, texcoord)"),
                    label + " " + name + " must preserve the input image");
        }
    }

    private static void verifyM54Shadow(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "m5.4 shadow program was not discovered");
        assertEquals(List.of("fragment", "vertex"), shadow.stages(), "m5.4 shadow stages");
        assertEquals(ConformanceReport.SupportStatus.SUPPORTED_WITH_DEVIATION,
                shadow.support(), "m5.4 shadow support");
        // LIVE_UNIFORM_BRIDGE: the per-layer shadow alpha-test reference.
        assertEquals(List.of("LIVE_UNIFORM_BRIDGE", "SHADOW_VERTEX_BRIDGE"), shadow.deviations(),
                "m5.4 shadow deviations");
        assertEquals(List.of("lightmap", "texture"), shadow.samplers(),
                "m5.4 shadow sampler inventory");
        assertTrue(report.passInventory().equals(List.of("composite", "final")),
                "m5.4 shadow must be discovered outside shaders.json");
        assertTrue(report.programs().stream().filter(program -> program.name().equals("shadow")).count() == 1,
                "m5.4 shadow program was duplicated");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowDistance"),
                "m5.4 shadow distance application is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_APPLIED:shadowMapResolution"),
                "m5.4 shadow resolution application is missing");

        PackSource.LoadResult loaded = PackSource.loadResult(pack);
        PackProgram shadowProgram = loaded.programs().stream()
                .filter(program -> program.name().equals("shadow"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("m5.4 shadow source is missing"));
        assertTrue(shadowProgram.vertexSource() != null, "m5.4 shadow vertex source is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                shadowProgram.fragmentSource(), UniformRegistry.Stage.SHADOW);
        assertEquals(List.of(
                        new UniformRegistry.SamplerBinding("texture", 0),
                        new UniformRegistry.SamplerBinding("lightmap", 2)),
                interfacePlan.samplers(), "m5.4 shadow sampler bindings");
        assertTrue(interfacePlan.executable(), "m5.4 shadow interface is not executable");

        LegacyGlslConverter.TerrainVertexConversion vertex = TerrainVertexBridge.convertShadowVertex(
                shadowProgram.vertexSource(), shadowProgram.vertexPath(), shadowProgram.fragmentSource());
        assertTrue(vertex != null, "m5.4 shadow vertex conversion failed");
        assertTrue(vertex.source().contains("layout(binding = 0) uniform ViewUBO"),
                "m5.4 shadow MVP UBO is missing");
        assertTrue(vertex.source().contains("mat4 MVP;"), "m5.4 shadow MVP field is missing");
        assertTrue(!vertex.source().contains("LightMVP"),
                "m5.4 pack shadow bridge retained the fixed LightMVP name");
        assertTrue(vertex.source().contains("layout(binding = 2) uniform SectionData"),
                "m5.4 shadow section UBO is missing");
        assertTrue(vertex.source().contains("layout(location = 3) in int inMaterialId"),
                "m5.4 shadow material input is missing");
        assertTrue(vertex.source().contains("layout(location = 4) in int inRenderType"),
                "m5.4 shadow render-type input is missing");

        String convertedFragment = FragmentConverter.FragmentConversionRequest
                .of(shadowProgram.fragmentSource(), shadowProgram.fragmentPath(), true,
                        new int[] {0, 2})
                .withTerrainLayout(vertex.layout())
                .withInterfacePlan(interfacePlan)
                .convert();
        assertTrue(convertedFragment != null, "m5.4 shadow fragment conversion failed");
        assertTrue(convertedFragment.contains("layout(binding = 3) uniform sampler2D chimeraTexture"),
                "m5.4 shadow atlas binding is missing");
        assertTrue(convertedFragment.contains("layout(binding = 4) uniform sampler2D lightmap"),
                "m5.4 shadow lightmap binding is missing");
        assertTrue(convertedFragment.contains("layout(location = "),
                "m5.4 shadow varying locations are missing");

        JsonObject shadowConfig = PackPipelines.shadowPipelineJson();
        JsonArray shadowUbos = shadowConfig.getAsJsonArray("UBOs");
        assertTrue(shadowUbos != null && shadowUbos.toString().contains("\"binding\":2"),
                "m5.4 shadow section UBO binding was not preserved");
        assertTrue(shadowUbos.toString().contains("\"name\":\"MVP\""),
                "m5.4 shadow pack config MVP field is missing");
        assertEquals(List.of(0, 2), toList(PackPipelines.shadowSamplerSlots(new int[] {2})),
                "m5.4 shadow lightmap descriptor reservation");
        assertEquals(List.of(0), toList(PackPipelines.shadowSamplerSlots(new int[] {0})),
                "m5.4 shadow atlas descriptor ordering");

        PackConfig.PackConfigData config = PackConfig.parse(loaded.programs(), loaded.shadersDir());
        assertEquals(1024, config.shadowSettings().resolution(),
                "m5.4 shaders.properties resolution precedence");
        assertEquals(256.0F, config.shadowSettings().distance(),
                "m5.4 shaders.properties distance precedence");
        assertTrue(config.shadowSettings().rawValues().get("shadowMapResolution").equals("1024"),
                "m5.4 resolution raw value");
        assertTrue(config.shadowSettings().rawValues().get("shadowDistance").equals("256"),
                "m5.4 distance raw value");
        verifyM54SettingValidation();
        verifyM54Baseline(report, baselinePath, "shadow");
    }

    private static void verifyM54Unsupported(Path pack, Path baselinePath) throws IOException {
        ConformanceReport report = probe(pack);
        ConformanceReport.ProgramReport shadow = report.program("shadow");
        assertTrue(shadow != null, "m5.4 unsupported shadow program was not discovered");
        assertEquals(ConformanceReport.SupportStatus.IDENTITY_FALLBACK,
                shadow.support(), "m5.4 unsupported shadow support");
        assertTrue(shadow.deviations().contains("SHADOW_SAMPLER_UNSUPPORTED:shadowtex1"),
                "m5.4 unsupported shadow sampler deviation is missing");
        assertTrue(!report.shouldAttempt("shadow"),
                "m5.4 unsupported shadow must not execute");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                "m5.4 invalid distance default is missing");
        assertTrue(report.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                "m5.4 invalid resolution default is missing");

        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.plan(
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8),
                UniformRegistry.Stage.SHADOW);
        assertTrue(!interfacePlan.executable(), "m5.4 unsupported shadow interface was accepted");
        UniformRegistry.ProgramInterface colorInput = UniformRegistry.plan(
                "uniform sampler2D texture; uniform sampler2D shadowcolor0;",
                UniformRegistry.Stage.SHADOW);
        assertTrue(colorInput.deviations().contains("SHADOW_COLOR_INPUT_UNSUPPORTED"),
                "m5.4 shadow color input deviation is missing");
        assertTrue(!colorInput.executable(), "m5.4 shadow color input was accepted");
        assertTrue(TerrainVertexBridge.convertShadowVertex(
                Files.readString(pack.resolve("shaders/shadow.vsh"), StandardCharsets.UTF_8),
                pack.resolve("shaders/shadow.vsh"),
                Files.readString(pack.resolve("shaders/shadow.fsh"), StandardCharsets.UTF_8)) != null,
                "m5.4 unsupported fixture vertex was rejected for the wrong reason");
        verifyM54Baseline(report, baselinePath, "unsupported_shadow");
    }

    private static void verifyM54SettingValidation() {
        Path root;
        try {
            root = Files.createTempDirectory("chimera-m54-settings-");
            Path shaders = Files.createDirectories(root.resolve("shaders"));
            Files.writeString(shaders.resolve("shaders.properties"),
                    "shadowMapResolution=64\nshadowDistance=4096\nshadowMapFov=90\n");
            PackConfig.ShadowSettings settings = PackConfig.parse(List.of(
                    new PackProgram("shadow", "#version 120\nconst int shadowMapResolution = 2048;",
                            shaders.resolve("shadow.fsh"), null, null)), shaders).shadowSettings();
            assertEquals(PackConfig.DEFAULT_SHADOW_MAP_RESOLUTION, settings.resolution(),
                    "m5.4 invalid resolution default");
            assertEquals(PackConfig.DEFAULT_SHADOW_DISTANCE, settings.distance(),
                    "m5.4 invalid distance default");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_UNSUPPORTED:shadowMapFov"),
                    "m5.4 unsupported shadow setting");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowMapResolution"),
                    "m5.4 invalid resolution deviation");
            assertTrue(settings.deviations().contains("SHADOW_SETTING_DEFAULTED:shadowDistance"),
                    "m5.4 invalid distance deviation");
            Files.deleteIfExists(shaders.resolve("shaders.properties"));
            Files.deleteIfExists(shaders);
            Files.deleteIfExists(root);
        } catch (IOException e) {
            throw new AssertionError("m5.4 setting validation setup failed", e);
        }
    }

    private static void assertExtendedFormat() {
        var format = ChimeraVertexFormats.EXTENDED_COMPRESSED_TERRAIN;
        assertEquals(24, format.getVertexSize(), "m5.2 terrain stride");
        assertEquals(5, format.getElements().size(), "m5.2 terrain attribute count");
        for (int i = 3; i < 5; i++) {
            var element = format.getElements().get(i);
            assertEquals(VertexFormatElement.Type.INT,
                    element.type(), "m5.2 generic attribute type " + i);
            assertEquals(VertexFormatElement.Usage.GENERIC,
                    element.usage(), "m5.2 generic attribute usage " + i);
            assertEquals(1, element.count(), "m5.2 generic attribute count " + i);
        }
    }

    private static void verifyMaterialParserEdgeCases() throws IOException {
        Path root = Files.createTempDirectory("chimera-m52-material-");
        try {
            Files.writeString(root.resolve("block.properties"),
                    "block.-7 = diorite\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.8 = minecraft:dirt\n"
                            + "block.9 = minecraft:oak_planks:axis=x\n"
                            + "block.13 = minecraft:tall_grass:half=lower tall_grass:half=upper\n"
                            + "block.14 = leaves:waterlogged=false:distance=1,2\n"
                            + "block.15 = oak_leaves\n"
                            + "block.10 = %minecraft:logs\n"
                            + "block.40000 = bad\n"
                            + "block.11 = minecraft:stone\n"
                            + "block.12 = minecraft:stone\n");
            PackMaterialResolver.ParseResult result = PackMaterialResolver.parse(root);
            assertEquals(-7, result.resolver().resolveName("diorite"), "signed material id");
            assertEquals(8, result.resolver().resolveName("minecraft:dirt"), "duplicate material id");
            assertEquals(-1, result.resolver().resolveName("minecraft:oak_planks"), "selector mapping");
            assertEquals(-1, result.resolver().resolveName("minecraft:stone"), "conflicting mapping");
            // State selectors, as Iris reads them: BSL and Complementary map
            // tall grass by half and leaves by waterlogged.
            PackMaterialResolver resolver = result.resolver();
            assertEquals(13, resolver.resolveState("minecraft:tall_grass",
                    Map.of("half", "lower")::get), "namespaced selector");
            assertEquals(13, resolver.resolveState("tall_grass",
                    Map.of("half", "upper")::get), "unqualified selector");
            assertEquals(-1, resolver.resolveState("minecraft:tall_grass", Map.<String, String>of()::get),
                    "selector matched a state without the property");
            assertEquals(14, resolver.resolveState("minecraft:leaves",
                    Map.of("waterlogged", "false", "distance", "2")::get), "multi-property selector");
            assertEquals(-1, resolver.resolveState("minecraft:leaves",
                    Map.of("waterlogged", "false", "distance", "3")::get), "selector value list");
            assertEquals(15, resolver.resolveState("minecraft:oak_leaves",
                    Map.of("waterlogged", "true")::get), "plain mapping under a selector-free name");
            assertEquals(-1, resolver.resolveName("minecraft:oak_planks"),
                    "a selector became a plain mapping");
            assertTrue(result.deviations().contains("BLOCK_TAG_UNSUPPORTED"),
                    "tag deviation is missing");
            assertTrue(result.deviations().contains("BLOCK_PROPERTIES_INVALID"),
                "invalid properties deviation is missing");
            assertTrue(result.deviations().contains("BLOCK_MAPPING_CONFLICT"),
                    "mapping conflict deviation is missing");
        } finally {
            Files.deleteIfExists(root.resolve("block.properties"));
            Files.deleteIfExists(root);
        }
    }

    private static ConformanceReport probe(Path pack) {
        assertTrue(Files.isDirectory(pack), "fixture is missing: " + pack);
        return PackProbe.probe(pack);
    }

    private static void verifyRequiredPrograms(ConformanceReport report, String label) {
        for (String name : REQUIRED_PROGRAMS) {
            assertTrue(report.program(name) != null, label + " is missing program " + name);
        }
    }

    private static void verifyBaseline(ConformanceReport report, Path baselinePath) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "Simplex baseline is missing: " + baselinePath);
        JsonObject baseline = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "Simplex pass inventory");

        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry :
                baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        Map<String, String> actualHashes = sourceHashes(report);
        assertEquals(expectedHashes, actualHashes, "Simplex source hashes");
        assertStable(report, "simplex");
    }

    private static Map<String, String> sourceHashes(ConformanceReport report) {
        Map<String, String> hashes = new TreeMap<>();
        JsonObject root = JsonParser.parseString(report.toJson()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry :
                root.getAsJsonObject("metadataHashes").entrySet()) {
            hashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        for (JsonElement element : root.getAsJsonArray("programs")) {
            JsonObject program = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry :
                    program.getAsJsonObject("sourceHashes").entrySet()) {
                hashes.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return hashes;
    }

    private static Map<String, String> metadataHashes(ConformanceReport report) {
        Map<String, String> hashes = new TreeMap<>();
        JsonObject root = JsonParser.parseString(report.toJson()).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("metadataHashes").entrySet()) {
            hashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        return hashes;
    }

    private static void verifyM52Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.2 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.2 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.2 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.2 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.2 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.2 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.2 " + fixture + " source hashes");
        assertStable(report, "m5.2 " + fixture);
    }

    private static void verifyM53Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.3 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.3 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.3 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.3 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.3 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.3 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.3 " + fixture + " source hashes");
        assertStable(report, "m5.3 " + fixture);
    }

    private static void verifyM54Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.4 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.4 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.4 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.4 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.4 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.4 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.4 " + fixture + " source hashes");
        assertStable(report, "m5.4 " + fixture);
    }

    private static void verifyM55Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.5 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.5 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.5 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.5 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.5 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.5 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.5 " + fixture + " source hashes");
        assertStable(report, "m5.5 " + fixture);
    }

    private static void verifyM56Baseline(
            ConformanceReport report,
            Path baselinePath,
            String fixture
    ) throws IOException {
        assertTrue(Files.isRegularFile(baselinePath), "M5.6 baseline is missing: " + baselinePath);
        JsonObject root = JsonParser.parseString(
                Files.readString(baselinePath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject baseline = root.getAsJsonObject("fixtures").getAsJsonObject(fixture);
        assertTrue(baseline != null, "M5.6 baseline fixture is missing: " + fixture);
        assertEquals(baseline.get("reportSha256").getAsString(), report.sha256(),
                "M5.6 " + fixture + " report hash");
        assertEquals(baseline.get("expectedPassInventory").toString(),
                JsonParser.parseString(report.toJson()).getAsJsonObject()
                        .get("passInventory").toString(),
                "M5.6 " + fixture + " pass inventory");
        assertEquals(baseline.get("expectedStages").toString(), expectedStages(report).toString(),
                "M5.6 " + fixture + " stages");
        assertEquals(baseline.get("expectedDeviations").toString(), expectedDeviations(report).toString(),
                "M5.6 " + fixture + " deviations");
        Map<String, String> expectedHashes = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : baseline.getAsJsonObject("sourceHashes").entrySet()) {
            expectedHashes.put(entry.getKey(), entry.getValue().getAsString());
        }
        assertEquals(expectedHashes, sourceHashes(report), "M5.6 " + fixture + " source hashes");
        assertStable(report, "m5.6 " + fixture);
    }

    private static JsonObject expectedStages(ConformanceReport report) {
        JsonObject stages = new JsonObject();
        for (ConformanceReport.ProgramReport program : report.programs()) {
            stages.add(program.name(), strings(program.stages()));
        }
        return stages;
    }

    private static JsonObject expectedDeviations(ConformanceReport report) {
        JsonObject deviations = new JsonObject();
        deviations.add("global", strings(report.deviations()));
        for (ConformanceReport.ProgramReport program : report.programs()) {
            deviations.add(program.name(), strings(program.deviations()));
        }
        return deviations;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> result = new ArrayList<>();
        for (int value : values) {
            result.add(value);
        }
        return result;
    }

    private static void assertStable(ConformanceReport report, String label) {
        String first = report.toJson();
        String second = report.toJson();
        assertEquals(first, second, label + " report must be deterministic");
        assertTrue(!first.contains("C:\\") && !first.contains("file:") && !first.contains("\"/"),
                label + " report contains an absolute path");
        JsonObject root = JsonParser.parseString(first).getAsJsonObject();
        for (JsonElement element : root.getAsJsonArray("programs")) {
            for (JsonElement hash : element.getAsJsonObject()
                    .getAsJsonObject("sourceHashes").entrySet().stream()
                    .map(Map.Entry::getValue).toList()) {
                assertTrue(hash.getAsString().matches("[0-9a-f]{64}"),
                        label + " contains an invalid source hash");
            }
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
