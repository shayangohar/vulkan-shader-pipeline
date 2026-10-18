package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ChimeraHorizonRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.CloudStatus;
import net.vulkanmod.vulkan.memory.AutoIndexBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sky, weather and rain programs, the horizon fan, and the end portal hooks. */
final class SkyWeatherTest {
    @Test
    void rainContracts(@TempDir Path root) throws IOException {
        for (int target : List.of(10, 12, 15)) {
            String source = "/* RENDERTARGETS:" + target + " */\nvoid main(){gl_FragData[0]=vec4(1);}";
            var post = PostTargetPlan.parse("composite", source).plan();
            var geometry = GeometryOutputPlan.parse("gbuffers_weather", source, Map.of());
            assertTrue(post.executable() && geometry.executable(), "two-digit target rejected");
            assertEquals(List.of(target), post.targetSlots(), "integer target split into digits");
            assertEquals(post.targetSlots(), geometry.targetSlots(), "geometry target grammar drift");
            assertEquals(List.of(0), geometry.outputLocations(), "rain must have one output");
            String canonical = "/* RENDERTARGETS:" + target + ",1 */\n" + source;
            assertEquals(List.of(target),
                    GeometryOutputPlan.parse("gbuffers_weather", canonical, Map.of()).targetSlots(),
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
                assertTrue(analysis.report().shouldAttempt(name),
                        name + " report rejected: " + analysis.report().program(name).deviations());
                assertTrue(PackCompileTest.compile(plan.convertedVertex(), true) == null, name + " vertex compile");
                assertTrue(PackCompileTest.compile(plan.convertedFragment(), false) == null,
                        name + " fragment compile");
                assertTrue(plan.convertedFragment().matches("(?s).*\\b(?:flat\\s+in|in\\s+flat)\\s+vec4\\b.*"),
                        name + " flat interpolation lost");
            }
            var weather = analysis.plan().program("gbuffers_weather");
            assertEquals(List.of(12), weather.geometryOutputPlan().targetSlots(), "rain data route");
            assertEquals(PackBlendPlan.Mode.OFF, weather.blendPlan().attachments(List.of(12))[0],
                    "rain data blend override");
            Files.writeString(shaders.resolve("gbuffers_skybasic.fsh"), "#version 130\n"
                    + "uniform samplerCube unsupportedCube; void main(){gl_FragColor=texture(unsupportedCube,vec3(1));"
                            + "}");
            assertTrue(!PackProbe.analyze(root).plan().shouldAttempt("gbuffers_skybasic"),
                    "sky admission bypassed resource validation");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }

    /**
     * The horizon is Iris's ten-vertex fan in a vertex buffer, drawn with the position shader so the
     * sky window gives it gbuffers_skybasic. VulkanMod's vertex buffer expands an unindexed fan with
     * its own index buffer into eight triangles.
     */
    @Test
    void horizonSubmission() throws IOException {
        assertEquals(24, AutoIndexBuffer.DrawType.getTriangleStripIndexCount(ChimeraHorizonRenderer.VERTEX_COUNT),
                "horizon fan must expand to eight triangles");
        var draw = MixinBytecode.nativeClass("net/chimera/render/ChimeraHorizonRenderer").methods.stream()
                .filter(method -> method.name.equals("draw")).findFirst().orElseThrow();
        boolean fan = false;
        boolean positionShader = false;
        boolean vertexBuffer = false;
        for (var instruction : draw.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals("TRIANGLE_FAN")) fan = true;
            if (instruction instanceof MethodInsnNode call) {
                if (call.name.equals("getPositionShader")) positionShader = true;
                if (call.owner.equals("com/mojang/blaze3d/vertex/VertexBuffer")
                        && call.name.equals("drawWithShader")) vertexBuffer = true;
            }
        }
        assertTrue(fan && positionShader && vertexBuffer,
                "the horizon must draw a fan from a vertex buffer with the position shader");
    }

    /**
     * The end portal follows Iris: an entitySolid cube in place of the portal's own render type,
     * from a head-cancelling hook on TheEndPortalRenderer.render. The gateway draws its portal
     * through that same method, after its beam.
     */
    @Test
    void endPortalHooks() throws IOException {
        String portal = "net/minecraft/client/renderer/blockentity/TheEndPortalRenderer";
        MixinBytecode.assertCancellingHeadInject("net/chimera/mixin/ChimeraEndPortalRendererMixin", portal,
                "render(Lnet/minecraft/world/level/block/entity/TheEndPortalBlockEntity;F"
                        + "Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
                false);
        var gateway = MixinBytecode.nativeClass("net/minecraft/client/renderer/blockentity/TheEndGatewayRenderer");
        assertEquals(portal, gateway.superName, "the gateway is an end portal renderer");
        assertTrue(MixinBytecode.instructionsThrough(gateway, "render").stream().anyMatch(instruction ->
                        instruction instanceof MethodInsnNode call && call.owner.equals(portal)
                                && call.name.equals("render")),
                "the gateway must draw its portal through TheEndPortalRenderer.render");
    }

