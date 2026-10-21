package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's const directives (ConstDirectiveParser, ProgramSet.locateDirectives). Chimera read them
 * from vertex stages and from programs Iris never loads, in name order, and only when nothing but
 * a // comment followed the semicolon.
 */
final class IrisConstDirectivesTest {
    @Test
    void aLineDeclaresOneDirectiveUpToItsFirstSemicolon() {
        assertEquals(List.of(new IrisConstDirectives.Directive("int", "shadowMapResolution", "2048"),
                        new IrisConstDirectives.Directive("float", "shadowDistance", "128"),
                        new IrisConstDirectives.Directive("bool", "colortex0Clear", "false")),
                IrisConstDirectives.find("""
                        const int shadowMapResolution = 2048; /* any text */ const int ignored = 1;
                            const float shadowDistance= 128 ; // [64 128]
                        // const float shadowDistance = 64.0;
                        float x; const float notAtLineStart = 1.0;
                        const bool colortex0Clear = false;
                        """));
    }

    /**
     * Iris reads shadowcomp, begin and prepare, then every ProgramId (gbuffers, shadow, dh and
     * final), then deferred and composite; a later directive replaces an earlier one.
     */
    @Test
    void laterProgramsInIrisOrderReplaceEarlierDirectives(@TempDir Path shaders) {
        List<PackProgram> programs = List.of(
                program(shaders, "composite", "const int shadowMapResolution = 4096;"),
                program(shaders, "final", "const float shadowDistance = 64.0;"),
                program(shaders, "gbuffers_terrain", "const int shadowMapResolution = 1024;\n"
                        + "const float shadowDistance = 32.0;"),
                program(shaders, "shadowcomp", "const int shadowMapResolution = 512;"),
                new PackProgram("deferred", "void main() {}", shaders.resolve("deferred.fsh"),
                        "const float shadowDistance = 16.0;", shaders.resolve("deferred.vsh")),
                program(shaders, "gbuffers_custom", "const float shadowDistance = 24.0;"));
        PackConfig.ShadowSettings settings = PackConfig.parse(programs, shaders).shadowSettings();
        assertEquals(4096, settings.resolution(), "composite is read after shadowcomp and the gbuffers");
        assertEquals(64.0F, settings.distance(),
                "final is read after the gbuffers; a vertex stage and an unknown program are not read");
    }

    /** Iris reads each directive as the type it declares, and a bool only as exactly true or false. */
    @Test
    void aDirectiveOfAnotherTypeOrValueIsIgnored(@TempDir Path shaders) {
        PackConfig.PackConfigData config = PackConfig.parse(List.of(program(shaders, "composite", """
                const float shadowMapResolution = 4096.0;
                const int shadowDistance = 64;
                const bool colortex2Clear = TRUE;
                const int colortex1Format = RGBA16F; // HDR
                """)), shaders);
        assertTrue(config.shadowSettings().rawValues().isEmpty(), config.shadowSettings().rawValues().toString());
        assertTrue(!config.targetSettings().containsKey(2), "TRUE is no Iris boolean");
        assertEquals(97, config.colortexFormats().get(1), "RGBA16F with a comment after the semicolon");
    }

    private static PackProgram program(Path shaders, String name, String fragment) {
        return new PackProgram(name, fragment, shaders.resolve(name + ".fsh"));
    }
}
