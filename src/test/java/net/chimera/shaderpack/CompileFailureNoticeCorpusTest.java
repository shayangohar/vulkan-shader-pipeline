package net.chimera.shaderpack;

import net.chimera.render.shader.ChimeraShaderLoader;
import net.chimera.render.shader.GlslCompiler;
import net.chimera.shaderpack.options.PendingOptions;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RedHat v34.3.1 with DIRTY_LENS on declares lenscolor twice in final, and Iris refuses the pack
 * for it (TASK-922). Chimera skips final, and its notice's Copy Info names the error, the translated
 * line, and the line the pack wrote. Needs the corpus (check-packs.ps1 -Corpus).
 */
@Tag("packs")
final class CompileFailureNoticeCorpusTest {
    @Test
    void redHatsDirtyLensErrorIsNamedWhereThePackWroteIt() {
        Path pack = TestPacks.extra().stream()
                .filter(candidate -> candidate.getFileName().toString().startsWith("RedHat_v34.3.1"))
                .findFirst().orElse(null);
        assumeTrue(pack != null, "needs RedHat_v34.3.1 in chimera.pack.extra (-Corpus)");
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack, "minecraft:overworld",
                new PendingOptions.Drained(Map.of("DIRTY_LENS", "true"), false))) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack, loaded);
            PackProgramPlan program = analysis.plan().program("final");
            PackPipelines.PreparedPipeline prepared = PackPipelines.prepare(program,
                    analysis.plan().advancedResources(), UniformRegistry.Stage.POST,
                    ChimeraShaderLoader.loadSource("chimera_composite/chimera_composite.vsh"));
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> GlslCompiler.compile("pack_final.fsh", prepared.fragment(),
                            Shaderc.shaderc_glsl_fragment_shader, GlslCompiler::optimize));
            String info = CompileErrorLocation.describe(failure.getMessage(), prepared.fragment(),
                    loaded.shadersDir());
            System.out.println("[chimera] RedHat DIRTY_LENS Copy Info:\n" + info);
            assertTrue(info.contains("'lenscolor' : redefinition"), info);
            assertTrue(info.contains("Translated line pack_final.fsh:"), info);
            // mainlens.glsl declares it at 18 and again at 79, inside the DIRTY_LENS branch.
            assertTrue(info.contains("Authored: lib/effect/visual/lens/mainlens.glsl:18 or "
                    + "lib/effect/visual/lens/mainlens.glsl:79"), info);
            assertEquals("compile error: 'lenscolor' : redefinition", PackPipelines.compileError(failure.getMessage()),
                    "the chat line's reason");
            String warning = PackPipelines.errorSummary(failure.getMessage());
            assertTrue(warning.matches("pack_final\\.fsh:\\d+: error: 'lenscolor' : redefinition \\(\\+1 more\\)"),
                    "the log's one line: " + warning);
        }
    }
}
