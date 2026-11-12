package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged 0.3.5 ends the bound pass for a readback (Renderer.flushCmds, a screenshot among them) or
 * a render-target blit and begins MainPass.resumeRenderPass's answer, whose default is the same
 * pass. Chimera's world and output passes that clear would then clear what the frame drew before
 * the split, so each resumes with the twin that loads colour and depth.
 */
final class MainPassResumeTest {
    private static final String TARGETS = "net/chimera/render/MainPassTargets";

    @Test
    void aSplitPassResumesWithItsLoadTwin() throws IOException {
        var main = MixinBytecode.nativeClass("net/chimera/render/ChimeraMainPass");
        assertTrue(MixinBytecode.instructionsThrough(main, "resumeRenderPass").stream().anyMatch(instruction ->
                        instruction instanceof MethodInsnNode call && call.owner.equals(TARGETS)
                                && call.name.equals("resumePass")),
                "ChimeraMainPass overrides MainPass.resumeRenderPass with MainPassTargets.resumePass");

        List<FieldInsnNode> reads = MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(TARGETS), "resumePass")
                .stream().filter(instruction -> instruction.getOpcode() == Opcodes.GETFIELD)
                .map(FieldInsnNode.class::cast).toList();
        // The clearing passes are compared with the bound one; each comparison answers with a twin.
        Set<String> clearing = Set.of("hdrRenderPass", "hdrAuxClearDepthRenderPass", "compositeRenderPass",
                "compositeAuxClearDepthRenderPass");
        Set<String> twins = Set.of("hdrAuxRenderPass", "compositeAuxRenderPass");
        Set<String> read = reads.stream().map(field -> field.name).collect(Collectors.toSet());
        assertTrue(read.containsAll(clearing) && read.containsAll(twins), read.toString());
        assertEquals(Set.of(), read.stream().filter(name -> !clearing.contains(name) && !twins.contains(name))
                .collect(Collectors.toSet()), "resumePass resumes no other pass in place of the bound one");
    }
}