    /**
     * A sky program's samplers follow binding 2, which holds its uniform block or an empty block in
     * its place: with or without pack uniforms, the shader's sampler binding is the layout's.
     */
    @Test
    void skySamplersFollowTheUniformBlock() {
        String vertex = "#version 120\nvarying vec2 uv;\n"
                + "void main() { uv = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy; gl_Position = ftransform(); }\n";
        for (boolean uniforms : List.of(false, true)) {
            String fragment = "#version 120\nuniform sampler2D texture;\n"
                    + (uniforms ? "uniform float rainStrength;\n" : "") + "varying vec2 uv;\n"
                    + "void main() { gl_FragData[0] = texture2D(texture, uv)"
                    + (uniforms ? " * (1.0 - rainStrength)" : "") + "; }\n";
            var plan = PackPlanBuilder.build(new PackProgram("gbuffers_skytextured", fragment, null, vertex, null),
                    null);
            assertTrue(plan.executable(), "sky program rejected: " + plan.deviations());
            var images = PackDescriptorContracts.ordinaryDescriptorContract(plan, Set.of()).layout().images();
            assertEquals(1, images.size(), "one sampler descriptor");
            assertTrue(plan.convertedFragment().contains("layout(binding = " + images.get(0).binding()
                            + ") uniform sampler2D chimeraTexture"),
                    "sampler binding disagrees with the layout (uniforms " + uniforms + "): "
                            + plan.convertedFragment());
        }
    }

