package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack front end reads the Iris/GLSL formats in full rather
 * than the subset the baseline packs needed. Each check is the spec behaviour, not a pack's usage.
 */
final class PackFrontEndTest {
    /** Sky/cloud draws must not require the terrain bridge's implicit atlas and lightmap. */
    @Test
    void skySamplerContract() {
        for (String name : List.of("gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds")) {
            for (boolean textured : List.of(false, true)) {
                String fragment = "#version 120\n" + (textured
                        ? "uniform sampler2D texture; void main() { gl_FragColor = texture2D(texture, vec2(0.5)); }"
                        : "void main() { gl_FragColor = vec4(1.0); }");
                PackProgramPlan plan = PackPlanBuilder.build(new PackProgram(name, fragment, null,
                        "#version 120\nvoid main() { gl_Position = ftransform(); }", null), null);
                assertTrue(plan.executable(), name + " fixture rejected: " + plan.deviations());
                var ordinary = PackDescriptorContracts.ordinaryDescriptorContract(plan, Set.of());
                var manifest = ProgramImageBindingManifest.from(null, ordinary);
                assertTrue(manifest.entries().size() == (textured ? 1 : 0),
                        name + " injected host textures: " + manifest.entries());
                assertTrue(manifest.entries().stream().allMatch(entry -> entry.slot() == 0
                                && entry.resourceKey().equals("texture")),
                        name + " lost the declared atlas or requires an undeclared lightmap");
                manifest.verify(ordinary.descriptors());
            }
        }
    }

    /** Any GLSL interface type, packed by the locations it occupies (GLSL 4.60 section 4.4.1). */
    @Test
    void interfaceTypes() {
        String vertex = String.join("\n",
                "#version 400",
                "struct Fog { vec3 color; float density; mat2x3 extinction; };",
                "flat out uint mask;",
                "flat out mat3 tbn;",
                "flat out vec3 sky_sh[9];",
                "flat out Fog fog;",
                "out vec2 uv, light;",
                "void main() {}");
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(vertex, true);
        assertTrue(stage.deviations().isEmpty(), "interface types rejected: " + stage.deviations());
        // Name order: fog(1+1+2=4) light(1) mask(1) sky_sh(9) tbn(3) uv(1).
        assertTrue(stage.locations().equals(Map.of("fog", 0, "light", 4, "mask", 5, "sky_sh", 6,
                "tbn", 15, "uv", 18)), "packed locations " + stage.locations());
        assertTrue(stage.output("sky_sh").typeWithArray().equals("vec3[9]"), "array type");
        String rewritten = GlslInterfaceScanner.rewrite(vertex, true, declaration ->
                "layout(location = " + stage.locations().get(declaration.name()) + ") "
                        + (declaration.qualifier() == null ? "" : declaration.qualifier() + " ")
                        + "out " + declaration.type() + " " + declaration.declarator() + ";\n");
        assertTrue(rewritten.contains("layout(location = 6) flat out vec3 sky_sh[9];")
                        && rewritten.contains("layout(location = 4) out vec2 light;")
                        && rewritten.contains("layout(location = 18) out vec2 uv;")
                        && rewritten.contains("struct Fog {"),
                "rewrite did not give each name its location:\n" + rewritten);
        String bad = "#version 400\nflat out Unknown thing;\nvoid main() {}";
        assertTrue(GlslInterfaceScanner.scan(bad, true).deviations()
                        .contains("PROGRAM_VARYING_TYPE_UNSUPPORTED:Unknown"),
                "an undefined struct type must be named");
        // Bliss composite.fsh: `//*` opens a line comment, not a block that hides live code.
        assertTrue(GlslLexer.stripComments("a = z;//*z*m;\nb = viewWidth;/* x */c;\n// tail */")
                        .equals("a = z; \nb = viewWidth; c;\n "),
                "comments must be stripped in source order: "
                        + GlslLexer.stripComments("a = z;//*z*m;\nb = viewWidth;/* x */c;\n// tail */"));
        // Bliss deferred.vsh defines tanh under #version 120, where GLSL has none.
        String ownTanh = LegacyShaderNormalizer.normalize(
                "#version 120\nfloat tanh(float x) { return x; }\nvoid main() { float y = tanh(1.0); }", true).source();
        assertTrue(ownTanh.contains("float chimera_tanh(float x)") && ownTanh.contains("y = chimera_tanh(1.0)"),
                "a pre-1.30 pack function named like a later built-in must be renamed: " + ownTanh);
        String builtinTanh = LegacyShaderNormalizer.normalize(
                "#version 130\nvoid main() { float y = tanh(1.0); }", true).source();
        assertTrue(builtinTanh.contains("y = tanh(1.0)"), "a 1.30 tanh call is the built-in: " + builtinTanh);
        // Bliss composite.fsh: before GLSL 4.20 a trailing backslash does not continue a comment.
        String continued = LegacyShaderNormalizer.normalize(
                "#version 120\nvoid main() {\n\t/// --- STUFF --- \\\\\r\n\tint count = 1;\n}", false).source();
        assertTrue(!continued.contains("\\") && continued.contains("int count = 1;"),
                "a pre-4.20 comment must end at its line: " + continued);
        // Bliss deferred.fsh: parameter qualifiers are not stage interfaces.
        String parameters = String.join("\n",
                "#version 400",
                "in vec2 texcoord;",
                "float linearizeDepthFast(const in float depth, const in float near, const in float far) {",
                "    return (near * far) / (depth * (near - far) + far);",
                "}",
                "void blend(inout vec3 color, in highp vec3 other, out float weight) { weight = 1.0; }",
                "void main() {}");
        GlslInterfaceScanner.StageInterface fragment = GlslInterfaceScanner.scan(parameters, false);
        assertTrue(fragment.deviations().isEmpty(),
                "parameter qualifiers read as interfaces: " + fragment.deviations());
        assertTrue(fragment.inputs().size() == 1 && fragment.input("texcoord") != null && fragment.outputs().isEmpty(),
                "only the file-scope input is an interface: " + fragment.inputs() + " " + fragment.outputs());
    }

