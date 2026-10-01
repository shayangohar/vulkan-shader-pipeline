package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Broadening step 1 (TASK-455): the pack front end reads the Iris/GLSL formats in full rather
 * than the subset the baseline packs needed. Each check is the spec behaviour, not a pack's usage.
 */
public final class PackFrontEndHarness {
    private PackFrontEndHarness() {}

    public static void main(String[] args) {
        verifyLineContinuation();
        verifyDirectivesAreNotDeclarations();
        verifyTypedExpressions();
        verifyVectorCustomValues();
        verifyIrisBiomeConstants();
        verifyInterfaceTypes();
        verifyRawTextureTypeMatch();
        verifyProgramSwitches();
        verifyBoolAndAtlasNames();
        System.out.println("[chimera] pack front-end generality: PASS");
    }

    /** Any GLSL interface type, packed by the locations it occupies (GLSL 4.60 section 4.4.1). */
    private static void verifyInterfaceTypes() {
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
        check(stage.deviations().isEmpty(), "interface types rejected: " + stage.deviations());
        // Name order: fog(1+1+2=4) light(1) mask(1) sky_sh(9) tbn(3) uv(1).
        check(stage.locations().equals(Map.of("fog", 0, "light", 4, "mask", 5, "sky_sh", 6,
                "tbn", 15, "uv", 18)), "packed locations " + stage.locations());
        check(stage.output("sky_sh").typeWithArray().equals("vec3[9]"), "array type");
        String rewritten = GlslInterfaceScanner.rewrite(vertex, true, declaration ->
                "layout(location = " + stage.locations().get(declaration.name()) + ") "
                        + (declaration.qualifier() == null ? "" : declaration.qualifier() + " ")
                        + "out " + declaration.type() + " " + declaration.declarator() + ";\n");
        check(rewritten.contains("layout(location = 6) flat out vec3 sky_sh[9];")
                        && rewritten.contains("layout(location = 4) out vec2 light;")
                        && rewritten.contains("layout(location = 18) out vec2 uv;")
                        && rewritten.contains("struct Fog {"),
                "rewrite did not give each name its location:\n" + rewritten);
        String bad = "#version 400\nflat out Unknown thing;\nvoid main() {}";
        check(GlslInterfaceScanner.scan(bad, true).deviations()
                        .contains("PROGRAM_VARYING_TYPE_UNSUPPORTED:Unknown"),
                "an undefined struct type must be named");
    }

    /** Iris patches a raw texture only into a sampler of the same dimensionality. */
    private static void verifyRawTextureTypeMatch() {
        PackResourceDeclaration volume = new PackResourceDeclaration("texture.composite.colortex0",
                "composite", "colortex0", "image/noise.dat TEXTURE_3D R8 64 64 64 RED UNSIGNED_BYTE",
                PackResourceKind.RAW_TEXTURE);
        check(PackResourcePlan.appliesTo(volume, "sampler3D"), "a 3D volume serves sampler3D");
        check(!PackResourcePlan.appliesTo(volume, "sampler2D"),
                "a 3D volume must leave sampler2D colortex0 as the render target");
        PackResourceDeclaration png = new PackResourceDeclaration("texture.composite.colortex13",
                "composite", "colortex13", "image/galaxy.png", PackResourceKind.PACK_TEXTURE);
        check(PackResourcePlan.appliesTo(png, "sampler2D"), "a PNG declaration applies as before");
    }

    /** program.<folder>/<name>.enabled with a declared-but-off toggle disables, as in Iris. */
    private static void verifyProgramSwitches() {
        PackSettingsPlan settings = new PackSettingsPlan(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("world0/composite2", false, "composite3", false), java.util.Set.of(),
                java.util.Set.of(), java.util.Set.of(), List.of(), "default");
        check(!settings.enabled("world0", "composite2"), "folder-scoped switch ignored");
        check(settings.enabled("world-1", "composite2"), "another folder's switch applied");
        check(!settings.enabled("world0", "composite3"), "bare switch must still apply");
    }

