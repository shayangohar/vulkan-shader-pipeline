package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading and preparing a pack's sources: archives, dimension folders, includes, conditionals and
 * macros, the legacy post conversion's edges, token boundaries and the preprocessor's budget.
 */
final class PackSourceEdgesTest {
    /**
     * Iris preprocesses dimension.properties with JCPP, which reads every comment as whitespace.
     * Only a line comment was stripped, so a block comment stayed in a #define body and the #if
     * that tested it failed.
     */
    @Test
    void aCommentInDimensionPropertiesIsWhitespace(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("dimension.properties"), """
                #define CUSTOM 1 /* note */
                #if CUSTOM == 1 /* note */
                dimension.world_custom = example:custom
                #endif
                #if /* note */ CUSTOM == 2 // note
                dimension.world_wrong = example:wrong
                #endif
                """);
        List<String> deviations = new ArrayList<>();
        Map<String, String> folders = DimensionProperties.read(root, Map.of(), deviations).worlds();
        assertEquals(Map.of("example:custom", "world_custom"), folders, String.valueOf(deviations));
        assertTrue(deviations.isEmpty(), String.valueOf(deviations));
    }

    /**
     * Iris reads dimension.properties as it reads the id maps (PropertiesPreprocessor, then
     * Properties.load), with the standard environment defines. A continuation goes on to the next
     * line (superDuperVanilla lists undergarden:undergarden under world-1 that way), a block comment
     * runs across lines (KNOW-1148), and a colon separates a key as an equals sign does. Each folder
     * here is what JCPP and Properties.load give for this file.
     */
    @Test
    void dimensionPropertiesReadsAsIrisLoadsIt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("dimension.properties"), """
                dimension.world-1 = minecraft:the_nether \\
                    undergarden:undergarden
                #if MC_VERSION >= 11300
                dimension.world1 = the_end
                #endif
                /*
                #if 0
                */
                dimension.world0 = *
                #define CUSTOM 1 /* open
                dimension.world_wrong = example:wrong
                */
                dimension.world2 : example:colon
                """);
        List<String> deviations = new ArrayList<>();
        DimensionProperties.Folders folders = DimensionProperties.read(root, Map.of("MC_VERSION", "12101"), deviations);
        assertEquals(Map.of("minecraft:the_nether", "world-1", "undergarden:undergarden", "world-1",
                "minecraft:the_end", "world1", "*", "world0", "example:colon", "world2"), folders.worlds());
        assertTrue(folders.present() && deviations.isEmpty(), String.valueOf(deviations));

        Files.writeString(root.resolve("dimension.properties"), "# only a comment\n");
        assertTrue(!DimensionProperties.read(root, Map.of(), deviations).present(),
                "a file with no property maps the standard folders, as no file does");
        assertTrue(DimensionProperties.folderNames(root).isEmpty(), "and the option scan takes the world folders");

        // Iris preprocesses it with its environment defines (StandardMacros), IS_IRIS among them.
        Files.writeString(root.resolve("dimension.properties"), """
                #ifdef IS_IRIS
                dimension.world0 = *
                #endif
                #if MC_VERSION < 11300
                dimension.world_legacy = minecraft:overworld
                #endif
                """);
        assertEquals(Map.of("*", "world0"), DimensionProperties.read(root, deviations).worlds());
        assertEquals(Optional.of(List.of("world0")), DimensionProperties.folderNames(root),
                "the option scan reads the same preprocessed keys");
    }

    /**
     * Iris pastes an include in as text before JCPP reads the program (IncludeProcessor), so what an
     * include leaves open goes on into the includer (KNOW-1148): its open block comment hides the
     * includer's #define up to the close, and a directive whose comment is still open at the
     * include's end goes on after the includer's close (X is 1 + 1). JCPP gives these results for
     * the pasted text. Before, each file started outside any comment.
     */
    @Test
    void whatAnIncludeLeavesOpenGoesOnIntoTheIncluder(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("lib"));
        Files.writeString(root.resolve("lib/open.glsl"), "float opened = 1.0; /* opens\n");
        Files.writeString(root.resolve("lib/define.glsl"), "#define X 1 /* open\n");
        String source = String.join("\n", "#version 120",
                "#include \"/lib/open.glsl\"",
                "#define HIDDEN 1",
                "*/",
                "#ifdef HIDDEN", "float hidden = 1.0;", "#endif",
                "#include \"/lib/define.glsl\"",
                "*/ + 1",
                "#if X == 2", "float joined = 1.0;", "#endif",
                "void main() {}");
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(root, root.resolve("main.fsh"),
                source, Map.of());
        assertTrue(result.successful(), String.valueOf(result.deviations()));
        assertTrue(result.source().contains("float opened"), result.source());
        assertTrue(!result.source().contains("float hidden"), "the include's comment hides the #define:\n"
                + result.source());
        assertTrue(result.source().contains("float joined") && !result.source().contains("+ 1"),
                "the include's #define goes on after the includer's close:\n" + result.source());
        assertEquals(List.of("lib/define.glsl", "lib/open.glsl"), result.dependencies());
    }

    /**
     * Iris's JCPP lexes a quote as a string or character literal, in a skipped branch too, so a
     * comment mark after one is text: the apostrophe in {@code don't} keeps the {@code /*} from
     * hiding the #endif, and a #define with a quoted comment mark takes no lines. JCPP gives these
     * results.
     */
    @Test
    void aQuoteHidesACommentMarkInGlsl() {
        String source = String.join("\n", "#version 120",
                "#if 0", "don't /* use this", "#endif", "float kept = 1.0;",
                "#define Q \"a /* b\"", "float q = 1.0;",
                "#if 1 /* x", "it's */ && 1", "float joined = 1.0;", "#endif",
                "void main() {}");
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(Path.of("."), null, source,
                Map.of());
        assertTrue(result.successful(), String.valueOf(result.deviations()));
        for (String kept : List.of("float kept", "float q", "float joined")) {
            assertTrue(result.source().contains(kept), kept + ":\n" + result.source());
        }
    }

    /**
     * An include name that is no path on Windows ({@code *} is not allowed there) is unresolved: it
     * threw InvalidPathException out of prepare. A quoted {@code /*} now stays in the name, as JCPP
     * reads it.
     */
    @Test
    void anIncludeNameThatIsNoPathIsUnresolved(@TempDir Path root) {
        for (String name : List.of("a*b.glsl", "a/*b.glsl")) {
            String source = "#version 120\n#include \"" + name + "\"\nvoid main() {}\n";
            ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(root,
                    root.resolve("main.fsh"), source, Map.of());
            assertEquals(List.of("SOURCE_INCLUDE_UNRESOLVED"), result.deviations(), name);
        }
    }

    @Test
    void archiveEdges(@TempDir Path root) throws IOException {
        Path validZip = root.resolve("valid.zip");
        writeZip(validZip, Map.of(
                "Example/shaders/shaders.json",
                        "{\"programs\":[{\"name\":\"composite\",\"fragment\":\"composite.fsh\"}]}\n",
                "Example/shaders/composite.fsh", "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }\n"));
        Path extracted;
        try (PackSource.LoadResult loaded = PackSource.loadResult(validZip)) {
            extracted = loaded.shadersDir();
            assertTrue(Files.isRegularFile(extracted.resolve("composite.fsh")),
                    "ZIP shader source was not extracted");
            assertEquals(1L, loaded.programs().stream().map(PackProgram::name).distinct().count(),
                    "ZIP program count");
        }
        assertTrue(!Files.exists(extracted), "ZIP extraction was not cleaned up");

        Path duplicateZip = root.resolve("duplicate.zip");
        writeZip(duplicateZip, Map.of(
                "shaders/shaders.json",
                        "{\"programs\":[{\"name\":\"duplicate\",\"fragment\":\"b.fsh\"},{\"name\":\"duplicate\","
                        + "\"fragment\":\"a.fsh\"}]}\n",
                "shaders/a.fsh", "a\n",
                "shaders/b.fsh", "b\n"));
        try (PackSource.LoadResult loaded = PackSource.loadResult(duplicateZip)) {
            assertEquals(1, loaded.programs().size(), "duplicate program selection");
            assertTrue(loaded.deviations().contains("DUPLICATE_PROGRAM:duplicate"),
                    "duplicate program deviation");
            assertEquals("a.fsh",
                    loaded.shadersDir().relativize(loaded.programs().get(0).fragmentPath())
                            .toString().replace('\\', '/'),
                    "duplicate deterministic source");
        }

        Path unsafeZip = root.resolve("unsafe.zip");
        writeZip(unsafeZip, Map.of(
                "../outside.fsh", "bad\n",
                "shaders/composite.fsh", "good\n"));
        try (PackSource.LoadResult loaded = PackSource.loadResult(unsafeZip)) {
            assertTrue(loaded.programs().isEmpty(), "unsafe ZIP was accepted");
            assertTrue(loaded.deviations().contains("PACK_ARCHIVE_UNSAFE_PATH"),
                    "unsafe ZIP deviation");
        }
    }

    @Test
    void preparedSourceEdges(@TempDir Path root) throws IOException {
        Path shaders = root.resolve("shaders");
        writePreparedSources(shaders);

        try (PackSource.LoadResult loaded = PackSource.loadResult(root)) {
            assertEquals("world0", loaded.selectedVariantFolder(),
                    "prepared directory dimension selection");
            assertEquals("minecraft:overworld", loaded.selectedDimension(),
                    "prepared directory dimension name");
            PackProgram finalProgram = loaded.programs().stream()
                    .filter(programEntry -> programEntry.name().equals("final"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("prepared final source is missing"));
            String prepared = finalProgram.executableFragmentSource();
            assertTrue(prepared != null && prepared.contains("uniform sampler2D colortex0"),
                    "root-relative include was not expanded");
            assertTrue(prepared.contains("uniform sampler2D noisetex"),
                    "active conditional declaration was removed");
            assertTrue(!prepared.contains("inactiveSampler"),
                    "inactive conditional declaration was retained");
            assertEquals(shaders.resolve("tex/noise.png").toAbsolutePath().normalize(),
                    loaded.findNoiseTexture().toAbsolutePath().normalize(),
                    "pack noise resource resolution");

            loaded.selectDimension("custom:moon");
            assertEquals("world0", loaded.selectedVariantFolder(),
                    "custom dimension source fallback");
            assertTrue(loaded.deviations().contains(
                            "DIMENSION_SOURCE_FALLBACK_TO_WORLD0:custom:moon"),
                    "custom dimension fallback deviation");
        }

        verifyPreparedZip(root.resolve("prepared.zip"), shaders);
        verifyMacroNormalization(shaders);
        verifyConverterEdges(shaders);
    }

    /** A world0 final stage that includes a root-relative program file with a conditional, and a noise texture. */
    private static void writePreparedSources(Path shaders) throws IOException {
        Path world0 = shaders.resolve("world0");
        Path program = shaders.resolve("program");
        Files.createDirectories(world0);
        Files.createDirectories(program);
        Files.createDirectories(shaders.resolve("tex"));
        Files.writeString(world0.resolve("final.fsh"), """
                #version 130
                #define FRAGMENT_SHADER
                #define DIMENSION_OVERWORLD
                #include "/program/final.glsl"
                """, StandardCharsets.UTF_8);
        Files.writeString(program.resolve("final.glsl"), """
                #if defined(FRAGMENT_SHADER) && (2 > 1)
                uniform sampler2D colortex0;
                uniform sampler2D noisetex;
                #else
                uniform sampler2D inactiveSampler;
                #endif
                void main() {
                    gl_FragColor = texture2D(colortex0, vec2(0.0)) +
                            texture2D(noisetex, vec2(0.0));
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(shaders.resolve("tex/noise.png"), "fixture-noise",
                StandardCharsets.UTF_8);
    }

    /** The same prepared sources, zipped, expand the same way and clean up their extraction. */
    private static void verifyPreparedZip(Path zip, Path shaders) throws IOException {
        writeZip(zip, Map.of(
                "ShaderPack/shaders/world0/final.fsh",
                Files.readString(shaders.resolve("world0").resolve("final.fsh"), StandardCharsets.UTF_8),
                "ShaderPack/shaders/program/final.glsl",
                Files.readString(shaders.resolve("program").resolve("final.glsl"), StandardCharsets.UTF_8),
                "ShaderPack/shaders/tex/noise.png", "fixture-noise\n"));
        Path extracted;
        try (PackSource.LoadResult loaded = PackSource.loadResult(zip)) {
            extracted = loaded.shadersDir();
            assertEquals("world0", loaded.selectedVariantFolder(),
                    "prepared ZIP dimension selection");
            assertTrue(loaded.programs().stream().anyMatch(
                            programEntry -> programEntry.name().equals("final")
                                    && programEntry.executableFragmentSource().contains("colortex0")),
                    "prepared ZIP source was not expanded");
            assertTrue(loaded.findNoiseTexture() != null,
                    "prepared ZIP noise resource is missing");
        }
        assertTrue(!Files.exists(extracted), "prepared ZIP extraction was not cleaned up");
    }

    @Test
    void glsl130PostConversion() {
        String simple130 = """
                #version 130
                noperspective in vec2 texCoord;
                uniform sampler2D colortex0;
                void main() {
                    gl_FragColor = texture2D(colortex0, texCoord);
                }
                """;
        PostTargetPlan targetPlan = PostTargetPlan.parse("final", simple130).plan();
        UniformRegistry.ProgramInterface interfacePlan = UniformRegistry.planPreparedPost(
                simple130, targetPlan);
        String converted = FragmentConverter.convertPostFragment(
                simple130, null, interfacePlan, targetPlan);
        assertTrue(converted != null, "GLSL 130 post conversion failed");
        assertTrue(converted.contains("layout(location = 0) out vec4 chimeraFragColor0"),
                "GLSL 130 output declaration is missing");
        assertTrue(converted.contains("layout(location = 0) in vec2 texCoord"),
                "GLSL 130 input varying bridge is missing");
        assertTrue(converted.contains("texture(colortex0"),
                "texture2D conversion is missing");
    }

    private static void verifyMacroNormalization(Path shaders) throws IOException {
        Path sourcePath = shaders.resolve("macro.fsh");
        String source = "#version 130\n"
                + "#define MODE 1\n"
                + "#define MODE 1\n"
                + "#define MODE 2\n"
                + "#if MODE == 2\n"
                + "void main() { gl_FragColor = vec4(1.0); }\n"
                + "#endif\n";
        Files.writeString(sourcePath, source, StandardCharsets.UTF_8);
        ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                shaders, sourcePath, source);
        assertTrue(prepared.successful(), "macro normalization failed");
        assertTrue(prepared.source().contains("void main()") && !prepared.source().contains("#define"),
                "the redefined macro chose the branch and left no definition:\n" + prepared.source());
        assertTrue(prepared.deviations().contains("PREPROCESSOR_MACRO_REDEFINED:MODE"),
                "macro redefinition deviation is missing");
    }

    private static void verifyConverterEdges(Path shaders) {
        String simple130 = """
                #version 130
                #extension GL_ARB_shader_texture_lod : enable
                noperspective in vec2 texCoord;
                uniform sampler2D colortex0;
                const int colortex0Format = R11F_G11F_B10F;
                const int gaux0Format = RGBA16F;
                const float shadowDistance = 192.0;
                void main() {
                    gl_FragColor = texture2DLod(colortex0, texCoord, shadowDistance * 0.0);
                }
                """;
        PostTargetPlan target = PostTargetPlan.parse("final", simple130).plan();
        UniformRegistry.ProgramInterface plan = UniformRegistry.planPreparedPost(simple130, target);
        String converted = FragmentConverter.convertPostFragment(
                simple130, shaders.resolve("final.fsh"), plan, target);
        assertTrue(converted != null, "metadata and constant conversion failed");
        assertEquals(1, count(converted, "#version 460"),
                "converter emitted duplicate version directives");
        assertTrue(!converted.contains("#version 130"),
                "converter retained a legacy version directive");
        assertTrue(!converted.contains("GL_ARB_shader_texture_lod"),
                "converter retained the legacy texture-lod extension");
        assertTrue(!converted.contains("colortex0Format") && !converted.contains("gaux0Format"),
                "converter retained pack metadata declarations");
        assertTrue(converted.contains("const float shadowDistance = 192.0;"),
                "converter did not keep the program's own shadow constant");
        assertTrue(converted.contains("layout(location = 0) in vec2 texCoord"),
                "fixed fullscreen varying was not accepted");

        String unsupported = """
                #version 120
                varying vec3 sunVec, upVec;
                void main() {
                    gl_FragColor = vec4(dot(sunVec, upVec));
                }
                """;
        PostTargetPlan unsupportedTarget = PostTargetPlan.parse("final", unsupported).plan();
        UniformRegistry.ProgramInterface unsupportedPlan = UniformRegistry.planPreparedPost(
                unsupported, unsupportedTarget);
        assertTrue(FragmentConverter.convertPostFragment(
                        unsupported, shaders.resolve("final.fsh"), unsupportedPlan, unsupportedTarget) == null,
                "unsupported fullscreen varying was converted");
        assertTrue(PostVertexBridge.postVaryingDeviations(unsupported).contains(
                        "POST_VARYING_UNSUPPORTED:sunVec"),
                "unsupported varying deviation is missing");
        assertTrue(PostVertexBridge.postVaryingDeviations(unsupported).contains(
                        "POST_VARYING_UNSUPPORTED:upVec"),
                "second unsupported varying deviation is missing");
    }

    private static int count(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void writeZip(Path target, Map<String, String> files) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    @Test
    void tokenBoundaries() {
        String source = "// texture2D(gl_FragColor)\n"
                + "const char *name = \"texture2D gl_FragColor\";\n"
                + "vec4 value = texture2D(colortex0, texture2D(colortex0, uv).xy);\n";
        String rewritten = GlslTokenRewriter.rewriteTextureCalls(source);
        assertTrue(rewritten.contains("texture(colortex0, texture(colortex0, uv).xy)"),
                "nested texture rewrite failed");
        assertTrue(rewritten.contains("// texture2D(gl_FragColor)"),
                "comment was rewritten");
        assertTrue(rewritten.contains("\"texture2D gl_FragColor\""),
                "literal was rewritten");
        assertEquals(6L, GlslLexer.lex("1+2 1e-2 3.5 4.0e+1").stream()
                        .filter(GlslLexer.Token::significant).count(),
                "numeric token boundary");
    }

    @Test
    void preparationBudgets() {
        StringBuilder nested = new StringBuilder("#version 120\n#if ");
        for (int i = 0; i < 70; i++) {
            nested.append('(');
        }
        nested.append('1');
        for (int i = 0; i < 70; i++) {
            nested.append(')');
        }
        nested.append("\n#endif\n");
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, nested.toString());
        assertTrue(result.deviations().contains("PREPROCESSOR_CONDITION_UNSUPPORTED"),
                "expression recursion budget was not enforced");
    }
}
