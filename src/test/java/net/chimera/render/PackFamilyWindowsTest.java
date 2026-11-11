package net.chimera.render;

import net.chimera.shaderpack.GeometryOutputPlan;
import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackFamilyWindowsTest {
    /**
     * A run holds the targets its programs store to as colorimgN in the general layout until it
     * ends, so a batch that samples one as colortexN must not share it: batches split on their
     * stored targets as well as on their outputs. Equal outputs alone kept a storing hand batch and
     * a sampling one in one run, where binding the held target throws "Unexpected value:1".
     */
    @Test
    void aStoringBatchNeverSharesARunWithOneThatDoesNot() throws IOException {
        GeometryOutputPlan hand = GeometryOutputPlan.parse("gbuffers_hand", "/* DRAWBUFFERS:04 */\n", Map.of());
        List<PackFamilyWindows.RunKey> batches = List.of(
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(hand, List.of(7)),
                new PackFamilyWindows.RunKey(hand, List.of(7)),
                new PackFamilyWindows.RunKey(hand, List.of()),
                new PackFamilyWindows.RunKey(null, List.of()));
        assertEquals(List.of("0-2", "2-4", "4-5", "5-6"), PackFamilyWindows.runs(batches).stream()
                .map(run -> run[0] + "-" + run[1]).toList());
        assertTrue(PackFamilyWindows.runs(List.of()).isEmpty(), "no batches, no runs");

        // Each batch's key carries the targets its own lane pipeline's program stores to.
        var instructions = MixinBytecode.instructionsThrough(
                MixinBytecode.nativeClass("net/chimera/render/PackFamilyWindows"), "drawFamilyBatches");
        for (String callee : List.of("runKey", "laneWindowOutputs", "lanePipeline", "worldStorageTargets", "runs")) {
            assertTrue(instructions.stream().anyMatch(instruction ->
                    instruction instanceof MethodInsnNode call && call.name.equals(callee)),
                    "drawFamilyBatches must reach " + callee);
        }
        var bindings = MixinBytecode.instructionsThrough(
                MixinBytecode.nativeClass("net/chimera/render/ChimeraMainPass"), "worldStorageTargets");
        assertTrue(bindings.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/chimera/render/PackImageBindings")
                        && call.name.equals("worldStorageTargets")),
                "the pass reads the stored targets the program images record");
    }
}
