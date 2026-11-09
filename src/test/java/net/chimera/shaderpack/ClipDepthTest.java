package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every path to the rasterizer converts GL's clip depth to Vulkan's exactly once when its stages
 * work in GL's range, never when they keep the host's, and only in its last stage (ClipDepth).
 * Converting twice halves every depth a later pass rebuilds; missing it puts the near half of the
 * view in front of the near plane (KNOW-361).
 */
final class ClipDepthTest {
    @Test
    void theWrappersRunTheAuthoredMainThenConvertOnce() {
        String wrapped = ClipDepth.afterEntry("void main() { gl_Position = vec4(1.0); }\n", "chimeraEntry");
        assertTrue(wrapped.startsWith("void chimeraEntry() {"), wrapped);
        assertTrue(wrapped.endsWith("void main() {\n    chimeraEntry();\n    " + ClipDepth.TO_VULKAN + "\n}\n"),
                wrapped);
        assertEquals(1, ClipDepth.count(wrapped));
        String compact = ClipDepth.afterRenamedEntry("void chimeraHandMain() {}\n", "chimeraHandMain");
        assertTrue(compact.endsWith("\nvoid main() { chimeraHandMain(); " + ClipDepth.TO_VULKAN + " }\n"), compact);
        assertEquals(1, ClipDepth.count(compact));
        assertEquals(0, ClipDepth.count(null));
    }

    /**
     * Every corpus program's stages before the rasterizer, as converted: at most one conversion, in
     * the last of them, and exactly one where they work in GL's range: a shadow program, the hand
     * and sky draws (their preamble's chimeraHandProjection or chimeraSkyProjection), and any stage
     * that takes the host's clip position into GL's range (chimeraGlClip). A glint drawn in the
     * hand converts once too.
     */
    @Test
    @Tag("packs")
    void everyCorpusPathConvertsTheClipDepthOnceAtMost() throws Exception {
        List<Path> packs = new ArrayList<>(TestPacks.extra());
        packs.add(TestPacks.complementary());
        packs.add(TestPacks.bsl());
        List<String> failures = new ArrayList<>();
        int paths = 0;
        int converting = 0;
        for (Path pack : packs) {
            for (PackProgramPlan plan : PackProbe.analyze(pack).plan().programs()) {
                UniformRegistry.Stage stage = FamilyAdapterRegistry.stageFor(plan.name());
                if (!plan.executable() || stage == UniformRegistry.Stage.POST || plan.convertedVertex() == null) {
                    continue;
                }
                paths++;
                String failure = pathFailure(plan, stage);
                if (failure != null) failures.add(pack.getFileName() + " " + plan.name() + ": " + failure);
                if (conversions(chain(plan)) == 1) converting++;
            }
        }
        System.out.printf("[chimera] clip depth: %d paths, %d convert in GL's range%n", paths, converting);
        assertTrue(paths > 0 && failures.isEmpty(), String.join("\n", failures));
    }

    /** What is wrong with a program's path to the rasterizer, or null. */
    private static String pathFailure(PackProgramPlan plan, UniformRegistry.Stage stage) {
        List<String> chain = chain(plan);
        int total = conversions(chain);
        if (total > 1) return total + " conversions";
        if (total == 1 && ClipDepth.count(chain.get(chain.size() - 1)) != 1) return "converted before the last stage";
        // Recognised by the projection the stages carry, never by the wrapper that converts.
        boolean glRange = stage.shadowPass() || chain.stream().anyMatch(source -> source.contains("chimeraGlClip(")
                || source.contains("chimeraHandProjection(") || source.contains("chimeraSkyProjection("));
        if (glRange && total != 1) return "works in GL's range and converts " + total + " times";
        if (FamilyAdapterRegistry.isGlintFamily(plan.name())
                && plan.convertedVertex().contains(GlslVertexPreambles.GLINT_VERTEX_PREAMBLE)) {
            int inHand = ClipDepth.count(EntityVertexBridge.handGlintVertex(plan.convertedVertex()));
            if (inHand != 1) return "the glint in the hand converts " + inHand + " times";
        }
        return null;
    }

    /** The converted stages before the rasterizer, in the order GL links them. */
    private static List<String> chain(PackProgramPlan plan) {
        List<String> chain = new ArrayList<>();
        for (String source : new String[] {plan.convertedVertex(), plan.convertedTessControl(),
                plan.convertedTessEvaluation(), plan.convertedGeometry()}) {
            if (source != null) chain.add(source);
        }
        return chain;
    }

    private static int conversions(List<String> chain) {
        return chain.stream().mapToInt(ClipDepth::count).sum();
    }
}
