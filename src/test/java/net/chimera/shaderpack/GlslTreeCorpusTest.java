package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate for the lossless tree (TASK-718): every prepared stage of the locked packs and
 * {@code chimera.pack.extra} parses, and prints back byte for byte. So does each converted shader,
 * which adds the Vulkan forms (uniform blocks, binding layouts) the prepared sources rarely use.
 */
@Tag("packs")
final class GlslTreeCorpusTest {
    @Test
    void everyStageParsesAndPrintsAsWritten() {
        List<Path> packs = new ArrayList<>(List.of(TestPacks.complementary(), TestPacks.bsl()));
        packs.addAll(TestPacks.extra());
        List<String> failures = new ArrayList<>();
        int parsed = 0;
        for (Path pack : packs) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack);
            for (PackProgramPlan program : analysis.plan().programs()) {
                String where = pack.getFileName() + " " + program.name() + " ";
                for (Map.Entry<String, PreparedShaderSource> stage : program.stages().entrySet()) {
                    parsed += check(where + stage.getKey(), stage.getValue().source(), failures);
                }
                parsed += check(where + "converted fragment", program.convertedFragment(), failures);
                parsed += check(where + "converted vertex", program.convertedVertex(), failures);
            }
        }
        System.out.printf("[chimera] glsl tree: %d sources parsed, %d failures%n", parsed, failures.size());
        assertTrue(failures.isEmpty(), failures.size() + " sources do not round-trip:\n" + String.join("\n",
                failures.subList(0, Math.min(40, failures.size()))));
    }

    private static int check(String where, String source, List<String> failures) {
        if (source == null || source.isBlank()) return 0;
        try {
            GlslTree tree = GlslTree.parse(source);
            if (!tree.print().equals(source)) failures.add(where + ": prints differently");
            // Statements may fall back to a token run; every stage still has a main the parser found.
            boolean main = tree.externals().stream().anyMatch(external -> external instanceof GlslTree.Function
                    function && function.body() != null && tree.text(function.name()).equals("main"));
            if (!main) failures.add(where + ": no main function parsed");
        } catch (RuntimeException failure) {
            failures.add(where + ": " + failure.getMessage());
        }
        return 1;
    }
}
