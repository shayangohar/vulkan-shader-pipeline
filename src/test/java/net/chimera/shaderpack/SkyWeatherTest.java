package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.ChimeraHorizonRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.chimera.render.shader.PackUniformProvider;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.vulkan.memory.buffer.index.AutoIndexBuffer;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.MethodInsnNode;

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
     * VulkanMod draws fans as triangle lists. An explicit index buffer is passed through unchanged,
     * so the 10-vertex horizon must be submitted non-indexed to get the native 24-index expansion.
     */
    @Test
    void horizonSubmission() throws IOException {
        int vertices = ChimeraHorizonRenderer.VERTEX_COUNT;
        assertEquals(VertexFormat.Mode.TRIANGLE_FAN,
                RenderPipelines.SKY.getVertexFormatMode(),
                "horizon relies on the SKY pipeline's fan auto-index path");
        assertEquals(24, AutoIndexBuffer.getIndexCount(
                        AutoIndexBuffer.DrawType.TRIANGLE_FAN, vertices),
                "horizon fan must expand to eight triangles");
        var draw = MixinBytecode.nativeClass("net/chimera/render/ChimeraHorizonRenderer").methods.stream()
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
    @Test
    void endPortalHooks() throws IOException {
        var portal = MixinBytecode.nativeClass("net/minecraft/client/renderer/blockentity/AbstractEndPortalRenderer");
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
            assertTrue(MixinBytecode.nativeClass(renderer).methods.stream().anyMatch(method ->
                    method.name.equals("renderType")
                            && method.desc.equals("()Lnet/minecraft/client/renderer/rendertype/RenderType;")),
                    renderer + " renderType seam changed");
        }
        String storageAdd = "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                + "Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V";
        var storage = MixinBytecode.nativeClass("net/minecraft/client/renderer/feature/CustomFeatureRenderer$Storage");
        assertTrue(storage.methods.stream().anyMatch(method -> method.name.equals("add")
                        && method.desc.equals(storageAdd)),
                "custom geometry storage seam changed");
    }

    @Test
    void weatherAndSky() throws IOException {
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
        String fragmentError = PackCompileTest.compile(plan.convertedFragment(), false);
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
        var level = MixinBytecode.nativeClass("net/minecraft/client/renderer/LevelRenderer").methods.stream()
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
        var skyClass = MixinBytecode.nativeClass("net/minecraft/client/renderer/SkyRenderer");
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
                && method.desc.equals(
                        "(Lcom/mojang/blaze3d/vertex/PoseStack;FFFLnet/minecraft/world/level/MoonPhase;FF)V"))
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
        var immediate = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraRenderTypeMixin").methods.stream()
                .filter(method -> method.name.equals("chimera$immediateFamilyWindow")).findFirst().orElseThrow();
        boolean weather = false;
        for (var instruction : immediate.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("beginHostLayoutDraw")) weather = true;
        }
        assertTrue(weather, "weather must own its actual immediate draw");
        var indexed = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraVkRenderPassMixin");
        for (String name : List.of("chimera$familyAttachments", "chimera$skyAttachments")) {
            var method = indexed.methods.stream().filter(candidate ->
                    candidate.name.equals(name)).findFirst().orElseThrow();
            boolean targets = false;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && call.name.equals("beginPackFamilyWindow")) targets = true;
            }
            assertTrue(targets, "both native sky draw APIs must own attachments");
        }
    }
}