    /** uniform.bool reads through `uniform bool`, and gtexture is Iris's atlas name. */
    private static void verifyBoolAndAtlasNames() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "bool", "cycle", "worldTime > 10")),
                Map.of(), List.of());
        check(settings.customDescriptors().get("cycle").accepts("bool")
                && settings.customDescriptors().get("cycle").accepts("int"), "bool custom types");
        check(UniformRegistry.GEOMETRY_NAME_TO_SLOT.get("gtexture") == 0
                        && UniformRegistry.ENTITY_NAME_TO_SLOT.get("gtexture") == 0
                        && PackResourcePlan.canonicalResource("gtexture").equals("texture"),
                "gtexture must alias the albedo atlas");
    }

    /** GLSL 3.3 section 3.1: backslash-newline splices lines before directives are read. */
    private static void verifyLineContinuation() {
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
        check(result.successful(), "continued #if rejected: " + result.deviations());
        check(result.source().contains("kept") && !result.source().contains("dropped"),
                "continued #if chose the wrong branch:\n" + result.source());
        check(result.source().lines().anyMatch(line ->
                        line.startsWith("#define SUM(x, y)") && line.endsWith("((x) + (y))")),
                "continued #define was not spliced:\n" + result.source());
    }

    /** `#define attribute in` followed by a `const` line is a directive, not an `in const` varying. */
    private static void verifyDirectivesAreNotDeclarations() {
        String source = String.join("\n",
                "#version 400",
                "#define attribute in",
                "const float scale = 2.0;",
                "out vec2 uv;",
                "void main() { uv = vec2(scale); }");
        GlslInterfaceScanner.StageInterface stage = GlslInterfaceScanner.scan(source, true);
        check(stage.deviations().isEmpty(), "directive read as a declaration: " + stage.deviations());
        check(stage.outputs().size() == 1 && stage.outputs().get(0).name().equals("uv"),
                "declaration after a directive was missed: " + stage.outputs());
    }

    /** Iris's typed language: vectors, accessors, matrix columns, widening, and type errors. */
    private static void verifyTypedExpressions() {
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
        check(vector.width() == 3, "vec3 width " + vector.width());
        double[] expected = {2.0, 22.0, 18.0};
        for (int lane = 0; lane < 3; lane++) {
            double value = vector.evaluate(inputs, vector.newState(), 0.0f, lane);
            check(value == expected[lane], "vec3 lane " + lane + " gave " + value);
        }
        PackExpression.Program accessors = PackExpression.parse("skyColor.r + skyColor.s + skyColor.0",
                BiomeIds.constants(), widths);
        check(accessors.evaluate(inputs, accessors.newState(), 0.0f) == 30.0,
                "Iris accessor aliases r/s/0 must all pick component 0");
        PackExpression.Program column = PackExpression.parse("vec4(m.3).w", BiomeIds.constants(), widths);
        check(column.evaluate(inputs, column.newState(), 0.0f) == 15.0, "m.3 is the fourth column");
        rejected("vec2(1.0, 2.0) + vec3(1.0)", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("skyColor.xy", widths, "CUSTOM_EXPRESSION_COMPONENT");
        rejected("k.x", widths, "CUSTOM_EXPRESSION_COMPONENT");
        rejected("vec3(1.0, 2.0)", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("m + 1.0", widths, "CUSTOM_EXPRESSION_TYPE");
        rejected("skyColor > 1.0", widths, "CUSTOM_EXPRESSION_TYPE");
    }

    /** uniform.vecN values: declared width, per-component storage, and reads of their components. */
    private static void verifyVectorCustomValues() {
        PackRuntimeSettings settings = PackRuntimeSettings.build(List.of(
                PackRuntimeSettings.declaration(true, "vec2", "view_res", "vec2(viewWidth, viewHeight)"),
                PackRuntimeSettings.declaration(false, "vec3", "dir", "vec3(0.0, 1.0, 2.0) * 2.0"),
                PackRuntimeSettings.declaration(true, "float", "dir_y", "dir.y"),
                PackRuntimeSettings.declaration(true, "vec3", "wrong", "1.0"),
                PackRuntimeSettings.declaration(true, "float", "cell",
                        "gbufferModelViewInverse.3.0")), Map.of(), List.of());
        check(settings.rejected().equals(java.util.Set.of("wrong")),
                "only the mistyped value is refused: " + settings.rejected() + " " + settings.deviations());
        check(settings.widthOf("view_res") == 2 && settings.widthOf("dir") == 3
                && settings.widthOf("dir_y") == 1, "widths");
        check("vec2".equals(settings.customDescriptors().get("view_res").acceptedTypes().get(0)),
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
        check(values[res] == 1920.0f && values[res + 1] == 1080.0f, "view_res components");
        check(values[settings.indexOf("dir_y")] == 2.0f, "a value reads a vector value's component");
        check(values[settings.indexOf("cell")] == 7.0f, "matrix element m[3][0] is lane 12");
    }

    /** Iris's IrisDefines: CAT_* are BiomeCategories ordinals, PPT_* the precipitation codes. */
    private static void verifyIrisBiomeConstants() {
        PackExpression.Constants constants = BiomeIds.constants();
        check(constants.value("CAT_NONE") == 0.0 && constants.value("CAT_DESERT") == 12.0
                && constants.value("CAT_UNDERGROUND") == 18.0, "CAT_* ordinals");
        check(constants.value("PPT_NONE") == 0.0 && constants.value("PPT_RAIN") == 1.0
                && constants.value("PPT_SNOW") == 2.0, "PPT_* codes");
        check(UniformRegistry.expressionWidth("biome_category") == 1, "biome_category is an int input");
    }

    private static void rejected(String source, PackExpression.Widths widths, String code) {
        try {
            PackExpression.parse(source, BiomeIds.constants(), widths);
        } catch (PackExpression.Unsupported failure) {
            check(failure.code().equals(code), source + " refused with " + failure.code() + ", expected " + code);
            return;
        }
        throw new AssertionError("accepted: " + source);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
