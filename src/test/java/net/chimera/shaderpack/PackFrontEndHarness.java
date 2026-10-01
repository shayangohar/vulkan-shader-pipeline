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
        System.out.println("[chimera] pack front-end generality: PASS");
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