    /** Iris patches a raw texture only into a sampler of the same dimensionality. */
    @Test
    void rawTextureTypeMatch() {
        PackResourceDeclaration volume = new PackResourceDeclaration("texture.composite.colortex0",
                "composite", "colortex0", "image/noise.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE",
                PackResourceKind.RAW_TEXTURE);
        assertTrue(PackResourcePlan.appliesTo(volume, "sampler3D"), "a 3D volume serves sampler3D");
        assertTrue(!PackResourcePlan.appliesTo(volume, "sampler2D"),
                "a 3D volume must leave sampler2D colortex0 as the render target");
        PackResourceDeclaration png = new PackResourceDeclaration("texture.composite.colortex13",
                "composite", "colortex13", "image/galaxy.png", PackResourceKind.PACK_TEXTURE);
        assertTrue(PackResourcePlan.appliesTo(png, "sampler2D"), "a PNG declaration applies as before");
    }

    /** Iris raw declarations: words name the dimension; Chimera uploads only an unconverted layout. */
    @Test
    void rawTextureSpecs() {
        RawTextureSpec scattering = RawTextureSpec.parse(
                "image/atmosphere/scattering.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT");
        assertTrue(scattering != null && scattering.dimensions() == 3 && scattering.unsupportedReason() == null
                        && scattering.storage().uploadedChannels() == 4 && scattering.requiredBytes() == 393216,
                "Photon scattering LUT: " + scattering);
        RawTextureSpec worley = RawTextureSpec.parse("image/worley.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE");
        assertTrue(worley != null && worley.unsupportedReason() == null && worley.requiredBytes() == 262144,
                "Photon worley noise: " + worley);
        // GL_UNPACK_ALIGNMENT 4: a 3-byte row is read from a 4-byte stride, the last row unpadded.
        RawTextureSpec odd = RawTextureSpec.parse("lut.dat TEXTURE_2D R8 3 2 RED UNSIGNED_BYTE");
        assertTrue(odd != null && odd.dimensions() == 2 && odd.rowStride() == 4 && odd.requiredBytes() == 7,
                "row alignment: " + odd);
        assertTrue(RawTextureSpec.parse("line.dat TEXTURE_1D RGBA8 16 RGBA UNSIGNED_BYTE").dimensions() == 1,
                "six words are 1D");
        for (String unsupported : List.of("a.dat TEXTURE_2D RGB16F 4 4 RGB FLOAT",
                "a.dat TEXTURE_2D RGBA8 4 4 BGRA UNSIGNED_BYTE", "a.dat TEXTURE_2D R32 4 4 RED UNSIGNED_INT",
                "a.dat TEXTURE_3D R8 4 4 RED UNSIGNED_BYTE")) {
            RawTextureSpec spec = RawTextureSpec.parse(unsupported);
            assertTrue(spec == null || spec.unsupportedReason() != null, "converted or malformed: " + unsupported);
        }
    }

    /** A volume a deferred pass samples binds as a pack file, sampled as its .mcmeta says. */
    @Test
    void rawTextureBinding(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Path lut = Files.createDirectories(shaders.resolve("image")).resolve("lut.dat");
        Files.writeString(shaders.resolve("deferred.fsh"), "#version 120\nuniform sampler3D depthtex0;\n"
                + "varying vec2 texcoord;\n"
                + "void main() { gl_FragData[0] = texture3D(depthtex0, vec3(texcoord, 0.5)); }\n");
        Files.writeString(shaders.resolve("composite.fsh"), "#version 120\nuniform sampler3D colortex5;\n"
                + "varying vec2 texcoord;\n"
                + "void main() { gl_FragData[0] = texture3D(colortex5, vec3(texcoord, 0.5)); }\n");
        Files.writeString(shaders.resolve("shaders.properties"),
                "texture.deferred.depthtex0 = image/lut.dat TEXTURE_3D RGB16F 2 2 2 RGB HALF_FLOAT\n");
        Files.writeString(shaders.resolve("image/lut.dat.mcmeta"), "{\"texture\": {\"blur\": true, \"clamp\": true}}");
        Files.write(lut, new byte[48]);
        PackPlan plan = PackProbe.analyze(root).plan();
        PackResourceBinding volume = plan.resources().binding("deferred", "depthtex0");
        assertTrue(volume != null && volume.kind() == PackResourceKind.RAW_TEXTURE
                        && volume.status() == PackResourceStatus.PACK_FILE
                        && volume.filter().equals("linear") && volume.wrap().equals("clamp"),
                "raw volume binding: " + volume);
        // A raw volume is an ordinary texture, not an image only an installed shadowcomp serves.
        assertTrue(!plan.advancedResources().dependentPrograms().contains("deferred")
                && plan.shouldAttempt("deferred"), "raw volume taken for a pack image");
        assertTrue(plan.advancedResources().dependentPrograms().contains("composite")
                && !plan.shouldAttempt("composite"), "an unserved volume needs shadowcomp, as at runtime");
        Files.write(lut, new byte[40]);
        PackResourceBinding truncated = PackProbe.analyze(root).plan().resources().binding("deferred", "depthtex0");
        assertTrue(truncated != null && truncated.status() == PackResourceStatus.UNAVAILABLE
                        && truncated.deviations().contains("PACK_RAW_TEXTURE_SIZE_MISMATCH:texture.deferred.depthtex0"),
                "a short file must not upload: " + truncated);
    }

