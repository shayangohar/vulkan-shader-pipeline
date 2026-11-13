package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A compile error names the translated line, and the authored line that reads the same where one
 * does: RedHat's DIRTY_LENS error reads pack_final.fsh:518, which no author has (TASK-922).
 */
final class CompileErrorLocationTest {
    private static final String TRANSLATED = String.join("\n",
            "#version 450",
            "layout(location = 0) out vec4 chimera_FragData0;",
            "vec3 lenscolor = vec3(1.0, 0.8, 0.6);",
            "vec3 lenscolor = vec3(0.5);",
            "void main() { chimera_FragData0 = vec4(lenscolor, 1.0); }");

    @Test
    void anErrorNamesTheTranslatedLineAndTheOneAuthoredLineLikeIt(@TempDir Path shaders) throws IOException {
        Files.createDirectories(shaders.resolve("lib/post"));
        Files.writeString(shaders.resolve("final.fsh"), "#include \"/lib/post/mainlens.glsl\"\nvoid main() {}\n");
        Files.writeString(shaders.resolve("lib/post/mainlens.glsl"),
                "// lens\nvec3 lenscolor = vec3(1.0, 0.8, 0.6);\n    vec3 lenscolor = vec3(0.5);\n");
        String info = CompileErrorLocation.describe("pack_final.fsh:4: error: 'lenscolor' : redefinition",
                TRANSLATED, shaders);
        assertTrue(info.startsWith("pack_final.fsh:4: error: 'lenscolor' : redefinition"), info);
        assertTrue(info.contains("Translated line pack_final.fsh:4: vec3 lenscolor = vec3(0.5);"), info);
        assertTrue(info.contains("Authored: lib/post/mainlens.glsl:3"), info);
    }

    @Test
    void aLineAFewPlacesHoldNamesEachAndManyNameNone(@TempDir Path shaders) throws IOException {
        Files.writeString(shaders.resolve("a.glsl"), "vec3 lenscolor = vec3(0.5);\n\nvec3 lenscolor = vec3(0.5);\n");
        Files.writeString(shaders.resolve("b.glsl"), "vec3 lenscolor = vec3(0.5);\n");
        assertEquals("a.glsl:1 or a.glsl:3 or b.glsl:1",
                CompileErrorLocation.authoredLine("vec3 lenscolor = vec3(0.5);", shaders));
        Files.writeString(shaders.resolve("c.glsl"), "vec3 lenscolor = vec3(0.5);\n");
        assertNull(CompileErrorLocation.authoredLine("vec3 lenscolor = vec3(0.5);", shaders),
                "past " + CompileErrorLocation.MOST_MATCHES + " places, naming them says nothing");
        String info = CompileErrorLocation.describe("pack_final.fsh:4: error: x", TRANSLATED, shaders);
        assertFalse(info.contains("Authored:"), info);
        assertTrue(info.contains("Translated line"), info);
    }

    @Test
    void aShortOrRewrittenLineNamesNone(@TempDir Path shaders) throws IOException {
        Files.writeString(shaders.resolve("a.glsl"), "}\nvec3 lenscolour = vec3(0.5);\n");
        assertNull(CompileErrorLocation.authoredLine("}", shaders), "too short to tell");
        assertNull(CompileErrorLocation.authoredLine("vec3 lenscolor = vec3(0.5);", shaders), "no such line");
        assertNull(CompileErrorLocation.authoredLine("vec3 lenscolor = vec3(0.5);", null));
    }

    @Test
    void aMessageWithoutALineIsCopiedAsItIs() {
        assertEquals("linker: entry point missing", CompileErrorLocation.describe("linker: entry point missing",
                TRANSLATED, null));
        assertEquals("pack_final.fsh:99: error: past the end", CompileErrorLocation.describe(
                "pack_final.fsh:99: error: past the end", TRANSLATED, null));
        assertEquals("", CompileErrorLocation.describe(null, null, null));
    }
}