    @Test
    void skyProgramVariants() {
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
            var sky = skyVariant(plan, contract);
            variants.put(sky.vertexFormat(), sky);
        }
        String fragmentError = PackCompileTest.compile(plan.convertedFragment(), false);
        assertTrue(fragmentError == null, "sky MRT fragment failed: " + fragmentError);
        assertSkyBridgeRoutesFamilies(plan, variants);
    }

    /** One native sky layout's variant: it compiles, keeps live inputs, and reads the nested render stage. */
    private static PackPipelines.PackSky skyVariant(PackProgramPlan plan, FamilyAdapterPlan.VertexContract contract) {
        String source = HostLayoutVertexBridge.skyVertexForContract(plan.convertedVertex(), contract);
        assertTrue(PackCompileTest.compile(source, true) == null, "sky native variant failed: " + contract);
        assertTrue(source.contains("mat4 mvp = (chimeraSkyProjection() * ModelViewMat);")
                        && source.contains("(Color * ColorModulator)")
                        && source.contains("gbufferModelViewInverse[0]")
                        && source.contains("mat4 gbufferModelViewInverse;")
                        && source.contains("bool hasSkylight;")
                        && !source.contains("in uvec4 EntityIds")
                        && source.contains("void main() { chimeraSkyMain();"
                                + " gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w); }"),
                "sky live inputs, world matrices or early-return clip conversion changed");
        var format = PackPipelines.skyVertexFormat(contract);
        var ordinary = PackDescriptorContracts.ordinaryDescriptorContract(plan, Set.of());
        var stage = ordinary.layout().uniformBlocks().stream().flatMap(block -> block.fields().stream())
                .filter(field -> field.name().equals("renderStage")).findFirst().orElseThrow();
        var supplier = PackUniformProvider.shared().supplier(stage.name(), stage.type());
        var previousPhase = PackUniformProvider.setRenderingPhase(PackRenderingPhase.SUN);
        assertEquals(4, supplier.get().getInt(0), "sun stage UBO value");
        var enclosing = PackUniformProvider.setRenderingPhase(PackRenderingPhase.MOON);
        assertEquals(5, supplier.get().getInt(0), "moon stage UBO value");
        PackUniformProvider.setRenderingPhase(enclosing);
        assertEquals(4, supplier.get().getInt(0), "nested stage restore");
        PackUniformProvider.setRenderingPhase(previousPhase);
        return new PackPipelines.PackSky(null, new int[0], source, plan.convertedFragment(),
                format, plan.geometryOutputPlan(), new ProgramImageBindingManifest(List.of()));
    }

    /** The installed sky bridge serves basic and textured sky shapes from their own families, and nothing else. */
    private static void assertSkyBridgeRoutesFamilies(PackProgramPlan plan,
                                                      Map<VertexFormat, PackPipelines.PackSky> variants) {
        var basic = new PackPipelines.PackSkyFamily(variants);
        var textured = new PackPipelines.PackSkyFamily(variants.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> new PackPipelines.PackSky(null, new int[0], "", "", entry.getKey(),
                        GeometryOutputPlan.empty("textured"), new ProgramImageBindingManifest(List.of())))));
        ChimeraSkyBridge.install(basic, textured, null, true, "off");
        ChimeraSkyBridge.setEnabled(true);
        try {
            assertTrue(ChimeraSkyBridge.programFor("position", DefaultVertexFormat.POSITION) == null,
                    "sky selection leaked outside the sky window");
            ChimeraSkyBridge.beginSky();
            // The sky disc, stars and void draw with "position", the sunrise fan with "position_color".
            for (var draw : Map.of("position", DefaultVertexFormat.POSITION,
                    "position_color", DefaultVertexFormat.POSITION_COLOR).entrySet()) {
                var sky = ChimeraSkyBridge.programFor(draw.getKey(), draw.getValue());
                assertTrue(sky != null && sky.outputPlan().equals(plan.geometryOutputPlan()),
                        "wrong sky family for " + draw.getKey());
            }
            // The sun and moon draw with "position_tex", the end sky with "position_tex_color".
            for (var draw : Map.of("position_tex", DefaultVertexFormat.POSITION_TEX,
                    "position_tex_color", DefaultVertexFormat.POSITION_TEX_COLOR).entrySet()) {
                var sky = ChimeraSkyBridge.programFor(draw.getKey(), draw.getValue());
                assertTrue(sky != null && sky.outputPlan().programName().equals("textured"),
                        "wrong textured family for " + draw.getKey());
            }
            assertTrue(ChimeraSkyBridge.programFor("rendertype_text", DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP)
                    == null, "sky selection leaked to another shader");
            assertTrue(ChimeraSkyBridge.skipHostClouds(), "authored empty clouds changed");
            assertEquals(CloudStatus.OFF, ChimeraSkyBridge.packCloudStatus(),
                    "pack clouds directive not applied");
            ChimeraSkyBridge.setEnabled(false);
            assertEquals(null, ChimeraSkyBridge.packCloudStatus(),
                    "the clouds directive outlived the pack's programs");
        } finally {
            ChimeraSkyBridge.endSky();
            ChimeraSkyBridge.disable();
        }
    }

    /** Rain and snow draw inside renderSnowAndRain, the weather window, and Iris's stage macros keep their ABI. */
    @Test
    void weatherDrawsInsideItsWindow() throws IOException {
        var weather = MixinBytecode.nativeClass("net/minecraft/client/renderer/LevelRenderer").methods.stream()
                .filter(method -> method.name.equals("renderSnowAndRain")).findFirst().orElseThrow();
        boolean draws = false;
        for (var instruction : weather.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("drawWithShader")
                    && call.owner.equals("com/mojang/blaze3d/vertex/BufferUploader")) draws = true;
        }
        assertTrue(draws, "re-audit where rain and snow draw");
        assertEquals("6", PackEngineDefines.standard().get("MC_RENDER_STAGE_STARS"), "Iris star macro ABI");
        assertEquals("21", PackEngineDefines.standard().get("MC_RENDER_STAGE_RAIN_SNOW"), "Iris weather macro ABI");
        assertEquals("8", PackEngineDefines.standard().get("MC_RENDER_STAGE_TERRAIN_SOLID"), "Iris terrain macro ABI");
    }

    /**
     * Iris turns the celestial plane by the pack's sun path rotation after vanilla's -90 degree Y
     * turn and before the time-of-day X turn; Chimera's hook is the first pose rotation after the Y
     * axis is read in renderSky.
     */
    @Test
    void skyPhaseHooks() throws IOException {
        var sky = MixinBytecode.nativeClass("net/minecraft/client/renderer/LevelRenderer").methods.stream()
                .filter(method -> method.name.equals("renderSky")).findFirst().orElseThrow();
        assertTrue(sunPathSeamPrecedesTimeTurn(sky), "re-audit the Iris celestial sun-path rotation seam");
        float previousRotation = PackUniformProvider.currentSunPathRotation();
        try {
            PackUniformProvider.installSunPath(17.5F, 0.0F);
            assertTrue(PackUniformProvider.currentSunPathRotation() == 17.5F,
                    "native celestial geometry must use the pack sun-path rotation");
        } finally {
            PackUniformProvider.installSunPath(previousRotation, 0.0F);
        }
    }

    /** True when the first pose rotation after the Y axis is read comes before the X axis is read. */
    private static boolean sunPathSeamPrecedesTimeTurn(MethodNode sky) {
        boolean yTurn = false;
        boolean rotation = false;
        for (var instruction : sky.instructions) {
            if (instruction instanceof FieldInsnNode field && field.owner.equals("com/mojang/math/Axis")) {
                if (field.name.equals("YP")) yTurn = true;
                if (field.name.equals("XP") && yTurn) return rotation;
            } else if (yTurn && instruction instanceof MethodInsnNode call
                    && call.owner.equals("com/mojang/blaze3d/vertex/PoseStack") && call.name.equals("mulPose")) {
                rotation = true;
            }
        }
        return false;
    }
}
