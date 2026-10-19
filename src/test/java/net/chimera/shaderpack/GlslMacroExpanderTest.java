package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Macros expand as a C preprocessor (Iris's JCPP) expands them, before any analysis reads the source. */
final class GlslMacroExpanderTest {
    @Test
    void objectMacrosExpandAndTheirDefinitionsLeaveEmptyLines() {
        assertEquals("#version 120\n\nfloat x = 1.0;\n",
                GlslMacroExpander.expand("#version 120\n#define A 1.0\nfloat x = A;\n"));
    }

    @Test
    void functionMacrosTakeExpandedArgumentsAndRescan() {
        assertEquals("\n\nfloat y = ((2.0 + 1.0)*(2.0 + 1.0));\n", GlslMacroExpander.expand(
                "#define SQ(x) ((x)*(x))\n#define TWO 2.0\nfloat y = SQ(TWO + 1.0);\n"));
        assertEquals("\nint a = foo + 1;\n", GlslMacroExpander.expand("#define foo foo + 1\nint a = foo;\n"),
                "a macro never expands inside its own expansion");
    }

    @Test
    void undefAndRedefinitionHoldFromTheirLine() {
        assertEquals("\nint a = 1;\n\n\nint b = 2;\n", GlslMacroExpander.expand(
                "#define V 1\nint a = V;\n#undef V\n#define V 2\nint b = V;\n"));
    }

    @Test
    void aCallAcrossLinesKeepsTheLineCount() {
        String source = "#define ADD(a, b) a + b\nfloat c = ADD(1.0,\n  2.0);\nfloat d;\n";
        String expanded = GlslMacroExpander.expand(source);
        assertTrue(expanded.contains("float c = 1.0 + 2.0"), expanded);
        assertEquals(source.lines().count(), expanded.lines().count(), expanded);
    }

    @Test
    void pastingNamesAndCommentsAndNonCalls() {
        assertEquals("\nint myVar = 1;\n",
                GlslMacroExpander.expand("#define CAT(a, b) a ## b\nint CAT(my, Var) = 1;\n"));
        assertEquals("\nfloat f = 1.0;\n", GlslMacroExpander.expand("#define f(x) x\nfloat f = 1.0;\n"),
                "a function-like name without a call stays");
        assertEquals("\n// A here\n/* A */ int a = 1;\n",
                GlslMacroExpander.expand("#define A 1\n// A here\n/* A */ int a = A;\n"));
        assertEquals("\nint a = 2 - -1;\n", GlslMacroExpander.expand("#define NEG -1\nint a = 2 -NEG;\n"),
                "tokens that would merge stay apart");
        assertEquals("\nfloat x = F(1);\n", GlslMacroExpander.expand("#define F(a, b) a\nfloat x = F(1);\n"),
                "a call with the wrong argument count stays for the compiler to report");
    }

    /** BSL documents its options with "#define FEATURE ..." inside a block comment; JCPP reads that as text. */
    @Test
    void aDirectiveInsideABlockCommentIsText() {
        String source = "#version 120\n/*\n#define FEATURE -> feature is enabled\n*/\n"
                + "#ifdef FEATURE\nfloat on;\n#else\nfloat off;\n#endif\n";
        ShaderSourcePreprocessor.Result result = ShaderSourcePreprocessor.prepare(
                Path.of("."), null, source);
        assertTrue(result.successful() && result.source().contains("float off;")
                && !result.source().contains("float on;"), result.source());
        assertTrue(result.source().contains("#define FEATURE -> feature is enabled"), "the comment stays as written");
    }

    @Test
    void otherDirectivesStayAsWritten() {
        assertEquals("#version 330 core\n#extension GL_ARB_shader_texture_lod : enable\n\nint a = 3;\n",
                GlslMacroExpander.expand("#version 330 core\n#extension GL_ARB_shader_texture_lod : enable\n"
                        + "#define N 3\nint a = N;\n"));
    }
}