    /** program.<folder>/<name>.enabled with a declared-but-off toggle disables, as in Iris. */
    @Test
    void programSwitches() {
        PackSettingsPlan settings = new PackSettingsPlan(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("world0/composite2", false, "composite3", false), Set.of(),
                Set.of(), Set.of(), List.of(), "default",
                PackRuntimeSettings.empty(), Map.of(), Map.of(), false, Map.of(), null);
        assertTrue(!settings.enabled("world0", "composite2"), "folder-scoped switch ignored");
        assertTrue(settings.enabled("world-1", "composite2"), "another folder's switch applied");
        assertTrue(settings.enabled("world0", "composite3") && !settings.enabled("", "composite3"),
                "a bare switch names the root program only, as Iris matches paths exactly");
    }

    /** uniform.bool reads through `uniform bool`, and gtexture is Iris's atlas name. */
    @Test
    void boolAndAtlasNames() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "bool", "cycle", "worldTime > 10")),
                Map.of(), List.of());
        assertTrue(settings.customDescriptors().get("cycle").accepts("bool")
                && settings.customDescriptors().get("cycle").accepts("int"), "bool custom types");
        assertTrue(UniformRegistry.GEOMETRY_NAME_TO_SLOT.get("gtexture") == 0
                        && UniformRegistry.ENTITY_NAME_TO_SLOT.get("gtexture") == 0
                        && PackResourcePlan.canonicalResource("gtexture").equals("texture"),
                "gtexture must alias the albedo atlas");
    }

    /** GLSL 3.3 section 3.1: backslash-newline splices lines before directives are read. */
    @Test
    void lineContinuation() {
        String source = String.join("\n",
                "#version 330",
                "#if defined(A) && \\",
                "    defined(B)",
                "float kept = 1.0;",
                "#else",
                "float dropped = 1.0;",
                "#endif",
                "#define SUM(x, y) \\",
                "    ((x) + (y))",
                "float total = SUM(1.0, 2.0);");
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, source, Map.of("A", "1", "B", "1"));
        assertTrue(result.successful(), "continued #if rejected: " + result.deviations());
        assertTrue(result.source().contains("kept") && !result.source().contains("dropped"),
                "continued #if chose the wrong branch:\n" + result.source());
        assertTrue(result.source().contains("float total = ((1.0) + (2.0));"),
                "continued #define was not spliced:\n" + result.source());
    }

    /** `#define attribute in` followed by a `const` line is a directive, not an `in const` varying. */
    @Test
    void directivesAreNotDeclarations() {
        String source = String.join("\n",
                "#version 400",
                "#define attribute in",
                "const float scale = 2.0;",
                "out vec2 uv;",
                "void main() { uv = vec2(scale); }");
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(source, true);
        assertTrue(stage.deviations().isEmpty(), "directive read as a declaration: " + stage.deviations());
        assertTrue(stage.outputs().size() == 1 && stage.outputs().get(0).name().equals("uv"),
                "declaration after a directive was missed: " + stage.outputs());
    }

    /** Iris's typed language: vectors, accessors, matrix columns, widening, and type errors. */
    @Test
    void typedExpressions() {
        PackExpression.Widths widths = name -> switch (name) {
            case "skyColor" -> 3;
            case "m" -> PackExpression.MATRIX_WIDTH;
            default -> 1;
        };
        // Column-major: element (column c, row r) is lane c * 4 + r.
        PackExpression.Inputs inputs = (name, component) -> switch (name) {
            case "skyColor" -> 10.0 + component;
            case "m" -> component;
            case "k" -> 2.0;
            default -> 0.0;
        };
        PackExpression.Program vector = PackExpression.parse("vec3(1.0, skyColor.g, m.2.1) * k",
                BiomeIds.constants(), widths);
        assertTrue(vector.width() == 3, "vec3 width " + vector.width());
        double[] expected = {2.0, 22.0, 18.0};
        double[] lanes = new double[3];
        vector.evaluate(inputs, vector.newState(), 0.0f, lanes, 0);
        for (int lane = 0; lane < 3; lane++) {
            assertTrue(lanes[lane] == expected[lane], "vec3 lane " + lane + " gave " + lanes[lane]);
        }
        // A scalar inside a vector is one value per frame: its smooth advances once, as in Iris.
        PackExpression.Program smoothed = PackExpression.parse("smooth(k, 10, 10) * vec3(1.0, 2.0, 3.0)",
                BiomeIds.constants(), widths);
        PackExpression.State state = smoothed.newState();
        PackExpression.Program reference = PackExpression.parse("smooth(k, 10, 10)", BiomeIds.constants(), widths);
        PackExpression.State referenceState = reference.newState();
        double[] values = new double[3];
        for (int frame = 0; frame < 5; frame++) {
            double k = frame == 0 ? 0.0 : 8.0;
            PackExpression.Inputs step = (name, component) -> name.equals("k") ? k : inputs.value(name, component);
            smoothed.evaluate(step, state, 0.05f, values, 0);
            double once = reference.evaluate(step, referenceState, 0.05f);
            assertTrue(Math.abs(values[0] - once) < 1e-9 && Math.abs(values[2] - 3.0 * once) < 1e-9,
                    "a scalar smooth in a vector advanced more than once per frame: " + values[0] + " vs " + once);
        }
        PackExpression.Program accessors = PackExpression.parse("skyColor.r + skyColor.s + skyColor.0",
                BiomeIds.constants(), widths);
        assertTrue(accessors.evaluate(inputs, accessors.newState(), 0.0f) == 30.0,
                "Iris accessor aliases r/s/0 must all pick component 0");
        PackExpression.Program column = PackExpression.parse("vec4(m.3).w", BiomeIds.constants(), widths);
        assertTrue(column.evaluate(inputs, column.newState(), 0.0f) == 15.0, "m.3 is the fourth column");
        rejected("vec2(1.0, 2.0) + vec3(1.0)", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("skyColor.xy", widths, "CUSTOM_EXPRESSION_COMPONENT");
        rejected("k.x", widths, "CUSTOM_EXPRESSION_COMPONENT");
        rejected("vec3(1.0, 2.0)", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("m + 1.0", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("skyColor > 1.0", widths, "CUSTOM_EXPRESSION_TYPE");
    }

    /** uniform.vecN values: declared width, per-component storage, and reads of their components. */
    @Test
    void vectorCustomValues() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "vec2", "view_res", "vec2(viewWidth, viewHeight)"),
                PackRuntimeSettings.declaration(false, "vec3", "dir", "vec3(0.0, 1.0, 2.0) * 2.0"),
                PackRuntimeSettings.declaration(true, "float", "dir_y", "dir.y"),
                PackRuntimeSettings.declaration(true, "vec3", "wrong", "1.0"),
                PackRuntimeSettings.declaration(true, "float", "cell",
                        "gbufferModelViewInverse.3.0")), Map.of(), List.of());
        assertTrue(settings.rejected().equals(Set.of("wrong")),
                "only the mistyped value is refused: " + settings.rejected() + " " + settings.deviations());
        assertTrue(settings.widthOf("view_res") == 2 && settings.widthOf("dir") == 3
                && settings.widthOf("dir_y") == 1, "widths");
        assertTrue("vec2".equals(settings.customDescriptors().get("view_res").acceptedTypes().get(0)),
                "a vec2 value is served as a vec2 uniform");
        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> switch (name) {
            case "viewWidth" -> 1920.0;
            case "viewHeight" -> 1080.0;
            case "gbufferModelViewInverse" -> component == 12 ? 7.0 : 0.0;
            default -> 0.0;
        }, session, 0.05f);
        float[] values = session.values();
        int res = settings.indexOf("view_res");
        assertTrue(values[res] == 1920.0f && values[res + 1] == 1080.0f, "view_res components");
        assertTrue(values[settings.indexOf("dir_y")] == 2.0f, "a value reads a vector value's component");
        assertTrue(values[settings.indexOf("cell")] == 7.0f, "matrix element m[3][0] is lane 12");
    }

    /** Iris's IrisDefines: CAT_* are BiomeCategories ordinals, PPT_* the precipitation codes. */
    @Test
    void irisBiomeConstants() {
        PackExpression.Constants constants = BiomeIds.constants();
        assertTrue(constants.value("CAT_NONE") == 0.0 && constants.value("CAT_DESERT") == 12.0
                && constants.value("CAT_UNDERGROUND") == 18.0, "CAT_* ordinals");
        assertTrue(constants.value("PPT_NONE") == 0.0 && constants.value("PPT_RAIN") == 1.0
                && constants.value("PPT_SNOW") == 2.0, "PPT_* codes");
        assertTrue(UniformRegistry.expressionWidth("biome_category") == 1, "biome_category is an int input");
    }

    private static void rejected(String source, PackExpression.Widths widths, String code) {
        try {
            PackExpression.parse(source, BiomeIds.constants(), widths);
        } catch (PackExpression.Unsupported failure) {
            assertTrue(failure.code().equals(code), source + " refused with " + failure.code() + ", expected " + code);
            return;
        }
        throw new AssertionError("accepted: " + source);
    }

    /**
     * The uniform names Iris serves a program (player, HUD, clock and DH), and the zero it leaves
     * in a uniform it does not provide.
     */
    @Test
    void irisUniforms() {
        UniformRegistry.ProgramInterface served = UniformRegistry.plan(String.join("\n",
                "uniform float currentPlayerHealth;", "uniform bool hideGUI;", "uniform int isRightHanded;",
                "uniform mat4 dhProjection;", "uniform float dhFarPlane;", "uniform int dhRenderDistance;",
                "uniform ivec3 currentDate;", "uniform vec3 playerBodyVector;",
                "void main() { gl_FragColor = vec4(currentPlayerHealth + float(hideGUI) + float(isRightHanded)",
                "    + dhProjection[0][0] + dhFarPlane + float(dhRenderDistance + currentDate.x) + "
                        + "playerBodyVector.x); }"),
                UniformRegistry.Stage.POST);
        assertTrue(served.executable(), "Iris player/DH uniforms must plan: " + served.deviations());
        assertTrue(served.deviations().stream().noneMatch(d -> d.startsWith("UNIFORM_UNSET_ZERO")),
                "served names must not fall back to zero: " + served.deviations());

        UniformRegistry.ProgramInterface unset = UniformRegistry.plan(
                "uniform float vxRenderDistance; void main() { gl_FragColor = vec4(vxRenderDistance); }",
                UniformRegistry.Stage.POST);
        assertTrue(unset.executable() && unset.deviations().contains("UNIFORM_UNSET_ZERO:vxRenderDistance"),
                "a name Iris does not serve runs on zero: " + unset.deviations());
        assertTrue(UniformRegistry.resolve("vxRenderDistance", "float", Map.of()) != null,
                "the runtime serves the same zero");
        assertTrue(UniformRegistry.resolve("fogDensity", "float", Map.of()) == null,
                "an unserved Iris name gets no substitute at runtime");
    }

    @Test
    void packOwnTexelSize() {
        // Bliss authors texelSize; Iris has no such builtin, so the pack's value stands.
        PackRuntimeSettings texel = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "vec2", "texelSize",
                        "vec2(1.0 / viewWidth, 1.0 / viewHeight)")), Map.of(), List.of());
        assertTrue(texel.rejected().isEmpty(), "texelSize is the pack's own: " + texel.deviations());
        UniformRegistry.UniformDescriptor resolved =
                UniformRegistry.resolve("texelSize", "vec2", texel.customDescriptors());
        assertTrue(resolved != null && resolved.sourceKey().startsWith("custom:"),
                "the pack's texelSize wins over the host name");
        assertTrue(UniformRegistry.isEngineInput("gbufferProjection") && !UniformRegistry.isEngineInput("texelSize"),
                "Iris names stay engine inputs; VulkanMod names do not");
    }

    @Test
    void shadowSamplerAliases() {
        // IrisSamplers.addShadowSamplers: shadow is shadowtex0 unless watershadow is declared.
        assertTrue(PackResourcePlan.programResource("shadow", false).equals("shadowtex0")
                && PackResourcePlan.programResource("shadow", true).equals("shadowtex1")
                && PackResourcePlan.programResource("watershadow", true).equals("shadowtex0")
                && PackResourcePlan.programResource("shadowcolor", false).equals("shadowcolor0"),
                "shadow alias table");
        UniformRegistry.ProgramInterface water = UniformRegistry.plan(
                "uniform sampler2DShadow shadow; void main() { gl_FragColor = vec4(shadow2D(shadow, vec3(0.0)).r); }",
                UniformRegistry.Stage.TRANSLUCENT);
        assertTrue(water.executable() && slotOf(water, "shadow") == 5, "water reads shadow as shadowtex0: "
                + water.deviations());
        UniformRegistry.ProgramInterface both = UniformRegistry.plan(String.join("\n",
                "uniform sampler2D shadow;", "uniform sampler2D watershadow;",
                "void main() { gl_FragColor = texture2D(shadow, vec2(0.0)) + texture2D(watershadow, vec2(0.0)); }"),
                UniformRegistry.Stage.POST);
        assertTrue(both.executable() && slotOf(both, "shadow") == SelectorNamespace.SHADOW_TEX1_SLOT
                        && slotOf(both, "watershadow") == 5,
                "with watershadow, shadow is the opaque casters: " + both.deviations());
    }

    @Test
    void fogColor() {
        // CommonTransformer: gl_Fog.color is iris_FogColor, the fog RGBA.
        LegacyShaderNormalizer.Result undeclared = LegacyShaderNormalizer.normalize(
                "#version 120\nvoid main() { gl_FragColor = gl_Fog.color; }", false);
        assertTrue(undeclared.successful() && undeclared.source().contains("uniform vec4 fogColor;")
                && undeclared.source().contains("gl_FragColor = fogColor;"), "gl_Fog.color:\n" + undeclared.source());
        LegacyShaderNormalizer.Result vec3 = LegacyShaderNormalizer.normalize(
                "#version 120\nuniform vec3 fogColor;\nvoid main() { gl_FragColor = gl_Fog.color; }", false);
        assertTrue(vec3.successful() && vec3.source().contains("vec4(fogColor, 1.0)")
                && !vec3.source().contains("uniform vec4 fogColor"), "vec3 fogColor kept:\n" + vec3.source());
    }

    @Test
    void fogFragCoord() {
        // CommonTransformer: gl_FogFragCoord is the varying iris_FogFragCoord, zeroed first in the vertex stage.
        LegacyShaderNormalizer.Result fogVertex = LegacyShaderNormalizer.normalize(
                "#version 120\nvoid main() {\n    gl_FogFragCoord = 4.0;\n}\n", true);
        assertTrue(fogVertex.successful() && fogVertex.source().contains("varying float iris_FogFragCoord;")
                        && fogVertex.source().indexOf("iris_FogFragCoord = 0.0;")
                        < fogVertex.source().indexOf("iris_FogFragCoord = 4.0;")
                        && !fogVertex.source().contains("gl_FogFragCoord"),
                "gl_FogFragCoord vertex:\n" + fogVertex.source());
        LegacyShaderNormalizer.Result fogFragment = LegacyShaderNormalizer.normalize(
                "#version 120\nvoid main() { gl_FragColor = vec4(gl_FogFragCoord); }", false);
        assertTrue(fogFragment.successful() && fogFragment.source().contains("varying float iris_FogFragCoord;")
                        && fogFragment.source().contains("vec4(iris_FogFragCoord)")
                        && !fogFragment.source().contains("= 0.0;"),
                "gl_FogFragCoord fragment:\n" + fogFragment.source());
        assertTrue(LegacyShaderNormalizer.normalize(fogVertex.source(), true).source().equals(fogVertex.source()),
                "gl_FogFragCoord normalization must be idempotent");
    }

    @Test
    void vertexOutputsStartAtZero() {
        // BSL gbuffers_basic writes isSelection only for the selection outline. GL drivers leave it
        // 0 elsewhere; Vulkan leaves it undefined, and leads took the unlit outline path at random.
        String bsl = """
                #version 120
                varying float isSelection;
                varying vec2 texCoord, lmCoord;
                flat varying int blockId;
                varying vec4 taps[2];
                varying vec3 unread;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    lmCoord = gl_MultiTexCoord1.xy;
                    blockId = 3;
                    taps[0] = gl_Color;
                    if (gl_Color.a > 0.399 && gl_Color.a < 0.401) {
                        isSelection = 1.0;
                    }
                    gl_Position = ftransform();
                }
                """;
        LegacyShaderNormalizer.Result vertex = LegacyShaderNormalizer.normalize(bsl, true);
        String source = vertex.source();
        assertTrue(vertex.successful() && source.indexOf("isSelection = float(0);") >= 0
                        && source.indexOf("isSelection = float(0);") < source.indexOf("isSelection = 1.0;")
                        && source.contains("texCoord = vec2(0);") && source.contains("lmCoord = vec2(0);")
                        && source.contains("blockId = int(0);")
                        && source.contains(
                                "chimeraZeroIndex < 2; chimeraZeroIndex++) taps[chimeraZeroIndex] = vec4(0);")
                        && !source.contains("unread ="),
                "referenced vertex outputs start at zero, before the authored writes:\n" + source);
        assertTrue(LegacyShaderNormalizer.normalize(source, true).source().equals(source),
                "zeroing vertex outputs must be idempotent");
        String fragment = LegacyShaderNormalizer.normalize(
                "#version 120\nvarying float isSelection;\nvoid main() { gl_FragColor = vec4(isSelection); }",
                false).source();
        assertTrue(!fragment.contains("= float(0)"), "fragment inputs are not written:\n" + fragment);
    }

    @Test
    void uniformDeclaratorLists() {
        // Solas declares samplers in lists; every per-name rewriter needs one declaration per name.
        String lists = GlslTokenRewriter.splitUniformDeclarators("""
                #version 330
                #define LIST uniform float a, b;
                layout(binding = 3) uniform sampler3D floodfillSampler, floodfillSamplerCopy;
                uniform highp sampler2D depthtex0, depthtex1; // two depth snapshots
                uniform vec3 tint = vec3(1.0, 0.5, 0.25), shade[2];
                uniform Block { float x, y; } blocked;
                void main() { float c = 1.0, d = 2.0; }
                """);
        assertTrue(lists.contains("layout(binding = 3) uniform sampler3D floodfillSampler;\n"
                        + "layout(binding = 3) uniform sampler3D floodfillSamplerCopy;")
                        && lists.contains("uniform highp sampler2D depthtex0;\nuniform highp sampler2D depthtex1;")
                        && lists.contains("uniform vec3 tint = vec3(1.0, 0.5, 0.25);\nuniform vec3 shade[2];")
                        && lists.contains("#define LIST uniform float a, b;")
                        && lists.contains("{ float x, y; }")
                        && lists.contains("float c = 1.0, d = 2.0;"),
                "uniform declarator lists:\n" + lists);
    }

    @Test
    void commentedUniformDeclarations() {
        // Solas comments "a corresponding uniform in shaders.properties" right above its declarations.
        String commented = """
                //Every biome has a corresponding uniform in the shaders.properties file
                uniform float frameTimeCounter;
                /* uniform float inBlockComment; */
                void main() { gl_FragColor = vec4(frameTimeCounter); }
                """;
        String stripped = UniformRegistry.removeUniformDeclarations(commented,
                UniformRegistry.plan(commented, UniformRegistry.Stage.POST));
        assertTrue(!stripped.contains("uniform float frameTimeCounter;")
                        && stripped.contains("corresponding uniform in the shaders.properties file")
                        && stripped.contains("/* uniform float inBlockComment; */"),
                "a comment must not start a uniform declaration:\n" + stripped);
    }

    @Test
    void nonConstantGlobals() {
        // Photon: constants stay const (array sizes and later consts need them); only a
        // global const that really depends on a uniform, a plain global or a function loses it.
        String relaxed = GlslTokenRewriter.relaxNonConstantGlobals("""
                uniform float frameTime;
                float plain = 2.0;
                const int char_width = 5;
                const ivec2 char_size = ivec2(char_width, 6);
                const float pi = acos(-1.0), tau = 2.0 * pi;
                const vec3 weights = vec3(0.2, 0.7, 0.1).zyx;
                const float[2] pair = float[2](pi, tau);
                const float animated = frameTime * 2.0;
                const float derived = animated + 1.0;
                const float fromPlain = plain;
                float helper() { return 1.0; }
                const float called = helper();
                float samples[char_width];
                float shade(const float x) { const float y = x * 2.0; return y; }
                """);
        assertTrue(relaxed.contains("const int char_width = 5;")
                        && relaxed.contains("const ivec2 char_size")
                        && relaxed.contains("const float pi = acos(-1.0), tau")
                        && relaxed.contains("const vec3 weights")
                        && relaxed.contains("const float[2] pair")
                        && !relaxed.contains("const float animated")
                        && !relaxed.contains("const float derived")
                        && !relaxed.contains("const float fromPlain")
                        && !relaxed.contains("const float called")
                        && relaxed.contains("float shade(const float x) { const float y"),
                "non-constant global const relaxation:\n" + relaxed);
    }

    @Test
    void samplerMacroAliases() {
        // Photon reaches samplers through macros; each expands while it is defined.
        String aliased = GlslMacroExpander.expand("""
                uniform sampler2D colortex0;
                uniform sampler2D colortex5;
                #define rec709_to_working_color rec709_to_rec2020
                #define SRC_SAMPLER colortex5
                #define LUT SRC_SAMPLER
                #define SAMPLE(uv) texture(SRC_SAMPLER, uv)
                vec4 a = texture(SRC_SAMPLER, vec2(0.0)) + texture(LUT, vec2(0.0)) + SAMPLE(vec2(1.0));
                #undef SRC_SAMPLER
                #define SRC_SAMPLER colortex0
                vec4 b = texture(SRC_SAMPLER, vec2(0.0));
                vec3 c = rec709_to_working_color[0];
                """);
        assertTrue(!aliased.contains("SRC_SAMPLER") && !aliased.contains("#define")
                        && aliased.contains("vec4 a = texture(colortex5, vec2(0.0)) + texture(colortex5, vec2(0.0))"
                                + " + texture(colortex5, vec2(1.0));")
                        && aliased.contains("vec4 b = texture(colortex0, vec2(0.0));")
                        && aliased.contains("vec3 c = rec709_to_rec2020[0];"),
                "sampler alias expansion:\n" + aliased);
    }

    @Test
    void objectMacroReachability() {
        // Photon calls tonemap_lottes only as tonemap(x) through an object-like alias, which preparation expands.
        String reachable = GlslMacroExpander.expand("""
                #define tonemap tonemap_lottes
                vec3 tonemap_lottes(vec3 rgb) { return rgb; }
                vec3 unused_operator(vec3 rgb) { return rgb * 2.0; }
                void main() { gl_FragColor = vec4(tonemap(vec3(1.0)), 1.0); }
                """);
        String pruned = GlslTokenRewriter.removeUnreachableFunctions(reachable,
                GlslResourceUsage.analyze(reachable));
        assertTrue(pruned.contains("vec3 tonemap_lottes(vec3 rgb)") && !pruned.contains("unused_operator"),
                "object-like macro call reachability:\n" + pruned);
    }

    @Test
    void modernOutputLocations() {
        // Photon: explicit (even multi-line) output locations hold; unlocated ones take the next free one.
        String outputs = "/* RENDERTARGETS: 6,14,3 */\nlayout(\n    location = 1\n) out vec4 b;\n"
                + "out vec2 a;\nlayout(location = 0) out vec3 c;\nvoid main() {}\n";
        List<PostTargetPlan.ModernOutput> modern = PostTargetPlan.modernOutputs(outputs);
        PostTargetPlan routed = PostTargetPlan.parse("composite", outputs).plan();
        assertTrue(modern.size() == 3
                        && modern.get(0).name().equals("b") && modern.get(0).location() == 1
                        && modern.get(1).name().equals("a") && modern.get(1).location() == 2
                        && modern.get(2).name().equals("c") && modern.get(2).location() == 0
                        && routed.outputLocations().equals(List.of(0, 1, 2))
                        && routed.outputType(0).equals("vec3") && routed.outputType(2).equals("vec2"),
                "modern output locations: " + modern + " plan " + routed.outputLocations());
    }

    @Test
    void worldColourTargetReads() {
        // MakeUp: world programs read pack colour targets (gaux4 = colortex7) as Iris binds them;
        // colortex0..3 stay off the host atlas/overlay/lightmap/shadowcolor selectors.
        String worldReader = """
                uniform sampler2D gaux4;
                uniform sampler2D colortex2;
                void main() { gl_FragColor = texture2D(gaux4, vec2(0.5)) + texture2D(colortex2, vec2(0.5)); }
                """;
        for (UniformRegistry.Stage world : List.of(UniformRegistry.Stage.GEOMETRY, UniformRegistry.Stage.ENTITY,
                UniformRegistry.Stage.BLOCK)) {
            UniformRegistry.ProgramInterface reader = UniformRegistry.plan(worldReader, world);
            assertTrue(slotOf(reader, "gaux4") == SelectorNamespace.colorTargetSlot(7)
                            && reader.samplers().stream().noneMatch(value -> value.name().equals("colortex2")),
                    world + " colour target reads: " + reader.samplers() + " " + reader.deviations());
        }
    }

    @Test
    void implicitModelView() {
        // The terrain bridge rewrites gl_ModelViewMatrix to gbufferModelView; the plan must serve it.
        UniformRegistry.ProgramInterface legacyMatrix = UniformRegistry.plan(
                "void main() { gl_Position = gl_ModelViewMatrix * gl_Vertex; }", UniformRegistry.Stage.GEOMETRY);
        assertTrue(legacyMatrix.uniforms().stream().anyMatch(value -> value.name().equals("gbufferModelView")),
                "implicit gbufferModelView: " + legacyMatrix.uniforms());
    }

    @Test
    void entityDefaultAttribute() {
        // Iris's entity vertex format has no mc_Entity: GL reads the unbound default (0, 0, 0, 1).
        String entity = EntityDeclarations.removeEntityAttributes(
                "attribute vec4 mc_Entity;\nattribute vec2 mc_midTexCoord;\nvoid main() {}\n");
        assertTrue(entity.contains("const vec4 mc_Entity = vec4(0.0, 0.0, 0.0, 1.0);")
                        && !entity.contains("mc_midTexCoord"),
                "entity mc_Entity default:\n" + entity);
    }

    /** Iris ClearPassCreator: undeclared colortex0 clears to the fog (alpha 1), colortex1 to white. */
    @Test
    void irisClearDefaults() {
        float[] fog = {0.2F, 0.4F, 0.6F};
        var undeclared = PackConfig.TargetSettings.defaults();
        assertTrue(undeclared.clearColorCopy() == null, "an undeclared clear colour stays undeclared");
        assertArrayEquals(new float[] {0.2F, 0.4F, 0.6F, 1.0F}, undeclared.irisClearColor(0, fog));
        assertArrayEquals(new float[] {1.0F, 1.0F, 1.0F, 1.0F}, undeclared.irisClearColor(1, null));
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F, 0.0F}, undeclared.irisClearColor(2, null));
        // Photon declares colortex0ClearColor = vec4(0.0): the fog never reaches colortex0.
        var declared = new PackConfig.TargetSettings("", true, new float[] {0.0F, 0.0F, 0.0F, 0.0F}, List.of());
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F, 0.0F}, declared.irisClearColor(0, fog));
        assertArrayEquals(new float[] {0.0F, 0.0F, 0.0F, 0.0F}, declared.irisClearColor(1, null));
    }

    @Test
    void persistentWorldTargetSeed() {
        // A persistent target a world program reads needs a defined first frame even with no post reader.
        TargetSpec fog = new TargetSpec(7, 97, 16, 16, false, null, true, false, false, false, List.of());
        assertTrue(PackTargetGraphPlan.requiresInitialSeed(fog, List.of(), Set.of(7))
                        && !PackTargetGraphPlan.requiresInitialSeed(fog, List.of(), Set.of()),
                "persistent world-read target seeding");
    }

    /** A function the program defines under a legacy lookup's name is the program's own (Solas). */
    @Test
    void userDefinedShadowLookup() {
        String source = String.join("\n",
                "uniform sampler2D shadowtex0;",
                "float texture2DShadow(sampler2D shadowtex, vec3 p) { return texture2D(shadowtex, p.xy).r; }",
                "void main() { gl_FragColor = vec4(texture2DShadow(shadowtex0, vec3(0.0))); }");
        String rewritten = GlslTokenRewriter.rewriteShadowCalls(source, Map.of("shadowtex0", "sampler2D"));
        assertTrue(rewritten.equals(source), "a user-defined texture2DShadow was rewritten:\n" + rewritten);
        String legacy = "uniform sampler2DShadow shadow;\nvoid main() { gl_FragColor = shadow2D(shadow, vec3(0.0)); }";
        assertTrue(GlslTokenRewriter.rewriteShadowCalls(legacy, Map.of("shadow", "sampler2DShadow"))
                .contains("vec4(texture(shadow, vec3(0.0)))"), "the built-in shadow2D is still rewritten");
    }

    @Test
    void legacyTexelFetch() {
        String source = "#version 450\nlayout(binding=0) uniform sampler2D image2;\n"
                + "layout(binding=1) uniform sampler3D image3;\nlayout(location=0) out vec4 color;\n"
                + "// texelFetch2D stays in this comment\n"
                + "void main() { color = texelFetch2D(image2, ivec2(0), 0)"
                + " + texelFetch3D(image3, ivec3(0), 0); }\n";
        String converted = GlslTokenRewriter.rewriteTextureCalls(source);
        assertTrue(converted.contains("// texelFetch2D stays in this comment")
                && !converted.contains("texelFetch2D(image2")
                && !converted.contains("texelFetch3D(image3"), "EXT texelFetch call rewrite");
        assertTrue(PackCompileTest.compile(converted, false) == null, "converted texelFetch calls compile");
    }

    @Test
    void reservedSamplerParameter() {
        String source = "#version 450\nlayout(binding=0) uniform sampler2D image;\n"
                + "layout(location=0) out vec4 color;\n"
                + "// sampler remains in comments\n"
                + "vec4 sampleImage(sampler2D sampler, vec2 uv) { float chimeraSamplerParameter = 0.0;"
                + " return texture(sampler, uv) + chimeraSamplerParameter; }\n"
                + "void main() { color = sampleImage(image, vec2(0.5)); }\n";
        String converted = GlslTokenRewriter.renameReservedSamplerParameters(source);
        assertTrue(converted.contains("sampler2D chimeraSamplerParameter_")
                && converted.contains("// sampler remains in comments"), "scoped collision-safe sampler rename");
        assertTrue(PackCompileTest.compile(converted, false) == null, "renamed sampler parameter compiles");
    }

    @Test
    void compileFallbackBoundary() {
        PackPipelines.handleBuildFailure("expected-test-fallback",
                new PackPipelines.PreparationFailure("shader-compilation", "SHADER_COMPILATION_FAILED"));
        for (String phase : List.of("descriptor-contract", "resource-rewrite", "native-create")) {
            var expected = new PackPipelines.PreparationFailure(phase, "EXPECTED_CONTRACT_FAILURE");
            try {
                PackPipelines.handleBuildFailure("expected-test-fatal", expected);
                throw new AssertionError("contract failure swallowed: " + phase);
            } catch (PackPipelines.PreparationFailure actual) {
                assertTrue(actual == expected, "original contract error must propagate");
            }
        }
    }

    private static int slotOf(UniformRegistry.ProgramInterface plan, String sampler) {
        return plan.samplers().stream().filter(binding -> binding.name().equals(sampler))
                .mapToInt(UniformRegistry.SamplerBinding::slot).findFirst().orElse(-1);
    }
}
