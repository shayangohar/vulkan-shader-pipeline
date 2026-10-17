package net.chimera.shaderpack;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.chimera.render.shader.MrtPipelineContext;
import net.chimera.render.vertex.SeparateAoColor;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.util.shaderc.Shaderc;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What terrain programs read: legacy host vertex semantics, mid-block inputs, shading directives
 * and output targets.
 */
final class TerrainInputsTest {
    @Test
    void legacyHostVertexSemantics() {
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
            assertLegacyMatrices(family, legacyConversion(family, vertex, fragment));
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
    }

    /** The family's conversion of a legacy vertex stage; the particle family has no checked variant. */
    private static LegacyGlslConverter.TerrainVertexConversion legacyConversion(String family, String vertex,
                                                                               String fragment) {
        return switch (family) {
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
    }

    /** One family's conversion compiles and keeps the legacy MVP, position, texture and lightmap semantics. */
    private static void assertLegacyMatrices(String family, LegacyGlslConverter.TerrainVertexConversion conversion) {
        assertTrue(conversion != null, family + " typed legacy matrices rejected");
        String source = conversion.source();
        String compileError = PackCompileTest.compile(source, true);
        assertTrue(compileError == null, family + " typed legacy matrices: " + compileError);
        String projectionName = family.equals("hand") ? "chimeraHandProjection()" : "ProjMat";
        assertTrue(source.contains("mat4 mvp = (" + projectionName + " * ModelViewMat);")
                        && source.contains("vec4 direct = (" + projectionName
                                + " * ModelViewMat) * chimeraEntityVertexValue();")
                        && source.contains("vec4 typed = mvp * chimeraEntityVertexValue();")
                        && source.contains("vec4 helper = chimeraEntityFtransform();")
                        && source.contains("return " + projectionName
                                + " * ModelViewMat * chimeraEntityVertexValue();"),
                family + " explicit MVP and ftransform disagree");
        // No 1.21.1 host draw carries a model offset: the pose places the vertices.
        assertTrue(source.contains("return vec4(Position, 1.0);"), family + " host position");
        assertTrue(source.contains("mat4 textureMatrix = TextureMat;")
                        && source.contains("light1 = (lightMatrix * vec4(vec2(UV2), 0.0, 1.0)).xy;")
                        && source.contains("light2 = (lightAliasMatrix * vec4(vec2(UV2), 0.0, 1.0)).xy;")
                        && !source.contains("vec2(UV2) / 256.0"),
                family + " texture/lightmap contract");
        assertLightMatrixScalesOnce(family, source);
    }

    /** Iris's light texture matrix scales raw UV2 and adds the half-texel offset exactly once. */
    private static void assertLightMatrixScalesOnce(String family, String source) {
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

    /**
     * Iris hands packs at_midBlock as four unnormalized signed bytes: the offset to the block's
     * centre in 1/64 blocks, then its light emission. Solas voxelizes each block at
     * gl_Vertex + at_midBlock / 64.0; Chimera's helper had already divided by 64, so blocks landed
     * on voxel boundaries, neighbours raced into one voxel, and Solas's lights flickered.
     */
    @Test
    void midBlockInputs(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        ShadowPassTest.writeLegacyShadowProgram(shaders);
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
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "at_midBlock programs compile: " + compiled.failures());
    }

    /**
     * Iris reads oldLighting and separateAo (both default off). Without oldLighting it meshes
     * terrain and fluids unshaded: ClientLevel.getShade sees shaded = false; Chimera kept
     * Minecraft's face shading, so Solas's walls and ceiling rendered at 0.5-0.8 of Iris's
     * brightness. With separateAo it keeps the tint in RGB and carries the light pipeline's
     * brightness in alpha; Chimera multiplied it into RGB and gave packs a constant AO word
     * nobody read, so BSL, Complementary, Bliss, Photon and Solas darkened corners twice.
     */
    @Test
    void terrainShadingDirectives() {
        assertEquals(new TerrainShadingDirectives(false, false, List.of()),
                TerrainShadingDirectives.fromProperties(Map.of()),
                "Iris's oldLighting and separateAo default to off");
        assertTrue(TerrainShadingDirectives.DEFAULTS.disablesDirectionalShading()
                        && !TerrainShadingDirectives.VANILLA.disablesDirectionalShading()
                        && !TerrainShadingDirectives.VANILLA.separateAo(),
                "a pack without directives lights faces itself; vanilla keeps shading and AO in RGB");
        for (String key : List.of("oldLighting", "separateAo")) {
            for (String value : List.of("true", "1", " true ")) {
                var directives = TerrainShadingDirectives.fromProperties(Map.of(key, value));
                assertTrue(terrainShading(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " switches on");
            }
            for (String value : List.of("false", "0")) {
                var directives = TerrainShadingDirectives.fromProperties(Map.of(key, value));
                assertTrue(!terrainShading(directives, key) && directives.deviations().isEmpty(),
                        key + "=" + value + " switches off");
            }
        }
        var malformed = TerrainShadingDirectives.fromProperties(Map.of("oldLighting", "yes", "separateAo", "maybe"));
        assertTrue(malformed.disablesDirectionalShading() && !malformed.separateAo()
                        && malformed.deviations().containsAll(List.of(
                                "OLD_LIGHTING_DIRECTIVE_MALFORMED", "SEPARATE_AO_DIRECTIVE_MALFORMED")),
                "unexpected values keep Iris's defaults and are reported: " + malformed);
    }

    @Test
    void terrainShading(@TempDir Path root) throws IOException {
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
    }

    @Test
    void separateAoColour() throws IOException {
        // Iris XHFPTerrainVertex: ColorABGR.withAlpha(tint, brightness) instead of mulRGB.
        assertEquals(0xCC3F7FFF, SeparateAoColor.rgba(1.0F, 0.5F, 0.25F, 0.8F),
                "a vertex keeps its tint and carries the brightness in alpha");
        assertEquals(0xFF3F7FFF, SeparateAoColor.rgba(1.0F, 0.5F, 0.25F, 1.0004F),
                "a brightness rounding just above 1 stays opaque instead of wrapping to 0");
        String fixedVertex = MixinBytecode.resourceText("assets/chimera/shaders/chimera_terrain/chimera_terrain.vsh");
        String separateAoVertex = ChimeraTerrainPipelines.withSeparateAo(fixedVertex);
        assertTrue(fixedVertex.contains("#ifdef CHIMERA_SEPARATE_AO")
                        && separateAoVertex.replace("\r\n",
                                "\n").startsWith("#version 460\n#define CHIMERA_SEPARATE_AO\n"),
                "Chimera's own terrain pipeline folds the alpha brightness back into RGB under separateAo");
        compileVertex(separateAoVertex, "chimera_terrain_separate_ao.vsh");
    }

    @Test
    void terrainShadingHooks() throws IOException {
        var shadeMixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraClientLevelShadeMixin");
        boolean shadeArgument = false;
        for (var method : shadeMixin.methods) {
            for (var annotation : MixinBytecode.annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;")) continue;
                shadeArgument = MixinBytecode.annotationValue(annotation, "method") instanceof List<?> names
                        && names.contains("getShade")
                        && Boolean.TRUE.equals(MixinBytecode.annotationValue(annotation, "argsOnly"))
                        && method.desc.equals("(Z)Z");
            }
        }
        assertTrue(MixinBytecode.mixinTargets(shadeMixin, "net/minecraft/client/multiplayer/ClientLevel")
                && shadeArgument,
                "ClientLevel.getShade's shaded argument follows the pack's oldLighting");
        var blockMixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraBlockSeparateAoMixin");
        assertTrue(MixinBytecode.mixinTargets(blockMixin, "net/vulkanmod/render/chunk/build/BlockRenderer")
                        && MixinBytecode.injects(blockMixin,
                                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
                                "putQuadData(Lnet/vulkanmod/render/vertex/TerrainBufferBuilder;Lorg/joml/Vector3f;"
                                        + "Lnet/vulkanmod/render/model/quad/QuadView;"
                                        + "Lnet/vulkanmod/render/chunk/build/light/data/QuadLightData;FFF)V",
                                "Lnet/vulkanmod/vulkan/util/ColorUtil$RGBA;pack(FFFF)I"),
                "block quads route VulkanMod's brightness through separateAo where it packs the colour");
        var fluidMixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraLiquidRendererMixin");
        assertTrue(MixinBytecode.mixinTargets(fluidMixin, "net/vulkanmod/render/chunk/build/LiquidRenderer")
                        && MixinBytecode.injects(fluidMixin, "Lorg/spongepowered/asm/mixin/injection/Inject;",
                                "updateColor", null),
                "fluid colours apply separateAo after VulkanMod computes them");
        for (String name : List.of("ChimeraClientLevelShadeMixin", "ChimeraBlockSeparateAoMixin",
                "ChimeraLiquidRendererMixin")) {
            MixinBytecode.assertMixinRegistered(name);
        }
    }

    @Test
    void packSessionAppliesTerrainShading() throws IOException {
        var pass = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        for (String name : List.of("loadPackConfig", "cleanUpPackVariant")) {
            boolean applies = false;
            for (var instruction : MixinBytecode.instructionsThrough(pass, name)) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/render/shader/ChimeraTerrainPipelines")
                        && call.name.equals("setTerrainShading")) {
                    applies = true;
                }
            }
            assertTrue(applies, "PackSessionLifecycle." + name + " applies the terrain shading state");
        }
    }

    static boolean terrainShading(TerrainShadingDirectives directives, String key) {
        return key.equals("oldLighting") ? directives.oldLighting() : directives.separateAo();
    }

    static void compileVertex(String source, String name) {
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

    @Test
    void nonzeroGeometryRoutes() {
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
    }

    @Test
    void prepareStages(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        List<String> names = List.of("prepare", "prepare2", "prepare10", "gbuffers_terrain", "deferred", "final");
        try {
            for (String name : names) {
                Files.writeString(shaders.resolve(name + ".vsh"),
                        "#version 120\nvoid main() { gl_Position = ftransform(); }\n");
                Files.writeString(shaders.resolve(name + ".fsh"), "#version 120\n" + prepareFixtureFragment(name));
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

    /** Prepare-fixture fragments: prepare writes 1 and 7, terrain reads 7 as gaux4, final keeps both uncleared. */
    private static String prepareFixtureFragment(String name) {
        return switch (name) {
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
    }
}
