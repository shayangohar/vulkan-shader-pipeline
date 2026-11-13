package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gate for the lossless tree (TASK-718), run per pack by CorpusChecks: every prepared stage of the
 * corpus packs parses, and prints back byte for byte. So does each converted shader,
 * which adds the Vulkan forms (uniform blocks, binding layouts) the prepared sources rarely use.
 */
final class GlslTreeCorpusTest {
    private GlslTreeCorpusTest() {}

    /** One corpus pack's stages and converted shaders (CorpusChecks); returns how many it parsed. */
    static int checkPack(Path pack, PackProbe.Analysis analysis, List<String> failures) {
        List<String> mine = new ArrayList<>();
        int parsed = 0;
        for (PackProgramPlan program : analysis.plan().programs()) {
            String where = pack.getFileName() + " " + program.name() + " ";
            for (Map.Entry<String, PreparedShaderSource> stage : program.stages().entrySet()) {
                parsed += check(where + stage.getKey(), stage.getValue().source(), mine);
            }
            parsed += check(where + "converted fragment", program.convertedFragment(), mine);
            parsed += check(where + "converted vertex", program.convertedVertex(), mine);
        }
        System.out.printf("[chimera] glsl tree %s: %d sources parsed, %d failures%n", pack.getFileName(), parsed,
                mine.size());
        failures.addAll(mine.subList(0, Math.min(40, mine.size())));
        return parsed;
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
